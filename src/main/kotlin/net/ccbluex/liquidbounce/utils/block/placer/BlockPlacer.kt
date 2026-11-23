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
package net.ccbluex.liquidbounce.utils.block.placer

import it.unimi.dsi.fastutil.longs.Long2BooleanLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.ccbluex.fastutil.fastIterator
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.render.FULL_BOX
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.getCenterDistanceSquaredEyes
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.block.isBlockedByEntitiesReturnCrystal
import net.ccbluex.liquidbounce.utils.block.isInteractable
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockOffsetOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTargetFindingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.CenterTargetPositionFactory
import net.ccbluex.liquidbounce.utils.block.targetfinding.FaceHandlingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.PlayerLocationOnPlacement
import net.ccbluex.liquidbounce.utils.block.targetfinding.findBestBlockPlacementTarget
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.collection.getSlot
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.raytracing.raytraceBlock
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@Suppress("TooManyFunctions")
class BlockPlacer(
    name: String,
    val module: ClientModule,
    val priority: Priority,
    val slotFinder: (BlockPos?) -> HotbarItemSlot?,
    allowSupportPlacements: Boolean = true
) : ValueGroup(name), EventListener {

    val range by float("Range", 4.5f, 1f..6f)
    val wallRange by float("WallRange", 4.5f, 0f..6f)
    val cooldown by intRange("Cooldown", 1..2, 0..40, "ticks")
    val swingMode by enumChoice("Swing", SwingMode.DO_NOT_HIDE)

    /**
     * Construct a center hit result when the raytrace result is invalid.
     * This can make the module rotations wrong as well as place a bit outside the range,
     * but it makes the placements a lot more reliable and works on most servers.
     */
    val constructFailResult by boolean("ConstructFailResult", true)

    /**
     * Defines how long the player should sneak when placing on an interactable block.
     * This can make placing multiple blocks seem smoother.
     */
    private val sneak by intRange("Sneak", 1..1, 0..10, "ticks")

    private val ignores by multiEnumChoice("Ignore", Ignore.entries)

    val ignoreOpenInventory get() = Ignore.OPEN_INVENTORY in ignores

    val ignoreUsingItem get() = Ignore.USING_ITEM in ignores

    val slotResetDelay by intRange("SlotResetDelay", 4..6, 0..40, "ticks")

    val rotationMode = modes(this, "RotationMode") {
        arrayOf(NormalRotationMode(it, this), NoRotationMode(it, this))
    }

    val support = SupportFeature(this)

    init {
        if (allowSupportPlacements) {
            tree(support)
        } else {
            support.enabled = false
        }
    }

    /**
     * Never below `IMPORTANT_FOR_USAGE_3`, the priority it always had, and never below this placer's own: the
     * destroyer clears the cells the placer wants, so it may not lose the aim to a module the placer outranks.
     * Only the Surround's placer is above `_3` today. Placer and destroyer share one rotation provider (the module),
     * and `RequestHandler.request` drops a provider's older request, so raising this does not change which of the
     * two wins between themselves - the later request, as before.
     */
    val crystalDestroyer = tree(CrystalDestroyFeature(
        this,
        module,
        if (priority.priority > Priority.IMPORTANT_FOR_USAGE_3.priority) priority else Priority.IMPORTANT_FOR_USAGE_3
    ))

    /**
     * Renders all tracked positions that are queued to be placed.
     */
    val targetRenderer = tree(PlacementRenderer("TargetRendering", false, module))

    /**
     * Renders all placements.
     */
    val placedRenderer = tree(PlacementRenderer(
        "PlacedRendering",
        true,
        module,
        keep = false
    ))

    private val blockPosCache = BlockPos.MutableBlockPos()

    /**
     * Stores all block positions where blocks should be placed paired with a boolean that is `true`
     * if the position was added by [support].
     */
    val blocks = Long2BooleanLinkedOpenHashMap()

    private val inaccessible = LongOpenHashSet()
    var ticksToWait = 0
    var ranAction = false
    private var sneakTimes = 0

    /**
     * Whether a `UseItemOn` / `UseItem` of this placer went out in the CURRENT Grim tick window, i.e. after the last
     * tick delimiter ([endsWindow]). Set from the PACKET ([placementObserver]) while [ownSend] brackets our own
     * call, not from `doPlacement` returning: the vanilla call can decline without sending (world border). Volatile
     * because the `Instant` path places from an incoming packet handler, i.e. possibly the netty thread.
     */
    @Volatile
    private var placedThisWindow = false

    @Volatile
    private var ownSend = false

    /** A movement packet went out since the last `ClientTickEnd` - Grim's `didSendMovementBeforeTickEnd`. */
    @Volatile
    private var movedBeforeTickEnd = false

    /** Whether [placementObserver] saw our packet inside the current [ownSend] bracket; read by [doPlacement]. */
    @Volatile
    private var ownPlacementSent = false

    /**
     * When this placer last sent a placement, `System.currentTimeMillis()` by packed TARGET cell. Written only when
     * [placementObserver] saw our `UseItemOn` / `UseItem` uncancelled ([ownPlacementSent]): a vanilla call that
     * declined (world border) sends nothing, and a cancel by a handler ahead of the observer - InventoryMove's flush at
     * `FIRST_PRIORITY` - is seen. One behind it is not, and need not be: `BlinkManager` cancels at `FINAL_DECISION`
     * to queue the packet, which still goes out later.
     *
     * Concurrent because the `Instant` path places from an incoming packet handler, i.e. possibly the netty thread.
     * KNOWN HOLE, shared with [ownSend]: the brackets are per placer, not per thread, so an `Instant` placement on the
     * netty thread overlapping a main-thread one can miss a record or credit another module's packet to us.
     *
     * Deliberately NOT cleared by [reset], which also runs on disable: a reader asks "placed since T" ([placedSince])
     * whether or not the module is still on. Cleared on a world change the module sees running (the cells name another
     * world's blocks), and entries older than [PLACED_RETENTION_MS] are dropped at the next placement.
     */
    private val placedAtMs = ConcurrentHashMap<Long, Long>()

    /**
     * Whether this placer has a placement on the wire in the current tick window - and nothing else. Only
     * `ModuleSurround` reads it (the top of the aura chain, see `ModuleSurround.isActing`), and the user's rule
     * (2026-10-03) is that Surround holds the other modules for its block PLACEMENT only, not while it is on.
     *
     * Until that day it also covered the 250 ms after every placement and every tick a placement was merely
     * scheduled (the rotation still turning onto the cell). With `DisableOn` empty Surround rebuilds around the player
     * wherever they step, so in a moving fight that read true nearly all the time and froze CrystalAura, AutoAnchor
     * and KillAura for as long as Surround was switched on (the user's log: 309 `holding: Surround is placing` lines
     * in one session). Nothing needed the wider window: the AIM is Surround's by priority anyway (the auras verify
     * their clicks against the outgoing rotation and send nothing while it is Surround's), and what a click of theirs
     * must not share is the tick window of our `UseItemOn` - which is exactly this flag.
     */
    val isPlacing: Boolean
        get() = module.running && placedThisWindow

    /**
     * Sets [placedThisWindow] on our own interaction packets and clears it at the end of every tick window
     * ([endsWindow]), whoever sent the packet that ends it. Outgoing `PacketEvent`s fire synchronously on the
     * sending thread, so [ownSend] is a valid self-filter. Read at the default priority, i.e. before `BlinkManager`
     * cancels-and-queues at `FINAL_DECISION`: a placement held by FakeLag is still a placement of ours.
     */
    @Suppress("unused")
    private val placementObserver = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }

        val packet = event.packet
        if (endsWindow(packet)) {
            placedThisWindow = false
        } else if (ownSend && (packet is ServerboundUseItemOnPacket || packet is ServerboundUseItemPacket)) {
            placedThisWindow = true
            if (!event.isCancelled) {
                ownPlacementSent = true
            }
        }
    }

    /**
     * Whether this placer sent a placement into [pos] at or after [sinceMs] (`System.currentTimeMillis()`), looking
     * back no further than [PLACED_RETENTION_MS]. The cell is the one the placer TARGETED; the server may still have
     * refused the block. Taking the instant rather than a window lets a caller judge many cells against ONE moment.
     */
    fun placedSince(pos: BlockPos, sinceMs: Long): Boolean {
        val placedAt = placedAtMs[pos.asLong()] ?: return false
        return placedAt >= sinceMs && placedAt >= System.currentTimeMillis() - PLACED_RETENTION_MS
    }

    private fun recordPlacement(pos: BlockPos) {
        val now = System.currentTimeMillis()
        placedAtMs.values.removeIf { now - it > PLACED_RETENTION_MS }
        placedAtMs[pos.asLong()] = now
    }

    /**
     * Whether [packet] closes a Grim tick window: a movement packet always; `ClientTickEnd` only on a tick that sent
     * no movement packet before it. That is Grim's own rule (`checks/GrimProcessor.java:81-90`:
     * `!player.packetStateData.didSendMovementBeforeTickEnd`, set by every flying,
     * `events/packets/CheckManagerListener.java:750`, and reset at the tick end, `:578`). Treating every
     * `ClientTickEnd` as a boundary broke `PostMove`: its placement goes out right after the movement packet and
     * before `ClientTickEnd` (`Minecraft.java:1876` vs `:1925-1928`), so the flag dropped at once while Grim still
     * counts that placement in the window the auras click into on the next tick (refutation audit, 2026-10-03).
     */
    private fun endsWindow(packet: Packet<*>): Boolean = when (packet) {
        is ServerboundMovePlayerPacket, is ServerboundMoveVehiclePacket -> {
            movedBeforeTickEnd = true
            true
        }
        is ServerboundClientTickEndPacket -> !movedBeforeTickEnd.also { movedBeforeTickEnd = false }
        else -> false
    }

    @Suppress("unused")
    private val targetUpdater = handler<RotationUpdateEvent>(priority = -20) {
        if (ticksToWait > 0) {
            ticksToWait--
        } else if (ranAction) {
            ranAction = false
            ticksToWait = cooldown.random()
        }

        val inventoryOpen = !ignoreOpenInventory && mc.screen is AbstractContainerScreen<*>
        val usingItem = !ignoreUsingItem && player.isUsingItem
        if (inventoryOpen || usingItem) {
            return@handler
        }

        if (blocks.isEmpty()) {
            return@handler
        }

        // return if no blocks are available
        val slot = slotFinder(null) ?: return@handler

        val itemStack = slot.itemStack

        inaccessible.clear()
        rotationMode.activeMode.onTickStart()
        if (scheduleCurrentPlacements(itemStack)) {
            return@handler
        }

        // no possible position found, now a support placement can be considered

        if (support.enabled && support.chronometer.hasElapsed(support.delay.toLong())) {
            findSupportPath(itemStack)
        }
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (sneakTimes > 0) {
            sneakTimes--
            event.sneak = true
        }
    }

    @Suppress("CognitiveComplexMethod")
    private fun findSupportPath(itemStack: ItemStack) {
        val currentPlaceCandidates = hashSetOf<BlockPos>()

        // remove all positions of the current support path
        blocks.long2BooleanEntrySet().removeIf { entry ->
            if (entry.booleanValue) {
                currentPlaceCandidates.add(BlockPos.of(entry.longKey))
                true
            } else {
                false
            }
        }

        var supportPath: Set<BlockPos>? = null
        // find the best path
        for (entry in blocks.fastIterator()) {
            val posAsLong = entry.longKey
            if (posAsLong in inaccessible) continue
            val pos = blockPosCache.set(posAsLong)

            support.findSupport(pos)?.let { path ->
                val size = path.size
                if (supportPath == null || supportPath.size > size) {
                    supportPath = path
                }

                // one block is almost the best we can get, so why bother scanning the other blocks
                if (size <= 1) {
                    break
                }
            }
        }

        // we found the same path again, updating is not required
        if (currentPlaceCandidates == supportPath) {
            currentPlaceCandidates.forEach { blocks.put(it.asLong(), true) }
            return
        }

        currentPlaceCandidates.forEach { removeFromQueue(blockPosCache.set(it)) }

        supportPath?.let { path ->
            for (pos in path) {
                if (pos.asLong() !in blocks.keys) {
                    addToQueue(pos, isSupport = true)
                }
            }
            scheduleCurrentPlacements(itemStack)
        }

        support.chronometer.reset()
    }

    private fun scheduleCurrentPlacements(itemStack: ItemStack): Boolean {
        var hasPlaced = false

        for (entry in blocks.fastIterator()) {
            val posAsLong = entry.longKey

            if (inaccessible.contains(posAsLong) || isBlocked(posAsLong, itemStack)) {
                continue
            }

            val searchOptions = BlockPlacementTargetFindingOptions(
                BlockOffsetOptions.Default,
                FaceHandlingOptions(CenterTargetPositionFactory, considerFacingAwayFaces = wallRange > 0),
                stackToPlaceWith = itemStack,
                PlayerLocationOnPlacement(position = player.position()),
            )

            // TODO prioritize faces where sneaking is not required
            //
            // Immutable on purpose. [blockPosCache] is one shared MutableBlockPos for the whole
            // placer, and this position does not stay local: the rotation mode captures it in the
            // `whenReached` closure, which runs LATER — after the rotation has converged, through
            // PostRotationExecutor. By then the cache has been moved on by the next candidate, the
            // next `isBlocked` call or the next tick's queue update, and [doPlacement] would read
            // whatever it now holds. The block itself still lands correctly (that comes from
            // `placementTarget`), but the bookkeeping does not: `blocks.remove(pos.asLong())` and
            // the `pos.getState().canBeReplaced()` guard would both act on the WRONG position, so a
            // placed block stays queued while an unplaced one is dropped. With a single candidate
            // the cache has nowhere else to point and the bug hides; it needs a second position to
            // show itself.
            val pos = blockPosCache.set(posAsLong).immutable()
            val placementTarget = findBestBlockPlacementTarget(pos, searchOptions) ?: continue

            // Check if we can reach the target
            if (!canReach(placementTarget.interactedBlockPos, placementTarget.rotation)) {
                inaccessible.add(posAsLong)
                continue
            }

            debugGeometry("PlacementTarget") {
                ModuleDebug.DebuggedPoint(pos.center, Color4b.GREEN.with(a = 100))
            }

            // sneak when placing on interactable block to not trigger their action
            if (placementTarget.interactedBlockPos.getState().isInteractable) {
                sneakTimes = sneak.random()
            }

            if (rotationMode.activeMode(entry.booleanValue, pos, placementTarget)) {
                return true
            }

            hasPlaced = true
        }

        return hasPlaced
    }

    private fun isBlocked(posAsLong: Long, itemStack: ItemStack): Boolean {
        val pos = blockPosCache.set(posAsLong)
        if (!pos.getState()!!.canBeReplaced()) {
            inaccessible.add(posAsLong)
            return true
        }

        // Vanilla only refuses a placement because of entities when the block would actually
        // COLLIDE with them. `CollisionGetter.isUnobstructed` asks for the state's collision shape
        // first and short-circuits to true the moment it is empty — the entity loop is never
        // reached. Cobweb is the obvious case (`noCollision()` in its properties), and it is exactly
        // why a module placing web onto a player saw nothing happen at all: the positions it wants
        // are BY CONSTRUCTION the ones the target occupies, so a blanket entity test rejects every
        // one of them before a rotation is even requested.
        if (!wouldCollideWithEntities(itemStack, pos)) {
            return false
        }

        val blockedResult = pos.isBlockedByEntitiesReturnCrystal()
        if (crystalDestroyer.enabled) {
            blockedResult.value()?.let {
                crystalDestroyer.currentTarget = it
            }
        }

        if (blockedResult.keyBoolean()) {
            inaccessible.add(posAsLong)
            return true
        }

        return false
    }

    /**
     * Whether the block this stack would place has a collision shape at [pos] — i.e. whether vanilla
     * would consult entities at all before allowing the placement.
     *
     * Anything that is not a block item is treated as colliding, which keeps the conservative
     * behaviour for every existing consumer of the placer.
     */
    private fun wouldCollideWithEntities(itemStack: ItemStack, pos: BlockPos): Boolean {
        val block = (itemStack.item as? BlockItem)?.block ?: return true
        return !block.defaultBlockState().getCollisionShape(world, pos).isEmpty
    }

    fun doPlacement(isSupport: Boolean, pos: BlockPos, placementTarget: BlockPlacementTarget) {
        // choose block to place
        val slot = if (isSupport) {
            support.filter.getSlot(support.blocks)
        } else {
            slotFinder(pos)
        } ?: return

        val verificationRotation = rotationMode.activeMode.getVerificationRotation(placementTarget.rotation)

        // check if we can still reach the target
        if (!canReach(placementTarget.interactedBlockPos, verificationRotation)) {
            return
        }

        // get the block hit result needed for the placement
        val blockHitResult = raytraceTarget(
            placementTarget.interactedBlockPos,
            verificationRotation,
            placementTarget.direction
        ) ?: return

        SilentHotbar.selectSlotSilently(this, slot, slotResetDelay.random())

        if (slot.itemStack.item !is BlockItem || pos.getState()!!.canBeReplaced()) {
            blocks.remove(pos.asLong())

            // place the block; bracketed so [placementObserver] can tell our packet from anybody else's
            ownPlacementSent = false
            ownSend = true
            try {
                doPlacement(blockHitResult, hand = slot.useHand, swingMode = swingMode)
            } finally {
                ownSend = false
            }
            if (ownPlacementSent) {
                recordPlacement(pos)
            }
            placedRenderer.addBlock(pos)
            targetRenderer.removeBlock(pos)
        }
    }

    private fun raytraceTarget(pos: BlockPos, providedRotation: Rotation, direction: Direction): BlockHitResult? {
        val blockHitResult = raytraceBlock(
            range = max(range, wallRange).toDouble(),
            rotation = providedRotation,
            pos = pos,
            state = pos.getState()!!
        )

        if (blockHitResult != null && blockHitResult.type == HitResult.Type.BLOCK && blockHitResult.blockPos == pos) {
            return blockHitResult.withDirection(direction)
        }

        if (constructFailResult) {
            return BlockHitResult(pos.center, direction, pos, false)
        }

        return null
    }

    fun canReach(pos: BlockPos, rotation: Rotation): Boolean {
        // not the exact distance but good enough
        val distance = pos.getCenterDistanceSquaredEyes()
        val wallRangeSq = wallRange.toDouble().sq()

        // if the wall range already covers it, the actual range doesn't matter
        if (distance <= wallRangeSq) {
            return true
        }

        val raycast = traceFromPlayer(range = range.toDouble(), rotation = rotation)
        return raycast.type == HitResult.Type.BLOCK && raycast.blockPos == pos
    }

    /**
     * Removes all positions that are not in [positions] and adds all that are not in the queue.
     */
    fun update(positions: Collection<BlockPos>) {
        val iterator = blocks.fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val position = blockPosCache.set(entry.longKey)
            if (position !in positions) {
                targetRenderer.removeBlock(position)
                iterator.remove()
            } else {
                entry.setValue(false)
            }
        }

        positions.forEach { addToQueue(it, false) }
        targetRenderer.updateAll()
    }

    /**
     * Adds a block to be placed.
     *
     * @param pos The position, can be [BlockPos.MutableBlockPos].
     * @param update Whether the renderer should update the culling.
     */
    fun addToQueue(pos: BlockPos, update: Boolean = true, isSupport: Boolean = false) {
        blocks.computeIfAbsent(pos.asLong()) {
            targetRenderer.addBlock(blockPosCache.set(it), update, FULL_BOX)
            isSupport
        }
    }

    /**
     * Removes a block from the queue.
     *
     * @param pos The position, can be [BlockPos.MutableBlockPos].
     */
    fun removeFromQueue(pos: BlockPos) {
        blocks.remove(pos.asLong())
        targetRenderer.removeBlock(pos)
    }

    /**
     * Discards all blocks.
     */
    fun clear() {
        blocks.fastIterator().forEach { targetRenderer.removeBlock(blockPosCache.set(it.longKey)) }
        blocks.clear()
    }

    /**
     * THis should be called when the module using this placer is disabled.
     */
    fun disable() {
        reset()
        crystalDestroyer.onDisable()
        targetRenderer.clearSilently()
        placedRenderer.clearSilently()
    }

    fun isDone(): Boolean {
        return blocks.isEmpty()
    }

    @Suppress("unused")
    val worldChangeHandler = handler<WorldChangeEvent> {
        reset()
        placedAtMs.clear()
    }

    private fun reset() {
        sneakTimes = 0
        blocks.clear()
        inaccessible.clear()
        placedThisWindow = false
        movedBeforeTickEnd = false
    }

    override fun parent(): EventListener = module

    private enum class Ignore(override val tag: String) : Tagged {
        OPEN_INVENTORY("OpenInventory"),
        USING_ITEM("UsingItem")
    }

    private companion object {
        /** The furthest back [placedSince] looks; [recordPlacement] prunes entries older than this. */
        const val PLACED_RETENTION_MS = 10_000L
    }
}
