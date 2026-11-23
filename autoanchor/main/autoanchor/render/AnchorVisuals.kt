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

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.AnchorPhase
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.LivingEntity

/**
 * Immutable snapshot of everything the renderers need, built once per PLAN tick by the module and read by the
 * render handlers between ticks.
 *
 * Every field may be null or zero, and a snapshot is built even on a tick the module refuses to act: that is what
 * [standDown] is for. A module that stands down silently looks broken, and this module has four ways to stand
 * down that a player cannot see from the world (wrong dimension, dud latch, glowstone in the offhand, another
 * module mid-interaction). Carrying the reason - and the cell the cycle WOULD have used - into the render is what
 * turns "it does nothing" into "it is waiting, and here is what for".
 *
 * @param cell the cell an anchor is being placed into, charged at, or detonated at. Survives a stand-down tick so
 *   the overlay has something to anchor its explanation to.
 * @param clickPos the block actually clicked: a neighbour for a relative placement, [cell] itself for the
 *   replace-in-place form and for the anchor's own two clicks.
 * @param face the placement face, or null for the replace-in-place form and for charge/detonate.
 * @param phase which of the three interactions the cycle is on; `IDLE` when there is no cycle.
 * @param remaining interactions - and therefore ticks - still needed before the blast: 3 / 2 / 1 / 0.
 * @param phaseTicks how long the current phase has waited, against [maxPhaseTicks] (`Cycle.MaxTicks`). The pair
 *   is what the ribbon's countdown arc draws, so a stalling cycle is visible BEFORE it aborts.
 * @param charge the anchor's `RespawnAnchorBlock.CHARGE` at [cell], or null when no anchor stands there. Read
 *   straight from the blockstate, which the client predicts, so the pips react on the same frame as the click.
 * @param target the victim the cell was scored against, and [targetState] its frozen state.
 * @param selfState our own state, the one the self-damage gate is evaluated against.
 * @param bestDamage / [selfDamage] effective damage of the decided cell against the victim and against us.
 * @param lethal whether [bestDamage] empties the victim on its own.
 * @param minDamage / [maxSelfDamage] the two acceptance thresholds the rings are drawn at.
 * @param standDown why the module refused the tick, or null when every gate was open.
 */
class AnchorVisuals(
    val cell: BlockPos? = null,
    val clickPos: BlockPos? = null,
    val face: Direction? = null,
    val phase: AnchorPhase = AnchorPhase.IDLE,
    val remaining: Int = 0,
    val phaseTicks: Int = 0,
    val maxPhaseTicks: Int = 0,
    val charge: Int? = null,
    val target: LivingEntity? = null,
    val targetState: VictimState? = null,
    val selfState: VictimState? = null,
    val bestDamage: Float = 0f,
    val selfDamage: Float = 0f,
    val lethal: Boolean = false,
    val minDamage: Float = 0f,
    val maxSelfDamage: Float = 0f,
    val standDown: String? = null,
) {

    /** A cycle is running and the module is free to act on it. */
    val engaged: Boolean get() = standDown == null && phase != AnchorPhase.IDLE

    /** Fraction of the current phase's tick budget already spent, `0..1`; drives the ribbon's countdown arc. */
    val phaseProgress: Float
        get() = if (maxPhaseTicks <= 0) 0f else (phaseTicks.toFloat() / maxPhaseTicks).coerceIn(0f, 1f)
}
