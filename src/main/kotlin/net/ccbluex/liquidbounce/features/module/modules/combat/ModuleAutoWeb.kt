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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.placement.CatleanPlaceModule
import net.ccbluex.liquidbounce.features.module.modules.combat.placement.IgnoreMode
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.combat.TargetSelector
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.AABB

/**
 * AutoWeb module
 *
 * Port of catlean's `AutoWeb` (`su.catlean.AutoWeb`). Drops cobweb into the cells a target occupies,
 * so they are held in place instead of being able to disengage.
 *
 * The original is obfuscated past literal translation — control flow flattened, strings encrypted,
 * a junk key threaded through every call — so what is ported here is its behaviour, read out of the
 * parts that survive: the block list is `Blocks.COBWEB`, the target is either the aura's (when that
 * is a player) or its own FOV search, and the positions are the block the target stands in and the
 * one above it, filtered by two rules.
 *
 * Both filters are theirs and both matter:
 * - **Never web a liquid.** Cobweb cannot replace flowing or source liquid, so those positions would
 *   only stall the placer on something it can never satisfy.
 * - **Never web the cell you are standing or looking out of.** The feet position is skipped when it
 *   equals your own feet block, and the head position when it equals the block containing your eyes.
 *   Cobweb does not care whose it is; without this the module traps you along with the target, which
 *   in a fight is worse than doing nothing.
 *
 * The placement mechanics are the fork's [BlockPlacer] — the same one BedDefender, AutoBuild and
 * CrystalAura use — rather than a second port of catlean's placer base class. It already carries
 * range and wall-range, the rotation modes, swing, cooldown, slot handling and the sneak/interact
 * handling, and it is wired into the rotation machinery this fork validates placements against.
 *
 * ## Sharing the aim with KillAura
 *
 * The web goes into the cell the target is standing in, so it is wanted at exactly the moment the
 * aura is swinging at that target — and both need the aim. Left at the placer's default priority
 * this is a tie with KillAura, settled by request order, so on most ticks the aura won, the
 * placement's `serverRotation.angleTo(placement.rotation)` gate never opened and the web simply
 * never went down.
 *
 * So the placement rotation is requested one step ABOVE the aura ([placeRotationPriority]). The
 * handover needs nothing else, in either direction:
 *
 * - *to us*: [CatleanPlaceModule.placeOne] only asks for a rotation on a tick it has a real
 *   placement, so the aura is outranked for exactly the ticks the web needs and not one more;
 * - *back*: the request expires after a single tick, and KillAura never stopped asking at its own
 *   priority — the tick our last request lapses, its plan is already the top of the queue. Telling
 *   it to stand down instead would leave the tick in between with no request at all, which is the
 *   one case where the aim visibly walks home mid-fight.
 *
 * What the aura is NOT allowed to do is attack on the tick the web goes down: a block interaction
 * and an entity interaction inside one tick window are Grim `MultiActionsF` / `PacketOrderJ`. That
 * is what [placedThisTick] is for — KillAura's `canAttackNow` reads it, and since every
 * [RotationUpdateEvent] handler runs inside `RotationManager`'s FIRST_PRIORITY tick handler, ahead
 * of the aura's own `GameTickEvent` handler, the flag is always set before it is read.
 */
object ModuleAutoWeb : CatleanPlaceModule("AutoWeb", ModuleCategories.COMBAT) {

    /**
     * Where the target comes from. Catlean offers the same pair: ride on the aura's current target,
     * or run an independent search.
     */
    private val targetMode by enumChoice("TargetMode", TargetMode.AURA)

    /** Which of the target's two cells to fill. */
    private val face by enumChoice("Face", Face.BOTH)

    /**
     * Range for the target search — and it has to be given explicitly.
     *
     * [TargetSelector] treats a missing range as "no range check at all": `validateRange` returns
     * true unconditionally when none was registered, so a selector built without one accepts every
     * entity in render distance. Constructed that way the module happily locked onto a player fifty
     * blocks off and queued their cells, leaving the placer grinding on positions it could never
     * reach.
     */
    private val targetRange = float("TargetRange", 4.5f, 1f..8f)

    private val targetSelector = tree(TargetSelector(range = targetRange))

    override val blocks: List<Block> = listOf(Blocks.COBWEB)

    /** One step above KillAura and AutoCrystal — see the note on sharing the aim in the class KDoc. */
    override val placeRotationPriority: Priority
        get() = Priority.IMPORTANT_FOR_USAGE_3

    private var placedThisTickFlag = false

    /**
     * Whether a web went down THIS tick, for KillAura to keep its attack out of that tick window
     * (Grim `MultiActionsF` / `PacketOrderJ`). Read after being written — see the class KDoc.
     */
    val placedThisTick: Boolean
        get() = running && placedThisTickFlag

    @Suppress("unused")
    private val targetUpdater = handler<RotationUpdateEvent> {
        placedThisTickFlag = false

        // Cobweb has no collision shape, so nothing standing in the cell can stop it going in —
        // which is the entire reason this module wants catlean's placer. Our own vetoes any
        // occupied position unconditionally, and the position we want is by definition occupied:
        // it is where the target is standing.
        val positions = findTarget()?.let { webPositions(it).toList() } ?: emptyList()
        placedThisTickFlag = place(positions, ignore = IgnoreMode.NONE)
    }

    override fun onDisabled() {
        placedThisTickFlag = false
        super.onDisabled()
    }

    /**
     * Aura's target only counts when it is a player — catlean checks the same, and for the same
     * reason: webbing a mob the aura happens to be swinging at wastes the stack.
     *
     * Aura mode falls back to its own search when the aura has nothing, which in practice means
     * KillAura is off. Keyed strictly to the aura the module would then be dead weight for anyone
     * not running one, and there is no reason to make webbing conditional on a separate module being
     * enabled: the fallback picks the same kind of target through [targetSelector], with its own
     * range and FOV.
     */
    private fun findTarget(): LivingEntity? = when (targetMode) {
        TargetMode.AURA ->
            ModuleKillAura.targetTracker.target?.takeIf { it is Player }
                ?: targetSelector.targets().firstOrNull()

        TargetMode.SEARCH -> targetSelector.targets().firstOrNull()
    }

    private fun webPositions(target: LivingEntity): Set<BlockPos> {
        val feet = BlockPos.containing(target.position())
        val positions = LinkedHashSet<BlockPos>(2)

        if (face != Face.HEAD && isWebbable(feet) && feet != BlockPos.containing(player.position())) {
            positions.add(feet)
        }

        val head = feet.above()
        if (face != Face.FEET && isWebbable(head) && head != BlockPos.containing(player.eyePosition)) {
            positions.add(head)
        }

        // Never queue what cannot be reached. The placer would discard these itself, but only after
        // a face search and a reach test per position per tick — and the target does not have to be
        // ours to begin with: in Aura mode it comes from KillAura, whose range is its own setting
        // and can sit well outside placing distance.
        positions.removeIf { !inPlacingRange(it) }
        return positions
    }

    private fun inPlacingRange(pos: BlockPos): Boolean {
        val reach = maxOf(range, wallRange).toDouble()
        return AABB(pos).distanceToSqr(player.eyePosition) <= reach * reach
    }

    /** Cobweb cannot replace a liquid, so such a position would only stall the placer. */
    private fun isWebbable(pos: BlockPos): Boolean = world.getBlockState(pos).block !is LiquidBlock

    private enum class TargetMode(override val tag: String) : Tagged {
        /** Follow whatever KillAura is hitting. */
        AURA("Aura"),

        /** Independent search, with its own range and FOV. */
        SEARCH("Search")
    }

    private enum class Face(override val tag: String) : Tagged {
        FEET("Feet"),
        HEAD("Head"),
        BOTH("Both")
    }

}
