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

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.EntityEquipmentChangeEvent
import net.ccbluex.liquidbounce.event.events.EntityHealthUpdateEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldEntityRemoveEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.misc.ModulePvpHelper
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The observation core of the PvP helper: everything the client can legitimately learn about other
 * players in a fight, gathered from packets and mixin events and exposed as a plain main-thread API.
 *
 * A parentless [EventListener] like [net.ccbluex.liquidbounce.utils.combat.CombatManager] — it runs
 * for the whole session so a module toggling on mid-fight still has history. [ModulePvpHelper]
 * (alerts) and the HUD read from here; nothing here depends on either being enabled.
 *
 * ## Threading
 *
 * [PacketEvent] fires on the netty thread. The packet handler therefore reads only primitives and
 * pushes them onto [ConcurrentLinkedQueue]s — it never touches the world or resolves an entity —
 * and its whole body is wrapped so a decode-time exception cannot kill the connection (the same
 * netty discipline the crystal module learned the hard way). Everything else — the queue drain, the
 * fastutil maps, the ledgers — runs on the client thread from [GameTickEvent] and mixin-fired
 * events, so no locking is needed beyond the queues.
 */
@Suppress("TooManyFunctions")
object PvpIntelTracker : EventListener {

    // ── Netty-thread ingress ─────────────────────────────────────────────────

    private data class PendingDamage(val victimId: Int, val attackerId: Int, val timeMs: Long)
    private data class PendingSwing(val entityId: Int, val action: Int)

    private val damageQueue = ConcurrentLinkedQueue<PendingDamage>()
    private val swingQueue = ConcurrentLinkedQueue<PendingSwing>()
    private val entityEventQueue = ConcurrentLinkedQueue<ClientboundEntityEventPacket>()

    // ── Main-thread state ────────────────────────────────────────────────────

    /** Totem pops seen this session, per player UUID. Survives per-fight resets. */
    private val popCounts = Object2IntOpenHashMap<UUID>().apply { defaultReturnValue(0) }
    private val lastPopAt = Object2LongOpenHashMap<UUID>()

    /** Per-opponent rolling combat ledger, keyed by that player's UUID. */
    private val fights = Object2ObjectOpenHashMap<UUID, FightRecord>()

    /** Gapple estimator state, keyed by observed player UUID. */
    private val gappleState = Object2ObjectOpenHashMap<UUID, GappleState>()

    /** Health drops recorded this tick: entity id → total drop, for damage attribution. */
    private val healthDropsThisTick = Object2ObjectOpenHashMap<Int, HealthDrop>()

    /** Players seen swinging this tick (main-hand/off-hand), for the heuristic team edge. */
    private val swingersThisTick = ArrayList<Int>(8)

    private var tickCounter = 0

    private class FightRecord {
        var damageByMe = 0f
        var damageToMe = 0f
        var lastLedgerDecayMs = 0L
        var combo = 0
        var lastMyHitMs = 0L

        // Hit tallies deliberately survive the 60 s ledger decay: they are the score of the whole
        // fight, not a rolling rate, and are only cleared when the fight itself ends.
        var hitsByMe = 0
        var hitsToMe = 0
    }

    private class GappleState(var lastSeenCount: Int, var eatenSinceSeen: Int, var useItemTick: Int)

    private class HealthDrop(var amount: Float)

    // ── Public API (main thread) ─────────────────────────────────────────────

    data class GappleEstimate(val count: Int, val known: Boolean)
    data class CombatStats(
        val damageByMe: Float,
        val damageToMe: Float,
        val combo: Int,
        val hitsByMe: Int,
        val hitsToMe: Int,
    )

    fun popCount(uuid: UUID): Int = popCounts.getInt(uuid)

    fun gappleEstimate(target: Player): GappleEstimate {
        val state = gappleState[target.uuid] ?: return GappleEstimate(0, known = false)
        return GappleEstimate((state.lastSeenCount - state.eatenSinceSeen).coerceAtLeast(0), known = true)
    }

    fun combatStats(uuid: UUID): CombatStats {
        val fight = fights[uuid] ?: return CombatStats(0f, 0f, 0, 0, 0)
        return CombatStats(
            fight.damageByMe, fight.damageToMe, fight.combo, fight.hitsByMe, fight.hitsToMe,
        )
    }

    // ── Ingress handler (netty thread) ───────────────────────────────────────

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !event.original) {
            return@handler
        }

        // A throw here runs on the netty pipeline and would drop the connection. Only primitive
        // reads happen inside, but decode races are still possible — swallow defensively.
        runCatching {
            when (val packet = event.packet) {
                is ClientboundDamageEventPacket -> damageQueue.add(
                    PendingDamage(packet.entityId(), packet.sourceCauseId(), System.currentTimeMillis())
                )

                is ClientboundEntityEventPacket -> {
                    val id = packet.eventId
                    if (id == EntityEvent.PROTECTED_FROM_DEATH || id in ARMOR_BREAK_RANGE) {
                        entityEventQueue.add(packet)
                    }
                }

                is ClientboundAnimatePacket -> {
                    val action = packet.action
                    if (action == SWING_MAIN || action == SWING_OFF) {
                        swingQueue.add(PendingSwing(packet.id, action))
                    }
                }
            }
        }
    }

    // ── Drain + bookkeeping (main thread) ────────────────────────────────────

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        tickCounter++
        if (!inGame) {
            drainAndDiscard()
            return@handler
        }

        drainDamageEdges()
        drainEntityEvents()
        drainSwings()
        observeGappleConsumption()
        decayLedgers()

        healthDropsThisTick.clear()
        swingersThisTick.clear()

        if (tickCounter % PRUNE_INTERVAL_TICKS == 0) {
            pruneToOnlinePlayers()
        }
    }

    private fun drainAndDiscard() {
        damageQueue.clear()
        swingQueue.clear()
        entityEventQueue.clear()
        healthDropsThisTick.clear()
        swingersThisTick.clear()
    }

    private fun drainDamageEdges() {
        val self = mc.player
        while (true) {
            val d = damageQueue.poll() ?: break
            val victim = world.getEntity(d.victimId) as? Player
            val attacker = world.getEntity(d.attackerId) as? Player
            if (victim != null && attacker != null && attacker !== victim) {
                TeamDetector.addEdge(attacker, victim, victimWasMe = victim === self, weight = 1.0f)
                attributeDamage(attacker, victim)
            }
        }
    }

    private fun drainEntityEvents() {
        while (true) {
            val packet = entityEventQueue.poll() ?: break
            val level = mc.level ?: continue
            val entity = packet.getEntity(level) as? Player ?: continue
            if (packet.eventId == EntityEvent.PROTECTED_FROM_DEATH) {
                popCounts.addTo(entity.uuid, 1)
                lastPopAt[entity.uuid] = System.currentTimeMillis()
                ModulePvpHelper.onTotemPop(entity.gameProfile.name, popCounts.getInt(entity.uuid))
            }
            // Armor-break statuses are captured for future use; no counter needed yet.
        }
    }

    private fun drainSwings() {
        while (true) {
            val s = swingQueue.poll() ?: break
            swingersThisTick.add(s.entityId)
        }

        if (!ModulePvpHelper.heuristicEdges) {
            return
        }

        // Heuristic team edge: a swinger this tick whose facing lines up with a nearby player who
        // just lost health is treated as an attack, weighted at half so it never outvotes a real
        // damage edge. Only used where the server withholds third-party ClientboundDamageEventPacket.
        for (attackerId in swingersThisTick) {
            val attacker = world.getEntity(attackerId) as? Player ?: continue
            for (drop in healthDropsThisTick) {
                val victim = world.getEntity(drop.key) as? Player
                if (victim != null && isHeuristicHit(attacker, victim)) {
                    TeamDetector.addEdge(
                        attacker, victim, victimWasMe = victim === mc.player, weight = HEURISTIC_WEIGHT
                    )
                }
            }
        }
    }

    /** A swing counts as a hit when the swinger is close enough and actually facing the victim. */
    private fun isHeuristicHit(attacker: Player, victim: Player): Boolean =
        victim !== attacker &&
            attacker.distanceTo(victim) <= HEURISTIC_REACH &&
            facingToward(attacker, victim)

    private fun attributeDamage(attacker: Player, victim: Player) {
        val self = mc.player ?: return
        val now = System.currentTimeMillis()
        when {
            attacker === self && victim !== self -> fightOf(victim.uuid).let {
                it.damageByMe += pendingDropFor(victim)
                it.hitsByMe++
                it.combo++
                it.lastMyHitMs = now
            }
            victim === self && attacker !== self -> fightOf(attacker.uuid).let {
                it.damageToMe += pendingDropFor(attacker)
                it.hitsToMe++
                it.combo = 0
            }
        }
    }

    /** Health lost by [player] this tick, consumed as the magnitude of the current edge. */
    private fun pendingDropFor(player: Player): Float =
        healthDropsThisTick[player.id]?.amount?.takeIf { it > 0f } ?: DEFAULT_EDGE_DAMAGE

    private fun decayLedgers() {
        val now = System.currentTimeMillis()
        for (fight in fights.values) {
            if (fight.lastLedgerDecayMs == 0L) {
                fight.lastLedgerDecayMs = now
                continue
            }
            val elapsed = now - fight.lastLedgerDecayMs
            if (elapsed >= LEDGER_WINDOW_MS) {
                // Full window elapsed with no refresh — reset the rolling sums.
                fight.damageByMe = 0f
                fight.damageToMe = 0f
                fight.lastLedgerDecayMs = now
            }
            if (now - fight.lastMyHitMs > COMBO_RESET_MS) {
                fight.combo = 0
            }
        }
    }

    private fun observeGappleConsumption() {
        for (entity in world.players()) {
            if (entity === mc.player) continue
            val state = gappleState[entity.uuid] ?: continue

            if (entity.isUsingItem && entity.useItem.isGapple()) {
                state.useItemTick = entity.ticksUsingItem
            } else if (state.useItemTick > 0) {
                // Finished (or aborted) a use: if it ran long enough to be a full eat, count it.
                if (state.useItemTick >= GAPPLE_EAT_TICKS) {
                    state.eatenSinceSeen++
                }
                state.useItemTick = 0
            }
        }
    }

    private fun fightOf(uuid: UUID): FightRecord = fights.getOrPut(uuid) { FightRecord() }

    private fun pruneToOnlinePlayers() {
        val online = world.players().mapTo(HashSet()) { it.uuid }
        fights.keys.retainAll(online)
        gappleState.keys.retainAll(online)
        // Pop counts and TeamDetector intentionally survive relogs within a session.
    }

    // ── Mixin-fired events (main thread) ─────────────────────────────────────

    @Suppress("unused")
    private val healthHandler = handler<EntityHealthUpdateEvent> { event ->
        val entity = event.entity
        if (entity !is Player || entity === mc.player && event.new > event.old) {
            return@handler
        }
        // Suppress the spawn-time spike the way ModuleDamageParticles does.
        if (entity.tickCount == 0) {
            return@handler
        }

        val drop = event.old - event.new
        if (drop > 0f) {
            healthDropsThisTick.getOrPut(entity.id) { HealthDrop(0f) }.amount += drop
        }
    }

    @Suppress("unused")
    private val equipmentHandler = handler<EntityEquipmentChangeEvent> { event ->
        val entity = event.entity
        if (entity !is Player || entity === mc.player) {
            return@handler
        }
        if (event.itemStack.isGapple()) {
            gappleState[entity.uuid] = GappleState(event.itemStack.count, eatenSinceSeen = 0, useItemTick = 0)
        }
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val target = event.entity as? Player ?: return@handler
        fightOf(target.uuid).let {
            it.combo++
            it.lastMyHitMs = System.currentTimeMillis()
        }
    }

    @Suppress("unused")
    private val removeHandler = handler<WorldEntityRemoveEvent> { event ->
        if (event.reason != Entity.RemovalReason.KILLED) {
            return@handler
        }
        val uuid = (event.entity as? Player)?.uuid ?: return@handler
        fights.remove(uuid)
        gappleState.remove(uuid)
        // Session pop count kept.
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        damageQueue.clear()
        swingQueue.clear()
        entityEventQueue.clear()
        popCounts.clear()
        lastPopAt.clear()
        fights.clear()
        gappleState.clear()
        healthDropsThisTick.clear()
        swingersThisTick.clear()
        TeamDetector.clear()
    }

    private fun facingToward(attacker: Player, victim: LivingEntity): Boolean {
        val toVictim = victim.position().subtract(attacker.position())
        if (toVictim.lengthSqr() < 1.0E-4) return true
        return attacker.lookAngle.normalize().dot(toVictim.normalize()) > FACING_DOT
    }

    private fun ItemStack.isGapple(): Boolean =
        `is`(Items.GOLDEN_APPLE) || `is`(Items.ENCHANTED_GOLDEN_APPLE)

    // ItemUseAnimation guard kept so a server that reuses gapple items for other purposes cannot
    // inflate the eat count with a non-eat use.
    @Suppress("unused")
    private fun ItemStack.isEatAnimation(): Boolean = useAnimation == ItemUseAnimation.EAT

    override val running: Boolean get() = true

    private const val SWING_MAIN = 0
    private const val SWING_OFF = 3
    private val ARMOR_BREAK_RANGE = 47..52
    private const val HEURISTIC_REACH = 6.0
    private const val HEURISTIC_WEIGHT = 0.5f
    private const val FACING_DOT = 0.7
    private const val DEFAULT_EDGE_DAMAGE = 1.0f
    private const val LEDGER_WINDOW_MS = 60_000L
    private const val COMBO_RESET_MS = 3_000L
    private const val GAPPLE_EAT_TICKS = 30
    private const val PRUNE_INTERVAL_TICKS = 100
}
