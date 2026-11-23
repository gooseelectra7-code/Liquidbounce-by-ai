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
package net.ccbluex.liquidbounce.features.module.modules.player

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.text.stripMinecraftColorCodes
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW

/**
 * FastSearch module
 *
 * On a hotkey press, sends a server search command (e.g. auction house search) for the item
 * currently held in hand — the command template's `{name}` placeholder is replaced with the
 * item's display name.
 *
 * Ported from SystemDLC (`system-source — копия`,
 * `src/main/java/client/module/player/FastSearch.java`). The original polled its hotkey
 * edge-triggered each tick, only in game with no screen open and the module enabled; on
 * press (rate-limited to one send per 250 ms) it took the main- or off-hand stack, stripped
 * color codes from its display name, optionally cut leading square-bracket prefixes like
 * "[Rare]", substituted the name into the command template (appending it if the template
 * has no `{name}`), stripped one leading slash and sent the result as a chat command.
 * Setting names translated from the Russian originals ("Команда" -> Command,
 * "Второй руки" -> OffHand, "Убирать скобки" -> StripBrackets,
 * "Клавиша поиска" -> SearchKey); defaults and the 250 ms debounce kept exactly.
 *
 * Grim-relevant decisions: the only packet emitted is one chat command
 * (`ClientPacketListener.sendCommand`) on a key press — no movement, rotation or
 * interaction packets, so no Grim check surface.
 */
object ModuleFastSearch : ClientModule("FastSearch", ModuleCategories.PLAYER) {

    private const val DEFAULT_TEMPLATE = "ah search {name}"
    private const val USE_COOLDOWN_MS = 250L

    /** Original: "Команда" — command template, `{name}` is replaced with the item name. */
    private val command by text("Command", DEFAULT_TEMPLATE)

    /** Original: "Второй руки" — take the item from the off hand instead of the main hand. */
    private val offHand by boolean("OffHand", false)

    /** Original: "Убирать скобки" — cut square-bracket prefixes like "[Rare]" (default true). */
    private val stripBrackets by boolean("StripBrackets", true)

    /** Original: "Клавиша поиска" — press to send the search command for the held item. */
    private val searchKey by key("SearchKey")

    private var lastUse = 0L

    // The original hotkey supported both keyboard keys and mouse buttons (GLFW poll);
    // in this repo those arrive as two separate events (KeyEvent is keyboard-only).
    @Suppress("unused")
    private val keyHandler = handler<KeyEvent> { event ->
        onSearchKey(event.key, event.action)
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        onSearchKey(event.key, event.action)
    }

    private fun onSearchKey(key: InputConstants.Key, action: Int) {
        // The original polled only while mc.currentScreen == null and in game (the module's
        // `running` state already guarantees in-game here); unbound keys never fire.
        if (action != GLFW.GLFW_PRESS || key == InputConstants.UNKNOWN ||
            key != searchKey || mc.screen != null) {
            return
        }

        // 250 ms debounce; the timestamp is taken before the item checks, like the original.
        val now = System.currentTimeMillis()
        if (now - lastUse < USE_COOLDOWN_MS) {
            return
        }
        lastUse = now

        val stack = if (offHand) player.offhandItem else player.mainHandItem
        if (stack.isEmpty) {
            return
        }

        val itemName = itemName(stack)
        if (itemName.isEmpty()) {
            return
        }

        val template = command.ifEmpty { DEFAULT_TEMPLATE }
        var search = if (template.contains("{name}")) {
            template.replace("{name}", itemName)
        } else {
            "$template $itemName"
        }
        search = search.trim()
        if (search.startsWith("/")) {
            search = search.substring(1)
        }

        network.sendCommand(search)
    }

    /**
     * Display name of the stack with color codes stripped and, when [stripBrackets] is on,
     * every leading `[...]` prefix removed (unterminated brackets are left alone) — a 1:1
     * port of the original `getStringByItemStack`.
     */
    private fun itemName(stack: ItemStack): String {
        var name = stack.hoverName.string.stripMinecraftColorCodes().trim()

        if (stripBrackets) {
            while (name.startsWith("[")) {
                val close = name.indexOf(']')
                if (close < 0) {
                    break
                }

                name = name.substring(close + 1).trim()
            }
        }

        return name
    }
}
