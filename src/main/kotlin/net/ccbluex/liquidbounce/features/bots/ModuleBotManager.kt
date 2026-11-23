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
package net.ccbluex.liquidbounce.features.bots

import net.ccbluex.liquidbounce.features.bots.gui.BotManagerScreen
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc

/**
 * BotManager module — opens the bot management screen when the user presses its bind.
 *
 * State is `disableActivation = true` (like ClickGUI) — the module is never "on" or "off"; the
 * bind / click just toggles the screen.
 */
object ModuleBotManager : ClientModule(
    name = "BotManager",
    category = ModuleCategories.MISC,
    disableActivation = true,
) {

    /**
     * Phase 1: opens the empty bot list screen. Phase 2+ reads / writes the binary path value
     * defined here.
     *
     * Note: anti-detect knobs added later must default OFF per the project rule.
     */
    @Suppress("UnusedPrivateProperty")
    private val botcraftBinaryPath by text("BotcraftBinaryPath", "")

    @Suppress("UnusedPrivateProperty")
    private val singBoxBinaryPath by text("SingBoxBinaryPath", "")

    override fun onEnabled() {
        mc.execute {
            mc.setScreen(BotManagerScreen())
        }
    }

    override fun onDisabled() {
        if (!inGame) BotManager.stopAll()
    }
}
