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

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fire-mode chain of [ModuleAutoCart]: a rail is placed, a TNT minecart goes on it, the block on
 * the player side of the rail is lit with flint and steel, and a charged crossbow bolt is fired
 * through it, so the burning bolt sets the minecart off when it hits.
 *
 * One use-item action per tick (vanilla ceiling), driven like [AutoCartXbow]: the swap leads its
 * window (`PacketOrderE`-clean, one conflicting packet per window), and every step re-checks the
 * rotation the server actually holds before it acts (`RotationPlace`-style settle gate).
 *
 * [ModuleAutoCart.CartMode.FIRE] runs this on the module tick; [ModuleAutoCart.CartMode.BIND]
 * starts it on the module bind press; [ModuleCartAura] starts it around a popped target.
 */
internal object AutoCartFire {

    private enum class Step { RAIL, CART, FIRE, SHOOT }

    private var active = false
    private var step = Step.RAIL
    private var rail: BlockPos? = null
    private var fire: BlockPos? = null
    private var savedSlot = -1
    private var stepTicks = 0
    private var lastSwapTick = Int.MIN_VALUE

    /** Start the chain unless one is already running or a previous one ended within COOLDOWN_MS. */
    internal fun startAt(center: Vec3) {
        if (active) return
        if (System.currentTimeMillis() < cooldownUntil) return
        val spot = findSpot(center) ?: return

        active = true
        step = Step.RAIL
        stepTicks = 0
        rail = spot.rail
        fire = spot.fire
        savedSlot = player.inventory.selectedSlot
    }

    /** Bind-press entry for [ModuleAutoCart.CartMode.BIND]; debounced through [active]. */
    internal fun onBindPress() {
        startAt(player.position())
    }

    internal val isActive: Boolean get() = active

    /**
     * Abandons the chain; hands the hotbar back and arms [cooldownUntil] so a restart is
     * spaced out. ModuleAutoCart.reset's own restore is a no-op when this path owns the slot
     * ([ModuleAutoCart.CartMode.XBOW] leaves [savedSlot] at -1 there).
     */
    internal fun reset() {
        restoreSlot()
        active = false
        step = Step.RAIL
        rail = null
        fire = null
        savedSlot = -1
        stepTicks = 0
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
    }

    @Volatile
    private var cooldownUntil = 0L

    internal fun tick() {
        if (!active) return
        clock++
        if (++stepTicks > STEP_TIMEOUT_TICKS) {
            reset()
            return
        }
        when (step) {
            Step.RAIL -> stepRail()
            Step.CART -> stepCart()
            Step.FIRE -> stepFire()
            Step.SHOOT -> stepShoot()
        }
    }

    private fun stepRail() {
        val railPos = rail ?: return reset()

        if (world.getBlockState(railPos).block is BaseRailBlock) {
            step = Step.CART
            stepTicks = 0
            return
        }
        if (wireHitOn(railPos) == null) return
        if (!inReach(railPos)) return reset()

        val slot = findHotbarItem { it.`is`(Items.RAIL) || it.`is`(Items.POWERED_RAIL) } ?: return reset()
        swapTo(slot)
        useOn(railHit(railPos))

        step = Step.CART
        stepTicks = 0
    }

    private fun stepCart() {
        val railPos = rail ?: return reset()

        if (findCart(railPos) == null) {
            // The cart spawns with the placement; do the click once, then wait it out.
            if (wireHitOn(railPos) == null) return
            if (!inReach(railPos)) return reset()
            val slot = findHotbarItem { it.`is`(Items.TNT_MINECART) } ?: return reset()
            swapTo(slot)
            useOn(railHit(railPos))
            return
        }

        step = Step.FIRE
        stepTicks = 0
    }

    private fun stepFire() {
        val railPos = rail ?: return reset()
        val firePos = fire ?: return reset()

        if (world.getBlockState(firePos).isAir) {
            // No flammable block here — the bolt alone will reach the cart.
            step = Step.SHOOT
            stepTicks = 0
            return
        }
        if (wireHitOn(firePos) == null) return
        if (!inReach(firePos)) return reset()

        val slot = findHotbarItem { it.`is`(Items.FLINT_AND_STEEL) } ?: return reset()
        swapTo(slot)
        useOn(fireHit(firePos))

        step = Step.SHOOT
        stepTicks = 0
    }

    private fun stepShoot() {
        val railPos = rail ?: return reset()

        if (findCart(railPos) == null) return

        if (!shotLineValid()) {
            // The user asked for the bolt to pass through the fire into the cart; a broken line
            // costs the charged crossbow — so aim at the rail and give the line a few ticks to
            // open up instead of firing a bolt into nothing.
            aimAt(Vec3(railPos.x + 0.5, railPos.y + RAIL_SURFACE, railPos.z + 0.5))
            return
        }
        if (wireHitOn(railPos) == null) return
        if (!inReach(railPos)) return reset()

        val slot = findHotbarItem { it.item is CrossbowItem && CrossbowItem.isCharged(it) }
            ?: return reset()

        swapTo(slot)
        useInAir()
        reset()
    }

    /**
     * Does the bolt, fired from the player's eye, cross the fire cell, reach the cart box and
     * stay unobstructed end to end?
     */
    private fun shotLineValid(): Boolean {
        val railPos = rail ?: return false
        val firePos = fire ?: return false

        val origin = boltOrigin()
        val target = Vec3(railPos.x + 0.5, railPos.y + 0.5, railPos.z + 0.5)
        val entry = boxEntry(origin, target, cartBox(railPos)) ?: return false

        return crossesBox(origin, target, AABB(firePos)) && reachesUnobstructed(origin, entry)
    }

    // ── Spot scan ────────────────────────────────────────────────────────────

    private class Spot(val rail: BlockPos, val fire: BlockPos)

    /** Scan ring around [center]: left/right/front/back and heights around the actor. */
    private fun findSpot(center: Vec3): Spot? {
        for (yOff in -2..2) {
            for (angle in 0 until 360 step 45) {
                for (radius in SPOT_RADII) {
                    val rad = Math.toRadians(angle.toDouble())
                    val railPos = BlockPos(
                        (center.x + cos(rad) * radius).toInt(),
                        (center.y + yOff).toInt(),
                        (center.z + sin(rad) * radius).toInt()
                    )
                    if (railViable(railPos)) {
                        // Fire cell on the player side of the rail — between the eye and the bolt.
                        val toward = Direction.getApproximateNearest(
                            player.x - railPos.x, 0.0, player.z - railPos.z
                        )
                        return Spot(railPos, railPos.relative(toward))
                    }
                }
            }
        }
        return null
    }

    private fun railViable(railPos: BlockPos): Boolean {
        val state = world.getBlockState(railPos)
        if (!state.isAir && !state.canBeReplaced()) return false
        val below = railPos.below()
        if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) return false
        if (findCart(railPos) != null) return false

        val firePos = railPos.relative(Direction.getApproximateNearest(player.x - railPos.x, 0.0, player.z - railPos.z))
        if (world.getBlockState(firePos).isAir) return false

        return shotLineValidViable(railPos, firePos)
    }

    /**
     * Same line check as [shotLineValid], but with the estimated cart box (the real one does not
     * exist yet when the spot is chosen).
     */
    private fun shotLineValidViable(railPos: BlockPos, firePos: BlockPos): Boolean {
        val origin = boltOrigin()
        val target = Vec3(railPos.x + 0.5, railPos.y + 0.5, railPos.z + 0.5)
        val entry = boxEntry(origin, target, estimatedCartBox(railPos)) ?: return false
        return crossesBox(origin, target, AABB(firePos)) && reachesUnobstructed(origin, entry)
    }

    // ── Aim / interaction (mirrors AutoCartXbow idioms) ───────────────────────

    private fun aimAt(point: Vec3) {
        RotationManager.setRotationTarget(
            plan = RotationTarget(
                rotation = Rotation.lookingAt(point = point, from = player.eyePosition),
                entity = null,
                processors = emptyList(),
                ticksUntilReset = 2,
                resetThreshold = 1f,
                considerInventory = false,
                movementCorrection = MovementCorrection.SILENT
            ),
            priority = Priority.IMPORTANT_FOR_PLAYER_LIFE,
            provider = ModuleAutoCart
        )
    }

    /** grim FarPlace: never click a box outside block-interaction range. */
    private fun inReach(pos: BlockPos): Boolean {
        val reach = player.blockInteractionRange() - 0.05
        return AABB(pos).distanceToSqr(player.eyePosition) <= reach * reach
    }

    /** grim settle gate: the wire rotation must already land on [block] before we click. */
    private fun wireHitOn(block: BlockPos): BlockHitResult? {
        val eye = player.eyePosition
        val end = eye.add(RotationManager.serverRotation.directionVector.scale(player.blockInteractionRange()))
        val hit = world.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        return hit.takeIf { it.type == HitResult.Type.BLOCK && it.blockPos == block }
    }

    private fun useOn(hit: BlockHitResult) {
        swingIfVanillaWould(mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit))
    }

    private fun useInAir() {
        swingIfVanillaWould(mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND))
    }

    private fun swingIfVanillaWould(result: InteractionResult?) {
        if (result is InteractionResult.Success && result.swingSource == InteractionResult.SwingSource.CLIENT) {
            player.swing(InteractionHand.MAIN_HAND)
        }
    }

    /** Real swap through the vanilla tracker (SetCarriedItem announces; BadPacketsA-clean). */
    private fun swapTo(slot: Int): Boolean {
        if (slot !in 0..8) return false
        if (player.inventory.selectedSlot == slot) return true
        if (clock == lastSwapTick) return false

        lastSwapTick = clock
        player.inventory.selectedSlot = slot
        mc.gameMode?.ensureHasSentCarriedItem()
        return true
    }

    private var clock = 0

    private fun findHotbarItem(predicate: (ItemStack) -> Boolean): Int? {
        for (i in 0..8) {
            val stack = player.inventory.getItem(i)
            if (!stack.isEmpty && predicate(stack)) return i
        }
        return null
    }

    private fun restoreSlot() {
        if (savedSlot in 0..8) {
            player.inventory.selectedSlot = savedSlot
            mc.gameMode?.ensureHasSentCarriedItem()
        }
        savedSlot = -1
    }

    /** Site-specific aim: the rail top, with the face pointing back at the player like AutoCartXbow. */
    private fun railHit(cell: BlockPos): BlockHitResult {
        val face = Direction.getApproximateNearest(player.eyePosition.subtract(Vec3.atCenterOf(cell)))
        val location = when (face) {
            Direction.UP -> Vec3(cell.x + 0.5, cell.y + RAIL_SURFACE, cell.z + 0.5)
            Direction.DOWN -> Vec3(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
            else -> Vec3(cell.x + 0.5 + face.stepX * 0.5, cell.y + RAIL_SURFACE / 2.0, cell.z + 0.5 + face.stepZ * 0.5)
        }
        return BlockHitResult(location, face, cell, false)
    }

    /** Aim point for the flint & steel: the fire block, hit from the player's side. */
    private fun fireHit(cell: BlockPos): BlockHitResult {
        val face = Direction.getApproximateNearest(player.x - cell.x, 0.0, player.z - cell.z)
        return BlockHitResult(
            Vec3(cell.x + 0.5 + face.stepX * 0.5, cell.y + 0.5, cell.z + 0.5 + face.stepZ * 0.5),
            face,
            cell,
            false
        )
    }

    // ── World queries / geometry (mirrors AutoCartXbow) ──────────────────────

    /** Where the bolt is born: eye minus 0.1 (`Projectile.shootFromRotation`). */
    private fun boltOrigin(): Vec3 = player.eyePosition.subtract(0.0, PROJECTILE_SPAWN_DROP, 0.0)

    private fun estimatedCartBox(rail: BlockPos): AABB = AABB(
        rail.x + 0.5 - CART_HALF_WIDTH,
        rail.y.toDouble(),
        rail.z + 0.5 - CART_HALF_WIDTH,
        rail.x + 0.5 + CART_HALF_WIDTH,
        rail.y + CART_BOX_HEIGHT,
        rail.z + 0.5 + CART_HALF_WIDTH
    )

    private fun cartBox(rail: BlockPos): AABB =
        findCart(rail)?.boundingBox?.inflate(0.12) ?: estimatedCartBox(rail)

    private fun boxEntry(from: Vec3, through: Vec3, box: AABB): Vec3? {
        val dir = through.subtract(from)
        if (dir.lengthSqr() < 1.0E-8) return null
        return box.clip(from, from.add(dir.normalize().scale(RAY_LENGTH))).orElse(null)
    }

    private fun crossesBox(from: Vec3, through: Vec3, box: AABB): Boolean =
        boxEntry(from, through, box) != null

    private fun reachesUnobstructed(origin: Vec3, entry: Vec3): Boolean {
        val hit = world.clip(ClipContext(origin, entry, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return hit.type != HitResult.Type.BLOCK
    }

    private fun findCart(pos: BlockPos): MinecartTNT? =
        world.getEntities(null, AABB(pos).inflate(0.35, 1.0, 0.35)) { it is MinecartTNT }
            .firstOrNull() as? MinecartTNT

    // ── Constants ────────────────────────────────────────────────────────────

    private const val STEP_TIMEOUT_TICKS = 12
    private const val COOLDOWN_MS = 2000L
    private const val PROJECTILE_SPAWN_DROP = 0.1
    private const val CART_HALF_WIDTH = 0.55
    private const val CART_BOX_HEIGHT = 0.85
    private const val RAY_LENGTH = 10.0
    private const val RAIL_SURFACE = 0.125
    private val SPOT_RADII = listOf(1.5, 2.5, 3.5)
}
