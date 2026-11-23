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
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GrimGlide module
 *
 * Elytra flight without fireworks (Grim bypass). Ported from the Nuclear client
 * (1.16.5 `GrimGlide`); the motion math is kept 1:1, the prediction-sync part is
 * simplified to LiquidBounce's KillAura target and may need further tuning.
 *
 * @author Nuclear (original), ported to LiquidBounce
 */
@Suppress("MagicNumber")
object ModuleGrimGlide : ClientModule("GrimGlide", ModuleCategories.MOVEMENT) {

    private val timer = Chronometer()
    private val syncWithPredict by boolean("SyncWithPredict", false)
    private val predictTicks by int("PredictTicks", 3, 0..20)

    @Suppress("unused")
    private val moveHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        if (event.state != EventState.PRE || !player.isFallFlying) {
            return@handler
        }

        val pos = player.position()
        val yaw = getSyncedYaw()

        var speedBase = 0.087
        val currentMotion = getBps(player)
        val speedLimit = 100.0

        if (currentMotion >= speedLimit) {
            speedBase = 0.0
        }

        val dx = -sin(Math.toRadians(yaw.toDouble())) * speedBase
        val dz = cos(Math.toRadians(yaw.toDouble())) * speedBase

        val randomMultiplier = randomFloat(1.1f, 1.21f)
        player.deltaMovement = Vec3(
            dx * randomMultiplier,
            player.deltaMovement.y - 0.02,
            dz * randomMultiplier
        )

        if (timer.hasAtLeastElapsed(50L)) {
            player.setPos(pos.x + dx, pos.y, pos.z + dz)
            timer.reset()
        }

        player.deltaMovement = Vec3(
            dx * randomMultiplier,
            player.deltaMovement.y + 0.016,
            dz * randomMultiplier
        )

        event.x += dx
        event.z += dz
    }

    /**
     * Blocks-per-second of the player's horizontal velocity (the Nuclear `MoveUtil.getBps`).
     */
    private fun getBps(p: Player): Double {
        val motion = p.deltaMovement
        return sqrt(motion.x * motion.x + motion.z * motion.z) * 20.0
    }

    private fun getSyncedYaw(): Float {
        val yaw = player.yRot

        if (!syncWithPredict || !ModuleKillAura.running) {
            return yaw
        }

        val target = ModuleKillAura.targetTracker.target ?: return yaw
        if (!target.isFallFlying) {
            return yaw
        }

        val predictedTargetPos = getPredictedTargetPos(target)
        val direction = predictedTargetPos.subtract(player.getEyePosition(1.0f))
        return Mth.wrapDegrees(Math.toDegrees(atan2(direction.z, direction.x)) - 90.0).toFloat()
    }

    /**
     * Simplified linear extrapolation of the target's position. The original relied on
     * Nuclear's elytra-prediction subsystem (`elytraPredict`/`PredictUtility`); adapt the
     * tick count / model here as needed.
     */
    private fun getPredictedTargetPos(target: LivingEntity): Vec3 {
        val simTicks = predictTicks.coerceAtLeast(1)
        val motion = target.deltaMovement
        val base = target.position().add(motion.scale(simTicks.toDouble()))
        val yAdd = Mth.clamp(target.y - target.bbHeight, 0.0, target.bbHeight / 2.0)
        return base.add(0.0, yAdd, 0.0)
    }

    private fun randomFloat(min: Float, max: Float): Float =
        min + (Math.random() * (max - min)).toFloat()

}
