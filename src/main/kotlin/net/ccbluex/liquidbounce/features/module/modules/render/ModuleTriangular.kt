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

import com.mojang.blaze3d.pipeline.RenderPipeline
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.scaledDimension
import net.ccbluex.liquidbounce.utils.entity.RenderedEntities
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.math.toRadians
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.CameraType
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Triangular
 *
 * Draws a ring of arrows around the crosshair, each one pointing at a nearby entity. Unlike
 * [ModuleRadar] the ring is not a compass: the angle is the entity's bearing *in view space*, so an
 * arrow sits where the entity actually is relative to where the camera looks, and arrows spread
 * further apart near the screen diagonals.
 *
 * Ported from the veganew client. Its health indicator masked the arrow with the stencil buffer;
 * the GUI render-state pipeline has no stencil, so the same split is drawn as two texture quads
 * cut at the health fraction, which is visually identical and cheaper.
 */
object ModuleTriangular : ClientModule("Triangular", ModuleCategories.RENDER) {

    private val players by boolean("Players", true)
    private val friends by boolean("Friends", true)
    private val mobs by boolean("Mobs", true)

    private val style by enumChoice("Style", Style.CLASSIC)

    private val playerColor by color("PlayerColor", Color4b(255, 10, 70, 255))
    private val friendColor by color("FriendColor", Color4b(70, 255, 70, 255))
    private val mobColor by color("MobColor", Color4b(20, 255, 255, 255))

    private object HealthWobble : ToggleableValueGroup(this, "HealthWobble", true) {
        val damagedColor by color("DamagedColor", Color4b.WHITE)
    }

    private val scale by float("Scale", 0.6f, 0.1f..1.5f)
    private val range by float("Range", 0.75f, 0f..2f)

    /** Arrows are pushed outwards while a container is open so they clear the inventory window. */
    private val spreadInContainers by boolean("SpreadInContainers", true)

    init {
        tree(HealthWobble)
    }

    enum class Style(override val tag: String, val path: String, val additive: Boolean) : Tagged {
        CLASSIC("Classic", "textures/triangular/triangle_classic.png", true),
        MODERN("Modern", "textures/triangular/triangle_modern.png", false);

        val texture: AbstractTexture by lazy(LazyThreadSafetyMode.NONE) {
            LiquidBounce.resource(this.path).readNativeImage().asTexture { "Triangular $tag" }
        }
    }

    /** Smoothed camera yaw, so the ring does not jitter with every mouse twitch. */
    private var smoothedYaw = Float.NaN

    /** Container spread, eased between 0 and 1 rather than snapping open. */
    private var containerSpread = 0f

    private var lastFrameNanos = 0L

    override fun onEnabled() {
        smoothedYaw = Float.NaN
        containerSpread = 0f
        lastFrameNanos = 0L
        RenderedEntities.subscribe(this)
        super.onEnabled()
    }

    override fun onDisabled() {
        RenderedEntities.unsubscribe(this)
        super.onDisabled()
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        // The ring is anchored to the crosshair, which only means anything in first person.
        if (mc.options.cameraType != CameraType.FIRST_PERSON) {
            return@handler
        }

        val deltaSeconds = frameDeltaSeconds()
        val yaw = updateSmoothedYaw(event.tickDelta, deltaSeconds)
        updateContainerSpread(deltaSeconds)

        val texture = style.texture
        val pipeline = if (style.additive) {
            ClientRenderPipelines.GUI.TexQuadAdditiveNoCull
        } else {
            ClientRenderPipelines.GUI.TexQuadNoCull
        }

        val (width, height) = mc.window.scaledDimension
        val playerPos = player.interpolateCurrentPosition(event.tickDelta)
        val target = ModuleKillAura.targetTracker.target

        // 11 px per unit of scale and 10..50 px of ring radius are the veganew constants.
        val arrowSize = BASE_ARROW_PIXELS * scale
        val minRadius = MIN_RING_RADIUS + RING_RADIUS_RANGE * range

        with(event.context) {
            pose().withPush {
                translate(width * 0.5f, height * 0.5f)

                for (entity in RenderedEntities) {
                    if (entity === player) {
                        continue
                    }

                    val color = colorFor(entity) ?: continue
                    val entityPos = entity.interpolateCurrentPosition(event.tickDelta)

                    // Rotate the horizontal offset into view space; atan2 of that is the on-screen
                    // bearing, with 0 straight ahead.
                    val yawRad = yaw.toRadians()
                    val cosYaw = cos(yawRad)
                    val sinYaw = -sin(yawRad)
                    val dx = entityPos.x - playerPos.x
                    val dz = entityPos.z - playerPos.z
                    val viewY = -dz * cosYaw - dx * sinYaw
                    val viewX = -dx * cosYaw + dz * sinYaw
                    val angleRad = atan2(viewY, viewX).toFloat()

                    val isTarget = entity === target
                    val radius = minRadius +
                        (if (isTarget) minRadius / 3f else 0f) +
                        spreadFor(angleRad) * containerSpread

                    // Arrows grow in over the first 14 ticks of an entity's life so entities
                    // streaming into render range do not pop.
                    val appear = ((entity.tickCount - 1 + event.tickDelta) / APPEAR_TICKS).coerceIn(0f, 1f)
                    val half = arrowSize * appear * (if (isTarget) TARGET_SCALE else 1f)
                    if (half <= 0.25f) {
                        continue
                    }

                    withPush {
                        translate(radius * cos(angleRad), radius * sin(angleRad))
                        rotate(angleRad)
                        with(this@with) {
                            drawArrow(texture, pipeline, half, entity, color)
                        }
                    }
                }
            }
        }
    }

    /**
     * Draws the arrow, split into a healthy and a damaged half when [HealthWobble] is on.
     *
     * The split runs along the arrow's local X axis; [uSplit] is where it lands in texture space so
     * both halves sample the correct part of the sprite.
     */
    private fun GuiGraphicsExtractor.drawArrow(
        texture: AbstractTexture,
        pipeline: RenderPipeline,
        half: Float,
        entity: LivingEntity,
        color: Color4b,
    ) {
        val setup = texture.textureSetup
        val healthFraction = healthFraction(entity)

        if (!HealthWobble.enabled || healthFraction >= 1f) {
            drawTexQuad(setup, -half, -half, half, half, argb = color.argb, pipeline = pipeline)
            return
        }

        // veganew masked x in [-half, 0.9 * half * (2 * hp - 0.8)]; reproduced verbatim so the bar
        // still reads full slightly before the entity is actually at full health.
        val splitX = (WOBBLE_INSET * half * (2f * healthFraction - WOBBLE_BIAS)).coerceIn(-half, half)
        val uSplit = (splitX + half) / (2f * half)

        if (uSplit > 0f) {
            drawTexQuad(
                setup, -half, -half, splitX, half,
                u1 = 0f, v1 = 0f, u2 = uSplit, v2 = 1f,
                argb = color.argb, pipeline = pipeline,
            )
        }

        if (uSplit < 1f) {
            drawTexQuad(
                setup, splitX, -half, half, half,
                u1 = uSplit, v1 = 0f, u2 = 1f, v2 = 1f,
                argb = HealthWobble.damagedColor.argb, pipeline = pipeline,
            )
        }
    }

    private fun colorFor(entity: LivingEntity): Color4b? = when {
        entity is Player && FriendManager.isFriend(entity) -> friendColor.takeIf { friends }
        entity is Player -> playerColor.takeIf { players }
        else -> mobColor.takeIf { mobs }
    }

    private fun healthFraction(entity: LivingEntity): Float {
        val absorption = entity.absorptionAmount / 4f
        val max = entity.maxHealth + absorption
        if (max <= 0f) {
            return 1f
        }

        return ((entity.getActualHealth() + 0.01f + absorption) / max).coerceIn(0f, 1f)
    }

    /**
     * Extra radius applied while a container is open. Arrows near the screen diagonals are pushed
     * out further than those near the axes, which keeps the ring roughly rectangular around the
     * inventory window instead of circular.
     */
    private fun spreadFor(angleRad: Float): Float {
        val degrees = Math.toDegrees(angleRad.toDouble()).toFloat()
        var diagonal = abs(degrees) % 90f / 90f
        diagonal = (if (diagonal > 0.5f) 1f - diagonal else diagonal) * 2f
        // easeOutCubic, then pulled back slightly so the axes are not fully flattened.
        val inv = 1f - diagonal
        diagonal = 1f - (1f - inv * inv * inv) * (1f - diagonal * 0.25f)

        val minRadius = MIN_RING_RADIUS + RING_RADIUS_RANGE * range
        return SPREAD_BASE + diagonal * SPREAD_DIAGONAL + (SPREAD_REFERENCE_RADIUS - minRadius)
    }

    private fun updateSmoothedYaw(tickDelta: Float, deltaSeconds: Float): Float {
        val actual = player.getYRot(tickDelta)

        if (smoothedYaw.isNaN()) {
            smoothedYaw = actual
            return actual
        }

        val difference = Mth.degreesDifference(smoothedYaw, actual)

        // A near-180° flip means the player spun around; easing through it would sweep every arrow
        // across the screen, so snap instead.
        if (abs(difference) >= YAW_SNAP_DEGREES) {
            smoothedYaw = actual
            return actual
        }

        smoothedYaw = Mth.wrapDegrees(smoothedYaw + difference * smoothingFactor(YAW_SMOOTHING_TAU, deltaSeconds))
        return smoothedYaw
    }

    private fun updateContainerSpread(deltaSeconds: Float) {
        val target = if (spreadInContainers && mc.screen is AbstractContainerScreen<*>) 1f else 0f
        containerSpread += (target - containerSpread) * smoothingFactor(CONTAINER_SPREAD_TAU, deltaSeconds)
    }

    /**
     * Frame-rate independent exponential smoothing weight. Without this the ring would ease at a
     * speed proportional to the framerate, which is what the original did.
     */
    private fun smoothingFactor(tauSeconds: Float, deltaSeconds: Float): Float =
        1f - exp(-deltaSeconds / tauSeconds)

    private fun frameDeltaSeconds(): Float {
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now

        if (previous == 0L) {
            return 0f
        }

        return ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
    }

    private const val BASE_ARROW_PIXELS = 11f
    private const val MIN_RING_RADIUS = 10f
    private const val RING_RADIUS_RANGE = 20f
    private const val APPEAR_TICKS = 14f
    private const val TARGET_SCALE = 1.25f
    private const val WOBBLE_INSET = 0.9f
    private const val WOBBLE_BIAS = 0.8f
    private const val SPREAD_BASE = 65f
    private const val SPREAD_DIAGONAL = 32.5f
    private const val SPREAD_REFERENCE_RADIUS = 30f
    private const val YAW_SNAP_DEGREES = 180f
    private const val YAW_SMOOTHING_TAU = 0.045f
    private const val CONTAINER_SPREAD_TAU = 0.09f
    private const val MAX_FRAME_DELTA_SECONDS = 0.1f

}
