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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints

import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map.WorldMapStore
import org.lwjgl.glfw.GLFW

/**
 * WorldMap: the module's own bind (M by default) opens the [WorldMapScreen], the way ClickGUI's
 * opens the ClickGUI - `disableActivation`, so the module never stays "on" and the key works
 * whatever else is enabled. Clicking it in the ClickGUI opens the map too.
 *
 * [collect] is the capture switch for the whole feature and is ON by default: the map is meant to
 * hold every chunk the client has ever loaded, and a chunk that was not captured while it was loaded
 * cannot be recovered later. It is independent of the HUD minimap on purpose - an empty map because
 * the minimap happened to be off was the first playtest report (2026-09-27).
 */
object ModuleWorldMap : ClientModule(
    "WorldMap",
    ModuleCategories.RENDER,
    bind = GLFW.GLFW_KEY_M,
    disableActivation = true,
) {

    private val collect by boolean("Collect", true)

    init {
        // Polled by the store every tick; touching the store here also constructs it at start-up,
        // which registers its event handlers.
        WorldMapStore.collectWhile { collect }
    }

    override fun onEnabled() {
        // Not from ModuleManager's first-join replay (it runs inside AutoConfig.withLoading).
        if (!AutoConfig.loadingNow) {
            openMap()
        }
        super.onEnabled()
    }

    /**
     * In a world this module can only be switched OFF if its state was left on by a toggle made
     * outside one (`ClientModule.onToggled` returns the raw state when not in game, and
     * `disableActivation` only applies after that check) - treat it as a press, or the first press
     * of the key would do nothing. Not during a config load or the Unhook sweep (`loadingNow`).
     */
    override fun onDisabled() {
        // Nor during self-destruct, which disables every module outside a config load.
        if (!AutoConfig.loadingNow && !HideAppearance.isDestructed) {
            openMap()
        }
        super.onDisabled()
    }

    private fun openMap() {
        // Deferred: the module bind is dispatched from KeyboardKeyEvent, which fires before
        // KeyboardHandler.keyPress reads `minecraft.screen` (MixinKeyboardHandler, GETFIELD ordinal
        // 0). A screen set now would receive this same press, and this key closes the map.
        mc.schedule {
            // Only over nothing or the ClickGUI (clicked there): never replace a screen the server
            // opened in the frame between the press and this task - its container would stay open.
            val screen = mc.screen
            if (mc.level != null && (screen == null || ModuleClickGui.isClickGuiScreen(screen))) {
                mc.setScreen(WorldMapScreen())
            }
        }
    }
}
