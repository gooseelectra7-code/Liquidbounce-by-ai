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

import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomElement
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getBoundsXYWH
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The GUI text foundation for the SystemDLC port: six baked `mtsdf` atlases ([Family]) rendered 1:1
 * through the [ClientRenderPipelines.GUI.systemMsdfText] pipeline (identical to the HUD's
 * `msdfText()` but with `DISTANCE_RANGE = 12` for these atlases).
 *
 * This is [net.ccbluex.liquidbounce.render.engine.font.hud.HudFont]'s structure applied to external
 * atlases: lazy render-thread init ([ensureReady]), and a per-glyph `POSITION_TEX_COLOR` quad emit
 * that mirrors `HudFont.drawCore` byte-for-byte in winding and UV assignment. The only differences
 * are that the atlas is loaded from disk instead of rasterized ([SystemFontAtlas]), that the field
 * decode uses distance range 12, and that a missing codepoint falls back to the family's sibling
 * atlas (a<->aa, b<->bb, c<->cc).
 *
 * All the drawing entry points are safe to call before the atlases exist: they lazily build on the
 * calling (render) thread and degrade to a no-op / zero metric if the build fails.
 */
object SystemFont {

    /**
     * The six atlas families. `a`/`b`/`c` and `aa`/`bb`/`cc` are the Latin/Cyrillic siblings of the
     * three weights; a codepoint absent from one is looked up in its [sibling].
     */
    enum class Family(internal val resource: String) {
        A("a"), AA("aa"), B("b"), BB("bb"), C("c"), CC("cc");

        /** The paired atlas that covers the other script at the same weight. */
        internal val sibling: Family
            get() = when (this) {
                A -> AA
                AA -> A
                B -> BB
                BB -> B
                C -> CC
                CC -> C
            }
    }

    private val atlases = HashMap<Family, SystemFontAtlas>()
    private var ready = false
    private var failed = false

    /** Lazily builds all six atlases on the calling (render) thread. Returns false if unavailable. */
    private fun ensureReady(): Boolean {
        if (ready) return true
        if (failed) return false
        return try {
            for (family in Family.entries) {
                atlases[family] = SystemFontAtlas.load(family.resource)
            }
            ready = true
            true
        } catch (e: Throwable) {
            failed = true
            logger.error("SystemFont: failed to build atlases", e)
            false
        }
    }

    /** Number of glyphs the given family parsed (diagnostic; 0 before [ensureReady] succeeds). */
    fun glyphCount(family: Family): Int {
        if (!ensureReady()) return 0
        return atlases[family]?.glyphCount ?: 0
    }

    /**
     * The atlas that should provide [ch] for [family]: the family itself if it has the glyph,
     * otherwise its [Family.sibling], otherwise the family again (its whitespace fallback path).
     */
    private fun atlasFor(family: Family, ch: Char): SystemFontAtlas? {
        val primary = atlases[family] ?: return null
        if (primary.has(ch)) return primary
        val sibling = atlases[family.sibling]
        if (sibling != null && sibling.has(ch)) return sibling
        return primary
    }

    /** Advanced pixel width of [text] in [family] at [pxSize]. */
    fun width(family: Family, text: String, pxSize: Float): Float {
        if (!ensureReady()) return 0f
        var pen = 0f
        for (ch in text) {
            val atlas = atlasFor(family, ch) ?: continue
            val glyph = atlas.glyph(ch) ?: atlas.glyph(' ') ?: continue
            pen += glyph.advance * pxSize
        }
        return pen
    }

    /** Height of one text line in [family] at [pxSize] (`metrics.lineHeight * pxSize`). */
    fun lineHeight(family: Family, pxSize: Float): Float {
        if (!ensureReady()) return pxSize
        val atlas = atlases[family] ?: return pxSize
        return atlas.lineHeightEm * pxSize
    }

    /**
     * Draws [text] with its top-left at ([x], [y]) in a single [color], at pixel size [pxSize]
     * (the EM square height). Returns the pen X after the last glyph (`x + advanced width`).
     *
     * The baseline is fixed by the requested family's ascender; a glyph that falls back to the
     * sibling atlas keeps that shared baseline (its own `planeBounds` already position it relative
     * to the baseline). Because each [drawCustomElement] call binds ONE texture (Sampler0), glyphs
     * are grouped by their providing atlas and emitted in one call per atlas — in the common case
     * (all glyphs in the primary atlas) that is a single call, exactly like `HudFont.drawCore`.
     */
    fun draw(
        ctx: GuiGraphicsExtractor,
        family: Family,
        text: String,
        x: Float,
        y: Float,
        pxSize: Float,
        color: Color4b = Color4b.WHITE,
    ): Float {
        if (!ensureReady() || text.isEmpty()) return x
        val primary = atlases[family] ?: return x

        val argb = color.argb
        val baseY = y + primary.ascenderEm * pxSize

        // One sequential pass resolves each glyph to its atlas and advances the pen, so the pen stays
        // continuous even across a sibling-atlas fallback. Quads are bucketed by atlas for emit.
        val groups = LinkedHashMap<SystemFontAtlas, MutableList<Placement>>()
        var pen = x
        for (ch in text) {
            val atlas = atlasFor(family, ch) ?: continue
            val glyph = atlas.glyph(ch) ?: atlas.glyph(' ') ?: continue
            if (glyph.quadW > 0f && glyph.quadH > 0f) {
                groups.getOrPut(atlas) { ArrayList() }.add(Placement(glyph, pen))
            }
            pen += glyph.advance * pxSize
        }

        val advanced = pen - x
        if (groups.isNotEmpty()) {
            // The mtsdf field and tall ascenders can reach a little past the nominal line box; an
            // over-sized element bound only affects culling, never the 1:1 glyph geometry.
            val pad = pxSize * 0.25f
            val bounds = ctx.getBoundsXYWH(
                x - pad,
                y - pad,
                advanced + 2f * pad,
                primary.lineHeightEm * pxSize + 2f * pad,
            )

            for ((atlas, placements) in groups) {
                ctx.drawCustomElement(
                    // UNVERIFIED-SYMBOL: ClientRenderPipelines.GUI.systemMsdfText() is published by
                    // sibling Agent A (mtsdf pipeline, DISTANCE_RANGE=12). Expected-pending until then.
                    pipeline = ClientRenderPipelines.GUI.systemMsdfText(),
                    textureSetup = atlas.setup,
                    bounds = bounds,
                ) { pose ->
                    for (placement in placements) {
                        val glyph = placement.glyph
                        val x0 = placement.penX + glyph.offsetX * pxSize
                        val y0 = baseY + glyph.offsetY * pxSize
                        val x1 = x0 + glyph.quadW * pxSize
                        val y1 = y0 + glyph.quadH * pxSize

                        // POSITION_TEX_COLOR quad — winding + UV assignment mirror HudFont.drawCore.
                        addVertexWith2DPose(pose, x0, y0).setUv(glyph.u0, glyph.v0).setColor(argb)
                        addVertexWith2DPose(pose, x0, y1).setUv(glyph.u0, glyph.v1).setColor(argb)
                        addVertexWith2DPose(pose, x1, y1).setUv(glyph.u1, glyph.v1).setColor(argb)
                        addVertexWith2DPose(pose, x1, y0).setUv(glyph.u1, glyph.v0).setColor(argb)
                    }
                }
            }
        }

        return x + advanced
    }

    /** A resolved glyph plus its pen X; the shared baseline and pxSize are captured at draw time. */
    private class Placement(val glyph: SystemFontAtlas.Glyph, val penX: Float)
}
