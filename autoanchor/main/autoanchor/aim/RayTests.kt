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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.function.Predicate
import kotlin.math.sqrt

/**
 * Crosshair ray tests for the local player at an ARBITRARY rotation, mirroring the vanilla 26.1.2 client pick.
 *
 * Copied from `crystalaura/aim/RayTests.kt` under the copy-not-extract decision recorded in the plan, minus the
 * entity-attack family (`hitsCrystal`, `hitsPhantomBox`, `hitsBoxGeom`). Every anchor interaction - place, charge,
 * detonate - is a `UseItemOn` on a BLOCK (`RespawnAnchorBlock.useItemOn` / `useWithoutItem`,
 * `net/minecraft/world/level/block/RespawnAnchorBlock.java:78-123`), so there is no entity this module ever aims at
 * and the box tests would be dead code that detekt flags and that misleads the next reader. The entity leg of [pick]
 * itself stays: a mob or a player standing between the eye and the cell still masks the block from the vanilla pick,
 * and dropping that would let the module click cells the server refuses.
 *
 * Vanilla reference (Mojang mappings 26.1.2 - in this version the pick lives in `LocalPlayer`, not in `Player`):
 * - `LocalPlayer.raycastHitResult(float, Entity)` (`net/minecraft/client/player/LocalPlayer.java:1234-1252`) calls the
 *   private static `pick(cameraEntity, blockInteractionRange, entityInteractionRange, partialTicks)`
 *   (`LocalPlayer.java:1254-1273`) and `filterHitResult(hitResult, from, maxRange)` (`LocalPlayer.java:1275-1284`).
 *   `blockInteractionRange()` / `entityInteractionRange()` are the attributes read at
 *   `net/minecraft/world/entity/player/Player.java:1958-1964`.
 * - `Entity.pick(double range, float a, boolean withLiquids)` (`net/minecraft/world/entity/Entity.java:1915-1920`):
 *   `from = getEyePosition(a)`, `viewVector = getViewVector(a)`,
 *   `to = from.add(viewVector.x * range, viewVector.y * range, viewVector.z * range)`,
 *   `level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, withLiquids ? Fluid.ANY : Fluid.NONE, this))`.
 * - `Entity.getViewVector(a)` (`Entity.java:1851-1853`) = `calculateViewVector(getViewXRot(a), getViewYRot(a))`;
 *   `Entity.calculateViewVector(float xRot, float yRot)` (`Entity.java:1875-1883`) - `xRot` is the PITCH, `yRot`
 *   the yaw: `realXRot = xRot * (PI / 180)`, `realYRot = -yRot * (PI / 180)`,
 *   `new Vec3(ySin * xCos, -xSin, yCos * xCos)` with `Mth.sin` / `Mth.cos`.
 * - `ProjectileUtil.getEntityHitResult(Entity except, Vec3 from, Vec3 to, AABB box, Predicate<Entity> matching,
 *   double maxValue)` (`net/minecraft/world/entity/projectile/ProjectileUtil.java:109-146`): iterates
 *   `level.getEntities(except, box, matching)`; per entity
 *   `bb = entity.getBoundingBox().inflate(entity.getPickRadius())` and `clipPoint = bb.clip(from, to)`;
 *   `bb.contains(from)` -> `nearest = 0.0` with location `clipPoint.orElse(from)`;
 *   otherwise a present clip point wins when `dd < nearest || nearest == 0.0`, unless the entity shares the root
 *   vehicle with `except`. It is called directly (not re-implemented) so the pick radius, the inside-eye rule and the
 *   vehicle rule stay vanilla.
 * - `EntitySelector.CAN_BE_PICKED = Entity::isPickable` (`net/minecraft/world/entity/EntitySelector.java:17`);
 *   `Entity.isPickable()` (`Entity.java:1926-1928`), `Entity.getPickRadius()` (`Entity.java:2500-2502`).
 * - `AABB.clip(Vec3, Vec3): Optional<Vec3>` (`net/minecraft/world/phys/AABB.java:288-306`); its per-face test in
 *   `clipPoint` (`AABB.java:388-397`) only accepts `0.0 < s < scale`, so a ray STARTING inside the box yields
 *   `Optional.empty()`. `AABB.contains(Vec3)` (`AABB.java:253-259`) is half-open (`>= min && < max`).
 * - `ClipContext(Vec3, Vec3, Block, Fluid, Entity)` (`net/minecraft/world/level/ClipContext.java:28-30`);
 *   `Block.OUTLINE` = `BlockStateBase::getShape` (`ClipContext.java:58`); `Fluid.NONE` (`ClipContext.java:97`) makes
 *   `getFluidShape` return `Shapes.empty()` (`ClipContext.java:52-54`).
 * - `BlockGetter.clip(ClipContext)` (`net/minecraft/world/level/BlockGetter.java:65-82`): a MISS carries
 *   `location = context.getTo()` (`BlockGetter.java:79-81`), so `blockDistanceSq` equals `maxDistance` squared on a
 *   miss.
 * - `HitResult.getLocation()` (`net/minecraft/world/phys/HitResult.java:21-23`); `BlockHitResult.miss(location,
 *   direction, pos)` (`net/minecraft/world/phys/BlockHitResult.java:13-15`), `BlockHitResult.getType()`
 *   (`BlockHitResult.java:56-59`), `getBlockPos()` (`BlockHitResult.java:48-50`).
 * - `Vec3.closerThan(Position, double)` (`net/minecraft/world/phys/Vec3.java:120-122`) is STRICT
 *   (`distanceToSqr < distance * distance`); `Direction.getApproximateNearest(double, double, double)`
 *   (`net/minecraft/core/Direction.java:312`); `BlockPos.containing(Position)` (`net/minecraft/core/BlockPos.java:95`);
 *   `Mth.square(double)` (`net/minecraft/util/Mth.java:619`).
 *
 * Deliberate deviations (everything else is copied operation by operation):
 * 1. The eye is a parameter instead of `cameraEntity.getEyePosition(partialTicks)`, and the direction comes from the
 *    given [Rotation] via `player.calculateViewVector(rotation.pitch, rotation.yaw)` instead of
 *    `getViewVector(partialTicks)` (which lerps `xRotO` / `yRotO`); the camera entity is always the local player.
 * 2. The entity predicate is `CAN_BE_PICKED and !ignore(entity)`, so callers can exclude the entities that the
 *    `LookThrough` setting allows aiming through.
 * 3. The `ATTACK_RANGE` item branch of `raycastHitResult` (`LocalPlayer.java:1235-1244`) is NOT replicated. It runs
 *    when `getActiveItem()` (`net/minecraft/world/entity/LivingEntity.java:2203-2209`: the use item while using,
 *    otherwise the main-hand item) carries a `DataComponents.ATTACK_RANGE` component and resolves through
 *    `AttackRange.getClosesetHit` (`net/minecraft/world/item/component/AttackRange.java:60-86`); on a MISS vanilla
 *    falls back to `pick` (`LocalPlayer.java:1246-1249`), which is what this object mirrors. That branch only shapes
 *    the client's own crosshair; the server never validates a click against it.
 * 4. The unused vanilla local `float overlap = 1.0F` (`LocalPlayer.java:1267`) is dropped.
 * 5. `world` (`MinecraftShortcuts.world` = `mc.level`) replaces `cameraEntity.level()` - the same `ClientLevel`.
 */
object RayTests : MinecraftShortcuts {

    /**
     * Replica of `LocalPlayer.pick(cameraEntity, blockInteractionRange, entityInteractionRange, partialTicks)`
     * (`LocalPlayer.java:1254-1273`) for the local player looking along [rotation] from [eye]:
     * `maxDistance = Math.max(blockRange, entityRange)`, `maxDistanceSq = Mth.square(maxDistance)` (:1255-1256);
     * block clip up to `maxDistance` exactly as `Entity.pick(maxDistance, partialTicks, false)` does (:1258);
     * `blockDistanceSq = blockHit.location.distanceToSqr(from)` (:1259) and, unless the clip MISSED, both
     * `maxDistanceSq` and `maxDistance` shrink to the block hit (:1260-1263); `to = from + direction * maxDistance`
     * (:1266); `box = boundingBox.expandTowards(direction.scale(maxDistance)).inflate(1.0, 1.0, 1.0)` (:1268);
     * `ProjectileUtil.getEntityHitResult(player, from, to, box, predicate, maxDistanceSq)` (:1269); the entity wins iff
     * `entityHit.location.distanceToSqr(from) < blockDistanceSq` (:1270), and each leg passes through
     * [filterHitResult] with its own range (:1271-1272).
     *
     * @return a [BlockHitResult] (type BLOCK, or MISS with the vanilla miss location) or an `EntityHitResult`.
     */
    fun pick(
        eye: Vec3,
        rotation: Rotation,
        blockRange: Double,
        entityRange: Double,
        ignore: (Entity) -> Boolean,
    ): HitResult {
        var maxDistance = maxOf(blockRange, entityRange)
        var maxDistanceSq = Mth.square(maxDistance)
        val direction = direction(rotation)
        val blockHitResult = clipBlocks(eye, endPoint(eye, direction, maxDistance))
        val blockDistanceSq = blockHitResult.location.distanceToSqr(eye)
        if (blockHitResult.type != HitResult.Type.MISS) {
            maxDistanceSq = blockDistanceSq
            maxDistance = sqrt(blockDistanceSq)
        }

        val to = endPoint(eye, direction, maxDistance)
        val box = player.boundingBox.expandTowards(direction.scale(maxDistance)).inflate(1.0, 1.0, 1.0)
        val entityHitResult = ProjectileUtil.getEntityHitResult(
            player, eye, to, box, pickPredicate(ignore), maxDistanceSq
        )
        return if (entityHitResult != null && entityHitResult.location.distanceToSqr(eye) < blockDistanceSq) {
            filterHitResult(entityHitResult, eye, entityRange)
        } else {
            filterHitResult(blockHitResult, eye, blockRange)
        }
    }

    /**
     * [pick] with `blockRange = entityRange = range`, kept only when it is a BLOCK hit on [pos]
     * (`BlockHitResult.getBlockPos`, `BlockHitResult.java:48-50`); null otherwise.
     *
     * This is the ONLY ray test the anchor module needs. The placement clicks a solid neighbour and lets
     * `BlockItem.place` push the anchor into the empty cell; the charge and the detonation click the anchor itself.
     * Neither `useItemOn` nor `useWithoutItem` reads the hit direction (`RespawnAnchorBlock.java:78-123`), so only
     * the placement leg cares which face came back - and that check lives in [LookPoints.forNeighbourFace].
     *
     * Vanilla uses `blockInteractionRange` (4.5) and `entityInteractionRange` (3.0) here; passing [range] for both
     * legs never changes whether [pos] is the picked block: an entity in front of the block masks it in both variants
     * (vanilla merely reports that entity as MISS beyond 3.0 instead of returning it), and a block at or beyond
     * [range] is filtered to MISS by [filterHitResult] in both variants.
     */
    fun hitsBlock(
        eye: Vec3,
        rotation: Rotation,
        pos: BlockPos,
        range: Double,
        ignore: (Entity) -> Boolean,
        throughBlocks: Boolean = false,
    ): BlockHitResult? {
        if (throughBlocks) return clipTarget(eye, rotation, pos, range)

        val hit = pick(eye, rotation, range, range, ignore)
        return if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == pos) hit else null
    }

    /**
     * The box the pick actually sees at [pos], in world coordinates, or null when nothing there can be picked.
     *
     * `ClipContext.Block.OUTLINE` resolves to `state.getShape(level, pos, context)`
     * (`net/minecraft/world/level/ClipContext.java:48-50, 58`) with `CollisionContext.of(entity)`
     * (`ClipContext.java:28-30`), so this is the same shape [hitsBlock] clips against; [VoxelShape.bounds]
     * (`net/minecraft/world/phys/shapes/VoxelShape.java:60-72`) and `AABB.move(BlockPos)`
     * (`net/minecraft/world/phys/AABB.java:221-223`) put it where the block stands. An EMPTY shape answers null -
     * air has nothing to click, and `bounds()` would throw for it.
     *
     * This is what a look point search must sample when the block does not fill its cell. A one-layer snow is 2/16
     * of a block tall (`SnowLayerBlock.java:31, 53-55`) and a fire 1/16 (`BaseFireBlock.java:30`,
     * `Block.column(16.0, 0.0, 1.0)`), both a full 16x16 footprint glued to the BOTTOM of their cell. Sampling
     * `AABB(pos)` for such a block yields points that lie ABOVE the shape, and a ray aimed at one of those need
     * not terminate on the block at all; sampling these bounds aims at the slab that is really there. A respawn
     * anchor fills its cell, so for the charge and the detonation this is `AABB(pos)` exactly.
     */
    fun pickBox(pos: BlockPos): AABB? {
        val shape = world.getBlockState(pos).getShape(world, pos, CollisionContext.of(player))
        return if (shape.isEmpty) null else shape.bounds().move(pos)
    }

    /**
     * `AnchorSettings.throughBlocks`: the ray is clipped against [pos] ALONE, so nothing standing between the eye and
     * that block can mask it.
     *
     * This is the same per-cell primitive the full pick is built from - `VoxelShape.clip(from, to, pos)`
     * (`net/minecraft/world/phys/shapes/VoxelShape.java:159-179`, replicated in `calc/ClipCore.kt`) - applied to one
     * cell instead of walking the whole segment, and on the same shape vanilla's pick would use there:
     * `ClipContext.Block.OUTLINE` resolves to `state.getShape(level, pos, context)`
     * (`net/minecraft/world/level/ClipContext.java:48-50`). An empty shape (air) yields null, and the range filter
     * is the `location.closerThan(from, maxRange)` of [filterHitResult], so the only rule dropped is occlusion.
     *
     * The returned hit is the one that goes on the wire verbatim, and its `direction` is the face the ray entered
     * through - necessarily a face the eye is outside of, which keeps the face id inside 0..5 for every server-side
     * face check.
     */
    private fun clipTarget(eye: Vec3, rotation: Rotation, pos: BlockPos, range: Double): BlockHitResult? {
        val shape = world.getBlockState(pos).getShape(world, pos, CollisionContext.of(player))
        if (shape.isEmpty) return null

        val to = endPoint(eye, direction(rotation), range)
        val hit = shape.clip(eye, to, pos) ?: return null
        return hit.takeIf { it.location.closerThan(eye, range) }
    }

    /**
     * `LocalPlayer.filterHitResult(hitResult, from, maxRange)` (`LocalPlayer.java:1275-1284`): a hit whose location is
     * not `closerThan(from, maxRange)` (strict, `Vec3.java:120-122`) becomes
     * `BlockHitResult.miss(location, Direction.getApproximateNearest(location - from), BlockPos.containing(location))`.
     */
    private fun filterHitResult(hitResult: HitResult, from: Vec3, maxRange: Double): HitResult {
        val location = hitResult.location
        if (location.closerThan(from, maxRange)) {
            return hitResult
        }

        val direction = Direction.getApproximateNearest(location.x - from.x, location.y - from.y, location.z - from.z)
        return BlockHitResult.miss(location, direction, BlockPos.containing(location))
    }

    /**
     * The block leg of `Entity.pick(range, partialTicks, withLiquids = false)` (`Entity.java:1919`):
     * `level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this))`.
     */
    private fun clipBlocks(from: Vec3, to: Vec3): BlockHitResult =
        world.clip(ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))

    /** `EntitySelector.CAN_BE_PICKED` (`EntitySelector.java:17`) narrowed by the caller's [ignore] filter. */
    private fun pickPredicate(ignore: (Entity) -> Boolean): Predicate<Entity> =
        EntitySelector.CAN_BE_PICKED.and { entity -> !ignore(entity) }

    /** `Entity.calculateViewVector(xRot, yRot)` (`Entity.java:1875-1883`) with `xRot = pitch` and `yRot = yaw`. */
    private fun direction(rotation: Rotation): Vec3 = player.calculateViewVector(rotation.pitch, rotation.yaw)

    /**
     * `from.add(direction.x * distance, direction.y * distance, direction.z * distance)` - `Entity.java:1918` and
     * `LocalPlayer.java:1266`, kept as three scalar products rather than `direction.scale(distance)` to preserve the
     * vanilla rounding.
     */
    private fun endPoint(from: Vec3, direction: Vec3, distance: Double): Vec3 =
        from.add(direction.x * distance, direction.y * distance, direction.z * distance)
}
