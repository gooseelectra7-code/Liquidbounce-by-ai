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

package net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.autobow

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.ModuleAutoBow
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.projectiles.ArrowBallistics
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.handItems
import net.ccbluex.liquidbounce.utils.entity.usingItemOrNull
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TridentItem
import kotlin.math.max

/**
 * Aims the drawn bow (crossbow, trident) at an enemy.
 *
 * ## Rotation timing — the last tick before the shot
 *
 * A bow or trident release leaves from `handleKeybinds`, BEFORE `ClientLevel.tickEntities` sends
 * the tick's movement packet (`Minecraft.java:1938` vs `:1949`, the release at `:2111`), so the
 * server steers the arrow with the rotation of the PREVIOUS tick's movement packet. This feature
 * files its request from [RotationUpdateEvent] — RotationManager's FIRST_PRIORITY tick handler,
 * ahead of `update()` — so a request reaches the movement packet of the SAME tick, and the
 * release on the next tick is the first one it can steer. A charged crossbow fires from a use
 * packet that carries this tick's rotation itself (`ServerGamePacketListenerImpl.handleUseItem`
 * snaps the player to it), so its shot can leave in the very tick the rotation is filed — which
 * is also how a crossbow fired from the hotbar ([AutoBowAutoShootFeature.HotbarCrossbow]) is
 * aimed: the rotation, the slot change and the shot all share one tick.
 *
 * With [AutoBowAutoShootFeature] enabled the rotation is filed only once the shot is that close:
 * the bow draws with the player's own view, the aim appears in the tick whose movement packet
 * precedes the release (earlier only by the ticks the configured smoother needs to get there),
 * and AutoShoot releases once the rotation on the wire is one that hits. With AutoShoot disabled
 * this is a continuous aim assist: the release is manual, so the rotation must already be on
 * the solution whenever it comes.
 *
 * ## Resolver and prediction
 *
 * [AutoBowResolver] solves the exact vanilla trajectory ([ArrowBallistics]) from where the
 * projectile will spawn to the centre of the target's hitbox, for the charge the bow will have
 * at the release; [Predict] decides where the target will be by then, starting from the server's
 * own position and velocity of it ([TargetMotionLog]) and leading by exactly the time between
 * that position being read on the server and the arrow spawning there ([predictionFor]).
 *
 * ## Hit chance
 *
 * Vanilla's spread turns every shot by up to a degree per axis, so a rotation that nominally
 * clips the edge of the hitbox lands about half of its shots and one through the centre lands
 * nearly all of them. [hitChance] flies the spread ([ArrowBallistics.hitChance]) and AutoShoot
 * releases on it rather than on the nominal path, which is what stops the shot from leaving on
 * the first tick a smoothed rotation grazes the box - "it fires before the aim has arrived".
 */
object AutoBowAimbotFeature : ToggleableValueGroup(ModuleAutoBow, "BowAimbot", true) {

    val targetTracker = TargetTracker(TargetPriority.DISTANCE)
    private val rotations = RotationsValueGroup(this)

    /**
     * Aim at a target even when blocks cover it. Off (the default), a target is only aimed at —
     * and AutoShoot only releases — when the simulated path reaches it before any block, so there
     * is no rotation at all towards a target behind a wall.
     */
    private val throughWalls by boolean("ThroughWalls", false)

    /**
     * Where on the hitbox to aim, from the feet (0) to the top of the head (1). The middle leaves
     * the most room for error both ways.
     */
    private val aimHeight by float("AimHeight", 0.5F, 0.1F..0.9F)

    init {
        tree(Predict)
        tree(targetTracker)
        tree(rotations)
        tree(TargetRenderer(AutoBowAimbotFeature, targetTracker))
    }

    /**
     * Leads a moving target. Disabled, the shot goes where the server last reported the target.
     */
    private object Predict : ToggleableValueGroup(AutoBowAimbotFeature, "Predict", true) {

        val mode by enumChoice("Mode", PredictMode.SIMULATION)

        /**
         * Leads by the time our shot takes to happen on the server, measured from our own shots
         * ([ShotDelayMeter]; the tab-list ping until one was measured). The TARGET's latency plays
         * no part — the arrow is flown against its server-side position. Off, the target is led
         * only by its own staleness and the flight time, which is short by a round trip.
         */
        val pingCompensation by boolean("PingCompensation", true)

        /**
         * The sub-tick residue of the server's timing, in ticks (see [predictionFor]): the
         * target's position is read by its tracker some way INTO a level tick (after the packet
         * drain that starts the tick, before the entities move), so the whole-tick count is short
         * by that fraction - a few milliseconds of the 50, about 0.2. Tune against a particular
         * server; the timing jitter the hit chance is flown under covers the rest.
         */
        val alignment by float("Alignment", 0.2F, -2.0F..2.0F, "ticks")

        /**
         * Extra lead on top of everything, for tuning against a particular server; negative to
         * lead less.
         */
        val leadTicks by float("LeadTicks", 0.0F, -5.0F..10.0F, "ticks")

        /**
         * Cap on the total look-ahead. No model of another player's input stays honest for long.
         */
        val maxTicks by int("MaxTicks", 40, 5..60, "ticks")

    }

    /**
     * The target's predicted feet position for a release [releaseDelay] ticks from now, as a
     * function of the projectile's flight ticks.
     *
     * The lead - how far past the position we hold the target has moved when the arrow's first
     * tick tests it - is derived in [AutoBowResolver]'s class KDoc:
     * `releaseDelay + sinceReceived + shotDelay + Alignment`, where `sinceReceived` is how long
     * the target's last position packet has been on our side ([TargetMotionLog]), `shotDelay` how
     * long our shot takes to be processed on the server ([ShotDelayMeter]), and `Alignment` the
     * sub-tick residue; the resolver then reads the target one step behind each arrow tick. The
     * downlink latency is in both `sinceReceived`'s reference point and the measured delay and
     * cancels; nothing here assumes a symmetric round trip.
     */
    fun predictionFor(entity: Entity, releaseDelay: Int): PositionExtrapolation {
        val motion = TargetMotionLog.motion(entity)

        if (!Predict.enabled) {
            return AutoBowResolver.predict(entity, motion, null, 0.0, 0.0)
        }

        val shotDelay = if (Predict.pingCompensation) {
            ShotDelayMeter.delayTicks() ?: ShotDelayMeter.fallbackDelayTicks()
        } else {
            0.0
        }
        val lead = releaseDelay + motion.sinceReceivedTicks + shotDelay + Predict.alignment + Predict.leadTicks

        return AutoBowResolver.predict(entity, motion, Predict.mode, lead, Predict.maxTicks.toDouble())
    }

    /**
     * Whether a shot of [stack] released NOW with [rotation] hits [target] on the nominal path —
     * the check AutoShoot releases on when its hit-chance gate is off. With [safeCharge] a bow shot
     * must also hit if the server counted the draw one tick shorter or longer than we did: it
     * counts its own ticks (`ServerGamePacketListenerImpl.tickPlayer` -> `doTick`), so network
     * jitter can shift the charge by one, and below full power one tick is a ~9% speed change
     * (about a metre of drop at 30 blocks).
     */
    fun shotHits(target: Entity, stack: ItemStack, rotation: Rotation, safeCharge: Boolean): Boolean {
        val charge = player.ticksUsingItem
        val info = AutoBowResolver.shotInfo(stack, charge) ?: return false
        val shooter = AutoBowResolver.currentShooter(info)
        val prediction = predictionFor(target, 0)

        if (!AutoBowResolver.hits(
                rotation,
                ArrowBallistics.Physics.of(info),
                shooter,
                target,
                prediction,
                collideBlocks = !throughWalls
            )) {
            return false
        }

        return !safeCharge || isChargeSafe(rotation, stack, charge, shooter, target, prediction)
    }

    /**
     * The chance that a shot of [stack] released NOW with [rotation] hits [target] - under
     * vanilla's spread, the server's timing jitter, and with every other entity that could take
     * the arrow first in the way ([blockersNear]). See the class KDoc.
     */
    fun hitChance(target: Entity, stack: ItemStack, rotation: Rotation): Double {
        val info = AutoBowResolver.shotInfo(stack, player.ticksUsingItem) ?: return 0.0
        val candidates = listOf(AutoBowResolver.Candidate(target, predictionFor(target, 0), wanted = true)) +
            blockersNear(target)

        return AutoBowResolver.hitChance(
            rotation,
            ArrowBallistics.Physics.of(info),
            AutoBowResolver.currentShooter(info),
            candidates,
            collideBlocks = !throughWalls,
            timingJitter = timingJitter()
        )
    }

    /**
     * Whether a bow shot of [stack] released NOW with [rotation] at [target] survives a one-tick
     * charge error either way (see [shotHits]).
     */
    fun chargeSafe(target: Entity, stack: ItemStack, rotation: Rotation): Boolean {
        val charge = player.ticksUsingItem
        val info = AutoBowResolver.shotInfo(stack, charge) ?: return false

        return isChargeSafe(
            rotation,
            stack,
            charge,
            AutoBowResolver.currentShooter(info),
            target,
            predictionFor(target, 0)
        )
    }

    /**
     * Half-width of the uniform timing uncertainty a hit chance is flown under: the measured
     * wander of our shot delay, never below the half tick the server's own processing grid adds.
     */
    fun timingJitter(): Double = ShotDelayMeter.jitterTicks()

    /**
     * Living entities other than [target] that an arrow flown at it could meet first - anything
     * pickable within [BLOCKER_LINE_DISTANCE] of the line from our eyes to the target, each at its
     * own predicted position. `AbstractArrow.canHitEntity` stops at any of them; allied players are
     * skipped by the server (`canHarmPlayer`) but we cannot tell alliances, so they count as in the
     * way, which only ever makes the estimate lower.
     */
    private fun blockersNear(target: Entity): List<AutoBowResolver.Candidate> {
        val eye = player.eyePosition
        val toTarget = target.boundingBox.center.subtract(eye)
        val length = toTarget.length()
        if (length < 1E-3) {
            return emptyList()
        }
        val direction = toTarget.scale(1.0 / length)

        return world.entitiesForRendering().mapNotNull { entity ->
            if (entity === target || entity === player || entity !is LivingEntity ||
                !entity.isAlive || !entity.isPickable) {
                return@mapNotNull null
            }

            val offset = entity.boundingBox.center.subtract(eye)
            val along = offset.dot(direction)
            if (along < 0.0 || along > length + BLOCKER_LINE_DISTANCE) {
                return@mapNotNull null
            }

            val across = offset.subtract(direction.scale(along)).length()
            if (across > BLOCKER_LINE_DISTANCE) {
                return@mapNotNull null
            }

            AutoBowResolver.Candidate(entity, predictionFor(entity, 0), wanted = false)
        }
    }

    /**
     * The rotation this feature planned this tick and the target it planned it for, null on a
     * tick it planned nothing. AutoShoot compares the rotation that will carry the shot against
     * it: the plan is the best aim available this tick, so its hit chance is the ceiling.
     */
    var plannedRotation: Rotation? = null
        private set
    var plannedTarget: LivingEntity? = null
        private set

    /**
     * A shot planned for a release [releaseDelay] ticks from now.
     */
    private class Plan(val rotation: Rotation, val releaseDelay: Int)

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        targetTracker.reset()
        plannedRotation = null
        plannedTarget = null

        // What will be shot: the item in use, else the crossbow AutoShoot fires next (planned with
        // ITS physics - an arrow and a firework bolt fly differently), else a charged crossbow in
        // hand for a manual shot.
        val stack = player.usingItemOrNull
            ?: AutoBowAutoShootFeature.nextCrossbowStack()
            ?: player.handItems.firstOrNull { it.item is CrossbowItem && CrossbowItem.isCharged(it) }
            ?: return@handler
        val item = stack.item

        // A crossbow that is still loading has nothing to aim yet.
        if (item !is BowItem && item !is TridentItem && (item !is CrossbowItem || !CrossbowItem.isCharged(stack))) {
            return@handler
        }

        // The earliest release a rotation filed now can steer (see the class KDoc).
        val earliestRelease = if (item is CrossbowItem) 0 else 1

        // With AutoShoot: the release waits for its charge and delay gates. Without it (or when it
        // does not handle what is held) the release is manual and may come on any tick.
        val gateTicks = if (AutoBowAutoShootFeature.enabled) AutoBowAutoShootFeature.ticksUntilGatesOpen() else null
        val releaseDelay = max(earliestRelease, gateTicks ?: earliestRelease)
        val safeCharge = gateTicks != null && AutoBowAutoShootFeature.safeCharge && item is BowItem

        var plan: Plan? = null
        val enemy = targetTracker.selectFirst { enemy ->
            plan = planShot(enemy, stack, releaseDelay, safeCharge)
            plan != null
        } ?: return@handler

        val shot = plan!!
        plannedRotation = shot.rotation
        plannedTarget = enemy

        // Rotate as late as possible: only in the tick(s) the rotation needs to be on the wire by
        // the release — one tick with an instant smoother.
        if (gateTicks != null && shot.releaseDelay > rotations.calculateTicks(shot.rotation) + 1) {
            return@handler
        }

        RotationManager.setRotationTarget(
            shot.rotation,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = ModuleAutoBow,
            valueGroup = rotations
        )
    }

    /**
     * Solves the shot at [enemy] for a release [releaseDelay] ticks from now. With [safeCharge]
     * the release is pushed back tick by tick until the shot survives a one-tick charge error
     * ([isChargeSafe]); from [FULL_POWER_TICKS] + 1 on it always does.
     */
    private fun planShot(enemy: LivingEntity, stack: ItemStack, releaseDelay: Int, safeCharge: Boolean): Plan? {
        val firstInfo = AutoBowResolver.shotInfo(stack, player.ticksUsingItem + releaseDelay) ?: return null
        val shooter = if (releaseDelay == 0) {
            AutoBowResolver.currentShooter(firstInfo)
        } else {
            AutoBowResolver.nextTickShooter(firstInfo)
        }

        var delay = releaseDelay

        while (true) {
            val charge = player.ticksUsingItem + delay
            val info = AutoBowResolver.shotInfo(stack, charge) ?: return null
            val prediction = predictionFor(enemy, delay)

            val solution = AutoBowResolver.solve(
                enemy,
                prediction,
                ArrowBallistics.Physics.of(info),
                shooter,
                aimHeight.toDouble(),
                requireClearPath = !throughWalls
            ) ?: return null

            if (!safeCharge || charge > FULL_POWER_TICKS ||
                isChargeSafe(solution.rotation, stack, charge, shooter, enemy, prediction)) {
                return Plan(solution.rotation, delay)
            }

            delay++
        }
    }

    /**
     * Whether a bow shot at [rotation], planned for [charge] ticks of drawing, still hits when the
     * server counts one tick less or more.
     */
    private fun isChargeSafe(
        rotation: Rotation,
        stack: ItemStack,
        charge: Int,
        shooter: AutoBowResolver.Shooter,
        target: Entity,
        prediction: PositionExtrapolation,
    ): Boolean {
        if (stack.item !is BowItem || charge > FULL_POWER_TICKS) {
            return true
        }

        return intArrayOf(charge - 1, charge + 1).all { neighbour ->
            val info = AutoBowResolver.shotInfo(stack, neighbour) ?: return false
            AutoBowResolver.hits(
                rotation,
                ArrowBallistics.Physics.of(info),
                shooter,
                target,
                prediction,
                collideBlocks = false
            )
        }
    }

    /**
     * `BowItem.getPowerForTime` reaches 1.0 at exactly this many ticks (`BowItem.MAX_DRAW_DURATION`).
     */
    private const val FULL_POWER_TICKS = BowItem.MAX_DRAW_DURATION

    /**
     * How far off the eye-to-target line an entity may stand to count as a blocker: the spread
     * and the lead can put the arrow about that far from the straight line at typical ranges.
     */
    private const val BLOCKER_LINE_DISTANCE = 3.0

}

/**
 * How [AutoBowAimbotFeature]'s prediction moves the target.
 */
enum class PredictMode(override val tag: String) : Tagged {
    /**
     * Full movement simulation for players (their input guessed from how they move), a straight
     * line for everything else and for a player that is using an item (see
     * [AutoBowResolver.predict]).
     */
    SIMULATION("Simulation"),

    /**
     * A straight line along the current velocity.
     */
    LINEAR("Linear"),
}
