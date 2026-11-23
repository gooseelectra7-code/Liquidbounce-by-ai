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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Easings
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The modal base for pop-over widgets — dropdowns, choice lists, colour pickers — ported from
 * `client/gui/widget/ButtonWidget.java`.
 *
 * A [ButtonWidget] draws NOTHING in the main pass ([render] is final and empty, ButtonWidget.java:71);
 * it only paints while open, in the late pass ([renderModal]), so the pop-over overlays the rest of
 * the GUI. Open/close is animated by [openTween]:
 *  - **open** eases in over 0.38 s with easeOutQuint (source `update10`, ButtonWidget.java:190-193;
 *    `value239 = 0.38`, tween curve `getFloatByFloat9` = easeOutQuint).
 *  - **close** eases out over 0.26 s with easeOutCubic (source `update6`, ButtonWidget.java:159-162;
 *    `value240 = 0.26`, tween curve `getFloatByFloat3` = easeOutCubic).
 *
 * Two open modes (source `update3` vs `update5`):
 *  - [openInline] — anchored to a parent; the parent forwards [renderModal] and the button never
 *    touches [WidgetState] (source `flag7 = true`).
 *  - [openModal] — registers as the [WidgetState] global modal, which renders and routes input to
 *    it; on full close it unregisters itself (source `flag7 = false`, ButtonWidget.java:113-116).
 *
 * Closing paths: a click outside the button ([closeOnClickOutside], default on) or ESC
 * ([escCloses], default on).
 */
abstract class ButtonWidget : Widget() {

    /** Open/close progress tween (source `tween4`). Seeded to the open curve/duration. */
    protected val openTween: Tween = Tween(0f, OPEN_SECONDS).easing(Easings::easeOutQuint)

    /** True once [close] has begun (source `flag4`). Blocks re-entrant input while fading out. */
    private var closing: Boolean = false

    /** If true, a click outside the button closes it (source `flag5`, default true). */
    var closeOnClickOutside: Boolean = true

    /** If true, ESC closes the button (source `flag6`, default true). */
    var escCloses: Boolean = true

    /**
     * True once opened as the [WidgetState] modal, false when opened inline (source `flag7`).
     * Only a modal-opened button unregisters from [WidgetState] on close.
     */
    private var openedAsModal: Boolean = false

    /** Optional anchor supplier returning `[x, y]` applied every frame (source `colorSupplier`). */
    private var anchor: (() -> FloatArray?)? = null

    /** Applies the anchor position to [x]/[y] if set (source `update9`, ButtonWidget.java:176-184). */
    private fun applyAnchor() {
        val values = anchor?.invoke() ?: return
        if (values.size >= 2) {
            placeAt(values[0], values[1])
        }
    }

    // ---------------------------------------------------------------- open / close

    /**
     * Open anchored to a parent (source `update3`, ButtonWidget.java:26-31). The parent is
     * responsible for forwarding [renderModal]; this does not register with [WidgetState].
     */
    fun openInline() {
        closing = false
        openedAsModal = true // source sets flag7 = true here (inline: never clears WidgetState)
        applyOpenCurve()
        openTween.to(1f)
    }

    /**
     * Open as the [WidgetState] global modal (source `update5`, ButtonWidget.java:87-93).
     * [WidgetState] then renders it and routes all input to it until it closes.
     */
    fun openModal() {
        closing = false
        openedAsModal = false // source sets flag7 = false here (modal: clears WidgetState on close)
        applyOpenCurve()
        openTween.to(1f)
        WidgetState.setModal(this)
    }

    /** Begin the close animation (source `update4`, ButtonWidget.java:78-85). Idempotent while fading. */
    fun close() {
        if (!closing) {
            closing = true
            applyCloseCurve()
            openTween.to(0f)
            onCloseStart()
        }
    }

    /**
     * Set the `[x, y]` anchor supplier and open inline (source `setColorSupplier`,
     * ButtonWidget.java:41-45). The anchor is re-applied every frame while open.
     */
    fun setAnchorInline(anchor: () -> FloatArray?) {
        this.anchor = anchor
        applyAnchor()
        openInline()
    }

    /**
     * Set the `[x, y]` anchor supplier and open as the [WidgetState] modal (source
     * `setColorSupplier2`, ButtonWidget.java:149-153).
     */
    fun setAnchorModal(anchor: () -> FloatArray?) {
        this.anchor = anchor
        applyAnchor()
        openModal()
    }

    private fun applyOpenCurve() {
        openTween.duration = OPEN_SECONDS
        openTween.easing(Easings::easeOutQuint)
    }

    private fun applyCloseCurve() {
        openTween.duration = CLOSE_SECONDS
        openTween.easing(Easings::easeOutCubic)
    }

    /**
     * True while the pop-over is open or still animating in/out (source `check`,
     * ButtonWidget.java:66-68: `tween.current > 0.001 || tween.target > 0.001`).
     */
    fun isOpen(): Boolean = openTween.current > OPEN_EPSILON || openTween.target > OPEN_EPSILON

    /** Raw open progress `0..1` (source `getFloat2`/`getFloat4` = `tween.current`). */
    val openProgress: Float
        get() = openTween.current

    /** Pop-in scale `0.92..1` of the open progress (source `getFloat5` = `EasingPresets.popScale`). */
    fun openPopScale(): Float = EasingPresets.popScale(openTween.current)

    /** `16 * (1 - progress)` falloff of the open progress (source `getFloat3` = `EasingPresets.falloff16`). */
    fun openFalloff(): Float = EasingPresets.falloff16(openTween.current)

    // ---------------------------------------------------------------- render

    /** [ButtonWidget] never draws in the main pass (source final empty override, ButtonWidget.java:71). */
    final override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {}

    /**
     * Late pass: draw the open pop-over, or finish closing (source `onFloatFloatFloatMatrix4f2`,
     * ButtonWidget.java:107-129). When fully closed after a [close], fire [onClosed], drop the
     * anchor, and — if this was opened as a modal — unregister from [WidgetState]. Otherwise scale
     * the incoming [alpha] by the open progress and hand off to [renderOpen].
     */
    final override fun renderModal(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        val progress = openTween.get()
        if (progress <= OPEN_EPSILON) {
            if (closing) {
                onClosed()
                anchor = null
                if (!openedAsModal) {
                    WidgetState.clearModal(this)
                }
            }
            return
        }
        applyAnchor()
        // The source toggled RenderSystem depth-test here; the fork's 2D pipeline paints in
        // submission order, so this late pass is already on top — no manual depth toggle needed.
        renderOpen(ctx, alpha * progress, mouseX, mouseY)
    }

    /**
     * Draw the open pop-over body. [alpha] is already faded by the open progress; ([mouseX],
     * [mouseY]) is the pointer (source concrete draw `onFloatFloatFloatMatrix4f3`,
     * ButtonWidget.java:20). Draw through `SystemDraw`.
     */
    protected abstract fun renderOpen(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float)

    /** Hook fired when the close animation starts (source `update7`, empty by default). */
    protected open fun onCloseStart() {}

    /** Hook fired once the widget has fully closed (source `update8`, empty by default). */
    protected open fun onClosed() {}

    // ---------------------------------------------------------------- input

    /**
     * While open, a click outside the body closes the pop-over (source `isIntDoubleDouble`,
     * ButtonWidget.java:47-60). A click while already closing is swallowed. Clicks inside are
     * delegated to [onClickInside].
     */
    final override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (closing) {
            return true
        }
        applyAnchor()
        if (button == 0 && closeOnClickOutside && !contains(mouseX, mouseY)) {
            close()
            return true
        }
        return onClickInside(mouseX, mouseY, button)
    }

    /**
     * ESC closes the pop-over when [escCloses] is set (source `isIntIntInt2`, ButtonWidget.java:139-147;
     * ESC keyCode = 256). Other keys are delegated to [onKeyInside].
     */
    final override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (escCloses && keyCode == KEY_ESCAPE) {
            close()
            return true
        }
        return onKeyInside(keyCode, scanCode, modifiers)
    }

    /** Handle a click inside the open body (source `isIntDoubleDouble2`, default not consumed). */
    protected open fun onClickInside(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    /** Handle a non-ESC key while open (source `isIntIntInt3`, default not consumed). */
    protected open fun onKeyInside(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    /** Reset when dropped as the [WidgetState] modal (source `update2`, ButtonWidget.java:170-174). */
    override fun onModalDismissed() {
        closing = false
        openTween.snap(0f)
    }

    companion object {
        /** Open-animation duration, seconds (source `value239 = 0.38`). */
        const val OPEN_SECONDS = 0.38f

        /** Close-animation duration, seconds (source `value240 = 0.26`). */
        const val CLOSE_SECONDS = 0.26f

        /** Progress below which the pop-over is treated as closed (source `0.001`). */
        private const val OPEN_EPSILON = 0.001f

        /** GLFW `GLFW_KEY_ESCAPE` (source literal `256`, ButtonWidget.java:141). */
        private const val KEY_ESCAPE = 256
    }
}
