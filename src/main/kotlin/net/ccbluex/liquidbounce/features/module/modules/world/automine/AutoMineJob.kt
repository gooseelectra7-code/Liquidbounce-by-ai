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

package net.ccbluex.liquidbounce.features.module.modules.world.automine

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.world.packetmine.ModulePacketMine
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlockRotation
import net.ccbluex.liquidbounce.utils.block.MiningClaim
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doBreak
import net.ccbluex.liquidbounce.utils.block.isNotBreakable
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavHandle
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavService
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavStatus
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavHotbar
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalAdjacentTo
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.ccbluex.liquidbounce.utils.raytracing.rayTraceCollidingBlocks
import net.ccbluex.liquidbounce.utils.raytracing.raytraceBlock
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.sqrt

/** How the job's attempt on one block ended, or that it has not yet. */
enum class MineOutcome {
    /** Nothing to do. */
    IDLE,

    /** Still walking to it or still breaking it. */
    RUNNING,

    /** The block is gone. */
    MINED,

    /** The route could not get us there, or the block would not break. Try another target. */
    GAVE_UP,
}

/**
 * Gets one block, from wherever the player happens to be standing.
 *
 * Every mode this module has ends up here: a mode decides *what* is worth breaking and this decides
 * nothing at all except how to go and break it. Keeping that split is what stops each new mode from
 * growing its own half-correct copy of walking, aiming and swinging.
 *
 * ## The shape of it
 *
 * ```
 * IDLE ──target──▶ APPROACH ──in reach──▶ MINE ──block gone──▶ MINED
 *                     │                    │
 *                     └── unreachable ─────┴── deadline ──▶ GAVE_UP
 * ```
 *
 * Walking is [NavService]'s job, and it is asked for a [GoalAdjacentTo] — the goal written for this
 * module, which is satisfied wherever the eyes can reach the block rather than at the block itself.
 *
 * ## Legitimacy
 *
 * The aim is requested through [RotationManager] and the swing waits for
 * [RotationManager.serverRotation] to confirm the server sees us looking at the block. Nothing is
 * clicked in the same tick the rotation is asked for: that is how a placement or a break gets
 * resolved against the *previous* angle. Breaking itself is vanilla's own
 * `continueDestroyBlock`, which is what a held mouse button does.
 */
@Suppress("TooManyFunctions")
class AutoMineJob(
    private val owner: ClientModule,
    private val rotations: RotationsValueGroup,
) {

    private enum class Phase { IDLE, APPROACH, MINE }

    private var phase = Phase.IDLE

    /** The block being worked on. */
    var target: BlockPos? = null
        private set

    /**
     * The block the last attempt was about, kept past the end of it.
     *
     * [tick] reports how an attempt ended after it has already let go of the target, so this is what
     * the mode is told about — otherwise every "that one is mined" and "that one is hopeless" would
     * arrive with nothing attached.
     */
    var lastTarget: BlockPos? = null
        private set

    private var handle: NavHandle? = null
    private var phaseTicks = 0

    /** Ticks spent standing at a spot the route called good while still unable to see the block. */
    private var arrivedTicks = 0

    /** What the route last reported, from its own callbacks. Null while it is still working. */
    private var navResult: NavStatus? = null

    /** Ticks to wait before asking for the engine again after somebody else took it. */
    private var navRetry = 0

    /** Swings this attempt has actually been allowed to send. Zero means nothing ever got through. */
    private var swings = 0

    /** Ticks of walking this target is worth, set from how far away it was when chosen. */
    private var approachDeadline = APPROACH_CAP

    /**
     * The block being broken right now, which is not always the block we are after.
     *
     * Ore is usually behind something. With through-wall aiming refused - and it is refused by
     * default, because a player cannot mine what they cannot see - arriving next to a buried ore left
     * nothing to do: the route got us there, the block was in range, and no point of it was in view.
     * That was reported as the miner walking up to ore and giving up on it, over and over.
     *
     * So when the goal cannot be seen, the first solid thing in the way becomes the block to break and
     * the goal waits behind it. That is what a person does: the stone in front of the ore *is* the next
     * thing to mine.
     */
    private var working: BlockPos? = null

    /** Blocks dug through on the way into the current goal, so a hopeless tunnel is not endless. */
    private var digDepth = 0

    /** True while a swing is in flight, so the module knows to keep vanilla from aborting it. */
    val isMining: Boolean get() = MiningClaim.isHeldBy(this)

    /** Where the job currently is, for the overlay and for the modes' own decisions. */
    val isBusy: Boolean get() = phase != Phase.IDLE

    /**
     * Points the job at a block, or at nothing.
     *
     * Retargeting an active route rather than replacing it: the search keeps what it has learned,
     * and a mode that changes its mind between two adjacent ores does not pay for a fresh plan.
     */
    fun retarget(pos: BlockPos?) {
        if (pos == target) return

        target = pos
        working = pos
        digDepth = 0
        phaseTicks = 0
        arrivedTicks = 0
        navResult = null
        navRetry = 0
        swings = 0
        MiningClaim.release(this)

        if (pos == null) {
            releaseRoute()
            phase = Phase.IDLE
            return
        }

        phase = Phase.APPROACH
        val reusing = handle?.isActive == true
        val distance = sqrt(pos.distToCenterSqr(player.eyePosition))
        // Scaled with the journey, not a flat number. A sweep may legitimately send the miner ninety
        // blocks for one ore, which at a sprint is twenty seconds before a single detour — a fixed
        // fifteen-second patience would abandon every distant target just before arriving at it.
        approachDeadline = (APPROACH_BASE + distance * APPROACH_TICKS_PER_BLOCK)
            .toInt()
            .coerceAtMost(APPROACH_CAP)
        AutoMineDebug.event(
            "target ${AutoMineDebug.pos(pos)} ${pos.state?.block?.name?.string ?: "?"}" +
                " d=%.1f".format(distance) +
                " route=${if (reusing) "retarget" else "new"}"
        )
        if (reusing) handle?.retarget(goalFor(pos)) else requestRoute(pos)
    }

    /** Drops everything: the route, the claim and the target. */
    fun cancel() {
        releaseRoute()
        MiningClaim.release(this)
        SilentHotbar.resetSlot(owner)
        target = null
        working = null
        digDepth = 0
        phase = Phase.IDLE
        phaseTicks = 0
    }

    /**
     * Asks for the aim, from the rotation update rather than from the tick.
     *
     * A rotation submitted here is stepped and sent on this same tick; submitted from an ordinary
     * tick handler it waits for the next one. Over a mining session that is the difference between
     * a swing every tick and a swing every other tick.
     */
    fun updateAim() {
        val pos = working ?: target ?: return
        if (phase == Phase.IDLE) return

        val state = pos.state ?: return
        val reach = ModuleAutoMine.reach.toDouble()

        // Two different aims, and conflating them is what made the miner stand and stare.
        //
        // The one that counts is a point on the block *within reach*, because the swing is only sent
        // once the server's own rotation traces back to the block at that same range. Asking for the
        // aim with a range longer than reach — which is what "start turning early" naively becomes —
        // returns a perfectly good rotation onto a point five blocks away, and the check at four
        // then misses it for ever: the head is right, the trace is short, and nothing ever happens.
        //
        // So the lead is a plain look at the block's middle while still walking up to it. It commits
        // to no point in particular, it turns the head most of the way, and the moment the block is
        // genuinely in reach the precise aim takes over with only a little left to correct.
        val precise = raytraceBlockRotation(player.eyePosition, pos, state, reach, wallsReach())
        val rotation = precise?.rotation
            ?: if (phase == Phase.APPROACH) Rotation.lookingAt(pos.center, player.eyePosition) else return

        RotationManager.setRotationTarget(
            rotation,
            considerInventory = false,
            valueGroup = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = owner,
        )
    }

    /** One tick of work. Call from a tick handler, i.e. after the rotation has been stepped. */
    @Suppress("ReturnCount")
    fun tick(): MineOutcome {
        val goal = target ?: return MineOutcome.IDLE
        phaseTicks++

        // The goal being gone ends the attempt however it happened: we broke it, the route broke it on
        // the way in, or somebody else did.
        if (goal.state?.isAir != false) return finish(MineOutcome.MINED)

        val block = working ?: goal
        if (block != goal && block.state?.isAir != false) {
            // One layer of the way in is open. Take the next one, or the goal if it is now in view.
            return advanceThroughTunnel(goal)
        }

        return when (phase) {
            Phase.IDLE -> MineOutcome.IDLE
            Phase.APPROACH -> approach(goal)
            Phase.MINE -> mine(block)
        }
    }

    /**
     * Arrived, out of patience, and the goal is still hidden: start on whatever is covering it.
     *
     * The honest reading of "I am standing next to it and cannot see it" is that the ore is behind
     * something, and that something is the next thing to mine. Only when nothing is in the way is the
     * target hopeless: that means we are on the wrong side of it, and no amount of digging from here
     * changes where we are standing.
     */
    private fun startDiggingIn(goal: BlockPos): MineOutcome {
        val between = firstBlockInTheWay(goal)
        if (between == null) {
            AutoMineDebug.event("gave up on ${AutoMineDebug.pos(goal)}: hidden, nothing in the way")
            return finish(MineOutcome.GAVE_UP)
        }

        working = between
        phase = Phase.MINE
        phaseTicks = 0
        AutoMineDebug.event("digging through ${AutoMineDebug.pos(between)} to ${AutoMineDebug.pos(goal)}")
        return MineOutcome.RUNNING
    }

    /**
     * Chooses the next block to break on the way into [goal], after one was cleared.
     *
     * Bounded, because "dig towards it until you can see it" is a loop and a loop needs an end: a goal
     * still hidden after several blocks is one the geometry is arguing about, and another target costs
     * less than winning that argument.
     */
    private fun advanceThroughTunnel(goal: BlockPos): MineOutcome {
        digDepth++
        if (digDepth > MAX_DIG_DEPTH) {
            AutoMineDebug.event("gave up on ${AutoMineDebug.pos(goal)}: still buried after $digDepth blocks")
            return finish(MineOutcome.GAVE_UP)
        }

        if (canReach(goal)) {
            working = goal
            phaseTicks = 0
            AutoMineDebug.event("${AutoMineDebug.pos(goal)} is in view now, taking it")
            return MineOutcome.RUNNING
        }

        val next = firstBlockInTheWay(goal)
        if (next == null) {
            // Nothing in the way and still not visible: we are looking from the wrong side of it.
            phase = Phase.APPROACH
            phaseTicks = 0
            arrivedTicks = 0
            requestRoute(goal)
            return MineOutcome.RUNNING
        }

        working = next
        phaseTicks = 0
        AutoMineDebug.event("digging through ${AutoMineDebug.pos(next)} to reach ${AutoMineDebug.pos(goal)}")
        return MineOutcome.RUNNING
    }

    /**
     * The first solid block between the eyes and [goal], or null when nothing is in the way.
     *
     * The plain colliding-block trace, which is what the server itself uses to decide what a click hit
     * - so whatever this returns is by definition visible, and therefore something the aim can be
     * verified against.
     */
    private fun firstBlockInTheWay(goal: BlockPos): BlockPos? {
        val hit = rayTraceCollidingBlocks(player.eyePosition, goal.center) ?: return null
        if (hit.type != HitResult.Type.BLOCK || hit.blockPos == goal) return null

        val between = hit.blockPos
        val state = between.state ?: return null
        if (state.isAir || state.isNotBreakable(between)) return null

        return between.takeIf { canReach(it) }
    }

    // -----------------------------------------------------------------------
    //  Phases
    // -----------------------------------------------------------------------

    private fun approach(pos: BlockPos): MineOutcome {
        if (canReach(pos)) {
            // Standing still to work: a route that keeps walking while we swing carries us out of
            // reach halfway through the block.
            val walked = phaseTicks
            releaseRoute()
            phase = Phase.MINE
            phaseTicks = 0
            AutoMineDebug.event("in reach after $walked ticks of walking, mining ${AutoMineDebug.pos(pos)}")
            return MineOutcome.RUNNING
        }

        AutoMineDebug.state(
            "approaching ${AutoMineDebug.pos(pos)}: nav=${NavService.status} owner=" +
                (if (NavService.currentOwner === owner) "us" else NavService.currentOwner?.name ?: "none") +
                " result=${navResult ?: "-"}"
        )

        // The route reports through its callbacks, not through the handle. A handle whose request
        // has ended — arrived, failed or preempted — is no longer the active one, and an inactive
        // handle answers IDLE to everything it is asked. Reading the status was therefore a way of
        // never being told anything at all: the job would have sat there until its deadline.
        when (navResult) {
            NavStatus.UNREACHABLE, NavStatus.FAILED -> {
                AutoMineDebug.event("gave up on ${AutoMineDebug.pos(pos)}: route said $navResult")
                return finish(MineOutcome.GAVE_UP)
            }

            // Something more important wanted the engine. That is not this target's fault, so we
            // wait our turn rather than blacklisting a perfectly good block.
            NavStatus.PREEMPTED -> {
                AutoMineDebug.event("route preempted by ${NavService.currentOwner?.name ?: "?"}, waiting")
                navResult = null
                handle = null
                navRetry = NAV_RETRY_TICKS
            }

            NavStatus.ARRIVED -> {
                // Arrived, and the block is not in view. The goal measures the distance to the block's
                // centre while the aim needs a line to a visible face, and those two disagree while the
                // body is still moving — so it is given a moment to come to rest before we believe it.
                arrivedTicks++
                AutoMineDebug.state("arrived but cannot see ${AutoMineDebug.pos(pos)} ($arrivedTicks/$ARRIVAL_GRACE)")
                if (arrivedTicks > ARRIVAL_GRACE) return startDiggingIn(pos)
            }

            else -> Unit
        }

        if (navRetry > 0) {
            navRetry--
        } else if (handle?.isActive != true && navResult == null) {
            requestRoute(pos)
        }

        if (phaseTicks > approachDeadline) {
            AutoMineDebug.event("gave up on ${AutoMineDebug.pos(pos)}: $approachDeadline ticks of walking")
            return finish(MineOutcome.GAVE_UP)
        }
        return MineOutcome.RUNNING
    }

    private fun mine(pos: BlockPos): MineOutcome {
        interrupted(pos)?.let { return it }

        val hit = swingableHit(pos) ?: return MineOutcome.RUNNING
        swings++
        AutoMineDebug.state("breaking ${AutoMineDebug.pos(pos)}")

        pos.state?.let { state ->
            NavHotbar.bestDigTool(state)?.let { slot ->
                SilentHotbar.selectSlotSilently(owner, slot, SLOT_RESET_TICKS)
            }
        }
        doBreak(hit, swingMode = SwingMode.DO_NOT_HIDE)

        val after = pos.state?.takeIf { it.isAir } ?: return MineOutcome.RUNNING

        // The navigation cache only ever learns about blocks from the server's own packets, so for
        // a round trip the route would still be planning against the wall we just removed.
        NavWorld.recordBlock(pos, after, cleared = true)
        AutoMineDebug.event("broke ${AutoMineDebug.pos(pos)} in $phaseTicks ticks")

        // Only the block we came for ends the attempt. Anything else was the way in, and the next tick
        // works out what is left of it.
        val goal = target
        val wasTheGoal = goal == null || pos == goal
        if (!wasTheGoal) MiningClaim.release(this)

        return if (wasTheGoal) finish(MineOutcome.MINED) else MineOutcome.RUNNING
    }

    /**
     * Anything that stops this from being an ordinary tick of digging, or null to carry on.
     *
     * Three ways out, and they are separate for a reason. Losing sight of the block is not a failure
     * — walk back to it. Handing the block to PacketMine is not a failure either, it is the player's
     * choice of who swings. Only the deadline is a failure, and there are two of them: "this block is
     * slow" and "this block is never going to happen" deserve very different patience. A swing that
     * has never once been allowed through — an aim that cannot be verified, marginal geometry, a dig
     * held elsewhere — is the second kind, and waiting ten seconds on it is the freeze a player sees
     * and reports as the miner staring at a block.
     */
    private fun interrupted(pos: BlockPos): MineOutcome? {
        if (!canReach(pos)) {
            AutoMineDebug.event("lost sight of ${AutoMineDebug.pos(pos)} mid-dig, walking back")
            phase = Phase.APPROACH
            phaseTicks = 0
            requestRoute(pos)
            return MineOutcome.RUNNING
        }

        val deadline = if (swings > 0) MINE_DEADLINE else START_DEADLINE
        if (phaseTicks > deadline) {
            val why = if (swings > 0) "ticks of swinging" else "ticks without a single swing"
            AutoMineDebug.event("gave up on ${AutoMineDebug.pos(pos)}: $deadline $why")
            return finish(MineOutcome.GAVE_UP)
        }

        // Nuker and Fucker hand their targets over the same way.
        if (ModuleAutoMine.usePacketMine && ModulePacketMine.running) {
            ModulePacketMine.setTarget(pos)
            return MineOutcome.RUNNING
        }

        return null
    }

    /**
     * The hit to swing at, or null when this tick may not swing yet.
     *
     * Two conditions, and both are refusals to act rather than failures. The server has to be looking
     * at the block already — `serverRotation` is the angle on the wire, while the one we asked for a
     * moment ago may still be catching up, and a break resolved against the old angle is a break
     * somewhere else. And the dig has to be ours to take: one at a time across the whole client.
     */
    private fun swingableHit(pos: BlockPos): BlockHitResult? {
        val state = pos.state ?: return null
        val hit = raytraceBlock(ModuleAutoMine.reach.toDouble(), RotationManager.serverRotation, pos, state)

        if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != pos) {
            // The commonest reason a dig looks stuck from outside.
            val saw = hit?.blockPos?.let(AutoMineDebug::pos) ?: "miss"
            AutoMineDebug.state("waiting for the server to see ${AutoMineDebug.pos(pos)} (hit=$saw)")
            return null
        }

        if (!MiningClaim.claim(this, pos)) {
            val holder = MiningClaim.holder?.let { it::class.simpleName } ?: "?"
            AutoMineDebug.state("dig claimed elsewhere by $holder")
            return null
        }

        return hit
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private fun finish(outcome: MineOutcome): MineOutcome {
        releaseRoute()
        MiningClaim.release(this)
        lastTarget = target
        target = null
        working = null
        digDepth = 0
        phase = Phase.IDLE
        phaseTicks = 0
        arrivedTicks = 0
        navResult = null
        navRetry = 0
        swings = 0
        return outcome
    }

    private fun canReach(pos: BlockPos): Boolean {
        val state = pos.state ?: return false
        if (state.isAir) return false

        val reach = ModuleAutoMine.reach.toDouble()
        return raytraceBlockRotation(player.eyePosition, pos, state, reach, wallsReach()) != null
    }

    /**
     * How far a point that the eyes cannot see may still be aimed at.
     *
     * Zero unless the player asked otherwise, and that zero does two things at once. It is the "do not
     * break through walls" rule — an ore behind stone is left until the stone is gone — and it is also
     * what keeps the aim honest with the check that follows it: the swing is only sent once a trace
     * from the server's own rotation reaches the block, and such a trace stops at the first solid
     * thing. Aiming at a point behind a wall therefore produced a rotation that could never be
     * confirmed, which is a miner standing and staring rather than one mining.
     */
    private fun wallsReach(): Double =
        if (ModuleAutoMine.throughWalls) ModuleAutoMine.reach.toDouble() else 0.0

    private fun goalFor(pos: BlockPos) = GoalAdjacentTo(pos, ModuleAutoMine.reach.toDouble())

    private fun requestRoute(pos: BlockPos) {
        navResult = null
        handle = NavService.request(
            owner = owner,
            goal = goalFor(pos),
            caps = AutoMineCaps.build(),
            rotations = rotations,
            priority = Priority.NORMAL,
            // Re-read before every search: health and the block supply both change while we mine,
            // and a route planned against the inventory of ten minutes ago plans bridges we can no
            // longer build.
            capsProvider = AutoMineCaps::build,
            onArrive = { navResult = NavStatus.ARRIVED },
            onFailed = { status -> navResult = status },
        )
    }

    private fun releaseRoute() {
        handle?.takeIf { it.isActive }?.cancel()
        handle = null
    }

    private companion object {
        /** Patience for the walk itself, before any distance is counted: three seconds. */
        const val APPROACH_BASE = 60

        /**
         * Extra ticks of patience per block of distance.
         *
         * Roughly three times what a sprint takes, which is the room a route needs for going round
         * things, digging through one or two, and the occasional replan.
         */
        const val APPROACH_TICKS_PER_BLOCK = 10.0

        /** Forty-five seconds. Past this a route is not slow, it is wrong. */
        const val APPROACH_CAP = 900

        /** Ten seconds of swinging. Obsidian by hand is worse, but nothing a miner should be doing. */
        const val MINE_DEADLINE = 200

        /** Ticks the chosen tool stays selected after the last swing asked for it. */
        const val SLOT_RESET_TICKS = 4

        /** How long an arrival is given to settle before "I cannot see it" is believed. */
        const val ARRIVAL_GRACE = 10

        /**
         * Blocks the miner will dig through to expose one goal.
         *
         * Four is a wall, not a tunnel: past that either the pick is on the wrong side of the ore or
         * the geometry is not what it looks like, and the next target costs less than finding out which.
         */
        const val MAX_DIG_DEPTH = 4

        /** A second of patience after being preempted, before asking the engine for a route again. */
        const val NAV_RETRY_TICKS = 20

        /**
         * Ticks a block gets to produce its very first swing before it is written off.
         * A second and a half: long enough for a rotation to reach the server twice over, short
         * enough that a target the geometry will never allow is a hiccup rather than a freeze.
         */
        const val START_DEADLINE = 30
    }
}
