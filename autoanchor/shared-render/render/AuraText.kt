/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
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
package net.ccbluex.liquidbounce.features.module.modules.combat.aura.render

import net.ccbluex.liquidbounce.render.FontFace
import net.ccbluex.liquidbounce.render.engine.font.FontRenderer
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale

/**
 * The overlay text path shared by the combat auras: one measured, drawn line at a requested PIXEL height, through
 * a preferred [FontFace] when the glyph manager can serve one and through the SystemDLC atlas when it cannot.
 *
 * Shared for the same reason [AuraGlow] and [AuraBlastDome] are - it caches a resolved [FontRenderer], and two
 * copies would keep two caches of the same face - and because the fallback logic is the part that is easy to get
 * subtly wrong: the two paths measure differently ([lineHeight]), so any caller that mixes a width from one with
 * a height from the other draws crooked text.
 *
 * Nothing here is aura-specific; the face is a parameter, never a module lookup.
 */
object AuraText {

    /** The SystemDLC family used whenever the preferred face is unavailable. */
    val FAMILY: SystemFont.Family = SystemFont.Family.B

    /** Resolved renderer, cached because `FontFace.renderer` is a lazy that needs the glyph manager. */
    private var cached: FontRenderer? = null

    /**
     * [face]'s renderer, or `null` while it is not usable.
     *
     * `FontFace.renderer` is a lazy that builds a `FontRenderer` over `FontManager.glyphManager`
     * (`render/FontFace.kt:56-58`), which throws while the glyph manager does not exist yet - so the resolution
     * is guarded and the result cached against the face it was built for.
     */
    private fun renderer(face: FontFace?): FontRenderer? {
        if (face == null) return null
        cached?.let { if (it.font === face) return it }
        val renderer = runCatching { face.renderer }.getOrNull() ?: return null
        cached = renderer
        return renderer
    }

    /** Advanced width of [text] at pixel height [size], through whichever text path is active. */
    fun width(face: FontFace?, text: String, size: Float): Float {
        val renderer = renderer(face) ?: return SystemDraw.textWidth(text, size, FAMILY)
        return renderer.getStringWidth(renderer.process(text)) * (size / renderer.height)
    }

    /**
     * Height of one line at pixel height [size]. The `FontRenderer` path is scaled by `size / renderer.height`, so
     * its line box is exactly [size] tall; the SystemDLC atlas carries its own line height.
     */
    fun lineHeight(face: FontFace?, size: Float): Float =
        if (renderer(face) == null) SystemDraw.lineHeight(size, FAMILY) else size

    /**
     * Draws [text] with its top-left at ([drawX], [drawY]) and returns the pen X after the last glyph.
     *
     * `FontRenderer` bakes one atlas per face and scales at draw time (`render/AbstractFontRenderer.kt:114-131`:
     * `DrawParameters.scale` multiplies both metrics and geometry), so the pixel height is requested as
     * `size / renderer.height`. Each `process` hands back the SAME recycled instance
     * (`render/engine/font/processor/MinecraftTextProcessor.kt:31-53`), which is why every string is processed and
     * consumed in one step instead of being measured up front and kept.
     */
    @Suppress("LongParameterList")
    fun draw(
        face: FontFace?,
        ctx: GuiGraphicsExtractor,
        text: String,
        drawX: Float,
        drawY: Float,
        size: Float,
        color: Color4b,
    ): Float {
        val renderer = renderer(face)
            ?: return SystemDraw.text(ctx, text, drawX, drawY, size, color, FAMILY)

        val factor = size / renderer.height
        val processed = renderer.process(text, color)
        val width = renderer.getStringWidth(processed) * factor
        with(ctx) {
            renderer.draw(processed) {
                x = drawX
                y = drawY
                scale = factor
            }
        }
        return drawX + width
    }

    /** One decimal, locale-independent: the format both auras print damage figures in. */
    fun format(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
}
