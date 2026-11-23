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

import net.ccbluex.liquidbounce.deeplearn.clone.eval.CloneEval
import net.ccbluex.liquidbounce.deeplearn.clone.eval.HitboxHits
import net.ccbluex.liquidbounce.deeplearn.clone.eval.ShardSimulation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable

/** One `  label: value` line, the register every part of `.clone` reports in. */
internal fun line(label: String, value: String) {
    chat(regular("  $label: "), variable(value))
}

/**
 * Renders a [CloneEval.Report] into chat.
 *
 * Split from the command itself only because the command was getting long; nothing here decides
 * anything, it just says what was measured.
 */
internal object CloneReportPrinter {

    private const val TICKS_PER_MINUTE = 20 * 60

    /** Range inside which a swing could still have reached the box the crosshair was on. */
    private const val HITBOX_REACH = 4

    /**
     * @param profileTicks everything the profile holds, used only to say how far short it falls
     *   when the classifier cannot run.
     */
    fun print(report: CloneEval.Report, profileTicks: Int) {
        report.temperature?.let { temperature ->
            line("Temperature", "%.2f — the aim was sampled at this, the clicks are not scaled by it"
                .format(temperature))
        }
        if (report.aimPoint != CloneEval.CENTRE_AIM_POINT) {
            line(
                "Aim point",
                ("%.2f of the hitbox height, not the %.2f centre the recorder labelled against — " +
                    "every figure below still measures the error to the centre, so this run says " +
                    "what a displaced anchor costs").format(
                    report.aimPoint,
                    CloneEval.CENTRE_AIM_POINT
                )
            )
        }
        printMotion(report)
        printClicks(report)
        printRhythm(report)
        line(
            "Ray in hitbox",
            "player %.1f%%, clone %.1f%% of %d tracked ticks; inside %d blocks %.1f%% / %.1f%%".format(
                report.realHitbox.overall * 100f,
                report.cloneHitbox.overall * 100f,
                report.realHitbox.samples,
                HITBOX_REACH,
                report.realHitbox.within(HITBOX_REACH) * 100f,
                report.cloneHitbox.within(HITBOX_REACH) * 100f
            )
        )
        line("Ray in hitbox by range", HitboxHits.byRange(report.realHitbox, report.cloneHitbox))
        line(
            "Path efficiency",
            "player %.2f, clone %.2f (travel per degree closed; 1.0 = a straight line)".format(
                report.realPathEfficiency,
                report.clonePathEfficiency
            )
        )
        printConvergence(report)
        // A continuous sample almost never lands exactly on a count, so a large share here is
        // normal — the quantizer absorbs the remainder and the wire stays exact. The old label
        // said "must be 0", which was true only while this counted the already-quantized output.
        line(
            "Grid rounding",
            "%d of %d raw samples sat off the count grid before quantization (absorbed; the wire is exact)"
                .format(report.offGridSteps, report.ticks)
        )
        printAdversaries(report, profileTicks)
    }

    /**
     * The longitudinal half of the report: not whether the steps look right but whether the aim
     * arrives and stays. These are the numbers behind "the clone drifts off the target" — a stream
     * can pass every distribution check while losing approaches the player always lands.
     */
    private fun printConvergence(report: CloneEval.Report) {
        val real = report.realConvergence
        val clone = report.cloneConvergence

        line(
            "Residual",
            "player %.1f°/%.1f°, clone %.1f°/%.1f° (median/p90 off the box centre)".format(
                real.medianResidual, real.p90Residual,
                clone.medianResidual, clone.p90Residual
            )
        )
        line(
            "Stalls",
            "player p95 %d ticks (worst %d), clone p95 %d (worst %d) — no-progress runs past 15°"
                .format(real.stallP95, real.stallMax, clone.stallP95, clone.stallMax)
        )
        line(
            "Approaches 15°→3°",
            "player lands in %s ticks, loses %.1f%% of %d; clone %s ticks, loses %.1f%% of %d"
                .format(
                    formatTicks(real.medianTicksToLand), real.lostShare * 100f, real.approaches,
                    formatTicks(clone.medianTicksToLand), clone.lostShare * 100f, clone.approaches
                )
        )
        line(
            "Pursuit lag",
            ("player %s, clone %s ticks behind the target (50 ms each; effective lag — " +
                "reaction time plus whatever tracking gain fails to close)").format(
                formatTicks(real.lagTicks),
                formatTicks(clone.lagTicks)
            )
        )
    }

    private fun formatTicks(value: Float) = if (value.isNaN()) "-" else "%.0f".format(value)

    private fun printMotion(report: CloneEval.Report) {
        line(
            "Still ticks",
            "player %.1f%% / %.1f%%, clone %.1f%% / %.1f%%".format(
                report.realYaw.zeroFraction * 100f,
                report.realPitch.zeroFraction * 100f,
                report.cloneYaw.zeroFraction * 100f,
                report.clonePitch.zeroFraction * 100f
            )
        )
        line(
            "Mean step",
            "player %.3f° / %.3f°, clone %.3f° / %.3f°".format(
                report.realYaw.meanAbsStep,
                report.realPitch.meanAbsStep,
                report.cloneYaw.meanAbsStep,
                report.clonePitch.meanAbsStep
            )
        )
        line("Step distribution", "KS yaw %.3f, pitch %.3f (lower is closer)"
            .format(report.ksYaw, report.ksPitch))
        line(
            "Momentum (lag 1)",
            "player %.3f / %.3f, clone %.3f / %.3f".format(
                report.realYaw.autocorrelation[0],
                report.realPitch.autocorrelation[0],
                report.cloneYaw.autocorrelation[0],
                report.clonePitch.autocorrelation[0]
            )
        )
        line("Axis coupling", "player %.3f, clone %.3f"
            .format(report.realAxisCorrelation, report.cloneAxisCorrelation))
    }

    /**
     * Click rate belongs to whoever decided the swings. When that was not the generator, saying so
     * is the whole point — a zero here would read as "never swings", which is a measurement, and
     * this is the absence of one.
     */
    private fun printClicks(report: CloneEval.Report) {
        if (report.decidesAttacks) {
            line(
                "Click rate",
                "player %.1f cps, clone %.1f cps".format(
                    report.realClicks.clicksPerSecond,
                    report.cloneClicks.clicksPerSecond
                )
            )
        } else {
            line(
                "Click rate",
                "not measured — this source only moves the crosshair. The recording's own swings " +
                    "were replayed onto its aim, so the two figures below say where its crosshair " +
                    "sat when the player clicked, not when it would have"
            )
        }

        line(
            "Angle at click",
            "player %.2f°, clone %.2f° (median)".format(
                report.realClicks.medianAngleAtClick,
                report.cloneClicks.medianAngleAtClick
            )
        )
        line(
            "Swings off target",
            "player %.1f%%, clone %.1f%%".format(
                report.realClicks.missRate * 100f,
                report.cloneClicks.missRate * 100f
            )
        )
        line(
            "Aim point at swing",
            "player %+.2f/%+.2f ±%.2f/%.2f, clone %+.2f/%+.2f ±%.2f/%.2f (hitbox fractions)"
                .format(
                    report.realAimPoint.meanX, report.realAimPoint.meanY,
                    report.realAimPoint.spreadX, report.realAimPoint.spreadY,
                    report.cloneAimPoint.meanX, report.cloneAimPoint.meanY,
                    report.cloneAimPoint.spreadX, report.cloneAimPoint.spreadY
                )
        )
    }

    /**
     * Where the swings sit rather than how many there are.
     *
     * Both of these are what an attack-window-centred anticheat cuts its features around, so a
     * clone matching the click rate and nothing else is matching the one number that costs nothing
     * to match. A recording's gaps pile up at the weapon's recharge and hold almost nothing below
     * it; its swings almost all come off a charged weapon.
     */
    private fun printRhythm(report: CloneEval.Report) {
        val real = report.realRhythm
        val clone = report.cloneRhythm
        val buckets = CloneEval.INTERVAL_LABELS.joinToString("/")

        if (!report.decidesAttacks) {
            // The generated stream is carrying the recording's own swings, so the two sides hold
            // the same numbers. Show the recording's shape once, as what a clone has to reproduce.
            line("Swing gaps", "%s over ticks %s (%d gaps in the recording)"
                .format(percentages(real.intervals), buckets, real.gaps))
            line("Swings by cooldown", "%s of ticks within %.0f blocks, by cooldown fifth"
                .format(percentages(real.byCooldown), CloneEval.COOLDOWN_RANGE))
            return
        }

        line(
            "Swing gaps",
            "player %s, clone %s over ticks %s (%d / %d gaps)".format(
                percentages(real.intervals),
                percentages(clone.intervals),
                buckets,
                real.gaps,
                clone.gaps
            )
        )
        line(
            "Swings by cooldown",
            "player %s, clone %s of ticks within %.0f blocks, by cooldown fifth".format(
                percentages(real.byCooldown),
                percentages(clone.byCooldown),
                CloneEval.COOLDOWN_RANGE
            )
        )
    }

    private fun percentages(fractions: FloatArray) =
        fractions.joinToString("/") { if (it.isNaN()) "-" else "%.2f".format(it * 100f) } + "%"

    private fun printAdversaries(report: CloneEval.Report, profileTicks: Int) {
        val auc = report.discriminator?.auc
        line("Discriminator", auc?.let { "AUC %.3f".format(it) } ?: "not enough held-out data")
        chat(regular("Verdict: "), variable(report.verdict))

        report.trees?.let { trees ->
            line("Tree ensemble", "AUC %.3f over %d windows".format(trees.auc, trees.windows))
            if (trees.topFeatures.isNotEmpty()) {
                line(
                    "Most telling",
                    trees.topFeatures.joinToString(", ") {
                        "%s %.0f%%".format(it.first, it.second * 100f)
                    }
                )
            }
            chat(regular("Tree verdict: "), variable(trees.verdict))
        }

        printShard(report.shardV1, "rotation deltas only, 40-tick sliding windows")
        printShard(report.shardV2, "attack-centred 32+32 windows, aim in mouse counts")

        if (auc == null) {
            // The discriminator is the only part of this that answers the real question, so say
            // plainly what it would take to run it rather than leaving a blank line.
            val needed = CloneEval.ticksNeededForDiscriminator()
            chat(
                regular("The classifier needs about "),
                variable("$needed"),
                regular(" recorded ticks (~"),
                variable("${needed / TICKS_PER_MINUTE} min"),
                regular(") to run; this profile has "),
                variable("$profileTicks"),
                regular(".")
            )
        }
    }

    private fun printShard(report: ShardSimulation.Report?, description: String) {
        if (report == null) {
            return
        }

        chat(regular("Against Shard "), variable(report.version.label), regular(" ($description):"))
        line("Classifier", "AUC %.3f over %d windows".format(report.auc, report.windows))
        line(
            "Over their threshold",
            "%.1f%% of windows, longest run %d".format(
                report.thresholdFraction * 100f,
                report.longestRun
            )
        )
        line(
            "Violation level",
            "peaked at %.1f of %.0f%s".format(
                report.peakBuffer,
                ShardSimulation.FLAG,
                report.secondsToFirstFlag?.let { " — flagged after ~%.1fs".format(it) } ?: ""
            )
        )
        chat(regular("Shard ${report.version.label} verdict: "), variable(report.verdict))
    }
}
