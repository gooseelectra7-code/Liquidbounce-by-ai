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

package net.ccbluex.liquidbounce.features.module.modules.world.automine

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.core.BlockPos

/**
 * How far from where it started the miner is allowed to work.
 *
 * The anchor is taken once, when the module is switched on, and never re-read. That is the whole point
 * of it: a limit measured from wherever the player currently stands is not a limit, it is a leash that
 * moves with the dog — the miner would follow ore for a thousand blocks, each step legal because the
 * centre came along too.
 *
 * [contains] is called from the block tracker, which runs on chunk-scanner threads, so the anchor is
 * volatile and nothing here touches the player except at the moment of setting it.
 */
class AutoMineBounds : ValueGroup("Bounds") {

    enum class Shape(override val tag: String) : Tagged {
        /** Stay within [radius] blocks of where the module was switched on. */
        RADIUS("Radius"),

        /** Go wherever the ore is. */
        UNLIMITED("Unlimited"),
    }

    private val shape by enumChoice("Shape", Shape.RADIUS)
    private val radius by int("Radius", 96, 8..512, "blocks")

    /** Where the work started. Written on enable, read from any thread. */
    @Volatile
    private var anchor: BlockPos = BlockPos.ZERO

    /** True once [anchor] means something. */
    @Volatile
    private var anchored = false

    fun anchorHere() {
        anchor = player.blockPosition()
        anchored = true
    }

    fun forget() {
        anchored = false
    }

    /** Where the limit is measured from, for the log and the chat summary. */
    fun anchorPos(): BlockPos = anchor

    fun describe(): String = when (shape) {
        Shape.UNLIMITED -> "unlimited"
        Shape.RADIUS -> "$radius blocks of ${anchor.x},${anchor.y},${anchor.z}"
    }

    /**
     * Is this block within the limit?
     *
     * Measured across the ground only. Height is not distance for a miner: everything it wants is
     * below it, and a vertical limit would refuse the ore under its feet while allowing the ore a
     * hundred blocks away.
     */
    fun contains(x: Int, z: Int): Boolean {
        if (shape == Shape.UNLIMITED) return true
        if (!anchored) return false

        val dx = (x - anchor.x).toLong()
        val dz = (z - anchor.z).toLong()
        val limit = radius.toLong()
        return dx * dx + dz * dz <= limit * limit
    }

    fun contains(pos: BlockPos): Boolean = contains(pos.x, pos.z)
}
