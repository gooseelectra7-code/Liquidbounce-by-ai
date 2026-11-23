/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.render

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimPipeline
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimState

/**
 * The two radii the overlay draws as ground rings: how far an anchor still hurts the VICTIM enough to be worth
 * placing, and how close one may be placed before it hurts US more than we allow.
 *
 * This is the module's most useful diagnostic and the reason the render exists at all. An anchor's blast is
 * centred INSIDE the block it stands in (`AnchorMath.anchorCenter` = `pos.center`), 71 raw at zero distance, so
 * at the ranges this module works at the self-damage gate is the binding constraint far more often than the
 * target one - a player watching the module refuse cell after cell has no way to tell "nothing is in range" from
 * "everything in range would take my own head off". Drawn as two rings, the answer is immediate: the module is
 * silent because the player is standing inside the red one.
 *
 * ### Why bisection is exact here, and why there are no rays
 * `AnchorMath.rawDamage` depends on the two points ONLY through their distance
 * (`calc/AnchorMath.kt:109-130`), so a probe centre placed due east of the victim's feet stands in for every
 * direction, and the whole pipeline is monotone decreasing in that distance - the raw damage falls with it and
 * every later stage of [VictimPipeline] is monotone in the raw. A bisection over `[0, DOUBLE_RADIUS]` therefore
 * converges on the true threshold rather than approximating it, at [BISECTION_STEPS] evaluations of pure
 * arithmetic and not a single raycast per frame.
 *
 * Exposure is fixed at `1f` deliberately: these rings answer "how far does the blast reach through open air",
 * which is the question a player is asking when they look at the ground. Real exposure is what the SEARCH scores
 * cells with, and the difference between the two is exactly the cover the player can see for themselves.
 *
 * Ported from `crystalaura/render/AuraTargetRenderer.killZoneRadius`, with the crystal's geometry replaced by
 * the anchor's: power 5 instead of 6, and a centre at the block's own middle instead of one block above a
 * pedestal.
 */
object AnchorDanger {

    /** Bisection steps over `[0, DOUBLE_RADIUS]`; 24 halvings resolve 10 blocks to well under a millimetre. */
    private const val BISECTION_STEPS = 24

    /**
     * Largest distance `d` at which an anchor centred `d` blocks from [state]'s feet, seeing them fully, would
     * still deal at least [minDamage] effective damage. `0f` when even a point-blank anchor fails the threshold,
     * which is the honest answer "no ring" rather than a ring of zero size.
     *
     * The shield is taken OUT of the probe (`blocking = null`). `VictimPipeline.blockedDamage` derives the arc
     * from `sourcePosition - blocking.position`, so a probe that keeps it would make the radius depend on the one
     * direction the probe happens to walk in: the ring would collapse whenever due east fell inside the shield
     * arc and stand at full size when it did not. A ring is a statement about ARMOUR, which has no direction.
     *
     * `respectIFrames = false` for the same reason: the ring says what an anchor does to this victim, not what it
     * does inside the invulnerability window the previous blast happens to have left.
     */
    fun reachRadius(state: VictimState, minDamage: Float): Float {
        if (minDamage <= 0f) return AnchorMath.DOUBLE_RADIUS
        val victim = if (state.blocking == null) state else state.copy(blocking = null)
        if (damageAt(victim, 0.0) < minDamage) return 0f

        var low = 0.0
        var high = AnchorMath.DOUBLE_RADIUS.toDouble()
        repeat(BISECTION_STEPS) {
            val middle = (low + high) / 2.0
            if (damageAt(victim, middle) >= minDamage) {
                low = middle
            } else {
                high = middle
            }
        }
        return low.toFloat()
    }

    /** Effective damage of a fully exposed anchor whose centre sits [distance] blocks due east of [victim]. */
    private fun damageAt(victim: VictimState, distance: Double): Float {
        val center = victim.feet.add(distance, 0.0, 0.0)
        return VictimPipeline.apply(
            victim = victim,
            raw = AnchorMath.rawDamage(victim.feet, center, 1f),
            exposure = 1f,
            sourcePosition = center,
            respectIFrames = false,
        ).effective
    }

    /**
     * Ratio of [damage] to everything [state] has left, clamped to `0..1`. Drives the cold -> hot walk of the
     * target ring: 0 is a scratch, 1 is a cell that empties the victim on its own.
     */
    fun heatRatio(state: VictimState?, damage: Float): Float {
        val pool = (state?.health ?: 0f) + (state?.absorption ?: 0f)
        if (pool <= 0f) return 0f
        return (damage / pool).coerceIn(0f, 1f)
    }
}
