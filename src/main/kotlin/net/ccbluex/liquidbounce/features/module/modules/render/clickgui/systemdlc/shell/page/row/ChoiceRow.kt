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
 */
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * A mode / choice setting — the SystemDLC `client/gui/widget/ChoiceRow` role. The source ChoiceRow was a
 * two-option SegmentedRow; fork mode groups ([ModeValueGroup]) and choice lists ([ChoiceListValue]) carry
 * N options, so this cluster renders a compact CYCLING field instead of a full dropdown pop-over (the
 * modal-backed dropdown, matching `LeetClickGuiScreen.drawDropdownRow`, arrives in a later cluster).
 *
 * The label is on the left; a rounded field on the right shows the active option's tag with a
 * dropdown-arrows glyph. LEFT click cycles to the next option, RIGHT click to the previous — both write
 * through `Value.setByString(tag)`, which [ModeValueGroup] (ModeValueGroup.kt:83) and [ChoiceListValue]
 * (ChoiceListValue.kt:61) both implement. Enumeration mirrors
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.layoutSetting]
 * (LeetClickGuiScreen.kt:1660-1757): mode tags from `modes`, choice tags from `choices`.
 */
class ChoiceRow(value: Value<*>) : SettingRow(value) {

    private var fieldX: Float = 0f
    private var fieldW: Float = 0f

    override fun measure(width: Float): Float = max(FIELD_H, SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE))

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val textY = rowY + (height - SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE)) / 2f
        SystemDraw.text(
            ctx, value.name, rowX, textY, SETTING_ROW_LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )

        val tag = currentTag()
        val tagW = SystemDraw.textWidth(tag, VALUE_SIZE)
        val rightEdge = rowX + rowWidth
        val arrowsX = rightEdge - FIELD_PAD - ARROWS_SIZE
        val tagRight = arrowsX - ARROWS_GAP
        fieldW = FIELD_PAD + tagW + ARROWS_GAP + ARROWS_SIZE + FIELD_PAD
        fieldX = rightEdge - fieldW
        val fieldY = rowY + (height - FIELD_H) / 2f

        SystemDraw.panel(
            ctx, fieldX, fieldY, fieldW, FIELD_H, radius = FIELD_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
        )
        SystemDraw.text(
            ctx, tag, tagRight, rowY + (height - SystemDraw.lineHeight(VALUE_SIZE)) / 2f, VALUE_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.RIGHT,
        )
        SystemDraw.icon(
            ctx, SystemIcon.DROPDOWN_ARROWS, arrowsX, rowY + (height - ARROWS_SIZE) / 2f, ARROWS_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha),
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (!Widget.rectContains(fieldX, rowY, fieldW, height, mouseX, mouseY)) {
            return false
        }
        when (button) {
            0 -> cycle(1)
            1 -> cycle(-1)
            else -> return false
        }
        return true
    }

    private fun options(): List<String> = when (val v = value) {
        is ModeValueGroup<*> -> v.modes.map { it.tag }
        is ChoiceListValue<*> -> v.choices.map { it.tag }
        else -> emptyList()
    }

    private fun currentTag(): String = when (val v = value) {
        is ModeValueGroup<*> -> v.activeMode.tag
        is ChoiceListValue<*> -> v.get().tag
        else -> ""
    }

    private fun cycle(direction: Int) {
        val opts = options()
        if (opts.isEmpty()) {
            return
        }
        val current = opts.indexOf(currentTag()).coerceAtLeast(0)
        val next = ((current + direction) % opts.size + opts.size) % opts.size
        runCatching { value.setByString(opts[next]) }
    }

    private companion object {
        const val VALUE_SIZE = 11f
        const val FIELD_H = 16f
        const val FIELD_RADIUS = 4f
        const val FIELD_PAD = 6f
        const val ARROWS_SIZE = 8f
        const val ARROWS_GAP = 4f
    }
}
