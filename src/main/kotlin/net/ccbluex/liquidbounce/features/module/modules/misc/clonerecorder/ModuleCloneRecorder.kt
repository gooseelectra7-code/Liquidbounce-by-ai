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
package net.ccbluex.liquidbounce.features.module.modules.misc.clonerecorder

import net.ccbluex.liquidbounce.config.gson.adapter.toUnderlinedString
import net.ccbluex.liquidbounce.config.gson.publicGson
import net.ccbluex.liquidbounce.deeplearn.clone.CloneCombatSignals
import net.ccbluex.liquidbounce.deeplearn.clone.CloneRecordingSanitizer
import net.ccbluex.liquidbounce.deeplearn.clone.FeatureSchema
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimSequence
import net.ccbluex.liquidbounce.deeplearn.clone.data.AimTick
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.waypoints.MapIdentity
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import net.ccbluex.liquidbounce.utils.client.FloatValueProvider
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.clickablePath
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.minecraft.world.entity.LivingEntity
import java.time.LocalDateTime
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Records the player's own aim so a clone can be trained from it.
 *
 * Ordinary misses, overshoots and fast corrective turns are kept untouched. Rows outside a real
 * fight are not: a target merely remaining in scan range while the player turns away is a different
 * task, and teaching it as aim made the clone reproduce the disengagement during PvP. Finished
 * candidate runs are therefore cut to real attack-centred combat and sanitised before they reach
 * disk; anything recorded while a module steers the rotation is rejected at capture time.
 */
object ModuleCloneRecorder : ClientModule("CloneRecorder", ModuleCategories.MISC, disableOnQuit = true) {

    init {
        // Recording is a deliberate, per-session act; it has no business being in a shared config.
        doNotIncludeAlways()
    }

    private val profile by text("Profile", "default")

    private val targetTracker = tree(
        TargetTracker(
            defaultPriority = TargetPriority.DIRECTION,
            rangeValue = FloatValueProvider("Range", 8f, 3f..12f)
        )
    )

    private val gym = tree(CloneGym)

    /**
     * The `attackedEntityId` value that means "an attack packet went out with no AttackEntityEvent
     * behind it" — a direct/automated send rather than a vanilla click. Must match
     * `CloneCombatSignals.NO_ENTITY`. A real or client-only target id (including a negative fake
     * player id) is never this, so it is not mistaken for the sentinel.
     */
    private const val NO_ATTACK_ENTITY = -1

    /** Flush this often so a crash costs at most a few minutes of recording. */
    private const val FLUSH_INTERVAL = 5_000

    /** Fraction of a grid step a delta may sit off before it counts as contaminated. */
    private const val GRID_TOLERANCE = 0.05

    /** A handful of stray ticks is float noise; a steady stream is another module. */
    private const val GRID_WARN_AFTER = 40

    /** Dropped ticks before saying that something else is aiming — a second of it is deliberate. */
    private const val STEERED_WARN_AFTER = 20

    private val sequences = mutableListOf<AimSequence>()
    private val ticks = mutableListOf<AimTick>()

    private var sinceFlush = 0
    private var sanitizedAway = 0
    private var disengagedAway = 0

    /** State captured at the start of the previous tick, waiting for its action to be known. */
    private var pending: Pending? = null
    private var sequenceGcd = 0.0

    /** [AimSequence.source] of the sequence being recorded; a change closes it, like a GCD change. */
    private var sequenceSource: String? = null

    /** Serializes background writes so two flushes cannot interleave in the profile folder. */
    private val flushLock = Any()

    /** Ticks whose movement did not land on the sensitivity grid — see [checkGridPurity]. */
    private var offGridTicks = 0
    private var gridWarned = false

    /** Ticks dropped because a module was driving the rotation — see [warnIfSteered]. */
    private var steeredTicks = 0
    private var steeredWarned = false

    @Suppress("LongParameterList")
    private class Pending(
        val rotation: Rotation,
        val targetYaw: Float,
        val targetPitch: Float,
        val distance: Float,
        val hurtTime: Int,
        val targetWidth: Float,
        val targetHeight: Float,
        val targetId: Int,
        val moveForward: Float,
        val moveStrafe: Float,
        val onTarget: Boolean,
        val hasTarget: Boolean,
        val attackCooldown: Float,
        val sprinting: Boolean,
        val sneaking: Boolean,
        val onGround: Boolean,
        val usingItem: Boolean
    )

    override fun onEnabled() {
        sequences.clear()
        ticks.clear()
        sinceFlush = 0
        sanitizedAway = 0
        disengagedAway = 0
        offGridTicks = 0
        gridWarned = false
        steeredTicks = 0
        steeredWarned = false
        pending = null
        sequenceGcd = RotationUtil.gcd
        sequenceSource = null

        chat(regular("Recording profile "), variable(profile), regular("..."))
    }

    override fun onDisabled() {
        breakSequence()
        flush(final = true)
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        breakSequence()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // All three combat signals come from the shared object, so the recorder and the live aim
        // path read the same quantities — the attack flag counts real ServerboundAttackPackets
        // that survived cancellation, which is exactly what the label has to mean.
        val attackedDuringLastTick = CloneCombatSignals.attackedLastTick
        val attackedEntityDuringLastTick = CloneCombatSignals.attackedEntityIdLastTick
        val velocityDuringLastTick = CloneCombatSignals.receivedVelocity
        val damageDuringLastTick = CloneCombatSignals.tookDamage

        // A rotation the player did not produce is not a sample of the player. This is the one
        // guard that has to hold, because a poisoned profile is not visible in the data — it only
        // shows up later as a clone that imitates the previous smoother.
        //
        // Counted and announced, because it is silent otherwise and looks like nothing else: an
        // aura steers the rotation on exactly the ticks it attacks on, so the swings land in the
        // ticks this drops while the gaps between them survive with no attack in them. The whole
        // recording is then thrown away by the attack-centred sanitizer, and from the outside it
        // reads as "my hits do not count".
        if (RotationManager.currentRotation != null) {
            steeredTicks++
            warnIfSteered()
            breakSequence()
            return@handler
        }

        if (mc.screen != null || player.isDeadOrDying) {
            breakSequence()
            return@handler
        }

        val gcd = RotationUtil.gcd
        if (gcd != sequenceGcd) {
            breakSequence()
            sequenceGcd = gcd
        }

        val target = gym.target ?: targetTracker.selectFirst()

        // One sequence, one source: a drill against a dummy is set aside at training time (see
        // AimSequence.isPractice), so it must never share a sequence with a real fight. The pending
        // tick is dropped with the break — its action straddles the two.
        val source = sourceOf(target)
        if (source != sequenceSource) {
            breakSequence()
            sequenceSource = source
        }

        val eyes = player.eyePosition
        val rotation = player.rotation

        commitPending(
            rotation,
            gcd,
            attackedDuringLastTick,
            attackedEntityDuringLastTick,
            velocityDuringLastTick,
            damageDuringLastTick
        )

        val targetRotation = target?.let { Rotation.lookingAt(point = it.box.center, from = eyes) }
        val movement = FeatureSchema.toLocalMovement(
            player.x - player.lastPos.x,
            player.z - player.lastPos.z,
            rotation.yaw
        )

        pending = Pending(
            rotation = rotation,
            targetYaw = targetRotation?.yaw ?: 0f,
            targetPitch = targetRotation?.pitch ?: 0f,
            distance = target?.let { eyes.distanceTo(it.box.center).toFloat() } ?: 0f,
            hurtTime = target?.hurtTime ?: 0,
            targetWidth = target?.box?.let { (it.maxX - it.minX).toFloat() } ?: 0f,
            targetHeight = target?.box?.let { (it.maxY - it.minY).toFloat() } ?: 0f,
            targetId = target?.id ?: -1,
            moveForward = movement[0],
            moveStrafe = movement[1],
            onTarget = target != null && isInCrosshair(target, rotation),
            hasTarget = target != null,
            attackCooldown = player.getAttackStrengthScale(0f),
            sprinting = player.isSprinting,
            sneaking = player.isShiftKeyDown,
            onGround = player.onGround(),
            usingItem = player.isUsingItem
        )

        // Completed sequences go to disk, but the one in progress keeps running — chopping it here
        // would manufacture breaks every few minutes and destroy exactly the long-range continuity
        // a session-drift model would need.
        if (sinceFlush >= FLUSH_INTERVAL) {
            flush(final = false)
        }
    }

    /**
     * Turns the state captured last tick into a sample, now that the movement that followed it is
     * known. The action is the *difference* between the two rotations, quantized back into whole
     * mouse counts — which is what the player's hardware actually produced.
     */
    @Suppress("LongParameterList")
    private fun commitPending(
        current: Rotation,
        gcd: Double,
        attacked: Boolean,
        attackedEntityId: Int,
        receivedVelocity: Boolean,
        tookDamage: Boolean
    ) {
        val previous = pending ?: return

        val deltaYaw = angleDifference(current.yaw, previous.rotation.yaw)
        val deltaPitch = angleDifference(current.pitch, previous.rotation.pitch)
        if (!checkGridPurity(deltaYaw, deltaPitch, gcd)) {
            // The contaminated action must not be rounded into a plausible-looking mouse count.
            // Closing here preserves everything before it and starts fresh from the next tick.
            breakSequence()
            return
        }

        if (attacked && (attackedEntityId == NO_ATTACK_ENTITY ||
                attackedEntityId != previous.targetId)) {
            // TargetTracker selected somebody other than the entity vanilla actually attacked.
            // A direct packet with no AttackEntityEvent (sentinel NO_ATTACK_ENTITY) is automation
            // rather than a manual click. Either way its geometry cannot label this action, and a
            // wrong target on the most important row is worse than losing the row.
            //
            // The test is exactly the sentinel, not "any negative id": a client-only practice
            // dummy (CloneGym's fake player, `.fakeplayer`) carries a negative but perfectly valid
            // id, and `notePracticeAttack` labels the swing with it. Rejecting every negative id
            // discarded the whole gym drill — "No usable combat recorded" — because every gym
            // attack failed this gate and was never labelled.
            breakSequence()
            return
        }

        accept(
            AimTick(
                ownYaw = previous.rotation.yaw,
                ownPitch = previous.rotation.pitch,
                dx = (deltaYaw / gcd).roundToInt(),
                dy = (deltaPitch / gcd).roundToInt(),
                targetYaw = previous.targetYaw,
                targetPitch = previous.targetPitch,
                distance = previous.distance,
                hurtTime = previous.hurtTime,
                targetWidth = previous.targetWidth,
                targetHeight = previous.targetHeight,
                targetId = previous.targetId,
                moveForward = previous.moveForward,
                moveStrafe = previous.moveStrafe,
                onTarget = previous.onTarget,
                attacked = attacked,
                hasTarget = previous.hasTarget,
                attackCooldown = previous.attackCooldown,
                sprinting = previous.sprinting,
                sneaking = previous.sneaking,
                onGround = previous.onGround,
                usingItem = previous.usingItem,
                receivedVelocity = receivedVelocity,
                tookDamage = tookDamage
            )
        )
        sinceFlush++
    }

    /**
     * Files one candidate tick. Target-less rows are a runtime reset, not an action the model ever
     * generates, so they are hard sequence boundaries rather than lead/trail padding.
     */
    private fun accept(tick: AimTick) {
        if (tick.hasTarget) {
            ticks.add(tick)
            return
        }
        breakSequence()
    }

    /**
     * Warns once if the recorded movement is not landing on the sensitivity grid.
     *
     * Every delta a mouse produces is a whole number of counts. If it is not, something is
     * rewriting the rotation — another module, a mod, a macro — and the profile is a recording of
     * that thing rather than of the player. Catching it in the first seconds is worth a great deal
     * more than discovering it after an hour of play.
     */
    private fun checkGridPurity(deltaYaw: Float, deltaPitch: Float, gcd: Double): Boolean {
        val tolerance = gcd * GRID_TOLERANCE
        val yawOff = abs(deltaYaw - (deltaYaw / gcd).roundToInt() * gcd) > tolerance
        val pitchOff = abs(deltaPitch - (deltaPitch / gcd).roundToInt() * gcd) > tolerance

        if (!yawOff && !pitchOff) {
            return true
        }

        offGridTicks++
        if (gridWarned || offGridTicks < GRID_WARN_AFTER) {
            return false
        }

        gridWarned = true
        chat(
            markAsError(
                "Recorded movement is not on the sensitivity grid — something is rewriting your " +
                    "rotation. Disable other modules before recording, or this profile is useless."
            )
        )
        return false
    }

    /**
     * Says once that something is aiming for the player, because the shape of the failure hides it.
     *
     * The dropped ticks are precisely the ones worth having, so what reaches disk is the quiet
     * stretches between engagements — a recording that looks plausible right up to the moment the
     * sanitizer finds no attacks in it and discards the lot.
     */
    private fun warnIfSteered() {
        if (steeredWarned || steeredTicks < STEERED_WARN_AFTER) {
            return
        }

        steeredWarned = true
        chat(
            markAsError(
                "A module is steering your rotation — those ticks are being dropped, because a " +
                    "recording of the bot is not a recording of you. Turn KillAura (and anything " +
                    "else that aims) off while recording, or this profile stays empty."
            )
        )
    }

    /**
     * Where this tick is being recorded. Both dummies are client-only entities with negative ids
     * (`CloneGym.FIRST_GYM_ID`, `CommandFakePlayer.fakePlayerId`) — a server never assigns one — so
     * the id alone separates practice from a real opponent; the gym is named apart for the report.
     * Otherwise the server key the waypoint store already normalizes (typed address, `local_<world>`).
     */
    private fun sourceOf(target: LivingEntity?): String = when {
        target != null && target === gym.target -> AimSequence.SOURCE_GYM
        target != null && target.id < 0 -> AimSequence.SOURCE_DUMMY
        else -> MapIdentity.currentServerKey() ?: AimSequence.SOURCE_UNKNOWN
    }

    private fun isInCrosshair(target: LivingEntity, rotation: Rotation): Boolean {
        val range = player.eyePosition.distanceTo(target.box.center) + 1.0
        return findEntityInCrosshair(range, rotation) { it === target }?.entity != null
    }

    private fun breakSequence() {
        pending = null
        if (ticks.isEmpty()) {
            ticks.clear()
            return
        }

        val candidate = AimSequence(
                version = AimSequence.VERSION,
                profile = profile,
                gcd = sequenceGcd,
                sensitivity = mc.options.sensitivity().get(),
                timestamp = System.currentTimeMillis(),
                ticks = ticks.toList(),
                source = sequenceSource
        )
        val cleaned = CloneRecordingSanitizer.sanitize(candidate)
        sequences.addAll(cleaned.sequences)
        sanitizedAway += cleaned.candidateTicks - cleaned.keptTicks
        disengagedAway += cleaned.disengagedTicks
        ticks.clear()
    }

    private fun flush(final: Boolean) {
        sinceFlush = 0

        if (sequences.isEmpty()) {
            if (final) {
                reportNoUsableCombat()
            }
            return
        }

        // Snapshot on the game thread, write on a background one: serializing minutes of ticks to
        // JSON was a visible hitch, and a flush lands mid-fight by design. On failure the batch is
        // handed back to the front of the queue so the next flush retries it instead of losing it.
        val batch = sequences.toList()
        sequences.clear()

        val recorded = batch.sumOf { it.ticks.size }
        val count = batch.size
        val withTarget = batch.sumOf { sequence -> sequence.ticks.count { it.hasTarget } }
        val share = if (recorded == 0) 0f else withTarget.toFloat() / recorded * 100f
        val folder = AimSequence.folderOf(profile)

        thread(name = "clone-recorder-flush", isDaemon = false) {
            synchronized(flushLock) {
                runCatching {
                    folder.mkdirs()

                    val baseName = LocalDateTime.now().toUnderlinedString()
                    var file = folder.resolve("$baseName.json")
                    var index = 0
                    while (file.exists()) {
                        file = folder.resolve("${baseName}_${index++}.json")
                    }

                    file.bufferedWriter().use { writer ->
                        publicGson.toJson(batch, writer)
                    }
                    file
                }.onFailure { error ->
                    mc.execute {
                        sequences.addAll(0, batch)
                        chat(
                            markAsError(
                                "Failed to write recording (kept in memory): ${error.localizedMessage}"
                            )
                        )
                    }
                }.onSuccess { file ->
                    mc.execute {
                        chat(
                            regular("Wrote "),
                            variable("$recorded"),
                            regular(" ticks in "),
                            variable("$count"),
                            regular(" sequences ("),
                            variable("%.0f%%".format(share)),
                            regular(" with a target) to "),
                            clickablePath(file)
                        )
                        if (final) {
                            reportRejectedTicks()
                        }
                    }
                }
            }
        }
    }

    private fun reportNoUsableCombat() {
        val suffix = if (sanitizedAway > 0) {
            " ($sanitizedAway candidate ticks rejected)."
        } else {
            "."
        }
        chat(regular("No usable combat recorded$suffix"))
        reportSteeredTicks()
    }

    /**
     * The one number that explains an empty profile when everything else looked fine.
     */
    private fun reportSteeredTicks() {
        if (steeredTicks <= 0) {
            return
        }

        chat(
            regular("Dropped "),
            variable("$steeredTicks"),
            regular(" ticks where a module was aiming for you — the swings happen on exactly those "),
            regular("ticks, so what is left has none in it.")
        )
    }

    private fun reportRejectedTicks() {
        reportSteeredTicks()

        if (sanitizedAway <= 0) {
            return
        }
        chat(
            regular("Rejected "),
            variable("$sanitizedAway"),
            regular(" non-combat/short ticks; "),
            variable("$disengagedAway"),
            regular(" of them were sustained slow turn-aways.")
        )
    }
}
