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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.drive

import net.ccbluex.liquidbounce.features.module.modules.movement.elytrafly.ModuleElytraFly
import net.ccbluex.liquidbounce.features.module.modules.movement.elytrafly.modes.ElytraFlyModeVanilla
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The FullHvH flight model.
 *
 * In this mode we are not really flying an elytra: ElytraFly's Vanilla mode overwrites the velocity
 * outright, giving a constant speed along the movement direction. That makes the aircraft holonomic,
 * able to go any direction at any moment, with no energy to manage and nothing a firework could buy.
 *
 * The velocity is written here rather than steered into existence. The obvious alternative - point
 * the camera and let ElytraFly's own `withStrafe` do the work - was tried first and is wrong twice
 * over: `withStrafe` reads the REAL `player.yRot`, so it forces a visible camera exactly where the
 * point of the mode is that nothing visible happens, and the commanded yaw only reaches that field
 * through a render-time write, arriving a tick late and, at low frame rates, only partly. Writing
 * the velocity after ElytraFly has had its turn removes the camera from the control path entirely
 * and makes the direction exact.
 *
 * ElytraFly is still taken into custody and still supplies the speed budget, so its settings remain
 * the knobs a user tunes; what it no longer supplies is the heading.
 */
object HolonomicDriver {

    /**
     * Blocks per tick of closing speed commanded per block of station error.
     *
     * This is the coefficient that makes the mode able to stop. At a constant speed an aircraft can
     * only ever fly past whatever it is chasing, so holding station means matching the target's
     * velocity and adding a correction proportional to how wrong the range is: far away that
     * correction saturates and we chase flat out, and as the gap closes it decays to nothing and we
     * simply fly alongside.
     */
    private const val APPROACH_GAIN = 0.35

    private var held = false
    private var appliedModeTag: String? = null
    private var savedEnabled = false
    private var savedModeTag: String? = null
    private var savedSpeedEnabled = true

    /**
     * The velocity that holds station on a target.
     *
     * @param direction the guidance direction, which already points outwards when we are inside the
     *   standoff and inwards or tangentially when we are outside it
     * @param range current distance to the target
     * @param standoffRange the distance we are trying to hold
     * @param targetVelocity what the target is doing, which is the velocity we converge on
     */
    @Suppress("LongParameterList")
    fun stationKeepingVelocity(
        direction: Vec3,
        range: Double,
        standoffRange: Double,
        targetVelocity: Vec3,
        maxHorizontal: Double,
        maxVertical: Double,
        verticalGain: Double,
    ): Vec3 {
        val error = abs(range - standoffRange)
        val closing = (error * APPROACH_GAIN).coerceIn(0.0, maxHorizontal)
        val desired = targetVelocity.add(direction.normalize().scale(closing))

        val horizontal = hypot(desired.x, desired.z)
        val scale = if (horizontal > maxHorizontal && horizontal > 0.0) maxHorizontal / horizontal else 1.0

        return Vec3(
            desired.x * scale,
            (desired.y * verticalGain).coerceIn(-maxVertical, maxVertical),
            desired.z * scale,
        )
    }

    /**
     * Writes the commanded velocity.
     *
     * Runs from a handler ordered after ElytraFly, so this is the last word. Safe to overwrite what
     * ElytraFly wrote: that mode exists to set a flat speed along the player's own input, which is
     * exactly the behaviour being replaced here.
     */
    fun applyVelocity(velocity: Vec3) {
        player.deltaMovement = velocity
    }

    /**
     * Takes charge of ElytraFly, remembering enough to put it back exactly as it was.
     *
     * `Speed` has to be switched on as well as the mode: the Vanilla mode's tick is only called
     * while that group is enabled, and its speed values are the budget this driver flies within.
     */
    fun claim() {
        if (held) {
            return
        }

        savedEnabled = ModuleElytraFly.enabled
        savedModeTag = ModuleElytraFly.modes.activeMode.tag
        savedSpeedEnabled = ModuleElytraFly.Speed.enabled

        // activeMode has a private setter; the tag-based setter is the public way in, and it is a
        // no-op when the mode is already selected, so this costs nothing if ElytraFly was already
        // set up the way we want it.
        ModuleElytraFly.modes.setByString(ElytraFlyModeVanilla.tag)
        ModuleElytraFly.Speed.enabled = true
        if (!ModuleElytraFly.enabled) {
            ModuleElytraFly.enabled = true
        }

        appliedModeTag = ModuleElytraFly.modes.activeMode.tag
        held = true
    }

    /** Puts ElytraFly back the way it was found. Idempotent. */
    fun release() {
        if (!held) {
            return
        }

        held = false
        val mode = savedModeTag
        if (mode != null) {
            ModuleElytraFly.modes.setByString(mode)
        }
        ModuleElytraFly.Speed.enabled = savedSpeedEnabled
        if (ModuleElytraFly.enabled != savedEnabled) {
            ModuleElytraFly.enabled = savedEnabled
        }
        appliedModeTag = null
    }

    /**
     * Drops custody without restoring anything if the user has meanwhile changed ElytraFly
     * themselves. Re-applying our settings every tick would be a tug of war nobody can win, and
     * restoring on top of a deliberate change would undo their edit.
     *
     * @return true while custody is still ours and intact
     */
    fun verifyCustody(): Boolean {
        if (!held) {
            return false
        }

        val untouched = ModuleElytraFly.enabled && ModuleElytraFly.modes.activeMode.tag == appliedModeTag
        if (!untouched) {
            held = false
            appliedModeTag = null
            return false
        }

        return true
    }

    val hasCustody: Boolean get() = held

    val maxHorizontal: Double get() = ModuleElytraFly.Speed.horizontal.toDouble()

    val maxVertical: Double get() = ModuleElytraFly.Speed.vertical.toDouble()
}
