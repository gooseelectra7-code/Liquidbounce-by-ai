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

import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Items

/**
 * Elytra recast module
 *
 * Bounces along the ground on an elytra: forces forward movement, jumps whenever
 * the player touches the ground, instantly recasts the glide once airborne and
 * dives at a fixed pitch while gliding.
 */
object ModuleElytraRecast : ClientModule("ElytraRecast", ModuleCategories.MOVEMENT) {

    private const val GLIDE_PITCH = 68f

    private val rotations = tree(RotationsValueGroup(this))

    private var wasOnGround = false

    override fun onDisabled() {
        wasOnGround = false
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (!hasElytra()) {
            return@handler
        }

        event.directionalInput = event.directionalInput.copy(forwards = true)

        when {
            player.onGround() -> {
                event.jump = true
                wasOnGround = true
            }

            player.isFallFlying -> wasOnGround = false

            // Skip the tick right after the ground jump, so the jump gains height first;
            // any other airborne tick recasts the glide immediately.
            !wasOnGround -> recast()

            else -> wasOnGround = false
        }
    }

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        if (!hasElytra() || !player.isFallFlying) {
            return@handler
        }

        RotationManager.setRotationTarget(
            Rotation(player.yRot, GLIDE_PITCH),
            valueGroup = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = this@ModuleElytraRecast
        )
    }

    private fun hasElytra(): Boolean {
        val itemStack = player.getItemBySlot(EquipmentSlot.CHEST)

        return itemStack.`is`(Items.ELYTRA) && !itemStack.nextDamageWillBreak()
    }

    /**
     * Mirrors the vanilla [net.minecraft.world.entity.player.Player.tryToStartFallFlying]
     * conditions, so we never claim a glide the server would reject.
     */
    private fun recast() {
        if (player.abilities.flying || player.isPassenger || player.onClimbable() ||
            player.isInWater || player.hasEffect(MobEffects.LEVITATION)
        ) {
            return
        }

        player.startFallFlying()
        network.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
        )
    }

}
