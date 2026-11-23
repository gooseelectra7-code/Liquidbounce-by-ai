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

package net.ccbluex.liquidbounce.features.module.modules.world.automine.mode

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import net.ccbluex.liquidbounce.features.module.modules.world.automine.AutoMineDebug
import net.ccbluex.liquidbounce.utils.block.getSortedSphere
import net.ccbluex.liquidbounce.utils.block.isNotBreakable
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.player
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import kotlin.math.max
import kotlin.math.min

/**
 * Clears a box the player marked out with two corners, one level at a time.
 *
 * ## The principle, in one sentence
 *
 * **The miner works the level it is standing on until nothing of that level is left inside the box,
 * then steps down exactly one level and does it again, until the bottom of the box.**
 *
 * That sentence is the whole mode, and it is written down because a rule you can state is a rule you
 * can check. The first version had no such rule: it took the nearest solid block in the box, whatever
 * and wherever it was, and the result was a miner that ate a sphere around itself, took the floor
 * from under its own feet as readily as the wall beside it, and sank through the region leaving holes
 * above. No single block of that is *wrong*, and that is exactly the problem — with no expected
 * behaviour to compare against, "it is misbehaving" cannot be told from "it is working".
 *
 * Three consequences, and they are what make the rule safe:
 *
 * - The **working level** is remembered in [workingY], not read off the player's feet. A body that
 *   falls, is pushed, or steps into its own hole would otherwise rewrite the plan under itself.
 * - A level is worked as a **slab of two cells** — itself and the one above — because that is what a
 *   body occupies. After the first slab the upper half is already gone, so each step down is one
 *   layer of real work.
 * - The floor underfoot is only ever chosen **once the level is finished**. It is the way down, and
 *   the one place in this mode where digging below yourself is the intended thing.
 *
 * Within a level the nearest cell wins. That is a preference, not a policy: it keeps the walking
 * short, and it cannot send the miner anywhere except the level it is already working.
 */
object RegionMode : AutoMineMode("Region") {

    /**
     * Read the corners as an offset from where the module was switched on, rather than as world
     * coordinates.
     *
     * Off, because a region is a place: somebody who types coordinates means those coordinates. On,
     * the two corners describe a box around wherever the work started — and the anchor is taken
     * *once*, at that moment. Re-reading the player's position on every look would make the box
     * follow them around, which is a box that is never finished and never obeyed.
     */
    private val relativeToPlayer by boolean("RelativeToPlayer", false)
    private val corner1 by vec3i("Corner1", Vec3i.ZERO)
    private val corner2 by vec3i("Corner2", Vec3i.ZERO)

    /**
     * How far around itself the cheap first look reaches.
     *
     * Only an optimisation: it answers "something right in front of me" without touching the rest of
     * the level. Whatever it does not answer, the level sweep does, and both return the same thing —
     * the nearest remaining cell of the working level.
     */
    private val searchRadius by int("SearchRadius", 24, 8..64, "blocks")

    /** Where the relative corners are measured from; fixed for as long as the mode runs. */
    private var anchor: BlockPos = BlockPos.ZERO

    /**
     * The level being cleared. Remembered rather than derived — see the class doc.
     *
     * [NO_LEVEL] before the mode has started, and below the box's floor once the region is finished.
     */
    private var workingY = NO_LEVEL

    /**
     * Blocks refused recently, and until when.
     *
     * A target the job could not reach would otherwise be chosen again the very next tick, for ever:
     * it is still the nearest cell of this level. Timed rather than permanent, because the reason is
     * usually temporary — a route blocked by something we are about to mine.
     */
    private val refused = Long2LongOpenHashMap()

    override fun onStarted() {
        refused.clear()
        anchor = player.blockPosition()

        val box = bounds()
        if (box == null) {
            workingY = NO_LEVEL
            chat("§b[AutoMine] §cNo region set — give Corner1 and Corner2 a value.")
            AutoMineDebug.event("started with no region set")
            return
        }

        val (min, max) = box
        // Start on the player's own level, or at the near end of the box when they are outside it.
        workingY = player.blockPosition().y.coerceIn(min.y, max.y)

        chat(
            "§b[AutoMine] §7region §f${min.x} ${min.y} ${min.z}§7 to §f${max.x} ${max.y} ${max.z}" +
                "§7 (${max.x - min.x + 1}×${max.y - min.y + 1}×${max.z - min.z + 1}), levels §f" +
                "$workingY§7 → §f${min.y}"
        )
        AutoMineDebug.event(
            "region ${AutoMineDebug.pos(min)} .. ${AutoMineDebug.pos(max)} relative=$relativeToPlayer" +
                " anchor=${AutoMineDebug.pos(anchor)} startLevel=$workingY searchRadius=$searchRadius"
        )
    }

    override fun onGaveUp(pos: BlockPos) {
        refused.put(pos.asLong(), System.currentTimeMillis() + REFUSAL_MS)
    }

    /**
     * The next cell of the working level, or the step down to the next one.
     *
     * At most one level sweep per call: a level that turns out to be finished steps down and returns
     * nothing, and the following tick works the new level. One idle tick per level costs nothing, and
     * it keeps a region a few hundred blocks tall from being swept in a single frame.
     */
    override fun nextTarget(): BlockPos? {
        val (min, max) = bounds() ?: return null
        if (workingY == NO_LEVEL || workingY < min.y) return null

        val now = System.currentTimeMillis()
        val slab = workingY..min(workingY + 1, max.y)

        nearby(min, max, slab, now)?.let { return picked(it, "near") }
        acrossLevel(min, max, slab, now)?.let { return picked(it, "level sweep") }

        // The level is empty. The floor underneath the body is both the next level's first cell and
        // the way down onto it, so this is the one block below itself the mode will ever take.
        val here = player.blockPosition()
        val floor = BlockPos(here.x, workingY - 1, here.z)
        descend(min)

        if (workingY >= min.y && contains(floor, min, max) && isTarget(floor, now)) {
            return picked(floor, "stepping down")
        }
        return null
    }

    private fun descend(min: BlockPos) {
        workingY--
        if (workingY < min.y) {
            AutoMineDebug.event("region finished at level ${min.y}")
            chat("§b[AutoMine] §aRegion cleared.")
        } else {
            AutoMineDebug.event("level ${workingY + 1} clear, working level $workingY")
        }
    }

    private fun picked(pos: BlockPos, how: String): BlockPos {
        AutoMineDebug.state("level $workingY, picking by $how")
        return pos
    }

    /** Is this position inside the marked box? */
    private fun contains(pos: BlockPos, min: BlockPos, max: BlockPos): Boolean =
        pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z

    /**
     * Nearest cell of the slab within a short radius of the body.
     *
     * Walks a precomputed sphere of offsets in ascending distance and stops at the first hit, so the
     * usual answer — a block against the miner's face — costs a handful of reads rather than a sweep
     * of the level. The offset table only goes out to ten blocks, hence the clamp; anything further
     * away is the sweep's business.
     */
    private fun nearby(min: BlockPos, max: BlockPos, levels: IntRange, now: Long): BlockPos? {
        val radius = searchRadius.coerceAtMost(NEARBY_RADIUS_CAP).toFloat()
        return player.blockPosition().getSortedSphere(radius).firstOrNull { pos ->
            pos.y in levels && contains(pos, min, max) && isTarget(pos, now)
        }
    }

    /** Nearest cell of the slab anywhere in the box: two flat sweeps, never a volume. */
    private fun acrossLevel(min: BlockPos, max: BlockPos, levels: IntRange, now: Long): BlockPos? {
        val from = BlockPos(min.x, max(min.y, levels.first), min.z)
        val to = BlockPos(max.x, min(max.y, levels.last), max.z)
        if (from.y > to.y) return null

        return scan(from, to, now)
    }

    /** Nearest breakable cell in the box between the two corners, or null when there is none. */
    private fun scan(min: BlockPos, max: BlockPos, now: Long): BlockPos? {
        val eyes = player.eyePosition

        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE

        // Distance before the block itself, deliberately: the comparison is arithmetic on a cursor we
        // already hold, while the state is a chunk lookup — and most of a level is further away than
        // the best candidate found in its first few rows.
        for (pos in BlockPos.betweenClosed(min, max)) {
            val distance = pos.distToCenterSqr(eyes)
            if (distance < bestDistance && isTarget(pos, now)) {
                bestDistance = distance
                best = pos.immutable()
            }
        }

        return best
    }

    /** Is this a block we are willing to break right now? */
    private fun isTarget(pos: BlockPos, now: Long): Boolean {
        val state = pos.state ?: return false
        if (state.isAir || state.isNotBreakable(pos)) return false

        val until = refused.get(pos.asLong())
        return until == 0L || until <= now
    }

    /** The box, ordered so that min is genuinely the minimum corner, or null when it has no volume. */
    private fun bounds(): Pair<BlockPos, BlockPos>? {
        // Emptiness is judged on what the user actually typed, before any anchoring. Judging it after
        // the offset meant the check only ever fired for somebody standing exactly at the world
        // origin, so an unset region silently became a single block under the player's feet.
        if (corner1 == Vec3i.ZERO && corner2 == Vec3i.ZERO) return null

        val a = if (relativeToPlayer) corner1.offset(anchor) else corner1
        val b = if (relativeToPlayer) corner2.offset(anchor) else corner2

        val min = BlockPos(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z))
        val max = BlockPos(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))
        return min to max
    }

    /** Ten seconds. Long enough to have moved on, short enough to come back once the way opens. */
    private const val REFUSAL_MS = 10_000L

    /**
     * How far the cheap sphere walk reaches.
     *
     * `CachedBlockPosSpheres` only tabulates offsets out to ten blocks, which is plenty for a "what
     * is right in front of me" question.
     */
    private const val NEARBY_RADIUS_CAP = 10

    /** No level is being worked: the mode has not started, or the region has no volume. */
    private const val NO_LEVEL = Int.MIN_VALUE
}
