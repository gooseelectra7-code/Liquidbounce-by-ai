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
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.DrawOutlinesEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP
import net.ccbluex.liquidbounce.features.module.modules.render.hawenesp.KawaseBlurPyramid
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.entity.RenderedEntities
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.render.putVec4
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.world.phys.AABB

/**
 * Halo ESP — port of the NightShift "Shader ESP".
 *
 * A silhouette of every target is blurred through a dual-Kawase pyramid and composited back as an
 * outer halo, an interior fill with an inner glow, an outline band and an optional shimmer sweep.
 *
 * Where the silhouette comes from is a choice ([Silhouette]):
 *  - [SilhouetteSource.MODEL] is the original's: the mode makes its targets glow, vanilla fills its
 *    entity-outline target with their real shapes in the colour
 *    [ModuleESP.getColor] picked (see `MixinEntityRenderer`), and that target is taken as the mask
 *    at [DrawOutlinesEvent] — the moment it holds raw silhouettes, before the vanilla outline pass
 *    (which is suppressed for this mode in `MixinLevelRenderer`).
 *  - [SilhouetteSource.BOX] draws filled entity boxes into an own target instead, the way
 *    [EspShaderMode] does. Coarser, but it owes nothing to vanilla's outline batch — the fallback
 *    for when a model refuses to show up in it.
 */
object EspHaloMode : EspMode("Halo", requiresTrueSight = true) {

    @Suppress("unused")
    enum class OutlineType(override val tag: String, val index: Int) : Tagged {
        OUTSIDE("Outside", 0),
        INSIDE("Inside", 1),
        BOTH("Both", 2),
    }

    @Suppress("unused")
    enum class SilhouetteSource(override val tag: String) : Tagged {
        MODEL("Model"),
        BOX("Box"),
    }

    private val silhouette by enumChoice("Silhouette", SilhouetteSource.MODEL)

    /** Off = the silhouette's own per-entity colour, on = one flat [tint] for everything. */
    private val flatTint by boolean("FlatTint", false)
    private val tint by color("Tint", Color4b(0x36, 0xD3, 0xFF, 0xFF))
    private val opacity by float("Opacity", 1f, 0.05f..1f)
    private val saturation by float("Saturation", 1.15f, 0f..2f)
    private val expand by float("Expand", 0.05f, 0f..0.5f)

    private object Glow : ToggleableValueGroup(this, "Glow", true) {
        val radius by float("Radius", 0.55f, 0f..1f)
        val strength by float("Strength", 1.6f, 0f..4f)
        val falloff by float("Falloff", 1.1f, 0.2f..4f)
    }

    private object Fill : ToggleableValueGroup(this, "Fill", true) {
        val opacity by float("Opacity", 0.45f, 0f..1.5f)
        val innerGlow by float("InnerGlow", 0.6f, 0f..2f)
    }

    private object Outline : ToggleableValueGroup(this, "Outline", true) {
        val type by enumChoice("Type", OutlineType.OUTSIDE)
        val width by int("Width", 2, 1..5)
        val strength by float("Strength", 1.8f, 0f..3f)
        val white by float("White", 0.45f, 0f..1f)
    }

    private object Pulse : ToggleableValueGroup(this, "Pulse", false) {
        val speed by float("Speed", 1f, 0.1f..5f)
        val amount by float("Amount", 0.3f, 0f..1f)
    }

    private object Shimmer : ToggleableValueGroup(this, "Shimmer", false) {
        val width by float("Width", 0.04f, 0.01f..0.15f)
        val period by float("Period", 5f, 1f..15f)
        val brightness by float("Brightness", 0.8f, 0f..2f)
    }

    init {
        tree(Glow)
        tree(Fill)
        tree(Outline)
        tree(Pulse)
        tree(Shimmer)
    }

    /** Used by `MixinEntityRenderer`: only the model source needs vanilla to draw the silhouettes. */
    @JvmStatic
    fun usesVanillaSilhouette(): Boolean = running && silhouette == SilhouetteSource.MODEL

    /** Own silhouette target, only allocated while the box source is in use. */
    private val silhouetteHolder = LazyRenderTargetHolder(
        "LiquidBounce HaloESP Silhouette",
        useDepth = false,
    )

    /** Dual-Kawase pyramid built from the silhouette; level 0 holds the finished blur. */
    private val blurPyramid = KawaseBlurPyramid("LiquidBounce HaloESP Kawase")

    private val haloUbo = ClientUniformDefine.ESP_HALO.createSingleBuffer()

    private val linearSampler by lazy {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    /**
     * Model source: vanilla has just flushed the silhouettes into its outline target.
     *
     * "Just flushed" is load-bearing and is why `MixinLevelRenderer.onRenderGlow` injects AFTER
     * `OutlineBufferSource.endOutlineBatch()`. The outline buffer is one shared `ByteBufferBuilder`
     * whose last batch is only drawn by that call, so one instruction earlier the mask was always
     * short one batch - in a plain fight the nearest player's BODY, since player models are
     * translucent and sorted nearest-last. The full derivation and its limits are in that mixin's
     * KDoc. Moving the injection back to BEFORE regresses this mode, not just its edges.
     */
    @Suppress("unused")
    private val outlineHandler = handler<DrawOutlinesEvent> { event ->
        if (event.type != DrawOutlinesEvent.OutlineType.MINECRAFT_GLOW ||
            silhouette != SilhouetteSource.MODEL
        ) {
            return@handler
        }

        blurAndComposite(event.renderTarget)

        // Vanilla would blend this same target over the frame in `LevelRenderer.doEntityOutline`,
        // but that runs after the world render event, so the silhouettes would land on top of
        // everything drawn there - the target renderer on the very entity they cover, nametags,
        // tracers. `MixinLevelRenderer` cancels that pass while Halo owns the target and the blend
        // happens here instead: same look against the world, nothing buried afterwards.
        val screen = mc.mainRenderTarget.colorTextureView ?: return@handler
        event.renderTarget.blitAndBlendToTexture(screen)
    }

    /** Box source: we draw the silhouette ourselves, so nothing has to glow. */
    @Suppress("unused")
    private val worldHandler = handler<WorldRenderEvent> { event ->
        if (silhouette != SilhouetteSource.BOX) {
            return@handler
        }

        val target = silhouetteHolder.initAndGet()
        var drewAnything = false

        renderEnvironmentForWorld(poseStack = event.matrixStack, renderTarget = target) {
            for (entity in RenderedEntities) {
                if (!shouldRender(entity)) {
                    continue
                }

                val dimensions = entity.getDimensions(entity.pose)
                val halfWidth = dimensions.width / 2.0
                val box = AABB(
                    -halfWidth, 0.0, -halfWidth,
                    halfWidth, dimensions.height.toDouble(), halfWidth,
                ).inflate(expand.toDouble())

                val color = ModuleESP.getColor(entity)
                withPositionRelativeToCamera(entity.interpolateCurrentPosition(event.partialTicks)) {
                    drawBox(box, faceColor = color.with(a = 255), outlineColor = null)
                }
                drewAnything = true
            }
        }

        if (drewAnything) {
            blurAndComposite(target)
        }
    }

    private fun blurAndComposite(mask: RenderTarget) {
        val width = mask.width
        val height = mask.height
        if (width <= 0 || height <= 0) {
            return
        }

        // The radius drives both how deep the pyramid goes and how far the upsample reaches,
        // exactly like the original: a wider glow costs another halving, not a bigger kernel.
        val radius = Glow.radius
        val levels = (1 + Math.round(radius * 4f)).coerceIn(1, 5)
        blurPyramid.configure(width, height, levels, 0.5f + radius * 3.5f)

        for (i in 0 until blurPyramid.size) {
            val src = if (i == 0) mask else blurPyramid.level(i - 1)
            blurPyramid.level(i).createRenderPass({ "HaloESP KawaseDown L$i" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.HawenKawaseDown)
                pass.bindTexture("texture0", src.colorTextureView, linearSampler)
                pass.setUniform(ClientUniformDefine.HAWEN_KAWASE.uboName, blurPyramid.levelUniform(i))
                pass.draw(0, 3)
            }
        }

        for (i in (blurPyramid.size - 1) downTo 1) {
            blurPyramid.level(i - 1).createRenderPass({ "HaloESP KawaseUp L${i - 1}" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.HawenKawaseUp)
                pass.bindTexture("texture0", blurPyramid.level(i).colorTextureView, linearSampler)
                pass.setUniform(ClientUniformDefine.HAWEN_KAWASE.uboName, blurPyramid.levelUniform(i - 1))
                pass.draw(0, 3)
            }
        }

        writeParams(width, height)

        mc.mainRenderTarget.createRenderPass({ "HaloESP Composite" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.EspHalo)
            pass.bindTexture("texture0", mask.colorTextureView, linearSampler)
            pass.bindTexture("texture1", blurPyramid.level(0).colorTextureView, linearSampler)
            pass.setUniform(ClientUniformDefine.ESP_HALO.uboName, haloUbo)
            pass.draw(0, 3)
        }
    }

    private fun writeParams(width: Int, height: Int) {
        val tintColor = tint
        val pulseAmount = if (Pulse.enabled) Pulse.amount.coerceIn(0f, 1f) else 0f
        haloUbo.writeStd140 {
            // mainColor: flat tint (used only when FlatTint is on) + overall opacity
            putVec4(
                tintColor.r / 255f,
                tintColor.g / 255f,
                tintColor.b / 255f,
                opacity.coerceIn(0f, 1f),
            )
            // tintMode: a = 1 → take the tint from mainColor instead of the silhouette
            putVec4(0f, 0f, 0f, flag(flatTint))
            // params1: texel size, outline width, outline mode
            putVec4(
                1f / width,
                1f / height,
                Outline.width.toFloat(),
                Outline.type.index.toFloat(),
            )
            // params2: glow strength / falloff, fill opacity, inner glow
            putVec4(
                Glow.strength.coerceAtLeast(0f),
                Glow.falloff.coerceAtLeast(0.05f),
                Fill.opacity.coerceAtLeast(0f),
                Fill.innerGlow.coerceAtLeast(0f),
            )
            // params3: outline strength / whiteness, glow + fill switches
            putVec4(
                Outline.strength.coerceAtLeast(0f),
                Outline.white.coerceIn(0f, 1f),
                flag(Glow.enabled),
                flag(Fill.enabled),
            )
            // params4: shimmer phase, switch, width, brightness
            putVec4(
                phase(Shimmer.period),
                flag(Shimmer.enabled),
                Shimmer.width.coerceAtLeast(0.005f),
                Shimmer.brightness.coerceAtLeast(0f),
            )
            // params5: pulse amount + phase, saturation, outline switch
            putVec4(
                pulseAmount,
                phase(1f / Pulse.speed.coerceAtLeast(0.05f)),
                saturation.coerceAtLeast(0f),
                flag(Outline.enabled),
            )
        }
    }

    /** Shader switches travel as floats. */
    private fun flag(value: Boolean): Float = if (value) 1f else 0f

    /** Position inside a [periodSeconds] long cycle, 0..1. */
    private fun phase(periodSeconds: Float): Float {
        val periodMs = (periodSeconds * 1000f).toLong().coerceAtLeast(1L)
        return (System.currentTimeMillis() % periodMs) / periodMs.toFloat()
    }

    override fun disable() {
        super.disable()
        blurPyramid.close()
        silhouetteHolder.close()
    }
}
