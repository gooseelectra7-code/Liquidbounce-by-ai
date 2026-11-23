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
package net.ccbluex.liquidbounce.features.command.commands.ingame

import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.MapIdentity
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointGraphics
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WaypointStore
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.WorldMapScreen
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player

/**
 * Waypoints from chat, for the current server and dimension.
 *
 * ```
 * .waypoint add [name]              at the player's feet
 * .waypoint addat <x> <y> <z> [name]
 * .waypoint remove <name>
 * .waypoint list
 * .waypoint clear                   every waypoint of this dimension
 * .waypoint map                     open the world map
 * ```
 */
object CommandWaypoint : Command.Factory {

    private const val PREFIX = "§b[Waypoints] §7"

    override fun createCommand(): Command = CommandBuilder
        .begin("waypoint")
        .alias("wp", "waypoints")
        .requiresIngame()
        .hub()
        .subcommand(addSubcommand())
        .subcommand(addAtSubcommand())
        .subcommand(removeSubcommand())
        .subcommand(listSubcommand())
        .subcommand(clearSubcommand())
        .subcommand(mapSubcommand())
        .build()

    private fun nameParameter(required: Boolean) = ParameterBuilder
        .begin<String>("name")
        .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
        .vararg()
        .apply { if (required) required() else optional() }
        .autocompletedFrom { MapIdentity.currentDimension()?.let { WaypointStore.inDimension(it).map { w -> w.name } } }
        .build()

    private fun coordinate(name: String) = ParameterBuilder
        .begin<Int>(name)
        .verifiedBy(ParameterBuilder.INTEGER_VALIDATOR)
        .required()
        .build()

    private fun joinedName(value: Any?): String? =
        (value as? Array<*>)?.joinToString(" ") { it.toString() }?.trim()?.takeIf { it.isNotEmpty() }

    private fun addSubcommand() = CommandBuilder
        .begin("add")
        .parameter(nameParameter(required = false))
        .handler {
            val block = player.blockPosition()
            add(joinedName(args.getOrNull(0)), block.x, block.y, block.z)
        }
        .build()

    private fun addAtSubcommand() = CommandBuilder
        .begin("addat")
        .parameter(coordinate("x"))
        .parameter(coordinate("y"))
        .parameter(coordinate("z"))
        .parameter(nameParameter(required = false))
        .handler {
            add(joinedName(args.getOrNull(3)), args[0] as Int, args[1] as Int, args[2] as Int)
        }
        .build()

    private fun add(name: String?, x: Int, y: Int, z: Int) {
        val dimension = MapIdentity.currentDimension() ?: return
        val waypoint = WaypointStore.add(name ?: WaypointStore.uniqueName(), x, y, z, dimension)
        if (waypoint == null) {
            chat(markAsError("Not connected to a world."))
        } else {
            chat("${PREFIX}Added §f${waypoint.name}§7 at §f$x $y $z§7 (${MapIdentity.displayDimension(dimension)})")
        }
    }

    private fun removeSubcommand() = CommandBuilder
        .begin("remove")
        .alias("delete", "del")
        .parameter(nameParameter(required = true))
        .handler {
            val name = joinedName(args.getOrNull(0)) ?: return@handler
            val waypoint = WaypointStore.find(name, MapIdentity.currentDimension())
                ?: WaypointStore.find(name)
            if (waypoint == null) {
                chat(markAsError("No waypoint named '$name' on this server."))
            } else {
                WaypointStore.remove(waypoint)
                chat("${PREFIX}Removed §f${waypoint.name}")
            }
        }
        .build()

    private fun listSubcommand() = CommandBuilder
        .begin("list")
        .handler {
            val all = WaypointStore.all
            if (all.isEmpty()) {
                chat("${PREFIX}No waypoints on §f${MapIdentity.currentServerDisplay()}§7 yet.")
                return@handler
            }
            chat("${PREFIX}§f${all.size}§7 waypoint(s) on §f${MapIdentity.currentServerDisplay()}")
            val here = player.position()
            val current = MapIdentity.currentDimension()
            for (waypoint in all.sortedBy { it.dimension != current }) {
                val where = if (waypoint.dimension == current) {
                    WaypointGraphics.formatDistance(waypoint.distanceTo(here.x, here.y, here.z))
                } else {
                    MapIdentity.displayDimension(waypoint.dimension)
                }
                val hidden = if (waypoint.visible) "" else " §8(hidden)"
                chat("§7  §f${waypoint.name}§7  ${waypoint.x} ${waypoint.y} ${waypoint.z}  §8$where$hidden")
            }
        }
        .build()

    private fun clearSubcommand() = CommandBuilder
        .begin("clear")
        .handler {
            val dimension = MapIdentity.currentDimension() ?: return@handler
            val removed = WaypointStore.clear(dimension)
            chat("${PREFIX}Removed §f$removed§7 waypoint(s) in ${MapIdentity.displayDimension(dimension)}")
        }
        .build()

    private fun mapSubcommand() = CommandBuilder
        .begin("map")
        .handler {
            // The chat screen closes after the command runs; `schedule` always queues, unlike
            // `execute`, which would run now on the main thread and be closed right after.
            mc.schedule { mc.setScreen(WorldMapScreen()) }
        }
        .build()
}
