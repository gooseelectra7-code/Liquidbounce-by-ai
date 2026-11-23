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

package net.ccbluex.liquidbounce.features.module.modules.movement.nav

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.ElytraGuidance
import net.ccbluex.liquidbounce.utils.navigation.pathing.exec.NavPath
import net.ccbluex.liquidbounce.utils.navigation.pathing.move.NavMove
import net.ccbluex.liquidbounce.utils.navigation.pathing.plan.ElytraRoute
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the route as a thin line of light.
 *
 * The old picture was a translucent cube per node: it showed the lattice the search worked on, not
 * the journey anyone was going to take, and every step of level ground was shouted about as loudly
 * as the jump over the ravine.
 *
 * ## Why light rather than a ribbon
 *
 * The obvious replacement — a wide translucent strip — is what makes overlay geometry look like fog
 * on the lens. Translucency *removes* what is behind it, so a strip laid over the world greys the
 * world out and the two fight; stack two passes of it and the result is the milky look of a badly
 * done ESP. Light does the opposite. A narrow, nearly opaque core carries the shape crisply, and a
 * wider halo around it is drawn with additive blending, which only ever *adds* to what is already
 * on screen: it brightens the ground under it instead of veiling it, and where the route doubles
 * back on itself the crossing glows brighter rather than turning to mud.
 *
 * ## What carries meaning
 *
 * - **Hue** is the kind of move, so a dug tunnel or a bridged gap is legible along its whole length.
 * - **Brightness** is time: what has been walked dims, and the far end dissolves rather than
 *   stopping dead, because a best-effort route really does continue past what was planned.
 * - **Sparks** run along the line towards the destination. Direction, without arrows.
 * - **Markers** appear only where something happens — a jump, a drop, a dig, a block placed.
 */
object NavPathRenderer {

    fun render(env: WorldRenderEnvironment, path: NavPath, progress: Float) {
        val ribbon = NavRouteRibbon.of(path) ?: return
        if (ribbon.isEmpty) return

        val walked = ribbon.length * progress
        val colorOf = { index: Int -> moveColorAt(path, ribbon.sourceIndex[index]) }

        env.withPositionRelativeToCamera {
            drawGlow(this, ribbon, walked, colorOf)
            drawCore(this, ribbon, walked, colorOf)
            drawMarkers(this, path, progress)
        }
        drawDestination(env, path.centerAt(path.size - 1))
    }

    /**
     * Draws a glide the same way, minus the parts that only mean something on the ground.
     *
     * A flight has no lattice to smooth and no per-block moves to mark; the line and the ring at the
     * end are the whole picture.
     */
    fun renderFlight(env: WorldRenderEnvironment, route: ElytraRoute) {
        val ribbon = NavRouteRibbon.of(route.waypoints) ?: return
        val colorOf = { _: Int -> FLIGHT_COLOR }

        env.withPositionRelativeToCamera {
            drawGlow(this, ribbon, walked = 0.0, colorOf = colorOf)
            drawCore(this, ribbon, walked = 0.0, colorOf = colorOf)
        }
        drawTouchdownRing(env, route.waypoints.last())
    }

    // -----------------------------------------------------------------------
    //  The line itself, in two passes
    // -----------------------------------------------------------------------

    /**
     * The halo: wide, dim, and additive, so it lights the ground rather than veiling it.
     *
     * Drawn first and depth-tested, which puts it behind the core and lets terrain occlude both.
     */
    private fun drawGlow(
        env: WorldRenderEnvironment,
        ribbon: NavRouteRibbon,
        walked: Double,
        colorOf: (Int) -> Color4b,
    ) {
        val phase = sparkPhase()

        env.drawCustomMesh(ClientRenderPipelines.GlowTriangles) { pose ->
            forEachSegment(ribbon, GLOW_HALF_WIDTH) { index, near, far, offsetX, offsetZ ->
                val nearColor = strandColor(ribbon, index, walked, phase, colorOf, GLOW_ALPHA, toWhite = 0.0)
                val farColor = strandColor(ribbon, index + 1, walked, phase, colorOf, GLOW_ALPHA, toWhite = 0.0)
                quadAsTriangles(this, pose, near, far, offsetX, offsetZ, nearColor, farColor)
            }
        }
    }

    /** The core: narrow, nearly white, nearly opaque. This is the line one actually reads. */
    private fun drawCore(
        env: WorldRenderEnvironment,
        ribbon: NavRouteRibbon,
        walked: Double,
        colorOf: (Int) -> Color4b,
    ) {
        val phase = sparkPhase()

        env.drawCustomMesh(ClientRenderPipelines.Quads) { pose ->
            forEachSegment(ribbon, CORE_HALF_WIDTH) { index, near, far, offsetX, offsetZ ->
                val nearColor = strandColor(ribbon, index, walked, phase, colorOf, CORE_ALPHA, CORE_WHITENESS)
                val farColor = strandColor(ribbon, index + 1, walked, phase, colorOf, CORE_ALPHA, CORE_WHITENESS)

                addVertex(pose, (near.x - offsetX).toFloat(), near.y.toFloat(), (near.z - offsetZ).toFloat())
                    .setColor(nearColor)
                addVertex(pose, (near.x + offsetX).toFloat(), near.y.toFloat(), (near.z + offsetZ).toFloat())
                    .setColor(nearColor)
                addVertex(pose, (far.x + offsetX).toFloat(), far.y.toFloat(), (far.z + offsetZ).toFloat())
                    .setColor(farColor)
                addVertex(pose, (far.x - offsetX).toFloat(), far.y.toFloat(), (far.z - offsetZ).toFloat())
                    .setColor(farColor)
            }
        }
    }

    /**
     * Walks the segments, handing each one its endpoints and the sideways offset that gives it width.
     *
     * The offset is horizontal whatever the slope, so the line lies on the ground it crosses instead
     * of banking with it.
     */
    private inline fun forEachSegment(
        ribbon: NavRouteRibbon,
        halfWidth: Double,
        emit: (index: Int, near: Vec3, far: Vec3, offsetX: Double, offsetZ: Double) -> Unit,
    ) {
        for (index in 0 until ribbon.points.size - 1) {
            val near = ribbon.points[index]
            val far = ribbon.points[index + 1]

            val dx = far.x - near.x
            val dz = far.z - near.z
            val flat = sqrt(dx * dx + dz * dz)
            if (flat < MIN_SEGMENT) continue

            emit(index, near, far, -dz / flat * halfWidth, dx / flat * halfWidth)
        }
    }

    private fun quadAsTriangles(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        near: Vec3,
        far: Vec3,
        offsetX: Double,
        offsetZ: Double,
        nearColor: Int,
        farColor: Int,
    ) {
        val nearLeftX = (near.x - offsetX).toFloat()
        val nearLeftZ = (near.z - offsetZ).toFloat()
        val nearRightX = (near.x + offsetX).toFloat()
        val nearRightZ = (near.z + offsetZ).toFloat()
        val farLeftX = (far.x - offsetX).toFloat()
        val farLeftZ = (far.z - offsetZ).toFloat()
        val farRightX = (far.x + offsetX).toFloat()
        val farRightZ = (far.z + offsetZ).toFloat()

        val nearY = near.y.toFloat()
        val farY = far.y.toFloat()

        consumer.addVertex(pose, nearLeftX, nearY, nearLeftZ).setColor(nearColor)
        consumer.addVertex(pose, nearRightX, nearY, nearRightZ).setColor(nearColor)
        consumer.addVertex(pose, farRightX, farY, farRightZ).setColor(farColor)

        consumer.addVertex(pose, farRightX, farY, farRightZ).setColor(farColor)
        consumer.addVertex(pose, farLeftX, farY, farLeftZ).setColor(farColor)
        consumer.addVertex(pose, nearLeftX, nearY, nearLeftZ).setColor(nearColor)
    }

    /**
     * Colour of one point of one strand.
     *
     * Everything that varies along the route is folded in here: the hue of the move it belongs to,
     * how much of it is behind us, how near the end it is, and whether a spark is passing over it.
     */
    @Suppress("LongParameterList")
    private fun strandColor(
        ribbon: NavRouteRibbon,
        index: Int,
        walked: Double,
        phase: Double,
        colorOf: (Int) -> Color4b,
        baseAlpha: Double,
        toWhite: Double,
    ): Int {
        val distance = ribbon.travelled[index]
        val base = colorOf(index)

        // Behind us: dim, but still there. Seeing where the route came from is how a wrong turn
        // gets noticed.
        val progressFade = if (distance < walked) BEHIND_ALPHA else 1.0

        // The far end dissolves instead of stopping, because a best-effort route really does carry
        // on past the point it was planned to.
        val tailFade = ((ribbon.length - distance) / TAIL_FADE_LENGTH).coerceAtMost(1.0)

        val intensity = baseAlpha * progressFade * tailFade * (1.0 + SPARK_GAIN * spark(distance, phase))

        return Color4b(
            lerpChannel(base.r, WHITE, toWhite),
            lerpChannel(base.g, WHITE, toWhite),
            lerpChannel(base.b, WHITE, toWhite),
            (base.a * intensity).toInt().coerceIn(0, 255),
        ).argb
    }

    /**
     * A short bright dash travelling towards the destination.
     *
     * Narrow on purpose. A slow swell over the whole line reads as a wash and takes the crispness
     * with it; a tight band that runs along the route reads as something moving through it.
     */
    private fun spark(distance: Double, phase: Double): Double {
        val along = (distance / SPARK_SPACING - phase).let { it - kotlin.math.floor(it) }
        val fromCentre = (along - 0.5) * SPARK_SHARPNESS
        return exp(-fromCentre * fromCentre)
    }

    private fun lerpChannel(from: Int, to: Int, amount: Double): Int =
        (from + (to - from) * amount).toInt().coerceIn(0, 255)

    /** Colour of the move arriving at a node; the first node borrows the one leaving it. */
    private fun moveColorAt(path: NavPath, index: Int): Color4b {
        if (path.moveCount == 0) return NavMove.colorOf(NavMove.WALK)
        return NavMove.colorOf(path.kindAt((index - 1).coerceIn(0, path.moveCount - 1)))
    }

    // -----------------------------------------------------------------------
    //  Markers
    // -----------------------------------------------------------------------

    /**
     * A small light wherever the route does something other than walk.
     *
     * The point of marking anything is that it stands out, and a marker on every block of level
     * ground marks nothing at all. Jumps, drops, digging, building and climbing are the moments
     * where a plan can be wrong in a way worth seeing before it happens.
     */
    private fun drawMarkers(env: WorldRenderEnvironment, path: NavPath, progress: Float) {
        val walkedMoves = (path.moveCount * progress).toInt()

        env.drawCustomMesh(ClientRenderPipelines.GlowTriangles) { pose ->
            for (move in 0 until path.moveCount) {
                val kind = path.kindAt(move)
                if (!isWorthMarking(kind)) continue

                val base = NavMove.colorOf(kind)
                val fade = if (move < walkedMoves) BEHIND_ALPHA else 1.0
                val color = Color4b(base.r, base.g, base.b, (base.a * fade).toInt().coerceIn(0, 255)).argb

                val at = path.centerAt(move + 1)
                addDiamond(this, pose, at.x, at.y + MARKER_LIFT, at.z, color)
            }
        }
    }

    private fun isWorthMarking(kind: Int): Boolean = when (kind) {
        NavMove.WALK, NavMove.DIAGONAL, NavMove.SWIM, NavMove.DESCEND -> false
        else -> true
    }

    /** A flat diamond of light: readable from above, never mistaken for a block. */
    @Suppress("LongParameterList")
    private fun addDiamond(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        x: Double,
        y: Double,
        z: Double,
        color: Int,
    ) {
        val r = MARKER_RADIUS
        val cx = x.toFloat()
        val cy = y.toFloat()
        val cz = z.toFloat()

        consumer.addVertex(pose, cx, cy, (z - r).toFloat()).setColor(color)
        consumer.addVertex(pose, (x + r).toFloat(), cy, cz).setColor(color)
        consumer.addVertex(pose, cx, cy, (z + r).toFloat()).setColor(color)

        consumer.addVertex(pose, cx, cy, (z + r).toFloat()).setColor(color)
        consumer.addVertex(pose, (x - r).toFloat(), cy, cz).setColor(color)
        consumer.addVertex(pose, cx, cy, (z - r).toFloat()).setColor(color)
    }

    // -----------------------------------------------------------------------
    //  Destination
    // -----------------------------------------------------------------------

    /**
     * Where the route ends: a thin ring on the ground and a beam standing over it.
     *
     * The beam is the one thing here drawn through terrain, and it is drawn dim and narrow for that
     * reason. Where the route is going is the single question the line itself cannot answer once its
     * far end is over a hill — and a marker that the hill hides is no answer at all.
     */
    private fun drawDestination(env: WorldRenderEnvironment, destination: Vec3) {
        val breath = 0.7 + 0.3 * sin(sparkPhase() * GOAL_BREATH)
        val ringColor = GOAL_COLOR.with(a = (GOAL_COLOR.a * breath).toInt().coerceIn(0, 255)).argb

        env.withPositionRelativeToCamera {
            drawCustomMesh(ClientRenderPipelines.Quads) { pose ->
                for (segment in 0 until GOAL_SEGMENTS) {
                    val from = segment * 2.0 * PI / GOAL_SEGMENTS
                    val to = (segment + 1) * 2.0 * PI / GOAL_SEGMENTS
                    addRingQuad(this, pose, destination, from, to, ringColor)
                }
            }

            drawCustomMesh(ClientRenderPipelines.GlowTrianglesThroughWalls) { pose ->
                addBeam(this, pose, destination, breath)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun addRingQuad(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        centre: Vec3,
        from: Double,
        to: Double,
        color: Int,
    ) {
        val inner = GOAL_RADIUS - GOAL_RING_WIDTH
        val outer = GOAL_RADIUS
        val y = (centre.y + NavRouteRibbon.LIFT).toFloat()

        consumer.addVertex(pose, ringX(centre, from, inner), y, ringZ(centre, from, inner)).setColor(color)
        consumer.addVertex(pose, ringX(centre, from, outer), y, ringZ(centre, from, outer)).setColor(color)
        consumer.addVertex(pose, ringX(centre, to, outer), y, ringZ(centre, to, outer)).setColor(color)
        consumer.addVertex(pose, ringX(centre, to, inner), y, ringZ(centre, to, inner)).setColor(color)
    }

    private fun ringX(centre: Vec3, angle: Double, radius: Double): Float =
        (centre.x + cos(angle) * radius).toFloat()

    private fun ringZ(centre: Vec3, angle: Double, radius: Double): Float =
        (centre.z + sin(angle) * radius).toFloat()

    /**
     * Two crossed vertical panels of light, fading out with height.
     *
     * Crossed so the beam reads from any direction without having to face the camera, and faded at
     * the top so it looks like light leaving rather than a post standing.
     */
    private fun addBeam(consumer: VertexConsumer, pose: PoseStack.Pose, centre: Vec3, breath: Double) {
        val bottom = (centre.y + NavRouteRibbon.LIFT).toFloat()
        val top = (centre.y + GOAL_BEAM_HEIGHT).toFloat()
        val half = GOAL_BEAM_WIDTH / 2.0

        val low = GOAL_COLOR.with(a = (GOAL_BEAM_ALPHA * breath).toInt().coerceIn(0, 255)).argb
        val high = GOAL_COLOR.with(a = 0).argb

        for (panel in 0 until 2) {
            val offsetX = if (panel == 0) half else 0.0
            val offsetZ = if (panel == 0) 0.0 else half

            val leftX = (centre.x - offsetX).toFloat()
            val leftZ = (centre.z - offsetZ).toFloat()
            val rightX = (centre.x + offsetX).toFloat()
            val rightZ = (centre.z + offsetZ).toFloat()

            consumer.addVertex(pose, leftX, bottom, leftZ).setColor(low)
            consumer.addVertex(pose, rightX, bottom, rightZ).setColor(low)
            consumer.addVertex(pose, rightX, top, rightZ).setColor(high)

            consumer.addVertex(pose, rightX, top, rightZ).setColor(high)
            consumer.addVertex(pose, leftX, top, leftZ).setColor(high)
            consumer.addVertex(pose, leftX, bottom, leftZ).setColor(low)
        }
    }

    /**
     * The circle a glide is aiming to come down in.
     *
     * A glider lands wherever its last turn leaves it, somewhere in a circle around the place it was
     * aiming at — so a point would promise a precision the aircraft does not have, and make every
     * landing look like a miss.
     */
    private fun drawTouchdownRing(env: WorldRenderEnvironment, destination: Vec3) {
        env.withPositionRelativeToCamera {
            drawCustomMesh(ClientRenderPipelines.GlowTriangles) { pose ->
                for (segment in 0 until TOUCHDOWN_SEGMENTS) {
                    val from = segment * 2.0 * PI / TOUCHDOWN_SEGMENTS
                    val to = (segment + 1) * 2.0 * PI / TOUCHDOWN_SEGMENTS
                    addTouchdownArc(this, pose, destination, from, to)
                }
            }
        }
    }

    private fun addTouchdownArc(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        centre: Vec3,
        from: Double,
        to: Double,
    ) {
        val inner = ElytraGuidance.TOUCHDOWN_RADIUS - TOUCHDOWN_WIDTH
        val outer = ElytraGuidance.TOUCHDOWN_RADIUS
        val y = (centre.y + NavRouteRibbon.LIFT).toFloat()
        val color = FLIGHT_COLOR.with(a = TOUCHDOWN_ALPHA).argb

        consumer.addVertex(pose, ringX(centre, from, inner), y, ringZ(centre, from, inner)).setColor(color)
        consumer.addVertex(pose, ringX(centre, from, outer), y, ringZ(centre, from, outer)).setColor(color)
        consumer.addVertex(pose, ringX(centre, to, outer), y, ringZ(centre, to, outer)).setColor(color)

        consumer.addVertex(pose, ringX(centre, to, outer), y, ringZ(centre, to, outer)).setColor(color)
        consumer.addVertex(pose, ringX(centre, to, inner), y, ringZ(centre, to, inner)).setColor(color)
        consumer.addVertex(pose, ringX(centre, from, inner), y, ringZ(centre, from, inner)).setColor(color)
    }

    // -----------------------------------------------------------------------
    //  Timing
    // -----------------------------------------------------------------------

    /** Phase of the travelling sparks, from the wall clock so they run at one rate everywhere. */
    private fun sparkPhase(): Double =
        (System.currentTimeMillis() % SPARK_PERIOD_MS) / SPARK_PERIOD_MS.toDouble()

    // -----------------------------------------------------------------------
    //  Tuning
    // -----------------------------------------------------------------------

    /** Half the width of the bright core, in blocks. Thin: this is a line, not a carpet. */
    private const val CORE_HALF_WIDTH = 0.035

    /** Half the width of the halo around it. */
    private const val GLOW_HALF_WIDTH = 0.17

    /** How far the core is pulled toward white. Colour lives in the glow; the core is the light. */
    private const val CORE_WHITENESS = 0.72

    private const val CORE_ALPHA = 0.95

    /** Dim: additive light stacks, and a bright halo is a smear. */
    private const val GLOW_ALPHA = 0.3

    private const val WHITE = 255

    /** How dim the part already walked becomes. */
    private const val BEHIND_ALPHA = 0.25

    /** Blocks over which the far end fades out entirely. */
    private const val TAIL_FADE_LENGTH = 12.0

    /** Blocks between sparks. */
    private const val SPARK_SPACING = 9.0

    /** Higher is a tighter dash. About one and a half blocks of bright at this value. */
    private const val SPARK_SHARPNESS = 9.0

    /** How much brighter a spark makes the strand under it. */
    private const val SPARK_GAIN = 1.1

    private const val SPARK_PERIOD_MS = 1600L

    /** Below this a segment is shorter than the line is wide, and its direction is noise. */
    private const val MIN_SEGMENT = 1.0E-4

    private const val MARKER_RADIUS = 0.26
    private const val MARKER_LIFT = 0.07

    private const val GOAL_RADIUS = 0.8
    private const val GOAL_RING_WIDTH = 0.09
    private const val GOAL_SEGMENTS = 32
    private const val GOAL_BREATH = 2.0 * PI
    private const val GOAL_BEAM_HEIGHT = 2.6
    private const val GOAL_BEAM_WIDTH = 0.45
    private const val GOAL_BEAM_ALPHA = 70.0

    private val GOAL_COLOR = Color4b(255, 226, 140, 235)

    /** Sky blue, so a flight reads as something other than a walk at a glance. */
    private val FLIGHT_COLOR = Color4b(130, 205, 255, 225)

    private const val TOUCHDOWN_SEGMENTS = 64
    private const val TOUCHDOWN_WIDTH = 0.25
    private const val TOUCHDOWN_ALPHA = 150
}
