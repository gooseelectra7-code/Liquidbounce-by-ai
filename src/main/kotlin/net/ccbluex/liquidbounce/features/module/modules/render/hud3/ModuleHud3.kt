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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAimbot
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGlass
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.LivingEntity
import org.lwjgl.glfw.GLFW

/**
 * Hud3 — a native HUD that reproduces the "Shard" design.
 *
 * The design was not measured off a screenshot: it was **read out of the Figma file** over the REST
 * API (file `peW5mqUNF4v3xOKGZXRtt3`, page `1:4`, frame `1:117`), so every size, colour, radius,
 * gap and font size in this package is the value the file reports, and the node id it came from is
 * cited next to it. The icon set is the design's own vectors, exported at 4x and flattened to white
 * alpha masks that are tinted at draw time (see [Hud3Icons]).
 *
 * Seven panels, all of them the design's own components:
 * - [Hud3Watermark]  `<3:489>`  — brand, player, fps, server, ping
 * - [Hud3Keybinds]   `<3:486>`  — bound modules and their keys
 * - [Hud3Potions]    `<3:488>`  — active effects with a countdown ring
 * - [Hud3Target]     `<3:802>`  — target avatar, gear, hands and a health ring
 * - [Hud3Armor]      `<3:485>`  — worn armour with durability bars
 * - [Hud3ItemList]   `<3:487>`  — carried amounts of chosen items
 * - [Hud3Notifications] `<3:179>` — the notification stack
 *
 * Everything is drawn in **native-pixel space**: the gui scale is taken back out of the pose, so
 * one local unit is one physical screen pixel and the file's numbers are used verbatim. Geometry is
 * contour-tessellated with a one-device-pixel antialiasing feather, text goes through the bundled
 * SF Pro glyph-atlas renderer, and every pill samples a region-limited background blur — all on the
 * vanilla GUI pipeline, without registering a pipeline of its own.
 *
 * ## Deliberate departures from the file, and there are only three
 *
 * 1. **The rings.** The design fakes both of them with a rounded-corner frame and, for the potion
 *    timer, a *linear* gradient on the stroke — which cannot express a countdown and renders
 *    lopsided. They are real angular arcs here; see [Hud3Shapes.arc].
 * 2. **SF Pro Regular.** The file uses SF Pro Medium and Regular; only `SFProDisplay-Medium.otf`
 *    is bundled, so the Regular runs are drawn in Medium. See [Hud3Theme.font].
 * 3. **Text the file cannot supply.** Notification severities are five in the client and three in
 *    the file, and a notification is one sentence there but a title plus a message here. Both
 *    mappings are documented in [Hud3Notifications].
 *
 * Panels are placed by anchor plus the design's own offset in native pixels, and can be dragged
 * while the chat or a container screen is open.
 *
 * ## Motion
 *
 * The design is a still frame, so everything that moves is ours ([Hud3Motion], `Animations` group,
 * off = every transition lands at once). Panels fade in AND out — a panel keeps drawing its last
 * content while it leaves, including one that was just switched off. Stacks ([Hud3Presence]) slide
 * rows in and out and close the gap smoothly; pills resize to new text; the target ring eases and
 * leaves a damage trail; armour and item counts pop and flash; cards follow their placement and lift
 * while dragged; the edit mode outlines every panel. Item sprites in motion go through
 * `HudTestItemIcons.drawTransient`: the crisp path bakes one texture per device size, and a size
 * that changes every frame would bake one every frame.
 *
 * OVERLAP: this is a third native HUD next to `ModuleHud2` and `ModuleHudTest`, and a fourth
 * display of the same information next to the web `ModuleHud`. Enable one.
 */
object ModuleHud3 : Mode("Hud3", aliases = listOf("Shard")) {

    override val parent: ModeValueGroup<*>
        get() = ModuleHud.theme

    /**
     * The face the whole HUD is set in — read back through [Hud3Theme.font].
     *
     * Kept on the module rather than per panel: a HUD set in two typefaces is not a design, and one
     * setting also means one glyph atlas per size instead of seven.
     */
    internal val typeface by enumChoice("Typeface", Hud3Typeface.SF_PRO)

    private val blur by boolean("Blur", true)
    private val blurStrength by int("BlurStrength", 7, 1..10)
    private val blurPasses by int("BlurPasses", 2, 1..6)

    /** Every transition of every panel; see [Hud3Motion]. */
    internal val animations = tree(Hud3Animations(this))

    internal val watermark = Hud3Watermark(this)
    internal val keybinds = Hud3Keybinds(this)
    internal val potions = Hud3Potions(this)
    internal val armor = Hud3Armor(this)
    internal val itemList = Hud3ItemList(this)
    internal val targetHud = Hud3Target(this)
    internal val notifications = Hud3Notifications(this)

    /** Draw order, bottom to top. */
    private val panels: List<Hud3Panel> = listOf(
        watermark, keybinds, potions, armor, itemList, targetHud, notifications,
    )

    init {
        panels.forEach { tree(it) }
    }

    // --- Frame state ------------------------------------------------------------------------------

    private var frameId = 0L
    private var glassFrame = -1L
    private var glassSetup: TextureSetup? = null
    private var lastFrameNs = 0L

    private var lastAttacked: LivingEntity? = null
    private var lastAttackAt = 0L

    override fun disable() {
        Hud3Input.endDrag()
        notifications.clear()
        super.disable()
    }

    /**
     * The HUD is draggable exactly while the chat or a container screen is open. Any other screen
     * (the ClickGUI above all) is left alone.
     */
    fun editMode(): Boolean = mc.screen is ChatScreen || mc.screen is AbstractContainerScreen<*>

    /**
     * Whether the vanilla corner effect icons should stay off screen: the Potions panel is the
     * same list, so leaving both on draws every effect twice.
     *
     * Hooked from `MixinGui#extractEffects`, which already ORs the same predicate across Hud2,
     * HellhitHud and HudTest.
     */
    @JvmStatic
    fun shouldHideVanillaEffects(): Boolean =
        running && !HideAppearance.isHidingNow && potions.enabled

    /**
     * The region-limited background blur every pill samples — the design's
     * `BACKGROUND_BLUR(radius = 12)`. All panels share one capture per frame, because vanilla
     * allows the blur marker only once per frame.
     */
    fun glass(ctx: GuiGraphicsExtractor): TextureSetup? {
        if (!blur) {
            return null
        }
        if (glassFrame != frameId) {
            glassFrame = frameId
            ctx.guiRenderState.blurBeforeThisStratum()
            glassSetup = WatermarkGlass.request(blurStrength, blurPasses)
        }
        return glassSetup
    }

    /**
     * The entity the target hud shows: whatever the combat modules are currently aiming at, and
     * otherwise the last entity the player hit, for as long as it stays alive and close.
     */
    fun combatTarget(): LivingEntity? {
        val self = mc.player ?: return null
        val moduleTarget = when {
            ModuleKillAura.running -> ModuleKillAura.targetTracker.target
            ModuleAimbot.running -> ModuleAimbot.targetTracker.target
            else -> null
        }
        val tracked = lastAttacked?.takeIf {
            System.currentTimeMillis() - lastAttackAt <= TARGET_TIMEOUT_MS &&
                it.isAlive && !it.isRemoved && self.distanceTo(it) <= TARGET_MAX_DISTANCE
        }
        return moduleTarget ?: tracked
    }

    /** Seconds since the previous frame, zero on the first one and capped after a stall. */
    private fun frameDelta(): Float {
        val now = System.nanoTime()
        val delta = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1_000_000_000.0).toFloat()
        lastFrameNs = now
        return delta.coerceIn(0f, MAX_FRAME_DELTA)
    }

    // --- Event handlers ---------------------------------------------------------------------------

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (HideAppearance.isHidingNow) {
            return@handler
        }
        frameId++
        val delta = frameDelta()
        for (panel in panels) {
            // A switched-off panel still renders until its fade-out has run
            if (panel.isOnScreen) {
                panel.frameDt = delta
                panel.render(event, delta)
            }
        }

        val editing = editMode()
        val mouseX = Hud3Input.mouseX()
        val mouseY = Hud3Input.mouseY()
        for (panel in panels) {
            panel.renderEditOutline(event.context, editing, mouseX, mouseY, delta)
        }
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return@handler
        }
        if (event.action == GLFW.GLFW_RELEASE) {
            Hud3Input.endDrag()
            return@handler
        }
        if (event.action != GLFW.GLFW_PRESS || !editMode()) {
            return@handler
        }

        val mouseX = Hud3Input.mouseX()
        val mouseY = Hud3Input.mouseY()
        val grabbed = panels.lastOrNull { it.enabled && it.containsGui(mouseX, mouseY) } ?: return@handler
        Hud3Input.startDrag(grabbed, mouseX, mouseY)
    }

    @Suppress("unused")
    private val notificationHandler = handler<NotificationEvent> { event ->
        notifications.push(event)
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val entity = event.entity
        if (entity is LivingEntity && entity !== mc.player) {
            lastAttacked = entity
            lastAttackAt = System.currentTimeMillis()
        }
    }

    private const val TARGET_TIMEOUT_MS = 4000L
    private const val TARGET_MAX_DISTANCE = 24f
    private const val MAX_FRAME_DELTA = 0.25f
}
