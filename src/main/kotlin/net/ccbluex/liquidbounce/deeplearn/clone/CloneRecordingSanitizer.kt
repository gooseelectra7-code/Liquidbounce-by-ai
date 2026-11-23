/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.ccbluex.liquidbounce.deeplearn.clone

import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns the recorder's candidate stream into the combat stream the clone is actually deployed on.
 *
 * A nearby entity is not proof that the player is fighting it. TargetTracker can keep somebody in
 * range while the player eats, loots or walks away, and teaching that stretch as aim is what made
 * earlier clones reproduce deliberate turn-aways in the middle of PvP. A kept segment therefore
 * has to sit within [COMBAT_CONTEXT_TICKS] of a real outgoing attack and must not be part of a
 * sustained, slow excursion away from the target. Short, fast excursions are retained: those are
 * the rare emergency turns the model needs rather than noise to clean up.
 */
object CloneRecordingSanitizer {

    class Result(
        val sequences: List<AimSequence>,
        val candidateTicks: Int,
        val keptTicks: Int,
        val disengagedTicks: Int
    )

    fun sanitize(sequence: AimSequence): Result {
        val ticks = sequence.ticks
        if (ticks.isEmpty()) {
            return Result(emptyList(), 0, 0, 0)
        }

        val keep = combatMask(ticks)
        val disengaged = removeDisengagement(keep, ticks, sequence.gcd)
        val result = segments(sequence, keep)

        return Result(
            sequences = result,
            candidateTicks = ticks.size,
            keptTicks = result.sumOf { it.ticks.size },
            disengagedTicks = disengaged
        )
    }

    private fun combatMask(ticks: List<AimTick>): BooleanArray {
        val keep = BooleanArray(ticks.size)
        for (index in ticks.indices) {
            if (!ticks[index].attacked) {
                continue
            }
            val from = (index - COMBAT_CONTEXT_TICKS).coerceAtLeast(0)
            val to = (index + COMBAT_CONTEXT_TICKS).coerceAtMost(ticks.lastIndex)
            for (i in from..to) {
                keep[i] = true
            }
        }

        // Target-less/corrupt rows cannot occur in a valid v5 combat sequence. Treat them as a
        // hard boundary rather than letting their sentinel geometry reach the recurrent state.
        for (i in ticks.indices) {
            keep[i] = keep[i] && valid(ticks[i])
        }
        return keep
    }

    private fun removeDisengagement(keep: BooleanArray, ticks: List<AimTick>, gcd: Double): Int {
        var disengaged = 0
        var start = 0
        while (start < ticks.size) {
            if (!isOpen(ticks[start])) {
                start++
                continue
            }

            var end = start + 1
            while (end < ticks.size && isOpen(ticks[end])) {
                end++
            }

            if (isDisengagement(ticks, gcd, start, end)) {
                for (i in start until end) {
                    // Keep the committed snap that interrupts the disengagement, but start it as
                    // a fresh recurrent segment so the idle state before it cannot leak through.
                    if (stepOf(ticks[i], gcd) < CloneDisengagement.RESCUE_STEP && keep[i]) {
                        keep[i] = false
                        disengaged++
                    }
                }
            }
            start = end
        }
        return disengaged
    }

    private fun isDisengagement(ticks: List<AimTick>, gcd: Double, start: Int, end: Int) =
        end - start >= CloneDisengagement.MIN_TICKS &&
            meanStep(ticks, gcd, start, end) < CloneDisengagement.MAX_MEAN_STEP

    private fun segments(sequence: AimSequence, keep: BooleanArray): List<AimSequence> {
        val ticks = sequence.ticks
        val result = mutableListOf<AimSequence>()
        var segmentStart = 0
        var segmentIndex = 0
        while (segmentStart < ticks.size) {
            while (segmentStart < ticks.size && !keep[segmentStart]) {
                segmentStart++
            }
            if (segmentStart >= ticks.size) {
                break
            }

            var end = segmentStart + 1
            while (end < ticks.size && keep[end]) {
                end++
            }

            val segment = ticks.subList(segmentStart, end)
            if (segment.size >= AimSequence.MIN_LENGTH && segment.any(AimTick::attacked)) {
                // Make independently saved segments distinct even when they originated from the
                // same tick and happen to have the same length (identity uses the timestamp).
                result += sequence.copy(
                    timestamp = sequence.timestamp + segmentIndex,
                    ticks = segment.toList()
                )
                segmentIndex++
            }
            segmentStart = end
        }
        return result
    }

    // targetId != NO_TARGET, not >= 0: a client-only practice dummy (CloneGym's fake player,
    // `.fakeplayer`) carries a negative but valid id. hasTarget already excludes the no-target
    // sentinel row; this only rejects the impossible "has target yet id is the no-target sentinel"
    // state. Requiring a non-negative id here silently discarded every gym drill.
    private fun valid(tick: AimTick) = tick.hasTarget && tick.targetId != NO_TARGET &&
        tick.ownYaw.isFinite() && tick.ownPitch.isFinite() &&
        tick.targetYaw.isFinite() && tick.targetPitch.isFinite() &&
        tick.distance.isFinite() && tick.distance > 0f &&
        tick.targetWidth.isFinite() && tick.targetWidth > 0f &&
        tick.targetHeight.isFinite() && tick.targetHeight > 0f

    private fun isOpen(tick: AimTick) =
        abs(angleDifference(tick.targetYaw, tick.ownYaw)) > CloneDisengagement.OPEN_DEGREES

    private fun stepOf(tick: AimTick, gcd: Double) =
        hypot(tick.dx * gcd, tick.dy * gcd).toFloat()

    private fun meanStep(ticks: List<AimTick>, gcd: Double, start: Int, end: Int): Float {
        var sum = 0.0
        for (i in start until end) {
            sum += stepOf(ticks[i], gcd)
        }
        return (sum / (end - start)).toFloat()
    }

    /** Two seconds either side, enough to retain the approach and the immediate recovery. */
    const val COMBAT_CONTEXT_TICKS = 40

    /** The `AimTick.targetId` sentinel for a row that had no tracked target. */
    private const val NO_TARGET = -1
}
