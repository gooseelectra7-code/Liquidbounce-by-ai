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
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.utils.render.writeStd140
import kotlin.math.abs

/**
 * Saturation
 *
 * A full-screen saturation post-pass: drains the colour out of the frame at low values, leaves it
 * untouched at 1, and pushes it past the original above 1.
 *
 * Ported from SystemDLC's `Saturation.java` (`client.module.visual.Saturation`). The fragment math
 * is 1:1 with its `saturation_pass.fsh`:
 *
 *   gray   = dot(rgb, vec3(0.299, 0.587, 0.114))   // Rec. 601 luma (Saturation.java shader)
 *   result = mix(vec3(gray), rgb, Saturation)       // 0 grey, 1 identity, 2 oversaturated
 *
 * What could not come across as-is is the plumbing. The original drove a vanilla `PostEffectProcessor`
 * loaded from `post_effect/saturation.json` and swapped the `Saturation` uniform in through reflection
 * (`Saturation.java:90-123`). This engine has no vanilla post_effect path — post-processing is a
 * fullscreen client-pipeline pass — so the reflection is dropped and the uniform is written straight
 * into a std140 UBO every frame, exactly the way [net.ccbluex.liquidbounce.features.module.modules
 * .render.frageffects.FragEffectsShader] does. The pass ping-pongs: sampling the render target you are
 * drawing into is undefined, so the scene is drawn through the saturation pipeline into a scratch
 * target and blitted straight back.
 *
 * Two settings, mirroring the original (`Saturation.java:41-49`):
 *  - [saturation] ("Насыщенность"): default 1.0, range 0.0..2.0.
 *  - [onlyWorld] ("Только мир" / OnlyWorld): default false. Chooses the injection site, mutually
 *    exclusive, exactly as `Saturation.java:52-59, 142-191`:
 *      * OFF (default, whole frame): [applyFrame] runs after the HUD is composited — injected right
 *        after `GuiRenderer.render(...)` in `GameRenderer.render` (see `MixinGameRendererSaturation`),
 *        so world + HUD are all in the main target and none of it has been presented yet.
 *      * ON (world only): [applyWorld] runs at the tail of the world main pass — the existing
 *        `MixinLevelRenderer.lambda$addMainPass$0` TAIL, before the HUD is drawn.
 *
 * The dead-band from `Saturation.java:56` is kept verbatim: within 0.001 of 1.0 the effect is skipped
 * entirely, so an idle module never touches the framebuffer.
 *
 * NOTE (honest scope): the original's OnlyWorld=ON left the first-person hands untouched as well as the
 * HUD. This port reuses the shared `addMainPass` TAIL site (the same one FragEffects desaturation uses),
 * which fires after the world's main pass; the hands may be included there. The HUD is always excluded in
 * that mode. The shader math and the dead-band are 1:1 regardless of site.
 */
object ModuleSaturation : ClientModule("Saturation", ModuleCategories.RENDER) {

    /**
     * "Насыщенность" / Saturation. Original: default 1.0, 0.0..2.0, step 0.05, 2 decimals
     * (`Saturation.java:41`). The step/decimal hints are cosmetic slider metadata the LB value DSL does
     * not carry; the range and default are exact.
     */
    private val saturation by float("Saturation", 1f, 0f..2f)

    /** "Только мир" / OnlyWorld. Original default false (`Saturation.java:45`). */
    private val onlyWorld by boolean("OnlyWorld", false)

    /** |value - 1| <= this and the pass is skipped (`Saturation.java:56`: `Math.abs(f - 1.0F) > 0.001F`). */
    private const val DEAD_BAND = 0.001f

    private val scratch = LazyRenderTargetHolder("Saturation", useDepth = false)
    private val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)

    private val uniform by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.SATURATION.createSingleBuffer()
    }

    private var lastValue = Float.NaN

    /**
     * OnlyWorld=ON path. Called from `MixinLevelRenderer.lambda$addMainPass$0` TAIL, once the world
     * main pass has drawn but before the HUD.
     */
    @JvmStatic
    fun applyWorld() {
        if (running && onlyWorld) {
            apply()
        }
    }

    /**
     * OnlyWorld=OFF (default) path. Called from `MixinGameRendererSaturation` right after
     * `GuiRenderer.render(...)`, so world + HUD are composited into the main target.
     */
    @JvmStatic
    fun applyFrame() {
        if (running && !onlyWorld) {
            apply()
        }
    }

    private fun apply() {
        val value = saturation
        if (abs(value - 1f) <= DEAD_BAND) {
            return
        }

        val main = mc.mainRenderTarget
        val mainView = main.colorTextureView ?: return
        // clear = false: the saturation pass below is a fullscreen triangle that writes every pixel.
        val scratchView = scratch.initAndGet(clear = false).colorTextureView ?: return

        if (value != lastValue) {
            uniform.writeStd140 {
                putFloat(value)
                putFloat(0f)
                putFloat(0f)
                putFloat(0f)
            }
            lastValue = value
        }

        scratchView.createRenderPass({ "Saturation pass" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.Saturation)
            pass.bindTexture("InSampler", mainView, sampler)
            ClientUniformDefine.SATURATION.setTo(pass, uniform)
            pass.draw(0, 3)
        }

        // The saturation pass writes alpha 1, so the premultiplied blit is an exact copy back.
        mainView.createRenderPass({ "Saturation blit" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.Composite.Blit)
            pass.bindTexture("InSampler", scratchView, sampler)
            pass.draw(0, 3)
        }
    }

    override fun onDisabled() {
        // The scratch target is window-sized; release it so a single toggle does not keep a
        // full-screen colour attachment resident for the rest of the session.
        scratch.close()
        lastValue = Float.NaN
        super.onDisabled()
    }
}
