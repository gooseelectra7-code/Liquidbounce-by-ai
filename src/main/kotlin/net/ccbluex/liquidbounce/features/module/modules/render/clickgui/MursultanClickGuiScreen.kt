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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.BindValue
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.lang.LanguageManager
import net.ccbluex.liquidbounce.features.global.GlobalManager
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.integration.screen.NativeClientScreen
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawSweepRoundedRect
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.util.ARGB
import org.lwjgl.glfw.GLFW
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Native ClickGUI in the **Mursultan** design language — a reproduction of the Nursultan
 * client's menu inside LiquidBounce.
 *
 * Layout: a 270-unit sidebar (logo, captioned category list, account footer), a header row
 * with the search field and the settings burger, and a two-column masonry of outlined cards
 * holding module rows (name + "..." + pill toggle). Clicking the dots opens a floating
 * settings popup anchored to the row; the burger opens the GUI's own settings popover.
 *
 * Everything is authored in **design units** (the window is 1200 x 753) and drawn through one
 * screen-fit scale, so the proportions of the original survive every resolution. Colours,
 * paddings, radii, row heights and the toggle geometry come from pixel forensics over
 * reference captures plus the decompiled layout tree — see `MURSULTAN_STYLE.md` in this
 * directory, which is the authority for every constant below. **Read it before changing any
 * of them.**
 *
 * Deviations from the original (LiquidBounce has no sub-categories, more categories and a
 * different settings model) are listed in section 7 of that document.
 */
@Suppress("TooManyFunctions", "LargeClass")
class MursultanClickGuiScreen :
    Screen("ClickGUI".asPlainText()),
    NativeClientScreen,
    ClickGuiSearchAware {

    override val isSearchFocused: Boolean get() = searchFocused

    // - Theme (see MURSULTAN_STYLE.md section 3) -
    private object Theme {
        val page = Color4b(13, 13, 13, 245)
        val border = Color4b(23, 23, 23, 255)
        val divider = Color4b(21, 21, 21, 255)
        val navActive = Color4b(17, 19, 21, 255)
        val navActiveBorder = Color4b(42, 42, 42, 255)
        val trackOff = Color4b(29, 29, 29, 255)
        val knobOff = Color4b(49, 49, 49, 255)
        val title = Color4b(255, 255, 255, 255)
        val textOn = Color4b(226, 226, 235, 255)
        val textOff = Color4b(146, 146, 146, 255)
        val navIdle = Color4b(172, 172, 172, 255)
        val label = Color4b(79, 79, 79, 255)
        val placeholder = Color4b(59, 59, 59, 255)
        val hover = Color4b(20, 20, 20, 255)

        /**
         * The docked settings panel is OPAQUE. At the window's own 245 alpha the bold card
         * titles behind it stayed readable through the panel (reported 2026-09-20).
         */
        val popup = Color4b(15, 15, 15, 255)
        val chip = Color4b(24, 24, 24, 255)
    }

    private companion object {
        // - Geometry, design units -
        const val DESIGN_W = 1200f
        const val DESIGN_H = 753f
        const val WINDOW_RADIUS = 14f
        const val SIDEBAR_W = 270f
        const val HEADER_H = 89f
        const val PAD = 20f
        const val COL_GAP = 20f
        const val CARD_RADIUS = 10f
        const val CARD_HEAD_H = 60f
        const val ROW_H = 67f
        const val SEARCH_H = 44f
        const val SEARCH_RADIUS = 6f
        const val BURGER_GAP = 12f
        const val NAV_ITEM_H = 50f
        const val NAV_ITEM_GAP = 6f
        const val NAV_RADIUS = 8f
        const val NAV_ICON = 20f
        const val NAV_ICON_INSET = 21f
        const val NAV_TEXT_INSET = 46f
        const val CAPTION_H = 34f
        // Shorter than the original's 52x24 and with a ROUND knob: the stadium knob read as
        // stretched and the switch as overlong (user feedback, 2026-09-20).
        const val TOGGLE_W = 44f
        const val TOGGLE_H = 22f
        const val TOGGLE_KNOB_INSET = 2f
        const val DOTS_W = 24f
        const val DOTS_GAP = 21f
        const val POPUP_W = 402f
        const val POPUP_ROW_H = 49f
        /** Gap between the window's right edge and the docked settings panel. */
        const val POPUP_DOCK_GAP = 18f
        const val POPUP_HEAD_H = 56f
        const val PILL_W = 151f
        const val PILL_H = 30f
        const val PILL_RADIUS = 8f
        const val POPOVER_W = 329f
        const val FOOTER_H = 67f
        const val LOGO_W = 52f
        const val LOGO_H = 38f

        // - Type sizes, design units -
        const val FONT_CARD_TITLE = 24f
        const val FONT_ROW = 20f
        const val FONT_PILL = 19f
        const val FONT_FOOTER = 16f
        const val FONT_CAPTION = 15f
        const val FONT_POPUP_TITLE = 21f
        const val CHIP_W = 46f
        const val CHIP_H = 26f
        const val CHIP_GAP = 6f

        // colour picker
        const val PICK_SV_H = 150f
        const val PICK_BAR_H = 14f
        const val PICK_PRESET = 26f
        const val PICK_PRESET_GAP = 8f
        const val PICK_PREVIEW_H = 28f

        /** Quick swatches under the picker, in display order. */
        val PICK_PRESETS: List<Int> = listOf(
            0xFFFFFFFF.toInt(), 0xFF9AA0A6.toInt(), 0xFF1E1E1E.toInt(),
            0xFFFF5370.toInt(), 0xFFFF9E64.toInt(), 0xFFFFD866.toInt(),
            0xFF6FE3A1.toInt(), 0xFF56C8D8.toInt(), 0xFF8BACFF.toInt(),
            0xFFC792EA.toInt(),
        )

        // - Timings -
        const val OPEN_MS = 250f
        const val CLOSE_MS = 250f
        const val COLOR_LERP_MS = 150f
        const val KNOB_SEC = 0.12f
        const val REVEAL_SEC = 0.15f
        const val SCROLL_TAU = 0.03f

        /** How much of the screen the window is allowed to take (matches the original's framing). */
        const val FIT_W = 0.50f
        const val FIT_H = 0.56f

        /** Sidebar entry -> icon. Nursultan glyphs where they exist, Solar `linear` otherwise. */
        val CATEGORY_ICONS = mapOf(
            "combat" to "mursultan/combat",
            "movement" to "mursultan/movement",
            "player" to "mursultan/player",
            "render" to "mursultan/visuals",
            "misc" to "mursultan/misc",
            "world" to "world/linear",
            "exploit" to "exploit/linear",
            "fun" to "fun/linear",
        )

        /** Virtual entry under the MANAGEMENT caption: the GUI's own settings. */
        val GUI_CATEGORY = ModuleCategory("gui")

        // - UI state, persisted across openings -
        var selectedCategory: ModuleCategory = ModuleCategories.COMBAT
        var searchQuery = ""
        var searchFocused = false
        var scrollY = 0f
        var scrollTarget = 0f

        /** Module whose settings popup is open, plus where it was opened from (design units). */
        var popupModule: String? = null
        var popupX = 0f
        var popupY = 0f
        var popupScroll = 0f
        var popupScrollTarget = 0f
        var popoverOpen = false

        /** Accent eyedropper picker, and the hue it keeps so greys stay steerable. */
        var accentPickerOpen = false
        var accentPickerT = 0f
        var accentHue: Float? = null

        val openDropdowns: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val openPickers: MutableSet<Value<*>> = Collections.newSetFromMap(IdentityHashMap())

        // - Animation state, advanced once per frame -
        val dropdownProgress = IdentityHashMap<Any, Float>()
        val pickerProgress = IdentityHashMap<Value<*>, Float>()
        val knobProgress = HashMap<String, Float>()

        /** Where each knob is heading, fed by [knobOf] during layout, consumed by the advance. */
        val knobTargets = HashMap<String, Float>()

        /**
         * Hue kept per colour value, because it cannot be recovered from the RGB once the
         * colour turns grey or black — dragging the hue bar on #000000 would otherwise snap
         * back to red on every frame.
         */
        val pickerHue = IdentityHashMap<Value<*>, Float>()
        val colorLerps = HashMap<String, ColorLerp>()
        var popupT = 0f
        var popoverT = 0f

        /**
         * Per-frame work that must NOT be redone per frame. The module set is fixed for the
         * JVM's lifetime, so the package-leaf grouping, the search index and the name lookup
         * are built once and reused; rebuilding them every frame (the first cut did) meant a
         * full filter + sort + package-name string walk over ~900 modules per frame.
         */
        val groupCache = HashMap<String, List<CardGroup>>()
        val searchCache = HashMap<String, List<CardGroup>>()
        val modulesByName: Map<String, ClientModule> by lazy { ModuleManager.associateBy { it.name } }

        /** Memoized [clip] results, keyed by "text|px|maxW" — text truncation is a measure loop. */
        val clipCache = HashMap<String, String>()
    }

    /** One card: a titled group of modules. */
    private class CardGroup(val title: String, val modules: List<ClientModule>)

    private class ColorLerp(var from: Color4b, var to: Color4b, var startMs: Long)

    /**
     * Knob colour for a track painted in [track]: white on a dark accent, near-black on a light
     * one. Without this a white accent gave a white track under a white knob — the switch read
     * as one solid blob and looked "not coloured at all".
     */
    private fun knobOn(track: Color4b): Color4b {
        val luma = (track.r * 0.299f + track.g * 0.587f + track.b * 0.114f) / 255f
        return if (luma > 0.6f) Color4b(24, 24, 28, 255) else Theme.title
    }

    // - Per-frame interaction state -
    private class Hit(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val layer: Int,
        val onClick: (button: Int) -> Boolean,
    ) {
        var clipTop: Float = -Float.MAX_VALUE
        var clipBottom: Float = Float.MAX_VALUE

        fun contains(mx: Float, my: Float) =
            mx >= x0 && mx <= x1 && my >= y0 && my <= y1 && my >= clipTop && my <= clipBottom
    }

    private class SliderDrag(val trackX: Float, val trackW: Float, val apply: (frac: Float) -> Unit) {
        var lastFrac = Float.NaN

        fun update(frac: Float) {
            if (frac == lastFrac) return
            lastFrac = frac
            apply(frac)
        }
    }

    /** Generic pointer capture (the SV area): receives virtual mouse coords while held. */
    private class PointerDrag(val apply: (x: Float, y: Float) -> Unit) {
        var lastX = Float.NaN
        var lastY = Float.NaN

        fun update(x: Float, y: Float) {
            if (x == lastX && y == lastY) return
            lastX = x
            lastY = y
            apply(x, y)
        }
    }

    private val hits = ArrayList<Hit>(256)
    private var activeSlider: SliderDrag? = null
    private var pointerDrag: PointerDrag? = null
    private var pendingBind: Value<InputBind>? = null
    private var focusedField: Value<*>? = null
    private var editBuffer = ""

    /** Layer of the hits being recorded: 0 = page, 1 = popup, 2 = popover. Topmost wins. */
    private var hitLayer = 0

    private var viewTop = 0f
    private var viewBottom = 0f
    private var contentHeight = 0f
    private var popupContentH = 0f

    /**
     * Left edge of the settings panel itself.
     *
     * Rows are indented by nesting depth, so `x` inside a row is NOT the panel's edge. Deriving
     * the right-aligned control position from it pushed toggles and pills out through the panel's
     * right border once a setting sat two groups deep (reported 2026-09-20). Everything
     * right-aligned measures from here instead.
     */
    private var popupBaseX = 0f

    /**
     * Width of the surface the current rows belong to: the settings panel, or an Interface-pane
     * card. Right-aligned controls measure from `popupBaseX + popupWidth`, so both surfaces can
     * host the very same walker.
     */
    private var popupWidth = POPUP_W

    /** Rect of the settings panel as drawn this frame — the authority for its hit tests. */
    private var popupRectX = 0f
    private var popupRectY = 0f
    private var popupRectH = 0f

    private val openedAt = System.currentTimeMillis()
    private var closingAt = 0L
    private var lastFrameMs = 0L

    private var curScale = 1f
    private var curTx = 0f
    private var curTy = 0f

    /**
     * The scale WITHOUT the open/close pop, i.e. the one the GUI rests at.
     *
     * Font atlases are rasterized per target pixel size ([WatermarkFont.submit] keys them on
     * `round(px * density)`), so feeding the animated [curScale] into the density rebuilt every
     * atlas on every frame of the open animation and of a scale change — the flicker reported
     * on 2026-09-20. Text is therefore always measured and drawn at the SETTLED density; the
     * 4 % pop only scales the quads, which costs nothing.
     */
    private var settledScale = 1f

    // - Transform helpers -

    private fun toVirtualX(mx: Float): Float = (mx - curTx) / curScale

    private fun toVirtualY(my: Float): Float = (my - curTy) / curScale

    private fun toRealX(vx: Float): Float = vx * curScale + curTx

    private fun toRealY(vy: Float): Float = vy * curScale + curTy

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val inv = -2f * x + 2f
            1f - inv * inv * inv / 2f
        }
    }

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        return Color4b(ch(a.r, b.r), ch(a.g, b.g), ch(a.b, b.b), ch(a.a, b.a))
    }

    private fun Color4b.alphaMul(f: Float): Color4b = with(a = (a * f).roundToInt().coerceIn(0, 255))

    private fun watchColor(key: String, target: Color4b): Color4b {
        val now = System.currentTimeMillis()
        val lerp = colorLerps.getOrPut(key) { ColorLerp(target, target, now) }
        var shown = lerpColor(lerp.from, lerp.to, easeInOutCubic((now - lerp.startMs) / COLOR_LERP_MS))
        if (lerp.to.argb != target.argb) {
            lerp.from = shown
            lerp.to = target
            lerp.startMs = now
            shown = lerp.from
        }
        return shown
    }

    /**
     * A translated string, falling back to [fallback] when the key carries no translation.
     *
     * Resolved through [LanguageManager], NOT `Component.translatable`: the client's translations
     * live in its own registry, driven by the ClientLanguage setting, and vanilla's `Language`
     * has never heard of them - it hands the key straight back. That is why the sidebar stayed
     * English with the client set to Russian: every key missed and every caption took its
     * hard-coded fallback.
     *
     * [ClientLanguage.getOrDefault] already falls back to en_us before the fallback here.
     */
    private fun tr(key: String, fallback: String): String =
        LanguageManager.getLanguage()?.getOrDefault(key, fallback) ?: fallback

    /** Display name of a category in the client's language. */
    private fun categoryName(category: ModuleCategory): String =
        tr("liquidbounce.mursultan.category." + category.tag.lowercase(), category.tag)

    private fun moveToward(cur: Float, target: Float, step: Float): Float = when {
        cur < target -> (cur + step).coerceAtMost(target)
        cur > target -> (cur - step).coerceAtLeast(target)
        else -> cur
    }

    /** The accent, resolved once per use so a change in the popover applies immediately. */
    private val accent: Color4b
        get() {
            val argb = MursultanClickGuiSettings.accent
            return Color4b(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb), 255)
        }

    // - Fonts -
    private val dpr: Float get() = mc.window.guiScale.toFloat()

    private val density: Float get() = dpr * settledScale

    private fun textWidth(font: WatermarkFont, text: String, px: Float): Float = font.width(text, px, density)

    private fun lineHeight(font: WatermarkFont, px: Float): Float = font.lineHeight(px, density)

    private fun drawText(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, y: Float, px: Float, color: Color4b,
    ) {
        val argb = color.argb
        font.submit(ctx, text, x, y, px, density) { argb }
    }

    /** Draws [text] centred vertically in a row of [h] starting at [y]. */
    private fun drawTextVCentered(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, y: Float, h: Float, px: Float, color: Color4b,
    ) = drawText(ctx, font, text, x, y + (h - lineHeight(font, px)) / 2f, px, color)

    /**
     * Draws an icon baked to the exact physical size it covers ([MursultanIcons]).
     *
     * The masters are 64 px; at this GUI's scale an icon covers ~16 device pixels, and letting a
     * LINEAR sampler minify that far is what made the icons look soft.
     */
    private fun drawIcon(
        ctx: GuiGraphicsExtractor, name: String, cx: Float, cy: Float,
        w: Float, h: Float, color: Color4b,
    ) {
        val deviceW = (w * curScale * dpr).roundToInt()
        val deviceH = (h * curScale * dpr).roundToInt()
        val setup = MursultanIcons.setup(name, deviceW, deviceH) ?: return
        ctx.drawTexQuad(setup, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, argb = color.argb)
    }

    private fun drawIcon(ctx: GuiGraphicsExtractor, name: String, cx: Float, cy: Float, size: Float, color: Color4b) =
        drawIcon(ctx, name, cx, cy, size, size, color)

    /**
     * Cuts [text] down to what fits into [maxW] (no ellipsis — the original just clips).
     *
     * Binary search over the length, memoized in [clipCache]: the first cut measured the string
     * once per dropped character, every frame, for every truncated label on screen.
     */
    private fun clip(font: WatermarkFont, text: String, px: Float, maxW: Float): String {
        if (text.isEmpty() || textWidth(font, text, px) <= maxW) return text
        val key = "$text|$px|${maxW.roundToInt()}|${System.identityHashCode(font)}|${density.roundToInt()}"
        clipCache[key]?.let { return it }
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (textWidth(font, text.substring(0, mid), px) <= maxW) lo = mid else hi = mid - 1
        }
        val out = text.substring(0, lo)
        if (clipCache.size > 512) clipCache.clear()
        clipCache[key] = out
        return out
    }

    // - Lifecycle -

    val isClosing: Boolean get() = closingAt != 0L

    private fun startClose() {
        if (closingAt == 0L) {
            closingAt = System.currentTimeMillis()
            activeSlider = null
            pointerDrag = null
            commitEdit()
            MursultanClickGuiSettings.flushDirty()
        }
    }

    override fun tick() {
        if (closingAt != 0L && System.currentTimeMillis() - closingAt >= CLOSE_MS.toLong()) {
            super.onClose()
        }
    }

    override fun onClose() = startClose()

    private var prunedOnOpen = false

    override fun init() {
        super.init()
        if (!prunedOnOpen) {
            prunedOnOpen = true
            pruneStaleState()
            searchFocused = ModuleClickGui.inner
                .firstOrNull { it.name == "SearchBarAutoFocus" }
                ?.let { it.get() as? Boolean } ?: true
        }
    }

    private fun pruneStaleState() {
        val names = ModuleManager.mapTo(HashSet()) { it.name }
        if (popupModule !in names) popupModule = null
        colorLerps.keys.retainAll { it.substringAfterLast(':') in names || !it.contains(':') }
        openDropdowns.clear()
        openPickers.clear()
        dropdownProgress.clear()
        pickerProgress.clear()
        popoverOpen = false
    }

    override fun isPauseScreen(): Boolean = false

    // - Frame -

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        hits.clear()
        hitLayer = 0

        val now = System.currentTimeMillis()
        val dtSec = (if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 50L)) / 1000f
        lastFrameMs = now
        advanceAnimations(dtSec)

        val openT = ((now - openedAt) / OPEN_MS).coerceIn(0f, 1f)
        val closeT = if (closingAt == 0L) 0f else ((now - closingAt) / CLOSE_MS).coerceIn(0f, 1f)
        val motion = easeInOutCubic(openT) * (1f - easeInOutCubic(closeT))

        // Page: the original dims the world heavily behind the plate.
        ctx.fill(0, 0, width, height, ARGB.color((196 * motion).toInt().coerceIn(0, 255), 6, 6, 7))

        // Screen-fit scale: the window keeps the original's share of the viewport, so every
        // design unit below is resolution independent.
        val fit = minOf(width * FIT_W / DESIGN_W, height * FIT_H / DESIGN_H)
        settledScale = (fit * MursultanClickGuiSettings.scale).coerceAtLeast(0.02f)
        val pop = 0.96f + 0.04f * motion
        curScale = settledScale * pop

        val slide = (1f - motion) * 14f
        val rawTx = (width - DESIGN_W * curScale) / 2f
        val rawTy = (height - DESIGN_H * curScale) / 2f + slide
        val atRest = motion >= 0.999f
        val d = dpr
        curTx = if (atRest && d > 0f) Math.round(rawTx * d) / d else rawTx
        curTy = if (atRest && d > 0f) Math.round(rawTy * d) / d else rawTy

        val vx = toVirtualX(mouseX.toFloat())
        val vy = toVirtualY(mouseY.toFloat())

        val leftDown = GLFW.glfwGetMouseButton(mc.window.handle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS
        if (!leftDown) {
            if (activeSlider != null) {
                activeSlider = null
                MursultanClickGuiSettings.flushDirty()
            }
            pointerDrag = null
        } else {
            activeSlider?.let { it.update(((vx - it.trackX) / it.trackW).coerceIn(0f, 1f)) }
            pointerDrag?.update(vx, vy)
        }

        ctx.pose().pushMatrix()
        ctx.pose().translate(curTx, curTy)
        ctx.pose().scale(curScale, curScale)
        try {
            drawGui(ctx, vx, vy, motion)
        } finally {
            ctx.pose().popMatrix()
        }
    }

    private fun advanceAnimations(dtSec: Float) {
        // smooth scroll with a device-pixel snap at rest
        val settle = 1f - exp(-dtSec / SCROLL_TAU)
        scrollY += (scrollTarget - scrollY) * settle
        if (abs(scrollTarget - scrollY) < 0.05f) scrollY = scrollTarget

        // the settings panel scrolls with the same easing as the card list - it used to jump
        // straight to the wheel position, which read as a stutter
        popupScroll += (popupScrollTarget - popupScroll) * settle
        if (abs(popupScrollTarget - popupScroll) < 0.05f) popupScroll = popupScrollTarget

        advanceSetProgress(dropdownProgress, openDropdowns, dtSec, REVEAL_SEC)
        advanceSetProgress(pickerProgress, openPickers, dtSec, REVEAL_SEC)

        // Toggle knobs. Entries are seeded at their target by the row that first draws them,
        // so this walks only what is actually on screen instead of every module in the client.
        for (entry in knobProgress.entries) {
            val target = knobTargets[entry.key] ?: continue
            val next = moveToward(entry.value, target, dtSec / KNOB_SEC)
            if (next != entry.value) entry.setValue(next)
        }

        accentPickerT = moveToward(accentPickerT, if (accentPickerOpen) 1f else 0f, dtSec / REVEAL_SEC)
        popupT = moveToward(popupT, if (popupModule != null) 1f else 0f, dtSec / REVEAL_SEC)
        popoverT = moveToward(popoverT, if (popoverOpen) 1f else 0f, dtSec / REVEAL_SEC)
    }

    private fun <K> advanceSetProgress(
        map: MutableMap<K, Float>, activeKeys: Set<K>, dtSec: Float, durSec: Float,
    ) {
        for (key in activeKeys) map.putIfAbsent(key, 0f)
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val next = moveToward(entry.value, if (entry.key in activeKeys) 1f else 0f, dtSec / durSec)
            if (entry.key !in activeKeys && next <= 0f) {
                iterator.remove()
            } else if (next != entry.value) {
                entry.setValue(next)
            }
        }
    }

    /**
     * Knob position 0..1 of the toggle keyed by [key]. Seeds the entry AT its target the first
     * time it is drawn (so nothing animates on open) and registers the target for the once-per-
     * frame advance — the progress map itself is never written here.
     */
    private fun knobOf(key: String, on: Boolean): Float {
        val target = if (on) 1f else 0f
        knobTargets[key] = target
        return easeInOutCubic(knobProgress.getOrPut(key) { target })
    }

    private fun drawGui(ctx: GuiGraphicsExtractor, mx: Float, my: Float, motion: Float) {
        val fade = motion

        // Window plate. No inner fills anywhere: structure is carried by outlines only.
        ctx.drawRoundedRect(
            0f, 0f, DESIGN_W, DESIGN_H, WINDOW_RADIUS,
            Theme.page.alphaMul(fade), Theme.border.alphaMul(fade), 1f,
        )

        drawSidebar(ctx, mx, my, fade)
        drawHeader(ctx, mx, my, fade)
        drawContent(ctx, mx, my, fade)

        // Floating layers last, so their hits are recorded on top.
        drawSettingsPopup(ctx, mx, my)
        drawSettingsPopover(ctx, mx, my)
    }

    // - Sidebar -

    private fun drawSidebar(ctx: GuiGraphicsExtractor, mx: Float, my: Float, fade: Float) {
        val divider = Theme.divider.alphaMul(fade)

        // vertical divider (the sidebar's 270th unit) and the header divider, full width
        ctx.drawRoundedRect(SIDEBAR_W - 1f, 0f, SIDEBAR_W, DESIGN_H, 0f, divider, null)
        ctx.drawRoundedRect(0f, HEADER_H - 1f, DESIGN_W, HEADER_H, 0f, divider, null)

        // logo, centred in the header slot of the sidebar
        // The LB mark is square; drawing it into the original's 52x38 slot would squash it,
        // so it keeps its aspect and is fitted to the slot height.
        drawIcon(ctx, "mursultan/logo", SIDEBAR_W / 2f, HEADER_H / 2f, LOGO_H, LOGO_H, accent.alphaMul(fade))

        var y = HEADER_H + PAD
        y = drawNavCaption(ctx, tr("liquidbounce.mursultan.section.functions", "FUNCTIONS"), y, fade)
        for (category in ModuleCategories.entries) {
            y = drawNavItem(ctx, category, categoryName(category), iconFor(category), y, mx, my, fade)
        }
        y += PAD
        y = drawNavCaption(ctx, tr("liquidbounce.mursultan.section.management", "MANAGEMENT"), y, fade)
        drawNavItem(
            ctx, GUI_CATEGORY, tr("liquidbounce.mursultan.interface", "Interface"),
            "mursultan/client-settings", y, mx, my, fade,
        )

        drawSidebarFooter(ctx, fade)
    }

    private fun iconFor(category: ModuleCategory): String =
        CATEGORY_ICONS[category.tag.lowercase()] ?: "mursultan/misc"

    private fun drawNavCaption(ctx: GuiGraphicsExtractor, text: String, y: Float, fade: Float): Float {
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, text, PAD + 5f, y, CAPTION_H,
            FONT_CAPTION, Theme.label.alphaMul(fade),
        )
        return y + CAPTION_H
    }

    @Suppress("LongParameterList")
    private fun drawNavItem(
        ctx: GuiGraphicsExtractor, category: ModuleCategory, label: String, icon: String,
        y: Float, mx: Float, my: Float, fade: Float,
    ): Float {
        val x = PAD
        val w = SIDEBAR_W - PAD * 2f
        val active = category === selectedCategory && searchQuery.isBlank()
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + NAV_ITEM_H

        val fill = when {
            active -> Theme.navActive
            hovered -> Theme.hover
            else -> Theme.page.with(a = 0)
        }
        val outline = if (active) Theme.navActiveBorder else Theme.page.with(a = 0)
        val shownFill = watchColor("nav:" + category.tag, fill).alphaMul(fade)
        val shownOutline = watchColor("navb:" + category.tag, outline).alphaMul(fade)
        ctx.drawRoundedRect(x, y, x + w, y + NAV_ITEM_H, NAV_RADIUS, shownFill, shownOutline, 1f)

        val tint = watchColor("navfg:" + category.tag, if (active) accent else Theme.navIdle).alphaMul(fade)
        drawIcon(ctx, icon, x + NAV_ICON_INSET + NAV_ICON / 2f, y + NAV_ITEM_H / 2f, NAV_ICON, tint)
        drawTextVCentered(ctx, WatermarkFont.TEXT, label, x + NAV_TEXT_INSET, y, NAV_ITEM_H, FONT_ROW, tint)

        hits += Hit(x, y, x + w, y + NAV_ITEM_H, hitLayer) { button ->
            if (button == 0) {
                selectCategory(category)
                true
            } else {
                false
            }
        }
        return y + NAV_ITEM_H + NAV_ITEM_GAP
    }

    private fun selectCategory(category: ModuleCategory) {
        if (selectedCategory !== category || searchQuery.isNotBlank()) {
            selectedCategory = category
            searchQuery = ""
            scrollY = 0f
            scrollTarget = 0f
            popupModule = null
        }
    }

    private fun drawSidebarFooter(ctx: GuiGraphicsExtractor, fade: Float) {
        val top = DESIGN_H - FOOTER_H
        ctx.drawRoundedRect(0f, top, SIDEBAR_W - 1f, top + 1f, 0f, Theme.divider.alphaMul(fade), null)

        val name = mc.user?.name ?: "player"
        val nameH = lineHeight(WatermarkFont.BRAND, FONT_FOOTER)
        val subH = lineHeight(WatermarkFont.TEXT, FONT_CAPTION)
        val blockH = nameH + 4f + subH
        val y = top + (FOOTER_H - blockH) / 2f
        drawText(ctx, WatermarkFont.BRAND, name, PAD + 25f, y, FONT_FOOTER, Theme.title.alphaMul(fade))
        drawText(
            ctx, WatermarkFont.TEXT, LiquidBounce.clientVersion,
            PAD + 25f, y + nameH + 4f, FONT_CAPTION, Theme.label.alphaMul(fade),
        )
    }

    // - Header -

    private fun drawHeader(ctx: GuiGraphicsExtractor, mx: Float, my: Float, fade: Float) {
        val x = SIDEBAR_W + PAD
        val burgerX = DESIGN_W - PAD - SEARCH_H
        val searchW = burgerX - BURGER_GAP - x
        val y = (HEADER_H - 1f - SEARCH_H) / 2f

        // search field
        ctx.drawRoundedRect(
            x, y, x + searchW, y + SEARCH_H, SEARCH_RADIUS,
            Theme.page.with(a = 0), Theme.border.alphaMul(fade), 1f,
        )
        drawIcon(ctx, "mursultan/search", x + 15f + 7f, y + SEARCH_H / 2f, 15f, Theme.placeholder.alphaMul(fade))
        val textX = x + 15f + 18f
        val shown = searchQuery.ifEmpty {
            if (searchFocused) "" else tr("liquidbounce.mursultan.search", "Search")
        }
        val color = if (searchQuery.isEmpty()) Theme.placeholder else Theme.textOn
        val caret = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, shown + caret, textX, y, SEARCH_H, FONT_ROW, color.alphaMul(fade),
        )
        hits += Hit(x, y, x + searchW, y + SEARCH_H, hitLayer) { button ->
            when (button) {
                0 -> { searchFocused = true; commitEdit(); true }
                1 -> { searchQuery = ""; scrollTarget = 0f; true }
                else -> false
            }
        }

        // burger: the GUI's own settings
        val hovered = mx >= burgerX && mx <= burgerX + SEARCH_H && my >= y && my <= y + SEARCH_H
        ctx.drawRoundedRect(
            burgerX, y, burgerX + SEARCH_H, y + SEARCH_H, SEARCH_RADIUS,
            (if (hovered || popoverOpen) Theme.hover else Theme.page.with(a = 0)).alphaMul(fade),
            Theme.border.alphaMul(fade), 1f,
        )
        drawIcon(
            ctx, "mursultan/client-settings", burgerX + SEARCH_H / 2f, y + SEARCH_H / 2f, 18f,
            (if (popoverOpen) accent else Theme.navIdle).alphaMul(fade),
        )
        hits += Hit(burgerX, y, burgerX + SEARCH_H, y + SEARCH_H, hitLayer) { button ->
            if (button == 0) {
                popoverOpen = !popoverOpen
                popupModule = null
                true
            } else {
                false
            }
        }
    }

    // - Content -

    /**
     * LiquidBounce has no sub-categories, so cards are derived from the package leaf a module
     * lives in: `modules/combat/totem/ModuleAutoTotem` -> "Totem", `modules/combat/...` ->
     * the category name. See MURSULTAN_STYLE.md section 7.1. Cached — see [groupCache].
     */
    private fun groupsFor(category: ModuleCategory): List<CardGroup> =
        groupCache.getOrPut(category.tag) { buildGroups(category) }

    private fun buildGroups(category: ModuleCategory): List<CardGroup> {
        val modules = ModuleManager.filter { it.category === category }
        val categoryKey = category.tag.lowercase()
        val buckets = LinkedHashMap<String, MutableList<ClientModule>>()
        for (module in modules.sortedBy { it.name }) {
            val pkg = module.javaClass.name.substringBeforeLast('.').substringAfterLast(".modules.")
            val leaf = pkg.removePrefix(categoryKey).removePrefix(".").substringBefore('.')
            buckets.getOrPut(if (leaf.isBlank()) categoryKey else leaf) { ArrayList() } += module
        }
        // the category's own bucket first, the rest alphabetically
        return buckets.entries
            .sortedWith(compareBy({ if (it.key == categoryKey) 0 else 1 }, { it.key }))
            .map { CardGroup(prettify(it.key), it.value) }
    }

    private fun prettify(key: String): String = key
        .split('-', '_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private fun searchGroups(): List<CardGroup> {
        val query = searchQuery.trim().lowercase()
        return searchCache.getOrPut(query) {
            val found = ModuleManager
                .filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name }
            if (found.isEmpty()) emptyList() else listOf(CardGroup("Search", found))
        }
    }

    private fun cardHeight(group: CardGroup): Float = CARD_HEAD_H + group.modules.size * ROW_H

    private fun drawContent(ctx: GuiGraphicsExtractor, mx: Float, my: Float, fade: Float) {
        val x0 = SIDEBAR_W + PAD
        val contentW = DESIGN_W - SIDEBAR_W - PAD * 2f
        val colW = (contentW - COL_GAP) / 2f
        viewTop = HEADER_H
        viewBottom = DESIGN_H

        if (selectedCategory === GUI_CATEGORY && searchQuery.isBlank()) {
            drawInterfacePane(ctx, x0, contentW, mx, my, fade)
            return
        }

        val groups = if (searchQuery.isBlank()) groupsFor(selectedCategory) else searchGroups()

        // two-column masonry: each card goes to the currently shorter column
        var leftY = viewTop + PAD
        var rightY = viewTop + PAD
        val placed = ArrayList<Triple<CardGroup, Float, Float>>(groups.size)
        for (group in groups) {
            val h = cardHeight(group)
            if (leftY <= rightY) {
                placed += Triple(group, x0, leftY)
                leftY += h + COL_GAP
            } else {
                placed += Triple(group, x0 + colW + COL_GAP, rightY)
                rightY += h + COL_GAP
            }
        }
        contentHeight = maxOf(leftY, rightY) - (viewTop + PAD)

        val maxScroll = (contentHeight - (viewBottom - viewTop - PAD * 2f)).coerceAtLeast(0f)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollY = scrollY.coerceIn(0f, maxScroll)

        val clip = realRect(x0 - PAD, viewTop, DESIGN_W, viewBottom)
        ctx.scissorStack.push(clip)
        try {
            for ((group, cx, cy) in placed) {
                drawCard(ctx, group, cx, cy - scrollY, colW, mx, my, fade)
            }
        } finally {
            ctx.scissorStack.pop()
        }
    }

    private fun realRect(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle {
        val rx = floor(toRealX(x0).toDouble()).toInt()
        val ry = floor(toRealY(y0).toDouble()).toInt()
        return ScreenRectangle(
            rx, ry,
            (ceil(toRealX(x1).toDouble()).toInt() - rx).coerceAtLeast(1),
            (ceil(toRealY(y1).toDouble()).toInt() - ry).coerceAtLeast(1),
        )
    }

    @Suppress("LongParameterList")
    private fun drawCard(
        ctx: GuiGraphicsExtractor, group: CardGroup,
        x: Float, y: Float, w: Float, mx: Float, my: Float, fade: Float,
    ) {
        val h = cardHeight(group)
        if (y + h < viewTop || y > viewBottom) return

        ctx.drawRoundedRect(x, y, x + w, y + h, CARD_RADIUS, Theme.page.with(a = 0), Theme.border.alphaMul(fade), 1f)

        drawTextVCentered(
            ctx, WatermarkFont.BRAND, group.title, x + PAD, y, CARD_HEAD_H,
            FONT_CARD_TITLE, Theme.title.alphaMul(fade),
        )
        drawIcon(
            ctx, "mursultan/squeeze", x + w - PAD - 8f, y + CARD_HEAD_H / 2f, 16f, 23f,
            Theme.title.alphaMul(fade),
        )

        // header divider, full card width (unlike the popup's, which is inset)
        ctx.drawRoundedRect(x, y + CARD_HEAD_H, x + w, y + CARD_HEAD_H + 1f, 0f, Theme.divider.alphaMul(fade), null)

        var ry = y + CARD_HEAD_H + 1f
        for ((index, module) in group.modules.withIndex()) {
            drawModuleRow(ctx, module, x, ry, w, mx, my, fade)
            ry += ROW_H
            // The original draws no separator under the FIRST row of a list. Verified in
            // three cards and in the settings popup — see MURSULTAN_STYLE.md section 5.
            if (index in 1 until group.modules.size - 1) {
                ctx.drawRoundedRect(x, ry, x + w, ry + 1f, 0f, Theme.divider.alphaMul(fade), null)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun drawModuleRow(
        ctx: GuiGraphicsExtractor, module: ClientModule,
        x: Float, y: Float, w: Float, mx: Float, my: Float, fade: Float,
    ) {
        val visible = y + ROW_H >= viewTop && y <= viewBottom
        val textColor = watchColor(
            "row:" + module.name,
            if (module.enabled) Theme.textOn else Theme.textOff,
        ).alphaMul(fade)

        val toggleX = x + w - PAD - TOGGLE_W
        val dotsX = toggleX - DOTS_GAP - DOTS_W

        if (visible) {
            drawTextVCentered(ctx, WatermarkFont.TEXT, module.name, x + PAD, y, ROW_H, FONT_ROW, textColor)
            drawIcon(ctx, "mursultan/dots", dotsX + DOTS_W / 2f, y + ROW_H / 2f, DOTS_W, 6f, textColor)
            drawToggle(ctx, toggleX, y + (ROW_H - TOGGLE_H) / 2f, module.enabled, knobOf(module.name, module.enabled), fade)
        }

        // Off-screen rows register nothing: a long category would otherwise allocate two Hits
        // per module per frame just to have them clipped away.
        if (!visible) return

        // dots first: a more specific area must be tested before the row body
        hits += Hit(dotsX, y, dotsX + DOTS_W, y + ROW_H, hitLayer) { button ->
            if (button == 0) {
                openPopup(module, y)
                true
            } else {
                false
            }
        }.also { it.clipTop = viewTop; it.clipBottom = viewBottom }

        hits += Hit(x, y, x + w, y + ROW_H, hitLayer) { button ->
            when (button) {
                0 -> { module.enabled = !module.enabled; true }
                1 -> { openPopup(module, y); true }
                2 -> {
                    pendingBind = if (pendingBind === module.bindValue) null else module.bindValue
                    searchFocused = false
                    commitEdit()
                    true
                }
                else -> false
            }
        }.also { it.clipTop = viewTop; it.clipBottom = viewBottom }
    }

    /**
     * Opens the settings panel for [module]. It docks to the RIGHT of the window, in the empty
     * margin beside it, instead of covering the cards — reported 2026-09-20 ("неудобная,
     * и её хуй уберёшь с экрана"): overlapping the list made it unreadable and left no obvious
     * way out. [anchorY] only decides the vertical alignment.
     */
    private fun openPopup(module: ClientModule, anchorY: Float) {
        if (popupModule == module.name) {
            popupModule = null
            return
        }
        popupModule = module.name
        popupScroll = 0f
        popupScrollTarget = 0f
        popoverOpen = false
        openDropdowns.clear()
        openPickers.clear()
        popupX = DESIGN_W + POPUP_DOCK_GAP
        popupY = anchorY
    }

    /**
     * The pill toggle: accent track when on, neutral when off, and a **round** knob that slides
     * between the two. Both the track and the knob lerp with [t], so a toggle never snaps.
     */
    private fun drawToggle(ctx: GuiGraphicsExtractor, x: Float, y: Float, on: Boolean, t: Float, fade: Float) {
        val radius = TOGGLE_H / 2f
        val accentColor = accent
        val track = lerpColor(Theme.trackOff, accentColor, t).alphaMul(fade)
        ctx.drawRoundedRect(x, y, x + TOGGLE_W, y + TOGGLE_H, radius, track, null)

        // circle, not a stadium: diameter is the track height minus the inset on both sides
        val knobD = TOGGLE_H - TOGGLE_KNOB_INSET * 2f
        val travel = TOGGLE_W - TOGGLE_KNOB_INSET * 2f - knobD
        val kx = x + TOGGLE_KNOB_INSET + travel * t
        val ky = y + TOGGLE_KNOB_INSET
        val knobColor = lerpColor(Theme.knobOff, knobOn(accentColor), t).alphaMul(fade)
        ctx.drawRoundedRect(kx, ky, kx + knobD, ky + knobD, knobD / 2f, knobColor, null)
    }

    // - Interface pane (the virtual "gui" category) -

    /**
     * The virtual "Interface" pane: the GUI's own look, plus every client-wide setting tree,
     * laid out as a two-column masonry of cards.
     *
     * None of it is re-implemented. Each card is a group from [GlobalManager] (language,
     * commands, target, blink, hand-swap, auto-translate, browser, chat, rich presence) or a
     * module, walked by the same [layoutSettings] the module panel uses — so anything those
     * trees grow shows up here without touching this file.
     */
    private fun drawInterfacePane(
        ctx: GuiGraphicsExtractor, x: Float, w: Float, mx: Float, my: Float, fade: Float,
    ) {
        viewTop = HEADER_H
        viewBottom = DESIGN_H
        val colW = (w - COL_GAP) / 2f

        val clip = realRect(x - PAD, viewTop, DESIGN_W, viewBottom)
        ctx.scissorStack.push(clip)
        try {
            var leftY = viewTop + PAD - scrollY
            var rightY = viewTop + PAD - scrollY
            val startY = viewTop + PAD - scrollY

            for (card in paneCards()) {
                val toLeft = leftY <= rightY
                val cx = if (toLeft) x else x + colW + COL_GAP
                val cy = if (toLeft) leftY else rightY
                val end = drawPaneCard(ctx, card.title, cx, cy, colW, fade, card.toggle) { bodyY ->
                    popupBaseX = cx
                    popupWidth = colW
                    card.body(ctx, bodyY, cx, colW, mx, my, fade)
                }
                if (toLeft) leftY = end + COL_GAP else rightY = end + COL_GAP
            }

            contentHeight = maxOf(leftY, rightY) - startY
        } finally {
            ctx.scissorStack.pop()
        }

        val maxScroll = (contentHeight - (viewBottom - viewTop - PAD * 2f)).coerceAtLeast(0f)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    /** One card of the Interface pane; [body] draws its rows and returns the y below them. */
    private class PaneCard(
        val title: String,
        /** The group's own master switch, shown in the card header when it has one. */
        val toggle: ToggleableValueGroup? = null,
        val body: (ctx: GuiGraphicsExtractor, y: Float, x: Float, w: Float, mx: Float, my: Float, fade: Float) -> Float,
    )

    private fun paneCards(): List<PaneCard> {
        val cards = ArrayList<PaneCard>(12)
        cards += PaneCard(tr("liquidbounce.mursultan.interface", "Interface")) { c, y, cx, cw, mx, my, fade ->
            var ry = drawAccentRow(c, cx, y, cw, mx, my, fade)
            ry = drawAccentPicker(c, cx, ry, cw, mx, my)
            val label = tr("liquidbounce.mursultan.descriptions", "Descriptions")
            drawSwitchRow(c, label, MursultanClickGuiSettings.descriptions, "desc", cx, ry, cw, fade) {
                MursultanClickGuiSettings.descriptions = !MursultanClickGuiSettings.descriptions
            }
        }
        cards += PaneCard("ClickGUI") { c, y, cx, _, mx, my, _ ->
            layoutSettings(c, ModuleClickGui, cx, y, mx, my)
        }
        cards += PaneCard("HUD", ModuleHud) { c, y, cx, _, mx, my, _ ->
            layoutSettings(c, ModuleHud, cx, y, mx, my)
        }
        for (group in GlobalManager.inner) {
            if (group !is ValueGroup || !hasVisibleSettings(group)) continue
            cards += PaneCard(prettify(group.name), group as? ToggleableValueGroup) { c, y, cx, _, mx, my, _ ->
                layoutSettings(c, group, cx, y, mx, my)
            }
        }
        return cards
    }

    /**
     * A titled card in the Interface pane; [body] lays its rows out and returns the y below them.
     *
     * When the card is backed by a [ToggleableValueGroup], its **own** switch goes in the header:
     * the settings walker deliberately skips the `Enabled` value of a group it is descending
     * into, so without this there was no way to turn Rich Presence, Blink, AutoTranslate, Chat
     * or the HUD on and off from this pane at all.
     */
    private fun drawPaneCard(
        ctx: GuiGraphicsExtractor, title: String, x: Float, y: Float, w: Float, fade: Float,
        toggle: ToggleableValueGroup? = null,
        body: (Float) -> Float,
    ): Float {
        val headEnd = y + CARD_HEAD_H + 1f
        val bodyEnd = body(headEnd)
        val h = bodyEnd - y + PAD
        ctx.drawRoundedRect(x, y, x + w, y + h, CARD_RADIUS, Theme.page.with(a = 0), Theme.border.alphaMul(fade), 1f)
        drawTextVCentered(
            ctx, WatermarkFont.BRAND, title, x + PAD, y, CARD_HEAD_H, FONT_CARD_TITLE, Theme.title.alphaMul(fade),
        )
        if (toggle != null) {
            val key = "card:" + System.identityHashCode(toggle)
            val tx = x + w - PAD - TOGGLE_W
            val ty = y + (CARD_HEAD_H - TOGGLE_H) / 2f
            drawToggle(ctx, tx, ty, toggle.enabled, knobOf(key, toggle.enabled), fade)
            hits += Hit(tx - 6f, y, x + w, y + CARD_HEAD_H, hitLayer) { button ->
                if (button == 0) { toggle.enabled = !toggle.enabled; true } else false
            }
        }
        ctx.drawRoundedRect(x, y + CARD_HEAD_H, x + w, y + CARD_HEAD_H + 1f, 0f, Theme.divider.alphaMul(fade), null)
        return y + h
    }

    @Suppress("LongParameterList")
    private fun drawSwitchRow(
        ctx: GuiGraphicsExtractor, label: String, on: Boolean, key: String,
        x: Float, y: Float, w: Float, fade: Float, onClick: () -> Unit,
    ): Float {
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, label, x + PAD, y, POPUP_ROW_H, FONT_ROW,
            (if (on) Theme.textOn else Theme.textOff).alphaMul(fade),
        )
        val tx = x + w - PAD - TOGGLE_W
        drawToggle(ctx, tx, y + (POPUP_ROW_H - TOGGLE_H) / 2f, on, knobOf(key, on), fade)
        hits += Hit(x, y, x + w, y + POPUP_ROW_H, hitLayer) { button ->
            if (button == 0) { onClick(); true } else false
        }
        return y + POPUP_ROW_H
    }

    @Suppress("LongParameterList")
    private fun drawAccentRow(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, mx: Float, my: Float, fade: Float,
    ): Float {
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, tr("liquidbounce.mursultan.accent", "Accent"), x + PAD, y, POPUP_ROW_H,
            FONT_ROW, Theme.textOff.alphaMul(fade),
        )
        val swatches = MursultanClickGuiSettings.ACCENTS
        val size = 18f
        val gap = 10f
        var sx = x + w - PAD - (swatches.size * size + (swatches.size - 1) * gap)
        for (argb in swatches) {
            val color = Color4b(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb), 255)
            val selected = argb == MursultanClickGuiSettings.accent
            val cy = y + POPUP_ROW_H / 2f
            ctx.drawRoundedRect(
                sx, cy - size / 2f, sx + size, cy + size / 2f, size / 2f,
                color.alphaMul(fade),
                (if (selected) Theme.title else Theme.border).alphaMul(fade), if (selected) 2f else 1f,
            )
            val hx = sx
            hits += Hit(hx, cy - size / 2f, hx + size, cy + size / 2f, hitLayer) { button ->
                if (button == 0) { MursultanClickGuiSettings.accent = argb; true } else false
            }
            sx += size + gap
        }

        // custom colour: the eyedropper opens a full picker under the row
        val ecx = x + w - PAD - size / 2f
        val ecy = y + POPUP_ROW_H / 2f
        val open = accentPickerOpen
        drawIcon(ctx, "mursultan/eye-dropper", ecx, ecy, 15f, (if (open) accent else Theme.textOff).alphaMul(fade))
        hits += Hit(ecx - size / 2f, ecy - size / 2f, ecx + size / 2f, ecy + size / 2f, hitLayer) { button ->
            if (button == 0) { accentPickerOpen = !accentPickerOpen; true } else false
        }
        return y + POPUP_ROW_H
    }

    /**
     * Accent picker: the same field and bars the colour settings use, driving the GUI's own
     * accent (a plain ARGB int rather than a `Value<Color4b>`, hence its own small wiring).
     */
    private fun drawAccentPicker(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val t = easeInOutCubic(accentPickerT)
        if (t <= 0.001f) return y
        val full = PICK_SV_H + 10f + PICK_BAR_H + 14f
        val shown = full * t
        val bx = x + PAD
        val bw = w - PAD * 2f
        val clipRect = realRect(bx - 2f, y, bx + bw + 2f, y + shown)
        ctx.scissorStack.push(clipRect)
        try {
            val mark = hits.size
            val argb = MursultanClickGuiSettings.accent
            val color = Color4b(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb), 255)
            val hsb = java.awt.Color.RGBtoHSB(color.r, color.g, color.b, null)
            val hue = accentHue ?: hsb[0].also { accentHue = it }

            drawSvField(ctx, bx, y, bw, PICK_SV_H, hue)
            drawHandle(ctx, bx + bw * hsb[1], y + PICK_SV_H * (1f - hsb[2]), 6f, color)
            hits += Hit(bx, y, bx + bw, y + PICK_SV_H, hitLayer) { button ->
                if (button == 0) {
                    pointerDrag = PointerDrag { px, py ->
                        setAccentHsb(
                            hue,
                            ((px - bx) / bw).coerceIn(0f, 1f),
                            1f - ((py - y) / PICK_SV_H).coerceIn(0f, 1f),
                        )
                    }
                    pointerDrag?.update(mx, my)
                    true
                } else {
                    false
                }
            }

            val barY = y + PICK_SV_H + 10f
            ctx.drawSweepRoundedRect(bx, barY, bx + bw, barY + PICK_BAR_H, PICK_BAR_H / 2f, columns = 32) { fraction ->
                Color4b.ofHSB(fraction.coerceAtMost(0.999f), 1f, 1f).argb
            }
            ctx.drawRoundedRect(bx, barY, bx + bw, barY + PICK_BAR_H, PICK_BAR_H / 2f, null, Theme.border, 1f)
            drawBarHandle(ctx, bx + bw * hue, barY, PICK_BAR_H, Color4b.ofHSB(hue, 1f, 1f))
            hits += Hit(bx, barY - 3f, bx + bw, barY + PICK_BAR_H + 3f, hitLayer) { button ->
                if (button == 0) {
                    pointerDrag = PointerDrag { px, _ ->
                        val current = MursultanClickGuiSettings.accent
                        val c = java.awt.Color.RGBtoHSB(ARGB.red(current), ARGB.green(current), ARGB.blue(current), null)
                        setAccentHsb(((px - bx) / bw).coerceIn(0f, 0.9999f), c[1], c[2])
                    }
                    pointerDrag?.update(mx, barY)
                    true
                } else {
                    false
                }
            }
            for (i in mark until hits.size) hits[i].clipBottom = y + shown
        } finally {
            ctx.scissorStack.pop()
        }
        return y + shown + 4f
    }

    private fun setAccentHsb(hue: Float, sat: Float, bri: Float) {
        accentHue = hue
        val rgb = Color4b.ofHSB(hue.coerceIn(0f, 0.9999f), sat.coerceIn(0f, 1f), bri.coerceIn(0f, 1f))
        MursultanClickGuiSettings.accent = ARGB.color(255, rgb.r, rgb.g, rgb.b)
    }


    /**
     * Scale as a grid of percent chips (50 % .. 200 %), like the original's `menu-scale` list.
     *
     * Deliberately NOT a slider: the font atlases are rasterized per target pixel size, so a
     * continuously changing scale rebuilds every one of them each frame — that is what made the
     * GUI flicker when this was a slider.
     */
    private fun drawScaleChips(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, mx: Float, my: Float, fade: Float,
    ): Float {
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, tr("liquidbounce.mursultan.scale", "SCALE"), x + PAD, y, CAPTION_H,
            FONT_CAPTION, Theme.label.alphaMul(fade),
        )
        var cy = y + CAPTION_H
        val perRow = chipsPerRow(w)
        val current = MursultanClickGuiSettings.scale
        for ((index, step) in MursultanClickGuiSettings.SCALE_STEPS.withIndex()) {
            val col = index % perRow
            if (col == 0 && index > 0) cy += CHIP_H + CHIP_GAP
            val cx = x + PAD + col * (CHIP_W + CHIP_GAP)
            val active = step == current
            val hovered = mx >= cx && mx <= cx + CHIP_W && my >= cy && my <= cy + CHIP_H
            val border = when {
                active -> accent
                hovered -> Theme.navActiveBorder
                else -> Theme.border
            }
            ctx.drawRoundedRect(
                cx, cy, cx + CHIP_W, cy + CHIP_H, 6f,
                (if (active) accent.with(a = 28) else Theme.chip).alphaMul(fade), border.alphaMul(fade), 1f,
            )
            val text = "${(step * 100).roundToInt()}%"
            val tw = textWidth(WatermarkFont.TEXT, text, FONT_CAPTION)
            drawTextVCentered(
                ctx, WatermarkFont.TEXT, text, cx + (CHIP_W - tw) / 2f, cy, CHIP_H, FONT_CAPTION,
                (if (active) accent else Theme.textOff).alphaMul(fade),
            )
            val hx = cx
            val hy = cy
            hits += Hit(hx, hy, hx + CHIP_W, hy + CHIP_H, hitLayer) { button ->
                if (button == 0) { MursultanClickGuiSettings.scale = step; true } else false
            }
        }
        return cy + CHIP_H + 10f
    }

    private fun chipsPerRow(w: Float): Int =
        (((w - PAD * 2f + CHIP_GAP) / (CHIP_W + CHIP_GAP)).toInt())
            .coerceIn(1, MursultanClickGuiSettings.SCALE_STEPS.size)

    /** Height [drawScaleChips] occupies for a panel of width [w] — used to size the popover. */
    private fun scaleChipsHeight(w: Float): Float {
        val perRow = chipsPerRow(w)
        val rows = (MursultanClickGuiSettings.SCALE_STEPS.size + perRow - 1) / perRow
        return CAPTION_H + rows * CHIP_H + (rows - 1) * CHIP_GAP + 10f
    }

    private fun drawSliderTrack(ctx: GuiGraphicsExtractor, x: Float, cy: Float, w: Float, frac: Float, fade: Float) {
        val h = 4f
        ctx.drawRoundedRect(x, cy - h / 2f, x + w, cy + h / 2f, h / 2f, Theme.trackOff.alphaMul(fade), null)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            ctx.drawRoundedRect(x, cy - h / 2f, x + w * f, cy + h / 2f, h / 2f, accent.alphaMul(fade), null)
        }
        val knob = 12f
        val kx = x + w * f
        ctx.drawRoundedRect(kx - knob / 2f, cy - knob / 2f, kx + knob / 2f, cy + knob / 2f, knob / 2f, accent.alphaMul(fade), null)
    }

    // - Settings popup -

    private fun drawSettingsPopup(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val name = popupModule ?: return
        val module = modulesByName[name] ?: run { popupModule = null; return }
        val reveal = easeInOutCubic(popupT)
        if (reveal <= 0.001f) return

        hitLayer = 1
        val bodyH = measurePopup(module)
        val fullH = (bodyH + PAD * 2f + POPUP_HEAD_H).coerceIn(POPUP_HEAD_H + PAD * 2f, DESIGN_H)

        // Dock side: the right margin by default, the left one when the right cannot hold the
        // panel (large user scale), and inside the window's right edge when neither can.
        val screenRight = (width - curTx) / curScale
        val screenLeft = -curTx / curScale
        val dockX = when {
            screenRight - DESIGN_W >= POPUP_W + POPUP_DOCK_GAP -> DESIGN_W + POPUP_DOCK_GAP
            screenLeft <= -(POPUP_W + POPUP_DOCK_GAP) -> -(POPUP_W + POPUP_DOCK_GAP)
            else -> DESIGN_W - PAD - POPUP_W
        }
        popupX = dockX
        val slideFrom = if (dockX < 0f) 26f else -26f

        // slide in from under the window's edge, fading as it comes
        val x = dockX + (1f - reveal) * slideFrom
        val y = (popupY - POPUP_HEAD_H).coerceIn(0f, (DESIGN_H - fullH).coerceAtLeast(0f))
        popupBaseX = x
        popupWidth = POPUP_W
        popupRectX = x
        popupRectY = y
        popupRectH = fullH

        val viewH = fullH - POPUP_HEAD_H - PAD * 2f
        val maxPopupScroll = (bodyH - viewH).coerceAtLeast(0f)
        popupScrollTarget = popupScrollTarget.coerceIn(0f, maxPopupScroll)
        popupScroll = popupScroll.coerceIn(0f, maxPopupScroll)

        ctx.drawRoundedRect(
            x, y, x + POPUP_W, y + fullH, CARD_RADIUS,
            Theme.popup.alphaMul(reveal), Theme.border.alphaMul(reveal), 1f,
        )

        // header: module name + a close button, so the panel is never a dead end
        drawTextVCentered(
            ctx, WatermarkFont.BRAND, clip(WatermarkFont.BRAND, module.name, FONT_POPUP_TITLE, POPUP_W - PAD * 2f - 28f),
            x + PAD, y, POPUP_HEAD_H, FONT_POPUP_TITLE, Theme.title.alphaMul(reveal),
        )
        val closeCx = x + POPUP_W - PAD - 7f
        val closeCy = y + POPUP_HEAD_H / 2f
        val closeHovered = abs(mx - closeCx) <= 12f && abs(my - closeCy) <= 12f
        drawIcon(
            ctx, "mursultan/xmark", closeCx, closeCy, 13f,
            (if (closeHovered) Theme.title else Theme.textOff).alphaMul(reveal),
        )
        hits += Hit(closeCx - 12f, closeCy - 12f, closeCx + 12f, closeCy + 12f, hitLayer) { button ->
            if (button == 0) { popupModule = null; true } else false
        }
        ctx.drawRoundedRect(
            x, y + POPUP_HEAD_H, x + POPUP_W, y + POPUP_HEAD_H + 1f, 0f, Theme.divider.alphaMul(reveal), null,
        )

        val bodyTop = y + POPUP_HEAD_H
        val clip = realRect(x, bodyTop, x + POPUP_W, y + fullH)
        ctx.scissorStack.push(clip)
        try {
            val mark = hits.size
            layoutPopupBody(ctx, module, x, bodyTop + PAD - popupScroll, mx, my)
            for (i in mark until hits.size) {
                hits[i].clipTop = bodyTop
                hits[i].clipBottom = y + fullH
            }
        } finally {
            ctx.scissorStack.pop()
        }
        hitLayer = 0
    }

    private fun measurePopup(module: ClientModule): Float =
        layoutSettings(null, module, popupX, 0f, 0f, 0f)

    private fun layoutPopupBody(
        ctx: GuiGraphicsExtractor?, module: ClientModule, x: Float, y: Float, mx: Float, my: Float,
    ) {
        var cy = y
        if (ctx != null && MursultanClickGuiSettings.descriptions) {
            // Through the client's own registry, or a description never resolves and the raw
            // "liquidbounce.module.elytraTarget.description" ends up on screen.
            val key = module.descriptionKey
            val text = if (key != null) tr(key, "") else ""
            if (text.isNotBlank()) {
                val lineH = lineHeight(WatermarkFont.TEXT, FONT_CAPTION)
                drawText(
                    ctx, WatermarkFont.TEXT,
                    clip(WatermarkFont.TEXT, text, FONT_CAPTION, POPUP_W - PAD * 2f),
                    x + PAD, cy, FONT_CAPTION, Theme.label,
                )
                cy += lineH + 10f
            }
        }
        layoutSettings(ctx, module, x, cy, mx, my)
    }

    private fun hasVisibleSettings(group: ValueGroup): Boolean =
        group.inner.any { it.name != "Enabled" && it.name != "Hidden" && !it.notAnOption }

    private fun layoutSettings(
        ctx: GuiGraphicsExtractor?, group: ValueGroup, x: Float, yStart: Float, mx: Float, my: Float,
        depth: Int = 0,
    ): Float {
        var y = yStart
        var index = 0
        for (value in group.inner) {
            if (value.name == "Enabled" || value.name == "Hidden" || value.notAnOption) continue
            val top = y
            y = layoutSetting(ctx, value, x, y, mx, my, depth)
            // Same rule as the cards: a separator sits below every row but the first, i.e.
            // ABOVE every row from the third on (index >= 2). See MURSULTAN_STYLE.md section 5.
            if (ctx != null && index >= 2 && depth == 0) {
                ctx.drawRoundedRect(popupBaseX + PAD, top, rowRight, top + 1f, 0f, Theme.divider, null)
            }
            index++
        }
        return y
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "UNCHECKED_CAST")
    private fun layoutSetting(
        ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, yStart: Float, mx: Float, my: Float, depth: Int,
    ): Float {
        val inset = depth * 14f
        var y = yStart
        when (value) {
            is ModeValueGroup<*> -> {
                val active = value.activeMode
                y = drawPillRow(
                    ctx, value.name, active.tag, value, x + inset, y,
                    items = value.modes.map { it.tag },
                    isActive = { it == active.tag },
                    onPick = { value.setByString(it); openDropdowns.remove(value) },
                    mx, my,
                )
                if (hasVisibleSettings(active)) {
                    y = layoutSettings(ctx, active, x, y, mx, my, depth + 1)
                }
            }

            is ToggleableValueGroup -> {
                y = drawToggleRow(ctx, value.name, value.enabled, value, x + inset, y) {
                    value.enabled = !value.enabled
                }
                if (value.enabled && hasVisibleSettings(value)) {
                    y = layoutSettings(ctx, value, x, y, mx, my, depth + 1)
                }
            }

            is BindValue -> {
                val bind = value.get()
                val capturing = pendingBind === value
                val text = if (capturing) "..." else bind.keyName
                y = drawBadgeRow(ctx, value.name, text, capturing, x + inset, y) {
                    pendingBind = if (capturing) null else value as Value<InputBind>
                    searchFocused = false
                }
            }

            is MultiChoiceListValue<*> -> {
                val selected = value.get()
                val tags = value.choices.map { it.tag }
                y = drawPillRow(
                    ctx, value.name, "${selected.size}/${tags.size}", value, x + inset, y,
                    items = tags,
                    isActive = { tag -> selected.any { it.tag == tag } },
                    // MUST go through toggle(): `setByString` runs the MULTI_CHOOSE deserializer,
                    // which hands back a plain List. The generic is erased, so that List lands in
                    // a value typed as a Set and every later `as EnumSet` blows up — it crashed
                    // entity rendering outright (ClassCastException, 2026-09-20).
                    onPick = { tag ->
                        @Suppress("UNCHECKED_CAST")
                        val multi = value as MultiChoiceListValue<Tagged>
                        multi.choices.firstOrNull { it.tag == tag }?.let(multi::toggle)
                    },
                    mx, my,
                )
            }

            is ChoiceListValue<*> -> {
                val current = value.get().tag
                y = drawPillRow(
                    ctx, value.name, current, value, x + inset, y,
                    items = value.choices.map { it.tag },
                    isActive = { it == current },
                    onPick = { value.setByString(it); openDropdowns.remove(value) },
                    mx, my,
                )
            }

            is RangedValue<*> -> y = drawRangeRow(ctx, value, x + inset, y)

            is ValueGroup -> {
                if (hasVisibleSettings(value)) {
                    y = drawCaptionRow(ctx, value.name, x + inset, y)
                    y = layoutSettings(ctx, value, x, y, mx, my, depth + 1)
                }
            }

            else -> when (value.valueType) {
                ValueType.BOOLEAN -> {
                    val on = value.get() as? Boolean ?: false
                    y = drawToggleRow(ctx, value.name, on, value, x + inset, y) {
                        (value as Value<Boolean>).set(!on)
                    }
                }

                ValueType.COLOR -> y = drawColorRow(ctx, value as Value<Color4b>, x + inset, y, mx, my)

                ValueType.TEXT -> y = drawTextRow(ctx, value, x + inset, y)

                else -> y = drawBadgeRow(ctx, value.name, value.get().toString().take(18), false, x + inset, y) {}
            }
        }
        return y
    }

    /** Right edge available to a row's control — the panel's, never the indented row's. */
    private val rowRight: Float get() = popupBaseX + popupWidth - PAD

    /** Label on the left, control on the right — the shape every popup row shares. */
    private fun rowLabel(ctx: GuiGraphicsExtractor?, name: String, x: Float, y: Float, on: Boolean) {
        ctx ?: return
        val maxW = (rowRight - PILL_W - 12f - (x + PAD)).coerceAtLeast(40f)
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, clip(WatermarkFont.TEXT, name, FONT_ROW, maxW),
            x + PAD, y, POPUP_ROW_H, FONT_ROW, if (on) Theme.textOn else Theme.textOff,
        )
    }

    private fun drawCaptionRow(ctx: GuiGraphicsExtractor?, name: String, x: Float, y: Float): Float {
        if (ctx != null) {
            drawTextVCentered(
                ctx, WatermarkFont.TEXT, name.uppercase(), x + PAD, y, CAPTION_H, FONT_CAPTION, Theme.label,
            )
        }
        return y + CAPTION_H
    }

    @Suppress("LongParameterList")
    private fun drawToggleRow(
        ctx: GuiGraphicsExtractor?, name: String, on: Boolean, key: Any,
        x: Float, y: Float, onClick: () -> Unit,
    ): Float {
        val hitKey = "set:" + System.identityHashCode(key)
        rowLabel(ctx, name, x, y, on)
        if (ctx != null) {
            val tx = rowRight - TOGGLE_W
            drawToggle(ctx, tx, y + (POPUP_ROW_H - TOGGLE_H) / 2f, on, knobOf(hitKey, on), 1f)
            hits += Hit(x, y, rowRight + PAD, y + POPUP_ROW_H, hitLayer) { button ->
                if (button == 0) { onClick(); true } else false
            }
        }
        return y + POPUP_ROW_H
    }

    @Suppress("LongParameterList")
    private fun drawPillRow(
        ctx: GuiGraphicsExtractor?, name: String, shown: String, key: Any,
        x: Float, y: Float,
        items: List<String>, isActive: (String) -> Boolean, onPick: (String) -> Unit,
        mx: Float, my: Float,
    ): Float {
        rowLabel(ctx, name, x, y, false)
        val px = rowRight - PILL_W
        val py = y + (POPUP_ROW_H - PILL_H) / 2f
        if (ctx != null) {
            ctx.drawRoundedRect(px, py, px + PILL_W, py + PILL_H, PILL_RADIUS, Theme.page.with(a = 0), Theme.border, 1f)
            drawTextVCentered(
                ctx, WatermarkFont.TEXT,
                clip(WatermarkFont.TEXT, shown, FONT_PILL, PILL_W - 18f - 12f),
                px + 8f, py, PILL_H, FONT_PILL, Theme.textOff,
            )
            drawIcon(ctx, "mursultan/angles", px + PILL_W - 9f - 5f, py + PILL_H / 2f, 10f, 14f, Theme.textOff)
            hits += Hit(px, py, px + PILL_W, py + PILL_H, hitLayer) { button ->
                if (button == 0) {
                    if (!openDropdowns.add(key)) openDropdowns.remove(key)
                    true
                } else {
                    false
                }
            }
        }
        var next = y + POPUP_ROW_H
        val t = easeInOutCubic(dropdownProgress[key] ?: 0f)
        if (t > 0.001f) {
            val listH = items.size * PILL_H + 8f
            val shownH = listH * t
            if (ctx != null) {
                val lx = px
                val ly = next
                ctx.drawRoundedRect(lx, ly, lx + PILL_W, ly + shownH, PILL_RADIUS, Theme.page, Theme.border, 1f)
                val clipRect = realRect(lx, ly, lx + PILL_W, ly + shownH)
                ctx.scissorStack.push(clipRect)
                try {
                    var iy = ly + 4f
                    for (item in items) {
                        val active = isActive(item)
                        val hovered = mx >= lx && mx <= lx + PILL_W && my >= iy && my <= iy + PILL_H
                        if (hovered) {
                            ctx.drawRoundedRect(lx + 3f, iy, lx + PILL_W - 3f, iy + PILL_H, 6f, Theme.hover, null)
                        }
                        drawTextVCentered(
                            ctx, WatermarkFont.TEXT,
                            clip(WatermarkFont.TEXT, item, FONT_PILL, PILL_W - 24f),
                            lx + 10f, iy, PILL_H, FONT_PILL,
                            if (active) accent else Theme.textOff,
                        )
                        val rowY = iy
                        hits += Hit(lx, rowY, lx + PILL_W, rowY + PILL_H, hitLayer) { button ->
                            if (button == 0) { onPick(item); true } else false
                        }.also { it.clipBottom = ly + shownH }
                        iy += PILL_H
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            next += shownH
        }
        return next
    }

    @Suppress("LongParameterList")
    private fun drawBadgeRow(
        ctx: GuiGraphicsExtractor?, name: String, text: String, active: Boolean,
        x: Float, y: Float, onClick: () -> Unit,
    ): Float {
        rowLabel(ctx, name, x, y, false)
        if (ctx != null) {
            val tw = textWidth(WatermarkFont.TEXT, text, FONT_PILL)
            val bw = (tw + 20f).coerceAtLeast(56f)
            val bx = rowRight - bw
            val by = y + (POPUP_ROW_H - PILL_H) / 2f
            ctx.drawRoundedRect(
                bx, by, bx + bw, by + PILL_H, PILL_RADIUS,
                if (active) accent.with(a = 40) else Theme.page.with(a = 0),
                if (active) accent else Theme.border, 1f,
            )
            drawTextVCentered(
                ctx, WatermarkFont.TEXT, text, bx + (bw - tw) / 2f, by, PILL_H, FONT_PILL,
                if (active) accent else Theme.textOff,
            )
            hits += Hit(bx, by, bx + bw, by + PILL_H, hitLayer) { button ->
                if (button == 0) { onClick(); true } else false
            }
        }
        return y + POPUP_ROW_H
    }

    @Suppress("UNCHECKED_CAST")
    private fun drawRangeRow(ctx: GuiGraphicsExtractor?, value: RangedValue<*>, x: Float, y: Float): Float {
        rowLabel(ctx, value.name, x, y, false)
        if (ctx == null) return y + POPUP_ROW_H

        val trackW = PILL_W
        val trackX = rowRight - trackW
        val cy = y + POPUP_ROW_H / 2f
        val text: String
        val frac: Float
        when (value.valueType) {
            ValueType.FLOAT -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val cur = value.get() as Float
                frac = fracOf(cur, range.start, range.endInclusive)
                text = "%.1f".format(cur)
            }
            ValueType.INT -> {
                val range = value.range as IntRange
                val cur = value.get() as Int
                frac = fracOf(cur.toFloat(), range.first.toFloat(), range.last.toFloat())
                text = cur.toString()
            }
            ValueType.FLOAT_RANGE, ValueType.INT_RANGE -> {
                // two handles on one track; the drag locks onto the nearer one for its whole life
                val (f0, f1) = rangeFracs(value) ?: return y + POPUP_ROW_H
                val trackW2 = PILL_W
                val trackX2 = rowRight - trackW2
                val cy2 = y + POPUP_ROW_H / 2f
                val label = when (value.valueType) {
                    ValueType.FLOAT_RANGE -> {
                        val v = value.get() as ClosedFloatingPointRange<Float>
                        "%.1f - %.1f".format(v.start, v.endInclusive)
                    }
                    else -> {
                        val v = value.get() as IntRange
                        "${v.first} - ${v.last}"
                    }
                }
                drawRangeTrack(ctx, trackX2, cy2, trackW2, f0, f1)
                val lw = textWidth(WatermarkFont.TEXT, label, FONT_PILL)
                drawTextVCentered(
                    ctx, WatermarkFont.TEXT, label, trackX2 - 12f - lw, y, POPUP_ROW_H, FONT_PILL, Theme.textOn,
                )
                hits += Hit(trackX2 - 4f, y, trackX2 + trackW2 + 4f, y + POPUP_ROW_H, hitLayer) { button ->
                    if (button == 0) {
                        var handle = -1
                        activeSlider = SliderDrag(trackX2, trackW2) { f ->
                            if (handle < 0) handle = pickRangeHandle(value, f)
                            if (handle >= 0) applyRangeFrac(value, f, handle)
                        }
                        true
                    } else {
                        false
                    }
                }
                return y + POPUP_ROW_H
            }

            else -> return drawBadgeRow(ctx, value.name, value.get().toString().take(14), false, x, y) {}
        }
        drawSliderTrack(ctx, trackX, cy, trackW, frac, 1f)
        val tw = textWidth(WatermarkFont.TEXT, text, FONT_PILL)
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, text, trackX - 12f - tw, y, POPUP_ROW_H, FONT_PILL, Theme.textOn,
        )
        hits += Hit(trackX - 4f, y, trackX + trackW + 4f, y + POPUP_ROW_H, hitLayer) { button ->
            if (button == 0) {
                activeSlider = SliderDrag(trackX, trackW) { f -> applyFrac(value, f) }
                true
            } else {
                false
            }
        }
        return y + POPUP_ROW_H
    }


    /** A track with two handles and the selected span between them. */
    private fun drawRangeTrack(ctx: GuiGraphicsExtractor, x: Float, cy: Float, w: Float, f0: Float, f1: Float) {
        val h = 4f
        val a = minOf(f0, f1).coerceIn(0f, 1f)
        val b = maxOf(f0, f1).coerceIn(0f, 1f)
        ctx.drawRoundedRect(x, cy - h / 2f, x + w, cy + h / 2f, h / 2f, Theme.trackOff, null)
        if (b > a) {
            ctx.drawRoundedRect(x + w * a, cy - h / 2f, x + w * b, cy + h / 2f, h / 2f, accent, null)
        }
        val knob = 12f
        for (frac in floatArrayOf(a, b)) {
            val kx = x + w * frac
            ctx.drawRoundedRect(kx - knob / 2f, cy - knob / 2f, kx + knob / 2f, cy + knob / 2f, knob / 2f, accent, null)
        }
    }

    /** Current (start, end) of a range value in fraction space, or null when it is not a range. */
    @Suppress("UNCHECKED_CAST")
    private fun rangeFracs(value: RangedValue<*>): Pair<Float, Float>? = when (value.valueType) {
        ValueType.FLOAT_RANGE -> {
            val range = value.range as ClosedFloatingPointRange<Float>
            val cur = value.get() as ClosedFloatingPointRange<Float>
            fracOf(cur.start, range.start, range.endInclusive) to
                fracOf(cur.endInclusive, range.start, range.endInclusive)
        }
        ValueType.INT_RANGE -> {
            val range = value.range as IntRange
            val cur = value.get() as IntRange
            fracOf(cur.first.toFloat(), range.first.toFloat(), range.last.toFloat()) to
                fracOf(cur.last.toFloat(), range.first.toFloat(), range.last.toFloat())
        }
        else -> null
    }

    /**
     * Which handle a drag grabs: the nearer one, decided ONCE and kept for the whole drag so the
     * two never trade places mid-gesture. Collapsed handles wait for the drag direction (-1 until
     * the pointer leaves the overlap), which is what makes a `4..4` range draggable at all.
     */
    private fun pickRangeHandle(value: RangedValue<*>, frac: Float): Int {
        val (f0, f1) = rangeFracs(value) ?: return 0
        if (abs(f1 - f0) < 1.0e-3f) {
            return when {
                frac > f0 + 1.0e-3f -> 1
                frac < f0 - 1.0e-3f -> 0
                else -> -1
            }
        }
        return if (abs(frac - f0) <= abs(frac - f1)) 0 else 1
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyRangeFrac(value: RangedValue<*>, frac: Float, handle: Int) {
        when (value.valueType) {
            ValueType.FLOAT_RANGE -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val cur = value.get() as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100).roundToInt() / 100f
                val next = if (handle == 0) {
                    v.coerceAtMost(cur.endInclusive)..cur.endInclusive
                } else {
                    cur.start..v.coerceAtLeast(cur.start)
                }
                // ranges have no value equality - compare the endpoints before writing
                if (next.start != cur.start || next.endInclusive != cur.endInclusive) {
                    (value as RangedValue<ClosedFloatingPointRange<Float>>).set(next)
                }
            }
            ValueType.INT_RANGE -> {
                val range = value.range as IntRange
                val cur = value.get() as IntRange
                val v = (range.first + frac * (range.last - range.first)).roundToInt()
                val next = if (handle == 0) v.coerceAtMost(cur.last)..cur.last else cur.first..v.coerceAtLeast(cur.first)
                if (next.first != cur.first || next.last != cur.last) {
                    (value as RangedValue<IntRange>).set(next)
                }
            }
            else -> Unit
        }
    }

    private fun fracOf(value: Float, from: Float, to: Float): Float =
        if (to - from <= 0f) 0f else ((value - from) / (to - from)).coerceIn(0f, 1f)

    @Suppress("UNCHECKED_CAST")
    private fun applyFrac(value: RangedValue<*>, frac: Float) {
        when (value.valueType) {
            ValueType.FLOAT -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100).roundToInt() / 100f
                if (v != value.get()) (value as RangedValue<Float>).set(v)
            }
            ValueType.INT -> {
                val range = value.range as IntRange
                val raw = range.first + frac * (range.last - range.first)
                val v = raw.roundToInt().coerceIn(range)
                if (v != value.get()) (value as RangedValue<Int>).set(v)
            }
            else -> Unit
        }
    }

    /** Total height of the expanded picker block. */
    private fun pickerHeight(): Float =
        PICK_SV_H + 10f + PICK_BAR_H + 8f + PICK_BAR_H + 14f + PICK_PREVIEW_H + 10f + PICK_PRESET + 8f

    private fun hexOf(color: Color4b): String = "#%02X%02X%02X".format(color.r, color.g, color.b)

    private fun hueOf(value: Value<Color4b>, color: Color4b): Float =
        pickerHue.getOrPut(value) { java.awt.Color.RGBtoHSB(color.r, color.g, color.b, null)[0] }

    /** Writes back HSB + alpha, remembering the hue so greys stay steerable. */
    private fun applyHsb(value: Value<Color4b>, hue: Float, sat: Float, bri: Float, alpha: Int) {
        pickerHue[value] = hue
        val rgb = Color4b.ofHSB(hue.coerceIn(0f, 0.9999f), sat.coerceIn(0f, 1f), bri.coerceIn(0f, 1f))
        val next = Color4b(rgb.r, rgb.g, rgb.b, alpha.coerceIn(0, 255))
        if (next.argb != value.get().argb) value.set(next)
    }

    private fun drawColorRow(
        ctx: GuiGraphicsExtractor?, value: Value<Color4b>, x: Float, y: Float, mx: Float, my: Float,
    ): Float {
        rowLabel(ctx, value.name, x, y, false)
        val color = value.get()
        val open = value in openPickers

        if (ctx != null) {
            val size = 22f
            val sx = rowRight - size
            val cy = y + POPUP_ROW_H / 2f
            ctx.drawRoundedRect(
                sx, cy - size / 2f, sx + size, cy + size / 2f, 6f,
                color.with(a = 255), if (open) accent else Theme.border, 1f,
            )
            val hex = hexOf(color)
            val tw = textWidth(WatermarkFont.TEXT, hex, FONT_PILL)
            drawTextVCentered(
                ctx, WatermarkFont.TEXT, hex, sx - 12f - tw, y, POPUP_ROW_H, FONT_PILL,
                if (open) accent else Theme.textOff,
            )
            hits += Hit(sx - 12f - tw, y, sx + size, y + POPUP_ROW_H, hitLayer) { button ->
                if (button == 0) {
                    if (!openPickers.add(value)) openPickers.remove(value)
                    true
                } else {
                    false
                }
            }
        }

        var next = y + POPUP_ROW_H
        val t = easeInOutCubic(pickerProgress[value] ?: 0f)
        if (t > 0.001f) {
            val shown = pickerHeight() * t
            if (ctx != null) {
                val bx = popupBaseX + PAD
                val bw = popupWidth - PAD * 2f
                val clipRect = realRect(bx - 2f, next, bx + bw + 2f, next + shown)
                ctx.scissorStack.push(clipRect)
                try {
                    val mark = hits.size
                    drawPicker(ctx, value, bx, next, bw, mx, my)
                    for (i in mark until hits.size) hits[i].clipBottom = next + shown
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            next += shown
        }
        return next
    }

    /**
     * The picker itself: a saturation/value field, a hue bar, an alpha bar, a preview with the
     * hex readout and a row of quick swatches. Drags are pointer-captured, so the pointer may
     * leave the widget without losing the grab.
     */
    @Suppress("LongMethod")
    private fun drawPicker(
        ctx: GuiGraphicsExtractor, value: Value<Color4b>, x: Float, y: Float, w: Float, mx: Float, my: Float,
    ) {
        val color = value.get()
        val hsb = java.awt.Color.RGBtoHSB(color.r, color.g, color.b, null)
        val hue = hueOf(value, color)
        val sat = hsb[1]
        val bri = hsb[2]

        // - saturation / value field -
        val svY = y
        drawSvField(ctx, x, svY, w, PICK_SV_H, hue)
        val handleX = x + w * sat
        val handleY = svY + PICK_SV_H * (1f - bri)
        drawHandle(ctx, handleX, handleY, 6f, Color4b.ofHSB(hue, sat, bri))
        hits += Hit(x, svY, x + w, svY + PICK_SV_H, hitLayer) { button ->
            if (button == 0) {
                pointerDrag = PointerDrag { px, py ->
                    applyHsb(
                        value, hue,
                        ((px - x) / w).coerceIn(0f, 1f),
                        1f - ((py - svY) / PICK_SV_H).coerceIn(0f, 1f),
                        value.get().a,
                    )
                }
                pointerDrag?.update(mx, my)
                true
            } else {
                false
            }
        }

        // - hue bar -
        var by = svY + PICK_SV_H + 10f
        ctx.drawSweepRoundedRect(x, by, x + w, by + PICK_BAR_H, PICK_BAR_H / 2f, columns = 32) { fraction ->
            Color4b.ofHSB(fraction.coerceAtMost(0.999f), 1f, 1f).argb
        }
        ctx.drawRoundedRect(x, by, x + w, by + PICK_BAR_H, PICK_BAR_H / 2f, null, Theme.border, 1f)
        drawBarHandle(ctx, x + w * hue, by, PICK_BAR_H, Color4b.ofHSB(hue, 1f, 1f))
        val hueY = by
        hits += Hit(x, by - 3f, x + w, by + PICK_BAR_H + 3f, hitLayer) { button ->
            if (button == 0) {
                pointerDrag = PointerDrag { px, _ ->
                    val current = value.get()
                    val cur = java.awt.Color.RGBtoHSB(current.r, current.g, current.b, null)
                    applyHsb(value, ((px - x) / w).coerceIn(0f, 0.9999f), cur[1], cur[2], current.a)
                }
                pointerDrag?.update(mx, hueY)
                true
            } else {
                false
            }
        }

        // - alpha bar -
        by += PICK_BAR_H + 8f
        ctx.drawRoundedRect(x, by, x + w, by + PICK_BAR_H, PICK_BAR_H / 2f, Theme.chip, null)
        ctx.drawSweepRoundedRect(x, by, x + w, by + PICK_BAR_H, PICK_BAR_H / 2f, columns = 24) { fraction ->
            color.with(a = (fraction * 255f).toInt().coerceIn(0, 255)).argb
        }
        ctx.drawRoundedRect(x, by, x + w, by + PICK_BAR_H, PICK_BAR_H / 2f, null, Theme.border, 1f)
        drawBarHandle(ctx, x + w * (color.a / 255f), by, PICK_BAR_H, color.with(a = 255))
        val alphaY = by
        hits += Hit(x, by - 3f, x + w, by + PICK_BAR_H + 3f, hitLayer) { button ->
            if (button == 0) {
                pointerDrag = PointerDrag { px, _ ->
                    val current = value.get()
                    val cur = java.awt.Color.RGBtoHSB(current.r, current.g, current.b, null)
                    applyHsb(value, hueOf(value, current), cur[1], cur[2], (((px - x) / w) * 255f).toInt())
                }
                pointerDrag?.update(mx, alphaY)
                true
            } else {
                false
            }
        }

        // - preview + readout -
        by += PICK_BAR_H + 14f
        ctx.drawRoundedRect(x, by, x + PICK_PREVIEW_H * 1.6f, by + PICK_PREVIEW_H, 7f, color.with(a = 255), Theme.border, 1f)
        val hex = hexOf(color)
        drawTextVCentered(
            ctx, WatermarkFont.BRAND, hex, x + PICK_PREVIEW_H * 1.6f + 12f, by, PICK_PREVIEW_H,
            FONT_PILL, Theme.textOn,
        )
        val rgba = "${color.r} ${color.g} ${color.b} - ${(color.a * 100f / 255f).roundToInt()}%"
        val rw = textWidth(WatermarkFont.TEXT, rgba, FONT_CAPTION)
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, rgba, x + w - rw, by, PICK_PREVIEW_H, FONT_CAPTION, Theme.textOff,
        )

        // - quick swatches -
        by += PICK_PREVIEW_H + 10f
        for ((index, argb) in PICK_PRESETS.withIndex()) {
            val cx = x + index * (PICK_PRESET + PICK_PRESET_GAP)
            if (cx + PICK_PRESET > x + w) break
            val preset = Color4b(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb), 255)
            val selected = preset.argb == color.with(a = 255).argb
            val hovered = mx >= cx && mx <= cx + PICK_PRESET && my >= by && my <= by + PICK_PRESET
            ctx.drawRoundedRect(
                cx, by, cx + PICK_PRESET, by + PICK_PRESET, 7f, preset,
                when {
                    selected -> Theme.title
                    hovered -> Theme.navActiveBorder
                    else -> Theme.border
                },
                if (selected) 2f else 1f,
            )
            val swatchY = by
            hits += Hit(cx, swatchY, cx + PICK_PRESET, swatchY + PICK_PRESET, hitLayer) { button ->
                if (button == 0) {
                    val hsv = java.awt.Color.RGBtoHSB(preset.r, preset.g, preset.b, null)
                    applyHsb(value, hsv[0], hsv[1], hsv[2], value.get().a)
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Saturation (x) by value (y) field for [hue], drawn as a coarse vertex-coloured grid. */
    private fun drawSvField(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, hue: Float) {
        val cols = 14
        val rows = 8
        ctx.drawCustomElement(bounds = ctx.getBoundsXYWH(x, y, w, h)) { pose ->
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val x0 = x + w * col / cols
                    val x1 = x + w * (col + 1) / cols
                    val y0 = y + h * row / rows
                    val y1 = y + h * (row + 1) / rows
                    val s0 = col.toFloat() / cols
                    val s1 = (col + 1f) / cols
                    val v0 = 1f - row.toFloat() / rows
                    val v1 = 1f - (row + 1f) / rows
                    addVertexWith2DPose(pose, x0, y0).setColor(Color4b.ofHSB(hue, s0, v0).argb)
                    addVertexWith2DPose(pose, x0, y1).setColor(Color4b.ofHSB(hue, s0, v1).argb)
                    addVertexWith2DPose(pose, x1, y1).setColor(Color4b.ofHSB(hue, s1, v1).argb)
                    addVertexWith2DPose(pose, x1, y0).setColor(Color4b.ofHSB(hue, s1, v0).argb)
                }
            }
        }
        ctx.drawQuad(x, y, x + w, y + h, null, Theme.border)
    }

    /** Ring handle over the SV field: white outer ring, dark inner ring, live colour inside. */
    private fun drawHandle(ctx: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, color: Color4b) {
        ctx.drawRoundedRect(cx - r, cy - r, cx + r, cy + r, r, color.with(a = 255), Theme.title, 2f)
        ctx.drawRoundedRect(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f, r + 1f, null, Theme.page.with(a = 160), 1f)
    }

    /** Pill handle riding a hue/alpha bar. */
    private fun drawBarHandle(ctx: GuiGraphicsExtractor, cx: Float, y: Float, h: Float, color: Color4b) {
        val hw = 5f
        val x0 = cx - hw
        ctx.drawRoundedRect(x0, y - 2f, x0 + hw * 2f, y + h + 2f, hw, color, Theme.title, 2f)
    }

    private fun drawTextRow(ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, y: Float): Float {
        rowLabel(ctx, value.name, x, y, false)
        if (ctx == null) return y + POPUP_ROW_H
        val focused = focusedField === value
        val raw = if (focused) editBuffer else value.get().toString()
        val px = rowRight - PILL_W
        val py = y + (POPUP_ROW_H - PILL_H) / 2f
        ctx.drawRoundedRect(
            px, py, px + PILL_W, py + PILL_H, PILL_RADIUS, Theme.page.with(a = 0),
            if (focused) accent else Theme.border, 1f,
        )
        val caret = if (focused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        drawTextVCentered(
            ctx, WatermarkFont.TEXT, clip(WatermarkFont.TEXT, raw + caret, FONT_PILL, PILL_W - 16f),
            px + 8f, py, PILL_H, FONT_PILL, Theme.textOff,
        )
        hits += Hit(px, py, px + PILL_W, py + PILL_H, hitLayer) { button ->
            if (button == 0) {
                commitEdit()
                focusedField = value
                editBuffer = value.get().toString()
                searchFocused = false
                true
            } else {
                false
            }
        }
        return y + POPUP_ROW_H
    }

    @Suppress("UNCHECKED_CAST")
    private fun commitEdit() {
        val target = focusedField ?: return
        focusedField = null
        if (target.valueType == ValueType.TEXT) {
            runCatching { (target as Value<String>).set(editBuffer) }
        }
        editBuffer = ""
    }

    // - Settings popover (the burger) -

    private fun drawSettingsPopover(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val reveal = easeInOutCubic(popoverT)
        if (reveal <= 0.001f) return
        hitLayer = 2
        val fullH = popoverHeight()
        val h = fullH * reveal
        val x = DESIGN_W - PAD - POPOVER_W
        val y = HEADER_H + 8f
        ctx.drawRoundedRect(x, y, x + POPOVER_W, y + h, CARD_RADIUS, Theme.popup, Theme.border, 1f)

        val clip = realRect(x, y, x + POPOVER_W, y + h)
        ctx.scissorStack.push(clip)
        try {
            val mark = hits.size
            var ry = y + PAD
            ry = drawAccentRow(ctx, x, ry, POPOVER_W, mx, my, 1f)
            ry = drawAccentPicker(ctx, x, ry, POPOVER_W, mx, my)
            ry = drawScaleChips(ctx, x, ry, POPOVER_W, mx, my, 1f)
            val label = tr("liquidbounce.mursultan.descriptions", "Descriptions")
            drawSwitchRow(ctx, label, MursultanClickGuiSettings.descriptions, "descP", x, ry, POPOVER_W, 1f) {
                MursultanClickGuiSettings.descriptions = !MursultanClickGuiSettings.descriptions
            }
            for (i in mark until hits.size) {
                hits[i].clipTop = y
                hits[i].clipBottom = y + h
            }
        } finally {
            ctx.scissorStack.pop()
        }
        hitLayer = 0
    }

    private fun popoverHeight(): Float {
        val picker = if (accentPickerT <= 0.001f) {
            0f
        } else {
            (PICK_SV_H + 10f + PICK_BAR_H + 14f) * easeInOutCubic(accentPickerT) + 4f
        }
        return PAD * 2f + POPUP_ROW_H * 2f + picker + scaleChipsHeight(POPOVER_W)
    }

    // - Input -

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = toVirtualX(click.x().toFloat())
        val my = toVirtualY(click.y().toFloat())
        val button = click.button()

        // topmost layer first, and within a layer the most recently added hit
        for (layer in 2 downTo 0) {
            for (i in hits.indices.reversed()) {
                val hit = hits[i]
                if (hit.layer != layer) continue
                if (hit.contains(mx, my) && hit.onClick(button)) {
                    return true
                }
            }
            // a click inside an open floating layer never falls through to the page
            if (layer == 2 && popoverOpen && insidePopover(mx, my)) return true
            if (layer == 1 && popupModule != null && insidePopup(mx, my)) return true
        }

        // click-away closes the floating layers and drops focus
        if (popoverOpen) popoverOpen = false
        if (popupModule != null) popupModule = null
        commitEdit()
        searchFocused = false
        return true
    }

    private fun insidePopover(mx: Float, my: Float): Boolean {
        val x = DESIGN_W - PAD - POPOVER_W
        val y = HEADER_H + 8f
        return mx >= x && mx <= x + POPOVER_W && my >= y && my <= y + popoverHeight()
    }

    /** Hit rect of the panel as it was actually drawn — never the theoretical maximum. */
    private fun insidePopup(mx: Float, my: Float): Boolean =
        popupModule != null && mx >= popupRectX && mx <= popupRectX + POPUP_W &&
            my >= popupRectY && my <= popupRectY + popupRectH

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() == 0 && activeSlider != null) {
            activeSlider = null
            MursultanClickGuiSettings.flushDirty()
        }
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val my = toVirtualY(mouseY.toFloat())
        val mx = toVirtualX(mouseX.toFloat())
        if (popupModule != null && insidePopup(mx, my)) {
            popupScrollTarget = (popupScrollTarget - vertical.toFloat() * 56f).coerceAtLeast(0f)
            return true
        }
        if (my >= viewTop) {
            val maxScroll = (contentHeight - (viewBottom - viewTop - PAD * 2f)).coerceAtLeast(0f)
            scrollTarget = (scrollTarget - vertical.toFloat() * 60f).coerceIn(0f, maxScroll)
        }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()

        // bind capture swallows everything
        pendingBind?.let { target ->
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> pendingBind = null
                GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                    target.set(InputBind.UNBOUND)
                    pendingBind = null
                }
                else -> {
                    target.set(InputBind(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, key, target.get().action))
                    pendingBind = null
                }
            }
            return true
        }

        if (focusedField != null) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { focusedField = null; editBuffer = "" }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitEdit()
                GLFW.GLFW_KEY_BACKSPACE -> editBuffer = editBuffer.dropLast(1)
            }
            return true
        }

        if (searchFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    if (searchQuery.isEmpty()) startClose() else searchQuery = ""
                    return true
                }
                GLFW.GLFW_KEY_BACKSPACE -> {
                    searchQuery = searchQuery.dropLast(1)
                    scrollTarget = 0f
                    return true
                }
            }
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            when {
                popoverOpen -> popoverOpen = false
                popupModule != null -> popupModule = null
                else -> startClose()
            }
            return true
        }
        return super.keyPressed(input)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        val ch = input.codepoint()
        if (ch < ' '.code) return false
        if (focusedField != null) {
            editBuffer += ch.toChar()
            return true
        }
        if (searchFocused) {
            searchQuery += ch.toChar()
            scrollTarget = 0f
            return true
        }
        return super.charTyped(input)
    }
}
