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
package net.ccbluex.liquidbounce.features.module.modules.combat.testaa

import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.entity.ping
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.BeaconBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.BeehiveBlock
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BrewingStandBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CakeBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.CandleCakeBlock
import net.minecraft.world.level.block.CartographyTableBlock
import net.minecraft.world.level.block.CaveVinesBlock
import net.minecraft.world.level.block.CaveVinesPlantBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ChiseledBookShelfBlock
import net.minecraft.world.level.block.CommandBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.block.CopperGolemStatueBlock
import net.minecraft.world.level.block.CrafterBlock
import net.minecraft.world.level.block.CraftingTableBlock
import net.minecraft.world.level.block.DaylightDetectorBlock
import net.minecraft.world.level.block.DecoratedPotBlock
import net.minecraft.world.level.block.DispenserBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.DragonEggBlock
import net.minecraft.world.level.block.EnchantingTableBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.GrindstoneBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.JigsawBlock
import net.minecraft.world.level.block.JukeboxBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LightBlock
import net.minecraft.world.level.block.LoomBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.ShelfBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SmithingTableBlock
import net.minecraft.world.level.block.StonecutterBlock
import net.minecraft.world.level.block.StructureBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.TestBlock
import net.minecraft.world.level.block.TestInstanceBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * World-side helpers of donut's "Anchor Aura": raycasts, the two aim searches, the block tests and the slot finders.
 * Every function names the decompiled method it reproduces (paths relative to
 * `C:\Users\caves\Documents\donut\work\src_named\c\`).
 */
@Suppress("MagicNumber", "TooManyFunctions")
internal object TestAAWorld {

    /** Yaw and pitch DELTAS from a reference rotation, `IiIiIiIIii.IIiIiIIiIiiiI` (`^Ii^Ii^Ii^I^Iii.java:98-117`). */
    class AngleDelta(val yaw: Float, val pitch: Float)

    /**
     * A placement click found by [findPlaceHit]: the block that is CLICKED, its face, and the rotation that lands on
     * it. `c/IiIiIIIiii` (`^Ii^Ii^I^I^Iiii.java`).
     */
    class PlaceHit(val clickedPos: BlockPos, val face: Direction, val yaw: Float, val pitch: Float, val score: Float)

    private class AngleBounds(
        val centerYaw: Float,
        val minYaw: Float,
        val maxYaw: Float,
        val minPitch: Float,
        val maxPitch: Float,
    )

    /** `{0.0, 0.3, -0.3}` in this order, `SocialCommand.java:26401`. */
    private val FACE_OFFSETS = doubleArrayOf(0.0, 0.3, -0.3)

    /** Tangent axes of a face, `SocialCommand.IIIIIIiiIiiIII` (`SocialCommand.java:31029-31052`). */
    private fun tangents(axis: Direction.Axis): Pair<Vec3, Vec3> = when (axis) {
        Direction.Axis.X -> Vec3(0.0, 1.0, 0.0) to Vec3(0.0, 0.0, 1.0)
        Direction.Axis.Y -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 0.0, 1.0)
        Direction.Axis.Z -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 1.0, 0.0)
    }

    fun angleDelta(pitchReference: Float, yawReference: Float, direction: Vec3): AngleDelta {
        val yaw = -Math.toDegrees(atan2(direction.x, direction.z)).toFloat()
        val pitch = -Math.toDegrees(atan2(direction.y, hypot(direction.x, direction.z))).toFloat()
        return AngleDelta(Mth.wrapDegrees(yaw - yawReference), pitch - pitchReference)
    }

    /** `(s * 0.6 + 0.2)^3 * 8 * 0.15`, `SocialCommand.java:46358-46359`. */
    private fun gcd(): Double {
        val base = mc.options.sensitivity().get() * 0.6 + 0.2
        return base * base * base * 8.0 * 0.15
    }

    /** `SocialCommand.iiiiIiIiIIIii` (`SocialCommand.java:16156-16166`): [target] on the grid around [current]. */
    private fun snap(gcd: Double, target: Float, current: Float): Float =
        (current.toDouble() + Math.rint((target - current).toDouble() / gcd) * gcd).toFloat()

    /** `SocialCommand.IIIiIiiIiIiiii(-j..j)`: a uniform draw in `[-jitter, jitter]`. */
    private fun jitter(amount: Float): Float {
        if (amount <= 0.0f) {
            return 0.0f
        }
        return ThreadLocalRandom.current().nextFloat() * (2.0f * amount) - amount
    }

    /**
     * `IiIiIiiIIi.IIiIiIiIiiIii` / `IIiIiIiiIIIiI` (`^Ii^Ii^Iii^I^Ii.java:95-108`, `:280-327`): an OUTLINE block ray
     * without fluids from [origin] along ([yaw], [pitch]). An [ignore] cell is walked through as air.
     */
    @Suppress("LongParameterList")
    fun raycast(
        level: Level,
        entity: Entity,
        origin: Vec3,
        yaw: Float,
        pitch: Float,
        range: Double,
        ignore: BlockPos? = null,
    ): BlockHitResult {
        val end = origin.add(Vec3.directionFromRotation(pitch, yaw).scale(range))
        val context = ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity)
        if (ignore == null) {
            return level.clip(context)
        }

        val hit = TestAAMath.traverse(origin, end) { pos ->
            if (pos == ignore) {
                null
            } else {
                val state = level.getBlockState(pos)
                level.clipWithInteractionOverride(origin, end, pos, context.getBlockShape(state, level, pos), state)
            }
        }
        if (hit != null) {
            return hit
        }

        val delta = origin.subtract(end)
        val nearest = Direction.getApproximateNearest(delta.x, delta.y, delta.z)
        return BlockHitResult.miss(end, nearest, BlockPos.containing(end))
    }

    /** `SocialCommand.iiiIiIIIiiIii` (`SocialCommand.java:6954-7015`): yaw/pitch extent of [box] seen from [origin]. */
    @Suppress("CognitiveComplexMethod")
    private fun angleBounds(box: AABB, origin: Vec3): AngleBounds {
        val centerYaw = angleDelta(0.0f, 0.0f, box.center.subtract(origin)).yaw
        var minYaw = Float.POSITIVE_INFINITY
        var maxYaw = Float.NEGATIVE_INFINITY
        var minPitch = Float.POSITIVE_INFINITY
        var maxPitch = Float.NEGATIVE_INFINITY

        for (x in doubleArrayOf(box.minX, box.maxX)) {
            for (y in doubleArrayOf(box.minY, box.maxY)) {
                for (z in doubleArrayOf(box.minZ, box.maxZ)) {
                    val corner = angleDelta(0.0f, 0.0f, Vec3(x - origin.x, y - origin.y, z - origin.z))
                    val yaw = centerYaw + Mth.wrapDegrees(corner.yaw - centerYaw)
                    if (yaw < minYaw) minYaw = yaw
                    if (yaw > maxYaw) maxYaw = yaw
                    if (corner.pitch < minPitch) minPitch = corner.pitch
                    if (corner.pitch > maxPitch) maxPitch = corner.pitch
                }
            }
        }
        return AngleBounds(centerYaw, minYaw, maxYaw, minPitch, maxPitch)
    }

    /**
     * `RaycastUtil.IIiIIiiiiiIii` -> `SocialCommand.IIIIiiIiiIiIII` (`SocialCommand.java:46264-46469`): a grid-snapped
     * rotation from [origin] whose OUTLINE ray lands on [pos]. Starts at the rotation towards [aim] (clamped into the
     * full cube's angular extent) and walks square rings of [step] degrees outwards until the extent is covered. One
     * [jitter] draw per axis is taken per CALL and added to every candidate before it is snapped onto the mouse grid
     * around the entity's own (camera) rotation. Returns the hit, not the rotation - callers recompute the rotation
     * from the hit location, as `AnchorAura.java:424-434` does.
     */
    @Suppress("LongParameterList", "CognitiveComplexMethod", "ReturnCount")
    fun findInteractHit(
        entity: Player,
        pos: BlockPos,
        aim: Vec3,
        range: Double,
        step: Float,
        origin: Vec3,
        jitter: Float,
    ): BlockHitResult? {
        val level = entity.level()
        val bounds = angleBounds(AABB(pos), origin)
        val stepSize = max(step, 0.1f)

        val towards = angleDelta(0.0f, 0.0f, aim.subtract(origin))
        val startYaw = (bounds.centerYaw + Mth.wrapDegrees(towards.yaw - bounds.centerYaw))
            .coerceIn(bounds.minYaw, bounds.maxYaw)
        val startPitch = towards.pitch.coerceIn(bounds.minPitch, bounds.maxPitch)

        val gcd = gcd()
        val yawJitter = jitter(jitter)
        val pitchJitter = jitter(jitter)

        fun attempt(yaw: Float, pitch: Float): BlockHitResult? {
            if (yaw < bounds.minYaw - stepSize || yaw > bounds.maxYaw + stepSize ||
                pitch < bounds.minPitch - stepSize || pitch > bounds.maxPitch + stepSize
            ) {
                return null
            }

            val snappedYaw = snap(gcd, yaw + yawJitter, entity.yRot)
            val snappedPitch = snap(gcd, pitch + pitchJitter, entity.xRot).coerceIn(-90.0f, 90.0f)
            val hit = raycast(level, entity, origin, snappedYaw, snappedPitch, range)
            return hit.takeIf { it.type == HitResult.Type.BLOCK && it.blockPos == pos }
        }

        attempt(startYaw, startPitch)?.let { return it }

        val yawRings = ceil(max(bounds.maxYaw - startYaw, startYaw - bounds.minYaw) / stepSize).toInt()
        val pitchRings = ceil(max(bounds.maxPitch - startPitch, startPitch - bounds.minPitch) / stepSize).toInt()
        val rings = max(yawRings, pitchRings)

        for (ring in 1..rings) {
            val radius = ring * stepSize
            for (i in -ring..ring) {
                val yaw = startYaw + i * stepSize
                attempt(yaw, startPitch - radius)?.let { return it }
                attempt(yaw, startPitch + radius)?.let { return it }
            }
            for (i in -(ring - 1) until ring) {
                val pitch = startPitch + i * stepSize
                attempt(startYaw - radius, pitch)?.let { return it }
                attempt(startYaw + radius, pitch)?.let { return it }
            }
        }
        return null
    }

    /**
     * `RaycastUtil.IIiIiIIIIIiII` -> `SocialCommand.IIIIIIIIiiIiiI` (`SocialCommand.java:26363-26567`): the click that
     * places the held block into [target]. For each direction, `target` itself and its neighbour are tried as the
     * clicked block (a [support] restricts that), on the face pointing back at `target`, at nine points of that face
     * offset by 0 / +-0.3 along its two tangents. The first point of a candidate block that raycasts onto it AND
     * resolves to `target` is that block's only entry; among the entries the smallest offset wins, then the smallest
     * rotation change.
     *
     * The rotation fix `SocialCommand.IIIIiiIIIiIiii` is native (`SocialCommand.java:43947`); it is substituted by
     * the same grid snap the other search uses ([snap] around the player's own rotation). GUESS, flagged in the
     * module KDoc.
     */
    @Suppress(
        "LongParameterList", "CognitiveComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "LongMethod"
    )
    fun findPlaceHit(
        player: Player,
        target: BlockPos,
        range: Double,
        stack: ItemStack,
        support: BlockPos?,
        ignore: BlockPos?,
        jitter: Float,
    ): PlaceHit? {
        val level = player.level()
        val eye = player.eyePosition
        val rangeSqr = range * range
        val gcd = gcd()
        val yawJitter = jitter(jitter)
        val pitchJitter = jitter(jitter)

        var best: PlaceHit? = null
        var bestOffset = Double.MAX_VALUE

        for (direction in Direction.entries) {
            candidates@ for (candidate in listOf(target, target.relative(direction))) {
                if (support != null && candidate != support) {
                    continue
                }

                val face = direction.opposite
                val state = level.getBlockState(candidate)
                if (isInteractable(state.block)) {
                    continue
                }
                val shape = state.getShape(level, candidate)
                if (shape.isEmpty) {
                    continue
                }

                val box = shape.bounds().move(candidate)
                val normal = face.unitVec3
                val faceCenter = Vec3(
                    box.center.x + normal.x * box.xsize * 0.5,
                    box.center.y + normal.y * box.ysize * 0.5,
                    box.center.z + normal.z * box.zsize * 0.5
                )
                val (first, second) = tangents(face.axis)

                for (u in FACE_OFFSETS) {
                    for (v in FACE_OFFSETS) {
                        val point = faceCenter.add(first.scale(u)).add(second.scale(v))
                        if (point.distanceToSqr(eye) > rangeSqr) {
                            continue
                        }

                        val delta = angleDelta(player.xRot, player.yRot, point.subtract(eye))
                        val yaw = snap(gcd, player.yRot + delta.yaw + yawJitter, player.yRot)
                        val pitch = snap(gcd, player.xRot + delta.pitch + pitchJitter, player.xRot)
                            .coerceIn(-90.0f, 90.0f)

                        val hit = raycast(level, player, eye, yaw, pitch, range, ignore)
                        if (support != null && hit.blockPos != support) {
                            continue
                        }
                        if (hit.type != HitResult.Type.BLOCK) {
                            continue
                        }

                        val placed = if (ignore == null) {
                            placementPos(player, hit, stack)
                        } else {
                            hit.blockPos.relative(hit.direction)
                        }
                        if (placed != target) {
                            continue
                        }

                        val offset = abs(u) + abs(v)
                        val score = abs(delta.yaw) + abs(delta.pitch)
                        val current = best
                        if (current == null || offset < bestOffset || offset == bestOffset && score < current.score) {
                            best = PlaceHit(hit.blockPos, hit.direction, yaw, pitch, score)
                            bestOffset = offset
                        }
                        continue@candidates
                    }
                }
            }
        }
        return best
    }

    /**
     * `SocialCommand.IIIIiiiiIIiIiI` (`SocialCommand.java:48433-48476`): the cell a click with [stack] would fill -
     * vanilla's own `BlockPlaceContext` + `BlockItem.updatePlacementContext` - or null when it would not place.
     */
    fun placementPos(player: Player, hit: BlockHitResult, stack: ItemStack): BlockPos? {
        if (hit.type != HitResult.Type.BLOCK || stack.isEmpty) {
            return null
        }
        if (isInteractable(player.level().getBlockState(hit.blockPos).block)) {
            return null
        }

        val item = stack.item as? BlockItem ?: return null
        val context = BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit)
        if (!context.canPlace()) {
            return null
        }
        return item.updatePlacementContext(context)?.clickedPos
    }

    /**
     * `IiIiIIiIII.IIiIIiiIiiIIi` (`^Ii^Ii^I^Ii^I^I^I.java:138-145`): blocks a click would OPEN or USE instead of
     * placing against. Every class was checked to exist in 26.1.2.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    fun isInteractable(block: Block): Boolean =
        block is AbstractFurnaceBlock || block is AbstractCauldronBlock || block is AnvilBlock ||
            block is BarrelBlock || block is BeaconBlock || block is BedBlock || block is BeehiveBlock ||
            block is BellBlock || block is BrewingStandBlock || block is ButtonBlock || block is CakeBlock ||
            block is CampfireBlock || block is CandleCakeBlock || block is CartographyTableBlock ||
            block is CaveVinesBlock || block is CaveVinesPlantBlock || block is ChestBlock ||
            block is ChiseledBookShelfBlock || block is CommandBlock || block is ComparatorBlock ||
            block is ComposterBlock || block is CopperGolemStatueBlock || block is CrafterBlock ||
            block is CraftingTableBlock || block is DaylightDetectorBlock || block is DecoratedPotBlock ||
            block is DispenserBlock || block is DoorBlock || block is DragonEggBlock ||
            block is EnchantingTableBlock || block is EnderChestBlock || block is FenceBlock ||
            block is FenceGateBlock || block is FlowerPotBlock || block is GrindstoneBlock ||
            block is HopperBlock || block is JigsawBlock || block is JukeboxBlock || block is LecternBlock ||
            block is LeverBlock || block is LightBlock || block is LoomBlock || block is NoteBlock ||
            block is RedStoneWireBlock || block is RepeaterBlock || block is RespawnAnchorBlock ||
            block is ShelfBlock || block is ShulkerBoxBlock || block is SignBlock ||
            block is SmithingTableBlock || block is StonecutterBlock || block is StructureBlock ||
            block is SweetBerryBushBlock || block is TestBlock || block is TestInstanceBlock ||
            block is TrapDoorBlock

    /**
     * `SocialCommand.IIIiiiiiiIiIiI` / `iiiiIiIIIIIii` (`SocialCommand.java:71680-71734`, `:15405-15459`, identical):
     * [pos] or one of its six neighbours is a non-interactable block with a non-empty OUTLINE shape.
     */
    fun hasSupport(level: Level, pos: BlockPos): Boolean {
        if (isSupporting(level, pos)) {
            return true
        }
        return Direction.entries.any { isSupporting(level, pos.relative(it)) }
    }

    private fun isSupporting(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !isInteractable(state.block) && !state.getShape(level, pos).isEmpty
    }

    /**
     * `SocialCommand.IIiIIIIIIiiiiI` (`SocialCommand.java:72643-72712`): [state] may go into [pos] without a collision,
     * and - while [player] is airborne - without our own fall over the next `min(ceil(ping / 50) + 2, 20)` ticks
     * running into it.
     */
    fun canPlaceSafely(level: Level, pos: BlockPos, state: BlockState, player: Player): Boolean {
        if (!level.isUnobstructed(state, pos, CollisionContext.of(player))) {
            return false
        }
        if (player.onGround()) {
            return true
        }

        val ticks = min(ceil(ping(player) / 50.0f).toInt() + 2, 20)
        val cell = AABB(pos)
        var box = player.boundingBox
        var velocity = player.deltaMovement
        repeat(ticks) {
            val moved = Entity.collideBoundingBox(player, velocity, box, level, emptyList())
            box = box.move(moved)
            if (box.intersects(cell)) {
                return false
            }
            if (moved.y != velocity.y) {
                return true
            }
            velocity = Vec3(velocity.x * 0.91, (velocity.y - 0.08) * 0.98, velocity.z * 0.91)
        }
        return true
    }

    /**
     * donut's ping getter (`iiIIIiiiiI.iIiiiiIIIiIII`, `ii^I^I^Iiiii^I.java:89-112`) prefers a round trip it measures
     * itself with `ServerboundPingRequestPacket`s and falls back to the tab-list latency. That measurement is native;
     * this is the fallback only.
     */
    fun ping(player: Player): Int = player.ping

    /** `DamageUtil.IIiIiIIIiIiii` (`DamageUtil.java:698-704`): [ticks] if positive, else `min(ceil(ping/50)+1, 10)`. */
    fun predictionTicks(ticks: Int, player: Player): Int =
        if (ticks > 0) ticks else min(ceil(ping(player) / 50.0f).toInt() + 1, 10)

    private fun firstHotbarSlot(player: Player, predicate: (ItemStack) -> Boolean): Int {
        for (slot in 0..8) {
            if (predicate(player.inventory.getItem(slot))) {
                return slot
            }
        }
        return -1
    }

    fun hotbarSlotOf(player: Player, item: Item): Int = firstHotbarSlot(player) { it.`is`(item) }

    /** `SocialCommand.iiiIIiIIIIIii(.., true, ..)`: glowstone in the hotbar. The offhand is not considered. */
    fun glowstoneSlot(player: Player): Int = hotbarSlotOf(player, Items.GLOWSTONE)

    /**
     * `SocialCommand.iiiIIiIIIIIii(.., false, ..)` (`SocialCommand.java:3340-3449`): the hand that detonates. A totem
     * first; glowstone in the offhand refuses everything else (it would charge instead of detonating); then the
     * player's own pre-swap slot, then any slot holding neither glowstone nor a block item, then any slot without
     * glowstone.
     *
     * The pre-swap slot is donut's `SwapManager.IIiiiiiIiiIii` - the slot saved when its first lease was taken. Here
     * the lease is [SilentHotbar]'s, whose [SilentHotbar.clientsideSlot] is that same slot.
     */
    @Suppress("ReturnCount")
    fun detonateSlot(player: Player): Int {
        val totem = hotbarSlotOf(player, Items.TOTEM_OF_UNDYING)
        if (totem != -1) {
            return totem
        }
        if (player.offhandItem.`is`(Items.GLOWSTONE)) {
            return -1
        }

        val real = SilentHotbar.clientsideSlot
        if (real in 0..8 && isDetonationItem(player.inventory.getItem(real))) {
            return real
        }

        val clean = firstHotbarSlot(player, ::isDetonationItem)
        if (clean != -1) {
            return clean
        }
        return firstHotbarSlot(player) { !it.`is`(Items.GLOWSTONE) }
    }

    private fun isDetonationItem(stack: ItemStack): Boolean = !stack.`is`(Items.GLOWSTONE) && stack.item !is BlockItem
}
