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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.LabelWidget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.AnimatedInt
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW

/**
 * The bind chip shown in a [ModuleRow] header — the SystemDLC `client/gui/widget/KeybindButton.java`
 * role, re-expressed against the FORK's bind model instead of the source's `KeybindSetting`.
 *
 * ## Geometry (cited to KeybindButton.java)
 * A rounded field: a keyboard glyph then the bind label. The source draws a 10 px [SystemIcon.KEYBOARD]
 * at `x + 4`, vertically centred, then the bind text at `iconRight + 4` (KeybindButton.java:108-116); its
 * field height is 20 (`value238 = 20`, :25) and its width is `20 + textWidth` padded (`getFloat`, :185-188).
 * This port keeps the same layout at a header-friendly 16 px height. When capturing it shows `"..."`
 * (source `getString`, :119-126).
 *
 * ## Bind mechanism — the FORK's, not the source's
 * The source mutated a `KeybindSetting`; here the bind is the module's `Value<InputBind>`
 * ([net.ccbluex.liquidbounce.features.module.ClientModule.bindValue], statically `Value<InputBind>` — see
 * AGENTS.md "Native ClickGUI" gotcha). Capture writes it EXACTLY as
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen] does
 * (LeetClickGuiScreen.kt:3646-3769):
 *  - a key -> `copy(boundKey = InputConstants.Type.KEYSYM.getOrCreate(key))`,
 *  - a mouse button -> `copy(boundKey = InputConstants.Type.MOUSE.getOrCreate(button))`,
 *  - Delete / Backspace -> `copy(boundKey = InputConstants.UNKNOWN)` (unbind),
 *  - ESC -> cancel, leaving the old bind untouched.
 * `copy()` preserves the action + modifiers. The label is `InputBind.keyName` (returns "None" when
 * unbound, InputBind.kt:81-89).
 *
 * ## Arming and capture routing
 * A left click ON the chip toggles the [armed] state (source `isDoubleDouble2`, :137-141). While armed the
 * host ([ContentArea]) routes the NEXT key/mouse event here through [onCaptureKey] / [onCaptureMouse],
 * regardless of where the pointer is — mirroring LeetClickGuiScreen's `pendingBind` swallow. A left click
 * back on the chip while armed cancels instead (source `isIntDoubleDouble`, :29-48).
 */
class KeybindButton(private val bind: Value<InputBind>) : LabelWidget() {

    /** True while waiting to capture the next key/button (source `flag4`). */
    var armed: Boolean = false
        private set

    /** Arm-highlight cross-fade, 0.15 s easeOutCubic (source `tween4`, KeybindButton.java:21). */
    private val armTween: Tween = EasingPresets.cubicTween(0f, ARM_TWEEN_SECONDS)

    init {
        height = HEIGHT
        width = measureWidth()
    }

    private fun label(): String = if (armed) "..." else bind.get().keyName.ifEmpty { "None" }

    /** Recompute [width] from the current label and return it (source `getFloat`, :185-188). */
    fun measureWidth(): Float {
        val w = PAD + ICON_SIZE + ICON_GAP + SystemDraw.textWidth(label(), TEXT_SIZE) + PAD
        width = w
        return w
    }

    override fun render(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        measureWidth()
        armTween.to(if (armed) 1f else 0f)
        val f = armTween.get()

        val fill = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.primary().argb, f, SystemDlcTheme.elevated().argb))
        val textColor = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.background().argb, f, SystemDlcTheme.mutedFg().argb))
        val iconColor = Color4b(AnimatedInt.lerpArgb(SystemDlcTheme.background().argb, f, SystemDlcTheme.mutedFg().argb))

        SystemDraw.pill(ctx, x, y, width, height, SystemDraw.withAlpha(fill, alpha))

        val iconX = x + PAD
        val iconY = y + (height - ICON_SIZE) / 2f
        SystemDraw.icon(ctx, SystemIcon.KEYBOARD, iconX, iconY, ICON_SIZE, SystemDraw.withAlpha(iconColor, alpha))

        val textX = iconX + ICON_SIZE + ICON_GAP
        val textY = y + (height - SystemDraw.lineHeight(TEXT_SIZE)) / 2f
        SystemDraw.text(
            ctx, label(), textX, textY, TEXT_SIZE,
            SystemDraw.withAlpha(textColor, alpha), align = TextAlign.LEFT,
        )
    }

    /** Left click on the chip toggles the capture arm (source `isDoubleDouble2`). */
    override fun onClick(): Boolean {
        armed = !armed
        return true
    }

    /** Arm the capture from an external affordance (the card's MMB, mirroring LeetClickGuiScreen's card). */
    fun beginCapture() {
        armed = true
    }

    /**
     * Host-routed mouse event while [armed]: a left click back on the chip cancels; any other button (or
     * a click elsewhere) binds that mouse button (source `isIntDoubleDouble`, KeybindButton.java:29-48).
     */
    fun onCaptureMouse(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button == 0 && contains(mouseX, mouseY)) {
            armed = false
            return true
        }
        bind.set(bind.get().copy(boundKey = InputConstants.Type.MOUSE.getOrCreate(button)))
        armed = false
        return true
    }

    /**
     * Host-routed key event while [armed]: ESC cancels, Delete / Backspace unbind, anything else becomes
     * the new bound key (fork semantics, LeetClickGuiScreen.kt:3758-3769).
     */
    fun onCaptureKey(keyCode: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> Unit
            GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE ->
                bind.set(bind.get().copy(boundKey = InputConstants.UNKNOWN))
            else ->
                bind.set(bind.get().copy(boundKey = InputConstants.Type.KEYSYM.getOrCreate(keyCode)))
        }
        armed = false
        return true
    }

    /** Drop the arm without changing the bind (used when the page changes / cards rebuild). */
    fun cancelCapture() {
        armed = false
    }

    private companion object {
        const val HEIGHT = 16f
        const val ICON_SIZE = 10f
        const val ICON_GAP = 4f
        const val PAD = 5f
        const val TEXT_SIZE = 11f
        const val ARM_TWEEN_SECONDS = 0.15f
    }
}
