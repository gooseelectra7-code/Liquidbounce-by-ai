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

import net.ccbluex.fastutil.enumSetOf
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawSquareTexture
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.block.collisionShape
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.shouldBeShown
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.math.copy
import net.ccbluex.liquidbounce.utils.math.fma
import net.ccbluex.liquidbounce.utils.math.toBlockPos
import net.ccbluex.liquidbounce.utils.raytracing.hasLineOfSight
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.util.Mth
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.max

/**
 * Particles
 *
 * Displays particles when attacking an entity.
 *
 * @author sqlerrorthing
 */
object ModuleParticles : ClientModule("Particles", category = ModuleCategories.RENDER) {

    private val particleSize by float("Size", 1f, 0.5f..2f)
    private val count by intRange("Count", 2..10, 2..30, "particles")
    private val rotate by boolean("RandomParticleRotation", true)
    private class Physical : ValueGroup("Physical") {
        val motion by float("Motion", 15f, 1f..30f)
        val bounceX by float("BounceX", 0.8f, 0.0f..1.0f)
        val bounceY by float("BounceY", 0.6f, 0.0f..1.0f)
        val bounceZ by float("BounceZ", 0.8f, 0.0f..1.0f)
        val drag by float("Drag", 0.99f, 0.0f..1.0f)
        val gravityFactor by float("GravityFactor", 0.8f, 0.0f..1f)
    }

    private val physicalSettings = Physical()
    init {
        tree(physicalSettings)
    }

    private val color by color("Color", Color4b.RED)
    private val builtinParticles by multiEnumChoice("Particle", enumSetOf(BuiltinParticle.STAR), canBeNone = false)
    private val bloomHalo by boolean("BloomHalo", true)

    private class IdleSpawn : ValueGroup("IdleSpawn") {
        val enabled by boolean("Enabled", false)
        val amount by intRange("Amount", 1..2, 1..10, "particles")
        val radius by float("Radius", 8f, 4f..32f, "blocks")
        val intervalMs by int("IntervalMs", 120, 40..1000, "ms")
    }

    private class MovementSpawn : ValueGroup("MovementSpawn") {
        val enabled by boolean("Enabled", false)
        val amount by intRange("Amount", 1..3, 1..10, "particles")
        val intervalMs by int("IntervalMs", 80, 40..1000, "ms")
    }

    private class ProjectileSpawn : ValueGroup("ProjectileSpawn") {
        val enabled by boolean("Enabled", false)
        val amount by intRange("Amount", 1..2, 1..8, "particles")
        val intervalMs by int("IntervalMs", 60, 30..500, "ms")
        val spread by float("Spread", 0.25f, 0.05f..1f, "blocks")
        val maxDistance by float("MaxDistance", 24f, 4f..64f, "blocks")
    }

    private val idleSpawnSettings = IdleSpawn()
    private val movementSpawnSettings = MovementSpawn()
    private val projectileSpawnSettings = ProjectileSpawn()
    init {
        tree(idleSpawnSettings)
        tree(movementSpawnSettings)
        tree(projectileSpawnSettings)
    }

    private val particles = mutableListOf<Particle>()
    private val chronometer = Chronometer()
    private val idleChronometer = Chronometer()
    private val movementChronometer = Chronometer()
    private val projectileChronometer = Chronometer()

    private val gravity: Double
        get() = physicalSettings.gravityFactor.toDouble() * 0.03125

    override fun onDisabled() {
        particles.clear()
        super.onDisabled()
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldChangeEvent> {
        particles.clear()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val cameraPos = mc.gameRenderer.mainCamera.position()
        particles.removeIf { particle ->
            if (particle.alpha <= 0 || cameraPos.distanceToSqr(particle.pos) > 30 * 30) {
                true
            } else {
                particle.update(cameraPos)
                false
            }
        }

        // Ported from hawen Particles: idle + movement ambient spawn triggers.
        val playerPos = player.position()
        val movedSq = player.deltaMovement.lengthSqr()

        if (movementSpawnSettings.enabled
            && movedSq > 0.0025
            && movementChronometer.hasElapsed(movementSpawnSettings.intervalMs.toLong())) {
            movementChronometer.reset()
            repeat(movementSpawnSettings.amount.random()) {
                val offset = Vec3(
                    (-0.45..0.45).random(),
                    (0.0..player.bbHeight.toDouble()).random(),
                    (-0.45..0.45).random()
                )
                particles.add(Particle(playerPos.add(offset), builtinParticles.random()))
            }
        }

        if (idleSpawnSettings.enabled
            && idleChronometer.hasElapsed(idleSpawnSettings.intervalMs.toLong())) {
            idleChronometer.reset()
            val r = idleSpawnSettings.radius.toDouble()
            repeat(idleSpawnSettings.amount.random()) {
                val offset = Vec3(
                    (-r..r).random(),
                    (player.bbHeight.toDouble() * 0.5..r).random(),
                    (-r..r).random()
                )
                particles.add(Particle(playerPos.add(offset), builtinParticles.random()))
            }
        }

        // Ported from hawen Particles "Бросок" trigger — trail particles on moving projectiles
        // (arrows, tridents, ender pearls). Stuck/grounded arrows are skipped.
        if (projectileSpawnSettings.enabled
            && projectileChronometer.hasElapsed(projectileSpawnSettings.intervalMs.toLong())) {
            projectileChronometer.reset()
            val spread = projectileSpawnSettings.spread.toDouble()
            val maxDistSq = projectileSpawnSettings.maxDistance.let { it * it }.toDouble()
            for (entity in world.entitiesForRendering()) {
                if (!entity.isTrailableProjectile(playerPos, maxDistSq)) continue

                val basePos = entity.position()
                repeat(projectileSpawnSettings.amount.random()) {
                    val offset = Vec3(
                        (-spread..spread).random(),
                        (-spread..spread).random(),
                        (-spread..spread).random(),
                    )
                    particles.add(Particle(basePos.add(offset), builtinParticles.random()))
                }
            }
        }
    }

    @Suppress("unused")
    private val attackEvent = handler<AttackEntityEvent> { event ->
        if (!event.entity.shouldBeShown() || !chronometer.hasElapsed(230)) {
            return@handler
        }

        chronometer.reset()

        val directionVector = (RotationManager.currentRotation ?: player.rotation).directionVector
        val pos = player.eyePosition.fma(player.distanceTo(event.entity).toDouble(), directionVector)

        repeat(count.random()) {
            particles.add(Particle(pos, builtinParticles.random()))
        }
    }

    @Suppress("unused")
    private val displayHandler = handler<WorldRenderEvent> { event ->
        renderEnvironmentForWorld(event.matrixStack) {
            for (particle in particles) {
                if (!particle.visible) continue

                particle.render(event.partialTicks)
            }
        }
    }

    private fun net.minecraft.world.entity.Entity.isTrailableProjectile(
        playerPos: Vec3,
        maxDistSq: Double,
    ): Boolean {
        if (this !is AbstractArrow && this !is ThrownEnderpearl) return false
        if (this is AbstractArrow && this.isInGround) return false
        if (this.deltaMovement.lengthSqr() < 0.0025) return false
        return playerPos.distanceToSqr(this.position()) <= maxDistSq
    }

    @Suppress("UNUSED")
    private enum class BuiltinParticle(
        override val tag: String,
        fileName: String,
    ) : Tagged {
        /**
         * Original: IDK (first: https://github.com/CCBlueX/LiquidBounce/pull/4976)
         */
        ORBIZ("Orbiz", "glow"),

        /**
         * Original: https://www.svgrepo.com/svg/528677/stars-minimalistic
         * Modified: @sqlerrorthing
         */
        STAR("Star", "star"),

        /**
         * Original: https://www.svgrepo.com/svg/487288/dollar?edit=true
         * Modified: @sqlerrorthing
         */
        DOLLAR("Dollar", "dollar"),

        CROWN("Crown", "crown"),
        HEART("Heart", "heart"),
        LIGHTNING("Lightning", "lightning"),
        LINE("Line", "line"),
        POINT("Point", "point"),
        RHOMBUS("Rhombus", "rhombus"),
        SNOWFLAKE("Snowflake", "snowflake"),
        SPARK("Spark", "spark"),

        /**
         * Ported from hawen-output (Stars2 / "Звездачка 2") — a softer, sharper-tipped star variant.
         */
        STARNEW("Stars2", "starnew");

        val image = LiquidBounce.resource("particles/$fileName.png").readNativeImage()
        val texture = this.image.asTexture { "Builtin Particle $tag" }
    }

    private class Particle(var pos: Vec3, val builtinParticle: BuiltinParticle) {
        private var prevPos = pos
        private var velocity = Vec3(
            (-0.01..0.01).random(),
            (0.01..0.02).random(),
            (-0.01..0.01).random()
        )
        @JvmField var alpha = 1f
        @JvmField var visible = true
        private val rotation = (0f..360f).random()
        private val spawnTime = System.currentTimeMillis()
        private var collisionTime = -1L

        fun update(cameraPos: Vec3) {
            prevPos = pos

            if (collisionTime != -1L) {
                val timeSinceCollision = System.currentTimeMillis() - collisionTime
                alpha = max(0f, 1f - (timeSinceCollision / 3000f))
            }

            val speedMultiplier = physicalSettings.motion.toDouble()
            velocity = velocity.add(0.0, -gravity, 0.0)
            var nextPos = pos.add(velocity.multiply(speedMultiplier, 1.0, speedMultiplier))

            if (!nextPos.toBlockPos().collisionShape.isEmpty) {
                if (collisionTime == -1L) {
                    collisionTime = System.currentTimeMillis()
                }

                when {
                    !pos.toBlockPos(xOffset = velocity.x * speedMultiplier).collisionShape.isEmpty -> {
                        velocity = velocity.copy(x = -velocity.x * physicalSettings.bounceX)
                    }
                    !pos.toBlockPos(yOffset = velocity.y).collisionShape.isEmpty -> {
                        velocity = velocity.copy(
                            x = velocity.x * physicalSettings.drag,
                            y = -velocity.y * physicalSettings.bounceY,
                            z = velocity.z * physicalSettings.drag
                        )
                    }
                    !pos.toBlockPos(zOffset = velocity.z * speedMultiplier).collisionShape.isEmpty -> {
                        velocity = velocity.copy(z = -velocity.z * physicalSettings.bounceZ)
                    }
                }

                nextPos = pos.add(velocity.multiply(speedMultiplier, 1.0, speedMultiplier))
            }

            pos = nextPos
            visible = hasLineOfSight(cameraPos, pos)
        }

        context(env: WorldRenderEnvironment)
        fun render(partialTicks: Float) {
            val interpPos = prevPos.lerp(pos, partialTicks.toDouble())
            env.withPositionRelativeToCamera(interpPos) {
                val size = particleSize * 0.25f * (1 - (System.currentTimeMillis() - spawnTime) / 12000f)
                if (size <= 0f) return@withPositionRelativeToCamera

                val rotation = if (rotate) {
                    (rotation + 90f) % 360f
                } else {
                    90f
                }

                val renderColor = color.alpha(
                    Mth.clamp(
                        (alpha * color.a.toFloat()).toInt(),
                        0, color.a
                    )
                )

                if (bloomHalo) {
                    val haloSize = size * 2.4f
                    val haloAlpha = (renderColor.a * 0.22f).toInt().coerceIn(0, renderColor.a)
                    poseStack.pushPose()
                    with(poseStack) {
                        translate(-haloSize / 2.0, -haloSize / 2.0, 0.0)
                        mulPose(mc.gameRenderer.mainCamera.rotation())
                        scale(-1.0f, 1.0f, -1.0f)
                        mulPose(Quaternionf().fromAxisAngleDeg(0.0f, 0.0f, 1.0f, rotation))
                        translate(haloSize / 2.0, haloSize / 2.0, 0.0)
                    }
                    drawSquareTexture(
                        BuiltinParticle.ORBIZ.texture,
                        haloSize,
                        renderColor.alpha(haloAlpha).argb
                    )
                    poseStack.popPose()
                }

                with(poseStack) {
                    translate(-size / 2.0, -size / 2.0, 0.0)
                    mulPose(mc.gameRenderer.mainCamera.rotation())
                    scale(-1.0f, 1.0f, -1.0f)
                    mulPose(Quaternionf().fromAxisAngleDeg(0.0f, 0.0f, 1.0f, rotation))
                    translate(size / 2.0, size / 2.0, 0.0)
                }

                drawSquareTexture(builtinParticle.texture, size, renderColor.argb)
            }
        }
    }

}
