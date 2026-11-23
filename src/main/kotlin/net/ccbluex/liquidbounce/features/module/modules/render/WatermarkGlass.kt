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
package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.render.TextureSetup

/**
 * Region-limited background blur ("glass") for the watermark, built on the vanilla GUI blur
 * mechanism without any custom shaders.
 *
 * How it works: the watermark requests `blurBeforeThisStratum()`, which makes [net.minecraft.client.gui.render.GuiRenderer]
 * call `GameRenderer.processBlurEffect()` (a fullscreen blur of the main target) between the
 * GUI strata. Around that call (hooked in `MixinGuiRenderer`) this object:
 *
 *  1. snapshots the sharp main target into [sharpTexture],
 *  2. lets vanilla blur the main target (with the radius temporarily forced to [forcedRadius]),
 *  3. re-runs that blur over its own output [passes] - 1 more times, widening and smoothing it,
 *  4. copies the blurred result into [blurredTexture],
 *  5. restores the sharp snapshot back onto the main target.
 *
 * Net effect: the screen stays sharp, while [blurredTexture] holds a blurred copy of the frame
 * that the watermark panel samples only inside its rounded rectangle — true glassmorphism.
 */
object WatermarkGlass {

    private var requested = false
    private var forcedRadius = 7
    private var savedRadius: Int? = null

    /**
     * How many times the vanilla blur runs over the frame. Vanilla's is a single box blur, which
     * reads flat and streaky; composing N of them approximates a gaussian of radius `r * sqrt(N)`,
     * i.e. a much wider *and* smoother frost for the same per-pass cost.
     */
    private var passes = 1

    private var sharpTexture: GpuTexture? = null
    private var blurredTexture: GpuTexture? = null
    private var blurredView: GpuTextureView? = null
    private var texWidth = 0
    private var texHeight = 0
    private var failed = false

    /**
     * Called by the watermark during GUI extraction (after it has requested
     * `blurBeforeThisStratum()`). Ensures the capture textures exist and returns the
     * texture setup the glass quad should sample, or `null` if unavailable.
     */
    @JvmOverloads
    fun request(strength: Int, blurPasses: Int = 1): TextureSetup? {
        if (failed) {
            return null
        }
        val main = mc.mainRenderTarget ?: return null

        return try {
            ensureTextures(main.width, main.height)
            forcedRadius = strength.coerceIn(1, 10)
            passes = blurPasses.coerceIn(1, 6)
            requested = true
            TextureSetup.singleTexture(
                blurredView ?: return null,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            )
        } catch (e: Throwable) {
            failed = true
            logger.error("WatermarkGlass: failed to prepare blur capture textures", e)
            null
        }
    }

    /** False for good once the capture textures could not be created; do not mark a blur then. */
    val isAvailable: Boolean get() = !failed

    /**
     * The capture another caller already requested this frame, WITHOUT touching its strength or
     * passes - [request] overwrites both and the last caller in a frame wins, which let a late
     * caller silently change a HUD theme's blur. `null` when nothing is pending.
     */
    fun pendingSetup(): TextureSetup? {
        if (!requested || failed) {
            return null
        }
        val view = blurredView ?: return null
        return TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
    }

    /** Texture size of the blur capture, for UV mapping. */
    val textureWidth: Int get() = texWidth
    val textureHeight: Int get() = texHeight

    /**
     * True while a region-blur capture is pending this frame. Used by the GuiRenderState mixin
     * to swallow a second `blurBeforeThisStratum()` call from vanilla screens (only one blur
     * marker is allowed per frame).
     */
    @JvmStatic
    fun isCapturePending(): Boolean = requested

    /** Hooked right before `GameRenderer.processBlurEffect()` in the GUI renderer. */
    @JvmStatic
    fun beforeBlur() {
        if (!requested) {
            return
        }
        val main = mc.mainRenderTarget ?: return
        val mainColor = main.colorTexture ?: return
        val sharp = sharpTexture ?: return
        if (main.width != texWidth || main.height != texHeight) {
            requested = false
            return
        }

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(mainColor, sharp, 0, 0, 0, 0, 0, texWidth, texHeight)

        // Force a consistent blur radius regardless of the user's menu-blur option
        val option = mc.options.menuBackgroundBlurriness()
        savedRadius = option.get()
        option.set(forcedRadius)
    }

    /** Hooked right after `GameRenderer.processBlurEffect()` in the GUI renderer. */
    @JvmStatic
    fun afterBlur() {
        if (!requested) {
            return
        }
        requested = false

        // Extra passes while the radius is still forced: each one blurs the already blurred
        // frame, so the kernel widens and the box-blur banding disappears.
        repeat(passes - 1) {
            mc.gameRenderer.processBlurEffect()
        }

        savedRadius?.let { mc.options.menuBackgroundBlurriness().set(it) }
        savedRadius = null

        val main = mc.mainRenderTarget ?: return
        val mainColor = main.colorTexture ?: return
        val sharp = sharpTexture ?: return
        val blurred = blurredTexture ?: return

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        // Keep the blurred frame for the glass quad…
        encoder.copyTextureToTexture(mainColor, blurred, 0, 0, 0, 0, 0, texWidth, texHeight)
        // …and restore the sharp image so the rest of the screen is unaffected.
        encoder.copyTextureToTexture(sharp, mainColor, 0, 0, 0, 0, 0, texWidth, texHeight)
    }

    private fun ensureTextures(width: Int, height: Int) {
        if (width == texWidth && height == texHeight && sharpTexture != null && blurredTexture != null) {
            return
        }

        sharpTexture?.close()
        blurredView?.close()
        blurredTexture?.close()

        val device = RenderSystem.getDevice()
        sharpTexture = device.createTexture(
            "WatermarkGlass sharp",
            GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST,
            TextureFormat.RGBA8, width, height, 1, 1,
        )
        val blurred = device.createTexture(
            "WatermarkGlass blurred",
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            TextureFormat.RGBA8, width, height, 1, 1,
        )
        blurredTexture = blurred
        blurredView = device.createTextureView(blurred)
        texWidth = width
        texHeight = height
    }
}
