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

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import kotlin.math.abs
import kotlin.math.pow

/**
 * The `Animations` group of the Hud3 look.
 *
 * Off means every transition lands on the frame it starts on — the layout is then exactly what it
 * was before the panels were animated. [speed] divides every duration and half-life alike, so the
 * choreography keeps its proportions at any speed.
 */
internal class Hud3Animations(parent: EventListener) : ToggleableValueGroup(parent, "Animations", true) {
    val speed by float("Speed", 1f, 0.25f..3f)
}

/**
 * Timing shared by every Hud3 animation. All of it is frame-rate independent: a transition is a
 * linear progress advanced by the frame time, and a follow is an exponential approach expressed as
 * a half-life, so neither depends on how many frames a second the game renders.
 */
internal object Hud3Motion {

    private val group: Hud3Animations get() = ModuleHud3.animations

    /** Whether animations are on at all; off makes every transition instant. */
    val enabled: Boolean get() = group.enabled

    private val speed: Float get() = group.speed.coerceAtLeast(MIN_SPEED)

    /**
     * How far a linear transition of [durationMs] advances in [dt] seconds, as a share of the whole
     * transition. `1` when animations are off, so the transition completes in one step.
     */
    fun step(durationMs: Float, dt: Float): Float {
        if (!enabled || durationMs <= 0f) {
            return 1f
        }
        return (dt * 1000f * speed / durationMs).coerceAtLeast(0f)
    }

    /**
     * The share of the remaining distance an exponential follow covers in [dt] seconds, given that
     * it halves the distance every [halfLifeMs]. `1` when animations are off.
     */
    fun follow(halfLifeMs: Float, dt: Float): Float {
        if (!enabled || halfLifeMs <= 0f) {
            return 1f
        }
        if (dt <= 0f) {
            return 0f
        }
        val halfLives = dt * 1000f * speed / halfLifeMs
        return (1f - 0.5f.pow(halfLives)).coerceIn(0f, 1f)
    }

    /** Monotonic time in seconds, for the few effects that are periodic rather than transitions. */
    fun seconds(): Float = (System.nanoTime() / 1_000_000_000.0 % PERIOD_WRAP_SECONDS).toFloat()

    /** `easeOutCubic` — enter transitions, and exits run it backwards (so they accelerate away). */
    fun easeOut(t: Float): Float = Hud3Theme.easeOutCubic(t)

    /**
     * `easeOutBack` — a small overshoot past 1 before settling, used for the item "pop". The
     * overshoot is what makes an item read as arriving rather than merely fading in.
     */
    fun easeOutBack(t: Float): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        return 1f + BACK_C3 * x * x * x + BACK_C1 * x * x
    }

    private const val MIN_SPEED = 0.05f
    private const val PERIOD_WRAP_SECONDS = 3600.0
    private const val BACK_C1 = 1.70158f
    private const val BACK_C3 = BACK_C1 + 1f
}

/**
 * A number that eases towards its target every frame.
 *
 * The first [update] lands exactly on the target, so nothing animates in from zero unless the
 * caller asks for it with [snap]. Once within [SETTLE] of the target it lands exactly, so a value
 * at rest is bit-for-bit the target and does not keep the pixel grid shimmering.
 */
internal class Hud3Smooth(private val halfLifeMs: Float) {

    private var current = Float.NaN

    /** The current value; `0` before the first [update]. */
    val value: Float get() = if (current.isNaN()) 0f else current

    fun update(target: Float, dt: Float): Float {
        current = if (current.isNaN()) {
            target
        } else {
            val next = current + (target - current) * Hud3Motion.follow(halfLifeMs, dt)
            if (abs(target - next) < SETTLE) target else next
        }
        return current
    }

    /** Jumps straight to [value] — the start point of an animation that should play from there. */
    fun snap(value: Float) {
        current = value
    }

    /** Forgets the value, so the next [update] lands on its target without animating. */
    fun reset() {
        current = Float.NaN
    }

    private companion object {
        const val SETTLE = 0.001f
    }
}

/**
 * A keyed list whose items animate in and out instead of popping.
 *
 * Every frame the caller hands over the items it would show, in display order. An item that is new
 * starts its enter transition; one that is gone is kept, marked [Entry.leaving], and plays its exit
 * before it is dropped — at the place it had, so the rows around it close the gap smoothly rather
 * than jumping. An item that comes back while leaving reverses its exit from where it is.
 *
 * Each entry carries its own [Entry.offset] and [Entry.size] follows, so the stacks built on top of
 * this also animate reorders and width changes, not just membership.
 */
internal class Hud3Presence<K : Any, V : Any>(
    private val keyOf: (V) -> K,
    private val enterMs: Float = Hud3Theme.ENTER_MS,
    private val exitMs: Float = Hud3Theme.EXIT_MS,
) {

    inner class Entry(val key: K, value: V) {
        /** The item as of the last frame it was present — what a leaving entry keeps drawing. */
        var value: V = value
            internal set

        /** Linear 0..1 progress of the enter / exit transition. */
        var progress = 0f
            internal set

        var leaving = false
            internal set

        /** Position along the stack axis, in the panel's local units. */
        val offset = Hud3Smooth(Hud3Theme.LAYOUT_HALF_LIFE_MS)

        /** Size across the stack axis (a pill's width, a column's content), in local units. */
        val size = Hud3Smooth(Hud3Theme.SIZE_HALF_LIFE_MS)

        /** A progress value the entry shows (a ring, a bar), eased towards the live one. */
        val amount = Hud3Smooth(Hud3Theme.PROGRESS_HALF_LIFE_MS)

        /** A flash the entry can be kicked into when its value changes. */
        val pulse = Hud3Pulse(Hud3Theme.PULSE_MS)

        /** Eased presence — the number every draw scales its alpha and its slide by. */
        val eased: Float get() = Hud3Motion.easeOut(progress)
    }

    private val entries = ArrayList<Entry>()

    /** Everything on screen this frame, present and leaving, in display order. */
    val all: List<Entry> get() = entries

    /** Whether at least one entry is present rather than on its way out. */
    val hasPresent: Boolean get() = entries.any { !it.leaving }

    /**
     * Merges [items] into the list and advances every transition by [dt] seconds.
     *
     * A leaving entry stays right after the nearest entry that preceded it in the old order and is
     * still present, which is what keeps it in place while it fades.
     */
    fun sync(items: List<V>, dt: Float) {
        val incoming = LinkedHashMap<K, V>(items.size * 2)
        for (item in items) {
            incoming.putIfAbsent(keyOf(item), item)
        }

        val byKey = HashMap<K, Entry>(entries.size * 2)
        val trailing = HashMap<K?, MutableList<Entry>>()
        var anchor: K? = null
        for (entry in entries) {
            byKey[entry.key] = entry
            if (entry.key in incoming) {
                anchor = entry.key
            } else {
                entry.leaving = true
                trailing.getOrPut(anchor) { ArrayList(2) } += entry
            }
        }

        val order = ArrayList<Entry>(incoming.size + entries.size)
        trailing[null]?.let { order += it }
        for ((key, value) in incoming) {
            val entry = byKey[key] ?: Entry(key, value)
            entry.value = value
            entry.leaving = false
            order += entry
            trailing[key]?.let { order += it }
        }

        val enter = Hud3Motion.step(enterMs, dt)
        val exit = Hud3Motion.step(exitMs, dt)
        for (entry in order) {
            entry.progress = if (entry.leaving) {
                (entry.progress - exit).coerceAtLeast(0f)
            } else {
                (entry.progress + enter).coerceAtMost(1f)
            }
        }
        order.removeAll { it.leaving && it.progress <= 0f }

        entries.clear()
        entries.addAll(order)
    }

    /**
     * Stacks the entries along one axis, [pitch] apart (a row plus its gap), and returns the total
     * extent. Each entry claims its pitch only in proportion to its presence, so a row that arrives
     * pushes the ones after it away gradually and a leaving one lets them close the gap the same way;
     * on top of that every entry follows its target place, which is what animates a reorder.
     */
    fun layout(pitch: Float, dt: Float): Float {
        var cursor = 0f
        for (entry in entries) {
            entry.offset.update(cursor, dt)
            cursor += pitch * entry.eased
        }
        return cursor
    }

    fun clear() {
        entries.clear()
    }
}

/**
 * A 0..1 pulse that is kicked to full and decays over [durationMs] — the flash on a count that
 * changed or on armour that took a hit.
 */
internal class Hud3Pulse(private val durationMs: Float) {

    /** Linear remaining share of the pulse. */
    private var remaining = 0f

    /** Eased strength of the pulse this frame. */
    val value: Float get() = remaining * remaining

    fun kick() {
        if (Hud3Motion.enabled) {
            remaining = 1f
        }
    }

    fun update(dt: Float): Float {
        remaining = (remaining - Hud3Motion.step(durationMs, dt)).coerceAtLeast(0f)
        return value
    }
}
