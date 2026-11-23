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
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.SmallButton
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * A boolean setting — ported from `client/gui/widget/BooleanRow.java`. The label sits on the left and a
 * 16 px [SmallButton] checkbox is right-aligned (source draws the button at `x + width - 16`,
 * BooleanRow.java:30-31; row height `max(block, 16)`, :44). The button owns its own check-reveal /
 * fill animation (C5 atom); this row only feeds it the current state and forwards the click.
 */
class BooleanRow(value: Value<Boolean>) : SettingRow(value) {

    private val booleanValue: Value<Boolean> = value

    private val button = SmallButton(value.get()).apply {
        onClickAction = { booleanValue.set(!booleanValue.get()) }
    }

    override fun measure(width: Float): Float = max(BOX_SIZE, SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE))

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        button.setState(booleanValue.get())

        val textY = rowY + (height - SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE)) / 2f
        SystemDraw.text(
            ctx, value.name, rowX, textY, SETTING_ROW_LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )

        button.placeAt(rowX + rowWidth - BOX_SIZE, rowY + (height - BOX_SIZE) / 2f)
        button.render(ctx, alpha, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean =
        this.button.mouseClicked(mouseX, mouseY, button)

    private companion object {
        const val BOX_SIZE = 16f
    }
}
