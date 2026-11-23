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
import net.ccbluex.liquidbounce.event.events.ClientBlockPlaceEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.AnchorRotationsValueGroup.AnchorRotationTiming.GRIM_SNAP
import net.ccbluex.liquidbounce.features.module.modules.combat.AnchorRotationsValueGroup.AnchorRotationTiming.NORMAL
import net.ccbluex.liquidbounce.features.module.modules.combat.AnchorRotationsValueGroup.AnchorRotationTiming.ON_TICK
import net.ccbluex.liquidbounce.features.module.modules.combat.AnchorRotationsValueGroup.AnchorRotationTiming.SNAP
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.ModuleAutoAnchor
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.PendingPlacements
import net.ccbluex.liquidbounce.utils.combat.autoanchor.AnchorAimSearch
import net.ccbluex.liquidbounce.utils.combat.autoanchor.RotationConvergenceTracker
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.world.respawnAnchorWorks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.ccbluex.liquidbounce.utils.entity.ping
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * AnchorOptimizer module
 *
 * Fully automatic respawn-anchor detonation: finds an anchor WE placed within reach, charges it
 * with glowstone if it is empty, and detonates it on the next tick. There is no damage model at
 * all: what the explosion achieves, and what it does to us, is the player's business — the module
 * only makes the charge/detonate cycle as fast and as clean as vanilla allows.
 *
 * **Why "optimizer":** vanilla `RespawnAnchorBlock.explode` always explodes with power `5.0F`,
 * regardless of the charge level — so a second, third or fourth glowstone adds exactly zero damage.
 * The module therefore charges at most ONCE (and not at all when the anchor already carries a
 * charge), which is both the cheapest and the fastest possible cycle.
 *
 * **How the detonation click actually works.** `RespawnAnchorBlock.useItemOn` returns
 * `TRY_WITH_EMPTY_HAND` for any item that is not respawn fuel. That result does NOT mean the hand
 * must be empty — it is the block telling the caller "my item branch did nothing, run my item-less
 * interaction", and `ServerPlayerGameMode.useItemOn` then calls `useWithoutItem` (which explodes)
 * whatever is being held. Its only condition is `hand == MAIN_HAND`, which is why every interaction
 * here goes through the main hand. Two vanilla branches defeat it, and both are gated against:
 * respawn fuel in the OFFHAND on a still-chargeable anchor returns `PASS` instead
 * ([blockedByOffhandFuel]), and sneaking with a non-empty hand skips the block interaction
 * altogether ([pauseReason]).
 *
 * **Speed.** The whole cycle costs one interaction per tick, which is the vanilla ceiling:
 * - anchor already charged → 1 tick (detonate)
 * - anchor at charge 0 → 2 ticks (charge, detonate)
 *
 * Everything runs on [RotationUpdateEvent], at the head of the client tick: the aim is submitted
 * before `RotationManager.update()` steps it, and the interaction leaves before that tick's movement
 * packet. So the server validates the interaction against the rotation from the previous flying —
 * which is exactly the one [readyHit] gated on, rather than one it never saw.
 *
 * **Anti-flag discipline** (Grim, non-experimental):
 * - *Raytrace* — every interaction re-casts an OUTLINE ray from the eye along
 *   [RotationManager.serverRotation] (what the server actually holds, not what we requested) and
 *   only fires when it lands on the anchor block itself. Wall-clipping or a half-converged aim
 *   simply skips the tick (RotationPlace / PositionPlace).
 * - *No bad packets* — the vanilla `useItemOn` path (so the interaction sequence id is generated
 *   by vanilla's prediction), the swing sent only when vanilla would send it
 *   (`SwingSource.CLIENT`), hotbar changes announced through the vanilla tracker
 *   (`ensureHasSentCarriedItem`, never a duplicate SetCarriedItem), and never an interaction while
 *   a screen is open, while eating or from a vehicle.
 * - *One action per tick* — [actionDelay] is floored at 1 tick because no vanilla client can emit
 *   two use-item packets inside a single tick (MultiPlace / MultiInteractA).
 * - *Packet sub-order* — SetCarriedItem → UseItemOn → Animation, exactly like vanilla. The swap is
 *   only performed AFTER the raytrace gate has passed, so a rejected tick never leaves a stray
 *   held-item change on the wire; and since at most one thing happens per tick, a window reads
 *   `[swap]`, `[swap, use]` or `[use]` and never `[use, swap]` (PacketOrderE).
 * - *One swap per tick* — vanilla resolves the hotbar selection once per tick in `handleKeybinds`
 *   (see [swapTo]).
 * - *DuplicateRotPlace* — gated through the shared [RotationConvergenceTracker].
 * - *FarPlace* — the clicked box is clamped to `blockInteractionRange`.
 * - *MultiActionsF / MultiActionsE* — a block click and an entity attack in one tick window flag
 *   whichever comes second, so the module fully yields to KillAura while it holds a target; and
 *   nothing is ever sent while an item is in use (the swing would flag, and no vanilla client
 *   clicks blocks mid-use anyway).
 * - *SilentHotbar* — while another module enforces a silent slot the carried-item announcement
 *   would not carry ours, so the module stands down entirely.
 *
 * The detonation swap is normally free: the slot restored after charging IS the slot the anchor is
 * detonated with, so a full cycle costs one SetCarriedItem per direction and nothing extra — and
 * zero swaps at all when the anchor is already charged and the main hand holds no glowstone.
 *
 * The one thing that outranks that thrift is a TOTEM: if the hotbar holds one, it is what the anchor
 * is detonated with, because the detonating hand is the only hand this module chooses and vanilla
 * reads the MAIN hand first when a killing blow lands ([isTotemDetonationSlot]). That costs at most
 * one extra swap, and nothing at all when the restore slot is already the totem.
 *
 * **Ownership.** With [ownOnly] on (the default) the module only engages anchors WE placed, and it
 * is told so by the client itself: [ClientBlockPlaceEvent] fires from inside `BlockItem.place`, with
 * the cell vanilla has just written ([placementHandler]). Nothing is derived and nothing is guessed
 * — not which of two cells a clicked face resolves to, not whether the hand still holds an anchor
 * after the prediction consumed the last one, not whether a click on an existing anchor was a
 * placement or a detonation. The method ran for our player on our client, so the placement is ours,
 * and its position is the position.
 *
 * That replaced three earlier attempts, each of which lost placements or claimed foreign ones, and
 * none of which should come back: reading the outgoing `ServerboundUseItemOnPacket` and re-deriving
 * the cell from the clicked face (two candidates, one wrong, plus a hand test that cannot see a
 * consumed stack); claiming from the client's prediction inside that packet handler (needs the block
 * to already exist the instant the packet leaves — it did not, on one server); and vouching for an
 * anchor that merely APPEARED with "a respawn anchor is in our hand", which claims every anchor an
 * opponent sets down in reach.
 *
 * The cell still waits in [pendingPlacements] for up to [INTENT_WINDOW_TICKS] instead of being
 * owned outright, for one reason: the event fires on the client's PREDICTION. A server that rejects
 * the placement reverts the block, and [pruneOwnAnchors] would drop the entry a tick later — so
 * ownership is only taken once an anchor is actually standing in that cell, which also survives a
 * placement that is rejected and then re-sent. Those cells are checked DIRECTLY rather than through
 * the anchor scan: routing them through it tied a placement to the scan cube and to the previous
 * tick's cube, and both dropped real placements — a step sideways between two scans moves the cube
 * out from under a cell near the edge of reach, and a lowered [range] shrinks the cube below the
 * distance a placement can legally happen at.
 *
 * A placement driven by a silent hotbar swap, or by one of our own modules, counts the same way —
 * it is the same client-side placement.
 *
 * Eligibility lasts [maxAge] and the entry dies with the block (broken, exploded, or a rejected
 * placement reverting), so a later foreign anchor on the same spot never inherits it; the ledger is
 * dropped on a world change. Ownership gates only the ACQUISITION — once a glowstone has been spent
 * ([committedAnchor]) the cycle always finishes; the commitment survives pauses and ends only when
 * the anchor block is gone.
 *
 * Among several eligible anchors the nearest one wins.
 */
@Suppress("TooManyFunctions", "MagicNumber", "ReturnCount")
object ModuleAnchorOptimizer : ClientModule("AnchorOptimizer", ModuleCategories.COMBAT) {

    /** Vanilla `RespawnAnchorBlock.canBeCharged` stops here. */
    private const val MAX_ANCHOR_CHARGES = 4

    /** Same settle threshold AutoAnchor2 uses for the DuplicateRotPlace gate. */
    private const val ROTATION_SETTLE_THRESHOLD = 1.7f

    /**
     * How long a cell named by one of our placement packets waits for the anchor to actually turn
     * up. Generous on purpose: a rejected placement that the server re-sends a few ticks later must
     * still be recognised as ours.
     */
    private const val INTENT_WINDOW_TICKS = 40

    /** Margin added to the round trip before a detonated cell may be clicked again; see [detonatedAnchors]. */
    private const val DETONATION_GHOST_MARGIN_MS = 100L

    /**
     * [nextActionTick] before anything has been scheduled - "the next action is due now".
     *
     * It must never be subtracted from: `Int.MIN_VALUE - clock` WRAPS for every `clock > 0`, and
     * `coerceAtLeast(0)` does not catch a positive. That is what [ticksUntilAction] did until
     * 2026-09-20, and under `RotationTiming = Snap` it was a self-sustaining clinch - see there.
     */
    private const val NEVER_SCHEDULED = Int.MIN_VALUE

    /** How long after a click of ours [isActing] stays up - the same 250 ms `ModuleAutoAnchor` uses. */
    private const val ACTING_MS = 250L

    // ── Reach ────────────────────────────────────────────────────────────────

    private val range by float("Range", 4.5f, 1.0f..6.0f)

    /** Charge an empty anchor with glowstone. Off: only anchors that already carry a charge. */
    private val recharge by boolean("Recharge", true)

    // ── Ownership ────────────────────────────────────────────────────────────

    /**
     * Only act on an anchor we placed ourselves (see the class KDoc for how a placement is
     * recognised). Off turns the module into a plain "detonate whatever is in reach".
     */
    private val ownOnly by boolean("OwnOnly", true)

    /** How long after placing it an anchor of ours is still engaged. */
    private val maxAge by int("MaxAge", 2000, 250..10000, "ms")

    /**
     * Act even where the dimension says respawn anchors WORK, i.e. set spawn instead of exploding.
     *
     * Normally that is a hard stand-down: clicking the anchor there would move the player's spawn
     * and burn glowstone for nothing. The value comes from dimension data the SERVER sends
     * (`EnvironmentAttributes.RESPAWN_ANCHOR_WORKS`), and a server whose custom dimension type or
     * translation layer disagrees with how it actually behaves takes the whole module offline with
     * no other symptom. Turn this on to test exactly that — if anchors do explode there, this is the
     * gate that was stopping it.
     */
    private val anyDimension by boolean("AnyDimension", false)

    /**
     * Ticks between the charge and the detonation. The floor of 1 is deliberate — two use-item
     * packets inside one tick is a bad packet no vanilla client can produce.
     */
    private val actionDelay by intRange("ActionDelay", 1..1, 1..10, " ticks")

    private val rotations = tree(AnchorRotationsValueGroup)

    // ── Pauses ───────────────────────────────────────────────────────────────

    private object Pause : ToggleableValueGroup(this, "Pause", true) {
        val onMine by boolean("OnMine", true)

        /** Pause below this total health (health + absorption); 0 disables the check. */
        val health by float("Health", 0.0f, 0.0f..36.0f)
    }

    // ── Render / debug ───────────────────────────────────────────────────────

    /**
     * Chat-prints the gate that stopped the sequence, throttled to once a second.
     *
     * There are about ten places this module can bail out of silently, and from the outside they all
     * look identical: nothing happens. Every rejection names its gate here, so "it does not swap"
     * can be told apart from "not our anchor", "paused: KillAura holds a target" or "the raytrace
     * does not land yet".
     */
    private val debug by boolean("Debug", false)

    private var lastDebugMs = 0L

    /** The message is built lazily: with Debug off this costs a boolean check and nothing else. */
    private inline fun debug(message: () -> String) {
        if (!debug) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastDebugMs < 1000L) {
            return
        }
        lastDebugMs = now
        chat("§8[AnchorOptimizer] §7${message()}", this)
    }

    /**
     * Unthrottled [debug] for one-shot events. The throttle exists for per-tick state lines, which
     * would otherwise be a wall of chat; an anchor appearing fires once per anchor, and losing it to
     * a scan summary that happened to win the same second is exactly the thing being diagnosed.
     */
    private fun debugEvent(message: String) {
        if (debug) {
            chat("§8[AnchorOptimizer] §7$message", this)
        }
    }

    private val highlight by boolean("Highlight", true)
    private val fillColor by color("FillColor", Color4b(255, 70, 0, 90))
    private val outlineColor by color("OutlineColor", Color4b(255, 170, 60, 160))

    init {
        tree(Pause)
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var activeAnchor: BlockPos? = null
    private var aimAnchor: BlockPos? = null
    private var aimPoint: Vec3? = null
    private var nextActionTick = NEVER_SCHEDULED

    /**
     * Wall clock of the last block interaction this module actually sent, or `0` when it has sent nothing yet.
     *
     * Published for the mutual stand-down with `ModuleAutoAnchor`: two block interactions in one tick is
     * Grim's `PacketOrderI`, and handler order between two modules is undefined, so a WALL CLOCK is the only
     * reference either of them can agree on — the same reason `ModuleCrystalAura.lastActionMs` exists and the same
     * reason `ModuleSpeedMine` reads that one. Milliseconds, not ticks.
     */
    @Volatile
    var lastActionMs: Long = 0L
        private set

    /**
     * Whether this module is WORKING right now - a click of ours went out less than [ACTING_MS] ago.
     *
     * Read by `ModuleKillAura` on its `canAttack` line ONLY, never on its rotation request. This module already
     * outranks the aura chain by PRIORITY (`IMPORTANT_FOR_PLAYER_LIFE` 40 against KillAura's 30), so the aim needs
     * no abstention - and abstaining there would cost a reset tick every time we stop, which is the mistake
     * `ModuleAutoWeb`'s entry in AGENTS.md warns about: a module that keeps requesting at its own priority has its
     * plan already at the head of the queue when ours lapses.
     *
     * A wall clock and not a tick flag, because handler order between modules is undefined.
     */
    val isActing: Boolean
        get() = running && System.currentTimeMillis() - lastActionMs < ACTING_MS

    /**
     * Whether this module is steering the view towards an anchor right now, i.e. it has a target it is aiming at.
     *
     * This module spends several ticks converging on the anchor before it clicks
     * (`AnchorRotationsValueGroup.snapTicks`), and those ticks carry no packet, so [lastActionMs] alone would let
     * another module act in the middle of the approach and steal the aim.
     *
     * Nothing reads this any more, and that is deliberate rather than an oversight: what it protected against is
     * now structural. This module requests at `IMPORTANT_FOR_PLAYER_LIFE` (40, see the rotation request below),
     * strictly above `ModuleAutoAnchor`'s `IMPORTANT_FOR_USAGE_2` (30), so `RequestHandler` alone keeps the
     * approach from being stolen. That module used to read this flag and stood
     * down for as long as it was true, which - since it is true for as long as an anchor is in reach - was a
     * permanent stand-down rather than an arbitration. The property is kept because it states this module's aim
     * state exactly, and a future reader may want it; do not turn it back into a gate.
     */
    val isRotating: Boolean
        get() = running && aimAnchor != null

    /** Slot held before the charge swap; doubles as the preferred detonation slot. */
    private var slotBeforeCharge = -1

    /** Owed hotbar restore, flushed on a later tick (see [flushRestoreIfOwed]). */
    private var pendingSwapBack: Int? = null

    /** Tick of the last emitted SetCarriedItem — vanilla resolves the hotbar once per tick. */
    private var lastSwapTick = Int.MIN_VALUE

    /**
     * Our own tick clock, because `player.tickCount` is NOT one.
     *
     * That field is the age of the player ENTITY, and the entity is rebuilt on respawn, on a
     * dimension change and on reconnect — at which point it restarts near zero while any tick number
     * we stored is still in the thousands. Everything scheduled against it then sits in a future
     * that has already passed: the module reports "waiting 1199 ticks" and never acts again.
     *
     * This counter only ever moves forward for as long as the module is enabled, so a schedule made
     * against it stays meaningful across all three.
     */
    @Volatile
    private var clock = 0

    private val rotationTracker = RotationConvergenceTracker()

    /**
     * Anchors WE placed: [BlockPos.asLong] → the time the block was first seen standing there.
     * Filled by [claimPendingPlacements], pruned by [pruneOwnAnchors] once the block is gone.
     */
    private val ownAnchors = HashMap<Long, Long>()

    /**
     * Cell -> wall clock of the detonating click WE sent for it, until the block actually disappears.
     *
     * This is what keeps Grim's `AirLiquidPlace` quiet. That check reads the clicked block out of its own
     * lag-compensated world and flags when it is air, it runs on EVERY `PLAYER_BLOCK_PLACEMENT` — a charge and a
     * detonate click included, not just placements — and its cancel VL is 0, so the first violation already
     * resyncs. Meanwhile the client predicts NO part of a detonation: `RespawnAnchorBlock.useWithoutItem` returns
     * `CONSUME` client-side and `ClientLevel.explode` has an empty body, so the anchor stays drawn for about a
     * round trip after the server has already removed it. Without this map the very next tick finds that ghost
     * anchor, clicks it again, and the click lands on air.
     *
     * Cleared the moment the block is gone (the honest signal), or after [detonationWindowMs] as a backstop.
     */
    private val detonatedAnchors = HashMap<Long, Long>()

    private var ledgerWorld: ClientLevel? = null

    /** A cell we placed an anchor into, waiting for the block to actually be standing in it. */
    private class PendingPlace(val tick: Int)

    /**
     * Cells our own client placed an anchor into: [BlockPos.asLong] → the tick it happened on.
     *
     * Concurrent because [placementHandler] runs wherever `BlockItem.place` was called from while
     * the tick handler reads and prunes it.
     */
    private val pendingPlacements = ConcurrentHashMap<Long, PendingPlace>()

    /** Why the last scan found nothing, for [debug]; only filled while Debug is on. */
    private val lastRejections = ArrayList<String>()
    private var lastInRange = 0

    /**
     * Anchor we already spent a glowstone on. Ownership gates ACQUIRING a target, never finishing
     * one — otherwise a short [maxAge] could leave a charged anchor sitting there undetonated.
     */
    private var committedAnchor: BlockPos? = null

    override fun onEnabled() {
        rotationTracker.reset()
        resetSequence()
        committedAnchor = null
        clock = 0
        nextActionTick = NEVER_SCHEDULED
        lastSwapTick = Int.MIN_VALUE
        // Only placements made from now on are ours: with no previous scan recorded, the first scan
        // establishes the baseline and nothing standing around at enable time can look placed.
        clearLedger()
        super.onEnabled()
    }

    override fun onDisabled() {
        // Never leave the server holding the glowstone slot we swapped in. This is a one-off on
        // toggle with no interaction of ours behind it, so the per-tick swap cap does not apply.
        if (mc.player != null) {
            val owed = pendingSwapBack ?: slotBeforeCharge.takeIf { it in 0..8 }
            if (owed != null && player.inventory.selectedSlot != owed) {
                player.inventory.selectedSlot = owed
                interaction.ensureHasSentCarriedItem()
            }
        }
        pendingSwapBack = null
        slotBeforeCharge = -1
        clearLedger()
        resetSequence()
        committedAnchor = null
        super.onDisabled()
    }

    // ── Ownership ledger ─────────────────────────────────────────────────────

    /**
     * Our own anchor placement, straight from `BlockItem.place` via [ClientBlockPlaceEvent].
     *
     * There is nothing to infer here: vanilla has already decided the cell and written the block,
     * the event only fires for the local player's own client-side placement, and no other player's
     * actions can reach it. Everything the earlier versions needed — which of two candidate cells
     * the block went into, whether the hand still held an anchor after the prediction consumed it,
     * whether a click on an existing anchor was a placement or a detonation — is answered by the
     * fact that this method ran at all.
     *
     * The cell still goes through [pendingPlacements] rather than straight into [ownAnchors],
     * because this fires on the client's PREDICTION: a server that rejects the placement reverts the
     * block, and [pruneOwnAnchors] would drop the entry a tick later. Waiting for the anchor to be
     * standing there survives a rejected-then-resent placement.
     */
    @Suppress("unused")
    private val placementHandler = handler<ClientBlockPlaceEvent> { event ->
        if (event.block !== Blocks.RESPAWN_ANCHOR) {
            return@handler
        }

        val key = event.blockPos.asLong()
        if (key in ownAnchors) {
            return@handler
        }

        pendingPlacements[key] = PendingPlace(clock)
        debugEvent("we placed an anchor at ${event.blockPos.toShortString()}")
    }

    /**
     * A dimension change reuses coordinates: a stamp carried across worlds would let an anchor that
     * was always standing in the OTHER world pass as freshly placed.
     */
    private fun resetLedgerIfWorldChanged() {
        if (ledgerWorld !== world) {
            clearLedger()
            ledgerWorld = world
        }
    }

    private fun clearLedger() {
        ownAnchors.clear()
        detonatedAnchors.clear()
        pendingPlacements.clear()
        ledgerWorld = null
        // A new world is a new tick window; the auras reading this clock would otherwise keep yielding to a burst
        // that happened before the disconnect.
        lastActionMs = 0L
    }

    /** Drops every owned position whose block is no longer an anchor (exploded, broken, reverted). */
    private fun pruneOwnAnchors() {
        ownAnchors.keys.removeIf { world.getBlockState(BlockPos.of(it)).block !== Blocks.RESPAWN_ANCHOR }
        // A vanished block is the honest end of the ghost window; the clock is only the backstop.
        detonatedAnchors.keys.removeIf { world.getBlockState(BlockPos.of(it)).block !== Blocks.RESPAWN_ANCHOR }
    }

    /**
     * Turns the cells we placed into owned anchors, for as long as [INTENT_WINDOW_TICKS] allows.
     *
     * Every pending cell is looked at directly rather than through the anchor scan. Going through
     * the scan tied a placement to the scan cube and to the previous tick's cube, and both dropped
     * real placements: a step sideways between two scans moves the cube out from under a cell near
     * the edge of reach, and a lowered [range] shrinks the cube below the distance a placement can
     * legally happen at. There is nothing to lose either — the two tests existed to separate a
     * placement from walking up to an old anchor, and [placementHandler] already does that.
     *
     * A claimed cell keeps its entry until the window runs out: a placement the server first rejects
     * and then confirms goes empty and full again, and the second appearance is the same placement.
     */
    private fun claimPendingPlacements(now: Long) {
        if (pendingPlacements.isEmpty()) {
            return
        }

        val claimed = ArrayList<Long>(2)
        val retired = ArrayList<Long>(2)

        for ((key, pending) in pendingPlacements) {
            if (clock - pending.tick > INTENT_WINDOW_TICKS) {
                retired.add(key)
                continue
            }
            if (key in ownAnchors) {
                continue
            }
            if (world.getBlockState(BlockPos.of(key)).block !== Blocks.RESPAWN_ANCHOR) {
                continue
            }

            claimed.add(key)
        }

        retired.forEach(pendingPlacements::remove)
        for (key in claimed) {
            ownAnchors[key] = now
            debugEvent("own anchor confirmed at ${BlockPos.of(key).toShortString()}")
        }
    }

    // ── Tick: aim submission (runs before RotationManager.update) ────────────

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> { _ ->
        clock++

        // Must sample at tick HEAD, before this tick's rotation is applied and sent.
        rotationTracker.update()

        // A committed anchor whose block is gone (detonated by us or anyone else, or broken) is
        // finished business — without this the commitment could dangle forever and exempt a
        // future, unrelated anchor on the same spot from the ownership gate.
        committedAnchor?.let { pos ->
            if (world.getBlockState(pos).block !== Blocks.RESPAWN_ANCHOR) {
                committedAnchor = null
            }
        }

        if (mc.player == null || mc.level == null) {
            return@handler
        }

        val pause = pauseReason()
        val runnable = pause == null
        // The scan runs on paused ticks too: every scan is the baseline the next one diffs against,
        // so a gap in the sequence is a placement the ownership ledger can no longer see.
        val anchor = findBestAnchor(score = runnable)
        if (anchor == null) {
            debug { if (pause != null) "paused: $pause" else scanSummary() }
            releaseSequence()
            flushRestoreIfOwed()
            return@handler
        }

        // GrimSnap needs a runway to walk the aim in over; a freshly spotted anchor has none,
        // so the first interaction on it is armed SnapTime into the future.
        if (anchor != activeAnchor && rotations.rotationTiming == GRIM_SNAP) {
            nextActionTick = maxOf(nextActionTick, clock + rotations.snapTicks)
        }
        activeAnchor = anchor

        val point = resolveAimPoint(anchor)
        if (shouldSubmitAim()) {
            RotationManager.setRotationTarget(
                Rotation.lookingAt(point = point, from = player.eyePosition),
                valueGroup = rotations,
                priority = Priority.IMPORTANT_FOR_PLAYER_LIFE,
                provider = this@ModuleAnchorOptimizer
            )
        }

        if (clock < nextActionTick) {
            debug { "waiting ${nextActionTick - clock}t before the next action" }
            flushRestoreIfOwed()
            return@handler
        }

        runAction(anchor)
    }

    /**
     * Ticks until the schedule allows the next interaction; 0 once it is due. Read by Snap and GrimSnap.
     *
     * The [NEVER_SCHEDULED] branch is load-bearing, not defensive. Without it the subtraction wrapped:
     * `Int.MIN_VALUE - clock` is POSITIVE for every `clock > 0`, and `coerceAtLeast(0)` only clamps below, so
     * from the second tick after every enable this answered `Int.MAX_VALUE`. Under `RotationTiming = Snap`
     * that made [shouldSubmitAim] (`ticksUntilAction() <= 1`) permanently false, so the aim was never filed;
     * the raytrace gate in `interactWithAnchor` never opened; and [nextActionTick] is written ONLY there - so
     * nothing could ever give it a real value again. The module sat silent for the whole session unless the
     * player happened to look at the anchor by hand. GrimSnap escaped it by accident, because it writes a real
     * value on a new anchor before reading this.
     *
     * Zero is the right answer for the sentinel, and it agrees with the scheduler: `clock < nextActionTick` is
     * false at `Int.MIN_VALUE`, i.e. the action is already due.
     */
    internal fun ticksUntilAction(): Int =
        if (nextActionTick == NEVER_SCHEDULED) 0 else (nextActionTick - clock).coerceAtLeast(0)

    /**
     * Whether the silent aim is fed to [RotationManager] this tick.
     *
     * `Normal` always holds it. `Snap` engages only once the interaction is due (costing a tick,
     * because the aim still has to reach the wire before the raytrace gate opens). `GrimSnap`
     * engages [AnchorRotationsValueGroup.snapTicks] ahead so its stepping lands on the action tick.
     * `OnTick` never engages — it spoofs the rotation around the interaction instead.
     */
    private fun shouldSubmitAim(): Boolean = when (rotations.rotationTiming) {
        NORMAL -> true
        SNAP -> ticksUntilAction() <= 1
        GRIM_SNAP -> ticksUntilAction() <= rotations.snapTicks
        ON_TICK -> false
    }

    /**
     * `OnTick` only: sends a rotation packet carrying the anchor aim, so the interaction that
     * follows is validated against it. [RotationManager] tracks outgoing move packets, so
     * `serverRotation` — and therefore the raytrace gate — picks this up immediately.
     *
     * @return true when a packet actually went out and [restoreRotationOnWire] is owed.
     */
    private fun spoofAimOnWire(anchor: BlockPos): Boolean {
        val aim = Rotation.lookingAt(point = resolveAimPoint(anchor), from = player.eyePosition).normalize()
        network.send(
            ServerboundMovePlayerPacket.PosRot(
                player.x, player.y, player.z,
                aim.yaw, aim.pitch,
                player.onGround(), player.horizontalCollision
            )
        )
        return true
    }

    /** Puts the player's real rotation back on the wire after an `OnTick` interaction. */
    private fun restoreRotationOnWire() {
        network.send(
            ServerboundMovePlayerPacket.PosRot(
                player.x, player.y, player.z,
                player.yRot, player.xRot,
                player.onGround(), player.horizontalCollision
            )
        )
    }

    /**
     * Everything this module sends leaves BEFORE the tick's movement packet, at the head of the
     * client tick.
     *
     * It used to go through [PostRotationExecutor] instead, which was wrong twice over. That
     * executor keeps a SINGLE global priority slot, and `NormalRotationMode` and the block placers
     * write to the same one — whoever writes last in a tick wins, and the loser's action is dropped
     * without a trace. An anchor sequence sharing a tick with any of them simply did not happen,
     * which is what "it does not always swap" was.
     *
     * The second reason is subtler and matters even alone. The gate in [readyHit] tests
     * `RotationManager.serverRotation`, which is the rotation the server had at the END of the
     * PREVIOUS tick. Acting post-move puts this tick's flying — carrying the newly submitted aim —
     * on the wire first, so the server would validate the interaction against a rotation we never
     * checked. Sending ahead of the movement packet makes the two the same rotation by construction.
     *
     * Window shape is unaffected: at most one thing happens per tick, at the head of it, so a window
     * reads `[swap]`, `[swap, use]` or `[use]` and never `[use, swap]` (Grim PacketOrderE).
     */
    private fun flushRestoreIfOwed() {
        // Never yank the hotbar mid item-use: a slot change cancels the player's own eat/drink.
        // The restore just stays owed until the use ends.
        if (player.isUsingItem) return
        pendingSwapBack?.let(::swapTo)
    }

    private fun runAction(anchor: BlockPos) {
        if (!canRun() || activeAnchor != anchor) {
            return
        }

        // A hand swap stands the whole step down for its window ([HandSwapGate]). Read HERE, at the head, and not
        // down at the click: `swapTo` runs first, and a tick that swaps and then withholds the interaction is the
        // stray held-item change this module goes out of its way never to send.
        if (HandSwapGate.holdsUses) {
            debug { "hand swap in flight" }
            return
        }

        val state = world.getBlockState(anchor)
        if (state.block !== Blocks.RESPAWN_ANCHOR || blockedByOffhandFuel(state)) {
            releaseSequence()
            return
        }
        // Vanilla's own server-side leniency check (reach + 1.0). The Grim FarPlace clamp is the
        // scan's `blockInteractionRange - 0.05`, applied in [rejectReason] on the same tick.
        if (!player.isWithinBlockInteractionRange(anchor, 1.0)) {
            return
        }

        // Grim AirLiquidPlace: an anchor our client placed is in Grim's world only once the server has answered the
        // placement - Grim skips writing it when an entity is near, or when a check cancelled it - and a click
        // before then reads as a click on air ([PendingPlacements]). Held here, ahead of the swap and the OnTick
        // spoof, so the tick sends nothing at all; the aim and the sequence stay as they are.
        if (PendingPlacements.isAwaitingServer(anchor)) {
            debug { "the server has not answered our placement of this anchor yet" }
            return
        }

        // Grim DuplicateRotPlace: never interact when the last look packet's yaw delta repeats the
        // delta the previous interaction went out with. Checked BEFORE the OnTick spoof — the old
        // order could leave a spoofed flying on the wire with no interaction and no restore behind
        // it (the gate samples tick-head deltas, so it never depends on the spoof).
        if (!rotationTracker.isDuplicateRotPlaceSafe(ROTATION_SETTLE_THRESHOLD)) {
            debug { "waiting for the rotation to settle (DuplicateRotPlace gate)" }
            return
        }

        // OnTick: put the aim on the wire right now, so the gate below sees it. This is the extra
        // flying vanilla never sends — the mode's documented cost.
        val spoofed = rotations.rotationTiming == ON_TICK && spoofAimOnWire(anchor)

        // Grim RotationPlace / PositionPlace: the ray of the rotation the SERVER holds has to land
        // on the anchor. A half-converged aim or a wall in the way just skips this tick.
        val hitResult = readyHit(anchor) ?: run {
            debug { "raytrace: server rotation does not hit the anchor yet" }
            if (spoofed) restoreRotationOnWire()
            return
        }

        val charge = state.getValue(RespawnAnchorBlock.CHARGE)
        if (charge <= 0) {
            chargeAnchor(hitResult)
        } else {
            detonateAnchor(hitResult)
        }

        if (spoofed) {
            restoreRotationOnWire()
        }
    }

    /**
     * Charge step. Exactly one glowstone is ever spent: the explosion power does not scale with the
     * charge, so charging past 1 only burns items and ticks.
     */
    private fun chargeAnchor(hitResult: BlockHitResult) {
        if (!recharge) {
            releaseSequence()
            return
        }

        val glowstoneSlot = Slots.Hotbar.findSlot(Items.GLOWSTONE)?.hotbarIndex ?: run {
            debug { "no glowstone in the hotbar" }
            releaseSequence()
            return
        }

        if (slotBeforeCharge == -1) {
            // An owed restore from an aborted cycle IS the player's real slot — the current
            // selection would be whatever that cycle left in hand (usually the glowstone slot).
            slotBeforeCharge = pendingSwapBack ?: player.inventory.selectedSlot
        }
        if (!swapTo(glowstoneSlot) || player.mainHandItem.item !== Items.GLOWSTONE) {
            debug { "swap to glowstone slot $glowstoneSlot blocked (one swap per tick)" }
            return
        }

        if (interactWithAnchor(hitResult)) {
            // Glowstone spent — this anchor is now ours to finish regardless of its age.
            committedAnchor = hitResult.blockPos
        }
    }

    /** Detonation step: click the charged anchor with anything that is not respawn fuel. */
    private fun detonateAnchor(hitResult: BlockHitResult) {
        val slot = findDetonationSlot() ?: run {
            debug { "no non-fuel detonation slot in the hotbar" }
            releaseSequence()
            return
        }

        // Guard against a stale inventory read: glowstone in hand would recharge, not detonate.
        if (!swapTo(slot) || isRespawnFuel(player.mainHandItem)) {
            debug { "swap to detonation slot $slot blocked" }
            return
        }

        if (interactWithAnchor(hitResult)) {
            // Usually the detonation swap IS the restore. It is not when the pre-charge slot held
            // glowstone or a block item, so owe the restore for a later tick's head-of-tick flush.
            val owed = slotBeforeCharge
            if (owed in 0..8 && owed != slot) {
                pendingSwapBack = owed
            }
            slotBeforeCharge = -1
            // The anchor is a ghost from here until the server's removal lands; see [detonatedAnchors].
            detonatedAnchors[hitResult.blockPos.asLong()] = System.currentTimeMillis()
            // The detonation click ends the commitment (the block-gone validation would catch it
            // next tick anyway; this just keeps the state exact).
            committedAnchor = null
            resetSequence()
        }
    }

    /**
     * Vanilla interaction path: `useItemOn` (which announces the carried item and wraps the packet
     * in `startPrediction`, so the sequence id is always vanilla-correct) and a swing only where
     * vanilla itself swings — an unconditional animation packet would be a bad packet.
     */
    private fun interactWithAnchor(hitResult: BlockHitResult): Boolean {
        // `useItemOn` puts the packet on the wire inside `startPrediction` whatever the outcome, so
        // the tick budget is spent even when the interaction turns out to be a no-op. Pacing on the
        // ATTEMPT rather than on the success is what keeps a rejected interaction from becoming a
        // once-per-tick packet loop.
        nextActionTick = clock + actionDelay.random().coerceAtLeast(1)
        // Stamped on the ATTEMPT for the same reason the pacing is: `useItemOn` puts the packet on the wire inside
        // `startPrediction` whatever the interaction returns, so the tick's one block interaction is spent here.
        lastActionMs = System.currentTimeMillis()

        val result = interaction.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
        if (!result.consumesAction()) {
            return false
        }

        if (result is InteractionResult.Success && result.swingSource == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND)
        }

        rotationTracker.markInteraction()
        return true
    }

    // ── Anchor search ────────────────────────────────────────────────────────

    /**
     * Nearest eligible anchor in reach, or null. The scan itself is only block-state lookups; the
     * aim-point raycasts run solely for positions that actually hold an anchor and pass the cheap
     * gates.
     *
     * It also carries the ownership ledger, which is why it runs on paused ticks too: this scan is
     * the previous scan the next one compares against, and a gap in the sequence is a placement the
     * module can no longer recognise.
     */
    private fun findBestAnchor(score: Boolean): BlockPos? {
        resetLedgerIfWorldChanged()
        pruneOwnAnchors()

        val now = System.currentTimeMillis()
        // Ownership is settled before the search, and independently of it: a cell we clicked has to
        // be recognised whether or not this tick's scan cube happens to cover it.
        claimPendingPlacements(now)

        val eyePos = player.eyePosition
        val maxDist = min(range.toDouble(), player.blockInteractionRange() - 0.05)
        val maxDistSq = maxDist * maxDist
        val r = ceil(maxDist).toInt()
        val center = BlockPos.containing(eyePos)

        val canCharge = recharge && Slots.Hotbar.findSlot(Items.GLOWSTONE) != null
        lastRejections.clear()
        lastInRange = 0

        var best: BlockPos? = null
        var bestDistanceSq = Double.MAX_VALUE

        // betweenClosed reuses one MutableBlockPos — every escaping use needs immutable().
        for (pos in BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            val state = world.getBlockState(pos)
            if (state.block !== Blocks.RESPAWN_ANCHOR) continue

            val distanceSq = eligibleDistanceSq(pos, state, eyePos, maxDistSq, score, canCharge, now)
            if (distanceSq != null && distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq
                best = pos.immutable()
            }
        }

        return best
    }

    /**
     * Squared eye distance of the anchor at [pos] when it is in range and passes every gate, else
     * null. Counts in-range anchors and, with Debug on, records why one was rejected.
     */
    @Suppress("LongParameterList")
    private fun eligibleDistanceSq(
        pos: BlockPos,
        state: BlockState,
        eyePos: Vec3,
        maxDistSq: Double,
        score: Boolean,
        canCharge: Boolean,
        now: Long
    ): Double? {
        val distanceSq = AABB(pos).distanceToSqr(eyePos)
        if (distanceSq > maxDistSq) return null
        lastInRange++
        if (!score) return null

        val reason = rejectReason(pos, state, canCharge, now) ?: return distanceSq
        if (debug) lastRejections.add("${pos.toShortString()}: $reason")
        return null
    }

    /** Debug line for a scan that found nothing usable. */
    private fun scanSummary(): String {
        if (lastInRange == 0) return "no anchor in range"
        if (lastRejections.isEmpty()) return "$lastInRange anchor(s) in range, all rejected"
        return "$lastInRange anchor(s) in range, rejected: ${lastRejections.joinToString("; ")}"
    }

    /** Null when the in-range anchor at [pos] is usable; otherwise the gate that rejects it. */
    private fun rejectReason(pos: BlockPos, state: BlockState, canCharge: Boolean, now: Long): String? {
        detonatedAnchors[pos.asLong()]?.let { clicked ->
            if (now - clicked < detonationWindowMs()) return "we already detonated it; the server has not caught up"
        }

        if (blockedByOffhandFuel(state)) return "respawn fuel in the offhand blocks the detonation click"

        if (ownOnly && pos != committedAnchor) {
            val placedAt = ownAnchors[pos.asLong()]
                ?: return "was not seen being placed by us (OwnOnly)"
            val age = now - placedAt
            if (age > maxAge) return "ours, but placed $age ms ago (MaxAge $maxAge)"
        }

        // An empty anchor is only worth it when we can actually charge it.
        if (state.getValue(RespawnAnchorBlock.CHARGE) <= 0 && !canCharge) {
            return if (recharge) "uncharged and no glowstone in the hotbar" else "uncharged and Recharge is off"
        }

        // An anchor no ray from the eye can land on cannot be clicked; letting it win the ranking
        // would park the aim on it while the raytrace gate skips forever, ignoring usable anchors.
        if (AnchorAimSearch.findClearAimPointOrNull(pos, 1.0f) == null) {
            return "no face of it raycasts cleanly from the eye"
        }

        return null
    }

    /**
     * Vanilla's offhand branch in `RespawnAnchorBlock.useItemOn`:
     *
     * ```
     * if (hand == MAIN_HAND && isRespawnFuel(player.getItemInHand(OFF_HAND)) && canBeCharged(state))
     *     return InteractionResult.PASS;
     * ```
     *
     * With respawn fuel in the OFFHAND and an anchor that can still take a charge, a main-hand click
     * returns PASS instead of TRY_WITH_EMPTY_HAND — so `useWithoutItem` never runs and the anchor
     * simply cannot be detonated (vanilla reserves the click for the offhand charge). It only becomes
     * detonatable at charge 4, and walking it there costs three extra glowstone for zero extra
     * damage, so such anchors are left alone entirely.
     */
    private fun blockedByOffhandFuel(state: BlockState): Boolean =
        isRespawnFuel(player.offhandItem) &&
            state.getValue(RespawnAnchorBlock.CHARGE) < MAX_ANCHOR_CHARGES

    /**
     * How long a detonated cell stays refused: one round trip plus a margin for the server's tick boundary and
     * for the jitter in `player.ping`. The block-gone sweep normally ends the window long before this.
     */
    private fun detonationWindowMs(): Long = player.ping.toLong() + DETONATION_GHOST_MARGIN_MS

    // ── Aim / raytrace ───────────────────────────────────────────────────────

    /**
     * Cached aim point on the anchor, re-picked whenever it stops raycasting cleanly (a block
     * placed since we picked it can occlude the chosen corner).
     *
     * Sampled at partial tick 1.0 — the exact, non-interpolated eye position, which is the one the
     * movement packet carries and therefore the one the server raytraces from.
     */
    private fun resolveAimPoint(anchor: BlockPos): Vec3 {
        val cached = aimPoint

        if (cached != null && aimAnchor == anchor &&
            AnchorAimSearch.canHitAnchor(player.eyePosition, cached, anchor)
        ) {
            return cached
        }

        return AnchorAimSearch.findClearAimPoint(anchor, 1.0f).also {
            aimAnchor = anchor
            aimPoint = it
        }
    }

    /**
     * The raytrace gate. Casts from the eye along the rotation the SERVER currently holds — not the
     * one we requested — so it answers the exact question Grim asks when it validates the
     * interaction. Both ends are the real tick-final values, not render-interpolated ones.
     */
    private fun readyHit(anchor: BlockPos): BlockHitResult? {
        val eyePos = player.eyePosition
        val end = eyePos.add(RotationManager.serverRotation.directionVector.scale(player.blockInteractionRange()))

        val raycast = world.clip(
            ClipContext(eyePos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        )
        if (raycast.type != HitResult.Type.BLOCK) return null
        if (raycast.blockPos != anchor) return null
        return raycast
    }

    // ── Hotbar ───────────────────────────────────────────────────────────────

    /**
     * Slot to detonate with. Preference order:
     * 1. the slot held before the charge — the restore then IS the detonation swap, costing nothing
     * 2. any non-fuel, non-block slot — a block item could be placed onto the anchor if the server
     *    disagrees with our predicted charge (vanilla falls through to `useOn` when the charge is 0)
     * 3. any non-fuel slot
     */
    private fun findDetonationSlot(): Int? {
        val preferred = slotBeforeCharge
        // A totem outranks even the free restore slot. The detonating hand is the one hand this module gets to
        // choose, and `LivingEntity.checkTotemDeathProtection` walks `InteractionHand.values()` MAIN_HAND FIRST
        // and keys on the DEATH_PROTECTION component, so a totem held for the click pops if our own blast kills
        // us — `bad_respawn_point` is in no `bypasses_*` tag, `#bypasses_invulnerability` included, so the check
        // is reached. Worst case it costs one extra SetCarriedItem; when the restore slot already holds a totem
        // it costs nothing, which is why that slot is tested first.
        if (preferred in 0..8 && isTotemDetonationSlot(preferred)) {
            return preferred
        }

        for (i in 0..<9) {
            if (isTotemDetonationSlot(i)) return i
        }

        if (preferred in 0..8 && isDetonationSafe(player.inventory.getItem(preferred))) {
            return preferred
        }

        val current = player.inventory.selectedSlot
        if (isDetonationSafe(player.inventory.getItem(current))) {
            return current
        }

        for (i in 0..<9) {
            if (isDetonationSafe(player.inventory.getItem(i))) return i
        }
        for (i in 0..<9) {
            if (!isRespawnFuel(player.inventory.getItem(i))) return i
        }
        return null
    }

    private fun isDetonationSafe(stack: ItemStack): Boolean =
        !isRespawnFuel(stack) && stack.item !is BlockItem

    /**
     * A hotbar slot holding an item that both detonates safely and would save us from our own blast.
     *
     * The component, not the item id: a data pack or a server may hang `DEATH_PROTECTION` on anything, and that
     * is exactly what vanilla tests (`LivingEntity.checkTotemDeathProtection`). [isDetonationSafe] is still
     * applied on top, so a death-protecting item that would consume the click is passed over.
     */
    private fun isTotemDetonationSlot(slot: Int): Boolean {
        val stack = player.inventory.getItem(slot)
        return stack.has(DataComponents.DEATH_PROTECTION) && isDetonationSafe(stack)
    }

    /** Vanilla `RespawnAnchorBlock.isRespawnFuel`. */
    private fun isRespawnFuel(stack: ItemStack): Boolean = stack.`is`(Items.GLOWSTONE)

    /**
     * Real hotbar swap announced through the vanilla tracker, which only emits a SetCarriedItem when
     * it differs from what the server was last told (Grim BadPacketsA).
     *
     * Capped at one swap per tick: vanilla resolves the hotbar selection once per tick in
     * `handleKeybinds`, so two SetCarriedItem packets inside one tick is a pattern no vanilla client
     * produces. Any owed restore is dropped here — a swap that happens anyway supersedes it.
     *
     * @return true when the hotbar ends up on [slot].
     */
    private fun swapTo(slot: Int): Boolean {
        if (slot !in 0..8) return false
        if (player.inventory.selectedSlot == slot) {
            pendingSwapBack = null
            return true
        }
        if (clock == lastSwapTick) return false

        lastSwapTick = clock
        player.inventory.selectedSlot = slot
        interaction.ensureHasSentCarriedItem()
        pendingSwapBack = null
        return true
    }

    // ── Gates ────────────────────────────────────────────────────────────────

    private fun canRun(): Boolean = pauseReason() == null

    /** Null when the module may act this tick; otherwise the gate holding it, for [debug]. */
    private fun pauseReason(): String? {
        if (mc.player == null || mc.level == null) return "no world"
        // No vanilla client interacts with a screen open.
        if (mc.screen != null) return "a screen is open"
        // No vanilla client clicks a block mid item-use either, and Grim MultiActionsE flags the
        // swing outright while an item is in use (an offhand use bypasses even its slot
        // comparison). A hard gate, not a Pause option — packet-pattern rule, not a preference.
        if (player.isUsingItem) return "an item is in use"
        // `ServerPlayerGameMode.useItemOn` skips the block interaction entirely while sneaking with
        // a non-empty hand and falls through to `stack.useOn` — neither the charge nor the
        // detonation would land, so acting here would only put dead packets on the wire.
        if (player.isSecondaryUseActive() &&
            !(player.mainHandItem.isEmpty && player.offhandItem.isEmpty)
        ) {
            return "sneaking with an item in hand (the server skips the block interaction)"
        }
        // Grim MultiActionsG: actions from a vehicle.
        if (player.vehicle != null) return "in a vehicle"
        // NO KillAura clause here any more (2026-09-20, the user's decision). It used to be
        // `ModuleKillAura.running && ModuleKillAura.targetTracker.target != null`, and that is a permanent
        // stand-down, not an arbitration: `targetTracker.target` is taken from `range.scanRange` whenever the
        // closest enemy is beyond interaction range (`ModuleKillAura.kt:540-543`) and is kept for as long as it
        // "still qualifies", so with KillAura enabled and anybody in scan range this module did nothing and asked
        // for no aim for the WHOLE engagement - despite being the one feature here documented as outranking the
        // chain. It was the exact shape the comment below condemns, one clause above it.
        // The yield now runs the other way: KillAura withholds its ATTACK while [isActing], and only the attack -
        // its rotation keeps being filed at 30, which we outrank at 40.
        // The other half of the mutual stand-down with AutoAnchor: both modules want the tick's single
        // `UseItemOn`, and `PacketOrderI` flags the second one.
        //
        // The test is `isActing` - WORKING - and not `isRotating`. This is the same correction the aura chain
        // took on 2026-09-11 and it is what the KDoc on this module's OWN `isRotating` already demanded: a flag
        // that is true for as long as a module merely has something in reach is a permanent stand-down, not an
        // arbitration. Here it was worse than permanent-in-practice: AutoAnchor's `isRotating` compared against
        // an `Int.MIN_VALUE` sentinel and overflowed, so it read TRUE from the moment that module was enabled
        // until it first asked for a rotation - and a module standing down never asks. This gate therefore held
        // for whole sessions. The overflow was fixed on 2026-09-20; reading the right flag is the other half.
        //
        // `isActing` subsumes the 200 ms wall-clock window this used to OR in - it is already "a packet within
        // the last ~5 ticks, or a cycle still in flight", which is strictly wider - and it carries the `running`
        // test itself, so the constant that held that window is gone with it.
        if (ModuleAutoAnchor.isActing) {
            return "AutoAnchor owns the tick"
        }
        // Another module is enforcing a silent hotbar slot: MixinMultiPlayerGameMode makes every
        // carried-item announcement report THAT slot, so our real-slot swap would never reach the
        // wire and the server would resolve the click with an item we never chose. Stand down.
        if (SilentHotbar.isSlotModified()) return "another module holds a silent hotbar slot"
        // Where the anchor works it sets spawn instead of exploding — nothing to optimize. The value
        // is server-supplied dimension data, so [anyDimension] exists to overrule it.
        if (!anyDimension && world.respawnAnchorWorks) {
            return "the dimension says respawn anchors set spawn instead of exploding (AnyDimension is off)"
        }

        return pauseGroupReason()
    }

    /** The user-configurable pauses ([Pause]); null when none applies. */
    private fun pauseGroupReason(): String? {
        if (!Pause.enabled) return null
        if (Pause.onMine && interaction.isDestroying) return "Pause.OnMine: a block is being mined"
        if (Pause.health > 0.0f && player.health + player.absorptionAmount < Pause.health) return "Pause.Health"
        return null
    }

    // ── Sequence bookkeeping ─────────────────────────────────────────────────

    /**
     * Drops the acquisition state. [committedAnchor] deliberately survives: the commitment to
     * finish a charged anchor must outlive transient pauses (a screen, a sneak, KillAura engaging)
     * or a short [maxAge] strands charged anchors. It ends only with the detonation click, with
     * the anchor block disappearing, or on toggle.
     */
    private fun resetSequence() {
        activeAnchor = null
        aimAnchor = null
        aimPoint = null
    }

    /**
     * Drops the sequence and owes the pre-charge slot back. The restore is deferred to the start of
     * the next tick so it can never share a tick-window with the interaction that preceded it.
     */
    private fun releaseSequence() {
        resetSequence()
        val owed = slotBeforeCharge
        if (owed in 0..8 && player.inventory.selectedSlot != owed) {
            pendingSwapBack = owed
        }
        slotBeforeCharge = -1
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!highlight) return@handler
        val pos = activeAnchor ?: return@handler

        val fill = fillColor.takeIf { it.a > 0 }
        val outline = outlineColor.takeIf { it.a > 0 }
        if (fill == null && outline == null) return@handler

        renderEnvironmentForWorld(event.matrixStack) {
            withPositionRelativeToCamera(Vec3.atLowerCornerOf(pos)) {
                drawBox(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), fill, outline)
            }
        }
    }

}
