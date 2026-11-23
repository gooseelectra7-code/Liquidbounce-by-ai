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

package net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.utils.math.toDegrees
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Per-pitch thrust profile for firework boosts, in 5° steps from level flight (0°) to vertical
 * (90°). Ported from the original's degree table.
 *
 * The multiplier scales the look vector the rocket pushes along, so it scales the whole vanilla
 * boost, not just one of its terms. Leaving every step at 1.0 reproduces vanilla thrust exactly,
 * which is why the group ships disabled and flat: it is a tuning surface, not a speed hack that
 * does something on its own.
 *
 * Applied in
 * [net.ccbluex.liquidbounce.injection.mixins.minecraft.entity.projectile.MixinFireworkRocketEntity]
 * via [ModuleElytraTarget.scaleFireworkBoost].
 */
internal object ElytraTargetSpeed : ToggleableValueGroup(ModuleElytraTarget, "CustomSpeed", false) {

    private const val STEP_DEGREES = 5f
    private const val STEP_COUNT = 19

    private val steps = List(STEP_COUNT) { index ->
        float("Deg${index * STEP_DEGREES.toInt()}", 1f, 0f..3f, "x")
    }

    /**
     * Multiplier for the pitch this [lookAngle] represents. The angle is taken from the vector
     * rather than from a rotation so it matches whatever the rocket is actually being pushed along
     * — silent rotation, real rotation or vanilla look.
     */
    fun multiplierFor(lookAngle: Vec3): Float {
        val pitch = abs(-atan2(lookAngle.y, hypot(lookAngle.x, lookAngle.z)).toDegrees()).toFloat()
        val index = (pitch / STEP_DEGREES).roundToInt().coerceIn(0, steps.lastIndex)
        return steps[index].get()
    }
}
