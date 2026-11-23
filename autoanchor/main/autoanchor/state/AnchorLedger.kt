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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.state

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * A detonating click we sent for the anchor at [pos].
 *
 * @param detonatedTick the client tick the click went out on; every expiry in [AnchorLedger] is measured from it.
 * @param detonatedAt the wall clock of the same moment. Ticks are not reliably 50 ms apart - after a stall several
 *   run inside one frame - so anything that reasons about REAL time (the debug trace, a ping comparison) must read
 *   this and not the tick.
 * @param centre `pos.getCenter()` through [AnchorMath.anchorCenter]: bit for bit the `boomPos` vanilla builds at
 *   `net/minecraft/world/level/block/RespawnAnchorBlock.java:168` and passes BOTH as the explosion centre and into
 *   `damageSources().badRespawnPointExplosion(boomPos)` (`:169`). Using the same function the damage model uses is
 *   what makes the exact double comparisons below legitimate.
 */
data class PendingDetonation(
    val pos: BlockPos,
    val detonatedTick: Int,
    val detonatedAt: Long,
    val centre: Vec3,
)

/**
 * One explosion whose block changes may still be travelling, as [AnchorLedger.isBlastShadowed] reads it.
 *
 * @param centre the blast centre, exactly as the server named it.
 * @param radiusSq squared reach including [AnchorLedger.BLAST_SLACK], so the test needs no square root.
 * @param expireTick the client tick at which the shadow lifts.
 */
private class Blast(val centre: Vec3, val radiusSq: Double, val expireTick: Int)

/**
 * Bookkeeping for the one thing the client cannot see for itself: whether the anchor we clicked actually blew up.
 *
 * Pure state, main thread only (not thread-safe; incoming packets are queued on the netty thread and drained into
 * this ledger on the main thread). No world access - the shell reads the world and reports what it saw.
 *
 * ### Why a pending-detonation record is mandatory
 * The charge is predicted client-side and the placement is too, so both can simply be read back off the
 * blockstate. The DETONATION is predicted by nothing: the client branch of `RespawnAnchorBlock.useWithoutItem` is
 * `return InteractionResult.CONSUME` (`net/minecraft/world/level/block/RespawnAnchorBlock.java:120-122`) and
 * `ClientLevel.explode` has an empty body (`net/minecraft/client/multiplayer/ClientLevel.java:1045-1060`). The
 * anchor therefore stays standing on screen after the click, and without [markDetonated] the search would propose
 * the very same anchor again on the next tick and the module would re-detonate a block that is already gone.
 * While an entry is pending the cell is invisible to the search and to the sequencer alike: no second detonate,
 * no re-charge, no placement into it.
 *
 * ### Clearing, in order of preference
 * 1. [onExplosion] - a `ClientboundExplodePacket` whose `center` equals ours by EXACT double comparison. The
 *    packet is a record whose FIRST component is `Vec3 center`
 *    (`net/minecraft/network/protocol/game/ClientboundExplodePacket.java:17-25`) and `ServerLevel.explode` sends
 *    it verbatim to every player within 64 blocks (`distanceToSqr(center) < 4096.0`,
 *    `net/minecraft/server/level/ServerLevel.java:1220-1229`). That is a POSITIVE confirmation the crystal aura
 *    never had - it had to time its removals out.
 * 2. [onAnchorGone] - the cell stopped reading as a `RespawnAnchorBlock`. Slightly weaker (someone could have
 *    mined it) but conclusive enough: either way there is nothing left to detonate.
 * 3. Timeout in [onTickStart] - neither arrived within `CycleSettings.detonateTimeout` ticks. That is a **DUD**.
 *
 * ### The dud latch - the empirical backstop for the dimension question
 * `RespawnAnchorBlock.canSetSpawn` reads `RESPAWN_ANCHOR_WORKS` POSITIONALLY on a `ServerLevel` (`:172-174`), and
 * the client can only consult the dimension-level value. The primary gate stays `Level.respawnAnchorWorks` plus
 * the `AnyDimension` override (owned by the module hub); this is belt and braces for a server that lies or that
 * varies the attribute per position. A dud cell is excluded for `CycleSettings.dudTicks`, and after
 * `CycleSettings.dudLimit` CONSECUTIVE duds [latched] goes true and [consumeLatchNotice] lets the hub say so
 * exactly once. Any confirmed detonation resets the streak; a LATE explosion that matches a cell already written
 * off retracts the dud and the latch with it ([onExplosion] second leg), because that is positive proof anchors do
 * explode here.
 *
 * The latch is scoped to the current `ClientLevel` by the hub calling [reset] on a world change - the same
 * discipline `CrystalLedger` documents for entity ids. Keeping the level out of this class is what keeps it pure.
 *
 * ### lastHurt estimates, and the one rule that had to change
 * `LivingEntity.hurtServer` (`net/minecraft/world/entity/LivingEntity.java:1197-1207`): inside the i-frame window
 * (`invulnerableTime > 10`) a hit deals only `damage - lastHurt` and only when `damage > lastHurt`. The client is
 * told THAT a hit happened but never the amount (`ClientboundDamageEventPacket`), so the estimate stored by
 * [markDetonated] is our own prediction that [onDamageEvent] either confirms or discards.
 *
 * `CrystalLedger.onDamageEvent` attributes by ENTITY ID, which is useless here: `badRespawnPointExplosion` is
 * `new DamageSource(type, boomPos)` with no causing and no direct entity
 * (`net/minecraft/world/damagesource/DamageSources.java:278-280`), so `sourceCauseId` and `sourceDirectId` both
 * encode as -1 (`ClientboundDamageEventPacket.java:26-27, 46-48`). The replacement rule is damage type
 * `bad_respawn_point` AND an exact match of the packet's `sourcePosition`
 * (`Optional<Vec3>`, `ClientboundDamageEventPacket.java:16, 28, 38`) against a detonation centre of ours.
 *
 * Known limit, stated honestly: a FOREIGN anchor detonating at the same cell inside the window is
 * indistinguishable from ours. The consequence is bounded - we keep an estimate that the other explosion's damage
 * may exceed - and the alternative (dropping every estimate) is worse, because the damage pipeline treats an
 * unknown lastHurt as 0 and would then overestimate our own next hit.
 *
 * ### Why cleared entries are remembered for a moment
 * `RespawnAnchorBlock.explode` calls `level.removeBlock(pos, false)` at `:151` BEFORE `level.explode(...)` at
 * `:169`, and `ServerLevel.explode` sends the explode packet only after `explosion.explode()` has already hurt
 * everything (`ServerLevel.java:1222-1228`). So both clearing routes can land BEFORE the damage events they
 * belong to. Cleared entries therefore move into a short private grace memory that only [onDamageEvent] and the
 * late-explosion leg of [onExplosion] consult - the same shape as `CrystalLedger.removedOurs`.
 *
 * Expiry (client ticks, evaluated in [onTickStart]): an entry stamped at tick `t` with timeout `n` survives
 * through tick `t + n` and is dropped at tick `t + n + 1`, so a timeout of 0 expires on the next tick. Entries
 * stamped after the current tick (the counter went backwards without [reset]) are dropped as stale.
 */
// Detekt's 11-function ceiling is meant to catch a class that has grown two jobs. This one has a single job -
// it is the module's whole memory of what the server has and has not confirmed - and the count is simply the
// number of facts worth remembering: pending detonations, the grace memory, duds, lastHurt estimates and the
// blast shadow, each with an arm, a query and an expiry. Splitting them would put one tick's bookkeeping in
// several places that all have to be advanced in the right order, which is the bug this class exists to avoid.
@Suppress("TooManyFunctions")
class AnchorLedger(private val clock: () -> Long = System::currentTimeMillis) {

    /** Current client tick as given to [onTickStart]; 0 before the first call and after [reset]. */
    var tick: Int = 0
        private set

    /** Detonations in a row that produced no observable explosion. Any confirmation resets it to 0. */
    var dudStreak: Int = 0
        private set

    /**
     * The dud limit was reached: this world does not appear to explode anchors, and the hub should stop
     * detonating until the world changes (or the user turns `AnyDimension` on). Retracted by a late explosion.
     */
    var latched: Boolean = false
        private set

    /** Cells whose detonation has not been accounted for yet, keyed by `BlockPos.asLong()`. */
    private val pending = Long2ObjectOpenHashMap<PendingDetonation>()

    /** Accounted-for cells kept for [DETONATION_GRACE_TICKS] so late packets still attribute (class KDoc). */
    private val settled = Long2ObjectOpenHashMap<PendingDetonation>()

    /** Cell -> tick its detonation was written off as a dud. */
    private val duds = Long2IntOpenHashMap()

    /** Victim entity id -> lastHurt estimate and the tick it was last updated. */
    private val lastHurt = Int2ObjectOpenHashMap<LastHurt>()

    /** Set when [latched] flips on, consumed by [consumeLatchNotice] so the hub reports it exactly once. */
    private var latchNotice = false

    /**
     * Blasts whose block changes may not have reached this client yet; see [isBlastShadowed]. A handful at most,
     * so a plain list beats any map - it is walked, never looked up by key.
     */
    private val blasts = ArrayList<Blast>()

    /** How long a blast keeps its shadow, in ticks; set from the hub each tick so a ping change takes effect. */
    private var blastShadowTicks = 0

    /**
     * Called once per tick before anything else. Expires pending detonations older than [detonateTimeoutTicks] -
     * each of those is a DUD, counted against [dudLimit] - the grace memory after [DETONATION_GRACE_TICKS], dud
     * marks after [dudTicks] and lastHurt estimates after [LAST_HURT_TICKS].
     *
     * All three windows are read live, so a setting changed mid-fight takes effect at once.
     */
    fun onTickStart(tick: Int, detonateTimeoutTicks: Int, dudTicks: Int, dudLimit: Int, blastShadowTicks: Int) {
        this.tick = tick
        this.blastShadowTicks = blastShadowTicks
        expireBlasts()
        expirePending(detonateTimeoutTicks, dudLimit)
        // Measured from the CLICK, so the window has to cover the pending phase too - otherwise a cell that duds
        // after a long `detonateTimeout` would be swept out of the grace memory in the very same call that put it
        // there, and the late-explosion retraction of [onExplosion] could never fire.
        expireDetonations(settled, detonateTimeoutTicks + DETONATION_GRACE_TICKS)
        expireDuds(dudTicks)
        expireLastHurt()
    }

    /**
     * A detonating click for [pos] went out. Replaces any older entry for the same cell and stamps the centre from
     * [AnchorMath.anchorCenter] so the exact comparisons in [onExplosion] and [onDamageEvent] can work.
     *
     * [predictedLastHurt]: victim entity id -> the value vanilla will store in `lastHurt` (post-shield damage,
     * `LivingEntity.java:1203,1206`); stored as the estimate when > 0 and stamped with the current [tick].
     *
     * An existing dud mark for [pos] is deliberately NOT cleared: the cell stays excluded for its full
     * [dudTicks] window, and a detonation that does land will reset the streak through [onExplosion] anyway.
     */
    fun markDetonated(pos: BlockPos, predictedLastHurt: Map<Int, Float>) {
        val immutable = pos.immutable()
        // The shadow starts HERE, at the click, not when the explosion comes back: the server removes the blocks
        // roughly half a ping from now and we only see it a full ping from now, and that gap is precisely the
        // window in which clicking one of them looks like a click on air ([isBlastShadowed]).
        shadowBlast(AnchorMath.anchorCenter(immutable), AnchorMath.POWER)
        pending.put(
            immutable.asLong(),
            PendingDetonation(immutable, tick, clock(), AnchorMath.anchorCenter(immutable)),
        )
        for ((victimId, damage) in predictedLastHurt) {
            if (damage > 0f) {
                storeLastHurt(victimId, damage)
            }
        }
    }

    /**
     * A `ClientboundExplodePacket` arrived with `center` = [centre] (class KDoc, route 1). Compared by EXACT
     * doubles, which is legitimate because both sides are `pos.getCenter()` of the same block.
     *
     * Two legs: a match against a PENDING detonation clears it and resets [dudStreak]; a match against a cell
     * already written off (grace memory) retracts that dud and releases [latched], because a late explosion is
     * positive proof that anchors do explode in this world.
     *
     * @return whether the explosion was attributable to one of our detonations.
     */
    fun onExplosion(centre: Vec3, radius: Float): Boolean {
        // Anyone's explosion, ours included: the block updates it caused are still in flight. Ours already has a
        // shadow from [markDetonated]; re-shadowing refreshes it, which is right - the confirmation proves the
        // server has acted, and the updates follow it rather than precede it.
        shadowBlast(centre, radius)

        val key = matchCentre(pending, centre)
        if (key != null) {
            settle(key)
            dudStreak = 0
            return true
        }

        val late = matchCentre(settled, centre) ?: return false
        retractDud(late)
        return true
    }

    /**
     * The cell at [pos] stopped reading as a `RespawnAnchorBlock` (class KDoc, route 2). Weaker than the explode
     * packet - someone may simply have mined it - but conclusive for our purposes, since there is nothing left to
     * detonate either way, so it counts as a confirmation and resets [dudStreak].
     *
     * @return whether a pending detonation for [pos] was cleared.
     */
    fun onAnchorGone(pos: BlockPos): Boolean {
        val key = pos.asLong()
        if (!pending.containsKey(key)) {
            return false
        }

        settle(key)
        dudStreak = 0
        return true
    }

    /**
     * `ClientboundDamageEventPacket` for [entityId]. [isBadRespawnPoint] is
     * `sourceType.is(DamageTypes.BAD_RESPAWN_POINT)` and [sourcePosition] the packet's `sourcePosition`
     * (`Optional<Vec3>`, null when absent).
     *
     * The hit is ours when the type matches AND the position is the exact centre of one of our detonations,
     * pending or freshly settled (class KDoc): the stored estimate is kept and its tick refreshed. Any other hit
     * has an unknown amount, so the victim's estimate is dropped - the damage pipeline treats unknown as 0, and
     * both a too-high estimate (suppresses valid cycles) and a too-low one (overestimates damage) are wrong.
     */
    fun onDamageEvent(entityId: Int, isBadRespawnPoint: Boolean, sourcePosition: Vec3?) {
        if (!isBadRespawnPoint || sourcePosition == null || !isOurCentre(sourcePosition)) {
            lastHurt.remove(entityId)
            return
        }

        lastHurt.get(entityId)?.tick = tick
    }

    /** A detonation for [pos] is still unaccounted for: the cell is invisible to the search and the sequencer. */
    fun isDetonating(pos: BlockPos): Boolean = pending.containsKey(pos.asLong())

    /** [pos] detonated without any observable explosion and is still inside its `CycleSettings.dudTicks` window. */
    fun isDud(pos: BlockPos): Boolean = duds.containsKey(pos.asLong())

    /** The search filter: a cell is skipped while a detonation is in flight or while it is a known dud. */
    fun isExcluded(pos: BlockPos): Boolean = isDetonating(pos) || isDud(pos)

    /** The unaccounted-for detonation for [pos], or null. */
    fun pendingDetonation(pos: BlockPos): PendingDetonation? = pending.get(pos.asLong())

    /** Our lastHurt estimate for [entityId], null when unknown (never predicted, discarded or expired). */
    fun lastHurtEstimate(entityId: Int): Float? = lastHurt.get(entityId)?.damage

    /** True exactly once after [latched] flipped on, so the hub prints the notice a single time. */
    fun consumeLatchNotice(): Boolean {
        val notice = latchNotice
        latchNotice = false
        return notice
    }

    /** Forgets everything, [tick] and the dud latch included. Call it on world change and on module disable. */
    fun reset() {
        tick = 0
        dudStreak = 0
        latched = false
        latchNotice = false
        pending.clear()
        settled.clear()
        duds.clear()
        lastHurt.clear()
    }

    /** Moves a pending entry into the grace memory that only the damage attribution and late explosions read. */
    private fun settle(key: Long) {
        val entry = pending.remove(key) ?: return
        settled.put(key, entry)
    }

    /** A cell whose detonation produced nothing observable: excluded, counted, and possibly the latch. */
    private fun markDud(key: Long, entry: PendingDetonation, dudLimit: Int) {
        settled.put(key, entry)
        duds.put(key, tick)
        dudStreak++
        if (dudStreak >= dudLimit && !latched) {
            latched = true
            latchNotice = true
        }
    }

    /** Positive proof arrived after the verdict: drop the dud mark, the streak and the latch. */
    private fun retractDud(key: Long) {
        if (!duds.containsKey(key)) {
            return
        }

        duds.remove(key)
        dudStreak = 0
        latched = false
        latchNotice = false
    }

    private fun isOurCentre(position: Vec3): Boolean =
        matchCentre(pending, position) != null || matchCentre(settled, position) != null

    /**
     * The key of the entry whose [PendingDetonation.centre] equals [position] on all three doubles, or null.
     * Written out rather than delegating to `Vec3.equals` so the exactness is visible at the call site and so no
     * `Double.compare` corner (-0.0, NaN) can ever creep in.
     */
    private fun matchCentre(map: Long2ObjectOpenHashMap<PendingDetonation>, position: Vec3): Long? {
        val iterator = map.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val centre = entry.value.centre
            if (centre.x == position.x && centre.y == position.y && centre.z == position.z) {
                return entry.longKey
            }
        }
        return null
    }

    private fun storeLastHurt(victimId: Int, damage: Float) {
        val entry = lastHurt.get(victimId)
        if (entry == null) {
            lastHurt.put(victimId, LastHurt(damage, tick))
        } else {
            entry.damage = damage
            entry.tick = tick
        }
    }

    private fun isExpired(stampedTick: Int, timeoutTicks: Int): Boolean = (tick - stampedTick) !in 0..timeoutTicks

    /**
     * Whether [pos] sits inside a blast whose aftermath this client may not have received yet, and therefore
     * must not be CLICKED.
     *
     * This is what Grim's `AirLiquidPlace` punishes (`checks/impl/scaffolding/AirLiquidPlace.java`): it reads the
     * block at the clicked position out of its own lag-compensated world and flags when that is air or a
     * no-place liquid - and it runs on EVERY `PLAYER_BLOCK_PLACEMENT`, so a charge or a detonate click counts
     * exactly as much as a placement. Its cancel VL is 0, so the first violation already resyncs the placement.
     *
     * An anchor blast is the one thing this module does that routinely destroys the blocks it is about to click.
     * `RespawnAnchorBlock.explode` passes `ExplosionInteraction.BLOCK` and a damage calculator that overrides the
     * resistance only for the anchor's own cell when it stands in water
     * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:150-169`), so everything around it is destroyed on
     * the ordinary rules. Meanwhile the client predicts NO part of the detonation, so for about one ping it still
     * draws a sphere of blocks that the server has already removed - and every one of them is a click that reads
     * as a click on air.
     *
     * Deliberately coarse: the whole sphere is refused rather than the blocks vanilla's ray walk would actually
     * have broken. Replicating that walk would be a second damage model to keep true, and the cost of being
     * coarse is a pause of a few ticks in a place we genuinely do not know the state of.
     */
    fun isBlastShadowed(pos: BlockPos): Boolean {
        if (blasts.isEmpty()) return false
        val centre = pos.center
        return blasts.any { centre.distanceToSqr(it.centre) <= it.radiusSq }
    }

    /** Arms a shadow around [centre] for [blastShadowTicks]; a zero budget disarms the mechanism entirely. */
    private fun shadowBlast(centre: Vec3, radius: Float) {
        if (blastShadowTicks <= 0 || radius <= 0f) return
        val reach = radius.toDouble() + BLAST_SLACK
        blasts.add(Blast(centre, reach * reach, tick + blastShadowTicks))
    }

    private fun expireBlasts() {
        blasts.removeIf { it.expireTick <= tick }
    }

    private fun expirePending(timeoutTicks: Int, dudLimit: Int) {
        val iterator = pending.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isExpired(entry.value.detonatedTick, timeoutTicks)) {
                markDud(entry.longKey, entry.value, dudLimit)
                iterator.remove()
            }
        }
    }

    private fun expireDetonations(map: Long2ObjectOpenHashMap<PendingDetonation>, timeoutTicks: Int) {
        val iterator = map.long2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value.detonatedTick, timeoutTicks)) {
                iterator.remove()
            }
        }
    }

    private fun expireDuds(timeoutTicks: Int) {
        val iterator = duds.long2IntEntrySet().fastIterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().intValue, timeoutTicks)) {
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

        /**
         * How long a cleared detonation still attributes damage events and late explosions to us, counted from the
         * end of its pending window (see [onTickStart]). One second, the same order as [LAST_HURT_TICKS]: the
         * packets it exists for (class KDoc) arrive in the same tick or the next one, and a longer memory would
         * only widen the foreign-anchor ambiguity.
         */
        const val DETONATION_GRACE_TICKS = 20

        /**
         * Blocks of margin added to a blast's radius before it is refused. Vanilla's ray walk can break a block
         * whose centre sits slightly outside the nominal radius, and the cost of one extra block of caution is
         * nothing next to a flag.
         */
        const val BLAST_SLACK = 1.0
    }
}
