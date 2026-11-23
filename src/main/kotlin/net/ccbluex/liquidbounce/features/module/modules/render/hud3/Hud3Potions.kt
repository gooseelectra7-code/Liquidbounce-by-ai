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
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.core.Holder
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * The potions stack — `<3:488>`, a 147x76 box at (170, 71) of the design frame.
 *
 * Structure, all of it read off the file: a header pill (`<3:400>`, icon + title) and one row per
 * effect, each row being **two** pills packed left with a gap of 5 — the name pill
 * (`<3:405>`: name, then the amplifier as a Roman numeral with a smaller `lvl` suffix) and the
 * timer pill (`<3:410>`: the countdown ring, then `mm:ss`). Rows are separated by 5 and each pill
 * is 22 tall with 5 of padding.
 *
 * The two pills of a row are packed **independently per row** — row 1 is 90 + 5 + 52 and row 2 is
 * 65 + 5 + 55, so the timer pills do not share a column. That is the design's own layout and it is
 * reproduced rather than tidied up.
 *
 * ## The ring
 *
 * This is the one place where the module deliberately departs from the file. The design draws the
 * timer as a 10x10 frame with `cornerRadius = 180` and a **linear** gradient on its 2 px stroke
 * (`<9:830>`: `GRADIENT_LINEAR[#5E8CFF@0.65 / #5E8CFF@0.15@0.65]`); the second row's ring
 * (`<9:825>`) is a plain solid stroke with no gradient at all, so the two rows do not even agree
 * on what the mark means. A linear gradient cuts a circle along a straight chord, which is why it
 * reads as lopsided, and it cannot express a countdown: two different remaining times can produce
 * the same picture and a full ring is indistinguishable from an empty one.
 *
 * [Hud3Shapes.progressRing] replaces it with what the mark is clearly reaching for — an angular
 * sweep from twelve o'clock, the remaining share solid and the spent share at the same 15 % the
 * gradient's second stop uses. Geometry, hue, position and the 2 px inside stroke are unchanged.
 *
 * Remaining time needs a total to divide by, and `MobEffectInstance` carries only what is left.
 * [totals] therefore remembers the longest duration seen for each effect and treats a duration
 * that goes **up** as a fresh application. An infinite effect draws a full ring.
 */
internal class Hud3Potions(parent: EventListener) :
    Hud3Panel(parent, "Potions", Hud3Anchor.TOP_LEFT, 170f, 71f) {

    private val title by text("Title", "Potions")
    private val showLevel by boolean("Level", true)
    private val showTimer by boolean("Timer", true)
    private val maxRows by int("MaxRows", 8, 1..16)

    /** Longest duration seen per effect, so a countdown has something to be a fraction of. */
    private val totals = HashMap<Holder<MobEffect>, Int>()

    private class Row(
        val effect: Holder<MobEffect>,
        val label: String,
        val level: String,
        val time: String,
        val fraction: Float,
        /** Inside the last [EXPIRING_TICKS] — the ring and the timer blink, as vanilla's icons do. */
        val expiring: Boolean,
        val leftWidth: Float,
        val rightWidth: Float,
    )

    /**
     * The rows on screen, keyed by effect. A new effect slides in, an expired one slides out, and a
     * re-applied one stays put while its ring refills.
     */
    private val rows = Hud3Presence<Holder<MobEffect>, Row>({ it.effect })

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val density = frameDensity
        rows.sync(if (enabled) measure(density) else emptyList(), dt)
        val alpha = fade(rows.hasPresent, dt)
        if (alpha <= 0.004f) {
            return
        }

        val ctx = event.context
        val headerWidth = Hud3Theme.PAD * 2f + Hud3Theme.ICON_12 + Hud3Theme.GAP +
            textWidth(title, Hud3Theme.PX_10, density)
        val extent = rows.layout(Hud3Theme.STACK_GAP + Hud3Theme.ROW_22, dt)
        var width = headerWidth
        for (entry in rows.all) {
            val right = entry.size.update(entry.value.rightWidth, dt)
            entry.amount.update(entry.value.fraction, dt)
            if (!entry.leaving) {
                width = maxOf(width, entry.value.leftWidth + if (right > 0f) Hud3Theme.GAP + right else 0f)
            }
        }
        val frame = frame(width, Hud3Theme.ROW_22 + extent, alpha)
        val glass = ModuleHud3.glass(ctx)

        ctx.pushFrame(frame)
        drawHeader(ctx, headerWidth, density, alpha, glass)
        val blink = blinkFactor()
        for (entry in rows.all) {
            val presence = entry.eased
            drawRow(
                ctx, entry.value, entry.size.value, entry.amount.value,
                slideDirection * Hud3Theme.SLIDE * (1f - presence),
                Hud3Theme.ROW_22 + Hud3Theme.STACK_GAP + entry.offset.value,
                density, alpha * presence, if (entry.value.expiring) blink else 1f, glass,
            )
        }
        ctx.popFrame()
    }

    /** The opacity of an expiring row's timer this frame: a slow breathe between 45 % and full. */
    private fun blinkFactor(): Float {
        if (!Hud3Motion.enabled) {
            return 1f
        }
        val wave = 0.5f + 0.5f * cos(Hud3Motion.seconds() * BLINK_HZ * 2f * PI.toFloat())
        return BLINK_FLOOR + (1f - BLINK_FLOOR) * wave
    }

    @Suppress("LongParameterList")
    private fun drawHeader(
        ctx: GuiGraphicsExtractor,
        headerWidth: Float,
        density: Float,
        alpha: Float,
        glass: TextureSetup?,
    ) {
        ctx.hud3Pill(0f, 0f, headerWidth, Hud3Theme.ROW_22, alpha, density, glass)
        Hud3Icons.draw(
            ctx, Hud3Icons.POTION, Hud3Theme.PAD,
            (Hud3Theme.ROW_22 - Hud3Theme.ICON_12) * 0.5f, Hud3Theme.ICON_12,
            scaled(Hud3Theme.ACCENT, alpha),
        )
        ctx.hud3Text(
            title, Hud3Theme.PAD + Hud3Theme.ICON_12 + Hud3Theme.GAP,
            textTopCentered(Hud3Theme.PX_10, density, 0f, Hud3Theme.ROW_22),
            Hud3Theme.PX_10, density, scaled(Hud3Theme.TEXT, alpha),
        )
    }

    @Suppress("LongParameterList")
    private fun drawRow(
        ctx: GuiGraphicsExtractor,
        row: Row,
        rightWidth: Float,
        fraction: Float,
        x: Float,
        rowY: Float,
        density: Float,
        alpha: Float,
        blink: Float,
        glass: TextureSetup?,
    ) {
        if (alpha <= 0.004f) {
            return
        }
        val text = scaled(Hud3Theme.TEXT, alpha)
        val labelTop = textTopCentered(Hud3Theme.PX_10, density, rowY, Hud3Theme.ROW_22)

        ctx.hud3Pill(x, rowY, row.leftWidth, Hud3Theme.ROW_22, alpha, density, glass)
        ctx.hud3Text(row.label, x + Hud3Theme.PAD, labelTop, Hud3Theme.PX_10, density, text)
        if (row.level.isNotEmpty()) {
            val levelX = x + Hud3Theme.PAD + textWidth(row.label, Hud3Theme.PX_10, density) + Hud3Theme.GAP
            ctx.hud3Text(row.level, levelX, labelTop, Hud3Theme.PX_10, density, text)
            ctx.hud3Text(
                LEVEL_SUFFIX,
                levelX + textWidth(row.level, Hud3Theme.PX_10, density) + LEVEL_GAP,
                textTopOnBaseline(
                    Hud3Theme.PX_8, density, textBaseline(Hud3Theme.PX_10, density, labelTop),
                ),
                Hud3Theme.PX_8, density, scaled(Hud3Theme.TEXT_DIM, alpha),
            )
        }

        if (rightWidth <= 0.5f || row.time.isEmpty()) {
            return
        }
        val rightX = x + row.leftWidth + Hud3Theme.GAP
        ctx.hud3Pill(rightX, rowY, rightWidth, Hud3Theme.ROW_22, alpha, density, glass)

        val slotX = rightX + Hud3Theme.PAD
        val slotY = rowY + (Hud3Theme.ROW_22 - Hud3Theme.ICON_12) * 0.5f
        ctx.hud3Shapes(
            slotX - 1f, slotY - 1f,
            slotX + Hud3Theme.ICON_12 + 1f, slotY + Hud3Theme.ICON_12 + 1f, density,
        ) {
            progressRing(
                slotX + Hud3Theme.ICON_12 * 0.5f, slotY + Hud3Theme.ICON_12 * 0.5f,
                RING_OUTER_RADIUS, RING_THICKNESS, fraction, scaled(Hud3Theme.ACCENT, alpha * blink),
            )
        }
        ctx.hud3Text(
            row.time, slotX + Hud3Theme.ICON_12 + Hud3Theme.GAP, labelTop,
            Hud3Theme.PX_10, density, scaled(Hud3Theme.TEXT, alpha * blink),
        )
    }

    /**
     * Ordered **longest name pill first**, which is the order the design's own stack is in
     * (`<3:405>` 90, `<3:415>` 65) and what the user asked for on 2026-09-21.
     *
     * Two things that reading turns on, and both are deliberate:
     *
     * Which effects are on screen is still decided by infinite-first then longest-remaining, and
     * only the display order is by length — the request was about order, and letting length pick
     * the survivors of a `take(maxRows)` would hide the effect about to run out in favour of the
     * one with a long name.
     *
     * The sort key is the NAME pill's width and deliberately not the whole row's: the timer text
     * loses a character on every digit boundary it crosses (`10:00` to `9:59`), so ordering by the
     * total would reshuffle the stack mid-countdown. Name plus level is stable for as long as the
     * effect lasts. Ties fall back to the label so the order is total.
     */
    private fun measure(density: Float): List<Row> {
        val effects = mc.player?.activeEffects
        if (effects.isNullOrEmpty()) {
            totals.clear()
            return emptyList()
        }
        totals.keys.retainAll(effects.mapTo(HashSet()) { it.effect })

        return effects
            .sortedWith(
                compareByDescending<MobEffectInstance> { it.isInfiniteDuration }
                    .thenByDescending { it.duration }
            )
            .take(maxRows)
            .map { instance -> row(instance, density) }
            .sortedWith(compareByDescending<Row> { it.leftWidth }.thenBy { it.label })
    }

    private fun row(instance: MobEffectInstance, density: Float): Row {
        val label = instance.effect.value().displayName.string
        val level = if (showLevel) roman(instance.amplifier + 1) else ""
        val time = if (showTimer) durationOf(instance) else ""

        var left = Hud3Theme.PAD * 2f + textWidth(label, Hud3Theme.PX_10, density)
        if (level.isNotEmpty()) {
            left += Hud3Theme.GAP + textWidth(level, Hud3Theme.PX_10, density) + LEVEL_GAP +
                textWidth(LEVEL_SUFFIX, Hud3Theme.PX_8, density)
        }
        val right = if (time.isEmpty()) {
            0f
        } else {
            Hud3Theme.PAD * 2f + Hud3Theme.ICON_12 + Hud3Theme.GAP +
                textWidth(time, Hud3Theme.PX_10, density)
        }
        val expiring = !instance.isInfiniteDuration && instance.duration <= EXPIRING_TICKS
        return Row(instance.effect, label, level, time, fractionOf(instance), expiring, left, right)
    }

    /**
     * Remaining share of the effect. The total is the longest duration this effect has been seen
     * with; a duration that rose since the last frame means it was re-applied, so the new value
     * becomes the total and the ring restarts full.
     */
    private fun fractionOf(instance: MobEffectInstance): Float {
        if (instance.isInfiniteDuration) {
            return 1f
        }
        val remaining = instance.duration.coerceAtLeast(0)
        val total = totals.merge(instance.effect, remaining) { previous, current ->
            if (current > previous) current else previous
        } ?: remaining
        return if (total <= 0) 0f else (remaining.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun durationOf(instance: MobEffectInstance): String {
        if (instance.isInfiniteDuration) {
            return INFINITE
        }
        val seconds = instance.duration / TICKS_PER_SECOND
        return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    /**
     * The amplifier as a Roman numeral, which is what the design shows (`IV`, `V`). Past
     * [ROMAN_LIMIT] the numeral stops being readable at 10 px, so it falls back to digits.
     */
    private fun roman(value: Int): String {
        if (value <= 0) {
            return ""
        }
        if (value > ROMAN_LIMIT) {
            return value.toString()
        }
        val builder = StringBuilder()
        var rest = value
        for (index in ROMAN_VALUES.indices) {
            while (rest >= ROMAN_VALUES[index]) {
                builder.append(ROMAN_SYMBOLS[index])
                rest -= ROMAN_VALUES[index]
            }
        }
        return builder.toString()
    }

    private companion object {
        const val TICKS_PER_SECOND = 20
        const val INFINITE = "--"

        /** Vanilla starts blinking an effect icon at 200 ticks left (`Gui.java:506`, `endsWithin(200)`). */
        const val EXPIRING_TICKS = 200

        /** The blink: 1.6 breaths a second, never below 45 %. */
        const val BLINK_HZ = 1.6f
        const val BLINK_FLOOR = 0.45f

        /** The smaller suffix after the level — `<3:409>`. */
        const val LEVEL_SUFFIX = "lvl"

        /** Gap between the level and its suffix — `<3:407>` itemSpacing. */
        const val LEVEL_GAP = 3f

        /** The ring is a 10x10 circle inside a 12 px slot, so its outer radius is 5 (`<9:830>`). */
        const val RING_OUTER_RADIUS = 5f

        /** `strokeWeight = 2`, `strokeAlign = INSIDE`, so the band grows inwards from radius 5. */
        const val RING_THICKNESS = 2f

        const val ROMAN_LIMIT = 39
        val ROMAN_VALUES = intArrayOf(10, 9, 5, 4, 1)
        val ROMAN_SYMBOLS = arrayOf("X", "IX", "V", "IV", "I")
    }
}
