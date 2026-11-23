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
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A full-width action button — ported from `client/gui/widget/ActionRow.java` (a `32 px` tall rounded
 * button, radius 8, 1 px border, that fires its setting's action on a left click, ActionRow.java:26-100).
 *
 * ## Not currently dispatched
 * The fork's [net.ccbluex.liquidbounce.config.types.ValueType] enum has NO action/button type (verified in
 * ValueType.kt), so [SettingRow.create] never produces an [ActionRow] — unlike the source, whose
 * `SettingWidgetFactory` mapped `ActionSetting -> ActionRow`. It is provided here to complete the C7 row
 * set and to mirror the source geometry; a later cluster that introduces an action setting (or a per-card
 * "reset" affordance) can construct it with an [onInvoke] callback. The [value]'s `name` is the label.
 */
class ActionRow(value: Value<*>, private val onInvoke: () -> Unit) : SettingRow(value) {

    override fun measure(width: Float): Float = BUTTON_H

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val hovered = Widget.rectContains(rowX, rowY, rowWidth, height, mouseX, mouseY)
        val fill = if (hovered) SystemDlcTheme.elevated() else SystemDlcTheme.surface()

        SystemDraw.panel(
            ctx, rowX, rowY, rowWidth, height, radius = RADIUS,
            fill = SystemDraw.withAlpha(fill, alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
            shadow = SystemDraw.withAlpha(SystemDlcTheme.SHADOW, alpha),
            shadowBlur = 1f,
        )
        SystemDraw.text(
            ctx, value.name, rowX + rowWidth / 2f, rowY + (height - SystemDraw.lineHeight(LABEL_TEXT)) / 2f,
            LABEL_TEXT, SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.CENTER,
        )
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            onInvoke()
            return true
        }
        return false
    }

    private companion object {
        const val BUTTON_H = 32f
        const val RADIUS = 8f
        const val LABEL_TEXT = 12f
    }
}
