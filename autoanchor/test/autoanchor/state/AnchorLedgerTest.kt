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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.state

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pending-detonation ledger, exercised without a client: every input arrives through a method, the clock is
 * injected and the tick counter is driven by hand.
 *
 * The centre of every detonation is `pos.getCenter()`, exactly the `boomPos` vanilla builds at
 * `net/minecraft/world/level/block/RespawnAnchorBlock.java:168` and puts into both the explosion and the damage
 * source, which is why the comparisons below are exact doubles.
 */
class AnchorLedgerTest {

    private val cell = BlockPos(4, 64, -7)

    private val centre = Vec3(4.5, 64.5, -6.5)

    private val other = BlockPos(5, 64, -7)

    private var now = 10_000L

    /** `CycleSettings` defaults small enough to walk to; two duds in a row latch. */
    private val detonateTimeout = 2
    private val dudTicks = 5
    private val dudLimit = 2

    /** Blast-shadow budget the helpers advance the ledger with; individual tests override it. */
    private var shadowTicks = 0

    private fun ledgerAt(tick: Int): AnchorLedger =
        AnchorLedger { now }.also { it.onTickStart(tick, detonateTimeout, dudTicks, dudLimit, shadowTicks) }

    private fun AnchorLedger.advanceTo(tick: Int) =
        onTickStart(tick, detonateTimeout, dudTicks, dudLimit, shadowTicks)

    // -----------------------------------------------------------------------
    //  Pending detonation: set, and the three ways it clears
    // -----------------------------------------------------------------------

    /**
     * The click alone hides the cell: the anchor is still standing locally because the client predicts no part of
     * the explosion (`RespawnAnchorBlock.java:120-122`, `ClientLevel.explode` is empty), so without this the search
     * would propose the same anchor again next tick.
     */
    @Test
    fun `a detonated cell is hidden and stamped`() {
        val ledger = ledgerAt(3)
        ledger.markDetonated(cell, emptyMap())

        assertTrue(ledger.isDetonating(cell))
        assertTrue(ledger.isExcluded(cell))
        assertFalse(ledger.isDetonating(other))

        val pending = ledger.pendingDetonation(cell)
        requireNotNull(pending)
        assertEquals(3, pending.detonatedTick)
        assertEquals(now, pending.detonatedAt)
        assertEquals(centre, pending.centre)
    }

    /** Route 1: `ClientboundExplodePacket.center` matches ours exactly - the positive confirmation. */
    @Test
    fun `an explosion at our centre clears the pending detonation`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())

        assertTrue(ledger.onExplosion(centre, AnchorMath.POWER))
        assertFalse(ledger.isDetonating(cell))
        assertFalse(ledger.isExcluded(cell))
        assertEquals(0, ledger.dudStreak)
    }

    /** A blast somewhere else is not ours and must not clear anything. */
    @Test
    fun `an explosion elsewhere clears nothing`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())

        assertFalse(ledger.onExplosion(Vec3(5.5, 64.5, -6.5), AnchorMath.POWER))
        assertTrue(ledger.isDetonating(cell))
    }

    /** Route 2: the cell stopped reading as a respawn anchor - there is nothing left to detonate either way. */
    @Test
    fun `the anchor disappearing clears the pending detonation`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())

        assertTrue(ledger.onAnchorGone(cell))
        assertFalse(ledger.isDetonating(cell))
        assertFalse(ledger.onAnchorGone(cell))
        assertFalse(ledger.onAnchorGone(other))
    }

    // -----------------------------------------------------------------------
    //  Route 3: the dud verdict and the latch
    // -----------------------------------------------------------------------

    /** Route 3: neither confirmation arrived inside the window, so the click counts as a dud. */
    @Test
    fun `a detonation nobody confirmed becomes a dud and stays excluded`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())

        ledger.advanceTo(detonateTimeout)
        assertTrue(ledger.isDetonating(cell))
        assertEquals(0, ledger.dudStreak)

        ledger.advanceTo(detonateTimeout + 1)
        assertFalse(ledger.isDetonating(cell))
        assertTrue(ledger.isDud(cell))
        assertTrue(ledger.isExcluded(cell))
        assertEquals(1, ledger.dudStreak)
        assertFalse(ledger.latched)
    }

    /** The dud mark is a window, not a life sentence: it expires after `CycleSettings.dudTicks`. */
    @Test
    fun `a dud mark expires after dudTicks`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())
        ledger.advanceTo(detonateTimeout + 1)
        assertTrue(ledger.isDud(cell))

        ledger.advanceTo(detonateTimeout + 1 + dudTicks)
        assertTrue(ledger.isDud(cell))

        ledger.advanceTo(detonateTimeout + 2 + dudTicks)
        assertFalse(ledger.isDud(cell))
        assertFalse(ledger.isExcluded(cell))
    }

    /**
     * Two duds in a row are a systematic answer - the dimension really does not explode anchors here - and the
     * module latches off and says so exactly once.
     */
    @Test
    fun `dudLimit consecutive duds latch the module off and notify once`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())
        ledger.advanceTo(detonateTimeout + 1)
        assertFalse(ledger.latched)
        assertFalse(ledger.consumeLatchNotice())

        ledger.markDetonated(other, emptyMap())
        ledger.advanceTo(detonateTimeout * 2 + 2)

        assertEquals(dudLimit, ledger.dudStreak)
        assertTrue(ledger.latched)
        assertTrue(ledger.consumeLatchNotice())
        assertFalse(ledger.consumeLatchNotice())
    }

    /** "Consecutive" means consecutive: one confirmed blast in between resets the streak. */
    @Test
    fun `a confirmed detonation resets the dud streak`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())
        ledger.advanceTo(detonateTimeout + 1)
        assertEquals(1, ledger.dudStreak)

        ledger.markDetonated(other, emptyMap())
        assertTrue(ledger.onExplosion(other.center, AnchorMath.POWER))
        assertEquals(0, ledger.dudStreak)
        assertFalse(ledger.latched)
    }

    /**
     * A late explode packet for a cell already written off is positive proof that anchors DO explode here, so the
     * dud mark, the streak and the latch all go.
     */
    @Test
    fun `a late explosion retracts the dud and the latch`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, emptyMap())
        ledger.markDetonated(other, emptyMap())
        ledger.advanceTo(detonateTimeout + 1)

        assertTrue(ledger.latched)
        assertTrue(ledger.isDud(cell))

        assertTrue(ledger.onExplosion(centre, AnchorMath.POWER))
        assertFalse(ledger.isDud(cell))
        assertEquals(0, ledger.dudStreak)
        assertFalse(ledger.latched)
        assertFalse(ledger.consumeLatchNotice())
    }

    // -----------------------------------------------------------------------
    //  lastHurt estimates
    // -----------------------------------------------------------------------

    /** The estimate rides along with the detonation that predicted it; a zero prediction stores nothing. */
    @Test
    fun `markDetonated stores the predicted lastHurt`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f, 8 to 0f))

        assertEquals(11.5f, ledger.lastHurtEstimate(7))
        assertNull(ledger.lastHurtEstimate(8))
        assertNull(ledger.lastHurtEstimate(9))
    }

    /**
     * Attribution is by damage TYPE plus the exact source position, because
     * `badRespawnPointExplosion(boomPos)` has neither a causing nor a direct entity and both ids decode to -1
     * (`ClientboundDamageEventPacket.java:26-27, 46-48`).
     */
    @Test
    fun `a bad respawn point hit at our centre keeps the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))

        ledger.onDamageEvent(entityId = 7, isBadRespawnPoint = true, sourcePosition = centre)

        assertEquals(11.5f, ledger.lastHurtEstimate(7))
    }

    /** The right type at the wrong place is somebody else's anchor; the amount is unknowable, so drop it. */
    @Test
    fun `a bad respawn point hit elsewhere drops the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))

        ledger.onDamageEvent(entityId = 7, isBadRespawnPoint = true, sourcePosition = Vec3(9.5, 64.5, -6.5))

        assertNull(ledger.lastHurtEstimate(7))
    }

    /** Any other damage - melee, fall, a foreign crystal - also has an unknown amount. */
    @Test
    fun `a foreign damage type drops the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))

        ledger.onDamageEvent(entityId = 7, isBadRespawnPoint = false, sourcePosition = centre)

        assertNull(ledger.lastHurtEstimate(7))
    }

    /** A packet with no `sourcePosition` cannot be attributed at all. */
    @Test
    fun `a hit without a source position drops the estimate`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))

        ledger.onDamageEvent(entityId = 7, isBadRespawnPoint = true, sourcePosition = null)

        assertNull(ledger.lastHurtEstimate(7))
    }

    /**
     * `removeBlock` runs BEFORE `level.explode` (`RespawnAnchorBlock.java:151, :169`) and the explode packet is
     * sent only after the blast has already hurt everything (`ServerLevel.java:1222-1228`), so both clearing
     * routes can land before the damage events they belong to - which is what the grace memory is for.
     */
    @Test
    fun `a damage event still attributes after the detonation was cleared`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))
        assertTrue(ledger.onAnchorGone(cell))

        ledger.onDamageEvent(entityId = 7, isBadRespawnPoint = true, sourcePosition = centre)

        assertEquals(11.5f, ledger.lastHurtEstimate(7))
    }

    /** Vanilla's `invulnerableTime = 20` (`LivingEntity.java:1207`) bounds how long an estimate is worth keeping. */
    @Test
    fun `an estimate expires after the i-frame window`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))

        ledger.advanceTo(20)
        assertEquals(11.5f, ledger.lastHurtEstimate(7))

        ledger.advanceTo(21)
        assertNull(ledger.lastHurtEstimate(7))
    }

    // -----------------------------------------------------------------------
    //  Reset
    // -----------------------------------------------------------------------

    /** The latch is scoped to one `ClientLevel` by the hub calling this on a world change. */
    @Test
    fun `reset forgets every mark including the latch`() {
        val ledger = ledgerAt(0)
        ledger.markDetonated(cell, mapOf(7 to 11.5f))
        ledger.markDetonated(other, emptyMap())
        ledger.advanceTo(detonateTimeout + 1)
        assertTrue(ledger.latched)

        ledger.reset()

        assertEquals(0, ledger.tick)
        assertEquals(0, ledger.dudStreak)
        assertFalse(ledger.latched)
        assertFalse(ledger.consumeLatchNotice())
        assertFalse(ledger.isDetonating(cell))
        assertFalse(ledger.isDud(cell))
        assertNull(ledger.lastHurtEstimate(7))
    }

    // ---- The blast shadow: what keeps Grim's AirLiquidPlace quiet ----

    @Test
    fun `our own detonating click shadows its blast at once, before any explosion comes back`() {
        shadowTicks = 4
        val ledger = ledgerAt(1)
        val cell = BlockPos(0, 64, 0)
        ledger.markDetonated(cell, emptyMap())

        // The click is the start of the danger window: the server has not answered yet, and by the time it does
        // the blocks around the anchor are already gone.
        assertTrue(ledger.isBlastShadowed(cell), "the anchor's own cell")
        assertTrue(ledger.isBlastShadowed(cell.offset(4, 0, 0)), "a support well inside the blast")
    }

    @Test
    fun `the shadow covers the blast radius and stops outside it`() {
        shadowTicks = 4
        val ledger = ledgerAt(1)
        ledger.markDetonated(BlockPos(0, 64, 0), emptyMap())

        // Radius 5 plus one block of slack, measured between block centres.
        assertTrue(ledger.isBlastShadowed(BlockPos(5, 64, 0)), "inside")
        assertFalse(ledger.isBlastShadowed(BlockPos(9, 64, 0)), "clear of the blast and its slack")
    }

    @Test
    fun `the shadow lifts on its own`() {
        shadowTicks = 3
        val ledger = ledgerAt(1)
        val cell = BlockPos(0, 64, 0)
        ledger.markDetonated(cell, emptyMap())

        ledger.advanceTo(3)
        assertTrue(ledger.isBlastShadowed(cell), "still inside the window")
        ledger.advanceTo(4)
        assertFalse(ledger.isBlastShadowed(cell), "the window has passed")
    }

    @Test
    fun `a foreign explosion shadows its own radius`() {
        shadowTicks = 4
        val ledger = ledgerAt(1)
        val centre = Vec3(40.5, 64.5, 40.5)

        // Nothing of ours: the return value says so, but the aftermath is in flight all the same.
        assertFalse(ledger.onExplosion(centre, AnchorMath.POWER))
        assertTrue(ledger.isBlastShadowed(BlockPos(42, 64, 40)))
        assertFalse(ledger.isBlastShadowed(BlockPos(0, 64, 0)))
    }

    @Test
    fun `a zero budget disarms the shadow entirely`() {
        shadowTicks = 0
        val ledger = ledgerAt(1)
        val cell = BlockPos(0, 64, 0)
        ledger.markDetonated(cell, emptyMap())

        assertFalse(ledger.isBlastShadowed(cell))
    }
}
