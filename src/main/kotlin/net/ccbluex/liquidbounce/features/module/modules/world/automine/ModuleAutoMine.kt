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

package net.ccbluex.liquidbounce.features.module.modules.world.automine

import net.ccbluex.liquidbounce.event.events.CancelBlockBreakingEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.world.automine.logistics.PickupTask
import net.ccbluex.liquidbounce.features.module.modules.world.automine.mode.AutoMineMode
import net.ccbluex.liquidbounce.features.module.modules.world.automine.mode.RegionMode
import net.ccbluex.liquidbounce.features.module.modules.world.automine.mode.SweepMode
import net.ccbluex.liquidbounce.features.module.modules.world.automine.target.OreTracker
import net.ccbluex.liquidbounce.features.module.modules.world.automine.target.TargetPicker
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.navigation.pathing.NavService
import net.ccbluex.liquidbounce.utils.collection.Filter
import net.ccbluex.liquidbounce.utils.collection.blockSortedSetOf
import net.ccbluex.liquidbounce.utils.collection.itemSortedSetOf
import net.ccbluex.liquidbounce.utils.inventory.findBlocksEndingWith
import net.ccbluex.liquidbounce.utils.navigation.pathing.world.NavWorld
import net.minecraft.world.level.block.Block
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * Mines on its own: finds something worth breaking, walks to it, and breaks it like a player would.
 *
 * The engine underneath is the client's own navigation service — this is its first module consumer,
 * and the reason [net.ccbluex.liquidbounce.utils.navigation.pathing.goal.GoalAdjacentTo] was written
 * in the first place. What the module itself owns is small on purpose: settings, the choice of mode,
 * and the loop that hands one target at a time to [AutoMineJob].
 *
 * **Legit by construction.** Every rotation goes through
 * [net.ccbluex.liquidbounce.utils.aiming.RotationManager] and every swing waits until the server has
 * that rotation; blocks break through vanilla's own progressive breaking rather than through forged
 * packets; the tool is the real tool, held in the real hand.
 */
object ModuleAutoMine : ClientModule("AutoMine", ModuleCategories.WORLD, disableOnQuit = true) {

    /**
     * Where a block counts as reachable from.
     *
     * Below vanilla's own limit by default: a target chosen at the very edge of reach is one the
     * server may disagree about after a step of latency, and the route only has to walk a fraction
     * of a block further to be comfortable.
     */
    internal val reach by float("Reach", 4.3f, 3f..6f, "blocks")

    /**
     * May the route build to get where it is going?
     *
     * Bridging a gap and pillaring up are ordinary parts of getting around a cave system; turning
     * this off keeps every route to ground that already exists.
     */
    internal val allowPlace by boolean("AllowPlace", true)

    /**
     * Hand the actual breaking to PacketMine when it is on.
     *
     * Off by default, because this module's whole premise is doing it the way a player does.
     * On, targets are passed to PacketMine exactly as Nuker and Fucker pass theirs, and its timings
     * — not ours — decide what goes on the wire.
     */
    internal val usePacketMine by boolean("UsePacketMine", false)

    /**
     * May a block be mined that the eyes cannot actually see?
     *
     * Off, and this is the switch for "do not break through walls". With it off, the aim is only ever
     * allowed to land on a point of the block that is genuinely in view, so anything hidden behind
     * stone is left alone until the stone in front of it is gone. On, the old behaviour returns: the
     * block is aimed at through whatever is in the way, which no player can do — and which the server
     * may well refuse, since the swing is checked against a line that stops at the first solid thing.
     */
    internal val throughWalls by boolean("ThroughWalls", false)

    /** Walk over what the mining dropped. */
    internal val pickup by boolean("Pickup", true)

    /** How far the miner will go out of its way for a dropped item. */
    internal val pickupRange by int("PickupRange", 12, 2..48, "blocks")

    /**
     * Which drops are worth walking to.
     *
     * Blacklisted by default, and the list is the reason: a miner that tunnels produces cobble and tuff
     * by the stack, and chasing every piece of it means five seconds of walking after every block. What
     * is left — the ore drops, and anything else that happens to be lying about — is worth the trip.
     */
    private val pickupIgnored by items("PickupIgnore", defaultJunk())

    private val pickupFilter by enumChoice("PickupFilter", Filter.BLACKLIST)

    /** Is this drop worth walking to? */
    fun wantsDrop(item: Item): Boolean = pickupFilter(item, pickupIgnored)

    /**
     * What breaking one block on the way is worth avoiding, in ticks, beyond the time it takes.
     *
     * Not zero, which is what it was and what produced the silliest thing this module did: a good
     * shovel goes through dirt in less time than a footstep, so on a pure stopwatch the cheapest way
     * down a one-block ledge was to mine the ledge. Digging still wins wherever it is a real
     * shortcut — through a wall rather than around it — and loses where it was only winning on the
     * technicality. Far below the travel profile's thirty, because for a miner tunnelling is normal
     * work rather than a last resort.
     */
    internal val routeDigAversion by int("RouteDigAversion", 12, 0..120, "ticks")

    /**
     * What counts as worth mining.
     *
     * Every ore in the registry by default, deepslate variants included, plus ancient debris — which
     * ends in nothing useful and has to be named. Read on chunk-scanner threads through
     * [targetSnapshot], never directly.
     */
    private val targets by blocks("Targets", defaultTargets()).onChanged { retarget() }

    private val filter by enumChoice("Filter", Filter.WHITELIST).onChanged { retarget() }

    /**
     * The target rule as the indexer sees it.
     *
     * A snapshot, because [OreTracker.getStateFor] runs on several scanner threads at once and a
     * setting the player can edit underneath it is not something to read from there.
     */
    @Volatile
    private var targetSnapshotSet: Set<Block> = emptySet()

    @Volatile
    private var targetSnapshotFilter: Filter = Filter.WHITELIST

    internal val bounds = tree(AutoMineBounds())

    internal val modes = choices<AutoMineMode>("Mode", SweepMode, arrayOf(SweepMode, RegionMode))
        .apply { tagBy(this) }

    /**
     * Write what the miner is deciding into `logs/latest.log`.
     *
     * For diagnosis rather than for play. A miner that is subtly wrong looks, from outside, exactly
     * like a miner that is right but slow — the log is where the difference lives: which block was
     * chosen out of how many, what the route said about it, and what it was waiting for while
     * nothing appeared to happen. Grep the log for `[AutoMine]`.
     */
    private val debug by boolean("Debug", false).onChanged { AutoMineDebug.enabled = it }

    val rotations = tree(RotationsValueGroup(this))

    private val job = AutoMineJob(this, rotations)
    private val pickupTask = PickupTask(this, rotations)

    /** The block being worked on, for the overlay and for anything that wants to draw it. */
    val currentTarget get() = job.target

    /** Is this block one we are looking for? Safe from any thread. */
    fun wants(block: Block): Boolean = targetSnapshotFilter(block, targetSnapshotSet)

    /** Re-snapshots the target rule and rebuilds the index around it. */
    private fun retarget() {
        targetSnapshotSet = targets.toSet()
        targetSnapshotFilter = filter
        OreTracker.restart()
        AutoMineDebug.event("targets changed: ${targetSnapshotSet.size} blocks, $targetSnapshotFilter")
    }

    override fun onEnabled() {
        // Held from the moment the module is on rather than from the first route. The cache fills
        // asynchronously, and a route planned against a half-filled one sees unloaded space in every
        // direction and concludes the player is walled in.
        NavWorld.acquire()
        AutoMineDebug.enabled = debug
        AutoMineDebug.reset()

        // The limit is measured from here, and the index is filtered by it — so the anchor has to be
        // set before the tracker is allowed to see its first chunk.
        bounds.anchorHere()
        targetSnapshotSet = targets.toSet()
        targetSnapshotFilter = filter
        TargetPicker.clear()
        OreTracker.start()
        AutoMineDebug.event(
            "enabled: mode=${modes.activeMode.name} reach=$reach routeMayDig=${modes.activeMode.routeMayDig}" +
                " digAversion=$routeDigAversion allowPlace=$allowPlace packetMine=$usePacketMine" +
                " targets=${targetSnapshotSet.size}/$targetSnapshotFilter bounds=${bounds.describe()}" +
                " throughWalls=$throughWalls pickup=$pickup"
        )
        modes.activeMode.onStarted()
    }

    override fun onDisabled() {
        AutoMineDebug.event("disabled")
        OreTracker.stop()
        bounds.forget()
        pickupTask.stop()
        job.cancel()
        // Only our own route. The engine is shared, and another module's journey is not this
        // switch's to end.
        if (NavService.currentOwner === this) NavService.stop()
        NavWorld.release()
    }

    /**
     * Asks for the aim here rather than in the tick handler.
     *
     * [RotationUpdateEvent] fires immediately before the rotation manager steps, so an angle
     * submitted now rides out on this tick's movement packet instead of the next one's.
     */
    @Suppress("unused")
    private val rotationHandler = handler<RotationUpdateEvent> {
        job.updateAim()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val mode = modes.activeMode
        AutoMineDebug.onTick()

        when (job.tick()) {
            MineOutcome.MINED -> job.lastTarget?.let(mode::onMined)
            MineOutcome.GAVE_UP -> job.lastTarget?.let(mode::onGaveUp)
            MineOutcome.RUNNING -> return@handler
            MineOutcome.IDLE -> Unit
        }

        // Nothing in hand: ask the mode what is worth doing next. A mode with nothing to offer
        // simply leaves the player standing, which is the honest answer to "the box is empty".
        if (!job.isBusy) {
            // Collecting comes first, and only ever between blocks: a drop left behind is the mining
            // wasted, and stepping onto it while nothing is being dug costs the route nothing.
            if (pickupTask.tick()) return@handler

            val next = mode.nextTarget()
            if (next == null) {
                // Not an error, and the single most useful line in the log when the module looks
                // idle: the mode was asked and had nothing to offer.
                AutoMineDebug.state("${mode.name} has no target")
            }
            job.retarget(next)
        }
    }

    /**
     * Every ore in the registry, plus the one that is an ore in all but name.
     *
     * Built from the registry rather than listed by hand so that a modded or a newly added ore is
     * included without anybody remembering to add it.
     */
    /**
     * What tunnelling leaves on the floor.
     *
     * Named by hand rather than derived, because "worthless" is not a property of a block — it is a
     * judgement about this activity. Everything here is something a miner walks past.
     */
    private fun defaultJunk() = itemSortedSetOf(
        Items.COBBLESTONE,
        Items.COBBLED_DEEPSLATE,
        Items.STONE,
        Items.DEEPSLATE,
        Items.TUFF,
        Items.ANDESITE,
        Items.DIORITE,
        Items.GRANITE,
        Items.DIRT,
        Items.GRAVEL,
        Items.SAND,
        Items.NETHERRACK,
        Items.BASALT,
        Items.BLACKSTONE,
        Items.CALCITE,
        Items.SMOOTH_BASALT,
    )

    private fun defaultTargets() = blockSortedSetOf().apply {
        addAll(findBlocksEndingWith("_ORE"))
        add(Blocks.ANCIENT_DEBRIS)
    }

    /**
     * Keeps vanilla from tearing down the dig between our own swings.
     *
     * `handleKeybinds` ends any breaking the attack key is not holding up, once per tick and before
     * our tick runs — so without this only blocks that break in a single swing would ever fall.
     */
    @Suppress("unused")
    private val cancelBreakingHandler = handler<CancelBlockBreakingEvent> { event ->
        if (job.isMining) event.cancelEvent()
    }
}
