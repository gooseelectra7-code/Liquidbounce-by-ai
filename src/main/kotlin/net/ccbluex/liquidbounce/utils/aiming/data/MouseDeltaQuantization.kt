/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.data

import kotlin.math.ceil
import kotlin.math.floor

/** Whole sensitivity counts, with no independent noise after quantization. */
internal fun quantizeMouseDelta(delta: Double, quantum: Double): Double =
    if (quantum.isFinite() && quantum > 0.0) floor(delta / quantum + 0.5) * quantum else delta

/** Quantize within speed/acceleration bounds which contain at least one whole count. */
internal fun quantizeMouseDeltaWithin(delta: Double, quantum: Double, minimum: Double, maximum: Double): Double {
    if (!quantum.isFinite() || quantum <= 0.0) return delta.coerceIn(minimum, maximum)
    val lowerCount = ceil(minimum / quantum - 1e-9)
    val upperCount = floor(maximum / quantum + 1e-9)
    check(lowerCount <= upperCount) { "Mouse-count interval is empty" }
    return floor(delta / quantum + 0.5).coerceIn(lowerCount, upperCount) * quantum
}
