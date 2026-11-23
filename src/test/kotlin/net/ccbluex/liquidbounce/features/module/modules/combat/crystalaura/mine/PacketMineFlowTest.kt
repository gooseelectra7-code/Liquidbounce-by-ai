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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arbitration of the SpeedMine engine, driven headlessly through [PacketMineFlow]. The shell around it (the rays,
 * `ModuleSpeedMine`, the world) needs a running client and is not exercised here; what is, is the promise the engine
 * exists for: the dig takes ONLY its START and FINISH ticks, a ready aura action goes first at most
 * [PacketMineFlow.MAX_DEFER_TICKS] ticks in a row, and every way of waiting is bounded.
 */
class PacketMineFlowTest {

    private val flow = PacketMineFlow()

    private val limits = PacketMineFlow.Limits(maxOwnedTicks = 40, maxWaitTicks = 200, breakTicks = 5)

    private val noDig = PacketMineFlow.Reading(engineDigs = false, finishable = false, pacingOpen = true)
    private val digging = PacketMineFlow.Reading(engineDigs = true, finishable = false, pacingOpen = true)
    private val ready = PacketMineFlow.Reading(engineDigs = true, finishable = true, pacingOpen = true)

    private fun tick(reading: PacketMineFlow.Reading, auraOffers: Boolean = false) =
        flow.tick(reading, auraOffers, limits)

    /** The whole point: between the START and the STOP the dig takes no tick at all. */
    @Test
    fun `only the START and the FINISH ticks are taken`() {
        flow.begin(1)

        assertEquals(MineMove.START, tick(noDig))
        flow.onStarted()
        repeat(30) { assertEquals(MineMove.NONE, tick(digging), "middle tick $it was taken") }
        assertEquals(MineMove.FINISH, tick(ready))
        flow.onFinishSent()
        repeat(3) { assertEquals(MineMove.NONE, tick(ready), "a tick after the STOP was taken") }
        flow.onCellGone()

        assertEquals(PacketMinePhase.DONE, flow.phase)
        assertFalse(flow.active)
    }

    /** An action the aura has ready goes first - but only twice in a row, then the dig takes the tick anyway. */
    @Test
    fun `a ready aura action goes first at most twice in a row`() {
        flow.begin(1)
        flow.onStarted()

        repeat(PacketMineFlow.MAX_DEFER_TICKS) { assertEquals(MineMove.NONE, tick(ready, auraOffers = true)) }
        assertEquals(MineMove.FINISH, tick(ready, auraOffers = true))
        // Taking the tick resets the count: the next wanted tick yields again.
        flow.onFinishRefused()
        assertEquals(MineMove.NONE, tick(ready, auraOffers = true))
    }

    /**
     * The defer limit counts a RUN: a tick the job does not want at all ends it, so deferrals scattered over a long
     * dig do not add up into a tick taken against an aura action (a refutation audit's finding).
     */
    @Test
    fun `a tick the job does not want ends the run of deferrals`() {
        flow.begin(1)
        flow.onStarted()

        assertEquals(MineMove.NONE, tick(ready, auraOffers = true))
        assertEquals(MineMove.NONE, tick(digging, auraOffers = true))
        repeat(PacketMineFlow.MAX_DEFER_TICKS) { assertEquals(MineMove.NONE, tick(ready, auraOffers = true)) }
        assertEquals(MineMove.FINISH, tick(ready, auraOffers = true))
    }

    /** With no aura action the wanted tick is taken at once, nothing is deferred. */
    @Test
    fun `a quiet aura gives the tick at once`() {
        flow.begin(1)

        assertEquals(MineMove.START, tick(noDig, auraOffers = false))
    }

    /** No START inside the 300 ms after anybody's STOP, and no tick taken for waiting it out. */
    @Test
    fun `the START waits out the pacing without taking ticks`() {
        flow.begin(1)
        val pacing = PacketMineFlow.Reading(engineDigs = false, finishable = false, pacingOpen = false)

        repeat(6) { assertEquals(MineMove.NONE, tick(pacing)) }
        assertEquals(0, flow.ownedTicks)
        assertEquals(MineMove.START, tick(noDig))
    }

    /** A block the engine already digs (the player clicked it first) is adopted, not started again. */
    @Test
    fun `an existing dig is adopted`() {
        flow.begin(1)

        assertEquals(MineMove.NONE, tick(digging))
        assertEquals(PacketMinePhase.DIG, flow.phase)
        assertEquals(MineMove.FINISH, tick(ready))
    }

    /** A STOP that would be held is never asked for: `finishable` false keeps the job in DIG, taking nothing. */
    @Test
    fun `an unready dig takes nothing`() {
        flow.begin(1)
        flow.onStarted()

        repeat(50) { tick(digging) }
        assertEquals(0, flow.ownedTicks)
        assertEquals(PacketMinePhase.DIG, flow.phase)
    }

    /** Three refusals in a row back the job off, so a refusal that does not clear cannot keep taking ticks. */
    @Test
    fun `repeated refusals back off`() {
        flow.begin(1)
        flow.onStarted()

        repeat(PacketMineFlow.MAX_REFUSALS) {
            assertEquals(MineMove.FINISH, tick(ready))
            flow.onFinishRefused()
        }
        repeat(PacketMineFlow.BACKOFF_TICKS) { assertEquals(MineMove.NONE, tick(ready), "backoff tick $it") }
        assertEquals(MineMove.FINISH, tick(ready))
    }

    /** A STOP the server never answers puts the job back on the dig after the break timeout. */
    @Test
    fun `an unanswered STOP resumes the dig`() {
        flow.begin(1)
        flow.onStarted()
        assertEquals(MineMove.FINISH, tick(ready))
        flow.onFinishSent()

        repeat(limits.breakTicks) { assertEquals(MineMove.NONE, tick(ready)) }
        assertEquals(MineMove.NONE, tick(ready))
        assertEquals(PacketMinePhase.DIG, flow.phase)
        assertEquals(MineMove.FINISH, tick(ready))
    }

    /** The engine dropping the dig sends the job back to START, and only a bounded number of times. */
    @Test
    fun `a dropped dig restarts, a bounded number of times`() {
        flow.begin(1)
        flow.onStarted()

        repeat(PacketMineFlow.MAX_RESTARTS) {
            assertEquals(MineMove.NONE, tick(noDig))
            assertEquals(PacketMinePhase.START, flow.phase)
            assertEquals(MineMove.START, tick(noDig))
            flow.onStarted()
        }
        assertEquals(MineMove.NONE, tick(noDig))
        assertEquals(PacketMinePhase.ABORTED, flow.phase)
    }

    /** `Mine.MaxTicks` counts the ticks TAKEN, and ends the job when they run out. */
    @Test
    fun `the owned-tick budget ends the job`() {
        val small = PacketMineFlow.Limits(maxOwnedTicks = 3, maxWaitTicks = 200, breakTicks = 5)
        flow.begin(1)

        repeat(3) { assertEquals(MineMove.START, flow.tick(noDig, false, small)) }
        assertEquals(MineMove.NONE, flow.tick(noDig, false, small))
        assertEquals(PacketMinePhase.ABORTED, flow.phase)
    }

    /** The wait budget bounds a dig that never becomes ready, even though it takes no ticks. */
    @Test
    fun `the wait budget ends a dig that never fills`() {
        val small = PacketMineFlow.Limits(maxOwnedTicks = 40, maxWaitTicks = 10, breakTicks = 5)
        flow.begin(1)
        flow.onStarted()

        repeat(10) { flow.tick(digging, false, small) }
        assertTrue(flow.active)
        flow.tick(digging, false, small)
        assertEquals(PacketMinePhase.ABORTED, flow.phase)
    }

    /** A two-cell pocket starts the second cell over, with fresh budgets. */
    @Test
    fun `the second cell starts over`() {
        flow.begin(2)
        assertEquals(MineMove.START, tick(noDig))
        flow.onStarted()
        assertEquals(MineMove.FINISH, tick(ready))
        flow.onFinishSent()
        flow.onCellGone()

        assertEquals(PacketMinePhase.START, flow.phase)
        assertEquals(1, flow.cell)
        assertEquals(0, flow.ownedTicks)
        assertEquals(0, flow.waitTicks)
        assertEquals(MineMove.START, tick(noDig))
    }
}
