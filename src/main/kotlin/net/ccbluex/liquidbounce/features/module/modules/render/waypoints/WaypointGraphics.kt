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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGlass
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawGradientRoundedRect
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawRoundedRectShadow
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.util.ARGB
import org.joml.Matrix3x2f
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shared look of the waypoint feature, in the visual language of the Glass HUD (`ModuleHud2`,
 * its Classic defaults): dark translucent glass with a real blur of what is behind it, a vertical
 * body gradient, a soft feathered shadow, a hairline rim, the lavender accent and generous corner
 * radii. Plus one marker shape (a faceted diamond) and one arrow, so a waypoint looks the same on
 * the HUD minimap, the world map and in the world.
 *
 * The blur is [WatermarkGlass], the capture every native HUD shares (one per frame). It is used only
 * while no menu is open, see [glass]; the world map screen draws the same panels solid.
 */
internal object WaypointGraphics {

    // Glass HUD palette (ModuleHud2 Classic defaults: BackgroundTop/Bottom, AccentFrom/To, InfoColor).
    const val GLASS_TOP = 0x9612141C.toInt()
    const val GLASS_BOTTOM = 0xAF0A0B10.toInt()

    /** The same gradient where there is no blur under it: over a bright map it must stay legible. */
    const val SOLID_TOP = 0xE612141C.toInt()
    const val SOLID_BOTTOM = 0xF00A0B10.toInt()

    const val ACCENT_FROM = 0xFFD8DDF4.toInt()
    const val ACCENT_TO = 0xFF8690D6.toInt()
    const val ACCENT = 0xFFAFB6E5.toInt()

    const val RIM = 0x24FFFFFF
    const val SHADOW = 0xE0050608.toInt()
    const val TEXT = 0xFFECEEF5.toInt()
    const val TEXT_DIM = 0xFF989EB6.toInt()

    /** Kept for the few flat fills that are not panels (list rows, fields). */
    const val GLASS = 0xE0101218.toInt()
    const val GLASS_LIGHT = 0xE8141620.toInt()

    const val PANEL_RADIUS = 12f
    const val CONTROL_RADIUS = 8f

    /** The Glass HUD's default BlurStrength. */
    private const val BLUR_STRENGTH = 7
    private const val SHADOW_ALPHA = 0.32f
    private const val CORNER_SEGMENTS = 8

    val font: WatermarkFont get() = WatermarkFont.TEXT
    val boldFont: WatermarkFont get() = WatermarkFont.BRAND

    val density: Float get() = mc.window.guiScale.toFloat().coerceAtLeast(1f)

    fun withAlpha(argb: Int, alpha: Float): Int =
        ARGB.color((ARGB.alpha(argb) * alpha.coerceIn(0f, 1f)).toInt(), argb and 0xFFFFFF)

    fun lighten(argb: Int, amount: Float): Int = ARGB.srgbLerp(amount, argb, 0xFFFFFFFF.toInt())

    // ------------------------------------------------------------------------------------------
    //  Glass
    // ------------------------------------------------------------------------------------------

    /**
     * The blurred frame to sample under a panel, or `null` (then panels are drawn solid).
     *
     * Only in the HUD with no screen open (or the chat, whose background is empty - `ChatScreen.
     * extractBackground` - so it never blurs): `blurBeforeThisStratum` may be marked once per frame and
     * while a capture is pending the client swallows every later mark (`MixinGuiRenderState`) - a
     * mark of ours with a menu open would take the menu's own background blur away. If someone else
     * already marked the frame without [WatermarkGlass], vanilla throws; that is caught and the
     * panel goes solid rather than breaking the frame.
     */
    fun glass(ctx: GuiGraphicsExtractor): TextureSetup? {
        val screen = mc.screen
        if (screen != null && screen !is ChatScreen) return null
        // A HUD theme already asked this frame: share its capture, keep its strength and passes.
        if (WatermarkGlass.isCapturePending()) return WatermarkGlass.pendingSetup()
        // A mark without a capture behind it would make vanilla blur the whole frame in place.
        if (!WatermarkGlass.isAvailable) return null
        try {
            ctx.guiRenderState.blurBeforeThisStratum()
        } catch (_: IllegalStateException) {
            return null
        }
        return WatermarkGlass.request(BLUR_STRENGTH)
    }

    /** A rounded rectangle as a convex polygon, clockwise on screen. */
    fun roundedPolygon(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): Pair<FloatArray, FloatArray> {
        val r = radius.coerceIn(0f, minOf(x1 - x0, y1 - y0) / 2f)
        val centres = arrayOf(
            floatArrayOf(x1 - r, y1 - r),
            floatArrayOf(x0 + r, y1 - r),
            floatArrayOf(x0 + r, y0 + r),
            floatArrayOf(x1 - r, y0 + r),
        )
        val count = centres.size * (CORNER_SEGMENTS + 1)
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        var n = 0
        for ((index, centre) in centres.withIndex()) {
            val start = index * PI / 2.0
            for (step in 0..CORNER_SEGMENTS) {
                val angle = start + (PI / 2.0) * step / CORNER_SEGMENTS
                xs[n] = centre[0] + (cos(angle) * r).toFloat()
                ys[n] = centre[1] + (sin(angle) * r).toFloat()
                n++
            }
        }
        return xs to ys
    }

    /**
     * Samples [glass] inside the convex polygon ([xs], [ys]): the blurred frame shows exactly where
     * the panel is. UVs are the screen position of each vertex (the capture is the whole frame).
     */
    fun GuiGraphicsExtractor.glassFill(glass: TextureSetup, xs: FloatArray, ys: FloatArray, alpha: Float = 1f) {
        val textureWidth = WatermarkGlass.textureWidth.toFloat()
        val textureHeight = WatermarkGlass.textureHeight.toFloat()
        if (textureWidth <= 0f || textureHeight <= 0f || xs.size < 3) return

        val pose = Matrix3x2f(pose())
        val scale = mc.window.guiScale.toFloat()
        val n = xs.size
        val u = FloatArray(n)
        val v = FloatArray(n)
        for (i in 0 until n) {
            // local -> device pixels -> UV; the render target is stored bottom-up.
            val deviceX = (pose.m00 * xs[i] + pose.m10 * ys[i] + pose.m20) * scale
            val deviceY = (pose.m01 * xs[i] + pose.m11 * ys[i] + pose.m21) * scale
            u[i] = deviceX / textureWidth
            v[i] = 1f - deviceY / textureHeight
        }
        val tint = withAlpha(-1, alpha)
        val bounds = getBounds(xs.min(), ys.min(), xs.max(), ys.max())
        drawCustomElement(RenderPipelines.GUI_TEXTURED, glass, bounds = bounds) { matrix ->
            fan(this, matrix, xs, ys, u, v, n, tint)
        }
    }

    /**
     * A glass card: shadow, blurred background (when [glass] is given), the Glass HUD body gradient
     * and a hairline rim ([outline], if given, replaces the rim - an accent or a waypoint colour).
     */
    @Suppress("LongParameterList")
    fun GuiGraphicsExtractor.glassPanel(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radius: Float,
        glass: TextureSetup?,
        alpha: Float = 1f,
        outline: Int? = null,
        shadow: Boolean = true,
    ) {
        val width = x1 - x0
        val height = y1 - y0
        if (width <= 0f || height <= 0f || alpha <= 0.01f) return
        val r = radius.coerceIn(0f, minOf(width, height) / 2f)

        if (shadow) {
            drawRoundedRectShadow(
                x0, y0, width, height, r,
                Color4b(withAlpha(0xFF000000.toInt(), SHADOW_ALPHA * alpha)), blur = 9f, offsetY = 1.5f,
            )
        }
        if (glass != null) {
            val (xs, ys) = roundedPolygon(x0, y0, x1, y1, r)
            glassFill(glass, xs, ys, alpha)
        }
        val top = withAlpha(if (glass != null) GLASS_TOP else SOLID_TOP, alpha)
        val bottom = withAlpha(if (glass != null) GLASS_BOTTOM else SOLID_BOTTOM, alpha)
        drawGradientRoundedRect(x0, y0, x1, y1, r, top, bottom, bottom, top)
        drawRoundedRect(
            x0, y0, x1, y1, r,
            fillColor = null,
            outlineColor = Color4b(withAlpha(outline ?: RIM, alpha * if (outline != null) 0.85f else 1f)),
            outlineWidth = 1f,
        )
    }

    /**
     * A convex polygon as a triangle fan, each triangle a degenerate quad (the GUI pipelines draw
     * quads). Every triangle is wound the way vanilla's `fill` is, because those pipelines cull.
     */
    @Suppress("LongParameterList")
    fun fan(
        consumer: VertexConsumer,
        pose: Matrix3x2f,
        sx: FloatArray,
        sy: FloatArray,
        u: FloatArray,
        v: FloatArray,
        n: Int,
        color: Int = -1,
    ) {
        for (i in 1 until n - 1) {
            var b = i
            var c = i + 1
            val cross = (sx[b] - sx[0]) * (sy[c] - sy[0]) - (sy[b] - sy[0]) * (sx[c] - sx[0])
            if (cross > 0f) {
                b = i + 1
                c = i
            }
            consumer.addVertexWith2DPose(pose, sx[0], sy[0]).setUv(u[0], v[0]).setColor(color)
            consumer.addVertexWith2DPose(pose, sx[b], sy[b]).setUv(u[b], v[b]).setColor(color)
            consumer.addVertexWith2DPose(pose, sx[c], sy[c]).setUv(u[c], v[c]).setColor(color)
            consumer.addVertexWith2DPose(pose, sx[c], sy[c]).setUv(u[c], v[c]).setColor(color)
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Marker and arrow
    // ------------------------------------------------------------------------------------------

    /**
     * The waypoint marker, centred on ([x], [y]) with half-diagonal [radius].
     */
    fun GuiGraphicsExtractor.waypointDiamond(x: Float, y: Float, radius: Float, color: Int, alpha: Float = 1f) {
        if (alpha <= 0.01f) return
        val rim = radius + maxOf(1f, radius * 0.28f)
        quad(x, y - rim, x - rim, y, x, y + rim, x + rim, y, withAlpha(SHADOW, alpha))
        quad(x, y - radius, x - radius, y, x, y + radius, x + radius, y, withAlpha(color, alpha))
        // Upper-left facet catches the light.
        val half = radius * 0.5f
        quad(
            x, y - radius, x - radius, y, x - half * 0.2f, y - half * 0.2f, x, y - half * 0.2f,
            withAlpha(lighten(color, 0.35f), alpha * 0.9f),
        )
        val core = radius * 0.32f
        quad(x, y - core, x - core, y, x, y + core, x + core, y, withAlpha(0xFFFFFFFF.toInt(), alpha * 0.95f))
    }

    /**
     * A navigation arrow pointing along [angle] (radians, 0 = up the screen, clockwise).
     */
    @Suppress("LongParameterList")
    fun GuiGraphicsExtractor.arrow(x: Float, y: Float, angle: Float, size: Float, fill: Int, outline: Int) {
        arrowShape(x, y, angle, size * 1.32f, outline, notch = 0.28f)
        arrowShape(x, y, angle, size, fill, notch = 0.35f)
    }

    @Suppress("LongParameterList")
    private fun GuiGraphicsExtractor.arrowShape(
        x: Float,
        y: Float,
        angle: Float,
        size: Float,
        color: Int,
        notch: Float,
    ) {
        val s = sin(angle)
        val c = cos(angle)
        fun px(lx: Float, ly: Float) = x + (lx * c - ly * s) * size
        fun py(lx: Float, ly: Float) = y + (lx * s + ly * c) * size
        // tip, left, notch, right: two triangles that share the tip-notch diagonal.
        quad(
            px(0f, -1f), py(0f, -1f),
            px(-0.72f, 0.82f), py(-0.72f, 0.82f),
            px(0f, notch), py(0f, notch),
            px(0.72f, 0.82f), py(0.72f, 0.82f),
            color,
        )
    }

    /**
     * One flat quad. Vertices in the order vanilla's own `fill` uses on screen (the GUI pipelines
     * cull back faces), which a diamond given as top, left, bottom, right matches.
     */
    @Suppress("LongParameterList")
    fun GuiGraphicsExtractor.quad(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
        color: Int,
    ) {
        if (ARGB.alpha(color) == 0) return
        val bounds = getBounds(
            minOf(x0, x1, x2, x3), minOf(y0, y1, y2, y3),
            maxOf(x0, x1, x2, x3), maxOf(y0, y1, y2, y3),
        )
        drawCustomElement(RenderPipelines.GUI, bounds = bounds) { pose ->
            addVertexWith2DPose(pose, x0, y0).setColor(color)
            addVertexWith2DPose(pose, x1, y1).setColor(color)
            addVertexWith2DPose(pose, x2, y2).setColor(color)
            addVertexWith2DPose(pose, x3, y3).setColor(color)
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Text
    // ------------------------------------------------------------------------------------------

    fun textWidth(text: String, px: Float, bold: Boolean = false): Float =
        (if (bold) boldFont else font).width(text, px, density)

    fun textHeight(px: Float): Float = font.lineHeight(px, density)

    @Suppress("LongParameterList")
    fun GuiGraphicsExtractor.text(text: String, x: Float, y: Float, px: Float, color: Int, bold: Boolean = false) {
        if (text.isEmpty() || ARGB.alpha(color) == 0) return
        (if (bold) boldFont else font).submit(this, text, x, y, px, density) { color }
    }

    /**
     * Text on a glass capsule, horizontally centred on [centerX] with its top at [top].
     * Returns the capsule's height.
     */
    @Suppress("LongParameterList")
    fun GuiGraphicsExtractor.pill(
        text: String,
        centerX: Float,
        top: Float,
        px: Float,
        color: Int,
        alpha: Float = 1f,
        accent: Int? = null,
        glass: TextureSetup? = null,
    ): Float {
        val padX = px * 0.75f
        val padY = px * 0.32f
        val width = textWidth(text, px) + padX * 2f
        val height = textHeight(px) + padY * 2f
        val left = centerX - width / 2f
        glassPanel(left, top, left + width, top + height, height / 2f, glass, alpha, outline = accent)
        text(text, left + padX, top + padY, px, withAlpha(color, alpha))
        return height
    }

    fun formatDistance(distance: Double): String = when {
        distance < 1000.0 -> "${distance.toInt()}m"
        distance < 100_000.0 -> String.format(Locale.ROOT, "%.1fkm", distance / 1000.0)
        else -> "${(distance / 1000.0).toInt()}km"
    }
}
