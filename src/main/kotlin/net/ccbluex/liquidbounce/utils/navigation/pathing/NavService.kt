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

package net.ccbluex.liquidbounce.utils.navigation.pathing

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.CancelBlockBreakingEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.CRITICAL_MODIFICATION
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.ElytraExecutor
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.GroundExecutor
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavExecStatus
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavExecutor
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavFireworks
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavPath
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.NavGoal
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.GuidedGoal
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.NavCorridor
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.NavSectionGraph
import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.ElytraPlanner
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.ElytraRoute
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.EnclosedFlightPlanner
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.GroundPlanner
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavBlockSource
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.NavStandingSpot
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.asBlockSource
import net.ccbluex.liquidbounce.utils.navigation.pathing.route.NavRouter
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearch
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchLimits
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchResult
import net.ccbluex.liquidbounce.utils.navigation.pathing.search.NavSearchWorkspace
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/** What the navigation service is currently doing. */
enum class NavStatus {
    /** Nothing requested. */
    IDLE,

    /** A search is running off-thread. */
    CALCULATING,

    /** Walking a route. */
    FOLLOWING,

    /** The goal was reached. */
    ARRIVED,

    /** Something went wrong; see the chat or the debug overlay. */
    FAILED,

    /** No route to the goal exists from here. */
    UNREACHABLE,

    /** Someone more important asked to go somewhere else, and this request is over. */
    PREEMPTED,
}

/**
 * A consumer's handle on its navigation request.
 *
 * Held by whoever asked to go somewhere, so it can retarget or cancel without knowing whether it is
 * still the active request. A handle whose owner was preempted simply reports [isActive] false.
 */
class NavHandle internal constructor(
    val owner: ClientModule,
    internal val priority: Priority,
) {
    /** True while this handle is the one the service is acting on. */
    val isActive: Boolean get() = NavService.isActiveHandle(this)

    /** Current status, or [NavStatus.IDLE] if this handle was preempted. */
    val status: NavStatus get() = if (isActive) NavService.status else NavStatus.IDLE

    /** Points the same request at a new goal without restarting it. */
    fun retarget(goal: NavGoal) {
        if (isActive) NavService.retarget(goal)
    }

    /** Gives up this request. Harmless if it was already preempted. */
    fun cancel() {
        if (isActive) NavService.stop()
    }
}

/**
 * What a planning pass produced: a walking route, or a glide.
 *
 * The two are kept apart rather than flattened into one node space, which is the whole reason each
 * mode can be planned by something that understands it — a lattice of standable blocks for walking,
 * a line with an altitude profile for flying.
 */
private sealed interface NavPlan {

    /** A route through the block lattice. */
    class Ground(val result: NavSearchResult) : NavPlan

    /** A glide across open sky. */
    class Flight(val route: ElytraRoute) : NavPlan

    /**
     * Nothing to change: carry on with what is already being flown.
     *
     * For the refresh of a flight that could not be re-planned this time round — a roof came over
     * the line, or the terrain ahead is not known yet. The alternative is a lattice search from the
     * cockpit, which cannot produce anything usable from mid-air and costs a full timeout to say so.
     */
    object Unchanged : NavPlan
}

/**
 * The engine's front door: everything that wants to walk somewhere goes through here.
 *
 * The previous engine's `PathManager` was a singleton wired directly to one module — it refused to
 * build an executor unless that module was enabled and always used that module's rotation settings,
 * which is why AutoMine, autofarms and combat following could never use it. Here a request carries
 * its own owner, capabilities and rotation group, and requests are arbitrated by [Priority], so a
 * combat module can preempt a long-distance travel command and hand control back afterwards.
 *
 * All public methods are main-thread. Searching happens on [Dispatchers.Default] and the result is
 * handed back through `mc.execute`.
 */
object NavService : EventListener {

    @Volatile
    var status: NavStatus = NavStatus.IDLE
        private set

    /** The walking route currently being followed, exposed for rendering and diagnostics. */
    @Volatile
    var currentPath: NavPath? = null
        private set

    /** The glide currently being flown, if this leg is being flown rather than walked. */
    @Volatile
    var currentFlight: ElytraRoute? = null
        private set

    /** Rockets the current glide has lit, for the debug overlay. */
    val rocketsUsed: Int get() = (executor as? ElytraExecutor)?.rocketsUsed ?: 0

    /** How far along the current leg the executor has got, 0..1, for the route rendering. */
    val routeProgress: Float get() = executor?.progress ?: 0f

    /** The coarse route the last search was steered along, if it had one. */
    @Volatile
    var lastCorridor: NavCorridor? = null
        private set

    /** Destination [lastCorridor] was built for, so it is not reused for somewhere else. */
    private var corridorTarget: BlockPos? = null

    /** Where we are trying to get to. */
    @Volatile
    var currentGoal: NavGoal? = null
        private set

    /** Statistics from the most recent search, for the debug overlay. */
    @Volatile
    var lastSearch: NavSearchResult? = null
        private set

    private var activeHandle: NavHandle? = null

    /**
     * Whether we are currently holding a reference on [NavWorld].
     *
     * Tracked explicitly rather than inferred from [activeHandle]: the two are cleared at different
     * points, and inferring it leaked the reference every time [stop] ran, leaving the chunk cache
     * subscribed and updating forever.
     */
    private var worldAcquired = false

    private var caps: NavCaps = NavCaps.TRAVEL

    /**
     * Where a fresh capability snapshot comes from, when the owner offered one.
     *
     * A snapshot taken at the start of a journey is a statement about an inventory that the journey
     * itself changes: rockets get burned, blocks get placed, health gets lost. Re-reading before
     * each search is what stops the router planning a second take-off with the fuel of an hour ago.
     */
    private var capsProvider: (() -> NavCaps)? = null
    private var rotations: RotationsValueGroup? = null
    private var onArrive: (() -> Unit)? = null
    private var onFailed: ((NavStatus) -> Unit)? = null

    private var executor: NavExecutor? = null

    /** Where the current glide left the ground, so a flight that achieved nothing can be spotted. */
    private var flightStart: Vec3? = null

    /** Ticks left before flying is considered again, after one that went nowhere. */
    private var flightSuppression = 0

    /** Ticks since the flight plan was last rebuilt against newly arrived chunks. */
    private var flightRefreshTicks = 0

    /**
     * One search at a time, whatever else is going on.
     *
     * The workspace — arena, heap and edge buffer — is a single instance shared by every search and
     * is explicitly not thread-safe. Cancellation is cooperative and therefore late, so the only way
     * to keep two searches out of it at once is to give them one thread between them. The cost is
     * that an abandoned search delays its replacement by what is left of its 200 ms budget, and its
     * own cancellation check cuts that short.
     *
     * Declared before [searchScope], which is built from it as this object initialises.
     */
    private val searchDispatcher = Dispatchers.Default.limitedParallelism(1)

    private var searchScope = newScope()
    private var searchJob: Job? = null
    private val searching = AtomicBoolean(false)

    /**
     * Which search is the current one.
     *
     * Bumped whenever a search is started or abandoned. A running search compares it against the
     * value it captured and stops; a result that lands with a stale stamp is dropped rather than
     * applied. Both halves are needed, and neither used to exist: a coroutine that never suspends
     * ignores `cancel()` outright, so the "cancelled" search ran to completion, wrote through the
     * one shared workspace the new search was already using, and then handed its route for the old
     * goal to the executor for the new one.
     */
    private val searchGeneration = AtomicInteger(0)

    /** One workspace, reused by every search. This is what keeps replanning allocation-free. */
    private val workspace = NavSearchWorkspace()

    private var replanRequested = false
    private var replanCooldown = 0

    /** Consecutive replans that failed to bring us closer, before we call the goal unreachable. */
    private var stalledReplans = 0

    /** Replans spent waiting for chunks to stream in, which get a far more generous budget. */
    private var frontierWaits = 0
    private var waitingForChunks = false
    private var bestHeuristicSoFar = Double.POSITIVE_INFINITY

    /** Weighted-A* multiplier, taken from the requesting module's settings. */
    private var heuristicWeight = DEFAULT_HEURISTIC_WEIGHT

    /** Consecutive attempts deferred because the block cache was not filled in yet. */
    private var cacheWaits = 0

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Asks to walk to [goal].
     *
     * Preempts any request of lower priority; a request of lower priority than the active one is
     * rejected and comes back as a handle that is already inactive.
     */
    @Suppress("LongParameterList")
    fun request(
        owner: ClientModule,
        goal: NavGoal,
        caps: NavCaps = NavCaps.TRAVEL,
        rotations: RotationsValueGroup,
        priority: Priority = Priority.NORMAL,
        heuristicWeight: Double = DEFAULT_HEURISTIC_WEIGHT,
        capsProvider: (() -> NavCaps)? = null,
        onArrive: (() -> Unit)? = null,
        onFailed: ((NavStatus) -> Unit)? = null,
    ): NavHandle {
        val handle = NavHandle(owner, priority)

        val active = activeHandle
        if (active != null && active.priority.priority > priority.priority) return handle

        // The outgoing owner is told, rather than left holding a handle that silently reports
        // itself inactive for ever. Nothing here hands control back afterwards — a preempted
        // journey is over, and its owner is the one who gets to decide whether to ask again.
        if (active != null && active !== handle) {
            this.onFailed?.invoke(NavStatus.PREEMPTED)
        }

        acquireWorld()
        activeHandle = handle
        this.caps = caps
        this.capsProvider = capsProvider
        this.rotations = rotations
        this.onArrive = onArrive
        this.onFailed = onFailed
        this.heuristicWeight = heuristicWeight.coerceAtLeast(1.0)

        currentGoal = goal
        resetProgressTracking()
        startSearch(goal)
        return handle
    }

    /** The module the active request belongs to, if there is one. */
    val currentOwner: ClientModule? get() = activeHandle?.owner

    /** Retargets the active request. */
    fun retarget(goal: NavGoal) {
        currentGoal = goal
        resetProgressTracking()
        startSearch(goal)
    }

    /** Stops navigating and releases the world cache. */
    fun stop() {
        cancelSearch()
        searchScope.cancel()
        searchScope = newScope()

        releaseWorld()
        activeHandle = null
        executor = null
        currentPath = null
        currentFlight = null
        currentGoal = null
        lastCorridor = null
        corridorTarget = null
        flightStart = null
        onArrive = null
        onFailed = null
        capsProvider = null
        replanRequested = false
        waitingForChunks = false
        // A rocket asked for and not yet fetched outlives the flight that wanted it, and the module's
        // inventory handler is still listening: without this, `.nav stop` is followed seconds later
        // by a swap and a firework going off in the player's hand.
        NavFireworks.cancelSwap()
        status = NavStatus.IDLE
    }

    internal fun isActiveHandle(handle: NavHandle): Boolean = activeHandle === handle

    /** Block the player is looking at, for a bare `.nav goto`. */
    fun crosshairBlock(): BlockPos? = (mc.hitResult as? BlockHitResult)?.blockPos

    /**
     * Why the planner would or would not let the player stand at a position.
     *
     * A search that cannot reach its goal has to exhaust everything reachable before it can say so,
     * which looks identical whether the goal is walled off or simply not a place a body fits. This
     * answers that question directly instead of leaving it to be inferred from expansion counts.
     */
    fun describeOccupancy(pos: BlockPos, caps: NavCaps = this.caps): String =
        NavStandingSpot.describe(worldSource, pos.x, pos.y, pos.z, caps)

    /** True when the body described by [caps] could hold this position, as the planner sees it. */
    fun isOccupiable(pos: BlockPos, caps: NavCaps = this.caps): Boolean =
        NavStandingSpot.canOccupy(worldSource, pos.x, pos.y, pos.z, caps)

    /**
     * Moves a requested position to the nearest one a player can actually occupy.
     *
     * Pointing at the ground resolves upward onto it; pointing into mid-air resolves downward onto
     * whatever is holding weight. A goal the planner cannot satisfy is worse than a slightly moved
     * one, because the search can only reject it after exhausting everything reachable.
     */
    fun resolveStandingSpot(target: BlockPos, caps: NavCaps = this.caps): BlockPos =
        NavStandingSpot.resolve(worldSource, target, caps)

    /** Reads the shared cache directly; the one-chunk cursor is not worth it for occasional lookups. */
    private val worldSource = NavBlockSource { x, y, z -> NavWorld.packedAt(x, y, z) }

    /**
     * Why a journey to [target] would or would not be flown, in the order the engine asks.
     *
     * Flying is the one strategy whose refusal is invisible: the route falls back to walking and
     * everything downstream looks like an ordinary ground plan, so "it will not fly there" and "it
     * cannot plan there at all" arrive as the same silence. Every gate is reported rather than the
     * first failing one, because the interesting case is usually the second reason.
     */
    fun explainFlight(target: BlockPos, caps: NavCaps = this.caps): List<String> {
        val here = player.blockPosition()
        val distance = hypot((target.x - here.x).toDouble(), (target.z - here.z).toDouble())

        val lines = mutableListOf(
            "distance ${distance.toInt()} blocks, need ${NavRouter.MIN_FLIGHT_DISTANCE.toInt()}",
            "elytra ${yesNo(caps.allowElytra)}, rockets ${caps.fireworks} (need ${NavRouter.MIN_ROCKETS})",
        )
        if (flightSuppression > 0) {
            lines += "§csuppressed for ${flightSuppression} more ticks after a flight that went nowhere"
        }
        lines += "router says ${if (NavRouter.preferFlight(distance, caps)) "§afly" else "§cwalk"}" +
            "§7 (fly ${NavRouter.estimateFlightTicks(distance).toInt()} ticks," +
            " walk ${NavRouter.estimateWalkTicks(distance, caps).toInt()})"

        val reader = NavWorld.reader()
        val route = ElytraPlanner.planOpenSky(
            source = reader.asBlockSource(),
            from = here,
            to = target,
            surfaceAt = { x, z -> reader.surfaceY(x, z) },
            ceiling = NavWorld.buildCeiling,
        )

        lines += if (route != null) {
            val altitudes = route.waypoints.map { it.y.toInt() }
            val blind = ElytraPlanner.lastBlindFrom
            "§aopen sky: ${route.waypoints.size} waypoints, y ${altitudes.min()}..${altitudes.max()}" +
                if (blind >= 0) "§7, blind from waypoint $blind" else "§7, all over known ground"
        } else {
            "§copen sky refused: ${ElytraPlanner.lastRefusal}"
        }

        return lines
    }

    private fun yesNo(value: Boolean) = if (value) "§ayes§7" else "§cno§7"

    // -----------------------------------------------------------------------
    //  Searching
    // -----------------------------------------------------------------------

    private fun startSearch(goal: NavGoal) {
        cancelSearch()

        // Rebuilding the plan of a flight already under way must not interrupt it: the executor is
        // only ticked while FOLLOWING, and a search takes several ticks to come back — which would
        // leave the aircraft unattended once every refresh.
        if (!(player.isFallFlying && executor is ElytraExecutor)) {
            status = NavStatus.CALCULATING
        }

        if (deferUntilCacheIsReady()) return

        // Snapshot everything the search needs while we are still on the main thread. Capabilities
        // are re-read here rather than reused from the request: a journey outlives its inventory,
        // and a router still seeing the sixteen rockets that have since been burned plans another
        // take-off with nothing to light.
        val start = player.blockPosition().asLong()
        val snapshotCaps = refreshCaps()
        val weight = heuristicWeight
        val flightTarget = flightTargetFor(goal)
        val airborne = player.isFallFlying && executor is ElytraExecutor
        val generation = searchGeneration.get()

        searching.set(true)
        searchJob = searchScope.launch {
            try {
                val plan = runSearch(start, goal, snapshotCaps, weight, flightTarget, airborne, generation)
                // A superseded search must not be allowed to speak. mc.execute knows nothing about
                // cancellation, so without this stamp the answer to a question nobody is asking any
                // more arrives as the answer to the current one.
                mc.execute { if (generation == searchGeneration.get()) onSearchFinished(plan) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                mc.execute { if (generation == searchGeneration.get()) onSearchFailed(e) }
            } finally {
                searching.set(false)
            }
        }
    }

    /**
     * Puts the search off while the block cache is still filling. Returns true when it did.
     *
     * The cache fills on background workers after we subscribe, and searching before the player's
     * own surroundings are in it reads unloaded space in every direction and concludes we are
     * walled in — which looks exactly like a perfectly ordinary destination being rejected as
     * unreachable. After long enough, that is what we call it.
     */
    private fun deferUntilCacheIsReady(): Boolean {
        if (isCacheReadyAroundPlayer()) {
            cacheWaits = 0
            return false
        }

        cacheWaits++
        if (cacheWaits > MAX_CACHE_WAITS) {
            cacheWaits = 0
            finishUnreachable()
            return true
        }
        replanRequested = true
        replanCooldown = CACHE_WAIT_COOLDOWN
        return true
    }

    /**
     * Re-reads the owner's capabilities, or keeps the snapshot when it cannot.
     *
     * Main thread only, like everything the provider touches — it counts real items and measures
     * the real tool.
     */
    private fun refreshCaps(): NavCaps {
        val provider = capsProvider ?: return caps
        caps = provider()
        return caps
    }

    /**
     * Plans the next leg, on the search thread.
     *
     * A glide is tried first when the router asked for one, and falls through to walking when the
     * planner refuses — which it does whenever the sky above the line is not genuinely open, since
     * flying blind through enclosed space is flying into rock.
     */
    @Suppress("LongParameterList")
    private fun runSearch(
        start: Long,
        goal: NavGoal,
        caps: NavCaps,
        weight: Double,
        flightTarget: BlockPos?,
        airborne: Boolean,
        generation: Int,
    ): NavPlan {
        if (flightTarget != null) {
            planFlight(start, flightTarget, caps, generation)
                ?.takeIf { !it.isEmpty }
                ?.let { return NavPlan.Flight(it) }
        }

        // Nothing a lattice of standable blocks has to say applies from up here, and finding that
        // out costs a whole search timeout. Keep flying what we have; the ground gets its turn at
        // the far end, where there is ground.
        if (airborne) return NavPlan.Unchanged

        return NavPlan.Ground(runGroundSearch(start, goal, caps, weight, generation))
    }

    /** Has this search been superseded? Consulted by the search loops every few hundred nodes. */
    private fun isSuperseded(generation: Int): Boolean = generation != searchGeneration.get()

    /**
     * Routes a glide to the destination, or null when it cannot be flown.
     *
     * Open sky first, because when it applies it is both the cheaper answer and the better one: a
     * straight line with an altitude profile, drawn from a height map, costs a few hundred samples.
     * It refuses whenever there is a roof over the route, and only then is the three-dimensional
     * search worth its expansions.
     *
     * One reader serves every block read and every surface height, so the whole plan comes out of a
     * single chunk cursor rather than a hash lookup per sample.
     */
    private fun planFlight(start: Long, target: BlockPos, caps: NavCaps, generation: Int): ElytraRoute? {
        val reader = NavWorld.reader()
        val source = reader.asBlockSource()
        val from = BlockPos(BlockPos.getX(start), BlockPos.getY(start), BlockPos.getZ(start))

        // A destination beyond the render distance is a guess about its height — nobody typing
        // coordinates knows the ground level there, and nothing local can tell them. Once the
        // chunk has arrived the guess is replaced by the real surface, which is the difference
        // between landing on the hill and flying into it.
        val landing = if (NavWorld.isLoaded(target.x, target.z)) {
            NavStandingSpot.resolve(source, target, caps)
        } else {
            target
        }

        ElytraPlanner.planOpenSky(
            source = source,
            from = from,
            to = landing,
            surfaceAt = { x, z -> reader.surfaceY(x, z) },
            ceiling = NavWorld.buildCeiling,
        )?.let { return it }

        if (!caps.allowEnclosedFlight) return null

        return EnclosedFlightPlanner.plan(
            source = source,
            from = from,
            to = landing,
            workspace = workspace,
            timeoutNanos = SEARCH_TIMEOUT_NANOS,
            cancelled = { isSuperseded(generation) },
        )
    }

    /**
     * Looks at the terrain at the scale of rooms before searching it block by block.
     *
     * Only for journeys long enough to be worth it. Over a short distance the detailed search is
     * already looking at the whole problem, and building the coarse map would be pure overhead; over
     * a long one, the coarse map is the difference between routing around a mountain and expanding
     * every block on its near face first.
     *
     * A corridor is only ever guidance. When this returns nothing — no standing room at either end,
     * pockets it cannot join, budget exhausted — the search runs exactly as it did before.
     */
    private fun guideFor(goal: NavGoal, start: Long, caps: NavCaps): NavGoal {
        if (!caps.allowHierarchy) return goal

        val target = goal.targetPosition() ?: return goal
        val from = BlockPos(BlockPos.getX(start), BlockPos.getY(start), BlockPos.getZ(start))
        if (from.distSqr(target) < MIN_GUIDED_DISTANCE * MIN_GUIDED_DISTANCE) return goal

        // A corridor is advice about the shape of the terrain, and advice does not go stale from
        // age — only from being somewhere else. Rebuilding it on every replan is what makes a route
        // pause: replans come thick and fast while digging or building, and every block placed
        // invalidates the pockets around it, so each one re-floods sections that had not changed in
        // any way that mattered.
        val reusable = lastCorridor
        if (reusable != null &&
            corridorTarget == target &&
            reusable.detourFrom(from.x, from.y, from.z) < CORRIDOR_REUSE_RADIUS
        ) {
            return GuidedGoal(goal, reusable)
        }

        val corridor = NavSectionGraph.route(NavWorld.reader().asBlockSource(), from, target, caps) ?: return goal
        lastCorridor = corridor
        corridorTarget = target
        return GuidedGoal(goal, corridor)
    }

    private fun runGroundSearch(
        start: Long,
        goal: NavGoal,
        caps: NavCaps,
        weight: Double,
        generation: Int,
    ): NavSearchResult {
        val guided = guideFor(goal, start, caps)

        val result = searchWith(start, guided, caps, weight, generation)
        if (!caps.allowPlace) return result

        // Capabilities only decide whether placing is allowed at all, not how much of it a whole
        // route adds up to. Setting off across a two-hundred-block bridge with a stack of sixty-four
        // strands the player halfway over; planning again without placing at least produces a route
        // that can be finished.
        val required = NavMove.placementsIn(result.moves)
        if (required <= caps.placeableBlocks) return result

        logger.debug("Nav: route needs $required blocks but only ${caps.placeableBlocks} are carried; replanning")
        return searchWith(start, guided, caps.copy(allowPlace = false, placeableBlocks = 0), weight, generation)
    }

    private fun searchWith(
        start: Long,
        goal: NavGoal,
        caps: NavCaps,
        weight: Double,
        generation: Int,
    ): NavSearchResult {
        val planner = GroundPlanner(NavWorld.reader().asBlockSource(), caps)

        return NavSearch.search(
            start = start,
            goal = goal,
            neighbors = planner,
            workspace = workspace,
            limits = NavSearchLimits(
                maxIterations = MAX_ITERATIONS,
                heuristicWeight = weight,
                // Goals estimate in walking time; anything faster has to scale them down or the
                // estimate becomes an overestimate and the search stops being optimal.
                heuristicScale = caps.heuristicScale,
                timeoutNanos = SEARCH_TIMEOUT_NANOS,
            ),
            isFrontier = ::isOnLoadedFrontier,
            cancelled = { isSuperseded(generation) },
        )
    }

    /**
     * Is the cache filled in far enough around the player to plan from?
     *
     * Checks the player's own chunk and its four neighbours. That is enough to tell a cache that is
     * still filling from one that genuinely ends at the edge of the loaded world.
     */
    private fun isCacheReadyAroundPlayer(): Boolean {
        val position = player.blockPosition()
        val x = position.x
        val z = position.z
        return NavWorld.isLoaded(x, z) &&
            NavWorld.isLoaded(x + CHUNK, z) &&
            NavWorld.isLoaded(x - CHUNK, z) &&
            NavWorld.isLoaded(x, z + CHUNK) &&
            NavWorld.isLoaded(x, z - CHUNK)
    }

    /**
     * A node sits on the frontier when a neighbouring column has not been received yet.
     *
     * Ending a best-effort segment here is what makes travel past the render distance work: we walk
     * to the edge of what we know, the next chunks arrive on the way, and the replan sees further.
     */
    private fun isOnLoadedFrontier(node: Long): Boolean {
        val x = BlockPos.getX(node)
        val z = BlockPos.getZ(node)
        return !NavWorld.isLoaded(x + 1, z) ||
            !NavWorld.isLoaded(x - 1, z) ||
            !NavWorld.isLoaded(x, z + 1) ||
            !NavWorld.isLoaded(x, z - 1)
    }

    /**
     * Where a glide would be aimed for this goal, or null when this leg is one to walk.
     *
     * Called on the main thread, because it reads the player and the capability snapshot.
     *
     * Already being airborne overrides everything the router would otherwise say. A replan that
     * came in mid-flight has to produce another flight: the lattice search only knows places a body
     * can stand, so asking it from the sky comes back as "unreachable" and strands us gliding with
     * no route at all.
     */
    private fun flightTargetFor(goal: NavGoal): BlockPos? {
        val target = goal.targetPosition() ?: return null
        if (player.isFallFlying) return target

        if (flightSuppression > 0 || !caps.allowElytra) return null

        val here = player.blockPosition()
        val distance = hypot((target.x - here.x).toDouble(), (target.z - here.z).toDouble())
        return if (NavRouter.preferFlight(distance, caps)) target else null
    }

    private fun onSearchFinished(plan: NavPlan) {
        if (status == NavStatus.IDLE) return

        when (plan) {
            is NavPlan.Ground -> onGroundPlan(plan.result)
            is NavPlan.Flight -> onFlightPlan(plan.route)
            // The flight we are on stands. Only meaningful while there is still a flight to stand:
            // a glide that touched down while this search was in the air has already reported
            // ARRIVED and dropped its executor, and setting FOLLOWING over the top of that leaves
            // the service permanently claiming to be walking a route that does not exist.
            NavPlan.Unchanged -> if (executor is ElytraExecutor) status = NavStatus.FOLLOWING
        }
    }

    private fun onFlightPlan(route: ElytraRoute) {
        // A flight already under way takes the new plan rather than being replaced by it. Building a
        // fresh executor in mid-air would throw away the phase, the rocket count and the touchdown
        // decision — and would start the whole flight again from a take-off it is long past.
        val flying = executor as? ElytraExecutor
        if (flying != null && flying.refresh(route)) {
            currentFlight = route
            status = NavStatus.FOLLOWING
            return
        }

        // The refresh was declined — an approach that has already committed to putting down will
        // not have the ground swapped under it. That is a reason to keep flying what we have, never
        // a reason to build a second executor around a player who is already gliding: a fresh one
        // starts at waypoint one, at cruise altitude, with no rockets counted and no touchdown
        // latched, so a flight on short finals climbs back to cruise and comes round again.
        if (flying != null && player.isFallFlying) {
            status = NavStatus.FOLLOWING
            return
        }

        val owner = activeHandle?.owner
        val rotationGroup = rotations
        if (owner == null || rotationGroup == null) {
            status = NavStatus.IDLE
            return
        }

        currentPath = null
        currentFlight = route
        flightStart = player.position()
        executor = ElytraExecutor(route, owner, rotationGroup)
        status = NavStatus.FOLLOWING
    }

    private fun onGroundPlan(result: NavSearchResult) {
        // A walking route cannot begin in mid-air. If the planner declined to route a glide from up
        // here — a mountain came into view, or the goal moved somewhere unflyable — the glide we are
        // already on is the only thing that can put us back on the ground, so let it finish its
        // landing and plan again from there. Swapping in a ground route now would hand the executor
        // a route starting at a block with no floor, which comes back as "unreachable" while the
        // player is still in the sky with nothing flying them.
        if (player.isFallFlying && executor is ElytraExecutor) {
            status = NavStatus.FOLLOWING
            return
        }

        currentFlight = null
        lastSearch = result
        val goal = currentGoal ?: return

        val path = NavPath.of(result)
        if (path.isEmpty) {
            finishUnreachable()
            return
        }

        if (!path.reachedGoal && !trackPartialProgress(path, goal)) return

        // The request can be cancelled or preempted while its search is in flight, in which case
        // there is nobody left to walk the route we just computed.
        val owner = activeHandle?.owner
        val rotationGroup = rotations
        if (owner == null || rotationGroup == null) {
            status = NavStatus.IDLE
            return
        }

        currentPath = path
        executor = GroundExecutor(path, caps, owner, rotationGroup)
        status = NavStatus.FOLLOWING
    }

    /**
     * Decides whether a partial route represents progress worth walking.
     *
     * Returns false when the request has been given up on, in which case the caller stops.
     */
    private fun trackPartialProgress(path: NavPath, goal: NavGoal): Boolean {
        val end = path.size - 1
        val heuristic = goal.heuristic(path.xAt(end), path.yAt(end), path.zAt(end))
        waitingForChunks = isOnLoadedFrontier(path.positions[end])

        when {
            heuristic < bestHeuristicSoFar - PROGRESS_EPSILON -> {
                bestHeuristicSoFar = heuristic
                stalledReplans = 0
                frontierWaits = 0
            }

            waitingForChunks -> {
                // Parked at the edge of the loaded world. Not stuck — just waiting for the server.
                frontierWaits++
                if (frontierWaits >= MAX_FRONTIER_WAITS) {
                    finishUnreachable()
                    return false
                }
            }

            else -> {
                stalledReplans++
                if (stalledReplans >= MAX_STALLED_REPLANS) {
                    finishUnreachable()
                    return false
                }
            }
        }
        return true
    }

    /**
     * A search threw. Ends the request the same way every other failure does.
     *
     * It used to end it only halfway: the status went to FAILED and the owner was told, but the
     * chunk-cache reference was never given back and the handle stayed active — so the subscription
     * was held for the rest of the session and every later request of lower priority was refused by
     * a journey that had already failed. `.nav stop` was the only way out.
     */
    private fun onSearchFailed(error: Throwable) {
        logger.error("Navigation search failed", error)
        currentPath = null
        currentFlight = null
        executor = null
        status = NavStatus.FAILED
        onFailed?.invoke(NavStatus.FAILED)
        NavFireworks.cancelSwap()
        releaseWorld()
        activeHandle = null
        capsProvider = null
    }

    private fun finishUnreachable() {
        // A glide cannot be abandoned in the air: the ground planner has no floor to plan from up
        // here, so dropping the executor leaves the player gliding with nothing flying them. Tell
        // the flight to put down instead, and let the landing decide what happens next.
        val flying = executor as? ElytraExecutor
        if (flying != null && player.isFallFlying) {
            flying.abort()
            status = NavStatus.FOLLOWING
            return
        }

        currentPath = null
        currentFlight = null
        executor = null
        status = NavStatus.UNREACHABLE
        onFailed?.invoke(NavStatus.UNREACHABLE)
        NavFireworks.cancelSwap()
        releaseWorld()
        activeHandle = null
        capsProvider = null
    }

    /**
     * Records that a glide is over, and stops trying to fly if it accomplished nothing.
     *
     * A take-off that fails, or a flight that drops back down where it started, would otherwise be
     * planned again the moment we land — and again after that, burning a rocket each time. Standing
     * the strategy down for a while lets the ground engine cover the distance instead.
     */
    private fun noteFlightEnded() {
        val start = flightStart ?: return
        flightStart = null
        currentFlight = null

        if (player.position().distanceTo(start) < MIN_USEFUL_FLIGHT) {
            flightSuppression = FLIGHT_SUPPRESSION_TICKS
        }
    }

    private fun onArrivedAtRouteEnd() {
        noteFlightEnded()

        val goal = currentGoal
        val position = player.blockPosition()

        if (goal == null || goal.isGoal(position)) {
            currentPath = null
            executor = null
            status = NavStatus.ARRIVED
            onArrive?.invoke()
            releaseWorld()
            activeHandle = null
            return
        }

        // End of a best-effort segment. Plan the next one from here — this is what gives the engine
        // unbounded range. Drop the finished executor and leave FOLLOWING, or the movement handler
        // would re-run the completed route every tick until the replan lands.
        executor = null
        status = NavStatus.CALCULATING
        stalledReplans = 0
        replanRequested = true
    }

    /**
     * Rebuilds the flight plan every so often while airborne.
     *
     * This is what makes flying anywhere beyond the render distance mean anything. The altitude
     * profile is drawn from a height map that ends where the loaded world does, so at the moment of
     * take-off the whole far half of a long flight is planned over ground nobody has seen — and
     * without this, it is still being flown on that plan when the ground arrives. Chunks stream in
     * ahead of the aircraft faster than it covers them, so a rebuild every couple of seconds means
     * the profile always knows the terrain the guidance is about to meet.
     *
     * Cheap: a few hundred height-map reads, on the search thread, for a plan that is otherwise a
     * straight line. The executor keeps flying the old one until the new one lands.
     */
    private fun askForFlightRefresh() {
        if (status != NavStatus.FOLLOWING || currentFlight == null || !player.isFallFlying) {
            flightRefreshTicks = 0
            return
        }

        flightRefreshTicks++
        if (flightRefreshTicks < FLIGHT_REFRESH_TICKS) return

        flightRefreshTicks = 0
        replanRequested = true
    }

    private fun resetProgressTracking() {
        bestHeuristicSoFar = Double.POSITIVE_INFINITY
        stalledReplans = 0
        frontierWaits = 0
        waitingForChunks = false
    }

    /**
     * Abandons whatever search is running.
     *
     * The generation bump is the part that does the work: a search body never suspends, so
     * `Job.cancel()` alone does not stop it — it runs to completion, writing through the workspace
     * the next search is already using. Bumping makes the loop's own cancellation check fire within
     * a few hundred expansions and makes anything it still returns be discarded.
     */
    private fun cancelSearch() {
        searchGeneration.incrementAndGet()
        searchJob?.cancel()
        searchJob = null
        searching.set(false)
    }

    private fun acquireWorld() {
        if (worldAcquired) return
        NavWorld.acquire()
        worldAcquired = true
    }

    private fun releaseWorld() {
        if (!worldAcquired) return
        NavWorld.release()
        worldAcquired = false
    }

    private fun newScope() =
        CoroutineScope(searchDispatcher + SupervisorJob() + CoroutineName("NavService"))

    // -----------------------------------------------------------------------
    //  Event handlers
    // -----------------------------------------------------------------------

    @Suppress("unused")
    private val replanHandler = tickHandler {
        if (flightSuppression > 0) flightSuppression--
        if (status == NavStatus.IDLE) return@tickHandler

        askForFlightRefresh()

        if (replanCooldown > 0) {
            replanCooldown--
            return@tickHandler
        }
        if (!replanRequested || searching.get()) return@tickHandler

        replanRequested = false
        val goal = currentGoal ?: return@tickHandler
        replanCooldown = if (waitingForChunks) FRONTIER_REPLAN_COOLDOWN else REPLAN_COOLDOWN
        startSearch(goal)
    }

    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent>(priority = CRITICAL_MODIFICATION) { event ->
        if (status != NavStatus.FOLLOWING) return@handler
        val active = executor ?: return@handler

        when (active.tick(event)) {
            NavExecStatus.RUNNING -> Unit
            NavExecStatus.FINISHED -> onArrivedAtRouteEnd()
            NavExecStatus.NEEDS_REPLAN -> {
                // A glide asking for a new plan has already come back down, and it did so without
                // getting anywhere — otherwise it would have finished. Count it as a flight that
                // failed, or the next plan proposes the very same take-off.
                noteFlightEnded()
                replanRequested = true
            }
        }
    }

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent>(priority = CRITICAL_MODIFICATION) { event ->
        if (status != NavStatus.FOLLOWING) return@handler
        executor?.onSprint(event)
    }

    /**
     * Keeps vanilla from tearing down the dig the route is in the middle of.
     *
     * `Minecraft.handleKeybinds` ends any block-breaking the attack key is not holding up, once per
     * tick and before the movement input we are driven from — so a dig driven from here is aborted
     * between every two of its own swings and progress never accumulates. Anything that takes more
     * than one swing simply never breaks, the route runs to its dig deadline, replans, and proposes
     * the identical dig again. Every other module in this client that mines without the mouse
     * cancels this event for the same reason.
     */
    @Suppress("unused")
    private val cancelBreakingHandler = handler<CancelBlockBreakingEvent> { event ->
        if (status != NavStatus.FOLLOWING) return@handler
        if ((executor as? GroundExecutor)?.isMining == true) event.cancelEvent()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        stop()
    }

    // -----------------------------------------------------------------------
    //  Tuning
    // -----------------------------------------------------------------------

    /** Slightly greedy by default: much faster searches for barely longer routes. */
    const val DEFAULT_HEURISTIC_WEIGHT = 1.3

    private const val MAX_ITERATIONS = 50_000

    /** Keep a search well under a tick's worth of wall clock even though it runs off-thread. */
    private const val SEARCH_TIMEOUT_NANOS = 200_000_000L

    private const val REPLAN_COOLDOWN = 5
    private const val FRONTIER_REPLAN_COOLDOWN = 20

    /** Replans that brought us no closer before the goal is declared unreachable. */
    private const val MAX_STALLED_REPLANS = 5

    /** Far more generous, because waiting for chunks is not failing. */
    private const val MAX_FRONTIER_WAITS = 60

    /** Ticks of improvement that count as real progress. */
    private const val PROGRESS_EPSILON = 0.5

    /** Ticks between checks while the block cache is still filling. */
    private const val CACHE_WAIT_COOLDOWN = 2

    /** Roughly five seconds of waiting before accepting that the cache is not going to fill. */
    private const val MAX_CACHE_WAITS = 50

    /**
     * Shortest journey worth building a coarse map for.
     *
     * Below this the detailed search already has the whole problem in view, and the map would be
     * overhead spent to describe terrain that is about to be searched exhaustively anyway.
     */
    private const val MIN_GUIDED_DISTANCE = 96.0

    /**
     * How far from its corridor a route may stray before the coarse map is worth building again.
     *
     * Generous. Wandering a few dozen blocks off — round a building, down into a ravine — does not
     * change which side of the mountain the destination is on, and that is all the corridor is
     * there to say.
     */
    private const val CORRIDOR_REUSE_RADIUS = 64.0

    /** Blocks a glide has to cover to have been worth the rockets it burned. */
    private const val MIN_USEFUL_FLIGHT = 48.0

    /** Half a minute on foot after a flight that went nowhere, before trying to fly again. */
    private const val FLIGHT_SUPPRESSION_TICKS = 600

    /**
     * Ticks between rebuilds of a flight plan in the air.
     *
     * A second and a half. A boosted glide covers something like a hundred blocks in that time,
     * comfortably less than the render distance, so the profile is never asked about terrain that
     * arrived after it was drawn.
     */
    private const val FLIGHT_REFRESH_TICKS = 30

    private const val CHUNK = 16
}
