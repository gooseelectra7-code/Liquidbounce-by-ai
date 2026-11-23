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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A stub content pane for the SideBar's Settings-group tabs (Configs / Friends / Waypoints) and the
 * Search tab — the source client backed each of these with a full widget (`ConfigsPage`, `FriendsPage`,
 * `WaypointsPage`). Those are out of scope for the C6 screen-shell cluster, so each is represented here
 * by a centred icon + title + a short "coming soon" note, drawn in the SystemDLC style. Consumes wheel
 * input so a scroll over an empty pane does nothing surprising.
 */
class PlaceholderPage(
    override val title: String,
    private val icon: SystemIcon? = null,
    private val message: String = "Coming soon",
) : Page() {

    override fun render(ctx: GuiGraphicsExtractor, mouseX: Float, mouseY: Float, alpha: Float) {
        val cx = contentX + contentWidth / 2f
        val cy = contentY + contentHeight / 2f

        var textTop = cy - TITLE_SIZE
        if (icon != null) {
            val iconX = cx - ICON_SIZE / 2f
            val iconY = cy - ICON_SIZE - ICON_TITLE_GAP - TITLE_SIZE
            SystemDraw.icon(ctx, icon, iconX, iconY, ICON_SIZE, SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha))
            textTop = iconY + ICON_SIZE + ICON_TITLE_GAP
        }

        SystemDraw.text(
            ctx, title, cx, textTop, TITLE_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.primary(), alpha), align = TextAlign.CENTER,
        )
        SystemDraw.text(
            ctx, message, cx, textTop + TITLE_SIZE + LINE_GAP, MESSAGE_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha), align = TextAlign.CENTER,
        )
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean = true

    private companion object {
        const val ICON_SIZE = 28f
        const val ICON_TITLE_GAP = 10f
        const val TITLE_SIZE = 16f
        const val MESSAGE_SIZE = 12f
        const val LINE_GAP = 6f
    }
}
