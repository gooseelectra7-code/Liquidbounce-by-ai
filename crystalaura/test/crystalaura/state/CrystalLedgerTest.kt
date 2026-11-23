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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrystalLedgerTest {

    /** Block above the clicked obsidian; a crystal placed there spawns at (1.5, 64.0, -2.5). */
    private val above = BlockPos(1, 64, -3)

    private fun ledgerAt(tick: Int, attackTimeout: Int = 10, placeTimeout: Int = 10): CrystalLedger =
        CrystalLedger().also { it.onTickStart(tick, attackTimeout, placeTimeout) }

    // -----------------------------------------------------------------------
    //  Attack / chain marks
    // -----------------------------------------------------------------------

    @Test
    fun `markAttacked marks only that id as attacked`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, emptyMap())
        assertTrue(ledger.isAttacked(7))
        assertFalse(ledger.isAttacked(8))
        assertFalse(ledger.isExpectedRemoved(7))
    }

    @Test
    fun `attacked marks expire once older than the attack timeout`() {
        val ledger = ledgerAt(0, attackTimeout = 2)
        ledger.markAttacked(7, emptyMap())
        ledger.onTickStart(2, 2, 10)
        assertTrue(ledger.isAttacked(7))
        ledger.onTickStart(3, 2, 10)
        assertFalse(ledger.isAttacked(7))
    }

    @Test
    fun `a zero attack timeout expires on the next tick`() {
        val ledger = ledgerAt(5, attackTimeout = 0)
        ledger.markAttacked(7, emptyMap())
        assertTrue(ledger.isAttacked(7))
        ledger.onTickStart(6, 0, 0)
        assertFalse(ledger.isAttacked(7))
    }

    @Test
    fun `onRemoved clears attacked expectedRemoved and own`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, emptyMap())
        ledger.markExpectedRemoved(7)
        ledger.markPlaced(above, null)
        ledger.onSpawn(7, true, 1.5, 64.0, -2.5)
        assertTrue(ledger.isOwn(7))
        ledger.onRemoved(7)
        assertFalse(ledger.isAttacked(7))
        assertFalse(ledger.isExpectedRemoved(7))
        assertFalse(ledger.isOwn(7))
    }

    @Test
    fun `markExpectedRemoved marks the chain crystal and expires with the attack timeout`() {
        val ledger = ledgerAt(0, attackTimeout = 1)
        ledger.markExpectedRemoved(9)
        assertTrue(ledger.isExpectedRemoved(9))
        assertFalse(ledger.isAttacked(9))
        ledger.onTickStart(1, 1, 10)
        assertTrue(ledger.isExpectedRemoved(9))
        ledger.onTickStart(2, 1, 10)
        assertFalse(ledger.isExpectedRemoved(9))
    }

    @Test
    fun `attacked mark survives the spawn of a predicted id`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, 42)
        ledger.markAttacked(42, emptyMap())
        ledger.onSpawn(42, true, 1.5, 64.0, -2.5)
        assertTrue(ledger.isAttacked(42))
        assertTrue(ledger.isOwn(42))
    }

    // -----------------------------------------------------------------------
    //  Placements and spawns
    // -----------------------------------------------------------------------

    @Test
    fun `crystal spawn at the exact placement position marks own and clears pending`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        assertTrue(ledger.isPending(above))
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5)
        assertTrue(ledger.isOwn(100))
        assertFalse(ledger.isPending(above))
        assertNull(ledger.pendingPlacement())
    }

    @Test
    fun `crystal spawn at a different position does not match`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        ledger.onSpawn(100, true, 1.5, 65.0, -2.5)
        ledger.onSpawn(101, true, 1.5, 64.0, -2.5000001)
        assertFalse(ledger.isOwn(100))
        assertFalse(ledger.isOwn(101))
        assertTrue(ledger.isPending(above))
    }

    @Test
    fun `non crystal spawn at the placement position does not match`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, 100)
        ledger.onSpawn(100, false, 1.5, 64.0, -2.5)
        assertFalse(ledger.isOwn(100))
        assertTrue(ledger.isPending(above))
    }

    @Test
    fun `crystal spawn with the predicted id marks own`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, 42)
        ledger.onSpawn(42, true, 0.0, 0.0, 0.0)
        assertTrue(ledger.isOwn(42))
        assertFalse(ledger.isPending(above))
    }

    @Test
    fun `spawn reusing an own id without an observed removal clears the stale own mark`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5)
        assertTrue(ledger.isOwn(100))
        ledger.onSpawn(100, true, 9.5, 70.0, 9.5)
        assertFalse(ledger.isOwn(100))
    }

    @Test
    fun `an own mark survives up to the own timeout`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5)
        ledger.onTickStart(600, 10, 10)
        assertTrue(ledger.isOwn(100))
    }

    @Test
    fun `an own mark expires once older than the own timeout`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5)
        ledger.onTickStart(601, 10, 10)
        assertFalse(ledger.isOwn(100))
    }

    @Test
    fun `the own timeout is measured from the spawn not from the placement`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        ledger.onTickStart(300, 1000, 1000)
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5)
        ledger.onTickStart(900, 1000, 1000)
        assertTrue(ledger.isOwn(100))
        ledger.onTickStart(901, 1000, 1000)
        assertFalse(ledger.isOwn(100))
    }

    @Test
    fun `pending placements expire after the place timeout`() {
        val ledger = ledgerAt(0, placeTimeout = 3)
        ledger.markPlaced(above, null)
        ledger.onTickStart(3, 10, 3)
        assertTrue(ledger.isPending(above))
        ledger.onTickStart(4, 10, 3)
        assertFalse(ledger.isPending(above))
        assertNull(ledger.pendingPlacement())
    }

    @Test
    fun `pendingPlacement returns the newest placement`() {
        val ledger = ledgerAt(1)
        val other = BlockPos(5, 70, 5)
        ledger.markPlaced(other, 40)
        ledger.onTickStart(2, 10, 10)
        ledger.markPlaced(above, 41)
        assertEquals(PendingPlacement(above, 2, 41), ledger.pendingPlacement())
        ledger.onTickStart(3, 10, 10)
        ledger.markPlaced(other, null)
        assertEquals(PendingPlacement(other, 3, null), ledger.pendingPlacement())
    }

    @Test
    fun `onRemoved forgets the removed id as a prediction but keeps the placement pending`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, 42)
        ledger.onRemoved(42)
        assertEquals(PendingPlacement(above, 0, null), ledger.pendingPlacement())
        ledger.onSpawn(42, true, 0.0, 0.0, 0.0)
        assertFalse(ledger.isOwn(42))
        assertTrue(ledger.isPending(above))
    }

    // -----------------------------------------------------------------------
    //  Id prediction
    // -----------------------------------------------------------------------

    @Test
    fun `predictNextId is null for fewer than three spawns`() {
        val ledger = ledgerAt(0)
        assertNull(ledger.predictNextId())
        assertEquals(-1, ledger.maxSeenEntityId)
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0)
        assertNull(ledger.predictNextId())
        assertEquals(11, ledger.maxSeenEntityId)
    }

    @Test
    fun `predictNextId is null for non consecutive ids`() {
        val ledger = ledgerAt(0)
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(13, false, 0.0, 0.0, 0.0)
        assertNull(ledger.predictNextId())
    }

    @Test
    fun `predictNextId is max plus one for consecutive ids`() {
        val ledger = ledgerAt(0)
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(11, true, 0.0, 0.0, 0.0)
        ledger.onSpawn(12, false, 0.0, 0.0, 0.0)
        assertEquals(13, ledger.predictNextId())
        ledger.onSpawn(20, false, 0.0, 0.0, 0.0)
        assertNull(ledger.predictNextId())
        ledger.onSpawn(21, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(22, false, 0.0, 0.0, 0.0)
        assertEquals(23, ledger.predictNextId())
    }

    // -----------------------------------------------------------------------
    //  lastHurt estimates
    // -----------------------------------------------------------------------

    @Test
    fun `markAttacked stores positive lastHurt predictions`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f, 4 to 0f, 5 to -1f))
        assertEquals(8.5f, ledger.lastHurtEstimate(3))
        assertNull(ledger.lastHurtEstimate(4))
        assertNull(ledger.lastHurtEstimate(5))
        assertNull(ledger.lastHurtEstimate(6))
    }

    @Test
    fun `damage caused by us through the attacked crystal keeps the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.onDamageEvent(entityId = 3, causeId = 1, directId = 7, selfId = 1)
        assertEquals(8.5f, ledger.lastHurtEstimate(3))
    }

    @Test
    fun `damage through an expected removed crystal keeps the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.markExpectedRemoved(8)
        ledger.onDamageEvent(entityId = 3, causeId = 1, directId = 8, selfId = 1)
        assertEquals(8.5f, ledger.lastHurtEstimate(3))
    }

    @Test
    fun `damage through our crystal removed right before the event keeps the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.onRemoved(7)
        assertFalse(ledger.isAttacked(7))
        ledger.onDamageEvent(entityId = 3, causeId = 1, directId = 7, selfId = 1)
        assertEquals(8.5f, ledger.lastHurtEstimate(3))
    }

    @Test
    fun `foreign damage drops the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f, 4 to 6f, 5 to 6f))
        ledger.onDamageEvent(entityId = 3, causeId = 2, directId = 7, selfId = 1)
        ledger.onDamageEvent(entityId = 4, causeId = 1, directId = 1, selfId = 1)
        ledger.onDamageEvent(entityId = 5, causeId = -1, directId = -1, selfId = 1)
        assertNull(ledger.lastHurtEstimate(3))
        assertNull(ledger.lastHurtEstimate(4))
        assertNull(ledger.lastHurtEstimate(5))
    }

    @Test
    fun `confirmed damage refreshes the estimate age`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.onTickStart(15, 100, 100)
        ledger.onDamageEvent(entityId = 3, causeId = 1, directId = 7, selfId = 1)
        ledger.onTickStart(35, 100, 100)
        assertEquals(8.5f, ledger.lastHurtEstimate(3))
        ledger.onTickStart(36, 100, 100)
        assertNull(ledger.lastHurtEstimate(3))
    }

    @Test
    fun `lastHurt estimate expires twenty ticks after its last update`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.onTickStart(20, 100, 100)
        assertEquals(8.5f, ledger.lastHurtEstimate(3))
        ledger.onTickStart(21, 100, 100)
        assertNull(ledger.lastHurtEstimate(3))
    }

    @Test
    fun `a newer prediction overwrites the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.onTickStart(1, 10, 10)
        ledger.markAttacked(8, mapOf(3 to 12f))
        assertEquals(12f, ledger.lastHurtEstimate(3))
    }

    // -----------------------------------------------------------------------
    //  Reset
    // -----------------------------------------------------------------------

    @Test
    fun `reset clears all state`() {
        val ledger = ledgerAt(5)
        ledger.markAttacked(7, mapOf(3 to 8.5f))
        ledger.markExpectedRemoved(8)
        ledger.markPlaced(above, 42)
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(12, true, 1.5, 64.0, -2.5)
        ledger.reset()
        assertEquals(0, ledger.tick)
        assertEquals(-1, ledger.maxSeenEntityId)
        assertFalse(ledger.isAttacked(7))
        assertFalse(ledger.isExpectedRemoved(8))
        assertFalse(ledger.isPending(above))
        assertFalse(ledger.isOwn(12))
        assertNull(ledger.lastHurtEstimate(3))
        assertNull(ledger.predictNextId())
        assertNull(ledger.pendingPlacement())
    }

    @Test
    fun `reset clears the spawn window so id prediction starts over`() {
        val ledger = ledgerAt(0)
        ledger.onSpawn(10, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(11, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(12, false, 0.0, 0.0, 0.0)
        assertEquals(13, ledger.predictNextId())
        ledger.reset()
        ledger.onSpawn(50, false, 0.0, 0.0, 0.0)
        ledger.onSpawn(51, false, 0.0, 0.0, 0.0)
        assertNull(ledger.predictNextId())
        ledger.onSpawn(52, false, 0.0, 0.0, 0.0)
        assertEquals(53, ledger.predictNextId())
    }

    @Test
    fun `reset clears own marks that no removal was observed for`() {
        val ledger = ledgerAt(0)
        ledger.markPlaced(above, null)
        ledger.onSpawn(100, true, 1.5, 64.0, -2.5)
        assertTrue(ledger.isOwn(100))
        ledger.reset()
        assertFalse(ledger.isOwn(100))
        ledger.onTickStart(0, 10, 10)
        assertFalse(ledger.isOwn(100))
    }
}
