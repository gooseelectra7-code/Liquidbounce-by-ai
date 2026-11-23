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

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.BindValue
import net.ccbluex.liquidbounce.config.types.CurveValue
import net.ccbluex.liquidbounce.config.types.FileValue
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.MutableGroupListValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.ItemsChooseValue
import net.ccbluex.liquidbounce.config.types.list.ListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MutableListValue
import net.ccbluex.liquidbounce.config.types.list.RegistryListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.global.GlobalManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.preview.VisualsPreview
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.preview.VisualsPreviewScene
import net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.Esp2DMode
import net.ccbluex.liquidbounce.features.module.modules.render.nametags.ModuleNametags
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawSweepRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.utils.network.packetRegistry
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.Registry
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.util.ARGB
import net.minecraft.world.item.Item
import net.minecraft.world.phys.Vec3
import org.joml.Vector2f
import org.joml.Vector2fc
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Native (OpenGL, no browser) ClickGUI in the "Leet" design language.
 *
 * Layout: a dedicated logo slot in the top-left corner, a header row (category name +
 * search) to its right, one continuous vertical category strip below the logo, and two
 * fixed columns of module cards (a module never changes its column when expanding).
 *
 * Enabled modules are shown with a lighter card instead of a checkbox. Nested setting
 * groups live in subtle rounded containers without shifting the text X axis. Single-choice
 * settings collapse into a small field and expand with RMB. Colors get a full HSV+alpha
 * picker. The GUI animates on open/close and when expanding/collapsing a card.
 *
 * Text uses the bundled Inter faces ([WatermarkFont.BRAND]/[WatermarkFont.TEXT]) drawn
 * 1:1 device-pixel-snapped. Ctrl+wheel zoom keeps text crisp because the zoom translation
 * is snapped to the device pixel grid (glyph quads then land exactly on device pixels).
 *
 * A virtual, UNREGISTERED "gui" category is pinned to the bottom of the category rail; its
 * content pane hosts settings of the native GUI itself (see [LeetClickGuiSettings], e.g. the
 * per-category Solar icon style). Being unregistered, it never leaks into the browser GUI.
 * Pane settings render as compact full-width cards in the module-card design language
 * (see [guiSettingCard]): the icon-style rows pair a lavender live icon + dimmed category
 * name with a fixed-width style dropdown whose option list previews every style's icon.
 */
class LeetClickGuiScreen :
    Screen("ClickGUI".asPlainText()),
    net.ccbluex.liquidbounce.integration.screen.NativeClientScreen,
    ClickGuiSearchAware {

    override val isSearchFocused: Boolean get() = searchFocused


    // - Theme -
    private object Theme {
        val overlayRgb = intArrayOf(11, 11, 13)
        val backdrop = Color4b(15, 15, 18, 252)
        val backdropBorder = Color4b(28, 28, 34, 255)
        val card = Color4b(19, 19, 22, 255)
        val cardHover = Color4b(22, 22, 26, 255)
        val cardOpen = Color4b(23, 23, 28, 255)
        val cardOpenHover = Color4b(26, 26, 32, 255)
        val cardEnabled = Color4b(27, 27, 33, 255)
        val cardEnabledHover = Color4b(30, 30, 37, 255)
        val cardEnabledOpen = Color4b(32, 32, 40, 255)
        val cardEnabledOpenHover = Color4b(35, 35, 44, 255)
        val group = Color4b(22, 22, 26, 255)
        val groupHead = Color4b(25, 25, 30, 255)
        val stripActive = Color4b(30, 30, 37, 255)
        val rowBorder = Color4b(31, 31, 38, 255)
        val field = Color4b(16, 16, 19, 255)
        val badge = Color4b(30, 30, 36, 255)
        val track = Color4b(38, 38, 46, 255)
        val listBg = Color4b(22, 22, 27, 255)
        val listActive = Color4b(31, 31, 40, 255)

        val lav = Color4b(168, 163, 245, 255)
        val lavDark = Color4b(27, 27, 42, 255)
        val knob = Color4b(214, 212, 250, 255)

        val white = Color4b(236, 236, 241, 255)
        val desc = Color4b(143, 143, 151, 255)
        val dim = Color4b(98, 98, 107, 255)
    }

    // - Layout constants (gui units) -
    private companion object {
        const val WINDOW_W = 640f
        const val WINDOW_H = 340f
        const val RAIL = 30f
        const val RAIL_RADIUS = 8f
        const val RAIL_CELL = 30f
        const val GAP = 7f
        const val HEADER_H = 30f
        const val SEARCH_W = 130f
        const val CARD_RADIUS = 5f
        const val CARD_PAD = 10f
        const val COL_GAP = 8f
        const val CTRL_W = 110f

        /** Virtual GUI pane: compact setting-card metrics and the FIXED dropdown field width. */
        const val GUI_ROW_H = 20f
        const val GUI_PAD = 6f
        const val GUI_ROW_GAP = 4f
        const val GUI_FIELD_W = 104f

        const val FONT_TITLE = 10.5f
        const val FONT_SEARCH = 7.5f
        const val FONT_NAME = 8.5f
        const val FONT_DESC = 7f
        const val FONT_SET = 7.5f
        const val FONT_CTRL = 7f

        // One timing for every animation (GUI open/close, module expand/collapse)
        const val OPEN_MS = 200f
        const val CLOSE_MS = 200f
        const val EXPAND_SEC = 0.2f
        const val COLLAPSE_SEC = 0.2f

        // Widget animation timings (ALL eased with easeInOutCubic): height reveals
        // 150-200 ms, micro-interactions (hover, checkbox, borders, icons) 100-150 ms
        const val REVEAL_SEC = 0.15f    // dropdown open/close
        const val PICKER_SEC = 0.18f    // color picker open/close
        const val GROUP_SEC = 0.15f     // ToggleableValueGroup box reveal
        const val CHECK_SEC = 0.12f     // checkbox toggle
        const val PICK_SEC = 0.12f      // multi-choice selection feedback
        const val HOVER_SEC = 0.10f     // dropdown item hover
        const val CAT_ICON_SEC = 0.12f  // category icon color + cell highlight
        const val FOCUS_SEC = 0.12f     // search focus border
        const val BIND_SEC = 0.12f      // bind-capture badge
        const val COLOR_LERP_MS = 150f  // resolved-color watcher (card fill, name/desc text)
        const val INTRO_MS = 150f       // category switch content intro
        const val SCROLL_TAU = 0.03f    // smooth scroll time constant (~150 ms settle)

        /** One uniform vertical gap: between settings, above/below setting blocks, etc. */
        const val SET_GAP = 6f

        /** Registry/items pickers: entry row height and max visible rows (then internal scroll). */
        const val REG_ROW_H = 12f
        const val LIST_MAX_ROWS = 8

        /**
         * Visuals preview window (Render category). [PREVIEW_TUCK] is how far its inner edge
         * slides UNDER the main window plate so it reads as a panel sitting behind the GUI.
         */
        const val PREVIEW_W = 130f
        const val PREVIEW_TUCK = 14f
        const val PREVIEW_PAD = 8f

        /**
         * Stage box. Sized to the figure the scene renders: the stand-in fills roughly
         * three quarters of it, leaving air above and below. Anything taller
         * only pushes the toggle strip down the panel.
         */
        const val PREVIEW_STAGE_H = 100f
        const val PREVIEW_ROW_H = 18f
        const val PREVIEW_ROW_GAP = 4f

        /** Nametag scale in the preview: the stage is small, the in-world curve is not. */
        const val PREVIEW_NAMETAG_SCALE = 0.5f

        val CATEGORY_DESC = mapOf(
            "Combat" to "Advanced modules for total battlefield control.",
            "Player" to "Automation for the player itself.",
            "Movement" to "Modules that enhance the way you move.",
            "Render" to "See more than everyone else.",
            "World" to "Interact with the world around you.",
            "Misc" to "Everything that fits nowhere else.",
            "Exploit" to "Abuse server-side quirks and bugs.",
            "Fun" to "Just for fun.",
            "gui" to "Native GUI settings.",
            "Settings" to "Client-wide settings: targets, language, commands.",
        )

        /**
         * Virtual category pinned to the bottom of the rail: the native GUI's own settings.
         * Deliberately UNREGISTERED (not in [ModuleCategories.entries]) - the browser GUI
         * only learns categories from module serialization, so this sentinel can never
         * appear there.
         */
        val GUI_CATEGORY = ModuleCategory("gui")

        /**
         * Second virtual category: the CLIENT's own settings, i.e. the whole [GlobalManager]
         * tree (Targets, Language, Commands, BlinkManager, SwapGate, ...). The Hellhit GUI
         * has had this panel all along (`HellhitClickGuiScreen.kt:1043`/`:1062`,
         * `layoutSettings(..., GlobalManager, ...)`) while the native GUI rendered
         * `GlobalManager` nowhere, so those settings were unreachable here.
         *
         * Unregistered for the same reason as [GUI_CATEGORY]. Distinct from it: that tab is
         * the look of THIS GUI, this one is the client.
         */
        val SETTINGS_CATEGORY = ModuleCategory("Settings")

        // UI state persisted between openings (in-memory)
        var selectedCategory: ModuleCategory = ModuleCategories.COMBAT
        val expandedModules = mutableSetOf<String>()
        val expandProgress = HashMap<String, Float>()
        // keyed by Value identity for module settings, by stable row-key strings for the
        // virtual GUI pane (it has no owning Value objects)
        val openDropdowns: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val openPickers: MutableSet<Value<*>> = Collections.newSetFromMap(IdentityHashMap())
        val pickerState = IdentityHashMap<Value<*>, FloatArray>()
        // registry/items pickers: per-value scroll offset, search query, full entry cache
        val listScrollOffsets = IdentityHashMap<Value<*>, Float>()
        val pickerSearchQueries = IdentityHashMap<Value<*>, String>()
        val pickerEntries = IdentityHashMap<Value<*>, List<RegEntry>>()
        var scrollY = 0f
        var searchQuery = ""
        var searchFocused = false
        var uiScale = 1f

        // - Persistent animation state (advanced ONCE per frame, never in measure/draw) -
        val dropdownProgress = IdentityHashMap<Any, Float>()
        val pickerProgress = IdentityHashMap<Value<*>, Float>()
        val groupProgress = IdentityHashMap<Value<*>, Float>()
        val checkboxProgress = IdentityHashMap<Value<*>, Float>()
        val listHoverProgress = HashMap<String, Float>()   // key: "identityHash(value):tag"
        val listPickProgress = HashMap<String, Float>()    // key: "identityHash(value):tag"
        val categoryIconProgress = HashMap<ModuleCategory, Float>()
        val colorLerps = HashMap<String, ColorLerp>()      // resolved-color watchers
        var searchFocusT = 0f
        var bindCaptureT = 0f
        var scrollTarget = 0f
        var categorySwitchedAt = 0L

        /** Bind action dropdown items (Toggle/Hold/Smart), fixed for the JVM lifetime. */
        val BIND_ACTION_TAGS: List<String> = InputBind.BindAction.entries.map { it.tag }
    }

    /** Resolved-color watcher state: lerps from the on-screen color to the new target. */
    private class ColorLerp(var from: Color4b, var to: Color4b, var startMs: Long)

    /**
     * Solar icon set (CC BY 4.0, 480 Design) rasterized to white 64px PNGs at build time.
     * Loaded lazily as GPU textures; tinted at draw time via vertex color.
     */
    private object Icons {
        private val setups = HashMap<String, TextureSetup?>()

        /** Resource-existence probe results, kept SEPARATE from [setups] (which negative-caches permanently). */
        private val existence = HashMap<String, Boolean>()

        fun get(name: String): TextureSetup? {
            if (name in setups) return setups[name]
            val setup = try {
                val image = LiquidBounce.resource("textures/clickgui/$name.png").readNativeImage()
                val texture = image.asTexture { "LeetClickGui-$name" }
                TextureSetup.singleTexture(
                    texture.textureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                )
            } catch (e: Throwable) {
                logger.error("LeetClickGui: failed to load icon $name", e)
                null
            }
            setups[name] = setup
            return setup
        }

        /**
         * Whether `textures/clickgui/$name.png` exists on the classpath. Probed once per
         * name via the same resource lookup as [get] and cached separately, so a missing
         * styled variant never poisons the texture cache with a load attempt.
         */
        fun has(name: String): Boolean = existence.getOrPut(name) {
            runCatching { LiquidBounce.resource("textures/clickgui/$name.png").close() }.isSuccess
        }
    }

    /**
     * Icon name for [assetTag] (e.g. "combat", "settings") honoring the per-category style
     * chosen under [styleKey] in [LeetClickGuiSettings]: `<assetTag>/<style>` when that file
     * exists, the flat `<assetTag>` (bold) otherwise.
     */
    private fun styledIconName(assetTag: String, styleKey: String): String =
        styledIconNameFor(assetTag, LeetClickGuiSettings.styleFor(styleKey))

    /** Icon name for [assetTag] in a specific [styleId] (flat `<assetTag>` fallback). */
    private fun styledIconNameFor(assetTag: String, styleId: String): String {
        val styled = "$assetTag/$styleId"
        return if (Icons.has(styled)) styled else assetTag
    }

    private fun drawIcon(ctx: GuiGraphicsExtractor, name: String, cx: Float, cy: Float, size: Float, color: Color4b) {
        val setup = Icons.get(name) ?: return
        val half = size / 2f
        ctx.drawTexQuad(setup, cx - half, cy - half, cx + half, cy + half, argb = color.argb)
    }

    // - Fonts (crisp Inter, device-pixel-snapped) -
    private val dpr: Float get() = mc.window.guiScale.toFloat()

    private val density: Float get() = dpr * uiScale

    private fun textWidth(font: WatermarkFont, text: String, px: Float): Float = font.width(text, px, density)

    private fun lineHeight(font: WatermarkFont, px: Float): Float = font.lineHeight(px, density)

    private fun drawText(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, y: Float, px: Float, color: Color4b,
    ) {
        val argb = color.argb
        font.submit(ctx, text, x, y, px, density) { argb }
    }

    // - Per-frame interaction state -
    private class Hit(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val inScroll: Boolean,
        val onClick: (button: Int) -> Boolean,
    ) {
        /** Virtual-Y cutoff while the collapse animation scissors the card (see bug fix 4). */
        var clipBottom: Float = Float.MAX_VALUE

        fun contains(mx: Float, my: Float) = mx >= x0 && mx <= x1 && my >= y0 && my <= y1
    }

    private class SliderDrag(val trackX: Float, val trackW: Float, val apply: (frac: Float) -> Unit) {
        /** Last fraction actually applied - repeat frames with an unchanged fraction are skipped. */
        var lastFrac = Float.NaN

        fun update(frac: Float) {
            if (frac == lastFrac) return
            lastFrac = frac
            apply(frac)
        }
    }

    /** Generic pointer capture (color picker areas): receives virtual mouse coords while held. */
    private class PointerDrag(val apply: (x: Float, y: Float) -> Unit) {
        /** Last coords actually applied - repeat frames with an unchanged position are skipped. */
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
    /** Bind capture target: a module's `Bind` value or any [BindValue] setting. */
    private var pendingBind: Value<InputBind>? = null
    private var pendingKey: Value<InputConstants.Key>? = null

    /**
     * Text editing happens in a buffer and is committed on Enter/click-away (Regex-safe).
     * The focus target is identified by a stable KEY so non-Value targets (the search box
     * of the registry/items pickers) share the same machinery: Value-backed module fields
     * get "text:<identityHash>", picker search boxes their own key. [focusedField] is null
     * for keyed non-Value targets - their buffer is handed back through [keyedCommit].
     */
    private var focusedField: Value<*>? = null
    private var focusedFieldKey: String? = null
    private var editBuffer = ""
    private var keyedCommit: ((String) -> Unit)? = null

    private var viewTop = 0f
    private var viewBottom = 0f
    private var contentHeight = 0f

    // - Per-frame animation feed (collected during the passes, consumed by the next
    //   frame's advanceUiAnimations - progress maps themselves are never touched there) -
    private val groupTargets = Collections.newSetFromMap(IdentityHashMap<Value<*>, Boolean>())
    private val checkboxTargets = Collections.newSetFromMap(IdentityHashMap<Value<*>, Boolean>())
    private var hoveredListKey: String? = null
    /** Selection-feedback feed of picker entry rows (same lifecycle as [hoveredListKey]). */
    private val registryPickTargets = HashSet<String>()
    /** Wheel-capture regions of open picker lists, rebuilt every frame (see mouseScrolled). */
    private val listScrollRegions = ArrayList<ListScrollRegion>(8)
    private var hoveredCategory: ModuleCategory? = null
    private var frameDt = 1f / 60f
    private var introE = 1f

    // - Open/close + expand animation state -
    private val openedAt = System.currentTimeMillis()
    private var closingAt = 0L
    private var lastFrameMs = 0L

    /** Effective transform of the current frame: virtual = (real - tx) / scale. */
    private var curScale = 1f
    private var curTx = 0f
    private var curTy = 0f

    // - Rendering -

    private fun toVirtualX(mx: Float): Float = (mx - curTx) / curScale

    private fun toVirtualY(my: Float): Float = (my - curTy) / curScale

    private fun toRealX(vx: Float): Float = vx * curScale + curTx

    private fun toRealY(vy: Float): Float = vy * curScale + curTy

    /** The single easing used by ALL animations: gentle at both ends (no abrupt finish). */
    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val inv = -2f * x + 2f
            1f - inv * inv * inv / 2f
        }
    }

    /** Channel-wise color lerp - THE color interpolation used by every animated widget. */
    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        return Color4b(ch(a.r, b.r), ch(a.g, b.g), ch(a.b, b.b), ch(a.a, b.a))
    }

    /** Premultiplies the alpha channel (category intro fade, hover/dot fades). */
    private fun Color4b.alphaMul(f: Float): Color4b = with(a = (a * f).roundToInt().coerceIn(0, 255))

    /**
     * Resolved-color watcher: widgets resolve their target color from state every frame;
     * when it changes, the color currently on screen is captured and lerped to the new
     * target over COLOR_LERP_MS. Covers every state combination with one mechanism.
     */
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

    private fun moveToward(cur: Float, target: Float, step: Float): Float = when {
        cur < target -> (cur + step).coerceAtMost(target)
        cur > target -> (cur - step).coerceAtLeast(target)
        else -> cur
    }

    val isClosing: Boolean get() = closingAt != 0L

    private fun startClose() {
        if (closingAt == 0L) {
            closingAt = System.currentTimeMillis()
            activeSlider = null
            pointerDrag = null
            commitEdit()
        }
    }

    override fun tick() {
        if (closingAt != 0L && System.currentTimeMillis() - closingAt >= CLOSE_MS.toLong()) {
            super.onClose() // actually closes the screen
        }
    }

    override fun onClose() {
        // Intercept: play the closing animation first; tick() finishes the close.
        startClose()
    }

    private var prunedOnOpen = false

    override fun init() {
        super.init()
        if (!prunedOnOpen) {
            prunedOnOpen = true // init() also runs on resize; prune only once per open
            pruneStaleState()
            // honor the module's SearchBarAutoFocus (mirrors the browser GUI): focus the
            // search field once on open; click-to-focus/-clear semantics are untouched
            searchFocused = ModuleClickGui.inner
                .firstOrNull { it.name == "SearchBarAutoFocus" }
                ?.let { it.get() as? Boolean } ?: true
        }
    }

    /**
     * The companion object keeps UI state across openings - drop entries whose module or
     * Value no longer exists. Module-keyed maps are pruned precisely; the Value-identity
     * maps are cleared wholesale (cheap, and a closed dropdown/picker is harmless).
     */
    private fun pruneStaleState() {
        val names = ModuleManager.mapTo(HashSet()) { it.name }
        expandedModules.retainAll(names)
        expandProgress.keys.retainAll(names)
        // watcher keys carry a "kind:ModuleName" prefix - prune by the module part
        colorLerps.keys.retainAll { it.substringAfter(':') in names }
        openDropdowns.clear()
        openPickers.clear()
        pickerState.clear()
        listScrollOffsets.clear()
        pickerSearchQueries.clear()
        pickerEntries.clear()
        // these pair with the cleared sets above - a reopened widget must re-animate
        dropdownProgress.clear()
        pickerProgress.clear()
        listHoverProgress.clear()
        listPickProgress.clear()
        bindCaptureT = 0f
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        hits.clear()

        val now = System.currentTimeMillis()
        val dtSec = (if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 50L)) / 1000f
        lastFrameMs = now
        frameDt = dtSec
        advanceExpandAnimations(dtSec)
        advanceUiAnimations(dtSec)

        // open/close motion: 0 = hidden, 1 = fully open
        val openT = ((now - openedAt) / OPEN_MS).coerceIn(0f, 1f)
        val closeT = if (closingAt == 0L) 0f else ((now - closingAt) / CLOSE_MS).coerceIn(0f, 1f)
        val motion = easeInOutCubic(openT) * (1f - easeInOutCubic(closeT))

        // Leet page background: near-black + edge vignette (unscaled), faded by motion
        val (or, og, ob) = Theme.overlayRgb
        ctx.fill(0, 0, width, height, ARGB.color((238 * motion).toInt().coerceIn(0, 255), or, og, ob))
        drawVignette(ctx, motion)

        // Zoom transform. At rest the translation is snapped to the device pixel grid so
        // the pixel-snapped glyph quads keep landing exactly on device pixels (crisp text).
        val pop = 0.96f + 0.04f * motion
        curScale = uiScale * pop
        val slide = (1f - motion) * 10f
        val rawTx = width / 2f * (1f - curScale)
        val rawTy = height / 2f * (1f - curScale) + slide
        val atRest = motion >= 0.999f
        val d = dpr
        curTx = if (atRest && d > 0f) Math.round(rawTx * d) / d else rawTx
        curTy = if (atRest && d > 0f) Math.round(rawTy * d) / d else rawTy

        val vMouseX = toVirtualX(mouseX.toFloat())
        val vMouseY = toVirtualY(mouseY.toFloat())

        // Frame-driven drag: poll the physical button state instead of relying on drag
        // events. Captured slider/picker drags follow the mouse every frame and release
        // exactly when the button is up - no missed events, no stolen captures.
        val leftDown = GLFW.glfwGetMouseButton(mc.window.handle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS
        if (!leftDown) {
            activeSlider = null
            pointerDrag = null
        } else {
            activeSlider?.let { it.update(((vMouseX - it.trackX) / it.trackW).coerceIn(0f, 1f)) }
            pointerDrag?.update(vMouseX, vMouseY)
        }

        ctx.pose().pushMatrix()
        ctx.pose().translate(curTx, curTy)
        ctx.pose().scale(curScale, curScale)
        try {
            drawGui(ctx, vMouseX, vMouseY)
        } finally {
            ctx.pose().popMatrix()
        }
    }

    private fun advanceExpandAnimations(dtSec: Float) {
        for (name in expandedModules) {
            expandProgress.putIfAbsent(name, 0f)
        }
        val iterator = expandProgress.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val expanding = entry.key in expandedModules
            val next = if (expanding) {
                (entry.value + dtSec / EXPAND_SEC).coerceAtMost(1f)
            } else {
                (entry.value - dtSec / COLLAPSE_SEC).coerceAtLeast(0f)
            }
            if (!expanding && next <= 0f) {
                iterator.remove()
            } else if (next != entry.value) {
                // skip the write once settled (expanded at 1f) - nothing changes per frame
                entry.setValue(next)
            }
        }
    }

    /**
     * Generic progress-map driver: seeds [activeKeys] at 0 and advances every entry
     * toward its target (1 for active keys, 0 otherwise) at [durSec] full-range speed.
     * Entries settled back at 0 are dropped. Called ONCE per frame - the measure/draw
     * passes only read the maps, so both passes always see identical progress.
     */
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

    /** Category strip icon target: active = 1, hovered = 0.5, else 0. */
    private fun catIconTarget(category: ModuleCategory): Float = when {
        category == selectedCategory && searchQuery.isBlank() -> 1f
        category == hoveredCategory -> 0.5f
        else -> 0f
    }

    /** One per-frame advance for every widget animation (except the timestamp-based ones). */
    private fun advanceUiAnimations(dtSec: Float) {
        // height reveals: dropdown lists, color pickers, group boxes
        advanceSetProgress(dropdownProgress, openDropdowns, dtSec, REVEAL_SEC)
        advanceSetProgress(pickerProgress, openPickers, dtSec, PICKER_SEC)
        advanceSetProgress(groupProgress, groupTargets, dtSec, GROUP_SEC)
        advanceSetProgress(checkboxProgress, checkboxTargets, dtSec, CHECK_SEC)

        // multi-choice selection feedback, keyed by "identityHash(value):tag"
        val pickTargets = HashSet<String>()
        for (v in openDropdowns) {
            if (v is MultiChoiceListValue<*>) {
                val prefix = System.identityHashCode(v).toString() + ":"
                for (tagged in v.get()) pickTargets += prefix + tagged.tag
            }
        }
        // picker entry rows feed their own keys during the layout passes (drawEntryRow)
        pickTargets += registryPickTargets
        advanceSetProgress(listPickProgress, pickTargets, dtSec, PICK_SEC)

        // dropdown item hover (only one item can be hovered at a time)
        val hoverKey = hoveredListKey
        advanceSetProgress(listHoverProgress, if (hoverKey == null) emptySet() else setOf(hoverKey),
            dtSec, HOVER_SEC)

        // category strip icons: seeded AT their target (no intro flash), never dropped
        for (category in ModuleCategories.entries) {
            categoryIconProgress.putIfAbsent(category, catIconTarget(category))
        }
        categoryIconProgress.putIfAbsent(GUI_CATEGORY, catIconTarget(GUI_CATEGORY))
        categoryIconProgress.putIfAbsent(SETTINGS_CATEGORY, catIconTarget(SETTINGS_CATEGORY))
        for (entry in categoryIconProgress) {
            val next = moveToward(entry.value, catIconTarget(entry.key), dtSec / CAT_ICON_SEC)
            if (next != entry.value) entry.setValue(next)
        }

        // scalar micro-lerps
        searchFocusT = moveToward(searchFocusT, if (searchFocused) 1f else 0f, dtSec / FOCUS_SEC)
        bindCaptureT = moveToward(bindCaptureT, if (pendingBind != null) 1f else 0f, dtSec / BIND_SEC)
    }

    private fun drawGui(ctx: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        // per-frame animation feed is rebuilt here (consumed by the NEXT frame's advance)
        groupTargets.clear()
        checkboxTargets.clear()
        hoveredListKey = null
        registryPickTargets.clear()
        listScrollRegions.clear()

        // category switch content intro: colors fade + cards rise 6 units (no crossfade)
        introE = easeInOutCubic((System.currentTimeMillis() - categorySwitchedAt) / INTRO_MS)

        // Fixed centered window - Leet look: the GUI is a rectangle, not fullscreen.
        // Clamped: on a tiny window the size must never go negative.
        val windowW = minOf(WINDOW_W, width - 16f).coerceAtLeast(1f)
        val windowH = minOf(WINDOW_H, height - 16f).coerceAtLeast(1f)
        val guiX = (width - windowW) / 2f
        val guiY = (height - windowH) / 2f
        val mainX = guiX + RAIL + GAP
        val mainW = windowW - RAIL - GAP

        // computed once per frame, shared by the header ("N modules found") and the cards
        val modules = visibleModules()

        // Visuals preview: a detached window beside the GUI. Drawn BEFORE the backdrop so the
        // main plate overlaps its tucked inner edge (it sits behind the GUI, not inside it).
        if (LeetClickGuiSettings.previewEnabled &&
            selectedCategory === ModuleCategories.RENDER && searchQuery.isBlank()
        ) {
            drawPreviewWindow(ctx, guiX, guiY, windowW, windowH, mouseX, mouseY)
        }

        // neutral backdrop panel under the whole GUI window
        val pad = 9f
        ctx.drawRoundedRect(
            guiX - pad, guiY - pad,
            guiX + windowW + pad, guiY + windowH + pad,
            12f, Theme.backdrop, Theme.backdropBorder, 1f,
        )

        // Dedicated logo slot above the category strip (not part of it), beside the header.
        drawLogoSlot(ctx, guiX, guiY)
        drawHeader(ctx, mainX, guiY, mainW, modules.size)

        // The vertical category strip starts below the header line.
        val railTop = guiY + HEADER_H + GAP
        drawCategoryStrip(ctx, guiX, railTop, mouseX, mouseY)

        // - Scrollable columns (fixed-height viewport) -
        viewTop = railTop
        viewBottom = guiY + windowH
        val viewH = viewBottom - viewTop
        val colW = (mainW - COL_GAP) / 2f

        // Stable columns: even index - left, odd index - right. Expanding a card only
        // pushes the cards below it inside the same column.
        val count = modules.size
        val heights = FloatArray(count) { measureCard(modules[it], colW) }
        val placeX = FloatArray(count)
        val placeY = FloatArray(count)
        var leftY = 0f
        var rightY = 0f
        for (i in 0 until count) {
            if (i % 2 == 0) {
                placeX[i] = mainX
                placeY[i] = leftY
                leftY += heights[i] + COL_GAP
            } else {
                placeX[i] = mainX + colW + COL_GAP
                placeY[i] = rightY
                rightY += heights[i] + COL_GAP
            }
        }
        // measured at y=0; the draw pass re-lays the pane at its scrolled position
        contentHeight = layoutVirtualPane(null, mainX, 0f, mainW, mouseX, mouseY)
            ?: maxOf(leftY, rightY)
        val maxScroll = (contentHeight - viewH).coerceAtLeast(0f)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        // Smooth scroll: exponential approach (~150 ms settle). Within one device pixel of
        // the target, scrollY snaps to the device-pixel grid so resting text stays crisp
        // (same atRest/1/dpr idea as the zoom translation snap above).
        scrollY = scrollY.coerceIn(0f, maxScroll)
        val scrollDiff = scrollTarget - scrollY
        val scrollGrid = dpr * curScale
        scrollY = if (abs(scrollDiff) * scrollGrid <= 1f) {
            if (scrollGrid > 0f) Math.round(scrollTarget * scrollGrid) / scrollGrid else scrollTarget
        } else {
            scrollY + scrollDiff * (1f - exp(-frameDt / SCROLL_TAU))
        }

        // Scissor operates in real (untransformed) screen coordinates. floor(origin) /
        // ceil(end) so no pixel row is lost to truncation at fractional boundaries.
        val sx0 = floor(toRealX(mainX).toDouble())
        val sy0 = floor(toRealY(viewTop).toDouble())
        val viewRect = ScreenRectangle(
            sx0.toInt(), sy0.toInt(),
            (ceil(toRealX(mainX + mainW).toDouble()) - sx0).toInt(),
            (ceil(toRealY(viewBottom).toDouble()) - sy0).toInt(),
        )
        ctx.scissorStack.push(viewRect)
        try {
            val introOff = (1f - introE) * 6f
            if (layoutVirtualPane(ctx, mainX, viewTop - scrollY + introOff, mainW, mouseX, mouseY) == null) {
                for (i in 0 until count) {
                    val y = viewTop + placeY[i] - scrollY
                    val h = heights[i]
                    if (y + h < viewTop - 8f || y > viewBottom + 8f) continue
                    drawCard(ctx, modules[i], placeX[i], y + introOff, colW, h, mouseX, mouseY)
                }
            }
        } finally {
            ctx.scissorStack.pop()
        }
    }

    private fun visibleModules(): List<ClientModule> {
        val q = searchQuery.trim()
        // NOTE: `hidden` only hides a module from the on-screen ArrayList - the ClickGUI
        // must list every module (HUD, HudTheme, ...), so no `hidden` filter here.
        return if (q.isNotEmpty()) {
            ModuleManager.filter { it.name.contains(q, ignoreCase = true) }
        } else {
            ModuleManager.filter { it.category == selectedCategory }
        }
    }

    // - Logo slot + vertical category strip -

    private fun drawLogoSlot(ctx: GuiGraphicsExtractor, x: Float, y: Float) {
        ctx.drawRoundedRect(x, y, x + RAIL, y + HEADER_H, RAIL_RADIUS, Theme.card, null)
        val text = "LB"
        val tw = textWidth(WatermarkFont.BRAND, text, FONT_NAME)
        val th = lineHeight(WatermarkFont.BRAND, FONT_NAME)
        drawText(
            ctx, WatermarkFont.BRAND, text,
            x + (RAIL - tw) / 2f, y + (HEADER_H - th) / 2f, FONT_NAME, Theme.lav,
        )
    }

    /** All category icons merged into ONE vertical strip; the icon (not the cell) carries the accent. */
    private fun drawCategoryStrip(ctx: GuiGraphicsExtractor, x: Float, y: Float, mx: Float, my: Float) {
        val entries = ModuleCategories.entries
        // +2: the two virtual categories pinned to the bottom of the strip (client settings,
        // then this GUI's own settings)
        val stripH = (entries.size + 2) * RAIL_CELL
        ctx.drawRoundedRect(x, y, x + RAIL, y + stripH, RAIL_RADIUS, Theme.card, null)

        hoveredCategory = null
        var cy = y
        for (category in entries) {
            drawRailCell(ctx, category, styledIconName(category.tag.lowercase(), category.tag), x, cy, mx, my)
            cy += RAIL_CELL
        }
        // Client settings. A FLAT icon on purpose: the styled sets hold exactly one gear
        // ("settings"), which the GUI cell below already uses, and two identical glyphs
        // would read as one tab drawn twice.
        drawRailCell(ctx, SETTINGS_CATEGORY, "mursultan/client-settings", x, cy, mx, my)
        cy += RAIL_CELL
        // the gear follows the "gui" entry of the icon-style map (asset tag "settings")
        drawRailCell(ctx, GUI_CATEGORY, styledIconName("settings", GUI_CATEGORY.tag), x, cy, mx, my)
    }

    /** One rail cell: hover feed, animated highlight + icon tint, and the select hitbox. */
    private fun drawRailCell(
        ctx: GuiGraphicsExtractor, category: ModuleCategory, iconName: String,
        x: Float, cy: Float, mx: Float, my: Float,
    ) {
        val hovered = mx >= x && mx <= x + RAIL && my >= cy && my <= cy + RAIL_CELL
        if (hovered) hoveredCategory = category

        // icon color + cell highlight lerp: dim -> white (hover, 0.5) -> lav (active, 1)
        val e = easeInOutCubic(categoryIconProgress[category] ?: catIconTarget(category))
        if (e > 0.01f) {
            ctx.drawRoundedRect(
                x + 3f, cy + 3f, x + RAIL - 3f, cy + RAIL_CELL - 3f,
                6f, Theme.stripActive.alphaMul(e), null,
            )
        }
        val iconColor = if (e <= 0.5f) {
            lerpColor(Theme.dim, Theme.white, e * 2f)
        } else {
            lerpColor(Theme.white, Theme.lav, (e - 0.5f) * 2f)
        }
        drawIcon(ctx, iconName, x + RAIL / 2f, cy + RAIL_CELL / 2f, 16f, iconColor)

        hits += Hit(x, cy, x + RAIL, cy + RAIL_CELL, inScroll = false) { button ->
            if (button == 0) {
                selectedCategory = category
                searchQuery = ""
                searchFocused = false
                scrollY = 0f
                scrollTarget = 0f
                categorySwitchedAt = System.currentTimeMillis()
                true
            } else false
        }
    }

    /**
     * Edge vignette (Leet reference: the page darkens towards the borders).
     * Four gradient strips: opaque-ish dark at the screen edge - transparent inwards.
     */
    private fun drawVignette(ctx: GuiGraphicsExtractor, motion: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val edge = ARGB.color((150 * motion).toInt().coerceIn(0, 255), 6, 6, 8)
        val mid = ARGB.color(0, 6, 6, 8)
        val sizeX = w * 0.18f
        val sizeY = h * 0.22f
        ctx.drawCustomElement(bounds = ScreenRectangle(0, 0, width, height)) { pose ->
            // top
            addVertexWith2DPose(pose, 0f, 0f).setColor(edge)
            addVertexWith2DPose(pose, 0f, sizeY).setColor(mid)
            addVertexWith2DPose(pose, w, sizeY).setColor(mid)
            addVertexWith2DPose(pose, w, 0f).setColor(edge)
            // bottom
            addVertexWith2DPose(pose, 0f, h - sizeY).setColor(mid)
            addVertexWith2DPose(pose, 0f, h).setColor(edge)
            addVertexWith2DPose(pose, w, h).setColor(edge)
            addVertexWith2DPose(pose, w, h - sizeY).setColor(mid)
            // left
            addVertexWith2DPose(pose, 0f, 0f).setColor(edge)
            addVertexWith2DPose(pose, 0f, h).setColor(edge)
            addVertexWith2DPose(pose, sizeX, h).setColor(mid)
            addVertexWith2DPose(pose, sizeX, 0f).setColor(mid)
            // right
            addVertexWith2DPose(pose, w - sizeX, 0f).setColor(mid)
            addVertexWith2DPose(pose, w - sizeX, h).setColor(mid)
            addVertexWith2DPose(pose, w, h).setColor(edge)
            addVertexWith2DPose(pose, w, 0f).setColor(edge)
        }
    }

    // - Top bar: category + search -
    private fun drawHeader(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, resultCount: Int) {
        val catW = w - SEARCH_W - GAP

        // category bar
        ctx.drawRoundedRect(x, y, x + catW, y + HEADER_H, RAIL_RADIUS, Theme.card, null)
        val name = when {
            searchQuery.isNotBlank() -> "Search"
            selectedCategory === GUI_CATEGORY -> "GUI"
            else -> selectedCategory.tag
        }
        val desc = if (searchQuery.isNotBlank()) {
            "$resultCount modules found."
        } else {
            CATEGORY_DESC[selectedCategory.tag] ?: ""
        }
        val nameH = lineHeight(WatermarkFont.BRAND, FONT_TITLE)
        drawText(
            ctx, WatermarkFont.BRAND, name,
            x + 12f, y + (HEADER_H - nameH) / 2f, FONT_TITLE, Theme.white.alphaMul(introE),
        )

        val descW = textWidth(WatermarkFont.TEXT, desc, FONT_DESC)
        val descH = lineHeight(WatermarkFont.TEXT, FONT_DESC)
        drawText(
            ctx, WatermarkFont.TEXT, desc,
            x + catW - 12f - descW, y + (HEADER_H - descH) / 2f, FONT_DESC, Theme.dim.alphaMul(introE),
        )

        // search bar (focus border lerps rowBorder -> lav)
        val sx = x + catW + GAP
        val outline = lerpColor(Theme.rowBorder, Theme.lav, easeInOutCubic(searchFocusT))
        ctx.drawRoundedRect(sx, y, sx + SEARCH_W, y + HEADER_H, RAIL_RADIUS, Theme.card, outline, 1f)

        val caret = if (searchFocused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        val text = if (searchQuery.isEmpty() && !searchFocused) "Search" else searchQuery + caret
        val tColor = if (searchQuery.isEmpty() && !searchFocused) Theme.dim else Theme.white
        val tH = lineHeight(WatermarkFont.TEXT, FONT_SEARCH)
        drawIcon(ctx, "search", sx + 14f, y + HEADER_H / 2f, 11f, Theme.dim)
        drawText(ctx, WatermarkFont.TEXT, text, sx + 23f, y + (HEADER_H - tH) / 2f, FONT_SEARCH, tColor)

        hits += Hit(sx, y, sx + SEARCH_W, y + HEADER_H, inScroll = false) { button ->
            if (button == 0) {
                searchFocused = true
                commitEdit()
                pendingBind = null
                true
            } else false
        }
    }

    // - Visuals preview window (Render category) -

    /**
     * Detached preview window beside the main GUI. Drawn BEFORE the backdrop so the main
     * window plate overlaps its inner edge ([PREVIEW_TUCK]) and it reads as a separate panel
     * sitting behind the GUI rather than a pane inside it.
     *
     * Shows the stand-in player from [VisualsPreview] with the render modules applied, a
     * quick-toggle strip for every module that draws on players, and the nametag Y offset.
     * Nothing here scrolls — the window is pinned to the GUI, so all hits are `inScroll=false`.
     */
    private fun drawPreviewWindow(
        ctx: GuiGraphicsExtractor,
        guiX: Float, guiY: Float, windowW: Float, windowH: Float,
        mx: Float, my: Float,
    ) {
        val pad = 9f
        val x0 = guiX + windowW + pad - PREVIEW_TUCK
        val x1 = x0 + PREVIEW_W
        val y0 = guiY - pad
        val y1 = guiY + windowH + pad
        ctx.drawRoundedRect(x0, y0, x1, y1, 12f, Theme.backdrop, Theme.backdropBorder, 1f)

        // content column starts past the tucked-under edge
        val cx = x0 + PREVIEW_TUCK + PREVIEW_PAD
        val cw = x1 - PREVIEW_PAD - cx
        var cy = y0 + PREVIEW_PAD

        val titleH = lineHeight(WatermarkFont.BRAND, FONT_SET)
        drawText(ctx, WatermarkFont.BRAND, "PREVIEW", cx, cy, FONT_SET, Theme.white.alphaMul(introE))
        cy += titleH + SET_GAP

        // - stage: the stand-in player WITH the live render modules applied -
        // Rendered by VisualsPreviewScene into its own target (the GUI is deferred, the
        // modules draw immediately - see that class), then blitted here as a GUI element.
        val stageH = PREVIEW_STAGE_H
        ctx.drawRoundedRect(cx, cy, cx + cw, cy + stageH, CARD_RADIUS, Theme.card, Theme.rowBorder, 1f)
        val yaw = ((mx - (cx + cw / 2f)) / cw * 90f).coerceIn(-60f, 60f)
        val pitch = ((my - (cy + stageH / 2f)) / stageH * 40f).coerceIn(-25f, 25f)
        val scene = VisualsPreviewScene.render(
            (cw * density).roundToInt(), (stageH * density).roundToInt(), yaw, pitch,
        )
        if (scene != null) {
            // V is flipped: the scene renders with a standard GL projection (row 0 is the
            // BOTTOM of the image) while the GUI samples v=0 at the top.
            ctx.drawTexQuad(scene, cx, cy, cx + cw, cy + stageH, u1 = 0f, v1 = 1f, u2 = 1f, v2 = 0f)
            // Screen-space modules draw OVER the blitted stage, in GUI space, the same way
            // they draw over the world.
            drawPreviewEsp2D(ctx, cx, cy, cw, stageH)
            drawPreviewNametag(ctx, cx, cy, cw, stageH)
        } else {
            drawCenteredHint(ctx, "no preview", cx, cy, cw, stageH)
        }
        cy += stageH + SET_GAP

        // - nametag Y offset -
        // The strip follows on the strip's own row rhythm, not SET_GAP: the toggles read as
        // one block hanging directly off the slider.
        // NOTE: the setting widgets return the ABSOLUTE next Y, not a height - assigning, not
        // accumulating, is what keeps the strip tight under the slider.
        cy = drawNativeSlider(
            ctx, "Distance", LeetClickGuiSettings.previewDistance,
            LeetClickGuiSettings.PREVIEW_DISTANCE_MIN, LeetClickGuiSettings.PREVIEW_DISTANCE_MAX,
            cx, cy, cw,
        ) { LeetClickGuiSettings.previewDistance = it } + PREVIEW_ROW_GAP
        cy = drawSlider(ctx, ModuleNametags.verticalOffsetValue, cx, cy, cw) + PREVIEW_ROW_GAP

        // - quick toggles: everything that draws on players -
        for (module in VisualsPreview.toggleModules) {
            drawPreviewToggle(ctx, module, cx, cy, cw, mx, my)
            cy += PREVIEW_ROW_H + PREVIEW_ROW_GAP
        }
    }

    /**
     * ESP's 2D mode draws in GUI space from a projected screen rect, so like the nametag it
     * sits outside the offscreen scene. The rect comes from the scene's own matrices instead
     * of `WorldToScreen`, which reads the main camera.
     */
    private fun drawPreviewEsp2D(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        if (!ModuleESP.running || ModuleESP.modes.activeMode !== Esp2DMode) {
            return
        }
        val entity = VisualsPreview.previewPlayer() ?: return
        val rect = VisualsPreviewScene.projectBoxToStage(entity, x, y, w, h) ?: return
        Esp2DMode.drawPreview(ctx, entity, rect)
    }

    /**
     * The nametag is NOT part of the offscreen scene: it draws in GUI space, so it is placed
     * here, over the blitted stage. The anchor is projected with the scene's own matrices
     * (the module's world path reads the main camera, which the scene does not share) and the
     * same eye-height + 0.55 + VerticalOffset formula the in-world tag uses — so dragging the
     * slider moves it here exactly as it moves in game.
     */
    private fun drawPreviewNametag(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        if (!ModuleNametags.running) {
            return
        }
        val entity = VisualsPreview.previewPlayer() ?: return
        val anchorY = entity.getEyeHeight(entity.pose) + 0.55f + ModuleNametags.verticalOffset
        val at = VisualsPreviewScene.projectToStage(0f, anchorY, 0f, x, y, w, h) ?: return
        // Clipped to the stage: a tag pushed far up by the offset must not escape the panel.
        if (at[1] < y - 4f || at[1] > y + h + 4f) {
            return
        }
        ModuleNametags.drawPreviewNametag(ctx, entity, at[0], at[1], PREVIEW_NAMETAG_SCALE)
    }

    /**
     * Compact slider for a NATIVE preview setting (no backing [Value] — this lives in
     * `clickgui-native.json`, per LEET_STYLE §11). Same block shape as [drawSlider]: value text
     * in the upper half, track in the lower half, nothing leaking past the row.
     *
     * Returns the absolute next Y, matching the convention of the setting widgets.
     */
    private fun drawNativeSlider(
        ctx: GuiGraphicsExtractor, label: String,
        value: Float, min: Float, max: Float,
        x: Float, y: Float, w: Float,
        apply: (Float) -> Unit,
    ): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        val rowH = nameH + 7f

        val text = "%.1f".format(value)
        drawText(ctx, WatermarkFont.TEXT, label, x, y, FONT_CTRL, Theme.desc)
        val tw = textWidth(WatermarkFont.TEXT, text, FONT_CTRL)
        drawText(ctx, WatermarkFont.TEXT, text, x + w - tw, y, FONT_CTRL, Theme.white)

        val trackY = y + nameH + 3f
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        ctx.drawRoundedRect(x, trackY, x + w, trackY + 2f, 1f, Theme.track, null)
        if (frac > 0f) {
            ctx.drawRoundedRect(x, trackY, x + w * frac, trackY + 2f, 1f, Theme.lav, null)
        }
        ctx.drawRoundedRect(x + w * frac - 1.5f, trackY - 1.5f, x + w * frac + 1.5f, trackY + 3.5f, 2f, Theme.knob, null)

        hits += Hit(x, y, x + w, y + rowH, inScroll = false) { button ->
            if (button == 0) {
                activeSlider = SliderDrag(x, w) { f -> apply(min + (max - min) * f) }
                true
            } else false
        }
        return y + rowH
    }

    private fun drawCenteredHint(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, w: Float, h: Float) {
        val tw = textWidth(WatermarkFont.TEXT, text, FONT_DESC)
        val th = lineHeight(WatermarkFont.TEXT, FONT_DESC)
        drawText(ctx, WatermarkFont.TEXT, text, x + (w - tw) / 2f, y + (h - th) / 2f, FONT_DESC, Theme.dim)
    }

    /** One quick-toggle row: same fill ladder as a module card, plus an accent state dot. */
    private fun drawPreviewToggle(
        ctx: GuiGraphicsExtractor, module: ClientModule,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ) {
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + PREVIEW_ROW_H
        val target = when {
            module.enabled -> if (hovered) Theme.cardEnabledHover else Theme.cardEnabled
            else -> if (hovered) Theme.cardHover else Theme.card
        }
        val fill = watchColor("preview:fill:${module.name}", target)
        ctx.drawRoundedRect(x, y, x + w, y + PREVIEW_ROW_H, CARD_RADIUS, fill, Theme.rowBorder, 1f)

        val dot = watchColor("preview:dot:${module.name}", if (module.enabled) Theme.lav else Theme.dim)
        val cyMid = y + PREVIEW_ROW_H / 2f
        ctx.drawRoundedRect(x + 7f, cyMid - 2f, x + 11f, cyMid + 2f, 2f, dot, null)

        val nameColor = watchColor("preview:text:${module.name}", if (module.enabled) Theme.white else Theme.desc)
        val nh = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        drawText(ctx, WatermarkFont.TEXT, module.name, x + 17f, cyMid - nh / 2f, FONT_CTRL, nameColor)

        hits += Hit(x, y, x + w, y + PREVIEW_ROW_H, inScroll = false) { button ->
            if (button == 0) {
                module.enabled = !module.enabled
                true
            } else false
        }
    }

    // - Module cards -

    private fun measureCard(module: ClientModule, w: Float): Float = layoutCard(null, module, 0f, 0f, w, 0f, 0f)

    private fun drawCard(
        ctx: GuiGraphicsExtractor, module: ClientModule,
        x: Float, y: Float, w: Float, h: Float, mx: Float, my: Float,
    ) {
        // h comes from drawGui's measure pass - no second measure walk here (P1)
        val expanded = module.name in expandedModules
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + h &&
            my >= viewTop && my <= viewBottom
        // four distinct states: disabled / disabled+expanded / enabled / enabled+expanded
        val fill = when {
            module.enabled && expanded -> if (hovered) Theme.cardEnabledOpenHover else Theme.cardEnabledOpen
            module.enabled -> if (hovered) Theme.cardEnabledHover else Theme.cardEnabled
            expanded -> if (hovered) Theme.cardOpenHover else Theme.cardOpen
            else -> if (hovered) Theme.cardHover else Theme.card
        }
        // universal fill lerp: whatever state change altered the resolved color (hover,
        // enable/disable, expand variants) is lerped channel-wise by the watcher
        val animatedFill = watchColor("fill:" + module.name, fill).alphaMul(introE)
        val border = if (expanded) Theme.rowBorder.alphaMul(introE) else null
        ctx.drawRoundedRect(x, y, x + w, y + h, CARD_RADIUS, animatedFill, border, 1f)

        // collapsed card: the WHOLE card is clickable - LMB toggle, RMB expand, MMB bind.
        // Added before the head hits so more specific areas (expand icon) still win.
        if (!expanded) {
            hits += Hit(x, y, x + w, y + h, inScroll = true) { button ->
                moduleCardClick(module, button)
            }
        }

        layoutCard(ctx, module, x, y, w, mx, my)
    }

    /** Shared card click behavior: LMB toggles, RMB expands/collapses, MMB (re)binds. */
    private fun moduleCardClick(module: ClientModule, button: Int): Boolean = when (button) {
        0 -> { module.enabled = !module.enabled; true }
        1 -> { toggleExpanded(module); true }
        2 -> {
            pendingBind = if (pendingBind === module.bindValue) null else module.bindValue
            pendingKey = null
            commitEdit()
            searchFocused = false
            true
        }
        else -> false
    }

    /**
     * Lays out (and optionally draws, when [ctx] != null) one module card.
     * @return the card height.
     */
    @Suppress("LongMethod")
    private fun layoutCard(
        ctx: GuiGraphicsExtractor?, module: ClientModule,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val expanded = module.name in expandedModules
        val rawProgress = expandProgress[module.name] ?: if (expanded) 1f else 0f
        val progress = easeInOutCubic(rawProgress)
        var cy = y + CARD_PAD

        // - Head: name + bind badge + expand icon -
        val nameH = lineHeight(WatermarkFont.BRAND, FONT_NAME)
        val headH = maxOf(nameH, 10f)
        if (ctx != null) {
            // enable/disable text lerp (dim <-> white), driven by the resolved-color watcher
            val nameColor = watchColor(
                "name:" + module.name,
                if (module.enabled || expanded) Theme.white else Theme.dim,
            )
            drawText(
                ctx, WatermarkFont.BRAND, module.name,
                x + CARD_PAD, cy + (headH - nameH) / 2f, FONT_NAME, nameColor,
            )

            // expand icon morph: 1 bar <-> 3 bars, driven by the card's eased expand
            // progress (middle bar fades in, outer bars spread +-2.5 from center)
            val ix = x + w - CARD_PAD - 8f
            val iy = cy + headH / 2f
            val ic = Theme.desc
            val spread = 2.5f * progress
            ctx.drawRoundedRect(ix, iy - spread - 0.5f, ix + 8f, iy - spread + 0.5f, 0.5f, ic, null)
            ctx.drawRoundedRect(ix + 1f, iy - 0.5f, ix + 8f, iy + 0.5f, 0.5f, ic.alphaMul(progress), null)
            ctx.drawRoundedRect(ix, iy + spread - 0.5f, ix + 8f, iy + spread + 0.5f, 0.5f, ic, null)

            // bind badge right before the expand icon ("..." while capturing a new bind)
            val bind = module.bind
            val capturing = pendingBind === module.bindValue
            if (capturing || !bind.isUnbound) {
                val keyText = if (capturing) "..." else bind.keyName
                val ktW = textWidth(WatermarkFont.TEXT, keyText, FONT_CTRL - 0.5f)
                val badgeW = ktW + 8f
                val badgeH = 11f
                val bdx = ix - 8f - badgeW
                val bdy = iy - badgeH / 2f
                // capture feedback: badge bg/fg lerp lavDark <-> lav
                val bindE = easeInOutCubic(bindCaptureT)
                val bg = lerpColor(Theme.lavDark, Theme.lav, bindE)
                val fg = lerpColor(Theme.lav, Theme.lavDark, bindE)
                ctx.drawRoundedRect(bdx, bdy, bdx + badgeW, bdy + badgeH, 3.5f, bg, null)
                val ktH = lineHeight(WatermarkFont.TEXT, FONT_CTRL - 0.5f)
                drawText(
                    ctx, WatermarkFont.TEXT, keyText,
                    bdx + 4f, bdy + (badgeH - ktH) / 2f, FONT_CTRL - 0.5f, fg,
                )
            }

            // head hitboxes: LMB toggles, RMB expands, MMB binds; icon area expands
            hits += Hit(x, cy - CARD_PAD, x + w - CARD_PAD - 14f, cy + headH, inScroll = true) { button ->
                moduleCardClick(module, button)
            }
            hits += Hit(x + w - CARD_PAD - 14f, cy - CARD_PAD, x + w, cy + headH, inScroll = true) { button ->
                if (button == 0 || button == 1) { toggleExpanded(module); true } else false
            }
        }
        cy += headH

        // - Description -
        val desc = module.description.get()
        if (!desc.isNullOrBlank()) {
            cy += SET_GAP
            val lineH = lineHeight(WatermarkFont.TEXT, FONT_DESC) + 1.5f
            for (line in wrapText(desc, FONT_DESC, w - CARD_PAD * 2)) {
                if (ctx != null) {
                    // enable/disable text lerp (dim <-> desc), same watcher as the name
                    val descColor = watchColor(
                        "desc:" + module.name,
                        if (module.enabled || expanded) Theme.desc else Theme.dim,
                    )
                    drawText(ctx, WatermarkFont.TEXT, line, x + CARD_PAD, cy, FONT_DESC, descColor)
                }
                cy += lineH
            }
        }

        // - Settings (animated expand/collapse) -
        if (progress > 0.001f) {
            // uniform offsets: SET_GAP above the block, SET_GAP between settings
            // (added by layoutSetting) and the card's own padding below it
            val innerEnd = layoutSettings(null, module, x + CARD_PAD, cy + SET_GAP, w - CARD_PAD * 2, mx, my)
            val fullH = SET_GAP + (innerEnd - (cy + SET_GAP)) - SET_GAP // strip the trailing gap
            val shownH = fullH * progress

            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(cy).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(cy + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits registered while the animation still scissors the card are only
                    // clickable above the animated clip bottom (mouseClicked filters on it)
                    val hitMark = hits.size
                    layoutSettings(ctx, module, x + CARD_PAD, cy + SET_GAP, w - CARD_PAD * 2, mx, my)
                    if (progress < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = cy + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy += shownH
        }

        return (cy - y) + CARD_PAD
    }

    private fun toggleExpanded(module: ClientModule) {
        if (!expandedModules.add(module.name)) expandedModules.remove(module.name)
    }

    // - Virtual GUI category pane (settings of the native GUI itself) -

    /** One row of the "Category icons" section: which style key, which assets, which label. */
    private class IconStyleRow(styleKey: String, val assetTag: String, val label: String) {
        val styleKey = styleKey.lowercase()

        /** Stable identity key for the dropdown open-state maps (one instance per row). */
        val rowKey = "gui-style:${this.styleKey}"
    }

    private val iconStyleRows: List<IconStyleRow> = buildList {
        for (category in ModuleCategories.entries) {
            add(IconStyleRow(category.tag, category.tag.lowercase(), category.tag))
        }
        // the virtual category's own gear (asset tag "settings") is styleable too
        add(IconStyleRow(GUI_CATEGORY.tag, "settings", "GUI"))
    }

    private val iconStyleNames: List<String> = LeetClickGuiSettings.ICON_STYLES.map { it.second }

    /**
     * The pane is a plain list of sections rendered sequentially - a new setting is one
     * more entry here, no layout code to touch.
     */
    private val guiSettingEntries: List<GuiSettingEntry> = listOf(
        GuiSettingEntry("Category icons") { ctx, x, y, w, mx, my ->
            var cy = y
            for (row in iconStyleRows) {
                cy = drawIconStyleRow(ctx, row, x, cy, w, mx, my) + GUI_ROW_GAP
            }
            cy - GUI_ROW_GAP
        },
        GuiSettingEntry("Visuals preview") { ctx, x, y, w, mx, my ->
            var cy = guiToggleCard(
                ctx, "preview-enabled", "render", "Show preview",
                LeetClickGuiSettings.previewEnabled, x, cy0(y), w, mx, my,
            ) { LeetClickGuiSettings.previewEnabled = !LeetClickGuiSettings.previewEnabled }
            cy += GUI_ROW_GAP
            cy = guiSliderCard(
                ctx, "preview-distance", "render", "Distance",
                LeetClickGuiSettings.previewDistance,
                LeetClickGuiSettings.PREVIEW_DISTANCE_MIN, LeetClickGuiSettings.PREVIEW_DISTANCE_MAX,
                x, cy, w, mx, my,
            ) { LeetClickGuiSettings.previewDistance = it }
            cy
        },
    )

    /** Identity helper so the entry bodies read uniformly (the walker hands in the start Y). */
    private fun cy0(y: Float) = y

    /**
     * GUI-pane row with a checkbox on the right. Same plate, icon and label treatment as
     * [drawIconStyleRow]; only the control differs.
     */
    @Suppress("LongParameterList")
    private fun guiToggleCard(
        ctx: GuiGraphicsExtractor?, key: String, icon: String, label: String,
        checked: Boolean, x: Float, y: Float, w: Float, mx: Float, my: Float,
        onToggle: () -> Unit,
    ): Float {
        val end = guiSettingCard(ctx, key, x, y, w, mx, my, GUI_ROW_H) { c, innerX, centerY, innerW ->
            drawIcon(c, styledIconNameFor(icon, LeetClickGuiSettings.styleFor("gui")), innerX + 5.5f, centerY, 11f, Theme.lav)
            val lh = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(c, WatermarkFont.TEXT, label, innerX + 16f, centerY - lh / 2f, FONT_CTRL, Theme.desc)

            // The checkbox progress maps are keyed by Value identity, and GUI-pane rows own no
            // Value — so the toggle animates through the string-keyed colour watcher instead.
            val box = 11f
            val bx = innerX + innerW - box
            c.drawRoundedRect(
                bx, centerY - box / 2f, bx + box, centerY + box / 2f, 3f,
                watchColor("gui-check:$key", if (checked) Theme.lav else Theme.badge),
                watchColor("gui-check-border:$key", if (checked) Theme.lav else Theme.rowBorder),
                1f,
            )
        }
        if (ctx != null) {
            hits += Hit(x, y, x + w, end, inScroll = true) { button ->
                if (button == 0) { onToggle(); true } else false
            }
        }
        return end
    }

    /** GUI-pane row with a compact slider on the right (fixed [GUI_FIELD_W] control column). */
    @Suppress("LongParameterList")
    private fun guiSliderCard(
        ctx: GuiGraphicsExtractor?, key: String, icon: String, label: String,
        value: Float, min: Float, max: Float,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
        apply: (Float) -> Unit,
    ): Float {
        var trackX = 0f
        var trackW = 0f
        val end = guiSettingCard(ctx, key, x, y, w, mx, my, GUI_ROW_H) { c, innerX, centerY, innerW ->
            drawIcon(c, styledIconNameFor(icon, LeetClickGuiSettings.styleFor("gui")), innerX + 5.5f, centerY, 11f, Theme.lav)
            val lh = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(c, WatermarkFont.TEXT, label, innerX + 16f, centerY - lh / 2f, FONT_CTRL, Theme.desc)

            val text = "%.1f".format(value)
            val tw = textWidth(WatermarkFont.TEXT, text, FONT_CTRL)
            drawText(c, WatermarkFont.TEXT, text, innerX + innerW - tw, centerY - lh / 2f, FONT_CTRL, Theme.white)

            trackW = GUI_FIELD_W - tw - 6f
            trackX = innerX + innerW - tw - 6f - trackW
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
            c.drawRoundedRect(trackX, centerY - 1f, trackX + trackW, centerY + 1f, 1f, Theme.track, null)
            if (frac > 0f) {
                c.drawRoundedRect(trackX, centerY - 1f, trackX + trackW * frac, centerY + 1f, 1f, Theme.lav, null)
            }
            val kx = trackX + trackW * frac
            c.drawRoundedRect(kx - 1.5f, centerY - 3f, kx + 1.5f, centerY + 3f, 2f, Theme.knob, null)
        }
        if (ctx != null) {
            hits += Hit(trackX - 4f, y, trackX + trackW + 4f, end, inScroll = true) { button ->
                if (button == 0) {
                    activeSlider = SliderDrag(trackX, trackW) { f -> apply(min + (max - min) * f) }
                    true
                } else false
            }
        }
        return end
    }

    /** One section of the virtual GUI pane: a label with a body laid out below it. */
    private inner class GuiSettingEntry(
        val title: String,
        val body: (GuiGraphicsExtractor?, Float, Float, Float, Float, Float) -> Float,
    ) {
        fun layout(ctx: GuiGraphicsExtractor?, x: Float, y: Float, w: Float, mx: Float, my: Float): Float {
            val headEnd = drawLabel(ctx, title, x, y, w)
            // drawLabel leaves 3 under the text; top the gap up to the uniform SET_GAP rhythm
            return body(ctx, x, headEnd + SET_GAP - 3f, w, mx, my)
        }
    }

    /**
     * The content pane of a virtual rail category, or null when the module cards are what
     * belongs in the viewport (a search query always wins - its results ARE module cards).
     *
     * Measures when [ctx] == null, exactly like the two panes it dispatches to, so the
     * caller can use it for the content height and then again for the draw.
     */
    private fun layoutVirtualPane(
        ctx: GuiGraphicsExtractor?, x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float? = when {
        searchQuery.isNotBlank() -> null
        // this GUI's own look
        selectedCategory === GUI_CATEGORY -> layoutGuiPane(ctx, x, y, w, mx, my)
        // the client's own settings: the whole GlobalManager tree through the ordinary
        // walker (those ARE real Values, so every identity-keyed animation map just works)
        selectedCategory === SETTINGS_CATEGORY -> layoutSettings(ctx, GlobalManager, x, y, w, mx, my)
        else -> null
    }

    /**
     * Lays out the virtual GUI pane (measures when [ctx] == null - identical heights in
     * both passes, same contract as the module settings walker). Returns the end y.
     */
    private fun layoutGuiPane(
        ctx: GuiGraphicsExtractor?, x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float {
        var y = yStart
        for (entry in guiSettingEntries) {
            y = entry.layout(ctx, x, y, w, mx, my) + SET_GAP
        }
        return y - SET_GAP
    }

    /**
     * The plate every virtual-pane setting sits on: a full-width rounded card in the
     * disabled-module-card fill (radius [CARD_RADIUS], [GUI_PAD] side padding), with the
     * hover fill lerp done by the resolved-color watcher exactly like module cards.
     * [contentH] must be identical in both passes (measure/draw contract); the body runs
     * in the draw pass only and receives the inner X, the vertical center, the inner width.
     */
    @Suppress("LongParameterList")
    private fun guiSettingCard(
        ctx: GuiGraphicsExtractor?, cardKey: String,
        x: Float, y: Float, w: Float, mx: Float, my: Float, contentH: Float,
        body: (ctx: GuiGraphicsExtractor, innerX: Float, centerY: Float, innerW: Float) -> Unit,
    ): Float {
        if (ctx != null) {
            val hovered = mx >= x && mx <= x + w && my >= y && my <= y + contentH &&
                my >= viewTop && my <= viewBottom
            val fill = watchColor("gui-card:$cardKey", if (hovered) Theme.cardHover else Theme.card)
            ctx.drawRoundedRect(x, y, x + w, y + contentH, CARD_RADIUS, fill, null)
            body(ctx, x + GUI_PAD, y + contentH / 2f, w - GUI_PAD * 2)
        }
        return y + contentH
    }

    /**
     * Icon-style selector card: the lavender category icon + the category name (dim label
     * style) on the left, a FIXED-width style field on the right (RMB expands the style
     * list under the field, LMB picks, list closes). Mirrors [drawDropdownRow], but the
     * open state is keyed by the row's stable string key instead of a Value identity
     * (these rows own no Value objects). Every list item previews the row's category icon
     * in that item's style.
     */
    @Suppress("LongMethod")
    private fun drawIconStyleRow(
        ctx: GuiGraphicsExtractor?, row: IconStyleRow,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val currentName = LeetClickGuiSettings.displayName(LeetClickGuiSettings.styleFor(row.styleKey))
        val t = easeInOutCubic(dropdownProgress[row.rowKey] ?: 0f)

        val nameH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        val icon = 11f
        val fieldH = 12f
        val fieldW = minOf(GUI_FIELD_W, (w - GUI_PAD * 2) * 0.6f)

        var cy = guiSettingCard(ctx, row.rowKey, x, y, w, mx, my, GUI_ROW_H) { c, innerX, centerY, innerW ->
            // live styled icon (lavender accent): exactly the icon the rail shows for this
            // category, drawn directly on the card - no plate
            drawIcon(c, styledIconName(row.assetTag, row.styleKey), innerX + icon / 2f, centerY, icon, Theme.lav)
            drawText(c, WatermarkFont.TEXT, row.label, innerX + icon + 5f, centerY - nameH / 2f,
                FONT_CTRL, Theme.desc)

            // compact style field, right-aligned and vertically centered in the card
            val fx = innerX + innerW - fieldW
            val fy = centerY - fieldH / 2f
            val outline = lerpColor(Theme.rowBorder, Theme.lav, t)
            c.drawRoundedRect(fx, fy, fx + fieldW, fy + fieldH, 4f, Theme.listBg, outline, 1f)

            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(c, WatermarkFont.TEXT, currentName, fx + 6f, fy + (fieldH - textH) / 2f, FONT_CTRL, Theme.white)

            // chevron morph: vertex-lerp between the down (t=0) and up (t=1) triangle
            val chx = fx + fieldW - 9f
            val sideY = centerY - 1.5f + 3f * t
            val tipY = centerY + 2f - 4f * t
            c.drawTriangle(chx - 3f, sideY, chx + 3f, sideY, chx, tipY, Theme.desc)

            hits += Hit(fx, fy, fx + fieldW, fy + fieldH, inScroll = true) { button ->
                if (button == 1) {
                    if (!openDropdowns.add(row.rowKey)) openDropdowns.remove(row.rowKey)
                    true
                } else false
            }
        }

        if (t > 0.001f) {
            val listTop = cy + 3f * t
            val fullH = iconStyleNames.size * 10f + 6f // compact select list: rows + 2 * pad
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(listTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(listTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits below the animated scissor bottom are not clickable yet
                    val hitMark = hits.size
                    // w - GUI_PAD right-aligns the list with the field inside the card
                    drawSelectList(
                        ctx, x, listTop, w - GUI_PAD, mx, my, iconStyleNames,
                        isActive = { it == currentName },
                        onPick = {
                            LeetClickGuiSettings.setStyle(row.styleKey, LeetClickGuiSettings.styleId(it))
                            openDropdowns.remove(row.rowKey)
                        },
                        borderT = t, keyPrefix = row.rowKey + ":", multi = false,
                        listWidth = fieldW, rowHeight = 10f,
                        // live preview: the row's category icon rendered in that item's style
                        itemIcon = { c, item, icx, icy ->
                            val styleId = LeetClickGuiSettings.styleId(item)
                            drawIcon(c, styledIconNameFor(row.assetTag, styleId), icx, icy, 9f, Theme.lav)
                        },
                    )
                    if (t < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = listTop + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy = listTop + shownH
        }
        return cy
    }

    // - Settings walker -

    /** Renders all settings of [group] and returns the new y. */
    private fun layoutSettings(
        ctx: GuiGraphicsExtractor?, group: ValueGroup,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float, depth: Int = 0,
    ): Float {
        var y = yStart
        for (value in group.inner) {
            if (value.name == "Enabled" || value.name == "Hidden") continue
            if (value.notAnOption) continue
            y = layoutSetting(ctx, value, x, y, w, mx, my, depth)
        }
        return y
    }

    private fun hasVisibleSettings(group: ValueGroup): Boolean =
        group.inner.any { it.name != "Enabled" && it.name != "Hidden" && !it.notAnOption }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun layoutSetting(
        ctx: GuiGraphicsExtractor?, value: Value<*>,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float, depth: Int,
    ): Float {
        var y = yStart
        when (value) {
            is ModeValueGroup<*> -> {
                val activeTag = value.activeMode.tag
                val t = easeInOutCubic(dropdownProgress[value] ?: 0f)
                y = drawDropdownRow(
                    ctx, value.name, activeTag, t, x, y, w, mx, my, value,
                    items = value.modes.map { it.tag }, multi = false,
                    isActive = { it == activeTag },
                    onToggle = { if (!openDropdowns.add(value)) openDropdowns.remove(value) },
                    onPick = { value.setByString(it); openDropdowns.remove(value) },
                )
                // nested settings of the active mode, in a subtle rounded container
                val active = value.activeMode
                if (hasVisibleSettings(active)) {
                    y += 2f
                    y = layoutGroupBox(ctx, x, y, w) { c, cx, cy, cw ->
                        layoutSettings(c, active, cx, cy, cw, mx, my, depth + 1)
                    }
                }
            }

            is ToggleableValueGroup -> {
                // group-box reveal: children are measured once, then drawn under a
                // scissor of shownH = fullH * ease(t); the border rect rides the reveal
                val reveal = easeInOutCubic(groupProgress[value] ?: 0f)
                if (value.enabled && hasVisibleSettings(value)) groupTargets += value
                y = drawCheckboxRow(
                    ctx, value.name, value.enabled, x, y, w,
                    boxed = true, checkKey = value,
                ) {
                    value.enabled = !value.enabled
                }
                if (reveal > 0.001f && hasVisibleSettings(value)) {
                    val boxTop = y + 2f * reveal
                    val innerEnd = layoutGroupBox(null, x, boxTop, w) { c, cx, cy, cw ->
                        layoutSettings(c, value, cx, cy, cw, mx, my, depth + 1)
                    }
                    val shownH = (innerEnd - boxTop) * reveal
                    if (ctx != null && shownH > 1f) {
                        ctx.drawRoundedRect(
                            x - 4f, boxTop, x + w + 4f, boxTop + shownH,
                            5f, Theme.group, Theme.rowBorder, 1f,
                        )
                        val cx0 = floor(toRealX(x - 4f).toDouble())
                        val cy0 = floor(toRealY(boxTop).toDouble())
                        val clip = ScreenRectangle(
                            cx0.toInt(), cy0.toInt(),
                            (ceil(toRealX(x + w + 4f).toDouble()) - cx0).toInt(),
                            (ceil(toRealY(boxTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                        )
                        ctx.scissorStack.push(clip)
                        try {
                            val hitMark = hits.size
                            layoutSettings(ctx, value, x + 4f, boxTop + SET_GAP, w - 8f, mx, my, depth + 1)
                            if (reveal < 1f) {
                                for (i in hitMark until hits.size) {
                                    hits[i].clipBottom = boxTop + shownH
                                }
                            }
                        } finally {
                            ctx.scissorStack.pop()
                        }
                    }
                    y = boxTop + shownH
                }
            }

            is BindValue -> y = drawBindRow(ctx, value, x, y, w, mx, my)

            is MultiChoiceListValue<*> -> {
                val selected = value.get()
                val t = easeInOutCubic(dropdownProgress[value] ?: 0f)
                y = drawDropdownRow(
                    ctx, value.name,
                    label = "${selected.size}/${value.choices.size}",
                    t, x, y, w, mx, my, value,
                    items = value.choices.map { it.tag }, multi = true,
                    isActive = { tag -> selected.any { it.tag == tag } },
                    onToggle = { if (!openDropdowns.add(value)) openDropdowns.remove(value) },
                    // multi-select: keep the list open so several options can be toggled
                    onPick = { tag ->
                        @Suppress("UNCHECKED_CAST")
                        val v = value as MultiChoiceListValue<Tagged>
                        v.choices.firstOrNull { it.tag == tag }?.let(v::toggle)
                    },
                )
            }

            is ChoiceListValue<*> -> {
                val currentTag = value.get().tag
                val t = easeInOutCubic(dropdownProgress[value] ?: 0f)
                y = drawDropdownRow(
                    ctx, value.name, currentTag, t, x, y, w, mx, my, value,
                    items = value.choices.map { it.tag }, multi = false,
                    isActive = { it == currentTag },
                    onToggle = { if (!openDropdowns.add(value)) openDropdowns.remove(value) },
                    onPick = { value.setByString(it); openDropdowns.remove(value) },
                )
            }

            is RegistryListValue<*, *> -> y = drawRegistryList(ctx, value, x, y, w, mx, my)

            is ItemsChooseValue -> y = drawItemsChoose(ctx, value, x, y, w, mx, my)

            is CurveValue -> {
                // read-only point count: a real curve editor (draggable 2D point canvas
                // with tension) is out of scope for the native GUI
                y = drawBadgeRow(ctx, value.name, "${value.get().size} points", false, x, y, w) {}
            }

            is MutableListValue<*, *> -> y = drawMutableList(ctx, value, x, y, w, mx, my)

            is FileValue -> y = drawFileRow(ctx, value, x, y, w)

            is RangedValue<*> -> y = drawSlider(ctx, value, x, y, w)

            is MutableGroupListValueGroup<*> -> {
                // the folder look PLUS add/remove: a "+" badge on the head appends a fresh
                // factory-built entry, per-entry "-" badges remove (never the last entry -
                // mirrors GroupListSetting.svelte); entries render as today
                y = drawBadgeRow(ctx, value.name, "+", false, x, y, w) { value.addEntry() }
                if (value.entries.isNotEmpty()) {
                    y = layoutGroupBox(ctx, x, y, w) { c, cx, cy, cw ->
                        var yy = cy
                        for ((index, entry) in value.entries.withIndex()) {
                            yy = drawGroupEntryHead(
                                c, value.entries.size > 1, index,
                                "grouprm:" + System.identityHashCode(value) + ":" + index,
                                cx, yy, cw, mx, my,
                            ) {
                                removeGroupEntry(value, index)
                            }
                            yy = layoutSetting(c, entry, cx, yy, cw, mx, my, depth + 1)
                        }
                        yy
                    }
                }
            }

            is ValueGroup -> {  // plain Configurable folder
                y = drawLabel(ctx, value.name, x, y, w)
                if (hasVisibleSettings(value)) {
                    y = layoutGroupBox(ctx, x, y, w) { c, cx, cy, cw ->
                        layoutSettings(c, value, cx, cy, cw, mx, my, depth + 1)
                    }
                }
            }

            else -> when (value.valueType) {
                ValueType.BOOLEAN -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = value as Value<Boolean>
                    y = drawCheckboxRow(ctx, value.name, v.get(), x, y, w, checkKey = value) {
                        v.set(!v.get())
                    }
                }

                ValueType.TEXT -> {
                    // ValueType.TEXT covers String, Regex and string/regex lists - dispatch
                    // on the actual payload (a blind String cast crashes on Regex values).
                    y = when (val raw = value.get()) {
                        // A list edits as its own comma-joined text: the value already parses
                        // that shape in setByString, so the field needs no list widget of its own.
                        is String, is Regex, is Collection<*> -> drawTextField(ctx, value, x, y, w)
                        else -> drawBadgeRow(ctx, value.name, "n/a", false, x, y, w) {}
                    }
                }

                ValueType.COLOR -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = value as Value<Color4b>
                    y = drawColorSetting(ctx, v, x, y, w)
                }

                ValueType.KEY -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = value as Value<InputConstants.Key>
                    val binding = pendingKey === v
                    val label = if (binding) "Press key..." else keyDisplayName(v.get())
                    y = drawBadgeRow(ctx, value.name, label, binding, x, y, w) {
                        pendingKey = if (binding) null else v
                        pendingBind = null
                        commitEdit()
                        searchFocused = false
                    }
                }

                ValueType.VECTOR3_I, ValueType.VECTOR3_D, ValueType.VECTOR2_F ->
                    y = drawVecFields(ctx, value, x, y, w)

                else -> {
                    // unsupported type: name + n/a badge (read-only)
                    y = drawBadgeRow(ctx, value.name, "n/a", false, x, y, w) {}
                }
            }
        }
        return y + SET_GAP
    }

    // - Widgets -

    /**
     * Subtle rounded container around nested settings. The box spans `x-4 .. x+w+4`
     * (same width as the boxed toggle row above it); its children are laid out on a
     * narrower column (`x+4`, width `w-8`) so they keep clear side margins (8px from
     * the box border) at every nesting level.
     */
    private fun layoutGroupBox(
        ctx: GuiGraphicsExtractor?, x: Float, y: Float, w: Float,
        body: (GuiGraphicsExtractor?, Float, Float, Float) -> Float,
    ): Float {
        val innerX = x + 4f
        val innerW = w - 8f
        val innerEnd = body(null, innerX, y + SET_GAP, innerW)
        // children end with a trailing SET_GAP; reuse it as the bottom pad -> equal paddings
        val bottom = innerEnd
        if (ctx != null) {
            ctx.drawRoundedRect(x - 4f, y, x + w + 4f, bottom, 5f, Theme.group, Theme.rowBorder, 1f)
            body(ctx, innerX, y + SET_GAP, innerW)
        }
        return bottom
    }

    private fun drawLabel(
        ctx: GuiGraphicsExtractor?, name: String,
        x: Float, y: Float, w: Float,
    ): Float {
        val nameH = lineHeight(WatermarkFont.BRAND, FONT_SET)
        if (ctx != null) {
            drawText(ctx, WatermarkFont.BRAND, name, x, y, FONT_SET, Theme.white)
        }
        return y + nameH + 3f
    }

    @Suppress("LongParameterList")
    private fun drawCheckboxRow(
        ctx: GuiGraphicsExtractor?, name: String, checked: Boolean,
        x: Float, y: Float, w: Float,
        boxed: Boolean = false,
        checkKey: Value<*>? = null,
        onToggle: () -> Unit,
    ): Float {
        val boxSize = 11f
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val rowH = maxOf(nameH, boxSize)

        // checkbox toggle lerp (fill badge -> lav, border rowBorder -> lav); the target
        // feed is collected in BOTH passes (identical), the progress advances per frame
        if (checkKey != null && checked) checkboxTargets += checkKey
        val checkT = if (checkKey != null) {
            easeInOutCubic(checkboxProgress[checkKey] ?: 0f)
        } else {
            if (checked) 1f else 0f
        }

        if (ctx != null) {
            // the toggle that gates sub-settings gets its own soft rectangle,
            // exactly as wide as the group box below it
            if (boxed) {
                ctx.drawRoundedRect(x - 4f, y - 2f, x + w + 4f, y + rowH + 2f, 4f, Theme.groupHead, null)
            }

            drawText(ctx, WatermarkFont.TEXT, name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            // checkbox: solid filled square when checked, hollow when not
            val bx = x + w - boxSize
            val byy = y + (rowH - boxSize) / 2f
            val boxFill = lerpColor(Theme.badge, Theme.lav, checkT)
            val boxBorder = lerpColor(Theme.rowBorder, Theme.lav, checkT)
            ctx.drawRoundedRect(bx, byy, bx + boxSize, byy + boxSize, 3f, boxFill, boxBorder, 1f)

            hits += Hit(x, y, x + w, y + rowH, inScroll = true) { button ->
                if (button == 0) { onToggle(); true } else false
            }
        }
        return y + rowH
    }

    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod", "LongMethod")
    private fun drawSlider(ctx: GuiGraphicsExtractor?, value: RangedValue<*>, x: Float, y: Float, w: Float): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val trackW = minOf(CTRL_W, w * 0.5f)
        val trackX = x + w - trackW

        // current fractions (the display text is only built when drawing - see below)
        var frac0 = 0f
        var frac1: Float? = null
        when (value.valueType) {
            ValueType.FLOAT -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                frac0 = fracOf(value.get() as Float, range.start, range.endInclusive)
            }
            ValueType.INT -> {
                val range = value.range as IntRange
                frac0 = fracOf((value.get() as Int).toFloat(), range.first.toFloat(), range.last.toFloat())
            }
            ValueType.FLOAT_RANGE -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val v = value.get() as ClosedFloatingPointRange<Float>
                frac0 = fracOf(v.start, range.start, range.endInclusive)
                frac1 = fracOf(v.endInclusive, range.start, range.endInclusive)
            }
            ValueType.INT_RANGE -> {
                val range = value.range as IntRange
                val v = value.get() as IntRange
                frac0 = fracOf(v.first.toFloat(), range.first.toFloat(), range.last.toFloat())
                frac1 = fracOf(v.last.toFloat(), range.first.toFloat(), range.last.toFloat())
            }
            else -> Unit
        }

        // The value text + track pair is centered around the row middle: the text sits half
        // a block ABOVE the center, the track half a block BELOW it — nothing leaks outside
        // the row, so the uniform SET_GAP offsets stay intact.
        val valH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        val knobR = 3.2f
        val innerGap = 2f
        val blockH = valH + innerGap + knobR * 2f
        val rowH = maxOf(nameH, blockH)
        if (ctx != null) {
            // display text: formatting only happens on the draw pass, never when measuring
            val text = when (value.valueType) {
                ValueType.FLOAT -> "%.1f%s".format(value.get() as Float, value.suffix.suffixText())
                ValueType.INT -> "%d%s".format(value.get() as Int, value.suffix.suffixText())
                ValueType.FLOAT_RANGE -> {
                    val v = value.get() as ClosedFloatingPointRange<Float>
                    "%.1f - %.1f%s".format(v.start, v.endInclusive, value.suffix.suffixText())
                }
                ValueType.INT_RANGE -> {
                    val v = value.get() as IntRange
                    "%d - %d%s".format(v.first, v.last, value.suffix.suffixText())
                }
                else -> value.get().toString()
            }
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            val blockTop = y + (rowH - blockH) / 2f
            val valW = textWidth(WatermarkFont.TEXT, text, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, text, x + w - valW, blockTop, FONT_CTRL, Theme.white)

            // track (vertical center of the lower half of the block)
            val ty = blockTop + valH + innerGap + knobR
            ctx.drawRoundedRect(trackX, ty - 1f, trackX + trackW, ty + 1f, 1f, Theme.track, null)
            val fx0 = trackX + trackW * (if (frac1 != null) frac0 else 0f)
            val fx1 = trackX + trackW * (frac1 ?: frac0)
            ctx.drawRoundedRect(fx0, ty - 1f, fx1, ty + 1f, 1f, Theme.lav, null)
            ctx.drawCircle(trackX + trackW * frac0, ty, knobR) { Theme.knob.argb }
            if (frac1 != null) {
                ctx.drawCircle(trackX + trackW * frac1, ty, knobR) { Theme.knob.argb }
            }

            // drag hit: the press CAPTURES this slider until the button is released,
            // so passing over another slider can never steal the drag.
            // Range sliders: the active handle (0 = min, 1 = max) is picked on the FIRST
            // application and never changes mid-drag — handles clamp against each other
            // and cannot cross. Collapsed handles (e.g. `4..4` at the minimum) are picked
            // by drag DIRECTION: right grabs the max handle, left grabs the min one, so a
            // collapsed range is always draggable (see [pickRangeHandle]).
            val applyFrac: (Float) -> Unit = when (value.valueType) {
                ValueType.FLOAT_RANGE, ValueType.INT_RANGE -> {
                    var handle = -1
                    { rawFrac ->
                        val frac = rawFrac.coerceIn(0f, 1f)
                        if (handle < 0) handle = pickRangeHandle(value, frac)
                        if (handle >= 0) applyRangeFrac(value, frac, handle)
                    }
                }
                else -> { rawFrac -> applySliderFrac(value, rawFrac.coerceIn(0f, 1f)) }
            }
            hits += Hit(trackX - 4f, y, trackX + trackW + 4f, y + rowH, inScroll = true) { button ->
                if (button == 0) {
                    activeSlider = SliderDrag(trackX, trackW, applyFrac)
                    true
                } else false
            }
        }
        return y + rowH
    }

    @Suppress("UNCHECKED_CAST")
    private fun applySliderFrac(value: RangedValue<*>, frac: Float) {
        when (value.valueType) {
            ValueType.FLOAT -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100).roundToInt() / 100f
                // skip no-op sets: change listeners / autosave must not fire 60/s for nothing
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

    /** Current (start, end) handle positions of a range value in fraction space, or null if not a range. */
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
     * Which handle a range drag grabs: the nearer one in fraction space. The pick is FINAL
     * for the whole drag — handles clamp against each other and never trade the drag.
     * Collapsed handles (both at the same spot, e.g. `4..4`): decided by drag DIRECTION
     * once the pointer leaves the overlap — right grabs the max handle, left grabs the min
     * one; returns -1 while the direction is not yet clear (nothing is applied that frame),
     * which is what makes a collapsed range draggable at all.
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

    /** Applies [frac] to the drag-locked [handle] (0 = min, 1 = max) of a range slider. */
    @Suppress("UNCHECKED_CAST")
    private fun applyRangeFrac(value: RangedValue<*>, frac: Float, handle: Int) {
        when (value.valueType) {
            ValueType.FLOAT_RANGE -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val cur = value.get() as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100).roundToInt() / 100f
                val newRange = if (handle == 0) {
                    v.coerceAtMost(cur.endInclusive)..cur.endInclusive
                } else {
                    cur.start..v.coerceAtLeast(cur.start)
                }
                // ranges have no value equality - compare the endpoints before setting
                if (newRange.start != cur.start || newRange.endInclusive != cur.endInclusive) {
                    (value as RangedValue<ClosedFloatingPointRange<Float>>).set(newRange)
                }
            }
            ValueType.INT_RANGE -> {
                val range = value.range as IntRange
                val cur = value.get() as IntRange
                val v = (range.first + frac * (range.last - range.first)).roundToInt()
                val newRange = if (handle == 0) {
                    v.coerceAtMost(cur.last)..cur.last
                } else {
                    cur.first..v.coerceAtLeast(cur.first)
                }
                if (newRange.first != cur.first || newRange.last != cur.last) {
                    (value as RangedValue<IntRange>).set(newRange)
                }
            }
            else -> Unit
        }
    }

    /**
     * The option list under a dropdown field. Drawn at FULL height; the caller scissors
     * it to the animated reveal height. [borderT] lerps the list border rowBorder -> lav.
     * Rows get a subtle hover background (100 ms) and - for multi-choice lists - the
     * active background + selected dot lerp over PICK_SEC, keyed by "identityHash:tag".
     * [listWidth] > 0 pins the list to that width (right-aligned within [w]); [itemIcon]
     * draws a small preview to the left of each item's text (text shifts right for it).
     * [rowHeight] sets the item row height (the caller's reveal-height formula must use
     * the same value to keep the measure/draw contract).
     */
    @Suppress("LongParameterList")
    private fun drawSelectList(
        ctx: GuiGraphicsExtractor?, x: Float, y: Float, w: Float, mx: Float, my: Float,
        items: List<String>, isActive: (String) -> Boolean, onPick: (String) -> Unit,
        borderT: Float, keyPrefix: String, multi: Boolean,
        listWidth: Float = 0f,
        rowHeight: Float = 12f,
        itemIcon: ((ctx: GuiGraphicsExtractor, item: String, cx: Float, cy: Float) -> Unit)? = null,
    ): Float {
        val listW = if (listWidth > 0f) minOf(listWidth, w) else minOf(CTRL_W + 20f, w)
        val listX = x + w - listW
        val rowH = rowHeight
        val pad = 3f
        val listH = items.size * rowH + pad * 2

        if (ctx != null) {
            ctx.drawRoundedRect(
                listX, y, listX + listW, y + listH,
                6f, Theme.listBg, lerpColor(Theme.rowBorder, Theme.lav, borderT), 1f,
            )
            var ry = y + pad
            for (item in items) {
                val key = keyPrefix + item
                val active = isActive(item)
                // single-choice rows keep the binary look (the list closes on pick);
                // multi-choice rows lerp their selection feedback
                val pickT = if (multi) {
                    easeInOutCubic(listPickProgress[key] ?: 0f)
                } else {
                    if (active) 1f else 0f
                }
                val hovered = mx >= listX && mx <= listX + listW && my >= ry && my <= ry + rowH
                if (hovered) hoveredListKey = key
                val hoverT = easeInOutCubic(listHoverProgress[key] ?: 0f)

                val bgT = maxOf(pickT, hoverT * 0.55f)
                if (bgT > 0.01f) {
                    ctx.drawRoundedRect(
                        listX + pad, ry, listX + listW - pad, ry + rowH,
                        4f, Theme.listActive.alphaMul(bgT), null,
                    )
                }
                var textX = listX + pad + 6f
                if (itemIcon != null) {
                    itemIcon(ctx, item, listX + pad + 6f + 5f, ry + rowH / 2f)
                    textX += 14f
                }
                val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
                drawText(
                    ctx, WatermarkFont.TEXT, item,
                    textX, ry + (rowH - textH) / 2f, FONT_CTRL,
                    lerpColor(Theme.desc, Theme.white, pickT),
                )
                if (pickT > 0.01f) {
                    // small lavender dot marker on the right
                    ctx.drawCircle(listX + listW - pad - 6f, ry + rowH / 2f, 1.8f) {
                        Theme.lav.alphaMul(pickT).argb
                    }
                }

                hits += Hit(listX, ry, listX + listW, ry + rowH, inScroll = true) { button ->
                    if (button == 0) { onPick(item); true } else false
                }
                ry += rowH
            }
        }
        return y + listH
    }

    /**
     * Choice setting: a collapsed field showing the current value (or `k/n` for multi-choice).
     * RMB on the field expands/collapses the option list (LMB picks/toggles inside the list).
     * [t] is the eased open progress: the list renders under a scissor of fullH * t and the
     * returned height follows the same value in BOTH passes; the chevron morphs with t.
     */
    @Suppress("LongParameterList")
    private fun drawDropdownRow(
        ctx: GuiGraphicsExtractor?, name: String, label: String, t: Float,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
        value: Value<*>, items: List<String>, multi: Boolean,
        isActive: (String) -> Boolean,
        onToggle: () -> Unit, onPick: (String) -> Unit,
    ): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val fieldH = 14f
        val rowH = maxOf(nameH, fieldH)
        val fieldW = minOf(CTRL_W + 20f, w * 0.6f)
        val fx = x + w - fieldW

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            val fy = y + (rowH - fieldH) / 2f
            val outline = lerpColor(Theme.rowBorder, Theme.lav, t)
            ctx.drawRoundedRect(fx, fy, fx + fieldW, fy + fieldH, 4f, Theme.listBg, outline, 1f)

            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, label, fx + 6f, fy + (fieldH - textH) / 2f, FONT_CTRL, Theme.white)

            // chevron morph: vertex-lerp between the down (t=0) and up (t=1) triangle
            val chx = fx + fieldW - 9f
            val chy = fy + fieldH / 2f
            val sideY = chy - 1.5f + 3f * t
            val tipY = chy + 2f - 4f * t
            ctx.drawTriangle(chx - 3f, sideY, chx + 3f, sideY, chx, tipY, Theme.desc)

            hits += Hit(fx, fy, fx + fieldW, fy + fieldH, inScroll = true) { button ->
                if (button == 1) { onToggle(); true } else false
            }
        }

        var cy = y + rowH
        if (t > 0.001f) {
            val listTop = cy + 3f * t
            val fullH = items.size * 12f + 6f // select list: rows + 2 * pad
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(listTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(listTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits below the animated scissor bottom are not clickable yet
                    val hitMark = hits.size
                    drawSelectList(
                        ctx, x, listTop, w, mx, my, items, isActive, onPick,
                        borderT = t, keyPrefix = System.identityHashCode(value).toString() + ":",
                        multi = multi,
                    )
                    if (t < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = listTop + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy = listTop + shownH
        }
        return cy
    }

    private fun drawBadgeRow(
        ctx: GuiGraphicsExtractor?, name: String, label: String, highlighted: Boolean,
        x: Float, y: Float, w: Float, onClick: () -> Unit,
    ): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val badgeH = 13f
        val rowH = maxOf(nameH, badgeH)

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            val textW = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
            val badgeW = textW + 12f
            val bx = x + w - badgeW
            val byy = y + (rowH - badgeH) / 2f
            val bg = if (highlighted) Theme.lav else Theme.badge
            val fg = if (highlighted) Theme.lavDark else Theme.white
            ctx.drawRoundedRect(bx, byy, bx + badgeW, byy + badgeH, 4f, bg, null)
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, label, bx + 6f, byy + (badgeH - textH) / 2f, FONT_CTRL, fg)

            hits += Hit(bx, byy, bx + badgeW, byy + badgeH, inScroll = true) { button ->
                if (button == 0) { onClick(); true } else false
            }
        }
        return y + rowH
    }

    private fun drawTextField(ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, y: Float, w: Float): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val fieldH = 14f
        val rowH = maxOf(nameH, fieldH)
        val fieldW = minOf(CTRL_W, w * 0.55f)
        val fx = x + w - fieldW

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            val focused = focusedField === value
            val current = if (focused) editBuffer else fieldTextOf(value)
            val fy = y + (rowH - fieldH) / 2f
            val outline = if (focused) Theme.lav else Theme.rowBorder
            ctx.drawRoundedRect(fx, fy, fx + fieldW, fy + fieldH, 5f, Theme.field, outline, 1f)

            val caret = if (focused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
            // clip from the left so the caret stays visible
            val display = clipFieldText(current + caret, FONT_CTRL, fieldW - 8f)
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            val color = if (current.isEmpty() && !focused) Theme.dim else Theme.white
            drawText(ctx, WatermarkFont.TEXT, display, fx + 4f, fy + (fieldH - textH) / 2f, FONT_CTRL, color)

            hits += Hit(fx, fy, fx + fieldW, fy + fieldH, inScroll = true) { button ->
                if (button == 0) {
                    beginEdit(value)
                    true
                } else false
            }
        }
        return y + rowH
    }

    /** The editable string representation of a TEXT value (String or Regex). */
    private fun fieldTextOf(value: Value<*>): String = when (val raw = value.get()) {
        is String -> raw
        is Regex -> raw.pattern
        is Collection<*> -> raw.joinToString(", ") { item ->
            if (item is Regex) item.pattern else item.toString()
        }
        else -> raw.toString()
    }

    /**
     * Clips [display] from the left so it fits [maxW] (the caret end stays visible). Font
     * width is additive per glyph, so walk ONCE from the end accumulating widths, then one
     * substring (a substring-per-char loop would be O(n^2) allocs + width lookups).
     */
    private fun clipFieldText(display: String, px: Float, maxW: Float): String {
        if (display.length <= 1 || textWidth(WatermarkFont.TEXT, display, px) <= maxW) return display
        var start = display.length
        var acc = 0f
        while (start > 1) {
            val cw = textWidth(WatermarkFont.TEXT, display[start - 1].toString(), px)
            if (acc + cw > maxW) break
            acc += cw
            start--
        }
        if (start == display.length) start = display.length - 1 // keep at least one char
        return display.substring(start)
    }

    private fun beginEdit(value: Value<*>) {
        commitEdit()
        focusedField = value
        focusedFieldKey = "text:" + System.identityHashCode(value)
        keyedCommit = null
        editBuffer = fieldTextOf(value)
        pendingBind = null
        pendingKey = null
        searchFocused = false
    }

    /**
     * Focuses a keyed non-Value text target (the picker search boxes). [onCommit] receives
     * the buffer on Enter/click-away; ESC cancels without committing.
     */
    private fun beginKeyedEdit(key: String, initial: String, onCommit: (String) -> Unit) {
        commitEdit()
        focusedField = null
        focusedFieldKey = key
        keyedCommit = onCommit
        editBuffer = initial
        pendingBind = null
        pendingKey = null
        searchFocused = false
    }

    /** Writes the edit buffer back into the target; invalid regex keeps the old value. */
    @Suppress("UNCHECKED_CAST")
    private fun commitEdit() {
        if (focusedFieldKey == null) return
        focusedFieldKey = null
        val commit = keyedCommit
        keyedCommit = null
        val value = focusedField
        focusedField = null
        if (value == null) {
            // keyed non-Value target (picker search boxes): the owner takes the buffer
            commit?.invoke(editBuffer)
            return
        }
        try {
            // skip the set() when nothing changed - listeners/autosave would fire for nothing
            when (val cur = value.get()) {
                is String -> if (editBuffer != cur) (value as Value<String>).set(editBuffer)
                is Regex -> if (editBuffer != cur.pattern) (value as Value<Regex>).set(Regex(editBuffer))
                // The list parser splits on "," and keeps what it is given, so the entries are
                // trimmed here; an empty field clears the list, which no string can express.
                is MutableCollection<*> -> {
                    val items = editBuffer.split(',').map(String::trim).filter(String::isNotEmpty)
                    if (items.isEmpty()) cur.clear() else value.setByString(items.joinToString(","))
                }
                else -> Unit
            }
        } catch (e: Throwable) {
            logger.warn("LeetClickGui: could not apply text for ${value.name}: ${e.message}")
        }
    }

    private fun cancelEdit() {
        focusedField = null
        focusedFieldKey = null
        keyedCommit = null
    }

    // - Registry/items list pickers (REGISTRY_LIST + ITEMS_CHOOSE) -

    /** One selectable entry of a list picker: the raw registry element + its display name. */
    private class RegEntry(val element: Any, val name: String)

    /**
     * Wheel-capture region of an open picker list: while the mouse is inside, the wheel
     * drives the list's own scroll offset instead of the pane scroll (see mouseScrolled).
     */
    private class ListScrollRegion(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val key: Value<*>, val maxOff: Float,
    )

    /**
     * The full selectable entry set of a REGISTRY_LIST / ITEMS_CHOOSE value, enumerated
     * from the built-in registries once per value and cached. Display names follow the
     * simple form of the REST registry endpoint: the registry path without namespace
     * ("obsidian"); client modules keep their module name.
     */
    private fun pickEntriesOf(value: Value<*>): List<RegEntry> = pickerEntries.getOrPut(value) {
        fun <T : Any> Registry<T>.regEntries(): List<RegEntry> =
            mapNotNull { e -> getKey(e)?.let { RegEntry(e, it.path) } }

        when (value) {
            is ItemsChooseValue -> BuiltInRegistries.ITEM.regEntries()
            is RegistryListValue<*, *> -> when (value.registry) {
                "block" -> BuiltInRegistries.BLOCK.regEntries()
                "item" -> BuiltInRegistries.ITEM.regEntries()
                "sound_event" -> BuiltInRegistries.SOUND_EVENT.regEntries()
                "mob_effect" -> BuiltInRegistries.MOB_EFFECT.regEntries()
                "entity_type" -> BuiltInRegistries.ENTITY_TYPE.regEntries()
                "menu" -> BuiltInRegistries.MENU.regEntries()
                "c2s_packet" -> packetRegistry[PacketFlow.SERVERBOUND].orEmpty().map { RegEntry(it, it.path) }
                "s2c_packet" -> packetRegistry[PacketFlow.CLIENTBOUND].orEmpty().map { RegEntry(it, it.path) }
                "client_module" -> ModuleManager.map { RegEntry(it.name, it.name) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Re-fires listeners + autosave after an IN-PLACE mutation of a list value's
     * collection: plain set() would see identity equality and skip, so the two-arg
     * overload is used (same trick as ListValue.deserializeFrom). The generic signature
     * sidesteps the star-projection ban on set() at the call sites.
     */
    private fun <T : MutableCollection<E>, E> refireListValue(value: ListValue<T, E>) {
        value.set(value.get()) { }
    }

    /**
     * Toggles [element] in the selected set of a REGISTRY_LIST value (mutated in place,
     * then re-fired via [refireListValue]).
     */
    @Suppress("UNCHECKED_CAST")
    private fun toggleRegistryEntry(value: RegistryListValue<*, *>, element: Any) {
        val selected = value.get() as MutableSet<Any>
        if (!selected.remove(element)) selected.add(element)
        refireListValue(value)
    }

    /** Adds [item] to an ITEMS_CHOOSE list (order preserved, duplicates refused). */
    private fun addChosenItem(value: ItemsChooseValue, item: Item) {
        val list = value.get()
        if (item in list) return
        list.add(item)
        refireListValue(value)
    }

    /** Removes the [index]-th entry of an ITEMS_CHOOSE list. */
    private fun removeChosenItem(value: ItemsChooseValue, index: Int) {
        val list = value.get()
        if (index !in list.indices) return
        list.removeAt(index)
        refireListValue(value)
    }

    /**
     * Collapsed head of the expandable list pickers: the setting name on the left and a
     * compact dropdown-style field ([label] + chevron morph by [t]) on the right - same
     * field metrics as [drawDropdownRow]. RMB toggles the reveal via [onToggle].
     */
    private fun drawListHead(
        ctx: GuiGraphicsExtractor?, name: String, label: String, t: Float,
        x: Float, y: Float, w: Float, onToggle: () -> Unit,
    ): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val fieldH = 14f
        val rowH = maxOf(nameH, fieldH)

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            val fieldW = minOf(CTRL_W + 20f, w * 0.6f)
            val fx = x + w - fieldW
            val fy = y + (rowH - fieldH) / 2f
            ctx.drawRoundedRect(
                fx, fy, fx + fieldW, fy + fieldH, 4f, Theme.listBg,
                lerpColor(Theme.rowBorder, Theme.lav, t), 1f,
            )

            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, label, fx + 6f, fy + (fieldH - textH) / 2f, FONT_CTRL, Theme.white)

            // chevron morph: vertex-lerp between the down (t=0) and up (t=1) triangle
            val chx = fx + fieldW - 9f
            val chy = fy + fieldH / 2f
            val sideY = chy - 1.5f + 3f * t
            val tipY = chy + 2f - 4f * t
            ctx.drawTriangle(chx - 3f, sideY, chx + 3f, sideY, chx, tipY, Theme.desc)

            hits += Hit(fx, fy, fx + fieldW, fy + fieldH, inScroll = true) { button ->
                if (button == 1) { onToggle(); true } else false
            }
        }
        return y + rowH
    }

    /**
     * The search field on top of an open list picker: full width, keyed text-edit target
     * ([key]). While focused the live text is [editBuffer], otherwise [query]; Enter /
     * click-away commits through the keyed commit hook, ESC cancels. The focused border
     * snaps to lav (typing is direct manipulation - no focus animation).
     */
    private fun drawKeyedSearchField(
        ctx: GuiGraphicsExtractor?, key: String, query: String,
        x: Float, y: Float, w: Float, onBegin: () -> Unit,
    ): Float {
        val fieldH = 14f
        if (ctx != null) {
            val focused = focusedFieldKey == key
            val current = if (focused) editBuffer else query
            ctx.drawRoundedRect(
                x, y, x + w, y + fieldH, 5f, Theme.field,
                if (focused) Theme.lav else Theme.rowBorder, 1f,
            )

            val caret = if (focused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
            val placeholder = current.isEmpty() && !focused
            val display = clipFieldText(if (placeholder) "Search" else current + caret, FONT_CTRL, w - 10f)
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(
                ctx, WatermarkFont.TEXT, display, x + 5f, y + (fieldH - textH) / 2f, FONT_CTRL,
                if (placeholder) Theme.dim else Theme.white,
            )

            hits += Hit(x, y, x + w, y + fieldH, inScroll = true) { button ->
                if (button == 0) { onBegin(); true } else false
            }
        }
        return y + fieldH
    }

    /** Box height of [drawEntryScrollList] for [count] entries (shared by the reveal math). */
    private fun entryListHeight(count: Int): Float =
        minOf(count, LIST_MAX_ROWS).coerceAtLeast(1) * REG_ROW_H + 6f

    /**
     * Scrollable entry list of the pickers: a [Theme.listBg] box of at most [LIST_MAX_ROWS]
     * rows; longer content gets an internal scroll offset (wheel-driven while the mouse is
     * over the box - see [listScrollRegions]) and a thin scrollbar. Rows are clipped to the
     * box interior; partially clipped rows stay clickable on their visible part only. The
     * height formula is shared with the callers' reveal math (measure/draw contract).
     */
    @Suppress("LongParameterList")
    private fun drawEntryScrollList(
        ctx: GuiGraphicsExtractor?, x: Float, y: Float, w: Float, mx: Float, my: Float,
        listKey: Value<*>, entries: List<RegEntry>, showCheck: Boolean,
        isMarked: (RegEntry) -> Boolean, onPick: (RegEntry) -> Unit,
        clipBottom: Float,
    ): Float {
        val pad = 3f
        val boxH = entryListHeight(entries.size)
        val innerH = boxH - pad * 2
        // internal scroll offset, clamped to the (possibly filtered) content in BOTH passes
        val maxOff = (entries.size * REG_ROW_H - innerH).coerceAtLeast(0f)
        val offset = (listScrollOffsets[listKey] ?: 0f).coerceIn(0f, maxOff)
        listScrollOffsets[listKey] = offset

        if (ctx != null) {
            ctx.drawRoundedRect(x, y, x + w, y + boxH, 6f, Theme.listBg, Theme.rowBorder, 1f)
            if (maxOff > 0f) {
                // wheel over the box scrolls the list, not the pane (consumed in mouseScrolled)
                listScrollRegions += ListScrollRegion(x, y, x + w, minOf(y + boxH, clipBottom), listKey, maxOff)
            }

            // rows are clipped to the box interior (floor/ceil, no lost pixel rows)
            val cx0 = floor(toRealX(x + 1f).toDouble())
            val cy0 = floor(toRealY(y + 1f).toDouble())
            val clip = ScreenRectangle(
                cx0.toInt(), cy0.toInt(),
                (ceil(toRealX(x + w - 1f).toDouble()) - cx0).toInt(),
                (ceil(toRealY(y + boxH - 1f).toDouble()) - cy0).toInt().coerceAtLeast(1),
            )
            ctx.scissorStack.push(clip)
            try {
                var ry = y + pad - offset
                for (entry in entries) {
                    if (ry + REG_ROW_H > y && ry < y + boxH) {
                        drawEntryRow(
                            ctx, entry, x + pad, ry, w - pad * 2, mx, my, listKey,
                            showCheck, isMarked(entry), onPick, y + 1f, y + boxH - 1f,
                        )
                    }
                    ry += REG_ROW_H
                }
                if (entries.isEmpty()) {
                    val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
                    drawText(ctx, WatermarkFont.TEXT, "no matches", x + pad + 5f,
                        y + (boxH - textH) / 2f, FONT_CTRL, Theme.dim)
                }
            } finally {
                ctx.scissorStack.pop()
            }

            // thin scrollbar while the content overflows
            if (maxOff > 0f) {
                val trackH = boxH - 4f
                val thumbH = (trackH * innerH / (entries.size * REG_ROW_H)).coerceIn(6f, trackH)
                val thumbY = y + 2f + (trackH - thumbH) * (offset / maxOff)
                ctx.drawRoundedRect(x + w - 3f, thumbY, x + w - 1f, thumbY + thumbH, 1f, Theme.dim, null)
            }
        }
        return y + boxH
    }

    /**
     * One entry row of a picker list: an optional checkbox (badge -> lav lerp on toggle,
     * fed through [registryPickTargets]) + the entry name. Hover/selection feedback reuses
     * the select-list progress maps keyed by "identityHash(value):name".
     */
    @Suppress("LongParameterList")
    private fun drawEntryRow(
        ctx: GuiGraphicsExtractor, entry: RegEntry,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
        listKey: Value<*>, showCheck: Boolean, marked: Boolean, onPick: (RegEntry) -> Unit,
        clipTop: Float, clipBottom: Float,
    ) {
        val key = System.identityHashCode(listKey).toString() + ":" + entry.name
        if (marked) registryPickTargets += key
        val pickT = easeInOutCubic(listPickProgress[key] ?: 0f)
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + REG_ROW_H
        if (hovered) hoveredListKey = key
        val hoverT = easeInOutCubic(listHoverProgress[key] ?: 0f)

        val bgT = maxOf(pickT * 0.6f, hoverT * 0.55f)
        if (bgT > 0.01f) {
            ctx.drawRoundedRect(x, y, x + w, y + REG_ROW_H, 4f, Theme.listActive.alphaMul(bgT), null)
        }

        var textX = x + 5f
        if (showCheck) {
            val box = 8f
            val byy = y + (REG_ROW_H - box) / 2f
            ctx.drawRoundedRect(
                x + 3f, byy, x + 3f + box, byy + box, 2.5f,
                lerpColor(Theme.badge, Theme.lav, pickT), lerpColor(Theme.rowBorder, Theme.lav, pickT), 1f,
            )
            textX = x + 3f + box + 5f
        }
        val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        drawText(
            ctx, WatermarkFont.TEXT, entry.name, textX, y + (REG_ROW_H - textH) / 2f, FONT_CTRL,
            lerpColor(Theme.desc, Theme.white, pickT),
        )

        // partially clipped rows stay clickable on their visible part only
        val hy0 = maxOf(y, clipTop)
        val hy1 = minOf(y + REG_ROW_H, clipBottom)
        if (hy1 > hy0) {
            hits += Hit(x, hy0, x + w, hy1, inScroll = true) { button ->
                if (button == 0) { onPick(entry); true } else false
            }
        }
    }

    /**
     * REGISTRY_LIST widget: the collapsed field shows `k/n` (selected vs total entries);
     * RMB reveals a search field (live case-insensitive filter) and a scroll-capped
     * multi-checkbox list of the matching entries. LMB on a row toggles membership -
     * the value's set is mutated in place and persisted via the two-arg set() (see
     * [toggleRegistryEntry]). The reveal reuses the dropdown machinery (height scissor +
     * clipBottom hit stamping); the returned height follows `fullH * t` in BOTH passes.
     */
    private fun drawRegistryList(
        ctx: GuiGraphicsExtractor?, value: RegistryListValue<*, *>,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val entries = pickEntriesOf(value)
        val selected = value.get()
        val t = easeInOutCubic(dropdownProgress[value] ?: 0f)

        var cy = drawListHead(ctx, value.name, "${selected.size}/${entries.size}", t, x, y, w) {
            if (!openDropdowns.add(value)) openDropdowns.remove(value)
        }

        if (t > 0.001f) {
            val listTop = cy + 3f * t
            val searchKey = "regsearch:" + System.identityHashCode(value)
            val query = if (focusedFieldKey == searchKey) editBuffer else pickerSearchQueries[value] ?: ""
            val filtered = if (query.isBlank()) {
                entries
            } else {
                val q = query.trim()
                entries.filter { it.name.contains(q, ignoreCase = true) }
            }

            val searchH = 14f
            val gap = 4f
            val fullH = searchH + gap + entryListHeight(filtered.size)
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(listTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(listTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits below the animated scissor bottom are not clickable yet
                    val hitMark = hits.size
                    drawKeyedSearchField(ctx, searchKey, pickerSearchQueries[value] ?: "", x, listTop, w) {
                        beginKeyedEdit(searchKey, pickerSearchQueries[value] ?: "") {
                            pickerSearchQueries[value] = it
                        }
                    }
                    drawEntryScrollList(
                        ctx, x, listTop + searchH + gap, w, mx, my, value, filtered,
                        showCheck = true,
                        isMarked = { it.element in selected },
                        onPick = { toggleRegistryEntry(value, it.element) },
                        clipBottom = listTop + shownH,
                    )
                    if (t < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = listTop + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy = listTop + shownH
        }
        return cy
    }

    /**
     * ITEMS_CHOOSE widget (ordered item list, e.g. ItemBind): the collapsed field shows
     * the chosen count; the reveal lists the chosen entries in order (each with an "x"
     * remove badge) plus the search picker for adding new ones. Add/remove mutate the
     * value's list in place and persist via the two-arg set() (see [refireListValue]).
     * Same reveal + measure/draw contract as [drawRegistryList].
     */
    private fun drawItemsChoose(
        ctx: GuiGraphicsExtractor?, value: ItemsChooseValue,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val chosen = value.get()
        val t = easeInOutCubic(dropdownProgress[value] ?: 0f)

        var cy = drawListHead(
            ctx, value.name, "${chosen.size} item${if (chosen.size == 1) "" else "s"}", t, x, y, w,
        ) {
            if (!openDropdowns.add(value)) openDropdowns.remove(value)
        }

        if (t > 0.001f) {
            val listTop = cy + 3f * t
            val searchKey = "itemsearch:" + System.identityHashCode(value)
            val query = if (focusedFieldKey == searchKey) editBuffer else pickerSearchQueries[value] ?: ""
            val available = pickEntriesOf(value).filter { e ->
                e.element is Item && e.element !in chosen &&
                    (query.isBlank() || e.name.contains(query.trim(), ignoreCase = true))
            }

            val searchH = 14f
            val gap = 4f
            val chosenH = chosen.size * REG_ROW_H
            val chosenGap = if (chosen.isEmpty()) 0f else gap
            val fullH = chosenH + chosenGap + searchH + gap + entryListHeight(available.size)
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(listTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(listTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits below the animated scissor bottom are not clickable yet
                    val hitMark = hits.size
                    var ry = listTop
                    // chosen entries in order, each with an "x" remove badge
                    for ((index, item) in chosen.withIndex()) {
                        drawChosenRow(ctx, value, item, index, x, ry, w, mx, my)
                        ry += REG_ROW_H
                    }
                    ry += chosenGap
                    drawKeyedSearchField(ctx, searchKey, pickerSearchQueries[value] ?: "", x, ry, w) {
                        beginKeyedEdit(searchKey, pickerSearchQueries[value] ?: "") {
                            pickerSearchQueries[value] = it
                        }
                    }
                    drawEntryScrollList(
                        ctx, x, ry + searchH + gap, w, mx, my, value, available,
                        showCheck = false,
                        isMarked = { false },
                        onPick = { addChosenItem(value, it.element as Item) },
                        clipBottom = listTop + shownH,
                    )
                    if (t < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = listTop + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy = listTop + shownH
        }
        return cy
    }

    /** One chosen entry of [drawItemsChoose]: the item name + an "x" remove badge (LMB). */
    @Suppress("LongParameterList")
    private fun drawChosenRow(
        ctx: GuiGraphicsExtractor, value: ItemsChooseValue, item: Item, index: Int,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ) {
        val name = BuiltInRegistries.ITEM.getKey(item)?.path ?: item.toString()
        val key = "chosen:" + System.identityHashCode(value) + ":" + name
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + REG_ROW_H
        if (hovered) hoveredListKey = key
        val hoverT = easeInOutCubic(listHoverProgress[key] ?: 0f)
        if (hoverT > 0.01f) {
            ctx.drawRoundedRect(x, y, x + w, y + REG_ROW_H, 4f,
                Theme.listActive.alphaMul(hoverT * 0.55f), null)
        }

        val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        drawText(ctx, WatermarkFont.TEXT, name, x + 5f, y + (REG_ROW_H - textH) / 2f, FONT_CTRL, Theme.desc)

        // "x" remove badge (badge -> lav lerp on hover, like the bind-capture badge)
        val badge = 11f
        val bx = x + w - badge - 3f
        val byy = y + (REG_ROW_H - badge) / 2f
        ctx.drawRoundedRect(bx, byy, bx + badge, byy + badge, 3f,
            lerpColor(Theme.badge, Theme.lav, hoverT), null)
        val xW = textWidth(WatermarkFont.TEXT, "x", FONT_CTRL)
        drawText(ctx, WatermarkFont.TEXT, "x", bx + (badge - xW) / 2f, byy + (badge - textH) / 2f, FONT_CTRL,
            lerpColor(Theme.white, Theme.lavDark, hoverT))

        hits += Hit(bx - 2f, y, bx + badge + 2f, y + REG_ROW_H, inScroll = true) { button ->
            if (button == 0) { removeChosenItem(value, index); true } else false
        }
    }

    // - Bind setting: key-capture badge + action dropdown -

    /**
     * BIND setting: the key-capture badge (LMB captures - mouse buttons included - like the
     * module-card MMB capture) plus a compact action dropdown (Toggle/Hold/Smart, RMB opens)
     * to its left. The action persists via `copy(action = ...)`, so the bound key and the
     * modifiers survive; modifiers themselves stay display-only (the badge keeps showing
     * the full bind name).
     */
    @Suppress("LongMethod")
    private fun drawBindRow(
        ctx: GuiGraphicsExtractor?, value: BindValue,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val fieldH = 14f
        val badgeH = 13f
        val rowH = maxOf(nameH, fieldH)
        val rowKey = "bindact:" + System.identityHashCode(value)
        val t = easeInOutCubic(dropdownProgress[rowKey] ?: 0f)
        val action = value.get().action

        val binding = pendingBind === value
        val label = if (binding) "Press key..." else value.get().keyName
        val badgeW = textWidth(WatermarkFont.TEXT, label, FONT_CTRL) + 12f
        val fieldW = 46f
        val bx = x + w - badgeW
        val fx = bx - 4f - fieldW

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)

            // key badge (right-most): LMB toggles the capture
            val byy = y + (rowH - badgeH) / 2f
            ctx.drawRoundedRect(
                bx, byy, bx + badgeW, byy + badgeH, 4f,
                if (binding) Theme.lav else Theme.badge, null,
            )
            drawText(
                ctx, WatermarkFont.TEXT, label, bx + 6f, byy + (badgeH - textH) / 2f, FONT_CTRL,
                if (binding) Theme.lavDark else Theme.white,
            )
            hits += Hit(bx, byy, bx + badgeW, byy + badgeH, inScroll = true) { button ->
                if (button == 0) {
                    pendingBind = if (binding) null else value
                    pendingKey = null
                    commitEdit()
                    searchFocused = false
                    true
                } else false
            }

            // action field (left of the badge): RMB opens the Toggle/Hold/Smart list
            val fy = y + (rowH - fieldH) / 2f
            ctx.drawRoundedRect(
                fx, fy, fx + fieldW, fy + fieldH, 4f, Theme.listBg,
                lerpColor(Theme.rowBorder, Theme.lav, t), 1f,
            )
            drawText(ctx, WatermarkFont.TEXT, action.tag, fx + 5f, fy + (fieldH - textH) / 2f,
                FONT_CTRL, Theme.white)
            // chevron morph: vertex-lerp between the down (t=0) and up (t=1) triangle
            val chx = fx + fieldW - 8f
            val chy = fy + fieldH / 2f
            val sideY = chy - 1.5f + 3f * t
            val tipY = chy + 2f - 4f * t
            ctx.drawTriangle(chx - 3f, sideY, chx + 3f, sideY, chx, tipY, Theme.desc)
            hits += Hit(fx, fy, fx + fieldW, fy + fieldH, inScroll = true) { button ->
                if (button == 1) {
                    if (!openDropdowns.add(rowKey)) openDropdowns.remove(rowKey)
                    true
                } else false
            }
        }

        var cy = y + rowH
        if (t > 0.001f) {
            val listTop = cy + 3f * t
            val fullH = BIND_ACTION_TAGS.size * 12f + 6f // select list: rows + 2 * pad
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(listTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(listTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits below the animated scissor bottom are not clickable yet
                    val hitMark = hits.size
                    drawSelectList(
                        ctx, x, listTop, fx + fieldW - x, mx, my, BIND_ACTION_TAGS,
                        isActive = { it == action.tag },
                        onPick = { tag ->
                            InputBind.BindAction.of(tag)?.let { value.set(value.get().copy(action = it)) }
                            openDropdowns.remove(rowKey)
                        },
                        borderT = t, keyPrefix = "$rowKey:", multi = false,
                        listWidth = fieldW,
                    )
                    if (t < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = listTop + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy = listTop + shownH
        }
        return cy
    }

    // - Mutable string/regex list (MUTABLE_LIST) -

    /**
     * MUTABLE_LIST widget (textList/regexList, e.g. Spammer messages, BetterChat filters):
     * the collapsed field shows the entry count; RMB reveals one text field per entry
     * (keyed edit, commit on Enter/click-away, invalid regex patterns rejected) with an
     * "x" remove badge, plus a "+" row that appends a new empty entry and focuses it.
     * Mutations happen in place and are re-fired via [refireListValue]. Same reveal +
     * measure/draw contract as [drawItemsChoose].
     */
    private fun drawMutableList(
        ctx: GuiGraphicsExtractor?, value: MutableListValue<*, *>,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val entries = value.get().toList()
        val regex = value.innerType == Regex::class.java
        val t = easeInOutCubic(dropdownProgress[value] ?: 0f)

        var cy = drawListHead(
            ctx, value.name, "${entries.size} item${if (entries.size == 1) "" else "s"}", t, x, y, w,
        ) {
            if (!openDropdowns.add(value)) openDropdowns.remove(value)
        }

        if (t > 0.001f) {
            val listTop = cy + 3f * t
            val rowH = 14f
            val gap = 3f
            val fullH = entries.size * (rowH + gap) + rowH // entry rows + the "+" row
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                // floor(origin) / ceil(end): no pixel row lost to truncation
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(listTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(listTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                try {
                    // hits below the animated scissor bottom are not clickable yet
                    val hitMark = hits.size
                    var ry = listTop
                    for ((index, entry) in entries.withIndex()) {
                        val text = (entry as? Regex)?.pattern ?: entry.toString()
                        drawMutableListRow(ctx, value, index, text, regex, x, ry, w, mx, my)
                        ry += rowH + gap
                    }
                    drawMutableListAddRow(ctx, value, regex, x, ry, w, mx, my)
                    if (t < 1f) {
                        for (i in hitMark until hits.size) {
                            hits[i].clipBottom = listTop + shownH
                        }
                    }
                } finally {
                    ctx.scissorStack.pop()
                }
            }
            cy = listTop + shownH
        }
        return cy
    }

    /** One entry of [drawMutableList]: a keyed text field + an "x" remove badge (LMB). */
    @Suppress("LongParameterList")
    private fun drawMutableListRow(
        ctx: GuiGraphicsExtractor, value: MutableListValue<*, *>, index: Int, text: String,
        regex: Boolean, x: Float, y: Float, w: Float, mx: Float, my: Float,
    ) {
        val rowH = 14f
        val badge = 11f
        val bx = x + w - badge
        val fieldW = w - badge - 3f
        val key = "mlist:" + System.identityHashCode(value) + ":" + index

        // hover feedback of the "x" badge (badge -> lav lerp, like drawChosenRow)
        val hovered = mx >= bx - 2f && mx <= x + w && my >= y && my <= y + rowH
        val hKey = "$key:x"
        if (hovered) hoveredListKey = hKey
        val hoverT = easeInOutCubic(listHoverProgress[hKey] ?: 0f)

        val focused = focusedFieldKey == key
        val current = if (focused) editBuffer else text
        ctx.drawRoundedRect(
            x, y, x + fieldW, y + rowH, 5f, Theme.field,
            if (focused) Theme.lav else Theme.rowBorder, 1f,
        )
        val caret = if (focused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        val display = clipFieldText(current + caret, FONT_CTRL, fieldW - 8f)
        val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        drawText(
            ctx, WatermarkFont.TEXT, display, x + 4f, y + (rowH - textH) / 2f, FONT_CTRL,
            if (current.isEmpty() && !focused) Theme.dim else Theme.white,
        )

        val byy = y + (rowH - badge) / 2f
        ctx.drawRoundedRect(bx, byy, bx + badge, byy + badge, 3f,
            lerpColor(Theme.badge, Theme.lav, hoverT), null)
        val xW = textWidth(WatermarkFont.TEXT, "x", FONT_CTRL)
        drawText(ctx, WatermarkFont.TEXT, "x", bx + (badge - xW) / 2f, byy + (badge - textH) / 2f,
            FONT_CTRL, lerpColor(Theme.white, Theme.lavDark, hoverT))

        hits += Hit(x, y, x + fieldW, y + rowH, inScroll = true) { button ->
            if (button == 0) {
                beginKeyedEdit(key, text) { commitMutableListEntry(value, index, it, regex) }
                true
            } else false
        }
        hits += Hit(bx - 2f, y, x + w, y + rowH, inScroll = true) { button ->
            if (button == 0) { removeListEntry(value, index); true } else false
        }
    }

    /** The "+" row of [drawMutableList]: appends a new empty entry and focuses it (LMB). */
    private fun drawMutableListAddRow(
        ctx: GuiGraphicsExtractor, value: MutableListValue<*, *>, regex: Boolean,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ) {
        val rowH = 14f
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + rowH
        val hKey = "mlistadd:" + System.identityHashCode(value)
        if (hovered) hoveredListKey = hKey
        val hoverT = easeInOutCubic(listHoverProgress[hKey] ?: 0f)

        ctx.drawRoundedRect(x, y, x + w, y + rowH, 5f, Theme.listBg,
            lerpColor(Theme.rowBorder, Theme.lav, hoverT), 1f)
        val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
        val plusW = textWidth(WatermarkFont.TEXT, "+", FONT_CTRL)
        drawText(ctx, WatermarkFont.TEXT, "+", x + (w - plusW) / 2f, y + (rowH - textH) / 2f,
            FONT_CTRL, lerpColor(Theme.desc, Theme.lav, hoverT))

        hits += Hit(x, y, x + w, y + rowH, inScroll = true) { button ->
            if (button == 0) { addListEntry(value, regex); true } else false
        }
    }

    /**
     * Commits one entry edit: regex entries reject invalid patterns (the old entry stays,
     * like the plain regex text field); unchanged text is skipped. List payloads are
     * indexed in place; set payloads swap the element (remove + add).
     */
    @Suppress("UNCHECKED_CAST")
    private fun commitMutableListEntry(value: MutableListValue<*, *>, index: Int, text: String, regex: Boolean) {
        val element: Any = if (regex) {
            try {
                Regex(text)
            } catch (e: Throwable) {
                logger.warn("LeetClickGui: invalid regex for ${value.name}: ${e.message}")
                return
            }
        } else {
            text
        }
        val coll = value.get() as MutableCollection<Any>
        val old = coll.elementAtOrNull(index) ?: return
        if (old == element) return
        if (coll is MutableList<*>) {
            (coll as MutableList<Any>)[index] = element
        } else {
            coll.remove(old)
            coll.add(element)
        }
        refireListValue(value)
    }

    /** Removes the [index]-th entry of a MUTABLE_LIST value (list: by index, set: by element). */
    @Suppress("UNCHECKED_CAST")
    private fun removeListEntry(value: MutableListValue<*, *>, index: Int) {
        val coll = value.get() as MutableCollection<Any>
        if (coll is MutableList<*>) {
            if (index !in coll.indices) return
            (coll as MutableList<Any>).removeAt(index)
        } else {
            val old = coll.elementAtOrNull(index) ?: return
            coll.remove(old)
        }
        refireListValue(value)
    }

    /** Appends a new empty entry to a MUTABLE_LIST value, then focuses its row for typing. */
    @Suppress("UNCHECKED_CAST")
    private fun addListEntry(value: MutableListValue<*, *>, regex: Boolean) {
        val element: Any = if (regex) Regex("") else ""
        (value.get() as MutableCollection<Any>).add(element)
        refireListValue(value)
        val index = value.get().size - 1
        val key = "mlist:" + System.identityHashCode(value) + ":" + index
        beginKeyedEdit(key, "") { commitMutableListEntry(value, index, it, regex) }
    }

    // - File setting (FILE) -

    /**
     * FILE setting: badge row with the current file name (clipped from the left to fit).
     * LMB opens the SAME native TinyFileDialogs dialog [FileValue] defines for its dialog
     * mode (a cancelled dialog is an empty selection = no-op); RMB resets to the default.
     */
    private fun drawFileRow(ctx: GuiGraphicsExtractor?, value: FileValue, x: Float, y: Float, w: Float): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val badgeH = 13f
        val rowH = maxOf(nameH, badgeH)

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            val maxBadgeW = minOf(CTRL_W + 30f, w * 0.6f)
            val label = clipFieldText(value.get().name.ifEmpty { "?" }, FONT_CTRL, maxBadgeW - 12f)
            val badgeW = textWidth(WatermarkFont.TEXT, label, FONT_CTRL) + 12f
            val bx = x + w - badgeW
            val byy = y + (rowH - badgeH) / 2f
            ctx.drawRoundedRect(bx, byy, bx + badgeW, byy + badgeH, 4f, Theme.badge, null)
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, label, bx + 6f, byy + (badgeH - textH) / 2f, FONT_CTRL, Theme.white)

            hits += Hit(bx, byy, bx + badgeW, byy + badgeH, inScroll = true) { button ->
                when (button) {
                    0 -> { openFileDialog(value); true }
                    1 -> { value.restore(); true }
                    else -> false
                }
            }
        }
        return y + rowH
    }

    /** Opens the native file dialog of [value]'s dialog mode and applies the first pick. */
    private fun openFileDialog(value: FileValue) {
        try {
            val path = value.dialogMode.selectFiles(value.supportedExtensions).firstOrNull() ?: return
            value.set(File(path))
        } catch (e: Throwable) {
            logger.warn("LeetClickGui: file dialog for ${value.name} failed: ${e.message}")
        }
    }

    // - Vector settings (VECTOR3_I / VECTOR3_D / VECTOR2_F) -

    /**
     * Vector setting: one row of 2-3 compact numeric fields (X, Y, Z) in the text-field
     * look. Each field is a keyed edit target (per-axis keys, commit on Enter/click-away);
     * invalid input is rejected and keeps the old value, int axes round.
     */
    private fun drawVecFields(ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, y: Float, w: Float): Float {
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val fieldH = 14f
        val rowH = maxOf(nameH, fieldH)
        val axes = if (value.valueType == ValueType.VECTOR2_F) 2 else 3
        val gap = 3f
        val fieldW = (minOf(CTRL_W, w * 0.55f) - gap * (axes - 1)) / axes
        val axisLetters = arrayOf("X", "Y", "Z")

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            for (axis in 0 until axes) {
                val fx = x + w - fieldW * (axes - axis) - gap * (axes - 1 - axis)
                val fy = y + (rowH - fieldH) / 2f
                val key = "vec:" + System.identityHashCode(value) + ":" + axis
                val focused = focusedFieldKey == key
                val current = if (focused) editBuffer else vecAxisText(value, axis)
                ctx.drawRoundedRect(
                    fx, fy, fx + fieldW, fy + fieldH, 5f, Theme.field,
                    if (focused) Theme.lav else Theme.rowBorder, 1f,
                )
                val letter = axisLetters[axis]
                val letterW = textWidth(WatermarkFont.TEXT, "$letter ", FONT_CTRL)
                drawText(ctx, WatermarkFont.TEXT, letter, fx + 3f, fy + (fieldH - textH) / 2f,
                    FONT_CTRL, Theme.dim)
                val caret = if (focused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
                val display = clipFieldText(current + caret, FONT_CTRL, fieldW - 6f - letterW)
                drawText(ctx, WatermarkFont.TEXT, display, fx + 3f + letterW,
                    fy + (fieldH - textH) / 2f, FONT_CTRL, Theme.white)

                hits += Hit(fx, fy, fx + fieldW, fy + fieldH, inScroll = true) { button ->
                    if (button == 0) {
                        beginKeyedEdit(key, vecAxisText(value, axis)) { commitVecAxis(value, axis, it) }
                        true
                    } else false
                }
            }
        }
        return y + rowH
    }

    /** Current text of one vector axis (ints plain, floats/doubles via [fmtAxis]). */
    private fun vecAxisText(value: Value<*>, axis: Int): String = when (val v = value.get()) {
        is Vec3i -> when (axis) { 0 -> v.x.toString(); 1 -> v.y.toString(); else -> v.z.toString() }
        is Vec3 -> when (axis) { 0 -> fmtAxis(v.x); 1 -> fmtAxis(v.y); else -> fmtAxis(v.z) }
        is Vector2fc -> fmtAxis((if (axis == 0) v.x() else v.y()).toDouble())
        else -> "0"
    }

    /** Compact axis display: integers without decimals, others trimmed to two decimals. */
    private fun fmtAxis(v: Double): String =
        if (v == floor(v) && abs(v) < 1e9) {
            v.toLong().toString()
        } else {
            "%.2f".format(v).trimEnd('0').trimEnd('.')
        }

    /** Parses [text] into axis [axis] of [value]; invalid input keeps the old value. */
    @Suppress("UNCHECKED_CAST")
    private fun commitVecAxis(value: Value<*>, axis: Int, text: String) {
        val parsed = text.trim().toDoubleOrNull() ?: return
        when (val v = value.get()) {
            is Vec3i -> {
                val n = parsed.roundToInt()
                if (n == when (axis) { 0 -> v.x; 1 -> v.y; else -> v.z }) return
                val nv = when (axis) {
                    0 -> Vec3i(n, v.y, v.z)
                    1 -> Vec3i(v.x, n, v.z)
                    else -> Vec3i(v.x, v.y, n)
                }
                (value as Value<Vec3i>).set(nv)
            }
            is Vec3 -> {
                if (parsed == when (axis) { 0 -> v.x; 1 -> v.y; else -> v.z }) return
                val nv = when (axis) {
                    0 -> Vec3(parsed, v.y, v.z)
                    1 -> Vec3(v.x, parsed, v.z)
                    else -> Vec3(v.x, v.y, parsed)
                }
                (value as Value<Vec3>).set(nv)
            }
            is Vector2fc -> {
                val f = parsed.toFloat()
                if (f == (if (axis == 0) v.x() else v.y())) return
                val nv = if (axis == 0) Vector2f(f, v.y()) else Vector2f(v.x(), f)
                (value as Value<Vector2fc>).set(nv)
            }
            else -> Unit
        }
    }

    // - Mutable group list (GROUP_LIST) -

    /**
     * Head of one [MutableGroupListValueGroup] entry: a dim "#N" label and a "-" remove
     * badge (LMB, only while more than one entry exists - mirrors GroupListSetting.svelte).
     */
    @Suppress("LongParameterList")
    private fun drawGroupEntryHead(
        ctx: GuiGraphicsExtractor?, canRemove: Boolean, index: Int, hKey: String,
        x: Float, y: Float, w: Float, mx: Float, my: Float, onRemove: () -> Unit,
    ): Float {
        val rowH = 11f
        if (ctx != null) {
            val textH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, "#${index + 1}", x, y + (rowH - textH) / 2f,
                FONT_CTRL, Theme.dim)
            if (canRemove) {
                val hovered = mx >= x + w - 13f && mx <= x + w && my >= y && my <= y + rowH
                if (hovered) hoveredListKey = hKey
                val hoverT = easeInOutCubic(listHoverProgress[hKey] ?: 0f)
                val badge = 11f
                val bx = x + w - badge
                val byy = y + (rowH - badge) / 2f
                ctx.drawRoundedRect(bx, byy, bx + badge, byy + badge, 3f,
                    lerpColor(Theme.badge, Theme.lav, hoverT), null)
                val mW = textWidth(WatermarkFont.TEXT, "-", FONT_CTRL)
                drawText(ctx, WatermarkFont.TEXT, "-", bx + (badge - mW) / 2f,
                    byy + (badge - textH) / 2f, FONT_CTRL, lerpColor(Theme.white, Theme.lavDark, hoverT))
                hits += Hit(bx - 2f, y, x + w, y + rowH, inScroll = true) { button ->
                    if (button == 0) { onRemove(); true } else false
                }
            }
        }
        return y + rowH
    }

    /**
     * Removes the [index]-th entry of a GROUP_LIST: [ValueGroup.drop] unhooks it from the
     * config tree, then the live entry list (exposed read-only as `entries`) is shrunk.
     * Never removes the last entry (the group's API requires >= 1).
     */
    @Suppress("UNCHECKED_CAST")
    private fun removeGroupEntry(group: MutableGroupListValueGroup<*>, index: Int) {
        if (group.entries.size <= 1) return
        val entry = group.entries.getOrNull(index) ?: return
        group.drop(entry)
        (group.entries as MutableList<ValueGroup>).removeAt(index)
    }

    // - Color setting: swatch row + full HSV/alpha picker -

    private fun pickerStateOf(value: Value<Color4b>): FloatArray = pickerState.getOrPut(value) {
        val c = value.get()
        val hsb = Color.RGBtoHSB(c.r, c.g, c.b, null)
        floatArrayOf(hsb[0], hsb[1], hsb[2])
    }

    @Suppress("LongMethod")
    private fun drawColorSetting(ctx: GuiGraphicsExtractor?, value: Value<Color4b>, x: Float, y: Float, w: Float): Float {
        val color = value.get()
        val t = easeInOutCubic(pickerProgress[value] ?: 0f)
        val nameH = lineHeight(WatermarkFont.TEXT, FONT_SET)
        val rowH = maxOf(nameH, 10f)

        if (ctx != null) {
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + (rowH - nameH) / 2f, FONT_SET, Theme.white)

            // hex + alpha percentage, so alpha changes give instant feedback in the row
            val hex = "#%02X%02X%02X %d%%".format(color.r, color.g, color.b, color.a * 100 / 255)
            val hexW = textWidth(WatermarkFont.TEXT, hex, FONT_CTRL)
            val hexH = lineHeight(WatermarkFont.TEXT, FONT_CTRL)
            drawText(ctx, WatermarkFont.TEXT, hex, x + w - hexW, y + (rowH - hexH) / 2f, FONT_CTRL, Theme.desc)

            val cx = x + w - hexW - 10f
            ctx.drawCircle(cx, y + rowH / 2f, 4.2f) { Color4b(color.r, color.g, color.b, 255).argb }

            hits += Hit(x, y, x + w, y + rowH, inScroll = true) { button ->
                if (button == 0 || button == 1) {
                    if (value in openPickers) {
                        openPickers.remove(value)
                    } else {
                        openPickers.add(value)
                        pickerState.remove(value) // re-sync from the current color
                        pickerStateOf(value)
                    }
                    true
                } else false
            }
        }
        // picker open/close reveal: the body renders under a scissor of fullH * ease(t)
        // and the returned height follows the same value in BOTH passes
        if (t <= 0.001f) return y + rowH

        // picker body (uniform SET_GAP offsets between all picker elements)
        val state = pickerStateOf(value)
        val svH = 46f
        val barH = 8f
        val bodyTop = y + rowH
        val svTop = y + rowH + SET_GAP
        val hueTop = svTop + svH + SET_GAP
        val alphaTop = hueTop + barH + SET_GAP
        val fullH = (alphaTop + barH) - bodyTop
        val shownH = fullH * t

        if (ctx != null && shownH > 1f) {
            // floor(origin) / ceil(end): no pixel row lost to truncation
            val cx0 = floor(toRealX(x).toDouble())
            val cy0 = floor(toRealY(bodyTop).toDouble())
            val clip = ScreenRectangle(
                cx0.toInt(), cy0.toInt(),
                (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                (ceil(toRealY(bodyTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
            )
            ctx.scissorStack.push(clip)
            try {
                // hits below the animated scissor bottom are not clickable yet
                val hitMark = hits.size
                // saturation/value area (grid of smoothly interpolated quads)
                drawSvArea(ctx, x, svTop, w, svH, state[0])
                val knobX = x + state[1] * w
                val knobY = svTop + (1f - state[2]) * svH
                ctx.drawCircle(knobX, knobY, 3.4f) { Theme.white.argb }
                ctx.drawCircle(knobX, knobY, 2.1f) { Color4b(color.r, color.g, color.b, 255).argb }

                hits += Hit(x, svTop, x + w, svTop + svH, inScroll = true) { button ->
                    if (button == 0) {
                        pointerDrag = PointerDrag { px, py ->
                            val st = pickerStateOf(value)
                            val s = ((px - x) / w).coerceIn(0f, 1f)
                            val v = (1f - (py - svTop) / svH).coerceIn(0f, 1f)
                            if (s != st[1] || v != st[2]) { // apply only on real change
                                st[1] = s
                                st[2] = v
                                value.set(Color4b.ofHSB(st[0], st[1], st[2], value.get().a / 255f))
                            }
                        }
                        true
                    } else false
                }

                // hue bar (rounded: the knob is set at the bar center line, so corner
                // rounding never affects the selectable range)
                drawHueBar(ctx, x, hueTop, w, barH)
                val hueX = x + state[0] * w
                ctx.drawCircle(hueX, hueTop + barH / 2f, 3.2f) { Theme.white.argb }
                ctx.drawCircle(hueX, hueTop + barH / 2f, 2f) { Color4b.ofHSB(state[0], 1f, 1f).argb }

                hits += Hit(x, hueTop, x + w, hueTop + barH, inScroll = true) { button ->
                    if (button == 0) {
                        pointerDrag = PointerDrag { px, _ ->
                            val st = pickerStateOf(value)
                            val h = ((px - x) / w).coerceIn(0f, 0.9999f)
                            if (h != st[0]) { // apply only on real change
                                st[0] = h
                                value.set(Color4b.ofHSB(st[0], st[1], st[2], value.get().a / 255f))
                            }
                        }
                        true
                    } else false
                }

                // alpha bar (rounded, same as the hue bar)
                ctx.drawRoundedRect(x, alphaTop, x + w, alphaTop + barH, 3f, Theme.field, Theme.rowBorder, 1f)
                drawAlphaGradient(ctx, x + 1f, alphaTop + 1f, w - 2f, barH - 2f, color)
                val aX = x + (color.a / 255f) * w
                ctx.drawCircle(aX.coerceIn(x + 2f, x + w - 2f), alphaTop + barH / 2f, 3.2f) { Theme.white.argb }
                ctx.drawCircle(aX.coerceIn(x + 2f, x + w - 2f), alphaTop + barH / 2f, 2f) { color.argb }

                hits += Hit(x, alphaTop, x + w, alphaTop + barH, inScroll = true) { button ->
                    if (button == 0) {
                        pointerDrag = PointerDrag { px, _ ->
                            val a = (((px - x) / w).coerceIn(0f, 1f) * 255f).roundToInt()
                            if (a != value.get().a) { // apply only on real change
                                value.set(value.get().with(a = a))
                            }
                        }
                        true
                    } else false
                }
                if (t < 1f) {
                    for (i in hitMark until hits.size) {
                        hits[i].clipBottom = bodyTop + shownH
                    }
                }
            } finally {
                ctx.scissorStack.pop()
            }
        }
        return bodyTop + shownH
    }

    /** Smooth saturation (x) / brightness (y) gradient at [hue], tessellated for fidelity. */
    private fun drawSvArea(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, hue: Float) {
        val cols = 12
        val rows = 6
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
                    val c00 = Color4b.ofHSB(hue, s0, v0).argb
                    val c01 = Color4b.ofHSB(hue, s0, v1).argb
                    val c11 = Color4b.ofHSB(hue, s1, v1).argb
                    val c10 = Color4b.ofHSB(hue, s1, v0).argb
                    addVertexWith2DPose(pose, x0, y0).setColor(c00)
                    addVertexWith2DPose(pose, x0, y1).setColor(c01)
                    addVertexWith2DPose(pose, x1, y1).setColor(c11)
                    addVertexWith2DPose(pose, x1, y0).setColor(c10)
                }
            }
        }
        ctx.drawQuad(x, y, x + w, y + h, null, Theme.rowBorder)
    }

    private fun drawHueBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        ctx.drawSweepRoundedRect(x, y, x + w, y + h, 3f, columns = 24) { fraction ->
            Color4b.ofHSB(fraction.coerceAtMost(0.999f), 1f, 1f).argb
        }
        ctx.drawRoundedRect(x, y, x + w, y + h, 3f, null, Theme.rowBorder, 1f)
    }

    private fun drawAlphaGradient(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, color: Color4b) {
        ctx.drawSweepRoundedRect(x, y, x + w, y + h, 2.5f, columns = 16) { fraction ->
            color.with(a = (fraction * 255f).toInt().coerceIn(0, 255)).argb
        }
    }

    // - Helpers -

    private fun fracOf(v: Float, min: Float, max: Float): Float =
        if (max <= min) 0f else ((v - min) / (max - min)).coerceIn(0f, 1f)

    private fun String.suffixText(): String = if (isBlank()) "" else " $this"

    private val keyNameCache = HashMap<InputConstants.Key, String>()

    private fun keyDisplayName(key: InputConstants.Key): String = keyNameCache.getOrPut(key) {
        if (key == InputConstants.UNKNOWN) {
            "None"
        } else {
            key.name.split('.').drop(2).joinToString("_").uppercase().ifEmpty { key.name }
        }
    }

    private val wrapCache = HashMap<Long, List<String>>()
    private var wrapCacheDensity = 0f

    private fun wrapText(text: String, px: Float, maxWidth: Float): List<String> {
        if (wrapCacheDensity != density) {
            wrapCache.clear()
            wrapCacheDensity = density
        }
        val key = (text.hashCode().toLong() shl 32) or
            ((px * 10).toInt().toLong() shl 16) or
            (maxWidth.toInt().toLong() and 0xFFFF)
        wrapCache[key]?.let { return it }

        val words = text.split(' ')
        val lines = ArrayList<String>(2)
        val sb = StringBuilder()
        for (word in words) {
            val candidate = if (sb.isEmpty()) word else "$sb $word"
            if (textWidth(WatermarkFont.TEXT, candidate, px) <= maxWidth || sb.isEmpty()) {
                sb.setLength(0); sb.append(candidate)
            } else {
                lines.add(sb.toString())
                sb.setLength(0); sb.append(word)
            }
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())
        if (wrapCache.size > 512) wrapCache.clear()
        wrapCache[key] = lines
        return lines
    }

    // - Input -

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (isClosing) return true
        // a new press must never re-target an active drag
        if (activeSlider != null || pointerDrag != null) return true

        // bind capture swallows the click: the clicked mouse button becomes the new bind
        // (action + modifiers preserved) instead of firing the widget under the cursor
        pendingBind?.let { bind ->
            bind.set(bind.get().copy(boundKey = InputConstants.Type.MOUSE.getOrCreate(click.button())))
            pendingBind = null
            return true
        }
        // key capture is keyboard-only; clicks during it must not fire widgets either
        if (pendingKey != null) return true

        val mx = toVirtualX(click.x.toFloat())
        val my = toVirtualY(click.y.toFloat())
        val button = click.button()

        // click outside of focus targets clears them (pending text is committed)
        searchFocused = false
        commitEdit()

        for (hit in hits.asReversed()) {
            if (!hit.contains(mx, my)) continue
            if (hit.inScroll && (my < viewTop || my > viewBottom)) continue
            if (my > hit.clipBottom) continue // scissored away by the collapse animation
            if (hit.onClick(button)) {
                // seed captured drags with the press position immediately
                activeSlider?.let { it.update(((mx - it.trackX) / it.trackW).coerceIn(0f, 1f)) }
                pointerDrag?.update(mx, my)
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        // only the button that started the capture (LMB) ends it
        if (click.button() == 0) {
            activeSlider = null
            pointerDrag = null
            // native-GUI sliders defer their disk write to the end of the drag
            LeetClickGuiSettings.flushDirty()
        }
        return super.mouseReleased(click)
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (click.button() == 0) {
            activeSlider?.let { slider ->
                val vx = toVirtualX(click.x.toFloat())
                slider.update(((vx - slider.trackX) / slider.trackW).coerceIn(0f, 1f))
                return true
            }
            pointerDrag?.let { drag ->
                drag.update(toVirtualX(click.x.toFloat()), toVirtualY(click.y.toFloat()))
                return true
            }
        }
        return super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double,
        horizontalAmount: Double, verticalAmount: Double,
    ): Boolean {
        if (isClosing) return true

        // Ctrl + wheel: zoom the whole GUI, anchored to the screen center.
        val window = mc.window
        val ctrlDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
        if (ctrlDown) {
            uiScale = (uiScale + verticalAmount.toFloat() * 0.08f).coerceIn(0.5f, 1.6f)
            return true
        }

        // wheel over an open registry/items picker list scrolls the LIST, not the pane
        val vmx = toVirtualX(mouseX.toFloat())
        val vmy = toVirtualY(mouseY.toFloat())
        for (region in listScrollRegions) {
            if (vmx >= region.x0 && vmx <= region.x1 && vmy >= region.y0 && vmy <= region.y1) {
                val cur = listScrollOffsets[region.key] ?: 0f
                listScrollOffsets[region.key] =
                    (cur - verticalAmount.toFloat() * REG_ROW_H).coerceIn(0f, region.maxOff)
                return true
            }
        }

        // smooth scroll: the wheel moves the TARGET; drawGui eases scrollY towards it
        scrollTarget = (scrollTarget - verticalAmount.toFloat() * 24f).coerceIn(0f, maxScroll())
        return true
    }

    private fun maxScroll(): Float = (contentHeight - (viewBottom - viewTop)).coerceAtLeast(0f)

    private fun isCtrlDown(): Boolean {
        val window = mc.window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
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

    override fun keyPressed(input: KeyEvent): Boolean {
        if (isClosing) return true
        val key = input.key

        // key binding capture (copy() keeps the existing action + modifiers)
        pendingBind?.let { bind ->
            val old = bind.get()
            when (key) {
                // ESC cancels the capture, the old bind stays
                GLFW.GLFW_KEY_ESCAPE -> Unit
                // Delete/Backspace unbinds
                GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE ->
                    bind.set(old.copy(boundKey = InputConstants.UNKNOWN))
                else -> bind.set(old.copy(boundKey = InputConstants.Type.KEYSYM.getOrCreate(key)))
            }
            pendingBind = null
            return true
        }
        pendingKey?.let { keyValue ->
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                keyValue.set(InputConstants.UNKNOWN)
            } else {
                keyValue.set(InputConstants.Type.KEYSYM.getOrCreate(key))
            }
            pendingKey = null
            return true
        }

        // arrow / page keys scroll the module pane, like the mouse wheel
        when (key) {
            GLFW.GLFW_KEY_UP -> { scrollTarget = (scrollTarget - 28f).coerceIn(0f, maxScroll()); return true }
            GLFW.GLFW_KEY_DOWN -> { scrollTarget = (scrollTarget + 28f).coerceIn(0f, maxScroll()); return true }
            GLFW.GLFW_KEY_PAGE_UP -> { scrollTarget = (scrollTarget - 210f).coerceIn(0f, maxScroll()); return true }
            GLFW.GLFW_KEY_PAGE_DOWN -> { scrollTarget = (scrollTarget + 210f).coerceIn(0f, maxScroll()); return true }
        }

        // text field editing (buffer-based; commit on Enter, cancel on Escape)
        if (focusedFieldKey != null) {
            when (key) {
                GLFW.GLFW_KEY_BACKSPACE ->
                    editBuffer = if (isCtrlDown()) deleteWordBackward(editBuffer) else editBuffer.dropLast(1)
                GLFW.GLFW_KEY_ENTER -> commitEdit()
                GLFW.GLFW_KEY_ESCAPE -> cancelEdit()
            }
            return true
        }

        // search
        if (searchFocused) {
            when (key) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    searchQuery = if (isCtrlDown()) deleteWordBackward(searchQuery) else searchQuery.dropLast(1)
                    return true
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    searchQuery = ""
                    searchFocused = false
                    // clearing the search replays the category content intro
                    categorySwitchedAt = System.currentTimeMillis()
                    return true
                }
                GLFW.GLFW_KEY_ENTER -> {
                    searchFocused = false
                    return true
                }
            }
            return true
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            startClose()
            return true
        }

        return super.keyPressed(input)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        if (isClosing) return true
        val codepoint = input.codepoint()
        // skip non-BMP codepoints: the font atlas only bakes ASCII + Cyrillic anyway, and
        // toChar() would truncate a surrogate pair into a corrupt lone half
        if (codepoint > 0xFFFF) return false
        val ch = codepoint.toChar()
        if (ch.isISOControl()) return false

        if (focusedFieldKey != null) {
            editBuffer += ch
            return true
        }
        if (searchFocused) {
            searchQuery += ch
            return true
        }
        return super.charTyped(input)
    }

    override fun isPauseScreen(): Boolean = false
}
