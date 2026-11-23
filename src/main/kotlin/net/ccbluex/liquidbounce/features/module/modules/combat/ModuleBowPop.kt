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

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * BowPop module
 *
 * Ported from ThunderHack-Recode. Spoofs movement packets when releasing a bow,
 * throwing an ender pearl, splash potion, egg, snowball or experience bottle
 * to increase the resulting velocity on some server configurations.
 */
object ModuleBowPop : ClientModule("BowPop", ModuleCategories.COMBAT) {

    private val rotation by boolean("Rotation", false)
    private val mode by enumChoice("Mode", ModeEn.Maximum).apply { tagBy(this) }
    private val factor by float("Factor", 1f, 1f..20f)
    private val exploit by enumChoice("Exploit", ExploitEn.Strong)
    private val scale by float("Scale", 0.01f, 0.01f..0.4f)
    private val minimize by boolean("Minimize", false)
    private val delay by float("Delay", 5f, 0f..10f)

    private val bow by boolean("Bow", true)
    private val pearls by boolean("EnderPearls", true)
    private val experience by boolean("Experience", true)
    private val eggs by boolean("Eggs", true)
    private val splashPotions by boolean("SplashPotions", true)
    private val snowballs by boolean("Snowballs", true)

    private val delayTimer = Chronometer()
    private val random = Random.Default

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) return@handler
        if (!delayTimer.hasAtLeastElapsed((delay * 1000).toLong())) return@handler

        val packet = event.packet
        val matches = when (packet) {
            is ServerboundPlayerActionPacket -> {
                packet.action == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                    && bow && player.useItem.`is`(Items.BOW)
            }
            is ServerboundUseItemPacket -> {
                val item = player.getItemInHand(InteractionHand.MAIN_HAND).item
                packet.hand == InteractionHand.MAIN_HAND && when (item) {
                    Items.ENDER_PEARL -> pearls
                    Items.EXPERIENCE_BOTTLE -> experience
                    Items.EGG -> eggs
                    Items.SPLASH_POTION -> splashPotions
                    Items.SNOWBALL -> snowballs
                    else -> false
                }
            }
            else -> false
        }

        if (!matches) return@handler

        network.send(ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING))

        val yawRadians = Math.toRadians(player.yRot.toDouble())
        val strictDirection = doubleArrayOf(
            100f * -sin(yawRadians),
            100f * cos(yawRadians)
        )

        val runs = getRuns()
        repeat(runs) {
            when (exploit) {
                ExploitEn.Fast -> {
                    spoof(player.x, if (minimize) player.y else player.y - 1e-10, player.z, true)
                    spoof(player.x, player.y + 1e-10, player.z, false)
                }
                ExploitEn.Strong -> {
                    spoof(player.x, player.y + 1e-10, player.z, false)
                    spoof(player.x, if (minimize) player.y else player.y - 1e-10, player.z, true)
                }
                ExploitEn.Phobos -> {
                    spoof(player.x, player.y + 0.00000000000013, player.z, true)
                    spoof(player.x, player.y + 0.00000000000027, player.z, false)
                }
                ExploitEn.Strict -> {
                    if (random.nextBoolean()) {
                        spoof(player.x - strictDirection[0], player.y, player.z - strictDirection[1], false)
                    } else {
                        spoof(player.x + strictDirection[0], player.y, player.z + strictDirection[1], true)
                    }
                }
                ExploitEn.WB -> {
                    spoof(player.x + getWorldBorderRnd(), player.y, player.z + getWorldBorderRnd(), false)
                }
            }
        }

        delayTimer.reset()
    }

    private fun spoof(x: Double, y: Double, z: Double, ground: Boolean) {
        network.send(
            if (rotation) {
                ServerboundMovePlayerPacket.PosRot(
                    x, y, z,
                    player.yRot, player.xRot,
                    ground, player.horizontalCollision
                )
            } else {
                ServerboundMovePlayerPacket.Pos(
                    x, y, z,
                    ground, player.horizontalCollision
                )
            }
        )
    }

    private fun getRuns(): Int = when (mode) {
        ModeEn.Normal -> factor.toInt()
        ModeEn.Maximum -> (30f * factor).toInt()
        ModeEn.Factorised -> 10 + (factor - 1).toInt()
    }

    private fun getWorldBorderRnd(): Int {
        if (mc.isSingleplayer) return 1

        val n = random.nextInt(29000000)
        return if (random.nextBoolean()) n else -n
    }

    private enum class ExploitEn(override val tag: String) : Tagged {
        Strong("Strong"),
        Fast("Fast"),
        Strict("Strict"),
        Phobos("Phobos"),
        WB("WB")
    }

    private enum class ModeEn(override val tag: String) : Tagged {
        Normal("Normal"),
        Maximum("Maximum"),
        Factorised("Factorised")
    }

}
