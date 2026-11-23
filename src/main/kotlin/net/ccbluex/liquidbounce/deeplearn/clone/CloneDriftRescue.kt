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
package net.ccbluex.liquidbounce.deeplearn.clone

/**
 * Detects the one failure a sampled policy has and a recording never does: the aim stops making
 * progress and stays that way.
 *
 * A generative clone samples from `p(step | state)`, and the state includes its own recurrent
 * memory. When drift or extrapolation lands that memory in a region the training data barely
 * covers, the distribution being sampled stops describing an engaged hand — the symptom in play is
 * the crosshair parking off the target, or wandering further away, for seconds. The recording says
 * how long an engaged hand ever does that: excursions past 45° that come back do so within 12-13
 * ticks on 95% of occasions, and the committed turn closes 16-21° per tick — while the sustained
 * no-progress stretches were disengagement, which the sanitizer deliberately cut from training.
 * So "no progress at a meaningful angle for [STALL_TICKS]" is a state the deployed policy was
 * never even taught, and staying in it is pure failure, not style.
 *
 * The intervention is a recurrent-state reset and nothing else. No hand-written correction ever
 * reaches the wire — the next step is still sampled from the model, only from the cold state it
 * was trained to open engagements with (sanitised sequences begin up to two seconds before the
 * first attack, at arbitrary residuals, and are scored cold — so "fresh state, target at 30°" is
 * squarely inside the training distribution, unlike whatever the stuck memory had become). The
 * features are left truthful; only the memory is re-dealt.
 *
 * Both the live smoother and `.clone eval`'s rollout run this with the same thresholds, so the
 * evaluation scores the policy exactly as deployed.
 */
class CloneDriftRescue {

    private var stallTicks = 0
    private var lastResidual = Float.NaN

    /** Call on any discontinuity the caller already treats as a stream break. */
    fun reset() {
        stallTicks = 0
        lastResidual = Float.NaN
    }

    /**
     * Feeds one tick's residual angle to the target and says whether the recurrent state should be
     * cleared. Returns true at most once per stall — the counter restarts after a trigger.
     */
    fun shouldReset(residual: Float): Boolean {
        val previous = lastResidual
        lastResidual = residual

        // Near the target, holding still is a real technique (waiting for a strafing target to
        // walk into the crosshair), so nothing below the floor can ever count as a stall.
        if (!residual.isFinite() || residual < STALL_MIN_ANGLE) {
            stallTicks = 0
            return false
        }

        // Any real progress restarts the clock. A strafing target makes the residual oscillate,
        // and the closing half of each swing lands here.
        if (previous.isNaN() || previous - residual > STALL_PROGRESS) {
            stallTicks = 0
            return false
        }

        if (++stallTicks < STALL_TICKS) {
            return false
        }

        reset()
        return true
    }

    companion object {
        /**
         * Residual below which a stall cannot exist. Half the disengagement threshold: between
         * here and 45° a real hand is sometimes patient, but a full second of zero progress is
         * still nothing the sanitised training data contains.
         */
        const val STALL_MIN_ANGLE = 15f

        /** Degrees of closing per tick that count as progress — above PointTracker jitter. */
        const val STALL_PROGRESS = 0.5f

        /**
         * The recording's returning excursions are over within 12-13 ticks at the 95th percentile,
         * and the sanitizer's own disengagement rule starts at 12 — this sits just beyond both, so
         * a trigger means the policy is somewhere the player never was. Note the progress test
         * above makes this conservative in practice: a strafing target hands out free "progress"
         * ticks that keep resetting the clock, so only a genuinely stuck state ever counts to the
         * end. Was 20; lowered so a ghost turn costs well under a second before the state is
         * re-dealt.
         */
        const val STALL_TICKS = 14
    }
}
