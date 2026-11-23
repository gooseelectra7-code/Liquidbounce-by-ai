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

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawCustomMeshTextured
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import com.mojang.math.Axis
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.min

/**
 * TotemGhost
 *
 * Port of the NightShift module: popping a totem leaves a translucent wireframe copy of the
 * victim behind, which drifts upwards, turns on the spot and fades out.
 *
 * The ghost is not an entity — it is a six-box rig (head, body, two arms, two legs) posed once
 * from the victim at the moment of the pop: body and head yaw, head pitch and the limb swing the
 * walk animation was at. From then on it is pure geometry, so it costs nothing to keep a handful
 * of them on screen and none of them can be desynced by the server.
 */
object ModuleTotemGhost : ClientModule("TotemGhost", ModuleCategories.RENDER) {

    private val color by color("Color", Color4b(0, 255, 255, 255))
    private val rise by float("Rise", 2f, 0.5f..4f, "blocks")
    private val duration by float("Duration", 2f, 1f..4f, "s")
    private val spin by float("Spin", 40f, 0f..180f, "°")
    private val fillAlpha by float("FillAlpha", 0.25f, 0f..1f)
    private val outlineAlpha by float("OutlineAlpha", 0.45f, 0f..1f)
    private val glow by boolean("Glow", true)
    private val maxGhosts by int("MaxGhosts", 8, 1..24)
    private val selfGhost by boolean("Self", true)

    /**
     * The rig, in the victim's own space: minX, minY, minZ, maxX, maxY, maxZ. Same proportions the
     * vanilla player model has, so the ghost reads as a person and not as a stack of crates.
     */
    private val parts = arrayOf(
        floatArrayOf(-0.25f, 1.5f, -0.25f, 0.25f, 2f, 0.25f),
        floatArrayOf(-0.25f, 0.75f, -0.125f, 0.25f, 1.5f, 0.125f),
        floatArrayOf(0.25f, 0.75f, -0.125f, 0.5f, 1.5f, 0.125f),
        floatArrayOf(-0.5f, 0.75f, -0.125f, -0.25f, 1.5f, 0.125f),
        floatArrayOf(0f, 0f, -0.125f, 0.25f, 0.75f, 0.125f),
        floatArrayOf(-0.25f, 0f, -0.125f, 0f, 0.75f, 0.125f),
    )

    /** Where each part turns around: neck, waist, the two shoulders and the two hips. */
    private val pivots = arrayOf(
        floatArrayOf(0f, 1.5f, 0f),
        floatArrayOf(0f, 0.75f, 0f),
        floatArrayOf(0.375f, 1.4f, 0f),
        floatArrayOf(-0.375f, 1.4f, 0f),
        floatArrayOf(0.125f, 0.75f, 0f),
        floatArrayOf(-0.125f, 0.75f, 0f),
    )

    private val boxes = Array(parts.size) { index ->
        val p = parts[index]
        AABB(
            p[0].toDouble(), p[1].toDouble(), p[2].toDouble(),
            p[3].toDouble(), p[4].toDouble(), p[5].toDouble(),
        )
    }

    private val glowTexture by lazy(LazyThreadSafetyMode.NONE) {
        LiquidBounce.resource("particles/glow.png").readNativeImage().asTexture { "TotemGhost Glow" }
    }

    private class Ghost(
        val position: Vec3,
        val bodyYaw: Float,
        val headYaw: Float,
        val partPitch: FloatArray,
        val start: Long,
    )

    private val ghosts = ObjectArrayList<Ghost>()

    /**
     * Pops arrive on the network thread and the entity may only be read from the main one, so the
     * packet is parked and resolved on the next tick — the same handshake [ModuleTotemPopESP] does.
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
    private val worldChangeHandler = handler<WorldChangeEvent> {
        pendingPops.clear()
        ghosts.clear()
    }

    override fun onDisabled() {
        pendingPops.clear()
        ghosts.clear()
        super.onDisabled()
    }

    private fun drainPops() {
        var packet = pendingPops.poll()
        while (packet != null) {
            val victim = mc.level?.let(packet::getEntity)
            if (victim != null && (selfGhost || victim !== mc.player)) {
                spawn(victim)
            }
            packet = pendingPops.poll()
        }
    }

    private fun spawn(entity: Entity) {
        while (ghosts.size >= maxGhosts) {
            ghosts.removeAt(0)
        }

        val pitches = FloatArray(parts.size)
        val bodyYaw: Float
        var headYaw = 0f

        if (entity is LivingEntity) {
            bodyYaw = entity.yBodyRot
            headYaw = Mth.clamp(Mth.wrapDegrees(entity.yHeadRot - entity.yBodyRot), -75f, 75f)

            // The walk cycle is frozen where the victim left it, so the ghost keeps their stride.
            val limbAngle = entity.walkAnimation.position()
            val limbDistance = min(entity.walkAnimation.speed(), 1f)
            pitches[0] = entity.xRot
            pitches[2] = Math.toDegrees((cos(limbAngle * 0.6662f + Mth.PI) * 2f * limbDistance * 0.5f).toDouble())
                .toFloat()
            pitches[3] = Math.toDegrees((cos(limbAngle * 0.6662f) * 2f * limbDistance * 0.5f).toDouble())
                .toFloat()
            pitches[4] = Math.toDegrees((cos(limbAngle * 0.6662f) * 1.4f * limbDistance).toDouble())
                .toFloat()
            pitches[5] = Math.toDegrees((cos(limbAngle * 0.6662f + Mth.PI) * 1.4f * limbDistance).toDouble())
                .toFloat()
        } else {
            bodyYaw = entity.yRot
        }

        ghosts.add(Ghost(entity.position(), bodyYaw, headYaw, pitches, System.currentTimeMillis()))
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        drainPops()
        if (ghosts.isEmpty()) {
            return@handler
        }

        val now = System.currentTimeMillis()
        val durationMs = (duration * 1000f).toLong().coerceAtLeast(1L)
        val base = color

        renderEnvironmentForWorld(event.matrixStack) {
            val iterator = ghosts.iterator()
            while (iterator.hasNext()) {
                val ghost = iterator.next()
                val progress = (now - ghost.start).toFloat() / durationMs
                if (progress >= 1f) {
                    iterator.remove()
                    continue
                }
                drawGhost(ghost, progress, base)
            }
        }
    }

    private fun WorldRenderEnvironment.drawGhost(ghost: Ghost, progress: Float, base: Color4b) {
        // ease-out on the way up, a quick fade in and a long fade out
        val inverse = 1f - progress
        val ease = 1f - inverse * inverse * inverse
        val fadeIn = min(progress * 8f, 1f)
        val fadeOut = if (progress > 0.55f) 1f - (progress - 0.55f) / 0.45f else 1f
        val alpha = (fadeIn * fadeOut).coerceIn(0f, 1f)
        if (alpha <= 0.001f) {
            return
        }

        val faceColor = base.with(a = (base.a * fillAlpha * alpha).toInt().coerceIn(0, 255))
        val outlineColor = base.with(a = (base.a * outlineAlpha * alpha).toInt().coerceIn(0, 255))
        val origin = ghost.position.add(0.0, (ease * rise).toDouble(), 0.0)

        withPositionRelativeToCamera(origin) {
            poseStack.pushPose()
            poseStack.mulPose(Axis.YP.rotationDegrees(-ghost.bodyYaw + progress * spin))
            drawRig(ghost, faceColor, outlineColor)
            poseStack.popPose()

            if (glow) {
                poseStack.pushPose()
                poseStack.translate(0f, 1.1f, 0f)
                drawSprite(1.5f, base.with(a = (110f * alpha).toInt().coerceIn(0, 255)).argb)
                drawSprite(0.7f, base.with(a = (160f * alpha).toInt().coerceIn(0, 255)).argb)
                poseStack.popPose()
            }
        }
    }

    /** The six posed boxes, each turned around its own pivot before it is drawn. */
    private fun WorldRenderEnvironment.drawRig(ghost: Ghost, faceColor: Color4b, outlineColor: Color4b) {
        for (index in parts.indices) {
            val pitch = ghost.partPitch[index]
            val yaw = if (index == 0) ghost.headYaw else 0f
            val pivot = pivots[index]

            poseStack.pushPose()
            poseStack.translate(pivot[0], pivot[1], pivot[2])
            if (yaw != 0f) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-yaw))
            }
            if (pitch != 0f) {
                poseStack.mulPose(Axis.XP.rotationDegrees(pitch))
            }
            poseStack.translate(-pivot[0], -pivot[1], -pivot[2])
            drawBox(boxes[index], faceColor, outlineColor)
            poseStack.popPose()
        }
    }

    /** Camera-facing additive glow sprite of full size [size] centred on the current origin. */
    private fun WorldRenderEnvironment.drawSprite(size: Float, argb: Int) {
        val half = size * 0.5f
        poseStack.pushPose()
        poseStack.mulPose(camera.rotation())
        drawCustomMeshTextured(glowTexture, ClientRenderPipelines.GlowTexQuads) { pose ->
            addVertex(pose, -half, -half, 0f).setUv(0f, 1f).setColor(argb)
            addVertex(pose, half, -half, 0f).setUv(1f, 1f).setColor(argb)
            addVertex(pose, half, half, 0f).setUv(1f, 0f).setColor(argb)
            addVertex(pose, -half, half, 0f).setUv(0f, 0f).setColor(argb)
        }
        poseStack.popPose()
    }
}
