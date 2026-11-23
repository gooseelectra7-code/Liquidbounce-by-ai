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

package net.ccbluex.liquidbounce.utils.navigation.pathing.move

import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * How the player gets from one node to the next, encoded in a single byte.
 *
 * The old engine allocated a `Movement` object for every candidate edge it considered — tens per
 * expanded node, hundreds of thousands per search — and threw nearly all of them away. Here an edge
 * carries only its kind and direction, which is everything the executor needs; magnitude (how far a
 * fall drops, how wide a jump reaches) is read back from the two positions, which the path already
 * stores. Concrete move objects are built once, for the handful of edges that made the final route.
 *
 * Layout is `kind shl 3 or direction`, so both fit in a positive byte.
 */
object NavMove {

    // -----------------------------------------------------------------------
    //  Kinds
    // -----------------------------------------------------------------------

    /** Cardinal step on the level. */
    const val WALK = 0

    /** Diagonal step on the level. */
    const val DIAGONAL = 1

    /** Step up one block. */
    const val ASCEND = 2

    /** Step down one block. */
    const val DESCEND = 3

    /** Drop two or more blocks. Magnitude comes from the position delta. */
    const val FALL = 4

    /** Jump a gap. Magnitude comes from the position delta. */
    const val PARKOUR = 5

    /** Ladder or vine, up or down depending on the position delta. */
    const val CLIMB = 6

    /** Move through water, in any of the three axes. */
    const val SWIM = 7

    /** Break what is in the way, then step in. */
    const val DIG = 8

    /** Break the block underfoot and drop into the hole. */
    const val DIG_DOWN = 9

    /** Break the ceiling and pillar up through it. */
    const val DIG_UP = 10

    /** Jump and place a block underfoot. */
    const val PILLAR = 11

    /** Bridge: place support, then step onto it. */
    const val PLACE = 12

    /** Number of distinct kinds. */
    const val KIND_COUNT = 13

    // -----------------------------------------------------------------------
    //  Directions
    // -----------------------------------------------------------------------

    const val NORTH = 0
    const val EAST = 1
    const val SOUTH = 2
    const val WEST = 3
    const val NORTH_EAST = 4
    const val SOUTH_EAST = 5
    const val SOUTH_WEST = 6
    const val NORTH_WEST = 7

    /** X offset of each direction index. */
    @JvmField
    val OFFSET_X = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)

    /** Z offset of each direction index. */
    @JvmField
    val OFFSET_Z = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)

    /** The four cardinal direction indices. */
    @JvmField
    val CARDINALS = intArrayOf(NORTH, EAST, SOUTH, WEST)

    /** The four diagonal direction indices. */
    @JvmField
    val DIAGONALS = intArrayOf(NORTH_EAST, SOUTH_EAST, SOUTH_WEST, NORTH_WEST)

    /** Direction used by moves that only travel vertically. */
    const val VERTICAL = NORTH

    // -----------------------------------------------------------------------
    //  Encoding
    // -----------------------------------------------------------------------

    fun encode(kind: Int, direction: Int): Byte = ((kind shl 3) or (direction and 0x7)).toByte()

    fun kindOf(move: Byte): Int = (move.toInt() shr 3) and 0xF

    fun directionOf(move: Byte): Int = move.toInt() and 0x7

    /** Is this a diagonal step, which needs the anti-corner-cut checks? */
    fun isDiagonal(direction: Int): Boolean = direction >= NORTH_EAST

    /** Does this kind change the world, so the executor has to mine or build before moving? */
    fun mutatesWorld(kind: Int): Boolean = when (kind) {
        DIG, DIG_DOWN, DIG_UP, PILLAR, PLACE -> true
        else -> false
    }

    /** Does executing this kind consume a block from the inventory? */
    fun consumesBlock(kind: Int): Boolean = when (kind) {
        PILLAR, PLACE, DIG_UP -> true
        else -> false
    }

    /** How many blocks a whole route would have to place to be walked. */
    fun placementsIn(moves: ByteArray): Int {
        var total = 0
        for (move in moves) {
            if (consumesBlock(kindOf(move))) total++
        }
        return total
    }

    /** Human-readable name, for the debug overlay and path rendering. */
    fun nameOf(kind: Int): String = when (kind) {
        WALK -> "walk"
        DIAGONAL -> "diagonal"
        ASCEND -> "ascend"
        DESCEND -> "descend"
        FALL -> "fall"
        PARKOUR -> "parkour"
        CLIMB -> "climb"
        SWIM -> "swim"
        DIG -> "dig"
        DIG_DOWN -> "dig down"
        DIG_UP -> "dig up"
        PILLAR -> "pillar"
        PLACE -> "place"
        else -> "unknown"
    }

    /** Colour each kind renders in, so a glance at the path shows what it is about to do. */
    fun colorOf(kind: Int): Color4b = when (kind) {
        WALK -> Color4b(240, 240, 240, 200)
        DIAGONAL -> Color4b(220, 220, 255, 200)
        ASCEND -> Color4b(100, 200, 255, 220)
        DESCEND -> Color4b(255, 180, 80, 220)
        FALL -> Color4b(255, 120, 30, 220)
        PARKOUR -> Color4b(80, 255, 220, 230)
        CLIMB -> Color4b(80, 220, 180, 220)
        SWIM -> Color4b(60, 120, 255, 200)
        DIG -> Color4b(220, 60, 60, 230)
        DIG_DOWN -> Color4b(200, 80, 40, 230)
        DIG_UP -> Color4b(200, 40, 80, 230)
        PILLAR -> Color4b(60, 200, 80, 230)
        PLACE -> Color4b(80, 220, 100, 220)
        else -> Color4b(160, 160, 160, 180)
    }
}
