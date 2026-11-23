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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.FIRST_PRIORITY
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.READ_FINAL_STATE
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

/**
 * KnockbackDisplace module
 *
 * Delays knockback hits by one tick to redirect knockback. When an attack with extra knockback
 * (sprint hit or Knockback-enchanted weapon) is about to happen, the attack is cancelled, the
 * server-side rotation is silently spoofed to the player's yaw rotated by the chosen direction
 * for exactly one movement packet, and the attack is replayed right after that packet - so the
 * server computes the victim's knockback direction from the spoofed yaw.
 *
 * Ported from the Echo client ([hack.echo.client.features.impl.combat.KnockbackDisplaceModule],
 * debug-only there; regular module here). Echo flow: EventStartAttack (cancel + instant
 * forceSilentRotations) -> EventMove.Pre/Post (spoofed movement packet) -> EventHandleInput.Early
 * (replay attack) -> EventOnAttackEntity.Post (restore previous silent rotation or release).
 *
 * LB mapping and intentional deviations:
 * - Staging hooks [AttackEntityEvent] (MultiPlayerGameMode.attack), which covers manual clicks
 *   AND module attacks (e.g. KillAura via attackEntity()) - a superset of echo's startAttack-only
 *   hook. The arm swing of a manual click still happens on the stage tick (vanilla swings after
 *   gameMode.attack regardless of our cancel); the replay swings again like echo does.
 * - The rotation spoof uses the sanctioned rotation system: a [RotationTarget] with empty
 *   processors (instant snap), [MovementCorrection.SILENT] (movement/velocity/jump correction is
 *   applied centrally by the rotation system) and `ticksUntilReset = 1`, requested at
 *   [Priority.IMPORTANT_FOR_USAGE_3] so it overrides KillAura's aim for that single packet
 *   (echo's forceSilentRotations overrides any silent rotation unconditionally). Restoring is
 *   automatic: when the request expires, a lower-priority target (e.g. KillAura's) takes over
 *   again - echo's "restore previous silent rotation" - or, with no other target, the rotation
 *   is released instantly - echo's release-to-player-rotation.
 * - Because rotation targets materialize on the next RotationManager update, the spoofed packet
 *   goes out one tick after staging and the replay one tick after that (echo replays one tick
 *   earlier via its synchronous rotation spoof).
 */
object ModuleKnockbackDisplace : ClientModule("KnockbackDisplace", ModuleCategories.COMBAT) {

    /**
     * Echo: ATTACK_TIMEOUT_TICKS - decremented each tick the replay is pending; below zero the
     * staged attack is dropped.
     */
    private const val ATTACK_TIMEOUT_TICKS = 1

    /**
     * LB-specific safeguard: ticks we allow the spoofed movement packet to take before giving up.
     * Echo spoofs synchronously and cannot get stuck here; in LB the rotation target could be
     * dropped (e.g. rotation updates paused) or the movement packet suppressed by another module.
     */
    private const val SPOOF_TIMEOUT_TICKS = 3

    /**
     * How close the on-wire silent rotation has to be to the queued spoof rotation to count as
     * "the server saw the spoofed yaw". Covers the GCD snapping the rotation pipeline applies.
     */
    private const val SPOOF_MATCH_TOLERANCE_DEG = 5f

    private val supportKnockbackEnchant by boolean("KnockbackEnchant", true)

    private val direction = modes(this, "Direction", Back, arrayOf(Left, Right, Back))
    private val yawOffset by float("YawOffset", 90.0f, 0.0f..180.0f)

    private var waitingForSpoofedMovement = false
    private var readyToReplayAttack = false
    private var replayingAttack = false

    private var pendingTargetId = -1
    private var timeoutTicks = 0
    private var spoofTicks = 0

    private var queuedYaw = 0.0f
    private var queuedPitch = 0.0f
    private var spoofPlan: RotationTarget? = null

    override fun onDisabled() {
        // The rotation request is dropped automatically once the module is no longer running;
        // RotationManager then releases (or hands back to a lower-priority target) on its own.
        resetState()
    }

    override val tag: String
        get() = direction.activeMode.tagName

    /**
     * Echo's EventStartAttack handler: gate, snapshot the spoof rotation, cancel the attack.
     *
     * Runs at the highest priority so the cancel is visible to any handler that checks it.
     */
    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent>(priority = FIRST_PRIORITY) { event ->
        if (!shouldStageAttack(event)) {
            return@handler
        }

        val player = mc.player ?: return@handler
        val target = event.entity

        // Echo bases the spoof on the client (camera) yaw, even when a silent rotation is active.
        queuedYaw = getQueuedYaw(player.yRot)
        queuedPitch = player.xRot

        val plan = RotationTarget(
            rotation = Rotation(queuedYaw, queuedPitch),
            entity = target,
            // No processors: the rotation snaps to the spoof on the next update and is released
            // instantly (or overtaken by a lower-priority target) when the request expires.
            ticksUntilReset = 1,
            resetThreshold = 1.0f,
            considerInventory = false,
            movementCorrection = MovementCorrection.SILENT
        )
        RotationManager.setRotationTarget(plan, Priority.IMPORTANT_FOR_USAGE_3, this)

        // If the rotation system refused the request (rotation updates paused or something with
        // even higher priority is aiming), let the attack go through normally instead of eating it.
        if (RotationManager.activeRotationTarget !== plan) {
            return@handler
        }

        spoofPlan = plan
        pendingTargetId = target.id
        timeoutTicks = ATTACK_TIMEOUT_TICKS
        spoofTicks = 0
        waitingForSpoofedMovement = true
        readyToReplayAttack = false
        replayingAttack = false

        event.cancelEvent()
    }

    /**
     * Echo's EventMove.Post (sendPosition RETURN, lowest priority): once the movement packet that
     * carried the spoofed rotation is on the wire, the staged attack becomes ready to replay.
     */
    @Suppress("unused")
    private val movePostHandler = handler<PlayerNetworkMovementTickEvent>(
        priority = READ_FINAL_STATE
    ) { event ->
        if (event.state != EventState.POST || !waitingForSpoofedMovement) {
            return@handler
        }

        // The packet only carried the spoof if our plan is the active target AND the silent
        // rotation that was substituted into the packet matches the queued rotation.
        val current = RotationManager.currentRotation ?: return@handler
        if (RotationManager.activeRotationTarget !== spoofPlan) {
            return@handler
        }
        if (!current.approximatelyEquals(Rotation(queuedYaw, queuedPitch), SPOOF_MATCH_TOLERANCE_DEG)) {
            return@handler
        }

        waitingForSpoofedMovement = false
        readyToReplayAttack = true
    }

    /**
     * Echo's EventHandleInput.Early: replay the delayed attack at the start of the next client
     * tick - after RotationManager has already released the spoof for this tick's movement packet
     * (the handler priority is below [FIRST_PRIORITY]), but before that packet is sent. The attack
     * packet therefore reaches the server while its last known rotation is still the spoofed one.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> { _ ->
        if (waitingForSpoofedMovement && ++spoofTicks > SPOOF_TIMEOUT_TICKS) {
            // The spoofed movement packet never went out (LB-specific safeguard, see constant).
            resetState()
            return@handler
        }

        if (!readyToReplayAttack) {
            return@handler
        }

        timeoutTicks--
        if (timeoutTicks < 0) {
            resetState()
            return@handler
        }

        val target = getPendingTarget()
        if (target == null || !canReplayAttack(target)) {
            resetState()
            return@handler
        }

        val player = mc.player
        val gameMode = mc.gameMode
        if (player == null || gameMode == null) {
            resetState()
            return@handler
        }

        readyToReplayAttack = false
        // Guards shouldStageAttack against the AttackEntityEvent our own replay fires.
        replayingAttack = true

        gameMode.attack(player, target)
        if (!player.isSpectator) {
            player.swing(InteractionHand.MAIN_HAND)
        }

        // Rotation restore is handled by the rotation system (request already expired);
        // echo does this manually in its EventOnAttackEntity.Post handler.
        clearState()
    }

    /**
     * Echo's shouldStageAttack, in the original order. The player-identity check is structural in
     * LB (the event is only fired for the local player) and echo has no trigger setting.
     */
    @Suppress("ReturnCount", "ComplexCondition")
    private fun shouldStageAttack(event: AttackEntityEvent): Boolean {
        if (event.isCancelled) return false
        if (waitingForSpoofedMovement || readyToReplayAttack || replayingAttack) return false

        val player = mc.player ?: return false
        if (mc.level == null) return false

        val target = event.entity as? LivingEntity ?: return false
        if (!target.shouldBeAttacked()) return false
        if (player.getAttackStrengthScale(0.5f) <= 0.9f) return false
        if (target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) >= 1.0) return false
        if (target.isBlocking) return false
        if (!hasExtraKnockback()) return false
        if (!canReplayAttack(target)) return false

        return true
    }

    private fun getPendingTarget(): Entity? {
        val level = mc.level ?: return null
        return level.getEntity(pendingTargetId)
    }

    /**
     * Echo's canReplayAttack: target still attackable, weapon usable and target in vanilla attack
     * range (echo's AttackRangeCompat resolves to Player.isWithinAttackRange on MC >= 26.1).
     */
    @Suppress("ReturnCount")
    private fun canReplayAttack(target: Entity?): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val livingTarget = target as? LivingEntity ?: return false
        if (mc.gameMode == null) return false
        if (!livingTarget.shouldBeAttacked()) return false

        val weapon = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (!weapon.isItemEnabled(level.enabledFeatures())) return false
        if (player.isHandsBusy) return false
        if (player.cannotAttackWithItem(weapon, 0)) return false

        return player.isWithinAttackRange(weapon, livingTarget.boundingBox, 0.0)
    }

    private fun hasExtraKnockback(): Boolean {
        val player = mc.player ?: return false
        if (player.isSprinting) return true

        if (!supportKnockbackEnchant) return false

        val weapon = player.getItemInHand(InteractionHand.MAIN_HAND)
        val knockbackHolder = mc.level?.registryAccess()
            ?.get(Enchantments.KNOCKBACK)?.orElse(null) ?: return false

        return EnchantmentHelper.getItemEnchantmentLevel(knockbackHolder, weapon) > 0
    }

    private fun getQueuedYaw(baseYaw: Float): Float = when (direction.activeMode) {
        Left -> Mth.wrapDegrees(baseYaw - yawOffset)
        Right -> Mth.wrapDegrees(baseYaw + yawOffset)
        else -> Mth.wrapDegrees(baseYaw + 180.0f)
    }

    private fun resetState() {
        clearState()
    }

    private fun clearState() {
        waitingForSpoofedMovement = false
        readyToReplayAttack = false
        replayingAttack = false
        pendingTargetId = -1
        timeoutTicks = 0
        spoofTicks = 0
        spoofPlan = null
    }

    object Left : DisplaceDirection("Left") {
        override val tagName: String
            get() = "Left ${Math.round(yawOffset)}"
    }

    object Right : DisplaceDirection("Right") {
        override val tagName: String
            get() = "Right ${Math.round(yawOffset)}"
    }

    object Back : DisplaceDirection("Back") {
        override val tagName: String
            get() = "Back"
    }

    abstract class DisplaceDirection(name: String) : Mode(name) {
        abstract val tagName: String
        override val parent: ModeValueGroup<*>
            get() = direction
    }

}
