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
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import com.google.common.base.Suppliers
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.render.gui.ItemImageAtlas
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.jvm.optionals.getOrNull
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Item and gui-sprite icons at an exact device-pixel size.
 *
 * A HUD that works in native pixels asks for icon sizes like 10 or 16 physical pixels, which are
 * rarely a whole multiple of the 16 px the vanilla item renderer produces, nor of the 18 px a mob
 * effect sprite is authored at. Whole multiples of an item go straight through the vanilla path
 * (a pixel-exact texel copy, glint included); every other size is resampled on the CPU — from the
 * 64 px [ItemImageAtlas] bake for an item, from the source png for a sprite — into a cached
 * one-icon texture and drawn 1:1 from there.
 *
 * The resample is a coverage average over a window that is deliberately **narrower** than a
 * destination pixel: wide enough to antialias a texel border across a fraction of a pixel, far too
 * narrow to smear a whole texel into its neighbours. That is what keeps a shrunk item sprite
 * reading like the original pixel art instead of a blurred smudge — Java2D's bilinear blit only
 * ever takes 2x2 taps and cannot do this.
 *
 * A sprite drawn through vanilla's own `blitSprite` instead gets a plain nearest-neighbour stretch
 * off the gui atlas, and 18 into 10 device pixels drops eight of every eighteen texel rows and
 * columns — which is what left the potion markers looking chewed along one edge.
 */
internal object HudTestItemIcons {

    /**
     * Half-width of the resample window as a fraction of a destination pixel. 1 would be a plain
     * box average, which softens the pixel art.
     */
    private const val SHARPNESS = 0.4f

    /** How many (item, size) icon textures stay resident before the least recent one is dropped. */
    private const val MAX_CACHED = 96

    private class Entry(val texture: DynamicTexture, val setup: TextureSetup) {
        var lastUsed = System.currentTimeMillis()
    }

    /** An item or a sprite identifier, plus the device size it was baked for. */
    private data class Key(val source: Any, val size: Int)

    private val cache = HashMap<Key, Entry>()

    /** Sprite ids whose source png could not be read; logged once, then left alone. */
    private val missingSprites = HashSet<Identifier>()

    /**
     * Draws [stack] with its top-left corner at the gui-space point ([guiX], [guiY]) so it covers
     * exactly [deviceSize] physical pixels. Empty stacks and unavailable atlases draw nothing.
     */
    fun draw(ctx: GuiGraphicsExtractor, stack: ItemStack, guiX: Float, guiY: Float, deviceSize: Int) {
        if (stack.isEmpty || deviceSize <= 0) {
            return
        }
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        // Snapping the origin to the device grid is what keeps the texels square
        val x = (guiX * guiScale).roundToInt() / guiScale
        val y = (guiY * guiScale).roundToInt() / guiScale

        if (deviceSize % 16 == 0) {
            drawVanilla(ctx, stack, x, y, deviceSize / 16f / guiScale)
            return
        }

        val setup = setupFor(Key(stack.item, deviceSize)) {
            if (ItemImageAtlas.isAtlasAvailable) ItemImageAtlas.getItemImage(stack.item) else null
        }
        if (setup == null) {
            // Atlas not baked yet: fall back to the nearest whole factor rather than nothing
            drawVanilla(ctx, stack, x, y, (deviceSize / 16f).roundToInt().coerceAtLeast(1) / guiScale)
            return
        }
        val size = deviceSize / guiScale
        ctx.hudTexturedQuad(setup, x, y, size, size, WHITE)
    }

    /**
     * Draws [stack] covering [guiSize] gui units through vanilla's own item rendering, at any
     * scale. For TRANSITIONS only: [draw] bakes one texture per device size, so a size that changes
     * every frame (an item popping in) would bake a texture per frame and churn the cache. This
     * bakes nothing, at the price of vanilla's atlas sampling for the few frames it is on screen.
     */
    fun drawTransient(ctx: GuiGraphicsExtractor, stack: ItemStack, guiX: Float, guiY: Float, guiSize: Float) {
        if (stack.isEmpty || guiSize <= 0f) {
            return
        }
        drawVanilla(ctx, stack, guiX, guiY, guiSize / 16f)
    }

    /**
     * Draws the gui sprite [id] — a mob effect marker and the like — the same way: resampled to
     * exactly [deviceSize] physical pixels off its source png, from a device-aligned origin.
     */
    fun drawTexture(ctx: GuiGraphicsExtractor, id: Identifier, guiX: Float, guiY: Float, deviceSize: Int) {
        if (deviceSize <= 0) {
            return
        }
        val setup = setupFor(Key(id, deviceSize)) { spriteImage(id) } ?: return
        val guiScale = mc.window.guiScale.toFloat().coerceAtLeast(1f)
        ctx.hudTexturedQuad(
            setup,
            (guiX * guiScale).roundToInt() / guiScale,
            (guiY * guiScale).roundToInt() / guiScale,
            deviceSize / guiScale, deviceSize / guiScale, WHITE,
        )
    }

    /**
     * The png behind a gui sprite, trimmed to the art it carries. A sprite id and its texture path
     * differ only by the `textures/` prefix and the `.png` suffix, which is the whole mapping the
     * atlas does too.
     */
    private fun spriteImage(id: Identifier): BufferedImage? {
        if (id in missingSprites) {
            return null
        }
        val path = Identifier.fromNamespaceAndPath(id.namespace, "textures/" + id.path + ".png")
        val image = mc.resourceManager.getResource(path).getOrNull()?.open()?.use(ImageIO::read)
        if (image == null) {
            missingSprites += id
            logger.info("HudTest: no source png for gui sprite " + id + ", its marker stays blank")
            return null
        }
        return squaredInk(image)
    }

    /**
     * [image] cropped to the pixels it actually paints and re-centred in a square, so the icon slot
     * is filled by the art rather than by the texture's own padding.
     *
     * A mob effect texture is 18x18 but each one reserves a different margin — absorption paints
     * all 18 columns, fire resistance only 14 — so drawn as authored one marker comes out a quarter
     * smaller than the next and than the vector glyph in the header above them. This is the bitmap
     * half of what `fitGlyph` does for the vector marks; the square keeps the art's aspect, so a
     * tall icon stays tall instead of being stretched sideways.
     */
    private fun squaredInk(image: BufferedImage): BufferedImage {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) >= MIN_INK_ALPHA) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return image
        }

        val inkWidth = maxX - minX + 1
        val inkHeight = maxY - minY + 1
        val side = maxOf(inkWidth, inkHeight)
        if (side == image.width && side == image.height) {
            return image
        }
        val square = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val graphics = square.createGraphics()
        try {
            graphics.drawImage(
                image.getSubimage(minX, minY, inkWidth, inkHeight),
                (side - inkWidth) / 2, (side - inkHeight) / 2, null,
            )
        } finally {
            graphics.dispose()
        }
        return square
    }

    private fun drawVanilla(ctx: GuiGraphicsExtractor, stack: ItemStack, x: Float, y: Float, scale: Float) {
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)
        ctx.item(stack, 0, 0)
        ctx.pose().popMatrix()
    }

    private inline fun setupFor(key: Key, source: () -> BufferedImage?): TextureSetup? {
        cache[key]?.let {
            it.lastUsed = System.currentTimeMillis()
            return it.setup
        }

        return try {
            val image = source() ?: return null
            val entry = bake(image, key.size)
            cache[key] = entry
            evictStale()
            entry.setup
        } catch (e: Throwable) {
            logger.error("HudTest: failed to resample " + key.source + " at " + key.size + "px", e)
            null
        }
    }

    private fun evictStale() {
        while (cache.size > MAX_CACHED) {
            val eldest = cache.minByOrNull { it.value.lastUsed } ?: return
            eldest.value.texture.close()
            cache.remove(eldest.key)
        }
    }

    private fun bake(source: BufferedImage, size: Int): Entry {
        val image = NativeImage(NativeImage.Format.RGBA, size, size, false)
        resampleInto(image, source, size)
        val texture = DynamicTexture(Suppliers.ofInstance("HudTest item ${size}px"), image)
        val setup = TextureSetup.singleTexture(
            texture.textureView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )
        return Entry(texture, setup)
    }

    private fun resampleInto(target: NativeImage, source: BufferedImage, size: Int) {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val pixels = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth)
        val scaleX = sourceWidth.toFloat() / size
        val scaleY = sourceHeight.toFloat() / size
        // Never below half a source pixel, or the window could fall between samples entirely
        val halfX = (scaleX * 0.5f * SHARPNESS).coerceAtLeast(0.5f)
        val halfY = (scaleY * 0.5f * SHARPNESS).coerceAtLeast(0.5f)

        for (dy in 0 until size) {
            val centerY = (dy + 0.5f) * scaleY
            for (dx in 0 until size) {
                val centerX = (dx + 0.5f) * scaleX
                target.setPixel(
                    dx, dy,
                    sample(
                        Source(pixels, sourceWidth, sourceHeight),
                        centerX - halfX, centerX + halfX,
                        centerY - halfY, centerY + halfY,
                    ),
                )
            }
        }
    }

    private class Source(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * One destination pixel: the average over its sample window, weighted by how much of each
     * source pixel the window covers. The average runs in premultiplied alpha, otherwise
     * transparent pixels drag a dark fringe into the sprite's edges.
     */
    @Suppress("LongParameterList")
    private fun sample(source: Source, left: Float, right: Float, top: Float, bottom: Float): Int {
        var weightSum = 0f
        var alphaSum = 0f
        var redSum = 0f
        var greenSum = 0f
        var blueSum = 0f

        val firstRow = top.toInt().coerceAtLeast(0)
        val lastRow = ceil(bottom).toInt().coerceAtMost(source.height)
        val firstColumn = left.toInt().coerceAtLeast(0)
        val lastColumn = ceil(right).toInt().coerceAtMost(source.width)

        for (sy in firstRow until lastRow) {
            val weightY = minOf(bottom, sy + 1f) - maxOf(top, sy.toFloat())
            if (weightY <= 0f) {
                continue
            }
            val rowOffset = sy * source.width
            for (sx in firstColumn until lastColumn) {
                val weightX = minOf(right, sx + 1f) - maxOf(left, sx.toFloat())
                if (weightX <= 0f) {
                    continue
                }
                val weight = weightX * weightY
                val argb = source.pixels[rowOffset + sx]
                val alpha = (argb ushr 24 and 0xFF) * weight
                weightSum += weight
                alphaSum += alpha
                redSum += alpha * (argb ushr 16 and 0xFF)
                greenSum += alpha * (argb ushr 8 and 0xFF)
                blueSum += alpha * (argb and 0xFF)
            }
        }

        if (alphaSum <= 0f || weightSum <= 0f) {
            return 0
        }
        val alpha = (alphaSum / weightSum).roundToInt().coerceIn(0, 255)
        val red = (redSum / alphaSum).roundToInt().coerceIn(0, 255)
        val green = (greenSum / alphaSum).roundToInt().coerceIn(0, 255)
        val blue = (blueSum / alphaSum).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private const val WHITE = -1

    /** Below this a source pixel counts as padding rather than art. */
    private const val MIN_INK_ALPHA = 16
}
