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
package net.ccbluex.liquidbounce.features.module.modules.combat.totem

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.inventory.ArmorItemSlot
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.SwitchMode
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MainHandTotem module
 *
 * Keeps a totem staged in the hotbar and takes it into the MAIN hand while we are low, instead of
 * into the offhand. Written for this fork on the user's description (2026-09-14): "swap from the
 * inventory into a free or chosen hotbar slot and take it in hand on low HP; when one is spent, swap
 * the second in for the future straight away."
 *
 * A totem pops out of either hand in vanilla (`LivingEntity.checkTotemDeathProtection` walks
 * `EquipmentSlot.MAINHAND` and `OFFHAND`), so this is a real alternative to the offhand route and
 * not a cosmetic one. What it buys over the offhand is the shape of the wire traffic: the hold is a
 * `ServerboundSetCarriedItemPacket`, which carries NO container state id and therefore cannot be
 * answered with the full-inventory resync that ghosts an offhand swap. The only container click in
 * the whole feature is the staging SWAP, and that one happens ahead of the danger window rather
 * than inside it.
 *
 * What it costs is the main hand: a totem in hand is not a sword, so the auras stand down for the
 * hold ([isBusy]) rather than attack with it. That was the user's explicit choice over keeping DPS.
 *
 * Design decisions:
 * - **Staging is continuous, the hold is conditional.** [TotemStaging.ensureStaged] runs every
 *   scheduling cycle whenever the module is on, so the totem is already in the bar before the fight
 *   and the refill after a pop costs one SWAP that is not on the critical path. Only the held-slot
 *   assertion is gated on health.
 * - **Hysteresis is mandatory, not a nicety.** [releaseHealth] is coerced above [health] so the
 *   trigger and the release can never be the same number: at equal thresholds one point of regen
 *   flips the hold every tick, and each flip is a `SetCarriedItem` on the wire.
 * - **A pop forces a release for that tick** ([popped]). The staged slot the next totem lands in is
 *   not necessarily the one that just emptied, so a hold left asserted across a pop can point at
 *   the wrong slot for a tick. Dropping it and re-deriving on the next tick is a cheaper fix than
 *   tracking the refill's destination. If we are still low, the trigger simply fires again — which
 *   is the intended "pop, new totem in hand" cycle, not a flap.
 * - **The pop packet is parked on the netty thread** and read at the head of the tick, like
 *   `ModuleAutoTotem` does it: `ClientboundEntityEventPacket.getEntity` needs the level, and
 *   resolving an entity off the network thread is a race.
 * - **Never fight the player.** [dropRestoreIfPlayerMoved] voids a pending
 *   [SwitchMode.NORMAL] restore the moment the player's own selection leaves the slot we forced,
 *   copied from `ModuleCrystalAura.normalSwitch`. The silent path is bounded the same way by
 *   re-asserting [SilentHotbar] every tick with a short reset, so letting go of the module releases
 *   the slot on its own.
 *
 * OVERLAP: `ModuleAutoTotem` with `Route = MainHand` also stages a totem in the hotbar, and since
 * 2026-09-16 each module owns its OWN [TotemStaging] rather than sharing one. That makes the
 * settings independent, which is the point, but it does not make the two safe together: both
 * resolvers prefer the first EMPTY hotbar slot, so with both enabled they pick the same slot and
 * then undo each other's refills. Enable the one whose hand you want the totem in.
 */
object ModuleMainHandTotem : ClientModule("MainHandTotem", ModuleCategories.COMBAT) {

    /** How long a silent hold is asserted for. Re-asserted every tick, so this is only the tail. */
    private const val SILENT_HOLD_TICKS = 2

    /**
     * Runs the hold ahead of the auras.
     *
     * `EventHookRegistry` orders hooks DESCENDING by priority (`EventHookRegistry.kt:121`), so a
     * positive number here puts this handler in front of every default-priority one - which is what
     * `ModuleKillAura`, `ModuleCrystalAura` and `ModuleAutoAnchor` read [isBusy] from. Without it
     * the flag they see is one tick stale, and the tick we decide to hold on is a tick they would
     * still swing on. Well below `SilentHotbar`'s own 1001 expiry hook, which has to stay first.
     */
    private const val TICK_PRIORITY: Short = 100

    /** The smallest gap the trigger and the release threshold are allowed to have. */
    private const val MIN_HYSTERESIS = 1f

    /** Absorption below this many ticks left does not count towards health. */
    private const val ABSORPTION_MIN_TICKS = 25

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    /**
     * Incoming-damage prediction, ON by default here: a module whose whole job is to have a totem
     * in hand before the hit lands has no reason to wait for the health bar to report it.
     */
    private val incomingDamage = tree(IncomingDamage(this, enabledByDefault = true))

    /**
     * Hotbar staging, OURS alone: the anchor, the displaced item and the slot policy belong to this
     * module and are not shared with `ModuleAutoTotem` any more.
     */
    private val staging = tree(TotemStaging(this))

    /**
     * The anticheat gate for this module's staging clicks.
     *
     * The staging window IS this module's swap window - the hold itself is a `SetCarriedItem`, not
     * a container click - so that is what keeps the movement stop alive.
     */
    private val quiet = tree(SwapQuiet(this) { staging.isStaging })

    /** Health at or below which the totem goes into the main hand. */
    private val health by float("Health", 8f, 1f..20f, "HP")

    /** The same threshold while an elytra sits in the chest slot. */
    private val elytraHealth by float("ElytraHealth", 12f, 1f..20f, "HP")

    /**
     * Health above which the main hand is given back. Coerced to at least [health] plus
     * [MIN_HYSTERESIS] at read time — see the class KDoc on why they may not meet.
     */
    private val releaseHealth by float("ReleaseHealth", 14f, 1f..20f, "HP")

    /** How the held slot is moved: silently, visibly, or not at all (stage only). */
    private val switchMode by enumChoice("Switch", SwitchMode.SILENT)

    /** Whether a totem is in our hand right now because we put it there. */
    private var holding = false

    /** [SwitchMode.NORMAL] bookkeeping: the slot to give back, and the slot we forced. */
    private var restoreSlot: Int? = null
    private var forcedSlot: Int? = null

    /** Set by the pop packet, consumed by the next tick. */
    private var popped = false

    /** Netty thread to main thread; only the packet object is parked. */
    private val packetQueue = ConcurrentLinkedQueue<ClientboundEntityEventPacket>()

    /**
     * Whether the auras have to stand down: a totem is in our main hand, or a staging click of ours
     * may still be in flight.
     *
     * The hold half is the obvious one — attacking with a totem wastes the hit and announces the
     * module. The staging half is the anti-ghost window described on [TotemStaging.isStaging]: the
     * staging SWAP is a container click, and an aura that spends a slot inside its round trip makes
     * the server answer it with a resync that leaves the staged totem a ghost.
     *
     * Read by `combat/crystalaura/ModuleCrystalAura` and `combat/autoanchor/ModuleAutoAnchor`
     * alongside `ModuleAutoTotem.isSwapping`.
     */
    val isBusy: Boolean
        get() = running && (holding || staging.isStaging)

    override fun onEnabled() = reset()

    override fun onDisabled() {
        releaseHand()
        staging.reset()
        reset()
    }

    private fun reset() {
        incomingDamage.reset()
        holding = false
        restoreSlot = null
        forcedSlot = null
        popped = false
        packetQueue.clear()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        staging.reset()
        reset()
    }

    // ------------------------------------------------------------------------------------------
    // Ingress (netty thread — queue only)
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !event.original) {
            return@handler
        }

        val packet = event.packet
        if (packet is ClientboundEntityEventPacket && packet.eventId == EntityEvent.PROTECTED_FROM_DEATH) {
            packetQueue += packet
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hold (main thread)
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent>(priority = TICK_PRIORITY) {
        debugParameter("Totem Hand Holding") { holding }
        debugParameter("Totem Hand Slot") { staging.stagedSlot?.hotbarIndex ?: -1 }
        debugParameter("Totem Hand Staging") { staging.isStaging }
        debugParameter("Totem Hand Predicted") { incomingDamage.lastPredicted }

        drainPackets()

        if (player.isDeadOrDying) {
            releaseHand()
            reset()
            return@handler
        }

        // A pop invalidates the slot we were pointing at, so the hold is dropped for this tick and
        // re-derived on the next one against whatever the refill staged.
        if (popped) {
            popped = false
            releaseHand()
            return@handler
        }

        if (!shouldHold()) {
            releaseHand()
            return@handler
        }

        val slot = staging.stagedSlot
        if (slot == null) {
            releaseHand()
            return@handler
        }

        holdHand(slot)
    }

    /** Replays the parked pop packets; `getEntity` needs the level and so cannot run on netty. */
    private fun drainPackets() {
        while (true) {
            val packet = packetQueue.poll() ?: break
            val level = mc.level ?: continue

            if (packet.getEntity(level) === player) {
                popped = true
            }
        }
    }

    /**
     * The trigger, with the release threshold as a separate, strictly higher number.
     *
     * Absorption counts towards health only while the effect has more than [ABSORPTION_MIN_TICKS]
     * left, matching `ModuleAutoTotem.effectiveHealth`: a hearts bar that is about to expire must
     * not talk the module out of a totem.
     */
    private fun shouldHold(): Boolean {
        val effective = effectiveHealth()
        val base = if (ArmorItemSlot.CHEST.itemStack.`is`(Items.ELYTRA)) elytraHealth else health

        // Already holding: keep it until the release threshold is genuinely cleared. Handing the
        // HIGHER number to the predictor as well is what keeps the hysteresis on the predicted path
        // too - a smaller `allowedDamage` over there makes a hold easier to KEEP than to start.
        val threshold = if (holding) releaseHealth.coerceAtLeast(base + MIN_HYSTERESIS) else base

        // Off restores the plain health test exactly, which is why the predictor is asked rather
        // than merged into the comparison.
        if (incomingDamage.enabled) {
            return incomingDamage.warrantsTotem(effective, threshold)
        }

        return effective <= threshold
    }

    private fun effectiveHealth(): Float {
        val effect = player.getEffect(MobEffects.ABSORPTION)
        val absorption = if (effect != null && effect.duration > ABSORPTION_MIN_TICKS) player.absorptionAmount else 0f
        return player.health + absorption
    }

    /**
     * Asserts the staged slot as the held one, by whichever route the user picked.
     *
     * Refuses to do anything while our own inventory is open server-side. BOTH routes end in a
     * `ServerboundSetCarriedItemPacket` - the silent one because `ensureHasSentCarriedItem` reports
     * `SilentHotbar.serversideSlot` at the next interaction, the normal one because vanilla
     * announces the field it writes - which TotemGuard flags as InventoryA "change slot". The rule
     * and its switch live in [SwapQuiet.allowsHandSwapNow].
     */
    private fun holdHand(slot: HotbarItemSlot) {
        if (!quiet.allowsHandSwapNow) {
            return
        }

        val index = slot.hotbarIndex ?: return

        when (switchMode) {
            SwitchMode.SILENT -> {
                SilentHotbar.selectSlotSilently(this, slot, SILENT_HOLD_TICKS)
                holding = true
            }

            SwitchMode.NORMAL -> {
                normalSwitch(index)
                holding = true
            }

            // Stage only: the totem is in the bar and the player can reach it, but we never move
            // the selection for them. Nothing is held, so the auras are not stood down either.
            SwitchMode.NONE -> holding = false
        }
    }

    /** Gives the hand back. Safe to call when nothing is held. */
    private fun releaseHand() {
        if (SilentHotbar.isSlotModifiedBy(this)) {
            SilentHotbar.resetSlot(this)
        }

        restoreNormalSlot()
        holding = false
    }

    /**
     * The vanilla-visible switch. Copied from `ModuleCrystalAura.normalSwitch`: remember the slot
     * the player had before the FIRST switch, write the field only when it differs, and let vanilla
     * announce it — `MultiPlayerGameMode` emits the `SetCarriedItem` at the tick head through
     * `ensureHasSentCarriedItem()`, so sending one here would only double it.
     */
    private fun normalSwitch(index: Int) {
        val inventory = player.inventory
        val selected = inventory.selectedSlot
        dropRestoreIfPlayerMoved(selected)

        if (selected == index) {
            return
        }

        if (restoreSlot == null) {
            restoreSlot = selected
        }

        inventory.selectedSlot = index
        forcedSlot = index
    }

    /** Nothing else restores `Inventory.selected`, so without this we keep the player on the totem. */
    private fun restoreNormalSlot() {
        val inventory = mc.player?.inventory ?: return
        val selected = inventory.selectedSlot
        dropRestoreIfPlayerMoved(selected)
        val target = restoreSlot ?: return

        restoreSlot = null
        forcedSlot = null

        if (selected != target) {
            inventory.selectedSlot = target
        }
    }

    /** Never fight the player: once their own selection left the slot we forced, the restore is void. */
    private fun dropRestoreIfPlayerMoved(selected: Int) {
        if (restoreSlot != null && selected != forcedSlot) {
            restoreSlot = null
            forcedSlot = null
        }
    }

    // ------------------------------------------------------------------------------------------
    // Staging and refill
    // ------------------------------------------------------------------------------------------

    /**
     * Keeps a totem in the bar.
     *
     * The refill needs no pop detection of its own: [TotemStaging.stagedSlot] goes null the moment
     * the totem is spent, so the very next cycle stages the next one.
     *
     * Staging is continuous by design (the user's spec is "always keep a totem in the hotbar"), so
     * the item the totem displaced cannot go back while we still want the slot. The one moment it
     * can is [TotemStaging.StageState.UNAVAILABLE] — out of totems everywhere, the anchor is
     * pointless, and holding someone's item hostage for a slot we cannot fill would be a bug. That
     * state is sticky while the inventory has no totem, so this cannot ping-pong.
     */
    @Suppress("unused")
    private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (player.isDeadOrDying) {
            return@handler
        }

        if (staging.ensureStaged(event, inventoryConstraints, quiet) ==
            TotemStaging.StageState.UNAVAILABLE
        ) {
            staging.releaseDisplaced(event, inventoryConstraints, quiet)
        }
    }

}
