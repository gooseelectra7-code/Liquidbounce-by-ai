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

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.ClickGuiSearchAware
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.HellhitClickGuiScreen
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiScreen
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.MursultanClickGuiScreen
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.SystemClickGuiScreen
import net.ccbluex.liquidbounce.utils.client.inGame
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW

/**
 * ClickGUI module
 *
 * Shows you an easy-to-use menu to toggle and configure modules.
 *
 * Every renderer is native. The browser (JCEF) renderer and the settings only it read - `Scale`,
 * `Snapping`, `Cache` - were removed on 2026-09-24 with the rest of the browser integration.
 */

object ModuleClickGui :
    ClientModule("ClickGUI", ModuleCategories.RENDER, bind = GLFW.GLFW_KEY_RIGHT_SHIFT, disableActivation = true) {

    override val running get() = true

    /**
     * Which renderer to use for the ClickGUI:
     * [Renderer.NATIVE] — Leet-style single-window screen drawn with the client's own GL stack,
     * [Renderer.HELLHIT] — Hellhit-style drop-down panels,
     * [Renderer.MURSULTAN] — Mursultan-style single window (sidebar + card masonry),
     * [Renderer.SYSTEM] — the SystemDLC-style window.
     */
    @Suppress("unused")
    enum class Renderer(override val tag: String) : Tagged {
        NATIVE("Native"),
        HELLHIT("Hellhit"),
        MURSULTAN("Mursultan"),
        SYSTEM("System"),
    }

    @Suppress("unused")
    private val renderer by enumChoice("Renderer", Renderer.NATIVE).onChanged {
        // The module config is normally written only by the graceful-shutdown hook;
        // persist this one immediately so a crash or killed process cannot revert it.
        ConfigSystem.store(ModuleManager.modulesConfig)
    }

    /** Read by name from the Leet and Mursultan screens when they open. */
    @Suppress("UnusedPrivateProperty", "unused")
    private val searchBarAutoFocus by boolean("SearchBarAutoFocus", true)

    /** Whether [screen] is any of the client's ClickGUI screens. */
    fun isClickGuiScreen(screen: Screen?): Boolean =
        screen is LeetClickGuiScreen ||
            screen is HellhitClickGuiScreen ||
            screen is MursultanClickGuiScreen ||
            screen is SystemClickGuiScreen

    /** Whether a ClickGUI's search field owns the keyboard, so module keybinds must not fire. */
    val isInSearchBar: Boolean
        get() {
            val screen = mc.screen
            return screen is ClickGuiSearchAware && screen.isSearchFocused
        }

    override fun onEnabled() {
        if (!LiquidBounce.isInitialized || !inGame) {
            return
        }

        mc.execute {
            mc.setScreen(
                when (renderer) {
                    Renderer.NATIVE -> LeetClickGuiScreen()
                    Renderer.HELLHIT -> HellhitClickGuiScreen()
                    Renderer.MURSULTAN -> MursultanClickGuiScreen()
                    Renderer.SYSTEM -> SystemClickGuiScreen()
                }
            )
        }
        super.onEnabled()
    }

}
