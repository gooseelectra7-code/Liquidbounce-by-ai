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

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.NoneMode
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isDestructed
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isHidingNow
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.hud3.ModuleHud3
import net.ccbluex.liquidbounce.features.module.modules.render.hudtest.ModuleHudTest
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError

/**
 * Module HUD
 *
 * The client in-game dashboard, and the ONLY HUD module this fork has. Every HUD look is a mode of
 * [theme]; exactly one of them draws at a time, which is a property of the mode system rather than
 * something this module has to enforce.
 *
 * The looks used to be separate modules (`HellhitHud`, `Hud2`, `HudTest`, `Hud3`) that could all be
 * on at once — which is exactly what happened, and two arraylists then drew over each other. The
 * first answer was a theme dropdown that switched those modules on and off every tick; they are now
 * modes instead, so a look that is not selected has no running handler at all
 * (`EventManager.kt:349` skips a listener that is not running, and `Mode.running` is false unless
 * the mode is selected).
 *
 * - [MursultanHud] — "Mursultan"
 * - [ModuleHellhitHud] — "Hellhit"
 * - [ModuleHud2] — "Glass" (was the `Hud2` module)
 * - [ModuleHudTest] — "HudTest"
 * - [ModuleHud3] — "Hud3", the "Shard" design
 * - [NoneMode] — nothing
 *
 * The stock LiquidBounce look (native Watermark / ArrayList / TargetHUD / Minimap components) was
 * removed on 2026-09-24 at the user's request; it was the first native HUD of this fork. The
 * browser (JCEF) HUD went the same day, with the rest of the browser integration.
 *
 * Settings of the looks that used to be modules do NOT carry over from an older config: they were
 * saved under the old module names, and now live under `HUD -> Theme -> <mode>`.
 */

object ModuleHud : ClientModule("HUD", ModuleCategories.RENDER, state = true, hide = true) {

    /** The HUD looks this client can draw. One at a time, always. */
    val theme = modes(this, "Theme", 0) {
        arrayOf<Mode>(
            MursultanHud(it),
            ModuleHellhitHud,
            ModuleHud2,
            ModuleHudTest,
            ModuleHud3,
            NoneMode(it),
        )
    }

    override val running
        get() = this.enabled && !isDestructed
    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.hud"

    init {
        tree(Blur)
    }

    /**
     * The background blur under translucent HUD pixels ([net.ccbluex.liquidbounce.render.engine.BlurEffectRenderer]).
     * It works on whatever the GUI draws, so it is not tied to any one look.
     */
    object Blur : ToggleableValueGroup(ModuleHud, "Blur", enabled = true) {
        /**
         * The range in which the blending from not-blurred to blurred occurs.
         */
        val alphaBlendRange by floatRange("AlphaBlendRange", 0.0F..0.75F, 0.0F..1.0F)
    }

    val isBlurEffectActive
        get() = Blur.enabled && !(mc.options.hideGui && mc.screen == null)

    override fun onEnabled() {
        if (isHidingNow) {
            chat(markAsError(message("hidingAppearance")))
        }
    }

}
