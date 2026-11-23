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
package net.ccbluex.liquidbounce.features.module.modules.render.dashtrail

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A single smear of the trail.
 *
 * Cubics do not follow their entity: they inherit a slice of its motion at spawn and then coast,
 * shedding speed every tick, which is what makes the trail lag behind and fan out. Vertical motion
 * is damped harder going up than down so a jump leaves an arc instead of a column.
 */
class DashCubic(
    entity: LivingEntity,
    val sprite: DashSpriteSource,
    val color: Int,
    val lifetimeMs: Int,
    /** Where in the tick this cubic was "emitted", so a burst spreads along the path travelled. */
    offsetInTick: Float,
    private val withSparks: Boolean,
    random: Random,
) : MinecraftShortcuts {

    private companion object {
        /** Fraction of the entity's per-tick motion a fresh cubic keeps. */
        const val MOTION_INHERITANCE = 0.04

        /** Speed decay per tick. */
        const val DRAG = 1.05

        /** How far a cubic coasts per tick, in units of its own motion. */
        const val COAST = 5.0

        /** Extra vertical damping while rising. */
        const val RISE_DAMPING = 3.5

        const val HORIZONTAL_JITTER = 0.175
        const val FADE_IN_MS = 220f
        const val FADE_OUT_MS = 260f

        /** Below this the cubic is treated as standing still and given a random facing. */
        const val STILL_THRESHOLD = 5.0E-4
    }

    /** The entity id is kept, not the entity: a cubic must not pin a dead entity in memory. */
    val ownerId: Int = entity.id

    var x: Double
    var y: Double
    var z: Double
    var prevX: Double
    var prevY: Double
    var prevZ: Double

    var motionX: Double
    var motionY: Double
    var motionZ: Double

    /** Yaw and pitch the sprite is drawn at, in degrees. Recomputed as the motion changes. */
    var yaw: Float = 0f
        private set
    var pitch: Float = 0f
        private set

    private val bornAt: Long = System.currentTimeMillis()

    val sparks: ObjectArrayList<DashSpark> = ObjectArrayList()

    init {
        val entityMotionX = entity.x - entity.xOld
        val entityMotionY = entity.y - entity.yOld
        val entityMotionZ = entity.z - entity.zOld

        val height = entity.bbHeight.toDouble()
        x = entity.xOld - entityMotionX * offsetInTick +
            (-HORIZONTAL_JITTER / 2.0 + HORIZONTAL_JITTER * random.nextDouble())
        y = entity.yOld - entityMotionY * offsetInTick + (height / 3.0 + height / 4.0 * random.nextDouble() * 0.7)
        z = entity.zOld - entityMotionZ * offsetInTick +
            (-HORIZONTAL_JITTER / 2.0 + HORIZONTAL_JITTER * random.nextDouble())

        prevX = x
        prevY = y
        prevZ = z

        motionX = entityMotionX * MOTION_INHERITANCE
        motionY = entityMotionY * MOTION_INHERITANCE
        motionZ = entityMotionZ * MOTION_INHERITANCE

        updateFacing(entity, random)
    }

    /** 0 at spawn, 1 once the cubic has lived out [lifetimeMs]. */
    fun progress(now: Long): Float = ((now - bornAt).toFloat() / lifetimeMs).coerceIn(0f, 1f)

    /** Fades in on spawn and back out once the lifetime is spent. */
    fun alpha(now: Long): Float {
        val age = (now - bornAt).toFloat()
        val fadeIn = (age / FADE_IN_MS).coerceIn(0f, 1f)
        val remaining = lifetimeMs - age
        val fadeOut = (remaining / FADE_OUT_MS).coerceIn(0f, 1f)
        return fadeIn * fadeOut
    }

    fun isDead(now: Long): Boolean = now - bornAt >= lifetimeMs + FADE_OUT_MS

    /**
     * @param inheritFrom the next cubic of the same entity, whose motion this one adopts when
     *   MotionsSmoothing is on. Chaining the motion forward like this is what makes a trail read as
     *   one continuous ribbon rather than a scatter of independent puffs.
     */
    fun tick(owner: LivingEntity?, inheritFrom: DashCubic?, random: Random) {
        prevX = x
        prevY = y
        prevZ = z

        motionX = (inheritFrom?.motionX ?: motionX) / DRAG
        motionY = (inheritFrom?.motionY ?: motionY) / DRAG
        motionZ = (inheritFrom?.motionZ ?: motionZ) / DRAG

        x += COAST * motionX
        y += COAST * motionY / (if (motionY < 0.0) 1.0 else RISE_DAMPING)
        z += COAST * motionZ

        updateFacing(owner, random)

        if (withSparks) {
            sparks.removeIf(DashSpark::isDead)
            sparks.forEach(DashSpark::tick)
        } else if (!sparks.isEmpty) {
            sparks.clear()
        }
    }

    fun addSpark(random: Random, sizeScale: Float) {
        sparks.add(DashSpark(random, sizeScale))
    }

    /**
     * Points the sprite along the direction of travel, skewed away from where the entity is facing
     * so the smear reads as motion blur rather than a decal. A cubic that has come to rest gets a
     * random roll instead, since there is no direction left to point at.
     */
    private fun updateFacing(owner: LivingEntity?, random: Random) {
        val speed = sqrt(motionX * motionX + motionZ * motionZ)

        if (speed < STILL_THRESHOLD || owner == null) {
            yaw = random.nextFloat() * 360f
            pitch = mc.gameRenderer.mainCamera.xRot()
            return
        }

        val motionYaw = motionYawDegrees()
        yaw = motionYaw - 60f - (owner.yRotO - owner.yRot) * 3f

        // Facing roughly along or against the travel direction means the smear is seen edge-on, so
        // it is laid flat instead of being drawn as a vanishingly thin sliver.
        val difference = Mth.abs(Mth.wrapDegrees(motionYaw + 26.3f - owner.yRot))
        pitch = if (difference < 10f || difference > 160f) -90f else mc.gameRenderer.mainCamera.xRot()
    }

    private fun motionYawDegrees(): Float {
        val degrees = Math.toDegrees(atan2(motionZ, motionX)) - 90.0
        return (if (degrees < 0.0) degrees + 360.0 else degrees).toFloat()
    }

    fun renderX(partialTicks: Float): Double = prevX + (x - prevX) * partialTicks
    fun renderY(partialTicks: Float): Double = prevY + (y - prevY) * partialTicks
    fun renderZ(partialTicks: Float): Double = prevZ + (z - prevZ) * partialTicks

}

/**
 * Which sprite a cubic draws. Either one fixed image, or a group cycled over time.
 */
sealed interface DashSpriteSource {

    fun current(): DashSprite

    class Fixed(private val sprite: DashSprite) : DashSpriteSource {
        override fun current(): DashSprite = sprite
    }

    class Animated(private val frames: List<DashSprite>, private val periodMs: Int) : DashSpriteSource {
        private val bornAt = System.currentTimeMillis()

        override fun current(): DashSprite {
            if (frames.isEmpty() || periodMs <= 0) {
                return frames.first()
            }

            val phase = ((System.currentTimeMillis() - bornAt) % periodMs).toFloat() / periodMs
            return frames[(phase * frames.size).toInt().coerceIn(0, frames.size - 1)]
        }
    }

}

/**
 * A speck thrown off a cubic. Sparks live in the cubic's local space and are drawn mirrored around
 * it, so one spark produces a pair of marks straddling the smear.
 */
class DashSpark(random: Random, sizeScale: Float) {

    private companion object {
        const val LIFETIME_MS = 1000f
    }

    private val speed = random.nextDouble() / 50.0 * sizeScale
    private val yawRad = random.nextFloat() * Mth.TWO_PI
    private val pitchRad = (-90f + random.nextFloat() * 180f) * Mth.DEG_TO_RAD
    private val bornAt = System.currentTimeMillis()

    var x: Double = 0.0
        private set
    var y: Double = 0.0
        private set
    var z: Double = 0.0
        private set

    private var prevX = 0.0
    private var prevY = 0.0
    private var prevZ = 0.0

    fun tick() {
        prevX = x
        prevY = y
        prevZ = z
        x += sin(yawRad) * speed
        y += cos(pitchRad - Mth.HALF_PI) * speed
        z += cos(yawRad) * speed
    }

    fun alpha(): Float = 1f - progress()

    fun isDead(): Boolean = progress() >= 1f

    private fun progress(): Float =
        ((System.currentTimeMillis() - bornAt).toFloat() / LIFETIME_MS).coerceIn(0f, 1f)

    fun renderX(partialTicks: Float): Double = prevX + (x - prevX) * partialTicks
    fun renderY(partialTicks: Float): Double = prevY + (y - prevY) * partialTicks
    fun renderZ(partialTicks: Float): Double = prevZ + (z - prevZ) * partialTicks

}
