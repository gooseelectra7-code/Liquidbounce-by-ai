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
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorldReader

/**
 * Where a planner reads blocks from.
 *
 * In the game this is a [NavWorldReader] over the shared cache. In tests it is a hand-built voxel
 * grid, which is what lets the planners be checked against known-correct routes without a running
 * client.
 */
fun interface NavBlockSource {

    /** Packed navigation byte at a world position; [NavBlockClass.PACKED_UNKNOWN] when unloaded. */
    fun packed(x: Int, y: Int, z: Int): Byte
}

/** Reads the shared navigation cache through a chunk-caching cursor. */
fun NavWorldReader.asBlockSource(): NavBlockSource = NavBlockSource { x, y, z -> packed(x, y, z) }

/** Navigation class at a position. */
fun NavBlockSource.navClass(x: Int, y: Int, z: Int): Int = NavBlockClass.navClassOf(packed(x, y, z))

/** Dig bucket at a position. */
fun NavBlockSource.digBucket(x: Int, y: Int, z: Int): Int = NavBlockClass.digBucketOf(packed(x, y, z))
