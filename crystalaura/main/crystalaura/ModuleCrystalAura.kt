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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura

import net.ccbluex.liquidbounce.event.events.CancelBlockBreakingEvent
import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldEntityRemoveEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoTotem
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base.BasePlaceSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base.BasePlan
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.debug.AuraDebug
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.exec.ActionExecutor
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.exec.TickPlan
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.exec.TickPlanner
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine.MineJob
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine.MinePlan
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine.MineSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine.MineTarget
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.net.PacketIntake
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.render.AuraRenderer
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.render.AuraTargetRenderer
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.render.AuraVisuals
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.BreakCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.BreakSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.render.FontFace
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.FloatValueProvider
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.Items
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs

/**
 * CrystalAura - places end crystals next to a target and attacks them, driven by an exact client-side replica
 * of the 26.1.2 explosion damage model (original module; not derived from the existing crystal modules).
 *
 * ### Design
 * One PLAN step per client tick and one EXECUTE step per client tick, at the two positions vanilla itself decides
 * and clicks:
 * - PLAN at tick HEAD (`RotationUpdateEvent`, fired by `RotationManager`'s `GameTickEvent` handler right before
 *   `RotationManager.update()`, `RotationManager.kt:319-324`; `GameTickEvent` is the HEAD of `Minecraft.tick`,
 *   `MixinMinecraft.java:291`): drain the packets queued by the netty thread into the [CrystalLedger], capture
 *   a [WorldView], run `BreakSearch` / `PlaceSearch` / `BasePlaceSearch`, let the [TickPlanner] pick the candidates
 *   and the look point against the rotation on the wire, switch the hotbar slot for a use offered this tick, and
 *   file the rotation request - which `RotationManager.update()`, called right after `RotationUpdateEvent`
 *   (`RotationManager.kt:319-324`), turns into this tick's outgoing rotation.
 * - EXECUTE at the RETURN of `Minecraft.handleKeybinds` (`InputHandleEvent`, `MixinMinecraft.java:333`), where
 *   vanilla has just processed the player's own clicks (`Minecraft.java:2050-2056`): the [ActionExecutor]
 *   re-verifies and sends. Between the two, `gameMode.tick()` has emitted
 *   the carried-item change for the slot chosen in PLAN (`MultiPlayerGameMode.tick`, `MultiPlayerGameMode.java:
 *   297-298` → `ensureHasSentCarriedItem`, `:311-317`, which reads `SilentHotbar.serversideSlot` through
 *   `MixinMultiPlayerGameMode.hookCustomSelectedSlot`), exactly where a real slot change appears on the wire.
 *   `SilentHotbar`'s own expiry handler runs at priority 1001, before `RotationManager`'s 1000
 *   (`EventHookRegistry`: descending priority), so a slot selected in PLAN is never reset in the same tick.
 * - After EXECUTE the tick continues with `level.tickEntities()` → `LocalPlayer.tick` → ONE movement packet
 *   carrying the rotation `RotationManager.update()` computed in PLAN, then `ServerboundClientTickEndPacket`
 *   (`Minecraft.java:1823-1927`). Actions are therefore judged against `AuraRotation.outgoing` - the rotation that
 *   very movement packet will carry, already final at EXECUTE - and a rotation requested in PLAN serves the action
 *   of the SAME tick. This is what vanilla does (one rotation per `Minecraft.tick()`, the movement packet sent
 *   after the click, `client/MouseHandler.java:334-336` + `client/Minecraft.java:1301, 1321, 1823, 1865`) and what
 *   Grim evaluates (interactions deferred to the flying packet, `CheckManagerListener.java:680-681`,
 *   `scaffolding/RotationPlace.java:52`). [AuraRotation.wire] stays the reference for what the server ALREADY has.
 *
 * ### Packet sequences produced (one tick each; `[SCI]` SetCarriedItem, `[ATK]` Attack, `[SW]` Swing,
 * `[UIO]` UseItemOn, `[UI]` UseItem, `[MOVE]` MovePlayer, `[TE]` ClientTickEnd; `m` / `o` = main / off hand)
 * - attack:                            `[ATK][SW][MOVE][TE]`
 * - place, crystal already held:       `[UIO][SW][MOVE][TE]`
 * - place after a slot switch:         `[SCI][UIO][SW][MOVE][TE]` (SCI from `gameMode.tick()`)
 * - place from the offhand:            `[UIO m][UIO o][SW o][MOVE][TE]`, with a plain item in the main hand
 *                                      `[UIO m][UI m][UIO o][SW o][MOVE][TE]` (`Minecraft.startUseItem:1743-1800`)
 * - attack + place in one tick:        `[SCI]?[ATK][SW][UIO][SW][MOVE][TE]` - the ONE known multi-action: a real
 *                                      client produces it only with both buttons clicked in the same tick, in this
 *                                      order (`Minecraft.handleKeybinds`, attack clicks before use clicks)
 * - IdPredict phantom attack:          `[ATK id][SW][MOVE][TE]` for an id whose spawn packet is still in flight
 * - base placement (obsidian):         `[SCI]?[UIO][SW][MOVE][TE]`
 * - silent swap back:                  `[SCI back][MOVE][TE]` on the first tick without a use (SwapBack = 0), sent
 *                                      by `gameMode.tick()` after `SilentHotbar` expired the selection
 * - a re-aim + its action share one tick: `[SCI]?[UIO][SW][MOVE(new rot)][TE]`. The click precedes the `[MOVE]` that
 *   carries the rotation it was aimed with, exactly as in vanilla; the `[SCI]` stays ahead of the click because
 *   `gameMode.tick()` emits it, and it must - a `HELD_ITEM_CHANGE` between the click and the flying packet would
 *   make Grim drain its queue early with `hasLook = false` (`PacketPlayerDigging.java:109`,
 *   `GrimPlayer.java:444-445`).
 * - when the exact ray misses the outgoing rotation (Dither noise), NOTHING is sent that tick and the next one
 *   retries with the rotation by then on the wire - the module's behaviour before the reaction fix, kept as the
 *   fallback.
 *
 * ### Manual swing (recorded decision)
 * When the client-side `EndCrystalItem.useOn` fails only because a crystal we attacked this tick (or expect to be
 * chain-removed) still exists client-side, the `UseItemOn` packet has already gone out and the server places the
 * crystal; the hand is then swung manually so the sequence equals a legit client whose removal packet had arrived
 * (`ActionExecutor`).
 *
 * ### Bookkeeping
 * Incoming packets are queued on the netty thread ([PacketIntake]) and drained in PLAN, right after the ledger
 * advanced its clock so the drained packets carry the current tick; entity removals are also taken from
 * `WorldEntityRemoveEvent`. All state belongs to exactly one [ClientLevel]: PLAN compares `mc.level` by identity
 * against a weakly held reference and resets on a mismatch ([guardLevel]) - the `WorldChangeEvent` handler alone
 * cannot carry that, it is `running`-gated and misses both the disconnect and the login. Timeouts: attacks retry
 * after `RetryTicks` (0 = ping + 2 ticks), pending placements expire after ping + 1 ticks, predictions extrapolate
 * targets by `Predict` (AUTO: place `round(1.5 * ping) + 1`, break `round(0.5 * ping)` ticks).
 *
 * ### When PLAN idles or EXECUTE holds back
 * - A screen or overlay makes vanilla skip `handleKeybinds` entirely (`Minecraft.java:1863-1869`) and with it the
 *   EXECUTE step, so nothing planned could ever be clicked: PLAN drops the plan, gives the silent slot back and
 *   requests no rotation while one is open.
 * - `InputHandleEvent` fires at the RETURN of `handleKeybinds`, i.e. after vanilla ran its own attack loop, use
 *   loop and `continueAttack` (`Minecraft.java:2041-2073`). When the player used an item or dug a block in the same
 *   tick, appending our click behind theirs would produce an order no vanilla client emits, so the offending action
 *   is suppressed for that tick ([executeTick]).
 */
/*
 * LargeClass: this object is the hub and deliberately holds no logic - the searches, the planner, the executor,
 * the miner and the renderers are separate classes. What is left here is the settings tree, the event handlers and
 * the per-tick wiring between them, and that is exactly the part that cannot be moved out without inventing a
 * second object to hold the same state.
 */
@Suppress("TooManyFunctions", "LargeClass")
object ModuleCrystalAura : ClientModule("CrystalAura", ModuleCategories.COMBAT) {

    val place = tree(PlaceSettings())
    val brk = tree(BreakSettings())
    val rotation = tree(RotationSettings())
    val basePlace = tree(BasePlaceSettings(this))

    /**
     * `Range` is registered INSIDE the tracker's own group through [FloatValueProvider]
     * (`utils/client/RangedValueProvider.kt:58-70`), so every target setting sits under one "Target" node instead
     * of leaving a loose slider on the module.
     */
    val targetTracker = tree(TargetTracker(TargetPriority.HEALTH, FloatValueProvider("Range", 10f, 1f..16f)))

    val render = tree(RenderSettings())

    /** Chat cross-check of our damage model against vanilla's own code; diagnostics only, see `debug/AuraDebug`. */
    val debug by boolean("Debug", false)

    /** LookThrough filters for the pick rays (`PlaceSettings.lookThrough`). */
    private val ignoreNone: (Entity) -> Boolean = { false }
    private val ignoreCrystals: (Entity) -> Boolean = { it is EndCrystal }
    private val ignoreAll: (Entity) -> Boolean = { true }

    private val intake = PacketIntake()
    private val ledger = CrystalLedger()
    private val breakChrono = Chronometer(0)
    private val placeChrono = Chronometer(0)
    private val planner = TickPlanner(place, brk, rotation, basePlace)
    private val executor = ActionExecutor(place, brk, basePlace)

    /**
     * The AutoMine job (`mine/MineJob.kt`): it is consulted in PLAN BEFORE the aura's own plan is filed, and a
     * tick it answers carries a dig packet and therefore nothing else - no attack, no use, and the rotation and
     * the hotbar slot belong to it (`PacketOrderI.java:81-96, :98-123, :134-149, :195-210`,
     * `MultiActionsF.java:78-92`).
     */
    private val miner = MineJob(basePlace.mine, place)

    /** Client ticks since the last reset (the ledger's clock). */
    private var tick = 0

    /** Consecutive ticks the player has not moved, gating the `SameTick` safe mode ([sameTickAllowed]). */
    private var calmTicks = 0

    /**
     * The face the "Detonation" damage text is drawn with, or `null` when it is unavailable - then `AuraRenderer`
     * keeps its `SystemDraw` path. `SF Pro Rounded Bold` is `round.otf` of the competitor client
     * (`rockstar-client-src/src/main/resources/assets/rockstar/fonts/round.otf`, `java.awt.Font.getName()` verified).
     *
     * The OTFs are baked by `FontManager.loadSfProFamily()` BEFORE `createGlyphManager()` (`LiquidBounce.kt:424-427`)
     * because `availableFonts` is a startup snapshot: a face registered later is served another face's glyphs
     * (`render/engine/font/FontGlyphPageManager.kt:100-114`), i.e. it would silently draw in the wrong typeface.
     */
    val damageFont: FontFace?
        get() = FontManager.fontFace(DAMAGE_FONT_NAME)

    /** Plan of the current tick, consumed by [executeTick]; [visuals] keeps the last plan's render state. */
    /**
     * The victim this tick's plan is built around, or null when the aura has nothing to do.
     *
     * Published for the modules that used to align with the old crystal aura — `ModuleAutoCity` picks its city
     * target from it so both hit the same player. Written in PLAN, cleared whenever the aura goes idle.
     */
    var target: LivingEntity? = null
        private set

    /**
     * Whether the aura is steering the view right now, i.e. it asked `RotationManager` for a rotation in this tick
     * or the one before.
     *
     * `ModuleKillAura` and `ModuleElytraTarget` read this to keep their own aim out of the way; the one-tick grace
     * matches the fact that a rotation requested in PLAN only reaches the wire at the end of that tick.
     */
    val isRotating: Boolean
        get() = running && tick - rotatingTick <= 1

    /** Tick of the last rotation request or hold; see [isRotating]. */
    private var rotatingTick = Int.MIN_VALUE

    /**
     * Wall clock of the last packet this module actually sent (attack, placement or dig), or `0` when it has sent
     * nothing yet.
     *
     * `ModuleSpeedMine` reads it to keep its own dig packets out of the tick window the aura just acted in:
     * `PacketOrderI` flags a dig that shares a tick with a place, use or attack
     * (`checks/impl/packetorder/PacketOrderI.java:98-123, 195-210`) and the two modules' handler order is
     * undefined, so a wall-clock window is the only thing either of them can agree on. Milliseconds, not ticks,
     * for exactly that reason.
     */
    @Volatile
    var lastActionMs: Long = 0L
        private set

    /**
     * Whether this module is WORKING right now - not merely enabled, and not merely aiming.
     *
     * This is the signal the aura chain runs on (KillAura < AutoAnchor < CrystalAura). [isRotating] is the wrong
     * one for it and was tried first: `requestRotation` holds the aim whenever `breakBest != null`, i.e. whenever
     * any crystal worth breaking sits in range, which in a crystal fight is nearly always - so the modules below
     * stood down for a module that was merely SWITCHED ON. The user's rule is the other one: while an aura works,
     * the ones below wait; while it only stands there, they run.
     *
     * Two terms, both meaning "a cycle is in progress":
     * - a packet went out less than [ACTING_MS] ago. The aura acts every tick or two while it has work, so the
     *   window bridges the gaps inside one burst and expires a few ticks after the burst ends.
     * - a placement of ours is still in flight ([CrystalLedger.pendingPlacement]) - the crystal has been placed
     *   and not yet resolved, so the cycle is mid-air even on a tick that sends nothing.
     */
    val isActing: Boolean
        get() = running &&
            (System.currentTimeMillis() - lastActionMs < ACTING_MS || ledger.pendingPlacement() != null)

    /**
     * Whether vanilla's own explosion effect for a blast of [radius] should be dropped - the flash, the boom and
     * the block debris of `ClientPacketListener.handleExplosion`, and nothing else
     * (`RenderSettings.explosionEffect`, where the whole rationale lives).
     *
     * Read from `MixinClientPacketListener` on the packet thread's dispatch to the main thread, so it stays a
     * pure function of two immutable reads and touches no module state.
     *
     * The radius test is what keeps the setting honest about its name: an end crystal always explodes at
     * [ExplosionMath.POWER], so a TNT blast (4.0) or a respawn anchor (5.0) is never touched by a crystal aura's
     * setting. Compared with a tolerance because the value is a float that has been through the wire.
     */
    fun hidesExplosionEffect(radius: Float): Boolean =
        running && !render.explosionEffect && abs(radius - ExplosionMath.POWER) < EXPLOSION_RADIUS_EPSILON

    private var plan: TickPlan? = null

    /** Dig of the current tick, consumed by [executeTick]. Non-null exactly when [plan] is null and mining owns it. */
    private var minePlan: MinePlan? = null
    private var visuals: AuraVisuals? = null
    private var lastView: WorldView? = null
    private var breakSearch: BreakSearch? = null
    private var rayIgnore: (Entity) -> Boolean = ignoreNone

    /** Ticks of the most recent attacks (ring buffer) for [tag]. */
    private val attackTicks = IntArray(ATTACK_RING) { Int.MIN_VALUE }
    private var attackCursor = 0

    /** The level the current state belongs to; weak, so a level we saw is never kept alive by us ([guardLevel]). */
    private var levelRef: WeakReference<ClientLevel>? = null

    /** Foreign click packets seen in this tick, reset in PLAN and read in EXECUTE ([observeOutgoing]). */
    private var foreignUseSeen = false
    private var foreignDigSeen = false
    private var foreignEntitySeen = false

    /**
     * Every outgoing packet of the current tick, in order, recorded only while `Debug` is on; entries the module
     * itself sent are prefixed with `*`. Printed at the head of the next PLAN, so one line carries exactly one
     * client tick - which is the unit Grim's tick-window checks reason in (`GrimProcessor.java:80-91`).
     */
    private val packetTrace = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** True while [executeTick] runs, so [observeOutgoing] can tell the module's own packets from foreign ones. */
    @Volatile
    private var ownSend = false

    /**
     * Every `ServerboundAttackPacket` seen at the HEAD of `Connection.send` (`MixinConnection.java:52-54`), ours
     * included; [executeTick] brackets `ActionExecutor.execute` with it.
     */
    private var attackPackets = 0

    /** Hotbar slot to give back after a [SwitchMode.NORMAL] switch, and the slot we forced ([restoreNormalSlot]). */
    private var restoreSlot: Int? = null
    private var forcedSlot: Int? = null

    /** Crystals still present client-side but gone (or about to be) server-side; only those may be placed over. */
    private val columnIgnore: (Entity) -> Boolean = { entity ->
        entity is EndCrystal && (ledger.isAttacked(entity.id) || ledger.isExpectedRemoved(entity.id))
    }

    /** Attacks in the last [TAG_WINDOW_TICKS] ticks, i.e. per second. */
    override val tag: String?
        get() = if (running) String.format(Locale.ROOT, "%.1f/s", recentAttacks().toFloat()) else null

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.INCOMING) {
            intake.offer(event.packet)
        } else {
            observeOutgoing(event.packet)
        }
    }

    @Suppress("unused")
    private val entityRemoveHandler = handler<WorldEntityRemoveEvent> { event ->
        ledger.onRemoved(event.entity.id)
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
    private val executeHandler = handler<InputHandleEvent> {
        executeTick()
    }

    /**
     * Keeps vanilla from tearing our dig down between two of its own swings: the last statement of
     * `handleKeybinds` is `continueAttack(...)` (`net/minecraft/client/Minecraft.java:2073`) and, whenever the
     * attack key is not held on a block, that ends in `gameMode.stopDestroyBlock()` (`:1666-1668`) - which fires
     * `CancelBlockBreakingEvent` at its HEAD (`MixinMultiPlayerGameMode.java:85-93`). Same shape as
     * `ModuleAutoMine.kt:306-308`. The job clears the flag around its own abort, so that one goes through.
     */
    @Suppress("unused")
    private val cancelBreakingHandler = handler<CancelBlockBreakingEvent> { event ->
        if (miner.cancelsBreaking) event.cancelEvent()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        AuraRenderer.render(event, visuals, render)
    }

    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        AuraRenderer.renderOverlay(event, render)
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
        val attackTimeout = if (brk.retryTicks > 0) brk.retryTicks else pingTicks + ATTACK_TIMEOUT_EXTRA
        // The ledger stamps everything with its own clock, so it has to advance BEFORE the drain, or the drained
        // packets are dated one tick early and every timeout they touch is one tick short (`PacketIntake.drainInto`).
        ledger.onTickStart(tick, attackTimeout, pingTicks + PLACE_TIMEOUT_EXTRA)
        intake.drainInto(ledger, player.id)
        tracePreviousTick()
        foreignUseSeen = false
        foreignDigSeen = false
        foreignEntitySeen = false

        val reason = standDownReason()
        if (reason != null) {
            standDown()
            AuraDebug.log(this, debug) { "stand down: $reason" }
            return
        }

        val view = captureView(pingTicks)
        lastView = view
        if (view.targets.isEmpty()) {
            plan = null
            target = null
            minePlan = null
            miner.reset()
            visuals = null
            restoreNormalSlot()
            return
        }

        val ignore = rayIgnoreFor(place.lookThrough)
        rayIgnore = ignore
        val canAttack = breakChrono.hasAtLeastElapsed(brk.delay.toLong())
        val searches = searchTick(view, canAttack)
        val decided = planner.decide(
            view = view,
            ledger = ledger,
            breakBest = searches.breakBest,
            placeCandidates = searches.placeCandidates,
            basePlan = searches.basePlan,
            wire = searches.wire,
            canAttack = canAttack,
            canUse = placeChrono.hasAtLeastElapsed(place.delay.toLong()),
            sameTick = sameTickAllowed(),
            crystalSlot = crystalSlot(),
            obsidianSlot = Slots.Hotbar.findSlot(Items.OBSIDIAN),
            rayIgnore = ignore,
        )
        val mine = mineTick(view, searches, decided, ignore)
        plan = if (mine == null) decided else null
        target = decided.place?.target?.entity ?: decided.attack?.target?.entity
        minePlan = mine
        visuals = buildVisuals(decided)
        if (mine == null) {
            selectSlot(decided)
            requestRotation(decided, searches.breakBest)
        } else {
            selectMineSlot(mine.slot)
            requestMineRotation(mine)
        }
        debugPlan(decided, mine)
    }

    /**
     * Consults the AutoMine job. A new target is only searched for when nothing is running AND the aura offered
     * nothing this tick - digging must never displace an action that is ready now - and the job itself refuses to
     * start against an offer as well (the job re-checks it itself).
     *
     * The search's MinGain floor is the best placement available WITHOUT digging: the head of the placement
     * ranking, or, when `BasePlace` found a plan this tick, whichever of that plan's candidate and the direct one
     * is better ([bestWithoutMining]) - the reference `MineSearch.best` documents.
     */
    private fun mineTick(
        view: WorldView,
        searches: Searches,
        decided: TickPlan,
        ignore: (Entity) -> Boolean,
    ): MinePlan? {
        if (!basePlace.mine.running) {
            if (miner.active) miner.reset()
            return null
        }
        val target = if (miner.active || decided.attempting) null else mineTarget(view, searches)
        return miner.plan(view, target, decided.attempting, AuraRotation.slackDegrees(rotation), ignore)
    }

    private fun mineTarget(view: WorldView, searches: Searches): MineTarget? = MineSearch(
        view = view,
        placeSearch = searches.placeSearch,
        settings = basePlace.mine,
        baseSettings = basePlace,
        wire = searches.wire,
        rayIgnore = rayIgnore,
        slackDeg = AuraRotation.slackDegrees(rotation),
        throughBlocks = place.throughBlocks,
    ).best(bestWithoutMining(searches))

    /** The best placement this tick offers without breaking anything: the base plan's candidate or the direct one. */
    private fun bestWithoutMining(searches: Searches): PlaceCandidate? {
        val direct = searches.placeBest
        val base = searches.basePlan?.candidate ?: return direct

        return if (direct != null && direct.damage.effective >= base.damage.effective) direct else base
    }

    /**
     * Brings the mining tool into the hand at the tick HEAD, so `gameMode.tick()` emits its `SetCarriedItem` before
     * the dig - which `continueDestroyBlock` would otherwise emit itself, from its own `ensureHasSentCarriedItem()`
     * (`MultiPlayerGameMode.java:224`), i.e. still ahead of the `START` but not at the vanilla position.
     * [SwitchMode.NONE] and creative hand out no slot at all (`MineJob.toolFor`) and the dig runs with whatever is
     * held. The silent selection is renewed every tick of the dig and expires [TOOL_HOLD_TICKS] ticks after the
     * job stops asking.
     */
    private fun selectMineSlot(slot: HotbarItemSlot?) {
        val index = slot?.hotbarIndex
        if (index == null) {
            restoreNormalSlot()
            return
        }

        when (basePlace.mine.tool) {
            SwitchMode.SILENT -> SilentHotbar.selectSlotSilently(this, index, TOOL_HOLD_TICKS)
            SwitchMode.NORMAL -> normalSwitch(index)
            SwitchMode.NONE -> restoreNormalSlot()
        }
    }

    /**
     * The dig's rotation request, filed exactly where the aura files its own ([requestRotation]) so that THIS
     * tick's movement packet carries it - which is the rotation `RotationBreak` raytraces the block from
     * (`checks/impl/breaking/RotationBreak.java:56-75`). The victim the mined base was scored against is passed as
     * the reset-detection entity, so a `SMOOTH` step towards the cell is not restarted every tick.
     */
    private fun requestMineRotation(mine: MinePlan) {
        val look = mine.look
        if (look != null) {
            AuraRotation.request(this, look.rotation, mine.entity, rotation)
        } else {
            AuraRotation.hold(this, rotation)
        }
    }

    /**
     * The render snapshot of the decided plan, built once per PLAN tick and read by the render handlers between
     * ticks. The placement wins over the attack (it is what the aura is aiming at), and the victim snapshot is the
     * one the winning candidate was scored against - `placeState` for a placement, `breakState` for an attack
     * (`world/WorldView.kt:66-71`).
     *
     * `AuraTargetRenderer.killZoneRadius` is a fixed 17-step arithmetic sweep, so it is evaluated here (once per
     * tick) instead of per frame. It is a pure function of the victim snapshot and `MinDamage`: no world access, no
     * rays, no effect on the plan.
     */
    private fun buildVisuals(plan: TickPlan): AuraVisuals {
        val candidate = plan.place
        val target = candidate?.target ?: plan.attack?.target
        val state = if (candidate != null) candidate.target.placeState else plan.attack?.target?.breakState
        val damage = candidate?.damage ?: plan.attack?.damage
        return AuraVisuals(
            target = target?.entity,
            targetState = state,
            killZoneRadius = if (state == null) 0f else AuraTargetRenderer.killZoneRadius(state, place.minDamage),
            bestDamage = damage?.effective ?: 0f,
            selfDamage = (candidate?.selfDamage ?: plan.attack?.selfDamage)?.effective ?: 0f,
            lethal = state != null && damage != null && damage.isLethalFor(state),
            placePos = plan.placePos,
            basePos = plan.basePos,
            attackBox = plan.attackBox,
        )
    }

    /**
     * `WorldView.capture` with the prediction and latency inputs of this tick. `AssumeResistance` is a 1-based
     * setting (0 = assume none) while the capture takes an amplifier, so it is passed one lower: 0 becomes `-1`
     * ("no effect"), 1 becomes amplifier 0 (Resistance I).
     */
    private fun captureView(pingTicks: Int): WorldView = WorldView.capture(
        tick = tick,
        targets = targetTracker.targets(),
        ledger = ledger,
        placePredictTicks = placePredictTicks(pingTicks),
        breakPredictTicks = breakPredictTicks(pingTicks),
        latencyTicks = pingTicks,
        assumeResistance = place.assumeResistance - 1,
        assumeArmor = place.assumeArmor,
    )

    /**
     * Resets everything when `mc.level` is no longer the level the state was built on. The `WorldChangeEvent`
     * handler cannot carry this alone: it is `running`-gated (`EventManager.kt:347` ->
     * `ClientModule.kt:80-81` -> `inGame`, which needs both a level and a player), while `WorldChangeEvent` is
     * fired at the HEAD of `updateLevelInEngines` (`MixinMinecraft.java:407-410`) - which `Minecraft.disconnect`
     * reaches only after nulling `level` (`Minecraft.java:2235` before `:2251`) and which
     * `ClientPacketListener.handleLogin` reaches through `setLevel` before assigning the player
     * (`ClientPacketListener.java:527` before `:529`). Both times `inGame` is false and the handler is skipped, so
     * stale `CrystalLedger` ids would leak into a server whose entity ids restart at 0.
     */
    private fun guardLevel() {
        val level = mc.level
        if (levelRef?.get() !== level) {
            resetState()
            levelRef = level?.let(::WeakReference)
        }
    }

    /**
     * Why the module may not act this tick, or null when both gates are open. Either verdict ends in [standDown].
     *
     * - **A screen or overlay** makes vanilla skip `handleKeybinds` (`Minecraft.java:1863-1869`) and with it
     *   EXECUTE, so a plan made now could never be clicked. Drop it and give the hand back instead of rotating the
     *   player server-side and holding a slot for a whole inventory session.
     * - **[ModuleAutoTotem] has a totem swap in flight** (`ModuleAutoTotem.isSwapping`, added 2026-09-13 on the
     *   user's report of ghosted totems). Every interaction this module sends consumes an inventory slot
     *   server-side - a crystal, an obsidian, a mined block - and the server bumps the open menu's state id for
     *   it. The totem's own container click still carries the state id from before that, so
     *   `handleContainerClick` answers it with a full `ClientboundContainerSetContentPacket` resync
     *   (`ServerGamePacketListenerImpl.java:1915-1916`) which overwrites the predicted offhand: the totem is
     *   drawn in a hand the server does not have, and it does not pop. The module stands ALL the way down for the
     *   window rather than only holding its click, because a `SetCarriedItem` of ours would otherwise still sit
     *   between `InventoryManager`'s inventory open and its click - a packet shape vanilla, which cannot touch
     *   the hotbar while its own menu is up, never produces. The window is a few ticks, and `HandSwapGate` covers
     *   only the middle one of its three phases.
     */
    private fun standDownReason(): String? = when {
        mc.screen != null || mc.overlay != null -> "a screen is open, so vanilla skips handleKeybinds and EXECUTE"
        ModuleAutoTotem.isSwapping -> "AutoTotem has a totem swap in flight"
        else -> null
    }

    /** Gives the tick back whole: no plan, no dig job, no slot of ours and no aim. */
    private fun standDown() {
        plan = null
        minePlan = null
        miner.reset()
        visuals = null
        restoreNormalSlot()
        SilentHotbar.resetSlot(this)
    }

    /**
     * The end crystal slot. A HOTBAR slot beats the offhand: with any other item in the main hand vanilla's hand
     * loop consumes the use with that item first, so `ActionExecutor` rejects the offhand placement outright, while
     * a hotbar crystal is one `SetCarriedItem` away. `Slots.OffhandWithHotbar` cannot express that - `OFFHAND` is
     * the first enum constant (`utils/inventory/ItemSlot.kt:165-174`) and `findSlot` takes the first match
     * (`utils/inventory/Slots.kt:62-64`). With [SwitchMode.NONE] no switch may happen, so only the slot the server
     * already holds - or the offhand, which needs none - can actually be used.
     */
    private fun crystalSlot(): HotbarItemSlot? {
        val hotbar = Slots.Hotbar.findSlot(Items.END_CRYSTAL)
        val offhand = HotbarItemSlot.OFFHAND.takeIf { it.itemStack.`is`(Items.END_CRYSTAL) }
        if (place.switchMode != SwitchMode.NONE) {
            return hotbar ?: offhand
        }

        val held = hotbar?.takeIf { it.hotbarIndex == SilentHotbar.serversideSlot }
        return held ?: offhand ?: hotbar
    }

    /**
     * Records the click packets of this tick. No self-filter is needed: the module's own sends all happen in
     * [executeTick], which reads the flags before it sends anything, and PLAN clears them again at the head of the
     * next tick. Attacks are counted rather than flagged - [executeTick] brackets `ActionExecutor.execute` with the
     * counter to tell a real attack from one another module cancelled through `AttackEntityEvent`.
     */
    private fun observeOutgoing(packet: Packet<*>) {
        if (debug) {
            packetTrace += (if (ownSend) "*" else "") + packet.javaClass.simpleName
                .removePrefix("Serverbound").removeSuffix("Packet")
        }

        // Counted for OURS as well - [executeTick] brackets `ActionExecutor.execute` with it to tell a real attack
        // from one another module cancelled through `AttackEntityEvent`.
        if (packet is ServerboundAttackPacket) attackPackets++
        // Our own sends are not foreign clicks. The aura never needed the filter (it reads the flags before it
        // sends anything), but the AutoMine job emits dig packets from the same EXECUTE step and would otherwise
        // set `foreignDigSeen` for the rest of the tick and suppress itself on the next one.
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

    private fun placePredictTicks(pingTicks: Int): Int = when (place.predict) {
        PredictMode.OFF -> 0
        PredictMode.AUTO -> Math.round(PLACE_PREDICT_FACTOR * pingTicks).toInt() + 1
        PredictMode.FIXED -> place.predictTicks
    }

    private fun breakPredictTicks(pingTicks: Int): Int = when (place.predict) {
        PredictMode.OFF -> 0
        PredictMode.AUTO -> Math.round(BREAK_PREDICT_FACTOR * pingTicks).toInt()
        PredictMode.FIXED -> place.predictTicks
    }

    /**
     * Runs the three searches; the column filter goes to the placement searches, the LookThrough filter to rays.
     *
     * The placement search hands out its whole accepted ranking ([PlaceSearch.candidates]) so that the planner can
     * demote past a candidate it cannot use. Only the PRIMARY one is pulled here - that is the cost the single
     * `best()` had, and it is the candidate `BasePlaceSearch` needs: its MinGain floor is defined against the best
     * placement DIRECTLY available, not against whatever the planner ends up settling for. The tail stays lazy and
     * is walked by `TickPlanner.decide` alone.
     */
    private fun searchTick(view: WorldView, canAttack: Boolean): Searches {
        val calc = DamageCalculator(brk.iFrames == IFrameMode.RESPECT)
        val breaks = BreakSearch(view, calc, place, brk, ledger)
        breakSearch = breaks
        val breakBest = breaks.best()
        val columns = placeColumnIgnore(breakBest, canAttack)
        val placeSearch = PlaceSearch(view, calc, place, ledger, columns, brk.range)
        val places = placeSearch.candidates().iterator()
        val placeBest = if (places.hasNext()) places.next() else null
        val wire = AuraRotation.wire
        val basePlan = if (basePlace.running) {
            val slack = AuraRotation.slackDegrees(rotation)
            BasePlaceSearch(view, placeSearch, basePlace, columns, wire, slack, place.throughBlocks)
                .best(placeBest)
        } else {
            null
        }
        val candidates = if (placeBest == null) {
            emptySequence()
        } else {
            sequenceOf(placeBest) + places.asSequence()
        }
        return Searches(breakBest, candidates, basePlan, wire, placeSearch, placeBest)
    }

    /**
     * Column filter for the PLACEMENT searches: [columnIgnore] plus the crystals that this tick's own attack is
     * about to remove.
     *
     * Inside one tick the attack packet precedes the use packet (`Minecraft.handleKeybinds`,
     * `Minecraft.java:2050-2056`), and the server removes the crystal and runs its explosion while handling
     * that attack (`EndCrystal.hurtServer`, `EndCrystal.java:88-99`) - which also removes every other crystal
     * within the blast (`ServerExplosion.java:181-190`, again without exploding them). By the time the
     * placement is handled the whole column is therefore free, exactly
     * as [ActionExecutor] re-checks it after the attack has been marked.
     *
     * Without this the aura blocked its own best position for a full cycle: at PLAN time the crystal it was about to
     * break still counted as an obstruction, so the search moved to the next-best block, and moved back once the
     * removal was observed - the target visibly bounced between two blocks every crystal.
     *
     * Only applied when [canAttack] (the break delay is open); if the attack is then dropped anyway - its ray misses,
     * or another module cancels it - the planner suppresses the use with it, and the executor's own column re-check
     * (which reads the ledger, not this filter) refuses the placement rather than sending a doomed one.
     */
    private fun placeColumnIgnore(breakBest: BreakCandidate?, canAttack: Boolean): (Entity) -> Boolean {
        val dying = breakBest?.crystal?.takeIf { canAttack } ?: return columnIgnore
        val center = dying.position()

        return { entity ->
            columnIgnore(entity) ||
                (entity is EndCrystal && ExplosionMath.distanceFraction(entity.position(), center) <= 1.0)
        }
    }

    /**
     * Brings the item of a use that is OFFERED this tick into the hand now, so `gameMode.tick()` emits the single
     * `SetCarriedItem` at its vanilla position (class KDoc). The offhand needs no switch. When no use is planned
     * any more, a [SwitchMode.NORMAL] switch is handed back ([restoreNormalSlot]).
     *
     * It keys on the ATTEMPT, not on the hit: the hit is decided at `InputHandleEvent`, long after `gameMode.tick()`
     * had its only chance to emit the slot change. Waiting for the verdict would put the `SetCarriedItem` behind the
     * click, and a `HELD_ITEM_CHANGE` between an interaction and the flying packet makes Grim drain its queue early
     * with `hasLook = false` (`PacketPlayerDigging.java:109`, `GrimPlayer.java:444-445`) - i.e. it would forfeit the
     * same-tick rotation. A missed attempt costs one `SetCarriedItem` and nothing else.
     */
    private fun selectSlot(plan: TickPlan) {
        if (!plan.useAttempt) {
            restoreNormalSlot()
            return
        }
        val index = plan.slot?.hotbarIndex ?: return

        when (place.switchMode) {
            SwitchMode.SILENT -> SilentHotbar.selectSlotSilently(this, index, place.swapBack)
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
     * Gives a [SwitchMode.NORMAL] switch back. Nothing anywhere else restores `Inventory.selected`, so without this
     * the module would leave the player on the crystal slot for good.
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
     * Requests the plan's look point (with the victim as the reset-detection entity, see `AuraRotation`), or keeps
     * the current wire rotation alive while a cycle is in progress, or lets the rotation return otherwise.
     *
     * The request is filed BEFORE `RotationManager.update()` runs - the manager fires `RotationUpdateEvent` and
     * calls `update()` back to back (`RotationManager.kt:319-324`) - so it is this tick's movement packet that
     * carries it, and `AuraRotation.outgoing` already holds it when EXECUTE verifies the plan's attempts.
     */
    private fun requestRotation(plan: TickPlan, breakBest: BreakCandidate?) {
        val look = plan.look
        if (look != null) {
            val entity = plan.place?.target?.entity ?: plan.attack?.target?.entity
            AuraRotation.request(this, look.rotation, entity, rotation)
            rotatingTick = tick
        } else if (plan.attempting || ledger.pendingPlacement() != null || breakBest != null) {
            AuraRotation.hold(this, rotation)
            rotatingTick = tick
        }
    }

    private fun debugPlan(plan: TickPlan, mine: MinePlan?) {
        AuraDebug.log(this, debug) {
            if (mine == null) plan.describe() else miner.describe() + " " + mine.move.name + " | " + plan.describe()
        }
        if (!debug) return

        plan.attack?.let { AuraDebug.crossCheck(this, true, it.center, it.target, it.damage, forBreak = true) }
        plan.place?.let { AuraDebug.crossCheck(this, true, it.center, it.target, it.damage, forBreak = false) }
    }

    /** The EXECUTE step (class KDoc); a plan is executed at most once. */
    private fun executeTick() {
        val mine = minePlan
        minePlan = null
        if (mine != null) {
            executeMine(mine)
            return
        }

        val current = plan ?: return
        plan = null
        val view = lastView
        val breaks = breakSearch
        if (!current.attempting || view == null || breaks == null) return

        // Vanilla emits all attacks, then all uses, then `continueAttack` (`Minecraft.java:2041-2073`), and we are
        // behind all of them. So an attack may not follow a foreign use or dig, and a use may not follow a dig.
        // A hand swap stands the whole cycle down, the break included ([HandSwapGate]): the crystal we are about to
        // hit is only worth a click while the hand that follows it up is the one the server has.
        val allowAttack = !foreignUseSeen && !foreignDigSeen && !HandSwapGate.holdsAttacks
        // A block interaction must not follow an entity interaction inside one tick window either - that is
        // MultiActionsF (`MultiActionsF.java:21, 43-74`) and PacketOrderJ (`PacketOrderJ.java:12, 24`), and the
        // window is closed by our own move packet, which goes out AFTER the clicks. So a foreign attack this tick
        // (KillAura, the player's own left click) blocks our placement exactly like a foreign dig does.
        // A hand swap that went out this window takes the use with it: the offhand crystal may not be there
        // server-side yet, and the click would sit between the swap and its close packet ([HandSwapGate]). The gate
        // is read HERE rather than trusted to reject inside `interactBlock`, because `useItemOn` counts as spent
        // whatever it returns - a silent rejection down there would book a placement that never left.
        val allowUse = !foreignDigSeen && !foreignEntitySeen && !HandSwapGate.holdsUses
        val attacksBefore = attackPackets
        ownSend = true
        val report = try {
            executor.execute(current, view, ledger, breaks, rayIgnore, allowAttack, allowUse)
        } finally {
            ownSend = false
        }
        // `attackEntity` silently sends nothing when another module cancels `AttackEntityEvent`
        // (`utils/combat/CombatExtensions.kt:255-258`), yet the report still claims the attack; only the packet
        // counter can tell. The ledger's own mark needs no undo - it self-heals through the attack timeout.
        val cancelled = report.attacked && attackPackets == attacksBefore
        if (report.attacked || report.placed || report.basePlaced) {
            lastActionMs = System.currentTimeMillis()
        }
        if (report.attacked && !cancelled) {
            breakChrono.reset()
            recordAttack()
            recordShockwave(current)
        }
        if (report.placed || report.basePlaced) {
            placeChrono.reset()
        }
        AuraDebug.log(this, debug) {
            if (cancelled) "$report | attack cancelled by another module (AttackEntityEvent)" else report.toString()
        }
    }

    /**
     * The EXECUTE step of a tick the AutoMine job owns: one `doBreak` on the current cell, or the
     * `stopDestroyBlock()` that ends the job. Bracketed with [ownSend] so [observeOutgoing] does not read our own
     * dig back as a foreign one.
     *
     * A dig packet may not join a tick another click already used, in either order: `PacketOrderI` flags an attack,
     * an entity interact or a release-use that follows ANY dig packet (`PacketOrderI.java:81-96`, `:134-149`,
     * `:195-210`) and a placement or `USE_ITEM` that follows a finished, cancelled or non-instant started one
     * (`:98-123`), while `MultiActionsF` sets its block channel on `START_DIGGING` / `FINISHED_DIGGING`
     * (`MultiActionsF.java:78-92`) so the reverse order flags symmetrically. Since `InputHandleEvent` fires at the
     * RETURN of `handleKeybinds`, any foreign click of this tick is already on the wire - so a foreign use, dig or
     * entity interaction closes the gate for the whole tick.
     */
    private fun executeMine(mine: MinePlan) {
        val view = lastView ?: return
        val allowDig = !foreignUseSeen && !foreignDigSeen && !foreignEntitySeen && !HandSwapGate.holdsDigs
        ownSend = true
        val report = try {
            miner.execute(mine, view, rayIgnore, allowDig, foreignDigSeen)
        } finally {
            ownSend = false
        }
        AuraDebug.log(this, debug) { report }
    }

    /**
     * Dumps the previous tick's outgoing packets as one line, e.g. `packets: SetCarriedItem *Attack *Swing MovePlayer
     * ClientTickEnd`. `*` marks the module's own sends. This is the ground truth for every tick-window check: Grim
     * groups packets between tick packets, and on 1.21.2+ `CLIENT_TICK_END` only counts as one when the tick sent no
     * movement, otherwise the movement packet is the delimiter (`GrimProcessor.java:80-91`).
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
        AuraDebug.log(this, debug) { line }
    }

    /**
     * Only ticks that can actually trip a tick-window check are logged: one carrying at least two click packets, or
     * one where a foreign click met one of ours. Everything else is the ordinary one-action tick and would drown the
     * interesting lines.
     */
    private fun worthTracing(names: List<String>): Boolean {
        var own = 0
        var foreign = 0
        for (name in names) {
            if (!isClickName(name)) continue
            if (name.startsWith("*")) own++ else foreign++
        }
        return own + foreign >= 2
    }

    private fun isClickName(name: String): Boolean {
        val bare = name.removePrefix("*")
        return bare == "Attack" || bare == "Interact" || bare == "UseItemOn" ||
            bare == "UseItem" || bare == "PlayerAction"
    }

    /**
     * Whether an attack and a use may share this tick: `SameTick`, narrowed by its safe mode to ticks in which the
     * player is standing still ([PlaceSettings.sameTickSafe]).
     *
     * Movement is measured the way the client itself decides whether to send a position (`LocalPlayer.sendPosition`
     * compares against the last SENT position with a `2.0E-4` threshold); here the per-tick delta against
     * `Entity.lastPos` is enough. Sprinting counts as moving even when the delta is small, e.g. into a wall.
     * Asymmetric on purpose: movement disables the mode instantly, and it only returns after
     * [SAME_TICK_CALM_TICKS] calm ticks in a row.
     *
     * `Entity.lastPos` cannot be used here: `LivingEntity` has a private member of the same name, which shadows the
     * client's extension, so the previous position is read straight off `xo`/`yo`/`zo` - allocation-free as well.
     */
    private fun sameTickAllowed(): Boolean {
        if (!place.sameTick) return false
        if (!place.sameTickSafe) return true

        val moved = movedSquared() > STILL_EPSILON_SQ || player.isSprinting
        calmTicks = if (moved) 0 else calmTicks + 1
        return calmTicks >= SAME_TICK_CALM_TICKS
    }

    /** Squared distance the player moved during the last tick, without allocating a vector. */
    private fun movedSquared(): Double {
        val dx = player.x - player.xo
        val dy = player.y - player.yo
        val dz = player.z - player.zo
        return dx * dx + dy * dy + dz * dz
    }

    private fun recordAttack() {
        attackTicks[attackCursor] = tick
        attackCursor = (attackCursor + 1) % ATTACK_RING
    }

    /**
     * Reports the detonation whose attack really went out - the crystal's live position, or the centre
     * `ExplosionMath.crystalCenter` predicted for an IdPredict phantom (`exec/TickPlanner.kt:58`). Rendering only:
     * nothing here is read back by the plan.
     *
     * Deliberately NOT gated on `Render.Shockwave`. Three effects hang off this one event - the drawn ripple, the
     * refraction dome and the forge's heat - and each owns its own toggle downstream; gating the report itself
     * would silently switch off the other two with the ripple.
     */
    private fun recordShockwave(plan: TickPlan) {
        val center = plan.attack?.crystal?.position() ?: plan.phantomAttack?.center ?: return
        AuraRenderer.recordShockwave(center)
    }

    private fun recentAttacks(): Int {
        var count = 0
        for (stamp in attackTicks) {
            if (tick - stamp in 0 until TAG_WINDOW_TICKS) count++
        }
        return count
    }

    /**
     * Forgets everything, hands a [SwitchMode.NORMAL] switch back and clears [AuraRotation]'s cached entity. The
     * chronometers are set to 0 (not "now") so the first action after a reset is immediate
     * (`Chronometer.reset(lastUpdate)`, `Chronometer.kt:35-37`).
     */
    private fun resetState() {
        restoreNormalSlot()
        ledger.reset()
        intake.reset()
        miner.reset()
        plan = null
        target = null
        minePlan = null
        visuals = null
        AuraRenderer.reset()
        lastView = null
        breakSearch = null
        rayIgnore = ignoreNone
        tick = 0
        calmTicks = 0
        attackTicks.fill(Int.MIN_VALUE)
        attackCursor = 0
        foreignUseSeen = false
        foreignDigSeen = false
        foreignEntitySeen = false
        breakChrono.reset(0)
        placeChrono.reset(0)
        AuraRotation.reset()
        AuraDebug.reset()
    }

    private fun rayIgnoreFor(mode: LookThrough): (Entity) -> Boolean = when (mode) {
        LookThrough.OFF -> ignoreNone
        LookThrough.CRYSTALS -> ignoreCrystals
        LookThrough.ALL -> ignoreAll
    }

    /**
     * Results of one tick's searches plus the wire rotation they were made against.
     *
     * [placeCandidates] is the accepted placement ranking, best first, with only its head materialised; it may be
     * iterated once and only `TickPlanner.decide` does so.
     */
    private class Searches(
        val breakBest: BreakCandidate?,
        val placeCandidates: Sequence<PlaceCandidate>,
        val basePlan: BasePlan?,
        val wire: Rotation,
        /** The tick's placement search, re-used by `MineSearch` for its `evaluate` / `upperBound` calls. */
        val placeSearch: PlaceSearch,
        /** The head of [placeCandidates], already materialised; the MinGain reference of both mined searches. */
        val placeBest: PlaceCandidate?,
    )

    private const val MS_PER_TICK = 50.0
    private const val ATTACK_TIMEOUT_EXTRA = 2
    private const val PLACE_TIMEOUT_EXTRA = 1
    private const val PLACE_PREDICT_FACTOR = 1.5
    private const val BREAK_PREDICT_FACTOR = 0.5
    private const val TAG_WINDOW_TICKS = 20

    /** Squared per-tick movement below which the player counts as standing ([sameTickAllowed]). */
    private const val STILL_EPSILON_SQ = 1.0E-6

    /** Calm ticks required before the `SameTick` safe mode allows a shared tick again. */
    private const val SAME_TICK_CALM_TICKS = 3

    /**
     * Ticks a silently selected MINING tool survives without being renewed ([selectMineSlot]).
     *
     * The job renews it in every PLAN of the dig, and `SilentHotbar`'s expiry handler runs at priority 1001,
     * before `RotationManager`'s 1000 (`EventHookRegistry`: descending priority), so a slot selected in PLAN is
     * never dropped in the same tick. Two ticks is therefore only the grace after the job stops asking - long
     * enough that a single skipped PLAN (a screen, a level guard) does not put the wrong item into the hand
     * mid-dig, short enough that the hand comes back immediately afterwards.
     */
    private const val TOOL_HOLD_TICKS = 2
    private const val ATTACK_RING = 32

    /**
     * `java.awt.Font.getName()` of the SF Pro cut the damage text uses - the key
     * `FontManager.queueFontFromStream` registers the face under (`render/FontManager.kt:172-177`:
     * `FontFace(font.name, size, file = null)`). Baked by `FontManager.loadSfProFamily()`.
     */
    private const val DAMAGE_FONT_NAME = "SF Pro Rounded Bold"

    /** How long after a sent action the module still counts as working; see [isActing]. Five ticks. */
    private const val ACTING_MS = 250L

    /** A wire float never lands exactly on 6.0f; this is far tighter than the gap to the next power. */
    private const val EXPLOSION_RADIUS_EPSILON = 1.0E-3f
}
