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

package net.ccbluex.liquidbounce.utils.aiming.projectiles

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.minecraft.world.phys.Vec3
import kotlin.math.pow

/**
 * Exact ballistics of vanilla's arrow-like projectiles (arrows, tridents, crossbow bolts) and a
 * direct-fire solver built on them.
 *
 * ## The step (26.1.2)
 *
 * `AbstractArrow.tick` first moves the arrow along `position -> position + movement`
 * (`stepMoveAndHit`, entities are clipped against that segment), THEN scales the velocity by the
 * inertia (0.99 in air) and THEN subtracts gravity (0.05). After `t` ticks from a launch velocity
 * `V` the arrow therefore sits at
 *
 * ```
 * position(t) = V * S(t) - up * g * (t - S(t)) / (1 - r),   S(t) = (1 - r^t) / (1 - r)
 * ```
 *
 * (`S(t) = t` and a drop of `g * t * (t - 1) / 2` when `r == 1`). This is exact at every whole
 * tick; between ticks the real path is the straight chord while the formula bends, which differs
 * by less than `g / 8` (about 6 mm).
 *
 * The launch velocity is `direction * speed + inherited`: `Projectile.shootFromRotation` adds the
 * shooter's known movement (vertical part only while airborne), the crossbow's plain `shoot`
 * does not.
 *
 * ## The spread
 *
 * `Projectile.getMovementToShoot` (`Projectile.java:136-143`) normalises the direction, adds
 * `random.triangle(0, 0.0172275 * inaccuracy)` to EACH axis and only then scales by the speed:
 * the noise is on the unit vector, so it turns the shot by up to about a degree per axis AND
 * changes its speed by up to 1.7 %. Bows and crossbows shoot with inaccuracy 1
 * (`BowItem.java:41`, `CrossbowItem.performShooting`). It has zero mean, so the solver aims at the
 * nominal direction; [hitChance] flies a fixed set of draws of it to say how much of a target that
 * nominal shot covers.
 */
object ArrowBallistics {

    /**
     * Half-width of the per-axis triangular spread `Projectile.getMovementToShoot` adds to the
     * unit direction, at inaccuracy 1.
     */
    const val SPREAD = 0.0172275

    /**
     * What one flown draw of the spread ended up doing, decided by the caller's per-segment test.
     */
    enum class Outcome {
        /** Nothing so far: keep flying. */
        NONE,

        /** The target was hit. */
        TARGET,

        /** Something else was hit first, or the path is over. */
        BLOCKED,
    }

    /**
     * A fixed set of draws of the spread, as offsets to add to the unit direction (before scaling
     * by the speed, like vanilla). `RandomSource.triangle(mean, spread)` is
     * `mean + spread * (nextDouble() - nextDouble())` (`RandomSource.java:59-61`); the seed is fixed
     * so that a hit chance is a pure function of the geometry and cannot flicker between ticks.
     */
    val spreadSamples: List<Vec3> by lazy {
        val random = java.util.Random(SPREAD_SEED)
        fun triangle() = SPREAD * (random.nextDouble() - random.nextDouble())
        List(SPREAD_SAMPLE_COUNT) { Vec3(triangle(), triangle(), triangle()) }
    }

    /**
     * The share of the spread's draws ([spreadSamples], scaled by [inaccuracy]) of a shot at
     * [direction] that [test] reports as [Outcome.TARGET].
     *
     * Every draw is flown tick by tick like [simulate] and [test] is asked about each segment until
     * it answers something other than [Outcome.NONE] or [maxTicks] is reached (a miss). It also
     * receives a per-draw time shift, spread evenly over `-timingJitter..timingJitter` ticks, so a
     * caller that places a moving target at `tick + shift` folds the uncertainty of WHEN the shot
     * is processed into the same estimate.
     */
    inline fun hitChance(
        physics: Physics,
        spawn: Vec3,
        inherited: Vec3,
        direction: Vec3,
        inaccuracy: Double,
        timingJitter: Double,
        maxTicks: Int,
        test: (tick: Int, from: Vec3, to: Vec3, timeShift: Double) -> Outcome,
    ): Double {
        val samples = spreadSamples
        var hits = 0

        for ((index, noise) in samples.withIndex()) {
            val shift = timingJitter * (2.0 * (index + 0.5) / samples.size - 1.0)
            val velocity = direction.add(noise.scale(inaccuracy)).scale(physics.speed).add(inherited)
            var outcome = Outcome.NONE

            simulate(physics, spawn, velocity, maxTicks) { tick, from, to ->
                outcome = test(tick, from, to, shift)
                outcome == Outcome.NONE
            }

            if (outcome == Outcome.TARGET) {
                hits++
            }
        }

        return hits.toDouble() / samples.size
    }

    /**
     * Speed, gravity and inertia of one shot.
     */
    data class Physics(val speed: Double, val gravity: Double, val drag: Double) {

        /**
         * `S(t)`: how many launch velocities the projectile has travelled after [ticks] ticks.
         */
        fun travelFactor(ticks: Double): Double =
            if (drag == 1.0) ticks else (1.0 - drag.pow(ticks)) / (1.0 - drag)

        /**
         * How far gravity has pulled the projectile down after [ticks] ticks.
         */
        fun drop(ticks: Double, travelFactor: Double = travelFactor(ticks)): Double =
            if (drag == 1.0) {
                gravity * ticks * (ticks - 1.0) / 2.0
            } else {
                gravity * (ticks - travelFactor) / (1.0 - drag)
            }

        companion object {
            @JvmStatic
            fun of(info: TrajectoryInfo) = Physics(info.initialVelocity, info.gravity, info.drag)
        }
    }

    /**
     * A direct-fire solution: the unit launch [direction] and the flight time until the
     * projectile passes the aim point.
     */
    class Solution(val direction: Vec3, val flightTicks: Double) {
        val rotation: Rotation
            get() = Rotation.fromRotationVec(direction)
    }

    /**
     * Solves for the direction that makes a projectile launched from [source] pass through
     * [target] — the aim point as a function of flight ticks, so a moving target is led by
     * exactly the flight time — on the DIRECT (shortest-flight) trajectory.
     *
     * For a flight time `t` the required launch velocity is `(target(t) - source + up * drop(t)) /
     * S(t)`; the shot is possible when that minus [inherited] has exactly the launch speed. The
     * excess falls from infinity as `t` grows, crosses zero at the direct solution and comes back
     * up at the lob, so the first sign change of a forward scan is the direct shot and a bisection
     * pins it.
     *
     * @return null when the target is out of range within [maxFlightTicks]
     */
    fun solveDirect(
        physics: Physics,
        source: Vec3,
        inherited: Vec3,
        maxFlightTicks: Double,
        target: (Double) -> Vec3,
    ): Solution? {
        fun requiredVelocity(ticks: Double): Vec3 {
            val aim = target(ticks)
            val travel = physics.travelFactor(ticks)
            val drop = physics.drop(ticks, travel)

            return Vec3(
                (aim.x - source.x) / travel,
                (aim.y - source.y + drop) / travel,
                (aim.z - source.z) / travel,
            )
        }

        fun excessSpeed(ticks: Double) = requiredVelocity(ticks).subtract(inherited).length() - physics.speed

        var previous = 0.0
        var ticks = SCAN_STEP

        while (ticks <= maxFlightTicks) {
            if (excessSpeed(ticks) <= 0.0) {
                var low = previous
                var high = ticks

                repeat(BISECT_ITERATIONS) {
                    val mid = (low + high) * 0.5

                    if (mid > 0.0 && excessSpeed(mid) > 0.0) {
                        low = mid
                    } else {
                        high = mid
                    }
                }

                val direction = requiredVelocity(high).subtract(inherited).normalize()
                return Solution(direction, high)
            }

            previous = ticks
            ticks += SCAN_STEP
        }

        return null
    }

    /**
     * The velocity the server gives a projectile shot at [rotation] (`Projectile.shootFromRotation`
     * with the spread left out).
     */
    fun launchVelocity(rotation: Rotation, speed: Double, inherited: Vec3): Vec3 =
        rotation.directionVector.scale(speed).add(inherited)

    /**
     * How far outside an entity's bounding box a projectile still hits it, during the projectile's
     * [tick]-th tick (1-based). `ProjectileUtil.computeMargin` is
     * `clamp((tickCount - 2) / 20, 0, 0.3)`, and `ServerLevel.tickNonPassenger` increments
     * `tickCount` BEFORE calling `tick`, so the first tick sees `tickCount == 1`: no margin at all
     * for the first two ticks, the full 0.3 only from the eighth. A point-blank shot has to hit the
     * bare box.
     */
    fun entityMargin(tick: Int): Double = ((tick - 2) / 20.0).coerceIn(0.0, MAX_ENTITY_MARGIN)

    /**
     * Steps a projectile exactly as vanilla does — move, then inertia, then gravity — and hands
     * every tick's segment to [step] (1-based tick, segment start, segment end). Stops when [step]
     * returns false or after [maxTicks].
     */
    inline fun simulate(
        physics: Physics,
        start: Vec3,
        velocity: Vec3,
        maxTicks: Int,
        step: (tick: Int, from: Vec3, to: Vec3) -> Boolean,
    ) {
        var position = start
        var motion = velocity

        for (tick in 1..maxTicks) {
            val next = position.add(motion)

            if (!step(tick, position, next)) {
                return
            }

            position = next
            motion = motion.scale(physics.drag).subtract(0.0, physics.gravity, 0.0)
        }
    }

    private const val SCAN_STEP = 0.25
    private const val BISECT_ITERATIONS = 40
    private const val MAX_ENTITY_MARGIN = 0.3

    /**
     * Draws per estimate. 32 flown paths against one box cost well under a millisecond and put
     * the estimate within about +-0.09 of the truth (binomial), enough for a release threshold.
     */
    const val SPREAD_SAMPLE_COUNT = 32
    private const val SPREAD_SEED = 0x41524F57L

}
