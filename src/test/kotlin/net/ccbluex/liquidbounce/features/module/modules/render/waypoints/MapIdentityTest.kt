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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Waypoints and the collected map are keyed by the server address; every spelling of one address
 * must land in one folder, and no address may escape the storage root.
 */
class MapIdentityTest {

    @Test
    fun `spellings of one address share a key`() {
        val key = MapIdentity.normalizeAddress("play.example.net")
        assertEquals(key, MapIdentity.normalizeAddress("Play.Example.NET"))
        assertEquals(key, MapIdentity.normalizeAddress("play.example.net."))
        assertEquals(key, MapIdentity.normalizeAddress("play.example.net:25565"))
        assertEquals(key, MapIdentity.normalizeAddress("  play.example.net  "))
    }

    @Test
    fun `a non-default port is a different server`() {
        assertNotEquals(
            MapIdentity.normalizeAddress("play.example.net"),
            MapIdentity.normalizeAddress("play.example.net:25566"),
        )
    }

    @Test
    fun `keys are file-system safe`() {
        assertEquals("play.example.net_25566", MapIdentity.normalizeAddress("play.example.net:25566"))
        assertEquals("___1_", MapIdentity.sanitize("[::1]"))
        assertEquals("a_b_c", MapIdentity.sanitize("a/b\\c"))
    }

    @Test
    fun `dot-only names cannot climb out of the root`() {
        assertEquals("unknown", MapIdentity.sanitize(".."))
        assertEquals("unknown", MapIdentity.sanitize("."))
        assertEquals("unknown", MapIdentity.sanitize(""))
    }
}
