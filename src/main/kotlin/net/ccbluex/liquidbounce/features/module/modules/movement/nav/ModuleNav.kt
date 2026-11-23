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

package net.ccbluex.liquidbounce.features.module.modules.movement.nav

import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavDebug
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavService
import net.ccbluex.liquidbounce.utils.navigation.pathing.cost.NavCaps
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavBridgeStyle
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavFireworks
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavHotbar
import net.ccbluex.liquidbounce.utils.navigation.pathing.route.NavMount
import net.ccbluex.liquidbounce.utils.navigation.pathing.route.NavMountSteering
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.minecraft.world.level.block.Blocks

/**
 * Navigation module: the in-game face of the `pathing` engine.
 *
 * Holds the settings a route is planned under and renders whatever is currently being walked. The
 * engine itself lives in [NavService] and does not depend on this module — other consumers
 * (AutoMine, autofarms, combat following) pass their own capabilities and rotation settings, which
 * is the mistake the previous engine made and could not undo.
 */
object ModuleNav : ClientModule("Nav", ModuleCategories.MOVEMENT) {

    private val sprint by boolean("Sprint", true)
    private val allowParkour by boolean("Parkour", true)
    /**
     * Blocks of gap a jump may cross — so the landing is one further than this.
     *
     * Three is the vanilla ceiling: a running sprint jump lands four blocks away. Offering four here
     * planned five-block jumps no unbuffed player can make, which the executor's own simulation then
     * refused for as long as the move was allowed to take.
     */
    private val maxParkourGap by int("MaxParkourGap", 3, 1..3, "blocks")

    private val allowBreak by boolean("AllowBreak", false)
    private val allowPlace by boolean("AllowPlace", false)

    private val allowFallDamage by boolean("AllowFallDamage", false)
    private val maxFall by int("MaxFall", 3, 1..20, "blocks")

    private val allowSwim by boolean("Swim", true)

    /**
     * Plan around whatever the player is already riding.
     *
     * Only affects a mount they are on: walking over to find one is the router's job and does not
     * exist yet.
     */
    private val useMounts by boolean("UseMounts", true)

    /**
     * Fly long stretches on an elytra when one is worn and rockets are carried.
     *
     * Only long ones: the router will not spend a take-off and two rockets to save a few seconds
     * over ground it could simply walk.
     */
    private val useElytra by boolean("UseElytra", true)

    /**
     * Fly under a roof as well as over open ground.
     *
     * A different proposition from an open-sky flight, and worth its own switch: over terrain the
     * route can always climb away from trouble, while in the Nether or a cave it is threading a
     * searched course between walls.
     */
    private val flyEnclosed by boolean("FlyEnclosed", true)

    /**
     * Timings for fetching a rocket out of the pack.
     *
     * Rockets usually live in the inventory rather than on the bar, so a flight that could only see
     * the bar would simply never light one. Fetching is therefore always available and only its
     * pacing is configurable — a switch here would have to be mirrored into the flight as well, and
     * a flight that believes it has fuel it cannot reach plans climbs it cannot make.
     */
    private val fireworkFetch = tree(PlayerInventoryConstraints())

    /**
     * Steer a ridden animal without moving the camera.
     *
     * Vanilla takes a mount's direction from the rider's real head yaw, so the alternative is
     * dragging the player's view around for the whole journey. Off falls back to exactly that.
     */
    private val silentMountSteering by boolean("SilentMountSteering", true).onChanged {
        NavMountSteering.enabled = it && running
    }

    /**
     * Look at the terrain at the scale of rooms before searching it block by block.
     *
     * What keeps a long route from exploring the whole near face of a mountain before it considers
     * going around. Off makes every search work the way it did before the coarse map existed, which
     * is the comparison worth having when a route looks wrong.
     */
    private val hierarchy by boolean("Hierarchy", true)

    /**
     * How a gap gets bridged.
     *
     * Sneak backs out over the edge and builds against the face behind it: slow, and as close to
     * unfailable as this gets. GodBridge holds the forty-five degree stance and keeps sprinting,
     * which is far quicker and depends on angles to a tenth of a degree.
     */
    private val bridgeStyle by enumChoice("Bridge", NavBridgeStyle.SNEAK)

    /** How much detour is worth taking to avoid one hazardous block, in ticks. */
    private val hazardAversion by int("HazardAversion", 60, 0..400, "ticks")

    /**
     * How much detour is worth taking to leave one block standing, in ticks.
     *
     * Digging is priced on the clock, and a sharp tool goes through soft ground faster than a
     * footstep — so without this a route mines the floor instead of stepping down off it. Zero
     * gives the pure stopwatch; high makes digging a last resort.
     */
    private val digAversion by int("DigAversion", 30, 0..400, "ticks")

    /**
     * How much detour is worth taking to keep one block in the pocket, in ticks.
     *
     * The mirror of [digAversion]. Four ticks of swing is all the clock sees in a placement, so
     * without this a route paves every one-block dip it meets instead of stepping through it.
     */
    private val placeAversion by int("PlaceAversion", 30, 0..400, "ticks")

    /**
     * Weighted-A* multiplier. Higher dives at the goal with far fewer expansions, at the cost of a
     * route up to this factor longer. Anything above 1 is a deliberate trade.
     */
    private val greediness by float("Greediness", 1.3f, 1.0f..3.0f)

    private val renderPath by boolean("RenderPath", true)

    /**
     * Narrates what the executor is waiting for, in chat.
     *
     * For the manoeuvres whose failure looks identical from outside whatever the cause — a pillar
     * that will not commit, a hole the body never drops into. One line per change of answer, so it
     * is a commentary rather than a log.
     */
    private val trace by boolean("Trace", false).onChanged { NavDebug.trace = it }

    val rotations = tree(RotationsValueGroup(this))

    /**
     * Snapshots the current settings and inventory into capabilities the planner can use off-thread.
     *
     * Must be called on the main thread — it counts real items and measures the real tool.
     */
    fun buildCaps(): NavCaps {
        // Riding changes almost everything: speed, how much height a step clears, and whether
        // touching the world is even possible. Read once here rather than anywhere near the search.
        val mount = if (useMounts) NavMount.of(player.vehicle) else null
        val riding = mount != null

        return NavCaps(
            canSprint = sprint,
            allowParkour = allowParkour && !riding,
            maxParkourGap = maxParkourGap,
            allowBreak = allowBreak && !riding,
            allowPlace = allowPlace && !riding,
            placeableBlocks = if (allowPlace && !riding) NavHotbar.buildingBlockCount() else 0,
            breakTicksPerHardness = NavHotbar.breakTicksPerHardness(Blocks.STONE.defaultBlockState()),
            allowFallDamage = allowFallDamage,
            maxFallBlocks = maxFall,
            health = player.health.toDouble(),
            allowSwim = allowSwim,
            // Flying is off the table while riding: the animal is not coming along, and abandoning
            // it mid-journey is not something the router gets to decide.
            allowElytra = useElytra && !riding && NavHotbar.wearingElytra(),
            fireworks = if (useElytra && !riding) NavFireworks.count() else 0,
            allowEnclosedFlight = flyEnclosed,
            allowHierarchy = hierarchy,
            hazardAversion = hazardAversion.toDouble(),
            digAversion = digAversion.toDouble(),
            placeAversion = placeAversion.toDouble(),
            bridgeStyle = bridgeStyle,
            travelTicksPerBlock = mount?.ticksPerBlock,
            maxStepUp = mount?.maxStepUp ?: 1,
            bodyWidth = mount?.bodyWidth ?: NavCaps.PLAYER_WIDTH,
            bodyHeight = mount?.bodyHeight ?: NavCaps.PLAYER_HEIGHT,
        )
    }

    /** What the player is currently riding, if the module is set to make use of it. */
    fun currentMount(): NavMount? = if (useMounts) NavMount.of(player.vehicle) else null

    /** Weighted-A* multiplier for requests made through this module. */
    fun heuristicWeight(): Double = greediness.toDouble()

    /** Whether this module is currently holding the navigation cache open. */
    private var holdingWorld = false

    /**
     * Driven from the module's own lifecycle rather than from [ModuleToggleEvent].
     *
     * The event is dispatched from inside the enabled value's change listener, *before* the new
     * value is written — so while it is being handled `enabled` still reads false, `running` is
     * therefore false, and the event system skips this module's own handlers. The disable half
     * worked (there `enabled` is still true) and the enable half never ran once: the cache was
     * never held warm, silent mount steering never engaged, and Trace never turned on.
     */
    override fun onEnabled() {
        NavMountSteering.enabled = silentMountSteering
        NavDebug.trace = trace

        // Keep the block cache warm for as long as the module is on. It fills asynchronously after
        // subscribing, and a route planned against a half-filled cache sees unloaded space
        // everywhere and concludes the player is walled in.
        if (!holdingWorld) {
            NavWorld.acquire()
            holdingWorld = true
        }
    }

    override fun onDisabled() {
        NavMountSteering.enabled = false
        NavDebug.trace = false

        // Turning the module off has to abandon the route, or the player keeps walking with no way
        // to stop it — but only our own route. The engine is a shared service, and another module's
        // journey is not this switch's to end.
        if (NavService.currentOwner === this) NavService.stop()

        if (holdingWorld) {
            NavWorld.release()
            holdingWorld = false
        }
    }

    /**
     * Brings a rocket from the pack into the hand when the flight asked for one and had none there.
     *
     * Lives on the module rather than in the executor because it is an inventory action with its own
     * timing constraints, and those are settings — the flight itself only ever asks for thrust.
     */
    @Suppress("unused")
    private val fireworkFetchHandler = handler<ScheduleInventoryActionEvent> { event ->
        NavFireworks.scheduleSwap(event, fireworkFetch, this)
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!renderPath) return@handler

        val path = NavService.currentPath
        val flight = NavService.currentFlight
        if (path == null && flight == null) return@handler

        renderEnvironmentForWorld(event.matrixStack) {
            path?.let { NavPathRenderer.render(this, it, NavService.routeProgress) }
            flight?.let { NavPathRenderer.renderFlight(this, it) }
        }
    }
}
