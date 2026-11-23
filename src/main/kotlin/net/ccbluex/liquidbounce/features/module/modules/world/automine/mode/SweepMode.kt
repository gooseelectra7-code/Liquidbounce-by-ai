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

package net.ccbluex.liquidbounce.features.module.modules.world.automine.mode

import net.ccbluex.liquidbounce.features.module.modules.world.automine.AutoMineDebug
import net.ccbluex.liquidbounce.features.module.modules.world.automine.target.OreTracker
import net.ccbluex.liquidbounce.features.module.modules.world.automine.target.TargetPicker
import net.minecraft.core.BlockPos

/**
 * Mines the ore the client knows about, nearest first.
 *
 * ## The principle, in one sentence
 *
 * **Go to the nearest block on the target list that is inside the bounds and take it; when there is
 * none left, stand still.**
 *
 * This is the mode that makes the module a miner rather than a hole-digger, and the only thing it adds
 * to that sentence is a choice about honesty:
 *
 * - **[exposedOnly] off** — anything the index knows about counts, buried or not, and the route is
 *   allowed to tunnel to it. Efficient, and openly what it is: no player finds diamonds through forty
 *   blocks of deepslate.
 * - **[exposedOnly] on** — only ore with a face open to the air, i.e. only what somebody walking that
 *   cave would have spotted. The route still digs, because the last block between the cave wall and the
 *   ore is ordinary mining, but nothing is chosen that could not have been seen.
 *
 * When the ore within range runs out this mode stops, on purpose. Going to *look* for more — walking
 * the caves, tunnelling on a heading — is the cave mode's job, and pretending to do it here would be
 * two behaviours under one name.
 */
object SweepMode : AutoMineMode("Sweep") {

    /**
     * Only take ore with a face open to the air.
     *
     * Off by default in this mode, because this is the mode for "get me the ore, I know how you know
     * where it is". The cave mode is the one whose whole premise is looking like a player.
     */
    private val exposedOnly by boolean("ExposedOnly", false)

    /** How far the miner is willing to travel for one block. */
    private val range by int("Range", 96, 8..256, "blocks")

    /** The route may tunnel: an ore two blocks inside a wall is unreachable otherwise. */
    override val routeMayDig: Boolean get() = true

    override fun onStarted() {
        TargetPicker.clear()
        AutoMineDebug.event("sweep started: range=$range exposedOnly=$exposedOnly indexed=${OreTracker.size()}")
    }

    override fun nextTarget(): BlockPos? = TargetPicker.best(range.toDouble(), exposedOnly)

    override fun onGaveUp(pos: BlockPos) {
        TargetPicker.refuse(pos)
    }
}
