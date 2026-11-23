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

package net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget

import net.ccbluex.liquidbounce.additions.shooter
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.player.offhand.ModuleOffhand
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/**
 * Follows and rams a target while gliding on an elytra.
 *
 * Ported from Catlean's `elytra-target`. The shape of the original is kept intact:
 *
 * - While not gliding, the elytra is re-opened in mid-air on a fixed tick delay ([AutoLaunch]).
 * - While gliding, the aim is driven straight at the target every tick — the wing steers by the
 *   look vector, so the rotation *is* the flight control.
 * - A target that is itself gliding is chased directly (optionally led by its velocity).
 * - A target that is **not** gliding is approached over a *peek* point instead: a scan for a
 *   direction with clear air around the target, so we swing past it rather than nosing into the
 *   ground next to it. See [ElytraTargetPeek].
 * - Firework rockets are used as thrust ([Boost]), including a reflex launch when the target
 *   swings at us.
 * - The rocket's thrust vector can be scaled per pitch angle ([ElytraTargetSpeed]).
 *
 * Two things are deliberately *not* faithful, because the original relies on client internals that
 * do not exist here:
 * - The original writes the player's rotation directly every tick. Here the aim goes through
 *   [RotationManager] like every other module, so it arbitrates against KillAura, Scaffold and
 *   friends. [RotationVisibility] picks between moving the real camera and steering silently.
 * - The original fires a rocket every single tick while `boost` is on. [Boost] adds a cooldown and
 *   a "not while one of ours is already burning" guard, otherwise a stack is gone in a second.
 */
object ModuleElytraTarget : ClientModule("ElytraTarget", ModuleCategories.COMBAT) {

    private val targetTracker = tree(ElytraTargetTracker)

    /**
     * The original pulls its target straight out of the aura. Keep that as the default so both
     * modules commit to the same player, but fall back to our own tracker when KillAura is off or
     * has nothing.
     */
    private val useKillAuraTarget by boolean("UseKillAuraTarget", true)
    private val playersOnly by boolean("PlayersOnly", true)

    private val rotations by enumChoice("Rotations", RotationVisibility.VISIBLE)

    /**
     * Where the attack-run aim comes from. See [killAuraDrivesAim].
     */
    private val rotationSource by enumChoice("RotationSource", RotationSource.KILL_AURA)

    /**
     * Ticks of target velocity added to the aim point. The original gates this behind
     * `always-predict`; with it off, a value of 0 disables leading entirely.
     */
    private val predict by float("Predict", 0f, 0f..10f, "ticks")
    private val alwaysPredict by boolean("AlwaysPredict", false)

    private val pauseWhileEating by boolean("PauseWhileEating", true)

    private val ignoreKillAuraRotations by boolean("IgnoreKillAuraRotation", true)

    init {
        tree(AutoLaunch)
        tree(Boost)
        tree(ElytraTargetPeek)
        tree(ElytraTargetSpeed)
    }

    private val targetRenderer = tree(TargetRenderer(this) { target })

    internal val target: LivingEntity?
        get() = targetTracker.target

    /**
     * True only while we are actually flying a target down. Cross-module hooks must test this and
     * not [running] — the module counts as running the moment it is enabled, including on foot,
     * and gating KillAura on that would change its behaviour on the ground.
     */
    val isControlling: Boolean
        get() = running && player.isFallFlying && targetTracker.target != null

    /**
     * True on the ticks we are actually driving the aim ourselves, rather than letting KillAura's
     * rotation steer the wing.
     */
    private var overridingRotation = false

    /**
     * Latched once KillAura is seen aiming with [MovementCorrection.OFF]. That correction makes the
     * gliding hook ignore the rotation entirely, so inheriting it would leave the wing unsteered
     * and the failure would be silent. Cleared when the target changes.
     */
    private var killAuraAimUnusable = false

    /**
     * Only meaningful while we override the aim. On a break-away the crosshair points away from the
     * target and KillAura cannot pass its own raycast, so it defers to us for the range decision.
     * When KillAura is the one aiming there is nothing to forgive — its raycast passes on its own.
     */
    val canIgnoreKillAuraRotations: Boolean
        get() = isControlling && ignoreKillAuraRotations && overridingRotation

    fun isSameTargetRendering(target: LivingEntity) =
        running && targetRenderer.enabled && targetTracker.target === target

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        Boost.tickCooldown()
        updateTarget()

        if (!player.isFallFlying) {
            overridingRotation = false
            AutoLaunch.tick()
            return@handler
        }

        AutoLaunch.reset()

        val target = targetTracker.target
        if (target == null || pauseWhileEating && player.isUsingItem) {
            overridingRotation = false
            return@handler
        }

        aimAt(target)

        if (Boost.running) {
            Boost.tryLaunch()
        }
    }

    /**
     * Aim origin and target are both taken at *feet* height, exactly as the original does. That
     * reads odd for a combat module but it is the right choice here: the elytra is steered by this
     * vector, and feet-to-feet keeps the approach flat instead of diving under a target we are
     * level with.
     */
    private fun aimAt(target: LivingEntity) {
        // A gliding target is tail-chased directly, as in the original. Reset the cycle rather than
        // leave its phase and cooldown snapshot stale for when the target lands again.
        val peekRotation = if (target.isFallFlying && !ElytraTargetPeek.appliesToGliders) {
            ElytraTargetPeek.reset()
            null
        } else {
            ElytraTargetPeek.rotationFor(target)
        }

        // Peek returns null on its attack run — that is the leg where KillAura may fly the wing.
        if (peekRotation == null && killAuraDrivesAim(target)) {
            overridingRotation = false
            return
        }

        overridingRotation = true

        val rotation = peekRotation ?: Rotation.lookingAt(predictedPosition(target), player.position())

        RotationManager.setRotationTarget(
            plan = RotationTarget(
                rotation = rotation,
                entity = target,
                // No smoothing: the rotation is a control surface, a smoother would lag the wing
                // behind the target and turn every correction into an overshoot.
                processors = listOf(InstantRotation),
                ticksUntilReset = 1,
                resetThreshold = 1f,
                // Never stop steering because a screen is open — that would drop the wing onto
                // whatever the real rotation happens to be, mid-flight.
                considerInventory = false,
                movementCorrection = rotations.correction
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_3,
            provider = this
        )
    }

    /**
     * Whether to hand the attack run over to KillAura's aim instead of requesting our own.
     *
     * The wing is steered by whatever rotation is active, and KillAura is already aiming at this
     * target with its own point tracker, smoothing and timing. Requesting a second rotation for the
     * same target only means two modules fighting over one tick — our higher priority wins, KillAura
     * then fails its own raycast, and `IgnoreKillAuraRotation` exists purely to paper over that.
     * Stepping back instead makes the aim KillAura's and the flight follows it for free. Same idea
     * as KillAura yielding to AutoCrystal while that is rotating.
     *
     * We simply stop requesting; our previous request expires a tick later and KillAura's — which it
     * files every tick regardless of who wins — becomes the active one. No reset path is involved,
     * because from [RotationManager]'s view there is still a live target every tick.
     *
     * The break-away leg never hands over: KillAura would never aim away from the target.
     */
    private fun killAuraDrivesAim(target: LivingEntity): Boolean {
        if (rotationSource != RotationSource.KILL_AURA || killAuraAimUnusable) {
            return false
        }

        // KillAura only holds a target once it has found a rotation for it and filed the request,
        // and it clears the target otherwise. So "holds our target" is exactly "is aiming at our
        // target" — outside its scan range, or with the module off, this is false and we aim
        // ourselves. That is the whole approach: we fly them in, KillAura takes over at the merge.
        if (!ModuleKillAura.running || ModuleKillAura.targetTracker.target !== target) {
            return false
        }

        // The one case where KillAura holds a target and still files nothing: it yields to
        // AutoCrystal. Stepping back there would hand the wing to an aim pointed at a crystal.
        if (ModuleCrystalAura.isRotating) {
            return false
        }

        // Checked only once we have already stepped back, because while we hold the aim the active
        // target is ours and says nothing about KillAura's correction.
        if (!overridingRotation) {
            val active = RotationManager.activeRotationTarget
            if (active != null && active.movementCorrection == MovementCorrection.OFF) {
                killAuraAimUnusable = true
                return false
            }
        }

        return true
    }

    private fun predictedPosition(target: LivingEntity): Vec3 {
        val position = target.position()

        if (!alwaysPredict && predict <= 0f) {
            return position
        }

        return position.add(target.deltaMovement.scale(predict.toDouble()))
    }

    private fun updateTarget() {
        val previous = targetTracker.target

        selectTarget()

        if (targetTracker.target !== previous) {
            // A fresh engagement re-tests whether KillAura's aim can steer.
            killAuraAimUnusable = false
        }
    }

    private fun selectTarget() {
        if (useKillAuraTarget) {
            val auraTarget = ModuleKillAura.targetTracker.target
            if (auraTarget != null && isSuitable(auraTarget)) {
                targetTracker.target = auraTarget
                return
            }
        }

        targetTracker.validate { isSuitable(it) }

        if (targetTracker.target == null) {
            targetTracker.selectFirst { isSuitable(it) }
        }
    }

    private fun isSuitable(entity: LivingEntity) =
        entity.isAlive && entity !== player && (!playersOnly || entity is Player)

    /**
     * Scales the thrust a firework applies to us, called from
     * [net.ccbluex.liquidbounce.injection.mixins.minecraft.entity.projectile.MixinFireworkRocketEntity].
     * Scaling the look vector scales both terms of the vanilla boost, which is what the original
     * does through its firework-vector event.
     */
    @JvmStatic
    fun scaleFireworkBoost(lookAngle: Vec3): Vec3 {
        if (!ElytraTargetSpeed.running) {
            return lookAngle
        }

        val multiplier = ElytraTargetSpeed.multiplierFor(lookAngle)
        return if (multiplier == 1f) lookAngle else lookAngle.scale(multiplier.toDouble())
    }

    override fun onDisabled() {
        targetTracker.reset()
        ElytraTargetPeek.reset()
        AutoLaunch.reset()
        Boost.reset()
        overridingRotation = false
        killAuraAimUnusable = false
    }

    /**
     * Re-opens the wing in mid-air. The original refuses to launch without a rocket in reach,
     * because opening an elytra you cannot power just turns a fall into a longer fall.
     */
    private object AutoLaunch : ToggleableValueGroup(ModuleElytraTarget, "AutoLaunch", true) {

        private val delay by int("Delay", 6, 0..15, "ticks")
        private val requireFirework by boolean("RequireFirework", true)

        private var ticksNotFlying = 0

        fun reset() {
            ticksNotFlying = 0
        }

        @Suppress("ReturnCount")
        fun tick() {
            if (!enabled) {
                ticksNotFlying = 0
                return
            }

            if (ticksNotFlying < delay) {
                ticksNotFlying++
                return
            }

            if (player.onGround() || player.isPassenger || player.isInWater || player.abilities.flying) {
                return
            }

            val chest = player.getItemBySlot(EquipmentSlot.CHEST)
            if (!chest.`is`(Items.ELYTRA) || chest.nextDamageWillBreak()) {
                return
            }

            if (requireFirework && Slots.OffhandWithHotbar.findSlot(Items.FIREWORK_ROCKET) == null) {
                return
            }

            player.startFallFlying()
            network.send(
                ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
            )
            ticksNotFlying = 0
        }
    }

    /**
     * Firework thrust. Fires on a cooldown while chasing, and immediately when the target swings —
     * a swing means it is in reach of us, so the rocket both closes the gap and carries us out.
     */
    private object Boost : ToggleableValueGroup(ModuleElytraTarget, "Boost", true) {

        /**
         * Keep a rocket in the offhand and boost from there, so a boost costs no hotbar switch and
         * the main hand keeps the weapon. See [offhandSwapHandler] for how the rocket gets there.
         */
        private val preferOffhand by boolean("PreferOffhand", false)
        private val swapBack by boolean("SwapBack", false)
        private val cooldown by int("Cooldown", 8, 0..60, "ticks")

        /**
         * Allow stacking a new rocket on top of one that is still burning. Off by default: the
         * boosts do not add up usefully and it empties the stack.
         */
        private val whileBoosting by boolean("WhileBoosting", false)

        /**
         * Speed ceiling in blocks/second × 10 (the original's `min-speed` range). Above it we stop
         * boosting — more thrust past that point only widens the turn radius. 0 disables the check.
         */
        private val maxSpeed by int("MaxSpeed", 0, 0..200)

        private val onTargetSwing by boolean("OnTargetSwing", true)

        private val inventoryConstraints = tree(PlayerInventoryConstraints())

        private var cooldownTicks = 0

        /**
         * Bounds how often an offhand swap may be scheduled, so a swap that the inventory scheduler
         * has not carried out yet is not queued again every tick.
         */
        private val offhandSwapCooldown = Chronometer()

        fun reset() {
            cooldownTicks = 0
        }

        fun tickCooldown() {
            if (cooldownTicks > 0) {
                cooldownTicks--
            }
        }

        @Suppress("ReturnCount")
        fun tryLaunch(): Boolean {
            if (cooldownTicks > 0) {
                return false
            }

            if (!whileBoosting && hasOwnRocketInAir()) {
                return false
            }

            if (maxSpeed > 0 && horizontalSpeed() * 10.0 > maxSpeed) {
                return false
            }

            val slot = findRocketSlot() ?: return false

            useHotbarSlotOrOffhand(slot, if (swapBack) 1 else 0)
            cooldownTicks = cooldown
            return true
        }

        private fun findRocketSlot(): HotbarItemSlot? {
            if (preferOffhand && HotbarItemSlot.OFFHAND.itemStack.`is`(Items.FIREWORK_ROCKET)) {
                return HotbarItemSlot.OFFHAND
            }

            return Slots.OffhandWithHotbar.findSlot(Items.FIREWORK_ROCKET)
        }

        /**
         * Brings a rocket into the offhand for [preferOffhand].
         *
         * The slot is only taken when the Offhand module (AutoTotem) is not currently driving it.
         * The test is [ModuleOffhand.isOperating] — whether that module is *acting* on the offhand
         * — and deliberately not "is there a totem in there". A totem nothing is managing is fair
         * game, and a health-triggered AutoTotem sitting idle should not reserve a slot for a swap
         * it is not making; when its threshold does trip it takes the slot straight back.
         *
         * ModuleOffhand handles the same event at priority 100, so by the time this runs its
         * `activeMode` is already resolved for the tick and there is no race for the slot.
         *
         * Gated on actually gliding: enabling the module on foot should not yank a shield out of
         * the offhand for a boost that is not going to happen.
         */
        @Suppress("unused", "ReturnCount")
        private val offhandSwapHandler = handler<ScheduleInventoryActionEvent> { event ->
            if (!preferOffhand || !player.isFallFlying) {
                return@handler
            }

            if (HotbarItemSlot.OFFHAND.itemStack.`is`(Items.FIREWORK_ROCKET)) {
                return@handler
            }

            if (ModuleOffhand.isOperating() || !HotbarItemSlot.OFFHAND.canBeSwapTarget) {
                return@handler
            }

            if (!offhandSwapCooldown.hasElapsed(OFFHAND_SWAP_COOLDOWN)) {
                return@handler
            }

            val source = Slots.HotbarAndInventory.findSlot(Items.FIREWORK_ROCKET) ?: return@handler

            event.schedule(
                inventoryConstraints,
                listOf(InventoryAction.Click.performSwap(from = source, to = HotbarItemSlot.OFFHAND))
            )
            offhandSwapCooldown.reset()
        }

        private fun hasOwnRocketInAir() = world.entitiesForRendering().any {
            it is FireworkRocketEntity && it.isAlive && it.shooter === player
        }

        private fun horizontalSpeed(): Double {
            val movement = player.deltaMovement
            return hypot(movement.x, movement.z) * 20.0
        }

        private const val OFFHAND_SWAP_COOLDOWN = 500L

        /**
         * Reflex boost on the target's swing animation. Runs on the netty thread, so the actual
         * use is bounced onto the client thread.
         */
        @Suppress("unused")
        private val swingHandler = handler<PacketEvent> { event ->
            if (event.origin != TransferOrigin.INCOMING || !onTargetSwing || !player.isFallFlying) {
                return@handler
            }

            val packet = event.packet as? ClientboundAnimatePacket ?: return@handler
            val target = ModuleElytraTarget.targetTracker.target ?: return@handler

            if (packet.id != target.id) {
                return@handler
            }

            if (packet.action != ClientboundAnimatePacket.SWING_MAIN_HAND
                && packet.action != ClientboundAnimatePacket.SWING_OFF_HAND) {
                return@handler
            }

            mc.execute {
                if (running && player.isFallFlying) {
                    tryLaunch()
                }
            }
        }
    }
}

/**
 * Pass-through processor. [RotationTarget] needs at least one processor for the reset path to
 * converge on the player's real rotation, but the aim itself must not be filtered.
 */
private object InstantRotation : RotationProcessor {
    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ) = targetRotation
}

@Suppress("unused")
private enum class RotationSource(override val tag: String) : Tagged {
    /**
     * Let KillAura's aim fly the wing on the attack run. It is already pointed at the target with
     * its own point tracker and timing, and one rotation beats two fighting over the same tick.
     * Falls back to [OWN] whenever KillAura is not aiming at our target.
     */
    KILL_AURA("KillAura"),

    /**
     * Always aim ourselves, straight at the predicted target position.
     */
    OWN("Own")
}

@Suppress("unused")
private enum class RotationVisibility(
    override val tag: String,
    val correction: MovementCorrection
) : Tagged {
    /**
     * Moves the real camera, like the original's `forceSetRotation`.
     */
    VISIBLE("Visible", MovementCorrection.CHANGE_LOOK),

    /**
     * Silent override. Still steers the wing — the gliding hook in `MixinLivingEntity` follows the
     * silent rotation for any correction other than `OFF`.
     */
    SILENT("Silent", MovementCorrection.STRICT)
}
