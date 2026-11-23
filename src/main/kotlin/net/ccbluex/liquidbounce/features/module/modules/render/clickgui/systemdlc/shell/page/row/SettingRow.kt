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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Label text size shared by the simple rows (source setting-name font ~11-12 px). */
internal const val SETTING_ROW_LABEL_SIZE = 11f

/**
 * One setting row inside an expanded [net.ccbluex.liquidbounce.features.module.modules.render.clickgui
 * .systemdlc.shell.page.ModuleRow] — the SystemDLC `client/gui/widget/PanelWidget` role, narrowed to the
 * handful of simple rows cluster C7 ships.
 *
 * The host lays a row out with [place] (position + measured [height], no drawing — mirrors the source
 * measure/draw split) and then paints it with [draw]. Text-metric measuring needs no `ctx`, so [place]
 * can run in the input path too, keeping hit rects consistent with the last frame.
 *
 * @property value the fork [Value] this row edits (its `name` is the row label).
 */
abstract class SettingRow(val value: Value<*>) {

    protected var rowX: Float = 0f
        private set
    protected var rowY: Float = 0f
        private set
    protected var rowWidth: Float = 0f
        private set

    /** Measured height of the last [place] (GUI units). */
    var height: Float = 0f
        private set

    /** Measured row height at [width], ctx-independent (text metrics only) — source `getFloat5`. */
    abstract fun measure(width: Float): Float

    /** Place the row at ([x], [y]) with [width] and (re)measure its [height]. No drawing. */
    fun place(x: Float, y: Float, width: Float) {
        rowX = x
        rowY = y
        rowWidth = width
        height = measure(width)
    }

    /** Draw the row into its last [place]. [alpha] is already the card's faded opacity. */
    abstract fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float)

    open fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    open fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    open fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean = false

    /** Keyboard input, offered to every row; only a focused row (e.g. a text field) consumes it. */
    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    /** Typed character, offered to every row; only a focused text field consumes it. */
    open fun charTyped(char: Char, modifiers: Int): Boolean = false

    /** True while this row holds a pointer-drag capture (a slider being dragged). */
    open val capturing: Boolean get() = false

    /** Point-in-row test against the last [place]. */
    protected fun contains(mouseX: Float, mouseY: Float): Boolean =
        Widget.rectContains(rowX, rowY, rowWidth, height, mouseX, mouseY)

    companion object {
        /**
         * The Value -> row dispatcher — the fork analog of the SystemDLC `data/SettingWidgetFactory`
         * (SettingWidgetFactory.java:48-66). Type checks come FIRST (mode/choice groups are Kotlin
         * subtypes, not a `valueType`), then a [ValueType] switch, mirroring the walk order of
         * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.layoutSetting]
         * (LeetClickGuiScreen.kt:1659-1855).
         *
         * Dispatch table (which this cluster ACTUALLY renders vs. a read-only [PlaceholderRow]):
         *  - [ModeValueGroup] / [ChoiceListValue]      -> [ChoiceRow]   (cycles the mode/choice)
         *  - [RangedValue] FLOAT / INT                 -> [SliderRow]
         *  - [RangedValue] FLOAT_RANGE / INT_RANGE     -> placeholder (two-handle range: a later cluster)
         *  - [ValueType.BOOLEAN]                       -> [BooleanRow]
         *  - everything else (TEXT / COLOR / KEY / BIND / LIST / VECTOR / TOGGLEABLE / CONFIGURABLE group /
         *    ALIGNMENT / registry pickers / curve / ...) -> [PlaceholderRow] (name + current value text)
         *
         * There is no fork `ValueType.ACTION` (ValueType.kt has none), so [ActionRow] is provided by the
         * row set but is never produced here — see its KDoc.
         */
        fun create(value: Value<*>): SettingRow = when (value) {
            // Most-specific ValueGroup subtypes first: both group flavours become an expandable
            // GroupRow, so they precede the choice-list DropdownRow arms and the plain ValueGroup.
            is ToggleableValueGroup -> GroupRow(value)
            is ModeValueGroup<*> -> GroupRow(value)
            is MultiChoiceListValue<*> -> DropdownRow(value)
            is ChoiceListValue<*> -> DropdownRow(value)
            is ValueGroup -> GroupRow(value)
            is RangedValue<*> -> when (value.valueType) {
                ValueType.FLOAT, ValueType.INT -> SliderRow(value)
                ValueType.FLOAT_RANGE, ValueType.INT_RANGE -> RangeRow(value)
                else -> PlaceholderRow(value)
            }
            else -> when (value.valueType) {
                ValueType.BOOLEAN -> {
                    @Suppress("UNCHECKED_CAST")
                    BooleanRow(value as Value<Boolean>)
                }
                ValueType.COLOR -> ColorRow(value)
                ValueType.TEXT -> TextRow(value)
                else -> PlaceholderRow(value)
            }
        }
    }
}

/**
 * The read-only fallback row: the setting name on the left, its current value as text on the right, both
 * muted. Rendered for every [Value] type this cluster does not yet give a real widget (COLOR, TEXT, LIST,
 * KEY/BIND, VECTOR, nested TOGGLEABLE/CONFIGURABLE groups, ALIGNMENT, ...); a later cluster replaces these
 * with the corresponding SystemDLC rows. The current value comes from `Value.getTagValue()`
 * (Value.kt:162-166) which already stringifies ranges, tagged choices and multi-choice counts sensibly.
 */
class PlaceholderRow(value: Value<*>) : SettingRow(value) {

    override fun measure(width: Float): Float = ROW_H

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val textY = rowY + (height - SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE)) / 2f
        SystemDraw.text(
            ctx, value.name, rowX, textY, SETTING_ROW_LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )
        val valueText = valueSummary()
        SystemDraw.text(
            ctx, valueText, rowX + rowWidth, textY, SETTING_ROW_LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha), align = TextAlign.RIGHT,
        )
    }

    /**
     * A short read-only summary of the current value. Nested groups (which have no scalar value — `get()`
     * returns their child collection) are handled specially so the placeholder never dumps a list's
     * `toString`; a toggleable group shows On/Off, a plain group shows nothing. Everything else uses
     * `Value.getTagValue()` (ranges, tagged choices, `k/n` multi-choice), truncated for safety.
     */
    private fun valueSummary(): String = when {
        value is ToggleableValueGroup -> if (value.enabled) "On" else "Off"
        value is ValueGroup -> ""
        else -> runCatching { value.getTagValue().toString() }.getOrDefault("").take(MAX_SUMMARY)
    }

    private companion object {
        const val ROW_H = 16f
        const val MAX_SUMMARY = 24
    }
}
