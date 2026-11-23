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
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The sidebar profile row: a rounded avatar chip carrying the player's initial, plus the username
 * and current server address — ported from `client/gui/widget/ServerLabel.java`.
 *
 * ## Data source (retarget)
 * The source read `Feature.mc.getCurrentServerEntry()`, the game profile, and the `Protect` module's
 * name masking directly (:27-38 / :90-102). Those client-specific accessors are NOT wired here;
 * instead the owner sets [username] and [serverAddress] (a later cluster feeds the live values). The
 * two pure formatters are ported: [stripPort] (source `getStringByString`, :21-25, drops a trailing
 * `:port` that is not inside a `[..]` IPv6 literal) and [initialOf] (source `getStringByString2`,
 * :104-117, the first letter-or-digit upper-cased).
 *
 * ## Geometry (ServerLabel.java, 208x32 from the ctor line 18)
 *  - Avatar chip: `32 x 32`, radius 8 (`f12`, :54), fill [SystemDlcTheme.surface], 1 px
 *    [SystemDlcTheme.border] (`f13 = 1`, :51). Its x lerps `x -> x + 8` by [collapse]
 *    (`getFloatByFloatFloatFloat(x, collapse, x + 8)`, :47); y = row top.
 *    Decoded ShapeShader roles (A=width, C=fill, D=x, E=y, G/L/N/O=radius, H=height, K=borderColor,
 *    M=borderWidth) — cross-checked against SmallButton / ToggleButton.
 *  - Initial: 14 px [SystemDlcTheme.foreground] centred in the chip, baseline `y + 9` (:62-66).
 *  - Username: 14 px at `x + 48` (`f + 32 + 8`, :69), `y + 3` (:73); server: 12 px at `x + 48`,
 *    `y + 17` (:74). Both [SystemDlcTheme.foreground], faded by `renderAlpha * (1 - collapse)` (:71),
 *    scissor-clipped to `max(0, x + width - 8 - (x + 48))` x 36 (:70/:81), and trimmed to that width.
 */
class ServerLabel : SliderWidget("", ROW_WIDTH, ROW_HEIGHT) {

    /** The player name shown on line 1 and used for the avatar initial. */
    var username: String? = null

    /** The server address shown on line 2 (its trailing port is stripped by [stripPort]). */
    var serverAddress: String? = null

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val foreground = SystemDlcTheme.foreground()
        val chipExpandedX = x + CHIP_INSET
        val chipX = lerp(x, chipExpandedX, collapse)
        val chipY = y

        SystemDraw.panel(
            ctx,
            chipX,
            chipY,
            CHIP_SIZE,
            CHIP_SIZE,
            radius = CHIP_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.surface(), alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
            borderWidth = CHIP_BORDER,
        )

        val initial = initialOf(username)
        if (initial.isNotEmpty()) {
            SystemDraw.text(
                ctx,
                initial,
                chipX + CHIP_SIZE / 2f,
                y + INITIAL_Y,
                INITIAL_SIZE,
                SystemDraw.withAlpha(foreground, alpha),
                align = TextAlign.CENTER,
            )
        }

        val textX = chipExpandedX + CHIP_SIZE + TEXT_GAP
        val clipWidth = maxOf(0f, x + width - EDGE_PAD - textX)
        val textAlpha = alpha * (1f - collapse)
        if (clipWidth > 0f && textAlpha > TEXT_ALPHA_EPSILON) {
            val nameLine = trimToWidth(username ?: "", clipWidth, USERNAME_SIZE)
            val serverLine = trimToWidth(stripPort(serverAddress ?: ""), clipWidth, SERVER_SIZE)
            SystemDraw.scissor(ctx, textX, y, clipWidth, SCISSOR_HEIGHT) {
                SystemDraw.text(
                    ctx, nameLine, textX, y + USERNAME_Y, USERNAME_SIZE,
                    SystemDraw.withAlpha(foreground, textAlpha),
                )
                SystemDraw.text(
                    ctx, serverLine, textX, y + SERVER_Y, SERVER_SIZE,
                    SystemDraw.withAlpha(foreground, textAlpha),
                )
            }
        }
    }

    private fun trimToWidth(text: String, maxWidth: Float, size: Float): String {
        if (text.isEmpty() || SystemDraw.textWidth(text, size) <= maxWidth) {
            return text
        }
        val ellipsis = "..."
        var end = text.length
        while (end > 0 && SystemDraw.textWidth(text.substring(0, end) + ellipsis, size) > maxWidth) {
            end--
        }
        return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
    }

    private companion object {
        const val ROW_WIDTH = 208f
        const val ROW_HEIGHT = 32f
        const val CHIP_SIZE = 32f
        const val CHIP_RADIUS = 8f
        const val CHIP_BORDER = 1f

        /** Expanded chip left inset `16 - 8` (source `value235 + 16 - 8`, ServerLabel.java:44). */
        const val CHIP_INSET = 8f

        /** Avatar initial baseline (source `value236 + 9`, ServerLabel.java:63). */
        const val INITIAL_Y = 9f
        const val INITIAL_SIZE = 14f

        /** Gap between chip and text block (source `+ 8`, ServerLabel.java:69). */
        const val TEXT_GAP = 8f

        /** Right edge padding (source `- 8`, ServerLabel.java:70). */
        const val EDGE_PAD = 8f

        const val USERNAME_SIZE = 14f
        const val SERVER_SIZE = 12f

        /** Username line top (source `value236 + 3`, ServerLabel.java:73). */
        const val USERNAME_Y = 3f

        /** Server line top (source `value236 + 17`, ServerLabel.java:74). */
        const val SERVER_Y = 17f

        /** Text scissor height (source `f18 = 36`, ServerLabel.java:79). */
        const val SCISSOR_HEIGHT = 36f

        const val TEXT_ALPHA_EPSILON = 0.001f

        fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

        /**
         * Drop a trailing `:port` unless it sits inside an IPv6 `[..]` literal (source
         * `getStringByString`, ServerLabel.java:21-25): keep everything before the last `:` only
         * when that `:` comes after the last `]`.
         */
        fun stripPort(address: String): String {
            val colon = address.lastIndexOf(':')
            val bracket = address.lastIndexOf(']')
            return if (colon > bracket && colon > 0) address.substring(0, colon) else address
        }

        /** First letter-or-digit of [name], upper-cased (source `getStringByString2`, :104-117). */
        fun initialOf(name: String?): String {
            if (name == null) {
                return ""
            }
            for (c in name) {
                if (c.isLetterOrDigit()) {
                    return c.uppercaseChar().toString()
                }
            }
            return ""
        }
    }
}
