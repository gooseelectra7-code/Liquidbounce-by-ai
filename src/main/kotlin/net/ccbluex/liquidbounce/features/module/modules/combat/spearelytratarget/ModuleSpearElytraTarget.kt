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

package net.ccbluex.liquidbounce.features.module.modules.combat.spearelytratarget

import net.ccbluex.liquidbounce.additions.shooter
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Rams a target with a braced spear while gliding.
 *
 * A spear does not kill by being swung — it kills by being *held* (right-click) while you fly
 * through the target fast enough. See [SpearKinetics] for the exact vanilla rule; the short version
 * is that damage is applied server-side every tick of the charge, needs a windup, needs speed, and
 * needs the target inside a **band** of 2 to 4.5 blocks along the look direction. Closer than the
 * band's inner edge and nothing lands at all, which is why a naive "fly at their face" never works.
 *
 * That shape forces a three-phase cycle, the same one vanilla's own `SpearUseGoal` uses for mobs:
 *
 * ```
 * CLOSE ── band is delayTicks away ──► STRIKE ── passed through / window spent ──► RECOVER
 *   ▲                                                                                │
 *   └────────────────── contact cooldown spent, gap reopened ────────────────────────┘
 * ```
 *
 * How wide that cycle is drawn is the one real choice, and it is [Mode]:
 *
 * - [Mode.HIT_AND_RUN] flies the classic bomber pattern — strike, break away twenty-odd blocks,
 *   climb, turn, come back. Every pass arrives at full speed, and speed is damage, but the target
 *   spends most of the engagement out of reach and free to do as it likes.
 * - [Mode.CLINCH] refuses to leave. It carries only the few blocks past the target it takes to get
 *   the tip back outside the band, turns straight around and comes back in through the far edge.
 *   Each stab lands for less, but they land every contact cooldown instead of every fly-past, and
 *   the target never gets the twenty blocks of quiet the other mode hands it.
 *
 * The module is self-contained: its own target tracker, its own aim, its own attack timing. It
 * needs no KillAura and does not care whether one is running — KillAura swings, and swinging is not
 * how this weapon works.
 */
@Suppress("TooManyFunctions")
object ModuleSpearElytraTarget : ClientModule("SpearElytraTarget", ModuleCategories.COMBAT) {

    private val targetTracker = tree(SpearElytraTargetTracker)

    /**
     * How far the cycle is allowed to swing out between passes. See the class docs; everything else
     * about the two modes is identical, because the weapon does not change — only the distance the
     * recovery is willing to spend does.
     */
    private val mode by enumChoice("Mode", Mode.HIT_AND_RUN).apply { tagBy(this) }

    enum class Mode(override val tag: String) : Tagged {
        HIT_AND_RUN("HitAndRun"),
        CLINCH("Clinch")
    }

    /**
     * Restricts targeting to opponents who are themselves gliding.
     *
     * Off by default, and the name is about *our* delivery method, not theirs: the ram works just
     * as well on someone standing on the ground, which is exactly what the dive-and-climb half of
     * [Recovery] is for. Defaulting this on made the module silently pick nobody against a target
     * on foot — and made that whole recovery branch unreachable.
     */
    private val glidingOnly by boolean("GlidingTargetsOnly", false)
    private val playersOnly by boolean("PlayersOnly", true)
    private val maxTargetRange by float("MaxTargetRange", 96f, 16f..256f, "blocks")

    /**
     * The standard rotation engine rather than a raw instant snap, so the aim carries the same
     * smoothing and humanisation as every other combat module. This is the part bystanders actually
     * see: the server-visible rotation is identical under every non-`OFF` correction, so a
     * teleporting crosshair reads as a bot no matter which camera mode is picked.
     *
     * Defaults to [MovementCorrection.CHANGE_LOOK] — moving the real camera means there is no
     * silent-rotation signature to spot at all, and the wing is steered by the same angle that is
     * on the wire.
     *
     * Trade-off worth knowing: this aim is also the flight control, so a slow AngleSmooth turns
     * every correction into a wide arc and passes start to miss. If a run keeps ending with
     * [lastPassBandTicks] at zero, raise the smoother's turn speed before touching anything else.
     */
    private val rotations = tree(
        RotationsValueGroup(this, MovementCorrection.CHANGE_LOOK, combatSpecific = true)
    )

    /**
     * Extra ticks of charge started before the target is predicted to enter the strike band, on top
     * of the spear's own windup. Covers jitter in the closing-speed estimate — starting early only
     * spends charge window, starting late misses the pass entirely.
     *
     * Sampled fresh per pass: bracing on the same exact tick of every approach is a pattern nobody
     * produces by hand.
     */
    private val chargeLead by intRange("ChargeLead", 2..5, 0..20, "ticks")

    private var chargeLeadTicks = 3

    /**
     * How far ahead of the current tick the aim is built, in ticks of both aircraft's own motion.
     *
     * One is the honest default: the rotation chosen this tick steers this tick's flight and is
     * raycast against a target that has also moved on by then. Zero aims at where everything was,
     * which trails the pass; much above one starts leading a target that has not committed to
     * anything yet, and a wing cannot correct a lead it flew into.
     */
    private val aimLead by float("AimLead", 1f, 0f..3f, "ticks")

    /**
     * Refuse to charge when we are too slow for the hit to register anyway. Off means charge on
     * geometry alone, which looks right and deals nothing.
     */
    private val requireStrikeSpeed by boolean("RequireStrikeSpeed", true)

    private val autoSwap by boolean("AutoSwap", true)

    init {
        tree(Recovery)
        tree(Clinch)
        tree(Boost)
        tree(AutoLaunch)
    }

    private val targetRenderer = tree(TargetRenderer(this) { target })

    private enum class Phase { CLOSE, STRIKE, RECOVER }

    private var phase = Phase.CLOSE
    private var strikeTicks = 0
    private var recoverTicks = 0
    private var breakAwayPoint: Vec3? = null

    /**
     * Ticks of the current pass spent with the target actually inside the reach band. Zero at the
     * end of a pass means the run was geometrically fine and still could not have dealt damage —
     * the charge was late, or we went straight to point-blank. Surfaced through [lastPassBandTicks]
     * because it is the one number that tells a miss from a bad tune.
     */
    private var ticksInBand = 0

    /**
     * Band ticks of the pass that just finished. 0 = the tip never covered the target.
     */
    var lastPassBandTicks = 0
        private set

    /** Hotbar slot held before we swapped to a spear, or -1 when we did not swap. */
    private var restoreSlot = -1

    /** True while we are the reason the use key is held. */
    private var forcingUse = false

    /** Set each tick by the aim pass, consumed by the input pass later in the same tick. */
    private var wantsCharge = false

    /** Ticks the use must stay released before the next one counts as a genuinely new charge. */
    private var rechargeTicks = 0

    internal val target: LivingEntity?
        get() = targetTracker.target

    val isControlling: Boolean
        get() = running && player.isFallFlying && targetTracker.target != null

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        Boost.tickCooldown()

        if (rechargeTicks > 0) {
            rechargeTicks--
        }

        updateTarget()

        if (!player.isFallFlying) {
            AutoLaunch.tick()
            reset()
            return@handler
        }

        AutoLaunch.reset()

        val target = targetTracker.target
        val slot = if (autoSwap) SpearKinetics.findSpearSlot() else heldSpearSlot()
        val profile = slot?.let(SpearKinetics::profileFor)

        if (target == null || profile == null) {
            reset()
            return@handler
        }

        advancePhase(target, profile)
        aim(target)

        wantsCharge = wantsCharge(target)
        applySpearSlot(profile)

        if (Boost.running) {
            Boost.tryLaunch(target, profile, phase)
        }
    }

    /**
     * The use key is driven here rather than in the aim pass because [GameTickEvent] is dispatched
     * at tick HEAD, before vanilla's `handleKeybinds` reads the key — so a charge decided this tick
     * is already held when vanilla starts the use, with no tick of lag.
     */
    @Suppress("unused")
    private val inputHandler = handler<GameTickEvent> {
        holdUse(wantsCharge)
    }

    // ---------------------------------------------------------------- phases

    private fun advancePhase(target: LivingEntity, profile: SpearKinetics.Profile) {
        when (phase) {
            Phase.CLOSE -> advanceClose(target, profile)
            Phase.STRIKE -> advanceStrike(target, profile)
            Phase.RECOVER -> advanceRecover(target, profile)
        }
    }

    private fun advanceClose(target: LivingEntity, profile: SpearKinetics.Profile) {
        // Already too close to reach past the shaft's near edge, so no charge started from here can
        // land. Without this the run keeps aiming at a target it can no longer hit and circles it
        // at point-blank until something else breaks the loop — several seconds of orbiting in
        // plain view, which is exactly what a wing never does by accident.
        if (SpearKinetics.isInsideDeadZone(target, profile)) {
            beginRecover(target)
            return
        }

        val ticksOut = SpearKinetics.ticksUntilStrikeBand(target, profile) ?: return
        val inWindow = ticksOut <= profile.delayTicks + chargeLeadTicks
        val fastEnough = !requireStrikeSpeed || SpearKinetics.hasStrikeSpeed(target, profile)

        if (inWindow && fastEnough) {
            phase = Phase.STRIKE
            strikeTicks = 0
        }
    }

    private fun advanceStrike(target: LivingEntity, profile: SpearKinetics.Profile) {
        strikeTicks++

        if (SpearKinetics.isInStrikeBand(target, profile)) {
            ticksInBand++
        }

        // Past the near edge the shaft starts behind the target: nothing this pass can connect any
        // more, whether or not it already did.
        val passedThrough = SpearKinetics.isInsideDeadZone(target, profile)
        val windowSpent = strikeTicks > profile.lethalUntilTick
        val openingUp = SpearKinetics.closingSpeed(target) <= 0.0 &&
            SpearKinetics.gapTo(target) > profile.maxReach

        if (passedThrough || windowSpent || openingUp) {
            beginRecover(target)
        }
    }

    private fun advanceRecover(target: LivingEntity, profile: SpearKinetics.Profile) {
        recoverTicks++

        if (mode == Mode.CLINCH) {
            advanceClinch(target, profile)
            return
        }

        val gapOpen = SpearKinetics.gapTo(target) >= Recovery.returnDistance
        val cooled = recoverTicks >= profile.contactCooldownTicks

        // A grounded target is attacked from above, so the next run cannot begin until the height
        // for it has been bought back. Skipping this is what flies the second pass into the floor.
        val readyToDive = !isGrounded(target) || player.y - target.y >= Recovery.diveHeight

        if (cooled && gapOpen && readyToDive || recoverTicks >= MAX_RECOVER_TICKS) {
            phase = Phase.CLOSE
            breakAwayPoint = null
            chargeLeadTicks = chargeLead.random()
            return
        }

        // The break-away point is reachable, and once reached, still aiming at it means turning
        // back onto it — an orbit around a fixed point that holds until the tick cap expires.
        // Push it further out along the same heading instead, so the recovery stays a straight
        // climbing extension until the gap and the height are genuinely there.
        if (hasReached(breakAwayPoint)) {
            extendBreakAway(target)
        }
    }

    /**
     * The clinch turn-around, which is the whole of [Mode.CLINCH] that differs.
     *
     * Nothing here waits for altitude or for two dozen blocks of separation. The pass is over the
     * moment the tip goes past the target, and only two things are worth waiting for: the contact
     * cooldown, because a re-entry sooner than that cannot deal damage however perfect the line is,
     * and enough room to come back in **through** the far edge of the band — a turn started from
     * inside the dead zone flies the whole approach with the shaft already behind the target.
     *
     * Both are short. The cooldown is ten ticks on a vanilla spear and the room needed is the band's
     * own far edge, so the swing-out is a handful of blocks rather than a departure.
     */
    private fun advanceClinch(target: LivingEntity, profile: SpearKinetics.Profile) {
        val cooled = recoverTicks >= profile.contactCooldownTicks
        val roomToStab = SpearKinetics.reachTo(target) >= profile.farEdge(0.0)

        if (cooled && roomToStab || recoverTicks >= Clinch.maxTurnTicks) {
            phase = Phase.CLOSE
            breakAwayPoint = null
            chargeLeadTicks = chargeLead.random()
            return
        }

        // Once the swing-out has been flown, drop it: the aim falls back to the intercept and the
        // wing turns back onto the target on its own. Pushing the point further out — what
        // hit-and-run does here — is precisely the flying-away this mode exists not to do.
        if (hasReached(breakAwayPoint)) {
            breakAwayPoint = null
        }
    }

    private fun hasReached(point: Vec3?): Boolean {
        if (point == null) {
            return false
        }

        val radius = if (mode == Mode.CLINCH) CLINCH_REACHED_RADIUS else REACHED_RADIUS

        val toPoint = point.subtract(player.position())
        if (toPoint.lengthSqr() <= radius * radius) {
            return true
        }

        // Overshot without passing close to it — a wing at ram speed can miss a point by more than
        // the radius, and turning back for it is the same orbit by another route.
        val heading = flatten(SpearKinetics.motionOf(player)) ?: return false
        return heading.dot(toPoint.normalize()) < OVERSHOT_DOT
    }

    private fun extendBreakAway(target: LivingEntity) {
        val heading = flatten(SpearKinetics.motionOf(player)) ?: return

        breakAwayPoint = breakAwayFrom(player.position(), heading, target)
    }

    /**
     * Forward travel and climb of the break-away. In a clinch neither depends on where the target
     * is: the pass ends the same way whether it was flown level or downhill, and the point of the
     * mode is that the answer stays small either way.
     */
    private fun breakAwayFrom(from: Vec3, heading: Vec3, target: LivingEntity): Vec3 {
        val grounded = isGrounded(target)

        val extend = when {
            mode == Mode.CLINCH -> Clinch.swingOut
            grounded -> Recovery.groundExtend
            else -> Recovery.airExtend
        }

        val climb = when {
            mode == Mode.CLINCH -> Clinch.climb
            grounded -> Recovery.groundClimb
            else -> Recovery.airClimb
        }

        return from
            .add(heading.scale(extend.toDouble()))
            .add(0.0, climb.toDouble(), 0.0)
    }

    /**
     * Sets up the break-away, which is shaped by where the target is.
     *
     * A target **on the ground** is struck in a dive, so the recovery is mostly a pull-up: little
     * forward travel, a lot of climb. Height is the resource the next pass spends, and it can only
     * be bought with the speed this one still has.
     *
     * A target **in the air** is already at altitude, so there is nothing to climb back to — the
     * recovery is a long flat extension that opens the gap and gives room to turn. A steep climb
     * here would only bleed the speed the next strike needs.
     *
     * [Mode.CLINCH] answers both cases the same way and answers them small — see [Clinch].
     */
    private fun beginRecover(target: LivingEntity) {
        phase = Phase.RECOVER
        recoverTicks = 0
        strikeTicks = 0
        lastPassBandTicks = ticksInBand
        ticksInBand = 0

        // Carry straight on rather than turning here: a turn at ram speed costs more height than
        // the distance it saves, and the turn is what the CLOSE intercept is for anyway.
        val heading = flatten(SpearKinetics.motionOf(player))
            ?: flatten(player.position().subtract(target.position()))
            ?: return

        breakAwayPoint = breakAwayFrom(player.position(), heading, target)
    }

    private fun isGrounded(target: LivingEntity) = !target.isFallFlying

    // ---------------------------------------------------------------- aiming

    /**
     * The aim for this tick. Two corrections live here, and both of them, left out, push the pass
     * *over* the target rather than through it.
     *
     * The ray the server casts starts at the **eye**, so the rotation has to be built from the eye.
     * Building it from the feet aims a whole eye-height high: at strike range that is more than
     * twenty degrees of pitch, and because this same angle flies the wing, the error is not a near
     * miss — it is a pass straight over their head.
     *
     * The second is that the aim decided here is not used here. It steers this tick's flight, and
     * only afterwards does the server raycast, by which time both aircraft have moved. [aimLead]
     * ticks of each side's own motion are added to both ends of the line, so the angle is the one
     * that will be true when it is used rather than the one that was true when it was picked.
     */
    private fun aim(target: LivingEntity) {
        val lead = aimLead.toDouble() / SpearKinetics.TICKS_PER_SECOND
        val from = player.eyePosition.add(SpearKinetics.motionOf(player).scale(lead))

        val point = when (phase) {
            // Lead the target so the two paths meet, instead of tail-chasing a glider that is as
            // fast as we are and never converging.
            Phase.CLOSE -> interceptPoint(target)

            // The server raycasts along the look direction, so on the pass the crosshair has to be
            // on the body itself. Aim at the middle of the hitbox, not the feet — the band is only
            // 2.5 blocks deep and the margin matters.
            Phase.STRIKE -> target.position()
                .add(0.0, target.bbHeight * 0.5, 0.0)
                .add(SpearKinetics.motionOf(target).scale(lead))

            Phase.RECOVER -> breakAwayPoint ?: interceptPoint(target)
        }

        // Only the phase decision consults the profile; the aim itself is never bent away from the
        // target, because the server raycasts along the look and an offset aim simply misses.
        val rotation = Rotation.lookingAt(point, from)
        val plan = rotations.toRotationTarget(rotation, target, considerInventory = false)

        RotationManager.setRotationTarget(
            plan = flyable(plan),
            priority = Priority.IMPORTANT_FOR_USAGE_3,
            provider = this
        )
    }

    /**
     * The gliding hook in `MixinLivingEntity` only steers the wing while the active target's
     * correction is not `OFF`. Picking `OFF` in the rotation settings would therefore leave the
     * aircraft uncontrolled with nothing on screen to explain it, so that one choice is overridden
     * to the nearest correction that still flies.
     */
    private fun flyable(plan: RotationTarget): RotationTarget {
        if (plan.movementCorrection != MovementCorrection.OFF) {
            return plan
        }

        return RotationTarget(
            plan.rotation,
            plan.entity,
            plan.processors,
            plan.ticksUntilReset,
            plan.resetThreshold,
            plan.considerInventory,
            MovementCorrection.STRICT,
            plan.whenReached
        )
    }

    /**
     * Where to point so that our path and the target's meet, given both speeds.
     *
     * Solves `|R + V·t| = s·t` for the smallest positive `t`, i.e.
     * `(V·V - s²)·t² + 2(R·V)·t + R·R = 0`, with R the offset to the target, V its velocity and s
     * our speed. Falls back to aiming straight at it when there is no solution — we are too slow to
     * catch it on this heading, and pure pursuit at least keeps the pressure on.
     *
     * Everything in here is in **seconds**, not ticks, because [SpearKinetics.motionOf] is the
     * server's own motion vector and that is scaled to blocks per second.
     */
    private fun interceptPoint(target: LivingEntity): Vec3 {
        val aimAt = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
        val relative = aimAt.subtract(player.eyePosition)
        val targetVelocity = SpearKinetics.motionOf(target)
        val ourSpeed = SpearKinetics.ownSpeed()

        if (ourSpeed < MIN_INTERCEPT_SPEED) {
            return aimAt
        }

        val a = targetVelocity.lengthSqr() - ourSpeed * ourSpeed
        val b = 2.0 * relative.dot(targetVelocity)
        val c = relative.lengthSqr()

        val meetTime = solveMeetTime(a, b, c) ?: return aimAt

        // Two gliders travel at almost the same speed, which drives the quadratic's leading term to
        // zero and lets the meet time run away. Worse, its sign flips as the target crosses our
        // line of approach, so the aim point jumps from far ahead of them to far behind between
        // neighbouring ticks and the wing weaves instead of closing. Cap the lead by how long pure
        // pursuit would take: no honest intercept is far longer than that, and a runaway solution
        // is exactly what this bound discards.
        val pursuitSeconds = relative.length() / ourSpeed
        val cap = minOf(MAX_LEAD_SECONDS, pursuitSeconds * LEAD_CAP_FACTOR)

        return aimAt.add(targetVelocity.scale(meetTime.coerceAtMost(cap)))
    }

    // ---------------------------------------------------------------- weapon

    private fun heldSpearSlot() = Slots.Hotbar.findSlot { SpearKinetics.isKinetic(it) }
        ?.takeIf { it.hotbarIndex == player.inventory.selectedSlot }

    /**
     * Whether the use key should be held this tick.
     *
     * In [Mode.HIT_AND_RUN] the charge is spent per pass: started a few ticks before the band and
     * dropped on the break-away, which is also what leaves the hand free to light a rocket.
     *
     * In a clinch it is simply kept alive. A charge stays lethal for 250 ticks after its windup —
     * over twelve seconds — while a re-entry comes every contact cooldown, so re-charging per pass
     * would pay the spear's 13-tick windup on every turn and leave the tip cold for exactly the
     * ticks it is covering the target. It is bounded by [Clinch.holdRange] rather than being
     * unconditional because there is nothing to charge for out at rocket-closing distance.
     *
     * Rockets still fly during a clinch — a wing that stops flying stops fighting. An offhand one
     * costs nothing; a hotbar one costs the charge, and [rearmCharge] is how that is paid.
     */
    private fun wantsCharge(target: LivingEntity): Boolean = when {
        rechargeTicks > 0 -> false
        phase == Phase.STRIKE -> true
        mode != Mode.CLINCH || !Clinch.holdCharge -> false
        else -> SpearKinetics.gapTo(target) <= Clinch.holdRange
    }

    /**
     * A real hotbar swap, not a silent one. The charge is a *use*, and the client decides the use
     * animation and duration from the slot it believes is selected — a silent swap would leave the
     * client charging one item while the server charges another.
     *
     * The spear is held for the whole engagement rather than swapped in per pass. Everyone nearby
     * can see the held item, and a weapon that appears for one tick of every approach and vanishes
     * again is a far louder tell than simply fighting with a spear out. It goes back only when the
     * engagement ends, which is what a player does too.
     */
    private fun applySpearSlot(profile: SpearKinetics.Profile) {
        if (!autoSwap) {
            return
        }

        val wanted = profile.slot.hotbarIndex ?: return
        val selected = player.inventory.selectedSlot

        if (selected != wanted) {
            if (restoreSlot == -1) {
                restoreSlot = selected
            }
            player.inventory.selectedSlot = wanted
        }
    }

    /**
     * Drop the use for a couple of ticks so the next one starts a charge the *server* also believes
     * in.
     *
     * Needed after anything that stops the use server-side without the client noticing, which a
     * hotbar selection does. Without the release the client never sends another use packet: the
     * spear looks charged on screen, is not charged on the wire, and every pass after it deals
     * nothing — the worst kind of failure, because it is invisible.
     */
    private fun rearmCharge() {
        rechargeTicks = RECHARGE_RELEASE_TICKS
    }

    private fun restoreHeldSlot() {
        if (restoreSlot in 0..8) {
            player.inventory.selectedSlot = restoreSlot
        }
        restoreSlot = -1
    }

    /**
     * Holding the use key is what keeps the charge alive: vanilla releases the item the moment it
     * sees the key up, so this has to be re-asserted every tick, and released back to the player's
     * real input rather than forced false.
     */
    private fun holdUse(hold: Boolean) {
        val key = mc.options.keyUse

        if (hold) {
            key.isDown = true
            forcingUse = true
        } else if (forcingUse) {
            key.isDown = key.isPressedOnAny
            forcingUse = false
        }
    }

    // ---------------------------------------------------------------- target

    private fun updateTarget() {
        targetTracker.validate { isSuitable(it) }

        if (targetTracker.target == null) {
            targetTracker.selectFirst { isSuitable(it) }
        }
    }

    private fun isSuitable(entity: LivingEntity): Boolean {
        if (entity === player || !entity.isAlive) {
            return false
        }

        if (playersOnly && entity !is Player) {
            return false
        }

        if (glidingOnly && !entity.isFallFlying) {
            return false
        }

        return player.distanceTo(entity) <= maxTargetRange
    }

    // ---------------------------------------------------------------- state

    private fun reset() {
        phase = Phase.CLOSE
        strikeTicks = 0
        recoverTicks = 0
        breakAwayPoint = null
        wantsCharge = false
        rechargeTicks = 0
        restoreHeldSlot()
    }

    override fun onDisabled() {
        targetTracker.reset()
        holdUse(false)
        reset()
        Boost.reset()
        AutoLaunch.reset()
    }

    private fun flatten(vector: Vec3): Vec3? {
        val flat = Vec3(vector.x, 0.0, vector.z)
        return if (flat.lengthSqr() > 1.0E-4) flat.normalize() else null
    }

    /**
     * Smallest strictly positive root of `a·t² + b·t + c = 0`, handling the near-linear case.
     */
    @Suppress("ReturnCount")
    private fun solveMeetTime(a: Double, b: Double, c: Double): Double? {
        if (kotlin.math.abs(a) < 1.0E-6) {
            if (kotlin.math.abs(b) < 1.0E-6) {
                return null
            }
            val t = -c / b
            return t.takeIf { it > 0.0 }
        }

        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0) {
            return null
        }

        val root = sqrt(discriminant)
        val first = (-b - root) / (2.0 * a)
        val second = (-b + root) / (2.0 * a)

        return listOfNotNull(first.takeIf { it > 0.0 }, second.takeIf { it > 0.0 }).minOrNull()
    }

    private const val MAX_RECOVER_TICKS = 80

    /** Blocks per second, below which leading a target is meaningless — we are not going anywhere. */
    private const val MIN_INTERCEPT_SPEED = 0.05

    /** Hard ceiling on the intercept lead. Two seconds of a target's travel is already a long way. */
    private const val MAX_LEAD_SECONDS = 2.0

    /** Ceiling on the intercept lead, as a multiple of the pure-pursuit time to the target. */
    private const val LEAD_CAP_FACTOR = 2.0

    /**
     * Ticks the use key is let go of to restart a charge. One would do — the client releases at the
     * next tick head and presses again the one after — but the second tick absorbs vanilla's own
     * use delay without leaving the module guessing.
     */
    private const val RECHARGE_RELEASE_TICKS = 2

    /** How close counts as having arrived at the break-away point. */
    private const val REACHED_RADIUS = 4.0

    /**
     * The same, for a clinch. Four blocks would count a five-block swing-out as reached before it
     * was even flown, and the turn would begin on top of the target.
     */
    private const val CLINCH_REACHED_RADIUS = 1.5

    /** Heading-vs-target dot below which the point counts as flown past rather than approached. */
    private const val OVERSHOT_DOT = -0.3

    /**
     * How the break-away is flown. The two sets exist because the geometry genuinely differs: a
     * grounded target is attacked downhill and the height has to be earned back before the next
     * run, while an airborne one is met level and only needs separation and turning room.
     */
    private object Recovery : ValueGroup("Recovery") {

        /** Forward travel after a pass on a grounded target — short, because this is a pull-up. */
        val groundExtend by float("GroundExtend", 12f, 4f..48f, "blocks")

        /** Height bought back after a dive. Paid for out of the speed the pass still carries. */
        val groundClimb by float("GroundClimb", 16f, 0f..48f, "blocks")

        /** Forward travel after a pass on a flying target — long and flat, to open turning room. */
        val airExtend by float("AirExtend", 22f, 4f..64f, "blocks")

        /** Barely any climb against a target that is already at altitude. */
        val airClimb by float("AirClimb", 4f, 0f..32f, "blocks")

        /**
         * Height advantage required over a grounded target before the next dive may start. Without
         * it the second pass is flown into the floor.
         */
        val diveHeight by float("DiveHeight", 12f, 0f..48f, "blocks")

        /** Gap that must reopen before the next approach begins. */
        val returnDistance by float("ReturnDistance", 24f, 8f..64f, "blocks")
    }

    /**
     * [Mode.CLINCH]: the tight cycle, where the recovery is a turn rather than a departure.
     *
     * The spear's own numbers are what make it possible. Damage asks for a *relative* speed of only
     * 4.6 blocks per second along the aim — a fraction of gliding speed — and the same charge stays
     * lethal for 250 ticks, so a pass does not have to be a full-speed ram and the spear does not
     * have to be re-charged between passes. What a pass does have to do is cross the band: the
     * shaft reaches from 2.0 to 4.5 blocks, so the turn only has to buy back the few blocks that
     * puts the tip outside 4.5 again. [swingOut] is that, and nothing more.
     *
     * The trade is real and worth knowing. Damage is `base + floor(relativeSpeed * 0.82)`, so
     * clinching converts one big ram into many small stabs — one every contact cooldown, ten ticks
     * apart on a vanilla spear — and buys it with the pressure of never letting the target out of
     * reach. Against someone who out-turns you, it is also the mode that keeps you inside *their*
     * band; there is no free lunch in staying close.
     */
    private object Clinch : ValueGroup("Clinch") {

        /**
         * How far past the target to carry before turning back in. The band's far edge is 4.5, so
         * anything under about five leaves the turn starting inside the dead zone.
         */
        val swingOut by float("SwingOut", 5.5f, 3f..16f, "blocks")

        /** A little climb on the turn, so a low pass does not finish in the floor. */
        val climb by float("Climb", 2f, 0f..12f, "blocks")

        /**
         * Give up on the turn after this long and approach again from wherever we ended up. A wing
         * that cannot come round inside this is not going to be helped by more of the same circle.
         */
        val maxTurnTicks by int("MaxTurnTicks", 30, 5..120, "ticks")

        /** Keep the charge alive between passes instead of paying the windup on every one. */
        val holdCharge by boolean("HoldCharge", true)

        /** Range inside which this counts as a clinch, and the charge is worth holding. */
        val holdRange by float("HoldRange", 20f, 8f..64f, "blocks")
    }

    /**
     * Speed is damage for a kinetic weapon, so rockets are thrust and thrust is DPS. Fired on the
     * approach, never during the charge — a rocket lit mid-strike arrives too late to change the
     * speed that pass and only wastes the stack.
     */
    private object Boost : ToggleableValueGroup(ModuleSpearElytraTarget, "Boost", true) {

        private val cooldown by int("Cooldown", 10, 0..60, "ticks")
        private val whileBoosting by boolean("WhileBoosting", false)

        private var cooldownTicks = 0

        fun reset() {
            cooldownTicks = 0
        }

        fun tickCooldown() {
            if (cooldownTicks > 0) {
                cooldownTicks--
            }
        }

        fun tryLaunch(target: LivingEntity, profile: SpearKinetics.Profile, phase: Phase): Boolean {
            if (phase == Phase.STRIKE || cooldownTicks > 0) {
                return false
            }

            if (!whileBoosting && hasOwnRocketInAir()) {
                return false
            }

            // Already fast enough to kill on the next pass; more thrust only widens the turn.
            if (phase == Phase.CLOSE && SpearKinetics.hasStrikeSpeed(target, profile)) {
                return false
            }

            // Offhand first — [Slots.OffhandWithHotbar] is ordered that way — because which hand
            // the rocket comes from decides whether the spear survives it.
            val slot = Slots.OffhandWithHotbar.findSlot(Items.FIREWORK_ROCKET) ?: return false

            // Selecting a hotbar slot makes the server drop a live charge: `handleSetCarriedItem`
            // calls `stopUsingItem`, and it does it silently — the client keeps animating a spear
            // that is no longer charging anywhere that counts. A use packet does *not* do this, so
            // an offhand rocket costs nothing at all.
            //
            // When only a hotbar rocket exists the thrust is still worth more than the charge, since
            // a wing that stops flying stops fighting. Take it and re-arm the spear afterwards
            // rather than leaving it quietly dead.
            val breaksCharge = !slot.isOffHand &&
                slot.hotbarIndex != player.inventory.selectedSlot &&
                player.isUsingItem

            useHotbarSlotOrOffhand(slot, 0)
            cooldownTicks = cooldown

            if (breaksCharge) {
                rearmCharge()
            }

            return true
        }

        private fun hasOwnRocketInAir() = world.entitiesForRendering().any {
            it is FireworkRocketEntity && it.isAlive && it.shooter === player
        }
    }

    private object AutoLaunch : ToggleableValueGroup(ModuleSpearElytraTarget, "AutoLaunch", true) {

        private val delay by int("Delay", 6, 0..15, "ticks")

        private var ticksNotFlying = 0

        fun reset() {
            ticksNotFlying = 0
        }

        @Suppress("ReturnCount")
        fun tick() {
            if (!enabled) {
                ticksNotFlying = 0
                return
            }

            if (ticksNotFlying < delay) {
                ticksNotFlying++
                return
            }

            if (player.onGround() || player.isPassenger || player.isInWater || player.abilities.flying) {
                return
            }

            val chest = player.getItemBySlot(EquipmentSlot.CHEST)
            if (!chest.`is`(Items.ELYTRA) || chest.nextDamageWillBreak()) {
                return
            }

            player.startFallFlying()
            network.send(
                ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
            )
            ticksNotFlying = 0
        }
    }
}


