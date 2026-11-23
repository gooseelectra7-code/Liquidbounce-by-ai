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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui

import com.google.common.base.Suppliers
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture
import com.mojang.blaze3d.platform.NativeImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Icon textures for the Mursultan UI, **baked to the physical size they are drawn at**.
 *
 * The icons ship as 64 px white masks (extracted from the original's MTSDF atlases). Handing that
 * texture straight to the GPU and letting it draw at ~14 device pixels is a 4-5x minification
 * through a plain LINEAR sampler with no mip chain: it samples roughly one texel in twenty, which
 * is exactly the "мыло" reported on 2026-09-20.
 *
 * So every requested size gets its own texture, resampled on the CPU by averaging the full source
 * window per destination pixel — the same approach `HudTestItemIcons` uses for item sprites.
 * Averaging runs in premultiplied alpha, or transparent texels drag a dark fringe into the edges.
 *
 * Entries are cached by (name, width, height) and the least recently used ones are dropped once
 * the cache grows past [MAX_ENTRIES].
 */
internal object MursultanIcons {

    private const val MAX_ENTRIES = 192
    private const val MIN_SIZE = 2
    private const val MAX_SIZE = 512

    private class Entry(val texture: DynamicTexture, val setup: TextureSetup) {
        var lastUsed = System.nanoTime()
    }

    /** Source masks, loaded once per name. A null means "tried and missing". */
    private val sources = HashMap<String, BufferedImage?>()
    private val baked = HashMap<String, Entry>()

    /**
     * A [TextureSetup] for [name] rendered at exactly [deviceW] x [deviceH] physical pixels,
     * or null when the icon does not exist.
     */
    fun setup(name: String, deviceW: Int, deviceH: Int): TextureSetup? {
        val w = deviceW.coerceIn(MIN_SIZE, MAX_SIZE)
        val h = deviceH.coerceIn(MIN_SIZE, MAX_SIZE)
        val key = "$name@${w}x$h"
        baked[key]?.let {
            it.lastUsed = System.nanoTime()
            return it.setup
        }
        val source = source(name) ?: return null
        val entry = bake(name, source, w, h)
        baked[key] = entry
        evictStale()
        return entry.setup
    }

    private fun source(name: String): BufferedImage? = sources.getOrPut(name) {
        try {
            LiquidBounce.resource("textures/clickgui/$name.png").use(ImageIO::read)
        } catch (e: Throwable) {
            logger.error("MursultanIcons: failed to read icon $name", e)
            null
        }
    }

    private fun bake(name: String, source: BufferedImage, w: Int, h: Int): Entry {
        val image = NativeImage(NativeImage.Format.RGBA, w, h, false)
        resample(source, image, w, h)
        val texture = DynamicTexture(Suppliers.ofInstance("Mursultan icon $name ${w}x$h"), image)
        return Entry(
            texture,
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            ),
        )
    }

    /** Box filter over the exact source window of each destination pixel, in premultiplied alpha. */
    @Suppress("NestedBlockDepth")
    private fun resample(source: BufferedImage, target: NativeImage, w: Int, h: Int) {
        val sw = source.width
        val sh = source.height
        val pixels = source.getRGB(0, 0, sw, sh, null, 0, sw)
        val stepX = sw.toFloat() / w
        val stepY = sh.toFloat() / h

        for (dy in 0 until h) {
            val y0 = dy * stepY
            val y1 = (dy + 1) * stepY
            for (dx in 0 until w) {
                val x0 = dx * stepX
                val x1 = (dx + 1) * stepX
                var weightSum = 0f
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                var sy = floor(y0).toInt()
                while (sy < ceil(y1).toInt().coerceAtMost(sh)) {
                    val coverY = (minOf(y1, sy + 1f) - maxOf(y0, sy.toFloat())).coerceAtLeast(0f)
                    if (coverY > 0f) {
                        var sx = floor(x0).toInt()
                        while (sx < ceil(x1).toInt().coerceAtMost(sw)) {
                            val coverX = (minOf(x1, sx + 1f) - maxOf(x0, sx.toFloat())).coerceAtLeast(0f)
                            if (coverX > 0f) {
                                val argb = pixels[sy * sw + sx]
                                val pa = (argb ushr 24 and 0xFF) / 255f
                                val weight = coverX * coverY
                                weightSum += weight
                                a += pa * weight
                                // premultiplied, so fully transparent texels contribute no colour
                                r += (argb ushr 16 and 0xFF) * pa * weight
                                g += (argb ushr 8 and 0xFF) * pa * weight
                                b += (argb and 0xFF) * pa * weight
                            }
                            sx++
                        }
                    }
                    sy++
                }
                target.setPixel(dx, dy, pack(a, r, g, b, weightSum))
            }
        }
    }

    private fun pack(a: Float, r: Float, g: Float, b: Float, weightSum: Float): Int {
        if (weightSum <= 0f) return 0
        val alpha = (a / weightSum).coerceIn(0f, 1f)
        if (alpha <= 0f) return 0
        // back out of premultiplied alpha
        val outR = ((r / weightSum) / alpha).coerceIn(0f, 255f).toInt()
        val outG = ((g / weightSum) / alpha).coerceIn(0f, 255f).toInt()
        val outB = ((b / weightSum) / alpha).coerceIn(0f, 255f).toInt()
        val outA = (alpha * 255f).toInt().coerceIn(0, 255)
        // NativeImage stores ABGR
        return (outA shl 24) or (outB shl 16) or (outG shl 8) or outR
    }

    private fun evictStale() {
        while (baked.size > MAX_ENTRIES) {
            val oldest = baked.entries.minByOrNull { it.value.lastUsed } ?: return
            baked.remove(oldest.key)
            runCatching { oldest.value.texture.close() }
        }
    }
}
