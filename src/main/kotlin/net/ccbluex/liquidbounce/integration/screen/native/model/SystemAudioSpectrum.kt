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
package net.ccbluex.liquidbounce.integration.screen.native.model

import net.ccbluex.liquidbounce.utils.client.logger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A tiny spectrum of what the PC is currently playing, for the title-screen visualizer.
 *
 * Capture uses `javax.sound.sampled` (JDK, no dependencies) and works when the system
 * exposes a LOOPBACK recording device — on Windows that is "Stereo Mix" / "What U Hear" /
 * anything with "loopback" in the name. Without one, [isCapturing] stays false and the
 * caller renders its idle animation instead; the feature degrades silently.
 *
 * [bands] is a [BANDS]-sized array of smoothed 0..1 magnitudes over log-spaced
 * frequencies (Goertzel per band over 1024-sample windows, auto-gain, fast attack /
 * slow decay). Capture runs on one daemon thread between [acquire]/[release].
 */
object SystemAudioSpectrum {

    const val BANDS = 48

    private const val SAMPLE_RATE = 44100f
    private const val WINDOW = 1024
    private const val MIN_FREQ = 40.0
    private const val MAX_FREQ = 15000.0
    private const val ATTACK = 0.55f
    private const val DECAY = 0.10f

    private val LOOPBACK_HINTS = listOf("stereo mix", "what u hear", "loopback", "wave out", "стерео микшер")

    @Volatile
    private var published = FloatArray(BANDS)

    @Volatile
    var isCapturing = false
        private set

    private var refCount = 0
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Latest smoothed band magnitudes (do not mutate). */
    fun bands(): FloatArray = published

    @Synchronized
    fun acquire() {
        refCount++
        if (thread == null) {
            running = true
            thread = Thread(::captureLoop, "hellhit-audio-spectrum").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Synchronized
    fun release() {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) {
            running = false
            thread = null
        }
    }

    private fun findLoopbackLine(format: AudioFormat): TargetDataLine? {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val mixers = AudioSystem.getMixerInfo()

        // prefer explicit loopback devices, then anything that can capture at all
        val ranked = mixers.sortedByDescending { mixerInfo ->
            val name = mixerInfo.name.lowercase()
            if (LOOPBACK_HINTS.any { it in name }) 1 else 0
        }
        for (mixerInfo in ranked) {
            val name = mixerInfo.name.lowercase()
            val isLoopback = LOOPBACK_HINTS.any { it in name }
            if (!isLoopback) continue // only true loopback: a microphone would pulse to room noise
            runCatching {
                val mixer = AudioSystem.getMixer(mixerInfo)
                if (mixer.isLineSupported(info)) {
                    return mixer.getLine(info) as TargetDataLine
                }
            }
        }
        return null
    }

    @Suppress("detekt:CognitiveComplexMethod", "detekt:LongMethod", "TooGenericExceptionCaught")
    private fun captureLoop() {
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
        val freqs = DoubleArray(BANDS) { i ->
            MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(i / (BANDS - 1.0))
        }
        val coeffs = DoubleArray(BANDS) { i ->
            2.0 * cos(2.0 * PI * freqs[i] / SAMPLE_RATE)
        }
        val smoothed = FloatArray(BANDS)
        val samples = FloatArray(WINDOW)
        val buffer = ByteArray(WINDOW * 2)
        var agcPeak = 1e-4f

        var line: TargetDataLine? = null
        try {
            line = findLoopbackLine(format)
            if (line == null) {
                isCapturing = false
                return
            }
            line.open(format, WINDOW * 4)
            line.start()
            isCapturing = true

            while (running) {
                var read = 0
                while (read < buffer.size && running) {
                    val n = line.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (!running) break
                if (read < buffer.size) continue

                for (i in 0 until WINDOW) {
                    val lo = buffer[i * 2].toInt() and 0xFF
                    val hi = buffer[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo) / 32768f
                }

                // Goertzel per band with a Hann-ish energy normalization
                var frameMax = 0f
                for (b in 0 until BANDS) {
                    var s0: Double
                    var s1 = 0.0
                    var s2 = 0.0
                    val coeff = coeffs[b]
                    for (i in 0 until WINDOW) {
                        s0 = samples[i] + coeff * s1 - s2
                        s2 = s1
                        s1 = s0
                    }
                    val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
                    val mag = (sqrt(power.coerceAtLeast(0.0)) / WINDOW).toFloat()
                    // gentle log response, weighting highs up a touch
                    val shaped = (ln(1.0 + mag * 260.0) / ln(28.0)).toFloat() * (0.8f + 0.4f * b / BANDS)
                    if (shaped > frameMax) frameMax = shaped
                    val target = shaped
                    smoothed[b] += (target - smoothed[b]) * (if (target > smoothed[b]) ATTACK else DECAY)
                }

                // slow AGC so quiet and loud tracks both fill the range
                agcPeak = maxOf(frameMax, agcPeak * 0.995f, 1e-4f)
                val out = FloatArray(BANDS)
                for (b in 0 until BANDS) {
                    out[b] = (smoothed[b] / agcPeak).coerceIn(0f, 1f)
                }
                published = out
            }
        } catch (e: Exception) {
            logger.warn("Audio spectrum capture stopped: ${e.message}")
        } finally {
            isCapturing = false
            runCatching {
                line?.stop()
                line?.close()
            }
            published = FloatArray(BANDS)
        }
    }
}
