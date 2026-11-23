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
package net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove

import net.ccbluex.fastutil.fastIterable
import net.ccbluex.fastutil.referenceBooleanArrayMapOf
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove.features.InventoryMoveBlinkFeature
import net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove.features.InventoryMoveSneakControlFeature
import net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove.features.InventoryMoveSprintControlFeature
import net.ccbluex.liquidbounce.features.module.modules.movement.inventorymove.features.InventoryMoveTimerFeature
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.utils.network.sendCloseInventory
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.isInInventoryScreen
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FINAL_DECISION
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.entity.anyHorizontal
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.isC2SContainerPacket
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.protocol.Packet
import net.minecraft.world.item.CreativeModeTabs
import org.lwjgl.glfw.GLFW

/**
 * InventoryMove module
 *
 * Allows you to walk while an inventory is opened.
 */

object ModuleInventoryMove : ClientModule("InventoryMove", ModuleCategories.MOVEMENT) {

    internal val behavior by enumChoice("Behavior", Behaviour.NORMAL).also(::tagBy)

    @Suppress("unused")
    enum class Behaviour(override val tag: String) : Tagged {
        NORMAL("Normal"),
        SAFE("Safe"), // disable clicks while moving
        UNDETECTABLE("Undetectable"), // stop in inventory
        STOP_ON_ACTION("StopOnAction"), // stop input on inventory action
        BYPASS("Bypass"), // delay container packets while moving, flush them during a short stop
        LEGIT("Legit"), // like Bypass, but with slower flush timings for ACs that check them
        // SystemDLC InvMove port: immediate stop + drip-fed click flush, shulker-click delay,
        // movement only in inventory/client GUI - see [InventoryMoveSystemBypass]
        SYSTEM("System"),
    }

    private val passthroughSneak by boolean("PassthroughSneak", false)

    // states of movement keys, using mc.options.<key>.isPressed doesn't work for some reason
    private val movementKeys =
        referenceBooleanArrayMapOf(
            mc.options.keyUp, false,
            mc.options.keyLeft, false,
            mc.options.keyDown, false,
            mc.options.keyRight, false,
            mc.options.keyJump, false,
            mc.options.keyShift, false,
        )

    /**
     * Restricts user from clicking while moving in inventory.
     */
    val doNotAllowClicking
        get() = behavior === Behaviour.SAFE && movementKeys.fastIterable().any {
            it.booleanValue && shouldHandleInputs(it.key)
        }

    init {
        tree(InventoryMoveSprintControlFeature)
        tree(InventoryMoveSneakControlFeature)
        tree(InventoryMoveTimerFeature)
        tree(InventoryMoveBlinkFeature)
        // Settings + packet engine of Behaviour.SYSTEM (SystemDLC InvMove port); tree()
        // also forces class initialization so its event handlers register.
        tree(InventoryMoveSystemBypass)

        // Not a value group - just force class initialization so its event handlers register
        // (same idiom as the utility listeners referenced in LiquidBounce.kt).
        InventoryMoveGuiBypass
    }

    fun shouldHandleInputs(keyBinding: KeyMapping): Boolean {
        val screen = mc.screen ?: return true

        if (!running || screen is ChatScreen || screen.isInCreativeSearchField() || ModuleClickGui.isInSearchBar) {
            return false
        }

        if (keyBinding == mc.options.keyShift && !passthroughSneak) {
            return false
        }

        if (behavior === Behaviour.SYSTEM) {
            // SystemDLC InvMove screen policy: survival inventory / client GUI only
            // (each behind its own toggle), no movement in any other screen.
            return InventoryMoveSystemBypass.allowsMovementOn(screen)
        }

        // If we are in a handled screen, we should handle the inputs only if the undetectable option is not enabled
        return behavior === Behaviour.NORMAL || screen !is AbstractContainerScreen<*>
            || behavior === Behaviour.SAFE && screen is InventoryScreen
            || behavior === Behaviour.STOP_ON_ACTION
            // Bypass/Legit move exactly like NORMAL - MixinKeyboardInput fakes the keys;
            // the packet-side work is done by [InventoryMoveGuiBypass].
            || behavior === Behaviour.BYPASS
            || behavior === Behaviour.LEGIT
    }

    private val delayedContainerPackets = mutableListOf<Packet<*>>()

    /**
     * Whether a container packet has to wait for [Behaviour.STOP_ON_ACTION]'s stop.
     *
     * NOT `player.input.keyPresses != Input.EMPTY`, which is what this used to be. `Input` is a
     * SEVEN-field record and its last field is `keySprint.isDown()`
     * (`client/player/KeyboardInput.tick`) - a bit that is set for anyone holding the sprint key and
     * set PERMANENTLY for anyone using vanilla's Toggle Sprint, where `ToggleKeyMapping.isDown`
     * stays true until the key is pressed again. Against that comparison the module was "moving"
     * while standing perfectly still, so EVERY container packet of EVERY feature was held back a
     * frame and cost the player a tick of movement - and a click another feature had deliberately
     * timed into a no-movement window (`totem/SwapQuiet`) was flushed back OUT of that window,
     * where Grim's `MultiActionsC` cancels it and the swap ghosts. That is the "it stops me but
     * never takes the totem" report of 2026-09-16.
     *
     * The keys read here are [movementKeys] - which is where this module defines movement, and it
     * has never included sprint - plus the sprint STATE, i.e. the entity flag the anticheats read
     * and the same notion [InventoryMoveSystemBypass] already uses.
     */
    private val movesForContainerAction: Boolean
        get() {
            val keys = (mc.player ?: return false).input.keyPresses
            return keys.anyHorizontal || keys.jump || keys.shift || player.isSprinting
        }

    override fun onDisabled() {
        delayedContainerPackets.clear()
        InventoryMoveGuiBypass.cleanup()
        // SystemDLC InvMove onDisable -> update16: flush the pending clicks/close, then reset
        // (no-op when SYSTEM never queued anything; internally connection-guarded).
        InventoryMoveSystemBypass.reset(flushPending = true)
        super.onDisabled()
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent>(FINAL_DECISION) {
        if (delayedContainerPackets.isEmpty() ||
            behavior !== Behaviour.STOP_ON_ACTION) {
            return@handler
        }

        val packetsSnapshot = delayedContainerPackets.toTypedArray()
        delayedContainerPackets.clear()
        it.sneak = false
        it.jump = false
        it.directionalInput = DirectionalInput.NONE
        // `schedule` will force the Runnable to be run in next loop
        mc.schedule { packetsSnapshot.forEach(::sendPacketSilently) }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(FIRST_PRIORITY) { event ->
        if (behavior !== Behaviour.STOP_ON_ACTION) {
            return@handler
        }

        val packet = event.packet

        if (packet.isC2SContainerPacket() && movesForContainerAction) {
            event.cancelEvent()
            // Here only be called from render thread because [packet] is c2s
            delayedContainerPackets += packet
        }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        val key = movementKeys.keys.find { it.matches(KeyEvent(event.keyCode, event.scanCode, event.mods)) }
            ?: return@handler
        val pressed = shouldHandleInputs(key) && event.action != GLFW.GLFW_RELEASE
        movementKeys.put(key, pressed)

        if (behavior === Behaviour.SAFE && isInInventoryScreen && InventoryManager.isInventoryOpenServerSide
            && pressed) {
            network.sendCloseInventory()
        }
    }

    /**
     * Checks if the player is in the creative search field
     */
    private fun Screen.isInCreativeSearchField() =
        this is CreativeModeInventoryScreen &&
            CreativeModeInventoryScreen.selectedTab == CreativeModeTabs.searchTab()

}
