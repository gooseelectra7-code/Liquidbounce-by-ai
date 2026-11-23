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

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.module.modules.world.automine.ModuleAutoMine
import net.minecraft.core.BlockPos

/**
 * What a mode of AutoMine has to be able to answer.
 *
 * A mode decides *what* is worth breaking and nothing else — walking to it, aiming at it and
 * swinging at it belong to
 * [AutoMineJob][net.ccbluex.liquidbounce.features.module.modules.world.automine.AutoMineJob]. That
 * split is the whole reason a new mode is a small file: cave roaming, a branch mine and a marked-out
 * box differ entirely in what they choose and not at all in how it is dug.
 *
 * A [Mode] is itself an event listener whose `running` follows being selected, so a mode may keep
 * its own handlers and they will only fire while it is the active one.
 */
sealed class AutoMineMode(name: String) : Mode(name) {

    override val parent: ModeValueGroup<*>
        get() = ModuleAutoMine.modes

    /**
     * The block to work on now, or null when this mode has nothing to offer.
     *
     * Called on the main thread, once a target is needed — not every tick. Returning null is a
     * normal answer: it means "nothing here", and the module decides what to do about it.
     */
    abstract fun nextTarget(): BlockPos?

    /**
     * May the *route* break blocks on its way, or only walk through space that already exists?
     *
     * False by default, and the difference is visible from outside. A mode that marks out work —
     * a box, a tunnel, a staircase — owns every block that comes out of the ground, and a route
     * allowed to dig will happily open its own shortcut through the wall of that box on the way in.
     * A mode that goes to find things — ore anywhere in the loaded world — needs the opposite, since
     * the whole point is reaching something buried.
     */
    open val routeMayDig: Boolean get() = false

    /** Told after a target of ours was successfully broken. */
    open fun onMined(pos: BlockPos) {}

    /** Told when a target of ours could not be reached or broken, so it can be avoided for a while. */
    open fun onGaveUp(pos: BlockPos) {}

    /** Called when this mode becomes the active one, or the module is switched on with it selected. */
    open fun onStarted() {}

    /**
     * Switching to this mode counts as starting it.
     *
     * The module calls [onStarted] when it is switched on; this covers the other way in, where the
     * module was already running and the player changed the choice underneath it.
     */
    final override fun enable() {
        onStarted()
    }
}
