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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.preview

import com.mojang.authlib.GameProfile
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/**
 * Shared state of the visuals preview shown beside the native ClickGUI's Render category.
 *
 * Holds the stand-in player the preview draws (a client-only [RemotePlayer] that is NEVER
 * added to the level — it exists purely as a render subject) and the list of modules the
 * quick-toggle strip exposes.
 *
 * The panel layout and drawing live in `LeetClickGuiScreen` because they need its private
 * `Theme`/font helpers; everything that does not depend on the GUI's look lives here.
 */
object VisualsPreview {

    /**
     * Names of the modules the quick-toggle strip lists: everything that draws ON players.
     * Resolved by name through [ModuleManager] so a missing or renamed module silently drops
     * out of the strip instead of breaking the GUI.
     */
    private val TOGGLE_MODULE_NAMES = listOf(
        "ESP",
        "Nametags",
        "Chams",
        "Tracers",
        "TrueSight",
        "LogoffSpot",
        "MobOwners",
    )

    /** Test equipment shown on the stand-in player (and in the nametag's equipment row). */
    private val TEST_EQUIPMENT = listOf(
        EquipmentSlot.HEAD to Items.DIAMOND_HELMET,
        EquipmentSlot.CHEST to Items.DIAMOND_CHESTPLATE,
        EquipmentSlot.LEGS to Items.IRON_LEGGINGS,
        EquipmentSlot.FEET to Items.DIAMOND_BOOTS,
        EquipmentSlot.MAINHAND to Items.NETHERITE_SWORD,
        EquipmentSlot.OFFHAND to Items.SHIELD,
    )

    private const val PREVIEW_NAME = "Player"

    private val PREVIEW_UUID: UUID = UUID.nameUUIDFromBytes("liquidbounce:visuals-preview".toByteArray())

    /**
     * The stand-in entity. Rebuilt whenever the level instance changes — a [RemotePlayer] is
     * bound to the level it was constructed with, so a cached one from a previous world would
     * carry a stale reference.
     */
    private var cachedPlayer: RemotePlayer? = null
    private var cachedLevel: ClientLevel? = null

    /** Modules of the quick-toggle strip, in display order. Empty entries are skipped. */
    val toggleModules: List<ClientModule>
        get() = TOGGLE_MODULE_NAMES.mapNotNull { name ->
            ModuleManager.find { it.name.equals(name, ignoreCase = true) }
        }

    /**
     * The player to draw in the preview, or null when there is no level to bind one to
     * (the ClickGUI can technically be open on a title screen).
     */
    fun previewPlayer(): LivingEntity? {
        val level = mc.level ?: run {
            cachedPlayer = null
            cachedLevel = null
            return null
        }
        val cached = cachedPlayer
        if (cached != null && cachedLevel === level) {
            return cached
        }
        return runCatching { createPlayer(level) }
            .onSuccess {
                cachedPlayer = it
                cachedLevel = level
            }
            .getOrNull()
    }

    private fun createPlayer(level: ClientLevel): RemotePlayer {
        val player = RemotePlayer(level, GameProfile(PREVIEW_UUID, PREVIEW_NAME))
        for ((slot, item) in TEST_EQUIPMENT) {
            player.setItemSlot(slot, ItemStack(item))
        }
        player.yRot = 0f
        player.yHeadRot = 0f
        player.yBodyRot = 0f
        player.xRot = 0f
        return player
    }

    /** Drops the cached stand-in (world change / GUI closed) so it never outlives its level. */
    fun release() {
        cachedPlayer = null
        cachedLevel = null
    }
}
