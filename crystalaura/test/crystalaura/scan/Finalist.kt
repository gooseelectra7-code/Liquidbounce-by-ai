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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.TargetEntry
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes

/**
 * One constructed finalist of an [ExplosionRanker] ranking: [key] names it in the assertions, [effective] is the
 * target damage the ranking is ordered by, [distSq] the eye-distance tie-break and [self] the self damage the walk
 * is to find at its explosion centre.
 *
 * This file holds the fixtures every ranking test builds on: the finalist list, the victim snapshots and the target
 * entry a [Bound] needs.
 */
internal class Finalist(val key: String, val effective: Float, val distSq: Double, val self: Float)

/**
 * [cells] as the finalist list [rankFinalists] expects: sorted by `ExplosionRanker.BY_TARGET` (`damage.effective`
 * descending, then distance ascending), which is what makes a tie group contiguous.
 *
 * The explosion centre of entry `i` is `(i, 0, 0)`, so a self-damage lookup can recover the cell from the centre
 * alone - the walk only ever hands the centre to the self-damage function. [ub] is irrelevant past `refine()` and
 * is set to [Finalist.effective].
 */
internal fun finalists(cells: List<Finalist>, target: TargetEntry): List<Scored<String>> =
    cells.sortedWith(compareByDescending<Finalist> { it.effective }.thenBy { it.distSq })
        .mapIndexed { index, cell ->
            val bound = Bound(
                key = cell.key,
                center = Vec3(index.toDouble(), 0.0, 0.0),
                distSq = cell.distSq,
                target = target,
                ub = cell.effective,
            )
            Scored(bound, result(cell.effective))
        }

/** A [DamageResult] with the given [DamageResult.effective] (`afterMagic`) and nothing else set. */
internal fun result(effective: Float): DamageResult = DamageResult.ZERO.copy(afterMagic = effective)

/** A shape source without a single occluder: every exposure ray passes, so `seenPercent` is `1f`. */
internal fun emptyShapes(): ShapeSource = ShapeSource { _, _, _ -> Shapes.empty() }

/** A 20-health player victim with no armour, no effects, no shield and no i-frames, standing at [feet]. */
internal fun victim(feet: Vec3, health: Float = 20f): VictimState = VictimState(
    feet = feet,
    box = AABB(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3),
    isPlayer = true,
    skip = false,
    armor = 0,
    toughness = 0f,
    resistanceAmplifier = -1,
    protectionPoints = 0f,
    absorption = 0f,
    health = health,
    blocking = null,
    invulnerableTime = 0,
    lastHurt = null,
    difficulty = Difficulty.NORMAL,
)

/**
 * A [TargetEntry] carrying [state] for both snapshots.
 *
 * [TargetEntry.entity] is a bare [ArmorStand] with no level. Nothing in [ExplosionRanker] or [rankFinalists] ever
 * reads it - the entity only matters to the callers that turn a ranking into a `PlaceCandidate` / `BreakCandidate` -
 * and a real level would mean a real world for a value that is never touched. `Entity`'s constructor stores the
 * level, reads `EntityType.getDimensions()` and calls `setPos(0, 0, 0)`, which is a no-op at the position it already
 * has, so a null level never reaches a dereference (`net/minecraft/world/entity/Entity.java:315-335`).
 */
internal fun target(state: VictimState): TargetEntry =
    TargetEntry(ArmorStand(EntityType.ARMOR_STAND, noLevel()), state, state, emptyShapes())

/** `null` typed as anything: the cast is erased, so no runtime check rejects it (see [target]). */
@Suppress("UNCHECKED_CAST")
private fun <T> noLevel(): T = null as T
