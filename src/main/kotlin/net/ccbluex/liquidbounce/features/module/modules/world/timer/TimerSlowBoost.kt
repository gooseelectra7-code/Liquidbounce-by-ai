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
package net.ccbluex.liquidbounce.features.module.modules.world.timer

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.GameRenderTaskQueueEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.features.module.modules.world.ModuleTimer
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.CRITICAL_MODIFICATION
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * SlowBoost - "slow and boost" timer, ported from the `TimerSpooky` / `TimerGrimSP` choice the user supplied
 * (godweer, author "daun kvass"). The port keeps the mechanism and replaces its five hand-tuned constants
 * (charge 1.25 s, boost 2.4 s, 4 jumps, 0.05, 1.7) with a client-side mirror of the balance Grim itself keeps -
 * see "The budget" below for why those constants and this model agree.
 *
 * ## What the cycle does
 *
 * Grim's `Timer` check does not measure a tick RATE, it keeps a balance: every tick packet adds 50 ms to
 * `timerBalanceRealTime` and the player is flagged the moment that balance passes real time
 * (`checks/impl/timer/Timer.java:69` and `:75`). Running the game slow therefore BANKS credit - real time runs
 * away from the balance - and running it fast spends that credit again. The cycle here is exactly that: a
 * CHARGE phase at `ChargeSpeed` to bank, then a BOOST phase at `BoostSpeed` to spend, which buys a burst of
 * ~1.7x movement without the balance ever passing real time.
 *
 * ## Why the transaction delay is the whole trick
 *
 * On its own the bank is worthless: `limitFallBehind` raises the balance back to
 * `lastMovementPlayerClock - clockDrift` on every movement packet (`Timer.java:95-97`, drift default 120 ms,
 * `:111`), and `lastMovementPlayerClock` is refreshed by the FIRST transaction that follows a movement packet
 * (`:60-64`, `checkForTransaction` = `PONG`, `:99-101`). So the bank normally saturates at ping + 120 ms.
 *
 * Withholding our `ServerboundPongPacket`s freezes that clock: with no PONG arriving, `lastMovementPlayerClock`
 * stops advancing, the floor goes stale and the balance is free to fall arbitrarily far behind. `Timer` itself
 * is then unbounded - but `TimerLimit`, a second live instance of the same check, floors the clock at
 * `System.nanoTime() - ping-abuse-limit-threshold` (default 1000 ms) instead
 * (`checks/impl/timer/TimerLimit.java:38-46`, `:51`). **That is the real ceiling: 1000 + 120 = 1120 ms of
 * credit**, and it is why `Budget` is capped at 1100 ms here.
 *
 * Delaying the pongs is safe against the transaction bookkeeping itself as long as they go out IN ORDER and all
 * of them go out: `TransactionOrder` flags only a gap (`player/GrimPlayer.java:429-430`), and the pong is not
 * seen by the server at all - Grim cancels it before it (`events/packets/PacketPingListener.java:72-76`).
 * The hard limit is the timeout: `pollData` kicks when no transaction has been answered for
 * `max-transaction-time` seconds, default 60 (`GrimPlayer.java:571-573`, `config/en.yml:44`). One cycle holds
 * them for ~2.5 s and [MAX_HOLD_NANOS] bounds the idle HOLD phase at 20 s, so the margin is 3x at worst.
 *
 * ## The budget
 *
 * The balance is mirrored here from the real packet stream rather than assumed from the timer setting:
 * [balanceNanos] adds 50 ms per outgoing tick packet - a flying packet, or `CLIENT_TICK_END` on a tick that sent
 * no flying packet, which is exactly what Grim counts (`checks/GrimProcessor.java:70-91`) - and is floored at
 * `now - Budget` the way `limitFallBehind` floors Grim's. `credit = now - balance` is therefore the same number
 * Grim holds, up to the parts deliberately not modelled (see the limits below). CHARGE runs until the credit
 * reaches `Budget`, BOOST until it is back down to one tick.
 *
 * That makes the port's constants derivable instead of magic: 1000 ms of credit at `ChargeSpeed` 0.05 needs
 * 1000 / 0.95 = 1053 ms of charging (the port charges 1250 ms) and buys 1000 / 0.7 = 1428 ms of boost at 1.7x
 * (the port caps at 2400 ms but ends on the 4th landed jump, ~1.4 s). The model lands where the hand-tuned
 * numbers did, and unlike them it stays correct when the speeds are changed.
 *
 * ## What this mode cannot do, stated plainly
 *
 * The cycle's average speed is 1.0x by construction - credit spent equals credit banked, which is what the
 * check enforces. This buys a BURST (1.7x for ~1.4 s), not distance: over a minute of running it is not faster
 * than walking, and the charge phase is a real cost, at 0.05 the client ticks once per second and the player is
 * close to frozen. Use it to close or break a gap, not as a Speed module.
 *
 * ## Known limits, not bugs
 *
 * - `NegativeTimer` would flag the charge phase outright, but it is `experimental = true`
 *   (`checks/impl/timer/NegativeTimer.java:13`) and every shipped config ships `experimental-checks: false`.
 *   On a server that opted in, do not use this mode.
 * - Vehicles: `VehicleTimer` keeps its own balance over VEHICLE_MOVE/STEER packets
 *   (`checks/impl/timer/VehicleTimer.java:16-32`) which this model does not count at all, so the cycle stops
 *   entirely while riding.
 * - Withholding transactions also stalls Grim's lag compensation: server block changes are applied to its world
 *   on transaction receipt, so for the length of a cycle its world can miss blocks that were PLACED by someone
 *   else. Clicking such a block reads as air to `AirLiquidPlace`. Our own placements are not affected - they are
 *   drained on the next flying packet (`events/packets/CheckManagerListener.java:680`) - but a crystal or anchor
 *   aura working on foreign blocks during a cycle is a real risk. `DelayTransactions` turns the trick off.
 * - With `DelayTransactions` off the real ceiling is ping + 120 ms, which this model does not know. Lower
 *   `Budget` by hand to about the ping, or the boost will overspend.
 */
object TimerSlowBoost : Mode("SlowBoost", aliases = listOf("SlowAndBoost", "TimerGrimSP")) {

    override val parent: ModeValueGroup<Mode>
        get() = ModuleTimer.modes

    private val chargeSpeed by float("ChargeSpeed", 0.05f, 0.05f..1f)
    private val boostSpeed by float("BoostSpeed", 1.7f, 1.05f..3f)

    /**
     * How much banked time a boost may spend. The ceiling Grim enforces is `ping-abuse-limit-threshold`
     * (1000 ms) plus `drift` (120 ms); the default keeps 120 ms of that as margin.
     */
    private val budget by int("Budget", 1000, 100..1100, "ms")

    private val delayTransactions by boolean("DelayTransactions", true)

    /**
     * Bank while standing still and then HOLD the bank at 1.0x until the player moves. Off by default because
     * the supplied source stops the cycle whenever the movement keys are released; on, the slow phase is paid
     * for while it costs no distance, which is what makes the burst usable on demand.
     */
    private val chargeWhileStill by boolean("ChargeWhileStill", false)

    private val forceJump by boolean("Jump", true)
    private val forceSprint by boolean("Sprint", true)

    /** One client tick as Grim accounts for it (`Timer.java:69`). */
    private const val TICK_NANOS = 50_000_000L

    /** The boost stops with one tick of credit left over, never at exactly zero. */
    private const val SAFETY_NANOS = TICK_NANOS

    /** Dead-man's switch: neither phase can ever be the reason the cycle stops making progress. */
    private const val MAX_PHASE_NANOS = 10_000_000_000L

    /**
     * Ceiling on one continuous withholding run, well under the 60 s `max-transaction-time` kick
     * (`GrimPlayer.java:571-573`). Only reachable through HOLD, which by design waits for the player.
     */
    private const val MAX_HOLD_NANOS = 20_000_000_000L

    /** Insurance against a stall nothing else caught - flush and let the clock catch up rather than pile up. */
    private const val MAX_QUEUED_TRANSACTIONS = 400

    private enum class Phase { CHARGE, HOLD, BOOST }

    private var phase = Phase.CHARGE
    private var cycleActive = false
    private var phaseStartedAt = 0L

    /**
     * Mirror of Grim's `timerBalanceRealTime`. Written from the packet handler, which runs on the sending
     * thread, hence the atomic.
     */
    private val balanceNanos = AtomicLong(0L)

    @Volatile
    private var sentMoveThisTick = false

    @Volatile
    private var withholding = false

    @Volatile
    private var setbackSeen = false

    private val delayedTransactions = ConcurrentLinkedQueue<ServerboundPongPacket>()

    override fun enable() {
        resetState()
    }

    override fun disable() {
        flushTransactions()
        resetState()

        // A lapsed request expires after two TICKS, and a tick at ChargeSpeed 0.05 is a real second - long
        // enough to strand the game at 1/20 speed after a mode switch. Hand the speed back explicitly.
        Timer.requestTimerSpeed(1f, Priority.IMPORTANT_FOR_USAGE_1, ModuleTimer)
    }

    /**
     * The phase machine runs per FRAME, not per tick. At ChargeSpeed 0.05 a tick is one real second, so a
     * tick-driven machine would overshoot the charge by up to a second and the boost by the same - the port has
     * that flaw, and it is the reason its charge is a hard 1.25 s rather than the 1.05 s it means.
     * `GameRenderTaskQueueEvent` fires at the head of `Minecraft.runTick`, ahead of the `DeltaTracker` call the
     * timer hooks, so a speed set here applies to the very frame that set it.
     */
    @Suppress("unused")
    private val frameHandler = handler<GameRenderTaskQueueEvent> {
        if (setbackSeen) {
            setbackSeen = false

            // A setback is the one state where our own accounting cannot be trusted - it may BE the timer flag.
            // Drop the bank, hand the transactions over and start the cycle again from zero credit.
            flushTransactions()
            balanceNanos.set(System.nanoTime())
            if (cycleActive) {
                beginPhase(Phase.CHARGE)
            }
        }

        // The model counts player flying packets only; in a vehicle the tick packets are the ones VehicleTimer
        // counts and we would be blind to our own spending.
        if (player.isPassenger) {
            stopCycle()
            return@handler
        }

        val moving = DirectionalInput(player.input).isMoving
        if (!moving && !chargeWhileStill) {
            stopCycle()
            return@handler
        }

        if (!cycleActive) {
            cycleActive = true
            balanceNanos.set(System.nanoTime())
            beginPhase(Phase.CHARGE)
        }

        withholding = delayTransactions
        if (!delayTransactions || delayedTransactions.size > MAX_QUEUED_TRANSACTIONS) {
            flushTransactions()
        }

        val credit = clampAndReadCredit()
        val elapsed = System.nanoTime() - phaseStartedAt

        when (phase) {
            Phase.CHARGE -> if (credit >= budgetNanos() || elapsed > MAX_PHASE_NANOS) {
                beginPhase(if (moving) Phase.BOOST else Phase.HOLD)
            }

            Phase.HOLD -> when {
                moving -> beginPhase(Phase.BOOST)
                elapsed > MAX_HOLD_NANOS -> {
                    stopCycle()
                    return@handler
                }
                else -> {}
            }

            Phase.BOOST -> if (!moving || credit <= SAFETY_NANOS || elapsed > MAX_PHASE_NANOS) {
                beginPhase(Phase.CHARGE)
            }
        }

        Timer.requestTimerSpeed(speedOf(phase), Priority.IMPORTANT_FOR_USAGE_1, ModuleTimer)

        debugParameter("Phase") { phase }
        debugParameter("Credit") { credit / 1_000_000L }
        debugParameter("Queued") { delayedTransactions.size }
    }

    /**
     * Runs on the sending thread for outgoing packets and on netty for incoming ones, so it touches nothing but
     * the queue and the atomics - the world is read in the frame handler.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        when (val packet = event.packet) {
            // The pong is the transaction response. Queue it, never drop it: a gap in the ids is what
            // TransactionOrder flags (GrimPlayer.java:429-430).
            is ServerboundPongPacket -> if (event.origin == TransferOrigin.OUTGOING && withholding) {
                delayedTransactions.add(packet)
                event.cancelEvent()
            }

            // Grim counts every flying packet, and CLIENT_TICK_END only on a tick that sent none
            // (GrimProcessor.java:70-91). Teleport-confirm flyings are excluded there and counted here, which
            // can only make our credit read lower than the real one.
            is ServerboundMovePlayerPacket -> if (event.origin == TransferOrigin.OUTGOING) {
                balanceNanos.addAndGet(TICK_NANOS)
                sentMoveThisTick = true
            }

            is ServerboundClientTickEndPacket -> if (event.origin == TransferOrigin.OUTGOING) {
                if (!sentMoveThisTick) {
                    balanceNanos.addAndGet(TICK_NANOS)
                }
                sentMoveThisTick = false
            }

            is ClientboundPlayerPositionPacket -> if (event.origin == TransferOrigin.INCOMING) {
                setbackSeen = true
            }

            else -> {}
        }
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (forceJump && cycleActive && phase == Phase.BOOST && event.directionalInput.isMoving &&
            player.onGround()
        ) {
            event.jump = true
        }
    }

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent>(priority = CRITICAL_MODIFICATION) { event ->
        if (!forceSprint || !cycleActive || phase != Phase.BOOST || !event.directionalInput.isMoving) {
            return@handler
        }

        // Same two sources ModuleSprint writes: the network source would change what is announced without
        // changing what the player does.
        if (event.source == SprintEvent.Source.INPUT || event.source == SprintEvent.Source.MOVEMENT_TICK) {
            event.sprint = true
        }
    }

    private fun speedOf(current: Phase) = when (current) {
        Phase.CHARGE -> chargeSpeed
        Phase.HOLD -> 1f
        Phase.BOOST -> boostSpeed
    }

    private fun budgetNanos() = budget * 1_000_000L

    /**
     * `limitFallBehind` (`Timer.java:95-97`), mirrored: the balance can never be further behind real time than
     * the floor Grim clamps it to, so neither can our estimate of the credit.
     */
    private fun clampAndReadCredit(): Long {
        val now = System.nanoTime()
        val floor = now - budgetNanos()
        val balance = balanceNanos.updateAndGet { current -> if (current < floor) floor else current }
        return now - balance
    }

    /** A charge always begins by handing the withheld transactions over, exactly as the source does. */
    private fun beginPhase(next: Phase) {
        if (next == Phase.CHARGE) {
            flushTransactions()
        }

        phase = next
        phaseStartedAt = System.nanoTime()
    }

    /**
     * Idempotent: the frame handler calls this on every idle frame, so the timer request is filed only on the
     * frame the cycle actually ends. Once filed it expires by itself two ticks later, and those two ticks are
     * already running at 1.0x.
     */
    private fun stopCycle() {
        flushTransactions()
        withholding = false

        if (!cycleActive) {
            return
        }

        cycleActive = false
        phase = Phase.CHARGE
        phaseStartedAt = 0L
        Timer.requestTimerSpeed(1f, Priority.IMPORTANT_FOR_USAGE_1, ModuleTimer)
    }

    private fun resetState() {
        cycleActive = false
        withholding = false
        setbackSeen = false
        sentMoveThisTick = false
        phase = Phase.CHARGE
        phaseStartedAt = 0L
        balanceNanos.set(System.nanoTime())
    }

    private fun flushTransactions() {
        while (true) {
            // Silently: the send hook is what queued it in the first place (MixinConnection hooks
            // Connection.send(Packet), sendPacketSilently takes the two-argument overload).
            sendPacketSilently(delayedTransactions.poll() ?: return)
        }
    }

}
