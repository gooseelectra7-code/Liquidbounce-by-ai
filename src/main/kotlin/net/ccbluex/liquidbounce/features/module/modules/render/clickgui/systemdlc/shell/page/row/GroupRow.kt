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

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.ToggleButton
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget.Widget
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.EasingPresets
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Tween
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemIcon
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A nested value-group row: the SystemDLC restyle of the native GUI's nested-group container
 * ([net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen.layoutSetting]
 * cases at LeetClickGuiScreen.kt:1660-1805, over its `layoutGroupBox` at LeetClickGuiScreen.kt:1868-1882).
 * It hosts the CHILD setting rows of a [ValueGroup], each built recursively through
 * [SettingRow.create], inside a subtle rounded group box.
 *
 * Three group flavours, all fork [ValueGroup] subtypes, share this one row:
 *  - [ToggleableValueGroup]: a header of `name + gating [ToggleButton]` (the toggle writes the group's
 *    `enabled`, ToggleableValueGroup.kt:53-54). Clicking the header (anywhere but the toggle) flips
 *    [expanded], revealing the group box; the toggle only gates the feature and does NOT expand.
 *  - plain [ValueGroup] (a `ValueType.CONFIGURABLE` folder): the same box with header `name + chevron`
 *    and no gating toggle (mirrors LeetClickGuiScreen.kt:1798-1805 `drawLabel` + `layoutGroupBox`).
 *  - [ModeValueGroup]: the mode DROPDOWN selector — a reused [DropdownRow] (which already reads
 *    `modes` / `activeMode` / `setByString`, DropdownRow.kt:242-281) — PLUS, below it, the ACTIVE mode's
 *    own child settings in a group box (mirrors LeetClickGuiScreen.kt:1660-1677). Switching modes rebuilds
 *    the child rows ([syncActiveMode]).
 *
 * ## Geometry (cited to LeetClickGuiScreen.layoutGroupBox, LeetClickGuiScreen.kt:1868-1882)
 * The box spans `rowX - [BOX_OUT] .. rowX + rowWidth + [BOX_OUT]` (source `x - 4 .. x + w + 4`) and its
 * children sit on a narrower column `rowX + [CHILD_INSET]` of width `rowWidth - 2 * [CHILD_INSET]` (source
 * `x + 4`, `w - 8`) so the children keep clear side margins at every nesting level. A child that is itself
 * a group nests another [GroupRow] (via [SettingRow.create]), and the box, column inset and paddings apply
 * again one level in. Vertical paddings are SystemDLC-tuned (the Leet GUI packs at `SET_GAP = 6`; this
 * denser SystemDLC restyle uses [BOX_PAD_V] interior padding and [CHILD_GAP] between children).
 *
 * ## Height contract
 * [measure] is ctx-independent and SETTLED (it keys off the [expanded] / active-mode BOOLEAN state, never
 * off the reveal tween), so the host card's `expandedHeight` and, crucially, its settled
 * [net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.shell.page.ModuleRow
 * .targetHeight] stay stable — the masonry assigns columns off that settled height
 * (ContentArea.kt:130-144). This matches the sibling sub-expanding rows [DropdownRow] / [ColorRow], which
 * also grow their measured height on open rather than animating it. The reveal is therefore a DRAW-ONLY
 * curtain: the box is painted at its full settled size and the children are scissored to an animating
 * bottom (`children shown = fullChildrenHeight * reveal`, mirroring LeetClickGuiScreen.kt:1696's
 * `shownH = fullH * reveal`).
 *
 * ## Input routing
 * A press is offered to the gating toggle, then the mode dropdown, then the header (expand/collapse), then
 * the child rows (once the reveal is at least half open, mirroring the ModuleRow row gate at
 * ModuleRow.kt:299). [capturing] is true while the gating toggle is scrubbed OR any child row is capturing
 * a drag, and [mouseDragged] / [mouseReleased] forward to that captor — so a slider or colour drag inside a
 * nested group keeps routing exactly the way [ModuleRow] forwards to a captured row (ModuleRow.kt:301-333).
 */
class GroupRow(value: Value<*>) : SettingRow(value) {

    private val group: ValueGroup = value as ValueGroup
    private val toggleable: ToggleableValueGroup? = value as? ToggleableValueGroup
    private val modeGroup: ModeValueGroup<*>? = value as? ModeValueGroup<*>

    /** For a [ModeValueGroup]: the reused dropdown that selects the active mode (null otherwise). */
    private val modeField: DropdownRow? = modeGroup?.let { DropdownRow(it) }

    /** For a [ToggleableValueGroup]: the gating toggle writing the group's `enabled` (null otherwise). */
    private val gate: ToggleButton? = toggleable?.let { t ->
        ToggleButton(t.enabled).apply { onToggle = { t.enabled = state } }
    }

    /** The active mode the current [childRows] were built for; a change triggers a rebuild. */
    private var builtMode: ValueGroup? = null

    /** The hosted child rows (rebuilt on a mode switch). */
    private var childRows: List<SettingRow> = buildChildRows()

    /** Collapsed by default (a plain / toggleable group); a [ModeValueGroup]'s box has no expand toggle. */
    private var expanded: Boolean = false

    private val revealTween: Tween = EasingPresets.cubicTweenBase(0f)
    private var lastReveal: Float = 0f

    // active pointer-drag capture routed on from the host: the gating toggle or a capturing child row
    private var dragToggle: ToggleButton? = null
    private var dragChild: SettingRow? = null

    override val capturing: Boolean
        get() = dragToggle != null || (dragChild?.capturing == true)

    // ---------------------------------------------------------------- child model

    /** The group whose `inner` supplies the visible children: the active mode, or the group itself. */
    private fun sourceGroup(): ValueGroup = modeGroup?.activeMode ?: group

    /**
     * The visible children of [sourceGroup] — the same filter the native GUI applies (drop the gating
     * `Enabled` boolean, the `Hidden` flag and anything marked `notAnOption`; LeetClickGuiScreen.kt:
     * 1643-1644/1650-1651, matched by ModuleRow.buildRows, ModuleRow.kt:101-105).
     */
    private fun visibleChildren(): List<Value<*>> = sourceGroup().inner.filter { child ->
        child.name != "Enabled" && child.name != "Hidden" && !child.notAnOption
    }

    private fun buildChildRows(): List<SettingRow> {
        builtMode = modeGroup?.activeMode
        return visibleChildren().map { SettingRow.create(it) }
    }

    /** Rebuild the child rows when a [ModeValueGroup]'s active mode changed under us. */
    private fun syncActiveMode() {
        val mg = modeGroup ?: return
        if (builtMode !== mg.activeMode) {
            childRows = buildChildRows()
        }
    }

    private fun hasBox(): Boolean = childRows.isNotEmpty()

    /** True when the group box is part of the layout: always for a mode with settings, else when open. */
    private fun boxVisible(): Boolean = hasBox() && (modeGroup != null || expanded)

    // ---------------------------------------------------------------- geometry

    /** Header band height: the mode dropdown's own measured height, or the static name/toggle band. */
    private fun headerHeight(width: Float): Float = modeField?.measure(width) ?: staticHeaderHeight()

    private fun staticHeaderHeight(): Float =
        maxOf(GATE_HEIGHT, SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE))

    override fun measure(width: Float): Float {
        syncActiveMode()
        val headerH = headerHeight(width)
        if (!boxVisible()) {
            return headerH
        }
        val cw = width - 2f * CHILD_INSET
        var childrenTotal = 0f
        childRows.forEachIndexed { index, row ->
            if (index > 0) {
                childrenTotal += CHILD_GAP
            }
            childrenTotal += row.measure(cw)
        }
        return headerH + HEAD_TO_BOX + BOX_PAD_V + childrenTotal + BOX_PAD_V
    }

    /** Position the header widgets from the current place() and return the header band height. */
    private fun placeHeader(): Float {
        val mf = modeField
        if (mf != null) {
            mf.place(rowX, rowY, rowWidth)
            return mf.height
        }
        val h = staticHeaderHeight()
        val g = gate
        if (g != null) {
            g.placeAt(rowX + rowWidth - g.width, rowY + (h - g.height) / 2f)
        }
        return h
    }

    /** Place every child row on the narrower interior column (no drawing). */
    private fun layoutChildren(headerH: Float) {
        val cx = rowX + CHILD_INSET
        val cw = rowWidth - 2f * CHILD_INSET
        var cy = rowY + headerH + HEAD_TO_BOX + BOX_PAD_V
        childRows.forEachIndexed { index, row ->
            if (index > 0) {
                cy += CHILD_GAP
            }
            row.place(cx, cy, cw)
            cy += row.height
        }
    }

    // ---------------------------------------------------------------- draw

    override fun draw(ctx: GuiGraphicsExtractor, alpha: Float, mouseX: Float, mouseY: Float) {
        syncActiveMode()
        val headerH = placeHeader()

        val mf = modeField
        if (mf != null) {
            mf.draw(ctx, alpha, mouseX, mouseY)
        } else {
            drawStaticHeader(ctx, alpha, headerH, mouseX, mouseY)
        }

        // draw-only reveal: the tween settles toward 1 while the box is part of the layout, toward 0 once
        // it is not, so a reopen always wipes from the top again.
        revealTween.to(if (boxVisible()) 1f else 0f)
        val reveal = revealTween.get()
        lastReveal = reveal

        if (boxVisible()) {
            drawBox(ctx, alpha, headerH, reveal, mouseX, mouseY)
        }
    }

    private fun drawStaticHeader(
        ctx: GuiGraphicsExtractor,
        alpha: Float,
        headerH: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        val textY = rowY + (headerH - SystemDraw.lineHeight(SETTING_ROW_LABEL_SIZE)) / 2f
        SystemDraw.text(
            ctx, group.name, rowX, textY, SETTING_ROW_LABEL_SIZE,
            SystemDraw.withAlpha(SystemDlcTheme.foreground(), alpha), align = TextAlign.LEFT,
        )

        val g = gate
        if (g != null) {
            toggleable?.let { t ->
                if (g.state != t.enabled) {
                    g.setStateSilently(t.enabled)
                }
            }
            g.render(ctx, alpha, mouseX, mouseY)
        } else if (hasBox()) {
            // plain-group expand affordance: a chevron, brightened while open
            val cx = rowX + rowWidth - CHEVRON_SIZE
            val color = if (expanded) SystemDlcTheme.foreground() else SystemDlcTheme.mutedFg()
            SystemDraw.icon(
                ctx, SystemIcon.CHEVRON_RIGHT, cx, rowY + (headerH - CHEVRON_SIZE) / 2f, CHEVRON_SIZE,
                SystemDraw.withAlpha(color, alpha),
            )
        }
    }

    private fun drawBox(
        ctx: GuiGraphicsExtractor,
        alpha: Float,
        headerH: Float,
        reveal: Float,
        mouseX: Float,
        mouseY: Float,
    ) {
        val boxTop = rowY + headerH + HEAD_TO_BOX
        val boxBottom = rowY + height // the SETTLED measured height (see [measure])
        val boxLeft = rowX - BOX_OUT
        val boxWidth = rowWidth + 2f * BOX_OUT
        val boxHeight = boxBottom - boxTop
        if (boxHeight <= 0f) {
            return
        }

        // the box is painted at full settled size (it fills the space the host already reserved)
        SystemDraw.panel(
            ctx, boxLeft, boxTop, boxWidth, boxHeight, radius = BOX_RADIUS,
            fill = SystemDraw.withAlpha(SystemDlcTheme.surface(), alpha),
            border = SystemDraw.withAlpha(SystemDlcTheme.border(), alpha),
            borderWidth = 1f,
        )

        // children wipe in: scissor the interior to an animating bottom
        val contentTop = boxTop + BOX_PAD_V
        val contentFullHeight = (boxHeight - 2f * BOX_PAD_V).coerceAtLeast(0f)
        val shownHeight = contentFullHeight * reveal
        if (shownHeight <= 0.5f) {
            return
        }

        layoutChildren(headerH)
        SystemDraw.scissor(ctx, boxLeft, contentTop, boxWidth, shownHeight) {
            for (row in childRows) {
                row.draw(ctx, alpha, mouseX, mouseY)
            }
        }
    }

    // ---------------------------------------------------------------- input

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
        syncActiveMode()
        val headerH = placeHeader()

        // 1) gating toggle (toggleable groups)
        val g = gate
        if (g != null && g.mouseClicked(mouseX, mouseY, button)) {
            dragToggle = g
            return true
        }

        // 2) mode dropdown selector (a pick changes the active mode -> rebuild on the next measure/draw)
        val mf = modeField
        if (mf != null && mf.mouseClicked(mouseX, mouseY, button)) {
            return true
        }

        // 3) header expand/collapse (plain + toggleable, never a mode: its box is always shown)
        if (mf == null && hasBox() &&
            Widget.rectContains(rowX, rowY, rowWidth, headerH, mouseX, mouseY)
        ) {
            expanded = !expanded
            if (expanded) {
                revealTween.snap(0f) // restart the reveal from the top
            }
            return true
        }

        // 4) child rows, once the reveal is at least half open (mirrors ModuleRow.kt:299)
        if (boxVisible() && lastReveal >= HALF) {
            layoutChildren(headerH)
            for (row in childRows) {
                if (row.mouseClicked(mouseX, mouseY, button)) {
                    if (row.capturing) {
                        dragChild = row
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        dragToggle?.let { return it.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) }
        dragChild?.let { return it.mouseDragged(mouseX, mouseY, button, deltaX, deltaY) }
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int): Boolean {
        val toggle = dragToggle
        val child = dragChild
        dragToggle = null
        dragChild = null
        toggle?.let { return it.mouseReleased(mouseX, mouseY, button) }
        child?.let { return it.mouseReleased(mouseX, mouseY, button) }
        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (boxVisible()) {
            for (row in childRows) if (row.keyPressed(keyCode, scanCode, modifiers)) return true
        }
        return false
    }

    override fun charTyped(char: Char, modifiers: Int): Boolean {
        if (boxVisible()) {
            for (row in childRows) if (row.charTyped(char, modifiers)) return true
        }
        return false
    }

    private companion object {
        /** Gating-toggle track height (ToggleButton is 28x16); sets the static header band floor. */
        const val GATE_HEIGHT = 16f

        /** Box outward extension per side (source `x - 4 .. x + w + 4`, LeetClickGuiScreen.kt:1878). */
        const val BOX_OUT = 4f

        /** Child column inset per side (source `x + 4`, `w - 8`, LeetClickGuiScreen.kt:1872-1873). */
        const val CHILD_INSET = 4f

        /** Gap between the header band and the box top. */
        const val HEAD_TO_BOX = 4f

        /** Interior vertical padding of the box (top and bottom). */
        const val BOX_PAD_V = 8f

        /** Vertical gap between consecutive child rows. */
        const val CHILD_GAP = 10f

        const val BOX_RADIUS = 6f
        const val CHEVRON_SIZE = 8f

        /** Reveal fraction a child row must clear before it accepts clicks (source gate ModuleRow.kt:299). */
        const val HALF = 0.5f
    }
}
