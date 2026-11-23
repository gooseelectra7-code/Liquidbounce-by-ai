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
package net.ccbluex.liquidbounce.deeplearn.clone

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.network.isLocalPlayerDamage
import net.ccbluex.liquidbounce.utils.network.isLocalPlayerVelocity
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The combat signals both the recorder and the live aim path read, so they read the same thing.
 *
 * Exists because they did not. Knockback was a received packet when recording and a horizontal
 * speed threshold at inference — and that threshold is crossed by ordinary sprint-jumping, so a
 * column that was almost always zero in training was pinned at one for most of a real fight,
 * driving the model into an input region it had never seen. The attack flag repeats the lesson:
 * the recorder labelled real `ServerboundAttackPacket`s, while inference fed the model its own
 * *intention* to click — which KillAura's gates then vetoed often enough that the history channel
 * described attacks that never went on the wire.
 *
 * The flags are set from the netty thread and consumed on the game thread, so they are swapped
 * atomically: reading and clearing separately loses any packet that lands between the two.
 */
object CloneCombatSignals : EventListener {

    private val velocity = AtomicBoolean()
    private val damage = AtomicBoolean()
    private val attack = AtomicBoolean()
    private val pendingAttackEntity = AtomicInteger(NO_ENTITY)
    private val sentAttackEntity = AtomicInteger(NO_ENTITY)

    /** Set for the tick in which the packet was seen, cleared when that tick is consumed. */
    var receivedVelocity: Boolean = false
        private set

    var tookDamage: Boolean = false
        private set

    /**
     * Whether a real attack packet went out during the previous tick interval — the exact quantity
     * the recorder stores as `AimTick.attacked`, so the feature built from this at inference means
     * the same thing it meant in training. A decision the clicker gated away does not count.
     */
    var attackedLastTick: Boolean = false
        private set

    /** Entity id paired with [attackedLastTick], or `-1` for an attack sent outside vanilla's hook. */
    var attackedEntityIdLastTick: Int = NO_ENTITY
        private set

    override val running: Boolean
        get() = true

    /**
     * Stages the target resolved by vanilla. The final packet handler below only commits it if the
     * corresponding packet survives cancellation, so an attempted hit that another module ate is
     * not mislabeled as combat.
     */
    @Suppress("unused")
    private val attackEntityHandler = handler<AttackEntityEvent>(priority = 2000) { event ->
        pendingAttackEntity.set(event.entity.id)
    }

    /**
     * Reads the final state: a packet another module cancelled never reaches the wire, and a
     * knockback the Velocity module ate never perturbs the aim, so neither is a combat signal.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(
        priority = EventPriorityConvention.READ_FINAL_STATE
    ) { event ->
        if (event.isCancelled) {
            return@handler
        }

        if (event.packet.isLocalPlayerVelocity()) {
            velocity.set(true)
        }
        if (event.packet.isLocalPlayerDamage()) {
            damage.set(true)
        }
        // Only a real attack — the generic interact helper also matches right-clicks and spectate
        // requests, so anything looser would count saddling a pig as a swing.
        if (event.packet is ServerboundAttackPacket) {
            attack.set(true)
            // A packet a module built and sent directly — AutoCrystal breaking a crystal is the
            // constant case — has no AttackEntityEvent, so the pending slot holds NO_ENTITY.
            // Committing that would erase the id of a real swing from earlier in the same tick.
            // The id must mean "what vanilla attacked", so an id-less packet leaves it alone and
            // a tick with only module packets reports NO_ENTITY, which readers treat as "not the
            // player's own swing".
            val pending = pendingAttackEntity.getAndSet(NO_ENTITY)
            if (pending != NO_ENTITY) {
                sentAttackEntity.set(pending)
            }
        }
    }

    /**
     * Records an attack whose packet deliberately never reaches the wire — the gym's practice
     * dummy exists only on this client, so its packet is cancelled to keep the server ignorant,
     * yet the click that produced it is exactly the physical act the recorder's label means.
     * Without this, schema v5's attack-centred sanitizer finds no attacks in a gym session and
     * throws the entire drill away.
     */
    fun notePracticeAttack(entityId: Int) {
        attack.set(true)
        sentAttackEntity.set(entityId)
        pendingAttackEntity.set(NO_ENTITY)
    }

    /**
     * Runs before anything that reads the flags — the recorder and the rotation system are both
     * default or lower priority on this event.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent>(priority = 2000) {
        receivedVelocity = velocity.getAndSet(false)
        tookDamage = damage.getAndSet(false)
        attackedLastTick = attack.getAndSet(false)
        attackedEntityIdLastTick = sentAttackEntity.getAndSet(NO_ENTITY)
        pendingAttackEntity.set(NO_ENTITY)
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        velocity.set(false)
        damage.set(false)
        attack.set(false)
        pendingAttackEntity.set(NO_ENTITY)
        sentAttackEntity.set(NO_ENTITY)
        receivedVelocity = false
        tookDamage = false
        attackedLastTick = false
        attackedEntityIdLastTick = NO_ENTITY
    }

    private const val NO_ENTITY = -1
}
