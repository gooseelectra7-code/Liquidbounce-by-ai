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

package net.ccbluex.liquidbounce.features.module.modules.world.automine.target

import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavBlockClass
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Is this block one a passing player could actually have seen?
 *
 * The difference between a miner and an X-ray: an ore with a face open to the air is one anybody
 * walking the cave would spot, while an ore buried in stone is one only the client knows about. Which
 * of the two the module is allowed to take is a setting; this is how the question is answered.
 *
 * Answered from the navigation cache rather than from the world, for two reasons. It is safe off the
 * main thread, and it treats unloaded space as solid — `NavBlockClass.isPassable` deliberately refuses
 * `UNKNOWN` — so an ore against the edge of the loaded world is not mistaken for one against a cave.
 *
 * The client already had a test like this, in XRay (`ModuleXRay.shouldRender`), and it is not reusable:
 * private to that module, main-thread, and phrased as "is the neighbour a redstone conductor", which
 * counts glass and water as open air.
 */
object OreVisibility {

    /** Does any of the six faces touch a cell a body could pass through? */
    fun isExposed(pos: BlockPos): Boolean {
        for (side in Direction.entries) {
            val navClass = NavWorld.navClassAt(
                pos.x + side.stepX,
                pos.y + side.stepY,
                pos.z + side.stepZ,
            )
            if (NavBlockClass.isPassable(navClass)) return true
        }
        return false
    }
}
