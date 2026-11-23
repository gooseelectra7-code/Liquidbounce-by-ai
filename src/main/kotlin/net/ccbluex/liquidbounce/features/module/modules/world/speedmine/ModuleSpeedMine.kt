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
package net.ccbluex.liquidbounce.features.module.modules.world.speedmine

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.BlockAttackEvent
import net.ccbluex.liquidbounce.event.events.BlockShapeEvent
import net.ccbluex.liquidbounce.event.events.GameRenderTaskQueueEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoTotem
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.ModuleMainHandTotem
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleFreeCam
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlockRotation
import net.ccbluex.liquidbounce.utils.block.BlockChangeAcks
import net.ccbluex.liquidbounce.utils.block.MiningClaim
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.collection.blockSortedSetOf
import net.ccbluex.liquidbounce.utils.entity.anyHorizontal
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryItemSlot
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findBestToolToMineBlock
import net.ccbluex.liquidbounce.utils.item.durability
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.raytraceBlock
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * SpeedMine module
 *
 * Breaks the block you are mining faster than vanilla allows. Ported from the hellhit client's
 * `SpeedMine`, rebuilt on LiquidBounce's API with full support for double-mine, Grim/NCP air
 * rebreak, replace and on-eating bypass.
 *
 * Working integrations:
 * - GRIM rebreak, including instant-async remining: incoming block updates are only enqueued
 *   by the packet handler (which may run on the netty thread) and remined from the tick
 *   handler on the main thread.
 * - AwaitCrystalAura ([cev]): blocks hovering above the crystal aura's target are not
 *   insta-remined, so they don't drop items into the fight.
 * - Cross-module dig arbitration goes through [MiningClaim] (Grim WrongBreak: only ONE
 *   active dig pos may exist on the wire at a time). This module used to arbitrate against
 *   the old AutoCrystal through a web of shared fields; it now takes the claim like every
 *   other digger, so the aura, the navigation executor and PacketMine all see it and it
 *   sees them.
 * - Slot switching through the vanilla carried-item tracker (`ensureHasSentCarriedItem`),
 *   so no duplicate SetCarriedItem packets are sent (Grim BadPacketsA).
 *
 * Grim discipline (packet layer):
 * - An ANIMATION (packet-path swing) goes out immediately before every START_DESTROY_BLOCK
 *   the module emits, and before the scheduled [StartMode.STOP] STOP (NoSwingBreak).
 * - Insta-breakable blocks get a START only, never a STOP: the server breaks them on the
 *   START itself and predicts air from then on (AirLiquidBreak; vanilla never sends STOP
 *   for insta-broken blocks either).
 * - ABORT_DESTROY_BLOCK always carries [Direction.DOWN] — vanilla never sends the real dig
 *   face, and a non-DOWN ABORT arms PositionBreakB — and is never sent twice for the same
 *   pos without an intermediate START (WrongBreak, tracked per action via `abortSent`).
 * - A new dig starts no earlier than 300 ms after the last STOP, and sustained mining is
 *   paced by a local mirror of Grim's FastBreak balance: a break that would push the
 *   estimate past 800 (flag threshold 1000) is deferred to a later tick (FastBreak finish
 *   branch).
 * - DAMAGE mode never touches the destroy delay and only micro-boosts progress within the
 *   block's own tick count (FastBreak) — on Grim it is a micro-boost only.
 * - Replace places through a rotation target with the placement deferred until the aim is
 *   on the wire (RotationPlace; PacketOrderI).
 * - [Rebreak.NCP_AIR] and double-mine are Grim-incompatible by design (FastBreak,
 *   AirLiquidBreak, MultiBreak): NCP modes, not for Grim servers.
 * - [Rebreak.GRIM] (2026-09-24): the action survives the cell being empty, a rebreak STOP waits until the
 *   server's ack of the previous STOP has been processed (AirLiquidBreak - Grim holds the cell as air until
 *   then), and nothing is sent once the last START/ABORT on the wire is not ours on that cell (WrongBreak).
 *   `RebreakSpeed` above 20/s adds a per-frame path that is NOT Grim-safe (Post).
 * - Reach is the eyes to the nearest point of the cell against `blockInteractionRange` (FarBreak's rule,
 *   inside the server's +1.0), the same in every direction.
 */
@Suppress("TooManyFunctions", "LargeClass")
object ModuleSpeedMine : ClientModule("SpeedMine", ModuleCategories.WORLD) {

    private val defaultPlaceBlocks = blockSortedSetOf(
        Blocks.COBWEB, Blocks.LADDER, Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.OAK_BUTTON, Blocks.STONE_BUTTON
    )

    private val mode by enumChoice("Mode", Mode.PACKET)

    // Packet-mode behavior
    private val timing by enumChoice("Timing", Timing.SEQUENTIAL)
    // Declared ABOVE `RebreakSpeed` so the speed sits under it in the GUI. Safe: `onChanged` only fires from `set`,
    // never while the enum value is being constructed, and the `init` below covers the initial state.
    private val rebreak by enumChoice("Rebreak", Rebreak.OFF)
        .onChanged { syncRebreakSpeedVisibility(it) }

    /**
     * The most rebreaks per second [Rebreak.GRIM] may send.
     *
     * A rebreak is a STOP on a block that reappeared in the cell we already dug: the server keeps `destroyPos` and
     * `destroyProgressStart` from our START, so that STOP breaks it at once
     * (`ServerPlayerGameMode.handleBlockBreakAction`, STOP branch, `ServerPlayerGameMode.java:218-229`). So this is a
     * CEILING, never a rate the module can reach on its own: there is one rebreak per reappearance, and each one
     * also waits for the server to have answered the previous STOP (see [MineAction.serverAnsweredLastStop]).
     *
     * Up to [MAX_TICK_REBREAKS] the rebreak is decided once per client tick, before the tick's movement packet, the
     * way vanilla digs. Above it the check ALSO runs every frame ([frameHandler]), which is NOT Grim-safe: a dig
     * sent after the tick's movement packet and followed by a pong before the next one is flagged by `Post`
     * (`checks/impl/misc/Post.java:77-102`, reported at `:53`), a LIVE check.
     */
    private val rebreakSpeedValue = int("RebreakSpeed", MAX_TICK_REBREAKS, 1..MAX_REBREAK_SPEED, "/s")
    private val rebreakSpeed by rebreakSpeedValue

    /**
     * Whether a rebreak STOP is also held by this module's own mirror of Grim `FastBreak` (the budget in
     * [MineAction.breakBlock]). ON keeps the old behaviour; OFF skips the mirror for REBREAKS only and books them as
     * the "close enough" case Grim decays by 0.9.
     *
     * Why OFF can be right: Grim's FINISH branch predicts `ceil(1 / maximumBlockDamage) * 50` ms
     * (`checks/impl/breaking/FastBreak.java:100`), and `maximumBlockDamage` is a MAX over every ANIMATION / flying
     * packet since the START (`:132`). After our first FINISH Grim holds the cell as air
     * (`events/packets/CheckManagerListener.java:786-790`), and the swing sent right after every STOP ([sendSwing]) is
     * read against that air: hardness 0, so the damage is speed / 0 = Infinity (`utils/nmsutil/BlockBreakSpeed.java:124`)
     * and the predicted time is 0 until the next START. The mirror does not model that, so under heavy Mining Fatigue it
     * holds a rebreak for `ceil(1 / damage)` ticks after the START - about 13.6 minutes for obsidian under Fatigue III
     * with a diamond pickaxe and Efficiency V - where Grim itself would not.
     *
     * Read from the source, not observed in game - hence a switch rather than a silent removal. It stays ON by default:
     * if the first FINISH was cancelled by some other check, Grim never saw the cell as air and the mirror is right.
     */
    private val rebreakFastBreakCheckValue = boolean("RebreakFastBreakCheck", true)
    private val rebreakFastBreakCheck by rebreakFastBreakCheckValue

    /**
     * STOPs sent per rebreak ([Rebreak.GRIM]). 1 is the Grim-safe behaviour: one STOP per reappearance of the
     * block, the next only after the server has answered it ([MineAction.serverAnsweredLastStop]).
     *
     * Above 1, every STOP of ours is followed by `RebreakStops - 1` more on the following ticks, one per tick, sent
     * BLIND - whatever the client shows and without waiting for the server. The point is a block placed back
     * server-side before we have even seen it: a blind STOP arriving after it breaks it a whole round trip earlier,
     * and one arriving before it digs air, which the server ignores (`ServerPlayerGameMode.java:222`).
     *
     * NOT Grim-safe. Grim holds the cell as air from our FINISH until the server's ack of it
     * (`events/packets/CheckManagerListener.java:786-790`, `utils/latency/CompensatedWorld.java:286-289`), so every
     * follow-up sent before that ack is cancelled and flagged by `AirLiquidBreak` (LIVE,
     * `checks/impl/breaking/AirLiquidBreak.java:58-75`). The user chose this for servers without Grim.
     */
    private val rebreakStopsValue = int("RebreakStops", 1, 1..MAX_REBREAK_STOPS)
    private val rebreakStops by rebreakStopsValue

    init {
        syncRebreakSpeedVisibility(rebreak)
    }

    private fun syncRebreakSpeedVisibility(current: Rebreak) {
        rebreakSpeedValue.notAnOption(current != Rebreak.GRIM)
        rebreakFastBreakCheckValue.notAnOption(current != Rebreak.GRIM)
        rebreakStopsValue.notAnOption(current != Rebreak.GRIM)
    }

    private val awaitAutoCrystal by boolean("AwaitCrystalAura", true)
    private val instantAsync by boolean("InstantAsync", true)
    // NOTE (Grim): not for Grim servers. Sends multiple dig packets per tick-window by
    // design, and the instant STOP of the first block lands its full predicted dig time
    // on the FastBreak balance (STABLE) — plus MultiBreak / MultiActions exposure on
    // servers with experimental-checks: true.
    //
    // How it digs two blocks: every START goes out as START + ABORT + STOP. The early STOP arms the server's single
    // `hasDelayedDestroy` for that cell when none is armed (`ServerPlayerGameMode.java:231-236`), and the server then
    // finishes that block BY ITSELF - on the first server tick where the item in the hand times the ticks since its
    // START reaches 1.0 (`:111-119`, `:134-136`). The next START moves `destroyPos` to the second block, which the
    // normal STOP finishes. So the first block needs the TOOL IN THE HAND ON A SERVER TICK after its time - not a STOP
    // (one for a cell that is no longer `destroyPos` is ignored, `:219`). See [serverDelayed] and [holdFor].
    private val doubleMine by boolean("DoubleMine", false)
    private val startMode by enumChoice("StartMode", StartMode.ABORT)
    private val factor by float("Factor", 1f, 0.5f..3f)
    private val rotate by boolean("Rotate", true)
    private val await by boolean("Await", true)
    private val phase by boolean("Phase", true)
    private val resetOnSwitch by boolean("ResetOnSwitch", true)
    private val retryDelay by int("RetryDelay", 500, 0..2000, "ms")
    private val pauseEat by enumChoice("OnEating", OnEating.NONE)

    // Tool switching
    private val switchTool by boolean("SwitchTool", true)
    private val inventoryTool by boolean("InventoryTool", true)
    private val switchMode by enumChoice("SwitchMode", SwitchMode.Silent)
    private val swapTiming by enumChoice("SwapTiming", SwapTiming.ONCE)
    private val swapDelay by boolean("SwapDelay", false)
    private val durabilityThreshold by int("DurabilityThreshold", 4, 0..20)

    // Block replacement
    private val replace by boolean("Replace", false)
    private val replaceBlocks by blocks("ReplaceBlocks", defaultPlaceBlocks)

    // Damage mode
    private val speed by float("Speed", 0.5f, 0f..1f)

    /**
     * Names, in chat, every gate that refuses a dig and every break that goes out.
     *
     * The first thing to read when the module looks dead on one block type and alive on another:
     * the tool the dig is timed against, the item actually in the hand, and which of the gates
     * ([MiningClaim], the 300 ms FINISH->START pacing, the FastBreak budget, the crystal aura's
     * tick window, a refused tool swap) turned it away.
     */
    private val debug by boolean("Debug", false)

    // Render
    private val render by boolean("Render", true)
    private val renderMode by enumChoice("RenderMode", RenderMode.SHRINK)
    private val startColor by color("StartColor", Color4b(255, 0, 0, 120))
    private val endColor by color("EndColor", Color4b(47, 255, 0, 120))

    private val rotations = tree(RotationsValueGroup(this))

    /** Currently tracked mining actions. Up to two when [doubleMine] is enabled. */
    private val actions = LinkedList<MineAction>()
    private var swappedTool: InventoryItemSlot? = null

    /** Last block the player actually clicked, used by double-mine eviction logic. */
    private var lastClickedBlock: BlockPos? = null

    /** Last block successfully broken (for getIgnoreBlocks fallback). */
    private var lastBreakedBlock: BlockPos? = null
    private var lastBreakTime = 0L

    /**
     * Last time a STOP_DESTROY_BLOCK went out (Grim FastBreak FINISH->START pacing).
     * Internal: AutoCrystal shares this timestamp for the cross-module pacing.
     */
    internal var lastStopMs = 0L
        private set

    /**
     * Last time this module put a dig START/STOP (or a replace placement) on the wire.
     * AutoCrystal defers its actions while this is < 40 ms old (Grim PacketOrderI /
     * MultiActionsF / MultiBreak, experimental — timestamp-based because the two modules'
     * handler order is undefined: actions within one tick pass are microseconds apart,
     * adjacent ticks 50 ms).
     */
    internal var lastBlockActionMs = 0L
        private set

    /**
     * Cross-module arbitration (Grim WrongBreak, STABLE): the pos of any action whose START
     * is on the wire and whose dig has not finished or cancelled. AutoCrystal never STARTs
     * a dig of its own while this is set — only ONE active dig pos may exist across both
     * modules. Computed on access; cheap (at most two actions).
     */
    internal val activeDigPos: BlockPos?
        get() = actions.firstOrNull { it.digActive }?.pos

    /**
     * Whether the module is in the packet-mining mode that actually breaks blocks.
     *
     * `Damage` mode only nudges the vanilla break progress and never sends dig packets, so a consumer
     * that feeds this module targets (AutoCity) has nothing to feed while it is in `Damage`.
     */
    val isPacketMode: Boolean
        get() = mode == Mode.PACKET

    init {
        // Reported rather than claimed. This module's active position is derived from the packets it
        // has in flight, and copying that into a claim would be a second copy of a truth that is
        // maintained carefully here and nowhere else. Everything that asks "is anyone digging" —
        // the navigation executor, AutoMine — asks MiningClaim, and MiningClaim asks this.
        MiningClaim.registerProbe(this) { activeDigPos?.takeIf { running } }
    }

    /**
     * Grim PacketOrderI / MultiActionsF (experimental): true while AutoCrystal's last
     * attack/place is < 40 ms old, i.e. inside the same Grim tick-window (windows close on
     * flying/CLIENT_TICK_END). Timestamp-based because the two modules' handler order is
     * undefined — a flag cannot see the other module's same-tick actions.
     */
    /**
     * The crystal aura sent a packet inside this tick window. `PacketOrderI` flags a dig that shares a tick with a
     * place, use or attack (`checks/impl/packetorder/PacketOrderI.java:98-123, 195-210`), and the two modules'
     * handler order is undefined, so the shared wall clock is what both can read (< 40 ms = same window).
     */
    private fun auraBusy(): Boolean =
        ModuleCrystalAura.running &&
            System.currentTimeMillis() - ModuleCrystalAura.lastActionMs < 40L

    /**
     * Local mirror of Grim's FastBreak balance (FastBreak.java, finish branch): dig faster
     * than the predicted break time adds the difference, dig at/below prediction decays it
     * by 0.9. Breaks are deferred while this would exceed 800 (flag threshold is 1000).
     */
    private var estFastBreakBalance = 0f

    /** Prevents other code from swapping the hotbar while a break is in-flight. */
    var hotBarLock = AtomicBoolean(false)

    /**
     * Where the last START_DESTROY_BLOCK or ABORT_DESTROY_BLOCK on the wire pointed, from ANY sender in this client
     * (read off every outgoing packet in [sendPacketHandler], not off this module's own bookkeeping).
     *
     * A rebreak only works while it is still OUR cell the server and Grim think we are digging:
     * - the server breaks on a STOP only when `pos.equals(this.destroyPos)` (`ServerPlayerGameMode.java:219`), and
     *   any START elsewhere moves `destroyPos` (`:212`);
     * - Grim `WrongBreak` (LIVE) flags a FINISH whose pos is neither the last START nor the last CANCEL
     *   (`checks/impl/breaking/WrongBreak.java:84-98`), and a START or a CANCEL elsewhere replaces both (`:49-81`).
     * So a dig of another module - or of ours on another block - ends the rebreak for good.
     */
    @Volatile
    private var lastDigAnchor: DigAnchor? = null

    private class DigAnchor(val pos: BlockPos, val level: WeakReference<ClientLevel>)

    /**
     * DoubleMine: the cell whose `hasDelayedDestroy` the server is running - the mirror of `ServerPlayerGameMode`'s
     * `hasDelayedDestroy` / `delayedDestroyPos`. Set when a STOP of ours arms it ([MineAction.sendDoubleMineStart],
     * [MineAction.armServerDelayed]); the server clears its own only when the cell turns to air (`:113-114` - an ABORT
     * does not, a new START does not), and this one is cleared when the CLIENT sees the cell air
     * ([maintainDelayedHold]). There is ONE: while it is armed, the early STOP of every other START is dropped
     * (`if (!this.hasDelayedDestroy)`).
     *
     * Known desyncs, all of them costing at most one wasted tool hold: [reset] clears it (toggle, world change,
     * respawn) while the server's survives a dimension change (`ServerPlayer.setServerLevel` keeps the game mode); a
     * cell in a chunk the client unloaded reads as air; a START the server ignored (spawn protection, `mayInteract`)
     * still arms the mirror.
     */
    private var serverDelayed: BlockPos? = null

    /**
     * DoubleMine: the mirror of the server's `destroyPos` - the cell of the last START on the wire, whoever sent it,
     * EXCEPT an insta-break START, which the server answers with `destroyAndAck` before it would move `destroyPos`
     * (`ServerPlayerGameMode.java:203-204`). [lastDigAnchor] moves on those and on an ABORT as well, so it cannot
     * answer "would a STOP for this cell be taken". Written by [sendPacketHandler]; [instaStartInFlight] marks our
     * own insta START.
     */
    @Volatile
    private var serverDestroyPos: BlockPos? = null
    private var instaStartInFlight = false

    /**
     * DoubleMine: the delayed dig whose tool we keep in the hand ([requestDelayedHold]), the hotbar slot held, the slot
     * the player goes back to when the hold ends, and the wall clock it gives up at. One hold per dig: an expired one
     * marks the dig ([MineAction.holdExpired]) and the dig is dropped rather than held again.
     */
    private var holdFor: MineAction? = null
    private var holdSlot = -1
    private var holdHomeSlot = -1
    private var holdUntilMs = 0L

    /**
     * DoubleMine: a hold's slot restore that had to wait - the player was using an item (a slot change stops the use,
     * `ServerGamePacketListenerImpl.java:1448-1449`) or an inventory swap was still out. Applied by
     * [maintainDelayedHold] once neither holds, and only while the hand is still on [pendingRestoreFrom]: a slot the
     * player picked meanwhile wins.
     */
    private var pendingRestore = -1
    private var pendingRestoreFrom = -1

    /**
     * The target of a `switchBack` a STOP scheduled a tick or two out ([MineAction.breakBlock]) and that has not run
     * yet. A hold that starts in that gap takes its home slot from here - the hand still shows the tool the STOP
     * switched to.
     */
    private var pendingSwitchBack = -1

    /**
     * The slot a dig captures to go back to: the player's own slot, or - during a DoubleMine hold, when the hand
     * holds the hold's tool - the slot the hold will hand back. Capturing the tool slot there is what used to leave
     * the player on it once the hold had ended.
     */
    private fun restingSlot(): Int =
        if (holdFor != null && holdHomeSlot in 0..8) holdHomeSlot else player.inventory.selectedSlot

    /** Whether the last START/ABORT on the wire was for [pos] in the current level (see [lastDigAnchor]). */
    private fun serverDigsAt(pos: BlockPos): Boolean {
        val anchor = lastDigAnchor ?: return false
        return anchor.pos == pos && anchor.level.get() === world
    }

    /**
     * Rebreak rate limiter ([rebreakSpeed]). Up to [MAX_TICK_REBREAKS] per second it is a credit refilled once per
     * client tick (exact in ticks, where a wall clock would read 49 ms between two ticks and skip one), capped at one
     * rebreak plus one tick's refill so a fraction carries over; above it a wall clock, because the frame path is not
     * tick-aligned.
     */
    private var rebreakCredit = 1f
    private var lastRebreakAt = 0L

    /** Set by a [ClientboundRespawnPacket] on the netty thread, consumed at the head of [tickHandler]. */
    private val respawned = AtomicBoolean(false)

    private fun rebreakRateAllows(now: Long): Boolean =
        if (rebreakSpeed > MAX_TICK_REBREAKS) {
            (now - lastRebreakAt) * rebreakSpeed >= MS_PER_SECOND
        } else {
            rebreakCredit >= 1f
        }

    private fun spendRebreak(now: Long) {
        rebreakCredit = (rebreakCredit - 1f).coerceAtLeast(0f)
        lastRebreakAt = now
    }

    /** Positions recently sent a break for (await/phase): pos -> time. */
    private val unconfirmedBreaks = ConcurrentHashMap<BlockPos, Long>()

    /**
     * Positions reported by [ClientboundBlockUpdatePacket] for instant remining. Filled by
     * the packet handler, which may run on the netty thread — no world access there! — and
     * drained at the start of the tick handler on the main thread.
     */
    private val pendingInstantRemines = ConcurrentLinkedQueue<BlockPos>()

    /** Block positions explicitly asked to stop mining. */
    private val stopRequests = mutableSetOf<BlockPos>()

    /** Tick-deferred tasks: (ticks remaining, action). Replaces hellhit's TaskScheduler. */
    private val scheduled = mutableListOf<Pair<Int, () -> Unit>>()

    /** Lock for the tick-deferred [scheduled] list, which is written from the packet handler thread. */
    private val scheduledLock = Any()

    /**
     * Set while the module sends its own SetCarriedItem packets, so reset-on-switch does not
     * mistake them for a manual hotbar switch. A plain flag suffices: MixinConnection fires
     * OUTGOING [PacketEvent]s synchronously on the sending thread (verified in the pipeline).
     */
    private var internalSwap = false

    private inline fun withInternalSwap(block: () -> Unit) {
        internalSwap = true
        try {
            block()
        } finally {
            internalSwap = false
        }
    }

    override fun onDisabled() = reset(ResetReason.TOGGLE)

    override fun onEnabled() = reset(ResetReason.TOGGLE)

    private fun reset(reason: ResetReason = ResetReason.TOGGLE) {
        actions.forEach { it.reset(reason) }
        actions.clear()
        lastBreakedBlock = null
        lastClickedBlock = null
        hotBarLock.set(false)
        swappedTool = null
        synchronized(scheduledLock) {
            scheduled.clear()
        }
        pendingInstantRemines.clear()
        unconfirmedBreaks.clear()
        lastDigAnchor = null
        serverDelayed = null
        serverDestroyPos = null
        if (holdFor != null && mc.player != null && mc.connection != null) {
            restoreAfterHold(holdHomeSlot)
        }
        holdFor = null
        holdSlot = -1
        holdHomeSlot = -1
        pendingRestore = -1
        pendingRestoreFrom = -1
        pendingSwitchBack = -1
        rebreakCredit = 1f
        lastRebreakAt = 0L
        SilentHotbar.resetSlot(this)
    }

    private fun schedule(delay: Int, task: () -> Unit) {
        synchronized(scheduledLock) {
            scheduled.add(delay.coerceAtLeast(0) to task)
        }
    }

    private fun processScheduled() {
        val run = ArrayList<() -> Unit>()
        synchronized(scheduledLock) {
            val it = scheduled.listIterator()
            while (it.hasNext()) {
                val (delay, task) = it.next()
                if (delay <= 0) {
                    run.add(task)
                    it.remove()
                } else {
                    it.set((delay - 1) to task)
                }
            }
        }
        run.forEach { it() }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { reset(ResetReason.TOGGLE) }

    @Suppress("unused", "ReturnCount")
    private val tickHandler = handler<GameTickEvent> {
        // The cap is one rebreak plus one tick's refill, so the fraction left after a spend carries over: capped at
        // 1, a spend always left exactly 0 and every speed from 11 to 19 came out as 10/s.
        val perTick = rebreakSpeed.coerceAtMost(MAX_TICK_REBREAKS) / MAX_TICK_REBREAKS.toFloat()
        rebreakCredit = min(1f + perTick, rebreakCredit + perTick)

        // A respawn - death or a dimension change - gives us a new ServerPlayerGameMode with
        // `destroyPos = BlockPos.ZERO` (`ServerPlayerGameMode.java:44`): every dig of ours died with the old one, and
        // the client keeps the same ClientLevel when the dimension is unchanged, so nothing else notices. Dropped
        // without a packet: an ABORT or a START for a dig the server no longer has is a stray.
        if (respawned.getAndSet(false)) {
            actions.forEach { it.abandon() }
            reset(ResetReason.TOGGLE)
        }

        // Instant-rebreak positions deferred from the packet handler (netty thread): run the
        // same instant-remining logic here on the main thread, after the block update has
        // been applied to the world.
        while (true) {
            val pos = pendingInstantRemines.poll() ?: break
            for (action in actions) {
                if (action.pos == pos && action.isInstantRemining()) {
                    action.update(async = true)
                }
            }
        }

        processScheduled()

        val iterator = unconfirmedBreaks.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (System.currentTimeMillis() - next.value > 500 || world.isEmptyBlock(next.key)) {
                iterator.remove()
            }
        }

        if (player.abilities.instabuild) {
            return@handler
        }

        // Damage mode: push the vanilla progress — a micro-boost only on Grim.
        if (mode == Mode.DAMAGE) {
            // Grim FastBreak (STABLE): never zero the destroy delay — the next START would
            // land ~50 ms after the break, adding ~250 balance per block (flag within
            // ~5 blocks). The vanilla 5-tick delay keeps the FINISH->START spacing legal.
            if (interaction.destroyProgress > 0f) {
                val hit = mc.hitResult as? BlockHitResult
                val state = hit?.let { world.getBlockState(it.blockPos) }
                if (hit != null && state != null && !state.isAir) {
                    val d = vanillaProgress(hit.blockPos, state, player.mainHandItem)
                    if (d > 0f) {
                        // Grim FastBreak: the dig must still take ceil(1/d) ticks, so the
                        // boost may never save a whole tick — any bigger boost accumulates
                        // FastBreak balance. On Grim, DAMAGE mode is a micro-boost only.
                        val maxBoost = 1.0f - d * (ceil(1.0 / d).toFloat() - 1)
                        val boost = min(speed, maxBoost)
                        if (interaction.destroyProgress < boost) {
                            interaction.destroyProgress = boost
                        }
                    }
                }
            }
            return@handler
        }

        // Eating handling
        if (player.isUsingItem && pauseEat == OnEating.PAUSE) {
            return@handler
        }
        if (player.isUsingItem && pauseEat == OnEating.RESET) {
            reset(ResetReason.EATING)
            return@handler
        }

        stopRequests.forEach { requested ->
            actions.removeIf { it.pos == requested }
        }
        stopRequests.clear()

        actions.removeIf { it.update() }
        maintainDelayedHold()

        if (debug && actions.isNotEmpty()) {
            debugLog("state") {
                actions.joinToString(separator = "; ") { action ->
                    "${action.pos.toShortString()} prog=${"%.2f".format(action.progress)} " +
                        "tool=${describeSlot(toolSlot(action.pos))}"
                } + " held=${player.mainHandItem.hoverName.string}"
            }
        }
    }

    /**
     * The per-frame rebreak, only while [rebreakSpeed] is above [MAX_TICK_REBREAKS]. NOT Grim-safe (`Post`, see
     * [rebreakSpeed]): the STOP leaves between two ticks instead of before the tick's movement packet.
     *
     * `GameRenderTaskQueueEvent` fires at the head of `Minecraft.runTick`, main thread, before that frame's
     * `runAllTasks`, so it sees the world as the previous frame's packet processing left it. What it buys over the
     * tick path is reaction time only - up to a tick - because every rebreak still needs the block to be back and
     * the previous STOP answered.
     */
    @Suppress("unused")
    private val frameHandler = handler<GameRenderTaskQueueEvent> {
        if (mode != Mode.PACKET || rebreak != Rebreak.GRIM || rebreakSpeed <= MAX_TICK_REBREAKS) {
            return@handler
        }
        if (player.abilities.instabuild || (player.isUsingItem && pauseEat != OnEating.NONE)) {
            return@handler
        }

        val now = System.currentTimeMillis()
        for (action in actions) {
            action.rebreakFromFrame(now)
        }
    }

    @Suppress("unused")
    private val attackHandler = handler<BlockAttackEvent> { event ->
        if (mode != Mode.PACKET || player.abilities.instabuild || !canBreak(event.pos)) {
            return@handler
        }
        event.cancelEvent()

        val direction = (mc.hitResult as? BlockHitResult)?.direction ?: player.direction
        startMine(event.pos, direction)
    }

    @Suppress("unused")
    private val receivePacketHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val packet = event.packet
        if (packet is ClientboundBlockUpdatePacket && rebreak == Rebreak.GRIM && instantAsync &&
            packet.blockState.block == Blocks.OBSIDIAN
        ) {
            // PacketEvent can fire on the netty thread (see MixinConnection): no world
            // access here — only enqueue the position; the tick handler drains the queue
            // on the main thread and runs the instant rebreak there.
            pendingInstantRemines.add(packet.pos)
        }
        if (packet is ClientboundRespawnPacket) {
            // Netty thread: only flag it, the tick handler drops the actions.
            respawned.set(true)
        }
    }

    @Suppress("unused")
    private val sendPacketHandler = handler<PacketEvent> { event ->
        val digPacket = event.packet as? ServerboundPlayerActionPacket
        if (event.origin == TransferOrigin.OUTGOING && digPacket != null &&
            (digPacket.action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK ||
                digPacket.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)
        ) {
            // Any sender, this module included: see [lastDigAnchor].
            lastDigAnchor = mc.level?.let { DigAnchor(digPacket.pos.immutable(), WeakReference(it)) }
            if (digPacket.action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK && !instaStartInFlight) {
                serverDestroyPos = digPacket.pos.immutable()
            }
        }

        if (event.origin == TransferOrigin.OUTGOING &&
            event.packet is ServerboundSetCarriedItemPacket &&
            resetOnSwitch && switchMode != SwitchMode.Silent && rebreak != Rebreak.GRIM &&
            !internalSwap
        ) {
            // [internalSwap] filters out the module's own slot-change sends; only a slot
            // change initiated elsewhere (e.g. the player scrolling) resets the actions.
            schedule(0) { actions.forEach { it.reset(ResetReason.SWITCH) } }
        }
    }

    @Suppress("unused")
    private val shapeHandler = handler<BlockShapeEvent> { event ->
        if (canPhase() && unconfirmedBreaks.containsKey(event.pos)) {
            event.shape = Shapes.empty()
        }
    }

    fun canPhase() = running && phase && mode == Mode.PACKET &&
        unconfirmedBreaks.any { System.currentTimeMillis() - it.value < 200 }

    fun alreadyActing(blockPos: BlockPos) = actions.any { it.pos == blockPos }

    // --- Crystal aura AutoMine (`Mine.Engine = SpeedMine`, crystalaura/mine/PacketMineJob.kt) ----------------------
    //
    // The aura drives the two dig packets that need its aim and its tick - the START and the regular STOP - and
    // leaves every tick in between to its own crystals. Everything else stays here: the claim, the pacing, the
    // FastBreak mirror, the STOP's server-side acceptance test, the ABORT that `StartMode` schedules, the rebreak.
    // Main thread only, like the tick handler that owns [actions].

    /**
     * [startMine] for the aura, with the face the aura's own ray landed on (so it is one the eye can see, Grim
     * `PositionBreakA`, LIVE). True when an action now digs [pos], whether started here or already running - a
     * block the player clicked first is adopted. The action is marked [MineAction.auraOwned].
     */
    internal fun startForAura(pos: BlockPos, face: Direction): Boolean {
        startMine(pos, face)
        return markForAura(pos)
    }

    /**
     * Whether the module can dig for the aura at all: enabled, in the mode that sends dig packets, and not with
     * `Rebreak = NCPAir`, which sends an ABORT, a STOP and a START right behind every STOP - dig packets the aura
     * cannot account for. Not Grim-safe by design anyway (class KDoc).
     *
     * DoubleMine is allowed since 2026-10-03 (the user's ask). Its START + ABORT + STOP leaves inside the aura's own
     * START tick, which carries nothing else; a dig of the aura's that the server finishes by itself - because
     * another START moved `destroyPos` away - is refused a STOP of ours ([MineAction.finishedByServer]) and finished
     * by the tool hold like any other; and the aura does not START over two digs of the player's
     * ([digsElsewhereForOthers]).
     */
    internal val auraCompatible: Boolean
        get() = running && mode == Mode.PACKET && rebreak != Rebreak.NCP_AIR

    /**
     * Marks the action on [pos] as the aura's ([MineAction.auraOwned]), whoever started it - a block the player
     * clicked first is adopted. False when there is none. Idempotent; the aura calls it every tick it reads the dig.
     */
    internal fun markForAura(pos: BlockPos): Boolean {
        val action = actions.firstOrNull { it.pos == pos } ?: return false
        action.auraOwned = true
        return true
    }

    /**
     * The aura is done with [pos] - the cell is broken and stays open - and gives the dig back: rebreaks of whatever
     * reappears there run as for any dig of this module's from now on.
     */
    internal fun handBackFromAura(pos: BlockPos) {
        actions.firstOrNull { it.pos == pos }?.auraOwned = false
    }

    /**
     * A dig the aura does not own is live on a cell other than [pos] - the player's own click, most often. The aura
     * does not START over it: without DoubleMine [startMine] evicts the oldest action, so its START would end the
     * player's dig, the player's held click would START it again on the next tick and evict the aura's, and the two
     * would take turns sending STARTs for ever. A rebreak watcher (a dig whose STOP is out, `digActive` false) is
     * not counted - nothing is lost by evicting it.
     */
    internal fun digsElsewhereForOthers(pos: BlockPos): Boolean {
        val others = actions.count { it.pos != pos && !it.auraOwned && it.digActive }
        // DoubleMine keeps two digs, so one of the player's leaves room for the aura's; two would make [startMine]
        // evict one of them.
        return if (doubleMine) others >= 2 else others >= 1
    }

    /**
     * Why the aura may NOT send the STOP for [pos] with [stack] in the hand, or null when it may: the action exists,
     * its progress is full, and the gates [MineAction.breakBlock] would apply all pass for that stack - evaluated
     * BEFORE the aura spends a tick on it. See [MineAction.auraFinishBlocker].
     */
    internal fun auraFinishBlocker(pos: BlockPos, stack: ItemStack): String? {
        val action = actions.firstOrNull { it.pos == pos } ?: return "no action"
        return action.auraFinishBlocker(stack)
    }

    /**
     * Sends the STOP for [pos] NOW, in a tick the aura owns: no other click of the aura goes with it, and the aura
     * holds [tool] silently, so the server already has it in the hand. Null when the STOP went out, otherwise why it
     * was held (the same gates as the module's own STOP, minus the aura's tick window - see [MineAction.breakBlock]).
     */
    internal fun finishForAura(pos: BlockPos, face: Direction, tool: HotbarItemSlot?): String? {
        val action = actions.firstOrNull { it.pos == pos } ?: return "no action"
        val stack = tool?.itemStack ?: player.mainHandItem
        action.auraFinishBlocker(stack)?.let { return it }
        return action.breakBlock(tool, restingSlot(), face, auraOwnsTick = true)
    }

    /**
     * Drops the action on [pos] WITHOUT the rebreak that would otherwise follow it.
     *
     * The aura calls this for a cell it is about to fill itself - the base cell of a mined pocket, or any cell an
     * obsidian base is placed into. With `Rebreak = Grim` the action outlives the break and breaks again whatever
     * reappears in the cell, and `InstantAsync` fires on exactly the obsidian block update our own placement
     * produces ([receivePacketHandler]) - so without this the module would mine the aura's own base the moment the
     * server confirmed it. Removed at once rather than through [stopMining]: the instant remine is drained at the
     * head of [tickHandler], BEFORE the stop requests.
     *
     * A dig that is still live on a standing block is ended with [MineAction.cancel] (its ABORT, if one is still
     * owed); once the block is gone nothing is owed and [MineAction.abandon] sends nothing.
     */
    internal fun releaseForAura(pos: BlockPos) {
        val action = actions.firstOrNull { it.pos == pos } ?: return
        if (action.digActive && !world.isEmptyBlock(pos)) action.cancel()
        // Abandoned in every case, so a `whenReached` of ours still pending for it sends nothing
        // ([MineAction.breakBlock]).
        action.abandon()
        actions.remove(action)
        if (lastClickedBlock == pos) lastClickedBlock = null
        debugLine { "released ${pos.toShortString()} for the crystal aura" }
    }

    /**
     * Ticks until this module's own progress for a dig of [pos] with [stack] reaches 1, the point it finishes at
     * ([strengthOf], `Factor` included), with the player standing ([onGround]) or in the air for the whole dig - a
     * dig runs five times slower off the ground (`digSpeed`). Null when the block cannot be dug with it at all.
     */
    internal fun ticksToFinish(pos: BlockPos, stack: ItemStack, onGround: Boolean): Int? {
        val state = world.getBlockState(pos)
        val perTick = strengthOf(pos, state, stack, onGround)
        return if (perTick > 0f) ceil(1.0 / perTick).toInt() else null
    }

    fun startMine(pos: BlockPos, direction: Direction?) {
        if (!canBreak(pos) || player.abilities.instabuild || mode == Mode.DAMAGE) return

        if (!alreadyActing(pos)) {
            // Grim FastBreak (STABLE) measures FINISH->START spacing (275 ms threshold): a new
            // MineAction may not START within 300 ms of the last STOP of EITHER module
            // (vanilla's 5-tick destroy delay ≈ 250 ms). There is no intra-burst exemption
            // in Grim — the NCP_AIR tail and double-mine bypass this gate entirely, which is
            // exactly why they are marked Grim-incompatible.
            val now = System.currentTimeMillis()
            // Grim WrongBreak (STABLE): only ONE active dig pos may be live on the wire, or
            // the next STOP mismatches the last START. MiningClaim is where every digger in
            // this client says so; a refused claim means somebody else is mid-dig and this
            // START waits for the next click.
            if (!MiningClaim.claim(this, pos)) {
                debugLog("claim") {
                    val holder = MiningClaim.holder
                    val name = (holder as? ClientModule)?.name ?: holder?.javaClass?.simpleName ?: "?"
                    "no start at ${pos.toShortString()}: $name is digging ${MiningClaim.position?.toShortString()}"
                }
                return
            }
            if (now - lastStopMs < 300L) {
                debugLog("pace") {
                    "no start at ${pos.toShortString()}: ${now - lastStopMs} ms since the last STOP, need 300"
                }
                return
            }

            // Grim FastBreak decays the balance by 0.9 on a START that lands >= 275 ms after
            // the last FINISH — mirror it so the module's budget estimate tracks Grim's.
            if (now - lastStopMs >= 275L) {
                estFastBreakBalance *= 0.9f
            }

            if (!doubleMine) {
                if (actions.isNotEmpty()) {
                    actions.removeFirst().cancel()
                }
            }

            if (doubleMine) {
                makeRoomForDoubleMine(pos)
            }

            lastClickedBlock = pos

            // Never throw from the event handler: evict the oldest conflicting action instead.
            if (actions.size >= 2) {
                actions.removeFirst().cancel()
            }
            actions.add(MineAction(pos, direction))
            player.swing(InteractionHand.MAIN_HAND)
        }
    }

    /**
     * DoubleMine, before the START of [pos]: with two digs already, the last clicked one goes (the player's newest
     * click replaces it, the older one stays with the server's delayed destroy). THEN, if the delayed destroy is
     * free, the dig standing on `destroyPos` gets it with an early STOP of its own ([MineAction.armServerDelayed]):
     * this START moves `destroyPos`, a STOP for that dig would be ignored from then on, and this START's own early
     * STOP would take the free slot - leaving the old dig finished by nothing. Evicting first, so the slot is never
     * handed to a dig that is dropped the next line.
     */
    private fun makeRoomForDoubleMine(pos: BlockPos) {
        if (actions.size >= 2) {
            (actions.firstOrNull { it.pos == lastClickedBlock } ?: actions.first()).let {
                it.cancel()
                actions.remove(it)
            }
        }
        if (serverDelayed == null) {
            actions.firstOrNull { it.pos == serverDestroyPos && it.pos != pos }?.armServerDelayed()
        }
    }

    fun getIgnoreBlocks(targetAfter: Boolean = true): List<BlockPos> {
        if (actions.isEmpty()) {
            return emptyList()
        }
        if (actions.size == 1 && validMineIgnore(actions.first())) {
            return listOf(actions.first().pos)
        }
        if (actions.size == 2) {
            val last = actions.firstOrNull { it.pos == lastClickedBlock }
            if (last != null && validMineIgnore(last)) {
                return listOf(actions.first().pos, last.pos)
            }
        }
        val recentlyBroken = System.currentTimeMillis() - lastBreakTime < 200 && lastBreakedBlock != null
        if (recentlyBroken && targetAfter) {
            return listOf(lastBreakedBlock!!)
        }
        return emptyList()
    }

    private fun validMineIgnore(action: MineAction) =
        action.progress < 1f &&
            action.progress + (action.progress - action.prevProgress) * 2.5f >= 1f &&
            !world.isEmptyBlock(action.pos)

    /** A block hovering above the crystal aura's victim: remining it would rain items into the fight. */
    private fun cev(bp: BlockPos): Boolean {
        val target = ModuleCrystalAura.target
        return ModuleCrystalAura.running && target != null && bp.y > target.y + 1
    }

    private fun stopMining(pos: BlockPos) {
        stopRequests.add(pos)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun canBreak(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        if (state.isAir || state.block.defaultDestroyTime() == -1f) {
            return false
        }
        return inReach(pos) || (doubleMine && pos != lastClickedBlock)
    }

    /**
     * Whether [pos] is in reach for a dig: the eyes to the NEAREST point of the block's cell, against
     * `blockInteractionRange`.
     *
     * This used to be the eyes to the block's CENTRE against the range + 0.5, which is neither rule that matters.
     * The server refuses a dig only beyond range + 1.0 to the nearest point (`ServerPlayerGameMode.java:155`,
     * `Player.isWithinBlockInteractionRange`, `Player.java:1980-1983`), and Grim `FarBreak` (experimental) wants
     * the nearest point within the range itself (`checks/impl/breaking/FarBreak.java:27-43`). A centre is 0.5
     * further than the face straight ahead but up to ~0.87 further across a corner, so the old test cut the reach
     * to ~4.1 whenever the block stood diagonally or above/below, and [MineAction.update] then dropped the action
     * - with its rebreak - for good. Reported 2026-09-24 as "the rebreak depends on where I stand".
     */
    private fun inReach(pos: BlockPos): Boolean {
        val range = player.blockInteractionRange()
        return AABB(pos).distanceToSqr(player.eyePosition) <= range * range
    }

    /**
     * Where to look to dig [pos].
     *
     * This used to be `Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0)`, i.e. `(x+0.5, y+1.0, z+0.5)` — the
     * centre of the block's TOP FACE, half a block above its middle and exactly on the boundary
     * plane with the cell above. Reported 2026-09-22 as "it rotates to the wrong place", and it is
     * worse than cosmetic: a point on the surface plane is a grazing hit, so with anything standing
     * on top of the block the ray reaches THAT block first and [faceOf] — which raytraces the same
     * rotation — misses and falls back to the approximate eye-facing direction, putting a face in
     * the dig packets that the aim does not support. It was also the only aim of this shape in the
     * tree.
     *
     * [raytraceBlockRotation] is what every other block-aiming feature here uses, including the
     * sibling miner (`ModulePacketMine.rotate`): it searches the block's own outline shape for a
     * point actually VISIBLE from the eyes and prefers the rotation closest to looking at the
     * centre. `wallsRange` is kept equal to the range so an occluded block is still aimed at rather
     * than refused — the module never checked visibility before and this change is about WHERE it
     * looks, not about what it agrees to mine. The block centre is the fallback when the search
     * finds nothing (an empty shape), because it is at least inside the block.
     */
    private fun digRotation(pos: BlockPos, state: BlockState): Rotation {
        val reach = player.blockInteractionRange()
        val traced = raytraceBlockRotation(
            eyes = player.eyePosition,
            pos = pos,
            state = state,
            range = reach,
            wallsRange = reach,
        )
        if (traced == null) {
            debugLog("aim") { "no visible point on ${pos.toShortString()}, aiming at its centre" }
            return Rotation.lookingAt(point = pos.center, from = player.eyePosition)
        }
        return traced.rotation
    }

    private fun faceOf(pos: BlockPos): Direction {
        val state = world.getBlockState(pos)
        return raytraceBlock(
            range = player.blockInteractionRange() + 1.0,
            rotation = RotationManager.serverRotation,
            pos = pos,
            state = state,
        )?.direction
            // Raytrace miss: fall back to the face pointing toward the eyes (Grim
            // PositionBreakA) — never the body direction.
            ?: run {
                val eye = player.eyePosition
                Direction.getApproximateNearest(
                    eye.x - (pos.x + 0.5), eye.y - (pos.y + 0.5), eye.z - (pos.z + 0.5)
                )
            }
    }

    private fun toolSlot(pos: BlockPos): ItemSlot? = toolFor(world.getBlockState(pos))

    /** [toolSlot] for a known [state], e.g. the block a cell held before it was broken ([MineAction.sendFollowUpStop]). */
    private fun toolFor(state: BlockState): ItemSlot? {
        if (!switchTool) {
            return null
        }
        if (state.isAir) {
            return null
        }
        val pool = if (inventoryTool) Slots.HotbarAndInventory else Slots.Hotbar
        return pool.findBestToolToMineBlock(state, ignoreDurability = true)
            // `maxDamage` is 0 for everything that cannot take damage - a block stack, a crystal, an
            // empty slot - so the bare `maxDamage - damageValue > threshold` this used to be
            // rejected every one of them and returned null. That is not a rare case: for a block no
            // tool applies to (stone with no pickaxe in the kit) every candidate ties at destroy
            // speed 1.0 and `findBestToolToMineBlock` breaks the tie by proximity, i.e. returns the
            // HELD slot - obsidian, crystals, an empty slot. The null then froze the whole dig in
            // [MineAction.update]. Same exemption the repo's own filter carries
            // (`InventoryUtils.kt:104`: `stack.durability > 2 || stack.maxDamage <= 0`).
            ?.takeIf { it.itemStack.maxDamage <= 0 || it.itemStack.durability > durabilityThreshold }
    }

    /**
     * The stack the dig is timed against: the tool that will be put in the hand for it, or whatever
     * is held when there is none.
     *
     * The server reads `getDestroyProgress` off the item in the main hand at the moment it processes
     * the STOP and multiplies by the elapsed ticks (`ServerPlayerGameMode.handleBlockBreakAction`,
     * STOP branch: `getDestroyProgress(player, level, pos) * (ticksSpentDestroying + 1) >= 0.7F`).
     * So the client's own progress must be simulated against that same stack - simulating a tool the
     * server will not see puts the STOP under 0.7, and the block is then finished by the server's
     * own `hasDelayedDestroy` at the FULL vanilla time.
     */
    private fun stackOf(tool: ItemSlot?): ItemStack = tool?.itemStack ?: player.mainHandItem

    private fun strengthOf(pos: BlockPos, state: BlockState, tool: ItemStack?, onGround: Boolean): Float {
        val hardness = state.getDestroySpeed(world, pos)
        if (hardness < 0f) {
            return 0f
        }
        val item = tool ?: player.mainHandItem
        val suitable = if (!state.requiresCorrectToolForDrops() || item.isCorrectToolForDrops(state)) 30f else 100f
        // `item`, not `tool`: the two differ only on the null path, and reading the fallback for
        // `suitable` while `digSpeed` saw null mixed the held item's tool-correctness with a bare
        // hand's speed.
        return digSpeed(state, item, onGround) / hardness / suitable
    }

    /**
     * Vanilla per-tick destroy progress with [tool] (pass the held item for "no swap"),
     * without [factor] — the insta-break gate (Grim AirLiquidBreak) needs the server's view
     * of the break speed.
     */
    private fun vanillaProgress(pos: BlockPos, state: BlockState, tool: ItemStack): Float {
        val hardness = state.getDestroySpeed(world, pos)
        if (hardness < 0f) {
            return 0f
        }
        val suitable = if (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state)) 30f else 100f
        return digSpeed(state, tool, player.onGround(), applyFactor = false) / hardness / suitable
    }

    /**
     * Mining Fatigue's speed factor, vanilla's own table (`Player.getDestroySpeed`, `Player.java:594-601`).
     *
     * This was `0.3^(amplifier + 1)`, which matches levels I and II only and is TEN times too fast from level III on
     * (0.027 against 0.0027). Under Mining Fatigue III the STOP gate in [MineAction] therefore believed a STOP would
     * pass long before the server would take it: the server refused it and armed `hasDelayedDestroy`, and a rebreak
     * resent it after every ack for as long as the fatigue lasted.
     */
    private fun miningFatigueScale(amplifier: Int): Float = when (amplifier) {
        0 -> 0.3f
        1 -> 0.09f
        2 -> 0.0027f
        else -> 8.1E-4f
    }

    private fun digSpeed(state: BlockState, tool: ItemStack?, onGround: Boolean, applyFactor: Boolean = true): Float {
        var speedValue = 1f
        if (tool != null && !tool.isEmpty) {
            speedValue *= tool.getDestroySpeed(state)
        }
        if (speedValue > 1f && tool != null && !tool.isEmpty) {
            val efficiency = tool.getEnchantment(Enchantments.EFFICIENCY)
            if (efficiency > 0) {
                speedValue += (efficiency.toDouble().pow(2.0) + 1).toFloat()
            }
        }
        player.getEffect(MobEffects.HASTE)?.let { speedValue *= 1f + (it.amplifier + 1) * 0.2f }
        player.getEffect(MobEffects.MINING_FATIGUE)?.let { speedValue *= miningFatigueScale(it.amplifier) }
        if (player.isUnderWater) {
            speedValue *= player.getAttribute(Attributes.SUBMERGED_MINING_SPEED)?.value?.toFloat() ?: 0.2f
        }
        if (!onGround && !ModuleFreeCam.running) {
            speedValue /= 5f
        }
        val clamped = if (speedValue < 0f) 0f else speedValue
        return if (applyFactor) clamped * factor else clamped
    }

    /**
     * @param from Original slot index for [SwitchMode.Alternative] swap-back.
     *        -1 means "use the tool slot itself".
     */
    private fun switchTo(tool: ItemSlot?, from: Int = -1): Boolean {
        when (tool) {
            is HotbarItemSlot -> {
                // A null hotbarIndex is the offhand, which cannot become the main hand.
                val idx = tool.hotbarIndex ?: return false
                if (idx == player.inventory.selectedSlot) {
                    // Already in hand: nothing to swap, no slot sync needed at all.
                    return true
                }
                player.inventory.selectedSlot = idx
                // All modes sync through the vanilla carried-item tracker: it sends
                // SetCarriedItem only when the server-side tracker actually differs, so
                // no duplicate packets go out (Grim BadPacketsA). Normal keeps the
                // visible slot change above; Silent semantics stay as they are.
                withInternalSwap { interaction.ensureHasSentCarriedItem() }
                return true
            }

            is InventoryItemSlot -> {
                // Grim MultiActionsC (STABLE): a container click while moving/sprinting is
                // flagged (1.21.2+: knownInput.moving() suffices) — skip the swap while
                // the player moves and dig with the held item.
                if (player.isSprinting || player.input.keyPresses.anyHorizontal) {
                    return false
                }
                swappedTool = tool
                val sourceSlot = if (from != -1) {
                    // Alternative mode may ask to swap the original inventory slot back,
                    // not necessarily the currently best tool.
                    InventoryItemSlot(from)
                } else {
                    tool
                }
                return InventoryAction.Click.performSwap(
                    from = sourceSlot,
                    to = HotbarItemSlot(player.inventory.selectedSlot),
                ).performAction()
            }

            // Null tool means "dig with whatever is held": nothing to put in the hand, nothing to
            // wait for.
            else -> return true
        }
    }

    private fun switchBack(prevSlot: Int) {
        if (prevSlot == pendingSwitchBack) pendingSwitchBack = -1
        val swapped = swappedTool
        if (swapped != null) {
            // Grim MultiActionsC (STABLE, see switchTo): defer the swap-back while the
            // player is moving/sprinting; retried on the next tick.
            if (player.isSprinting || player.input.keyPresses.anyHorizontal) {
                schedule(1) { switchBack(prevSlot) }
                return
            }
            InventoryAction.Click.performSwap(
                from = swapped,
                to = HotbarItemSlot(player.inventory.selectedSlot),
            ).performAction()
            swappedTool = null
        } else if (prevSlot in 0..8 && prevSlot != player.inventory.selectedSlot) {
            // DoubleMine hold: the tool stays until the server has finished the delayed block; the slot this call
            // wanted is the one the hold gives back ([maintainDelayedHold]). An inventory swap-back above is NOT held:
            // the selected slot does not move during a hold, so it still pairs with the slot it was swapped into.
            if (holdFor != null) {
                if (prevSlot != holdSlot) holdHomeSlot = prevSlot
                return
            }
            player.inventory.selectedSlot = prevSlot
            // See switchTo: vanilla-tracked sync only, no duplicate packets (BadPacketsA).
            withInternalSwap { interaction.ensureHasSentCarriedItem() }
        }
    }

    /**
     * DoubleMine: puts [tool] in the hand for the delayed dig [action] ONCE and leaves it there until
     * [maintainDelayedHold] sees the block gone, the action dropped, or [holdUntilMs] pass: our ping twice (the
     * server took the START a little later than we sent it, and its block update has to come back) plus the larger of
     * [DELAYED_HOLD_SLACK_MS] and [DELAYED_HOLD_SLACK_SHARE] % of the dig ([dueAfterMs]). One hold per dig and one at
     * a time (there is one delayed destroy). The hand is never fought for: a slot change by anyone else - the player,
     * another module's NORMAL switch, a dig of ours with a different tool - ends the hold on the spot, without a
     * restore, and drops the dig.
     *
     * Held only with a HOTBAR tool and only while `SwitchTool` is on: an inventory tool would need a container swap to
     * come and go around a hold of seconds, and with `SwitchTool` off there is nothing to hold. Not started while an
     * inventory swap of another dig is outstanding (its swap-back pairs with the selected slot, which the hold moves)
     * and not started while the player uses an item - a slot change would stop eating or blocking; the restore at its
     * end waits for the same reason ([pendingRestore]).
     *
     * Known gap: while another module holds a silent slot (the crystal aura's crystal, `Place.Switch = Silent`),
     * `ensureHasSentCarriedItem` announces THAT slot and not ours (AGENTS.md, "Slot changes"), so the server sees no
     * tool on those ticks and finishes the block on the first tick it does - or, if the hold runs out first, the next
     * time a tool is in the hand.
     */
    private fun requestDelayedHold(action: MineAction, tool: ItemSlot?, dueAfterMs: Long) {
        if (holdFor === action) {
            // Somebody moved the hand off the tool - the player's scroll, another module's NORMAL switch (a totem
            // going in): the hand is theirs. The hold ends there and is not fought for; the dig is dropped.
            if (player.inventory.selectedSlot != holdSlot) endDelayedHold(expired = true, restore = false)
            return
        }
        val idx = (tool as? HotbarItemSlot)?.hotbarIndex
        if (idx == null || !switchTool) {
            debugLog("hold") { "DoubleMine: no hotbar tool to hold for ${action.pos.toShortString()}" }
            return
        }
        if (player.isUsingItem || holdFor != null || swappedTool != null) return
        // A totem feature holding the hand (MainHandTotem, AutoTotem's swap in flight): even the one switch a hold
        // makes would take the totem out of it server-side.
        if (ModuleMainHandTotem.isBusy || ModuleAutoTotem.isSwapping) return
        holdFor = action
        holdSlot = idx
        holdHomeSlot = pendingSwitchBack.takeIf { it in 0..8 } ?: player.inventory.selectedSlot
        // Proportional slack: the server counts its own ticks, so below 20 TPS a long dig comes due late - by 4 % of it
        // at 19.2 TPS, 10 % at 18; 15 % of the dig keeps a 9 s obsidian dig covered down to about 17 TPS.
        val slack = maxOf(DELAYED_HOLD_SLACK_MS, dueAfterMs * DELAYED_HOLD_SLACK_SHARE / 100)
        holdUntilMs = System.currentTimeMillis() + player.ping * 2L + slack
        debugLine { "DoubleMine: ${action.pos.toShortString()} is due, holding ${describeSlot(tool)} for it" }
        if (player.inventory.selectedSlot != idx) {
            player.inventory.selectedSlot = idx
            withInternalSwap { interaction.ensureHasSentCarriedItem() }
        }
    }

    /**
     * Ends a hold whose block is gone or whose time is up, applies a restore that had to wait, and forgets a delayed
     * destroy the client sees finished.
     */
    private fun maintainDelayedHold() {
        serverDelayed?.let { if (world.isEmptyBlock(it)) serverDelayed = null }
        if (pendingRestore in 0..8 && !player.isUsingItem && swappedTool == null) {
            if (player.inventory.selectedSlot == pendingRestoreFrom) setSlot(pendingRestore)
            pendingRestore = -1
            pendingRestoreFrom = -1
        }
        val action = holdFor ?: return
        val gone = action.finished || world.isEmptyBlock(action.pos)
        val expired = System.currentTimeMillis() > holdUntilMs
        if (!gone && !expired) return
        debugLine {
            val what = if (gone) "finished, tool released" else "still standing, hold ran out - dropped"
            "DoubleMine: ${action.pos.toShortString()} $what"
        }
        endDelayedHold(expired = !gone, restore = true)
    }

    /**
     * Ends the hold. [expired] marks its dig so it is dropped rather than held again; [restore] hands the hand back
     * to the home slot ([restoreAfterHold]) - not when somebody else already moved it.
     */
    private fun endDelayedHold(expired: Boolean, restore: Boolean) {
        val action = holdFor ?: return
        if (expired) action.holdExpired = true
        val home = holdHomeSlot
        holdFor = null
        holdHomeSlot = -1
        if (restore) restoreAfterHold(home)
        holdSlot = -1
    }

    /**
     * Back to [home] from the hold's slot - now, or once the player has stopped using an item and no inventory swap is
     * out ([pendingRestore]). Only while the hand is still on the hold's slot.
     */
    private fun restoreAfterHold(home: Int) {
        if (home !in 0..8 || player.inventory.selectedSlot != holdSlot || home == holdSlot) return
        if (player.isUsingItem || swappedTool != null) {
            pendingRestore = home
            pendingRestoreFrom = holdSlot
            return
        }
        setSlot(home)
    }

    private fun setSlot(slot: Int) {
        player.inventory.selectedSlot = slot
        withInternalSwap { interaction.ensureHasSentCarriedItem() }
    }

    private fun sequenced(supplier: (Int) -> ServerboundPlayerActionPacket) {
        interaction.startPrediction(world) { sequence -> supplier(sequence) }
    }

    private fun send(packet: ServerboundPlayerActionPacket) = network.send(packet)

    private fun sendSwing() = player.swing(InteractionHand.MAIN_HAND)

    /** One clock per [key], so a gate that refuses once is not drowned by one that refuses every tick. */
    private val debugAt = HashMap<String, Long>()

    /**
     * Rate-limited [debug] chat line. The message is built lazily, so the setting off costs one
     * boolean read.
     */
    private inline fun debugLog(key: String, message: () -> String) {
        if (!debug) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - (debugAt[key] ?: 0L) < DEBUG_INTERVAL_MS) {
            return
        }
        debugAt[key] = now
        chat(message(), this)
    }

    /** Unthrottled [debug] line, for one-shot verdicts a per-tick state line must not swallow. */
    private inline fun debugLine(message: () -> String) {
        if (debug) {
            chat(message(), this)
        }
    }

    private fun describeSlot(slot: ItemSlot?): String = when (slot) {
        null -> "none (dig with the held item)"
        is HotbarItemSlot -> "${slot.itemStack.hoverName.string}@hotbar${slot.hotbarIndex}"
        is InventoryItemSlot -> "${slot.itemStack.hoverName.string}@inventory"
        else -> slot.itemStack.hoverName.string
    }

    private fun timingPriority(): Priority = when (timing) {
        Timing.VANILLA -> Priority.IMPORTANT_FOR_USAGE_1
        Timing.SEQUENTIAL -> Priority.IMPORTANT_FOR_USAGE_2
        Timing.GRIM -> Priority.IMPORTANT_FOR_USAGE_3
    }

    private fun replaceBlock(pos: BlockPos): Boolean {
        if (hotBarLock.get()) {
            return false
        }

        val slot = Slots.Hotbar.findSlot { stack ->
            val item = stack.item
            item is BlockItem && replaceBlocks.contains(item.block)
        } ?: return false
        if (slot.hotbarIndex == null) {
            return false
        }

        // Find a neighboring block we can click on to place into `pos`.
        val placeRange = player.blockInteractionRange() + 0.5
        val eye = player.eyePosition
        var bestHit: BlockHitResult? = null
        for (dir in Direction.values()) {
            val neighbor = pos.relative(dir)
            if (world.isEmptyBlock(neighbor)) continue
            val clickPoint = Vec3.atCenterOf(neighbor)
            if (clickPoint.distanceTo(eye) > placeRange) continue
            val hit = raytraceBlock(
                range = placeRange,
                rotation = Rotation.lookingAt(point = clickPoint, from = eye),
                pos = neighbor,
                state = world.getBlockState(neighbor),
            )
            if (hit != null && hit.blockPos.relative(hit.direction) == pos) {
                bestHit = hit
                break
            }
        }
        bestHit ?: return false

        val slotIdx = slot.hotbarIndex ?: return false
        val prevSlot = player.inventory.selectedSlot
        val hit = bestHit

        // Grim RotationPlace (STABLE) raytraces the UseItemOn against the post-flying
        // rotation, so the aim must be on the wire for the place; Grim PacketOrderI
        // (experimental) flags a place in the same tick-window as the FINISH. Same
        // pattern as breakBlock: rotate to the click location and place in whenReached,
        // a later tick-window than the STOP. Slot sync goes through vanilla's
        // ensureHasSentCarriedItem inside useItemOn — a raw sendHeldItemChange here would
        // duplicate the slot (Grim BadPacketsA).
        val place = {
            // Grim PacketOrderI / MultiActionsF (experimental): the crystal aura's attack/place
            // shares this tick-window — retry the placement next tick instead.
            if (auraBusy()) {
                schedule(1) { replaceBlock(pos) }
            } else {
                SilentHotbar.selectSlotSilently(this, slotIdx, 2)
                player.inventory.selectedSlot = slotIdx
                doPlacement(hit, swingMode = SwingMode.HIDE_CLIENT)
                lastBlockActionMs = System.currentTimeMillis()
                SilentHotbar.resetSlot(this)
                player.inventory.selectedSlot = prevSlot
            }
        }
        val rotation = Rotation.lookingAt(point = hit.location, from = player.eyePosition)
        RotationManager.setRotationTarget(
            rotation,
            considerInventory = false,
            valueGroup = rotations,
            priority = timingPriority(),
            provider = this,
            whenReached = RestrictedSingleUseAction({ true }, place),
        )
        return true
    }

    // --- Render ---------------------------------------------------------------------------------

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!render || mode != Mode.PACKET) {
            return@handler
        }

        actions.forEach { current ->
            if (world.isEmptyBlock(current.pos)) {
                return@forEach
            }

            val f = (current.prevProgress + (current.progress - current.prevProgress) * event.partialTicks)
                .coerceIn(0f, 1f)
            val x = current.pos.x.toDouble()
            val y = current.pos.y.toDouble()
            val z = current.pos.z.toDouble()
            val inset = (1.0 - f) * 0.5
            val box = when (renderMode) {
                RenderMode.BLOCK -> AABB(current.pos)
                RenderMode.GROW -> AABB(x, y, z, x + 1, y + f, z + 1)
                RenderMode.SHRINK -> AABB(
                    x + inset, y + inset, z + inset,
                    x + 1 - inset, y + 1 - inset, z + 1 - inset,
                )
            }
            val face = startColor.interpolateTo(endColor, f.toDouble())
            val outline = startColor.alpha(255).interpolateTo(endColor.alpha(255), f.toDouble())

            renderEnvironmentForWorld(event.matrixStack) {
                withPositionRelativeToCamera {
                    drawBox(box, face, outline)
                }
            }
        }
    }
    // --- Mine action ----------------------------------------------------------------------------

    class MineAction(val pos: BlockPos, direction: Direction?) {
        var progress = 0f
            private set
        var prevProgress = 0f
            private set

        private val groundStates = ArrayDeque<Boolean>()
        var mineBreaks = 0
        private var lastStartAt = 0L
        private var lastResendAt = 0L

        /**
         * Set when the module has sent an ABORT for [pos] without an intermediate START —
         * Grim WrongBreak (STABLE) flags a second CANCELLED on the same pos without one.
         */
        private var abortSent = false

        /** Set when the insta-break gate completed the dig with its START (Grim AirLiquidBreak). */
        private var instaCompleted = false

        /**
         * Set by a dig finished with a STOP ([breakBlock]), cleared by the insta-break START. Only the former leaves
         * the server's `destroyPos` on this cell - the insta branch returns before assigning it
         * (`ServerPlayerGameMode.java:203-212`) - so only the former can be rebroken with a bare STOP.
         */
        private var rebreakable = false

        /** Sequence and send time of this action's last STOP ([breakBlock]); see [serverAnsweredLastStop]. */
        private var stopSequence = -1
        private var stopSentAt = 0L

        /** Set by [abandon]: another dig owns the server's `destroyPos` now, nothing of ours may follow. */
        private var abandoned = false

        /** Blind STOPs still owed after the last one ([rebreakStops]); see [sendFollowUpStop]. */
        private var followUpStops = 0

        /** `player.tickCount` of this action's last STOP: at most one STOP per action per tick. */
        private var lastStopTick = -1

        /** The block this cell held at our last STOP - what a follow-up picks its tool for once the cell is air. */
        private var dugState: BlockState? = null

        /**
         * Set once the action is dropped ([cancel], [abandon]). The ABORT / STOP that [sendStartSequence] scheduled a
         * tick after the START must not go out after that: an action evicted before its first tick (AutoCity feeding
         * one block while the player clicks another) otherwise sent its ABORT AFTER the next action's START - a stray
         * ABORT for another cell, which also moved [lastDigAnchor] off the new dig and got it abandoned.
         */
        private var ended = false

        /**
         * Set while this action's START is on the wire and the dig has not finished or
         * cancelled — read by [ModuleSpeedMine.activeDigPos] for the cross-module
         * single-dig arbitration (Grim WrongBreak, STABLE).
         */
        var digActive = false
            private set

        /**
         * The crystal aura's AutoMine drives this dig ([startForAura]). Two consequences:
         * - its FIRST STOP is the aura's to send ([finishForAura]); [step] files none of its own (see there);
         * - no blind follow-up STOPs ([rebreakStops]) go out after the regular one: each of them is a dig packet in its
         *   own tick, the aura stands down for a tick that carries one (`PacketOrderI`), and they would fall exactly
         *   on the ticks in which the pocket just opened and the crystal should go in - up to 19 of them in a row.
         */
        var auraOwned = false

        private val mine = ModuleSpeedMine

        init {
            start(direction)
        }

        private fun start(direction: Direction?) {
            val startDirection = direction ?: mine.player.direction
            val prevSlot = mine.restingSlot()
            val pickSlot = mine.toolSlot(pos)

            // Grim AirLiquidBreak (STABLE, fires at default config): the server breaks an
            // insta-breakable block on the START itself and predicts air from then on — a
            // later STOP would be digging air, and vanilla never sends STOP for insta-broken
            // blocks either. The dig completes right here: swing + START, no STOP.
            val instaState = mine.world.getBlockState(pos)
            if (!instaState.isAir) {
                val heldInsta = mine.vanillaProgress(pos, instaState, mine.player.mainHandItem) >= 1.0f
                // A tool-dependent insta-break only happens server-side when the tool is in
                // hand for the START (SwapTiming.ALWAYS); ONCE swaps at the STOP, so that dig
                // runs the normal path.
                val swapForInsta = !heldInsta && pickSlot != null &&
                    mine.swapTiming == SwapTiming.ALWAYS &&
                    mine.vanillaProgress(pos, instaState, pickSlot.itemStack) >= 1.0f
                // A refused container swap (Grim MultiActionsC, see switchTo) would leave the
                // server evaluating this START against the HELD item, which does not insta-break:
                // the server starts an ordinary dig while this action marks itself instaCompleted
                // and never sends a STOP, so the block would never break. Fall through to the
                // normal dig instead.
                if ((heldInsta || swapForInsta) && (!swapForInsta || mine.switchTo(pickSlot, -1))) {
                    // Grim NoSwingBreak: ANIMATION immediately before the START (the packet
                    // path bypasses the client swing cooldown).
                    SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
                    abortSent = false
                    // Answered with `destroyAndAck`, never a new `destroyPos` (see [serverDestroyPos]).
                    mine.instaStartInFlight = true
                    try {
                        mine.sequenced { seq ->
                            ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, startDirection, seq
                            )
                        }
                    } finally {
                        mine.instaStartInFlight = false
                    }
                    mine.lastBlockActionMs = System.currentTimeMillis()
                    if (swapForInsta) {
                        mine.switchBack(prevSlot)
                    }
                    // Same bookkeeping as a finished break (breakBlock), minus swap/STOP.
                    mine.unconfirmedBreaks[pos] = System.currentTimeMillis()
                    mine.lastBreakTime = System.currentTimeMillis()
                    mine.lastBreakedBlock = pos
                    mineBreaks++
                    progress = 0f
                    prevProgress = 0f
                    instaCompleted = true
                    rebreakable = false
                    // The START completed the dig (Grim AirLiquidBreak): no live dig pos.
                    digActive = false
                    return
                }
            }

            if (mine.swapTiming == SwapTiming.ALWAYS && mine.switchTool && pickSlot != null) {
                mine.switchTo(pickSlot, -1)
            }
            // No tool found: still send the START without the pre-swap — the break-time
            // switchTo in breakBlock will find the tool later, or the dig proceeds with
            // the held item. Only the pre-swap is skipped, the action never aborts here.

            sendStartSequence(startDirection)
            // The START is on the wire; the dig stays active until breakBlock/cancel.
            digActive = true

            if (mine.swapTiming == SwapTiming.ALWAYS && mine.switchTool) {
                mine.hotBarLock.set(true)
                if (mine.swapDelay) {
                    mine.schedule(1) {
                        mine.switchBack(prevSlot)
                        mine.hotBarLock.set(false)
                    }
                } else {
                    mine.switchBack(prevSlot)
                    mine.hotBarLock.set(false)
                }
            }

            progress = 0f
            prevProgress = 0f
            lastStartAt = System.currentTimeMillis()
        }

        /**
         * Sends the module's ABORT for [pos]. Vanilla ALWAYS sends ABORT_DESTROY_BLOCK with
         * [Direction.DOWN] (bytecode-confirmed): a non-DOWN ABORT arms Grim PositionBreakB
         * (STABLE), which then flags any following dig packet except a START with that same
         * face. Grim WrongBreak (STABLE) flags a second CANCELLED on the same pos without an
         * intermediate START, so a duplicate module ABORT is suppressed via [abortSent].
         * Raw packet, sequence 0 (BadPacketsH).
         */
        private fun sendAbort() {
            if (abortSent) return
            abortSent = true
            mine.send(
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN
                )
            )
        }

        private fun sendStartSequence(startDirection: Direction) {
            // Grim NoSwingBreak (experimental): every tick-window containing a dig packet
            // needs an ANIMATION — the packet-path swing bypasses the client swing cooldown,
            // so it is always on the wire immediately BEFORE the START.
            SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
            abortSent = false
            if (mine.doubleMine) {
                sendDoubleMineStart(startDirection)
            } else {
                mine.sequenced { seq ->
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, startDirection, seq
                    )
                }
                when (mine.startMode) {
                    // Both scheduled packets are skipped once the action has ended ([ended]).
                    StartMode.ABORT -> mine.schedule(1) {
                        if (!ended) {
                            sendAbort()
                        }
                    }
                    StartMode.STOP -> mine.schedule(1) {
                        sendScheduledStop(startDirection)
                    }
                    StartMode.NONE -> {}
                }
            }
            mine.lastBlockActionMs = System.currentTimeMillis()
        }

        /** The scheduled [StartMode.STOP] STOP, one tick after the START. */
        private fun sendScheduledStop(startDirection: Direction) {
            // An aura-held dig gets its STOP from the aura ([auraOwned]); this early one would charge the whole dig
            // time to Grim's FastBreak balance on top (`FastBreak`, LIVE). Checked when it runs: the flag is set
            // right after the START that scheduled it.
            if (ended || auraOwned) {
                return
            }
            // Grim PacketOrderI / MultiActionsF (experimental): the crystal aura's attack/place
            // shares this tick-window — retry next tick instead of dropping the STOP.
            if (mine.auraBusy()) {
                mine.schedule(1) { sendScheduledStop(startDirection) }
                return
            }
            // Grim NoSwingBreak (experimental): this window's dig packet needs
            // an ANIMATION of its own, immediately before the STOP.
            SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
            mine.sequenced { seq ->
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, startDirection, seq
                )
            }
            mine.lastBlockActionMs = System.currentTimeMillis()
        }

        private fun sendDoubleMineStart(startDirection: Direction) {
            // Order: vanilla START -> ABORT -> STOP. Not for Grim servers (see the
            // DoubleMine setting note): the instant STOP lands the full predicted dig time
            // on the FastBreak balance.
            mine.sequenced { seq ->
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, startDirection, seq
                )
            }
            sendAbort()
            // The server's verdict on this STOP, read before it goes: at 0.7 or more it destroys the block outright,
            // below that it arms its single delayed destroy here - unless one is armed already, which drops it.
            val state = mine.world.getBlockState(pos)
            val atOnce = !state.isAir &&
                mine.vanillaProgress(pos, state, mine.player.mainHandItem) >= SERVER_BREAK_THRESHOLD
            mine.sequenced { seq ->
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, startDirection, seq
                )
            }
            // Beyond reach the server drops the packets outright (`ServerPlayerGameMode.java:155`): nothing is armed.
            if (!atOnce && !state.isAir && mine.serverDelayed == null && mine.inReach(pos)) {
                mine.serverDelayed = pos
                mine.debugLine { "DoubleMine: the server finishes ${pos.toShortString()} by itself" }
            }
        }

        /**
         * DoubleMine: this cell is the server's delayed destroy ([serverDelayed]) and a later START moved `destroyPos`
         * off it, so a STOP of ours would be ignored (`ServerPlayerGameMode.java:219`). While it is still `destroyPos`
         * (no other START since) the ordinary STOP is the faster way and stays allowed.
         */
        val finishedByServer: Boolean
            get() = mine.doubleMine && !ended && !abandoned && pos == mine.serverDelayed && pos != mine.serverDestroyPos

        /**
         * DoubleMine: a live dig of ours on a standing cell that is neither the server's `destroyPos` nor its delayed
         * destroy - another START moved `destroyPos` away while the delayed slot was taken, so nothing will finish it.
         * [startMine] hands the free slot over before it can happen ([armServerDelayed]); what is left (a START of
         * another module's, a desync) is dropped.
         */
        private val orphaned: Boolean
            get() = mine.doubleMine && digActive && !ended && !abandoned && !instaCompleted &&
                pos != mine.serverDelayed && pos != mine.serverDestroyPos && !mine.world.isEmptyBlock(pos)

        /** Set when this dig's one tool hold ran out with the block still standing ([maintainDelayedHold]). */
        var holdExpired = false

        /**
         * DoubleMine: gives the server's free delayed destroy to this dig with an early STOP, before a START elsewhere
         * moves `destroyPos` off it. A dig already at 0.7 is simply destroyed by it (`ServerPlayerGameMode.java:224`).
         */
        fun armServerDelayed() {
            // Beyond reach the server drops the STOP (`ServerPlayerGameMode.java:155`) and the mirror would arm a cell
            // the server never will - every later dig would then read as an orphan.
            if (ended || abandoned || !digActive || !mine.inReach(pos)) return
            val state = mine.world.getBlockState(pos)
            if (state.isAir) return
            val elapsedTicks = ((System.currentTimeMillis() - lastStartAt) / MS_PER_TICK).toInt()
            val atOnce = mine.vanillaProgress(pos, state, mine.player.mainHandItem) * (elapsedTicks + 1) >=
                SERVER_BREAK_THRESHOLD
            // Grim NoSwingBreak (experimental): every window with a dig packet carries an ANIMATION before it.
            SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
            mine.sequenced { seq ->
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, mine.faceOf(pos), seq
                )
            }
            if (atOnce) {
                // Destroyed outright: the FINISH->START pacing counts from here like from any STOP.
                mine.lastStopMs = System.currentTimeMillis()
            } else {
                mine.serverDelayed = pos
                mine.debugLine { "DoubleMine: ${pos.toShortString()} handed to the server's delayed destroy" }
            }
        }

        /** Ended or dropped: nothing of this action's may go out any more. */
        val finished: Boolean
            get() = ended || abandoned

        /**
         * The server's delayed destroy of this cell is due with [tool] in the hand: `getDestroyProgress(tool) *
         * (ticks since our START + 1) >= 1.0` - the full 1.0, not the STOP's 0.7 (`ServerPlayerGameMode.java:116-117`).
         * Counted from OUR send time, so the server, which took the START a little later, reaches it a little later;
         * the hold covers that ([requestDelayedHold]).
         */
        private fun delayedDue(state: BlockState, tool: ItemSlot?): Boolean {
            if (state.isAir) return false
            val elapsedTicks = ((System.currentTimeMillis() - lastStartAt) / MS_PER_TICK).toInt()
            return mine.vanillaProgress(pos, state, mine.stackOf(tool)) * (elapsedTicks + 1) >= 1f
        }

        private fun shouldResend(now: Long): Boolean =
            progress == 0f && prevProgress == -1f && mine.mode == Mode.PACKET &&
                now - lastResendAt > mine.retryDelay &&
                // Grim FastBreak: even with RetryDelay = 0, a re-START waits >= 300 ms after
                // the last STOP (FINISH->START pacing).
                now - mine.lastStopMs >= 300L &&
                !(mineBreaks > 0 && mine.rebreak == Rebreak.GRIM &&
                    (pos == mine.lastClickedBlock || mine.actions.size <= 1))

        /** Advances mining; returns true when the action should be removed. */
        fun update(async: Boolean = false): Boolean {
            val remove = step(async)
            // Follow-ups go LAST: a regular STOP sent this tick takes the tick, and they wait for the next one.
            if (!remove && followUpStops > 0 && lastStopTick != mine.player.tickCount) {
                sendFollowUpStop()
            }
            return remove
        }

        @Suppress("ReturnCount", "CognitiveComplexMethod", "LongMethod")
        private fun step(async: Boolean): Boolean {
            if (abandoned) {
                // Already dropped without an ABORT, see [abandon].
                return true
            }

            // An aura-held dig sends no STOP of its own, so the `abandon()` of [breakBlock] for a dig whose
            // `destroyPos` another START moved away can never run for it. Done here instead, so the aura sees the dig
            // gone and starts it over rather than waiting on a STOP that would break nothing (`WrongBreak`).
            if (auraOwned && digActive && !mine.doubleMine && !mine.serverDigsAt(pos)) {
                mine.debugLog("drop") { "drop ${pos.toShortString()}: another dig moved the server off it" }
                abandon()
                return true
            }

            val dir = mine.faceOf(pos)
            val firstActive = mine.actions.firstOrNull { !mine.world.isEmptyBlock(it.pos) }
            val instantRemining = isInstantRemining()
            val tool = mine.toolSlot(pos)

            if (mineBreaks > 0 && mine.doubleMine && pos != mine.lastClickedBlock) {
                mine.debugLog("evict") { "drop ${pos.toShortString()}: DoubleMine keeps the last clicked block" }
                cancel()
                return true
            }

            // Rebreak = Grim: after our STOP the cell being EMPTY is the state a rebreak waits in, not a reason to
            // drop the action. [canBreak] below answers false for air, and it used to run first, so the action was
            // removed the moment the client saw the break - and when the block came back there was nothing left to
            // rebreak it. The rebreak only ever fired when the break and the new block arrived inside one client
            // tick, and the "block is gone" branch further down could never run at all.
            if (rebreakable && instantRemining && mine.world.isEmptyBlock(pos)) {
                if (!mine.inReach(pos)) {
                    mine.debugLog("drop") { "drop ${pos.toShortString()}: out of reach while waiting to rebreak" }
                    cancel()
                    return true
                }
                if (!mine.serverDigsAt(pos)) {
                    mine.debugLog("drop") { "drop ${pos.toShortString()}: another dig moved the server off it" }
                    abandon()
                    return true
                }
                digActive = false
                return false
            }

            if (!mine.canBreak(pos)) {
                mine.debugLog("drop") {
                    val gone = mine.world.getBlockState(pos).isAir
                    "drop ${pos.toShortString()}: ${if (gone) "the block is gone" else "out of reach or unbreakable"}"
                }
                cancel()
                return true
            }

            if (mine.world.isEmptyBlock(pos)) {
                progress = 0f
                prevProgress = -1f
                groundStates.add(mine.player.onGround())
                instaCompleted = false
                // The block is gone: no live dig pos until the resend re-STARTs it.
                digActive = false
                return pos != mine.lastClickedBlock && mine.actions.size > 1
            }

            // The insta-break gate already finished this dig with its START (Grim
            // AirLiquidBreak): the block is broken server-side, so no progress accumulates
            // and no STOP is sent — the action only waits for the block update.
            if (instaCompleted) {
                return false
            }

            // DoubleMine: the server finishes this block by itself (see the `DoubleMine` setting). A STOP of ours
            // would be ignored - `destroyPos` is elsewhere - so none is sent, the aura's included; what it needs is
            // the tool in the hand on a server tick once its time has come, and that is [requestDelayedHold].
            if (orphaned) {
                // Neither `destroyPos` nor the delayed destroy: nothing will ever finish it. Dropped without a
                // packet; a held click STARTs it again.
                mine.debugLine { "DoubleMine: ${pos.toShortString()} has no dig on the server any more, dropped" }
                abandon()
                return true
            }
            if (finishedByServer) {
                if (holdExpired) {
                    // One hold per dig: the server may still finish it the next time a tool is in the hand.
                    abandon()
                    return true
                }
                val state = mine.world.getBlockState(pos)
                if (progress < 1f) {
                    prevProgress = progress
                    progress += mine.strengthOf(pos, state, mine.stackOf(tool), mine.player.onGround())
                }
                if (delayedDue(state, tool)) {
                    mine.requestDelayedHold(this, tool, System.currentTimeMillis() - lastStartAt)
                }
                return false
            }

            val now = System.currentTimeMillis()
            if (shouldResend(now)) {
                if (groundStates.isEmpty() || mine.rebreak != Rebreak.NCP_AIR) {
                    mine.player.swing(InteractionHand.MAIN_HAND)
                    start(dir)
                    lastResendAt = now
                } else {
                    val state = mine.world.getBlockState(pos)
                    groundStates.forEach {
                        prevProgress = progress
                        progress += mine.strengthOf(pos, state, tool?.itemStack, it)
                    }
                }
                return false
            }

            // No tool anywhere - no pickaxe in the kit, or the best one is under
            // `DurabilityThreshold` - used to `return false` right here, which accumulated no
            // progress and therefore never sent a STOP. Since [attackHandler] cancels
            // `BlockAttackEvent` unconditionally, vanilla's own dig never starts either
            // (`MultiPlayerGameMode.continueDestroyBlock` falls through to `startDestroyBlock`,
            // which the mixin answers with `false`), so the block was simply unbreakable for as
            // long as the module was on. [start] already promises the opposite ("the dig proceeds
            // with the held item"); this is where that promise was broken.
            val digStack = mine.stackOf(tool)

            if (mine.player.isUsingItem && mine.pauseEat == OnEating.GRIM) {
                prevProgress = progress
                progress += mine.strengthOf(pos, mine.world.getBlockState(pos), digStack, mine.player.onGround())
                return false
            }

            if (progress >= 1 || instantRemining) {
                // While the crystal aura holds this dig it sends every STOP of it itself ([finishForAura]). Filing ours
                // as well would leave a rotation request behind whose `whenReached` - single use, never consumed while
                // the aura outranks it - fires the first tick the aura asks for no rotation: a SECOND STOP for a cell
                // the aura already finished, into the air Grim predicts until the server answers (`AirLiquidBreak`,
                // LIVE). Keyed on the flag and not on `mineBreaks`: a STOP of the aura's that did not take leaves
                // `mineBreaks` at 1 with the aura still on the cell. The aura clears the flag when it is done with the
                // cell ([handBackFromAura]) or drops the dig ([releaseForAura]); rebreaks run as for any dig after
                // that.
                if (auraOwned) {
                    return false
                }
                val activeCount = mine.actions.count { !mine.world.isEmptyBlock(it.pos) }
                val isBlockedDoubleMine = firstActive != null && firstActive == this && activeCount == 2
                if (!isBlockedDoubleMine) {
                    val state = mine.world.getBlockState(pos)
                    // Instant remining is precisely the rebreak of an unconfirmed position,
                    // so it must bypass the await gate.
                    val canBreakNow = !state.isAir && !state.liquid() &&
                        (!mine.await || !mine.unconfirmedBreaks.containsKey(pos) || instantRemining)
                    // A rebreak skips `Await` above, so it needs its own guard: see [rebreakVerdict].
                    if (canBreakNow && instantRemining) {
                        when (rebreakVerdict(now)) {
                            RebreakVerdict.DROP -> {
                                abandon()
                                return true
                            }
                            RebreakVerdict.WAIT -> return false
                            RebreakVerdict.GO -> Unit
                        }
                    }
                    if (canBreakNow) {
                        if (mine.rotate) {
                            val rotation = mine.digRotation(pos, state)
                            // The break runs once the rotation is on the wire: the dig
                            // direction is raytraced inside doBreak against the applied
                            // rotation (Grim PositionBreakA). The async (drained
                            // instant-remine) path takes the same rotation route — the
                            // 1-tick delay keeps the aim on the wire for the dig packets
                            // (Grim RotationBreak, experimental).
                            val doBreak: () -> Unit = {
                                breakBlock(
                                    tool, mine.restingSlot(), mine.faceOf(pos),
                                    rebreak = instantRemining,
                                )
                            }
                            RotationManager.setRotationTarget(
                                rotation,
                                considerInventory = false,
                                valueGroup = mine.rotations,
                                priority = mine.timingPriority(),
                                provider = ModuleSpeedMine,
                                whenReached = RestrictedSingleUseAction({ true }, doBreak),
                            )
                        } else {
                            // Rotate off: user's choice — dig packets go out without the aim
                            // (Grim RotationBreak, experimental), same exposure class as
                            // Rotate=false elsewhere. The face falls back to the
                            // eye-facing direction on a raytrace miss (PositionBreakA).
                            breakBlock(
                                tool, mine.restingSlot(), mine.faceOf(pos),
                                rebreak = instantRemining,
                            )
                        }
                    }
                }
            } else {
                prevProgress = progress
                progress += mine.strengthOf(pos, mine.world.getBlockState(pos), digStack, mine.player.onGround())
            }
            return false
        }

        /**
         * Why the aura may NOT finish this dig now with [stack] in the hand, or null when it may - the gates of
         * [breakBlock], run for [stack] instead of the hand and without a single side effect, so the aura can ask
         * BEFORE it spends a tick on the STOP:
         * - nothing ended the dig, the insta-break gate did not already complete it, DoubleMine does not hold it,
         *   `Await` does not hold it;
         * - the block still stands, and the last START/ABORT on the wire is ours on this cell ([serverDigsAt],
         *   `WrongBreak`);
         * - the dig is full at this module's own pace, for [stack] ([timingBlocker]);
         * - the FastBreak mirror has room for it ([estFastBreakBalance]);
         * - the server would accept it: `getDestroyProgress(stack) * (ticksSpentDestroying + 1) >= 0.7`
         *   (`ServerPlayerGameMode.handleBlockBreakAction`, STOP branch). [breakBlock] asks this of the hand the
         *   server really has; here it is asked of the stack the aura is about to put there.
         */
        @Suppress("ReturnCount")
        fun auraFinishBlocker(stack: ItemStack): String? {
            if (abandoned || ended) return "action ended"
            if (instaCompleted) return "broken on its START"
            if (finishedByServer) return "the server finishes it (DoubleMine)"
            if (orphaned) return "no dig on the server any more (DoubleMine)"
            if (blockedByDoubleMine()) return "DoubleMine holds it"
            if (mine.await && mine.unconfirmedBreaks.containsKey(pos)) return "awaiting the previous break"

            val state = mine.world.getBlockState(pos)
            if (state.isAir || state.liquid()) return "the block is gone"
            if (!mine.doubleMine && !mine.serverDigsAt(pos)) return "another dig moved the server off it"

            return timingBlocker(state, stack)
        }

        /**
         * The timing gates of [auraFinishBlocker], all for [stack]:
         * - the dig is full at this module's own pace - `strengthOf` (`Factor` included, the current ground state, as
         *   the server reads it at the STOP) times the ticks since the START reaching 1. Not [progress]: that one is
         *   accumulated for the tool THIS module would pick ([toolSlot]), which is not the aura's - and is the item in
         *   the hand, i.e. the aura's crystal, whenever `SwitchTool` is off or the durability filter refuses the pick;
         * - the FastBreak mirror has room for it;
         * - the server's own 0.7 test passes.
         */
        private fun timingBlocker(state: BlockState, stack: ItemStack): String? {
            val now = System.currentTimeMillis()
            val elapsedTicks = ((now - lastStartAt) / MS_PER_TICK).toInt()
            val own = mine.strengthOf(pos, state, stack, mine.player.onGround()) * (elapsedTicks + 1)
            if (own < 1f) return "progress ${"%.2f".format(own)}"

            val damage = mine.vanillaProgress(pos, state, stack)
            if (damage <= 0f) return null

            if (damage < 1f) {
                val diff = ceil(1.0 / damage) * MS_PER_TICK - (now - lastStartAt)
                if (diff > FAST_BREAK_TOLERANCE_MS && mine.estFastBreakBalance + diff > FAST_BREAK_BUDGET) {
                    return "FastBreak budget"
                }
            }
            val reached = damage * (elapsedTicks + 1)
            return if (reached < SERVER_BREAK_THRESHOLD) {
                "the server would refuse it yet (${"%.2f".format(reached)} of 0.70)"
            } else {
                null
            }
        }

        fun isInstantRemining() = mineBreaks > 0 && mine.rebreak == Rebreak.GRIM &&
            // hellhit additionally ORs its WorldCacheService lock here; LiquidBounce has
            // no equivalent world cache, so that clause is omitted.
            (!mine.cev(pos) || !mine.awaitAutoCrystal) &&
            (pos == mine.lastClickedBlock || mine.actions.size <= 1)

        private enum class RebreakVerdict { GO, WAIT, DROP }

        /**
         * Whether a rebreak STOP may leave now. A rebreak skips `Await`, so these are its only gates:
         * - the last START/ABORT on the wire must still be ours on this cell ([serverDigsAt]), or the STOP breaks
         *   nothing and trips Grim `WrongBreak` - DROP;
         * - the server must have answered our previous STOP ([serverAnsweredLastStop]) - WAIT;
         * - [rebreakSpeed] - WAIT.
         */
        @Suppress("ReturnCount")
        private fun rebreakVerdict(now: Long): RebreakVerdict {
            if (!mine.serverDigsAt(pos)) {
                mine.debugLog("rebreak") { "drop ${pos.toShortString()}: another dig moved the server off it" }
                return RebreakVerdict.DROP
            }
            if (!serverAnsweredLastStop(now)) {
                mine.debugLog("rebreak") {
                    "hold ${pos.toShortString()}: the server has not answered the last STOP yet"
                }
                return RebreakVerdict.WAIT
            }
            if (!mine.rebreakRateAllows(now)) {
                mine.debugLog("rebreak") { "hold ${pos.toShortString()}: RebreakSpeed ${mine.rebreakSpeed}/s" }
                return RebreakVerdict.WAIT
            }
            return RebreakVerdict.GO
        }

        /**
         * Whether the server's answer to this action's last STOP has been processed ([BlockChangeAcks]).
         *
         * Until then the client and Grim disagree about the cell. Grim turns it to air the moment it reads our
         * FINISH (`events/packets/CheckManagerListener.java:786-790`) and, while that prediction is open, a block
         * update from the server only replaces the remembered state, not the world
         * (`utils/latency/CompensatedWorld.java:286-289`); it is resolved on the ack
         * (`BasePacketWorldReader.java:68-73` -> `CompensatedWorld.handlePredictionConfirmation`). The client keeps
         * drawing the old block for that whole round trip. A rebreak skips `Await`, so without this gate it sent a
         * STOP every tick of that window into a cell Grim holds as air - `AirLiquidBreak`, LIVE, which cancels
         * (`checks/impl/breaking/AirLiquidBreak.java:58-75`). After the ack the client world IS the server's
         * answer, so a block standing there then is really back.
         *
         * [ACK_TIMEOUT_MS] only covers a STOP that never produced an ack (cancelled by another module) and nothing
         * sequenced followed it.
         */
        private fun serverAnsweredLastStop(now: Long): Boolean =
            stopSequence < 0 || BlockChangeAcks.isProcessed(mine.world, stopSequence) ||
                now - stopSentAt > ACK_TIMEOUT_MS

        /**
         * Drops the action WITHOUT an ABORT, for when another dig took the server's `destroyPos` away. An ABORT for
         * our cell now would land on THEIR dig: vanilla clears `isDestroyingBlock` on any ABORT
         * (`ServerPlayerGameMode.java:241-250`), and Grim `WrongBreak` would then read their next FINISH against
         * our cell (`checks/impl/breaking/WrongBreak.java:60-98`).
         */
        fun abandon() {
            abandoned = true
            ended = true
            groundStates.clear()
            digActive = false
        }

        /**
         * One blind follow-up STOP ([rebreakStops] above 1, NOT Grim-safe - see the setting). Sent whatever the
         * client shows at the cell, without the ack gate and without a rotation. Still refused when it could only do
         * harm or nothing: another dig owns the server's `destroyPos` ([serverDigsAt] - `WrongBreak`, and the
         * server would ignore it), the cell is out of reach, the crystal aura owns this tick window, or the tool
         * could not be put in the hand (then retried next tick, not counted).
         */
        @Suppress("ReturnCount")
        private fun sendFollowUpStop() {
            // isInstantRemining also carries AwaitCrystalAura: no remining a block hovering over the aura's target.
            if (ended || abandoned || !isInstantRemining() || !mine.inReach(pos) || !mine.serverDigsAt(pos)) {
                followUpStops = 0
                return
            }
            // Same pauses as the regular path: the crystal aura's tick window, and OnEating = Grim while eating.
            if (mine.auraBusy() || (mine.player.isUsingItem && mine.pauseEat == OnEating.GRIM)) {
                return
            }
            // The server times the STOP against the item in the hand: while the cell is air there is no block to
            // pick a tool for, so it is picked for the block the cell held when we broke it.
            val state = mine.world.getBlockState(pos)
            val tool = if (state.isAir) dugState?.let { mine.toolFor(it) } else mine.toolSlot(pos)
            val prevSlot = mine.restingSlot()
            if (!mine.switchTo(tool, -1)) {
                return
            }

            SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
            mine.sequenced { seq ->
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, mine.faceOf(pos), seq
                )
            }
            mine.lastStopMs = System.currentTimeMillis()
            mine.lastBlockActionMs = mine.lastStopMs
            lastStopTick = mine.player.tickCount
            followUpStops--
            mine.debugLine { "follow-up STOP ${pos.toShortString()}, $followUpStops left" }
            mine.switchBack(prevSlot)
        }

        private fun blockedByDoubleMine(): Boolean {
            val standing = mine.actions.filter { !mine.world.isEmptyBlock(it.pos) }
            return standing.size == 2 && standing.first() === this
        }

        /**
         * The per-frame rebreak ([frameHandler]). Same gates as the tick path in [update]. With `Rotate` it fires
         * only when the rotation already on the wire hits the block; otherwise the tick path aims and breaks as
         * before, so the frame path only saves time when the aim is already there.
         */
        @Suppress("ReturnCount")
        fun rebreakFromFrame(now: Long) {
            if (abandoned || instaCompleted || !isInstantRemining() || blockedByDoubleMine()) {
                return
            }
            val state = mine.world.getBlockState(pos)
            if (!mine.canBreak(pos) || state.liquid()) {
                return
            }
            if (rebreakVerdict(now) != RebreakVerdict.GO) {
                return
            }
            if (mine.rotate && raytraceBlock(
                    range = mine.player.blockInteractionRange(),
                    rotation = RotationManager.serverRotation,
                    pos = pos,
                    state = state,
                ) == null
            ) {
                return
            }
            breakBlock(mine.toolSlot(pos), mine.restingSlot(), mine.faceOf(pos), rebreak = true)
        }

        /**
         * Sends the regular STOP once every gate passes. Null when it went out, otherwise the reason it was held -
         * read by [finishForAura]; the module's own callers ignore it and retry from [step] as before.
         *
         * [auraOwnsTick]: the crystal aura calls this from a tick it planned NO other click in, so the
         * [auraBusy] window - which exists to keep this STOP out of a tick the aura acted in - would only read the
         * aura's PREVIOUS tick (two ticks can fall inside 40 ms when the client catches up) and is skipped.
         */
        @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
        internal fun breakBlock(
            tool: ItemSlot?,
            prevSlot: Int,
            dir: Direction,
            rebreak: Boolean = false,
            auraOwnsTick: Boolean = false,
        ): String? {
            // `ended` as well: an action that was cancelled or released may still have a `whenReached` of ours
            // pending in `RotationManager`, and a STOP from it would land on a cell that is no longer ours.
            if (abandoned || ended) {
                return "action ended"
            }
            // Ignored by the server (`destroyPos` is elsewhere) and `WrongBreak` on Grim; see [finishedByServer].
            if (finishedByServer) {
                return "the server finishes it (DoubleMine)"
            }

            // Grim PacketOrderI / MultiActionsF (experimental): the crystal aura's attack/place
            // shares this tick-window — defer the whole break; update() retries next tick.
            if (!auraOwnsTick && mine.auraBusy()) {
                mine.debugLog("aura") { "hold ${pos.toShortString()}: the crystal aura owns this tick window" }
                return "the crystal aura owns this tick window"
            }

            // The break may run a tick after it was decided (whenReached) or from the frame path, so the cell is
            // read again here: a STOP into a cell the client shows as air or liquid digs nothing on the server and is
            // Grim `AirLiquidBreak` (LIVE, cancels, `checks/impl/breaking/AirLiquidBreak.java:58-75`).
            val state = mine.world.getBlockState(pos)
            if (state.isAir || state.liquid()) {
                mine.debugLog("gone") { "hold ${pos.toShortString()}: the block is gone" }
                return "the block is gone"
            }

            // Every STOP rides on the `destroyPos` our START left on the server (see [lastDigAnchor]); once another
            // dig moved it, the STOP breaks nothing and trips `WrongBreak`. DoubleMine is exempt by design: its
            // first cell is finished by the server's own `hasDelayedDestroy` while the second one is dug.
            if (!mine.doubleMine && !mine.serverDigsAt(pos)) {
                mine.debugLog("drop") { "drop ${pos.toShortString()}: another dig moved the server off it" }
                abandon()
                return "another dig moved the server off it"
            }

            if (rebreak && rebreakVerdict(System.currentTimeMillis()) != RebreakVerdict.GO) {
                return "rebreak held"
            }

            // Grim FastBreak (STABLE), finish branch of FastBreak.java: the server predicts
            // the dig time as ceil(1/damage) ticks (predictedMs = ceil(1/damage) * 50) and
            // compares it to the real dig time; breaking more than 25 ms faster adds the
            // difference to a balance that flags at >1000 (<=25 ms decays it by 0.9).
            // Mirror it here and defer the break to a later tick while the budget would
            // exceed 800 (margin below the threshold) — this paces sustained mining with
            // factor > 1 to the fastest flag-free rate.
            val vanillaDamage = mine.vanillaProgress(pos, state, mine.stackOf(tool))
            val fastBreakDiff = if (rebreak && !mine.rebreakFastBreakCheck) {
                // RebreakFastBreakCheck off: Grim predicts 0 ms for a rebreak (see the setting), i.e. the
                // "close enough" case - no hold, and the balance decays instead of being charged.
                0.0
            } else if (vanillaDamage > 0f && vanillaDamage < 1f) {
                ceil(1.0 / vanillaDamage) * 50.0 - (System.currentTimeMillis() - lastStartAt)
            } else {
                null
            }
            if (fastBreakDiff != null && fastBreakDiff > FAST_BREAK_TOLERANCE_MS &&
                mine.estFastBreakBalance + fastBreakDiff > FAST_BREAK_BUDGET
            ) {
                mine.debugLog("budget") {
                    "hold ${pos.toShortString()}: FastBreak budget " +
                        "${mine.estFastBreakBalance.toInt()} + ${fastBreakDiff.toInt()} ms over 800"
                }
                return "FastBreak budget"
            }

            // The server evaluates the STOP against the item in the main hand AT THAT MOMENT
            // (`ServerPlayerGameMode.handleBlockBreakAction`, STOP branch), so a swap that was
            // refused - a container click is skipped while moving, Grim MultiActionsC - means the
            // dig was timed for a tool the server will not see. Sending anyway is worse than
            // waiting: the STOP lands under 0.7, the server arms `hasDelayedDestroy` and finishes
            // the block at the full vanilla time, which reads in game as "SpeedMine does nothing".
            if (!mine.switchTo(tool, -1)) {
                mine.debugLog("swap") { "hold ${pos.toShortString()}: tool swap refused (moving)" }
                return "tool swap refused (moving)"
            }

            // The server's own acceptance test, run before the packet instead of after it
            // (`ServerPlayerGameMode.handleBlockBreakAction`, STOP branch):
            //
            //     getDestroyProgress(player, level, pos) * (ticksSpentDestroying + 1) >= 0.7F
            //
            // evaluated against the item the SERVER believes is in the main hand at that moment.
            // `player.mainHandItem` IS that item: `MixinInventory` redirects `getSelectedItem` to
            // `SilentHotbar.serversideSlot`, so this also covers the case the switch above cannot
            // see - our hotbar switch never reaching the wire because ANOTHER module holds a silent
            // slot, which makes `ensureHasSentCarriedItem` announce that module's slot and not ours
            // (AGENTS.md, "Slot changes").
            //
            // A STOP that fails this test is not merely wasted. The server arms `hasDelayedDestroy`
            // and finishes the block itself at the FULL vanilla time, and while that flag is up the
            // next premature STOP on any other block is dropped outright (`if (!this.hasDelayedDestroy)`,
            // same method), so one wrong STOP costs more than one block. Waiting a tick costs 50 ms.
            val serverDamage = mine.vanillaProgress(pos, state, mine.player.mainHandItem)
            val elapsedTicks = ((System.currentTimeMillis() - lastStartAt) / MS_PER_TICK).toInt()
            // A zero reading means the block is not breakable at all by this hand, which is not
            // something waiting can fix: fall through rather than spin on a gate that can never open.
            if (serverDamage > 0f && serverDamage * (elapsedTicks + 1) < SERVER_BREAK_THRESHOLD) {
                mine.debugLog("early") {
                    "hold ${pos.toShortString()}: the server would refuse this STOP - " +
                        "${mine.player.mainHandItem.hoverName.string} in hand server-side gives " +
                        "${"%.3f".format(serverDamage * (elapsedTicks + 1))} after $elapsedTicks ticks, " +
                        "needs 0.70 (timed for ${mine.stackOf(tool).hoverName.string})"
                }
                return "the server would refuse it"
            }

            // Only now, past every gate: a break that was deferred costs the server nothing and
            // must cost the budget nothing either. Moving the estimate up where it used to sit
            // would charge every retry of the same dig again and inflate the mirror into a
            // permanent refusal.
            if (fastBreakDiff != null) {
                if (fastBreakDiff > FAST_BREAK_TOLERANCE_MS) {
                    mine.estFastBreakBalance += fastBreakDiff.toFloat()
                } else {
                    mine.estFastBreakBalance *= 0.9f
                }
            }

            mine.hotBarLock.set(true)

            // Grim NoSwingBreak: this window's ANIMATION precedes the STOP — and the NCP_AIR
            // tail START below, which therefore needs no swing of its own. The packet path
            // bypasses the client swing cooldown; the visual player.swing below stays as-is.
            SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
            mine.sequenced { seq ->
                stopSequence = seq
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir, seq
                )
            }
            // Grim FastBreak measures FINISH->START spacing; startMine/shouldResend pace the
            // next START off this timestamp.
            mine.lastStopMs = System.currentTimeMillis()
            mine.lastBlockActionMs = mine.lastStopMs
            stopSentAt = mine.lastStopMs
            rebreakable = true
            lastStopTick = mine.player.tickCount
            dugState = state
            // A new series of blind follow-ups after every regular STOP ([rebreakStops], Rebreak = Grim only) - but
            // not on a dig the crystal aura drives ([auraOwned]): those ticks belong to the crystal going in.
            followUpStops = if (mine.rebreak == Rebreak.GRIM && !auraOwned) mine.rebreakStops - 1 else 0
            if (rebreak) {
                mine.spendRebreak(mine.lastStopMs)
            }
            mine.debugLine {
                val real = mine.lastStopMs - lastStartAt
                "${if (rebreak) "rebreak" else "break"} ${pos.toShortString()} after $real ms, timed for " +
                    "${mine.stackOf(tool).hoverName.string}, server sees " +
                    "${mine.player.mainHandItem.hoverName.string} " +
                    "(${"%.2f".format(serverDamage * (elapsedTicks + 1))} of 0.70), " +
                    "budget ${mine.estFastBreakBalance.toInt()}"
            }
            // The FINISH is out: no live dig pos from here on (Grim WrongBreak arbitration).
            digActive = false

            if (mine.rebreak == Rebreak.NCP_AIR) {
                sendAbort()
                mine.sequenced { seq ->
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir, seq
                    )
                }
                // NOT Grim-safe (NCP mode, see the Rebreak enum note): this tail START lands
                // ~0 ms after the FINISH (+300 FastBreak balance per block) and the tail
                // STOP+START dig the air Grim predicts after the FINISH (AirLiquidBreak on
                // 1.19+). There is no intra-burst exemption in Grim's FINISH->START check.
                abortSent = false
                mine.sequenced { seq ->
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir, seq
                    )
                }
                mine.lastBlockActionMs = System.currentTimeMillis()
            }

            groundStates.clear()
            mine.sendSwing()

            val delay = if ((mine.doubleMine && mineBreaks < 1) || mine.swapDelay) 1 else 0
            if (delay != 0) {
                mine.pendingSwitchBack = prevSlot
                mine.schedule(delay) { mine.switchBack(prevSlot) }
            } else {
                mine.switchBack(prevSlot)
            }

            mine.lastBreakTime = System.currentTimeMillis()
            mine.lastBreakedBlock = pos

            mineBreaks++
            mine.unconfirmedBreaks[pos] = System.currentTimeMillis()

            prevProgress = 0f
            progress = prevProgress

            if (mine.replace && !mine.hotBarLock.get() && !mine.doubleMine) {
                if (mine.replaceBlock(pos)) {
                    mine.stopMining(pos)
                }
            }
            return null
        }

        /** Reset progress and re-open the break (used by reset-on-switch). */
        fun reset(reason: ResetReason) {
            if (abandoned) {
                // Another dig owns the server's destroyPos: no ABORT or START of ours, see [abandon].
                return
            }
            groundStates.clear()
            val dir = mine.faceOf(pos)

            if (reason == ResetReason.SWITCH && mine.rebreak == Rebreak.NCP_AIR && mine.world.isEmptyBlock(pos)) {
                progress = 0f
                prevProgress = progress
                // Grim NoSwingBreak: ANIMATION immediately before the START (packet path,
                // no client swing cooldown).
                SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
                abortSent = false
                mine.sequenced { seq ->
                    ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir, seq
                    )
                }
                mine.lastBlockActionMs = System.currentTimeMillis()
                digActive = true
                return
            }

            if (progress == 0f && prevProgress == 0f) return

            progress = 0f
            prevProgress = progress
            sendAbort()
            start(dir)
        }

        fun cancel() {
            ended = true
            groundStates.clear()
            digActive = false
            if (!abandoned && (progress != 0f || prevProgress != 0f)) {
                sendAbort()
            }
        }
    }

    /** Rate limit of the per-key [debug] lines. */
    private const val DEBUG_INTERVAL_MS = 500L

    /**
     * The share of a dig the server really requires, `ServerPlayerGameMode.handleBlockBreakAction`:
     * `destroyProgress >= 0.7F` on the STOP. This, not 1.0, is the margin the module lives on.
     */
    private const val SERVER_BREAK_THRESHOLD = 0.7f

    /**
     * DoubleMine: how long a tool hold lasts past twice our ping ([requestDelayedHold]). Twenty ticks: the server
     * checks once per tick, so a few would do on a steady connection; the rest absorbs jitter. A hold that runs out
     * drops its dig - the server may still finish the block the next time a tool is in the hand, but we do not hold
     * again.
     */
    private const val DELAYED_HOLD_SLACK_MS = 1000L

    /** DoubleMine: the share of the dig's own length the hold adds when that is more than [DELAYED_HOLD_SLACK_MS]. */
    private const val DELAYED_HOLD_SLACK_SHARE = 15L

    private const val MS_PER_TICK = 50L

    private const val MS_PER_SECOND = 1000L

    /** Client ticks per second: the most rebreaks the tick path can send, and the Grim-safe [rebreakSpeed]. */
    private const val MAX_TICK_REBREAKS = 20

    /** [rebreakSpeed] ceiling; everything above [MAX_TICK_REBREAKS] runs per frame and is NOT Grim-safe. */
    private const val MAX_REBREAK_SPEED = 50

    /** [rebreakStops] ceiling. */
    private const val MAX_REBREAK_STOPS = 20

    /** See [MineAction.serverAnsweredLastStop]: a fallback for a STOP that never produced an ack. */
    private const val ACK_TIMEOUT_MS = 2000L

    /**
     * Grim `FastBreak`'s finish branch: a dig more than this many ms faster than its predicted time is charged to the
     * balance, anything closer decays it by 0.9 (see [MineAction.breakBlock]).
     */
    private const val FAST_BREAK_TOLERANCE_MS = 25

    /** The mirror's ceiling for [estFastBreakBalance]; Grim flags above 1000 (see [MineAction.breakBlock]). */
    private const val FAST_BREAK_BUDGET = 800

    enum class Mode(override val tag: String) : Tagged {
        PACKET("Packet"),
        DAMAGE("Damage"),
    }

    enum class Timing(override val tag: String) : Tagged {
        VANILLA("Vanilla"),
        SEQUENTIAL("Sequential"),
        GRIM("Grim"),
    }

    enum class Rebreak(override val tag: String) : Tagged {
        OFF("Off"),
        GRIM("Grim"),
        // NOT for Grim servers — NCP mode. The tail START lands ~0 ms after the FINISH
        // (+300 FastBreak balance per block, STABLE), and the tail STOP+START dig the air
        // Grim predicts immediately after a FINISH (AirLiquidBreak, STABLE, 1.19+); plus
        // MultiBreak / MultiActions exposure with experimental-checks: true.
        NCP_AIR("NCPAir"),
    }

    enum class StartMode(override val tag: String) : Tagged {
        ABORT("Abort"),
        STOP("Stop"),
        NONE("None"),
    }

    enum class SwitchMode(override val tag: String) : Tagged {
        Silent("Silent"),
        Normal("Normal"),
        Alternative("Alternative"),
    }

    enum class SwapTiming(override val tag: String) : Tagged {
        ONCE("Once"),
        ALWAYS("Always"),
    }

    enum class OnEating(override val tag: String) : Tagged {
        PAUSE("Pause"),
        GRIM("Grim"),
        RESET("Reset"),
        NONE("None"),
    }

    enum class RenderMode(override val tag: String) : Tagged {
        BLOCK("Block"),
        GROW("Grow"),
        SHRINK("Shrink"),
    }

    enum class ResetReason {
        SWITCH,
        TOGGLE,
        EATING,
    }
}
