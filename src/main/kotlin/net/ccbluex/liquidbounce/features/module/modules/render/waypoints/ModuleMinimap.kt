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

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.arrow
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.glassPanel
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.pill
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.text
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.waypointDiamond
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.ConvexClip
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.MapTile
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.WorldMapStore
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimap: the area around the player on the HUD, drawn from the collected world map. Capture and
 * the full map belong to [ModuleWorldMap] (its bind opens the [WorldMapScreen]); this module only
 * displays, so turning it off does not stop the map from filling in.
 *
 * Everything is bound to the server address (see [MapIdentity]); nothing here sends a packet.
 *
 * The map is drawn straight from the per-tile textures: the view shape (circle or rounded square)
 * is mapped into world space, clipped against every tile it overlaps ([ConvexClip]) and each piece
 * is drawn as a textured fan. That is what lets it be round and rotate without an offscreen target.
 */
object ModuleMinimap : ClientModule("Minimap", ModuleCategories.RENDER) {

    private val size by int("Size", 112, 64..256, "px")
    private val shape by enumChoice("Shape", Shape.CIRCLE)
    private val zoomValue = float("Zoom", 1.5f, MIN_ZOOM..MAX_ZOOM, "px/block")
    private val zoom by zoomValue
    private val rotate by boolean("Rotate", true)

    private object Placement : ToggleableValueGroup(this, "Placement", true) {
        val anchor by enumChoice("Anchor", Anchor.TOP_RIGHT)
        val offsetX by int("OffsetX", 8, 0..600, "px")
        val offsetY by int("OffsetY", 8, 0..600, "px")
    }

    private val coordinates by boolean("Coordinates", true)
    private val players by boolean("Players", true)
    private val showWaypoints by boolean("Waypoints", true)

    private val zoomIn by bind("ZoomIn")
    private val zoomOut by bind("ZoomOut")

    private const val MIN_ZOOM = 0.25f
    private const val MAX_ZOOM = 6f
    private const val ZOOM_STEP = 1.5f
    private const val CIRCLE_SEGMENTS = 64
    private const val CORNER_SEGMENTS = 8
    private const val EDGE_MARGIN = 5f

    /** Corner radius of the Square shape as a share of its side: rounded like a Glass HUD card. */
    private const val SQUARE_CORNER = 0.22f

    private enum class Shape(override val tag: String) : Tagged {
        CIRCLE("Circle"),
        SQUARE("Square"),
    }

    private enum class Anchor(override val tag: String) : Tagged {
        TOP_LEFT("TopLeft"),
        TOP_RIGHT("TopRight"),
        BOTTOM_LEFT("BottomLeft"),
        BOTTOM_RIGHT("BottomRight"),
    }

    private val clip = ConvexClip()
    private var shapeX = DoubleArray(CIRCLE_SEGMENTS + 4 * (CORNER_SEGMENTS + 1))
    private var shapeY = DoubleArray(shapeX.size)
    private var worldX = DoubleArray(shapeX.size)
    private var worldZ = DoubleArray(shapeX.size)

    init {
        tree(Placement)
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (event.action != GLFW.GLFW_PRESS || mc.screen != null) return@handler

        when {
            zoomIn.matchesKeyPress(event) -> zoomValue.set((zoom * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM))
            zoomOut.matchesKeyPress(event) -> zoomValue.set((zoom / ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM))
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent>(priority = EventPriorityConvention.MODEL_STATE) { event ->
        if (HideAppearance.isHidingNow || mc.options.hideGui || mc.screen is WorldMapScreen) {
            return@handler
        }
        WorldMapStore.beginFrame()
        draw(event.context, event.tickDelta)
    }

    private fun draw(ctx: GuiGraphicsExtractor, partialTicks: Float) {
        val side = size.toFloat()
        val radius = side / 2f
        val textPx = 8f
        val coordsHeight = if (coordinates) WaypointGraphics.textHeight(textPx) + 8f else 0f

        val screenW = mc.window.guiScaledWidth.toFloat()
        val screenH = mc.window.guiScaledHeight.toFloat()
        val anchor = if (Placement.enabled) Placement.anchor else Anchor.TOP_RIGHT
        val offsetX = if (Placement.enabled) Placement.offsetX.toFloat() else 8f
        val offsetY = if (Placement.enabled) Placement.offsetY.toFloat() else 8f

        val left = when (anchor) {
            Anchor.TOP_LEFT, Anchor.BOTTOM_LEFT -> offsetX
            Anchor.TOP_RIGHT, Anchor.BOTTOM_RIGHT -> screenW - offsetX - side
        }
        val top = when (anchor) {
            Anchor.TOP_LEFT, Anchor.TOP_RIGHT -> offsetY
            Anchor.BOTTOM_LEFT, Anchor.BOTTOM_RIGHT -> screenH - offsetY - side - coordsHeight
        }
        val cx = left + radius
        val cy = top + radius

        val position = player.getPosition(partialTicks)
        val yaw = player.getViewYRot(partialTicks).toDouble() * PI / 180.0
        val theta = if (rotate) PI - yaw else 0.0
        val scale = zoom.toDouble()
        val circle = shape == Shape.CIRCLE
        val corner = if (circle) radius else side * SQUARE_CORNER
        val glass = WaypointGraphics.glass(ctx)

        // A Glass HUD card under the map: unexplored ground shows the blurred world through it.
        ctx.glassPanel(left, top, left + side, top + side, corner, glass)

        val count = buildShape(circle, radius, corner)
        drawMap(ctx, cx, cy, count, theta, scale, position)

        val bounds = ctx.getBounds(left, top, left + side, top + side)
        ctx.scissorStack.push(bounds)
        try {
            if (players) drawPlayers(ctx, cx, cy, radius, circle, theta, scale, position, partialTicks)
        } finally {
            ctx.scissorStack.pop()
        }
        if (showWaypoints) drawWaypoints(ctx, cx, cy, radius, circle, theta, scale, position)

        // The player: always the centre.
        ctx.arrow(cx, cy, (yaw + PI + theta).toFloat(), 5f, 0xFFFFFFFF.toInt(), 0xE0101318.toInt())

        // Hairline rim over the map's edge, as on every Glass HUD card.
        ctx.drawRoundedRect(
            left, top, left + side, top + side, corner,
            fillColor = null, outlineColor = Color4b(0x38FFFFFF), outlineWidth = 1f,
        )

        drawNorth(ctx, cx, cy, radius, circle, theta, glass)

        if (coordinates) {
            val block = player.blockPosition()
            val label = "${block.x}  ${block.y}  ${block.z}"
            ctx.pill(label, cx, top + side + 5f, textPx, WaypointGraphics.TEXT, glass = glass)
        }
    }

    /**
     * The view outline relative to the centre, in screen units, as a convex polygon.
     */
    private fun buildShape(circle: Boolean, radius: Float, corner: Float): Int {
        if (circle) {
            for (i in 0 until CIRCLE_SEGMENTS) {
                val angle = 2.0 * PI * i / CIRCLE_SEGMENTS
                shapeX[i] = cos(angle) * radius
                shapeY[i] = sin(angle) * radius
            }
            return CIRCLE_SEGMENTS
        }

        val inner = (radius - corner).toDouble()
        val centres = arrayOf(
            doubleArrayOf(inner, inner),
            doubleArrayOf(-inner, inner),
            doubleArrayOf(-inner, -inner),
            doubleArrayOf(inner, -inner),
        )
        var n = 0
        for ((index, centre) in centres.withIndex()) {
            val start = index * PI / 2.0
            for (step in 0..CORNER_SEGMENTS) {
                val angle = start + (PI / 2.0) * step / CORNER_SEGMENTS
                shapeX[n] = centre[0] + cos(angle) * corner
                shapeY[n] = centre[1] + sin(angle) * corner
                n++
            }
        }
        return n
    }

    @Suppress("LongParameterList")
    private fun drawMap(
        ctx: GuiGraphicsExtractor,
        cx: Float,
        cy: Float,
        count: Int,
        theta: Double,
        scale: Double,
        position: Vec3,
    ) {
        val cos = cos(theta)
        val sin = sin(theta)

        // Screen offset -> world offset: inverse rotation, then un-scale.
        var minX = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (i in 0 until count) {
            val wx = (cos * shapeX[i] + sin * shapeY[i]) / scale
            val wz = (-sin * shapeX[i] + cos * shapeY[i]) / scale
            worldX[i] = wx
            worldZ[i] = wz
            minX = minOf(minX, wx)
            maxX = maxOf(maxX, wx)
            minZ = minOf(minZ, wz)
            maxZ = maxOf(maxZ, wz)
        }

        val linear = scale * mc.window.guiScale < 1.0
        val size = MapTile.SIZE.toDouble()
        val rx0 = floor((position.x + minX) / size).toInt()
        val rx1 = floor((position.x + maxX) / size).toInt()
        val rz0 = floor((position.z + minZ) / size).toInt()
        val rz1 = floor((position.z + maxZ) / size).toInt()

        for (rz in rz0..rz1) {
            for (rx in rx0..rx1) {
                val tile = WorldMapStore.tile(rx, rz) ?: continue
                val tx0 = rx * size - position.x
                val tz0 = rz * size - position.z
                val n = clip.clip(worldX, worldZ, count, tx0, tz0, tx0 + size, tz0 + size)
                if (n < 3) continue

                // The vertex callback runs later, when the GUI is rendered - after the clipper has
                // been reused for the next tile. Bake everything it needs into arrays of its own.
                val xs = clip.xs
                val zs = clip.ys
                val sx = FloatArray(n)
                val sy = FloatArray(n)
                val u = FloatArray(n)
                val v = FloatArray(n)
                for (i in 0 until n) {
                    sx[i] = (cx + (cos * xs[i] - sin * zs[i]) * scale).toFloat()
                    sy[i] = (cy + (sin * xs[i] + cos * zs[i]) * scale).toFloat()
                    u[i] = ((xs[i] - tx0) / size).toFloat()
                    v[i] = ((zs[i] - tz0) / size).toFloat()
                }

                // GuiRenderState drops an element without bounds, and layers by them.
                val bounds = ctx.getBounds(sx.min(), sy.min(), sx.max(), sy.max())
                val setup = tile.prepare(linear)
                ctx.drawCustomElement(RenderPipelines.GUI_TEXTURED, setup, bounds = bounds) { pose ->
                    WaypointGraphics.fan(this, pose, sx, sy, u, v, n)
                }
            }
        }
    }

    /** World offset -> screen offset. */
    private fun project(dx: Double, dz: Double, theta: Double, scale: Double): Pair<Float, Float> {
        val cos = cos(theta)
        val sin = sin(theta)
        return ((cos * dx - sin * dz) * scale).toFloat() to ((sin * dx + cos * dz) * scale).toFloat()
    }

    private fun isInside(ox: Float, oy: Float, radius: Float, circle: Boolean, margin: Float): Boolean {
        val limit = radius - margin
        return if (circle) ox * ox + oy * oy <= limit * limit else max(abs(ox), abs(oy)) <= limit
    }

    private fun clampToEdge(ox: Float, oy: Float, radius: Float, circle: Boolean, margin: Float): Pair<Float, Float> {
        val limit = radius - margin
        val length = if (circle) sqrt(ox * ox + oy * oy) else max(abs(ox), abs(oy))
        if (length < 1e-4f) return 0f to 0f
        return ox * limit / length to oy * limit / length
    }

    @Suppress("LongParameterList")
    private fun drawPlayers(
        ctx: GuiGraphicsExtractor,
        cx: Float,
        cy: Float,
        radius: Float,
        circle: Boolean,
        theta: Double,
        scale: Double,
        position: Vec3,
        partialTicks: Float,
    ) {
        for (other in world.players()) {
            if (other === player) continue
            val at = other.getPosition(partialTicks)
            val (ox, oy) = project(at.x - position.x, at.z - position.z, theta, scale)
            if (!isInside(ox, oy, radius, circle, 2f)) continue
            val x = cx + ox
            val y = cy + oy
            ctx.drawRoundedRect(
                x - 2.2f, y - 2.2f, x + 2.2f, y + 2.2f, 2.2f,
                fillColor = Color4b(0xFFFFFFFF.toInt()), outlineColor = Color4b(0xE0101318.toInt()), outlineWidth = 1f,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun drawWaypoints(
        ctx: GuiGraphicsExtractor,
        cx: Float,
        cy: Float,
        radius: Float,
        circle: Boolean,
        theta: Double,
        scale: Double,
        position: Vec3,
    ) {
        val dimension = WorldMapStore.dimension ?: MapIdentity.currentDimension() ?: return
        for (waypoint in WaypointStore.inDimension(dimension)) {
            if (!waypoint.visible) continue
            val (ox, oy) = project(waypoint.centerX - position.x, waypoint.centerZ - position.z, theta, scale)
            if (isInside(ox, oy, radius, circle, EDGE_MARGIN)) {
                ctx.waypointDiamond(cx + ox, cy + oy, 4f, waypoint.color)
            } else {
                val (ex, ey) = clampToEdge(ox, oy, radius, circle, EDGE_MARGIN)
                ctx.waypointDiamond(cx + ex, cy + ey, 3f, waypoint.color, alpha = 0.85f)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun drawNorth(
        ctx: GuiGraphicsExtractor,
        cx: Float,
        cy: Float,
        radius: Float,
        circle: Boolean,
        theta: Double,
        glass: TextureSetup?,
    ) {
        // World north (-z) on screen: rotation applied to (0, -1).
        var nx = sin(theta).toFloat()
        var ny = -cos(theta).toFloat()
        if (!circle) {
            val (ex, ey) = clampToEdge(nx * radius, ny * radius, radius, false, 0f)
            nx = ex / radius
            ny = ey / radius
        }
        val x = cx + nx * radius
        val y = cy + ny * radius
        val badge = 6f
        ctx.glassPanel(x - badge, y - badge, x + badge, y + badge, badge, glass, shadow = false)
        val px = 7f
        val width = WaypointGraphics.textWidth("N", px, bold = true)
        ctx.text("N", x - width / 2f, y - WaypointGraphics.textHeight(px) / 2f, px, 0xFFFF7A7A.toInt(), bold = true)
    }
}
