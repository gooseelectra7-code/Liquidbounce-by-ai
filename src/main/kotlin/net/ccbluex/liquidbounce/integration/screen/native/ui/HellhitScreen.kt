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
package net.ccbluex.liquidbounce.integration.screen.native.ui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.integration.screen.NativeClientScreen
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.alphaF
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.lerpColor
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.getOrPutNullable
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/**
 * Base class of the native Hellhit menu screens.
 *
 * Provides the shared machinery generalized from `HellhitClickGuiScreen` (which stays
 * untouched until the consolidation phase): the design-scale transform ("as if guiScale
 * 2", so geometry numbers match the ClickGUI at any real gui scale), the per-frame hit
 * list, hover/colour animation maps with the one shared easing, pixel-snapped Inter text,
 * an open/close fade, and a pill-button widget.
 *
 * Subclasses lay out in DESIGN UNITS inside [drawContent]; [drawBackdrop] runs before the
 * transform in raw gui units (fullscreen backgrounds, visualizers).
 */
@Suppress("TooManyFunctions")
internal abstract class HellhitScreen(title: String) : Screen(title.asPlainText()), NativeClientScreen {

    protected val palette = HellhitPalette

    // - motion -
    private val openedAt = System.currentTimeMillis()
    private var closingAt = 0L
    private var lastFrameMs = 0L
    protected var frameDt = 1f / 60f
        private set
    protected var motion = 0f
        private set

    // - transform (design units <-> gui units) -
    protected var curScale = 1f
        private set
    private var curTx = 0f
    private var curTy = 0f

    @PublishedApi
    internal val curTxAccess: Float get() = curTx

    @PublishedApi
    internal val curTyAccess: Float get() = curTy
    protected var vw = 0f
        private set
    protected var vh = 0f
        private set

    protected val dpr: Float get() = mc.window.guiScale.toFloat()

    /** Glyph atlases rasterize at design-pt x2 — the density the layout targets. */
    protected val density: Float get() = 2f

    private val fitScale: Float get() = if (dpr > 0f) 2f / dpr else 1f

    // - hits -
    protected class Hit(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val onClick: (button: Int) -> Boolean,
    ) {
        fun contains(mx: Float, my: Float) = mx >= x0 && mx <= x1 && my >= y0 && my <= y1
    }

    protected val hits = ArrayList<Hit>(64)

    /** While false, [addHit] is a no-op — used by fading-out modals that must stay visible but inert. */
    protected var hitsEnabled = true

    protected fun addHit(x0: Float, y0: Float, x1: Float, y1: Float, onClick: (Int) -> Boolean) {
        if (!hitsEnabled) return
        hits += Hit(x0, y0, x1, y1, onClick)
    }

    // - animation state -
    private class ColorLerp(var from: Color4b, var to: Color4b, var startMs: Long) {
        var frame = 0L
    }

    private val colorLerps = HashMap<String, ColorLerp>()
    private var frameIndex = 0L
    private val hoverProgress = HashMap<String, Float>()
    private val hoveredKeys = HashSet<String>()
    private val clipCache = HashMap<String, String>()

    // - keyed float springs + row lifecycles (list animations) -
    private class FloatAnim(var cur: Float)

    private val floatAnims = HashMap<String, FloatAnim>()

    private class RowLife {
        var bornAt = 0L
        var lastSeenFrame = 0L
    }

    private val rowLives = HashMap<String, RowLife>()

    protected var mouseXv = 0f
        private set
    protected var mouseYv = 0f
        private set

    companion object {
        const val OPEN_MS = 160f
        const val CLOSE_MS = 140f
        const val HOVER_SEC = 0.10f
        const val COLOR_LERP_MS = 140f
        const val BUTTON_W = 158f
        const val BUTTON_H = 18f
        const val BUTTON_GAP = 5f
    }

    // ------------------------------------------------------------------ *
    // Helpers
    // ------------------------------------------------------------------ *

    protected fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val inv = -2f * x + 2f
            1f - inv * inv * inv / 2f
        }
    }

    /** Resolved-colour watcher with the same-frame conflict guard. */
    protected fun watchColor(key: String, target: Color4b): Color4b {
        val now = System.currentTimeMillis()
        val lerp = colorLerps.getOrPut(key) { ColorLerp(target, target, 0L) }
        if (lerp.to != target && lerp.frame != frameIndex) {
            val t = ((now - lerp.startMs) / COLOR_LERP_MS).coerceIn(0f, 1f)
            lerp.from = lerpColor(lerp.from, lerp.to, t)
            lerp.to = target
            lerp.startMs = now
        }
        lerp.frame = frameIndex
        val t = ((now - lerp.startMs) / COLOR_LERP_MS).coerceIn(0f, 1f)
        return lerpColor(lerp.from, lerp.to, t)
    }

    protected fun hover(key: String, hovered: Boolean): Float {
        if (hovered) hoveredKeys += key
        return easeInOutCubic(hoverProgress[key] ?: 0f)
    }

    /**
     * Exponentially settles a keyed value toward [target]; snaps to the device pixel
     * grid once close so resting glyphs stay pixel-crisp. Used for row positions —
     * reordering slides rows to their new slots instead of teleporting them.
     */
    protected fun animFloat(key: String, target: Float, tau: Float = 0.06f): Float {
        val anim = floatAnims.getOrPut(key) { FloatAnim(target) }
        val diff = target - anim.cur
        val grid = dpr * curScale
        anim.cur = if (abs(diff) * grid <= 1f) {
            if (grid > 0f) Math.round(target * grid) / grid else target
        } else {
            anim.cur + diff * (1f - exp(-frameDt / tau))
        }
        return anim.cur
    }

    /** Exponentially settled 0..1 progress for a keyed on/off state (modals etc.). */
    protected fun animUnit(key: String, target: Float, tau: Float = 0.05f): Float {
        val anim = floatAnims.getOrPut(key) { FloatAnim(target) }
        anim.cur += (target - anim.cur) * (1f - exp(-frameDt / tau))
        if (abs(target - anim.cur) < 0.002f) anim.cur = target
        return anim.cur.coerceIn(0f, 1f)
    }

    /**
     * Fade-in progress of a keyed list row. Rows seen for the first time (or re-entering
     * after being filtered out) fade in; during the initial population right after the
     * screen opened, [index] adds a small cascade delay.
     */
    protected fun rowAppear(key: String, index: Int = 0): Float {
        val now = System.currentTimeMillis()
        val life = rowLives.getOrPut(key) { RowLife().also { it.bornAt = now } }
        if (frameIndex - life.lastSeenFrame > 2) life.bornAt = now
        life.lastSeenFrame = frameIndex
        val stagger = if (life.bornAt - openedAt < 400L) index * 25L else 0L
        return easeInOutCubic(((now - life.bornAt) - stagger) / 180f)
    }

    protected fun textWidth(font: WatermarkFont, text: String, px: Float): Float = font.width(text, px, density)

    protected fun lineHeight(font: WatermarkFont, px: Float): Float = font.lineHeight(px, density)

    protected fun drawText(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, y: Float, px: Float, color: Color4b,
    ) {
        val argb = color.argb
        font.submit(ctx, text, x, y, px, density) { argb }
    }

    protected fun drawTextCentered(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, cy: Float, px: Float, color: Color4b,
    ) = drawText(ctx, font, text, x, cy - lineHeight(font, px) / 2f, px, color)

    protected fun clipText(font: WatermarkFont, text: String, px: Float, maxW: Float): String {
        val key = "$text|${(px * 10).toInt()}|${maxW.toInt()}|${if (font === WatermarkFont.BRAND) 'b' else 't'}"
        clipCache[key]?.let { return it }
        var result = text
        if (textWidth(font, text, px) > maxW) {
            var t = text
            while (t.isNotEmpty() && textWidth(font, "$t..", px) > maxW) t = t.dropLast(1)
            result = "$t.."
        }
        if (clipCache.size > 512) clipCache.clear()
        clipCache[key] = result
        return result
    }

    // ------------------------------------------------------------------ *
    // Text input (buffered live editing + blinking caret)
    // ------------------------------------------------------------------ *

    protected class TextInput(val id: String, var text: String = "", val placeholder: String = "")

    protected var focusedInput: TextInput? = null

    protected fun caretOn(): Boolean = (System.currentTimeMillis() / 530L) % 2L == 0L

    /** Called when Enter is pressed inside [input]. */
    protected open fun onInputSubmit(input: TextInput) {
        focusedInput = null
    }

    @Suppress("LongParameterList")
    protected fun drawTextInput(
        ctx: GuiGraphicsExtractor, input: TextInput,
        x: Float, y: Float, w: Float, h: Float,
        px: Float = 6.5f, alphaF: Float = 1f,
    ) {
        val focused = focusedInput === input
        ctx.drawRoundedRect(
            x, y, x + w, y + h, HellhitPalette.R_CTRL,
            palette.well.alphaF(alphaF),
            (if (focused) palette.accent else palette.hairline).alphaF(alphaF), 1f,
        )
        val cy = y + h / 2f
        if (input.text.isEmpty() && !focused) {
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, input.placeholder, px, w - 10f),
                x + 5f, cy, px, palette.dim.alphaF(alphaF),
            )
        } else {
            val text = clipText(WatermarkFont.TEXT, input.text, px, w - 10f)
            drawTextCentered(ctx, WatermarkFont.TEXT, text, x + 5f, cy, px, palette.white.alphaF(alphaF))
            if (focused && caretOn()) {
                val tw = textWidth(WatermarkFont.TEXT, text, px)
                ctx.drawQuad(x + 5.7f + tw, cy - 3.4f, x + 6.5f + tw, cy + 3.4f, palette.white.alphaF(alphaF), null)
            }
        }
        addHit(x, y, x + w, y + h) { button ->
            if (button == 0) {
                focusedInput = input
                true
            } else {
                false
            }
        }
    }

    // ------------------------------------------------------------------ *
    // Scroll (one region per screen: the list viewport)
    // ------------------------------------------------------------------ *

    private var scrollYCur = 0f
    protected var scrollTarget = 0f
    private var scrollMax = 0f

    /** Smoothed scroll offset; call once per frame with the viewport/content heights. */
    protected fun updateScroll(viewH: Float, contentH: Float): Float {
        scrollMax = (contentH - viewH).coerceAtLeast(0f)
        scrollTarget = scrollTarget.coerceIn(0f, scrollMax)
        val diff = scrollTarget - scrollYCur
        val grid = dpr * curScale
        scrollYCur = if (abs(diff) * grid <= 1f) {
            if (grid > 0f) Math.round(scrollTarget * grid) / grid else scrollTarget
        } else {
            scrollYCur + diff * (1f - exp(-frameDt / 0.03f))
        }
        return scrollYCur
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double,
        horizontalAmount: Double, verticalAmount: Double,
    ): Boolean {
        if (isClosing) return true
        scrollTarget = (scrollTarget - verticalAmount.toFloat() * 22f).coerceIn(0f, scrollMax)
        return true
    }

    /** Scissors a DESIGN-unit rect (the scissor stack works in raw gui units). */
    protected inline fun clipDesign(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, body: () -> Unit,
    ) {
        val gx0 = floor((x * curScale + curTxAccess).toDouble()).toInt()
        val gy0 = floor((y * curScale + curTyAccess).toDouble()).toInt()
        val gx1 = ceil(((x + w) * curScale + curTxAccess).toDouble()).toInt()
        val gy1 = ceil(((y + h) * curScale + curTyAccess).toDouble()).toInt()
        ctx.scissorStack.push(ScreenRectangle(gx0, gy0, (gx1 - gx0).coerceAtLeast(1), (gy1 - gy0).coerceAtLeast(1)))
        try {
            body()
        } finally {
            ctx.scissorStack.pop()
        }
    }

    /** Fullscreen dim behind modals, in design units. */
    protected fun drawScrim(ctx: GuiGraphicsExtractor, alphaF: Float) {
        ctx.drawQuad(0f, 0f, vw, vh, Color4b(0, 0, 0, (150 * alphaF).toInt()), null)
    }

    /** The Hellhit pill button; returns its bottom edge. */
    @Suppress("LongParameterList")
    protected fun pillButton(
        ctx: GuiGraphicsExtractor, key: String, label: String,
        x: Float, y: Float, w: Float = BUTTON_W, h: Float = BUTTON_H,
        alphaF: Float = 1f,
        primary: Boolean = false,
        px: Float = 7f,
        onClick: () -> Unit,
    ): Float {
        val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + h)
        val ht = hover(key, hovered)
        val fill = watchColor(
            "pbf:$key",
            if (primary) {
                lerpColor(palette.accent, palette.white, 0.12f * ht)
            } else {
                lerpColor(palette.row, palette.rowHover, ht)
            },
        )
        ctx.drawRoundedRect(x, y, x + w, y + h, HellhitPalette.R_ROW, fill.alphaF(0.94f * alphaF), null)
        if (!primary && ht > 0.01f) {
            // accent hairline rises with hover
            ctx.drawRoundedRect(
                x, y + h - 1.2f, x + w * ht, y + h, 0.6f,
                palette.accent.alphaF(0.8f * ht * alphaF), null,
            )
        }
        val fg = if (primary) {
            palette.ink
        } else {
            watchColor("pbt:$key", lerpColor(palette.textOff, palette.white, ht))
        }
        val labelClipped = clipText(WatermarkFont.TEXT, label, px, w - 10f)
        val lw = textWidth(WatermarkFont.TEXT, labelClipped, px)
        drawTextCentered(ctx, WatermarkFont.TEXT, labelClipped, x + (w - lw) / 2f, y + h / 2f, px, fg.alphaF(alphaF))
        addHit(x, y, x + w, y + h) { button ->
            if (button == 0) {
                onClick()
                true
            } else {
                false
            }
        }
        return y + h
    }

    // ------------------------------------------------------------------ *
    // Icon buttons (row hover controls)
    // ------------------------------------------------------------------ *

    protected enum class RowIcon(val texture: String) {
        UP("up"),
        DOWN("down"),
        EDIT("edit"),
        DELETE("delete"),
        STAR("star"),
        CHECK("check"),
    }

    /** Small square control button with a vector icon; used by list rows on hover. */
    @Suppress("LongParameterList")
    protected fun iconButton(
        ctx: GuiGraphicsExtractor, key: String, icon: RowIcon,
        x: Float, y: Float, size: Float = 12f,
        alphaF: Float = 1f,
        active: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        val hovered = mouseXv in x..(x + size) && mouseYv in y..(y + size)
        val ht = hover(key, hovered)
        val fill = watchColor("ibf:$key", lerpColor(palette.well, palette.rowHover, ht))
        val border = watchColor(
            "ibb:$key",
            when {
                ht > 0.5f && danger -> palette.rose
                ht > 0.5f -> palette.accent
                else -> palette.hairline
            },
        )
        ctx.drawRoundedRect(
            x, y, x + size, y + size, HellhitPalette.R_CTRL,
            fill.alphaF(0.95f * alphaF), border.alphaF(alphaF), 1f,
        )
        val iconColor = when {
            danger -> lerpColor(palette.desc, palette.rose, ht)
            active -> palette.accent
            else -> lerpColor(palette.desc, palette.white, ht)
        }
        drawRowIcon(ctx, icon, x + size / 2f, y + size / 2f, size, iconColor.alphaF(alphaF))
        addHit(x, y, x + size, y + size) { button ->
            if (button == 0) {
                onClick()
                true
            } else {
                false
            }
        }
    }

    /** Lucide glyph, tinted by the vertex color (the PNGs are white with alpha coverage). */
    private fun drawRowIcon(
        ctx: GuiGraphicsExtractor, icon: RowIcon,
        cx: Float, cy: Float, size: Float, color: Color4b,
    ) {
        val setup = RowIcons.get(icon.texture) ?: return
        val half = size * 0.33f
        ctx.drawTexQuad(setup, cx - half, cy - half, cx + half, cy + half, argb = color.argb)
    }

    // ------------------------------------------------------------------ *
    // Frame
    // ------------------------------------------------------------------ *

    /** Fullscreen layer in RAW gui units (backgrounds, visualizers). */
    protected open fun drawBackdrop(ctx: GuiGraphicsExtractor) {}

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // The native screens paint their own wallpaper in [drawBackdrop]; the vanilla
        // chain (theme background, panorama, menu blur + grey darkening) would wash it out.
    }

    /** The screen content, laid out in design units (vw x vh). */
    protected abstract fun drawContent(ctx: GuiGraphicsExtractor)

    @Suppress("CognitiveComplexMethod")
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        hits.clear()

        val now = System.currentTimeMillis()
        frameDt = (if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 50L)) / 1000f
        lastFrameMs = now
        frameIndex++

        // advance hover map from the previous frame's feed
        for (key in hoverProgress.keys.toList()) {
            val target = if (key in hoveredKeys) 1f else 0f
            val delta = frameDt / HOVER_SEC
            val cur = hoverProgress.getValue(key)
            val next = if (target > cur) (cur + delta).coerceAtMost(1f) else (cur - delta).coerceAtLeast(0f)
            if (next <= 0f && key !in hoveredKeys) hoverProgress.remove(key) else hoverProgress[key] = next
        }
        for (key in hoveredKeys) hoverProgress.getOrPut(key) { 0f }
        hoveredKeys.clear()

        val openT = ((now - openedAt) / OPEN_MS).coerceIn(0f, 1f)
        val closeT = if (closingAt == 0L) 0f else ((now - closingAt) / CLOSE_MS).coerceIn(0f, 1f)
        motion = easeInOutCubic(openT) * (1f - easeInOutCubic(closeT))

        drawBackdrop(ctx)

        val pop = 0.97f + 0.03f * motion
        curScale = fitScale * pop
        vw = width / fitScale
        vh = height / fitScale
        val rawTx = width / 2f * (1f - pop)
        val rawTy = height / 2f * (1f - pop)
        val atRest = motion >= 0.999f
        val d = dpr
        curTx = if (atRest && d > 0f) Math.round(rawTx * d) / d else rawTx
        curTy = if (atRest && d > 0f) Math.round(rawTy * d) / d else rawTy

        mouseXv = (mouseX - curTx) / curScale
        mouseYv = (mouseY - curTy) / curScale

        ctx.pose().pushMatrix()
        ctx.pose().translate(curTx, curTy)
        ctx.pose().scale(curScale, curScale)
        try {
            drawContent(ctx)
        } finally {
            ctx.pose().popMatrix()
        }
    }

    // ------------------------------------------------------------------ *
    // Lifecycle + input
    // ------------------------------------------------------------------ *

    protected fun startClose() {
        if (closingAt == 0L) closingAt = System.currentTimeMillis()
    }

    val isClosing: Boolean get() = closingAt != 0L

    override fun tick() {
        if (closingAt != 0L && System.currentTimeMillis() - closingAt >= CLOSE_MS.toLong()) {
            super.onClose()
        }
    }

    override fun onClose() {
        startClose()
    }

    /** ESC behavior; default plays the fade and closes. Title overrides to a no-op. */
    protected open fun onEscape() {
        startClose()
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (isClosing) return true
        val mx = (click.x.toFloat() - curTx) / curScale
        val my = (click.y.toFloat() - curTy) / curScale
        val button = click.button()
        focusedInput = null // click-away unfocuses; the field's own hit refocuses
        for (hit in hits.asReversed()) {
            if (hit.contains(mx, my) && hit.onClick(button)) {
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (isClosing) return true
        // arrow / page keys scroll the list (updateScroll clamps the target each frame)
        when (input.key) {
            GLFW.GLFW_KEY_UP -> { scrollTarget -= 28f; return true }
            GLFW.GLFW_KEY_DOWN -> { scrollTarget += 28f; return true }
            GLFW.GLFW_KEY_PAGE_UP -> { scrollTarget -= 210f; return true }
            GLFW.GLFW_KEY_PAGE_DOWN -> { scrollTarget += 210f; return true }
        }
        val field = focusedInput
        if (field != null) {
            when (input.key) {
                GLFW.GLFW_KEY_BACKSPACE ->
                    field.text = if (isCtrlDown()) deleteWordBackward(field.text) else field.text.dropLast(1)
                GLFW.GLFW_KEY_ENTER -> onInputSubmit(field)
                GLFW.GLFW_KEY_ESCAPE -> focusedInput = null
                GLFW.GLFW_KEY_V -> if (isCtrlDown()) field.text += mc.keyboardHandler.clipboard
            }
            return true
        }
        if (input.key == GLFW.GLFW_KEY_ESCAPE) {
            onEscape()
            return true
        }
        return super.keyPressed(input)
    }

    private fun isCtrlDown(): Boolean {
        val window = mc.window
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
    }

    /**
     * Windows-style Ctrl+Backspace: drop the trailing run of non-word chars, then the
     * word before it — so `mc.hypixel.net` deletes to `mc.hypixel.`, then `mc.`, ...
     */
    private fun deleteWordBackward(s: String): String {
        var i = s.length
        while (i > 0 && !s[i - 1].isLetterOrDigit()) i--
        while (i > 0 && s[i - 1].isLetterOrDigit()) i--
        return s.substring(0, i)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        if (isClosing) return true
        val field = focusedInput
        if (field != null) {
            val codepoint = input.codepoint()
            if (codepoint in 32..0xFFFF) {
                val ch = codepoint.toChar()
                if (!ch.isISOControl()) field.text += ch
            }
            return true
        }
        return super.charTyped(input)
    }

    override fun isPauseScreen(): Boolean = false
}

/** Cache of the row-control glyph textures (`textures/hellhit/icons/<name>.png`). */
private object RowIcons {

    private val setups = HashMap<String, TextureSetup?>()

    fun get(name: String): TextureSetup? = setups.getOrPutNullable(name) {
        try {
            val image = LiquidBounce.resource("textures/hellhit/icons/$name.png").readNativeImage()
            val texture = image.asTexture { "HellhitRowIcon-$name" }
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            )
        } catch (e: Throwable) {
            logger.error("Failed to load row icon $name", e)
            null
        }
    }
}
