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
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.SpearUtils
import net.ccbluex.liquidbounce.utils.combat.SpearUtils.getCurrentItemCrosshairTarget
import net.ccbluex.liquidbounce.utils.combat.SpearUtils.isSpearSwapFullyCooled
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.item.isSpear
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantments

/**
 * SpearReach module
 *
 * When the attack key is pressed on a target the held item cannot reach, swaps to the hotbar item
 * that reaches furthest and would actually connect, then swaps back one tick later.
 *
 * ## Why the swap has to happen at tick HEAD
 *
 * `Minecraft.startAttack` never raycasts — it reads the cached `hitResult`, which
 * `LocalPlayer.raycastHitResult` builds from the `ATTACK_RANGE` of the item **in hand at the time**.
 * Inside `Minecraft.tick`, `pick` runs at bytecode offset 97 and `handleKeybinds` — which calls
 * `startAttack` — at 377. So a swap is only useful if it precedes `pick`; done any later, the
 * entity is simply not in the result and the click is spent on a miss.
 *
 * That is the defect this port inherited. Echo swaps from `EventStartAttack.Pre`, injected before
 * `handleKeybinds` and therefore *after* `pick`, and the first version here hooked the raw GLFW
 * press, which has the same problem whenever input is polled after the tick. Driving it from
 * [GameTickEvent] — injected at `tick` HEAD — is ordered before `pick` by construction.
 *
 * `MultiPlayerGameMode.attack` calls `ensureHasSentCarriedItem`, so the server also learns about
 * the new slot before the attack packet reaches it.
 *
 * Originally a port of Echo's SpearReachModule, which only ever considered non-Lunge spears.
 */
@Suppress("TooManyFunctions")
object ModuleSpearReach : ClientModule("SpearReach", ModuleCategories.COMBAT) {

    private val paperSupport by boolean("PaperSupport", true)

    /**
     * Restrict candidates to spears. Off by default: reach is a property of the item's
     * `ATTACK_RANGE` component, not of being a spear, so anything that reaches further than what is
     * held qualifies — and the item actually chosen is always the one that both reaches furthest
     * and genuinely hits the entity under the crosshair.
     */
    private val spearsOnly by boolean("SpearsOnly", false)

    /**
     * Never swap to an item enchanted with Lunge. A Lunge weapon turns the attack into a dash,
     * which is [ModuleSpearLunge]'s job and not something a reach swap should trigger by surprise.
     */
    private val avoidLunge by boolean("AvoidLunge", true)

    /**
     * Prints, on every attack click, either the swap that was made or the reason none was.
     * The reach window is narrow enough that guessing from feel is unreliable.
     */
    private val debug by boolean("Debug", false)


    /** Echo uses a fixed, non-configurable switch delay of 1 tick. */
    private const val SWITCH_DELAY = 1

    private var shouldSwitchBack = false
    private var switchBackTick = -1
    private var originalSlot = -1

    /** Kept public for TriggerBot/AimAssist-style integrations (mirrors echo's isSwapActive). */
    val isSwapActive: Boolean
        get() = shouldSwitchBack

    /**
     * The hotbar slot that would reach the entity under the crosshair, together with that entity.
     *
     * A candidate has to beat what is already held *and* actually connect: a longer nominal range
     * is worthless if the raycast still misses. Ordering by reach and keeping the best means the
     * swap always lands on the furthest-reaching item that works, rather than the first one found.
     */
    @Suppress("ReturnCount")
    private fun findReachSlot(partialTick: Float): Pair<Int, LivingEntity>? {
        val player = mc.player ?: return null

        var best: Pair<Int, LivingEntity>? = null
        var bestReach = SpearUtils.maxReachOf(player, player.mainHandItem)

        for (slot in 0 until Inventory.getSelectionSize()) {
            val stack = player.inventory.getItem(slot)
            if (!isCandidate(stack)) {
                continue
            }

            val reach = SpearUtils.maxReachOf(player, stack)

            // The raycast is the expensive part, so it only runs for an item that would be an
            // improvement in the first place.
            val target = if (reach > bestReach) {
                SpearUtils.getCrosshairTargetWith(player, stack, partialTick)
            } else {
                null
            }

            if (target != null) {
                best = slot to target
                bestReach = reach
            }
        }

        return best
    }

    private fun isCandidate(stack: ItemStack): Boolean = when {
        stack.isEmpty -> false
        spearsOnly && !stack.isSpear -> false
        avoidLunge && stack.getEnchantment(Enchantments.LUNGE) > 0 -> false
        else -> true
    }


    fun getSwapReadyCrosshairTarget(): LivingEntity? =
        getSwapReadyCrosshairTarget(mc.deltaTracker.getGameTimeDeltaPartialTick(false))

    /**
     * The entity a swap would let us hit, for TriggerBot/AimAssist-style integrations.
     */
    fun getSwapReadyCrosshairTarget(partialTick: Float): LivingEntity? = findSwapPlan(partialTick)?.second

    /**
     * The slot to swap to and the entity it would reach, or `null` when no swap is warranted.
     *
     * There is no restriction on what is currently held: the only thing that matters is that the
     * held item cannot reach the target and something else can. That is also the first gate, since
     * this module exists purely for the case vanilla cannot already handle.
     *
     * ## The window is narrower than it looks
     *
     * A swap only ever happens between the held item's max reach and the candidate's. For a sword
     * (which falls back to the `ENTITY_INTERACTION_RANGE` attribute, 3.0 for a player) against a
     * spear (max 4.5) that is a band roughly 1.5 blocks deep, and the crosshair ray still has to
     * intersect the hitbox inside it. Closer than the band the held item already connects and this
     * module deliberately stays out of the way; further out nothing reaches. That is why it fires
     * on some approaches and not others — set [debug] to watch the numbers instead of guessing.
     */
    @Suppress("ReturnCount")
    private fun findSwapPlan(partialTick: Float, report: Boolean = false): Pair<Int, LivingEntity>? {
        if (!enabled) return null
        val player = mc.player ?: return null
        if (mc.level == null) return null
        if (mc.screen != null) return null
        if (mc.isPaused) return null

        val heldReach = SpearUtils.maxReachOf(player, player.mainHandItem)

        if (getCurrentItemCrosshairTarget(player, partialTick) != null) {
            return reject(report, "held item already reaches (max %.2f) — vanilla handles it".format(heldReach))
        }

        val plan = findReachSlot(partialTick)
            ?: return reject(report, "nothing in the hotbar reaches it (held max %.2f)".format(heldReach))

        val (slot, target) = plan
        if (slot == player.inventory.selectedSlot) {
            return reject(report, "best reach is already the selected slot")
        }

        val stack = player.inventory.getItem(slot)

        if (paperSupport) {
            if (!isSpearSwapFullyCooled(player, stack, partialTick)) {
                val scale = SpearUtils.getProjectedSpearSwapStrengthScale(player, stack, partialTick)
                return reject(report, "swap still cooling (%.2f of 1.00) — PaperSupport".format(scale))
            }
        } else if (player.getAttackStrengthScale(partialTick) < 1.0f) {
            return reject(
                report,
                "attack still cooling (%.2f of 1.00)".format(player.getAttackStrengthScale(partialTick))
            )
        }

        if (report && debug) {
            chat(
                ("§8[SpearReach] §aswap→ slot %d (%s, max %.2f); eye→hitbox %.2f, feet %.2f; held max %.2f")
                    .format(
                        slot + 1,
                        stack.hoverName.string,
                        SpearUtils.maxReachOf(player, stack),
                        rayDistanceTo(target),
                        player.distanceTo(target),
                        heldReach
                    ),
                this
            )
        }

        return slot to target
    }


    /**
     * Distance the attack ray actually travels to the target's hitbox, measured from the eye.
     *
     * This is what the range check compares against, and it is longer than the feet-to-feet
     * distance whenever there is any height difference — which is why a target that "looks like
     * four blocks away" can still sit outside a 4.5 reach.
     */
    private fun rayDistanceTo(target: LivingEntity): Double {
        val eye = player.eyePosition
        return target.boundingBox.clip(eye, eye.add(player.lookAngle.scale(MAX_PROBE)))
            .map { it.distanceTo(eye) }
            .orElse(eye.distanceTo(target.boundingBox.getCenter()))
    }

    private const val MAX_PROBE = 8.0

    private fun reject(report: Boolean, reason: String): Pair<Int, LivingEntity>? {
        if (report && debug) {
            chat("§8[SpearReach] §7no swap: $reason", this)
        }
        return null
    }

    /**
     * Performs the swap for a click that is about to be consumed this tick.
     *
     * **This has to happen before `pick`, not merely before `startAttack`.** `startAttack` never
     * raycasts — it reads the `hitResult` that `pick` built earlier in the same tick from the
     * `ATTACK_RANGE` of the item then in hand. A swap made after `pick` therefore changes nothing
     * about the attack it was meant to enable; the entity is simply absent from the result and the
     * click is spent on a miss.
     *
     * Echo swaps from its `EventStartAttack.Pre`, injected before `handleKeybinds` — which is
     * offset 377 in `tick`, while `pick` is offset 97. Hooking the raw GLFW press, as this port
     * first did, has the same defect whenever input is polled after the tick. Driving it from
     * [GameTickEvent] (injected at `tick` HEAD) is the only placement that is ordered before `pick`
     * by construction, so the swapped item is the one the crosshair result is built from.
     */
    private fun swapForPendingClick() {
        val player = mc.player ?: return
        if (mc.options.keyAttack.clickCount <= 0) {
            return
        }

        val (slot, _) = findSwapPlan(mc.deltaTracker.getGameTimeDeltaPartialTick(false), report = true) ?: return

        // Real hotbar swap (echo SwapStateManager.swapTo with inputSimulation=false).
        // Keep the original slot if a swap is somehow still pending (echo keeps rootSlot).
        if (!shouldSwitchBack) {
            originalSlot = player.inventory.selectedSlot
        }
        player.inventory.selectedSlot = slot
        shouldSwitchBack = true
        switchBackTick = player.tickCount + SWITCH_DELAY
    }

    /**
     * Echo's EventSwingHand (LocalPlayer.swing): the client only sends
     * ServerboundSwingPacket from its own swings, so the outgoing packet is the
     * closest existing equivalent. Only tracked while Paper support is enabled.
     */
    @Suppress("unused")
    private val swingHandler = handler<PacketEvent> { event ->
        if (!paperSupport) return@handler
        if (event.origin != TransferOrigin.OUTGOING) return@handler
        if (event.packet is ServerboundSwingPacket) {
            SpearUtils.onPlayerSwing(mc.player)
        }
    }

    /**
     * Tick HEAD, so both halves land before `pick` builds this tick's crosshair result.
     *
     * Restoring first matters: the previous pass has to be handed back before a fresh click is
     * considered, or the original slot would be lost behind a second swap.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> { _ ->
        restorePendingSwap()
        swapForPendingClick()
    }

    private fun restorePendingSwap() {
        if (!shouldSwitchBack) return

        val player = mc.player
        if (player == null) {
            // No player to restore on (echo clears its swap stack on respawn/world change).
            clearSwapState()
            return
        }

        if (player.tickCount + SWITCH_DELAY < switchBackTick) {
            // tickCount went backwards (respawn) - drop without restoring.
            clearSwapState()
            return
        }

        if (player.tickCount >= switchBackTick) {
            restoreSlot()
        }
    }

    override fun onDisabled() {
        if (shouldSwitchBack) {
            restoreSlot()
        } else {
            clearSwapState()
        }
    }

    private fun restoreSlot() {
        val player = mc.player
        if (player != null && originalSlot in 0..8) {
            player.inventory.selectedSlot = originalSlot
        }
        clearSwapState()
    }

    private fun clearSwapState() {
        shouldSwitchBack = false
        originalSlot = -1
        switchBackTick = -1
    }
}
