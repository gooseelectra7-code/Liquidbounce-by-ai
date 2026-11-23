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
package net.ccbluex.liquidbounce.features.module.modules.render.hawenesp

import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.utils.render.clearColor
import net.ccbluex.liquidbounce.utils.render.writeStd140

/**
 * Holds a stack of progressively-halved framebuffers for the Kawase dual-filter
 * blur pipeline. Level 0 is base/2; each subsequent level halves again.
 *
 * Recreates the underlying targets when the base size or required level count
 * changes; otherwise reuses them across frames.
 *
 * Each level also owns the UBO that describes it *as a draw destination* — see [levelUniform].
 */
class KawaseBlurPyramid(
    private val baseName: String,
    /**
     * Whether more than one caller configures this instance. A shared pyramid grows to the deepest depth any of
     * them asks for and never shrinks, so two callers with different depths cannot thrash the allocation against
     * each other; each then walks the depth [configure] hands back. Leave it false for a pyramid one object owns.
     */
    private val shared: Boolean = false,
) : AutoCloseable {

    private val targets = ArrayList<RenderTarget>()
    private val levelUniforms = ArrayList<GpuBufferSlice>()
    private var baseWidth = 0
    private var baseHeight = 0
    private var uniformOffset = Float.NaN

    fun configure(baseWidth: Int, baseHeight: Int, levels: Int, offset: Float): Int {
        val want = levels.coerceAtLeast(1)
        val resized = baseWidth != this.baseWidth || baseHeight != this.baseHeight
        // A [shared] pyramid grows and never shrinks, and the depth is RETURNED rather than implied by [size].
        // Reallocating whenever the request merely DIFFERS destroys every level and every UBO and builds them
        // again; with two auras drawing at different `GlowRadius` values that happened twice per FRAME, forever.
        // Allocating the deepest request seen and letting each caller walk only its own prefix costs some idle
        // VRAM instead, which the owner's idle release reclaims.
        //
        // A single-owner pyramid keeps the exact fit: nobody else can want a different depth, so shrinking is
        // free and holding unused levels would be pure waste.
        val needRealloc = resized || if (shared) targets.size < want else targets.size != want
        val allocate = if (resized || !shared) want else maxOf(want, targets.size)

        if (needRealloc) {
            close()
            this.baseWidth = baseWidth
            this.baseHeight = baseHeight
            for (i in 0 until allocate) {
                val w = (baseWidth shr (i + 1)).coerceAtLeast(1)
                val h = (baseHeight shr (i + 1)).coerceAtLeast(1)
                val target = TextureTarget("$baseName-L$i", w, h, false)
                // A fresh TextureTarget has undefined contents; the first frame would otherwise
                // upsample whatever the driver handed us.
                target.colorTexture?.clearColor(0)
                targets.add(target)
                levelUniforms.add(
                    ClientUniformDefine.HAWEN_KAWASE.createSingleBuffer { "$baseName-L$i UBO" }
                )
            }
            // NaN forces the uniform write below, whatever `offset` is.
            this.uniformOffset = Float.NaN
        } else {
            for (t in targets) t.colorTexture?.clearColor(0)
        }

        if (this.uniformOffset != offset) {
            this.uniformOffset = offset
            writeLevelUniforms(offset)
        }

        // The caller's own depth, never the allocated one - they differ only for a [shared] pyramid. Safe to
        // write the uniforms here for two callers with different offsets: each configures immediately before its
        // own passes, so no draw is in flight - which is the race `writeLevelUniforms` warns about below.
        return want
    }

    /**
     * Writes every level's UBO once per configuration change.
     *
     * Deliberately *not* done per pass: the contents depend only on the level index (both the
     * downsample into a level and the upsample into that same level need its texel size), and
     * `CommandEncoder.mapBuffer` maps for writing with `GL_MAP_UNSYNCHRONIZED_BIT` — rewriting one
     * shared UBO between `pass.draw` calls races with the draw that is still reading it, so levels
     * could be blurred with a neighbour's texel size.
     */
    private fun writeLevelUniforms(offset: Float) {
        for (i in targets.indices) {
            val target = targets[i]
            levelUniforms[i].writeStd140 {
                putFloat(1f / target.width.toFloat())
                putFloat(1f / target.height.toFloat())
                putFloat(offset)
                putFloat(0f) // padding (vec4 = 4 floats)
            }
        }
    }

    fun level(i: Int): RenderTarget = targets[i]

    /**
     * The Kawase UBO to bind when drawing *into* level [i] (texel size of that level + offset).
     */
    fun levelUniform(i: Int): GpuBufferSlice = levelUniforms[i]

    val size: Int get() = targets.size

    override fun close() {
        for (t in targets) {
            t.destroyBuffers()
        }
        targets.clear()

        for (u in levelUniforms) {
            u.buffer.close()
        }
        levelUniforms.clear()

        baseWidth = 0
        baseHeight = 0
        uniformOffset = Float.NaN
    }
}
