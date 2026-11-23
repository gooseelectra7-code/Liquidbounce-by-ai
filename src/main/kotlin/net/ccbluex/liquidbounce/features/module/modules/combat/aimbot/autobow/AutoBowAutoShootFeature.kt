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

package net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.autobow

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoTotem
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.ModuleAutoBow
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.ModuleMainHandTotem
import net.ccbluex.liquidbounce.features.module.modules.combat.totem.SwapQuiet
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.projectiles.ArrowBallistics
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.entity.usingItemOrNull
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.SwitchMode
import net.ccbluex.liquidbounce.utils.math.geometry.Line
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TridentItem
import kotlin.math.ceil
import kotlin.math.max

/**
 * Releases the drawn bow (throws the trident, fires the charged crossbow) once it is charged and,
 * with [AutoBowAimbotFeature] on, once the rotation that will carry the shot actually hits.
 *
 * Runs on [GameTickEvent] at default priority — after RotationManager's FIRST_PRIORITY handler has
 * run the aimbot and `update()` — and releases the bow by reporting the use key unpressed to
 * `handleKeybinds`. That release leaves BEFORE this tick's movement packet, so the rotation that
 * steers it is [RotationManager.serverRotation], the one already on the wire. A crossbow fires
 * from a use packet that carries [RotationManager.currentRotation] instead
 * (`MixinServerboundUseItemPacket`) — the same value this tick's movement packet will carry
 * (`MixinLocalPlayer.hookSilentRotationYaw/Pitch`), which is what Grim's BadPacketsJ (live) demands
 * of a use packet.
 */
object AutoBowAutoShootFeature : ToggleableValueGroup(ModuleAutoBow, "AutoShoot", true) {

    private val charged by int("Charged", 15, 3..20, suffix = "ticks")

    private val chargedRandom by floatRange(
        "ChargedRandom",
        0.0F..0.0F,
        -10.0F..10.0F,
        suffix = "ticks"
    )
    private val delayBetweenShots by float("DelayBetweenShots", 0.0F, 0.0F..5.0F, suffix = "s")

    // Declared before AimCheck, whose change hook toggles its visibility.
    private val aimThresholdValue = float("AimThreshold", 1.5F, 0.1F..4.0F, suffix = "°")
    private val aimThreshold by aimThresholdValue

    /**
     * What makes the aim good enough to release while the aimbot has a target.
     */
    private val aimCheck by enumChoice("AimCheck", AimCheck.HIT)
        .onChanged { syncAimThresholdVisibility(it) }

    /**
     * Holds a bow shot until it also hits with one tick more or less of charge. The server counts
     * the draw with its own ticks, so jitter can shift the charge by one, and below full power
     * that moves the arrow by about a metre at 30 blocks. From 21 ticks on the power is saturated
     * and this never holds. Needs the aimbot and [AimCheck.HIT].
     */
    val safeCharge by boolean("SafeCharge", true)

    private val requiresHypotheticalHit by boolean("RequiresHypotheticalHit", false)
    private val usePrechargedCrossbow by boolean("UsePrechargedCrossbow", false)

    init {
        tree(HitChance)
        tree(HotbarCrossbow)
        syncAimThresholdVisibility(aimCheck)
    }

    /**
     * Releases on the CHANCE that the shot hits ([AutoBowAimbotFeature.hitChance]) instead of on
     * its nominal path grazing the hitbox.
     *
     * A smoothed rotation sweeps onto the target from one side, and the first tick on which the
     * nominal path touches the box is the box's EDGE - where vanilla's spread loses about half of
     * the shots. That is the "it fires before the rotation has arrived" miss. So the shot waits
     * until the rotation on the wire achieves [relative] of the chance the aimbot's own plan would
     * (the ceiling this tick), and never fires under [minimum]. Off, the old nominal check applies
     * ([AutoBowAimbotFeature.shotHits] / a nominal hypothetical hit).
     */
    object HitChance : ToggleableValueGroup(AutoBowAutoShootFeature, "HitChance", true) {

        /**
         * Share of the best chance available this tick the carried rotation must reach. 1 waits
         * for the exact plan (and never fires with a smoother that only approaches it); 0.9 fires
         * once the aim is close to the centre.
         */
        val relative by float("Relative", 0.9F, 0.0F..1.0F)

        /**
         * Absolute floor. Spread alone caps a standing target at about 0.7 at 40 blocks and 0.5 at
         * 60, so a high floor is a range limit.
         */
        val minimum by float("Minimum", 0.25F, 0.0F..1.0F)

        /**
         * Whether [chance] clears the gate against the best chance [best] available this tick.
         */
        fun allows(chance: Double, best: Double): Boolean =
            chance > 0.0 && chance >= minimum && chance >= relative * best

    }

    private fun syncAimThresholdVisibility(current: AimCheck) =
        aimThresholdValue.notAnOption(current != AimCheck.ANGLE)

    /**
     * Fires charged crossbows straight from the hotbar while nothing is being used. With
     * [SwitchMode.SILENT] the slot is selected for the server only ([SilentHotbar]) for the one tick
     * of the shot and goes back on the next; with [SwitchMode.NORMAL] it is selected for real in the
     * tick of the shot and handed back once the volley is over ([switchBack]). Several charged
     * crossbows are fired one after another, one per shot tick, [delay] ticks apart — the first from
     * the hand when one is already there.
     *
     * It never fires blind: a shot needs the aimbot's target and [aimCheck], or
     * [requiresHypotheticalHit]. The aimbot plans for the crossbow this will fire next
     * ([nextCrossbowStack]), so the aim appears in the tick of the shot itself.
     *
     * Packet order (Grim, read from the clone at `8eb5f28`): the slot change leads its window —
     * sent here at the head of the tick, before `handleKeybinds` and vanilla's own actions — and is
     * followed by the use (`PacketOrderE`, experimental, flags only a slot change BEHIND a
     * conflicting action). Both switch modes announce it the same way. The slot goes back in a later
     * tick, in its own window, announced by `gameMode.tick()` through the vanilla tracker
     * (`ensureHasSentCarriedItem`, which never repeats a slot — `BadPacketsA`, live).
     */
    object HotbarCrossbow : ToggleableValueGroup(AutoBowAutoShootFeature, "HotbarCrossbow", true) {

        /**
         * Ticks between two crossbow shots of a volley. One is the floor: one use per tick.
         */
        val delay by int("Delay", 1, 1..20, "ticks")

        // Declared before Switch, whose change hook toggles its visibility.
        private val switchBackValue = boolean("SwitchBack", true)

        /**
         * [SwitchMode.NORMAL] only: go back to the slot the player had once the volley is over. A
         * slot the player picks themselves in the meantime cancels the way back.
         */
        val switchBack by switchBackValue

        /**
         * How a crossbow outside the hands is brought into the main hand: [SwitchMode.SILENT] for
         * the server only (the view keeps the player's slot), [SwitchMode.NORMAL] for real, or
         * [SwitchMode.NONE] not at all - then only a crossbow already held is fired.
         */
        val switchMode by enumChoice("Switch", SwitchMode.SILENT)
            .onChanged { syncSwitchVisibility(it) }

        init {
            tree(FromInventory)
            syncSwitchVisibility(switchMode)
        }

        private fun syncSwitchVisibility(mode: SwitchMode) {
            switchBackValue.notAnOption(mode != SwitchMode.NORMAL)
            // Without a switch a crossbow brought into the hotbar could never be fired.
            FromInventory.notAnOption(mode == SwitchMode.NONE)
        }

        /**
         * Brings charged crossbows from the main inventory into the hotbar when the hotbar has
         * none left and there is an enemy around: into a slot holding a spent crossbow first (the
         * spent one goes back to the inventory), else an empty slot, else [slot].
         *
         * It is a `CLICK_WINDOW`, so it goes through [ScheduleInventoryActionEvent] with its own
         * [constraints], and it waits for [quiet]: Grim's `MultiActionsC` (live) CANCELS a click
         * sent while sprinting, moving or sneaking, and a cancelled click is a ghost item (see
         * `combat/totem/SwapQuiet`). So a pull stops the player's movement for a moment.
         */
        object FromInventory : ToggleableValueGroup(HotbarCrossbow, "FromInventory", false) {

            /**
             * Hotbar slot (1-9) a crossbow is brought into when no slot holds a spent crossbow and
             * none is empty. Never the held slot.
             */
            private val slot by int("Slot", 9, 1..9)

            private val constraints = tree(PlayerInventoryConstraints())
            private val quiet = tree(SwapQuiet(this) { pullPending })

            /**
             * From the schedule until the crossbow shows up in the hotbar (the click is predicted
             * client-side, so that is the tick it leaves), bounded by [PULL_TIMEOUT_TICKS].
             */
            var pullPending = false
                private set
            private var pullScheduledTick = 0

            /**
             * `player.tickCount` of the tick a pull landed on. The hotbar path waits that tick out:
             * a slot change and a use behind a click in one window is not a shape vanilla produces.
             */
            private var settledTick = Int.MIN_VALUE

            private val pace = Chronometer()

            /**
             * Whether a pull is in flight or has just landed, i.e. the hotbar path must wait.
             */
            val holdsShots: Boolean
                get() = enabled && (pullPending || player.tickCount == settledTick)

            /**
             * Called once per tick from the shoot handler, before anything is fired.
             */
            fun tick() {
                if (!pullPending) {
                    return
                }

                val age = player.tickCount - pullScheduledTick
                val landed = nextHotbarCrossbow() != null
                if (landed || age !in 0..PULL_TIMEOUT_TICKS) {
                    pullPending = false
                    settledTick = player.tickCount
                    debug(
                        PULL_CHANNEL,
                        if (landed) "landed after $age tick(s)" else "timed out: the click never landed in $age ticks"
                    )
                }
            }

            fun reset() {
                pullPending = false
                quiet.reset()
            }

            @Suppress("unused")
            private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
                if (pullPending) {
                    return@handler
                }

                pullRefusal()?.let { reason ->
                    debug(PULL_CHANNEL, reason)
                    return@handler
                }

                val source = Slots.Inventory.findSlot { it.isReadyCrossbow } ?: run {
                    debug(PULL_CHANNEL, "no charged crossbow in the inventory")
                    return@handler
                }
                val destination = pickDestination() ?: run {
                    debug(PULL_CHANNEL, "no hotbar slot to bring one into (Slot $slot is the held one)")
                    return@handler
                }

                if (!pace.hasAtLeastElapsed(PULL_DELAY_MS)) {
                    return@handler
                }

                val action = InventoryAction.Click.performSwap(from = source, to = destination)

                // A chain the Constraints tree refuses never reaches InventoryManager: asking for
                // the stop then would freeze the player for a click that is not coming.
                if (!action.canPerformAction(constraints)) {
                    debug(PULL_CHANNEL, "the Constraints tree refuses the click (Requires)")
                    return@handler
                }

                if (HandSwapGate.holdsClicks) {
                    debug(PULL_CHANNEL, "a hand swap is in flight")
                    return@handler
                }

                // Ask for the stop on the same branch that bails out - see SwapQuiet.requestStop.
                if (!quiet.isQuiet) {
                    debug(PULL_CHANNEL, "waiting for the movement stop (${quiet.quietBlocker}; ${quiet.stopDiagnostic})")
                    quiet.requestStop()
                    return@handler
                }

                event.schedule(constraints, action)
                pace.reset()
                pullPending = true
                pullScheduledTick = player.tickCount
                debug(PULL_CHANNEL, "scheduled into hotbar slot ${(destination.hotbarIndex ?: 0) + 1}")
            }

            /**
             * Why no pull is wanted now, or null when one is: a pull is worth it only when the hands
             * and hotbar have no crossbow to fire, a charged one waits in the inventory, and there is
             * an enemy to shoot.
             */
            private fun pullRefusal(): String? = when {
                !HotbarCrossbow.running -> "HotbarCrossbow is off"
                HotbarCrossbow.switchMode == SwitchMode.NONE -> "Switch = None"
                player.isUsingItem -> "the player is using an item"
                mc.screen != null -> "a screen is open"
                nextHotbarCrossbow() != null -> "not needed: a charged crossbow is in the hands or hotbar"
                // A shot of ours not yet confirmed changes a slot server-side; a click carrying the
                // older state id would be answered with a full inventory resync.
                firedCrossbows.isNotEmpty() -> "${firedCrossbows.size} fired crossbow(s) await the server"
                ModuleAutoTotem.isSwapping || ModuleMainHandTotem.isBusy -> "a totem swap is in flight"
                AutoBowAimbotFeature.targetTracker.targets().isEmpty() -> "no enemy for BowAimbot"
                else -> null
            }

            /**
             * A hotbar slot holding a spent crossbow, else an empty one, else [slot] - never the
             * slot the server has in the main hand unless it only holds a spent crossbow.
             */
            private fun pickDestination(): HotbarItemSlot? {
                val held = SilentHotbar.serversideSlot

                Slots.Hotbar.find { it.itemStack.item is CrossbowItem && !it.itemStack.isChargedCrossbow }
                    ?.let { return it }

                Slots.Hotbar.find { it.itemStack.isEmpty && it.hotbarIndex != held }?.let { return it }

                return HotbarItemSlot(slot - 1).takeIf { it.hotbarIndex != held }
            }

            /**
             * How long a scheduled pull may take to show up in the hotbar before it counts as refused.
             * `InventoryManager` may open the inventory and wait out its own delays first.
             */
            private const val PULL_TIMEOUT_TICKS = 20

            /**
             * Minimum time between two pulls, so a chain that keeps being refused is not rescheduled
             * on every cycle.
             */
            private const val PULL_DELAY_MS = 300L

        }

    }

    private var currentChargeRandom: Int? = null

    private fun updateChargeRandom() {
        val lenHalf = (chargedRandom.endInclusive - chargedRandom.start) / 2.0F
        val mid = chargedRandom.start + lenHalf

        currentChargeRandom =
            (mid + ModuleAutoBow.random.nextGaussian() * lenHalf).toInt()
                .coerceIn(chargedRandom.start.toInt(), chargedRandom.endInclusive.toInt())
    }

    private fun getChargedRandom(): Int {
        if (currentChargeRandom == null) {
            updateChargeRandom()
        }

        return currentChargeRandom!!
    }

    private var forceUncharged = false

    /**
     * Set in the tick a hotbar crossbow fires: the player's own use in that tick's `handleKeybinds`
     * would use the crossbow again, which the client still believes charged (see [firedCrossbows]),
     * so it is held back for that one tick.
     */
    private var holdPlayerUse = false

    /**
     * Set in the tick a hotbar crossbow fires from a slot we just switched to: the player's attack in
     * that tick's `handleKeybinds` would land with the crossbow instead of the item they chose.
     */
    private var holdPlayerAttack = false

    /**
     * `player.tickCount` of the last hotbar crossbow shot. The count restarts with the player entity
     * (respawn, dimension change); a negative distance to it therefore means "long ago".
     */
    private var lastCrossbowShotTick = 0

    /**
     * [SwitchMode.NORMAL]: the slot the player had before the volley's first switch, and the slot
     * we switched to. The way back is dropped once the player's own selection leaves [forcedSlot].
     */
    private var restoreSlot: Int? = null
    private var forcedSlot: Int? = null

    /**
     * Crossbows fired and not yet seen empty, with the `player.tickCount` of the shot.
     * `CrossbowItem.performShooting` empties the charge only on the server (`CrossbowItem.java:171-172`);
     * the client's stack stays charged until the server's slot update replaces it, a round trip
     * later. Fired again meanwhile, it would reach the server as a use of an EMPTY crossbow, which
     * starts loading it (`CrossbowItem.use`). The update arrives as a new stack instance, so the
     * fired one is recognised by identity.
     */
    private val firedCrossbows = ArrayList<Pair<ItemStack, Int>>()

    /**
     * Charged, and not one this feature already fired.
     */
    private val ItemStack.isReadyCrossbow: Boolean
        get() = isChargedCrossbow && firedCrossbows.none { (fired, _) -> fired === this }

    /**
     * Fires [stack] from [hand] and books the shot when the use succeeded. Returns what the use
     * answered.
     */
    private fun fireCrossbow(hand: InteractionHand, stack: ItemStack): InteractionResult {
        val result = useItem(hand)

        if (result is InteractionResult.Success) {
            firedCrossbows += stack to player.tickCount
            ModuleAutoBow.lastShotTimer.reset()
        }

        return result
    }

    /**
     * Whether this very [stack] instance is still in the player's inventory, hands included.
     */
    private fun isInInventory(stack: ItemStack): Boolean {
        val inventory = player.inventory
        return (0 until inventory.containerSize).any { inventory.getItem(it) === stack }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        forceUncharged = false
        holdPlayerUse = false
        holdPlayerAttack = false
        // A fired crossbow is settled once the server's slot update has replaced the instance (or
        // emptied it); until then FromInventory must not click (see its wantsPull).
        firedCrossbows.removeIf { (stack, tick) ->
            player.tickCount - tick !in 0..FIRED_CROSSBOW_TIMEOUT_TICKS ||
                !stack.isChargedCrossbow ||
                !isInInventory(stack)
        }
        HotbarCrossbow.FromInventory.tick()
        if (ModuleAutoBow.debug.enabled) {
            debug(DELAY_CHANNEL, ShotDelayMeter.describe())
        }

        if (usingItemHand() == null) {
            if (!fireHotbarCrossbow()) {
                restoreNormalSlotIfDone()
            }
            return@handler
        }

        if (ticksUntilGatesOpen() != 0) {
            return@handler
        }

        val stack = usingItemStack()

        aimRefusal(stack, allowBlind = true)?.let { reason ->
            if (stack.isReadyCrossbow) {
                debug(HAND_CHANNEL, reason)
            }
            return@handler
        }

        if (stack.item is CrossbowItem) {
            if (stack.isReadyCrossbow) {
                val result = fireCrossbow(usingItemHand() ?: return@handler, stack)
                debug(
                    HAND_CHANNEL,
                    if (result is InteractionResult.Success) "fired from the hand" else "the use answered $result"
                )
            } else {
                forceUncharged = true
            }
        } else {
            forceUncharged = true
            if (stack.item is BowItem) {
                updateChargeRandom()
            }
        }
    }

    @Suppress("unused")
    private val keybindHandler = handler<KeybindIsPressedEvent> { event ->
        val key = event.keyBinding

        if (key == mc.options.keyUse && (forceUncharged || holdPlayerUse) ||
            key == mc.options.keyAttack && holdPlayerAttack) {
            event.isPressed = false
        }
    }

    /**
     * Whether the aim allows a shot of [stack] now: a hypothetical hit, or the aimbot's target
     * passing [aimCheck]. Without either requirement the answer is [allowBlind].
     */
    private fun isAimed(stack: ItemStack, allowBlind: Boolean): Boolean = aimRefusal(stack, allowBlind) == null

    /**
     * Why the aim does not allow a shot of [stack] now, or null when it does - [isAimed] in words,
     * for [debug].
     */
    private fun aimRefusal(stack: ItemStack, allowBlind: Boolean): String? {
        if (requiresHypotheticalHit) {
            return hypotheticalRefusal(stack)
        }

        if (!AutoBowAimbotFeature.enabled) {
            return if (allowBlind) null else "BowAimbot is off and RequiresHypotheticalHit is off - never fired blind"
        }

        val target = AutoBowAimbotFeature.targetTracker.target
            ?: return "BowAimbot has no target (${AutoBowAimbotFeature.targetTracker.countTargets()} valid " +
                "entities, none with a solvable shot)"

        return when (aimCheck) {
            AimCheck.HIT -> hitRefusal(target, stack)

            AimCheck.ANGLE -> {
                val targetRotation = RotationManager.activeRotationTarget
                    ?: return "AimCheck = Angle: no rotation requested"
                val angle = shotRotation(stack).angleTo(targetRotation.rotation)
                if (angle <= aimThreshold) null else "AimCheck = Angle: %.1f° > %.1f°".format(angle, aimThreshold)
            }
        }
    }

    /**
     * [AimCheck.HIT]: why the rotation that will carry a shot of [stack] may not fire at [target]
     * now, or null when it may.
     */
    private fun hitRefusal(target: Entity, stack: ItemStack): String? {
        val rotation = shotRotation(stack)

        if (!HitChance.enabled) {
            return if (AutoBowAimbotFeature.shotHits(target, stack, rotation, safeCharge)) {
                null
            } else {
                "AimCheck = Hit: the shot misses ${target.name.string} (${aimState(stack)})"
            }
        }

        val chance = AutoBowAimbotFeature.hitChance(target, stack, rotation)
        val planned = AutoBowAimbotFeature.plannedRotation?.takeIf { AutoBowAimbotFeature.plannedTarget === target }
        val best = planned?.let { AutoBowAimbotFeature.hitChance(target, stack, it) } ?: chance

        if (!HitChance.allows(chance, best)) {
            return "AimCheck = Hit: ${chanceState(chance, best)} on ${target.name.string} (${aimState(stack)})"
        }

        if (safeCharge && !AutoBowAimbotFeature.chargeSafe(target, stack, rotation)) {
            return "SafeCharge: a one-tick charge error would miss ${target.name.string}"
        }

        return null
    }

    /**
     * [requiresHypotheticalHit]: why a shot of [stack] flown at the rotation that will carry it
     * may not fire now, or null when it may - the first entity it meets has to be attackable.
     */
    private fun hypotheticalRefusal(stack: ItemStack): String? {
        if (!HitChance.enabled) {
            return if (getHypotheticalHit(stack)?.shouldBeAttacked() == true) {
                null
            } else {
                "RequiresHypotheticalHit: the shot would hit nothing attackable (${aimState(stack)})"
            }
        }

        val chance = hypotheticalHitChance(stack)
        val target = AutoBowAimbotFeature.plannedTarget
        val planned = AutoBowAimbotFeature.plannedRotation
        val best = if (target != null && planned != null) {
            AutoBowAimbotFeature.hitChance(target, stack, planned)
        } else {
            chance
        }

        return if (HitChance.allows(chance, best)) {
            null
        } else {
            "RequiresHypotheticalHit: ${chanceState(chance, best)} on anything attackable (${aimState(stack)})"
        }
    }

    private fun chanceState(chance: Double, best: Double): String =
        "hit chance %d%% of the plan's %d%%".format((chance * 100).toInt(), (best * 100).toInt())

    /**
     * The aimbot's target and how far the rotation that carries a shot of [stack] is from what the
     * aimbot asked for - the two things that decide a refused aim. The angle is bucketed so the line
     * only changes when the state does ([debug] writes on change).
     */
    private fun aimState(stack: ItemStack): String {
        val target = AutoBowAimbotFeature.targetTracker.target?.name?.string ?: "none"
        val requested = RotationManager.activeRotationTarget?.rotation
            ?: return "aimbot target: $target, no rotation requested"
        val bucket = when (shotRotation(stack).angleTo(requested)) {
            in 0.0F..1.0F -> "under 1°"
            in 1.0F..5.0F -> "1-5°"
            in 5.0F..20.0F -> "5-20°"
            else -> "over 20°"
        }
        return "aimbot target: $target, carried rotation $bucket off the request"
    }

    /**
     * Last line written per [debug] channel, so a reason is written once when it changes instead of
     * once per tick.
     */
    private val debugLines = HashMap<String, String>()

    private fun debug(channel: String, message: String) {
        if (!ModuleAutoBow.debug.enabled) {
            // Forget what was written, so switching Debug back on writes the current reason again.
            debugLines.clear()
            return
        }

        if (debugLines.put(channel, message) != message) {
            ModuleAutoBow.debug.log("$channel: $message")
        }
    }

    /**
     * Ticks until every gate except the aim opens — the charge threshold and the delay between
     * shots: 0 when they are open now, null when nothing this feature releases is held. The
     * aimbot plans its rotation for that tick.
     *
     * For a crossbow that is still loading the charge gate is the load finishing (this feature
     * then releases the use key to complete it; that is not a shot). With nothing in use the
     * answer is [HotbarCrossbow]'s.
     */
    fun ticksUntilGatesOpen(): Int? {
        usingItemHand() ?: return hotbarTicksUntilReady()

        val stack = usingItemStack()
        val ticksUsingItem = player.ticksUsingItem

        val chargeTicks = when (stack.item) {
            is CrossbowItem -> if (stack.isReadyCrossbow) {
                0
            } else {
                CrossbowItem.getChargeDuration(stack, player) - ticksUsingItem
            }

            is BowItem -> charged + getChargedRandom() - ticksUsingItem
            is TridentItem -> TridentItem.THROW_THRESHOLD_TIME + 1 - ticksUsingItem
            else -> return null
        }

        return max(max(chargeTicks, shotDelayTicks()), 0)
    }

    /**
     * Ticks until [delayBetweenShots] has passed since the last shot.
     */
    private fun shotDelayTicks(): Int {
        val remainingDelay = (delayBetweenShots * 1000.0F).toLong() - ModuleAutoBow.lastShotTimer.elapsed

        return if (remainingDelay >= 0L) {
            // Chronometer.hasElapsed is strict, so a remainder of exactly 0 ms is still closed.
            ceil((remainingDelay + 1L) / MILLISECONDS_PER_TICK).toInt()
        } else {
            0
        }
    }

    /**
     * The charged crossbow this feature fires next — from a hand ([usePrechargedCrossbow]) or
     * through [HotbarCrossbow] — for the aimbot to plan with (an arrow bolt and a firework fly
     * differently), or null when it fires none.
     */
    fun nextCrossbowStack(): ItemStack? {
        if (!enabled || player.isUsingItem) {
            return null
        }

        usingItemHand()?.let { hand ->
            return player.getItemInHand(hand)
        }

        if (hotbarTicksUntilReady() == null) {
            return null
        }

        return nextHotbarCrossbow()?.stack
    }

    /**
     * A charged crossbow and how to fire it: from [hand], after selecting [hotbarSlot] first when
     * it is not already the held one.
     */
    private class CrossbowSource(val hand: InteractionHand, val hotbarSlot: Int?, val stack: ItemStack)

    /**
     * The crossbow a volley fires next: the one already held (no slot change at all), then the
     * offhand, then the hotbar in order.
     */
    private fun nextHotbarCrossbow(): CrossbowSource? {
        val mainHand = player.mainHandItem
        if (mainHand.isReadyCrossbow) {
            return CrossbowSource(InteractionHand.MAIN_HAND, null, mainHand)
        }

        val offHand = player.offhandItem
        if (offHand.isReadyCrossbow) {
            return CrossbowSource(InteractionHand.OFF_HAND, null, offHand)
        }

        if (HotbarCrossbow.switchMode == SwitchMode.NONE) {
            return null
        }

        val held = SilentHotbar.serversideSlot

        for (slot in 0 until Inventory.getSelectionSize()) {
            val stack = player.inventory.getItem(slot)

            if (slot != held && stack.isReadyCrossbow) {
                return CrossbowSource(InteractionHand.MAIN_HAND, slot, stack)
            }
        }

        return null
    }

    /**
     * Why the hotbar path must not touch the slot or use anything now, or null when it may.
     */
    private fun hotbarStandDownReason(): String? = when {
        // A server-side slot change stops a main-hand use in progress
        // (`ServerGamePacketListenerImpl.handleSetCarriedItem`); an offhand use would go on under a
        // slot we changed. Neither is ours to interrupt.
        player.isUsingItem -> "the player is using an item"
        // Vanilla cannot change the slot with a menu up, and skips `handleKeybinds` meanwhile.
        mc.screen != null || mc.overlay != null -> "a screen is open"
        // The same, for a menu `InventoryManager` opened without a screen (the tail of a pull, or
        // another module's chain): a slot change or a use with it up is a shape vanilla never sends.
        InventoryManager.isInventoryOpenServerSide -> "the inventory is open server-side"
        SilentHotbar.isSlotModified() && !SilentHotbar.isSlotModifiedBy(this) -> "another module holds the hotbar"
        HandSwapGate.holdsUses -> "a hand swap is in flight"
        HotbarCrossbow.FromInventory.holdsShots -> "a crossbow is being brought in from the inventory"
        ModuleAutoTotem.isSwapping -> "AutoTotem has a totem swap in flight"
        ModuleMainHandTotem.isBusy -> "MainHandTotem holds the main hand"
        // A melee target in reach is KillAura's: a crossbow selected under it would take its hits.
        ModuleKillAura.running && ModuleKillAura.targetTracker.target != null -> "KillAura has a target"
        else -> null
    }

    /**
     * Ticks until the hotbar path may fire, or null when it is off, has nothing to fire, or must
     * stand down.
     */
    private fun hotbarTicksUntilReady(): Int? {
        if (!HotbarCrossbow.enabled || hotbarStandDownReason() != null || nextHotbarCrossbow() == null) {
            return null
        }

        val sinceLastShot = player.tickCount - lastCrossbowShotTick
        val volleyTicks = if (sinceLastShot < 0) 0 else HotbarCrossbow.delay - sinceLastShot

        return max(max(volleyTicks, shotDelayTicks()), 0)
    }

    /**
     * Fires the next crossbow of a volley when its tick has come and the aim allows it, switching to
     * its hotbar slot first when it is not in a hand. Returns whether a shot left.
     */
    private fun fireHotbarCrossbow(): Boolean {
        hotbarBlocker()?.let { reason ->
            debug(HOTBAR_CHANNEL, reason)
            return false
        }

        val crossbow = nextHotbarCrossbow() ?: return false

        // Never blind: a hotbar volley is only worth its bolts with a reason to shoot.
        aimRefusal(crossbow.stack, allowBlind = false)?.let { reason ->
            debug(HOTBAR_CHANNEL, reason)
            return false
        }

        crossbow.hotbarSlot?.let { slot ->
            when (HotbarCrossbow.switchMode) {
                SwitchMode.SILENT -> {
                    SilentHotbar.selectSlotSilently(this, slot, 0)

                    // Another module may veto the selection; then the main hand is not the
                    // crossbow, and a use would use whatever it is.
                    if (!SilentHotbar.isSlotModifiedBy(this)) {
                        debug(HOTBAR_CHANNEL, "a SelectHotbarSlotSilentlyEvent listener vetoed slot ${slot + 1}")
                        return false
                    }
                }

                SwitchMode.NORMAL -> normalSwitch(slot)

                // nextHotbarCrossbow offers no hotbar slot without a switch.
                SwitchMode.NONE -> return false
            }

            // Announce now, so the slot change leads this window instead of trailing the use. The
            // tracker announces SilentHotbar.serversideSlot, which is the crossbow slot in both
            // modes (NORMAL: nothing else holds the hotbar - hotbarStandDownReason).
            mc.gameMode?.ensureHasSentCarriedItem()

            // The slot changed under the player's clicks of this tick: their attack would land with
            // the crossbow, and their use would fire it past the aim check if ours fails below.
            holdPlayerClicks(attack = true)
        }

        val source = crossbow.hotbarSlot?.let { "hotbar slot ${it + 1} (${HotbarCrossbow.switchMode.tag})" }
            ?: if (crossbow.hand == InteractionHand.MAIN_HAND) "the main hand" else "the offhand"

        val result = fireCrossbow(crossbow.hand, crossbow.stack)
        if (result !is InteractionResult.Success) {
            debug(HOTBAR_CHANNEL, "the use of $source answered $result")
            return false
        }

        debug(HOTBAR_CHANNEL, "fired from $source")
        holdPlayerClicks(attack = false)
        lastCrossbowShotTick = player.tickCount
        return true
    }

    /**
     * Why the hotbar path fires nothing this tick before the aim is even asked, or null when only
     * the aim is left to decide.
     */
    private fun hotbarBlocker(): String? {
        if (!HotbarCrossbow.enabled) {
            return "HotbarCrossbow is off"
        }

        hotbarStandDownReason()?.let { return "standing down: $it" }

        if (nextHotbarCrossbow() == null) {
            val scope = if (HotbarCrossbow.switchMode == SwitchMode.NONE) "hands (Switch = None)" else "hands or hotbar"
            val pending = if (firedCrossbows.isEmpty()) "" else ", ${firedCrossbows.size} fired and awaiting the server"
            return "no charged crossbow in the $scope$pending"
        }

        val ticks = hotbarTicksUntilReady() ?: return "not ready"
        return if (ticks > 0) "waiting $ticks tick(s) for Delay / DelayBetweenShots" else null
    }

    /**
     * Holds the player's own use - and with [attack] their attack - for the rest of this tick,
     * dropping the clicks already queued for `handleKeybinds`.
     */
    private fun holdPlayerClicks(attack: Boolean) {
        mc.options.keyUse.clickCount = 0
        holdPlayerUse = true

        if (attack) {
            mc.options.keyAttack.clickCount = 0
            holdPlayerAttack = true
        }
    }

    /**
     * [SwitchMode.NORMAL]: selects [slot] for real. The slot the player had before the volley's
     * FIRST switch is kept for the way back ([HotbarCrossbow.switchBack]); a later switch of the same
     * volley keeps that original.
     */
    private fun normalSwitch(slot: Int) {
        val inventory = player.inventory
        val selected = inventory.selectedSlot
        dropRestoreIfPlayerMoved(selected)

        if (selected == slot) {
            return
        }

        if (restoreSlot == null && HotbarCrossbow.switchBack) {
            restoreSlot = selected
        }

        inventory.selectedSlot = slot
        forcedSlot = slot
    }

    /**
     * [SwitchMode.NORMAL]: gives the player's slot back once the volley is over ([isVolleyOver]).
     * Runs at the tick head, so `gameMode.tick()` announces the slot at its vanilla position. Nothing
     * else restores `Inventory.selected`, so without this the player stays on the crossbow slot.
     */
    private fun restoreNormalSlotIfDone() {
        val inventory = player.inventory
        val selected = inventory.selectedSlot
        dropRestoreIfPlayerMoved(selected)
        val target = restoreSlot ?: return

        if (!HotbarCrossbow.switchBack) {
            restoreSlot = null
            forcedSlot = null
            return
        }

        // A slot change stops a main-hand use (the player loading the spent crossbow, say -
        // `ServerGamePacketListenerImpl.handleSetCarriedItem`), and vanilla cannot change the slot
        // with a menu up. The way back waits for both.
        if (player.isUsingItem || mc.screen != null || InventoryManager.isInventoryOpenServerSide) {
            return
        }

        if (!isVolleyOver()) {
            return
        }

        restoreSlot = null
        forcedSlot = null

        if (selected != target) {
            inventory.selectedSlot = target
        }
    }

    /**
     * Whether a volley is over: nothing left to fire, the hotbar path standing down (KillAura took a
     * target, a totem swap, ...), or no shot for [SWITCH_BACK_IDLE_TICKS]. Holding the crossbow slot
     * between shots is only cosmetic - a switch shares the tick of its shot - so the idle bound only
     * keeps the slot from flickering between the shots of one volley.
     */
    private fun isVolleyOver(): Boolean {
        if (!HotbarCrossbow.running || hotbarTicksUntilReady() == null) {
            return true
        }

        val sinceLastShot = player.tickCount - lastCrossbowShotTick
        return sinceLastShot !in 0..max(HotbarCrossbow.delay, SWITCH_BACK_IDLE_TICKS)
    }

    /**
     * Never fight the player: once their own selection left the slot we forced, the way back is void.
     */
    private fun dropRestoreIfPlayerMoved(selected: Int) {
        if (restoreSlot != null && selected != forcedSlot) {
            restoreSlot = null
            forcedSlot = null
        }
    }

    /**
     * The rotation a shot released now leaves with: the one on the wire for a bow or trident, the
     * one the use packet carries for a crossbow (`useItem`'s default).
     */
    private fun shotRotation(stack: ItemStack): Rotation = if (stack.item is CrossbowItem) {
        RotationManager.currentRotation ?: player.rotation
    } else {
        RotationManager.serverRotation
    }

    /**
     * The hand this feature releases or fires from: the one in use, else (with
     * [usePrechargedCrossbow]) a hand holding a ready crossbow, else null - and null is what sends a
     * tick down the [HotbarCrossbow] path.
     *
     * `LocalPlayer.usingItemHand` is NOT "the hand in use". It is a public field set in
     * `startUsingItem` (`LocalPlayer.java:552`) and never cleared by `stopUsingItem` (`:570-573`), so
     * after the first use of anything it names a hand for good. Read unguarded, it sent every tick
     * down the in-use path and the hotbar path never ran (2026-09-24, the user's Debug log: not one
     * `hotbar:` line). The hand is therefore read only while [player] is really using an item.
     */
    private fun usingItemHand(): InteractionHand? {
        if (player.isUsingItem) {
            return player.usedItemHand
        }

        return if (usePrechargedCrossbow) {
            InteractionHand.entries.find { player.getItemInHand(it).isReadyCrossbow }
        } else {
            null
        }
    }

    private fun usingItemStack(): ItemStack {
        return player.usingItemOrNull ?: usingItemHand()?.let { player.getItemInHand(it) } ?: ItemStack.EMPTY
    }

    /**
     * The attackable entity a shot released now would hit first, flown by [AutoBowResolver] with
     * the aimbot's prediction settings.
     */
    private fun getHypotheticalHit(stack: ItemStack): Entity? {
        val info = AutoBowResolver.shotInfo(stack, player.ticksUsingItem) ?: return null
        val rotation = shotRotation(stack)

        val candidates = hypotheticalCandidates(rotation)
            .filter { it.wanted }
            .map { it.entity to it.prediction }

        return AutoBowResolver.firstHit(
            rotation,
            ArrowBallistics.Physics.of(info),
            AutoBowResolver.currentShooter(info),
            candidates,
            collideBlocks = true
        )
    }

    /**
     * The chance that a shot of [stack] released now meets an attackable entity before anything
     * else - every pickable living entity near the line counts, the unattackable ones as being in
     * the way (`AbstractArrow.canHitEntity` stops at any of them).
     */
    private fun hypotheticalHitChance(stack: ItemStack): Double {
        val info = AutoBowResolver.shotInfo(stack, player.ticksUsingItem) ?: return 0.0
        val rotation = shotRotation(stack)

        return AutoBowResolver.hitChance(
            rotation,
            ArrowBallistics.Physics.of(info),
            AutoBowResolver.currentShooter(info),
            hypotheticalCandidates(rotation),
            collideBlocks = true,
            timingJitter = AutoBowAimbotFeature.timingJitter()
        )
    }

    /**
     * Living, pickable entities within [HYPOTHETICAL_LINE_DISTANCE] of the line of fire, each at
     * its predicted position, wanted when the client's target config would attack it.
     */
    private fun hypotheticalCandidates(rotation: Rotation): List<AutoBowResolver.Candidate> {
        val line = Line(player.eyePosition, rotation.directionVector)

        return world.entitiesForRendering().mapNotNull { entity ->
            if (entity === player || entity !is LivingEntity || !entity.isAlive || !entity.isPickable ||
                line.distanceToSqr(entity.position()) >= HYPOTHETICAL_LINE_DISTANCE * HYPOTHETICAL_LINE_DISTANCE) {
                return@mapNotNull null
            }

            AutoBowResolver.Candidate(entity, AutoBowAimbotFeature.predictionFor(entity, 0), entity.shouldBeAttacked())
        }
    }

    private inline val ItemStack.isChargedCrossbow
        get() = CrossbowItem.isCharged(this)

    override fun onDisabled() {
        forceUncharged = false
        holdPlayerUse = false
        holdPlayerAttack = false
        firedCrossbows.clear()
        HotbarCrossbow.FromInventory.reset()
        SilentHotbar.resetSlot(this)

        // Hand a NORMAL switch back now: nothing runs the way back once this is off.
        mc.player?.inventory?.let { inventory ->
            val target = restoreSlot
            if (target != null && inventory.selectedSlot == forcedSlot && HotbarCrossbow.switchBack) {
                inventory.selectedSlot = target
            }
        }
        restoreSlot = null
        forcedSlot = null

        super.onDisabled()
    }

    private const val MILLISECONDS_PER_TICK = 50.0

    private const val HOTBAR_CHANNEL = "hotbar"
    private const val HAND_CHANNEL = "hand"
    private const val DELAY_CHANNEL = "delay"

    /** How far an entity may stand from the line of fire to be flown against as a hypothetical hit. */
    private const val HYPOTHETICAL_LINE_DISTANCE = 10.0
    private const val PULL_CHANNEL = "pull"

    /**
     * [SwitchMode.NORMAL]: ticks without a shot after which the player's slot is given back although
     * crossbows are left (see [isVolleyOver]).
     */
    private const val SWITCH_BACK_IDLE_TICKS = 10

    /**
     * How long a fired crossbow waits for the server's slot update before it counts as charged
     * again — if the update never came, the shot never happened.
     */
    private const val FIRED_CROSSBOW_TIMEOUT_TICKS = 40

}

/**
 * What [AutoBowAutoShootFeature] requires of the aim before releasing.
 */
enum class AimCheck(override val tag: String) : Tagged {
    /**
     * The rotation that will carry the shot, flown the way the server flies it, hits the
     * predicted target.
     */
    HIT("Hit"),

    /**
     * The rotation that will carry the shot is within `AimThreshold` degrees of the aimbot's
     * current request.
     */
    ANGLE("Angle"),
}
