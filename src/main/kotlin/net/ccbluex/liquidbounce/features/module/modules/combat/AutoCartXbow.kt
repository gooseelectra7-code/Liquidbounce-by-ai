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
@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoCart.Xbow
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Xbow mode of [ModuleAutoCart] — the fire cart bomb.
 *
 * You place a rail; the module builds the rest of the trap around it and sets it off: a TNT minecart
 * onto that rail, a slab between you and the cart, fire lit above the slab with flint and steel, then
 * a crossbow bolt threaded through the fire into the cart. The bolt catches fire crossing the fire,
 * and `MinecartTNT.hurtServer` detonates on a burning `AbstractArrow` *immediately* — no fuse — so
 * the whole cost of the combo is the build.
 *
 * Ported from the `rich` client's `Xbow` (`rich.modules.impl.player.Xbow`): the trap geometry and
 * the aim search that hunts for a line clearing the slab, crossing the fire and still landing on the
 * cart are kept as they were. Everything about the packet layer was rebuilt.
 *
 * ## Timing
 *
 * One interaction packet per tick, which is the vanilla ceiling. Four are needed (cart, slab, fire,
 * shot), so the floor is 4 ticks plus one to notice the rail — and the trap lands in about **6
 * ticks** rather than the ~22 a naive port takes. Two things buy that back:
 *
 * - *The hotbar swap shares the tick with the use it serves.* Verified against Grim's
 *   `PacketOrderE`: it flags a `HELD_ITEM_CHANGE` only when a conflicting action packet has ALREADY
 *   arrived in the same window (`placing`/`using`/`attacking`/`sneaking`/`sprinting`/…, all cleared
 *   by the tick packet). Swap-then-use is clean; use-then-swap is the violation, and it even arms a
 *   setback. So the swap simply has to lead its window — which it does, since this runs at the head
 *   of the client tick, ahead of `handleKeybinds` and of vanilla's own sprint/sneak actions.
 * - *The aim is pipelined one step ahead.* Each step submits the rotation the NEXT step will need,
 *   so it rides out on this tick's movement packet and the next step's gate is already open. That
 *   is also why everything runs on `RotationUpdateEvent` (before `RotationManager.update`) and the
 *   packets go out BEFORE the movement packet: acting post-move would put the newly pipelined aim
 *   on the wire ahead of the action it is not meant for.
 *
 * Nothing is ever done on an aim the server has not got: every step re-checks the rotation ALREADY
 * ON THE WIRE and only re-aims when it does not already work — so when you are still looking at the
 * rail you just placed (the normal case) the cart goes down with no aiming tick at all.
 *
 * ## What the original did that is gone here
 *
 * - *Three interactions in one tick.* Its `placeLava` tried the slab side, the slab top and a plain
 *   use-item back-to-back in one tick. This version uses FlintAndSteelItem's block interaction on
 *   the slab's top face so fire is placed above the slab with one ordinary `useItemOn` call.
 * - *Hand-rolled slot packets* that desynced from vanilla's tracker and duplicated the announce
 *   (BadPacketsA). Slot changes go through `ensureHasSentCarriedItem`, capped at one per tick.
 * - *Extra flying packets* from its `look(target, true)`. Rotations go through [RotationManager].
 * - *Unvalidated aim* — it placed in the same tick it set the client rotation (RotationPlace).
 * - *Unconditional swings*; vanilla only swings on a CLIENT swing source.
 * - *Unclamped reach*; placements are bound by `blockInteractionRange` (FarPlace).
 */

// ── State ────────────────────────────────────────────────────────────────

internal enum class XbowPhase { CART, SLAB, IGNITE, SHOOT, RESTORE }

private var pendingRail: BlockPos? = null
private var pendingTicks = 0

private var active = false
private var phase: XbowPhase? = null
private var railPos: BlockPos? = null
private var slabPos: BlockPos? = null
private var savedSlot = -1
private var age = 0
private var aimTicks = 0
private var lastSwapTick = Int.MIN_VALUE

/** Tick the rail placement was seen on — the zero point every Debug timing is measured from. */
private var triggerTick = -1

/**
 * Own tick clock. `player.tickCount` is the age of the player ENTITY and restarts near zero on
 * respawn, dimension change and reconnect, which would leave the one-swap-per-tick cap comparing
 * against a stale number and the Debug timings printing nonsense.
 */
private var clock = 0

internal val xbowActive: Boolean get() = active

private const val PENDING_TIMEOUT_TICKS = 10
private const val SEQUENCE_TIMEOUT_TICKS = 40
private const val AIM_TIMEOUT_TICKS = 8

/** Bolts spawn at eye height minus 0.1 (`Projectile.shootFromRotation`). */
private const val PROJECTILE_SPAWN_DROP = 0.1

// ── Trigger ──────────────────────────────────────────────────────────────

/** Outgoing rail placement — the trap is built around whatever rail the player just put down. */
internal fun ModuleAutoCart.xbowOnRailPlace(hitResult: BlockHitResult, hand: InteractionHand) {
    if (active) return debug("already running a sequence")

    val clicked = hitResult.blockPos
    val placePos = if (isRail(clicked)) clicked else clicked.relative(hitResult.direction)

    // The client predicts the placement — and consumes the item — BEFORE this packet is sent
    // (`MultiPlayerGameMode.startPrediction` runs `predict(...)` and only then `send(...)`). So on
    // the LAST rail of a stack the hand is already empty here, and a hand-only test misses exactly
    // the placement we care about. Accept that case via the block the prediction just wrote.
    val held = player.getItemInHand(hand)
    if (!isRailStack(held) && !(held.isEmpty && isRail(placePos))) {
        return debug("not a rail placement — hand=${held.item}, target=${placePos.toShortString()}")
    }
    val distSq = player.eyePosition.distanceToSqr(Vec3.atCenterOf(placePos))
    if (distSq > Xbow.range * Xbow.range) {
        return debug("rail out of Range (${sqrt(distSq).toInt()}+ blocks, Range=${Xbow.range})")
    }

    debug("rail queued at ${placePos.toShortString()}")
    triggerTick = clock
    pendingRail = placePos.immutable()
    pendingTicks = 0
}

/** Ticks since the rail went down. The whole point of the Debug timings is to check this budget. */
private fun elapsed(): Int = if (triggerTick < 0) -1 else clock - triggerTick

/** Chat trace for the silent gates; costs nothing while [Xbow.debug] is off. */
private fun ModuleAutoCart.debug(message: String) {
    if (Xbow.debug) {
        chat("§8[Xbow] §7$message", this)
    }
}

// ── Tick (RotationUpdateEvent — before RotationManager.update, before the movement packet) ──

internal fun ModuleAutoCart.xbowTick() {
    clock++

    if (!active) {
        awaitPendingRail()
        if (!active) return
    }

    // Runs ahead of `handleKeybinds`, so queued use-key clicks (yours, or an autoclicker's) are
    // eaten before vanilla can consume them. Without this they fire with the hotbar WE are holding:
    // a click while looking at the slab we just placed puts a second slab into that cell, and
    // vanilla merges the pair into a full block — which also seals the bolt's line.
    if (Xbow.lockUse) {
        mc.options.keyUse.clickCount = 0
    }

    if (++age > SEQUENCE_TIMEOUT_TICKS) {
        xbowFinish()
        return
    }

    when (phase) {
        XbowPhase.CART -> stepCart()
        XbowPhase.SLAB -> stepSlab()
        XbowPhase.IGNITE -> stepIgnite()
        XbowPhase.SHOOT -> stepShoot()
        // Grim PacketOrderE: the hotbar goes back in its own window, never behind the shot.
        XbowPhase.RESTORE -> xbowFinish()
        null -> xbowFinish()
    }
}

// ── Steps ────────────────────────────────────────────────────────────────

private fun ModuleAutoCart.stepCart() {
    val rail = railPos ?: return xbowFinish()
    if (!isRail(rail)) return xbowFinish()

    if (findCart(rail) != null) {
        enterSlabStep()
        return
    }

    // The normal case needs no aiming tick: you are still looking at the rail you just placed.
    val hit = wireHitOn(rail) ?: return retryAim(Vec3(rail.x + 0.5, rail.y + RAIL_TOP, rail.z + 0.5))
    if (!inReach(rail)) return xbowFinish()

    val slot = findHotbarItem(Items.TNT_MINECART)
    if (slot == -1) {
        // Waiting cannot bring the item back — better to hand the hotbar over now than to stall
        // the whole 40-tick timeout with movement and mouse locked.
        debug("no TNT minecart in the hotbar any more")
        return xbowFinish()
    }
    if (!swapTo(slot)) return
    useOn(hit)
    debug("cart §f+${elapsed()}t")
    enterSlabStep()
}

private const val RAIL_TOP = 0.0625

private fun ModuleAutoCart.stepSlab() {
    val slab = slabPos ?: return xbowFinish()
    if (isBottomSlab(slab)) {
        enterIgniteStep()
        return
    }

    val support = slab.below()
    val hit = wireHitOn(support)
    if (hit == null || hit.direction != Direction.UP) return retryAim(slabSupportAim())
    if (!inReach(support)) return xbowFinish()

    // Avoid handing the placement a slab that would merge with the support instead of sitting on it.
    val merging = mergingSlabBlock(support)
    val slot = findSlabSlot(merging)
    if (slot == -1) {
        debug("only slab in the hotbar would merge with the one below into a double block")
        return xbowFinish()
    }

    if (!swapTo(slot)) return
    useOn(hit)
    debug("slab §f+${elapsed()}t")
    enterIgniteStep()
}

/** Light fire in the cell above the bottom slab with a normal block interaction. */
private fun ModuleAutoCart.stepIgnite() {
    val slab = slabPos ?: return xbowFinish()
    if (hasFire(slab)) {
        // Nothing was used this tick, so the next aim may still ride out on this tick's flying.
        enterShootStep(pipelineAim = true)
        return
    }
    if (!isBottomSlab(slab)) return retryAim(slabTopAim())
    if (!canPlaceFireAt(slab)) {
        debug("fire cannot be placed above ${slab.toShortString()}")
        return xbowFinish()
    }

    val hit = wireHitOn(slab)
    if (hit == null || hit.direction != Direction.UP) return retryAim(slabTopAim())
    if (!inReach(slab)) return xbowFinish()

    val slot = findHotbarItem(Items.FLINT_AND_STEEL)
    if (slot == -1) {
        debug("no flint and steel in the hotbar any more")
        return xbowFinish()
    }
    if (!swapTo(slot)) return
    useOn(hit)
    debug("fire §f+${elapsed()}t")
    // Keep this tick's outgoing aim on the clicked slab; the next step can pipeline its shot aim.
    enterShootStep(pipelineAim = false)
}

private fun ModuleAutoCart.stepShoot() {
    val rail = railPos ?: return xbowFinish()
    val slab = slabPos ?: return xbowFinish()
    if (!hasFire(slab)) {
        // No fire, no detonation — rebuild that step rather than waste the bolt.
        phase = XbowPhase.IGNITE
        return
    }

    // Fire only once the server's cart entity is in the world: until its spawn packet lands the
    // box is an estimate, and a bolt sent at an estimate can miss the real cart — the charged
    // crossbow is the one item this sequence cannot bring back.
    if (findCart(rail) == null) {
        return retryAim(shootTarget() ?: return xbowFinish())
    }

    // The functional gate: not "is the angle close to a point" but "does the bolt, fired along the
    // rotation the server has, clear the slab, cross the fire and land on the cart".
    if (!shotLineValid()) {
        val target = shootTarget() ?: return xbowFinish()
        return retryAim(target)
    }

    val slot = findChargedCrossbow()
    if (slot == -1) {
        debug("no charged crossbow in the hotbar any more")
        return xbowFinish()
    }
    if (!swapTo(slot)) return
    useInAir()
    debug("§ashot §f+${elapsed()}t §7— total from rail to bolt (+1-2t flight to the blast)")
    phase = XbowPhase.RESTORE
}

// ── Step transitions (each pipelines the aim the NEXT step will need) ────

private fun ModuleAutoCart.enterSlabStep() {
    phase = XbowPhase.SLAB
    aimTicks = 0
    if (slabPos != null) aimAt(slabSupportAim())
}

private fun ModuleAutoCart.enterIgniteStep() {
    phase = XbowPhase.IGNITE
    aimTicks = 0
    if (slabPos != null) aimAt(slabTopAim())
}

/**
 * Grim BadPacketsJ (`use_item_rotation_mismatch`, NOT experimental — on by default) compares the
 * yaw/pitch carried by a `ServerboundUseItemPacket` against the rotation of that tick. LiquidBounce
 * fills the packet from `RotationManager.currentRotation` at construction time
 * (`MixinServerboundUseItemPacket`), and we build it from `RotationUpdateEvent` — i.e. BEFORE
 * `update()` steps the rotation. So the crossbow shot cannot pipeline a different aim in its send
 * tick. The flint-and-steel action is a block interaction; it also keeps the slab aim through that
 * tick so the outgoing placement remains aligned with the ray used to choose its hit.
 */
private fun ModuleAutoCart.enterShootStep(pipelineAim: Boolean) {
    phase = XbowPhase.SHOOT
    aimTicks = 0
    if (pipelineAim) {
        shootTarget()?.let { aimAt(it) }
    }
}

/** Top face of the block the slab will rest on. */
private fun slabSupportAim(): Vec3 {
    val slab = slabPos ?: return Vec3.ZERO
    return Vec3(slab.x + 0.5, slab.y.toDouble(), slab.z + 0.5)
}

/** Top face of the placed bottom slab — where the flint-and-steel click has to land. */
private fun slabTopAim(): Vec3 {
    val slab = slabPos ?: return Vec3.ZERO
    return Vec3(slab.x + 0.5, slab.y + 0.5, slab.z + 0.5)
}

/** Re-aim and wait a tick; the pipelined aim only misses when the world moved under us. */
private fun ModuleAutoCart.retryAim(point: Vec3) {
    aimAt(point)
    if (++aimTicks > AIM_TIMEOUT_TICKS) xbowFinish()
}

// ── Setup ────────────────────────────────────────────────────────────────

private fun ModuleAutoCart.awaitPendingRail() {
    val pending = pendingRail ?: return

    if (isRail(pending)) {
        pendingRail = null
        pendingTicks = 0
        xbowStart(pending)
        return
    }
    if (++pendingTicks > PENDING_TIMEOUT_TICKS) {
        debug("no rail appeared at ${pending.toShortString()} within $PENDING_TIMEOUT_TICKS ticks")
        pendingRail = null
        pendingTicks = 0
    }
}

private fun ModuleAutoCart.xbowStart(rail: BlockPos) {
    if (!hasAllItems()) {
        return debug(
            "missing items — cart=${findHotbarItem(Items.TNT_MINECART) != -1} " +
                "slab=${findSlabSlot() != -1} flintAndSteel=${findHotbarItem(Items.FLINT_AND_STEEL) != -1} " +
                "chargedXbow=${findChargedCrossbow() != -1}"
        )
    }

    val slab = findSlabCell(rail) ?: return debug(
        "no slab cell between you and the rail — step back so there is a free block in between"
    )
    // Checked before the cart goes down: finding this out at the slab step wastes a minecart.
    if (!isBottomSlab(slab) && findSlabSlot(mergingSlabBlock(slab.below())) == -1) {
        return debug("only slab in the hotbar would merge with the one below ${slab.toShortString()}")
    }
    if (!canPlaceFireAt(slab)) return debug("cannot place fire above ${slab.toShortString()}")
    if (!hasShootingLine(rail, slab)) return debug("no shooting line clearing the slab through fire into the cart")

    debug("armed: rail=${rail.toShortString()} slab=${slab.toShortString()}")

    railPos = rail.immutable()
    slabPos = slab.immutable()
    savedSlot = player.inventory.selectedSlot
    phase = XbowPhase.CART
    age = 0
    aimTicks = 0
    active = true
}

internal fun xbowFinish() {
    if (savedSlot in 0..8 && mc.player != null && player.inventory.selectedSlot != savedSlot) {
        player.inventory.selectedSlot = savedSlot
        mc.gameMode?.ensureHasSentCarriedItem()
    }
    active = false
    phase = null
    railPos = null
    slabPos = null
    savedSlot = -1
    age = 0
    aimTicks = 0
}

internal fun xbowReset() {
    pendingRail = null
    pendingTicks = 0
    lastSwapTick = Int.MIN_VALUE
    clock = 0
    xbowFinish()
}

/**
 * The slab cell: the block directly between the player and the rail. Fire is lit above it, so the
 * bolt crosses fire on its way to the cart without the fire touching the cart itself.
 */
private fun ModuleAutoCart.findSlabCell(rail: BlockPos): BlockPos? {
    val railCenter = Vec3.atCenterOf(rail)
    val toward = Direction.getApproximateNearest(player.x - railCenter.x, 0.0, player.z - railCenter.z)

    val cell = rail.relative(toward)
    if (cell == rail || isRail(cell)) return null
    if (!isBottomSlab(cell) && !canPlaceAt(cell)) return null

    val playerPos = player.position()
    if (playerPos.distanceToSqr(Vec3.atCenterOf(cell)) >= playerPos.distanceToSqr(railCenter) - 1.0E-3) {
        return null
    }
    return cell
}

private fun ModuleAutoCart.canPlaceFireAt(slab: BlockPos): Boolean {
    val firePos = slab.above()
    val fireCell = world.getBlockState(firePos)
    if (fireCell.block is BaseFireBlock) return true
    return (fireCell.isAir || fireCell.canBeReplaced()) &&
        BaseFireBlock.canBePlacedAt(world, firePos, Direction.UP)
}

// ── Shot geometry ────────────────────────────────────────────────────────

/** Where the bolt is actually born — 0.1 below the eye, which matters against a half-slab. */
private fun boltOrigin(): Vec3 = player.eyePosition.subtract(0.0, PROJECTILE_SPAWN_DROP, 0.0)

/**
 * Does a bolt fired along the rotation the SERVER currently holds clear the slab, cross the fire and
 * land on the cart? This is the real condition, and a whole cone of rotations satisfies it — far
 * more forgiving than sighting a single computed point.
 */
private fun shotLineValid(): Boolean {
    val rail = railPos ?: return false
    val slab = slabPos ?: return false
    val origin = boltOrigin()
    val through = origin.add(RotationManager.serverRotation.directionVector.scale(RAY_LENGTH))

    if (crossesBox(origin, through, slabSolidBox())) return false
    val targetBox = cartBox(rail)
    if (!crossesFireBeforeCart(origin, through, fireBox(slab), targetBox)) return false
    val entry = boxEntry(origin, through, targetBox) ?: return false
    return reachesUnobstructed(origin, entry)
}

private fun cartBox(rail: BlockPos): AABB =
    findCart(rail)?.boundingBox?.inflate(0.12) ?: estimatedTntMinecartBox(rail)

/**
 * Walks candidate heights down the cart looking for a line that clears the slab's solid half,
 * crosses the fire cell (so the bolt ignites) and still lands on the cart.
 */
private fun shootTarget(): Vec3? {
    val rail = railPos ?: return null
    val slab = slabPos ?: return null
    val origin = boltOrigin()
    val cart = findCart(rail)

    val cx = cart?.x ?: (rail.x + 0.5)
    val cz = cart?.z ?: (rail.z + 0.5)
    val baseY = cart?.y ?: rail.y.toDouble()
    val height = cart?.bbHeight?.toDouble() ?: DEFAULT_CART_HEIGHT
    val box = cartBox(rail)

    var best: Vec3? = null
    var f = AIM_SCAN_TOP
    while (f >= AIM_SCAN_BOTTOM) {
        val aim = Vec3(cx, baseY + height * f, cz)
        val entry = boxEntry(origin, aim, box)
        if (entry != null && !crossesBox(origin, aim, slabSolidBox()) && reachesUnobstructed(origin, entry)) {
            if (crossesFireBeforeCart(origin, aim, fireBox(slab), box)) return aim
            if (best == null) best = aim
        }
        f -= AIM_SCAN_STEP
    }
    return best
}

private const val DEFAULT_CART_HEIGHT = 0.7
private const val AIM_SCAN_TOP = 0.95
private const val AIM_SCAN_BOTTOM = 0.2
private const val AIM_SCAN_STEP = 0.05

/** Solid lower half of a bottom slab — the bolt must not clip it. */
private fun slabSolidBox(): AABB {
    val slab = slabPos ?: return AABB(BlockPos.ZERO)
    return AABB(slab.x.toDouble(), slab.y.toDouble(), slab.z.toDouble(), slab.x + 1.0, slab.y + 0.5, slab.z + 1.0)
}

/** Fire is placed in the cell above the bottom slab. */
private fun fireBox(slab: BlockPos): AABB = AABB(slab.above()).deflate(0.02)

private fun crossesBox(from: Vec3, through: Vec3, box: AABB): Boolean =
    boxEntry(from, through, box) != null

/** The bolt must pass through the fire cell before entering the cart. */
private fun crossesFireBeforeCart(from: Vec3, through: Vec3, fire: AABB, cart: AABB): Boolean {
    val fireEntry = boxEntry(from, through, fire) ?: return false
    val cartEntry = boxEntry(from, through, cart) ?: return false
    return fireEntry.distanceToSqr(from) < cartEntry.distanceToSqr(from)
}

/** First intersection of the (extended) `from → through` ray with [box], or null when it misses. */
private fun boxEntry(from: Vec3, through: Vec3, box: AABB): Vec3? {
    val dir = through.subtract(from)
    if (dir.lengthSqr() < 1.0E-8) return null
    return box.clip(from, from.add(dir.normalize().scale(RAY_LENGTH))).orElse(null)
}

/**
 * The boxes only say where the trap's parts are; this says nothing ELSE eats the bolt first. A
 * trench lip or a wall between the slab and the rail is invisible to the box tests, and a bolt
 * they let through but the world stops is a charged crossbow wasted — the one item that ends the
 * combo. Same clip an arrow flies by: COLLIDER (the rail has no collision shape).
 */
private fun reachesUnobstructed(origin: Vec3, entry: Vec3): Boolean {
    val hit = world.clip(ClipContext(origin, entry, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
    return hit.type != HitResult.Type.BLOCK
}

private const val RAY_LENGTH = 10.0

/** Pre-flight: is there any usable line at all, before we start spending items? */
private fun hasShootingLine(rail: BlockPos, slab: BlockPos): Boolean {
    val origin = boltOrigin()
    // The estimate the cart will actually occupy — an inflated whole cell here would arm traps
    // whose only "line" misses the real cart, and the failure would surface four items later.
    val cartBox = estimatedTntMinecartBox(rail)
    val slabSolid = AABB(
        slab.x.toDouble(), slab.y.toDouble(), slab.z.toDouble(), slab.x + 1.0, slab.y + 0.5, slab.z + 1.0
    )
    val fire = fireBox(slab)

    var f = AIM_SCAN_TOP
    while (f >= AIM_SCAN_PREFLIGHT_BOTTOM) {
        val aim = Vec3(rail.x + 0.5, rail.y + DEFAULT_CART_HEIGHT * f, rail.z + 0.5)
        if (!crossesBox(origin, aim, slabSolid) &&
            crossesFireBeforeCart(origin, aim, fire, cartBox)
        ) {
            return true
        }
        f -= AIM_SCAN_STEP
    }
    return false
}

private const val AIM_SCAN_PREFLIGHT_BOTTOM = 0.25

// ── Interaction ──────────────────────────────────────────────────────────

/** The real hit the wire rotation resolves to, when it lands on [block]. */
private fun wireHitOn(block: BlockPos): BlockHitResult? {
    val eye = player.eyePosition
    val end = eye.add(RotationManager.serverRotation.directionVector.scale(player.blockInteractionRange()))
    val hit = world.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
    return hit.takeIf { it.type == HitResult.Type.BLOCK && it.blockPos == block }
}

/** Grim FarPlace: never click a box outside block-interaction range. */
private fun inReach(block: BlockPos): Boolean {
    val reach = player.blockInteractionRange() - 0.05
    return AABB(block).distanceToSqr(player.eyePosition) <= reach * reach
}

private fun useOn(hit: BlockHitResult) {
    swingIfVanillaWould(mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit))
}

private fun useInAir() {
    swingIfVanillaWould(mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND))
}

/** Vanilla only swings on a CLIENT swing source; an unconditional swing is an extra animation. */
private fun swingIfVanillaWould(result: InteractionResult?) {
    if (result is InteractionResult.Success && result.swingSource == InteractionResult.SwingSource.CLIENT) {
        player.swing(InteractionHand.MAIN_HAND)
    }
}

private fun ModuleAutoCart.aimAt(point: Vec3) {
    RotationManager.setRotationTarget(
        plan = RotationTarget(
            rotation = Rotation.lookingAt(point = point, from = player.eyePosition),
            entity = null,
            processors = emptyList(),
            ticksUntilReset = 2,
            resetThreshold = 1f,
            considerInventory = false,
            movementCorrection = if (Xbow.silent) MovementCorrection.SILENT else MovementCorrection.CHANGE_LOOK
        ),
        priority = Priority.IMPORTANT_FOR_PLAYER_LIFE,
        provider = this
    )
}

// ── Hotbar ───────────────────────────────────────────────────────────────

/**
 * Real swap through the vanilla tracker, which only emits SetCarriedItem when it differs from what
 * the server was last told (BadPacketsA). Capped at one per tick, and always the FIRST conflicting
 * packet of its window — Grim's PacketOrderE flags a held-item change that lands behind a
 * use/attack/sneak/sprint in the same window, not one that leads it.
 */
private fun swapTo(slot: Int): Boolean {
    if (slot !in 0..8) return false
    if (player.inventory.selectedSlot == slot) return true
    val tick = clock
    if (tick == lastSwapTick) return false

    lastSwapTick = tick
    player.inventory.selectedSlot = slot
    mc.gameMode?.ensureHasSentCarriedItem()
    return true
}

private fun hasAllItems(): Boolean =
    findHotbarItem(Items.TNT_MINECART) != -1 &&
        findSlabSlot() != -1 &&
        findHotbarItem(Items.FLINT_AND_STEEL) != -1 &&
        findChargedCrossbow() != -1

private fun findHotbarItem(item: Item): Int {
    for (i in 0..<9) {
        if (player.inventory.getItem(i).`is`(item)) return i
    }
    return -1
}

/**
 * Vanilla `SlabBlock.canBeReplaced`: clicking the UP face of a **BOTTOM** slab while holding the
 * **same** slab item merges the two into a double slab instead of placing above. The result is two
 * slabs spent, a full block where the support was, and the cell we wanted still empty — and since
 * the trap's whole point is a HALF block the bolt can clear, that quietly ruins the geometry too.
 *
 * Only that exact combination merges (a TOP slab clicked on its UP face places above as normal), so
 * this reports the block to steer clear of rather than refusing the spot outright.
 */
private fun mergingSlabBlock(support: BlockPos): Block? {
    val state = world.getBlockState(support)
    if (!state.hasProperty(SlabBlock.TYPE)) return null
    if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) return null
    return state.block
}

/** Hotbar slab, optionally skipping the one type that would merge with the support ([avoid]). */
private fun findSlabSlot(avoid: Block? = null): Int {
    for (i in 0..<9) {
        val stack = player.inventory.getItem(i)
        val block = (stack.item as? BlockItem)?.block
        if (avoid != null && block === avoid) continue
        if (stack.`is`(ItemTags.SLABS)) return i
        if (block != null && block.defaultBlockState().`is`(BlockTags.SLABS)) return i
    }
    return -1
}

private fun findChargedCrossbow(): Int {
    for (i in 0..<9) {
        val stack = player.inventory.getItem(i)
        if (stack.item is CrossbowItem && CrossbowItem.isCharged(stack)) return i
    }
    return -1
}

// ── World queries ────────────────────────────────────────────────────────

private fun isRailStack(stack: ItemStack): Boolean = !stack.isEmpty && stack.`is`(ItemTags.RAILS)

private fun isRail(pos: BlockPos): Boolean = world.getBlockState(pos).`is`(BlockTags.RAILS)

/**
 * The trap's geometry needs specifically a BOTTOM slab: its solid lower half is what the bolt has
 * to clear while the fire sits above it. A bare `BlockTags.SLABS` test also matches TOP and DOUBLE
 * states — a solid upper half exactly where the bolt's corridor is supposed to be.
 */
private fun isBottomSlab(pos: BlockPos): Boolean {
    val state = world.getBlockState(pos)
    return state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM
}

private fun hasFire(pos: BlockPos): Boolean =
    world.getBlockState(pos.above()).block is BaseFireBlock

private fun canPlaceAt(pos: BlockPos): Boolean {
    val state = world.getBlockState(pos)
    if (!state.isAir && !state.canBeReplaced()) return false
    val below = pos.below()
    // A sturdy full top face — or a BOTTOM slab, whose UP face (surface at +0.5) also places into
    // this cell; that support is exactly the case [mergingSlabBlock] steers the slab choice around.
    return world.getBlockState(below).isFaceSturdy(world, below, Direction.UP) || isBottomSlab(below)
}

private fun findCart(pos: BlockPos): MinecartTNT? =
    world.getEntities(null, AABB(pos).inflate(0.35, 1.0, 0.35)) { it is MinecartTNT }
        .firstOrNull() as? MinecartTNT
