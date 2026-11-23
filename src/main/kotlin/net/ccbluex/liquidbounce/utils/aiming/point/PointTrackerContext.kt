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
package net.ccbluex.liquidbounce.utils.aiming.point

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Most-recent `PointTracker.findPoint(...)` invocation summary — written by the tracker, read by
 * cross-cutting consumers (debug recorders, learned-aim sampler) that aren't inside the tracker's
 * tree and can't see its per-instance fields.
 *
 * Three `PointTracker` instances exist (Aimbot/AutoRod/KillAura), but the player only attacks
 * after the active module's `findPoint` resolved — so "most recent across all trackers" is the
 * right value for any consumer reading at attack time.
 */
object PointTrackerContext {
    @Volatile
    var lastEntity: Entity? = null

    @Volatile
    var lastEyes: Vec3? = null

    @Volatile
    var lastResolvedPoint: PointInsideBox? = null
}
