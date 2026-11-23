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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget.ModuleElytraTarget
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.spearelytratarget.ModuleSpearElytraTarget
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.AirState
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air.RocketWatch
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.debug.ChaseDebug
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.drive.HolonomicDriver
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.drive.WingDriver
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.firework.FireworkUse
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.EnergyController
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.EnergyDecision
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.EnergyInput
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.EnergySettings
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.Feasibility
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.FeasibilityInput
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.FeasibilitySettings
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.FlightCommand
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.Guidance
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.GuidanceInput
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.GuidanceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.GuidanceSolution
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.guide.Rollout
import net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.launch.AutoLaunch
import net.ccbluex.liquidbounce.features.module.modules.movement.elytrafly.ModuleElytraFly
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/**
 * TestElytraTarget - an autopilot that keeps an opponent inside weapon range.
 *
 * It does not attack. KillAura does that, and doing it in two places would be two modules fighting
 * over one packet order. What this owns is the flying: picking a target, pointing the wing, spending
 * fireworks, and getting off the ground in the first place.
 *
 * The control loop is a guidance law rather than a search. Each tick it works out a direction to fly
 * from the geometry of the engagement, corrects how steeply to fly it from the energy balance against
 * the target, and only then rolls the result forward through vanilla physics to check it does not end
 * in a hillside. That ordering is the whole design: decide by law, verify by simulation. Deciding by
 * simulation instead means scoring a fan of candidates every tick and living with a heading that
 * jumps whenever two of them trade places.
 *
 * Where that pays off is the overshoot, which is what makes elytra chasing hard in the first place.
 * Simulated against the naive alternative of pointing at the target, the two are close in a
 * straight-line tail chase, where there is nothing to get wrong; against a target that is stationary
 * or manoeuvring, the naive law blows past and spends most of the fight looping back, and the
 * measured time in reach comes out several times better here.
 *
 * Three modes, differing in what the rotation is allowed to be used for:
 *
 * - [ChaseMode.LEGIT] moves the real camera and yields aim to the aura whenever it has something to
 *   shoot at, because a hit needs a crosshair on a hitbox and an aura is better at that than a flight
 *   controller is.
 * - [ChaseMode.HVH] steers silently. Hits do not need the crosshair, so the wing never has to turn
 *   around to attack and the objective collapses to staying inside the band as long as possible.
 * - [ChaseMode.FULL_HVH] hands the flying to ElytraFly's Vanilla mode, which makes the aircraft
 *   holonomic: constant speed in any direction, no energy to manage, no fireworks worth spending.
 *
 * While the older `ModuleElytraTarget` or `ModuleSpearElytraTarget` is running this module stands
 * down entirely. Two autopilots requesting rotations at once produce a wing that flies neither plan.
 */
object ModuleTestElytraTarget : ClientModule("TestElytraTarget", ModuleCategories.COMBAT) {

    enum class ChaseMode(override val tag: String) : Tagged {
        LEGIT("Legit"),
        HVH("Hvh"),
        FULL_HVH("FullHvh"),
    }

    /**
     * Ticks of push assumed from a rocket we have not fired yet, when asking whether firing one
     * would pay. A flight-one rocket expects `10 + 5`; assuming the smallest keeps the test honest,
     * since a bigger rocket can only do better than the case that justified spending it.
     */
    private const val NOMINAL_BOOST_TICKS = 15

    private val mode by enumChoice("Mode", ChaseMode.LEGIT)

    private val tracker = tree(ChaseTargetTracker)

    /**
     * Whether to chase whoever the aura has picked.
     *
     * On by default, and not merely for convenience: if the two disagree about who the target is,
     * the flying serves one opponent while the hitting serves another and neither gets done.
     */
    private val useKillAuraTarget by boolean("UseKillAuraTarget", true)

    /**
     * Whether the aura outranks us for aim in Legit.
     *
     * On by default. The aura sits at `IMPORTANT_FOR_USAGE_2` and only asks for a rotation when it
     * has a target in reach, so letting it win costs the flight nothing at the exact moment precise
     * aim is worth more than a precise heading.
     */
    private val yieldToKillAura by boolean("YieldToKillAura", true)

    private object Guide : ValueGroup("Guidance") {
        val navConstant by float("NavConstant", 3.5f, 1.5f..6f)

        /**
         * Where inside the reach band to sit, 0 being the near edge and 1 the far edge. Reach has a
         * floor as well as a ceiling, so this is a position within a band rather than a fraction of
         * a maximum.
         */
        val standoff by float("Standoff", 0.3f, 0f..1f)

        /** How far out, in multiples of maximum reach, the lead turn begins. */
        val mergeDistance by float("MergeDistance", 3f, 1.5f..8f)

        val passBehind by boolean("PassBehind", true)

        /**
         * Ceiling on how far ahead of the aircraft a command may be. A heading the wing cannot reach
         * within this many ticks is pulled back towards one it can, which stops the controller
         * chasing a setpoint that smoothing will never deliver.
         */
        val maxCommandTicks by int("MaxCommandTicks", 6, 1..20, "ticks")
    }

    private object Energy : ToggleableValueGroup(ModuleTestElytraTarget, "Energy", true) {
        val margin by float("Margin", 0.6f, 0f..3f)
        val convertOvershoot by boolean("ConvertOvershoot", true)
        val maxClimbPitch by float("MaxClimbPitch", 40f, 10f..80f, "degrees")
    }

    private object Prediction : ValueGroup("Prediction") {
        val horizon by int("Horizon", 30, 10..60, "ticks")
        val leadTarget by boolean("LeadTarget", true)
        val assumeSprint by boolean("AssumeSprint", true)

        /**
         * Flight rating to assume for an opponent's rocket. Guessing high is the safe direction:
         * under-predicting their boost aims the intercept at where they used to be.
         */
        val assumeEnemyFlight by int("AssumeEnemyFlight", 3, 1..3)
    }

    private object Safety : ValueGroup("Safety") {
        val minAltitude by float("MinAltitude", 4f, 0f..32f, "blocks")
        val avoidVoid by boolean("AvoidVoid", true)
    }

    private object FullHvh : ValueGroup("FullHvh") {
        /** Switch ElytraFly on, force it to Vanilla, and put it back afterwards. */
        val manageElytraFly by boolean("ManageElytraFly", true)

        /** Scales the commanded climb against ElytraFly's own vertical speed. */
        val verticalGain by float("VerticalGain", 1f, 0f..2f)
    }

    // Silent by default, on the user's instruction: nothing this module does should move the
    // camera. The wing still follows it, because vanilla fall-flying reads the rotation we send
    // rather than the one on screen.
    private val rotations = RotationsValueGroup(this, MovementCorrection.SILENT)
    private val fireworks = tree(FireworkUse(this))
    private val autoLaunch = tree(AutoLaunch(this))
    private val debug = tree(ChaseDebug(this))

    init {
        tree(Guide)
        tree(Energy)
        tree(Prediction)
        tree(Safety)
        tree(FullHvh)
        tree(rotations)
    }

    private var command: FlightCommand? = null
    private var launchPending = false
    private var warnedAboutRival = false

    override fun onDisabled() {
        stand()
        warnedAboutRival = false
    }

    /**
     * Drops every piece of state that could outlive a tick, and gives ElytraFly back.
     *
     * Called from every early exit as well as on disable. A stale command is worse than no command:
     * the execute phase would act on geometry that no longer exists.
     */
    private fun stand() {
        command = null
        launchPending = false
        HolonomicDriver.release()
        RocketWatch.reset()
        fireworks.reset()
        debug.clear()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { stand() }

    /**
     * The plan phase.
     *
     * Runs on [RotationUpdateEvent], which fires at the head of the tick from inside the rotation
     * system's own handler, immediately before it arbitrates the requests it has been given. Planning
     * here means a request made now is resolved this tick rather than the next one.
     */
    @Suppress("unused", "ReturnCount")
    private val planHandler = handler<RotationUpdateEvent> {
        if (standDownForRival()) {
            stand()
            return@handler
        }

        RocketWatch.tick()

        val target = resolveTarget()
        if (target == null) {
            stand()
            return@handler
        }

        if (!player.isFallFlying) {
            command = null
            launchPending = autoLaunch.running
            return@handler
        }
        launchPending = false

        if (mode == ChaseMode.FULL_HVH && FullHvh.manageElytraFly && !takeCustody()) {
            command = null
            return@handler
        }

        val state = AirState.capture(
            target = target,
            horizon = Prediction.horizon,
            assumeSprint = Prediction.assumeSprint,
            lead = Prediction.leadTarget,
            assumeEnemyFlight = Prediction.assumeEnemyFlight,
        )
        if (state == null) {
            command = null
            return@handler
        }

        val solution = solveGuidance(state)
        val energy = applyEnergy(state, solution)

        val next = if (mode == ChaseMode.FULL_HVH) {
            holonomicCommand(state, solution, energy)
        } else {
            wingCommand(state, solution, energy)
        }

        command = next
        next?.let { pushRotation(it, target) }
    }

    /**
     * The execute phase, where vanilla would have acted on a click.
     *
     * Only two things happen here, and neither is an attack: opening the wings, and spending a
     * rocket. Everything else was decided at the head of the tick.
     */
    @Suppress("unused")
    private val executeHandler = handler<InputHandleEvent> {
        if (standDownForRival()) {
            return@handler
        }

        if (launchPending) {
            autoLaunch.tryLaunch()
            return@handler
        }

        val current = command ?: return@handler
        if (mode != ChaseMode.FULL_HVH) {
            fireworks.consider(current.boost, current.rotation.yaw, current.rotation.pitch)
        }
    }

    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (standDownForRival()) {
            return@handler
        }

        if (launchPending && autoLaunch.wantsJump()) {
            event.jump = true
            return@handler
        }

        if (mode == ChaseMode.FULL_HVH && HolonomicDriver.hasCustody && command != null) {
            // The velocity is written outright, so no movement input is needed to produce it. Jump
            // and sneak are suppressed only so a held key cannot fight the commanded climb rate.
            event.jump = false
            event.sneak = false
        }
    }

    /**
     * The flight model of the holonomic mode.
     *
     * Ordered below every default handler so it runs after ElytraFly has written its own velocity,
     * making this the last word. It has to be a plain handler rather than a `tickHandler`, because
     * that helper offers no way to set a priority at all.
     */
    @Suppress("unused")
    private val velocityHandler = handler<GameTickEvent>(
        priority = EventPriorityConvention.READ_FINAL_STATE
    ) {
        if (mode != ChaseMode.FULL_HVH || !HolonomicDriver.hasCustody || !player.isFallFlying) {
            return@handler
        }
        val velocity = command?.velocity ?: return@handler
        HolonomicDriver.applyVelocity(velocity)
    }

    private fun solveGuidance(state: AirState): GuidanceSolution {
        val frame = state.frames[0]

        return Guidance.solve(
            GuidanceInput(
                eye = state.eye,
                velocity = state.velocity,
                targetPoint = frame.aimPoint(state.target),
                targetVelocity = frame.velocity,
                minReach = state.minReach,
                maxReach = state.maxReach,
            ),
            GuidanceSettings(
                navConstant = Guide.navConstant.toDouble(),
                standoff = Guide.standoff.toDouble(),
                mergeDistance = Guide.mergeDistance.toDouble(),
                passBehind = Guide.passBehind,
            ),
        )
    }

    private fun applyEnergy(state: AirState, solution: GuidanceSolution): EnergyDecision = EnergyController.apply(
        EnergyInput(
            velocity = state.velocity,
            y = state.eye.y,
            targetVelocity = state.target.velocity,
            targetY = state.target.position.y,
            desiredDirection = solution.direction,
            orbitWeight = solution.orbitWeight,
            gravity = state.target.gravity,
        ),
        EnergySettings(
            // Holonomic flight has no energy to trade: the speed is a constant set by ElytraFly and
            // altitude is free, so the controller is meaningless there and is switched off rather
            // than neutered.
            enabled = Energy.enabled && mode != ChaseMode.FULL_HVH,
            margin = Energy.margin.toDouble(),
            convertOvershoot = Energy.convertOvershoot,
            maxClimbPitch = Energy.maxClimbPitch.toDouble(),
            // In HvH we never break away, so a zoom climb that leaves the band must not happen; the
            // orbit keeps the vertical instead.
            allowZoomClimb = mode == ChaseMode.LEGIT,
        ),
    )

    /**
     * Builds the command for the two modes that really fly an elytra.
     *
     * The rollout is not optional. The guidance law is blind to terrain, and on a dive at a grounded
     * opponent it will fly us into the floor with perfect accuracy.
     */
    private fun wingCommand(
        state: AirState,
        solution: GuidanceSolution,
        energy: EnergyDecision,
    ): FlightCommand {
        val desired = WingDriver.rotationFor(energy.direction)

        // A smoothed aim is a turn-rate limit, so the command is pulled back into what the wing can
        // actually reach and the rollout is slewed at the same rate. A rollout that assumed the
        // rotation snapped would clear dives there is no time to pull out of.
        val turnTicks = if (mode == ChaseMode.LEGIT) {
            rotations.calculateTicks(desired).coerceAtLeast(1)
        } else {
            1
        }
        val commanded = WingDriver.clampToReachable(desired, state.rotation, turnTicks, Guide.maxCommandTicks)

        val input = FeasibilityInput(
            eye = state.eye,
            velocity = state.velocity,
            targetFrames = state.frames,
            targetAimHeight = state.target.height,
            minReach = state.minReach,
            maxReach = state.maxReach,
        )
        val settings = FeasibilitySettings(
            horizon = Prediction.horizon,
            minAltitude = Safety.minAltitude.toDouble(),
            avoidVoid = Safety.avoidVoid,
            turnTicks = turnTicks,
        )

        val ownBoost = fireworks.ownBoostTicksLeft()
        val rollout = Feasibility.resolve(commanded, state.rotation, input, settings, AirState.terrainProbe, ownBoost)

        // Two reasons to want a rocket, and the second was missing at first, which is why the
        // module simply never fired one: being short of energy is the obvious case, but a chase that
        // will not reach the band inside the whole horizon needs one just as badly, and against a
        // target at even moderate range that is the normal state of affairs.
        // Never stack a rocket on a rocket: the second push mostly overwrites the first, so it buys
        // nothing and costs an item.
        val notCatchingThem = rollout.ticksToBand == Rollout.NEVER
        val boost = fireworks.running &&
            (energy.wantsBoost || notCatchingThem) &&
            ownBoost <= 0 &&
            Feasibility.boostPaysOff(
                rollout.rotation,
                state.rotation,
                input,
                settings,
                AirState.terrainProbe,
                NOMINAL_BOOST_TICKS,
                fireworks.boostGainTicks,
            )

        val result = FlightCommand(
            rotation = rollout.rotation,
            boost = boost,
            velocity = null,
            diagnostics = diagnosticsOf(solution, energy, rollout, recovered = rollout.rotation != commanded),
        )

        debug.publish(result, state.frames.map { it.position }, rollout.positions)
        return result
    }

    /**
     * Builds the command for FullHvH, where the aircraft can go anywhere at a fixed speed.
     *
     * There is no elytra physics to roll forward here, because ElytraFly overwrites the velocity
     * outright, so the safety check is a straight projection of the commanded velocity instead. If
     * that runs into the ground the answer is simply to climb: with holonomic movement there is no
     * turn to plan and no energy to spend on it.
     */
    private fun holonomicCommand(
        state: AirState,
        solution: GuidanceSolution,
        energy: EnergyDecision,
    ): FlightCommand {
        val direction = energy.direction

        var velocity = HolonomicDriver.stationKeepingVelocity(
            direction = direction,
            range = solution.range,
            standoffRange = solution.standoffRange,
            targetVelocity = state.target.velocity,
            maxHorizontal = HolonomicDriver.maxHorizontal,
            maxVertical = HolonomicDriver.maxVertical,
            verticalGain = FullHvh.verticalGain.toDouble(),
        )

        // No elytra physics to roll forward, since the velocity is simply written, so the safety
        // check is a straight projection of it. Holonomic movement has no turn to plan and no energy
        // to spend, so if the ground is coming the whole answer is to climb.
        var clearance = Double.MAX_VALUE
        var probe = state.eye
        repeat(Prediction.horizon) {
            probe = probe.add(velocity)
            clearance = minOf(clearance, AirState.terrainProbe.clearanceBelow(probe))
        }

        val recovered = clearance < Safety.minAltitude
        if (recovered) {
            velocity = Vec3(velocity.x, HolonomicDriver.maxVertical, velocity.z)
        }

        val result = FlightCommand(
            rotation = WingDriver.rotationFor(direction),
            boost = false,
            velocity = velocity,
            diagnostics = diagnosticsOf(
                solution,
                energy,
                rollout = null,
                recovered = recovered,
                clearance = clearance,
            ),
        )

        debug.publish(result, state.frames.map { it.position }, emptyList())
        return result
    }

    private fun diagnosticsOf(
        solution: GuidanceSolution,
        energy: EnergyDecision,
        rollout: Rollout?,
        recovered: Boolean,
        clearance: Double = rollout?.minClearance ?: Double.MAX_VALUE,
    ) = FlightCommand.Diagnostics(
        range = solution.range,
        closure = solution.closure,
        standoffRange = solution.standoffRange,
        orbitWeight = solution.orbitWeight,
        energy = energy.energy,
        targetEnergy = energy.targetEnergy,
        energyDeficit = energy.deficit,
        ticksInBand = rollout?.ticksInBand ?: 0,
        minClearance = clearance,
        recovered = recovered,
    )

    private fun pushRotation(command: FlightCommand, target: LivingEntity) {
        when (mode) {
            ChaseMode.LEGIT -> WingDriver.steerVisible(
                module = this,
                rotations = rotations,
                rotation = command.rotation,
                target = target,
                priority = if (yieldToKillAura) Priority.IMPORTANT_FOR_USAGE_1 else Priority.IMPORTANT_FOR_USAGE_3,
            )

            ChaseMode.HVH -> WingDriver.steerSilent(
                module = this,
                rotation = command.rotation,
                target = target,
                priority = Priority.IMPORTANT_FOR_USAGE_3,
            )

            // Nothing steers by rotation here - the velocity is written directly - so the aim is
            // free to stay silent and merely point where we are going.
            ChaseMode.FULL_HVH -> WingDriver.steerSilent(
                module = this,
                rotation = command.rotation,
                target = target,
                priority = Priority.IMPORTANT_FOR_USAGE_3,
            )
        }
    }

    /**
     * Takes or keeps custody of ElytraFly.
     *
     * @return false when the user has changed ElytraFly themselves, in which case custody is dropped
     *   silently: re-applying our settings every tick would be a tug of war, and restoring on top of
     *   a deliberate change would undo their edit
     */
    private fun takeCustody(): Boolean {
        if (!HolonomicDriver.hasCustody) {
            HolonomicDriver.claim()
            return true
        }
        return HolonomicDriver.verifyCustody()
    }




    /**
     * Whether an older elytra autopilot has the wing. The warning fires once per engagement, because
     * a message every tick is not a warning, it is noise.
     */
    private fun standDownForRival(): Boolean {
        val rival = ModuleElytraTarget.running || ModuleSpearElytraTarget.running
        if (rival && !warnedAboutRival) {
            warnedAboutRival = true
            chat("TestElytraTarget: standing down, another elytra autopilot is running")
        }
        if (!rival) {
            warnedAboutRival = false
        }
        return rival
    }

    private fun resolveTarget(): LivingEntity? {
        if (useKillAuraTarget) {
            ModuleKillAura.targetTracker.target?.let { return it }
        }

        tracker.validate()
        return tracker.target ?: tracker.selectFirst()
    }
}
