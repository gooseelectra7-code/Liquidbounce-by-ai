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

package net.ccbluex.liquidbounce.features.module.modules.combat.testelytratarget.air

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * The aerodynamics of a gliding player, as pure functions.
 *
 * Nothing here touches the world, an entity or Minecraft's singletons: every input is a parameter,
 * so the guidance layer can roll a hypothetical flight forward and the unit tests can run headless.
 *
 * [step] and [boost] are transcriptions of vanilla, not models of it. They were checked line by line
 * against the 26.1.2 sources jar during implementation; the citations below are the exact ranges
 * that were read. An approximation anywhere in here would silently poison every prediction built on
 * top, so this file is the one place in the module that must stay a literal copy.
 */
object WingPhysics {

    /**
     * The `Attributes.GRAVITY` default, 0.08 (`Attributes.java:45-47`).
     *
     * Vanilla reaches it the long way: `updateFallFlyingMovement` reads `getEffectiveGravity()`
     * (`LivingEntity.java:2548`), which is `getGravity()` unless the entity is falling with
     * SLOW_FALLING, and `getGravity()` resolves to the synced GRAVITY attribute. Callers that have a
     * real entity should pass its actual value; this constant is the right answer for a plain player.
     */
    const val DEFAULT_GRAVITY: Double = 0.08

    /**
     * One tick of elytra flight. Exact port of `LivingEntity.updateFallFlyingMovement`
     * (`LivingEntity.java:2543-2568`, 26.1.2 sources jar).
     *
     * The only deviations are syntactic, forced by Kotlin's lack of implicit float widening: the
     * drag literals of `:2567` are widened with an explicit `toDouble()` (bit-identical to Java's
     * widening), and `Math.cos` / `Mth.sin` receive `leanAngle.toDouble()` exactly as Java widens
     * the float argument.
     *
     * @param movement velocity in blocks per tick
     * @param look unit look vector, as produced by [look]
     * @param pitchDegrees xRot in degrees, positive being nose-down
     * @param gravity the entity's effective gravity — see [DEFAULT_GRAVITY]
     */
    @Suppress("MagicNumber")
    fun step(movement: Vec3, look: Vec3, pitchDegrees: Float, gravity: Double = DEFAULT_GRAVITY): Vec3 {
        var result = movement
        val leanAngle = pitchDegrees * (Math.PI / 180.0).toFloat()
        val lookHorLength = Math.sqrt(look.x * look.x + look.z * look.z)
        val moveHorLength = result.horizontalDistance()
        val liftForce = Mth.square(Math.cos(leanAngle.toDouble()))

        result = result.add(0.0, gravity * (-1.0 + liftForce * 0.75), 0.0)

        if (result.y < 0.0 && lookHorLength > 0.0) {
            val convert = result.y * -0.1 * liftForce
            result = result.add(look.x * convert / lookHorLength, convert, look.z * convert / lookHorLength)
        }

        if (leanAngle < 0.0F && lookHorLength > 0.0) {
            val convert = moveHorLength * -Mth.sin(leanAngle.toDouble()) * 0.04
            result = result.add(-look.x * convert / lookHorLength, convert * 3.2, -look.z * convert / lookHorLength)
        }

        if (lookHorLength > 0.0) {
            result = result.add(
                (look.x / lookHorLength * moveHorLength - result.x) * 0.1,
                0.0,
                (look.z / lookHorLength * moveHorLength - result.z) * 0.1,
            )
        }

        return result.multiply(0.99F.toDouble(), 0.98F.toDouble(), 0.99F.toDouble())
    }

    /**
     * One tick of an attached firework rocket's push. Exact port of the
     * `isAttachedToEntity() && attachedToEntity.isFallFlying()` branch of `FireworkRocketEntity.tick`
     * (`FireworkRocketEntity.java:127-139`, 26.1.2 sources jar).
     *
     * Iterated alone the recurrence is `v' = 0.5 v + 0.85 look`, so it converges on `1.7 * look`.
     * That fixed point is theoretical: a rocket dies after a few dozen ticks and [step] applies its
     * drag between every push, which is why real boosted flight settles far below it.
     *
     * The rocket pushes along the look direction of whoever it is attached to. For us that is the
     * rotation on the wire rather than the camera, because `MixinFireworkRocketEntity` substitutes
     * `RotationManager.currentRotation` for the look — the same rotation this module steers with.
     */
    @Suppress("MagicNumber")
    fun boost(movement: Vec3, look: Vec3): Vec3 = movement.add(
        look.x * 0.1 + (look.x * 1.5 - movement.x) * 0.5,
        look.y * 0.1 + (look.y * 1.5 - movement.y) * 0.5,
        look.z * 0.1 + (look.z * 1.5 - movement.z) * 0.5,
    )

    /**
     * Unit look vector for a yaw/pitch pair — `Vec3.directionFromRotation(pitch, yaw)`
     * (`Vec3.java:260-266`), the same helper `Rotation.directionVector` uses.
     */
    fun look(yaw: Float, pitch: Float): Vec3 = Vec3.directionFromRotation(pitch, yaw)

    /**
     * Specific energy of a flying body: kinetic per unit mass plus potential per unit mass.
     *
     * This is the quantity the chase is really fought over. Speed and altitude are interchangeable —
     * a dive converts height into speed and a climb spends speed to buy height back — but their sum
     * only changes when something adds or removes energy, and in the air the only source is a
     * firework. Comparing our figure against the target's says who can dictate the fight regardless
     * of who happens to be faster at this instant.
     *
     * Units are blocks and ticks throughout, matching [step].
     */
    fun specificEnergy(velocity: Vec3, y: Double, gravity: Double = DEFAULT_GRAVITY): Double =
        velocity.lengthSqr() / 2.0 + gravity * y
}
