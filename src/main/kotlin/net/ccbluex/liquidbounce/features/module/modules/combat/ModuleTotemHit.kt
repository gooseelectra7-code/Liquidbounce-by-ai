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

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.minecraft.client.KeyMapping
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.phys.EntityHitResult
import org.lwjgl.glfw.GLFW

/**
 * TotemHit module
 *
 * "Swaps to knockback sword when attacking with totem": when the attack button is pressed
 * while holding a Totem of Undying and aiming at a (targetable) living entity, swaps to a
 * hotbar sword that has the Knockback enchantment right before the attack input resolves,
 * so the very attack of this click lands with the knockback sword, then swaps back after
 * the configured delay (or stays on the sword when SilentSwap is on).
 *
 * Faithful port of Echo's TotemHitModule.
 *
 * Echo hooks EventStartAttack.Pre, fired inside Minecraft.tick right BEFORE handleKeybinds
 * whenever the attack key has a pending click, with the target taken from mc.hitResult.
 * LiquidBounce has no event at that spot; per the SpearReach/SpearLunge precedent the raw
 * attack-button GLFW press (MouseButtonEvent/KeyEvent) is used instead: it arrives before
 * the next client tick consumes the click via handleKeybinds -> startAttack, i.e. at the
 * same relative position (no player tick runs in between). Echo's Pre event implicitly only
 * fires when no screen is open (clicks never pend otherwise), so the screen check is explicit
 * here.
 *
 * Swap mechanics ported from Echo's SwapStateManager.swapTo(owner, slot, inputSimulation,
 * restoreDelayTicks, restoreOnEnd, onEnd), which TotemHit calls as:
 *
 * - SilentSwap OFF: swapTo(slot, inputSim = InputSimulation, delay = random(Delay), restore = true).
 *   Input simulation clicks the hotbar KeyMapping (KeyMapping.click) so the slot change is
 *   performed by vanilla handleKeybinds itself - the hotbar loop runs before the attack-key
 *   loop, so the swap lands just before startAttack; without input simulation the slot is
 *   set directly. Either way MultiPlayerGameMode.attack flushes the new slot to the server
 *   (ensureHasSentCarriedItem -> ServerboundSetCarriedItemPacket) before the ATTACK packet.
 *   The restore back to the original slot runs at tick HEAD (echo's EARLY restore phase,
 *   right before handleKeybinds) once the delay has elapsed, using the same input-simulation
 *   mode as the swap.
 * - SilentSwap ON: forces inputSimulation = false, delay = 0 and restoreOnEnd = false, which
 *   in SwapStateManager amounts to: set the slot directly and drop the swap state at the end
 *   of the same tick WITHOUT ever restoring - the player simply stays on the knockback sword.
 *   (No packets are sent directly; despite the name this is a plain, persistent client swap.)
 *
 * Like echo's SwapStateManager, a pending restore is dropped without restoring when the
 * player disappears or a respawn/position-correction packet arrives, and disabling the
 * module restores the original slot immediately (SwapStateManager.cancel).
 *
 * Deviations (LiquidBounce framework):
 * - Echo's Delay and InputSimulation settings are hidden while SilentSwap is on; LiquidBounce
 *   has no per-setting visibility dependencies, so they are always shown (and simply unused
 *   while SilentSwap is on).
 * - Echo's TargetUtils.isTargetAllowed consults its global TargetControl module; the
 *   LiquidBounce equivalent is the global targets configuration via shouldBeAttacked().
 * - Echo's dead `attacked` flag (set on swap, cleared on restore, never read) is not ported.
 */
object ModuleTotemHit : ClientModule("TotemHit", ModuleCategories.COMBAT) {

    /** Echo: "Silent Swap" (default false). */
    private val silentSwap by boolean("SilentSwap", false)

    /**
     * Echo: "Delay" RangeSetting, default 1..1, bounds 1..20, step 1 (ticks), used only when
     * SilentSwap is off. A random value is picked per swap via echo's getRandom(), which is
     * `(int) (Math.random() * (max - min) + min)` - i.e. the upper bound itself is only ever
     * produced when min == max. Replicated in [pickSwapBackDelay].
     */
    private val swapBackDelay by intRange("Delay", 1..1, 1..20, "ticks")

    /** Echo: "Input Simulation" (default true) - send hotbar key press simulations. */
    private val inputSimulation by boolean("InputSimulation", true)

    /**
     * Echo's isSword() item list: wooden/stone/iron/golden/diamond/netherite. Note that echo's
     * predicate does NOT include the copper sword (unlike its other sword helpers) - replicated
     * as-is for faithfulness.
     */
    private val SWORD_ITEMS = setOf(
        Items.WOODEN_SWORD,
        Items.STONE_SWORD,
        Items.IRON_SWORD,
        Items.GOLDEN_SWORD,
        Items.DIAMOND_SWORD,
        Items.NETHERITE_SWORD
    )

    /** Original ("root") hotbar slot to restore to (echo SwapState.rootSlot). */
    private var originalSlot = -1

    /** Input-simulation mode captured at swap time (echo SwapState.rootInputSimulation). */
    private var restoreInputSimulation = false

    /** Whether a restore is pending (echo: swap state on the stack with restoreOnEnd). */
    private var shouldSwitchBack = false

    /** Tick the (last) swap was armed at (echo: restoreTick = tickCount + delay). */
    private var swapTick = 0

    /** Delay in ticks picked for the pending restore. */
    private var restoreDelay = 0

    /**
     * Echo's findHotbarSlotWithKnockbackSword(): first hotbar slot 0..8 holding a non-empty
     * sword stack with Knockback level > 0 (no fallback to unenchanted swords).
     */
    private fun findKnockbackSwordSlot(): Int? {
        val slot = Slots.Hotbar.findSlot { stack ->
            !stack.isEmpty && stack.item in SWORD_ITEMS && stack.getEnchantment(Enchantments.KNOCKBACK) > 0
        }
        return slot?.hotbarIndex
    }

    /**
     * Echo's isEntityAllowed -> TargetUtils.isTargetAllowed: alive, not removed, not dead or
     * dying, plus the global target filters (TargetControl in echo, targets config here).
     */
    private fun isTargetAllowed(entity: LivingEntity): Boolean {
        if (!entity.isAlive || entity.isRemoved || entity.isDeadOrDying) {
            return false
        }
        return entity.shouldBeAttacked()
    }

    /**
     * Echo's InventoryUtils.setInvSlot(slot, inputSimulation): no-op when already selected;
     * with input simulation the bound hotbar key is clicked (consumed by this tick's
     * handleKeybinds, which performs the real slot change), otherwise the slot is set
     * directly. An unbound hotbar key makes KeyMapping.click a no-op, exactly like echo.
     */
    private fun setSelectedSlot(player: Player, slot: Int, inputSimulation: Boolean) {
        if (slot !in 0..8 || player.inventory.selectedSlot == slot) {
            return
        }

        if (inputSimulation) {
            val hotbarKey = mc.options.keyHotbarSlots.getOrNull(slot) ?: return
            KeyMapping.click(hotbarKey.key)
        } else {
            player.inventory.selectedSlot = slot
        }
    }

    /** Echo RangeSetting.getRandom() truncated to int, see [swapBackDelay]. */
    private fun pickSwapBackDelay(): Int {
        val delay = swapBackDelay
        return (Math.random() * (delay.last - delay.first)).toInt() + delay.first
    }

    /**
     * Echo's SwapStateManager.swapTo(this, slot, inputSimulation && !silentSwap,
     * silentSwap ? 0 : random(Delay), !silentSwap, () -> attacked = false), reduced to the
     * single-owner case.
     */
    private fun swapTo(player: Player, targetSlot: Int) {
        val useInputSimulation = inputSimulation && !silentSwap
        val restoreOnEnd = !silentSwap

        if (shouldSwitchBack) {
            // Echo: the top swap state is ours -> update it in place (root slot and root
            // input-simulation mode are kept) and re-apply the swap.
            if (restoreOnEnd) {
                swapTick = player.tickCount
                restoreDelay = pickSwapBackDelay()
            } else {
                // Turned silent mid-swap: echo drops the state at the end of this tick
                // without restoring.
                clearSwapState()
            }
            setSelectedSlot(player, targetSlot, useInputSimulation)
            return
        }

        // Echo: previousSlot == targetSlot -> no swap at all.
        if (player.inventory.selectedSlot == targetSlot) {
            return
        }

        if (restoreOnEnd) {
            originalSlot = player.inventory.selectedSlot
            restoreInputSimulation = useInputSimulation
            shouldSwitchBack = true
            swapTick = player.tickCount
            restoreDelay = pickSwapBackDelay()
        }
        // SilentSwap: no restore bookkeeping at all - swap and stay (echo's state is dropped
        // at handleKeybinds RETURN of the same tick without restoring).

        setSelectedSlot(player, targetSlot, useInputSimulation)
    }

    /**
     * Echo's EventStartAttack.Pre handler: guards, crosshair target, totem-in-mainhand check,
     * knockback sword lookup, swap.
     */
    private fun onAttackKeyPressed() {
        val player = mc.player ?: return // echo: isNull() / mc.player == null
        if (mc.level == null) return // echo: isNull()
        if (mc.screen != null) return // implicit in echo (clicks never pend while a screen is open)
        if (mc.isPaused) return // echo: mc.isPaused()

        // Echo: target from mc.hitResult, entities only.
        val target = (mc.hitResult as? EntityHitResult)?.entity as? LivingEntity ?: return
        if (!isTargetAllowed(target)) return

        if (player.mainHandItem.item != Items.TOTEM_OF_UNDYING) return

        val swordSlot = findKnockbackSwordSlot() ?: return
        swapTo(player, swordSlot)
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

    /**
     * Restore runs at tick HEAD - before handleKeybinds consumes clicks - matching echo's
     * EARLY restore phase (Delay >= 1 always maps to EARLY). With input simulation the
     * queued hotbar key click is consumed by the very same tick's handleKeybinds.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!shouldSwitchBack) return@handler

        val player = mc.player
        if (player == null) {
            // Echo processDueRestores: no player -> clear the stack without restoring.
            clearSwapState()
            return@handler
        }

        if (player.tickCount < swapTick) {
            // tickCount went backwards (respawn) - drop without restoring.
            clearSwapState()
            return@handler
        }

        if (player.tickCount - swapTick >= restoreDelay) {
            setSelectedSlot(player, originalSlot, restoreInputSimulation)
            clearSwapState()
        }
    }

    /**
     * Echo's SwapStateManager clears all swap states (without restoring) when a respawn or
     * position-correction packet arrives.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        if (event.packet is ClientboundRespawnPacket || event.packet is ClientboundPlayerPositionPacket) {
            clearSwapState()
        }
    }

    /** Echo onDisable: SwapStateManager.cancel(this) - restore the root slot if still swapped. */
    override fun onDisabled() {
        val player = mc.player
        if (shouldSwitchBack && player != null) {
            setSelectedSlot(player, originalSlot, restoreInputSimulation)
        }
        clearSwapState()
    }

    private fun clearSwapState() {
        shouldSwitchBack = false
        originalSlot = -1
        restoreInputSimulation = false
        swapTick = 0
        restoreDelay = 0
    }
}
