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

import com.mojang.blaze3d.platform.NativeImage
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor

/**
 * What a block looks like from straight above: the average colour of the texture on its TOP face,
 * multiplied by the biome tint when that face is tinted (grass, leaves, vines, water). This is the
 * "satellite" look - grass is the grass texture's green in this biome, sand is the sand texture,
 * a stone-brick roof is stone bricks - instead of vanilla's 60-colour map palette.
 *
 * The face comes from the block's baked model (`ModelManager.getBlockStateModelSet().get(state)`):
 * the UP quads of its parts, else an unculled quad facing up, else any unculled quad (crossed
 * plants), else the particle sprite (chests, signs, beds - block entities with no model faces).
 * Liquids have no block model and use their still texture.
 *
 * The texture is read from the resource manager by the sprite's name, so resource packs apply;
 * nothing touches the atlas or a private field. Averages are cached per sprite and per state
 * (main thread only); a pixel under 10 % opacity is not counted, so cut-out textures (leaves,
 * flowers) average their visible part. [clear] drops the caches (a resource reload).
 */
internal object BlockTopColors {

    private const val MIN_ALPHA = 26
    private const val NO_COLOR = 0

    /** Not a colour any average can produce (every average is opaque, alpha 0xFF). */
    private const val NOT_CACHED = 1

    /** Water's still texture is near-grey; this is the brightness the tint lands at. */
    private val WATER_SPRITE = Identifier.fromNamespaceAndPath("minecraft", "block/water_still")
    private val LAVA_SPRITE = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still")

    private class Face(val color: Int, val tintIndex: Int)

    private val byState = Reference2ObjectOpenHashMap<BlockState, Face>()
    private val bySprite = Object2IntOpenHashMap<Identifier>().apply { defaultReturnValue(NOT_CACHED) }
    private val random = RandomSource.create(0L)
    private val parts = ArrayList<BlockStateModelPart>()

    fun clear() {
        byState.clear()
        bySprite.clear()
    }

    /**
     * Opaque ARGB of [state] seen from above at [pos], tint applied.
     */
    fun color(level: ClientLevel, state: BlockState, pos: BlockPos): Int {
        val face = byState.get(state) ?: resolve(state).also { byState.put(state, it) }
        if (face.color == NO_COLOR) {
            return state.getMapColor(level, pos).calculateARGBColor(MapColor.Brightness.HIGH)
        }
        if (face.tintIndex < 0) {
            return face.color
        }
        val tint = tint(level, state, pos, face.tintIndex) ?: return face.color
        return multiply(face.color, tint)
    }

    /**
     * The biome-tinted still-water colour at [pos]. The tint is read from the biome directly: the
     * water BLOCK's tint source in BlockColors is `waterParticles()`, whose `colorInWorld` is -1
     * (`BlockColors.java:42`); the real water tint belongs to the fluid model.
     */
    fun water(level: ClientLevel, pos: BlockPos): Int {
        val base = spriteAverage(WATER_SPRITE).takeIf { it != NO_COLOR } ?: 0xFFB0B0B0.toInt()
        return multiply(base, BiomeColors.getAverageWaterColor(level, pos))
    }

    fun lava(): Int = spriteAverage(LAVA_SPRITE).takeIf { it != NO_COLOR }
        ?: MapColor.FIRE.calculateARGBColor(MapColor.Brightness.HIGH)

    private fun tint(level: ClientLevel, state: BlockState, pos: BlockPos, index: Int): Int? {
        val source = mc.blockColors.getTintSource(state, index) ?: return null
        val color = source.colorInWorld(state, level, pos)
        return if (color == -1) null else color
    }

    private fun multiply(argb: Int, tint: Int): Int = ARGB.color(
        255,
        ARGB.red(argb) * ARGB.red(tint) / 255,
        ARGB.green(argb) * ARGB.green(tint) / 255,
        ARGB.blue(argb) * ARGB.blue(tint) / 255,
    )

    private fun resolve(state: BlockState): Face = runCatching {
        val model = mc.modelManager.blockStateModelSet.get(state)
        parts.clear()
        random.setSeed(0L)
        model.collectParts(random, parts)

        val quad = parts.firstNotNullOfOrNull { part -> part.getQuads(Direction.UP).firstOrNull() }
            ?: parts.firstNotNullOfOrNull { part -> part.getQuads(null).firstOrNull { it.direction == Direction.UP } }
            ?: parts.firstNotNullOfOrNull { part -> part.getQuads(null).firstOrNull() }

        if (quad != null) {
            val info = quad.materialInfo
            Face(spriteAverage(info.sprite.contents().name()), info.tintIndex)
        } else {
            Face(spriteAverage(model.particleMaterial().sprite.contents().name()), -1)
        }
    }.getOrElse {
        Face(NO_COLOR, -1)
    }

    /**
     * Average opaque colour of a block texture, `NO_COLOR` when it cannot be read.
     */
    private fun spriteAverage(sprite: Identifier): Int {
        val cached = bySprite.getInt(sprite)
        if (cached != NOT_CACHED) return cached

        val file = Identifier.fromNamespaceAndPath(sprite.namespace, "textures/${sprite.path}.png")
        val average = try {
            val resource = mc.resourceManager.getResource(file).orElse(null)
            if (resource == null) {
                NO_COLOR
            } else {
                NativeImage.read(resource.open()).use(::average)
            }
        } catch (e: Exception) {
            logger.debug("[WorldMap] Could not read $file", e)
            NO_COLOR
        }
        bySprite.put(sprite, average)
        return average
    }

    private fun average(image: NativeImage): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (ARGB.alpha(pixel) < MIN_ALPHA) continue
                red += ARGB.red(pixel)
                green += ARGB.green(pixel)
                blue += ARGB.blue(pixel)
                count++
            }
        }
        if (count == 0L) return NO_COLOR
        return ARGB.color(255, (red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }
}
