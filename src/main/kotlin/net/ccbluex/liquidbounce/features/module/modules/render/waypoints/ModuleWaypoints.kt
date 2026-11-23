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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.pill
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.waypointDiamond
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Waypoints: shows the waypoints of the current server in the world.
 *
 * The look ("beacon pin"), top to bottom:
 * - a soft BEAM of the waypoint's colour rising from the marked block and fading out upwards: a
 *   thin bright core inside a wide faint halo, additive, drawn through terrain so a place behind a
 *   hill can still be found. It breathes slowly, and fades away when the player stands in it;
 * - a faceted DIAMOND on the screen where the place is, the same marker the minimap and the world
 *   map use, with a glass pill under it: name and distance;
 * - LOOK AT IT (the marker near the crosshair) and the pill grows a second line with the
 *   coordinates - details only when asked for, so twenty waypoints do not bury the screen;
 * - near the place a slowly turning dashed RING lies on the ground (depth-tested: it is a floor
 *   marking, not a hint through walls), and the screen marker fades out so it does not sit in the
 *   player's face.
 *
 * Waypoints are managed on the world map (Minimap -> OpenMap), with `.waypoint`, or dropped on the
 * spot with [addKey]. Nothing here sends a packet.
 */
object ModuleWaypoints : ClientModule("Waypoints", ModuleCategories.RENDER, aliases = listOf("Waypoint")) {

    private val markerScale by float("Scale", 1f, 0.5f..2f)

    private object Beam : ToggleableValueGroup(this, "Beam", true) {
        val opacity by float("Opacity", 0.6f, 0.1f..1f)
    }

    private object Labels : ToggleableValueGroup(this, "Labels", true) {
        val distance by boolean("Distance", true)
        val coordinatesOnFocus by boolean("CoordinatesOnFocus", true)
    }

    private object Limit : ToggleableValueGroup(this, "Limit", false) {
        val distance by int("Distance", 2000, 16..50000, "blocks")
    }

    private val addKey by bind("AddKey")

    /** The beam is drawn at most this far; farther ones are scaled towards the camera (same picture). */
    private const val BEAM_DRAW_DISTANCE = 160.0
    private const val BEAM_HEIGHT = 256.0
    private const val RING_DISTANCE = 48.0
    private const val RING_DASHES = 12
    private const val FOCUS_RADIUS = 40f

    /** Screen markers are projected from at most this far (and inside the far plane, see [safeDistance]). */
    private const val PROJECT_DISTANCE = 128.0

    /**
     * Depth-far is `max(renderDistance * 16 * 4, cloudRange * 16)` (`Camera.update`); 80 % of its lower
     * bound. At render distance 2 that is ~102 blocks, less than both constants above.
     */
    private val safeDistance: Double
        get() = mc.options.effectiveRenderDistance * 16.0 * 4.0 * 0.8

    init {
        tree(Beam)
        tree(Labels)
        tree(Limit)
    }

    private fun candidates(): List<Waypoint> {
        val dimension = MapIdentity.currentDimension() ?: return emptyList()
        return WaypointStore.inDimension(dimension).filter { it.visible }
    }

    private fun inRange(distance: Double) = !Limit.enabled || distance <= Limit.distance

    // ------------------------------------------------------------------------------------------
    //  Quick add
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (mc.screen != null || !addKey.matchesKeyPress(event)) return@handler
        val block = player.blockPosition()
        val dimension = MapIdentity.currentDimension() ?: return@handler
        val waypoint = WaypointStore.add(WaypointStore.uniqueName(), block.x, block.y, block.z, dimension)
        if (waypoint == null) {
            chat(markAsError("Could not add a waypoint: not connected to a world."))
        } else {
            chat("§b[Waypoints] §7Added §f${waypoint.name}§7 at §f${block.x} ${block.y} ${block.z}")
        }
    }

    // ------------------------------------------------------------------------------------------
    //  World: beam and ground ring
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        if (HideAppearance.isHidingNow) return@handler
        val waypoints = candidates()
        if (waypoints.isEmpty()) return@handler

        val camera = event.camera.position()
        val origin = player.position()
        val seconds = System.nanoTime() / 1e9

        renderEnvironmentForWorld(event.matrixStack) {
            if (Beam.enabled) {
                drawCustomMesh(ClientRenderPipelines.GlowTrianglesThroughWalls) { pose ->
                    for (waypoint in waypoints) {
                        if (!inRange(waypoint.distanceTo(origin.x, origin.y, origin.z))) continue
                        addBeam(this, pose, waypoint, camera, seconds)
                    }
                }
            }

            drawCustomMesh(ClientRenderPipelines.GlowTriangles) { pose ->
                for (waypoint in waypoints) {
                    val distance = waypoint.distanceTo(origin.x, origin.y, origin.z)
                    if (distance > RING_DISTANCE || !inRange(distance)) continue
                    addRing(this, pose, waypoint, camera, seconds, distance)
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun addBeam(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        waypoint: Waypoint,
        camera: Vec3,
        seconds: Double,
    ) {
        val rx = waypoint.centerX - camera.x
        val rz = waypoint.centerZ - camera.z
        val horizontal = sqrt(rx * rx + rz * rz)

        // Scaling the whole beam about the eye keeps its picture and keeps it inside the far plane.
        val limit = minOf(BEAM_DRAW_DISTANCE, safeDistance)
        val scale = if (horizontal > limit) limit / horizontal else 1.0

        // Billboard: the beam's width runs across the line of sight.
        val (rightX, rightZ) = if (horizontal > 1e-3) {
            -rz / horizontal to rx / horizontal
        } else {
            1.0 to 0.0
        }

        val breath = 0.85 + 0.15 * sin(seconds * 2.2 + waypoint.x * 0.1)
        // Fade out when standing inside it, instead of a wall of colour.
        val proximity = (horizontal / 3.0).coerceIn(0.0, 1.0)
        val alpha = Beam.opacity * breath * (0.25 + 0.75 * proximity)

        val bottom = waypoint.y - camera.y
        val middle = bottom + BEAM_HEIGHT * 0.3
        val top = bottom + BEAM_HEIGHT

        val halo = maxOf(0.55, horizontal * 0.0065)
        val core = maxOf(0.08, horizontal * 0.0013)

        val color = waypoint.color
        beamLayer(consumer, pose, rx, rz, rightX, rightZ, halo, bottom, middle, top, scale, color, alpha * 0.28)
        beamLayer(consumer, pose, rx, rz, rightX, rightZ, core, bottom, middle, top, scale, color, alpha)
    }

    /**
     * One layer of the beam: bright on its centre line, transparent at its edges and at its top.
     */
    @Suppress("LongParameterList")
    private fun beamLayer(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        cx: Double,
        cz: Double,
        rightX: Double,
        rightZ: Double,
        halfWidth: Double,
        bottom: Double,
        middle: Double,
        top: Double,
        scale: Double,
        color: Int,
        alpha: Double,
    ) {
        val strong = WaypointGraphics.withAlpha(color, alpha.toFloat())
        val soft = WaypointGraphics.withAlpha(color, (alpha * 0.6).toFloat())
        val clear = color and 0x00FFFFFF

        val heights = doubleArrayOf(bottom, middle, top)
        val centreColors = intArrayOf(strong, soft, clear)

        for (side in intArrayOf(-1, 1)) {
            val ex = cx + rightX * halfWidth * side
            val ez = cz + rightZ * halfWidth * side
            for (segment in 0..1) {
                val y0 = heights[segment]
                val y1 = heights[segment + 1]
                val c0 = centreColors[segment]
                val c1 = centreColors[segment + 1]

                vertex(consumer, pose, cx, y0, cz, scale, c0)
                vertex(consumer, pose, ex, y0, ez, scale, clear)
                vertex(consumer, pose, ex, y1, ez, scale, clear)

                vertex(consumer, pose, ex, y1, ez, scale, clear)
                vertex(consumer, pose, cx, y1, cz, scale, c1)
                vertex(consumer, pose, cx, y0, cz, scale, c0)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun addRing(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        waypoint: Waypoint,
        camera: Vec3,
        seconds: Double,
        distance: Double,
    ) {
        val cx = waypoint.centerX - camera.x
        val cz = waypoint.centerZ - camera.z
        val y = waypoint.y + 0.02 - camera.y
        val fade = (1.0 - distance / RING_DISTANCE).coerceIn(0.0, 1.0)
        val color = WaypointGraphics.withAlpha(waypoint.color, (0.7 * fade).toFloat())
        val inner = 0.62
        val outer = 0.78
        val spin = seconds * 0.6
        val dash = 2.0 * PI / RING_DASHES

        for (i in 0 until RING_DASHES) {
            val a0 = spin + i * dash
            val a1 = a0 + dash * 0.6
            vertex(consumer, pose, cx + cos(a0) * inner, y, cz + sin(a0) * inner, 1.0, color)
            vertex(consumer, pose, cx + cos(a0) * outer, y, cz + sin(a0) * outer, 1.0, color)
            vertex(consumer, pose, cx + cos(a1) * outer, y, cz + sin(a1) * outer, 1.0, color)

            vertex(consumer, pose, cx + cos(a1) * outer, y, cz + sin(a1) * outer, 1.0, color)
            vertex(consumer, pose, cx + cos(a1) * inner, y, cz + sin(a1) * inner, 1.0, color)
            vertex(consumer, pose, cx + cos(a0) * inner, y, cz + sin(a0) * inner, 1.0, color)
        }
    }

    @Suppress("LongParameterList")
    private fun vertex(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        x: Double,
        y: Double,
        z: Double,
        scale: Double,
        color: Int,
    ) {
        consumer.addVertex(pose, (x * scale).toFloat(), (y * scale).toFloat(), (z * scale).toFloat()).setColor(color)
    }

    // ------------------------------------------------------------------------------------------
    //  Screen: marker and label
    // ------------------------------------------------------------------------------------------

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (HideAppearance.isHidingNow || mc.options.hideGui) return@handler
        val waypoints = candidates()
        if (waypoints.isEmpty()) return@handler

        val ctx = event.context
        val camera = WorldToScreen.cameraPosition
        // Glass HUD capsules with the blurred world behind them (none while a screen is open). Asked
        // for on the first label drawn: a frame capture costs a full-screen blur.
        val glass by lazy(LazyThreadSafetyMode.NONE) { WaypointGraphics.glass(ctx) }
        val origin = player.getPosition(event.tickDelta)
        val centerX = mc.window.guiScaledWidth / 2f
        val centerY = mc.window.guiScaledHeight / 2f

        // Far first, so nearer markers are drawn over them.
        val ordered = waypoints
            .map { it to it.distanceTo(origin.x, origin.y, origin.z) }
            .filter { (_, distance) -> inRange(distance) }
            .sortedByDescending { (_, distance) -> distance }

        for ((waypoint, distance) in ordered) {
            val anchor = Vec3(waypoint.centerX, waypoint.y + 1.0, waypoint.centerZ)
            val relative = anchor.subtract(camera)
            val length = relative.length()
            val projectLimit = minOf(PROJECT_DISTANCE, safeDistance)
            val projected = if (length > projectLimit) {
                camera.add(relative.scale(projectLimit / length))
            } else {
                anchor
            }
            val screen = WorldToScreen.calculateScreenPos(projected) ?: continue

            val alpha = smoothstep(1.5, 5.0, distance).toFloat()
            if (alpha <= 0.02f) continue

            val x = screen.x
            val y = screen.y
            val scale = markerScale
            val focused = (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= FOCUS_RADIUS * FOCUS_RADIUS

            if (focused) {
                // A halo behind the marker while it is looked at.
                ctx.waypointDiamond(x, y, 10f * scale, waypoint.color, alpha = 0.22f * alpha)
            }
            ctx.waypointDiamond(x, y, (if (focused) 6.5f else 5.5f) * scale, waypoint.color, alpha)

            if (!Labels.enabled) continue

            val label = if (Labels.distance) {
                "${waypoint.name}  ${WaypointGraphics.formatDistance(distance)}"
            } else {
                waypoint.name
            }
            // Whole GUI units only: every distinct size bakes a glyph atlas (WatermarkFont), and a
            // float slider would otherwise bake one per step while dragged.
            val px = (8f * scale).roundToInt().coerceAtLeast(5).toFloat()
            var top = y + 9f * scale
            top += ctx.pill(
                label, x, top, px, WaypointGraphics.TEXT, alpha,
                accent = if (focused) waypoint.color else null,
                glass = glass,
            ) + 2f

            if (focused && Labels.coordinatesOnFocus) {
                val coordinates = "${waypoint.x}  ${waypoint.y}  ${waypoint.z}"
                val smallPx = (7f * scale).roundToInt().coerceAtLeast(5).toFloat()
                ctx.pill(coordinates, x, top, smallPx, WaypointGraphics.TEXT_DIM, alpha, glass = glass)
            }
        }
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
