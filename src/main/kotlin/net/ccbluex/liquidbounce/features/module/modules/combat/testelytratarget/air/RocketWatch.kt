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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air

import net.ccbluex.liquidbounce.additions.shooter
import net.ccbluex.liquidbounce.utils.client.world
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.FireworkRocketEntity

/**
 * How much push is left in a firework.
 *
 * A burning rocket is the single largest term in anyone's near-future trajectory, so a prediction
 * that ignores one is wrong by tens of blocks, and one that assumes a rocket burns forever is wrong
 * in the other direction. What is needed is the remaining lifetime, and the client cannot simply
 * read it: `FireworkRocketEntity.lifetime` is private, and it is rolled per rocket as
 * `10 * flightDuration + random(6) + random(7)` (`FireworkRocketEntity.java:68`) on whichever side
 * constructed the entity, so even a mixin accessor would be reading the client's own roll rather
 * than the server's.
 *
 * So this counts instead. Every rocket is stamped with the tick it was first seen, and the remaining
 * burn is the expected lifetime for its flight rating minus its age. The estimate is good to a few
 * ticks, which is the accuracy the guidance law needs and the best any client-side answer can be.
 */
object RocketWatch {

    /** Expected `random(6) + random(7)`, the variable part of a rocket lifetime. */
    private const val EXPECTED_JITTER = 5

    private const val TICKS_PER_FLIGHT_LEVEL = 10

    private val firstSeen = HashMap<Int, Int>()
    private var now = 0

    /**
     * Ages every known rocket by a tick and forgets the ones that are gone. Called once per tick by
     * the module, and only while it is running: an unbounded map of dead entity ids is a leak.
     */
    fun tick() {
        now++

        val level = world
        val alive = level.entitiesForRendering()
            .filterIsInstance<FireworkRocketEntity>()
            .filter { it.isAlive }

        alive.forEach { firstSeen.putIfAbsent(it.id, now) }

        if (firstSeen.isNotEmpty()) {
            val aliveIds = alive.mapTo(HashSet()) { it.id }
            firstSeen.keys.retainAll(aliveIds)
        }
    }

    fun reset() {
        firstSeen.clear()
        now = 0
    }

    /**
     * Ticks of boost [entity] can still expect from any rocket it has in the air, or zero.
     *
     * @param flightDuration the flight rating to assume. For our own rockets this is read off the
     *   stack we fired; for an opponent it has to be a guess, and the module guesses high, because
     *   under-predicting an opponent's boost aims the intercept behind them
     */
    fun remainingBoostTicks(entity: LivingEntity, flightDuration: Int): Int {
        val lifetime = TICKS_PER_FLIGHT_LEVEL * flightDuration + EXPECTED_JITTER

        return world.entitiesForRendering()
            .filterIsInstance<FireworkRocketEntity>()
            .filter { it.isAlive && it.shooter === entity }
            .maxOfOrNull { rocket ->
                val age = now - (firstSeen[rocket.id] ?: now)
                (lifetime - age).coerceAtLeast(0)
            } ?: 0
    }

    /** Whether [entity] has a rocket of its own still burning. */
    fun isBoosting(entity: LivingEntity): Boolean = world.entitiesForRendering()
        .any { it is FireworkRocketEntity && it.isAlive && it.shooter === entity }
}
