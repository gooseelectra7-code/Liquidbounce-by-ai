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
package net.ccbluex.liquidbounce.features.module.modules.render.nametags

import net.ccbluex.fastutil.Pool
import net.ccbluex.liquidbounce.config.types.CurveValue.Axis.Companion.axis
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.utils.combat.shouldBeShown
import net.ccbluex.liquidbounce.utils.entity.RenderedEntities
import net.ccbluex.liquidbounce.utils.entity.cameraDistance
import net.ccbluex.liquidbounce.utils.entity.cameraDistanceSq
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.render.entity
import net.ccbluex.liquidbounce.utils.render.isCustom
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.entity.state.EntityRenderState
import org.joml.Vector2f

/**
 * Nametags module
 *
 * Makes player name tags more visible and adds useful information.
 */
object ModuleNametags : ClientModule("Nametags", ModuleCategories.RENDER) {

    init {
        tree(NametagTextFormatter)
        tree(NametagEquipment)
        tree(NametagEffects)
        tree(NametagSystemStyle)
    }

    /**
     * Rendering style. [NametagStyle.DEFAULT] is the unchanged LiquidBounce plate;
     * [NametagStyle.SYSTEM] restyles the plate to the SystemDLC look ([NametagSystemStyle]).
     * Default MUST stay [NametagStyle.DEFAULT] so existing behavior is byte-identical.
     */
    private val styleValue = enumChoice("Style", NametagStyle.DEFAULT)

    internal val style by styleValue

    internal enum class NametagStyle(override val tag: String) : Tagged {
        DEFAULT("Default"),
        SYSTEM("System"),
    }

    private val borderWidthValue = float("BorderWidth", 1f, 0f..8f)
    internal val borderWidth by borderWidthValue

    private val backgroundRadiusValue = float("BackgroundRadius", 2f, 0f..16f)
    internal val backgroundRadius by backgroundRadiusValue

    /**
     * Vertical shift of the tag relative to the entity, in blocks, added to the default
     * eye-height + 0.55 anchor in [NametagRenderState.calculateScreenPos]. Applied in WORLD
     * space (before projection), so the tag keeps its offset consistently at any distance
     * instead of drifting the way a screen-space shift would.
     */
    internal val verticalOffsetValue = float("VerticalOffset", 0f, -2f..2f)

    internal val verticalOffset by verticalOffsetValue

    /**
     * Distance -> scale curve, applied by BOTH styles.
     *
     * For [NametagStyle.DEFAULT] it is the tag's whole scale. For [NametagStyle.SYSTEM] it multiplies
     * the plate's own distance compensation ([NametagSystemStyle] `Size`), which is a different job:
     * that one already holds the plate at a constant on-screen size between 8 and 40 blocks
     * (`SystemDlcTheme.distanceScale` clamps `distance * 0.0025` to `0.02..0.1`), so a flat `1.0`
     * curve leaves it exactly as the source draws it and a rising curve grows far tags on top of that.
     * Until 2026-09-17 the SYSTEM plate ignored this curve entirely.
     */
    internal val scale = curve(
        "Scale",
        mutableListOf(Vector2f(0f, 1f), Vector2f(200f, 1f)),
        xAxis = "Distance" axis 0f..200f,
        yAxis = "Scale" axis 0.25f..4f,
    )

    /**
     * The face the tag text is drawn with: the CrystalAura damage face
     * (`SF Pro Rounded Bold`, `SFProRounded-Bold.otf`, baked by `FontManager.loadSfProFamily()` before
     * `createGlyphManager()`), falling back to the client default while it is unavailable - the OTF is
     * only registered when that bake succeeded, and a missing face must not take the tags down with it.
     *
     * Everything the [NametagStyle.DEFAULT] plate lays out is expressed in `fontRenderer.height` units and
     * scaled by [net.ccbluex.liquidbounce.render.AbstractFontRenderer.scaleToVanillaFont], so the swap keeps
     * the tag at the same on-screen line height even though the two atlases are baked at different sizes.
     * [NametagStyle.SYSTEM] does not read this - it draws through its own imported SystemDLC atlas.
     */
    val fontRenderer
        get() = FontManager.fontFace(NAMETAG_FONT_NAME)?.renderer ?: FontManager.FONT_RENDERER

    private val nametagPool = Pool(::NametagRenderState, NametagRenderState::reset)

    private val nametagsToRender = mutableListOf<NametagRenderState>()

    override fun onDisabled() {
        RenderedEntities.unsubscribe(this)
        nametagPool.recycleAll(nametagsToRender)
        nametagsToRender.clear()
        NametagSystemStyle.reset()
        NametagEffects.reset()
    }

    override fun onEnabled() {
        RenderedEntities.subscribe(this)
        RenderedEntities.onUpdated(::collectAndSortNametagsToRender)
    }

    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent>(priority = FIRST_PRIORITY) { event ->
        if (nametagsToRender.isEmpty()) {
            return@handler
        }

        event.context.drawNametags(event.tickDelta)
    }

    private fun GuiGraphicsExtractor.drawNametags(tickDelta: Float) {
        if (style == NametagStyle.SYSTEM) {
            NametagSystemStyle.render(this, tickDelta, nametagsToRender)
            return
        }

        for (nametagInfo in nametagsToRender) {
            val (x, y) = nametagInfo.calculateScreenPos(tickDelta) ?: continue

            drawNametag(nametagInfo, x, y)
        }
    }

    /**
     * Collects all entities that should be rendered, gets the screen position, where the name tag should be displayed,
     * add what should be rendered ([NametagRenderState]). The nametags are sorted in order of rendering.
     */
    private fun collectAndSortNametagsToRender() {
        nametagPool.recycleAll(nametagsToRender)
        nametagsToRender.clear()
        for (entity in RenderedEntities) {
            val distance = entity.position().cameraDistance().toFloat()
            val scale = scale.transform(distance)
            // Both styles read the curve now (see [scale]), so one gate covers both. The curve's y axis
            // bottoms out at 0.25, so in practice nothing is ever dropped here; the check is kept as the
            // guard it has always been against a degenerate curve.
            if (scale > 0.01f) {
                val nametag = nametagPool.borrow()
                nametag.update(entity, scale)
                nametagsToRender += nametag
            }
        }
        nametagsToRender.sortWith(NAMETAG_COMPARATOR)
    }

    private val NAMETAG_COMPARATOR: Comparator<NametagRenderState> = Comparator.comparingDouble { nametag ->
        nametag.entity?.position()?.cameraDistanceSq() ?: Double.POSITIVE_INFINITY
    }

    fun shouldRenderVanillaNametag(state: EntityRenderState): Boolean {
        return !running || !(state.entity ?: return true).shouldBeShown() || state.isCustom
    }

    /** Reusable state for [drawPreviewNametag] — the preview draws exactly one tag per frame. */
    private val previewNametag by lazy { NametagRenderState() }

    /**
     * Draws a real nametag for [entity] at an explicit screen position, for the ClickGUI
     * visuals preview.
     *
     * The world path derives its position from the main camera, which the preview's own scene
     * does not share — so the caller projects the anchor itself and passes it in. Everything
     * else (text formatting, equipment row, background, border) is the SAME code the in-world
     * tag uses, so the preview reflects the live settings.
     */
    internal fun drawPreviewNametag(
        context: GuiGraphicsExtractor,
        entity: net.minecraft.world.entity.Entity,
        x: Float, y: Float, scale: Float,
    ) {
        previewNametag.update(entity, scale)
        with(context) { drawNametag(previewNametag, x, y) }
    }

    /**
     * Moves everything only one style draws out of the GUI, per the settings-style rule in AGENTS.md
     * ("a setting that does not apply right now must not be on screen"). [NametagTextFormatter],
     * [NametagEquipment], [borderWidth] and [backgroundRadius] are read only by [drawNametag];
     * [NametagSystemStyle] only by [NametagSystemStyle.render]. Everything else — [scale],
     * [verticalOffset] and [NametagEffects] — applies to both styles and stays put.
     *
     * The listener is attached from an `init` at the END of the object rather than chained onto
     * [styleValue] at its declaration, because it writes fields declared after it; by the time this
     * runs every one of them exists. The explicit call covers the INITIAL state, which no change
     * hook can (AGENTS.md, "Settings style").
     */
    private fun syncStyleVisibility(current: NametagStyle) {
        val system = current == NametagStyle.SYSTEM
        NametagTextFormatter.notAnOption(system)
        NametagEquipment.notAnOption(system)
        borderWidthValue.notAnOption(system)
        backgroundRadiusValue.notAnOption(system)
        NametagSystemStyle.notAnOption(!system)
    }

    init {
        styleValue.onChanged { syncStyleVisibility(it) }
        syncStyleVisibility(style)
    }

    /**
     * `java.awt.Font.getName()` of the face the tags are drawn in - the key `FontManager.queueFontFromStream`
     * registers it under (`render/FontManager.kt:172-177`: `FontFace(font.name, size, file = null)`).
     * The same cut the CrystalAura damage text uses (`ModuleCrystalAura.DAMAGE_FONT_NAME`).
     */
    private const val NAMETAG_FONT_NAME = "SF Pro Rounded Bold"

}
