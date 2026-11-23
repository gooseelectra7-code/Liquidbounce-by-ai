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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.OverlayMessageEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.world.level.Level
import kotlin.math.floor

/**
 * DonutTweek
 *
 * Players online, the DonutSMP proxy region you are standing in, your ping and the server's tick
 * rate — stacked under the crosshair, or on one line.
 *
 * Every part is optional and every metric of the block is a setting: layout, alignment, font size,
 * line spacing, offsets, label and value colours, a shadow pass and an optional plate.
 *
 * A native port of the `DonutTweek` script: the same 9x9 region grid, the same TAB-footer ping and
 * the same colour thresholds, but compiled — the scripting route died on a mapping lookup
 * (`ResourceKey.location()`), and a per-frame readout has no business running in an interpreter.
 */
object ModuleDonutTweek : ClientModule("DonutTweek", ModuleCategories.MISC) {

    /** One line per readout, stacked, or everything on one line. */
    @Suppress("unused")
    enum class Layout(override val tag: String) : Tagged {
        COLUMN("Column"),
        ROW("Row"),
    }

    @Suppress("unused")
    enum class Align(override val tag: String) : Tagged {
        LEFT("Left"),
        CENTER("Center"),
        RIGHT("Right"),
    }

    private val layout by enumChoice("Layout", Layout.COLUMN)
    private val align by enumChoice("Align", Align.CENTER)

    private val showPlayers by boolean("Players", true)
    private val showProxy by boolean("Proxy", true)
    private val showPing by boolean("Ping", true)
    private val showTps by boolean("TPS", true)
    private val showCombat by boolean("Combat", true)

    private val showLabels by boolean("Labels", true)

    // Free-text labels: the readouts mean different things on different servers, so the wording
    // is the user's, not a guess baked into the code.
    private val playersLabel by text("PlayersLabel", "Players: ")
    private val pingLabel by text("PingLabel", "Ping: ")
    private val tpsLabel by text("TpsLabel", "TPS: ")
    private val combatLabel by text("CombatLabel", "Combat: ")
    private val colored by boolean("Colored", true)
    private val shadow by boolean("Shadow", true)

    private val fontSize by float("FontSize", 7f, 3f..24f)
    private val lineSpacing by float("LineSpacing", 1f, 0f..12f)
    private val offsetX by float("OffsetX", 0f, -400f..400f)
    private val offsetY by float("OffsetY", 10f, -400f..400f)

    private val labelColor by color("LabelColor", Color4b(255, 255, 255, 255))
    private val valueColor by color("ValueColor", Color4b(255, 255, 255, 255))
    private val playersColor by color("PlayersColor", Color4b(235, 205, 75, 255))
    private val combatColor by color("CombatColor", Color4b(235, 80, 80, 255))

    private val background by boolean("Background", false)
    private val backgroundColor by color("BackgroundColor", Color4b(13, 13, 13, 170))
    private val backgroundPad by float("BackgroundPadding", 3f, 0f..16f)
    private val backgroundRadius by float("BackgroundRadius", 3f, 0f..16f)

    /**
     * The proxy grid published at donut.auction/regions: one character per server, numbered 1..81
     * row by row over a 9x9 grid of 50k-block cells centred on the origin.
     */
    private const val PROXY_GRID =
        "OWWWNNNNN" +
            "OWWWNNNNN" +
            "OWWWNNNNN" +
            "OOOWNNNNN" +
            "AAAANNNNN" +
            "AEECCCCCN" +
            "AEECCCCCN" +
            "CECCCCCCC" +
            "CEEEEEECC"

    private val PROXY_NAMES = mapOf(
        'N' to "NA East",
        'W' to "NA West",
        'O' to "Oceania",
        'C' to "EU Central",
        'E' to "EU West",
        'A' to "Asia",
    )

    private const val CELL_SIZE = 50_000.0
    private const val GRID_ORIGIN = 225_000.0
    private const val NETHER_SCALE = 8.0

    /** Vanilla emits the time sync every 20 server ticks — the basis of the tps readout. */
    private const val TIME_SYNC_TICKS = 20.0

    private val TAB_PING = Regex("""(?i)\b(\d{1,5})\s*ms\b""")

    /**
     * The combat countdown DonutSMP writes to the action bar, in either client language:
     * `Бой: 18с` and `Combat: 18s` both carry the seconds in group 1.
     */
    private val COMBAT_TIMER = Regex("""(?iu)(?:бой|combat)\D{0,4}(\d{1,3})""")

    /** Wall-clock instant the combat tag is predicted to drop; 0 when we are not tagged. */
    private var combatEndsAt = 0L

    private var lastSync = 0L
    private var tps = 20f

    /**
     * Tick rate measured from the gap between two time syncs, smoothed so one late packet cannot
     * make the number jump. Only a timestamp is touched, which is safe on the netty thread.
     */
    /**
     * The action bar is the only place the combat tag is published, and the server rewrites it
     * once a second. Anchoring an end instant instead of storing the number lets the readout keep
     * ticking between two of those writes.
     */
    @Suppress("unused")
    private val overlayHandler = handler<OverlayMessageEvent> { event ->
        val seconds = COMBAT_TIMER.find(event.text.string)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return@handler
        combatEndsAt = System.currentTimeMillis() + seconds * 1000L
    }

    /** Whole seconds left on the tag, rounded up the way the server's own text does. */
    private fun combatSecondsLeft(): Int {
        val left = combatEndsAt - System.currentTimeMillis()
        return if (left <= 0L) 0 else ((left + 999L) / 1000L).toInt()
    }

    @Suppress("unused")
    private val timeHandler = handler<PacketEvent> { event ->
        if (event.packet !is ClientboundSetTimePacket) return@handler
        val now = System.currentTimeMillis()
        val previous = lastSync
        lastSync = now
        if (previous == 0L) return@handler
        val seconds = (now - previous) / 1000.0
        if (seconds <= 0.0) return@handler
        val measured = (TIME_SYNC_TICKS / seconds).coerceIn(0.0, 20.0).toFloat()
        tps += (measured - tps) * 0.5f
    }

    private fun proxyCell(value: Double): Int =
        floor((value + GRID_ORIGIN) / CELL_SIZE).toInt().coerceIn(0, 8)

    /** 1..81, read off the grid the same way the region map does. */
    private fun currentServer(): Int {
        val player = mc.player ?: return 1
        // the nether is compressed 8:1, so its coordinates expand before matching the grid
        val scale = if (mc.level?.dimension() == Level.NETHER) NETHER_SCALE else 1.0
        val column = proxyCell(player.x * scale)
        val row = proxyCell(player.z * scale)
        return row * 9 + column + 1
    }

    private fun proxyForServer(server: Int): String {
        val index = (server - 1).coerceIn(0, PROXY_GRID.lastIndex)
        return PROXY_NAMES[PROXY_GRID[index]] ?: "Unknown"
    }

    /**
     * DonutSMP puts the ping that matters in the TAB footer; the connection's own latency is the
     * fallback for the moments before the footer has arrived.
     */
    private fun currentPing(): Int {
        val footer = mc.gui.tabList.footer?.string
        if (footer != null) {
            val parsed = TAB_PING.find(footer)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsed != null) return parsed.coerceIn(0, 60_000)
        }
        val player = mc.player ?: return 0
        return (mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0).coerceIn(0, 60_000)
    }

    private fun pingColor(ping: Int): Color4b = when {
        !colored -> valueColor
        ping <= 60 -> Color4b(70, 225, 90, 255)
        ping <= 120 -> Color4b(235, 205, 75, 255)
        ping <= 200 -> Color4b(240, 150, 60, 255)
        else -> Color4b(235, 80, 80, 255)
    }

    private fun tpsColor(value: Float): Color4b = when {
        !colored -> valueColor
        value >= 19f -> Color4b(70, 225, 90, 255)
        value >= 17f -> Color4b(235, 205, 75, 255)
        value >= 14f -> Color4b(240, 150, 60, 255)
        else -> Color4b(235, 80, 80, 255)
    }

    /** One run: a label and its value, so only the value carries a colour. */
    private class Line(val label: String, val value: String, val color: Color4b)

    private val lines = ArrayList<Line>(4)

    /**
     * Players currently online, straight off the player list.
     *
     * An earlier cut labelled this "PvP" and tried to scrape a number out of the TAB text — that
     * word came from a screenshot of a different client's HUD, and the line then claimed a PvP
     * count while showing the plain head-count. The number is what it is; the wording is a
     * setting.
     */
    private fun currentPlayers(): Int = mc.connection?.onlinePlayers?.size ?: 0

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (HideAppearance.isHidingNow || mc.options.hideGui || mc.player == null) return@handler

        lines.clear()
        // The tag is the one readout you look for mid-fight, so it leads the block.
        if (showCombat) {
            val left = combatSecondsLeft()
            if (left > 0) {
                lines += Line(combatLabel, "${left}s", if (colored) combatColor else valueColor)
            }
        }
        if (showPlayers) {
            lines += Line(playersLabel, currentPlayers().toString(), if (colored) playersColor else valueColor)
        }
        if (showProxy) {
            val server = currentServer()
            lines += Line("", "${proxyForServer(server)} ($server)", valueColor)
        }
        if (showPing) {
            val ping = currentPing()
            lines += Line(pingLabel, "$ping ms", pingColor(ping))
        }
        if (showTps) {
            lines += Line(tpsLabel, "%.1f".format(tps), tpsColor(tps))
        }
        if (lines.isEmpty()) return@handler

        draw(event.context)
    }

    private fun label(line: Line): String = if (showLabels) line.label else ""

    private fun widthOf(line: Line, density: Float): Float =
        WatermarkFont.TEXT.width(label(line), fontSize, density) +
            WatermarkFont.TEXT.width(line.value, fontSize, density)

    private fun draw(ctx: GuiGraphicsExtractor) {
        val density = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val lineHeight = WatermarkFont.TEXT.lineHeight(fontSize, density)
        val centerX = mc.window.guiScaledWidth / 2f + offsetX
        val topY = mc.window.guiScaledHeight / 2f + offsetY

        if (layout == Layout.ROW) {
            var total = 0f
            for (line in lines) total += widthOf(line, density)
            val blockX = originX(centerX, total)
            drawBackground(ctx, blockX, topY, total, lineHeight)
            var x = blockX
            for (line in lines) {
                x = drawRun(ctx, label(line), x, topY, density, labelColor)
                x = drawRun(ctx, line.value, x, topY, density, line.color)
            }
            return
        }

        val step = lineHeight + lineSpacing
        var widest = 0f
        for (line in lines) widest = maxOf(widest, widthOf(line, density))
        drawBackground(ctx, originX(centerX, widest), topY, widest, step * lines.size - lineSpacing)

        var y = topY
        for (line in lines) {
            val lineWidth = widthOf(line, density)
            var x = originX(centerX, lineWidth)
            x = drawRun(ctx, label(line), x, y, density, labelColor)
            drawRun(ctx, line.value, x, y, density, line.color)
            y += step
        }
    }

    /** Left edge of a run of [width] for the chosen alignment around [centerX]. */
    private fun originX(centerX: Float, width: Float): Float = when (align) {
        Align.LEFT -> centerX
        Align.RIGHT -> centerX - width
        Align.CENTER -> centerX - width / 2f
    }

    @Suppress("LongParameterList")
    private fun drawRun(
        ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, density: Float, color: Color4b,
    ): Float {
        if (text.isEmpty()) return x
        if (shadow) {
            // the font has no shadow pass, so it is a second draw one unit down-right
            val shadowArgb = Color4b(0, 0, 0, (color.a * 0.65f).toInt().coerceIn(0, 255)).argb
            WatermarkFont.TEXT.submit(ctx, text, x + 1f, y + 1f, fontSize, density) { shadowArgb }
        }
        val argb = color.argb
        WatermarkFont.TEXT.submit(ctx, text, x, y, fontSize, density) { argb }
        return x + WatermarkFont.TEXT.width(text, fontSize, density)
    }

    private fun drawBackground(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        if (!background || w <= 0f || h <= 0f) return
        val pad = backgroundPad
        ctx.drawRoundedRect(
            x - pad, y - pad, x + w + pad, y + h + pad, backgroundRadius, backgroundColor, null,
        )
    }
}
