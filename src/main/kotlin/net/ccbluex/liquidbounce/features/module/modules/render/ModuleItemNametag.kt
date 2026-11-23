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
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcItems
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawLinesWithWidth
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.drawPlateItem
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import kotlin.math.hypot
import kotlin.math.max

/**
 * ItemNametag module
 *
 * 1:1 visual port of the SystemDLC `client.module.render.ItemESP`
 * (`.../system-source/src/main/java/client/module/render/ItemESP.java`, 461 L). Above every dropped
 * [ItemEntity] it can draw a camera-facing rounded plate (icon + optional `xN` count + item name)
 * and/or a distance-thickness-scaled 3D box, exactly like the source `render8` / `render9` methods.
 *
 * This is a NEW module named "ItemNametag" (the user's chosen name); the fork's unrelated upstream
 * [net.ccbluex.liquidbounce.features.module.modules.render.ModuleItemESP] is a separate module and
 * is not touched. Because ItemNametag also carries the source's 3D-box half, the "Nametag" name is
 * partly a misnomer — this is the user's explicit choice, kept as requested.
 *
 * ## Plate — render pattern (A), projected screen-space plate (mirrors [ModuleTNTTimer])
 * The SystemDLC original draws a true 3D billboard: it translates the pose to the lerped item
 * position + height + 0.5, applies its camera-facing `RotationBuffer`, scales the local geometry by
 * the (negative) `DistanceScale` factor and rasterizes shape/icon/text through its own shaders
 * (ItemESP.java:167-259, 442-446). That shader stack is NOT ported. Instead — exactly as
 * [ModuleTNTTimer] does — the plate anchor is projected with [WorldToScreen.calculateScreenPos] and
 * the plate is drawn in an [OverlayRenderEvent] with our GUI API (`drawRoundedRect` + `item` + the
 * imported SystemDLC font). The world-space distance-scale factor is converted to a screen-pixel scale by
 * measuring how many screen pixels one world block spans along the camera-up axis at the anchor
 * depth (perpendicular to view, never foreshortens), reproducing the perspective sizing.
 *
 * Local plate geometry is verbatim from `onMatrix4fIntItemEspEntry` (ItemESP.java:261-295) and
 * `onMatrix4fIntItemEspEntry2` (ItemESP.java:371-390): plate HEIGHT 32 (note TntTimer used 26),
 * radius 8, left pad 6, icon 16, gap 4 before the count, gap 6 before the name, right pad 6; the
 * background fill is `Theme.background()` with alpha scaled by PlateOpacity/100
 * (`getIntByIntFloat`, ItemESP.java:178,360-365). The plate width formula
 * `22 + (count ? 4 + countW : 0) + 6 + nameW + 6` is `value5` (ItemESP.java:357).
 *
 * ## Box — world-space (drawBox + drawLinesWithWidth)
 * `render9` (ItemESP.java:392-436) builds an AABB centred on the item's x/z (width = entity width)
 * spanning y..y+height and rasterizes, in order, a translucent FILL, a thick dark OUTLINE stroke and
 * the coloured LINE edges, with per-edge world half-width `thickness * 0.005 * max(1, dist * 0.12)`
 * when DistanceScaleThickness is on (`BoxEspRenderer`, BoxEspRenderer.java:148-191). Ported here with
 * our clean API: [drawBox] draws the fill faces, [drawLinesWithWidth] draws the outline then the
 * lines. The `max(1, dist * 0.12)` distance-thickness factor and the outline-is-2.2x-the-line ratio
 * are reproduced; the outline alpha is multiplied by the line alpha (`f6 = value6 * value9`,
 * BoxEspRenderer.java:169).
 *
 * ## Fidelity notes / deviations (all cited)
 * - The plate half's FOV-cone visibility test (`ItemPickupMath.isCameraDoubleFloatDoubleDouble`,
 *   ItemPickupMath.java:130-144, a `cos(fov/2 + 10deg)` dot-product gate, ItemESP.java:194) is
 *   NOT ported. As [ModuleTNTTimer] does, off-screen plates are instead dropped by the null return
 *   of [WorldToScreen.calculateScreenPos] (behind-camera / non-finite). Slightly wider culling.
 * - No per-entity smoothing: the source has none for items (`ItemEspEntry.update` only nulls its
 *   fields, ItemEspEntry.java:24-29) — unlike TntTimer. Not added here.
 * - Line/outline width: the source's world-space half-width `thickness * 0.005 * factor`
 *   (BoxEspRenderer.java:148-149,190) is a triangle-strip half-width in blocks; our
 *   [drawLinesWithWidth] takes a width in the `LinesWithWidth` pipeline's own units (as
 *   [ModuleTracers] uses 1..16). The `0.005` world constant is therefore dropped and LineThickness
 *   is used directly as the base width; the distance factor and 2.2x outline ratio are kept.
 * - The mode tags are English (`Plate` / `Box` / `Plate+Box`) rather than the source's Cyrillic
 *   labels (ItemESP.java:73-78); the fork's GUI keys off Tagged tags, ASCII by convention.
 */
object ModuleItemNametag : ClientModule("ItemNametag", ModuleCategories.RENDER) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.itemNametag"

    /**
     * "Режим" / how to display items: Plate, Box, or both. Source `mode` ListSetting, default
     * "Плашка"(Plate) (ItemESP.java:69-82).
     */
    private enum class DisplayMode(override val tag: String) : Tagged {
        PLATE("Plate"),
        BOX("Box"),
        BOTH("Plate+Box"),
    }

    private val mode by enumChoice("Mode", DisplayMode.PLATE)

    // --- plate settings (source: ItemESP.java:83-102) ---

    /** "Размер" plate scale — SliderSetting 0.35, 0.01..1.0 (ItemESP.java:83). */
    private val size by float("Size", 0.35F, 0.01F..1.0F)

    /** "Прозрачность плашки" background opacity in % — SliderSetting 100, 0..100 (ItemESP.java:87). */
    private val plateOpacity by int("PlateOpacity", 100, 0..100)

    /** "Только ценные предметы" only valuables — BooleanSetting false (ItemESP.java:91). */
    private val onlyValuable by boolean("OnlyValuable", false)

    /** "Подсвечивать талики" highlight FT talismans — BooleanSetting true (ItemESP.java:95). */
    private val highlightTalismans by boolean("HighlightTalismans", true)

    /** "Цвет таликов" talisman text color — ColorSetting -48060 = 0xFFFF4444 (ItemESP.java:99). */
    private val talismanColor by color("TalismanColor", Color4b(0xFFFF4444.toInt()))

    // --- box settings (source groups "Форма"/"Цвета", ItemESP.java:103-152) ---

    /** "Толщина линии" line thickness — SliderSetting 2.0, 0.1..5.0 (ItemESP.java:103). */
    private val lineThickness by float("LineThickness", 2.0F, 0.1F..5.0F)

    /** "Масштаб от дистанции" thicken lines with distance — BooleanSetting true (ItemESP.java:107). */
    private val distanceScaleThickness by boolean("DistanceScaleThickness", true)

    /** "Цвет линий" line color — ColorSetting -1 = 0xFFFFFFFF (ItemESP.java:111). */
    private val lineColor by color("LineColor", Color4b(0xFFFFFFFF.toInt()))

    /** "Обводка" dark outline stroke — BooleanSetting false (ItemESP.java:115). */
    private val outline by boolean("Outline", false)

    /** "Цвет обводки" outline color — ColorSetting -16777216 = 0xFF000000 (ItemESP.java:119). */
    private val outlineColor by color("OutlineColor", Color4b(0xFF000000.toInt()))

    /** "Заливка" box fill — BooleanSetting false (ItemESP.java:123). */
    private val fill by boolean("Fill", false)

    /** "Цвет заливки" fill color — ColorSetting 1291845631 = 0x4CFFFFFF (ItemESP.java:127). */
    private val fillColor by color("FillColor", Color4b(0x4CFFFFFF))

    /** Cache of translated vanilla item names, keyed by item (source `map`, ItemESP.java:332-338). */
    private val nameCache = HashMap<Item, String>()

    // ---- plate (render8 + check4: Plate or Both) ----

    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        if (mode != DisplayMode.PLATE && mode != DisplayMode.BOTH) {
            return@handler
        }
        val player = mc.player ?: return@handler
        if (mc.level == null) {
            return@handler
        }

        val cameraPos = mc.gameRenderer.mainCamera.position()
        // Camera-up basis vector (unit, perpendicular to view) for the world->screen scale probe.
        val up = mc.gameRenderer.mainCamera.upVector()
        val playerPos = player.position()
        val onlyValuableFlag = onlyValuable

        for (entity in world.entitiesForRendering()) {
            if (entity !is ItemEntity || !shouldInclude(entity, playerPos, onlyValuableFlag)) {
                continue
            }

            val stack = entity.item
            // Anchor: lerped item pos + height + 0.5 (ItemESP.java:191,443).
            val anchor = entity.interpolateCurrentPosition(event.tickDelta)
                .add(0.0, entity.bbHeight.toDouble() + 0.5, 0.0)
            val anchorScreen = WorldToScreen.calculateScreenPos(anchor) ?: continue

            // Distance-scale factor = clamp(dist * 0.0025, 0.02, 0.1) * Size, dist = player->item
            // pos (non-lerped) (ItemESP.java:196,348; DistanceScale.java:5-6).
            val distance = playerPos.distanceTo(entity.position())
            val scaleFactor = SystemDlcTheme.distanceScale(distance, size)

            // World->screen: pixels spanned by one world block along camera-up at the anchor depth.
            val upPoint = anchor.add(up.x().toDouble(), up.y().toDouble(), up.z().toDouble())
            val upScreen = WorldToScreen.calculateScreenPos(upPoint) ?: continue
            val pixelsPerBlock = hypot(
                (upScreen.x - anchorScreen.x).toDouble(),
                (upScreen.y - anchorScreen.y).toDouble(),
            ).toFloat()
            val screenScale = scaleFactor * pixelsPerBlock
            if (screenScale <= 0.0F) {
                continue
            }

            drawPlate(event, anchorScreen.x, anchorScreen.y, screenScale, stack)
        }
    }

    /**
     * Draws one plate centred on the projected anchor, with the exact SystemDLC local geometry
     * (ItemESP.java:261-295, 371-390) scaled from local units to screen pixels by [screenScale].
     */
    private fun drawPlate(
        event: OverlayRenderEvent,
        anchorX: Float,
        anchorY: Float,
        screenScale: Float,
        stack: ItemStack,
    ) {
        val tracked = if (stack.item === Items.TOTEM_OF_UNDYING) {
            SystemDlcItems.trackedItemByTotemModelData(stack)
        } else {
            null
        }
        val isTalisman = tracked != null                              // flag2 (ItemESP.java:350)
        val name = resolveName(stack, tracked)                        // text (ItemESP.java:351)
        val count = stack.count
        val hasCount = count > 1                                      // flag (ItemESP.java:353)
        val countText = if (hasCount) "x$count" else ""              // text2 (ItemESP.java:354)

        // Widths in plate-local units: the imported SystemDLC font width at the source size-14 name
        // and size-12 count (ItemESP.java:355-356), so the plate auto-fits the real glyphs.
        val nameWidth = SystemDraw.textWidth(name, NAME_SIZE, TEXT_FAMILY)           // value3
        val countWidth = if (hasCount) SystemDraw.textWidth(countText, COUNT_SIZE, TEXT_FAMILY) else 0.0F

        // value5 = 22 + (count ? 4 + countW : 0) + 6 + nameW + 6 (ItemESP.java:357).
        val plateWidth = 22.0F + (if (hasCount) 4.0F + countWidth else 0.0F) + 6.0F + nameWidth + 6.0F
        val left = -plateWidth / 2.0F                                 // f / f7 (ItemESP.java:372,262)

        // Name color: talisman? (highlight ? TalismanColor : fg) : fg (ItemESP.java:181,242).
        val nameColor = if (isTalisman && highlightTalismans) talismanColor else SystemDlcTheme.foreground()
        // Background fill = Theme.background() with alpha * PlateOpacity/100 (ItemESP.java:177-178).
        val background = SystemDlcTheme.withAlphaFactor(SystemDlcTheme.background(), plateOpacity / 100.0F)

        with(event.context) {
            pose().pushMatrix()
            pose().translate(anchorX, anchorY)
            pose().scale(screenScale, screenScale)

            // Rounded plate: [left, -16]..[left+width, 16], radius 8, no border (ItemESP.java:371-389).
            drawRoundedRect(
                x1 = left,
                y1 = -PLATE_HEIGHT / 2.0F,
                x2 = left + plateWidth,
                y2 = PLATE_HEIGHT / 2.0F,
                radius = PLATE_RADIUS,
                fillColor = background,
                outlineColor = null,
                outlineWidth = 0.0F,
            )

            // 16x16 item icon at (left + 6, -8) (f5 = f + 6, f2 = -8, ItemESP.java:267,264,271).
            // Crisp per-on-screen-size raster (drawPlateItem) instead of the vanilla fixed-16px atlas
            // blit, which aliases under this plate's screenScale (see PlateItemIcon).
            pose().pushMatrix()
            pose().translate(left + ICON_LEFT_PAD, -ICON_SIZE / 2.0F)
            drawPlateItem(stack, 0.0F, 0.0F, ICON_SIZE, screenScale)
            pose().popMatrix()

            // Cursor advances left->right (ItemESP.java:277-294).
            if (hasCount) {
                // count at left + 26 (icon + 4), size 12, mutedFg (ItemESP.java:278-287).
                val countX = left + 22.0F + 4.0F
                drawScaledText(countText, countX, COUNT_SIZE, SystemDlcTheme.mutedFg())
            }
            // name after icon(+count) + 6, size 14 (ItemESP.java:290-294).
            val nameX = left + 28.0F + if (hasCount) 4.0F + countWidth else 0.0F
            drawScaledText(name, nameX, NAME_SIZE, nameColor)

            pose().popMatrix()
        }
    }

    /**
     * Draws [text] left-aligned at plate-local x [leftX], vertically centred on the plate centre,
     * at the given [size], in the imported SystemDLC font ([SystemFont]). SystemFont's `y` is the
     * line top, so `-size/2` centres a `size`-tall line on the plate centre (ItemESP.java:264-266).
     * The active pose (anchor + screenScale) scales the glyphs 1:1 with the plate.
     */
    private fun GuiGraphicsExtractor.drawScaledText(
        text: String,
        leftX: Float,
        size: Float,
        color: Color4b,
    ) {
        SystemDraw.text(this, text, leftX, -size / 2.0F, size, color, TEXT_FAMILY)
    }

    // ---- box (render9 + check3: Box or Both) ----

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        if (mode != DisplayMode.BOX && mode != DisplayMode.BOTH) {
            return@handler
        }
        val player = mc.player ?: return@handler
        if (mc.level == null) {
            return@handler
        }

        val playerPos = player.position()
        val onlyValuableFlag = onlyValuable
        val cameraPos = mc.gameRenderer.mainCamera.position()

        val line = lineColor
        val fillEnabled = fill
        val fillCol = fillColor
        val outlineEnabled = outline
        // Outline alpha is multiplied by line alpha (f6 = value6 * value9, BoxEspRenderer.java:169).
        val outlineEffective = outlineColor.fade(line.a / 255.0F)
        val scaleThickness = distanceScaleThickness
        val baseThickness = lineThickness

        renderEnvironmentForWorld(event.matrixStack) {
            for (entity in world.entitiesForRendering()) {
                if (entity !is ItemEntity || !shouldInclude(entity, playerPos, onlyValuableFlag)) {
                    continue
                }

                val pos = entity.interpolateCurrentPosition(event.partialTicks)
                val width = entity.bbWidth.toDouble()
                val height = entity.bbHeight.toDouble()
                // Local AABB centred on x/z, y..y+height (ItemESP.java:59-64 of BoxEspRenderer).
                val box = AABB(-width / 2.0, 0.0, -width / 2.0, width / 2.0, height, width / 2.0)

                // Distance-thickness factor: max(1, dist * 0.12), dist = camera->lerped pos
                // (BoxEspRenderer.java:190).
                val factor = if (scaleThickness) {
                    max(1.0, pos.distanceTo(cameraPos) * 0.12).toFloat()
                } else {
                    1.0F
                }

                withPositionRelativeToCamera(pos) {
                    // Draw order: fill, dark outline (thick), coloured lines (BoxEspRenderer.java:163-177).
                    if (fillEnabled && fillCol.a > 0) {
                        drawBox(box, faceColor = fillCol, outlineColor = null)
                    }
                    val edges = boxEdges(box)
                    if (outlineEnabled && outlineColor.a > 0) {
                        drawLinesWithWidth(outlineEffective.argb, baseThickness * OUTLINE_RATIO * factor, *edges)
                    }
                    drawLinesWithWidth(line.argb, baseThickness * factor, *edges)
                }
            }
        }
    }

    /** The 12 edges of [box] as 24 [Vec3f] (line-pair) vertices, in local box coordinates. */
    private fun boxEdges(box: AABB): Array<Vec3f> {
        val x0 = box.minX.toFloat(); val x1 = box.maxX.toFloat()
        val y0 = box.minY.toFloat(); val y1 = box.maxY.toFloat()
        val z0 = box.minZ.toFloat(); val z1 = box.maxZ.toFloat()
        return arrayOf(
            // bottom face
            Vec3f(x0, y0, z0), Vec3f(x1, y0, z0),
            Vec3f(x1, y0, z0), Vec3f(x1, y0, z1),
            Vec3f(x1, y0, z1), Vec3f(x0, y0, z1),
            Vec3f(x0, y0, z1), Vec3f(x0, y0, z0),
            // top face
            Vec3f(x0, y1, z0), Vec3f(x1, y1, z0),
            Vec3f(x1, y1, z0), Vec3f(x1, y1, z1),
            Vec3f(x1, y1, z1), Vec3f(x0, y1, z1),
            Vec3f(x0, y1, z1), Vec3f(x0, y1, z0),
            // vertical edges
            Vec3f(x0, y0, z0), Vec3f(x0, y1, z0),
            Vec3f(x1, y0, z0), Vec3f(x1, y1, z0),
            Vec3f(x1, y0, z1), Vec3f(x1, y1, z1),
            Vec3f(x0, y0, z1), Vec3f(x0, y1, z1),
        )
    }

    // ---- shared gather (ItemPickupMath.isVec3dItemEntityDoubleBooleanDouble, :54-65) ----

    /**
     * Item is alive, within 128 blocks of [playerPos] (squared, non-lerped), holds >= 1 item, and —
     * when [onlyValuableFlag] — its item is in the hard-coded valuables set (DepthState.java:22-67,
     * mirrored by [isValuable]). Source ItemPickupMath.java:54-65.
     */
    private fun shouldInclude(entity: ItemEntity, playerPos: net.minecraft.world.phys.Vec3, onlyValuableFlag: Boolean): Boolean {
        if (!entity.isAlive) {
            return false
        }
        if (entity.position().distanceToSqr(playerPos) > MAX_DISTANCE_SQ) {
            return false
        }
        val stack = entity.item
        if (stack.count < 1) {
            return false
        }
        return !onlyValuableFlag || isValuable(stack.item)
    }

    /** Name resolution — ItemESP.getStringByItemStack (ItemESP.java:322-343). */
    private fun resolveName(stack: ItemStack, tracked: net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.TrackedItem?): String {
        val item = stack.item
        // Totem with a talisman custom-model-data -> talisman RU name (ItemESP.java:324-329).
        if (item === Items.TOTEM_OF_UNDYING && tracked != null) {
            return tracked.text
        }
        // Player head / totem (no talisman) -> stack display name (ItemESP.java:340-342).
        return if (item !== Items.PLAYER_HEAD && item !== Items.TOTEM_OF_UNDYING) {
            // else -> translated vanilla item name, cached (ItemESP.java:331-339).
            nameCache.getOrPut(item) { stripColor(Component.translatable(item.descriptionId).string) }
        } else {
            stripColor(stack.hoverName.string)
        }
    }

    /** Strips `§x` color codes (source Pattern "§.", ItemESP.java:48,336,341). */
    private fun stripColor(text: String): String = text.replace(COLOR_CODE, "")

    /** The 44-item valuables set (DepthState.java:22-67). */
    private fun isValuable(item: Item): Boolean = item in VALUABLES

    override fun onDisabled() {
        nameCache.clear()
        super.onDisabled()
    }

    private const val MAX_DISTANCE_SQ = 128.0 * 128.0     // d2 = 128 (ItemESP.java:188)
    private const val NAME_SIZE = 14.0F                    // f11 (ItemESP.java:293)
    private const val COUNT_SIZE = 12.0F                   // f9 (ItemESP.java:284)
    private const val PLATE_HEIGHT = 32.0F                 // f3 (ItemESP.java:385)
    private const val PLATE_RADIUS = 8.0F                  // f4..f7 (ItemESP.java:381-384)
    private const val ICON_LEFT_PAD = 6.0F                 // f5 = f + 6 (ItemESP.java:267)
    private const val ICON_SIZE = 16.0F                    // f6 (ItemESP.java:269)
    private const val OUTLINE_RATIO = 2.2F                 // f2 = f1 * 2.2 (BoxEspRenderer.java:149)

    /** Imported SystemDLC body atlas (Latin `b` / Cyrillic `bb`), the source's primary body weight. */
    private val TEXT_FAMILY = SystemFont.Family.B

    private val COLOR_CODE = Regex("§.")

    /** Valuables set, item-for-item from DepthState.java:22-67 (44 entries). */
    private val VALUABLES: Set<Item> = setOf(
        Items.DIAMOND,
        Items.EMERALD,
        Items.GOLD_INGOT,
        Items.IRON_INGOT,
        Items.NETHERITE_INGOT,
        Items.DIAMOND_SWORD,
        Items.DIAMOND_PICKAXE,
        Items.DIAMOND_AXE,
        Items.DIAMOND_SHOVEL,
        Items.DIAMOND_HOE,
        Items.DIAMOND_HELMET,
        Items.DIAMOND_CHESTPLATE,
        Items.DIAMOND_LEGGINGS,
        Items.DIAMOND_BOOTS,
        Items.NETHERITE_SWORD,
        Items.NETHERITE_PICKAXE,
        Items.NETHERITE_AXE,
        Items.NETHERITE_SHOVEL,
        Items.NETHERITE_HOE,
        Items.NETHERITE_HELMET,
        Items.NETHERITE_CHESTPLATE,
        Items.NETHERITE_LEGGINGS,
        Items.NETHERITE_BOOTS,
        Items.ENCHANTED_GOLDEN_APPLE,
        Items.GOLDEN_APPLE,
        Items.TOTEM_OF_UNDYING,
        Items.ELYTRA,
        Items.SHULKER_BOX,
        Items.WHITE_SHULKER_BOX,
        Items.ORANGE_SHULKER_BOX,
        Items.MAGENTA_SHULKER_BOX,
        Items.LIGHT_BLUE_SHULKER_BOX,
        Items.YELLOW_SHULKER_BOX,
        Items.LIME_SHULKER_BOX,
        Items.PINK_SHULKER_BOX,
        Items.GRAY_SHULKER_BOX,
        Items.LIGHT_GRAY_SHULKER_BOX,
        Items.CYAN_SHULKER_BOX,
        Items.PURPLE_SHULKER_BOX,
        Items.BLUE_SHULKER_BOX,
        Items.BROWN_SHULKER_BOX,
        Items.GREEN_SHULKER_BOX,
        Items.RED_SHULKER_BOX,
        Items.BLACK_SHULKER_BOX,
    )
}
