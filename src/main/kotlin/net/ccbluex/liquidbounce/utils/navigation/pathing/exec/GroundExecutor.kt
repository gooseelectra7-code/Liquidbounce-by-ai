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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doBreak
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockOffsetOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTargetFindingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.CenterTargetPositionFactory
import net.ccbluex.liquidbounce.utils.block.targetfinding.FaceHandlingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.PlayerLocationOnPlacement
import net.ccbluex.liquidbounce.utils.block.MiningClaim
import net.ccbluex.liquidbounce.utils.block.targetfinding.findBestBlockPlacementTarget
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.PlayerSimulationCache
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.movement.getDegreesRelativeToView
import net.ccbluex.liquidbounce.utils.movement.getDirectionalInputForDegrees
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavDebug
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.asBlockSource
import net.ccbluex.liquidbounce.utils.navigation.pathing.route.NavMountSteering
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drives the player along a [NavPath], one tick at a time.
 *
 * Everything it does goes through [MovementInputEvent], [SprintEvent] and [RotationManager] — the
 * player's velocity is never written directly and no movement packets are forged. Everything here is
 * something a person could do with a keyboard.
 *
 * The steering is deliberately physics-aware rather than a naive "face the waypoint and hold W":
 * momentum carries a sprinting player well past a block boundary, so the executor skips waypoints
 * inertia already took it past, eases off before hairpins, and only sprints down runs that are
 * actually straight. Getting this wrong looks like a bot bouncing off corners.
 */
@Suppress("TooManyFunctions")
class GroundExecutor(
    private val path: NavPath,
    private val caps: NavCaps,
    private val owner: ClientModule,
    private val rotations: RotationsValueGroup,
) : NavExecutor {

    /** Index of the move currently being executed. */
    var moveIndex: Int = 0
        private set

    private var wantSprint = false
    private var stuckTicks = 0
    private var lastPosition: Vec3 = player.position()

    /** Keys held last tick. Only used to settle ties, so the body does not flutter between two. */
    private var steeredInput: DirectionalInput = DirectionalInput.NONE

    /** Move index at the far end of the level run currently being steered at; see [latchedRunEnd]. */
    private var runTarget = -1

    /** Consecutive ticks the line to [runTarget] has failed to check out. */
    private var runTargetDoubts = 0

    /** Set while mining or building, when standing still is the correct thing to be doing. */
    private var working = false

    /** Ticks spent waiting for a placement aim to reach the server, since the wait began. */
    private var aimWaitTicks = 0

    /**
     * True while this executor is breaking a block and vanilla must be kept from aborting it.
     *
     * The claim itself lives in [MiningClaim], which is also what keeps this dig from running
     * alongside SpeedMine's or AutoMine's — see the reasoning there.
     */
    val isMining: Boolean get() = MiningClaim.isHeldBy(this)

    /**
     * Ticks spent on the move currently being executed.
     *
     * The route's backstop. Standing still is caught by the stuck detector, but plenty of ways of
     * getting nowhere involve moving: a drop that lands just outside its landing radius, a dig that
     * never completes, a placement whose aim never arrives, a body grinding along a wall. All of
     * them used to end in the executor quietly working at an impossible move for as long as the
     * player was willing to watch. A move that has taken far longer than any move of its kind should
     * is a wrong plan, and the answer to a wrong plan is a new one.
     */
    private var moveTicks = 0

    /** Cursor into the navigation cache, used by the line-of-sight checks that smooth the route. */
    private val reader = NavWorld.reader()

    /** The same cursor as a plain block source, which is all [NavRouteLine] needs of it. */
    private val blocks = reader.asBlockSource()

    /**
     * The entity whose physics actually govern travel.
     *
     * While riding, that is the animal: a passenger's own `onGround` is never updated and its
     * velocity stays near zero, so reading them off the player makes a mounted route look
     * permanently airborne and motionless. Landing would never be detected and every waypoint
     * would look like it had been overshot.
     */
    private val mover: Entity get() = player.vehicle ?: player

    private fun isOnGround(): Boolean = mover.onGround()

    private fun velocity(): Vec3 = mover.deltaMovement

    /** True while surfacing for air, which suspends the route. */
    private var breathing = false

    /** Ticks spent surfacing without rising, and the highest the body has got while doing it. */
    private var breathTicks = 0
    private var bestDepth = 0.0


    /** How far along the route we are, 0..1, for the debug overlay. */
    override val progress: Float
        get() = if (path.moveCount == 0) 1f else moveIndex.toFloat() / path.moveCount

    /** The position currently being steered toward, if any. */
    fun currentTarget(): Vec3? =
        if (moveIndex < path.moveCount) path.centerAt(moveIndex + 1) else null

    // -----------------------------------------------------------------------
    //  Tick entry points
    // -----------------------------------------------------------------------

    override fun tick(event: MovementInputEvent): NavExecStatus {
        wantSprint = false
        working = false
        // Chunks can be swapped out under a long-lived cursor; a tick is a cheap enough granularity.
        reader.invalidate()

        if (moveIndex >= path.moveCount) return NavExecStatus.FINISHED
        moveTicks++

        // Drowning beats any route. Suspend and come up for air — but surfacing is not always
        // possible, and a route that has us holding jump against a ceiling with no air left is a
        // route to replace, not one to persevere with.
        if (maybeSurfaceForAir(event)) {
            stuckTicks = 0
            lastPosition = player.position()
            return if (isDrowningInPlace()) NavExecStatus.NEEDS_REPLAN else NavExecStatus.RUNNING
        }

        skipWaypointsAlreadyPassed()
        if (moveIndex >= path.moveCount) return NavExecStatus.FINISHED

        val status = driveCurrentMove(event)
        if (status != NavExecStatus.RUNNING) return status

        return updateStuckDetection()
    }

    override fun onSprint(event: SprintEvent) {
        // Sprinting is the rider's own state and does nothing for the animal underneath.
        if (player.isPassenger) return
        if (!wantSprint || !event.directionalInput.isMoving) return
        if (event.source == SprintEvent.Source.MOVEMENT_TICK || event.source == SprintEvent.Source.INPUT) {
            event.sprint = true
        }
    }

    // -----------------------------------------------------------------------
    //  Move dispatch
    // -----------------------------------------------------------------------

    private fun driveCurrentMove(event: MovementInputEvent): NavExecStatus {
        val target = path.centerAt(moveIndex + 1)

        // A boat overrides the move kind entirely. It cannot jump, climb, dig or build, and on water
        // the planner emits swim moves — which are driven from the player's own yaw and so would
        // never reach the hull-relative steering below. Every kind reduces to the same thing: point
        // the hull at the next waypoint and go.
        val boat = player.vehicle as? AbstractBoat
        if (boat != null) {
            // Aim at a point a fixed distance ahead along the route rather than at the next block
            // centre. A boat cannot trace the lattice's right angles, and chasing a nearby waypoint
            // makes it saw at every one; a lookahead point turns the corner into an arc whose radius
            // is set by how far ahead we look. The distance grows with speed, because a boat under
            // way needs a wider berth than one barely moving.
            val lookahead = BOAT_LOOKAHEAD_BASE + boatSpeed(boat) * BOAT_LOOKAHEAD_PER_SPEED
            steerBoat(event, boat, pursuitPointAhead(lookahead))

            return advanceIfReached(path.centerAt(moveIndex + 1))
        }

        return when (path.kindAt(moveIndex)) {
            NavMove.WALK, NavMove.DIAGONAL -> driveFlatRun(event)

            NavMove.DESCEND, NavMove.FALL -> driveDrop(event, target)

            NavMove.ASCEND -> {
                steer(event, target)
                if (isOnGround() && shouldJumpUpStep(target, event.directionalInput)) {
                    event.jump = true
                }
                advanceIfArrived(target)
            }

            NavMove.PARKOUR -> drivePakour(event, target)

            NavMove.CLIMB -> {
                steer(event, target)
                if (target.y > player.y) event.jump = true
                advanceIfArrived(target)
            }

            NavMove.SWIM -> {
                swimToward(event, target)
                advanceIfArrived(target)
            }

            NavMove.DIG -> driveDig(event, target)
            NavMove.DIG_DOWN -> driveDigDown(event, target)
            NavMove.DIG_UP -> driveDigUp(event, target)
            NavMove.PILLAR -> drivePillar(event, target)
            NavMove.PLACE -> drivePlace(event, target)

            else -> steerAndAdvance(event, target)
        }
    }

    private fun steerAndAdvance(event: MovementInputEvent, target: Vec3): NavExecStatus {
        steer(event, target)
        return advanceIfArrived(target)
    }

    /**
     * Walks a run of level steps by heading for the furthest one still in clear line of sight.
     *
     * A route is a chain of block centres, and steering at each in turn makes the player trace the
     * staircase the grid produced — a visible zigzag with a turn at every block, which is both slow
     * and unmistakably robotic. Aiming at the far end of a straight run instead cuts the corners,
     * so an open diagonal is walked as one smooth line.
     *
     * It also removes the stutter after a drop: rather than turning back for a waypoint momentum
     * carried us past, we simply pick the next one that is genuinely ahead.
     */
    private fun driveFlatRun(event: MovementInputEvent): NavExecStatus {
        val through = latchedRunEnd()
        val target = path.centerAt(through + 1)

        // The turn that matters is the one at the far end of the smoothed segment, not at the
        // waypoints we are cutting across.
        steer(event, target, path.centerAtOrNull(through + 2))

        val reached = hasOvershot(target) || (hasArrivedAt(target) && canStartLegFrom(through + 1))
        if (!reached) return NavExecStatus.RUNNING

        moveIndex = through + 1
        resetStuck()
        return if (moveIndex >= path.moveCount) NavExecStatus.FINISHED else NavExecStatus.RUNNING
    }

    /**
     * Walks off a ledge and rides the drop out.
     *
     * Stepping off at speed always carries the player past the landing column — that is just
     * momentum, not an error. Steering at the target regardless makes the character spin around in
     * mid-air to chase a point behind it, then walk back, throwing away about a second every ledge.
     * So once the target is behind us we stop steering at it and simply let the fall finish.
     *
     * Landing is judged by height and footing rather than by horizontal distance, because where we
     * land horizontally is exactly what momentum is allowed to change. Overshooting onto the next
     * waypoint is fine: the level-run skip picks that up on the following tick.
     */
    private fun driveDrop(event: MovementInputEvent, target: Vec3): NavExecStatus {
        if (isTargetBehind(target)) {
            releaseInput(event)
        } else {
            steer(event, target, path.centerAtOrNull(moveIndex + 2))
        }

        if (!hasLandedAt(target)) return NavExecStatus.RUNNING

        moveIndex++
        resetStuck()
        return if (moveIndex >= path.moveCount) NavExecStatus.FINISHED else NavExecStatus.RUNNING
    }

    /**
     * Have we come to rest at the height this drop was aiming for?
     *
     * Height and footing only. Where a drop lands horizontally is exactly what momentum is allowed
     * to change — walking off a ledge at a sprint and falling six blocks carries the body three
     * beyond the landing column — so a landing radius smaller than that carry meant a long fall
     * never counted as finished and ran to its deadline instead. Overshooting onto a later waypoint
     * is picked up by the skip on the next tick.
     */
    private fun hasLandedAt(target: Vec3): Boolean {
        val position = player.position()
        if (position.y > target.y + DROP_LANDED_TOLERANCE) return false
        return isOnGround()
    }

    /** Is this a move whose whole point is to leave the block it started on? */
    private fun isTravellingMove(kind: Int): Boolean =
        kind == NavMove.PARKOUR || kind == NavMove.FALL || kind == NavMove.DESCEND

    /** How far the body is from the straight line between this leg's two nodes, horizontally. */
    private fun distanceFromLeg(position: Vec3): Double {
        val from = path.centerAt(moveIndex)
        val to = path.centerAt(moveIndex + 1)
        val dx = to.x - from.x
        val dz = to.z - from.z
        val lengthSq = dx * dx + dz * dz
        if (lengthSq < 1.0E-6) return sqrt(horizontalDistanceSq(position, to))

        val along = (((position.x - from.x) * dx + (position.z - from.z) * dz) / lengthSq)
            .coerceIn(0.0, 1.0)
        val offX = position.x - (from.x + dx * along)
        val offZ = position.z - (from.z + dz * along)
        return sqrt(offX * offX + offZ * offZ)
    }

    /**
     * The end of the run being steered at, held steady once it has been chosen.
     *
     * The straight-line test is a sampled one, and beside a drop it is a knife edge: a body walking
     * a diagonal along a cliff top puts a hitbox corner over the void every time it crosses a block
     * boundary, so the answer flickers between "the node ten ahead" and "the node right here" from
     * one tick to the next. The steering follows the target, the head follows the steering, and the
     * player watches the character spin on the spot — which is exactly what it was reported doing.
     *
     * So a target, once chosen, is kept. It is extended freely, abandoned instantly when the route
     * moves on or the run ends, and given up on a short grace period when the line to it stops
     * checking out — long enough to outlast the flicker, short enough to notice a wall.
     */
    private fun latchedRunEnd(): Int {
        val furthest = NavRouteLine.endOfRun(path, blocks, moveIndex, player.position())

        if (furthest >= runTarget || !isWithinCurrentRun(runTarget)) {
            runTarget = furthest
            runTargetDoubts = 0
            return furthest
        }

        runTargetDoubts++
        if (runTargetDoubts > RUN_LATCH_GRACE) {
            runTarget = furthest
            runTargetDoubts = 0
            return furthest
        }
        return runTarget
    }

    /** Is this move still part of the level run we are walking, and still ahead of us? */
    private fun isWithinCurrentRun(through: Int): Boolean {
        if (through <= moveIndex || through >= path.moveCount) return false

        val runY = path.yAt(moveIndex + 1)
        for (probe in moveIndex..through) {
            val kind = path.kindAt(probe)
            if (kind != NavMove.WALK && kind != NavMove.DIAGONAL) return false
            if (path.yAt(probe + 1) != runY) return false
        }
        return true
    }

    private fun isStraightWalkable(from: Vec3, to: Vec3, runY: Int): Boolean =
        NavRouteLine.isStraightWalkable(blocks, from, to, runY)

    /**
     * Jumps a gap, taking off when the physics says the jump lands rather than when a distance
     * threshold says it might.
     *
     * A rule of thumb cannot decide this. Whether a jump clears four blocks depends on the speed
     * already built up, on whether the sprint has kicked in, on the exact fraction of a block the
     * body is from the edge — and being a tick early or late is the difference between the far side
     * and the bottom of the ravine. The client can simulate its own physics, so the question is put
     * to it directly: jump this tick, and see where the body comes down.
     */
    private fun drivePakour(event: MovementInputEvent, target: Vec3): NavExecStatus {
        steer(event, target)
        // Sprint is what makes the jump reach; take off from the last solid block.
        wantSprint = caps.canSprint

        if (isOnGround() && jumpWouldLandOn(target, event.directionalInput)) {
            event.jump = true
        }
        return advanceIfArrived(target)
    }

    /**
     * Is this the tick to jump for a step up?
     *
     * A distance threshold cannot answer this either. It fires at a fixed radius from the block's
     * *centre*, which for a diagonal step is most of a block further out than for a straight one, so
     * one of the two is always wrong — and a jump begun early comes down against the face of the
     * step, which is a stall dressed up as a jump. The physics is asked instead: jump this tick, and
     * see whether the body ends up on the step.
     *
     * The distance is kept as a gate rather than as the rule, so the jump can only ever happen later
     * than it used to. The second way out is being pressed against the step already: standing there
     * not jumping is the one answer that is certainly wrong.
     */
    private fun shouldJumpUpStep(target: Vec3, input: DirectionalInput): Boolean {
        if (horizontalDistanceTo(target) > ASCEND_JUMP_DISTANCE) return false
        return jumpWouldLandOn(target, input) || player.horizontalCollision
    }

    /**
     * Simulates a jump from here and reports whether it puts us on [target].
     *
     * Cheap enough to ask every tick while approaching: a couple of dozen ticks of one body, over
     * the handful of ticks a run-up lasts.
     */
    private fun jumpWouldLandOn(target: Vec3, input: DirectionalInput): Boolean {
        val simulation = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput(
                input,
                jumping = true,
                sprinting = wantSprint && caps.canSprint,
                sneaking = false,
            ),
        )

        repeat(JUMP_SIMULATION_TICKS) {
            simulation.tick()

            // Only interested in where it first comes back down.
            if (!simulation.onGround) return@repeat

            val landed = simulation.pos
            if (Math.abs(landed.y - target.y) > JUMP_LANDING_HEIGHT) return false

            val dx = landed.x - target.x
            val dz = landed.z - target.z
            return dx * dx + dz * dz <= JUMP_LANDING_RADIUS * JUMP_LANDING_RADIUS
        }
        return false
    }

    // -----------------------------------------------------------------------
    //  Mining and building
    // -----------------------------------------------------------------------

    private fun driveDig(event: MovementInputEvent, target: Vec3): NavExecStatus {
        val destination = path.blockAt(moveIndex + 1)
        // Clear the head block first: standing under an overhang and mining the floor block leaves
        // us unable to step in.
        if (!clearBlock(destination.above())) {
            // Every other manoeuvre that stands still to work drops the keys while it does. This one
            // did not, so a player leaning on W ground the body into the wall being mined for the
            // whole two hundred ticks the dig is allowed — with the stuck detector suppressed,
            // because `working` says standing still is the plan.
            releaseInput(event)
            return NavExecStatus.RUNNING
        }
        if (!clearBlock(destination)) {
            releaseInput(event)
            return NavExecStatus.RUNNING
        }

        return steerAndAdvance(event, target)
    }

    /**
     * Mines the floor and drops into the hole.
     *
     * Dropping in is not automatic and was being taken for granted. The hole is one block wide and
     * the body is 0.6 across, so it only goes in from the middle: standing at the lip with a third
     * of the hitbox over the neighbour, the body rests on that neighbour and mines a hole it never
     * enters. The route then counts it as arrived — 0.55 blocks of tolerance is wider than the ledge
     * it is stuck on — and walks off to do the next move from a block it never left.
     */
    private fun driveDigDown(event: MovementInputEvent, target: Vec3): NavExecStatus {
        val hole = path.blockAt(moveIndex + 1)
        if (!clearBlock(hole)) {
            NavDebug.say("dig down: mining $hole")
            releaseInput(event)
            return NavExecStatus.RUNNING
        }

        if (player.y > target.y + DIG_DOWN_ENTERED) {
            NavDebug.say("dig down: dropping in, ${offsetFrom(hole)} from centre")
            settleOver(event, target)
            return NavExecStatus.RUNNING
        }

        releaseInput(event)
        return advanceIfArrived(target)
    }

    private fun driveDigUp(event: MovementInputEvent, target: Vec3): NavExecStatus {
        releaseInput(event)
        val ceiling = path.blockAt(moveIndex).above().above()
        if (!clearBlock(ceiling)) return NavExecStatus.RUNNING
        return drivePillar(event, target)
    }

    /**
     * Builds one block upward and stands on it.
     *
     * The order matters more here than anywhere else in the executor, and getting it wrong is what
     * makes a bot leave blocks in places nobody asked for:
     *
     * 1. **Aim first, and wait for the aim to land.** A placement is resolved against the face the
     *    server believes we are looking at, and a silently requested rotation takes a few ticks to
     *    get there. Sending the placement in the same tick means it is judged against wherever we
     *    were looking before — up the route, more often than not, which is precisely how a block
     *    ends up over the player's head instead of under their feet.
     * 2. **Then jump**, already looking down at the spot.
     * 3. **Place only once the body has left the space.** The block goes exactly where the feet
     *    were, so placing before clearing it is placing inside ourselves, which the server refuses.
     */
    private fun drivePillar(event: MovementInputEvent, target: Vec3): NavExecStatus {
        working = true

        val support = path.blockAt(moveIndex + 1).below()
        // Already built — most often because this is the tick after a successful placement.
        if (isSupported(support)) return advanceIfArrived(target)

        // Asked for first so the head is already on its way down while the feet are still walking:
        // the aim takes a few ticks to reach the server, and there is no reason to spend them
        // standing still.
        val placement = aimForPlacement(support)
        if (placement == null) {
            // Out of blocks, or nowhere to place from. Standing still until the deadline replans is
            // the honest thing to do — and the keys have to be dropped, or the player's own input
            // walks the body away while the route thinks it is building.
            releaseInput(event)
            NavDebug.say("pillar: nothing to build $support with")
            return NavExecStatus.RUNNING
        }

        // Into the column, and settled, before the jump. The block goes exactly where the feet are,
        // so the manoeuvre is wrong from anywhere else — and a move begins while the body is still
        // more than half a block short of its node, which here means still crossing from the last
        // one. Jumping from there carries it out of the column: it comes down beside the block it
        // just made, with a third of the hitbox on the neighbour, standing on nothing it planned.
        if (isOnGround() && !jumpWouldComeBackDownOver(support) && moveTicks < SETTLE_PATIENCE) {
            // Still positioning, not yet waiting on the aim.
            aimWaitTicks = 0
            NavDebug.say("pillar: onto $support, ${offsetFrom(support)} from centre")
            settleOver(event, Vec3(support.x + 0.5, player.y, support.z + 0.5))
            return NavExecStatus.RUNNING
        }

        releaseInput(event)
        if (!isReadyToPlace(placement)) {
            NavDebug.say("pillar: waiting for the aim to reach the server")
            return NavExecStatus.RUNNING
        }

        if (isOnGround()) {
            NavDebug.say("pillar: jumping from ${offsetFrom(support)} off centre")
            event.jump = true
            return NavExecStatus.RUNNING
        }

        // Past the top of the arc and clear of the block's own space.
        if (velocity().y > 0.0) return NavExecStatus.RUNNING
        if (player.y < support.y + 1.0) return NavExecStatus.RUNNING

        if (!commitPlacement(placement)) return NavExecStatus.RUNNING
        return advanceIfArrived(target)
    }

    /**
     * Lays a block into the gap ahead and walks onto it.
     *
     * Same rule as pillaring about aiming before placing, for the same reason — but nothing has to
     * be jumped over, since the block goes beside us rather than beneath us.
     */
    /**
     * Lays a block into the gap ahead and walks onto it.
     *
     * The whole difficulty of bridging is one piece of geometry: the block goes against the *side*
     * of the block we are standing on, and that face cannot be seen from on top of it. Every ray
     * from eyes above the surface down to a point on the side face crosses the surface first, so
     * standing in the middle and looking down hits our own footing every time — and a placement
     * aimed there puts the block on top of us instead of out in front, which is precisely what it
     * was doing.
     *
     * So the body has to hang over the edge before it can build from it. Sneaking is what makes
     * that safe: vanilla will let the hitbox overhang a drop as long as part of it is still
     * supported, and refuses to walk off. From out there the face is in plain view, and the block
     * goes where the route asked.
     */
    /**
     * Bridges across a gap: backing out over the drop and building under our own feet.
     *
     * This is the one manoeuvre in the engine that has to be done the way people do it, because the
     * geometry leaves no alternative. The block goes against the *side* of the block we are standing
     * on, and no ray from eyes above that block can reach that face — the surface we are stood on is
     * in the way of every one of them. The face only comes into view once the eyes have passed
     * beyond the edge, and then it lies *behind* us.
     *
     * Hence: look back and down at the face, sneak so the drop cannot take us, and walk out over it
     * — which, with the head turned round like that, is the back key. Sneaking makes the position
     * stable rather than a moment to be timed: the body can stand at the limit of its overhang
     * indefinitely with the face in plain view, place, and step onto what it placed.
     *
     * Three earlier attempts got this wrong in ways worth remembering. Aiming from atop the block
     * hit its top face instead, putting the block beside the player rather than in the gap. Walking
     * out forwards left a window about one tick wide, which is how two blocks got built and the
     * third step went into the void. And taking the block we stand on from the player's own
     * position — rather than from the route — made it change the moment we overhung the edge, which
     * is exactly when it must not: the aim flipped between the face behind and the node ahead every
     * tick, and the head shook instead of the block appearing.
     */
    private fun drivePlace(event: MovementInputEvent, target: Vec3): NavExecStatus {
        val support = path.blockAt(moveIndex + 1).below()
        if (isSupported(support)) return steerAndAdvance(event, target)

        working = true
        if (caps.bridgeStyle == NavBridgeStyle.GOD_BRIDGE) return godBridge(event, target, support)

        wantSprint = false

        // From the route, never from where the body happens to be. Leaning out over the edge changes
        // which block the player's coordinates fall in, and an anchor that moves halfway through the
        // manoeuvre takes the aim with it.
        val anchor = path.blockAt(moveIndex).below()
        val placement = bridgePlacement(anchor, support)
        if (placement == null) {
            // The route says we should be standing somewhere we are not. Walk to it — silently, with
            // no rotation of our own, so nothing fights whatever else is aiming.
            walkTowards(event, path.centerAt(moveIndex))
            return NavExecStatus.RUNNING
        }

        // Sneak for the part that is actually over the drop, and no earlier. Held from the first
        // step it costs most of the walking speed for nothing: while the body is still squarely on
        // its block there is nothing to fall off, and vanilla's refusal to walk a sneaking player
        // over an edge only has to be in force for the last third of a block.
        event.sneak = overhangPast(anchor, support) > SNEAK_FROM_OVERHANG
        // Without smoothing: a placement aim that arrives over half a second is a placement that
        // does not happen, and this one turns the head through most of a half-circle.
        requestRotationInstantly(placement.rotation)

        if (isLookingAt(placement) && commitBridge(placement, support)) {
            return steerAndAdvance(event, target)
        }

        // Keep backing out until the edge brings the face into view. The direction is worked out
        // from the route rather than assumed, so a head turned backwards produces the back key by
        // itself — and it stays right whichever way the body happens to be facing.
        val towards = Vec3(support.x + 0.5, player.y, support.z + 0.5).subtract(player.position())
        val degrees = getDegreesRelativeToView(towards, player.yRot)
        event.directionalInput = getDirectionalInputForDegrees(DirectionalInput.NONE, degrees, deadAngle = 0f)
        return NavExecStatus.RUNNING
    }

    /**
     * The godbridge: keep sprinting forwards and build underneath without ever slowing down.
     *
     * The stance is the whole trick, and it is copied from this client's own
     * `ScaffoldGodBridgeTechnique` rather than derived: face back along the route, turn forty-five
     * degrees to the side that puts the face in reach, and hold the nose at 75.7 degrees. Looking
     * along the diagonal is what stretches the moment in which the block below is clickable from
     * about one tick into something a sprinting body can actually hit.
     *
     * Nothing here is aimed at a face chosen in advance. The stance decides what the crosshair
     * finds, and any face is fair as long as clicking it fills the cell the route wants filled —
     * which is also the check that keeps the block out of the wrong place.
     */
    private fun godBridge(event: MovementInputEvent, target: Vec3, support: BlockPos): NavExecStatus {
        val anchor = path.blockAt(moveIndex).below()
        if (!isSupported(anchor)) {
            walkTowards(event, path.centerAt(moveIndex))
            return NavExecStatus.RUNNING
        }

        requestRotationInstantly(godBridgeStance(anchor, support))

        // Forwards, at speed. Slowing down is the one thing this technique is for avoiding.
        walkTowards(event, Vec3(support.x + 0.5, player.y, support.z + 0.5))
        wantSprint = caps.canSprint

        if (clickWhateverFills(support)) return steerAndAdvance(event, target)

        // The next tick would step off with nothing under it and nothing placed: jump. A jump keeps
        // the body over the edge for another tick, and a tick is all this needs.
        if (isOnGround() && isSteppingOffNextTick()) event.jump = true

        return NavExecStatus.RUNNING
    }

    /**
     * The godbridge stance for travelling from [anchor] towards [support].
     *
     * Straight from `ScaffoldGodBridgeTechnique.getRotationForStraightInput`, including the choice
     * of side: which diagonal has the face in reach depends on which side of a block boundary the
     * body is standing on, and getting it backwards aims at thin air.
     */
    private fun godBridgeStance(anchor: BlockPos, support: BlockPos): Rotation {
        val travel = Math.toDegrees(
            atan2((anchor.x - support.x).toDouble(), (support.z - anchor.z).toDouble()),
        ).toFloat()
        val facingBack = Mth.wrapDegrees(travel + 180f)

        val radians = Math.toRadians(facingBack.toDouble())
        var onRightSide = Math.floor(player.x + cos(radians) * 0.5) != Math.floor(player.x) ||
            Math.floor(player.z + sin(radians) * 0.5) != Math.floor(player.z)

        // Leaning off the block with nothing ahead flips which diagonal reaches the face.
        val ahead = BlockPos(
            Math.floor(player.x + cos(radians) * GODBRIDGE_LOOKAHEAD).toInt(),
            Math.floor(player.y).toInt(),
            Math.floor(player.z + sin(radians) * GODBRIDGE_LOOKAHEAD).toInt(),
        )
        if (!isSupported(player.blockPosition().below()) && !isSupported(ahead.below())) {
            onRightSide = !onRightSide
        }

        return Rotation(facingBack + if (onRightSide) 45f else -45f, GODBRIDGE_PITCH)
    }

    /**
     * Clicks whatever the crosshair is on, but only when doing so fills [support].
     *
     * The stance picks the face, not us, so the guard is on the outcome instead: a click that would
     * put the block anywhere other than where the route wants it is simply not sent.
     */
    private fun clickWhateverFills(support: BlockPos): Boolean {
        val trace = traceFromPlayer(
            rotation = RotationManager.serverRotation,
            block = ClipContext.Block.OUTLINE,
        )
        if (trace.type != HitResult.Type.BLOCK) return false
        if (trace.blockPos.relative(trace.direction) != support) return false

        val slot = NavHotbar.buildingBlock() ?: return false
        SilentHotbar.selectSlotSilently(owner, slot, SLOT_RESET_TICKS)
        doPlacement(trace, hand = slot.useHand, swingMode = SwingMode.DO_NOT_HIDE)

        return isSupported(support)
    }

    /** Would the next tick take the body off the edge it is standing on? */
    private fun isSteppingOffNextTick(): Boolean =
        PlayerSimulationCache.getSimulationForLocalPlayer().getSnapshotAt(1).clipLedged

    /**
     * How far past the edge of [anchor] the body has backed, in blocks. Negative before the edge.
     *
     * Measured from the route's own anchor rather than from whichever block the player's
     * coordinates currently fall in — that one changes exactly when we start to overhang, which is
     * the moment this has to stay still.
     */
    private fun overhangPast(anchor: BlockPos, support: BlockPos): Double {
        val stepX = support.x - anchor.x
        val stepZ = support.z - anchor.z
        val travelled = (player.x - (anchor.x + 0.5)) * stepX + (player.z - (anchor.z + 0.5)) * stepZ
        return travelled - 0.5
    }

    /**
     * Sets the keys that move towards a point, without asking for a rotation of its own.
     *
     * For the manoeuvres that aim at something other than where they are going — a bridge looking
     * back at a face, a pillar looking down at its own feet. The keys are still chosen by trying
     * them, because the reason to be walking somewhere precisely is exactly when guessing at the
     * keys is least affordable.
     *
     * Every key here is derived from nothing: `getDirectionalInputForDegrees` only ever *adds* to
     * the input it is handed, so feeding it the player's own keys ORs the two together — a player
     * leaning on W while the sneak-bridge backs out produces forwards *and* backwards, vanilla
     * cancels them to a standstill, and the bridge runs out its deadline having never reached the
     * overhang.
     */
    private fun walkTowards(event: MovementInputEvent, point: Vec3, sprinting: Boolean = false) {
        steeredInput = if (player.isPassenger) {
            val relative = point.subtract(player.position())
            val degrees = getDegreesRelativeToView(relative, player.yRot)
            getDirectionalInputForDegrees(DirectionalInput.NONE, degrees, deadAngle = 0f)
        } else {
            NavSteering.bestInput(player.position(), point, sprinting, steeredInput)
        }
        event.directionalInput = steeredInput
    }

    /**
     * Walks onto a spot precisely, and then stands still on it.
     *
     * For the two things a route does that a block boundary decides: filling the cell under one's
     * own feet, and dropping into a hole one block wide. Arrival is measured at 0.55 blocks, which
     * is deliberately generous for walking and hopeless for these — at that distance a third of the
     * hitbox is over the neighbouring block, which is enough to be standing on it.
     *
     * The last fraction of a block is coasted rather than walked, because a body steered at a point
     * it is already on top of dances around it: friction takes a tenth of the speed each tick and
     * gets there on its own.
     */
    private fun settleOver(event: MovementInputEvent, point: Vec3) {
        if (horizontalDistanceTo(point) < COLUMN_SETTLE_RADIUS) {
            releaseInput(event)
            return
        }
        walkTowards(event, point)
        wantSprint = false
    }

    /**
     * Would a jump from here, with nothing held, come back down with the whole body inside [column]?
     *
     * One question in place of two badly chosen thresholds. It is false while the body is still
     * approaching the column, and false while it is over the column but carrying enough speed to be
     * taken out of it before it lands — which is the same thing the jump has to be sure of, asked of
     * the physics that will decide it.
     *
     * "Inside" is asked of the body and not of the point between its feet, which is the mistake the
     * first version of this made: a position test of `floor(x) == column.x` is satisfied a hair from
     * the boundary, with a third of the hitbox on the neighbouring block — which is the exact state
     * being guarded against, so the guard passed precisely when it was needed.
     */
    private fun jumpWouldComeBackDownOver(column: BlockPos): Boolean {
        val simulation = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput(
                DirectionalInput.NONE,
                jumping = true,
                sprinting = false,
                sneaking = false,
            ),
        )

        repeat(JUMP_SIMULATION_TICKS) {
            simulation.tick()
            if (!simulation.onGround) return@repeat
            return isBodyWithin(column, simulation.pos)
        }
        return false
    }

    /** How far the body is from the middle of a block, rounded for the trace. */
    private fun offsetFrom(column: BlockPos): String {
        val dx = player.x - (column.x + 0.5)
        val dz = player.z - (column.z + 0.5)
        return String.format("%.2f", sqrt(dx * dx + dz * dz))
    }

    /**
     * Is the whole hitbox inside this one block's footprint?
     *
     * A body is 0.6 across and a block is 1.0, so there is a fifth of a block of daylight either
     * side of the middle and no more. Every manoeuvre that fills, enters or stands on a single cell
     * needs this rather than "which block is the midpoint in".
     */
    private fun isBodyWithin(column: BlockPos, position: Vec3): Boolean =
        abs(position.x - (column.x + 0.5)) <= COLUMN_FIT_RADIUS &&
            abs(position.z - (column.z + 0.5)) <= COLUMN_FIT_RADIUS

    /**
     * The click that turns the side of [anchor] into a block at [support], or null when the two are
     * not neighbours to build between.
     *
     * Aimed a little below the middle of the face: high enough to clear the lip we are leaning over,
     * low enough that the ray lands squarely on the face rather than grazing its top edge.
     */
    private fun bridgePlacement(anchor: BlockPos, support: BlockPos): BridgePlacement? {
        if (anchor.y != support.y) return null
        val stepX = support.x - anchor.x
        val stepZ = support.z - anchor.z
        if (Math.abs(stepX) + Math.abs(stepZ) != 1) return null
        if (!isSupported(anchor)) return null

        val face = Direction.getApproximateNearest(stepX.toDouble(), 0.0, stepZ.toDouble())
        val hit = Vec3(
            anchor.x + 0.5 + stepX * 0.5,
            anchor.y + FACE_AIM_HEIGHT,
            anchor.z + 0.5 + stepZ * 0.5,
        )

        return BridgePlacement(anchor, face, hit, Rotation.lookingAt(hit, player.eyePosition))
    }

    /** A hand-built placement: which block to click, on which face, and where exactly. */
    private class BridgePlacement(
        val block: BlockPos,
        val face: Direction,
        val hit: Vec3,
        val rotation: Rotation,
    )

    /** Does the server's view of where we are looking actually land on that face? */
    private fun isLookingAt(placement: BridgePlacement): Boolean {
        val trace = traceFromPlayer(
            rotation = RotationManager.serverRotation,
            block = ClipContext.Block.OUTLINE,
        )
        return trace.type == HitResult.Type.BLOCK &&
            trace.blockPos == placement.block &&
            trace.direction == placement.face
    }

    /** Clicks the face. Returns true once the gap has a floor. */
    private fun commitBridge(placement: BridgePlacement, support: BlockPos): Boolean {
        val slot = NavHotbar.buildingBlock() ?: return false
        SilentHotbar.selectSlotSilently(owner, slot, SLOT_RESET_TICKS)

        doPlacement(
            BlockHitResult(placement.hit, placement.face, placement.block, false),
            hand = slot.useHand,
            swingMode = SwingMode.DO_NOT_HIDE,
        )
        return isSupported(support)
    }

    /** Is there something at this position to stand on? */
    private fun isSupported(pos: BlockPos): Boolean {
        val state = pos.state ?: return false
        return !state.isAir && !state.canBeReplaced()
    }

    /**
     * Is the placement worth sending yet?
     *
     * Waiting for the server to see us looking at the face is what puts the block where the route
     * asked rather than wherever the old rotation pointed. But the wait cannot be unbounded: a
     * rotation held by something else, or a face the trace disagrees about, would otherwise stop the
     * route dead. After a few ticks we click anyway — a placement the server may argue with beats a
     * player standing still.
     */
    /**
     * May the click go now?
     *
     * Either the server already sees us looking at the face, or we have waited long enough to stop
     * believing it ever will. The patience is counted from the tick the wait *began*, not from the
     * start of the move: a pillar spends up to a second walking onto its own column first, so a
     * deadline measured from the move's start was already expired by the time the aim was ever
     * consulted — which is the very failure the wait exists to prevent, blocks appearing above the
     * player's head because the placement was judged against the last rotation.
     */
    private fun isReadyToPlace(target: BlockPlacementTarget): Boolean {
        if (isAimedAtPlacement(target)) return true
        aimWaitTicks++
        return aimWaitTicks >= AIM_WAIT_TICKS
    }

    /**
     * Would a click land on the face we intend, as the server currently sees us?
     *
     * Asked of [RotationManager.serverRotation] rather than of the angles we requested, because
     * that is the rotation the placement will be judged against — the one already on the wire. A
     * rotation that is still swinging towards the block resolves the click against whatever it
     * passes over on the way, and the block appears there instead.
     */
    private fun isAimedAtPlacement(target: BlockPlacementTarget): Boolean =
        target.doesCrosshairTargetMatchRequirements(
            traceFromPlayer(rotation = RotationManager.serverRotation, block = ClipContext.Block.OUTLINE),
        )

    /**
     * Mines [pos] until it is gone. Returns true once the way is clear.
     *
     * Returns false while still working, or when the block simply cannot be broken — in which case
     * the route is wrong and the stuck detector will call for a replan.
     */
    private fun clearBlock(pos: BlockPos): Boolean {
        val state = pos.state ?: return false
        if (state.isAir) return true
        if (!caps.allowBreak) return false

        working = true

        // Only one dig may be active across the client at a time, and the claim serves twice over:
        // it keeps this executor off a block SpeedMine or AutoMine is already breaking, and it is
        // what tells vanilla's per-tick teardown of unattended breaking to leave ours alone.
        // Standing still until the other dig finishes costs a moment; two competing digs cost the
        // block and a flag.
        if (!MiningClaim.claim(this, pos)) return false

        NavHotbar.bestDigTool(state)?.let { slot ->
            SilentHotbar.selectSlotSilently(owner, slot, SLOT_RESET_TICKS)
        }

        val center = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val eye = player.eyePosition
        requestRotation(Rotation.lookingAt(center, eye))
        doBreak(BlockHitResult(center, faceToward(center, eye), pos, false), swingMode = SwingMode.DO_NOT_HIDE)

        val after = pos.state ?: return false
        if (!after.isAir) return false

        // Tell the navigation cache what we just did. It is only ever written from the server's own
        // block packets, so for a whole round trip the route's line-of-sight checks judge the new
        // geometry against the old world — and refuse to walk through the hole they just made.
        NavWorld.recordBlock(pos, after, cleared = true)
        // Done with it — let whoever wants the next dig have it without waiting the claim out.
        MiningClaim.release(this)
        return true
    }

    /**
     * Works out how a block would be placed at [pos], holds the block, and starts turning to it.
     *
     * Split from the placement itself on purpose. A face is only worth clicking once the server
     * agrees we are looking at it, and that takes a few ticks of turning — so the aim is requested
     * here, every tick, and the click waits for [isAimReached].
     *
     * Returns null when there is nothing to place with or nowhere to place from.
     */
    private fun aimForPlacement(pos: BlockPos): BlockPlacementTarget? {
        if (!caps.allowPlace) return null

        val slot = NavHotbar.buildingBlock() ?: return null
        SilentHotbar.selectSlotSilently(owner, slot, SLOT_RESET_TICKS)

        val target = findBestBlockPlacementTarget(
            pos,
            BlockPlacementTargetFindingOptions(
                BlockOffsetOptions.Default,
                FaceHandlingOptions(CenterTargetPositionFactory),
                stackToPlaceWith = slot.itemStack,
                PlayerLocationOnPlacement(position = player.position()),
            ),
        ) ?: return null

        requestRotation(target.rotation)
        return target
    }

    /** Clicks the face worked out by [aimForPlacement]. Returns true once the block is there. */
    private fun commitPlacement(target: BlockPlacementTarget): Boolean {
        val slot = NavHotbar.buildingBlock() ?: return false
        val pos = target.blockHitResult.blockPos.relative(target.blockHitResult.direction)

        doPlacement(target.blockHitResult, hand = slot.useHand, swingMode = SwingMode.DO_NOT_HIDE)
        return isSupported(pos)
    }

    /** Block face closest to the eye, so the break packet carries a plausible hit result. */
    private fun faceToward(center: Vec3, eye: Vec3): Direction {
        val dx = eye.x - center.x
        val dy = eye.y - center.y
        val dz = eye.z - center.z
        val ax = abs(dx)
        val ay = abs(dy)
        val az = abs(dz)
        return when {
            ax >= ay && ax >= az -> if (dx >= 0) Direction.EAST else Direction.WEST
            ay >= az -> if (dy >= 0) Direction.UP else Direction.DOWN
            else -> if (dz >= 0) Direction.SOUTH else Direction.NORTH
        }
    }

    // -----------------------------------------------------------------------
    //  Steering
    // -----------------------------------------------------------------------

    /**
     * Points the player at [target] and sets the input that gets them there.
     *
     * The choice of keys is made by [NavSteering], which tries them against the client's own physics
     * rather than picking whichever one points nearest — see there for why the obvious way slides.
     * What is decided here is everything that needs to know about the *route* rather than about the
     * body: whether this is a corner to brake into, whether the ground ahead is worth sprinting on,
     * and which line the body is supposed to be tracking in the first place.
     *
     * [from] is the start of that line. Legs are walked, not teleported between, and steering only
     * at the far end lets a body that has drifted sideways stay drifted for the rest of the leg.
     */
    private fun steer(
        event: MovementInputEvent,
        target: Vec3,
        nextTarget: Vec3? = path.centerAtOrNull(moveIndex + 2),
        from: Vec3 = path.centerAt(moveIndex),
    ) {
        val position = player.position()
        val relative = target.subtract(position)
        val distance = sqrt(relative.x * relative.x + relative.z * relative.z)

        val turn = turnAlignment(relative, target, nextTarget)
        val velocity = velocity()
        val speedSq = velocity.x * velocity.x + velocity.z * velocity.z

        val hairpin = turn < HAIRPIN_COS &&
            distance < HAIRPIN_BRAKE_DISTANCE &&
            speedSq > BRAKE_MIN_SPEED * BRAKE_MIN_SPEED

        // Coming to a halt takes ground, and a body at sprint speed carries the best part of three
        // blocks after the key comes up. Anything the route wants done standing still — mining,
        // building, or simply arriving — has to be braked for before it arrives, not when.
        val braking = distanceToNextStandstill() <= brakingDistance()

        // Decided before the keys are chosen rather than after, because it changes the answer: a
        // sprint covers half again as much ground in the ticks the prediction looks at.
        val sprint = !braking &&
            !hairpin &&
            !isApproachingBridge() &&
            caps.canSprint &&
            distance > SPRINT_MIN_DISTANCE &&
            turn > SPRINT_ALIGN_COS

        steeredInput = when {
            braking || hairpin -> DirectionalInput.NONE

            // A rider's own physics are not what is moving, so simulating them predicts nothing.
            // A mount is steered the plain way: point the head, hold the key nearest the heading.
            player.isPassenger -> getDirectionalInputForDegrees(
                DirectionalInput.NONE,
                getDegreesRelativeToView(relative, player.yRot),
                deadAngle = STEER_DEAD_ANGLE,
            )

            else -> NavSteering.bestInput(from, target, sprint, steeredInput)
        }

        event.directionalInput = steeredInput
        lookAlongRoute(relative)
        wantSprint = sprint
    }

    /**
     * Points the head some way down the route rather than at the waypoint being walked to.
     *
     * A waypoint is a point, and the direction to a point a hand's breadth away is meaningless: the
     * last few ticks of every leg have the yaw swinging through tens of degrees over nothing, and on
     * a lattice staircase — where consecutive legs are at right angles — that reads as a character
     * spinning on the spot. Looking at a point a few blocks along instead gives a heading that turns
     * as the route turns and is steady everywhere else, which is also simply what a person does.
     *
     * The keys are unaffected: they are chosen against the line by [NavSteering], not against the
     * heading. [relative] is the fallback for the end of the route, where there is nothing further
     * along to look at.
     */
    private fun lookAlongRoute(relative: Vec3) {
        val ahead = pursuitPointAhead(LOOK_AHEAD_DISTANCE).subtract(player.position())
        val heading = if (ahead.x * ahead.x + ahead.z * ahead.z > MIN_HEADING_SQ) ahead else relative

        if (heading.x * heading.x + heading.z * heading.z <= MIN_HEADING_SQ) return
        requestRotation(Rotation(yawToward(heading), 0f))
    }

    /**
     * How far the body will still travel if the keys come up now.
     *
     * Ground friction takes about nine per cent of the horizontal speed each tick, so the remaining
     * slide is that speed times roughly ten. Priced a little shorter than the full stop on purpose:
     * the aim is to arrive at the block rather than to halt one short of it.
     */
    private fun brakingDistance(): Double {
        val velocity = velocity()
        return sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * BRAKE_SLIDE_PER_SPEED
    }

    /**
     * Distance along the route to the next thing that has to be done standing still.
     *
     * Mining, building and the end of the journey all need the body stopped where the route said,
     * and every one of them used to be approached at a sprint and overshot. Only a short way ahead
     * is examined: past that the answer cannot matter yet, and the route may not even survive.
     */
    private fun distanceToNextStandstill(): Double {
        var total = 0.0
        var from = player.position()

        val horizon = min(path.size - 1, moveIndex + STOP_LOOKAHEAD)
        for (index in moveIndex + 1..horizon) {
            val point = path.centerAt(index)
            total += sqrt(horizontalDistanceSq(from, point))
            from = point

            // The move leaving this position is the one that would have to start from a standstill.
            if (index < path.moveCount && needsStandstill(path.kindAt(index))) return total
        }

        // Running off the end of the lookahead is only a stop if it is the end of the route.
        return if (horizon == path.size - 1) total else Double.POSITIVE_INFINITY
    }

    /**
     * Moves that have to begin from a standstill.
     *
     * Bridging is not among them any more. It wants to be walked into at a steady pace, because the
     * block goes down in a window that opens as the body crosses the edge — braking to a halt for
     * every block is both slower and no safer, and it is what made bridging look like a machine
     * shuffling to a corner. Sprinting into one is still wrong, and that is handled separately.
     */
    private fun needsStandstill(kind: Int): Boolean = when (kind) {
        NavMove.DIG, NavMove.DIG_DOWN, NavMove.DIG_UP, NavMove.PILLAR -> true
        else -> false
    }

    /**
     * Is there a bridge close enough ahead that arriving at a sprint would overshoot its window?
     *
     * A sprint crosses the third of a block the placement lives in inside one tick. Walking into it
     * keeps the timing comfortable without stopping for it.
     */
    private fun isApproachingBridge(): Boolean {
        val horizon = min(path.moveCount - 1, moveIndex + BRIDGE_SPRINT_LOOKAHEAD)
        for (index in moveIndex..horizon) {
            if (path.kindAt(index) == NavMove.PLACE) return true
        }
        return false
    }

    /**
     * Steers a boat, which is nothing like steering a body.
     *
     * A boat ignores where its passenger is looking entirely. It turns by the left and right keys,
     * which feed its own rotation, and it thrusts along its own hull rather than along the camera.
     * Deriving input from the player's yaw — the right answer for a walking body and for a horse —
     * therefore drives a boat wherever the camera happens to point, which is exactly the symptom.
     *
     * Turning is separated from thrust: a boat pivots far more readily while it is not already
     * charging forwards, so it lines up first and only then accelerates.
     */
    private fun steerBoat(event: MovementInputEvent, boat: AbstractBoat, target: Vec3) {
        val relative = target.subtract(player.position())
        val offBy = Mth.wrapDegrees(yawToward(relative) - boat.yRot)

        // The turn keys accelerate the boat's rotation rather than setting it, and that rotation
        // bleeds off only slowly - about a tenth per tick, so releasing the key still leaves roughly
        // nine times the current rate left to turn through. Steering on the present error alone
        // therefore always overshoots and then corrects back, which is the weaving. Aim at where the
        // heading will actually come to rest instead.
        val turnRate = Mth.wrapDegrees(boat.yRot - boat.yRotO)
        val settlingError = offBy - turnRate * BOAT_TURN_SETTLE

        // Yaw grows clockwise and the right key is what increases the boat's, so a positive error
        // is corrected by steering right.
        val turnRight = settlingError > BOAT_ALIGNED_DEGREES
        val turnLeft = settlingError < -BOAT_ALIGNED_DEGREES

        // Speed governor rather than a separate stop-and-turn mode. A boat can only hold a curve up
        // to the speed its turn rate supports, so the allowance falls away as the heading error
        // grows: full ahead when lined up, and nothing at all near a right angle — which reproduces
        // coming about on the spot without it being a special case. Sailing through gentle turns
        // then happens naturally, because gentle turns barely reduce the allowance.
        val speed = sqrt(
            boat.deltaMovement.x * boat.deltaMovement.x + boat.deltaMovement.z * boat.deltaMovement.z,
        )
        val allowance = 1.0 - (abs(offBy) / BOAT_STANDSTILL_DEGREES).coerceAtMost(1.0)

        event.directionalInput = DirectionalInput(
            forwards = speed < BOAT_CRUISE_SPEED * allowance,
            backwards = false,
            left = turnLeft,
            right = turnRight,
        )
        wantSprint = false

        // The camera does not steer a boat, but pointing it along the route keeps the view sensible
        // and the server-side rotation consistent with where we are going.
        requestRotation(Rotation(yawToward(relative), 0f))
    }

    private fun boatSpeed(boat: AbstractBoat): Double = sqrt(
        boat.deltaMovement.x * boat.deltaMovement.x + boat.deltaMovement.z * boat.deltaMovement.z,
    )

    /**
     * The point [lookahead] blocks along the remaining route, measured by walking its waypoints.
     *
     * The steering target for a vehicle that cannot pivot on the spot. Chasing the nearest waypoint
     * makes such a vehicle saw at every corner; chasing a point a set distance ahead turns each
     * corner into an arc, and the lookahead distance is what sets that arc's radius. Falls back to
     * the end of the route when there is less than [lookahead] of it left, so the last stretch is
     * approached directly.
     */
    private fun pursuitPointAhead(lookahead: Double): Vec3 {
        var remaining = lookahead
        var from = player.position()

        for (index in moveIndex + 1 until path.size) {
            val waypoint = path.centerAt(index)
            val segment = sqrt(horizontalDistanceSq(from, waypoint))

            if (segment >= remaining) {
                if (segment < 1.0E-6) return waypoint
                val ratio = remaining / segment
                return Vec3(
                    from.x + (waypoint.x - from.x) * ratio,
                    waypoint.y,
                    from.z + (waypoint.z - from.z) * ratio,
                )
            }

            remaining -= segment
            from = waypoint
        }
        return path.centerAt(path.size - 1)
    }

    /**
     * Swims toward [target] in three dimensions, along the surface wherever the route allows it.
     *
     * Vanilla's rule for the swimming pose is asymmetric, and the whole technique lives in that
     * asymmetry: it *starts* only with the eyes under water, and then *keeps* going as long as any
     * part of the body is in water. So a crossing dips once to get going and rides the surface
     * afterwards — full swimming speed with the head out, where the air supply is not being spent.
     * Swimming the same lake a couple of blocks lower is the same distance at the same speed, and
     * arrives out of breath.
     */
    private fun swimToward(event: MovementInputEvent, target: Vec3) {
        val relative = target.subtract(player.position())
        val horizontal = sqrt(relative.x * relative.x + relative.z * relative.z)
        var pitch = (-Math.toDegrees(atan2(relative.y, horizontal))).toFloat().coerceIn(-90f, 90f)

        // A move that only rises — the one edge the planner offers a submerged body, to get it back
        // to the surface — has no heading at all. Steering it produces a key pointing at world yaw
        // zero and, worse, the dip below would put the nose thirty degrees *down* on the one move
        // whose entire purpose is up.
        if (horizontal < VERTICAL_SWIM_EPSILON) {
            requestRotation(Rotation(player.yRot, -90f))
            event.directionalInput = DirectionalInput.NONE
            event.jump = true
            wantSprint = false
            return
        }

        // Only override the aim when the route is not itself asking us to go down.
        if (relative.y > -SWIM_DEPTH_EPSILON) {
            if (!player.isSwimming) {
                // The one dip that starts the pose.
                pitch = max(pitch, SUBMERGE_PITCH)
            } else if (player.isUnderWater) {
                // Back up to the surface, still swimming, no longer breathing water.
                pitch = min(pitch, SURFACE_PITCH)
                event.jump = true
            }
        }

        requestRotation(Rotation(yawToward(relative), pitch))

        val degrees = getDegreesRelativeToView(relative, player.yRot)
        event.directionalInput = getDirectionalInputForDegrees(DirectionalInput.NONE, degrees, deadAngle = 20f)
        // Sprinting underwater is what puts the player into the swimming pose, which is much faster.
        wantSprint = caps.canSprint
        if (relative.y > SWIM_RISE_THRESHOLD) event.jump = true
    }

    private fun yawToward(relative: Vec3): Float =
        Math.toDegrees(atan2(-relative.x, relative.z)).toFloat()

    /**
     * Cosine of the angle between the leg into [target] and the leg out of it. One means straight
     * on, negative means doubling back.
     */
    private fun turnAlignment(relative: Vec3, target: Vec3, next: Vec3?): Double {
        if (next == null) return 1.0
        val outX = next.x - target.x
        val outZ = next.z - target.z
        val inMagnitude = sqrt(relative.x * relative.x + relative.z * relative.z)
        val outMagnitude = sqrt(outX * outX + outZ * outZ)
        if (inMagnitude < 1.0E-3 || outMagnitude < 1.0E-3) return 1.0
        return (relative.x * outX + relative.z * outZ) / (inMagnitude * outMagnitude)
    }

    private fun releaseInput(event: MovementInputEvent) {
        event.directionalInput = DirectionalInput.NONE
        wantSprint = false
    }

    /**
     * Asks for a rotation that has to be there now rather than eventually.
     *
     * Only for building against a face — see [withoutSmoothing]. Everywhere else the owner's
     * humanised aiming is exactly what is wanted.
     */
    private fun requestRotationInstantly(rotation: Rotation) {
        val target = rotations.toRotationTarget(rotation, considerInventory = false)
        RotationManager.setRotationTarget(target.withoutSmoothing(), Priority.IMPORTANT_FOR_USAGE_1, owner)
    }

    private fun requestRotation(rotation: Rotation) {
        val target = rotations.toRotationTarget(rotation, considerInventory = false)

        // Silent correction does not only hide the rotation, it also rewrites movement input to
        // compensate for the gap between camera and target. That is right for a body, whose input is
        // read relative to where it looks - but a boat's left and right are a rudder, not a
        // sidestep, so any rewriting of them corrupts the steering outright. Strict keeps the
        // rotation hidden without touching input, which is what a boat needs.
        if (player.vehicle is AbstractBoat) {
            RotationManager.setRotationTarget(
                target.withCorrection(MovementCorrection.STRICT),
                Priority.IMPORTANT_FOR_USAGE_1,
                owner,
            )
            return
        }

        // A ridden animal is steered by the rider's real head yaw, so a silent rotation - which only
        // changes what the server is told - leaves it following the untouched camera. The mixin on
        // the mount's own steering fixes that without disturbing the view; only when it is switched
        // off does the camera itself have to turn.
        val needsVisibleCamera = player.isPassenger &&
            !NavMountSteering.enabled &&
            !target.movementCorrection.usesVisibleCamera()

        val effective = if (needsVisibleCamera) {
            target.withCorrection(MovementCorrection.CHANGE_LOOK)
        } else {
            target
        }

        RotationManager.setRotationTarget(effective, Priority.IMPORTANT_FOR_USAGE_1, owner)
    }

    // -----------------------------------------------------------------------
    //  Arrival and overshoot
    // -----------------------------------------------------------------------

    /**
     * Advances once the waypoint is either reached or already behind us.
     *
     * A boat carries far more momentum than a body and cannot stop on a block centre, so requiring
     * arrival alone would leave it circling a waypoint it has already sailed past.
     */
    private fun advanceIfReached(target: Vec3): NavExecStatus {
        if (!hasArrivedAt(target) && !hasOvershot(target)) return NavExecStatus.RUNNING
        moveIndex++
        resetStuck()
        return if (moveIndex >= path.moveCount) NavExecStatus.FINISHED else NavExecStatus.RUNNING
    }

    private fun advanceIfArrived(target: Vec3): NavExecStatus {
        if (!hasArrivedAt(target)) return NavExecStatus.RUNNING
        if (!canStartLegFrom(moveIndex + 1)) return NavExecStatus.RUNNING
        moveIndex++
        resetStuck()
        return if (moveIndex >= path.moveCount) NavExecStatus.FINISHED else NavExecStatus.RUNNING
    }

    /**
     * Could the leg after this waypoint actually be started from where the body is standing?
     *
     * Arrival is judged with a radius, so "arrived" routinely means standing at the edge of a block
     * rather than in it. Turning from there is what wedges a player on an inside corner: in a
     * one-wide corridor — which is what a dug route is — the turn begins while half the hitbox is
     * still in the block behind, and that half meets the corner. The body then grinds along the
     * wall while the executor insists it is walking.
     *
     * So the next leg has to be clear from here before we commit to it; otherwise we keep walking
     * into the block we are in, which puts the body in the middle where the turn fits.
     *
     * Only asked of level walking legs, and only on foot. A jump, a drop, a dig or a placement is
     * driven by its own rules and has no reason to present a clear straight line at all.
     */
    private fun canStartLegFrom(index: Int): Boolean {
        if (index >= path.moveCount) return true
        if (!isOnGround()) return true

        val kind = path.kindAt(index)
        if (kind != NavMove.WALK && kind != NavMove.DIAGONAL) return true

        return isStraightWalkable(player.position(), path.centerAt(index + 1), path.yAt(index + 1))
    }

    private fun hasArrivedAt(target: Vec3): Boolean {
        val position = player.position()
        val dx = target.x - position.x
        val dz = target.z - position.z
        if (dx * dx + dz * dz > ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) return false

        // A move that only goes up or down has no horizontal distance to prove it happened, so the
        // height is the whole test and the ordinary tolerance is wider than the move itself: a
        // twelve-rung ladder was retired at one rung per tick while the body had climbed two.
        val vertical = if (isVerticalMove(path.kindAt(moveIndex))) CLIMB_ARRIVAL_HEIGHT else ARRIVAL_HEIGHT
        return abs(target.y - position.y) < vertical
    }

    /** Does this move change height and nothing else? */
    private fun isVerticalMove(kind: Int): Boolean =
        kind == NavMove.CLIMB || kind == NavMove.PILLAR || kind == NavMove.DIG_UP

    /**
     * Drops waypoints that momentum already carried the player past.
     *
     * Steering back to a block centre that is now behind us produces the stutter that makes a bot
     * obvious — most visibly right after a drop, where the landing throws us a block or so beyond
     * the waypoint we were aiming at. Besides the overshoot test, a waypoint is abandoned whenever
     * the one after it is already closer, which is the general "we are past this" condition.
     *
     * Only level walking steps are skipped: jumps, drops and anything that changes the world have to
     * be executed in order.
     */
    private fun skipWaypointsAlreadyPassed() {
        while (moveIndex < path.moveCount) {
            val kind = path.kindAt(moveIndex)
            if (kind != NavMove.WALK && kind != NavMove.DIAGONAL) return

            val target = path.centerAt(moveIndex + 1)
            // Momentum past a waypoint is a fact and skipping it is right regardless; merely being
            // near one is not, and committing to the next leg from the edge of a block is how a
            // turn clips the corner.
            val passed = hasOvershot(target) ||
                isNextWaypointCloser() ||
                (hasArrivedAt(target) && canStartLegFrom(moveIndex + 1))
            if (!passed) return
            moveIndex++
            resetStuck()
        }
    }

    /** True when the waypoint after the current one is already the nearer of the two. */
    private fun isNextWaypointCloser(): Boolean {
        if (moveIndex + 2 >= path.size) return false
        // Only meaningful while the run stays level; across a drop the comparison is misleading.
        if (path.kindAt(moveIndex + 1) != NavMove.WALK && path.kindAt(moveIndex + 1) != NavMove.DIAGONAL) {
            return false
        }

        val position = player.position()
        val next = path.centerAt(moveIndex + 2)
        if (horizontalDistanceSq(position, next) >= horizontalDistanceSq(position, path.centerAt(moveIndex + 1))) {
            return false
        }

        // Nearer is not the same as reachable. Round the inside of a corner the next waypoint is
        // nearer while the line to it runs through the corner block.
        return canStartLegFrom(moveIndex + 1)
    }

    private fun horizontalDistanceSq(from: Vec3, to: Vec3): Double {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return dx * dx + dz * dz
    }

    /**
     * Is this waypoint behind us, and near enough that skipping it is safe?
     *
     * The distance cap matters here: without it, a waypoint far off to one side would count as
     * "behind" the moment we turned, and the whole route would unravel.
     */
    private fun hasOvershot(target: Vec3): Boolean {
        val position = player.position()
        val dx = target.x - position.x
        val dz = target.z - position.z
        if (dx * dx + dz * dz > OVERSHOOT_MAX_DISTANCE * OVERSHOOT_MAX_DISTANCE) return false
        return isTargetBehind(target)
    }

    /** Is the target behind the direction we are actually travelling, at any distance? */
    private fun isTargetBehind(target: Vec3): Boolean {
        val position = player.position()
        val velocity = velocity()
        val speedSq = velocity.x * velocity.x + velocity.z * velocity.z
        if (speedSq < OVERSHOOT_MIN_SPEED * OVERSHOOT_MIN_SPEED) return false

        val dx = target.x - position.x
        val dz = target.z - position.z
        return dx * velocity.x + dz * velocity.z < 0.0
    }

    private fun horizontalDistanceTo(target: Vec3): Double {
        val position = player.position()
        val dx = target.x - position.x
        val dz = target.z - position.z
        return sqrt(dx * dx + dz * dz)
    }

    // -----------------------------------------------------------------------
    //  Failure detection
    // -----------------------------------------------------------------------

    private fun updateStuckDetection(): NavExecStatus {
        val position = player.position()

        // Mining and building legitimately stand still, so the position-based detector has nothing
        // to say about them — but standing still for ever is still failure, which is what the move
        // deadline below is for.
        if (working) {
            stuckTicks = 0
            lastPosition = position
            return moveDeadline()
        }

        if (position.distanceToSqr(lastPosition) < STUCK_MOVEMENT_EPSILON) {
            stuckTicks++
            if (stuckTicks >= STUCK_TICKS_LIMIT) {
                resetStuck()
                return NavExecStatus.NEEDS_REPLAN
            }
        } else {
            stuckTicks = 0
        }
        lastPosition = position

        // Drifting far off the route means the world moved under us, or a jump missed. Measured
        // against the *leg* rather than against its far end, and not at all while airborne: a
        // parkour move puts its landing four blocks away and a drop carries the body several more,
        // so a distance-to-target test fired before the jump had even begun — and a replan mid-jump
        // silences the executor for the whole search, which drops air control at the worst moment.
        if (isOnGround() && !isTravellingMove(path.kindAt(moveIndex)) &&
            distanceFromLeg(position) > DRIFT_LIMIT
        ) {
            return NavExecStatus.NEEDS_REPLAN
        }

        return moveDeadline()
    }

    /**
     * Abandons a move that has taken far longer than any move of its kind should.
     *
     * The catch-all for getting nowhere while still technically doing something: landing a drop just
     * outside its landing radius, mining a block that will not break, scraping along a wall towards
     * a waypoint the body cannot reach. None of those look like being stuck, and all of them mean
     * the plan is wrong.
     */
    private fun moveDeadline(): NavExecStatus {
        val limit = when (path.kindAt(moveIndex)) {
            // Mining is genuinely slow, and how slow depends on the tool and the block.
            NavMove.DIG, NavMove.DIG_DOWN, NavMove.DIG_UP -> DIG_DEADLINE_TICKS
            NavMove.PILLAR, NavMove.PLACE -> BUILD_DEADLINE_TICKS
            else -> MOVE_DEADLINE_TICKS
        }
        return if (moveTicks > limit) NavExecStatus.NEEDS_REPLAN else NavExecStatus.RUNNING
    }

    private fun resetStuck() {
        stuckTicks = 0
        moveTicks = 0
        aimWaitTicks = 0
        lastPosition = player.position()
    }

    // -----------------------------------------------------------------------
    //  Air
    // -----------------------------------------------------------------------

    /** Returns true while the route is suspended to come up for air. */
    private fun maybeSurfaceForAir(event: MovementInputEvent): Boolean {
        val underwater = player.isUnderWater
        if (!breathing) {
            if (!underwater || player.airSupply > AIR_LOW_THRESHOLD) return false
            breathing = true
            breathTicks = 0
            bestDepth = player.y
        }
        if (!underwater || player.airSupply >= player.maxAirSupply - 1) {
            breathing = false
            return false
        }

        // Swimming up only works when there is somewhere up to go. Under a ceiling — an underwater
        // tunnel, a flooded cave — this presses jump into rock until the player drowns, so the one
        // thing it must not do is keep at it silently.
        //
        // Progress is measured as height gained, not as air gained. Air only comes back once the
        // eyes are clear of the water, and this loop has already ended by then — so judging by air
        // meant the deadline was a plain timer that fired on every ascent from deeper than about six
        // blocks, replanning from underwater rather than surfacing.
        if (player.y > bestDepth + BREATH_RISE_EPSILON) {
            bestDepth = player.y
            breathTicks = 0
        } else {
            breathTicks++
        }

        requestRotation(Rotation(player.yRot, -90f))
        event.directionalInput = DirectionalInput(forwards = true, backwards = false, left = false, right = false)
        event.jump = true
        wantSprint = caps.canSprint
        working = true
        return true
    }

    /** Are we out of air and getting no closer to any? */
    private fun isDrowningInPlace(): Boolean = breathTicks > BREATH_DEADLINE_TICKS

    private companion object {
        /** Horizontal distance at which a waypoint counts as reached. */
        const val ARRIVAL_DISTANCE = 0.55

        /** Vertical tolerance for arrival, so a fall in progress is not mistaken for landing. */
        const val ARRIVAL_HEIGHT = 1.2

        /**
         * Height tolerance for a move that is nothing but a change of height.
         *
         * Well under the one block such a move covers. The general tolerance is deliberately loose
         * because a walking body bobs and falls, but applied to a ladder it declares each rung
         * reached the moment the one below it is, and a whole ladder is consumed in a few ticks
         * while the body is still at the bottom of it.
         */
        const val CLIMB_ARRIVAL_HEIGHT = 0.35

        /** Beyond this from the route, something went wrong enough to replan. */
        const val DRIFT_LIMIT = 2.5

        /** Roughly two seconds of no movement. */
        const val STUCK_TICKS_LIMIT = 40
        const val STUCK_MOVEMENT_EPSILON = 0.01

        /**
         * Ticks a single placement may take before the route is called wrong.
         *
         * Generous — a placement waits on a rotation that may be smoothed over a second, and on a
         * jump — but finite, because building is exactly where standing still looks like working.
         */
        const val BUILD_DEADLINE_TICKS = 60

        /**
         * Ticks to wait for the rotation to arrive before placing anyway.
         *
         * Short. The wait buys the block landing on the face the route chose; it is not worth a
         * stalled route when something else is holding the rotation.
         */
        const val AIM_WAIT_TICKS = 10

        /** Any ordinary move — a step, a jump, a drop — that takes three seconds has gone wrong. */
        const val MOVE_DEADLINE_TICKS = 60

        /** The godbridge's pitch. Not a round number, and not one to round. */
        const val GODBRIDGE_PITCH = 75.7f

        /** How far ahead the stance looks when deciding which diagonal reaches the face. */
        const val GODBRIDGE_LOOKAHEAD = 0.6

        /**
         * Overhang at which the safety sneak comes on, in blocks before the edge.
         *
         * A third of a block of margin: sneak has to be held on the tick the movement is applied,
         * and backing out at a walk covers about a fifth of a block in one. Earlier than this only
         * costs speed, later is a step into the gap.
         */
        const val SNEAK_FROM_OVERHANG = -0.35

        /**
         * Height on the side face the bridge aims at, above the block's own base.
         *
         * Below the middle. Higher grazes the lip we are leaning over and lands on the top face
         * instead — which is how a bridge turns into a block placed beside the player.
         */
        const val FACE_AIM_HEIGHT = 0.35

        /**
         * Mining is allowed ten seconds.
         *
         * Long enough for a bare hand on stone, which is the slowest thing a route plans for; a
         * block that has not given way by then is one the plan was wrong about.
         */
        const val DIG_DEADLINE_TICKS = 200

        /** Only skip a waypoint we are still near; otherwise we would skip the whole route. */
        const val OVERSHOOT_MAX_DISTANCE = 2.0

        /** How close to the target height counts as having finished a drop. */
        const val DROP_LANDED_TOLERANCE = 0.35

        /** Momentum may carry us this far past a landing column before the drop counts as botched. */
        const val DROP_LANDING_RADIUS = 2.2

        /**
         * Ticks the line to a latched run target may fail its check before it is given up.
         *
         * The failure this rides out is a sampling one — a hitbox corner passing over a drop for a
         * tick or two as the body crosses a block boundary. A wall does not go away again, so a
         * fifth of a second costs a brush against it and buys a steady heading everywhere else.
         */
        const val RUN_LATCH_GRACE = 4

        /**
         * How far down the route the head looks, in blocks.
         *
         * Far enough that arriving at a waypoint barely moves it, near enough that a corner is
         * turned into rather than anticipated out of existence.
         */
        const val LOOK_AHEAD_DISTANCE = 3.0

        /** Below this, a heading vector is shorter than the noise in the position that made it. */
        const val MIN_HEADING_SQ = 0.04

        /** Below this speed the velocity direction is noise, not travel. */
        const val OVERSHOOT_MIN_SPEED = 0.08

        const val SPRINT_MIN_DISTANCE = 2.0

        /** cos(45 degrees): sprint only into gentle turns. */
        const val SPRINT_ALIGN_COS = 0.7

        /** cos(100 degrees): a genuine doubling-back. */
        const val HAIRPIN_COS = -0.2
        const val HAIRPIN_BRAKE_DISTANCE = 1.4

        /** Releasing forward below sprint speed stalls rather than brakes. */
        const val BRAKE_MIN_SPEED = 0.17

        /**
         * Blocks of slide per block-per-tick of speed once the keys come up.
         *
         * Ground friction leaves about ninety-one per cent of the speed each tick, so the full
         * slide is a little over ten times it — nearly three blocks from a sprint. Seven is used
         * here rather than ten because the goal is to arrive at the block with almost nothing left,
         * not to stop short of it and then creep the rest of the way.
         */
        const val BRAKE_SLIDE_PER_SPEED = 7.0

        /** Nodes ahead to look for something that has to be done standing still. */
        const val STOP_LOOKAHEAD = 24

        /** Nodes ahead a bridge suppresses sprinting from. */
        const val BRIDGE_SPRINT_LOOKAHEAD = 3

        /**
         * Heading error tolerated before the input is changed to correct it, in degrees.
         *
         * Tight. The eight keyboard directions already quantise the answer to within a couple of
         * dozen degrees, and adding a wide dead band on top means small errors are not corrected at
         * all — they accumulate until the body is off the line, then snap back. On open ground that
         * reads as weaving; on a one-block ledge it reads as falling off.
         */
        const val STEER_DEAD_ANGLE = 5.0f

        /** No jump for a step further away than this, whatever the simulation says. */
        const val ASCEND_JUMP_DISTANCE = 1.2

        /**
         * Furthest the middle of a 0.6-wide body can be from the middle of a block and still be
         * entirely on it. Half a block less half a body.
         */
        const val COLUMN_FIT_RADIUS = 0.2

        /**
         * How near the middle of a block the settling walk aims to stop.
         *
         * Inside [COLUMN_FIT_RADIUS] with a little to spare, so a tick of coasting does not put the
         * body back outside it. Tighter would be dancing on the spot.
         */
        const val COLUMN_SETTLE_RADIUS = 0.15

        /** Blocks below the lip at which a dug hole counts as entered rather than stood beside. */
        const val DIG_DOWN_ENTERED = 0.3

        /**
         * Ticks spent walking onto a spot before the manoeuvre goes ahead from where it is.
         *
         * A second. Getting it right is worth waiting for and is not worth waiting for forever: if
         * the body cannot settle on the block in that time something else is wrong with the plan,
         * and a placement made from slightly the wrong place at least ends the move — after which
         * the drift and deadline checks can have their say.
         */
        const val SETTLE_PATIENCE = 20

        /**
         * Ticks of jump to simulate before giving up on it landing anywhere useful.
         *
         * A sprint jump is airborne for about twelve; the rest is margin for one that starts from a
         * height and comes down further away.
         */
        const val JUMP_SIMULATION_TICKS = 24

        /** How near the landing has to be to the block we aimed at, horizontally. */
        const val JUMP_LANDING_RADIUS = 0.75

        /** And how near in height, so landing on something else entirely does not count. */
        const val JUMP_LANDING_HEIGHT = 0.6

        /** Below this a swim edge is purely vertical and has no heading worth steering at. */
        const val VERTICAL_SWIM_EPSILON = 0.05

        /** Rise threshold before adding jump while swimming upward. */
        const val SWIM_RISE_THRESHOLD = 0.3

        /** Below this the route is genuinely taking us down, and the surface rules stand aside. */
        const val SWIM_DEPTH_EPSILON = 0.5

        /** Nose-down attitude for the one dip that starts the swimming pose. */
        const val SUBMERGE_PITCH = 30f

        /** Nose-up attitude that brings the head back out while the pose carries on. */
        const val SURFACE_PITCH = -20f

        /** Close enough to the heading that steering further would just weave. */
        const val BOAT_ALIGNED_DEGREES = 3.0f

        /**
         * How much further a boat turns after the key is released, as a multiple of its current
         * turn rate.
         *
         * Its rotation decays by about a tenth each tick, so the tail sums to roughly nine times
         * whatever it is turning at now. Ignoring that is what makes bang-bang steering weave.
         */
        const val BOAT_TURN_SETTLE = 9.0f

        /** Heading error at which the governor allows no speed at all, so the boat turns in place. */
        const val BOAT_STANDSTILL_DEGREES = 75.0

        /** Roughly a boat's flat-out speed on water, in blocks per tick. */
        const val BOAT_CRUISE_SPEED = 0.4

        /** Lookahead at a standstill. Sets the tightest arc the boat will be asked to trace. */
        const val BOAT_LOOKAHEAD_BASE = 3.0

        /** Extra lookahead per block-per-tick of speed: faster boats need a wider berth. */
        const val BOAT_LOOKAHEAD_PER_SPEED = 14.0

        /** Surface once under three seconds of air remain. */
        const val AIR_LOW_THRESHOLD = 60

        /**
         * Ticks of swimming upward with nothing to show for it before the route is blamed.
         *
         * Short, because the whole budget is short: surfacing begins with three seconds of air in
         * hand. A second and a half of rising without gaining any of it back means there is a
         * ceiling, and the answer to a ceiling is a different route rather than more jumping.
         */
        const val BREATH_DEADLINE_TICKS = 30

        /** Height that counts as having risen, so a bobbing body is not mistaken for a rising one. */
        const val BREATH_RISE_EPSILON = 0.05

        const val SLOT_RESET_TICKS = 4
    }
}
