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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.row

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

/**
 * A TEXT setting row for the ported SystemDLC ClickGUI, restyled from the proven native picker's text
 * field in
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.drawTextField]
 * (LeetClickGuiScreen.kt:2320-2351) and its edit lifecycle (`beginEdit` :2379-2388, `commitEdit`
 * :2407-2429, `cancelEdit` :2431-2435, the click-away commit :3658-3660, the key handling :3790-3798 and
 * `charTyped` :3839-3842).
 *
 * ## Payload dispatch (NEVER blind-cast a TEXT [Value] to `Value<String>`)
 * A `ValueType.TEXT` [Value] can hold a `String`, a single `Regex`, or a `MutableList` of either
 * (ValueType.kt:39; the Leet field dispatches on the runtime payload for exactly this reason —
 * `fieldTextOf` LeetClickGuiScreen.kt:2354-2358, `commitEdit` :2421-2425). This row inspects
 * `value.get()` ONCE in [kind]:
 *  - `String`  -> editable field, read via `get()` / written via `(value as Value<String>).set(text)`.
 *  - `Regex`   -> editable field, read via `get().pattern` / written via `(value as Value<Regex>)
 *    .set(Regex(text))`, rejecting an invalid pattern (`Regex(...)` throws
 *    `PatternSyntaxException`) and keeping the old value with a red-border cue.
 *  - `List<*>` (a string/regex list) -> a READ-ONLY `N items` badge; list editing is a later
 *    list-picker cluster, not this row.
 *  - anything else -> a read-only value readout (defensive; `SettingRow.create` only routes real TEXT
 *    values here).
 *
 * ## Buffer, caret and commit
 * The live text is edited in [buffer] (separate from the [Value] until commit — the Leet `editBuffer`,
 * LeetClickGuiScreen.kt:421). [caret] is a real insertion index (this row supports Left/Right/Home/End and
 * mid-string Backspace/Delete, a superset of the Leet field which only appends/`dropLast` at the end,
 * :3793/:3840). A commit applies [buffer] to the [Value] (skipping the write when unchanged so
 * listeners/autosave do not fire for nothing, mirroring LeetClickGuiScreen.kt:2421-2425); Enter commits,
 * ESC cancels back to the committed value, and losing focus commits. Focusing seeds [buffer] from the
 * committed value and puts the caret at the end (Leet `beginEdit`, :2384).
 *
 * ## Keyboard focus routing (the mechanism)
 * There is exactly ONE focused text field client-wide, tracked in the companion [active] (the Leet single
 * `focusedField`, LeetClickGuiScreen.kt:419). [SettingRow] carries no pointer capture for text (the field
 * has no drag), so [capturing] stays false; instead the host forwards `keyPressed` / `charTyped` down to
 * every row and only the focused field consumes them (returns true), exactly the way the mouse dispatch
 * already offers a click to every row (ModuleRow.kt:299-309). See the port report for the base-class and
 * host forwarding this requires — the SystemDLC row stack does not route keys to rows yet.
 *
 * Refocusing a different field commits the previously [active] one, which covers field-to-field
 * click-away. Universal click-away (clicking an empty area or a non-text widget) needs the host to commit
 * the [active] field at the top of its click dispatch, mirroring `commitEdit()` at
 * LeetClickGuiScreen.kt:3660 — described for central apply in the port report; Enter/ESC always work
 * regardless.
 */
class TextRow(value: Value<*>) : SettingRow(value) {

    private enum class Kind { STRING, REGEX, LIST, OTHER }

    /** The payload family, resolved once from the current value (stable for a TEXT value's lifetime). */
    private val kind: Kind = when (value.get()) {
        is List<*> -> Kind.LIST
        is Regex -> Kind.REGEX
        is String -> Kind.STRING
        else -> Kind.OTHER
    }

    private val editable: Boolean get() = kind == Kind.STRING || kind == Kind.REGEX

    /** The edit buffer while [focused]; ignored otherwise (the [Value] is the source of truth). */
    private var buffer: String = ""

    /** Insertion caret index into [buffer], `0..buffer.length`. */
    private var caret: Int = 0

    /** Whether THIS field is the focused one (companion [active] === this). */
    private var focused: Boolean = false

    /** Horizontal text scroll (px) keeping the caret inside the field for long strings. */
    private var scrollPx: Float = 0f

    // No pointer-drag capture for a text field; keyboard focus is routed by row iteration, not capturing.
    override val capturing: Boolean get() = false

    override fun measure(width: Float): Float = if (editable) {
        SystemDraw.lineHeight(LABEL_SIZE) + LABEL_FIELD_GAP + FIELD_H
    } else {
        maxOf(READONLY_H, SystemDraw.lineHeight(LABEL_SIZE))
    }

    // ------------------------------------------------------------------ geometry

    private fun fieldTop(): Float = rowY + SystemDraw.lineHeight(LABEL_SIZE) + LABEL_FIELD_GAP

    // ------------------------------------------------------------------ draw

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        if (!editable) {
            drawReadOnly(ctx, alpha)
            return
        }

        // label on the top line
        SystemDraw.text(
            ctx, value.name, rowX, rowY, LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )

        val fieldY = fieldTop()
        val valid = bufferValid()
        val borderCol = when {
            focused && !valid -> SystemDlcTheme.danger()
            focused -> SystemDlcTheme.primary()
            else -> SystemDlcTheme.border()
        }
        SystemDraw.panel(
            ctx, rowX, fieldY, rowWidth, FIELD_H, radius = FIELD_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.elevated(), alpha),
            border = SystemDraw.withAlpha(borderCol, alpha), borderWidth = 1f,
        )

        val innerX = rowX + FIELD_PAD_X
        val innerW = (rowWidth - 2f * FIELD_PAD_X).coerceAtLeast(1f)
        val display = if (focused) buffer else committedString()
        val textH = SystemDraw.lineHeight(FIELD_SIZE)
        val textY = fieldY + (FIELD_H - textH) / 2f

        updateScroll(innerW)

        // clip text + caret to the field interior so long strings do not bleed past the border
        SystemDraw.scissor(ctx, rowX + 1f, fieldY + 1f, rowWidth - 2f, FIELD_H - 2f) {
            val textColor = if (display.isEmpty() && !focused) {
                SystemDlcTheme.mutedFg()
            } else {
                SystemDlcTheme.foreground()
            }
            if (display.isNotEmpty()) {
                SystemDraw.text(
                    ctx, display, innerX - scrollPx, textY, FIELD_SIZE,
                    SystemDraw.withAlpha(textColor, alpha), align = TextAlign.LEFT,
                )
            }
            // blinking caret bar (cadence copied from LeetClickGuiScreen.kt:2336)
            if (focused && System.currentTimeMillis() / CARET_BLINK_MS % 2 == 0L) {
                val caretPx = SystemDraw.textWidth(buffer.substring(0, caret), FIELD_SIZE)
                val caretX = innerX + caretPx - scrollPx
                SystemDraw.panel(
                    ctx, caretX, textY, CARET_W, textH, radius = 0f,
                    fill = SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha),
                )
            }
        }
    }

    private fun drawReadOnly(ctx: GuiGraphicsExtractor, alpha: Float) {
        val textY = rowY + (height - SystemDraw.lineHeight(LABEL_SIZE)) / 2f
        SystemDraw.text(
            ctx, value.name, rowX, textY, LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )
        val summary = when (kind) {
            Kind.LIST -> {
                val n = (value.get() as List<*>).size
                if (n == 1) "1 item" else "$n items"
            }
            else -> runCatching { value.get().toString() }.getOrDefault("").take(MAX_SUMMARY)
        }
        SystemDraw.text(
            ctx, summary, rowX + rowWidth, textY, LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.mutedFg(), alpha), align = TextAlign.RIGHT,
        )
    }

    /**
     * Recompute [scrollPx] so the caret stays visible in the field ([innerW] px). When the whole string
     * fits, the field is left-aligned (`scrollPx = 0`); otherwise it scrolls just enough to keep the caret
     * end on screen — the "clip from the left so the caret stays visible" rule of
     * LeetClickGuiScreen.clipFieldText (LeetClickGuiScreen.kt:2360-2377), here expressed as a scroll
     * because the caret can sit mid-string.
     */
    private fun updateScroll(innerW: Float) {
        if (!focused) {
            scrollPx = 0f
            return
        }
        val fullW = SystemDraw.textWidth(buffer, FIELD_SIZE)
        if (fullW <= innerW) {
            scrollPx = 0f
            return
        }
        val caretPx = SystemDraw.textWidth(buffer.substring(0, caret), FIELD_SIZE)
        if (caretPx - scrollPx > innerW) {
            scrollPx = caretPx - innerW
        }
        if (caretPx - scrollPx < 0f) {
            scrollPx = caretPx
        }
        scrollPx = scrollPx.coerceIn(0f, fullW - innerW)
    }

    // ------------------------------------------------------------------ mouse

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (!editable) {
            return false
        }
        val inField = Widget.rectContains(rowX, fieldTop(), rowWidth, FIELD_H, mouseX, mouseY)
        if (button == 0 && inField) {
            requestFocus()
            placeCaretAt(mouseX)
            return true
        }
        // a click landing on this row but outside the field commits and blurs (intra-row click-away)
        if (focused && !inField) {
            blur()
        }
        return false
    }

    /** Put the caret at the buffer index nearest to [mouseX] (accounting for the current scroll). */
    private fun placeCaretAt(mouseX: Float) {
        val innerX = rowX + FIELD_PAD_X
        val target = mouseX - innerX + scrollPx
        var best = 0
        var bestDist = abs(target)
        var acc = 0f
        for (i in 1..buffer.length) {
            acc = SystemDraw.textWidth(buffer.substring(0, i), FIELD_SIZE)
            val d = abs(acc - target)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        caret = best
    }

    // ------------------------------------------------------------------ keyboard
    // NOTE: these override the base SettingRow key hooks that must be added for central apply (see report).

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!focused) {
            return false
        }
        when (keyCode) {
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> if (tryApply()) {
                releaseFocus()
            }
            GLFW.GLFW_KEY_ESCAPE -> releaseFocus() // cancel: drop the buffer, keep the committed value
            GLFW.GLFW_KEY_BACKSPACE -> backspace()
            GLFW.GLFW_KEY_DELETE -> deleteForward()
            GLFW.GLFW_KEY_LEFT -> caret = (caret - 1).coerceAtLeast(0)
            GLFW.GLFW_KEY_RIGHT -> caret = (caret + 1).coerceAtMost(buffer.length)
            GLFW.GLFW_KEY_HOME -> caret = 0
            GLFW.GLFW_KEY_END -> caret = buffer.length
            else -> Unit
        }
        // consume EVERY key while focused so ESC-to-close and arrow-scroll never fire mid-edit
        return true
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean {
        if (!focused || char.isISOControl()) {
            return false
        }
        buffer = buffer.substring(0, caret) + char + buffer.substring(caret)
        caret++
        return true
    }

    private fun backspace() {
        if (caret == 0) {
            return
        }
        if (isCtrlDown()) {
            val start = wordStart(buffer, caret)
            buffer = buffer.substring(0, start) + buffer.substring(caret)
            caret = start
        } else {
            buffer = buffer.substring(0, caret - 1) + buffer.substring(caret)
            caret--
        }
    }

    private fun deleteForward() {
        if (caret < buffer.length) {
            buffer = buffer.substring(0, caret) + buffer.substring(caret + 1)
        }
    }

    // ------------------------------------------------------------------ focus + commit

    private fun requestFocus() {
        active?.let { prev ->
            if (prev !== this) {
                prev.blur()
            }
        }
        active = this
        focused = true
        buffer = committedString()
        caret = buffer.length
        scrollPx = 0f
    }

    /** Clear focus without applying the buffer (ESC cancel, or after a commit already applied). */
    private fun releaseFocus() {
        focused = false
        if (active === this) {
            active = null
        }
    }

    /** Commit (best effort) and clear focus — the losing-focus path (field switch / click-away). */
    private fun blur() {
        if (!focused) {
            return
        }
        tryApply()
        releaseFocus()
    }

    /**
     * Write [buffer] to the [Value]. Returns true when applied (or unchanged), false when a Regex pattern
     * is invalid (old value kept). The `get()`-type check keeps the cast runtime-safe, matching
     * LeetClickGuiScreen.commitEdit (LeetClickGuiScreen.kt:2421-2425).
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryApply(): Boolean {
        try {
            when (val cur = value.get()) {
                is Regex -> {
                    val rx = runCatching { Regex(buffer) }.getOrNull() ?: return false
                    if (cur.pattern != buffer) {
                        (value as Value<Regex>).set(rx)
                    }
                }
                is String -> if (cur != buffer) {
                    (value as Value<String>).set(buffer)
                }
                else -> Unit
            }
        } catch (e: Throwable) {
            logger.warn("SystemDLC TextRow: could not apply text for ${value.name}: ${e.message}")
        }
        return true
    }

    /** The committed text of the [Value] as an editable string (String -> itself, Regex -> its pattern). */
    private fun committedString(): String = when (val raw = value.get()) {
        is String -> raw
        is Regex -> raw.pattern
        else -> raw.toString()
    }

    /** True unless the current [buffer] is a Regex that will not compile (drives the red-border cue). */
    private fun bufferValid(): Boolean =
        kind != Kind.REGEX || runCatching { Regex(buffer) }.isSuccess

    private fun isCtrlDown(): Boolean {
        val window = mc.window
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
    }

    companion object {
        /** The single focused field client-wide (Leet single `focusedField`, LeetClickGuiScreen.kt:419). */
        private var active: TextRow? = null

        /**
         * Commit and blur the currently focused text field, if any. The host's universal click-away hook:
         * call this at the top of the screen's mouse-click dispatch (mirrors the unconditional
         * `commitEdit()` at LeetClickGuiScreen.kt:3660) so clicking anything outside a field commits it.
         * A no-op when nothing is focused.
         */
        fun commitActive() {
            active?.blur()
        }

        private const val LABEL_SIZE = SETTING_ROW_LABEL_SIZE
        private const val FIELD_SIZE = 11f
        private const val FIELD_H = 16f
        private const val LABEL_FIELD_GAP = 3f
        private const val FIELD_PAD_X = 5f
        private const val FIELD_RADIUS = 4f
        private const val CARET_W = 1f
        private const val CARET_BLINK_MS = 500L
        private const val READONLY_H = 16f
        private const val MAX_SUMMARY = 24

        /**
         * Windows-style Ctrl+Backspace start index: from [from], skip the trailing run of non-word chars,
         * then the word before it (so `mc.hypixel.net` deletes to `mc.hypixel.`, then `mc.`). Mirrors
         * LeetClickGuiScreen.deleteWordBackward (LeetClickGuiScreen.kt:3746-3751), caret-relative.
         */
        private fun wordStart(s: String, from: Int): Int {
            var i = from
            while (i > 0 && !s[i - 1].isLetterOrDigit()) {
                i--
            }
            while (i > 0 && s[i - 1].isLetterOrDigit()) {
                i--
            }
            return i
        }
    }
}
