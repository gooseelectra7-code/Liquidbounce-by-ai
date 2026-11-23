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

import net.ccbluex.liquidbounce.integration.screen.native.InterfaceMode
import net.ccbluex.liquidbounce.integration.screen.native.InterfaceSettings
import net.ccbluex.liquidbounce.integration.screen.native.MenuBackgrounds
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Menu-background policy for VANILLA screens that stay in the flow (connect, level
 * loading, options, create world, ...) — consulted by `MixinScreen.extractBackground`.
 * Lives outside the `native` package because `native` is a reserved word in Java and
 * the mixin could not import it.
 */
object NativeMenuBackground {

    /**
     * Draws the native wallpaper behind a vanilla screen when the native interface owns
     * the menus. Returns true when drawn (the vanilla background chain is cancelled).
     */
    @JvmStatic
    fun draw(ctx: GuiGraphicsExtractor, width: Int, height: Int): Boolean {
        if (InterfaceSettings.mode != InterfaceMode.NATIVE) {
            return false
        }
        MenuBackgrounds.drawLive(ctx, "bg_worlds", width, height)
        return true
    }
}
