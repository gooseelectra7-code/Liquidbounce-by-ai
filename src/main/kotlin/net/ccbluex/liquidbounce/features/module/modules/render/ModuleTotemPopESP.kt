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
package net.ccbluex.liquidbounce.features.module.modules.render

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TagEntityEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.GenericRainbowColorMode
import net.ccbluex.liquidbounce.render.GenericStaticColorMode
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.render.entity
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TotemPopESP
 *
 * Leaves a fading ghost of a player behind when they pop a totem of undying. The ghost drifts
 * upwards out of the body it came from and dissolves over [lifetime].
 *
 * Rendering the ghost is not done by hand — modern Minecraft extracts entity render state in a
 * separate pass, so drawing an entity outside that pipeline is not possible. Instead a client-side
 * [RemotePlayer] is inserted into the world (the same trick [ModuleLogoffSpot] uses) and
 * `MixinLivingEntityRenderer` turns it translucent and tints it per frame.
 */
object ModuleTotemPopESP : ClientModule("TotemPopESP", ModuleCategories.RENDER) {

    private val lifetime by int("Lifetime", 3000, 800..6000, "ms")
    private val riseHeight by float("RiseHeight", 4f, 0f..12f, "blocks")

    /**
     * Ghosts are cheap but not free — each one is a full player model with armor layers. Popping
     * repeatedly in a crowded fight would otherwise pile up unbounded.
     */
    private val maxGhosts by int("MaxGhosts", 12, 1..48)

    private val colorModes = choices("ColorMode", 0) {
        arrayOf(
            GenericStaticColorMode(it, Color4b(0, 191, 255, 255)),
            GenericRainbowColorMode(it, alpha = 255),
        )
    }

    private class PopGhost(val ghost: RemotePlayer, val origin: Vec3) {
        val spawnedAt: Long = System.currentTimeMillis()

        /** 0 at the moment of the pop, 1 once the ghost has fully dissolved. */
        fun progress(lifetimeMs: Int): Float =
            ((System.currentTimeMillis() - spawnedAt).toFloat() / lifetimeMs).coerceIn(0f, 1f)
    }

    /** Ghost entity id -> ghost. Linked so the oldest entry can be evicted when the cap is hit. */
    private val ghosts = Int2ObjectLinkedOpenHashMap<PopGhost>()

    /**
     * Pops arrive on the network thread, but entities may only be added from the main thread, so
     * the packets are parked here and resolved against the world on the next tick. The packet only
     * exposes its subject through [ClientboundEntityEventPacket.getEntity], so the packet itself
     * has to be kept rather than an id.
     */
    private val pendingPops = ConcurrentLinkedQueue<ClientboundEntityEventPacket>()

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet as? ClientboundEntityEventPacket ?: return@handler

        if (packet.eventId == EntityEvent.PROTECTED_FROM_DEATH) {
            pendingPops += packet
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        drainPops()
        updateGhosts()
    }

    private fun drainPops() {
        while (true) {
            val packet = pendingPops.poll() ?: break
            val level = mc.level ?: continue
            val victim = packet.getEntity(level) as? Player ?: continue

            // A ghost of a ghost would spawn if the server ever echoed the event back at one.
            if (victim.isAlive && !ghosts.containsKey(victim.id)) {
                spawnGhost(victim)
            }
        }
    }

    private fun spawnGhost(victim: Player) {
        while (ghosts.size >= maxGhosts) {
            discard(ghosts.removeFirst())
        }

        val ghost = RemotePlayer(world, victim.gameProfile)
        ghost.copyPosition(victim)
        ghost.yHeadRot = victim.yHeadRot
        ghost.setUUID(UUID.randomUUID())
        ghost.inventory.replaceWith(victim.inventory)
        ghost.health = victim.getActualHealth()

        // The ghost is a prop: it must not fall, drift, or be pushed around by the world tick,
        // because its position is fully driven by [updateGhosts].
        ghost.isNoGravity = true
        ghost.deltaMovement = Vec3.ZERO

        world.addEntity(ghost)
        ghosts.put(ghost.id, PopGhost(ghost, victim.position()))
    }

    private fun updateGhosts() {
        val iterator = ghosts.values.iterator()

        while (iterator.hasNext()) {
            val popGhost = iterator.next()
            val ghost = popGhost.ghost
            val progress = popGhost.progress(lifetime)

            if (progress >= 1f) {
                discard(popGhost)
                iterator.remove()
                continue
            }

            // Seed the previous position from where the ghost actually was, otherwise the renderer
            // interpolates from whatever the world tick left behind and the rise judders.
            ghost.setOldPosAndRot()
            ghost.setPos(popGhost.origin.add(0.0, (progress * riseHeight).toDouble(), 0.0))
            ghost.deltaMovement = Vec3.ZERO
        }
    }

    private fun discard(popGhost: PopGhost) {
        // [mc.level] rather than [world]: disabling outside a world would otherwise throw.
        mc.level?.removeEntity(popGhost.ghost.id, Entity.RemovalReason.DISCARDED)
    }

    /** Keeps the aura, ESP and nametags from treating a decoration as a real player. */
    @Suppress("unused")
    private val tagHandler = handler<TagEntityEvent> { event ->
        if (ghosts.containsKey(event.entity.id)) {
            event.ignore()
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        pendingPops.clear()
        ghosts.clear()
    }

    override fun onDisabled() {
        pendingPops.clear()
        ghosts.values.forEach(::discard)
        ghosts.clear()
        super.onDisabled()
    }

    /**
     * Tint applied to a ghost this frame, or `null` when [state] does not belong to this module.
     * Alpha fades linearly across the ghost's life.
     */
    @JvmStatic
    fun tintFor(state: EntityRenderState): Int? {
        if (!running) {
            return null
        }

        val popGhost = ghosts.get(state.entity?.id ?: return null) ?: return null
        val color = colorModes.activeMode.getColor(null)
        val alpha = (color.a * (1f - popGhost.progress(lifetime))).toInt().coerceIn(0, 255)

        return color.with(a = alpha).argb
    }

    @JvmStatic
    fun isPopGhost(state: EntityRenderState): Boolean =
        running && ghosts.containsKey(state.entity?.id ?: return false)

}
