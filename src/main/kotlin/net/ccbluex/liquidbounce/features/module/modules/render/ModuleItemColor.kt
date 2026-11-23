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

import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ScreenRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcItems
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.TrackedItem
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import java.lang.reflect.Field
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * ItemColor module.
 *
 * 1:1 behavioural port of the SystemDLC `client.module.render.ItemColor`
 * (`.../system-source/src/main/java/client/module/render/ItemColor.java`, 188 L). While a
 * container screen (any [AbstractContainerScreen] except the creative inventory) is open, every
 * occupied slot whose stack resolves to one of the 36 [TrackedItem]s gets its 16x16 rect filled
 * with that item's configured colour; in "Blink" mode the fill alpha is modulated by a 900 ms
 * cosine pulse (ItemColor.java:110-131 / :82-88).
 *
 * ## Settings (ItemColor.java:37-71)
 *  - **Highlight** (`ListSetting "Подсветка"`, ItemColor.java:39-48): single choice
 *    Static / Blink, default **Blink** (source default `"Мигать"`).
 *  - **Items** (`CompactGroupSetting "Предметы"`, ItemColor.java:65-70): one colour+toggle per
 *    [TrackedItem] (36 of them, ItemColor.java:55-63). Each is a [ToggleableValueGroup] (the
 *    source's `ColorToggleSetting` = a colour with an on/off box), enabled by default, its colour
 *    defaulting to an evenly-spaced hue-wheel entry `MathUtil.getIntByFloat(ordinal/36f)`
 *    (ItemColor.java:57-58,77-80; see [hueWheelColor]) with the source's hard-coded ALPHA 0x78.
 *
 * ## Item matching — deliberate deviation from the byte-exact source
 * The source's own two-pass matcher `getColorToggleSettingByItemStack` (ItemColor.java:133-155)
 * matches ONLY by display-name substring (pass 1) then plain item-id / `ItemTags.SHULKER_BOXES`
 * (pass 2); it never inspects a potion's custom colour, a totem's custom-model-data id, or lore.
 * As instructed for this port, matching instead goes through the shared
 * [SystemDlcItems.resolve] (used by every SystemDLC render port), which is a SUPERSET: it also
 * matches potion custom-colour entries (POTION_POPPER etc.), totem/talisman CMD ids and lore
 * lines. Consequence: e.g. a splash potion is tinted by its actual custom colour rather than, as
 * the original did, falling through to the first item-id splash-potion entry. Everything else
 * (name-substring items, plain item-id items, shulker boxes) resolves identically.
 *
 * ## Render path / z-order — 26.1 vs the source
 * The source draws in its HUD pass with `DrawContext.fill` at z=0, so the fill lands BEHIND the
 * item sprite (items are drawn at a higher z in the same 1.21.4 batch). On 26.1 there is no
 * per-draw z: [ScreenRenderEvent] (`MixinGameRenderer.hookScreenRender`) fires AFTER the screen's
 * render state — slots and items included — is extracted, and `GuiRenderState.addGuiElement`
 * places a new element in a node ABOVE any already-submitted element whose bounds intersect it.
 * The fill therefore renders as a translucent wash OVER the item, not behind it. Reproducing the
 * exact "behind" ordering would require a mixin injection inside the slot loop (as
 * [ModuleBetterInventory]'s Border highlight does); this module is a single self-contained file
 * and does not add one. Everything else (which slots, which colour, the pulse) is faithful.
 */
object ModuleItemColor : ClientModule("ItemColor", ModuleCategories.RENDER) {

    private enum class HighlightMode(override val tag: String) : Tagged {
        /** source `"Cтатик"` — static fill. */
        STATIC("Static"),

        /** source `"Мигать"` — cosine-pulsed alpha (default). */
        BLINK("Blink"),
    }

    /** `ListSetting "Подсветка"`, default `"Мигать"` (ItemColor.java:43-44). */
    private val highlight by enumChoice("Highlight", HighlightMode.BLINK)

    /** Per-[TrackedItem] toggle group; `enabled` is the source's colour-toggle box. */
    private val itemGroups = LinkedHashMap<TrackedItem, ToggleableValueGroup>()

    /** Per-[TrackedItem] colour value (ARGB, default alpha 0x78 from the hue wheel). */
    private val itemColors = LinkedHashMap<TrackedItem, Value<Color4b>>()

    /**
     * `CompactGroupSetting "Предметы"` (ItemColor.java:65-70). Built by iterating
     * `TrackedItem.entries` exactly like the source loop (ItemColor.java:55-63).
     */
    @Suppress("unused")
    private val items = tree(object : ValueGroup("Items") {
        init {
            for (tracked in TrackedItem.entries) {
                val default = Color4b(hueWheelColor(tracked.ordinal, TrackedItem.entries.size))
                val group = object : ToggleableValueGroup(this@ModuleItemColor, tracked.name, enabled = true) {}
                val colorValue = group.color("Color", default)
                tree(group)
                itemGroups[tracked] = group
                itemColors[tracked] = colorValue
            }
        }
    })

    private val screenRenderHandler = handler<ScreenRenderEvent> { event ->
        // `currentScreen() instanceof HandledScreen`, minus the creative inventory (ItemColor.java:113).
        val screen = mc.screen
        if (screen !is AbstractContainerScreen<*> || screen is CreativeModeInventoryScreen) {
            return@handler
        }

        val origin = containerOrigin(screen)
        val originX = origin.first
        val originY = origin.second
        val context = event.context

        // for (Slot slot : handledscreen.getScreenHandler().slots) (ItemColor.java:119-128)
        for (slot in screen.menu.slots) {
            if (!slot.isActive || !slot.hasItem()) {
                continue
            }

            val tracked = SystemDlcItems.resolve(slot.item) ?: continue
            val group = itemGroups[tracked] ?: continue
            if (!group.enabled) {
                continue
            }

            val base = itemColors[tracked]?.get()?.argb ?: continue
            // "Мигать".equals(podsvetka) ? getIntByInt(k) : k (ItemColor.java:124)
            val argb = if (highlight == HighlightMode.BLINK) pulseAlpha(base) else base

            // drawcontext.fill(i + slot.x, j + slot.y, i + slot.x + 16, j + slot.y + 16, l)
            context.drawQuadXYWH(
                (originX + slot.x).toFloat(),
                (originY + slot.y).toFloat(),
                ITEM_RECT,
                ITEM_RECT,
                Color4b(argb),
            )
        }
    }

    /**
     * The container's top-left origin (leftPos/topPos). The source reads the equivalent
     * `HandledScreen` fields via Unsafe with a centred fallback (ItemColor.java:157-183); those
     * fields (`leftPos`, `topPos`, `imageWidth`, `imageHeight`) are all `protected` with no getter
     * on 26.1, so this reflects them the same way. If reflection fails (e.g. the runtime is not
     * Mojmap-named), it falls back to the source's centred `(width-176)/2, (height-166)/2`.
     */
    private fun containerOrigin(screen: AbstractContainerScreen<*>): Pair<Int, Int> {
        val left = readIntField(screen, LEFT_POS_FIELD)
        val top = readIntField(screen, TOP_POS_FIELD)
        if (left != null && top != null && !(left == 0 && top == 0)) {
            return left to top
        }

        var w = readIntField(screen, IMAGE_WIDTH_FIELD) ?: 0
        var h = readIntField(screen, IMAGE_HEIGHT_FIELD) ?: 0
        if (w == 0) {
            w = DEFAULT_IMAGE_WIDTH
        }
        if (h == 0) {
            h = DEFAULT_IMAGE_HEIGHT
        }
        return ((screen.width - w) / 2) to ((screen.height - h) / 2)
    }

    /**
     * `ItemColor.getIntByInt` (ItemColor.java:82-88) — modulate the alpha of [argb] by the 900 ms
     * cosine pulse `f2 = 0.35 + 0.65 * (0.5 - 0.5*cos(2pi * (ms%900)/900))`, keeping the RGB.
     */
    private fun pulseAlpha(argb: Int): Int {
        val f = (System.currentTimeMillis() % 900L) / 900.0F
        val f1 = 0.5F - 0.5F * cos(f * (PI * 2).toFloat())
        val f2 = 0.35F + 0.65F * f1
        val a = (((argb shr 24) and 0xFF) * f2).toInt().coerceIn(0, 255)
        return (argb and 0xFFFFFF) or (a shl 24)
    }

    /**
     * `getIntByIntInt(count, count2)` -> `MathUtil.getIntByFloat(count/count2)` (ItemColor.java:77-80,
     * MathUtil.java:252-295). Evenly-spaced 6-sector hue wheel, s=0.85, v=1, ALPHA 0x78 (the source's
     * `2013265920 == 0x78000000`). [ordinal] is the item index, [size] the item count (36).
     */
    private fun hueWheelColor(ordinal: Int, size: Int): Int {
        val value = if (size <= 1) 0.0F else ordinal.toFloat() / size
        return getIntByFloat(value)
    }

    /** `MathUtil.getIntByFloat` (MathUtil.java:252-295), ported verbatim. Returns 0x78-alpha ARGB. */
    @Suppress("MagicNumber")
    private fun getIntByFloat(value: Float): Int {
        val f = (value - floor(value)) * 6.0F
        val l = f.toInt()
        val f1 = f - l
        val f2 = 0.14999998F
        val f3 = 1.0F - 0.85F * f1
        val f4 = 1.0F - 0.85F * (1.0F - f1)
        val i: Int
        val j: Int
        val k: Int
        when (l) {
            0 -> {
                i = 255
                j = (f4 * 255.0F + 0.5F).toInt()
                k = (f2 * 255.0F + 0.5F).toInt()
            }
            1 -> {
                i = (f3 * 255.0F + 0.5F).toInt()
                j = 255
                k = (f2 * 255.0F + 0.5F).toInt()
            }
            2 -> {
                i = (f2 * 255.0F + 0.5F).toInt()
                j = 255
                k = (f4 * 255.0F + 0.5F).toInt()
            }
            3 -> {
                i = (f2 * 255.0F + 0.5F).toInt()
                j = (f3 * 255.0F + 0.5F).toInt()
                k = 255
            }
            4 -> {
                i = (f4 * 255.0F + 0.5F).toInt()
                j = (f2 * 255.0F + 0.5F).toInt()
                k = 255
            }
            else -> {
                i = 255
                j = (f2 * 255.0F + 0.5F).toInt()
                k = (f3 * 255.0F + 0.5F).toInt()
            }
        }
        return 2013265920 or (i shl 16) or (j shl 8) or k
    }

    private fun readIntField(screen: Any, field: Field?): Int? =
        field?.let { runCatching { it.getInt(screen) }.getOrNull() }

    private fun resolveField(name: String): Field? = runCatching {
        AbstractContainerScreen::class.java.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private const val ITEM_RECT = 16.0F
    private const val DEFAULT_IMAGE_WIDTH = 176
    private const val DEFAULT_IMAGE_HEIGHT = 166

    private val LEFT_POS_FIELD: Field? = resolveField("leftPos")
    private val TOP_POS_FIELD: Field? = resolveField("topPos")
    private val IMAGE_WIDTH_FIELD: Field? = resolveField("imageWidth")
    private val IMAGE_HEIGHT_FIELD: Field? = resolveField("imageHeight")
}
