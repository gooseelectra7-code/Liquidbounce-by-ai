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

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAimbot
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.HellhitClickGuiSettings
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawGradientRoundedRect
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.hud.HudAccent
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.combat.intel.TargetIntel.nametagHealth
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native HUD in the "Hellhit" design language — the same near-black slabs, hairline
 * borders, Inter type and accent (Themes panel of the Hellhit ClickGUI) as
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.HellhitClickGuiScreen].
 *
 * Deliberately NOT a restyle of [ModuleHud2]: layouts are this module's own. Rendering
 * uses the flat Render2D primitives + [WatermarkFont] — no glass tessellation, no blur.
 * Animation discipline matches the ClickGUI: easeInOutCubic only, alpha fades and
 * scissor reveals; glyphs never slide (pixel-snapped text turns sub-unit motion into
 * jitter). The ArrayList is the one exception — its Motion setting defaults to the
 * original client's horizontal Slide, and offers Reveal for the still-glyph behaviour.
 *
 * v1 elements: TargetHud (reference design: head + name + held items + health with a
 * damage ghost, plus the TARGET's armor with the most-damaged piece's durability),
 * ArrayList (a port of the Rice client's `Interface` module — see [ArrayListColorMode]),
 * Watermark, Notifications. Positions are module values (auto-persist);
 * dragging works while the chat is open, like [ModuleHud2].
 */
/** The Potions element's two looks: Blade's horizontal cards, or the "Effects" panel. */
enum class PotionStyle(override val tag: String) : Tagged {
    CARDS("Cards"),
    PANEL("Effects"),
}

@Suppress("LargeClass", "TooManyFunctions")
object ModuleHellhitHud : Mode("Hellhit", aliases = listOf("HellhitHud")) {

    override val parent: ModeValueGroup<*>
        get() = ModuleHud.theme

    // ------------------------------------------------------------------ *
    // Palette (mirror of the Hellhit ClickGUI's private Palette) + accent
    // ------------------------------------------------------------------ *

    private object P {
        val panel = Color4b(14, 14, 16, 250)
        val panelBorder = Color4b(255, 255, 255, 15)
        val hairline = Color4b(255, 255, 255, 11)
        val row = Color4b(29, 29, 32, 255)
        val well = Color4b(9, 9, 11, 255)
        val white = Color4b(236, 236, 240, 255)
        val desc = Color4b(148, 148, 155, 255)
        val dim = Color4b(102, 102, 110, 255)
        val ink = Color4b(15, 15, 17, 255)
        val rose = Color4b(244, 63, 94, 255)
        val emerald = Color4b(16, 185, 129, 255)
    }

    private val accent: Color4b get() = HellhitClickGuiSettings.accent

    private const val R_PANEL = 6.5f
    private const val R_ROW = 4f
    private const val R_CTRL = 3f
    private const val IN_MS = 180f
    private const val OUT_MS = 140f

    // ------------------------------------------------------------------ *
    // Shared helpers
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

    private fun Color4b.alphaF(f: Float): Color4b =
        with(a = (a * f.coerceIn(0f, 1f)).toInt().coerceIn(0, 255))

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        return Color4b(ch(a.r, b.r), ch(a.g, b.g), ch(a.b, b.b), ch(a.a, b.a))
    }

    /** Physical pixels per local unit for an element rendered at [elementScale]. */
    private fun density(elementScale: Float): Float = elementScale * mc.window.guiScale.toFloat()

    private fun ellipsize(font: WatermarkFont, text: String, px: Float, density: Float, maxW: Float): String {
        if (font.width(text, px, density) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t..", px, density) > maxW) t = t.dropLast(1)
        return "$t.."
    }

    /** Scissor rect in GUI units (the scissor stack ignores the pose transform). */
    private inline fun clipGui(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, body: () -> Unit) {
        val x0 = floor(x.toDouble()).toInt()
        val y0 = floor(y.toDouble()).toInt()
        val rect = ScreenRectangle(
            x0, y0,
            (ceil((x + w).toDouble()).toInt() - x0).coerceAtLeast(1),
            (ceil((y + h).toDouble()).toInt() - y0).coerceAtLeast(1),
        )
        ctx.scissorStack.push(rect)
        try {
            body()
        } finally {
            ctx.scissorStack.pop()
        }
    }

    /** Item icon at [size] gui units through the vanilla renderer, origin device-snapped. */
    private fun drawItemIcon(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, size: Float) {
        if (stack.isEmpty) return
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val gx = Math.round(x * guiScale) / guiScale
        val gy = Math.round(y * guiScale) / guiScale
        ctx.pose().pushMatrix()
        ctx.pose().translate(gx, gy)
        ctx.pose().scale(size / 16f, size / 16f)
        ctx.item(stack, 0, 0)
        ctx.pose().popMatrix()
    }

    /** Face + hat from the skin, rounded via the rounded-texture pipeline; well slab otherwise. */
    private fun drawHead(ctx: GuiGraphicsExtractor, skin: Identifier?, x: Float, y: Float, size: Float, alphaF: Float) {
        val r = (R_CTRL + 1f).coerceAtMost(size * 0.5f)
        if (skin == null) {
            ctx.drawRoundedRect(x, y, x + size, y + size, r, P.well.alphaF(alphaF), P.hairline.alphaF(alphaF), 1f)
            return
        }
        val setup = mc.textureManager.getTexture(skin).textureSetup
        val sz = Math.round(size)
        val rr = Math.round(r)
        val tint = Color4b(255, 255, 255, (255 * alphaF).toInt().coerceIn(0, 255)).argb
        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.GUI.roundedTex(),
            textureSetup = setup,
            bounds = ctx.getBoundsXYWH(x, y, size, size),
        ) { pose ->
            addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0).setLineWidth(0f)
            addVertexWith2DPose(pose, x, y + size).setUv(0f, 1f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
            addVertexWith2DPose(pose, x + size, y + size).setUv(1f, 1f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
            addVertexWith2DPose(pose, x + size, y).setUv(1f, 0f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
        }
    }

    // ------------------------------------------------------------------ *
    // Own combat-target tracking (independent from Hud2 — its resolver only
    // ticks while Hud2 itself is enabled)
    // ------------------------------------------------------------------ *

    private const val TARGET_TIMEOUT_MS = 4000L
    private const val TARGET_MAX_DISTANCE = 64f
    private var lastAttacked: LivingEntity? = null
    private var lastAttackAt = 0L

    @Suppress("unused")
    private val attackTracker = handler<AttackEntityEvent> { event ->
        val entity = event.entity
        if (entity is LivingEntity && entity !== mc.player) {
            lastAttacked = entity
            lastAttackAt = System.currentTimeMillis()
        }
    }

    private fun combatTarget(): LivingEntity? {
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

    // ------------------------------------------------------------------ *
    // Region blur ("glass")
    // ------------------------------------------------------------------ *

    /** Vanilla allows blurBeforeThisStratum() once per frame; every element shares one capture. */
    private var blurMarkedEvent: OverlayRenderEvent? = null

    /**
     * Marks this frame for the region blur and returns the texture the glass quads sample, or null
     * when the capture is unavailable. See [WatermarkGlass] for how the sharp frame is preserved.
     */
    private fun requestGlass(
        ctx: GuiGraphicsExtractor,
        event: OverlayRenderEvent,
        strength: Int,
        passes: Int,
    ): TextureSetup? {
        if (blurMarkedEvent !== event) {
            ctx.guiRenderState.blurBeforeThisStratum()
            blurMarkedEvent = event
        }
        return WatermarkGlass.request(strength, passes)
    }

    /**
     * Blurred backdrop clipped to the local rect ([x0],[y0])..([x1],[y1]). The current pose is baked
     * in, so the sampled region tracks the element wherever it is drawn.
     */
    @Suppress("LongParameterList")
    private fun drawGlassRect(
        ctx: GuiGraphicsExtractor,
        setup: TextureSetup,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        alphaF: Float,
    ) {
        val texW = WatermarkGlass.textureWidth.toFloat()
        val texH = WatermarkGlass.textureHeight.toFloat()
        if (texW <= 0f || texH <= 0f) {
            return
        }

        val pose = Matrix3x2f(ctx.pose())
        val guiScale = mc.window.guiScale.toFloat()
        // local unit -> device pixel -> UV (the render target texture is y-flipped)
        val uAt = { lx: Float -> (pose.m00 * lx + pose.m20) * guiScale / texW }
        val vAt = { ly: Float -> 1f - (pose.m11 * ly + pose.m21) * guiScale / texH }
        val tint = Color4b.WHITE.alphaF(alphaF).argb

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI_TEXTURED,
                setup,
                pose,
                ctx.scissorStack.peek(),
                ctx.watermarkBounds(x0, y0, x1, y1),
            ) { p ->
                addVertexWith2DPose(p, x0, y0).setUv(uAt(x0), vAt(y0)).setColor(tint)
                addVertexWith2DPose(p, x0, y1).setUv(uAt(x0), vAt(y1)).setColor(tint)
                addVertexWith2DPose(p, x1, y1).setUv(uAt(x1), vAt(y1)).setColor(tint)
                addVertexWith2DPose(p, x1, y0).setUv(uAt(x1), vAt(y0)).setColor(tint)
            }
        )
    }

    // ------------------------------------------------------------------ *
    // Edge haze: the same backdrops, tessellated and feathered to dissolve at the outer edges
    // ------------------------------------------------------------------ *

    private fun smooth01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Tessellation break-points for one axis: the tile edges [tLo]/[tHi], a couple of sub-samples
     * inside each outer band (so the fade curves instead of stepping), and the optional [extraA]/
     * [extraB] splits (the bottom overhang boundaries). Coarse — the interior is a single span, so
     * the whole haloed row is a few dozen quads, not a few hundred.
     */
    @Suppress("LongParameterList")
    private fun hazeAxis(lo: Float, tLo: Float, tHi: Float, hi: Float, extraA: Float, extraB: Float): FloatArray {
        val set = sortedSetOf(lo, tLo, tHi, hi)
        if (tLo > lo) { set.add(lo + (tLo - lo) / 3f); set.add(lo + 2f * (tLo - lo) / 3f) }
        if (hi > tHi) { set.add(tHi + (hi - tHi) / 3f); set.add(tHi + 2f * (hi - tHi) / 3f) }
        if (!extraA.isNaN() && extraA in lo..hi) set.add(extraA)
        if (!extraB.isNaN() && extraB in lo..hi) set.add(extraB)
        return set.toFloatArray()
    }

    /** Smooth, deterministic (no time → no shimmer) waver of the halo edge. */
    private fun hazeWobbleAt(lx: Float, ly: Float, amp: Float): Float =
        if (amp <= 0f) 0f else (sin(lx * 1.3f + ly * 0.7f) + sin(ly * 1.9f - lx * 0.5f)) * 0.5f * amp

    /**
     * Coverage 0..1 over the EXPANDED rect: 1 everywhere inside the tile [[x0]..[x1]]x[[y0]..[y1]],
     * ramping to 0 across the outer halo of width [fl]/[fr]/[ft]/[fb] added beyond each edge. Sides
     * multiply, so corners round off. [noisePhase] shifts the optional waver per row.
     *
     * The bottom halo is drawn only across [[bFrom]..[bTo]] — the slice of the row's width with no
     * module beneath it (the staircase overhang). Below the tile outside that slice there is a
     * next row, so nothing extends there.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private fun hazeCoverage(
        lx: Float, ly: Float, x0: Float, y0: Float, x1: Float, y1: Float,
        fl: Float, fr: Float, ft: Float, fb: Float, bFrom: Float, bTo: Float, wob: Float, noisePhase: Float,
    ): Float {
        val w = hazeWobbleAt(lx, ly + noisePhase, wob)
        var m = 1f
        if (fl > 0f) m *= smooth01((lx - (x0 - fl) + w) / fl)
        if (fr > 0f) m *= smooth01(((x1 + fr) - lx + w) / fr)
        if (ft > 0f) m *= smooth01((ly - (y0 - ft) + w) / ft)
        if (ly > y1) {
            // In the extended band below the tile: only the exposed overhang slice fades down.
            if (fb > 0f && lx > bFrom && lx < bTo) m *= smooth01(((y1 + fb) - ly + w) / fb) else return 0f
        }
        return m.coerceIn(0f, 1f)
    }

    /** [drawGlassRect] plus a frosted halo added outside the tile's fed edges (tile stays full). */
    @Suppress("LongParameterList", "LongMethod")
    private fun drawGlassRectHazed(
        ctx: GuiGraphicsExtractor, setup: TextureSetup,
        x0: Float, y0: Float, x1: Float, y1: Float, alphaF: Float,
        fl: Float, fr: Float, ft: Float, fb: Float, bFrom: Float, bTo: Float, wob: Float, noisePhase: Float,
    ) {
        val texW = WatermarkGlass.textureWidth.toFloat()
        val texH = WatermarkGlass.textureHeight.toFloat()
        if (texW <= 0f || texH <= 0f) return
        val ex0 = x0 - fl
        val ex1 = x1 + fr
        val ey0 = y0 - ft
        val ey1 = y1 + fb
        val pose = Matrix3x2f(ctx.pose())
        val guiScale = mc.window.guiScale.toFloat()
        val uAt = { lx: Float -> (pose.m00 * lx + pose.m20) * guiScale / texW }
        val vAt = { ly: Float -> 1f - (pose.m11 * ly + pose.m21) * guiScale / texH }
        val xs = hazeAxis(ex0, x0, x1, ex1, bFrom, bTo)
        val ys = hazeAxis(ey0, y0, y1, ey1, Float.NaN, Float.NaN)

        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI_TEXTURED, setup, pose,
                ctx.scissorStack.peek(), ctx.watermarkBounds(ex0, ey0, ex1, ey1),
            ) { p ->
                fun vtx(vx: Float, vy: Float) {
                    val cov = hazeCoverage(vx, vy, x0, y0, x1, y1, fl, fr, ft, fb, bFrom, bTo, wob, noisePhase)
                    val a = (alphaF * cov * 255f).toInt().coerceIn(0, 255)
                    addVertexWith2DPose(p, vx, vy).setUv(uAt(vx), vAt(vy)).setColor((a shl 24) or 0xFFFFFF)
                }
                for (j in 0 until ys.size - 1) {
                    for (i in 0 until xs.size - 1) {
                        vtx(xs[i], ys[j]); vtx(xs[i], ys[j + 1]); vtx(xs[i + 1], ys[j + 1]); vtx(xs[i + 1], ys[j])
                    }
                }
            }
        )
    }

    /** A flat-color fill with the same outward halo (the row darkening; tile stays full). */
    @Suppress("LongParameterList", "LongMethod")
    private fun drawDarkHazed(
        ctx: GuiGraphicsExtractor,
        x0: Float, y0: Float, x1: Float, y1: Float, baseAlpha: Float,
        fl: Float, fr: Float, ft: Float, fb: Float, bFrom: Float, bTo: Float, wob: Float, noisePhase: Float,
    ) {
        if (baseAlpha <= 0f) return
        val ex0 = x0 - fl
        val ex1 = x1 + fr
        val ey0 = y0 - ft
        val ey1 = y1 + fb
        val pose = Matrix3x2f(ctx.pose())
        val xs = hazeAxis(ex0, x0, x1, ex1, bFrom, bTo)
        val ys = hazeAxis(ey0, y0, y1, ey1, Float.NaN, Float.NaN)
        ctx.guiRenderState.addGuiElement(
            WatermarkGuiElement(
                RenderPipelines.GUI, TextureSetup.noTexture(), pose,
                ctx.scissorStack.peek(), ctx.watermarkBounds(ex0, ey0, ex1, ey1),
            ) { p ->
                fun vtx(vx: Float, vy: Float) {
                    val cov = hazeCoverage(vx, vy, x0, y0, x1, y1, fl, fr, ft, fb, bFrom, bTo, wob, noisePhase)
                    val a = (baseAlpha * cov).toInt().coerceIn(0, 255)
                    addVertexWith2DPose(p, vx, vy).setColor(a shl 24)
                }
                for (j in 0 until ys.size - 1) {
                    for (i in 0 until xs.size - 1) {
                        vtx(xs[i], ys[j]); vtx(xs[i], ys[j + 1]); vtx(xs[i + 1], ys[j + 1]); vtx(xs[i + 1], ys[j])
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------ *
    // Edit mode + dragging (chat open, like Hud2)
    // ------------------------------------------------------------------ *

    private fun editMode(): Boolean = mc.screen is ChatScreen

    private fun mouseGuiX(): Float = (mc.mouseHandler.xpos() / mc.window.guiScale).toFloat()

    private fun mouseGuiY(): Float = (mc.mouseHandler.ypos() / mc.window.guiScale).toFloat()

    private enum class DragTarget { TARGET_HUD, NOTIFICATIONS, ARRAY_LIST, WATERMARK, POTIONS }

    /** Last rendered bounds (gui units) for hit testing; stale after 500 ms. */
    internal class Bounds {
        var x = 0f
        var y = 0f
        var width = 0f
        var height = 0f
        var time = 0L
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
            if (System.currentTimeMillis() - time >= 500) return false
            val subRects = rects
            if (subRects != null) {
                return subRects.any { mx >= it[0] && mx <= it[0] + it[2] && my >= it[1] && my <= it[1] + it[3] }
            }
            return mx >= x && mx <= x + width && my >= y && my <= y + height
        }
    }

    private var dragging: DragTarget? = null
    private var grabDX = 0f
    private var grabDY = 0f

    @Suppress("unused")
    private val mouseButtonHandler = handler<MouseButtonEvent> { event ->
        if (event.button != 0) return@handler
        if (event.action == 0) {
            dragging = null
            return@handler
        }
        if (event.action != 1 || !editMode()) return@handler

        val mx = mouseGuiX()
        val my = mouseGuiY()
        val hit = when {
            TargetHudEl.enabled && TargetHudEl.bounds.contains(mx, my) -> DragTarget.TARGET_HUD
            NotificationsEl.enabled && NotificationsEl.bounds.contains(mx, my) -> DragTarget.NOTIFICATIONS
            ArrayListEl.enabled && ArrayListEl.bounds.contains(mx, my) -> DragTarget.ARRAY_LIST
            PotionsEl.enabled && PotionsEl.bounds.contains(mx, my) -> DragTarget.POTIONS
            WatermarkEl.enabled && WatermarkEl.bounds.contains(mx, my) -> DragTarget.WATERMARK
            else -> null
        } ?: return@handler
        val bounds = when (hit) {
            DragTarget.TARGET_HUD -> TargetHudEl.bounds
            DragTarget.NOTIFICATIONS -> NotificationsEl.bounds
            DragTarget.ARRAY_LIST -> ArrayListEl.bounds
            DragTarget.POTIONS -> PotionsEl.bounds
            DragTarget.WATERMARK -> WatermarkEl.bounds
        }
        grabDX = mx - bounds.x
        grabDY = my - bounds.y
        dragging = hit
    }

    /** Accent edit frames over every enabled element while the chat is open. */
    @Suppress("unused")
    private val editFrameHandler = handler<OverlayRenderEvent> { event ->
        if (!editMode()) return@handler
        val ctx = event.context

        fun frame(bounds: Bounds, target: DragTarget) {
            if (System.currentTimeMillis() - bounds.time >= 500) return
            val active = dragging == target
            val outline = accent.alphaF(if (active) 0.9f else 0.4f)
            val rects = bounds.rects ?: listOf(floatArrayOf(bounds.x, bounds.y, bounds.width, bounds.height))
            for (r in rects) {
                ctx.drawRoundedRect(
                    r[0] - 1.5f, r[1] - 1.5f, r[0] + r[2] + 1.5f, r[1] + r[3] + 1.5f,
                    R_CTRL, accent.alphaF(0.06f), outline, 1f,
                )
            }
        }
        if (TargetHudEl.enabled) frame(TargetHudEl.bounds, DragTarget.TARGET_HUD)
        if (NotificationsEl.enabled) frame(NotificationsEl.bounds, DragTarget.NOTIFICATIONS)
        if (ArrayListEl.enabled) frame(ArrayListEl.bounds, DragTarget.ARRAY_LIST)
        if (PotionsEl.enabled) frame(PotionsEl.bounds, DragTarget.POTIONS)
        if (WatermarkEl.enabled) frame(WatermarkEl.bounds, DragTarget.WATERMARK)
    }

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

    init {
        tree(WatermarkEl)
        tree(TargetHudEl)
        tree(ArrayListEl)
        tree(PotionsEl)
        tree(NotificationsEl)
    }

    /** Used by the Gui mixin: vanilla corner effect icons hide while our Potions render. */
    @JvmStatic
    fun shouldHideVanillaEffects(): Boolean = running && PotionsEl.enabled

    // ================================================================== *
    // Watermark
    // ================================================================== *

    private object WatermarkEl : ToggleableValueGroup(this, "Watermark", true) {

        private val corner by enumChoice("Corner", Corner.TOP_LEFT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 6f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val title by text("Title", "Hellhit")
        private val showFps by boolean("Fps", true)
        private val showPing by boolean("Ping", true)

        val bounds = Bounds()

        // int-keyed string caches: no per-frame allocation while the value is stable
        private var fpsText = ""
        private var fpsValue = -1
        private var pingText = ""
        private var pingValue = -1

        private var shownSince = 0L

        @Suppress("unused", "detekt:LongMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) return@handler
            val ctx = event.context

            if (shownSince == 0L) shownSince = System.currentTimeMillis()
            val alphaF = easeInOutCubic((System.currentTimeMillis() - shownSince) / IN_MS)

            val px = 7.5f
            val smallPx = 6.2f
            val d = density(scale)
            val titleH = WatermarkFont.BRAND.lineHeight(px, d)
            val h = titleH + 9f

            if (showFps) {
                val fps = mc.fps
                if (fps != fpsValue) {
                    fpsValue = fps
                    fpsText = "$fps fps"
                }
            }
            if (showPing) {
                val ping = mc.player?.let { p ->
                    mc.connection?.getPlayerInfo(p.uuid)?.latency
                } ?: 0
                if (ping != pingValue) {
                    pingValue = ping
                    pingText = "$ping ms"
                }
            }

            val titleW = WatermarkFont.BRAND.width(title, px, d)
            val fpsW = if (showFps) WatermarkFont.TEXT.width(fpsText, smallPx, d) else 0f
            val pingW = if (showPing) WatermarkFont.TEXT.width(pingText, smallPx, d) else 0f
            val hasInfo = showFps || showPing

            var w = 6f + titleW + 6f
            if (hasInfo) {
                w += 1f + 6f // divider + gap
                if (showFps) w += fpsW
                if (showFps && showPing) w += 10f
                if (showPing) w += pingW
                w += 6f
            }

            val screenW = mc.window.guiScaledWidth.toFloat()
            val screenH = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.WATERMARK && editMode()) {
                val nx = mouseGuiX() - grabDX
                val ny = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenW - nx - w * scale else nx).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenH - ny - h * scale else ny).coerceIn(0f, 1000f)
            }
            val x = if (corner.isRight) screenW - offsetX - w * scale else offsetX
            val y = if (corner.isBottom) screenH - offsetY - h * scale else offsetY
            bounds.set(x, y, w * scale, h * scale)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(scale, scale)

            ctx.drawRoundedRect(0f, 0f, w, h, R_ROW, P.panel.alphaF(alphaF), P.panelBorder.alphaF(alphaF), 1f)

            val cy = h / 2f
            var tx = 6f
            WatermarkFont.BRAND.submit(ctx, title, tx, cy - titleH / 2f, px, d) { accent.alphaF(alphaF).argb }
            tx += titleW + 6f

            if (hasInfo) {
                ctx.drawQuad(tx, 3.5f, tx + 1f, h - 3.5f, P.hairline.alphaF(alphaF), null)
                tx += 1f + 6f
                val smallH = WatermarkFont.TEXT.lineHeight(smallPx, d)
                if (showFps) {
                    WatermarkFont.TEXT.submit(ctx, fpsText, tx, cy - smallH / 2f, smallPx, d) {
                        P.desc.alphaF(alphaF).argb
                    }
                    tx += fpsW + 10f
                }
                if (showPing) {
                    WatermarkFont.TEXT.submit(ctx, pingText, tx, cy - smallH / 2f, smallPx, d) {
                        P.desc.alphaF(alphaF).argb
                    }
                }
            }

            ctx.pose().popMatrix()
        }
    }

    // ================================================================== *
    // TargetHud — the reference design
    // ================================================================== *

    private object TargetHudEl : ToggleableValueGroup(this, "TargetHud", true) {

        // Geometry from the forensic read of the reference (device px at guiScale 2,
        // halved into gui units): 104x36 translucent slab, a 32-unit avatar tile with a
        // thick light frame, a centered text column with the health bar as the NAME's
        // underline, frameless held-item markers, and an engraved 2x2 armor grid right.
        private const val W = 104f
        private const val H = 36f
        private const val AVATAR = 32f
        private const val AVATAR_X = 2.5f
        private const val AVATAR_Y = 2f
        private const val FRAME_T = 2.2f
        private const val COL_X0 = 38.5f
        private const val COL_X1 = 72f
        private const val GRID_X = 76f
        private const val GRID_Y = 6.5f
        private const val CELL_W = 11f
        private const val CELL_H = 12f
        private const val CELL_GAP = 1.5f
        private const val RETENTION_MS = 300L

        /** Design geometry is reference-exact but reads small in game; user Scale stacks on top. */
        private const val BASE_SCALE = 1.4f

        private var offsetX by float("OffsetX", 60f, -2000f..2000f)
        private var offsetY by float("OffsetY", 20f, -2000f..2000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val preview by boolean("Preview", false)
        private val background by boolean("Background", true)
        private val showArmor by boolean("Armor", true)
        private val showHeld by boolean("HeldItems", true)
        private val damageGhost by boolean("DamageGhost", true)

        val bounds = Bounds()

        /** Translucent slab: the world tints it, exactly like the reference's panel. */
        private val hudPanel = Color4b(11, 11, 12, 205)

        /**
         * A depleting ROUNDED ring: the full rounded outline is revealed clockwise from
         * top-center through non-overlapping scissor windows, so the corners stay arcs
         * at every fraction. Coordinates are ABSOLUTE gui units — the scissor stack
         * ignores the pose transform, so this must be called OUTSIDE the panel pose.
         */
        @Suppress("detekt:CognitiveComplexMethod", "detekt:LongMethod", "LongParameterList")
        private fun drawRoundedRing(
            ctx: GuiGraphicsExtractor,
            x: Float, y: Float, w: Float, h: Float,
            r: Float, t: Float, fraction: Float, color: Color4b,
        ) {
            val f = fraction.coerceIn(0f, 1f)
            if (f <= 0.001f) return

            fun outline(clipX: Float, clipY: Float, clipW: Float, clipH: Float) {
                if (clipW <= 0.01f || clipH <= 0.01f) return
                clipGui(ctx, clipX, clipY, clipW, clipH) {
                    ctx.drawRoundedRect(x, y, x + w, y + h, r, null, color, t)
                }
            }

            val cx = x + w / 2f
            val cy = y + h / 2f
            val x1 = x + w
            val y1 = y + h
            // corner window: wide enough to always hold the arc and the stroke
            val m = maxOf(r, t) + 0.5f
            val hw = w / 2f
            val hh = h / 2f

            // clockwise phases from top-center; each phase = one growing window.
            // Windows are laid out so consecutive phases never overlap (translucent
            // strokes would double up into visible seams otherwise).
            val lengths = floatArrayOf(hw, hh - m, hh, hw - m, hw, hh - m, hh, hw - m)
            var total = 0f
            for (len in lengths) total += len
            var remain = f * total

            for (phase in 0..7) {
                val len = lengths[phase]
                if (len <= 0.01f) continue
                val g = (remain / len).coerceIn(0f, 1f) * len
                if (g <= 0.01f) break
                when (phase) {
                    0 -> outline(cx, y, g, m) // top edge, right half (incl. TR arc)
                    1 -> outline(x1 - m, y + m, m, g) // right edge, upper part
                    2 -> outline(x1 - m, cy, m, g) // right edge, lower (incl. BR arc)
                    3 -> outline(cx + (hw - m) - g, y1 - m, g, m) // bottom, right half
                    4 -> outline(cx - g, y1 - m, g, m) // bottom, left half (incl. BL arc)
                    5 -> outline(x, y1 - m - g, m, g) // left edge, lower part
                    6 -> outline(x, cy - g, m, g) // left edge, upper (incl. TL arc)
                    else -> outline(x + m, y, g, m) // top edge toward center (TL arc came with phase 6)
                }
                remain -= len
                if (remain <= 0f) break
            }
        }

        private class TargetInfo(
            val name: String,
            val health: Float,
            val maxHealth: Float,
            val skin: Identifier?,
            val armor: List<ItemStack>, // HEAD, CHEST, LEGS, FEET
            val mainHand: ItemStack,
            val offHand: ItemStack,
        ) {
            val pct: Float get() = (health / maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }

        private var displayedPct = 1f
        private var ghostPct = 1f
        private var lastFrameNs = 0L
        private var currentKey = ""
        private var shownSince = 0L

        // target retention: freeze the last snapshot briefly, then fade out
        private var lastInfo: TargetInfo? = null
        private var lastSeenAt = 0L
        private var fadeOutAt = 0L

        /** Cells (local x, local y, durability pct) whose rings draw after the pose pops. */
        private val armorRings = ArrayList<FloatArray>(4)

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) return@handler
            val ctx = event.context
            val now = System.currentTimeMillis()

            val live = liveInfo()
            var alphaMul = 1f
            val info: TargetInfo?
            if (live != null) {
                info = live
                lastInfo = live
                lastSeenAt = now
                fadeOutAt = 0L
            } else {
                val held = lastInfo
                if (held != null && now - lastSeenAt <= RETENTION_MS) {
                    info = held
                } else if (held != null) {
                    if (fadeOutAt == 0L) fadeOutAt = now
                    val outT = (now - fadeOutAt) / OUT_MS
                    if (outT >= 1f) {
                        lastInfo = null
                        currentKey = ""
                        shownSince = 0L
                        return@handler
                    }
                    alphaMul = 1f - easeInOutCubic(outT)
                    info = held
                } else {
                    currentKey = ""
                    shownSince = 0L
                    return@handler
                }
            }

            if (info.name != currentKey) {
                currentKey = info.name
                displayedPct = info.pct
                ghostPct = info.pct
                shownSince = now
            }

            // interpolated health + lagging damage ghost (snaps up on heal)
            val nowNs = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat()
            lastFrameNs = nowNs
            displayedPct += (info.pct - displayedPct) * (dt * 10f).coerceIn(0f, 1f)
            ghostPct = if (displayedPct > ghostPct) {
                displayedPct
            } else {
                ghostPct + (displayedPct - ghostPct) * (dt * 2.5f).coerceIn(0f, 1f)
            }

            val alphaF = easeInOutCubic((now - shownSince) / IN_MS) * alphaMul
            val es = scale * BASE_SCALE
            val d = density(es)
            armorRings.clear()

            val namePx = 5.4f
            val heldPx = 5f
            val nameH = WatermarkFont.BRAND.lineHeight(namePx, d)
            val heldH = WatermarkFont.TEXT.lineHeight(heldPx, d)
            val colW = COL_X1 - COL_X0
            val colCx = (COL_X0 + COL_X1) / 2f

            val screenW = mc.window.guiScaledWidth.toFloat()
            val screenH = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.TARGET_HUD && editMode()) {
                offsetX = (mouseGuiX() - grabDX - screenW * 0.5f).coerceIn(-2000f, 2000f)
                offsetY = (mouseGuiY() - grabDY - screenH * 0.5f).coerceIn(-2000f, 2000f)
            }
            val x = screenW * 0.5f + offsetX
            val y = screenH * 0.5f + offsetY
            bounds.set(x, y, W * es, H * es)

            ctx.pose().pushMatrix()
            ctx.pose().translate(x, y)
            ctx.pose().scale(es, es)

            // translucent slab, no border (the reference has none)
            if (background) {
                ctx.drawRoundedRect(0f, 0f, W, H, R_PANEL, hudPanel.alphaF(alphaF), null)
            }

            // avatar tile: the thick frame IS the health display — a dim full track here;
            // the rose ghost and the accent rings are drawn after the pose pops (the
            // ring helper scissors in absolute coords)
            ctx.drawRoundedRect(
                AVATAR_X, AVATAR_Y, AVATAR_X + AVATAR, AVATAR_Y + AVATAR, 5.5f,
                accent.alphaF(0.18f * alphaF), null,
            )
            ctx.drawRoundedRect(
                AVATAR_X + FRAME_T, AVATAR_Y + FRAME_T,
                AVATAR_X + AVATAR - FRAME_T, AVATAR_Y + AVATAR - FRAME_T, 3.5f,
                Color4b(16, 15, 14, 255).alphaF(alphaF), null,
            )
            drawHead(ctx, info.skin, AVATAR_X + FRAME_T + 0.6f, AVATAR_Y + FRAME_T + 0.6f,
                AVATAR - 2f * (FRAME_T + 0.6f), alphaF)

            // name, centered in the column
            val name = ellipsize(WatermarkFont.BRAND, info.name, namePx, d, colW)
            val nameW = WatermarkFont.BRAND.width(name, namePx, d)
            var cy = 5.5f
            WatermarkFont.BRAND.submit(ctx, name, colCx - nameW / 2f, cy, namePx, d) {
                P.white.alphaF(alphaF).argb
            }
            cy += nameH + 1.8f

            // faint full-column hairline under the title block
            ctx.drawQuad(COL_X0, cy, COL_X1, cy + 0.7f, Color4b(255, 255, 255, 22).alphaF(alphaF), null)
            cy += 2.6f

            // "Empty" is written ONLY for an empty hand — a held item is represented by
            // its icon below, not by text. Separator (a drawn thin bar: the glyph would
            // not survive the font atlas) appears only between two runs.
            val mainEmpty = info.mainHand.isEmpty
            val offEmpty = info.offHand.isEmpty
            if (showHeld && (mainEmpty || offEmpty)) {
                val word = "Empty"
                val wordW = WatermarkFont.TEXT.width(word, heldPx, d)
                val sepW = 0.8f
                if (mainEmpty && offEmpty) {
                    val lineW = wordW + 3f + sepW + 3f + wordW
                    var hx = colCx - lineW / 2f
                    WatermarkFont.TEXT.submit(ctx, word, hx, cy, heldPx, d) { P.desc.alphaF(alphaF).argb }
                    hx += wordW + 3f
                    ctx.drawQuad(hx, cy + 0.6f, hx + sepW, cy + heldH - 0.6f, P.dim.alphaF(alphaF), null)
                    hx += sepW + 3f
                    WatermarkFont.TEXT.submit(ctx, word, hx, cy, heldPx, d) { P.desc.alphaF(alphaF).argb }
                } else {
                    WatermarkFont.TEXT.submit(ctx, word, colCx - wordW / 2f, cy, heldPx, d) {
                        P.desc.alphaF(alphaF).argb
                    }
                }
            }

            // bottom row: FRAMELESS held-item icons, the pair CENTER-ANCHORED on the
            // column (fixed slots — icons never drift); a dim dot marks an empty hand
            if (showHeld) {
                val handIcon = 9f
                val rowY = H - handIcon - 2f
                var ix = colCx - (handIcon + 4f + handIcon) / 2f
                for (stack in listOf(info.mainHand, info.offHand)) {
                    if (stack.isEmpty) {
                        val dcx = ix + handIcon / 2f
                        val dcy = rowY + handIcon / 2f
                        ctx.drawRoundedRect(
                            dcx - 1.1f, dcy - 1.1f, dcx + 1.1f, dcy + 1.1f, 1.1f,
                            P.dim.alphaF(0.8f * alphaF), null,
                        )
                    } else {
                        drawItemIcon(ctx, stack, ix, rowY, handIcon)
                    }
                    ix += handIcon + 4f
                }
            }

            // engraved 2x2 armor grid on the right: cells darker than the slab,
            // separators lighter — no outer border
            if (showArmor) {
            for ((index, piece) in info.armor.withIndex()) {
                val col = index % 2
                val row = index / 2
                val cellX = GRID_X + col * (CELL_W + CELL_GAP)
                val cellY = GRID_Y + row * (CELL_H + CELL_GAP)
                ctx.drawRoundedRect(
                    cellX, cellY, cellX + CELL_W, cellY + CELL_H, 3.5f,
                    Color4b(0, 0, 0, 70).alphaF(alphaF), null,
                )
                if (!piece.isEmpty) {
                    // fill the cell: icon as wide as the cell itself
                    drawItemIcon(ctx, piece, cellX, cellY + (CELL_H - CELL_W) / 2f, CELL_W)
                }
                // durability rings are drawn after the pose pops (absolute coords);
                // remember the cell + percent for that pass
                if (!piece.isEmpty && piece.maxDamage > 0) {
                    val pct = (piece.maxDamage - piece.damageValue) * 100f / piece.maxDamage
                    armorRings.add(floatArrayOf(cellX, cellY, pct))
                }
            }
            // lighter engraved separators between the cells
            val sepColor = Color4b(255, 255, 255, 18).alphaF(alphaF)
            val gridW = 2f * CELL_W + CELL_GAP
            val gridH = 2f * CELL_H + CELL_GAP
            ctx.drawQuad(
                GRID_X + CELL_W + 0.35f, GRID_Y,
                GRID_X + CELL_W + CELL_GAP - 0.35f, GRID_Y + gridH, sepColor, null,
            )
            ctx.drawQuad(
                GRID_X, GRID_Y + CELL_H + 0.35f,
                GRID_X + gridW, GRID_Y + CELL_H + CELL_GAP - 0.35f, sepColor, null,
            )
            }

            ctx.pose().popMatrix()

            // rings, in absolute gui coords (their scissors ignore the pose)
            if (damageGhost && ghostPct > 0.002f) {
                drawRoundedRing(
                    ctx, x + AVATAR_X * es, y + AVATAR_Y * es, AVATAR * es, AVATAR * es,
                    r = 5.5f * es, t = FRAME_T * es, fraction = ghostPct,
                    color = P.rose.alphaF(0.5f * alphaF),
                )
            }
            if (displayedPct > 0.002f) {
                drawRoundedRing(
                    ctx, x + AVATAR_X * es, y + AVATAR_Y * es, AVATAR * es, AVATAR * es,
                    r = 5.5f * es, t = FRAME_T * es, fraction = displayedPct,
                    color = accent.alphaF(0.9f * alphaF),
                )
            }
            for (ring in armorRings) {
                val pct = ring[2]
                // same material as the health ring: the accent (white on Client)
                drawRoundedRing(
                    ctx, x + ring[0] * es, y + ring[1] * es, CELL_W * es, CELL_H * es,
                    r = 3.5f * es, t = 0.9f * es, fraction = pct / 100f,
                    color = accent.alphaF(0.9f * alphaF),
                )
            }
        }

        private fun liveInfo(): TargetInfo? {
            val target = combatTarget()
            val entity = target ?: (if (editMode()) mc.player else null)
                ?: return if (preview) previewInfo() else null
            val shownHealth = entity.nametagHealth()
            return TargetInfo(
                name = entity.name.string,
                health = shownHealth,
                maxHealth = entity.maxHealth.coerceAtLeast(shownHealth),
                skin = (entity as? AbstractClientPlayer)?.skin?.body?.texturePath(),
                armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                    .map { entity.getItemBySlot(it) },
                mainHand = entity.getItemBySlot(EquipmentSlot.MAINHAND),
                offHand = entity.getItemBySlot(EquipmentSlot.OFFHAND),
            )
        }

        /** Fake full-gear target so the HUD can be positioned/previewed with no combat. */
        private fun previewInfo(): TargetInfo {
            val t = (System.currentTimeMillis() % 4000L) / 4000f
            val hp = (0.15f + 0.8f * (0.5f + 0.5f * sin(t * 2f * Math.PI.toFloat()))) * 20f
            return TargetInfo(
                name = "Steve",
                health = hp,
                maxHealth = 20f,
                skin = mc.player?.skin?.body?.texturePath(),
                armor = listOf(
                    Items.DIAMOND_HELMET, Items.IRON_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.GOLDEN_BOOTS,
                ).map { it.defaultInstance },
                mainHand = Items.DIAMOND_SWORD.defaultInstance,
                offHand = ItemStack.EMPTY,
            )
        }
    }

    // ================================================================== *
    // ArrayList
    // ================================================================== *

    private object ArrayListEl : ToggleableValueGroup(this, "ArrayList", true) {

        // Reference geometry, in local (pre-Scale) gui units. These are deliberately NOT settings:
        // the element has exactly one shape — the original client's — and nothing may override it.

        private const val NAME_PX = 7f

        /** Tags render at the same size as the name, only in the lighter face. */
        private const val TAG_PX = 7f

        /** Padding between a row edge and its text. */
        private const val PAD_X = 2f
        private const val PAD_Y = 1.5f

        /** Gap between a module name and its suffix tag. */
        private const val TAG_GAP = 4f

        /** The `|` on the anchored edge: a hairline of the row's own color, full row height. */
        private const val BAR_WIDTH = 1f
        private const val BAR_MARGIN = 1.5f

        /** Vertical inset of the bar: every row gets its own segment, not one long strip. */
        private const val BAR_INSET = 1.5f

        /** Drop shadow offset — one gui pixel down-right, like the 1.8 font renderer. */
        private const val SHADOW_OFFSET = 1f

        /** How far past its own width a row travels while sliding out. */
        private const val SLIDE_MARGIN = 6f

        /** Vertical re-stack speed (exponential settle, per second). */
        private const val GLIDE_SPEED = 14f

        /** Hue delay between neighbouring rows in [ArrayListColorMode.RAINBOW]. */
        private const val RAINBOW_ROW_DELAY_MS = 500
        private const val RAINBOW_ROW_DIVISOR = 6

        /** Below this a row contributes nothing but its share of the vertical space. */
        private const val LIFE_EPSILON = 0.02f

        /** Suffix tags are never themed — they stay the reference's near-white grey. */
        private val TAG_COLOR = Color4b(204, 204, 204, 255)

        private val corner by enumChoice("Corner", Corner.TOP_RIGHT)
        private var offsetX by float("OffsetX", 4f, 0f..1000f)
        private var offsetY by float("OffsetY", 4f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)

        private val colorMode by enumChoice("ColorMode", ArrayListColorMode.FADE)
        private val motion by enumChoice("Motion", ArrayListMotion.SLIDE)
        private val moduleFilter by enumChoice("Modules", ArrayListFilter.ALL)

        /**
         * Follow the HUD theme: both gradient stops are derived from the ClickGUI accent (the second
         * one hue-rotated by [themeHueShift]), so switching theme recolors the list. Turn it off to
         * drive [primaryColor] / [secondaryColor] by hand.
         */
        private val themeSync by boolean("ThemeSync", true)
        private val themeHueShift by float("ThemeHueShift", 0.12f, 0f..0.5f)
        private val primaryColor by color("Primary", Color4b(60, 200, 235, 255))
        private val secondaryColor by color("Secondary", Color4b(60, 235, 140, 255))

        private val showTags by boolean("Tags", true)
        private val lowercase by boolean("Lowercase", false)
        private val removeSpaces by boolean("RemoveSpaces", false)
        private val colorNames by boolean("ColorNames", true)
        private val slab by boolean("Background", true)

        /** Frosted backdrop behind the rows instead of a flat dark fill. */
        private val blur by boolean("Blur", true)
        private val blurStrength by int("BlurStrength", 6, 1..10)

        /** Repeats of the blur. Each pass widens and smooths it; 1 is vanilla's flat box blur. */
        private val blurPasses by int("BlurPasses", 3, 1..6)

        /** How far the backdrop is darkened under the text. */
        private val darkening by float("Darkening", 0.32f, 0f..1f)
        private val sidebar by boolean("Sidebar", true)

        /**
         * Extend the frosted backdrop past each outer edge with a soft halo that fades to nothing
         * — the full tile stays, the haze is *added around it* (draw size = tile + [hazeSize]).
         * [hazeWobble] can waver the falloff for an organic edge; 0 keeps it a clean dissolve.
         */
        private val edgeHaze by boolean("EdgeHaze", true)
        private val hazeSize by float("HazeSize", 4f, 0f..16f)
        private val hazeWobble by float("HazeWobble", 0f, 0f..4f)

        val bounds = Bounds()

        /** One row: its module, its presence timings and the vertical slot it is gliding toward. */
        private class Entry(val module: ClientModule) {
            var appearAt = System.currentTimeMillis()
            var removeAt = 0L
            var smoothY = Float.NaN

            var name = ""
            var rawTag: String? = null
            var tagText: String? = null
            var nameWidth = 0f

            /** Tag width *including* the gap that separates it from the name; 0 without a tag. */
            var tagWidth = 0f

            val contentWidth
                get() = nameWidth + tagWidth
        }

        /** Everything the layout derives from the font metrics, resolved once per frame. */
        private class Layout(
            val density: Float,
            val textH: Float,
            val tagH: Float,
            val barWidth: Float,
        ) {
            val rowH = textH + PAD_Y * 2f

            /** Horizontal space the accent bar and its outer margin take away from the text. */
            val barSpace = if (barWidth > 0f) barWidth + BAR_MARGIN else 0f

            /** Space a row needs on top of its text: both paddings plus the bar. */
            val extraWidth = PAD_X * 2f + barSpace
        }

        private val entries = LinkedHashMap<String, Entry>()
        private val alive = HashSet<String>()
        private var lastFrameNs = 0L

        /** First gradient stop: the HUD accent, or the hand-picked color. */
        private val gradientFrom: Color4b
            get() = if (themeSync) accent else primaryColor

        /** Second stop: the same accent rotated in hue, or the hand-picked color. */
        private val gradientTo: Color4b
            get() = if (themeSync) HudAccent.shiftHue(accent, themeHueShift) else secondaryColor

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) return@handler
            val ctx = event.context
            val now = System.currentTimeMillis()

            // Sync with the enabled modules; a module that drops out starts its slide-out instead of
            // vanishing, and one that comes back mid-flight restarts from where it is.
            alive.clear()
            for (module in ModuleManager) {
                if (!module.enabled || module.hidden || !passesFilter(module)) continue
                alive += module.name
                val entry = entries.getOrPut(module.name) { Entry(module) }
                if (entry.removeAt != 0L) {
                    entry.removeAt = 0L
                    entry.appearAt = now
                }
                entry.rawTag = module.tag
            }
            for (entry in entries.values) {
                if (entry.module.name !in alive && entry.removeAt == 0L) entry.removeAt = now
            }
            entries.values.removeIf { it.removeAt != 0L && now > it.removeAt + OUT_MS.toLong() }
            if (entries.isEmpty()) return@handler

            val d = density(scale)
            val layout = Layout(
                density = d,
                textH = WatermarkFont.BRAND.lineHeight(NAME_PX, d),
                tagH = WatermarkFont.TEXT.lineHeight(TAG_PX, d),
                barWidth = if (sidebar) BAR_WIDTH else 0f,
            )

            for (entry in entries.values) {
                entry.name = decorate(entry.module.name)
                entry.nameWidth = WatermarkFont.BRAND.width(entry.name, NAME_PX, d)
                val tag = entry.rawTag?.takeIf { showTags && it.isNotEmpty() }?.let { decorate(it) }
                entry.tagText = tag
                entry.tagWidth = if (tag == null) 0f else TAG_GAP + WatermarkFont.TEXT.width(tag, TAG_PX, d)
            }

            // Widest first — the staircase along the anchored edge the original is known for.
            val ordered = entries.values.sortedByDescending { it.contentWidth }

            val dt = if (lastFrameNs == 0L) {
                1f / 60f
            } else {
                ((System.nanoTime() - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
            lastFrameNs = System.nanoTime()

            var maxW = 0f
            var totalH = 0f
            val lives = FloatArray(ordered.size)
            for ((index, entry) in ordered.withIndex()) {
                val inT = ((now - entry.appearAt) / IN_MS).coerceIn(0f, 1f)
                val outT = if (entry.removeAt == 0L) 0f else ((now - entry.removeAt) / OUT_MS).coerceIn(0f, 1f)
                // A leaving row gives its slot up gradually, so everything below closes the gap.
                lives[index] = easeInOutCubic(inT) * (1f - easeInOutCubic(outT))
                maxW = maxOf(maxW, entry.contentWidth + layout.extraWidth)
                // Rows are flush: the slabs of the whole list form one stepped column.
                totalH += layout.rowH * lives[index]
            }
            totalH = totalH.coerceAtLeast(layout.rowH)

            val screenW = mc.window.guiScaledWidth.toFloat()
            val screenH = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.ARRAY_LIST && editMode()) {
                val nx = mouseGuiX() - grabDX
                val ny = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenW - nx - maxW * scale else nx).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenH - ny - totalH * scale else ny).coerceIn(0f, 1000f)
            }
            val anchorX = if (corner.isRight) screenW - offsetX - maxW * scale else offsetX
            val anchorY = if (corner.isBottom) screenH - offsetY - totalH * scale else offsetY
            bounds.set(anchorX, anchorY, maxW * scale, totalH * scale)

            val glass = if (slab && blur) requestGlass(ctx, event, blurStrength, blurPasses) else null

            // The topmost visible row is the only one whose top edge is exposed (widest-first, so
            // every other row is fully tucked under the wider one above it).
            var firstVis = -1
            for (i in lives.indices) {
                if (lives[i] > LIFE_EPSILON) {
                    firstVis = i
                    break
                }
            }
            // Width of the visible row directly below each row — the bottom halo only covers the
            // slice of this row that overhangs past it (the staircase step).
            val nextW = FloatArray(ordered.size) { -1f }
            var belowW = -1f
            for (i in ordered.indices.reversed()) {
                nextW[i] = belowW
                if (lives[i] > LIFE_EPSILON) belowW = ordered[i].contentWidth + layout.extraWidth
            }

            val glide = 1f - exp(-GLIDE_SPEED * dt)
            val sliding = motion == ArrayListMotion.SLIDE
            val rowRects = ArrayList<FloatArray>(ordered.size)
            var targetY = 0f
            for ((index, entry) in ordered.withIndex()) {
                val life = lives[index]
                if (life <= LIFE_EPSILON) {
                    targetY += layout.rowH * life
                    continue
                }

                if (entry.smoothY.isNaN()) {
                    entry.smoothY = targetY
                } else {
                    entry.smoothY += (targetY - entry.smoothY) * glide
                    if (abs(targetY - entry.smoothY) < 0.05f) entry.smoothY = targetY
                }

                val rowW = entry.contentWidth + layout.extraWidth
                val rowGuiY = anchorY + entry.smoothY * scale
                // Rows rest flush against the anchored edge; maxW only sizes the box, never a row.
                val homeX = if (corner.isRight) anchorX + (maxW - rowW) * scale else anchorX
                val travel = (rowW + SLIDE_MARGIN) * scale * (1f - life)
                val contentX = if (!sliding) homeX else homeX + (if (corner.isRight) travel else -travel)

                // Reveal keeps the content still and lets a scissor uncover it from the anchored edge.
                val visibleW = if (sliding) rowW * scale else rowW * scale * life
                val clipX = if (sliding || !corner.isRight) contentX else homeX + rowW * scale - visibleW
                rowRects += floatArrayOf(clipX, rowGuiY, visibleW, layout.rowH * scale)

                val fTop = index == firstVis
                // The bottom overhang slice (local x) with no module beneath it.
                val nw = nextW[index]
                val bFrom: Float
                val bTo: Float
                when {
                    nw < 0f -> { bFrom = 0f; bTo = rowW } // last visible row: whole bottom is exposed
                    corner.isRight -> { bFrom = 0f; bTo = (rowW - nw).coerceAtLeast(0f) } // right-flush: left steps out
                    else -> { bFrom = nw.coerceIn(0f, rowW); bTo = rowW } // left-flush: right steps out
                }
                if (sliding) {
                    drawRow(ctx, entry, index, contentX, rowGuiY, rowW, layout, life, glass, fTop, bFrom, bTo)
                } else {
                    // The reveal scissor must leave room for the outward halo, or it clips the haze.
                    val m = if (edgeHaze) (hazeSize + 1f) * scale else 0f
                    clipGui(ctx, clipX - m, rowGuiY - m, visibleW + 2f * m, layout.rowH * scale + 2f * m) {
                        drawRow(ctx, entry, index, contentX, rowGuiY, rowW, layout, life, glass, fTop, bFrom, bTo)
                    }
                }

                targetY += layout.rowH * life
            }
            bounds.rects = rowRects
        }

        /** Paints one row at ([guiX], [guiY]); everything inside is in local (pre-scale) units. */
        @Suppress("LongParameterList")
        private fun drawRow(
            ctx: GuiGraphicsExtractor,
            entry: Entry,
            index: Int,
            guiX: Float,
            guiY: Float,
            rowW: Float,
            layout: Layout,
            life: Float,
            glass: TextureSetup?,
            featherTop: Boolean,
            bottomFrom: Float,
            bottomTo: Float,
        ) {
            val rowH = layout.rowH
            // The gradient is driven by the row's own offset in the list, so the pattern does not
            // change when the element is dragged somewhere else.
            val color = rowColor(index, entry.smoothY)

            ctx.pose().pushMatrix()
            ctx.pose().translate(guiX, guiY)
            ctx.pose().scale(scale, scale)

            if (slab) {
                // Square and flush, so neighbouring rows merge into one stepped column.
                if (edgeHaze) {
                    // Halo the outer edges; the bottom halo only covers [bottomFrom..bottomTo] — the
                    // overhang with no module under it — so seams over the next row stay continuous.
                    val fl = hazeSize
                    val fr = hazeSize
                    val ft = if (featherTop) hazeSize else 0f
                    val fb = if (bottomTo > bottomFrom) hazeSize else 0f
                    val phase = entry.smoothY
                    if (glass != null) {
                        drawGlassRectHazed(
                            ctx, glass, 0f, 0f, rowW, rowH, life,
                            fl, fr, ft, fb, bottomFrom, bottomTo, hazeWobble, phase,
                        )
                    }
                    drawDarkHazed(
                        ctx, 0f, 0f, rowW, rowH, 255f * darkening * life,
                        fl, fr, ft, fb, bottomFrom, bottomTo, hazeWobble, phase,
                    )
                } else {
                    if (glass != null) {
                        drawGlassRect(ctx, glass, 0f, 0f, rowW, rowH, life)
                    }
                    val alpha = (255f * darkening).toInt().coerceIn(0, 255)
                    ctx.drawQuad(0f, 0f, rowW, rowH, Color4b(0, 0, 0, alpha).alphaF(life), null)
                }
            }

            if (layout.barWidth > 0f) {
                val barX = if (corner.isRight) rowW - BAR_MARGIN - layout.barWidth else BAR_MARGIN
                // Inset top and bottom so each row shows its own short bar.
                ctx.drawQuad(
                    barX, BAR_INSET, barX + layout.barWidth, rowH - BAR_INSET,
                    color.alphaF(life), null,
                )
            }

            val textX = if (corner.isRight) PAD_X else layout.barSpace + PAD_X
            val textY = PAD_Y
            val nameColor = if (colorNames) color else P.white

            drawText(ctx, WatermarkFont.BRAND, entry.name, textX, textY, NAME_PX, layout, nameColor, life)

            val tag = entry.tagText
            if (tag != null) {
                val tagY = textY + (layout.textH - layout.tagH) * 0.5f
                drawText(
                    ctx, WatermarkFont.TEXT, tag,
                    textX + entry.nameWidth + TAG_GAP, tagY, TAG_PX, layout, TAG_COLOR, life,
                )
            }

            ctx.pose().popMatrix()
        }

        /** One string plus the 1.8-style drop shadow one unit down-right behind it. */
        @Suppress("LongParameterList")
        private fun drawText(
            ctx: GuiGraphicsExtractor,
            font: WatermarkFont,
            text: String,
            x: Float,
            y: Float,
            px: Float,
            layout: Layout,
            color: Color4b,
            life: Float,
        ) {
            val shadowColor = shadowOf(color).alphaF(life).argb
            font.submit(ctx, text, x + SHADOW_OFFSET, y + SHADOW_OFFSET, px, layout.density) { shadowColor }

            val argb = color.alphaF(life).argb
            font.submit(ctx, text, x, y, px, layout.density) { argb }
        }

        /** The font renderer's shadow tint: the same hue at a quarter brightness. */
        private fun shadowOf(color: Color4b) = Color4b(
            (color.r * 0.25f).toInt(),
            (color.g * 0.25f).toInt(),
            (color.b * 0.25f).toInt(),
            color.a,
        )

        /** The original's theme math, fed with the HUD accent (or the manual pair). */
        private fun rowColor(index: Int, localY: Float): Color4b = when (colorMode) {
            ArrayListColorMode.STATIC -> gradientFrom
            ArrayListColorMode.FADE ->
                HudAccent.mix(gradientFrom, gradientTo, HudAccent.blendFactor(y = localY))
            ArrayListColorMode.BREATHE ->
                HudAccent.mix(gradientFrom, gradientTo, HudAccent.blendFactor())
            ArrayListColorMode.RAINBOW ->
                HudAccent.rainbow(RAINBOW_ROW_DELAY_MS * index / RAINBOW_ROW_DIVISOR)
        }

        private fun passesFilter(module: ClientModule) = when (moduleFilter) {
            ArrayListFilter.ALL -> true
            ArrayListFilter.EXCLUDE_RENDER -> module.category !== ModuleCategories.RENDER
            ArrayListFilter.ONLY_BOUND -> !module.bind.isUnbound
        }

        private fun decorate(text: String): String {
            var result = text
            if (lowercase) result = result.lowercase()
            if (removeSpaces) result = result.replace(" ", "")
            return result
        }
    }

    // ================================================================== *
    // Potions (active status effects)
    // ================================================================== *

    private object PotionsEl : ToggleableValueGroup(this, "Potions", true) {

        // Blade's card structure (icon over m:ss over a tricolor drain bar, no names)
        // rebuilt in the Hellhit material: translucent slab, our radii, our fades.
        private const val PAD = 5.5f
        private const val CARD_W = 21f
        private const val CARD_H = 22f
        private const val ICON = 10f
        private const val BAR_W = 11f
        private const val BAR_H = 2f
        private const val DIV_GAP = 3f
        private const val DIV_W = 0.7f
        private const val TIME_PX = 6.5f

        private val style by enumChoice("Style", PotionStyle.CARDS)
        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 4f, 0f..1000f)
        private var offsetY by float("OffsetY", 80f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)

        val bounds = Bounds()

        private val slab = Color4b(11, 11, 12, 205)
        private val amber = Color4b(245, 191, 79, 255)

        // ---- "Effects" panel style (Figma spec, in local units ≈ half the 2x mockup) ----
        private const val P_TITLE = "Effects"
        private const val P_PAD = 6f
        private const val P_TITLE_H = 11f
        private const val P_ROW_H = 11f
        private const val P_ROW_GAP = 1.5f
        private const val P_ICON = 7.5f
        private const val P_ICON_BOX = 10f
        private const val P_NAME_PX = 7f
        private const val P_LVL_PX = 6f
        private const val P_TITLE_PX = 7.5f
        private const val P_TIME_PX = 6f
        private const val P_CHIP_W = 22f
        private const val P_CHIP_H = 10f
        private const val P_R = 3.5f
        private const val P_CHIP_R = 2.5f

        private val panelBg = Color4b(15, 15, 15, 242)
        private val panelBorder = Color4b(255, 255, 255, 18)
        private val chipBg = Color4b(39, 39, 42, 255)
        private val chipBgHarm = Color4b(71, 32, 32, 255)
        private val chipBorder = Color4b(255, 255, 255, 10)
        private val nameGrey = Color4b(200, 200, 200, 255)
        private val harmName = Color4b(255, 94, 94, 255)
        private val harmLvl = Color4b(255, 127, 127, 255)
        private val titleIconBg = Color4b(38, 36, 61, 255)

        /** Highest seen duration per effect — reference for the drain bar. */
        private val maxDurations = HashMap<String, Int>()
        private val appearStart = HashMap<String, Long>()
        private var smoothW = -1f

        private class Card(
            val sprite: Identifier?,
            val time: String,
            val fraction: Float,
            val harmful: Boolean,
            val appear: Float,
        )

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) return@handler
            val ctx = event.context
            val now = System.currentTimeMillis()

            val effects = mc.player?.activeEffects?.toList().orEmpty()
            if (effects.isEmpty() && !editMode()) {
                maxDurations.clear()
                appearStart.clear()
                smoothW = -1f
                return@handler
            }

            if (style == PotionStyle.PANEL) {
                val rows = if (effects.isEmpty()) showcasePanel() else buildPanelRows(effects, event.tickDelta, now)
                if (rows.isNotEmpty()) drawPanel(ctx, rows)
                return@handler
            }

            val cards = if (effects.isEmpty()) showcaseCards() else buildCards(effects, event.tickDelta, now)
            if (cards.isEmpty()) return@handler

            val d = density(scale)
            val n = cards.size
            val targetW = PAD * 2f + n * CARD_W + (n - 1) * (DIV_GAP * 2f + DIV_W)
            val h = PAD * 2f + CARD_H

            // the panel width follows card count smoothly instead of Blade's hard jump
            smoothW = if (smoothW < 0f) {
                targetW
            } else {
                val diff = targetW - smoothW
                if (kotlin.math.abs(diff) < 0.4f) targetW else smoothW + diff * 0.25f
            }
            val w = smoothW

            val screenW = mc.window.guiScaledWidth.toFloat()
            val screenH = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.POTIONS && editMode()) {
                val nx = mouseGuiX() - grabDX
                val ny = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenW - nx - w * scale else nx).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenH - ny - h * scale else ny).coerceIn(0f, 1000f)
            }
            val anchorX = if (corner.isRight) screenW - offsetX - w * scale else offsetX
            val anchorY = if (corner.isBottom) screenH - offsetY - h * scale else offsetY
            bounds.set(anchorX, anchorY, w * scale, h * scale)

            ctx.pose().pushMatrix()
            ctx.pose().translate(anchorX, anchorY)
            ctx.pose().scale(scale, scale)

            // one continuous translucent slab, clipped to the smoothed width
            ctx.drawRoundedRect(0f, 0f, w, h, R_PANEL, slab, null)

            val timeH = WatermarkFont.TEXT.lineHeight(TIME_PX, d)
            var cx = PAD
            for ((index, card) in cards.withIndex()) {
                val life = card.appear
                val cardTop = PAD

                // icon, top-centered in the card
                if (card.sprite != null) {
                    ctx.blitSprite(
                        RenderPipelines.GUI_TEXTURED, card.sprite,
                        Math.round(cx + (CARD_W - ICON) / 2f), Math.round(cardTop),
                        Math.round(ICON), Math.round(ICON),
                    )
                }

                // m:ss centered under the icon; harmful reads red
                val timeW = WatermarkFont.TEXT.width(card.time, TIME_PX, d)
                val timeColor = if (card.harmful) P.rose else P.white
                WatermarkFont.TEXT.submit(
                    ctx, card.time,
                    cx + (CARD_W - timeW) / 2f, cardTop + ICON + 1.5f, TIME_PX, d,
                ) { timeColor.alphaF(life).argb }

                // tricolor drain bar, bottom-centered: green > 50%, amber > 25%, rose
                // below; harmful is always rose. Fill hides when narrower than its height.
                val barX = cx + (CARD_W - BAR_W) / 2f
                val barY = cardTop + CARD_H - BAR_H
                ctx.drawRoundedRect(
                    barX, barY, barX + BAR_W, barY + BAR_H, BAR_H / 2f,
                    Color4b(255, 255, 255, 24).alphaF(life), null,
                )
                val fillW = BAR_W * card.fraction.coerceIn(0f, 1f)
                if (fillW >= BAR_H) {
                    val barColor = when {
                        card.harmful -> P.rose
                        card.fraction > 0.5f -> P.emerald
                        card.fraction > 0.25f -> amber
                        else -> P.rose
                    }
                    ctx.drawRoundedRect(
                        barX, barY, barX + fillW, barY + BAR_H, BAR_H / 2f,
                        barColor.alphaF(life), null,
                    )
                }

                // hairline divider between cards
                if (index < cards.size - 1) {
                    val divX = cx + CARD_W + DIV_GAP
                    ctx.drawQuad(
                        divX, cardTop, divX + DIV_W, cardTop + CARD_H,
                        Color4b(255, 255, 255, 22).alphaF(life), null,
                    )
                }

                cx += CARD_W + DIV_GAP * 2f + DIV_W
            }

            ctx.pose().popMatrix()
        }

        private fun buildCards(effects: List<MobEffectInstance>, tickDelta: Float, now: Long): List<Card> {
            val activeKeys = HashSet<String>(effects.size)
            val cards = effects.map { instance ->
                val sprite = Gui.getMobEffectSprite(instance.effect)
                val key = sprite.toString()
                activeKeys += key

                val maxDuration = maxDurations.merge(key, instance.duration, ::maxOf) ?: instance.duration
                val fraction = if (instance.isInfiniteDuration) {
                    1f
                } else {
                    ((instance.duration - tickDelta) / maxDuration.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                val time = if (instance.isInfiniteDuration) {
                    "inf"
                } else {
                    val seconds = instance.duration / 20
                    "%d:%02d".format(seconds / 60, seconds % 60)
                }
                val appearAt = appearStart.getOrPut(key) { now }

                Card(
                    sprite, time, fraction,
                    harmful = instance.effect.value().category == MobEffectCategory.HARMFUL,
                    appear = easeInOutCubic((now - appearAt) / IN_MS),
                )
            }
            maxDurations.keys.retainAll(activeKeys)
            appearStart.keys.retainAll(activeKeys)
            return cards
        }

        /** Chat-open preview with no real effects: three representative fake cards. */
        private fun showcaseCards(): List<Card> = listOf(
            Card(Gui.getMobEffectSprite(MobEffects.FIRE_RESISTANCE), "1:52", 0.9f, harmful = false, appear = 1f),
            Card(Gui.getMobEffectSprite(MobEffects.SPEED), "0:47", 0.4f, harmful = false, appear = 1f),
            Card(Gui.getMobEffectSprite(MobEffects.HUNGER), "0:30", 1f, harmful = true, appear = 1f),
        )

        // ---- "Effects" panel style ---------------------------------------------------------- *

        private class PanelRow(
            val name: String,
            val lvl: String,
            val time: String,
            val harmful: Boolean,
            val sprite: Identifier?,
        )

        private fun buildPanelRows(effects: List<MobEffectInstance>, tickDelta: Float, now: Long): List<PanelRow> {
            val activeKeys = HashSet<String>(effects.size)
            val rows = effects.map { instance ->
                val effect = instance.effect.value()
                val sprite = Gui.getMobEffectSprite(instance.effect)
                activeKeys += sprite.toString()
                maxDurations.merge(sprite.toString(), instance.duration, ::maxOf)
                val time = if (instance.isInfiniteDuration) {
                    "inf"
                } else {
                    val seconds = instance.duration / 20
                    "%d:%02d".format(seconds / 60, seconds % 60)
                }
                PanelRow(
                    name = effect.displayName.string,
                    lvl = "LVL ${instance.amplifier + 1}",
                    time = time,
                    harmful = effect.category == MobEffectCategory.HARMFUL,
                    sprite = sprite,
                )
            }
            maxDurations.keys.retainAll(activeKeys)
            return rows
        }

        private fun showcasePanel(): List<PanelRow> = listOf(
            PanelRow("Fire Resistance", "LVL 1", "7:59", false, Gui.getMobEffectSprite(MobEffects.FIRE_RESISTANCE)),
            PanelRow("Strength", "LVL 3", "2:26", false, Gui.getMobEffectSprite(MobEffects.STRENGTH)),
            PanelRow("Poison", "LVL 2", "0:12", true, Gui.getMobEffectSprite(MobEffects.POISON)),
        )

        @Suppress("detekt:LongMethod")
        private fun drawPanel(ctx: GuiGraphicsExtractor, rows: List<PanelRow>) {
            val d = density(scale)
            val titleH = WatermarkFont.BRAND.lineHeight(P_TITLE_PX, d)

            var contentW = P_ICON_BOX + 4f + WatermarkFont.BRAND.width(P_TITLE, P_TITLE_PX, d)
            for (r in rows) {
                val nameW = WatermarkFont.BRAND.width(r.name, P_NAME_PX, d)
                val lvlW = WatermarkFont.TEXT.width(r.lvl, P_LVL_PX, d)
                contentW = maxOf(contentW, P_ICON + 3f + nameW + 3f + lvlW + 8f + P_CHIP_W)
            }
            val w = P_PAD * 2f + contentW
            val listTop = P_PAD + P_TITLE_H + 2f
            val listH = rows.size * P_ROW_H + (rows.size - 1) * P_ROW_GAP
            val h = listTop + listH + P_PAD

            val screenW = mc.window.guiScaledWidth.toFloat()
            val screenH = mc.window.guiScaledHeight.toFloat()
            if (dragging == DragTarget.POTIONS && editMode()) {
                val nx = mouseGuiX() - grabDX
                val ny = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenW - nx - w * scale else nx).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenH - ny - h * scale else ny).coerceIn(0f, 1000f)
            }
            val anchorX = if (corner.isRight) screenW - offsetX - w * scale else offsetX
            val anchorY = if (corner.isBottom) screenH - offsetY - h * scale else offsetY
            bounds.set(anchorX, anchorY, w * scale, h * scale)

            ctx.pose().pushMatrix()
            ctx.pose().translate(anchorX, anchorY)
            ctx.pose().scale(scale, scale)

            // near-solid #0F0F0F card, hairline border, and a faint accent wash from the top-right
            ctx.drawRoundedRect(0f, 0f, w, h, P_R, panelBg, panelBorder, 1f)
            ctx.drawGradientRoundedRect(
                0f, 0f, w, h, P_R,
                accent.alphaF(0.03f).argb, 0, accent.alphaF(0.015f).argb, accent.alphaF(0.06f).argb,
            )

            // title: accent-tinted icon square + "Effects"
            ctx.drawRoundedRect(P_PAD, P_PAD, P_PAD + P_ICON_BOX, P_PAD + P_ICON_BOX, P_CHIP_R, titleIconBg, null)
            drawPotionGlyph(ctx, P_PAD, P_PAD, P_ICON_BOX)
            WatermarkFont.BRAND.submit(
                ctx, P_TITLE, P_PAD + P_ICON_BOX + 4f, P_PAD + (P_ICON_BOX - titleH) / 2f, P_TITLE_PX, d,
            ) { P.white.argb }

            var y = listTop
            for (r in rows) {
                drawPanelRow(ctx, r, P_PAD, y, contentW, d)
                y += P_ROW_H + P_ROW_GAP
            }
            ctx.pose().popMatrix()
        }

        @Suppress("LongParameterList")
        private fun drawPanelRow(ctx: GuiGraphicsExtractor, r: PanelRow, x: Float, y: Float, w: Float, d: Float) {
            val cy = y + P_ROW_H / 2f

            if (r.sprite != null) {
                ctx.blitSprite(
                    RenderPipelines.GUI_TEXTURED, r.sprite,
                    Math.round(x), Math.round(cy - P_ICON / 2f), Math.round(P_ICON), Math.round(P_ICON),
                )
            }

            val nameColor = if (r.harmful) harmName else nameGrey
            val nameH = WatermarkFont.BRAND.lineHeight(P_NAME_PX, d)
            WatermarkFont.BRAND.submit(ctx, r.name, x + P_ICON + 3f, cy - nameH / 2f, P_NAME_PX, d) { nameColor.argb }
            val nameW = WatermarkFont.BRAND.width(r.name, P_NAME_PX, d)

            val lvlColor = if (r.harmful) harmLvl else accent
            val lvlH = WatermarkFont.TEXT.lineHeight(P_LVL_PX, d)
            WatermarkFont.TEXT.submit(
                ctx, r.lvl, x + P_ICON + 3f + nameW + 3f, cy - lvlH / 2f, P_LVL_PX, d,
            ) { lvlColor.argb }

            // time chip, right-aligned: bg (red when harmful), a status dot, the m:ss centred after it
            val chipX = x + w - P_CHIP_W
            val chipY = cy - P_CHIP_H / 2f
            ctx.drawRoundedRect(
                chipX, chipY, chipX + P_CHIP_W, chipY + P_CHIP_H, P_CHIP_R,
                if (r.harmful) chipBgHarm else chipBg, chipBorder, 1f,
            )
            val dotColor = if (r.harmful) harmLvl else accent
            ctx.drawCircle(chipX + 4f, cy, 2.2f) { dotColor.argb }
            val timeW = WatermarkFont.TEXT.width(r.time, P_TIME_PX, d)
            val timeH = WatermarkFont.TEXT.lineHeight(P_TIME_PX, d)
            val timeSpaceL = chipX + 7f
            val timeX = timeSpaceL + ((chipX + P_CHIP_W - 2f) - timeSpaceL - timeW) / 2f
            WatermarkFont.TEXT.submit(ctx, r.time, timeX, cy - timeH / 2f, P_TIME_PX, d) { nameGrey.argb }
        }

        /** A tiny accent potion inside the title's icon square. */
        private fun drawPotionGlyph(ctx: GuiGraphicsExtractor, bx: Float, by: Float, s: Float) {
            val cx = bx + s / 2f
            ctx.drawCircle(cx, by + s * 0.62f, s * 0.26f) { accent.argb }
            ctx.drawRoundedRect(cx - s * 0.09f, by + s * 0.22f, cx + s * 0.09f, by + s * 0.46f, 0.5f, accent, null)
            ctx.drawRoundedRect(
                cx - s * 0.13f, by + s * 0.16f, cx + s * 0.13f, by + s * 0.24f, 0.8f, accent.alphaF(0.8f), null,
            )
        }
    }

    // ================================================================== *
    // Notifications
    // ================================================================== *

    private object NotificationsEl : ToggleableValueGroup(this, "Notifications", true) {

        private const val PAD_X = 7f
        private const val PAD_Y = 4f
        private const val STACK_GAP = 3f
        private const val STRIPE_W = 2f

        private val corner by enumChoice("Corner", Corner.BOTTOM_RIGHT)
        private var offsetX by float("OffsetX", 6f, 0f..1000f)
        private var offsetY by float("OffsetY", 40f, 0f..1000f)
        private val scale by float("Scale", 1f, 0.5f..3f)
        private val lifetime by float("Lifetime", 2.5f, 1f..10f, "s")
        private val maxStack by int("MaxStack", 5, 1..10)
        private val moduleToggles by boolean("ModuleToggles", true)

        val bounds = Bounds()

        private class Toast(
            val title: String,
            val message: String,
            val color: Color4b,
            val createdAt: Long,
            var expireAt: Long,
        )

        private val toasts = ArrayDeque<Toast>()

        private fun severityColor(severity: NotificationEvent.Severity): Color4b = when (severity) {
            NotificationEvent.Severity.INFO -> accent
            NotificationEvent.Severity.SUCCESS, NotificationEvent.Severity.ENABLED -> P.emerald
            NotificationEvent.Severity.ERROR -> P.rose
            NotificationEvent.Severity.DISABLED -> P.dim
        }

        private fun push(title: String, message: String, color: Color4b) {
            val now = System.currentTimeMillis()
            toasts.addLast(Toast(title, message, color, now, now + (lifetime * 1000f).toLong()))
            while (toasts.count { it.expireAt > now } > maxStack) {
                toasts.firstOrNull { it.expireAt > now }?.expireAt = now
            }
        }

        @Suppress("unused")
        private val notificationHandler = handler<NotificationEvent> { event ->
            // module toggles arrive through ModuleToggleEvent below in a cleaner format
            if (event.severity == NotificationEvent.Severity.ENABLED ||
                event.severity == NotificationEvent.Severity.DISABLED
            ) {
                return@handler
            }
            push(event.title, event.message, severityColor(event.severity))
        }

        @Suppress("unused")
        private val toggleHandler = handler<ModuleToggleEvent> { event ->
            if (!moduleToggles || event.hidden) return@handler
            push(
                event.moduleName,
                if (event.enabled) "enabled" else "disabled",
                if (event.enabled) P.emerald else P.dim,
            )
        }

        @Suppress("unused", "detekt:LongMethod", "detekt:CognitiveComplexMethod")
        private val renderHandler = handler<OverlayRenderEvent> { event ->
            if (HideAppearance.isHidingNow) return@handler
            val now = System.currentTimeMillis()
            toasts.removeIf { now > it.expireAt + OUT_MS.toLong() }

            val rendered: List<Toast> = if (toasts.isEmpty()) {
                if (!editMode()) return@handler
                listOf(Toast("Notifications", "drag me", accent, now - IN_MS.toLong(), Long.MAX_VALUE))
            } else {
                toasts
            }

            val ctx = event.context
            val titlePx = 6.6f
            val msgPx = 6.2f
            val d = density(scale)
            val titleH = WatermarkFont.BRAND.lineHeight(titlePx, d)
            val toastH = titleH + PAD_Y * 2f

            val screenW = mc.window.guiScaledWidth.toFloat()
            val screenH = mc.window.guiScaledHeight.toFloat()

            var maxW = 70f
            val widths = FloatArray(rendered.size)
            val lives = FloatArray(rendered.size)
            var stackH = 0f
            for ((index, toast) in rendered.withIndex()) {
                val inT = ((now - toast.createdAt) / IN_MS).coerceIn(0f, 1f)
                val outT = if (toast.expireAt == Long.MAX_VALUE) {
                    0f
                } else {
                    if (now <= toast.expireAt) 0f else ((now - toast.expireAt) / OUT_MS).coerceIn(0f, 1f)
                }
                val life = easeInOutCubic(inT) * (1f - easeInOutCubic(outT))
                lives[index] = life
                val titleW = WatermarkFont.BRAND.width(toast.title, titlePx, d)
                val msgW = WatermarkFont.TEXT.width(toast.message, msgPx, d)
                val w = PAD_X + STRIPE_W + 5f + titleW + 6f + msgW + PAD_X
                widths[index] = maxOf(w, 70f)
                maxW = maxOf(maxW, widths[index])
                stackH += (toastH + STACK_GAP) * life
            }
            stackH = (stackH - STACK_GAP).coerceAtLeast(toastH)

            if (dragging == DragTarget.NOTIFICATIONS && editMode()) {
                val nx = mouseGuiX() - grabDX
                val ny = mouseGuiY() - grabDY
                offsetX = (if (corner.isRight) screenW - nx - maxW * scale else nx).coerceIn(0f, 1000f)
                offsetY = (if (corner.isBottom) screenH - ny - stackH * scale else ny).coerceIn(0f, 1000f)
            }
            val anchorX = if (corner.isRight) screenW - offsetX - maxW * scale else offsetX
            val anchorY = if (corner.isBottom) screenH - offsetY - stackH * scale else offsetY
            bounds.set(anchorX, anchorY, maxW * scale, stackH * scale)

            // newest toast pinned at the anchored edge, stack growing upward
            var stackY = 0f
            for (index in rendered.indices.reversed()) {
                val toast = rendered[index]
                val life = lives[index]
                if (life <= 0.02f) continue
                val w = widths[index]

                val slotY = anchorY + (stackH * scale) - (stackY + toastH * life) * scale
                val revealW = w * life
                val toastGuiX = if (corner.isRight) {
                    anchorX + (maxW - w) * scale + (w - revealW) * scale
                } else {
                    anchorX
                }

                clipGui(ctx, toastGuiX, slotY, revealW * scale, toastH * scale) {
                    ctx.pose().pushMatrix()
                    val contentX = if (corner.isRight) anchorX + (maxW - w) * scale else anchorX
                    ctx.pose().translate(contentX, slotY)
                    ctx.pose().scale(scale, scale)

                    ctx.drawRoundedRect(
                        0f, 0f, w, toastH, R_ROW,
                        P.panel.alphaF(life), P.panelBorder.alphaF(life), 1f,
                    )
                    // severity stripe
                    ctx.drawRoundedRect(
                        PAD_X - 2f, 3f, PAD_X - 2f + STRIPE_W, toastH - 3f, 1f,
                        toast.color.alphaF(life), null,
                    )

                    val textX = PAD_X + STRIPE_W + 5f - 2f
                    WatermarkFont.BRAND.submit(ctx, toast.title, textX, PAD_Y, titlePx, d) {
                        P.white.alphaF(life).argb
                    }
                    val titleW = WatermarkFont.BRAND.width(toast.title, titlePx, d)
                    val msgH = WatermarkFont.TEXT.lineHeight(msgPx, d)
                    WatermarkFont.TEXT.submit(
                        ctx, toast.message,
                        textX + titleW + 6f, PAD_Y + (titleH - msgH) * 0.7f, msgPx, d,
                    ) { P.desc.alphaF(life).argb }

                    // lifetime drain along the bottom inner edge
                    if (toast.expireAt != Long.MAX_VALUE) {
                        val total = (toast.expireAt - toast.createdAt).coerceAtLeast(1L)
                        val left = ((toast.expireAt - now).toFloat() / total).coerceIn(0f, 1f)
                        ctx.drawQuad(
                            2f, toastH - 1.4f, 2f + (w - 4f) * left, toastH - 0.6f,
                            toast.color.alphaF(0.65f * life), null,
                        )
                    }

                    ctx.pose().popMatrix()
                }

                stackY += (toastH + STACK_GAP) * life
            }
        }
    }
}
