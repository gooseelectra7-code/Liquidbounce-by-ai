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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.minecraft.world.entity.Entity

/**
 * What the module hub asks of an AutoMine engine, the contract both [MineJob] (vanilla breaking) and [PacketMineJob]
 * (packet breaking through `ModuleSpeedMine`) keep. Main thread only.
 *
 * The one rule the hub builds on: [plan] returns a [MinePlan] exactly on the ticks the dig TAKES. Such a tick
 * carries a dig packet - or the aim that leads to one - and therefore no attack and no use of the aura
 * (`PacketOrderI`, `MultiActionsF`); the hub files the plan's rotation and slot and consults [execute] at EXECUTE.
 * On every other tick [plan] returns null and the aura plans as if nothing were being dug.
 */
interface AutoMineEngine {

    /** A job is in progress (it may still leave this tick to the aura). */
    val active: Boolean

    /** The target damage of the placement the running job digs for, null when no job runs (`Debug`). */
    val plannedDamage: Float?

    /** One-liner for the module's `Debug` trace. */
    fun describe(): String

    /**
     * Decides this tick at PLAN. [target] is a freshly searched plan, only looked at while nothing runs; [auraOffers]
     * is whether the aura has an action ready THIS tick. [keepAbove] is what the aura can do without digging (the
     * hub's MinGain reference, 0 = nothing): a running job whose pocket was planned for LESS than that gives up the
     * way it gives up on anything else - the dig would now cost more than it buys. Strictly less: with `MinGain = 0`
     * a job may start at exactly the reference, and `<=` would give it up on the very tick it started, every cooldown
     * (refutation audit). `PacketMineJob` does not give up once its STOP is out (`BREAKING`): the server breaks the
     * block whatever happens next.
     *
     * Deliberately the bare reference and not the MinGain floor a new job needs to START (`mineGainFloor`). Once a
     * dig is underway most of its cost is paid (with the SpeedMine engine the rest is one STOP tick), so it is worth
     * finishing as long as it gains anything; and the band between the two thresholds is what keeps a job from being
     * started, given up and started again from zero progress whenever the reference wobbles (the refutation audit's
     * flap, 2026-10-03).
     */
    fun plan(
        view: WorldView,
        target: MineTarget?,
        auraOffers: Boolean,
        slack: Float,
        rayIgnore: (Entity) -> Boolean,
        keepAbove: Float,
    ): MinePlan?

    /**
     * Performs [plan] at EXECUTE. [allowDig] is the hub's foreign-click gate for this tick, [foreignDig] whether a
     * dig packet of somebody else's already went out in it. Returns a line for the `Debug` trace.
     */
    fun execute(
        plan: MinePlan,
        view: WorldView,
        rayIgnore: (Entity) -> Boolean,
        allowDig: Boolean,
        foreignDig: Boolean,
    ): String

    /** The module stands down for a while (a screen, a totem swap); see the implementations. */
    fun stop()

    /** Forget everything: disable, a world change, no targets, a change of engine. */
    fun reset()

    /**
     * The plan of a job that broke ALL its cells since the last call, then forgotten; null otherwise (a give-up never
     * reports one). The hub keeps that pocket in its MinGain reference for a while: until the obsidian is in and the
     * dropped block has left the crystal's column, the placement ranking cannot see it.
     */
    fun takeFinished(): MineTarget?
}
