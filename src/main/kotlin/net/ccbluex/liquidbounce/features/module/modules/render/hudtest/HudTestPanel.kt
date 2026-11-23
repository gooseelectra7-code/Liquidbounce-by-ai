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
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent

/**
 * Where a panel sticks when the window is resized. The reference positions were measured on a
 * ~1920x1080 frame; each one is expressed here as the nearest sensible anchor plus an offset in
 * native pixels, so the layout still holds together at other resolutions.
 */
internal enum class HudTestAnchor(
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
 * One local unit in this HUD is one native screen pixel, so a card whose origin sits at a
 * fractional gui coordinate puts the header hairline, the two-pixel bars, the dividers and the
 * contact edge halfway across two device pixels — each of them then renders as a pair of
 * half-bright rows. The layout is correct either way; this is the difference between it reading
 * as crisp and as soft. [WatermarkFont] already snaps its own origin, which is why text came out
 * sharp while everything drawn around it did not.
 */
internal fun snapToPixel(guiCoordinate: Float, guiScale: Float): Float =
    Math.round(guiCoordinate * guiScale) / guiScale

/**
 * Everything a panel needs to place itself for one frame.
 *
 * [x] / [y] are the card's top-left corner in gui units, [unit] is how many gui units one native
 * pixel is worth, and [density] is the inverse — physical pixels per local unit — which is what
 * the font atlas and the antialiasing feather are sized from. [grow] is the appear animation's
 * scale about the card's own centre.
 */
@Suppress("LongParameterList")
internal class HudTestFrame(
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

    /** Maps a local (native-pixel) x to gui space, for hit rectangles and out-of-pose draws. */
    fun guiX(localX: Float): Float = x + (width * 0.5f + (localX - width * 0.5f) * grow) * unit

    /** Maps a local (native-pixel) y to gui space, for hit rectangles and out-of-pose draws. */
    fun guiY(localY: Float): Float = y + (height * 0.5f + (localY - height * 0.5f) * grow) * unit
}

/**
 * One toggleable display option of a panel, as shown by the settings popover: a real config value
 * plus the animation state of its toggle knob.
 */
internal class HudTestOption(val label: String, private val value: Value<Boolean>) {

    /** Knob position, 0 = off, 1 = on; eased towards [enabled] every frame. */
    var knob: Float = if (value.get()) 1f else 0f

    val enabled: Boolean get() = value.get()

    fun toggle() {
        value.set(!value.get())
    }

    /** Eases [knob] towards the real value; [dt] is the frame time in seconds. */
    fun advance(dt: Float) {
        val goal = if (enabled) 1f else 0f
        knob += (goal - knob) * HudTestTheme.damp(KNOB_SMOOTHING, dt)
    }

    private companion object {
        /** Fraction of the remaining knob travel left after one second. */
        const val KNOB_SMOOTHING = 1.0e-7f
    }
}

/**
 * Base class of every HudTest panel: the anchor + offset placement, the drag hit box, the
 * appear/disappear fade and the list of display options its settings popover drives.
 *
 * Panels do **not** register event handlers of their own — [ModuleHudTest] drives them in one
 * render pass so the stacking order (panels, then the popover, then the notifications) is exact.
 */
internal abstract class HudTestPanel(
    parent: EventListener,
    name: String,
    defaultAnchor: HudTestAnchor,
    defaultOffsetX: Float,
    defaultOffsetY: Float,
) : ToggleableValueGroup(parent, name, true) {

    private val anchor by enumChoice("Anchor", defaultAnchor)
    private var offsetX by float("OffsetX", defaultOffsetX, -4000f..4000f)
    private var offsetY by float("OffsetY", defaultOffsetY, -4000f..4000f)
    private val scale by float("Scale", 1f, 0.4f..4f)

    /** Gui-space hit box of the last rendered frame, and when it was last refreshed. */
    private var boundsX = 0f
    private var boundsY = 0f
    private var boundsWidth = 0f
    private var boundsHeight = 0f
    private var boundsAt = 0L

    /** 0 = fully faded out (and not drawn at all), 1 = fully present. */
    private var visibility = 0f

    /** Gui units per native pixel of the last drawn frame — the popover matches its owner's size. */
    private var lastUnit = 1f
    val frameUnit: Float get() = lastUnit

    /** Physical pixels per local unit of the last drawn frame. */
    val frameDensity: Float get() = scale

    /** The display options this panel's settings popover shows; empty means it has no popover. */
    open val displayOptions: List<HudTestOption> get() = emptyList()

    /** Registers a real config boolean and wraps it as a popover row. */
    protected fun displayOption(label: String, default: Boolean = true): HudTestOption =
        HudTestOption(label, boolean(label, default))

    /**
     * Draws the panel for this frame. Implementations bail out early when they have nothing to
     * show; [dt] is the frame time in seconds and is used for every animation.
     */
    abstract fun render(event: OverlayRenderEvent, dt: Float)

    /**
     * Advances the fade towards [present] and returns the eased alpha, or `0` when the panel is
     * fully hidden and should not be drawn at all.
     */
    protected fun fade(present: Boolean, dt: Float): Float {
        val step = dt * 1000f / HudTestTheme.FADE_MS
        visibility = (visibility + if (present) step else -step).coerceIn(0f, 1f)
        return HudTestTheme.easeOutCubic(visibility)
    }

    /**
     * Places a card of [width] x [height] native pixels for this frame: applies the drag, resolves
     * the anchor, clamps the card onto the screen and refreshes the hit box.
     */
    protected fun frame(width: Float, height: Float, alpha: Float): HudTestFrame {
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val unit = scale / guiScale
        val screenWidth = mc.window.guiScaledWidth.toFloat()
        val screenHeight = mc.window.guiScaledHeight.toFloat()
        val cardWidth = width * unit
        val cardHeight = height * unit

        val baseX = (screenWidth - cardWidth) * anchor.fractionX
        val baseY = (screenHeight - cardHeight) * anchor.fractionY

        val maxX = (screenWidth - cardWidth).coerceAtLeast(0f)
        val maxY = (screenHeight - cardHeight).coerceAtLeast(0f)
        if (HudTestInput.dragging === this) {
            // The offset is stored already clamped, so a drag past the edge does not build up a
            // hidden overshoot the cursor would then have to travel back through
            offsetX = ((HudTestInput.mouseX() - HudTestInput.grabX).coerceIn(0f, maxX) - baseX) / unit
            offsetY = ((HudTestInput.mouseY() - HudTestInput.grabY).coerceIn(0f, maxY) - baseY) / unit
        }

        val x = snapToPixel((baseX + offsetX * unit).coerceIn(0f, maxX), guiScale)
        val y = snapToPixel((baseY + offsetY * unit).coerceIn(0f, maxY), guiScale)

        boundsX = x
        boundsY = y
        boundsWidth = cardWidth
        boundsHeight = cardHeight
        boundsAt = System.currentTimeMillis()
        lastUnit = unit

        return HudTestFrame(
            x = x,
            y = y,
            unit = unit,
            density = scale,
            alpha = alpha,
            grow = APPEAR_SCALE + (1f - APPEAR_SCALE) * alpha,
            width = width,
            height = height,
        )
    }

    /** Whether the gui-space point is inside the panel's freshly drawn card. */
    fun containsGui(mouseX: Float, mouseY: Float): Boolean =
        System.currentTimeMillis() - boundsAt < BOUNDS_TTL_MS &&
            mouseX >= boundsX && mouseX <= boundsX + boundsWidth &&
            mouseY >= boundsY && mouseY <= boundsY + boundsHeight

    /** Gui-space right edge of the last drawn card — where the settings popover docks. */
    val guiRight: Float get() = boundsX + boundsWidth

    /** Gui-space left edge of the last drawn card. */
    val guiLeft: Float get() = boundsX

    /** Gui-space top edge of the last drawn card. */
    val guiTop: Float get() = boundsY

    private companion object {
        /** A hit box goes stale when the panel stops drawing, so it cannot be grabbed blindly. */
        const val BOUNDS_TTL_MS = 500L

        /** How small a card starts before it grows into place. */
        const val APPEAR_SCALE = 0.94f
    }
}
