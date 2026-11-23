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

package net.ccbluex.liquidbounce.utils.render.trajectory

import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleAirStuck
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawBoxSide
import net.ccbluex.liquidbounce.render.drawLineStripAsLines
import net.ccbluex.liquidbounce.render.drawGradientLinesWithWidth
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.stateOrEmpty
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.math.toRadians
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.kotlin.subList
import net.ccbluex.liquidbounce.utils.math.copy
import net.ccbluex.liquidbounce.utils.math.minus
import net.ccbluex.liquidbounce.utils.math.move
import net.ccbluex.liquidbounce.utils.math.scaleMut
import net.ccbluex.liquidbounce.utils.math.set
import net.ccbluex.liquidbounce.utils.math.withLength
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer.Companion.getHypotheticalTrajectory
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.jvm.optionals.getOrNull
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class TrajectoryInfoRenderer @Suppress("LongParameterList") constructor(
    val owner: Entity,
    val icon: ItemStack,
    velocity: Vec3,
    pos: Vec3,
    val trajectoryInfo: TrajectoryInfo,
    val trajectoryType: TrajectoryType,
    /**
     * Only used for rendering. No effect on simulation.
     */
    val type: Type,
    /**
     * The visualization should be what-you-see-is-what-you-get, so we use the actual current position of the player
     * for simulation. Since the trajectory line should follow the player smoothly, we offset it by some amount.
     */
    private val renderOffset: Vec3
) {
    enum class Type {
        /**
         * From the entity holding items.
         *
         * @see [getHypotheticalTrajectory]
         */
        HYPOTHETICAL,

        /**
         * From a moving entity, such as [net.minecraft.world.entity.projectile.Projectile].
         */
        REAL,
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun getHypotheticalTrajectory(
            owner: Entity,
            trajectoryInfo: TrajectoryInfo,
            trajectoryType: TrajectoryType,
            rotation: Rotation,
            icon: ItemStack = ItemStack.EMPTY,
            partialTicks: Float = mc.deltaTracker.getGameTimeDeltaPartialTick(true),
        ): TrajectoryInfoRenderer {
            val yawRadians = rotation.yaw.toRadians()
            val pitchRadians = rotation.pitch.toRadians()

            val interpolatedOffset = owner.interpolateCurrentPosition(partialTicks) - owner.position()

            val pos = Vec3(
                owner.x,
                owner.eyeY - 0.10000000149011612,
                owner.z
            )

            var velocity = Vec3(
                -sin(yawRadians) * cos(pitchRadians).toDouble(),
                -sin((rotation.pitch + trajectoryInfo.roll).toRadians()).toDouble(),
                cos(yawRadians) * cos(pitchRadians).toDouble()
            ).withLength(trajectoryInfo.initialVelocity)

            //While AirStuck is active, this momentum is the residual value before freezing.
            if (trajectoryInfo.copiesPlayerVelocity && !ModuleAirStuck.running) {
                velocity = velocity.add(
                    owner.deltaMovement.x,
                    if (owner.onGround()) 0.0 else owner.deltaMovement.y,
                    owner.deltaMovement.z
                )
            }

            return TrajectoryInfoRenderer(
                owner = owner,
                icon = icon,
                velocity = velocity,
                pos = pos,
                trajectoryInfo = trajectoryInfo,
                trajectoryType = trajectoryType,
                type = Type.HYPOTHETICAL,
                renderOffset = interpolatedOffset.add(-cos(yawRadians) * 0.16, 0.0, -sin(yawRadians) * 0.16)
            )
        }
    }

    private val velocity = velocity.copy() // Used as mutable
    private val pos = pos.copy() // Used as mutable

    private val hitbox = trajectoryInfo.hitbox()
    private val mutableBlockPos = BlockPos.MutableBlockPos()

    fun runSimulation(
        maxTicks: Int,
    ): SimulationResult {
        fun tickVelocity() {
            val blockState = world.getBlockState(mutableBlockPos.set(pos.x, pos.y, pos.z))
            // Check is next position water
            val drag = if (!blockState.fluidState.isEmpty) {
                trajectoryInfo.dragInWater
            } else {
                trajectoryInfo.drag
            }

            velocity.scaleMut(drag).move(y = -trajectoryInfo.gravity)
        }

        val positions = mutableListOf<Vec3>()
        val requiresInitialTickCorrection = this.trajectoryType.requiresInitialTickCorrection

        // Apply first-tick physics to velocity only, mimicking server spawn reset
        if (requiresInitialTickCorrection) {
            tickVelocity()
        }

        // Now start normal simulation, starting from currTicks = 1
        val prevPos = pos.copy()
        var currTicks = if (requiresInitialTickCorrection) 1 else 0

        while (currTicks < maxTicks) {
            if (pos.y < world.minY) {
                break
            }

            val hitResult = checkForHits(prevPos.set(pos), pos.move(velocity))

            if (hitResult != null) {
                hitResult.second?.let {
                    positions += it
                }

                return SimulationResult(hitResult.first, positions)
            }

            tickVelocity()

            // Draw path
            positions += pos.copy()

            currTicks++
        }

        if (positions.isEmpty()) {
            positions += pos
        }

        return SimulationResult(null, positions)
    }

    private fun checkForHits(
        posBefore: Vec3,
        posAfter: Vec3
    ): Pair<HitResult, Vec3?>? {
        val blockHitResult = world.clip(
            ClipContext(
                posBefore,
                posAfter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                owner
            )
        )
        if (blockHitResult.type != HitResult.Type.MISS) {
            return blockHitResult to blockHitResult.location
        }

        val entityHitResult = ProjectileUtil.getEntityHitResult(
            world,
            owner,
            posBefore,
            posAfter,
            hitbox.move(posBefore).expandTowards(posAfter - posBefore).inflate(1.0),
            {
                val canCollide = !it.isSpectator && it.isAlive
                val shouldCollide = it.isPickable || owner !== player && it === player

                return@getEntityHitResult canCollide && shouldCollide && !owner.isPassengerOfSameVehicle(it)
            },
            if (owner is Projectile) ProjectileUtil.computeMargin(owner) else 0f,
        )

        return if (entityHitResult != null && entityHitResult.type != HitResult.Type.MISS) {
            val hitPos = entityHitResult.entity.box.inflate(trajectoryInfo.hitboxRadius).clip(posBefore, posAfter)

            entityHitResult to hitPos.getOrNull()
        } else {
            null
        }
    }

    context(env: WorldRenderEnvironment)
    fun drawTrajectoryForProjectile(
        maxTicks: Int,
        partialTicks: Float,
        trajectoryColor: Color4b,
        blockHitColor: Color4b?,
        entityHitColor: Color4b?,
        tracerStyle: TracerStyle? = null,
    ): SimulationResult {
        val simulationResult = runSimulation(maxTicks)
        drawTrajectoryResult(simulationResult, partialTicks, trajectoryColor, blockHitColor, entityHitColor, tracerStyle)
        return simulationResult
    }

    context(env: WorldRenderEnvironment)
    fun drawTrajectoryResult(
        simulationResult: SimulationResult,
        partialTicks: Float,
        trajectoryColor: Color4b,
        blockHitColor: Color4b?,
        entityHitColor: Color4b?,
        tracerStyle: TracerStyle? = null,
    ) {
        val (landingPosition, positions) = simulationResult

        env.drawTrajectoryForProjectile(positions, trajectoryColor, tracerStyle)

        when (landingPosition) {
            null -> return
            is BlockHitResult -> if (blockHitColor != null) {
                env.renderHitBlockFace(landingPosition, blockHitColor)
            }
            is EntityHitResult -> if (entityHitColor != null) {
                val entities = listOf(landingPosition.entity)

                env.drawHitEntities(entityHitColor, entities, partialTicks)
            }
            else -> error("Unexpected HitResult type: ${landingPosition::class.java.name}")
        }

        if (trajectoryInfo == TrajectoryInfo.POTION && entityHitColor != null) {
            env.drawSplashPotionTargets(landingPosition.location, trajectoryInfo, partialTicks, entityHitColor)
        }

    }

    private fun WorldRenderEnvironment.drawTrajectoryForProjectile(
        positions: List<Vec3>,
        color: Color4b,
        tracerStyle: TracerStyle?,
    ) {
        val renderedPositions = if (tracerStyle == null && positions.size and 1 != 0) positions.subList(1) else positions
        val origin = renderedPositions.firstOrNull() ?: return
        val relativePositions = renderedPositions.mapIndexed { index, point ->
            // Only the launch end follows the interpolated hand. The impact stays on the actual surface.
            val launchBlend = (1.0 - index / 12.0).coerceIn(0.0, 1.0)
            point.subtract(origin).add(renderOffset.scale(launchBlend))
        }

        poseStack.pushPose()
        poseStack.translate(origin.subtract(camera.position()))
        if (tracerStyle == null) {
            // Don't use LineStrip because in batch mode.
            drawLineStripAsLines(color.argb, relativePositions)
        } else {
            drawShimmerTracer(relativePositions, tracerStyle)
        }
        poseStack.popPose()
    }

    context(env: WorldRenderEnvironment)
    fun drawPersistedTrajectory(
        positions: List<Vec3>,
        style: TracerStyle,
        progress: Float,
    ) {
        if (progress >= 1F) return
        val endFade = (1F - ((progress - 0.82F) / 0.18F).coerceIn(0F, 1F)).let { it * it }
        env.drawTrajectoryForProjectile(
            positions,
            style.baseColor.fade(endFade),
            style.copy(tailCutoff = progress).fade(endFade),
        )
    }

    private fun WorldRenderEnvironment.drawShimmerTracer(positions: List<Vec3>, style: TracerStyle) {
        if (positions.size < 2) return

        val smoothPositions = smoothTrajectory(positions)
        if (smoothPositions.size < 2) return

        val segments = ArrayList<Vec3f>((smoothPositions.size - 1) * 2)
        val coreColors = ArrayList<Color4b>((smoothPositions.size - 1) * 2)
        val middleGlowColors = ArrayList<Color4b>((smoothPositions.size - 1) * 2)
        val outerGlowColors = ArrayList<Color4b>((smoothPositions.size - 1) * 2)
        val phase = ((System.currentTimeMillis() % style.cycleMillis) / style.cycleMillis.toDouble()).toFloat()
        val distances = FloatArray(smoothPositions.size)
        for (index in 1 until smoothPositions.size) {
            distances[index] = distances[index - 1] + smoothPositions[index].distanceTo(smoothPositions[index - 1]).toFloat()
        }
        val totalDistance = distances.last().coerceAtLeast(0.001F)

        fun colorAt(index: Int): Color4b {
            val progress = distances[index] / totalDistance
            val wrappedDistance = abs(((progress - phase + 1.5f) % 1f) - 0.5f)
            val shimmer = exp(-wrappedDistance * wrappedDistance / (2f * style.shimmerSize * style.shimmerSize))
            val tailEdge = ((progress - style.tailCutoff) / 0.10F).coerceIn(0F, 1F)
            val tailAlpha = tailEdge * tailEdge * (3F - 2F * tailEdge)
            return style.baseColor.interpolateTo(style.shimmerColor, shimmer.toDouble()).fade(tailAlpha)
        }

        for (index in 0 until smoothPositions.lastIndex) {
            segments += Vec3f(smoothPositions[index])
            segments += Vec3f(smoothPositions[index + 1])
            val start = colorAt(index)
            val end = colorAt(index + 1)
            coreColors += start
            coreColors += end
            middleGlowColors += start.alpha((start.a * style.glowAlpha * 0.72F).toInt().coerceIn(0, 255))
            middleGlowColors += end.alpha((end.a * style.glowAlpha * 0.72F).toInt().coerceIn(0, 255))
            outerGlowColors += start.alpha((start.a * style.glowAlpha * 0.28F).toInt().coerceIn(0, 255))
            outerGlowColors += end.alpha((end.a * style.glowAlpha * 0.28F).toInt().coerceIn(0, 255))
        }

        drawGradientLinesWithWidth(style.glowWidth, segments, outerGlowColors)
        drawGradientLinesWithWidth(maxOf(style.width, style.glowWidth * 0.52F), segments, middleGlowColors)
        drawGradientLinesWithWidth(style.width, segments, coreColors)
    }

    /** Catmull-Rom subdivision removes the visible one-tick corners without moving either endpoint. */
    private fun smoothTrajectory(points: List<Vec3>, subdivisions: Int = 5): List<Vec3> {
        if (points.size < 3) return points
        val result = ArrayList<Vec3>((points.size - 1) * subdivisions + 1)
        for (index in 0 until points.lastIndex) {
            val p0 = points[(index - 1).coerceAtLeast(0)]
            val p1 = points[index]
            val p2 = points[index + 1]
            val p3 = points[(index + 2).coerceAtMost(points.lastIndex)]
            for (step in 0 until subdivisions) {
                val t = step.toDouble() / subdivisions
                val t2 = t * t
                val t3 = t2 * t
                fun component(a: Double, b: Double, c: Double, d: Double) = 0.5 * (
                    2.0 * b + (-a + c) * t +
                        (2.0 * a - 5.0 * b + 4.0 * c - d) * t2 +
                        (-a + 3.0 * b - 3.0 * c + d) * t3
                    )
                result += Vec3(
                    component(p0.x, p1.x, p2.x, p3.x),
                    component(p0.y, p1.y, p2.y, p3.y),
                    component(p0.z, p1.z, p2.z, p3.z),
                )
            }
        }
        result += points.last()
        return result
    }

    @JvmRecord
    data class SimulationResult(
        val hitResult: HitResult?,
        val positions: List<Vec3>,
        val ticksToImpact: Int = positions.size,
        val displayAlpha: Float = 1F,
    )

    data class TracerStyle(
        val baseColor: Color4b,
        val shimmerColor: Color4b,
        val width: Float,
        val glowWidth: Float,
        val glowAlpha: Float,
        val shimmerSize: Float,
        val cycleMillis: Long,
        val tailCutoff: Float = 0F,
    ) {
        fun fade(alpha: Float) = copy(
            baseColor = baseColor.fade(alpha),
            shimmerColor = shimmerColor.fade(alpha),
            glowAlpha = glowAlpha * alpha,
        )
    }
}

private fun WorldRenderEnvironment.drawSplashPotionTargets(
    landingPosition: Vec3,
    trajectoryInfo: TrajectoryInfo,
    partialTicks: Float,
    entityHitColor: Color4b,
) {
    val box: AABB = trajectoryInfo.hitbox(landingPosition).inflate(4.0, 2.0, 4.0)

    val hitTargets =
        world.getEntitiesOfClass(LivingEntity::class.java, box) {
            it.distanceToSqr(landingPosition) <= 16.0 && it.isAffectedByPotions
        }

    drawHitEntities(entityHitColor, hitTargets, partialTicks)
}

private fun WorldRenderEnvironment.drawHitEntities(
    entityHitColor: Color4b,
    entities: List<Entity>,
    partialTicks: Float
) {
    for (entity in entities) {
        if (entity === player) {
            continue
        }

        val pos = entity.interpolateCurrentPosition(partialTicks)

        withPositionRelativeToCamera(pos) {
            drawBox(
                entity
                    .getDimensions(entity.pose)
                    .makeBoundingBox(Vec3.ZERO),
                entityHitColor,
            )
        }
    }
}

private fun WorldRenderEnvironment.renderHitBlockFace(blockHitResult: BlockHitResult, color: Color4b) {
    val currPos = blockHitResult.blockPos
    val currState = currPos.stateOrEmpty

    val bestBox = currState.getShape(world, currPos, CollisionContext.of(player)).toAabbs()
        .filter { blockHitResult.location in it.inflate(0.01).move(currPos) }
        .minByOrNull { it.distanceToSqr(blockHitResult.location) }

    if (bestBox != null) {
        withPositionRelativeToCamera(currPos) {
            drawBoxSide(
                bestBox,
                side = blockHitResult.direction,
                faceColor = color,
            )
        }
    }
}
