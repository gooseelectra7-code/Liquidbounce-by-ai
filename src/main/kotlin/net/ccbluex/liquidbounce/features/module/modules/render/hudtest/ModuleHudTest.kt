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

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
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
 * HudTest module.
 *
 * A native, self-rendered HUD that reproduces one specific design pixel for pixel: a calm, flat set
 * of dark glass cards — a status bar, a Windows "now playing" card, a potions / keybinds /
 * cooldowns list system with its own settings popover, a target hud with a gear strip, a
 * notification stack and a coordinate readout.
 *
 * Everything is drawn in **native-pixel space**: the gui scale is taken back out of the pose, so
 * one local unit is one physical screen pixel and the measured numbers of the design are used
 * verbatim. Geometry is contour-tessellated with a one-device-pixel antialiasing feather, text goes
 * through the bundled Inter glyph-atlas renderer, and the cards sample a region-limited background
 * blur — all on the vanilla GUI pipeline, without registering a pipeline of its own.
 *
 * Panels are placed by anchor plus an offset in native pixels and can be dragged while a screen
 * (the chat or a container) is open, which is also the only time their buttons accept clicks.
 */
object ModuleHudTest : Mode("HudTest") {

    override val parent: ModeValueGroup<*>
        get() = ModuleHud.theme

    private val blur by boolean("Blur", true)
    private val blurStrength by int("BlurStrength", 7, 1..10)
    private val blurPasses by int("BlurPasses", 2, 1..6)

    internal val statusBar = HudTestStatusBarPanel(this)
    internal val nowPlaying = HudTestMediaPanel(this)
    internal val potions = HudTestPotionsPanel(this)
    internal val keybinds = HudTestKeybindsPanel(this)
    internal val cooldowns = HudTestCooldownsPanel(this)
    internal val targetHud = HudTestTargetPanel(this)
    internal val coords = HudTestCoordsPanel(this)
    internal val notifications = HudTestNotificationsPanel(this)

    /** Draw order, bottom to top; the settings popover is drawn above all of them. */
    private val panels: List<HudTestPanel> = listOf(
        statusBar, nowPlaying, potions, keybinds, cooldowns, targetHud, coords, notifications,
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

    override fun enable() {
        HudTestMediaBridge.start()
        super.enable()
    }

    override fun disable() {
        HudTestMediaBridge.stop()
        HudTestSettingsPopover.close()
        HudTestInput.endDrag()
        super.disable()
    }

    /**
     * The HUD is interactive — draggable panels, clickable affordances — exactly while the chat or
     * a container screen is open. Any other screen (the ClickGUI above all) is left alone.
     */
    fun editMode(): Boolean = mc.screen is ChatScreen || mc.screen is AbstractContainerScreen<*>

    /**
     * Whether the vanilla corner effect icons should stay off screen: the Potions panel is the
     * same list, so leaving both on draws every effect twice. Hooked from
     * `MixinGui#extractEffects`.
     */
    @JvmStatic
    fun shouldHideVanillaEffects(): Boolean = running && !HideAppearance.isHidingNow && potions.enabled

    /**
     * The region-limited background blur every card samples. All panels share one capture per
     * frame, because vanilla allows the blur marker only once per frame.
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
        HudTestInput.beginFrame()
        for (panel in panels) {
            if (panel.enabled) {
                panel.render(event, delta)
            }
        }
        HudTestSettingsPopover.render(event, delta)
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return@handler
        }
        if (event.action == GLFW.GLFW_RELEASE) {
            HudTestInput.endDrag()
            return@handler
        }
        if (event.action != GLFW.GLFW_PRESS || !editMode()) {
            return@handler
        }

        val mouseX = HudTestInput.mouseX()
        val mouseY = HudTestInput.mouseY()
        if (HudTestInput.consumeClick(mouseX, mouseY)) {
            return@handler
        }
        val grabbed = panels.lastOrNull { it.enabled && it.containsGui(mouseX, mouseY) } ?: return@handler
        HudTestInput.startDrag(grabbed, mouseX, mouseY)
    }

    @Suppress("unused")
    private val keyboardHandler = handler<KeyboardKeyEvent> { event ->
        if (mc.screen == null) {
            nowPlaying.handleKey(event)
        }
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
