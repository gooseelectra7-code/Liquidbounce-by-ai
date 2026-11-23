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
package net.ccbluex.liquidbounce.integration.screen.native

import net.ccbluex.liquidbounce.integration.screen.CustomScreenType
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.Screen

/**
 * Registry of the native Hellhit menu screens.
 *
 * [replacementFor] is consulted by `ScreenManager` when [InterfaceSettings.mode] is NATIVE —
 * a non-null result replaces the vanilla screen, null falls through to vanilla.
 *
 * Alt/Proxy managers have no vanilla counterpart and are opened directly via
 * [openAltManager]/[openProxyManager] from the native title screen.
 */
object NativeScreens {

    /**
     * The native replacement for a recognized vanilla screen, or null to let vanilla
     * render.
     */
    @Suppress("UNUSED_PARAMETER")
    fun replacementFor(type: CustomScreenType, original: Screen): Screen? = when (type) {
        CustomScreenType.TITLE -> HellhitTitleScreen()
        CustomScreenType.MULTIPLAYER -> HellhitMultiplayerScreen()
        CustomScreenType.SINGLEPLAYER -> HellhitSingleplayerScreen()
        else -> null
    }

    fun openAltManager() {
        mc.setScreen(HellhitAltManagerScreen())
    }

    fun openProxyManager() {
        mc.setScreen(HellhitProxyManagerScreen())
    }
}
