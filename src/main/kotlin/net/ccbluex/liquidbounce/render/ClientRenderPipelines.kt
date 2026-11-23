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

@file:Suppress("NOTHING_TO_INLINE", "TooManyFunctions")
package net.ccbluex.liquidbounce.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.platform.DestFactor
import com.mojang.blaze3d.platform.SourceFactor
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.ccbluex.fastutil.fastIterator
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.kotlin.optional
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object ClientRenderPipelines {

    private val renderPipelines = Object2ObjectOpenHashMap<Identifier, RenderPipeline>()

    /**
     * Premultiplied-alpha blending, for compositing a render target that was drawn with
     * premultiplied colour (the HUD blur overlay, the post effects).
     */
    private val PREMULTIPLIED_BLEND = BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA

    internal inline fun newPipeline(
        name: String,
        builderAction: RenderPipeline.Builder.() -> Unit,
    ): RenderPipeline {
        val id = LiquidBounce.identifier("pipeline/$name")
        return RenderPipeline.Builder()
            .withLocation(id)
            .apply(builderAction)
            .build().also { r ->
                renderPipelines.put(id, r)?.let { error("Duplicated render pipeline: $id") }
            }
    }

    inline fun RenderPipeline.Builder.withUniformBuffer(define: ClientUniformDefine) =
        withUniform(define.uboName, UniformType.UNIFORM_BUFFER)

    private inline fun RenderPipeline.Builder.forWorldRender(noDepthTest: Boolean = true) {
        withCull(false)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        if (noDepthTest) withDepthStencilState(optional())
    }

    inline fun RenderPipeline.Builder.screenQuadSnippet() = apply {
        withVertexShader("core/screenquad")
        withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
    }

    private fun RenderPipeline.Builder.posColorSnippet(mode: VertexFormat.Mode) {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        withVertexFormat(DefaultVertexFormat.POSITION_COLOR, mode)
    }

    private inline fun RenderPipeline.Builder.relativePosSnippet(mode: VertexFormat.Mode) {
        withVertexShader(ClientShaders.Vertex.PosRelativeToCamera)
        withFragmentShader(ClientShaders.Fragment.PosRelativeToCamera)
        withVertexFormat(DefaultVertexFormat.POSITION, mode)
    }

    private inline fun RenderPipeline.Builder.relativePosColorSnippet(mode: VertexFormat.Mode) {
        withVertexShader(ClientShaders.Vertex.PosColorRelativeToCamera)
        withFragmentShader("core/position_color")
        withVertexFormat(DefaultVertexFormat.POSITION_COLOR, mode)
    }

    /**
     * Texture compositing. These were the browser (JCEF) pipelines; the browser went on 2026-09-24
     * and these two stayed because the HUD blur, the post effects and the loading overlay use them.
     */
    object Composite {
        @JvmField
        val SMOOTH_TEXTURE = newPipeline("composite/smooth_texture") {
            withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
            withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            withDepthStencilState(optional())
        }

        /**
         * @see RenderPipelines.ENTITY_OUTLINE_BLIT
         */
        @JvmField
        val Blit = newPipeline("composite/blit") {
            screenQuadSnippet()
            withFragmentShader("core/blit_screen")
            withSampler("InSampler")
            withColorTargetState(ColorTargetState(optional(PREMULTIPLIED_BLEND), ColorTargetState.WRITE_COLOR))
            withDepthStencilState(optional())
        }
    }

    object GUI {
        private fun RenderPipeline.Builder.guiPosColorSnippet(mode: VertexFormat.Mode) {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexFormat(DefaultVertexFormat.POSITION_COLOR, mode)
        }

        private val CircleLut = newPipeline("gui/circle_lut") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.GuiCircleLut)
            withFragmentShader(ClientShaders.Fragment.GuiCircleLut)
            withSampler("Sampler0")
            withVertexFormat(ClientVertexFormats.GUI_CIRCLE_LUT, VertexFormat.Mode.QUADS)
        }

        private val RoundedRect = newPipeline("gui/rounded_rect") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.GuiRoundedRect)
            withFragmentShader(ClientShaders.Fragment.GuiRoundedRect)
            withVertexFormat(ClientVertexFormats.GUI_ROUNDED_RECT, VertexFormat.Mode.QUADS)
        }

        /**
         * Drop-shadow rounded rect: the same SDF rounded box as [RoundedRect], but the fragment
         * shader outputs a blurred silhouette (alpha fades over `blur` GUI units past the edge).
         * Drawn as an expanded quad BEHIND the opaque fill. Reuses the rounded-rect vertex shader
         * and [ClientVertexFormats.GUI_ROUNDED_RECT] format (Size = original rect, Parameters.x =
         * radius, Parameters.y = per-side margin, StrokeWidth = blur). Standard GUI alpha blend.
         *
         * @see net.ccbluex.liquidbounce.render.gui.element.RoundedRectShadowGuiElementRenderState
         */
        private val RoundedRectShadow = newPipeline("gui/rounded_rect_shadow") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.GuiRoundedRect)
            withFragmentShader(ClientShaders.Fragment.GuiRoundedRectShadow)
            withVertexFormat(ClientVertexFormats.GUI_ROUNDED_RECT, VertexFormat.Mode.QUADS)
        }

        /**
         * MSDF text: samples a multi-channel signed-distance-field atlas (Sampler0) and
         * recovers crisp, resolution-independent glyph edges via [median] + screen-space
         * derivatives. Used by the native HUD font (not LB's downscaled bitmap font).
         */
        private val MsdfText = newPipeline("gui/msdf_text") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.MsdfText)
            withFragmentShader(ClientShaders.Fragment.MsdfText)
            withSampler("Sampler0")
            withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        }

        /**
         * System-font MSDF text: identical to [MsdfText] (same vertex shader, sampler, format and
         * vertex-emit convention) but the fragment shader uses `DISTANCE_RANGE = 12`, matching the
         * SystemDLC `type=mtsdf` atlases (distanceRange=12). Emit glyph quads exactly as HudFont
         * does through [msdfText] — see SystemFont.
         */
        private val SystemMsdfText = newPipeline("gui/system_msdf_text") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.MsdfText)
            withFragmentShader(ClientShaders.Fragment.SystemMsdfText)
            withSampler("Sampler0")
            withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        }

        /**
         * Rounded textured quad: samples a texture (Sampler0) and applies the rounded-rect SDF mask, so an
         * image gets rounded corners. Reuses the rounded-rect vertex shader/format (UV0=local, Size, radius
         * in Parameters). Used for the rounded TargetHUD head.
         */
        private val RoundedTex = newPipeline("gui/rounded_tex") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.GuiRoundedRect)
            withFragmentShader(ClientShaders.Fragment.RoundedTex)
            withSampler("Sampler0")
            withVertexFormat(ClientVertexFormats.GUI_ROUNDED_RECT, VertexFormat.Mode.QUADS)
        }

        private val HawenRoundedRect = newPipeline("gui/hawen_rounded_rect") {
            withSnippet(RenderPipelines.GUI_SNIPPET)
            withVertexShader(ClientShaders.Vertex.HawenRoundedRect)
            withFragmentShader(ClientShaders.Fragment.HawenRoundedRect)
            withVertexFormat(ClientVertexFormats.HAWEN_ROUNDED_RECT, VertexFormat.Mode.QUADS)
        }

        private val Lines = newPipeline("gui/lines") {
            guiPosColorSnippet(VertexFormat.Mode.DEBUG_LINES)
        }

        private val Triangles = newPipeline("gui/triangles") {
            guiPosColorSnippet(VertexFormat.Mode.TRIANGLES)
        }

        private val LinesNoCull = newPipeline("gui/lines_no_cull") {
            guiPosColorSnippet(VertexFormat.Mode.DEBUG_LINES)
            withCull(false)
        }

        private val TrianglesNoCull = newPipeline("gui/triangles_no_cull") {
            guiPosColorSnippet(VertexFormat.Mode.TRIANGLES)
            withCull(false)
        }

        @JvmField
        val TexQuadNoCull = newPipeline("gui/tex_quad_no_cull") {
            withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
            withCull(false)
        }

        /**
         * Additive-blended GUI texture quads (`SRC_ALPHA, ONE`), the screen-space counterpart of
         * [ClientRenderPipelines.GlowTexQuads]. Overlapping sprites brighten instead of stacking
         * into opaque squares, which is what glow-style overlays drawn on top of the world need.
         */
        @JvmField
        val TexQuadAdditiveNoCull = newPipeline("gui/tex_quad_additive_no_cull") {
            withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
            withCull(false)
            withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
        }

        @JvmStatic
        fun lines(cull: Boolean) = if (cull) Lines else LinesNoCull

        @JvmStatic
        fun triangles(cull: Boolean) = if (cull) Triangles else TrianglesNoCull

        @JvmStatic
        fun circleLut() = CircleLut

        @JvmStatic
        fun roundedRect() = RoundedRect

        @JvmStatic
        fun roundedRectShadow() = RoundedRectShadow

        @JvmStatic
        fun msdfText() = MsdfText

        @JvmStatic
        fun systemMsdfText() = SystemMsdfText

        @JvmStatic
        fun roundedTex() = RoundedTex

        @JvmStatic
        fun hawenRoundedRect() = HawenRoundedRect
    }

    /**
     * @see RenderPipelines.LINES_TRANSLUCENT
     */
    @JvmField
    val LinesWithWidth = newPipeline("lines_with_width") {
        withSnippet(RenderPipelines.LINES_SNIPPET)
        withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
        forWorldRender()
    }

    @JvmField
    val Lines = newPipeline("lines") {
        posColorSnippet(VertexFormat.Mode.DEBUG_LINES)
        forWorldRender()
    }

    private val LinesRelativeToCamera = newPipeline("lines_relative_to_camera") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        relativePosColorSnippet(VertexFormat.Mode.DEBUG_LINES)
        withUniformBuffer(ClientUniformDefine.MESH_BASE_BLOCK_POS)
        withUniformBuffer(ClientUniformDefine.DISTANCE_FADE)
        forWorldRender()
    }

    /**
     * The `useColor = false` counterpart of [LinesRelativeToCamera]: callers that let the
     * ColorModulator carry the colour write POSITION only (see
     * [net.ccbluex.liquidbounce.render.addShapeOutlines] with a `null` colour), so this must be a
     * POSITION pipeline. Declaring POSITION_COLOR here made `BufferBuilder.endLastVertex` throw
     * `Missing elements in vertex: Color` — mirror [QuadsRelativeToCameraNoColor].
     */
    private val LinesRelativeToCameraNoColor = newPipeline("lines_relative_to_camera_no_color") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        relativePosSnippet(VertexFormat.Mode.DEBUG_LINES)
        withUniformBuffer(ClientUniformDefine.MESH_BASE_BLOCK_POS)
        withUniformBuffer(ClientUniformDefine.DISTANCE_FADE)
        forWorldRender()
    }

    @JvmStatic
    fun relativeLines(useColor: Boolean) = if (useColor) LinesRelativeToCamera else LinesRelativeToCameraNoColor

    @JvmField
    val LineStrip = newPipeline("line_strip") {
        posColorSnippet(VertexFormat.Mode.DEBUG_LINE_STRIP)
        forWorldRender()
    }

    @JvmField
    val Triangles = newPipeline("triangles") {
        posColorSnippet(VertexFormat.Mode.TRIANGLES)
        forWorldRender()
    }

    /**
     * Depth state for the glow particles: normal LEQUAL depth test so walls and entity models
     * occlude them (they are NOT visible through geometry), with depth write off so overlapping
     * additive glows still blend together correctly.
     */
    private val GLOW_DEPTH = DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)

    /**
     * Additive-blended ([SRC_ALPHA, ONE]) untextured triangles for self-glowing geometry
     * (e.g. the target-ESP crystals) so the whole surface emits light uniformly. Depth-tested
     * so it is occluded by walls / the target model.
     */
    val GlowTriangles = newPipeline("glow_triangles") {
        posColorSnippet(VertexFormat.Mode.TRIANGLES)
        withCull(false)
        withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
        withDepthStencilState(GLOW_DEPTH)
    }

    /**
     * The same additive triangles as [GlowTriangles], drawn through everything in front of them.
     *
     * For the handful of marks whose entire job is to be findable — a beam standing over a
     * navigation destination on the far side of a hill. A glow that is occluded is invisible exactly
     * when it is wanted, so this one is not; use it sparingly and dimly, because nothing else on
     * screen can hide it.
     */
    @JvmField
    val GlowTrianglesThroughWalls = newPipeline("glow_triangles_through_walls") {
        posColorSnippet(VertexFormat.Mode.TRIANGLES)
        withCull(false)
        withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
    }

    private val TriangleStrip = newPipeline("triangle_strip") {
        posColorSnippet(VertexFormat.Mode.TRIANGLE_STRIP)
        forWorldRender(noDepthTest = false)
    }

    private val TriangleStripNoDepthTest = newPipeline("triangle_strip_no_depth_test") {
        posColorSnippet(VertexFormat.Mode.TRIANGLE_STRIP)
        forWorldRender(noDepthTest = true)
    }

    @JvmStatic
    fun triangleStrip(noDepthTest: Boolean) = if (noDepthTest) TriangleStripNoDepthTest else TriangleStrip

    @JvmField
    val Quads = newPipeline("quads") {
        posColorSnippet(VertexFormat.Mode.QUADS)
        forWorldRender()
    }

    private val QuadsRelativeToCamera = newPipeline("quads_relative_to_camera") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        relativePosColorSnippet(VertexFormat.Mode.QUADS)
        withUniformBuffer(ClientUniformDefine.MESH_BASE_BLOCK_POS)
        withUniformBuffer(ClientUniformDefine.DISTANCE_FADE)
        forWorldRender()
    }

    private val QuadsRelativeToCameraNoColor = newPipeline("quads_relative_to_camera_no_color") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        relativePosSnippet(VertexFormat.Mode.QUADS)
        withUniformBuffer(ClientUniformDefine.MESH_BASE_BLOCK_POS)
        withUniformBuffer(ClientUniformDefine.DISTANCE_FADE)
        forWorldRender()
    }

    @JvmStatic
    fun relativeQuads(useColor: Boolean) = if (useColor) QuadsRelativeToCamera else QuadsRelativeToCameraNoColor

    /**
     * @see net.ccbluex.liquidbounce.features.module.modules.render.ModuleStorageESP
     * @see net.ccbluex.liquidbounce.features.module.modules.render.ModuleBlockESP
     */
    private val OutlineQuads = newPipeline("outline_quads") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        withSnippet(RenderPipelines.GLOBALS_SNIPPET)
        withVertexShader(ClientShaders.Vertex.PosColorRelativeToCamera)
        withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        withUniformBuffer(ClientUniformDefine.MESH_BASE_BLOCK_POS)
        withUniformBuffer(ClientUniformDefine.DISTANCE_FADE)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }

    private val OutlineQuadsNoColor = newPipeline("outline_quads_no_color") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        withSnippet(RenderPipelines.GLOBALS_SNIPPET)
        withVertexShader(ClientShaders.Vertex.PosRelativeToCamera)
        withFragmentShader(ClientShaders.Fragment.PosRelativeToCamera)
        withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
        withUniformBuffer(ClientUniformDefine.MESH_BASE_BLOCK_POS)
        withUniformBuffer(ClientUniformDefine.DISTANCE_FADE)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }

    @JvmStatic
    fun outlineQuads(useColor: Boolean) = if (useColor) OutlineQuads else OutlineQuadsNoColor

    @JvmField
    val TexQuads = newPipeline("tex_quads") {
        withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
        withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        forWorldRender()
    }

    /** Textured world quads that respect the scene depth buffer. */
    @JvmField
    val TexQuadsDepthTest = newPipeline("tex_quads_depth_test") {
        withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
        withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        withCull(false)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
    }

    /**
     * Additive-blended textured quads (`SRC_ALPHA, ONE`) for soft glow particles.
     * Used by the wyvern-style target ESP appearances so overlapping glow sprites
     * brighten instead of stacking into opaque squares.
     */
    val GlowTexQuads = newPipeline("glow_tex_quads") {
        withSnippet(RenderPipelines.GUI_TEXTURED_SNIPPET)
        withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        withCull(false)
        withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
        withDepthStencilState(GLOW_DEPTH)
    }

    private fun RenderPipeline.Builder.roundedRectSnippet() {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        withVertexShader(ClientShaders.Vertex.Circle)
        withFragmentShader(ClientShaders.Fragment.RoundedRect)
        withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        withUniformBuffer(ClientUniformDefine.ROUNDED_RECT)
    }

    private fun RenderPipeline.Builder.gradientCircleSnippet() {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        withVertexShader(ClientShaders.Vertex.GradientCircle)
        withFragmentShader(ClientShaders.Fragment.GradientCircle)
        withVertexFormat(ClientVertexFormats.GRADIENT_CIRCLE, VertexFormat.Mode.QUADS)
    }

    private val RoundedRect = newPipeline("rounded_rect") {
        roundedRectSnippet()
        forWorldRender(noDepthTest = false)
    }

    private val RoundedRectNoDepthTest = newPipeline("rounded_rect_no_depth_test") {
        roundedRectSnippet()
        forWorldRender(noDepthTest = true)
    }

    fun roundedRect(noDepthTest: Boolean) = if (noDepthTest) RoundedRectNoDepthTest else RoundedRect

    private val GradientCircle = newPipeline("gradient_circle") {
        gradientCircleSnippet()
        forWorldRender(noDepthTest = false)
    }

    private val GradientCircleNoDepthTest = newPipeline("gradient_circle_no_depth_test") {
        gradientCircleSnippet()
        forWorldRender(noDepthTest = true)
    }

    fun gradientCircle(noDepthTest: Boolean) =
        if (noDepthTest) GradientCircleNoDepthTest else GradientCircle

    @JvmField
    val KillWave = newPipeline("kill_wave") {
        withSnippet(RenderPipelines.DEBUG_FILLED_SNIPPET)
        withVertexShader(ClientShaders.Vertex.KillWave)
        withFragmentShader(ClientShaders.Fragment.KillWave)
        withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.TRIANGLES)
        withUniformBuffer(ClientUniformDefine.KILL_WAVE)
        forWorldRender(noDepthTest = false)
    }

    /**
     * Full-screen desaturation for FragEffects, standing in for the vanilla `desaturate.json`
     * post-effect that no longer exists. Samples a copy of the scene, so it must never be pointed at
     * the target it is drawing into.
     */
    @JvmField
    val FragDesaturate = newPipeline("frageffects/desaturate") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.FragDesaturate)
        withSampler("InSampler")
        withUniformBuffer(ClientUniformDefine.FRAG_DESATURATE)
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    /**
     * Full-screen saturation post-pass (SystemDLC port). Samples a copy of the scene, so it must
     * never be pointed at the target it is drawing into.
     */
    @JvmField
    val Saturation = newPipeline("saturation/saturation") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.Saturation)
        withSampler("InSampler")
        withUniformBuffer(ClientUniformDefine.SATURATION)
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    // Special

    /**
     * @see RenderPipelines.ENTITY_OUTLINE_BLIT
     * @see RenderPipelines.OUTLINE_SNIPPET
     */
    @JvmField
    val Outline = newPipeline("outline") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.EntityOutline)
        withSampler("InSampler")
        withColorTargetState(
            ColorTargetState(
                optional(BlendFunction.ENTITY_OUTLINE_BLIT),
                ColorTargetState.WRITE_COLOR,
            )
        )
        withDepthStencilState(optional())
    }

    @JvmField
    val ItemChams = newPipeline("item_chams") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.Glow)
        withSampler("texture0")
        withSampler("image")
        withUniformBuffer(ClientUniformDefine.HAND_ITEM_LIGHTMAP)
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    @JvmField
    val HawenComposite = newPipeline("hawen_composite") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.HawenComposite)
        withSampler("texture0")
        withSampler("texture1")
        withUniformBuffer(ClientUniformDefine.HAWEN_COMPOSITE)
        // Premultiplied additive — composite outputs `vec4(rgb, 0)` for the halo
        // (additive: dst = src + bg) and `vec4(rgb*a, a)` for the chams overlay.
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        withDepthStencilState(optional())
    }

    @JvmField
    val HawenKawaseDown = newPipeline("hawen_kawase_down") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.HawenKawaseDown)
        withSampler("texture0")
        withUniformBuffer(ClientUniformDefine.HAWEN_KAWASE)
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    @JvmField
    val HawenKawaseUp = newPipeline("hawen_kawase_up") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.HawenKawaseUp)
        withSampler("texture0")
        withUniformBuffer(ClientUniformDefine.HAWEN_KAWASE)
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    /**
     * Scan-wave kill effect (NightShift port): a full-screen pass over the finished scene that
     * rebuilds world positions from the depth buffer. Purely additive, so it only brightens.
     */
    @JvmField
    val KillEffectScan = newPipeline("kill_effect_scan") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.KillEffectScan)
        withSampler("DepthSampler")
        withUniformBuffer(ClientUniformDefine.SCAN_WAVE)
        withColorTargetState(ColorTargetState(BlendFunction.ADDITIVE))
        withDepthStencilState(optional())
    }

    // SkyShader (SystemDLC port): five procedural full-screen sky programs sharing SKY_DATA.
    @JvmField
    val SkyNebula = newPipeline("sky_nebula") {
        screenQuadSnippet()
        withVertexShader(ClientShaders.Vertex.SkyNebula)
        withFragmentShader(ClientShaders.Fragment.SkyNebula)
        withUniformBuffer(ClientUniformDefine.SKY_DATA)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }
    @JvmField
    val SkyAurora = newPipeline("sky_aurora") {
        screenQuadSnippet()
        withVertexShader(ClientShaders.Vertex.SkyAurora)
        withFragmentShader(ClientShaders.Fragment.SkyAurora)
        withUniformBuffer(ClientUniformDefine.SKY_DATA)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }
    @JvmField
    val SkyStars = newPipeline("sky_stars") {
        screenQuadSnippet()
        withVertexShader(ClientShaders.Vertex.SkyStars)
        withFragmentShader(ClientShaders.Fragment.SkyStars)
        withUniformBuffer(ClientUniformDefine.SKY_DATA)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }
    @JvmField
    val SkyPlasma = newPipeline("sky_plasma") {
        screenQuadSnippet()
        withVertexShader(ClientShaders.Vertex.SkyPlasma)
        withFragmentShader(ClientShaders.Fragment.SkyPlasma)
        withUniformBuffer(ClientUniformDefine.SKY_DATA)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }
    @JvmField
    val SkyNeon = newPipeline("sky_neon") {
        screenQuadSnippet()
        withVertexShader(ClientShaders.Vertex.SkyNeon)
        withFragmentShader(ClientShaders.Fragment.SkyNeon)
        withUniformBuffer(ClientUniformDefine.SKY_DATA)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
    }

    /**
     * Halo ESP composite (NightShift port). Additive: the shader writes its contribution straight
     * into rgb with alpha 1, so LIGHTNING (SRC_ALPHA, ONE) adds it on top of the scene and the
     * channels that overshoot give the white core on the rim.
     */
    @JvmField
    val EspHalo = newPipeline("esp_halo") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.EspHalo)
        withSampler("texture0")
        withSampler("texture1")
        withUniformBuffer(ClientUniformDefine.ESP_HALO)
        withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
        withDepthStencilState(optional())
    }

    /**
     * CrystalAura soft glow composite.
     *
     * Premultiplied "over" (`dst = src.rgb + dst * (1 - src.a)`), the same blend the halo composite
     * uses: the shader emits `vec4((sharp.rgb + glow) * alpha, sharp.a * alpha)`, so the blurred
     * halo lands additively where alpha is 0 and the sharp silhouette blends normally where it is
     * not. One pass, both behaviours, and nothing anywhere with a hard edge.
     *
     * @see net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraGlow
     */
    @JvmField
    val AuraGlow = newPipeline("aura/aura_glow") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.AuraGlow)
        withSampler("texture0")
        withSampler("texture1")
        withUniformBuffer(ClientUniformDefine.AURA_GLOW)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        withDepthStencilState(optional())
    }

    /**
     * Full-screen copy of the finished world into an offscreen target, so [AuraBlastDome] has
     * something to refract while it writes into the main target. Replaces every pixel
     * ([ColorTargetState.DEFAULT], no blend), so the destination never needs clearing.
     */
    @JvmField
    val AuraSceneCopy = newPipeline("aura/scene_copy") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.AuraSceneCopy)
        withSampler("InSampler")
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    /**
     * CrystalAura blast dome: a world-anchored refracting hemisphere.
     *
     * Reads the scene snapshot written by [AuraSceneCopy] plus the main target's depth attachment,
     * and writes the displaced colour back over the main target premultiplied by its own coverage —
     * at coverage 0 the sample it writes is the destination pixel itself, so the dome fades in and
     * out without a border. Must be drawn through a colour-only render pass: the depth texture it
     * samples is the very attachment the main target owns. Driven by
     * `aura.render.AuraBlastDome`.
     */
    @JvmField
    val AuraBlastDome = newPipeline("aura/blast_dome") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.AuraBlastDome)
        withSampler("SceneSampler")
        withSampler("DepthSampler")
        withUniformBuffer(ClientUniformDefine.AURA_DOME)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        withDepthStencilState(optional())
    }

    /**
     * CrystalAura forge haze: the heat plume over the block the aura spams crystals on.
     *
     * The same arrangement as [AuraBlastDome] and for the same reasons — it reads the scene
     * snapshot written by [AuraSceneCopy] plus the main target's depth attachment, writes the
     * displaced colour back over the main target premultiplied by its own coverage, and must be
     * drawn through a colour-only render pass because the depth texture it samples is the very
     * attachment the main target owns. Driven by `aura.render.AuraForge`.
     */
    @JvmField
    val AuraForgeHaze = newPipeline("aura/forge_haze") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.AuraForgeHaze)
        withSampler("SceneSampler")
        withSampler("DepthSampler")
        withUniformBuffer(ClientUniformDefine.AURA_FORGE)
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        withDepthStencilState(optional())
    }

    @JvmField
    val HawenOutline = newPipeline("hawen_outline") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.HawenOutline)
        withSampler("texture0")
        withUniformBuffer(ClientUniformDefine.HAWEN_OUTLINE)
        // Straight (non-premultiplied) alpha: the shader emits vec4(rgb, a).
        withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        withDepthStencilState(optional())
    }

    /**
     * BurningHands (exploit-client port): REPLACE blend — the pass fully overwrites the
     * offscreen target (ONE/ZERO, ONE/ZERO), used by the glow and trail passes.
     */
    private val BURN_REPLACE_BLEND =
        BlendFunction(SourceFactor.ONE, DestFactor.ZERO, SourceFactor.ONE, DestFactor.ZERO)

    /**
     * BurningHands (exploit-client port): additive composite onto the main framebuffer —
     * color accumulates (`ONE, ONE`), destination alpha is preserved (`ZERO, ONE`).
     */
    private val BURN_ADDITIVE_BLEND =
        BlendFunction(SourceFactor.ONE, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE)

    private fun RenderPipeline.Builder.burnSnippet() {
        // Fullscreen triangle-pair drawn from gl_VertexID — no vertex buffer required.
        withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        withUniformBuffer(ClientUniformDefine.BURN_DATA)
        withCull(false)
        withDepthStencilState(optional())
    }

    @JvmField
    val BurnGlow = newPipeline("burninghands/burn_glow") {
        withVertexShader(ClientShaders.Vertex.BurnGlow)
        withFragmentShader(ClientShaders.Fragment.BurnGlow)
        burnSnippet()
        withSampler("HandSampler")
        withSampler("MaskSampler")
        withColorTargetState(ColorTargetState(BURN_REPLACE_BLEND))
    }

    @JvmField
    val BurnTrail = newPipeline("burninghands/burn_trail") {
        withVertexShader(ClientShaders.Vertex.BurnTrail)
        withFragmentShader(ClientShaders.Fragment.BurnTrail)
        burnSnippet()
        withSampler("TrailSampler")
        withSampler("GlowSampler")
        withSampler("HandSampler")
        withSampler("MaskSampler")
        withColorTargetState(ColorTargetState(BURN_REPLACE_BLEND))
    }

    @JvmField
    val BurnComposite = newPipeline("burninghands/burn_composite") {
        withVertexShader(ClientShaders.Vertex.BurnComposite)
        withFragmentShader(ClientShaders.Fragment.BurnComposite)
        burnSnippet()
        withSampler("MaskSampler")
        withSampler("GlowSampler")
        withSampler("TrailSampler")
        withColorTargetState(ColorTargetState(BURN_ADDITIVE_BLEND))
    }

    @JvmField
    val GuiBlur = newPipeline("blur") {
        screenQuadSnippet()
        withFragmentShader(ClientShaders.Fragment.GuiBlur)
        withSampler("texture0")
        withSampler("overlay")
        withUniformBuffer(ClientUniformDefine.GUI_BLUR)
        withCull(false)
        withColorTargetState(ColorTargetState.DEFAULT)
        withDepthStencilState(optional())
    }

    @JvmField
    val Blend = newPipeline("blend") {
        withVertexShader(ClientShaders.Vertex.PlainPosTex)
        withFragmentShader(ClientShaders.Fragment.Blend)
        withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLES)
        withSampler("texture0")
        withUniformBuffer(ClientUniformDefine.BLEND)
        withColorTargetState(ColorTargetState.DEFAULT)
    }

    /**
     * Precompiles every registered pipeline.
     *
     * Called from the TAIL of `ShaderManager.apply` (`MixinShaderManager.java:31`), i.e. from inside a resource
     * reload: a Throwable escaping here aborts the reload and the game exits before the main menu. One pipeline
     * whose GLSL a particular driver refuses must not cost the whole client, so each is compiled on its own and a
     * failure is logged and counted - that pipeline is simply unavailable, and the effect using it degrades the way
     * it would on any other GPU-side failure.
     */
    fun precompile() {
        Composite
        GUI

        var failed = 0
        renderPipelines.fastIterator().forEach { (id, pipeline) ->
            runCatching { gpuDevice.precompilePipeline(pipeline, ClientShaders) }.onFailure { error ->
                failed++
                logger.error("Failed to compile render pipeline '$id'.", error)
            }
        }

        val loaded = renderPipelines.size - failed
        logger.info("Loaded $loaded Render Pipelines.")
        if (failed > 0) {
            logger.warn("$failed Render Pipelines failed to compile; the effects using them are unavailable.")
        }
    }

}
