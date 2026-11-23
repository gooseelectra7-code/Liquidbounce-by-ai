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
package net.ccbluex.liquidbounce.features.module.modules.combat.totem

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.modules.player.nofall.ModuleNoFall
import net.ccbluex.liquidbounce.utils.block.getBlock
import net.ccbluex.liquidbounce.utils.block.getPotentialSecondBedBlock
import net.ccbluex.liquidbounce.utils.block.getSortedSphere
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.block.isCharged
import net.ccbluex.liquidbounce.utils.block.isFallDamageBlocking
import net.ccbluex.liquidbounce.utils.entity.FallingPlayer
import net.ccbluex.liquidbounce.utils.entity.getDamageFromExplosion
import net.ccbluex.liquidbounce.utils.entity.getEffectiveDamage
import net.ccbluex.liquidbounce.utils.entity.getExplosionDamageFromEntity
import net.ccbluex.liquidbounce.utils.entity.isBurrowed
import net.ccbluex.liquidbounce.utils.entity.isInHole
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.item.attackDamage
import net.ccbluex.liquidbounce.utils.item.isSword
import net.ccbluex.liquidbounce.utils.world.bedRule
import net.ccbluex.liquidbounce.utils.world.respawnAnchorWorks
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.RespawnAnchorBlock

/**
 * Incoming-damage prediction for the totem features.
 *
 * The damage model itself is NOT new: `utils/entity/EntityExtensions.kt` already mirrors the vanilla
 * pipeline ([getEffectiveDamage], [getExplosionDamageFromEntity], [getDamageFromExplosion]) and
 * `player/offhand/Totem.kt` already wires it up. That wiring is what this class lifts out, because
 * there it is a chain of nested `object`s parented to `ModuleOffhand` and cannot be mounted anywhere
 * else. Here it is a CLASS taking its owner as a constructor argument - the
 * `tree(WallHitBypass(this))` / `tree(PointTracker(this))` shape - so `ModuleMainHandTotem` and
 * `ModuleAutoTotem` can each own an instance with its own settings.
 *
 * Deliberate differences from the original wiring:
 * - **The verdict is cached per tick** ([warrantsTotem]). `ModuleAutoTotem.condition()` is called
 *   from `ScheduleInventoryActionEvent`, which fires MANY times inside one client tick
 *   (`InventoryManager`'s cycle loop), so an uncached [damageFromBlocks] would re-walk a
 *   ~4000-block sphere several times per tick. The cache key carries the health and the threshold,
 *   so a mid-tick damage packet still forces a recompute.
 * - **The block sphere is `by lazy`, not built from an `onChanged` hook.** The original fills its
 *   sphere field only from `onChanged`, which does not fire when the setting merely LOADS as true
 *   out of a saved config - that path leaves the field null and silently disables the block scan.
 * - **A null block state is skipped, not force-unwrapped.** The original dereferences the state of
 *   every sphere position; at a chunk border that is a crash rather than a missed prediction.
 * - The sub-features are nested `ToggleableValueGroup`s so the ClickGUI collapses their settings
 *   when they are off (`LeetClickGuiScreen.kt:1680-1700` only grows the reveal while
 *   `value.enabled`). They are nested CLASSES taking the owner as a constructor argument, not
 *   nested `object`s: an object cannot reference the enclosing instance, which is why the original
 *   could use objects (it is one) and this cannot.
 *
 * @param enabledByDefault off for `ModuleAutoTotem`, whose javelin semantics stay 1:1 by default
 *   (AGENTS.md guards that), on for `ModuleMainHandTotem`, which has no such legacy to protect.
 */
class IncomingDamage(
    owner: EventListener,
    enabledByDefault: Boolean,
) : ToggleableValueGroup(owner, "IncomingDamage", enabledByDefault) {

    /**
     * Compare the health left AFTER the predicted damage against the threshold, rather than asking
     * whether the damage alone would kill.
     *
     * On (the default) this is the aggressive reading: a totem goes in whenever
     * `health - predicted <= threshold`, so it is occasionally spent on a hit that would have been
     * survivable. Off, the prediction only fires when the incoming damage covers the whole health
     * bar - fewer swaps, but a double crystal landing inside one round trip can outrun it.
     */
    private val subtractCalculatedDamage by boolean("SubtractCalculatedDamage", true)

    /**
     * [Trigger.ONLY_LETHAL]: count a sword hit from the nearest opponent.
     *
     * Read ONLY in that trigger, and hidden outside it. Declared BEFORE [trigger] because the
     * hook there touches this field. A whole nested group can be hidden this way because
     * `ValueGroup` IS a `Value` (`config/types/group/ValueGroup.kt:94`), so the GUI's
     * `notAnOption` skip applies to it exactly like it does to a single setting.
     */
    private val melee = tree(MeleeGroup(this))

    /**
     * Which question the prediction answers. See [Trigger].
     *
     * The hook moves [melee] in and out of the GUI, and fires on a config load as well as a click -
     * `Value.deserializeFrom` ends in `set` (`config/types/Value.kt:307`), which runs the
     * changed-listeners (`:244`).
     */
    private val trigger by enumChoice("Trigger", Trigger.THRESHOLD)
        .onChanged { syncMeleeVisibility(it) }

    init {
        // A change hook cannot cover the INITIAL state; the default needs this.
        syncMeleeVisibility(trigger)
    }

    /** Shows [melee] only under [Trigger.ONLY_LETHAL]. See `SwapJitter.syncCustomVisibility`. */
    private fun syncMeleeVisibility(current: Trigger) {
        melee.notAnOption(current != Trigger.ONLY_LETHAL)
    }

    /** Crystals, primed TNT, TNT minecarts and creepers, via [getExplosionDamageFromEntity]. */
    private val explosionEntities by boolean("ExplosionEntities", true)

    /**
     * Beds and charged respawn anchors. Off by default: it walks a radius-[BLOCK_SCAN_RADIUS] sphere
     * of block states, far and away the most expensive thing in here.
     */
    private val explosionBlocks by boolean("ExplosionBlocks", false)

    /** Fall damage, through the same predictor the offhand module uses. */
    private val fall = tree(FallDamageGroup(this))

    /**
     * While burrowed or standing in a hole, substitute this group's lower threshold.
     *
     * Explosions barely reach through obsidian, so the lower number lets a useful item stay in hand
     * longer. It can only ever make the feature LESS eager, never more.
     */
    private val safety = tree(SafetyGroup(this))

    /** Offsets walked by [damageFromBlocks]; built once, on first use. */
    private val sphere by lazy { BlockPos.ZERO.getSortedSphere(BLOCK_SCAN_RADIUS) }

    /**
     * What the prediction is asked to decide.
     *
     * [THRESHOLD] is the general-purpose reading and the default: every enabled source, compared
     * against the caller's health threshold (see [subtractCalculatedDamage] for how hard).
     *
     * [ONLY_LETHAL] is the crystal-PvP reading the user asked for on 2026-09-14 - take one only
     * when we predict DEATH from an anchor, a crystal or the nearest opponent's sword. It answers a
     * narrower question over a narrower source set, and in this mode [subtractCalculatedDamage],
     * [explosionEntities], [explosionBlocks], [fall] and [safety] are all IGNORED, because each of
     * them either widens the sources or moves the comparison off death:
     * - the comparison is against the WHOLE health bar, never a threshold;
     * - explosions are narrowed to end crystals and charged respawn anchors - no TNT, no creepers,
     *   no beds;
     * - fall damage does not count at all;
     * - a sword in the nearest opponent's main hand does.
     *
     * The blast is the WORSE of the crystal and the anchor rather than their sum (both landing on
     * one tick is not worth pre-spending a totem for), but the sword ADDS to it: crystal plus a hit
     * in the same tick is how people actually die here, so a plain max over all three would miss
     * the most common death outright. That combination was the user's explicit choice.
     *
     * Note there is no hysteresis in this mode - the threshold argument goes unused, so a hold is
     * released the moment death stops being predicted. That is the point of the mode, but it does
     * mean a flickering crystal flickers the hold.
     */
    enum class Trigger(override val tag: String) : Tagged {
        THRESHOLD("Threshold"),
        ONLY_LETHAL("OnlyLethal"),
    }

    /** The last prediction, for the debug read-out only. */
    var lastPredicted = 0f
        private set

    private var cachedTick = -1
    private var cachedHealth = Float.NaN
    private var cachedThreshold = Float.NaN
    private var cachedResult = false

    /**
     * Whether the predicted incoming damage warrants a totem at [threshold].
     *
     * Cheap to call repeatedly inside one tick - see the class KDoc on the cache. Callers check
     * [enabled] themselves and fall back to their own plain health test, so turning this group off
     * restores their original behaviour exactly.
     *
     * @param effectiveHealth health plus whatever absorption the caller decided to count.
     * @param threshold the caller's own health threshold, already adjusted for what it knows about
     *   (an elytra in the chest slot, a release hysteresis).
     */
    fun warrantsTotem(effectiveHealth: Float, threshold: Float): Boolean {
        val tick = player.tickCount
        if (tick == cachedTick && effectiveHealth == cachedHealth && threshold == cachedThreshold) {
            return cachedResult
        }

        val result = compute(effectiveHealth, threshold)

        cachedTick = tick
        cachedHealth = effectiveHealth
        cachedThreshold = threshold
        cachedResult = result

        return result
    }

    /** Drops the per-tick cache. For disable / world change / death paths. */
    fun reset() {
        cachedTick = -1
        cachedHealth = Float.NaN
        cachedThreshold = Float.NaN
        cachedResult = false
        lastPredicted = 0f
    }

    /**
     * The original's `healthBelowThreshold`, restated.
     *
     * The sources are probed cheapest first and each one short-circuits the moment the running
     * maximum covers [allowedDamage] - there is no reason to keep scanning once the answer cannot
     * change any more.
     */
    private fun compute(effectiveHealth: Float, threshold: Float): Boolean {
        if (trigger == Trigger.ONLY_LETHAL) {
            return computeLethal(effectiveHealth)
        }

        val safeNow = safety.enabled && (player.isBurrowed() || player.isInHole())
        val effectiveThreshold = if (safeNow) safety.safeHealth else threshold

        var allowedDamage = effectiveHealth - effectiveThreshold

        // At or under the threshold on health alone; no prediction needed.
        if (allowedDamage <= 0f) {
            lastPredicted = 0f
            return true
        }

        // Without the subtraction the question becomes "would this alone kill me", so the whole
        // health bar is what the damage has to cover.
        if (!subtractCalculatedDamage) {
            allowedDamage = effectiveHealth
        }

        var predicted = damageFromEntities(allowedDamage)

        if (predicted < allowedDamage) {
            predicted = predicted.coerceAtLeast(damageFromBlocks(allowedDamage))
        }

        // Fall damage ADDS to an explosion rather than competing with it: landing into a crystal is
        // both at once. The original does the same.
        if (predicted < allowedDamage) {
            predicted += fallDamage()
        }

        lastPredicted = predicted
        return predicted >= allowedDamage
    }

    /**
     * [Trigger.ONLY_LETHAL]: would an anchor, a crystal or the nearest opponent's sword kill us?
     *
     * No threshold anywhere - the allowance is the entire health bar, so this answers true only for
     * damage that actually finishes us. See [Trigger] for why the blast is a max and the sword a
     * sum.
     */
    private fun computeLethal(effectiveHealth: Float): Boolean {
        val allowedDamage = effectiveHealth

        var predicted = crystalDamage(allowedDamage).coerceAtLeast(anchorDamage(allowedDamage))
        predicted += swordDamage()

        lastPredicted = predicted
        return predicted >= allowedDamage
    }

    /** The worst end crystal around us - crystals only, unlike [damageFromEntities]. */
    private fun crystalDamage(allowedDamage: Float): Float {
        var maxDamage = 0f

        for (entity in world.entitiesForRendering()) {
            if (entity !is EndCrystal) {
                continue
            }

            maxDamage = maxDamage.coerceAtLeast(player.getExplosionDamageFromEntity(entity))

            if (maxDamage >= allowedDamage) {
                return maxDamage
            }
        }

        return maxDamage
    }

    /**
     * The worst charged respawn anchor around us - anchors only, unlike [damageFromBlocks].
     *
     * Returns nothing in a dimension where an anchor sets spawn instead of exploding, which also
     * skips the sphere walk entirely there.
     */
    private fun anchorDamage(allowedDamage: Float): Float {
        if (world.respawnAnchorWorks) {
            return 0f
        }

        val playerPos = player.blockPosition()
        var maxDamage = 0f

        for (offset in sphere) {
            val pos = offset.offset(playerPos)

            val block = pos.getBlock()
            if (block !is RespawnAnchorBlock) {
                continue
            }

            val state = pos.getState() ?: continue
            if (!block.isCharged(state)) {
                continue
            }

            maxDamage = maxDamage.coerceAtLeast(
                player.getDamageFromExplosion(
                    pos = pos.center,
                    power = BLOCK_BLAST_POWER,
                    explosionRange = BLOCK_BLAST_RANGE,
                    damageDistance = BLOCK_BLAST_RANGE * BLOCK_BLAST_RANGE,
                    // Vanilla removes the anchor before resolving its blast, so leaving it in would
                    // have it shield the explosion it is itself producing.
                    exclude = listOf(pos),
                    damageSource = player.damageSources().badRespawnPointExplosion(pos.center)
                )
            )

            if (maxDamage >= allowedDamage) {
                return maxDamage
            }
        }

        return maxDamage
    }

    /**
     * One sword hit from the nearest opponent in reach.
     *
     * Base item damage plus Sharpness, through [attackDamage] - the client cannot read enchantment
     * damage off the item any more (MC-196250) and that property is where this fork works around
     * it. Deliberately NO crit multiplier and no Strength: the user asked for the plain sword hit,
     * so this under-predicts a jump-crit rather than spending totems on one that may never come.
     */
    private fun swordDamage(): Float {
        if (!melee.enabled) {
            return 0f
        }

        val enemy = nearestArmedOpponent() ?: return 0f

        return player.getEffectiveDamage(
            player.damageSources().playerAttack(enemy),
            enemy.mainHandItem.attackDamage.toFloat()
        )
    }

    /** The closest opponent within the melee group's range holding a sword, or null. */
    private fun nearestArmedOpponent(): Player? {
        val rangeSq = (melee.range * melee.range).toDouble()
        var best: Player? = null
        var bestDistance = Double.MAX_VALUE

        for (other in world.players()) {
            if (other === player || !other.shouldBeAttacked() || !other.mainHandItem.isSword) {
                continue
            }

            val distance = player.squaredBoxedDistanceTo(other)
            if (distance > rangeSq || distance >= bestDistance) {
                continue
            }

            best = other
            bestDistance = distance
        }

        return best
    }

    /** The worst single explosive entity around us. */
    private fun damageFromEntities(allowedDamage: Float): Float {
        if (!explosionEntities) {
            return 0f
        }

        var maxDamage = 0f

        for (entity in world.entitiesForRendering()) {
            maxDamage = maxDamage.coerceAtLeast(player.getExplosionDamageFromEntity(entity))

            if (maxDamage >= allowedDamage) {
                return maxDamage
            }
        }

        return maxDamage
    }

    /**
     * The worst bed or charged anchor around us.
     *
     * The exploding block is passed as `exclude` because vanilla removes it BEFORE resolving its
     * explosion, so leaving it in would have it shield the blast it is itself producing. A bed is
     * two blocks and both have to go.
     */
    private fun damageFromBlocks(allowedDamage: Float): Float {
        if (!explosionBlocks) {
            return 0f
        }

        val bedsAreInert = !world.bedRule.explodes
        val anchorsAreInert = world.respawnAnchorWorks
        val playerPos = player.blockPosition()
        var maxDamage = 0f

        for (offset in sphere) {
            val pos = offset.offset(playerPos)
            val block = pos.getBlock()
            val state = pos.getState() ?: continue

            val noBedExplosion = bedsAreInert || block !is BedBlock
            val noAnchorExplosion =
                anchorsAreInert || block !is RespawnAnchorBlock || !block.isCharged(state)

            if (noBedExplosion && noAnchorExplosion) {
                continue
            }

            val exclude = if (noBedExplosion) {
                listOf(pos)
            } else {
                listOf(pos, block.getPotentialSecondBedBlock(state, pos))
            }

            maxDamage = maxDamage.coerceAtLeast(
                player.getDamageFromExplosion(
                    pos = pos.center,
                    power = BLOCK_BLAST_POWER,
                    explosionRange = BLOCK_BLAST_RANGE,
                    damageDistance = BLOCK_BLAST_RANGE * BLOCK_BLAST_RANGE,
                    exclude = exclude,
                    damageSource = player.damageSources().badRespawnPointExplosion(pos.center)
                )
            )

            if (maxDamage >= allowedDamage) {
                return maxDamage
            }
        }

        return maxDamage
    }

    /** What the ground we are heading for would do to us. */
    private fun fallDamage(): Float {
        if (!fall.enabled || ModuleNoFall.running || player.fallDistance <= MIN_FALL_DISTANCE) {
            return 0f
        }

        if (fall.ignoreElytra && player.isFallFlying && player.hasPose(Pose.FALL_FLYING)) {
            return 0f
        }

        val collision = FallingPlayer.fromPlayer(player).findCollision(FALL_LOOKAHEAD_TICKS)?.pos
            ?: return 0f

        if (collision.isFallDamageBlocking()) {
            return 0f
        }

        return player.getEffectiveDamage(
            player.damageSources().fall(),
            player.calculateFallDamage(player.fallDistance, 1f).toFloat()
        )
    }

    /** Fall damage and its one option. */
    class FallDamageGroup(owner: EventListener) : ToggleableValueGroup(owner, "FallDamage", true) {

        /** Do not count fall damage while actually gliding on an elytra. */
        val ignoreElytra by boolean("IgnoreElytra", false)

    }

    /** The burrowed / in-hole carve-out and the threshold it substitutes. */
    class SafetyGroup(owner: EventListener) : ToggleableValueGroup(owner, "Safety", true) {

        val safeHealth by float("SafeHealth", 10f, 0f..20f, "HP")

    }

    /** The nearest opponent's sword, and how close they have to be. */
    class MeleeGroup(owner: EventListener) : ToggleableValueGroup(owner, "MeleeSword", true) {

        /**
         * How close the nearest opponent has to be for their sword to count, as a boxed distance.
         *
         * Vanilla entity reach is 3, so the default leaves a block of margin for the step they take
         * while our packet is in flight. Beyond this they cannot reach us this tick, and counting
         * them would spend totems on someone still running at us from across the map.
         */
        val range by float("Range", 4f, 1f..6f, "blocks")

    }

    private companion object {

        /** Radius of the bed / anchor scan, matching the original's 10. */
        const val BLOCK_SCAN_RADIUS = 10f

        /** Vanilla bed / respawn-anchor blast power and its reach. */
        const val BLOCK_BLAST_POWER = 5f
        const val BLOCK_BLAST_RANGE = 10f

        /** Vanilla takes no fall damage below this distance. */
        const val MIN_FALL_DISTANCE = 3f

        /** How far ahead [FallingPlayer] is simulated. */
        const val FALL_LOOKAHEAD_TICKS = 20

    }

}
