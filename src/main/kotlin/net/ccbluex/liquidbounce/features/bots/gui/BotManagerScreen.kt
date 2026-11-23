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
package net.ccbluex.liquidbounce.features.bots.gui

import net.ccbluex.liquidbounce.features.bots.BotInstance
import net.ccbluex.liquidbounce.features.bots.BotManager
import net.ccbluex.liquidbounce.features.bots.BotProfile
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.util.ARGB

/**
 * Minimal native Minecraft screen showing the list of bot profiles and lifecycle controls.
 *
 * Phase 1: pure-text rendering, click detection done by tracking row bounds. From Phase 4 the
 * row gains a context menu (send command, edit). Edge insets honour [[hud-edge-breathing-room]].
 */
class BotManagerScreen : Screen("LiquidBounce — Bot Manager".asPlainText()) {

    private data class HitBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val action: () -> Unit) {
        fun contains(mx: Double, my: Double) = mx >= x1 && mx <= x2 && my >= y1 && my <= y2
    }

    private val hitboxes = mutableListOf<HitBox>()

    private companion object {
        const val INSET_X = 24
        const val INSET_Y = 24
        const val ROW_HEIGHT = 18
        const val ACTION_GAP = 10
        const val FOOTER_BAR_HEIGHT = 26

        val COLOR_BACKGROUND = ARGB.color(220, 12, 14, 18)
        val COLOR_PANEL = ARGB.color(255, 24, 26, 32)
        val COLOR_BUTTON = ARGB.color(255, 48, 96, 160)
        val COLOR_BUTTON_HOVER = ARGB.color(255, 68, 116, 180)
        val COLOR_DANGER = ARGB.color(255, 160, 56, 56)
    }

    override fun extractRenderState(
        context: GuiGraphicsExtractor,
        mouseX: Int, mouseY: Int, partialTick: Float
    ) {
        hitboxes.clear()
        extractMenuBackground(context)

        context.fill(INSET_X, INSET_Y, width - INSET_X, height - INSET_Y, COLOR_BACKGROUND)
        drawHeader(context)
        drawBotList(context, mouseX, mouseY)
        drawFooter(context, mouseX, mouseY)
    }

    private fun drawHeader(context: GuiGraphicsExtractor) {
        context.text(font, title, INSET_X + 12, INSET_Y + 10, -1, true)
        val countLine = "${BotManager.profiles().size} bot(s) configured"
            .asPlainText(ChatFormatting.GRAY)
        context.text(
            font, countLine,
            width - INSET_X - 12 - font.width(countLine.string),
            INSET_Y + 12, -1, false
        )
    }

    private fun drawBotList(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val listTop = INSET_Y + 36
        val listBottom = height - INSET_Y - FOOTER_BAR_HEIGHT - 8
        val rowX1 = INSET_X + 12
        val rowX2 = width - INSET_X - 12

        if (BotManager.profiles().isEmpty()) {
            val empty = "No bots yet — use Add Bot below.".asPlainText(ChatFormatting.DARK_GRAY)
            context.text(
                font, empty,
                width / 2 - font.width(empty.string) / 2,
                listTop + 20, -1, false
            )
            return
        }

        var y = listTop
        for (profile in BotManager.profiles()) {
            if (y + ROW_HEIGHT > listBottom) break
            drawRow(context, profile, rowX1, y, rowX2, mouseX, mouseY)
            y += ROW_HEIGHT + 2
        }
    }

    private fun drawFooter(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val footerY = height - INSET_Y - FOOTER_BAR_HEIGHT
        drawButton(
            context, "Add Demo Bot", INSET_X + 12, footerY, 110, mouseX, mouseY,
            color = COLOR_BUTTON
        ) {
            val nextIndex = BotManager.profiles().size + 1
            BotManager.add(BotManager.newProfile(
                username = "DemoBot$nextIndex",
                server = "localhost",
                port = 25565,
            ))
        }
        drawButton(
            context, "Start All", INSET_X + 12 + 110 + ACTION_GAP, footerY, 80,
            mouseX, mouseY, color = COLOR_BUTTON
        ) {
            BotManager.profiles().forEach { BotManager.start(it.id) }
        }
        drawButton(
            context, "Stop All", INSET_X + 12 + 110 + ACTION_GAP + 80 + ACTION_GAP, footerY, 80,
            mouseX, mouseY, color = COLOR_DANGER
        ) {
            BotManager.stopAll()
        }
        drawButton(
            context, "Close", width - INSET_X - 12 - 70, footerY, 70,
            mouseX, mouseY, color = COLOR_PANEL
        ) {
            mc.setScreen(null)
        }
    }

    private fun drawRow(
        context: GuiGraphicsExtractor, profile: BotProfile,
        x1: Int, y: Int, x2: Int,
        @Suppress("UNUSED_PARAMETER") mouseX: Int, @Suppress("UNUSED_PARAMETER") mouseY: Int,
    ) {
        val running = BotManager.instance(profile.id)?.state?.value
        val statusLabel = when (running) {
            null, BotInstance.State.DISCONNECTED -> "Disconnected" to ChatFormatting.DARK_GRAY
            BotInstance.State.CONNECTING -> "Connecting" to ChatFormatting.YELLOW
            BotInstance.State.IN_GAME -> "In game" to ChatFormatting.GREEN
            BotInstance.State.CAPTCHA_PENDING -> "Captcha" to ChatFormatting.AQUA
            BotInstance.State.DEAD -> "Dead" to ChatFormatting.RED
        }

        context.fill(x1, y, x2, y + ROW_HEIGHT, COLOR_PANEL)

        context.text(
            font,
            profile.username.asPlainText(ChatFormatting.WHITE),
            x1 + 8, y + 5, -1, false
        )

        val addr = "→ ${profile.address}".asPlainText(ChatFormatting.GRAY)
        context.text(font, addr, x1 + 110, y + 5, -1, false)

        val statusText = statusLabel.first.asPlainText(statusLabel.second)
        context.text(font, statusText, x1 + 260, y + 5, -1, false)

        // Start / Stop / Remove buttons (compact)
        val rightEdge = x2 - 4
        drawButton(
            context, "Remove", rightEdge - 56, y + 1, 56, 0, 0,
            color = COLOR_DANGER, h = ROW_HEIGHT - 2
        ) { BotManager.remove(profile.id) }
        drawButton(
            context, "Stop", rightEdge - 56 - ACTION_GAP - 42, y + 1, 42, 0, 0,
            color = COLOR_PANEL, h = ROW_HEIGHT - 2
        ) { BotManager.stop(profile.id) }
        drawButton(
            context, "Start", rightEdge - 56 - ACTION_GAP - 42 - ACTION_GAP - 42, y + 1, 42, 0, 0,
            color = COLOR_BUTTON, h = ROW_HEIGHT - 2
        ) { BotManager.start(profile.id) }
    }

    @Suppress("LongParameterList")
    private fun drawButton(
        context: GuiGraphicsExtractor,
        label: String, x: Int, y: Int, w: Int,
        mouseX: Int, mouseY: Int,
        color: Int, h: Int = 18,
        action: () -> Unit,
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + h)
        val fill = if (hovered) COLOR_BUTTON_HOVER else color
        context.fill(x, y, x + w, y + h, fill)
        val text = label.asPlainText(ChatFormatting.WHITE)
        context.text(
            font, text,
            x + w / 2 - font.width(label) / 2,
            y + (h - font.lineHeight) / 2 + 1,
            -1, false
        )
        hitboxes.add(HitBox(x, y, x + w, y + h, action))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != 0) return super.mouseClicked(click, doubled)
        val mx = click.x
        val my = click.y
        // Iterate from last to first so overlay buttons win over rows.
        for (i in hitboxes.indices.reversed()) {
            val box = hitboxes[i]
            if (box.contains(mx, my)) {
                box.action()
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun isPauseScreen(): Boolean = false
}
