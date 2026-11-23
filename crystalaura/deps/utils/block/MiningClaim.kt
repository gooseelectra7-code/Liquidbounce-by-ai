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

package net.ccbluex.liquidbounce.utils.block

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.core.BlockPos

/**
 * Who, in this whole client, is currently breaking a block.
 *
 * Two separate rules make this necessary, and neither is optional.
 *
 * **The server's rule.** Only one dig may be in progress at a time: a START for a second position
 * while the first is unfinished is what Grim's WrongBreak check is looking for. Every module that
 * breaks blocks therefore has to know whether somebody else got there first.
 *
 * **Vanilla's rule.** `Minecraft.handleKeybinds` ends any breaking the attack key is not holding
 * up — it calls `stopDestroyBlock()` once per tick, *before* the tick our own code runs in. So a
 * dig driven from anywhere other than the mouse is torn down between two of its own swings and no
 * progress ever accumulates. The fix is to cancel `CancelBlockBreakingEvent`, and the question
 * "should I cancel it right now" is exactly "is one of ours mining", which is what this answers.
 *
 * ## Why a claim and not a flag
 *
 * The cancellation is asked for *before* the holder gets its tick, so a flag set during our tick
 * and cleared at the end of it is already gone by the time anyone consults it. A claim therefore
 * lasts a few ticks and is renewed by whoever still wants it.
 *
 * ## Probes
 *
 * Not every digger keeps a claim. [ModuleSpeedMine][net.ccbluex.liquidbounce.features.module
 * .modules.world.speedmine.ModuleSpeedMine] derives its active position from the packets it has in
 * flight, and duplicating that into a claim would be a second copy of a carefully maintained truth.
 * Such a module registers a [probe] instead, and this object treats what the probe reports as a
 * claim held by it. That is what replaces the pairwise arrangement this used to be — the navigation
 * executor reading SpeedMine's field directly, and every new digger meaning one more such pair.
 *
 * Main thread only.
 */
object MiningClaim : EventListener {

    private class Claim(val owner: Any, val pos: BlockPos, var ticksLeft: Int)

    private var claim: Claim? = null

    /** External diggers that keep their own truth; see the class doc. */
    private val probes = LinkedHashMap<Any, () -> BlockPos?>()

    /**
     * Registers a digger that reports its own active position instead of holding a claim.
     *
     * Idempotent, and called from the owner's own initialiser — nothing here reaches back into
     * modules, so the dependency only ever points this way.
     */
    fun registerProbe(owner: Any, probe: () -> BlockPos?) {
        probes[owner] = probe
    }

    /** The claim currently in force, as (owner, position), or null when nobody is digging. */
    private fun current(): Pair<Any, BlockPos>? {
        claim?.takeIf { it.ticksLeft > 0 }?.let { return it.owner to it.pos }

        for ((owner, probe) in probes) {
            val pos = probe() ?: continue
            return owner to pos
        }
        return null
    }

    /** Who is digging, or null. */
    val holder: Any? get() = current()?.first

    /** What is being dug, or null. */
    val position: BlockPos? get() = current()?.second

    /** Is anything being dug at all? */
    val isHeld: Boolean get() = current() != null

    /** Is [owner] the one digging? */
    fun isHeldBy(owner: Any): Boolean = current()?.first === owner

    /**
     * Takes or renews the claim on [pos], or reports that somebody else has it.
     *
     * A caller that gets false must not send any break packet this tick — standing still until the
     * other dig finishes costs a moment, whereas two live digs cost the block and a flag.
     */
    fun claim(owner: Any, pos: BlockPos, ticks: Int = DEFAULT_TICKS): Boolean {
        val held = current()
        if (held != null && held.first !== owner) return false

        claim = Claim(owner, pos, ticks)
        return true
    }

    /** Gives up the claim, if it is ours. Harmless otherwise. */
    fun release(owner: Any) {
        if (claim?.owner === owner) claim = null
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val active = claim ?: return@handler

        active.ticksLeft--
        if (active.ticksLeft <= 0) claim = null
    }

    /**
     * Ticks a claim outlives its last renewal.
     *
     * Vanilla asks whether to abort before the holder is given its tick, so a claim that expired the
     * moment renewals stopped would be gone exactly when it is consulted. Three ticks covers that
     * offset with margin and is short enough that the player's own mouse takes the block straight
     * back once a module stops asking for it.
     */
    const val DEFAULT_TICKS = 3
}
