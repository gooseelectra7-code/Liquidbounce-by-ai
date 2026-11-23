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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import kotlin.math.max

/**
 * A [Widget] with a smoothed vertical scroll offset — ported from
 * `client/gui/widget/ScrollableWidget.java`. The scroll model is a single settle [Tween]:
 * the wheel snaps the target [scrollOffset], and [scroll] reads the eased value that chases it.
 *
 * ## Scroll constants, cited to source
 *  - **Wheel step 48** GUI units per notch (source `value239 = 48`, ScrollableWidget.java:7; applied
 *    in `isDoubleDoubleDouble`, :39).
 *  - **Settle tween 0.28 s, easeOutExpo** (source `tween4 = EasingPresets.getTweenByFloatFloat(0, 0.28)`,
 *    ScrollableWidget.java:8; `getTweenByFloatFloat` = expo tween).
 *  - **Max scroll = contentExtent − height + 16** — the trailing 16 is bottom padding
 *    (source `getFloat2`, :30-32).
 *
 * There is no spring: the "settle" is the wall-clock easeOutExpo tween above. (The source client's
 * only spring-like list mechanism is the per-child visibility integrator ported as `ScrollAnimator`;
 * this widget's own scroll is purely tween-settled.)
 *
 * Subclasses lay out their content, call [setContentHeight] once its extent is known, and read
 * [scroll] each frame to offset drawing (clipping via `SystemDraw.scissor`).
 */
abstract class ScrollableWidget : Widget() {

    /** Settle tween the visible scroll offset eases along (source `tween4`, expo, 0.28 s). */
    protected val scrollTween: Tween = EasingPresets.expoTween(0f, SCROLL_SETTLE_SECONDS)

    /** Target scroll offset, clamped to `[0, maxScroll]` (source `value240`). */
    protected var scrollOffset: Float = 0f

    /** Total content extent used to compute [maxScroll] (source `value241`). */
    protected var contentExtent: Float = 0f

    /**
     * Set the total content extent and re-clamp the current offset (source `setFloat`,
     * ScrollableWidget.java:12-24). Retargets the settle tween to the clamped offset.
     */
    fun setContentHeight(contentHeight: Float) {
        contentExtent = contentHeight
        val maxScroll = maxScroll()
        if (scrollOffset < 0f) {
            scrollOffset = 0f
        }
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll
        }
        scrollTween.to(scrollOffset)
    }

    /** The smoothed (eased) current scroll offset (source `getFloat`, ScrollableWidget.java:26-28). */
    fun scroll(): Float = scrollTween.get()

    /** Maximum scroll offset: `contentExtent − height + 16` clamped at 0 (source `getFloat2`). */
    fun maxScroll(): Float = max(0f, contentExtent - height + BOTTOM_PADDING)

    /**
     * Wheel scroll: while the pointer is inside, step the target by `−amount * 48` clamped to
     * `[0, maxScroll]` and retarget the settle tween (source `isDoubleDoubleDouble`,
     * ScrollableWidget.java:34-43).
     */
    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean {
        if (!contains(mouseX, mouseY)) {
            return false
        }
        val next = scrollOffset - (amount * WHEEL_STEP).toFloat()
        scrollOffset = next.coerceIn(0f, maxScroll())
        scrollTween.to(scrollOffset)
        return true
    }

    companion object {
        /** Wheel step in GUI units per notch (source `value239 = 48`). */
        const val WHEEL_STEP = 48f

        /** Settle tween duration in seconds (source `0.28`). */
        const val SCROLL_SETTLE_SECONDS = 0.28f

        /** Trailing bottom padding added to the scrollable extent (source `+ 16`). */
        const val BOTTOM_PADDING = 16f
    }
}
