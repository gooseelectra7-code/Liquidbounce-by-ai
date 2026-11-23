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

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/** The leading marker of a list row, drawn in the icon column the header mark shares. */
internal sealed interface HudTestRowIcon {

    /** A vector glyph painted into the panel's shape batch. */
    class Vector(val argb: Int, val draw: HudTestShapes.(Float, Float, Float, Int) -> Unit) : HudTestRowIcon

    /** A gui sprite, e.g. the real mob effect texture. */
    class Sprite(val id: Identifier) : HudTestRowIcon

    /** A real item, drawn through the sharp resampler. */
    class Stack(val stack: ItemStack) : HudTestRowIcon
}

/**
 * One row of a list panel: a marker, a name, an optional dim suffix glued to the name (the potion
 * amplifier) and a right-aligned trailing value.
 */
internal class HudTestRow(
    val icon: HudTestRowIcon?,
    val label: String,
    val suffix: String = "",
    val trailing: String = "",
)

/**
 * The shared list-panel system of the reference — Potions, Keybinds and Cooldowns are the very
 * same card with different rows:
 *
 * ```
 * width 188, radius 12, padding-x 15
 * header 36 high: mark centred on x=21, title at x=33, "..." right-aligned to width-15
 * hairline at y=36 from x=13 to width-13, white @ 6 %
 * rows from y=40, pitch 26: mark centred on x=21, label at x=33, value right-aligned to width-15
 * height = 48 + 26 * rows
 * ```
 *
 * Clicking the header's `...` opens [HudTestSettingsPopover] beside the card; the toggles it shows
 * are this panel's own [displayOptions], so they drive what actually gets drawn here.
 *
 * A panel whose rows carry no marker of their own says so with [rowIcons]; its labels then move
 * into the icon column, so a keybind list is not left with an empty gutter down its side. The
 * header title does the same when the Icon toggle is off and the header mark goes with it.
 */
internal abstract class HudTestListPanel(
    parent: EventListener,
    name: String,
    anchor: HudTestAnchor,
    offsetX: Float,
    offsetY: Float,
    private val title: String,
) : HudTestPanel(parent, name, anchor, offsetX, offsetY) {

    protected val optionHeader = displayOption("Header")
    protected val optionIcon = displayOption("Icon")
    protected val optionName = displayOption("Name")

    override val displayOptions: List<HudTestOption>
        get() = listOf(optionHeader, optionIcon, optionName) + extraOptions

    /** The panel-specific rows of the settings popover, after Header / Icon / Name. */
    protected open val extraOptions: List<HudTestOption> get() = emptyList()

    /** Shown instead of the real rows while a screen is open and there is nothing to list. */
    protected abstract val emptyLabel: String

    /**
     * Whether this panel's rows carry a marker at all. `false` gives up the icon column for the
     * rows — the header keeps its own mark — so the labels are not left hanging off an empty
     * gutter.
     */
    protected open val rowIcons: Boolean get() = true

    /** Whether the rows draw into the icon column this frame. */
    private val markersShown: Boolean get() = optionIcon.enabled && rowIcons

    /** Where the header title starts: beside its mark, or at the padding once the mark is off. */
    private val titleX: Float
        get() = if (optionIcon.enabled) HudTestTheme.LABEL_X else HudTestTheme.PAD_X

    /** Where a row label starts: beside its marker, or in the icon column when there is none. */
    private val labelX: Float
        get() = if (markersShown) HudTestTheme.LABEL_X else HudTestTheme.PAD_X

    /** The lavender glyph in the header. */
    protected abstract fun drawHeaderIcon(shapes: HudTestShapes, x: Float, y: Float, size: Float, argb: Int)

    /** The live rows; an empty list hides the panel unless the HUD is in edit mode. */
    protected abstract fun collectRows(): List<HudTestRow>

    final override fun render(event: OverlayRenderEvent, dt: Float) {
        val rows = collectRows()
        val editing = ModuleHudTest.editMode()
        val alpha = fade(rows.isNotEmpty() || editing, dt)
        if (alpha <= MIN_ALPHA) {
            return
        }

        val shown = rows.ifEmpty { listOf(HudTestRow(null, emptyLabel)) }
        val headerShown = optionHeader.enabled
        val contentTop = if (headerShown) HudTestTheme.ROW_START else HudTestTheme.BOTTOM_PAD
        val height = contentTop + HudTestTheme.ROW_PITCH * shown.size + HudTestTheme.BOTTOM_PAD
        val frame = frame(HudTestTheme.LIST_WIDTH, height, alpha)

        val ctx = event.context
        ctx.pushFrame(frame)
        ctx.hudCard(frame.width, height, HudTestTheme.LIST_RADIUS, alpha, frame.density, ModuleHudTest.glass(ctx))
        ctx.hudShapes(0f, 0f, frame.width, height, frame.density) {
            if (headerShown) {
                paintHeader(this, alpha)
            }
            paintRowIcons(this, shown, contentTop, alpha)
        }
        if (headerShown) {
            writeHeaderText(ctx, frame, alpha)
        }
        writeRows(ctx, frame, shown, contentTop, alpha)
        ctx.popFrame()

        drawRowTextures(ctx, frame, shown, contentTop)
    }

    // --- Header ---------------------------------------------------------------------------------

    private fun paintHeader(shapes: HudTestShapes, alpha: Float) {
        if (optionIcon.enabled) {
            drawHeaderIcon(
                shapes,
                HudTestTheme.ICON_CENTER_X - HudTestTheme.ICON * 0.5f,
                (HudTestTheme.HEADER_HEIGHT - HudTestTheme.ICON) * 0.5f,
                HudTestTheme.ICON,
                scaleAlpha(HudTestTheme.ACCENT, alpha),
            )
        }
        shapes.iconEllipsis(
            HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - ELLIPSIS_WIDTH,
            HudTestTheme.HEADER_HEIGHT * 0.5f,
            ELLIPSIS_WIDTH,
            scaleAlpha(HudTestTheme.UNIT_DIM, alpha),
        )
        shapes.rect(
            HAIRLINE_INSET,
            HudTestTheme.HEADER_HEIGHT,
            HudTestTheme.LIST_WIDTH - HAIRLINE_INSET * 2f,
            1f,
            scaleAlpha(HudTestTheme.HAIRLINE, alpha),
        )
    }

    private fun writeHeaderText(ctx: GuiGraphicsExtractor, frame: HudTestFrame, alpha: Float) {
        val top = textTopCentered(
            WatermarkFont.BRAND, HudTestTheme.TEXT_PX, frame.density, 0f, HudTestTheme.HEADER_HEIGHT,
        )
        val room = HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - ELLIPSIS_WIDTH - LABEL_GAP - titleX
        ctx.hudText(
            WatermarkFont.BRAND, fitText(WatermarkFont.BRAND, title, HudTestTheme.TEXT_PX, frame.density, room),
            titleX, top,
            HudTestTheme.TEXT_PX, frame.density, scaleAlpha(HudTestTheme.WHITE, alpha),
        )
        HudTestInput.addButton(
            frame,
            HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - ELLIPSIS_WIDTH - 4f,
            HudTestTheme.HEADER_HEIGHT * 0.5f - 9f,
            ELLIPSIS_WIDTH + 8f,
            18f,
        ) { HudTestSettingsPopover.toggleFor(this) }
    }

    // --- Rows -----------------------------------------------------------------------------------

    private fun rowTop(contentTop: Float, index: Int): Float =
        contentTop + index * HudTestTheme.ROW_PITCH

    private fun paintRowIcons(shapes: HudTestShapes, rows: List<HudTestRow>, contentTop: Float, alpha: Float) {
        if (!markersShown) {
            return
        }
        val left = HudTestTheme.ICON_CENTER_X - HudTestTheme.ICON * 0.5f
        val inset = (HudTestTheme.ROW_PITCH - HudTestTheme.ICON) * 0.5f
        for ((index, row) in rows.withIndex()) {
            val icon = row.icon as? HudTestRowIcon.Vector ?: continue
            icon.draw(
                shapes, left, rowTop(contentTop, index) + inset, HudTestTheme.ICON,
                scaleAlpha(icon.argb, alpha),
            )
        }
    }

    private fun writeRows(
        ctx: GuiGraphicsExtractor,
        frame: HudTestFrame,
        rows: List<HudTestRow>,
        contentTop: Float,
        alpha: Float,
    ) {
        val density = frame.density
        val left = labelX
        for ((index, row) in rows.withIndex()) {
            val top = rowTop(contentTop, index)
            val labelTop = textTopCentered(
                WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, top, HudTestTheme.ROW_PITCH,
            )
            val baseline = textBaseline(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, labelTop)

            // The trailing value owns the right edge, and the amplifier suffix sits directly
            // behind the label, so both are measured before the label is allowed its room.
            val trailingWidth = if (row.trailing.isEmpty()) {
                0f
            } else {
                WatermarkFont.BRAND.width(row.trailing, HudTestTheme.TEXT_PX, density) + LABEL_GAP
            }
            val suffixWidth = if (row.suffix.isEmpty() || !optionName.enabled) {
                0f
            } else {
                WatermarkFont.TEXT.width(row.suffix, HudTestTheme.UNIT_PX, density) + SUFFIX_GAP
            }
            val labelRoom = HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - trailingWidth - suffixWidth - left

            var labelWidth = 0f
            if (optionName.enabled) {
                val label = fitText(WatermarkFont.BRAND, row.label, HudTestTheme.TEXT_PX, density, labelRoom)
                labelWidth = WatermarkFont.BRAND.width(label, HudTestTheme.TEXT_PX, density)
                ctx.hudText(
                    WatermarkFont.BRAND, label, left, labelTop,
                    HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
                )
            }
            writeSuffix(ctx, row, left + labelWidth, baseline, density, alpha)
            if (row.trailing.isNotEmpty()) {
                val width = WatermarkFont.BRAND.width(row.trailing, HudTestTheme.TEXT_PX, density)
                ctx.hudText(
                    WatermarkFont.BRAND, row.trailing,
                    HudTestTheme.LIST_WIDTH - HudTestTheme.PAD_X - width, labelTop,
                    HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.VALUE_DIM, alpha),
                )
            }
        }
    }

    /** The dim amplifier that trails the label; [labelEnd] is where the drawn label actually ended. */
    @Suppress("LongParameterList")
    private fun writeSuffix(
        ctx: GuiGraphicsExtractor,
        row: HudTestRow,
        labelEnd: Float,
        baseline: Float,
        density: Float,
        alpha: Float,
    ) {
        if (row.suffix.isEmpty() || !optionName.enabled) {
            return
        }
        val top = textTopOnBaseline(WatermarkFont.TEXT, HudTestTheme.UNIT_PX, density, baseline)
        ctx.hudText(
            WatermarkFont.TEXT, row.suffix, labelEnd + SUFFIX_GAP, top,
            HudTestTheme.UNIT_PX, density, scaleAlpha(HudTestTheme.UNIT_DIM, alpha),
        )
    }

    /** Sprite and item row markers, which each need a textured element of their own. */
    private fun drawRowTextures(
        ctx: GuiGraphicsExtractor,
        frame: HudTestFrame,
        rows: List<HudTestRow>,
        contentTop: Float,
    ) {
        if (!markersShown) {
            return
        }
        val deviceSize = (HudTestTheme.ROW_ICON * frame.density * frame.grow).roundToInt().coerceAtLeast(4)
        val guiX = frame.guiX(HudTestTheme.ICON_CENTER_X - HudTestTheme.ROW_ICON * 0.5f)
        for ((index, row) in rows.withIndex()) {
            val guiY = frame.guiY(rowTop(contentTop, index) + TEXTURE_INSET)
            when (val icon = row.icon) {
                is HudTestRowIcon.Sprite -> HudTestItemIcons.drawTexture(ctx, icon.id, guiX, guiY, deviceSize)
                is HudTestRowIcon.Stack -> HudTestItemIcons.draw(ctx, icon.stack, guiX, guiY, deviceSize)
                else -> Unit
            }
        }
    }

    protected companion object {
        /** Below this the panel is invisible anyway, so it is skipped entirely. */
        const val MIN_ALPHA = 0.004f

        /** Width of the three-dot affordance. */
        const val ELLIPSIS_WIDTH = 10f

        /** The header hairline stops two pixels short of the padding on each side. */
        const val HAIRLINE_INSET = 13f

        /** Gap between a row's name and its dim suffix. */
        const val SUFFIX_GAP = 4f

        /** Clear space kept between a row's label and its right-aligned value. */
        const val LABEL_GAP = 8f

        /** What centres a textured row marker in its band; the band's centre is the text's too. */
        const val TEXTURE_INSET = (HudTestTheme.ROW_PITCH - HudTestTheme.ROW_ICON) * 0.5f
    }
}
