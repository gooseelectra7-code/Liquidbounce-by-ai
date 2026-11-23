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

package net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes.grim

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/**
 * A flat, fixed horizontal speed with no bunny-hopping and no acceleration - it sets the same
 * capped velocity every tick and hard-clamps it, so a step, a slab or a corner cannot push it over
 * the limit.
 *
 * The reason a normal speed spikes on steps is that stepping up briefly changes the ground/friction
 * state, and any mode that accelerates or re-applies a jump-off boost recomputes from that changed
 * base. This mode never accumulates: it overwrites the horizontal velocity to exactly [speed] in the
 * input direction each tick (strength 1.0 drops the previous velocity entirely), then clamps again
 * right before the movement is sent, in case another feature nudged it. The result is a constant
 * cap that a threshold check (the NCP layer here) measures the same on flat ground and on stairs.
 *
 * On the user's HvH server (GrimProbe, 2026-08-22) a ground horizontal of 1.2 b/t flagged and ~1.1
 * passed, on both the Grim and the NCP layer, so keep [speed] a little under that.
 *
 * @anticheat Grim
 * @note No jump is forced, so this holds altitude on flat ground; it is meant for steady travel /
 *       positioning, not bhop distance.
 */
class SpeedGrimFixed(parent: ModeValueGroup<*>) : Mode("GrimFixed") {

    override val parent: ModeValueGroup<*> = parent

    // Grim predicts ground movement from friction far more tightly than air movement (air has the
    // 0.02 control uncertainty), and the NCP threshold differs too, so the two get their own cap.
    private val groundSpeed by float("GroundSpeed", 0.8f, 0.8f..1.1f)
    private val airSpeed by float("AirSpeed", 1.0f, 0.3f..2.0f)
    private val onlyOnGround by boolean("OnlyGround", false)

    /** The cap that applies to the state the player is in right now. */
    private val limit: Double
        get() = (if (player.onGround()) groundSpeed else airSpeed).toDouble()

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!player.moving || (onlyOnGround && !player.onGround())) {
            return@handler
        }

        // Overwrite, do not accelerate: strength 1.0 discards the prior horizontal velocity so the
        // speed can never ratchet up over successive ticks or after a step.
        player.deltaMovement = player.deltaMovement.withStrafe(speed = limit, strength = 1.0)
        clampHorizontal()
    }

    /**
     * Second clamp in the pre-move window: the step-up happens inside the movement tick, so catching
     * any residual overshoot here is what keeps a stair from reading as a speed burst.
     */
    @Suppress("unused")
    private val moveHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        if (event.state == EventState.PRE) {
            clampHorizontal()
        }
    }

    private fun clampHorizontal() {
        val cap = limit
        val v = player.deltaMovement
        val h = hypot(v.x, v.z)
        if (h > cap) {
            val scale = cap / h
            player.deltaMovement = Vec3(v.x * scale, v.y, v.z * scale)
        }
    }
}
