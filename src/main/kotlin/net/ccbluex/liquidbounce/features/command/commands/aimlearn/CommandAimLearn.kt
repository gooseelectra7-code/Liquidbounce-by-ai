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
package net.ccbluex.liquidbounce.features.command.commands.aimlearn

import com.google.gson.reflect.TypeToken
import net.ccbluex.liquidbounce.config.gson.publicGson
import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes.LearnedAimRecorder
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes.LearnedAimSample
import net.ccbluex.liquidbounce.utils.aiming.point.features.learned.AimLearningStorage
import net.ccbluex.liquidbounce.utils.aiming.point.features.learned.BucketKey
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.clickablePath
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.minecraft.util.Util
import java.io.File

/**
 * `.aimlearn ...` — manage the per-bucket aim-point histogram (the model behind
 * `PointProcessorLearned`). Mirrors the workflow of `.models` for the deep-learning combat model:
 * the [LearnedAimRecorder] debug-recorder mode dumps raw samples to disk, then `train` aggregates
 * those dumps into the live histogram that `aim-learning.json` persists for sampling.
 *
 * Subcommands:
 *  - `train` — rebuild the histogram from every JSON dump in `debug-recorder/LearnedAim/`
 *  - `stats` — print current model summary
 *  - `reset` — clear the histogram and persist the empty state
 *  - `browse` — open the storage folder
 */
object CommandAimLearn : Command.Factory {

    override fun createCommand(): Command = CommandBuilder
        .begin("aimlearn")
        .hub()
        .subcommand(trainCommand())
        .subcommand(statsCommand())
        .subcommand(resetCommand())
        .subcommand(browseCommand())
        .build()

    private fun trainCommand(): Command = CommandBuilder
        .begin("train")
        .handler {
            val folder = LearnedAimRecorder.folder
            val dumps = folder.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                ?.toList().orEmpty()
            if (dumps.isEmpty()) {
                chat(markAsError("No recordings in ${folder.absolutePath}. Run the LearnedAim recorder first."))
                return@handler
            }

            val samples = loadSamples(dumps)
            if (samples.isEmpty()) {
                chat(markAsError("All ${dumps.size} dump file(s) were empty or malformed."))
                return@handler
            }

            AimLearningStorage.ensureLoaded()
            AimLearningStorage.reset()
            for (sample in samples) {
                AimLearningStorage.record(
                    BucketKey(sample.distBin, sample.hurtBin),
                    sample.cellX,
                    sample.cellY,
                    sample.cellZ,
                )
            }
            AimLearningStorage.save(force = true)

            val stats = AimLearningStorage.stats()
            chat(regular("Trained from "), variable(dumps.size.toString()), regular(" dump(s), "),
                variable(samples.size.toString()), regular(" sample(s)."))
            chat(regular("Model: "), variable("${stats.bucketCount} buckets / ${stats.cellCount} cells "
                + "@ grid=${stats.gridResolution}"))
        }
        .build()

    private fun statsCommand(): Command = CommandBuilder
        .begin("stats")
        .handler {
            AimLearningStorage.ensureLoaded()
            val stats = AimLearningStorage.stats()
            chat(regular("AimLearn model: "), variable("${stats.bucketCount} buckets, "
                + "${stats.cellCount} cells, weight=${"%.1f".format(stats.totalWeight)}, "
                + "grid=${stats.gridResolution}"))

            val breakdown = AimLearningStorage.perBucketBreakdown().take(STATS_TOP_BUCKETS)
            if (breakdown.isEmpty()) {
                chat(regular("  (no buckets yet — record some attacks first)"))
                return@handler
            }
            for (b in breakdown) {
                chat(
                    regular("  dist=${b.key.distBin} hurt=${b.key.hurtBin}: "),
                    variable("${b.cellCount} cells, weight=${"%.1f".format(b.totalWeight)}"),
                )
            }
        }
        .build()

    private fun resetCommand(): Command = CommandBuilder
        .begin("reset")
        .handler {
            AimLearningStorage.reset()
            AimLearningStorage.save(force = true)
            chat(regular("AimLearn model cleared."))
        }
        .build()

    private fun browseCommand(): Command = CommandBuilder
        .begin("browse")
        .handler {
            val folder = LearnedAimRecorder.folder
            Util.getPlatform().openFile(folder)
            chat(regular("Location: "), clickablePath(folder))
        }
        .build()

    private fun loadSamples(files: List<File>): List<LearnedAimSample> {
        val listType = object : TypeToken<List<LearnedAimSample>>() {}.type
        val out = ArrayList<LearnedAimSample>()
        for (file in files) {
            runCatching {
                file.bufferedReader().use { reader ->
                    val parsed: List<LearnedAimSample>? = publicGson.fromJson(reader, listType)
                    if (parsed != null) out.addAll(parsed)
                }
            }.onFailure { /* skip malformed file */ }
        }
        return out
    }

    private const val STATS_TOP_BUCKETS = 8
}
