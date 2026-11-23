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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Where a panel sticks when the window is resized. The design's positions were authored on a
 * 1920x1080 frame (`<1:117>`); each panel states the nearest sensible anchor plus the design's own
 * offset in native pixels, so the layout still holds together at other resolutions.
 */
internal enum class Hud3Anchor(
    override val tag: String,
    /** Horizontal anchor fraction: 0 left, 0.5 centre, 1 right. */
    val fractionX: Float,
    /** Vertical anchor fraction: 0 top, 0.5 centre, 1 bottom. */
    val fractionY: Float,
) : Tagged {
    TOP_LEFT("TopLeft", 0f, 0f),
    TOP_CENTER("TopCenter", 0.5f, 0f),
    TOP_RIGHT("TopRight", 1f, 0f),
    CENTER("Center", 0.5f, 0.5f),
    BOTTOM_LEFT("BottomLeft", 0f, 1f),
    BOTTOM_CENTER("BottomCenter", 0.5f, 1f),
    BOTTOM_RIGHT("BottomRight", 1f, 1f),
}

/**
 * A gui coordinate pulled onto the physical pixel grid.
 *
 * One local unit here is one native screen pixel, so a card whose origin sits at a fractional gui
 * coordinate puts the 1.5 px rim and the 2 px progress bars halfway across two device pixels and
 * each of them renders as a pair of half-bright rows. The layout is correct either way; this is
 * the difference between it reading as crisp and as soft.
 */
internal fun snapToPixel(guiCoordinate: Float, guiScale: Float): Float =
    Math.round(guiCoordinate * guiScale) / guiScale

/**
 * Everything a panel needs to place itself for one frame.
 *
 * [x] / [y] are the card's top-left corner in gui units, [unit] is how many gui units one native
 * pixel is worth, and [density] is physical pixels per local unit — which is what the font atlas
 * and the antialiasing feather are sized from. [grow] is the appear animation's scale about the
 * card's own centre.
 */
@Suppress("LongParameterList")
internal class Hud3Frame(
    val x: Float,
    val y: Float,
    val unit: Float,
    val density: Float,
    val alpha: Float,
    val grow: Float,
    val width: Float,
    val height: Float,
) {
    /** Gui units per native pixel including the appear animation. */
    val guiUnit: Float get() = unit * grow

    /** Physical pixels per gui unit, recovered from the two scales the frame already carries. */
    val guiScale: Float get() = if (unit > 0f) density / unit else 1f

    /** [guiCoordinate] pulled onto the physical pixel grid; see [snapToPixel]. */
    fun snap(guiCoordinate: Float): Float = snapToPixel(guiCoordinate, guiScale)

    /** Maps a local (native-pixel) x to gui space, for out-of-pose draws. */
    fun guiX(localX: Float): Float = x + (width * 0.5f + (localX - width * 0.5f) * grow) * unit

    /** Maps a local (native-pixel) y to gui space, for out-of-pose draws. */
    fun guiY(localY: Float): Float = y + (height * 0.5f + (localY - height * 0.5f) * grow) * unit
}

/**
 * Dragging for the Hud3 panels, active only while a screen (chat or a container) is open.
 *
 * This is a separate object from `HudTestInput` on purpose: drag state is per-HUD, and a single
 * shared one would let a grab in one module move a panel of the other while both are enabled.
 * There are no clickable affordances in this design, so unlike HudTest there is no hit list.
 */
internal object Hud3Input {

    /** The panel currently being dragged, if any. */
    var dragging: Hud3Panel? = null
        private set

    /** Cursor offset inside the dragged panel, so it does not jump on grab. */
    var grabX = 0f
        private set
    var grabY = 0f
        private set

    /** Cursor position in gui units. */
    fun mouseX(): Float = (mc.mouseHandler.xpos() / mc.window.guiScale).toFloat()

    fun mouseY(): Float = (mc.mouseHandler.ypos() / mc.window.guiScale).toFloat()

    /** Grabs [panel] for dragging with the cursor at ([mouseX], [mouseY]) in gui units. */
    fun startDrag(panel: Hud3Panel, mouseX: Float, mouseY: Float) {
        dragging = panel
        grabX = mouseX - panel.guiLeft
        grabY = mouseY - panel.guiTop
    }

    /** Releases any active drag. */
    fun endDrag() {
        dragging = null
    }
}

/**
 * Base class of every Hud3 panel: the anchor + offset placement, the drag hit box and the
 * appear/disappear fade.
 *
 * Panels do **not** register event handlers of their own — [ModuleHud3] drives them in one render
 * pass so the stacking order is exact.
 *
 * [defaultOffsetX] / [defaultOffsetY] are the design's own coordinates inside `<1:117>`.
 */
internal abstract class Hud3Panel(
    parent: EventListener,
    name: String,
    defaultAnchor: Hud3Anchor,
    defaultOffsetX: Float,
    defaultOffsetY: Float,
) : ToggleableValueGroup(parent, name, true) {

    private val anchor by enumChoice("Anchor", defaultAnchor)
    private var offsetX by float("OffsetX", defaultOffsetX, -4000f..4000f)
    private var offsetY by float("OffsetY", defaultOffsetY, -4000f..4000f)

    /**
     * Default 2, not 1.
     *
     * One local unit is one **physical** pixel, and the design was authored in physical pixels on a
     * 1920x1080 frame — so at a scale of 1 the whole HUD comes out half the size of everything the
     * game draws at a gui scale of 2, and its 10 px labels bake a 10 px glyph atlas, which is where
     * the soft text came from. At 2 the pills match the rest of the interface and the atlas is baked
     * at 20 / 24 px.
     *
     * Everything a panel positions itself with — the anchor offsets, the gaps, the paddings — is in
     * the same local units, so the whole layout scales together and the design's arrangement is
     * preserved rather than pulled apart.
     */
    private val scale by float("Scale", 2f, 0.4f..4f)

    /** Gui-space hit box of the last rendered frame, and when it was last refreshed. */
    private var boundsX = 0f
    private var boundsY = 0f
    private var boundsWidth = 0f
    private var boundsHeight = 0f
    private var boundsUnit = 0f
    private var boundsAt = 0L

    /** 0 = fully faded out (and not drawn at all), 1 = fully present. */
    private var visibility = 0f

    /** The drawn position, following the placed one; see [frame]. */
    private val shownX = Hud3Smooth(Hud3Theme.POSITION_HALF_LIFE_MS)
    private val shownY = Hud3Smooth(Hud3Theme.POSITION_HALF_LIFE_MS)

    /** 0..1 — how far the card is lifted off the screen while it is being dragged. */
    private val lift = Hud3Smooth(LIFT_HALF_LIFE_MS)

    /** 0..1 — strength of the edit-mode outline; see [renderEditOutline]. */
    private val outline = Hud3Smooth(OUTLINE_HALF_LIFE_MS)

    /** Physical pixels per local unit of the last drawn frame. */
    val frameDensity: Float get() = scale

    /**
     * Which way a row slides while it enters or leaves: out towards the nearer side edge, so an
     * arriving row comes in from off-card rather than from inside the panel next to it.
     */
    protected val slideDirection: Float get() = if (anchor.fractionX > 0.5f) 1f else -1f

    /**
     * Draws the panel for this frame. [dt] is the frame time in seconds and is used for every
     * animation.
     *
     * Called for a switched-off panel too, until its fade-out has finished — which is why every
     * implementation ANDs [enabled] into what it considers present, and keeps drawing its last
     * content while [fade] runs down instead of returning the moment there is nothing new to show.
     */
    abstract fun render(event: OverlayRenderEvent, dt: Float)

    /** Whether the panel has anything on screen, or is still fading; the module skips it otherwise. */
    val isOnScreen: Boolean get() = enabled || visibility > 0f

    /**
     * Advances the fade towards [present] and returns the eased alpha, or `0` when the panel is
     * fully hidden and should not be drawn at all.
     */
    protected fun fade(present: Boolean, dt: Float): Float {
        val step = Hud3Motion.step(Hud3Theme.FADE_MS, dt)
        visibility = (visibility + if (present) step else -step).coerceIn(0f, 1f)
        return Hud3Theme.easeOutCubic(visibility)
    }

    /**
     * The edit-mode affordance: while the chat or a container is open every panel shows a faint
     * accent outline, and the one under the cursor or in the hand a strong one. Drawn in gui space
     * around the card's last bounds, after the panel itself.
     */
    fun renderEditOutline(ctx: GuiGraphicsExtractor, editing: Boolean, mouseX: Float, mouseY: Float, dt: Float) {
        val fresh = System.currentTimeMillis() - boundsAt < BOUNDS_TTL_MS
        val target = when {
            !editing || !fresh || !enabled -> 0f
            Hud3Input.dragging === this || containsGui(mouseX, mouseY) -> 1f
            else -> OUTLINE_IDLE
        }
        val strength = outline.update(target, dt)
        if (strength <= 0.004f || boundsUnit <= 0f) {
            return
        }
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val margin = (Hud3Theme.RIM + OUTLINE_GAP) * boundsUnit
        val x = boundsX - margin
        val y = boundsY - margin
        val width = boundsWidth + margin * 2f
        val height = boundsHeight + margin * 2f
        ctx.hud3Shapes(x - 2f, y - 2f, x + width + 2f, y + height + 2f, guiScale) {
            rimOutside(
                x, y, width, height, Hud3Theme.RADIUS * boundsUnit + margin, OUTLINE_WEIGHT / guiScale,
                scaled(Hud3Theme.ACCENT, strength * OUTLINE_OPACITY),
            )
        }
    }

    /**
     * Places a card of [width] x [height] native pixels for this frame: applies the drag, resolves
     * the anchor, clamps the card onto the screen and refreshes the hit box.
     *
     * [width] / [height] are the design's **fill box**. The pill rim is aligned OUTSIDE, so the
     * screen clamp keeps [Hud3Theme.RIM] of slack on every side — otherwise a panel pushed into a
     * corner would have its rim cut off, which reads as a flat edge on a rounded card.
     */
    protected fun frame(width: Float, height: Float, alpha: Float): Hud3Frame {
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val unit = scale / guiScale
        val screenWidth = mc.window.guiScaledWidth.toFloat()
        val screenHeight = mc.window.guiScaledHeight.toFloat()
        val cardWidth = width * unit
        val cardHeight = height * unit
        val rim = Hud3Theme.RIM * unit

        val baseX = (screenWidth - cardWidth) * anchor.fractionX
        val baseY = (screenHeight - cardHeight) * anchor.fractionY

        val minX = rim
        val minY = rim
        val maxX = (screenWidth - cardWidth - rim).coerceAtLeast(minX)
        val maxY = (screenHeight - cardHeight - rim).coerceAtLeast(minY)
        if (Hud3Input.dragging === this) {
            // The offset is stored already clamped, so a drag past the edge does not build up a
            // hidden overshoot the cursor would then have to travel back through
            offsetX = ((Hud3Input.mouseX() - Hud3Input.grabX).coerceIn(minX, maxX) - baseX) / unit
            offsetY = ((Hud3Input.mouseY() - Hud3Input.grabY).coerceIn(minY, maxY) - baseY) / unit
        }

        // The card FOLLOWS its placement instead of jumping to it: a drag trails the cursor by a
        // few frames, and a right- or bottom-anchored stack slides when its width changes rather
        // than twitching. A panel that has not been drawn for a while starts where it is placed.
        val stale = System.currentTimeMillis() - boundsAt >= BOUNDS_TTL_MS
        if (stale) {
            shownX.reset()
            shownY.reset()
        }
        val dt = frameDt
        val x = snapToPixel(shownX.update((baseX + offsetX * unit).coerceIn(minX, maxX), dt), guiScale)
        val y = snapToPixel(shownY.update((baseY + offsetY * unit).coerceIn(minY, maxY), dt), guiScale)
        val lifted = lift.update(if (Hud3Input.dragging === this) 1f else 0f, dt)

        boundsX = x
        boundsY = y
        boundsWidth = cardWidth
        boundsHeight = cardHeight
        boundsUnit = unit
        boundsAt = System.currentTimeMillis()

        return Hud3Frame(
            x = x,
            y = y,
            unit = unit,
            density = scale,
            alpha = alpha,
            grow = (APPEAR_SCALE + (1f - APPEAR_SCALE) * alpha) * (1f + LIFT_SCALE * lifted),
            width = width,
            height = height,
        )
    }

    /** The frame time of the current render pass, set by the module before it calls [render]. */
    internal var frameDt = 0f

    /** Whether the gui-space point is inside the panel's freshly drawn card. */
    fun containsGui(mouseX: Float, mouseY: Float): Boolean =
        System.currentTimeMillis() - boundsAt < BOUNDS_TTL_MS &&
            mouseX >= boundsX && mouseX <= boundsX + boundsWidth &&
            mouseY >= boundsY && mouseY <= boundsY + boundsHeight

    /** Gui-space left edge of the last drawn card. */
    val guiLeft: Float get() = boundsX

    /** Gui-space top edge of the last drawn card. */
    val guiTop: Float get() = boundsY

    private companion object {
        /** A hit box goes stale when the panel stops drawing, so it cannot be grabbed blindly. */
        const val BOUNDS_TTL_MS = 500L

        /** How small a card starts before it grows into place. */
        const val APPEAR_SCALE = 0.94f

        /** How much a card grows while it is held by the drag. */
        const val LIFT_SCALE = 0.03f
        const val LIFT_HALF_LIFE_MS = 45f

        /** The edit outline: its idle strength, spacing off the rim, weight in device pixels. */
        const val OUTLINE_IDLE = 0.35f
        const val OUTLINE_GAP = 3f
        const val OUTLINE_WEIGHT = 1.5f
        const val OUTLINE_OPACITY = 0.85f
        const val OUTLINE_HALF_LIFE_MS = 50f
    }
}
