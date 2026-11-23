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
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.ElytraRoute
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.ccbluex.liquidbounce.utils.raytracing.clip
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Flies an [ElytraRoute], legitimately.
 *
 * Nothing here writes velocity or forges a movement packet. The whole flight is flown by asking
 * [RotationManager] for a rotation and letting vanilla's own gliding physics follow it — the client
 * takes the pitch and look vector from the active rotation through the hooks in `MixinLivingEntity`,
 * and the server sees the same rotation on the wire. Rockets are lit out of the hotbar exactly as a
 * player would light them. Take-off is a jump followed by a second press of jump in the air.
 *
 * ## Handing control back
 *
 * A glide never reports back while airborne. The ground planner searches a lattice of places a body
 * can stand, so a request that starts in mid-air has no floor beneath it and comes back as
 * "unreachable" — the goal would look walled off when the real problem is that we asked from the
 * sky. So every way this leg can end, including a wing that gives out halfway, ends by landing
 * first.
 */
@Suppress("TooManyFunctions")
class ElytraExecutor(
    route: ElytraRoute,
    private val owner: ClientModule,
    private val rotations: RotationsValueGroup,
) : NavExecutor {

    /**
     * The route being flown. Replaced in the air by [refresh] as the world arrives.
     *
     * A long flight is planned almost entirely over ground nobody has seen: the altitude profile
     * comes from a height map that stops at the render distance, and past that the plan is a
     * straight line held at the last height that meant anything. That is the correct thing to set
     * off with and the wrong thing to still be flying an hour later, so the plan is rebuilt while
     * under way — the destination never changes, only what is known about the way there.
     */
    private var route: ElytraRoute = route
        private set

    private enum class Phase {
        /** On the ground, or falling, and not yet gliding. */
        TAKEOFF,

        /** Under way along the route. */
        CRUISE,

        /** Final descent onto the destination. */
        LANDING,
    }

    private var phase = if (player.isFallFlying) Phase.CRUISE else Phase.TAKEOFF

    /** Waypoint being flown at. Index zero is where the route started, which is behind us. */
    private var waypointIndex = 1

    private var takeoffTicks = 0
    private var fireworkCooldown = 0
    private var recastCooldown = 0

    /** Set once the flight has committed to putting down here; cleared only by a relaunch. */
    private var touchdownCommitted = false

    /** Times this route has picked itself back up off the ground. */
    private var relaunches = 0

    /** Closest we have been to the destination, and how long since that last improved. */
    private var bestDistance = Double.MAX_VALUE
    private var stagnantTicks = 0

    /**
     * Set when the flight has been given up on: put down, then ask for a new plan.
     *
     * A glide cannot hand back control in mid-air — the ground planner has no floor to plan from up
     * here — so abandoning a flight still means flying it down first. What changes is what happens
     * on touchdown: a new plan rather than a walk along the rest of a route that stopped working.
     */
    private var aborting = false

    /**
     * Gives up on this flight without abandoning the aircraft.
     *
     * For the service, when it has decided the request is over while the player is still gliding.
     * The wing keeps being flown — it is the only thing that can put the player down — and the leg
     * ends the moment it is on the ground.
     */
    fun abort() {
        aborting = true
    }

    /** Rockets this flight has actually lit, for the debug overlay. */
    var rocketsUsed = 0
        private set

    /** Where the route ends: the destination itself, at ground level. */
    private var destination: Vec3 = route.waypoints.last()

    override val progress: Float
        get() = if (route.waypoints.size < 2) 1f else waypointIndex.toFloat() / (route.waypoints.size - 1)

    /**
     * Takes a freshly planned route for the same destination, mid-flight.
     *
     * Only while cruising, and only while the destination has not moved: an approach that has
     * already committed to putting down somewhere must not have the ground swapped out from under
     * it, and the take-off is aiming at a first waypoint it is halfway to. Everything the flight has
     * learned — how many rockets it has burned, how many times it has picked itself up, how long it
     * has gone without getting closer — carries over, because the journey is the same journey.
     *
     * Returns false when the flight declined it, in which case the old plan stands.
     */
    fun refresh(fresh: ElytraRoute): Boolean {
        if (phase != Phase.CRUISE || aborting || fresh.isEmpty) return false

        // Measured across the ground only. The height of a destination outside the render distance
        // is a guess until its chunk arrives, and correcting that guess is one of the things the
        // refresh is for — it must not look like a different destination.
        val moved = fresh.waypoints.last().let { hypot(it.x - destination.x, it.z - destination.z) }
        if (moved > DESTINATION_DRIFT) return false

        route = fresh
        destination = fresh.waypoints.last()
        // The new plan starts from where the body is now, so its first point is the one behind us.
        waypointIndex = 1
        return true
    }

    override fun tick(event: MovementInputEvent): NavExecStatus {
        // Gliding ignores the movement keys; holding them only affects what happens on touchdown.
        event.directionalInput = DirectionalInput.NONE
        if (fireworkCooldown > 0) fireworkCooldown--
        if (recastCooldown > 0) recastCooldown--

        return when (phase) {
            Phase.TAKEOFF -> takeOff(event)
            Phase.CRUISE -> cruise()
            Phase.LANDING -> land()
        }
    }

    // -----------------------------------------------------------------------
    //  Phases
    // -----------------------------------------------------------------------

    /**
     * Gets off the ground: jump, then press jump again in the air.
     *
     * The second press is what casts the wing, and it is the same packet a player's keyboard sends.
     * Climbing to cruise height is not part of take-off — that is the ordinary altitude error the
     * guidance law handles once we are flying, paid for with rockets.
     */
    private fun takeOff(event: MovementInputEvent): NavExecStatus {
        if (player.isFallFlying) {
            phase = Phase.CRUISE
            return NavExecStatus.RUNNING
        }

        // Still on our feet, so this is the one moment a failed flight can be abandoned cheaply.
        if (!NavHotbar.wearingElytra()) return NavExecStatus.NEEDS_REPLAN

        takeoffTicks++
        if (takeoffTicks > TAKEOFF_LIMIT) return NavExecStatus.NEEDS_REPLAN

        // Point the nose along the route before the wing catches, so the very first tick of flight
        // is already headed somewhere useful rather than wherever the camera happened to be.
        //
        // Aimed at the first waypoint rather than simply upward, which comes to the same thing in
        // the open — the first waypoint of a glide is at cruise height, far above — but is the
        // difference between flying a Nether cavern and launching into its ceiling.
        val first = route.waypoints[waypointIndex]
        requestRotation(Rotation(yawToward(first), takeoffPitch(first)))

        if (player.onGround()) {
            event.jump = true
            return NavExecStatus.RUNNING
        }
        if (player.deltaMovement.y != 0.0) startGliding()
        return NavExecStatus.RUNNING
    }

    private fun cruise(): NavExecStatus {
        if (!player.isFallFlying) return recoverOrFinish()

        advanceWaypoints()
        if (isOnFinalApproach()) {
            phase = Phase.LANDING
            return land()
        }

        if (hasStoppedMakingProgress()) {
            // Nothing about this route is working any more. Put it down and plan again from the
            // ground, rather than flying a stale plan until the terrain interrupts it.
            aborting = true
            phase = Phase.LANDING
            return land()
        }

        steerAt(route.waypoints[waypointIndex], landing = false)
        return NavExecStatus.RUNNING
    }

    /**
     * Has the flight stopped getting anywhere?
     *
     * The one thing a glide cannot do is notice that it is lost. It never yields in mid-air by
     * design, so without this it flies a route that has stopped describing where it is — circling a
     * waypoint it cannot reach, or gliding on a plan the world has moved out from under — until the
     * ground happens to stop it. From outside that is a bot doing something inexplicable for a long
     * time instead of simply replanning, which is exactly what it is.
     *
     * Progress is measured against the destination rather than the next waypoint, because chasing a
     * waypoint in circles is itself one of the ways of getting nowhere.
     */
    private fun hasStoppedMakingProgress(): Boolean {
        val distance = horizontalDistanceTo(destination)
        if (distance < bestDistance - PROGRESS_EPSILON) {
            bestDistance = distance
            stagnantTicks = 0
            return false
        }

        stagnantTicks++
        return stagnantTicks > STAGNANT_LIMIT_TICKS
    }

    private fun land(): NavExecStatus {
        if (!player.isFallFlying) return recoverOrFinish()

        // Once we are over the destination, or low enough that the run is going to end anyway, the
        // destination stops being a thing to steer at for the rest of the flight. Recomputing that
        // from distance alone would unlatch as soon as the aircraft flew past — and turning back
        // there is how a tight orbit becomes a wide one instead of a landing.
        if (horizontalDistanceTo(destination) < ElytraGuidance.TOUCHDOWN_RADIUS ||
            player.y - groundBelow() < ElytraGuidance.FLARE_HEIGHT
        ) {
            touchdownCommitted = true
        }

        steerAt(destination, landing = true)
        return NavExecStatus.RUNNING
    }

    /**
     * The wing stopped flying. Either we are down, or we are about to be.
     *
     * A glide that drops out in mid-air — clipping a leaf, a wing that broke — is recast if it can
     * be, and otherwise ridden down. What is not allowed is handing control back while still
     * falling, which would ask the ground planner to plan from thin air.
     */
    private fun recoverOrFinish(): NavExecStatus {
        if (player.onGround()) return relaunchOrFinish()
        // Water ends a glide for good: the wing will not catch again from in it.
        if (player.isInWater) return NavExecStatus.FINISHED

        // Spaced out rather than tried every tick: if the server is refusing the cast for a reason
        // we cannot see from here, one attempt a quarter-second is a retry and sixty is a flood.
        if (recastCooldown > 0) return NavExecStatus.RUNNING

        if (NavHotbar.wearingElytra() && player.deltaMovement.y < 0.0) {
            startGliding()
            recastCooldown = RECAST_COOLDOWN
            // Whatever phase the flight was in, it is still in. A wing that blinks out for a tick
            // over a forest three thousand blocks from home has not begun its approach: forcing
            // LANDING there threw away every remaining waypoint, refused all further rockets and
            // pointed the nose at a destination that was still an hour away — through, on an
            // enclosed route, the walls the search had gone round.
        }
        return NavExecStatus.RUNNING
    }

    /**
     * Down, but not necessarily done.
     *
     * A glide that ends on a hillside halfway along its route has not arrived anywhere; the route
     * still leads somewhere far off, and the way to carry on with it is the way it started — jump,
     * and catch the wing in the air. Handing back to the walker here would mean walking the rest of
     * a journey that was planned as a flight.
     *
     * Bounded, though. Each launch costs a rocket, and a spot that puts us back on the ground
     * immediately would otherwise empty the whole stack into it one jump at a time.
     */
    private fun relaunchOrFinish(): NavExecStatus {
        // A flight we gave up on is down where we wanted it: on the ground, where a new plan can
        // start. Launching again would just fly the same failed route.
        if (aborting) return NavExecStatus.NEEDS_REPLAN

        val remaining = horizontalDistanceTo(destination)
        val worthIt = remaining > RELAUNCH_MIN_DISTANCE &&
            relaunches < MAX_RELAUNCHES &&
            NavHotbar.wearingElytra() &&
            NavFireworks.storedSlot() != null

        if (!worthIt) return NavExecStatus.FINISHED

        relaunches++
        takeoffTicks = 0
        // Whatever the last approach decided about putting down here no longer applies, and the
        // new leg deserves its own chance to make progress before being judged.
        touchdownCommitted = false
        bestDistance = Double.MAX_VALUE
        stagnantTicks = 0
        phase = Phase.TAKEOFF
        return NavExecStatus.RUNNING
    }

    private fun startGliding() {
        player.startFallFlying()
        network.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING),
        )
    }

    // -----------------------------------------------------------------------
    //  Route following
    // -----------------------------------------------------------------------

    /** Drops waypoints we have reached or already flown past. */
    private fun advanceWaypoints() {
        while (waypointIndex < route.waypoints.lastIndex) {
            if (!hasPassed(route.waypoints[waypointIndex]) && !isNextWaypointCloser()) return
            waypointIndex++
        }
    }

    /**
     * Is the waypoint after this one already the nearer of the two?
     *
     * The general "we are past this" test, and the one that stops a waypoint the aircraft could not
     * quite hit from holding the whole route hostage. Without it a wide turn leaves the glider
     * circling back for a point it has effectively already made.
     */
    private fun isNextWaypointCloser(): Boolean {
        if (waypointIndex + 1 > route.waypoints.lastIndex) return false

        val current = horizontalDistanceTo(route.waypoints[waypointIndex])
        return horizontalDistanceTo(route.waypoints[waypointIndex + 1]) < current
    }

    /**
     * Is this waypoint behind us, or near enough to count as made?
     *
     * The reach is generous because the route is a line in the sky rather than a lattice of blocks:
     * a glider that passes within a dozen blocks of a point at cruise has flown the route as
     * intended, and turning back to hit it exactly would only cost speed.
     */
    private fun hasPassed(point: Vec3): Boolean {
        val dx = point.x - player.x
        val dz = point.z - player.z
        val distanceSq = dx * dx + dz * dz
        // Tighter between walls, where a dozen blocks of slack is most of the corridor.
        val reach = if (route.enclosed) ENCLOSED_WAYPOINT_REACH else WAYPOINT_REACH
        if (distanceSq < reach * reach) return true

        val velocity = player.deltaMovement
        return distanceSq < PASSED_MAX_DISTANCE * PASSED_MAX_DISTANCE &&
            dx * velocity.x + dz * velocity.z < 0.0
    }

    /**
     * Should the descent start?
     *
     * Descending is free and climbing is not, so the approach begins as soon as the remaining
     * distance is only just enough to lose the height at a comfortable slope. Leaving it later
     * means arriving above the destination and having to spiral down; earlier only means a longer,
     * shallower glide, which costs nothing.
     */
    private fun isOnFinalApproach(): Boolean {
        if (waypointIndex >= route.waypoints.lastIndex) return true

        // Only in the open. A searched route through enclosed space carries its own height profile,
        // and cutting the corner off it descends through whatever the search was routing around.
        if (route.enclosed) return false

        val height = (player.y - destination.y).coerceAtLeast(0.0)
        return horizontalDistanceTo(destination) <= height * DESCENT_RATIO
    }

    // -----------------------------------------------------------------------
    //  Steering
    // -----------------------------------------------------------------------

    private fun steerAt(target: Vec3, landing: Boolean) {
        val situation = ElytraSituation(
            position = player.position(),
            velocity = player.deltaMovement,
            target = target,
            cruiseY = target.y,
            groundY = groundBelow(),
            // A searched route already knows what is in the way, and under a roof the only thing
            // climbing at an obstacle achieves is hitting the ceiling instead of the wall.
            blockedAhead = !route.enclosed && isBlockedAhead(),
            hasFirework = !landing && NavFireworks.storedSlot() != null,
            boosted = fireworkCooldown > 0 || NavFireworks.ownRocketInFlight(),
            landing = landing,
            touchdown = touchdownCommitted,
        )

        val command = ElytraGuidance.steer(situation)
        requestRotation(Rotation(command.yaw, command.pitch))
        if (command.useFirework) launchFirework()
    }

    /**
     * Points the aircraft, and insists on a correction the flight hooks actually act on.
     *
     * Vanilla's gliding physics is driven from the rotation only while a target with a movement
     * correction other than [MovementCorrection.OFF] is active — with correction off the hooks bow
     * out and the wing follows the untouched camera, which is to say the flight does not happen.
     * Strict is the one that hides the rotation without also rewriting movement input.
     */
    private fun requestRotation(rotation: Rotation) {
        val target = rotations.toRotationTarget(rotation, considerInventory = false)
        RotationManager.setRotationTarget(
            target.withCorrection(MovementCorrection.STRICT),
            Priority.IMPORTANT_FOR_USAGE_1,
            owner,
        )
    }

    /**
     * Asks for thrust.
     *
     * A rocket to hand is lit now; one in the pack is fetched by [NavFireworks] over the next few
     * ticks, so the cooldown is short in that case — waiting the full boost interval for a rocket
     * that was never lit would let the wing sink while it did nothing.
     */
    private fun launchFirework() {
        if (NavFireworks.launch(owner)) {
            rocketsUsed++
            fireworkCooldown = FIREWORK_COOLDOWN
        } else {
            fireworkCooldown = FETCH_RETRY_TICKS
        }
    }

    /**
     * Is there anything solid along the heading?
     *
     * The route's altitude profile is built from the surface heights known when it was planned, and
     * a long flight outruns what was loaded then. This is the check that catches the mountain the
     * profile could not have known about, far enough ahead to climb over it: the look-ahead is
     * measured in seconds of flight rather than blocks, so it grows with speed exactly as stopping
     * distance does.
     */
    private fun isBlockedAhead(): Boolean {
        val velocity = player.deltaMovement
        val speed = hypot(velocity.x, velocity.z)
        // Too slow for the velocity to mean anything, so use where we are flying rather than where
        // the camera points — the two are not the same thing while the rotation is silent.
        val heading = if (speed > MIN_HEADING_SPEED) {
            velocity.normalize()
        } else {
            RotationManager.currentRotation?.directionVector ?: player.lookAngle
        }

        val start = player.eyePosition
        val reach = (speed * LOOKAHEAD_TICKS).coerceIn(MIN_LOOKAHEAD, MAX_LOOKAHEAD)
        val end = start.add(heading.scale(reach))

        return world.clip(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            .type != HitResult.Type.MISS
    }

    /** Ground level under the aircraft, falling back to the destination when the column is cold. */
    private fun groundBelow(): Double {
        val surface = NavWorld.surfaceY(player.blockX, player.blockZ)
        return if (surface == Int.MIN_VALUE) destination.y else surface.toDouble()
    }

    private fun horizontalDistanceTo(target: Vec3): Double =
        hypot(target.x - player.x, target.z - player.z)

    private fun yawToward(target: Vec3): Float =
        Math.toDegrees(atan2(player.x - target.x, target.z - player.z)).toFloat()

    /**
     * Attitude to cast the wing at: along the line to the first waypoint, never nose-down.
     *
     * A take-off that starts by pointing down puts the rocket into the floor, and one that always
     * points steeply up puts it into whatever is overhead.
     */
    private fun takeoffPitch(target: Vec3): Float {
        val horizontal = horizontalDistanceTo(target).coerceAtLeast(MIN_SLOPE_DISTANCE)
        val slope = Math.toDegrees(-atan2(target.y - player.y, horizontal)).toFloat()
        return slope.coerceIn(ElytraGuidance.CLIMB_PITCH, 0f)
    }

    private companion object {
        /**
         * How far a refreshed plan's destination may sit from the current one and still be the same
         * journey. A block or two of resolution as the goal's chunk arrives is expected; more than
         * that is a different flight and deserves a new executor.
         */
        const val DESTINATION_DRIFT = 8.0

        /** Roughly two seconds to get off the ground before the flight is written off. */
        const val TAKEOFF_LIMIT = 40

        /** Horizontal distance at which a waypoint counts as made. */
        const val WAYPOINT_REACH = 12.0

        /** The same, between walls: about one cell of the lattice the route was searched on. */
        const val ENCLOSED_WAYPOINT_REACH = 4.0

        /** Only skip a waypoint we are still near, or one bad heading would skip the whole route. */
        const val PASSED_MAX_DISTANCE = 48.0

        /** Blocks of ground covered per block of height lost on the approach: a shallow slope. */
        const val DESCENT_RATIO = 4.0

        /** Ticks of flight to look ahead for terrain. */
        const val LOOKAHEAD_TICKS = 40.0

        const val MIN_LOOKAHEAD = 24.0
        const val MAX_LOOKAHEAD = 96.0

        /** Below this the velocity direction is noise, so the nose is the better heading. */
        const val MIN_HEADING_SPEED = 0.05

        /** Guards the take-off slope when the first waypoint is directly overhead. */
        const val MIN_SLOPE_DISTANCE = 1.0

        /** Ticks before another rocket may be lit, in case the entity has not spawned in yet. */
        const val FIREWORK_COOLDOWN = 10

        /**
         * Ticks before asking again for a rocket that has to come out of the pack.
         *
         * A whole swap-use-swap chain, run through the inventory scheduler at its configured
         * timings, takes considerably longer than the three ticks this used to wait — so the flight
         * asked again while the first chain was still being carried out, and two or three rockets
         * went up for one request. A second is comfortably longer than the default chain and still
         * far shorter than a glide's patience.
         */
        const val FETCH_RETRY_TICKS = 20

        /** Ticks between attempts to recast a wing that dropped out mid-air. */
        const val RECAST_COOLDOWN = 5

        /** Closer than this to the destination, the rest is a walk rather than another take-off. */
        const val RELAUNCH_MIN_DISTANCE = 64.0

        /** Launches per route. Enough for a clipped hilltop, few enough to notice a bad spot. */
        const val MAX_RELAUNCHES = 3

        /** Blocks of closing on the destination that count as getting somewhere. */
        const val PROGRESS_EPSILON = 1.0

        /**
         * Five seconds of not getting closer.
         *
         * Long enough to cover a turn, a climb over a ridge, or the wait between rockets — all of
         * which legitimately stop the distance falling for a while — and short enough that a flight
         * that has genuinely lost the plot is put down rather than watched.
         */
        const val STAGNANT_LIMIT_TICKS = 100

        const val SLOT_RESET_TICKS = 4
    }
}
