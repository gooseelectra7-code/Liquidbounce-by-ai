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

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.render.writeStd140
import kotlin.math.cos
import kotlin.math.sin

/**
 * SkyShader
 *
 * A procedural full-screen sky replacement with five modes. Ported from the SystemDLC "SkyShader"
 * (client/module/visual/SkyShader.java, 403 L) — settings, defaults and the uniform-driving math are
 * a 1:1 port of that class; the five GLSL programs under `shaders/sky/` copy the port's
 * `core/sky_{nebula,aurora,stars,plasma,neon}.fsh` math bodies verbatim.
 *
 * Rendering path adapted to the 26.1 render API:
 * - The SystemDLC original drew a full-screen quad from inside the sky-color dome method
 *   (`SkyRendering.method_62302`, the `(FFF)V` sky disc) and cancelled every other vanilla sky draw.
 *   Our `MixinSkyRenderer` mirrors that: it cancels `renderSkyDisc`/`renderDarkDisc`/
 *   `renderSunMoonAndStars`/`renderSunriseAndSunset`/`renderEndSky`/`renderEndFlash` while this
 *   module is active and calls [renderSky] from the HEAD of `renderSkyDisc` (overworld) and
 *   `renderEndSky` (End). Both run in the frame graph's SKY pass, before the terrain (main) pass,
 *   so terrain composes over the replacement exactly like it composes over the vanilla sky.
 *   `MixinCloudRenderer` cancels `CloudRenderer.render` (the clouds pass runs after terrain).
 * - The uniforms the original fed as individual `uniform float uTime; ...` are packed into one
 *   std140 UBO ([ClientUniformDefine.SKY_DATA]); the GLSL declares one matching `SkyData` block.
 * - Time is wall-clock seconds since enable, reset on toggle (matches `SkyShader.time`).
 *
 * Notes / deviations (see the port report):
 * - The LB float DSL has no step parameter, so the original slider steps (Speed 0.1, Size 0.5,
 *   Intensity 0.001, Opacity 0.05) are dropped; defaults and ranges are exact.
 * - Fog: the original relied on Minecraft auto-binding the vanilla `FogStart`/`FogEnd`/`FogColor`
 *   uniforms into the sky program. Our UBO path feeds the shader's own json defaults
 *   (FogStart 0, FogEnd 1, FogColor with alpha 0), which leaves the (byte-identical) fog-tail code
 *   present but inert — the procedural sky is shown without the vanilla horizon fog blend.
 */
object ModuleSkyShader : ClientModule("SkyShader", ModuleCategories.RENDER) {

    enum class Mode(override val tag: String) : Tagged {
        NEBULA("Nebula"),
        AURORA("Aurora"),
        STARS("Stars"),
        PLASMA("Plasma"),
        NEON("Neon"),
    }

    enum class NeonPalette(override val tag: String) : Tagged {
        RGB("RGB"),
        DARK("Dark"),
        LIGHT("Light"),
        CUSTOM("Custom"),
    }

    // Order and defaults from SkyShader.java:69-146.
    private val mode by enumChoice("Mode", Mode.NEBULA)

    /** SkyShader.java:85 default -13395457 == 0xFF3FBFFF. Hidden in Neon (java:137,150-152). */
    private val skyColor by color("Color", Color4b(0xFF3FBFFF.toInt()))
        .doNotIncludeWhen { mode == Mode.NEON }

    /** SkyShader.java:89-103. Only shown for Neon (java:138,168-170). */
    private val neonPalette by enumChoice("NeonPalette", NeonPalette.RGB)
        .doNotIncludeWhen { mode != Mode.NEON }

    /** SkyShader.java:104 default -65536 == 0xFFFF0000. Neon + Custom only (java:139,158-160). */
    private val neonColor1 by color("NeonColor1", Color4b(0xFFFF0000.toInt()))
        .doNotIncludeWhen { !(mode == Mode.NEON && neonPalette == NeonPalette.CUSTOM) }

    /** SkyShader.java:108 default -16711936 == 0xFF00FF00. Neon + Custom only (java:140,154-156). */
    private val neonColor2 by color("NeonColor2", Color4b(0xFF00FF00.toInt()))
        .doNotIncludeWhen { !(mode == Mode.NEON && neonPalette == NeonPalette.CUSTOM) }

    /** SkyShader.java:112 default -16776961 == 0xFF0000FF. Neon + Custom only (java:141,172-174). */
    private val neonColor3 by color("NeonColor3", Color4b(0xFF0000FF.toInt()))
        .doNotIncludeWhen { !(mode == Mode.NEON && neonPalette == NeonPalette.CUSTOM) }

    // SkyShader.java:116-131. Step values (0.1 / 0.5 / 0.001 / 0.05) dropped — no DSL step param.
    private val speed by float("Speed", 1.0f, 0.1f..5.0f)
    private val size by float("Size", 5.0f, 1.0f..20.0f)
    private val intensity by float("Intensity", 0.01f, 0.001f..0.05f)
    private val opacity by float("Opacity", 1.0f, 0.3f..1.0f)

    /** Wall-clock ms at enable; -1 until the first frame draws. Matches `SkyShader.time`. */
    private var startTime = -1L

    private val uniformBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.SKY_DATA.createSingleBuffer()
    }

    override fun onEnabled() {
        startTime = -1L
        super.onEnabled()
    }

    override fun onDisabled() {
        startTime = -1L
        super.onDisabled()
    }

    /**
     * True while the module should replace the vanilla sky. Read by `MixinSkyRenderer` /
     * `MixinCloudRenderer` to decide whether to cancel each vanilla sky/cloud draw.
     */
    @JvmStatic
    fun isActive(): Boolean = running && mc.player != null && mc.level != null

    /**
     * Draws the procedural sky into the main render target's colour view. Called from the HEAD of
     * `SkyRenderer.renderSkyDisc` / `renderEndSky` (the vanilla draw is cancelled in the same hook),
     * so it runs in the SKY pass before terrain. Depth is untouched (colour-only pass), so terrain
     * draws over it.
     */
    @JvmStatic
    fun renderSky() {
        if (!running) {
            return
        }
        if (mc.player == null || mc.level == null) {
            return
        }

        val main = mc.mainRenderTarget
        val colorView = main.colorTextureView ?: return
        val width = main.width
        val height = main.height
        if (width <= 0 || height <= 0) {
            return
        }

        if (startTime < 0L) {
            startTime = System.currentTimeMillis()
        }
        val time = (System.currentTimeMillis() - startTime) / 1000f

        // Camera basis, copied verbatim from SkyShader.java:244-257 (note the negated yaw/pitch).
        val camera = mc.gameRenderer.mainCamera
        val f6 = Math.toRadians(camera.yRot().toDouble()).toFloat()
        val f7 = Math.toRadians(camera.xRot().toDouble()).toFloat()
        val fov = mc.options.fov().get().toFloat()
        val f9 = cos(-f6)
        val f10 = sin(-f6)
        val f11 = cos(-f7)
        val f12 = sin(-f7)
        // uCamRight = (f9, 0, -f10); uCamUp = (f10*f12, f11, f9*f12); uCamForward = (-f10*f11, f12, -f9*f11)

        val color = skyColor
        val neon = neonColors()
        val n1 = neon[0]
        val n2 = neon[1]
        val n3 = neon[2]

        val uniform = uniformBuffer
        uniform.writeStd140 {
            putVec2(width.toFloat(), height.toFloat())          // uResolution
            putFloat(time)                                      // uTime
            putFloat(opacity)                                   // uAlpha
            putFloat(speed)                                     // uSpeed
            putFloat(size)                                      // uScale
            putFloat(intensity)                                 // uIntensity
            putFloat(fov)                                       // uFov
            putFloat(0f)                                        // FogStart (json default)
            putFloat(1f)                                        // FogEnd   (json default)
            putVec3(color.r / 255f, color.g / 255f, color.b / 255f) // uColor
            putVec3(n1[0], n1[1], n1[2])                        // uColor1
            putVec3(n2[0], n2[1], n2[2])                        // uColor2
            putVec3(n3[0], n3[1], n3[2])                        // uColor3
            putVec3(f9, 0f, -f10)                               // uCamRight
            putVec3(f10 * f12, f11, f9 * f12)                   // uCamUp
            putVec3(-f10 * f11, f12, -f9 * f11)                 // uCamForward
            putVec4(0f, 0f, 0f, 0f)                             // FogColor (alpha 0 -> fog tail inert)
        }

        val pipeline = when (mode) {
            Mode.NEBULA -> ClientRenderPipelines.SkyNebula
            Mode.AURORA -> ClientRenderPipelines.SkyAurora
            Mode.STARS -> ClientRenderPipelines.SkyStars
            Mode.PLASMA -> ClientRenderPipelines.SkyPlasma
            Mode.NEON -> ClientRenderPipelines.SkyNeon
        }

        colorView.createRenderPass({ "SkyShader $mode pass" }).use { pass ->
            pass.setPipeline(pipeline)
            ClientUniformDefine.SKY_DATA.setTo(pass, uniform)
            pass.draw(0, 3)
        }
    }

    /**
     * Resolves uColor1 / uColor2 / uColor3 from the neon palette, reproducing SkyShader.java:337-385.
     * Index 0 -> uColor1, 1 -> uColor2, 2 -> uColor3 (the shader reads only uColor3.x).
     */
    private fun neonColors(): Array<FloatArray> = when (neonPalette) {
        NeonPalette.DARK -> arrayOf(
            floatArrayOf(0.1f, 0.1f, 0.1f),
            floatArrayOf(0.25f, 0.25f, 0.25f),
            floatArrayOf(0.45f, 0.45f, 0.45f),
        )
        NeonPalette.LIGHT -> arrayOf(
            floatArrayOf(0.8f, 0.8f, 0.8f),
            floatArrayOf(0.95f, 0.95f, 0.95f),
            floatArrayOf(1.0f, 1.0f, 1.0f),
        )
        NeonPalette.CUSTOM -> arrayOf(
            neonColor1.toVec3(),
            neonColor2.toVec3(),
            neonColor3.toVec3(),
        )
        // RGB (default): red / green / blue (SkyShader.java:371-384).
        NeonPalette.RGB -> arrayOf(
            floatArrayOf(1.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 1.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 1.0f),
        )
    }

    private fun Color4b.toVec3(): FloatArray = floatArrayOf(r / 255f, g / 255f, b / 255f)
}
