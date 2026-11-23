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
package net.ccbluex.liquidbounce.integration.screen

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.HideAppearance
import net.ccbluex.liquidbounce.integration.screen.native.InterfaceMode
import net.ccbluex.liquidbounce.integration.screen.native.InterfaceSettings
import net.ccbluex.liquidbounce.integration.screen.native.NativeScreens
import net.ccbluex.liquidbounce.integration.task.TaskProgressScreen
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import org.lwjgl.glfw.GLFW

/**
 * Swaps vanilla menu screens for the client's native ones.
 *
 * This used to be the router of the browser (JCEF) integration, which could also replace a screen
 * with a web page or lay a web overlay over it. That integration was removed on 2026-09-24; what is
 * left is the native path: a recognized vanilla screen is replaced by its native counterpart when
 * [InterfaceSettings.mode] is [InterfaceMode.NATIVE], and left alone in [InterfaceMode.VANILLA].
 */
object ScreenManager : EventListener {

    internal val parent: Screen
        get() = mc.screen ?: TitleScreen()

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        // Set to default GLFW cursor
        GLFW.glfwSetCursor(mc.window.handle(), 0)

        if (handleCurrentScreen(event.screen)) {
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val screenUpdater = handler<GameTickEvent> {
        handleCurrentScreen(mc.screen)
    }

    /**
     * @return whether the screen that was about to open has been replaced (and the open must be
     * cancelled)
     */
    private fun handleCurrentScreen(screen: Screen?): Boolean {
        if (mc.screen is TaskProgressScreen || HideAppearance.isHidingNow) {
            return false
        }
        if (screen is NativeClientScreen) {
            return false
        }
        // Playing the game, nothing to replace
        if (mc.level != null && screen == null) {
            return false
        }
        if (InterfaceSettings.mode != InterfaceMode.NATIVE) {
            return false
        }

        val original = screen ?: TitleScreen()
        val type = CustomScreenType.recognize(original) ?: return false
        val replacement = NativeScreens.replacementFor(type, original) ?: return false
        mc.setScreen(replacement)
        return true
    }

    /**
     * Checks if the given screen is one of the client's own screens.
     */
    @JvmStatic
    fun isClientScreen(screen: Screen?) = screen is NativeClientScreen

}
