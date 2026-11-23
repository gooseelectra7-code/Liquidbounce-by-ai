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

package net.ccbluex.liquidbounce.utils.navigation.pathing.plan

import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass

/**
 * A hand-built block of terrain for testing planners without a Minecraft world.
 *
 * Everything outside the box reads as [NavBlockClass.UNKNOWN], the same as unloaded chunks in the
 * real cache — so tests also exercise the rule that the search must never plan through terrain it
 * has not seen.
 */
internal class NavTestTerrain(
    private val sizeX: Int,
    private val sizeY: Int,
    private val sizeZ: Int,
    fill: Int = NavBlockClass.AIR,
) : NavBlockSource {

    private val data = ByteArray(sizeX * sizeY * sizeZ) { NavBlockClass.pack(fill, DEFAULT_DIG_BUCKET) }

    override fun packed(x: Int, y: Int, z: Int): Byte {
        if (x !in 0 until sizeX || y !in 0 until sizeY || z !in 0 until sizeZ) {
            return NavBlockClass.PACKED_UNKNOWN
        }
        return data[index(x, y, z)]
    }

    fun set(x: Int, y: Int, z: Int, navClass: Int, digBucket: Int = DEFAULT_DIG_BUCKET) {
        if (x !in 0 until sizeX || y !in 0 until sizeY || z !in 0 until sizeZ) return
        data[index(x, y, z)] = NavBlockClass.pack(navClass, digBucket)
    }

    /** Lays a solid floor across the whole box at [y]. */
    fun floor(y: Int, navClass: Int = NavBlockClass.SOLID_FULL, digBucket: Int = DEFAULT_DIG_BUCKET) {
        for (x in 0 until sizeX) {
            for (z in 0 until sizeZ) {
                set(x, y, z, navClass, digBucket)
            }
        }
    }

    /** Fills an inclusive box. */
    @Suppress("LongParameterList")
    fun box(
        fromX: Int, fromY: Int, fromZ: Int,
        toX: Int, toY: Int, toZ: Int,
        navClass: Int,
        digBucket: Int = DEFAULT_DIG_BUCKET,
    ) {
        for (x in fromX..toX) {
            for (y in fromY..toY) {
                for (z in fromZ..toZ) {
                    set(x, y, z, navClass, digBucket)
                }
            }
        }
    }

    private fun index(x: Int, y: Int, z: Int): Int = (x * sizeY + y) * sizeZ + z

    companion object {
        /** Stone-ish hardness, so digging is possible but not free. */
        const val DEFAULT_DIG_BUCKET = 4
    }
}
