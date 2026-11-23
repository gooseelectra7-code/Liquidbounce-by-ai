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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor

import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAnchorOptimizer
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoTotem
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.ModuleMainHandTotem
import net.ccbluex.liquidbounce.features.module.modules.world.ModuleSurround
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.exec.AnchorExecutor
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.net.PacketIntake
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.AnchorAmmo
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.AnchorSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.DETONATE_ONLY
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.AnchorJob
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.AnchorMove
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.AnchorPlan
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.hotbarSlotFor
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.state.AnchorLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.AnchorView
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.chargeOf
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.FloatValueProvider
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.world.respawnAnchorWorks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Items
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.render.AnchorRenderer
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.render.AnchorVisuals
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan.AnchorCandidate
import net.ccbluex.liquidbounce.render.FontFace
import net.ccbluex.liquidbounce.render.FontManager
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AutoAnchor - places, charges and detonates respawn anchors next to a target, driven by an exact client-side
 * replica of the 26.1.2 explosion damage model. Built to the doctrine of `combat/crystalaura/` (AGENTS.md).
 *
 * Written from scratch and NOT derived from the two anchor modules it replaced: the old `ModuleAutoAnchor` and
 * `ModuleAutoAnchor2` were deleted the moment this one was proven in game, and were deliberately never used as
 * design references. `ModuleAnchorOptimizer` is a different feature - it charges and pops anchors WE placed, with
 * no damage model at all - and stays.
 *
 * ### Design
 * One PLAN step per client tick and one EXECUTE step per client tick, at the two positions vanilla itself decides
 * and clicks:
 * - PLAN at tick HEAD (`RotationUpdateEvent`, fired by `RotationManager`'s `GameTickEvent` handler right before
 *   `RotationManager.update()`, `RotationManager.kt:319-324`; `GameTickEvent` is the HEAD of `Minecraft.tick`,
 *   `MixinMinecraft.java:291`): drain the packets queued by the netty thread into the [AnchorLedger], capture an
 *   [AnchorView], run [AnchorSearch], let [AnchorJob] pick this tick's move, switch the hotbar slot for it, and
 *   file the rotation request - which `RotationManager.update()` turns into this tick's outgoing rotation.
 * - EXECUTE at the RETURN of `Minecraft.handleKeybinds` (`InputHandleEvent`, `MixinMinecraft.java:333`), where
 *   vanilla has just processed the player's own clicks (`Minecraft.java:2050-2056`): `AnchorExecutor` re-verifies
 *   and sends. Between the two, `gameMode.tick()` has emitted the carried-item change for the slot chosen in PLAN
 *   (`MultiPlayerGameMode.java:297-298` -> `ensureHasSentCarriedItem`, `:311-317`), exactly where a real slot
 *   change appears on the wire.
 * - After EXECUTE the tick continues with `LocalPlayer.tick` -> ONE movement packet carrying the rotation
 *   `RotationManager.update()` computed in PLAN, then `ServerboundClientTickEndPacket`. Interactions are therefore
 *   judged against `AuraRotation.outgoing` - the rotation that very movement packet will carry, already final at
 *   EXECUTE.
 *
 * ### Packet sequences produced (one tick each; `[SCI]` SetCarriedItem, `[UIO]` UseItemOn, `[SW]` Swing,
 * `[MOVE]` MovePlayer, `[TE]` ClientTickEnd)
 * - place:     `[SCI]? [UIO] [SW] [MOVE] [TE]` - `BlockItem.place` returns `SUCCESS`, so vanilla swings
 * - charge:    `[SCI]? [UIO] [SW] [MOVE] [TE]` - `RespawnAnchorBlock.useItemOn` returns `SUCCESS` (`:91`)
 * - detonate:  `[SCI]? [UIO] [MOVE] [TE]` - the client branch of `useWithoutItem` returns `CONSUME` (`:120-122`),
 *   which is `Success(SwingSource.NONE)` (`net/minecraft/world/InteractionResult.java:13`), and vanilla does not
 *   swing for it. The absence of the `[SW]` is correct, not a bug.
 * - silent swap back: `[SCI back] [MOVE] [TE]` on the first tick without a move (SwapBack = 0), emitted by
 *   `gameMode.tick()` after `SilentHotbar` expired the selection.
 * - a re-aim and its click share one tick: `[SCI]? [UIO] [MOVE(new rot)] [TE]` - the click precedes the `[MOVE]`
 *   that carries the rotation it was aimed with, exactly as in vanilla, and the `[SCI]` stays ahead of the click
 *   because `gameMode.tick()` emits it (a `HELD_ITEM_CHANGE` between the click and the flying packet would make
 *   Grim drain its queue early with `hasLook = false`, `PacketPlayerDigging.java:109`, `GrimPlayer.java:444-445`).
 * - when the exact ray misses the outgoing rotation (Dither noise), NOTHING is sent that tick and the next one
 *   retries with the rotation by then on the wire.
 * - an anchor our client placed - by this module or by hand - is not clicked until the server's block-change ack
 *   for the placement has been processed: Grim may not have written it into its own world, and a charge before
 *   that answer would be a click on air (`AnchorFlow`, "Our anchor is not Grim's until...";
 *   `utils/block/PendingPlacements`).
 *
 * **There is never a second block interaction in a tick.** All three moves are `ServerboundUseItemOnPacket` and
 * `PacketOrderI` allows one per tick, so a full cycle is three ticks and cannot be compressed. That is the
 * module's single hardest invariant: [AnchorJob] emits at most one move, [AnchorExecutor] performs at most one
 * `interactBlock`, and the stand-downs below keep the other two anchor/crystal modules out of the same tick.
 *
 * ### Stand-downs (all of them, and why)
 * - **Dimension.** Act only where `!level.respawnAnchorWorks` (`utils/world/WorldExtensions.kt:47-48`), i.e. where
 *   the anchor EXPLODES instead of setting spawn - vanilla's values are overworld `false`, nether `true`, end
 *   `false`. `Anchor.AnyDimension` overrides it for a server whose data disagrees with its behaviour.
 * - **Dud latch.** The positional form of `canSetSpawn` cannot be read by a client
 *   (`RespawnAnchorBlock.java:172-174`), so [AnchorLedger] counts detonations that produced no observable
 *   explosion and latches after [DUD_LIMIT] of them. [consumeLatchNotice] prints the verdict once.
 * - **Glowstone in the OFFHAND.** `RespawnAnchorBlock.useItemOn` returns `PASS` instead of
 *   `TRY_WITH_EMPTY_HAND` when `hand == MAIN_HAND && isRespawnFuel(offhand) && canBeCharged(state)` (`:93-95`).
 *   `PASS` is neither `consumesAction()` nor a `TryEmptyHandInteraction`, so `performUseItemOn` never reaches
 *   `useWithoutItem` (`MultiPlayerGameMode.java:352-354`) and the offhand pass charges the anchor instead. A
 *   player who keeps glowstone in the offhand can never detonate. The module says so in `Debug` and stands down;
 *   it does NOT try to fix it with an offhand swap, which would be a second `PLAYER_ACTION` in the tick. The
 *   `canBeCharged` half of that condition (a `CHARGE == 4` anchor would still detonate) is deliberately not
 *   exploited: our own cycle never produces one.
 * - **Sneaking.** `performUseItemOn` sets `suppressUsingBlock = isSecondaryUseActive() && haveSomethingInOurHands`
 *   and skips the block entirely (`MultiPlayerGameMode.java:339-341`), so every click would be a dead packet.
 * - **[ModuleAutoTotem] has a totem swap in flight** (`ModuleAutoTotem.isSwapping`, added 2026-09-13). Each move
 *   of this module consumes an inventory slot server-side - an anchor, a glowstone - and the server bumps the open
 *   menu's state id for it, so the totem's own container click, which still carries the older state id, is
 *   answered with a full `ClientboundContainerSetContentPacket` resync
 *   (`ServerGamePacketListenerImpl.java:1915-1916`). That resync overwrites the predicted offhand: the totem is
 *   drawn in a hand the server does not have and does not pop - the ghost totem
 *   (`utils/inventory/OffhandSyncTracker.kt`). A stand-down rather than a click hold, because the module's
 *   `SetCarriedItem` is a packet too and would sit between `InventoryManager`'s inventory open and its click.
 *   `HandSwapGate` covers only the middle of the swap's three phases; this covers all of them.
 * - **The inventory is open server-side** (another module's container session, [inventoryReason]). TotemGuard
 *   fails both our `UseItemOn` and our `SetCarriedItem` while it is.
 * - **Another module's block interaction, [ModuleCrystalAura]'s or [ModuleAnchorOptimizer]'s.** Both publish a
 *   `lastActionMs` WALL CLOCK - a wall clock because handler order between modules is undefined, so neither can
 *   read the other's tick counter and get a stable answer - and this module yields for [YIELD_MS] after one.
 *   That window is the whole of what `PacketOrderI` needs: it counts INTERACTIONS per tick, and an interaction
 *   is a packet.
 *
 * ### The aura chain: KillAura < AutoAnchor < CrystalAura
 * Set by the user on 2026-09-11, and the ordering is by how OFTEN a module can act, inverted. KillAura can swing
 * at anything in reach, so it can act nearly every tick; this module can reach a cell nearly every time, because
 * an anchor detonated at the victim's feet almost always clears `MinDamage`; CrystalAura is the pickiest, since a
 * crystal needs an obsidian pedestal AND enough damage. So the rarest opportunity outranks the commonest: when
 * CrystalAura has work it takes the tick, otherwise this module does, and KillAura fills what is left.
 *
 * The test is WORKING - [ModuleCrystalAura.isActing] - and the distinction is the whole point. `isRotating` was
 * tried first and was far too broad: that aura holds the aim whenever a breakable crystal is in range, so this
 * module stood down for an aura that was merely SWITCHED ON. Enabled is not active. `isActing` is true only while
 * a cycle is really in progress - a packet within the last few ticks, or a placement still in flight - so an aura
 * with nothing to do steps aside instead of squatting on the chain.
 *
 * Enforced by ABSTENTION, the idiom this codebase already used: the lower module does not file a rotation request
 * at all while a higher one is working (`ModuleKillAura.anAuraIsWorking` does exactly that, and did it for
 * CrystalAura long before this module existed). Deterministic in a way a priority comparison is not - and the
 * numbers are only the second layer, `IMPORTANT_FOR_USAGE_2` (30) here against CrystalAura's `_3` (35).
 *
 * If this module ever does go quiet, that is visible rather than mysterious: `Debug` prints the reason and the
 * overlay greys the cell and draws it (`render/AnchorRenderer`).
 *
 * [ModuleAnchorOptimizer] sits outside that chain, higher than all of it (`IMPORTANT_FOR_PLAYER_LIFE`, 40), and
 * keeps its aim outright; it additionally yields to this module's own [lastActionMs] / [isRotating] in its
 * `pauseReason()`, which is what keeps the two from interleaving clicks on the same anchor.
 *
 * ### Bookkeeping
 * Incoming packets are queued on the netty thread ([PacketIntake]) and drained in PLAN, right after the ledger
 * advanced its clock so the drained packets carry the current tick. All state belongs to exactly one
 * [ClientLevel]: PLAN compares `mc.level` by identity against a weakly held reference and resets on a mismatch
 * ([guardLevel]) - the `WorldChangeEvent` handler alone cannot carry that, it is `running`-gated and misses both
 * the disconnect and the login.
 *
 * The detonation is the one step the client predicts nothing of, so [detonated] keeps the cells we clicked and
 * PLAN reports a vanished anchor to [AnchorLedger.onAnchorGone] - the ledger's second clearing route, next to the
 * `ClientboundExplodePacket` the intake feeds it.
 */
/*
 * LargeClass / TooManyFunctions: this object is the hub and deliberately holds no logic - the search, the flow,
 * the job and the executor are separate classes. What is left here is the settings tree, the event handlers and
 * the per-tick wiring between them, which is exactly the part that cannot be moved out without inventing a second
 * object to hold the same state. `ModuleCrystalAura` carries the same pair of suppressions for the same reason.
 */
@Suppress("TooManyFunctions", "LargeClass")
object ModuleAutoAnchor : ClientModule("AutoAnchor", ModuleCategories.COMBAT) {

    val anchor = tree(AnchorSettings())
    val rotation = tree(RotationSettings())

    /**
     * `Range` is registered INSIDE the tracker's own group through [FloatValueProvider]
     * (`utils/client/RangedValueProvider.kt:58-70`), so every target setting sits under one "Target" node instead
     * of leaving a loose slider on the module.
     */
    val targetTracker = tree(TargetTracker(TargetPriority.HEALTH, FloatValueProvider("Range", 10f, 1f..16f)))

    val render = tree(AnchorRenderSettings())

    val debug = tree(DebugSettings(this))

    /**
     * The face the overlay's damage text prefers, or `null` when it is unavailable - `AuraText` then keeps its
     * SystemDLC path. The same face the crystal aura uses, resolved the same way and for the same reason: the
     * OTFs are baked before the glyph manager exists, so a face is asked for by name and never held.
     */
    val damageFont: FontFace?
        get() = FontManager.fontFace(DAMAGE_FONT_NAME)

    /** LookThrough filters for the pick rays ([AnchorSettings.lookThrough]). */
    private val ignoreNone: (Entity) -> Boolean = { false }
    private val ignoreAll: (Entity) -> Boolean = { true }

    private val intake = PacketIntake()
    private val ledger = AnchorLedger()
    private val job = AnchorJob(anchor, AnchorExecutor(anchor, rotation))

    /** Client ticks since the last reset (the ledger's clock). */
    private var tick = 0

    /** This tick's cycle budget ([cycleBudget]); read by the render for the progress ribbon. */
    private var cycleTicks = CYCLE_BASE_TICKS

    /**
     * The victim this tick's cycle is aimed at, or null when the module has nothing to do. Published for the same
     * reason `ModuleCrystalAura.target` is: another module can align its own target with ours.
     */
    var target: LivingEntity? = null
        private set

    /**
     * Whether this module is steering the view right now, i.e. it asked `RotationManager` for a rotation in this
     * tick or the one before. The one-tick grace matches the fact that a rotation requested in PLAN only reaches
     * the wire at the end of that tick.     *
     * The comparison is `rotatingTick >= tick - 1` and NOT `tick - rotatingTick <= 1`, which is what it used to
     * be. With the `NEVER` sentinel below, that subtraction overflows: `tick - Int.MIN_VALUE` wraps to a negative
     * int for every reachable tick (checked on JDK 25 up to `Int.MAX_VALUE - 1`), so the flag read TRUE from the
     * moment the module was enabled until it first asked for a rotation - and a module that stands down never
     * asks. Found by audit 2026-09-20; it had `ModuleAnchorOptimizer` paused for whole sessions.
     */
    val isRotating: Boolean
        get() = running && rotatingTick >= tick - 1

    /**
     * Whether this module is WORKING right now - not merely enabled, and not merely aiming.
     *
     * The signal the aura chain runs on (class KDoc). A cycle here is up to three ticks of one interaction each,
     * so both terms are needed: an interaction went out less than [ACTING_MS] ago, or a cycle is running and has
     * simply not reached its next click yet. Between them they cover a whole place -> charge -> detonate run
     * without ever staying true once the module goes quiet.
     */
    val isActing: Boolean
        get() = running && (System.currentTimeMillis() - lastActionMs < ACTING_MS || job.active)

    /** Tick of the last rotation request; [NEVER] until the module asks for one. */
    private var rotatingTick = NEVER

    /**
     * Wall clock of the last block interaction this module actually sent, or `0` when it has sent nothing yet.
     *
     * Milliseconds and not ticks on purpose: handler order between modules is undefined, so a wall-clock window is
     * the only thing two modules can agree on. [ModuleAnchorOptimizer] reads it to keep its own `UseItemOn` out of
     * the tick window we just acted in - two block interactions in one tick is `PacketOrderI`.
     */
    @Volatile
    var lastActionMs: Long = 0L
        private set

    private var plan: AnchorPlan? = null

    /** Render snapshot of this tick, built in PLAN and read by the render handlers between ticks. */
    private var visuals: AnchorVisuals? = null
    private var lastView: AnchorView? = null
    private var search: AnchorSearch? = null

    /** The candidate the running cycle was started on; the only place its damage figures survive. */
    private var scored: AnchorCandidate? = null
    private var rayIgnore: (Entity) -> Boolean = ignoreNone

    /** Cells we sent a detonating click for, watched until the ledger accounts for them ([confirmDetonations]). */
    private val detonated = ArrayList<BlockPos>()

    /** The level the current state belongs to; weak, so a level we saw is never kept alive by us ([guardLevel]). */
    private var levelRef: WeakReference<ClientLevel>? = null

    /**
     * Foreign click packets of the current Grim tick window, set and cleared in [observeOutgoing] and read
     * in EXECUTE. The window ends at [isTickDelimiter], never in PLAN - see there for why.
     */
    private var foreignUseSeen = false
    private var foreignDigSeen = false
    private var foreignEntitySeen = false

    /**
     * Every outgoing packet of the current tick, in order, recorded only while `Debug.Packets` is on; entries the
     * module itself sent are prefixed with `*`. Printed at the head of the next PLAN, so one line carries exactly
     * one client tick - the unit Grim's tick-window checks reason in (`GrimProcessor.java:80-91`).
     */
    private val packetTrace = ConcurrentLinkedQueue<String>()

    /** True while [executeTick] runs, so [observeOutgoing] can tell the module's own packets from foreign ones. */
    @Volatile
    private var ownSend = false

    /** Hotbar slot to give back after a [SwitchMode.NORMAL] switch, and the slot we forced ([restoreNormalSlot]). */
    private var restoreSlot: Int? = null
    private var forcedSlot: Int? = null

    /** Rate limit of the `Debug` chat line. */
    private val logTimer = Chronometer(0)

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.INCOMING) {
            intake.offer(event.packet)
        } else {
            observeOutgoing(event.packet)
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        resetState()
    }

    @Suppress("unused")
    private val planHandler = handler<RotationUpdateEvent> {
        planTick()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        AnchorRenderer.render(event, visuals, render)
    }

    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        AnchorRenderer.renderOverlay(event, render)
    }

    private val executeHandler = handler<InputHandleEvent> {
        executeTick()
    }

    override fun onEnabled() {
        resetState()
        super.onEnabled()
    }

    override fun onDisabled() {
        resetState()
        SilentHotbar.resetSlot(this)
        super.onDisabled()
    }

    /** The PLAN step (class KDoc). */
    private fun planTick() {
        guardLevel()
        tick++
        val pingTicks = Math.round(player.ping / MS_PER_TICK).toInt()
        cycleTicks = cycleBudget(pingTicks)
        // The ledger stamps everything with its own clock, so it has to advance BEFORE the drain, or the drained
        // packets are dated one tick early and every timeout they touch is one tick short.
        ledger.onTickStart(
            tick, detonateTimeout(pingTicks), DUD_TICKS, DUD_LIMIT,
            blastShadowTicks = pingTicks + BLAST_SHADOW_MARGIN_TICKS,
        )
        intake.drainInto(ledger)
        confirmDetonations()
        tracePreviousTick()
        if (ledger.consumeLatchNotice()) {
            debugLine("no anchor of ours has exploded here - detonation stands down until the world changes")
        }

        if (mc.screen != null || mc.overlay != null) {
            pauseForScreen()
            return
        }

        val reason = standDownReason()
        if (reason != null) {
            idle()
            // The overlay keeps the last cell and prints the reason on it. Four of these gates are invisible from
            // the world, and a module that goes quiet for one of them looks broken (`AnchorRenderer`, class KDoc).
            visuals = AnchorVisuals(cell = visuals?.cell, standDown = reason)
            debugLog { "stand down: $reason" }
            return
        }
        planCycle(pingTicks)
    }

    /**
     * The half of PLAN that runs once every gate is open: capture, search, decide, and file the slot and the
     * rotation the decision needs.
     */
    private fun planCycle(pingTicks: Int) {
        val view = captureView(pingTicks)
        lastView = view
        if (view.targets.isEmpty()) {
            idle()
            visuals = null
            return
        }

        val ignore = rayIgnoreFor(anchor.lookThrough)
        rayIgnore = ignore
        val tickSearch = AnchorSearch(
            view = view,
            calc = DamageCalculator(anchor.iFrames == IFrameMode.RESPECT),
            settings = anchor,
            ammo = ammo(),
            excluded = ledger::isExcluded,
        )
        search = tickSearch

        // `best()` is asked ONCE and the winner kept: it is the only place the damage figures exist, and the
        // render needs them for every tick of the cycle, not just the tick that started it. Re-running the search
        // to get them back would price the whole heap again for a drawing.
        val candidate = if (job.active) null else tickSearch.best()
        if (candidate != null) {
            scored = candidate
        }

        val decided = job.plan(
            view = view,
            ledger = ledger,
            best = candidate,
            maxTicks = cycleTicks,
            slack = AuraRotation.slackDegrees(rotation),
            rayIgnore = ignore,
        )
        plan = decided
        target = decided?.entity as? LivingEntity
        // The rotation is filed either way; only the slot switch waits for Surround ([surroundHolds]).
        val held = surroundHolds()
        if (!held) selectSlot(decided)
        requestRotation(decided)
        visuals = buildVisuals(view, decided)
        debugLog { "${job.describe()} | ${decided?.move ?: "-"}" + if (held) " | holding: Surround is placing" else "" }
    }

    /**
     * Whether a `ModuleSurround` placement is on the wire in this tick window - the top of the aura chain, above this
     * module (user, 2026-09-27), and since 2026-10-03 for the placement itself only, not while Surround is on (see
     * `ModuleSurround.isActing`).
     *
     * Held on the PACKETS only, not through [standDownReason]: that path idles the module and files no rotation,
     * and the placer's `IMPORTANT_FOR_PLAYER_LIFE` (40) outranks our 30 anyway - filing it means the plan is already
     * top of the queue the tick the placement is done instead of the aim passing through a reset first. The slot
     * switch is held in PLAN and the plan is dropped at EXECUTE, which asks again because the placement leaves from
     * `PostRotationExecutor`'s `GameTickEvent` handler, whose order against `RotationManager`'s (which runs our PLAN)
     * is undefined - both are `FIRST_PRIORITY`. A cycle held for longer than its budget ends on the budget, as any
     * stalled cycle does; the flow re-enters at the first missing step.
     */
    private fun surroundHolds(): Boolean = ModuleSurround.isActing

    /** The EXECUTE step (class KDoc); a plan is executed at most once and performs at most one interaction. */
    private fun executeTick() {
        // Re-read, because the window can arm between PLAN and here - see [totemWindow]. The plan is DROPPED
        // rather than held: it is this tick's geometry, and the flow re-enters at the first missing step anyway.
        (totemWindow() ?: inventoryReason())?.let { reason ->
            plan = null
            debugLog { "stand down at EXECUTE: $reason" }
            return
        }
        // Same drop for the module above us in the chain ([surroundHolds]).
        if (surroundHolds()) {
            plan = null
            debugLog { "held at EXECUTE: Surround is placing" }
            return
        }

        val current = plan ?: return
        plan = null
        val view = lastView ?: return

        // A block interaction may not follow a foreign use, dig or entity interaction inside one tick window:
        // `PacketOrderI.java:98-123`, `MultiActionsF.java:21, 43-74` and `PacketOrderJ.java:12, 24` all read that
        // window, and `InputHandleEvent` fires after every click vanilla itself made this tick.
        // Same for a hand swap of this window: the glowstone or the anchor may not be in the hand server-side yet,
        // and the click would sit between the swap and its close packet ([HandSwapGate]). Read here and not left to
        // `interactBlock`, because [AnchorExecutor] counts a returned `useItemOn` as sent whatever it answered -
        // a rejection down there would advance the flow and book a detonation that never happened.
        val allowUse = !foreignUseSeen && !foreignDigSeen && !foreignEntitySeen && !HandSwapGate.holdsUses
        ownSend = true
        val report = try {
            job.execute(current, view, rayIgnore, allowUse)
        } finally {
            ownSend = false
        }
        if (report.sent) {
            lastActionMs = System.currentTimeMillis()
            if (current.move == AnchorMove.DETONATE) {
                bookDetonation(current.cell)
                AnchorRenderer.recordBlast(AnchorMath.anchorCenter(current.cell), render.dome)
            }
        }
        if (report.reason != null || report.sent) {
            debugLog { report.toString() }
        }
    }

    /**
     * The detonating click left the client. Nothing local will ever confirm the blast - the client branch of
     * `useWithoutItem` returns `CONSUME` and `ClientLevel.explode` has an empty body - so the cell goes into the
     * ledger, which hides it from the search until a `ClientboundExplodePacket`, a vanished anchor or the timeout
     * accounts for it.
     */
    private fun bookDetonation(pos: BlockPos) {
        ledger.markDetonated(pos, search?.predictedLastHurt(pos) ?: emptyMap())
        detonated += pos
    }

    /**
     * Route 2 of the ledger's clearing (`state/AnchorLedger.kt`, class KDoc): the cell stopped reading as a
     * `RespawnAnchorBlock`. Weaker than the explode packet - someone may simply have mined it - but conclusive,
     * since there is nothing left to detonate either way. Cells the ledger has already accounted for are dropped.
     */
    private fun confirmDetonations() {
        if (detonated.isEmpty()) return

        val iterator = detonated.iterator()
        while (iterator.hasNext()) {
            val pos = iterator.next()
            if (!ledger.isDetonating(pos)) {
                iterator.remove()
            } else if (chargeOf(world.getBlockState(pos)) == null) {
                ledger.onAnchorGone(pos)
                iterator.remove()
            }
        }
    }

    /**
     * Why the module may not act this tick, or null when every gate is open. Listed and justified in the class
     * KDoc; the order is cheapest first.
     */
    private fun standDownReason(): String? = when {
        !anchor.anyDimension && world.respawnAnchorWorks ->
            "the dimension says anchors set spawn instead of exploding (AnyDimension is off)"
        !anchor.anyDimension && ledger.latched -> "dud latch: detonations here produce no explosion"
        player.isSecondaryUseActive -> "sneaking: the server skips the block interaction"
        player.offhandItem.`is`(Items.GLOWSTONE) -> "glowstone in the offhand: useItemOn returns PASS, no detonation"
        // The two totem windows and the open inventory, which EXECUTE asks about again - see [totemWindow].
        else -> totemWindow() ?: inventoryReason() ?: chainReason()
    }

    /**
     * Another module holds the inventory open server-side (`InventoryManager.isInventoryOpenServerSide`: a
     * container click went out and its close has not).
     *
     * TotemGuard's `InventoryA` fails every `PLAYER_BLOCK_PLACEMENT` ("place", `check/impl/inventory/
     * InventoryA.java:51, 109-113`) and every `HELD_ITEM_CHANGE` ("change slot", `:103-107`) while it believes the
     * inventory is open - a state vanilla cannot be in, since a container screen skips `handleKeybinds`. Both of
     * those are this module's packets. Grim has no such check (no check under `checks/impl` reads the open
     * inventory), so this is the TotemGuard half of "no interaction while a container is open server-side".
     *
     * Re-asked at EXECUTE like [totemWindow], because `InventoryManager`'s scheduler runs after PLAN in the same
     * tick. Known residue, both "change slot" only: a slot switch filed in the PLAN of the very tick another
     * module opens the inventory is still emitted by `gameMode.tick()`, and a silent slot of ours that was already
     * selected expires on its own schedule (`SilentHotbar`), so its swap-back can land inside the session.
     */
    private fun inventoryReason(): String? =
        if (InventoryManager.isInventoryOpenServerSide) "the inventory is open server-side" else null

    /**
     * The totem half of [standDownReason], split out because EXECUTE must ask it AGAIN.
     *
     * A totem swap of [ModuleAutoTotem]'s means every move of ours consumes an inventory slot server-side, which
     * bumps the open menu's state id and turns the totem's own click into a full resync - the ghost totem
     * (`ModuleAutoTotem.isSwapping`, `utils/inventory/OffhandSyncTracker.kt`). Stand-down, not just a click hold:
     * the hotbar switch is a packet of ours too, and it would sit between `InventoryManager`'s inventory open and
     * its click. Same for [ModuleMainHandTotem]: a totem held in the MAIN hand is neither glowstone nor an anchor,
     * and its staging click carries a state id our interactions would resync away.
     *
     * Why EXECUTE cannot trust PLAN's answer: PLAN runs inside `RotationManager`'s `GameTickEvent` handler at
     * `FIRST_PRIORITY` (1000), while `InventoryManager`'s scheduler is a plain `tickHandler`, i.e. priority 0
     * (`event/SuspendHandlers.kt:57-66`), and `EventHookRegistry` orders descending - so a swap is scheduled
     * AFTER our PLAN of the same tick, and the other two phases (the click on the wire, then
     * `OffhandSyncTracker.isUnconfirmed`) are later still.
     *
     * The rest of [standDownReason] is deliberately not re-asked here. The dimension, the latch, the sneak and
     * the offhand glowstone cannot flip mid-tick in a way that matters, and the packet-order half of the aura
     * chain is already covered structurally at EXECUTE by `foreignUseSeen` - which sees CrystalAura's own
     * interaction of this tick whatever order the two handlers ran in.
     */
    private fun totemWindow(): String? = when {
        ModuleAutoTotem.isSwapping -> "AutoTotem has a totem swap in flight"
        ModuleMainHandTotem.isBusy -> "MainHandTotem holds the main hand"
        else -> null
    }

    /** The aura chain and the block-interaction yield (class KDoc). */
    private fun chainReason(): String? = when {
        // The aura chain (class KDoc). Subsumes the packet-order window for this module: `isActing` already
        // covers "sent something in the last few ticks", which is what `PacketOrderI` cares about.
        ModuleCrystalAura.isActing -> "CrystalAura is working"
        isBusy(ModuleAnchorOptimizer.running, ModuleAnchorOptimizer.lastActionMs) ->
            "AnchorOptimizer just interacted"
        else -> null
    }

    /**
     * Whether another module sent a block interaction less than [YIELD_MS] ago.
     *
     * The wall clock is the point. Handler order between modules is undefined - ours may run before or after
     * theirs within the same tick - so neither can look at the other's tick counter and get a stable answer, and
     * `PacketOrderI` counts packets per tick, not per handler.
     *
     * Their AIM is a separate question and has its own gate above this one (the aura chain, class KDoc): a
     * module that is merely aiming has sent no packet, so `PacketOrderI` has nothing to say about it.
     */
    private fun isBusy(enabled: Boolean, lastAction: Long): Boolean =
        enabled && System.currentTimeMillis() - lastAction < YIELD_MS

    /**
     * Which entry phases can be paid for right now. Folded into the search's ACCEPTANCE rather than checked later:
     * a candidate the sequencer would refuse for want of an item must never be proposed, or the module starves on
     * cycles it keeps rejecting. The slot lookup is the sequencer's own ([hotbarSlotFor]), so the two agree by
     * construction, `SwitchMode.NONE` included.
     *
     * Charging is not optional. It used to be (`Anchor.Recharge`, removed 2026-09-26): switched off, every NEW cell
     * and every uncharged anchor failed [AnchorAmmo.affords], which left the module detonating only anchors somebody
     * else had charged - a saved `false` made it look dead in a fight (`idle` on every tick, no reason printed).
     * Glowstone missing from the hotbar still restricts it to charged anchors, which is the only case where that
     * restriction means something.
     */
    private fun ammo(): AnchorAmmo {
        val mode = anchor.switchMode
        return AnchorAmmo(
            canPlace = hotbarSlotFor(Items.RESPAWN_ANCHOR, mode) != null,
            canCharge = hotbarSlotFor(Items.GLOWSTONE, mode) != null,
        )
    }

    /**
     * `AnchorView.capture` with this tick's prediction and latency inputs. `AssumeResistance` is a 1-based setting
     * (0 = assume none) while the capture takes an amplifier, so it is passed one lower.
     */
    private fun captureView(pingTicks: Int): AnchorView = AnchorView.capture(
        tick = tick,
        targets = targetTracker.targets(),
        lastHurt = ledger::lastHurtEstimate,
        predictTicks = predictTicks(pingTicks),
        latencyTicks = pingTicks,
        assumeResistance = anchor.assumeResistance - 1,
        assumeArmor = anchor.assumeArmor,
    )

    /**
     * How far ahead the victim is extrapolated for the damage evaluation: the latency term plus the interactions
     * still to come.
     *
     * The latency term is half the round trip - the blast happens when the SERVER processes our click, about
     * `ping / 2` after we send it, and `pingTicks` is the full round trip in ticks.
     *
     * The second term is why `AnchorFlow.remaining` is public. A cycle entered at PLACE detonates up to three
     * ticks after the snapshot it was scored against, so predicting only the snapshot tick would score a victim
     * three ticks in their past. While a cycle runs the depth is exact; on the tick a NEW cycle is chosen it is
     * not yet known which of the three entry phases will win, so [DETONATE_ONLY] is assumed - the cheapest and
     * most common entry, and the one where being wrong costs a single tick. The error is bounded by the cycle
     * length either way, and the re-validation deliberately does not re-score.
     */
    private fun predictTicks(pingTicks: Int): Int {
        val latency = when (anchor.predict) {
            PredictMode.OFF -> return 0
            PredictMode.AUTO -> Math.round(PREDICT_FACTOR * pingTicks).toInt()
            PredictMode.FIXED -> anchor.predictTicks
        }
        return latency + if (job.active) job.remaining else DETONATE_ONLY
    }

    /**
     * Ticks a detonated cell stays excluded from the search, and the window inside which its explosion must be
     * observed: `ping + 2`, the shape the crystal ledger uses for a chain-removed crystal.
     *
     * After the detonating click the anchor is still drawn locally - the client branch of
     * `RespawnAnchorBlock.useWithoutItem` returns `CONSUME`
     * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:120-122`) and `ClientLevel.explode` has an empty
     * body (`net/minecraft/client/multiplayer/ClientLevel.java:1045-1060`) - so without this the search
     * re-proposes the same anchor every tick until the server's removal arrives. The two
     * are one window on purpose: `AnchorLedger` turns a detonation that reaches this age with neither a
     * `ClientboundExplodePacket` nor a vanished anchor into a DUD, and a cell whose detonation is still unexplained
     * must stay hidden. It was a setting (`Cycle.DetonateTimeout`, `0` = this formula) until 2026-09-26.
     */
    private fun detonateTimeout(pingTicks: Int): Int = pingTicks + DETONATE_TIMEOUT_EXTRA

    /**
     * Ticks one cycle may run before it is abandoned: [CYCLE_BASE_TICKS] of slack plus the round trip.
     *
     * The round trip is in the budget because the cycle WAITS for it: after our placement, the charge is held until
     * the server's block-change ack for it has been processed (`AnchorFlow`, "Our anchor is not Grim's until...";
     * `utils/block/PendingPlacements`), and those ticks are charged to the same counter as the clicks
     * (`AnchorFlow.tick`, step 2). A fixed budget therefore shrinks with ping, and a small one aborts every placing
     * cycle before its charge: that was the 2026-09-26 log, `Cycle.MaxTicks = 5` on a server whose ack takes longer
     * than the two or three ticks that left. Capped at [MAX_WAIT_TICKS], the point where `PendingPlacements` stops
     * waiting anyway, so a garbage ping reading cannot keep a dead cycle alive.
     */
    private fun cycleBudget(pingTicks: Int): Int = CYCLE_BASE_TICKS + pingTicks.coerceIn(0, MAX_WAIT_TICKS)

    /**
     * Resets everything when `mc.level` is no longer the level the state was built on. The `WorldChangeEvent`
     * handler cannot carry this alone: it is `running`-gated (`EventManager.kt:347` -> `ClientModule.kt:80-81` ->
     * `inGame`, which needs both a level and a player), while `WorldChangeEvent` is fired at the HEAD of
     * `updateLevelInEngines` (`MixinMinecraft.java:407-410`) - which `Minecraft.disconnect` reaches only after
     * nulling `level` and which `ClientPacketListener.handleLogin` reaches through `setLevel` before assigning the
     * player. Both times `inGame` is false and the handler is skipped, so a stale dud latch and stale cells would
     * leak into the next world.
     */
    private fun guardLevel() {
        val level = mc.level
        if (levelRef?.get() !== level) {
            resetState()
            levelRef = level?.let(::WeakReference)
        }
    }

    /**
     * A screen or overlay makes vanilla skip `handleKeybinds` (`Minecraft.java:1863-1869`) and with it EXECUTE, so
     * a plan made now could never be clicked. Drop it and give the hand back instead of rotating the player
     * server-side and holding a slot for a whole inventory session.
     */
    private fun pauseForScreen() {
        idle()
        SilentHotbar.resetSlot(this)
    }

    /**
     * Nothing to do this tick: drop the plan and the cycle, and give a visible hotbar switch back.
     *
     * [AnchorJob.stop], not [AnchorJob.reset]: a stand-down or a screen must not restart the `Anchor.Delay`
     * throttle, which lives in the flow's wall clock and is meant to span cycles.
     */
    private fun idle() {
        plan = null
        target = null
        scored = null
        job.stop()
        restoreNormalSlot()
    }

    /**
     * The render snapshot of the decided plan, built once per PLAN tick and read by the render handlers between
     * ticks (`render/AnchorVisuals.kt`).
     *
     * The damage figures come from [scored], the candidate that STARTED the cycle, and only while it still names
     * the cell being worked - a cycle is never re-scored mid-flight (`AnchorJob`, "Re-validation"), so quoting a
     * newer number over an older cell would be a lie the module never acted on. The victim's state is looked up in
     * this tick's own targets rather than kept from the candidate, so the ring follows a victim whose armour or
     * health changed under a running cycle.
     */
    private fun buildVisuals(view: AnchorView, plan: AnchorPlan?): AnchorVisuals {
        val cell = plan?.cell ?: job.cell
        val entity = plan?.entity as? LivingEntity
        val entry = view.targets.firstOrNull { it.entity === entity }
        val hit = scored?.takeIf { it.pos == cell }

        return AnchorVisuals(
            cell = cell,
            clickPos = plan?.clickPos,
            face = plan?.face,
            phase = job.phase,
            remaining = job.remaining,
            phaseTicks = job.phaseTicks,
            maxPhaseTicks = cycleTicks,
            charge = cell?.let { view.anchorCharge(it) },
            target = entity,
            targetState = entry?.state,
            selfState = view.self,
            bestDamage = hit?.damage?.effective ?: 0f,
            selfDamage = hit?.selfDamage?.effective ?: 0f,
            lethal = entry != null && hit?.damage?.isLethalFor(entry.state) == true,
            minDamage = anchor.minDamage,
            maxSelfDamage = anchor.maxSelfDamage,
        )
    }

    /**
     * Brings the item this tick's move needs into the hand NOW, so `gameMode.tick()` emits the single
     * `SetCarriedItem` at its vanilla position, ahead of the click (class KDoc).
     *
     * It keys on the planned MOVE, not on the click actually landing: the ray verdict is only known at
     * `InputHandleEvent`, long after `gameMode.tick()` had its one chance to emit the slot change. A missed
     * attempt therefore costs one `SetCarriedItem` and nothing else.
     */
    private fun selectSlot(plan: AnchorPlan?) {
        val index = plan?.slot?.hotbarIndex
        if (index == null) {
            restoreNormalSlot()
            return
        }

        when (anchor.switchMode) {
            SwitchMode.SILENT -> SilentHotbar.selectSlotSilently(this, index, anchor.swapBack)
            SwitchMode.NORMAL -> normalSwitch(index)
            SwitchMode.NONE -> {}
        }
    }

    /**
     * The vanilla-visible switch: remembers the slot the player had before the FIRST switch (a later switch keeps
     * that original) and writes the field only when it differs.
     */
    private fun normalSwitch(index: Int) {
        val inventory = player.inventory
        val selected = inventory.selectedSlot
        dropRestoreIfPlayerMoved(selected)
        if (selected == index) return

        if (restoreSlot == null) {
            restoreSlot = selected
        }
        inventory.selectedSlot = index
        forcedSlot = index
    }

    /**
     * Gives a [SwitchMode.NORMAL] switch back. Nothing anywhere else restores `Inventory.selected`, so without
     * this the module would leave the player on the anchor or glowstone slot for good.
     */
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

    /** Never fight the player: once their own selection left the slot we forced, the pending restore is void. */
    private fun dropRestoreIfPlayerMoved(selected: Int) {
        if (restoreSlot != null && selected != forcedSlot) {
            restoreSlot = null
            forcedSlot = null
        }
    }

    /**
     * Requests the plan's look point (with the victim as the reset-detection entity, see `AuraRotation`), or holds
     * the rotation already on the wire while a cycle is in progress.
     *
     * The request is filed BEFORE `RotationManager.update()` runs - the manager fires `RotationUpdateEvent` and
     * calls `update()` back to back (`RotationManager.kt:319-324`) - so it is THIS tick's movement packet that
     * carries it, and `AuraRotation.outgoing` already holds it when EXECUTE verifies the ray.
     *
     * A null look point with a live cycle is the normal case from the second tick on: the aim is taken once and
     * held, which is the shape Grim's `DuplicateRotPlace` rewards (`AnchorJob`, class KDoc).
     */
    private fun requestRotation(plan: AnchorPlan?) {
        if (plan == null) return

        val look = plan.look
        if (look != null) {
            AuraRotation.request(this, look.rotation, plan.entity, rotation)
            rotatingTick = tick
            return
        }

        // A null look point means one of two very different things, and only one of them may pin the view:
        // either the rotation on the wire already lands on the click target (`AnchorJob.lookFor` returns null for
        // that), or no reachable point was found at all. `AuraRotation.hold` re-requests the CURRENT wire
        // rotation, so calling it freezes the camera where it stands and overrides the player's own mouse. That is
        // only earned once the cycle has actually sent an interaction and the remaining clicks depend on the aim
        // staying put; before that, holding a cell we can never finish is a permanent view lock, because the cycle
        // restarts on the same candidate every [cycleBudget]. So: no engagement, no hold - the manager releases
        // and the view goes back to the player.
        if (!job.engaged) return

        AuraRotation.hold(this, rotation)
        rotatingTick = tick
    }

    /**
     * Records the click packets of this tick. The module's own sends all happen in [executeTick], which reads the
     * flags before it sends anything, and PLAN clears them again at the head of the next tick - but they are still
     * filtered by [ownSend], so a trace line can name whose packet was whose.
     */
    private fun observeOutgoing(packet: Packet<*>) {
        if (debug.enabled && debug.packets) {
            packetTrace += (if (ownSend) "*" else "") + packet.javaClass.simpleName
                .removePrefix("Serverbound").removeSuffix("Packet")
        }
        if (isTickDelimiter(packet)) {
            foreignUseSeen = false
            foreignDigSeen = false
            foreignEntitySeen = false
            return
        }
        if (ownSend) return

        when (packet) {
            is ServerboundUseItemOnPacket, is ServerboundUseItemPacket -> foreignUseSeen = true
            is ServerboundAttackPacket, is ServerboundInteractPacket -> foreignEntitySeen = true
            is ServerboundPlayerActionPacket -> if (isDigAction(packet.action)) foreignDigSeen = true
            else -> {}
        }
    }

    /**
     * The end of a Grim tick window: the movement packet of the tick, or `CLIENT_TICK_END` on a tick that sent
     * none (`GrimProcessor.java:80-91`, quoted in the class KDoc). `ServerboundClientTickEndPacket` is sent
     * unconditionally by every unpaused tick with a connection (`Minecraft.java:1925-1927`), after
     * `handleKeybinds` and after `level.tick()`'s movement send, so one of the two always arrives and always
     * after EXECUTE.
     *
     * This is where the foreign-click flags are cleared, and it has to be here rather than in PLAN. PLAN is not a
     * boundary: it runs inside `RotationManager`'s `GameTickEvent` handler, and other modules act from that same
     * event - `ModuleAnchorOptimizer` emits its whole cycle from `RotationUpdateEvent` by design, and the
     * catlean placer behind `ModuleAutoWeb` places there too. Handler order between modules is undefined, so
     * whenever one of them ran first, a reset in PLAN ERASED a click that really was in this tick's window and
     * EXECUTE then read the window as clean - two block interactions in one window, which is exactly the
     * `PacketOrderI` violation the flags exist to prevent. Clearing on the delimiter makes the window structural:
     * the flags cover precisely the packets between two tick boundaries, whoever sent them and in whatever order
     * the handlers ran.
     */
    private fun isTickDelimiter(packet: Packet<*>): Boolean =
        packet is ServerboundMovePlayerPacket ||
            packet is ServerboundMoveVehiclePacket ||
            packet is ServerboundClientTickEndPacket

    /**
     * Only the three dig actions belong to vanilla's attack loop. `SWAP_ITEM_WITH_OFFHAND` and `DROP_ITEM` /
     * `DROP_ALL_ITEMS` are sent BEFORE the click loops (`Minecraft.java:2015-2025`) and `RELEASE_USE_ITEM` comes
     * from `releaseUsingItem`, so none of them says anything about the order of the clicks
     * (`ServerboundPlayerActionPacket.java:69-77`).
     */
    private fun isDigAction(action: ServerboundPlayerActionPacket.Action): Boolean = when (action) {
        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK -> true
        else -> false
    }

    /**
     * Dumps the previous tick's outgoing packets as one line, e.g.
     * `packets: SetCarriedItem *UseItemOn *Swing MovePlayer ClientTickEnd`, with `*` marking the module's own
     * sends. This is the ground truth for every tick-window check - Grim groups packets between tick packets, and
     * on 1.21.2+ `CLIENT_TICK_END` counts as one only when the tick sent no movement, otherwise the movement
     * packet is the delimiter (`GrimProcessor.java:80-91`) - and the proof that three `UseItemOn` clicks really
     * are spread across three ticks.
     */
    private fun tracePreviousTick() {
        if (packetTrace.isEmpty()) return

        val names = ArrayList<String>(packetTrace.size)
        while (true) {
            names += packetTrace.poll() ?: break
        }
        if (!worthTracing(names)) return

        val line = names.joinToString(separator = " ", prefix = "packets: ")
        logger.info(line)
        chat(line, this)
    }

    /**
     * Only ticks that can actually trip a tick-window check are logged: one carrying at least two click packets,
     * whether both are ours or one is foreign. Everything else is the ordinary one-action tick and would drown the
     * interesting lines - and an ordinary tick is exactly what this module is supposed to produce.
     */
    private fun worthTracing(names: List<String>): Boolean = names.count(::isClickName) >= 2

    private fun isClickName(name: String): Boolean = when (name.removePrefix("*")) {
        "Attack", "Interact", "UseItemOn", "UseItem", "PlayerAction" -> true
        else -> false
    }

    /** Rate-limited `Debug` chat line; the message is built lazily, so `Debug` off costs one boolean. */
    private inline fun debugLog(message: () -> String) {
        if (debug.enabled && logTimer.hasAtLeastElapsed(LOG_INTERVAL_MS)) {
            logTimer.reset()
            chat(message(), this)
        }
    }

    /** Unthrottled `Debug` line, for one-shot verdicts that a per-tick state line must not swallow. */
    private fun debugLine(message: String) {
        if (debug.enabled) {
            chat(message, this)
        }
    }

    /**
     * Forgets everything, hands a [SwitchMode.NORMAL] switch back and clears [AuraRotation]'s cached entity. The
     * ledger's dud latch goes with it, which is the point of calling this on a world change.
     */
    private fun resetState() {
        restoreNormalSlot()
        ledger.reset()
        intake.reset()
        job.reset()
        detonated.clear()
        // A new world is a new tick window; without this the modules reading it keep yielding to a burst that
        // happened before the disconnect for the rest of `ACTING_MS`.
        lastActionMs = 0L
        plan = null
        target = null
        scored = null
        lastView = null
        search = null
        rayIgnore = ignoreNone
        tick = 0
        rotatingTick = NEVER
        foreignUseSeen = false
        foreignDigSeen = false
        foreignEntitySeen = false
        packetTrace.clear()
        logTimer.reset(0)
        visuals = null
        AnchorRenderer.reset()
        AuraRotation.reset()
    }

    private fun rayIgnoreFor(mode: LookThrough): (Entity) -> Boolean = when (mode) {
        LookThrough.OFF -> ignoreNone
        LookThrough.ALL -> ignoreAll
    }

    private const val DAMAGE_FONT_NAME = "SF Pro Rounded Bold"

    /**
     * Ticks added to the round-trip estimate before a blast's shadow lifts (`AnchorLedger.isBlastShadowed`).
     *
     * The danger window runs from OUR detonating click to the moment the resulting block updates land, which is
     * one round trip; the margin covers the server's own tick boundary and the jitter in `player.ping`.
     */
    /** [rotatingTick] before the module has ever asked for a rotation; see [isRotating]. */
    private const val NEVER = Int.MIN_VALUE

    private const val BLAST_SHADOW_MARGIN_TICKS = 2

    private const val MS_PER_TICK = 50.0

    /** Half the round trip: the blast happens when the server processes our click ([predictTicks]). */
    private const val PREDICT_FACTOR = 0.5

    /** `ping + 2` ticks, the shape the crystal ledger uses for a chain-removed crystal ([detonateTimeout]). */
    private const val DETONATE_TIMEOUT_EXTRA = 2

    /**
     * The fixed part of [cycleBudget]. A cycle is three interactions at one per tick, so three is the floor and the
     * rest is slack for re-validation misses (the ray no longer lands, the slot was not free, the tick already
     * carried a click). One second: past that the world has changed enough that re-scanning beats waiting.
     * Abandoning is free - a use has no ABORT packet, and an abandoned cycle leaves a standing anchor worth more
     * than it cost - which is what makes a finite budget safe. It is the old `Cycle.MaxTicks` default.
     */
    private const val CYCLE_BASE_TICKS = 20

    /** `PendingPlacements`' own 2 s give-up, in ticks: the longest a cycle can usefully wait for an ack. */
    private const val MAX_WAIT_TICKS = 40

    /**
     * Ticks a cell whose detonation produced no observable explosion stays excluded from the search.
     *
     * A dud is what a dimension where anchors do NOT explode looks like from the client: `canSetSpawn` is read
     * positionally on a `ServerLevel` (`RespawnAnchorBlock.java:172-174`) and the client can only consult the
     * DIMENSION-level `RESPAWN_ANCHOR_WORKS` value, which a server may set per position or simply lie about. Five
     * seconds is generous on purpose: this is a backstop for a wrong reading, not a latency budget. The positive
     * signal is exact when it comes - `ClientboundExplodePacket` carries the centre, and
     * `badRespawnPointExplosion(boomPos)` puts that same centre in the damage source
     * (`net/minecraft/world/damagesource/DamageSources.java:278-280`) - and one arriving late retracts the verdict.
     * Was `Cycle.DudTicks` until 2026-09-26.
     */
    private const val DUD_TICKS = 100

    /**
     * Consecutive duds that stand detonation down until the world changes. Two, not one: a single dud is explained
     * by a lost packet or by a block broken under us between the click and the blast; two in a row is systematic,
     * and looping on it is useless and loud. Was `Cycle.DudLimit` until 2026-09-26.
     */
    private const val DUD_LIMIT = 2

    /**
     * How long after another module's block interaction this one keeps standing down.
     *
     * 200 ms is four client ticks - longer than the two-tick cycle `ModuleCrystalAura` runs at its default delays,
     * so a crystal fight keeps the tick for itself, which is the intended precedence: crystals cycle faster and an
     * anchor cycle that loses a tick mid-way costs nothing to abandon.
     */
    private const val YIELD_MS = 200L

    /** How long after a sent interaction the module still counts as working; see [isActing]. Five ticks. */
    private const val ACTING_MS = 250L

    /** Minimum gap between two `Debug` state lines. */
    private const val LOG_INTERVAL_MS = 500L
}
