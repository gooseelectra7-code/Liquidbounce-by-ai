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

package net.ccbluex.liquidbounce.utils.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.FailRotationProcessor
import net.ccbluex.liquidbounce.utils.aiming.features.processors.JitterRotationProcessor
import net.ccbluex.liquidbounce.utils.aiming.features.processors.ShortStopRotationProcessor
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.AccelerationAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.CloneAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.InterpolationAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.IntpolAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.GrimSnapAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.LearnedAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.LinearAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.NaturalAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.ShardAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.SigmoidAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.Test2AngleSmooth
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.minecraft.world.entity.Entity

/**
 * Configurable to configure the dynamic rotation engine
 */
open class RotationsValueGroup(
    owner: EventListener,
    movementCorrection: MovementCorrection = MovementCorrection.SILENT,
    combatSpecific: Boolean = false
) : ValueGroup("Rotations") {

    private val angleSmooth = modes(owner, "AngleSmooth", 0) {
        val linearAngleSmooth = LinearAngleSmooth(it)
        val interpolationAngleSmooth = if (combatSpecific) InterpolationAngleSmooth(it) else null

        listOfNotNull(
            linearAngleSmooth,
            SigmoidAngleSmooth(it),
            interpolationAngleSmooth,
            AccelerationAngleSmooth(it),
            // Guide-style easing interpolation ("Intpol"): its entity-aware parts (floating aim
            // point, jitter, speed coupling) switch off without a target and it falls back to a
            // monotone convergent hand-back, so it is safe outside combat too.
            IntpolAngleSmooth(it),
            NaturalAngleSmooth(it),
            // Available to every module, not just combat: the profile is about how the turn is
            // shaped in time, which has nothing to do with there being a target.
            GrimSnapAngleSmooth(it),
            // "Shard", javelin's ShardRotation (2026-09-25, replacing Sloth, which it keeps as an
            // alias). Without an entity it is a plain linear grid-truncated return, so it is safe
            // for non-combat callers too.
            ShardAngleSmooth(it),
            if (combatSpecific) Test2AngleSmooth(it) else null,
            if (combatSpecific) CloneAngleSmooth(it) else null,
            // "Learned": replays the player's own recorded hand movement (.ai start / .ai create).
            // Combat only: the libraries are cut out of PvP recordings and need a live entity.
            if (combatSpecific) LearnedAngleSmooth(it) else null
        ).toTypedArray()
    }

    /**
     * Switches the smoother by name (tag or alias), as `.ai create` does to put KillAura on the
     * rotation it just built. False when no mode carries [name].
     */
    fun selectAngleSmooth(name: String): Boolean = runCatching { angleSmooth.setByString(name) }.isSuccess

    /**
     * True when the active smoother wants a randomized (fanned) aim anchor rather than the single
     * deterministic best point. See [AngleSmooth.prefersFannedAimPoint] for which modes ask for it
     * and why Clone deliberately does not.
     */
    val prefersFannedAimPoint: Boolean
        get() = angleSmooth.activeMode.prefersFannedAimPoint

    /**
     * The smoother currently driving this config. Exposed so a clicker can ask a learned smoother
     * whether the player would swing on this tick.
     */
    val activeAngleSmooth: AngleSmooth
        get() = angleSmooth.activeMode

    /**
     * Every smoother this config offers, in the order the menu lists them, whether selected or not.
     *
     * The list above is where smoothers are enumerated and there is no second place that knows
     * about all of them, so anything that has to work over the whole set — a tool that scores each
     * one, a menu that describes them — has to read it from an instance.
     */
    val angleSmoothModes: List<AngleSmooth>
        get() = angleSmooth.modes

    private val shortStop = if (combatSpecific) tree(ShortStopRotationProcessor(owner)) else null
    private val fail = if (combatSpecific) tree(FailRotationProcessor(owner)) else null
    private val jitter = if (combatSpecific) tree(JitterRotationProcessor(owner)) else null
    private val resetThresholdValue = float("ResetThreshold", 2f, 1f..180f)
    private val resetThreshold by resetThresholdValue

    init {
        // Register after all three groups exist; mode changes also arrive while loading configs.
        angleSmooth.onChanged { syncProcessorVisibility() }
        syncProcessorVisibility()
    }

    private fun syncProcessorVisibility() {
        val hidden = angleSmooth.activeMode.suppressesPostProcessors
        shortStop?.notAnOption(hidden)
        fail?.notAnOption(hidden)
        jitter?.notAnOption(hidden)
        resetThresholdValue.notAnOption(angleSmooth.activeMode.preservesResetSteps)
    }

    private val movementCorrection by enumChoice("MovementCorrection", movementCorrection)
    private val ticksUntilReset by int("TicksUntilReset", 5, 1..30, "ticks")

    open fun toRotationTarget(
        rotation: Rotation,
        entity: Entity? = null,
        considerInventory: Boolean = false,
        whenReached: RestrictedSingleUseAction? = null
    ) = RotationTarget(
        rotation,
        entity,
        if (angleSmooth.activeMode.suppressesPostProcessors) {
            listOf(angleSmooth.activeMode)
        } else {
            listOfNotNull(
                angleSmooth.activeMode,
                fail?.takeIf { it.running },
                shortStop?.takeIf { it.running },
                jitter?.takeIf { it.running }
            )
        },
        ticksUntilReset,
        resetThreshold,
        considerInventory,
        movementCorrection,
        whenReached
    )

    /**
     * How long it takes to rotate to a rotation in ticks
     *
     * Calculates the difference from the server rotation to the target rotation and divides it by the
     * minimum turn speed (to make sure we are always there in time)
     *
     * @param rotation The rotation to rotate to
     * @return The amount of ticks it takes to rotate to the rotation
     */
    fun calculateTicks(rotation: Rotation) = angleSmooth.activeMode
        .calculateTicks(RotationManager.actualServerRotation, rotation)

}
