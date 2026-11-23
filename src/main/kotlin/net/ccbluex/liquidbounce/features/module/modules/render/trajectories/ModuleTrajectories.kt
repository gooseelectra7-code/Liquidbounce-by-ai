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
package net.ccbluex.liquidbounce.features.module.modules.render.trajectories

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.render.drawLinesWithWidth
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleFreeCam
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.entity.handItems
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentRotation
import net.ccbluex.liquidbounce.utils.render.trajectory.EntityTrajectoryResolver
import net.ccbluex.liquidbounce.utils.render.trajectory.HeldItemTrajectoryResolver
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryDisplayResolver
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryType
import net.minecraft.world.entity.TraceableEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.UUID

/**
 * Trajectories module
 *
 * Allows you to see where projectile items will land.
 */
@Suppress("MagicNumber")
object ModuleTrajectories : ClientModule("Trajectories", ModuleCategories.RENDER) {
    private val maxSimulatedTicks by int("MaxSimulatedTicks", 240, 1..1000, "ticks")
    private val maxRenderDistance by int("MaxRenderDistance", 96, 16..512, "m")
    private val cullBehindPlayer by boolean("CullBehindPlayer", false)
    private val showMultiShot by boolean("ShowMultiShot", true)

    private val trajectoryTypes by multiEnumChoice("TrajectoryTypes", TrajectoryType.entries, canBeNone = false)

    private val lineColor by color("LineColor", Color4b.WHITE)

    /**
     * Whether projectiles already in flight take those colours too. Off, they keep the per-type
     * colours (red arrow, purple pearl, orange rocket) that tell them apart at a glance.
     */
    private val colorFlyingProjectiles by boolean("ColorFlyingProjectiles", false)

    private object Tracer : ToggleableValueGroup(ModuleTrajectories, "Tracer", true) {
        val baseColor by color("BaseColor", Color4b(255, 52, 104, 225))
        val shimmerColor by color("ShimmerColor", Color4b(255, 242, 250, 255))
        val width by float("Width", 2.0F, 0.5F..5F)
        val glowWidth by float("GlowWidth", 9F, 1F..18F)
        val glowAlpha by float("GlowAlpha", 0.32F, 0F..1F)
        val shimmerSize by float("ShimmerSize", 0.24F, 0.05F..0.5F)
        val speed by float("Speed", 0.38F, 0.05F..2F)
        val linger by boolean("LingerAfterLanding", true)
        val lingerSeconds by float("LingerSeconds", 2.8F, 0.25F..8F)

        fun style() = TrajectoryInfoRenderer.TracerStyle(
            baseColor = baseColor,
            shimmerColor = shimmerColor,
            width = width,
            glowWidth = maxOf(width, glowWidth),
            glowAlpha = glowAlpha,
            shimmerSize = shimmerSize,
            cycleMillis = (2200F / speed).toLong().coerceAtLeast(100L),
        )
    }

    private object LandingMarker : ToggleableValueGroup(ModuleTrajectories, "LandingMarker", true) {
        val style by enumChoice("Style", MarkerStyle.DIAMOND)
        val color by color("Color", Color4b(245, 250, 255, 235))
        val size by float("Size", 0.24F, 0.08F..0.55F)
        val pulse by boolean("Pulse", false)

        fun draw(env: WorldRenderEnvironment, hit: HitResult) {
            val position = hit.location
            val normal = (hit as? BlockHitResult)?.direction
            val pulseScale = if (pulse) 1F + sin(System.currentTimeMillis() / 650.0).toFloat() * 0.035F else 1F
            val s = size * pulseScale
            val surfacePosition = if (normal == null) position else position.add(
                normal.stepX * 0.006, normal.stepY * 0.006, normal.stepZ * 0.006
            )
            env.withPositionRelativeToCamera(surfacePosition) {
                env.poseStack.pushPose()
                if (normal == null) {
                    env.poseStack.mulPose(mc.gameRenderer.mainCamera.rotation())
                } else {
                    env.poseStack.mulPose(Quaternionf().rotationTo(
                        Vector3f(0F, 0F, 1F), Vector3f(normal.stepX.toFloat(), normal.stepY.toFloat(), normal.stepZ.toFloat())
                    ))
                }
                when (style) {
                    MarkerStyle.DIAMOND -> {
                        val top = Vec3f(0F, s, 0F)
                        val right = Vec3f(s, 0F, 0F)
                        val bottom = Vec3f(0F, -s, 0F)
                        val left = Vec3f(-s, 0F, 0F)
                        val tint = color.alpha(22)
                        env.drawTriangle(top, right, left, tint.argb)
                        env.drawTriangle(right, bottom, left, tint.argb)
                        val outline = arrayOf(top, right, right, bottom, bottom, left, left, top)
                        env.drawLinesWithWidth(color.alpha(65).argb, 2.6F, *outline)
                        env.drawLinesWithWidth(color.argb, 1.1F, *outline)
                    }
                    MarkerStyle.RING -> {
                        val points = ArrayList<Vec3f>(64)
                        for (i in 0 until 32) {
                            val a0 = i * PI / 16.0
                            val a1 = (i + 1) * PI / 16.0
                            points += Vec3f(cos(a0).toFloat() * s, sin(a0).toFloat() * s, 0F)
                            points += Vec3f(cos(a1).toFloat() * s, sin(a1).toFloat() * s, 0F)
                        }
                        env.drawLinesWithWidth(color.alpha(72).argb, 4F, *points.toTypedArray())
                        env.drawLinesWithWidth(color.argb, 1.35F, *points.toTypedArray())
                    }
                    MarkerStyle.BEACON -> {
                        val beam = arrayOf(Vec3f(0F, 0F, 0F), Vec3f(0F, s * 5F, 0F))
                        env.drawLinesWithWidth(color.alpha(72).argb, 5F, *beam)
                        env.drawLinesWithWidth(color.argb, 1.5F, *beam)
                    }
                    MarkerStyle.CROSS -> {
                        val cross = arrayOf(
                            Vec3f(-s, 0F, 0F), Vec3f(s, 0F, 0F),
                            Vec3f(0F, -s, 0F), Vec3f(0F, s, 0F),
                        )
                        env.drawLinesWithWidth(color.alpha(72).argb, 4F, *cross)
                        env.drawLinesWithWidth(color.argb, 1.35F, *cross)
                    }
                }
                env.poseStack.popPose()
            }
        }
    }

    private enum class MarkerStyle(override val tag: String) : Tagged {
        DIAMOND("Diamond"),
        RING("Ring"),
        BEACON("Beacon"),
        CROSS("Cross"),
    }

    private val show by multiEnumChoice(
        "Show",
        Show.OTHER_PLAYERS,
        Show.ACTIVE_TRAJECTORY_ARROW,
        Show.ACTIVE_TRAJECTORY_OTHER,
    )

    private val alwaysShowBow get() = Show.ALWAYS_SHOW_BOW in show
    private val otherPlayers get() = Show.OTHER_PLAYERS in show
    private val activeTrajectoryArrow get() = Show.ACTIVE_TRAJECTORY_ARROW in show
    private val activeTrajectoryOther get() = Show.ACTIVE_TRAJECTORY_OTHER in show

    init {
        tree(Tracer)
        tree(LandingMarker)
        tree(TrajectoryDetailedInfoRenderer)
    }

    internal val simulationResults =
        mutableListOf<Pair<TrajectoryInfoRenderer, TrajectoryInfoRenderer.SimulationResult>>()

    private data class TrailSnapshot(
        val renderer: TrajectoryInfoRenderer,
        val result: TrajectoryInfoRenderer.SimulationResult,
        val travelled: MutableList<Vec3>,
        var lastSampleTick: Int,
        var lastSeenAt: Long,
    )

    private val realProjectileTrails = mutableMapOf<UUID, TrailSnapshot>()
    private data class PreviewKey(val owner: UUID, val shot: Int)
    private data class SmoothedPreview(val positions: List<Vec3>, val timestamp: Long)
    private val smoothedPreviews = mutableMapOf<PreviewKey, SmoothedPreview>()
    private data class MarkerPosition(val hit: BlockHitResult, val timestamp: Long)
    private val smoothedMarkers = mutableMapOf<PreviewKey, MarkerPosition>()

    override fun onDisabled() {
        simulationResults.clear()
        realProjectileTrails.clear()
        smoothedPreviews.clear()
        smoothedMarkers.clear()
    }

    private val worldChangeHandler = handler<WorldChangeEvent> {
        realProjectileTrails.clear()
        smoothedPreviews.clear()
        smoothedMarkers.clear()
    }

    val renderHandler = handler<WorldRenderEvent> { event ->
        simulationResults.clear()
        renderEnvironmentForWorld(event.matrixStack) {
            val now = System.currentTimeMillis()
            smoothedPreviews.entries.removeIf { now - it.value.timestamp > 500L }
            smoothedMarkers.entries.removeIf { now - it.value.timestamp > 500L }
            val seenProjectiles = HashSet<UUID>()
            val viewPos = player.eyePosition
            val viewDirection = player.rotation.directionVector
            val maxRenderDistanceSq = maxRenderDistance * maxRenderDistance.toDouble()

            for (entity in world.entitiesForRendering()) {
                val delta = entity.position().subtract(viewPos)
                if (delta.lengthSqr() > maxRenderDistanceSq ||
                    cullBehindPlayer && delta.dot(viewDirection) < 0.0 && delta.lengthSqr() > 9.0) {
                    continue
                }

                val (trajectoryInfo, trajectoryType) = EntityTrajectoryResolver.resolveEntityTrajectory(
                    entity,
                    activeTrajectoryArrow,
                    activeTrajectoryOther
                ) ?: continue

                if (trajectoryType !in trajectoryTypes) continue

                val trajectoryRenderer = TrajectoryInfoRenderer(
                    owner = (entity as? TraceableEntity)?.owner ?: entity,
                    icon = TrajectoryDisplayResolver.resolveEntityIcon(
                        entity, activeTrajectoryArrow, activeTrajectoryOther
                    ),
                    velocity = entity.deltaMovement,
                    pos = entity.position(),
                    trajectoryInfo = trajectoryInfo,
                    trajectoryType = trajectoryType,
                    type = TrajectoryInfoRenderer.Type.REAL,
                    renderOffset = Vec3.ZERO,
                )

                val color = TrajectoryDisplayResolver.resolveEntityColor(entity)

                val predictedResult = trajectoryRenderer.runSimulation(maxSimulatedTicks)
                val previous = realProjectileTrails[entity.uuid]
                val travelled = previous?.travelled ?: mutableListOf()
                // Sample vanilla's render interpolation so the path advances between 20 Hz game ticks.
                val currentPosition = entity.interpolateCurrentPosition(event.partialTicks)
                val simulationOffset = currentPosition.subtract(entity.position())
                if (previous == null) {
                    travelled += currentPosition
                } else if (previous.lastSampleTick != entity.tickCount) {
                    if (travelled.lastOrNull()?.distanceToSqr(currentPosition) ?: Double.MAX_VALUE > 0.0004) {
                        travelled += currentPosition
                    }
                    previous.lastSampleTick = entity.tickCount
                } else if (travelled.isNotEmpty()) {
                    // Move the newest control point with the render tick; this avoids 20 Hz stair-steps.
                    travelled[travelled.lastIndex] = currentPosition
                }
                if (travelled.size > 512) travelled.removeAt(0)
                val fullPath = buildList {
                    addAll(travelled)
                    val future = predictedResult.positions.mapIndexed { index, point ->
                        point.add(simulationOffset.scale((1.0 - index / 12.0).coerceIn(0.0, 1.0)))
                    }
                    if (future.isNotEmpty()) {
                        val start = if (lastOrNull()?.distanceToSqr(future.first()) ?: Double.MAX_VALUE < 0.0025) 1 else 0
                        addAll(future.drop(start))
                    }
                }
                val result = predictedResult.copy(
                    positions = fullPath,
                    ticksToImpact = predictedResult.positions.size,
                )
                trajectoryRenderer.drawTrajectoryResult(
                    simulationResult = result,
                    partialTicks = event.partialTicks,
                    trajectoryColor = if (colorFlyingProjectiles) lineColor else color,
                    blockHitColor = null,
                    entityHitColor = null,
                    tracerStyle = Tracer.style().takeIf { Tracer.enabled },
                )
                simulationResults += trajectoryRenderer to result
                seenProjectiles += entity.uuid
                realProjectileTrails[entity.uuid] = TrailSnapshot(
                    trajectoryRenderer, result, travelled, entity.tickCount, now
                )
            }

            if (!Tracer.enabled || !Tracer.linger) {
                realProjectileTrails.keys.retainAll(seenProjectiles)
            } else {
                val lifetimeMillis = (Tracer.lingerSeconds * 1000F).toLong().coerceAtLeast(1L)
                val iterator = realProjectileTrails.iterator()
                while (iterator.hasNext()) {
                    val (uuid, snapshot) = iterator.next()
                    if (uuid in seenProjectiles) continue

                    val age = now - snapshot.lastSeenAt
                    if (age >= lifetimeMillis) {
                        iterator.remove()
                        continue
                    }

                    // Smoothstep fade holds the trail for a beat, then lets it dissolve without a hard edge.
                    val progress = (age.toFloat() / lifetimeMillis).coerceIn(0F, 1F)
                    snapshot.renderer.drawPersistedTrajectory(snapshot.result.positions, Tracer.style(), progress)
                }
            }

            if (otherPlayers) {
                for (otherPlayer in world.players()) {
                    if (otherPlayer !== player) {
                        val delta = otherPlayer.eyePosition.subtract(viewPos)
                        if (delta.lengthSqr() > maxRenderDistanceSq) {
                            continue
                        }
                        if (cullBehindPlayer && delta.dot(viewDirection) < 0.0 && delta.lengthSqr() > 9.0) {
                            continue
                        }
                    }

                    // Including the user
                    drawHypotheticalTrajectory(otherPlayer, event.partialTicks)
                }
            } else {
                drawHypotheticalTrajectory(player, event.partialTicks)
            }
        }

        debugParameter("TrajectoryCount") { simulationResults.size }
    }

    /**
     * Draws the trajectory for an item in the player's hand
     */
    private fun WorldRenderEnvironment.drawHypotheticalTrajectory(
        otherPlayer: Player,
        partialTicks: Float,
    ) {
        val shouldFilterHeldFishingRod = otherPlayer.fishing != null &&
            activeTrajectoryOther &&
            TrajectoryType.FishingBobber in trajectoryTypes

        val (trajectoryShotDescriptors, stack) = otherPlayer.handItems.firstNotNullOfOrNull { stack ->
            if (shouldFilterHeldFishingRod && stack.item is FishingRodItem) {
                return@firstNotNullOfOrNull null
            }

            HeldItemTrajectoryResolver.resolveHeldItemShots(
                otherPlayer,
                stack,
                alwaysShowBow,
                includeMultiShot = showMultiShot
            )?.let {
                it to stack
            }
        } ?: return

        val rotation = if (otherPlayer === player) {
            if (ModuleFreeCam.running) {
                RotationManager.serverRotation
            } else {
                RotationManager.activeRotationTarget?.rotation
                    ?: RotationManager.currentRotation ?: otherPlayer.rotation
            }
        } else {
            otherPlayer.interpolateCurrentRotation(partialTicks)
        }

        trajectoryShotDescriptors.forEachIndexed { shotIndex, shotDescriptor ->
            if (shotDescriptor.trajectoryType !in trajectoryTypes) {
                return@forEachIndexed
            }

            val shotRotation = Rotation(
                yaw = rotation.yaw + shotDescriptor.yawOffsetDegrees,
                pitch = rotation.pitch,
                isNormalized = rotation.isNormalized
            )

            val renderer = TrajectoryInfoRenderer.getHypotheticalTrajectory(
                owner = otherPlayer,
                icon = if (shotDescriptor.icon.isEmpty) stack else shotDescriptor.icon,
                trajectoryInfo = shotDescriptor.trajectoryInfo,
                trajectoryType = shotDescriptor.trajectoryType,
                rotation = shotRotation,
                partialTicks = partialTicks
            )

            val result = renderer.runSimulation(maxSimulatedTicks)
            if (result.positions.isEmpty()) return@forEachIndexed
            val previewKey = PreviewKey(otherPlayer.uuid, shotIndex)
            val now = System.currentTimeMillis()
            val previous = smoothedPreviews[previewKey]
            val sampleCount = 96
            val targetPath = List(sampleCount) { index ->
                val source = index * (result.positions.size - 1).toDouble() / (sampleCount - 1)
                val low = source.toInt()
                val high = (low + 1).coerceAtMost(result.positions.lastIndex)
                result.positions[low].lerp(result.positions[high], source - low)
            }
            val blend = if (previous == null || now - previous.timestamp > 250L) 1.0 else {
                1.0 - exp(-(now - previous.timestamp).coerceAtLeast(1) / 65.0)
            }
            val displayPath = targetPath.mapIndexed { index, point ->
                previous?.positions?.getOrNull(index)?.lerp(point, blend) ?: point
            }
            smoothedPreviews[previewKey] = SmoothedPreview(displayPath, now)
            renderer.drawTrajectoryResult(
                result.copy(positions = displayPath),
                partialTicks,
                trajectoryColor = lineColor,
                blockHitColor = null,
                entityHitColor = null,
                tracerStyle = Tracer.style().takeIf { Tracer.enabled },
            )
            simulationResults += renderer to result
            if (LandingMarker.enabled) {
                val hit = result.hitResult
                if (hit is BlockHitResult) {
                    val old = smoothedMarkers[previewKey]
                    val sameFace = old?.hit?.blockPos == hit.blockPos && old.hit.direction == hit.direction
                    val visualHit = if (sameFace && old != null) {
                        BlockHitResult(old.hit.location.lerp(hit.location, blend), hit.direction, hit.blockPos, hit.isInside)
                    } else {
                        hit
                    }
                    smoothedMarkers[previewKey] = MarkerPosition(visualHit, now)
                    LandingMarker.draw(this, visualHit)
                } else if (hit != null) {
                    LandingMarker.draw(this, hit)
                }
            }
        }
    }

    private enum class Show(
        override val tag: String
    ) : Tagged {
        ALWAYS_SHOW_BOW("AlwaysShowBow"),
        OTHER_PLAYERS("OtherPlayers"),
        ACTIVE_TRAJECTORY_ARROW("ActiveTrajectoryArrow"),
        ACTIVE_TRAJECTORY_OTHER("ActiveTrajectoryOther"),
    }
}
