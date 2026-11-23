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

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.dropPort
import net.ccbluex.liquidbounce.utils.text.rootDomain
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The watermark pill — `<3:489>`, a 361x26 fill box at (20, 20) of the design frame.
 *
 * Its content is a horizontal flow of `[icon 16][gap 5][text]` segments. The brand is joined to the
 * rest by the pill's own auto-layout gap of **5** (`<3:489>`) while the info segments are separated
 * from each other by **10** (`<3:356>`) — two different numbers, both reproduced.
 *
 * The design's 361 px width is the width **its** strings happened to measure; a live HUD has to
 * re-measure, so the pill is laid out from the real text widths plus the file's own paddings and
 * gaps. Fed the design's own text widths that arithmetic returns 361 exactly, and the same holds
 * for every segment group.
 *
 * A value with a unit (`123 fps`, `231 ms`) is one segment whose two runs share a baseline. The
 * design bottom-aligns their line boxes instead (`align = MAX`, `<3:362>`); with one family at
 * 12 px and 10 px the two placements differ by a fraction of a pixel, and a shared baseline is the
 * one that stays right if either size is ever changed.
 */
internal class Hud3Watermark(parent: EventListener) :
    Hud3Panel(parent, "Watermark", Hud3Anchor.TOP_LEFT, 20f, 20f) {

    private val showBrand by boolean("Brand", true)
    private val showUser by boolean("User", true)
    private val showFps by boolean("Fps", true)
    private val showServer by boolean("Server", true)
    private val showPing by boolean("Ping", true)

    /** One `[icon][gap][value][unit]` run of the pill. */
    private class Segment(val icon: String, val value: String, val unit: String?, val width: Float)

    /**
     * The info segments on screen, keyed by their icon. A segment that appears (the server on
     * joining one, a toggle switched on) opens up in place and the pill widens around it; one that
     * goes closes the same way.
     */
    private val segments = Hud3Presence<String, Segment>({ it.icon })

    /** The brand's own presence, so switching it off does not snap the pill either. */
    private val brandShown = Hud3Smooth(Hud3Theme.LAYOUT_HALF_LIFE_MS)

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val density = frameDensity
        segments.sync(if (enabled) collect(density) else emptyList(), dt)
        val brandPresence = brandShown.update(if (enabled && showBrand) 1f else 0f, dt)
        val alpha = fade(segments.hasPresent || (enabled && showBrand), dt)
        if (alpha <= 0.004f) {
            return
        }

        val brand = LiquidBounce.CLIENT_NAME
        val brandWidth = Hud3Theme.ICON_16 + Hud3Theme.GAP + textWidth(brand, Hud3Theme.PX_12, density)

        // Lay the row out first: every segment claims its width and its leading gap in proportion
        // to its presence, which is what makes the pill breathe instead of jump.
        var cursor = Hud3Theme.PAD + brandWidth * brandPresence
        var before = brandPresence
        var anySegment = false
        for (entry in segments.all) {
            val presence = entry.eased
            val gap = if (anySegment) SEGMENT_GAP else Hud3Theme.GAP
            cursor += gap * presence * before.coerceAtMost(1f)
            entry.offset.update(cursor, dt)
            cursor += entry.size.update(entry.value.width, dt) * presence
            before = maxOf(before, presence)
            anySegment = true
        }
        val width = cursor + Hud3Theme.PAD

        val ctx = event.context
        val frame = frame(width, Hud3Theme.ROW_26, alpha)
        ctx.pushFrame(frame)
        ctx.hud3Pill(0f, 0f, width, Hud3Theme.ROW_26, alpha, density, ModuleHud3.glass(ctx))
        if (brandPresence > 0.004f) {
            drawRun(ctx, Hud3Icons.LOGO, brand, null, Hud3Theme.PAD, 0f, density, alpha * brandPresence)
        }
        for (entry in segments.all) {
            val presence = entry.eased
            val segment = entry.value
            drawRun(
                ctx, segment.icon, segment.value, segment.unit, entry.offset.value,
                -DROP * (1f - presence), density, alpha * presence,
            )
        }
        ctx.popFrame()
    }

    /** The live segments, in the design's order, each measured for this frame. */
    private fun collect(density: Float): List<Segment> {
        val result = ArrayList<Segment>(SEGMENT_CAPACITY)
        if (showUser) {
            result += segment(Hud3Icons.USER, mc.player?.gameProfile?.name ?: "", null, density)
        }
        if (showFps) {
            result += segment(Hud3Icons.FRAME, mc.fps.toString(), "fps", density)
        }
        if (showServer) {
            result += segment(Hud3Icons.PAD, serverName(), null, density)
        }
        if (showPing) {
            result += segment(Hud3Icons.CONNECT, ping().toString(), "ms", density)
        }
        result.removeAll { it.value.isEmpty() }
        return result
    }

    private fun segment(icon: String, value: String, unit: String?, density: Float): Segment {
        var width = Hud3Theme.ICON_16 + Hud3Theme.GAP + textWidth(value, Hud3Theme.PX_12, density)
        if (unit != null) {
            width += UNIT_GAP + textWidth(unit, Hud3Theme.PX_10, density)
        }
        return Segment(icon, value, unit, width)
    }

    /** One `[icon][gap][value][unit]` run with its left edge at [x], nudged down by [dy]. */
    @Suppress("LongParameterList")
    private fun drawRun(
        ctx: GuiGraphicsExtractor,
        icon: String,
        value: String,
        unit: String?,
        x: Float,
        dy: Float,
        density: Float,
        alpha: Float,
    ) {
        if (alpha <= 0.004f) {
            return
        }
        val iconY = (Hud3Theme.ROW_26 - Hud3Theme.ICON_16) * 0.5f + dy
        val valueTop = textTopCentered(Hud3Theme.PX_12, density, dy, Hud3Theme.ROW_26)
        Hud3Icons.draw(ctx, icon, x, iconY, Hud3Theme.ICON_16, scaled(Hud3Theme.ACCENT, alpha))
        val valueX = x + Hud3Theme.ICON_16 + Hud3Theme.GAP
        ctx.hud3Text(value, valueX, valueTop, Hud3Theme.PX_12, density, scaled(Hud3Theme.TEXT, alpha))
        if (unit != null) {
            val unitTop = textTopOnBaseline(
                Hud3Theme.PX_10, density, textBaseline(Hud3Theme.PX_12, density, valueTop),
            )
            val unitX = valueX + textWidth(value, Hud3Theme.PX_12, density) + UNIT_GAP
            ctx.hud3Text(unit, unitX, unitTop, Hud3Theme.PX_10, density, scaled(Hud3Theme.TEXT_DIM, alpha))
        }
    }

    /**
     * The address as the design shows it: `funtime.su`, i.e. the root domain without the port —
     * the same reduction `AutoConfig` applies. Singleplayer has no server, so the segment drops
     * out rather than showing a placeholder.
     */
    private fun serverName(): String = mc.currentServer?.ip?.dropPort()?.rootDomain() ?: ""

    private fun ping(): Int {
        val player = mc.player ?: return 0
        return mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
    }

    private companion object {
        const val SEGMENT_CAPACITY = 4

        /** How far a segment drops in from while it appears, in native pixels. */
        const val DROP = 4f

        /** Gap between the info segments — `<3:356>` itemSpacing. */
        const val SEGMENT_GAP = 10f

        /** Gap between a value and its unit — `<3:362>` itemSpacing. */
        const val UNIT_GAP = 3f
    }
}
