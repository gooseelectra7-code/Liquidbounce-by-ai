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
package net.ccbluex.liquidbounce.render.gui.systemdlc

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.toNativeImage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.jvm.optionals.getOrNull
import kotlin.math.roundToInt

/**
 * The gui-sprite counterpart of [PlateItemIcon]: a crisp, per-size renderer for a plain GUI sprite
 * (the mob effect markers the nametag effect row draws) inside a `pose().scale(...)`-ed plate.
 *
 * ## Why it exists
 * Vanilla's `blitSprite` is a nearest-neighbour stretch off the gui atlas. A mob effect texture is
 * authored at 18x18, and a world-space plate puts it on screen at whatever size the camera distance
 * works out to — commonly 6-12 device px — so whole texel rows and columns are dropped and the marker
 * comes out chewed along one edge. [net.ccbluex.liquidbounce.features.module.modules.render.hudtest.HudTestItemIcons]
 * already documents that exact failure for the HUD; this object is the same fix for a SCALED pose:
 * rasterize at the real on-screen pixel size, then blit ~1:1 through the GUI textured path.
 *
 * ## What it does differently from [PlateItemIcon]
 * The source is the sprite's own png (`textures/<sprite path>.png` — the same mapping the atlas does)
 * rather than the item atlas, and it is **trimmed to its ink and re-centred in a square** before the
 * resample. Mob effect textures each reserve a different margin (absorption paints all 18 columns,
 * fire resistance only 14), so drawn as authored one marker reads a quarter smaller than the next.
 * The downscale itself is [PlateItemIcon.highQualityScale], so both icon families in a plate are
 * resampled identically.
 *
 * GL resources are created lazily on the calling render thread; never touch them off-thread.
 */
object PlateSpriteIcon {

    /** Max rasterized dimension (px). Beyond the source png's own size this is a soft upscale. */
    private const val MAX_PX = 128

    /** Device-pixel quantization step — see [PlateItemIcon] for why the raster resolution snaps. */
    private const val QUANT = 2

    /**
     * LRU cap on cached (sprite, size) textures. The effect sprite set is small (~30), but a scene can
     * hold several distance sizes of each at once, so this is sized for the whole set a few times over.
     */
    private const val MAX_CACHE = 192

    /** Alpha at or above which a source pixel counts as ink for the trim. */
    private const val MIN_INK_ALPHA = 8

    /** Bits reserved for the size in a cache key; also the ceiling [MAX_PX] must stay under. */
    private const val SIZE_BITS = 12

    /** A rasterized sprite texture at one specific pixel size, ready to blit ~1:1. */
    class RenderedSprite(
        val setup: TextureSetup,
        val texture: DynamicTexture,
    )

    /** Dense index per sprite id, so a cache key can be a primitive long instead of a fresh object. */
    private val spriteIndices = Object2IntOpenHashMap<Identifier>().apply { defaultReturnValue(-1) }

    /** Trimmed source images, `null` for a sprite whose png could not be read (looked up once). */
    private val sources = HashMap<Identifier, BufferedImage?>()

    /** LRU cache of rendered textures; access-order so the eldest entry is the least-recently-used. */
    private val cache = object : LinkedHashMap<Long, RenderedSprite>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, RenderedSprite>): Boolean {
            if (size > MAX_CACHE) {
                runCatching { eldest.value.texture.close() }
                return true
            }
            return false
        }
    }

    /**
     * Get (rasterizing + caching on first use) the sprite [id] at the on-screen size [deviceSizePx]
     * (its edge length in device pixels, quantized to [QUANT]). Must be called on the render thread.
     * Returns null when the sprite's png is unavailable or the rasterization failed.
     */
    fun get(id: Identifier, deviceSizePx: Float): RenderedSprite? {
        val px = quantize(deviceSizePx)
        val key = (indexOf(id).toLong() shl SIZE_BITS) or px.toLong()
        cache[key]?.let { return it }

        val source = source(id) ?: return null
        val rendered = try {
            renderAt(source, px)
        } catch (e: Throwable) {
            logger.warn("PlateSpriteIcon: failed to rasterize $id at ${px}px", e)
            null
        } ?: return null

        cache[key] = rendered
        return rendered
    }

    private fun indexOf(id: Identifier): Int {
        val existing = spriteIndices.getInt(id)
        if (existing >= 0) {
            return existing
        }
        val next = spriteIndices.size
        spriteIndices.put(id, next)
        return next
    }

    private fun source(id: Identifier): BufferedImage? {
        if (sources.containsKey(id)) {
            return sources[id]
        }
        val image = loadSquaredInk(id)
        sources[id] = image
        return image
    }

    /**
     * The png behind a gui sprite, trimmed to the pixels it actually paints and re-centred in a
     * square. A sprite id and its texture path differ only by the `textures/` prefix and the `.png`
     * suffix, which is the whole mapping the atlas does too.
     */
    private fun loadSquaredInk(id: Identifier): BufferedImage? {
        val path = Identifier.fromNamespaceAndPath(id.namespace, "textures/" + id.path + ".png")
        val image = try {
            mc.resourceManager.getResource(path).getOrNull()?.open()?.use(ImageIO::read)
        } catch (e: Throwable) {
            logger.warn("PlateSpriteIcon: could not read $path", e)
            null
        }
        if (image == null) {
            logger.info("PlateSpriteIcon: no source png for gui sprite $id, it falls back to blitSprite")
            return null
        }
        return squaredInk(image)
    }

    /** [image] cropped to its ink and re-centred in a square that keeps the art's aspect. */
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

    private fun quantize(deviceRaw: Float): Int {
        val r = deviceRaw.roundToInt()
        return (((r + QUANT / 2) / QUANT) * QUANT).coerceIn(QUANT, MAX_PX)
    }

    private fun renderAt(source: BufferedImage, px: Int): RenderedSprite {
        val scaled = PlateItemIcon.highQualityScale(source, px)
        val tex = scaled.toNativeImage().asTexture { "PlateSprite ${px}x$px" }
        val setup = TextureSetup.singleTexture(
            tex.textureView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )
        return RenderedSprite(setup, tex)
    }
}

/**
 * Draws the gui sprite [id] crisply into the local box `[x, y, x + size, y + size]` at the ACTUAL
 * on-screen pixel size, instead of the aliased nearest-neighbour atlas stretch `blitSprite` does
 * under a `pose().scale(...)` (see [PlateSpriteIcon]).
 *
 * [poseScale] is the accumulated pose scale between this local box and GUI-logical space; the
 * rasterized texture is `size * poseScale * guiScale` device px, so the blit is ~1:1 with what the
 * caller's pose puts on screen. Positioning still comes entirely from the active pose.
 *
 * [tint] multiplies the sprite's own colours (a mob effect marker is already coloured, so the default
 * white leaves it untouched); it is the only way to fade one out, the GUI textured path having no
 * separate alpha.
 *
 * MUST be called on the render thread (it may trigger the lazy GL raster build).
 */
fun GuiGraphicsExtractor.drawPlateSprite(
    id: Identifier,
    x: Float,
    y: Float,
    size: Float,
    poseScale: Float,
    tint: Color4b = Color4b.WHITE,
) {
    if (size <= 0.0f || tint.a == 0) {
        return
    }

    val deviceSizePx = size * poseScale * mc.window.guiScale.toFloat()
    val rendered = PlateSpriteIcon.get(id, deviceSizePx)
    if (rendered == null) {
        // No source png (resource pack, modded effect): keep the vanilla blit so the slot is never blank.
        val px = size.roundToInt()
        blitSprite(RenderPipelines.GUI_TEXTURED, id, x.roundToInt(), y.roundToInt(), px, px)
        return
    }

    drawTexQuad(rendered.setup, x, y, x + size, y + size, 0.0f, 0.0f, 1.0f, 1.0f, tint.argb)
}
