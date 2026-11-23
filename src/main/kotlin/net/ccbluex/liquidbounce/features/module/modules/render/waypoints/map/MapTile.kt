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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.utils.render.uploadRect
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture

/**
 * One 512x512-block square of the collected map (the same grid as vanilla region files): one pixel
 * per block column, plus the height of that column.
 *
 * Pixel alpha 0 means "never seen"; everything captured is opaque. Heights are the Y of the block
 * that gave the column its colour (the water SURFACE for water), [UNKNOWN] where nothing was
 * captured. They exist for two things only: shading a column against a neighbour captured in an
 * earlier session, and giving a waypoint placed on the map a sensible Y.
 *
 * GPU side: the pixels live in the [DynamicTexture]'s own image; edits mark a dirty rectangle that
 * is uploaded the next time the tile is drawn, so a burst of block updates costs one upload.
 * Construct and close on the render (main) thread only.
 */
internal class MapTile(
    val rx: Int,
    val rz: Int,
    image: NativeImage,
    private val heights: ShortArray,
) : AutoCloseable {

    companion object {
        const val SHIFT = 9
        const val SIZE = 1 shl SHIFT
        const val MASK = SIZE - 1
        const val UNKNOWN: Short = Short.MIN_VALUE

        /** A dirty rectangle bigger than this share of the tile is uploaded whole. */
        private const val FULL_UPLOAD_AREA = SIZE * SIZE / 4

        fun key(rx: Int, rz: Int): Long = (rx.toLong() shl 32) or (rz.toLong() and 0xFFFFFFFFL)
        fun keyX(key: Long): Int = (key shr 32).toInt()
        fun keyZ(key: Long): Int = key.toInt()

        fun blank(rx: Int, rz: Int): MapTile =
            MapTile(rx, rz, NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true), newHeights())

        fun newHeights(): ShortArray = ShortArray(SIZE * SIZE) { UNKNOWN }
    }

    val key: Long = key(rx, rz)
    val minX: Int get() = rx shl SHIFT
    val minZ: Int get() = rz shl SHIFT

    private val texture = DynamicTexture({ "LiquidBounce WorldMap r.$rx.$rz" }, image)
    private val pixels: NativeImage get() = texture.pixels

    private val nearestSetup: TextureSetup = TextureSetup.singleTexture(
        texture.textureView,
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
    )
    private val linearSetup: TextureSetup = TextureSetup.singleTexture(
        texture.textureView,
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
    )

    /** `System.nanoTime` of the last capture into or draw of this tile; eviction is least-recent. */
    var lastUsed: Long = System.nanoTime()

    /** Captured since it was last written to disk. */
    var diskDirty = false

    private var dirtyMinX = SIZE
    private var dirtyMinZ = SIZE
    private var dirtyMaxX = -1
    private var dirtyMaxZ = -1

    var closed = false
        private set

    fun height(lx: Int, lz: Int): Short = heights[(lz shl SHIFT) or lx]

    fun color(lx: Int, lz: Int): Int = pixels.getPixel(lx, lz)

    fun set(lx: Int, lz: Int, argb: Int, height: Int) {
        pixels.setPixel(lx, lz, argb)
        heights[(lz shl SHIFT) or lx] = height.coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()).toShort()

        if (lx < dirtyMinX) dirtyMinX = lx
        if (lz < dirtyMinZ) dirtyMinZ = lz
        if (lx > dirtyMaxX) dirtyMaxX = lx
        if (lz > dirtyMaxZ) dirtyMaxZ = lz
        diskDirty = true
    }

    /**
     * Uploads pending edits and returns the texture to draw with. [linear] sampling is for views that
     * show more than one block per screen pixel, where nearest sampling shimmers.
     */
    fun prepare(linear: Boolean): TextureSetup {
        lastUsed = System.nanoTime()

        if (dirtyMaxX >= 0) {
            val width = dirtyMaxX - dirtyMinX + 1
            val height = dirtyMaxZ - dirtyMinZ + 1
            if (width * height >= FULL_UPLOAD_AREA) {
                texture.upload()
            } else {
                texture.uploadRect(0, dirtyMinX, dirtyMinZ, width, height)
            }
            dirtyMinX = SIZE
            dirtyMinZ = SIZE
            dirtyMaxX = -1
            dirtyMaxZ = -1
        }

        return if (linear) linearSetup else nearestSetup
    }

    /**
     * An independent copy for the IO thread: the live image may be closed by an eviction while a save
     * is still queued, and writing a freed native buffer is a crash, not a torn file.
     */
    fun snapshot(): TileSnapshot {
        val copy = NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false)
        copy.copyFrom(pixels)
        return TileSnapshot(rx, rz, copy, heights.copyOf())
    }

    override fun close() {
        if (!closed) {
            closed = true
            texture.close()
        }
    }
}

/**
 * Detached copy of a tile, owned by whoever holds it; [image] must be closed by that owner.
 */
internal class TileSnapshot(val rx: Int, val rz: Int, val image: NativeImage, val heights: ShortArray)
