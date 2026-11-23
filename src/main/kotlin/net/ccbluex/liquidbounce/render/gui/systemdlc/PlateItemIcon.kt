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
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.gui.ItemImageAtlas
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.toNativeImage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Crisp per-size ItemStack icon renderer for the SystemDLC world-space plates.
 *
 * ## The problem this solves (verified on MC 26.1)
 * Vanilla `GuiGraphicsExtractor.item(stack, x, y)` does NOT render the item model at the current pose
 * scale. `GuiRenderer.prepareItemElements` bakes every visible item ONCE into a `GuiItemAtlas` cell of
 * `DEFAULT_ITEM_SIZE (16) * guiScale` device px (the cell size is `16 * getGuiScaleInvalidatingItemAtlasIfChanged()`;
 * the atlas is keyed to `cachedGuiScale` and rebuilt only when the GUI scale changes), then every
 * `item()` draw is a textured-quad BLIT of that fixed cell through the item render state's pose
 * (`GuiRenderer.submitBlitFromItemAtlas`). The plates draw their icon under a small
 * `pose().scale(screenScale)` (a world-distance-derived factor), so the fixed `16 * guiScale`-px cell
 * is resampled down to a few on-screen pixels -> the aliased / "trashy" look the user reported.
 *
 * This is the same class of problem [SystemIconAtlas] already fixes for the vector icon set: render at
 * the ACTUAL on-screen pixel size instead of a fixed size, then blit ~1:1.
 *
 * ## The fix
 * The item is rasterized to a texture at the EXACT device-pixel size it will occupy on screen and blit
 * ~1:1 through the same GUI textured path ([drawTexQuad]). The pixel raster comes from [ItemImageAtlas],
 * the only render-thread item rasterizer this fork exposes off the main GUI pipeline: it renders EVERY
 * item into an offscreen atlas at `NATIVE_ITEM_SIZE (16) * scale (4) = 64` px per item
 * (`ItemImageAtlas` / `ItemTextureRenderer(scale = 4)`), transparent background
 * (`itemAtlasFramebuffer.clearColorAndDepth()` with clear colour 0), and exposes each 64x64 cell via
 * `getItemImage(item)`. That 64px source is downscaled to the target size with a high-quality
 * progressive-halving Java2D pass (in premultiplied space, so partly-covered edges never pick up a dark
 * fringe and the reduction never undersamples), cached per `(item, roundedPx)` in a small LRU, and blit
 * as a white (untinted, full-alpha) quad.
 *
 * ## Limitations (accepted; documented so they are not read as bugs)
 * [ItemImageAtlas] is keyed by [Item] and rasterizes `item.defaultInstance`, so per-STACK appearance
 * is not reproduced: enchantment glint, damage-bar-independent custom model data (e.g. a talisman
 * totem's custom skin), dyed leather colour and potion tint all fall back to the default item look.
 * For the plates this only matters for custom-model-data items (mainly talisman totems in ItemNametag /
 * equipment in the SYSTEM nametag); every ordinary item is pixel-faithful. When the atlas is not built
 * yet (`isAtlasAvailable == false`, before the first post-join resource load) or an item image is
 * missing, the draw falls back to the vanilla `item()` path so something still shows.
 *
 * GL resources are created lazily on the calling render thread; never touch them off-thread.
 */
object PlateItemIcon {

    /** Max rasterized icon dimension (px). Beyond the 64px source this is an upscale (soft, not aliased). */
    private const val MAX_PX = 128

    /**
     * Device-pixel quantization step. The plates rescale continuously with camera distance, so the raw
     * on-screen size changes every frame; snapping the RASTER resolution to even px keeps the per-size
     * cache from churning while the on-screen quad size itself stays continuous (it is drawn at the exact
     * local `size` under the caller's pose, so only the texture sampling resolution snaps).
     */
    private const val QUANT = 2

    /**
     * LRU cap on cached (item, size) textures. Sized to comfortably hold a busy SYSTEM-nametag scene
     * (many players x up to 6 equipment slots, each at a couple of distance sizes) so the working set is
     * not evicted+recreated every frame. Entries are small (typically <= 40px squared RGBA).
     */
    private const val MAX_CACHE = 384

    /** A rasterized item texture at one specific pixel size, ready to blit ~1:1. */
    class RenderedItem(
        val setup: TextureSetup,
        val texture: DynamicTexture,
    )

    /** LRU cache of rendered textures; access-order so the eldest entry is the least-recently-used. */
    private val cache = object : LinkedHashMap<Long, RenderedItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, RenderedItem>): Boolean {
            if (size > MAX_CACHE) {
                runCatching { eldest.value.texture.close() }
                return true
            }
            return false
        }
    }

    /**
     * Get (rendering + caching on first use) [item] rasterized to fit the on-screen size [deviceSizePx]
     * (its edge length in device pixels; quantized to [QUANT] to bound the per-size cache). Must be
     * called on the render thread. Returns null when the item atlas is not available yet, the item has no
     * image, or rasterization failed.
     */
    fun get(item: Item, deviceSizePx: Float): RenderedItem? {
        if (!ItemImageAtlas.isAtlasAvailable) {
            return null
        }
        val px = quantize(deviceSizePx)
        val key = (BuiltInRegistries.ITEM.getId(item).toLong() shl 12) or px.toLong()
        cache[key]?.let { return it }

        val source = try {
            ItemImageAtlas.getItemImage(item)
        } catch (e: Throwable) {
            logger.warn("PlateItemIcon: item image unavailable for $item", e)
            null
        } ?: return null

        val rendered = try {
            renderAt(source, px)
        } catch (e: Throwable) {
            logger.warn("PlateItemIcon: failed to rasterize $item at ${px}px", e)
            null
        } ?: return null
        cache[key] = rendered
        return rendered
    }

    /** Round a raw device size to the nearest [QUANT] px, clamped to a sane range. */
    private fun quantize(deviceRaw: Float): Int {
        val r = deviceRaw.roundToInt()
        return (((r + QUANT / 2) / QUANT) * QUANT).coerceIn(QUANT, MAX_PX)
    }

    /** Downscale [source] (the 64px atlas cell) to [px] and upload it as a clamped-linear texture. */
    private fun renderAt(source: BufferedImage, px: Int): RenderedItem {
        val scaled = highQualityScale(source, px)
        val tex = scaled.toNativeImage().asTexture { "PlateItem ${px}x$px" }
        val setup = TextureSetup.singleTexture(
            tex.textureView,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
        )
        return RenderedItem(setup, tex)
    }

    /**
     * High-quality square resample of [source] to [px] by [px]. Works in premultiplied ARGB so that
     * partly-transparent edges blend correctly (no dark halo), and halves the image repeatedly until it
     * is within 2x of the target before the final step, so a large reduction is a box-average rather than
     * an undersampling bilinear tap. `getRGB` (used by [toNativeImage]) returns straight ARGB regardless
     * of the premultiplied storage, so the uploaded texture is straight-alpha as the GUI pipeline expects.
     */
    internal fun highQualityScale(source: BufferedImage, px: Int): BufferedImage {
        var current = toPremultiplied(source)
        var w = current.width
        var h = current.height
        while (w > px * 2 && h > px * 2) {
            val nw = max(px, w / 2)
            val nh = max(px, h / 2)
            current = scaleStep(current, nw, nh)
            w = nw
            h = nh
        }
        return if (w == px && h == px) current else scaleStep(current, px, px)
    }

    private fun toPremultiplied(source: BufferedImage): BufferedImage {
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = out.createGraphics()
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return out
    }

    private fun scaleStep(source: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return out
    }
}

/**
 * Draws [stack]'s icon crisply into the local box `[x, y, x + size, y + size]` at the ACTUAL on-screen
 * pixel size, instead of the vanilla fixed-`16 * guiScale`-px atlas blit that aliases under the plates'
 * `pose().scale(...)` (see [PlateItemIcon]).
 *
 * [poseScale] is the accumulated pose scale between this local box and GUI-logical space (i.e. the
 * plate's `screenScale`, times any per-slot fade scale). The rasterized texture size is
 * `size * poseScale * guiScale` device px, so the blit is ~1:1 with what the caller's pose puts on
 * screen. Positioning still comes entirely from the caller's active pose; this only changes what the
 * 16-unit item box samples from. Falls back to the vanilla `item()` render when the crisp texture is
 * not available.
 *
 * MUST be called on the render thread (it may trigger the lazy GL raster build).
 */
fun GuiGraphicsExtractor.drawPlateItem(
    stack: ItemStack,
    x: Float,
    y: Float,
    size: Float,
    poseScale: Float,
) {
    if (stack.isEmpty || size <= 0.0f) {
        return
    }

    // On-screen edge length of the icon in device px = local size * accumulated pose scale * guiScale.
    val deviceSizePx = size * poseScale * mc.window.guiScale.toFloat()

    val rendered = PlateItemIcon.get(stack.item, deviceSizePx)
    if (rendered == null) {
        // Atlas not built yet, or no image for this item: keep the vanilla (aliased) render so the plate
        // is never blank.
        item(stack, x.roundToInt(), y.roundToInt())
        return
    }

    // 0xFFFFFFFF vertex colour: no tint, full alpha (the item image already carries its own colour).
    drawTexQuad(rendered.setup, x, y, x + size, y + size, 0.0f, 0.0f, 1.0f, 1.0f, -1)
}
