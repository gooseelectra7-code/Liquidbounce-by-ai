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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry the guidance law is written in.
 *
 * Kept apart from the law itself so that reading [Guidance] is about interception rather than about
 * rotating vectors, and so none of this has to be re-derived anywhere else in the chase.
 */

/** Below this a vector is too short for its direction to mean anything. */
internal const val NEGLIGIBLE = 1.0e-4

internal val UP: Vec3 = Vec3(0.0, 1.0, 0.0)

internal fun Vec3.normalizeOrElse(fallback: Vec3): Vec3 =
    if (lengthSqr() < NEGLIGIBLE * NEGLIGIBLE) fallback else normalize()

internal fun angleBetween(a: Vec3, b: Vec3): Double = acos(a.dot(b).coerceIn(-1.0, 1.0))

/** Some unit vector at right angles to [v]. Which one does not matter; that it exists does. */
@Suppress("MagicNumber")
internal fun anyPerpendicular(v: Vec3): Vec3 {
    val candidate = if (abs(v.y) < 0.9) UP else Vec3(1.0, 0.0, 0.0)
    return v.cross(candidate).normalizeOrElse(Vec3(1.0, 0.0, 0.0))
}

/** Rodrigues rotation of [v] about the unit [axis] by [angle] radians. */
internal fun rotateAbout(v: Vec3, axis: Vec3, angle: Double): Vec3 {
    val c = cos(angle)
    val s = sin(angle)
    return v.scale(c)
        .add(axis.cross(v).scale(s))
        .add(axis.scale(axis.dot(v) * (1.0 - c)))
}

/**
 * Spherical interpolation between two unit directions.
 *
 * Linear interpolation is not good enough here. It shortens the vector near the midpoint and, for
 * near-opposite inputs, passes through zero, which in flight reads as a heading that stalls or flips
 * instead of sweeping round.
 */
internal fun slerp(from: Vec3, to: Vec3, t: Double): Vec3 {
    val amount = t.coerceIn(0.0, 1.0)
    if (amount <= 0.0) {
        return from
    }
    if (amount >= 1.0) {
        return to
    }

    val angle = angleBetween(from, to)
    if (angle < 1.0e-6) {
        return from
    }

    val axis = from.cross(to)
    return if (axis.lengthSqr() < NEGLIGIBLE * NEGLIGIBLE) {
        rotateAbout(from, anyPerpendicular(from), angle * amount)
    } else {
        rotateAbout(from, axis.normalize(), angle * amount)
    }
}

/**
 * The smooth 0-to-1 ramp between two edges.
 *
 * Every blend in the guidance law uses this rather than a threshold, which is what lets the law drop
 * the phase machine the previous module needed: a ramp whose value and slope are both continuous
 * cannot produce the boundary chatter that hysteresis exists to suppress.
 */
@Suppress("MagicNumber")
internal fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    if (edge1 <= edge0) {
        return if (x < edge0) 0.0 else 1.0
    }
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

internal fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Shortest signed way round from one yaw to another, in degrees. */
internal fun shortestYawDelta(from: Float, to: Float): Float {
    var delta = (to - from) % 360f
    if (delta >= 180f) {
        delta -= 360f
    }
    if (delta < -180f) {
        delta += 360f
    }
    return delta
}
