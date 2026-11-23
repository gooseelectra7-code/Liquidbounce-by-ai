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
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.event.events.PlayerMoveEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.minecraft.world.phys.Vec3

/**
 * ElytraMotion module
 *
 * Freezes the player in the air on elytra while close to the [ModuleKillAura] target.
 *
 * Ported from the wyvern client (ElytraMotion).
 */
object ModuleElytraMotion : ClientModule("ElytraMotion", ModuleCategories.MOVEMENT) {

    private val attackDistance by float("AttackDistance", 3.0f, 0.1f..5.0f)

    private var freeze = false

    override fun onEnabled() {
        freeze = false
        super.onEnabled()
    }

    override fun onDisabled() {
        freeze = false
        super.onDisabled()
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        if (!player.isFallFlying) {
            freeze = false
            return@handler
        }

        freeze = shouldFreeze()
    }

    @Suppress("unused")
    private val moveHandler = handler<PlayerMoveEvent> { event ->
        if (freeze) {
            event.movement = Vec3.ZERO
        }
    }

    private fun shouldFreeze(): Boolean {
        if (!ModuleKillAura.running) {
            return false
        }

        val target = ModuleKillAura.targetTracker.target ?: return false
        if (!player.isFallFlying) {
            return false
        }

        return target.position().distanceTo(player.position()) < attackDistance
    }

}
