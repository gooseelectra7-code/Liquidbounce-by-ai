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
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.inventory.HandSwapGate
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.item.getEnchantmentCount
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Hotbar staging for the totem features that need a totem in the HOTBAR rather than straight in
 * the offhand: [ModuleMainHandTotem], which then holds that slot in the main hand, and
 * `ModuleAutoTotem`'s `Route = MainHand`, which then presses the vanilla offhand swap on it.
 *
 * One instance PER MODULE, owned by the module, so its anchor and its displaced item belong to
 * that module alone. It used to be a single global object shared by both managers - the argument
 * being that two features writing into one hotbar slot must agree on which slot - but the two are
 * documented as mutually exclusive anyway, and sharing meant one module's settings silently drove
 * the other's behaviour. Per-instance is the isolation the user asked for on 2026-09-16.
 *
 * The cost of that isolation is real and is not papered over: [pickAnchor] prefers the first EMPTY
 * hotbar slot, so two instances running at once choose the same slot and undo each other's refills.
 * That is the same collision the global object existed to prevent - it is now simply the caller's
 * problem, which is acceptable only because the two features are documented as mutually exclusive.
 *
 * Design notes:
 * - **The anchor is sticky.** The first slot staging picks is remembered in [anchor] and reused for
 *   every later refill, so a fight does not walk the totem across the whole hotbar. It is dropped
 *   only by [reset] (feature disabled, world change, death) or when the displaced item is put back.
 * - **[stagedSlot] prefers an OLDER hotbar totem over the one we just staged.** The fresh one is
 *   only a prediction until its click has been through a round trip; an older one has either been
 *   confirmed or already corrected. This is the reverse of what the first cut did - it preferred
 *   the anchor, which is precisely the slot our own last click wrote.
 * - **The refill tops up the ANCHOR, not "the hotbar".** [ensureStaged] reports READY only when the
 *   anchor itself holds a totem; a totem sitting in some OTHER hotbar slot does not satisfy it, it
 *   only serves as the fallback the caller may use in the meantime. Reported 2026-09-14 - "refill
 *   even if there are totems in the hotbar but one popped for us" - and the earlier behaviour of
 *   treating any hotbar totem as staged is what left the anchor empty after a pop. A totem STACK of
 *   several keeps the anchor satisfied until the last one goes.
 * - **The displaced item is remembered by IDENTITY first** ([findDisplaced]), exactly like
 *   `ModuleAutoTotem.findSlotOf`: `ItemStack` does not override `equals`, and a client-side swap
 *   moves the very same instance, so identity is the only thing that names the precise stack we
 *   pushed out. The component fallback covers a `ClientboundContainerSetContentPacket` resync, which
 *   rebuilds every stack in the menu and breaks identity for good.
 * - **A SWAP is its own inverse**, so putting the displaced item back is the same click again — but
 *   only while both ends still hold what they held. The totem is the end that changes (it gets
 *   spent), so the restore is re-derived from where the displaced stack actually IS now instead of
 *   being replayed blind the way `AnchoredHotbarSwapController` does it.
 * - **Every click goes through the caller's own Constraints tree.** [ensureStaged] and
 *   [releaseDisplaced] take an [InventoryConstraints] rather than owning one, so a staging click
 *   made for AutoTotem is paced and gated by AutoTotem's settings and a click made for
 *   MainHandTotem by its own. That keeps AutoTotem's "every click goes through its Constraints
 *   tree" invariant intact even on the new route.
 * - **The auras are stood down BEFORE the click, not merely for it** ([preArmTicks]). Arming at
 *   schedule time leaves the tick our own click is decided on unprotected: an aura that already
 *   spent an inventory slot earlier in that tick has bumped the menu's state id, and our click then
 *   carries the older one. So a refill first declares an intent, which puts [isStaging] up, and only
 *   clicks a couple of ticks later - into a window nothing else has written to. Bounded by
 *   [MAX_INTENT_TICKS], and `canPerformAction` is tested before the intent is declared at all, so a
 *   permanently refused chain can neither stick the gate nor duty-cycle it.
 * - **[isStaging] is the anti-ghost window** for the staging click itself. The staging SWAP targets
 *   a hotbar slot, so unless that slot happens to be the held one it does NOT arm `HandSwapGate`
 *   (`HandSwapGate.handSwapReason` only knows the offhand button and the held slot) — and it is
 *   still a container click carrying a state id, so an aura that consumes a slot inside its round
 *   trip makes the server answer it with a full resync and the staged totem becomes a ghost. The
 *   owning feature ORs this into whatever flag the auras stand down on.
 */
class TotemStaging(owner: EventListener) :
    ToggleableValueGroup(owner, "TotemStaging", true), MinecraftShortcuts {

    /** Minimum spacing between two staging clicks of ours, matching `ModuleAutoTotem`'s pacing. */
    private val ACTION_DELAY_MS = 300L

    /**
     * Dead man's switch for [isStaging]: how long a staging click may stay unsent before the window
     * lifts by itself. Same reasoning and same value as `ModuleAutoTotem.PENDING_SWAP_MS` — a chain
     * can still be dropped after we scheduled it (`HandSwapGate` defers it, the cycle aborts on
     * `requiresUpdate`), and a flag that stands the auras down must never be able to stick.
     */
    private val PENDING_STAGE_MS = 500L

    private val NOT_ARMED = Int.MIN_VALUE

    /**
     * How long a freshly staged totem stays UNTRUSTED, measured from the tick its click was handed
     * to `InventoryManager`.
     *
     * Longer than [PENDING_STAGE_MS] on purpose: that one only covers the wait before the click
     * leaves the client, while this has to outlast one server round trip - the time in which a
     * cancelled click or a resync would still be on its way back to us. Same value and same
     * reasoning as `ModuleAutoTotem.CONFIRM_SWAP_MS`.
     */
    private val CONFIRM_STAGE_MS = 1000L

    /**
     * Hard bound on the PRE-ARM half of [isStaging].
     *
     * A gate that stands two auras down must never be able to stick. Nothing should ever come close
     * to this - the pre-arm is a couple of ticks - so reaching it means the click is not coming, and
     * the right answer is to let the auras go rather than keep waiting.
     */
    private val MAX_INTENT_TICKS = 20

    /** Prefer an empty hotbar slot; fall back to [fallbackSlot] only when the hotbar is full. */
    private val autoFreeSlot by boolean("AutoFreeSlot", true)

    /** The slot used when [autoFreeSlot] is off, or when it is on and no hotbar slot is empty. */
    private val fallbackSlot by int("FallbackSlot", 9, 1..9)

    /** Put whatever the totem displaced back into the anchor once the feature is done with it. */
    private val restoreDisplaced by boolean("RestoreDisplaced", true)

    /**
     * Ticks the auras are stood down BEFORE the staging click is handed to `InventoryManager`.
     *
     * Arming the window at schedule time is too late on its own: an aura that consumed an inventory
     * slot earlier in the SAME tick has already bumped the open menu's state id, so our click goes
     * out carrying the older one and the server answers it with a full resync - the staged totem
     * becomes a ghost. Declaring the intent first, waiting, and only then clicking means the click
     * lands in a window nothing else has written to.
     *
     * Two ticks is enough because the auras stand down on the very tick the gate goes up (they read
     * it at their own decision point, before they spend anything). 0 restores the old behaviour of
     * arming and scheduling in one go. Costs nothing in practice: both features pre-stage outside
     * the danger window, so these ticks are spent while nothing is happening.
     */
    private val preArmTicks by int("PreArmTicks", 2, 0..10, "ticks")

    /** The hotbar slot staging settled on, sticky for the whole engagement. */
    private var anchor: HotbarItemSlot? = null

    /** The stack the staged totem pushed out of [anchor], or null when it displaced nothing. */
    private var displaced: ItemStack? = null

    /** Pacing for our own clicks. */
    private val pace = Chronometer()

    /** Wall clock of the last staging click handed to `InventoryManager`, or 0 while none is owed. */
    @Volatile
    private var scheduledMs = 0L

    /** Tick a refill intent was declared on, or [NOT_ARMED] while none is pending. */
    private var intentTick = NOT_ARMED

    /**
     * Whether a staging click of ours may still be in flight — see the class KDoc.
     *
     * Bounded by [PENDING_STAGE_MS] so a chain the Constraints tree keeps refusing cannot hold the
     * auras down forever.
     */
    val isStaging: Boolean
        get() {
            // Phase 1, the pre-arm: we know a refill is due and have not clicked yet.
            if (intentHeldTicks != null) {
                return true
            }

            // Phase 2: the click is with `InventoryManager`, which still has to open the inventory
            // and wait out `StartDelay` before it goes on the wire.
            val scheduled = scheduledMs
            return scheduled != 0L && System.currentTimeMillis() - scheduled < PENDING_STAGE_MS
        }

    /** Ticks since a refill intent was declared, or null when none is pending or it has expired. */
    private val intentHeldTicks: Int?
        get() {
            val armed = intentTick
            if (armed == NOT_ARMED) {
                return null
            }

            val elapsed = (mc.player?.tickCount ?: return null) - armed
            return elapsed.takeIf { it in 0..MAX_INTENT_TICKS }
        }

    /** The hotbar slot that holds a staged totem right now, or null when none does. */
    val stagedSlot: HotbarItemSlot?
        get() {
            val anchorSlot = anchor?.takeIf { it.itemStack.isTotem() }

            // PREFER THE OLDER TOTEM (2026-09-14, the user's point). A totem our own last staging
            // click just wrote exists only in the client's prediction: if that click was cancelled
            // - Grim's `MultiActionsC` does exactly that while we move, and `CompensatedInventory`
            // has already applied it client-side by then - or if the server answered it with a
            // full resync, the slot is EMPTY server-side and holding it means holding a ghost.
            //
            // Any other hotbar totem predates that click, so it has either been confirmed or
            // already been corrected by a resync. It is not "unghostable" in principle, but it is
            // strictly better evidence than a prediction that is still in flight, so it wins for
            // as long as ours is untrusted.
            //
            // Costs nothing when there is only one totem: the fallbacks below still return it.
            if (anchorSlot != null && isAnchorUnconfirmed) {
                Slots.Hotbar.firstOrNull { it != anchorSlot && it.itemStack.isTotem() }
                    ?.let { return it }
            }

            anchorSlot?.let { return it }
            return Slots.Hotbar.findSlot { it.isTotem() }
        }

    /**
     * Whether the totem in [anchor] is still only a client-side prediction.
     *
     * Read from [scheduledMs], the same stamp [isStaging] uses, because that is the moment we
     * handed the click over - there is no per-slot confirmation tracker for the hotbar the way
     * `OffhandSyncTracker` does it for the offhand.
     */
    private val isAnchorUnconfirmed: Boolean
        get() {
            val scheduled = scheduledMs
            return scheduled != 0L && System.currentTimeMillis() - scheduled < CONFIRM_STAGE_MS
        }

    /** What [ensureStaged] managed to do this cycle. */
    enum class StageState {
        /** A totem sits in [stagedSlot] right now and can be used this very tick. */
        READY,

        /** A click was scheduled, or pacing is not up yet — ask again next cycle. */
        WORKING,

        /** No totem left anywhere, or no hotbar slot may be used. Nothing more to try. */
        UNAVAILABLE,
    }

    /**
     * Makes sure a totem sits in a hotbar slot, scheduling one SWAP out of the main inventory when
     * it does not.
     *
     * This is also the refill: it is written to be called every scheduling cycle, so the tick after
     * a staged totem is spent the next one is already on its way in. Nothing here looks at health or
     * danger — that is the caller's job.
     *
     * The refill targets the ANCHOR specifically. A totem in another hotbar slot is returned as
     * [StageState.READY] so the caller can still act this tick, but it does NOT stop the top-up -
     * see the class KDoc.
     *
     * @param preferPlain spend unenchanted totems first, mirroring `ModuleAutoTotem.saveEnchanted`.
     *   The enchanted-stack IDENTITY bookkeeping that module does for its offhand route is not
     *   reproduced here; this is the cheaper "plain first, enchanted only if that is all there is".
     */
    fun ensureStaged(
        event: ScheduleInventoryActionEvent,
        constraints: InventoryConstraints,
        quiet: SwapQuiet,
        preferPlain: Boolean = true,
    ): StageState {
        // No anchor yet: adopt a totem the player already parked in the hotbar. Free, and it
        // displaces nothing.
        if (anchor == null) {
            hotbarTotem()?.let {
                anchor = it
                return StageState.READY
            }
        }

        // The anchor is loaded. Nothing to do - note a stack of several totems keeps satisfying
        // this until the last one is gone.
        if (anchor?.itemStack?.isTotem() == true) {
            return StageState.READY
        }

        // THE ANCHOR IS EMPTY - ours was just spent. Top IT up even when other hotbar slots still
        // hold totems: the slot the feature points at is the one that has to be loaded, and the
        // 2026-09-14 report was exactly this ("refill even if there are totems in the hotbar but
        // one popped for us"). `fallback` is whatever is reachable in the meantime, so a cycle
        // spent refilling never reports worse than the totem that is genuinely still usable.
        val fallback = hotbarTotem()

        val source = findSource(preferPlain) ?: return readyOr(fallback, StageState.UNAVAILABLE)
        val destination = anchor ?: pickAnchor() ?: return readyOr(fallback, StageState.UNAVAILABLE)

        if (!pace.hasAtLeastElapsed(ACTION_DELAY_MS)) {
            return readyOr(fallback, StageState.WORKING)
        }

        val action = InventoryAction.Click.performSwap(from = source, to = destination)

        // Tested BEFORE the pre-arm, not just before the click: a chain the Constraints tree
        // refuses never reaches `InventoryManager`, so no click is coming and nothing may be stood
        // down for it. Without this, a requirement that keeps failing (`Requires = NoMovement`
        // while we run) would have us declare an intent every few ticks forever and duty-cycle the
        // auras against a swap that never happens.
        if (!action.canPerformAction(constraints)) {
            intentTick = NOT_ARMED
            return readyOr(fallback, StageState.UNAVAILABLE)
        }

        // PRE-ARM. Declare the intent, stand everything down, and come back for the click - see
        // [preArmTicks] for why arming at schedule time is not enough on its own.
        val held = intentHeldTicks
        if (held == null) {
            intentTick = mc.player?.tickCount ?: return readyOr(fallback, StageState.WORKING)
            return readyOr(fallback, StageState.WORKING)
        }

        if (held < preArmTicks) {
            return readyOr(fallback, StageState.WORKING)
        }

        // Someone else's hand swap owns this window. `InventoryManager` would defer our chain for
        // it anyway; bailing here keeps the intent up instead of burning the pre-arm on a tick the
        // click cannot leave on.
        if (HandSwapGate.holdsClicks) {
            return readyOr(fallback, StageState.WORKING)
        }

        // The staging SWAP is a `CLICK_WINDOW`, so Grim's `MultiActionsC` CANCELS it outright while
        // we sprint, move or sneak - and a cancelled click is the ghost, because the client applies
        // it and the server never does. Ask for the stop on the same branch we bail out on, or the
        // quiet would never arrive: refusing to click also means opening no window, and the stop is
        // partly derived from those windows. See `SwapQuiet` for the cited sources.
        if (!quiet.isQuiet) {
            quiet.requestStop()
            return readyOr(fallback, StageState.WORKING)
        }

        // Only a real item is worth putting back. An empty slot displaces nothing, and a totem
        // cannot be here - the loaded-anchor branch above returned already.
        val occupant = destination.itemStack
        if (displaced == null && !occupant.isEmpty) {
            displaced = occupant
        }

        event.schedule(constraints, action)

        anchor = destination
        pace.reset()
        intentTick = NOT_ARMED
        armStageWindow(action, constraints)

        return StageState.WORKING
    }

    /**
     * Puts the displaced item back into the anchor and forgets the anchor.
     *
     * @return true once there is nothing left owed — either the item is home, or there was never
     *   anything to put back, or the stack is gone for good (spent, dropped, moved by hand). False
     *   means a click was scheduled or is being paced and the caller should ask again.
     */
    fun releaseDisplaced(
        event: ScheduleInventoryActionEvent,
        constraints: InventoryConstraints,
        quiet: SwapQuiet,
    ): Boolean {
        val saved = displaced
        val slot = anchor

        if (!restoreDisplaced || saved == null || slot == null) {
            reset()
            return true
        }

        // Already home.
        if (slot.itemStack === saved) {
            reset()
            return true
        }

        // Only a stack sitting in the main inventory is worth moving: anywhere else in the hotbar it
        // is already reachable, and chasing it would just shuffle the bar.
        val source = findDisplaced(saved)
        if (source == null) {
            reset()
            return true
        }

        if (!pace.hasAtLeastElapsed(ACTION_DELAY_MS)) {
            return false
        }

        // The restore is a `CLICK_WINDOW` like any other, so Grim's `MultiActionsC` cancels it
        // while we move - and a cancelled click here strands the displaced item in the inventory
        // while the client draws it back in the hotbar. Lower stakes than a totem, same bug.
        if (!quiet.isQuiet) {
            quiet.requestStop()
            return false
        }

        val action = InventoryAction.Click.performSwap(from = source, to = slot)
        event.schedule(constraints, action)

        pace.reset()
        armStageWindow(action, constraints)
        reset(keepWindow = true)

        return true
    }

    /** Any hotbar slot holding a totem, the anchor included. */
    private fun hotbarTotem(): HotbarItemSlot? = Slots.Hotbar.findSlot { it.isTotem() }

    /**
     * [StageState.READY] when [fallback] gives the caller something it can use this very tick,
     * otherwise [otherwise].
     *
     * A refill of the anchor must never make the caller act as if it had NO totem while a perfectly
     * usable one sits in another hotbar slot.
     */
    private fun readyOr(fallback: HotbarItemSlot?, otherwise: StageState): StageState =
        if (fallback != null) StageState.READY else otherwise

    /** Drops all staging state. For disable / world change / death paths. */
    fun reset() = reset(keepWindow = false)

    private fun reset(keepWindow: Boolean) {
        anchor = null
        displaced = null

        if (!keepWindow) {
            scheduledMs = 0L
            intentTick = NOT_ARMED
            pace.reset(0)
        }
    }

    /**
     * Opens the [isStaging] window for the click just scheduled.
     *
     * [InventoryAction.canPerformAction] is the same test `InventoryManager` filters the schedule
     * with (`InventoryManager.kt:126-127`), read here in the same cycle and so against the same
     * world: a chain the Constraints tree refuses never reaches the manager at all, so no click is
     * coming and nothing may be stood down for it.
     */
    private fun armStageWindow(action: InventoryAction, constraints: InventoryConstraints) {
        if (!action.canPerformAction(constraints)) {
            return
        }

        scheduledMs = System.currentTimeMillis()
    }

    /**
     * The hotbar slot to stage into.
     *
     * An empty slot first, so the common case costs the player nothing. [fallbackSlot] is the
     * deliberate eviction when the bar is full — its occupant is remembered in [displaced] and put
     * back by [releaseDisplaced].
     */
    private fun pickAnchor(): HotbarItemSlot? {
        if (autoFreeSlot) {
            Slots.Hotbar.findSlot { it.isEmpty }?.let { return it }
        }

        return Slots.Hotbar[fallbackSlot - 1]
    }

    /** A totem in the main inventory to stage. The hotbar is not searched — [stagedSlot] did that. */
    private fun findSource(preferPlain: Boolean): ItemSlot? {
        if (preferPlain) {
            Slots.Inventory.findSlot { it.isPlainTotem() }?.let { return it }
        }

        return Slots.Inventory.findSlot { it.isTotem() }
    }

    /**
     * Where the displaced stack ended up. Identity first, then components — see the class KDoc on
     * why both are needed.
     */
    private fun findDisplaced(stack: ItemStack): ItemSlot? =
        Slots.Inventory.find { it.itemStack === stack }
            ?: Slots.Inventory.find { !it.itemStack.isEmpty && ItemStack.matches(it.itemStack, stack) }

    private fun ItemStack.isTotem() = this.`is`(Items.TOTEM_OF_UNDYING)

    private fun ItemStack.isPlainTotem() = isTotem() && getEnchantmentCount() == 0

}
