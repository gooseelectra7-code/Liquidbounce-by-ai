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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.ccbluex.liquidbounce.config.types.list.Tagged

/**
 * How the executor gets a bridge across a gap.
 *
 * The two are not variations on a theme; they are different trades. One is slow and cannot really
 * fail, the other is quick and depends on angles measured to a tenth of a degree — which is exactly
 * the choice worth leaving to whoever is running it.
 */
enum class NavBridgeStyle(override val tag: String) : Tagged {

    /**
     * Back out over the edge, sneaking, and build against the face behind.
     *
     * The reliable one, and the default. Sneaking removes timing from the problem entirely: the body
     * can stand at the limit of its overhang for as long as it likes with the face in plain view, so
     * a slow rotation, a dropped packet or a stutter costs a moment rather than the route. It walks
     * backwards, and backwards cannot sprint, so it is about a third of walking pace.
     */
    SNEAK("Sneak"),

    /**
     * The godbridge stance: face back along the route, turned forty-five degrees, nose steeply down,
     * and keep sprinting forwards.
     *
     * Quick, because nothing ever stops — the diagonal is what widens the moment in which the face
     * below is reachable, so blocks go down without the body ever giving up its speed. The angles
     * are not adjustable-looking numbers; they are the ones the client's own scaffold uses, and a
     * degree out of stance means placing nothing at all. When the next tick would step off with
     * nothing placed, it jumps, which buys the tick back.
     */
    GOD_BRIDGE("GodBridge"),
}
