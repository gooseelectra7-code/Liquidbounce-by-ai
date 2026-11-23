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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.item.isSpear
import net.ccbluex.liquidbounce.utils.raytracing.hasLineOfSight
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Spear kill module
 *
 * Automatically attacks enemies using a charged spear.
 *
 * A spear only deals damage while it is *being used*, and only while the attacker is moving fast
 * enough along the look direction: `KineticWeapon.damageEntities` gates on the projected relative
 * speed and rolls the damage straight off it (`KineticWeapon.java:98-143`, MC 26.1.2 sources). This
 * module supplies exactly that speed — it rewrites [net.minecraft.world.entity.Entity.deltaMovement]
 * for a handful of ticks so the player flies at the target, then replays the same vectors negated so
 * the trip cancels itself out and we end up where we started.
 *
 * Port of the upstream module of the same name, adapted to this fork's render helpers and to the
 * fact that we have no `isWithinWorldBorder` extension here.
 */
object ModuleSpearKill : ClientModule("SpearKill", ModuleCategories.COMBAT, aliases = listOf("AutoSpear")) {

    private val maxTargetDistance by float("MaxTargetDistance", 50f, 3f..200f)
    private val maxAllowedSpeed by float("MaxSpeed", 7f, 2f..10f)

    private object Preview : ToggleableValueGroup(this, "Preview", true) {
        val fillColor by color("FillColor", Color4b.RED.alpha(67))
        val outlineColor by color("OutlineColor", Color4b.WHITE.alpha(167))
    }

    init {
        tree(Preview)
    }

    /**
     * Movement queue of the attack in flight: first the ticks that carry us into the target, then
     * the same vectors negated, then a zero to settle. Empty means no attack is running.
     */
    private val attackMovements = ArrayDeque<Vec3>()
    private var previewTarget: LivingEntity? = null

    private val isUsingSpear get() = player.isUsingItem && player.useItem.isSpear
    private val holdingSpear get() = player.mainHandItem.isSpear || player.offhandItem.isSpear

    private fun resetAttack() {
        previewTarget = null
        if (attackMovements.isNotEmpty()) {
            player.deltaMovement = Vec3.ZERO
        }
        attackMovements.clear()
    }

    /**
     * Closest living entity under the crosshair that we could actually reach, paired with the
     * distance the flight has to cover.
     */
    private fun findTarget(): Pair<LivingEntity, Double>? {
        val eye = player.eyePosition
        val lookEnd = eye.add(player.lookAngle.scale(maxTargetDistance.toDouble()))
        var best: Pair<LivingEntity, Double>? = null
        var bestDist = Double.MAX_VALUE

        val candidates = world.getEntitiesOfClass(
            LivingEntity::class.java,
            player.boundingBox.expandTowards(lookEnd.subtract(eye)).inflate(1.0)
        ) {
            it !== player && it.isAlive && world.worldBorder.isWithinBounds(it.blockPosition()) &&
                it.boundingBox.clip(eye, lookEnd).isPresent
        }

        for (entity in candidates) {
            val distSq = player.distanceToSqr(entity)
            if (distSq >= bestDist) {
                continue
            }

            val dist = sqrt(distSq)
            if (dist !in 3f..maxTargetDistance || !hasLineOfSight(eye, entity.boundingBox.center)) {
                continue
            }

            // The flight deliberately stops short of the target: the spear lands from its own reach
            // band, so we only cover the distance the last tick of travel does not.
            val ticks = ceil(dist / maxAllowedSpeed - 0.5).toInt().coerceAtLeast(1)
            val travel = 2.0 * dist * ticks / (2.0 * ticks + 1)

            // Terrain in the way makes the whole trip pointless - we would ram a wall instead.
            val hit = traceFromPlayer(range = travel, block = ClipContext.Block.COLLIDER)
            if (hit.type == HitResult.Type.MISS || hit.location.distanceTo(eye) >= travel) {
                best = entity to travel
                bestDist = distSq
            }
        }

        return best
    }

    private fun createAttackMovement(target: LivingEntity, distance: Double) {
        val ticks = ceil(distance / maxAllowedSpeed).toInt().coerceAtLeast(1)
        val velocity = distance / ticks

        // Aim at where the target will be once we arrive, not at where it stands right now.
        val direction = PositionExtrapolation.getBestForEntity(target)
            .getPositionInTicks(ticks.toDouble())
            .subtract(player.eyePosition)
            .normalize()
            .takeIf { it.lengthSqr() > 0 } ?: player.lookAngle.normalize()

        val movement = direction.scale(velocity)
        val reverse = movement.scale(-1.0)

        repeat(ticks) { attackMovements += movement }
        repeat(ticks) { attackMovements += reverse }
        attackMovements += Vec3.ZERO
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!holdingSpear || !isUsingSpear) {
            resetAttack()
            return@handler
        }

        val shouldFindTarget = Preview.enabled || (attackMovements.isEmpty() && mc.options.keyAttack.isDown)
        val target = if (shouldFindTarget) findTarget() else null
        previewTarget = target?.first

        val kineticWeapon = player.useItem.get(DataComponents.KINETIC_WEAPON) ?: run {
            resetAttack()
            return@handler
        }
        val chargeDuration = kineticWeapon.computeDamageUseDuration() - kineticWeapon.delayTicks()

        // Still in the windup - the spear cannot damage anything yet, so do not spend the flight.
        if (player.ticksUsingItem <= kineticWeapon.delayTicks()) {
            attackMovements.clear()
            return@handler
        }

        if (attackMovements.isEmpty()) {
            val (entity, dist) = target ?: return@handler
            if (player.ticksUsingItem >= chargeDuration || !mc.options.keyAttack.isDown) {
                return@handler
            }
            createAttackMovement(entity, dist)
        } else {
            player.deltaMovement = attackMovements.removeFirst()
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!Preview.enabled || !isUsingSpear) {
            return@handler
        }

        val target = previewTarget ?: return@handler

        renderEnvironmentForWorld(event.matrixStack) {
            withPositionRelativeToCamera {
                if (target is EnderDragon) {
                    target.subEntities.forEach { drawBox(it.boundingBox, Preview.fillColor, Preview.outlineColor) }
                } else {
                    drawBox(target.boundingBox, Preview.fillColor, Preview.outlineColor)
                }
            }
        }
    }

}
