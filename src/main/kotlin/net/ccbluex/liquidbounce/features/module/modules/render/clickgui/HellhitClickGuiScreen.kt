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
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.global.GlobalManager
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawSweepRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.ccbluex.liquidbounce.utils.kotlin.getOrPutNullable
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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import org.joml.Vector2f
import org.joml.Vector2fc
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Native (OpenGL, no browser) ClickGUI in the "Hellhit" design language — the classic
 * drop-down layout: free-floating draggable panels, one per module category, plus a
 * "Themes" panel carrying the accent palette.
 *
 * The design is a faithful reproduction of the Hellhit client's ClickGUI:
 *
 *  - every module is a detached, softly-rounded pill row with a status dot on the leading
 *    edge and a gear on the trailing edge;
 *  - the enabled state is an INVERSION — the row fills with the accent and its text goes
 *    dark — not a colour change on the label;
 *  - panels split their modules into named groups: a segmented tab strip under the header
 *    scrolls to the group's caption (it never filters — no module hides behind a tab);
 *  - clicking a row's gear (or RMB anywhere on it) replaces the panel content with the
 *    module's settings page (small back glyph in the page header returns);
 *  - the Themes panel swaps the accent live; "Client" (white) is the reference look.
 *
 * Interaction model, measure/draw contract, frame-driven drags and the bind-capture flow
 * follow [LeetClickGuiScreen] — that part is engine plumbing, not style. All rendering
 * geometry here is its own: this GUI shares no layout code with Leet.
 *
 * Module groups are derived from module NAMES via per-category pattern rules — the module
 * API carries no sub-category, and a hand-written module table would rot with every
 * upstream release. Unmatched modules land in a trailing "Other" group; a category that
 * produces fewer than two non-empty groups renders flat (no tabs, no captions).
 */
@Suppress("LargeClass", "TooManyFunctions")
class HellhitClickGuiScreen :
    Screen("ClickGUI".asPlainText()),
    net.ccbluex.liquidbounce.integration.screen.NativeClientScreen,
    ClickGuiSearchAware {

    override val isSearchFocused: Boolean get() = searchActive


    // ------------------------------------------------------------------ *
    // Palette
    //
    // Achromatic ladder; the single accent comes from the Themes panel
    // (HellhitClickGuiSettings.accent) and defaults to white.
    // ------------------------------------------------------------------ *

    private object Palette {
        val overlayRgb = intArrayOf(5, 5, 6)
        val panel = Color4b(14, 14, 16, 250)
        val panelBorder = Color4b(255, 255, 255, 15)
        val hairline = Color4b(255, 255, 255, 11)
        val row = Color4b(29, 29, 32, 255)
        val rowHover = Color4b(37, 37, 41, 255)
        val well = Color4b(9, 9, 11, 255)
        val track = Color4b(46, 46, 51, 255)
        val knobOff = Color4b(168, 168, 175, 255)
        val white = Color4b(236, 236, 240, 255)
        val textOff = Color4b(198, 198, 204, 255)
        val desc = Color4b(148, 148, 155, 255)
        val dim = Color4b(102, 102, 110, 255)
        val dot = Color4b(86, 86, 94, 255)

        /** Foreground on an accent fill — near-black, so any accent (incl. white) reads. */
        val ink = Color4b(15, 15, 17, 255)
    }

    private val accent: Color4b get() = HellhitClickGuiSettings.accent

    private val accentHover: Color4b get() = lerpColor(accent, Color4b(0, 0, 0, 255), 0.10f)

    // ------------------------------------------------------------------ *
    // Geometry + persistent UI state
    // ------------------------------------------------------------------ *

    private companion object {
        const val PANEL_W = 104f
        const val PAD = 5f
        const val HEAD_H = 16f
        const val TABS_H = 12f
        const val ROW_H = 15f
        const val ROW_GAP = 3f
        const val CAP_H = 10f
        const val PAGE_HEAD_H = 15f
        const val R_PANEL = 6.5f
        const val R_ROW = 4f
        const val R_CTRL = 3f
        const val MAX_CONTENT_H = 235f
        const val SET_GAP = 4f
        const val SET_ROW_H = 12f
        const val CHIP_LIMIT = 6

        /** Curve editor: plot box height, readout line height, spline samples, point cap. */
        const val CURVE_GRAPH_H = 46f
        const val CURVE_READ_H = 9f
        const val CURVE_SAMPLES = 36
        const val CURVE_MAX_POINTS = 12
        const val CURVE_HINT = "drag pts - click adds - rmb deletes"

        const val FONT_TITLE = 7.5f
        const val FONT_ROW = 7f
        const val FONT_CAP = 5.8f
        const val FONT_SET = 6.6f
        const val FONT_CTRL = 6.2f

        /** Descending sizes the vector fields fall back through; see [vecFieldFont]. */
        val VEC_FONT_STEPS = floatArrayOf(FONT_CTRL, 5.5f, 5f)

        const val OPEN_MS = 160f
        const val CLOSE_MS = 140f
        const val REVEAL_OPEN_MS = 240f
        const val STAGGER_MS = 40f
        const val SEARCH_BAR_W = 110f
        const val SEARCH_BAR_H = 14f
        const val HOVER_SEC = 0.10f
        const val REVEAL_SEC = 0.15f
        const val GROUP_SEC = 0.15f
        const val PAGE_SEC = 0.16f
        const val COLOR_LERP_MS = 140f
        const val SCROLL_TAU = 0.03f

        const val THEMES_KEY = "Themes"
        const val SETTINGS_KEY = "Settings"

        // - UI state persisted between openings (in-memory; positions/theme go to disk) -
        val panelOrder = ArrayList<String>()
        val panelPos = HashMap<String, FloatArray>()
        val scrollYMap = HashMap<String, Float>()
        val scrollTargetMap = HashMap<String, Float>()

        /** Panel key -> module name whose settings page is open in that panel. */
        val openModulePage = HashMap<String, String>()
        var themesTab = 0

        /** Panel key -> registry/items value whose picker page sits above the module page. */
        val openPicker = HashMap<String, Value<*>>()

        /** Panel key -> content crossfade (0 = covered, 1 = fully shown). */
        val contentFade = HashMap<String, Float>()

        /** Panel key -> smoothed body height: panels grow and shrink, never snap. */
        val bodyHAnim = HashMap<String, Float>()

        /** Panel key -> deferred mode switch, executed once the fade-out completes. */
        val pendingSwitch = HashMap<String, () -> Unit>()
        val pickerSearch = HashMap<String, String>()
        val pickerTab = HashMap<String, Int>()
        val pickerEntries = IdentityHashMap<Value<*>, List<PickEntry>>()

        /** Panel key -> its groups+modules, built once per open (module set is static). */
        val panelGroupsCache = HashMap<String, List<ModuleGroup>>()

        val openDropdowns: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val dropdownProgress = IdentityHashMap<Any, Float>()
        val groupProgress = IdentityHashMap<Value<*>, Float>()
        val hoverProgress = HashMap<String, Float>()
        val colorLerps = HashMap<String, ColorLerp>()
    }

    // ------------------------------------------------------------------ *
    // Module grouping (name-pattern derived; see class KDoc)
    // ------------------------------------------------------------------ *

    private class ModuleGroup(val name: String, val modules: List<ClientModule>)

    /** One selectable row of the registry/items picker; [stack] is null for iconless registries. */
    class PickEntry(val element: Any, val name: String, val stack: ItemStack?)

    /**
     * A named group inside a panel. [pattern] claims modules by name; the group with
     * `pattern == null` is the default bucket for everything unclaimed. Display order
     * follows the spec order.
     */
    private class GroupSpec(val name: String, val pattern: Regex?)

    /**
     * One drop-down panel of the GUI, mirroring the reference client's taxonomy rather
     * than LiquidBounce's category enum: LB's Render maps to "Visuals", and World, Misc,
     * Exploit and Fun all merge into "Utility". Tab names are the reference's own.
     */
    private class PanelSpec(
        val key: String,
        val iconTag: String,
        val categoryTags: List<String>,
        val groups: List<GroupSpec>,
    )

    private object Panels {
        val SPECS: List<PanelSpec> = listOf(
            PanelSpec(
                "Combat", "hellhit/combat", listOf("Combat"),
                listOf(
                    GroupSpec(
                        "CPvP",
                        Regex("Crystal|Anchor|Totem|Surround|Hole|Trap|BlockIn|BowPop"),
                    ),
                    GroupSpec("Other", null),
                ),
            ),
            PanelSpec(
                "Movement", "hellhit/movement", listOf("Movement"),
                listOf(
                    GroupSpec("Modes", null),
                    GroupSpec("Bypasses", Regex("^(Anti|No[A-Z])|Grim")),
                ),
            ),
            PanelSpec(
                "Visuals", "hellhit/visuals", listOf("Render"),
                listOf(
                    GroupSpec(
                        "ESP",
                        Regex(
                            "ESP|Chams|Tracers|Nametags|Tags|Radar|TrueSight|XRay|" +
                                "Breadcrumbs|NewChunks|Logoff|Owners|Trajector|Outline|Zones",
                        ),
                    ),
                    GroupSpec("World", null),
                ),
            ),
            PanelSpec(
                "Utility", "hellhit/utility", listOf("World", "Misc", "Exploit", "Fun"),
                listOf(
                    GroupSpec("Auto", Regex("^(Auto|Anti)")),
                    GroupSpec("Other", null),
                ),
            ),
            PanelSpec(
                "Player", "hellhit/player", listOf("Player"),
                listOf(
                    GroupSpec("Inventory", Regex("Inventory|Chest|Item|Offhand|Replenish|Eat|Gap|Shop|Cleaner")),
                    GroupSpec("Self", null),
                ),
            ),
        )

        /**
         * Per-module panel overrides — the category enum is too coarse for the reference
         * taxonomy (LB files Scaffold under World and PvpHelper under Misc; here they are
         * Movement and Combat concerns). Checked before the category mapping.
         */
        private val MODULE_OVERRIDES: Map<String, String> = buildMap {
            // combat concerns scattered across World/Misc
            for (name in listOf(
                "PvpHelper", "TargetLock", "CrystalOptimizer", "AntiBot", "Teams",
                "Surround", "HoleFiller", "AutoTrap", "BlockTrap", "BlockIn",
            )) {
                put(name, "Combat")
            }
            // movement concerns filed under World/Player
            for (name in listOf("Scaffold", "Timer", "NoFall", "AutoWalk", "Eagle")) {
                put(name, "Movement")
            }
            // client/meta modules living in Render, and packet tricks from Player
            for (name in listOf(
                "ClickGUI", "HUD", "Rotations", "Debug", "Blink",
            )) {
                put(name, "Utility")
            }
        }

        fun byKey(key: String): PanelSpec? = SPECS.firstOrNull { it.key == key }

        private fun panelKeyOf(module: ClientModule): String? =
            MODULE_OVERRIDES[module.name]
                ?: SPECS.firstOrNull { module.category.tag in it.categoryTags }?.key

        /** Alphabetical — the reference lists are alphabetical. */
        fun modulesOf(spec: PanelSpec): List<ClientModule> =
            ModuleManager.filter { panelKeyOf(it) == spec.key }.sortedBy { it.name }

        /** A single returned group means "render flat": no tab strip, no captions. */
        fun groupsOf(spec: PanelSpec, modules: List<ClientModule>): List<ModuleGroup> {
            if (spec.groups.isEmpty()) return listOf(ModuleGroup(spec.key, modules))
            val buckets = LinkedHashMap<String, MutableList<ClientModule>>()
            for (group in spec.groups) buckets[group.name] = mutableListOf()
            val default = spec.groups.firstOrNull { it.pattern == null }?.name
            for (module in modules) {
                val claimed = spec.groups.firstOrNull { it.pattern?.containsMatchIn(module.name) == true }
                val bucket = claimed?.name ?: default ?: spec.groups.first().name
                buckets.getValue(bucket).add(module)
            }
            val groups = buckets.entries
                .filter { it.value.isNotEmpty() }
                .map { ModuleGroup(it.key, it.value) }
            return if (groups.size > 1) groups else listOf(ModuleGroup(spec.key, modules))
        }
    }

    // ------------------------------------------------------------------ *
    // Icons (flat white PNGs from the shared clickgui texture set, tinted at draw)
    // ------------------------------------------------------------------ *

    private object Icons {
        private val setups = HashMap<String, TextureSetup?>()

        fun get(name: String): TextureSetup? = setups.getOrPutNullable(name) {
            try {
                val image = LiquidBounce.resource("textures/clickgui/$name.png").readNativeImage()
                val texture = image.asTexture { "HellhitClickGui-$name" }
                TextureSetup.singleTexture(
                    texture.textureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                )
            } catch (e: Throwable) {
                logger.error("HellhitClickGui: failed to load icon $name", e)
                null
            }
        }
    }

    private fun drawIcon(ctx: GuiGraphicsExtractor, name: String, cx: Float, cy: Float, size: Float, color: Color4b) {
        val setup = Icons.get(name) ?: return
        val half = size / 2f
        ctx.drawTexQuad(setup, cx - half, cy - half, cx + half, cy + half, argb = color.argb)
    }

    // ------------------------------------------------------------------ *
    // Text
    // ------------------------------------------------------------------ *

    private val dpr: Float get() = mc.window.guiScale.toFloat()

    /**
     * The GUI is DESIGNED at guiScale 2 (the reference screenshot's density): 104-unit
     * panels, 15-unit rows, 7pt row text. [fitScale] maps design units to gui units so
     * the on-screen size stays that of the reference at ANY Minecraft gui scale.
     */
    private val fitScale: Float get() = if (dpr > 0f) 2f / dpr else 1f

    /** Glyph atlases rasterize at design-pt x 2 — the device size the layout targets. */
    private val density: Float get() = 2f

    private fun textWidth(font: WatermarkFont, text: String, px: Float): Float = font.width(text, px, density)

    private fun lineHeight(font: WatermarkFont, px: Float): Float = font.lineHeight(px, density)

    private fun drawText(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, y: Float, px: Float, color: Color4b,
    ) {
        val argb = color.argb
        font.submit(ctx, text, x, y, px, density) { argb }
    }

    /** Text vertically centered on [cy]. */
    private fun drawTextCentered(
        ctx: GuiGraphicsExtractor, font: WatermarkFont,
        text: String, x: Float, cy: Float, px: Float, color: Color4b,
    ) = drawText(ctx, font, text, x, cy - lineHeight(font, px) / 2f, px, color)

    private val clipCache = HashMap<String, String>()

    private fun clipText(font: WatermarkFont, text: String, px: Float, maxW: Float): String {
        val key = "$text|${(px * 10).toInt()}|${maxW.toInt()}|${if (font === WatermarkFont.BRAND) 'b' else 't'}"
        clipCache[key]?.let { return it }
        var result = text
        if (textWidth(font, text, px) > maxW) {
            var t = text
            while (t.isNotEmpty() && textWidth(font, "$t..", px) > maxW) t = t.dropLast(1)
            result = "$t.."
        }
        if (clipCache.size > 1024) clipCache.clear()
        clipCache[key] = result
        return result
    }

    // ------------------------------------------------------------------ *
    // Animation helpers
    // ------------------------------------------------------------------ *

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

    private class ColorLerp(var from: Color4b, var to: Color4b, var startMs: Long) {
        var frame = 0L
    }

    /** Resolved-color watcher: any state-dependent colour lerps toward its new target. */
    private fun watchColor(key: String, target: Color4b): Color4b {
        val now = System.currentTimeMillis()
        val lerp = colorLerps.getOrPut(key) { ColorLerp(target, target, 0L) }
        if (lerp.to != target) {
            // conflict guard: a second writer in the SAME frame does not restart the
            // lerp — otherwise two widgets sharing a key flicker forever
            if (lerp.frame != frameIndex) {
                val t = ((now - lerp.startMs) / COLOR_LERP_MS).coerceIn(0f, 1f)
                lerp.from = lerpColor(lerp.from, lerp.to, t)
                lerp.to = target
                lerp.startMs = now
            }
        }
        lerp.frame = frameIndex
        val t = ((now - lerp.startMs) / COLOR_LERP_MS).coerceIn(0f, 1f)
        return lerpColor(lerp.from, lerp.to, t)
    }

    /** Monotonic frame counter for the [watchColor] conflict guard. */
    private var frameIndex = 0L

    // ------------------------------------------------------------------ *
    // Interaction state
    // ------------------------------------------------------------------ *

    private class Hit(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val clipTop: Float = -Float.MAX_VALUE,
        val clipBottom: Float = Float.MAX_VALUE,
        val onClick: (button: Int) -> Boolean,
    ) {
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

    private class PanelDrag(val key: String, val offX: Float, val offY: Float)

    private class PanelFrame(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val contentTop: Float, val contentBottom: Float, val maxScroll: Float,
    )

    private val hits = ArrayList<Hit>(192)
    private var activeSlider: SliderDrag? = null

    /** Free 2D pointer capture used by the colour picker (fed the virtual mouse each frame). */
    private var pointerDrag: ((vmx: Float, vmy: Float) -> Unit)? = null

    /** Cached HSV state per colour value while its picker is open (RGB is lossy for hue). */
    private val colorHsv = IdentityHashMap<Value<Color4b>, FloatArray>()
    private var panelDrag: PanelDrag? = null
    private var pendingBind: Value<InputBind>? = null
    private var pendingKey: Value<InputConstants.Key>? = null

    // buffered text editing (commit on Enter / click-away)
    private var focusedField: Value<*>? = null
    private var focusedFieldKey: String? = null
    private var editBuffer = ""
    private var keyedCommit: ((String) -> Unit)? = null

    /** Rebuilt every frame; wheel routing and hit clipping read from here. */
    private val panelFrames = HashMap<String, PanelFrame>()

    /** Per-panel group caption offsets (content-relative), recorded by the measure pass. */
    private val groupOffsets = HashMap<String, FloatArray>()

    // per-frame animation feeds (consumed by the NEXT frame's advance)
    private val hoveredKeys = HashSet<String>()
    private val groupTargets = Collections.newSetFromMap(IdentityHashMap<Value<*>, Boolean>())

    private var frameDt = 1f / 60f
    private val openedAt = System.currentTimeMillis()
    private var closingAt = 0L
    private var lastFrameMs = 0L

    private var curScale = 1f
    private var curTx = 0f
    private var curTy = 0f

    /** Screen dimensions in design units (see [fitScale]); layout code uses these. */
    private var vw = 0f
    private var vh = 0f

    private fun toVirtualX(mx: Float): Float = (mx - curTx) / curScale

    private fun toVirtualY(my: Float): Float = (my - curTy) / curScale

    private fun toRealX(vx: Float): Float = vx * curScale + curTx

    private fun toRealY(vy: Float): Float = vy * curScale + curTy

    val isClosing: Boolean get() = closingAt != 0L

    // ------------------------------------------------------------------ *
    // Lifecycle
    // ------------------------------------------------------------------ *

    private fun startClose() {
        if (closingAt == 0L) {
            closingAt = System.currentTimeMillis()
            activeSlider = null
            panelDrag = null
            commitEdit()
            HellhitClickGuiSettings.flushDirty()
        }
    }

    override fun tick() {
        if (closingAt != 0L && System.currentTimeMillis() - closingAt >= CLOSE_MS.toLong()) {
            super.onClose()
        }
    }

    override fun onClose() {
        startClose()
    }

    private var seededOnOpen = false

    override fun init() {
        super.init()
        if (seededOnOpen) return // init() also runs on resize
        seededOnOpen = true

        val keys = panelKeys()
        // seed the order once; keep any session order for panels that still exist
        panelOrder.retainAll(keys)
        for (key in keys) if (key !in panelOrder) panelOrder.add(key)

        // default positions: one centered row in the upper third, like the reference
        val fit = if (mc.window.guiScale > 0) 2f / mc.window.guiScale.toFloat() else 1f
        val designW = width / fit
        val designH = height / fit
        val gapX = 7f
        val total = keys.size * PANEL_W + (keys.size - 1) * gapX
        val rowStart = ((designW - total) / 2f).coerceAtLeast(6f)
        var nextX = rowStart
        var nextY = (designH * 0.24f).coerceAtLeast(10f)
        for (key in keys) {
            val stored = HellhitClickGuiSettings.panelPosition(key)
            val pos = panelPos.getOrPut(key) {
                stored?.copyOf() ?: floatArrayOf(0f, 0f).also { it[0] = Float.NaN }
            }
            if (pos[0].isNaN()) {
                if (nextX + PANEL_W > designW - 6f) {
                    nextX = rowStart
                    nextY += 250f
                }
                pos[0] = nextX
                pos[1] = nextY
                nextX += PANEL_W + gapX
            }
        }

        // prune state whose module/category no longer exists
        val moduleNames = ModuleManager.mapTo(HashSet()) { it.name }
        openModulePage.entries.removeAll { it.key !in keys || it.value !in moduleNames }
        openDropdowns.clear()
        dropdownProgress.clear()
        colorHsv.clear()
        curveActivePoint.clear()
        groupProgress.clear()
        hoverProgress.clear()
        openPicker.clear()
        pickerSearch.clear()
        pickerEntries.clear()
        panelGroupsCache.clear()
        pendingSwitch.clear()
        contentFade.clear()
        bodyHAnim.clear()
    }

    private fun panelKeys(): List<String> = buildList {
        for (spec in Panels.SPECS) {
            if (Panels.modulesOf(spec).isNotEmpty()) add(spec.key)
        }
        add(SETTINGS_KEY)
        add(THEMES_KEY)
    }

    // ------------------------------------------------------------------ *
    // Frame
    // ------------------------------------------------------------------ *

    @Suppress("CognitiveComplexMethod", "LongMethod")
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        hits.clear()
        panelFrames.clear()

        val now = System.currentTimeMillis()
        val dtSec = (if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 50L)) / 1000f
        lastFrameMs = now
        frameDt = dtSec
        frameIndex++
        advanceAnimations(dtSec)

        val openT = ((now - openedAt) / OPEN_MS).coerceIn(0f, 1f)
        val closeT = if (closingAt == 0L) 0f else ((now - closingAt) / CLOSE_MS).coerceIn(0f, 1f)
        val motion = easeInOutCubic(openT) * (1f - easeInOutCubic(closeT))

        // page dim: subtle — the reference GUI sits on the world, it does not black it out
        val (or, og, ob) = Palette.overlayRgb
        ctx.fill(0, 0, width, height, ARGB.color((110 * motion).toInt().coerceIn(0, 255), or, og, ob))

        // design-fit + pop transform: content lays out in design units (guiScale-2
        // density) and scales to the screen; the 0.97 -> 1.0 pop anchors to the screen
        // center; at rest the translation snaps to the device grid to keep text crisp
        val pop = 0.97f + 0.03f * motion
        curScale = fitScale * pop
        vw = width / fitScale
        vh = height / fitScale
        val rawTx = width / 2f * (1f - pop)
        val rawTy = height / 2f * (1f - pop) + (1f - motion) * 6f
        val atRest = motion >= 0.999f
        val d = dpr
        curTx = if (atRest && d > 0f) Math.round(rawTx * d) / d else rawTx
        curTy = if (atRest && d > 0f) Math.round(rawTy * d) / d else rawTy

        // (`now` is reused by the per-panel reveal stagger in the draw loop below)
        val vmx = toVirtualX(mouseX.toFloat())
        val vmy = toVirtualY(mouseY.toFloat())

        // frame-driven capture: poll the physical button, apply drags from the current
        // position, release exactly when the button is up
        val leftDown = GLFW.glfwGetMouseButton(mc.window.handle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS
        if (!leftDown) {
            if (panelDrag != null || activeSlider != null || pointerDrag != null) {
                panelDrag = null
                activeSlider = null
                pointerDrag = null
                HellhitClickGuiSettings.flushDirty()
            }
        } else {
            activeSlider?.let { it.update(((vmx - it.trackX) / it.trackW).coerceIn(0f, 1f)) }
            pointerDrag?.invoke(vmx, vmy)
            panelDrag?.let { drag ->
                val pos = panelPos[drag.key] ?: return@let
                pos[0] = (vmx - drag.offX).coerceIn(2f, vw - PANEL_W - 2f)
                pos[1] = (vmy - drag.offY).coerceIn(2f, vh - HEAD_H - 4f)
                HellhitClickGuiSettings.setPanelPosition(drag.key, pos[0], pos[1])
            }
        }

        mxNow = vmx
        myNow = vmy

        ctx.pose().pushMatrix()
        ctx.pose().translate(curTx, curTy)
        ctx.pose().scale(curScale, curScale)
        try {
            drawSearchBar(ctx)
            for ((index, key) in panelOrder.withIndex()) {
                // staggered drop-down: each panel unrolls from its header, left to right
                val revealT = if (closingAt != 0L) {
                    motion
                } else {
                    easeInOutCubic(((now - openedAt - index * STAGGER_MS) / REVEAL_OPEN_MS).coerceIn(0f, 1f))
                }
                drawPanel(ctx, key, vmx, vmy, revealT)
            }
        } finally {
            ctx.pose().popMatrix()
        }
    }

    /**
     * Global search bar (Ctrl+F): a small pinned slab top-center in the panel style —
     * lupa icon + live query. Revealed by fade + a top-edge unroll; text stays put.
     */
    private fun drawSearchBar(ctx: GuiGraphicsExtractor) {
        val t = easeInOutCubic(searchT)
        if (t <= 0.002f) return

        val w = SEARCH_BAR_W
        val h = SEARCH_BAR_H
        val x = (vw - w) / 2f
        val y = 8f

        fun Color4b.faded(): Color4b = with(a = (a * t).toInt().coerceIn(0, 255))

        val sx0 = floor(toRealX(x).toDouble())
        val sy0 = floor(toRealY(y).toDouble())
        val clip = ScreenRectangle(
            sx0.toInt(), sy0.toInt(),
            (ceil(toRealX(x + w).toDouble()) - sx0).toInt(),
            (ceil(toRealY(y + h * t).toDouble()) - sy0).toInt().coerceAtLeast(1),
        )
        ctx.scissorStack.push(clip)
        try {
            ctx.drawRoundedRect(x, y, x + w, y + h, h / 2f, Palette.panel.faded(), Palette.panelBorder.faded(), 1f)
            drawIcon(ctx, "hellhit/search", x + 9f, y + h / 2f, 7f, Palette.desc.faded())

            val cy = y + h / 2f
            val textX = x + 16f
            if (searchQuery.isEmpty() && !searchActive) {
                drawTextCentered(ctx, WatermarkFont.TEXT, "Search", textX, cy, FONT_ROW, Palette.dim.faded())
            } else {
                val text = clipText(WatermarkFont.TEXT, searchQuery, FONT_ROW, w - 26f)
                drawTextCentered(ctx, WatermarkFont.TEXT, text, textX, cy, FONT_ROW, Palette.white.faded())
                if (searchActive) {
                    drawCaret(ctx, textX + textWidth(WatermarkFont.TEXT, text, FONT_ROW), cy, Palette.white.faded())
                }
            }
        } finally {
            ctx.scissorStack.pop()
        }

        hits += Hit(x, y, x + w, y + h) { button ->
            if (button == 0) {
                commitEdit()
                searchFocusPanel = null
                searchActive = true
                true
            } else {
                false
            }
        }
    }

    /** Advances every progress map ONCE per frame from the previous frame's feeds. */
    @Suppress("CognitiveComplexMethod")
    private fun advanceAnimations(dtSec: Float) {
        fun step(cur: Float, target: Float, sec: Float): Float {
            // Must settle EXACTLY at the target. If cur == target the value is done;
            // the old `if (target > cur) up else down` fell into the down branch at
            // rest (cur == target == 1), dropping to 1-delta and bouncing back next
            // frame — a permanent 1px shimmer under any fully-open group.
            if (cur == target) return target
            val delta = dtSec / sec
            return if (target > cur) (cur + delta).coerceAtMost(target) else (cur - delta).coerceAtLeast(target)
        }

        for (key in hoverProgress.keys.toList()) {
            val t = step(hoverProgress.getValue(key), if (key in hoveredKeys) 1f else 0f, HOVER_SEC)
            if (t <= 0f && key !in hoveredKeys) hoverProgress.remove(key) else hoverProgress[key] = t
        }
        for (key in hoveredKeys) hoverProgress.getOrPut(key) { 0f }
        hoveredKeys.clear()

        for (key in dropdownProgress.keys.toList()) {
            val target = if (key in openDropdowns) 1f else 0f
            dropdownProgress[key] = step(dropdownProgress.getValue(key), target, REVEAL_SEC)
        }
        for (key in openDropdowns) dropdownProgress.getOrPut(key) { 0f }

        for (value in groupProgress.keys.toList()) {
            groupProgress[value] = step(groupProgress.getValue(value), if (value in groupTargets) 1f else 0f, GROUP_SEC)
        }
        for (value in groupTargets) groupProgress.getOrPut(value) { 0f }
        groupTargets.clear()

        // content crossfade: fade OUT while a switch is pending, execute the switch at
        // zero, then fade back IN over the new content
        for (key in contentFade.keys.toList()) {
            val t = step(contentFade.getValue(key), if (key in pendingSwitch) 0f else 1f, PAGE_SEC)
            contentFade[key] = t
            if (t <= 0.001f) {
                pendingSwitch.remove(key)?.invoke()
            }
        }
        for (key in pendingSwitch.keys) contentFade.getOrPut(key) { 1f }

        searchT = step(searchT, if (searchActive) 1f else 0f, REVEAL_SEC)
    }

    // ------------------------------------------------------------------ *
    // Panel
    // ------------------------------------------------------------------ *

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun drawPanel(ctx: GuiGraphicsExtractor, key: String, mx: Float, my: Float, revealT: Float) {
        val pos = panelPos[key] ?: return
        val x = pos[0].coerceIn(2f, vw - PANEL_W - 2f)
        val y = pos[1].coerceIn(2f, vh - HEAD_H - 4f)
        val w = PANEL_W
        val innerX = x + PAD
        val innerW = w - PAD * 2f
        currentPanelKey = key

        // resolve the content mode once; the pinned chrome and the body both need it
        val isThemes = key == THEMES_KEY
        val isSettings = key == SETTINGS_KEY
        val special = isThemes || isSettings
        val picker = if (special) null else openPicker[key]
        val page = if (special || picker != null) null else openPageModule(key)
        var listGroups: List<ModuleGroup>? = null
        if (!special && picker == null && page == null) {
            Panels.byKey(key)?.let { spec ->
                var groups = panelGroupsCache.getOrPut(key) {
                    Panels.groupsOf(spec, Panels.modulesOf(spec))
                }
                // global search: keep only matching modules; empty groups vanish and the
                // panel simply shrinks around whatever is left
                val query = searchQuery.trim()
                if (query.isNotEmpty()) {
                    groups = groups.mapNotNull { group ->
                        group.modules.filter { it.name.contains(query, ignoreCase = true) }
                            .takeIf { it.isNotEmpty() }
                            ?.let { ModuleGroup(group.name, it) }
                    }
                }
                listGroups = groups
            }
        }
        val grouped = (listGroups?.size ?: 0) > 1

        // pinned chrome: tab strips, page/picker headers and the search field stay put
        // regardless of scroll — only the body below them moves
        val pinnedH = when {
            isThemes -> 3f + TABS_H + 3f
            isSettings -> 2f
            picker != null -> 2f + PAGE_HEAD_H + 4f + SET_ROW_H + 3f + TABS_H + 3f
            page != null -> 2f + PAGE_HEAD_H + 6f
            grouped -> 3f + TABS_H + 3f
            else -> 2f
        }

        val measured = measureBody(key, picker, page, listGroups, innerX, innerW, mx, my)
        val avail = (vh - y - HEAD_H - pinnedH - 10f).coerceAtLeast(20f)
        val bodyH = measured.coerceAtMost(minOf(MAX_CONTENT_H, avail))

        // smoothed body height: dropdown reveals, page switches and search refilters
        // GROW and SHRINK the slab instead of snapping it (exponential ~70 ms settle)
        val prevBody = bodyHAnim[key] ?: bodyH
        val bodyDiff = bodyH - prevBody
        val shownBody = if (abs(bodyDiff) < 0.5f) bodyH else prevBody + bodyDiff * (1f - exp(-frameDt / 0.045f))
        bodyHAnim[key] = shownBody

        // drop-down reveal: everything below the header unrolls with [revealT]
        val fullContentH = pinnedH + shownBody + PAD
        val shownContentH = fullContentH * revealT
        val panelH = HEAD_H + shownContentH

        val maxScroll = (measured - bodyH).coerceAtLeast(0f)
        val scroll = updateScroll(key, maxScroll)

        val contentTop = y + HEAD_H + pinnedH + 1f
        val contentBottom = minOf(y + HEAD_H + pinnedH + shownBody, y + panelH - 1f)
        panelFrames[key] = PanelFrame(x, y, w, panelH, contentTop, contentBottom, maxScroll)

        // any click inside the panel body belongs to THIS panel: swallow what no widget
        // takes, so nothing falls through to a panel covered underneath
        hits += Hit(x, y, x + w, y + panelH) { true }

        // slab
        ctx.drawRoundedRect(x, y, x + w, y + panelH, R_PANEL, Palette.panel, Palette.panelBorder, 1f)

        // header: icon + centered title, hairline under, drag hit
        drawPanelHeader(ctx, key, x, y, w)

        // everything below the header rides the reveal scissor while unrolling
        val px0 = floor(toRealX(x + 1f).toDouble())
        val py0 = floor(toRealY(y + HEAD_H).toDouble())
        val revealClip = ScreenRectangle(
            px0.toInt(), py0.toInt(),
            (ceil(toRealX(x + w - 1f).toDouble()) - px0).toInt(),
            (ceil(toRealY(y + panelH).toDouble()) - py0).toInt().coerceAtLeast(0),
        )
        if (revealT <= 0.002f) return
        ctx.scissorStack.push(revealClip)
        try {
            // pinned chrome (it never rides the SCROLL, only the reveal)
            val pinnedY = y + HEAD_H
            when {
                isThemes -> drawThemesTabs(ctx, innerX, pinnedY + 3f, innerW)
                picker != null -> drawPickerChrome(ctx, key, picker, innerX, pinnedY + 2f, innerW, mx, my)
                page != null -> drawPageHeader(ctx, key, page, innerX, pinnedY + 2f, innerW, mx, my)
                grouped -> drawListTabs(ctx, key, listGroups!!, innerX, pinnedY + 3f, innerW, maxScroll)
            }

            // body, scissored to the scroll viewport
            val sx0 = floor(toRealX(x + 1f).toDouble())
            val sy0 = floor(toRealY(contentTop).toDouble())
            val clip = ScreenRectangle(
                sx0.toInt(), sy0.toInt(),
                (ceil(toRealX(x + w - 1f).toDouble()) - sx0).toInt(),
                (ceil(toRealY(contentBottom).toDouble()) - sy0).toInt().coerceAtLeast(1),
            )
            ctx.scissorStack.push(clip)
            try {
                drawBody(
                    ctx, key, picker, page, listGroups,
                    innerX, contentTop - scroll, innerW, mx, my, contentTop, contentBottom,
                )
            } finally {
                ctx.scissorStack.pop()
            }

            // content crossfade: a cover of the panel surface hides the swap in both
            // directions — motion without moving a single glyph (no pixel-snap jitter)
            val fadeT = easeInOutCubic(contentFade[key] ?: 1f)
            if (fadeT < 0.999f) {
                val cover = Color4b(14, 14, 16, (232 * (1f - fadeT)).toInt().coerceIn(0, 255))
                ctx.drawQuad(x + 1f, y + HEAD_H, x + w - 1f, y + panelH - 1f, cover, null)
            }
        } finally {
            ctx.scissorStack.pop()
        }
    }

    private fun drawPanelHeader(ctx: GuiGraphicsExtractor, key: String, x: Float, y: Float, w: Float) {
        val cy = y + HEAD_H / 2f
        val title = key
        val iconTag = when (key) {
            THEMES_KEY -> "hellhit/palette"
            SETTINGS_KEY -> "hellhit/gear"
            else -> Panels.byKey(key)?.iconTag ?: "hellhit/utility"
        }
        val titleW = textWidth(WatermarkFont.BRAND, title, FONT_TITLE)
        val iconSize = 7.5f
        val blockW = iconSize + 3f + titleW
        val bx = x + (w - blockW) / 2f
        drawIcon(ctx, iconTag, bx + iconSize / 2f, cy, iconSize, Palette.white)
        drawTextCentered(ctx, WatermarkFont.BRAND, title, bx + iconSize + 3f, cy, FONT_TITLE, Palette.white)
        ctx.drawQuad(x + PAD, y + HEAD_H - 0.6f, x + w - PAD, y + HEAD_H, Palette.hairline, null)

        hits += Hit(x, y, x + w, y + HEAD_H) { button ->
            if (button == 0) {
                panelDrag = PanelDrag(key, mxNow - x, myNow - y)
                // dragged panel goes topmost
                panelOrder.remove(key)
                panelOrder.add(key)
                true
            } else {
                false
            }
        }
    }


    private fun updateScroll(key: String, maxScroll: Float): Float {
        var target = (scrollTargetMap[key] ?: 0f).coerceIn(0f, maxScroll)
        scrollTargetMap[key] = target
        var cur = (scrollYMap[key] ?: 0f).coerceIn(0f, maxScroll)
        val diff = target - cur
        val grid = dpr * curScale
        cur = if (abs(diff) * grid <= 1f) {
            if (grid > 0f) Math.round(target * grid) / grid else target
        } else {
            cur + diff * (1f - exp(-frameDt / SCROLL_TAU))
        }
        scrollYMap[key] = cur
        return cur
    }

    // ------------------------------------------------------------------ *
    // Content dispatch (list mode / module page / themes)
    // ------------------------------------------------------------------ *

    @Suppress("LongParameterList")
    private fun measureBody(
        key: String, picker: Value<*>?, page: ClientModule?, listGroups: List<ModuleGroup>?,
        x: Float, w: Float, mx: Float, my: Float,
    ): Float = when {
        key == THEMES_KEY -> layoutThemesBody(null, x, 0f, w, mx, my)
        key == SETTINGS_KEY -> layoutSettings(null, GlobalManager, x, 0f, w, mx, my)
        picker != null -> layoutPickerRows(null, key, picker, x, 0f, w, mx, my)
        page != null -> layoutPageBody(null, page, x, 0f, w, mx, my)
        listGroups != null -> layoutModuleList(null, key, listGroups, x, 0f, w, mx, my)
        else -> 0f
    } + PAD

    @Suppress("LongParameterList")
    private fun drawBody(
        ctx: GuiGraphicsExtractor, key: String,
        picker: Value<*>?, page: ClientModule?, listGroups: List<ModuleGroup>?,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
        viewTop: Float, viewBottom: Float,
    ) {
        clipTopForHits = viewTop
        clipBottomForHits = viewBottom
        try {
            when {
                key == THEMES_KEY -> layoutThemesBody(ctx, x, y, w, mx, my)
                key == SETTINGS_KEY -> layoutSettings(ctx, GlobalManager, x, y, w, mx, my)
                picker != null -> layoutPickerRows(ctx, key, picker, x, y, w, mx, my)
                // NOTE: bodies draw with no sliding offsets. Text glyphs are pixel-snapped,
                // so a sub-unit slide re-snaps every frame and reads as jitter, not motion.
                page != null -> layoutPageBody(ctx, page, x, y, w, mx, my)
                listGroups != null -> layoutModuleList(ctx, key, listGroups, x, y, w, mx, my)
            }
        } finally {
            clipTopForHits = -Float.MAX_VALUE
            clipBottomForHits = Float.MAX_VALUE
        }
    }

    private var clipTopForHits = -Float.MAX_VALUE
    private var clipBottomForHits = Float.MAX_VALUE

    /** Panel whose content is currently being laid out (threads the key into widgets). */
    private var currentPanelKey = ""

    /** Panel whose picker search field owns keyboard input, or null. */
    private var searchFocusPanel: String? = null

    // - Global module search (Ctrl+F). Instance fields: reopening the GUI resets it. -
    private var searchActive = false
    private var searchQuery = ""
    private var searchT = 0f

    /** Applies a query change and pulses the list panels so the refilter reads as a fade. */
    private fun setSearchQuery(value: String) {
        if (value == searchQuery) return
        searchQuery = value
        for (key in panelOrder) {
            if (key != THEMES_KEY && key != SETTINGS_KEY && openPicker[key] == null && openModulePage[key] == null) {
                contentFade[key] = minOf(contentFade[key] ?: 1f, 0.55f)
            }
        }
    }

    /** Adds a hit clipped to the current panel viewport. */
    private fun addHit(x0: Float, y0: Float, x1: Float, y1: Float, onClick: (Int) -> Boolean) {
        hits += Hit(x0, y0, x1, y1, clipTopForHits, clipBottomForHits, onClick)
    }

    /** Whether a row at [y] with height [h] intersects the current viewport at all. */
    private fun rowVisible(y: Float, h: Float): Boolean =
        y + h >= clipTopForHits - 6f && y <= clipBottomForHits + 6f

    private fun openPageModule(key: String): ClientModule? {
        val name = openModulePage[key] ?: return null
        val module = ModuleManager.firstOrNull { it.name == name }
        if (module == null) openModulePage.remove(key)
        return module
    }

    // ------------------------------------------------------------------ *
    // Module list mode
    // ------------------------------------------------------------------ *

    @Suppress("LongParameterList")
    private fun layoutModuleList(
        ctx: GuiGraphicsExtractor?, key: String, groups: List<ModuleGroup>,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float {
        var y = yStart + 1f
        val grouped = groups.size > 1

        val offsets = FloatArray(groups.size)

        for ((index, group) in groups.withIndex()) {
            offsets[index] = y - yStart
            if (grouped) {
                if (ctx != null && rowVisible(y, CAP_H)) {
                    val capW = textWidth(WatermarkFont.TEXT, group.name, FONT_CAP)
                    drawTextCentered(
                        ctx, WatermarkFont.TEXT, group.name,
                        x + (w - capW) / 2f, y + CAP_H / 2f - 0.5f, FONT_CAP, Palette.dim,
                    )
                }
                y += CAP_H
            }
            for (module in group.modules) {
                y = drawModuleRow(ctx, key, module, x, y, w, mx, my)
                y += ROW_GAP
            }
        }

        if (ctx == null) groupOffsets[key] = offsets
        return y - yStart
    }

    /**
     * The group tab strip — PINNED under the panel header, never scrolls away. The
     * active tab follows the body scroll: whichever group's block the view sits in
     * lights up, including when the user scrolls there by wheel; reaching the end of
     * the body lights the last group even if its caption cannot reach the top.
     */
    @Suppress("CognitiveComplexMethod", "LongMethod", "LongParameterList")
    private fun drawListTabs(
        ctx: GuiGraphicsExtractor, key: String,
        groups: List<ModuleGroup>, x: Float, y: Float, w: Float, maxScroll: Float,
    ) {
        val gap = 2f
        val tabW = (w - gap * (groups.size - 1)) / groups.size
        val offsets = groupOffsets[key]
        val scroll = scrollTargetMap[key] ?: 0f
        var active = 0
        if (offsets != null) {
            for (i in offsets.indices) if (scroll >= offsets[i] - 6f) active = i
            if (maxScroll > 0f && scroll >= maxScroll - 2f) active = offsets.lastIndex
        }
        for ((i, group) in groups.withIndex()) {
            val tx = x + i * (tabW + gap)
            val hovKey = "tab:$key:$i"
            val fill: Color4b
            val fg: Color4b
            if (i == active) {
                fill = watchColor("tabf:$key:$i", accent)
                fg = Palette.ink
            } else {
                fill = watchColor("tabf:$key:$i", Palette.well)
                fg = Palette.desc
            }
            ctx.drawRoundedRect(tx, y, tx + tabW, y + TABS_H, R_CTRL, fill, null)
            val label = clipText(WatermarkFont.TEXT, group.name, FONT_CTRL, tabW - 4f)
            val lw = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
            drawTextCentered(ctx, WatermarkFont.TEXT, label, tx + (tabW - lw) / 2f, y + TABS_H / 2f, FONT_CTRL, fg)
            if (isHover(mxNow, myNow, tx, y, tx + tabW, y + TABS_H)) hoveredKeys += hovKey
            addHit(tx, y, tx + tabW, y + TABS_H) { button ->
                if (button == 0) {
                    val offs = groupOffsets[key]
                    if (offs != null && i < offs.size) {
                        scrollTargetMap[key] = offs[i]
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    // cached per frame inside drawPanel — used by widgets that need hover feedback
    private var mxNow = 0f
    private var myNow = 0f

    private fun isHover(mx: Float, my: Float, x0: Float, y0: Float, x1: Float, y1: Float): Boolean =
        mx >= x0 && mx <= x1 && my >= y0 && my <= y1 && my >= clipTopForHits && my <= clipBottomForHits

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun drawModuleRow(
        ctx: GuiGraphicsExtractor?, panelKey: String, module: ClientModule,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ): Float {
        val h = ROW_H
        if (ctx != null && rowVisible(y, h)) {
            val key = "row:$panelKey:${module.name}"
            val hovered = isHover(mx, my, x, y, x + w, y + h)
            if (hovered) hoveredKeys += key
            val ht = easeInOutCubic(hoverProgress[key] ?: 0f)

            val fillTarget = if (module.enabled) {
                if (hovered) accentHover else accent
            } else {
                lerpColor(Palette.row, Palette.rowHover, ht)
            }
            val fill = watchColor("rowf:$panelKey:${module.name}", fillTarget)
            val fg = watchColor(
                "rowt:$panelKey:${module.name}",
                if (module.enabled) Palette.ink else lerpColor(Palette.textOff, Palette.white, ht),
            )
            val dotColor = if (module.enabled) Palette.ink else lerpColor(Palette.dot, Palette.knobOff, ht)

            ctx.drawRoundedRect(x, y, x + w, y + h, R_ROW, fill, null)
            ctx.drawCircle(x + 7f, y + h / 2f, 1.7f) { dotColor.argb }

            val hasSettings = hasVisibleSettings(module)
            val nameMaxW = w - 14.5f - (if (hasSettings) 12f else 4f)
            val name = clipText(WatermarkFont.TEXT, module.name, FONT_ROW, nameMaxW)
            drawTextCentered(ctx, WatermarkFont.TEXT, name, x + 12.5f, y + h / 2f, FONT_ROW, fg)

            if (hasSettings) {
                val gearAlpha = if (module.enabled) 165 else if (hovered) 190 else 110
                drawIcon(ctx, "hellhit/gear", x + w - 8.5f, y + h / 2f, 7f, fg.with(a = gearAlpha))
                // the gear zone opens the settings page (added after the row → tested first)
            }

            addHit(x, y, x + w, y + h) { button ->
                when (button) {
                    0 -> {
                        module.enabled = !module.enabled
                        true
                    }
                    1 -> {
                        if (hasSettings) requestSwitch(panelKey) { openPage(panelKey, module) }
                        true
                    }
                    2 -> {
                        pendingBind = module.bindValue
                        true
                    }
                    else -> false
                }
            }
            if (hasSettings) {
                addHit(x + w - 14f, y, x + w, y + h) { button ->
                    if (button == 0) {
                        requestSwitch(panelKey) { openPage(panelKey, module) }
                        true
                    } else {
                        false
                    }
                }
            }
        }
        return y + h
    }

    /** Queues a content-mode switch behind the crossfade (no-op while one is running). */
    private fun requestSwitch(panelKey: String, action: () -> Unit) {
        if (panelKey in pendingSwitch) return
        pendingSwitch[panelKey] = action
        contentFade.getOrPut(panelKey) { 1f }
    }

    private fun openPage(panelKey: String, module: ClientModule) {
        openModulePage[panelKey] = module.name
        scrollTargetMap[panelKey] = 0f
        scrollYMap[panelKey] = 0f
    }

    private fun closePage(panelKey: String) {
        openModulePage.remove(panelKey)
        scrollTargetMap[panelKey] = 0f
        scrollYMap[panelKey] = 0f
        commitEdit()
    }

    // ------------------------------------------------------------------ *
    // Module settings page
    // ------------------------------------------------------------------ *

    /** PINNED page header: gear + name + enabled dot + back glyph. Never scrolls. */
    @Suppress("LongParameterList")
    private fun drawPageHeader(
        ctx: GuiGraphicsExtractor, panelKey: String, module: ClientModule,
        x: Float, y: Float, w: Float, mx: Float, my: Float,
    ) {
        val cy = y + PAGE_HEAD_H / 2f
        val enabled = module.enabled
        val dotColor = watchColor("pgdot:${module.name}", if (enabled) accent else Palette.dot)
        drawIcon(ctx, "hellhit/gear", x + 4f, cy, 7f, Palette.desc)
        val name = clipText(WatermarkFont.BRAND, module.name, FONT_TITLE, w - 26f)
        drawTextCentered(ctx, WatermarkFont.BRAND, name, x + 10f, cy, FONT_TITLE, Palette.white)
        ctx.drawCircle(x + 13f + textWidth(WatermarkFont.BRAND, name, FONT_TITLE), cy, 1.5f) { dotColor.argb }

        val backKey = "back:$panelKey"
        val bx = x + w - 8f
        if (isHover(mx, my, bx - 5f, y, x + w, y + PAGE_HEAD_H)) hoveredKeys += backKey
        val bt = easeInOutCubic(hoverProgress[backKey] ?: 0f)
        drawTextCentered(
            ctx, WatermarkFont.BRAND, "<", bx - 2f, cy, FONT_TITLE,
            lerpColor(Palette.dim, Palette.white, bt),
        )
        addHit(bx - 6f, y, x + w, y + PAGE_HEAD_H) { button ->
            if (button == 0) {
                requestSwitch(panelKey) { closePage(panelKey) }
                true
            } else {
                false
            }
        }
        // header row (left of the back glyph) toggles the module
        addHit(x, y, bx - 7f, y + PAGE_HEAD_H) { button ->
            if (button == 0) {
                module.enabled = !module.enabled
                true
            } else {
                false
            }
        }
        ctx.drawQuad(x, y + PAGE_HEAD_H + 1f, x + w, y + PAGE_HEAD_H + 1.6f, Palette.hairline, null)
    }

    /** Scrolling body of the module page: the settings tree alone. */
    private fun layoutPageBody(
        ctx: GuiGraphicsExtractor?, module: ClientModule,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float = layoutSettings(ctx, module, x + 1f, yStart + 2f, w - 2f, mx, my) - yStart

    private fun layoutSettings(
        ctx: GuiGraphicsExtractor?, group: ValueGroup,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float {
        var y = yStart
        for (value in group.inner) {
            if (value.name == "Enabled" || value.name == "Hidden") continue
            if (value.notAnOption) continue
            y = layoutSetting(ctx, value, x, y, w, mx, my)
        }
        return y
    }

    /** Cached per open: answered per row per frame, and the value tree is static. */
    private val visibleSettingsCache = IdentityHashMap<ValueGroup, Boolean>()

    private fun hasVisibleSettings(group: ValueGroup): Boolean =
        visibleSettingsCache.getOrPut(group) {
            group.inner.any { it.name != "Enabled" && it.name != "Hidden" && !it.notAnOption }
        }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongMethod")
    private fun layoutSetting(
        ctx: GuiGraphicsExtractor?, value: Value<*>,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float {
        var y = yStart
        when (value) {
            is ModeValueGroup<*> -> {
                val tags = value.modes.map { it.tag }
                val activeTag = value.activeMode.tag
                y = if (tags.size <= CHIP_LIMIT) {
                    drawChips(
                        ctx, value, value.name, tags, x, y, w,
                        isActive = { it == activeTag },
                        onPick = { value.setByString(it) },
                    )
                } else {
                    drawDropdown(
                        ctx, value, value.name, activeTag, tags, x, y, w, mx, my,
                        isActive = { it == activeTag },
                        onPick = { value.setByString(it); openDropdowns.remove(value) },
                    )
                }
                val active = value.activeMode
                if (hasVisibleSettings(active)) {
                    y += 1f
                    y = drawGroupBox(ctx, x, y, w) { c, cx, cy, cw ->
                        layoutSettings(c, active, cx, cy, cw, mx, my)
                    }
                }
            }

            is ToggleableValueGroup -> {
                if (value.enabled && hasVisibleSettings(value)) groupTargets += value
                y = drawSwitchRow(ctx, value.name, value.enabled, value, x, y, w) {
                    value.enabled = !value.enabled
                }
                val reveal = easeInOutCubic(groupProgress[value] ?: 0f)
                if (reveal > 0.001f && hasVisibleSettings(value)) {
                    val boxTop = y + 2f
                    val fullEnd = drawGroupBox(null, x, boxTop, w) { c, cx, cy, cw ->
                        layoutSettings(c, value, cx, cy, cw, mx, my)
                    }
                    val shownH = (fullEnd - boxTop) * reveal
                    if (ctx != null && shownH > 1f) {
                        val cx0 = floor(toRealX(x).toDouble())
                        val cy0 = floor(toRealY(boxTop).toDouble())
                        val clip = ScreenRectangle(
                            cx0.toInt(), cy0.toInt(),
                            (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                            (ceil(toRealY(boxTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                        )
                        ctx.scissorStack.push(clip)
                        val prevClip = clipBottomForHits
                        clipBottomForHits = minOf(clipBottomForHits, boxTop + shownH)
                        try {
                            drawGroupBox(ctx, x, boxTop, w) { c, cx, cy, cw ->
                                layoutSettings(c, value, cx, cy, cw, mx, my)
                            }
                        } finally {
                            clipBottomForHits = prevClip
                            ctx.scissorStack.pop()
                        }
                    }
                    y = boxTop + shownH
                }
            }

            is BindValue -> {
                val capturing = pendingBind === value
                val label = if (capturing) "..." else keyDisplayName(value.get().boundKey)
                y = drawBadgeRow(ctx, value.name, label, capturing, x, y, w) {
                    pendingBind = if (capturing) null else value
                    pendingKey = null
                    commitEdit()
                }
            }

            is MultiChoiceListValue<*> -> {
                val selected = value.get()
                val tags = value.choices.map { it.tag }
                val isActive = { tag: String -> selected.any { it.tag == tag } }
                val pick = { tag: String ->
                    @Suppress("UNCHECKED_CAST")
                    val v = value as MultiChoiceListValue<Tagged>
                    v.choices.firstOrNull { it.tag == tag }?.let(v::toggle)
                    Unit
                }
                y = if (tags.size <= CHIP_LIMIT) {
                    drawChips(ctx, value, value.name, tags, x, y, w, isActive, pick)
                } else {
                    drawDropdown(
                        ctx, value, value.name, "${selected.size}/${tags.size}", tags, x, y, w, mx, my,
                        isActive = isActive,
                        onPick = pick, // multi keeps the list open
                    )
                }
            }

            is ChoiceListValue<*> -> {
                val currentTag = value.get().tag
                val tags = value.choices.map { it.tag }
                y = if (tags.size <= CHIP_LIMIT) {
                    drawChips(
                        ctx, value, value.name, tags, x, y, w,
                        isActive = { it == currentTag },
                        onPick = { value.setByString(it) },
                    )
                } else {
                    drawDropdown(
                        ctx, value, value.name, currentTag, tags, x, y, w, mx, my,
                        isActive = { it == currentTag },
                        onPick = { value.setByString(it); openDropdowns.remove(value) },
                    )
                }
            }

            is RegistryListValue<*, *> -> {
                val count = (value.get() as? Collection<*>)?.size ?: 0
                val pk = currentPanelKey
                y = drawBadgeRow(ctx, value.name, "$count items", false, x, y, w) {
                    requestSwitch(pk) { openPickerPage(pk, value) }
                }
            }

            is ItemsChooseValue -> {
                val count = (value.get() as? Collection<*>)?.size ?: 0
                val pk = currentPanelKey
                y = drawBadgeRow(ctx, value.name, "$count items", false, x, y, w) {
                    requestSwitch(pk) { openPickerPage(pk, value) }
                }
            }

            is CurveValue -> y = drawCurveRow(ctx, value, x, y, w)

            is MutableListValue<*, *> -> {
                val count = (value.get() as? Collection<*>)?.size ?: 0
                y = drawBadgeRow(ctx, value.name, "$count items", false, x, y, w) {}
            }

            is FileValue -> {
                val name = value.get().name.ifEmpty { "none" }
                y = drawBadgeRow(ctx, value.name, name, false, x, y, w) {}
            }

            is RangedValue<*> -> y = drawSlider(ctx, value, x, y, w)

            is MutableGroupListValueGroup<*> -> {
                // "+ Add" appends a factory entry; each entry is its own toggle group with a
                // "#N ×" head to remove it (never the last). Entries render via layoutSetting,
                // so their key/items/chips/sliders all get the normal fields (fixes ItemBind).
                y = drawBadgeRow(ctx, value.name, "+ Add", false, x, y, w) { value.addEntry() }
                if (value.entries.isNotEmpty()) {
                    y = drawGroupBox(ctx, x, y, w) { c, cx, cy, cw ->
                        var yy = cy
                        for ((index, entry) in value.entries.withIndex()) {
                            yy = drawGroupEntryHead(c, value.entries.size > 1, index, value, cx, yy, cw)
                            yy = layoutSetting(c, entry, cx, yy, cw, mx, my)
                        }
                        yy
                    }
                }
            }

            is ValueGroup -> {
                y = drawCaption(ctx, value.name, x, y)
                y = layoutSettings(ctx, value, x, y, w, mx, my)
            }

            else -> when (value.valueType) {
                ValueType.BOOLEAN -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = value as Value<Boolean>
                    y = drawSwitchRow(ctx, value.name, v.get(), value, x, y, w) {
                        v.set(!v.get())
                    }
                }

                ValueType.TEXT -> {
                    y = when (val raw = value.get()) {
                        // Lists edit as comma-joined text - see the same dispatch in the Leet GUI.
                        is String, is Regex, is Collection<*> -> drawTextRow(ctx, value, x, y, w)
                        else -> drawBadgeRow(ctx, value.name, "n/a", false, x, y, w) {}
                    }
                }

                ValueType.COLOR -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = value as Value<Color4b>
                    y = drawColorRow(ctx, v, x, y, w)
                }

                ValueType.KEY -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = value as Value<InputConstants.Key>
                    val capturing = pendingKey === v
                    val label = if (capturing) "..." else keyDisplayName(v.get())
                    y = drawBadgeRow(ctx, value.name, label, capturing, x, y, w) {
                        pendingKey = if (capturing) null else v
                        pendingBind = null
                        commitEdit()
                    }
                }

                ValueType.VECTOR3_I, ValueType.VECTOR3_D, ValueType.VECTOR2_F ->
                    y = drawVecRow(ctx, value, x, y, w)

                else -> y = drawBadgeRow(ctx, value.name, "n/a", false, x, y, w) {}
            }
        }
        return y + SET_GAP
    }

    // ------------------------------------------------------------------ *
    // Widgets
    // ------------------------------------------------------------------ *

    private fun drawCaption(ctx: GuiGraphicsExtractor?, text: String, x: Float, y: Float): Float {
        if (ctx != null && rowVisible(y, CAP_H)) {
            drawTextCentered(ctx, WatermarkFont.TEXT, text, x + 1f, y + CAP_H / 2f, FONT_CAP, Palette.dim)
        }
        return y + CAP_H
    }

    /** "#N" label with a hover-red "x" remove hit (suppressed for the last entry). */
    @Suppress("LongParameterList")
    private fun drawGroupEntryHead(
        ctx: GuiGraphicsExtractor?, removable: Boolean, index: Int,
        group: MutableGroupListValueGroup<*>, x: Float, y: Float, w: Float,
    ): Float {
        if (ctx != null && rowVisible(y, CAP_H)) {
            val cy = y + CAP_H / 2f
            drawTextCentered(ctx, WatermarkFont.TEXT, "#${index + 1}", x + 1f, cy, FONT_CAP, Palette.dim)
            if (removable) {
                val rx = x + w - 6f
                val hovered = isHover(mxNow, myNow, rx - 4f, y, x + w, y + CAP_H)
                val rmColor = if (hovered) Color4b(244, 63, 94, 255) else Palette.dim
                drawTextCentered(ctx, WatermarkFont.TEXT, "x", rx, cy, FONT_CTRL, rmColor)
                addHit(rx - 4f, y, x + w, y + CAP_H) { button ->
                    if (button == 0) {
                        removeGroupEntry(group, index)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        return y + CAP_H
    }

    /** Removes an entry (never the last one), dropping it from the value tree. */
    @Suppress("UNCHECKED_CAST")
    private fun removeGroupEntry(group: MutableGroupListValueGroup<*>, index: Int) {
        if (group.entries.size <= 1) return
        val entry = group.entries.getOrNull(index) ?: return
        group.drop(entry)
        (group.entries as MutableList<ValueGroup>).removeAt(index)
    }

    /** A recessed container for nested settings — the reference's "Auto Weapon" box. */
    private fun drawGroupBox(
        ctx: GuiGraphicsExtractor?, x: Float, y: Float, w: Float,
        body: (GuiGraphicsExtractor?, Float, Float, Float) -> Float,
    ): Float {
        val innerX = x + 3f
        val innerW = w - 6f
        val innerEnd = body(null, innerX, y + SET_GAP, innerW)
        val bottom = innerEnd
        if (ctx != null) {
            ctx.drawRoundedRect(x, y, x + w, bottom, R_ROW, Palette.well, Palette.hairline, 1f)
            body(ctx, innerX, y + SET_GAP, innerW)
        }
        return bottom
    }

    @Suppress("LongParameterList")
    private fun drawSwitchRow(
        ctx: GuiGraphicsExtractor?, name: String, checked: Boolean, key: Any,
        x: Float, y: Float, w: Float,
        onToggle: () -> Unit,
    ): Float {
        val h = SET_ROW_H
        if (ctx != null && rowVisible(y, h)) {
            val cy = y + h / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, name, FONT_SET, w - 22f), x + 1f, cy, FONT_SET, Palette.desc,
            )

            // pill switch, right-aligned
            val sw = 15f
            val sh = 8f
            val sx = x + w - sw - 1f
            val sy = cy - sh / 2f
            val id = System.identityHashCode(key)
            val trackColor = watchColor("swt:$id", if (checked) accent else Palette.track)
            val knobColor = watchColor("swk:$id", if (checked) Palette.ink else Palette.knobOff)
            val knobX = watchColor(
                "swx:$id",
                if (checked) Color4b(255, 0, 0, 255) else Color4b(0, 0, 0, 255),
            ).r / 255f // red channel as an animated 0..1 scalar
            ctx.drawRoundedRect(sx, sy, sx + sw, sy + sh, sh / 2f, trackColor, null)
            ctx.drawCircle(sx + 4f + knobX * (sw - 8f), cy, 2.8f) { knobColor.argb }

            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    onToggle()
                    true
                } else {
                    false
                }
            }
        }
        return y + h
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    private fun drawSlider(ctx: GuiGraphicsExtractor?, value: RangedValue<*>, x: Float, y: Float, w: Float): Float {
        val labelH = 8f
        val trackH = 6f
        val h = labelH + trackH
        if (ctx != null && rowVisible(y, h)) {
            val labelCy = y + labelH / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, value.name, FONT_SET, w * 0.62f), x + 1f, labelCy, FONT_SET, Palette.desc,
            )
            val valueText = sliderValueText(value)
            val vw = textWidth(WatermarkFont.TEXT, valueText, FONT_SET)
            drawTextCentered(ctx, WatermarkFont.TEXT, valueText, x + w - vw - 1f, labelCy, FONT_SET, Palette.white)

            val trackY = y + labelH + 2.2f
            val trackX = x + 1f
            val trackW = w - 2f
            ctx.drawRoundedRect(trackX, trackY, trackX + trackW, trackY + 2.2f, 1.1f, Palette.track, null)

            val range = rangeFracs(value)
            if (range == null) {
                val frac = singleFrac(value)
                ctx.drawRoundedRect(trackX, trackY, trackX + trackW * frac, trackY + 2.2f, 1.1f, accent, null)
                ctx.drawCircle(trackX + trackW * frac, trackY + 1.1f, 2.4f) { Palette.white.argb }
            } else {
                val (f0, f1) = range
                ctx.drawRoundedRect(
                    trackX + trackW * f0, trackY, trackX + trackW * f1, trackY + 2.2f, 1.1f, accent, null,
                )
                ctx.drawCircle(trackX + trackW * f0, trackY + 1.1f, 2.4f) { Palette.white.argb }
                ctx.drawCircle(trackX + trackW * f1, trackY + 1.1f, 2.4f) { Palette.white.argb }
            }

            addHit(trackX - 2f, trackY - 3f, trackX + trackW + 2f, trackY + 5f) { button ->
                if (button == 0) {
                    activeSlider = if (range == null) {
                        SliderDrag(trackX, trackW) { frac -> applySliderFrac(value, frac) }
                    } else {
                        // range: the grabbed handle is locked for the whole drag; a
                        // collapsed range resolves the handle from the drag direction
                        var handle = -2
                        var pressFrac = Float.NaN
                        SliderDrag(trackX, trackW) { frac ->
                            if (handle == -2) {
                                pressFrac = frac
                                handle = pickRangeHandle(value, frac)
                            }
                            if (handle == -1 && abs(frac - pressFrac) > 0.004f) {
                                handle = if (frac > pressFrac) 1 else 0
                            }
                            if (handle >= 0) applyRangeFrac(value, frac, handle)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
        return y + h
    }

    private fun singleFrac(value: RangedValue<*>): Float = when (value.valueType) {
        ValueType.FLOAT -> {
            @Suppress("UNCHECKED_CAST")
            val range = value.range as ClosedFloatingPointRange<Float>
            fracOf(value.get() as Float, range.start, range.endInclusive)
        }
        ValueType.INT -> {
            val range = value.range as IntRange
            fracOf((value.get() as Int).toFloat(), range.first.toFloat(), range.last.toFloat())
        }
        else -> 0f
    }

    private fun fracOf(v: Float, min: Float, max: Float): Float =
        if (max <= min) 0f else ((v - min) / (max - min)).coerceIn(0f, 1f)

    @Suppress("UNCHECKED_CAST")
    private fun applySliderFrac(value: RangedValue<*>, frac: Float) {
        when (value.valueType) {
            ValueType.FLOAT -> {
                val range = value.range as ClosedFloatingPointRange<Float>
                val raw = range.start + frac * (range.endInclusive - range.start)
                val v = (raw * 100).roundToInt() / 100f
                if (v != value.get()) (value as RangedValue<Float>).set(v)
            }
            ValueType.INT -> {
                val range = value.range as IntRange
                val v = (range.first + frac * (range.last - range.first)).roundToInt().coerceIn(range)
                if (v != value.get()) (value as RangedValue<Int>).set(v)
            }
            else -> Unit
        }
    }

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
                val newRange = if (handle == 0) {
                    v.coerceAtMost(cur.endInclusive)..cur.endInclusive
                } else {
                    cur.start..v.coerceAtLeast(cur.start)
                }
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

    private fun sliderValueText(value: RangedValue<*>): String {
        val suffix = value.suffix
        val text = when (val raw = value.get()) {
            is Float -> fmtFloat(raw)
            is Int -> raw.toString()
            is IntRange -> "${raw.first}-${raw.last}"
            is ClosedFloatingPointRange<*> -> "${fmtFloat(raw.start as Float)}-${fmtFloat(raw.endInclusive as Float)}"
            else -> raw.toString()
        }
        return when {
            suffix.isBlank() -> text
            suffix.length <= 1 -> "$text$suffix"
            else -> "$text $suffix"
        }
    }

    private fun fmtFloat(v: Float): String {
        val s = "%.2f".format(v).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    @Suppress("LongParameterList", "CognitiveComplexMethod")
    private fun drawChips(
        ctx: GuiGraphicsExtractor?, owner: Any, name: String, items: List<String>,
        x: Float, yStart: Float, w: Float,
        isActive: (String) -> Boolean, onPick: (String) -> Unit,
    ): Float {
        var y = drawCaption(ctx, name, x, yStart)
        val chipH = 9f
        val gapX = 2.5f
        val gapY = 2.5f
        var cx = x
        for (item in items) {
            val label = clipText(WatermarkFont.TEXT, item, FONT_CTRL, w - 10f)
            val chipW = textWidth(WatermarkFont.TEXT, label, FONT_CTRL) + 8f
            if (cx + chipW > x + w && cx > x) {
                cx = x
                y += chipH + gapY
            }
            if (ctx != null) {
                val active = isActive(item)
                val key = "chip:${System.identityHashCode(owner)}:$item"
                val hovered = isHover(mxNow, myNow, cx, y, cx + chipW, y + chipH)
                if (hovered) hoveredKeys += key
                val ht = easeInOutCubic(hoverProgress[key] ?: 0f)
                // keyed by the OWNER's identity: setting names ("Mode") and option tags
                // ("Chams") repeat across values, and shared keys make two states fight
                // over one colour lerp -> permanent flicker
                val fill = watchColor(
                    "chf:${System.identityHashCode(owner)}:$item",
                    if (active) accent else lerpColor(Palette.row, Palette.rowHover, ht),
                )
                val fg = watchColor(
                    "cht:${System.identityHashCode(owner)}:$item",
                    if (active) Palette.ink else lerpColor(Palette.desc, Palette.white, ht),
                )
                ctx.drawRoundedRect(cx, y, cx + chipW, y + chipH, R_CTRL, fill, null)
                val lw = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
                drawTextCentered(ctx, WatermarkFont.TEXT, label, cx + (chipW - lw) / 2f, y + chipH / 2f, FONT_CTRL, fg)
                val cxHit = cx
                addHit(cxHit, y, cxHit + chipW, y + chipH) { button ->
                    if (button == 0) {
                        onPick(item)
                        true
                    } else {
                        false
                    }
                }
            }
            cx += chipW + gapX
        }
        return y + chipH
    }

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private fun drawDropdown(
        ctx: GuiGraphicsExtractor?, key: Any, name: String, valueLabel: String, items: List<String>,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
        isActive: (String) -> Boolean, onPick: (String) -> Unit,
    ): Float {
        var y = yStart
        val h = SET_ROW_H
        val open = key in openDropdowns
        val t = easeInOutCubic(dropdownProgress[key] ?: 0f)

        if (ctx != null) {
            val cy = y + h / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, name, FONT_SET, w * 0.5f), x + 1f, cy, FONT_SET, Palette.desc,
            )
            val label = clipText(WatermarkFont.TEXT, valueLabel, FONT_SET, w * 0.42f)
            val lw = textWidth(WatermarkFont.TEXT, label, FONT_SET)
            drawTextCentered(ctx, WatermarkFont.TEXT, label, x + w - lw - 8f, cy, FONT_SET, Palette.white)
            // chevron: a small triangle that flips while the list reveals
            val chx = x + w - 4f
            val dir = 1f - 2f * t
            ctx.drawTriangle(
                chx - 2.2f, cy - 1.1f * dir,
                chx + 2.2f, cy - 1.1f * dir,
                chx, cy + 1.3f * dir,
                Palette.desc, null, cull = false,
            )
            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    if (!openDropdowns.add(key)) openDropdowns.remove(key)
                    true
                } else {
                    false
                }
            }
        }
        y += h

        if (open || t > 0.001f) {
            val rowH = 10f
            val fullH = items.size * rowH + 4f
            val shownH = fullH * t
            if (ctx != null && shownH > 1f) {
                val boxTop = y + 1f
                ctx.drawRoundedRect(x, boxTop, x + w, boxTop + shownH, R_ROW, Palette.well, Palette.hairline, 1f)
                val cx0 = floor(toRealX(x).toDouble())
                val cy0 = floor(toRealY(boxTop).toDouble())
                val clip = ScreenRectangle(
                    cx0.toInt(), cy0.toInt(),
                    (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
                    (ceil(toRealY(boxTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
                )
                ctx.scissorStack.push(clip)
                val prevClip = clipBottomForHits
                clipBottomForHits = minOf(clipBottomForHits, boxTop + shownH)
                try {
                    var ry = boxTop + 2f
                    for (item in items) {
                        val active = isActive(item)
                        val rKey = "dd:${System.identityHashCode(key)}:$item"
                        val hovered = isHover(mx, my, x + 2f, ry, x + w - 2f, ry + rowH)
                        if (hovered) hoveredKeys += rKey
                        val ht = easeInOutCubic(hoverProgress[rKey] ?: 0f)
                        if (ht > 0.01f) {
                            ctx.drawRoundedRect(
                                x + 2f, ry, x + w - 2f, ry + rowH, R_CTRL,
                                Palette.row.with(a = (255 * ht * 0.6f).toInt()), null,
                            )
                        }
                        // radio: hollow ring, filled with the accent when chosen
                        val dcx = x + 7f
                        val dcy = ry + rowH / 2f
                        if (active) {
                            ctx.drawCircle(dcx, dcy, 2f) { accent.argb }
                        } else {
                            ctx.drawCircle(dcx, dcy, 2f) { Palette.dot.argb }
                            ctx.drawCircle(dcx, dcy, 1.1f) { Palette.well.argb }
                        }
                        val label = clipText(WatermarkFont.TEXT, item, FONT_CTRL, w - 18f)
                        drawTextCentered(
                            ctx, WatermarkFont.TEXT, label, x + 12f, dcy, FONT_CTRL,
                            if (active) Palette.white else Palette.desc,
                        )
                        addHit(x + 2f, ry, x + w - 2f, ry + rowH) { button ->
                            if (button == 0) {
                                onPick(item)
                                true
                            } else {
                                false
                            }
                        }
                        ry += rowH
                    }
                } finally {
                    clipBottomForHits = prevClip
                    ctx.scissorStack.pop()
                }
            }
            y += 1f + shownH
        }
        return y
    }

    @Suppress("LongParameterList")
    private fun drawBadgeRow(
        ctx: GuiGraphicsExtractor?, name: String, badge: String, active: Boolean,
        x: Float, y: Float, w: Float,
        onClick: () -> Unit,
    ): Float {
        val h = SET_ROW_H
        if (ctx != null && rowVisible(y, h)) {
            val cy = y + h / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, name, FONT_SET, w * 0.55f), x + 1f, cy, FONT_SET, Palette.desc,
            )
            val label = clipText(WatermarkFont.TEXT, badge, FONT_CTRL, w * 0.4f)
            val lw = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
            val bw = lw + 7f
            val bx = x + w - bw - 1f
            val border = if (active) accent else Palette.hairline
            ctx.drawRoundedRect(bx, cy - 4.5f, bx + bw, cy + 4.5f, R_CTRL, Palette.well, border, 1f)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, label, bx + 3.5f, cy, FONT_CTRL,
                if (active) Palette.white else Palette.desc,
            )
            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    onClick()
                    true
                } else {
                    false
                }
            }
        }
        return y + h
    }

    // ------------------------------------------------------------------ *
    // Text-backed rows (buffered editing)
    // ------------------------------------------------------------------ *

    private fun drawTextRow(
        ctx: GuiGraphicsExtractor?, value: Value<*>,
        x: Float, y: Float, w: Float,
    ): Float {
        val h = SET_ROW_H
        if (ctx != null && rowVisible(y, h)) {
            val cy = y + h / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, value.name, FONT_SET, w * 0.45f), x + 1f, cy, FONT_SET, Palette.desc,
            )
            val fieldKey = "text:${System.identityHashCode(value)}"
            val editing = focusedFieldKey == fieldKey
            val raw = when (val cur = value.get()) {
                is Regex -> cur.pattern
                is Collection<*> -> cur.joinToString(", ") { item ->
                    if (item is Regex) item.pattern else item.toString()
                }
                else -> cur.toString()
            }
            val display = if (editing) editBuffer + CARET else raw
            val text = clipText(WatermarkFont.TEXT, display, FONT_CTRL, w * 0.5f)
            val tw = textWidth(WatermarkFont.TEXT, text, FONT_CTRL)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, text, x + w - tw - 1f, cy, FONT_CTRL,
                if (editing) Palette.white else Palette.textOff,
            )
            if (editing) {
                ctx.drawQuad(x + w - tw - 1f, cy + 4f, x + w - 1f, cy + 4.6f, accent, null)
            }
            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    beginEdit(value, fieldKey, raw)
                    true
                } else {
                    false
                }
            }
        }
        return y + h
    }

    /** HSV state kept while a picker is open, so hue survives at s=0 / v=0. */
    private fun hsvOf(value: Value<Color4b>): FloatArray = colorHsv.getOrPut(value) {
        val c = value.get()
        Color.RGBtoHSB(c.r, c.g, c.b, null)
    }

    @Suppress("LongMethod")
    private fun drawColorRow(
        ctx: GuiGraphicsExtractor?, value: Value<Color4b>,
        x: Float, y: Float, w: Float,
    ): Float {
        val h = SET_ROW_H
        val open = value in openDropdowns
        val t = easeInOutCubic(dropdownProgress[value] ?: 0f)
        val cur = value.get()

        if (ctx != null && rowVisible(y, h)) {
            val cy = y + h / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, value.name, FONT_SET, w * 0.45f), x + 1f, cy, FONT_SET, Palette.desc,
            )
            val fieldKey = "color:${System.identityHashCode(value)}"
            val editing = focusedFieldKey == fieldKey
            val hex = "#%02X%02X%02X".format(cur.r, cur.g, cur.b)
            val display = if (editing) editBuffer + CARET else hex
            val tw = textWidth(WatermarkFont.TEXT, display, FONT_CTRL)
            // swatch (opens the picker); alpha shown as a checker-free flat tint over black
            val sw = 8f
            val sx = x + w - sw - 1f
            val swatchBorder = watchColor("csw:$fieldKey", if (open) accent else Palette.hairline)
            ctx.drawRoundedRect(sx, cy - sw / 2f, sx + sw, cy + sw / 2f, 2f, cur.with(a = 255), swatchBorder, 1f)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, display, sx - tw - 3f, cy, FONT_CTRL,
                if (editing) Palette.white else Palette.textOff,
            )
            // whole row (minus swatch) edits hex; the swatch toggles the picker
            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    beginKeyedEdit(fieldKey, hex.removePrefix("#")) { text ->
                        parseHexColor(text, value.get().a)?.let { value.set(it) }
                    }
                    true
                } else {
                    false
                }
            }
            addHit(sx - 2f, y, x + w, y + h) { button ->
                if (button == 0 || button == 1) {
                    if (!openDropdowns.add(value)) {
                        openDropdowns.remove(value)
                    } else {
                        colorHsv.remove(value) // re-sync from the live colour
                    }
                    commitEdit()
                    true
                } else {
                    false
                }
            }
        }

        var yEnd = y + h
        if (open || t > 0.001f) {
            yEnd = drawColorPickerBody(ctx, value, x, y + h, w, t)
        }
        return yEnd
    }

    /** The expanding HSV + alpha picker body, sized for the Hellhit panel. */
    @Suppress("LongMethod")
    private fun drawColorPickerBody(
        ctx: GuiGraphicsExtractor?, value: Value<Color4b>,
        x: Float, yTop: Float, w: Float, t: Float,
    ): Float {
        val svH = 42f
        val barH = 7f
        val bodyTop = yTop + SET_GAP
        val svTop = bodyTop
        val hueTop = svTop + svH + SET_GAP
        val alphaTop = hueTop + barH + SET_GAP
        val fullH = (alphaTop + barH) - yTop
        val shownH = fullH * t

        if (ctx == null || shownH <= 1f) {
            return yTop + shownH
        }

        val cx0 = floor(toRealX(x).toDouble())
        val cy0 = floor(toRealY(yTop).toDouble())
        val clip = ScreenRectangle(
            cx0.toInt(), cy0.toInt(),
            (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
            (ceil(toRealY(yTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
        )
        ctx.scissorStack.push(clip)
        val prevClip = clipBottomForHits
        clipBottomForHits = minOf(clipBottomForHits, yTop + shownH)
        try {
            val state = hsvOf(value)
            val cur = value.get()

            // saturation / value area
            drawSvArea(ctx, x, svTop, w, svH, state[0])
            val knobX = x + state[1] * w
            val knobY = svTop + (1f - state[2]) * svH
            ctx.drawCircle(knobX.coerceIn(x + 2f, x + w - 2f), knobY.coerceIn(svTop + 2f, svTop + svH - 2f), 2.9f) {
                Palette.white.argb
            }
            ctx.drawCircle(knobX.coerceIn(x + 2f, x + w - 2f), knobY.coerceIn(svTop + 2f, svTop + svH - 2f), 1.8f) {
                Color4b(cur.r, cur.g, cur.b, 255).argb
            }
            addHit(x, svTop, x + w, svTop + svH) { button ->
                if (button == 0) {
                    pointerDrag = { vmx, vmy ->
                        val st = hsvOf(value)
                        st[1] = ((vmx - x) / w).coerceIn(0f, 1f)
                        st[2] = (1f - (vmy - svTop) / svH).coerceIn(0f, 1f)
                        value.set(Color4b.ofHSB(st[0], st[1], st[2], value.get().a / 255f))
                    }
                    true
                } else {
                    false
                }
            }

            // hue bar
            drawHueBar(ctx, x, hueTop, w, barH)
            val hueX = (x + state[0] * w).coerceIn(x + 2f, x + w - 2f)
            ctx.drawCircle(hueX, hueTop + barH / 2f, 3f) { Palette.white.argb }
            ctx.drawCircle(hueX, hueTop + barH / 2f, 1.9f) { Color4b.ofHSB(state[0], 1f, 1f).argb }
            addHit(x, hueTop, x + w, hueTop + barH) { button ->
                if (button == 0) {
                    pointerDrag = { vmx, _ ->
                        val st = hsvOf(value)
                        st[0] = ((vmx - x) / w).coerceIn(0f, 0.9999f)
                        value.set(Color4b.ofHSB(st[0], st[1], st[2], value.get().a / 255f))
                    }
                    true
                } else {
                    false
                }
            }

            // alpha bar
            drawAlphaBar(ctx, x, alphaTop, w, barH, cur)
            val aX = (x + (cur.a / 255f) * w).coerceIn(x + 2f, x + w - 2f)
            ctx.drawCircle(aX, alphaTop + barH / 2f, 3f) { Palette.white.argb }
            ctx.drawCircle(aX, alphaTop + barH / 2f, 1.9f) { cur.with(a = 255).argb }
            addHit(x, alphaTop, x + w, alphaTop + barH) { button ->
                if (button == 0) {
                    pointerDrag = { vmx, _ ->
                        val a = (((vmx - x) / w).coerceIn(0f, 1f) * 255f).roundToInt()
                        if (a != value.get().a) value.set(value.get().with(a = a))
                    }
                    true
                } else {
                    false
                }
            }
        } finally {
            clipBottomForHits = prevClip
            ctx.scissorStack.pop()
        }
        return yTop + shownH
    }

    /** Smooth saturation (x) / brightness (y) gradient at [hue]. */
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
                    addVertexWith2DPose(pose, x0, y0).setColor(Color4b.ofHSB(hue, s0, v0).argb)
                    addVertexWith2DPose(pose, x0, y1).setColor(Color4b.ofHSB(hue, s0, v1).argb)
                    addVertexWith2DPose(pose, x1, y1).setColor(Color4b.ofHSB(hue, s1, v1).argb)
                    addVertexWith2DPose(pose, x1, y0).setColor(Color4b.ofHSB(hue, s1, v0).argb)
                }
            }
        }
        ctx.drawRoundedRect(x, y, x + w, y + h, R_CTRL, null, Palette.hairline, 1f)
    }

    private fun drawHueBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        ctx.drawSweepRoundedRect(x, y, x + w, y + h, h / 2f, columns = 24) { fraction ->
            Color4b.ofHSB(fraction.coerceAtMost(0.999f), 1f, 1f).argb
        }
        ctx.drawRoundedRect(x, y, x + w, y + h, h / 2f, null, Palette.hairline, 1f)
    }

    private fun drawAlphaBar(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, color: Color4b) {
        ctx.drawRoundedRect(x, y, x + w, y + h, h / 2f, Palette.well, null)
        ctx.drawSweepRoundedRect(x + 1f, y + 1f, x + w - 1f, y + h - 1f, (h - 2f) / 2f, columns = 16) { fraction ->
            color.with(a = (fraction * 255f).toInt().coerceIn(0, 255)).argb
        }
        ctx.drawRoundedRect(x, y, x + w, y + h, h / 2f, null, Palette.hairline, 1f)
    }

    private fun parseHexColor(text: String, keepAlpha: Int): Color4b? {
        val s = text.removePrefix("#").trim()
        return runCatching {
            when (s.length) {
                6 -> Color4b(
                    s.substring(0, 2).toInt(16), s.substring(2, 4).toInt(16),
                    s.substring(4, 6).toInt(16), keepAlpha,
                )
                8 -> Color4b(
                    s.substring(0, 2).toInt(16), s.substring(2, 4).toInt(16),
                    s.substring(4, 6).toInt(16), s.substring(6, 8).toInt(16),
                )
                else -> null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ *
    // Curve editor
    // ------------------------------------------------------------------ *

    /**
     * Per-curve control point the readout is showing: the one being dragged, or the one
     * last touched once the drag ends. A missing entry falls back to the usage hint.
     */
    private val curveActivePoint = IdentityHashMap<CurveValue, Int>()

    /**
     * Panel-space geometry of one curve plot: the axis-value to pixel mapping and back.
     * Built fresh every frame from the box the editor was laid out in, so a scrolled or
     * dragged panel needs no invalidation.
     */
    private class CurvePlot(value: CurveValue, boxX: Float, boxY: Float, boxW: Float, boxH: Float) {
        val x = boxX + 3f
        val y = boxY + 3f
        val w = (boxW - 6f).coerceAtLeast(1f)
        val h = (boxH - 6f).coerceAtLeast(1f)

        val xMin = value.xAxis.range.start
        val xMax = value.xAxis.range.endInclusive
        val yMin = value.yAxis.range.start
        val yMax = value.yAxis.range.endInclusive
        val xSpan = (xMax - xMin).takeIf { it > 0f } ?: 1f
        val ySpan = (yMax - yMin).takeIf { it > 0f } ?: 1f

        /** Smallest X distance kept between neighbouring points - see [drawCurveGraph]. */
        val gap get() = xSpan * 0.02f

        fun px(vx: Float) = x + ((vx - xMin) / xSpan).coerceIn(0f, 1f) * w
        fun py(vy: Float) = y + h - ((vy - yMin) / ySpan).coerceIn(0f, 1f) * h
        fun vx(px: Float) = (xMin + (px - x) / w * xSpan).coerceIn(xMin, xMax)
        fun vy(py: Float) = (yMin + (y + h - py) / h * ySpan).coerceIn(yMin, yMax)
    }

    /**
     * Curve setting: the "N pts" badge toggles an inline editor open, the same way the
     * colour swatch toggles the HSV picker. Collapsed, the row is exactly what it was.
     */
    @Suppress("CognitiveComplexMethod")
    private fun drawCurveRow(
        ctx: GuiGraphicsExtractor?, value: CurveValue,
        x: Float, y: Float, w: Float,
    ): Float {
        val h = SET_ROW_H
        val open = value in openDropdowns
        val t = easeInOutCubic(dropdownProgress[value] ?: 0f)

        if (ctx != null && rowVisible(y, h)) {
            val cy = y + h / 2f
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, value.name, FONT_SET, w * 0.55f), x + 1f, cy, FONT_SET, Palette.desc,
            )
            val label = "${value.get().size} pts"
            val lw = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
            val bw = lw + 7f
            val bx = x + w - bw - 1f
            val border = watchColor("crv:${System.identityHashCode(value)}", if (open) accent else Palette.hairline)
            ctx.drawRoundedRect(bx, cy - 4.5f, bx + bw, cy + 4.5f, R_CTRL, Palette.well, border, 1f)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, label, bx + 3.5f, cy, FONT_CTRL,
                if (open) Palette.white else Palette.desc,
            )
            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    if (!openDropdowns.add(value)) {
                        openDropdowns.remove(value)
                    }
                    commitEdit()
                    true
                } else {
                    false
                }
            }
        }

        var yEnd = y + h
        if (open || t > 0.001f) {
            yEnd = drawCurveBody(ctx, value, x, y + h, w, t)
        }
        return yEnd
    }

    /** The expanding editor body: the plot, then a one-line readout of the touched point. */
    private fun drawCurveBody(
        ctx: GuiGraphicsExtractor?, value: CurveValue,
        x: Float, yTop: Float, w: Float, t: Float,
    ): Float {
        val graphTop = yTop + SET_GAP
        val readTop = graphTop + CURVE_GRAPH_H + 1f
        val fullH = (readTop + CURVE_READ_H) - yTop
        val shownH = fullH * t

        if (ctx == null || shownH <= 1f) {
            return yTop + shownH
        }

        val cx0 = floor(toRealX(x).toDouble())
        val cy0 = floor(toRealY(yTop).toDouble())
        val clip = ScreenRectangle(
            cx0.toInt(), cy0.toInt(),
            (ceil(toRealX(x + w).toDouble()) - cx0).toInt(),
            (ceil(toRealY(yTop + shownH).toDouble()) - cy0).toInt().coerceAtLeast(1),
        )
        ctx.scissorStack.push(clip)
        val prevClip = clipBottomForHits
        clipBottomForHits = minOf(clipBottomForHits, yTop + shownH)
        try {
            val plot = CurvePlot(value, x, graphTop, w, CURVE_GRAPH_H)
            drawCurveGraph(ctx, value, plot, x, graphTop, w, CURVE_GRAPH_H)
            drawCurveReadout(ctx, value, plot, x, readTop, w)
        } finally {
            clipBottomForHits = prevClip
            ctx.scissorStack.pop()
        }
        return yTop + shownH
    }

    /**
     * The plot: grid, the sampled spline with a tinted area under it, and one handle per
     * control point. Left-drag moves a handle, a left click on empty plot inserts a point
     * there and grabs it, right click on a handle removes it - never below the two points
     * a curve needs.
     *
     * Handles stay sorted by X: [CurveValue.transform] hands the list straight to
     * CurveUtil.transformNormalized, which assumes it is sorted and free of duplicate X.
     * So a drag is clamped into the gap between its neighbours instead of swapping past
     * them, and an insert closer than that gap is refused.
     */
    @Suppress("LongParameterList", "CognitiveComplexMethod")
    private fun drawCurveGraph(
        ctx: GuiGraphicsExtractor, value: CurveValue, plot: CurvePlot,
        x: Float, y: Float, w: Float, h: Float,
    ) {
        ctx.drawRoundedRect(x, y, x + w, y + h, R_CTRL, Palette.well, Palette.hairline, 1f)

        for (i in 1..3) {
            val gy = plot.y + plot.h * i / 4f
            ctx.drawQuad(plot.x, gy, plot.x + plot.w, gy + 0.4f, Palette.hairline, null)
            val gx = plot.x + plot.w * i / 4f
            ctx.drawQuad(gx, plot.y, gx + 0.4f, plot.y + plot.h, Palette.hairline, null)
        }

        val points = value.get()
        if (points.isEmpty()) {
            return
        }

        drawCurveSpline(ctx, value, plot)

        // the plot floor takes the click that inserts a point; handles are added after it
        // so the reverse-ordered hit scan lets them win where the two overlap
        addHit(plot.x - 2f, plot.y - 2f, plot.x + plot.w + 2f, plot.y + plot.h + 2f) { button ->
            if (button == 0) {
                val index = curveAddPoint(value, plot, plot.vx(mxNow), plot.vy(myNow))
                if (index >= 0) {
                    curveActivePoint[value] = index
                    pointerDrag = { vmx, vmy -> curveDragPoint(value, index, plot, vmx, vmy) }
                }
                true
            } else {
                false
            }
        }

        val active = curveActivePoint[value] ?: -1
        for (index in points.indices) {
            val point = points[index]
            val hx = plot.px(point.x)
            val hy = plot.py(point.y)
            val hovered = isHover(mxNow, myNow, hx - 4f, hy - 4f, hx + 4f, hy + 4f)
            val ring = if (hovered || index == active) Palette.white else Palette.textOff
            ctx.drawCircle(hx, hy, if (hovered) 3.2f else 2.7f) { ring.argb }
            ctx.drawCircle(hx, hy, 1.5f) { accent.argb }
            addHit(hx - 4f, hy - 4f, hx + 4f, hy + 4f) { button ->
                when (button) {
                    0 -> {
                        curveActivePoint[value] = index
                        pointerDrag = { vmx, vmy -> curveDragPoint(value, index, plot, vmx, vmy) }
                        true
                    }
                    1 -> {
                        curveRemovePoint(value, index)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /** The spline itself: a tinted area down to the plot floor plus the accent stroke. */
    private fun drawCurveSpline(ctx: GuiGraphicsExtractor, value: CurveValue, plot: CurvePlot) {
        val samples = CURVE_SAMPLES
        val ys = FloatArray(samples + 1)
        for (i in 0..samples) {
            ys[i] = plot.py(value.transform(plot.vx(plot.x + plot.w * i / samples)))
        }

        val fillArgb = accent.with(a = 34).argb
        val lineArgb = accent.argb
        val floorY = plot.y + plot.h
        ctx.drawCustomElement(bounds = ctx.getBoundsXYWH(plot.x, plot.y, plot.w, plot.h)) { pose ->
            for (i in 0 until samples) {
                val x0 = plot.x + plot.w * i / samples
                val x1 = plot.x + plot.w * (i + 1) / samples
                addVertexWith2DPose(pose, x0, ys[i]).setColor(fillArgb)
                addVertexWith2DPose(pose, x0, floorY).setColor(fillArgb)
                addVertexWith2DPose(pose, x1, floorY).setColor(fillArgb)
                addVertexWith2DPose(pose, x1, ys[i + 1]).setColor(fillArgb)
            }
            for (i in 0 until samples) {
                val x0 = plot.x + plot.w * i / samples
                val x1 = plot.x + plot.w * (i + 1) / samples
                addVertexWith2DPose(pose, x0, ys[i] - 0.6f).setColor(lineArgb)
                addVertexWith2DPose(pose, x0, ys[i] + 0.6f).setColor(lineArgb)
                addVertexWith2DPose(pose, x1, ys[i + 1] + 0.6f).setColor(lineArgb)
                addVertexWith2DPose(pose, x1, ys[i + 1] - 0.6f).setColor(lineArgb)
            }
        }
    }

    /** Axis label + value of the hovered (else last touched) point, or the usage hint. */
    @Suppress("LongParameterList")
    private fun drawCurveReadout(
        ctx: GuiGraphicsExtractor, value: CurveValue, plot: CurvePlot,
        x: Float, y: Float, w: Float,
    ) {
        val cy = y + CURVE_READ_H / 2f
        val points = value.get()
        val hovered = points.indexOfFirst { point ->
            val hx = plot.px(point.x)
            val hy = plot.py(point.y)
            isHover(mxNow, myNow, hx - 4f, hy - 4f, hx + 4f, hy + 4f)
        }
        val point = points.getOrNull(if (hovered >= 0) hovered else curveActivePoint[value] ?: -1)

        if (point == null) {
            drawTextCentered(
                ctx, WatermarkFont.TEXT, clipText(WatermarkFont.TEXT, CURVE_HINT, FONT_CAP, w - 2f),
                x + 1f, cy, FONT_CAP, Palette.dim,
            )
            return
        }

        drawTextCentered(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, "${value.xAxis.label} ${fmtFloat(point.x)}", FONT_CTRL, w * 0.5f),
            x + 1f, cy, FONT_CTRL, Palette.desc,
        )
        val right = clipText(WatermarkFont.TEXT, "${value.yAxis.label} ${fmtFloat(point.y)}", FONT_CTRL, w * 0.5f)
        val rw = textWidth(WatermarkFont.TEXT, right, FONT_CTRL)
        drawTextCentered(ctx, WatermarkFont.TEXT, right, x + w - rw - 1f, cy, FONT_CTRL, Palette.white)
    }

    /** Moves point [index] under the cursor, kept inside the axes and between its neighbours. */
    private fun curveDragPoint(value: CurveValue, index: Int, plot: CurvePlot, vmx: Float, vmy: Float) {
        val points = value.get()
        val point = points.getOrNull(index) ?: return
        val gap = plot.gap
        val lo = points.getOrNull(index - 1)?.let { it.x + gap } ?: plot.xMin
        val hi = points.getOrNull(index + 1)?.let { it.x - gap } ?: plot.xMax
        val wanted = if (lo >= hi) (lo + hi) * 0.5f else plot.vx(vmx).coerceIn(lo, hi)
        val nx = curveQuantize(wanted, plot.xSpan).coerceIn(plot.xMin, plot.xMax)
        val ny = curveQuantize(plot.vy(vmy), plot.ySpan).coerceIn(plot.yMin, plot.yMax)
        if (nx != point.x || ny != point.y) {
            point.set(nx, ny)
            curveChanged(value)
        }
    }

    /** Inserts a point at the clicked spot, returning its index (or -1 if it did not fit). */
    private fun curveAddPoint(value: CurveValue, plot: CurvePlot, vx: Float, vy: Float): Int {
        val points = value.get()
        if (points.size >= CURVE_MAX_POINTS || points.any { abs(it.x - vx) < plot.gap }) {
            return -1
        }
        val at = points.indexOfFirst { it.x > vx }.let { if (it < 0) points.size else it }
        points.add(
            at,
            Vector2f(
                curveQuantize(vx, plot.xSpan).coerceIn(plot.xMin, plot.xMax),
                curveQuantize(vy, plot.ySpan).coerceIn(plot.yMin, plot.yMax),
            ),
        )
        curveChanged(value)
        return at
    }

    /** Drops point [index]; a curve never falls below the two points the spline needs. */
    private fun curveRemovePoint(value: CurveValue, index: Int) {
        val points = value.get()
        if (points.size <= 2 || index !in points.indices) {
            return
        }
        points.removeAt(index)
        curveActivePoint.remove(value)
        curveChanged(value)
    }

    /** Drag steps land on a tenth of a unit on wide axes, a hundredth on narrow ones. */
    private fun curveQuantize(v: Float, span: Float): Float =
        if (span >= 10f) (v * 10f).roundToInt() / 10f else (v * 100f).roundToInt() / 100f

    /**
     * Fires the listeners for an IN-PLACE edit of the point list. [Value.set] short-circuits
     * when the new value equals the old one, and the list just mutated IS the old one - so
     * the apply-consumer overload (which skips that check and leaves the backing field alone)
     * is what publishes the change, exactly as ListValue.deserializeFrom does.
     */
    private fun curveChanged(value: CurveValue) {
        value.set(value.get()) { }
    }

    /**
     * Vector setting: the name on its own line, then the two or three axis fields spread across
     * the full row width. A world coordinate is up to six digits plus a sign, which never fitted
     * beside the name, so the fields take the whole row and the number steps down through the
     * baked control sizes before anything is clipped.
     */
    @Suppress("LongMethod", "CognitiveComplexMethod")
    private fun drawVecRow(
        ctx: GuiGraphicsExtractor?, value: Value<*>,
        x: Float, y: Float, w: Float,
    ): Float {
        val labelH = 8f
        val fieldH = 9f
        val h = labelH + 1f + fieldH
        if (ctx != null && rowVisible(y, h)) {
            val comps: List<Float> = when (val raw = value.get()) {
                is Vec3i -> listOf(raw.x.toFloat(), raw.y.toFloat(), raw.z.toFloat())
                is Vec3 -> listOf(raw.x.toFloat(), raw.y.toFloat(), raw.z.toFloat())
                is Vector2fc -> listOf(raw.x(), raw.y())
                else -> emptyList()
            }
            drawTextCentered(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, value.name, FONT_SET, w - 2f),
                x + 1f, y + labelH / 2f, FONT_SET, Palette.desc,
            )

            val gap = 2f
            val count = comps.size.coerceAtLeast(1)
            val fieldW = (w - 2f - gap * (count - 1)) / count
            val textW = fieldW - 3f
            val fieldTop = y + labelH + 1f
            val cy = fieldTop + fieldH / 2f
            val baseKey = "vec:${System.identityHashCode(value)}"
            val displays = comps.mapIndexed { axis, comp ->
                if (focusedFieldKey == "$baseKey:$axis") editBuffer + CARET else fmtFloat(comp)
            }
            // Sized off the widest component, so the three fields never disagree on font size.
            val px = displays.minOfOrNull { vecFieldFont(it, textW) } ?: FONT_CTRL

            var fx = x + 1f
            for ((axis, comp) in comps.withIndex()) {
                val fieldKey = "$baseKey:$axis"
                val editing = focusedFieldKey == fieldKey
                ctx.drawRoundedRect(
                    fx, fieldTop, fx + fieldW, fieldTop + fieldH, R_CTRL, Palette.well,
                    if (editing) accent else Palette.hairline, 1f,
                )
                val text = clipText(WatermarkFont.TEXT, displays[axis], px, textW)
                val tw = textWidth(WatermarkFont.TEXT, text, px)
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, text, fx + (fieldW - tw) / 2f, cy, px,
                    if (editing) Palette.white else Palette.textOff,
                )
                val axisNow = axis
                val fxHit = fx
                addHit(fxHit, fieldTop, fxHit + fieldW, fieldTop + fieldH) { button ->
                    if (button == 0) {
                        beginKeyedEdit(fieldKey, fmtFloat(comp)) { input ->
                            input.toFloatOrNull()?.let { applyVecComponent(value, axisNow, it) }
                        }
                        true
                    } else {
                        false
                    }
                }
                fx += fieldW + gap
            }
        }
        return y + h
    }

    /**
     * Largest of [VEC_FONT_STEPS] at which [text] still fits [maxW] whole. Clipping a coordinate
     * to "-12.." destroys the value it is meant to show, so a smaller number is the lesser evil.
     */
    private fun vecFieldFont(text: String, maxW: Float): Float =
        VEC_FONT_STEPS.firstOrNull { textWidth(WatermarkFont.TEXT, text, it) <= maxW }
            ?: VEC_FONT_STEPS.last()

    @Suppress("UNCHECKED_CAST", "CognitiveComplexMethod")
    private fun applyVecComponent(value: Value<*>, axis: Int, v: Float) {
        when (val raw = value.get()) {
            is Vec3i -> {
                val n = v.roundToInt()
                val next = Vec3i(
                    if (axis == 0) n else raw.x,
                    if (axis == 1) n else raw.y,
                    if (axis == 2) n else raw.z,
                )
                (value as Value<Vec3i>).set(next)
            }
            is Vec3 -> {
                val next = Vec3(
                    if (axis == 0) v.toDouble() else raw.x,
                    if (axis == 1) v.toDouble() else raw.y,
                    if (axis == 2) v.toDouble() else raw.z,
                )
                (value as Value<Vec3>).set(next)
            }
            is Vector2fc -> {
                val next = Vector2f(
                    if (axis == 0) v else raw.x(),
                    if (axis == 1) v else raw.y(),
                )
                (value as Value<Vector2fc>).set(next)
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ *
    // Text editing machinery
    // ------------------------------------------------------------------ *

    private fun beginEdit(value: Value<*>, fieldKey: String, initial: String) {
        commitEdit()
        focusedField = value
        focusedFieldKey = fieldKey
        editBuffer = initial
        keyedCommit = null
    }

    private fun beginKeyedEdit(key: String, initial: String, onCommit: (String) -> Unit) {
        commitEdit()
        focusedField = null
        focusedFieldKey = key
        editBuffer = initial
        keyedCommit = onCommit
    }

    @Suppress("UNCHECKED_CAST")
    private fun commitEdit() {
        val key = focusedFieldKey ?: return
        val value = focusedField
        val keyed = keyedCommit
        val buffer = editBuffer
        focusedField = null
        focusedFieldKey = null
        editBuffer = ""
        keyedCommit = null

        if (keyed != null) {
            keyed(buffer)
            return
        }
        if (value != null) {
            when (val cur = value.get()) {
                is String -> if (buffer != cur) (value as Value<String>).set(buffer)
                is Regex -> runCatching { Regex(buffer) }.getOrNull()?.let {
                    if (it.pattern != cur.pattern) (value as Value<Regex>).set(it)
                }
                // Lists come back as comma-joined text; entries are trimmed and an empty
                // field clears the list, which the string parser cannot express on its own.
                is MutableCollection<*> -> {
                    val items = buffer.split(',').map(String::trim).filter(String::isNotEmpty)
                    if (items.isEmpty()) cur.clear() else value.setByString(items.joinToString(","))
                }
                else -> Unit
            }
        }
    }

    private fun cancelEdit() {
        focusedField = null
        focusedFieldKey = null
        editBuffer = ""
        keyedCommit = null
    }

    // ------------------------------------------------------------------ *
    // Themes panel
    // ------------------------------------------------------------------ *

    /** PINNED "Saved | Edit" strip of the Themes panel. */
    private fun drawThemesTabs(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float) {
        val gap = 2f
        val tabW = (w - gap) / 2f
        for ((i, label) in listOf("Saved", "Edit").withIndex()) {
            val tx = x + i * (tabW + gap)
            val active = themesTab == i
            val fill = watchColor("thtab:$i", if (active) accent else Palette.well)
            val fg = if (active) Palette.ink else Palette.desc
            ctx.drawRoundedRect(tx, y, tx + tabW, y + TABS_H, R_CTRL, fill, null)
            val lw = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
            drawTextCentered(ctx, WatermarkFont.TEXT, label, tx + (tabW - lw) / 2f, y + TABS_H / 2f, FONT_CTRL, fg)
            addHit(tx, y, tx + tabW, y + TABS_H) { button ->
                if (button == 0) {
                    themesTab = i
                    scrollTargetMap[THEMES_KEY] = 0f
                    true
                } else {
                    false
                }
            }
        }
    }

    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun layoutThemesBody(
        ctx: GuiGraphicsExtractor?, x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float {
        var y = yStart + 1f

        if (themesTab == 1) {
            if (ctx != null) {
                val hint = "Nothing to edit yet"
                val hw = textWidth(WatermarkFont.TEXT, hint, FONT_CTRL)
                drawTextCentered(ctx, WatermarkFont.TEXT, hint, x + (w - hw) / 2f, y + 12f, FONT_CTRL, Palette.dim)
            }
            return y + 24f - yStart
        }


        val selectedKey = HellhitClickGuiSettings.themeKey
        for (theme in HellhitClickGuiSettings.THEMES) {
            val h = ROW_H
            if (ctx != null && rowVisible(y, h)) {
                val key = "theme:${theme.key}"
                val hovered = isHover(mx, my, x, y, x + w, y + h)
                if (hovered) hoveredKeys += key
                val ht = easeInOutCubic(hoverProgress[key] ?: 0f)
                val selected = theme.key == selectedKey
                ctx.drawRoundedRect(
                    x, y, x + w, y + h, R_ROW,
                    lerpColor(Palette.row, Palette.rowHover, if (selected) 1f else ht), null,
                )
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, theme.label, x + 6f, y + h / 2f, FONT_ROW,
                    if (selected) Palette.white else Palette.textOff,
                )
                // colored dot: a ring normally, a solid disc when selected
                val dcx = x + w - 8f
                val dcy = y + h / 2f
                if (selected) {
                    ctx.drawCircle(dcx, dcy, 2.6f) { theme.accent.argb }
                } else {
                    ctx.drawCircle(dcx, dcy, 2.6f) { theme.accent.argb }
                    ctx.drawCircle(dcx, dcy, 1.4f) { Palette.row.argb }
                }
                addHit(x, y, x + w, y + h) { button ->
                    if (button == 0) {
                        HellhitClickGuiSettings.themeKey = theme.key
                        true
                    } else {
                        false
                    }
                }
            }
            y += h + ROW_GAP
        }
        return y - yStart
    }


    // ------------------------------------------------------------------ *
    // Registry / items picker page
    //
    // The reference's "Select Blocks" screen: accent title + back, a live search field,
    // an All | Selected tab pair, and icon rows that toggle membership on click. Sits as
    // a third content level above the module page.
    // ------------------------------------------------------------------ *

    private fun openPickerPage(panelKey: String, value: Value<*>) {
        openPicker[panelKey] = value
        pickerSearch[panelKey] = ""
        pickerTab[panelKey] = 0
        searchFocusPanel = panelKey
        scrollTargetMap[panelKey] = 0f
        scrollYMap[panelKey] = 0f
        commitEdit()
    }

    private fun closePickerPage(panelKey: String) {
        openPicker.remove(panelKey)
        pickerSearch.remove(panelKey)
        if (searchFocusPanel == panelKey) searchFocusPanel = null
        scrollTargetMap[panelKey] = 0f
        scrollYMap[panelKey] = 0f
    }

    /**
     * Full selectable entry set of a picker value, enumerated once per value and cached.
     * Icon stacks exist only for item-backed registries; other entries render text-only.
     */
    private fun pickEntriesOf(value: Value<*>): List<PickEntry> = pickerEntries.getOrPut(value) {
        fun <T : Any> Registry<T>.regEntries(stackOf: ((T) -> ItemStack?)? = null): List<PickEntry> =
            mapNotNull { e ->
                getKey(e)?.let { id ->
                    PickEntry(e, id.path, stackOf?.invoke(e)?.takeUnless { it.isEmpty })
                }
            }

        when (value) {
            is ItemsChooseValue -> BuiltInRegistries.ITEM.regEntries { ItemStack(it) }
            is RegistryListValue<*, *> -> when (value.registry) {
                "block" -> BuiltInRegistries.BLOCK.regEntries { block ->
                    val item = block.asItem()
                    if (item === Items.AIR) null else ItemStack(item)
                }
                "item" -> BuiltInRegistries.ITEM.regEntries { ItemStack(it) }
                "sound_event" -> BuiltInRegistries.SOUND_EVENT.regEntries()
                "mob_effect" -> BuiltInRegistries.MOB_EFFECT.regEntries()
                "entity_type" -> BuiltInRegistries.ENTITY_TYPE.regEntries()
                "menu" -> BuiltInRegistries.MENU.regEntries()
                "c2s_packet" -> packetRegistry[PacketFlow.SERVERBOUND].orEmpty().map { PickEntry(it, it.path, null) }
                "s2c_packet" -> packetRegistry[PacketFlow.CLIENTBOUND].orEmpty().map { PickEntry(it, it.path, null) }
                "client_module" -> ModuleManager.map { PickEntry(it.name, it.name, null) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Re-fires listeners + autosave after an IN-PLACE mutation of a list value's
     * collection: plain set() would see identity equality and skip.
     */
    private fun <T : MutableCollection<E>, E> refireListValue(value: ListValue<T, E>) {
        value.set(value.get()) { }
    }

    private fun isPicked(value: Value<*>, element: Any): Boolean =
        (value.get() as? Collection<*>)?.contains(element) == true

    @Suppress("UNCHECKED_CAST")
    private fun togglePicked(value: Value<*>, element: Any) {
        when (value) {
            is RegistryListValue<*, *> -> {
                val selected = value.get() as MutableCollection<Any>
                if (!selected.remove(element)) selected.add(element)
                refireListValue(value as ListValue<MutableCollection<Any>, Any>)
            }
            is ItemsChooseValue -> {
                val list = value.get()
                val item = element as? Item ?: return
                if (!list.remove(item)) list.add(item)
                refireListValue(value)
            }
            else -> Unit
        }
    }

    /** PINNED picker chrome: accent title + back, search field, All | Selected tabs. */
    @Suppress("CognitiveComplexMethod", "LongMethod", "LongParameterList")
    private fun drawPickerChrome(
        ctx: GuiGraphicsExtractor, panelKey: String, value: Value<*>,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ) {
        var y = yStart

        // - header: accent title + back glyph -
        run {
            val cy = y + PAGE_HEAD_H / 2f
            val title = clipText(WatermarkFont.BRAND, "Select ${value.name}", FONT_TITLE, w - 14f)
            drawTextCentered(ctx, WatermarkFont.BRAND, title, x + 1f, cy, FONT_TITLE, accent)

            val backKey = "pickback:$panelKey"
            val bx = x + w - 8f
            if (isHover(mx, my, bx - 5f, y, x + w, y + PAGE_HEAD_H)) hoveredKeys += backKey
            val bt = easeInOutCubic(hoverProgress[backKey] ?: 0f)
            drawTextCentered(
                ctx, WatermarkFont.BRAND, "<", bx - 2f, cy, FONT_TITLE,
                lerpColor(Palette.dim, Palette.white, bt),
            )
            addHit(bx - 6f, y, x + w, y + PAGE_HEAD_H) { button ->
                if (button == 0) {
                    requestSwitch(panelKey) { closePickerPage(panelKey) }
                    true
                } else {
                    false
                }
            }
            ctx.drawQuad(x, y + PAGE_HEAD_H + 1f, x + w, y + PAGE_HEAD_H + 1.6f, Palette.hairline, null)
        }
        y += PAGE_HEAD_H + 4f

        // - search field -
        val searchH = SET_ROW_H
        run {
            val focused = searchFocusPanel == panelKey
            val query = pickerSearch[panelKey] ?: ""
            ctx.drawRoundedRect(
                x, y, x + w, y + searchH, R_CTRL, Palette.well,
                if (focused) accent else Palette.hairline, 1f,
            )
            val cy = y + searchH / 2f
            if (query.isEmpty() && !focused) {
                drawTextCentered(ctx, WatermarkFont.TEXT, "Search", x + 4f, cy, FONT_SET, Palette.dim)
            } else {
                val text = clipText(WatermarkFont.TEXT, query, FONT_SET, w - 10f)
                drawTextCentered(ctx, WatermarkFont.TEXT, text, x + 4f, cy, FONT_SET, Palette.white)
                if (focused) {
                    drawCaret(ctx, x + 4f + textWidth(WatermarkFont.TEXT, text, FONT_SET), cy, Palette.white)
                }
            }
            addHit(x, y, x + w, y + searchH) { button ->
                if (button == 0) {
                    commitEdit()
                    searchFocusPanel = panelKey
                    true
                } else {
                    false
                }
            }
        }
        y += searchH + 3f

        // - All | Selected tabs -
        val tab = pickerTab[panelKey] ?: 0
        val gap = 2f
        val tabW = (w - gap) / 2f
        run {
            for ((i, label) in listOf("All", "Selected").withIndex()) {
                val tx = x + i * (tabW + gap)
                val active = tab == i
                val fill = watchColor("pktab:$panelKey:$i", if (active) accent else Palette.well)
                val fg = if (active) Palette.ink else Palette.desc
                ctx.drawRoundedRect(tx, y, tx + tabW, y + TABS_H, R_CTRL, fill, null)
                val lw = textWidth(WatermarkFont.TEXT, label, FONT_CTRL)
                drawTextCentered(ctx, WatermarkFont.TEXT, label, tx + (tabW - lw) / 2f, y + TABS_H / 2f, FONT_CTRL, fg)
                addHit(tx, y, tx + tabW, y + TABS_H) { button ->
                    if (button == 0) {
                        pickerTab[panelKey] = i
                        scrollTargetMap[panelKey] = 0f
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    /** Scrolling body of the picker: the filtered entry rows alone. */
    @Suppress("CognitiveComplexMethod", "LongMethod", "LongParameterList")
    private fun layoutPickerRows(
        ctx: GuiGraphicsExtractor?, panelKey: String, value: Value<*>,
        x: Float, yStart: Float, w: Float, mx: Float, my: Float,
    ): Float {
        var y = yStart + 1f
        val tab = pickerTab[panelKey] ?: 0
        val query = (pickerSearch[panelKey] ?: "").trim()
        var entries = pickEntriesOf(value)
        if (tab == 1) entries = entries.filter { isPicked(value, it.element) }
        if (query.isNotEmpty()) entries = entries.filter { it.name.contains(query, ignoreCase = true) }

        val rowH = ROW_H
        if (ctx == null) {
            if (entries.isEmpty()) return y + 14f - yStart
            return y + entries.size * (rowH + 1.5f) - yStart
        }

        if (entries.isEmpty()) {
            val hint = if (tab == 1) "Nothing selected" else "No matches"
            val hw = textWidth(WatermarkFont.TEXT, hint, FONT_CTRL)
            drawTextCentered(ctx, WatermarkFont.TEXT, hint, x + (w - hw) / 2f, y + 7f, FONT_CTRL, Palette.dim)
            return y + 14f - yStart
        }

        for (entry in entries) {
            // viewport culling: hundreds of registry rows, only the visible ones draw
            if (y + rowH >= clipTopForHits - 4f && y <= clipBottomForHits + 4f) {
                val picked = isPicked(value, entry.element)
                val rKey = "pk:$panelKey:${entry.name}"
                val hovered = isHover(mx, my, x, y, x + w, y + rowH)
                if (hovered) hoveredKeys += rKey
                val ht = easeInOutCubic(hoverProgress[rKey] ?: 0f)
                if (ht > 0.01f) {
                    ctx.drawRoundedRect(
                        x, y, x + w, y + rowH, R_CTRL,
                        Palette.row.with(a = (255 * ht * 0.7f).toInt()), null,
                    )
                }

                var textX = x + 4f
                if (entry.stack != null) {
                    drawItemStack(ctx, entry.stack, x + 3f, y + (rowH - 8f) / 2f, 8f)
                    textX = x + 14f
                }
                val label = clipText(WatermarkFont.TEXT, entry.name, FONT_ROW, x + w - textX - 9f)
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, label, textX, y + rowH / 2f, FONT_ROW,
                    if (picked) accent else Palette.textOff,
                )
                if (picked) {
                    ctx.drawCircle(x + w - 5f, y + rowH / 2f, 1.6f) { accent.argb }
                }

                val el = entry.element
                addHit(x, y, x + w, y + rowH) { button ->
                    if (button == 0) {
                        togglePicked(value, el)
                        true
                    } else {
                        false
                    }
                }
            }
            y += rowH + 1.5f
        }
        return y - yStart
    }

    /** Draws [stack] as a [size]-unit icon through the vanilla item renderer. */
    private fun drawItemStack(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, size: Float) {
        val scale = size / 16f
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)
        ctx.item(stack, 0, 0)
        ctx.pose().popMatrix()
    }

    // ------------------------------------------------------------------ *
    // Input
    // ------------------------------------------------------------------ *

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (isClosing) return true
        if (activeSlider != null || panelDrag != null || pointerDrag != null) return true

        pendingBind?.let { bind ->
            bind.set(bind.get().copy(boundKey = InputConstants.Type.MOUSE.getOrCreate(click.button())))
            pendingBind = null
            return true
        }
        if (pendingKey != null) return true

        val mx = toVirtualX(click.x.toFloat())
        val my = toVirtualY(click.y.toFloat())
        val button = click.button()
        mxNow = mx
        myNow = my

        commitEdit()
        searchFocusPanel = null

        for (hit in hits.asReversed()) {
            if (!hit.contains(mx, my)) continue
            if (hit.onClick(button)) {
                activeSlider?.let { it.update(((mx - it.trackX) / it.trackW).coerceIn(0f, 1f)) }
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() == 0) {
            activeSlider = null
            pointerDrag = null
            panelDrag = null
            HellhitClickGuiSettings.flushDirty()
        }
        return super.mouseReleased(click)
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (click.button() == 0) {
            pointerDrag?.let { drag ->
                drag(toVirtualX(click.x.toFloat()), toVirtualY(click.y.toFloat()))
                return true
            }
            activeSlider?.let { slider ->
                val vx = toVirtualX(click.x.toFloat())
                slider.update(((vx - slider.trackX) / slider.trackW).coerceIn(0f, 1f))
                return true
            }
            panelDrag?.let { drag ->
                val pos = panelPos[drag.key] ?: return true
                pos[0] = (toVirtualX(click.x.toFloat()) - drag.offX).coerceIn(2f, vw - PANEL_W - 2f)
                pos[1] = (toVirtualY(click.y.toFloat()) - drag.offY).coerceIn(2f, vh - HEAD_H - 4f)
                HellhitClickGuiSettings.setPanelPosition(drag.key, pos[0], pos[1])
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
        val vmx = toVirtualX(mouseX.toFloat())
        val vmy = toVirtualY(mouseY.toFloat())
        for (key in panelOrder.asReversed()) {
            val frame = panelFrames[key] ?: continue
            if (vmx >= frame.x && vmx <= frame.x + frame.w && vmy >= frame.y && vmy <= frame.y + frame.h) {
                val target = (scrollTargetMap[key] ?: 0f) - verticalAmount.toFloat() * 20f
                scrollTargetMap[key] = target.coerceIn(0f, frame.maxScroll)
                return true
            }
        }
        return true
    }

    /**
     * Scrolls the panel under the cursor by [delta]; if none is hovered, the first
     * scrollable panel. Used by the arrow / page keys (keyboard scrolling).
     */
    private fun scrollHovered(delta: Float) {
        fun apply(key: String, frame: PanelFrame) {
            scrollTargetMap[key] = ((scrollTargetMap[key] ?: 0f) + delta).coerceIn(0f, frame.maxScroll)
        }
        for (key in panelOrder.asReversed()) {
            val frame = panelFrames[key] ?: continue
            if (mxNow >= frame.x && mxNow <= frame.x + frame.w && myNow >= frame.y && myNow <= frame.y + frame.h) {
                apply(key, frame)
                return
            }
        }
        for (key in panelOrder.asReversed()) {
            val frame = panelFrames[key] ?: continue
            if (frame.maxScroll > 0f) {
                apply(key, frame)
                return
            }
        }
    }

    @Suppress("ReturnCount", "CognitiveComplexMethod", "LongMethod")
    override fun keyPressed(input: KeyEvent): Boolean {
        if (isClosing) return true
        val key = input.key

        pendingBind?.let { bind ->
            val old = bind.get()
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> Unit
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

        // arrow / page keys scroll the panel under the cursor, like the mouse wheel
        when (key) {
            GLFW.GLFW_KEY_UP -> { scrollHovered(-28f); return true }
            GLFW.GLFW_KEY_DOWN -> { scrollHovered(28f); return true }
            GLFW.GLFW_KEY_PAGE_UP -> { scrollHovered(-210f); return true }
            GLFW.GLFW_KEY_PAGE_DOWN -> { scrollHovered(210f); return true }
        }

        if (focusedFieldKey != null) {
            when (key) {
                GLFW.GLFW_KEY_BACKSPACE ->
                    editBuffer = if (isCtrlDown()) deleteWordBackward(editBuffer) else editBuffer.dropLast(1)
                GLFW.GLFW_KEY_ENTER -> commitEdit()
                GLFW.GLFW_KEY_ESCAPE -> cancelEdit()
            }
            return true
        }

        searchFocusPanel?.let { panel ->
            when (key) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    val cur = pickerSearch[panel] ?: ""
                    pickerSearch[panel] = if (isCtrlDown()) deleteWordBackward(cur) else cur.dropLast(1)
                }
                GLFW.GLFW_KEY_ESCAPE -> searchFocusPanel = null
                GLFW.GLFW_KEY_ENTER -> searchFocusPanel = null
            }
            return true
        }

        // Ctrl+F: open (or re-focus) the global module search
        if (key == GLFW.GLFW_KEY_F && isCtrlDown()) {
            searchActive = true
            searchFocusPanel = null
            commitEdit()
            return true
        }

        if (searchActive) {
            when (key) {
                GLFW.GLFW_KEY_BACKSPACE ->
                    setSearchQuery(if (isCtrlDown()) deleteWordBackward(searchQuery) else searchQuery.dropLast(1))
                GLFW.GLFW_KEY_ESCAPE -> {
                    setSearchQuery("")
                    searchActive = false
                }
                GLFW.GLFW_KEY_ENTER -> Unit
            }
            return true
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            startClose()
            return true
        }

        return super.keyPressed(input)
    }

    @Suppress("ReturnCount")
    override fun charTyped(input: CharacterEvent): Boolean {
        if (isClosing) return true
        val codepoint = input.codepoint()
        if (codepoint > 0xFFFF) return false
        val ch = codepoint.toChar()
        if (ch.isISOControl()) return false

        if (focusedFieldKey != null) {
            editBuffer += ch
            return true
        }
        searchFocusPanel?.let { panel ->
            pickerSearch[panel] = (pickerSearch[panel] ?: "") + ch
            return true
        }
        if (searchActive) {
            setSearchQuery(searchQuery + ch)
            return true
        }
        return super.charTyped(input)
    }

    override fun isPauseScreen(): Boolean = false

    // ------------------------------------------------------------------ *
    // Misc
    // ------------------------------------------------------------------ *

    /** Classic caret blink: ~530 ms on, ~530 ms off. */
    private fun caretOn(): Boolean = (System.currentTimeMillis() / 530L) % 2L == 0L

    /** Blinking caret quad after a LEFT-aligned text run ending at [textEnd]. */
    private fun drawCaret(ctx: GuiGraphicsExtractor, textEnd: Float, cy: Float, color: Color4b) {
        if (!caretOn()) return
        ctx.drawQuad(textEnd + 0.7f, cy - 3.4f, textEnd + 1.5f, cy + 3.4f, color, null)
    }

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

    private val keyNameCache = HashMap<InputConstants.Key, String>()

    private fun keyDisplayName(key: InputConstants.Key): String = keyNameCache.getOrPut(key) {
        if (key == InputConstants.UNKNOWN) {
            "None"
        } else {
            key.name.split('.').drop(2).joinToString("_").uppercase().ifEmpty { key.name }
        }
    }
}

/** Caret glyph appended to the edit buffer while a field is focused. */
private const val CARET = "_"
