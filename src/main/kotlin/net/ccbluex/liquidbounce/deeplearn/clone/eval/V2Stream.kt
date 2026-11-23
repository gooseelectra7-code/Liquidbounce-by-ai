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
package net.ccbluex.liquidbounce.deeplearn.clone.eval

import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The subset of Shard 2.0's per-tick record that a rollout can actually reconstruct.
 *
 * 2.0 collects roughly a hundred fields, most of them about the swing rather than the aim:
 * whether the attack ray hit, where inside the hitbox, how far the miss was, how many ticks until
 * the next attack, which movement keys were down, and — through its ported `AimProcessor` — the
 * rotation expressed in *mouse counts* rather than degrees, using a sensitivity it infers from the
 * running mode of the GCD of consecutive deltas.
 *
 * What is reproduced here is the aim-and-swing core. Everything that depends on state a replay
 * does not carry — movement inputs, physics, ping, the exact hit point inside the hitbox,
 * occlusion — is absent, so this is a **lower bound** on what 2.0 can see. A pass here is
 * necessary, not sufficient.
 */
object V2Stream {

    const val CHANNELS = 9

    /** `delta_dots_yaw` — the yaw step divided by the inferred sensitivity, i.e. mouse counts. */
    const val C_DOTS_YAW = 0
    const val C_DOTS_PITCH = 1

    /** Signed steps in degrees, which the model can derive from the raw yaw/pitch columns. */
    const val C_DYAW = 2
    const val C_DPITCH = 3

    /** `target_aim_angle` / `aim_error`. */
    const val C_AIM_ANGLE = 4
    const val C_TARGET_VALID = 5

    /** `attack_this_tick`. */
    const val C_ATTACK = 6

    /** `ticks_since_attack`, and its forward-looking twin `ticks_to_attack`. */
    const val C_TICKS_SINCE_ATTACK = 7
    const val C_TICKS_TO_ATTACK = 8

    /**
     * Beyond this the crosshair is off the hitbox for any realistic size, so a swing here is the
     * `raytrace_hit = false` case that 2.0 records explicitly.
     */
    private const val ON_TARGET_ANGLE = 3f

    /** Matches `AimProcessor.MAX_SAMPLE_DELTA`: larger steps never enter the sensitivity estimate. */
    private const val MAX_SAMPLE_DELTA = 5f

    /**
     * Rewrites a [CloneEval] stream in 2.0's terms.
     *
     * [gcd] is the sensitivity to divide by. Passing zero falls back to the live one, which is what
     * the plugin would converge on anyway — its estimate is the running mode of the GCD of
     * consecutive deltas, and for any client that emits whole counts that mode *is* the true
     * sensitivity.
     */
    fun of(stream: FloatArray, gcd: Double): FloatArray {
        val divisor = (if (gcd > 0.0) gcd else RotationUtil.gcd).toFloat()
        val ticks = stream.size / CloneEval.CHANNELS
        val out = FloatArray(ticks * CHANNELS)

        var sinceAttack = TICK_LIMIT
        val toAttack = IntArray(ticks) { TICK_LIMIT }
        var next = TICK_LIMIT

        for (i in ticks - 1 downTo 0) {
            if (stream[i * CloneEval.CHANNELS + CloneEval.C_ATTACKED] > 0.5f) {
                next = 0
            } else if (next < TICK_LIMIT) {
                next++
            }
            toAttack[i] = next
        }

        for (i in 0 until ticks) {
            val source = i * CloneEval.CHANNELS
            val target = i * CHANNELS

            val deltaYaw = stream[source + CloneEval.C_DX]
            val deltaPitch = stream[source + CloneEval.C_DY]
            val hasTarget = stream[source + CloneEval.C_HAS_TARGET] > 0.5f
            val attacked = stream[source + CloneEval.C_ATTACKED] > 0.5f

            // Only small steps feed the sensitivity estimate, so a flick contributes no dots — the
            // same blind spot the real AimProcessor has.
            val sampled = abs(deltaYaw) < MAX_SAMPLE_DELTA && abs(deltaPitch) < MAX_SAMPLE_DELTA
            out[target + C_DOTS_YAW] = if (sampled) abs(deltaYaw) / divisor else 0f
            out[target + C_DOTS_PITCH] = if (sampled) abs(deltaPitch) / divisor else 0f

            out[target + C_DYAW] = deltaYaw
            out[target + C_DPITCH] = deltaPitch

            val angle = if (hasTarget) {
                hypot(
                    stream[source + CloneEval.C_DELTA_YAW],
                    stream[source + CloneEval.C_DELTA_PITCH]
                )
            } else {
                0f
            }
            out[target + C_AIM_ANGLE] = angle
            out[target + C_TARGET_VALID] = if (hasTarget) 1f else 0f
            out[target + C_ATTACK] = if (attacked) 1f else 0f

            sinceAttack = if (attacked) 0 else minOf(sinceAttack + 1, TICK_LIMIT)
            out[target + C_TICKS_SINCE_ATTACK] = sinceAttack.toFloat()
            out[target + C_TICKS_TO_ATTACK] = toAttack[i].toFloat()
        }

        return out
    }

    /**
     * How often a swing went out while the crosshair was off the target — 2.0's `raytrace_hit`
     * turned into a single number.
     *
     * Worth watching on its own: an aura that gates the packet on its own hit check produces
     * exactly zero of these, and a player never does.
     */
    fun missRate(stream: FloatArray): Float {
        val ticks = stream.size / CloneEval.CHANNELS
        var attacks = 0
        var misses = 0

        for (i in 0 until ticks) {
            val offset = i * CloneEval.CHANNELS
            if (stream[offset + CloneEval.C_ATTACKED] <= 0.5f) {
                continue
            }

            attacks++
            val hasTarget = stream[offset + CloneEval.C_HAS_TARGET] > 0.5f
            val angle = hypot(
                stream[offset + CloneEval.C_DELTA_YAW],
                stream[offset + CloneEval.C_DELTA_PITCH]
            )
            if (!hasTarget || angle > ON_TARGET_ANGLE) {
                misses++
            }
        }

        return if (attacks == 0) 0f else misses.toFloat() / attacks
    }

    private const val TICK_LIMIT = 100
}
