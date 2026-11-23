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

package net.ccbluex.liquidbounce.utils.block

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule this enforces is the one the server cares about: exactly one dig at a time.
 *
 * Worth its own test because the failure it prevents is invisible from inside either party — two
 * modules each believing they are the only one breaking a block is a state that looks perfectly
 * healthy on both sides and only shows up as a flag.
 */
class MiningClaimTest {

    private val alice = Any()
    private val bob = Any()
    private val here = BlockPos(0, 64, 0)
    private val there = BlockPos(4, 64, 0)

    @AfterEach
    fun releaseEverything() {
        MiningClaim.release(alice)
        MiningClaim.release(bob)
    }

    @Test
    fun `an unclaimed dig is free to take`() {
        assertNull(MiningClaim.holder)
        assertTrue(MiningClaim.claim(alice, here))
        assertSame(alice, MiningClaim.holder)
        assertTrue(MiningClaim.isHeldBy(alice))
    }

    @Test
    fun `two diggers cannot hold the client at once`() {
        assertTrue(MiningClaim.claim(alice, here))

        assertFalse(MiningClaim.claim(bob, there), "a second digger must be told to wait")
        assertFalse(MiningClaim.isHeldBy(bob))
        assertSame(alice, MiningClaim.holder, "and must not have taken it anyway")
    }

    @Test
    fun `the holder may move to another block`() {
        MiningClaim.claim(alice, here)

        assertTrue(MiningClaim.claim(alice, there), "the same owner is always allowed to renew")
        assertSame(there, MiningClaim.position)
    }

    @Test
    fun `letting go hands it to the next one`() {
        MiningClaim.claim(alice, here)
        MiningClaim.release(alice)

        assertNull(MiningClaim.holder)
        assertTrue(MiningClaim.claim(bob, there))
    }

    @Test
    fun `releasing is only ever your own claim to release`() {
        MiningClaim.claim(alice, here)
        MiningClaim.release(bob)

        assertSame(alice, MiningClaim.holder, "somebody else's release must not free the claim")
    }

    @Test
    fun `a digger that reports its own position holds the claim just as firmly`() {
        // How SpeedMine and PacketMine take part: their active position is derived from the packets
        // they have in flight, and copying that into a claim would be a second copy of it.
        var reported: BlockPos? = there
        MiningClaim.registerProbe(bob) { reported }

        try {
            assertSame(bob, MiningClaim.holder)
            assertFalse(MiningClaim.claim(alice, here), "a probe's dig is still a dig")

            reported = null
            assertNull(MiningClaim.holder)
            assertTrue(MiningClaim.claim(alice, here), "and once it stops, the claim is free again")
        } finally {
            MiningClaim.registerProbe(bob) { null }
        }
    }
}
