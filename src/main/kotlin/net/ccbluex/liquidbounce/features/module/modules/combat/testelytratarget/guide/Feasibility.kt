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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide

import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.TargetFrame
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.WingPhysics
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.world.phys.Vec3

/**
 * How much clear air there is under a point. Supplied by the caller so this file never touches the
 * world: the module passes a probe backed by real block collisions, the tests pass a flat plane.
 */
fun interface TerrainProbe {
    /** Blocks of free space below [point]; a large value when there is nothing below at all. */
    fun clearanceBelow(point: Vec3): Double
}

/** Our state and the target's predicted track, as the feasibility filter needs them. */
data class FeasibilityInput(
    val eye: Vec3,
    val velocity: Vec3,
    val targetFrames: List<TargetFrame>,
    val targetAimHeight: Double,
    val minReach: Double,
    val maxReach: Double,
    val gravity: Double = WingPhysics.DEFAULT_GRAVITY,
)

/**
 * @param horizon how many ticks to look ahead
 * @param minAltitude clearance below which a rollout counts as flying into the ground
 * @param avoidVoid whether falling out of the world is treated as a rejection
 * @param turnTicks how many ticks the rotation system needs to actually reach the commanded angle;
 *   the rollout slews towards the command over that many ticks instead of snapping to it
 */
data class FeasibilitySettings(
    val horizon: Int = 30,
    val minAltitude: Double = 4.0,
    val avoidVoid: Boolean = true,
    val turnTicks: Int = 1,
)

/**
 * The outcome of rolling one candidate rotation forward.
 *
 * @param ticksToBand ticks until the target first falls inside the attack band, or [NEVER]
 * @param ticksInBand how many of the rolled-forward ticks have the target inside the band; this is
 *   the module's actual objective, so it is what ranks otherwise-equal candidates
 * @param minClearance the least clear air under the path
 * @param closestApproach the smallest distance to the target reached anywhere along the path. This
 *   is what says whether a chase is making progress at all when the band is still out of reach, and
 *   without it a rollout that never arrives is indistinguishable from one that never gets closer
 */
data class Rollout(
    val rotation: Rotation,
    val positions: List<Vec3>,
    val ticksToBand: Int,
    val ticksInBand: Int,
    val minClearance: Double,
    val closestApproach: Double,
    val safe: Boolean,
) {
    companion object {
        const val NEVER = Int.MAX_VALUE
    }
}

/**
 * The safety net between a guidance command and the wing.
 *
 * The guidance law knows about the target and nothing else. It will happily fly a perfect intercept
 * straight into a hillside, and on a dive at a grounded opponent it will do exactly that. So every
 * command is rolled forward through the real physics before it is used, and rejected if it ends in
 * terrain or in the void.
 *
 * What it is *not* is a search. The module this replaces chose its heading by scoring a fan of
 * candidate rollouts every tick, which is expensive and produces a heading that jumps whenever two
 * candidates trade places. Here the guidance law decides, and the rollout only gets a veto; the
 * small fan of alternatives is consulted only on the ticks where the veto actually fires.
 */
object Feasibility {

    /**
     * Pitch offsets tried when the commanded path is unsafe, in the order they are tried: pull up
     * first and hardest, because every rejection this filter exists to catch is a variation on
     * flying into the ground.
     */
    private val RECOVERY_PITCH_OFFSETS = doubleArrayOf(-20.0, -35.0, -50.0, -10.0, -65.0, -80.0)

    /** Yaw offsets tried if no amount of pulling up clears the terrain. */
    private val RECOVERY_YAW_OFFSETS = doubleArrayOf(35.0, -35.0, 70.0, -70.0)

    /**
     * Rolls [rotation] forward and reports what happens.
     *
     * The rotation is slewed towards the command over [FeasibilitySettings.turnTicks] rather than
     * applied instantly, because that is what the rotation system will really do: with a smoothed
     * aim the wing cannot snap to a new angle, and a rollout that pretends otherwise would clear a
     * dive the aircraft has no time to pull out of.
     */
    @Suppress("MagicNumber")
    fun rollout(
        rotation: Rotation,
        from: Rotation,
        input: FeasibilityInput,
        settings: FeasibilitySettings,
        probe: TerrainProbe,
        boostTicks: Int = 0,
    ): Rollout {
        var position = input.eye
        var velocity = input.velocity
        var minClearance = Double.MAX_VALUE
        var ticksToBand = Rollout.NEVER
        var ticksInBand = 0
        var closestApproach = Double.MAX_VALUE
        var safe = true

        val horizon = minOf(settings.horizon, input.targetFrames.size)
        val positions = ArrayList<Vec3>(horizon)
        val turnTicks = settings.turnTicks.coerceAtLeast(1)

        for (tick in 0 until horizon) {
            val progress = ((tick + 1).toDouble() / turnTicks).coerceAtMost(1.0)
            val stepRotation = Rotation(
                yaw = from.yaw + shortestYawDelta(from.yaw, rotation.yaw) * progress.toFloat(),
                pitch = from.pitch + (rotation.pitch - from.pitch) * progress.toFloat(),
            )
            val look = WingPhysics.look(stepRotation.yaw, stepRotation.pitch)

            if (tick < boostTicks) {
                velocity = WingPhysics.boost(velocity, look)
            }
            velocity = WingPhysics.step(velocity, look, stepRotation.pitch, input.gravity)
            position = position.add(velocity)
            positions.add(position)

            val clearance = probe.clearanceBelow(position)
            minClearance = minOf(minClearance, clearance)
            safe = safe &&
                clearance >= settings.minAltitude &&
                !(settings.avoidVoid && position.y < VOID_Y)

            val aim = input.targetFrames[tick].position.add(0.0, input.targetAimHeight * 0.5, 0.0)
            val distance = position.distanceTo(aim)
            closestApproach = minOf(closestApproach, distance)

            if (distance in input.minReach..input.maxReach) {
                ticksInBand++
                ticksToBand = minOf(ticksToBand, tick)
            }
        }

        return Rollout(rotation, positions, ticksToBand, ticksInBand, minClearance, closestApproach, safe)
    }

    /**
     * Returns the commanded rotation if its path is safe, otherwise the first alternative that is.
     *
     * Alternatives are tried in a fixed order rather than scored, so the answer is deterministic and
     * the recovery does not oscillate between two equally-rated escapes on consecutive ticks. If
     * nothing clears, the steepest climb is returned: at that point the target no longer matters and
     * the only job left is not hitting the ground.
     */
    @Suppress("LongParameterList")
    fun resolve(
        commanded: Rotation,
        from: Rotation,
        input: FeasibilityInput,
        settings: FeasibilitySettings,
        probe: TerrainProbe,
        boostTicks: Int = 0,
    ): Rollout {
        val direct = rollout(commanded, from, input, settings, probe, boostTicks)
        if (direct.safe) {
            return direct
        }

        for (offset in RECOVERY_PITCH_OFFSETS) {
            val candidate = Rotation(commanded.yaw, clampPitch(commanded.pitch + offset.toFloat()))
            val result = rollout(candidate, from, input, settings, probe, boostTicks)
            if (result.safe) {
                return result
            }
        }

        for (yawOffset in RECOVERY_YAW_OFFSETS) {
            val candidate = Rotation(commanded.yaw + yawOffset.toFloat(), clampPitch(commanded.pitch - 35f))
            val result = rollout(candidate, from, input, settings, probe, boostTicks)
            if (result.safe) {
                return result
            }
        }

        val lastResort = Rotation(commanded.yaw, clampPitch(commanded.pitch - 80f))
        return rollout(lastResort, from, input, settings, probe, boostTicks)
    }

    /**
     * Whether spending a rocket actually buys anything.
     *
     * The two futures are compared directly rather than trusted to a rule of thumb about speed,
     * because a boost that only makes us faster without shortening the intercept is a boost that
     * will overshoot, which is the opposite of what this module is for.
     *
     * Three cases, and the third is the one that matters in practice. When both futures reach the
     * band, the boost has to arrive materially sooner. When only the boosted one arrives, it has
     * obviously earned itself. When NEITHER arrives - which is the normal state of affairs the
     * moment the target is further away than a thirty-tick horizon - the question is not arrival
     * but progress, so the closest approach is compared instead. Requiring band entry in that case
     * would refuse every rocket at exactly the range where rockets are what the chase is for.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun boostPaysOff(
        commanded: Rotation,
        from: Rotation,
        input: FeasibilityInput,
        settings: FeasibilitySettings,
        probe: TerrainProbe,
        boostTicks: Int,
        gainTicks: Int,
    ): Boolean {
        if (boostTicks <= 0) {
            return false
        }

        val without = rollout(commanded, from, input, settings, probe)
        val with = rollout(commanded, from, input, settings, probe, boostTicks)

        if (!with.safe) {
            return false
        }

        val bothArrive = without.ticksToBand != Rollout.NEVER && with.ticksToBand != Rollout.NEVER
        if (bothArrive) {
            return without.ticksToBand - with.ticksToBand >= gainTicks
        }
        if (with.ticksToBand != Rollout.NEVER) {
            return true
        }
        if (without.ticksToBand != Rollout.NEVER) {
            return false
        }

        return without.closestApproach - with.closestApproach >= CLOSING_GAIN_BLOCKS
    }

    /**
     * How much nearer a rocket must bring the predicted closest approach before it is worth firing,
     * when the target is too far for either future to reach the band inside the horizon.
     */
    private const val CLOSING_GAIN_BLOCKS = 2.0

    /** Below this there is nothing left to land on. */
    private const val VOID_Y = -64.0

    private fun clampPitch(pitch: Float): Float = pitch.coerceIn(-90f, 90f)

}
