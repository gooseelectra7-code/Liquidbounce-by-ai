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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget

import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/**
 * Who to chase.
 *
 * [validate] deliberately does not call the base implementation, because two of its checks are wrong
 * for an aircraft:
 *
 * - the field-of-view test would reject anyone not already near the crosshair, and the entire point
 *   of a chase is to go after someone who is behind us;
 * - the hurt-time test would drop a target mid-flash, handing the wing a new heading in the middle
 *   of a pass for no reason other than that the last hit landed.
 *
 * Range is generous by default. A glider crosses a hundred blocks in a few seconds, so a reach-sized
 * search radius would mean the target is picked up only once it is already too late to turn.
 */
object ChaseTargetTracker : TargetTracker(TargetPriority.DISTANCE) {

    private val range by float("Range", 64f, 8f..160f, "blocks")
    private val playersOnly by boolean("PlayersOnly", true)

    /**
     * Whether to go after opponents who are standing on the ground.
     *
     * Worth turning off if the ground is somewhere you would rather not be flown at: chasing a
     * grounded target means diving, and a dive is the one manoeuvre where the feasibility filter has
     * to do real work to keep us out of the terrain.
     */
    val groundTargets by boolean("GroundTargets", true)

    override fun validate(entity: LivingEntity): Boolean =
        entity !== player &&
            !entity.isRemoved &&
            entity.shouldBeAttacked() &&
            (!playersOnly || entity is Player) &&
            (groundTargets || !entity.onGround()) &&
            entity.squaredBoxedDistanceTo(player) <= range.sq()
}
