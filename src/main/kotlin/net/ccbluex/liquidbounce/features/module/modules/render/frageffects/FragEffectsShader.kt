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

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.utils.render.writeStd140

/**
 * The full-screen desaturation half of [ModuleFragEffects].
 *
 * The vanilla `shaders/post/desaturate.json` and `blobs2.json` the original loaded were removed from
 * the game, so the effect is a client shader instead. Both the kill and the totem branch use it,
 * only at different strengths — a second shader for the totem would not have read differently.
 *
 * Run as a ping-pong rather than in place: sampling the render target you are drawing into is
 * undefined, so the scene is first written into a scratch target through the desaturate pipeline and
 * then blitted straight back.
 */
object FragEffectsShader : MinecraftShortcuts {

    private const val MIN_VISIBLE_STRENGTH = 0.004f

    private val scratch = LazyRenderTargetHolder("FragEffects Desaturate", useDepth = false)
    private val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)

    private val uniform by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.FRAG_DESATURATE.createSingleBuffer()
    }

    private var lastStrength = Float.NaN

    /** Called from `MixinLevelRenderer` once the world has finished drawing into the main target. */
    @JvmStatic
    fun apply() {
        val strength = ModuleFragEffects.desaturationStrength()
        if (strength <= MIN_VISIBLE_STRENGTH) {
            return
        }

        val main = mc.mainRenderTarget
        val mainView = main.colorTextureView ?: return
        // clear = false: the desaturate pass below is a fullscreen triangle that writes every pixel.
        val scratchView = scratch.initAndGet(clear = false).colorTextureView ?: return

        if (strength != lastStrength) {
            uniform.writeStd140 {
                putFloat(strength)
                putFloat(0f)
                putFloat(0f)
                putFloat(0f)
            }
            lastStrength = strength
        }

        scratchView.createRenderPass({ "FragEffects desaturate pass" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.FragDesaturate)
            pass.bindTexture("InSampler", mainView, sampler)
            ClientUniformDefine.FRAG_DESATURATE.setTo(pass, uniform)
            pass.draw(0, 3)
        }

        // The desaturate pass writes alpha 1, so the premultiplied blit is an exact copy back.
        mainView.createRenderPass({ "FragEffects desaturate blit" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.Composite.Blit)
            pass.bindTexture("InSampler", scratchView, sampler)
            pass.draw(0, 3)
        }
    }

    /**
     * Releases the window-sized scratch target. Called when [ModuleFragEffects] is disabled — the
     * holder is lazy but never dropped its target on its own, so a single frag kept a full-screen
     * colour attachment resident for the rest of the session.
     */
    @JvmStatic
    fun release() {
        scratch.close()
        lastStrength = Float.NaN
    }

}
