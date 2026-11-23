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
package net.ccbluex.liquidbounce.features.module.modules.render.frageffects

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The ring of debris thrown out of a body on a kill.
 *
 * Particles are stored as flat floats rather than objects: a single frag spawns thousands of them,
 * and allocating that many short-lived objects every kill is exactly the kind of garbage a render
 * path should not produce.
 *
 * Each particle spirals outwards from its spawn ring — yaw, radius and height all interpolate
 * linearly over its lifetime — and fades in and back out.
 */
class FragParticles(private val capacity: Int) {

    private companion object {
        /** Floats per particle; see the `OFFSET_*` constants for the layout. */
        const val STRIDE = 11

        const val OFFSET_SPAWN_X = 0
        const val OFFSET_SPAWN_Y = 1
        const val OFFSET_SPAWN_Z = 2
        const val OFFSET_START_YAW = 3
        const val OFFSET_END_YAW = 4
        const val OFFSET_START_DISTANCE = 5
        const val OFFSET_END_DISTANCE = 6
        const val OFFSET_RISE = 7
        const val OFFSET_BORN_AT = 8
        const val OFFSET_LIFETIME = 9
        const val OFFSET_RANDOM = 10

        /** Fade-in occupies the first third of the rise, matching the original's `min(x * 3, 1)`. */
        const val FADE_IN_SHARPNESS = 3f
    }

    private val data = FloatArray(capacity * STRIDE)

    var size: Int = 0
        private set

    val isEmpty: Boolean
        get() = size == 0

    fun clear() {
        size = 0
    }

    /**
     * Spawns [count] particles in a ring around [center], all dying within [lifetimeMs].
     *
     * [now] is the module's own effect clock rather than wall time, so a hit-stop slows the debris
     * down with everything else.
     */
    @Suppress("LongParameterList")
    fun spawnRing(
        center: Vec3,
        count: Int,
        lifetimeMs: Float,
        now: Float,
        startDistance: Float,
        random: () -> Float,
    ) {
        if (count <= 0) {
            return
        }

        val rise = startDistance / 5f
        val riseSpread = rise / 2f

        repeat(min(count, capacity - size)) { index ->
            val base = size * STRIDE
            val heightJitter = -riseSpread / 2f + random() * riseSpread
            val distanceJitter = startDistance / 2f + (-startDistance / 40f + random() * startDistance / 20f)

            data[base + OFFSET_SPAWN_X] = center.x.toFloat()
            data[base + OFFSET_SPAWN_Y] = (center.y + heightJitter).toFloat()
            data[base + OFFSET_SPAWN_Z] = center.z.toFloat()
            data[base + OFFSET_START_YAW] = index / count.toFloat() * 360f
            data[base + OFFSET_END_YAW] = data[base + OFFSET_START_YAW] + 36f * random()
            data[base + OFFSET_START_DISTANCE] = startDistance
            data[base + OFFSET_END_DISTANCE] = startDistance + distanceJitter
            data[base + OFFSET_RISE] = rise * random()
            data[base + OFFSET_BORN_AT] = now
            data[base + OFFSET_LIFETIME] = lifetimeMs
            data[base + OFFSET_RANDOM] = random()

            size++
        }
    }

    /** Drops finished particles by swapping the last live one into their slot. */
    fun prune(now: Float) {
        var index = 0
        while (index < size) {
            if (age(index, now) >= 1f) {
                val last = size - 1
                if (index != last) {
                    System.arraycopy(data, last * STRIDE, data, index * STRIDE, STRIDE)
                }
                size--
            } else {
                index++
            }
        }
    }

    /**
     * @param web when true the particles are wired together in triples instead of drawn
     *   individually. The original did this by feeding one vertex per particle into `GL_TRIANGLES`,
     *   which turns the cloud into a shattering mesh during the fade-out — a deliberate look, kept
     *   here rather than tidied away.
     */
    @Suppress("LongParameterList")
    fun render(
        env: WorldRenderEnvironment,
        now: Float,
        globalAlpha: Float,
        darkColor: Color4b,
        brightColor: Color4b,
        web: Boolean,
        pointHalfSize: Float,
    ) {
        if (size == 0 || globalAlpha <= 0f) {
            return
        }

        with(env) {
            withPositionRelativeToCamera {
                drawCustomMesh(ClientRenderPipelines.Triangles) { pose ->
                    if (web) {
                        emitWeb(pose, now, globalAlpha, darkColor, brightColor)
                    } else {
                        emitPoints(pose, now, globalAlpha, darkColor, brightColor, env, pointHalfSize)
                    }
                }
            }
        }
    }

    /**
     * Triples only; a trailing partial group is skipped rather than drawn as a degenerate sliver.
     */
    private fun VertexConsumer.emitWeb(
        pose: PoseStack.Pose,
        now: Float,
        globalAlpha: Float,
        darkColor: Color4b,
        brightColor: Color4b,
    ) {
        var index = 0
        while (index + 2 < size) {
            emitVertex(pose, index, now, globalAlpha, darkColor, brightColor)
            emitVertex(pose, index + 1, now, globalAlpha, darkColor, brightColor)
            emitVertex(pose, index + 2, now, globalAlpha, darkColor, brightColor)
            index += 3
        }
    }

    @Suppress("LongParameterList")
    private fun VertexConsumer.emitPoints(
        pose: PoseStack.Pose,
        now: Float,
        globalAlpha: Float,
        darkColor: Color4b,
        brightColor: Color4b,
        env: WorldRenderEnvironment,
        halfSize: Float,
    ) {
        val left = env.camera.leftVector()
        val up = env.camera.upVector()

        for (index in 0 until size) {
            emitPoint(pose, index, now, globalAlpha, darkColor, brightColor, left, up, halfSize)
        }
    }

    @Suppress("LongParameterList")
    private fun VertexConsumer.emitVertex(
        pose: PoseStack.Pose,
        index: Int,
        now: Float,
        globalAlpha: Float,
        darkColor: Color4b,
        brightColor: Color4b,
    ) {
        val base = index * STRIDE
        val progress = age(index, now)
        val argb = colorOf(base, progress, globalAlpha, darkColor, brightColor)

        val yaw = Mth.lerp(progress, data[base + OFFSET_START_YAW], data[base + OFFSET_END_YAW]) * Mth.DEG_TO_RAD
        val distance = Mth.lerp(progress, data[base + OFFSET_START_DISTANCE], data[base + OFFSET_END_DISTANCE])

        addVertex(
            pose,
            data[base + OFFSET_SPAWN_X] + sin(yaw) * distance,
            data[base + OFFSET_SPAWN_Y] + data[base + OFFSET_RISE] * progress,
            data[base + OFFSET_SPAWN_Z] + cos(yaw) * distance,
        ).setColor(argb)
    }

    /**
     * Stand-in for `GL_POINTS`, which modern pipelines do not expose: a camera-facing triangle just
     * big enough to read as a speck.
     */
    @Suppress("LongParameterList")
    private fun VertexConsumer.emitPoint(
        pose: PoseStack.Pose,
        index: Int,
        now: Float,
        globalAlpha: Float,
        darkColor: Color4b,
        brightColor: Color4b,
        left: Vector3fc,
        up: Vector3fc,
        halfSize: Float,
    ) {
        val base = index * STRIDE
        val progress = age(index, now)
        val argb = colorOf(base, progress, globalAlpha, darkColor, brightColor)

        val yaw = Mth.lerp(progress, data[base + OFFSET_START_YAW], data[base + OFFSET_END_YAW]) * Mth.DEG_TO_RAD
        val distance = Mth.lerp(progress, data[base + OFFSET_START_DISTANCE], data[base + OFFSET_END_DISTANCE])

        val x = data[base + OFFSET_SPAWN_X] + sin(yaw) * distance
        val y = data[base + OFFSET_SPAWN_Y] + data[base + OFFSET_RISE] * progress
        val z = data[base + OFFSET_SPAWN_Z] + cos(yaw) * distance

        val s = halfSize
        addVertex(pose, x - left.x() * s - up.x() * s, y - left.y() * s - up.y() * s, z - left.z() * s - up.z() * s)
            .setColor(argb)
        addVertex(pose, x + left.x() * s - up.x() * s, y + left.y() * s - up.y() * s, z + left.z() * s - up.z() * s)
            .setColor(argb)
        addVertex(pose, x + up.x() * s * 2f, y + up.y() * s * 2f, z + up.z() * s * 2f)
            .setColor(argb)
    }

    private fun age(index: Int, now: Float): Float {
        val base = index * STRIDE
        val lifetime = data[base + OFFSET_LIFETIME]
        if (lifetime <= 0f) {
            return 1f
        }

        return ((now - data[base + OFFSET_BORN_AT]) / lifetime).coerceIn(0f, 1f)
    }

    private fun colorOf(
        base: Int,
        progress: Float,
        globalAlpha: Float,
        darkColor: Color4b,
        brightColor: Color4b,
    ): Int {
        // 0 -> 1 -> 0 over the lifetime, with a sharper attack than release.
        val wave = (if (progress > 0.5f) 1f - progress else progress) * 2f
        val fade = (min(wave * FADE_IN_SHARPNESS, 1f) * (1f - progress)).coerceIn(0f, 1f) * globalAlpha

        // Packed straight into an ARGB int rather than through Color4b.interpolateTo().with():
        // Color4b is an ordinary data class, so that pair of calls allocated two objects per
        // particle per frame, and one frag spawns thousands of particles.
        val t = data[base + OFFSET_RANDOM]
        val a = (lerpChannel(darkColor.a, brightColor.a, t) * fade).toInt().coerceIn(0, 255)
        val r = lerpChannel(darkColor.r, brightColor.r, t).toInt().coerceIn(0, 255)
        val g = lerpChannel(darkColor.g, brightColor.g, t).toInt().coerceIn(0, 255)
        val b = lerpChannel(darkColor.b, brightColor.b, t).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerpChannel(from: Int, to: Int, t: Float): Float = from + (to - from) * t

}
