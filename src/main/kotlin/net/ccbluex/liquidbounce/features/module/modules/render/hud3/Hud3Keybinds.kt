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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup

/**
 * The keybinds stack — `<3:486>`, a 129x103 box at (20, 66) of the design frame.
 *
 * Same two-pill row system as [Hud3Potions]: a header pill (`<3:376>`) and one row per bound
 * module, the name pill (`<3:382>`) packed left of the key pill (`<3:384>`, keyboard icon plus the
 * key caption) with a gap of 5.
 *
 * The colour split is the design's: the module name is the full `#F7F3F3` while the key pill's
 * icon **and** caption are the same colour at 45 % (`<3:386>`, `<I3:385;1:407>`).
 */
internal class Hud3Keybinds(parent: EventListener) :
    Hud3Panel(parent, "Keybinds", Hud3Anchor.TOP_LEFT, 20f, 66f) {

    private val title by text("Title", "Keybinds")

    /**
     * Default ON, changed 2026-09-21 at the user's request. A list of every bound module is a
     * reference card; a list of the ones that are actually on is a HUD.
     */
    private val onlyEnabled by boolean("OnlyEnabled", true)

    private val showKey by boolean("Key", true)
    private val maxRows by int("MaxRows", 8, 1..24)

    private class Row(
        val label: String,
        val key: String,
        val leftWidth: Float,
        val rightWidth: Float,
    )

    /**
     * The rows on screen, keyed by module name. A module switched on slides into its sorted place,
     * one switched off slides back out, and the rows around both close up smoothly.
     */
    private val rows = Hud3Presence<String, Row>({ it.label })

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val density = frameDensity
        rows.sync(if (enabled) measure(density) else emptyList(), dt)
        val alpha = fade(rows.hasPresent, dt)
        if (alpha <= 0.004f) {
            return
        }

        val ctx = event.context
        val headerWidth = Hud3Theme.PAD * 2f + Hud3Theme.ICON_12 + Hud3Theme.GAP +
            textWidth(title, Hud3Theme.PX_10, density)
        val extent = rows.layout(Hud3Theme.STACK_GAP + Hud3Theme.ROW_22, dt)
        var width = headerWidth
        for (entry in rows.all) {
            val right = entry.size.update(entry.value.rightWidth, dt)
            if (!entry.leaving) {
                width = maxOf(width, entry.value.leftWidth + if (right > 0f) Hud3Theme.GAP + right else 0f)
            }
        }
        val frame = frame(width, Hud3Theme.ROW_22 + extent, alpha)
        val glass = ModuleHud3.glass(ctx)

        ctx.pushFrame(frame)
        drawHeader(ctx, headerWidth, density, alpha, glass)
        for (entry in rows.all) {
            val presence = entry.eased
            drawRow(
                ctx, entry.value, entry.size.value,
                slideDirection * Hud3Theme.SLIDE * (1f - presence),
                Hud3Theme.ROW_22 + Hud3Theme.STACK_GAP + entry.offset.value,
                density, alpha * presence, glass,
            )
        }
        ctx.popFrame()
    }

    @Suppress("LongParameterList")
    private fun drawHeader(
        ctx: GuiGraphicsExtractor,
        headerWidth: Float,
        density: Float,
        alpha: Float,
        glass: TextureSetup?,
    ) {
        ctx.hud3Pill(0f, 0f, headerWidth, Hud3Theme.ROW_22, alpha, density, glass)
        Hud3Icons.draw(
            ctx, Hud3Icons.MODULE, Hud3Theme.PAD,
            (Hud3Theme.ROW_22 - Hud3Theme.ICON_12) * 0.5f, Hud3Theme.ICON_12,
            scaled(Hud3Theme.ACCENT, alpha),
        )
        ctx.hud3Text(
            title, Hud3Theme.PAD + Hud3Theme.ICON_12 + Hud3Theme.GAP,
            textTopCentered(Hud3Theme.PX_10, density, 0f, Hud3Theme.ROW_22),
            Hud3Theme.PX_10, density, scaled(Hud3Theme.TEXT, alpha),
        )
    }

    @Suppress("LongParameterList")
    private fun drawRow(
        ctx: GuiGraphicsExtractor,
        row: Row,
        rightWidth: Float,
        x: Float,
        rowY: Float,
        density: Float,
        alpha: Float,
        glass: TextureSetup?,
    ) {
        if (alpha <= 0.004f) {
            return
        }
        val labelTop = textTopCentered(Hud3Theme.PX_10, density, rowY, Hud3Theme.ROW_22)
        val dim = scaled(Hud3Theme.TEXT_DIM, alpha)

        ctx.hud3Pill(x, rowY, row.leftWidth, Hud3Theme.ROW_22, alpha, density, glass)
        ctx.hud3Text(
            row.label, x + Hud3Theme.PAD, labelTop, Hud3Theme.PX_10, density,
            scaled(Hud3Theme.TEXT, alpha),
        )

        if (rightWidth <= 0.5f || row.key.isEmpty()) {
            return
        }
        val rightX = x + row.leftWidth + Hud3Theme.GAP
        ctx.hud3Pill(rightX, rowY, rightWidth, Hud3Theme.ROW_22, alpha, density, glass)
        Hud3Icons.draw(
            ctx, Hud3Icons.COMMAND, rightX + Hud3Theme.PAD,
            rowY + (Hud3Theme.ROW_22 - Hud3Theme.ICON_12) * 0.5f, Hud3Theme.ICON_12, dim,
        )
        ctx.hud3Text(
            row.key, rightX + Hud3Theme.PAD + Hud3Theme.ICON_12 + Hud3Theme.GAP, labelTop,
            Hud3Theme.PX_10, density, dim,
        )
    }

    /**
     * Every module that carries a bind, with the key name the client itself reports.
     *
     * Ordered **longest name pill first**, which is the order the design's own stack is in
     * (`<3:382>` 90, `<3:388>` 48, `<3:394>` 41) and what the user asked for on 2026-09-21.
     *
     * Two things that reading turns on, and both are deliberate:
     *
     * Sorting happens **after** `take(maxRows)`, not before. The request was about display order,
     * so which modules are on screen stays what it was — the alphabetically first [maxRows] — and
     * only their order changes. Sorting first would silently drop short-named modules off the
     * bottom of a full list instead.
     *
     * The key is the NAME pill's width, not the whole row's: the row also carries the key pill, and
     * ordering by the total would let a pill change places when a key caption changes width. The
     * name is stable, so the staircase is stable. Ties fall back to the name so the order is total.
     */
    private fun measure(density: Float): List<Row> = ModuleManager
        .asSequence()
        .filter { !it.bind.isUnbound && !it.hidden && !it.notActivatable }
        .filter { !onlyEnabled || it.enabled }
        .sortedBy { it.name }
        .take(maxRows)
        .map { module -> row(module.name, if (showKey) module.bind.keyName else "", density) }
        .sortedWith(compareByDescending<Row> { it.leftWidth }.thenBy { it.label })
        .toList()

    private fun row(label: String, key: String, density: Float) = Row(
        label = label,
        key = key,
        leftWidth = Hud3Theme.PAD * 2f + textWidth(label, Hud3Theme.PX_10, density),
        rightWidth = if (key.isEmpty()) {
            0f
        } else {
            Hud3Theme.PAD * 2f + Hud3Theme.ICON_12 + Hud3Theme.GAP +
                textWidth(key, Hud3Theme.PX_10, density)
        },
    )
}
