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

/**
 * Per-[PanelWidget] 0..1 visibility animation for a filterable panel list — ported from
 * `client/data/PanelLayout.java`. Each panel gets a [Tween] that eases toward `1` while
 * [PanelWidget.isVisible] is true and `0` while it is false, over **0.22 s easeOutCubic**
 * (source `EasingPresets.getTweenByFloatFloat2(.., 0.22)`, PanelLayout.java:27 — `getTweenByFloatFloat2`
 * = cubic tween).
 *
 * Usage per frame: call [sync] with the full panel list (creates/prunes tweens), then [advance]
 * (drives each tween toward its target). Read a panel's current visibility with [visibility].
 *
 * The `≤ 0.001` skip is the CONSUMER's responsibility, exactly as in the source: a container reads
 * [visibility] and skips a panel for both layout and hit-testing when it is `≤ 0.001` (the panel has
 * fully collapsed). This class only supplies the animated value.
 */
class PanelLayout {

    private val tweens = HashMap<PanelWidget, Tween>()

    /**
     * Ensure a tween exists for every panel in [panels] and drop tweens for panels no longer present
     * (source `onList2`, PanelLayout.java:22-32). A freshly created tween starts at the panel's
     * current visibility so it does not animate in from zero on first appearance.
     */
    fun sync(panels: List<PanelWidget>) {
        tweens.keys.retainAll(panels)
        for (panel in panels) {
            tweens.getOrPut(panel) {
                EasingPresets.cubicTween(if (panel.isVisible()) 1f else 0f, VISIBILITY_SECONDS)
            }
        }
    }

    /**
     * Advance each panel's tween toward `1`/`0` per its current [PanelWidget.isVisible] (source
     * `onList`, PanelLayout.java:12-20). Call once per frame after [sync].
     */
    fun advance(panels: List<PanelWidget>) {
        for (panel in panels) {
            val tween = tweens[panel] ?: continue
            tween.to(if (panel.isVisible()) 1f else 0f)
            tween.get()
        }
    }

    /**
     * The panel's current animated visibility `0..1` (source `getFloatByPanelWidget`,
     * PanelLayout.java:34-41). Falls back to the panel's boolean visibility if it has no tween yet.
     */
    fun visibility(panel: PanelWidget): Float {
        val tween = tweens[panel] ?: return if (panel.isVisible()) 1f else 0f
        return tween.current
    }

    companion object {
        /** Visibility tween duration in seconds (source `0.22`). */
        const val VISIBILITY_SECONDS = 0.22f
    }
}
