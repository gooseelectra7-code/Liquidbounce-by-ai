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
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldEntityRemoveEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.scaledDimension
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * EntityFinder module
 *
 * Detects entities the server keeps synchronizing while hiding them from the client
 * (vanish or anti-ESP plugins that suppress spawn packets but leak
 * [ClientboundEntityPositionSyncPacket]s) and marks their position with a tag or
 * a directional arrow. Entities that stand perfectly still never get synchronized,
 * so those cannot be found.
 */
object ModuleEntityFinder : ClientModule("EntityFinder", ModuleCategories.RENDER) {

    private val modes by multiEnumChoice("Mode", Mode.TAG, canBeNone = false)
    private val tagScale by float("TagScale", 1.5F, 0.75F..2.5F)
    private val arrowRadius by int("ArrowRadius", 50, 30..100, "px")
    private val arrowScale by float("ArrowScale", 2F, 1F..3F)
    private val timeout by float("Timeout", 5F, 2F..20F, "s")
    private val backgroundColor by color("BackgroundColor", Color4b(20, 12, 0, 125))
    private val arrowColor by color("ArrowColor", Color4b(255, 170, 10))
    private val enableSendInChat by boolean("SendInChat", default = false)

    private enum class Mode(override val tag: String) : Tagged {
        TAG("Tag"),
        ARROW("Arrow"),
    }

    private class Pending(@JvmField val id: Int, @JvmField val pos: Vec3) {
        @JvmField var age = 0
    }

    private class Spotted(
        @JvmField val id: Int,
        @JvmField val name: String?,
        @JvmField var pos: Vec3,
        @JvmField val firstSeen: Long,
    ) {
        @JvmField var lastSeen: Long = firstSeen
    }

    private const val EYE_HEIGHT = 1.62
    private const val FADE_IN_MS = 200F
    private const val FADE_OUT_MS = 300F
    private const val ARROW_TEXT = "▲"

    /**
     * Sync packets are captured on the netty thread and evaluated one tick later on the
     * main thread, so a spawn packet arriving in the same batch has been applied before
     * we decide the entity is missing.
     */
    private val pending = ConcurrentLinkedQueue<Pending>()

    private val spotted = Int2ObjectLinkedOpenHashMap<Spotted>()

    /**
     * Names of players that were removed from the world, so a later leak of their
     * entity id can be attributed to them.
     */
    private val lastKnownPlayers = Int2ObjectOpenHashMap<String>()

    override fun onDisabled() {
        pending.clear()
        spotted.clear()
        lastKnownPlayers.clear()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        pending.clear()
        spotted.clear()
        lastKnownPlayers.clear()
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (event.origin == TransferOrigin.INCOMING && packet is ClientboundEntityPositionSyncPacket) {
            pending += Pending(packet.id, packet.values.position)
        }
    }

    @Suppress("unused")
    private val entityRemoveHandler = handler<WorldEntityRemoveEvent> { event ->
        val entity = event.entity
        if (entity is Player
            && entity !== player
            && !ModuleLogoffSpot.isLogoffEntity(entity.id)
            && !ModuleBlink.isDummyPlayer(entity.id)) {
            lastKnownPlayers.put(entity.id, entity.scoreboardName)
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val now = System.currentTimeMillis()

        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.age++ < 1) {
                continue
            }
            iterator.remove()

            if (world.getEntity(entry.id) != null) {
                // The entity is (or became) visible, nothing is hidden here
                spotted.remove(entry.id)
                continue
            }

            val existing = spotted.get(entry.id)
            if (existing != null) {
                existing.pos = entry.pos
                existing.lastSeen = now
            } else {
                val name = lastKnownPlayers.get(entry.id)
                spotted.put(entry.id, Spotted(entry.id, name, entry.pos, now))

                if (enableSendInChat) {
                    val blockPos = BlockPosArgs(entry.pos)
                    chat(regular(message(
                        "spotted",
                        name ?: message("unknown"),
                        blockPos.x, blockPos.y, blockPos.z,
                    )))
                }
            }
        }

        val deadline = now - (timeout * 1000F).toLong()
        spotted.values.removeIf { it.lastSeen < deadline || world.getEntity(it.id) != null }
    }

    @JvmRecord
    private data class BlockPosArgs(val x: Int, val y: Int, val z: Int) {
        constructor(pos: Vec3) : this(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z))
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        if (spotted.isEmpty()) {
            return@handler
        }

        val context = event.context
        val font = mc.font
        val now = System.currentTimeMillis()
        val timeoutMs = timeout * 1000F

        val cameraYaw = mc.gameRenderer.mainCamera.yRot()
        val cameraPos = WorldToScreen.cameraPosition
        val (width, height) = mc.window.scaledDimension

        for (entry in spotted.values) {
            val fadeIn = min((now - entry.firstSeen) / FADE_IN_MS, 1F)
            val fadeOut = ((timeoutMs - (now - entry.lastSeen)) / FADE_OUT_MS).coerceIn(0F, 1F)
            val alpha = min(fadeIn, fadeOut)
            if (alpha < 0.05F) {
                continue
            }

            if (Mode.TAG in modes) {
                drawTag(context, font, entry, now, alpha)
            }

            if (Mode.ARROW in modes) {
                drawArrow(context, font, entry, cameraPos, cameraYaw, width, height, alpha)
            }
        }
    }

    private fun drawTag(context: GuiGraphicsExtractor, font: Font, entry: Spotted, now: Long, alpha: Float) {
        val screenPos = WorldToScreen.calculateScreenPos(entry.pos.add(0.0, EYE_HEIGHT, 0.0)) ?: return

        val tenths = (now - entry.firstSeen) / 100L
        val text = Component.empty()
        entry.name?.let { name -> text.append(Component.literal("$name ")) }
        text.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("${tenths / 10}.${tenths % 10}s").withStyle(ChatFormatting.RED))
            .append(Component.literal("]").withStyle(ChatFormatting.GRAY))

        val halfWidth = font.width(text) / 2
        val halfHeight = font.lineHeight / 2

        val pose = context.pose()
        pose.pushMatrix()
        pose.translate(screenPos.x, screenPos.y)
        pose.scale(tagScale, tagScale)
        context.fill(
            -halfWidth - 2, -halfHeight - 2,
            halfWidth + 2, halfHeight + 2,
            backgroundColor.fade(alpha).argb,
        )
        context.centeredText(font, text, 0, -halfHeight, Color4b.WHITE.fade(alpha).argb)
        pose.popMatrix()
    }

    @Suppress("LongParameterList")
    private fun drawArrow(
        context: GuiGraphicsExtractor,
        font: Font,
        entry: Spotted,
        cameraPos: Vec3,
        cameraYaw: Float,
        width: Int,
        height: Int,
        alpha: Float,
    ) {
        val dx = entry.pos.x - cameraPos.x
        val dz = entry.pos.z - cameraPos.z
        if (dx * dx + dz * dz < 1.0) {
            // Right on top of us, an arrow direction would be meaningless
            return
        }

        // Minecraft yaw convention: 0 faces +Z, increasing clockwise
        val yawToTarget = Math.toDegrees(atan2(-dx, dz))
        val relative = Math.toRadians(Mth.wrapDegrees(yawToTarget - cameraYaw))

        val x = width / 2F + sin(relative).toFloat() * arrowRadius
        val y = height / 2F - cos(relative).toFloat() * arrowRadius

        val pose = context.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.rotate(relative.toFloat())
        pose.scale(arrowScale, arrowScale)
        context.centeredText(font, ARROW_TEXT, 0, -font.lineHeight / 2, arrowColor.fade(alpha).argb)
        pose.popMatrix()
    }

}
