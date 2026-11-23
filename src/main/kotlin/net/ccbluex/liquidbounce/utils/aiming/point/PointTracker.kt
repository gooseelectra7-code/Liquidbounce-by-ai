/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.utils.aiming.point

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.point.exempts.ExemptBestHitVector
import net.ccbluex.liquidbounce.utils.aiming.point.exempts.ExemptBoxPart
import net.ccbluex.liquidbounce.utils.aiming.point.exempts.ExemptContext
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorDelay
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorGaussian
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorLazy
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorJumpLead
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorKalmanPerlin
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorLearned
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorMotionLead
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorRandomJump
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorStayInHitbox
import net.ccbluex.liquidbounce.utils.aiming.point.features.PointProcessorSticky
import net.ccbluex.liquidbounce.utils.aiming.utils.projectPointsOnBox
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.math.getNearestPoint
import net.ccbluex.liquidbounce.utils.math.pointAtProportion
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs

class PointTracker(val parent: EventListener) : ValueGroup("AimPoint"), EventListener {

    override fun parent() = parent

    /**
     * Selection strategy for the initial aim point inside the target's bbox, BEFORE the processor
     * chain runs. [OPTIMAL] keeps the closest projected point (the classic 1.8-style "shortest swing"
     * pick). [CENTERED_RANDOM] biases a random point toward the box center via the central-limit
     * trick (mean of N uniform samples). [FULL_RANDOM] picks uniformly with a small edge margin so
     * we never aim flush against a face.
     */
    private val aimPointMode by enumChoice("Mode", AimPointMode.OPTIMAL)
    private val centeredRandomBias by int("CenteredRandomBias", 3, 1..10)
    private val fullRandomEdgeMargin by float("FullRandomEdgeMargin", 0.05f, 0.0f..0.49f, "bbox")

    private val predicateBoxParts by multiEnumChoice<ExemptBoxPart>("ExemptBoxParts")
    private val predicateBestHitVector = tree(ExemptBestHitVector(this))

    /**
     * This introduces a layer of randomness to the point tracker. A gaussian distribution is being used to
     * calculate the offset.
     */
    private val gaussian = tree(PointProcessorGaussian(this))

    /**
     * This will allow the point to stay at a certain position when the minimum threshold is not reached.
     */
    private val lazy = tree(PointProcessorLazy(this))

    /**
     * This will allow the point to be delayed until the ticks expire.
     */
    private val delay = tree(PointProcessorDelay(this))

    /**
     * Histogram-based aim-point sampler — biases the resolved point toward cells where past
     * attacks landed (recording is done elsewhere; see `LearnedAimRecorder` debug-recorder mode).
     * Reads situation from [PointTrackerContext].
     */
    private val learned = tree(PointProcessorLearned(this))

    /**
     * Replaces the resolved point with a random lattice cell inside the bbox (with edge margin
     * and recent-pick avoidance). When enabled, runs last and overrides every other processor.
     */
    private val randomJump = tree(PointProcessorRandomJump(this))

    /**
     * Shifts the aim point along the box's vertical axis when either the player or the target
     * has nonzero Y velocity (jumping/falling), so the crosshair leads the moving party instead
     * of trailing them.
     */
    private val jumpLead = tree(PointProcessorJumpLead(this))

    /**
     * Shifts the aim point along the box's XZ plane toward the target's horizontal motion vector
     * (relative to self by default), so the crosshair leads a strafing opponent — the box itself
     * is not extrapolated; only the chosen point inside it.
     */
    private val motionLead = tree(PointProcessorMotionLead(this))

    /**
     * Holds the previously-resolved aim point for a short window so the crosshair doesn't
     * micro-jump every tick to whichever sub-cell is currently geometrically optimal. Reduces
     * the "tick-perfect re-pick" signature visible to behaviour classifiers.
     */
    private val sticky = tree(PointProcessorSticky(this))

    /** Smooths box-relative point coordinates and adds continuous, bounded Perlin wandering. */
    private val kalmanPerlin = tree(PointProcessorKalmanPerlin(this))

    /**
     * Snaps the resolved aim point onto whatever the current crosshair-ray already hits inside
     * the bbox, so the head doesn't micro-correct when it's already on target. Must run last.
     */
    private val stayInHitbox = tree(PointProcessorStayInHitbox(this))

    private val processors = arrayOf(
        delay, lazy, gaussian, learned, randomJump,
        jumpLead, motionLead, sticky, kalmanPerlin, stayInHitbox,
    )

    /**
     * The point tracker is being used to track a certain point of an entity.
     *
     * @param entity The entity we want to track.
     */
    fun findPoint(eyes: Vec3, entity: Entity, ticks: Int = 0): PointInsideBox {
        PointTrackerContext.lastEntity = entity
        PointTrackerContext.lastEyes = eyes

        // Predict target position
        val targetPos = PositionExtrapolation.getBestForEntity(entity)
            .getPositionInTicks(ticks.toDouble())

        // Project points onto box
        val box = entity.getBoundingBoxAt(targetPos)
            // Support [ModuleHitbox]
            .inflate(entity.pickRadius.toDouble())
        val points = box.getPoints(eyes)

        val bestHitVector = points.minByOrNull { it.distanceToSqr(eyes) }
            ?: box.getPseudoClosest(eyes)
        val worstHitVector = points.maxByOrNull { it.distanceToSqr(eyes) }
            ?: box.getPseudoFurthest(eyes)

        // Filter exempts
        val predicateContext = ExemptContext(box, bestHitVector, worstHitVector)
        val predicates = predicateBoxParts + predicateBestHitVector
        val pointsWithExempts = points.filter { point ->
            predicates.none { predicate -> predicate.predicate(predicateContext, point) }
        }

        parent.debugGeometry("Points") {
            ModuleDebug.DebugCollection(points.map { point ->
                val percentage = calculateDistancePercentage(point, eyes, bestHitVector, worstHitVector)
                val color = if (point !in pointsWithExempts) {
                    Color4b.MAGENTA
                } else {
                    Color4b.GREEN.interpolateTo(Color4b.RED, percentage)
                }.fade(1.0f - percentage.toFloat())
                ModuleDebug.DebuggedPoint(point, color, 0.05)
            })
        }

        val pos = when (aimPointMode) {
            AimPointMode.OPTIMAL -> pointsWithExempts.minByOrNull { it.distanceToSqr(eyes) } ?: bestHitVector
            AimPointMode.CENTERED_RANDOM -> sampleInBox(box, biased = true)
            AimPointMode.FULL_RANDOM -> sampleInBox(box, biased = false)
        }
        var point = PointInsideBox(pos, box)
        for (processor in processors) {
            if (processor.enabled) {
                point = processor.process(point)
            }
        }
        PointTrackerContext.lastResolvedPoint = point
        return point
    }

    private fun AABB.getPoints(eyes: Vec3) = buildList {
        projectPointsOnBox(eyes, this@getPoints) { point ->
            add(point)
        }
    }

    private var heldSampleProp: Vec3? = null
    private var heldSampleUntilTick = Int.MIN_VALUE

    /**
     * Random aim point that is HELD for a short tick window instead of re-rolled on every call.
     * Re-sampling each tick made the resolved point jump across the whole hitbox every tick, so
     * the rotation engine chased a target that never settled — the crosshair "goes strange / never
     * locks on". This was only masked when [PointProcessorRandomJump] (which holds its own point)
     * ran on top; turning RandomJump off exposed it. The sample is stored as box-relative
     * proportions so it keeps tracking the moving target between re-rolls.
     */
    private fun sampleInBox(box: AABB, biased: Boolean): Vec3 {
        val now = mc.player?.tickCount ?: 0
        var prop = heldSampleProp
        if (prop == null || now >= heldSampleUntilTick) {
            prop = sampleProportions(biased)
            heldSampleProp = prop
            heldSampleUntilTick = now + (SAMPLE_HOLD_MIN..SAMPLE_HOLD_MAX).random()
        }
        return box.pointAtProportion(prop.x, prop.y, prop.z)
    }

    private fun sampleProportions(biased: Boolean): Vec3 {
        val rng = ThreadLocalRandom.current()
        val biasN = centeredRandomBias
        val margin = fullRandomEdgeMargin.toDouble()
        fun frac(): Double {
            if (biased) {
                var sum = 0.0
                repeat(biasN) { sum += rng.nextDouble() }
                return sum / biasN
            }
            return margin + rng.nextDouble() * (1.0 - 2.0 * margin)
        }
        return Vec3(frac(), frac(), frac())
    }

    private fun AABB.getPseudoClosest(eyes: Vec3) = getNearestPoint(eyes)

    private fun AABB.getPseudoFurthest(eyes: Vec3) = Vec3(
        farthestAxis(eyes.x, minX, maxX),
        farthestAxis(eyes.y, minY, maxY),
        farthestAxis(eyes.z, minZ, maxZ),
    )

    private fun farthestAxis(value: Double, min: Double, max: Double): Double {
        val distToMin = abs(value - min)
        val distToMax = abs(value - max)
        return if (distToMin > distToMax) min else max
    }

    // For debug visuals
    private fun calculateDistancePercentage(point: Vec3, eyes: Vec3, bestHitVector: Vec3,
                                            worstHitVector: Vec3
    ): Double {
        val pointDistance = point.distanceTo(eyes)
        val bestDistance = bestHitVector.distanceTo(eyes)
        val worstDistance = worstHitVector.distanceTo(eyes)
        return ((pointDistance - bestDistance) / (worstDistance - bestDistance)).coerceIn(0.0, 1.0)
    }

    enum class AimPointMode(override val tag: String) : Tagged {
        OPTIMAL("Optimal"),
        CENTERED_RANDOM("CenteredRandom"),
        FULL_RANDOM("FullRandom"),
    }

    private companion object {
        const val SAMPLE_HOLD_MIN = 5
        const val SAMPLE_HOLD_MAX = 9
    }

}
