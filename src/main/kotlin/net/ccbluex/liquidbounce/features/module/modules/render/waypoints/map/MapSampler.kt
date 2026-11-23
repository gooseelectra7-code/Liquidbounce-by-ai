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
package net.ccbluex.liquidbounce.features.module.modules.render.waypoints.map

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.material.MapColor

/**
 * Reads the colour of one block column off a loaded client chunk. Main thread only.
 *
 * The look is the world seen from straight above ([BlockTopColors]: the average colour of each
 * block's top texture, biome-tinted where the game tints it), not vanilla's map palette. On top:
 * - shallow water shows the floor through it and deepens towards the biome's water colour;
 * - in a dimension with a ceiling (the Nether) the roof is skipped and the first floor under it is
 *   drawn, otherwise the whole Nether map is bedrock.
 *
 * Which blocks are looked THROUGH still follows vanilla's map ([MapColor.NONE]: air, glass,
 * barriers, light blocks), so a glass roof shows what is under it.
 *
 * Height shading is NOT done here: it needs the neighbouring columns, which [WorldMapStore] has.
 */
internal object MapSampler {

    /** Explored but empty (the End's void): dark, and distinct from "never seen" (transparent). */
    const val VOID_COLOR = 0xFF0B0D12.toInt()

    private const val MAX_WATER_DEPTH = 24
    private const val WATER_MIN_OPACITY = 0.42f
    private const val WATER_OPACITY_PER_BLOCK = 0.07f
    private const val WATER_MAX_OPACITY = 0.9f
    private const val WATER_DEPTH_DARKEN = 0.012f
    private const val WATER_MAX_DARKEN_DEPTH = 20

    class Sample {
        var color = 0
        var height = 0

        /** A liquid surface: height shading would only draw noise on it. */
        var flat = false
    }

    fun sample(
        level: ClientLevel,
        chunk: LevelChunk,
        x: Int,
        z: Int,
        ceiling: Boolean,
        pos: BlockPos.MutableBlockPos,
        out: Sample,
    ) {
        val minY = level.minY
        var y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15)

        if (ceiling) {
            y = minOf(y, minY + level.dimensionType().logicalHeight() - 1)
            // Out of the roof, then down through the air under it.
            while (y > minY && !chunk.getBlockState(pos.set(x, y, z)).isAir) y--
            while (y > minY && chunk.getBlockState(pos.set(x, y, z)).isAir) y--
        }

        // Down through everything a map does not draw (air, glass, barriers...).
        var state = chunk.getBlockState(pos.set(x, y, z))
        while (y > minY) {
            if (!state.fluidState.isEmpty) break
            if (!state.isAir && state.getMapColor(level, pos) != MapColor.NONE) break
            y--
            state = chunk.getBlockState(pos.set(x, y, z))
        }

        if (y <= minY && (state.isAir || state.getMapColor(level, pos) == MapColor.NONE)) {
            out.color = VOID_COLOR
            out.height = minY
            out.flat = true
            return
        }

        val fluid = state.fluidState
        if (fluid.isEmpty) {
            out.color = BlockTopColors.color(level, state, pos)
            out.height = y
            out.flat = false
            return
        }

        out.height = y
        out.flat = true

        if (fluid.type.isSame(Fluids.LAVA)) {
            out.color = BlockTopColors.lava()
            return
        }
        if (!fluid.type.isSame(Fluids.WATER)) {
            // Anything modded: opaque, vanilla's colour for the fluid's block.
            val legacy = fluid.createLegacyBlock()
            out.color = legacy.getMapColor(level, pos).calculateARGBColor(MapColor.Brightness.HIGH)
            return
        }

        val water = BlockTopColors.water(level, pos)

        var depth = 1
        var floor = state
        while (depth < MAX_WATER_DEPTH && y - depth > minY) {
            floor = chunk.getBlockState(pos.set(x, y - depth, z))
            if (floor.fluidState.isEmpty) break
            depth++
        }

        val floorColor = if (floor.fluidState.isEmpty && floor.getMapColor(level, pos) != MapColor.NONE) {
            BlockTopColors.color(level, floor, pos)
        } else {
            ARGB.scaleRGB(water, 0.6f)
        }

        val opacity = (WATER_MIN_OPACITY + depth * WATER_OPACITY_PER_BLOCK).coerceAtMost(WATER_MAX_OPACITY)
        val mixed = ARGB.srgbLerp(opacity, floorColor, water)
        val darken = 1f - minOf(depth, WATER_MAX_DARKEN_DEPTH) * WATER_DEPTH_DARKEN
        out.color = ARGB.opaque(ARGB.scaleRGB(mixed, darken))
    }
}
