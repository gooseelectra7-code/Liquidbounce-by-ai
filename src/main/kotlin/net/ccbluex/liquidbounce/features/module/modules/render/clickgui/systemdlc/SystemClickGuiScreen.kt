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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.ClickGuiSearchAware
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.NavBar
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.QuickMenu
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.SearchState
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.SideBar
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.SystemGuiState
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.WidgetState
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.integration.screen.NativeClientScreen
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.util.ARGB
import org.lwjgl.glfw.GLFW

/**
 * The SystemDLC ClickGUI screen shell (cluster C6) — a vanilla [Screen] port of
 * `client/gui/screen/ClickGuiScreen.java`, hosting the sidebar / topbar / navbar chrome over the C4/C5
 * widget foundation. It satisfies the SAME Screen / [GuiGraphicsExtractor] contract as
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen] (its authoritative
 * template): it draws in [extractRenderState], receives `MouseButtonEvent`/`KeyEvent`/`CharacterEvent`,
 * `isPauseScreen() = false`, and finishes the close animation in [tick].
 *
 * ## The virtual window + entrance transform (constants cited to ClickGuiScreen.java)
 * The GUI is a fixed 1188×700 window (`update9`, :404-415) centred inside a virtual space
 * `width/scale × height/scale` where `scale = min(width/1920, height/1080) · guiSize` (`update9`, :404).
 * On open it plays an entrance: the window scales 0.93→1.0 (`f9 = 0.93 + 0.06999999·t`, :449) and slides
 * 18→0 px down (`f10 = (1 - t)·18`, :450) about the window centre, over ~0.5 s open / ~0.42 s close
 * (`value245`/`value246`, :64-65; `tween.setValue5`, :541/:555).
 *
 * ## Input hit-testing UNDER the animated scale (the fiddly part)
 * [LeetClickGuiScreen] maps the mouse through its live zoom transform ([LeetClickGuiScreen.toVirtualX]/
 * `toVirtualY`, `(mx - curTx) / curScale`). This screen does the same, but its transform is the full
 * `base-scale ∘ centre-pop ∘ slide` stack, so [toVirtualX]/[toVirtualY] invert ALL THREE — the source
 * only inverted the base scale (`getValue2382`, :266), but inverting the entrance too keeps clicks
 * correct while the window is still animating open/closed.
 *
 * ## Draw order (`render`, ClickGuiScreen.java:461-491)
 * backdrop dim → window panel → SideBar → NavBar (TopBar + content) → [WidgetState] modal late pass.
 *
 * ## State & entry
 * UI state lives in [SystemGuiState] (in-memory, session-scoped). Opened by `ModuleClickGui` (see the
 * port report for the exact `Renderer.SYSTEM` edit); ESC starts the close animation and [tick] performs
 * the real `mc.setScreen(null)` once it finishes.
 */
class SystemClickGuiScreen :
    Screen("ClickGUI".asPlainText()),
    NativeClientScreen,
    ClickGuiSearchAware {

    // True while the header search field owns the keyboard, so module keybinds do not fire mid-query
    // (parity with the browser GUI's interop flag; read by ModuleClickGui.isInSearchBar).
    override val isSearchFocused: Boolean get() = SearchState.focused

    init {
        // Each time the screen opens, start unfiltered (SearchState is a JVM-lifetime singleton).
        SearchState.reset()
    }

    private val quickMenu = QuickMenu(
        onChangeTheme = { SystemDlcTheme.dark = !SystemDlcTheme.dark },
        // STUB: the source cycled `Translations`; no localization is wired into the shell yet.
        onChangeLanguage = {},
        // STUB: the source triggered its Panic module; a later cluster may route this to ModuleUnhook.
        onPanic = {},
        onCloseGui = { startClose() },
    )
    private val sideBar = SideBar(quickMenu)
    private val navBar = NavBar()

    // - open/close animation -
    private val openedAt = System.currentTimeMillis()
    private var closingAt = 0L
    private var lastFrameMs = 0L

    // - live transform of the current frame (virtual window coords -> real via base-scale ∘ pop ∘ slide) -
    private var baseScale = 1f
    private var winX = 0f
    private var winY = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var pop = 1f
    private var slide = 0f

    // - window drag-to-move (grab the empty TopBar strip) -
    private var draggingWindow = false

    val isClosing: Boolean get() = closingAt != 0L

    // - transform inverse: real (screen) coords -> virtual window coords -
    private fun toVirtualX(mx: Float): Float = (mx / baseScale - centerX) / pop + centerX

    private fun toVirtualY(my: Float): Float = (my / baseScale - centerY) / pop + centerY - slide

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val f = -2f * x + 2f
            1f - f * f * f / 2f
        }
    }

    private fun startClose() {
        if (closingAt == 0L) {
            closingAt = System.currentTimeMillis()
            draggingWindow = false
            WidgetState.current()?.let { WidgetState.clearModal(it) }
        }
    }

    override fun tick() {
        if (closingAt != 0L && System.currentTimeMillis() - closingAt >= CLOSE_MS.toLong()) {
            mc.setScreen(null)
        }
    }

    override fun onClose() {
        // Intercept the vanilla close: play the close animation; tick() performs the real close.
        startClose()
    }

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val now = System.currentTimeMillis()
        val dtSec = (if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(0L, 50L)) / 1000f
        lastFrameMs = now
        SystemDlcTheme.advance(dtSec)

        val openT = ((now - openedAt) / OPEN_MS).coerceIn(0f, 1f)
        val closeT = if (closingAt == 0L) 0f else ((now - closingAt) / CLOSE_MS).coerceIn(0f, 1f)
        val motion = easeInOutCubic(openT) * (1f - easeInOutCubic(closeT))

        // unscaled page backdrop dim, faded by the open/close motion
        val dim = (BACKDROP_ALPHA * motion).toInt().coerceIn(0, 255)
        ctx.fill(0, 0, width, height, ARGB.color(dim, BACKDROP_R, BACKDROP_G, BACKDROP_B))

        // base scale + centred virtual window (source update9)
        baseScale = minOf(width / REF_W, height / REF_H) * GUI_SIZE
        val vw = width / baseScale
        val vh = height / baseScale
        val maxPanX = maxOf(0f, (vw - WINDOW_W) / 2f)
        val maxPanY = maxOf(0f, (vh - WINDOW_H) / 2f)
        SystemGuiState.panX = SystemGuiState.panX.coerceIn(-maxPanX, maxPanX)
        SystemGuiState.panY = SystemGuiState.panY.coerceIn(-maxPanY, maxPanY)
        winX = (vw - WINDOW_W) / 2f + SystemGuiState.panX
        winY = (vh - WINDOW_H) / 2f + SystemGuiState.panY
        centerX = winX + WINDOW_W / 2f
        centerY = winY + WINDOW_H / 2f
        pop = ENTRANCE_MIN_SCALE + (1f - ENTRANCE_MIN_SCALE) * motion
        slide = (1f - motion) * ENTRANCE_SLIDE

        val vMouseX = toVirtualX(mouseX.toFloat())
        val vMouseY = toVirtualY(mouseY.toFloat())

        val pose = ctx.pose()
        pose.pushMatrix()
        pose.scale(baseScale, baseScale)
        pose.translate(centerX, centerY)
        pose.scale(pop, pop)
        pose.translate(-centerX, -centerY)
        pose.translate(0f, slide)
        try {
            drawGui(ctx, vMouseX, vMouseY, motion)
        } finally {
            pose.popMatrix()
        }
    }

    private fun drawGui(ctx: GuiGraphicsExtractor, mouseX: Float, mouseY: Float, alpha: Float) {
        // window plate
        SystemDraw.panel(
            ctx, winX, winY, WINDOW_W, WINDOW_H, WINDOW_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.background(), alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
            borderWidth = 1f,
            shadow = SystemDraw.withAlpha(SystemDlcTheme.SHADOW, alpha),
            shadowBlur = WINDOW_SHADOW_BLUR,
        )

        // sidebar (sets its own width from the collapse tween)
        sideBar.placeAt(winX, winY)
        sideBar.render(ctx, alpha, mouseX, mouseY)

        // content column to the right of the sidebar
        val navX = winX + sideBar.width + 1f
        navBar.placeAt(navX, winY)
        navBar.width = WINDOW_W - sideBar.width - 1f
        navBar.height = WINDOW_H
        navBar.render(ctx, alpha, mouseX, mouseY)

        // modal late pass (QuickMenu pop-over), drawn on top of everything
        WidgetState.renderModal(ctx, alpha, mouseX, mouseY)
    }

    /** The empty TopBar strip (to the right of the sidebar) used to drag the window. */
    private fun inTitleStrip(mx: Float, my: Float): Boolean =
        mx >= winX + sideBar.width + 1f && mx <= winX + WINDOW_W &&
            my >= winY && my <= winY + TOPBAR_H

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (isClosing) {
            return true
        }
        // Commit a focused text field when the user clicks anywhere (incl. blank space / another widget).
        net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row.TextRow
            .commitActive()
        val mx = toVirtualX(click.x.toFloat())
        val my = toVirtualY(click.y.toFloat())
        val button = click.button()

        // A left click outside the search field unfocuses it (the query is kept, so results persist).
        // A click ON the field is handled by the TopBar below, which (re)focuses it.
        if (button == 0 && SearchState.focused && !SearchState.inField(mx, my)) {
            SearchState.blur()
        }

        if (WidgetState.mouseClicked(mx, my, button)) {
            return true
        }
        if (sideBar.mouseClicked(mx, my, button)) {
            return true
        }
        if (navBar.mouseClicked(mx, my, button)) {
            return true
        }
        // grab the empty title strip to move the window
        if (button == 0 && inTitleStrip(mx, my)) {
            draggingWindow = true
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() == 0) {
            draggingWindow = false
        }
        val mx = toVirtualX(click.x.toFloat())
        val my = toVirtualY(click.y.toFloat())
        if (WidgetState.mouseReleased(mx, my, click.button())) {
            return true
        }
        if (navBar.mouseReleased(mx, my, click.button())) {
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        if (isClosing) {
            return true
        }
        val button = click.button()
        if (button == 0 && draggingWindow) {
            SystemGuiState.panX += (offsetX / baseScale).toFloat()
            SystemGuiState.panY += (offsetY / baseScale).toFloat()
            return true
        }
        val mx = toVirtualX(click.x.toFloat())
        val my = toVirtualY(click.y.toFloat())
        if (WidgetState.mouseDragged(mx, my, button, offsetX, offsetY)) {
            return true
        }
        if (navBar.mouseDragged(mx, my, button, offsetX, offsetY)) {
            return true
        }
        return super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        if (isClosing) {
            return true
        }
        val mx = toVirtualX(mouseX.toFloat())
        val my = toVirtualY(mouseY.toFloat())
        if (WidgetState.mouseScrolled(mx, my, verticalAmount)) {
            return true
        }
        navBar.mouseScrolled(mx, my, verticalAmount)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (isClosing) {
            return true
        }
        val key = input.key

        // Ctrl+1..9 -> toggle the Nth visible search result (source ClickGuiScreen.java:509-514, which
        // required the search focused + Ctrl held; there it ran the result's runnable, here it toggles
        // the module card that IS the result).
        if (SearchState.focused && isCtrlDown() && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            if (SearchState.quickPick(key - GLFW.GLFW_KEY_1)) {
                return true
            }
        }

        // an open modal (QuickMenu) captures keys first
        if (WidgetState.keyPressed(key, 0, 0)) {
            return true
        }
        // the focused search field captures editing keys (source: the SearchBar modal captured keys)
        if (SearchState.focused && SearchState.keyPressed(key)) {
            return true
        }
        // route to the active page (a focused setting text field consumes; nothing else does)
        if (navBar.keyPressed(key, 0, 0)) {
            return true
        }
        // Ctrl+F and Tab open + focus the search field (source ClickGuiScreen.java:520 / :523)
        if ((key == GLFW.GLFW_KEY_F && isCtrlDown()) || key == GLFW.GLFW_KEY_TAB) {
            SearchState.focus()
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            startClose()
            return true
        }
        return super.keyPressed(input)
    }

    private fun isCtrlDown(): Boolean {
        val window = mc.window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        if (isClosing) {
            return true
        }
        val codepoint = input.codepoint()
        if (codepoint > 0xFFFF) {
            return false
        }
        val ch = codepoint.toChar()
        if (ch.isISOControl()) {
            return false
        }
        if (WidgetState.charTyped(ch, 0)) {
            return true
        }
        // the focused search field takes typed characters before the page's setting fields
        if (SearchState.focused && SearchState.charTyped(ch)) {
            return true
        }
        if (navBar.charTyped(ch, 0)) {
            return true
        }
        return super.charTyped(input)
    }

    private companion object {
        // Virtual window (source ClickGuiScreen.java 1188×700; centred inside a 1920×1080-normalised space)
        const val WINDOW_W = 1188f
        const val WINDOW_H = 700f
        const val REF_W = 1920f
        const val REF_H = 1080f
        const val WINDOW_RADIUS = 12f
        const val WINDOW_SHADOW_BLUR = 18f
        const val TOPBAR_H = 49f

        /** Base GUI size multiplier (source `getFloat2()` steady state; the press-shrink gimmick is omitted). */
        const val GUI_SIZE = 1f

        // Entrance (source :64-65/:449-450): scale 0.93→1.0 + 18→0 px slide, 0.5 s open / 0.42 s close.
        const val ENTRANCE_MIN_SCALE = 0.93f
        const val ENTRANCE_SLIDE = 18f
        const val OPEN_MS = 500f
        const val CLOSE_MS = 420f

        // Page backdrop dim (near-black, faded by motion)
        const val BACKDROP_R = 10
        const val BACKDROP_G = 10
        const val BACKDROP_B = 12
        const val BACKDROP_ALPHA = 190f
    }
}
