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

package net.ccbluex.liquidbounce.render

import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.shaders.ShaderType
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.ccbluex.liquidbounce.LiquidBounce
import net.minecraft.resources.Identifier

sealed class ClientShaders(val type: ShaderType) : ShaderSource {

    private val shaders = Object2ObjectOpenHashMap<Identifier, String>()

    protected operator fun String.invoke(path: String): Identifier = newShader("${type.getName()}/${this}", path = path)

    object Vertex : ClientShaders(ShaderType.VERTEX) {

        @JvmField
        val PlainPosTex = "plain_pos_tex"("shaders/position_tex.vert")

        @JvmField
        val PosRelativeToCamera = "pos_relative_to_camera"("shaders/relative_to_camera/position.vsh")

        @JvmField
        val PosColorRelativeToCamera = "pos_color_relative_to_camera"("shaders/relative_to_camera/position_color.vsh")

        @JvmField
        val Sobel = "sobel"("shaders/sobel.vert")

        @JvmField
        val PlainProjection = "plane_projection"("shaders/plane_projection.vert")

        @JvmField
        val Circle = "circle"("shaders/circle/circle.vsh")

        @JvmField
        val GuiCircleLut = "gui_circle_lut"("shaders/circle/gui_circle_lut.vsh")

        @JvmField
        val GuiRoundedRect = "gui_rounded_rect"("shaders/gui/rounded_rect.vsh")

        @JvmField
        val MsdfText = "msdf_text"("shaders/gui/msdf_text.vsh")

        @JvmField
        val HawenRoundedRect = "hawen_rounded_rect"("shaders/hawen/rounded_rect.vsh")

        @JvmField
        val GradientCircle = "gradient_circle"("shaders/circle/gradient_circle.vsh")

        @JvmField
        val KillWave = "kill_wave"("shaders/kill_effect/wave.vsh")

        @JvmField
        val BurnGlow = "burn_glow"("shaders/burninghands/burn_glow.vsh")

        @JvmField
        val BurnTrail = "burn_trail"("shaders/burninghands/burn_trail.vsh")

        @JvmField
        val BurnComposite = "burn_composite"("shaders/burninghands/burn_composite.vsh")

        @JvmField
        val SkyNebula = "sky_nebula"("shaders/sky/sky_nebula.vsh")
        @JvmField
        val SkyAurora = "sky_aurora"("shaders/sky/sky_aurora.vsh")
        @JvmField
        val SkyStars = "sky_stars"("shaders/sky/sky_stars.vsh")
        @JvmField
        val SkyPlasma = "sky_plasma"("shaders/sky/sky_plasma.vsh")
        @JvmField
        val SkyNeon = "sky_neon"("shaders/sky/sky_neon.vsh")

    }

    object Fragment : ClientShaders(ShaderType.FRAGMENT) {

        @JvmField
        val PosRelativeToCamera = "pos_relative_to_camera"("shaders/relative_to_camera/position.fsh")

        @JvmField
        val Blit = "blit"("shaders/blit.frag")

        @JvmField
        val Blend = "blend"("shaders/blend.frag")

        @JvmField
        val GuiBlur = "blur"("shaders/blur/ui_blur.frag")

        @JvmField
        val Glow = "glow"("shaders/glow/glow.frag")

        @JvmField
        val EntityOutline = "outline"("shaders/outline/entity_outline.frag")

        @JvmField
        val RoundedRect = "rounded_rect"("shaders/circle/rounded_rect.fsh")

        @JvmField
        val GuiCircleLut = "gui_circle_lut"("shaders/circle/gui_circle_lut.fsh")

        @JvmField
        val GuiRoundedRect = "gui_rounded_rect"("shaders/gui/rounded_rect.fsh")

        /**
         * Drop-shadow variant of [GuiRoundedRect]: same SDF rounded box, blurred silhouette output.
         * Reuses [Vertex.GuiRoundedRect] + [ClientVertexFormats.GUI_ROUNDED_RECT].
         */
        @JvmField
        val GuiRoundedRectShadow = "gui_rounded_rect_shadow"("shaders/gui/rounded_rect_shadow.fsh")

        @JvmField
        val MsdfText = "msdf_text"("shaders/gui/msdf_text.fsh")

        /**
         * System-font MSDF variant of [MsdfText] (DISTANCE_RANGE=12 for `type=mtsdf` atlases).
         * Reuses [Vertex.MsdfText] + `DefaultVertexFormat.POSITION_TEX_COLOR`.
         */
        @JvmField
        val SystemMsdfText = "system_msdf_text"("shaders/gui/system_msdf_text.fsh")

        @JvmField
        val RoundedTex = "rounded_tex"("shaders/gui/rounded_tex.fsh")

        @JvmField
        val HawenRoundedRect = "hawen_rounded_rect"("shaders/hawen/rounded_rect.fsh")

        @JvmField
        val GradientCircle = "gradient_circle"("shaders/circle/gradient_circle.fsh")

        @JvmField
        val KillWave = "kill_wave"("shaders/kill_effect/wave.fsh")

        @JvmField
        val KillEffectScan = "kill_effect_scan"("shaders/kill_effect/scan.fsh")

        @JvmField
        val FragDesaturate = "frag_desaturate"("shaders/frageffects/desaturate.frag")

        @JvmField
        val HawenComposite = "hawen_composite"("shaders/hawenesp/composite.fsh")

        @JvmField
        val HawenKawaseDown = "hawen_kawase_down"("shaders/hawenesp/kawase_down.fsh")

        @JvmField
        val HawenKawaseUp = "hawen_kawase_up"("shaders/hawenesp/kawase_up.fsh")

        @JvmField
        val HawenOutline = "hawen_outline"("shaders/hawenesp/outline.fsh")

        @JvmField
        val EspHalo = "esp_halo"("shaders/esphalo/halo.fsh")

        /**
         * CrystalAura soft glow composite: the sharp silhouette (texture0) over its blurred
         * self (texture1), both produced by `aura.render.AuraGlow`.
         */
        @JvmField
        val AuraGlow = "aura_glow"("shaders/aura/aura_glow.fsh")

        /**
         * Plain full-screen copy of the finished world. [AuraBlastDome] refracts the scene into
         * the target it samples, which one pass cannot do, so it reads this snapshot instead.
         */
        @JvmField
        val AuraSceneCopy = "aura_scene_copy"("shaders/aura/scene_copy.fsh")

        /**
         * CrystalAura blast dome: a world-anchored hemisphere that refracts the scene along
         * the screen-space gradient of its own silhouette field, occluded by the depth buffer.
         */
        @JvmField
        val AuraBlastDome = "aura_blast_dome"("shaders/aura/blast_dome.fsh")

        /**
         * CrystalAura forge haze: a world-anchored heat plume over the block the aura spams,
         * refracting the scene the way hot air does. Driven by
         * `aura.render.AuraForge`.
         */
        @JvmField
        val AuraForgeHaze = "aura_forge_haze"("shaders/aura/forge_haze.fsh")

        @JvmField
        val BurnGlow = "burn_glow"("shaders/burninghands/burn_glow.fsh")

        @JvmField
        val BurnTrail = "burn_trail"("shaders/burninghands/burn_trail.fsh")

        @JvmField
        val BurnComposite = "burn_composite"("shaders/burninghands/burn_composite.fsh")

        @JvmField
        val Saturation = "saturation"("shaders/saturation/saturation.fsh")

        @JvmField
        val SkyNebula = "sky_nebula"("shaders/sky/sky_nebula.fsh")
        @JvmField
        val SkyAurora = "sky_aurora"("shaders/sky/sky_aurora.fsh")
        @JvmField
        val SkyStars = "sky_stars"("shaders/sky/sky_stars.fsh")
        @JvmField
        val SkyPlasma = "sky_plasma"("shaders/sky/sky_plasma.fsh")
        @JvmField
        val SkyNeon = "sky_neon"("shaders/sky/sky_neon.fsh")

    }

    private fun newShader(id: String, path: String): Identifier {
        val k = LiquidBounce.identifier("shader/$id")
        shaders.put(
            k,
            LiquidBounce.resourceToString(path),
        )?.let { error("Duplicated shader: $k") }
        return k
    }

    override fun get(identifier: Identifier, type: ShaderType): String? {
        if (type != this.type) return null
        return shaders[identifier]
    }

    companion object : ShaderSource {
        override fun get(identifier: Identifier, shaderType: ShaderType): String? = when (shaderType) {
            ShaderType.VERTEX -> Vertex[identifier, shaderType]
            ShaderType.FRAGMENT -> Fragment[identifier, shaderType]
        }
    }

}
