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
package net.ccbluex.liquidbounce.features.module.modules.render.killeffect

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.fastutil.objectObjectMapOf
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.block.CachedBlockPosSpheres
import net.ccbluex.liquidbounce.utils.render.putVec4
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

object KillEffectWaveMode : Mode("Wave"), KillEffectMode {

    override val parent: ModeValueGroup<*>
        get() = ModuleKillEffect.modes

    private val color by color("Color", Color4b(0, 191, 255, 255))
    private val maxRadius by float("MaxRadius", 6f, 1f..20f, "blocks")
    private val duration by int("Duration", 16, 4..60, "ticks")
    private val brightness by float("ShellBrightness", 1f, 0.1f..2f)
    private val affectBlocks by boolean("AffectBlocks", true)
    private val blockFillDuration by int("BlockFillDuration", 10, 2..40, "ticks")
    private val blockShellThickness by float("BlockShellThickness", 1.2f, 0.3f..3f, "blocks")

    private const val MAX_ACTIVE_WAVES = 8
    private const val FADE_IN_TICKS = 2f

    private class ActiveWave(
        val origin: Vec3,
        val startTick: Int,
        val color: Color4b,
        val maxRadius: Float,
        val durationTicks: Int,
    ) {
        val filledBlocks: Long2IntOpenHashMap = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        var lastFilledRadiusSq: Int = -1
    }

    private val activeWaves = ObjectArrayList<ActiveWave>()

    /**
     * Packed arena rather than a 3-buffer ring: up to [MAX_ACTIVE_WAVES] waves are drawn per frame,
     * and rotating per wave would fence-block from the fourth one onwards.
     */
    private val uboArena by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.KILL_WAVE.createArena("KillEffect Wave UBO arena")
    }

    override fun onKill(entity: Entity) {
        val tick = mc.player?.tickCount ?: return
        if (activeWaves.size >= MAX_ACTIVE_WAVES) {
            activeWaves.removeAt(0)
        }
        val origin = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)
        activeWaves.add(
            ActiveWave(
                origin = origin,
                startTick = tick,
                color = color,
                maxRadius = maxRadius,
                durationTicks = duration,
            )
        )
    }

    override fun reset() {
        activeWaves.clear()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val tick = mc.player?.tickCount ?: return@handler
        val it = activeWaves.iterator()
        while (it.hasNext()) {
            val wave = it.next()
            val age = tick - wave.startTick
            if (age > wave.durationTicks + blockFillDuration) {
                it.remove()
                continue
            }
            if (affectBlocks && age <= wave.durationTicks) {
                stampBlocks(wave, age, tick)
            }
        }
    }

    private fun stampBlocks(wave: ActiveWave, age: Int, tick: Int) {
        val progress = (age.toFloat() / wave.durationTicks).coerceIn(0f, 1f)
        val radius = progress * wave.maxRadius
        val half = blockShellThickness * 0.5f
        val rMin = (radius - half).coerceAtLeast(0f)
        val rMax = (radius + half).coerceAtMost(CachedBlockPosSpheres.RADIUS.toFloat())
        val rMinSqInt = (rMin * rMin).toInt().coerceAtLeast(0)
        val rMaxSqInt = (rMax * rMax).toInt()
            .coerceAtMost(CachedBlockPosSpheres.RADIUS * CachedBlockPosSpheres.RADIUS)
        if (rMaxSqInt <= wave.lastFilledRadiusSq) return

        val fromR = floor(rMin.toDouble()).toInt().coerceIn(0, CachedBlockPosSpheres.RADIUS)
        val toR = ceil(rMax.toDouble()).toInt().coerceIn(fromR, CachedBlockPosSpheres.RADIUS)

        val originBlockX = floor(wave.origin.x).toInt()
        val originBlockY = floor(wave.origin.y).toInt()
        val originBlockZ = floor(wave.origin.z).toInt()

        val offsets = CachedBlockPosSpheres.rangeLong(fromR, toR)
        val iter = offsets.longIterator()
        while (iter.hasNext()) {
            val packed = iter.nextLong()
            val ox = BlockPos.getX(packed)
            val oy = BlockPos.getY(packed)
            val oz = BlockPos.getZ(packed)
            val distSq = ox * ox + oy * oy + oz * oz
            if (distSq < rMinSqInt || distSq > rMaxSqInt) continue
            val worldKey = BlockPos.asLong(
                originBlockX + ox,
                originBlockY + oy,
                originBlockZ + oz,
            )
            if (wave.filledBlocks.get(worldKey) < 0) {
                wave.filledBlocks.put(worldKey, tick)
            }
        }
        wave.lastFilledRadiusSq = rMaxSqInt
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (activeWaves.isEmpty()) return@handler
        val tick = mc.player?.tickCount ?: return@handler
        val partial = event.partialTicks
        val time = ((System.nanoTime() / 1_000_000.0) % 10_000_000.0 / 1000.0).toFloat()

        renderEnvironmentForWorld(event.matrixStack) {
            for (wave in activeWaves) {
                val ageF = (tick - wave.startTick) + partial
                val progress = (ageF / wave.durationTicks).coerceAtLeast(0f)

                if (progress < 1f) {
                    val fadeIn = (ageF / FADE_IN_TICKS).coerceIn(0f, 1f)
                    val radius = progress * wave.maxRadius
                    drawShell(wave.origin, radius, wave.color, progress, fadeIn, time)
                }

                if (affectBlocks && !wave.filledBlocks.isEmpty()) {
                    drawFilledBlocks(wave, tick, partial)
                }
            }
        }
    }

    private fun WorldRenderEnvironment.drawShell(
        origin: Vec3,
        radius: Float,
        baseColor: Color4b,
        progress: Float,
        fadeIn: Float,
        time: Float,
    ) {
        if (radius <= 0.001f) return
        val tintedAlpha = (baseColor.a.toFloat() * brightness).toInt().coerceIn(0, 255)
        val tintedColor = baseColor.with(a = tintedAlpha)

        val slice = uboArena.allocate(ClientUniformDefine.KILL_WAVE.size)
        slice.writeStd140 {
            putVec4(progress, time, fadeIn, 0f)
            putVec4(tintedColor)
        }

        val uniforms = objectObjectMapOf(ClientUniformDefine.KILL_WAVE.uboName, slice)

        withPositionRelativeToCamera(origin) {
            drawSphereMesh(radius, uniforms)
        }
    }

    private fun WorldRenderEnvironment.drawSphereMesh(
        radius: Float,
        uniforms: Map<String, GpuBufferSlice>,
    ) {
        val verts = SphereMesh.triangles
        drawCustomMesh(
            pipeline = ClientRenderPipelines.KillWave,
            uniforms = uniforms,
        ) { pose: PoseStack.Pose ->
            var i = 0
            while (i < verts.size) {
                addVertex(pose, verts[i] * radius, verts[i + 1] * radius, verts[i + 2] * radius)
                addVertex(pose, verts[i + 3] * radius, verts[i + 4] * radius, verts[i + 5] * radius)
                addVertex(pose, verts[i + 6] * radius, verts[i + 7] * radius, verts[i + 8] * radius)
                i += 9
            }
        }
    }

    private fun WorldRenderEnvironment.drawFilledBlocks(
        wave: ActiveWave,
        tick: Int,
        partial: Float,
    ) {
        val baseColor = wave.color
        val durationF = blockFillDuration.toFloat()
        val it = wave.filledBlocks.long2IntEntrySet().fastIterator()
        while (it.hasNext()) {
            val entry = it.next()
            val age = (tick - entry.intValue) + partial
            if (age >= durationF) {
                it.remove()
                continue
            }
            val alphaF = (1f - age / durationF).coerceIn(0f, 1f)
            val alpha = (baseColor.a * alphaF * 0.55f).toInt().coerceIn(0, 255)
            if (alpha <= 1) continue
            val key = entry.longKey
            val pos = Vec3(
                BlockPos.getX(key).toDouble(),
                BlockPos.getY(key).toDouble(),
                BlockPos.getZ(key).toDouble(),
            )
            withPositionRelativeToCamera(pos) {
                drawBox(
                    AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                    faceColor = baseColor.with(a = alpha),
                    outlineColor = null,
                )
            }
        }
    }

    /**
     * Pre-baked unit-sphere triangle mesh.
     * Lat/long bands; each triangle stored as 9 floats (3 vertices × xyz).
     */
    private object SphereMesh {
        private const val LATITUDE_BANDS = 14
        private const val LONGITUDE_BANDS = 22

        val triangles: FloatArray = buildSphere()

        @Suppress("MagicNumber")
        private fun buildSphere(): FloatArray {
            val rings = Array(LATITUDE_BANDS + 1) { FloatArray((LONGITUDE_BANDS + 1) * 3) }
            for (lat in 0..LATITUDE_BANDS) {
                val theta = lat * PI / LATITUDE_BANDS
                val sinT = sin(theta).toFloat()
                val cosT = cos(theta).toFloat()
                val ring = rings[lat]
                for (lon in 0..LONGITUDE_BANDS) {
                    val phi = lon * 2.0 * PI / LONGITUDE_BANDS
                    val sinP = sin(phi).toFloat()
                    val cosP = cos(phi).toFloat()
                    val base = lon * 3
                    ring[base] = cosP * sinT
                    ring[base + 1] = cosT
                    ring[base + 2] = sinP * sinT
                }
            }

            val triangleCount = LATITUDE_BANDS * LONGITUDE_BANDS * 2
            val out = FloatArray(triangleCount * 9)
            var ptr = 0
            for (lat in 0 until LATITUDE_BANDS) {
                val r0 = rings[lat]
                val r1 = rings[lat + 1]
                for (lon in 0 until LONGITUDE_BANDS) {
                    val a0 = lon * 3
                    val a1 = (lon + 1) * 3

                    ptr = put(out, ptr, r0, a0)
                    ptr = put(out, ptr, r1, a0)
                    ptr = put(out, ptr, r0, a1)

                    ptr = put(out, ptr, r0, a1)
                    ptr = put(out, ptr, r1, a0)
                    ptr = put(out, ptr, r1, a1)
                }
            }
            return out
        }

        private fun put(out: FloatArray, ptr: Int, src: FloatArray, srcOff: Int): Int {
            out[ptr] = src[srcOff]
            out[ptr + 1] = src[srcOff + 1]
            out[ptr + 2] = src[srcOff + 2]
            return ptr + 3
        }
    }

}
