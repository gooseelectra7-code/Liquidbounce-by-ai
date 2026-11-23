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

import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.ClickGuiSearchAware
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.arrow
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.glassPanel
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.pill
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.text
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics.waypointDiamond
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.MapTile
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.WorldMapStore
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The full world map: the collected map of the current server and dimension, the waypoints on it,
 * and the place to manage them.
 *
 * - drag with the left button to move, wheel to zoom around the cursor;
 * - right-click the map to put a waypoint there (Y comes from the captured height, else the
 *   player's), left-click a marker to edit it;
 * - the list on the left jumps to a waypoint, `+ Here` marks where the player stands;
 * - Space glides back to the player, Esc (or the WorldMap module's bind) closes.
 *
 * The map keeps updating while it is open: capture runs on the tick, not on this screen.
 */
class WorldMapScreen : Screen(Component.literal("World Map")), ClickGuiSearchAware {

    private companion object {
        /** Zoom survives reopening; the centre always starts on the player. */
        var savedZoom = 2.0

        const val MIN_ZOOM = 0.25

        /** Tiles a full view may need; the store keeps 64 resident and has no coarser level. */
        const val MAX_VIEW_TILES = 36.0
        const val MAX_ZOOM = 16.0
        const val WHEEL_STEP = 1.25
        const val ZOOM_TIME = 0.08
        const val GLIDE_TIME = 0.12

        const val MARGIN = 8f
        /** Height of the floating title/readout capsules; panels start under them. */
        const val CAPSULE_HEIGHT = 20f
        const val TOP_BAR = 8f + CAPSULE_HEIGHT
        const val TITLE_PX = 9f
        const val INFO_PX = 8f
        const val PANEL_WIDTH = 160f
        const val EDITOR_WIDTH = 196f
        const val ROW_HEIGHT = 18f
        const val FIELD_HEIGHT = 18f
        const val PAD = 10f
        const val DRAG_THRESHOLD = 3.0
        const val PICK_RADIUS = 8f
        const val GRID_MIN_ZOOM = 4.0

        const val BACKDROP = 0xFF07090C.toInt()
        const val GRID = 0x16FFFFFF
        const val ERROR = 0xFFFF6B6B.toInt()
    }

    private class Button(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val action: () -> Unit) {
        fun contains(x: Double, y: Double) = x >= x0 && x <= x1 && y >= y0 && y <= y1
    }

    private var centerX = 0.0
    private var centerZ = 0.0
    private var zoom = savedZoom
    private var targetZoom = savedZoom

    private var anchorActive = false
    private var anchorWorldX = 0.0
    private var anchorWorldZ = 0.0
    private var anchorScreenX = 0.0
    private var anchorScreenY = 0.0

    private var glideActive = false
    private var glideX = 0.0
    private var glideZ = 0.0

    private var lastNanos = System.nanoTime()
    private var centred = false

    /**
     * The WorldMap bind closes the map only once its key has been released since opening: vanilla
     * forwards key REPEAT events to the screen (`KeyboardHandler.keyPress`, action 2), so holding
     * the key past the OS repeat delay would open the map and close it again.
     */
    private var closeArmed = !bindKeyHeld()

    private fun bindKeyHeld(): Boolean {
        val key = ModuleWorldMap.bind.boundKey
        return key.type == InputConstants.Type.KEYSYM && key.value > 0 &&
            InputConstants.isKeyDown(mc.window, key.value)
    }

    private var pressOnMap = false
    private var pressX = 0.0
    private var pressY = 0.0
    private var dragged = false

    private var selected: Waypoint? = null
    private var editor: Editor? = null

    private var listScroll = 0f
    private var listMaxScroll = 0f
    private var listArea: ScreenRectangle? = null
    private var editorArea: ScreenRectangle? = null

    private val buttons = ArrayList<Button>()

    /** The floating top capsules of this frame: the map under them is not clickable. */
    private val topAreas = ArrayList<ScreenRectangle>()

    private val dimension: String
        get() = WorldMapStore.dimension ?: MapIdentity.currentDimension() ?: "minecraft:overworld"

    // ------------------------------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------------------------------

    override fun init() {
        if (!centred) {
            centred = true
            mc.player?.let {
                centerX = it.x
                centerZ = it.z
            }
        }
        // A resize rebuilds the widget list; the editor's fields have to come back.
        editor?.attach()
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * InventoryMove passes movement keys through in any non-container screen; while a field of the
     * editor has the keyboard, typing a name must not walk the player (`ModuleClickGui.isInSearchBar`).
     */
    override val isSearchFocused: Boolean
        get() = editor?.anyFocused == true

    override fun removed() {
        savedZoom = targetZoom
        super.removed()
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        // No blur, no vanilla menu background: the map is the background.
    }

    // ------------------------------------------------------------------------------------------
    //  Coordinates
    // ------------------------------------------------------------------------------------------

    private fun worldX(screenX: Double) = centerX + (screenX - width / 2.0) / zoom
    private fun worldZ(screenY: Double) = centerZ + (screenY - height / 2.0) / zoom
    private fun screenX(worldX: Double) = (width / 2.0 + (worldX - centerX) * zoom).toFloat()
    private fun screenY(worldZ: Double) = (height / 2.0 + (worldZ - centerZ) * zoom).toFloat()

    private fun overPanels(x: Double, y: Double): Boolean {
        for (area in topAreas) {
            if (area.containsPoint(x.toInt(), y.toInt())) return true
        }
        listArea?.let { if (it.containsPoint(x.toInt(), y.toInt())) return true }
        editorArea?.let { if (it.containsPoint(x.toInt(), y.toInt())) return true }
        return false
    }

    // ------------------------------------------------------------------------------------------
    //  Frame
    // ------------------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9).coerceIn(0.0, 0.1)
        lastNanos = now
        animate(dt)

        WorldMapStore.beginFrame()
        buttons.clear()
        layoutTopBar(mouseX, mouseY)

        graphics.fill(0, 0, width, height, BACKDROP)
        drawTiles(graphics)
        drawGrid(graphics)

        val hovered = if (!overPanels(mouseX.toDouble(), mouseY.toDouble())) {
            pickWaypoint(mouseX.toFloat(), mouseY.toFloat())
        } else {
            null
        }
        drawWaypoints(graphics, hovered)
        drawPlayers(graphics, a)

        drawTopBar(graphics)
        drawList(graphics, mouseX, mouseY)
        editor?.draw(graphics, mouseX, mouseY) ?: run { editorArea = null }
        drawHint(graphics)

        if (hovered != null && editor?.target !== hovered) {
            drawTooltip(graphics, hovered, mouseX.toFloat(), mouseY.toFloat())
        }

        // Vanilla widgets (the editor's text fields) on top of everything above.
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    /**
     * How far out the map may zoom on this window. Every tile in view is held in memory (there is no
     * downsampled level), so a large GUI area gets a higher floor; at 960x540 GUI units it is 0.25.
     */
    private fun minZoom(): Double {
        val tileArea = MapTile.SIZE.toDouble() * MapTile.SIZE
        return maxOf(MIN_ZOOM, sqrt(width.toDouble() * height / (tileArea * MAX_VIEW_TILES)))
    }

    private fun animate(dt: Double) {
        val floor = minZoom()
        if (targetZoom < floor) targetZoom = floor
        if (abs(zoom - targetZoom) > 1e-4) {
            val k = 1.0 - exp(-dt / ZOOM_TIME)
            zoom = exp(ln(zoom) + (ln(targetZoom) - ln(zoom)) * k)
            if (anchorActive) {
                centerX = anchorWorldX - (anchorScreenX - width / 2.0) / zoom
                centerZ = anchorWorldZ - (anchorScreenY - height / 2.0) / zoom
            }
        } else {
            zoom = targetZoom
            anchorActive = false
        }

        if (glideActive) {
            val k = 1.0 - exp(-dt / GLIDE_TIME)
            centerX += (glideX - centerX) * k
            centerZ += (glideZ - centerZ) * k
            if (abs(glideX - centerX) * zoom < 0.5 && abs(glideZ - centerZ) * zoom < 0.5) {
                centerX = glideX
                centerZ = glideZ
                glideActive = false
            }
        }
    }

    private fun glideTo(x: Double, z: Double) {
        glideX = x
        glideZ = z
        glideActive = true
        anchorActive = false
    }

    private fun drawTiles(graphics: GuiGraphicsExtractor) {
        val size = MapTile.SIZE.toDouble()
        val rx0 = floor(worldX(0.0) / size).toInt()
        val rx1 = floor(worldX(width.toDouble()) / size).toInt()
        val rz0 = floor(worldZ(0.0) / size).toInt()
        val rz1 = floor(worldZ(height.toDouble()) / size).toInt()
        val linear = zoom * mc.window.guiScale < 1.0

        for (rz in rz0..rz1) {
            for (rx in rx0..rx1) {
                val tile = WorldMapStore.tile(rx, rz) ?: continue
                val setup = tile.prepare(linear)
                graphics.drawTexQuad(
                    setup,
                    screenX(rx * size), screenY(rz * size),
                    screenX((rx + 1) * size), screenY((rz + 1) * size),
                )
            }
        }
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor) {
        if (zoom < GRID_MIN_ZOOM) return
        val color = Color4b(GRID)
        val startX = floor(worldX(0.0) / 16.0).toInt()
        val endX = floor(worldX(width.toDouble()) / 16.0).toInt()
        for (cx in startX..endX + 1) {
            val x = screenX(cx * 16.0)
            graphics.drawQuad(x, 0f, x + 1f, height.toFloat(), color)
        }
        val startZ = floor(worldZ(0.0) / 16.0).toInt()
        val endZ = floor(worldZ(height.toDouble()) / 16.0).toInt()
        for (cz in startZ..endZ + 1) {
            val y = screenY(cz * 16.0)
            graphics.drawQuad(0f, y, width.toFloat(), y + 1f, color)
        }
    }

    private fun visibleWaypoints(): List<Waypoint> = WaypointStore.inDimension(dimension)

    private fun pickWaypoint(mouseX: Float, mouseY: Float): Waypoint? {
        var best: Waypoint? = null
        var bestDistance = PICK_RADIUS * PICK_RADIUS
        for (waypoint in visibleWaypoints()) {
            val dx = screenX(waypoint.centerX) - mouseX
            val dy = screenY(waypoint.centerZ) - mouseY
            val distance = dx * dx + dy * dy
            if (distance <= bestDistance) {
                bestDistance = distance
                best = waypoint
            }
        }
        return best
    }

    private fun drawWaypoints(graphics: GuiGraphicsExtractor, hovered: Waypoint?) {
        val pulse = (0.5 + 0.5 * sin(System.nanoTime() / 1e9 * 4.0)).toFloat()
        val labelPx = 8f
        val showLabels = zoom >= 0.5

        for (waypoint in visibleWaypoints()) {
            val x = screenX(waypoint.centerX)
            val y = screenY(waypoint.centerZ)
            if (x < -40f || y < -40f || x > width + 40f || y > height + 40f) continue

            val isSelected = waypoint === selected || waypoint === editor?.target
            val alpha = if (waypoint.visible) 1f else 0.45f

            if (isSelected) {
                graphics.waypointDiamond(x, y, 10f + pulse * 2f, waypoint.color, alpha = 0.18f + pulse * 0.12f)
            }
            val radius = if (waypoint === hovered || isSelected) 6.5f else 5f
            graphics.waypointDiamond(x, y, radius, waypoint.color, alpha)

            if (showLabels || waypoint === hovered || isSelected) {
                val name = waypoint.name
                val width = WaypointGraphics.textWidth(name, labelPx)
                val top = y + radius + 3f
                val shadow = WaypointGraphics.withAlpha(0xE0000000.toInt(), alpha)
                val ink = WaypointGraphics.withAlpha(WaypointGraphics.TEXT, alpha)
                graphics.text(name, x - width / 2f + 0.8f, top + 0.8f, labelPx, shadow)
                graphics.text(name, x - width / 2f, top, labelPx, ink)
            }
        }
    }

    private fun drawPlayers(graphics: GuiGraphicsExtractor, partialTicks: Float) {
        val self = mc.player ?: return
        val level = mc.level ?: return
        if (level.dimension().identifier().toString() != dimension) return

        for (other in level.players()) {
            if (other === self) continue
            val at = other.getPosition(partialTicks)
            val x = screenX(at.x)
            val y = screenY(at.z)
            graphics.drawRoundedRect(
                x - 2.5f, y - 2.5f, x + 2.5f, y + 2.5f, 2.5f,
                fillColor = Color4b(0xFFFFFFFF.toInt()), outlineColor = Color4b(0xE0101318.toInt()), outlineWidth = 1f,
            )
        }

        val at = self.getPosition(partialTicks)
        val yaw = self.getViewYRot(partialTicks) * PI / 180.0
        graphics.arrow(screenX(at.x), screenY(at.z), (yaw + PI).toFloat(), 6f, 0xFFFFFFFF.toInt(), 0xE0101318.toInt())
    }

    private fun drawTooltip(graphics: GuiGraphicsExtractor, waypoint: Waypoint, mouseX: Float, mouseY: Float) {
        val player = mc.player ?: return
        val distance = waypoint.distanceTo(player.x, player.y, player.z)
        val line = "${waypoint.name}   ${waypoint.x}, ${waypoint.y}, ${waypoint.z}   " +
            WaypointGraphics.formatDistance(distance)
        graphics.pill(line, mouseX, mouseY - 26f, 8f, WaypointGraphics.TEXT, accent = waypoint.color)
    }

    private class TopBar(
        val leftEnd: Float,
        val rightStart: Float,
        val title: String,
        val info: String,
        val readout: String,
    )

    private var topBar: TopBar? = null

    /**
     * Places the two top capsules for THIS frame, before hover and hit tests read [topAreas]. The
     * right one is sized for the full cursor readout and keeps that size when it shows only the zoom
     * (the cursor over a panel), so its hit area is the drawn one.
     */
    private fun layoutTopBar(mouseX: Int, mouseY: Int) {
        topAreas.clear()
        val pad = 10f

        val title = "World Map"
        val info = "${MapIdentity.currentServerDisplay()}  |  ${MapIdentity.displayDimension(dimension)}"
        val leftEnd = MARGIN + pad + WaypointGraphics.textWidth(title, TITLE_PX, bold = true) + 10f +
            WaypointGraphics.textWidth(info, INFO_PX) + pad
        val leftWidth = (leftEnd - MARGIN).toInt() + 1
        topAreas += ScreenRectangle(MARGIN.toInt(), MARGIN.toInt(), leftWidth, CAPSULE_HEIGHT.toInt())

        val zoomText = "x%.2f".format(java.util.Locale.ROOT, zoom)
        val x = floor(worldX(mouseX.toDouble())).toInt()
        val z = floor(worldZ(mouseY.toDouble())).toInt()
        val coordinates = buildString {
            append("X $x")
            WorldMapStore.heightAt(x, z)?.let { append("   Y ${it + 1}") }
            append("   Z $z     ")
            append(zoomText)
        }
        val rightStart = width - MARGIN - WaypointGraphics.textWidth(coordinates, INFO_PX) - pad * 2f
        val rightWidth = (width - MARGIN - rightStart).toInt() + 1
        topAreas += ScreenRectangle(rightStart.toInt(), MARGIN.toInt(), rightWidth, CAPSULE_HEIGHT.toInt())

        val readout = if (overPanels(mouseX.toDouble(), mouseY.toDouble())) zoomText else coordinates
        topBar = TopBar(leftEnd, rightStart, title, info, readout)
    }

    private fun drawTopBar(graphics: GuiGraphicsExtractor) {
        val layout = topBar ?: return
        val top = MARGIN
        val bottom = top + CAPSULE_HEIGHT
        val pad = 10f

        graphics.glassPanel(MARGIN, top, layout.leftEnd, bottom, CAPSULE_HEIGHT / 2f, null)
        graphics.text(
            layout.title, MARGIN + pad, top + (CAPSULE_HEIGHT - WaypointGraphics.textHeight(TITLE_PX)) / 2f,
            TITLE_PX, WaypointGraphics.ACCENT_FROM, bold = true,
        )
        graphics.text(
            layout.info,
            MARGIN + pad + WaypointGraphics.textWidth(layout.title, TITLE_PX, bold = true) + 10f,
            top + (CAPSULE_HEIGHT - WaypointGraphics.textHeight(INFO_PX)) / 2f,
            INFO_PX, WaypointGraphics.TEXT_DIM,
        )

        graphics.glassPanel(layout.rightStart, top, width - MARGIN, bottom, CAPSULE_HEIGHT / 2f, null)
        val readoutWidth = WaypointGraphics.textWidth(layout.readout, INFO_PX)
        graphics.text(
            layout.readout, width - MARGIN - pad - readoutWidth,
            top + (CAPSULE_HEIGHT - WaypointGraphics.textHeight(INFO_PX)) / 2f,
            INFO_PX, WaypointGraphics.TEXT_DIM,
        )
    }

    private fun drawHint(graphics: GuiGraphicsExtractor) {
        val hint = "Drag: move  |  Wheel: zoom  |  RMB: new waypoint  |  LMB marker: edit  |  " +
            "Space: center  |  Esc: close"
        val px = 7f
        val width = WaypointGraphics.textWidth(hint, px)
        val left = PANEL_WIDTH + MARGIN * 2f
        val available = this.width - left - (if (editor != null) EDITOR_WIDTH + MARGIN * 2f else MARGIN)
        if (width > available) return
        val x = left + (available - width) / 2f
        graphics.pill(hint, x + width / 2f, height - MARGIN - 14f, px, WaypointGraphics.TEXT_DIM, alpha = 0.85f)
    }

    // ------------------------------------------------------------------------------------------
    //  Waypoint list
    // ------------------------------------------------------------------------------------------

    private fun drawList(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val left = MARGIN
        val top = TOP_BAR + MARGIN
        val bottom = height - MARGIN
        val right = left + PANEL_WIDTH
        listArea = ScreenRectangle(left.toInt(), top.toInt(), PANEL_WIDTH.toInt(), (bottom - top).toInt())

        graphics.glassPanel(left, top, right, bottom, WaypointGraphics.PANEL_RADIUS, null)

        val player = mc.player
        val waypoints = visibleWaypoints().sortedBy { waypoint ->
            player?.let { waypoint.distanceTo(it.x, it.y, it.z) } ?: 0.0
        }

        val headerPx = 9f
        graphics.text("Waypoints", left + PAD, top + 8f, headerPx, WaypointGraphics.TEXT, bold = true)
        val countLabel = "${waypoints.size}"
        graphics.text(
            countLabel, left + PAD + WaypointGraphics.textWidth("Waypoints", headerPx, bold = true) + 5f, top + 9f,
            8f, WaypointGraphics.TEXT_DIM,
        )

        // "+ Here": mark where the player stands.
        val addLabel = "+ Here"
        val addWidth = WaypointGraphics.textWidth(addLabel, 8f) + 16f
        val addHeight = 16f
        val addX0 = right - PAD - addWidth
        val addY0 = top + 6f
        val addHover = mouseX >= addX0 && mouseX <= addX0 + addWidth && mouseY >= addY0 && mouseY <= addY0 + addHeight
        graphics.drawRoundedRect(
            addX0, addY0, addX0 + addWidth, addY0 + addHeight, addHeight / 2f,
            fillColor = Color4b(WaypointGraphics.withAlpha(WaypointGraphics.ACCENT, if (addHover) 0.4f else 0.2f)),
            outlineColor = Color4b(WaypointGraphics.withAlpha(WaypointGraphics.ACCENT, 0.75f)), outlineWidth = 1f,
        )
        val addTextY = addY0 + (addHeight - WaypointGraphics.textHeight(8f)) / 2f
        graphics.text(addLabel, addX0 + 8f, addTextY, 8f, WaypointGraphics.TEXT)
        buttons += Button(addX0, addY0, addX0 + addWidth, addY0 + addHeight) { openEditorAtPlayer() }

        val listTop = top + 28f
        val listBottom = bottom - 8f
        val viewHeight = listBottom - listTop
        listMaxScroll = (waypoints.size * ROW_HEIGHT - viewHeight).coerceAtLeast(0f)
        listScroll = listScroll.coerceIn(0f, listMaxScroll)

        if (waypoints.isEmpty()) {
            val empty = "No waypoints here yet."
            graphics.text(empty, left + PAD, listTop + 6f, 8f, WaypointGraphics.TEXT_DIM)
            graphics.text("Right-click the map to add one.", left + PAD, listTop + 18f, 7f, WaypointGraphics.TEXT_DIM)
            return
        }

        val clip = ScreenRectangle(left.toInt(), listTop.toInt(), PANEL_WIDTH.toInt(), viewHeight.toInt())
        graphics.scissorStack.push(clip)
        try {
            var y = listTop - listScroll
            for (waypoint in waypoints) {
                if (y + ROW_HEIGHT >= listTop && y <= listBottom) {
                    drawRow(graphics, waypoint, left + 6f, y, right - 6f, mouseX, mouseY, listTop, listBottom)
                }
                y += ROW_HEIGHT
            }
        } finally {
            graphics.scissorStack.pop()
        }

        if (listMaxScroll > 0f) {
            val trackHeight = viewHeight
            val thumbHeight = (trackHeight * viewHeight / (viewHeight + listMaxScroll)).coerceAtLeast(12f)
            val thumbY = listTop + (trackHeight - thumbHeight) * (listScroll / listMaxScroll)
            graphics.drawRoundedRect(
                right - 4f, thumbY, right - 2f, thumbY + thumbHeight, 1f,
                fillColor = Color4b(0x50FFFFFF),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun drawRow(
        graphics: GuiGraphicsExtractor,
        waypoint: Waypoint,
        x0: Float,
        y: Float,
        x1: Float,
        mouseX: Int,
        mouseY: Int,
        clipTop: Float,
        clipBottom: Float,
    ) {
        val hover = mouseX >= x0 && mouseX <= x1 &&
            mouseY >= maxOf(y, clipTop) && mouseY <= minOf(y + ROW_HEIGHT, clipBottom)
        val active = waypoint === selected || waypoint === editor?.target
        if (hover || active) {
            graphics.drawRoundedRect(
                x0, y + 1f, x1, y + ROW_HEIGHT - 1f, (ROW_HEIGHT - 2f) / 2f,
                fillColor = Color4b(
                    if (active) WaypointGraphics.withAlpha(WaypointGraphics.ACCENT, 0.18f) else 0x14FFFFFF,
                ),
            )
        }

        val alpha = if (waypoint.visible) 1f else 0.45f
        graphics.waypointDiamond(x0 + 10f, y + ROW_HEIGHT / 2f, 3.5f, waypoint.color, alpha)

        // Visibility toggle at the right edge: a filled dot shown, a ring hidden.
        val toggleX = x1 - 10f
        val toggleY = y + ROW_HEIGHT / 2f
        graphics.drawRoundedRect(
            toggleX - 3.5f, toggleY - 3.5f, toggleX + 3.5f, toggleY + 3.5f, 3.5f,
            fillColor = Color4b(if (waypoint.visible) WaypointGraphics.ACCENT else 0x00000000),
            outlineColor = Color4b(if (waypoint.visible) WaypointGraphics.ACCENT else WaypointGraphics.TEXT_DIM),
            outlineWidth = 1f,
        )

        val player = mc.player
        val distance = player?.let { WaypointGraphics.formatDistance(waypoint.distanceTo(it.x, it.y, it.z)) } ?: ""
        val px = 8f
        val distanceWidth = WaypointGraphics.textWidth(distance, 7f)
        val textY = y + (ROW_HEIGHT - WaypointGraphics.textHeight(px)) / 2f
        graphics.text(
            distance, toggleX - 8f - distanceWidth, y + (ROW_HEIGHT - WaypointGraphics.textHeight(7f)) / 2f,
            7f, WaypointGraphics.TEXT_DIM,
        )

        val nameRoom = toggleX - 8f - distanceWidth - 6f - (x0 + 20f)
        graphics.text(ellipsize(waypoint.name, px, nameRoom), x0 + 20f, textY, px,
            WaypointGraphics.withAlpha(WaypointGraphics.TEXT, alpha))

        if (y + ROW_HEIGHT > clipTop && y < clipBottom) {
            val top = maxOf(y, clipTop)
            val bottom = minOf(y + ROW_HEIGHT, clipBottom)
            // The toggle is registered after the row, so it wins the click.
            buttons += Button(x0, top, x1, bottom) {
                selected = waypoint
                glideTo(waypoint.centerX, waypoint.centerZ)
                openEditor(waypoint)
            }
            buttons += Button(toggleX - 7f, top, x1, bottom) {
                waypoint.visible = !waypoint.visible
                WaypointStore.changed()
            }
        }
    }

    private fun ellipsize(text: String, px: Float, room: Float): String {
        if (WaypointGraphics.textWidth(text, px) <= room) return text
        var end = text.length
        while (end > 1 && WaypointGraphics.textWidth(text.substring(0, end) + "...", px) > room) end--
        return text.substring(0, end) + "..."
    }

    // ------------------------------------------------------------------------------------------
    //  Editor
    // ------------------------------------------------------------------------------------------

    private fun openEditorAtPlayer() {
        val player = mc.player ?: return
        val block = player.blockPosition()
        openEditor(null, block.x, block.y, block.z)
    }

    private fun openEditorAt(worldX: Double, worldZ: Double) {
        val x = floor(worldX).toInt()
        val z = floor(worldZ).toInt()
        // The captured height is kept current while the chunk is loaded and already skips the
        // Nether roof; +1 because a waypoint's Y is where the feet go.
        val y = WorldMapStore.heightAt(x, z)?.plus(1) ?: mc.player?.blockY ?: 64
        openEditor(null, x, y, z)
    }

    private fun openEditor(target: Waypoint) {
        openEditor(target, target.x, target.y, target.z)
    }

    private fun openEditor(target: Waypoint?, x: Int, y: Int, z: Int) {
        editor?.detach()
        val created = Editor(
            target = target,
            x = x, y = y, z = z,
            name = target?.name ?: WaypointStore.uniqueName(),
            color = target?.color ?: WaypointStore.nextColor(),
            visible = target?.visible ?: true,
        )
        editor = created
        created.attach()
        setFocused(created.nameBox)
        created.nameBox.moveCursorToEnd(false)
        created.nameBox.setHighlightPos(0)
    }

    private fun closeEditor() {
        editor?.detach()
        editor = null
        editorArea = null
        clearFocus()
    }

    private inner class Editor(
        val target: Waypoint?,
        x: Int,
        y: Int,
        z: Int,
        name: String,
        var color: Int,
        var visible: Boolean,
    ) {
        val waypointDimension = target?.dimension ?: dimension
        val nameBox = field(name, WaypointStore.MAX_NAME_LENGTH)
        val xBox = field(x.toString(), 9)
        val yBox = field(y.toString(), 5)
        val zBox = field(z.toString(), 9)
        private val boxes = listOf(nameBox, xBox, yBox, zBox)
        private var error: String? = null

        private fun field(value: String, maxLength: Int) =
            EditBox(font, 0, 0, 40, 10, Component.empty()).apply {
                setBordered(false)
                setMaxLength(maxLength)
                setValue(value)
                setTextColor(WaypointGraphics.TEXT)
            }

        fun attach() = boxes.forEach { addRenderableWidget(it) }

        fun detach() = boxes.forEach { removeWidget(it) }

        val anyFocused: Boolean get() = boxes.any { it.isFocused }

        fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
            val right = width - MARGIN
            val left = right - EDITOR_WIDTH
            val top = TOP_BAR + MARGIN
            val inner = EDITOR_WIDTH - PAD * 2f
            val labelPx = 7f
            val labelGap = WaypointGraphics.textHeight(labelPx) + 3f

            var y = top + PAD
            val titleY = y
            y += 18f
            val nameLabelY = y
            y += labelGap
            val nameFieldY = y
            y += FIELD_HEIGHT + 8f
            val coordLabelY = y
            y += labelGap
            val coordFieldY = y
            y += FIELD_HEIGHT + 8f
            val colorLabelY = y
            y += labelGap
            val swatchY = y
            val swatch = 14f
            y += swatch + 10f
            val toggleY = y
            y += 16f
            val errorY = y
            if (error != null) y += 12f
            val buttonsY = y
            y += 18f
            val bottom = y + PAD

            editorArea = ScreenRectangle(left.toInt(), top.toInt(), EDITOR_WIDTH.toInt(), (bottom - top).toInt())

            graphics.glassPanel(
                left, top, right, bottom, WaypointGraphics.PANEL_RADIUS, null,
                outline = WaypointGraphics.withAlpha(color, 0.6f),
            )

            val title = if (target == null) "New waypoint" else "Edit waypoint"
            graphics.waypointDiamond(left + PAD + 4f, titleY + 6f, 4f, color)
            graphics.text(title, left + PAD + 14f, titleY + 1f, 9f, WaypointGraphics.TEXT, bold = true)
            val dimensionLabel = MapIdentity.displayDimension(waypointDimension)
            val dimensionWidth = WaypointGraphics.textWidth(dimensionLabel, 7f)
            graphics.text(dimensionLabel, right - PAD - dimensionWidth, titleY + 2f, 7f, WaypointGraphics.TEXT_DIM)

            graphics.text("Name", left + PAD, nameLabelY, labelPx, WaypointGraphics.TEXT_DIM)
            fieldBackground(graphics, nameBox, left + PAD, nameFieldY, inner)

            val third = (inner - 12f) / 3f
            listOf("X" to xBox, "Y" to yBox, "Z" to zBox).forEachIndexed { index, (label, box) ->
                val fx = left + PAD + index * (third + 6f)
                graphics.text(label, fx, coordLabelY, labelPx, WaypointGraphics.TEXT_DIM)
                fieldBackground(graphics, box, fx, coordFieldY, third)
            }

            graphics.text("Color", left + PAD, colorLabelY, labelPx, WaypointGraphics.TEXT_DIM)
            val palette = WaypointStore.PALETTE
            val gap = (inner - palette.size * swatch) / (palette.size - 1)
            palette.forEachIndexed { index, paletteColor ->
                val sx = left + PAD + index * (swatch + gap)
                val chosen = (paletteColor or 0xFF000000.toInt()) == (color or 0xFF000000.toInt())
                graphics.drawRoundedRect(
                    sx, swatchY, sx + swatch, swatchY + swatch, swatch / 2f,
                    fillColor = Color4b(paletteColor),
                    outlineColor = Color4b(if (chosen) 0xFFFFFFFF.toInt() else 0x40000000),
                    outlineWidth = if (chosen) 1.5f else 1f,
                )
                buttons += Button(sx, swatchY, sx + swatch, swatchY + swatch) { color = paletteColor }
            }

            // Visibility checkbox.
            val boxX = left + PAD
            graphics.drawRoundedRect(
                boxX, toggleY, boxX + 10f, toggleY + 10f, 4f,
                fillColor = Color4b(if (visible) WaypointGraphics.ACCENT else 0x00000000),
                outlineColor = Color4b(if (visible) WaypointGraphics.ACCENT else WaypointGraphics.TEXT_DIM),
                outlineWidth = 1f,
            )
            graphics.text("Show in world and on the minimap", boxX + 16f, toggleY + 1f, 7.5f, WaypointGraphics.TEXT)
            buttons += Button(boxX, toggleY - 2f, right - PAD, toggleY + 12f) { visible = !visible }

            error?.let { graphics.text(it, left + PAD, errorY, 7f, ERROR) }

            // Buttons: Save, Delete (existing only), Cancel.
            val labels = if (target != null) listOf("Save", "Delete", "Cancel") else listOf("Save", "Cancel")
            val buttonWidth = (inner - (labels.size - 1) * 6f) / labels.size
            labels.forEachIndexed { index, label ->
                val bx = left + PAD + index * (buttonWidth + 6f)
                val hover = mouseX >= bx && mouseX <= bx + buttonWidth && mouseY >= buttonsY && mouseY <= buttonsY + 18f
                val base = when (label) {
                    "Save" -> WaypointGraphics.ACCENT
                    "Delete" -> ERROR
                    else -> WaypointGraphics.TEXT_DIM
                }
                graphics.drawRoundedRect(
                    bx, buttonsY, bx + buttonWidth, buttonsY + 18f, 9f,
                    fillColor = Color4b(WaypointGraphics.withAlpha(base, if (hover) 0.42f else 0.22f)),
                    outlineColor = Color4b(WaypointGraphics.withAlpha(base, 0.8f)), outlineWidth = 1f,
                )
                val labelWidth = WaypointGraphics.textWidth(label, 8f)
                graphics.text(
                    label,
                    bx + (buttonWidth - labelWidth) / 2f,
                    buttonsY + (18f - WaypointGraphics.textHeight(8f)) / 2f,
                    8f, WaypointGraphics.TEXT,
                )
                buttons += Button(bx, buttonsY, bx + buttonWidth, buttonsY + 18f) {
                    when (label) {
                        "Save" -> save()
                        "Delete" -> delete()
                        else -> closeEditor()
                    }
                }
            }
        }

        private fun fieldBackground(graphics: GuiGraphicsExtractor, box: EditBox, x: Float, y: Float, w: Float) {
            graphics.drawRoundedRect(
                x, y, x + w, y + FIELD_HEIGHT, WaypointGraphics.CONTROL_RADIUS,
                fillColor = Color4b(0xC0080A0E.toInt()),
                outlineColor = Color4b(if (box.isFocused) WaypointGraphics.withAlpha(color, 0.9f) else 0x26FFFFFF),
                outlineWidth = 1f,
            )
            box.setX((x + 8f).toInt())
            box.setY((y + (FIELD_HEIGHT - 8f) / 2f).toInt())
            box.setSize((w - 16f).toInt(), 10)
            // The whole field focuses the box, not only its text line.
            buttons += Button(x, y, x + w, y + FIELD_HEIGHT) {
                this@WorldMapScreen.setFocused(box)
            }
        }

        fun save() {
            val x = xBox.value.trim().toIntOrNull()
            val y = yBox.value.trim().toIntOrNull()
            val z = zBox.value.trim().toIntOrNull()
            if (x == null || y == null || z == null) {
                error = "X, Y and Z must be whole numbers"
                return
            }
            val name = nameBox.value.trim().ifEmpty { WaypointStore.uniqueName() }

            if (target == null) {
                val waypoint = WaypointStore.add(name, x, y, z, waypointDimension, color) ?: run {
                    error = "Not connected to a world"
                    return
                }
                if (!visible) {
                    waypoint.visible = false
                    WaypointStore.changed()
                }
                selected = waypoint
            } else {
                target.name = name
                target.x = x
                target.y = y
                target.z = z
                target.color = color
                target.visible = visible
                WaypointStore.changed()
                selected = target
            }
            closeEditor()
        }

        fun delete() {
            target?.let { WaypointStore.remove(it) }
            if (selected === target) selected = null
            closeEditor()
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Input
    // ------------------------------------------------------------------------------------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x
        val y = event.y

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Our own controls first, last-registered (topmost) wins.
            for (i in buttons.indices.reversed()) {
                val button = buttons[i]
                if (button.contains(x, y)) {
                    // Let a text field take the click too, so the caret lands where clicked.
                    super.mouseClicked(event, doubleClick)
                    button.action()
                    return true
                }
            }
        }

        if (overPanels(x, y)) {
            return super.mouseClicked(event, doubleClick)
        }

        clearFocus()
        when (event.button()) {
            GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                pressOnMap = true
                pressX = x
                pressY = y
                dragged = false
                return true
            }

            GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                openEditorAt(worldX(x), worldZ(y))
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (pressOnMap && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!dragged && (abs(event.x - pressX) > DRAG_THRESHOLD || abs(event.y - pressY) > DRAG_THRESHOLD)) {
                dragged = true
            }
            if (dragged) {
                centerX -= dx / zoom
                centerZ -= dy / zoom
                glideActive = false
                anchorActive = false
            }
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (pressOnMap && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressOnMap = false
            if (!dragged) {
                val hit = pickWaypoint(event.x.toFloat(), event.y.toFloat())
                if (hit != null) {
                    selected = hit
                    openEditor(hit)
                } else {
                    selected = null
                    if (editor?.target != null) closeEditor()
                }
            }
            dragged = false
            return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        listArea?.let { area ->
            if (area.containsPoint(x.toInt(), y.toInt())) {
                listScroll = (listScroll - scrollY.toFloat() * ROW_HEIGHT * 2f).coerceIn(0f, listMaxScroll)
                return true
            }
        }
        if (overPanels(x, y) || scrollY == 0.0) {
            return super.mouseScrolled(x, y, scrollX, scrollY)
        }

        // Anchor the world point under the cursor for the whole animation.
        anchorWorldX = worldX(x)
        anchorWorldZ = worldZ(y)
        anchorScreenX = x
        anchorScreenY = y
        anchorActive = true
        glideActive = false

        val factor = Math.pow(WHEEL_STEP, scrollY)
        targetZoom = (targetZoom * factor).coerceIn(minZoom(), MAX_ZOOM)
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (ModuleWorldMap.bind.matchesKey(event.key(), event.scancode())) {
            closeArmed = true
        }
        return super.keyReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val typing = editor?.anyFocused == true

        when (event.key()) {
            GLFW.GLFW_KEY_ESCAPE -> {
                if (editor != null) {
                    closeEditor()
                    return true
                }
            }

            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                editor?.let {
                    it.save()
                    return true
                }
            }

            GLFW.GLFW_KEY_SPACE -> if (!typing) {
                mc.player?.let { glideTo(it.x, it.z) }
                return true
            }
        }

        val bind = ModuleWorldMap.bind
        if (!typing && closeArmed && bind.matchesKey(event.key(), event.scancode()) &&
            bind.matchesModifiers(event.modifiers())
        ) {
            onClose()
            return true
        }

        return super.keyPressed(event)
    }
}
