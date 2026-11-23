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
package net.ccbluex.liquidbounce.features.module.modules.combat.placement

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * catlean's block placement engine (`su.catlean.w4`), ported whole rather than mapped onto the
 * fork's own [net.ccbluex.liquidbounce.utils.block.placer.BlockPlacer].
 *
 * The two solve the same problem differently, and the difference that matters is the entity rule.
 * Ours vetoes a position whenever an entity stands in it — unconditionally, which is right for
 * obsidian and wrong for anything without a collision shape. catlean makes it [IgnoreMode], a
 * setting, so base-placing can ignore the crystal it is placing under and a web can be dropped onto
 * the player it is meant to catch.
 *
 * The rest of the engine follows from three pieces:
 *
 * - [supportCandidates] — which neighbour can be clicked to make a block appear at the target. A
 *   neighbour qualifies when it is solid, or when it is a position we placed at moments ago
 *   ([pending]) or one the caller promises will exist (`calcGhost`). Both exceptions exist so a
 *   sequence of placements can be planned before the first one has been acknowledged.
 * - [faceHitPoint] — where on that face to aim. The face rectangle is inset to 0.15..0.85 and
 *   sampled on a 0.1 grid; every sample is reach-tested, and the winner is the one nearest the eye
 *   (or, under [InteractMode.GRIM], a random one of the survivors — see the enum for why).
 * - [isPointRejected] — the reach model, and it is two-tiered. A point beyond `range` is out; a
 *   point whose line of sight is broken by another block is out beyond `wallRange`. That is what
 *   lets wall-range be tuned separately from range instead of being one number.
 *
 * ### The cached eye
 *
 * [eye] is snapshotted once per tick, early, and every calculation reads it instead of
 * `player.eyePosition`. That is deliberate in the original and worth keeping: within a tick the
 * candidate search, the reach test and the resulting rotation all have to agree about where the
 * player is, or a position that passed the reach test is aimed at from somewhere else and misses.
 */
object CatleanBlockPlacer : EventListener, MinecraftShortcuts {

    /**
     * Direction of the click mapped to the neighbour that has to be clicked to produce it.
     *
     * Clicking the UP face of the block BELOW the target places into the target, hence the inverted
     * pairing: the key is the face passed to the server, the value is the offset of the block that
     * owns it.
     *
     * The ORDER is part of the behaviour, not a detail. [supportCandidates] returns candidates in
     * this order and [findHitResult] takes the first that works, so this is what decides which face
     * gets clicked when several are available — floor first, then ceiling, then the sides.
     */
    private val SUPPORT_OFFSETS: Map<Direction, Vec3i> = linkedMapOf(
        Direction.UP to Vec3i(0, -1, 0),
        Direction.DOWN to Vec3i(0, 1, 0),
        Direction.EAST to Vec3i(-1, 0, 0),
        Direction.WEST to Vec3i(1, 0, 0),
        Direction.NORTH to Vec3i(0, 0, 1),
        Direction.SOUTH to Vec3i(0, 0, -1)
    )

    /**
     * Positions placed at whose block change the server has not sent back yet.
     *
     * Serves two purposes at once: it stops the same position being placed at twice while the round
     * trip is in flight, and it lets [supportCandidates] treat those positions as solid so a chain
     * of placements can be built in a single tick. Entries expire after twice the latency, which is
     * the point past which the packet is either lost or was rejected.
     */
    private val pending = ConcurrentHashMap<BlockPos, Long>()

    /**
     * Snapshot of the eye position for this tick. See the class docs.
     *
     * Reads through to the live eye until the first snapshot lands. The handler that fills this only
     * starts running once the object has been initialised, and the caller that initialises it is
     * usually mid-calculation — so without the fallback the first pass computes every rotation and
     * every reach test from the origin of the world.
     */
    private var eyeSnapshot: Vec3? = null

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        // A snapshot from the world we just left is worse than none: it would place the first tick
        // of the new one from an eye position that no longer exists.
        eyeSnapshot = null
        pending.clear()
    }

    private val eye: Vec3
        get() = eyeSnapshot ?: player.eyePosition

    val pendingPositions: Map<BlockPos, Long>
        get() = pending

    /**
     * Ticks of sneak still owed to a placement that had to defer. See [performPlacement].
     */
    private var sneakTicks = 0

    @Suppress("unused")
    private val sneakHandler = handler<MovementInputEvent> { event ->
        if (sneakTicks > 0) {
            sneakTicks--
            event.sneak = true
        }
    }

    /**
     * Runs ahead of everything that places, so the whole tick shares one eye position and one view
     * of what is still in flight.
     */
    @Suppress("unused")
    private val stateHandler = handler<RotationUpdateEvent>(priority = FIRST_PRIORITY) {
        val expiry = latencyMillis() * 2L
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value > expiry }

        eyeSnapshot = player.eyePosition
    }

    private fun latencyMillis(): Long = (player.playerInfo?.latency ?: 0).coerceAtLeast(0).toLong()

    /**
     * Whether a block could be placed at [pos] right now, without placing it.
     *
     * This is the whole of catlean's `tryPlace`: a position already in flight is not placeable, and
     * otherwise placeable means a hit result exists. AutoBasePlace uses it to decide, cheaply and
     * per position, which cells of a base are still worth queueing.
     */
    fun canPlaceAt(
        pos: BlockPos,
        interact: InteractMode,
        ignore: IgnoreMode,
        range: Float,
        wallRange: Float
    ): Boolean = !pending.containsKey(pos) &&
        findHitResult(pos, interact, ignore, range, wallRange, emptyList()) != null

    /**
     * The full placement, or null when [pos] cannot be reached.
     *
     * Returning a [Placement] rather than placing directly is what lets the caller decide the
     * timing: it can aim first and invoke [Placement.action] a tick later, or do both now.
     */
    @Suppress("LongParameterList")
    fun findPlacement(
        pos: BlockPos,
        slot: Int,
        interact: InteractMode,
        mode: PlaceMode,
        ignore: IgnoreMode,
        range: Float,
        wallRange: Float,
        switchMode: PlaceSwitchMode,
        calcGhost: List<BlockPos> = emptyList()
    ): Placement? {
        val hit = findHitResult(pos, interact, ignore, range, wallRange, calcGhost) ?: return null

        return Placement(
            rotation = Rotation.lookingAt(hit.location, eye),
            pos = pos,
            action = { performPlacement(slot, switchMode, hit, mode, pos) }
        )
    }

    /**
     * A placement for a hit somebody else already solved.
     *
     * End crystals need this: their click lands on the support block itself and the item appears in
     * the cell above, so the neighbour search in [findHitResult] — which exists to find a face that
     * makes a block appear in the TARGET cell — answers a different question. The crystal
     * calculation has already worked the face out, and re-deriving it here would only be a chance to
     * disagree with it.
     */
    fun placementFor(
        hit: BlockHitResult,
        slot: Int,
        mode: PlaceMode,
        switchMode: PlaceSwitchMode,
        target: BlockPos = hit.blockPos
    ): Placement = Placement(
        rotation = Rotation.lookingAt(hit.location, eye),
        pos = target,
        action = { performPlacement(slot, switchMode, hit, mode, target) }
    )

    /**
     * Face selection, ported from `placeHitResult`.
     *
     * Order matters and is the original's: entities first, then replaceability, then the air-place
     * shortcut, then faces. The entity test comes first because it is the only one that can veto a
     * position the geometry would happily accept.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun findHitResult(
        pos: BlockPos,
        interact: InteractMode,
        ignore: IgnoreMode,
        range: Float,
        wallRange: Float,
        calcGhost: List<BlockPos> = emptyList()
    ): BlockHitResult? {
        // The ledger belonged here all along. It was consulted only by `canPlaceAt`, which has no
        // callers, so nothing stopped a cell being placed into twice while the first attempt was in
        // flight. Harmless under `PlaceMode.NORMAL`, where the client's own block prediction hides
        // it — and a use packet every tick under `PlaceMode.PACKET`, which predicts nothing.
        if (pending.containsKey(pos)) {
            return null
        }

        if (isOccupied(pos, ignore)) {
            return null
        }

        if (!world.getBlockState(pos).canBeReplaced()) {
            return null
        }

        if (interact == InteractMode.AIR_PLACE) {
            val hit = world.clip(
                ClipContext(eye, pos.center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            )
            return hit.takeIf { it.type == HitResult.Type.BLOCK }
        }

        for (candidate in supportCandidates(pos, calcGhost)) {
            if (interact == InteractMode.STRICT || interact == InteractMode.GRIM) {
                val visible = visibleFaces(candidate.support)

                // No visible face at all means this support can never be clicked, whatever we try
                // next — the original bails out of the whole search rather than the candidate.
                if (visible.isEmpty()) {
                    return null
                }

                if (candidate.direction !in visible) {
                    continue
                }
            }

            val point = faceHitPoint(
                candidate.direction,
                candidate.support,
                wallRange,
                range,
                interact == InteractMode.GRIM
            ) ?: continue

            return BlockHitResult(point, candidate.direction, candidate.support, false)
        }

        return null
    }

    /**
     * Whether an entity standing in [pos] vetoes a placement there, per [ignore].
     *
     * Items and experience orbs are never counted: nothing collides with them, so a block placed
     * into one simply pops it out. Under [IgnoreMode.ONLY_CRYSTALS] end crystals join that list,
     * which is the mode base-placing wants — the crystal about to be blown up is standing exactly
     * where the next obsidian has to go.
     *
     * The box is shrunk by a hair on X and Z so an entity merely touching the boundary of the cell
     * does not count as being inside it.
     */
    private fun isOccupied(pos: BlockPos, ignore: IgnoreMode): Boolean {
        if (ignore == IgnoreMode.NONE) {
            return false
        }

        val box = AABB(pos).inflate(-1.0E-12, 0.0, -1.0E-12)

        return world.getEntitiesOfClass(Entity::class.java, box) { entity ->
            when {
                entity is ItemEntity || entity is ExperienceOrb -> false
                entity is EndCrystal -> ignore != IgnoreMode.ONLY_CRYSTALS
                else -> true
            }
        }.isNotEmpty()
    }

    /**
     * Neighbours that can be clicked to place into [pos].
     *
     * A neighbour qualifies when it is NOT replaceable — that is, when something solid is there to
     * click. The two exceptions are positions still in flight ([pending]) and positions the caller
     * declared it is about to fill (`calcGhost`); treating those as solid is what allows a tower or
     * a base to be planned as a whole instead of one acknowledgement at a time.
     */
    private fun supportCandidates(pos: BlockPos, calcGhost: List<BlockPos>): List<SupportCandidate> =
        SUPPORT_OFFSETS.mapNotNull { (direction, offset) ->
            val support = pos.offset(offset)
            val replaceable = world.getBlockState(support).canBeReplaced() &&
                !pending.containsKey(support) &&
                support !in calcGhost

            if (replaceable) null else SupportCandidate(support, direction)
        }

    /**
     * Faces of [pos] the player can actually see: the eye is on the outer side of the face plane
     * and the cell beyond it is free.
     *
     * The sign tests below compare the eye against each of the six face planes of the block. Both
     * halves are needed — being on the right side of a face is meaningless if the block in front of
     * it is solid, because then the face is buried.
     */
    fun visibleFaces(pos: BlockPos): List<Direction> {
        val center = pos.center
        val faces = ArrayList<Direction>(6)

        if (eye.x - (center.x + 0.5) > 0.0 && world.getBlockState(pos.east()).canBeReplaced()) {
            faces.add(Direction.EAST)
        }
        if (eye.x - (center.x - 0.5) < 0.0 && world.getBlockState(pos.west()).canBeReplaced()) {
            faces.add(Direction.WEST)
        }
        if (eye.z - (center.z + 0.5) > 0.0 && world.getBlockState(pos.south()).canBeReplaced()) {
            faces.add(Direction.SOUTH)
        }
        if (eye.z - (center.z - 0.5) < 0.0 && world.getBlockState(pos.north()).canBeReplaced()) {
            faces.add(Direction.NORTH)
        }
        if (eye.y - (center.y + 0.5) > 0.0 && world.getBlockState(pos.above()).canBeReplaced()) {
            faces.add(Direction.UP)
        }
        if (eye.y - (center.y - 0.5) < 0.0 && world.getBlockState(pos.below()).canBeReplaced()) {
            faces.add(Direction.DOWN)
        }

        return faces
    }

    /**
     * A reachable point on [direction]'s face of [support], or null when the face has none.
     *
     * The face rectangle is inset to 0.15..0.85 rather than the full unit square: the corners of a
     * face are the points most likely to be at a worse angle than the middle, and clipping them off
     * costs almost no reach while keeping the aim off the block edges. Sampling is a flat 0.1 grid
     * over the two non-degenerate axes.
     */
    private fun faceHitPoint(
        direction: Direction,
        support: BlockPos,
        wallRange: Float,
        range: Float,
        randomize: Boolean
    ): Vec3? {
        val face = faceRect(direction)
        val points = ArrayList<Vec3>()

        var a = face.minX
        while (a < face.maxX || (face.maxX == face.minX && a == face.minX)) {
            var b = face.minY
            while (b < face.maxY || (face.maxY == face.minY && b == face.minY)) {
                var c = face.minZ
                while (c < face.maxZ || (face.maxZ == face.minZ && c == face.minZ)) {
                    val point = Vec3(support.x + a, support.y + b, support.z + c)
                    if (!isPointRejected(point, support, wallRange, range)) {
                        points.add(point)
                    }

                    if (face.maxZ == face.minZ) break
                    c += SAMPLE_STEP
                }

                if (face.maxY == face.minY) break
                b += SAMPLE_STEP
            }

            if (face.maxX == face.minX) break
            a += SAMPLE_STEP
        }

        if (points.isEmpty()) {
            return null
        }

        return if (randomize) {
            points[Random.nextInt(points.size)]
        } else {
            points.minBy { eye.distanceToSqr(it) }
        }
    }

    /** The unit face rectangle, inset on the two axes it spans and degenerate on the third. */
    private fun faceRect(direction: Direction): AABB = when (direction) {
        Direction.DOWN -> AABB(0.15, 0.0, 0.15, 0.85, 0.0, 0.85)
        Direction.UP -> AABB(0.15, 1.0, 0.15, 0.85, 1.0, 0.85)
        Direction.NORTH -> AABB(0.15, 0.15, 0.0, 0.85, 0.85, 0.0)
        Direction.SOUTH -> AABB(0.15, 0.15, 1.0, 0.85, 0.85, 1.0)
        Direction.WEST -> AABB(0.0, 0.15, 0.15, 0.0, 0.85, 0.85)
        Direction.EAST -> AABB(1.0, 0.15, 0.15, 1.0, 0.85, 0.85)
    }

    /**
     * The two-tier reach test. True means the point is unusable.
     *
     * A clear line of sight is allowed out to `range`. A line of sight broken by some other block is
     * allowed only out to `wallRange`, which is normally the shorter of the two — that is the whole
     * point of having both numbers instead of one.
     */
    private fun isPointRejected(point: Vec3, support: BlockPos, wallRange: Float, range: Float): Boolean {
        val distanceSq = eye.distanceToSqr(point)
        val clip = world.clip(
            ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        )

        if (clip.type == HitResult.Type.BLOCK && clip.blockPos != support &&
            distanceSq > wallRange * wallRange
        ) {
            return true
        }

        return distanceSq > range * range
    }

    /**
     * The placement itself.
     *
     * Clicking a trapdoor or a chest opens it instead of placing against it, and the only way to say
     * "place, do not interact" is to be sneaking at the moment the click lands.
     *
     * The original brackets the click with a sneak press and release packet. That is not available
     * here: on 26.1.2 `ServerboundPlayerCommandPacket.Action` no longer carries the shift key —
     * sneaking is part of the movement input and can only change on a tick boundary. So the sneak is
     * requested and the placement DEFERS to the next tick, which is what the fork's own placer does
     * for the same reason. The caller sees this as [PlacementOutcome.DEFERRED] and simply tries
     * again; the position is not marked pending, so nothing is lost but a tick.
     *
     * Which mode hands the slot BACK is the opposite of what the names suggest, and it is the
     * original's: [PlaceSwitchMode.SILENT] restores the previous slot — that is what makes it
     * silent, the server never sees us settled on the crystal — while [PlaceSwitchMode.NORMAL]
     * simply stays there, as a player who switched would. [PlaceSwitchMode.ALTERNATIVE] swaps the
     * stack back through the container.
     *
     * Worth knowing when picking one: Grim's `PacketOrderE` flags a held-item change that arrives
     * after a conflicting action inside the same tick window, and the use packet is one. So the
     * restore that makes `Silent` silent is also the packet that check looks for.
     */
    private fun performPlacement(
        slot: Int,
        switchMode: PlaceSwitchMode,
        hit: BlockHitResult,
        mode: PlaceMode,
        target: BlockPos
    ): PlacementOutcome {
        val supportBlock = world.getBlockState(hit.blockPos).block
        if (isInteractable(supportBlock) && !player.isShiftKeyDown) {
            sneakTicks = 1
            return PlacementOutcome.DEFERRED
        }

        val previousSlot = player.inventory.selectedSlot

        if (slot in 0..8) {
            selectSlot(slot, switchMode)
        }

        if (mode == PlaceMode.NORMAL) {
            interaction.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        } else {
            interaction.startPrediction(world) { sequence ->
                ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence)
            }
        }

        network.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        pending[target.immutable()] = System.currentTimeMillis()

        when {
            switchMode == PlaceSwitchMode.SILENT && slot in 0..8 -> selectSlot(previousSlot, switchMode)
            switchMode == PlaceSwitchMode.ALTERNATIVE && slot in 0..8 -> selectSlot(slot, switchMode)
            else -> Unit
        }

        return PlacementOutcome.PLACED
    }

    private fun selectSlot(slot: Int, switchMode: PlaceSwitchMode) {
        when (switchMode) {
            PlaceSwitchMode.SILENT, PlaceSwitchMode.NORMAL -> {
                if (player.inventory.selectedSlot != slot) {
                    player.inventory.selectedSlot = slot
                    interaction.ensureHasSentCarriedItem()
                }
            }

            // Keep the held slot and move the stack into it instead. Costs a container click but
            // never produces a held-item change, so nothing can be ordered wrongly against it.
            PlaceSwitchMode.ALTERNATIVE -> {
                interaction.handleContainerInput(
                    player.inventoryMenu.containerId,
                    HOTBAR_SLOT_OFFSET + slot,
                    player.inventory.selectedSlot,
                    ContainerInput.SWAP,
                    player
                )
            }
        }
    }

    /** Blocks whose click opens something instead of placing. Kept literally from the original. */
    private fun isInteractable(block: Block): Boolean =
        block is BaseEntityBlock ||
            block == Blocks.CRAFTING_TABLE ||
            block is TrapDoorBlock ||
            block is LeverBlock ||
            block is ButtonBlock ||
            block is DoorBlock

    private const val SAMPLE_STEP = 0.1

    /** Where the hotbar starts in the player's container slot numbering. */
    private const val HOTBAR_SLOT_OFFSET = 36

    private data class SupportCandidate(val support: BlockPos, val direction: Direction)

    /**
     * A placement that has been solved but not yet performed.
     *
     * Split this way so the caller owns the timing: aim now and act next tick, or both at once.
     */
    class Placement(
        val rotation: Rotation,

        /** Where the block would end up. Carried so the caller can record what it sent. */
        val pos: BlockPos,
        val action: () -> PlacementOutcome
    )

    /** What [Placement.action] did. */
    enum class PlacementOutcome {
        /** The use packet went out. */
        PLACED,

        /**
         * Nothing was sent — a sneak was needed first and can only take effect next tick. Call
         * again; the position was not marked pending.
         */
        DEFERRED
    }

}
