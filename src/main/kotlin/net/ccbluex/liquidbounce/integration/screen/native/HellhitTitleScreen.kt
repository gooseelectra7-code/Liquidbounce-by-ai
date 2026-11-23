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
package net.ccbluex.liquidbounce.integration.screen.native

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.integration.screen.native.model.SystemAudioSpectrum
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.alphaF
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitScreen
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import kotlin.math.sin

/**
 * The native Hellhit title screen: one of three shipped wallpapers with a per-wallpaper
 * button layout, and — on wallpaper 3 (the waveform line) — a live visualization of
 * whatever the PC is playing, mirrored around the artwork's own baseline.
 *
 * Wallpaper layouts (from the forensic composition read):
 *  1 (moon)  — button stack dead-center, wordmark top-center; moon top-right untouched
 *  2 (baka)  — stack above center (figure occupies the bottom); wordmark top
 *  3 (AM)    — wordmark top-center, stack below the line (y > 0.6); the line pulses
 */
internal class HellhitTitleScreen : HellhitScreen("Title") {

    private val background: Int get() = InterfaceSettings.menuBackground

    private var spectrumHeld = false

    override fun init() {
        super.init()
        syncSpectrum()
    }

    override fun removed() {
        if (spectrumHeld) {
            SystemAudioSpectrum.release()
            spectrumHeld = false
        }
        super.removed()
    }

    private fun syncSpectrum() {
        val wanted = background == 3
        if (wanted && !spectrumHeld) {
            SystemAudioSpectrum.acquire()
            spectrumHeld = true
        } else if (!wanted && spectrumHeld) {
            SystemAudioSpectrum.release()
            spectrumHeld = false
        }
    }

    /** ESC on the title screen does nothing (vanilla behavior). */
    override fun onEscape() = Unit

    // ------------------------------------------------------------------ *
    // Backdrop: wallpaper (cover-crop) + the waveform visualizer on bg3
    // ------------------------------------------------------------------ *

    override fun drawBackdrop(ctx: GuiGraphicsExtractor) {
        val w = width.toFloat()
        val h = height.toFloat()

        MenuBackgrounds.drawLive(ctx, "bg$background", width, height)

        if (background == 3) {
            drawWaveform(ctx, w, h)
        }
    }

    /**
     * Mirrored bars around the artwork's baseline (y = 0.51). The static center squiggle
     * of the wallpaper (x 0.44..0.57) stays untouched as the idle anchor. Without a
     * loopback capture device a soft sine ripple keeps the line alive.
     */
    private fun drawWaveform(ctx: GuiGraphicsExtractor, w: Float, h: Float) {
        val baseline = h * 0.51f
        val exclude0 = w * 0.435f
        val exclude1 = w * 0.575f
        val bands = SystemAudioSpectrum.bands()
        val capturing = SystemAudioSpectrum.isCapturing
        val maxBar = h * 0.11f
        val n = SystemAudioSpectrum.BANDS
        val slot = w / n
        val barW = slot * 0.55f
        val time = (System.nanoTime() / 1_000_000_000.0 % 3600.0).toFloat()

        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            if (cx > exclude0 && cx < exclude1) continue

            val level = if (capturing) {
                bands[i]
            } else {
                // idle: a slow travelling ripple, barely above the line
                0.05f + 0.035f * (1f + sin(time * 1.6f + i * 0.55f))
            }
            val barH = (level * maxBar).coerceAtLeast(0.4f)
            val alpha = (0.35f + 0.55f * level).coerceIn(0f, 1f)
            ctx.drawQuad(
                cx - barW / 2f, baseline - barH, cx + barW / 2f, baseline + barH,
                palette.white.alphaF(alpha * motion), null,
            )
        }
    }

    // ------------------------------------------------------------------ *
    // Content
    // ------------------------------------------------------------------ *

    @Suppress("LongMethod")
    override fun drawContent(ctx: GuiGraphicsExtractor) {
        syncSpectrum()
        val a = motion

        // per-wallpaper layout
        val wordmarkCy: Float
        val stackTop: Float
        when (background) {
            2 -> {
                wordmarkCy = vh * 0.12f
                stackTop = vh * 0.42f - stackHeight() / 2f
            }
            3 -> {
                wordmarkCy = vh * 0.16f
                stackTop = vh * 0.63f
            }
            else -> {
                wordmarkCy = vh * 0.18f
                stackTop = vh * 0.5f - stackHeight() / 2f
            }
        }

        // wordmark + version
        val name = LiquidBounce.CLIENT_NAME.uppercase()
        val namePx = 21f
        val nameW = textWidth(WatermarkFont.BRAND, name, namePx)
        drawTextCentered(ctx, WatermarkFont.BRAND, name, (vw - nameW) / 2f, wordmarkCy, namePx, palette.white.alphaF(a))
        val version = "v${LiquidBounce.clientVersion}"
        val verW = textWidth(WatermarkFont.TEXT, version, 6f)
        drawTextCentered(
            ctx, WatermarkFont.TEXT, version,
            (vw - verW) / 2f, wordmarkCy + lineHeight(WatermarkFont.BRAND, namePx) / 2f + 5f, 6f,
            palette.dim.alphaF(a),
        )

        // button stack with a staggered reveal
        val x = (vw - BUTTON_W) / 2f
        var y = stackTop
        val now = System.currentTimeMillis()
        for ((index, entry) in buttons.withIndex()) {
            val revealT = easeInOutCubic(((now - openedAtForStagger) - index * 40L) / 240f)
            if (revealT <= 0.002f) {
                y += BUTTON_H + BUTTON_GAP
                continue
            }
            pillButton(ctx, "title:${entry.first}", entry.first, x, y, alphaF = a * revealT) {
                entry.second()
            }
            y += BUTTON_H + BUTTON_GAP
        }

        // session line, bottom-left; clicking it opens the alt manager
        val session = mc.user?.name ?: "offline"
        val sessH = lineHeight(WatermarkFont.TEXT, 6.2f)
        drawText(ctx, WatermarkFont.TEXT, session, 8f, vh - sessH - 6f, 6.2f, palette.desc.alphaF(a))
        val sessW = textWidth(WatermarkFont.TEXT, session, 6.2f)
        addHit(6f, vh - sessH - 8f, 10f + sessW, vh - 4f) { button ->
            if (button == 0) {
                NativeScreens.openAltManager()
                true
            } else {
                false
            }
        }

        // wallpaper switcher, bottom-right
        val label = "bg $background/3"
        val bw = textWidth(WatermarkFont.TEXT, label, 6f) + 12f
        val bx = vw - bw - 8f
        val by = vh - 18f
        val hovered = mouseXv in bx..(bx + bw) && mouseYv in by..(by + 12f)
        val ht = hover("title:bg", hovered)
        ctx.drawRoundedRect(
            bx, by, bx + bw, by + 12f, 6f,
            watchColor("title:bgf", if (ht > 0.5f) palette.rowHover else palette.row).alphaF(0.9f * a),
            palette.panelBorder.alphaF(a), 1f,
        )
        val lw = textWidth(WatermarkFont.TEXT, label, 6f)
        drawTextCentered(ctx, WatermarkFont.TEXT, label, bx + (bw - lw) / 2f, by + 6f, 6f, palette.desc.alphaF(a))
        addHit(bx, by, bx + bw, by + 12f) { button ->
            if (button == 0) {
                InterfaceSettings.menuBackground = background % 3 + 1
                syncSpectrum()
                true
            } else {
                false
            }
        }
    }

    private val openedAtForStagger = System.currentTimeMillis()

    private fun stackHeight(): Float = buttons.size * BUTTON_H + (buttons.size - 1) * BUTTON_GAP

    private val buttons: List<Pair<String, () -> Unit>> = buildList {
        add("Singleplayer" to { mc.setScreen(SelectWorldScreen(this@HellhitTitleScreen)) })
        add("Multiplayer" to { mc.setScreen(JoinMultiplayerScreen(this@HellhitTitleScreen)) })
        add("Alt Manager" to { NativeScreens.openAltManager() })
        add("Proxy Manager" to { NativeScreens.openProxyManager() })
        // optional integrations: buttons only exist when the mod is actually loaded,
        // and the mod's classes are only touched through reflection on click
        if (FabricLoader.getInstance().isModLoaded("modmenu")) {
            add("Mods" to { openModMenu() })
        }
        add("Options" to { mc.setScreen(OptionsScreen(this@HellhitTitleScreen, mc.options, false)) })
        add("Quit" to { mc.stop() })
    }

    /** Opens ModMenu's mod list without a hard class reference (the mod is optional). */
    private fun openModMenu() {
        runCatching {
            val screen = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen")
                .getConstructor(Screen::class.java)
                .newInstance(this) as Screen
            mc.setScreen(screen)
        }.onFailure {
            logger.error("Failed to open the ModMenu mods screen", it)
        }
    }
}
