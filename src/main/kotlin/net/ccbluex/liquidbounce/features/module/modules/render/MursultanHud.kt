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
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.MursultanClickGuiSettings
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.MursultanIcons
import net.ccbluex.liquidbounce.utils.combat.intel.TargetIntel.nametagHealth
import net.ccbluex.liquidbounce.features.module.modules.render.hudtest.HudTestItemIcons
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.util.ARGB
import org.lwjgl.glfw.GLFW
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The **Mursultan** HUD theme: the in-game half of the Mursultan design language, mounted as a
 * mode of [ModuleHud.theme].
 *
 * Reproduced from reference captures of the original client (2026-09-20), measured by sampling
 * pixels rather than by eye, the same way the ClickGUI was. **Every constant below is in the
 * reference's own pixels**, at its 100 % HUD scale; [REFERENCE_GUI_SCALE] converts them to GUI
 * units once, in the pose, so the proportions hold at any resolution and any GUI scale.
 *
 * What the original puts on screen, and what this draws:
 *
 * - a **brand pill** (logo + client name) and, 8 px to its right, an **info pill** carrying
 *   player / fps / ping / clock, each segment an icon plus its text;
 * - a **coords pill** on the second row with position / speed / server tick rate;
 * - **panels** below them, each an icon gutter (39 px, closed by a hairline divider) plus rows:
 *   the **hotkeys** panel lists the enabled modules with their binds, the **potions** panel the
 *   active effects with their remaining time.
 *
 * Icons are the original's own, extracted from its MTSDF HUD atlas to white PNGs under
 * `textures/clickgui/mursultan/hud/` and tinted at draw time. The accent is shared with the
 * ClickGUI ([MursultanClickGuiSettings.accent]).
 *
 * Nothing here draws unless [ModuleHud] resolves this theme, so two HUDs can never overlap the
 * way the separate HUD modules used to.
 */
@Suppress("TooManyFunctions")
class MursultanHud(override val parent: ModeValueGroup<*>) : Mode("Mursultan") {

    private val watermark by boolean("Watermark", true)
    private val info by boolean("Info", true)
    private val coords by boolean("Coords", true)

    /**
     * The hotkeys panel: the original's **bind list**, not an arraylist.
     *
     * It lists modules that actually have a key assigned — that is what makes it a hotkeys
     * panel. The first cut listed every enabled module instead, which is precisely an
     * arraylist wearing a keyboard icon ("какого хуя у нас хоткеи это арай лист?",
     * 2026-09-20). The reference capture shows a single row, `Attack Aura  X`.
     */
    private val moduleList by boolean("Hotkeys", true)

    /** Whether each row prints its key on the right. */
    private val binds by boolean("ShowBinds", true)

    /** Restrict the list to modules that are also currently on. */
    private val hotkeysOnlyEnabled by boolean("HotkeysOnlyEnabled", false)

    private val potions by boolean("Potions", true)

    private val targetHud by boolean("TargetHud", true)

    /** Equipped armour with durability bars, in the spirit of the Glass theme's row. */
    private val armor by boolean("Armor", true)
    private val armorDurability by boolean("ArmorDurability", true)
    private val armorItemScale by float("ArmorItemScale", 1f, 0.5f..3f)

    /** Inventory counters: totems, crystals, pearls and the rest. */
    private val counters by boolean("Counters", true)
    private val counterItemScale by float("CounterItemScale", 1f, 0.5f..3f)

    private val notifications by boolean("Notifications", true)


    /** Master scale; each element multiplies its own on top of it. */
    private val scale by float("Scale", 1f, 0.5f..2f)

    private val topScale by float("TopScale", 1f, 0.4f..3f)
    private val coordsScale by float("CoordsScale", 1f, 0.4f..3f)
    private val hotkeysScale by float("HotkeysScale", 1f, 0.4f..3f)
    private val potionsScale by float("PotionsScale", 1f, 0.4f..3f)
    private val notifyScale by float("NotifyScale", 1f, 0.4f..3f)
    private val armorScale by float("ArmorScale", 1f, 0.4f..3f)
    private val targetScale by float("TargetScale", 1f, 0.4f..3f)
    private val notifyLifetime by float("NotifyLifetime", 2.5f, 1f..10f, "s")
    private val notifyMaxStack by int("NotifyMaxStack", 4, 1..10)
    private val notifyToggles by boolean("NotifyModuleToggles", true)

    // Anchors, in REFERENCE PIXELS — the same unit every constant in this class uses.
    //
    // The first cut mixed units here: the pills got GUI-unit numbers while the panels kept
    // reference pixels, so every element landed bunched in the top-left corner on top of the
    // next one ("пол худа не работает", 2026-09-20). The keys carry an `Anchor` prefix because
    // the broken numbers were already persisted — new defaults alone would not have applied. Each element carries its own so it can be dragged freely;
    // see the drag block below (edit mode = the chat screen, as in the Hellhit HUD).
    private var topX by float("AnchorTopX", 76f, 0f..4000f)
    private var topY by float("AnchorTopY", 25f, 0f..4000f)
    private var coordsX by float("AnchorCoordsX", 76f, 0f..4000f)
    private var coordsY by float("AnchorCoordsY", 70f, 0f..4000f)
    private var hotkeysX by float("AnchorHotkeysX", 76f, 0f..4000f)
    private var hotkeysY by float("AnchorHotkeysY", 125f, 0f..4000f)
    private var potionsX by float("AnchorPotionsX", 76f, 0f..4000f)
    private var potionsY by float("AnchorPotionsY", 175f, 0f..4000f)

    /**
     * GUI units; negative means "centred horizontally" / "62 % down the screen".
     *
     * The keys carry a `Pos` prefix because the earlier build stored these in reference pixels:
     * a saved `740` re-read as GUI units puts the panel hundreds of units below a ~350-unit tall
     * screen, and the panel simply vanished. New names, new defaults.
     */
    private var targetX by float("TargetPosX", -1f, -1f..4000f)
    private var targetY by float("TargetPosY", -1f, -1f..4000f)

    private var armorX by float("AnchorArmorX", -1f, -1f..4000f)
    private var armorY by float("AnchorArmorY", 120f, 0f..2000f)
    private var countersX by float("AnchorCountersX", -1f, -1f..4000f)
    private var countersY by float("AnchorCountersY", 180f, 0f..2000f)

    /** Distance of the toast stack from the top, in reference pixels. */
    private var notifyTop by float("AnchorNotifyTop", 295f, 0f..2000f)

    /**
     * Centre of the toast stack. A negative value means "screen centre", which is where the
     * original puts it; dragging the stack pins it to a real coordinate instead.
     */
    private var notifyCenter by float("AnchorNotifyCenter", -1f, -1f..4000f)

    private companion object {
        /**
         * Body face: Inter **Regular**, which is what the original bundles for its HUD text
         * (`fonts/inter.ttf`, with `interm.ttf` only for emphasis). Everything here used
         * `BODY` (Medium) at first and read a notch too heavy — "шрифт какой-то
         * странный", 2026-09-20.
         */
        val BODY = WatermarkFont("resources/liquidbounce/fonts/Inter-Regular.ttf")

        /**
         * GUI scale of the capture the layout was measured on. The constants are that capture's
         * pixels; dividing by this turns them into GUI units.
         */
        const val REFERENCE_GUI_SCALE = 3f

        // - pills -
        const val PILL_H = 37f
        const val PILL_RADIUS = 10f
        const val PILL_GAP = 8f
        const val BRAND_PAD_L = 13f
        const val BRAND_LOGO = 12f
        const val BRAND_GAP = 24f
        const val BRAND_PAD_R = 10f
        const val INFO_PAD_L = 9f
        const val INFO_PAD_R = 12f
        const val ICON = 14f
        const val ICON_GAP = 8f
        const val SEGMENT_GAP = 25f

        // - panels -
        const val GUTTER = 39f
        const val PANEL_RADIUS = 10f
        const val PANEL_PAD = 13f
        const val PANEL_PAD_V = 6.5f
        const val ROW_H = 27f
        const val GUTTER_ICON = 16f
        const val VALUE_GAP = 24f

        // - type, reference pixels -
        const val BRAND_PX = 15f
        const val TEXT_PX = 14f

        const val IN_MS = 220f
        const val OUT_MS = 180f

        /** Vanilla sends the time sync every 20 server ticks — the basis of the tps readout. */
        const val TIME_SYNC_TICKS = 20.0

        // - target hud, ported from the Hellhit HUD (its own local units) -
        const val TARGET_W = 104f
        const val TARGET_H = 36f
        const val AVATAR = 32f
        const val AVATAR_X = 2.5f
        const val AVATAR_Y = 2f
        const val FRAME_T = 2.2f
        const val COL_X0 = 38.5f
        const val COL_X1 = 72f
        const val GRID_X = 76f
        const val GRID_Y = 6.5f
        const val CELL_W = 11f
        const val CELL_H = 12f
        const val GRID_GAP = 1.5f

        /** Hellhit renders this panel at 1.4; a touch smaller here, as asked. */
        const val TARGET_BASE = 1.15f

        /** How long the panel keeps showing a target after it stops being one. */
        const val TARGET_HOLD_MS = 400L

        // - armour / counter cells, in GUI units, matching the Glass theme one for one -
        const val CELL_PAD = 1.5f
        const val CELL_GAP = 3f
        const val CELL_RADIUS = 2f
        const val BAR_H = 1.6f
        const val BAR_GAP = 1.5f
        const val COUNT_GAP = 2f
        const val PLATE_PAD_X = 1.6f
        const val PLATE_PAD_Y = 1.2f
        const val PLATE_RADIUS = 2f

        /** Inter's cap height as a share of the em — the visual height of a row of digits. */
        const val DIGIT_CAP_FACTOR = 0.74f

        /**
         * Digit size on a count plate, in GUI units: the Glass theme's own `fontSize * 0.68`
         * at its default 12. It is NOT a reference pixel — the cells around it are whole
         * multiples of the 16 px sprite, so running this through the reference divisor made the
         * digits a quarter of their size and, as reported, illegible next to the icons.
         */
        const val COUNT_PX = 8.16f

        /** Durability bar: Glass's own ramp, red at empty to green at full. */
        val BAR_EMPTY = Color4b(224, 99, 95, 255)
        val BAR_FULL = Color4b(127, 203, 143, 255)

        /** Head first, like the vanilla row and the Glass theme's. */
        val ARMOR_SLOTS = listOf(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        )

        // - notifications, measured off the reference capture (plate 233 x 37) -
        const val TOAST_H = 37f
        const val TOAST_RADIUS = 12f
        const val TOAST_PAD_L = 10f
        const val TOAST_PAD_R = 23f
        const val TOAST_GAP = 5f
        const val TOAST_STACK_GAP = 8f
        // the reference is 47x20 with a stadium knob; trimmed to a round knob for the same
        // reason the ClickGUI switch was
        const val TOAST_TOGGLE_W = 40f
        const val TOAST_TOGGLE_H = 20f
        const val TOAST_PX = 16f
        const val TOAST_IN_MS = 200f
        const val TOAST_OUT_MS = 250f
    }

    private object Palette {
        val plate = Color4b(26, 26, 30, 238)
        val border = Color4b(20, 20, 23, 255)
        val divider = Color4b(24, 24, 28, 255)
        val text = Color4b(227, 227, 237, 255)
        val value = Color4b(255, 255, 255, 255)
        val icon = Color4b(190, 190, 198, 255)
        val cell = Color4b(36, 36, 41, 255)

        /** Toasts sit on a LIGHTER plate than the pills — measured `(48,48,58)` over the sky. */
        val toastPlate = Color4b(40, 40, 50, 238)

        /**
         * Off state of a toast's toggle. The ON state is the theme accent — the original uses a
         * fixed `#529DFF` there, but the accent is the point of having one.
         */
        val toastOff = Color4b(96, 96, 106, 255)
    }

    private val accent: Color4b
        get() {
            val argb = MursultanClickGuiSettings.accent
            return Color4b(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb), 255)
        }

    private var density = 1f

    /**
     * Scale of whatever is being drawn right now: the pose scale inside an element, or the
     * manual scale in the screen-anchored paths. Icons need it to bake themselves at the right
     * PHYSICAL size — see [MursultanIcons].
     */
    private var drawScale = 1f

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
        with(a = (a * f.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255))

    private fun width(font: WatermarkFont, text: String, px: Float) = font.width(text, px, density)

    private fun text(
        ctx: GuiGraphicsExtractor, font: WatermarkFont, text: String,
        x: Float, cy: Float, px: Float, color: Color4b,
    ) {
        val argb = color.argb
        font.submit(ctx, text, x, cy - font.lineHeight(px, density) / 2f, px, density) { argb }
    }

    private fun icon(ctx: GuiGraphicsExtractor, name: String, x: Float, cy: Float, size: Float, color: Color4b) =
        icon(ctx, name, x, cy, size, size, color)

    /**
     * Draws an icon baked to the exact physical size it covers, so it samples its texture ~1:1
     * instead of being minified from the 64 px master (which is what made the HUD look soft).
     */
    private fun icon(
        ctx: GuiGraphicsExtractor, name: String, x: Float, cy: Float, w: Float, h: Float, color: Color4b,
    ) {
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val deviceW = (w * drawScale * guiScale).roundToInt()
        val deviceH = (h * drawScale * guiScale).roundToInt()
        val setup = MursultanIcons.setup("mursultan/$name", deviceW, deviceH) ?: return
        ctx.drawTexQuad(setup, x, cy - h / 2f, x + w, cy + h / 2f, argb = color.argb)
    }

    // - Cached readouts -
    private var fpsValue = -1
    private var fpsText = ""
    private var pingValue = -1
    private var pingText = ""
    private var clockSecond = -1
    private var clockText = ""
    private var posKey = Long.MIN_VALUE
    private var posText = ""
    private var bpsValue = -1
    private var bpsText = ""
    private var tpsText = "20.0tps"

    private var lastTimeSync = 0L
    private var tpsValue = 20f

    private var shownSince = 0L

    /**
     * Server tick rate, measured the way every client measures it: vanilla emits the time sync
     * every [TIME_SYNC_TICKS] server ticks, so the wall-clock gap between two of them gives the
     * rate. Only a timestamp is touched here, which is safe on the netty thread.
     */
    @Suppress("unused")
    private val timeHandler = handler<PacketEvent> { event ->
        if (event.packet !is ClientboundSetTimePacket) return@handler
        val now = System.currentTimeMillis()
        val previous = lastTimeSync
        lastTimeSync = now
        if (previous == 0L) return@handler
        val seconds = (now - previous) / 1000.0
        if (seconds <= 0.0) return@handler
        val measured = (TIME_SYNC_TICKS / seconds).coerceIn(0.0, 20.0).toFloat()
        // a little smoothing: one slow packet must not make the readout jump
        tpsValue += (measured - tpsValue) * 0.5f
    }

    /** One hotkeys row, with its own in/out clock so the panel never jumps. */
    private class Row(val module: ClientModule) {
        var label = ""
        var value = ""

        /** A bound module that is currently off is listed, but greyed out. */
        var dimmed = false
        var appearAt = 0L
        var removeAt = 0L
    }

    /** One row handed to [drawPanel]: label, right-aligned value, life 0..1, and dimming. */
    private class PanelRow(val label: String, val value: String, val life: Float, val dim: Boolean = false)

    private val rows = LinkedHashMap<String, Row>()
    private val alive = HashSet<String>()

    /** What the pointer is currently dragging, if anything. */
    private enum class DragTarget { TOP, COORDS, HOTKEYS, POTIONS, NOTIFY, ARMOR, TARGET, COUNTERS }

    private var dragging: DragTarget? = null
    private var grabDX = 0f
    private var grabDY = 0f

    /**
     * Bounds of each element as last drawn, in **GUI units** — the space the mouse is in.
     * Reference pixels would not do: every element carries its own scale, so its on-screen size
     * is not a fixed multiple of its authored size.
     */
    private val bounds = HashMap<DragTarget, FloatArray>()

    /**
     * Edit mode is the chat screen — the same handle the Hellhit HUD uses, so both themes are
     * moved the same way: open chat, drag an element, close chat.
     */
    private fun editMode(): Boolean = mc.screen is ChatScreen

    private fun mouseGuiX(): Float = (mc.mouseHandler.xpos() / mc.window.guiScale).toFloat()

    private fun mouseGuiY(): Float = (mc.mouseHandler.ypos() / mc.window.guiScale).toFloat()

    /** Starts/stops drags from the physical button state, so a release is never missed. */
    private fun updateDrag() {
        if (!editMode()) {
            dragging = null
            return
        }
        val down = GLFW.glfwGetMouseButton(mc.window.handle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS
        if (!down) {
            dragging = null
            return
        }
        if (dragging != null) return
        val mx = mouseGuiX()
        val my = mouseGuiY()
        for ((target, box) in bounds) {
            if (mx >= box[0] && mx <= box[0] + box[2] && my >= box[1] && my <= box[1] + box[3]) {
                dragging = target
                grabDX = mx - box[0]
                grabDY = my - box[1]
                return
            }
        }
    }

    /** Writes the anchor a drag is currently moving, clamped into the visible screen. */
    private fun dragAnchor(target: DragTarget, unit: Float) {
        if (dragging != target || unit <= 0f) return
        val maxX = (mc.window.guiScaledWidth - 20f).coerceAtLeast(0f)
        val maxY = (mc.window.guiScaledHeight - 20f).coerceAtLeast(0f)
        val nx = (mouseGuiX() - grabDX).coerceIn(0f, maxX) / unit
        val ny = (mouseGuiY() - grabDY).coerceIn(0f, maxY) / unit
        when (target) {
            DragTarget.TOP -> { topX = nx; topY = ny }
            DragTarget.COORDS -> { coordsX = nx; coordsY = ny }
            DragTarget.HOTKEYS -> { hotkeysX = nx; hotkeysY = ny }
            DragTarget.POTIONS -> { potionsX = nx; potionsY = ny }
            DragTarget.ARMOR -> {
                val boxW = bounds[DragTarget.ARMOR]?.get(2) ?: 0f
                armorX = nx + boxW / 2f / unit
                armorY = ny
            }
            // GUI units, and the anchor is the panel's LEFT edge (not its centre like the
            // toast stack), so the dragged position is written straight through
            DragTarget.TARGET -> {
                targetX = nx
                targetY = ny
            }
            DragTarget.COUNTERS -> {
                val boxW = bounds[DragTarget.COUNTERS]?.get(2) ?: 0f
                countersX = nx + boxW / 2f / unit
                countersY = ny
            }
            DragTarget.NOTIFY -> {
                // the stack is centre-anchored, so convert the dragged left edge to its centre
                val boxW = bounds[DragTarget.NOTIFY]?.get(2) ?: 0f
                notifyCenter = nx + boxW / 2f / unit
                notifyTop = ny
            }
        }
    }

    /** A frame around a draggable element while the chat screen is open. */
    private fun editFrame(ctx: GuiGraphicsExtractor, target: DragTarget) {
        if (!editMode()) return
        val box = bounds[target] ?: return
        val color = if (dragging == target) accent else accent.with(a = 90)
        ctx.drawRoundedRect(box[0] - 2f, box[1] - 2f, box[0] + box[2] + 2f, box[1] + box[3] + 2f, 6f, null, color, 1f)
    }

    /**
     * Draws one element at its own anchor and its own scale.
     *
     * The anchor is positioned with the MASTER unit and the content is scaled by the element's
     * own factor on top of it, so resizing an element grows it in place instead of walking it
     * across the screen. [block] draws at the local origin and returns its size in reference
     * pixels; the bounds recorded for the drag are that size in GUI units.
     */
    private fun element(
        ctx: GuiGraphicsExtractor, target: DragTarget, elementScale: Float, unit: Float,
        block: (GuiGraphicsExtractor) -> Pair<Float, Float>,
    ) {
        dragAnchor(target, unit)
        val gx = when (target) {
            DragTarget.TOP -> topX
            DragTarget.COORDS -> coordsX
            DragTarget.HOTKEYS -> hotkeysX
            DragTarget.POTIONS -> potionsX
            // the toast stack and the armour row are not `element`s - they draw themselves,
            // centre-anchored, because their item/text quads must not sit under a pose scale
            DragTarget.NOTIFY, DragTarget.ARMOR, DragTarget.TARGET, DragTarget.COUNTERS -> 0f
        } * unit
        val gy = when (target) {
            DragTarget.TOP -> topY
            DragTarget.COORDS -> coordsY
            DragTarget.HOTKEYS -> hotkeysY
            DragTarget.POTIONS -> potionsY
            DragTarget.NOTIFY, DragTarget.ARMOR, DragTarget.TARGET, DragTarget.COUNTERS -> 0f
        } * unit

        val es = unit * elementScale
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        density = es * guiScale
        drawScale = es

        // Snap the origin to the device pixel grid. The font snaps glyphs horizontally through
        // the pose, but its baseline snap happens in LOCAL units — a fractional translation then
        // offsets every row by part of a pixel and the text blurs.
        val sx = (gx * guiScale).roundToInt() / guiScale
        val sy = (gy * guiScale).roundToInt() / guiScale

        ctx.pose().pushMatrix()
        ctx.pose().translate(sx, sy)
        ctx.pose().scale(es, es)
        val size = try {
            block(ctx)
        } finally {
            ctx.pose().popMatrix()
        }

        if (size.first <= 0f || size.second <= 0f) {
            bounds.remove(target)
            return
        }
        bounds.getOrPut(target) { FloatArray(4) }.also {
            it[0] = sx; it[1] = sy; it[2] = size.first * es; it[3] = size.second * es
        }
        editFrame(ctx, target)
    }

    /**
     * The render handler stops being called the moment another theme is picked or the HUD is
     * switched off, so the reset it does for a hidden HUD has to happen here as well - otherwise
     * the fade-in would not replay and a drag could come back stuck to the cursor.
     */
    override fun disable() {
        shownSince = 0L
        dragging = null
        bounds.clear()
        super.disable()
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (HideAppearance.isHidingNow || mc.options.hideGui) {
            shownSince = 0L
            dragging = null
            bounds.clear()
            return@handler
        }

        val ctx = event.context
        val now = System.currentTimeMillis()
        if (shownSince == 0L) shownSince = now
        val fade = easeInOutCubic((now - shownSince) / IN_MS)

        val unit = scale / REFERENCE_GUI_SCALE
        updateDrag()

        if (watermark || info) {
            element(ctx, DragTarget.TOP, topScale, unit) { c -> drawTopRow(c, fade) }
        } else {
            bounds.remove(DragTarget.TOP)
        }
        if (coords) {
            element(ctx, DragTarget.COORDS, coordsScale, unit) { c -> drawCoordsPill(c, fade) }
        } else {
            bounds.remove(DragTarget.COORDS)
        }
        if (moduleList) {
            element(ctx, DragTarget.HOTKEYS, hotkeysScale, unit) { c ->
                drawHotkeys(c, fade, now)
            }
        } else {
            bounds.remove(DragTarget.HOTKEYS)
        }
        if (potions) {
            element(ctx, DragTarget.POTIONS, potionsScale, unit) { c -> drawPotions(c, fade) }
        } else {
            bounds.remove(DragTarget.POTIONS)
        }
        if (notifications) {
            drawToasts(ctx, unit * notifyScale, fade)
        }
        if (armor) {
            drawArmor(ctx, unit * armorScale, fade)
        } else {
            bounds.remove(DragTarget.ARMOR)
        }
        if (counters) {
            drawCounters(ctx, unit * armorScale, fade)
        } else {
            bounds.remove(DragTarget.COUNTERS)
        }
        if (targetHud) {
            // NOT through `unit`: the ported layout is already in GUI units
            drawTargetHud(ctx, targetScale, fade)
        } else {
            bounds.remove(DragTarget.TARGET)
        }
    }

    // - Notifications -

    /** One toast. [on] drives the little toggle glyph the original shows on module switches. */
    private class Toast(val text: String, val on: Boolean, val bornAt: Long, var expireAt: Long)

    private val toasts = ArrayDeque<Toast>()

    private fun push(text: String, on: Boolean) {
        val now = System.currentTimeMillis()
        toasts.addLast(Toast(text, on, now, now + (notifyLifetime * 1000f).toLong()))
        // oldest live toast is retired early rather than dropped, so it still fades out
        while (toasts.count { it.expireAt > now } > notifyMaxStack) {
            toasts.firstOrNull { it.expireAt > now }?.expireAt = now
        }
    }

    @Suppress("unused")
    private val notificationHandler = handler<NotificationEvent> { event ->
        if (!notifications) return@handler
        // module toggles arrive through ModuleToggleEvent in a cleaner shape
        if (event.severity == NotificationEvent.Severity.ENABLED ||
            event.severity == NotificationEvent.Severity.DISABLED
        ) {
            return@handler
        }
        push(
            if (event.message.isBlank()) event.title else "${event.title}: ${event.message}",
            event.severity != NotificationEvent.Severity.ERROR,
        )
    }

    @Suppress("unused")
    private val toggleHandler = handler<ModuleToggleEvent> { event ->
        if (!notifications || !notifyToggles || event.hidden) return@handler
        push(spaced(event.moduleName), event.enabled)
    }

    /**
     * The toast stack: centred at the top, newest at the bottom, each row sliding up into place.
     * Drawn in GUI units rather than under an element pose: it is anchored to the screen, not
     * to a draggable element.
     */
    private fun drawToasts(ctx: GuiGraphicsExtractor, es: Float, fade: Float) {
        val now = System.currentTimeMillis()
        toasts.removeIf { now > it.expireAt + TOAST_OUT_MS.toLong() }

        // In edit mode the stack always shows a sample, so it can be positioned even when no
        // real toast is up — the original does the same ("Пример уведомления" on its capture).
        val editing = editMode()
        val shown: List<Toast> = if (toasts.isEmpty() && editing) {
            listOf(Toast("Example notification", true, now - TOAST_IN_MS.toLong(), now + 1000L))
        } else {
            toasts.toList()
        }
        if (shown.isEmpty()) {
            bounds.remove(DragTarget.NOTIFY)
            return
        }

        density = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        drawScale = es
        val textPx = TOAST_PX * es
        val screenW = mc.window.guiScaledWidth.toFloat()
        dragAnchor(DragTarget.NOTIFY, es)

        val centerX = if (notifyCenter < 0f) screenW / 2f else notifyCenter * es
        var y = notifyTop * es
        val top = y
        var widest = 0f

        for (toast in shown) {
            val inT = easeInOutCubic((now - toast.bornAt) / TOAST_IN_MS)
            val outT = if (now <= toast.expireAt) 0f else easeInOutCubic((now - toast.expireAt) / TOAST_OUT_MS)
            val life = (inT * (1f - outT)).coerceIn(0f, 1f)
            if (life <= 0.001f) continue
            val alpha = fade * life

            val textW = BODY.width(toast.text, textPx, density) / es
            val w = (TOAST_PAD_L + TOAST_TOGGLE_W + TOAST_GAP + textW + TOAST_PAD_R) * es
            val h = TOAST_H * es
            val x = centerX - w / 2f
            widest = maxOf(widest, w)
            // rise into place as it appears
            val ty = y + (1f - life) * 8f * es

            ctx.drawRoundedRect(
                x, ty, x + w, ty + h, TOAST_RADIUS * es,
                Palette.toastPlate.alphaF(alpha), Palette.border.alphaF(alpha), 1f,
            )

            val tw = TOAST_TOGGLE_W * es
            val th = TOAST_TOGGLE_H * es
            val tx = x + TOAST_PAD_L * es
            val tyy = ty + (h - th) / 2f
            val track = if (toast.on) accent else Palette.toastOff
            // white knob on a dark track, dark knob on a light one - a white accent would
            // otherwise put a white knob on a white track
            val luma = (track.r * 0.299f + track.g * 0.587f + track.b * 0.114f) / 255f
            val knob = if (luma > 0.6f) Color4b(24, 24, 28, 255) else Palette.value
            ctx.drawRoundedRect(tx, tyy, tx + tw, tyy + th, th / 2f, track.alphaF(alpha), null)
            // round knob, inset on both sides, sliding with the state
            val inset = 2.5f * es
            val knobD = th - inset * 2f
            val kx = if (toast.on) tx + tw - inset - knobD else tx + inset
            ctx.drawRoundedRect(
                kx, tyy + (th - knobD) / 2f, kx + knobD, tyy + (th + knobD) / 2f,
                knobD / 2f, knob.alphaF(alpha), null,
            )

            val lh = BODY.lineHeight(textPx, density)
            val argb = Palette.text.alphaF(alpha).argb
            BODY.submit(
                ctx, toast.text, tx + tw + TOAST_GAP * es, ty + (h - lh) / 2f, textPx, density,
            ) { argb }

            y += (h + TOAST_STACK_GAP * es) * life
        }

        if (widest <= 0f) {
            bounds.remove(DragTarget.NOTIFY)
            return
        }
        bounds.getOrPut(DragTarget.NOTIFY) { FloatArray(4) }.also {
            it[0] = centerX - widest / 2f; it[1] = top; it[2] = widest; it[3] = (y - top).coerceAtLeast(1f)
        }
        editFrame(ctx, DragTarget.NOTIFY)
    }

    private fun plate(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, r: Float, fade: Float) {
        ctx.drawRoundedRect(x, y, x + w, y + h, r, Palette.plate.alphaF(fade), Palette.border.alphaF(fade), 1f)
    }

    /** @return the drawn size in reference pixels. */
    private fun drawTopRow(ctx: GuiGraphicsExtractor, fade: Float): Pair<Float, Float> {
        var cx = 0f
        val cy = PILL_H / 2f

        if (watermark) {
            val title = LiquidBounce.CLIENT_NAME
            val titleW = width(WatermarkFont.BRAND, title, BRAND_PX)
            val w = BRAND_PAD_L + BRAND_LOGO + BRAND_GAP + titleW + BRAND_PAD_R
            plate(ctx, cx, 0f, w, PILL_H, PILL_RADIUS, fade)
            icon(ctx, "logo", cx + BRAND_PAD_L, cy, BRAND_LOGO, accent.alphaF(fade))
            text(
                ctx, WatermarkFont.BRAND, title, cx + BRAND_PAD_L + BRAND_LOGO + BRAND_GAP, cy,
                BRAND_PX, Palette.value.alphaF(fade),
            )
            cx += w
        }

        if (info) {
            if (watermark) cx += PILL_GAP
            val fps = mc.fps
            if (fps != fpsValue) {
                fpsValue = fps
                fpsText = "${fps}fps"
            }
            val ping = mc.player?.let { mc.connection?.getPlayerInfo(it.uuid)?.latency } ?: 0
            if (ping != pingValue) {
                pingValue = ping
                pingText = "${ping}ms"
            }
            val time = LocalTime.now()
            if (time.second != clockSecond) {
                clockSecond = time.second
                clockText = "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
            }
            cx += drawSegmentPill(
                ctx, cx, 0f,
                listOf(
                    "player" to (mc.user?.name ?: "player"),
                    "fps" to fpsText,
                    "ping" to pingText,
                    "time" to clockText,
                ),
                fade,
            )
        }

        return cx to PILL_H
    }

    /** @return the drawn size in reference pixels. */
    private fun drawCoordsPill(ctx: GuiGraphicsExtractor, fade: Float): Pair<Float, Float> {
        val player = mc.player ?: return 0f to 0f
        val px = player.x.roundToInt()
        val py = player.y.roundToInt()
        val pz = player.z.roundToInt()
        val key = (px.toLong() shl 42) xor (py.toLong() shl 21) xor pz.toLong()
        if (key != posKey) {
            posKey = key
            posText = "x$px y$py z$pz"
        }
        val motion = player.deltaMovement
        val bps = (sqrt(motion.x * motion.x + motion.z * motion.z) * 20.0 * 10).roundToInt()
        if (bps != bpsValue) {
            bpsValue = bps
            bpsText = "%.1fbps".format(bps / 10f)
        }
        tpsText = "%.1ftps".format(tpsValue)

        val w = drawSegmentPill(
            ctx, 0f, 0f,
            listOf("coordinates" to posText, "bps" to bpsText, "tps" to tpsText),
            fade,
        )
        return w to PILL_H
    }

    /** A pill of `icon + text` segments — the shape both top pills share. @return its width. */
    private fun drawSegmentPill(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, segments: List<Pair<String, String>>, fade: Float,
    ): Float {
        var w = INFO_PAD_L
        for ((index, segment) in segments.withIndex()) {
            if (index > 0) w += SEGMENT_GAP
            w += ICON + ICON_GAP + width(BODY, segment.second, TEXT_PX)
        }
        w += INFO_PAD_R

        plate(ctx, x, y, w, PILL_H, PILL_RADIUS, fade)
        val cy = y + PILL_H / 2f
        var tx = x + INFO_PAD_L
        for ((index, segment) in segments.withIndex()) {
            if (index > 0) tx += SEGMENT_GAP
            icon(ctx, "hud/${segment.first}", tx, cy, ICON, Palette.icon.alphaF(fade))
            tx += ICON + ICON_GAP
            text(ctx, BODY, segment.second, tx, cy, TEXT_PX, Palette.text.alphaF(fade))
            tx += width(BODY, segment.second, TEXT_PX)
        }
        return w
    }

    /**
     * A panel: icon gutter, hairline divider, then rows of `label ... value`.
     * @return the drawn size in reference pixels, or zero when there was nothing to draw.
     */
    private fun drawPanel(
        ctx: GuiGraphicsExtractor, gutterIcon: String,
        rows: List<PanelRow>, fade: Float,
    ): Pair<Float, Float> {
        if (rows.isEmpty()) return 0f to 0f

        var content = 0f
        for (row in rows) {
            val labelW = width(BODY, row.label, TEXT_PX)
            val valueW = if (row.value.isEmpty()) 0f else VALUE_GAP + width(BODY, row.value, TEXT_PX)
            content = maxOf(content, labelW + valueW)
        }
        val w = GUTTER + 1f + PANEL_PAD + content + PANEL_PAD
        // a leaving row gives its slot up gradually, so the panel closes the gap smoothly
        var liveRows = 0f
        for (row in rows) liveRows += row.life
        val h = PANEL_PAD_V * 2f + ROW_H * liveRows
        if (h <= PANEL_PAD_V * 2f) return 0f to 0f

        plate(ctx, 0f, 0f, w, h, PANEL_RADIUS, fade)
        ctx.drawRoundedRect(GUTTER, 6f, GUTTER + 1f, h - 6f, 0f, Palette.divider.alphaF(fade), null)
        icon(ctx, "hud/$gutterIcon", (GUTTER - GUTTER_ICON) / 2f, h / 2f, GUTTER_ICON, Palette.icon.alphaF(fade))

        var ry = PANEL_PAD_V
        for (row in rows) {
            if (row.life <= 0.001f) continue
            val alpha = fade * row.life
            val cy = ry + ROW_H / 2f
            val labelColor = if (row.dim) Palette.icon.alphaF(alpha * 0.65f) else Palette.text.alphaF(alpha)
            text(ctx, BODY, row.label, GUTTER + 1f + PANEL_PAD, cy, TEXT_PX, labelColor)
            if (row.value.isNotEmpty()) {
                val vw = width(BODY, row.value, TEXT_PX)
                val valueColor = if (row.dim) Palette.icon.alphaF(alpha * 0.65f) else Palette.value.alphaF(alpha)
                text(ctx, BODY, row.value, w - PANEL_PAD - vw, cy, TEXT_PX, valueColor)
            }
            ry += ROW_H * row.life
        }
        return w to h
    }

    private fun drawHotkeys(ctx: GuiGraphicsExtractor, fade: Float, now: Long): Pair<Float, Float> {
        alive.clear()
        for (module in ModuleManager) {
            // a hotkeys panel is about KEYS: an unbound module has nothing to say here
            if (module.hidden || module.bind.isUnbound) continue
            if (hotkeysOnlyEnabled && !module.enabled) continue
            alive += module.name
            val row = rows.getOrPut(module.name) { Row(module).also { it.appearAt = now } }
            if (row.removeAt != 0L) {
                row.removeAt = 0L
                row.appearAt = now
            }
        }
        for (row in rows.values) {
            if (row.module.name !in alive && row.removeAt == 0L) row.removeAt = now
        }
        rows.values.removeIf { it.removeAt != 0L && now > it.removeAt + OUT_MS.toLong() }

        val list = ArrayList<PanelRow>(rows.size)
        for (row in rows.values) {
            val inT = easeInOutCubic((now - row.appearAt) / IN_MS)
            val outT = if (row.removeAt == 0L) 0f else easeInOutCubic((now - row.removeAt) / OUT_MS)
            val life = (inT * (1f - outT)).coerceIn(0f, 1f)
            if (life <= 0.001f) continue
            row.label = spaced(row.module.name)
            row.value = if (binds) row.module.bind.keyName else ""
            row.dimmed = !row.module.enabled
            list += PanelRow(row.label, row.value, life, row.dimmed)
        }
        list.sortByDescending { width(BODY, it.label, TEXT_PX) }
        if (list.isEmpty() && editMode()) list += PanelRow("Attack Aura", "X", 1f)
        return drawPanel(ctx, "hotkeys", list, fade)
    }

    private fun drawPotions(ctx: GuiGraphicsExtractor, fade: Float): Pair<Float, Float> {
        val player = mc.player ?: return 0f to 0f
        val effects = player.activeEffects
        if (effects.isEmpty()) {
            if (!editMode()) return 0f to 0f
            return drawPanel(ctx, "potions", listOf(PanelRow("Resistance", "02:30", 1f)), fade)
        }
        val list = effects.map { effect ->
            val name = Component.translatable(effect.effect.value().descriptionId).string
            val seconds = effect.duration / 20
            PanelRow(name, "%02d:%02d".format(seconds / 60, seconds % 60), 1f)
        }
        return drawPanel(ctx, "potions", list, fade)
    }


    /**
     * An item cell: the icon on a small plate, drawn at a WHOLE device-pixel factor so the pixel
     * art stays crisp. The factor, the cell size and the gaps are the Glass theme's, so the two
     * rows read as the same widget.
     *
     * @return the cell size in GUI units.
     */
    private fun cellSize(itemScale: Float, es: Float): Float {
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val factor = Math.round(itemScale * guiScale).coerceAtLeast(1)
        return 16f * factor / guiScale + CELL_PAD * es
    }

    private fun drawItemCell(
        ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, cell: Float, itemScale: Float, alpha: Float,
    ) {
        ctx.drawRoundedRect(
            x, y, x + cell, y + cell, CELL_RADIUS * drawScale,
            Palette.plate.alphaF(alpha), Palette.border.alphaF(alpha), 1f,
        )
        if (stack.isEmpty) return

        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val factor = Math.round(itemScale * guiScale).coerceAtLeast(1)
        val iconGuiScale = factor / guiScale
        val itemSize = 16f * iconGuiScale
        val inset = (cell - itemSize) * 0.5f
        // origin on the device grid, or the sprite samples between texels and blurs
        val gx = Math.round((x + inset) * guiScale) / guiScale
        val gy = Math.round((y + inset) * guiScale) / guiScale

        ctx.pose().pushMatrix()
        ctx.pose().translate(gx, gy)
        ctx.pose().scale(iconGuiScale, iconGuiScale)
        try {
            ctx.item(stack, 0, 0)
        } finally {
            ctx.pose().popMatrix()
        }
    }

    /** Glass's durability bar: a dark track with a red-to-green fill. */
    private fun drawDurabilityBar(
        ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, w: Float, es: Float, alpha: Float,
    ) {
        if (!stack.isDamageableItem) return
        val left = 1f - stack.damageValue.toFloat() / stack.maxDamage.coerceAtLeast(1)
        val h = BAR_H * es
        ctx.drawRoundedRect(x, y, x + w, y + h, 0f, Color4b(0, 0, 0, 89).alphaF(alpha), null)
        val fillW = w * left.coerceIn(0f, 1f)
        if (fillW <= 0.3f) return
        val color = lerpColor(BAR_EMPTY, BAR_FULL, left)
        ctx.drawRoundedRect(x, y, x + fillW, y + h, 0f, color.alphaF(alpha), null)
    }

    private fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        return Color4b(ch(a.r, b.r), ch(a.g, b.g), ch(a.b, b.b), ch(a.a, b.a))
    }

    /**
     * The equipped armour: four cells with durability bars, geometry taken from the Glass theme's
     * row (cell = a whole-factor 16 px icon plus 1.5, gap 3, bar 1.6 under a 1.5 gap).
     *
     * Drawn in GUI units — item sprites must not sit under a pose scale, or the device-grid
     * snapping they rely on stops working and they blur.
     */
    private fun drawArmor(ctx: GuiGraphicsExtractor, es: Float, fade: Float) {
        val player = mc.player ?: return
        val pieces = ARMOR_SLOTS.map(player::getItemBySlot)
        if (pieces.all { it.isEmpty } && !editMode()) {
            bounds.remove(DragTarget.ARMOR)
            return
        }

        // GUI units for every metric, reference pixels for the position only — see drawCounters
        val ms = armorScale
        drawScale = ms
        density = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        dragAnchor(DragTarget.ARMOR, es)

        val cell = cellSize(armorItemScale, ms)
        val gap = CELL_GAP * ms
        val w = pieces.size * cell + (pieces.size - 1) * gap
        val h = cell + if (armorDurability) BAR_GAP * ms + BAR_H * ms else 0f

        val screenW = mc.window.guiScaledWidth.toFloat()
        val x = (if (armorX < 0f) screenW / 2f else armorX * es) - w / 2f
        val y = armorY * es

        for ((index, stack) in pieces.withIndex()) {
            val cx = x + index * (cell + gap)
            drawItemCell(ctx, stack, cx, y, cell, armorItemScale, fade)
            if (armorDurability) {
                drawDurabilityBar(ctx, stack, cx, y + cell + BAR_GAP * ms, cell, ms, fade)
            }
        }

        bounds.getOrPut(DragTarget.ARMOR) { FloatArray(4) }.also {
            it[0] = x; it[1] = y; it[2] = w; it[3] = h
        }
        editFrame(ctx, DragTarget.ARMOR)
    }

    /** One tracked item and the setting that shows its cell. */
    private class Counter(val item: Item, val enabled: () -> Boolean)

    private val showTotems by boolean("CountTotems", true)
    private val showCrystals by boolean("CountCrystals", true)
    private val showPearls by boolean("CountPearls", true)
    private val showGaps by boolean("CountGapples", true)
    private val showAnchors by boolean("CountAnchors", true)
    private val showExp by boolean("CountExperience", false)
    private val showArrows by boolean("CountArrows", false)

    private val tracked by lazy {
        listOf(
            Counter(Items.TOTEM_OF_UNDYING) { showTotems },
            Counter(Items.END_CRYSTAL) { showCrystals },
            Counter(Items.ENDER_PEARL) { showPearls },
            Counter(Items.ENCHANTED_GOLDEN_APPLE) { showGaps },
            Counter(Items.RESPAWN_ANCHOR) { showAnchors },
            Counter(Items.EXPERIENCE_BOTTLE) { showExp },
            Counter(Items.ARROW) { showArrows },
        )
    }

    /**
     * How much of each tracked item is left, one cell per item with the count on its own small
     * plate below it — the Glass theme's counter row, same cells and same plate metrics.
     */
    private fun drawCounters(ctx: GuiGraphicsExtractor, es: Float, fade: Float) {
        val player = mc.player ?: return
        val inventory = player.inventory
        val counts = IntArray(tracked.size)
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            val index = tracked.indexOfFirst { it.item === stack.item }
            if (index >= 0) counts[index] += stack.count
        }

        // While the chat is open every enabled cell shows, so the row stays draggable when empty
        val editing = editMode()
        val visible = tracked.indices.filter { tracked[it].enabled() && (editing || counts[it] > 0) }
        if (visible.isEmpty()) {
            bounds.remove(DragTarget.COUNTERS)
            return
        }

        // The cells are whole multiples of a 16 px sprite, so every metric around them lives in
        // GUI units too — the reference-pixel divisor applies to the POSITION only.
        val ms = armorScale
        drawScale = ms
        density = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        dragAnchor(DragTarget.COUNTERS, es)

        val cell = cellSize(counterItemScale, ms)
        val gap = CELL_GAP * ms
        val countPx = COUNT_PX * ms
        val plateH = countPx * DIGIT_CAP_FACTOR + PLATE_PAD_Y * 2f * ms
        val w = visible.size * cell + (visible.size - 1) * gap
        val h = cell + COUNT_GAP * ms + plateH

        val screenW = mc.window.guiScaledWidth.toFloat()
        val x = (if (countersX < 0f) screenW / 2f else countersX * es) - w / 2f
        val y = countersY * es

        val ascent = WatermarkFont.TEXT.ascent(countPx, density)
        for ((slot, index) in visible.withIndex()) {
            val cx = x + slot * (cell + gap)
            drawItemCell(ctx, ItemStack(tracked[index].item), cx, y, cell, counterItemScale, fade)

            val label = counts[index].toString()
            val lw = WatermarkFont.TEXT.width(label, countPx, density)
            // a single digit would leave a sliver of a plate — never narrower than it is tall
            val plateW = maxOf(lw + PLATE_PAD_X * 2f * ms, plateH)
            val px = cx + (cell - plateW) / 2f
            val py = y + cell + COUNT_GAP * ms
            ctx.drawRoundedRect(
                px, py, px + plateW, py + plateH, PLATE_RADIUS * ms,
                Palette.plate.alphaF(fade), Palette.border.alphaF(fade), 1f,
            )
            // the plate hugs the digits' CAP band, so the baseline sits a padding above its
            // bottom edge rather than in the middle of a line box digits never fill
            val argb = Palette.value.alphaF(fade).argb
            WatermarkFont.TEXT.submit(
                ctx, label, px + (plateW - lw) / 2f,
                py + plateH - PLATE_PAD_Y * ms - ascent, countPx, density,
            ) { argb }
        }

        bounds.getOrPut(DragTarget.COUNTERS) { FloatArray(4) }.also {
            it[0] = x; it[1] = y; it[2] = w; it[3] = h
        }
        editFrame(ctx, DragTarget.COUNTERS)
    }

    private var targetShownAt = 0L
    private var lastTargetSeen = 0L
    private var lastTarget: net.minecraft.world.entity.LivingEntity? = null
    private var shownPct = 1f
    private var ghostPct = 1f
    private var lastTargetFrameNs = 0L
    private var targetKey = ""

    /** Scissor in GUI units — the ring phases below clip in absolute coordinates. */
    private inline fun clipGui(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, body: () -> Unit) {
        // The scissor stack works in GUI units, NOT device pixels. Multiplying by the GUI scale
        // here pushed every window three times too far and clipped the health arc away entirely.
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val rect = ScreenRectangle(
            x0, y0,
            (ceil(x + w).toInt() - x0).coerceAtLeast(1),
            (ceil(y + h).toInt() - y0).coerceAtLeast(1),
        )
        ctx.scissorStack.push(rect)
        try {
            body()
        } finally {
            ctx.scissorStack.pop()
        }
    }

    /**
     * A rounded-rect outline drawn only for the first [fraction] of its perimeter, clockwise from
     * the top centre — the health frame around the avatar.
     *
     * Ported from the Hellhit HUD: the arc is produced by clipping the full outline to a sequence
     * of growing windows rather than by tessellating it, and the windows are laid out so two
     * consecutive phases never overlap (a translucent stroke would double up into a visible seam).
     */
    @Suppress("LongParameterList")
    private fun drawRing(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float,
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
        val m = maxOf(r, t) + 0.5f
        val hw = w / 2f
        val hh = h / 2f
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
                0 -> outline(cx, y, g, m)
                1 -> outline(x1 - m, y + m, m, g)
                2 -> outline(x1 - m, cy, m, g)
                3 -> outline(cx + (hw - m) - g, y1 - m, g, m)
                4 -> outline(cx - g, y1 - m, g, m)
                5 -> outline(x, y1 - m - g, m, g)
                6 -> outline(x, cy - g, m, g)
                else -> outline(x + m, y, g, m)
            }
            remain -= len
            if (remain <= 0f) break
        }
    }

    /**
     * The target's face + hat layer, rounded through the client's rounded-texture pipeline.
     * Falls back to a plain plate for anything that is not a player.
     */
    private fun drawHead(ctx: GuiGraphicsExtractor, skin: Identifier?, x: Float, y: Float, size: Float, alpha: Float) {
        val r = 3.5f.coerceAtMost(size * 0.5f)
        if (skin == null) {
            ctx.drawRoundedRect(
                x, y, x + size, y + size, r,
                Palette.cell.alphaF(alpha), Palette.border.alphaF(alpha), 1f,
            )
            return
        }
        val setup = mc.textureManager.getTexture(skin).textureSetup
        val sz = Math.round(size)
        val rr = Math.round(r)
        val tint = Color4b(255, 255, 255, (255 * alpha).toInt().coerceIn(0, 255)).argb
        ctx.drawCustomElement(
            pipeline = ClientRenderPipelines.GUI.roundedTex(),
            textureSetup = setup,
            bounds = ctx.getBoundsXYWH(x, y, size, size),
        ) { pose ->
            addVertexWith2DPose(pose, x, y).setUv(0f, 0f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
            addVertexWith2DPose(pose, x, y + size).setUv(0f, 1f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
            addVertexWith2DPose(pose, x + size, y + size).setUv(1f, 1f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
            addVertexWith2DPose(pose, x + size, y).setUv(1f, 0f).setColor(tint).setUv1(sz, sz).setUv2(rr, 0)
                .setLineWidth(0f)
        }
    }

    private fun drawItemIcon(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, size: Float) {
        if (stack.isEmpty) return
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        val gx = Math.round(x * guiScale) / guiScale
        val gy = Math.round(y * guiScale) / guiScale
        ctx.pose().pushMatrix()
        ctx.pose().translate(gx, gy)
        ctx.pose().scale(size / 16f, size / 16f)
        try {
            ctx.item(stack, 0, 0)
        } finally {
            ctx.pose().popMatrix()
        }
    }

    /**
     * The target panel, ported from the Hellhit HUD and trimmed down ([TARGET_BASE] 1.15 against
     * its 1.4): an avatar tile whose thick frame IS the health bar, the name in a centre column
     * over the held items, and an engraved 2x2 armour grid with durability rings.
     *
     * The health reading is [nametagHealth] — the one definition the nametags, ModuleHud2 and the
     * stock TargetHUD share, so this panel can never disagree with the number over the entity.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun drawTargetHud(ctx: GuiGraphicsExtractor, es: Float, fade: Float) {
        val now = System.currentTimeMillis()
        val live = ModuleHud2.resolveCombatTarget()
        if (live != null) {
            lastTarget = live
            lastTargetSeen = now
        }
        // With the chat open the panel shows the player instead of vanishing: an element that
        // is not on screen cannot be dragged, and there is rarely a target while editing.
        val entity = live
            ?: lastTarget?.takeIf { now - lastTargetSeen <= TARGET_HOLD_MS && it.isAlive }
            ?: if (editMode()) mc.player else null
        if (entity == null) {
            targetShownAt = 0L
            targetKey = ""
            lastTarget = null
            bounds.remove(DragTarget.TARGET)
            return
        }
        if (targetShownAt == 0L) targetShownAt = now

        val health = entity.nametagHealth()
        val maxHealth = entity.maxHealth.coerceAtLeast(health).coerceAtLeast(1f)
        val pct = (health / maxHealth).coerceIn(0f, 1f)
        if (entity.name.string != targetKey) {
            targetKey = entity.name.string
            shownPct = pct
            ghostPct = pct
            targetShownAt = now
        }

        // interpolated health plus a lagging damage ghost that snaps up on a heal
        val nowNs = System.nanoTime()
        val dt = if (lastTargetFrameNs == 0L) 0f else ((nowNs - lastTargetFrameNs) / 1_000_000_000.0).toFloat()
        lastTargetFrameNs = nowNs
        shownPct += (pct - shownPct) * (dt * 10f).coerceIn(0f, 1f)
        ghostPct = if (shownPct > ghostPct) shownPct else ghostPct + (shownPct - ghostPct) * (dt * 2.5f).coerceIn(0f, 1f)

        val leaving = when {
            live != null || editMode() -> 1f
            else -> 1f - ((now - lastTargetSeen) / TARGET_HOLD_MS.toFloat())
        }
        val alpha = (fade * easeInOutCubic((now - targetShownAt) / IN_MS) * leaving).coerceIn(0f, 1f)

        val scale = es * TARGET_BASE
        density = scale * mc.window.guiScale.toFloat().coerceAtLeast(1f)
        drawScale = scale
        // anchors are GUI units here, so the drag writes them 1:1
        dragAnchor(DragTarget.TARGET, 1f)

        val screenW = mc.window.guiScaledWidth.toFloat()
        val screenH = mc.window.guiScaledHeight.toFloat()
        val w = TARGET_W * scale
        val h = TARGET_H * scale
        // A stored anchor that no longer lands on screen (a resolution or GUI-scale change, or
        // a value written under different units) falls back to the default instead of drawing
        // the panel somewhere nobody can see or grab it.
        val x = if (targetX < 0f || targetX > screenW - 8f) (screenW - w) / 2f else targetX
        val y = if (targetY < 0f || targetY > screenH - 8f) screenH * 0.62f else targetY

        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)
        try {
            ctx.drawRoundedRect(0f, 0f, TARGET_W, TARGET_H, 6f, Palette.plate.alphaF(alpha), null)

            // avatar tile: a dim full track, the live arcs are drawn after the pose pops
            ctx.drawRoundedRect(
                AVATAR_X, AVATAR_Y, AVATAR_X + AVATAR, AVATAR_Y + AVATAR, 5.5f,
                accent.alphaF(0.18f * alpha), null,
            )
            ctx.drawRoundedRect(
                AVATAR_X + FRAME_T, AVATAR_Y + FRAME_T,
                AVATAR_X + AVATAR - FRAME_T, AVATAR_Y + AVATAR - FRAME_T, 3.5f,
                Color4b(16, 15, 14, 255).alphaF(alpha), null,
            )
            val skin = (entity as? AbstractClientPlayer)?.skin?.body?.texturePath()
            drawHead(
                ctx, skin, AVATAR_X + FRAME_T + 0.6f, AVATAR_Y + FRAME_T + 0.6f,
                AVATAR - 2f * (FRAME_T + 0.6f), alpha,
            )

            val colW = COL_X1 - COL_X0
            val colCx = (COL_X0 + COL_X1) / 2f
            val namePx = 5.4f
            val nameH = WatermarkFont.BRAND.lineHeight(namePx, density)
            var name = entity.name.string
            while (name.isNotEmpty() && WatermarkFont.BRAND.width(name, namePx, density) > colW) {
                name = name.dropLast(1)
            }
            val nameW = WatermarkFont.BRAND.width(name, namePx, density)
            val nameArgb = Palette.value.alphaF(alpha).argb
            var cy = 5.5f
            WatermarkFont.BRAND.submit(ctx, name, colCx - nameW / 2f, cy, namePx, density) { nameArgb }
            cy += nameH + 1.8f
            ctx.drawRoundedRect(COL_X0, cy, COL_X1, cy + 0.7f, 0f, Color4b(255, 255, 255, 22).alphaF(alpha), null)

            // held items, centre-anchored on the column; a dim dot marks an empty hand
            val handIcon = 9f
            val rowY = TARGET_H - handIcon - 2f
            var ix = colCx - (handIcon + 4f + handIcon) / 2f
            for (stack in listOf(entity.getItemBySlot(EquipmentSlot.MAINHAND), entity.getItemBySlot(EquipmentSlot.OFFHAND))) {
                if (stack.isEmpty) {
                    val dcx = ix + handIcon / 2f
                    val dcy = rowY + handIcon / 2f
                    ctx.drawRoundedRect(
                        dcx - 1.1f, dcy - 1.1f, dcx + 1.1f, dcy + 1.1f, 1.1f,
                        Palette.icon.alphaF(0.8f * alpha), null,
                    )
                } else {
                    drawItemIcon(ctx, stack, ix, rowY, handIcon)
                }
                ix += handIcon + 4f
            }

            // engraved 2x2 armour grid: cells darker than the slab, separators lighter
            targetRings.clear()
            for ((index, piece) in ARMOR_SLOTS.map(entity::getItemBySlot).withIndex()) {
                val col = index % 2
                val row = index / 2
                val cellX = GRID_X + col * (CELL_W + GRID_GAP)
                val cellY = GRID_Y + row * (CELL_H + GRID_GAP)
                ctx.drawRoundedRect(
                    cellX, cellY, cellX + CELL_W, cellY + CELL_H, 3.5f,
                    Color4b(0, 0, 0, 70).alphaF(alpha), null,
                )
                if (piece.isEmpty) continue
                drawItemIcon(ctx, piece, cellX, cellY + (CELL_H - CELL_W) / 2f, CELL_W)
                if (piece.maxDamage > 0) {
                    val left = (piece.maxDamage - piece.damageValue).toFloat() / piece.maxDamage
                    targetRings += floatArrayOf(cellX, cellY, left)
                }
            }
            val sep = Color4b(255, 255, 255, 18).alphaF(alpha)
            val gridW = 2f * CELL_W + GRID_GAP
            val gridH = 2f * CELL_H + GRID_GAP
            ctx.drawRoundedRect(
                GRID_X + CELL_W + 0.35f, GRID_Y, GRID_X + CELL_W + GRID_GAP - 0.35f, GRID_Y + gridH, 0f, sep, null,
            )
            ctx.drawRoundedRect(
                GRID_X, GRID_Y + CELL_H + 0.35f, GRID_X + gridW, GRID_Y + CELL_H + GRID_GAP - 0.35f, 0f, sep, null,
            )
        } finally {
            ctx.pose().popMatrix()
        }

        // rings live in absolute GUI coordinates: their scissors ignore the pose
        val ringX = x + AVATAR_X * scale
        val ringY = y + AVATAR_Y * scale
        val ringSize = AVATAR * scale
        if (ghostPct > 0.002f) {
            drawRing(
                ctx, ringX, ringY, ringSize, ringSize, 5.5f * scale, FRAME_T * scale, ghostPct,
                BAR_EMPTY.alphaF(0.55f * alpha),
            )
        }
        drawRing(ctx, ringX, ringY, ringSize, ringSize, 5.5f * scale, FRAME_T * scale, shownPct, accent.alphaF(alpha))

        for (ring in targetRings) {
            val cellX = x + ring[0] * scale
            val cellY = y + ring[1] * scale
            drawRing(
                ctx, cellX, cellY, CELL_W * scale, CELL_H * scale, 3.5f * scale, 1.2f * scale, ring[2],
                lerpColor(BAR_EMPTY, BAR_FULL, ring[2]).alphaF(alpha),
            )
        }

        bounds.getOrPut(DragTarget.TARGET) { FloatArray(4) }.also {
            it[0] = x; it[1] = y; it[2] = w; it[3] = h
        }
        editFrame(ctx, DragTarget.TARGET)
    }

    /** Armour cells whose durability rings are drawn after the pose pops (absolute coords). */
    private val targetRings = ArrayList<FloatArray>(4)

    /** "AttackAura" -> "Attack Aura", matching the original's spaced module names. */
    private fun spaced(name: String): String {
        val out = StringBuilder(name.length + 4)
        for ((index, ch) in name.withIndex()) {
            if (index > 0 && ch.isUpperCase() && !name[index - 1].isUpperCase()) out.append(' ')
            out.append(ch)
        }
        return out.toString()
    }
}
