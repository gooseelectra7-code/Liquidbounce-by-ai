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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine

import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The state machine of `MineJob`, driven headlessly through [MineFlow] — which is why the flow takes its clock as
 * a constructor argument and learns everything else through [MineFlow.tick], [MineFlow.onStarted],
 * [MineFlow.onFinished], [MineFlow.onSwallowed] and [MineFlow.onAborted]. The shell around it (rays, packets, the
 * `MiningClaim`) needs a running client and is not exercised here.
 *
 * The clock is a plain field so a test can hold it still — a real client tick is 50 ms, but after a stall several
 * ticks run inside one frame, which is exactly the case the wall-clock floor of `MineFlow.settle` exists for.
 */
class MineFlowTest {

    private var now = 1_000L

    private val flow = MineFlow { now }

    /** `Mine.MaxTicks`, the setting's own default — large enough that only the deadline tests reach it. */
    private val maxTicks = 40

    /** A deadline small enough to walk to in a test. */
    private val budget = 5

    private val pacing = MineJob.PACING_MS

    /** A freshly begun job aims at the first cell and owns its ticks. */
    @Test
    fun `begin starts on the first cell in AIM`() {
        flow.begin(2)

        assertEquals(MinePhase.AIM, flow.phase)
        assertEquals(0, flow.cell)
        assertEquals(0, flow.cellTicks)
        assertTrue(flow.active)
        assertFalse(flow.started)
        assertNull(flow.face)
    }

    /** An idle flow owns nothing. */
    @Test
    fun `an idle flow does not own the tick`() {
        assertEquals(MineMove.NONE, flow.tick(maxTicks, pacing))
        assertFalse(flow.active)
    }

    /**
     * The one-cell dig: aim, `START`, middle ticks, `STOP`. Every tick of it is a [MineMove.DIG], i.e. one
     * `doBreak` call, and the job is [MinePhase.DONE] the moment the only cell falls.
     */
    @Test
    fun `a one-cell dig runs AIM to DONE`() {
        flow.begin(1)

        assertEquals(MineMove.DIG, flow.tick(maxTicks, pacing))
        flow.onStarted(Direction.UP)
        assertEquals(MinePhase.DIG, flow.phase)
        assertTrue(flow.started)
        assertSame(Direction.UP, flow.face)

        assertEquals(MineMove.DIG, flow.tick(maxTicks, pacing))
        assertEquals(MinePhase.DIG, flow.phase)

        flow.onFinished(instant = false)
        assertEquals(MinePhase.DONE, flow.phase)
        assertFalse(flow.active)
        assertFalse(flow.started)
        assertNull(flow.face)
        assertEquals(MineMove.NONE, flow.tick(maxTicks, pacing))
    }

    /**
     * The two-cell dig and its pacing window. `MultiPlayerGameMode.continueDestroyBlock` swallows the five calls
     * that follow a completed break (`destroyDelay = 5`, `MultiPlayerGameMode.java:225-227, :278`), so those are
     * made straight away — they carry no packet — and only the call that would BECOME the next `START` waits for
     * the 300 ms floor.
     */
    @Test
    fun `the second cell waits out vanilla's destroy delay and the wall clock`() {
        flow.begin(2)
        flow.tick(maxTicks, pacing)
        flow.onStarted(Direction.UP)
        flow.onFinished(instant = false)

        assertEquals(MinePhase.SETTLE, flow.phase)
        assertEquals(1, flow.cell)
        assertEquals(0, flow.cellTicks)

        // The calls vanilla is going to swallow are made at once; the clock does not move (a stalled client).
        repeat(MineFlow.VANILLA_DESTROY_DELAY) {
            assertEquals(MineMove.DIG, flow.tick(maxTicks, pacing))
            flow.onSwallowed()
        }

        // The next call would be the START, and no real time has passed.
        assertEquals(MineMove.HOLD, flow.tick(maxTicks, pacing))
        assertEquals(MinePhase.SETTLE, flow.phase)

        now += pacing - 1
        assertEquals(MineMove.HOLD, flow.tick(maxTicks, pacing))

        now += 1
        assertEquals(MineMove.DIG, flow.tick(maxTicks, pacing))
        flow.onStarted(Direction.NORTH)
        assertEquals(MinePhase.DIG, flow.phase)
        assertSame(Direction.NORTH, flow.face)

        flow.onFinished(instant = false)
        assertEquals(MinePhase.DONE, flow.phase)
    }

    /**
     * An INSTANT break leaves `destroyDelay` at zero — the non-creative instant path of `startDestroyBlock`
     * (`MultiPlayerGameMode.java:188-190`) never sets it — so nothing but the wall-clock floor separates it from
     * the next `START`.
     */
    @Test
    fun `an instant break is paced by the clock alone`() {
        flow.begin(2)
        flow.tick(maxTicks, pacing)
        flow.onStarted(Direction.UP)
        flow.onFinished(instant = true)

        assertEquals(MinePhase.SETTLE, flow.phase)
        assertEquals(MineMove.HOLD, flow.tick(maxTicks, pacing))

        now += pacing
        assertEquals(MineMove.DIG, flow.tick(maxTicks, pacing))
    }

    /** A cell that outlives `Mine.MaxTicks` ends the job; the shell still owes the `ABORT` while [started]. */
    @Test
    fun `a cell that outlives MaxTicks gives up`() {
        flow.begin(1)
        repeat(budget) { assertEquals(MineMove.DIG, flow.tick(budget, pacing)) }
        flow.onStarted(Direction.UP)

        assertEquals(MineMove.ABORT, flow.tick(budget, pacing))
        assertEquals(MinePhase.ABORTED, flow.phase)
        assertFalse(flow.active)
        assertTrue(flow.started)
    }

    /** The deadline is charged per cell: finishing one hands the next its own full budget. */
    @Test
    fun `the tick budget is charged per cell`() {
        flow.begin(2)
        repeat(budget) { flow.tick(budget, pacing) }
        flow.onStarted(Direction.UP)
        flow.onFinished(instant = true)
        now += pacing

        assertEquals(0, flow.cellTicks)
        repeat(budget) { assertEquals(MineMove.DIG, flow.tick(budget, pacing)) }
        assertEquals(MineMove.ABORT, flow.tick(budget, pacing))
    }

    /** After the `ABORT` went out nothing is owed any more and the flow owns no further tick. */
    @Test
    fun `an abort clears the outstanding START`() {
        flow.begin(2)
        flow.tick(maxTicks, pacing)
        flow.onStarted(Direction.EAST)
        flow.onAborted()

        assertEquals(MinePhase.ABORTED, flow.phase)
        assertFalse(flow.started)
        assertNull(flow.face)
        assertFalse(flow.active)
        assertEquals(MineMove.NONE, flow.tick(maxTicks, pacing))
    }

    /** `reset` returns the flow to the idle state a fresh instance starts in. */
    @Test
    fun `reset returns the flow to idle`() {
        flow.begin(2)
        flow.tick(maxTicks, pacing)
        flow.onStarted(Direction.SOUTH)
        flow.reset()

        assertEquals(MinePhase.IDLE, flow.phase)
        assertEquals(0, flow.cell)
        assertEquals(0, flow.cellTicks)
        assertFalse(flow.started)
        assertNull(flow.face)
        assertFalse(flow.active)
    }
}
