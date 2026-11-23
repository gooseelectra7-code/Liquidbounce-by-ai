/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * Copyright (c) 2015 - 2026 CCBlueX
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.utils.aiming.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.round

class MouseDeltaQuantizationTest {
    @Test
    fun `rounding preserves zero and emits only sensitivity counts without noise`() {
        for (quantum in listOf(0.0096, 0.15, 0.6144)) {
            assertEquals(0.0, quantizeMouseDelta(0.0, quantum))
            for (i in -1000..1000) {
                val delta = i * 0.173
                val result = quantizeMouseDelta(delta, quantum)
                assertEquals(round(result / quantum), result / quantum, 1e-9)
                assertTrue(abs(result - delta) <= quantum * 0.5 + 1e-9)
                assertEquals(result, quantizeMouseDelta(delta, quantum))
            }
        }
    }

    @Test
    fun `bounded quantization respects speed and consecutive step intervals`() {
        var previous = 0.0
        for (i in -1000..1000) {
            val low = maxOf(-7.0, previous - 1.0)
            val high = minOf(7.0, previous + 1.0)
            val step = quantizeMouseDeltaWithin(i * 0.17, 0.15, low, high)
            assertTrue(step >= low - 1e-9 && step <= high + 1e-9)
            assertEquals(round(step / 0.15), step / 0.15, 1e-9)
            previous = step
        }
    }
}
