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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoWeapon
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAnchorOptimizer
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoWeb
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.CriticalsSelectionMode
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget.ModuleElytraTarget
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraSpearReach
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup.KillAuraRotationTiming.GRIM_SNAP
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup.KillAuraRotationTiming.ON_TICK
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup.KillAuraRotationTiming.SNAP
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.TRACE_ALL
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.TRACE_NONE
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.TRACE_ONLYENEMY
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.waitTicks
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraAutoBlock
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing.dealWithFakeSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFightBot
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.failedHits
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.renderFailedHits
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraRange
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraRangeIndicator
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraSprint
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes.GenericDebugRecorder
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.minecraft.world.InteractionHand
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.CloneAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceFanning
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.combat.WallHitBypass
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.isHitBehindWall
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager.isInventoryOpen
import net.ccbluex.liquidbounce.utils.inventory.isInContainerScreen

import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.pointAtProportion
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput

import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.ModuleAutoAnchor
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.ModuleMainHandTotem
import net.ccbluex.liquidbounce.features.module.modules.world.ModuleSurround

/**
 * KillAura module
 *
 * Automatically attacks enemies.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object ModuleKillAura : ClientModule("KillAura", ModuleCategories.COMBAT) {

    // Attack speed
    val clicker = tree(KillAuraClicker)
    val range = tree(KillAuraRange)
    val targetTracker = tree(KillAuraTargetTracker)

    // Rotation
    private val rotations = tree(KillAuraRotationsValueGroup)
    private val pointTracker = tree(PointTracker(this))

    private val requires by multiEnumChoice<KillAuraRequirements>("Requires")

    private val requirementsMet
        get() = requires.all { it.asBoolean }

    // Bypass techniques
    internal val raycast by enumChoice("Raycast", TRACE_ALL)
    internal val criticalsSelectionMode by enumChoice("Criticals", CriticalsSelectionMode.SMART)
    private val keepSprint by boolean("KeepSprint", true)

    /**
     * Disguises strikes whose hit point sits behind a block (only reachable because of
     * [KillAuraRange.interactionThroughWallsRange]). Hits with line of sight are never touched.
     */
    private val wallBypass = tree(WallHitBypass(this))

    // Inventory Handling
    internal val ignoreOpenInventory by boolean("IgnoreOpenInventory", true)
    internal val simulateInventoryClosing by boolean("SimulateInventoryClosing", true)

    /**
     * The use of suspend [waitTicks] is a bit too
     * risky for a large and complex module
     * such as KillAura. So back to the basics.
     */
    internal var waitTicks = 0

    /**
     * Human reaction delay before the first strike. Once the aim first lands on a hittable target,
     * KillAura waits a randomized [HitDelay.delay] (ms, sampled from-to) before the first hit, so a
     * fresh engagement doesn't fire the instant the crosshair snaps on — it reacts like a person.
     * Re-rolled every time a target is (re-)acquired; subsequent hits on the same locked target are
     * governed by the normal clicker CPS, not this delay.
     */
    object HitDelay : ToggleableValueGroup(this, "HitDelay", false) {
        val delay by intRange("Delay", 150..250, 0..2000, "ms")
    }

    // Nanotime the aim first became hittable this engagement (0 = not currently acquired), plus the
    // reaction delay rolled for it.
    private var aimAcquiredAt = 0L
    private var rolledHitDelayMs = 0

    init {
        tree(KillAuraAutoBlock)
        tree(HitDelay)
        tree(TargetRenderer(this) {
            targetTracker.target?.takeUnless { ModuleElytraTarget.isSameTargetRendering(it) }
        })
        tree(KillAuraFailSwing)
        tree(KillAuraFightBot)
        tree(KillAuraRangeIndicator)
        tree(KillAuraSpearReach)
        tree(KillAuraSprint)
    }

    /**
     * Whether a higher module of the aura chain is steering the view right now, in which case this one files no
     * rotation request at all.
     *
     * The chain is **KillAura < AutoAnchor < CrystalAura**, set by the user on 2026-09-11 and ordered by how often
     * each module can act, inverted: this one can swing at anything in reach and so gets its pick nearly every
     * tick, an anchor at the victim's feet almost always clears its damage floor, and a crystal needs both a
     * pedestal and enough damage - so the pickiest wins, because its chance is the rare one.
     *
     * `ModuleSurround` sits above all three (user, 2026-09-27) but is deliberately NOT part of this flag: it wins the
     * aim on priority (40), so it holds only the attack - see `canAttack` in `gameHandler`.
     *
     * The test is WORKING, not enabled and not aiming. `isRotating` was tried first and was too broad: CrystalAura
     * holds the aim whenever a breakable crystal is in range, so this module stood down for an aura that was
     * merely switched on. `isActing` is true only while a cycle is actually in progress - a packet sent within the
     * last few ticks, or a placement still in flight - so an aura that has nothing to do gets out of the way.
     *
     * Read on BOTH halves of the chain: the rotation requests below, and the attack itself in `gameHandler`.
     * Gating only the rotation left the packet order inverted - see the comment on `canAttack`.
     *
     * Abstaining rather than filing a losing request is deliberate, and is what this module already did for
     * CrystalAura alone. A lower-priority request would be arbitrated away by `RequestHandler` anyway, but
     * abstaining is deterministic where a priority comparison is not: `ModuleAutoAnchor` requests at the same
     * `IMPORTANT_FOR_USAGE_2` this module does, and `RequestHandler` orders by priority alone.
     */
    private val anAuraIsWorking: Boolean
        get() = ModuleCrystalAura.isActing || ModuleAutoAnchor.isActing

    override fun onDisabled() {
        targetTracker.reset()
        failedHits.clear()
        KillAuraNotifyWhenFail.failedHitsIncrement = 0
        resetHitDelay()
        KillAuraSprint.reset()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        renderFailedHits(event.matrixStack)
        renderEnvironmentForWorld(event.matrixStack) {
            KillAuraRangeIndicator.render(this, event.partialTicks)
        }
    }

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        if (waitTicks > 0) {
            waitTicks--
        }

        val isInInventoryScreen = isInventoryOpen || mc.screen is ContainerScreen
        val shouldResetTarget = player.isSpectator || player.isDeadOrDying || !requirementsMet

        if (isInInventoryScreen && !ignoreOpenInventory || shouldResetTarget) {
            targetTracker.reset()
            return@handler
        }

        updateTarget()

        ModuleAutoWeapon.onTarget(targetTracker.target)
    }

    @Suppress("unused")
    private val gameHandler = tickHandler {
        if (player.isDeadOrDying || player.isSpectator) {
            return@tickHandler
        }

        // Check if there is target to attack
        val target = targetTracker.target

        if (CombatManager.shouldPauseCombat) {
            KillAuraAutoBlock.stopBlocking()
            return@tickHandler
        }

        if (target == null) {
            // No target — reset the reaction timer so the next engagement re-rolls its delay,
            // and drop a sprint reset that has nothing left to reset for.
            resetHitDelay()
            KillAuraSprint.reset()

            val hasUnblocked = KillAuraAutoBlock.stopBlocking()

            if (KillAuraFailSwing.enabled && requirementsMet) {
                if (hasUnblocked && KillAuraAutoBlock.pauseOnUnblockTicks > 0)
                { waitTicks = KillAuraAutoBlock.pauseOnUnblockTicks
                } else {
                    dealWithFakeSwing(null)
                }
            }
            return@tickHandler
        }

        if (!requirementsMet) {
            return@tickHandler
        }

        val rotation = (if (rotations.effectiveRotationTiming == ON_TICK) {
            findRotation(target, range.interactionRange, range.interactionThroughWallsRange)?.rotation
        } else {
            null
        } ?: RotationManager.currentRotation ?: player.rotation).normalize()

        val crosshairTarget = when {
            raycast != TRACE_NONE -> {
                findEntityInCrosshair(range.interactionRange.toDouble(), rotation, predicate = {
                    when (raycast) { TRACE_ONLYENEMY -> it.shouldBeAttacked()
                        TRACE_ALL -> true
                        else -> false
                    }
                })?.entity ?: target
            }
            else -> target
        }

        if (crosshairTarget is LivingEntity && crosshairTarget.shouldBeAttacked() && crosshairTarget != target) {
            targetTracker.target = crosshairTarget
        }

        // ElytraTarget flies the player itself, so dropping sprint underneath it would fight it for
        // control — gated on isControlling, not running, because ElytraTarget counts as running the
        // moment it is enabled, including on foot.
        val sprintAllowsAttack = if (ModuleElytraTarget.isControlling) {
            true
        } else {
            KillAuraSprint.update(canAttack = true)
        }

        // A hand swap stands the aura's click down for its window ([HandSwapGate]): rotation, target and sprint
        // keep running, only the attack waits. Read HERE and not at the packet - the blanket `AttackEntityEvent`
        // veto would still let the clicker spend its click, the hit delay reset and `hasBlockedSinceAttack` on a
        // swing that never left. Applied AFTER the sprint update so its state machine keeps seeing the input it
        // has always been given from here.
        // `ModuleMainHandTotem` is read on the same line and for the same reason: while it holds a totem in the
        // main hand the aura would be swinging a totem, which wastes the hit and announces the module. Rotation,
        // target and sprint keep running exactly as they do for the swap window.
        // And the aura chain ([anAuraIsWorking]), for the same reason and on the same line. Until 2026-09-20 the
        // chain gated only this module's ROTATION requests, so at the packet level it ran backwards: the attack
        // leaves from this tick handler (priority 0), which is after AutoAnchor's PLAN (the rotation pipeline runs
        // at FIRST_PRIORITY) and before its EXECUTE at the return of `handleKeybinds` - so the anchor saw
        // `foreignEntitySeen` and gave up the tick to the module it is documented to outrank. Block and entity
        // interaction in one tick window is also what `MultiActionsF` reads.
        // `ModuleAnchorOptimizer` is NOT part of [anAuraIsWorking] and must not be: it requests at
        // `IMPORTANT_FOR_PLAYER_LIFE` (40) against our 30, so it already wins the aim on priority, and abstaining
        // from the rotation for it would only buy a reset tick each time it stops. What it does need is this -
        // the packet. Its click and our attack in one window is `MultiActionsF` all the same.
        // `ModuleSurround` is the top of the aura chain (user, 2026-09-27) and is held here for the same reason and
        // in the same way as AnchorOptimizer: its placer also requests at 40, so only the attack waits.
        val canAttack = sprintAllowsAttack &&
            !HandSwapGate.holdsAttacks &&
            !ModuleMainHandTotem.isBusy &&
            !ModuleAnchorOptimizer.isActing &&
            !ModuleSurround.isActing &&
            !anAuraIsWorking

        attackTarget(crosshairTarget, rotation, canAttack)
    }

    private var heldFanProp: Vec3? = null
    private var fanTicksLeft = 0

    /**
     * Holds ModuleSprint (and the vanilla input path) off for the length of a Legit reset. Default
     * priority, so it runs after ModuleSprint's CRITICAL_MODIFICATION handler and gets the last
     * word. The NETWORK source is left alone on purpose: during the release the server *should*
     * learn that we stopped sprinting, which vanilla reports on its own.
     */
    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (KillAuraSprint.blockSprint && (event.source == SprintEvent.Source.MOVEMENT_TICK ||
                event.source == SprintEvent.Source.INPUT)) { event.sprint = false
        }
    }

    /**
     * The "kill the motion" half of the Legit reset — the same thing a released W does.
     */
    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (KillAuraSprint.isMotionStopped) {
            event.directionalInput = DirectionalInput.NONE
        }
    }

    @Suppress("unused")
    private val sprintTickHandler = handler<GameTickEvent> {
        KillAuraSprint.tick()
    }

    /**
     * How far to aim: whatever the hotbar can actually deliver, not just what is held. Without
     * this a target that a borrowed spear could reach is discarded before [KillAuraSpearReach] is
     * ever consulted.
     */
    private fun aimRange() = maxOf(range.interactionRange, KillAuraSpearReach.bestAvailableReach)

    /**
     * Slot to borrow for this strike, resolved once per attempt from a raytrace that confirms the
     * item would actually connect. Engaged just before the attack, because
     * `MultiPlayerGameMode.attack` announces the carried slot and the server resolves reach against
     * whatever it then believes is held.
     */
    private fun spearReachSlot(target: Entity, rotation: Rotation): Int? {
        val slot = KillAuraSpearReach.bestSlotFor(
            target,
            rotation,
            range.interactionThroughWallsRange.toDouble()
        )
        debugParameter("Spear Reach Slot") { slot }
        return slot
    }

    private fun isWithinAttackRange(hitLocation: Vec3?, reachSlot: Int?): Boolean {
        if (ModuleElytraTarget.canIgnoreKillAuraRotations) {
            return true
        }

        val location = hitLocation ?: return false
        return range.isInRange(pos = location) || reachSlot != null
    }

    /**
     * Sends a swing the clone asked for but which cannot land.
     *
     * The aura only lets an attack packet through once its own raytrace says the crosshair is on
     * the hitbox, so it never misses — and a swing that always connects is not a subtle tell but a
     * recorded field: Shard 2.0 collects `raytrace_hit` and `raytrace_miss_distance` per attack.
     *
     * To be precise about what the model knows: its attack label was a real `ServerboundAttackPacket`,
     * which vanilla only sends when the client raytrace hits an entity — so pure air-clicks were
     * never labelled, and the head is not literally reproducing recorded whiffs. What it does
     * reproduce is the *pairing* of clicking with crosshair position, and that pairing has real
     * probability mass on "clicked while not quite on the box". When such a decision fires here,
     * this puts on the wire what that click would have produced from a human hand: the swing
     * animation with no attack packet behind it. Only the Clone smoother reaches this — every
     * other mode keeps the old behaviour untouched.
     */
    private fun swingCloneMiss(target: Entity) {
        val clone = rotations.activeAngleSmooth as? CloneAngleSmooth ?: return
        if (clone.attackDecisionThisTick != true) {
            return
        }

        // Vanilla refuses to swing again for ten ticks after a miss, and every other clicker in
        // the client reads the same global counter. Writing it without first honouring it turned
        // this into an unbounded swing every tick the crosshair was off — a blatant signal in its
        // own right, and it froze the schedulers of unrelated modules for as long as it lasted.
        if (mc.missTime > 0) {
            return
        }

        // The same guards `dealWithFakeSwing` applies. A swing while a container is open or an
        // item is in use is not something a player produces, and it is exactly what the checks
        // watching for interaction-while-busy are looking for.
        if (mc.screen != null || player.isUsingItem) {
            return
        }
        if (target.isRemoved || target !is LivingEntity || target.isDeadOrDying) {
            return
        }

        KillAuraAutoBlock.makeSeemBlock()

        if (interaction.hasMissTime()) {
            mc.missTime = 10
        }
        player.swing(InteractionHand.MAIN_HAND)
    }

    /**
     * Target is not hittable this tick: reset the reaction delay, then either keep blocking while it
     * is merely within scan range, or unblock and optionally fake a swing.
     */
    private fun handleOutOfRange(target: Entity) {
        resetHitDelay()

        if (KillAuraAutoBlock.enabled && KillAuraAutoBlock.onScanRange &&
            player.squaredBoxedDistanceTo(target) <= range.scanRange.sq()) {
            if (KillAuraClicker.ticksSinceLastClick >= KillAuraAutoBlock.reblockTicks) {
                KillAuraAutoBlock.startBlocking()
            }

            return
        }

        val hasUnblocked = KillAuraAutoBlock.stopBlocking()
        if (hasUnblocked && KillAuraAutoBlock.pauseOnUnblockTicks > 0) {
            waitTicks = KillAuraAutoBlock.pauseOnUnblockTicks
        } else if (KillAuraFailSwing.enabled) {
            dealWithFakeSwing(target)
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun attackTarget(target: Entity, rotation: Rotation, canAttack: Boolean) {
        KillAuraAutoBlock.makeSeemBlock()
        debugParameter("Rotation") { rotation }
        debugParameter("Target") { target.scoreboardName }
        // GrimSnap strikes with the rotation actually ON THE WIRE: the click gate raytraces
        // with the server-known rotation, so a hit can only fire while the snap has genuinely
        // arrived on the hitbox — never off a client-side plan the server has not seen yet.
        val gateRotation = if (rotations.effectiveRotationTiming == GRIM_SNAP) {
            RotationManager.serverRotation
        } else {
            rotation
        }
        val attackHitResult = isLookingAtEntity(
            toEntity = target, rotation = gateRotation,
            range = aimRange().toDouble(),
            throughWallsRange = range.interactionThroughWallsRange.toDouble()
        )
        debugParameter("Target Hit Result") { attackHitResult?.location }

        val reachSlot = spearReachSlot(target, rotation)
        val isInRange = isWithinAttackRange(attackHitResult?.location, reachSlot)
        debugParameter("Is In Range") { isInRange }

        // A through-wall hit is one whose hit point has no line of sight from our eyes; only
        // those get the bypass wrapper, a plain visible strike stays a plain attack packet.
        val behindWall = attackHitResult?.location?.let(::isHitBehindWall) == true
        debugParameter("Behind Wall") { behindWall }

        if (!isInRange) {
            swingCloneMiss(target)
            handleOutOfRange(target)
            return
        }

        debugParameter("Valid Rotation") { rotation }

        val mainHandStack = player.mainHandItem


        if (!hitDelayElapsed()) {
            if (KillAuraClicker.ticksSinceLastClick >= KillAuraAutoBlock.reblockTicks) {
                KillAuraAutoBlock.startBlocking()
            }
            return
        }

        if (canAttack && clicker.isClickTick && canAttackNow(target, mainHandStack) &&
            !KillAuraAutoBlock.isPrioritizingBlocking) {
            clicker.prepareForAttack(rotation) {
                if (!canAttackNow(target, mainHandStack)) {
                    return@prepareForAttack false
                }

                KillAuraSpearReach.engage(reachSlot)

                if (behindWall) {
                    wallBypass.attack(target, rotation) {
                        attackEntity(target, SwingMode.DO_NOT_HIDE, keepSprint)
                    }
                } else {
                    attackEntity(target, SwingMode.DO_NOT_HIDE, keepSprint)
                }
                KillAuraSprint.postAttack()

                range.update()
                KillAuraNotifyWhenFail.failedHitsIncrement = 0
                KillAuraAutoBlock.hasBlockedSinceAttack = false

                GenericDebugRecorder.recordDebugInfo(ModuleKillAura, "attackEntity", JsonObject().apply {
                    add("player", GenericDebugRecorder.debugObject(player))
                    add("targetPos", GenericDebugRecorder.debugObject(target))
                })

                true
            }
        } else if (KillAuraClicker.ticksSinceLastClick >= KillAuraAutoBlock.reblockTicks) {
            KillAuraAutoBlock.startBlocking()
        }
    }

    private fun updateTarget() {
        // Refreshed FIRST: `targets()` is what updates `closestSquaredEnemyDistance`, and the range
        // below is chosen from it. Read before the refresh it was last tick's distance, so the tick
        // an opponent stepped out past the interaction range still searched only that range, found
        // nobody, and dropped the target for a tick - re-acquired on the next one at scan range.
        // Measured 2026-09-29 with Clone's AimLog: 27 of 40 target losses in a fight came back within
        // two ticks, 25 of them at a boxed distance of 3-4 blocks. Each such tick reset the rotation
        // (a hand-back towards the camera) and wiped Clone's recurrent memory.
        val candidates = targetTracker.targets()

        val maximumRange = if (targetTracker.closestSquaredEnemyDistance > range.interactionRange.sq()) {
            range.scanRange
        } else {
            range.interactionRange
        }

        debugParameter("Maximum Range") { maximumRange }
        debugParameter("Range") { range }
        val squaredMaxRange = maximumRange.sq()
        val squaredNormalRange = range.interactionRange.sq()

        // The target we already have is tried FIRST, and kept for as long as it still qualifies.
        //
        // Without that the choice is recomputed from scratch every tick, so in a crowd the aura
        // drifts between whoever happens to sort highest — and every switch throws away the aim
        // progress and the cooldown build-up spent on the previous one. Sorting is only consulted
        // when the current target has actually stopped being viable.
        //
        // Ordering rather than a special case, because `processTarget` is not a predicate: it
        // installs the rotation for whoever it accepts. Putting the incumbent first means it is the
        // one asked first, and nothing else changes.
        val current = targetTracker.target

        val target = candidates
            .filter { entity -> entity.squaredBoxedDistanceTo(player) <= squaredMaxRange }
            .sortedWith(
                compareByDescending<LivingEntity> { entity -> entity === current }
                    .thenBy { entity ->
                        if (entity.squaredBoxedDistanceTo(player) <= squaredNormalRange) 0 else 1
                    }
            )
            .firstOrNull { entity -> processTarget(entity, maximumRange, range.interactionThroughWallsRange) }

        if (target != null) {
            targetTracker.target = target
        } else if (KillAuraFightBot.enabled) {
            KillAuraFightBot.updateTarget()

            if (!anAuraIsWorking) {
                RotationManager.setRotationTarget(
                    rotations.toRotationTarget(
                        KillAuraFightBot.getMovementRotation(),
                        considerInventory = !ignoreOpenInventory
                    ),
                    priority = Priority.IMPORTANT_FOR_USAGE_2,
                    provider = ModuleKillAura
                )
            }
        } else {
            targetTracker.reset()
        }
    }

    @Suppress("ReturnCount")
    private fun processTarget(
        entity: LivingEntity,
        range: Float,
        wallsRange: Float
    ): Boolean {
        val (rotation, _) = findRotation(entity, range, wallsRange) ?: return false
        val ticks = rotations.calculateTicks(rotation)
        debugParameter("Rotation Ticks") { ticks }

        when (rotations.effectiveRotationTiming) {

            // SNAP holds the player's natural rotation until a click is imminent, then hands the
            // rotation target to the regular RotationManager pipeline. The chosen AngleSmooth
            // does the actual motion (Linear 180/180 → 1-tick snap; slower smoother → brief
            // multi-tick aim). The lead window is randomized per tick so two attacks never fire
            // at the same pre-click offset.
            SNAP -> if (!rotations.snapGateOpen()) {
                return true
            }

            // [ON_TICK] will always instantly aim onto the target on attack, however, if
            // our rotation is unable to be ready in time, we can at least start aiming towards
            // the target.
            ON_TICK -> if (ticks <= 1) {
                return true
            }

            // [GRIM_SNAP] keeps the aim on the cursor between hits; it only feeds the
            // RotationManager while the weapon will be ready within the snap window AND the
            // target is actually inside striking distance. Out of reach there is nothing to
            // snap for — holding an unhittable target on the crosshair is exactly what this
            // mode exists to avoid, so the override simply expires and the aim walks home.
            GRIM_SNAP -> if (!rotations.grimSnapGateOpen() ||
                entity.squaredBoxedDistanceTo(player) > aimRange().sq()
            ) {
                return true
            }

            else -> {
                // Continue with regular aiming
            }
        }

        // Yield the aim to whichever aura is mid-cycle right now; see [anAuraIsWorking].
        if (!anAuraIsWorking) {
            RotationManager.setRotationTarget(
                rotations.toRotationTarget(
                    rotation,
                    entity,
                    considerInventory = !ignoreOpenInventory
                ),
                priority = Priority.IMPORTANT_FOR_USAGE_2,
                provider = this@ModuleKillAura
            )
        }
        return true
    }

    /**
     * Get the best spot to attack the entity
     *
     * @param entity The entity to attack
     * @param range The range to attack the entity (NOT SQUARED)
     *
     *  @return The best spot to attack the entity
     */
    private fun findRotation(entity: Entity, range: Float, wallsRange: Float): RotationWithVector? {
        val eyes = player.eyePosition
        val point = pointTracker.findPoint(eyes, entity)

        debugGeometry("Box") { ModuleDebug.DebuggedBox(point.box, Color4b.ORANGE.with(a = 90)) }
        debugGeometry("Point") { ModuleDebug.DebuggedPoint(point.pos, Color4b.WHITE, size = 0.1) }

        // Strong humanizers (Human, Chaotic) get a fanning aim-point picker: many random spots
        // on the hitbox, then a random pick from the few closest to the current rotation. The
        // chosen anchor is HELD (as box-relative proportions, so it tracks the moving target) for a
        // short window rather than re-rolled every tick — a per-tick re-roll gives the smoother a
        // new target each tick, so a path-based smoother (Human) never converges and the crosshair
        // wanders without locking on. Re-rolling on a window gives a fresh anchor every few ticks
        // while staying stable enough to actually aim.
        if (rotations.prefersFannedAimPoint) {
            val currentRotation = RotationManager.currentRotation ?: player.rotation

            if (heldFanProp == null || fanTicksLeft <= 0) {
                val fanned = raytraceFanning(
                    eyes = eyes,
                    box = point.box,
                    currentRotation = currentRotation,
                    range = range.toDouble(),
                    wallsRange = wallsRange.toDouble(),
                ) ?: if (rotations.aimThroughWalls) {
                    raytraceFanning(
                        eyes = eyes,
                        box = point.box,
                        currentRotation = currentRotation,
                        range = range.toDouble(),
                        wallsRange = range.toDouble(),
                    )
                } else {
                    null
                }

                if (fanned != null) {
                    heldFanProp = boxProportionOf(fanned.vec, point.box)
                    fanTicksLeft = (5..9).random()
                }
            }
            fanTicksLeft--

            val prop = heldFanProp
            if (prop != null) {
                // Aim at the stable held spot via the normal box raytrace (handles range/visibility
                // and box clamping). If it's no longer reachable, drop it so we re-roll next tick.
                val spot = point.box.pointAtProportion(prop.x, prop.y, prop.z)
                val pref = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, spot)
                val fannedRotation = raytraceBox(
                    eyes = eyes,
                    box = point.box,
                    range = range.toDouble(),
                    wallsRange = wallsRange.toDouble(),
                    rotationPreference = pref,
                ) ?: if (rotations.aimThroughWalls) {
                    raytraceBox(
                        eyes = eyes,
                        box = point.box,
                        range = range.toDouble(),
                        wallsRange = range.toDouble(),
                        rotationPreference = pref,
                    )
                } else {
                    null
                }

                if (fannedRotation != null) {
                    return fannedRotation
                }
                heldFanProp = null
            }
            // Fall through to the deterministic raytraceBox path if fanning produced nothing usable.
        }

        val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, point.pos)

        // raytrace to the point
        val rotation = raytraceBox(
            eyes = eyes,
            box = point.box,
            range = range.toDouble(),
            wallsRange = wallsRange.toDouble(),
            rotationPreference = rotationPreference
        )

        return if (rotation == null && rotations.aimThroughWalls) {
            val rotationThroughWalls = raytraceBox(
                eyes = eyes,
                box = point.box,
                // Since [range] is squared, we need to square root
                range = range.toDouble(),
                wallsRange = range.toDouble(),
                rotationPreference = rotationPreference
            )

            rotationThroughWalls
        } else {
            rotation
        }
    }

    /** Converts a world point on [box] to its clamped [0,1] proportion along each axis. */
    private fun boxProportionOf(v: Vec3, box: AABB): Vec3 = Vec3(
        if (box.xsize > 0) ((v.x - box.minX) / box.xsize).coerceIn(0.0, 1.0) else 0.5,
        if (box.ysize > 0) ((v.y - box.minY) / box.ysize).coerceIn(0.0, 1.0) else 0.5,
        if (box.zsize > 0) ((v.z - box.minZ) / box.zsize).coerceIn(0.0, 1.0) else 0.5,
    )

    /**
     * Check if we can attack the target at the current moment
     */
    internal fun canAttackNow(
        target: Entity? = null,
        itemStack: ItemStack = player.mainHandItem,
    ): Boolean {
        // AutoWeb has already spent this tick's interaction on a web in the target's own cell, and a
        // block interaction paired with an entity interaction inside one tick window is Grim
        // MultiActionsF / PacketOrderJ. It places at most two blocks per engagement and runs at the
        // head of the tick, so this costs an attack on those ticks and hands the aim straight back.
        if (ModuleAutoWeb.placedThisTick) {
            return false
        }

        if (!itemStack.isItemEnabled(world.enabledFeatures())) {
            return false
        }

        if (player.cannotAttackWithItem(itemStack, 0)) {
            return false
        }

        val criticalHitAllowed = target == null || player.isFallFlying ||
            criticalsSelectionMode.isCriticalHit(target)
        if (!criticalHitAllowed) {
            return false
        }

        val isInventoryBlockingAttack = (isInventoryOpen || isInContainerScreen) &&
            !ignoreOpenInventory && !simulateInventoryClosing
        return !isInventoryBlockingAttack
    }

    /**
     * Reaction-delay gate. Called every tick the aim is on a hittable target: on the first such
     * tick of an engagement it stamps [aimAcquiredAt] and rolls a delay from [HitDelay.delay];
     * returns true only once that many milliseconds have elapsed. Disabled group = always ready.
     */
    private fun hitDelayElapsed(): Boolean {
        // While the clone decides the clicks, its own reaction time is already in the sampled
        // distribution — the recording contains the player's real first-hit latency, and the
        // calibration reproduces their rate. Stacking the hand-rolled delay on top censors the
        // model's decisions the same way the clicker's recharge gate used to, and the deployed
        // click pattern stops being the one the evaluation scored.
        if ((rotations.activeAngleSmooth as? CloneAngleSmooth)?.attackDecisionThisTick != null) {
            aimAcquiredAt = 0L
            return true
        }

        if (!HitDelay.enabled) {
            aimAcquiredAt = 0L
            return true
        }

        val now = System.nanoTime()
        if (aimAcquiredAt == 0L) {
            aimAcquiredAt = now
            rolledHitDelayMs = HitDelay.delay.random()
        }

        val elapsedMs = (now - aimAcquiredAt) / 1_000_000L
        debugParameter("Hit Delay Left") { (rolledHitDelayMs - elapsedMs).coerceAtLeast(0L) }
        return elapsedMs >= rolledHitDelayMs
    }

    /** Clears the reaction-delay timer so the next target acquisition re-rolls the delay. */
    private fun resetHitDelay() {
        aimAcquiredAt = 0L
    }

    enum class RaycastMode(override val tag: String) : Tagged {
        TRACE_NONE("None"),
        TRACE_ONLYENEMY("Enemy"),
        TRACE_ALL("All")
    }

}
