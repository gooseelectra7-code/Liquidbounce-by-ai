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

import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import kotlin.math.asinh
import kotlin.math.sinh

/**
 * The contract between the recorder, the trainer and the inference path.
 *
 * All three have to build the exact same vector from the same situation or the model is fed
 * something it never saw in training. Keeping the layout and the fill routine in one object is
 * what makes that checkable — nothing else is allowed to know the column order.
 */
object FeatureSchema {

    // 5: combat-only rows carry target identity, sensitivity grid and the context that is also
    // available at inference. The output is now one joint 2D action distribution instead of two
    // independent axes, and the click head is conditioned on that sampled action.
    //
    // 6: the mixture predicts the *innovation* to the previous step (`step = prevStep + delta`),
    // not the absolute step. The head only has to say how the movement *changes*, so a continuing
    // motion is `delta ~= 0` and consecutive samples are correlated by construction — the temporal
    // coherence (momentum) the absolute-step model could only learn from scratch and never fully
    // did. Data (`AimSequence.VERSION`) is unchanged, so no re-record — only a retrain: the label
    // math and the sampling differ, and a v5 model read under v6 inference would be wrong, so
    // `schemaVersion` gates them apart.
    //
    // 7: the reparam is damped by a `stepMemory` coefficient lambda: `step = lambda*prevStep +
    // delta`. v6 used lambda = 1, which is an undamped integrator — any small bias in the
    // innovation accumulates and the crosshair runs away into full 360 spins (measured momentum
    // 0.89 vs the player's 0.6). An AR(1) step with |lambda| < 1 is stationary and its lag-1
    // autocorrelation IS lambda, so lambda directly sets the momentum. lambda is a per-model train
    // parameter stored in the metadata; v6 models (lambda = 1) are rejected by the version gate.
    const val VERSION = 7

    /** Number of mixture components per axis. */
    const val K = 3

    const val FEATURES = 26

    // Feature column indices.
    const val F_PREV_DX = 0
    const val F_PREV_DY = 1
    const val F_DELTA_YAW = 2
    const val F_DELTA_PITCH = 3
    const val F_DISTANCE = 4
    const val F_TARGET_VEL_YAW = 5
    const val F_TARGET_VEL_PITCH = 6
    const val F_RADIAL_VEL = 7
    const val F_MOVE_FORWARD = 8
    const val F_MOVE_STRAFE = 9
    const val F_HURT_TIME = 10
    const val F_ON_TARGET = 11
    const val F_ATTACKED_PREV = 12
    const val F_HAS_TARGET = 13
    const val F_OWN_PITCH = 14

    /**
     * Attack cooldown recharge, 0 to 1. The strongest single predictor of when a swing happens in
     * 1.9+ combat, and therefore the input the attack head most needs.
     */
    const val F_COOLDOWN = 15
    const val F_SPRINTING = 16
    const val F_USING_ITEM = 17

    /** Knockback landed on the previous tick — the aim correction that follows is a reaction. */
    const val F_KNOCKED_BACK = 18
    const val F_TARGET_CHANGED = 19

    /** Current sensitivity GCD in degrees per mouse count. */
    const val F_GCD = 20
    const val F_TARGET_WIDTH = 21
    const val F_TARGET_HEIGHT = 22
    const val F_SNEAKING = 23
    const val F_ON_GROUND = 24

    /** Damage landed on the previous tick, paired with the correction that follows it. */
    const val F_TOOK_DAMAGE = 25

    /**
     * Columns that are already 0/1 indicators. Standardizing them would only turn a clean flag
     * into an arbitrary pair of magic numbers, so they are passed through untouched.
     */
    private val INDICATOR_COLUMNS = intArrayOf(
        F_ON_TARGET,
        F_ATTACKED_PREV,
        F_HAS_TARGET,
        F_SPRINTING,
        F_USING_ITEM,
        F_KNOCKED_BACK,
        F_TARGET_CHANGED,
        F_SNEAKING,
        F_ON_GROUND,
        F_TOOK_DAMAGE
    )

    /**
     * Columns that hold a sentinel zero whenever there is no target. Their statistics are fitted
     * over the ticks with a target only: folding the sentinels in dragged every mean towards zero
     * and inflated every spread by however much of the recording was walking between fights, which
     * squashed the z-scores of the combat ticks — the only ticks these columns say anything on.
     * The sentinel itself still standardizes consistently on both sides; it just lands wherever
     * `(0 - mean) / std` falls instead of dictating the scale.
     */
    private val TARGET_COLUMNS = intArrayOf(
        F_DELTA_YAW,
        F_DELTA_PITCH,
        F_DISTANCE,
        F_TARGET_VEL_YAW,
        F_TARGET_VEL_PITCH,
        F_RADIAL_VEL,
        F_HURT_TIME,
        F_TARGET_WIDTH,
        F_TARGET_HEIGHT
    )

    /**
     * Resolves a world-space horizontal velocity into the player's own frame: how fast they are
     * going forwards, and how fast sideways. Inverse of the rotation Minecraft applies to movement
     * input, so `forward`/`strafe` here mean the same thing as the keys the player pressed.
     */
    fun toLocalMovement(deltaX: Double, deltaZ: Double, yaw: Float): FloatArray {
        val radians = Math.toRadians(yaw.toDouble())
        val sin = Math.sin(radians)
        val cos = Math.cos(radians)

        return floatArrayOf(
            (-deltaX * sin + deltaZ * cos).toFloat(),
            (deltaX * cos + deltaZ * sin).toFloat()
        )
    }

    /**
     * Joint head layout.
     *
     * Four movement-state logits represent `still`, `yaw only`, `pitch only` and `both`. That
     * preserves the exact mass on both zero axes without pretending their decisions are
     * independent. The mixture after them is bivariate and shares one component across yaw and
     * pitch: logits, both means, both log sigmas and a correlation per component. Finally, the
     * attack logit has two action coefficients, so clicking is conditioned on the movement that
     * was actually sampled instead of only sharing an upstream hidden state with it.
     */
    const val H_MOVE_STATE = 0
    const val MOVE_STATES = 4
    const val H_ATTACK = H_MOVE_STATE + MOVE_STATES
    const val H_ATTACK_DX = H_ATTACK + 1
    const val H_ATTACK_DY = H_ATTACK_DX + 1
    const val H_MIXTURE = H_ATTACK_DY + 1

    const val H_MIX_LOGITS = H_MIXTURE
    const val H_MIX_MU_X = H_MIX_LOGITS + K
    const val H_MIX_MU_Y = H_MIX_MU_X + K
    const val H_MIX_LOG_SIGMA_X = H_MIX_MU_Y + K
    const val H_MIX_LOG_SIGMA_Y = H_MIX_LOG_SIGMA_X + K
    const val H_MIX_RHO = H_MIX_LOG_SIGMA_Y + K
    const val HEADS = H_MIX_RHO + K

    const val LABELS = 9
    const val L_DX = 0
    const val L_DY = 1
    const val L_MOVE_STATE = 2
    const val L_ATTACKED = L_MOVE_STATE + MOVE_STATES

    /**
     * The previous tick's action, normalized the same way [L_DX]/[L_DY] are (0 when the axis was
     * still). The v6 mixture is scored on the *innovation* `L_DX - L_PREV_DX`, so the head predicts
     * how the step changes from the last one rather than the absolute step; inference adds the same
     * previous step back before de-normalizing. Carried as a label rather than read from the
     * previous row so the loss needs no cross-row lookup, and clean (un-perturbed) so the reparam
     * baseline is the true prior step while the input feature `F_PREV_DX` may be noise-augmented.
     */
    const val L_PREV_DX = L_ATTACKED + 1
    const val L_PREV_DY = L_PREV_DX + 1

    /** Burn-in on overlapping mid-sequence windows; the true sequence start is scored cold. */
    const val WARMUP = 8

    /** Log sigma is clamped so a component cannot collapse onto a single point and blow up the loss. */
    const val LOG_SIGMA_MIN = -5f
    const val LOG_SIGMA_MAX = 2f

    /**
     * Builds one feature row in place.
     *
     * Everything describes the situation at the *start* of the tick, before the action that becomes
     * the label. [prevDxDeg]/[prevDyDeg] are the action of the preceding tick, which is what makes
     * the model able to continue a movement it already started rather than re-deciding every tick.
     */
    @Suppress("LongParameterList", "CognitiveComplexMethod")
    fun fill(
        out: FloatArray,
        prevDxDeg: Float,
        prevDyDeg: Float,
        ownPitch: Float,
        moveForward: Float,
        moveStrafe: Float,
        attackedPrev: Boolean,
        hasTarget: Boolean,
        deltaYaw: Float,
        deltaPitch: Float,
        distance: Float,
        targetVelYaw: Float,
        targetVelPitch: Float,
        radialVel: Float,
        hurtTime: Int,
        onTarget: Boolean,
        cooldown: Float,
        sprinting: Boolean,
        sneaking: Boolean,
        onGround: Boolean,
        usingItem: Boolean,
        knockedBack: Boolean,
        tookDamage: Boolean,
        targetChanged: Boolean,
        gcd: Float,
        targetWidth: Float,
        targetHeight: Float
    ) {
        out[F_PREV_DX] = prevDxDeg
        out[F_PREV_DY] = prevDyDeg
        out[F_MOVE_FORWARD] = moveForward
        out[F_MOVE_STRAFE] = moveStrafe
        out[F_OWN_PITCH] = ownPitch / 90f
        out[F_ATTACKED_PREV] = if (attackedPrev) 1f else 0f
        out[F_HAS_TARGET] = if (hasTarget) 1f else 0f
        out[F_COOLDOWN] = cooldown
        out[F_SPRINTING] = if (sprinting) 1f else 0f
        out[F_SNEAKING] = if (sneaking) 1f else 0f
        out[F_ON_GROUND] = if (onGround) 1f else 0f
        out[F_USING_ITEM] = if (usingItem) 1f else 0f
        out[F_KNOCKED_BACK] = if (knockedBack) 1f else 0f
        out[F_TOOK_DAMAGE] = if (tookDamage) 1f else 0f
        out[F_TARGET_CHANGED] = if (targetChanged) 1f else 0f
        out[F_GCD] = gcd

        if (hasTarget) {
            out[F_DELTA_YAW] = deltaYaw
            out[F_DELTA_PITCH] = deltaPitch
            out[F_DISTANCE] = distance
            out[F_TARGET_VEL_YAW] = targetVelYaw
            out[F_TARGET_VEL_PITCH] = targetVelPitch
            out[F_RADIAL_VEL] = radialVel
            out[F_HURT_TIME] = hurtTime / 10f
            out[F_ON_TARGET] = if (onTarget) 1f else 0f
            out[F_TARGET_WIDTH] = targetWidth
            out[F_TARGET_HEIGHT] = targetHeight
        } else {
            out[F_DELTA_YAW] = 0f
            out[F_DELTA_PITCH] = 0f
            out[F_DISTANCE] = 0f
            out[F_TARGET_VEL_YAW] = 0f
            out[F_TARGET_VEL_PITCH] = 0f
            out[F_RADIAL_VEL] = 0f
            out[F_HURT_TIME] = 0f
            out[F_ON_TARGET] = 0f
            out[F_TARGET_WIDTH] = 0f
            out[F_TARGET_HEIGHT] = 0f
        }
    }

    /**
     * The target-relative part of a row, derived from the current and previous target geometry.
     * Angular velocities are differences of *absolute* target angles, so they describe the target's
     * own motion and stay independent of where the player happens to be looking.
     */
    @Suppress("LongParameterList")
    fun fillFromGeometry(
        out: FloatArray,
        prevDxDeg: Float,
        prevDyDeg: Float,
        ownYaw: Float,
        ownPitch: Float,
        moveForward: Float,
        moveStrafe: Float,
        attackedPrev: Boolean,
        hasTarget: Boolean,
        targetYaw: Float,
        targetPitch: Float,
        distance: Float,
        hadPrevTarget: Boolean,
        prevTargetYaw: Float,
        prevTargetPitch: Float,
        prevDistance: Float,
        hurtTime: Int,
        onTarget: Boolean,
        cooldown: Float,
        sprinting: Boolean,
        sneaking: Boolean,
        onGround: Boolean,
        usingItem: Boolean,
        knockedBack: Boolean,
        tookDamage: Boolean,
        targetChanged: Boolean,
        gcd: Float,
        targetWidth: Float,
        targetHeight: Float
    ) {
        val trackable = hasTarget && hadPrevTarget && !targetChanged
        fill(
            out = out,
            prevDxDeg = prevDxDeg,
            prevDyDeg = prevDyDeg,
            ownPitch = ownPitch,
            moveForward = moveForward,
            moveStrafe = moveStrafe,
            attackedPrev = attackedPrev,
            hasTarget = hasTarget,
            deltaYaw = angleDifference(targetYaw, ownYaw),
            deltaPitch = angleDifference(targetPitch, ownPitch),
            distance = distance,
            targetVelYaw = if (trackable) angleDifference(targetYaw, prevTargetYaw) else 0f,
            targetVelPitch = if (trackable) angleDifference(targetPitch, prevTargetPitch) else 0f,
            radialVel = if (trackable) distance - prevDistance else 0f,
            hurtTime = hurtTime,
            onTarget = onTarget,
            cooldown = cooldown,
            sprinting = sprinting,
            sneaking = sneaking,
            onGround = onGround,
            usingItem = usingItem,
            knockedBack = knockedBack,
            tookDamage = tookDamage,
            targetChanged = targetChanged,
            gcd = gcd,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    /**
     * A block of already-built rows, so statistics can be taken without copying the dataset.
     */
    interface RowSource {
        val rowCount: Int

        /** [rowCount] rows of [FEATURES] floats, raw. */
        val featureRows: FloatArray

        /** [rowCount] rows of two floats: the yaw and pitch step in degrees. */
        val actionRows: FloatArray
    }

    /**
     * Per-column standardization, fitted on the training split only and shipped with the model.
     *
     * [actionMean]/[actionStd] standardize the *labels* and are fitted over non-zero steps alone:
     * the zeros are already carried by the hurdle head, and letting them into the statistics would
     * shrink the scale towards a spike the mixture never has to represent.
     */
    class Normalization(
        val mean: FloatArray,
        val std: FloatArray,
        val actionMean: FloatArray,
        val actionStd: FloatArray,
        /**
         * Degrees at which the step scale turns logarithmic, or `0` for plain degrees.
         *
         * Recorded steps are heavy-tailed — measured 2026-09-28 on the `default` profile, yaw
         * kurtosis 15 with half the steps inside ±0.25σ and flicks out to 16σ — so one linear
         * scale spends the mixture's resolution on the flicks and gives every component a noise
         * floor sized for them. `s·asinh(v/s)` is linear below `s` and logarithmic above it, which
         * keeps the small corrections exact while pulling the flicks in. Shipped with the model:
         * the heads are fitted in this coordinate system and mean nothing in another.
         */
        val actionScale: Float = 0f
    ) {

        fun applyFeatures(row: FloatArray, offset: Int = 0) {
            for (i in 0 until FEATURES) {
                // Clamped, because a value outside the training range would otherwise arrive as an
                // enormous input and swamp every other feature through the first layer. Saturating
                // says "further than anything I was trained on", which is true and harmless;
                // letting it through says nothing at all and destroys the output.
                row[offset + i] = ((row[offset + i] - mean[i]) / std[i])
                    .coerceIn(-CLAMP, CLAMP)
            }
        }

        fun normalizeAction(value: Float, axis: Int) =
            (compress(value, actionScale) - actionMean[axis]) / actionStd[axis]

        fun denormalizeAction(value: Float, axis: Int) =
            expand(value * actionStd[axis] + actionMean[axis], actionScale)

        companion object {
            /**
             * Bound on `u / s` before `sinh`: past it the step is thousands of degrees, which the
             * sampler's max-step clamp cuts to size anyway, and `sinh` would otherwise overflow to
             * infinity on a wild sample and fail the step outright.
             */
            private const val EXPAND_LIMIT = 8f

            fun compress(value: Float, scale: Float): Float =
                if (scale <= 0f) value else scale * asinh(value / scale)

            fun expand(value: Float, scale: Float): Float =
                if (scale <= 0f) value else scale * sinh((value / scale).coerceIn(-EXPAND_LIMIT, EXPAND_LIMIT))

            /** How many standard deviations from the training mean an input may reach. */
            private const val CLAMP = 8f

            /**
             * Below this a column did not really vary during training, and dividing by its own
             * spread would turn it into an amplifier: `hurtTime` recorded as a constant zero and
             * then met at 3 in a real fight arrives as 3000, which alone decides the output.
             *
             * Such a column carries no information, so it keeps its natural scale instead — an
             * unseen value is then merely unusual rather than catastrophic. This was a real fault:
             * clones trained on recordings where the player never damaged the target aimed wildly
             * the moment they landed a hit.
             */
            private const val MIN_INFORMATIVE_STD = 0.05f

            /**
             * Fits mean/std over every row in [sources].
             *
             * Takes the blocks as they already exist rather than a single concatenated array —
             * a long recording is tens of megabytes and there is no reason to copy it just to
             * take an average of it.
             */
            fun fit(sources: List<RowSource>, actionScale: Float = 0f): Normalization {
                val mean = FloatArray(FEATURES)
                val std = FloatArray(FEATURES) { 1f }
                val actionMean = FloatArray(2)
                val actionStd = FloatArray(2) { 1f }

                val total = sources.sumOf { it.rowCount }
                if (total == 0) {
                    return Normalization(mean, std, actionMean, actionStd, actionScale)
                }

                for (i in 0 until FEATURES) {
                    val targetOnly = i in TARGET_COLUMNS

                    var sum = 0.0
                    var counted = 0L
                    for (source in sources) {
                        val rows = source.featureRows
                        for (r in 0 until source.rowCount) {
                            if (targetOnly && rows[r * FEATURES + F_HAS_TARGET] != 1f) {
                                continue
                            }
                            sum += rows[r * FEATURES + i]
                            counted++
                        }
                    }
                    if (counted == 0L) {
                        continue
                    }
                    val m = sum / counted

                    var variance = 0.0
                    for (source in sources) {
                        val rows = source.featureRows
                        for (r in 0 until source.rowCount) {
                            if (targetOnly && rows[r * FEATURES + F_HAS_TARGET] != 1f) {
                                continue
                            }
                            val d = rows[r * FEATURES + i] - m
                            variance += d * d
                        }
                    }

                    val deviation = Math.sqrt(variance / counted).toFloat()
                    mean[i] = m.toFloat()
                    std[i] = if (deviation < MIN_INFORMATIVE_STD) 1f else deviation
                }

                for (index in INDICATOR_COLUMNS) {
                    mean[index] = 0f
                    std[index] = 1f
                }

                for (axis in 0 until 2) {
                    var sum = 0.0
                    var n = 0
                    for (source in sources) {
                        val actions = source.actionRows
                        for (r in 0 until source.rowCount) {
                            val v = actions[r * 2 + axis]
                            if (v != 0f) {
                                sum += compress(v, actionScale)
                                n++
                            }
                        }
                    }
                    if (n == 0) {
                        continue
                    }
                    val m = sum / n

                    var variance = 0.0
                    for (source in sources) {
                        val actions = source.actionRows
                        for (r in 0 until source.rowCount) {
                            val v = actions[r * 2 + axis]
                            if (v != 0f) {
                                val d = compress(v, actionScale) - m
                                variance += d * d
                            }
                        }
                    }

                    val deviation = Math.sqrt(variance / n).toFloat()
                    actionMean[axis] = m.toFloat()
                    actionStd[axis] = if (deviation < MIN_INFORMATIVE_STD) 1f else deviation
                }

                return Normalization(mean, std, actionMean, actionStd, actionScale)
            }
        }
    }
}
