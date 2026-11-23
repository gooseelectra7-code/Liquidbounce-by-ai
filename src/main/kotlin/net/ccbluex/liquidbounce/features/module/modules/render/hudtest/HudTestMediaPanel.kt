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

import com.google.common.base.Suppliers
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGuiElement
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.ccbluex.liquidbounce.features.module.modules.render.watermarkBounds
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Matrix3x2f
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream

/**
 * The top-right "now playing" card, driven by [HudTestMediaBridge].
 *
 * Everything on it is real: the Windows media session's title, its playback state, its cover art
 * and a progress bar that interpolates the position between the one-second polls so it moves
 * smoothly. The transport glyphs are clickable while a screen is open and can also be bound to
 * keys. With no session — or on any machine where the bridge cannot be built — the panel simply
 * does not draw.
 */
internal class HudTestMediaPanel(parent: EventListener) : HudTestPanel(
    parent,
    name = "NowPlaying",
    defaultAnchor = HudTestAnchor.TOP_RIGHT,
    defaultOffsetX = -24f,
    defaultOffsetY = 28f,
) {

    private val showCover by boolean("Cover", true)
    private val showTransport by boolean("Transport", true)
    private val previousKey by key("PreviousKey", GLFW.GLFW_KEY_UNKNOWN)
    private val playPauseKey by key("PlayPauseKey", GLFW.GLFW_KEY_UNKNOWN)
    private val nextKey by key("NextKey", GLFW.GLFW_KEY_UNKNOWN)

    private var coverSetup: TextureSetup? = null
    private var coverTexture: DynamicTexture? = null
    private var coverWidth = 0
    private var coverHeight = 0

    /** The eased progress fraction, so a seek glides instead of jumping. */
    private var progress = 0f

    override fun render(event: OverlayRenderEvent, dt: Float) {
        uploadPendingCover()
        val session = HudTestMediaBridge.current()
        val alpha = fade(session != null, dt)
        if (alpha <= MIN_ALPHA || session == null) {
            return
        }

        val target = if (session.durationMs > 0L) {
            HudTestMediaBridge.positionMs(session).toFloat() / session.durationMs
        } else {
            0f
        }
        progress += (target.coerceIn(0f, 1f) - progress) * HudTestTheme.damp(BAR_SMOOTHING, dt)

        val density = frameDensity
        val frame = frame(WIDTH, HEIGHT, alpha)
        val ctx = event.context

        ctx.pushFrame(frame)
        ctx.hudCard(WIDTH, HEIGHT, RADIUS, alpha, density, ModuleHudTest.glass(ctx))
        ctx.hudShapes(0f, 0f, WIDTH, HEIGHT, density) {
            if (!showCover || coverSetup == null) {
                roundRect(COVER_X, COVER_Y, COVER, COVER, COVER_RADIUS, scaleAlpha(HudTestTheme.TRACK, alpha))
            }
            bar(
                TEXT_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT, progress,
                scaleAlpha(HudTestTheme.TRACK, alpha),
                scaleAlpha(HudTestTheme.BAR_FILL_MEDIA, alpha),
            )
            if (showTransport) {
                paintTransport(this, session.playing, alpha)
            }
        }
        ctx.hudText(
            WatermarkFont.BRAND, fitTitle(session.title, density), TEXT_X,
            textTopOnBaseline(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, TITLE_BASELINE),
            HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
        )
        if (showCover) {
            drawCover(ctx, alpha)
        }
        ctx.popFrame()

        if (showTransport) {
            registerTransportButtons(frame)
        }
    }

    /** Handles the optional media keybinds; returns whether the key was consumed. */
    fun handleKey(event: KeyboardKeyEvent): Boolean {
        if (event.action != GLFW.GLFW_PRESS || !enabled) {
            return false
        }
        val command = when (event.keyCode) {
            boundCode(previousKey) -> "prev"
            boundCode(playPauseKey) -> "toggle"
            boundCode(nextKey) -> "next"
            else -> return false
        }
        HudTestMediaBridge.command(command)
        return true
    }

    /** An unbound key must never match a real key code, so it maps to a code nothing produces. */
    private fun boundCode(bound: InputConstants.Key): Int =
        if (bound == InputConstants.UNKNOWN) Int.MIN_VALUE else bound.value

    // --- Transport --------------------------------------------------------------------------------

    private fun transportX(index: Int): Float = TRANSPORT_END - GLYPH - (2 - index) * TRANSPORT_PITCH

    private fun paintTransport(shapes: HudTestShapes, playing: Boolean, alpha: Float) {
        val argb = scaleAlpha(HudTestTheme.ACCENT_ICON, alpha)
        shapes.glyphPrevious(transportX(0), GLYPH_Y, GLYPH, argb)
        if (playing) {
            shapes.glyphPause(transportX(1), GLYPH_Y, GLYPH, argb)
        } else {
            shapes.glyphPlay(transportX(1), GLYPH_Y, GLYPH, argb)
        }
        shapes.glyphNext(transportX(2), GLYPH_Y, GLYPH, argb)
    }

    private fun registerTransportButtons(frame: HudTestFrame) {
        val commands = arrayOf("prev", "toggle", "next")
        for (index in 0 until 3) {
            val command = commands[index]
            HudTestInput.addButton(
                frame,
                transportX(index) - HIT_PAD,
                GLYPH_Y - HIT_PAD,
                GLYPH + HIT_PAD * 2f,
                GLYPH + HIT_PAD * 2f,
            ) { HudTestMediaBridge.command(command) }
        }
    }

    // --- Cover ------------------------------------------------------------------------------------

    /**
     * Uploads a cover the poll thread decoded, on the render thread where the GPU can be touched.
     * An empty payload means the session has no art, which drops back to the flat placeholder.
     */
    private fun uploadPendingCover() {
        val bytes = HudTestMediaBridge.takePendingCover() ?: return
        coverTexture?.close()
        coverTexture = null
        coverSetup = null
        if (bytes.isEmpty()) {
            return
        }
        try {
            val image = ByteArrayInputStream(bytes).use(NativeImage::read)
            coverWidth = image.width
            coverHeight = image.height
            val texture = DynamicTexture(Suppliers.ofInstance("HudTest cover"), image)
            coverTexture = texture
            coverSetup = TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            )
        } catch (e: Throwable) {
            logger.info("HudTest: could not decode the media cover: ${e.message}")
        }
    }

    /**
     * The cover inside the thumbnail's rounded square, centre-cropped to a square so a wide 336x188
     * album banner is not squashed.
     */
    private fun drawCover(ctx: GuiGraphicsExtractor, alpha: Float) {
        val setup = coverSetup ?: return
        val contour = HudTestGeometry.contour(
            COVER_X, COVER_Y, COVER_X + COVER, COVER_Y + COVER, COVER_RADIUS, 0f,
        )
        val tint = scaleAlpha(-1, alpha)
        val cropX = if (coverWidth > coverHeight) (coverWidth - coverHeight) * 0.5f / coverWidth else 0f
        val cropY = if (coverHeight > coverWidth) (coverHeight - coverWidth) * 0.5f / coverHeight else 0f
        val uAt = { vx: Float -> cropX + (vx - COVER_X) / COVER * (1f - cropX * 2f) }
        val vAt = { vy: Float -> cropY + (vy - COVER_Y) / COVER * (1f - cropY * 2f) }

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI_TEXTURED,
                setup,
                Matrix3x2f(ctx.pose()),
                ctx.scissorStack.peek(),
                ctx.watermarkBounds(COVER_X, COVER_Y, COVER_X + COVER, COVER_Y + COVER),
            ) { pose ->
                HudTestGeometry.fan(contour, COVER_X + COVER * 0.5f, COVER_Y + COVER * 0.5f) { vx, vy ->
                    addVertexWith2DPose(pose, vx, vy).setUv(uAt(vx), vAt(vy)).setColor(tint)
                }
            }
        )
    }

    /** Trims the title to the width between the thumbnail and the transport glyphs. */
    private fun fitTitle(title: String, density: Float): String {
        val limit = TRANSPORT_START - TEXT_X - TITLE_GAP
        if (WatermarkFont.BRAND.width(title, HudTestTheme.TEXT_PX, density) <= limit) {
            return title
        }
        var length = title.length
        while (length > 1) {
            val candidate = title.take(length) + ELLIPSIS
            if (WatermarkFont.BRAND.width(candidate, HudTestTheme.TEXT_PX, density) <= limit) {
                return candidate
            }
            length--
        }
        return ELLIPSIS
    }

    private companion object {
        const val WIDTH = 218f
        const val HEIGHT = 47f
        const val RADIUS = 12f

        const val COVER_X = 5f
        const val COVER_Y = 1.5f
        const val COVER = 44f
        const val COVER_RADIUS = 10f

        const val TEXT_X = 66f
        const val TITLE_BASELINE = 23f
        const val TITLE_GAP = 8f
        const val ELLIPSIS = "..."

        const val BAR_Y = 32f
        const val BAR_WIDTH = 135f
        const val BAR_HEIGHT = 2f

        const val GLYPH = HudTestTheme.ICON
        const val GLYPH_Y = 13f
        const val TRANSPORT_END = 202f
        const val TRANSPORT_PITCH = 16f
        const val TRANSPORT_START = TRANSPORT_END - GLYPH - 2f * TRANSPORT_PITCH
        const val HIT_PAD = 4f

        /** Fraction of the progress bar's remaining travel left after one second. */
        const val BAR_SMOOTHING = 1.0e-3f
        const val MIN_ALPHA = 0.004f
    }
}
