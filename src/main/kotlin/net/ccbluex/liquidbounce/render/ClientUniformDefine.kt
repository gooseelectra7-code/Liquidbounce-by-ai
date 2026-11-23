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

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.RenderPass
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.ccbluex.liquidbounce.utils.render.std140Size
import net.minecraft.client.renderer.MappableRingBuffer
import java.util.function.Supplier

enum class ClientUniformDefine(val uboName: String, val size: Int) {
    DISTANCE_FADE("u_DistanceFade", std140Size { vec4 }),
    MESH_BASE_BLOCK_POS("u_MeshBaseBlockPos", std140Size { ivec3 }),
    ROUNDED_RECT("u_RoundedRect", std140Size { vec2 + float }),
    HAND_ITEM_LIGHTMAP("ItemChamsData", std140Size { int + float + vec4 + float + vec4 + float + int }),
    GUI_BLUR("BlurData", std140Size { float + float + float }),
    BLEND("BlendData", std140Size { vec4 }),
    THEME_BACKGROUND("ThemeBackgroundData", std140Size { float + vec2 + vec2 }),
    KILL_WAVE("u_KillWave", std140Size { vec4 + vec4 }),
    /**
     * Scan-wave kill effect (NightShift port): the inverted proj × modelView, the camera, the
     * scan centre plus its current radius, the four band colours, screen/band/time and the fade.
     * Keep the order in sync with `shaders/kill_effect/scan.fsh`.
     */
    SCAN_WAVE(
        "ScanWaveData",
        std140Size { mat4f + vec4 + vec4 + vec4 + vec4 + vec4 + vec4 + vec4 + vec4 },
    ),
    /** FragEffects desaturation: x = strength (0..1), rest unused. */
    FRAG_DESATURATE("FragDesaturateData", std140Size { vec4 }),
    HAWEN_COMPOSITE("HawenCompositeData", std140Size { vec4 + vec4 + vec4 }),
    HAWEN_KAWASE("KawaseBlurData", std140Size { vec4 }),
    HAWEN_OUTLINE("HawenOutlineData", std140Size { vec4 + vec4 }),
    /**
     * Halo ESP (NightShift port): mainColor, tintMode, then five packed parameter vectors —
     * see `shaders/esphalo/halo.fsh` for what each lane means. Keep the order in sync with it.
     */
    ESP_HALO("EspHaloData", std140Size { vec4 + vec4 + vec4 + vec4 + vec4 + vec4 + vec4 }),
    /**
     * CrystalAura soft glow composite: one packed vector,
     * (glowStrength, glowFalloff, alpha, interiorDamping).
     * Keep in sync with `shaders/aura/aura_glow.fsh`.
     */
    AURA_GLOW("AuraGlowData", std140Size { vec4 }),
    /**
     * CrystalAura blast dome: the inverted proj × modelView, a control vector
     * (domeCount, zZeroToOne, strength, unused), then four camera-relative dome vectors
     * (centre.xyz, radius) and four parameter vectors (fade, amplitude, ripples, radialBlur).
     *
     * The two runs of four are `vec4 domes[4]` / `vec4 domeParams[4]` in GLSL — a std140 array of
     * vec4 has a 16-byte stride, so four consecutive `vec4`s lay out identically. `MAX_DOMES` in
     * `shaders/aura/blast_dome.fsh` must stay at 4 to match.
     */
    AURA_DOME(
        "AuraDomeData",
        std140Size {
            mat4f + vec4 +
                vec4 + vec4 + vec4 + vec4 +
                vec4 + vec4 + vec4 + vec4
        },
    ),
    /**
     * CrystalAura forge haze: the inverted proj × modelView, a control vector
     * (siteCount, zZeroToOne, strength, timeSeconds), a shape vector (aspect, radius, rise,
     * amplitude), the hot colour (rgb, tint amount), then four camera-relative site vectors
     * (centre.xyz, heat).
     *
     * The run of four is `vec4 sites[4]` in GLSL — a std140 array of vec4 has a 16-byte stride, so
     * four consecutive `vec4`s lay out identically. `MAX_SITES` in
     * `shaders/aura/forge_haze.fsh` must stay at 4 to match.
     */
    AURA_FORGE(
        "AuraForgeData",
        std140Size {
            mat4f + vec4 + vec4 + vec4 +
                vec4 + vec4 + vec4 + vec4
        },
    ),
    /**
     * BurningHands (exploit-client port): Screen(w, h, time, fillMode),
     * GlowParams(radius, strength, speed, colorMix),
     * TrailParams(decay, flameHeight, flowSpeed, trailStrength),
     * FireParams(r, g, b, glowStrength). Keep this exact order — the GLSL block
     * `BurnData` in the burninghands .fsh files is verbatim from the source.
     */
    BURN_DATA("BurnData", std140Size { vec4 + vec4 + vec4 + vec4 }),
    SATURATION("SaturationData", std140Size { vec4 }),
    SKY_DATA(
        "SkyData",
        std140Size {
            vec2 + float + float + float + float + float + float + float + float +
                vec3 + vec3 + vec3 + vec3 + vec3 + vec3 + vec3 + vec4
        },
    ),
    ;

    fun label(): String = "${LiquidBounce.CLIENT_NAME} Uniform ${this.uboName} (${this.size}b)"

    @JvmOverloads
    fun createSingleBuffer(
        labelGetter: Supplier<String> = Supplier(this::label),
    ): GpuBufferSlice {
        return gpuDevice.createBuffer(
            labelGetter,
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
            this.size.toLong(),
        ).slice()
    }

    @JvmOverloads
    fun createRingBuffer(
        labelGetter: Supplier<String> = Supplier(this::label),
    ): MappableRingBuffer {
        return MappableRingBuffer(
            labelGetter,
            GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE,
            this.size,
        )
    }

    /**
     * A packed arena for many small blocks of this uniform within a single frame — one
     * `arena.allocate(define.size)` per object drawn.
     *
     * Prefer this over [createRingBuffer] when the count is data-driven (one block per ESP orb, per
     * kill wave, …). A `MappableRingBuffer` holds exactly three buffers, so rotating once per object
     * makes `currentBuffer()` block on a fence from the fourth object onwards — a full CPU↔GPU sync
     * in the middle of the frame. The arena packs allocations into one buffer at the device's
     * uniform offset alignment and only turns the ring when that buffer is genuinely full.
     *
     * Prefer [createSingleBuffer] when there is exactly one block per frame.
     */
    @JvmOverloads
    fun createArena(
        label: String = this.label(),
        minSize: Int = DEFAULT_ARENA_SIZE,
    ): GrowableMappableRingBuffer = GrowableMappableRingBuffer(
        label,
        GpuBuffer.USAGE_UNIFORM,
        GrowableMappableRingBuffer.GrowPolicy.of(paddingScale = 8, min = minSize),
        alignment = gpuDevice.uniformOffsetAlignment,
    )

    fun setTo(renderPass: RenderPass, slice: GpuBufferSlice) {
        renderPass.setUniform(this.uboName, slice)
    }

    private companion object {
        /** 16 KiB: ~64 blocks per backing buffer at a 256-byte uniform alignment. */
        const val DEFAULT_ARENA_SIZE = 1 shl 14
    }

}
