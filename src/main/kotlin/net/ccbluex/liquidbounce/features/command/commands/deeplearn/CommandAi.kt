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
package net.ccbluex.liquidbounce.features.command.commands.deeplearn

import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.CommandException
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.learned.AiTrainer
import net.ccbluex.liquidbounce.utils.aiming.learned.AimDatasets
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedFiles
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotation
import net.ccbluex.liquidbounce.utils.aiming.learned.LearnedRotations
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.clickablePath
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.text.asText
import java.util.Locale

/**
 * `.ai ...` - record a dataset of your own aim and build a learned rotation from it.
 *
 * The workflow of the original client's `.ai` command (`troll.xyz`), on this client's command
 * builder: `.ai start` -> play PvP by hand -> `.ai stop` -> `.ai create <name>`. The result is a
 * library file the `Learned` angle smoother replays; `create` also selects it in KillAura, as the
 * original did.
 */
object CommandAi : Command.Factory {

    private const val LEARNED_SMOOTHER = "Learned"

    override fun createCommand(): Command = CommandBuilder
        .begin("ai")
        .alias("learn")
        .hub()
        .subcommand(startCommand())
        .subcommand(stopCommand())
        .subcommand(statusCommand())
        .subcommand(buildCreateCommand())
        .subcommand(listCommand())
        .subcommand(selectCommand())
        .subcommand(deleteCommand())
        .subcommand(reloadCommand())
        .build()

    private fun startCommand(): Command = CommandBuilder
        .begin("start")
        .handler {
            chat(regular(AiTrainer.start()))
        }
        .build()

    private fun stopCommand(): Command = CommandBuilder
        .begin("stop")
        .handler {
            chat(regular(AiTrainer.stop()))
        }
        .build()

    private fun statusCommand(): Command = CommandBuilder
        .begin("status")
        .handler {
            for (lineText in AiTrainer.status()) {
                chat(regular(lineText))
            }
        }
        .build()

    /**
     * `.ai create <name>`; `.ai create rotation <name>` is accepted too, for hands that learned the
     * original's syntax.
     */
    @Suppress("LongMethod")
    private fun buildCreateCommand(): Command = CommandBuilder
        .begin("create")
        .parameter(
            ParameterBuilder
                .begin<String>("name")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                .required()
                .build()
        )
        .parameter(
            ParameterBuilder
                .begin<String>("nameIfRotationKeyword")
                .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                .optional()
                .build()
        )
        .handler {
            val first = args[0] as String
            val second = args.getOrNull(1) as? String
            val name = if (first.equals("rotation", ignoreCase = true) && second != null) second else first

            val created = AiTrainer.createRotation(name).getOrElse { error ->
                throw CommandException((error.message ?: "Could not create the rotation.").asText())
            }
            val rotation = created.rotation
            val finalName = rotation.name ?: name

            LearnedRotations.load()
            val selected = LearnedRotations.select(finalName)
            val switched = KillAuraRotationsValueGroup.selectAngleSmooth(LEARNED_SMOOTHER)

            chat(
                regular("Rotation "), variable(finalName),
                regular(
                    if (selected && switched) {
                        " created and selected in KillAura."
                    } else {
                        " created (select it under KillAura > Rotations > AngleSmooth = Learned)."
                    }
                )
            )
            line(
                "Library",
                "${rotation.flicks?.size ?: 0} swings, ${rotation.micros?.size ?: 0} micros, " +
                    "${rotation.phrases?.size ?: 0} phrases, pauses " +
                    String.format(Locale.US, "%.0f%%", rotation.pauseRate * 100.0)
            )
            line("Handwriting", describeHandwriting(rotation))
            if (rotation.attackSyncRate > ATTACK_SYNC_MENTION) {
                line(
                    "Attack profile",
                    String.format(
                        Locale.US, "click %d-%d ticks after the swing starts (sync %.0f%%) - shown only, " +
                            "the clicker is not driven by it",
                        rotation.attackDelayMin, rotation.attackDelayMax, rotation.attackSyncRate * 100.0
                    )
                )
            }
            line(
                "Source",
                "${created.source} (${AiTrainer.formatTicks(created.totalTicks)} of movement). " +
                    "More dataset means better quality."
            )
            chat(regular("Written to "), clickablePath(LearnedRotations.fileOf(finalName)))
        }
        .build()

    private fun listCommand(): Command = CommandBuilder
        .begin("list")
        .handler {
            val names = LearnedRotations.names
            if (names.isEmpty()) {
                chat(
                    regular("No learned rotations yet. "), variable(".ai start"), regular(" -> PvP -> "),
                    variable(".ai stop"), regular(" -> "), variable(".ai create <name>")
                )
            } else {
                chat(regular("Learned rotations ("), variable("${names.size}"), regular("):"))
                for (name in names) {
                    val lr = LearnedRotations[name]
                    line(
                        name,
                        if (lr == null) {
                            "?"
                        } else {
                            "${lr.flicks?.size ?: 0} swings, ${lr.micros?.size ?: 0} micros, " +
                                "${lr.phrases?.size ?: 0} phrases, ${lr.sourceTicks} source ticks"
                        }
                    )
                }
            }
            val datasets = AimDatasets.files()
            chat(
                regular("Datasets: "), variable("${datasets.size}"), regular(" in "),
                clickablePath(LearnedFiles.datasetsDir)
            )
            datasets.lastOrNull()?.let { newest ->
                chat(regular("Newest: "), clickablePath(newest))
            }
        }
        .build()

    private fun selectCommand(): Command = CommandBuilder
        .begin("select")
        .parameter(rotationParameter())
        .handler {
            val name = args[0] as String
            if (!LearnedRotations.select(name)) {
                val known = LearnedRotations.names.joinToString(", ")
                throw CommandException("Rotation '$name' not found. Known: $known".asText())
            }
            val switched = KillAuraRotationsValueGroup.selectAngleSmooth(LEARNED_SMOOTHER)
            chat(
                regular("Selected "), variable(name),
                regular(if (switched) " (KillAura AngleSmooth set to Learned)." else ".")
            )
        }
        .build()

    private fun deleteCommand(): Command = CommandBuilder
        .begin("delete")
        .parameter(rotationParameter())
        .handler {
            val name = args[0] as String
            if (!LearnedRotations.delete(name)) {
                throw CommandException("Rotation '$name' not found.".asText())
            }
            chat(regular("Deleted rotation "), variable(name), regular("."))
        }
        .build()

    private fun reloadCommand(): Command = CommandBuilder
        .begin("reload")
        .handler {
            LearnedRotations.load()
            chat(regular("Reloaded "), variable("${LearnedRotations.names.size}"), regular(" learned rotation(s)."))
        }
        .build()

    private fun rotationParameter() = ParameterBuilder
        .begin<String>("rotation")
        .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
        .autocompletedFrom { LearnedRotations.names }
        .required()
        .build()

    private fun describeHandwriting(lr: LearnedRotation): String {
        val style = when {
            lr.smoothness > SMOOTH_ABOVE -> "smooth"
            lr.smoothness < SHARP_BELOW -> "sharp"
            else -> "medium"
        }
        return String.format(
            Locale.US,
            "%s (smoothness %.0f%%, spikes %.0f%%, direction changes %.1f/100t, jerk %.2f, head pitch %.0f +- %.0f)",
            style, lr.smoothness * 100.0, lr.spikeRate * 100.0, lr.dirChangeRate, lr.jerkAmp,
            lr.headPitchMean, lr.headPitchStd
        )
    }

    private const val SMOOTH_ABOVE = 0.55
    private const val SHARP_BELOW = 0.35
    private const val ATTACK_SYNC_MENTION = 0.25
}
