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
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/**
 * Hawen-style screen-space shader outline ESP (обводка).
 *
 * Pipeline per frame:
 *   1. Render filled coloured entity bounding boxes into an offscreen silhouette RT
 *      (no depth test, so the outline shows through walls).
 *   2. Composite an outline band onto the main RT with [ClientRenderPipelines.HawenOutline]:
 *      for every screen texel outside a silhouette the shader scans a disk of radius
 *      [thickness] px for the nearest filled texel and, if found, paints the outline in the
 *      silhouette's own colour. Interior texels are discarded (outline only) unless the
 *      optional [Fill] (chams) group is enabled.
 *
 * This is the single-pass counterpart of Hawen's separable grow-and-discard dilation
 * (`EntityOutlineShader`): grow the silhouette, subtract the interior, keep the ring.
 *
 * Registered as the separate "Outline" entry in the ESP Mode dropdown.
 */
object EspShaderOutlineMode : EspMode.BoxBased("Outline") {

    private val thickness by float("Thickness", 2.5f, 0.5f..8.0f)
    private val alpha by float("Alpha", 1.0f, 0.0f..1.0f)
    private val threshold by float("Threshold", 0.35f, 0.05f..0.95f)

    // ── Fill / chams overlay (interior fill) ──────────────────────────────────
    private object Fill : ToggleableValueGroup(this, "Fill", false) {
        val alpha by float("Alpha", 0.3f, 0.0f..1.0f)
    }

    init {
        tree(Fill)
    }

    // ── GPU resources (lazily allocated, freed on disable) ────────────────────
    /** Offscreen render target that holds the raw coloured entity silhouette. */
    private val silhouetteHolder = LazyRenderTargetHolder(
        "LiquidBounce OutlineESP Silhouette",
        useDepth = false,
    )

    /** Persistent single-frame UBO for the outline pass parameters. */
    private val outlineUbo = ClientUniformDefine.HAWEN_OUTLINE.createSingleBuffer()

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
                    drawBox(localBox, faceColor = color.with(a = 255), outlineColor = null)
                }
            }
        }

        compositeOutline(silhouetteRt, mc.mainRenderTarget, width, height)
    }

    /**
     * Step 2: dilate the silhouette into an outline band and composite it onto [dest].
     *
     * Split out of the render handler so the ClickGUI visuals preview can run the SAME pass
     * into its own target at its own size — the world path composites onto the main render
     * target, which the preview must not touch. [width]/[height] drive the texel size the
     * shader dilates by, so they must be the SILHOUETTE's dimensions, not the window's.
     */
    private fun compositeOutline(
        silhouetteRt: RenderTarget,
        dest: RenderTarget,
        width: Int,
        height: Int,
    ) {
        outlineUbo.writeStd140 {
            // params: 1/w, 1/h, thickness(px), fillAlpha
            putFloat(1f / width.toFloat())
            putFloat(1f / height.toFloat())
            putFloat(thickness)
            putFloat(if (Fill.enabled) Fill.alpha else 0f)
            // params2: outlineAlpha, threshold, pad, pad
            putFloat(alpha)
            putFloat(threshold)
            putFloat(0f)
            putFloat(0f)
        }

        dest.createRenderPass({ "OutlineESP Composite" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.HawenOutline)
            pass.bindTexture("texture0", silhouetteRt.colorTextureView, linearSampler)
            pass.setUniform(ClientUniformDefine.HAWEN_OUTLINE.uboName, outlineUbo)
            pass.draw(0, 3)
        }
    }

    /**
     * Renders this mode for a single entity into [dest], for the ClickGUI visuals preview.
     *
     * The caller owns [silhouette] because it must match the preview stage rather than the
     * window, and positions [pose] itself — hence no camera-relative translation. Thickness,
     * Alpha, Threshold and the Fill group come from the module, so the preview shows them
     * exactly as configured.
     */
    internal fun renderPreview(
        entity: LivingEntity,
        localBox: AABB,
        pose: PoseStack,
        silhouette: RenderTarget,
        dest: RenderTarget,
    ) {
        renderEnvironmentForWorld(poseStack = pose, renderTarget = silhouette) {
            drawBox(localBox, faceColor = ModuleESP.getColor(entity).with(a = 255), outlineColor = null)
        }
        compositeOutline(silhouette, dest, silhouette.width, silhouette.height)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun disable() {
        super.disable()
        silhouetteHolder.close()
    }
}
