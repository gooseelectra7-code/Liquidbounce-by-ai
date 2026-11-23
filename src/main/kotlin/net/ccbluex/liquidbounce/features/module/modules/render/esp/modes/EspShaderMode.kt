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
package net.ccbluex.liquidbounce.features.module.modules.render.esp.modes

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.PoseStack
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP
import net.ccbluex.liquidbounce.features.module.modules.render.hawenesp.KawaseBlurPyramid
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.render.putVec4
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/**
 * Hawen-style screen-space shader ESP.
 *
 * Pipeline per frame:
 *   1. Render filled coloured entity bounding boxes into an offscreen silhouette RT
 *      (no depth test, so glow shows through walls).
 *   2. Downsample the silhouette through a [KawaseBlurPyramid] with [ClientRenderPipelines.HawenKawaseDown].
 *   3. Upsample back with [ClientRenderPipelines.HawenKawaseUp].
 *   4. Composite the blurred result onto the main RT with [ClientRenderPipelines.HawenComposite],
 *      producing a soft bloom halo and an optional chams (solid fill) overlay.
 *
 * Silhouette capture assumption: [renderEnvironmentForWorld] accepts an explicit [renderTarget]
 * parameter (see [net.ccbluex.liquidbounce.render.RenderShortcuts]), so we pass the offscreen RT
 * directly. The world-projection + model-view matrices written by [RenderSystem.bindDefaultUniforms]
 * are still valid for the window-sized offscreen RT because its dimensions match the main RT and
 * we never override them. In-game visual check: if the silhouette is misaligned or absent, verify
 * that [LazyRenderTargetHolder.initAndGet] produced the correct window size.
 */
object EspShaderMode : EspMode.BoxBased("Shader") {

    // ── Bloom group ──────────────────────────────────────────────────────────
    private object Bloom : ToggleableValueGroup(this, "Bloom", true) {
        val intensity by float("Intensity", 1.8f, 0.1f..6.0f)

        object Blur : ToggleableValueGroup(this, "Blur", true) {
            val levels by int("Levels", 4, 2..6)
            val offset by float("Offset", 0.5f, 0.1f..2.0f)
        }

        init {
            tree(Blur)
        }
    }

    // ── Chams group ──────────────────────────────────────────────────────────
    private object Chams : ToggleableValueGroup(this, "Chams", false) {
        val alpha by float("Alpha", 0.45f, 0.0f..1.0f)
        val threshold by float("Threshold", 0.55f, 0.0f..1.0f)
    }

    init {
        tree(Bloom)
        tree(Chams)
    }

    // ── GPU resources (lazily allocated, freed on disable) ────────────────────
    /** Offscreen render target that holds the raw coloured entity silhouette. */
    private val silhouetteHolder = LazyRenderTargetHolder(
        "LiquidBounce ShaderESP Silhouette",
        useDepth = false,
    )

    /** Kawase dual-filter blur pyramid built from the silhouette texture. */
    private val blurPyramid = KawaseBlurPyramid("LiquidBounce ShaderESP Kawase")

    /** Persistent single-frame UBO for the composite pass parameters. */
    private val compositeUbo = ClientUniformDefine.HAWEN_COMPOSITE.createSingleBuffer()

    // ── Sampler reused every frame ────────────────────────────────────────────
    private val linearSampler by lazy {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    // ── Per-frame render handler ──────────────────────────────────────────────
    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val boxes = collectPreparedBoxes(event.partialTicks)
        if (boxes.isEmpty()) return@handler

        val width = mc.window.width
        val height = mc.window.height

        // ── Step 1: draw filled entity boxes into the silhouette RT ──────────
        val silhouetteRt = silhouetteHolder.initAndGet()   // also clears to transparent

        renderEnvironmentForWorld(
            poseStack = event.matrixStack,
            renderTarget = silhouetteRt,
        ) {
            for ((entity, localBox, position) in boxes) {
                val color = ModuleESP.getColor(entity)
                withPositionRelativeToCamera(position) {
                    // Outline-only: outlineColor = null, just filled faces.
                    // Alpha is kept deliberately high so the blur averages to
                    // a visible coverage signal.
                    drawBox(localBox, faceColor = color.with(a = 255), outlineColor = null)
                }
            }
        }

        blurAndComposite(silhouetteRt, blurPyramid, mc.mainRenderTarget, width, height)
    }

    /**
     * Steps 2-4 of the effect: Kawase down/up over [pyramid], then the composite of the blurred
     * result plus the sharp silhouette onto [dest].
     *
     * Split out of the render handler so the ClickGUI visuals preview can run the SAME passes
     * into its own target at its own size — the world path composites onto the main render
     * target, which the preview must not touch.
     */
    private fun blurAndComposite(
        silhouetteRt: RenderTarget,
        blurPyramid: KawaseBlurPyramid,
        dest: RenderTarget,
        width: Int,
        height: Int,
    ) {
        // ── Step 2 & 3: Kawase dual-filter blur ───────────────────────────────
        val levels = Bloom.Blur.levels
        val offset = Bloom.Blur.offset

        // The pyramid owns one UBO per level and writes them here, once per configuration change.
        // Sharing a single UBO across the passes below used to rewrite it between draws, which
        // races with the GPU (mapBuffer writes are unsynchronized).
        blurPyramid.configure(width, height, levels, offset)

        // Downsample pass: silhouette → pyramid[0], pyramid[0] → pyramid[1], …
        for (i in 0 until blurPyramid.size) {
            val src = if (i == 0) silhouetteRt else blurPyramid.level(i - 1)
            val dst = blurPyramid.level(i)

            dst.createRenderPass({ "ShaderESP KawaseDown L$i" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.HawenKawaseDown)
                pass.bindTexture("texture0", src.colorTextureView, linearSampler)
                pass.setUniform(ClientUniformDefine.HAWEN_KAWASE.uboName, blurPyramid.levelUniform(i))
                pass.draw(0, 3)
            }
        }

        // Upsample pass: pyramid[last] → pyramid[last-1], …  → pyramid[0]
        for (i in (blurPyramid.size - 1) downTo 1) {
            val src = blurPyramid.level(i)
            val dst = blurPyramid.level(i - 1)

            dst.createRenderPass({ "ShaderESP KawaseUp L${i - 1}" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.HawenKawaseUp)
                pass.bindTexture("texture0", src.colorTextureView, linearSampler)
                pass.setUniform(ClientUniformDefine.HAWEN_KAWASE.uboName, blurPyramid.levelUniform(i - 1))
                pass.draw(0, 3)
            }
        }

        // ── Step 4: composite blurred result onto the main RT ─────────────────
        // bloomTint.a == 0  → use sampled (entity) colour
        // chamsTint.a == 0  → use sampled colour
        compositeUbo.writeStd140 {
            // bloomTint  – a=0: defer to sampled colour
            putVec4(0f, 0f, 0f, 0f)
            // chamsTint  – a=0: defer to sampled colour
            putVec4(0f, 0f, 0f, 0f)
            // params: bloomIntensity, chamsAlpha, chamsThreshold, bloomEnabled
            putFloat(if (Bloom.enabled) Bloom.intensity else 0f)
            putFloat(if (Chams.enabled) Chams.alpha else 0f)
            putFloat(Chams.threshold)
            putFloat(if (Bloom.enabled) 1f else 0f)
        }

        // Level 0 of the pyramid holds the final blurred image after upsample.
        val blurredView = blurPyramid.level(0).colorTextureView

        dest.createRenderPass({ "ShaderESP Composite" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.HawenComposite)
            pass.bindTexture("texture0", blurredView, linearSampler)
            pass.bindTexture("texture1", silhouetteRt.colorTextureView, linearSampler)
            pass.setUniform(ClientUniformDefine.HAWEN_COMPOSITE.uboName, compositeUbo)
            pass.draw(0, 3)
        }
    }

    /**
     * Renders this mode for a single entity into [dest], for the ClickGUI visuals preview.
     *
     * The caller owns [silhouette] and [pyramid] because both must be sized to the preview
     * stage rather than the window, and it positions [pose] itself — hence no camera-relative
     * translation here. The blur and composite are the module's own, so Bloom/Chams settings
     * apply exactly as in the world.
     */
    internal fun renderPreview(
        entity: LivingEntity,
        localBox: AABB,
        pose: PoseStack,
        silhouette: RenderTarget,
        pyramid: KawaseBlurPyramid,
        dest: RenderTarget,
    ) {
        renderEnvironmentForWorld(poseStack = pose, renderTarget = silhouette) {
            drawBox(localBox, faceColor = ModuleESP.getColor(entity).with(a = 255), outlineColor = null)
        }
        blurAndComposite(silhouette, pyramid, dest, silhouette.width, silhouette.height)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun disable() {
        super.disable()
        silhouetteHolder.close()
        blurPyramid.close()
    }
}
