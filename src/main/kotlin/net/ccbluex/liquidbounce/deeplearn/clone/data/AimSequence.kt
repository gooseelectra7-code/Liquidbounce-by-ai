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
package net.ccbluex.liquidbounce.deeplearn.clone.data

import com.google.gson.annotations.SerializedName
import net.ccbluex.liquidbounce.config.gson.util.readJson
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine.datasetsFolder
import java.io.File

/**
 * An unbroken run of recorded ticks.
 *
 * A sequence is the training unit: the model is recurrent, so ticks are only meaningful next to
 * the ticks they actually followed. Anything that breaks the continuity of the player's hand on
 * the mouse — a world change, an opened screen, a sensitivity change, the bot taking over the
 * rotation — ends the current sequence and starts a new one rather than being papered over.
 *
 * [gcd] is stored per sequence because it is what converts the integer [AimTick.dx] counts back
 * into degrees, and it changes whenever the player changes their sensitivity.
 */
@JvmRecord
data class AimSequence(
    @SerializedName("v")
    val version: Int,
    @SerializedName("prof")
    val profile: String,
    @SerializedName("g")
    val gcd: Double,
    @SerializedName("sens")
    val sensitivity: Double,
    @SerializedName("ts")
    val timestamp: Long,
    @SerializedName("t")
    val ticks: List<AimTick>,

    /**
     * Where the sequence was recorded: [SOURCE_GYM], [SOURCE_DUMMY] or the server it was played
     * on (the waypoint store's normalized key — the typed address, or `local_<world>` in
     * singleplayer). `null` on recordings made before this was stored; [isPractice] then falls back
     * to the target ids.
     *
     * Added without a [VERSION] bump on purpose: it describes where the ticks came from, not what
     * they mean, and bumping would have thrown every older recording away.
     */
    @SerializedName("src")
    val source: String? = null
) {

    /**
     * A drill against a client-only dummy — CloneGym or `.fakeplayer` — rather than a real opponent.
     *
     * The dummy stands still or walks a scripted ring, never fights back, and is fought from point
     * blank, so it teaches a different hand: measured 2026-09-28 on the `default` profile, its files
     * had every target within 4 blocks and up to 16 degrees of yaw per tick against 4-6 in real
     * fights, and folding them into training cost the clone hit rate in proportion to their share.
     *
     * The legacy rule is exact rather than a heuristic: both dummies are client-side entities with
     * negative ids (`CloneGym.FIRST_GYM_ID`, `CommandFakePlayer`), a server never hands out a
     * negative entity id, and on that profile the practice files were precisely the ones whose
     * targets were 100 % negative while every other file had none.
     */
    val isPractice: Boolean
        get() = when (source) {
            SOURCE_GYM, SOURCE_DUMMY -> true
            null -> ticks.any { it.hasTarget } && ticks.all { !it.hasTarget || it.targetId < 0 }
            else -> false
        }

    /** Share of valid combat rows. Schema v5 recordings should always report one. */
    val targetFraction: Float
        get() = if (ticks.isEmpty()) 0f else ticks.count { it.hasTarget }.toFloat() / ticks.size

    /**
     * A stable fingerprint of this sequence as it sits on disk, stored in a trained model's
     * metadata so the exact train/hold-out membership can be reproduced later. Timestamp alone is
     * millisecond-unique in practice (sequences close at tick granularity); the length is folded
     * in as cheap insurance. This only has to distinguish sequences *within one profile*.
     */
    val identity: Long
        get() = timestamp * 31 + ticks.size

    companion object {
        /**
         * Bump whenever [AimTick] or the recording convention changes in a way that makes older
         * dumps mean something different. Mismatched sequences are dropped at load rather than
         * silently trained on.
         */
        const val VERSION = 5

        /**
         * Shortest sequence worth keeping: one whole training window.
         *
         * Was 24 on the false reasoning that the warmup sits outside the window — it is inside it.
         * Anything shorter produced zero training windows while still being counted in the loaded
         * tick total and folded into the normalization and coverage statistics, so a profile of
         * many short fights reported data that trained nothing.
         */
        const val MIN_LENGTH = 64

        /** [source] of a sequence recorded against the CloneGym dummy. */
        const val SOURCE_GYM = "gym"

        /** [source] of a sequence recorded against any other client-only entity, e.g. `.fakeplayer`. */
        const val SOURCE_DUMMY = "dummy"

        /** [source] when the recorder could not tell which server it was on. */
        const val SOURCE_UNKNOWN = "unknown"

        fun folderOf(profile: String): File = datasetsFolder.resolve(profile)

        fun profiles(): List<String> = datasetsFolder
            .listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        /**
         * What a profile actually yielded, including what had to be thrown away.
         *
         * Discards are reported rather than swallowed: a recording made before a schema change
         * describes a different world than the current features do, and silently training on
         * three quarters of a profile is the kind of thing that surfaces as a mysteriously poor
         * model hours later.
         */
        class ParseResult(
            /** What training and evaluation use — see [selectForTraining]. */
            val sequences: List<AimSequence>,
            /** Recorded by a build whose schema differs — older *or* newer. */
            val wrongVersion: Int,
            val tooShort: Int,
            /** Files that could not be parsed at all — a crash mid-write leaves one behind. */
            val unreadableFiles: Int,
            /** Practice sequences left out of [sequences]; see [AimSequence.isPractice]. */
            val practice: List<AimSequence> = emptyList(),
            /** Nothing but practice was recorded, so [sequences] holds it rather than nothing. */
            val practiceOnly: Boolean = false
        )

        /**
         * Real fights only, whenever the profile has any: practice against a dummy is a different
         * hand (see [isPractice]) and measurably pulled the clone away from the one it is deployed
         * against. A profile made of nothing but practice is kept whole, since leaving it empty
         * would make recording one on purpose useless.
         *
         * Returns what to use and what was set aside.
         */
        fun selectForTraining(sequences: List<AimSequence>): Pair<List<AimSequence>, List<AimSequence>> {
            val (practice, real) = sequences.partition { it.isPractice }
            return if (real.isEmpty()) practice to emptyList() else real to practice
        }

        fun parseDetailed(profile: String): ParseResult {
            val folder = folderOf(profile)
            if (!folder.isDirectory) {
                return ParseResult(emptyList(), 0, 0, 0)
            }

            var unreadable = 0
            val all = folder.listFiles { file -> file.isFile && file.extension == "json" }
                ?.sortedBy { it.name }
                ?.flatMap { file ->
                    // Counted rather than swallowed: a file corrupted by a crash mid-write would
                    // otherwise vanish from every statistic without a trace.
                    runCatching { file.readJson<List<AimSequence>>() }
                        .getOrElse {
                            unreadable++
                            emptyList()
                        }
                }
                ?: emptyList()

            val current = all.filter { it.version == VERSION }
            val usable = current.filter { it.ticks.size >= MIN_LENGTH }
            val (selected, practice) = selectForTraining(usable)
            return ParseResult(
                sequences = selected,
                wrongVersion = all.size - current.size,
                tooShort = current.count { it.ticks.size < MIN_LENGTH },
                unreadableFiles = unreadable,
                practice = practice,
                practiceOnly = selected.isNotEmpty() && selected.all { it.isPractice }
            )
        }

        fun parse(profile: String): List<AimSequence> = parseDetailed(profile).sequences
    }
}
