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

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * An inline-expanding dropdown for the finite, enumerable "choice" value families — the SystemDLC restyle
 * of the native GUI's dropdown widget
 * ([net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.drawDropdownRow],
 * LeetClickGuiScreen.kt:2220-2290, over [net.ccbluex.liquidbounce.features.module.modules.render.clickgui
 * .LeetClickGuiScreen.drawSelectList], LeetClickGuiScreen.kt:2145-2211).
 *
 * ## Collapsed
 * The setting name sits on the left; a rounded field on the right shows the current selection (or the
 * `k/n` selected-count for multi-choice) plus a [SystemIcon.DROPDOWN_ARROWS] chevron. A LEFT click on the
 * field toggles the expansion.
 *
 * ## Expanded
 * A raised option list is drawn under the field, one selectable line per option. The selected option(s)
 * are highlighted (a primary()-tinted background + primary() text; multi-choice also gets a
 * [SystemIcon.SUCCESS_CHECKBOX] check). A LEFT click on a line: single-choice picks it and closes the
 * list; multi-choice toggles it and KEEPS the list open — the exact behaviour LeetClickGuiScreen wires per
 * type (single `onPick = setByString + close`, LeetClickGuiScreen.kt:1668/1755; multi `onPick = toggle`,
 * no close, LeetClickGuiScreen.kt:1739-1743).
 *
 * ## Value access (reproduced 1:1 from LeetClickGuiScreen.layoutSetting, LeetClickGuiScreen.kt:1660-1757)
 *  - [ModeValueGroup] (ValueType.CHOICE): options `modes.map { tag }`, current `activeMode.tag`, write
 *    `setByString(tag)` (ModeValueGroup.kt:42/45/83).
 *  - [ChoiceListValue] (ValueType.CHOOSE): options `choices.map { tag }`, current `get().tag`, write
 *    `setByString(tag)` (ChoiceListValue.kt:39/61).
 *  - [MultiChoiceListValue] (ValueType.MULTI_CHOOSE): options `choices.map { tag }`, selected `get()`
 *    (membership by tag), label `${get().size}/${choices.size}`, toggle `toggle(choice)`
 *    (MultiChoiceListValue.kt:42/84/103).
 *
 * ## Inline geometry
 * [measure] returns the collapsed band height when closed and `band + gap + listHeight` when open, so the
 * card's `expandedHeight` sum ([net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc
 * .shell.page.ModuleRow.expandedHeight]) grows and reflows the rows below. There is no per-row reveal
 * tween: [measure] must be ctx-independent and stable within a frame (it is called from both the layout
 * and the input path), so the list snaps in — the same static approach [SliderRow] / [ChoiceRow] use. All
 * field/list rects derive from the row rect and ctx-free [SystemDraw.textWidth], so [draw] and
 * [mouseClicked] compute identical hitboxes.
 *
 * All input is LEFT-button, mirroring [SliderRow] / [BooleanRow]: in the native GUI the dropdown opens on
 * RMB only because LMB is claimed by the module-toggle at card level; here the field lives isolated in the
 * expanded settings body, so LMB is free and matches the rest of this row set.
 */
class DropdownRow(value: Value<*>) : SettingRow(value) {

    private val multi: Boolean = value is MultiChoiceListValue<*>

    private var expanded: Boolean = false

    /** A computed rectangle (GUI units), used for both drawing and hit testing. */
    private class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    override fun measure(width: Float): Float {
        val band = bandHeight()
        if (!expanded) {
            return band
        }
        val count = optionTags().size
        if (count == 0) {
            return band
        }
        return band + LIST_GAP + (count * OPTION_H + 2f * LIST_PAD)
    }

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val band = bandHeight()

        // setting name (left), vertically centred in the collapsed field band
        SystemDraw.text(
            ctx, value.name, rowX, rowY + (band - SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE)) / 2f,
            SETTING_ROW_LABEL_SIZE, SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha),
            align = TextAlign.LEFT,
        )

        drawField(ctx, alpha)

        if (expanded && optionTags().isNotEmpty()) {
            drawList(ctx, alpha, mouseX, mouseY)
        }
    }

    private fun drawField(ctx: GuiGraphicsExtractor, alpha: Float) {
        val f = fieldBox()
        val label = currentLabel()

        val border = if (expanded) SystemDlcTheme.primary() else SystemDlcTheme.border()
        SystemDraw.panel(
            ctx, f.x, f.y, f.w, f.h, radius = FIELD_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
            border = SystemDraw.withAlpha(border, alpha),
        )

        val chevronX = f.x + f.w - FIELD_PAD - CHEVRON_SIZE
        SystemDraw.text(
            ctx, label, chevronX - CHEVRON_GAP, f.y + (f.h - SystemDraw.lineHeight(VALUE_SIZE)) / 2f,
            VALUE_SIZE, SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.RIGHT,
        )
        SystemDraw.icon(
            ctx, SystemIcon.DROPDOWN_ARROWS, chevronX, f.y + (f.h - CHEVRON_SIZE) / 2f, CHEVRON_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha),
        )
    }

    private fun drawList(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val list = listBox()
        val tags = optionTags()

        SystemDraw.panel(
            ctx, list.x, list.y, list.w, list.h, radius = LIST_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
        )

        val textH = SystemDraw.lineHeight(VALUE_SIZE)
        tags.forEachIndexed { index, tag ->
            val oy = list.y + LIST_PAD + index * OPTION_H
            val selected = isSelected(tag)
            val hovered = Widget.rectContains(list.x, oy, list.w, OPTION_H, mouseX, mouseY)

            when {
                selected -> SystemDraw.panel(
                    ctx, list.x + LIST_PAD, oy, list.w - 2f * LIST_PAD, OPTION_H, radius = OPTION_RADIUS,
                    fill = SystemDraw.withAlpha(SystemDlcTheme.primary(), SELECTED_BG_ALPHA * alpha),
                )
                hovered -> SystemDraw.panel(
                    ctx, list.x + LIST_PAD, oy, list.w - 2f * LIST_PAD, OPTION_H, radius = OPTION_RADIUS,
                    fill = SystemDraw.withAlpha(SystemDlcTheme.border(), HOVER_BG_ALPHA * alpha),
                )
            }

            val textColor = when {
                selected -> SystemDlcTheme.primary()
                hovered -> SystemDlcTheme.foreground()
                else -> SystemDlcTheme.mutedFg()
            }
            SystemDraw.text(
                ctx, tag, list.x + LIST_PAD + OPTION_TEXT_INSET, oy + (OPTION_H - textH) / 2f, VALUE_SIZE,
                SystemDraw.withAlpha(textColor, alpha), align = TextAlign.LEFT,
            )

            if (multi && selected) {
                val checkX = list.x + list.w - LIST_PAD - OPTION_TEXT_INSET - CHECK_SIZE
                SystemDraw.icon(
                    ctx, SystemIcon.SUCCESS_CHECKBOX, checkX, oy + (OPTION_H - CHECK_SIZE) / 2f, CHECK_SIZE,
                    SystemDraw.withAlpha(SystemDlcTheme.primary(), alpha),
                )
            }
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != 0) {
            return false
        }

        val f = fieldBox()
        if (Widget.rectContains(f.x, f.y, f.w, f.h, mouseX, mouseY)) {
            if (optionTags().isNotEmpty()) {
                expanded = !expanded
            }
            return true
        }

        if (!expanded) {
            return false
        }

        val list = listBox()
        val tags = optionTags()
        tags.forEachIndexed { index, tag ->
            val oy = list.y + LIST_PAD + index * OPTION_H
            if (Widget.rectContains(list.x, oy, list.w, OPTION_H, mouseX, mouseY)) {
                pick(tag)
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- geometry (ctx-free)

    private fun bandHeight(): Float = max(FIELD_H, SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE))

    private fun fieldBox(): Box {
        val labelW = SystemDraw.textWidth(currentLabel(), VALUE_SIZE)
        val w = (FIELD_PAD + labelW + CHEVRON_GAP + CHEVRON_SIZE + FIELD_PAD).coerceAtMost(rowWidth)
        val x = rowX + rowWidth - w
        val y = rowY + (bandHeight() - FIELD_H) / 2f
        return Box(x, y, w, FIELD_H)
    }

    private fun listBox(): Box {
        val tags = optionTags()
        var widest = 0f
        for (tag in tags) {
            widest = max(widest, SystemDraw.textWidth(tag, VALUE_SIZE))
        }
        val checkW = if (multi) CHECK_GAP + CHECK_SIZE else 0f
        val innerW = LIST_PAD + OPTION_TEXT_INSET + widest + checkW + OPTION_TEXT_INSET + LIST_PAD
        val field = fieldBox()
        val w = max(field.w, innerW).coerceAtMost(rowWidth)
        val x = rowX + rowWidth - w
        val y = field.y + FIELD_H + LIST_GAP
        val h = tags.size * OPTION_H + 2f * LIST_PAD
        return Box(x, y, w, h)
    }

    // ---------------------------------------------------------------- value access

    private fun optionTags(): List<String> = when (val v = value) {
        is ModeValueGroup<*> -> v.modes.map { it.tag }
        is MultiChoiceListValue<*> -> v.choices.map { it.tag }
        is ChoiceListValue<*> -> v.choices.map { it.tag }
        else -> emptyList()
    }

    private fun isSelected(tag: String): Boolean = when (val v = value) {
        is ModeValueGroup<*> -> v.activeMode.tag == tag
        is MultiChoiceListValue<*> -> v.get().any { it.tag == tag }
        is ChoiceListValue<*> -> v.get().tag == tag
        else -> false
    }

    private fun currentLabel(): String = when (val v = value) {
        is ModeValueGroup<*> -> v.activeMode.tag
        is MultiChoiceListValue<*> -> "${v.get().size}/${v.choices.size}"
        is ChoiceListValue<*> -> v.get().tag
        else -> ""
    }

    private fun pick(tag: String) {
        when (val v = value) {
            is ModeValueGroup<*> -> {
                runCatching { v.setByString(tag) }
                expanded = false
            }
            is ChoiceListValue<*> -> {
                runCatching { v.setByString(tag) }
                expanded = false
            }
            is MultiChoiceListValue<*> -> {
                @Suppress("UNCHECKED_CAST")
                val mv = v as MultiChoiceListValue<Tagged>
                mv.choices.firstOrNull { it.tag == tag }?.let { choice -> runCatching { mv.toggle(choice) } }
                // multi-select: keep the list open so several options can be toggled
            }
            else -> Unit
        }
    }

    private companion object {
        const val VALUE_SIZE = 11f
        const val FIELD_H = 16f
        const val FIELD_RADIUS = 4f
        const val FIELD_PAD = 6f
        const val CHEVRON_SIZE = 8f
        const val CHEVRON_GAP = 4f

        const val LIST_GAP = 3f
        const val LIST_PAD = 3f
        const val LIST_RADIUS = 6f
        const val OPTION_H = 15f
        const val OPTION_RADIUS = 4f
        const val OPTION_TEXT_INSET = 6f
        const val CHECK_SIZE = 8f
        const val CHECK_GAP = 6f

        const val SELECTED_BG_ALPHA = 0.16f
        const val HOVER_BG_ALPHA = 0.55f
    }
}
