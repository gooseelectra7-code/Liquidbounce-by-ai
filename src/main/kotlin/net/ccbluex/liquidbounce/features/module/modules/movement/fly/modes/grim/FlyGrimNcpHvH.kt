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

package net.ccbluex.liquidbounce.features.module.modules.movement.fly.modes.grim

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * An item-free fly for servers running Grim next to NCP, the usual HvH pair.
 *
 * ## What it is spending
 *
 * Grim scores every tick by the distance between the movement you sent and the closest movement it
 * could simulate, and accumulates it (checks/impl/prediction/OffsetHandler.java):
 *
 *     if (offset >= threshold || offset >= immediateSetbackThreshold) {
 *         advantageGained += offset;
 *         if (flag(...) && (advantageGained >= maxAdvantage || offset >= immediateSetbackThreshold)
 *                 && violations >= setbackViolationThreshold) executeViolationSetback();
 *         advantageGained = Math.min(advantageGained, maxCeiling);
 *     } else { advantageGained *= setbackDecayMultiplier; }
 *
 * So the resource is a number of **blocks**, not a number of ticks, and this mode mirrors it tick by
 * tick instead of counting how long it has been flying. That matters because the price of a tick is
 * not what it looks like. Grim carries your last accepted velocity into the next prediction, so the
 * only thing you ever pay for is the *change* you force on top of physics:
 *
 * - free fall, and horizontal movement within air control - **0**,
 * - holding altitude - **0.078 a tick, for no height at all**, the worst trade there is,
 * - climbing at a steady 0.3 - **0.084 a tick**, because the prediction already carries the 0.3,
 * - the tick that starts that climb - **0.38**, paid once.
 *
 * A budget of one block therefore buys roughly two or three blocks of height if it is spent as one
 * fast climb, and nothing whatsoever if it is spent hovering. [Profile.VERTICAL] and
 * [Profile.BOOST] are the shapes that convert it into height; [Profile.FLAT] is for standing still
 * over a fight and is deliberately the expensive one.
 *
 * ## Why it stops instead of being set back
 *
 * Crossing max-advantage does not cost one setback, it costs a lockout: every flagging tick after it
 * triggers another setback, so advantageGained climbs to max-ceiling (4) and needs
 * ln(4) / 0.001 = about 1400 clean ticks to come back down. Stopping at [Limits.safetyMargin] of the
 * measured budget keeps the number near 0.9 instead, where the wait is seconds. The budget itself is
 * learned - it starts at the ceiling, is cut to what was actually on the meter when a setback lands,
 * and grows back after clean bursts - because the server's real configuration is not visible from
 * here and old Grim builds differ.
 *
 * There is no sustained item-free fly on top of this and there cannot be: the advantage only refills
 * at 0.1 % per tick, so the honest output is a hop of a few blocks, recharging over seconds. Enough
 * to get over someone's obsidian, not enough to travel.
 *
 * ## The NCP side
 *
 * NCP does not simulate, it compares single steps against thresholds, so it gets a shape rather than
 * a budget: no single move climbs more than a legal jump ([Ncp.maxAscendPerTick], 0.42), and a tick
 * is given back to gravity every [Ncp.floatingGuard] ticks. That drop also covers the vanilla kick -
 * ServerGamePacketListenerImpl only counts a tick as floating while the vertical delta is
 * >= -0.03125 and there are no blocks around, and disconnects after
 * ceil(80 * max(0.08 / gravity, 1)) = 80 of them.
 *
 * @anticheat Grim
 * @anticheatVersion behaviour read from 2.3.74 sources, applies to older builds
 * @anticheat NoCheatPlus
 * @note Ground spoofing is deliberately absent: Grim derives ground state itself
 *       (GrimPlayer#exemptOnGround) and NCP does the same, so it would only add a NoFall flag.
 */
internal object FlyGrimNcpHvH : Mode("GrimNcpHvH") {

    /** Vanilla air physics, which is exactly what Grim predicts for a player in the air. */
    private const val GRAVITY = 0.08
    private const val VERTICAL_DRAG = 0.98
    private const val AIR_DRAG = 0.91

    override val parent: ModeValueGroup<*>
        get() = ModuleFly.modes

    private val profile by enumChoice("Profile", Profile.VERTICAL)

    private val horizontal by float("Horizontal", 0.32f, 0f..2f)
    private val ascend by float("Ascend", 0.35f, 0f..2f)
    private val descend by float("Descend", 0.3f, 0f..2f)
    private val boostTicks by int("BoostTicks", 6, 1..40, "ticks")

    private object Limits : ValueGroup("Limits") {
        /**
         * Where the learned budget starts. Grim's own ceiling on advantageGained is 4 blocks; the
         * user's HvH server measured ~2 (GrimProbe, 2026-08-22), so 2.5 lets the first burst land
         * near the real limit instead of overshooting into a setback before calibration catches up.
         */
        val startBudget by float("StartBudget", 2.5f, 0.1f..16f)

        /** Stop this far short of the budget, so advantageGained never reaches the lockout. */
        val safetyMargin by float("SafetyMargin", 0.7f, 0.1f..1f)

        /** Resume once the mirrored advantage has decayed back under this fraction of the budget. */
        val resumeAt by float("ResumeAt", 0.45f, 0f..1f)

        /** Grim's setback-decay-multiplier, applied on every tick that does not deviate. */
        val decay by float("Decay", 0.999f, 0.9f..1f)

        /**
         * How much horizontal acceleration a tick gets for free. Vanilla air control is 0.02, or
         * 0.026 while sprinting, and Grim tries every input, so that much costs nothing.
         */
        val airControl by float("AirControl", 0.026f, 0f..0.2f)

        /**
         * Refuse to spend more than this in one tick, to stay under Grim's
         * immediate-setback-threshold (0.1 by default). Off at 0, because a server that enforces it
         * sets you back on the first tick anyway and you want to see that happen.
         */
        val perTickCap by float("PerTickCap", 0f, 0f..0.5f)
    }

    /**
     * The budget is measured, not configured: cut to what was on the meter when a setback landed,
     * grown after a burst that ended on its own terms.
     */
    private object Calibrate : ToggleableValueGroup(this, "Calibrate", true) {
        val collapse by float("Collapse", 0.6f, 0.05f..1f)
        val growth by float("Growth", 1.1f, 1f..2f)
        val giveUpUnder by float("GiveUpUnder", 0.15f, 0.01f..1f)
    }

    private object Ncp : ValueGroup("Ncp") {
        val maxAscendPerTick by float("MaxAscendPerTick", 0.42f, 0.05f..2f)
        val floatingGuard by int("FloatingGuard", 36, 0..79, "ticks")
    }

    /**
     * What to do when the burst reaches the safe end of the budget. This is the normal way the fly
     * stops - on the meter, before any flag - so it is the hands-free cycle, not the setback path.
     *
     * [SpentAction.GLIDE] keeps the module on and just stops forcing motion until the mirrored
     * advantage has decayed back under [Limits.resumeAt], then flies again on its own. That is the
     * auto-disable and auto-enable of the flight in one: you hold space and it bursts, rests and
     * bursts again without touching the key.
     *
     * [SpentAction.DISABLE] turns ModuleFly off instead. Re-enabling is then manual - a disabled
     * module has no handler running to switch itself back on - so [ReEnable] exists to schedule it.
     */
    private object WhenSpent : ValueGroup("WhenSpent") {
        val action by enumChoice("Action", SpentAction.GLIDE)
        val notify by boolean("Notify", true)
    }

    /**
     * Auto re-enable after [SpentAction.DISABLE]. It cannot live on this mode - the module is off, so
     * nothing here ticks - so it rides [PlayerTickEvent] on ModuleFly's own always-registered
     * children through a scheduled callback set up in [enable]. Off by default; the GLIDE cycle is
     * the cleaner way to fly hands-free.
     */
    private object ReEnable : ToggleableValueGroup(this, "ReEnable", false) {
        val afterTicks by int("AfterTicks", 60, 1..1200, "ticks")
    }

    private object OnSetback : ValueGroup("OnSetback") {
        val action by enumChoice("Action", SetbackAction.RECOVER)
        val recoverTicks by int("RecoverTicks", 40, 0..600, "ticks")
        val notify by boolean("Notify", true)
    }

    init {
        tree(Limits)
        tree(Calibrate)
        tree(WhenSpent)
        tree(ReEnable)
        tree(Ncp)
        tree(OnSetback)
    }

    /** Our mirror of Grim's advantageGained, in blocks. */
    private var spent = 0.0

    /** What we believe max-advantage is on this server, in blocks. */
    private var budget = 0.0

    private var recoverTicks = 0
    private var airTicks = 0
    private var boostLeft = 0
    private var waiting = false
    private var gaveUp = false
    private var jumpWasDown = false

    private val ceiling
        get() = budget * Limits.safetyMargin

    override fun enable() {
        budget = Limits.startBudget.toDouble()
        spent = 0.0
        recoverTicks = 0
        airTicks = 0
        waiting = false
        gaveUp = false
        jumpWasDown = mc.options.keyJump.isDown
        boostLeft = if (profile == Profile.BOOST) boostTicks else 0

        // We are on again - cancel any pending auto re-enable, whether it fired us or the user did.
        FlyReEnableClock.disarm()
    }

    override fun disable() {
        boostLeft = 0
        recoverTicks = 0
        super.disable()
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        // Plain physics. This is the half of the cycle that pays for the other half: Grim only
        // decays advantageGained on ticks where the offset is under its threshold.
        if (recoverTicks > 0) {
            recoverTicks--
            idle()
            return@handler
        }

        if (player.onGround()) {
            airTicks = 0
            armBoostFromGround()
        } else {
            airTicks++

            // Hand the tick back to gravity. Vanilla stops counting floating ticks as soon as the
            // vertical delta drops under -0.03125, and NCP's hover check wants to see the same.
            if (Ncp.floatingGuard > 0 && airTicks % Ncp.floatingGuard == 0) {
                idle()
                return@handler
            }
        }

        if (waiting) {
            idle()

            if (spent <= budget * Limits.resumeAt) {
                waiting = false
                if (WhenSpent.notify) {
                    notification("Fly", "Recharged, flying", NotificationEvent.Severity.INFO)
                }
            } else {
                return@handler
            }
        }

        val delta = player.deltaMovement
        val target = targetVelocity(delta)
        if (target == null) {
            idle()
            return@handler
        }

        val limit = Ncp.maxAscendPerTick.toDouble()
        val wanted = capped(
            target.subtract(delta).let { Vec3(it.x, it.y.coerceIn(-limit, limit), it.z) }
        )

        val price = priceOf(delta, delta.add(wanted))
        if (price < 1.0E-4) {
            // Free movement still counts as a clean tick for Grim, so it decays.
            player.deltaMovement = delta.add(wanted)
            idle()
            return@handler
        }

        if (spent + price > ceiling) {
            onBudgetSpent()
            return@handler
        }

        player.deltaMovement = delta.add(wanted)
        spent += price
    }

    /**
     * What Grim will score this tick: the distance between where we end up and where its own
     * simulation would have put us, with the horizontal input it grants for free taken off.
     */
    private fun priceOf(before: Vec3, after: Vec3): Double {
        val predictedY = (before.y - GRAVITY) * VERTICAL_DRAG
        val dy = after.y - predictedY

        val free = Limits.airControl.toDouble()
        val dx = trim(after.x - before.x * AIR_DRAG, free)
        val dz = trim(after.z - before.z * AIR_DRAG, free)

        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun trim(value: Double, free: Double) =
        if (abs(value) <= free) 0.0 else value - free * (if (value > 0) 1.0 else -1.0)

    /**
     * Grim writes its setback teleport straight to the channel with an id built as
     * random.nextInt() | Integer.MIN_VALUE (SetbackTeleportUtil#sendSetback), so it is always
     * negative, while the server numbers its own teleports up from one. That is the only way to tell
     * which of the two anti-cheats answered.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet !is ClientboundPlayerPositionPacket || recoverTicks > 0) {
            return@handler
        }

        onSetback(fromGrim = packet.id() < 0, meter = spent)
    }

    private fun onSetback(fromGrim: Boolean, meter: Double) {
        if (Calibrate.enabled) {
            // The meter is the evidence: whatever we had spent when it landed was too much.
            budget = (meter * Calibrate.collapse).coerceAtLeast(0.02)
        }

        recoverTicks = OnSetback.recoverTicks
        waiting = true
        boostLeft = 0

        // Grim clamps its own advantage at max-ceiling and does not reset it on a setback, so from
        // here on assume the worst case rather than our own count.
        spent = Limits.startBudget.toDouble()

        if (OnSetback.notify) {
            val source = if (fromGrim) "Grim" else "server/NCP"
            notification(
                "Fly",
                "$source setback at ${"%.2f".format(meter)}b, budget -> ${"%.2f".format(budget)}b",
                NotificationEvent.Severity.INFO
            )
        }

        if (Calibrate.enabled && budget < Calibrate.giveUpUnder && !gaveUp) {
            gaveUp = true
            notification(
                "Fly", "Server tolerates under ${"%.2f".format(budget)} blocks, only hops are possible",
                NotificationEvent.Severity.ERROR
            )
        }

        if (OnSetback.action == SetbackAction.DISABLE) {
            ModuleFly.enabled = false
        }
    }

    /**
     * The burst reached the safe end of the budget without a flag. This is the intended stopping
     * point, so it is where auto-disable and the hands-free cycle live - never the setback path.
     */
    private fun onBudgetSpent() {
        if (waiting) {
            return
        }

        waiting = true
        idle()

        if (Calibrate.enabled) {
            // We stopped on our own terms, so the budget survived a full burst - reach a little
            // further next time.
            budget = (budget * Calibrate.growth).coerceAtMost(Limits.startBudget.toDouble())
        }

        if (WhenSpent.action == SpentAction.DISABLE) {
            if (WhenSpent.notify) {
                notification("Fly", "Budget spent, disabling", NotificationEvent.Severity.INFO)
            }

            // Arm the external clock BEFORE the disable so the countdown outlives this mode's
            // handlers. A disabled module cannot tick itself back on.
            if (ReEnable.enabled) {
                FlyReEnableClock.arm(ReEnable.afterTicks)
            } else {
                FlyReEnableClock.disarm()
            }

            ModuleFly.enabled = false
        } else if (WhenSpent.notify) {
            notification(
                "Fly", "Budget spent (${"%.2f".format(budget)}b), gliding", NotificationEvent.Severity.INFO
            )
        }
    }

    /** A tick of plain physics, which is what lets Grim's advantage decay. */
    private fun idle() {
        spent *= Limits.decay
    }

    /**
     * Height is the point of a fly, so when a per-tick cap is set the vertical part is served first
     * and the horizontal takes what is left.
     */
    private fun capped(wanted: Vec3): Vec3 {
        val cap = Limits.perTickCap.toDouble()
        if (cap <= 0.0 || wanted.length() <= cap) {
            return wanted
        }

        val y = wanted.y.coerceIn(-cap, cap)
        val left = sqrt((cap * cap - y * y).coerceAtLeast(0.0))
        val h = hypot(wanted.x, wanted.z)

        return if (h <= left || h < 1.0E-9) {
            Vec3(wanted.x, y, wanted.z)
        } else {
            Vec3(wanted.x * left / h, y, wanted.z * left / h)
        }
    }

    private fun armBoostFromGround() {
        if (profile != Profile.BOOST) {
            return
        }

        val jumpDown = mc.options.keyJump.isDown
        if (jumpDown && !jumpWasDown) {
            boostLeft = boostTicks
        }
        jumpWasDown = jumpDown
    }

    private fun targetVelocity(delta: Vec3): Vec3? {
        val moving = DirectionalInput(player.input).isMoving
        val steered = if (moving) delta.withStrafe(speed = horizontal.toDouble()) else delta

        return when (profile) {
            Profile.FLAT -> Vec3(steered.x, 0.0, steered.z)

            Profile.VERTICAL -> {
                val y = when {
                    mc.options.keyJump.isDown -> ascend.toDouble()
                    mc.options.keyShift.isDown -> -descend.toDouble()
                    // Nothing pressed means nothing forced, so the tick is free and the meter
                    // drains instead of paying to hover.
                    else -> return null
                }

                Vec3(steered.x, y, steered.z)
            }

            Profile.BOOST -> {
                val jumpDown = mc.options.keyJump.isDown
                if (jumpDown && !jumpWasDown && boostLeft <= 0) {
                    boostLeft = boostTicks
                }
                jumpWasDown = jumpDown

                if (boostLeft <= 0) {
                    return null
                }

                boostLeft--
                Vec3(steered.x, ascend.toDouble(), steered.z)
            }
        }
    }

    enum class Profile(override val tag: String) : Tagged {
        /** Hold the current height. Costs 0.078 a tick and buys no height - use it knowingly. */
        FLAT("Flat"),

        /** Space climbs, shift drops, released is free. The efficient way to spend the budget. */
        VERTICAL("Vertical"),

        /** One climb per jump press, then plain physics while the meter drains. */
        BOOST("Boost"),
    }

    enum class SpentAction(override val tag: String) : Tagged {
        /** Keep the module on, stop forcing motion, and fly again once the budget refills. */
        GLIDE("Glide"),

        /** Turn ModuleFly off. Pair with [ReEnable] for a hands-free on/off cycle. */
        DISABLE("Disable"),
    }

    enum class SetbackAction(override val tag: String) : Tagged {
        RECOVER("Recover"),
        DISABLE("Disable"),
    }

}

/**
 * An always-running re-enable clock for [FlyGrimNcpHvH]'s [FlyGrimNcpHvH.SpentAction.DISABLE]. It has
 * to be its own listener: once ModuleFly is off, neither the module nor the mode ticks, so nothing
 * inside them could switch it back on. This object has no parent, so its [running] stays true
 * regardless of ModuleFly, and it counts down on the client tick.
 */
private object FlyReEnableClock : EventListener, MinecraftShortcuts {

    private var ticksLeft = 0

    fun arm(ticks: Int) {
        ticksLeft = ticks
    }

    fun disarm() {
        ticksLeft = 0
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (ticksLeft <= 0) {
            return@handler
        }

        // Do not count down on a title screen or between worlds - re-enabling there is pointless.
        if (mc.player == null || mc.level == null) {
            ticksLeft = 0
            return@handler
        }

        if (--ticksLeft == 0) {
            ModuleFly.enabled = true
        }
    }
}
