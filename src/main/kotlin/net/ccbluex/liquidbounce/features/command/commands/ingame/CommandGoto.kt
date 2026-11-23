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
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavService
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavStatus
import net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalBlock
import net.minecraft.core.BlockPos

/**
 * Walk somewhere.
 *
 * ```
 * .goto             to the block under the crosshair
 * .goto <x> <y> <z>
 * ```
 *
 * The same engine as `.nav goto`, on purpose: this is the short name people already have in their
 * fingers, not a second way of doing it. Everything beyond going somewhere — stopping, diagnosing,
 * probing a position — lives under `.nav`.
 */
object CommandGoto : Command.Factory {

    override fun createCommand(): Command =
        CommandBuilder
            .begin("goto")
            .alias("path")
            .requiresIngame()
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
                        chat(markAsError("Usage: .goto [x y z]"), command)
                        return@handler
                    }
                }

                val target = NavService.resolveStandingSpot(aimed)
                if (target != aimed) {
                    chat(
                        "§b[Nav] §7Nothing to stand on at §f${aimed.x} ${aimed.y} ${aimed.z}§7, " +
                            "using §f${target.y}§7.",
                    )
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
                    // Re-read before every search: a long journey outlives the inventory it was
                    // planned against, and a router still counting spent rockets plans a take-off
                    // with nothing to light.
                    capsProvider = { ModuleNav.buildCaps() },
                    onArrive = { chat("§b[Nav] §aArrived.") },
                    onFailed = { status -> chat(describeFailure(status, target)) },
                )
                chat("§b[Nav] §7Heading to §f${target.x} ${target.y} ${target.z}§7.")
            }
            .build()

    /**
     * Says which kind of "no" it was.
     *
     * A search that cannot reach its goal has to exhaust everything reachable before it can say so,
     * and that looks identical whether the place is walled off or simply not somewhere a body fits.
     */
    private fun describeFailure(status: NavStatus, target: BlockPos): String {
        if (status != NavStatus.UNREACHABLE) return "§b[Nav] §cNavigation failed."

        val verdict = NavService.describeOccupancy(target)
        return if (verdict == "standable") {
            "§b[Nav] §cNo route to §f${target.x} ${target.y} ${target.z}§c — it is walled off."
        } else {
            "§b[Nav] §cCannot stand at §f${target.x} ${target.y} ${target.z}§c: $verdict"
        }
    }

    private fun coordinate(name: String) = ParameterBuilder
        .begin<Int>(name)
        .verifiedBy(ParameterBuilder.INTEGER_VALIDATOR)
        .optional()
        .build()
}
