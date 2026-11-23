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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos

/**
 * A placement we sent whose crystal is expected to appear at [above] (the block above the clicked obsidian),
 * sent on client tick [tick]. [predictedId] is [CrystalLedger.predictNextId] at send time (null when no prediction
 * was possible).
 */
data class PendingPlacement(val above: BlockPos, val tick: Int, val predictedId: Int?)

/**
 * Pure bookkeeping of what we did (attacks, placements, chain removals we expect) and what the server confirmed
 * (spawns, removals, damage events). No Minecraft world access, value types only, main thread only (not
 * thread-safe; incoming packets are queued on the netty thread and drained into this ledger on the main thread).
 *
 * Vanilla facts this mirrors:
 * - A crystal placed on a block spawns at exactly `(above.x + 0.5, above.y, above.z + 0.5)` — `EndCrystalItem.useOn`
 *   (`net/minecraft/world/item/EndCrystalItem.java:42`). The position travels as plain doubles, so [onSpawn]
 *   confirms a pending placement by exact double comparison.
 * - Entity ids come from one global `ENTITY_COUNTER.incrementAndGet()`
 *   (`net/minecraft/world/entity/Entity.java:196,217`): a fresh entity's id is larger than every id seen so far and
 *   ids are never reused in one server session.
 *   [predictNextId] therefore answers `maxSeenEntityId + 1`, but only while the last three observed spawns had
 *   consecutive ids (evidence that no unseen entity is consuming ids in between).
 * - `LivingEntity.hurtServer` (`net/minecraft/world/entity/LivingEntity.java:1197-1207`): inside the i-frame window
 *   (`invulnerableTime > 10`) a hit deals only `damage - lastHurt` and only when `damage > lastHurt`; otherwise
 *   `lastHurt = damage; invulnerableTime = 20`. The client learns only THAT a hit happened
 *   (`ClientboundDamageEventPacket`, `net/minecraft/network/protocol/game/ClientboundDamageEventPacket.java:16`,
 *   absent cause/direct ids decode to -1, `:46-48`) and sets `invulnerableTime = 20` in `handleDamageEvent`
 *   (`LivingEntity.java:2017-2019`); the amount is never sent, hence [lastHurtEstimate] is our own prediction that
 *   [onDamageEvent] either confirms or discards.
 * - A hit crystal is removed BEFORE it explodes: `EndCrystal.hurtServer` calls `remove(KILLED)`
 *   (`net/minecraft/world/entity/boss/enderdragon/EndCrystal.java:95`), which synchronously sends the removal packet
 *   (`ServerLevel.java:2050-2051` → `ChunkMap.java:1363-1365` → `ServerEntity.java:250-252`), and only then
 *   `level.explode` (`EndCrystal.java:98`) hurts the victims (`LivingEntity.java:1220`). The removal therefore reaches
 *   this ledger before the damage events that name the crystal as direct entity; [onRemoved] keeps such ids in a short
 *   private grace memory that only [onDamageEvent] consults (safe because ids are never reused).
 *
 * Expiry (client ticks, evaluated in [onTickStart]): an entry stamped at tick `t` with timeout `n` survives through
 * tick `t + n` and is dropped at tick `t + n + 1` ("older than n ticks"), so a timeout of 0 expires on the next
 * tick. Entries stamped after the current tick (tick counter went backwards without [reset]) are dropped as stale.
 * lastHurt estimates use a fixed window of 20 ticks after their last update, vanilla's `invulnerableTime = 20`.
 * Own marks are stamped with the spawn tick and expire after [OWN_TICKS]; without that they would only ever shrink
 * when a removal is observed, so a crystal whose removal packet never arrives (chunk unload, dimension change,
 * a dropped `WorldEntityRemoveEvent`) would leak for the whole session.
 *
 * Deliberate choices: a damage event that is not "us through one of our attacked / chain-removed crystals" marks the
 * victim's lastHurt as unknown (entry removed) because the amount of a foreign hit is unknowable client-side and
 * both a too-high estimate (suppresses valid placements) and a too-low one (overestimates damage) are wrong;
 * the damage pipeline treats unknown as 0. A spawn that reuses an id still marked own (its removal was never
 * observed) clears that stale own mark; attacked / expectedRemoved marks survive a spawn so IdPredict attacks sent
 * before the spawn packet keep their state. Call [reset] on world change (entity ids restart there).
 */
@Suppress("TooManyFunctions")
class CrystalLedger {

    /** Current client tick as given to [onTickStart]; 0 before the first call and after [reset]. */
    var tick: Int = 0
        private set

    /** Highest entity id seen in any spawn, -1 when nothing has been seen. */
    var maxSeenEntityId: Int = -1
        private set

    /** Crystal id → tick the attack was sent. */
    private val attacked = Int2IntOpenHashMap()

    /** Crystal id → tick it was marked as removed by the chain reaction of an attacked crystal. */
    private val expectedRemoved = Int2IntOpenHashMap()

    /** Ids that were attacked / expected removed when their removal arrived → removal tick (see class KDoc). */
    private val removedOurs = Int2IntOpenHashMap()

    /** Crystal id → tick its spawn confirmed it as ours (see [OWN_TICKS]). */
    private val own = Int2IntOpenHashMap()

    /** Unconfirmed placements keyed by `above.asLong()`. */
    private val pending = Long2ObjectOpenHashMap<PendingPlacement>()

    /** Victim entity id → lastHurt estimate and the tick it was last updated. */
    private val lastHurt = Int2ObjectOpenHashMap<LastHurt>()

    /** The last three spawned ids, oldest first, and how many spawns have been seen (capped at [SPAWN_WINDOW]). */
    private var spawnId0 = 0
    private var spawnId1 = 0
    private var spawnId2 = 0
    private var spawnsSeen = 0

    /**
     * Called once per tick before anything else. Expires attacked / expectedRemoved marks older than
     * [attackTimeoutTicks], pending placements older than [placeTimeoutTicks], lastHurt estimates older than
     * [LAST_HURT_TICKS] and own marks older than [OWN_TICKS] (exact boundary in the class KDoc).
     */
    fun onTickStart(tick: Int, attackTimeoutTicks: Int, placeTimeoutTicks: Int) {
        this.tick = tick
        expireMarks(attacked, attackTimeoutTicks)
        expireMarks(expectedRemoved, attackTimeoutTicks)
        expireMarks(removedOurs, REMOVED_GRACE_TICKS)
        expireMarks(own, OWN_TICKS)
        expirePending(placeTimeoutTicks)
        expireLastHurt()
    }

    /**
     * Every `ClientboundAddEntityPacket` (any entity type). Tracks [maxSeenEntityId] and the consecutive-id window of
     * [predictNextId]. A crystal spawn confirms a pending placement when it appears at exactly
     * `(above.x + 0.5, above.y, above.z + 0.5)` (`EndCrystalItem.java:42`) or carries the placement's predicted id:
     * [id] becomes own and the placement is no longer pending. Attacked / expectedRemoved marks for [id] are kept.
     */
    fun onSpawn(id: Int, isCrystal: Boolean, x: Double, y: Double, z: Double) {
        maxSeenEntityId = maxOf(maxSeenEntityId, id)
        pushSpawnId(id)
        val matched = if (isCrystal) matchPending(id, x, y, z) else null
        if (matched == null) {
            own.remove(id)
            return
        }
        pending.remove(matched.above.asLong())
        own.put(id, tick)
    }

    /**
     * Entity removed client-side (packet or `WorldEntityRemoveEvent`). Clears the attacked / expectedRemoved / own
     * marks for [id] and drops [id] as the predicted id of pending placements (the position match still applies).
     */
    fun onRemoved(id: Int) {
        val wasOurs = attacked.containsKey(id) || expectedRemoved.containsKey(id)
        attacked.remove(id)
        expectedRemoved.remove(id)
        own.remove(id)
        if (wasOurs) {
            removedOurs.put(id, tick)
        }
        forgetPrediction(id)
    }

    /**
     * `ClientboundDamageEventPacket` for [entityId] (`sourceCauseId` = [causeId], `sourceDirectId` = [directId],
     * -1 when absent). When the cause is us ([causeId] == [selfId]) and the direct entity is a crystal we attacked,
     * expect to be chain-removed, or that was just removed while marked so, the hit is the one we predicted: the
     * stored estimate is kept and its tick refreshed. Any other hit (foreign explosion, melee, fall, ...) has an
     * unknown amount, so the victim's estimate is dropped (unknown, treated as 0 by the damage pipeline) — see the
     * class KDoc.
     */
    fun onDamageEvent(entityId: Int, causeId: Int, directId: Int, selfId: Int) {
        if (causeId != selfId || !isOurCrystalHit(directId)) {
            lastHurt.remove(entityId)
            return
        }
        lastHurt.get(entityId)?.tick = tick
    }

    /**
     * We sent an attack for [id]. [predictedLastHurt]: victim entity id → the value vanilla will store in `lastHurt`
     * (post-shield damage, `LivingEntity.java:1203,1206`); stored as the estimate when > 0, stamped with [tick].
     */
    fun markAttacked(id: Int, predictedLastHurt: Map<Int, Float>) {
        attacked.put(id, tick)
        for ((victimId, damage) in predictedLastHurt) {
            if (damage > 0f) {
                storeLastHurt(victimId, damage)
            }
        }
    }

    /** Crystal that the chain reaction of an attacked one will remove — treated like attacked for rays / columns. */
    fun markExpectedRemoved(id: Int) {
        expectedRemoved.put(id, tick)
    }

    /** We sent a placement whose crystal will stand at [above]; replaces an older pending placement for [above]. */
    fun markPlaced(above: BlockPos, predictedId: Int?) {
        pending.put(above.asLong(), PendingPlacement(above, tick, predictedId))
    }

    fun isAttacked(id: Int): Boolean = attacked.containsKey(id)

    fun isExpectedRemoved(id: Int): Boolean = expectedRemoved.containsKey(id)

    fun isPending(above: BlockPos): Boolean = pending.containsKey(above.asLong())

    /** Whether [id] is a crystal we placed and whose spawn is at most [OWN_TICKS] ticks old (see the class KDoc). */
    fun isOwn(id: Int): Boolean = own.containsKey(id)

    /** Our lastHurt estimate for [entityId], null when unknown (never predicted, discarded or expired). */
    fun lastHurtEstimate(entityId: Int): Float? = lastHurt.get(entityId)?.damage

    /** `maxSeenEntityId + 1` when the last three observed spawns had consecutive ids, else null. */
    fun predictNextId(): Int? {
        if (spawnsSeen < SPAWN_WINDOW) {
            return null
        }
        val consecutive = spawnId1 == spawnId0 + 1 && spawnId2 == spawnId1 + 1
        return if (consecutive) maxSeenEntityId + 1 else null
    }

    /** Newest unconfirmed placement (highest send tick) or null. */
    fun pendingPlacement(): PendingPlacement? {
        var newest: PendingPlacement? = null
        for (placement in pending.values) {
            if (newest == null || placement.tick > newest.tick) {
                newest = placement
            }
        }
        return newest
    }

    /** Forgets everything, including [tick] (0), [maxSeenEntityId] (-1) and the spawn window of [predictNextId]. */
    fun reset() {
        tick = 0
        maxSeenEntityId = -1
        attacked.clear()
        expectedRemoved.clear()
        removedOurs.clear()
        own.clear()
        pending.clear()
        lastHurt.clear()
        spawnId0 = 0
        spawnId1 = 0
        spawnId2 = 0
        spawnsSeen = 0
    }

    private fun isOurCrystalHit(directId: Int): Boolean =
        attacked.containsKey(directId) || expectedRemoved.containsKey(directId) || removedOurs.containsKey(directId)

    private fun storeLastHurt(victimId: Int, damage: Float) {
        val entry = lastHurt.get(victimId)
        if (entry == null) {
            lastHurt.put(victimId, LastHurt(damage, tick))
        } else {
            entry.damage = damage
            entry.tick = tick
        }
    }

    private fun pushSpawnId(id: Int) {
        spawnId0 = spawnId1
        spawnId1 = spawnId2
        spawnId2 = id
        if (spawnsSeen < SPAWN_WINDOW) {
            spawnsSeen++
        }
    }

    /** Exact position match wins over the predicted-id match. */
    private fun matchPending(id: Int, x: Double, y: Double, z: Double): PendingPlacement? {
        var byId: PendingPlacement? = null
        for (placement in pending.values) {
            val above = placement.above
            if (x == above.x + 0.5 && y == above.y.toDouble() && z == above.z + 0.5) {
                return placement
            }
            if (byId == null && placement.predictedId == id) {
                byId = placement
            }
        }
        return byId
    }

    private fun forgetPrediction(id: Int) {
        val iterator = pending.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val placement = entry.value
            if (placement.predictedId == id) {
                entry.setValue(placement.copy(predictedId = null))
            }
        }
    }

    private fun isExpired(stampedTick: Int, timeoutTicks: Int): Boolean = (tick - stampedTick) !in 0..timeoutTicks

    private fun expireMarks(marks: Int2IntOpenHashMap, timeoutTicks: Int) {
        val iterator = marks.int2IntEntrySet().fastIterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().intValue, timeoutTicks)) {
                iterator.remove()
            }
        }
    }

    private fun expirePending(timeoutTicks: Int) {
        val iterator = pending.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value.tick, timeoutTicks)) {
                iterator.remove()
            }
        }
    }

    private fun expireLastHurt() {
        val iterator = lastHurt.int2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value.tick, LAST_HURT_TICKS)) {
                iterator.remove()
            }
        }
    }

    /** Mutable so a confirmed hit refreshes [tick] and a new prediction overwrites [damage] without reallocation. */
    private class LastHurt(var damage: Float, var tick: Int)

    private companion object {
        /** Vanilla's `invulnerableTime = 20` (`LivingEntity.java:1207`, `:2019`). */
        const val LAST_HURT_TICKS = 20

        /** How long a removed attacked / expected id still attributes damage events to us (see class KDoc). */
        const val REMOVED_GRACE_TICKS = 20

        /**
         * How long a confirmed own crystal stays own without an observed removal: 600 ticks, 30 s at 20 TPS. The
         * only consumer is the `OnlyOwn` skip of `BreakSearch`, and a crystal that survived half a minute is not a
         * placement of ours worth protecting any more; without the bound the map would only ever grow.
         */
        const val OWN_TICKS = 600

        /** Number of consecutive spawn ids required before [predictNextId] answers. */
        const val SPAWN_WINDOW = 3
    }
}
