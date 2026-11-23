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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.debug

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.FlightCommand
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.world.phys.Vec3

/**
 * What the autopilot thinks, drawn where it can be checked against what actually happens.
 *
 * Two paths, because the whole design rests on them agreeing: where the target is predicted to go,
 * and where the rolled-forward flight expects us to go. When a chase goes wrong it is almost always
 * one of those two being wrong rather than the guidance law mis-steering, and seeing both at once
 * says immediately which.
 *
 * Off by default, and there is no always-on overlay. A module that draws on the world whenever it is
 * enabled is a module that cannot be used.
 */
class ChaseDebug(module: ClientModule) : ToggleableValueGroup(module, "Debug", false) {

    private val showPaths by boolean("ShowPaths", true)
    private val logDecisions by boolean("LogDecisions", false)

    private var targetPath: List<Vec3> = emptyList()
    private var ownPath: List<Vec3> = emptyList()

    private var lastRecovered = false

    /**
     * Publishes the paths behind the command that is about to be flown. Called from the plan phase
     * with the very values the command was built from, so the drawing can never be a second guess at
     * what the module did.
     */
    fun publish(command: FlightCommand, target: List<Vec3>, own: List<Vec3>) {
        if (!running) {
            return
        }

        targetPath = target
        ownPath = own

        if (logDecisions && command.diagnostics.recovered != lastRecovered) {
            lastRecovered = command.diagnostics.recovered
            chat(
                if (command.diagnostics.recovered) {
                    "ElytraTarget: terrain ahead, flying a recovery"
                } else {
                    "ElytraTarget: path clear"
                }
            )
        }
    }

    fun clear() {
        targetPath = emptyList()
        ownPath = emptyList()
        lastRecovered = false
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!showPaths) {
            return@handler
        }

        renderEnvironmentForWorld(event.matrixStack) {
            if (targetPath.size > 1) {
                drawLineStrip(
                    argb = TARGET_COLOR.argb,
                    positions = targetPath.map { Vec3f(relativeToCamera(it)) }.toTypedArray(),
                )
            }
            if (ownPath.size > 1) {
                drawLineStrip(
                    argb = OWN_COLOR.argb,
                    positions = ownPath.map { Vec3f(relativeToCamera(it)) }.toTypedArray(),
                )
            }
        }
    }

    private companion object {
        val TARGET_COLOR = Color4b(255, 80, 80, 180)
        val OWN_COLOR = Color4b(80, 200, 255, 180)
    }
}
