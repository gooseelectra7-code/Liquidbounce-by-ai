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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.tickConditional
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.exploit.ModuleKick
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.client.player.RemotePlayer
import net.minecraft.core.component.DataComponents

/**
 * AutoLeave module
 *
 * Automatically makes you leave the server when your health is low, or (optionally) when another
 * player appears within a configured radius.
 */
object ModuleAutoLeave : ClientModule("AutoLeave", ModuleCategories.COMBAT) {

    private val health by float("Health", 8f, 0f..20f, "HP")
    /**
     * When conditions are met, we will start counting up until we reach our threshold. If conditions are not met,
     * we reset the counter and start from the beginning.
     */
    private val delay by intRange("Delay", 0..0, 0..60, "ticks")
    private val mode by enumChoice("Mode", ModuleKick.KickModeEnum.QUIT)

    /** Leave as soon as a (non-friend) player shows up within [OnPlayer.radius]. */
    private object OnPlayer : ToggleableValueGroup(this, "OnPlayer", false) {
        val radius by float("Radius", 32f, 1f..256f, "blocks")
        val ignoreFriends by boolean("IgnoreFriends", true)
        val ignoreBots by boolean("IgnoreBots", true)
    }

    init {
        tree(OnPlayer)
    }

    private fun leave() {
        ModuleKick.kick(mode)
        enabled = false
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val passed = tickConditional(delay.random()) {
            if (player.abilities.instabuild || mc.hasSingleplayerServer()) {
                return@tickConditional true
            }

            // Player can heal himself
            if (player.mainHandItem.has(DataComponents.DEATH_PROTECTION)
                || player.offhandItem.has(DataComponents.DEATH_PROTECTION)
            ) {
                return@tickConditional true
            }

            player.health >= health
        }

        if (passed) {
            leave()
        }
    }

    /** Leaves when another player enters the radius. */
    @Suppress("unused")
    private val playerDetectHandler = tickHandler {
        if (!OnPlayer.enabled || mc.hasSingleplayerServer()) {
            return@tickHandler
        }

        val radiusSq = OnPlayer.radius.sq().toDouble()
        val intruder = world.players().any { other ->
            other is RemotePlayer &&
                other !== player &&
                player.distanceToSqr(other) <= radiusSq &&
                !(OnPlayer.ignoreFriends && FriendManager.isFriend(other)) &&
                !(OnPlayer.ignoreBots && ModuleAntiBot.isBot(other))
        }

        if (intruder) {
            leave()
        }
    }

}
