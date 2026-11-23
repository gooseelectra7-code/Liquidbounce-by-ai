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

package net.ccbluex.liquidbounce.render.engine.font.system

import com.google.gson.JsonParser
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture

/**
 * One baked `msdf-atlas-gen` atlas (`type=mtsdf`, `distanceRange=12`, `size=96`, `yOrigin=bottom`)
 * from the recovered SystemDLC client, loaded from `resources/liquidbounce/font/system/<family>.{json,png}`.
 *
 * Structurally the external-atlas twin of [net.ccbluex.liquidbounce.render.engine.font.hud.HudFont]:
 * the PNG becomes an ALPHA-PRESERVING [net.minecraft.client.gui.render.TextureSetup] (mtsdf keeps its
 * field in RGB where alpha may be 0, so it must NOT be premultiplied — [readNativeImage] wraps
 * `NativeImage.read` / STBImage, which loads straight RGBA), and the JSON metrics become a
 * `Map<Char, `[Glyph]`>`. Every metric here is in EM units (the JSON's `emSize = 1`); [SystemFont]
 * multiplies by `pxSize` at draw time.
 *
 * GL resources (the [DynamicTexture]) are created inside [load], which MUST be invoked on the render
 * thread — see [SystemFont] `ensureReady`.
 */
class SystemFontAtlas private constructor(
    private val glyphs: Map<Char, Glyph>,
    /** `metrics.lineHeight`, EM units. */
    val lineHeightEm: Float,
    /** `metrics.ascender`, EM units — the baseline sits this far below the pen's top-left origin. */
    val ascenderEm: Float,
    /** `metrics.descender`, EM units (negative). */
    val descenderEm: Float,
    /** Single-texture setup (Sampler0), LINEAR clamp — mirrors [HudFont] `buildAtlas`. */
    val setup: TextureSetup,
    @Suppress("unused") private val texture: DynamicTexture,
) {

    /** Number of glyphs parsed from this family's JSON (includes advance-only whitespace). */
    val glyphCount: Int get() = glyphs.size

    fun glyph(ch: Char): Glyph? = glyphs[ch]

    fun has(ch: Char): Boolean = glyphs.containsKey(ch)

    /**
     * Atlas UV rect + EM-space quad placement of one glyph relative to the pen origin on the baseline.
     *
     * UV is normalized by the atlas `width`/`height` with **V FLIPPED** (the JSON's `yOrigin = bottom`,
     * while the sampled texture has its origin top-left): `v = 1 - yFromBottom / height`. [u0]/[v0] is
     * the glyph's top-left texel, [u1]/[v1] the bottom-right.
     *
     * Placement is `planeBounds` in EM units, expressed y-down from the baseline like [HudFont]:
     * [offsetX] = `planeBounds.left`, [offsetY] = `-planeBounds.top` (the top edge is above the
     * baseline, hence negative in screen space), [quadW]/[quadH] the plane extent. [advance] is the
     * EM pen advance. A whitespace glyph (no `atlasBounds`) has zero quad and carries [advance] only.
     */
    class Glyph(
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val offsetX: Float, val offsetY: Float,
        val quadW: Float, val quadH: Float,
        val advance: Float,
    )

    companion object {

        /**
         * Parses one family's JSON metrics and uploads its PNG. [resource] is the bare family key
         * (`a`, `aa`, `b`, `bb`, `c`, `cc`); the files live at `font/system/<resource>.{json,png}`.
         * Call on the render thread.
         */
        fun load(resource: String): SystemFontAtlas {
            val json = LiquidBounce.resource("font/system/$resource.json")
                .reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

            val atlas = json.getAsJsonObject("atlas")
            val atlasW = atlas.get("width").asFloat
            val atlasH = atlas.get("height").asFloat

            val metrics = json.getAsJsonObject("metrics")
            val lineHeight = metrics.get("lineHeight").asFloat
            val ascender = metrics.get("ascender").asFloat
            val descender = metrics.get("descender").asFloat

            val glyphMap = HashMap<Char, Glyph>()
            for (element in json.getAsJsonArray("glyphs")) {
                val g = element.asJsonObject
                val code = g.get("unicode").asInt
                // Every glyph in these fonts is in the BMP; anything else can't be a Kotlin Char key.
                if (code !in 0..0xFFFF) continue
                val advance = g.get("advance").asFloat

                if (!g.has("atlasBounds") || !g.has("planeBounds")) {
                    // Whitespace (e.g. U+0020): advance only, no drawable quad.
                    glyphMap[code.toChar()] = Glyph(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, advance)
                    continue
                }

                val pb = g.getAsJsonObject("planeBounds")
                val planeLeft = pb.get("left").asFloat
                val planeBottom = pb.get("bottom").asFloat
                val planeRight = pb.get("right").asFloat
                val planeTop = pb.get("top").asFloat

                val ab = g.getAsJsonObject("atlasBounds")
                val atlasLeft = ab.get("left").asFloat
                val atlasBottom = ab.get("bottom").asFloat
                val atlasRight = ab.get("right").asFloat
                val atlasTop = ab.get("top").asFloat

                // UV from atlasBounds, normalized by the atlas size, V flipped (yOrigin=bottom).
                val u0 = atlasLeft / atlasW
                val u1 = atlasRight / atlasW
                val v0 = 1f - atlasTop / atlasH     // glyph top edge  -> smaller v
                val v1 = 1f - atlasBottom / atlasH  // glyph bottom edge -> larger v

                // Quad placement from planeBounds, EM units, y-down relative to the baseline.
                val offsetX = planeLeft
                val offsetY = -planeTop
                val quadW = planeRight - planeLeft
                val quadH = planeTop - planeBottom

                glyphMap[code.toChar()] =
                    Glyph(u0, v0, u1, v1, offsetX, offsetY, quadW, quadH, advance)
            }

            // ALPHA-PRESERVING load: NativeImage.read (STBImage) does not premultiply, so the mtsdf
            // field survives in RGB even where alpha is 0. LINEAR clamp is essential for the field AA.
            val image = LiquidBounce.resource("font/system/$resource.png").readNativeImage()
            val texture = image.asTexture {
                "SystemFont atlas '$resource' (${atlasW.toInt()}x${atlasH.toInt()})"
            }
            val setup = TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            )

            return SystemFontAtlas(glyphMap, lineHeight, ascender, descender, setup, texture)
        }
    }
}
