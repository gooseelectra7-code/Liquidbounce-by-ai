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

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.UseCooldownEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * AutoCart module
 *
 * Fire a Flame arrow into the sky with a short draw, and the module places a rail and a TNT
 * minecart where that arrow is going to land — the arrow comes down onto the cart and sets it off.
 *
 * The trigger is the *release* of the shot, detected on the outgoing `RELEASE_USE_ITEM` action
 * while a bow is drawn. At that instant the arrow's flight is simulated with vanilla ballistics
 * (speed from the draw charge, drag 0.99/tick, gravity 0.05/tick) and the first block it clips is
 * the landing site. A short draw is what makes the combo work at all: a lobbed arrow lands a few
 * blocks away — inside placement reach — and its long hang time pays for the whole placement
 * sequence many times over.
 *
 * Placement then runs as a small per-tick state machine: aim at the landing point (silent STRICT
 * rotation, so the visible camera stays on the sky shot), place the rail against the face the
 * arrow will strike, place the TNT cart on that rail, hand the hotbar slot back. Placements are
 * synthesized [BlockHitResult]s through `interaction.useItemOn` — the same idiom AutoCrystal uses —
 * because the crosshair is, by construction, pointing at the sky.
 */
@Suppress("TooManyFunctions")
object ModuleAutoCart : ClientModule("AutoCart", ModuleCategories.COMBAT) {

    /**
     * Which combo to run. The two share nothing but the TNT minecart:
     *
     * - [CartMode.SKY_SHOT] — the original behaviour of this module: lob a Flame arrow into the
     *   sky and build rail + cart under where it will land, so the arrow comes down and lights it.
     * - [CartMode.XBOW] — the lava cart bomb: you place a rail, the module adds the cart, a slab
     *   between you and it, lava on the slab, then threads a crossbow bolt through the lava into
     *   the cart. See `AutoCartXbow.kt`.
     */
    internal val mode by enumChoice("Mode", CartMode.SKY_SHOT)

    internal enum class CartMode(override val tag: String) : Tagged {
        SKY_SHOT("SkyShot"),
        XBOW("Xbow")
    }

    /** Settings that only apply to [CartMode.XBOW]. */
    internal object Xbow : ValueGroup("Xbow") {
        /** Keep the visible camera still while the module aims (silent rotations). */
        val silent by boolean("Silent", true)

        /** Freeze movement input for the length of the sequence. */
        val lockWalk by boolean("LockWalk", true)

        /** Swallow mouse input for the length of the sequence, so the aim is not fought. */
        val lockMouse by boolean("LockMouse", true)

        /**
         * Swallow the USE key for the length of the sequence.
         *
         * The sequence holds the hotbar on its own items, so any right-click of yours that lands
         * mid-sequence fires with OUR item at whatever you are looking at — most visibly, a click
         * while looking at the slab we just placed puts a second slab into it and vanilla merges
         * the two into a full block. Autoclickers make it near-certain. Leave this on.
         */
        val lockUse by boolean("LockUse", true)

        /** How far from the eye a rail placement is still adopted as a trap site. */
        val range by float("Range", 5.0f, 1.0f..8.0f)

        /** Chat-prints the exact gate that rejected a rail, instead of failing silently. */
        val debug by boolean("Debug", false)
    }

    init {
        tree(Xbow)
    }

    /**
     * Only trigger on a Flame bow. This is the default because the falling arrow must *ignite* the
     * cart — an unlit TNT cart under a plain arrow is just a parked minecart.
     */
    private val flameOnly by boolean("FlameOnly", true)

    /**
     * Largest draw (in ticks) that still counts as the deliberate short lob. Below 3 ticks vanilla
     * does not fire an arrow at all (charge < 0.1), so the effective window is 3..this.
     */
    private val maxDraw by int("MaxDraw", 10, 3..20, "ticks")

    /**
     * How far above the horizon the shot must point to count as "into the sky".
     */
    private val minPitch by float("MinPitch", 30f, 0f..90f, "°")

    /**
     * Degrees the server-side rotation may still be off the placement point before we place
     * anyway. The synthesized hit does not need the rotation to resolve — this is purely how
     * long we are willing to wait for the aim to look honest.
     */
    private val rotationTolerance by float("RotationTolerance", 15f, 1f..90f, "°")

    /**
     * Read the landing off the arrow the SERVER made, instead of predicting it from the shot.
     *
     * The prediction cannot be exact, and not because the physics are hard — because the bow is not
     * deterministic. It fires with `inaccuracy = 1.0`, and `Projectile.getMovementToShoot` adds
     * `random.triangle(0.0, 0.0172275)` to EACH AXIS of the normalized direction before scaling. That
     * roll happens server-side when the arrow is created, so at the moment we release we do not have
     * the number and cannot have it. On a long lob those ~1° of scatter move the landing point by
     * the better part of a block — enough for the arrow to miss the cart we placed for it, which is
     * exactly the "sometimes it breaks" failure.
     *
     * So instead of guessing, the module waits for the arrow's spawn packet, which carries the real
     * post-roll velocity, and simulates from that. The cost is the round trip; a lobbed arrow hangs
     * for sixty ticks or more, so a few of them are affordable — the mode is built on that hang time
     * paying for the placement sequence many times over.
     *
     * Off falls back to predicting at release, which is faster but scatters.
     */
    private val exactTrajectory by boolean("ExactTrajectory", true)

    private val cooldown = Chronometer()

    private enum class Phase { IDLE, PLACE_RAIL, PLACE_CART, RESTORE }

    private var phase = Phase.IDLE
    private var landing: BlockHitResult? = null
    private var railCell: BlockPos? = null
    private var originalSlot = -1
    private var phaseTicks = 0
    private var sequenceTicks = 0

    /** Ticks left to wait for our own arrow's spawn packet; 0 = not expecting one. */
    private var awaitingArrowTicks = 0

    /** Where our arrow will be born (eye minus 0.1), captured at the release. Read on the netty thread. */
    @Volatile
    private var expectedArrowSpawn: Vec3? = null

    /** Spawn + velocity of the matched arrow, published by the netty thread, drained by the tick handler. */
    @Volatile
    private var pendingArrowFlight: Pair<Vec3, Vec3>? = null

    /**
     * The arrow the server just made for us, matched by its spawn point.
     *
     * Arrows spawn at the shooter's eye minus 0.1, so anything appearing within a metre of where
     * OUR eye was at the release is the shot we are waiting for. Matching on identity would be
     * nicer, but the spawn packet's owner field is not populated for arrows, and this window is
     * short enough that another player's arrow cannot plausibly be born inside our own head.
     *
     * Incoming [PacketEvent]s fire on the NETTY thread (AGENTS.md), so this handler only reads the
     * packet and publishes what it saw — the ballistics, the world queries and the state machine
     * all run from the tick handler on the main thread.
     */
    @Suppress("unused")
    private val arrowSpawnHandler = handler<PacketEvent> { event ->
        val expected = expectedArrowSpawn ?: return@handler
        if (event.origin != TransferOrigin.INCOMING) return@handler

        val packet = event.packet as? ClientboundAddEntityPacket ?: return@handler
        if (packet.type != EntityType.ARROW && packet.type != EntityType.SPECTRAL_ARROW) return@handler

        val spawn = Vec3(packet.x, packet.y, packet.z)
        if (spawn.distanceTo(expected) > ARROW_MATCH_RADIUS) return@handler

        // The velocity in the packet is post-roll: this is the arrow that will actually fly.
        pendingArrowFlight = spawn to Vec3(packet.movement.x, packet.movement.y, packet.movement.z)
    }

    private fun beginPlacement(hit: BlockHitResult) {
        // Out of placement reach — a flat shot, or a lob over a cliff. Self-gates the "sky" intent:
        // only a genuinely lobbed arrow comes down close enough.
        if (hit.location.distanceTo(player.eyePosition) > player.blockInteractionRange()) {
            return
        }

        val cell = hit.blockPos.relative(hit.direction)

        // The rail must be able to exist there: a free cell over a sturdy top face (that is what
        // BaseRailBlock's survival check wants), or a rail left from a previous run. An arrow that
        // clipped a wall face or an overhang otherwise starts a sequence that can only time out
        // with the hotbar stolen.
        val cellState = world.getBlockState(cell)
        val support = cell.below()
        val canHostRail = cellState.block is BaseRailBlock ||
            (cellState.canBeReplaced() && world.getBlockState(support).isFaceSturdy(world, support, Direction.UP))
        if (!canHostRail) {
            return
        }

        // Both items confirmed before the silent aim starts — finding out mid-sequence wastes it.
        if (findHotbarSlot { isRail(it) } == null || findHotbarSlot { it.`is`(Items.TNT_MINECART) } == null) {
            return
        }

        landing = hit
        railCell = cell
        originalSlot = player.inventory.selectedSlot
        phase = Phase.PLACE_RAIL
        phaseTicks = 0
        sequenceTicks = 0
    }

    /**
     * The release of the shot. Fired on the main thread from `MultiPlayerGameMode.releaseUsingItem`
     * *before* the player's use state is cleared, so the drawn bow and its charge are still
     * readable here.
     */
    @Suppress("unused", "ComplexCondition")
    private val releaseHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING) return@handler

        // Xbow builds its trap around a rail the player places by hand.
        val useOn = event.packet as? ServerboundUseItemOnPacket
        if (useOn != null && Xbow.debug) {
            // Deliberately outside the mode check: this is what distinguishes "wrong mode" from
            // "the packet never reaches the module at all".
            chat(
                "§8[Xbow] §7use-on seen §8| §7mode=§f$mode §8| §7hand=§f" +
                    "${player.getItemInHand(useOn.hand).item}",
                this@ModuleAutoCart
            )
        }

        if (mode == CartMode.XBOW) {
            useOn?.let { xbowOnRailPlace(it.hitResult, it.hand) }
            return@handler
        }

        val packet = event.packet as? ServerboundPlayerActionPacket ?: return@handler
        if (packet.action != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) return@handler

        if (phase != Phase.IDLE || !cooldown.hasElapsed(COOLDOWN_MS)) return@handler

        val bow = player.useItem
        if (!player.isUsingItem || !bow.`is`(Items.BOW)) return@handler
        if (flameOnly && bow.getEnchantment(Enchantments.FLAME) == 0) return@handler

        // No arrow, no shot: vanilla sends the release packet regardless, but nothing will fly.
        if (player.getProjectile(bow).isEmpty) return@handler

        // Skyward, short draw. The charge floor is vanilla's own — below 0.1 the bow does not fire.
        if (player.xRot > -minPitch) return@handler
        val charge = drawCharge(player.ticksUsingItem)
        if (player.ticksUsingItem > maxDraw || charge < MIN_FIRE_CHARGE) return@handler

        if (exactTrajectory) {
            // Wait for the arrow the server actually created — see [exactTrajectory]. The spawn
            // point is captured NOW: by the time the packet arrives we may have moved.
            expectedArrowSpawn = player.eyePosition.subtract(0.0, ARROW_SPAWN_EYE_OFFSET, 0.0)
            awaitingArrowTicks = ARROW_SPAWN_WAIT_TICKS
            return@handler
        }

        val hit = simulateArrowLanding(charge * ARROW_SPEED_PER_CHARGE) ?: return@handler
        beginPlacement(hit)
    }

    /**
     * Xbow runs here rather than on the plain tick handler, and the difference is load-bearing:
     * [net.ccbluex.liquidbounce.event.events.RotationUpdateEvent] fires immediately BEFORE
     * `RotationManager.update()`, so a rotation submitted now is stepped this same tick and rides
     * out on this tick's movement packet — one tick to reach the wire instead of two. It is also
     * ahead of `handleKeybinds` and of vanilla's sprint/sneak actions, which is what keeps our
     * hotbar swap the first conflicting packet in its window (Grim PacketOrderE).
     */
    @Suppress("unused")
    private val xbowHandler = handler<RotationUpdateEvent> {
        if (mode == CartMode.XBOW) {
            xbowTick()
        }
    }

    @Suppress("unused")
    private val sequenceHandler = tickHandler {
        if (awaitingArrowTicks > 0 && --awaitingArrowTicks == 0) {
            expectedArrowSpawn = null
        }

        // Drain what the netty thread published — everything from here on is main-thread.
        pendingArrowFlight?.let { (spawn, velocity) ->
            pendingArrowFlight = null
            expectedArrowSpawn = null
            awaitingArrowTicks = 0
            if (mode == CartMode.SKY_SHOT && phase == Phase.IDLE) {
                simulateArrowFrom(spawn, velocity)?.let(::beginPlacement)
            }
        }

        // A mid-sequence mode flip would otherwise freeze the other machine with the hotbar
        // stolen: this handler skips the SkyShot phases in Xbow mode, and xbowTick does not run
        // outside it — so whichever machine the flip abandoned is wound down here.
        if (mode == CartMode.XBOW) {
            if (phase != Phase.IDLE) {
                reset()
            }
            return@tickHandler
        }
        if (xbowActive) {
            xbowReset()
        }

        if (phase == Phase.IDLE) {
            return@tickHandler
        }

        sequenceTicks++
        if (sequenceTicks > SEQUENCE_TIMEOUT_TICKS) {
            reset()
            return@tickHandler
        }

        when (phase) {
            Phase.PLACE_RAIL -> tickPlaceRail()
            Phase.PLACE_CART -> tickPlaceCart()
            Phase.RESTORE -> {
                restoreSlot()
                reset()
            }
            Phase.IDLE -> Unit
        }
    }

    // ---------------------------------------------------------------- phases

    private fun tickPlaceRail() {
        val hit = landing ?: return reset()
        val cell = railCell ?: return reset()

        // A rail is already sitting there from a previous run — nothing to place, go straight on.
        if (world.getBlockState(cell).block is BaseRailBlock) {
            phase = Phase.PLACE_CART
            phaseTicks = 0
            return
        }

        if (!aimSettledAt(hit.location)) {
            return
        }

        // Re-checked at the click, not only at the trigger: the aim settle takes ticks, and the
        // player may have drifted out of range in them (Grim FarPlace).
        if (!withinReach(hit.blockPos)) {
            return reset()
        }

        val slot = findHotbarSlot { isRail(it) } ?: return reset()

        swapAndAnnounce(slot)
        placeWithVanillaSwing(hit)

        phase = Phase.PLACE_CART
        phaseTicks = 0
    }

    private fun tickPlaceCart() {
        val cell = railCell ?: return reset()

        // The cart click only works against a rail block. If the rail place failed (unsupported
        // cell, occupied block), waiting will not fix it — let the timeout hand the slot back.
        if (world.getBlockState(cell).block !is BaseRailBlock) {
            phaseTicks++
            return
        }

        val click = railClickHit(cell)
        if (!aimSettledAt(click.location)) {
            return
        }
        if (!withinReach(cell)) {
            return reset()
        }

        val slot = findHotbarSlot { it.`is`(Items.TNT_MINECART) } ?: return reset()

        swapAndAnnounce(slot)
        placeWithVanillaSwing(click)

        phase = Phase.RESTORE
        phaseTicks = 0
    }

    /** Grim FarPlace: never click a box outside block-interaction range. */
    private fun withinReach(pos: BlockPos): Boolean {
        val reach = player.blockInteractionRange() - REACH_MARGIN
        return AABB(pos).distanceToSqr(player.eyePosition) <= reach * reach
    }

    /**
     * Vanilla decides whether a use swings — and therefore whether a swing packet exists at all —
     * by the interaction result. Mirroring that keeps the packet stream exactly what a real click
     * makes; HIDE_CLIENT then sends the same swing packet while keeping the visible arm still.
     */
    private fun placeWithVanillaSwing(hit: BlockHitResult) {
        val result = interaction.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        if (result is InteractionResult.Success && result.swingSource == InteractionResult.SwingSource.CLIENT) {
            SwingMode.HIDE_CLIENT.swing(InteractionHand.MAIN_HAND)
        }
    }

    // ---------------------------------------------------------------- aiming

    /**
     * Requests a silent STRICT rotation at [point] and reports whether the server-side rotation
     * has caught up (or we are done waiting). The placement itself never needs the rotation — the
     * hit result is synthesized — but a place packet whose rotation points at the sky is the kind
     * of thing that files itself with an anticheat.
     */
    private fun aimSettledAt(point: Vec3): Boolean {
        val wanted = Rotation.lookingAt(point, player.eyePosition)

        RotationManager.setRotationTarget(
            plan = RotationTarget(
                rotation = wanted,
                entity = null,
                processors = listOf(PassThroughRotation),
                ticksUntilReset = 2,
                resetThreshold = 1f,
                considerInventory = false,
                movementCorrection = MovementCorrection.STRICT
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this
        )

        phaseTicks++
        return RotationManager.serverRotation.angleTo(wanted) <= rotationTolerance ||
            phaseTicks >= AIM_TIMEOUT_TICKS
    }

    /**
     * A click on the rail as a real crosshair would produce it: the face points back at the player
     * (so the click looks like it came from where they stand), and the hit point lies ON the
     * rail's flat 2px box — top face at +0.125 — not at the top of the cell. A hit location
     * floating 0.875 above the clicked shape is the kind of thing a geometry check reads as
     * fabricated. Only the block position matters to `MinecartItem` itself.
     */
    private fun railClickHit(cell: BlockPos): BlockHitResult {
        val face = Direction.getApproximateNearest(player.eyePosition.subtract(Vec3.atCenterOf(cell)))
        val location = when (face) {
            Direction.UP -> Vec3(cell.x + 0.5, cell.y + RAIL_SURFACE, cell.z + 0.5)
            Direction.DOWN -> Vec3(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
            else -> Vec3(
                cell.x + 0.5 + face.stepX * 0.5,
                cell.y + RAIL_SURFACE / 2.0,
                cell.z + 0.5 + face.stepZ * 0.5
            )
        }
        return BlockHitResult(location, face, cell, false)
    }

    // ---------------------------------------------------------------- ballistics

    private fun drawCharge(ticks: Int): Float {
        val f = ticks / 20f
        return ((f * f + f * 2f) / 3f).coerceAtMost(1f)
    }

    /**
     * Flies the arrow the server is about to spawn and returns the first block it strikes.
     *
     * Vanilla per tick: clip the segment `pos → pos + velocity`, then move, then apply drag 0.99
     * and gravity 0.05. Spawn is at eye height minus 0.1, direction is the exact look vector, and
     * the shooter's own velocity is inherited (vertical part only while airborne) — all straight
     * out of `AbstractArrow` / `Projectile.shootFromRotation`. Divergence is ignored: at 1.0 it is
     * fractions of a degree, far below the size of a block face.
     */
    private fun simulateArrowLanding(speed: Float): BlockHitResult? = simulateArrowFrom(
        player.eyePosition.subtract(0.0, ARROW_SPAWN_EYE_OFFSET, 0.0),
        Vec3(player.lookAngle.x, player.lookAngle.y, player.lookAngle.z)
            .normalize()
            .scale(speed.toDouble())
            .add(
                player.deltaMovement.x,
                if (player.onGround()) 0.0 else player.deltaMovement.y,
                player.deltaMovement.z
            )
    )

    /**
     * Flies an arrow from a known start and returns the first block it strikes. Vanilla per tick:
     * clip the segment, move, then drag 0.99 and gravity 0.05 — in that order.
     */
    private fun simulateArrowFrom(start: Vec3, initialVelocity: Vec3): BlockHitResult? {
        var pos = start
        var velocity = initialVelocity

        repeat(MAX_SIMULATION_TICKS) {
            val next = pos.add(velocity)

            val hit = world.clip(
                ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            )
            if (hit.type == HitResult.Type.BLOCK) {
                return hit
            }

            pos = next
            velocity = velocity.scale(ARROW_DRAG).subtract(0.0, ARROW_GRAVITY, 0.0)
        }

        return null
    }

    // ---------------------------------------------------------------- hotbar

    /**
     * Real slot change with an immediate carried-item announce — the AutoCrystal idiom. The server
     * must believe the rail (or cart) is held when the use packet arrives, or the placement
     * resolves against the bow.
     */
    private fun swapAndAnnounce(slot: Int) {
        player.inventory.selectedSlot = slot
        interaction.ensureHasSentCarriedItem()
    }

    private fun restoreSlot() {
        if (originalSlot in 0..8) {
            player.inventory.selectedSlot = originalSlot
            interaction.ensureHasSentCarriedItem()
        }
        originalSlot = -1
    }

    private inline fun findHotbarSlot(predicate: (ItemStack) -> Boolean): Int? {
        for (i in 0..8) {
            val stack = player.inventory.getItem(i)
            if (!stack.isEmpty && predicate(stack)) {
                return i
            }
        }
        return null
    }

    private fun isRail(stack: ItemStack): Boolean =
        stack.item in RAIL_ITEMS

    // ---------------------------------------------------------------- state

    private fun reset() {
        restoreSlot()
        phase = Phase.IDLE
        landing = null
        railCell = null
        phaseTicks = 0
        sequenceTicks = 0
        awaitingArrowTicks = 0
        expectedArrowSpawn = null
        pendingArrowFlight = null
        cooldown.reset()
    }

    /** Movement lock for the Xbow sequence — the trap geometry assumes you stay put. */
    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (mode != CartMode.XBOW || !Xbow.lockWalk || !xbowActive) return@handler
        event.directionalInput = DirectionalInput.NONE
        event.jump = false
        event.sneak = false
    }

    /** Mouse lock, so the player cannot drag the aim off the trap mid-sequence. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { event ->
        if (mode == CartMode.XBOW && Xbow.lockMouse && xbowActive) {
            event.cancelEvent()
        }
    }

    /**
     * The other half of the use-key lock. Eating `keyUse.clickCount` kills queued clicks, but
     * vanilla also re-fires while the key is simply held down, once `rightClickDelay` reaches zero.
     * This event lands right after `startUseItem` sets that delay, so holding it up for the rest of
     * the sequence stops the repeat.
     */
    @Suppress("unused")
    private val useCooldownHandler = handler<UseCooldownEvent> { event ->
        if (mode == CartMode.XBOW && Xbow.lockUse && xbowActive) {
            event.cooldown = maxOf(event.cooldown, HELD_USE_LOCK_TICKS)
        }
    }

    override fun onEnabled() {
        phase = Phase.IDLE
        landing = null
        railCell = null
        originalSlot = -1
        awaitingArrowTicks = 0
        expectedArrowSpawn = null
        pendingArrowFlight = null
        xbowReset()
    }

    override fun onDisabled() {
        if (phase != Phase.IDLE) {
            reset()
        }
        awaitingArrowTicks = 0
        expectedArrowSpawn = null
        pendingArrowFlight = null
        xbowReset()
    }

    private val RAIL_ITEMS: Set<Item> = setOf(
        Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL
    )

    private const val COOLDOWN_MS = 1000L
    private const val MIN_FIRE_CHARGE = 0.1f
    private const val ARROW_SPEED_PER_CHARGE = 3.0f
    private const val ARROW_SPAWN_EYE_OFFSET = 0.1
    private const val ARROW_SPAWN_WAIT_TICKS = 20
    private const val ARROW_MATCH_RADIUS = 1.0
    private const val ARROW_DRAG = 0.99
    private const val ARROW_GRAVITY = 0.05
    private const val MAX_SIMULATION_TICKS = 120

    /** Top of the flat rail shape (`Block.box(0, 0, 0, 16, 2, 16)`). */
    private const val RAIL_SURFACE = 0.125
    private const val REACH_MARGIN = 0.05
    private const val AIM_TIMEOUT_TICKS = 5
    private const val SEQUENCE_TIMEOUT_TICKS = 30
    private const val HELD_USE_LOCK_TICKS = 4
}

/**
 * Pass-through processor: the placement aim is a two-tick snap onto a fixed point; smoothing it
 * would only delay the placement past the arrow.
 */
private object PassThroughRotation : RotationProcessor {
    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ) = targetRotation
}
