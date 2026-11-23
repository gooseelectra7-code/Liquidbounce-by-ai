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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.systemdlc.widget

import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Animation
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim.Interpolation

/**
 * An entry that a [ScrollAnimator] animates. Ported from the source `client.api.ListEntry`: each
 * entry owns a rate-based [Animation] (its 0..1 visibility) and reports an [itemHeight].
 */
interface ListEntry {
    /** This entry's rate-based visibility animation (source `animation()`). */
    fun animation(): Animation

    /** This entry's laid-out height in GUI units (source `itemHeight()`). */
    fun itemHeight(): Double
}

/**
 * An order-preserving list of [ListEntry] items each with an independent 0..1 visibility animation
 * that drives insert/remove reveals AND the running layout — ported from
 * `client/data/ScrollAnimator.java`.
 *
 * Every entry's height and the inter-entry [gap] are weighted by that entry's current visibility
 * `v = animation().eased()`, so a fading entry smoothly collapses the list:
 *  - [totalHeight] = `Σ height_i·v_i + Σ gap·v_i` (for i > 0) — source `getFloat`, ScrollAnimator.java:29-44.
 *  - [layout] assigns each entry a running Y via `Animation.setTrackTarget` — source `setFloat`, :80-90.
 *  - [tick] integrates every entry's animation by an internal per-frame delta and prunes entries
 *    that have finished fading out — source `update`, :225-236.
 *
 * The "animator constant" the source exposes is the inter-entry [gap] (source ctor `value2`); the
 * finished/idle threshold is `0.001` (source `value`, used by [isAnimating]). There is no spring —
 * the motion is the rate-based [Animation] integrator from the C0 foundation.
 *
 * @param V the entry type
 * @param gap inter-entry spacing in GUI units, weighted by visibility (source `value2`)
 */
class ScrollAnimator<V : ListEntry>(private val gap: Float) {

    /** Insertion-ordered entries keyed by the model object they were built from. */
    private val entries = LinkedHashMap<Any, V>()

    /** Reused scratch set for membership tests (source `hashSet`). */
    private val scratchKeys = HashSet<Any>()

    /** Internal per-frame delta clock used by [tick] (source `interpolation`). */
    private val interpolation = Interpolation()

    /** Once-per-frame gate stamp (source `time`). */
    private var frameStamp: Long = -1L

    /** When set, the next [layout] snaps each entry's track to its target (source `flag`). */
    private var snapRequested: Boolean = false

    /** Request that the next [layout] snap track values to their targets (source `setFlag`). */
    fun requestSnap() {
        snapRequested = true
    }

    /** The entries in order (source `getCollection`). */
    fun entries(): Collection<V> = entries.values

    /** True if the list is empty (source `check2`). */
    fun isEmpty(): Boolean = entries.isEmpty()

    /** True if any entry is still animating (`progress > 0.001`) (source `check`, ScrollAnimator.java:66-74). */
    fun isAnimating(): Boolean = entries.values.any { it.animation().progress > IDLE_THRESHOLD }

    /**
     * Once-per-frame gate: returns true the first time it is called with a given [frame] and false
     * on repeats within the same frame (source `isLong`, ScrollAnimator.java:163-170).
     */
    fun markFrame(frame: Long): Boolean {
        if (frameStamp == frame) {
            return false
        }
        frameStamp = frame
        return true
    }

    /** Visibility-weighted total height using the entry item heights (source `getFloat`). */
    fun totalHeight(): Float = accumulateHeight { it.itemHeight() }

    /** Visibility-weighted total height using a custom per-entry height (source `getFloatByToDoubleFunction2`). */
    fun totalHeightBy(heightOf: (V) -> Double): Float = accumulateHeight(heightOf)

    private fun accumulateHeight(heightOf: (V) -> Double): Float {
        var running = 0f
        var first = true
        for (entry in entries.values) {
            val visibility = entry.animation().eased()
            if (!first) {
                running += gap * visibility
            }
            first = false
            running += (heightOf(entry) * visibility).toFloat()
        }
        return running
    }

    /**
     * Lay the entries out top-to-bottom starting at [start], writing each entry's running Y into its
     * animation track (`setTrackTarget`), then snap tracks if [requestSnap] was called (source
     * `setFloat`, ScrollAnimator.java:80-90).
     */
    fun layout(start: Float) {
        layoutBy(start) { it.itemHeight() }
        if (snapRequested) {
            for (entry in entries.values) {
                val animation = entry.animation()
                animation.setValueImmediate(animation.valueTarget)
            }
            snapRequested = false
        }
    }

    /** Lay out with a custom per-entry height, no snap handling (source `onFloatToDoubleFunction2`). */
    fun layoutBy(start: Float, heightOf: (V) -> Double) {
        var running = start
        var first = true
        for (entry in entries.values) {
            val visibility = entry.animation().eased()
            if (!first) {
                running += gap * visibility
            }
            first = false
            entry.animation().setTrackTarget(running)
            running += (heightOf(entry) * visibility).toFloat()
        }
    }

    /**
     * Integrate every entry's visibility animation by the internal per-frame delta and remove entries
     * that have fully faded out (source `update`, ScrollAnimator.java:225-236).
     */
    fun tick() {
        val delta = interpolation.deltaSeconds()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val animation = iterator.next().value.animation()
            animation.integrate(delta)
            if (!animation.forward && animation.isIdle()) {
                iterator.remove()
            }
        }
    }

    /**
     * Diff [items] against the current entries: reveal newcomers (built via [factory]) and hide the
     * departed, staggering each transition by `index * stagger` seconds (source `onFloatListFunction`,
     * ScrollAnimator.java:123-156). Entries still present are simply set visible.
     */
    fun <K : Any> updateEntries(items: List<K>, stagger: Float, factory: (K) -> V?) {
        val present = snapshotKeys(items)

        var appearIndex = 0
        for (obj in items) {
            val existing = entries[obj]
            if (existing != null) {
                existing.animation().setDirection(true)
            } else {
                val created = factory(obj) ?: continue
                applyDirection(created.animation(), forward = true, index = appearIndex, stagger = stagger)
                entries[obj] = created
                appearIndex++
            }
        }

        var departIndex = 0
        for ((key, entry) in entries) {
            if (key !in present) {
                val animation = entry.animation()
                if (animation.forward) {
                    applyDirection(animation, forward = false, index = departIndex, stagger = stagger)
                    departIndex++
                }
            }
        }
    }

    /** [updateEntries] with no stagger (source `onListFunction`, ScrollAnimator.java:158-161). */
    fun <K : Any> updateEntries(items: List<K>, factory: (K) -> V?) = updateEntries(items, 0f, factory)

    /**
     * Reorder the retained entries to match [items] while keeping departing (not-in-[items]) entries
     * in their relative slots (source `onList`, ScrollAnimator.java:172-207). A no-op if already
     * ordered.
     */
    fun reorder(items: List<Any>) {
        val present = snapshotKeys(items)
        if (isOrdered(present, items)) {
            return
        }
        val reordered = LinkedHashMap<Any, V>(entries.size)
        val iterator = items.iterator()
        for ((key, value) in entries) {
            if (key !in present) {
                reordered[key] = value
            } else {
                while (iterator.hasNext()) {
                    val next = iterator.next()
                    val entry = entries[next]
                    if (entry != null) {
                        reordered[next] = entry
                        if (next == key) {
                            break
                        }
                    }
                }
            }
        }
        while (iterator.hasNext()) {
            val next = iterator.next()
            val entry = entries[next]
            if (entry != null) {
                reordered.putIfAbsent(next, entry)
            }
        }
        entries.clear()
        entries.putAll(reordered)
    }

    private fun applyDirection(animation: Animation, forward: Boolean, index: Int, stagger: Float) {
        if (stagger > 0f) {
            animation.setDirection(index * stagger, forward)
        } else {
            animation.setDirection(forward)
        }
    }

    private fun snapshotKeys(items: List<Any>): Set<Any> {
        scratchKeys.clear()
        scratchKeys.addAll(items)
        return scratchKeys
    }

    private fun isOrdered(present: Set<Any>, items: List<Any>): Boolean {
        var index = 0
        for (key in entries.keys) {
            if (key in present) {
                if (index >= items.size || key != items[index]) {
                    return false
                }
                index++
            }
        }
        return index == items.size
    }

    private companion object {
        /** Finished/idle progress threshold (source `value = 0.001`, ScrollAnimator.java:17). */
        const val IDLE_THRESHOLD = 0.001f
    }
}
