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

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.eventListenerScope
import net.ccbluex.liquidbounce.features.bots.ipc.BotCommand
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.File
import java.util.UUID

/**
 * Central registry for bot profiles and their runtime instances.
 *
 * Persistence: profiles are serialized to `LiquidBounce/bots.json` on every mutation;
 * runtime [BotInstance]s are NOT persisted (each session starts disconnected).
 */
@Suppress("TooManyFunctions")
object BotManager : EventListener {

    private val logger = clientLogger("BotManager")

    private val storeFile = File(ConfigSystem.rootFolder, "bots.json")

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<BotProfile>>() {}.type

    private val profiles = mutableListOf<BotProfile>()
    private val instances = mutableMapOf<String, BotInstance>()

    init {
        load()
    }

    fun profiles(): List<BotProfile> = profiles.toList()

    fun byId(id: String): BotProfile? = profiles.firstOrNull { it.id == id }

    fun byUsername(username: String): BotProfile? =
        profiles.firstOrNull { it.username.equals(username, ignoreCase = true) }

    fun instance(id: String): BotInstance? = instances[id]

    fun add(profile: BotProfile): BotProfile {
        require(byUsername(profile.username) == null) {
            "A bot with username '${profile.username}' already exists"
        }
        profiles.add(profile)
        save()
        return profile
    }

    fun remove(id: String): Boolean {
        stop(id)
        val removed = profiles.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    fun start(id: String): Boolean {
        val profile = byId(id) ?: return false
        val existing = instances[id]
        if (existing != null && existing.state.value != BotInstance.State.DISCONNECTED
            && existing.state.value != BotInstance.State.DEAD) {
            return false
        }
        val instance = existing ?: BotInstance(profile).also { instances[id] = it }
        instance.start(eventListenerScope)
        logger.info("Started bot ${profile.username} (${profile.address})")
        return true
    }

    fun stop(id: String): Boolean {
        val instance = instances[id] ?: return false
        instance.stop()
        logger.info("Stopped bot ${instance.profile.username}")
        return true
    }

    fun stopAll() {
        instances.values.forEach { it.stop() }
    }

    suspend fun send(id: String, command: BotCommand): Boolean {
        val instance = instances[id] ?: return false
        instance.send(command)
        return true
    }

    fun newProfile(
        username: String,
        server: String,
        port: Int = 25565,
        behavior: BotBehavior = BotBehavior.IDLE,
    ): BotProfile = BotProfile(
        id = UUID.randomUUID().toString(),
        username = username,
        server = server,
        port = port,
        behavior = behavior,
    )

    private fun load() {
        if (!storeFile.exists()) return
        try {
            val parsed: List<BotProfile>? = gson.fromJson(storeFile.readText(), listType)
            if (parsed != null) {
                profiles.clear()
                profiles.addAll(parsed)
            }
            logger.info("Loaded ${profiles.size} bot profile(s) from ${storeFile.name}")
        } catch (e: Exception) {
            logger.error("Failed to read bots.json — starting empty (${e.message})")
        }
    }

    private fun save() {
        try {
            storeFile.writeText(gson.toJson(profiles.toList(), listType))
        } catch (e: Exception) {
            logger.error("Failed to persist bots.json", e)
        }
    }
}
