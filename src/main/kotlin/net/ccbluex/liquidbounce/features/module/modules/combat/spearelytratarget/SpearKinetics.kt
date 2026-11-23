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

package net.ccbluex.liquidbounce.features.module.modules.combat.spearelytratarget

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.KineticWeapon
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Everything the module needs to know about how a spear actually kills, read off the item rather
 * than hardcoded — every spear tier and any future kinetic weapon carries its own numbers.
 *
 * ## The vanilla mechanic
 *
 * A spear is a *use* item, not an attack item: right-click starts a charge
 * ([net.minecraft.world.item.ItemUseAnimation] `SPEAR`). While the item is being used,
 * `ItemStack.onUseTick` calls `KineticWeapon.damageEntities` **every tick, server-side only**, and
 * that is the one and only thing that deals the damage. It lands when all of the following hold:
 *
 * - `usedTicks >= delayTicks` — the windup has passed;
 * - `usedTicks - delayTicks <= damageConditions.maxDurationTicks` — the window has not closed;
 * - a raycast **along the look direction** hits the entity. The segment runs from
 *   `eye + look * minReach` to `eye + look * (maxReach + max(0, knownMovement · look))` with the
 *   hitboxes inflated by `hitboxMargin`, and it is stopped by terrain. That range has a **minimum**
 *   as well as a maximum (2.0 and 4.5 on every vanilla spear), so a target closer than [Profile.nearEdge]
 *   cannot be hit at all — and the far end grows with our own speed, because the segment covers the
 *   ground travelled this tick;
 * - `speed >= minSpeed * factor` and `relativeSpeed >= minRelativeSpeed * factor`, where `speed` is
 *   our motion **projected on the look direction**, `relativeSpeed` is that minus the target's own
 *   projection (clamped at zero), and `factor` is 1.0 for a player attacker (0.2 only for mobs).
 *   All of these are in blocks per *second*;
 * - the target was not stabbed within the last [Profile.contactCooldownTicks] ticks.
 *
 * Damage is `baseAttackDamage + floor(relativeSpeed * damageMultiplier)`, so speed is not a gate to
 * clear once — it is the damage roll itself.
 *
 * On a vanilla copper spear that reads: windup 13 ticks, lethal for another 250, contact cooldown
 * 10 ticks, damage gate `relativeSpeed >= 4.6` blocks per second with no absolute speed requirement,
 * band 2.0 to 4.5 blocks with a 0.125 margin. Two consequences shape the whole module: the charge is
 * cheap to *keep* (over thirteen seconds of it) and expensive to *restart*, and the speed the gate
 * asks for is a fraction of gliding speed, so the hard part of a pass is the geometry, not the pace.
 *
 * Nothing here can be forced from the client — the client's whole job is to arrive fast, pointed
 * the right way, at the right distance, already charged. Hence [ticksUntilStrikeBand]: the charge
 * has to start [Profile.delayTicks] *before* the target enters the band, not when it is already there.
 */
@Suppress("TooManyFunctions")
internal object SpearKinetics {

    /**
     * `KineticWeapon.getMotion` scales the per-tick movement by 20, so every speed the server
     * compares against — and every speed in here — is in blocks per **second** while the module
     * itself thinks in ticks.
     */
    const val TICKS_PER_SECOND = 20.0

    /**
     * The kinetic profile of one spear, resolved against the player holding it.
     */
    class Profile(
        val slot: HotbarItemSlot,
        val delayTicks: Int,
        val windowTicks: Int,
        val contactCooldownTicks: Int,
        val minSpeed: Double,
        val minRelativeSpeed: Double,
        val minReach: Double,
        val maxReach: Double,
        val hitboxMargin: Double,
    ) {
        /**
         * Total ticks a charge stays lethal, counted from the moment the use starts.
         */
        val lethalUntilTick: Int get() = delayTicks + windowTicks

        /**
         * Near edge of the band, measured from the eye to the hitbox, including the slack the
         * server allows by inflating the hitboxes. Inside this the shaft starts *past* the target.
         */
        val nearEdge: Double get() = minReach - hitboxMargin

        /**
         * Far edge for a given forward travel this tick. The server builds the segment out to
         * `maxReach + forward movement`, so the reach genuinely grows with speed — at ram pace the
         * tip covers more than a block past the item's nominal 4.5.
         */
        fun farEdge(forwardPerTick: Double): Double =
            maxReach + hitboxMargin + maxOf(0.0, forwardPerTick)
    }

    /**
     * The best spear in the hotbar, or `null` if there is none. Selected on the kinetic component
     * rather than the item tag, because that component is exactly what the server tests.
     */
    fun findSpearSlot(): HotbarItemSlot? =
        Slots.Hotbar.findSlot { stack -> stack.has(DataComponents.KINETIC_WEAPON) }

    fun profileFor(slot: HotbarItemSlot): Profile? {
        val stack = slot.itemStack
        val weapon = stack.get(DataComponents.KINETIC_WEAPON) ?: return null
        val damage = weapon.damageConditions().orElse(null) ?: return null
        val range = player.getAttackRangeWith(stack)

        return Profile(
            slot = slot,
            delayTicks = weapon.delayTicks(),
            windowTicks = damage.maxDurationTicks(),
            contactCooldownTicks = weapon.contactCooldownTicks(),
            minSpeed = damage.minSpeed().toDouble(),
            minRelativeSpeed = damage.minRelativeSpeed().toDouble(),
            minReach = range.effectiveMinRange(player).toDouble(),
            maxReach = range.effectiveMaxRange(player).toDouble(),
            hitboxMargin = range.hitboxMargin().toDouble(),
        )
    }

    fun isKinetic(stack: ItemStack): Boolean = stack.has(DataComponents.KINETIC_WEAPON)

    /**
     * The same motion vector the server uses for its speed gates — taken from vanilla rather than
     * from `deltaMovement` so the client's estimate cannot drift from the check it is predicting.
     * Blocks per second, see [TICKS_PER_SECOND].
     */
    fun motionOf(entity: Entity): Vec3 = KineticWeapon.getMotion(entity)

    fun ownSpeed(): Double = motionOf(player).length()

    /**
     * The direction the *server* will raycast along.
     *
     * Not the camera: under a silent rotation the aim is what goes on the wire and the real look
     * lags behind it, so every speed gate and every reach test has to be predicted against the aim.
     * Falls back to the camera only before the first rotation of an engagement exists.
     */
    fun aimDirection(): Vec3 = RotationManager.currentRotation?.directionVector ?: player.lookAngle

    /**
     * The server's `speed`: how fast we travel **along the look direction**, blocks per second.
     * Note that this is a projection, not the length of the motion — flying fast across the aim
     * counts for nothing.
     */
    fun forwardSpeed(): Double = aimDirection().dot(motionOf(player))

    /**
     * The server's `relativeSpeed`, blocks per second: our forward speed minus the target's, both
     * projected on the look direction and clamped at zero. This is the value the damage gate tests
     * *and* the value the bonus damage is rolled from.
     */
    fun relativeSpeed(target: LivingEntity): Double {
        val look = aimDirection()
        return maxOf(0.0, look.dot(motionOf(player)) - look.dot(motionOf(target)))
    }

    /**
     * How far the far end of the segment is pushed out this tick by our own travel — the server
     * adds exactly this to the reach so that a fast pass cannot tunnel through the band.
     */
    fun forwardPerTick(): Double = player.knownMovement.dot(aimDirection())

    /**
     * Speed at which the gap to [target] is shrinking, blocks per second. This is the projection of
     * relative motion onto the line of approach, which is what decides *when* contact happens —
     * a question about the gap rather than about the aim, so unlike [relativeSpeed] it is measured
     * along the line to the target.
     */
    fun closingSpeed(target: LivingEntity): Double {
        val toTarget = target.position().subtract(player.position())
        if (toTarget.lengthSqr() < 1.0E-6) {
            return 0.0
        }

        return motionOf(player).subtract(motionOf(target)).dot(toTarget.normalize())
    }

    /** Plain centre-to-centre distance, for the engagement-scale decisions. */
    fun gapTo(target: LivingEntity): Double = player.position().distanceTo(target.position())

    /**
     * Distance from the eye to the nearest point of the target's hitbox — what the band is actually
     * measured against, since the server compares `eye → hit location`. Centre-to-centre is off by
     * the eye height and by half a hitbox, which is a lot inside a band only 2.5 blocks deep.
     */
    fun reachTo(target: LivingEntity): Double = sqrt(target.squaredBoxedDistanceTo(player.eyePosition))

    /**
     * Ticks until the target enters the band where a stab can connect, or `null` when we are not
     * closing at all and it never will at this rate.
     *
     * Returns 0 once already inside the band. Deliberately measured against the band's *far* edge:
     * the charge must be live at the moment the band is entered, and at elytra speed the whole band
     * is crossed in a tick or two.
     */
    fun ticksUntilStrikeBand(target: LivingEntity, profile: Profile): Int? {
        val gap = reachTo(target) - profile.farEdge(forwardPerTick())
        if (gap <= 0.0) {
            return 0
        }

        val closingPerTick = closingSpeed(target) / TICKS_PER_SECOND
        if (closingPerTick <= 1.0E-4) {
            return null
        }

        return ceil(gap / closingPerTick).toInt()
    }

    /**
     * Whether we are carrying enough speed for the hit to register at all. Charging below this is
     * wasted — the pass will connect geometrically and still deal nothing. Both thresholds are used
     * unscaled because the scaling factor is 1.0 for a player attacker.
     */
    fun hasStrikeSpeed(target: LivingEntity, profile: Profile): Boolean =
        forwardSpeed() >= profile.minSpeed && relativeSpeed(target) >= profile.minRelativeSpeed

    /**
     * Whether the target sits in the shaft's reach band right now.
     *
     * The weapon is modelled as a segment running from [Profile.nearEdge] to [Profile.farEdge]
     * ahead of the eye — the spear itself, held out in front. Only what that segment passes through
     * is pierced. Beyond the far end it is out of reach, and **inside the near end the segment
     * starts past the target entirely**, so a point-blank ram deals nothing at all. On a wing the
     * whole band is crossed in one or two ticks, which is why the charge has to be live before the
     * far edge is reached rather than when contact looks imminent.
     */
    fun isInStrikeBand(target: LivingEntity, profile: Profile): Boolean {
        val reach = reachTo(target)
        return reach >= profile.nearEdge && reach <= profile.farEdge(forwardPerTick())
    }

    /**
     * True once we are past the near edge — the target can no longer be pierced this pass no matter
     * what we do, because the shaft now starts behind it.
     */
    fun isInsideDeadZone(target: LivingEntity, profile: Profile): Boolean =
        reachTo(target) < profile.nearEdge

    /**
     * The entity the tip would actually pierce this instant, resolved with vanilla's own raycast so
     * it agrees with the server's decision exactly rather than approximating it.
     *
     * Caveat worth knowing: this reads the player's **real** look direction rather than
     * [aimDirection]. Under
     * [net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection.CHANGE_LOOK] the two are
     * the same and the answer is exact; under a silent correction the real look lags the aim, so
     * treat a negative as "not confirmed" rather than "will miss".
     */
    fun pierceTarget(profile: Profile, tickDelta: Float = 0f): LivingEntity? {
        val range = player.getAttackRangeWith(profile.slot.itemStack)
        val hit = range.getClosesetHit(player, tickDelta, EntitySelector.CAN_BE_PICKED) as? EntityHitResult
            ?: return null

        if (!range.isInRange(player, hit.location)) {
            return null
        }

        return hit.entity as? LivingEntity
    }
}
