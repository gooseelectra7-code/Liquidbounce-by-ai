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

package net.ccbluex.liquidbounce.render.engine.font.dynamic

import com.mojang.blaze3d.platform.NativeImage
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.render.engine.font.AtlasSliceHandle
import net.ccbluex.liquidbounce.render.engine.font.DynamicAtlasAllocator
import net.ccbluex.liquidbounce.render.engine.font.FontGlyph
import net.ccbluex.liquidbounce.render.engine.font.GlyphIdentifier
import net.ccbluex.liquidbounce.render.engine.font.GlyphPage
import net.ccbluex.liquidbounce.render.engine.font.GlyphPage.Companion
import net.ccbluex.liquidbounce.render.engine.font.GlyphRenderInfo
import net.ccbluex.liquidbounce.render.engine.font.FontStyle
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.toNativeImage
import java.awt.Dimension
import java.awt.Point
import java.awt.image.BufferedImage
import kotlin.math.min

class DynamicGlyphPage(val atlasSize: Dimension = DEFAULT_ATLAS_SIZE, fontHeight: Int) : GlyphPage() {
    private val image = createBufferedImageWithDimensions(atlasSize)
    override val texture = image.toNativeImage().asTexture { "DynamicGlyphPage ${atlasSize.width}x${atlasSize.height}" }
    private val glyphMap = Long2ObjectOpenHashMap<Pair<GlyphRenderInfo, AtlasSliceHandle>>()

    private val allocator = DynamicAtlasAllocator(
        atlasSize,
        fontHeight + 4,
        Dimension(fontHeight / 3, fontHeight / 3)
    )

    fun getGlyph(char: Char, style: @FontStyle Int): GlyphRenderInfo? {
        return glyphMap[GlyphIdentifier.asLong(char, style)]?.first
    }

    /**
     * Tries to add the given characters to the page.
     *
     * @return A list of characters that could not be added
     */
    fun tryAdd(c: Iterable<FontGlyph>): List<FontGlyph> {
        val failed = ObjectArrayList<FontGlyph>()

        val changesToDo = c
            .filter { glyphId -> !glyphMap.containsKey(GlyphIdentifier.asLong(glyphId)) }
            .sortedByDescending { glyphId ->
                val dims = glyphId.font.awtFont.createGlyphVector(fontRendererContext, glyphId.codepoint.toString())

                val bounds2D = dims.getGlyphMetrics(0).bounds2D

                bounds2D.width * bounds2D.height
            }
            .mapNotNull { glyphId ->
                val placementPlan = planCharacterPlacement(glyphId)

                if (placementPlan != null) {
                    placementPlan
                } else {
                    failed.add(glyphId)

                    null
                }
            }

        // Render the characters to the image
        renderGlyphs(this.image, changesToDo.map { it.first })

        changesToDo.forEach { (generationInfo, slice) ->
            val glyph = createGlyphFromGenerationInfo(generationInfo, atlasSize)

            glyphMap.put(GlyphIdentifier.asLong(generationInfo.fontGlyph), glyph to slice)

            updateNativeTexture(generationInfo)
        }

        return failed
    }

    fun free(ch: Char, style: @FontStyle Int): GlyphRenderInfo? {
        val (renderInfo, sliceHandle) = this.glyphMap.remove(GlyphIdentifier.asLong(ch, style)) ?: return null

        this.allocator.free(sliceHandle)

        return renderInfo
    }

    private fun updateNativeTexture(generationInfo: Companion.CharacterGenerationInfo) {
        copyImageSection(
            fromImage = this.image,
            toImage = texture.pixels!!,
            fromLocation = generationInfo.atlasLocation,
            toLocation = generationInfo.atlasLocation,
            patchSize = generationInfo.atlasDimension
        )
    }

    private fun copyImageSection(
        fromImage: BufferedImage,
        toImage: NativeImage,
        fromLocation: Point,
        toLocation: Point,
        patchSize: Dimension
    ) {
        // Row-major with a bulk read; the source raster is row-major, and getRGB(x, y) per pixel
        // goes through the ColorModel every call.
        val row = IntArray(patchSize.width)

        for (j in 0 until patchSize.height) {
            fromImage.getRGB(fromLocation.x, fromLocation.y + j, patchSize.width, 1, row, 0, patchSize.width)

            for (i in 0 until patchSize.width) {
                toImage.setPixel(toLocation.x + i, toLocation.y + j, row[i])
            }
        }
    }

    private fun planCharacterPlacement(glyph: FontGlyph): Pair<Companion.CharacterGenerationInfo, AtlasSliceHandle>? {
        val characterInfo = createCharacterCreationInfo(glyph) ?: return null
        val atlasAllocation = allocator.allocate(characterInfo.atlasDimension) ?: return null

        characterInfo.atlasLocation = atlasAllocation.pos

        return characterInfo to atlasAllocation
    }


    companion object {
        @JvmStatic
        private val atlasSize = min(2048, maxTextureSize.value)

        @JvmField
        val DEFAULT_ATLAS_SIZE = Dimension(atlasSize, atlasSize)
    }
}
