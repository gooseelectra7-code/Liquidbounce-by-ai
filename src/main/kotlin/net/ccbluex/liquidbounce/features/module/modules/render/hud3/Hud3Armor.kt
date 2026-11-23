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
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

/**
 * The armour pill — `<3:485>`, an 89x26 fill box at (318, 225) of the design frame.
 *
 * Four 16 px item cells with a gap of 5 (`<3:425>`), and under each one a 2 px durability bar
 * flush with the pill's bottom edge (`<3:434>` .. `<3:437>`, at y = 24 of a 26 px card).
 *
 * The bars are the design's own progress construction: a linear gradient whose two stops sit at
 * the **same** position, i.e. a hard step from the solid hue to the same hue at 15 %. So
 * [Hud3Shapes.progressBar] reproduces it exactly, with no gradient involved.
 *
 * The hue is read as durability rather than decoration — the file's three filled samples are
 * green at 65 %, amber at 45 % and red at 25 %, which is consistent with nothing else. That is an
 * INFERENCE about intent, not something the file states; the thresholds are named below.
 *
 * The bar sits in the card's rounded bottom corners, which the design handles with `clipsContent`.
 * The outermost bars are therefore inset to the card's own boundary ([bottomEdgeInset]); without
 * it the first bar pokes a couple of pixels out of the corner, which reads as a rendering fault
 * rather than as the design.
 */
internal class Hud3Armor(parent: EventListener) :
    Hud3Panel(parent, "Armor", Hud3Anchor.TOP_LEFT, 318f, 225f) {

    private val showDurability by boolean("Durability", true)
    private val hideWhenEmpty by boolean("HideWhenEmpty", true)

    /**
     * One armour cell's animation state. The stack is kept after the slot empties, so the item has
     * something to shrink away with; a different item in the same slot restarts the pop.
     */
    private class Cell {
        var stack: ItemStack = ItemStack.EMPTY
        var progress = 0f
        var lastDamage = -1
        val durability = Hud3Smooth(Hud3Theme.PROGRESS_HALF_LIFE_MS)
        val hit = Hud3Pulse(Hud3Theme.PULSE_MS)
    }

    private val cells = Array(SLOT_ORDER.size) { Cell() }

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val ctx = event.context
        val density = frameDensity
        val player = mc.player

        var anyWorn = false
        for ((index, slot) in SLOT_ORDER.withIndex()) {
            val stack = player?.getItemBySlot(slot) ?: ItemStack.EMPTY
            anyWorn = anyWorn || !stack.isEmpty
            advance(cells[index], stack, dt)
        }
        val present = enabled && player != null && (!hideWhenEmpty || anyWorn)
        val alpha = fade(present, dt)
        if (alpha <= 0.004f) {
            return
        }

        val count = cells.size
        val width = Hud3Theme.PAD * 2f + count * Hud3Theme.ITEM_16 + (count - 1) * Hud3Theme.GAP
        val frame = frame(width, Hud3Theme.ROW_26, alpha)

        ctx.pushFrame(frame)
        ctx.hud3Pill(0f, 0f, width, Hud3Theme.ROW_26, alpha, density, ModuleHud3.glass(ctx))

        if (showDurability) {
            drawBars(ctx, width, density, alpha)
        }
        ctx.popFrame()

        val cellY = (Hud3Theme.ROW_26 - Hud3Theme.ITEM_16) * 0.5f
        for ((index, cell) in cells.withIndex()) {
            ctx.hud3Item(
                cell.stack, frame,
                Hud3Theme.PAD + index * (Hud3Theme.ITEM_16 + Hud3Theme.GAP), cellY,
                Hud3Theme.ITEM_16, Hud3Motion.easeOutBack(cell.progress),
            )
        }
    }

    /** Steps one cell towards [live]: pop in, pop out, follow the durability, flash on a hit. */
    private fun advance(cell: Cell, live: ItemStack, dt: Float) {
        if (live.isEmpty) {
            cell.progress = (cell.progress - Hud3Motion.step(Hud3Theme.EXIT_MS, dt)).coerceAtLeast(0f)
            if (cell.progress <= 0f) {
                cell.stack = ItemStack.EMPTY
                cell.durability.reset()
            }
            cell.lastDamage = -1
        } else {
            if (cell.stack.isEmpty || cell.stack.item !== live.item) {
                cell.progress = 0f
                cell.durability.reset()
                cell.lastDamage = -1
            }
            cell.stack = live
            cell.progress = (cell.progress + Hud3Motion.step(Hud3Theme.ENTER_MS, dt)).coerceAtMost(1f)
            if (cell.lastDamage >= 0 && live.damageValue > cell.lastDamage) {
                cell.hit.kick()
            }
            cell.lastDamage = live.damageValue
            cell.durability.update(durabilityOf(live), dt)
        }
        cell.hit.update(dt)
    }

    /**
     * The durability bars, one per occupied cell, along the card's bottom edge. The outermost ones
     * are clipped to the card's rounded corners; see [bottomEdgeInset]. A bar fades with its item,
     * eases to a new durability and flashes towards white for a moment when the piece takes a hit.
     */
    private fun drawBars(
        ctx: GuiGraphicsExtractor,
        width: Float,
        density: Float,
        alpha: Float,
    ) {
        val barY = Hud3Theme.ROW_26 - Hud3Theme.BAR_HEIGHT
        val inset = bottomEdgeInset(Hud3Theme.RADIUS, Hud3Theme.BAR_HEIGHT)
        ctx.hud3Shapes(0f, barY, width, Hud3Theme.ROW_26, density) {
            for ((index, cell) in cells.withIndex()) {
                if (cell.stack.isEmpty || cell.progress <= 0f) {
                    continue
                }
                val rawX = Hud3Theme.PAD + index * (Hud3Theme.ITEM_16 + Hud3Theme.GAP)
                val left = maxOf(rawX, inset)
                val right = minOf(rawX + Hud3Theme.ITEM_16, width - inset)
                if (right > left) {
                    val fraction = cell.durability.value
                    val base = colorOf(cell.stack, fraction, alpha * Hud3Motion.easeOut(cell.progress))
                    progressBar(
                        left, barY, right - left, Hud3Theme.BAR_HEIGHT,
                        Hud3Theme.BAR_HEIGHT * 0.5f, fraction,
                        lerpArgb(base, base or 0xFFFFFF, cell.hit.value * HIT_FLASH),
                    )
                }
            }
        }
    }

    /** Remaining durability as a `0..1` share; an item that cannot be damaged reads as full. */
    private fun durabilityOf(stack: ItemStack): Float {
        if (!stack.isDamageableItem || stack.maxDamage <= 0) {
            return 1f
        }
        return ((stack.maxDamage - stack.damageValue).toFloat() / stack.maxDamage).coerceIn(0f, 1f)
    }

    /**
     * Bar hue by remaining durability.
     *
     * An **undamaged** item gets the flat neutral bar the design's fourth cell carries (`<3:437>`:
     * `#F7F3F3` at 45 %, full width), and only a damaged one is coloured. That reading is what
     * reconciles all four of the file's samples at once — 100 % neutral, 65 % green, 45 % amber,
     * 25 % red — where "neutral means no durability at all" would leave the helmet unexplained,
     * since armour is always damageable.
     */
    private fun colorOf(stack: ItemStack, fraction: Float, alpha: Float): Int {
        if (!stack.isDamageableItem || stack.maxDamage <= 0 || stack.damageValue <= 0) {
            return scaled(Hud3Theme.TEXT_DIM, alpha)
        }
        val hue = when {
            fraction >= HEALTHY -> Hud3Theme.OK
            fraction >= WORN -> Hud3Theme.WARN
            else -> Hud3Theme.FAIL
        }
        return scaled(hue, alpha)
    }

    private companion object {
        /**
         * Boots first, helmet last — read off the design's own render, where the four cells are
         * boots, leggings, chestplate, helmet from left to right. Not the vanilla HUD's order.
         */
        val SLOT_ORDER = listOf(
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD,
        )

        /** Above this share of durability the bar is green; the file's green sample is 0.65. */
        const val HEALTHY = 0.5f

        /** Above this it is amber; the file's amber sample is 0.45 and its red one 0.25. */
        const val WORN = 0.3f

        /** How far towards white a bar flashes when its piece takes damage. */
        const val HIT_FLASH = 0.7f
    }
}
