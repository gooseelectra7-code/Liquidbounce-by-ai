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

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The base contract every SystemDLC ClickGUI widget builds on — the C4 core, ported from the
 * recovered `client/gui/widget/Widget.java`.
 *
 * ## Scrambled-signature decode (this is the load-bearing part of the port)
 *
 * The recovered source has machine-recovered method names and scrambled positional argument
 * order. Every decode below was derived from CALL SITES, not transliterated:
 *
 * - **Rectangle / fields.** `Widget.isDoubleDouble` (the hit test) expands to
 *   `isFloatFloatDoubleFloatFloatDouble(value235, value236, my, value238, value237, mx)` =
 *   `mx in [value235, value235+value237] && my in [value236, value236+value238]`
 *   (Widget.java:100-121). Therefore **value235 = x, value236 = y, value237 = width,
 *   value238 = height** — retargeted here to [x] / [y] / [width] / [height].
 *   Cross-checked in `ModuleWidget.onFloatFloatFloatMatrix4f` (ModuleWidget.java:112, same
 *   `(x, ?, my, 32, width, mx)` shape).
 *
 * - **Main render.** `abstract onFloatFloatFloatMatrix4f(value, value2, value3, matrix4f)`
 *   (Widget.java:65). The top-level call is `sideBar.onFloatFloatFloatMatrix4f(f3, f1, f, m)`
 *   (ClickGuiScreen.java:479) and `ModuleWidget`'s override uses `value` as the shader opacity
 *   and hit-tests `(x, .., value2, 32, width, value3)` (ModuleWidget.java:99-112), so
 *   **value = alpha, value2 = mouseY, value3 = mouseX**. Exposed here, normalized, as
 *   [render]`(ctx, alpha, mouseX, mouseY)`.
 *
 * - **Modal/late render.** `onFloatFloatFloatMatrix4f2(value, value2, value3, matrix4f)`
 *   (Widget.java:108). `WidgetState.onMatrix4fFloatFloatFloat(m, value, value2, value3)` forwards
 *   `widget.onFloatFloatFloatMatrix4f2(value2, value, value3, m)` (WidgetState.java:53) and is
 *   called `WidgetState.onMatrix4fFloatFloatFloat(m, mouseX, mouseY, alpha)`
 *   (ClickGuiScreen.java:489), and `ButtonWidget` treats the 3rd arg as the fading alpha
 *   (`f1 = value3 * tween`, ButtonWidget.java:123). So **value3 = alpha, the other two are the
 *   mouse coords** — exposed, normalized, as [renderModal]`(ctx, alpha, mouseX, mouseY)`.
 *   The scrambled source ships alpha FIRST in the main pass and LAST in the modal pass; both are
 *   normalized to `(alpha, mouseX, mouseY)` here so siblings override one shape.
 *
 * - **Input.** `isIntDoubleDouble(count, value, value2)` is mouse-click: `count = button`,
 *   `value = mouseX`, `value2 = mouseY` (`ButtonWidget.isIntDoubleDouble` hit-tests
 *   `isDoubleDouble(value, value2)`, ButtonWidget.java:53). The other scrambled input entry
 *   points decode from `ScrollableWidget` / `ButtonWidget` / `ModuleWidget` and `WidgetState`
 *   dispatch (see each method's KDoc). All are normalized to natural `(mouseX, mouseY, ...)` order.
 *
 * ## Retarget to the fork foundation
 *
 * - The source render methods take a joml `Matrix4f` (the pose); on 26.1 the pose lives inside
 *   the [GuiGraphicsExtractor], so [render]/[renderModal] take `ctx` instead. All drawing goes
 *   through `SystemDraw` (never the raw shader families the source used).
 * - The source's `render/ScissorStack` is NOT ported; widgets clip via `SystemDraw.scissor { }`.
 * - Input arrives already-decoded from the vanilla `Screen` (a later cluster wires it); widgets
 *   never poll GLFW as the source client did.
 *
 * Subclasses override [render] (always) and, if they capture input or paint a modal overlay, the
 * relevant input methods and [renderModal].
 */
abstract class Widget {

    /** Left edge, GUI units (source `value235`). Positioned via [setPosition]/[placeAt]/[animateTo]. */
    var x: Float = 0f
        protected set

    /** Top edge, GUI units (source `value236`). */
    var y: Float = 0f
        protected set

    /** Width, GUI units (source `value237`). Public so the layout owner can size a widget. */
    open var width: Float = 0f

    /** Height, GUI units (source `value238`). Written by subclasses as they measure their content. */
    var height: Float = 0f

    /**
     * True while the pointer is over this widget AND no modal is capturing input — updated by
     * [hoverFactor] (source `flag`, set in `getFloatByFloatFloat`, Widget.java:51).
     */
    var hovered: Boolean = false
        protected set

    /**
     * Generic per-widget enable flag, default true (source `flag2`, `isFlag2`/`setFlag2`,
     * Widget.java:16/46/67). INFERRED: no widget-render-path caller was found in the recovered
     * source (the `isFlag2` hits are all on unrelated `Waypoint`/`ConfigEntry` types); carried
     * faithfully for the siblings that may read it.
     */
    var interactable: Boolean = true

    /** Short reveal tween used only for the hover factor (source `tween`, `EasingPresets.getTween()`). */
    private val hoverTween: Tween = EasingPresets.cubicTweenShort()

    // Lazily-created animated-position tweens (source `tween2`/`tween3`, expo, 0.32s).
    private var posTweenX: Tween? = null
    private var posTweenY: Tween? = null
    private var positionInitialized = false // source `flag3`

    private fun ensurePositionTweens() {
        if (posTweenX == null) {
            posTweenX = EasingPresets.expoTween(0f, POSITION_TWEEN_SECONDS)
            posTweenY = EasingPresets.expoTween(0f, POSITION_TWEEN_SECONDS)
        }
    }

    /**
     * Snap the widget to ([x], [y]) instantly and seed the animated-position tweens there
     * (source `onFloatFloat`, Widget.java:21-32). Use for the initial placement.
     */
    fun setPosition(x: Float, y: Float) {
        ensurePositionTweens()
        posTweenX!!.snap(x)
        posTweenY!!.snap(y)
        this.x = x
        this.y = y
        positionInitialized = true
    }

    /**
     * Set the position directly with no animation (source `onFloatFloat2`, Widget.java:56-59).
     * NOTE: the source took its arguments SWAPPED (`value235 = value2; value236 = value`); the
     * order is normalized to `(x, y)` here.
     */
    fun placeAt(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    /**
     * Animate toward ([x], [y]) (source `onFloatFloat3`, Widget.java:78-94). If the position
     * animation has never been seeded it snaps instead. Advance it each frame with
     * [updatePosition].
     */
    fun animateTo(x: Float, y: Float) {
        ensurePositionTweens()
        if (!positionInitialized) {
            posTweenX!!.snap(x)
            posTweenY!!.snap(y)
            this.x = x
            this.y = y
            positionInitialized = true
        } else {
            posTweenX!!.to(x)
            posTweenY!!.to(y)
        }
    }

    /**
     * Advance the animated position into [x]/[y] (source `update`, Widget.java:71-76). Call once
     * per frame if the widget uses [animateTo].
     */
    fun updatePosition() {
        val tx = posTweenX
        val ty = posTweenY
        if (positionInitialized && tx != null && ty != null) {
            x = tx.get()
            y = ty.get()
        }
    }

    /** True if ([mouseX], [mouseY]) is inside this widget's rectangle (source `isDoubleDouble`). */
    fun contains(mouseX: Float, mouseY: Float): Boolean =
        rectContains(x, y, width, height, mouseX, mouseY)

    /**
     * Update [hovered] and return the eased 0..1 hover factor (source `getFloatByFloatFloat`,
     * Widget.java:50-54). Hover is suppressed while a modal owns input
     * ([WidgetState.isModalActive]) so a widget under an open dropdown never highlights.
     */
    protected fun hoverFactor(mouseX: Float, mouseY: Float): Float {
        hovered = contains(mouseX, mouseY) && !WidgetState.isModalActive()
        hoverTween.to(if (hovered) 1f else 0f)
        return hoverTween.get()
    }

    // ---------------------------------------------------------------- render contract

    /**
     * Main render pass (source `onFloatFloatFloatMatrix4f`, Widget.java:65). [alpha] is the
     * widget's opacity multiplier; ([mouseX], [mouseY]) are the pointer position. Draw through
     * `SystemDraw`.
     */
    abstract fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float)

    /**
     * Late / modal render pass, drawn on top of everything after the main pass (source
     * `onFloatFloatFloatMatrix4f2`, Widget.java:108). Default no-op. Driven for the global modal
     * by [WidgetState.renderModal], and for an inline-open [ButtonWidget] by its parent forwarding
     * this call. Because the fork's 2D pipeline paints in submission order, this pass is naturally
     * on top — the source's manual depth-test toggle is unnecessary and intentionally omitted.
     */
    open fun renderModal(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {}

    // ----------------------------------------------------------------- input contract
    // All default to "not consumed"; the Screen forwards the vanilla events already decoded.

    /**
     * Left/right/middle click. Return true to consume (source `isIntDoubleDouble(button, mx, my)`,
     * Widget.java:42).
     */
    open fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    /** Mouse button release (source `isDoubleDoubleInt(mx, my, button)`, Widget.java:131). */
    open fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean = false

    /**
     * Mouse drag (source `isDoubleDoubleIntDoubleDouble(mx, my, button, dx, dy)`, Widget.java:127).
     */
    open fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean = false

    /**
     * Wheel scroll; [amount] is the wheel delta. Return true to consume (source
     * `isDoubleDoubleDouble(amount, mx, my)`, decoded from `ScrollableWidget.isDoubleDoubleDouble`
     * which hit-tests `isDoubleDouble(value2, value3)`, ScrollableWidget.java:35-42).
     */
    open fun mouseScrolled(mouseX: Float, mouseY: Float, amount: Double): Boolean = false

    /**
     * Key press. Return true to consume (source `isIntIntInt2`; the ESC branch checks
     * `keyCode == 256`, ButtonWidget.java:141, so the keyCode is the 3rd scrambled arg — the args
     * are normalized to `(keyCode, scanCode, modifiers)` here).
     */
    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    /** Key release (source `isIntIntInt`, the un-overridden sibling of key-press, Widget.java:38). */
    open fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false

    /** Typed character (source `isIntChar(modifiers, char)`, Widget.java:123). */
    open fun charTyped(char: Char, modifiers: Int): Boolean = false

    /**
     * Called when this widget is dropped as the global modal (source `update2`, Widget.java:143;
     * `WidgetState.setWidget2` invokes it on the outgoing modal, WidgetState.java:62). Default no-op.
     */
    open fun onModalDismissed() {}

    companion object {
        /** Animated-position tween duration in seconds (source `EasingPresets.getTweenByFloatFloat(0, 0.32)`). */
        const val POSITION_TWEEN_SECONDS = 0.32f

        /**
         * Point-in-rectangle test (source static `isFloatFloatDoubleFloatFloatDouble`,
         * Widget.java:119-121): `px in [x, x+w] && py in [y, y+h]`. Exposed for subclasses that
         * hit-test sub-rects (e.g. a header row narrower than the whole widget).
         */
        fun rectContains(x: Float, y: Float, w: Float, h: Float, px: Float, py: Float): Boolean =
            px >= x && px <= x + w && py >= y && py <= y + h
    }
}
