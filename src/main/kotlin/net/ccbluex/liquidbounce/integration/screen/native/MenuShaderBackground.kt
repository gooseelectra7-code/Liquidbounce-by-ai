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
package net.ccbluex.liquidbounce.integration.screen.native

import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ResourceReloadEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.render.ClientRenderPipelines.screenQuadSnippet
import net.ccbluex.liquidbounce.render.ClientRenderPipelines.withUniformBuffer
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawBlitOnCurrentLayer
import net.ccbluex.liquidbounce.utils.client.clientStartDurationMs
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.optional
import net.ccbluex.liquidbounce.utils.render.asTextureSetup
import net.ccbluex.liquidbounce.utils.render.asView
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Live GLSL background for the native Hellhit menus — a self-contained port of the
 * `ThemeBackground.Shader` machinery: a fullscreen fragment shader is rendered to an
 * offscreen texture (with the client's `ThemeBackgroundData` UBO for time + resolution)
 * and blitted into the GUI layer. The shader itself is [SHADER_PATH] (a spiral galaxy).
 *
 * Rendered at a capped resolution and LINEAR-upscaled — the shader is heavy per-pixel, so
 * this keeps the cost bounded on high-DPI monitors. Any build/compile failure flips [failed]
 * and [draw] returns false so callers fall back to the static wallpaper.
 */
object MenuShaderBackground : EventListener {

    private const val SHADER_PATH = "shaders/menu/galaxy.fsh"

    /** Longest offscreen dimension; the shader is expensive, so cap it and upscale. */
    private const val MAX_DIM = 1200f

    private val fshId = LiquidBounce.identifier("shader/fsh/menu-galaxy")
    private val fragmentShader: String by lazy { LiquidBounce.resourceToString(SHADER_PATH) }
    private val sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)

    private val ubo = ClientUniformDefine.THEME_BACKGROUND.createRingBuffer { "MenuGalaxy UBO" }

    private var pipeline: RenderPipeline? = null
    private var failed = false
    private var needsCompile = true

    private var background: GpuTexture? = null
    private var backgroundView: GpuTextureView? = null
    private var textureSetup: TextureSetup? = null

    @Suppress("unused")
    private val reloadHandler = handler<ResourceReloadEvent> { needsCompile = true }

    private fun ensurePipeline(): RenderPipeline? {
        if (failed) return null
        val built = pipeline ?: runCatching {
            RenderPipeline.Builder()
                .withLocation(LiquidBounce.identifier("pipeline/menu-galaxy"))
                .screenQuadSnippet()
                .withFragmentShader(fshId)
                .withUniformBuffer(ClientUniformDefine.THEME_BACKGROUND)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(optional())
                .build()
        }.getOrElse {
            logger.error("MenuShaderBackground: pipeline build failed", it)
            failed = true
            return null
        }.also { pipeline = it }

        if (needsCompile) {
            runCatching {
                gpuDevice.precompilePipeline(built) { id, _ ->
                    if (id == fshId) fragmentShader else error("Unknown shader id: $id")
                }
            }.onFailure {
                logger.error("MenuShaderBackground: shader compile failed", it)
                failed = true
                return null
            }
            needsCompile = false
        }
        return built
    }

    /** Draws the shader background over [width] x [height]; false when unavailable. */
    fun draw(context: GuiGraphicsExtractor, width: Int, height: Int): Boolean {
        val built = ensurePipeline() ?: return false

        val fbW = mc.window.width
        val fbH = mc.window.height
        if (fbW <= 0 || fbH <= 0) return false
        val scale = min(1f, MAX_DIM / max(fbW, fbH).toFloat())
        val rw = max(2, (fbW * scale).roundToInt())
        val rh = max(2, (fbH * scale).roundToInt())

        ubo.rotate()
        val uboSlice = ubo.currentBuffer().slice()
        uboSlice.writeStd140 {
            putFloat(clientStartDurationMs / 1000f)
            putVec2(0f, 0f)
            putVec2(rw.toFloat(), rh.toFloat())
        }

        resizeIfNeeded(rw, rh)

        runCatching {
            backgroundView!!.createRenderPass({ "MenuGalaxy Pass" }).use { pass ->
                pass.setPipeline(built)
                pass.setUniform(ClientUniformDefine.THEME_BACKGROUND.uboName, uboSlice)
                pass.draw(0, 3)
            }
        }.onFailure {
            logger.error("MenuShaderBackground: render pass failed", it)
            failed = true
            return false
        }

        context.drawBlitOnCurrentLayer(
            textureSetup!!,
            x0 = 0, y0 = 0,
            x1 = width, y1 = height,
            u1 = 0f, v1 = 1f,
            u2 = 1f, v2 = 0f,
        )
        return true
    }

    private fun resizeIfNeeded(width: Int, height: Int) {
        val current = background
        if (current != null && current.getWidth(0) == width && current.getHeight(0) == height) {
            return
        }
        background?.close()
        background = gpuDevice.createTexture(
            "MenuGalaxy ($width x $height)",
            GpuTexture.USAGE_RENDER_ATTACHMENT,
            TextureFormat.RGBA8, width, height, 1, 1,
        )
        backgroundView?.close()
        backgroundView = background!!.asView()
        textureSetup = backgroundView!!.asTextureSetup(sampler)
    }
}
