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

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.UiClock
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * A titled panel — an optional leading category icon + a 14 px title + an optional 12 px muted
 * description — ported from `client/gui/widget/PanelWidget.java`. It is the base for the module
 * rows and setting rows a later cluster builds on.
 *
 * ## What this class owns: the measure/draw contract
 *
 * The source resolves the title/description from a `Setting` (name/description parts, i18n-aware);
 * here that source is abstracted so a later cluster can feed real modules/settings. Subclasses
 * provide [title], optionally [description] and [categoryIcon], and their own total-content
 * [measureContentHeight]; PanelWidget provides the two things that are load-bearing:
 *
 *  1. **Memoized measurement keyed on [UiClock.frame] + [width].** The source memoizes the measured
 *     content height against `UiContext.getTime()` (the per-frame counter) and `value237` (the
 *     width): `getFloat7` caches [measureContentHeight] and `getFloat4` caches [measuredHeight]
 *     (PanelWidget.java:105-115 / 270-280). Reproduced as [contentHeight] and [measuredHeightMemo].
 *     Setting [width] invalidates both (source `setFloat` -> `update4`, PanelWidget.java:135-138/255).
 *     A finer memo layer caches the title/description text height against `(availableWidth, string)`
 *     (source `getFloatByFloat2`/`getFloatByFloat`, :207-234/48-73) — reproduced in
 *     [titleHeight]/[descriptionHeight].
 *  2. **The title/description draw** ([drawTitle]/[drawDescription]) retargeted to `SystemDraw`.
 *
 * ## Retarget note: text wrapping
 *
 * The source's `TextShader` wrapped text to a pixel width. The fork font exposes only advance-width
 * ([SystemDraw.textWidth]) and line height, so wrapping here is a greedy word wrap over explicit
 * `\n` paragraphs ([wrapLines]). This keeps the width-keyed memo meaningful; the source's exact
 * break points (char-level fallback, hyphenation) are NOT reproduced — a later refinement if the
 * real font gains a wrap primitive.
 */
abstract class PanelWidget : Widget() {

    /** Panel width; changing it invalidates the frame memo (source `value237`, default 269, `setFloat`). */
    override var width: Float = DEFAULT_PANEL_WIDTH
        set(value) {
            if (field != value) {
                field = value
                invalidateMeasure()
            }
        }

    // --- fine memo: title text height, keyed on (available width, title string) ---
    private var titleMemoWidth: Float = -1f
    private var titleMemoText: String? = null
    private var titleMemoValue: Float = 0f

    // --- fine memo: description text height, keyed on (width, description string) ---
    private var descMemoWidth: Float = -1f
    private var descMemoText: String? = null
    private var descMemoValue: Float = 0f

    // --- coarse frame memo of measureContentHeight (source getFloat7) ---
    private var contentFrameKey: Long = -1L
    private var contentFrameWidth: Float = Float.NaN
    private var contentFrameValue: Float = 0f

    // --- coarse frame memo of measuredHeight (source getFloat4) ---
    private var measuredFrameKey: Long = -1L
    private var measuredFrameWidth: Float = Float.NaN
    private var measuredFrameValue: Float = 0f

    // ---------------------------------------------------------------- subclass contract

    /** The resolved (already i18n'd) title text (source name parts / translated name). */
    abstract fun title(): String

    /** The resolved description, or null/blank for none (source description parts, `check`). */
    open fun description(): String? = null

    /** The leading category icon, or null for none (source `getCategoryType`). */
    open fun categoryIcon(): SystemIcon? = null

    /** The subclass's own total content height (source abstract `getFloat5`). */
    abstract fun measureContentHeight(): Float

    /**
     * Height reported to the layout (source `getFloat2`, which defaults to `getFloat5`). Overridable
     * separately from [measureContentHeight] to mirror the source's two memo slots.
     */
    open fun measuredHeight(): Float = measureContentHeight()

    /**
     * Whether this panel currently participates in layout (source `getSetting().isVisible()`, used
     * by [PanelLayout]). Default true; a later cluster overrides it for search filtering.
     */
    open fun isVisible(): Boolean = true

    // ---------------------------------------------------------------- memoized measurement

    /** True if there is a non-blank description (source `check`, PanelWidget.java:89-91). */
    fun hasDescription(): Boolean = !description().isNullOrBlank()

    /** Leading indent applied to the title when an icon is present (source `getFloat`: 20 or 0). */
    fun iconOffset(): Float = if (categoryIcon() != null) ICON_OFFSET else 0f

    /**
     * Measured title height at 14 px, memoized against `(width - iconOffset, title)` (source
     * `getFloatByFloat2`, PanelWidget.java:207-234). Floored to the icon box height when an icon
     * is present (source `getFloat6`).
     */
    fun titleHeight(): Float {
        val available = max(0f, width - iconOffset())
        val name = title()
        if (available == titleMemoWidth && name == titleMemoText) {
            return titleMemoValue
        }
        titleMemoText = name
        titleMemoWidth = available
        var height = measureTextHeight(name, available, TITLE_SIZE)
        if (categoryIcon() != null) {
            height = max(height, ICON_BOX)
        }
        titleMemoValue = height
        return height
    }

    /**
     * Measured description height at 12 px, memoized against `(width, description)` (source
     * `getFloatByFloat`, PanelWidget.java:48-73). Zero when there is no description.
     */
    fun descriptionHeight(): Float {
        val desc = description()
        if (desc.isNullOrBlank()) {
            return 0f
        }
        if (width == descMemoWidth && desc == descMemoText) {
            return descMemoValue
        }
        descMemoText = desc
        descMemoWidth = width
        descMemoValue = measureTextHeight(desc, width, DESC_SIZE)
        return descMemoValue
    }

    /**
     * Bottom Y of the title+description header block (source `getFloatByFloatFloat2`,
     * PanelWidget.java:44-46): `y + titleHeight + (descHeight when > 0) + 8`.
     */
    fun headerBottom(): Float {
        val descH = descriptionHeight()
        return y + titleHeight() + (if (descH > 0f) descH else 0f) + HEADER_GAP
    }

    /**
     * Total content height for this frame, memoized against [UiClock.frame] + [width] (source
     * `getFloat7`, PanelWidget.java:270-280).
     */
    fun contentHeight(): Float {
        val frame = UiClock.frame()
        if (frame == contentFrameKey && width == contentFrameWidth) {
            return contentFrameValue
        }
        contentFrameValue = measureContentHeight()
        contentFrameKey = frame
        contentFrameWidth = width
        return contentFrameValue
    }

    /**
     * Layout height for this frame, memoized against [UiClock.frame] + [width] (source `getFloat4`,
     * PanelWidget.java:105-115). Separate memo slot from [contentHeight], as in the source.
     */
    fun measuredHeightMemo(): Float {
        val frame = UiClock.frame()
        if (frame == measuredFrameKey && width == measuredFrameWidth) {
            return measuredFrameValue
        }
        measuredFrameValue = measuredHeight()
        measuredFrameKey = frame
        measuredFrameWidth = width
        return measuredFrameValue
    }

    /** Invalidate the coarse frame memos (source `update4`, PanelWidget.java:135-138). */
    fun invalidateMeasure() {
        contentFrameKey = -1L
        measuredFrameKey = -1L
    }

    // ---------------------------------------------------------------- draw

    /**
     * Draw the leading icon (if any) and the 14 px foreground title, and return the title height
     * (source `getFloatByFloatMatrix4fFloat`, PanelWidget.java:169-205). The icon is a 16x16 box at
     * the widget's left edge, vertically nudged to sit against the title cap; the title starts at
     * `x + iconOffset` and wraps to `width - iconOffset`.
     */
    fun drawTitle(ctx: GuiGraphicsExtractor, alpha: Float): Float {
        val height = titleHeight()
        val foreground = SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha)

        val icon = categoryIcon()
        if (icon != null) {
            // source: iconX = x + (16 - iconW)/2 = x ; iconY = y + (14 - iconH)/2 = y - 1
            val iconX = x + (ICON_SLOT - ICON_BOX) / 2f
            val iconY = y + (TITLE_SIZE - ICON_BOX) / 2f
            SystemDraw.icon(ctx, icon, iconX, iconY, ICON_BOX, foreground)
        }

        val offset = iconOffset()
        drawWrapped(ctx, title(), x + offset, y, max(0f, width - offset), TITLE_SIZE, foreground)
        return height
    }

    /**
     * Draw the 12 px muted description at `y + yOffset` (typically the title height) wrapping to
     * [width], and return the description height (source `getFloatByMatrix4fFloatFloatFloat`,
     * PanelWidget.java:140-167). No-op / zero when there is no description. The description is NOT
     * indented by the icon offset.
     */
    fun drawDescription(ctx: GuiGraphicsExtractor, alpha: Float, yOffset: Float): Float {
        val desc = description()
        if (desc.isNullOrBlank()) {
            return 0f
        }
        val height = descriptionHeight()
        val muted = SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha)
        drawWrapped(ctx, desc, x, y + yOffset, width, DESC_SIZE, muted)
        return height
    }

    // ---------------------------------------------------------------- text helpers

    private fun measureTextHeight(text: String, maxWidth: Float, size: Float): Float {
        if (text.isEmpty()) {
            return 0f
        }
        return wrapLines(text, maxWidth, size).size * SystemDraw.lineHeight(size)
    }

    @Suppress("LongParameterList")
    private fun drawWrapped(
        ctx: GuiGraphicsExtractor,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        size: Float,
        color: Color4b,
    ) {
        val lineHeight = SystemDraw.lineHeight(size)
        var lineY = y
        for (line in wrapLines(text, maxWidth, size)) {
            SystemDraw.text(ctx, line, x, lineY, size, color)
            lineY += lineHeight
        }
    }

    /**
     * Greedy word wrap of [text] to [maxWidth] at [size], splitting first on explicit `\n`. Words
     * that overflow on their own are emitted un-split (no char-level breaking). `maxWidth <= 0`
     * disables wrapping. See the class KDoc for how this differs from the source's `TextShader` wrap.
     */
    private fun wrapLines(text: String, maxWidth: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (maxWidth <= 0f || SystemDraw.textWidth(paragraph, size) <= maxWidth) {
                out.add(paragraph)
                continue
            }
            val line = StringBuilder()
            for (word in paragraph.split(' ')) {
                if (line.isEmpty()) {
                    line.append(word)
                } else {
                    val candidate = "$line $word"
                    if (SystemDraw.textWidth(candidate, size) <= maxWidth) {
                        line.append(' ').append(word)
                    } else {
                        out.add(line.toString())
                        line.setLength(0)
                        line.append(word)
                    }
                }
            }
            out.add(line.toString())
        }
        return out
    }

    companion object {
        /** Default panel width, GUI units (source `value237 = 269` in the ctor, PanelWidget.java:33). */
        const val DEFAULT_PANEL_WIDTH = 269f

        /** Title text size (source literal 14, PanelWidget.java:189/197). */
        const val TITLE_SIZE = 14f

        /** Description text size (source literal 12, PanelWidget.java:60/159). */
        const val DESC_SIZE = 12f

        /** Category icon box size (source `getFloat3`/`getFloat6` = 16, PanelWidget.java:101/123). */
        const val ICON_BOX = 16f

        /** Icon x-centering slot (source `value239`/`getFloat3` = 16, PanelWidget.java:14). */
        const val ICON_SLOT = 16f

        /** Title indent when an icon is present (source `getFloat` = 20, PanelWidget.java:37). */
        const val ICON_OFFSET = 20f

        /** Header-block bottom padding (source `+ 8`, PanelWidget.java:45). */
        const val HEADER_GAP = 8f
    }
}
