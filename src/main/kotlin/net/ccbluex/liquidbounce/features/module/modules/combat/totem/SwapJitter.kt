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
package net.ccbluex.liquidbounce.features.module.modules.combat.totem

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import kotlin.random.Random

/**
 * Spreads the pop-to-replenish interval so TotemGuard's `AutoTotemB` cannot read a machine off it.
 *
 * ## What is actually measured
 *
 * `TotemReplenishedDetector.onInventoryChanged:71-78` records
 * `deltaRaw = replenishedAt - lastTotemUse` - from the totem popping to the tick a HAND slot is
 * seen going from not-totem to totem. `PacketInventory.isHandSlot:183-185` is the offhand OR the
 * main-hand slot, so every route this fork has is measured, main hand included. Intervals above
 * `MAX_TRACKED_INTERVAL_MS` (2000) are discarded and produce no sample at all.
 *
 * `AutoTotemB.onTotemReplenished` then takes the SAMPLE standard deviation (`ss / (n - 1)`,
 * `MathUtil.java:79-94`) of the last 5 intervals and scores:
 * `stdDev < 5` punishes 3.0, `< 15` punishes 2.0, `> 60` rewards 1.0, against
 * `flagThreshold() = 4.0`. Two sub-5 samples in a row is 6.0 and an instant flag.
 *
 * Two properties make this worse than a normal heuristic, and they are why the default is not OFF:
 * - **`decayPerSecond() = 0`.** Unlike every other `HeuristicCheck`, time never cleans the buffer.
 *   The only way down is the reward, so a spread that never exceeds 60 ms means any unlucky run
 *   stays in the buffer for the rest of the session.
 * - **`Buffer.clamp:26-30` pins negatives to 0.** Credit cannot be banked ahead of time; rewards
 *   only cancel punishment that has already landed, one for one.
 *
 * ## Why this is a HOLD and not a randomised pacing
 *
 * The obvious implementation - randomising `ACTION_DELAY_MS` - does not work, and this is the
 * mistake to avoid repeating. That constant is the minimum SPACING between two clicks of ours, not
 * a delay measured from the pop. By the time a totem pops the spacing has almost always elapsed
 * already, so the interval the anticheat sees is dominated by `InventoryManager`'s `StartDelay`
 * and not by our pacing at all. Randomising it would move a number nobody measures.
 *
 * So the jitter is a hold applied AFTER the trigger fires and BEFORE the swap goes out, which is
 * exactly the span `deltaRaw` covers.
 *
 * ## Why the spread is counted in ticks
 *
 * The client can only act on tick boundaries, so any randomisation finer than 50 ms is invisible -
 * it rounds to the same tick and the interval comes out constant. A hold drawn uniformly from
 * `0 until ticks` gives that many distinct values spaced 50 ms apart, whose standard deviation is
 * `50 * sqrt((n^2 - 1) / 12)`:
 *
 * | ticks | stdDev | lands in |
 * |---|---|---|
 * | 4 | 56 ms | the 15..60 dead band: no punish, but no reward either |
 * | 5 | 71 ms | above 60, earns the reward |
 * | 8 | 115 ms | wide margin, even unlucky draws stay above 60 |
 *
 * [Mode.LOW] is in the dead band on purpose: it is the choice for someone who would rather not
 * delay the totem and accepts that the buffer never cleans itself.
 *
 * One instance per module, owned by the module, so the spread and the draw are that module's
 * alone - a swap by one manager can no longer inherit a hold drawn for another. The table above is
 * the whole basis for picking a number, so anything under 5 is a deliberate choice to sit in the
 * dead band, not a safe middle. [Mode.CUSTOM] takes it from [customTicks].
 */
class SwapJitter(owner: EventListener) :
    ToggleableValueGroup(owner, "SwapJitter", true), MinecraftShortcuts {

    private val NOT_ARMED = Int.MIN_VALUE

    /**
     * The spread used by [Mode.CUSTOM], as the NUMBER OF DISTINCT tick values - not a duration.
     *
     * The hold is uniform over `0 until this`, so it contributes `50 * sqrt((n^2 - 1) / 12)` ms of
     * standard deviation. Below 2 there is no spread at all and the interval comes out constant,
     * which is the case `AutoTotemB` punishes hardest - so 0 and 1 mean "no jitter", not "a little".
     *
     * Hidden unless [mode] is [Mode.CUSTOM] - see [syncCustomVisibility]. Declared BEFORE [mode]
     * on purpose: the change hook below touches this field, and a hook firing against a field the
     * constructor has not reached yet would read null.
     */
    private val customTicksValue = int("CustomTicks", 5, 0..20, "ticks")

    private val customTicks by customTicksValue

    /**
     * How wide the hold is drawn. See the class KDoc for what each one costs.
     *
     * The hook is what drives [Mode.CUSTOM]'s setting in and out of the GUI. It fires on a config
     * LOAD too, not just on a click: `Value.deserializeFrom` finishes by calling `set`
     * (`config/types/Value.kt:307`), and `set` runs the changed-listeners (`:244`).
     */
    private val mode by enumChoice("Mode", Mode.NORMAL)
        .onChanged { syncCustomVisibility(it) }

    init {
        // A change hook cannot cover the INITIAL state - nothing changed yet - so the default (and
        // a saved value that happens to equal it) needs this.
        syncCustomVisibility(mode)
    }

    /**
     * Shows [customTicksValue] only under [Mode.CUSTOM].
     *
     * `notAnOption` is a plain `var` on `Value` (`config/types/Value.kt:110`) and both ClickGUIs
     * skip a value that has it set (`HellhitClickGuiScreen.kt:1358`, and it is excluded from
     * `hasVisibleSettings` at `:1369`), so flipping it at runtime IS conditional visibility - this
     * config system has no dedicated mechanism for it. Safe for persistence: the config writer is
     * built with `includeNotAnOption = true` (`ValueGroupSerializer.kt:44-46`), so a hidden value
     * keeps its saved number and gets it back when the mode returns.
     */
    private fun syncCustomVisibility(current: Mode) {
        customTicksValue.notAnOption(current != Mode.CUSTOM)
    }

    /** Tick the current hold was armed on, or [NOT_ARMED] when no swap is pending. */
    private var armedTick = NOT_ARMED

    /**
     * Spread the current draw was made against, or -1.
     *
     * The state here is shared by every totem manager, which is fine while only one of them runs -
     * they are documented as mutually exclusive. It stops being fine once a caller supplies its own
     * spread: a draw made for 5 ticks must not be reused as if it had been made for 12. So a change
     * of spread re-draws, which also means a caller can switch its setting mid-fight without
     * inheriting a hold from the old range.
     */
    private var rolledForTicks = -1

    /**
     * Ticks the current swap has to wait, drawn once per swap.
     *
     * Public for the debug read-out only; the owning module reports it.
     */
    var rolledTicks = 0
        private set

    @Suppress("unused")
    enum class Mode(override val tag: String, @JvmField val ticks: Int) : Tagged {
        /** 4 ticks, stdDev about 56 ms - under the reward line, so the buffer never cleans. */
        LOW("Low", 4),

        /** 5 ticks, stdDev about 71 ms - the least that reliably earns the reward. */
        NORMAL("Normal", 5),

        /** 8 ticks, stdDev about 115 ms. Safe margin, but the totem can be 350 ms late. */
        HIGH("High", 8),

        /** Whatever [customTicks] says. There is no `Off` - the group's own toggle is that. */
        CUSTOM("Custom", -1),
    }

    /** The spread in force, resolving [Mode.CUSTOM] against [customTicks]. */
    private val spreadTicks: Int
        get() = if (mode == Mode.CUSTOM) customTicks else mode.ticks

    /**
     * Whether the jittered hold for the current swap has elapsed.
     *
     * Arms and draws on the first call of a swap, so a caller only ever has to ask. Safe to call
     * many times per tick - `ScheduleInventoryActionEvent` fires once per `InventoryManager` cycle -
     * because the draw is kept until [release].
     */
    fun allows(): Boolean {
        if (!enabled) {
            return true
        }

        val ticks = spreadTicks
        if (ticks <= 0) {
            return true
        }

        val tick = mc.player?.tickCount ?: return false

        // A NEGATIVE elapsed means `tickCount` restarted under us. Respawning, changing dimension
        // or reconnecting builds a new `LocalPlayer` whose count starts at 0 again, which leaves
        // `armedTick` sitting in the future - and then `elapsed >= rolledTicks` cannot become true
        // until the new player has ticked all the way past the OLD one's count.
        //
        // Reported 2026-09-17 and found in the log this had just been added for: `roll=0 left=881`
        // (contradictory on its face - a zero roll holds nothing) and `roll=3 left=4002`, a hold of
        // over three minutes that no amount of waiting could satisfy. `TotemStaging.intentHeldTicks`
        // and both of `SwapQuiet`'s tick reads already guard this with a range test; this was the
        // one place that did not.
        val elapsed = tick - armedTick

        if (armedTick == NOT_ARMED || rolledForTicks != ticks || elapsed < 0) {
            armedTick = tick
            rolledTicks = Random.nextInt(ticks)
            rolledForTicks = ticks

            // Freshly armed, so nothing has elapsed: only a zero draw is satisfied right away.
            return rolledTicks == 0
        }

        return elapsed >= rolledTicks
    }

    /**
     * Ticks still to wait on the current hold, or -1 when no hold is armed. Diagnostic only.
     */
    val remainingTicks: Int
        get() {
            if (armedTick == NOT_ARMED) {
                return -1
            }

            val tick = mc.player?.tickCount ?: return -1
            val elapsed = tick - armedTick

            // Same restart as in [allows]. Reporting the raw difference is what produced the
            // nonsense `left=4002` in the log; -1 says "nothing valid is held" and the next
            // [allows] re-draws.
            if (elapsed < 0) {
                return -1
            }

            return (rolledTicks - elapsed).coerceAtLeast(0)
        }

    /**
     * Ends the current hold so the next swap draws a fresh one.
     *
     * Call this where the swap is actually emitted, NOT where it is merely wanted - a hold that is
     * released without a swap having gone out re-draws against the same pop and correlates
     * consecutive intervals, which is the opposite of the point.
     */
    fun release() {
        armedTick = NOT_ARMED
        rolledTicks = 0
        rolledForTicks = -1
    }

}
