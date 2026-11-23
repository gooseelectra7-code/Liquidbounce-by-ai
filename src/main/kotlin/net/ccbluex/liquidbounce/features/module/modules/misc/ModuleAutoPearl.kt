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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.sequenceHandler
import net.ccbluex.liquidbounce.event.tickConditional
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.projectiles.SituationalProjectileAngleCalculator
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val MAX_SIMULATED_TICKS = 240

/**
 * Auto pearl module
 *
 * AutoPearl aims and throws a pearl at an enemies pearl trajectory
 *
 * @author sqlerrorthing
 */
object ModuleAutoPearl : ClientModule(
    "AutoPearl",
    ModuleCategories.COMBAT,
    aliases = listOf("PearlFollower", "PearlTarget")
) {

    private val mode by enumChoice("Mode", Modes.TRIGGER)

    private object Limits : ToggleableValueGroup(this, "Limits", true) {
        val angle by int("Angle", 180, 0..180, suffix = "°")
        val activationDistance by float("MinDistance", 8.0f, 0.0f..10.0f, suffix = "m")
        val destDistance by float("DestinationDistance", 8.0f, 0.0f..30.0f, suffix = "m")
    }

    private object Rotate : ToggleableValueGroup(this, "Rotate", true) {
        val rotations = tree(RotationsValueGroup(this))
    }

    /**
     * Where to actually land.
     *
     * Following the enemy's pearl to its exact landing point drops you INSIDE them — you arrive with
     * zero spacing, usually mid-air-to-ground, and eat the first hit of the trade. What you want is
     * to arrive at fighting distance, already facing them.
     *
     * So the destination is no longer the enemy's landing point but a spot [minDistance]..
     * [maxDistance] away from it, chosen by [findLandingSpot] — and chosen against the ONLY test
     * that matters for a thrown projectile: not "is this a nice spot" but "does a pearl thrown at
     * this angle actually end up there". A spot that fails the simulation is worthless however good
     * it looks, so candidates are simulated in preference order and the first that lands within
     * [tolerance] wins.
     */
    private object Landing : ToggleableValueGroup(this, "Landing", true) {
        val minDistance by float("MinDistance", 2.0f, 0.5f..8.0f, suffix = "m")
        val maxDistance by float("MaxDistance", 3.0f, 0.5f..12.0f, suffix = "m")

        /** How far the simulated landing may miss the intended spot before it is rejected. */
        val tolerance by float("Tolerance", 1.5f, 0.25f..5.0f, suffix = "m")
    }

    init {
        treeAll(Rotate, Limits, Landing)
    }

    /**
     * How long someone stays worth following after KillAura drops them.
     *
     * KillAura loses its target the moment they leave its range — which is precisely what pearling
     * away does. Keyed to the CURRENT target alone, Target mode therefore fails in exactly the
     * situation it exists for. This remembers the last one so the chase still happens, but only
     * while nobody else is engaging, so it never pulls you off a live fight to chase someone who
     * left half a minute ago.
     */
    private val targetMemory by int("TargetMemory", 30, 0..120, "s")

    private var lastKillAuraTarget: Entity? = null
    private var lastKillAuraTargetMs = 0L

    private val combatPauseTime by int("CombatPauseTime", 0, 0..40, "ticks")
    private val slotResetDelay by intRange("SlotResetDelay", 0..0, 0..40, "ticks")

    private val queue = ArrayDeque<Rotation>()

    @Suppress("unused")
    private val pearlSpawnHandler = handler<PacketEvent> { event ->
        if (event.packet !is ClientboundAddEntityPacket || event.packet.type != EntityType.ENDER_PEARL) {
            return@handler
        }

        Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL) ?: return@handler

        val data = event.packet
        val entity = data.type.create(world, EntitySpawnReason.SPAWN_ITEM_USE) as ThrownEnderpearl
        entity.recreateFromPacket(data)

        proceedPearl(
            pearl = entity,
            // entity.velocity & entity.pos doesn't work, don't use it
            velocity = with(data) { Vec3(movement.x, movement.y, movement.z) },
            pearlPos = with(data) { Vec3(x, y, z) }
        )
    }

    @Suppress("unused")
    private val simulatedTickHandler = sequenceHandler<RotationUpdateEvent> {
        val rotation = queue.firstOrNull() ?: return@sequenceHandler

        CombatManager.pauseCombatForAtLeast(combatPauseTime)
        if (Rotate.enabled) {
            RotationManager.setRotationTarget(
                Rotate.rotations.toRotationTarget(rotation),
                Priority.IMPORTANT_FOR_USAGE_3,
                this@ModuleAutoPearl
            )
        }
    }

    /** Keeps [lastKillAuraTarget] fresh; the stamp is what [canTrigger] measures the memory against. */
    @Suppress("unused")
    private val targetMemoryHandler = tickHandler {
        val current = ModuleKillAura.targetTracker.target ?: return@tickHandler
        lastKillAuraTarget = current
        lastKillAuraTargetMs = System.currentTimeMillis()
    }

    @Suppress("unused")
    private val gameTickHandler = tickHandler {
        val rotation = queue.removeFirstOrNull() ?: return@tickHandler
        val itemSlot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL) ?: return@tickHandler

        if (Rotate.enabled) {
            fun isRotationSufficient(): Boolean {
                return RotationManager.serverRotation.angleTo(rotation) <= 1.0f
            }

            tickConditional(20) {
                RotationManager.setRotationTarget(
                    Rotate.rotations.toRotationTarget(rotation),
                    Priority.IMPORTANT_FOR_USAGE_3,
                    this@ModuleAutoPearl
                )

                isRotationSufficient()
            }

            if (!isRotationSufficient()) {
                return@tickHandler
            }
        }

        val (yaw, pitch) = rotation.normalize()
        useHotbarSlotOrOffhand(itemSlot, slotResetDelay.random(), yaw, pitch)
    }

    private fun proceedPearl(
        pearl: ThrownEnderpearl,
        velocity: Vec3,
        pearlPos: Vec3
    ) {
        if (!canTrigger(pearl)) {
            return
        }

        val destination = runSimulation(
            owner = pearl.owner ?: player,
            velocity = velocity,
            pos = pearlPos
        )?.location ?: return

        if (Limits.enabled && Limits.activationDistance > destination.distanceTo(player.position())) {
            return
        }

        // Land NEXT to them, not on them.
        val rotation = if (Landing.enabled) {
            findLandingSpot(destination) ?: return
        } else {
            val direct = angleTo(destination) ?: return
            direct.takeIf { canThrow(it, destination) } ?: return
        }

        if (queue.isEmpty()) {
            queue.add(rotation)
        }
    }

    /**
     * Picks the throw that puts us at fighting distance from [enemyLanding].
     *
     * Candidates are generated as a fan around the enemy's landing point and walked in preference
     * order, and the first one whose SIMULATED landing agrees with the intent is taken. Ordering is
     * what keeps this cheap — the natural spot is usually accepted within the first few tries, so
     * the expensive part (a full projectile simulation each) runs a handful of times, not sixty.
     *
     * The order encodes what a good landing looks like:
     * - **Angle**: straight back along the line from the enemy toward US first, then fanning out.
     *   Landing short of them means the pearl travels less far, which is both the easiest throw to
     *   hit and the one that arrives with them in front of you rather than behind.
     * - **Distance**: the middle of the band first, then the edges, so spacing degrades gracefully
     *   instead of jumping to a limit.
     *
     * Every candidate is snapped to real ground ([groundSpot]) before being considered, so a spot
     * hanging in the air, over a hole, or on lava is discarded before a simulation is spent on it.
     */
    @Suppress("ReturnCount")
    private fun findLandingSpot(enemyLanding: Vec3): Rotation? {
        val toPlayer = player.position().subtract(enemyLanding)
        val baseAngle = atan2(toPlayer.z, toPlayer.x)

        val minDistance = Landing.minDistance.toDouble()
        val maxDistance = maxOf(minDistance, Landing.maxDistance.toDouble())
        val distances = listOf(
            (minDistance + maxDistance) / 2.0,
            minDistance,
            maxDistance
        )

        for (distance in distances) {
            for (offset in ANGLE_FAN) {
                val angle = baseAngle + Math.toRadians(offset)
                val spot = groundSpot(
                    x = enemyLanding.x + cos(angle) * distance,
                    z = enemyLanding.z + sin(angle) * distance,
                    aroundY = enemyLanding.y
                ) ?: continue

                val rotation = angleTo(spot) ?: continue
                if (simulatedLandingError(rotation, spot) <= Landing.tolerance) {
                    return rotation
                }
            }
        }
        return null
    }

    /** Fan of angles around the enemy-to-player direction, nearest first. */
    private val ANGLE_FAN = listOf(0.0, 25.0, -25.0, 50.0, -50.0, 80.0, -80.0, 115.0, -115.0, 155.0, -155.0, 180.0)

    /**
     * Snaps a horizontal position onto somewhere actually standable, searching down from a little
     * above [aroundY]. Returns null when the column offers no footing — over a hole, a ceiling in
     * the way, or a hazard to land on.
     */
    private fun groundSpot(x: Double, z: Double, aroundY: Double): Vec3? {
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        val top = floor(aroundY).toInt() + GROUND_SEARCH_UP

        for (y in top downTo top - GROUND_SEARCH_DOWN) {
            val feet = BlockPos(blockX, y, blockZ)
            val below = feet.below()

            if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) continue
            // Room to stand, or we would land inside geometry.
            if (!world.getBlockState(feet).isAir || !world.getBlockState(feet.above()).isAir) continue
            if (isHazard(below) || isHazard(feet)) continue

            return Vec3(blockX + 0.5, y.toDouble(), blockZ + 0.5)
        }
        return null
    }

    private fun isHazard(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return state.`is`(Blocks.LAVA) || state.`is`(Blocks.FIRE) || state.`is`(Blocks.MAGMA_BLOCK) ||
            state.`is`(Blocks.CAMPFIRE) || state.`is`(Blocks.SOUL_CAMPFIRE)
    }

    private fun angleTo(destination: Vec3): Rotation? =
        SituationalProjectileAngleCalculator.calculateAngleForStaticTarget(
            TrajectoryInfo.GENERIC,
            destination,
            EntityDimensions.fixed(1.0F, 0.0F)
        )

    /** How far a pearl thrown at [rotation] actually ends up from [intended]. */
    private fun simulatedLandingError(rotation: Rotation, intended: Vec3): Double {
        val landing = TrajectoryInfoRenderer.getHypotheticalTrajectory(
            owner = player,
            trajectoryInfo = TrajectoryInfo.GENERIC,
            trajectoryType = TrajectoryType.EnderPearl,
            rotation = rotation
        ).runSimulation(MAX_SIMULATED_TICKS).hitResult?.location ?: return Double.MAX_VALUE

        return landing.distanceTo(intended)
    }

    private const val GROUND_SEARCH_UP = 3
    private const val GROUND_SEARCH_DOWN = 8

    private fun canTrigger(pearl: ThrownEnderpearl): Boolean {
        if (Limits.enabled && Limits.angle < RotationUtil.crosshairAngleToEntity(pearl)) {
            return false
        }

        val owner = pearl.owner ?: return mode == Modes.TRIGGER

        if (owner === player) {
            return false
        }

        return when(mode) {
            Modes.TRIGGER -> owner.shouldBeAttacked()
            Modes.TARGET -> isTargetOrRecent(owner)
        }
    }

    /**
     * KillAura's current target, or the one it just lost.
     *
     * The recency branch is the whole point of Target mode working at all: a player who pearls away
     * leaves KillAura's range in the same instant, so by the time their pearl is in the air the
     * current target is already null and a strict identity test can never match. The memory keeps
     * them eligible for [targetMemory] seconds.
     *
     * It is deliberately conditional on there being no CURRENT target — if something is engaging us
     * now, that fight outranks chasing whoever ran off, and we should not be pearled away from it.
     */
    private fun isTargetOrRecent(owner: Entity): Boolean {
        val current = ModuleKillAura.targetTracker.target
        if (current != null) {
            return current === owner
        }

        if (targetMemory <= 0 || lastKillAuraTarget !== owner) {
            return false
        }

        return System.currentTimeMillis() - lastKillAuraTargetMs <= targetMemory * 1000L
    }

    private fun canThrow(
        angles: Rotation,
        destination: Vec3
    ): Boolean {
        val simulatedDestination = TrajectoryInfoRenderer.getHypotheticalTrajectory(
            owner = player,
            trajectoryInfo = TrajectoryInfo.GENERIC,
            trajectoryType = TrajectoryType.EnderPearl,
            rotation = angles
        ).runSimulation(MAX_SIMULATED_TICKS).hitResult?.location ?: return false

        return !Limits.enabled || Limits.destDistance > destination.distanceTo(simulatedDestination)
    }

    private fun runSimulation(
        owner: Entity,
        velocity: Vec3,
        pos: Vec3,
        trajectoryInfo: TrajectoryInfo = TrajectoryInfo.GENERIC,
        renderOffset: Vec3 = Vec3.ZERO
    ): HitResult? =
        TrajectoryInfoRenderer(
            owner = owner,
            icon = Items.ENDER_PEARL.defaultInstance,
            velocity = velocity,
            pos = pos,
            trajectoryInfo = trajectoryInfo,
            trajectoryType = TrajectoryType.EnderPearl,
            type = TrajectoryInfoRenderer.Type.REAL,
            renderOffset = renderOffset
        ).runSimulation(MAX_SIMULATED_TICKS).hitResult

    override fun onDisabled() {
        queue.clear()
        lastKillAuraTarget = null
        lastKillAuraTargetMs = 0L
    }

    private enum class Modes(override val tag: String) : Tagged {
        TRIGGER("Trigger"),
        TARGET("Target")
    }

}
