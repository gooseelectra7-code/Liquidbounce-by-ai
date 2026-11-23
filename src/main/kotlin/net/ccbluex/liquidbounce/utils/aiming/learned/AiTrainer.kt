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
package net.ccbluex.liquidbounce.utils.aiming.learned

import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.regular
import net.minecraft.util.Mth
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The AI trainer: records a DATASET of the player's own mouse movement and builds a learned
 * rotation out of it. Port of the recording half of `troll.xyz.ai.AiTrainer` (supplied by the
 * user 2026-09-19); the generation half is [LearnedRotationBuilder].
 *
 * The workflow (`.ai start` ... `.ai create <name>`):
 *  1. `.ai start` - every tick the yaw / pitch deltas of the camera are written (the raw
 *     handwriting of the hand) plus context: speed, ground / falling, swing, sprint, click, elytra,
 *     sneak and the absolute head angles. The player fights on a PvP server BY HAND for ~15
 *     minutes; ticks where a module steers the aim (`RotationManager.currentRotation != null`,
 *     the client's own "a module is driving" signal the CloneRecorder reads too) never enter the
 *     dataset - that is not a hand.
 *  2. `.ai stop` - the dataset is saved to `<config>/learned/datasets/dataset_<stamp>.json`
 *     (autosaved every 20 seconds, auto-stopped at the 15th minute, saved on world change and on
 *     client shutdown).
 *  3. `.ai create <name>` - the dataset is taken apart into SWINGS (monotone turns of >= 8 deg,
 *     the future aim speed curves), MICRO-movements (settling / jitter) and PHRASES, plus the
 *     duration profile, the pause share and the vertical follow. The result is
 *     `<config>/learned/rotations/<name>.json`, which the `Learned` angle smoother replays.
 *
 * Differences from the original, all deliberate: the dataset is written on a background thread
 * (a 15-minute JSON is a visible hitch on the game thread), the first tick after a start or a
 * world change only rebases the reference angles (a respawn would otherwise record a 180-degree
 * "movement"), and chat strings are English.
 */
object AiTrainer : EventListener {

    private val logger = clientLogger("AI/Learned")

    override val running: Boolean
        get() = true

    /** 15 minutes of recording at 20 ticks per second. */
    const val MAX_TICKS = 18000

    /** Autosave every 400 ticks (20 seconds). */
    private const val AUTOSAVE_TICKS = 400

    /** Longest segment before a forced commit. */
    private const val MAX_SEG_LEN = 120

    /** Sensor tremor below this path is noise and never written. */
    private const val NOISE_FLOOR = 0.25

    /** Still ticks after which the open segment closes: the hand stopped. */
    private const val IDLE_TICKS = 2
    private const val MOVED_EPSILON = 0.01f

    /** A rotation needs at least this many swings to be worth anything. */
    private const val MIN_FLICKS = 5

    /** v23: live handwriting stats over a ring of the last 512 ticks, for `.ai status`. */
    private const val LIVE_N = 512
    private const val LIVE_MIN = 8
    private const val LIVE_SPIKE_MEAN = 0.08
    private const val LIVE_SPIKE_ABS = 1.2
    private const val LIVE_SPIKE_FACTOR = 2.2
    private const val LIVE_MOVING = 0.15f
    private const val T50_LOW = 0.22f
    private const val T50_SPAN = 0.38f
    private const val MS_PER_TICK = 50L

    private val segments = ArrayList<AimSegment>()

    var isRecording = false
        private set

    private var startedMs = 0L

    var recordedTicks = 0
        private set

    private var autosaveCounter = 0
    private var lastYaw = 0f
    private var lastPitch = 0f

    /** True until the first tick has set [lastYaw] / [lastPitch] - there is no delta to measure yet. */
    private var rebase = true
    private var currentFile: File? = null

    // the open segment
    private var curDy: FloatArrayList? = null
    private var curDp: FloatArrayList? = null
    private var curSp: FloatArrayList? = null
    private var curFl: IntArrayList? = null
    private var curMy: FloatArrayList? = null
    private var curMp: FloatArrayList? = null
    private var curStart = 0
    private var idle = 0

    private val liveDy = FloatArray(LIVE_N)
    private val liveDp = FloatArray(LIVE_N)
    private var liveIdx = 0
    private var liveFill = 0
    private var prevSwinging = false

    /** Serializes the background autosaves and the final synchronous save. */
    private val saveLock = Any()

    val segmentCount: Int
        get() = segments.size

    val elapsedMs: Long
        get() = if (isRecording) System.currentTimeMillis() - startedMs else 0L

    // ===================== LIFECYCLE =====================

    /** Starts a recording; returns the message to show, or the reason it did not start. */
    fun start(): String {
        if (isRecording) {
            return "Recording already running: ${formatDuration(elapsedMs)}, $recordedTicks ticks. " +
                "Finish it with .ai stop"
        }
        segments.clear()
        recordedTicks = 0
        autosaveCounter = 0
        idle = 0
        dropOpenSegment()
        liveIdx = 0
        liveFill = 0
        startedMs = System.currentTimeMillis()
        rebase = true
        prevSwinging = mc.player?.swinging ?: false
        currentFile = AimDatasets.newFile()
        isRecording = true
        return "Dataset recording STARTED (15 minute limit). Play PvP BY HAND - every mouse movement " +
            "goes into the dataset; ticks where a module aims for you are skipped automatically. " +
            "Finish with .ai stop, then .ai create <name>"
    }

    /** Stops the recording and saves it; the returned message is empty when [silent]. */
    fun stop(silent: Boolean = false): String {
        if (!isRecording) {
            return if (silent) "" else "No recording is running. Start one with .ai start"
        }
        isRecording = false
        commitSegment()
        val saved = saveNow()
        val seconds = (System.currentTimeMillis() - startedMs) / 1000L
        if (silent) {
            return ""
        }
        if (!saved) {
            return "Recording stopped, but there is too little data (segments: ${segments.size}). " +
                "Play longer and try again."
        }
        return "Dataset saved: ${currentFile?.name} (${segments.size} segments, " +
            "${formatDuration(seconds * 1000L)} of clean recording). Build a rotation: .ai create <name>"
    }

    /** Multi-line status for `.ai status`. */
    fun status(): List<String> {
        if (!isRecording) {
            return listOf(
                "Not recording. Datasets: ${AimDatasets.count()}, learned rotations: " +
                    "${LearnedRotations.names.size}. Start with .ai start"
            )
        }
        val segmentTicks = segments.sumOf { it.length }
        val idleShare = if (recordedTicks > 0) 1.0 - segmentTicks / recordedTicks.toDouble() else 0.0
        val player = mc.player
        val head = if (player != null) {
            String.format(
                Locale.US, "head yaw %.0f pitch %.0f | ",
                Mth.wrapDegrees(player.yRot), player.xRot
            )
        } else {
            ""
        }
        val st = liveStats()
        val style = when {
            st[3] > 0.55 -> "smooth"
            st[3] < 0.35 -> "sharp"
            else -> "medium"
        }
        return listOf(
            "Recording: ${formatDuration(elapsedMs)} / 15:00 | ticks $recordedTicks | segments " +
                "${segments.size} | pauses ${String.format(Locale.US, "%.0f%%", idleShare * 100.0)}",
            head + String.format(Locale.US, "speed %.2f deg/tick", st[0]) +
                String.format(Locale.US, " | spikes %.0f%%", st[1] * 100.0) +
                String.format(Locale.US, " | direction changes %.1f/100t", st[2]) +
                " | handwriting: $style"
        )
    }

    /**
     * v23: live handwriting stats over the ring of the last recorded ticks.
     * Returns [meanAbsDy, spikeRate, dirChangePer100, smoothnessEstimate].
     */
    @Suppress("MagicNumber", "CognitiveComplexMethod")
    private fun liveStats(): DoubleArray {
        val n = liveFill
        if (n < LIVE_MIN) {
            return doubleArrayOf(0.0, 0.0, 0.0, 0.5)
        }
        var meanAbs = 0.0
        for (i in 0 until n) {
            meanAbs += abs(liveDy[i])
        }
        meanAbs /= n
        // spikes and direction changes
        var spikes = 0
        var flipTicks = 0.0
        var prevSign = 0f
        for (i in 0 until n) {
            val v = liveDy[i]
            val av = abs(v)
            var loc = 0.0
            var cnt = 0
            for (k in maxOf(0, i - 2)..minOf(n - 1, i + 2)) {
                loc += abs(liveDy[k])
                cnt++
            }
            loc /= cnt
            if (meanAbs > LIVE_SPIKE_MEAN && av > LIVE_SPIKE_ABS && av > LIVE_SPIKE_FACTOR * loc) {
                spikes++
            }
            if (av > LIVE_MOVING) {
                val sgn = sign(v)
                if (prevSign != 0f && sgn != prevSign) {
                    flipTicks += 100.0
                }
                prevSign = sgn
            }
        }
        val spikeRate = spikes / n.toDouble()
        val dirChange = flipTicks / n
        // smoothness estimate by the median t50 of the live movement (whole window): the same idea
        // as for flicks, early speed means a sharp hand
        var cum = 0.0
        var total = 0.0
        var t50 = 0.5
        for (i in 0 until n) {
            total += abs(liveDy[i])
        }
        if (total > 1e-6) {
            for (i in 0 until n) {
                val prev = cum / total
                cum += abs(liveDy[i])
                val cur = cum / total
                if (prev < 0.5 && cur >= 0.5) {
                    t50 = (i + 0.5) / n
                    break
                }
            }
            t50 = ((t50 - T50_LOW) / T50_SPAN).toFloat().coerceIn(0f, 1f).toDouble()
        }
        return doubleArrayOf(meanAbs, spikeRate, dirChange, t50)
    }

    // ===================== CAPTURE =====================

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!isRecording) {
            return@handler
        }
        val p = mc.player ?: return@handler
        mc.level ?: return@handler

        // Ticks where a module steers the aim never enter the dataset: that is arithmetic, not a
        // hand. The camera is left alone.
        if (RotationManager.currentRotation != null) {
            lastYaw = p.yRot
            lastPitch = p.xRot
            return@handler
        }
        if (rebase) {
            lastYaw = p.yRot
            lastPitch = p.xRot
            rebase = false
            return@handler
        }

        val y = p.yRot
        val pi = p.xRot
        val dy = Mth.wrapDegrees(y - lastYaw)
        val dp = pi - lastPitch
        lastYaw = y
        lastPitch = pi
        recordedTicks++
        autosaveCounter++

        // v23: the live handwriting buffer behind `.ai status`
        liveDy[liveIdx] = dy
        liveDp[liveIdx] = dp
        liveIdx = (liveIdx + 1) % LIVE_N
        if (liveFill < LIVE_N) {
            liveFill++
        }

        val moved = abs(dy) > MOVED_EPSILON || abs(dp) > MOVED_EPSILON
        if (moved) {
            idle = 0
            if (curDy == null) {
                curDy = FloatArrayList()
                curDp = FloatArrayList()
                curSp = FloatArrayList()
                curFl = IntArrayList()
                curMy = FloatArrayList()
                curMp = FloatArrayList()
                curStart = recordedTicks
            }
            val velocity = p.deltaMovement
            curDy!!.add(dy)
            curDp!!.add(dp)
            curSp!!.add(sqrt(velocity.x * velocity.x + velocity.z * velocity.z).toFloat())
            // The swing edge is sampled on MOVING ticks only, as written in the original.
            val swinging = p.swinging
            val swingStart = swinging && !prevSwinging
            prevSwinging = swinging
            var flags = 0
            if (p.onGround()) {
                flags = flags or AimSegment.FLAG_GROUND
            }
            if (p.getAttackAnim(1f) > 0f) {
                flags = flags or AimSegment.FLAG_SWINGING
            }
            if (p.isSprinting) {
                flags = flags or AimSegment.FLAG_SPRINT
            }
            if (p.fallDistance > 0.1) {
                flags = flags or AimSegment.FLAG_FALLING
            }
            if (swingStart) {
                flags = flags or AimSegment.FLAG_CLICK
            }
            if (p.isFallFlying) {
                flags = flags or AimSegment.FLAG_ELYTRA
            }
            if (p.isShiftKeyDown) {
                flags = flags or AimSegment.FLAG_SNEAK
            }
            curFl!!.add(flags)
            // the head turn: absolute angles
            curMy!!.add(Mth.wrapDegrees(y))
            curMp!!.add(pi)
            if (curDy!!.size >= MAX_SEG_LEN) {
                commitSegment()
            }
        } else if (curDy != null && ++idle >= IDLE_TICKS) {
            commitSegment() // the hand stopped - the segment is closed
        }

        if (autosaveCounter >= AUTOSAVE_TICKS) {
            autosaveCounter = 0
            saveInBackground()
        }
        if (recordedTicks >= MAX_TICKS) {
            val message = stop(silent = false)
            chat(regular(message))
        }
    }

    /** A world change is a boundary: the open segment closes, the reference angles are re-read. */
    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        if (!isRecording) {
            return@handler
        }
        commitSegment()
        rebase = true
        saveInBackground()
    }

    @Suppress("unused")
    private val shutdownHandler = handler<ClientShutdownEvent> {
        if (isRecording) {
            stop(silent = true)
        }
    }

    private fun commitSegment() {
        val dy = curDy
        if (dy != null && dy.size >= 2) {
            val segment = AimSegment(
                curStart,
                dy.toFloatArray(),
                curDp!!.toFloatArray(),
                curSp!!.toFloatArray(),
                curFl!!.toIntArray(),
                curMy!!.toFloatArray(),
                curMp!!.toFloatArray()
            )
            if (segment.absSum() > NOISE_FLOOR) {
                segments.add(segment)
            }
        }
        dropOpenSegment()
    }

    private fun dropOpenSegment() {
        curDy = null
        curDp = null
        curSp = null
        curFl = null
        curMy = null
        curMp = null
        idle = 0
    }

    // ===================== STORAGE =====================

    /** Synchronous save on the caller's thread; waits for a running background save first. */
    private fun saveNow(): Boolean {
        val file = currentFile ?: return false
        val snapshot = segments.toList()
        val ticks = recordedTicks
        synchronized(saveLock) {
            return AimDatasets.save(file, snapshot, ticks)
        }
    }

    private fun saveInBackground() {
        val file = currentFile ?: return
        if (segments.isEmpty()) {
            return
        }
        // Segments are immutable once committed, so a snapshot of the list is enough.
        val snapshot = segments.toList()
        val ticks = recordedTicks
        thread(name = "learned-autosave", isDaemon = true) {
            synchronized(saveLock) {
                if (!AimDatasets.save(file, snapshot, ticks)) {
                    logger.warn("Autosave of ${file.name} failed.")
                }
            }
        }
    }

    // ===================== GENERATION =====================

    /** What `.ai create` produced, for the chat summary. */
    class Created(val rotation: LearnedRotation, val source: String, val totalTicks: Int)

    /**
     * Builds a rotation from the current recording (if one is running and has segments) or from the
     * newest dataset on disk, and writes it. The registry is NOT reloaded here; the caller does that
     * so it can also select the result.
     */
    @Suppress("ReturnCount")
    fun createRotation(rawName: String): Result<Created> {
        val name = LearnedFiles.sanitizeName(rawName)
        if (name.isEmpty() || name.equals("default", ignoreCase = true) ||
            name.equals(LearnedRotations.NONE, ignoreCase = true)
        ) {
            return Result.failure(
                IllegalArgumentException("Invalid rotation name (A-Z, a-z, 0-9, _ -). Example: .ai create bypass")
            )
        }

        val data: List<AimSegment>
        val source: String
        if (isRecording && segments.isNotEmpty()) {
            data = segments.toList()
            source = "the current recording"
        } else {
            val file = AimDatasets.newest()
                ?: return Result.failure(
                    IllegalStateException(
                        "No datasets. First: .ai start -> play PvP for 10-15 minutes by hand -> " +
                            ".ai stop -> .ai create $name"
                    )
                )
            data = AimDatasets.load(file)
            source = file.name
        }
        if (data.isEmpty()) {
            return Result.failure(
                IllegalStateException("The dataset has no movement in it. Play longer (.ai start -> PvP -> .ai stop).")
            )
        }

        val rotation = LearnedRotationBuilder.build(name, data)
        val totalTicks = data.sumOf { it.length }
        rotation.sourceTicks = totalTicks

        val flicks = rotation.flicks?.size ?: 0
        if (flicks < MIN_FLICKS) {
            return Result.failure(
                IllegalStateException(
                    "Too few swings in the dataset ($flicks). Play PvP more actively: the more real " +
                        "aim swings, the better the rotation."
                )
            )
        }

        rotation.name = name
        rotation.createdMs = System.currentTimeMillis()
        if (!LearnedRotations.save(rotation)) {
            return Result.failure(IllegalStateException("Could not write the rotation file for '$name'."))
        }
        return Result.success(Created(rotation, source, totalTicks))
    }

    // ===================== HELPERS =====================

    fun formatDuration(ms: Long): String {
        val s = ms / 1000L
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun formatTicks(ticks: Int): String = formatDuration(ticks * MS_PER_TICK)
}
