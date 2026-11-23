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

package net.ccbluex.liquidbounce.render.engine.font.dynamic

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import it.unimi.dsi.fastutil.objects.ObjectImmutableList
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import net.ccbluex.liquidbounce.render.FontFace
import net.ccbluex.liquidbounce.render.engine.font.FontId
import net.ccbluex.liquidbounce.render.engine.font.FontGlyph
import net.ccbluex.liquidbounce.render.engine.font.FontStyle
import net.ccbluex.liquidbounce.render.engine.font.GlyphDescriptor
import net.ccbluex.liquidbounce.render.engine.font.GlyphIdentifier
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.render.uploadRect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class DynamicFontCacheManager(
    private val dynamicGlyphPage: DynamicGlyphPage,
    /**
     * Available fonts, sorted by priority
     */
    private val availableFonts: Collection<FontFace>
) {

    private val glyphPageLock = ReentrantLock()
    private val glyphPageDirtyFlag = AtomicBoolean(false)
    private val glyphPageChanges = ObjectArrayList<ChangeOnAtlas>()

    private val cacheData = ConcurrentHashMap<GlyphIdentifier, CharCacheData>()

    private val requests = ObjectOpenHashSet<GlyphIdentifier>()
    private val requestsLock = ReentrantLock()
    private val hasRequest = requestsLock.newCondition()

    @Volatile
    private var workerThread: Thread? = null

    fun requestGlyph(ch: Char, font: @FontStyle Int) {
        val glyphIdentifier = GlyphIdentifier(ch, font)
        val cacheObject = this.cacheData.computeIfAbsent(glyphIdentifier) { CharCacheData() }

        cacheObject.lastUsage.set(System.currentTimeMillis())

        if (cacheObject.cacheState.get() == UNCACHED) {
            // Notify font cache manager main thread
            this.requestsLock.withLock {
                requests.add(glyphIdentifier)

                hasRequest.signal()
            }
        }
    }

    fun update(): List<ChangeOnAtlas> {
        if (!this.glyphPageDirtyFlag.get()) {
            return emptyList()
        }

        return this.glyphPageLock.withLock {
            val changes = ObjectImmutableList(this.glyphPageChanges)
            this.glyphPageChanges.clear()
            val requiredUpdateCount = changes.count { !it.removed }

            if (requiredUpdateCount > 15) {
                this.dynamicGlyphPage.texture.upload()
            } else {
                for (change in changes) {
                    if (change.removed) {
                        continue
                    }

                    val bb = change.descriptor.renderInfo.atlasLocation?.pixelBoundingBox ?: continue

                    this.dynamicGlyphPage.texture.uploadRect(
                        mipLevel = 0,
                        x = bb.xMin.toInt(),
                        y = bb.yMin.toInt(),
                        width = (bb.xMax - bb.xMin).toInt(),
                        height = (bb.yMax - bb.yMin).toInt()
                    )
                }
            }

            this.glyphPageDirtyFlag.set(false)

            changes
        }
    }

    fun startThread() {
        check(this.workerThread == null) { "Dynamic font manager thread is already running" }

        this.workerThread = thread(name = "lb-dynamic-font-manager", isDaemon = true) {
            while (!Thread.interrupted()) {
                try {
                    threadMainLoop()
                } catch (_: InterruptedException) { // I hate everything about handling thread interrupts in java...
                    break
                } catch (e: Throwable) {
                    logger.error("Error on dynamic font manager thread", e)
                }
            }
        }
    }

    /**
     * Stops the worker so a discarded manager does not keep writing into a closed atlas texture.
     *
     * @see net.ccbluex.liquidbounce.render.engine.font.FontGlyphPageManager.unload
     */
    fun stopThread() {
        val thread = this.workerThread ?: return
        this.workerThread = null
        thread.interrupt()
    }

    private fun threadMainLoop() {
        val requestedChars = this.requestsLock.withLock {
            // Wait for stuff to happen. The predicate loop matters: requestGlyph() signals under
            // this lock, so a request that arrived while this thread was busy allocating found
            // nobody waiting and its signal was dropped. Without the re-check we would block on
            // an already-fired signal while `requests` is non-empty.
            while (this.requests.isEmpty()) {
                this.hasRequest.await()
            }

            val retrievedRequests = ObjectImmutableList(this.requests)

            this.requests.clear()

            retrievedRequests
        }

        val allocationList = createAllocationRequests(requestedChars)
        if (allocationList.isEmpty()) {
            return
        }

        val unsuccessfulAllocations = this.glyphPageLock.withLock {
            tryAllocations(allocationList)
        }

        if (unsuccessfulAllocations.isEmpty()) {
            return
        }

        // The atlas is full. Evict what has not been drawn recently and retry the failed glyphs
        // ONCE. Only what still does not fit afterwards is blocked from being asked for again —
        // blocking on the first failure would permanently hide characters that the eviction just
        // made room for.
        // TODO: Optimize (defragment) the atlas in this situation instead of only evicting.
        val stillUnsuccessful = this.glyphPageLock.withLock {
            freeSpace()
            tryAllocations(unsuccessfulAllocations)
        }

        stillUnsuccessful.forEach { dontRetryAllocationOf(GlyphIdentifier(it)) }
    }

    private fun dontRetryAllocationOf(it: GlyphIdentifier) {
        // The entry may have been evicted by freeSpace() in between; re-create it so the BLOCKED
        // state sticks and the glyph is not re-requested every frame.
        this.cacheData.computeIfAbsent(it) { CharCacheData() }.cacheState.set(BLOCKED)
    }

    /**
     * Evicts glyphs that have not been requested for [MAX_CACHE_TIME_MS].
     *
     * Callers **must** hold [glyphPageLock]: this mutates [dynamicGlyphPage] and appends to
     * [glyphPageChanges], which the render thread drains under the same lock in [update].
     *
     * Entries are removed from [cacheData] rather than reset to [UNCACHED] — keeping them made the
     * map grow by one entry per distinct (codepoint, style) ever seen, and never shrink.
     */
    private fun freeSpace() {
        val now = System.currentTimeMillis()
        val iterator = this.cacheData.entries.iterator()

        while (iterator.hasNext()) {
            val (glyphId, charCacheData) = iterator.next()
            if (now - charCacheData.lastUsage.get() <= MAX_CACHE_TIME_MS) {
                continue
            }

            if (charCacheData.cacheState.get() == CACHED) {
                val renderInfo = this.dynamicGlyphPage.free(glyphId.codepoint, glyphId.style)

                if (renderInfo != null) {
                    // Removals have to raise the dirty flag too: update() returns early while it is
                    // clear, so the change would sit in the list and FontGlyphPageManager would keep
                    // serving a descriptor pointing at a slot that was already handed back.
                    this.glyphPageDirtyFlag.set(true)
                    this.glyphPageChanges.add(
                        ChangeOnAtlas(
                            GlyphDescriptor(this.dynamicGlyphPage, renderInfo),
                            glyphId.style,
                            removed = true
                        )
                    )
                } else {
                    logger.warn("Character '${glyphId.codepoint}' was freed twice.")
                }
            }

            iterator.remove()
        }
    }

    /**
     * Tries the given allocations, returns all allocations that failed.
     */
    private fun tryAllocations(requests: Iterable<FontGlyph>): List<FontGlyph> {
        val unsuccessful = this.dynamicGlyphPage.tryAdd(requests)

        requests.forEach {
            if (it !in unsuccessful) {
                // computeIfAbsent, not !!: on the retry pass freeSpace() has just run and could in
                // principle have evicted the bookkeeping entry. A NPE here would only surface as a
                // log line on the worker thread and silently stop glyph caching for the session.
                this.cacheData.computeIfAbsent(GlyphIdentifier(it)) { CharCacheData() }
                    .cacheState.set(CACHED)

                val addedGlyph = this.dynamicGlyphPage.getGlyph(it.codepoint, it.font.style)!!

                this.glyphPageDirtyFlag.set(true)
                this.glyphPageChanges.add(
                    ChangeOnAtlas(
                        GlyphDescriptor(this.dynamicGlyphPage, addedGlyph),
                        it.font.style,
                        removed = false
                    )
                )
            }
        }

        return unsuccessful
    }

    private fun createAllocationRequests(requestedGlyphs: Iterable<GlyphIdentifier>): List<FontGlyph> {
        val requests = ObjectArrayList<FontGlyph>()

        for (requestedGlyph in requestedGlyphs) {
            val font = findFontForGlyph(requestedGlyph)

            // If we have no font which could draw the requested glyph there is no sense in trying it again.
            if (font == null) {
                dontRetryAllocationOf(requestedGlyph)

                continue
            }

            requests.add(FontGlyph(requestedGlyph.codepoint, font))
        }

        return requests
    }

    private fun findFontForGlyph(ch: GlyphIdentifier): FontId? {
        return this.availableFonts.firstNotNullOfOrNull { fontFace ->
            fontFace.style(ch.style)?.takeIf { it.awtFont.canDisplay(ch.codepoint) }
        }
    }

    class ChangeOnAtlas(
        @JvmField val descriptor: GlyphDescriptor,
        @JvmField val style: @FontStyle Int,
        @JvmField val removed: Boolean,
    )
}

private const val MAX_CACHE_TIME_MS = 30 * 1000L

private const val UNCACHED = 0
private const val CACHED = 1
private const val BLOCKED = 2

private class CharCacheData {
    /**
     * Possible values: [UNCACHED], [CACHED] and [BLOCKED]
     */
    val cacheState = AtomicInteger(UNCACHED)
    val lastUsage = AtomicLong(0L)
}
