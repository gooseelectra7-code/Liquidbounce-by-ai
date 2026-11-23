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
package net.ccbluex.liquidbounce.integration.screen.native.model

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.injection.mixins.minecraft.client.option.MixinServerListAccessor
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.SharedConstants
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.ServerStatusPinger
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.network.EventLoopGroupHolder
import net.minecraft.util.CommonColors
import net.minecraft.util.Util
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * The client's own copy of the saved server list, with a status pinger, as used by the native
 * multiplayer screen.
 *
 * Moved here on 2026-09-24 out of the browser's REST layer (`ServerListFunctions.kt`), which was
 * deleted with the rest of the browser integration. The logic is unchanged.
 */
object ActiveServerList : EventListener {

    internal val serverList = ServerList(mc).apply { load() }

    private val serverListPinger = ServerStatusPinger()
    private val cannotConnectText = Component.translatable("multiplayer.status.cannot_connect")
        .withColor(CommonColors.RED)
    private val cannotResolveText = Component.translatable("multiplayer.status.cannot_resolve")
        .withColor(CommonColors.RED)

    private val pingTasks = mutableListOf<Future<*>>()

    private fun cancelTasks() {
        pingTasks.forEach { it.cancel(true) }
        pingTasks.clear()
        serverListPinger.removeAll()
    }

    internal fun pingThemAll() {
        cancelTasks()
        serverList.servers
            .distinctBy { it.ip } // We do not want to ping the same server multiple times
            .forEach(this::ping)
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> {
        cancelTasks()
    }

    fun ping(serverEntry: ServerData) {
        if (serverEntry.state() != ServerData.State.INITIAL) {
            return
        }

        serverEntry.setState(ServerData.State.PINGING)
        serverEntry.motd = CommonComponents.EMPTY
        serverEntry.status = CommonComponents.EMPTY

        pingTasks += CompletableFuture.runAsync({
            try {
                serverListPinger.pingServer(serverEntry, { mc.execute(serverList::save) }, {
                    serverEntry.setState(
                        if (serverEntry.protocol == SharedConstants.getCurrentVersion().protocolVersion()) {
                            ServerData.State.SUCCESSFUL
                        } else {
                            ServerData.State.INCOMPATIBLE
                        }
                    )
                }, EventLoopGroupHolder.remote(true))
            } catch (unknownHostException: UnknownHostException) {
                serverEntry.setState(ServerData.State.UNREACHABLE)
                serverEntry.motd = cannotResolveText
                logger.error("Failed to ping server ${serverEntry.name} due to ${unknownHostException.message}")
            } catch (exception: Exception) {
                serverEntry.setState(ServerData.State.UNREACHABLE)
                serverEntry.motd = cannotConnectText
                logger.error("Failed to ping server ${serverEntry.name}", exception)
            }
        }, Util.nonCriticalIoPool())
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        serverListPinger.tick()
    }

    override val running = true

}

val ServerList.servers: List<ServerData>
    get() = (this as MixinServerListAccessor).`liquid_bounce$getServerList`()

fun ServerList.getByAddress(address: String) = servers.firstOrNull { it.ip == address }
