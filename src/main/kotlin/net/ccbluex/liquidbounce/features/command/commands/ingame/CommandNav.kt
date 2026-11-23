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
import net.ccbluex.liquidbounce.features.module.modules.movement.nav.ModuleNav
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavService
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavStatus
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavFireworks
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.ccbluex.liquidbounce.utils.navigation.pathing.hier.NavSectionGraph
import net.minecraft.core.BlockPos

/**
 * Drives the `pathing` engine from chat.
 *
 * ```
 * .nav goto            walk to the block under the crosshair
 * .nav goto <x> <y> <z>
 * .nav stop
 * .nav info            status and search statistics
 * .nav probe <x> <y> <z>   what the planner sees at a position
 * .nav fly <x> <y> <z>     whether a journey would be flown, and why not
 * ```
 */
object CommandNav : Command.Factory {

    override fun createCommand(): Command = CommandBuilder
        .begin("nav")
        .requiresIngame()
        .hub()
        .subcommand(gotoSubcommand())
        .subcommand(stopSubcommand())
        .subcommand(infoSubcommand())
        .subcommand(probeSubcommand())
        .subcommand(flySubcommand())
        .build()

    /**
     * Reports whether a journey would be flown, and why not when it would not.
     *
     * A refused flight is silent by design — the request falls back to walking — so from the ground
     * "it will not fly there" looks exactly like "it cannot go there". This asks the router and the
     * glide planner directly and plans nothing.
     */
    private fun flySubcommand() = CommandBuilder
        .begin("fly")
        .parameter(coordinate("x"))
        .parameter(coordinate("y"))
        .parameter(coordinate("z"))
        .handler {
            val target = when {
                args.size >= 3 -> BlockPos(args[0] as Int, args[1] as Int, args[2] as Int)
                else -> NavService.crosshairBlock() ?: run {
                    chat(markAsError("Usage: .nav fly [x y z], or look at a block"), command)
                    return@handler
                }
            }

            chat("§b[Nav] §7flight to §f${target.x} ${target.y} ${target.z}")
            for (line in NavService.explainFlight(target, ModuleNav.buildCaps())) {
                chat("§b[Nav] §7  $line")
            }
        }
        .build()

    /**
     * Reports what the planner sees at a position, and whether a body fits there.
     *
     * An unreachable goal and a goal that is merely not standable produce the same outcome from a
     * search — it has to exhaust everything reachable either way — so this asks the question
     * directly rather than leaving it to be guessed from expansion counts.
     */
    private fun probeSubcommand() = CommandBuilder
        .begin("probe")
        .parameter(coordinate("x"))
        .parameter(coordinate("y"))
        .parameter(coordinate("z"))
        .handler {
            val pos = when {
                args.size >= 3 -> BlockPos(args[0] as Int, args[1] as Int, args[2] as Int)
                else -> NavService.crosshairBlock() ?: run {
                    chat(markAsError("Usage: .nav probe [x y z], or look at a block"), command)
                    return@handler
                }
            }

            chat("§b[Nav] §7probe §f${pos.x} ${pos.y} ${pos.z}")
            chat("§b[Nav] §7  chunk §f${if (NavWorld.isLoaded(pos.x, pos.z)) "§aloaded" else "§cnot loaded"}")
            for (offset in 1 downTo -1) {
                val at = pos.above(offset)
                val label = when (offset) {
                    1 -> "head"
                    0 -> "feet"
                    else -> "below"
                }
                val packed = NavWorld.packedAt(at.x, at.y, at.z)
                chat(
                    "§b[Nav] §7  $label §f${NavBlockClass.nameOf(NavBlockClass.navClassOf(packed))}" +
                        "§7 (dig ${NavBlockClass.digBucketOf(packed)})",
                )
            }

            val verdict = NavService.describeOccupancy(pos)
            chat(
                if (verdict == "standable") "§b[Nav] §7  verdict §astandable"
                else "§b[Nav] §7  verdict §c$verdict",
            )
        }
        .build()

    private fun gotoSubcommand() = CommandBuilder
        .begin("goto")
        .alias("to")
        .parameter(coordinate("x"))
        .parameter(coordinate("y"))
        .parameter(coordinate("z"))
        .handler {
            val aimed = when {
                args.size >= 3 -> BlockPos(args[0] as Int, args[1] as Int, args[2] as Int)
                args.isEmpty() -> NavService.crosshairBlock() ?: run {
                    chat(markAsError("No block under the crosshair"), command)
                    return@handler
                }
                else -> {
                    chat(markAsError("Usage: .nav goto [x y z]"), command)
                    return@handler
                }
            }
            val target = NavService.resolveStandingSpot(aimed)
            if (target != aimed) {
                chat("§b[Nav] §7Nothing to stand on at §f${aimed.x} ${aimed.y} ${aimed.z}§7, using §f${target.y}§7.")
            }

            // The module owns the settings a route is planned under, so it has to be on.
            if (!ModuleNav.enabled) ModuleNav.enabled = true

            NavService.request(
                owner = ModuleNav,
                goal = GoalBlock(target),
                caps = ModuleNav.buildCaps(),
                rotations = ModuleNav.rotations,
                priority = Priority.NORMAL,
                heuristicWeight = ModuleNav.heuristicWeight(),
                capsProvider = { ModuleNav.buildCaps() },
                onArrive = { chat("§b[Nav] §aArrived.") },
                onFailed = { status ->
                    if (status == NavStatus.UNREACHABLE) {
                        // Say which of the two it was, rather than making it look like one thing.
                        val verdict = NavService.describeOccupancy(target)
                        if (verdict == "standable") {
                            chat("§b[Nav] §cNo route to §f${target.x} ${target.y} ${target.z}§c — it is walled off.")
                        } else {
                            chat("§b[Nav] §cCannot stand at §f${target.x} ${target.y} ${target.z}§c: $verdict")
                        }
                    } else {
                        chat("§b[Nav] §cNavigation failed.")
                    }
                },
            )
            chat("§b[Nav] §7Heading to §f${target.x} ${target.y} ${target.z}§7.")
        }
        .build()

    private fun stopSubcommand() = CommandBuilder
        .begin("stop")
        .handler {
            NavService.stop()
            chat("§b[Nav] §7Stopped.")
        }
        .build()

    private fun infoSubcommand() = CommandBuilder
        .begin("info")
        .alias("debug")
        .handler {
            chat("§b[Nav] §7status §f${NavService.status}")
            NavService.currentGoal?.let { chat("§b[Nav] §7goal §f${it.describe()}") }

            // What a route would actually be planned under, spelled out — otherwise "why won't it
            // dig" is a question that can only be answered by reading the module's settings.
            val caps = ModuleNav.buildCaps()
            fun flag(name: String, on: Boolean) = if (on) "§a$name" else "§8$name"
            chat(
                "§b[Nav] §7caps " + listOf(
                    flag("sprint", caps.canSprint),
                    flag("parkour", caps.allowParkour),
                    flag("break", caps.allowBreak),
                    flag("place", caps.allowPlace),
                    flag("swim", caps.allowSwim),
                    flag("falldmg", caps.allowFallDamage),
                    flag("elytra", caps.allowElytra),
                ).joinToString("§7, ") + "§7 · blocks §f${caps.placeableBlocks}" +
                    "§7 · rockets §f${caps.fireworks}" +
                    "§7 · break §f%.0f§7 ticks/hardness".format(caps.breakTicksPerHardness),
            )

            // A cold or half-filled cache looks identical to "unreachable" from the outside, so it
            // is worth being able to tell them apart at a glance.
            val here = player.blockPosition()
            chat(
                "§b[Nav] §7cache §f${NavWorld.loadedChunkCount}§7 chunks, " +
                    "§f%.1f§7 MB, here: %s".format(
                        NavWorld.denseBytes() / (1024.0 * 1024.0),
                        if (NavWorld.isLoaded(here.x, here.z)) "§aloaded" else "§cmissing",
                    ),
            )

            val flight = NavService.currentFlight
            if (flight != null) {
                chat(
                    "§b[Nav] §7flight §f${flight.waypoints.size}§7 points, §f%.0f§7 blocks, %s§7, %s"
                        .format(
                            flight.length,
                            if (flight.reachedGoal) "§areaches the goal" else "§epartial",
                            if (flight.enclosed) "§dsearched" else "§bopen sky",
                        ),
                )
                // Distinguishes "the flight never asked for thrust" from "it asked and got none",
                // which look identical from the ground.
                chat(
                    "§b[Nav] §7rockets §f${NavService.rocketsUsed}§7 lit, " +
                        "§f${NavFireworks.readyCount()}§7 to hand, §f${NavFireworks.count()}§7 carried",
                )
            }

            val path = NavService.currentPath
            if (path == null && flight == null) {
                chat("§b[Nav] §7no active route")
            } else if (path != null) {
                chat(
                    "§b[Nav] §7route §f${path.size}§7 nodes, cost §f%.0f§7 ticks, %s"
                        .format(path.totalCost, if (path.reachedGoal) "§areaches the goal" else "§epartial"),
                )
                chat("§b[Nav] §7moves §f${path.describeComposition()}")
            }

            NavService.lastCorridor?.let { corridor ->
                chat(
                    "§b[Nav] §7corridor §f${corridor.points.size}§7 points, §f%.0f§7 blocks · " +
                        "§f${NavSectionGraph.cachedSections}§7 pockets cached".format(corridor.length),
                )
            }

            NavService.lastSearch?.let { search ->
                chat(
                    "§b[Nav] §7search §f${search.expansions}§7 expanded, §f${search.discovered}§7 seen, " +
                        "peak open §f${search.peakOpen}§7, §f%.2f§7 ms (§f${search.outcome}§7)"
                        .format(search.elapsedNanos / 1_000_000.0),
                )
            }
        }
        .build()

    private fun coordinate(name: String) = ParameterBuilder
        .begin<Int>(name)
        .verifiedBy(ParameterBuilder.INTEGER_VALIDATOR)
        .optional()
        .build()
}
