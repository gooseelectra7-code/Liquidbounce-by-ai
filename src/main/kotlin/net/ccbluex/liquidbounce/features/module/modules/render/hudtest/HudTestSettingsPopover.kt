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

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The settings popover of the reference.
 *
 * Clicking a panel's `...` opens this card beside that panel; every row is one of the panel's real
 * [HudTestOption]s, so flipping a toggle immediately changes what the owning panel draws. The `x`
 * closes it again. It only exists while the HUD is interactive (a screen is open), because that is
 * the only time it can be clicked.
 *
 * Geometry is the shared list-panel system with no row icons: label at the padding, the 21x12
 * toggle right-aligned to `width - 15`.
 */
internal object HudTestSettingsPopover {

    private const val TITLE = "Settings"
    private const val GAP = 8f
    private const val CLOSE_SIZE = 9f
    private const val TOGGLE_WIDTH = 21f
    private const val TOGGLE_HEIGHT = 12f

    private var owner: HudTestPanel? = null
    private var visibility = 0f

    /** Opens the popover for [panel], or closes it when it is already showing that panel. */
    fun toggleFor(panel: HudTestPanel) {
        owner = if (owner === panel) null else panel
    }

    /** Closes the popover, whichever panel it belongs to. */
    fun close() {
        owner = null
    }

    /** Draws the popover; called after every panel so it always sits on top of them. */
    fun render(event: OverlayRenderEvent, dt: Float) {
        val panel = owner
        val options = panel?.displayOptions.orEmpty()
        val present = panel != null && panel.enabled && options.isNotEmpty() && ModuleHudTest.editMode()

        val step = dt * 1000f / HudTestTheme.FADE_MS
        visibility = (visibility + if (present) step else -step).coerceIn(0f, 1f)
        val alpha = HudTestTheme.easeOutCubic(visibility)
        if (panel == null || alpha <= MIN_ALPHA || options.isEmpty()) {
            return
        }

        for (option in options) {
            option.advance(dt)
        }

        val frame = place(panel, options.size, alpha)
        val ctx = event.context
        ctx.pushFrame(frame)
        ctx.hudCard(
            frame.width, frame.height, HudTestTheme.LIST_RADIUS, alpha, frame.density, ModuleHudTest.glass(ctx),
        )
        ctx.hudShapes(0f, 0f, frame.width, frame.height, frame.density) {
            paintChrome(this, options, alpha)
        }
        writeText(ctx, frame, options, alpha)
        ctx.popFrame()

        registerButtons(frame, options)
    }

    /** Docks the card to the right of its owner, flipping to the left when it would not fit. */
    private fun place(panel: HudTestPanel, rowCount: Int, alpha: Float): HudTestFrame {
        val unit = panel.frameUnit
        val height = HudTestTheme.listHeight(rowCount)
        val cardWidth = HudTestTheme.LIST_WIDTH * unit
        val cardHeight = height * unit
        val screenWidth = mc.window.guiScaledWidth.toFloat()
        val screenHeight = mc.window.guiScaledHeight.toFloat()

        var x = panel.guiRight + GAP * unit
        if (x + cardWidth > screenWidth) {
            x = panel.guiLeft - cardWidth - GAP * unit
        }
        val guiScale = if (unit > 0f) panel.frameDensity / unit else 1f
        return HudTestFrame(
            x = snapToPixel(x.coerceIn(0f, (screenWidth - cardWidth).coerceAtLeast(0f)), guiScale),
            y = snapToPixel(panel.guiTop.coerceIn(0f, (screenHeight - cardHeight).coerceAtLeast(0f)), guiScale),
            unit = unit,
            density = panel.frameDensity,
            alpha = alpha,
            grow = APPEAR_SCALE + (1f - APPEAR_SCALE) * alpha,
            width = HudTestTheme.LIST_WIDTH,
            height = height,
        )
    }

    private fun paintChrome(shapes: HudTestShapes, options: List<HudTestOption>, alpha: Float) {
        shapes.iconGear(
            HudTestTheme.ICON_CENTER_X - HudTestTheme.ICON * 0.5f,
            (HudTestTheme.HEADER_HEIGHT - HudTestTheme.ICON) * 0.5f,
            HudTestTheme.ICON,
            scaleAlpha(HudTestTheme.ACCENT, alpha),
        )
        shapes.iconClose(
            HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - CLOSE_SIZE,
            (HudTestTheme.HEADER_HEIGHT - CLOSE_SIZE) * 0.5f,
            CLOSE_SIZE,
            scaleAlpha(HudTestTheme.UNIT_DIM, alpha),
        )
        shapes.rect(
            HAIRLINE_INSET,
            HudTestTheme.HEADER_HEIGHT,
            HudTestTheme.LIST_WIDTH - HAIRLINE_INSET * 2f,
            1f,
            scaleAlpha(HudTestTheme.HAIRLINE, alpha),
        )

        for (index in options.indices) {
            shapes.glyphToggle(
                HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - TOGGLE_WIDTH,
                rowTop(index) + (HudTestTheme.ROW_PITCH - TOGGLE_HEIGHT) * 0.5f,
                TOGGLE_WIDTH,
                TOGGLE_HEIGHT,
                options[index].knob,
            )
        }
    }

    private fun writeText(
        ctx: GuiGraphicsExtractor,
        frame: HudTestFrame,
        options: List<HudTestOption>,
        alpha: Float,
    ) {
        val density = frame.density
        ctx.hudText(
            WatermarkFont.BRAND, TITLE, HudTestTheme.LABEL_X,
            textTopCentered(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, 0f, HudTestTheme.HEADER_HEIGHT),
            HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
        )
        for ((index, option) in options.withIndex()) {
            ctx.hudText(
                WatermarkFont.BRAND, option.label, HudTestTheme.PAD_X,
                textTopCentered(
                    WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, rowTop(index), HudTestTheme.ROW_PITCH,
                ),
                HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
            )
        }
    }

    private fun registerButtons(frame: HudTestFrame, options: List<HudTestOption>) {
        HudTestInput.addButton(
            frame,
            HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - CLOSE_SIZE - 5f,
            (HudTestTheme.HEADER_HEIGHT - CLOSE_SIZE) * 0.5f - 5f,
            CLOSE_SIZE + 10f,
            CLOSE_SIZE + 10f,
        ) { close() }

        for ((index, option) in options.withIndex()) {
            HudTestInput.addButton(
                frame,
                HudTestTheme.PAD_X,
                rowTop(index),
                HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X * 2f,
                HudTestTheme.ROW_PITCH,
            ) { option.toggle() }
        }
    }

    private fun rowTop(index: Int): Float = HudTestTheme.ROW_START + index * HudTestTheme.ROW_PITCH

    private const val MIN_ALPHA = 0.004f
    private const val HAIRLINE_INSET = 13f
    private const val APPEAR_SCALE = 0.94f
}
