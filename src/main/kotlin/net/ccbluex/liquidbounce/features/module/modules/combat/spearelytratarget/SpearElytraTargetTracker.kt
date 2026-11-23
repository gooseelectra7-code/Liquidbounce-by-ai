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

package net.ccbluex.liquidbounce.features.module.modules.combat.spearelytratarget

import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.minecraft.world.entity.LivingEntity

/**
 * Target selection for the spear ram.
 *
 * No field-of-view gate: a pass leaves the target behind us every single time, and dropping it the
 * moment it goes off screen would end the engagement after the first run instead of turning around
 * for the next one.
 */
object SpearElytraTargetTracker : TargetTracker(TargetPriority.DISTANCE) {

    override fun validate(entity: LivingEntity): Boolean =
        entity !== player && !entity.isRemoved && entity.shouldBeAttacked()
}
