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

@file:Suppress("detekt:LongParameterList", "detekt:TooManyFunctions")

package net.ccbluex.liquidbounce.features.module.modules.combat.aura.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawCircleOutline
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.drawGradientCircle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Face mask selecting all six faces of a box. */
const val SOFT_ALL_FACES = 0b111111

/** Face mask selecting only the four VERTICAL faces, i.e. a column with an open top and bottom. */
const val SOFT_SIDE_FACES = 0b110011

/**
 * A box drawn as a volume instead of a decal.
 *
 * Three terms replace the flat paint of `drawBox` (`render/RenderShortcuts.kt:321-339`), all of them plain
 * geometry through the stock pipelines - no shader, no render target:
 *
 * 1. **Fresnel weighting.** A box face has a constant normal, so the grazing term can be evaluated per FACE on
 *    the CPU: `f = 1 - |dot(normal, toCamera)|` from the face centre, and the face's alpha is scaled by
 *    `mix(faceMin, 1, f^fresnelPower)`. Faces seen head-on go faint, faces seen edge-on stay bright, and because
 *    nothing is culled the far side of the box shines through the near side exactly where the eye expects depth.
 * 2. **Vertical gradient.** Every vertex is tinted by its own height inside [box], from full alpha at `minY` to
 *    [topRatio] at `maxY`, so no face is a single tone. The top and bottom faces fall out of the same formula
 *    (all four of their vertices share one height), which is why they need no special case.
 * 3. **Feathered outline.** The 12 edges become camera-facing ribbons ([addRibbon]) instead of one-pixel
 *    `Lines` primitives.
 *
 * [origin] is the world position that local `(0, 0, 0)` maps to; [box] is expressed in that local frame, exactly
 * like the `withPositionRelativeToCamera` + `drawBox` idiom this replaces (`render/RenderShortcuts.kt:112-118`).
 *
 * [faceMask] keeps only the faces whose bit is set ([SOFT_ALL_FACES], [SOFT_SIDE_FACES]). Note that the
 * `enabled = false` fallback goes through `drawBox`, which honours neither [faceMask] nor [topRatio]; a caller
 * that needs those in the hard path branches itself (see `AuraRenderer.drawBeam`).
 */
fun WorldRenderEnvironment.drawSoftBox(
    origin: Vec3,
    box: AABB,
    fill: Color4b?,
    outline: Color4b?,
    style: SoftStyle,
    faceMask: Int = SOFT_ALL_FACES,
    topRatio: Float = style.gradientTop,
) {
    if (!style.enabled) {
        withPositionRelativeToCamera(origin) { drawBox(box, fill, outline) }
        return
    }

    val faceColor = fill?.takeUnless { it.isTransparent }
    val edgeColor = outline?.takeUnless { it.isTransparent || style.outlineWidth <= 0f }
    if (faceColor == null && edgeColor == null) return

    val camLocal = cameraLocal(origin)
    val fade = fadeFactor(boxDistance(camLocal, box), style)
    if (fade <= 0f) return

    withPositionRelativeToCamera(origin) {
        if (faceColor != null) {
            drawCustomMesh(ClientRenderPipelines.Quads) { pose ->
                addSoftFaces(pose, box, faceColor, fade, topRatio, style, faceMask, camLocal)
            }
        }
        if (edgeColor != null) {
            drawCustomMesh(ClientRenderPipelines.Triangles) { pose ->
                addSoftEdges(pose, box, edgeColor, fade, style, camLocal)
            }
        }
    }
}

/**
 * A closed horizontal band centred on [origin], feathered on BOTH radial rims - the ring equivalent of
 * [drawSoftBox]'s outline. Delegates to [drawSoftArc] over a full turn; the hard fallback is the stock
 * `drawGradientCircle` (`render/RenderShortcuts.kt:456-486`), which is what this replaced.
 *
 * [solidCenter] and [additive] are handed straight to [drawSoftArc] - with `innerRadius = 0` and both set, this
 * is a filled additive pool rather than a ring (the forge core).
 */
fun WorldRenderEnvironment.drawSoftRing(
    origin: Vec3,
    outerRadius: Float,
    innerRadius: Float,
    outerColor: Color4b,
    innerColor: Color4b,
    style: SoftStyle,
    solidCenter: Boolean = false,
    additive: Boolean = false,
) {
    if (!style.enabled) {
        withPositionRelativeToCamera(origin) {
            drawGradientCircle(outerRadius, innerRadius, outerColor, innerColor)
        }
        return
    }
    drawSoftArc(
        origin, outerRadius, innerRadius, 0.0, TWO_PI, outerColor, innerColor, style, solidCenter, additive,
    )
}

/**
 * A thin free-standing ring of world-space width, for marks whose only job is to be a ring (the attack
 * shockwave). The band grows with [radius] so an expanding wave thins out in perspective rather than staying a
 * constant screen-space stroke, and both rims fade to zero.
 *
 * The hard fallback is `drawCircleOutline`, an SDF stroke of two PIXELS (`render/RenderShortcuts.kt:570-582`) -
 * antialiased, but screen-space thin and therefore exactly the wire look the soft path exists to remove.
 * [additive] is handed to [drawSoftArc].
 */
fun WorldRenderEnvironment.drawSoftOutlineRing(
    origin: Vec3,
    radius: Float,
    color: Color4b,
    style: SoftStyle,
    additive: Boolean = false,
) {
    if (!style.enabled) {
        withPositionRelativeToCamera(origin) { drawCircleOutline(radius, color) }
        return
    }
    val band = (radius * RIPPLE_BAND_RATIO).coerceAtLeast(style.outlineWidth * RIPPLE_MIN_WIDTHS)
    val inner = (radius - band).coerceAtLeast(0f)
    drawSoftArc(origin, radius, inner, 0.0, TWO_PI, color, color, style, additive = additive)
}

/**
 * One angular slice of a horizontal band, `[fromRad, toRad]`, with an inner AND an outer feather zone.
 *
 * The band is emitted as four concentric rails - `inner`, `inner + zone`, `outer - zone`, `outer` - carrying
 * alphas `0, innerAlpha, outerAlpha, 0`. Both feather zones eat INTO the band rather than extending it, so the
 * radius stays exactly the number the caller means (for the blast ring that number is the kill zone) while
 * neither rim ends on a hard edge. A rail pair whose two alphas are both zero is skipped, which is why the
 * common "solid outer edge fading to nothing inwards" case costs barely more than it did before.
 *
 * Drawn as `Triangles` rather than a `TRIANGLE_STRIP`: a strip is one band by construction, and strips cannot be
 * batched (`VertexFormat.Mode.connectedPrimitives`, checked at `render/Render3D.kt:422-423`), while every
 * `Triangles` mesh of a frame is welded into a single draw.
 *
 * @param solidCenter only meaningful at `innerRadius = 0`, where the inner rim is the CENTRE of a disc and there
 *   is nothing outside it to feather into: `true` gives the innermost rail [innerColor]'s own alpha, turning the
 *   default ring into a filled pool. Left `false` everywhere the inner rim is a real rim, so nothing that existed
 *   before this parameter changes shape.
 * @param additive routes the mesh through [ClientRenderPipelines.GlowTriangles] (`SRC_ALPHA, ONE`) instead of the
 *   translucent [ClientRenderPipelines.Triangles], so the band ADDS light to the scene rather than painting over
 *   it - the only way a mark stays legible on top of a fill the user set to alpha 40. Note that the additive
 *   pipeline is depth-TESTED (`ClientRenderPipelines.kt:349`), so a ground decal has to be lifted clear of the
 *   surface it lies on, and that the `style.enabled = false` fallback below ignores this and stays translucent.
 */
fun WorldRenderEnvironment.drawSoftArc(
    origin: Vec3,
    outerRadius: Float,
    innerRadius: Float,
    fromRad: Double,
    toRad: Double,
    outerColor: Color4b,
    innerColor: Color4b,
    style: SoftStyle,
    solidCenter: Boolean = false,
    additive: Boolean = false,
) {
    val span = toRad - fromRad
    if (outerRadius <= 0f || span <= 0.0 || outerColor.isTransparent && innerColor.isTransparent) return
    if (!style.enabled) {
        withPositionRelativeToCamera(origin) {
            drawHardArc(outerRadius, innerRadius, fromRad, span, outerColor, innerColor)
        }
        return
    }

    val fade = fadeFactor(distanceTo(origin), style)
    if (fade <= 0f) return

    val inner = innerRadius.coerceIn(0f, outerRadius)
    val zone = (outerRadius - inner) * HALF_F * style.feather.coerceIn(0f, 1f)
    val coreAlpha = if (solidCenter && inner <= 0f) innerColor.a / MAX_CHANNEL_F * fade else 0f
    Scratch.arcRadius[0] = inner
    Scratch.arcRadius[1] = inner + zone
    Scratch.arcRadius[2] = outerRadius - zone
    Scratch.arcRadius[3] = outerRadius
    Scratch.arcColor[0] = argb(innerColor.argb and RGB_MASK, coreAlpha)
    Scratch.arcColor[1] = argb(innerColor.argb and RGB_MASK, innerColor.a / MAX_CHANNEL_F * fade)
    Scratch.arcColor[2] = argb(outerColor.argb and RGB_MASK, outerColor.a / MAX_CHANNEL_F * fade)
    Scratch.arcColor[3] = argb(outerColor.argb and RGB_MASK, 0f)

    val steps = arcSteps(outerRadius, span)
    withPositionRelativeToCamera(origin) {
        drawCustomMesh(trianglePipeline(additive)) { pose ->
            addArcSlices(pose, fromRad, span, steps)
        }
    }
}

/**
 * A batch of independent line segments as feathered ribbons, in ONE mesh.
 *
 * [points] holds `x0, y0, z0, x1, y1, z1` per segment in the local frame of [origin] and is supplied by the
 * caller, who owns it and refills it in place every frame - that is what keeps this allocation-free for a mark
 * rebuilt continuously (the shockwave's orbital crown, the forge's embers). Only the first [count] segments are
 * read. [widthScale] multiplies `SoftStyle.outlineWidth` for marks that want a thinner or fatter line than a box
 * edge, and a segment shorter than [EPSILON_SQUARED] is skipped ([addRibbon]) - which is what lets a caller fade
 * a tick in and out by growing it from zero length instead of by popping it into existence.
 *
 * [additive] routes the mesh through [ClientRenderPipelines.GlowTriangles]; see [drawSoftArc].
 */
fun WorldRenderEnvironment.drawSoftSegments(
    origin: Vec3,
    points: FloatArray,
    count: Int,
    color: Color4b,
    style: SoftStyle,
    widthScale: Float = 1f,
    additive: Boolean = false,
) {
    if (count <= 0 || color.isTransparent) return
    if (!style.enabled) {
        withPositionRelativeToCamera(origin) { drawHardSegments(points, count, color) }
        return
    }

    val camLocal = cameraLocal(origin)
    val fade = fadeFactor(distanceTo(origin), style)
    if (fade <= 0f) return

    setRails(style.outlineWidth * widthScale, style)
    val rgb = color.argb and RGB_MASK
    val alpha = color.a / MAX_CHANNEL_F * fade
    withPositionRelativeToCamera(origin) {
        drawCustomMesh(trianglePipeline(additive)) { pose ->
            for (index in 0 until count) {
                val base = index * VALUES_PER_SEGMENT
                Scratch.from.set(points[base], points[base + 1], points[base + 2])
                Scratch.to.set(points[base + 3], points[base + 4], points[base + 5])
                addRibbon(pose, camLocal, rgb, alpha)
            }
        }
    }
}

/**
 * Emits the six Fresnel-weighted, vertically graded faces of [box].
 *
 * `Color4b.fade` can only DIM (`render/engine/type/Color4b.kt:170-176`) and both terms here scale an
 * already-scaled alpha, so the colour is split into its RGB and its alpha once and recombined per vertex.
 */
private fun VertexConsumer.addSoftFaces(
    pose: PoseStack.Pose,
    box: AABB,
    color: Color4b,
    fade: Float,
    topRatio: Float,
    style: SoftStyle,
    faceMask: Int,
    camLocal: Vector3f,
) {
    val rgb = color.argb and RGB_MASK
    val base = color.a / MAX_CHANNEL_F * fade
    val minY = box.minY.toFloat()
    val height = (box.maxY - box.minY).toFloat()
    val invHeight = if (height > EPSILON) 1f / height else 0f

    for (face in 0 until FACES) {
        if (faceMask and (1 shl face) == 0) continue
        val faceAlpha = base * fresnelWeight(box, face, camLocal, style)
        if (faceAlpha <= 0f) continue
        for (corner in 0 until CORNERS_PER_FACE) {
            val index = (face * CORNERS_PER_FACE + corner) * 3
            val x = lerp(box.minX, box.maxX, FACE_CORNERS[index])
            val y = lerp(box.minY, box.maxY, FACE_CORNERS[index + 1])
            val z = lerp(box.minZ, box.maxZ, FACE_CORNERS[index + 2])
            val gradient = 1f + (topRatio - 1f) * ((y - minY) * invHeight)
            addVertex(pose, x, y, z).setColor(argb(rgb, faceAlpha * gradient))
        }
    }
}

/**
 * The grazing weight of one face: `mix(faceMin, 1, (1 - |dot(n, toCamera)|)^fresnelPower)`, with `toCamera`
 * taken from the FACE CENTRE so the term is smooth in the camera position and equal for both faces of a pair.
 */
private fun fresnelWeight(box: AABB, face: Int, camLocal: Vector3f, style: SoftStyle): Float {
    val base = face * 3
    val nx = FACE_NORMALS[base]
    val ny = FACE_NORMALS[base + 1]
    val nz = FACE_NORMALS[base + 2]
    val dx = camLocal.x - (((box.minX + box.maxX) * HALF) + nx * ((box.maxX - box.minX) * HALF)).toFloat()
    val dy = camLocal.y - (((box.minY + box.maxY) * HALF) + ny * ((box.maxY - box.minY) * HALF)).toFloat()
    val dz = camLocal.z - (((box.minZ + box.maxZ) * HALF) + nz * ((box.maxZ - box.minZ) * HALF)).toFloat()

    val length = sqrt(dx * dx + dy * dy + dz * dz)
    if (length < EPSILON) return 1f

    val grazing = (1f - abs((nx * dx + ny * dy + nz * dz) / length)).coerceIn(0f, 1f)
    return style.faceMin + (1f - style.faceMin) * grazing.pow(style.fresnelPower)
}

/** The 12 edges of [box] as camera-facing ribbons, all inside the caller's single `Triangles` mesh. */
private fun VertexConsumer.addSoftEdges(
    pose: PoseStack.Pose,
    box: AABB,
    color: Color4b,
    fade: Float,
    style: SoftStyle,
    camLocal: Vector3f,
) {
    val alpha = color.a / MAX_CHANNEL_F * fade
    if (alpha <= 0f) return

    setRails(style.outlineWidth, style)
    val rgb = color.argb and RGB_MASK
    for (edge in 0 until EDGES) {
        cornerOf(box, EDGE_CORNERS[edge * 2], Scratch.from)
        cornerOf(box, EDGE_CORNERS[edge * 2 + 1], Scratch.to)
        addRibbon(pose, camLocal, rgb, alpha)
    }
}

/**
 * One antialiased line of controlled world-space width: the segment `Scratch.from -> Scratch.to` swept sideways
 * along `edgeDirection x toCamera`, so the ribbon always turns its face to the camera and never edges out.
 *
 * The sweep is cut into three quads by the rails in [Scratch.railOffset] set by [setRails]: a solid core at full
 * alpha and one shoulder on each side falling to zero. That zero-alpha outer edge IS the antialiasing - the
 * blend does the smoothing the rasteriser refuses to do for a `Lines` primitive.
 *
 * The side vector is computed per ENDPOINT rather than per edge, which keeps the width honest on an edge that
 * runs away from the camera.
 */
private fun VertexConsumer.addRibbon(pose: PoseStack.Pose, camLocal: Vector3f, rgb: Int, alpha: Float) {
    val from = Scratch.from
    val to = Scratch.to
    val direction = Scratch.direction.set(to).sub(from)
    if (direction.lengthSquared() < EPSILON_SQUARED) return

    sideVector(from, direction, camLocal, Scratch.sideFrom)
    sideVector(to, direction, camLocal, Scratch.sideTo)
    val transparent = argb(rgb, 0f)
    val opaque = argb(rgb, alpha)

    for (band in 0 until RIBBON_BANDS) {
        val nearArgb = if (band == 0) transparent else opaque
        val farArgb = if (band == RIBBON_BANDS - 1) transparent else opaque
        val near = Scratch.railOffset[band]
        val far = Scratch.railOffset[band + 1]
        Scratch.quadA.set(Scratch.sideFrom).mul(near).add(from)
        Scratch.quadB.set(Scratch.sideFrom).mul(far).add(from)
        Scratch.quadC.set(Scratch.sideTo).mul(far).add(to)
        Scratch.quadD.set(Scratch.sideTo).mul(near).add(to)
        addVertex(pose, Scratch.quadA).setColor(nearArgb)
        addVertex(pose, Scratch.quadB).setColor(farArgb)
        addVertex(pose, Scratch.quadC).setColor(farArgb)
        addVertex(pose, Scratch.quadA).setColor(nearArgb)
        addVertex(pose, Scratch.quadC).setColor(farArgb)
        addVertex(pose, Scratch.quadD).setColor(nearArgb)
    }
}

/**
 * Unit vector perpendicular to both [direction] and the view ray at [point], i.e. the direction a ribbon has to
 * widen in to face the camera. Falls back to a stable perpendicular when the segment points straight at the
 * camera and the cross product collapses - at that angle the ribbon is a sliver anyway.
 */
private fun sideVector(point: Vector3f, direction: Vector3f, camLocal: Vector3f, dest: Vector3f) {
    direction.cross(Scratch.toCamera.set(camLocal).sub(point), dest)
    if (dest.lengthSquared() >= EPSILON_SQUARED) {
        dest.normalize()
        return
    }

    if (abs(direction.y) <= abs(direction.x) + abs(direction.z)) {
        dest.set(-direction.z, 0f, direction.x)
    } else {
        dest.set(0f, direction.z, -direction.y)
    }
    dest.normalize()
}

/**
 * The `Triangles` pipeline a soft mesh is emitted through: translucent by default, or the additive
 * [ClientRenderPipelines.GlowTriangles] when the caller asked for light instead of paint. Both take the same
 * `POSITION_COLOR` / `TRIANGLES` format (`render/ClientRenderPipelines.kt:339-361`), so nothing else about the
 * mesh changes - only the blend and, for the additive one, the depth test.
 */
private fun trianglePipeline(additive: Boolean) =
    if (additive) ClientRenderPipelines.GlowTriangles else ClientRenderPipelines.Triangles

/** Fills [Scratch.railOffset] with the four sideways offsets of a ribbon of total width [width]. */
private fun setRails(width: Float, style: SoftStyle) {
    val half = width * HALF_F
    val core = half * (1f - style.feather.coerceIn(0f, 1f))
    Scratch.railOffset[0] = -half
    Scratch.railOffset[1] = -core
    Scratch.railOffset[2] = core
    Scratch.railOffset[3] = half
}

/** Walks the arc once, reusing each boundary's sine and cosine for the next slice. */
private fun VertexConsumer.addArcSlices(pose: PoseStack.Pose, fromRad: Double, span: Double, steps: Int) {
    var cosFrom = cos(fromRad).toFloat()
    var sinFrom = sin(fromRad).toFloat()
    for (index in 1..steps) {
        val theta = fromRad + span * index / steps
        val cosTo = cos(theta).toFloat()
        val sinTo = sin(theta).toFloat()
        addArcSlice(pose, cosFrom, sinFrom, cosTo, sinTo)
        cosFrom = cosTo
        sinFrom = sinTo
    }
}

/** The three radial bands of one angular slice; a band whose two rails are both transparent is skipped. */
private fun VertexConsumer.addArcSlice(
    pose: PoseStack.Pose,
    cosFrom: Float,
    sinFrom: Float,
    cosTo: Float,
    sinTo: Float,
) {
    for (band in 0 until ARC_BANDS) {
        val nearArgb = Scratch.arcColor[band]
        val farArgb = Scratch.arcColor[band + 1]
        if (nearArgb ushr ALPHA_SHIFT == 0 && farArgb ushr ALPHA_SHIFT == 0) continue
        val near = Scratch.arcRadius[band]
        val far = Scratch.arcRadius[band + 1]
        addVertex(pose, cosFrom * near, 0f, sinFrom * near).setColor(nearArgb)
        addVertex(pose, cosFrom * far, 0f, sinFrom * far).setColor(farArgb)
        addVertex(pose, cosTo * far, 0f, sinTo * far).setColor(farArgb)
        addVertex(pose, cosFrom * near, 0f, sinFrom * near).setColor(nearArgb)
        addVertex(pose, cosTo * far, 0f, sinTo * far).setColor(farArgb)
        addVertex(pose, cosTo * near, 0f, sinTo * near).setColor(nearArgb)
    }
}

/** The pre-[SoftStyle] arc: one triangle strip with a hard rim on both sides, kept for `enabled = false`. */
private fun WorldRenderEnvironment.drawHardArc(
    outerRadius: Float,
    innerRadius: Float,
    fromRad: Double,
    span: Double,
    outerColor: Color4b,
    innerColor: Color4b,
) {
    val steps = ceil(span / HARD_STEP_RAD).toInt().coerceAtLeast(1)
    val outerArgb = outerColor.argb
    val innerArgb = innerColor.argb
    drawCustomMesh(ClientRenderPipelines.triangleStrip(true)) { pose ->
        for (index in 0..steps) {
            val theta = fromRad + span * index / steps
            val cosine = cos(theta).toFloat()
            val sine = sin(theta).toFloat()
            addVertex(pose, cosine * outerRadius, 0f, sine * outerRadius).setColor(outerArgb)
            addVertex(pose, cosine * innerRadius, 0f, sine * innerRadius).setColor(innerArgb)
        }
    }
}

/** The pre-[SoftStyle] segment batch: raw one-pixel `Lines`, kept for `enabled = false`. */
private fun WorldRenderEnvironment.drawHardSegments(points: FloatArray, count: Int, color: Color4b) {
    val argb = color.argb
    drawCustomMesh(ClientRenderPipelines.Lines) { pose ->
        for (index in 0 until count) {
            val base = index * VALUES_PER_SEGMENT
            addVertex(pose, points[base], points[base + 1], points[base + 2]).setColor(argb)
            addVertex(pose, points[base + 3], points[base + 4], points[base + 5]).setColor(argb)
        }
    }
}

/**
 * The camera in the local frame whose origin is [origin], as the shared [Scratch.camera]. Valid until the next
 * call, which is enough because world rendering is single-threaded and no soft draw nests inside another.
 */
private fun WorldRenderEnvironment.cameraLocal(origin: Vec3): Vector3f {
    val position = camera.position()
    return Scratch.camera.set(
        (position.x - origin.x).toFloat(),
        (position.y - origin.y).toFloat(),
        (position.z - origin.z).toFloat(),
    )
}

/** Camera distance to [origin], in blocks. */
private fun WorldRenderEnvironment.distanceTo(origin: Vec3): Float = camera.position().distanceTo(origin).toFloat()

/** Camera distance to the CENTRE of [box], both already in the same local frame. */
private fun boxDistance(camLocal: Vector3f, box: AABB): Float {
    val dx = camLocal.x - ((box.minX + box.maxX) * HALF).toFloat()
    val dy = camLocal.y - ((box.minY + box.maxY) * HALF).toFloat()
    val dz = camLocal.z - ((box.minZ + box.maxZ) * HALF).toFloat()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * The single place where a drawing's alpha scale is resolved, and therefore where BOTH global alpha terms live:
 * the distance fade and [SoftStyle.alphaGain].
 *
 * The fade is `1` closer than `fadeStart`, `0` beyond `fadeEnd` and smoothstep in between, so distant geometry
 * stops shouting without a visible threshold anywhere. One distance per drawing, taken from its anchor rather
 * than per vertex, which keeps the term continuous while the camera moves.
 *
 * The gain multiplies whatever the fade produced, so every helper above inherits it for free - each of them
 * already routes its colour through this one number ([argb] then clamps, so a gain past full alpha saturates
 * instead of wrapping). A drawing beyond `fadeEnd` stays gone no matter how large the gain is.
 */
private fun fadeFactor(distance: Float, style: SoftStyle): Float {
    val gain = style.alphaGain
    if (distance <= style.fadeStart) return gain
    if (distance >= style.fadeEnd) return 0f

    val span = (style.fadeEnd - style.fadeStart).coerceAtLeast(EPSILON)
    val t = ((distance - style.fadeStart) / span).coerceIn(0f, 1f)
    return (1f - t * t * (3f - 2f * t)) * gain
}

/**
 * Angular resolution of an arc, scaled with its radius so a small ring does not pay for a big one's vertices and
 * a big one never shows facets. [span] is the arc's own angle, so a segment costs a segment's worth.
 */
private fun arcSteps(radius: Float, span: Double): Int {
    val full = (radius * STEPS_PER_BLOCK).toInt().coerceIn(MIN_ARC_STEPS, MAX_ARC_STEPS)
    return ceil(full * span / TWO_PI).toInt().coerceAtLeast(1)
}

/** Writes corner [index] of the unit cube, scaled into [box], to [dest]. */
private fun cornerOf(box: AABB, index: Int, dest: Vector3f): Vector3f {
    val base = index * 3
    return dest.set(
        lerp(box.minX, box.maxX, CORNER_UNITS[base]),
        lerp(box.minY, box.maxY, CORNER_UNITS[base + 1]),
        lerp(box.minZ, box.maxZ, CORNER_UNITS[base + 2]),
    )
}

private fun lerp(min: Double, max: Double, t: Float): Float = (min + (max - min) * t).toFloat()

/** Recombines an RGB triple with a float alpha into the packed ARGB `setColor` expects. */
private fun argb(rgb: Int, alpha: Float): Int =
    rgb or ((alpha * MAX_CHANNEL_F).toInt().coerceIn(0, MAX_CHANNEL) shl ALPHA_SHIFT)

/**
 * Every vector, rail and colour buffer this file needs, allocated once. World rendering runs on one thread and
 * no entry point above re-enters another, so a shared scratch is safe and keeps the per-frame allocation of the
 * overlay where it was before the soft path existed.
 */
private object Scratch {
    val camera = Vector3f()
    val from = Vector3f()
    val to = Vector3f()
    val direction = Vector3f()
    val toCamera = Vector3f()
    val sideFrom = Vector3f()
    val sideTo = Vector3f()
    val quadA = Vector3f()
    val quadB = Vector3f()
    val quadC = Vector3f()
    val quadD = Vector3f()
    val railOffset = FloatArray(RAILS)
    val arcRadius = FloatArray(RAILS)
    val arcColor = IntArray(RAILS)
}

/**
 * Outward unit normals of the six faces, in the order `-X, +X, -Y, +Y, -Z, +Z` - the order [FACE_CORNERS],
 * [SOFT_ALL_FACES] and [SOFT_SIDE_FACES] are indexed and masked by.
 */
private val FACE_NORMALS = floatArrayOf(
    -1f, 0f, 0f,
    1f, 0f, 0f,
    0f, -1f, 0f,
    0f, 1f, 0f,
    0f, 0f, -1f,
    0f, 0f, 1f,
)

/**
 * The four corners of each face on the UNIT cube, as `0`/`1` per axis: a corner's local position is
 * `min + unit * (max - min)`. Wound as a simple quad; winding is irrelevant because every world pipeline used
 * here disables culling (`render/ClientRenderPipelines.kt:75-79`).
 */
private val FACE_CORNERS = floatArrayOf(
    0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f,
    1f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 0f,
    0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f,
    0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f,
    0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
    0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f,
)

/** The eight corners of the unit cube, indexed as `x * 4 + y * 2 + z`. */
private val CORNER_UNITS = floatArrayOf(
    0f, 0f, 0f,
    0f, 0f, 1f,
    0f, 1f, 0f,
    0f, 1f, 1f,
    1f, 0f, 0f,
    1f, 0f, 1f,
    1f, 1f, 0f,
    1f, 1f, 1f,
)

/** The 12 edges as pairs of [CORNER_UNITS] indices: four along X, four along Y, four along Z. */
private val EDGE_CORNERS = intArrayOf(
    0, 4, 1, 5, 2, 6, 3, 7,
    0, 2, 1, 3, 4, 6, 5, 7,
    0, 1, 2, 3, 4, 5, 6, 7,
)

private const val FACES = 6
private const val CORNERS_PER_FACE = 4
private const val EDGES = 12
private const val VALUES_PER_SEGMENT = 6

/** Rails per band stack, and how many quads they span: alphas run `0, full, full, 0`. */
private const val RAILS = 4
private const val RIBBON_BANDS = 3
private const val ARC_BANDS = 3

/** Band width of [drawSoftOutlineRing] as a fraction of its radius, and its floor in outline widths. */
private const val RIPPLE_BAND_RATIO = 0.1f
private const val RIPPLE_MIN_WIDTHS = 3f

/** Arc tessellation: steps per block of radius over a full turn, clamped to a sane band. */
private const val STEPS_PER_BLOCK = 14f
private const val MIN_ARC_STEPS = 24
private const val MAX_ARC_STEPS = 180

/** Fixed 2 degree step of the hard fallback, matching the arc resolution the module used before. */
private const val HARD_STEP_RAD = 2.0 * PI / 180.0
private const val TWO_PI = 2.0 * PI

private const val ALPHA_SHIFT = 24
private const val RGB_MASK = 0x00FFFFFF
private const val MAX_CHANNEL = 255
private const val MAX_CHANNEL_F = 255f
private const val HALF = 0.5
private const val HALF_F = 0.5f
private const val EPSILON = 1e-6f
private const val EPSILON_SQUARED = 1e-12f
