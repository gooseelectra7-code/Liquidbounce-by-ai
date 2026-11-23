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
package net.ccbluex.liquidbounce.deeplearn.clone.eval

import net.ccbluex.liquidbounce.deeplearn.clone.CloneDriftRescue
import net.ccbluex.liquidbounce.deeplearn.clone.CloneModel
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import net.minecraft.world.entity.Entity
import java.util.random.RandomGenerator
import kotlin.math.hypot

/**
 * Whatever is producing the aim that [CloneEval] replays and scores.
 *
 * The two kinds of generator worth scoring disagree about almost everything. A learned model is
 * handed a feature vector and answers with a mouse delta and a click decision; a hand-written
 * [AngleSmooth] is handed two rotations and answers with a third, and has no opinion about
 * clicking at all. The rollout only ever needs a delta per tick, so that is all this asks for —
 * each implementation pays whatever conversion its own generator needs, and the statistics and
 * adversaries downstream never learn which one they are looking at.
 */
interface AimSource : AutoCloseable {

    /** Names the generator in the report, so a run says what produced the numbers. */
    val label: String

    /**
     * Whether [Step.attack] is a decision this source actually took.
     *
     * When false, every click-derived figure in the report describes the recording rather than the
     * source, and the report has to say so rather than present zeros as measurements.
     */
    val decidesAttacks: Boolean

    /**
     * The tick stream breaks here — called before and after each replayed sequence. A source that
     * carries memory across ticks decides whether this is a break it would also see in game.
     */
    fun onDiscontinuity()

    override fun close() = Unit

    /** Generates the movement for one replayed tick. */
    fun step(tick: Context): Step

    /**
     * One replayed tick, offered in both of the forms a generator might want it in: the raw
     * feature row laid out per `FeatureSchema`, and the plain rotations the aim pipeline works in.
     *
     * [features] is scratch owned by the rollout and refilled every tick, so a source is free to
     * standardize it in place.
     */
    class Context(
        val features: FloatArray,
        val current: Rotation,
        val target: Rotation,
        val hasTarget: Boolean,

        /**
         * How far the target was, in blocks from the player's eyes to its box centre, as the
         * recording measured it. Zero when [hasTarget] is false — the schema's sentinel for "there
         * was nothing there", not a target standing on top of the player.
         */
        val distance: Float,
        val gcd: Double
    )

    /**
     * Mouse movement for one tick in degrees plus whether a swing went out.
     *
     * [rawDeltaYaw]/[rawDeltaPitch] are the same movement *before* grid quantization, feeding the
     * off-grid cost statistic. A source that quantizes internally (the clone's session) reports
     * its true pre-grid sample here; for one that emits unquantized degrees the raw values simply
     * equal the deltas — the rollout's own rounding then measures the loss either way.
     */
    class Step(
        val deltaYaw: Float,
        val deltaPitch: Float,
        val attack: Boolean,
        val rawDeltaYaw: Float = deltaYaw,
        val rawDeltaPitch: Float = deltaPitch
    )
}

/**
 * Drives the rollout from a trained clone, which is what the model was built to do: it reads the
 * feature row and samples its own movement and click from the distribution it learned.
 */
class ModelAimSource(
    private val model: CloneModel,
    private val temperature: Float,
    private val random: RandomGenerator,
    private val noiseCorrelation: Float = 0f,
    /** See `CloneModel.Session.step`; `1` is the plain sample the live smoother runs. */
    private val bestOf: Int = 1
) : AimSource {

    private val session = model.newSession()

    /**
     * The same stall detector the live smoother runs, with the same thresholds — without it the
     * report would score a policy that is never deployed, and the one number the drift rescue
     * exists to move (how long the clone stays parked off the target) would be measured against
     * the un-rescued model.
     */
    private val rescue = CloneDriftRescue()

    override val label: String get() = model.name

    override val decidesAttacks: Boolean get() = true

    /**
     * The GRU's hidden state means "this is the third tick of a flick", which is false about the
     * first tick of an unrelated sequence.
     */
    override fun onDiscontinuity() {
        session.reset()
        rescue.reset()
    }

    /**
     * No one-tick buffer here, deliberately, even though the live smoother holds one. The buffer
     * is a *packet-clock* mechanism: at runtime, features are measured at a tick head while the
     * resulting action only reaches the wire in the next tick's movement packet. The rollout has
     * no packet clock — a row IS the (features, action, click) triple of one head, and the state
     * advances by the row's action between rows, which is exactly where the packet boundary sits.
     * Buffering here shifted both halves of every sample one row late, so the generated rows
     * paired each action and click with the *next* row's geometry — a misalignment the real rows
     * do not have, and a free discriminator signal.
     */
    override fun step(tick: AimSource.Context): AimSource.Step {
        // The features are still raw here — the session standardizes its own copy — so the
        // residual channels carry degrees, the same quantity the live smoother feeds its rescue.
        if (tick.hasTarget) {
            val residual = hypot(
                tick.features[FeatureSchema.F_DELTA_YAW],
                tick.features[FeatureSchema.F_DELTA_PITCH]
            )
            if (rescue.shouldReset(residual)) {
                session.reset()
            }
        } else {
            rescue.reset()
        }

        val step = session.step(tick.features, tick.gcd, temperature, random, noiseCorrelation, bestOf)
        return AimSource.Step(
            step.deltaYaw,
            step.deltaPitch,
            step.attack,
            step.rawDeltaYaw,
            step.rawDeltaPitch
        )
    }

    override fun close() = session.close()
}

/**
 * Drives the rollout from an [AngleSmooth], so a hand-written smoother can be held to the same
 * classifiers as a learned one.
 *
 * A smoother returns the absolute rotation it wants to be at, which is the same information as a
 * delta only once the yaw is unwrapped — a turn from 179° to -179° is two degrees, not 358.
 *
 * @param viewer the live player the smoother runs as, and the eyes the target is placed relative to.
 */
class SmoothAimSource(
    private val smooth: AngleSmooth,
    viewer: Entity
) : AimSource {

    /**
     * Stands in for the entity being fought, moved each tick to the range the recording stored.
     *
     * It has to be non-null: a null [RotationTarget.entity] is how the aim pipeline signals "hand
     * the rotation back to the player", and every smoother switches there to a bounded convergence
     * path that is not its combat behaviour. Nothing reads it for the angle to aim at — that is
     * replayed from the recording and passed as the target rotation — so what it is here for is the
     * rest of what a smoother asks the world about its opponent: the distance to it, and the box at
     * the end of that distance.
     *
     * Three things about a real fight are still not replayed, and the report says so rather than
     * presenting their consequences as measurements:
     *  - The attack cooldown comes off the live player instead of the recording. A smoother that
     *    modulates itself by it — Intpol couples its speed to the recharge — is scored at whatever the
     *    user's own weapon happens to be at while the run goes past.
     *  - A hit check made through `isLookingAtEntity` cannot succeed, because that walks the
     *    level's entities and this one is deliberately not in the level. Acceleration and Natural
     *    both make that check, and both read a permanent miss here.
     *  - The eyes are the live player's and keep moving while the run is in flight, so a placement
     *    and the smoother's own reading of it agree only to within a tick of the user's movement.
     *
     * A smoother that picks its own aim point from the entity cannot be replayed at all and is
     * refused before it gets here, see [AngleSmooth.derivesTargetFromEntity].
     */
    private val anchor = TargetDummy.anchoredTo(viewer)

    override val label: String get() = smooth.name

    override val decidesAttacks: Boolean get() = false

    /**
     * Deliberately nothing. A smoother is built once, by the config tree, and lives as long as the
     * client does — the same object carries its pause/movement machine and its per-session identity
     * through every fight of a session. Rebuilding or rewinding it at each sequence boundary would
     * score a smoother that re-rolls itself dozens of times a minute, which is not the one the
     * player is running.
     */
    override fun onDiscontinuity() = Unit

    override fun step(tick: AimSource.Context): AimSource.Step {
        // On a tick with no target the recorded range is a zero sentinel, which lands the box on the
        // player's own eyes. That is the zero distance a smoother has always been shown on those
        // ticks, rather than a range invented for a target that was not being tracked, and it leaves
        // the stretches with nothing to track measuring what they measured before.
        anchor.placeAt(tick.target, tick.distance)

        val result = smooth.process(targetFor(tick.target), tick.current, tick.target)

        return AimSource.Step(
            deltaYaw = angleDifference(result.yaw, tick.current.yaw),
            deltaPitch = result.pitch - tick.current.pitch,

            // A smoother moves the crosshair and nothing else; whatever fires the swing in game is
            // a separate clicker that this harness is not replaying.
            attack = false
        )
    }

    /**
     * Built per tick so [RotationTarget.rotation] agrees with the angle actually being asked for,
     * rather than being a stale value a future smoother might read and quietly aim at.
     */
    private fun targetFor(rotation: Rotation) = RotationTarget(
        rotation = rotation,
        entity = anchor.entity,
        processors = emptyList(),
        ticksUntilReset = TICKS_UNTIL_RESET,
        resetThreshold = RESET_THRESHOLD,
        considerInventory = false,
        movementCorrection = MovementCorrection.SILENT
    )

    private companion object {
        /**
         * Defaults from `RotationsValueGroup`. They only matter to the RotationManager, which is
         * not in the loop here, but a plausible value beats an arbitrary one for anything that
         * starts reading them.
         */
        const val TICKS_UNTIL_RESET = 5
        const val RESET_THRESHOLD = 2f
    }
}
