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
package net.ccbluex.liquidbounce.utils.combat.intel

import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.modules.misc.ModulePvpHelper
import net.minecraft.world.entity.player.Player
import java.util.UUID

/**
 * Flags players who are teaming: two who never hit each other yet keep attacking the same victim.
 *
 * Pure logic fed by [PvpIntelTracker] on the client thread — no events of its own, so the whole
 * flagging rule stays in one place and away from the packet plumbing.
 *
 * A pair (A, B) is flagged when, inside the sliding window:
 * 1. they worked over at least `MinSharedVictims` **distinct** victims together, each side landing
 *    at least `MinHitsPerVictim` on every victim that counts — a single chaotic brawl around one
 *    player is exactly what this rule refuses to call teaming, no matter how many hits it produced;
 * 2. their combined weight over those shared victims reaches `MinSharedHits`;
 * 3. neither ever hit the other (any friendly-fire edge clears the pair immediately);
 * 4. those co-hits happened while the two were within `ProximityRadius` of each other.
 *
 * Friends and same-scoreboard-team players are never flagged. Hysteresis holds a flag until
 * `ClearSeconds` pass with no fresh co-hit, and each flag session announces once.
 */
@Suppress("TooManyFunctions")
object TeamDetector {

    private data class Edge(
        val attacker: UUID,
        val victim: UUID,
        val timeMs: Long,
        val weight: Float,
        val victimWasMe: Boolean,
    )

    data class TeamPair(
        val a: UUID,
        val b: UUID,
        val aName: String,
        val bName: String,
        val victimWasMe: Boolean,
        val flaggedAt: Long,
        /** Distinct victims the pair rotated through — the evidence behind the flag. */
        val sharedVictims: Int,
    )

    private val edges = ArrayDeque<Edge>()

    /** Live position/name context for the players in recent edges, refreshed as edges arrive. */
    private val context = HashMap<UUID, Player>()

    /** Pairs currently flagged, keyed by [pairKey]; value is the flag. */
    private val flagged = HashMap<Long, TeamPair>()

    /** Pair keys already announced this flag session (drained by the module). */
    private val newFlags = ArrayList<TeamPair>()

    fun addEdge(attacker: Player, victim: Player, victimWasMe: Boolean, weight: Float) {
        val now = System.currentTimeMillis()
        context[attacker.uuid] = attacker
        context[victim.uuid] = victim
        edges.addLast(Edge(attacker.uuid, victim.uuid, now, weight, victimWasMe))
        prune(now)
        recompute(now)
    }

    /**
     * Partners currently flagged as teaming *with* [uuid], for HUD marking of a specific target.
     */
    fun partnersOf(uuid: UUID): List<TeamPair> = flagged.values.filter { it.a == uuid || it.b == uuid }

    /** Consumes freshly flagged pairs for alerting. */
    fun drainNewFlags(): List<TeamPair> {
        if (newFlags.isEmpty()) return emptyList()
        val out = ArrayList(newFlags)
        newFlags.clear()
        return out
    }

    fun clear() {
        edges.clear()
        context.clear()
        flagged.clear()
        newFlags.clear()
    }

    private fun prune(now: Long) {
        val windowMs = ModulePvpHelper.windowSeconds * 1000L
        while (edges.isNotEmpty() && now - edges.first().timeMs > windowMs) {
            edges.removeFirst()
        }
        // Drop flags whose co-hits have gone stale.
        val clearMs = ModulePvpHelper.clearSeconds * 1000L
        flagged.entries.removeIf { (_, pair) -> now - lastCoHit(pair.a, pair.b) > clearMs }
    }

    private fun lastCoHit(a: UUID, b: UUID): Long {
        var last = 0L
        for (e in edges) {
            if (e.attacker == a || e.attacker == b) {
                last = maxOf(last, e.timeMs)
            }
        }
        return last
    }

    private fun recompute(now: Long) {
        val list = edges.mapTo(LinkedHashSet()) { it.attacker }.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                evaluatePair(list[i], list[j], now)
            }
        }
    }

    /** Applies the flagging rule to one candidate pair, flagging or clearing it. */
    private fun evaluatePair(a: UUID, b: UUID, now: Long) {
        val key = pairKey(a, b)

        // Any friendly fire is decisive: they are not teaming, and an old flag is void.
        if (hasFriendlyFire(a, b)) {
            flagged.remove(key)
            return
        }
        if (isExcluded(a, b) || !withinProximity(a, b) || flagged.containsKey(key)) {
            return
        }

        val shared = sharedVictimStats(a, b)
        if (shared.victims >= ModulePvpHelper.minSharedVictims &&
            shared.weight >= ModulePvpHelper.minSharedHits
        ) {
            val pair = buildPair(a, b, now, shared.victims)
            flagged[key] = pair
            newFlags.add(pair)
        }
    }

    private class SharedStats(val victims: Int, val weight: Float)

    /**
     * How many distinct victims both of them meaningfully worked over, and the weight that took.
     *
     * "Meaningfully" is the whole point: a victim only counts when EACH side landed at least
     * `MinHitsPerVictim` on them. One player tagging someone once while the other fought them is a
     * coincidence, not a rotation — and counting it would flag every crowded fight.
     */
    private fun sharedVictimStats(a: UUID, b: UUID): SharedStats {
        val byA = HashMap<UUID, Float>()
        val byB = HashMap<UUID, Float>()
        for (e in edges) {
            when (e.attacker) {
                a -> byA.merge(e.victim, e.weight, Float::plus)
                b -> byB.merge(e.victim, e.weight, Float::plus)
            }
        }

        val perVictim = ModulePvpHelper.minHitsPerVictim
        var victims = 0
        var total = 0f
        for ((victim, weightA) in byA) {
            if (victim == a || victim == b) continue
            val weightB = byB[victim] ?: continue
            if (weightA >= perVictim && weightB >= perVictim) {
                victims++
                total += weightA + weightB
            }
        }
        return SharedStats(victims, total)
    }

    private fun hasFriendlyFire(a: UUID, b: UUID): Boolean =
        edges.any { (it.attacker == a && it.victim == b) || (it.attacker == b && it.victim == a) }

    private fun isExcluded(a: UUID, b: UUID): Boolean {
        val pa = context[a] ?: return true
        val pb = context[b] ?: return true
        if (FriendManager.isFriend(pa) || FriendManager.isFriend(pb)) return true

        if (ModulePvpHelper.ignoreScoreboardTeams) {
            val ta = pa.team
            if (ta != null && ta == pb.team) return true
        }
        return false
    }

    private fun withinProximity(a: UUID, b: UUID): Boolean {
        val pa = context[a] ?: return false
        val pb = context[b] ?: return false
        return pa.distanceTo(pb) <= ModulePvpHelper.proximityRadius
    }

    private fun buildPair(a: UUID, b: UUID, now: Long, victims: Int): TeamPair {
        val pa = context[a]
        val pb = context[b]
        val victimWasMe = edges.any { (it.attacker == a || it.attacker == b) && it.victimWasMe }
        return TeamPair(
            a, b,
            pa?.gameProfile?.name ?: "?",
            pb?.gameProfile?.name ?: "?",
            victimWasMe, now, victims,
        )
    }

    /** Order-independent key for a UUID pair. */
    private fun pairKey(a: UUID, b: UUID): Long {
        val ha = a.hashCode().toLong() and 0xFFFFFFFFL
        val hb = b.hashCode().toLong() and 0xFFFFFFFFL
        return if (ha <= hb) (ha shl 32) or hb else (hb shl 32) or ha
    }
}
