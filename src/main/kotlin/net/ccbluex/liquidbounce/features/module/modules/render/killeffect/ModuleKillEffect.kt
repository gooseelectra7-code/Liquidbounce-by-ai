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
package net.ccbluex.liquidbounce.features.module.modules.render.killeffect

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.AABB
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * KillEffect
 *
 * Triggers a visual effect at the death position when the local player kills an entity.
 */
object ModuleKillEffect : ClientModule("KillEffect", ModuleCategories.RENDER) {

    /** Window (in ticks) during which a death after our attack still counts as our kill. */
    private const val ATTACK_TTL_TICKS = 130L

    /**
     * Window for a death credited to an end crystal we detonated ([creditBlast]).
     *
     * Much shorter than [ATTACK_TTL_TICKS] because the explosion is resolved while the server handles our attack
     * packet — the victim is dead within one server tick of it, so anything later than about a second is somebody
     * else's kill. It only has to survive the round trip of the attack plus the death packet.
     */
    private const val BLAST_TTL_TICKS = 30L

    /**
     * Feet-distance cut-off of the crystal explosion, `radius * 2.0` with the crystal's `radius = 6.0F`
     * (`EndCrystal.hurtServer` → `Level.explode(..., 6.0F, ...)`, `EndCrystal.java:88-99`).
     * `ServerExplosion.hurtEntities` skips every entity whose `distance / doubleRadius` exceeds `1.0`
     * (`ServerExplosion.java:181-190`), so nothing outside this can have been killed by the blast.
     */
    private const val BLAST_RADIUS = 12.0

    internal val modes = choices(
        "Mode",
        KillEffectParticlesMode,
        arrayOf<Mode>(KillEffectParticlesMode, KillEffectWaveMode, KillEffectScanMode),
    )

    /**
     * Off = any death in range plays the effect, not only the ones we finished with our own hand.
     *
     * The last hit is often not a swing at all. End crystals are handled ([creditBlast]), so a crystal kill —
     * from a crystal aura or from the crosshair — now counts as ours with this on. An anchor or an arrow still
     * does not: they leave no [AttackEntityEvent] at all, so this remains the switch to reach for when such
     * kills go unnoticed.
     */
    private val onlyOwnKills by boolean("OnlyOwnKills", true)
    private val maximumDistance by float("MaximumDistance", 64f, 4f..256f, "blocks")

    /** UUID of recently-attacked entities → tick they should be forgotten at. */
    private val attackedEntities = Object2LongOpenHashMap<UUID>().apply {
        defaultReturnValue(-1L)
    }

    /**
     * Deaths arrive as an entity-event packet on the network thread, so they are parked here and
     * resolved against the world on the next tick.
     *
     * This packet is the whole trigger. Watching entity removals — what this module used to do —
     * could never work: the client removes every entity with
     * [net.minecraft.world.entity.Entity.RemovalReason.DISCARDED] whatever happened to it, and
     * `KILLED` is a server-side reason that never reaches us. The death status the server sends to
     * play the death animation is the one signal a client actually gets.
     */
    private val pendingDeaths = ConcurrentLinkedQueue<ClientboundEntityEventPacket>()

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        val now = player.tickCount.toLong()
        credit(event.entity.uuid, now + ATTACK_TTL_TICKS)

        val crystal = event.entity as? EndCrystal
        if (crystal != null) creditBlast(crystal, now)
    }

    /**
     * Credits everything the crystal we just attacked can kill, so a crystal kill counts as ours under
     * [onlyOwnKills].
     *
     * Attacking a crystal fires [AttackEntityEvent] with the CRYSTAL as `event.entity`, so the player the crystal
     * is aimed at never entered [attackedEntities] and the effect only ever played with [onlyOwnKills] off — the
     * gap this module's own documentation describes ("a crystal, an anchor or an arrow never shows up"). The same
     * hole covers every crystal aura, the manual crystal PvP under the crosshair and anything else that detonates
     * one, which is why the credit is attached to the attack itself rather than to any particular module.
     *
     * Any damage the crystal deals is dealt by the explosion it triggers (`EndCrystal.hurtServer` removes the
     * crystal and calls `level.explode(..., 6.0F, ...)`, `EndCrystal.java:88-99`), and vanilla measures that blast
     * from the victim's FEET against `radius * 2.0` (`ServerExplosion.java:181-190`) — hence
     * [BLAST_RADIUS] and `position()` rather than a bounding-box distance.
     *
     * This over-credits: everything inside the radius is marked, not only what actually takes lethal damage. That
     * is deliberate — the module would otherwise need the whole exposure and armour model to decide, while the
     * effect plays only if the entity really dies, and only within [BLAST_TTL_TICKS]. An existing longer window is
     * never shortened ([credit] keeps the later expiry).
     */
    private fun creditBlast(crystal: EndCrystal, now: Long) {
        val center = crystal.position()
        val bounds = AABB.ofSize(center, BLAST_RADIUS * 2, BLAST_RADIUS * 2, BLAST_RADIUS * 2)
        val expireAt = now + BLAST_TTL_TICKS
        for (victim in crystal.level().getEntitiesOfClass(LivingEntity::class.java, bounds)) {
            if (victim !== player && victim.position().distanceTo(center) <= BLAST_RADIUS) {
                credit(victim.uuid, expireAt)
            }
        }
    }

    /** Marks [uuid] as ours until [expireAt], never bringing an already later expiry forward. */
    private fun credit(uuid: UUID, expireAt: Long) {
        if (attackedEntities.getLong(uuid) < expireAt) attackedEntities.put(uuid, expireAt)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet as? ClientboundEntityEventPacket ?: return@handler
        if (packet.eventId == EntityEvent.DEATH) {
            pendingDeaths += packet
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        var packet = pendingDeaths.poll()
        while (packet != null) {
            val victim = mc.level?.let(packet::getEntity) as? LivingEntity
            if (victim != null && isOurKill(victim)) {
                (modes.activeMode as? KillEffectMode)?.onKill(victim)
            }
            packet = pendingDeaths.poll()
        }
    }

    private fun isOurKill(victim: LivingEntity): Boolean {
        if (victim === mc.player || victim.distanceTo(player) > maximumDistance) {
            return false
        }

        val expireAt = attackedEntities.removeLong(victim.uuid)
        val attackedByUs = expireAt >= 0L && expireAt >= player.tickCount.toLong()
        return attackedByUs || !onlyOwnKills
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        attackedEntities.clear()
        pendingDeaths.clear()
        resetActive()
    }

    private fun resetActive() {
        (modes.activeMode as? KillEffectMode)?.reset()
    }

    override fun onDisabled() {
        attackedEntities.clear()
        pendingDeaths.clear()
        resetActive()
        super.onDisabled()
    }

}
