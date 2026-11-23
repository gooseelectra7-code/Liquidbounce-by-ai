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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleFakeLag
import net.ccbluex.liquidbounce.features.module.modules.combat.backtrack.ModuleBacktrack
import net.ccbluex.liquidbounce.features.module.modules.exploit.ModulePingSpoof
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.READ_FINAL_STATE
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket

/**
 * PerfLog module
 *
 * Writes a CSV of what the client is actually doing, one row per [interval], so a stutter complaint
 * can be turned into numbers instead of impressions.
 *
 * It is built around the fact that **an FPS counter cannot show stutter**. A counter averages over a
 * second, so one 40 ms frame among sixty 15 ms frames leaves the number at ~60 while the eye sees
 * every hitch. This logs the frame-time *distribution* — p50/p95/p99/max — which is where that hitch
 * actually shows up, and pairs it with the two things that usually cause it:
 *
 * - **GC.** `gcCount`/`gcMs` come from the JVM's own collector beans, so a row with a frame-time
 *   spike and a GC in the same second is a direct answer rather than a guess. `heapUsedMb` next to
 *   them shows whether the collections are being driven by allocation rate (fast sawtooth) or by a
 *   heap that keeps creeping up (retention).
 * - **Module work per tick.** `tickHandlersMs` measures the whole `GameTickEvent` dispatch, by
 *   sitting at [FIRST_PRIORITY] and [READ_FINAL_STATE] — the first and last handler positions — and
 *   subtracting. Everything every enabled module does on tick is inside that window, so if the
 *   client's own logic is the problem this column carries it.
 *
 * `worldAgeS` and the row written on [WorldChangeEvent] exist for the specific case where things
 * degrade over time and a rejoin clears them: line up the spikes against how long the world has been
 * loaded, and against `entities`/`heapUsedMb`, and a leak separates from a hot loop immediately —
 * a hot loop costs the same in the first minute as in the twentieth.
 *
 * The log is plain CSV in `<config>/perf-log/`, one file per enable, so two runs never mix.
 */
@Suppress("TooManyFunctions")
object ModulePerfLog : ClientModule("PerfLog", ModuleCategories.MISC) {

    /** Seconds per row. Shorter catches individual hitches, longer keeps the file readable. */
    private val interval by int("Interval", 1, 1..30, "s")

    /** Also print each row to chat, for watching live without alt-tabbing to the file. */
    private val toChat by boolean("Chat", false)

    /**
     * Adds the network columns — for the case where the renderer is provably fine but the game still
     * feels like it is running at half rate. Nothing here speeds anything up; a client cannot make a
     * server faster, and Minecraft already disables Nagle itself (`Connection$1.initChannel` sets
     * `TCP_NODELAY`), so there is no transport-level knob left to turn. What these columns do is
     * tell you whether "lag" is even a frame-rate phenomenon:
     *
     * - `teleports` counts incoming position corrections. Every one is a rubber-band, and a handful
     *   per second feels EXACTLY like a framerate collapse while the frame columns stay flat.
     * - `maxSilenceMs` is the longest the server went without sending anything in the window. A
     *   server tick stall shows up here and nowhere else.
     * - `pingMs`, `pktIn`, `pktOut` for context.
     * - `lagModules` lists the enabled modules that deliberately hold or delay packets. Backtrack,
     *   FakeLag, PingSpoof and Blink all make the game feel laggy BY DESIGN — if one of them is on,
     *   check that before blaming anything else.
     */
    private object Network : ToggleableValueGroup(this, "Network", true)

    /**
     * The JVM-side columns, for when the frame numbers say "hitch" but not why.
     *
     * - `allocMbS` and `topAlloc` answer where the garbage comes from. Java can report allocation
     *   per THREAD, so this names the worst offender instead of leaving "62 MB/s from somewhere" —
     *   render thread, netty, or a worker belonging to another mod are three very different fixes.
     * - `gcYoung`/`gcOld` split the collections. Young collections are cheap and expected; an old
     *   generation collection is the one that stops the world long enough to be seen, so a rising
     *   old count is a different problem from a fast young sawtooth.
     * - `tickGapMaxMs` is the longest gap between client ticks. Ticks are 50 ms apart by definition,
     *   so anything much larger is the CLIENT thread stalling — which is a separate failure from the
     *   render thread stalling, and the frame columns cannot tell them apart.
     * - `chunks` for context, since chunk churn is a classic allocation source.
     */
    private object Deep : ToggleableValueGroup(this, "JVM", true)

    init {
        tree(Network)
        tree(Deep)
    }

    private val folder: File = ConfigSystem.rootFolder.resolve("perf-log").apply { mkdirs() }

    private var writer: java.io.BufferedWriter? = null

    // ── Sampling state ───────────────────────────────────────────────────────

    /** Frame durations in microseconds for the current window; sorted at flush for percentiles. */
    private val frameTimes = ArrayList<Long>(4096)
    private var lastFrameNanos = 0L

    private var tickHandlerNanos = 0L
    private var tickStartNanos = 0L
    private var ticks = 0

    private var windowStartMs = 0L
    private var worldLoadedMs = 0L

    private var lastGcCount = 0L
    private var lastGcMs = 0L

    // Written from the netty thread, read on flush — hence atomics rather than plain ints.
    private val packetsIn = AtomicInteger()
    private val packetsOut = AtomicInteger()
    private val teleports = AtomicInteger()
    private val lastIncomingNanos = AtomicLong()
    private val maxSilenceNanos = AtomicLong()

    /**
     * Server TPS, measured exactly rather than guessed: `ClientboundSetTimePacket` carries the
     * server's `gameTime`, which advances once per server tick, so the ticks elapsed divided by the
     * wall clock between two packets IS the server's rate. The window logs the WORST value seen,
     * because a stall is what matters and an average hides it.
     */
    private val lastGameTime = AtomicLong(-1L)
    private val lastTimePacketNanos = AtomicLong()

    @Volatile
    private var worstServerTps = -1.0

    /** Longest gap between client ticks in the window — the client-thread stall detector. */
    private var lastTickNanos = 0L
    private var maxTickGapNanos = 0L

    private val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    private var lastTotalAllocated = 0L
    private val lastPerThreadAllocated = mutableMapOf<Long, Long>()

    override fun onEnabled() {
        openLog()
        resetWindow()
        worldLoadedMs = System.currentTimeMillis()
        lastFrameNanos = 0L
        val (count, time) = gcTotals()
        lastGcCount = count
        lastGcMs = time
        super.onEnabled()
    }

    override fun onDisabled() {
        flush(reason = "disabled")
        writer?.runCatching {
            flush()
            close()
        }
        writer = null
        super.onDisabled()
    }

    private fun openLog() {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val file = folder.resolve("perf-$stamp.csv")
        writer = file.bufferedWriter().apply {
            appendLine(
                "timeMs,worldAgeS,fpsCounter,frames,frameP50Ms,frameP95Ms,frameP99Ms,frameMaxMs," +
                    "ticks,tickHandlersAvgMs,tickHandlersMaxMs,heapUsedMb,heapMaxMb," +
                    "gcCount,gcMs,entities,modulesOn," +
                    (if (Network.enabled) "pingMs,pktIn,pktOut,teleports,maxSilenceMs,serverTps,lagModules," else "") +
                    (if (Deep.enabled) "allocMbS,topAlloc,gcYoung,gcOld,tickGapMaxMs,chunks," else "") +
                    "note"
            )
            // The previous log recorded only a COUNT of enabled modules, which was useless for
            // telling one run from another. Names go in once, up front.
            appendLine("# modules: ${ModuleManager.filter { it.running }.joinToString(" ") { it.name }}")
        }
        chat("§8[PerfLog] §7writing §f${file.name}§7 — disable the module to close it", this)
    }

    // ── Frame timing ─────────────────────────────────────────────────────────

    /**
     * One sample per rendered frame. The gap between consecutive world renders IS the frame time as
     * felt, including whatever the render thread stalled on.
     */
    @Suppress("unused")
    private val frameHandler = handler<WorldRenderEvent>(priority = FIRST_PRIORITY) {
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            frameTimes.add((now - lastFrameNanos) / 1000L)
        }
        lastFrameNanos = now

        if (System.currentTimeMillis() - windowStartMs >= interval * 1000L) {
            flush(reason = "")
        }
    }

    // ── Tick timing ──────────────────────────────────────────────────────────

    // These two bracket the whole GameTickEvent dispatch: every module's tick work happens between
    // the first handler position and the last, so the difference is the client's own per-tick cost.

    @Suppress("unused")
    private val tickStart = handler<GameTickEvent>(priority = FIRST_PRIORITY) {
        val now = System.nanoTime()
        if (lastTickNanos != 0L) {
            val gap = now - lastTickNanos
            if (gap > maxTickGapNanos) maxTickGapNanos = gap
        }
        lastTickNanos = now
        tickStartNanos = now
    }

    /** Ticks elapsed on the server per wall-clock second — 20.0 is healthy. */
    private fun recordServerTps(gameTime: Long) {
        val now = System.nanoTime()
        val previousTime = lastGameTime.getAndSet(gameTime)
        val previousNanos = lastTimePacketNanos.getAndSet(now)
        if (previousTime < 0 || previousNanos == 0L) return

        val elapsedSeconds = (now - previousNanos) / 1_000_000_000.0
        val serverTicks = gameTime - previousTime
        if (elapsedSeconds <= 0.0 || serverTicks <= 0) return

        val tps = (serverTicks / elapsedSeconds).coerceAtMost(20.0)
        if (worstServerTps < 0 || tps < worstServerTps) {
            worstServerTps = tps
        }
    }

    @Suppress("unused")
    private val tickEnd = handler<GameTickEvent>(priority = READ_FINAL_STATE) {
        if (tickStartNanos != 0L) {
            val spent = System.nanoTime() - tickStartNanos
            tickHandlerNanos += spent
            if (spent > tickHandlerMaxNanos) tickHandlerMaxNanos = spent
            ticks++
        }
    }

    private var tickHandlerMaxNanos = 0L

    /**
     * Runs on whatever thread delivered the packet, including netty — so it only touches atomics and
     * never the world.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (!Network.enabled) return@handler

        if (event.origin == TransferOrigin.OUTGOING) {
            packetsOut.incrementAndGet()
            return@handler
        }

        packetsIn.incrementAndGet()

        when (val packet = event.packet) {
            is ClientboundPlayerPositionPacket -> teleports.incrementAndGet()
            is ClientboundSetTimePacket -> recordServerTps(packet.gameTime)
            else -> Unit
        }

        val now = System.nanoTime()
        val previous = lastIncomingNanos.getAndSet(now)
        if (previous != 0L) {
            val gap = now - previous
            maxSilenceNanos.updateAndGet { if (gap > it) gap else it }
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        flush(reason = "world-change")
        worldLoadedMs = System.currentTimeMillis()
        lastFrameNanos = 0L
    }

    // ── Flush ────────────────────────────────────────────────────────────────

    @Suppress("MagicNumber")
    private fun flush(reason: String) {
        val out = writer ?: return
        val now = System.currentTimeMillis()
        if (frameTimes.isEmpty() && ticks == 0) {
            resetWindow()
            return
        }

        frameTimes.sort()
        val (gcCount, gcMs) = gcTotals()
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val heapMax = runtime.maxMemory() / MB

        val row = listOf(
            now,
            (now - worldLoadedMs) / 1000L,
            mc.fps,
            frameTimes.size,
            percentileMs(50),
            percentileMs(95),
            percentileMs(99),
            percentileMs(100),
            ticks,
            millis(if (ticks > 0) tickHandlerNanos / ticks else 0L),
            millis(tickHandlerMaxNanos),
            heapUsed,
            heapMax,
            gcCount - lastGcCount,
            gcMs - lastGcMs,
            mc.level?.entitiesForRendering()?.count() ?: 0,
            ModuleManager.count { it.running }
        ) + networkColumns() + jvmColumns() + listOf(reason)

        val line = row.joinToString(",")

        runCatching {
            out.appendLine(line)
            out.flush()
        }
        if (toChat) {
            chat("§8[PerfLog] §7$line", this)
        }

        lastGcCount = gcCount
        lastGcMs = gcMs
        resetWindow()
    }

    /** Empty when [Network] is off, so the row still matches the header that was written. */
    private fun networkColumns(): List<Any> {
        if (!Network.enabled) return emptyList()

        val ping = mc.player?.uuid?.let { mc.connection?.getPlayerInfo(it)?.latency } ?: -1
        return listOf(
            ping,
            packetsIn.get(),
            packetsOut.get(),
            teleports.get(),
            millis(maxSilenceNanos.get()),
            "%.1f".format(java.util.Locale.ROOT, worstServerTps),
            // Space-separated so it survives a CSV column.
            LAG_MODULES.filter { it.running }.joinToString(" ") { it.name }.ifEmpty { "-" }
        )
    }

    /** Empty when [Deep] is off, matching the header. */
    private fun jvmColumns(): List<Any> {
        if (!Deep.enabled) return emptyList()

        val (young, old) = gcSplit()
        val (allocMb, topThread) = allocationSample()
        return listOf(
            "%.1f".format(java.util.Locale.ROOT, allocMb),
            topThread,
            young,
            old,
            millis(maxTickGapNanos),
            mc.level?.chunkSource?.loadedChunksCount ?: 0
        )
    }

    /**
     * Total allocation since the last window plus the thread that produced the most of it.
     *
     * Attribution is the point: 60 MB/s means nothing on its own, but 60 MB/s coming out of "Render
     * thread" versus a mod's worker pool are completely different problems.
     */
    private fun allocationSample(): Pair<Double, String> {
        val bean = threadBean ?: return 0.0 to "n/a"

        val total = runCatching { bean.totalThreadAllocatedBytes }.getOrDefault(0L)
        val deltaMb = if (lastTotalAllocated > 0L) (total - lastTotalAllocated) / MB.toDouble() else 0.0
        lastTotalAllocated = total

        var topName = "-"
        var topDelta = 0L
        val current = mutableMapOf<Long, Long>()
        runCatching {
            val ids = bean.allThreadIds
            val bytes = bean.getThreadAllocatedBytes(ids)
            for (i in ids.indices) {
                if (bytes[i] < 0) continue
                current[ids[i]] = bytes[i]
                val previous = lastPerThreadAllocated[ids[i]] ?: continue
                val delta = bytes[i] - previous
                if (delta > topDelta) {
                    topDelta = delta
                    topName = bean.getThreadInfo(ids[i])?.threadName ?: "?"
                }
            }
        }
        lastPerThreadAllocated.clear()
        lastPerThreadAllocated.putAll(current)

        // Name plus its share, so a single column carries both who and how much.
        val topMb = topDelta / MB.toDouble()
        return deltaMb to "${topName.replace(',', ' ')}(${"%.1f".format(java.util.Locale.ROOT, topMb)}Mb)"
    }

    /**
     * Collections split by generation. The bean names carry it ("G1 Young Generation", "ZGC Major
     * Cycles", …), and the distinction matters: young collections are routine, an old/major one is
     * the pause long enough to be felt.
     */
    private fun gcSplit(): Pair<Long, Long> {
        var young = 0L
        var old = 0L
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            val count = bean.collectionCount
            if (count < 0) continue
            val previous = lastGcPerBean[bean.name] ?: 0L
            lastGcPerBean[bean.name] = count
            val delta = count - previous
            if (delta <= 0) continue

            val name = bean.name.lowercase()
            if ("young" in name || "minor" in name || "scavenge" in name || "eden" in name) {
                young += delta
            } else {
                old += delta
            }
        }
        return young to old
    }

    private val lastGcPerBean = HashMap<String, Long>()

    /** Modules that hold or delay packets on purpose — the first thing to rule out. */
    private val LAG_MODULES
        get() = listOf(ModuleBacktrack, ModuleFakeLag, ModulePingSpoof, ModuleBlink)

    private fun resetWindow() {
        frameTimes.clear()
        tickHandlerNanos = 0L
        tickHandlerMaxNanos = 0L
        ticks = 0
        packetsIn.set(0)
        packetsOut.set(0)
        teleports.set(0)
        maxSilenceNanos.set(0L)
        maxTickGapNanos = 0L
        worstServerTps = -1.0
        windowStartMs = System.currentTimeMillis()
    }

    /** [frameTimes] must be sorted. Percentile 100 is the max. */
    private fun percentileMs(percentile: Int): String {
        if (frameTimes.isEmpty()) return "0.00"
        val index = ((frameTimes.size - 1) * percentile / 100).coerceIn(0, frameTimes.size - 1)
        return "%.2f".format(java.util.Locale.ROOT, frameTimes[index] / 1000.0)
    }

    private fun millis(nanos: Long): String = "%.3f".format(java.util.Locale.ROOT, nanos / 1_000_000.0)

    /** Totals across every collector the JVM exposes — the direct read on GC pressure. */
    private fun gcTotals(): Pair<Long, Long> {
        var count = 0L
        var time = 0L
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.collectionCount >= 0) count += bean.collectionCount
            if (bean.collectionTime >= 0) time += bean.collectionTime
        }
        return count to time
    }

    private const val MB = 1024L * 1024L

}
