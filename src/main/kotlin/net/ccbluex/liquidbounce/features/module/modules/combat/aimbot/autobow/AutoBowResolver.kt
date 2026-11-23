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

package net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.autobow

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.projectiles.ArrowBallistics
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.InterpolatedSimulationExtrapolation
import net.ccbluex.liquidbounce.utils.entity.LeadingPositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.LinearPositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayerCache
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.world.phys.AABB
import net.ccbluex.liquidbounce.utils.render.trajectory.HeldItemTrajectoryResolver
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The AutoBow resolver: where a shot leaves from, where its target will be, and which rotation
 * connects the two. [AutoBowAimbotFeature] asks it which rotation to file, [AutoBowAutoShootFeature]
 * asks it whether the rotation that will actually carry the shot hits — both through the same
 * physics ([ArrowBallistics]), so they cannot disagree about a shot.
 *
 * ## What the server does with a release (26.1.2)
 *
 * - The projectile spawns at the shooter's server position, `getEyeY() - 0.1` for arrows and
 *   tridents (`AbstractArrow.java:133`), `getEyeY() - 0.15` for a crossbow firework
 *   (`CrossbowItem.java:147`).
 * - A bow or trident shot (`shootFromRotation`, `Projectile.java:157-163`) inherits the shooter's
 *   `getKnownMovement()`, with the vertical part dropped while on ground. For a player that is the
 *   position delta of its last movement packet, or zero when the last client tick sent none
 *   (`ServerGamePacketListenerImpl.handleClientTickEnd` / `handlePlayerKnownMovement`). A crossbow
 *   shoots with a plain `shoot` and inherits nothing (`CrossbowItem.java:123`).
 * - The server's player position and rotation are those of the last movement packet processed
 *   before the release. `Minecraft.tick` runs `handleKeybinds` (the release) BEFORE
 *   `level.tickEntities` (this tick's movement packet), so a bow released on a tick flies from the
 *   state the client had at the START of that tick.
 *
 * ## When the arrow meets the target (26.1.2)
 *
 * A server tick starts by draining the client packets - `MinecraftServer.processPacketsAndTick`
 * runs `packetProcessor.processQueuedPackets()` (`MinecraftServer.java:1033`; a dedicated queue,
 * `network/PacketProcessor.java:14-40`, NOT main-thread tasks) and only then `tickServer`
 * (`:1035`) with its level ticks. A projectile spawned while our shot packet is handled starts
 * ticking at once (`PersistentEntitySectionManager.addEntity`, `:92-93` -> `onTickingStart` ->
 * `entityTickList.add`, `ServerLevel.java:2007-2008`; `EntityTickList.forEach` walks the map it
 * was added to), so its first move is the level tick of that SAME server tick `k`
 * (`tickCount == 1`, `ServerLevel.tickNonPassenger`), and the target's position it is tested
 * against is the one after that tick's packet drain - the server player is moved only by its own
 * packets, `ServerGamePacketListenerImpl.tickPlayer` snaps the simulated tick back
 * (`:313`, `:318`). The target's position we hold was read by its tracker INSIDE a level tick `j`
 * (`ServerChunkCache.tick` -> `ChunkMap.tick` -> `sendChanges`, `ServerLevel.java:392`, before the
 * entities), i.e. after tick `j`'s drain. Hence on the arrow's tick `n` (in level tick
 * `k + n - 1`) the target has moved `n - 1 + (k - j)` ticks past the position we hold, and
 * `k - j` is how long its packet had been in flight and waiting on our side plus how long our shot
 * takes to be processed - [TargetMotionLog.Motion.sinceReceivedTicks] plus [ShotDelayMeter] -
 * plus the fraction of tick `j` at which the tracker sent (the aimbot's `Alignment`). [firstHit]
 * therefore reads the target at `tick - 1 + lead`, and the solver's continuous stand-in for that
 * staircase is `ticks - 0.5 + lead`.
 */
internal object AutoBowResolver {

    /**
     * An entity a flown shot may meet: [wanted] when hitting it is the point, false when it is
     * merely in the way (an arrow stops at the first entity it meets, `AbstractArrow.stepMoveAndHit`).
     */
    class Candidate(val entity: Entity, val prediction: PositionExtrapolation, val wanted: Boolean)

    /**
     * Where the projectile of a release spawns, and what motion it inherits from the shooter.
     */
    class Shooter(val spawn: Vec3, val inherited: Vec3)

    /**
     * The shooter of a release that leaves THIS tick: the state of the last movement packet,
     * which is the client's state at the start of the tick.
     */
    fun currentShooter(info: TrajectoryInfo): Shooter =
        shooterAt(player.position(), player.position().subtract(player.lastPos), player.onGround(), info)

    /**
     * The shooter of a release that leaves NEXT tick, i.e. after this tick's movement packet.
     *
     * The vertical motion and the ground contact come from simulating the local player one tick
     * ahead with its current input (a jump, gravity, a landing). The horizontal motion is the last
     * tick's movement carried forward: [SimulatedPlayer] does not apply the item-use slowdown
     * (`LocalPlayer.modifyInput`, `LocalPlayer.java:681-682`) that a drawn bow puts on the input,
     * so its horizontal step would be several times too long.
     */
    fun nextTickShooter(info: TrajectoryInfo): Shooter {
        val simulated = SimulatedPlayer.fromClientPlayer(
            SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(DirectionalInput(player.input))
        )
        simulated.tick()

        val lastMovement = player.position().subtract(player.lastPos)
        val movement = Vec3(lastMovement.x, simulated.pos.y - player.y, lastMovement.z)

        return shooterAt(player.position().add(movement), movement, simulated.onGround, info)
    }

    private fun shooterAt(position: Vec3, movement: Vec3, onGround: Boolean, info: TrajectoryInfo): Shooter {
        val spawnDrop = if (info == TrajectoryInfo.FIREWORK_ROCKET) FIREWORK_SPAWN_DROP else ARROW_SPAWN_DROP
        val inherited = if (info.copiesPlayerVelocity) {
            Vec3(movement.x, if (onGround) 0.0 else movement.y, movement.z)
        } else {
            Vec3.ZERO
        }

        return Shooter(position.add(0.0, player.eyeHeight - spawnDrop, 0.0), inherited)
    }

    /**
     * The shot [stack] makes when released after [chargeTicks] ticks of drawing (only a bow
     * depends on it), or null when that shot does not exist (a bow drawn for too short).
     */
    fun shotInfo(stack: ItemStack, chargeTicks: Int): TrajectoryInfo? =
        HeldItemTrajectoryResolver.resolveHeldItemPrimaryShot(
            player,
            stack,
            false,
            bowUsageDurationTicks = chargeTicks
        )?.trajectoryInfo

    /**
     * The target's feet position as a function of the projectile's flight ticks.
     *
     * Every mode starts from the target's position and velocity as the SERVER last reported them
     * ([motion], see [TargetMotionLog]) - not from the client's interpolated entity, which trails
     * the packet by up to two ticks and smooths its velocity over three.
     *
     * A player that is using an item (drawing a bow of its own, eating, blocking) is extrapolated
     * linearly even in [PredictMode.SIMULATION]: [SimulatedPlayer] does not apply the item-use
     * slowdown, so it would accelerate the target to several times the speed it can actually move.
     *
     * @param mode how the target moves from there, null for not at all
     * @param leadTicks added to the flight time: the ticks between now and the release, and the
     * latency that shifts what we see against when the shot lands
     * @param maxTicks cap on the total look-ahead
     */
    fun predict(
        entity: Entity,
        motion: TargetMotionLog.Motion,
        mode: PredictMode?,
        leadTicks: Double,
        maxTicks: Double,
    ): PositionExtrapolation {
        val base = when {
            mode == null -> return PositionExtrapolation.constant(motion.position)
            mode == PredictMode.SIMULATION && entity is Player && !entity.isUsingItem ->
                InterpolatedSimulationExtrapolation(simulationFor(entity, motion))

            else -> LinearPositionExtrapolation(motion.position, motion.velocity)
        }

        return LeadingPositionExtrapolation(base, leadTicks, maxTicks)
    }

    private val simulations = HashMap<Int, SimulatedPlayerCache>()
    private var simulationsTick = -1
    private var simulationsWorld: ClientLevel? = null

    /**
     * One simulation per player per tick, shared by the aimbot's solve and AutoShoot's check —
     * both run inside the same `Minecraft.tick`, before the entities (and `player.tickCount`) move.
     * It starts at the server's position with the server's velocity, so no offset is needed.
     */
    private fun simulationFor(target: Player, motion: TargetMotionLog.Motion): SimulatedPlayerCache {
        if (simulationsTick != player.tickCount || simulationsWorld !== world) {
            simulations.clear()
            simulationsTick = player.tickCount
            simulationsWorld = world
        }

        return simulations.getOrPut(target.id) {
            SimulatedPlayerCache(
                SimulatedPlayer.fromOtherPlayer(
                    target,
                    SimulatedPlayer.SimulatedPlayerInput.guessInput(target, motion.velocity),
                    motion.position,
                    motion.velocity
                )
            )
        }
    }

    /**
     * The direct-fire solution against [entity], aimed at the centre of its hitbox at
     * [heightFraction] of its height (0 = feet, 1 = top of the head). The centre, not a point on
     * the surface: a path through the centre crosses the box whatever the entity margin is,
     * which matters because a point-blank arrow gets no margin at all
     * ([ArrowBallistics.entityMargin]).
     *
     * With [requireClearPath] the solution must also survive a block-aware simulation; when the
     * preferred height is covered, other heights on the same vertical axis are tried.
     */
    fun solve(
        entity: Entity,
        prediction: PositionExtrapolation,
        physics: ArrowBallistics.Physics,
        shooter: Shooter,
        heightFraction: Double,
        requireClearPath: Boolean,
    ): ArrowBallistics.Solution? {
        val heights = if (requireClearPath) {
            listOf(heightFraction) + FALLBACK_HEIGHTS
                .filter { abs(it - heightFraction) > HEIGHT_EPSILON }
                .sortedBy { abs(it - heightFraction) }
        } else {
            listOf(heightFraction)
        }

        val box = entity.boundingBox
        val feet = entity.position()

        for (height in heights) {
            val aimOffset = Vec3(
                box.center.x - feet.x,
                box.minY - feet.y + box.ysize * height,
                box.center.z - feet.z
            )

            // The target's motion is a staircase the arrow's ticks read one step behind (class
            // KDoc); half a tick back is the continuous stand-in for the solver.
            val solution = ArrowBallistics.solveDirect(
                physics,
                shooter.spawn,
                shooter.inherited,
                MAX_FLIGHT_TICKS
            ) { ticks -> prediction.getPositionInTicks(ticks - 0.5).add(aimOffset) } ?: continue

            if (!requireClearPath ||
                hits(solution.rotation, physics, shooter, entity, prediction, collideBlocks = true)) {
                return solution
            }
        }

        return null
    }

    /**
     * Whether a shot at [rotation] hits [target] first — the way the server would fly it.
     */
    fun hits(
        rotation: Rotation,
        physics: ArrowBallistics.Physics,
        shooter: Shooter,
        target: Entity,
        prediction: PositionExtrapolation,
        collideBlocks: Boolean,
    ): Boolean = firstHit(rotation, physics, shooter, listOf(target to prediction), collideBlocks) === target

    /**
     * Flies a shot at [rotation] tick by tick and returns the first of [targets] it hits, or null
     * when it hits a block first (with [collideBlocks]) or nothing at all.
     *
     * Per tick this mirrors `AbstractArrow.stepMoveAndHit`: the segment is cut at the first block
     * (`Block.COLLIDER`, fluids ignored), and an entity is hit when that segment crosses its
     * bounding box grown by [ArrowBallistics.entityMargin] (`ProjectileUtil.getManyEntityHitResult`).
     * The target is placed at its predicted position one step behind the arrow's tick, shifted by
     * [timeShift] (see the class KDoc for the count).
     */
    fun firstHit(
        rotation: Rotation,
        physics: ArrowBallistics.Physics,
        shooter: Shooter,
        targets: List<Pair<Entity, PositionExtrapolation>>,
        collideBlocks: Boolean,
        timeShift: Double = 0.0,
    ): Entity? {
        if (targets.isEmpty()) {
            return null
        }

        val velocity = ArrowBallistics.launchVelocity(rotation, physics.speed, shooter.inherited)
        val bottom = world.minY - OUT_OF_WORLD_DEPTH
        var hit: Entity? = null

        ArrowBallistics.simulate(physics, shooter.spawn, velocity, MAX_SIMULATED_TICKS) { tick, from, segmentEnd ->
            var to = segmentEnd
            var blocked = false

            if (collideBlocks) {
                val blockHit = world.clip(
                    ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
                )

                if (blockHit.type != HitResult.Type.MISS) {
                    to = blockHit.location
                    blocked = true
                }
            }

            val margin = ArrowBallistics.entityMargin(tick)
            var nearest = Double.MAX_VALUE

            for ((entity, prediction) in targets) {
                val point = boxAt(entity, prediction, tick - 1 + timeShift, margin).clip(from, to).orElse(null)
                    ?: continue
                val distance = from.distanceToSqr(point)

                if (distance < nearest) {
                    nearest = distance
                    hit = entity
                }
            }

            hit == null && !blocked && to.y > bottom
        }

        return hit
    }

    /**
     * The share of vanilla's random spread (and of the timing jitter) under which a shot at
     * [rotation] hits a [Candidate.wanted] entity before anything else - the number AutoShoot
     * releases on. See [ArrowBallistics.hitChance].
     *
     * Blocks are read off the NOMINAL path once: the draws are bounded by the tick on which it
     * meets a block, and a draw that has hit nothing by then is a miss. Draws deviate from the
     * nominal path by a degree at most, so this is exact except within a block of a wall's edge.
     * Every draw's segments are then tested against all candidates at their predicted positions
     * for that tick shifted by the draw's time shift; the nearest hit decides, and an unwanted
     * entity in the way is a miss.
     *
     * @param timingJitter half-width, in ticks, of the uniform uncertainty on when the shot is
     * processed relative to the target's motion
     */
    fun hitChance(
        rotation: Rotation,
        physics: ArrowBallistics.Physics,
        shooter: Shooter,
        candidates: List<Candidate>,
        collideBlocks: Boolean,
        timingJitter: Double,
    ): Double {
        if (candidates.none { it.wanted }) {
            return 0.0
        }

        val bottom = world.minY - OUT_OF_WORLD_DEPTH
        var lastTick = MAX_SIMULATED_TICKS

        if (collideBlocks) {
            val nominal = ArrowBallistics.launchVelocity(rotation, physics.speed, shooter.inherited)
            ArrowBallistics.simulate(physics, shooter.spawn, nominal, MAX_SIMULATED_TICKS) { tick, from, to ->
                val blockHit = world.clip(
                    ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
                )

                if (blockHit.type != HitResult.Type.MISS || to.y <= bottom) {
                    lastTick = tick
                    false
                } else {
                    true
                }
            }
        }

        return ArrowBallistics.hitChance(
            physics,
            shooter.spawn,
            shooter.inherited,
            rotation.directionVector,
            inaccuracy = 1.0,
            timingJitter = timingJitter,
            maxTicks = lastTick,
        ) { tick, from, to, timeShift ->
            val margin = ArrowBallistics.entityMargin(tick)
            var nearest = Double.MAX_VALUE
            var wanted = false

            for (candidate in candidates) {
                val box = boxAt(candidate.entity, candidate.prediction, tick - 1 + timeShift, margin)
                val point = box.clip(from, to).orElse(null) ?: continue
                val distance = from.distanceToSqr(point)

                if (distance < nearest) {
                    nearest = distance
                    wanted = candidate.wanted
                }
            }

            when {
                nearest == Double.MAX_VALUE -> ArrowBallistics.Outcome.NONE
                wanted -> ArrowBallistics.Outcome.TARGET
                else -> ArrowBallistics.Outcome.BLOCKED
            }
        }
    }

    /**
     * [entity]'s bounding box moved to where [prediction] puts it at [ticks], grown by [margin].
     */
    private fun boxAt(entity: Entity, prediction: PositionExtrapolation, ticks: Double, margin: Double): AABB {
        val position = prediction.getPositionInTicks(ticks)
        return entity.boundingBox.move(position.subtract(entity.position())).inflate(margin)
    }

    /**
     * Flight time the solver may look ahead: well past the range of a full-power arrow on flat
     * ground, so only a target truly out of reach comes back unsolved.
     */
    private const val MAX_FLIGHT_TICKS = 120.0
    private const val MAX_SIMULATED_TICKS = 160
    private const val OUT_OF_WORLD_DEPTH = 64

    private const val ARROW_SPAWN_DROP = 0.1
    private const val FIREWORK_SPAWN_DROP = 0.15

    /**
     * Heights tried, nearest to the preferred one first, when the preferred one is covered: the
     * head over a wall, the legs under a ledge.
     */
    private val FALLBACK_HEIGHTS = listOf(0.9, 0.75, 0.25, 0.1)
    private const val HEIGHT_EPSILON = 1E-3

}
