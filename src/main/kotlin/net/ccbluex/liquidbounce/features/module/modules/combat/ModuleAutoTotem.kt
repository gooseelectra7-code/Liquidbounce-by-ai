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

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.IncomingDamage
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.SwapJitter
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.SwapQuiet
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.TotemStaging
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.inventory.ArmorItemSlot
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.OffhandSyncTracker
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.item.getEnchantmentCount
import net.ccbluex.liquidbounce.utils.network.sendSwapHotbarSlotWithOffhand
import net.ccbluex.liquidbounce.utils.raytracing.rayTraceCollidingBlocks
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.hypot

/**
 * AutoTotem module
 *
 * A port of the javelin client's `tech.javelin.features.module.impl.combat.AutoTotem` (Yarn
 * mappings, supplied inline by the user 2026-09-12), driving THIS fork's inventory machinery
 * instead of javelin's `swapManager.grimSwap`. It REPLACED the SystemDLC-derived AutoTotem that
 * used to live in this file; nothing of that module's feature set survives (see "Dropped" below).
 *
 * Semantics of the original, kept 1:1:
 * - Trigger (`condition()`): effective health at or below «Здоровье», the «Здоровье на элитре»
 *   threshold instead while an elytra sits in the chest slot, the «Отсутствие брони» threshold
 *   while ANY armour piece is missing, a fall past «При падении» / «При падении на элитре», or —
 *   with «Работать на крисы» — a live end crystal near us, or an enemy whose crosshair is resting
 *   on obsidian next to us (the tick before a crystal goes down).
 *   Absorption counts towards health only while the effect still has more than 25 ticks left, so a
 *   hearts bar that is about to expire does not talk the module out of a totem.
 * - Totem preference (`saveEnchanted`): an ENCHANTED totem in the offhand is remembered as the
 *   "general" one and is the last stack the module will spend. With the option on, the module
 *   swaps a plain totem OVER an enchanted one that is already in the offhand; with it off it only
 *   acts when the offhand holds no totem at all.
 * - Swap back: once the trigger is gone AND the hold counter has run out, whatever the totem
 *   displaced goes back into the offhand. A plain totem is never recorded as displaced, so the
 *   module does not undo its own work.
 * - Hold counter (`cooldownTicks`): 10 ticks after a crystal within dx/dz 7, dy 4 and not above us
 *   disappeared, 6 ticks after our own totem popped, 6 ticks while falling. It gates ONLY the swap
 *   back — the window in which the totem must stay in hand even though nothing looks dangerous any
 *   more, because the explosion or the next crystal has not been accounted for yet.
 *
 * Wiring into this fork (the part javelin does not have):
 * - Every container click goes through [ScheduleInventoryActionEvent] +
 *   [InventoryAction.Click.performSwap], executed by `InventoryManager`. That is what javelin's
 *   `grimSwap(0, slot, 40, SWAP)` becomes here: the same vanilla-shaped
 *   `ServerboundContainerClickPacket` SWAP, but with the manager's open/close tracking, click
 *   delays, miss-click simulation, [net.ccbluex.liquidbounce.utils.inventory.HandSwapGate] and the
 *   user-facing Constraints tree on top. Javelin's slot arithmetic (`finalSlot += 36`, the
 *   `45`/`slot - 36` offhand pair) is what [ItemSlot] already models, so it is gone.
 * - No packets are sent from the netty thread, no rotations are touched and the hotbar selection
 *   is never moved — the module only ever swaps into and out of the offhand.
 * - The block modules stand down while a swap of ours is in flight ([isSwapping], added 2026-09-13
 *   on the user's report of ghosted totems). Javelin has no such coupling; here CrystalAura and
 *   AutoAnchor place, detonate and mine every tick they can, and an inventory slot they consume
 *   server-side inside our click's round trip is what makes the server answer that click with a
 *   full resync — the ghost. See [isSwapping] for the mechanism and the three phases it covers.
 * - Javelin's `EventRemoveEntity` / `EventRemoveEntityPacket` pair is one mechanism here, and
 *   `EventPopTotem` rides the same one: add / remove / entity-event packets are parked on the
 *   netty thread and replayed in arrival order at the head of the tick, because reading entity
 *   positions off the network thread would be a race. A removed crystal's position comes from
 *   [crystalPositions] — rebuilt from the world every tick, and topped up from the spawn packets
 *   themselves so a crystal that lived and died inside one packet drain still has a position.
 *   `EventPopTotem` is `ClientboundEntityEventPacket` with [EntityEvent.PROTECTED_FROM_DEATH].
 *
 * Deliberate deviations, all forced by the host:
 * - The pacing gate (300 ms, javelin's `tasksDelay`) also covers the swap back, which javelin
 *   leaves ungated. [ScheduleInventoryActionEvent] can fire many times inside one client tick
 *   (`InventoryManager`'s cycle loop), so an ungated branch would be a click spammer rather than
 *   a once-per-tick action.
 * - The swap back keeps [savedStack] until the item is actually back in the offhand, instead of
 *   clearing it the moment the click is scheduled. A chain dropped by the Constraints tree (say
 *   NoMovement while running) would otherwise lose the displaced item for good; javelin has no
 *   constraints and no such failure mode.
 * - Only players within [HOVER_PLAYER_RANGE] are ray traced for the obsidian check. The obsidian
 *   has to be within [OBSIDIAN_HORIZONTAL_RANGE] of us and within [OBSIDIAN_TRACE_RANGE] of their
 *   eyes, so a farther player can never satisfy the test — the bound cannot change a verdict.
 *
 * Dropped with the SystemDLC module (not present in javelin and not re-added): the Hand setting
 * and the whole main-hand path, the TNT and trident triggers, the sacrificial-talisman item
 * filter, PreferUnenchanted, SkipOnCooldown, NoSwapWhileEating, WithoutFireworks, and the AntiGhost
 * tree (Rescue / Park) that used [net.ccbluex.liquidbounce.utils.inventory.OffhandSyncTracker] and
 * [net.ccbluex.liquidbounce.utils.client.SilentHotbar] to survive a ghosted offhand swap.
 *
 * OVERLAP: this fork also ships the upstream Offhand module (ModuleOffhand, Player category,
 * `player/offhand/ModuleOffhand.kt` — note its alias "AutoTotem"), whose Totem mode manages
 * offhand totems with damage prediction. Both enabled at once schedule clicks through the same
 * InventoryManager with diverging item filters and can alternate swaps against each other. Enable
 * only one of the two totem managers at a time.
 */
object ModuleAutoTotem : ClientModule("AutoTotem", ModuleCategories.COMBAT) {

    /** `tasksDelay.isReached(300)` — minimum spacing between two of our container clicks. */
    private const val ACTION_DELAY_MS = 300L

    /** `cooldownTicks = 10` — held after a crystal right next to us vanished. */
    private const val CRYSTAL_BREAK_HOLD_TICKS = 10

    /** `cooldownTicks = 6` — held after our own totem popped, and for as long as we are falling. */
    private const val POP_HOLD_TICKS = 6

    /** `dx < 7 && dz < 7`, crystal not above us — the removed-crystal proximity test. */
    private const val BREAK_HORIZONTAL = 7.0

    /** `dy < 4` of the same test. */
    private const val BREAK_VERTICAL = 4.0

    /** `condition()` crystal gate: eye to crystal box centre at most 9. */
    private const val CRYSTAL_RANGE = 9.0

    /** `condition()` crystal gate: `|dy|` at most 8. */
    private const val CRYSTAL_MAX_DY = 8.0

    /** Ledger radius; only crystals this close can ever satisfy [BREAK_HORIZONTAL]. */
    private const val CRYSTAL_LEDGER_RANGE = 16.0

    /** `RaycastUtils.rayTraceBlock(player, 4.5F, Blocks.OBSIDIAN)` — the enemy's reach probe. */
    private const val OBSIDIAN_TRACE_RANGE = 4.5

    /** The hovered obsidian must be this close to us horizontally. */
    private const val OBSIDIAN_HORIZONTAL_RANGE = 10.0

    /** ...and this close in Y. */
    private const val OBSIDIAN_VERTICAL_RANGE = 5

    /** See "Deliberate deviations" — a bound on the obsidian probe that cannot change a verdict. */
    private const val HOVER_PLAYER_RANGE = 16.0

    /**
     * Dead man's switch for the SCHEDULED half of [isSwapping]: how long a click we handed to
     * `InventoryManager` may stay unsent before the gate lifts by itself.
     *
     * With the default `Constraints` the manager needs one `StartDelay` (1..2 ticks) to open the
     * inventory before the click goes out, so 500 ms is about ten times the normal distance. It has
     * to exist because the chain can still be dropped after [armSwapWindow] admitted it — the
     * manager defers a chain [net.ccbluex.liquidbounce.utils.inventory.HandSwapGate] does not allow,
     * and abandons the cycle on `requiresUpdate` — and a gate that stands two modules down must
     * never be able to stick.
     *
     * It bounds ONE arming, which is not the same thing: a chain dropped over and over is re-offered
     * and re-armed faster than this expires. [MAX_SWAP_RUN_MS] is the bound on that.
     */
    private const val PENDING_SWAP_MS = 500L

    /**
     * Dead man's switch for the SENT half of [isSwapping]. [OffhandSyncTracker] caps its own
     * confirmation window at 1000 ms, but it also reports `isSuspectedDrop` as unconfirmed until
     * server truth arrives — which, for a click that hit one of the three silent branches, may be
     * never. This is the bound that covers that case.
     */
    private const val CONFIRM_SWAP_MS = 1000L

    /**
     * How long an unbroken RUN of scheduled-but-never-sent clicks may hold phase 1 of [isSwapping].
     *
     * [PENDING_SWAP_MS] bounds ONE arming and nothing more. `equip` offers the same chain every 300 ms
     * ([tasksDelay]) for as long as the totem is still missing, and every offer that passes the
     * Constraints tree re-stamps [swapScheduledMs] - so a chain that is admitted and then repeatedly
     * DROPPED (`InventoryManager` defers what `HandSwapGate` refuses, and abandons a cycle on
     * `requiresUpdate`) re-arms a 500 ms window every 300 ms and the gate never lapses. Both auras then
     * stand fully down, search and click alike, for as long as that lasts. The dead man's switch was
     * documented as covering this and it does not; this is the bound that does.
     *
     * Degradation, not failure: when the run is exhausted only phase 1 is dropped. A click that does
     * eventually leave is still covered by `HandSwapGate` on the wire and by [CONFIRM_SWAP_MS]
     * afterwards - we lose the cover for the ticks `InventoryManager` spends opening the inventory,
     * which is the phase that only exists because no packet marks it.
     */
    private const val MAX_SWAP_RUN_MS = 1500L

    /**
     * How long phase 1 stays down after an exhausted run before a fresh one may start. Same duty-cycle
     * shape as `combat/totem/SwapQuiet`'s `RETRY_COOLDOWN_TICKS`, and for the same reason: the caller
     * re-requests on its own clock, so without a cooldown the next tick simply starts another full run
     * and the bound buys nothing.
     */
    private const val SWAP_RUN_COOLDOWN_MS = 1000L

    private val inventoryConstraints = tree(PlayerInventoryConstraints())

    /** «Здоровье» — health at or below which a totem is taken (4.0, 1..20). */
    private val health by float("Health", 4f, 1f..20f, "HP")

    /** «Здоровье на элитре» — the same threshold while an elytra is worn (11.0, 1..20). */
    private val elytraHealth by float("ElytraHealth", 11f, 1f..20f, "HP")

    /** «Отсутствие брони» — the threshold while any armour piece is missing (10.0, 1..20). */
    private val armorBroken by float("ArmorBroken", 10f, 1f..20f, "HP")

    /** «Сохранять зачар» — hold the enchanted offhand totem back and spend plain ones first. */
    private val saveEnchanted by boolean("SaveEnchanted", true)

    /** «Работать на крисы» — also act on nearby crystals and on an enemy aiming at obsidian. */
    private val crystals by boolean("Crystals", false)

    /**
     * Incoming-damage prediction, OFF by default.
     *
     * Javelin triggers on the health bar alone and this module is a 1:1 port of it, an invariant
     * AGENTS.md guards; switching this on is a deliberate deviation the user asked for on
     * 2026-09-14, not the module's own semantics. Off, [condition] behaves exactly as before.
     */
    private val incomingDamage = tree(IncomingDamage(this, enabledByDefault = false))

    /**
     * Hotbar staging for [TotemRoute.MAIN_HAND]. OURS, not shared: the anchor, the displaced item
     * and the slot policy belong to this module only.
     */
    private val staging = tree(TotemStaging(this))

    /** The pop-to-replenish spread for this module's swaps. */
    private val jitter = tree(SwapJitter(this))

    /**
     * The anticheat gate for this module's clicks.
     *
     * [isSwapping] is what this module calls its swap window, so that is what keeps the movement
     * stop alive. Declared after [staging] because that flag reads it.
     */
    private val quiet = tree(SwapQuiet(this) { isSwapping })

    /** Which way a totem reaches the offhand. See [TotemRoute]. */
    private val route by enumChoice("Route", TotemRoute.OFFHAND_CLICK)

    /**
     * How a totem gets into the offhand.
     *
     * [OFFHAND_CLICK] is javelin's original and the default: ONE
     * `ServerboundContainerClickPacket` SWAP whose button is the offhand, scheduled through
     * `InventoryManager` and therefore paced and vetted by the Constraints tree.
     *
     * [MAIN_HAND] is the route the user asked for on 2026-09-14 - "always keep a totem in the
     * hotbar; when one is needed, switch to the slot and take it into the second hand". It keeps a
     * totem staged in the hotbar through [TotemStaging] and then presses the VANILLA offhand swap
     * on it: `SetCarriedItem` to the staged slot, `SWAP_ITEM_WITH_OFFHAND`, `SetCarriedItem` back.
     *
     * Why it is worth having: `SWAP_ITEM_WITH_OFFHAND` is a `ServerboundPlayerActionPacket`, so it
     * carries NO container state id and cannot be answered with the full-inventory resync that
     * ghosts an offhand click (see [isSwapping] for that mechanism). The one state-id click left in
     * the feature is the staging SWAP, and that one happens AHEAD of the danger window instead of
     * inside it, which is the whole point. The auras therefore only have to stand down for
     * [TotemStaging.isStaging]; the swap itself is covered by `HandSwapGate`, which arms on
     * `SWAP_ITEM_WITH_OFFHAND` of its own accord.
     *
     * What it costs: the swap burst is up to three packets in one tick (two `SetCarriedItem` around
     * the action) and it does NOT go through the Constraints tree, because a player action is not a
     * container click and `InventoryManager` does not carry it. That wire shape is not vanilla's -
     * vanilla emits at most one `SetCarriedItem` per tick, from `ensureHasSentCarriedItem` - and it
     * is the same shape `player/offhand/ModuleOffhand`'s SMART switch already uses here. It has NOT
     * been audited against Grim: the clone AGENTS.md points at is gone from this machine, so no
     * check source could be read this session. Treat the route as UNVERIFIED against anticheat and
     * keep [OFFHAND_CLICK] as the default until it can be.
     */
    enum class TotemRoute(override val tag: String) : Tagged {
        OFFHAND_CLICK("OffhandClick"),
        MAIN_HAND("MainHand"),
    }

    /** «Падение» — take a totem while falling; the two thresholds are its children. */
    private object Falling : ToggleableValueGroup(ModuleAutoTotem, "Falling", false) {

        /** «При падении» — fall distance that triggers on foot (10, 3..20). */
        val fallDistance by int("FallDistance", 10, 3..20, "blocks")

        /** «При падении на элитре» — fall distance while gliding (30, 25..65). */
        val elytraFallDistance by int("ElytraFallDistance", 30, 25..65, "blocks")

    }

    init {
        tree(Falling)
    }

    /** Javelin's search space: hotbar 0..8 then main inventory 9..35, in that order. */
    private val searchOrder = Slots.Hotbar + Slots.Inventory

    /** `savedGeneralStack` — the enchanted totem that was in the offhand when we first acted. */
    private var savedGeneralStack: ItemStack? = null

    /** `savedStack` — the item the totem displaced out of the offhand, to be put back. */
    private var savedStack: ItemStack? = null

    /** `cooldownTicks` — see the class KDoc; gates the swap back only. */
    private var cooldownTicks = 0

    /** `tasksDelay` — the 300 ms pacing between our own clicks. */
    private val tasksDelay = Chronometer()

    /** Wall clock of the last click we handed to `InventoryManager`, or 0 while nothing is owed. */
    @Volatile
    private var swapScheduledMs = 0L

    /** Wall clock of the last of those clicks that actually left the client, or 0. */
    @Volatile
    private var swapSentMs = 0L

    /**
     * Wall clock the current unbroken run of armings started at, or 0 when none is open. Cleared by a
     * click that actually leaves ([observeOwnSwap]) and by [reset]; bounded by [MAX_SWAP_RUN_MS].
     */
    @Volatile
    private var swapRunStartedMs = 0L

    /**
     * Whether a totem swap of ours is in flight, in ANY of its three phases — the flag the block
     * modules stand down on so the totem does not ghost.
     *
     * 1. **Scheduled, not sent.** [equip] / [swapBack] handed a chain to `InventoryManager`, which
     *    opens the inventory and waits out `StartDelay` before it clicks. Nothing covers this today:
     *    [net.ccbluex.liquidbounce.utils.inventory.HandSwapGate] arms on the OUTGOING packet, so for
     *    those ticks our menu is open server-side with no swap on the wire yet. Bounded by
     *    [PENDING_SWAP_MS] per arming and by [MAX_SWAP_RUN_MS] per unbroken run of them.
     * 2. **On the wire.** `HandSwapGate` owns this tick, and keeps owning it while the server still
     *    has the menu open — this flag simply stays up with it.
     * 3. **Sent, unconfirmed.** [OffhandSyncTracker.isUnconfirmed]: agreement is answered with
     *    SILENCE, so for about one round trip the totem we render may not exist server-side.
     *    Bounded by [CONFIRM_SWAP_MS].
     *
     * Why the block modules have to wait for all three: every interaction THEY send consumes an
     * inventory slot server-side (a crystal, an anchor, a glowstone, a mined block), and the server
     * bumps the open menu's state id for it. Our click carries the state id the client had BEFORE
     * that, so `handleContainerClick` answers it with a full `ClientboundContainerSetContentPacket`
     * resync (`ServerGamePacketListenerImpl.java:1915-1916`) which overwrites the offhand we
     * predicted — and a totem that is drawn in a hand the server does not have is a totem that does
     * not pop. That is the ghost, and the auras placing into this window is what produces it.
     *
     * Read by `combat/crystalaura/ModuleCrystalAura` and `combat/autoanchor/ModuleAutoAnchor`, both
     * of which stand ALL the way down for it — no search, no hotbar switch, no click — because a
     * `SetCarriedItem` of theirs would otherwise still sit between our inventory open and our click.
     */
    val isSwapping: Boolean
        get() {
            if (!running) {
                return false
            }

            val now = System.currentTimeMillis()
            val pending = swapScheduledMs != 0L && now - swapScheduledMs < PENDING_SWAP_MS &&
                now - swapRunStartedMs < MAX_SWAP_RUN_MS
            val unconfirmed = swapSentMs != 0L && now - swapSentMs < CONFIRM_SWAP_MS &&
                OffhandSyncTracker.isUnconfirmed

            // The MainHand route's own click is the STAGING swap; the offhand swap that follows it
            // is a player action with no state id and cannot ghost, so this is its whole window.
            val staging = route == TotemRoute.MAIN_HAND && staging.isStaging

            return pending || unconfirmed || staging
        }

    /** Crystal entity id to the position it last occupied; written on the main thread only. */
    private val crystalPositions = Int2ObjectOpenHashMap<Vec3>()

    /**
     * Netty thread to main thread. One queue for all three packet kinds so a spawn that is followed
     * by its own removal inside the same drain is still seen in that order; the netty side only
     * parks the packet object and never touches the world.
     */
    private val packetQueue = ConcurrentLinkedQueue<Packet<*>>()

    override fun onEnabled() = reset()

    override fun onDisabled() = reset()

    private fun reset() {
        savedGeneralStack = null
        savedStack = null
        cooldownTicks = 0
        tasksDelay.reset(0)
        swapScheduledMs = 0L
        swapSentMs = 0L
        swapRunStartedMs = 0L
        crystalPositions.clear()
        packetQueue.clear()
        incomingDamage.reset()

        // Unconditional since 2026-09-16: the staging instance is this module's own, so there is
        // no longer another module's anchor to wipe. It used to be route-gated for exactly that.
        staging.reset()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { reset() }

    // ------------------------------------------------------------------------------------------
    // Ingress (netty thread — queue only)
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.OUTGOING) {
            observeOwnSwap(event)
            return@handler
        }

        if (event.origin != TransferOrigin.INCOMING || !event.original) {
            return@handler
        }

        val packet = event.packet
        val interesting = packet is ClientboundRemoveEntitiesPacket ||
            (packet is ClientboundAddEntityPacket && packet.type == EntityType.END_CRYSTAL) ||
            (packet is ClientboundEntityEventPacket && packet.eventId == EntityEvent.PROTECTED_FROM_DEATH)

        if (interesting) {
            packetQueue += packet
        }
    }

    /**
     * Phase 1 to phase 3 of [isSwapping]: the click we scheduled has left the client.
     *
     * The click is recognised as OURS by [swapScheduledMs] alone — we are waiting for one — rather
     * than by comparing slots, because `InventoryManager` may have paced a miss-click or a foreign
     * chain in front of it and either shape is still the window we have to cover. The two forms
     * tested for are the ones the two [InventoryAction.Click.performSwap] calls below produce: a
     * SWAP whose hotbar button is the offhand ([equip]), and a click ON the offhand slot with a
     * hotbar button ([swapBack] to a hotbar slot). Same test as `OffhandSyncTracker.touchesOffhand`,
     * which is private to that object.
     *
     * Not a netty-thread concern in practice — `InventoryManager` sends from the client tick — but
     * the fields are volatile anyway, since outgoing [PacketEvent]s carry no such guarantee.
     */
    private fun observeOwnSwap(event: PacketEvent) {
        if (event.isCancelled || swapScheduledMs == 0L) {
            return
        }

        val packet = event.packet as? ServerboundContainerClickPacket ?: return
        val touchesOffhand =
            (packet.containerInput == ContainerInput.SWAP &&
                packet.buttonNum.toInt() == Inventory.SLOT_OFFHAND) ||
                (packet.containerId == InventoryMenu.CONTAINER_ID &&
                    packet.slotNum.toInt() == InventoryMenu.SHIELD_SLOT)

        if (touchesOffhand) {
            swapScheduledMs = 0L
            swapSentMs = System.currentTimeMillis()
            // The run did what it was for; the next arming starts a fresh budget.
            swapRunStartedMs = 0L
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tick bookkeeping (main thread)
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        debugParameter("Totem Hold") { cooldownTicks }
        debugParameter("Totem Saved") { savedStack?.hoverName?.string ?: "-" }
        debugParameter("Totem Swapping") { isSwapping }
        debugParameter("Totem Predicted") { incomingDamage.lastPredicted }

        // Drained BEFORE the ledger is refreshed: a removed crystal is already out of the world by
        // now, so its position has to come from the previous refresh or from its own spawn packet.
        drainPackets()
        refreshCrystalLedger()

        // `if (playerFalling) cooldownTicks = 6` — javelin does this inside condition(), which runs
        // once per tick there. Here condition() holds no state the RULES read, so the stamp lives on
        // the tick instead. (`IncomingDamage` does memoize inside it, but that is a per-tick cache
        // whose only effect is to make the repeated calls cheap - it cannot change a verdict.)
        if (isFalling()) {
            cooldownTicks = POP_HOLD_TICKS
        }

        if (cooldownTicks > 0) {
            cooldownTicks--
        }
    }

    /**
     * Replays the queued packets in arrival order.
     *
     * The spawn branch is what lets a crystal that was placed and destroyed inside a single packet
     * drain still be recognised: it never lives at a tick boundary, so [refreshCrystalLedger] would
     * never see it, and its removal would have no position to test.
     */
    private fun drainPackets() {
        while (true) {
            when (val packet = packetQueue.poll() ?: break) {
                is ClientboundAddEntityPacket ->
                    crystalPositions.put(packet.id, Vec3(packet.x, packet.y, packet.z))

                // `EventRemoveEntity` / `EventRemoveEntityPacket` — a crystal near us is gone.
                // `getEntityIds()` is a fastutil IntList; indexed reads avoid boxing an iterator.
                is ClientboundRemoveEntitiesPacket -> {
                    val ids = packet.entityIds
                    for (index in 0 until ids.size) {
                        onCrystalRemoved(ids.getInt(index))
                    }
                }

                // `EventPopTotem` — `if (e.getPlayer() == mc.player && cooldownTicks < 6)`.
                is ClientboundEntityEventPacket -> {
                    val level = mc.level
                    if (level != null && packet.getEntity(level) === player &&
                        cooldownTicks < POP_HOLD_TICKS
                    ) {
                        cooldownTicks = POP_HOLD_TICKS
                    }
                }
            }
        }
    }

    /** `dx < 7 && dy < 4 && dz < 7` and the crystal not above us, measured where it last stood. */
    private fun onCrystalRemoved(id: Int) {
        val position = crystalPositions.remove(id) ?: return

        val dx = abs(position.x - player.x)
        val dy = abs(position.y - player.y)
        val dz = abs(position.z - player.z)

        if (dx < BREAK_HORIZONTAL && dy < BREAK_VERTICAL && dz < BREAK_HORIZONTAL &&
            player.y >= position.y
        ) {
            cooldownTicks = CRYSTAL_BREAK_HOLD_TICKS
        }
    }

    /** Keeps [crystalPositions] current so a removal one tick later still has a position to test. */
    private fun refreshCrystalLedger() {
        crystalPositions.clear()

        val nearby = world.getEntitiesOfClass(
            EndCrystal::class.java,
            player.boundingBox.inflate(CRYSTAL_LEDGER_RANGE)
        ) { true }

        for (crystal in nearby) {
            crystalPositions.put(crystal.id, crystal.position())
        }
    }

    // ------------------------------------------------------------------------------------------
    // Brain (`update8` in javelin — re-hosted on the sanctioned scheduling event)
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (player.isDeadOrDying) {
            reset()
            return@handler
        }

        // The MainHand route keeps a totem in the bar at ALL times, not only once we are low. The
        // staging swap is a container click and must NOT sit on the critical path of the hand swap
        // it feeds: staged ahead of time, the dangerous tick costs one player action and nothing
        // else. This is the refill too - `stagedSlot` goes null the tick the staged totem leaves,
        // so the next cycle pulls the following one in. Idempotent, so `equipViaMainHand` asking
        // again in the same cycle is free.
        if (route == TotemRoute.MAIN_HAND) {
            staging.ensureStaged(event, inventoryConstraints, quiet, saveEnchanted)
        }

        if (condition()) {
            equip(event)
            return@handler
        }

        if (savedStack != null && cooldownTicks == 0) {
            swapBack(event)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Conditions (`condition()`)
    // ------------------------------------------------------------------------------------------

    /**
     * `condition()`, minus the `cooldownTicks` write — that side effect moved to the tick handler
     * because this method is read several times per tick here.
     *
     * That "several times per tick" is also why [IncomingDamage] memoizes: an uncached block scan
     * called once per `InventoryManager` cycle would walk a ~4000-block sphere several times a tick.
     * The cache is not a rule-bearing side effect - it is keyed on the health and the threshold, so
     * it cannot answer differently from a fresh computation.
     */
    private fun condition(): Boolean {
        val playerHealth = effectiveHealth()

        val threshold = if (ArmorItemSlot.CHEST.itemStack.`is`(Items.ELYTRA)) elytraHealth else health
        if (playerHealth <= threshold) {
            return true
        }

        if (playerHealth <= armorBroken && Slots.Armor.any { it.itemStack.isEmpty }) {
            return true
        }

        if (isFalling()) {
            return true
        }

        // Last of the health-shaped tests and after javelin's own, so the original ones keep
        // deciding first and the cheap ones stay cheap. The predictor caches per tick, which it has
        // to: this function runs once per `InventoryManager` CYCLE, not once per tick.
        if (incomingDamage.enabled && incomingDamage.warrantsTotem(playerHealth, threshold)) {
            return true
        }

        return crystals && (crystalNearby() || obsidianHovered())
    }

    /**
     * Health plus absorption, where absorption only counts while the effect has more than 25 ticks
     * to run. Reading `getAbsorptionAmount()` unconditionally would let a hearts bar that is about
     * to expire hold the total above the trigger.
     */
    private fun effectiveHealth(): Float {
        val effect = player.getEffect(MobEffects.ABSORPTION)
        val absorption = if (effect != null && effect.duration > 25) player.absorptionAmount else 0f
        return player.health + absorption
    }

    /** `playerFalling` — fall distance past the threshold for the current mode of travel. */
    private fun isFalling(): Boolean {
        if (!Falling.enabled) {
            return false
        }

        val limit = if (player.isFallFlying) Falling.elytraFallDistance else Falling.fallDistance
        return player.fallDistance > limit
    }

    /**
     * A live crystal at most [CRYSTAL_RANGE] from the eye (measured to its box centre, the point an
     * explosion originates from), within [CRYSTAL_MAX_DY] in Y and never above us — a crystal over
     * our head is not the one about to be hit into our feet.
     */
    private fun crystalNearby(): Boolean {
        val eye = player.eyePosition

        return world.getEntitiesOfClass(
            EndCrystal::class.java,
            player.boundingBox.inflate(CRYSTAL_RANGE + 1.0)
        ) { true }.any { crystal ->
            eye.distanceTo(crystal.boundingBox.center) <= CRYSTAL_RANGE &&
                abs(player.y - crystal.y) <= CRYSTAL_MAX_DY &&
                player.y >= crystal.y
        }
    }

    /**
     * The tick before the crystal: somebody else's crosshair is resting on an obsidian block inside
     * their own reach, that block is next to us, and their main hand holds nothing that is being
     * used for something else (BLOCK is a shield, NONE is everything inert — a crystal included).
     */
    private fun obsidianHovered(): Boolean {
        val selfPos = player.blockPosition()

        for (other in world.players()) {
            if (other === player || player.distanceTo(other) > HOVER_PLAYER_RANGE) {
                continue
            }

            val animation = other.mainHandItem.useAnimation
            if (animation != ItemUseAnimation.BLOCK && animation != ItemUseAnimation.NONE) {
                continue
            }

            val pos = traceObsidian(other) ?: continue

            if (abs(selfPos.y - pos.y) > OBSIDIAN_VERTICAL_RANGE || player.y < pos.y) {
                continue
            }

            val horizontal = hypot((selfPos.x - pos.x).toDouble(), (selfPos.z - pos.z).toDouble())
            if (horizontal < OBSIDIAN_HORIZONTAL_RANGE) {
                return true
            }
        }

        return false
    }

    /** `RaycastUtils.rayTraceBlock(player, 4.5F, Blocks.OBSIDIAN)` — their eye along their look. */
    private fun traceObsidian(other: Player): BlockPos? {
        val start = other.eyePosition
        val end = start.add(other.lookAngle.scale(OBSIDIAN_TRACE_RANGE))

        val hit = rayTraceCollidingBlocks(start, end) ?: return null
        return hit.blockPos.takeIf { world.getBlockState(it).`is`(Blocks.OBSIDIAN) }
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    /**
     * Opens phase 1 of [isSwapping] for the click that was just scheduled.
     *
     * [InventoryAction.canPerformAction] is the SAME test `InventoryManager` filters the schedule
     * with (`InventoryManager.kt:126-127`), read here in the same cycle and therefore against the
     * same world: a chain the Constraints tree refuses never reaches the manager's cycle at all, so
     * no click is coming and nothing may be stood down for it. Without this the gate would be a trap
     * — a requirement that keeps failing (`Requires = NoMovement` while we run) has [equip] offering
     * the same chain every 300 ms forever, and each offer would re-arm a window no packet ever ends.
     *
     * A fresh arming also drops the previous [swapSentMs]: that click is either confirmed or
     * superseded by this one, and leaving it standing would hold the gate on a window that is over.
     */
    private fun armSwapWindow(action: InventoryAction) {
        if (!action.canPerformAction(inventoryConstraints)) {
            return
        }

        val now = System.currentTimeMillis()
        // The run budget of [MAX_SWAP_RUN_MS]: a run that has been going that long without a single
        // click reaching the wire stops holding phase 1, and a new one may only start once the
        // cooldown after it has passed. `swapScheduledMs` is still stamped throughout, so the moment a
        // click does leave, [observeOwnSwap] hands over to phase 3 as usual.
        if (swapRunStartedMs == 0L || now - swapRunStartedMs >= MAX_SWAP_RUN_MS + SWAP_RUN_COOLDOWN_MS) {
            swapRunStartedMs = now
        }
        swapScheduledMs = now
        swapSentMs = 0L
    }

    private fun ItemStack.isTotem() = this.`is`(Items.TOTEM_OF_UNDYING)

    private fun ItemStack.isEnchantedTotem() = isTotem() && getEnchantmentCount() > 0

    private fun ItemStack.isPlainTotem() = isTotem() && getEnchantmentCount() == 0

    /**
     * The totem-into-offhand half of javelin's tick body.
     *
     * With «Сохранять зачар» off it is the plain original: take the first totem in 0..35 whenever
     * the offhand holds no totem. With it on, an enchanted totem already in the offhand is recorded
     * as [savedGeneralStack] and is additionally allowed to be REPLACED by a plain one, so the
     * enchanted stack ends up back in the inventory and is spent last.
     */
    private fun equip(event: ScheduleInventoryActionEvent) {
        val offhand = player.offhandItem

        var target = searchOrder.find { it.itemStack.isTotem() }
        var ready = target != null && !offhand.isTotem()

        if (saveEnchanted) {
            if (savedGeneralStack == null && offhand.isEnchantedTotem()) {
                savedGeneralStack = offhand
            }

            val plain = searchOrder.find { it.itemStack.isPlainTotem() }
            val enchanted = searchOrder.find { it.itemStack.isEnchantedTotem() }
            // Identity, exactly like javelin's `!stack.equals(savedGeneralStack)`: ItemStack does
            // not override equals, and a swap moves the very same instance between slots, so this
            // reads as "any enchanted totem except the one we started the fight with".
            val otherEnchanted = searchOrder.find {
                it.itemStack.isEnchantedTotem() && it.itemStack !== savedGeneralStack
            }

            target = plain ?: otherEnchanted ?: enchanted ?: target
            ready = target != null && (!offhand.isTotem() || (offhand.isEnchantedTotem() && plain != null))
        }

        val slot = target?.takeIf { ready } ?: return

        if (!tasksDelay.hasAtLeastElapsed(ACTION_DELAY_MS)) {
            return
        }

        // A plain totem in the offhand is never recorded: putting it back would undo our own work.
        if (savedStack == null && !offhand.isEmpty && (!offhand.isTotem() || offhand.isEnchantedTotem())) {
            savedStack = offhand
        }

        if (route == TotemRoute.MAIN_HAND) {
            equipViaMainHand(event)
            return
        }

        // Jittered hold, drawn once per swap. This is the span `AutoTotemB` measures, so it is
        // where the spread has to go - see `SwapJitter` on why randomising the pacing instead
        // would move a number nobody looks at. It is waited out BEFORE the stop is asked for:
        // holding the quiet across the whole hold would freeze the player for it, and asking only
        // in front of the hold makes the stop flap on and off for as many ticks as it lasts.
        if (!jitter.allows()) {
            return
        }

        // Grim's `MultiActionsC` (`checks/impl/multiactions/MultiActionsC.java:37-51`, NOT
        // experimental, enabled by the shipped punishments file) cancels ANY `CLICK_WINDOW` sent
        // while we sprint, move or sneak. That cancel is the ghost: `CompensatedInventory` applies
        // the click before the cancel (`:261`) so client and Grim both have the totem, the real
        // server does not, and the only resync is conditional and ~5 ticks late. Sprinting alone
        // is enough and is ungated by version, which is why this used to ghost in almost every
        // fight - you sprint in a fight.
        if (!quiet.isQuiet) {
            quiet.requestStop()
            return
        }

        val action = InventoryAction.Click.performSwap(from = slot, to = HotbarItemSlot.OFFHAND)
        event.schedule(inventoryConstraints, action)
        tasksDelay.reset()
        jitter.release()
        armSwapWindow(action)
    }

    /**
     * [TotemRoute.MAIN_HAND]: get a totem into the hotbar, then press the vanilla offhand swap on
     * that slot.
     *
     * The three packets are the same shape `player/offhand/ModuleOffhand`'s SMART switch sends, and
     * the held slot they move around is the one the SERVER holds - the carried-item tracker's
     * `carriedIndex` (`sendSwapHotbarSlotWithOffhand`), not `Inventory.selected` and not
     * [SilentHotbar.serversideSlot] either: the first is the client's slot, the second is what the
     * tracker will announce LATER this tick, after a module took or released a silent slot.
     *
     * [armSwapWindow] is deliberately NOT called: it opens the window for a click that has been
     * SCHEDULED but not sent, and there is no such phase here - the action is on the wire the moment
     * this returns. From there `HandSwapGate` owns the tick (it arms on `SWAP_ITEM_WITH_OFFHAND`),
     * and there is no third phase to cover either, because a player action carries no state id to
     * be resynced and [OffhandSyncTracker] cannot see one anyway (its `touchesOffhand` only matches
     * container clicks). The staging click is the only thing the auras have to wait for, and
     * [isSwapping] already reports it.
     */
    private fun equipViaMainHand(event: ScheduleInventoryActionEvent) {
        // Also the refill: `stagedSlot` goes null the tick the staged totem leaves, so the next
        // cycle stages the next one with no pop detection of its own.
        if (staging.ensureStaged(event, inventoryConstraints, quiet, saveEnchanted)
            != TotemStaging.StageState.READY
        ) {
            return
        }

        val index = staging.stagedSlot?.hotbarIndex ?: return

        // Nothing in the schedule paces a player action, so the gate is read here instead. A swap
        // sent inside another swap's window is the one thing `HandSwapGate` exists to stop.
        if (HandSwapGate.holdsClicks) {
            return
        }

        // Jittered hold first, for the same reason as the offhand route above: the stop belongs to
        // the ticks right before the swap, not to the random hold in front of it.
        if (!jitter.allows()) {
            return
        }

        // Not a `CLICK_WINDOW`, so `MultiActionsC` does not reach it - but the stop is still
        // required here. Vanilla releases sprint by sending `STOP_SPRINTING`, and Grim's
        // `PacketOrderF` (`checks/impl/packetorder/PacketOrderF.java:72-84`) cancels a
        // `SWAP_ITEM_WITH_OFFHAND` that follows a sprint or sneak entity action inside the same
        // tick. Waiting for the quiet to have SETTLED puts that action ticks behind us instead.
        if (!quiet.isQuiet) {
            quiet.requestStop()
            return
        }

        // Both halves of this burst are separate TotemGuard violations when our own inventory is
        // open server-side, and `InventoryManager` may well have the window open from the staging
        // click that fed us - a real collision, not a theoretical one. Cited in
        // `SwapQuiet.allowsHandSwapNow`, which owns the rule so it can be switched off there with
        // the other protections.
        if (!quiet.allowsHandSwapNow) {
            return
        }

        // Bracketed with the tracker's `carriedIndex`, the slot the server holds right now - see the helper for why
        // `SilentHotbar.serversideSlot`, which this used, is off by a tick whenever a silent slot changed this tick.
        network.sendSwapHotbarSlotWithOffhand(index)

        tasksDelay.reset()
        jitter.release()
    }

    /**
     * The restore half. Javelin drops the state the moment the click is emitted; here it is kept
     * until the item is actually back in the offhand, because the Constraints tree can refuse a
     * chain and an item lost that way would never return. The state is dropped when the stack
     * cannot be found in the hotbar or inventory at all — consumed, dropped, or moved by hand.
     */
    private fun swapBack(event: ScheduleInventoryActionEvent) {
        val saved = savedStack ?: return

        if (player.offhandItem === saved) {
            savedGeneralStack = null
            savedStack = null
            return
        }

        val slot = findSlotOf(saved)
        if (slot == null) {
            savedGeneralStack = null
            savedStack = null
            return
        }

        if (!tasksDelay.hasAtLeastElapsed(ACTION_DELAY_MS)) {
            return
        }

        // Same `CLICK_WINDOW` cancel applies to the restore. Losing this one does not cost a life,
        // but a cancelled click still desyncs the offhand for good, so it waits too.
        if (!quiet.isQuiet) {
            quiet.requestStop()
            return
        }

        // A hotbar slot is a SWAP button of its own, so there the offhand is the CLICKED slot;
        // everywhere else the inventory slot is clicked with the offhand button. Both are the
        // vanilla F-swap shape — javelin's `45` / `slot - 36` pair without the arithmetic.
        val action = if (slot is HotbarItemSlot) {
            InventoryAction.Click.performSwap(from = HotbarItemSlot.OFFHAND, to = slot)
        } else {
            InventoryAction.Click.performSwap(from = slot, to = HotbarItemSlot.OFFHAND)
        }

        event.schedule(inventoryConstraints, action)
        tasksDelay.reset()
        armSwapWindow(action)
    }

    /**
     * `InventoryUtils.findNeededStack`. Identity first — a client-side swap moves the instance, so
     * that is the exact stack we displaced. The component fallback covers what identity cannot: a
     * `ClientboundContainerSetContentPacket` resync rebuilds every stack in the menu, after which
     * the only thing left to recognise the item by is what it is made of.
     */
    private fun findSlotOf(stack: ItemStack): ItemSlot? =
        searchOrder.find { it.itemStack === stack }
            ?: searchOrder.find { !it.itemStack.isEmpty && ItemStack.matches(it.itemStack, stack) }

}
