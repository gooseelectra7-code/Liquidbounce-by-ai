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
package net.ccbluex.liquidbounce.api.models.marketplace

import com.google.gson.annotations.SerializedName
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.script.ScriptManager

enum class MarketplaceItemType(
    override val tag: String,
    val isListable: Boolean,
    val isSubscribable: Boolean
) : Tagged {
    @SerializedName("Config")
    CONFIG("Config", false, false),
    @SerializedName("Script")
    SCRIPT("Script", true, true),
    /**
     * Web themes for the browser (JCEF) interface, which was removed on 2026-09-24. Kept so the
     * marketplace API's responses still deserialize, but they can no longer be listed or
     * subscribed to - there is nothing left to load them into.
     */
    @SerializedName("Theme")
    THEME("Theme", false, false),
    @SerializedName("Other")
    OTHER("Other", false, false);

    suspend fun reload() = when (this) {
        SCRIPT -> ScriptManager.reload()
        else -> { }
    }
}
