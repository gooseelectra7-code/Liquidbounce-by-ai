/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.ccbluex.liquidbounce.features.module.modules.render.killeffect

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawSquareTexture
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Glowing model-like fragments, following Nursultan's count, fade-in and lifetime controls. */
object KillEffectParticlesMode : Mode("Particles"), KillEffectMode, MinecraftShortcuts {

    override val parent: ModeValueGroup<*>
        get() = ModuleKillEffect.modes

    private val behindWalls by boolean("BehindWalls", false)
    private val color by color("Color", Color4b.WHITE)
    private val density by int("Density", 300, 50..500, "particles")
    private val duration by intRange("Duration", 4..6, 1..12, "s")
    private val size by float("Size", 1F, 0.25F..3F)
    private val speed by float("Speed", 1F, 0.1F..3F)
    private val spread by float("Spread", 1F, 0.2F..3F)
    private val hold by int("Hold", 0, 0..30, "ticks")
    private val gravity by float("Gravity", 0F, 0F..0.006F)

    private data class Spark(
        var previous: Vec3,
        var position: Vec3,
        var velocity: Vec3,
        val drift: Vec3,
        val size: Float,
        val lifetimeTicks: Int,
        val holdTicks: Int,
        var ageTicks: Int = 0,
    )
    private data class SpawnBox(val x: Double, val y: Double, val z: Double, val w: Double, val h: Double, val d: Double)

    private val sparks = ArrayList<Spark>()
    private const val MAX_ACTIVE_SPARKS = 5_000

    private val glowTexture by lazy(LazyThreadSafetyMode.NONE) {
        LiquidBounce.resource("particles/glow.png").readNativeImage().asTexture { "Kill Effect glow" }
    }

    override fun onKill(entity: Entity) {
        val random = Random.Default
        val center = entity.position().add(0.0, entity.bbHeight * 0.55, 0.0)
        repeat(density) {
            val position = spawnPosition(entity, random)
            val outward = position.subtract(center).normalize()
            val initialVelocity = Vec3(
                random.nextDouble(-0.035, 0.035),
                random.nextDouble(0.025, 0.075),
                random.nextDouble(-0.035, 0.035),
            ).add(outward.scale(0.018)).scale(spread.toDouble())
            sparks += Spark(
                previous = position,
                position = position,
                velocity = initialVelocity,
                drift = outward.scale(0.018 * spread).add(randomDirection(random).scale(0.012 * spread))
                    .add(0.0, 0.012 * spread, 0.0),
                size = random.nextFloat() * 0.075F + 0.035F,
                lifetimeTicks = random.nextInt(duration.first * 20, duration.last * 20 + 1),
                holdTicks = hold + random.nextInt(0, 4),
            )
        }
        if (sparks.size > MAX_ACTIVE_SPARKS) {
            sparks.subList(0, sparks.size - MAX_ACTIVE_SPARKS).clear()
        }
    }

    override fun reset() {
        sparks.clear()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!ModuleKillEffect.running || ModuleKillEffect.modes.activeMode !== this) {
            sparks.clear()
            return@handler
        }

        val iterator = sparks.iterator()
        while (iterator.hasNext()) {
            val spark = iterator.next()
            spark.ageTicks++
            if (spark.ageTicks >= spark.lifetimeTicks) {
                iterator.remove()
                continue
            }

            spark.previous = spark.position
            if (spark.ageTicks > spark.holdTicks) {
                val driftBlend = ((spark.ageTicks - spark.holdTicks) / 15.0).coerceIn(0.0, 1.0)
                spark.velocity = spark.velocity.scale(0.972)
                    .add(spark.drift.scale(0.028 * driftBlend))
                    .add(0.0, -gravity.toDouble(), 0.0)
                spark.position = spark.position.add(spark.velocity.scale(speed.toDouble()))
            }
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { reset() }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!ModuleKillEffect.running || ModuleKillEffect.modes.activeMode !== this) return@handler
        if (sparks.isEmpty()) return@handler
        val camera = mc.gameRenderer.mainCamera
        val cameraPos = camera.position()
        val pipeline = if (behindWalls) ClientRenderPipelines.TexQuads else ClientRenderPipelines.TexQuadsDepthTest
        renderEnvironmentForWorld(event.matrixStack) {
            for (spark in sparks) {
                val position = spark.previous.lerp(spark.position, event.partialTicks.toDouble())
                if (cameraPos.distanceToSqr(position) > 64.0 * 64.0) continue

                val age = spark.ageTicks + event.partialTicks
                val fadeIn = (age / 8F).coerceIn(0F, 1F)
                val fadeOut = ((spark.lifetimeTicks - age) / 18F).coerceIn(0F, 1F)
                val alpha = min(fadeIn, fadeOut)
                if (alpha <= 0F) continue
                val tint = color.alpha((color.a * alpha * 0.68F).toInt().coerceIn(0, 255))
                val renderedSize = spark.size * size * (0.92F + 0.08F * fadeIn)

                withPositionRelativeToCamera(position) {
                    drawBillboard(renderedSize * 2.1F, tint.alpha((tint.a * 0.25F).toInt()), pipeline)
                    drawBillboard(renderedSize, tint, pipeline)
                }
            }
        }
    }

    private fun WorldRenderEnvironment.drawBillboard(size: Float, tint: Color4b, pipeline: com.mojang.blaze3d.pipeline.RenderPipeline) {
        poseStack.pushPose()
        poseStack.mulPose(mc.gameRenderer.mainCamera.rotation())
        poseStack.translate(size / 2.0, size / 2.0, 0.0)
        drawSquareTexture(glowTexture, size, tint.argb, pipeline)
        poseStack.popPose()
    }

    private fun randomDirection(random: Random): Vec3 = Vec3(
        random.nextDouble(-0.5, 0.5),
        random.nextDouble(-0.5, 0.5),
        random.nextDouble(-0.5, 0.5),
    ).normalize()

    private fun spawnPosition(entity: Entity, random: Random): Vec3 {
        val width = entity.bbWidth.toDouble().coerceAtLeast(0.55)
        val height = entity.bbHeight.toDouble().coerceAtLeast(0.6)
        val section = random.nextInt(100)
        val (cx, cy, cz, sx, sy, sz) = if (height / width > 2.2) {
            when (section) {
                in 0..17 -> SpawnBox(0.0, height * 0.88, 0.0, width * 0.85, height * 0.18, width * 0.85)
                in 18..47 -> SpawnBox(0.0, height * 0.58, 0.0, width * 0.82, height * 0.38, width * 0.48)
                in 48..60 -> SpawnBox(-width * 0.58, height * 0.55, 0.0, width * 0.22, height * 0.5, width * 0.22)
                in 61..73 -> SpawnBox(width * 0.58, height * 0.55, 0.0, width * 0.22, height * 0.5, width * 0.22)
                in 74..86 -> SpawnBox(-width * 0.22, height * 0.2, 0.0, width * 0.26, height * 0.4, width * 0.24)
                else -> SpawnBox(width * 0.22, height * 0.2, 0.0, width * 0.26, height * 0.4, width * 0.24)
            }
        } else {
            SpawnBox(0.0, height * 0.48, 0.0, width * 1.15, height * 0.42, width * 1.4)
        }
        val halfX = sx * 0.5
        val halfY = sy * 0.5
        val halfZ = sz * 0.5
        var x = cx + random.nextDouble(-halfX, halfX)
        var y = cy + random.nextDouble(-halfY, halfY)
        var z = cz + random.nextDouble(-halfZ, halfZ)
        if (random.nextDouble() < 0.72) {
            when (random.nextInt(6)) {
                0 -> x = cx - halfX
                1 -> x = cx + halfX
                2 -> y = cy - halfY
                3 -> y = cy + halfY
                4 -> z = cz - halfZ
                else -> z = cz + halfZ
            }
        }
        val yaw = Math.toRadians(-entity.yRot.toDouble())
        return Vec3(
            entity.x + x * cos(yaw) - z * sin(yaw),
            entity.y + y,
            entity.z + x * sin(yaw) + z * cos(yaw),
        )
    }
}
