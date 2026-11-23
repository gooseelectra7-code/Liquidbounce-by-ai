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

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.injection.mixins.minecraft.client.MinecraftAccessor
import net.ccbluex.liquidbounce.utils.combat.SpearUtils
import net.ccbluex.liquidbounce.utils.combat.SpearUtils.findSpearSlotWithLunge
import net.ccbluex.liquidbounce.utils.combat.SpearUtils.isSpearSwapFullyCooled
import net.ccbluex.liquidbounce.utils.input.isPressed
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.item.isSpear
import net.ccbluex.liquidbounce.utils.network.sendHeldItemChange
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantments
import org.lwjgl.glfw.GLFW

/**
 * SpearLunge module
 *
 * Uses the spear's lunge ability to move quickly: swaps to a hotbar spear that has the
 * Lunge enchantment right before the attack input resolves, lets the (vanilla or
 * packet-faked) attack trigger the lunge, then swaps back.
 *
 * Faithful port of Echo's SpearLungeModule.
 */
object ModuleSpearLunge : ClientModule("SpearLunge", ModuleCategories.COMBAT) {

    private val mode by enumChoice("Mode", Mode.ATTACK, Mode.entries.toSet())
    private val silent by boolean("Silent", false)
    private val cooldown by enumChoice("Cooldown", Cooldown.VANILLA, Cooldown.entries.toSet())

    /** Echo's "Cooldown %" (only used by the Vanilla cooldown mode). */
    private val cooldownThreshold by int("CooldownThreshold", 85, 0..100, "%")

    /** How many STAB action packets are sent per cancelled attack in Silent mode. */
    private val multiplier by int("Multiplier", 1, 1..10)


    /** Echo's "Activate Key" (Keypress mode only). */
    private val activateKey by key("ActivateKey")

    private enum class Mode(override val tag: String) : Tagged {
        ATTACK("Attack"),
        KEYPRESS("Keypress")
    }

    private enum class Cooldown(override val tag: String) : Tagged {
        VANILLA("Vanilla"),
        PAPER("Paper")
    }

    /** Echo uses a fixed, non-configurable switch delay of 1 tick. */
    private const val SWITCH_DELAY = 1

    private var originalSlot = -1
    private var shouldSwitchBack = false
    private var keyWasDown = false

    /** Echo's TimerUtils tick snapshot (timer.reset() in performSwap). */
    private var swapTick = Int.MIN_VALUE

    /**
     * Number of pending attack-key clicks whose vanilla startAttack echo would have
     * cancelled (Silent mode). Consumed from KeyMapping.clickCount at the next tick HEAD,
     * before handleKeybinds runs, so startAttack never fires for them.
     */
    private var pendingClickConsumption = 0

    private fun isHoldingSpearWithLunge(): Boolean {
        val player = mc.player ?: return false
        val mainHand = player.mainHandItem
        if (mainHand.isEmpty || !mainHand.isSpear) {
            return false
        }
        return mainHand.getEnchantment(Enchantments.LUNGE) > 0
    }


    /**
     * Echo's SpearLungeModule.performSwap(): find a hotbar spear WITH Lunge, gate on the
     * selected cooldown model, then swap - a real hotbar swap normally, or a raw
     * ServerboundSetCarriedItemPacket (client hand unchanged) in Silent mode.
     */
    @Suppress("ReturnCount")
    private fun performSwap(): Boolean {
        val player = mc.player ?: return false

        val spearSlot = findSpearSlotWithLunge()
        if (spearSlot == -1) return false

        when (cooldown) {
            Cooldown.VANILLA ->
                if (player.getAttackStrengthScale(0.0f) < cooldownThreshold / 100.0f) return false

            Cooldown.PAPER -> {
                val spearStack = player.inventory.getItem(spearSlot)
                if (!isSpearSwapFullyCooled(player, spearStack, 0.0f)) return false
            }
        }

        val currentSlot = player.inventory.selectedSlot
        if (currentSlot == spearSlot) return false

        originalSlot = currentSlot

        if (silent) {
            // Echo InventoryUtils.silentSwapTo: raw packet only, client-side hand unchanged.
            network.sendHeldItemChange(spearSlot)
        } else {
            player.inventory.selectedSlot = spearSlot
        }

        swapTick = player.tickCount // echo: timer.reset()
        shouldSwitchBack = true
        return true
    }

    /** Echo InventoryUtils.silentSwapBack: re-announce the (unchanged) client-side slot. */
    private fun silentSwapBack() {
        val player = mc.player ?: return
        network.sendHeldItemChange(player.inventory.selectedSlot)
    }

    private fun sendStabPackets() {
        repeat(multiplier) {
            network.send(
                ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN)
            )
        }
    }

    /**
     * Attack mode entry point. Echo hooks two spots inside Minecraft.tick:
     *
     * - EventStartAttack.Pre: fired right before handleKeybinds when the attack key has a
     *   pending click; SpearLunge performs the swap there so the very attack of this click
     *   already happens while holding the Lunge spear.
     * - EventStartAttack (startAttack HEAD, cancellable): in Silent mode, when holding a
     *   Lunge spear or after a silent swap, the vanilla attack is cancelled entirely and
     *   replaced with raw STAB action packets.
     *
     * LiquidBounce has no event at either spot, but the raw attack-button GLFW press
     * (MouseButtonEvent/KeyEvent) arrives BEFORE the next client tick consumes the click,
     * i.e. before handleKeybinds -> startAttack - the same relative position as Pre (no
     * player tick runs in between, so cooldown/held-item state is identical). For the
     * Silent cancel there is no cancellable startAttack hook; instead the pending click is
     * consumed from keyAttack.clickCount (access-widened) at the next tick HEAD, which
     * prevents startAttack from ever running for this press.
     */
    private fun onAttackKeyPressed() {
        val player = mc.player ?: return
        if (mc.level == null) return
        if (mc.screen != null) return
        if (mc.isPaused) return

        // Echo EventStartAttack.Pre handler (Attack mode only).
        if (mode == Mode.ATTACK && !isHoldingSpearWithLunge()) {
            performSwap()
        }

        // Echo EventStartAttack handler (Silent only, NOT mode-gated): cancel the vanilla
        // attack and send STAB packets when holding a Lunge spear or just silently swapped.
        if (silent) {
            val holdingLungeSpear = isHoldingSpearWithLunge()
            val silentSwapped = shouldSwitchBack

            if (!holdingLungeSpear && !silentSwapped) return

            pendingClickConsumption++
            sendStabPackets()

            if (silentSwapped) {
                silentSwapBack()
                shouldSwitchBack = false
            }
        }
    }

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        if (event.action != GLFW.GLFW_PRESS) return@handler
        if (event.key != mc.options.keyAttack.key) return@handler
        onAttackKeyPressed()
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyEvent> { event ->
        if (event.action != GLFW.GLFW_PRESS) return@handler
        if (event.key != mc.options.keyAttack.key) return@handler
        onAttackKeyPressed()
    }

    /** Echo InputHandler.isBindDown: raw GLFW state polling, supports mouse binds. */
    private fun isActivateKeyDown(): Boolean {
        val key = activateKey
        if (key == InputConstants.UNKNOWN || key.value == GLFW.GLFW_KEY_UNKNOWN) return false

        return if (key.type == InputConstants.Type.MOUSE) {
            GLFW.glfwGetMouseButton(mc.window.handle(), key.value) == GLFW.GLFW_PRESS
        } else {
            key.isPressed
        }
    }

    /**
     * Echo's EventHandleInput.Early handler (Keypress mode): per-tick key polling with
     * keyWasDown edge detection, running before handleKeybinds. On the pressed edge: if
     * already holding a Lunge spear do nothing, otherwise swap and programmatically invoke
     * the vanilla startAttack (which echo's Silent handler then cancels into STAB packets).
     */
    @Suppress("ReturnCount")
    private fun handleKeypressMode() {
        if (mode != Mode.KEYPRESS) return
        val player = mc.player ?: return
        if (mc.level == null) return
        if (mc.screen != null) return
        if (mc.isPaused) return
        if (shouldSwitchBack) return

        val keyDown = isActivateKeyDown()
        if (keyDown && !keyWasDown) {
            if (isHoldingSpearWithLunge()) {
                keyWasDown = true
                return
            }
            if (performSwap()) {
                if (silent) {
                    // Echo: invokeStartAttack() -> EventStartAttack is cancelled by the
                    // Silent handler, which sends the STAB packets and silently swaps back.
                    sendStabPackets()
                    silentSwapBack()
                    shouldSwitchBack = false
                } else {
                    (mc as MinecraftAccessor).callStartAttack()
                }
            }
        }
        keyWasDown = keyDown
    }

    /**
     * Tick HEAD (before handleKeybinds): consume attack clicks cancelled by Silent mode,
     * then run the Keypress mode polling (echo's EventHandleInput.Early position).
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (pendingClickConsumption > 0) {
            val attackKey = mc.options.keyAttack
            attackKey.clickCount = (attackKey.clickCount - pendingClickConsumption).coerceAtLeast(0)
            pendingClickConsumption = 0
        }

        handleKeypressMode()
    }

    /**
     * Echo's EventSwingHand handler: track own swings for the Paper cooldown model
     * (server resets the swap ticker on swing, the client does not). The outgoing swing
     * packet is the closest LiquidBounce equivalent - the client only sends it for its
     * own swings.
     */
    @Suppress("unused")
    private val swingHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) return@handler
        if (event.packet is ServerboundSwingPacket) {
            SpearUtils.onPlayerSwing(mc.player)
        }
    }

    /**
     * Echo's EventHandleInput.Post handler (handleKeybinds RETURN - LiquidBounce fires
     * InputHandleEvent at exactly that spot): silent swaps are reverted immediately
     * (safety net), real swaps are reverted once the 1-tick switch delay has passed.
     * Since player.tickCount only increments AFTER handleKeybinds (during the level
     * tick), the restore lands at the end of the tick FOLLOWING the swap tick, exactly
     * like echo's TimerUtils.hasReachedTicks(1).
     */
    @Suppress("unused")
    private val inputHandleHandler = handler<InputHandleEvent> {
        val player = mc.player ?: return@handler
        if (!shouldSwitchBack) return@handler

        if (silent) {
            silentSwapBack()
            shouldSwitchBack = false
        } else if (hasReachedSwitchDelay(player.tickCount)) {
            if (originalSlot != -1) {
                player.inventory.selectedSlot = originalSlot
                originalSlot = -1
            }
            shouldSwitchBack = false
        }
    }

    /** Echo TimerUtils.hasReachedTicks(SWITCH_DELAY), including the backwards-tick guard. */
    private fun hasReachedSwitchDelay(currentTick: Int): Boolean {
        if (currentTick < swapTick) {
            swapTick = currentTick
            return false
        }
        return currentTick - swapTick >= SWITCH_DELAY
    }

    override fun onDisabled() {
        val player = mc.player
        if (player != null && originalSlot != -1 && !silent) {
            player.inventory.selectedSlot = originalSlot
        }
        originalSlot = -1
        shouldSwitchBack = false
        keyWasDown = false
        swapTick = Int.MIN_VALUE
        pendingClickConsumption = 0
    }

    /** Echo public helper (used by its HUD to display the projected Paper cooldown). */
    fun getPaperCooldownSpearStack(): ItemStack {
        if (!enabled || cooldown != Cooldown.PAPER) return ItemStack.EMPTY
        val player = mc.player ?: return ItemStack.EMPTY

        val spearSlot = findSpearSlotWithLunge()
        return if (spearSlot == -1) ItemStack.EMPTY else player.inventory.getItem(spearSlot)
    }
}
