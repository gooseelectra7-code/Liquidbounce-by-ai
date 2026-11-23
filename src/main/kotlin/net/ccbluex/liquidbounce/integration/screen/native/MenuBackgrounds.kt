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
package net.ccbluex.liquidbounce.integration.screen.native

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.kotlin.getOrPutNullable
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup

/**
 * Shared cache of the shipped menu wallpapers (`textures/hellhit/menu/<name>.png`),
 * loaded once per game run: "bg1".."bg3" back the title screen, "bg_worlds" backs the
 * multiplayer/singleplayer screens.
 */
internal object MenuBackgrounds {

    private val setups = HashMap<String, TextureSetup?>()

    fun get(name: String): TextureSetup? = setups.getOrPutNullable(name) {
        try {
            val image = LiquidBounce.resource("textures/hellhit/menu/$name.png").readNativeImage()
            val texture = image.asTexture { "HellhitMenu-$name" }
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            )
        } catch (e: Throwable) {
            logger.error("Failed to load menu background $name", e)
            null
        }
    }

    /**
     * The live shader background ([MenuShaderBackground]) when it is available, otherwise the
     * static wallpaper [fallback] — a single entry point for every native menu backdrop.
     */
    fun drawLive(ctx: GuiGraphicsExtractor, fallback: String, width: Int, height: Int) {
        if (!MenuShaderBackground.draw(ctx, width, height)) {
            drawCover(ctx, fallback, width.toFloat(), height.toFloat())
        }
    }

    /** Cover-crops the 16:9 wallpaper [name] onto the whole screen; solid ink when missing. */
    fun drawCover(ctx: GuiGraphicsExtractor, name: String, w: Float, h: Float) {
        val setup = get(name)
        if (setup == null) {
            ctx.drawQuad(0f, 0f, w, h, HellhitPalette.ink, null)
            return
        }
        val imgAspect = 16f / 9f
        val scrAspect = if (h > 0f) w / h else imgAspect
        var u0 = 0f
        var v0 = 0f
        var u1 = 1f
        var v1 = 1f
        if (scrAspect > imgAspect) {
            val vis = imgAspect / scrAspect
            v0 = (1f - vis) / 2f
            v1 = 1f - v0
        } else {
            val vis = scrAspect / imgAspect
            u0 = (1f - vis) / 2f
            u1 = 1f - u0
        }
        ctx.drawTexQuad(setup, 0f, 0f, w, h, u0, v0, u1, v1)
    }
}
