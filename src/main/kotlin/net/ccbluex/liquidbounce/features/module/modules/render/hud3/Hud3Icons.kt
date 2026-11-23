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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup

/**
 * The icon set of the design, as textures.
 *
 * Unlike `HudTestIcons`, which hand-writes every mark as vector geometry, these are the **design's
 * own vectors**, exported from the source Figma file through the REST API at 4x and flattened to a
 * white alpha mask so a single asset can be tinted to whatever colour the spec asks for. That is
 * the same convention the native ClickGUI uses for its category icons, and for this module it is
 * also the only way the marks can be claimed to be 1:1 — a hand-traced polygon of someone else's
 * bezier is a likeness, not a copy.
 *
 * Every file is the icon **instance** as it is composed inside its component, not the bare glyph,
 * so the transparent margin the design leaves around each mark is part of the asset: drawing
 * `logo.png` into a 16 px slot puts a 12 px glyph in the middle of it, exactly as `<3:354>` does.
 *
 * | asset       | source node | slot | design colour       |
 * |-------------|-------------|------|---------------------|
 * | `logo`      | `<3:354>`   | 16   | `#5E8CFF`           |
 * | `user`      | `<3:358>`   | 16   | `#5E8CFF`           |
 * | `frame`     | `<3:361>`   | 16   | `#5E8CFF`           |
 * | `pad`       | `<3:367>`   | 16   | `#5E8CFF`           |
 * | `connect`   | `<3:370>`   | 16   | `#5E8CFF`           |
 * | `module`    | `<3:378>`   | 12   | `#5E8CFF`           |
 * | `potion`    | `<3:402>`   | 12   | `#5E8CFF`           |
 * | `command`   | `<3:385>`   | 12   | `#F7F3F3` at 45 %   |
 * | `check`     | `<3:161>`   | 12   | `#00C407`           |
 * | `cancel`    | `<3:165>`   | 12   | `#EE0909`           |
 * | `info`      | `<3:169>`   | 12   | `#E5B402`           |
 *
 * `command.png` is the one asset whose alpha was rewritten on export: the design bakes its 45 %
 * opacity into the fill, so the raw export peaked at alpha 115 and tinting it again would have
 * darkened it twice. Its alpha is renormalized to full range and the opacity lives in the tint,
 * like every other icon here.
 */
internal object Hud3Icons {

    const val LOGO = "logo"
    const val USER = "user"
    const val FRAME = "frame"
    const val PAD = "pad"
    const val CONNECT = "connect"
    const val MODULE = "module"
    const val POTION = "potion"
    const val COMMAND = "command"
    const val CHECK = "check"
    const val CANCEL = "cancel"
    const val INFO = "info"

    /** Negative results are cached too — a missing asset must not be retried every frame. */
    private val setups = HashMap<String, TextureSetup?>()

    private fun setup(name: String): TextureSetup? {
        if (name in setups) {
            return setups[name]
        }
        val setup = try {
            val image = LiquidBounce.resource("textures/hud3/$name.png").readNativeImage()
            val texture = image.asTexture { "Hud3-$name" }
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            )
        } catch (e: Throwable) {
            logger.error("Hud3: failed to load icon $name", e)
            null
        }
        setups[name] = setup
        return setup
    }

    /**
     * Draws [name] into the [size] slot at local ([x], [y]), tinted with [argb].
     *
     * The asset is a white mask, so the tint is the colour: no multiply against a baked hue and
     * the same file serves the accent, the dim and the severity variants.
     */
    @Suppress("LongParameterList")
    fun draw(
        ctx: GuiGraphicsExtractor,
        name: String,
        x: Float,
        y: Float,
        size: Float,
        argb: Int,
    ) {
        val setup = setup(name) ?: return
        ctx.hud3TexturedQuad(setup, x, y, size, size, argb)
    }
}
