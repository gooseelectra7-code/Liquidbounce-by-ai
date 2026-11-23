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
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import com.google.common.base.Suppliers
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAimbot
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoSwap
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.misc.nameprotect.ModuleNameProtect
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleItemBind
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.ItemImageAtlas
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.combat.intel.TargetIntel
import net.ccbluex.liquidbounce.utils.combat.intel.TargetIntel.nametagHealth
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.StringDecomposer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.image.BufferedImage
import org.joml.Matrix3x2f
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hud2 module
 *
 * A native, self-rendered HUD: a watermark and a target HUD sharing one visual language —
 * dark glass panels with real background blur, soft feathered shadows, antialiased rounded
 * contours, a breathing lavender accent and the bundled Inter typeface rendered through the
 * module's own glyph-atlas renderer ([WatermarkFont]).
 *
 * All 2D rendering is fully self-contained: panel geometry (contour-tessellated rounded
 * rectangles with AA feathering), textured quads and text are built as own GUI render states
 * ([WatermarkGuiElement]) on top of the vanilla GUI render pipeline, without using the
 * client's shared render utilities. The region-limited background blur is provided by
 * [WatermarkGlass].
 */
@Suppress("LargeClass")
object ModuleHud2 : Mode("Glass", aliases = listOf("Hud2")) {

    override val parent: ModeValueGroup<*>
        get() = ModuleHud.theme

    /** What `ClientModule.logger` used to give this, back when it was the `Hud2` module. */
    private val logger = clientLogger("Hud/Glass")

    private const val CORNER_SEGMENTS = 10
    private const val SHADOW_RINGS = 5
    private const val SHADOW_SPREAD = 5f
    private const val APPEAR_MS = 350f

    // --- Shared style ------------------------------------------------------------------------

    private val fontSize by float("FontSize", 12f, 6f..20f)

    private val style by enumChoice("Style", Style.CLASSIC)

    private val radiusSetting by float("Radius", 4.5f, 0f..12f)

    private val backgroundTopSetting by color("BackgroundTop", Color4b(18, 20, 28, 150))
    private val backgroundBottomSetting by color("BackgroundBottom", Color4b(10, 11, 16, 175))
    private val accentFromSetting by color("AccentFrom", Color4b(216, 221, 244, 255))
    private val accentToSetting by color("AccentTo", Color4b(134, 144, 214, 255))
    private val gradientSpeed by float("GradientSpeed", 0.4f, 0f..5f)
    private val infoColorSetting by color("InfoColor", Color4b(152, 158, 182, 255))

    private val language by enumChoice("Language", HudLanguage.ENGLISH)

    private val blur by boolean("Blur", true)
    private val blurStrength by int("BlurStrength", 7, 1..10)
    private val shadowOpacity by float("ShadowOpacity", 0.35f, 0f..1f)

    /**
     * Visual style presets.
     *
     * [LEET] — Leet design language: near-opaque dark cards (#131316), static lavender
     * accent (#a8a3f5), dim gray secondary text (#8f8f97), larger corner radius.
     * Overrides the palette at read time; the user's Classic color settings are preserved.
     */
    @Suppress("unused")
    enum class Style(override val tag: String) : Tagged {
        CLASSIC("Classic"),
        LEET("Leet"),
    }

    private val leetBackgroundTop = Color4b(19, 19, 22, 216)
    private val leetBackgroundBottom = Color4b(16, 16, 19, 228)
    private val leetAccent = Color4b(168, 163, 245, 255)
    private val leetInfo = Color4b(143, 143, 151, 255)

    private val isLeet: Boolean get() = style == Style.LEET

    // Style-resolved palette: every render site reads these instead of the raw settings.
    private val radius: Float get() = if (isLeet) 8f else radiusSetting
    private val backgroundTop: Color4b get() = if (isLeet) leetBackgroundTop else backgroundTopSetting
    private val backgroundBottom: Color4b get() = if (isLeet) leetBackgroundBottom else backgroundBottomSetting
    private val accentFrom: Color4b get() = if (isLeet) leetAccent else accentFromSetting
    private val accentTo: Color4b get() = if (isLeet) leetAccent else accentToSetting
    private val infoColor: Color4b get() = if (isLeet) leetInfo else infoColorSetting

    @Suppress("unused")
    enum class HudLanguage(override val tag: String) : Tagged {
        ENGLISH("English"),
        RUSSIAN("Russian"),
    }

    /** Localized labels for the HUD's own strings (effect names follow the game language). */
    private val ru: Boolean get() = language == HudLanguage.RUSSIAN
    private val txtFps: String get() = if (ru) "фпс" else "fps"
    private val txtMs: String get() = if (ru) "мс" else "ms"
    private val txtHp: String get() = if (ru) "хп" else "hp"
    private val txtMeters: String get() = if (ru) "м" else "m"
    private val txtNoEffects: String get() = if (ru) "Нет эффектов" else "No effects"
    private val txtInfinite: String get() = if (ru) "беск." else "inf"
    private val txtEnabled: String get() = if (ru) "включён" else "enabled"
    private val txtDisabled: String get() = if (ru) "выключен" else "disabled"
    private val txtNotifications: String get() = if (ru) "Уведомления" else "Notifications"
    private val txtDragMe: String get() = if (ru) "перетащи меня" else "drag me"
    private val txtLmb: String get() = if (ru) "ЛКМ" else "LMB"
    private val txtRmb: String get() = if (ru) "ПКМ" else "RMB"
    private val txtBps: String get() = if (ru) "БВС" else "BPS"
    private val txtWin: String get() = if (ru) "шанс" else "win"
    private val txtPops: String get() = if (ru) "тотемы" else "pops"
    private val txtGapples: String get() = if (ru) "яблоки" else "gapples"
    private val txtArmor: String get() = if (ru) "броня" else "armor"
    private val txtTeaming: String get() = if (ru) "ТИМЫ" else "TEAM"
    private val txtEating: String get() = if (ru) "ест" else "eating"
    private val txtShield: String get() = if (ru) "щит" else "shield"
    private val txtCombo: String get() = if (ru) "комбо" else "combo"
    private val txtHits: String get() = if (ru) "удары" else "hits"
    private val txtBetter: String get() = if (ru) "лучше" else "better"
    private val txtWorse: String get() = if (ru) "хуже" else "worse"
    private val txtEven: String get() = if (ru) "равно" else "even"

    init {
        tree(Watermark)
        tree(TargetHud)
        tree(EffectsHud)
        tree(NotificationsHud)
        tree(ArrayListHud)
        tree(KeystrokesHud)
        tree(InfoHud)
        tree(ScoreboardHud)
        tree(ArmorHud)
        tree(HotbarHud)
        tree(CountersHud)
        tree(PvpIntelHud)
        tree(ItemBindsHud)
        tree(AutoSwapHud)
    }

    // --- Shared combat target ------------------------------------------------------------------
    //
    // Lives at module scope, not inside TargetHud: PvpIntel needs the same target even when the
    // target panel itself is switched off.

    private const val TARGET_TIMEOUT_MS = 4000L
    private const val TARGET_MAX_DISTANCE = 64f
    private var lastAttacked: LivingEntity? = null
    private var lastAttackAt = 0L

    @Suppress("unused")
    private val sharedAttackHandler = handler<AttackEntityEvent> { event ->
        val entity = event.entity
        if (entity is LivingEntity && entity !== mc.player) {
            lastAttacked = entity
            lastAttackAt = System.currentTimeMillis()
        }
    }

    /**
     * Whoever we are fighting: a combat module's locked target first (KillAura, then Aimbot, then
     * an entity-only AutoClicker objective), otherwise whoever we hit in the last few seconds.
     */
    internal fun resolveCombatTarget(): LivingEntity? {
        val self = mc.player ?: return null

        val moduleTarget = when {
            ModuleKillAura.running -> ModuleKillAura.targetTracker.target
            else -> null
        } ?: when {
            ModuleAimbot.running -> ModuleAimbot.targetTracker.target
            else -> null
        } ?: ModuleAutoClicker.AttackButton.crosshairObjectiveEntity()?.takeIf { it !== self }

        val tracked = lastAttacked?.takeIf {
            System.currentTimeMillis() - lastAttackAt <= TARGET_TIMEOUT_MS &&
                it.isAlive && !it.isRemoved && self.distanceTo(it) <= TARGET_MAX_DISTANCE
        }
        return moduleTarget ?: tracked
    }

    /** Used by the Gui mixins: vanilla hotbar pieces are hidden while ours render. */
    @JvmStatic
    fun shouldHideVanillaHearts(): Boolean = running && HotbarHud.enabled && HotbarHud.heartsShown

    @JvmStatic
    fun shouldHideVanillaHotbar(): Boolean = running && HotbarHud.enabled

    @JvmStatic
    fun shouldHideVanillaFood(): Boolean = running && HotbarHud.enabled && HotbarHud.foodShown

    @JvmStatic
    fun shouldHideVanillaAir(): Boolean = running && HotbarHud.enabled && HotbarHud.airShown

    @JvmStatic
    fun shouldHideVanillaXp(): Boolean = running && HotbarHud.enabled && HotbarHud.xpShown

    @JvmStatic
    fun shouldHideVanillaItemName(): Boolean = running && HotbarHud.enabled && HotbarHud.itemNameShown

    /** Used by the Gui mixin: the vanilla sidebar is hidden while our scoreboard renders. */
    @JvmStatic
    fun shouldHideVanillaSidebar(): Boolean = running && ScoreboardHud.enabled

    /** Used by the Gui mixin: the vanilla corner effect icons are hidden while our list renders. */
    @JvmStatic
    fun shouldHideVanillaEffects(): Boolean = running && EffectsHud.enabled

    @Suppress("unused")
    enum class Corner(
        override val tag: String,
        val isRight: Boolean,
        val isBottom: Boolean,
    ) : Tagged {
        TOP_LEFT("TopLeft", isRight = false, isBottom = false),
        TOP_RIGHT("TopRight", isRight = true, isBottom = false),
        BOTTOM_LEFT("BottomLeft", isRight = false, isBottom = true),
        BOTTOM_RIGHT("BottomRight", isRight = true, isBottom = true),
    }

    private var enabledAt = 0L

    override fun enable() {
        enabledAt = System.currentTimeMillis()
        TargetHud.resetPresence()
        super.enable()
    }

    // --- Shared helpers ------------------------------------------------------------------------

    private fun timeSeconds(): Float = (System.nanoTime() / 1_000_000_000.0 % 3600.0).toFloat()

    /** Physical pixels per local unit for an element rendered at [elementScale]. */
    private fun density(elementScale: Float): Float = elementScale * mc.window.guiScale.toFloat()

    /** Slow breathing gradient between the two accent tones. */
    private fun accentAt(fraction: Float, alphaF: Float): Int {
        val wave = sin((fraction * 0.35f - timeSeconds() * gradientSpeed * 0.35f) * (Math.PI * 2.0).toFloat())
        return scaleAlpha(lerpArgb(accentFrom.argb, accentTo.argb, 0.5f + 0.5f * wave), alphaF)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    /** Appear progress 0..1 since [sinceMs] (0 = treat as fully appeared). */
    private fun appearProgress(sinceMs: Long): Float =
        if (sinceMs == 0L) 1f else ((System.currentTimeMillis() - sinceMs) / APPEAR_MS).coerceIn(0f, 1f)

    private fun fadeAlpha(progress: Float): Float = 1f - (1f - progress) * (1f - progress) * (1f - progress)

    // Vanilla allows blurBeforeThisStratum() only once per frame; both panels share one marker
    private var blurMarkedEvent: OverlayRenderEvent? = null

    /** Requests the region blur for this frame; all panels share one capture. */
    private fun requestGlass(ctx: GuiGraphicsExtractor, event: OverlayRenderEvent): TextureSetup? {
        if (!blur) {
            return null
        }
        if (blurMarkedEvent !== event) {
            ctx.guiRenderState.blurBeforeThisStratum()
            blurMarkedEvent = event
        }
        return WatermarkGlass.request(blurStrength)
    }

    // --- Dragging / edit mode (only while the chat is open) -------------------------------------

    /** Edit mode (draggable panels + frames) is active only while the chat is open. */
    private fun editMode(): Boolean = mc.screen is net.minecraft.client.gui.screens.ChatScreen

    private enum class DragTarget {
        WATERMARK, TARGET_HUD, EFFECTS, NOTIFICATIONS, ARRAY_LIST,
        KEYSTROKES, INFO, SCOREBOARD, ARMOR, HOTBAR, COUNTERS, ITEM_BINDS, AUTO_SWAP, PVP_INTEL
    }

    /** While the chat is open every fresh, enabled element shows its accent edit frame. */
    private fun submitEditOverlay(ctx: GuiGraphicsExtractor, bounds: PanelBounds, target: DragTarget) {
        if (!editMode() || bounds.width <= 0f || System.currentTimeMillis() - bounds.time >= 500) {
            return
        }
        val active = dragging === target
        val pad = 1.5f
        val ring = scaleAlpha(accentAt(0.25f, 1f), if (active) 0.95f else 0.45f)
        val fillTint = scaleAlpha(accentAt(0.25f, 1f), if (active) 0.10f else 0.04f)

        // Frame each sub-rect when present (matches the real hitbox — e.g. ArrayList rows),
        // otherwise a single box around the bounding rectangle
        val frames = bounds.rects?.takeIf { it.isNotEmpty() }
            ?: listOf(floatArrayOf(bounds.x, bounds.y, bounds.width, bounds.height))

        var ex0 = Float.POSITIVE_INFINITY
        var ey0 = Float.POSITIVE_INFINITY
        var ex1 = Float.NEGATIVE_INFINITY
        var ey1 = Float.NEGATIVE_INFINITY
        for (rect in frames) {
            ex0 = minOf(ex0, rect[0]); ey0 = minOf(ey0, rect[1])
            ex1 = maxOf(ex1, rect[0] + rect[2]); ey1 = maxOf(ey1, rect[1] + rect[3])
        }

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI,
                TextureSetup.noTexture(),
                Matrix3x2f(ctx.pose()),
                ctx.scissorStack.peek(),
                ctx.watermarkBounds(ex0 - pad - 1f, ey0 - pad - 1f, ex1 + pad + 1f, ey1 + pad + 1f),
            ) { pose ->
                for (rect in frames) {
                    val x0 = rect[0] - pad
                    val y0 = rect[1] - pad
                    val x1 = rect[0] + rect[2] + pad
                    val y1 = rect[1] + rect[3] + pad
                    val outer = buildContour(x0, y0, x1, y1, 3f, 0f)
                    val inner = buildContour(x0, y0, x1, y1, 3f, -1f)
                    emitFill(outer, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(fillTint)
                    }
                    emitRing(
                        inner, outer,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(ring) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(ring) },
                    )
                }
            }
        )
    }

    /** Central edit-frame pass: draws a frame for every enabled element with fresh bounds. */
    @Suppress("unused")
    private val editFrameHandler = handler<OverlayRenderEvent> { event ->
        if (!editMode()) {
            return@handler
        }
        val ctx = event.context
        if (Watermark.enabled) submitEditOverlay(ctx, Watermark.bounds, DragTarget.WATERMARK)
        if (TargetHud.enabled) submitEditOverlay(ctx, TargetHud.bounds, DragTarget.TARGET_HUD)
        if (EffectsHud.enabled) submitEditOverlay(ctx, EffectsHud.bounds, DragTarget.EFFECTS)
        if (NotificationsHud.enabled) submitEditOverlay(ctx, NotificationsHud.bounds, DragTarget.NOTIFICATIONS)
        if (ArrayListHud.enabled) submitEditOverlay(ctx, ArrayListHud.bounds, DragTarget.ARRAY_LIST)
        if (KeystrokesHud.enabled) submitEditOverlay(ctx, KeystrokesHud.bounds, DragTarget.KEYSTROKES)
        if (InfoHud.enabled) submitEditOverlay(ctx, InfoHud.bounds, DragTarget.INFO)
        if (ScoreboardHud.enabled) submitEditOverlay(ctx, ScoreboardHud.bounds, DragTarget.SCOREBOARD)
        if (ArmorHud.enabled) submitEditOverlay(ctx, ArmorHud.bounds, DragTarget.ARMOR)
        if (HotbarHud.enabled) submitEditOverlay(ctx, HotbarHud.bounds, DragTarget.HOTBAR)
        if (CountersHud.enabled) submitEditOverlay(ctx, CountersHud.bounds, DragTarget.COUNTERS)
        if (ItemBindsHud.enabled) submitEditOverlay(ctx, ItemBindsHud.bounds, DragTarget.ITEM_BINDS)
        if (AutoSwapHud.enabled) submitEditOverlay(ctx, AutoSwapHud.bounds, DragTarget.AUTO_SWAP)
    }

    private var dragging: DragTarget? = null
    private var grabDX = 0f
    private var grabDY = 0f

    private fun mouseGuiX(): Float = (mc.mouseHandler.xpos() / mc.window.guiScale).toFloat()
    private fun mouseGuiY(): Float = (mc.mouseHandler.ypos() / mc.window.guiScale).toFloat()

    /** Last rendered panel bounds (gui units) for hit testing: x, y, w, h + freshness time. */
    internal class PanelBounds {
        var x = 0f
        var y = 0f
        var width = 0f
        var height = 0f
        var time = 0L

        /**
         * Optional precise sub-rectangles (each `[x, y, w, h]`). When present, hit testing and
         * the edit frame use these instead of the bounding box — for staggered shapes like the
         * ArrayList where the bounding box would catch a lot of empty space.
         */
        var rects: List<FloatArray>? = null

        fun set(x: Float, y: Float, width: Float, height: Float) {
            this.x = x
            this.y = y
            this.width = width
            this.height = height
            this.time = System.currentTimeMillis()
            this.rects = null
        }

        fun contains(mx: Float, my: Float): Boolean {
            if (System.currentTimeMillis() - time >= 500) {
                return false
            }
            val subRects = rects
            if (subRects != null) {
                return subRects.any { mx >= it[0] && mx <= it[0] + it[2] && my >= it[1] && my <= it[1] + it[3] }
            }
            return mx >= x && mx <= x + width && my >= y && my <= y + height
        }
    }

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        if (event.button != 0) {
            return@handler
        }
        if (event.action == 0) {
            dragging = null
            return@handler
        }
        if (event.action != 1 || !editMode()) {
            return@handler
        }

        val mx = mouseGuiX()
        val my = mouseGuiY()
        val hit = when {
            TargetHud.enabled && TargetHud.bounds.contains(mx, my) -> DragTarget.TARGET_HUD
            EffectsHud.enabled && EffectsHud.bounds.contains(mx, my) -> DragTarget.EFFECTS
            NotificationsHud.enabled && NotificationsHud.bounds.contains(mx, my) -> DragTarget.NOTIFICATIONS
            ArrayListHud.enabled && ArrayListHud.bounds.contains(mx, my) -> DragTarget.ARRAY_LIST
            KeystrokesHud.enabled && KeystrokesHud.bounds.contains(mx, my) -> DragTarget.KEYSTROKES
            InfoHud.enabled && InfoHud.bounds.contains(mx, my) -> DragTarget.INFO
            ScoreboardHud.enabled && ScoreboardHud.bounds.contains(mx, my) -> DragTarget.SCOREBOARD
            ArmorHud.enabled && ArmorHud.bounds.contains(mx, my) -> DragTarget.ARMOR
            CountersHud.enabled && CountersHud.bounds.contains(mx, my) -> DragTarget.COUNTERS
            PvpIntelHud.enabled && PvpIntelHud.bounds.contains(mx, my) -> DragTarget.PVP_INTEL
            ItemBindsHud.enabled && ItemBindsHud.bounds.contains(mx, my) -> DragTarget.ITEM_BINDS
            AutoSwapHud.enabled && AutoSwapHud.bounds.contains(mx, my) -> DragTarget.AUTO_SWAP
            HotbarHud.enabled && HotbarHud.bounds.contains(mx, my) -> DragTarget.HOTBAR
            Watermark.enabled && Watermark.bounds.contains(mx, my) -> DragTarget.WATERMARK
            else -> null
        }
        if (hit != null) {
            val bounds = when (hit) {
                DragTarget.TARGET_HUD -> TargetHud.bounds
                DragTarget.EFFECTS -> EffectsHud.bounds
                DragTarget.NOTIFICATIONS -> NotificationsHud.bounds
                DragTarget.ARRAY_LIST -> ArrayListHud.bounds
                DragTarget.KEYSTROKES -> KeystrokesHud.bounds
                DragTarget.INFO -> InfoHud.bounds
                DragTarget.SCOREBOARD -> ScoreboardHud.bounds
                DragTarget.ARMOR -> ArmorHud.bounds
                DragTarget.HOTBAR -> HotbarHud.bounds
                DragTarget.COUNTERS -> CountersHud.bounds
                DragTarget.PVP_INTEL -> PvpIntelHud.bounds
                DragTarget.ITEM_BINDS -> ItemBindsHud.bounds
                DragTarget.AUTO_SWAP -> AutoSwapHud.bounds
                DragTarget.WATERMARK -> Watermark.bounds
            }
            grabDX = mx - bounds.x
            grabDY = my - bounds.y
            dragging = hit
        }
    }

    // =============================================================================================
    // Watermark element
    // =============================================================================================

    private object Watermark : ToggleableValueGroup(this, "Watermark", true) {

        private const val TITLE = "LiquidBounce"

        private const val PAD_X = 7f
        private const val PAD_Y = 4.5f
        private const val GAP = 4.5f
        private const val SECTION_SPACE = 5f
        private const val SEPARATOR_WIDTH = 0.8f

        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

        private val corner by enumChoice("Corner", Corner.TOP_LEFT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 6f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)

        val bounds = PanelBounds()
        private val logoGlow by boolean("LogoGlow", true)
        private val showFps by boolean("ShowFPS", true)
        private val showPing by boolean("ShowPing", true)
        private val showTime by boolean("ShowTime", true)

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val px = fontSize
            val infoPx = px * 0.85f
            val density = density(scale)

            // Layout in local (unscaled) units
            val textHeight = WatermarkFont.BRAND.lineHeight(px, density)
            val logoSize = textHeight
            val brandWidth = WatermarkFont.BRAND.width(TITLE, px, density)
            val sections = buildSections()
            val sectionWidths = sections.map { WatermarkFont.TEXT.width(it, infoPx, density) }

            var width = PAD_X + logoSize + GAP + brandWidth + PAD_X
            for (sectionWidth in sectionWidths) {
                width += SECTION_SPACE + SEPARATOR_WIDTH + SECTION_SPACE + sectionWidth
            }
            val height = textHeight + PAD_Y * 2f

            // Entrance animation: fade in + spring-slide from the anchored edge
            val appear = appearProgress(enabledAt)
            val alphaF = fadeAlpha(appear)
            val slide = (1f - easeOutBack(appear)) * 18f

            // Dragging with the mouse while a screen (e.g. the inventory) is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.WATERMARK && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            // Anchor to the selected screen corner
            var x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            x += if (corner.isRight) slide else -slide

            bounds.set(x, y, width * scale, height * scale)

            val glassSetup = requestGlass(ctx, event)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // Glow behind the droplet, softly pulsing
            val glowPulse = 0.8f + 0.2f * sin(timeSeconds() * 2.2f)
            val glowColor = if (logoGlow) {
                scaleAlpha(accentAt(0.2f, alphaF), 0.30f * glowPulse)
            } else {
                0
            }

            // Section separator positions
            val separators = ArrayList<Float>(sections.size)
            var separatorX = PAD_X + logoSize + GAP + brandWidth
            for (sectionWidth in sectionWidths) {
                separatorX += SECTION_SPACE
                separators += separatorX
                separatorX += SEPARATOR_WIDTH + SECTION_SPACE + sectionWidth
            }

            drawGlassPanel(
                ctx, width, height, alphaF, density, glassSetup,
                glowCenterX = PAD_X + logoSize * 0.5f,
                glowCenterY = height * 0.5f,
                glowRadius = logoSize * 1.05f * glowPulse,
                glowColor = glowColor,
                separators = separators,
            )

            submitLogo(ctx, PAD_X, (height - logoSize) * 0.5f, logoSize, alphaF)

            // Brand title with the breathing gradient
            val baseline = PAD_Y + WatermarkFont.BRAND.ascent(px, density)
            WatermarkFont.BRAND.submit(ctx, TITLE, PAD_X + logoSize + GAP, PAD_Y, px, density) {
                accentAt(it, alphaF)
            }

            // Info sections, baseline-aligned with the title
            var sectionX = PAD_X + logoSize + GAP + brandWidth
            val infoArgb = scaleAlpha(infoColor.argb, alphaF)
            val infoTop = baseline - WatermarkFont.TEXT.ascent(infoPx, density)
            for ((index, section) in sections.withIndex()) {
                sectionX += SECTION_SPACE + SEPARATOR_WIDTH + SECTION_SPACE
                WatermarkFont.TEXT.submit(ctx, section, sectionX, infoTop, infoPx, density) { infoArgb }
                sectionX += sectionWidths[index]
            }

            ctx.pose().popMatrix()
        }

        private fun buildSections(): List<String> = buildList {
            if (showFps) {
                add("${mc.fps} $txtFps")
            }
            if (showPing) {
                val ping = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
                add("$ping $txtMs")
            }
            if (showTime) {
                add(TIME_FORMAT.format(LocalTime.now()))
            }
        }
    }

    // =============================================================================================
    // TargetHud element
    // =============================================================================================

    @Suppress("LargeClass", "TooManyFunctions")
    private object TargetHud : ToggleableValueGroup(this, "TargetHud", true) {

        private const val PAD_X = 7f
        private const val PAD_Y = 6f
        private const val GAP = 6f
        private const val BAR_HEIGHT = 7f

        private val mode by enumChoice("Mode", Mode.GLASS)

        private var offsetX by float("OffsetX", 60f, -2000f..2000f)
        private var offsetY by float("OffsetY", 20f, -2000f..2000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val itemScale by float("ItemScale", 1f, 0.5f..3f)

        /**
         * [GLASS] — the module's own glass card. [DIMASIK] — a 1:1 port of the DimasikSRC target
         * panel, laid out in native screen pixels the way the original element space works.
         * [RING] — the same native-pixel space, but the health lives in a ring on the right and
         * the equipment row moves inside the card.
         */
        @Suppress("unused")
        enum class Mode(override val tag: String) : Tagged {
            GLASS("Glass"),
            DIMASIK("Dimasik"),
            RING("Ring"),
        }

        val bounds = PanelBounds()

        @Suppress("unused")
        private val attackHandler = handler<AttackEntityEvent> { event ->
            val entity = event.entity
            if (entity is LivingEntity && entity !== mc.player) {
                // Our hit landed on this entity — fire the head shake + red flash
                hitEntityKey = entity.name.string
                damagedAt = System.currentTimeMillis()
            }
        }

        /** Snapshot of the rendered target — real or fake (preview). */
        @Suppress("LongParameterList")
        private class TargetInfo(
            val name: String,
            val health: Float,
            val maxHealth: Float,
            val distance: Float,
            val skin: Identifier?,
            val armor: List<ItemStack>,
            val mainHand: ItemStack,
            val offHand: ItemStack,
            /** Health without absorption and the unscaled max — the Dimasik bars read these. */
            val baseHealth: Float = health,
            val baseMaxHealth: Float = maxHealth,
            val absorption: Float = 0f,
            val hurtTime: Int = 0,
        ) {
            val pct: Float get() = (health / maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val items: List<ItemStack> get() = armor + listOf(mainHand, offHand)

            val healthPct: Float get() = (baseHealth / baseMaxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val absorptionPct: Float get() = (absorption / baseMaxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)

            /** Equipment in the original's order: helmet → boots, then main and off hand. */
            val dimasikItems: List<ItemStack>
                get() = (armor + listOf(mainHand, offHand)).filter { !it.isEmpty }
        }

        // Smoothly animated displayed health (+ a slower "damage ghost" trail) + appear state
        private var displayedPct = 1f
        private var ghostPct = 1f

        // Dimasik / Ring modes: their own damped health / absorption follow
        private var dimHealthPct = 1f
        private var dimAbsorptionPct = 0f
        private var ringPct = 1f
        private var lastFrameNs = 0L
        private var currentKey = ""

        /**
         * 0..1 presence of the whole element, walked at [APPEAR_MS] in both directions: the panel
         * fades and scales in when a target shows up and, just as importantly, plays the same thing
         * backwards from the last snapshot once the target is gone, instead of blinking out.
         */
        private var visibility = 0f
        private var lastInfo: TargetInfo? = null

        /** Forgets the last target so the panel animates in again after a toggle. */
        fun resetPresence() {
            visibility = 0f
            lastInfo = null
            currentKey = ""
            ringParticles.clear()
        }

        // Hit reaction (head shake + red flash) — triggered only by OUR attacks on the target
        private const val DAMAGE_FX_MS = 700f
        private var hitEntityKey = ""
        private var damagedAt = 0L

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val dt = frameDelta()

            // Only real targets; while a screen is open the player's own (real) data is shown,
            // so the panel can still be dragged around from the inventory
            val live = currentTarget() ?: if (editMode()) selfInfo() else null

            // Per-target state: reset the bars and the burst pool on target change
            if (live != null && live.name != currentKey) {
                currentKey = live.name
                displayedPct = live.pct
                ghostPct = live.pct
                dimHealthPct = live.healthPct
                dimAbsorptionPct = live.absorptionPct
                ringPct = live.pct
                lastHurtTime = live.hurtTime
                ringParticles.clear()
            }

            // Walk the presence towards 1 while there is a target and back to 0 once there is not;
            // the last snapshot stays on screen for exactly as long as the fade out takes
            val walk = dt / (APPEAR_MS / 1000f)
            if (live != null) {
                lastInfo = live
                visibility = (visibility + walk).coerceAtMost(1f)
            } else {
                visibility = (visibility - walk).coerceAtLeast(0f)
                if (visibility <= 0.001f) {
                    lastInfo = null
                    currentKey = ""
                    ringParticles.clear()
                    return@handler
                }
            }

            val info = lastInfo ?: return@handler

            when (mode) {
                Mode.DIMASIK -> {
                    renderDimasik(event, info, dt)
                    return@handler
                }

                Mode.RING -> {
                    renderRing(event, info, dt)
                    return@handler
                }

                Mode.GLASS -> Unit
            }

            // Hit reaction: only when OUR attack landed on the displayed target
            val damageT = if (damagedAt == 0L || hitEntityKey != info.name) {
                1f
            } else {
                (System.currentTimeMillis() - damagedAt) / DAMAGE_FX_MS
            }
            val damageAmp = if (damageT < 1f) (1f - damageT) * (1f - damageT) else 0f
            val shakeX = sin(timeSeconds() * 50f) * 1.6f * damageAmp
            val shakeY = cos(timeSeconds() * 43f) * 1.1f * damageAmp
            val flashStrength = 0.5f * damageAmp

            // Health bar interpolation (frame-time based); the ghost trail lags behind on damage
            // and snaps up on healing — a classic fighting-game delayed bar.
            displayedPct += (info.pct - displayedPct) * (dt * 10f).coerceIn(0f, 1f)
            ghostPct = if (displayedPct > ghostPct) {
                displayedPct
            } else {
                ghostPct + (displayedPct - ghostPct) * (dt * 2.5f).coerceIn(0f, 1f)
            }

            val ctx = event.context
            val px = fontSize
            val smallPx = px * 0.85f
            val density = density(scale)

            // Layout
            val nameHeight = WatermarkFont.BRAND.lineHeight(px, density)
            val smallHeight = WatermarkFont.TEXT.lineHeight(smallPx, density)
            val nameWidth = WatermarkFont.BRAND.width(info.name, px, density)
            val hpStr = "${ceil(info.health).toInt()} $txtHp"
            val distStr = "%.1f $txtMeters".format(info.distance)
            val hpWidth = WatermarkFont.TEXT.width(hpStr, smallPx, density)
            val distWidth = WatermarkFont.TEXT.width(distStr, smallPx, density)

            val items = info.items
            val cellGap = 3f
            // Icon size in exact device pixels (any fraction allowed): at whole multiples of 16
            // the vanilla sprite path is used; otherwise icons are supersampled from the 64 px
            // item atlas with a high-quality CPU downscale. Cells wrap the icon either way.
            val guiScaleF = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val deviceItemSize = Math.round(itemScale * guiScaleF * 16f).coerceAtLeast(8)
            val iconGui = deviceItemSize / guiScaleF
            val iconLocal = iconGui / scale
            val cellSize = iconLocal + 1.5f
            val cellsWidth = items.size * cellSize + (items.size - 1) * cellGap

            val contentWidth = maxOf(nameWidth, hpWidth + 8f + distWidth, 86f)
            val topBlockHeight = nameHeight + 4f + BAR_HEIGHT + 4f + smallHeight
            val headSize = topBlockHeight
            val innerWidth = headSize + GAP + contentWidth
            val width = PAD_X * 2f + innerWidth
            // Main panel holds only the content; bare cells attach right under it
            val height = PAD_Y * 2f + topBlockHeight
            val rowY = height + 2f

            val appear = visibility
            val alphaF = fadeAlpha(appear)
            val pop = 0.96f + 0.04f * easeOutBack(appear)

            // Dragging with the mouse while a screen (e.g. the inventory) is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.TARGET_HUD && editMode()) {
                offsetX = (mouseGuiX() - grabDX - screenWidth * 0.5f).coerceIn(-2000f, 2000f)
                offsetY = (mouseGuiY() - grabDY - screenHeight * 0.5f).coerceIn(-2000f, 2000f)
            }

            // Position relative to the screen center
            val x = screenWidth * 0.5f + offsetX
            val y = screenHeight * 0.5f + offsetY

            val es = scale * pop
            bounds.set(x, y, maxOf(width, cellsWidth) * es, (rowY + cellSize) * es)

            val glassSetup = requestGlass(ctx, event)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale * pop, scale * pop)

            drawGlassPanel(ctx, width, height, alphaF, density, glassSetup)

            // Head from the player skin (or a placeholder), shaking + flashing red on our hits
            val headX = PAD_X + shakeX
            val headY = PAD_Y + shakeY
            submitHead(ctx, info.skin, headX, headY, headSize, density, alphaF, flashStrength)

            val contentX = PAD_X + headSize + GAP
            var contentY = PAD_Y

            // Name
            WatermarkFont.BRAND.submit(ctx, info.name, contentX, contentY, px, density) {
                scaleAlpha(-1, alphaF)
            }
            contentY += nameHeight + 4f

            // Health bar: dark track + damage ghost + accent fill with gloss, shimmer and cap glow
            submitHealthBar(ctx, contentX, contentY, contentWidth, BAR_HEIGHT, displayedPct, ghostPct, density, alphaF)
            contentY += BAR_HEIGHT + 4f

            // HP (health-tinted) on the left, distance on the right
            val healthColor = scaleAlpha(
                lerpArgb(0xFFE06060.toInt(), 0xFF7FCB8F.toInt(), info.pct),
                alphaF,
            )
            WatermarkFont.TEXT.submit(ctx, hpStr, contentX, contentY, smallPx, density) { healthColor }
            val distColor = scaleAlpha(infoColor.argb, alphaF)
            WatermarkFont.TEXT.submit(
                ctx, distStr,
                contentX + contentWidth - distWidth, contentY, smallPx, density,
            ) { distColor }

            // Bare equipment cells attached right under the panel
            submitItemCells(ctx, items, 0f, rowY, cellSize, cellGap, density, alphaF)

            ctx.pose().popMatrix()

            // Items on top: native sprites at whole factors, supersampled icons otherwise
            submitItems(
                ctx, items,
                rowGuiX = x,
                rowGuiY = y + rowY * es,
                cellSize = cellSize,
                cellGap = cellGap,
                elementScale = es,
                deviceItemSize = deviceItemSize,
                alphaF = alphaF,
            )
        }

        private fun currentTarget(): TargetInfo? {
            val self = mc.player ?: return null
            val target = resolveCombatTarget() ?: return null

            // Health is read exactly like the nametag reads it (scoreboard-aware, absorption
            // folded in the same way), so the two never disagree on the same player.
            val shownHealth = target.nametagHealth()
            return TargetInfo(
                name = target.name.string,
                health = shownHealth,
                maxHealth = target.maxHealth.coerceAtLeast(shownHealth),
                distance = self.distanceTo(target),
                skin = (target as? AbstractClientPlayer)?.skin?.body?.texturePath(),
                armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                    .map { target.getItemBySlot(it) },
                mainHand = target.getItemBySlot(EquipmentSlot.MAINHAND),
                offHand = target.getItemBySlot(EquipmentSlot.OFFHAND),
                baseHealth = target.getActualHealth(),
                baseMaxHealth = target.maxHealth,
                absorption = target.absorptionAmount,
                hurtTime = target.hurtTime,
            )
        }

        /** The player's own, real data — used as the drag stand-in while a screen is open. */
        private fun selfInfo(): TargetInfo? {
            val self = mc.player ?: return null
            return TargetInfo(
                name = self.name.string,
                health = self.health,
                maxHealth = self.maxHealth,
                distance = 0f,
                skin = self.skin?.body?.texturePath(),
                armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                    .map { self.getItemBySlot(it) },
                mainHand = self.getItemBySlot(EquipmentSlot.MAINHAND),
                offHand = self.getItemBySlot(EquipmentSlot.OFFHAND),
                baseHealth = self.health,
                baseMaxHealth = self.maxHealth,
                absorption = self.absorptionAmount,
                hurtTime = self.hurtTime,
            )
        }

        /**
         * The target's face + hat from their skin as AA rounded contour fills; placeholder
         * otherwise. [flash] (0..1) overlays a red tint — the hit feedback. [cornerRadius],
         * [hat] and [tint] let the Dimasik layout draw the same head its own way: a bigger
         * corner radius, no hat layer and the hurt-time tint multiplied into the face.
         */
        @Suppress("LongParameterList")
        private fun submitHead(
            ctx: GuiGraphicsExtractor,
            skin: Identifier?,
            x: Float,
            y: Float,
            size: Float,
            density: Float,
            alphaF: Float,
            flash: Float = 0f,
            cornerRadius: Float = 3f,
            hat: Boolean = true,
            tintColor: Int = -1,
        ) {
            val r = cornerRadius.coerceAtMost(size * 0.5f)
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val contour = buildContour(x, y, x + size, y + size, r, 0f)
            val contourFeather = buildContour(x, y, x + size, y + size, r, feather)
            val centerX = x + size * 0.5f
            val centerY = y + size * 0.5f
            val tint = scaleAlpha(tintColor, alphaF)

            if (skin == null) {
                val placeholder = scaleAlpha(0xFF373A44.toInt(), alphaF)
                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(x, y, x + size, y + size),
                    ) { pose ->
                        emitFill(contour, centerX, centerY) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(placeholder)
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(placeholder) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(placeholder and 0xFFFFFF) },
                        )
                    }
                )
                return
            }

            val texture = mc.textureManager.getTexture(skin)
            val setup = TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
            )

            // Face = skin px 8..16, hat overlay = 40..48 (of 64) — both mapped over the same square
            val uFace = { vx: Float -> 0.125f + (vx - x) / size * 0.125f }
            val vFace = { vy: Float -> 0.125f + (vy - y) / size * 0.125f }
            val uHat = { vx: Float -> 0.625f + (vx - x) / size * 0.125f }

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI_TEXTURED,
                    setup,
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x, y, x + size, y + size),
                ) { pose ->
                    emitFill(contour, centerX, centerY) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setUv(uFace(vx), vFace(vy)).setColor(tint)
                    }
                    if (hat) {
                        emitFill(contour, centerX, centerY) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setUv(uHat(vx), vFace(vy)).setColor(tint)
                        }
                    }
                    emitRing(
                        contour, contourFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setUv(uFace(vx), vFace(vy)).setColor(tint) },
                        { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setUv(uFace(vx), vFace(vy)).setColor(tint and 0xFFFFFF)
                        },
                    )
                }
            )

            // Red hit flash over the head
            if (flash > 0.01f) {
                val flashColor = scaleAlpha(0xFFE0443C.toInt(), flash * alphaF)
                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(x, y, x + size, y + size),
                    ) { pose ->
                        emitFill(contour, centerX, centerY) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(flashColor)
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(flashColor) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(flashColor and 0xFFFFFF) },
                        )
                    }
                )
            }
        }

        /**
         * Items drawn over the cells at an exact device-pixel size, origin snapped to the grid.
         * Whole multiples of the native 16 px go through the vanilla sprite path (perfect, with
         * glint); fractional sizes are supersampled from the 64 px [ItemImageAtlas] bake with a
         * high-quality CPU downscale, drawn 1:1 from an own strip texture.
         */
        @Suppress("LongParameterList")
        private fun submitItems(
            ctx: GuiGraphicsExtractor,
            items: List<ItemStack>,
            rowGuiX: Float,
            rowGuiY: Float,
            cellSize: Float,
            cellGap: Float,
            elementScale: Float,
            deviceItemSize: Int,
            alphaF: Float,
        ) {
            val guiScale = mc.window.guiScale.toFloat()
            if (guiScale <= 0f) {
                return
            }
            val itemSizeGui = deviceItemSize / guiScale

            // Supersampled strip for fractional sizes (falls back to vanilla when unavailable)
            val stripSetup = if (deviceItemSize % 16 != 0) {
                SuperSampledIcons.setupFor(items, deviceItemSize)
            } else {
                null
            }

            if (stripSetup != null) {
                val slots = items.size.coerceAtLeast(1)
                val tint = scaleAlpha(-1, alphaF)
                val rowWidthGui = (items.size * (cellSize + cellGap)) * elementScale

                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI_TEXTURED,
                        stripSetup,
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(rowGuiX, rowGuiY, rowGuiX + rowWidthGui, rowGuiY + cellSize * elementScale),
                    ) { pose ->
                        for ((index, stack) in items.withIndex()) {
                            if (stack.isEmpty) {
                                continue
                            }
                            val cellGuiX = rowGuiX + index * (cellSize + cellGap) * elementScale
                            val inset = (cellSize * elementScale - itemSizeGui) * 0.5f
                            val gx = Math.round((cellGuiX + inset) * guiScale) / guiScale
                            val gy = Math.round((rowGuiY + inset) * guiScale) / guiScale
                            val u0 = index.toFloat() / slots
                            val u1 = (index + 1).toFloat() / slots

                            addVertexWith2DPose(pose, gx, gy).setUv(u0, 0f).setColor(tint)
                            addVertexWith2DPose(pose, gx, gy + itemSizeGui).setUv(u0, 1f).setColor(tint)
                            addVertexWith2DPose(pose, gx + itemSizeGui, gy + itemSizeGui).setUv(u1, 1f).setColor(tint)
                            addVertexWith2DPose(pose, gx + itemSizeGui, gy).setUv(u1, 0f).setColor(tint)
                        }
                    }
                )
                return
            }

            // Vanilla sprite path (whole factors, or when the atlas isn't ready yet)
            val wholeFactor = (deviceItemSize / 16f).let { factor ->
                if (deviceItemSize % 16 == 0) factor else Math.round(factor).coerceAtLeast(1).toFloat()
            }
            val itemGuiScale = wholeFactor / guiScale
            val vanillaSizeGui = 16f * itemGuiScale
            for ((index, stack) in items.withIndex()) {
                if (stack.isEmpty) {
                    continue
                }
                val cellGuiX = rowGuiX + index * (cellSize + cellGap) * elementScale
                val inset = (cellSize * elementScale - vanillaSizeGui) * 0.5f
                val gx = Math.round((cellGuiX + inset) * guiScale) / guiScale
                val gy = Math.round((rowGuiY + inset) * guiScale) / guiScale

                ctx.pose().pushMatrix()
                ctx.pose().translate(gx, gy)
                ctx.pose().scale(itemGuiScale, itemGuiScale)
                ctx.item(stack, 0, 0)
                ctx.pose().popMatrix()
            }
        }

        /**
         * Builds and caches a one-row strip texture with the row's items downscaled from the
         * 64 px [ItemImageAtlas] bake to an exact device-pixel size, so fractional icon sizes stay
         * clean.
         */
        private object SuperSampledIcons {

            /**
             * Width of the resample window as a fraction of a destination pixel. 1 is a plain box
             * average (smooth, the pixel art goes soft); this keeps it tight so texel blocks stay
             * flat and only their borders get a sub-pixel blend.
             */
            private const val ICON_SHARPNESS = 0.4f

            private var keyItems: List<Item?> = emptyList()
            private var keySize = 0
            private var texture: DynamicTexture? = null
            private var setup: TextureSetup? = null

            fun setupFor(items: List<ItemStack>, deviceSize: Int): TextureSetup? {
                if (!ItemImageAtlas.isAtlasAvailable) {
                    return null
                }
                val ids = items.map { if (it.isEmpty) null else it.item }
                setup?.let {
                    if (ids == keyItems && deviceSize == keySize) {
                        return it
                    }
                }

                return try {
                    rebuild(items, ids, deviceSize)
                } catch (e: Throwable) {
                    logger.error("Hud2: failed to build supersampled item strip", e)
                    null
                }
            }

            private fun rebuild(items: List<ItemStack>, ids: List<Item?>, deviceSize: Int): TextureSetup {
                val slots = items.size.coerceAtLeast(1)
                val nativeImage = NativeImage(NativeImage.Format.RGBA, deviceSize * slots, deviceSize, false)

                for ((index, stack) in items.withIndex()) {
                    val offsetX = index * deviceSize
                    val source = if (stack.isEmpty) null else ItemImageAtlas.getItemImage(stack.item)
                    if (source == null) {
                        for (py in 0 until deviceSize) {
                            for (px in 0 until deviceSize) {
                                nativeImage.setPixel(offsetX + px, py, 0)
                            }
                        }
                        continue
                    }
                    resampleInto(nativeImage, offsetX, source, deviceSize)
                }

                texture?.close()
                val newTexture = DynamicTexture(Suppliers.ofInstance("Hud2 item strip ${deviceSize}px"), nativeImage)
                texture = newTexture
                keyItems = ids
                keySize = deviceSize
                return TextureSetup.singleTexture(
                    newTexture.textureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                ).also { setup = it }
            }

            /**
             * Sharp coverage resample of one 64 px item bake down to [size], written straight into
             * the strip at [offsetX].
             *
             * Java2D's bilinear blit only ever takes 2x2 taps, so any reduction beyond 2:1 — which
             * 64 px down to a HUD-sized icon always is — both blurs the sprite and aliases it; that
             * is what made scaled icons look chewed up. Instead every source pixel is weighted by
             * how much of the sample window it covers, and that window is deliberately **narrower**
             * than the destination pixel ([ICON_SHARPNESS]): wide enough to antialias a texel
             * border over a fraction of a pixel, far too narrow to smear a whole texel into its
             * neighbours. The result reads like nearest-neighbour — flat texel blocks, hard edges,
             * the same sprite as unscaled — without nearest's ragged, unevenly wide texels. Whole
             * ratios (64→32, 64→16) come out as an exact texel copy. The average runs in
             * premultiplied alpha, otherwise transparent pixels drag a dark fringe into the edges.
             */
            private fun resampleInto(target: NativeImage, offsetX: Int, source: BufferedImage, size: Int) {
                val sourceWidth = source.width
                val sourceHeight = source.height
                val pixels = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth)
                val scaleX = sourceWidth.toFloat() / size
                val scaleY = sourceHeight.toFloat() / size
                // Half of the sample window, centred on the destination pixel centre. Never below
                // half a source pixel, or the window could fall between samples entirely.
                val halfX = (scaleX * 0.5f * ICON_SHARPNESS).coerceAtLeast(0.5f)
                val halfY = (scaleY * 0.5f * ICON_SHARPNESS).coerceAtLeast(0.5f)

                for (dy in 0 until size) {
                    val centerY = (dy + 0.5f) * scaleY
                    for (dx in 0 until size) {
                        val centerX = (dx + 0.5f) * scaleX
                        val packed = samplePixel(
                            pixels = pixels,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            left = centerX - halfX,
                            right = centerX + halfX,
                            top = centerY - halfY,
                            bottom = centerY + halfY,
                        )
                        target.setPixel(offsetX + dx, dy, packed)
                    }
                }
            }

            /** One destination pixel: the premultiplied average over its sample window. */
            @Suppress("LongParameterList")
            private fun samplePixel(
                pixels: IntArray,
                sourceWidth: Int,
                sourceHeight: Int,
                left: Float,
                right: Float,
                top: Float,
                bottom: Float,
            ): Int {
                var weightSum = 0f
                var alphaSum = 0f
                var redSum = 0f
                var greenSum = 0f
                var blueSum = 0f

                val firstRow = top.toInt().coerceAtLeast(0)
                val lastRow = ceil(bottom).toInt().coerceAtMost(sourceHeight)
                val firstColumn = left.toInt().coerceAtLeast(0)
                val lastColumn = ceil(right).toInt().coerceAtMost(sourceWidth)

                for (sy in firstRow until lastRow) {
                    val weightY = minOf(bottom, sy + 1f) - maxOf(top, sy.toFloat())
                    if (weightY <= 0f) {
                        continue
                    }
                    val rowOffset = sy * sourceWidth
                    for (sx in firstColumn until lastColumn) {
                        val weightX = minOf(right, sx + 1f) - maxOf(left, sx.toFloat())
                        if (weightX <= 0f) {
                            continue
                        }
                        val weight = weightX * weightY
                        val argb = pixels[rowOffset + sx]
                        val alpha = (argb ushr 24 and 0xFF) * weight
                        weightSum += weight
                        alphaSum += alpha
                        redSum += alpha * (argb ushr 16 and 0xFF)
                        greenSum += alpha * (argb ushr 8 and 0xFF)
                        blueSum += alpha * (argb and 0xFF)
                    }
                }

                if (alphaSum <= 0f || weightSum <= 0f) {
                    return 0
                }
                val alpha = (alphaSum / weightSum).roundToInt().coerceIn(0, 255)
                val red = (redSum / alphaSum).roundToInt().coerceIn(0, 255)
                val green = (greenSum / alphaSum).roundToInt().coerceIn(0, 255)
                val blue = (blueSum / alphaSum).roundToInt().coerceIn(0, 255)
                return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        /**
         * A row of neat item cells: dark inset rounded squares with AA feathering.
         */
        @Suppress("LongParameterList")
        private fun submitItemCells(
            ctx: GuiGraphicsExtractor,
            items: List<ItemStack>,
            x: Float,
            y: Float,
            cellSize: Float,
            cellGap: Float,
            density: Float,
            alphaF: Float,
        ) {
            if (items.isEmpty()) {
                return
            }
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val cellR = 2f
            val cellTop = scaleAlpha(0xFF000000.toInt(), 0.40f * alphaF)
            val cellBottom = scaleAlpha(0xFF000000.toInt(), 0.26f * alphaF)
            val rowWidth = items.size * cellSize + (items.size - 1) * cellGap

            // All cells in one element
            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x - 1f, y - 1f, x + rowWidth + 1f, y + cellSize + 1f),
                ) { pose ->
                    val cellAt = { vy: Float ->
                        lerpArgb(cellTop, cellBottom, ((vy - y) / cellSize).coerceIn(0f, 1f))
                    }
                    for (index in items.indices) {
                        val cx = x + index * (cellSize + cellGap)
                        val contour = buildContour(cx, y, cx + cellSize, y + cellSize, cellR, 0f)
                        val contourFeather = buildContour(cx, y, cx + cellSize, y + cellSize, cellR, feather)
                        emitFill(contour, cx + cellSize * 0.5f, y + cellSize * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy))
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy)) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy) and 0xFFFFFF) },
                        )
                    }
                }
            )

        }

        /**
         * Health bar: dark inset track, a lagging warm "damage ghost" trail, the accent-gradient
         * fill with a glossy top highlight, a travelling shimmer band and a soft glow at the
         * leading edge. Everything contour-tessellated with AA feathering.
         */
        @Suppress("LongParameterList", "detekt:LongMethod")
        private fun submitHealthBar(
            ctx: GuiGraphicsExtractor,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            pct: Float,
            ghost: Float,
            density: Float,
            alphaF: Float,
        ) {
            val r = 2.2f // rectangular bar with gently softened corners
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val fillWidth = (width * pct).coerceIn(0f, width)
            val ghostWidth = (width * ghost).coerceIn(0f, width)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x - height, y - height, x + width + height, y + height * 2f),
                ) { pose ->
                    // Inset track: slightly darker at the top for depth
                    val trackTop = scaleAlpha(0xFF000000.toInt(), 0.48f * alphaF)
                    val trackBottom = scaleAlpha(0xFF000000.toInt(), 0.30f * alphaF)
                    val trackAt = { vy: Float -> lerpArgb(trackTop, trackBottom, ((vy - y) / height).coerceIn(0f, 1f)) }
                    val track = buildContour(x, y, x + width, y + height, r, 0f)
                    val trackFeather = buildContour(x, y, x + width, y + height, r, feather)
                    emitFill(track, x + width * 0.5f, y + height * 0.5f) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(trackAt(vy))
                    }
                    emitRing(
                        track, trackFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackAt(vy)) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackAt(vy) and 0xFFFFFF) },
                    )
                    barDressing(pose, x, y, x + width, y + height, r, alphaF)

                    // Damage ghost: warm red trail that the fill "eats" after a hit
                    if (ghostWidth > fillWidth + 0.5f) {
                        val ghostR = r.coerceAtMost(ghostWidth * 0.5f)
                        val ghostColor = scaleAlpha(0xFFE0635F.toInt(), 0.55f * alphaF)
                        val ghostContour = buildContour(x, y, x + ghostWidth, y + height, ghostR, 0f)
                        emitFill(ghostContour, x + ghostWidth * 0.5f, y + height * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(ghostColor)
                        }
                    }

                    // Accent fill
                    if (fillWidth > 0.5f) {
                        val fillR = r.coerceAtMost(fillWidth * 0.5f)
                        val fill = buildContour(x, y, x + fillWidth, y + height, fillR, 0f)
                        val fillFeather = buildContour(x, y, x + fillWidth, y + height, fillR, feather * 0.7f)
                        val colorOf = { vx: Float -> accentAt(((vx - x) / width).coerceIn(0f, 1f), alphaF) }
                        emitFill(fill, x + fillWidth * 0.5f, y + height * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx))
                        }
                        emitRing(
                            fill, fillFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx)) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx) and 0xFFFFFF) },
                        )

                        // Glossy top highlight on the fill
                        val glossLeft = x + fillR * 0.7f
                        val glossRight = (x + fillWidth - fillR * 0.7f)
                        if (glossRight > glossLeft) {
                            val glossTop = scaleAlpha(0xFFFFFFFF.toInt(), 0.13f * alphaF)
                            val glossBottom = scaleAlpha(0xFFFFFFFF.toInt(), 0.015f * alphaF)
                            addVertexWith2DPose(pose, glossLeft, y + height * 0.12f).setColor(glossTop)
                            addVertexWith2DPose(pose, glossLeft, y + height * 0.50f).setColor(glossBottom)
                            addVertexWith2DPose(pose, glossRight, y + height * 0.50f).setColor(glossBottom)
                            addVertexWith2DPose(pose, glossRight, y + height * 0.12f).setColor(glossTop)
                        }

                        // Travelling shimmer band across the fill
                        val band = height * 2.4f
                        val cycle = width + band * 2f
                        val bandCenter = x - band + (timeSeconds() * width * 0.45f) % cycle
                        val shimmerPeak = scaleAlpha(0xFFFFFFFF.toInt(), 0.16f * alphaF)
                        val shimmerEdge = shimmerPeak and 0xFFFFFF
                        val clamp = { vx: Float -> vx.coerceIn(x, x + fillWidth) }
                        val s0 = clamp(bandCenter - band)
                        val s1 = clamp(bandCenter)
                        val s2 = clamp(bandCenter + band)
                        if (s1 > s0) {
                            addVertexWith2DPose(pose, s0, y).setColor(shimmerEdge)
                            addVertexWith2DPose(pose, s0, y + height).setColor(shimmerEdge)
                            addVertexWith2DPose(pose, s1, y + height).setColor(shimmerPeak)
                            addVertexWith2DPose(pose, s1, y).setColor(shimmerPeak)
                        }
                        if (s2 > s1) {
                            addVertexWith2DPose(pose, s1, y).setColor(shimmerPeak)
                            addVertexWith2DPose(pose, s1, y + height).setColor(shimmerPeak)
                            addVertexWith2DPose(pose, s2, y + height).setColor(shimmerEdge)
                            addVertexWith2DPose(pose, s2, y).setColor(shimmerEdge)
                        }

                        // Soft glow at the leading edge of the fill
                        if (fillWidth > height && fillWidth < width - 0.5f) {
                            val capColor = scaleAlpha(accentAt(pct, alphaF), 0.45f)
                            radialGlow(pose, x + fillWidth, y + height * 0.5f, height * 1.1f, capColor)
                        }
                    }
                }
            )
        }

        // -----------------------------------------------------------------------------------------
        // Dimasik mode: a 1:1 port of the DimasikSRC target panel
        // -----------------------------------------------------------------------------------------
        //
        // The original element lives in *native screen pixels*: it divides the gui scale out of its
        // pose before drawing, so the card keeps the same physical size at any GUI scale. This port
        // keeps that space (one local unit is one physical pixel), so every number below is the
        // original's, unchanged: a 188x64 card with a 10 px corner radius and a 1 px outline, the
        // 48x48 skin face inset by 8 px inside a 1.5 px ring, the name at 12 px, the "Hp: x.x" line
        // at 10 px, a 116x5 health bar with the absorption bar stacked over it, and the equipment
        // row of 16 px icons sitting directly on top of the card.

        private const val DIM_WIDTH = 188f
        private const val DIM_HEIGHT = 64f
        private const val DIM_RADIUS = 10f
        private const val DIM_PAD = 8f
        private const val DIM_HEAD_SIZE = 48f
        private const val DIM_HEAD_RADIUS = 8f
        private const val DIM_HEAD_RING = 1.5f
        private const val DIM_COLUMN_X = 64f
        private const val DIM_NAME_Y = 12f
        private const val DIM_NAME_PX = 12f
        private const val DIM_HP_Y = 29f
        private const val DIM_HP_PX = 10f
        private const val DIM_BAR_Y = 47f
        private const val DIM_BAR_WIDTH = 116f
        private const val DIM_BAR_HEIGHT = 5f
        private const val DIM_BAR_RADIUS = 2f
        private const val DIM_ITEM = 16f

        /** The head stays red for the ten hurt ticks the entity keeps after taking damage. */
        private const val DIM_HURT_TICKS = 10f

        /** Per-second retention of the bar follow, the original's damped step. */
        private const val DIM_SMOOTHING = 1.0E-5f

        private val dimAbsorptionFrom = 0xFFF2C55C.toInt()
        private val dimAbsorptionTo = 0xFFC08A2A.toInt()

        /** Everything the two native-pixel cards need to place themselves for this frame. */
        @Suppress("LongParameterList")
        private class CardFrame(
            val centerX: Float,
            val centerY: Float,
            /** Gui units per native pixel, without and with the appear animation. */
            val es: Float,
            val esa: Float,
            val alphaF: Float,
            val density: Float,
            val width: Float,
            val height: Float,
        ) {
            /** A local (native pixel) point of the card in gui coordinates. */
            fun guiX(localX: Float) = centerX + (localX - width * 0.5f) * esa

            fun guiY(localY: Float) = centerY + (localY - height * 0.5f) * esa
        }

        /**
         * The frame both native-pixel cards share: one local unit is one physical pixel, the card
         * grows out of its own center on appear, and it stays draggable while a screen is open.
         */
        private fun nativeCardFrame(width: Float, height: Float): CardFrame {
            val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val es = scale / guiScale
            val alphaF = fadeAlpha(visibility)

            // Dragging with the mouse while a screen (e.g. the inventory) is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.TARGET_HUD && editMode()) {
                offsetX = (mouseGuiX() - grabDX - screenWidth * 0.5f).coerceIn(-2000f, 2000f)
                offsetY = (mouseGuiY() - grabDY - screenHeight * 0.5f).coerceIn(-2000f, 2000f)
            }

            // Position relative to the screen center
            val x = screenWidth * 0.5f + offsetX
            val y = screenHeight * 0.5f + offsetY
            bounds.set(x, y, width * es, height * es)

            return CardFrame(
                centerX = x + width * es * 0.5f,
                centerY = y + height * es * 0.5f,
                es = es,
                esa = es * alphaF.coerceAtLeast(0.02f),
                alphaF = alphaF,
                density = density(es),
                width = width,
                height = height,
            )
        }

        /** Puts the pose into the card's local space: (0,0) is the card's top-left corner. */
        private fun pushCardPose(ctx: GuiGraphicsExtractor, frame: CardFrame) {
            ctx.pose().pushMatrix()
            ctx.pose().translate(frame.centerX, frame.centerY)
            ctx.pose().scale(frame.esa, frame.esa)
            ctx.pose().translate(-frame.width * 0.5f, -frame.height * 0.5f)
        }

        /** Seconds since this element's previous frame (0 on the first one, capped after a stall). */
        private fun frameDelta(): Float {
            val nowNs = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat()
            lastFrameNs = nowNs
            return dt.coerceIn(0f, 0.25f)
        }

        /** Frame-time damped follow factor, the original's step. */
        private fun followStep(dt: Float): Float =
            if (dt <= 0f) 1f else (1f - DIM_SMOOTHING.pow(dt)).coerceIn(0f, 1f)

        private fun renderDimasik(event: OverlayRenderEvent, info: TargetInfo, dt: Float) {
            val ctx = event.context
            val frame = nativeCardFrame(DIM_WIDTH, DIM_HEIGHT)
            val density = frame.density
            val alphaF = frame.alphaF

            // Both bars follow the real values with a frame-time damped step
            val step = followStep(dt)
            dimHealthPct += (info.healthPct - dimHealthPct) * step
            dimAbsorptionPct += (info.absorptionPct - dimAbsorptionPct) * step

            pushCardPose(ctx, frame)

            submitDimasikCard(ctx, DIM_WIDTH, DIM_HEIGHT, density, alphaF)

            // Skin face, tinted red for as long as the target keeps hurt ticks
            val hurt = (info.hurtTime / DIM_HURT_TICKS).coerceIn(0f, 1f)
            submitHead(
                ctx = ctx,
                skin = info.skin,
                x = DIM_PAD,
                y = DIM_PAD,
                size = DIM_HEAD_SIZE,
                density = density,
                alphaF = alphaF,
                cornerRadius = DIM_HEAD_RADIUS,
                hat = false,
                tintColor = lerpArgb(0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), hurt),
            )
            submitRoundedOutline(
                ctx = ctx,
                x0 = DIM_PAD,
                y0 = DIM_PAD,
                x1 = DIM_PAD + DIM_HEAD_SIZE,
                y1 = DIM_PAD + DIM_HEAD_SIZE,
                r = DIM_HEAD_RADIUS,
                thickness = DIM_HEAD_RING,
                color = accentAt(0.5f, alphaF),
                density = density,
            )

            WatermarkFont.BRAND.submit(ctx, info.name, DIM_COLUMN_X, DIM_NAME_Y, DIM_NAME_PX, density) {
                scaleAlpha(-1, alphaF)
            }

            // "Hp: 20.0", always with a dot, the way the original forces it
            val hpText = "Hp: %.1f".format(Locale.ROOT, info.baseHealth)
            val hpColor = scaleAlpha(infoColor.argb, alphaF)
            WatermarkFont.TEXT.submit(ctx, hpText, DIM_COLUMN_X, DIM_HP_Y, DIM_HP_PX, density) { hpColor }

            submitDimasikBars(ctx, density, alphaF)

            ctx.pose().popMatrix()

            // Equipment row on top of the card: helmet, chest, legs, boots, main hand, off hand.
            // Kept at the original's tight 16 px grid — this mode is a 1:1 port, so the row does not
            // grow into the card's spare width the way the Ring one does. One native pixel is one
            // device pixel times the element scale, so that product is the nominal icon size in
            // device pixels; taken without the appear animation, so the supersampled strip is not
            // rebuilt on every frame of it.
            val itemDevice = cardIconDevice(DIM_ITEM * scale * itemScale)
            val itemGui = itemDevice / mc.window.guiScale.toFloat().coerceAtLeast(1f)
            submitCardItems(
                ctx = ctx,
                items = info.dimasikItems,
                rowGuiX = frame.guiX(0f),
                rowGuiY = frame.guiY(0f) - itemGui,
                gapGui = 0f,
                deviceSize = itemDevice,
                alphaF = alphaF,
            )
        }

        /** The flat card: body fill with an AA edge, then the hairline outline on top of it. */
        private fun submitDimasikCard(
            ctx: GuiGraphicsExtractor,
            width: Float,
            height: Float,
            density: Float,
            alphaF: Float,
        ) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val body = scaleAlpha(backgroundBottom.argb, alphaF)
            val edge = buildContour(0f, 0f, width, height, DIM_RADIUS, 0f)
            val edgeFeather = buildContour(0f, 0f, width, height, DIM_RADIUS, feather)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(-2f, -2f, width + 2f, height + 2f),
                ) { pose ->
                    emitFill(edge, width * 0.5f, height * 0.5f) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(body)
                    }
                    emitRing(
                        edge, edgeFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(body) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(body and 0xFFFFFF) },
                    )
                }
            )

            submitRoundedOutline(
                ctx = ctx,
                x0 = 0f,
                y0 = 0f,
                x1 = width,
                y1 = height,
                r = DIM_RADIUS,
                thickness = 1f,
                color = scaleAlpha(0xFFFFFFFF.toInt(), 0.10f * alphaF),
                density = density,
            )
        }

        /** Health track, the accent health fill and the absorption bar stacked over it. */
        private fun submitDimasikBars(ctx: GuiGraphicsExtractor, density: Float, alphaF: Float) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val trackColor = scaleAlpha(0xFF000000.toInt(), 0.45f * alphaF)
            val healthWidth = (DIM_BAR_WIDTH * dimHealthPct).coerceIn(0f, DIM_BAR_WIDTH)
            val absorptionWidth = (DIM_BAR_WIDTH * dimAbsorptionPct).coerceIn(0f, DIM_BAR_WIDTH)
            val barRight = DIM_COLUMN_X + DIM_BAR_WIDTH
            val barBottom = DIM_BAR_Y + DIM_BAR_HEIGHT
            val barMidY = DIM_BAR_Y + DIM_BAR_HEIGHT * 0.5f

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(DIM_COLUMN_X - 2f, DIM_BAR_Y - 2f, barRight + 2f, barBottom + 2f),
                ) { pose ->
                    val track = buildContour(DIM_COLUMN_X, DIM_BAR_Y, barRight, barBottom, DIM_BAR_RADIUS, 0f)
                    val trackFeather =
                        buildContour(DIM_COLUMN_X, DIM_BAR_Y, barRight, barBottom, DIM_BAR_RADIUS, feather)
                    emitFill(track, DIM_COLUMN_X + DIM_BAR_WIDTH * 0.5f, barMidY) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(trackColor)
                    }
                    emitRing(
                        track, trackFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor and 0xFFFFFF) },
                    )

                    // Health: the accent gradient sampled across the whole track, not the fill
                    dimasikBarFill(pose, healthWidth, feather) { vx ->
                        accentAt(((vx - DIM_COLUMN_X) / DIM_BAR_WIDTH).coerceIn(0f, 1f), alphaF)
                    }

                    // Absorption is drawn over the health, exactly as the original stacks them
                    dimasikBarFill(pose, absorptionWidth, feather) { vx ->
                        val fraction = ((vx - DIM_COLUMN_X) / DIM_BAR_WIDTH).coerceIn(0f, 1f)
                        scaleAlpha(lerpArgb(dimAbsorptionFrom, dimAbsorptionTo, fraction), alphaF)
                    }
                }
            )
        }

        /** One rounded bar fill growing from the track's left edge, with an AA edge. */
        private fun VertexConsumer.dimasikBarFill(
            pose: Matrix3x2f,
            width: Float,
            feather: Float,
            colorAt: (Float) -> Int,
        ) {
            if (width <= 0.5f) {
                return
            }
            val r = DIM_BAR_RADIUS.coerceAtMost(width * 0.5f)
            val barBottom = DIM_BAR_Y + DIM_BAR_HEIGHT
            val fill = buildContour(DIM_COLUMN_X, DIM_BAR_Y, DIM_COLUMN_X + width, barBottom, r, 0f)
            val fillFeather = buildContour(DIM_COLUMN_X, DIM_BAR_Y, DIM_COLUMN_X + width, barBottom, r, feather * 0.7f)
            emitFill(fill, DIM_COLUMN_X + width * 0.5f, DIM_BAR_Y + DIM_BAR_HEIGHT * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setColor(colorAt(vx))
            }
            emitRing(
                fill, fillFeather,
                { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorAt(vx)) },
                { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorAt(vx) and 0xFFFFFF) },
            )
        }

        /**
         * A [thickness] wide rounded-rect outline drawn inside the given rectangle, feathered on
         * both sides so it stays a clean hairline at any scale.
         */
        @Suppress("LongParameterList")
        private fun submitRoundedOutline(
            ctx: GuiGraphicsExtractor,
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            r: Float,
            thickness: Float,
            color: Int,
            density: Float,
        ) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val outer = buildContour(x0, y0, x1, y1, r, 0f)
            val inner = buildContour(x0, y0, x1, y1, r, -thickness)
            val outerFeather = buildContour(x0, y0, x1, y1, r, feather)
            val innerFeather = buildContour(x0, y0, x1, y1, r, -thickness - feather)
            val fade = color and 0xFFFFFF

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x0 - 2f, y0 - 2f, x1 + 2f, y1 + 2f),
                ) { pose ->
                    emitRing(
                        inner, outer,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(color) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(color) },
                    )
                    emitRing(
                        outer, outerFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(color) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(fade) },
                    )
                    emitRing(
                        innerFeather, inner,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(fade) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(color) },
                    )
                }
            )
        }

        /**
         * Icon size in device pixels for a row that nominally wants [nominalDevice] of them: the
         * asked-for size, rounded to a whole device pixel so the icon still lands on the pixel grid.
         *
         * Nothing is quantized beyond that — scaling the cards is continuous, and the icon simply
         * follows. Sizes that happen to be a whole multiple of the native 16 px get the vanilla
         * sprite path for free (an exact texel copy, glint included); every other size is
         * resampled by [SuperSampledIcons] with a sub-pixel-sharp window, which keeps texel blocks
         * flat and hard-edged instead of interpolating them into mush.
         */
        private fun cardIconDevice(nominalDevice: Float): Int =
            nominalDevice.roundToInt().coerceAtLeast(8)

        /**
         * The equipment row of the native-pixel cards. Icons are drawn at an exact device-pixel
         * size with the origin snapped to the device grid: whole multiples of the native 16 px go
         * through the vanilla sprite path, every other size is supersampled from the 64 px
         * [ItemImageAtlas] bake — the vanilla path only stays clean at whole factors, which is
         * what made scaled icons look chewed up. Badges are ours as well, so the durability bar
         * and the stack count stay sharp instead of being a minified vanilla overlay.
         */
        @Suppress("LongParameterList")
        private fun submitCardItems(
            ctx: GuiGraphicsExtractor,
            items: List<ItemStack>,
            rowGuiX: Float,
            rowGuiY: Float,
            gapGui: Float,
            deviceSize: Int,
            alphaF: Float,
        ) {
            if (items.isEmpty() || deviceSize <= 0) {
                return
            }
            val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val stripSetup = if (deviceSize % 16 != 0) SuperSampledIcons.setupFor(items, deviceSize) else null

            // Whole device-pixel factor for the vanilla sprite path; the strip keeps the exact size
            val wholeFactor = if (deviceSize % 16 == 0) {
                deviceSize / 16f
            } else {
                Math.round(deviceSize / 16f).coerceAtLeast(1).toFloat()
            }
            val sizeGui = if (stripSetup != null) deviceSize / guiScale else 16f * wholeFactor / guiScale

            // Icons are packed against each other: the row is laid out from the icon size, not from
            // a cell grid, so nothing is padded out with slack the icons could have used instead
            val stepGui = sizeGui + gapGui
            val originsX = FloatArray(items.size) { index ->
                Math.round((rowGuiX + index * stepGui) * guiScale) / guiScale
            }
            val originY = Math.round(rowGuiY * guiScale) / guiScale

            if (stripSetup != null) {
                submitIconStrip(ctx, items, originsX, originY, sizeGui, stripSetup, alphaF)
            } else {
                submitIconSprites(ctx, items, originsX, originY, wholeFactor / guiScale)
            }

            for ((index, stack) in items.withIndex()) {
                if (stack.isEmpty) {
                    continue
                }
                submitItemBadges(
                    ctx = ctx,
                    stack = stack,
                    x = originsX[index],
                    y = originY,
                    size = sizeGui,
                    guiScale = guiScale,
                    alphaF = alphaF,
                )
            }
        }

        /** The row as one textured quad per icon, sampled 1:1 from the supersampled strip. */
        @Suppress("LongParameterList")
        private fun submitIconStrip(
            ctx: GuiGraphicsExtractor,
            items: List<ItemStack>,
            originsX: FloatArray,
            originY: Float,
            sizeGui: Float,
            setup: TextureSetup,
            alphaF: Float,
        ) {
            val tint = scaleAlpha(-1, alphaF)
            val slots = items.size

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI_TEXTURED,
                    setup,
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(
                        originsX.first(), originY,
                        originsX.last() + sizeGui, originY + sizeGui,
                    ),
                ) { pose ->
                    for ((index, stack) in items.withIndex()) {
                        if (stack.isEmpty) {
                            continue
                        }
                        val gx = originsX[index]
                        val u0 = index.toFloat() / slots
                        val u1 = (index + 1).toFloat() / slots
                        addVertexWith2DPose(pose, gx, originY).setUv(u0, 0f).setColor(tint)
                        addVertexWith2DPose(pose, gx, originY + sizeGui).setUv(u0, 1f).setColor(tint)
                        addVertexWith2DPose(pose, gx + sizeGui, originY + sizeGui).setUv(u1, 1f).setColor(tint)
                        addVertexWith2DPose(pose, gx + sizeGui, originY).setUv(u1, 0f).setColor(tint)
                    }
                }
            )
        }

        /** The row through the vanilla sprite path, at a whole device-pixel factor. */
        private fun submitIconSprites(
            ctx: GuiGraphicsExtractor,
            items: List<ItemStack>,
            originsX: FloatArray,
            originY: Float,
            itemGuiScale: Float,
        ) {
            for ((index, stack) in items.withIndex()) {
                if (stack.isEmpty) {
                    continue
                }
                ctx.pose().pushMatrix()
                ctx.pose().translate(originsX[index], originY)
                ctx.pose().scale(itemGuiScale, itemGuiScale)
                ctx.item(stack, 0, 0)
                ctx.pose().popMatrix()
            }
        }

        /**
         * Vanilla's item badges rebuilt with our own primitives so they stay sharp at any icon
         * size: the durability bar (13x2 at +2,+13 of the 16 px grid) and the stack count,
         * right-aligned with a one-device-pixel drop shadow. Drawn in gui units, straight on top
         * of the icons.
         */
        @Suppress("LongParameterList")
        private fun submitItemBadges(
            ctx: GuiGraphicsExtractor,
            stack: ItemStack,
            x: Float,
            y: Float,
            size: Float,
            guiScale: Float,
            alphaF: Float,
        ) {
            val unit = size / 16f

            if (stack.isBarVisible) {
                val barX = x + 2f * unit
                val barY = y + 13f * unit
                val barWidth = 13f * unit
                val barHeight = 2f * unit
                val fillWidth = stack.barWidth * unit
                val back = scaleAlpha(0xFF000000.toInt(), alphaF)
                val fill = scaleAlpha(0xFF000000.toInt() or stack.barColor, alphaF)

                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(barX, barY, barX + barWidth, barY + barHeight),
                    ) { pose ->
                        addVertexWith2DPose(pose, barX, barY).setColor(back)
                        addVertexWith2DPose(pose, barX, barY + barHeight).setColor(back)
                        addVertexWith2DPose(pose, barX + barWidth, barY + barHeight).setColor(back)
                        addVertexWith2DPose(pose, barX + barWidth, barY).setColor(back)
                        if (fillWidth > 0f) {
                            addVertexWith2DPose(pose, barX, barY).setColor(fill)
                            addVertexWith2DPose(pose, barX, barY + barHeight * 0.5f).setColor(fill)
                            addVertexWith2DPose(pose, barX + fillWidth, barY + barHeight * 0.5f).setColor(fill)
                            addVertexWith2DPose(pose, barX + fillWidth, barY).setColor(fill)
                        }
                    }
                )
            }

            if (stack.count != 1) {
                val text = stack.count.toString()
                // Vanilla digits are 8 px tall on a 16 px icon; density is gui units → device px
                val px = 8f * unit
                val textWidth = WatermarkFont.BRAND.width(text, px, guiScale)
                val textX = x + 17f * unit - textWidth
                val textY = y + 9f * unit
                val offset = 1f / guiScale
                val shadow = scaleAlpha(0xFF3F3F3F.toInt(), alphaF)
                val white = scaleAlpha(-1, alphaF)
                WatermarkFont.BRAND.submit(ctx, text, textX + offset, textY + offset, px, guiScale) { shadow }
                WatermarkFont.BRAND.submit(ctx, text, textX, textY, px, guiScale) { white }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Ring mode
        // -----------------------------------------------------------------------------------------
        //
        // Same native-pixel space as the Dimasik card, different composition: the health is a ring
        // on the right instead of a bar, the equipment row moves inside the card, and the ring
        // carries the feedback — it glows in its own colour, turns gold while the target wears
        // absorption hearts, and throws a burst of soft, colourful particles that run along the
        // circumference whenever the target takes a hit.

        // The card is built *around* its equipment row: ItemScale picks the icon size, and the
        // head, the ring and the card itself all follow from it. So the icons always fill their
        // cells exactly, the card never carries slack the icons could have used, and turning
        // ItemScale up grows the panel instead of overflowing it.

        private const val RING_SLOTS = 6
        private const val RING_PAD = 6f
        private const val RING_HEAD_GAP = 8f
        private const val RING_NAME_Y = 8f
        private const val RING_ITEM_GAP = 1f

        /** Breathing room the row leaves before the ring. */
        private const val RING_ITEM_RESERVE = 3f

        /** Icon size the row settles on at ItemScale 1, and the ceiling it may not pass. */
        private const val RING_ICON_BASE = 20f
        private const val RING_ICON_MAX = 48f

        /** Card height: this much furniture (paddings plus the name block) on top of the row. */
        private const val RING_HEIGHT_OVERHEAD = 32f
        private const val RING_MIN_HEIGHT = 52f

        /** The ring the rest of the ornament is authored against. */
        private const val RING_REFERENCE_OUTER = 20f

        private const val RING_SEGMENTS = 64
        private const val RING_GLOW_LAYERS = 3

        /** Over how many degrees the glow fades out at each end, so the ends read as round caps. */
        private const val RING_CAP_DEG = 16f

        /** Twelve o'clock: the arc starts there and grows clockwise. */
        private const val RING_START_DEG = -90f

        private const val RING_BURST = 14
        private const val RING_PARTICLE_CAP = 64

        /** The ring's proportions, all tied to its radius so nothing drifts apart when it grows. */
        private class RingGeometry(val cx: Float, val cy: Float, val outer: Float) {
            val stroke: Float get() = outer * 0.25f
            val inner: Float get() = outer - stroke
            val mid: Float get() = outer - stroke * 0.5f
            val glow: Float get() = outer * 0.125f
            val numberPx: Float get() = outer * 0.6f

            /** Particle sizes and drifts are authored against [RING_REFERENCE_OUTER]. */
            val unit: Float get() = outer / RING_REFERENCE_OUTER
        }

        /** One soft particle running along the ring after a hit. */
        private class RingParticle(
            var angle: Float,
            var speed: Float,
            var radial: Float,
            val drift: Float,
            val size: Float,
            val color: Int,
            val maxLife: Float,
            var life: Float,
        )

        private val ringParticles = ArrayList<RingParticle>()
        private var lastHurtTime = 0

        @Suppress("detekt:LongMethod")
        private fun renderRing(event: OverlayRenderEvent, info: TargetInfo, dt: Float) {
            val ctx = event.context
            val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)

            // The row first: ItemScale sets the icon size, and the rest of the card is laid out
            // around whatever that turns out to be
            val items = info.dimasikItems.take(RING_SLOTS)
            val wanted = (RING_ICON_BASE * itemScale).coerceIn(8f, RING_ICON_MAX)
            val iconDevice = cardIconDevice(wanted * scale)
            val iconGui = iconDevice / guiScale
            val icon = iconDevice / scale.coerceAtLeast(0.01f)

            // The row is always sized for a full set, so the card and the ring stay put while the
            // target swaps gear instead of the whole panel breathing on every hotbar change
            val rowWidth = RING_SLOTS * icon + (RING_SLOTS - 1) * RING_ITEM_GAP
            val height = maxOf(RING_MIN_HEIGHT, RING_HEIGHT_OVERHEAD + icon)
            val head = height - RING_PAD * 2f
            val columnX = RING_PAD + head + RING_HEAD_GAP
            val width = columnX + rowWidth + RING_ITEM_RESERVE + head + RING_PAD
            val ring = RingGeometry(width - RING_PAD - head * 0.5f, height * 0.5f, head * 0.5f)
            val namePx = height * 0.23f

            val frame = nativeCardFrame(width, height)
            val density = frame.density
            val alphaF = frame.alphaF

            ringPct += (info.pct - ringPct) * followStep(dt)

            // Any fresh damage (ours or not) sends a burst off the head of the arc
            if (info.hurtTime > lastHurtTime) {
                spawnRingBurst(RING_START_DEG + 360f * ringPct)
            }
            lastHurtTime = info.hurtTime

            pushCardPose(ctx, frame)

            submitDimasikCard(ctx, width, height, density, alphaF)

            val hurt = (info.hurtTime / DIM_HURT_TICKS).coerceIn(0f, 1f)
            submitHead(
                ctx = ctx,
                skin = info.skin,
                x = RING_PAD,
                y = RING_PAD,
                size = head,
                density = density,
                alphaF = alphaF,
                cornerRadius = head * 0.175f,
                hat = false,
                tintColor = lerpArgb(0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), hurt),
            )
            submitRoundedOutline(
                ctx = ctx,
                x0 = RING_PAD,
                y0 = RING_PAD,
                x1 = RING_PAD + head,
                y1 = RING_PAD + head,
                r = head * 0.175f,
                thickness = DIM_HEAD_RING,
                color = accentAt(0.5f, alphaF),
                density = density,
            )

            WatermarkFont.BRAND.submit(ctx, info.name, columnX, RING_NAME_Y, namePx, density) {
                scaleAlpha(-1, alphaF)
            }

            // Golden hearts read straight off the ring: it goes gold while absorption is up
            val ringColor = if (info.absorption > 0f) dimAbsorptionFrom else healthGrade(ringPct)
            submitHealthRing(ctx, ring, ringPct, ringColor, density, alphaF)
            submitRingParticles(ctx, ring, density, alphaF, dt)

            // The value lives inside the ring, optically centred on the digits
            val text = ceil(info.health).toInt().toString()
            val textWidth = WatermarkFont.BRAND.width(text, ring.numberPx, density)
            val textTop = ring.cy + ring.numberPx * 0.36f - WatermarkFont.BRAND.ascent(ring.numberPx, density)
            val textColor = scaleAlpha(-1, alphaF)
            WatermarkFont.BRAND.submit(
                ctx, text,
                ring.cx - textWidth * 0.5f, textTop,
                ring.numberPx, density,
            ) { textColor }

            ctx.pose().popMatrix()

            // Equipment row inside the card, under the name, packed against each other
            submitCardItems(
                ctx = ctx,
                items = items,
                rowGuiX = frame.guiX(columnX),
                rowGuiY = frame.guiY(height - RING_PAD) - iconGui,
                gapGui = RING_ITEM_GAP * frame.esa,
                deviceSize = iconDevice,
                alphaF = alphaF,
            )
        }

        /** Red at nothing, gold mid-way, green at full — the classic health grade. */
        private fun healthGrade(pct: Float): Int {
            val low = 0xFFE05555.toInt()
            val mid = 0xFFF2C55C.toInt()
            val high = 0xFF7FCB8F.toInt()
            return if (pct < 0.5f) {
                lerpArgb(low, mid, pct * 2f)
            } else {
                lerpArgb(mid, high, (pct - 0.5f) * 2f)
            }
        }

        /**
         * The ring: a dark track, the soft glow bloom that makes it read as lit, the arc itself
         * from twelve o'clock clockwise, and a glowing head at its tip. Arc-tessellated with the
         * same one-device-pixel AA feathering the panels use.
         */
        @Suppress("detekt:LongMethod", "LongParameterList")
        private fun submitHealthRing(
            ctx: GuiGraphicsExtractor,
            ring: RingGeometry,
            pct: Float,
            color: Int,
            density: Float,
            alphaF: Float,
        ) {
            val cx = ring.cx
            val cy = ring.cy
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val outer = ring.outer
            val inner = ring.inner
            val fraction = pct.coerceIn(0f, 1f)
            val sweep = 360f * fraction
            val segments = Math.round(RING_SEGMENTS * fraction).coerceIn(2, RING_SEGMENTS)
            val trackColor = scaleAlpha(0xFF000000.toInt(), 0.45f * alphaF)
            val arcColor = scaleAlpha(color, alphaF)
            val margin = ring.glow + 4f
            val mid = ring.mid
            val cap = ring.stroke * 0.5f
            // The glow dies out over the last degrees at both ends: a flat cut across the whole
            // bloom width is what made the arc end look like a rectangle sticking out of the ring
            val taper = if (sweep <= 0f) 1f else (RING_CAP_DEG / sweep).coerceIn(0.03f, 0.5f)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(
                        cx - outer - margin, cy - outer - margin,
                        cx + outer + margin, cy + outer + margin,
                    ),
                ) { pose ->
                    // Track: the full circle the arc runs on
                    val trackInner = arcPoints(cx, cy, inner, 360f, 0f, RING_SEGMENTS)
                    val trackOuter = arcPoints(cx, cy, outer, 360f, 0f, RING_SEGMENTS)
                    emitStrip(
                        trackInner, trackOuter,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor) },
                    )
                    emitStrip(
                        arcPoints(cx, cy, inner - feather, 360f, 0f, RING_SEGMENTS), trackInner,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor and 0xFFFFFF) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor) },
                    )
                    emitStrip(
                        trackOuter, arcPoints(cx, cy, outer + feather, 360f, 0f, RING_SEGMENTS),
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(trackColor and 0xFFFFFF) },
                    )

                    if (sweep < 1f) {
                        return@WatermarkGuiElement
                    }

                    val from = RING_START_DEG + sweep
                    val to = RING_START_DEG

                    // Glow bloom: soft layers on both sides of the stroke, quadratic falloff
                    // across the stroke and a smooth taper to nothing along the arc's two ends
                    for (layer in 0 until RING_GLOW_LAYERS) {
                        val d0 = ring.glow * layer / RING_GLOW_LAYERS
                        val d1 = ring.glow * (layer + 1) / RING_GLOW_LAYERS
                        val a0 = ringGlowAlpha(d0, ring.glow) * alphaF
                        val a1 = ringGlowAlpha(d1, ring.glow) * alphaF
                        emitStripAlong(
                            arcPoints(cx, cy, outer + d0, from, to, segments),
                            arcPoints(cx, cy, outer + d1, from, to, segments),
                            { vx, vy, t ->
                                addVertexWith2DPose(pose, vx, vy).setColor(scaleAlpha(color, a0 * ringTaper(t, taper)))
                            },
                            { vx, vy, t ->
                                addVertexWith2DPose(pose, vx, vy).setColor(scaleAlpha(color, a1 * ringTaper(t, taper)))
                            },
                        )
                        emitStripAlong(
                            arcPoints(cx, cy, inner - d1, from, to, segments),
                            arcPoints(cx, cy, inner - d0, from, to, segments),
                            { vx, vy, t ->
                                addVertexWith2DPose(pose, vx, vy).setColor(scaleAlpha(color, a1 * ringTaper(t, taper)))
                            },
                            { vx, vy, t ->
                                addVertexWith2DPose(pose, vx, vy).setColor(scaleAlpha(color, a0 * ringTaper(t, taper)))
                            },
                        )
                    }

                    // The arc itself
                    val arcInner = arcPoints(cx, cy, inner, from, to, segments)
                    val arcOuter = arcPoints(cx, cy, outer, from, to, segments)
                    emitStrip(
                        arcInner, arcOuter,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(arcColor) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(arcColor) },
                    )
                    emitStrip(
                        arcPoints(cx, cy, inner - feather, from, to, segments), arcInner,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(arcColor and 0xFFFFFF) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(arcColor) },
                    )
                    emitStrip(
                        arcOuter, arcPoints(cx, cy, outer + feather, from, to, segments),
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(arcColor) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(arcColor and 0xFFFFFF) },
                    )

                    // Round caps close both ends of the band, so neither end is a flat cut
                    val tip = Math.toRadians(from.toDouble())
                    val start = Math.toRadians(to.toDouble())
                    val tipX = cx + mid * cos(tip).toFloat()
                    val tipY = cy + mid * sin(tip).toFloat()
                    val startX = cx + mid * cos(start).toFloat()
                    val startY = cy + mid * sin(start).toFloat()
                    emitDisc(pose, startX, startY, cap, feather, arcColor)
                    emitDisc(pose, tipX, tipY, cap, feather, arcColor)

                    // Lit head at the tip, and a fainter one where the arc starts
                    radialGlow(pose, tipX, tipY, ring.stroke * 1.4f, scaleAlpha(color, 0.26f * alphaF), 14)
                    radialGlow(pose, startX, startY, ring.stroke, scaleAlpha(color, 0.14f * alphaF), 12)
                }
            )
        }

        private fun ringGlowAlpha(distance: Float, reach: Float): Float {
            val falloff = 1f - (distance / reach.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            return 0.24f * falloff * falloff
        }

        /** Angular taper along the arc: 0 at both ends, 1 in the middle, smoothed. */
        private fun ringTaper(position: Float, capFraction: Float): Float {
            val edge = (minOf(position, 1f - position) / capFraction.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            return edge * edge * (3f - 2f * edge)
        }

        /** A hit throws colourful, soft-edged particles off the head of the arc. */
        private fun spawnRingBurst(tipAngle: Float) {
            repeat(RING_BURST) {
                if (ringParticles.size >= RING_PARTICLE_CAP) {
                    return
                }
                val life = 0.55f + Random.nextFloat() * 0.65f
                ringParticles += RingParticle(
                    angle = tipAngle + (Random.nextFloat() - 0.5f) * 44f,
                    speed = (90f + Random.nextFloat() * 260f) * (if (Random.nextFloat() < 0.22f) -1f else 1f),
                    radial = (Random.nextFloat() - 0.5f) * 4f,
                    drift = (Random.nextFloat() - 0.35f) * 9f,
                    size = 1.5f + Random.nextFloat() * 2.3f,
                    color = Color4b.ofHSB(Random.nextFloat(), 0.45f + Random.nextFloat() * 0.3f, 1f).argb,
                    maxLife = life,
                    life = life,
                )
            }
        }

        /**
         * Advances and draws the burst: every particle is a bright core inside a wide soft halo
         * that blooms as it fades, so the trail reads as fur rather than as dots.
         */
        @Suppress("LongParameterList")
        private fun submitRingParticles(
            ctx: GuiGraphicsExtractor,
            ring: RingGeometry,
            density: Float,
            alphaF: Float,
            dt: Float,
        ) {
            if (ringParticles.isEmpty()) {
                return
            }
            val iterator = ringParticles.iterator()
            while (iterator.hasNext()) {
                val particle = iterator.next()
                particle.life -= dt
                if (particle.life <= 0f) {
                    iterator.remove()
                    continue
                }
                particle.angle += particle.speed * dt
                particle.speed *= (1f - dt * 1.6f).coerceIn(0f, 1f)
                particle.radial += particle.drift * dt
            }
            if (ringParticles.isEmpty()) {
                return
            }

            val cx = ring.cx
            val cy = ring.cy
            val unit = ring.unit
            val margin = ring.outer + 14f * unit
            // Halos are wide and soft; one device pixel is the smallest they may get
            val minSize = (1f / density.coerceAtLeast(0.01f)).coerceAtLeast(0.3f)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(cx - margin, cy - margin, cx + margin, cy + margin),
                ) { pose ->
                    for (particle in ringParticles) {
                        val t = (particle.life / particle.maxLife).coerceIn(0f, 1f)
                        val fade = t * t
                        val radius = ring.mid + particle.radial * unit
                        val angle = Math.toRadians(particle.angle.toDouble())
                        val px = cx + radius * cos(angle).toFloat()
                        val py = cy + radius * sin(angle).toFloat()
                        val size = particle.size * unit
                        val halo = scaleAlpha(particle.color, 0.26f * fade * alphaF)
                        val core = scaleAlpha(particle.color, 0.85f * fade * alphaF)
                        radialGlow(pose, px, py, (size * (2f - t)).coerceAtLeast(minSize), halo, 10)
                        radialGlow(pose, px, py, (size * 0.42f).coerceAtLeast(minSize), core, 8)
                    }
                }
            )
        }
    }

    // =============================================================================================
    // Effects element
    // =============================================================================================

    private object EffectsHud : ToggleableValueGroup(this, "Effects", true) {

        private const val PAD_X = 6f
        private const val PAD_Y = 5.5f
        private const val ROW_GAP = 3f
        private const val ROW_PAD_X = 4.5f
        private const val ROW_PAD_Y = 2.5f
        private const val ROW_RADIUS = 2.5f
        private const val APPEAR_ROW_MS = 220f
        private const val SIZE_SMOOTH_SPEED = 12f
        private val ROMAN = arrayOf("", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

        private val corner by enumChoice("Corner", Corner.TOP_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 6f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val drainFill by boolean("DrainFill", true)

        val bounds = PanelBounds()

        // Highest seen duration per effect — reference for the draining row fill
        private val maxDurations = HashMap<String, Int>()

        // Per-row appear animation starts + smooth panel sizing (hawen-style)
        private val appearStart = HashMap<String, Long>()
        private var smoothWidth = -1f
        private var smoothHeight = -1f
        private var lastSizeNs = 0L
        private var shownSince = 0L
        private var wasVisible = false

        private class Row(
            val key: String,
            val sprite: Identifier?,
            val title: String,
            val time: String,
            /** Remaining fraction 0..1, sub-tick smooth. */
            val fraction: Float,
            val expiring: Boolean,
            /** Row appear progress 0..1 (easeOutCubic). */
            val appear: Float,
        )

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val effects = player.activeEffects.toList()

            // Visible with real effects; while a screen is open an empty placeholder row is
            // shown so the panel can be dragged around
            if (effects.isEmpty() && !editMode()) {
                wasVisible = false
                maxDurations.clear()
                appearStart.clear()
                return@handler
            }
            if (!wasVisible) {
                shownSince = System.currentTimeMillis()
                wasVisible = true
            }

            val rowPx = fontSize * 0.92f
            val density = density(scale)
            val rows = buildRows(effects, event.tickDelta)

            // Layout
            val textHeight = WatermarkFont.TEXT.lineHeight(rowPx, density)
            val iconSize = Math.round(textHeight + 2f)
            val textBlockHeight = maxOf(textHeight, iconSize.toFloat())
            val rowHeight = textBlockHeight + ROW_PAD_Y * 2f

            var innerWidth = 76f
            val titleWidths = FloatArray(rows.size)
            val timeWidths = FloatArray(rows.size)
            for ((index, row) in rows.withIndex()) {
                titleWidths[index] = WatermarkFont.TEXT.width(row.title, rowPx, density)
                timeWidths[index] = WatermarkFont.TEXT.width(row.time, rowPx, density)
                // Layout reserves the time column by a digits-stable sample ("1:23" → "0:00"),
                // so the ticking timer doesn't make the panel breathe in width
                val timeSample = String(CharArray(row.time.length) { i -> if (row.time[i].isDigit()) '0' else row.time[i] })
                val timeLayoutWidth = WatermarkFont.TEXT.width(timeSample, rowPx, density)
                innerWidth = maxOf(
                    innerWidth,
                    ROW_PAD_X + iconSize + 4f + titleWidths[index] + 12f + timeLayoutWidth + ROW_PAD_X,
                )
            }
            // Quantize the target to half-pixel steps to kill micro-noise
            innerWidth = Math.round(innerWidth * 2f) / 2f

            // Rows contribute height by their appear progress — the panel grows smoothly
            var rowsHeight = 0f
            for (row in rows) {
                rowsHeight += (rowHeight + ROW_GAP) * row.appear
            }
            rowsHeight = (rowsHeight - ROW_GAP).coerceAtLeast(rowHeight)

            // FPS-independent smoothing of the panel size (hawen-style exp approach)
            val targetWidth = PAD_X * 2f + innerWidth
            val targetHeight = PAD_Y * 2f + rowsHeight
            val nowNs = System.nanoTime()
            val dt = if (lastSizeNs == 0L) {
                1f / 60f
            } else {
                ((nowNs - lastSizeNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastSizeNs = nowNs
            if (smoothWidth < 0f) {
                smoothWidth = targetWidth
                smoothHeight = targetHeight
            }
            val smoothing = 1f - kotlin.math.exp(-SIZE_SMOOTH_SPEED * dt)
            smoothWidth += (targetWidth - smoothWidth) * smoothing
            smoothHeight += (targetHeight - smoothHeight) * smoothing
            // Settle fully once close — no endless asymptotic creep
            if (kotlin.math.abs(targetWidth - smoothWidth) < 0.1f) {
                smoothWidth = targetWidth
            }
            if (kotlin.math.abs(targetHeight - smoothHeight) < 0.1f) {
                smoothHeight = targetHeight
            }
            val width = smoothWidth
            val height = smoothHeight

            // Entrance animation
            val appear = appearProgress(shownSince)
            val alphaF = fadeAlpha(appear)
            val slide = (1f - easeOutBack(appear)) * 18f

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.EFFECTS && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            // Anchor to the selected screen corner
            var x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            x += if (corner.isRight) slide else -slide

            bounds.set(x, y, width * scale, height * scale)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // No shared panel behind the rows — each row is its own little backing

            // Expiring rows pulse with a slow sine wave (hawen-style blink)
            val wave = 0.45f + 0.55f * (0.5f + 0.5f * sin(timeSeconds() * 4.5f))

            var rowY = PAD_Y
            for ((index, row) in rows.withIndex()) {
                val rowAlpha = alphaF * row.appear
                if (rowAlpha <= 0.02f) {
                    rowY += (rowHeight + ROW_GAP) * row.appear
                    continue
                }
                val pulse = if (row.expiring) wave else 1f

                // The row grows vertically together with its slot — appearing rows can never
                // overlap their neighbours (which previously doubled the fill for an instant)
                ctx.pose().pushMatrix()
                ctx.pose().translate(0f, rowY)
                ctx.pose().scale(1f, row.appear)

                // Draining fill: the row background empties together with the remaining time
                submitRowFill(
                    ctx, PAD_X, 0f, innerWidth, rowHeight, density,
                    row.fraction, rowAlpha, pulse, row.expiring,
                )

                val contentY = ROW_PAD_Y
                if (row.sprite != null) {
                    val iconY = contentY + (textBlockHeight - iconSize) * 0.5f
                    ctx.blitSprite(
                        RenderPipelines.GUI_TEXTURED, row.sprite,
                        Math.round(PAD_X + ROW_PAD_X), Math.round(iconY), iconSize, iconSize,
                    )
                }

                val textX = PAD_X + ROW_PAD_X + iconSize + 4f
                val textY = contentY + (textBlockHeight - textHeight) * 0.5f
                val titleArgb = scaleAlpha(-1, rowAlpha)
                WatermarkFont.TEXT.submit(ctx, row.title, textX, textY, rowPx, density) { titleArgb }
                if (row.time.isNotEmpty()) {
                    val timeArgb = if (row.expiring) {
                        scaleAlpha(0xFFE0635F.toInt(), rowAlpha * pulse.coerceAtLeast(0.4f))
                    } else {
                        scaleAlpha(infoColor.argb, rowAlpha)
                    }
                    WatermarkFont.TEXT.submit(
                        ctx, row.time,
                        PAD_X + innerWidth - ROW_PAD_X - timeWidths[index], textY, rowPx, density,
                    ) { timeArgb }
                }

                ctx.pose().popMatrix()

                rowY += (rowHeight + ROW_GAP) * row.appear
            }

            ctx.pose().popMatrix()
        }

        private fun buildRows(effects: List<MobEffectInstance>, tickDelta: Float): List<Row> {
            val now = System.currentTimeMillis()
            if (effects.isEmpty()) {
                return listOf(Row("none", null, txtNoEffects, "", 0f, expiring = false, appear = 1f))
            }

            val sorted = effects.sortedWith(
                compareByDescending<MobEffectInstance> { it.isInfiniteDuration }
                    .thenByDescending { it.duration }
            )
            val activeKeys = HashSet<String>(sorted.size)
            val rows = sorted.map { instance ->
                val sprite = Gui.getMobEffectSprite(instance.effect)
                val key = sprite.toString()
                activeKeys += key

                val maxDuration = maxDurations.merge(key, instance.duration, ::maxOf) ?: instance.duration
                // Sub-tick smooth remaining fraction — the fill drains silkily, not in tick steps
                val fraction = if (instance.isInfiniteDuration) {
                    1f
                } else {
                    ((instance.duration - tickDelta) / maxDuration.coerceAtLeast(1)).coerceIn(0f, 1f)
                }

                val amplifier = instance.amplifier
                val level = when {
                    amplifier in 1..9 -> " ${ROMAN[amplifier]}"
                    amplifier > 9 -> " ${amplifier + 1}"
                    else -> ""
                }
                val title = instance.effect.value().displayName.string + level
                val time = if (instance.isInfiniteDuration) {
                    txtInfinite
                } else {
                    val seconds = instance.duration / 20
                    "%d:%02d".format(seconds / 60, seconds % 60)
                }

                val appearAt = appearStart.getOrPut(key) { now }
                val appearT = ((now - appearAt) / APPEAR_ROW_MS).coerceIn(0f, 1f)
                val rowAppear = 1f - (1f - appearT) * (1f - appearT) * (1f - appearT) // easeOutCubic

                Row(
                    key, sprite, title, time, fraction,
                    expiring = !instance.isInfiniteDuration && instance.duration < 120,
                    appear = rowAppear,
                )
            }
            maxDurations.keys.retainAll(activeKeys)
            appearStart.keys.retainAll(activeKeys)
            return rows
        }

        /**
         * The standalone row backing with the draining fill: a dark rounded base per row, and
         * over it an accent (or pulsing warm, when expiring) fill that empties from right to
         * left together with the remaining duration. Contour-tessellated with AA feathering.
         */
        @Suppress("LongParameterList")
        private fun submitRowFill(
            ctx: GuiGraphicsExtractor,
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            density: Float,
            fraction: Float,
            rowAlpha: Float,
            pulse: Float,
            expiring: Boolean,
        ) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val baseTop = scaleAlpha(backgroundTop.argb, rowAlpha)
            val baseBottom = scaleAlpha(backgroundBottom.argb, rowAlpha)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x - 1f, y - 1f, x + width + 1f, y + height + 1f),
                ) { pose ->
                    // Per-row backing (panel-tinted, with AA feather)
                    val baseAt = { vy: Float -> lerpArgb(baseTop, baseBottom, ((vy - y) / height).coerceIn(0f, 1f)) }
                    val base = buildContour(x, y, x + width, y + height, ROW_RADIUS, 0f)
                    val baseFeather = buildContour(x, y, x + width, y + height, ROW_RADIUS, feather)
                    emitFill(base, x + width * 0.5f, y + height * 0.5f) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(baseAt(vy))
                    }
                    emitRing(
                        base, baseFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(baseAt(vy)) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(baseAt(vy) and 0xFFFFFF) },
                    )

                    // Draining fill
                    if (drainFill) {
                        val fillWidth = width * fraction
                        if (fillWidth > 1f) {
                            val fillAlpha = (if (expiring) 0.34f * pulse else 0.24f) * rowAlpha
                            val fillR = ROW_RADIUS.coerceAtMost(fillWidth * 0.5f)
                            val fill = buildContour(x, y, x + fillWidth, y + height, fillR, 0f)
                            val fillFeather = buildContour(x, y, x + fillWidth, y + height, fillR, feather * 0.7f)
                            val colorOf = { vx: Float ->
                                if (expiring) {
                                    scaleAlpha(0xFFE0635F.toInt(), fillAlpha)
                                } else {
                                    scaleAlpha(accentAt(((vx - x) / width).coerceIn(0f, 1f), 1f), fillAlpha)
                                }
                            }
                            emitFill(fill, x + fillWidth * 0.5f, y + height * 0.5f) { vx, vy ->
                                addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx))
                            }
                            emitRing(
                                fill, fillFeather,
                                { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx)) },
                                { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx) and 0xFFFFFF) },
                            )

                            // Bright leading edge of the drain
                            if (fillWidth < width - 1f) {
                                val edgeColor = if (expiring) {
                                    scaleAlpha(0xFFE0635F.toInt(), 0.8f * pulse * rowAlpha)
                                } else {
                                    scaleAlpha(accentAt(fraction, 1f), 0.7f * rowAlpha)
                                }
                                addVertexWith2DPose(pose, x + fillWidth - 0.7f, y + 0.5f).setColor(edgeColor)
                                addVertexWith2DPose(pose, x + fillWidth - 0.7f, y + height - 0.5f).setColor(edgeColor)
                                addVertexWith2DPose(pose, x + fillWidth, y + height - 0.5f).setColor(edgeColor)
                                addVertexWith2DPose(pose, x + fillWidth, y + 0.5f).setColor(edgeColor)
                            }
                        }
                    }
                }
            )
        }
    }

    // =============================================================================================
    // Notifications element
    // =============================================================================================

    private object NotificationsHud : ToggleableValueGroup(this, "Notifications", true) {

        private const val PAD_X = 8f
        private const val PAD_Y = 5f
        private const val STACK_GAP = 4f
        private const val STRIPE_WIDTH = 2.5f
        private const val IN_MS = 250f
        private const val OUT_MS = 200f

        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 40f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val lifetime by float("Lifetime", 2.5f, 1f..10f, "s")
        private val stackDirection by enumChoice("StackDirection", StackDirection.UP)
        private val maxStack by int("MaxStack", 6, 1..10)
        private val moduleToggles by boolean("ModuleToggles", true)

        @Suppress("unused")
        enum class StackDirection(override val tag: String) : Tagged {
            UP("Up"),
            DOWN("Down"),
        }

        val bounds = PanelBounds()

        private class Toast(
            val title: String,
            val message: String,
            val color: Int,
            val createdAt: Long,
            var expireAt: Long,
        )

        private val toasts = ArrayDeque<Toast>()

        private fun severityColor(severity: NotificationEvent.Severity): Int = when (severity) {
            NotificationEvent.Severity.INFO -> accentTo.argb
            NotificationEvent.Severity.SUCCESS -> 0xFF7FCB8F.toInt()
            NotificationEvent.Severity.ERROR -> 0xFFE0635F.toInt()
            NotificationEvent.Severity.ENABLED -> 0xFF7FCB8F.toInt()
            NotificationEvent.Severity.DISABLED -> 0xFF9AA0B4.toInt()
        }

        private fun push(title: String, message: String, color: Int) {
            val now = System.currentTimeMillis()
            toasts.addLast(Toast(title, message, color, now, now + (lifetime * 1000f).toLong()))
            // Force-expire the oldest when over the stack cap
            while (toasts.count { it.expireAt > now } > maxStack) {
                toasts.firstOrNull { it.expireAt > now }?.expireAt = now
            }
        }

        @Suppress("unused")
        private val notificationHandler = handler<NotificationEvent> { event ->
            // Module toggle notifications ("Enabled — Name") are skipped: the ModuleToggleEvent
            // toast below shows them in the preferred "Name enabled/disabled" format instead
            if (event.severity == NotificationEvent.Severity.ENABLED ||
                event.severity == NotificationEvent.Severity.DISABLED
            ) {
                return@handler
            }
            push(event.title, event.message, severityColor(event.severity))
        }

        @Suppress("unused")
        private val toggleHandler = handler<ModuleToggleEvent> { event ->
            if (!moduleToggles || event.hidden) {
                return@handler
            }
            push(
                event.moduleName,
                if (event.enabled) txtEnabled else txtDisabled,
                if (event.enabled) 0xFF7FCB8F.toInt() else 0xFF9AA0B4.toInt(),
            )
        }

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val now = System.currentTimeMillis()
            toasts.removeIf { now > it.expireAt + OUT_MS.toLong() }

            // While a screen is open an inert sample toast is shown, so the stack can be dragged
            val rendered: List<Toast> = if (toasts.isEmpty()) {
                if (!editMode()) {
                    return@handler
                }
                listOf(Toast(txtNotifications, txtDragMe, accentTo.argb, now - IN_MS.toLong(), Long.MAX_VALUE))
            } else {
                toasts
            }

            val ctx = event.context
            val px = fontSize * 0.92f
            val density = density(scale)
            val textHeight = WatermarkFont.BRAND.lineHeight(px, density)
            val toastHeight = textHeight + PAD_Y * 2f

            // Per-toast lifecycle + sizes
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            var maxWidth = 60f
            val widths = FloatArray(rendered.size)
            val lifecycles = FloatArray(rendered.size) // 0..1 visibility
            val pops = FloatArray(rendered.size)
            var stackHeight = 0f
            for ((index, toast) in rendered.withIndex()) {
                val inT = ((now - toast.createdAt) / IN_MS).coerceIn(0f, 1f)
                val outT = if (toast.expireAt == Long.MAX_VALUE) {
                    0f
                } else {
                    ((now - toast.expireAt) / OUT_MS).coerceIn(0f, 1f)
                }
                lifecycles[index] = fadeAlpha(inT) * (1f - outT)
                pops[index] = easeOutBack(inT) * (1f - 0.1f * outT)

                val titleWidth = WatermarkFont.BRAND.width(toast.title, px, density)
                val messageWidth = WatermarkFont.TEXT.width(toast.message, px * 0.92f, density)
                widths[index] = PAD_X + STRIPE_WIDTH + 6f + titleWidth +
                    (if (toast.message.isNotEmpty()) 6f + messageWidth else 0f) + PAD_X
                maxWidth = maxOf(maxWidth, widths[index])
                stackHeight += (toastHeight + STACK_GAP) * lifecycles[index]
            }
            stackHeight = (stackHeight - STACK_GAP).coerceAtLeast(toastHeight)

            // Dragging with the mouse while a screen is open. The offset tracks the FIXED edge
            // of the stack — the one toasts emanate from (bottom for UP, top for DOWN).
            if (dragging == DragTarget.NOTIFICATIONS && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                val newEdgeY = if (stackDirection == StackDirection.UP) newY + stackHeight * scale else newY
                offsetX = (if (corner.isRight) screenWidth - newX - maxWidth * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newEdgeY else newEdgeY).coerceIn(0f, 1000f)
            }

            // Stack anchor: the fixed edge stays put, the region extends in the stack direction
            val anchorX = if (corner.isRight) screenWidth - offsetX - maxWidth * scale else offsetX
            val anchorEdgeY = if (corner.isBottom) screenHeight - offsetY else offsetY
            val anchorY = if (stackDirection == StackDirection.UP) {
                anchorEdgeY - stackHeight * scale
            } else {
                anchorEdgeY
            }
            bounds.set(anchorX, anchorY, maxWidth * scale, stackHeight * scale)

            val glassSetup = requestGlass(ctx, event)

            // Newest toast sits at the stack's anchored end; the rest are pushed away
            // in the configured direction (UP = stack grows upward, DOWN = downward)
            var stackOffset = 0f
            val ordered = rendered.asReversed()
            for (toast in ordered) {
                val index = rendered.indexOf(toast)
                val life = lifecycles[index]
                if (life <= 0.02f) {
                    continue
                }
                val pop = pops[index]
                val width = widths[index]

                // Slide in from the anchored side + scale pop around the toast center
                val slide = (1f - easeOutBack(((now - toast.createdAt) / IN_MS).coerceIn(0f, 1f))) * 24f
                val toastGuiY = if (stackDirection == StackDirection.UP) {
                    anchorY + stackHeight * scale - stackOffset - toastHeight * scale * life
                } else {
                    anchorY + stackOffset
                }
                var toastGuiX = if (corner.isRight) {
                    anchorX + (maxWidth - width) * scale + slide * scale
                } else {
                    anchorX - slide * scale
                }

                ctx.pose().pushMatrix()
                ctx.pose().translate(toastGuiX, toastGuiY)
                ctx.pose().scale(scale, scale)
                // Pop around the toast center
                ctx.pose().translate(width * 0.5f, toastHeight * 0.5f)
                ctx.pose().scale(pop, pop)
                ctx.pose().translate(-width * 0.5f, -toastHeight * 0.5f)

                drawGlassPanel(ctx, width, toastHeight, life, density, glassSetup)

                // Severity stripe at the left edge
                submitStripe(ctx, PAD_X * 0.5f, PAD_Y * 0.8f, toastHeight - PAD_Y * 1.6f, toast.color, life, density)

                // Title (bold, white) + message (dimmed)
                val textX = PAD_X + STRIPE_WIDTH + 6f - PAD_X * 0.5f + PAD_X * 0.5f
                val titleArgb = scaleAlpha(-1, life)
                WatermarkFont.BRAND.submit(ctx, toast.title, textX, PAD_Y, px, density) { titleArgb }
                if (toast.message.isNotEmpty()) {
                    val titleWidth = WatermarkFont.BRAND.width(toast.title, px, density)
                    val msgArgb = scaleAlpha(infoColor.argb, life)
                    val msgPx = px * 0.92f
                    val msgY = PAD_Y + WatermarkFont.BRAND.ascent(px, density) -
                        WatermarkFont.TEXT.ascent(msgPx, density)
                    WatermarkFont.TEXT.submit(ctx, toast.message, textX + titleWidth + 6f, msgY, msgPx, density) {
                        msgArgb
                    }
                }

                // Remaining-lifetime drain line along the bottom
                if (toast.expireAt != Long.MAX_VALUE) {
                    val total = (toast.expireAt - toast.createdAt).coerceAtLeast(1)
                    val remaining = ((toast.expireAt - now).toFloat() / total).coerceIn(0f, 1f)
                    submitLifeLine(ctx, width, toastHeight, remaining, toast.color, life)
                }

                ctx.pose().popMatrix()

                stackOffset += (toastHeight + STACK_GAP) * scale * life
            }
        }

        /** Rounded severity stripe at the toast's left edge. */
        @Suppress("LongParameterList")
        private fun submitStripe(
            ctx: GuiGraphicsExtractor,
            x: Float,
            y: Float,
            height: Float,
            color: Int,
            alphaF: Float,
            density: Float,
        ) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val argb = scaleAlpha(color, alphaF)
            val contour = buildContour(x, y, x + STRIPE_WIDTH, y + height, STRIPE_WIDTH * 0.5f, 0f)
            val contourFeather = buildContour(x, y, x + STRIPE_WIDTH, y + height, STRIPE_WIDTH * 0.5f, feather * 0.7f)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x - 1f, y - 1f, x + STRIPE_WIDTH + 1f, y + height + 1f),
                ) { pose ->
                    emitFill(contour, x + STRIPE_WIDTH * 0.5f, y + height * 0.5f) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(argb)
                    }
                    emitRing(
                        contour, contourFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb and 0xFFFFFF) },
                    )
                }
            )
        }

        /** Thin severity-colored line along the toast bottom, draining with the lifetime. */
        @Suppress("LongParameterList")
        private fun submitLifeLine(
            ctx: GuiGraphicsExtractor,
            width: Float,
            height: Float,
            remaining: Float,
            color: Int,
            alphaF: Float,
        ) {
            val lineWidth = (width - radius * 2f) * remaining
            if (lineWidth < 1f) {
                return
            }
            val x0 = radius
            val y0 = height - 1.4f
            val argb = scaleAlpha(color, 0.65f * alphaF)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(x0, y0, x0 + lineWidth, y0 + 0.8f),
                ) { pose ->
                    addVertexWith2DPose(pose, x0, y0).setColor(argb)
                    addVertexWith2DPose(pose, x0, y0 + 0.8f).setColor(argb)
                    addVertexWith2DPose(pose, x0 + lineWidth, y0 + 0.8f).setColor(argb)
                    addVertexWith2DPose(pose, x0 + lineWidth, y0).setColor(argb)
                }
            )
        }
    }

    // =============================================================================================
    // ArrayList element
    // =============================================================================================

    private object ArrayListHud : ToggleableValueGroup(this, "ArrayList", true) {

        private const val ROW_PAD_X = 4.5f
        private const val ROW_PAD_Y = 2f
        private const val ROW_GAP = 1.5f
        private const val ROW_RADIUS = 2f
        private const val STRIPE_WIDTH = 2f
        private const val IN_MS = 220f
        private const val OUT_MS = 180f
        private const val GLIDE_SPEED = 14f

        private val corner by enumChoice("Corner", Corner.TOP_RIGHT)
        private var offsetX by float("OffsetX", 2f, 0f..1000f)
        private var offsetY by float("OffsetY", 80f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val showTags by boolean("Tags", true)
        private val rowBackground by boolean("Background", true)
        private val accentStripe by boolean("AccentStripe", true)

        val bounds = PanelBounds()

        /** Per-module lifecycle + smooth vertical glide position. */
        private class Entry(val name: String) {
            var tag: String? = null
            var appearAt = System.currentTimeMillis()
            var removeAt = 0L
            var smoothY = Float.NaN
        }

        private val entries = LinkedHashMap<String, Entry>()
        private var lastFrameNs = 0L

        private class Measured(
            val entry: Entry,
            val tagText: String?,
            val nameWidth: Float,
            val tagWidth: Float,
            val stripe: Boolean,
        ) {
            val rowWidth: Float
                get() = ROW_PAD_X + nameWidth +
                    (if (tagWidth > 0f) 4f + tagWidth else 0f) + ROW_PAD_X +
                    (if (stripe) STRIPE_WIDTH + 2f else 0f)
        }

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val now = System.currentTimeMillis()

            // Sync entries with the currently enabled modules
            val active = HashSet<String>()
            for (module in ModuleManager) {
                if (!module.enabled || module.hidden) {
                    continue
                }
                active += module.name
                val entry = entries.getOrPut(module.name) { Entry(module.name) }
                if (entry.removeAt != 0L) {
                    entry.removeAt = 0L
                    entry.appearAt = now
                }
                entry.tag = module.tag
            }
            for (entry in entries.values) {
                if (entry.name !in active && entry.removeAt == 0L) {
                    entry.removeAt = now
                }
            }
            entries.values.removeIf { it.removeAt != 0L && now > it.removeAt + OUT_MS.toLong() }
            if (entries.isEmpty()) {
                return@handler
            }

            val px = fontSize * 0.92f
            val tagPx = px * 0.85f
            val density = density(scale)
            val textHeight = WatermarkFont.BRAND.lineHeight(px, density)
            val rowHeight = textHeight + ROW_PAD_Y * 2f

            // Measure + sort by row width (classic descending stagger)
            val measured = entries.values.map { entry ->
                val tag = entry.tag.takeIf { showTags && !it.isNullOrEmpty() }
                Measured(
                    entry,
                    tag,
                    WatermarkFont.BRAND.width(entry.name, px, density),
                    if (tag != null) WatermarkFont.TEXT.width(tag, tagPx, density) else 0f,
                    accentStripe,
                )
            }.sortedByDescending { it.rowWidth }

            // Lifecycle factors + total size
            val dt = if (lastFrameNs == 0L) {
                1f / 60f
            } else {
                ((System.nanoTime() - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastFrameNs = System.nanoTime()

            var maxWidth = 0f
            var totalHeight = 0f
            val lives = FloatArray(measured.size)
            for ((index, row) in measured.withIndex()) {
                val inT = ((now - row.entry.appearAt) / IN_MS).coerceIn(0f, 1f)
                val outT = if (row.entry.removeAt == 0L) {
                    0f
                } else {
                    ((now - row.entry.removeAt) / OUT_MS).coerceIn(0f, 1f)
                }
                lives[index] = fadeAlpha(inT) * (1f - outT)
                maxWidth = maxOf(maxWidth, row.rowWidth)
                totalHeight += (rowHeight + ROW_GAP) * lives[index]
            }
            totalHeight = (totalHeight - ROW_GAP).coerceAtLeast(rowHeight)

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.ARRAY_LIST && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - maxWidth * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - totalHeight * scale else newY)
                    .coerceIn(0f, 1000f)
            }

            val anchorX = if (corner.isRight) screenWidth - offsetX - maxWidth * scale else offsetX
            val anchorY = if (corner.isBottom) screenHeight - offsetY - totalHeight * scale else offsetY
            bounds.set(anchorX, anchorY, maxWidth * scale, totalHeight * scale)

            // Rows, gliding smoothly to their sorted positions
            val glide = 1f - kotlin.math.exp(-GLIDE_SPEED * dt)
            val rowRects = ArrayList<FloatArray>(measured.size)
            var targetY = 0f
            for ((index, row) in measured.withIndex()) {
                val life = lives[index]
                if (life <= 0.02f) {
                    targetY += (rowHeight + ROW_GAP) * life
                    continue
                }
                val entry = row.entry

                if (entry.smoothY.isNaN()) {
                    entry.smoothY = targetY
                } else {
                    entry.smoothY += (targetY - entry.smoothY) * glide
                    if (kotlin.math.abs(targetY - entry.smoothY) < 0.05f) {
                        entry.smoothY = targetY
                    }
                }

                // Slide in/out toward the anchored side
                val inT = ((now - entry.appearAt) / IN_MS).coerceIn(0f, 1f)
                val slide = (1f - easeOutBack(inT)) * 16f + (1f - life) * 12f

                val rowGuiY = anchorY + entry.smoothY * scale
                val rowGuiX = if (corner.isRight) {
                    anchorX + (maxWidth - row.rowWidth) * scale + slide * scale
                } else {
                    anchorX - slide * scale
                }

                // Per-row hitbox (the list is staggered — a single box would catch empty space)
                rowRects += floatArrayOf(rowGuiX, rowGuiY, row.rowWidth * scale, rowHeight * scale)

                ctx.pose().pushMatrix()
                ctx.pose().translate(rowGuiX, rowGuiY)
                ctx.pose().scale(scale, scale)

                // Continuous gradient flowing down the list
                val phase = index * 0.12f
                drawRow(ctx, row.rowWidth, rowHeight, textHeight, px, tagPx, density, life, phase, row)

                ctx.pose().popMatrix()

                targetY += (rowHeight + ROW_GAP) * life
            }
            bounds.rects = rowRects
        }

        @Suppress("LongParameterList")
        private fun drawRow(
            ctx: GuiGraphicsExtractor,
            width: Float,
            height: Float,
            textHeight: Float,
            px: Float,
            tagPx: Float,
            density: Float,
            life: Float,
            phase: Float,
            measured: Measured,
        ) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)

            // Row background
            if (rowBackground) {
                val bgColor = scaleAlpha(backgroundBottom.argb, 0.75f * life)
                val contour = buildContour(0f, 0f, width, height, ROW_RADIUS, 0f)
                val contourFeather = buildContour(0f, 0f, width, height, ROW_RADIUS, feather)
                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(-1f, -1f, width + 1f, height + 1f),
                    ) { pose ->
                        emitFill(contour, width * 0.5f, height * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(bgColor)
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(bgColor) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(bgColor and 0xFFFFFF) },
                        )

                        // Accent stripe on the outer edge
                        if (accentStripe) {
                            val stripeX0 = if (corner.isRight) width - STRIPE_WIDTH else 0f
                            val top = accentAt(phase, life)
                            val bottom = accentAt(phase + 0.05f, life)
                            addVertexWith2DPose(pose, stripeX0, 0f).setColor(top)
                            addVertexWith2DPose(pose, stripeX0, height).setColor(bottom)
                            addVertexWith2DPose(pose, stripeX0 + STRIPE_WIDTH, height).setColor(bottom)
                            addVertexWith2DPose(pose, stripeX0 + STRIPE_WIDTH, 0f).setColor(top)
                        }
                    }
                )
            }

            // Name with the flowing accent gradient + dimmed tag
            val textX = ROW_PAD_X + if (accentStripe && !corner.isRight) STRIPE_WIDTH + 2f else 0f
            val textY = ROW_PAD_Y + (height - ROW_PAD_Y * 2f - textHeight) * 0.5f
            WatermarkFont.BRAND.submit(ctx, measured.entry.name, textX, textY, px, density) {
                accentAt(phase + it * 0.1f, life)
            }
            val tag = measured.tagText
            if (tag != null) {
                val tagArgb = scaleAlpha(infoColor.argb, life)
                val tagY = textY + WatermarkFont.BRAND.ascent(px, density) - WatermarkFont.TEXT.ascent(tagPx, density)
                WatermarkFont.TEXT.submit(ctx, tag, textX + measured.nameWidth + 4f, tagY, tagPx, density) {
                    tagArgb
                }
            }
        }
    }

    // =============================================================================================
    // Info element (coordinates / speed / direction / biome)
    // =============================================================================================

    private object InfoHud : ToggleableValueGroup(this, "Info", true) {

        private const val PAD_X = 7f
        private const val PAD_Y = 4.5f
        private const val SECTION_SPACE = 5f
        private const val SEPARATOR_WIDTH = 0.8f
        private const val SIZE_SMOOTH_SPEED = 12f

        private val corner by enumChoice("Corner", Corner.BOTTOM_LEFT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 6f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val showCoords by boolean("ShowCoords", true)
        private val showBps by boolean("ShowBPS", true)
        private val showDirection by boolean("ShowDirection", true)
        private val showBiome by boolean("ShowBiome", true)

        val bounds = PanelBounds()

        // Smoothed horizontal speed (blocks per second)
        private var lastX = 0.0
        private var lastZ = 0.0
        private var lastBpsNs = 0L
        private var smoothBps = 0f

        private var smoothWidth = -1f
        private var lastSizeNs = 0L

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val px = fontSize * 0.92f
            val density = density(scale)

            // Speed: horizontal blocks/sec with exponential smoothing
            val nowNs = System.nanoTime()
            val bpsDt = if (lastBpsNs == 0L) 0f else ((nowNs - lastBpsNs) / 1_000_000_000.0).toFloat()
            if (bpsDt > 0f) {
                val dx = player.x - lastX
                val dz = player.z - lastZ
                val instant = (kotlin.math.sqrt(dx * dx + dz * dz) / bpsDt).toFloat()
                smoothBps += (instant.coerceAtMost(80f) - smoothBps) * (1f - kotlin.math.exp(-8f * bpsDt))
            }
            lastX = player.x
            lastZ = player.z
            lastBpsNs = nowNs

            // Rows: label (dimmed) + value (white)
            val rows = ArrayList<Pair<String, String>>(4)
            if (showCoords) {
                rows += "XYZ" to "${player.blockX} ${player.blockY} ${player.blockZ}"
            }
            if (showBps) {
                rows += txtBps to "%.1f".format(smoothBps)
            }
            if (showDirection) {
                // Server-side rotation when a module (e.g. KillAura) is rotating, otherwise the real one
                val rotation = RotationManager.currentRotation ?: player.rotation
                rows += "Y/P" to "%.1f / %.1f".format(
                    net.minecraft.util.Mth.wrapDegrees(rotation.yaw),
                    rotation.pitch,
                )
            }
            if (showBiome) {
                val biomeKey = world.getBiome(player.blockPosition()).unwrapKey().orElse(null)
                if (biomeKey != null) {
                    val biomeName = net.minecraft.network.chat.Component
                        .translatable(biomeKey.identifier().toLanguageKey("biome")).string
                    rows += "" to biomeName
                }
            }
            if (rows.isEmpty()) {
                return@handler
            }

            // Single-line layout: [label value] | [label value] | … (digit-stable widths)
            val textHeight = WatermarkFont.TEXT.lineHeight(px, density)
            val labelWidths = FloatArray(rows.size)
            val segmentWidths = FloatArray(rows.size)
            var innerWidth = 0f
            for ((index, row) in rows.withIndex()) {
                labelWidths[index] = if (row.first.isEmpty()) {
                    0f
                } else {
                    WatermarkFont.TEXT.width(row.first, px, density) + 4f
                }
                val sample = String(CharArray(row.second.length) { i ->
                    if (row.second[i].isDigit()) '0' else row.second[i]
                })
                segmentWidths[index] = labelWidths[index] + WatermarkFont.TEXT.width(sample, px, density)
                innerWidth += segmentWidths[index]
                if (index > 0) {
                    innerWidth += SECTION_SPACE * 2f + SEPARATOR_WIDTH
                }
            }
            innerWidth = Math.round(innerWidth.coerceAtLeast(40f) * 2f) / 2f

            val targetWidth = PAD_X * 2f + innerWidth
            val height = PAD_Y * 2f + textHeight

            // Smooth width (e.g. when the biome name changes)
            val sizeDt = if (lastSizeNs == 0L) {
                1f / 60f
            } else {
                ((nowNs - lastSizeNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastSizeNs = nowNs
            if (smoothWidth < 0f) {
                smoothWidth = targetWidth
            }
            smoothWidth += (targetWidth - smoothWidth) * (1f - kotlin.math.exp(-SIZE_SMOOTH_SPEED * sizeDt))
            if (kotlin.math.abs(targetWidth - smoothWidth) < 0.1f) {
                smoothWidth = targetWidth
            }
            val width = smoothWidth

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.INFO && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)

            val glassSetup = requestGlass(ctx, event)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // Separator positions between segments
            val separators = ArrayList<Float>(rows.size)
            var segmentX = PAD_X
            for ((index, _) in rows.withIndex()) {
                if (index > 0) {
                    segmentX += SECTION_SPACE
                    separators += segmentX
                    segmentX += SEPARATOR_WIDTH + SECTION_SPACE
                }
                segmentX += segmentWidths[index]
            }

            drawGlassPanel(ctx, width, height, 1f, density, glassSetup, separators = separators)

            val labelArgb = scaleAlpha(infoColor.argb, 1f)
            val textY = PAD_Y
            var drawX = PAD_X
            for ((index, row) in rows.withIndex()) {
                if (index > 0) {
                    drawX += SECTION_SPACE + SEPARATOR_WIDTH + SECTION_SPACE
                }
                if (row.first.isNotEmpty()) {
                    WatermarkFont.TEXT.submit(ctx, row.first, drawX, textY, px, density) { labelArgb }
                }
                WatermarkFont.TEXT.submit(ctx, row.second, drawX + labelWidths[index], textY, px, density) { -1 }
                drawX += segmentWidths[index]
            }

            ctx.pose().popMatrix()
        }
    }

    // =============================================================================================
    // Hotbar element (cells + sliding selection + pixel hearts/food/xp/air)
    // =============================================================================================

    private object HotbarHud : ToggleableValueGroup(this, "Hotbar", true) {

        private const val CELL = 20f
        private const val CELL_GAP = 2f
        private const val SLOT_COUNT = 9
        private const val ROW_WIDTH = CELL * SLOT_COUNT + CELL_GAP * (SLOT_COUNT - 1)
        private const val OFFHAND_GAP = 6f
        private const val XP_HEIGHT = 4.5f
        private const val XP_Y = -8.5f
        private const val STATS_Y = -19f
        private const val HEART_STEP = 8f
        private const val POP_MS = 250f
        private const val DIE_MS = 420f

        private var offsetX by float("OffsetX", 0f, -2000f..2000f)
        private var offsetY by float("OffsetY", 4f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val showHearts by boolean("Hearts", true)
        private val showFood by boolean("Food", true)
        private val showSaturation by boolean("Saturation", true)
        private val showXp by boolean("Experience", true)
        private val showAir by boolean("Air", true)
        private val showItemName by boolean("ItemName", true)

        val bounds = PanelBounds()
        val heartsShown: Boolean get() = showHearts
        val foodShown: Boolean get() = showFood
        val xpShown: Boolean get() = showXp
        val airShown: Boolean get() = showAir
        val itemNameShown: Boolean get() = showItemName

        // Selected item name (vanilla timing, our font, plain white)
        private const val NAME_VISIBLE_MS = 2000f
        private const val NAME_FADE_MS = 500f
        private var nameKey = ""
        private var nameShownAt = 0L

        // --- Hearts (vanilla sprites, our animations) --------------------------------------------

        private class Palette(val top: Int, val bottom: Int)

        private val PALETTE_FOOD = Palette(0xFFE8A33C.toInt(), 0xFFB05A1E.toInt())
        private val PALETTE_AIR = Palette(0xFF8FD8F2.toInt(), 0xFF3E8FD9.toInt())
        private val PALETTE_EMPTY = Palette(0x8C18090B.toInt(), 0xA60C0506.toInt())

        private const val HEART_SIZE = 9
        private val sprContainer = Identifier.withDefaultNamespace("hud/heart/container")

        /** Vanilla heart sprite: "hud/heart/full", "…/poisoned_half", "…/absorbing_full", … */
        private fun heartSprite(prefix: String, half: Boolean): Identifier {
            val value = if (half) "half" else "full"
            val path = if (prefix.isEmpty()) value else "${prefix}_$value"
            return Identifier.withDefaultNamespace("hud/heart/$path")
        }

        private class DyingHeart(val index: Int, val sprite: Identifier, val at: Long)

        private var prevHp = -1
        private val popStarts = HashMap<Int, Long>()
        private val dying = ArrayList<DyingHeart>()
        private var smoothSel = Float.NaN
        private var lastFrameNs = 0L

        /** Tiny segmented bar (food / air): 10 pixel-styled segments, right-to-left fill. */
        @Suppress("LongParameterList")
        private fun VertexConsumer.segmentBar(
            pose: Matrix3x2f,
            x: Float,
            y: Float,
            value: Int,
            max: Int,
            palette: Palette,
            rightAligned: Boolean,
        ) {
            val segments = 10
            val segWidth = 6f
            val segHeight = 5f
            val segGap = 1.3f
            for (segment in 0 until segments) {
                val segValue = (value * segments * 2 / max.coerceAtLeast(1)) - segment * 2
                val sx = if (rightAligned) {
                    x - (segment + 1) * segWidth - segment * segGap
                } else {
                    x + segment * (segWidth + segGap)
                }
                // Track
                val track = lerpArgb(PALETTE_EMPTY.top, PALETTE_EMPTY.bottom, 0.5f)
                addVertexWith2DPose(pose, sx, y).setColor(track)
                addVertexWith2DPose(pose, sx, y + segHeight).setColor(track)
                addVertexWith2DPose(pose, sx + segWidth, y + segHeight).setColor(track)
                addVertexWith2DPose(pose, sx + segWidth, y).setColor(track)
                // Fill (half segments supported)
                if (segValue >= 1) {
                    val fillWidth = if (segValue >= 2) segWidth else segWidth * 0.5f
                    val fx = if (rightAligned) sx + segWidth - fillWidth else sx
                    addVertexWith2DPose(pose, fx, y).setColor(palette.top)
                    addVertexWith2DPose(pose, fx, y + segHeight).setColor(palette.bottom)
                    addVertexWith2DPose(pose, fx + fillWidth, y + segHeight).setColor(palette.bottom)
                    addVertexWith2DPose(pose, fx + fillWidth, y).setColor(palette.top)
                }
            }
        }

        // --- Frame -------------------------------------------------------------------------------

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val now = System.currentTimeMillis()
            val survival = mc.gameMode?.canHurtPlayer() != false
            val offhand = player.getItemBySlot(EquipmentSlot.OFFHAND)
            val hasOffhand = !offhand.isEmpty

            // Geometry of the whole assembly (local units; cells row at y 0..CELL)
            val leftLocal = if (hasOffhand) -(CELL + OFFHAND_GAP) else 0f
            val topLocal = if (survival) STATS_Y - 12f else -2f
            val width = ROW_WIDTH - leftLocal
            val height = CELL - topLocal

            // Centered at the bottom of the screen; offsets are draggable deviations
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.HOTBAR && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = ((newX - leftLocal * scale) - (screenWidth - ROW_WIDTH * scale) * 0.5f)
                    .coerceIn(-2000f, 2000f) / scale
                offsetY = (screenHeight - (newY - topLocal * scale) - CELL * scale).coerceIn(0f, 1000f)
            }

            val x = (screenWidth - ROW_WIDTH * scale) * 0.5f + offsetX * scale
            val y = screenHeight - offsetY - CELL * scale
            bounds.set(x + leftLocal * scale, y + topLocal * scale, width * scale, height * scale)

            val glassSetup = requestGlass(ctx, event)
            val density = density(scale)
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.2f)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // Slot cells (+ offhand) as glass panels
            for (slot in 0 until SLOT_COUNT) {
                ctx.pose().pushMatrix()
                ctx.pose().translate(slot * (CELL + CELL_GAP), 0f)
                drawGlassPanel(ctx, CELL, CELL, 1f, density, glassSetup)
                ctx.pose().popMatrix()
            }
            if (hasOffhand) {
                ctx.pose().pushMatrix()
                ctx.pose().translate(leftLocal, 0f)
                drawGlassPanel(ctx, CELL, CELL, 1f, density, glassSetup)
                ctx.pose().popMatrix()
            }

            // Selection: an accent ring gliding between the cells
            val nowNs = System.nanoTime()
            val dt = if (lastFrameNs == 0L) {
                1f / 60f
            } else {
                ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastFrameNs = nowNs
            val targetSel = player.inventory.selectedSlot.toFloat()
            if (smoothSel.isNaN()) {
                smoothSel = targetSel
            }
            smoothSel += (targetSel - smoothSel) * (1f - kotlin.math.exp(-18f * dt))
            if (kotlin.math.abs(targetSel - smoothSel) < 0.002f) {
                smoothSel = targetSel
            }
            val selX = smoothSel * (CELL + CELL_GAP)
            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(selX - 2f, -2f, selX + CELL + 2f, CELL + 2f),
                ) { pose ->
                    val outer = buildContour(selX, 0f, selX + CELL, CELL, radius.coerceAtMost(CELL * 0.5f), 0f)
                    val inner = buildContour(selX, 0f, selX + CELL, CELL, radius.coerceAtMost(CELL * 0.5f), -1.4f)
                    val outerFeather = buildContour(selX, 0f, selX + CELL, CELL, radius.coerceAtMost(CELL * 0.5f), feather)
                    val colorOf = { vx: Float -> accentAt(((vx - selX) / CELL) * 0.3f, 1f) }
                    emitRing(
                        inner, outer,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx)) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx)) },
                    )
                    emitRing(
                        outer, outerFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx)) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(colorOf(vx) and 0xFFFFFF) },
                    )
                }
            )

            // XP bar + level
            if (showXp && survival) {
                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(0f, XP_Y - 1f, ROW_WIDTH, XP_Y + XP_HEIGHT + 1f),
                    ) { pose ->
                        // Clearly a rectangle — only the corners are gently rounded
                        val xpRadius = 1.3f
                        val track = scaleAlpha(0xFF000000.toInt(), 0.45f)
                        val trackContour = buildContour(0f, XP_Y, ROW_WIDTH, XP_Y + XP_HEIGHT, xpRadius, 0f)
                        val trackFeather = buildContour(0f, XP_Y, ROW_WIDTH, XP_Y + XP_HEIGHT, xpRadius, feather * 0.7f)
                        emitFill(trackContour, ROW_WIDTH * 0.5f, XP_Y + XP_HEIGHT * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(track)
                        }
                        emitRing(
                            trackContour, trackFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(track) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(track and 0xFFFFFF) },
                        )

                        barDressing(pose, 0f, XP_Y, ROW_WIDTH, XP_Y + XP_HEIGHT, xpRadius, 1f)

                        val fillWidth = ROW_WIDTH * player.experienceProgress.coerceIn(0f, 1f)
                        if (fillWidth > 1f) {
                            // Deep, saturated greens — the light pastel pair read as washed out
                            val xpTop = 0xFF7ED32F.toInt()
                            val xpBottom = 0xFF3F8C1A.toInt()
                            val fillAt = { vy: Float ->
                                lerpArgb(xpTop, xpBottom, ((vy - XP_Y) / XP_HEIGHT).coerceIn(0f, 1f))
                            }
                            val fillR = xpRadius.coerceAtMost(fillWidth * 0.5f)
                            val fillContour = buildContour(0f, XP_Y, fillWidth, XP_Y + XP_HEIGHT, fillR, 0f)
                            emitFill(fillContour, fillWidth * 0.5f, XP_Y + XP_HEIGHT * 0.5f) { vx, vy ->
                                addVertexWith2DPose(pose, vx, vy).setColor(fillAt(vy))
                            }
                            fillSheen(pose, 0f, XP_Y, fillWidth, fillR, 1f)
                        }
                    }
                )

                if (player.experienceLevel > 0) {
                    val levelText = player.experienceLevel.toString()
                    val levelPx = fontSize * 0.78f
                    val levelWidth = WatermarkFont.BRAND.width(levelText, levelPx, density)
                    val levelColor = 0xFF9FE060.toInt()
                    WatermarkFont.BRAND.submit(
                        ctx, levelText,
                        (ROW_WIDTH - levelWidth) * 0.5f,
                        STATS_Y - 1.5f,
                        levelPx, density,
                    ) { levelColor }
                }
            }

            // Hearts / food / air row
            if (survival) {
                val hp = ceil(player.health).toInt().coerceAtLeast(0)
                val maxHp = ceil(maxOf(player.maxHealth, hp.toFloat())).toInt().coerceAtLeast(2)
                val absorb = ceil(player.absorptionAmount).toInt().coerceAtLeast(0)

                // Vanilla status sprite variant
                val prefix = when {
                    player.hasEffect(net.minecraft.world.effect.MobEffects.POISON) -> "poisoned"
                    player.hasEffect(net.minecraft.world.effect.MobEffects.WITHER) -> "withered"
                    player.isFullyFrozen -> "frozen"
                    else -> ""
                }

                if (showHearts) {
                    if (prevHp >= 0 && hp != prevHp) {
                        val oldHearts = (prevHp + 1) / 2
                        val newHearts = (hp + 1) / 2
                        if (hp < prevHp) {
                            for (index in newHearts until oldHearts) {
                                val wasHalf = index == oldHearts - 1 && prevHp % 2 == 1
                                dying += DyingHeart(index, heartSprite(prefix, wasHalf), now)
                            }
                        } else {
                            for (index in oldHearts until newHearts) {
                                popStarts[index] = now
                            }
                        }
                    }
                    prevHp = hp
                    dying.removeIf { now - it.at > DIE_MS }

                    val healthHearts = (maxHp + 1) / 2
                    val absorbHearts = (absorb + 1) / 2
                    val totalHearts = healthHearts + absorbHearts
                    val lowHp = hp in 1..6
                    val time = timeSeconds()
                    val shakeX = if (lowHp) sin(time * 47f) * 0.7f else 0f
                    val shakeY = if (lowHp) cos(time * 41f) * 0.7f else 0f

                    // Vanilla heart sprites in our layout, with our animations
                    for (index in 0 until totalHearts) {
                        val column = index % 10
                        val row = index / 10
                        val heartX = column * HEART_STEP + shakeX
                        val heartY = STATS_Y - row * 10f + shakeY

                        var heartScale = 1f
                        popStarts[index]?.let { startedAt ->
                            val t = ((now - startedAt) / POP_MS).coerceIn(0f, 1f)
                            heartScale *= 0.4f + 0.6f * easeOutBack(t)
                            if (t >= 1f) {
                                popStarts.remove(index)
                            }
                        }
                        if (lowHp && index < healthHearts) {
                            heartScale *= 1f + 0.09f * sin(time * 9f + index * 0.9f)
                        }

                        ctx.pose().pushMatrix()
                        ctx.pose().translate(heartX + HEART_SIZE * 0.5f, heartY + HEART_SIZE * 0.5f)
                        ctx.pose().scale(heartScale, heartScale)
                        ctx.pose().translate(-HEART_SIZE * 0.5f, -HEART_SIZE * 0.5f)

                        ctx.blitSprite(RenderPipelines.GUI_TEXTURED, sprContainer, 0, 0, HEART_SIZE, HEART_SIZE)
                        if (index < healthHearts) {
                            val heartHp = hp - index * 2
                            if (heartHp >= 1) {
                                ctx.blitSprite(
                                    RenderPipelines.GUI_TEXTURED,
                                    heartSprite(prefix, half = heartHp == 1),
                                    0, 0, HEART_SIZE, HEART_SIZE,
                                )
                            }
                        } else {
                            val absorbHp = absorb - (index - healthHearts) * 2
                            if (absorbHp >= 1) {
                                ctx.blitSprite(
                                    RenderPipelines.GUI_TEXTURED,
                                    heartSprite("absorbing", half = absorbHp == 1),
                                    0, 0, HEART_SIZE, HEART_SIZE,
                                )
                            }
                        }

                        ctx.pose().popMatrix()
                    }

                    // Dying hearts: fly up, grow and fade out at their old slots
                    for (heart in dying) {
                        val t = ((now - heart.at) / DIE_MS).coerceIn(0f, 1f)
                        val column = heart.index % 10
                        val row = heart.index / 10
                        val rise = 9f * t * t + 2f * t
                        val grow = 1f + 0.35f * t
                        val alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)

                        ctx.pose().pushMatrix()
                        ctx.pose().translate(
                            column * HEART_STEP + HEART_SIZE * 0.5f,
                            STATS_Y - row * 10f + HEART_SIZE * 0.5f - rise,
                        )
                        ctx.pose().scale(grow, grow)
                        ctx.pose().translate(-HEART_SIZE * 0.5f, -HEART_SIZE * 0.5f)
                        ctx.blitSprite(
                            RenderPipelines.GUI_TEXTURED, heart.sprite,
                            0, 0, HEART_SIZE, HEART_SIZE,
                            (alpha shl 24) or 0xFFFFFF,
                        )
                        ctx.pose().popMatrix()
                    }
                }

                // Food: vanilla sprites in our layout, right-aligned, draining right→left
                if (showFood) {
                    val foodLevel = player.foodData.foodLevel
                    val hunger = player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER)
                    val suffix = if (hunger) "_hunger" else ""
                    for (icon in 0 until 10) {
                        val iconX = ROW_WIDTH - (icon + 1) * HEART_STEP - 1f
                        ctx.pose().pushMatrix()
                        ctx.pose().translate(iconX, STATS_Y)
                        // Vanilla layering: the empty container (with its dark outline) is always
                        // drawn first, the full/half drumstick goes on top of it
                        ctx.blitSprite(
                            RenderPipelines.GUI_TEXTURED,
                            Identifier.withDefaultNamespace("hud/food_empty$suffix"),
                            0, 0, HEART_SIZE, HEART_SIZE,
                        )
                        val value = when {
                            foodLevel > icon * 2 + 1 -> "hud/food_full$suffix"
                            foodLevel == icon * 2 + 1 -> "hud/food_half$suffix"
                            else -> null
                        }
                        if (value != null) {
                            ctx.blitSprite(
                                RenderPipelines.GUI_TEXTURED,
                                Identifier.withDefaultNamespace(value),
                                0, 0, HEART_SIZE, HEART_SIZE,
                            )
                        }
                        ctx.pose().popMatrix()
                    }
                }

                // Saturation: a thin AppleSkin-style line along the bottom of the food row,
                // right-aligned and draining right→left like hunger. A dark 1px shadow keeps
                // it legible over the bright drumsticks; the line yellows normally and turns
                // olive under the Hunger effect (which drains saturation).
                if (showFood && showSaturation) {
                    val saturation = player.foodData.saturationLevel.coerceIn(0f, 20f)
                    if (saturation > 0.05f) {
                        val hunger = player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER)
                        val satW = saturation * (HEART_STEP / 2f)
                        val xr = ROW_WIDTH - 1f
                        val xl = xr - satW
                        val yb = STATS_Y + HEART_SIZE.toFloat()
                        val lineColor = if (hunger) 0xF0C8B44C.toInt() else 0xF0FFF2A8.toInt()
                        ctx.guiRenderState.addGuiElement(
                            WatermarkGuiElement(
                                RenderPipelines.GUI,
                                TextureSetup.noTexture(),
                                Matrix3x2f(ctx.pose()),
                                ctx.scissorStack.peek(),
                                ctx.watermarkBounds(xl - 1f, yb - 3f, xr + 1f, yb + 1f),
                            ) { pose ->
                                val shadow = buildContour(xl, yb - 2f, xr, yb, 0f, 0f)
                                emitFill(shadow, (xl + xr) * 0.5f, yb - 1f) { vx, vy ->
                                    addVertexWith2DPose(pose, vx, vy).setColor(0xB0000000.toInt())
                                }
                                val line = buildContour(xl, yb - 2f, xr, yb - 1f, 0f, 0f)
                                emitFill(line, (xl + xr) * 0.5f, yb - 1.5f) { vx, vy ->
                                    addVertexWith2DPose(pose, vx, vy).setColor(lineColor)
                                }
                            }
                        )
                    }
                }

                // Air: our segmented bar above the food row, only when relevant
                if (showAir && (player.isUnderWater || player.airSupply < player.maxAirSupply)) {
                    ctx.guiRenderState.addGuiElement(
                        WatermarkGuiElement(
                            RenderPipelines.GUI,
                            TextureSetup.noTexture(),
                            Matrix3x2f(ctx.pose()),
                            ctx.scissorStack.peek(),
                            ctx.watermarkBounds(-2f, STATS_Y - 14f, ROW_WIDTH + 2f, STATS_Y + 2f),
                        ) { pose ->
                            segmentBar(
                                pose, ROW_WIDTH, STATS_Y - 7f,
                                player.airSupply.coerceAtLeast(0), player.maxAirSupply, PALETTE_AIR,
                                rightAligned = true,
                            )
                        }
                    )
                }
            }

            // Selected item name: vanilla behavior (show on change, then fade), our font, white
            if (showItemName) {
                val selected = player.inventory.getSelectedItem()
                if (selected.isEmpty) {
                    nameKey = ""
                } else {
                    val key = "${player.inventory.selectedSlot}|${selected.hoverName.string}"
                    if (key != nameKey) {
                        nameKey = key
                        nameShownAt = now
                    }
                    val elapsed = (now - nameShownAt).toFloat()
                    if (elapsed < NAME_VISIBLE_MS) {
                        val nameAlpha = ((NAME_VISIBLE_MS - elapsed) / NAME_FADE_MS).coerceIn(0f, 1f)
                        if (nameAlpha > 0.02f) {
                            val name = selected.hoverName.string
                            val namePx = fontSize * 0.92f
                            val nameWidth = WatermarkFont.TEXT.width(name, namePx, density)
                            val nameColor = scaleAlpha(-1, nameAlpha)
                            WatermarkFont.TEXT.submit(
                                ctx, name,
                                (ROW_WIDTH - nameWidth) * 0.5f,
                                (if (survival) STATS_Y else -2f) - 14f,
                                namePx, density,
                            ) { nameColor }
                        }
                    }
                }
            }

            ctx.pose().popMatrix()

            // Items at a whole device-pixel factor, snapped to the grid (crisp at any scale)
            val guiScaleF = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val itemFactor = Math.round(scale * guiScaleF).coerceAtLeast(1)
            val itemGuiScale = itemFactor / guiScaleF
            val itemSizeGui = 16f * itemGuiScale
            for (slot in -1 until SLOT_COUNT) {
                val stack = if (slot == -1) {
                    if (!hasOffhand) continue
                    offhand
                } else {
                    player.inventory.getItem(slot)
                }
                if (stack.isEmpty) {
                    continue
                }
                val cellLocalX = if (slot == -1) leftLocal else slot * (CELL + CELL_GAP)
                val inset = (CELL * scale - itemSizeGui) * 0.5f
                val gx = Math.round((x + cellLocalX * scale + inset) * guiScaleF) / guiScaleF
                val gy = Math.round((y + inset) * guiScaleF) / guiScaleF

                ctx.pose().pushMatrix()
                ctx.pose().translate(gx, gy)
                ctx.pose().scale(itemGuiScale, itemGuiScale)
                ctx.item(stack, 0, 0)
                ctx.itemDecorations(mc.font, stack, 0, 0)
                ctx.pose().popMatrix()
            }
        }
    }

    // =============================================================================================
    // Armor element (equipment cells with durability bars)
    // =============================================================================================

    private object ArmorHud : ToggleableValueGroup(this, "Armor", true) {

        private const val CELL_GAP = 3f
        private const val BAR_HEIGHT = 1.6f
        private const val BAR_GAP = 1.5f

        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 40f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val itemScale by float("ItemScale", 1f, 0.5f..3f)
        private val durabilityBars by boolean("DurabilityBars", true)

        val bounds = PanelBounds()

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                .map { player.getItemBySlot(it) }
            if (armor.all { it.isEmpty } && !editMode()) {
                return@handler
            }

            val density = density(scale)

            // Icons render at a whole device-pixel factor; cells wrap the icon (crisp pixel art)
            val guiScaleF = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val iconFactor = Math.round(itemScale * guiScaleF).coerceAtLeast(1)
            val iconGuiScale = iconFactor / guiScaleF
            val iconLocal = 16f * iconGuiScale / scale
            val cellSize = iconLocal + 1.5f

            val width = armor.size * cellSize + (armor.size - 1) * CELL_GAP
            val height = cellSize + if (durabilityBars) BAR_GAP + BAR_HEIGHT else 0f

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.ARMOR && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // Cells + durability bars, all in one element
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val cellTop = scaleAlpha(backgroundTop.argb, 1f)
            val cellBottom = scaleAlpha(backgroundBottom.argb, 1f)
            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(-1f, -1f, width + 1f, height + 1f),
                ) { pose ->
                    for ((index, stack) in armor.withIndex()) {
                        val cellX = index * (cellSize + CELL_GAP)
                        val cellAt = { vy: Float ->
                            lerpArgb(cellTop, cellBottom, (vy / cellSize).coerceIn(0f, 1f))
                        }
                        val contour = buildContour(cellX, 0f, cellX + cellSize, cellSize, 2f, 0f)
                        val contourFeather = buildContour(cellX, 0f, cellX + cellSize, cellSize, 2f, feather)
                        emitFill(contour, cellX + cellSize * 0.5f, cellSize * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy))
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy)) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy) and 0xFFFFFF) },
                        )

                        // Durability bar: green→red by remaining durability
                        if (durabilityBars && !stack.isEmpty && stack.isDamageableItem) {
                            val fraction =
                                1f - stack.damageValue.toFloat() / stack.maxDamage.coerceAtLeast(1)
                            val barY = cellSize + BAR_GAP
                            val trackColor = scaleAlpha(0xFF000000.toInt(), 0.35f)
                            addVertexWith2DPose(pose, cellX, barY).setColor(trackColor)
                            addVertexWith2DPose(pose, cellX, barY + BAR_HEIGHT).setColor(trackColor)
                            addVertexWith2DPose(pose, cellX + cellSize, barY + BAR_HEIGHT).setColor(trackColor)
                            addVertexWith2DPose(pose, cellX + cellSize, barY).setColor(trackColor)

                            val fillWidth = cellSize * fraction.coerceIn(0f, 1f)
                            if (fillWidth > 0.3f) {
                                val fillColor = lerpArgb(0xFFE0635F.toInt(), 0xFF7FCB8F.toInt(), fraction)
                                addVertexWith2DPose(pose, cellX, barY).setColor(fillColor)
                                addVertexWith2DPose(pose, cellX, barY + BAR_HEIGHT).setColor(fillColor)
                                addVertexWith2DPose(pose, cellX + fillWidth, barY + BAR_HEIGHT).setColor(fillColor)
                                addVertexWith2DPose(pose, cellX + fillWidth, barY).setColor(fillColor)
                            }
                        }
                    }
                }
            )

            ctx.pose().popMatrix()

            // Items at the whole device-pixel factor, origins snapped to the grid
            val itemSizeGui = 16f * iconGuiScale
            for ((index, stack) in armor.withIndex()) {
                if (stack.isEmpty) {
                    continue
                }
                val cellGuiX = x + index * (cellSize + CELL_GAP) * scale
                val inset = (cellSize * scale - itemSizeGui) * 0.5f
                val gx = Math.round((cellGuiX + inset) * guiScaleF) / guiScaleF
                val gy = Math.round((y + inset) * guiScaleF) / guiScaleF

                ctx.pose().pushMatrix()
                ctx.pose().translate(gx, gy)
                ctx.pose().scale(iconGuiScale, iconGuiScale)
                ctx.item(stack, 0, 0)
                ctx.pose().popMatrix()
            }
        }
    }

    // =============================================================================================
    // ItemBinds element (items bound to keys via ModuleItemBind)
    // =============================================================================================

    private object ItemBindsHud : ToggleableValueGroup(this, "ItemBinds", true) {

        private const val COL_GAP = 3f
        private const val BADGE_GAP = 2f
        private const val BADGE_PAD_X = 3f
        private const val BADGE_PAD_Y = 1.2f

        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 120f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val itemScale by float("ItemScale", 1f, 0.5f..3f)

        val bounds = PanelBounds()

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow || !ModuleItemBind.running) {
                return@handler
            }

            val ctx = event.context
            val editing = editMode()
            val binds = ModuleItemBind.hudBinds
            if (binds.isEmpty() && !editing) {
                return@handler
            }

            val density = density(scale)
            val labelPx = fontSize * 0.7f
            val labelHeight = WatermarkFont.BRAND.lineHeight(labelPx, density)
            val badgeHeight = labelHeight + BADGE_PAD_Y * 2f

            val guiScaleF = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val iconFactor = Math.round(itemScale * guiScaleF).coerceAtLeast(1)
            val iconGuiScale = iconFactor / guiScaleF
            val iconLocal = 16f * iconGuiScale / scale
            val cellSize = iconLocal + 1.5f

            // Per-column geometry: a column is as wide as its (item cell | key badge)
            val count = if (binds.isEmpty()) 1 else binds.size
            val labelWidths = FloatArray(count)
            val badgeWidths = FloatArray(count)
            val colWidths = FloatArray(count)
            for (i in 0 until count) {
                val label = binds.getOrNull(i)?.keyLabel ?: ""
                labelWidths[i] = WatermarkFont.BRAND.width(label, labelPx, density)
                badgeWidths[i] = if (label.isEmpty()) 0f else labelWidths[i] + BADGE_PAD_X * 2f
                colWidths[i] = maxOf(cellSize, badgeWidths[i])
            }
            val colX = FloatArray(count)
            var acc = 0f
            for (i in 0 until count) {
                colX[i] = acc
                acc += colWidths[i] + COL_GAP
            }
            val width = (acc - COL_GAP).coerceAtLeast(cellSize)
            val height = cellSize + BADGE_GAP + badgeHeight

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.ITEM_BINDS && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // Item cells + key badges (separate rounded rects), all in one element
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val cellTop = scaleAlpha(backgroundTop.argb, 1f)
            val cellBottom = scaleAlpha(backgroundBottom.argb, 1f)
            val badgeY = cellSize + BADGE_GAP
            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(-1f, -1f, width + 1f, height + 1f),
                ) { pose ->
                    val panelAt = { y0: Float, h: Float, vy: Float ->
                        lerpArgb(cellTop, cellBottom, ((vy - y0) / h).coerceIn(0f, 1f))
                    }
                    val drawRect = { rx0: Float, ry0: Float, rx1: Float, ry1: Float, radius: Float ->
                        val contour = buildContour(rx0, ry0, rx1, ry1, radius, 0f)
                        val contourFeather = buildContour(rx0, ry0, rx1, ry1, radius, feather)
                        emitFill(contour, (rx0 + rx1) * 0.5f, (ry0 + ry1) * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(panelAt(ry0, ry1 - ry0, vy))
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(panelAt(ry0, ry1 - ry0, vy)) },
                            { vx, vy ->
                                addVertexWith2DPose(pose, vx, vy).setColor(panelAt(ry0, ry1 - ry0, vy) and 0xFFFFFF)
                            },
                        )
                    }
                    for (i in 0 until count) {
                        val cellX = colX[i] + (colWidths[i] - cellSize) * 0.5f
                        drawRect(cellX, 0f, cellX + cellSize, cellSize, 2f)
                        if (badgeWidths[i] > 0f) {
                            val badgeX = colX[i] + (colWidths[i] - badgeWidths[i]) * 0.5f
                            drawRect(badgeX, badgeY, badgeX + badgeWidths[i], badgeY + badgeHeight, 2f)
                        }
                    }
                }
            )

            // Key labels inside the badges (centered, accent)
            for ((i, bind) in binds.withIndex()) {
                val badgeX = colX[i] + (colWidths[i] - badgeWidths[i]) * 0.5f
                val labelArgb = accentAt(0.25f, 1f)
                WatermarkFont.BRAND.submit(
                    ctx, bind.keyLabel,
                    badgeX + BADGE_PAD_X,
                    badgeY + BADGE_PAD_Y,
                    labelPx, density,
                ) { labelArgb }
            }

            ctx.pose().popMatrix()

            // Item icons at a whole device-pixel factor, snapped to the grid
            val itemSizeGui = 16f * iconGuiScale
            for ((i, bind) in binds.withIndex()) {
                val cellLocalX = colX[i] + (colWidths[i] - cellSize) * 0.5f
                val cellGuiX = x + cellLocalX * scale
                val inset = (cellSize * scale - itemSizeGui) * 0.5f
                val gx = Math.round((cellGuiX + inset) * guiScaleF) / guiScaleF
                val gy = Math.round((y + inset) * guiScaleF) / guiScaleF

                ctx.pose().pushMatrix()
                ctx.pose().translate(gx, gy)
                ctx.pose().scale(iconGuiScale, iconGuiScale)
                ctx.item(bind.item.defaultInstance, 0, 0)
                ctx.pose().popMatrix()
            }
        }
    }

    // =============================================================================================
    // Counters element (PvP item counters: totems / gapples / pearls / arrows)
    // =============================================================================================

    private object CountersHud : ToggleableValueGroup(this, "Counters", true) {

        private const val CELL_GAP = 3f

        /** Count plate: the gap below the cell, the padding around the digits, the corner radius. */
        private const val COUNT_GAP = 2f
        private const val PLATE_PAD_X = 1.6f
        private const val PLATE_PAD_Y = 1.2f
        private const val PLATE_RADIUS = 2f

        /** Inter's cap height as a share of the em — the visual height of a row of digits. */
        private const val DIGIT_CAP_FACTOR = 0.74f

        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 80f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val itemScale by float("ItemScale", 1f, 0.5f..3f)

        val bounds = PanelBounds()

        private val showTotems by boolean("Totems", true)
        private val showEnchantedApples by boolean("EnchantedApples", true)
        private val showGoldenApples by boolean("GoldenApples", true)
        private val showPearls by boolean("Pearls", true)
        private val showArrows by boolean("Arrows", true)
        private val showCrystals by boolean("Crystals", true)
        private val showAnchors by boolean("Anchors", true)
        private val showExperience by boolean("ExperienceBottles", true)

        /** One tracked item: what to count, and the setting that shows or hides its cell. */
        private class Counter(val item: Item, val enabled: () -> Boolean)

        private val TRACKED = listOf(
            Counter(Items.TOTEM_OF_UNDYING) { showTotems },
            Counter(Items.ENCHANTED_GOLDEN_APPLE) { showEnchantedApples },
            Counter(Items.GOLDEN_APPLE) { showGoldenApples },
            Counter(Items.ENDER_PEARL) { showPearls },
            Counter(Items.ARROW) { showArrows },
            Counter(Items.END_CRYSTAL) { showCrystals },
            Counter(Items.RESPAWN_ANCHOR) { showAnchors },
            Counter(Items.EXPERIENCE_BOTTLE) { showExperience },
        )

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context

            // Count tracked items across the whole inventory (incl. offhand)
            val counts = IntArray(TRACKED.size)
            val inventory = player.inventory
            for (slot in 0 until inventory.containerSize) {
                val stack = inventory.getItem(slot)
                if (stack.isEmpty) {
                    continue
                }
                val index = TRACKED.indexOfFirst { it.item === stack.item }
                if (index >= 0) {
                    counts[index] += stack.count
                }
            }

            // Visible entries: a cell needs its own setting on, and then either a count or an open
            // screen — while one is open every enabled cell shows, so the panel stays draggable
            val editing = editMode()
            val visible = TRACKED.indices.filter { TRACKED[it].enabled() && (editing || counts[it] > 0) }
            if (visible.isEmpty()) {
                return@handler
            }

            val density = density(scale)
            val guiScaleF = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val iconFactor = Math.round(itemScale * guiScaleF).coerceAtLeast(1)
            val iconGuiScale = iconFactor / guiScaleF
            val iconLocal = 16f * iconGuiScale / scale
            val cellSize = iconLocal + 1.5f

            // The count sits on its own plate below the cell, so it never covers the item. The
            // plate hugs the digits' cap band rather than the font's line box — digits carry no
            // descender, and the line box would make the row noticeably taller than it reads.
            val countPx = fontSize * 0.68f
            val plateHeight = countPx * DIGIT_CAP_FACTOR + PLATE_PAD_Y * 2f
            val plateTop = cellSize + COUNT_GAP
            val plateBottom = plateTop + plateHeight

            // The element reserves room for every ENABLED cell and centres the ones that currently
            // have a count inside it. That footprint only moves when a setting is toggled, so the
            // row grows and shrinks around its own middle as items are used up and picked back up,
            // instead of dragging one edge across the screen. The plate row is reserved the same
            // way, so the icons hold their line as counters appear and empty out.
            val enabledCount = TRACKED.count { it.enabled() }
            val footprintWidth = enabledCount * cellSize + (enabledCount - 1) * CELL_GAP
            val width = visible.size * cellSize + (visible.size - 1) * CELL_GAP
            val height = plateBottom

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.COUNTERS && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) {
                    screenWidth - newX - footprintWidth * scale
                } else {
                    newX
                }).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val footprintX = if (corner.isRight) screenWidth - offsetX - footprintWidth * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(footprintX, y, footprintWidth * scale, height * scale)

            // Content origin: centred in the footprint. In edit mode every enabled cell shows, so
            // the two coincide and the drag handle sits exactly on what is drawn.
            val x = footprintX + (footprintWidth - width) * scale * 0.5f

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            // Cells in one element
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val cellTop = scaleAlpha(backgroundTop.argb, 1f)
            val cellBottom = scaleAlpha(backgroundBottom.argb, 1f)
            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(-1f, -1f, width + 1f, height + 1f),
                ) { pose ->
                    for (position in visible.indices) {
                        val cellX = position * (cellSize + CELL_GAP)
                        val cellAt = { vy: Float ->
                            lerpArgb(cellTop, cellBottom, (vy / cellSize).coerceIn(0f, 1f))
                        }
                        val contour = buildContour(cellX, 0f, cellX + cellSize, cellSize, 2f, 0f)
                        val contourFeather = buildContour(cellX, 0f, cellX + cellSize, cellSize, 2f, feather)
                        emitFill(contour, cellX + cellSize * 0.5f, cellSize * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy))
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy)) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(cellAt(vy) and 0xFFFFFF) },
                        )
                    }
                }
            )

            ctx.pose().popMatrix()

            // Icons at a whole device-pixel factor, snapped to the grid
            val itemSizeGui = 16f * iconGuiScale
            for ((position, trackedIndex) in visible.withIndex()) {
                val cellGuiX = x + position * (cellSize + CELL_GAP) * scale
                val inset = (cellSize * scale - itemSizeGui) * 0.5f
                val gx = Math.round((cellGuiX + inset) * guiScaleF) / guiScaleF
                val gy = Math.round((y + inset) * guiScaleF) / guiScaleF

                ctx.pose().pushMatrix()
                ctx.pose().translate(gx, gy)
                ctx.pose().scale(iconGuiScale, iconGuiScale)
                ctx.item(TRACKED[trackedIndex].item.defaultInstance, 0, 0)
                ctx.pose().popMatrix()
            }

            // Counts: a pill under each cell, in the cell's own gradient, with the digits centred
            // on it. It used to sit on the icon's bottom edge, where it hid the item it was
            // counting; below the cell it covers nothing and the row still reads as one strip.
            //
            // The plate is submitted before the digits, because the render state stacks each
            // element above whatever it overlaps.
            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            val ascent = WatermarkFont.TEXT.ascent(countPx, density)
            val baseline = plateBottom - PLATE_PAD_Y

            // Measured once, reused by the text pass below
            val texts = arrayOfNulls<String>(visible.size)
            val textWidths = FloatArray(visible.size)
            val plateLeft = FloatArray(visible.size)
            val plateWidths = FloatArray(visible.size)
            for ((position, trackedIndex) in visible.withIndex()) {
                val count = counts[trackedIndex]
                if (count <= 0) {
                    continue
                }
                val text = count.toString()
                val textWidth = WatermarkFont.TEXT.width(text, countPx, density)
                // A single digit would leave a sliver of a plate — never narrower than it is tall
                val plateWidth = maxOf(textWidth + PLATE_PAD_X * 2f, plateHeight)
                texts[position] = text
                textWidths[position] = textWidth
                plateWidths[position] = plateWidth
                plateLeft[position] = position * (cellSize + CELL_GAP) + (cellSize - plateWidth) * 0.5f
            }

            if (texts.any { it != null }) {
                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI,
                        TextureSetup.noTexture(),
                        Matrix3x2f(ctx.pose()),
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(-1f, plateTop - 1f, width + 1f, plateBottom + 1f),
                    ) { pose ->
                        // Same top→bottom gradient the cells carry, so the pill reads as a piece
                        // of the same strip rather than a second, unrelated panel
                        val plateAt = { vy: Float ->
                            lerpArgb(cellTop, cellBottom, ((vy - plateTop) / plateHeight).coerceIn(0f, 1f))
                        }
                        for (position in visible.indices) {
                            if (texts[position] == null) {
                                continue
                            }
                            val px0 = plateLeft[position]
                            val px1 = px0 + plateWidths[position]
                            val radius = minOf(PLATE_RADIUS, plateWidths[position] * 0.5f, plateHeight * 0.5f)
                            val contour = buildContour(px0, plateTop, px1, plateBottom, radius, 0f)
                            val contourFeather = buildContour(px0, plateTop, px1, plateBottom, radius, feather)
                            emitFill(contour, (px0 + px1) * 0.5f, plateTop + plateHeight * 0.5f) { vx, vy ->
                                addVertexWith2DPose(pose, vx, vy).setColor(plateAt(vy))
                            }
                            emitRing(
                                contour, contourFeather,
                                { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(plateAt(vy)) },
                                { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(plateAt(vy) and 0xFFFFFF) },
                            )
                        }
                    }
                )
            }

            for (position in visible.indices) {
                val text = texts[position] ?: continue
                WatermarkFont.TEXT.submit(
                    ctx, text,
                    plateLeft[position] + (plateWidths[position] - textWidths[position]) * 0.5f,
                    baseline - ascent,
                    countPx, density,
                ) { -1 }
            }

            ctx.pose().popMatrix()
        }
    }

    // =============================================================================================
    // PvP intel element (win chance, totem pops, gapples, armor, teaming warning)
    // =============================================================================================

    /**
     * Everything [PvpIntelTracker] knows about the current target, as a compact row list.
     *
     * Reads the tracker directly, so it keeps working with the PvpHelper module switched off — that
     * module only owns the shared thresholds and the chat alerts.
     */
    private object PvpIntelHud : ToggleableValueGroup(this, "PvpIntel", true) {

        private val corner by enumChoice("Corner", Corner.TOP_LEFT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 120f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val fontSize by float("FontSize", 7.5f, 5f..16f)

        private val showWinChance by boolean("WinChance", true)
        private val showPops by boolean("TotemPops", true)
        private val showGapples by boolean("Gapples", true)
        private val showArmor by boolean("Armor", true)
        private val showPing by boolean("Ping", true)
        private val showStatus by boolean("Status", true)
        private val showCombo by boolean("Combo", true)
        private val showHits by boolean("HitCount", true)
        private val showTeamWarning by boolean("TeamWarning", true)

        val bounds = PanelBounds()

        private const val PAD_X = 5f
        private const val PAD_Y = 4f
        private const val ROW_GAP = 1.5f
        private const val BAR_HEIGHT = 2f
        private const val BAR_GAP = 3f
        private const val LABEL_GAP = 4f
        private const val MIN_INNER_WIDTH = 62f

        private class Row(val label: String, val value: String, val argb: Int)

        @Suppress("unused")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val self = mc.player ?: return@handler
            val editing = editMode()
            val target = resolveCombatTarget() as? Player

            // While a screen is open the panel stands in for itself so it stays draggable.
            val intel = when {
                target != null -> TargetIntel.build(self, target, self.tickCount.toLong())
                editing -> TargetIntel.build(self, self, self.tickCount.toLong())
                else -> return@handler
            }
            val title = target?.name?.string ?: self.name.string

            val rows = buildRows(intel)
            if (rows.isEmpty() && !showWinChance) {
                return@handler
            }

            val ctx = event.context
            val px = fontSize
            val density = density(scale)
            val lineHeight = WatermarkFont.TEXT.lineHeight(px, density)

            var innerWidth = WatermarkFont.TEXT.width(title, px, density)
            if (showWinChance) {
                // The win line is a row too — measure it, or a long "chance 100%" spills out.
                innerWidth = maxOf(innerWidth, WatermarkFont.TEXT.width(winLabel(intel), px, density))
            }
            for (row in rows) {
                val w = WatermarkFont.TEXT.width(row.label, px, density) + LABEL_GAP +
                    WatermarkFont.TEXT.width(row.value, px, density)
                if (w > innerWidth) {
                    innerWidth = w
                }
            }
            innerWidth = innerWidth.coerceAtLeast(MIN_INNER_WIDTH)

            // The win block is a text line AND the bar under it; counting only the bar used to leave
            // the last row hanging outside the glass.
            val winBlock = if (showWinChance) lineHeight + ROW_GAP + BAR_HEIGHT + BAR_GAP else 0f
            val rowBlock = if (rows.isEmpty()) 0f else rows.size * (lineHeight + ROW_GAP) - ROW_GAP
            val width = PAD_X * 2f + innerWidth
            val height = PAD_Y * 2f + lineHeight + ROW_GAP + winBlock + rowBlock

            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.PVP_INTEL && editing) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)

            val glassSetup = requestGlass(ctx, event)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            drawGlassPanel(ctx, width, height, 1f, density, glassSetup)
            submitRows(ctx, intel, title, rows, px, density, lineHeight, innerWidth)

            ctx.pose().popMatrix()

            submitEditOverlay(ctx, bounds, DragTarget.PVP_INTEL)
        }

        private fun buildRows(intel: TargetIntel.TargetIntelSnapshot): List<Row> {
            val rows = ArrayList<Row>(8)

            if (showTeamWarning && intel.teamPartner != null) {
                rows += Row(txtTeaming, intel.teamPartner, TEAM_ARGB)
            }
            if (showPops) {
                rows += Row(txtPops, intel.pops.toString(), if (intel.pops > 0) WARN_ARGB else -1)
            }
            if (showGapples) {
                rows += Row(txtGapples, gappleText(intel), -1)
            }
            if (showArmor) {
                rows += armorRow(intel)
            }
            if (showHits) {
                rows += hitsRow(intel)
            }
            if (showCombo && intel.combo > 1) {
                rows += Row(txtCombo, "x${intel.combo}", GOOD_ARGB)
            }
            if (showPing) {
                rows += Row("ping", "${intel.ping}${txtMs}", -1)
            }
            if (showStatus) {
                statusRow(intel)?.let { rows += it }
            }
            return rows
        }

        /** The exchange so far: our landed hits against theirs, coloured by who is winning it. */
        private fun hitsRow(intel: TargetIntel.TargetIntelSnapshot): Row {
            val argb = when {
                intel.hitsByMe > intel.hitsToMe -> GOOD_ARGB
                intel.hitsByMe < intel.hitsToMe -> TEAM_ARGB
                else -> -1
            }
            return Row(txtHits, "${intel.hitsByMe} : ${intel.hitsToMe}", argb)
        }

        private fun winLabel(intel: TargetIntel.TargetIntelSnapshot): String =
            "$txtWin ${Math.round(intel.winChance.coerceIn(0f, 1f) * 100f)}%"

        /** "?" is the honest answer until we have actually seen them hold one. */
        private fun gappleText(intel: TargetIntel.TargetIntelSnapshot): String =
            if (intel.gapples.known) "~${intel.gapples.count}" else "?"

        /** Live flags worth reacting to, or null when there is nothing to say. */
        private fun statusRow(intel: TargetIntel.TargetIntelSnapshot): Row? {
            val status = buildList {
                if (intel.eating) add(txtEating)
                if (intel.carriesShield) add(txtShield)
            }
            return if (status.isEmpty()) null else Row("", status.joinToString(", "), WARN_ARGB)
        }

        /** Worst-piece durability plus how that set compares with ours. */
        private fun armorRow(intel: TargetIntel.TargetIntelSnapshot): Row {
            val verdict = when (intel.armorVerdict) {
                TargetIntel.ArmorVerdict.BETTER -> txtBetter
                TargetIntel.ArmorVerdict.WORSE -> txtWorse
                TargetIntel.ArmorVerdict.EVEN -> txtEven
            }
            val argb = when (intel.armorVerdict) {
                TargetIntel.ArmorVerdict.BETTER -> WARN_ARGB
                TargetIntel.ArmorVerdict.WORSE -> GOOD_ARGB
                TargetIntel.ArmorVerdict.EVEN -> -1
            }
            return Row(txtArmor, "%.0f%% %s".format(intel.lowestArmorPct, verdict), argb)
        }

        private fun submitRows(
            ctx: GuiGraphicsExtractor,
            intel: TargetIntel.TargetIntelSnapshot,
            title: String,
            rows: List<Row>,
            px: Float,
            density: Float,
            lineHeight: Float,
            innerWidth: Float,
        ) {
            var textY = PAD_Y
            WatermarkFont.TEXT.submit(ctx, title, PAD_X, textY, px, density) { accentAt(0.25f, 1f) }
            textY += lineHeight + ROW_GAP

            if (showWinChance) {
                val pct = intel.winChance.coerceIn(0f, 1f)
                WatermarkFont.TEXT.submit(ctx, winLabel(intel), PAD_X, textY, px, density) { winArgb(pct) }
                textY += lineHeight + ROW_GAP

                // A thin bar under the number: instantly readable without parsing the digits.
                val filled = innerWidth * pct
                ctx.fill(
                    PAD_X.toInt(), textY.toInt(),
                    (PAD_X + innerWidth).toInt(), (textY + BAR_HEIGHT).toInt(),
                    scaleAlpha(BAR_TRACK_ARGB, 1f),
                )
                if (filled > 0.5f) {
                    ctx.fill(
                        PAD_X.toInt(), textY.toInt(),
                        (PAD_X + filled).toInt(), (textY + BAR_HEIGHT).toInt(),
                        winArgb(pct),
                    )
                }
                textY += BAR_HEIGHT + BAR_GAP
            }

            val labelArgb = scaleAlpha(infoColor.argb, 1f)
            for (row in rows) {
                if (row.label.isNotEmpty()) {
                    WatermarkFont.TEXT.submit(ctx, row.label, PAD_X, textY, px, density) { labelArgb }
                }
                // Right-aligned against the measured width: the column reads cleanly and no value
                // can ever run past the panel edge, however long the label before it is.
                val valueX = PAD_X + innerWidth - WatermarkFont.TEXT.width(row.value, px, density)
                WatermarkFont.TEXT.submit(ctx, row.value, valueX, textY, px, density) { row.argb }
                textY += lineHeight + ROW_GAP
            }
        }

        private fun winArgb(pct: Float): Int = when {
            pct >= 0.6f -> GOOD_ARGB
            pct >= 0.35f -> WARN_ARGB
            else -> TEAM_ARGB
        }

        private const val GOOD_ARGB = 0xFF6FE06F.toInt()
        private const val WARN_ARGB = 0xFFE0C060.toInt()
        private const val TEAM_ARGB = 0xFFE06060.toInt()
        private const val BAR_TRACK_ARGB = 0x40FFFFFF
    }

    // =============================================================================================
    // AutoSwap element (off-hand item ⇄ next swap item, with a swap button)
    // =============================================================================================

    private object AutoSwapHud : ToggleableValueGroup(this, "AutoSwap", true) {

        private const val GAP = 3f
        private const val ARROW_W = 13f
        private const val BTN_GAP = 3f
        private const val BTN_PAD_Y = 1.6f

        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 170f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val itemScale by float("ItemScale", 1f, 0.5f..3f)

        val bounds = PanelBounds()

        /** Swap button rect in gui (screen) units, for click + hover hit testing. */
        private val buttonBounds = PanelBounds()

        /** Clicking the swap button while a screen with a free cursor (e.g. inventory) is open. */
        @Suppress("unused")
        private val clickHandler = handler<MouseButtonEvent> { event ->
            if (event.button != 0 || event.action != 1 || !enabled || !ModuleAutoSwap.running) {
                return@handler
            }
            // Skip while the chat is open — there the panel is being dragged, not clicked.
            if (mc.screen == null || editMode()) {
                return@handler
            }
            if (buttonBounds.contains(mouseGuiX(), mouseGuiY())) {
                ModuleAutoSwap.hudRequestSwap()
            }
        }

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow || !ModuleAutoSwap.running) {
                return@handler
            }

            val ctx = event.context
            val editing = editMode()
            val snapshot = ModuleAutoSwap.hudSnapshot
            if (snapshot == null && !editing) {
                return@handler
            }
            val currentStack = snapshot?.current ?: net.minecraft.world.item.ItemStack.EMPTY
            val nextStack = snapshot?.next ?: net.minecraft.world.item.ItemStack.EMPTY

            val density = density(scale)
            val guiScaleF = mc.window.guiScale.toFloat().coerceAtLeast(1f)
            val iconFactor = Math.round(itemScale * guiScaleF).coerceAtLeast(1)
            val iconGuiScale = iconFactor / guiScaleF
            val iconLocal = 16f * iconGuiScale / scale
            val cellSize = iconLocal + 1.5f

            // Row geometry: [cell] gap [arrow] gap [cell]
            val arrowX0 = cellSize + GAP
            val arrowX1 = arrowX0 + ARROW_W
            val cell1X = arrowX1 + GAP
            val rowWidth = cell1X + cellSize

            // Button geometry below the row
            val labelPx = fontSize * 0.7f
            val labelHeight = WatermarkFont.BRAND.lineHeight(labelPx, density)
            val buttonY = cellSize + BTN_GAP
            val buttonHeight = labelHeight + BTN_PAD_Y * 2f

            val width = rowWidth
            val height = buttonY + buttonHeight

            // Dragging with the mouse while the chat is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.AUTO_SWAP && editing) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)
            buttonBounds.set(x, y + buttonY * scale, rowWidth * scale, buttonHeight * scale)

            val hovered = mc.screen != null && !editing &&
                buttonBounds.contains(mouseGuiX(), mouseGuiY())

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val cellTop = scaleAlpha(backgroundTop.argb, 1f)
            val cellBottom = scaleAlpha(backgroundBottom.argb, 1f)
            val arrowColor = accentAt(0.3f, 1f)
            val buttonAccent = accentAt(0.25f, 1f)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(-1f, -1f, width + 1f, height + 1f),
                ) { pose ->
                    val panelAt = { y0: Float, h: Float, vy: Float ->
                        lerpArgb(cellTop, cellBottom, ((vy - y0) / h).coerceIn(0f, 1f))
                    }
                    val drawRect = { rx0: Float, ry0: Float, rx1: Float, ry1: Float, radius: Float ->
                        val contour = buildContour(rx0, ry0, rx1, ry1, radius, 0f)
                        val contourFeather = buildContour(rx0, ry0, rx1, ry1, radius, feather)
                        emitFill(contour, (rx0 + rx1) * 0.5f, (ry0 + ry1) * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(panelAt(ry0, ry1 - ry0, vy))
                        }
                        emitRing(
                            contour, contourFeather,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(panelAt(ry0, ry1 - ry0, vy)) },
                            { vx, vy ->
                                addVertexWith2DPose(pose, vx, vy).setColor(panelAt(ry0, ry1 - ry0, vy) and 0xFFFFFF)
                            },
                        )
                    }
                    val emitQuad = { qx0: Float, qy0: Float, qx1: Float, qy1: Float, c: Int ->
                        addVertexWith2DPose(pose, qx0, qy0).setColor(c)
                        addVertexWith2DPose(pose, qx0, qy1).setColor(c)
                        addVertexWith2DPose(pose, qx1, qy1).setColor(c)
                        addVertexWith2DPose(pose, qx1, qy0).setColor(c)
                    }
                    val emitTri = { ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, c: Int ->
                        addVertexWith2DPose(pose, ax, ay).setColor(c)
                        addVertexWith2DPose(pose, bx, by).setColor(c)
                        addVertexWith2DPose(pose, cx, cy).setColor(c)
                        addVertexWith2DPose(pose, cx, cy).setColor(c)
                    }

                    // Two item cells
                    drawRect(0f, 0f, cellSize, cellSize, 2f)
                    drawRect(cell1X, 0f, cell1X + cellSize, cellSize, 2f)

                    // Double arrow (top → right, bottom → left) between the cells
                    val aLeft = arrowX0 + 1.5f
                    val aRight = arrowX1 - 1.5f
                    val midY = cellSize * 0.5f
                    val topY = midY - 2.2f
                    val botY = midY + 2.2f
                    val th = 0.55f
                    val headLen = 3.0f
                    val headH = 2.3f
                    emitQuad(aLeft, topY - th, aRight - headLen, topY + th, arrowColor)
                    emitTri(aRight - headLen, topY - headH, aRight - headLen, topY + headH, aRight, topY, arrowColor)
                    emitQuad(aLeft + headLen, botY - th, aRight, botY + th, arrowColor)
                    emitTri(aLeft + headLen, botY + headH, aLeft + headLen, botY - headH, aLeft, botY, arrowColor)

                    // Swap button background (brightens on hover via an accent overlay)
                    drawRect(0f, buttonY, rowWidth, buttonY + buttonHeight, 2f)
                    if (hovered) {
                        val glow = scaleAlpha(buttonAccent, 0.16f)
                        val contour = buildContour(0f, buttonY, rowWidth, buttonY + buttonHeight, 2f, 0f)
                        emitFill(contour, rowWidth * 0.5f, buttonY + buttonHeight * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setColor(glow)
                        }
                    }
                }
            )

            // Button label: the trigger key (like ItemBinds), centered, accent
            val label = ModuleAutoSwap.hudKeyLabel.ifEmpty { "—" }
            val labelW = WatermarkFont.BRAND.width(label, labelPx, density)
            WatermarkFont.BRAND.submit(
                ctx, label,
                (rowWidth - labelW) * 0.5f,
                buttonY + (buttonHeight - labelHeight) * 0.5f,
                labelPx, density,
            ) { buttonAccent }

            ctx.pose().popMatrix()

            // Item icons at a whole device-pixel factor, snapped to the grid
            val itemSizeGui = 16f * iconGuiScale
            val inset = (cellSize * scale - itemSizeGui) * 0.5f
            val drawIcon = { cellLocalX: Float, stack: net.minecraft.world.item.ItemStack ->
                if (!stack.isEmpty) {
                    val gx = Math.round((x + cellLocalX * scale + inset) * guiScaleF) / guiScaleF
                    val gy = Math.round((y + inset) * guiScaleF) / guiScaleF
                    ctx.pose().pushMatrix()
                    ctx.pose().translate(gx, gy)
                    ctx.pose().scale(iconGuiScale, iconGuiScale)
                    ctx.item(stack, 0, 0)
                    ctx.pose().popMatrix()
                }
            }
            drawIcon(0f, currentStack)
            drawIcon(cell1X, nextStack)
        }
    }

    // =============================================================================================
    // Scoreboard element (restyled server sidebar)
    // =============================================================================================

    private object ScoreboardHud : ToggleableValueGroup(this, "Scoreboard", true) {

        private const val PAD_X = 7f
        private const val PAD_Y = 5f
        private const val ROW_GAP = 2.5f
        private const val TITLE_GAP = 4f
        private const val MAX_ROWS = 15
        private const val SIZE_SMOOTH_SPEED = 12f

        private val corner by enumChoice("Corner", Corner.TOP_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 120f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val showScores by boolean("ShowScores", false)

        val bounds = PanelBounds()

        private var smoothWidth = -1f
        private var lastSizeNs = 0L

        /** A styled text run: vanilla color codes from the server are preserved. */
        private class Run(val text: String, val argb: Int)

        /**
         * Flattens a sidebar line into colored runs.
         *
         * The line goes through [StringDecomposer] first, so legacy "§x" codes that servers
         * embed as literal text become real styles (otherwise '§' renders as a fallback '?'
         * followed by the code character), and then through [ModuleNameProtect], which swaps
         * the player's name for its replacement in its own colour. Vanilla gets that for free
         * from the Font mixins; our glyph renderer never touches the vanilla font, so the
         * sidebar has to ask for it explicitly.
         */
        private fun flatten(component: net.minecraft.network.chat.Component): List<Run> {
            val degenerated = FormattedCharSequence { sink ->
                StringDecomposer.iterateFormatted(component, net.minecraft.network.chat.Style.EMPTY, sink)
            }
            val runs = ArrayList<Run>(4)
            val builder = StringBuilder()
            var argb = 0
            ModuleNameProtect.wrap(degenerated).accept { _, style, codePoint ->
                val color = 0xFF000000.toInt() or (style.color?.value ?: 0xFFFFFF)
                if (color != argb && builder.isNotEmpty()) {
                    runs += Run(builder.toString(), argb)
                    builder.setLength(0)
                }
                argb = color
                builder.appendCodePoint(codePoint)
                true
            }
            if (builder.isNotEmpty()) {
                runs += Run(builder.toString(), argb)
            }
            return runs
        }

        /**
         * Run width: our Inter when every glyph is covered, otherwise the vanilla font (which
         * resolves server resource-pack/custom font glyphs).
         */
        private fun runWidth(run: Run, px: Float, density: Float): Float =
            if (WatermarkFont.TEXT.canRender(run.text, px, density)) {
                WatermarkFont.TEXT.width(run.text, px, density)
            } else {
                mc.font.width(run.text) * (px / 9f)
            }

        /** Draws a run with Inter, or with the vanilla font when glyphs aren't covered. */
        @Suppress("LongParameterList")
        private fun drawRun(
            ctx: GuiGraphicsExtractor,
            run: Run,
            x: Float,
            y: Float,
            px: Float,
            density: Float,
            bold: Boolean,
        ): Float {
            val face = if (bold) WatermarkFont.BRAND else WatermarkFont.TEXT
            if (face.canRender(run.text, px, density)) {
                val color = run.argb
                face.submit(ctx, run.text, x, y, px, density) { color }
                return face.width(run.text, px, density)
            }

            // Vanilla fallback: handles custom server fonts and exotic glyphs
            val factor = px / 9f
            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(factor, factor)
            ctx.text(mc.font, run.text, 0, 0, run.argb)
            ctx.pose().popMatrix()
            return mc.font.width(run.text) * factor
        }

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val scoreboard = world.scoreboard
            val objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR)
                ?: return@handler

            // Same data the vanilla sidebar shows: visible entries, score-descending, capped
            val entries = scoreboard.listPlayerScores(objective)
                .filter { !it.isHidden }
                .sortedWith(compareByDescending<net.minecraft.world.scores.PlayerScoreEntry> { it.value() }
                    .thenBy { it.owner() })
                .take(MAX_ROWS)

            val titleRuns = flatten(objective.displayName)
            val rowRuns = entries.map { entry ->
                val team = scoreboard.getPlayersTeam(entry.owner())
                val name = net.minecraft.world.scores.PlayerTeam.formatNameForTeam(
                    team,
                    entry.display() ?: entry.ownerName(),
                )
                flatten(name) to entry.value()
            }

            val px = fontSize * 0.92f
            val density = density(scale)
            val textHeight = WatermarkFont.TEXT.lineHeight(px, density)

            // Layout (smooth width; scores measured with digit-stable samples)
            fun runsWidth(runs: List<Run>): Float {
                var total = 0f
                for (run in runs) {
                    total += runWidth(run, px, density)
                }
                return total
            }

            var innerWidth = maxOf(60f, runsWidth(titleRuns))
            val scoreWidths = FloatArray(rowRuns.size)
            for ((index, row) in rowRuns.withIndex()) {
                var rowWidth = runsWidth(row.first)
                if (showScores) {
                    val sample = String(CharArray(row.second.toString().length) { '0' })
                    scoreWidths[index] = WatermarkFont.TEXT.width(sample, px, density)
                    rowWidth += 10f + scoreWidths[index]
                }
                innerWidth = maxOf(innerWidth, rowWidth)
            }
            innerWidth = Math.round(innerWidth * 2f) / 2f

            val targetWidth = PAD_X * 2f + innerWidth
            val height = PAD_Y * 2f + textHeight + TITLE_GAP +
                rowRuns.size * (textHeight + ROW_GAP) - ROW_GAP

            val nowNs = System.nanoTime()
            val dt = if (lastSizeNs == 0L) {
                1f / 60f
            } else {
                ((nowNs - lastSizeNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastSizeNs = nowNs
            if (smoothWidth < 0f) {
                smoothWidth = targetWidth
            }
            smoothWidth += (targetWidth - smoothWidth) * (1f - kotlin.math.exp(-SIZE_SMOOTH_SPEED * dt))
            if (kotlin.math.abs(targetWidth - smoothWidth) < 0.1f) {
                smoothWidth = targetWidth
            }
            val width = smoothWidth

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.SCOREBOARD && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)

            val glassSetup = requestGlass(ctx, event)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            drawGlassPanel(ctx, width, height, 1f, density, glassSetup)

            // Title, centered, in the bold face (server colors preserved)
            val titleWidth = runsWidth(titleRuns)
            var titleX = (width - titleWidth) * 0.5f
            for (run in titleRuns) {
                titleX += drawRun(ctx, run, titleX, PAD_Y, px, density, bold = true)
            }

            // Rows
            var rowY = PAD_Y + textHeight + TITLE_GAP
            for ((index, row) in rowRuns.withIndex()) {
                var runX = PAD_X
                for (run in row.first) {
                    runX += drawRun(ctx, run, runX, rowY, px, density, bold = false)
                }
                if (showScores) {
                    val scoreText = row.second.toString()
                    val scoreColor = scaleAlpha(accentTo.argb, 0.9f)
                    WatermarkFont.TEXT.submit(
                        ctx, scoreText,
                        width - PAD_X - WatermarkFont.TEXT.width(scoreText, px, density), rowY, px, density,
                    ) { scoreColor }
                }
                rowY += textHeight + ROW_GAP
            }

            ctx.pose().popMatrix()
        }
    }

    // =============================================================================================
    // Keystrokes element
    // =============================================================================================

    private object KeystrokesHud : ToggleableValueGroup(this, "Keystrokes", true) {

        private const val KEY_SIZE = 17f
        private const val GAP = 2f
        private const val SPACE_HEIGHT = 8f
        private const val MOUSE_HEIGHT = 13f

        private val corner by enumChoice("Corner", Corner.TOP_RIGHT)
        private var offsetX by float("OffsetX", 8f, 0f..1000f)
        private var offsetY by float("OffsetY", 150f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val showSpace by boolean("ShowSpace", true)
        private val showMouse by boolean("ShowMouse", true)
        private val showCps by boolean("ShowCPS", true)

        val bounds = PanelBounds()

        // Smooth press highlight per cell + click times for CPS
        private val pressFactors = HashMap<String, Float>()
        private val leftClicks = ArrayDeque<Long>()
        private val rightClicks = ArrayDeque<Long>()
        private var lastFrameNs = 0L

        private class Cell(
            val id: String,
            val label: String,
            val pressed: Boolean,
            val x: Float,
            val y: Float,
            val width: Float,
            val height: Float,
            val cps: Int = -1,
        )

        @Suppress("unused")
        private val clickHandler = handler<MouseButtonEvent> { event ->
            if (event.action == 1 && mc.screen == null) {
                when (event.button) {
                    0 -> leftClicks.addLast(System.currentTimeMillis())
                    1 -> rightClicks.addLast(System.currentTimeMillis())
                }
            }
        }

        /** Always-English key label from the key identifier ("key.keyboard.w" → "W"). */
        private fun keyLabel(mapping: net.minecraft.client.KeyMapping): String {
            val id = mapping.saveString()
                .removePrefix("key.keyboard.")
                .removePrefix("key.mouse.")
            return id.substringAfterLast('.').uppercase().take(3)
        }

        private fun cpsOf(clicks: ArrayDeque<Long>, now: Long): Int {
            while (clicks.isNotEmpty() && now - clicks.first() > 1000L) {
                clicks.removeFirst()
            }
            return clicks.size
        }

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) {
                return@handler
            }

            val ctx = event.context
            val now = System.currentTimeMillis()
            val density = density(scale)
            val options = mc.options

            // Layout: [W] / [A][S][D] / [space] / [LMB][RMB]
            val width = KEY_SIZE * 3f + GAP * 2f
            val cells = ArrayList<Cell>(7)
            var layoutY = 0f
            cells += Cell("W", keyLabel(options.keyUp), options.keyUp.isDown, KEY_SIZE + GAP, layoutY, KEY_SIZE, KEY_SIZE)
            layoutY += KEY_SIZE + GAP
            cells += Cell("A", keyLabel(options.keyLeft), options.keyLeft.isDown, 0f, layoutY, KEY_SIZE, KEY_SIZE)
            cells += Cell("S", keyLabel(options.keyDown), options.keyDown.isDown, KEY_SIZE + GAP, layoutY, KEY_SIZE, KEY_SIZE)
            cells += Cell("D", keyLabel(options.keyRight), options.keyRight.isDown, (KEY_SIZE + GAP) * 2f, layoutY, KEY_SIZE, KEY_SIZE)
            layoutY += KEY_SIZE + GAP
            if (showSpace) {
                cells += Cell("Space", "—", options.keyJump.isDown, 0f, layoutY, width, SPACE_HEIGHT)
                layoutY += SPACE_HEIGHT + GAP
            }
            if (showMouse) {
                val mouseWidth = (width - GAP) * 0.5f
                val lcps = cpsOf(leftClicks, now)
                val rcps = cpsOf(rightClicks, now)
                cells += Cell("LMB", txtLmb, options.keyAttack.isDown, 0f, layoutY, mouseWidth, MOUSE_HEIGHT, lcps)
                cells += Cell("RMB", txtRmb, options.keyUse.isDown, mouseWidth + GAP, layoutY, mouseWidth, MOUSE_HEIGHT, rcps)
                layoutY += MOUSE_HEIGHT + GAP
            }
            val height = layoutY - GAP

            // Smooth press factors (fast in, slower out)
            val nowNs = System.nanoTime()
            val dt = if (lastFrameNs == 0L) {
                1f / 60f
            } else {
                ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastFrameNs = nowNs
            for (cell in cells) {
                val current = pressFactors.getOrDefault(cell.id, 0f)
                val target = if (cell.pressed) 1f else 0f
                val speed = if (cell.pressed) 28f else 12f
                val next = current + (target - current) * (1f - kotlin.math.exp(-speed * dt))
                pressFactors[cell.id] = if (kotlin.math.abs(next - target) < 0.01f) target else next
            }

            // Dragging with the mouse while a screen is open
            val screenWidth = mc.window.guiScaledWidth.toFloat()
            val screenHeight = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.KEYSTROKES && editMode()) {
                val newX = mouseGuiX() - grabDX
                val newY = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenWidth - newX - width * scale else newX).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenHeight - newY - height * scale else newY).coerceIn(0f, 1000f)
            }

            val x = if (corner.isRight) screenWidth - offsetX - width * scale else offsetX
            val y = if (corner.isBottom) screenHeight - offsetY - height * scale else offsetY
            bounds.set(x, y, width * scale, height * scale)

            val glassSetup = requestGlass(ctx, event)
            val labelPx = fontSize * 0.72f

            for (cell in cells) {
                val press = pressFactors.getOrDefault(cell.id, 0f)

                ctx.pose().pushMatrix()
                ctx.pose().translate(x + cell.x * scale, y + cell.y * scale)
                ctx.pose().scale(scale, scale)

                drawGlassPanel(ctx, cell.width, cell.height, 1f, density, glassSetup)

                // Press highlight: accent ink filling the key
                if (press > 0.01f) {
                    submitPressOverlay(ctx, cell.width, cell.height, density, press)
                }

                // Label (+ CPS on the mouse buttons), centered
                val label = if (showCps && cell.cps >= 0 && cell.cps > 0) "${cell.label} ${cell.cps}" else cell.label
                val labelWidth = WatermarkFont.BRAND.width(label, labelPx, density)
                val labelHeight = WatermarkFont.BRAND.lineHeight(labelPx, density)
                if (cell.id != "Space") {
                    val labelArgb = lerpArgb(scaleAlpha(-1, 0.85f), -1, press)
                    WatermarkFont.BRAND.submit(
                        ctx, label,
                        (cell.width - labelWidth) * 0.5f,
                        (cell.height - labelHeight) * 0.5f,
                        labelPx, density,
                    ) { labelArgb }
                } else {
                    // The space bar shows a small centered dash instead of text
                    val dashWidth = cell.width * 0.25f
                    val dashColor = lerpArgb(scaleAlpha(-1, 0.55f), -1, press)
                    ctx.guiRenderState.addGuiElement(
                        WatermarkGuiElement(
                            RenderPipelines.GUI,
                            TextureSetup.noTexture(),
                            Matrix3x2f(ctx.pose()),
                            ctx.scissorStack.peek(),
                            ctx.watermarkBounds(0f, 0f, cell.width, cell.height),
                        ) { pose ->
                            val dashX = (cell.width - dashWidth) * 0.5f
                            val dashY = cell.height * 0.5f - 0.6f
                            addVertexWith2DPose(pose, dashX, dashY).setColor(dashColor)
                            addVertexWith2DPose(pose, dashX, dashY + 1.2f).setColor(dashColor)
                            addVertexWith2DPose(pose, dashX + dashWidth, dashY + 1.2f).setColor(dashColor)
                            addVertexWith2DPose(pose, dashX + dashWidth, dashY).setColor(dashColor)
                        }
                    )
                }

                ctx.pose().popMatrix()
            }
        }

        /** Accent overlay filling the key while pressed, fading out smoothly on release. */
        private fun submitPressOverlay(
            ctx: GuiGraphicsExtractor,
            width: Float,
            height: Float,
            density: Float,
            press: Float,
        ) {
            val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)
            val r = radius.coerceIn(0f, minOf(width, height) * 0.5f)
            val argb = scaleAlpha(accentAt(0.3f, 1f), 0.38f * press)
            val contour = buildContour(0f, 0f, width, height, r, 0f)
            val contourFeather = buildContour(0f, 0f, width, height, r, feather)

            ctx.guiRenderState.addGuiElement(
                WatermarkGuiElement(
                    RenderPipelines.GUI,
                    TextureSetup.noTexture(),
                    Matrix3x2f(ctx.pose()),
                    ctx.scissorStack.peek(),
                    ctx.watermarkBounds(-1f, -1f, width + 1f, height + 1f),
                ) { pose ->
                    emitFill(contour, width * 0.5f, height * 0.5f) { vx, vy ->
                        addVertexWith2DPose(pose, vx, vy).setColor(argb)
                    }
                    emitRing(
                        contour, contourFeather,
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb) },
                        { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb and 0xFFFFFF) },
                    )
                }
            )
        }
    }

    // =============================================================================================
    // Shared glass panel renderer (own render states on the vanilla GUI pipeline)
    // =============================================================================================

    /**
     * Submits a glass panel at local (0,0)–(width,height): optional blurred-background fill,
     * concentric feathered drop shadow, vertical-gradient body, AA edge feather, optional
     * logo glow and section separators.
     */
    @Suppress("LongParameterList", "detekt:LongMethod")
    private fun drawGlassPanel(
        ctx: GuiGraphicsExtractor,
        width: Float,
        height: Float,
        alphaF: Float,
        density: Float,
        glassSetup: TextureSetup?,
        glowCenterX: Float = 0f,
        glowCenterY: Float = 0f,
        glowRadius: Float = 0f,
        glowColor: Int = 0,
        separators: List<Float> = emptyList(),
    ) {
        val r = radius.coerceIn(0f, minOf(width, height) * 0.5f)

        // One device pixel in local units — used for the antialiasing feather
        val feather = (1f / density.coerceAtLeast(0.01f)).coerceIn(0.2f, 1.5f)

        // With glass under it, the body tint can be lighter
        val bodyAlpha = (if (glassSetup != null) 0.7f else 1f) * alphaF
        val top = scaleAlpha(backgroundTop.argb, bodyAlpha)
        val bottom = scaleAlpha(backgroundBottom.argb, bodyAlpha)
        val bodyColorAt = { yy: Float -> lerpArgb(top, bottom, (yy / height).coerceIn(0f, 1f)) }
        val shadowMargin = SHADOW_SPREAD + 2f

        // Smooth contour rings of the panel shape, shared by all layers
        val edge = buildContour(0f, 0f, width, height, r, 0f)
        val edgeFeather = buildContour(0f, 0f, width, height, r, feather)

        // Glass: blurred background sampled only inside the rounded panel
        if (glassSetup != null) {
            val poseSnapshot = Matrix3x2f(ctx.pose())
            val guiScale = mc.window.guiScale.toFloat()
            val texWidth = WatermarkGlass.textureWidth.toFloat()
            val texHeight = WatermarkGlass.textureHeight.toFloat()
            if (texWidth > 0f && texHeight > 0f) {
                // Local → device-pixel → UV (the render target texture is y-flipped)
                val uAt = { lx: Float -> (poseSnapshot.m00 * lx + poseSnapshot.m20) * guiScale / texWidth }
                val vAt = { ly: Float -> 1f - (poseSnapshot.m11 * ly + poseSnapshot.m21) * guiScale / texHeight }
                val tint = scaleAlpha(-1, alphaF)

                ctx.guiRenderState.addGuiElement(
                    WatermarkGuiElement(
                        RenderPipelines.GUI_TEXTURED,
                        glassSetup,
                        poseSnapshot,
                        ctx.scissorStack.peek(),
                        ctx.watermarkBounds(0f, 0f, width, height),
                    ) { pose ->
                        emitFill(edge, width * 0.5f, height * 0.5f) { vx, vy ->
                            addVertexWith2DPose(pose, vx, vy).setUv(uAt(vx), vAt(vy)).setColor(tint)
                        }
                    }
                )
            }
        }

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI,
                TextureSetup.noTexture(),
                Matrix3x2f(ctx.pose()),
                ctx.scissorStack.peek(),
                ctx.watermarkBounds(-shadowMargin, -shadowMargin, width + shadowMargin, height + shadowMargin),
            ) { pose ->
                // Soft drop shadow: concentric feathered rings with quadratic falloff.
                // Starts just outside the AA feather so it never thickens the edge itself.
                if (shadowOpacity > 0f) {
                    val shadowAlphaAt = { d: Float ->
                        val falloff = 1f - (d / SHADOW_SPREAD).coerceIn(0f, 1f)
                        shadowOpacity * 0.45f * falloff * falloff * alphaF
                    }
                    var innerRing = buildContour(0f, 0f, width, height, r, feather)
                    var innerD = 0f
                    for (ring in 1..SHADOW_RINGS) {
                        val outerD = SHADOW_SPREAD * ring / SHADOW_RINGS
                        val outerRing = buildContour(0f, 0f, width, height, r, feather + outerD)
                        val innerColor = scaleAlpha(0xFF000000.toInt(), shadowAlphaAt(innerD))
                        val outerColor = scaleAlpha(0xFF000000.toInt(), shadowAlphaAt(outerD))
                        emitRing(
                            innerRing, outerRing,
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(innerColor) },
                            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(outerColor) },
                        )
                        innerRing = outerRing
                        innerD = outerD
                    }
                }

                // Body tint with vertical gradient (over the glass, if any)
                emitFill(edge, width * 0.5f, height * 0.5f) { vx, vy ->
                    addVertexWith2DPose(pose, vx, vy).setColor(bodyColorAt(vy))
                }

                // Antialiasing feather: one device pixel fading to transparent outside the edge
                emitRing(
                    edge, edgeFeather,
                    { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(bodyColorAt(vy)) },
                    { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(bodyColorAt(vy) and 0xFFFFFF) },
                )

                // Soft radial glow behind the droplet
                if (glowColor != 0) {
                    radialGlow(pose, glowCenterX, glowCenterY, glowRadius, glowColor)
                }

                // Section separators
                if (separators.isNotEmpty()) {
                    val separatorColor = scaleAlpha(0xFFFFFFFF.toInt(), 0.16f * alphaF)
                    val separatorTop = height * 0.28f
                    val separatorBottom = height * 0.72f
                    val separatorWidth = 0.8f
                    for (separatorX in separators) {
                        addVertexWith2DPose(pose, separatorX, separatorTop).setColor(separatorColor)
                        addVertexWith2DPose(pose, separatorX, separatorBottom).setColor(separatorColor)
                        addVertexWith2DPose(pose, separatorX + separatorWidth, separatorBottom)
                            .setColor(separatorColor)
                        addVertexWith2DPose(pose, separatorX + separatorWidth, separatorTop).setColor(separatorColor)
                    }
                }
            }
        )
    }

    // --- Contour geometry ------------------------------------------------------------------------

    /**
     * Builds the rounded-rect contour as a closed point loop (x/y interleaved): four corner
     * arcs sampled with [CORNER_SEGMENTS] steps each, walked with decreasing angles so that
     * fans and rings built from it match the GUI quad winding. [offset] expands (positive)
     * or insets (negative) the whole contour; [dy] shifts it vertically.
     */
    @Suppress("LongParameterList")
    private fun buildContour(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        r: Float,
        offset: Float,
        dy: Float = 0f,
    ): FloatArray {
        val rr = (r + offset).coerceAtLeast(0.01f)
        val corners = floatArrayOf(
            x0 + r, y0 + r, 270f, 180f, // top-left
            x0 + r, y1 - r, 180f, 90f, // bottom-left
            x1 - r, y1 - r, 90f, 0f, // bottom-right
            x1 - r, y0 + r, 360f, 270f, // top-right
        )

        val points = FloatArray((CORNER_SEGMENTS + 1) * 4 * 2)
        var index = 0
        for (corner in 0 until 4) {
            val cx = corners[corner * 4]
            val cy = corners[corner * 4 + 1] + dy
            val from = corners[corner * 4 + 2]
            val step = (corners[corner * 4 + 3] - from) / CORNER_SEGMENTS
            for (i in 0..CORNER_SEGMENTS) {
                val angle = Math.toRadians((from + step * i).toDouble())
                points[index++] = cx + rr * cos(angle).toFloat()
                points[index++] = cy + rr * sin(angle).toFloat()
            }
        }
        return points
    }

    /** Fan fill of a closed contour from its center; [vertexAt] writes one full vertex. */
    private inline fun emitFill(
        points: FloatArray,
        centerX: Float,
        centerY: Float,
        crossinline vertexAt: (Float, Float) -> Unit,
    ) {
        val count = points.size / 2
        for (i in 0 until count) {
            val j = (i + 1) % count
            vertexAt(centerX, centerY)
            vertexAt(points[i * 2], points[i * 2 + 1])
            vertexAt(points[j * 2], points[j * 2 + 1])
            vertexAt(points[j * 2], points[j * 2 + 1])
        }
    }

    /**
     * Points along an arc as an x/y interleaved open loop. Walked from [fromDeg] to [toDeg]; pass
     * a decreasing pair so fans and strips built from it match the GUI quad winding, exactly like
     * [buildContour]. Angles are screen angles: 0° is three o'clock, growing clockwise, so twelve
     * o'clock is -90°.
     */
    @Suppress("LongParameterList")
    private fun arcPoints(
        cx: Float,
        cy: Float,
        r: Float,
        fromDeg: Float,
        toDeg: Float,
        segments: Int,
    ): FloatArray {
        val points = FloatArray((segments + 1) * 2)
        val step = (toDeg - fromDeg) / segments
        var index = 0
        for (i in 0..segments) {
            val angle = Math.toRadians((fromDeg + step * i).toDouble())
            points[index++] = cx + r * cos(angle).toFloat()
            points[index++] = cy + r * sin(angle).toFloat()
        }
        return points
    }

    /** Open quad strip between two matching arcs (inner→outer) — [emitRing] without the wrap. */
    private inline fun emitStrip(
        inner: FloatArray,
        outer: FloatArray,
        crossinline vertexInner: (Float, Float) -> Unit,
        crossinline vertexOuter: (Float, Float) -> Unit,
    ) {
        for (i in 0 until inner.size / 2 - 1) {
            val j = i + 1
            vertexInner(inner[i * 2], inner[i * 2 + 1])
            vertexOuter(outer[i * 2], outer[i * 2 + 1])
            vertexOuter(outer[j * 2], outer[j * 2 + 1])
            vertexInner(inner[j * 2], inner[j * 2 + 1])
        }
    }

    /**
     * [emitStrip] with the normalized position along the strip (0 at the first point, 1 at the
     * last) handed to the callbacks — lets a strip fade out towards its two ends.
     */
    private inline fun emitStripAlong(
        inner: FloatArray,
        outer: FloatArray,
        crossinline vertexInner: (Float, Float, Float) -> Unit,
        crossinline vertexOuter: (Float, Float, Float) -> Unit,
    ) {
        val count = inner.size / 2
        if (count < 2) {
            return
        }
        val last = (count - 1).toFloat()
        for (i in 0 until count - 1) {
            val j = i + 1
            val ti = i / last
            val tj = j / last
            vertexInner(inner[i * 2], inner[i * 2 + 1], ti)
            vertexOuter(outer[i * 2], outer[i * 2 + 1], ti)
            vertexOuter(outer[j * 2], outer[j * 2 + 1], tj)
            vertexInner(inner[j * 2], inner[j * 2 + 1], tj)
        }
    }

    /** Quad strip between two matching contours (inner→outer), e.g. feathers and shadow rings. */
    private inline fun emitRing(
        inner: FloatArray,
        outer: FloatArray,
        crossinline vertexInner: (Float, Float) -> Unit,
        crossinline vertexOuter: (Float, Float) -> Unit,
    ) {
        val count = inner.size / 2
        for (i in 0 until count) {
            val j = (i + 1) % count
            vertexInner(inner[i * 2], inner[i * 2 + 1])
            vertexOuter(outer[i * 2], outer[i * 2 + 1])
            vertexOuter(outer[j * 2], outer[j * 2 + 1])
            vertexInner(inner[j * 2], inner[j * 2 + 1])
        }
    }

    /**
     * "Expensive" bar dressing: a dark hairline ring along the track edge plus an inner
     * top shadow — the track reads as inset glass instead of a flat rectangle.
     */
    @Suppress("LongParameterList")
    private fun VertexConsumer.barDressing(
        pose: Matrix3x2f,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        r: Float,
        alphaF: Float,
    ) {
        // Hairline ring
        val ring = scaleAlpha(0xFF000000.toInt(), 0.50f * alphaF)
        emitRing(
            buildContour(x0, y0, x1, y1, r, -0.6f),
            buildContour(x0, y0, x1, y1, r, 0f),
            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(ring) },
            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(ring) },
        )

        // Inner top shadow, fading downwards
        val shadowTop = scaleAlpha(0xFF000000.toInt(), 0.30f * alphaF)
        val shadowBottom = shadowTop and 0xFFFFFF
        val inset = r * 0.7f
        addVertexWith2DPose(pose, x0 + inset, y0 + 0.5f).setColor(shadowTop)
        addVertexWith2DPose(pose, x0 + inset, y0 + 1.6f).setColor(shadowBottom)
        addVertexWith2DPose(pose, x1 - inset, y0 + 1.6f).setColor(shadowBottom)
        addVertexWith2DPose(pose, x1 - inset, y0 + 0.5f).setColor(shadowTop)
    }

    /** Bright sheen line along the top of a bar fill — gives the fill a glassy volume. */
    @Suppress("LongParameterList")
    private fun VertexConsumer.fillSheen(
        pose: Matrix3x2f,
        x0: Float,
        y0: Float,
        x1: Float,
        r: Float,
        alphaF: Float,
    ) {
        if (x1 - x0 < 2f) {
            return
        }
        val inset = r * 0.7f
        val sheenTop = scaleAlpha(0xFFFFFFFF.toInt(), 0.16f * alphaF)
        val sheenBottom = scaleAlpha(0xFFFFFFFF.toInt(), 0.02f * alphaF)
        addVertexWith2DPose(pose, x0 + inset, y0 + 0.5f).setColor(sheenTop)
        addVertexWith2DPose(pose, x0 + inset, y0 + 1.4f).setColor(sheenBottom)
        addVertexWith2DPose(pose, x1 - inset, y0 + 1.4f).setColor(sheenBottom)
        addVertexWith2DPose(pose, x1 - inset, y0 + 0.5f).setColor(sheenTop)
    }

    /** A solid antialiased disc — used as the round cap of a stroked arc. */
    @Suppress("LongParameterList")
    private fun VertexConsumer.emitDisc(
        pose: Matrix3x2f,
        cx: Float,
        cy: Float,
        radius: Float,
        feather: Float,
        argb: Int,
    ) {
        if (radius <= 0f) {
            return
        }
        val core = buildContour(cx - radius, cy - radius, cx + radius, cy + radius, radius, -feather * 0.5f)
        val edge = buildContour(cx - radius, cy - radius, cx + radius, cy + radius, radius, feather * 0.5f)
        emitFill(core, cx, cy) { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb) }
        emitRing(
            core, edge,
            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb) },
            { vx, vy -> addVertexWith2DPose(pose, vx, vy).setColor(argb and 0xFFFFFF) },
        )
    }

    /** Soft radial glow: a circle fan fading from [argb] at the center to transparent at the rim. */
    @Suppress("LongParameterList")
    private fun VertexConsumer.radialGlow(
        pose: Matrix3x2f,
        cx: Float,
        cy: Float,
        radius: Float,
        argb: Int,
        segments: Int = 24,
    ) {
        if (radius <= 0f) {
            return
        }
        val edge = argb and 0xFFFFFF // alpha 0, same hue
        val step = -360f / segments // decreasing angle keeps the GUI quad winding
        for (i in 0 until segments) {
            val a = Math.toRadians((step * i).toDouble())
            val b = Math.toRadians((step * (i + 1)).toDouble())
            val ax = cx + radius * cos(a).toFloat()
            val ay = cy + radius * sin(a).toFloat()
            val bx = cx + radius * cos(b).toFloat()
            val by = cy + radius * sin(b).toFloat()

            addVertexWith2DPose(pose, cx, cy).setColor(argb)
            addVertexWith2DPose(pose, ax, ay).setColor(edge)
            addVertexWith2DPose(pose, bx, by).setColor(edge)
            addVertexWith2DPose(pose, bx, by).setColor(edge)
        }
    }

    // --- Logo --------------------------------------------------------------------------------

    private const val LOGO_RESOURCE = "resources/liquidbounce/icon_64x64.png"

    /** The droplet logo as a single textured quad. */
    private fun submitLogo(ctx: GuiGraphicsExtractor, x: Float, y: Float, size: Float, alphaF: Float) {
        val texture = logoTexture() ?: return
        val tint = scaleAlpha(-1, alphaF)

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI_TEXTURED,
                texture,
                Matrix3x2f(ctx.pose()),
                ctx.scissorStack.peek(),
                ctx.watermarkBounds(x, y, x + size, y + size),
            ) { pose ->
                addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(tint)
                addVertexWith2DPose(pose, x, y + size).setUv(0f, 1f).setColor(tint)
                addVertexWith2DPose(pose, x + size, y + size).setUv(1f, 1f).setColor(tint)
                addVertexWith2DPose(pose, x + size, y).setUv(1f, 0f).setColor(tint)
            }
        )
    }

    private var logoSetup: TextureSetup? = null
    private var logoFailed = false

    /** Loads the packaged droplet icon once (lazily, on the render thread) into a GPU texture. */
    private fun logoTexture(): TextureSetup? {
        logoSetup?.let { return it }
        if (logoFailed) {
            return null
        }

        return try {
            val stream = javaClass.classLoader.getResourceAsStream(LOGO_RESOURCE)
                ?: error("missing classpath resource $LOGO_RESOURCE")
            val image = stream.use(NativeImage::read)
            val texture = DynamicTexture(Suppliers.ofInstance("WatermarkLogo"), image)
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            ).also { logoSetup = it }
        } catch (e: Throwable) {
            logoFailed = true
            logger.error("Failed to load watermark logo texture", e)
            null
        }
    }
}
