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

import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen

/**
 * Checks for Lunar client screens
 */
private val Screen.isLunar
    get() = javaClass.name.startsWith("com.moonsworth.lunar.") && mc.level == null

/**
 * The vanilla screens the client has a native replacement for, and how to recognize them.
 *
 * This enum used to carry every route of the browser (JCEF) integration too; those went with it on
 * 2026-09-24. See [net.ccbluex.liquidbounce.integration.screen.native.NativeScreens].
 */
enum class CustomScreenType(private val recognizer: (Screen) -> Boolean) {

    TITLE({ it is TitleScreen || it.isLunar }),
    MULTIPLAYER({ it is JoinMultiplayerScreen || it is SafetyScreen }),
    SINGLEPLAYER({ it is SelectWorldScreen });

    companion object {
        @JvmStatic
        fun recognize(screen: Screen) = entries.find { it.recognizer(screen) }
    }

}
