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
package net.ccbluex.liquidbounce.features.module.modules.combat.autocity

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.world.packetmine.ModulePacketMine
import net.ccbluex.liquidbounce.features.module.modules.world.speedmine.ModuleSpeedMine
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.block.isBreakable
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * AutoCity — the crystal-PvP surround/city miner (mio's `AutoMine` / Combatmine).
 *
 * This is NOT the fork's [net.ccbluex.liquidbounce.features.module.modules.world.automine.ModuleAutoMine],
 * which is a legit ore miner. mio's "AutoMine" is a combat auto-targeter: it never sends a dig packet
 * itself, it decides WHICH block near the enemy is worth breaking — their surround, the obsidian above
 * their head, the block they are burrowed in, the one under their feet — and hands it to a packet miner
 * to open a hole a crystal can go in. Here that packet miner is [ModuleSpeedMine], which already carries
 * every packet-level Grim rule (dig-speed balance, one dig at a time, the swap ordering, and the
 * cross-module arbitration that keeps its digs out of AutoCrystal's action tick-windows). So this module
 * stays small: pick a target by priority, feed it, and let the engine mine it legitimately.
 *
 * ### Why it feeds an engine rather than mining itself
 *
 * A second copy of the dig-packet machinery would be a second thing to keep Grim-correct, and the fork
 * already has a careful one. Nuker and Fucker feed [ModulePacketMine] the same way for the same reason.
 * [ModuleSpeedMine] is the default engine because it is the one that arbitrates through [MiningClaim]
 * — running the two together is the whole point (mine the surround, drop the crystal in the hole).
 *
 * ### The targeters, by priority
 *
 * Highest priority wins, and the numbers are mio's so the behaviour matches: a player trapped in their
 * own hole gets un-trapped before anything else, then a burrow is dug out, then their surround is opened,
 * then the block over their head, then the one under their feet. An ender chest is a last resort.
 */
object ModuleAutoCity : ClientModule("AutoCity", ModuleCategories.COMBAT) {

    /** Which packet miner does the actual digging. */
    private val engine by enumChoice("Engine", Engine.SPEED_MINE)

    // ------------------------------------------------------------------ targeters

    /** The four blocks around the enemy's feet — their surround. Open it so a crystal reaches them. */
    private val surround by boolean("Surround", true)

    /** A solid block the enemy is standing INSIDE — a burrow. Dig them out of it. */
    private val burrow by boolean("Burrow", true)

    /** The block over the enemy's head, two up — for anti-air and to break a ceiling trap. */
    private val head by boolean("Head", false)

    /** The single block directly under the enemy's feet — drop them off it. */
    private val underFeet by boolean("UnderFeet", false)

    /** Dig ourselves out when a block is placed at our own head — the untrap. Highest priority. */
    private val selfUntrap by boolean("SelfUntrap", true)

    /** Farm a nearby ender chest. Lowest priority; off by default. */
    private val enderChest by boolean("EnderChest", false)

    // ------------------------------------------------------------------ policy

    /**
     * Never mine our OWN surround — the blocks that are keeping us alive.
     *
     * On by default, because the surround targeter would otherwise happily take the obsidian around our
     * own feet when the enemy is standing next to us, which opens us up rather than them.
     */
    private val safe by boolean("Safe", true)

    /** Skip unarmoured targets — nothing to open up on somebody with no kit. */
    private val ignoreNaked by boolean("IgnoreNaked", false)

    /**
     * How far a target block may be from the eye.
     *
     * Kept at the block interaction range: the engine refuses anything past its own reach anyway, so a
     * larger number here only proposes targets it will drop. A block past reach is also `FarBreak`.
     */
    private val range by float("Range", 4.5f, 1.0f..6.0f)

    /**
     * Ticks to wait between handing the engine a NEW target.
     *
     * mio's `GrimDelay`. The engine paces its own packets, but changing the target every tick as the
     * enemy jitters makes it restart digs it had made progress on; a tick or two of patience keeps a
     * dig alive long enough to finish.
     */
    private val grimDelay by int("GrimDelay", 1, 0..10, "ticks")

    // ------------------------------------------------------------------ state

    private var tickCounter = 0L

    /**
     * The tick a target was last handed over, for the [grimDelay] pacing.
     *
     * Seeded to `0` and never to a "very old" sentinel on purpose: [tickCounter] is monotonic from
     * zero, so `tickCounter - lastFedTick` stays a small honest delta — a `Long.MIN_VALUE` sentinel
     * would overflow that subtraction to a huge negative and wedge the gate shut forever.
     */
    private var lastFedTick = 0L
    private var lastFedPos: BlockPos? = null
    private val hintClock = Chronometer()

    override fun onDisabled() {
        lastFedPos = null
        lastFedTick = 0L
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        lastFedPos = null
        lastFedTick = 0L
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        tickCounter++

        if (!engine.isReady()) {
            // Said rather than silently doing nothing: without the engine the module cannot act, and
            // that looks identical to it having no target.
            if (hintClock.hasElapsed(HINT_INTERVAL_MS)) {
                hintClock.reset()
                chat(
                    "§b[AutoCity] §fEnable §e${engine.tag}§f in packet mode — AutoCity only picks the " +
                        "targets, the miner does the mining."
                )
            }
            return@handler
        }

        val enemy = selectEnemy() ?: return@handler

        // Stay on the current block while the engine is still working a valid one — retargeting mid-dig
        // throws away the progress. Only look for a new target once the current one is finished or gone.
        val current = lastFedPos
        if (current != null && engine.isActingOn(current) && current.state?.isAir == false) {
            return@handler
        }

        val target = bestTarget(enemy) ?: return@handler

        // Already mining it, or the new-target delay has not elapsed: leave it be.
        if (engine.isActingOn(target)) {
            return@handler
        }
        if (tickCounter - lastFedTick < grimDelay) {
            return@handler
        }

        engine.feed(target, faceToward(target))
        lastFedTick = tickCounter
        lastFedPos = target
    }

    // ------------------------------------------------------------------ targeting

    /**
     * Who to open up.
     *
     * The aura's target first when it is running, so the two act on the same enemy — mine the surround
     * of exactly whoever the crystals are aimed at. Otherwise the nearest attackable player in range.
     */
    private fun selectEnemy(): Player? {
        val fromAura = (ModuleCrystalAura.target as? Player)?.takeIf { ModuleCrystalAura.running }
        val candidate = fromAura ?: nearestEnemy() ?: return null

        if (ignoreNaked && candidate.armorValue <= 0) {
            return null
        }

        return candidate
    }

    private fun nearestEnemy(): Player? {
        // Through the client's own target rules (friends, invisibility, bot tags, the `.target` config)
        // rather than a hand-rolled player scan, so AutoCity agrees with the rest of the client about
        // who is an enemy. A little past reach because the enemy is walking into it.
        return world.findEnemy(0.0f, range + ENEMY_MARGIN) as? Player
    }

    /**
     * The best block to feed the engine, or null when nothing wanted is in reach.
     *
     * Every enabled targeter proposes a candidate; the highest-priority acceptable one wins. Acceptance
     * — breakable, in reach, inside the border, and not our own surround — is applied last so the
     * priority ranking is over things we could actually mine.
     */
    private fun bestTarget(enemy: Player): BlockPos? {
        val feet = BlockPos.containing(enemy.position())
        val candidates = ArrayList<Candidate>(TARGETER_COUNT)

        if (selfUntrap) selfUntrapTarget()?.let(candidates::add)
        if (burrow) burrowTarget(feet, enemy)?.let(candidates::add)
        if (surround) surroundTarget(feet)?.let(candidates::add)
        if (head) headTarget(feet)?.let(candidates::add)
        if (underFeet) underFeetTarget(feet)?.let(candidates::add)
        if (enderChest) enderChestTarget()?.let(candidates::add)

        return candidates
            .filter { acceptable(it.pos) }
            .maxByOrNull { it.priority }
            ?.pos
    }

    /** The nearest of the four blocks around the enemy's feet that is a real, reachable surround block. */
    private fun surroundTarget(feet: BlockPos): Candidate? {
        val eye = player.eyePosition

        // Acceptability is filtered HERE, not after the nearest is chosen — otherwise the closest of the
        // four could be out of reach or one of our OWN surround blocks, and committing to it would leave
        // the other three (perfectly good) blocks unmined. At point-blank that is the common case.
        return HORIZONTAL.asSequence()
            .map { feet.relative(it) }
            .filter { isSurroundBlock(it) && acceptable(it) }
            .minByOrNull { eye.distanceToSqr(Vec3.atCenterOf(it)) }
            ?.let { Candidate(it, PRIORITY_SURROUND) }
    }

    /** The enemy's own feet cell, when a solid block fills it — they are burrowed inside it. */
    private fun burrowTarget(feet: BlockPos, enemy: Player): Candidate? {
        val state = feet.state ?: return null
        if (state.isAir) {
            return null
        }

        val shape = state.getCollisionShape(world, feet)
        if (shape.isEmpty) {
            return null
        }

        // Burrowed = a solid block whose collision actually OVERLAPS the enemy's body, not a partial
        // block (a slab, soul sand, farmland) they are merely standing on top of. Tall grass / water
        // have no collision and are already excluded above.
        val box = shape.bounds().move(feet.x.toDouble(), feet.y.toDouble(), feet.z.toDouble())
        if (!box.intersects(enemy.boundingBox)) {
            return null
        }

        return Candidate(feet, PRIORITY_BURROW)
    }

    /** The block two above the enemy's feet — over their head. */
    private fun headTarget(feet: BlockPos): Candidate? {
        val pos = feet.above(2)
        return if (isSolidBreakable(pos)) Candidate(pos, PRIORITY_HEAD) else null
    }

    /** The block directly under the enemy's feet. */
    private fun underFeetTarget(feet: BlockPos): Candidate? {
        val pos = feet.below()
        return if (isSolidBreakable(pos)) Candidate(pos, PRIORITY_UNDER_FEET) else null
    }

    /** Our own head cell, when something has been placed in it — we are trapped and must dig out. */
    private fun selfUntrapTarget(): Candidate? {
        val head = BlockPos.containing(player.eyePosition)
        return if (isSolidBreakable(head)) Candidate(head, PRIORITY_SELF_UNTRAP) else null
    }

    /**
     * The nearest ender chest in reach.
     *
     * Point-blank only ([ECHEST_RADIUS]), the way an ender-chest farm actually stands on top of them —
     * which also keeps this off-by-default scan a few dozen block reads rather than a cube of thousands.
     */
    private fun enderChestTarget(): Candidate? {
        val eye = player.eyePosition
        val origin = BlockPos.containing(eye)

        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (x in -ECHEST_RADIUS..ECHEST_RADIUS) {
            for (y in -ECHEST_RADIUS..ECHEST_RADIUS) {
                for (z in -ECHEST_RADIUS..ECHEST_RADIUS) {
                    val pos = origin.offset(x, y, z)
                    // Acceptability filtered here, not after the nearest is chosen — so standing on one
                    // ender chest (own-surround, refused) cannot hide a second, valid one further out.
                    if (pos.state?.`is`(Blocks.ENDER_CHEST) != true || !acceptable(pos)) {
                        continue
                    }
                    val distance = eye.distanceToSqr(Vec3.atCenterOf(pos))
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = pos
                    }
                }
            }
        }

        return best?.let { Candidate(it, PRIORITY_ENDER_CHEST) }
    }

    // ------------------------------------------------------------------ helpers

    /** A surround block: blast-resistant (obsidian and friends) and breakable. */
    private fun isSurroundBlock(pos: BlockPos): Boolean {
        val state = pos.state ?: return false
        // Read the resistance off the state we already fetched rather than a second world lookup —
        // the block could be gone by the time a re-read (`isBlastResistant`'s `getBlock()!!`) runs.
        return state.block.explosionResistance >= BLAST_RESISTANT_THRESHOLD && state.isBreakable(pos)
    }

    private fun isSolidBreakable(pos: BlockPos): Boolean {
        val state = pos.state ?: return false
        return !state.isAir && !state.getCollisionShape(world, pos).isEmpty && state.isBreakable(pos)
    }

    /**
     * Whether a target may actually be fed: breakable, in reach, inside the border, and — under [safe]
     * — not one of our own surround blocks.
     */
    private fun acceptable(pos: BlockPos): Boolean {
        val state = pos.state ?: return false
        if (state.isAir || !state.isBreakable(pos)) {
            return false
        }

        if (player.eyePosition.distanceTo(Vec3.atCenterOf(pos)) > range) {
            return false
        }

        if (!world.worldBorder.isWithinBounds(pos)) {
            return false
        }

        return !(safe && isOwnSurround(pos))
    }

    /** Blocks that are keeping US alive: the four around our feet, and the one we stand on. */
    private fun isOwnSurround(pos: BlockPos): Boolean {
        val feet = player.blockPosition()
        if (pos == feet.below()) {
            return true
        }

        return HORIZONTAL.any { feet.relative(it) == pos }
    }

    /**
     * The face to dig from: the one pointing back toward the eye.
     *
     * A face on the far side of the block is one the eye cannot see, which the engine's own raytrace
     * would reject and Grim's `PositionBreak` would flag. The engine re-derives the face for the finish
     * packet, so this only has to be a sane, visible one for the start.
     */
    private fun faceToward(pos: BlockPos): Direction {
        val eye = player.eyePosition
        val center = Vec3.atCenterOf(pos)
        return Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z)
    }

    private class Candidate(val pos: BlockPos, val priority: Int)

    /**
     * Which packet miner AutoCity feeds.
     *
     * Both already exist and both do the packet-level Grim work; AutoCity only chooses targets. Kept an
     * enum rather than a hard dependency on one so the module works whichever the user runs.
     */
    enum class Engine(override val tag: String) : Tagged {
        /** The Grim-hardened miner that also arbitrates with AutoCrystal. The right default here. */
        SPEED_MINE("SpeedMine") {
            // Packet mode specifically: `Damage` mode boosts vanilla progress and sends no dig packets,
            // so there would be nothing to feed and the hint should tell the user to switch it.
            override fun isReady() = ModuleSpeedMine.running && ModuleSpeedMine.isPacketMode
            override fun isActingOn(pos: BlockPos) = ModuleSpeedMine.alreadyActing(pos)
            override fun feed(pos: BlockPos, direction: Direction) = ModuleSpeedMine.startMine(pos, direction)
        },

        /** The general packet miner. Its own state machine decides when to accept the target. */
        PACKET_MINE("PacketMine") {
            override fun isReady() = ModulePacketMine.running

            // PacketMine keeps its target private and `setTarget` already refuses a change while a dig
            // is live, so there is nothing to answer here — it self-arbitrates. Reporting "not acting"
            // lets the caller re-offer the target; `setTarget` no-ops when it is busy on a valid one.
            override fun isActingOn(pos: BlockPos) = false
            override fun feed(pos: BlockPos, direction: Direction) = ModulePacketMine.setTarget(pos)
        };

        abstract fun isReady(): Boolean
        abstract fun isActingOn(pos: BlockPos): Boolean
        abstract fun feed(pos: BlockPos, direction: Direction)
    }

    /** mio's priorities, so the choice of what to dig first matches: self > burrow > surround > … */
    private const val PRIORITY_SELF_UNTRAP = 1500
    private const val PRIORITY_BURROW = 700
    private const val PRIORITY_SURROUND = 600
    private const val PRIORITY_HEAD = 500
    private const val PRIORITY_UNDER_FEET = 400
    private const val PRIORITY_ENDER_CHEST = 5

    private const val TARGETER_COUNT = 6

    /** How far out to look for an ender chest — point-blank, as a farm stands on them. */
    private const val ECHEST_RADIUS = 3

    /** Explosion resistance at/above which a block counts as a real surround (obsidian is 1200). */
    private const val BLAST_RESISTANT_THRESHOLD = 600f

    /** Targets a little past reach still matter — the enemy is walking into it. */
    private const val ENEMY_MARGIN = 6.0f

    private const val HINT_INTERVAL_MS = 3_000L

    private val HORIZONTAL = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
}
