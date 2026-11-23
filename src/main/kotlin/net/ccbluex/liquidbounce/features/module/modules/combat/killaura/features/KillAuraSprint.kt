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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.CriticalsSelectionMode
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny

/**
 * How the aura drops sprint so a hit can crit.
 */
enum class SprintMode(override val tag: String) : Tagged {

    /**
     * Sprint is held off for as long as a crit is on the table — airborne with a live target — and
     * comes back the moment it is not. One stop on the way up, one start on landing, and the player
     * keeps full control of movement throughout. The cheap one, and the default.
     */
    NORMAL("Normal"),

    /**
     * A discrete w-tap around individual hits: the sprint key physically lifts, the attack waits a
     * couple of ticks so the stop is unmistakably in front of it, and everything comes back once the
     * hit is out. Optionally drops the movement input too. Costs momentum and rate of fire, so it is
     * rationed by a cooldown rather than fired on every swing.
     */
    LEGIT("Legit"),
}

/**
 * KillAura sprint reset.
 *
 * You cannot crit while the server thinks you are sprinting, so a hit that should crit has to be
 * preceded by a sprint drop.
 *
 * The rule the whole thing is built around: the client really stops sprinting, and the packet saying
 * so is the one vanilla sends by itself. `LocalPlayer.sendPosition` opens with
 * `sendIsSprintingIfNeeded`, so a real client emits at most one sprint packet per tick, immediately
 * ahead of that tick's movement packet. Hand-rolling a STOP/START pair around the attack puts two of
 * them in a single tick in a slot vanilla never uses and, worse, leaves the client moving at sprint
 * speed while the server has been told otherwise — which is exactly the divergence a
 * movement-prediction anticheat measures. So nothing here sends a sprint packet. It only decides
 * *when* the player is not sprinting and lets vanilla report it.
 *
 * That fixes the ordering for free. The aura runs at the head of `Minecraft.tick`, before the player
 * moves and before `sendPosition`, so the drop is armed one tick ahead of the swing:
 *
 *     tick T    STOP_SPRINTING, move            <- armed here
 *     tick T+1  ATTACK, ..., START_SPRINTING, move
 *
 * Both modes are gated on crits being wanted and possible ([critsWanted]): with `Criticals = Ignore`
 * there is nothing to reset for and the feature stays out of the way entirely.
 */
internal object KillAuraSprint : ValueGroup("Sprint"), MinecraftShortcuts {

    val mode by enumChoice("Mode", SprintMode.NORMAL)

    /**
     * [SprintMode.LEGIT]: how long the attack waits after the key lifts. One tick is the minimum
     * that puts the stop in front of the swing; more looks less mechanical, at the cost of rate.
     */
    private val releaseTicks by int("ReleaseTicks", 2, 1..6, "ticks")

    /**
     * [SprintMode.LEGIT]: also drop WASD for the release, so the momentum really dies instead of
     * only the sprint flag.
     *
     * Off by default, and deliberately so — it is the difference between a tap and being unable to
     * play. It only ever covers [releaseTicks], never the whole window.
     */
    private val stopMotion by boolean("StopMotion", false)

    /**
     * [SprintMode.LEGIT]: shortest gap between two taps. A person w-taps a few times a second, not
     * once per swing, and re-arming on every swing turns the wire into a sprint-packet strobe.
     */
    private val tapCooldown by int("TapCooldown", 6, 0..20, "ticks")

    /**
     * Only tap once we are genuinely falling. Above this the hit lands on the way up or at the apex
     * where it cannot crit anyway, and the tap would be pure lost momentum.
     */
    private const val FALL_THRESHOLD = -0.07

    /**
     * Ceiling on a [SprintMode.LEGIT] tap, in case the swing it was armed for never happens.
     */
    private const val TAP_TIMEOUT_TICKS = 10

    /**
     * How long the aura may go without asking before whatever is held gets handed back. Two rather
     * than one because [tick] and the aura's attack pass are separate `GameTickEvent` listeners with
     * no guaranteed order between them.
     */
    private const val STALE_TICKS = 2

    private const val NO_TICK = Int.MIN_VALUE

    private var tapStartedAt = NO_TICK
    private var lastUpdateAt = NO_TICK
    private var tapCooldownUntil = NO_TICK
    private var keyForcedUp = false

    /**
     * While set, the client may not (re-)start sprinting. Read by the `SprintEvent` handler in
     * [ModuleKillAura], which is what actually holds ModuleSprint and the vanilla input path off.
     */
    var blockSprint = false
        private set

    /**
     * While set, movement input is dropped — the "kill the momentum" half of a legit tap. Only ever
     * true when [stopMotion] is on.
     */
    var isMotionStopped = false
        private set

    private val isTapping
        get() = tapStartedAt != NO_TICK

    private val ticksSinceTap
        get() = player.tickCount - tapStartedAt

    private val ticksSinceUpdate
        get() = if (lastUpdateAt == NO_TICK) Int.MAX_VALUE else player.tickCount - lastUpdateAt

    /**
     * Whether a crit is both wanted and physically possible right now. `Criticals = Ignore` means we
     * do not care about crits at all, and the environment checks cover water, cobwebs, ladders,
     * levitation and the rest — [ModuleCriticals.allowsCriticalHit] is asked to ignore the ground
     * because the callers test airborne-ness themselves.
     */
    private val critsWanted
        get() = ModuleKillAura.criticalsSelectionMode != CriticalsSelectionMode.IGNORE &&
            ModuleCriticals.allowsCriticalHit(ignoreOnGround = true)

    /**
     * Called once per tick while a target is held, before the click decision is used. Returns
     * whether the aura may still attack this tick.
     */
    fun update(canAttack: Boolean): Boolean {
        lastUpdateAt = player.tickCount
        ModuleKillAura.debugParameter("Sprint Blocked") { blockSprint }

        return when (mode) {
            SprintMode.NORMAL -> {
                updateWindow()
                canAttack
            }
            SprintMode.LEGIT -> updateTap(canAttack)
        }
    }

    /**
     * [SprintMode.NORMAL]: hold sprint off for the whole airborne stretch.
     *
     * Re-asserting `isSprinting` every tick rather than once is deliberate — blocking `SprintEvent`
     * only stops a sprint from *starting*, so a sprint already under way when the window opens has
     * to be cleared by hand. Both are no-ops once the flag is down, so no packet comes of it.
     */
    private fun updateWindow() {
        if (critsWanted && !player.onGround()) {
            blockSprint = true
            player.isSprinting = false
        } else if (blockSprint) {
            releaseSprint()
        }
    }

    /**
     * [SprintMode.LEGIT]: arm a tap on the tick before the planned swing, then withhold the swing
     * until the stop has had its tick.
     */
    private fun updateTap(canAttack: Boolean): Boolean {
        if (isTapping) {
            ModuleKillAura.debugParameter("Sprint Tap Ticks") { ticksSinceTap }

            // Movement comes back as soon as the release has served its purpose, even if the swing
            // it was armed for is still waiting on range or the click plan. Holding WASD down for
            // the whole tap is what made this unplayable.
            val released = ticksSinceTap >= releaseTicks
            if (released) {
                isMotionStopped = false
            }

            return canAttack && released
        }

        if (!shouldTap()) {
            return canAttack
        }

        beginTap()
        return false
    }

    /**
     * Runs immediately after the attack packet. The hit is what a tap was waiting for, so it — not
     * the timeout — is what normally ends it.
     *
     * Nothing re-sprints by hand: clearing [blockSprint] lets `aiStep` pick the sprint back up later
     * in this same tick if the player is still asking for it, and vanilla reports that at
     * `sendPosition`. Forcing the flag here would be one more client-side lie about movement.
     */
    fun postAttack() {
        if (isTapping) {
            endTap()
        }
    }

    /**
     * Expires a tap whose swing never came, and — more importantly — hands sprint back when the aura
     * simply stops asking.
     *
     * [update] only runs on ticks the aura gets as far as having a target: it is skipped while combat
     * is paused, while ElytraTarget is flying, on a dead or spectating player, and on any other early
     * return. Sprint pinned off by a caller that then walked away is the one failure of this feature
     * the player cannot do anything about, so it is caught here rather than at each of those exits.
     */
    fun tick() {
        if (isTapping && ticksSinceTap >= TAP_TIMEOUT_TICKS) {
            endTap()
            return
        }

        if ((blockSprint || isTapping) && ticksSinceUpdate >= STALE_TICKS) {
            reset()
        }
    }

    /**
     * Drops everything in progress — module disabled, or the target is gone.
     */
    fun reset() {
        lastUpdateAt = NO_TICK

        if (isTapping) {
            endTap()
        } else if (blockSprint) {
            releaseSprint()
        }
    }

    private fun shouldTap() = critsWanted &&
        !player.onGround() &&
        player.isSprinting &&
        player.deltaMovement.y < FALL_THRESHOLD &&
        player.tickCount >= tapCooldownUntil &&
        KillAuraClicker.willClickAt(1)

    private fun beginTap() {
        tapStartedAt = player.tickCount
        blockSprint = true
        player.isSprinting = false

        keyForcedUp = true
        mc.options.keySprint.isDown = false

        isMotionStopped = stopMotion
    }

    private fun endTap() {
        mc.player?.let { tapCooldownUntil = it.tickCount + tapCooldown }
        tapStartedAt = NO_TICK
        isMotionStopped = false
        releaseSprint()
    }

    private fun releaseSprint() {
        blockSprint = false

        if (keyForcedUp) {
            // Hand the key back to the hardware rather than to whatever we forced it to.
            mc.options.keySprint.isDown = mc.options.keySprint.isPressedOnAny
            keyForcedUp = false
        }
    }

}
