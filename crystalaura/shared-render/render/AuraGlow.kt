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
package net.ccbluex.liquidbounce.features.module.modules.combat.aura.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.hawenesp.KawaseBlurPyramid
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.writeStd140

/**
 * How the glow layer is shaped. Every field is continuous — nothing here switches a look on or
 * off, so a value tweened by [AuraAnim] can be handed straight in without anything popping.
 *
 * @param radius how far the glow reaches, 0..1. Drives both the depth of the blur pyramid and the
 *   width of its upsample kernel, exactly like the Halo ESP: a wider glow costs another halving of
 *   the target rather than a bigger kernel.
 * @param strength brightness of the halo. 0 leaves only the sharp geometry.
 * @param falloff exponent on the blurred coverage. Higher = the glow hugs the geometry more
 *   tightly and dies out sooner; ~1.1 reads as air, ~3 as a thin rim.
 * @param alpha overall opacity of the whole layer, 0..1. At 0 nothing is drawn at all.
 * @param interiorDamping how much of the halo survives *inside* the silhouette, 0..1. Keeping a
 *   little (the default) stops the interior from washing out to white while the geometry stays
 *   sitting on its own light.
 */
data class GlowSettings(
    val radius: Float,
    val strength: Float,
    val falloff: Float,
    val alpha: Float,
    val interiorDamping: Float = 0.35f,
)

/**
 * A soft glow layer any aura geometry can be routed through.
 *
 * The caller draws whatever it likes inside [render]; the geometry lands in an offscreen
 * silhouette instead of the screen, is blurred through a dual-Kawase pyramid, and is composited
 * back as the sharp shape sitting on top of its own halo. Same three steps and the same GPU
 * resources as the Halo ESP (`EspHaloMode`) and the Shader ESP (`EspShaderMode`) — see
 * `shaders/aura/aura_glow.fsh` for what the composite actually adds together.
 *
 * ### Using it
 * ```
 * AuraGlow.render(event, settings, hasGeometry = visuals.placePos != null) {
 *     withPositionRelativeToCamera(pos) { drawBox(box, faceColor = colour, outlineColor = null) }
 * }
 * ```
 * The lambda receives the ordinary [WorldRenderEnvironment], so every `drawBox` / `drawCustomMesh`
 * / `drawGradientSides` helper works unchanged. Colours are taken from the geometry itself: draw
 * it in the colour you want it to glow in.
 *
 * ### Why [hasGeometry] exists
 * The silhouette target has to be allocated *before* the lambda runs, so this object cannot tell
 * afterwards whether anything was actually drawn without reading the GPU back. The caller always
 * knows (it has a snapshot), so it says. With `hasGeometry = false` this is a hard no-op: no
 * target, no passes, and after a while the targets are handed back entirely.
 *
 * ### Failure
 * Anything the render thread could throw is caught once. After a failure the layer turns itself
 * off permanently for the session and frees its targets, rather than throwing every frame.
 *
 * ### One instance, every aura
 * This lives in the shared `combat.aura.render` package rather than inside a module because it owns
 * GPU resources: a full-screen render target (plus its UBO and samplers) that is allocated lazily and
 * handed back when idle. A per-module copy would allocate a SECOND set, alive at the same time, for
 * exactly the same picture. Nothing here holds per-module state - every call is driven entirely by its
 * arguments - so one object serves CrystalAura and AutoAnchor at once. That is the opposite of the
 * copy-don't-share rule the two modules' LOGIC follows, and deliberately so: the logic was copied to
 * escape shared MUTABLE state, and there is none of that here.
 */
object AuraGlow {

    /** Idle frames tolerated before the targets are handed back — about two seconds at 60 fps. */
    private const val IDLE_FRAMES_BEFORE_RELEASE = 120

    /**
     * Offscreen silhouette; allocated on the first frame that actually draws something.
     *
     * It carries a depth attachment even though nothing here reads depth, unlike the ESP
     * silhouettes: the module is free to route geometry through any pipeline it likes, and a
     * depth-testing one (`GlowTriangles`, `triangleStrip(noDepthTest = false)`, …) cannot be drawn
     * into a target that has no depth buffer at all. The buffer is cleared every frame, so the
     * layer still shows through walls — the depth only lets the routed geometry occlude *itself*,
     * which is most of what stops a stack of translucent shapes from reading flat.
     */
    private val silhouetteHolder = LazyRenderTargetHolder(
        "LiquidBounce Aura Glow Silhouette",
        useDepth = true,
    )

    /** Dual-Kawase pyramid built from the silhouette; level 0 holds the finished blur. */
    private val blurPyramid = KawaseBlurPyramid("LiquidBounce Aura Glow Kawase")

    private val glowUbo by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.AURA_GLOW.createSingleBuffer()
    }

    private val linearSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    /** Sticky: set once the effect has thrown, never cleared, so it cannot throw every frame. */
    private var broken = false

    private var idleFrames = 0

    /**
     * Draws [geometry] into an offscreen target, blurs it and composites it back onto the main
     * render target as a soft additive glow with the sharp shape kept on top.
     *
     * Safe to call unconditionally: with [hasGeometry] `false` or a zero [GlowSettings.alpha] it
     * returns before allocating anything. A zero [GlowSettings.strength] still draws — it leaves
     * the sharp geometry without a halo, which is a legitimate look, not a disabled effect.
     *
     * @param hasGeometry whether [geometry] is going to draw at all — see the class docs.
     */
    fun render(
        event: WorldRenderEvent,
        settings: GlowSettings,
        hasGeometry: Boolean = true,
        geometry: WorldRenderEnvironment.() -> Unit,
    ) {
        if (broken || !hasGeometry || settings.alpha <= 0f) {
            onIdleFrame()
            return
        }

        idleFrames = 0

        runCatching {
            val silhouette = silhouetteHolder.initAndGet()

            renderEnvironmentForWorld(
                poseStack = event.matrixStack,
                renderTarget = silhouette,
                draw = geometry,
            )

            blurAndComposite(settings, silhouette)
        }.onFailure { cause ->
            broken = true
            logger.error("Aura glow disabled after a render failure", cause)
            release()
        }
    }

    /**
     * Frees the silhouette and the whole blur pyramid. Call on world change, on disable, and
     * anywhere else the module stops rendering — everything is lazily rebuilt on the next
     * [render] that has something to draw.
     */
    fun release() {
        // Safe from ANY consumer, unlike `AuraBlastDome.release`: this frees GPU resources only, and the holders
        // rebuild them on the next use, so a second aura mid-frame loses a target and not an effect. Nothing here
        // is per-caller state, which is why no owner is taken.
        idleFrames = 0
        runCatching {
            silhouetteHolder.close()
            blurPyramid.close()
        }
    }

    /**
     * Nothing to draw this frame. The targets are kept for a while (the module goes quiet between
     * plans constantly, and reallocating a window-sized target for every gap would be worse than
     * holding it), then handed back once the pause looks permanent.
     */
    private fun onIdleFrame() {
        if (silhouetteHolder.raw == null) {
            return
        }

        idleFrames++
        if (idleFrames >= IDLE_FRAMES_BEFORE_RELEASE) {
            release()
        }
    }

    private fun blurAndComposite(settings: GlowSettings, mask: RenderTarget) {
        val width = mask.width
        val height = mask.height
        if (width <= 0 || height <= 0) {
            return
        }

        // The radius drives both how deep the pyramid goes and how far the upsample reaches, the
        // way the Halo ESP does it: a wider glow costs another halving, not a bigger kernel.
        val radius = settings.radius.coerceIn(0f, 1f)
        val levels = (1 + Math.round(radius * 4f)).coerceIn(1, 5)
        blurPyramid.configure(width, height, levels, 0.5f + radius * 3.5f)

        for (i in 0 until blurPyramid.size) {
            val src = if (i == 0) mask else blurPyramid.level(i - 1)
            blurPyramid.level(i).createRenderPass({ "AuraGlow KawaseDown L$i" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.HawenKawaseDown)
                pass.bindTexture("texture0", src.colorTextureView, linearSampler)
                pass.setUniform(ClientUniformDefine.HAWEN_KAWASE.uboName, blurPyramid.levelUniform(i))
                pass.draw(0, 3)
            }
        }

        for (i in (blurPyramid.size - 1) downTo 1) {
            blurPyramid.level(i - 1).createRenderPass({ "AuraGlow KawaseUp L${i - 1}" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.HawenKawaseUp)
                pass.bindTexture("texture0", blurPyramid.level(i).colorTextureView, linearSampler)
                pass.setUniform(ClientUniformDefine.HAWEN_KAWASE.uboName, blurPyramid.levelUniform(i - 1))
                pass.draw(0, 3)
            }
        }

        glowUbo.writeStd140 {
            putVec4(
                settings.strength.coerceAtLeast(0f),
                settings.falloff.coerceAtLeast(0.05f),
                settings.alpha.coerceIn(0f, 1f),
                settings.interiorDamping.coerceIn(0f, 1f),
            )
        }

        mc.mainRenderTarget.createRenderPass({ "AuraGlow Composite" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.AuraGlow)
            pass.bindTexture("texture0", mask.colorTextureView, linearSampler)
            pass.bindTexture("texture1", blurPyramid.level(0).colorTextureView, linearSampler)
            ClientUniformDefine.AURA_GLOW.setTo(pass, glowUbo)
            pass.draw(0, 3)
        }
    }
}
