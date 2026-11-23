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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The anchor cycle state machine, driven headlessly through [AnchorFlow] - which is why the flow takes its clock as
 * a constructor argument and learns everything else through [AnchorFlow.begin], [AnchorFlow.tick] and
 * [AnchorFlow.onSent]. The shell around it (rays, packets, slots, the ledger) needs a running client and is not
 * exercised here.
 *
 * The clock is a plain field so a test can hold it still - a real client tick is 50 ms, but after a stall several
 * ticks run inside one frame, which is exactly why the delay gate is wall clock and not a tick counter.
 *
 * The readings mirror what `AnchorView` reports: an empty replaceable cell, an anchor at a given charge, a solid
 * block, and a replaceable cell an entity is standing in.
 */
class AnchorFlowTest {

    private var now = 1_000L

    private val flow = AnchorFlow { now }

    /** `CycleSettings.MaxTicks`, the setting's own default - only the deadline tests reach it. */
    private val maxTicks = 20

    /** A deadline small enough to walk to in a test. */
    private val budget = 5

    private fun empty() = CellReading(isAnchor = false, charge = 0, replaceable = true, unobstructed = true)

    private fun anchor(charge: Int) =
        CellReading(isAnchor = true, charge = charge, replaceable = false, unobstructed = true)

    private fun solid() = CellReading(isAnchor = false, charge = 0, replaceable = false, unobstructed = true)

    private fun blocked() = CellReading(isAnchor = false, charge = 0, replaceable = true, unobstructed = false)

    // -----------------------------------------------------------------------
    //  Entry phase: read from the world, no resume path
    // -----------------------------------------------------------------------

    /** An empty replaceable cell needs the whole cycle: place, charge, detonate. */
    @Test
    fun `an empty cell enters at PLACE with three interactions left`() {
        assertTrue(flow.begin(empty()))

        assertEquals(AnchorPhase.PLACE, flow.phase)
        assertEquals(3, flow.remaining)
        assertTrue(flow.active)
        assertFalse(flow.pending)
        assertFalse(flow.committed)
        assertEquals(0, flow.ticks)
    }

    /** An uncharged anchor is already standing: `RespawnAnchorBlock.useWithoutItem` needs `CHARGE != 0` (`:101`). */
    @Test
    fun `an uncharged anchor enters at CHARGE with two interactions left`() {
        assertTrue(flow.begin(anchor(charge = 0)))

        assertEquals(AnchorPhase.CHARGE, flow.phase)
        assertEquals(2, flow.remaining)
    }

    /** A charged anchor is one click and no items - the cheapest entry there is. */
    @Test
    fun `a charged anchor enters at DETONATE with one interaction left`() {
        assertTrue(flow.begin(anchor(charge = 3)))

        assertEquals(AnchorPhase.DETONATE, flow.phase)
        assertEquals(1, flow.remaining)
    }

    /** Neither an anchor nor replaceable: nothing can ever explode there. */
    @Test
    fun `a solid cell starts no cycle`() {
        assertFalse(flow.begin(solid()))

        assertEquals(AnchorPhase.IDLE, flow.phase)
        assertEquals(0, flow.remaining)
        assertFalse(flow.active)
    }

    /** A `blocksBuilding` entity in the cell refuses the placement (`EntityGetter.isUnobstructed`). */
    @Test
    fun `an obstructed cell starts no cycle`() {
        assertFalse(flow.begin(blocked()))

        assertEquals(AnchorPhase.IDLE, flow.phase)
    }

    /** An idle flow owns nothing. */
    @Test
    fun `an idle flow does not own the tick`() {
        assertEquals(AnchorMove.NONE, flow.tick(empty(), maxTicks, 0L))
        assertFalse(flow.active)
    }

    // -----------------------------------------------------------------------
    //  The cycle and the pending resolution
    // -----------------------------------------------------------------------

    /**
     * The best case: three interactions in three consecutive ticks. Both the placement and the charge are
     * predicted client-side (`ClientLevel.setBlock`'s prediction path, `ClientLevel.java:208-221`), so the reading
     * of the NEXT tick already confirms them - and a confirmation that lands falls through to the new phase's move
     * in the same tick, which is what keeps the cycle at three ticks instead of six.
     */
    @Test
    fun `a full cycle runs PLACE CHARGE DETONATE in three ticks`() {
        flow.begin(empty())

        assertEquals(AnchorMove.PLACE, flow.tick(empty(), maxTicks, 0L))
        flow.onSent(AnchorMove.PLACE)
        assertTrue(flow.pending)

        assertEquals(AnchorMove.CHARGE, flow.tick(anchor(charge = 0), maxTicks, 0L))
        assertEquals(AnchorPhase.CHARGE, flow.phase)
        assertFalse(flow.committed)
        flow.onSent(AnchorMove.CHARGE)

        assertEquals(AnchorMove.DETONATE, flow.tick(anchor(charge = 1), maxTicks, 0L))
        assertEquals(AnchorPhase.DETONATE, flow.phase)
        assertTrue(flow.committed)
        flow.onSent(AnchorMove.DETONATE)

        // The detonation is predicted by nothing (`RespawnAnchorBlock.java:120-122`), so DONE is reached at send
        // time and the blast wait belongs to the ledger, not to the flow.
        assertEquals(AnchorPhase.DONE, flow.phase)
        assertFalse(flow.pending)
        assertFalse(flow.active)
        assertEquals(AnchorMove.NONE, flow.tick(anchor(charge = 1), maxTicks, 0L))
    }

    /** A cycle entered at DETONATE is a single tick and never sets `committed` - no glowstone was ours. */
    @Test
    fun `a detonate-only cycle is one tick and commits nothing`() {
        flow.begin(anchor(charge = 4))

        assertEquals(AnchorMove.DETONATE, flow.tick(anchor(charge = 4), maxTicks, 0L))
        flow.onSent(AnchorMove.DETONATE)

        assertEquals(AnchorPhase.DONE, flow.phase)
        assertFalse(flow.committed)
    }

    /**
     * The rule that stops one phase from clicking twice in a row: the tick after a send is spent resolving, and an
     * unresolved interaction ends it with [AnchorMove.NONE]. Only the tick AFTER that retries the phase.
     */
    @Test
    fun `an unconfirmed placement waits a tick before it is retried`() {
        flow.begin(empty())
        assertEquals(AnchorMove.PLACE, flow.tick(empty(), maxTicks, 0L))
        flow.onSent(AnchorMove.PLACE)

        // The block never appeared: resolve fails, the tick is spent, nothing goes out.
        assertEquals(AnchorMove.NONE, flow.tick(empty(), maxTicks, 0L))
        assertEquals(AnchorPhase.PLACE, flow.phase)
        assertFalse(flow.pending)

        assertEquals(AnchorMove.PLACE, flow.tick(empty(), maxTicks, 0L))
    }

    /**
     * A `ClientboundBlockChangedAckPacket` rollback drops the predicted charge back to 0, and the flow must retry
     * the charge instead of detonating a block the server still sees at `CHARGE == 0`.
     */
    @Test
    fun `a rolled back charge is retried and does not commit`() {
        flow.begin(anchor(charge = 0))
        assertEquals(AnchorMove.CHARGE, flow.tick(anchor(charge = 0), maxTicks, 0L))
        flow.onSent(AnchorMove.CHARGE)

        assertEquals(AnchorMove.NONE, flow.tick(anchor(charge = 0), maxTicks, 0L))
        assertEquals(AnchorPhase.CHARGE, flow.phase)
        assertFalse(flow.committed)

        assertEquals(AnchorMove.CHARGE, flow.tick(anchor(charge = 0), maxTicks, 0L))
        flow.onSent(AnchorMove.CHARGE)
        assertEquals(AnchorMove.DETONATE, flow.tick(anchor(charge = 1), maxTicks, 0L))
        assertTrue(flow.committed)
    }

    /** A move that does not belong to the current phase is ignored, so a stale plan cannot advance the machine. */
    @Test
    fun `onSent ignores a move from another phase`() {
        flow.begin(empty())
        flow.tick(empty(), maxTicks, 0L)

        flow.onSent(AnchorMove.DETONATE)

        assertEquals(AnchorPhase.PLACE, flow.phase)
        assertFalse(flow.pending)
    }

    // -----------------------------------------------------------------------
    //  Deadline, throttle, abort, reset
    // -----------------------------------------------------------------------

    /**
     * Ticks are charged from [AnchorFlow.begin], aim ticks included: a cell no rotation can reach costs the module
     * exactly as much as a slow one. Past the budget the cycle is abandoned, and nothing is owed on the way out -
     * a use has no abort packet.
     */
    @Test
    fun `a cycle that outlives MaxTicks gives up and owes nothing`() {
        flow.begin(empty())
        repeat(budget) { assertEquals(AnchorMove.PLACE, flow.tick(empty(), budget, 0L)) }

        assertEquals(AnchorMove.NONE, flow.tick(empty(), budget, 0L))
        assertEquals(AnchorPhase.ABORTED, flow.phase)
        assertFalse(flow.active)
        assertEquals(budget + 1, flow.ticks)
    }

    /** Waiting for a confirmation is charged to the same budget - the deadline covers the whole cycle. */
    @Test
    fun `confirmation waits count against the deadline`() {
        flow.begin(empty())
        flow.tick(empty(), budget, 0L)
        flow.onSent(AnchorMove.PLACE)

        // Tick 2 resolves (and fails), 3 retries, 4 resolves, 5 retries, 6 is past the budget.
        repeat(budget - 1) { flow.tick(empty(), budget, 0L) }
        assertEquals(AnchorMove.NONE, flow.tick(empty(), budget, 0L))
        assertEquals(AnchorPhase.ABORTED, flow.phase)
    }

    /**
     * The throttle is wall clock since the last CONFIRMED send, not a tick counter: after a stall several client
     * ticks run inside one frame and a tick counter would let the delay collapse.
     */
    @Test
    fun `the delay gate holds the next interaction back on the wall clock`() {
        val delay = 500L
        flow.begin(empty())

        assertEquals(AnchorMove.PLACE, flow.tick(empty(), maxTicks, delay))
        flow.onSent(AnchorMove.PLACE)

        // The placement resolved, but no real time has passed - the charge waits.
        assertEquals(AnchorMove.NONE, flow.tick(anchor(charge = 0), maxTicks, delay))
        assertEquals(AnchorPhase.CHARGE, flow.phase)

        now += delay - 1
        assertEquals(AnchorMove.NONE, flow.tick(anchor(charge = 0), maxTicks, delay))

        now += 1
        assertEquals(AnchorMove.CHARGE, flow.tick(anchor(charge = 0), maxTicks, delay))
    }

    /** The delay is charged across cycles too, so it throttles the module and not just one cell. */
    @Test
    fun `the delay survives into the next cycle`() {
        val delay = 500L
        flow.begin(anchor(charge = 2))
        assertEquals(AnchorMove.DETONATE, flow.tick(anchor(charge = 2), maxTicks, delay))
        flow.onSent(AnchorMove.DETONATE)

        flow.begin(anchor(charge = 2))
        assertEquals(AnchorMove.NONE, flow.tick(anchor(charge = 2), maxTicks, delay))

        now += delay
        assertEquals(AnchorMove.DETONATE, flow.tick(anchor(charge = 2), maxTicks, delay))
    }

    /**
     * Aborting is free: nothing is owed, the flow stops owning ticks at once and a new cycle may start on another
     * cell in the very next tick. What it leaves behind - a standing charged anchor - is worth more than it cost.
     */
    @Test
    fun `abort ends the cycle at once and keeps the commitment record`() {
        flow.begin(anchor(charge = 0))
        flow.tick(anchor(charge = 0), maxTicks, 0L)
        flow.onSent(AnchorMove.CHARGE)
        flow.tick(anchor(charge = 1), maxTicks, 0L)
        assertTrue(flow.committed)

        flow.abort()

        assertEquals(AnchorPhase.ABORTED, flow.phase)
        assertFalse(flow.pending)
        assertFalse(flow.active)
        assertTrue(flow.committed)
        assertEquals(AnchorMove.NONE, flow.tick(anchor(charge = 1), maxTicks, 0L))

        // A new cycle may start immediately, on this cell or another one.
        assertTrue(flow.begin(anchor(charge = 1)))
        assertEquals(AnchorPhase.DETONATE, flow.phase)
        assertFalse(flow.committed)
    }

    /** `reset` returns the flow to the idle state a fresh instance starts in. */
    @Test
    fun `reset returns the flow to idle`() {
        flow.begin(empty())
        flow.tick(empty(), maxTicks, 0L)
        flow.onSent(AnchorMove.PLACE)

        flow.reset()

        assertEquals(AnchorPhase.IDLE, flow.phase)
        assertEquals(0, flow.ticks)
        assertEquals(0, flow.phaseTicks)
        assertEquals(0, flow.remaining)
        assertFalse(flow.pending)
        assertFalse(flow.committed)
        assertFalse(flow.active)
        assertEquals(AnchorMove.NONE, flow.tick(empty(), maxTicks, 0L))
    }
}
