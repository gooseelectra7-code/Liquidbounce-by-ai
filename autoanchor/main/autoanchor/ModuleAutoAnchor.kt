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
import net.ccbluex.liquidbounce.utils.world.respawnAnchorWorks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
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
 *   explosion and latches after `Cycle.DudLimit` of them. [consumeLatchNotice] prints the verdict once.
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
    val cycle = tree(CycleSettings())
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
    private val job = AnchorJob(anchor, cycle, AnchorExecutor(anchor))

    /** Client ticks since the last reset (the ledger's clock). */
    private var tick = 0

    /**
     * The victim this tick's cycle is aimed at, or null when the module has nothing to do. Published for the same
     * reason `ModuleCrystalAura.target` is: another module can align its own target with ours.
     */
    var target: LivingEntity? = null
        private set

    /**
     * Whether this module is steering the view right now, i.e. it asked `RotationManager` for a rotation in this
     * tick or the one before. The one-tick grace matches the fact that a rotation requested in PLAN only reaches
     * the wire at the end of that tick.
     */
    val isRotating: Boolean
        get() = running && tick - rotatingTick <= 1

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

    private var rotatingTick = Int.MIN_VALUE

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

    /** Foreign click packets seen in this tick, reset in PLAN and read in EXECUTE ([observeOutgoing]). */
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
        // The ledger stamps everything with its own clock, so it has to advance BEFORE the drain, or the drained
        // packets are dated one tick early and every timeout they touch is one tick short.
        ledger.onTickStart(
            tick, detonateTimeout(pingTicks), cycle.dudTicks, cycle.dudLimit,
            blastShadowTicks = pingTicks + BLAST_SHADOW_MARGIN_TICKS,
        )
        intake.drainInto(ledger)
        confirmDetonations()
        tracePreviousTick()
        foreignUseSeen = false
        foreignDigSeen = false
        foreignEntitySeen = false
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
            slack = AuraRotation.slackDegrees(rotation),
            rayIgnore = ignore,
        )
        plan = decided
        target = decided?.entity as? LivingEntity
        selectSlot(decided)
        requestRotation(decided)
        visuals = buildVisuals(view, decided)
        debugLog { "${job.describe()} | ${decided?.move ?: "-"}" }
    }

    /** The EXECUTE step (class KDoc); a plan is executed at most once and performs at most one interaction. */
    private fun executeTick() {
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
                AnchorRenderer.recordBlast(AnchorMath.anchorCenter(current.cell))
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
        // A totem swap of [ModuleAutoTotem]'s is in flight and every move of ours consumes an inventory slot
        // server-side, which bumps the open menu's state id and turns that click into a full resync - the ghost
        // totem (`ModuleAutoTotem.isSwapping`, `utils/inventory/OffhandSyncTracker.kt`). Stand-down, not just a
        // click hold: the hotbar switch is a packet of ours too, and it would sit between `InventoryManager`'s
        // inventory open and its click.
        ModuleAutoTotem.isSwapping -> "AutoTotem has a totem swap in flight"
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
     */
    private fun ammo(): AnchorAmmo {
        val mode = anchor.switchMode
        return AnchorAmmo(
            canPlace = hotbarSlotFor(Items.RESPAWN_ANCHOR, mode) != null,
            canCharge = anchor.recharge && hotbarSlotFor(Items.GLOWSTONE, mode) != null,
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

    /** `Cycle.DetonateTimeout`, or `ping + 2` ticks when it is left at `0`. */
    private fun detonateTimeout(pingTicks: Int): Int =
        if (cycle.detonateTimeout > 0) cycle.detonateTimeout else pingTicks + DETONATE_TIMEOUT_EXTRA

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
            maxPhaseTicks = cycle.maxTicks,
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
        // restarts on the same candidate every `Cycle.MaxTicks`. So: no engagement, no hold - the manager releases
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
        if (ownSend) return

        when (packet) {
            is ServerboundUseItemOnPacket, is ServerboundUseItemPacket -> foreignUseSeen = true
            is ServerboundAttackPacket, is ServerboundInteractPacket -> foreignEntitySeen = true
            is ServerboundPlayerActionPacket -> if (isDigAction(packet.action)) foreignDigSeen = true
            else -> {}
        }
    }

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
        plan = null
        target = null
        scored = null
        lastView = null
        search = null
        rayIgnore = ignoreNone
        tick = 0
        rotatingTick = Int.MIN_VALUE
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
    private const val BLAST_SHADOW_MARGIN_TICKS = 2

    private const val MS_PER_TICK = 50.0

    /** Half the round trip: the blast happens when the server processes our click ([predictTicks]). */
    private const val PREDICT_FACTOR = 0.5

    /** `ping + 2` ticks, the shape the crystal ledger uses for a chain-removed crystal ([detonateTimeout]). */
    private const val DETONATE_TIMEOUT_EXTRA = 2

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
