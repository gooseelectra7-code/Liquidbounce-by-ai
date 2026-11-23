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

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.lerpArgb
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The item counter — `<3:487>`, a 72x47 fill box at (325, 291) of the design frame.
 *
 * A row of columns (`<3:439>`, gap 7), each a 16 px item cell with its count centred underneath in
 * 12 px at 45 % (`<3:443>`). The card is 47 tall because the design stacks 5 + 16 + 7 + 14 + 5,
 * and that number is used verbatim: the count sits in the 14 px band at y = 28.
 *
 * The three items the file shows — totem of undying, golden apple, crossbow — are the default of
 * [items], not a hard-coded list, so the panel is a real counter rather than a picture of one.
 * Counts are summed over the hotbar, the main inventory and the offhand.
 */
internal class Hud3ItemList(parent: EventListener) :
    Hud3Panel(parent, "ItemList", Hud3Anchor.TOP_LEFT, 325f, 291f) {

    private val items by itemsChoose(
        "Items",
        mutableListOf(Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE, Items.CROSSBOW),
    )

    /** An item the player does not carry is dropped rather than shown as a permanent `0`. */
    private val hideMissing by boolean("HideMissing", true)

    private class Column(val item: Item, val stack: ItemStack, val label: String, val width: Float)

    /**
     * The columns on screen, keyed by item. A picked-up item opens a column in place, a used-up
     * one closes it, and a count that changes flashes: accent when it grows, red when it drops.
     */
    private val columns = Hud3Presence<Item, Column>({ it.item })

    /** Last count seen per item, so a change can be told from a first sighting. */
    private val lastCounts = HashMap<Item, Int>()

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val ctx = event.context
        val density = frameDensity

        columns.sync(if (enabled) collectColumns(density) else emptyList(), dt)
        val alpha = fade(columns.hasPresent, dt)
        if (alpha <= 0.004f) {
            return
        }

        val pitch = Hud3Theme.ITEM_16 + COLUMN_GAP
        val content = (columns.layout(pitch, dt) - COLUMN_GAP).coerceAtLeast(0f)
        val width = Hud3Theme.PAD * 2f + content
        val frame = frame(width, HEIGHT, alpha)

        ctx.pushFrame(frame)
        ctx.hud3Pill(0f, 0f, width, HEIGHT, alpha, density, ModuleHud3.glass(ctx))

        val countTop = textTopCentered(Hud3Theme.PX_12, density, COUNT_Y, COUNT_BAND)
        for (entry in columns.all) {
            val column = entry.value
            val flash = entry.pulse.update(dt)
            val presence = entry.eased
            if (presence <= 0.004f) {
                continue
            }
            val centerX = Hud3Theme.PAD + entry.offset.value + Hud3Theme.ITEM_16 * 0.5f
            val tone = lerpArgb(Hud3Theme.TEXT_DIM, entry.flashColor, flash)
            ctx.hud3Text(
                column.label, centerX - column.width * 0.5f, countTop + DROP * (1f - presence),
                Hud3Theme.PX_12, density, scaled(tone, alpha * presence),
            )
        }
        ctx.popFrame()

        for (entry in columns.all) {
            ctx.hud3Item(
                entry.value.stack, frame, Hud3Theme.PAD + entry.offset.value, Hud3Theme.PAD,
                Hud3Theme.ITEM_16, Hud3Motion.easeOutBack(entry.progress),
            )
        }
    }

    /** The colour a column's count flashes in, decided by which way it last changed. */
    private val flashColors = HashMap<Item, Int>()

    private val Hud3Presence<Item, Column>.Entry.flashColor: Int
        get() = flashColors[key] ?: Hud3Theme.ACCENT

    private fun collectColumns(density: Float): List<Column> {
        if (mc.player == null || items.isEmpty()) {
            lastCounts.clear()
            return emptyList()
        }
        val result = ArrayList<Column>(items.size)
        for (item in items) {
            val count = countOf(item)
            val previous = lastCounts.put(item, count)
            if (previous != null && previous != count) {
                flashColors[item] = if (count > previous) Hud3Theme.ACCENT else Hud3Theme.FAIL
                columns.all.firstOrNull { it.key === item }?.pulse?.kick()
            }
            if (count == 0 && hideMissing) {
                continue
            }
            val label = count.toString()
            result += Column(
                item = item,
                stack = ItemStack(item),
                label = label,
                width = textWidth(label, Hud3Theme.PX_12, density),
            )
        }
        val chosen = items.toSet()
        lastCounts.keys.retainAll(chosen)
        flashColors.keys.retainAll(chosen)
        return result
    }

    /** Total carried amount of [item] across the hotbar, the inventory and the offhand. */
    private fun countOf(item: Item): Int {
        var total = 0
        for (slot in Slots.HotbarAndInventory) {
            val stack = slot.itemStack
            if (!stack.isEmpty && stack.item === item) {
                total += stack.count
            }
        }
        val offhand = mc.player?.offhandItem
        if (offhand != null && !offhand.isEmpty && offhand.item === item) {
            total += offhand.count
        }
        return total
    }

    private companion object {
        /** 5 + 16 + 7 + 14 + 5, as the design stacks it. */
        const val HEIGHT = 47f

        /** Gap between the columns — `<3:439>` itemSpacing. */
        const val COLUMN_GAP = 7f

        /** Top of the count's line box — `<3:443>`. */
        const val COUNT_Y = 28f

        /** Height of the count's line box at 12 px. */
        const val COUNT_BAND = 14f

        /** How far a count drops in from while its column opens, in native pixels. */
        const val DROP = 4f
    }
}
