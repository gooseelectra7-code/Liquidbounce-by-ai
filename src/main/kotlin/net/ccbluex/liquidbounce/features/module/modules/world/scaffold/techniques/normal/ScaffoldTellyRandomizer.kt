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
package net.ccbluex.liquidbounce.features.module.modules.world.scaffold.techniques.normal

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold.ScaffoldRotationValueGroup
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Telly `ResetMode = Randomized`: the per-jump rhythm of a telly bridge, randomised inside the bounds the jump itself
 * allows (2026-10-04, the user's ask, aimed at Polar and Intave).
 *
 * One jump is one cycle: run facing forward, turn back onto the block (a coarse swing, then a correction), hold the aim
 * while the blocks go down, turn forward again before touching down. Randomised per cycle:
 * - how long we keep facing forward after the jump: [minForward] up to the LAST tick from which the bridge still
 *   reaches the landing point;
 * - how long the aim stays on the block: at least [minHold] ticks, then up to the last tick from which the rotation is
 *   forward again by the next jump;
 * - where on the face we aim, [yawSpread] / [pitchSpread] (pitch much narrower, the user's spec), kept on the face;
 * - the coarse swing's miss, a multiple of the same spreads.
 *
 * ## The upper bounds are derived, not configured (the user's choice)
 *
 * The flight is simulated every air tick from the current state ([SimulatedPlayer], fresh - the shared
 * `PlayerSimulationCache` is one tick old at the tick head), with the player's camera-relative input
 * ([ModuleScaffold.rawInput], read before `MixinKeyboardInput.transformDirection` rotates it into the silent frame;
 * the simulation moves along `player.yRot`, `SimulatedPlayer.kt:438`). Touchdown is the first step that lands or
 * drops below the top of the row we took off from; the landing is SUPPORTED when that step lands. The gap count is a
 * grid walk of the straight line from the take-off point to the touchdown point over that row: a column is a gap when
 * neither that row nor the one above it (where `SameY = Off` raises the bridge) has a collision shape.
 *
 * - Turn deadline ([slack]): a block placed in tick `j` is there for the movement of tick `j`, the aim requested now
 *   is on the wire after `calculateTicks` updates (0 = this tick for the factor smoothers, a step count minus one,
 *   `FactorAngleSmooth.calculateTicks`), and placements are `1 + Delay.max` ticks apart. [SAFETY_TICKS] covers the
 *   estimate's own slack: `approximatelyEquals` stops the count 2 degrees short, Acceleration returns 0 on a pure
 *   yaw turn, and the aim moves between targets. Model, not measurement.
 * - Release ([mayRelease]): only once the landing is supported - NOT once the line's gaps are gone, the scaffold
 *   builds its own 4-connected path and may never fill a corner of ours. The jump is taken in the input of the first
 *   tick on the ground, so the rotation has to be forward by that tick's `RotationManager.update()`; returning takes
 *   `calculateTicks(player.rotation)`.
 *
 * Priorities when they collide: not falling > [minHold] > speed. A deadline overrides [minForward]; [minHold]
 * overrides the release deadline (we then land facing back, and the jump waits on the ground until the rotation is
 * forward, [facesForward]); nothing releases the aim while the landing is unsupported.
 *
 * ## Where on the face
 *
 * The spread is anchored to the centre of the face, not to the technique's aim point: `Stabilized` and
 * `NearestRotation` aim at the face point nearest to the CURRENT server rotation (`FaceTargetPositionFactory.kt:122-126`),
 * so an offset added to that would be added again every tick and walk across the face. In this mode the technique's
 * `RotationMode` is therefore only the fallback for a face whose centre the eyes cannot see, and then without a spread.
 *
 * ## Why the request expires after one tick
 *
 * Telly `Reset` simply stops requesting, so the old request lives on for the user's `TicksUntilReset` (default 5,
 * `RequestHandler.request`/`getActiveRequestValue`) - longer than the player spends on the ground between jumps. Every
 * request of this mode goes through [expiringNextTick], so the tick we stop asking is the tick the return starts, and
 * the return is RotationManager's own reset path (the smoother towards the real rotation, landing exactly on it).
 *
 * ## Why the jump waits for the rotation
 *
 * With `MovementCorrection = Silent` the jump's sprint boost is turned into the silent yaw
 * (`MixinLivingEntity.hookFixRotation`) and the input into the silent frame (`MixinKeyboardInput.transformDirection`).
 * A jump taken while still facing back throws the boost backwards. Cost: while it waits nothing is aimed or placed on
 * the ground; Scaffold's `Ledge` is what keeps a slow return from walking off the last block.
 *
 * ## Honest scope
 *
 * Polar and Intave are closed source - nothing here was checked against them, and whether this passes them is up to
 * the playtest. The distributions (a triangle for the spreads and the forward time, the smaller of two draws for the
 * extra hold, a coarse miss of 1.5..3 spreads with a random sign) are GUESSES at what a hand does, not fitted to a
 * recording. The simulated path is the raw input along the camera yaw; the real one is that input rounded to 8
 * directions of the silent yaw (`MixinKeyboardInput.java:124-134`), up to ~22 degrees apart. Grim: no packet of its
 * own; the placement gate is still `ModuleScaffold`'s ray test of the outgoing rotation (`RotationPlace`), and the
 * spread is shrunk until that ray stays on the face. Only `RotationTiming = Normal` with `MovementCorrection` Silent or
 * Off is driven ([supportsMovementCorrection]); otherwise the mode behaves like `Reset` with `Straight = 0`.
 *
 * Known model limits, all on the safe side (the aim is held longer, never released early): support lower than ~0.3
 * below the take-off row's top (bottom slabs, carpet) and entities are not counted as a landing; a take-off from a
 * slab or a path block measures the row from the block under it. NOT playtested.
 */
object ScaffoldTellyRandomizer : ValueGroup("Randomizer"), MinecraftShortcuts {

    /** Ticks after the jump we keep facing forward at the least. The most is the turn deadline. */
    private val minForward by int("MinForward", 2, 1..6, "ticks")

    /** Ticks the aim stays on the block before turning back, at the least. The most is the release deadline. */
    private val minHold by int("MinHold", 2, 1..8, "ticks")

    /** Half-width of where on the face we aim, horizontally. */
    private val yawSpread by float("YawSpread", 3f, 0f..10f, "°")

    /** Half-width of where on the face we aim, vertically. Much narrower than [yawSpread] on purpose. */
    private val pitchSpread by float("PitchSpread", 0.6f, 0f..5f, "°")

    private enum class Phase {
        /** Facing forward (no request), until the drawn time or the deadline. */
        FORWARD,

        /** The swing back: aimed at the block plus this cycle's miss. */
        COARSE,

        /** The correction onto the face, then the hold. */
        FINE,

        /** Turning forward again (no request). */
        BACK
    }

    /** Ticks kept in hand for what the turn estimate gets wrong, see the class KDoc. */
    private const val SAFETY_TICKS = 2

    /** Longest flight we simulate before giving up on a prediction. A sprint jump lands after about 12. */
    private const val MAX_FLIGHT_TICKS = 40

    /** Grid walk guard. A sprint jump spans about 4 columns. */
    private const val MAX_COLUMNS = 24

    /** A take-off point farther than this is not this flight's (mode switched in the air, a tower in between). */
    private const val MAX_TAKEOFF_DISTANCE = 6.0

    /** Highest a flight from [row] can carry us above its top (a jump peaks at ~1.25; room for Jump Boost). */
    private const val MAX_RISE = 3.0

    /**
     * The landing counts as supported only over a column this far inside the hitbox (half-width 0.3): the simulated
     * path is not the real one to the centimetre (input one tick old, Silent's rounding to 8 directions).
     */
    private const val LANDING_MARGIN = 0.15

    /** An aim within this of the outgoing rotation counts as on the wire. */
    private const val REACHED_ANGLE = 1.5f

    /** The jump is taken once the outgoing rotation is this close to the camera. */
    private const val FORWARD_TOLERANCE = 1f

    /** The swing gives up on reaching its miss after this many ticks and corrects anyway. */
    private const val MAX_COARSE_TICKS = 3

    /** The swing misses by this many spreads. */
    private const val COARSE_MIN = 1.5
    private const val COARSE_MAX = 3.0

    /** Spread kept this far inside the largest one whose ray still lands on the face (sub-quantum dither margin). */
    private const val EDGE_MARGIN = 0.8f
    private const val SEARCH_STEPS = 8

    private var phase = Phase.FORWARD
    private var desiredForward: Int? = null
    private var coarseTicks = 0
    private var arrived = false

    /**
     * Ticks since the fine aim first reached the wire. The aim keeps following the current target after that (the next
     * block sits at a slightly different angle), so this counts ticks spent holding the block, not ticks on one exact
     * rotation.
     */
    private var heldTicks = 0
    private var releaseAt: Int? = null
    private var lastAim: Rotation? = null

    /** Where and over which block row we last stood. Recorded here, on every ground tick, so it is never stale. */
    private var takeoff: Vec3? = null
    private var row: Int? = null

    /** The tick [rotation] last ran on. A gap means the mode was off (or another world): our state is not this flight's. */
    private var lastCallTick: Int? = null

    private var fineYaw = 0f
    private var finePitch = 0f
    private var coarseYaw = 0f
    private var coarsePitch = 0f

    private val hasSpread get() = yawSpread > 0f || pitchSpread > 0f

    /**
     * The jump is taken only once the outgoing rotation is forward again, see the class KDoc. Another module holding
     * the rotation therefore holds the jump as well.
     */
    val facesForward: Boolean
        get() = RotationManager.currentRotation?.let { it.angleTo(player.rotation) <= FORWARD_TOLERANCE } ?: true

    fun reset() {
        endCycle()
        takeoff = null
        row = null
        lastCallTick = null
    }

    /**
     * The flight model moves the raw input along the CAMERA yaw. That is what happens under Silent (the input is
     * turned into the silent frame, `MixinKeyboardInput.transformDirection`) and Off (nothing is turned). Under Strict
     * the velocity follows the silent yaw with the input untouched (`RotationManager.velocityHandler`), so holding W
     * while aimed back flies backwards and every prediction here would be wrong; ChangeLook turns the camera itself.
     * Those fall back to Reset.
     */
    val supportsMovementCorrection: Boolean
        get() = ScaffoldRotationValueGroup.toRotationTarget(Rotation.ZERO).movementCorrection.let {
            it == MovementCorrection.SILENT || it == MovementCorrection.OFF
        }

    /**
     * The same plan, expiring next tick unless asked again - see the class KDoc.
     */
    fun expiringNextTick(plan: RotationTarget) = RotationTarget(
        plan.rotation,
        plan.entity,
        plan.processors,
        1,
        plan.resetThreshold,
        plan.considerInventory,
        plan.movementCorrection,
        plan.whenReached
    )

    /**
     * The rotation to request this tick, or null to request none (facing forward / turning back).
     *
     * [aim] is the technique's own aim at a placement target, used for the tower and for a face whose centre cannot be
     * seen.
     */
    fun rotation(target: BlockPlacementTarget?, aim: (BlockPlacementTarget?) -> Rotation?): Rotation? {
        val tick = player.tickCount
        val lastTick = lastCallTick
        if (lastTick == null || tick - lastTick !in 0..1) {
            reset()
        }
        lastCallTick = tick

        if (player.onGround()) {
            takeoff = player.position()
            row = player.blockPosition().y - 1
        }

        if (ModuleScaffold.isTowering && ScaffoldTellyFeature.aimOnTower) {
            endCycle()
            return aim(target)
        }

        if (player.onGround()) {
            endCycle()
            return null
        }

        val flight = predictFlight()
        val aimPoint = aimPoint(target, flight, aim)

        return step(flight, aimPoint).also {
            ModuleScaffold.debugParameter("Telly.Phase") { phase }
            ModuleScaffold.debugParameter("Telly.Land") { flight?.land }
            ModuleScaffold.debugParameter("Telly.Supported") { flight?.supported }
            ModuleScaffold.debugParameter("Telly.Needed") { flight?.needed }
            ModuleScaffold.debugParameter("Telly.Slack") {
                slack(flight, aimPoint).takeIf { s -> s != Int.MAX_VALUE }
            }
            ModuleScaffold.debugParameter("Telly.Held") { heldTicks }
        }
    }

    /**
     * Where to aim this tick. The centre of the target's face (or, before the technique has a target, of the face the
     * first gap is filled against), when the eyes can see it; the spread is anchored there. Otherwise the technique's
     * own aim, without a spread - see the class KDoc for why the two must not be combined.
     */
    private fun aimPoint(target: BlockPlacementTarget?, flight: Flight?, aim: (BlockPlacementTarget?) -> Rotation?):
        AimPoint? {
        val face = target?.let { AimFace(it.interactedBlockPos, it.direction, it.minPlacementY) }
            ?: flight?.bridgeFace()
        val centre = face?.let { Rotation.lookingAt(it.centre, player.eyePosition) }

        if (face != null && centre != null && face.isHitBy(centre)) {
            return AimPoint(centre, face, spread = true)
        }

        if (target != null) {
            return aim(target)?.let { AimPoint(it, face, spread = false) }
        }

        // The face the first gap goes against is hidden from here (we have not passed the edge yet): aim at it anyway,
        // nothing can be placed this tick, so the spread costs nothing.
        return centre?.let { AimPoint(it, face, spread = true) }
    }

    private fun step(flight: Flight?, aimPoint: AimPoint?): Rotation? {
        if (phase == Phase.FORWARD) {
            if (!shouldTurn(flight, aimPoint)) {
                return null
            }

            startTurn()
        }

        if (phase == Phase.BACK) {
            if (flight != null && flight.supported) {
                return null
            }

            // The landing lost its support after we let go (the server undid a placement, the landing moved), or can no
            // longer be predicted at all: aim straight onto the face again - a correction, not a new swing.
            phase = Phase.FINE
            arrived = false
            heldTicks = 0
            releaseAt = null
        }

        if (phase == Phase.COARSE) {
            val coarse = aimPoint?.takeIf { it.spread }?.let {
                offset(it.rotation, fineYaw + coarseYaw, finePitch + coarsePitch)
            }
            val reached = coarse != null && isOnWire(coarse)
            coarseTicks++

            if (coarse != null && !reached && coarseTicks <= MAX_COARSE_TICKS && slack(flight, aimPoint) > 0) {
                lastAim = coarse
                return coarse
            }

            phase = Phase.FINE
        }

        val fine = aimPoint?.let(::aimAround) ?: lastAim

        if (!arrived && fine != null && isOnWire(fine)) {
            arrived = true
        }
        if (arrived) {
            heldTicks++
        }

        // Evaluated whether or not there is an aim point this tick: once the last gap is filled the technique has no
        // target any more (`TargetFinding.kt:255-260`), and that is exactly when we want to let go.
        if (mayRelease(flight)) {
            phase = Phase.BACK
            return null
        }

        if (fine != null) {
            lastAim = fine
        }
        return fine
    }

    private fun shouldTurn(flight: Flight?, aimPoint: AimPoint?): Boolean {
        val airTicks = player.airTicks

        // No touchdown within reach of the simulation: nothing proves either way, so the plain minimum applies and
        // the aim then stays until we land (mayRelease refuses without a flight).
        flight ?: return airTicks >= minForward

        // Nothing to bridge on this jump - turning round for no block is not a thing a player does.
        if (flight.supported) {
            return false
        }

        val slack = slack(flight, aimPoint)
        val desired = desiredForward ?: drawForward(airTicks, slack).also { desiredForward = it }

        return airTicks >= desired || slack <= 0
    }

    /**
     * Ticks we can still wait before turning and have the landing filled before touchdown, see the class KDoc.
     * [Int.MAX_VALUE] when there is nothing to wait for.
     */
    private fun slack(flight: Flight?, aimPoint: AimPoint?): Int {
        if (flight == null || flight.supported) {
            return Int.MAX_VALUE
        }

        val turn = (aimPoint?.rotation?.let(::ticksTo) ?: 0) + if (aimPoint?.spread == true) 1 else 0
        val perBlock = 1 + ModuleScaffold.maxPlacementDelay

        return flight.land - 1 - SAFETY_TICKS - turn - (flight.needed - 1) * perBlock
    }

    private fun mayRelease(flight: Flight?): Boolean {
        // Without a flight nothing proves the landing is covered, so the aim stays until touchdown.
        if (flight == null || !flight.supported || heldTicks < minHold) {
            releaseAt = null
            return false
        }

        val window = flight.land - ticksTo(player.rotation)
        val at = releaseAt ?: (heldTicks + earlyInt(window.coerceAtLeast(0))).also { releaseAt = it }

        return heldTicks >= at || window <= 0
    }

    /**
     * The smoother's estimate, capped: Acceleration returns `Int.MAX_VALUE` on a turn with an exactly zero yaw step
     * (`AccelerationAngleSmooth.calculateTicks`), which would overflow the deadline sums. Anything past a flight means
     * "turn now" either way.
     */
    private fun ticksTo(rotation: Rotation) =
        ScaffoldRotationValueGroup.calculateTicks(rotation).coerceIn(0, MAX_FLIGHT_TICKS)

    private fun startTurn() {
        fineYaw = triangle() * yawSpread
        finePitch = triangle() * pitchSpread
        coarseYaw = sign() * yawSpread * Random.nextDouble(COARSE_MIN, COARSE_MAX).toFloat()
        coarsePitch = sign() * pitchSpread * Random.nextDouble(COARSE_MIN, COARSE_MAX).toFloat()

        coarseTicks = 0
        arrived = false
        heldTicks = 0
        releaseAt = null
        phase = if (hasSpread) Phase.COARSE else Phase.FINE
    }

    private fun endCycle() {
        phase = Phase.FORWARD
        desiredForward = null
        coarseTicks = 0
        arrived = false
        heldTicks = 0
        releaseAt = null
        lastAim = null
    }

    private fun isOnWire(rotation: Rotation) =
        RotationManager.currentRotation?.let { it.angleTo(rotation) <= REACHED_ANGLE } ?: false

    private fun offset(base: Rotation, yaw: Float, pitch: Float) =
        Rotation(base.yaw + yaw, (base.pitch + pitch).coerceIn(-90f, 90f))

    /**
     * The aim point moved by the cycle's spread, shrunk until the ray from the eyes still lands on the face - the same
     * test `ModuleScaffold` puts the outgoing rotation through before it places - and the result tested once more,
     * since a face seen in yaw/pitch is not convex. When the base itself misses from here no placement can happen this
     * tick either way, so the spread is left whole.
     */
    private fun aimAround(aimPoint: AimPoint): Rotation {
        val base = aimPoint.rotation
        val face = aimPoint.face

        if (!aimPoint.spread) {
            return base
        }

        fun at(scale: Float) = offset(base, fineYaw * scale, finePitch * scale)

        if (face == null || fineYaw == 0f && finePitch == 0f || !face.isHitBy(base)) {
            return at(1f)
        }

        val whole = at(1f)
        if (face.isHitBy(at(1f / EDGE_MARGIN)) && face.isHitBy(whole)) {
            return whole
        }

        var hit = 0f
        var miss = 1f / EDGE_MARGIN

        repeat(SEARCH_STEPS) {
            val mid = (hit + miss) / 2f

            if (face.isHitBy(at(mid))) {
                hit = mid
            } else {
                miss = mid
            }
        }

        val shrunk = at(hit * EDGE_MARGIN)
        return if (face.isHitBy(shrunk)) shrunk else base
    }

    /** GUESS: a symmetric triangle on -1..1, most of the mass near the middle. */
    private fun triangle() = Random.nextFloat() - Random.nextFloat()

    private fun sign() = if (Random.nextBoolean()) 1f else -1f

    /** GUESS: [lo]..[hi], triangular, most of the mass in the middle of the window. */
    private fun drawForward(now: Int, slack: Int): Int {
        val lo = max(minForward, now)
        val hi = now + slack

        if (hi <= lo) {
            return lo
        }

        val span = hi - lo + 1
        return lo + ((Random.nextFloat() + Random.nextFloat()) / 2f * span).toInt().coerceAtMost(span - 1)
    }

    /** GUESS: 0..[window], the smaller of two draws - a hand usually lets go soon after the last block. */
    private fun earlyInt(window: Int) = min(Random.nextInt(window + 1), Random.nextInt(window + 1))

    private fun predictFlight(): Flight? {
        // No row of this flight (mode switched on in the air, Scaffold enabled mid-air) or one from another floor (a
        // teleport, a pearl): no prediction, which holds the aim until touchdown.
        val row = row ?: return null
        val rowTop = row + 1.0
        val position = player.position()

        if (position.y < rowTop - 1.0 || position.y > rowTop + MAX_RISE) {
            return null
        }

        val start = takeoff?.takeIf {
            val dx = it.x - position.x
            val dz = it.z - position.z
            dx * dx + dz * dz <= MAX_TAKEOFF_DISTANCE * MAX_TAKEOFF_DISTANCE
        } ?: position

        val simulation = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput(
                ModuleScaffold.rawInput,
                jumping = false,
                sprinting = player.isSprinting,
                sneaking = player.isShiftKeyDown
            )
        )

        for (step in 1..MAX_FLIGHT_TICKS) {
            simulation.tick()

            if (simulation.onGround || simulation.pos.y < rowTop) {
                val supported = simulation.onGround && standsOn(simulation.pos, row)
                return walkColumns(start, simulation.pos, row, step, supported)
            }
        }

        return null
    }

    /** A column of [row] (or the one above) under a point [LANDING_MARGIN] inside the hitbox. */
    private fun standsOn(pos: Vec3, row: Int): Boolean {
        for (ox in doubleArrayOf(-LANDING_MARGIN, LANDING_MARGIN)) {
            for (oz in doubleArrayOf(-LANDING_MARGIN, LANDING_MARGIN)) {
                if (!isGap(Mth.floor(pos.x + ox), row, Mth.floor(pos.z + oz))) {
                    return true
                }
            }
        }

        return false
    }

    /** A column of [row] with nothing to stand on, neither in the row nor in the one above it. */
    private fun isGap(x: Int, row: Int, z: Int) = isEmpty(BlockPos(x, row, z)) && isEmpty(BlockPos(x, row + 1, z))

    private fun isEmpty(pos: BlockPos) = world.getBlockState(pos).getCollisionShape(world, pos).isEmpty

    /**
     * Walks the columns the straight line [from] -> [to] crosses (Amanatides-Woo on the XZ grid, 4-connected, the way
     * a bridge has to be built).
     */
    @Suppress("CognitiveComplexMethod", "LongParameterList")
    private fun walkColumns(from: Vec3, to: Vec3, row: Int, land: Int, supported: Boolean): Flight {
        var x = Mth.floor(from.x)
        var z = Mth.floor(from.z)
        val endX = Mth.floor(to.x)
        val endZ = Mth.floor(to.z)

        val dx = to.x - from.x
        val dz = to.z - from.z
        val stepX = if (dx > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val deltaX = if (dx != 0.0) abs(1.0 / dx) else Double.POSITIVE_INFINITY
        val deltaZ = if (dz != 0.0) abs(1.0 / dz) else Double.POSITIVE_INFINITY
        var nextX = when {
            dx > 0 -> (x + 1 - from.x) * deltaX
            dx < 0 -> (from.x - x) * deltaX
            else -> Double.POSITIVE_INFINITY
        }
        var nextZ = when {
            dz > 0 -> (z + 1 - from.z) * deltaZ
            dz < 0 -> (from.z - z) * deltaZ
            else -> Double.POSITIVE_INFINITY
        }

        var missing = 0
        var edge: BlockPos? = null
        var gap: BlockPos? = null
        var previous: BlockPos? = null

        for (i in 0 until MAX_COLUMNS) {
            if (isGap(x, row, z)) {
                missing++

                if (gap == null) {
                    gap = BlockPos(x, row, z)
                    edge = previous
                }
            }

            previous = BlockPos(x, row, z)

            if (x == endX && z == endZ) {
                break
            }

            if (nextX < nextZ) {
                x += stepX
                nextX += deltaX
            } else {
                z += stepZ
                nextZ += deltaZ
            }
        }

        // The take-off column itself is a gap (the centre was already past the block's edge, the box still stood on
        // it): the support is a neighbour off the line - the one nearest to where we took off.
        val firstGap = gap
        if (firstGap != null && edge == null) {
            edge = Direction.Plane.HORIZONTAL
                .map { firstGap.relative(it) }
                .filter { !isGap(it.x, row, it.z) }
                .minByOrNull {
                    val ex = it.x + 0.5 - from.x
                    val ez = it.z + 0.5 - from.z
                    ex * ex + ez * ez
                }
        }

        return Flight(land, supported, missing, edge, gap)
    }

    private class AimPoint(val rotation: Rotation, val face: AimFace?, val spread: Boolean)

    private class Flight(
        /** Simulation step that touches down: the last block has to be placed by game tick `land - 1`. */
        val land: Int,
        /** That step lands on something. */
        val supported: Boolean,
        /** Columns between take-off and touchdown with nothing to land on. */
        val missing: Int,
        /** The last supported column before the first gap. */
        val edge: BlockPos?,
        /** The first gap. */
        val gap: BlockPos?
    ) {
        /** Placements still to make before touchdown, as far as the straight line can tell. */
        val needed: Int
            get() = if (supported) 0 else max(1, missing)

        /**
         * The face a block goes against to fill the first gap, for the ticks before the technique has a target of its
         * own (it only finds one once the predicted position is over the gap).
         */
        fun bridgeFace(): AimFace? {
            val edge = edge ?: return null
            val gap = gap ?: return null
            val side = when {
                gap.x > edge.x -> Direction.EAST
                gap.x < edge.x -> Direction.WEST
                gap.z > edge.z -> Direction.SOUTH
                else -> Direction.NORTH
            }
            val block = if (isEmpty(edge)) edge.above() else edge

            return AimFace(block, side, Double.NEGATIVE_INFINITY)
        }
    }

    private class AimFace(val block: BlockPos, val side: Direction, val minY: Double) {

        /**
         * Centre of the face of the full block cube. A side face that has to be clicked in its upper part (slabs,
         * stairs: `minPlacementY`) is aimed at the middle of the part that counts.
         */
        val centre: Vec3
            get() {
                val centre = Vec3.atCenterOf(block).add(side.stepX * 0.5, side.stepY * 0.5, side.stepZ * 0.5)

                // `<=`: a top slab's side face has its valid part start exactly at the cube's centre line.
                return if (side.axis != Direction.Axis.Y && centre.y <= minY) {
                    Vec3(centre.x, (minY + block.y + 1.0) / 2.0, centre.z)
                } else {
                    centre
                }
            }

        fun isHitBy(rotation: Rotation) = matches(traceFromPlayer(rotation))

        private fun matches(hit: BlockHitResult) = hit.type == HitResult.Type.BLOCK &&
            hit.blockPos == block &&
            hit.direction == side &&
            hit.location.y >= minY
    }

}
