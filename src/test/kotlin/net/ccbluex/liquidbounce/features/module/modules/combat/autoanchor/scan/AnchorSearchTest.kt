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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.scan

import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.chargeOf
import net.ccbluex.liquidbounce.test.MinecraftBootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The rules `AnchorSearch` decides with, exercised on their own: the EXISTING / NEW classification and its
 * interaction counts ([anchorEntry]), the ammo refusal ([AnchorAmmo.affords]) and the gain floor
 * ([newAnchorFloor]).
 *
 * They are separate functions precisely so they can be tested without a client: the search itself reads
 * `mc.level` and `mc.player` through `AnchorView`, which no unit test can supply.
 */
class AnchorSearchTest {

    companion object {

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            MinecraftBootstrap.ensureInitialized()
        }
    }

    /**
     * `chargeOf` on the states the cell loop actually meets: a placed anchor
     * (`RespawnAnchorBlock` overrides neither `getStateForPlacement` nor `canSurvive`, so a `BlockItem` placement
     * lands at `CHARGE = 0`), a charged one, and two non-anchors.
     */
    @Test
    fun `chargeOf reads the anchor charge and nothing else`() {
        assertEquals(0, chargeOf(Blocks.RESPAWN_ANCHOR.defaultBlockState()))
        assertEquals(
            3,
            chargeOf(Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 3)),
        )
        assertNull(chargeOf(Blocks.AIR.defaultBlockState()))
        assertNull(chargeOf(Blocks.STONE.defaultBlockState()))
    }

    /** A standing anchor with `CHARGE >= 1` is one interaction: `useWithoutItem` only needs `CHARGE != 0`. */
    @Test
    fun `a charged anchor needs the detonation only`() {
        val entry = anchorEntry(charge = 1, replaceable = false)
        assertEquals(AnchorKind.EXISTING, entry?.kind)
        assertEquals(1, entry?.charge)
        assertEquals(DETONATE_ONLY, entry?.remaining)
    }

    /** A standing anchor at `CHARGE = 0` still needs a glowstone click before it will explode. */
    @Test
    fun `an uncharged anchor needs charge and detonation`() {
        val entry = anchorEntry(charge = 0, replaceable = false)
        assertEquals(AnchorKind.EXISTING, entry?.kind)
        assertEquals(0, entry?.charge)
        assertEquals(CHARGE_AND_DETONATE, entry?.remaining)
    }

    /** A replaceable cell is the full three-click cycle, and the placement always lands at `CHARGE = 0`. */
    @Test
    fun `a replaceable cell is the full cycle`() {
        val entry = anchorEntry(charge = null, replaceable = true)
        assertEquals(AnchorKind.NEW, entry?.kind)
        assertEquals(0, entry?.charge)
        assertEquals(FULL_CYCLE, entry?.remaining)
    }

    /** Neither an anchor nor replaceable: no explosion can ever happen in that cell. */
    @Test
    fun `a solid non-anchor cell is no candidate`() {
        assertNull(anchorEntry(charge = null, replaceable = false))
    }

    /**
     * A standing anchor is never a placement target, whatever `canBeReplaced` says: clicking it charges or
     * detonates it. The caller keeps that asymmetry by only asking for replaceability when no anchor was found,
     * and the classification enforces it a second time.
     */
    @Test
    fun `a standing anchor wins over replaceability`() {
        val entry = anchorEntry(charge = 0, replaceable = true)
        assertEquals(AnchorKind.EXISTING, entry?.kind)
        assertEquals(CHARGE_AND_DETONATE, entry?.remaining)
    }

    /** The detonation needs no item at all - the block takes the click before the item does. */
    @Test
    fun `detonation is affordable with an empty inventory`() {
        val empty = AnchorAmmo(canPlace = false, canCharge = false)
        assertTrue(empty.affords(DETONATE_ONLY))
        assertFalse(empty.affords(CHARGE_AND_DETONATE))
        assertFalse(empty.affords(FULL_CYCLE))
    }

    /**
     * Without an anchor in the hotbar a fresh cell is refused IN the search. Without that, the ranking keeps
     * proposing three-interaction candidates the sequencer refuses and the module starves.
     */
    @Test
    fun `no anchor in the hotbar refuses a new cell but keeps existing ones`() {
        val noAnchor = AnchorAmmo(canPlace = false, canCharge = true)
        assertFalse(noAnchor.affords(FULL_CYCLE))
        assertTrue(noAnchor.affords(CHARGE_AND_DETONATE))
        assertTrue(noAnchor.affords(DETONATE_ONLY))
    }

    /** Without glowstone (or with `Recharge` off) everything that still needs charging is refused. */
    @Test
    fun `no glowstone refuses everything that still needs charging`() {
        val noFuel = AnchorAmmo(canPlace = true, canCharge = false)
        assertFalse(noFuel.affords(FULL_CYCLE))
        assertFalse(noFuel.affords(CHARGE_AND_DETONATE))
        assertTrue(noFuel.affords(DETONATE_ONLY))
    }

    /** Everything affordable admits every entry phase. */
    @Test
    fun `a full inventory affords every entry phase`() {
        assertTrue(AnchorAmmo.ALL.affords(DETONATE_ONLY))
        assertTrue(AnchorAmmo.ALL.affords(CHARGE_AND_DETONATE))
        assertTrue(AnchorAmmo.ALL.affords(FULL_CYCLE))
    }

    /** `bestExisting * (1 + gain / 100)`: a new cell must BEAT the standing anchor, not tie it. */
    @Test
    fun `the gain floor scales the best existing candidate`() {
        assertEquals(12.5f, newAnchorFloor(bestExisting = 10f, gainPercent = 25f))
        assertEquals(10f, newAnchorFloor(bestExisting = 10f, gainPercent = 0f))
        assertEquals(30f, newAnchorFloor(bestExisting = 10f, gainPercent = 200f))
    }

    /**
     * With no standing anchor in reach the floor is `0`, so a new cell is judged on `MinDamage` alone - the gain
     * rule must never suppress the only kind of candidate available.
     */
    @Test
    fun `the gain floor is zero without an existing candidate`() {
        assertEquals(0f, newAnchorFloor(bestExisting = 0f, gainPercent = 25f))
    }

    /**
     * A candidate that ties the best standing anchor exactly is REFUSED by the floor, and one a hair above it
     * passes: the boundary the module's economics live on.
     */
    @Test
    fun `an equal new candidate does not clear the floor`() {
        val floor = newAnchorFloor(bestExisting = 20f, gainPercent = 25f)
        assertFalse(20f >= floor)
        assertTrue(25.1f >= floor)
    }
}
