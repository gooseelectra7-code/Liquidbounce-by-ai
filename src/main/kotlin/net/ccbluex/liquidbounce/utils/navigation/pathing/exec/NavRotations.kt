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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection

/**
 * The same rotation target under a different movement correction.
 *
 * Some ways of travelling need a particular correction whatever the owner configured — a boat's
 * left and right are a rudder that silent correction would rewrite, and a glider is flown by the
 * rotation hooks, which do nothing at all while correction is off. Rebuilt field by field rather
 * than by widening
 * [net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup.toRotationTarget], which is overridden
 * elsewhere and would break those callers for the sake of two consumers here.
 */
internal fun RotationTarget.withCorrection(correction: MovementCorrection) = RotationTarget(
    rotation,
    entity,
    processors,
    ticksUntilReset,
    resetThreshold,
    considerInventory,
    correction,
    whenReached,
)

/**
 * The same target with the smoothing taken off, so the head arrives this tick.
 *
 * For the one case where a rotation is not a look but a mechanism: placing a block against a face
 * that is only in view while the body hangs over a drop. Humanised aiming is right for travel and
 * for combat, and wrong here — the aim has to be on the face before the foot leaves the ledge, and
 * a processor that spreads it over half a second turns a bridge into a fall.
 */
internal fun RotationTarget.withoutSmoothing() = RotationTarget(
    rotation,
    entity,
    emptyList(),
    ticksUntilReset,
    resetThreshold,
    considerInventory,
    movementCorrection,
    whenReached,
)
