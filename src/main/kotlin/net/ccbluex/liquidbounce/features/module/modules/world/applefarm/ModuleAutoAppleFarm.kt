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
package net.ccbluex.liquidbounce.features.module.modules.world.applefarm

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.utils.asRefreshable
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.tickUntil
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlockRotation
import net.ccbluex.liquidbounce.utils.block.doBreak
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.searchBlocksInRangeSorted
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.findItemsInContainer
import net.ccbluex.liquidbounce.utils.inventory.findClosestSlot
import net.ccbluex.liquidbounce.utils.inventory.hasInventorySpace
import net.ccbluex.liquidbounce.utils.item.isFood
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.raytraceBlock
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.HoeItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * AutoAppleFarm module
 *
 * Automatically farms apples from oak trees in a closed loop:
 *  1. Break oak **leaves** with a hoe (faster + Fortune drops more apples/saplings).
 *  2. Break oak **logs** with an axe.
 *  3. **Plant** an oak sapling on exposed dirt/grass where the tree stood.
 *  4. Optionally **bonemeal** the sapling to speed up growth, then repeat.
 *
 * Dropped apples/saplings are walked to via [AppleFarmAutoWalk] (simple navigation, not a
 * full pathfinder — that's a separate future task). Stops on a full inventory.
 *
 * Reuses the proven block-interaction utilities from [ModuleAutoFarm]
 * (doBreak/doPlacement/raytraceBlockRotation/SilentHotbar).
 */
object ModuleAutoAppleFarm : ClientModule("AutoAppleFarm", ModuleCategories.WORLD) {

    private val range by float("Range", 4.5f, 1f..6f)
    // WallRange defaults to the full range: oak leaves/logs are frequently occluded by other
    // leaves, so we must allow breaking through blocks or the farm stalls. Set to 0 for legit.
    private val wallRange by float("WallRange", 4.5f, 0f..6f).onChange { minOf(it, range) }

    // Ticks to wait after each interaction.
    private val interactDelay by intRange("InteractDelay", 2..4, 1..15, "ticks")

    // Which phases of the farm cycle are active.
    private val breakLeaves by boolean("BreakLeaves", true)
    private val breakLogs by boolean("BreakLogs", true)

    // Auto-select hoe for leaves / axe for logs via a silent slot swap.
    private val autoTool by boolean("AutoTool", true)
    // If true, skip a block when no matching tool is held instead of mining with the current item.
    private val requireTool by boolean("RequireTool", false)

    private object AutoPlant : ToggleableValueGroup(this, "AutoPlant", true) {
        val swapBackDelay by intRange("SwapBackDelay", 1..2, 1..20, "ticks")
    }

    private object AutoUseBoneMeal : ToggleableValueGroup(this, "AutoUseBoneMeal", false) {
        // Independent cooldown for bonemeal (in ms), separate from the block-break InteractDelay.
        private val chronometer = Chronometer()
        private val useDelay = intRange("UseDelay", 250..600, 0..20000, "ms").asRefreshable()
        val swapBackDelay by intRange("SwapBackDelay", 1..2, 1..20, "ticks")

        /** Auto-move bone meal from the main inventory into the hotbar so it can be used. */
        val swapToHotbar by boolean("SwapToHotbar", true)
        val constraints = tree(PlayerInventoryConstraints())

        val isReady get() = chronometer.hasElapsed(useDelay.current.toLong())

        fun reset() {
            chronometer.reset()
            useDelay.refresh()
        }
    }

    private val disableOnFullInventory by boolean("DisableOnFullInventory", true)

    /** Pauses the farm to eat (apples) when hungry, then resumes. */
    private object AutoEat : ToggleableValueGroup(this, "AutoEat", false) {
        private val minHunger by int("MinHunger", 16, 0..20)
        var forceUseKey = false
            private set

        /** True while we still need to eat - used to pause the farm loop. */
        val needsToEat get() = enabled && player.foodData.foodLevel < minHunger

        @Suppress("unused")
        private val eatHandler = tickHandler {
            if (player.foodData.foodLevel >= minHunger) {
                return@tickHandler
            }
            if (Slots.Hotbar.findClosestSlot { it.isFood } == null) {
                // No food in the hotbar yet; the hotbar-swap handler will bring an apple. Try later.
                forceUseKey = false
                return@tickHandler
            }
            tickUntil {
                val slot = Slots.Hotbar.findClosestSlot { it.isFood } ?: return@tickUntil true
                SilentHotbar.selectSlotSilently(this@AutoEat, slot, 2)
                forceUseKey = true
                player.foodData.foodLevel >= minHunger
            }
            forceUseKey = false
            SilentHotbar.resetSlot(this@AutoEat)
        }

        @Suppress("unused")
        private val keyHandler = handler<KeybindIsPressedEvent> { event ->
            if (forceUseKey && event.keyBinding == mc.options.keyUse) {
                event.isPressed = true
            }
        }

        override fun onDisabled() {
            forceUseKey = false
            SilentHotbar.resetSlot(this)
        }
    }

    /** Throws away excess oak saplings (keeps [keep]) and sticks. */
    private object DropSaplings : ToggleableValueGroup(this, "DropSaplings", false) {
        val keep by int("Keep", 64, 0..640)
        val dropSticks by boolean("DropSticks", true)
        val constraints = tree(PlayerInventoryConstraints())
    }

    /**
     * Stores farmed apples into a nearby chest and restocks bone meal from it. Opens the chest only
     * when the inventory is full (to deposit) or bone meal has run out (to restock).
     */
    private object ChestStorage : ToggleableValueGroup(this, "ChestStorage", false) {
        val range by float("Range", 4.5f, 1f..6f)
        val depositApples by boolean("DepositApples", true)
        val depositLogs by boolean("DepositLogs", true)
        val withdrawBoneMeal by boolean("WithdrawBoneMeal", true)
        val boneMealTarget by int("BoneMealTarget", 128, 1..1024)
        val constraints = tree(InventoryConstraints())

        /** Suppresses re-opening a chest for bone meal when it turned out to have none. */
        val emptyCooldown = Chronometer()
    }

    internal val rotations = tree(RotationsValueGroup(this))

    /** The block we are currently aiming at this cycle. */
    internal var currentTarget: BlockPos? = null
        private set

    init {
        tree(AppleFarmAutoWalk)
        tree(AutoPlant)
        tree(AutoUseBoneMeal)
        tree(AutoEat)
        tree(DropSaplings)
        tree(ChestStorage)
    }

    override fun onDisabled() {
        currentTarget = null
        SilentHotbar.resetSlot(this)
    }

    @Suppress("unused", "CognitiveComplexMethod", "ReturnCount")
    private val farmHandler = tickHandler {
        // Pause while a chest GUI is open or while we are stocking a chest (the chest handlers act).
        if (chestScreenOpen()) {
            return@tickHandler
        }
        // Pause while we need to eat (AutoEat takes over).
        if (AutoEat.needsToEat) {
            return@tickHandler
        }
        if (ChestStorage.enabled && needsStorage() && findChest() != null) {
            return@tickHandler
        }

        if (disableOnFullInventory && !hasInventorySpace()) {
            notification(name, "Inventory is full — AutoAppleFarm disabled", NotificationEvent.Severity.ERROR)
            enabled = false
            return@tickHandler
        }

        // Keep the previous target if it's still valid (in range + matches a phase), so the
        // rotation doesn't flip-flop between two equidistant blocks and never arrive. Only when
        // the old target is gone/invalid do we pick a fresh one.
        val previous = currentTarget
        if (previous == null || !reaimAtCurrent(previous)) {
            currentTarget = null
            if (!findTarget()) {
                return@tickHandler
            }
        }

        val target = currentTarget ?: return@tickHandler
        val state = world.getBlockState(target)

        // Only interact once the ACTUAL (server-side) rotation reaches the target block — build the
        // hit along the current rotation and require it to land on `target`. This prevents the
        // desync where the crosshair is still travelling to block A while we mine block B.
        val currentRot = RotationManager.currentRotation ?: player.rotation
        val hit = raytraceBlock(range.toDouble(), currentRot, target, state)
        if (hit == null || hit.blockPos != target) {
            // Not looking at it yet — let the rotation keep travelling, don't break anything.
            return@tickHandler
        }
        when {
            isLeaves(state) && breakLeaves -> {
                if (!selectTool(leaves = true)) return@tickHandler
                mineBlock(target, hit)
            }

            isLog(state) && breakLogs -> {
                if (!selectTool(leaves = false)) return@tickHandler
                mineBlock(target, hit)
            }

            // Bonemeal the sapling on its own independent cooldown until it grows into a tree.
            isSapling(state) && AutoUseBoneMeal.enabled && AutoUseBoneMeal.isReady -> {
                val boneMealSlot = Slots.OffhandWithHotbar.findClosestSlot(Items.BONE_MEAL) ?: return@tickHandler
                SilentHotbar.selectSlotSilently(this, boneMealSlot, AutoUseBoneMeal.swapBackDelay.random())
                doPlacement(hit, hand = boneMealSlot.useHand)
                AutoUseBoneMeal.reset()
            }

            isPlantableGround(target) && AutoPlant.enabled -> {
                val saplingSlot = Slots.OffhandWithHotbar.findClosestSlot(Items.OAK_SAPLING) ?: return@tickHandler
                SilentHotbar.selectSlotSilently(this, saplingSlot, AutoPlant.swapBackDelay.random())
                // Place onto the TOP face of the ground block.
                doPlacement(
                    BlockHitResult(
                        Vec3(target.x + 0.5, target.y + 1.0, target.z + 0.5),
                        Direction.UP,
                        target,
                        false,
                    ),
                    hand = saplingSlot.useHand,
                )
                waitTicks(interactDelay.random())
            }
        }
    }

    /**
     * Mines [target] progressively. The interact-delay is applied ONCE, only after the block has
     * actually broken — not before and not on every tick — so there is no "double delay": a freshly
     * sighted block is hit immediately, mined over however many ticks it needs, then we pause once.
     */
    private suspend fun mineBlock(target: BlockPos, hit: BlockHitResult) {
        doBreak(hit)

        // Pause once, only after the block has actually broken. While it's still being mined we
        // keep hitting it each tick with no extra delay — findTarget keeps returning this same
        // nearest block until it's gone.
        if (world.getBlockState(target).isAir) {
            waitTicks(interactDelay.random())
        }
    }

    /**
     * Picks the next block to act on, by STRICT phase priority — all leaves must be cleared before
     * any log is touched, etc. Within a phase the nearest reachable block wins. Returns true and
     * sets [currentTarget] + rotation if a target was found.
     *
     * Phase order:
     *  1. Leaves (hoe)         — clear the canopy first
     *  2. Logs (axe)           — only once no leaves remain in range
     *  3. Sapling bonemeal     — speed up growth while the sapling exists
     *  4. Plant sapling        — on exposed ground
     */
    private fun findTarget(): Boolean {
        val eyes = player.eyePosition

        // Phase 1: leaves
        if (breakLeaves && aimAtFirst(eyes) { _, state -> isLeaves(state) }) return true

        // Phase 2: logs — only after all reachable leaves are gone
        if (breakLogs && aimAtFirst(eyes) { _, state -> isLog(state) }) return true

        // Phase 3: bonemeal the sapling while it still exists (a grown tree replaces the block)
        if (AutoUseBoneMeal.enabled && hasItem(Items.BONE_MEAL) &&
            aimAtFirst(eyes) { _, state -> isSapling(state) }) {
            return true
        }

        // Phase 4: plant a sapling on exposed ground
        if (AutoPlant.enabled && hasItem(Items.OAK_SAPLING) &&
            aimAtFirst(eyes) { pos, _ -> isPlantableGround(pos) }) {
            return true
        }

        return false
    }

    /**
     * If [pos] is still a block we want this phase and still reachable, re-aim at it and return
     * true. Keeps us locked onto one block until it's mined instead of re-picking every tick.
     */
    private fun reaimAtCurrent(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val wanted = (breakLeaves && isLeaves(state)) ||
            (breakLogs && isLog(state)) ||
            (AutoUseBoneMeal.enabled && isSapling(state)) ||
            (AutoPlant.enabled && isPlantableGround(pos))
        if (!wanted) return false

        val (rotation, _) = raytraceBlockRotation(
            player.eyePosition,
            pos,
            state,
            range = range.toDouble(),
            wallsRange = wallRange.toDouble(),
        ) ?: return false

        currentTarget = pos
        RotationManager.setRotationTarget(
            rotation,
            valueGroup = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = this@ModuleAutoAppleFarm,
        )
        return true
    }

    /** Finds the nearest block matching [predicate], aims at it, and returns true on success. */
    private inline fun aimAtFirst(eyes: Vec3, crossinline predicate: (BlockPos, BlockState) -> Boolean): Boolean {
        val candidates = eyes.searchBlocksInRangeSorted(range) { pos, state ->
            !state.isAir && predicate(pos, state)
        }

        for ((pos, state) in candidates) {
            val (rotation, _) = raytraceBlockRotation(
                eyes,
                pos,
                state,
                range = range.toDouble(),
                wallsRange = wallRange.toDouble(),
            ) ?: continue

            // Only set the rotation target here. We do NOT break yet — breaking happens only once
            // the server-side rotation has actually reached this block (see [farmHandler]),
            // otherwise the rotation would point at one block while a different one gets mined.
            currentTarget = pos
            RotationManager.setRotationTarget(
                rotation,
                valueGroup = rotations,
                priority = Priority.IMPORTANT_FOR_USAGE_1,
                provider = this@ModuleAutoAppleFarm,
            )

            debugGeometry("Target") { ModuleDebug.DebuggedBox(AABB(pos), Color4b.GREEN.alpha(100)) }
            return true
        }
        return false
    }

    private fun hasItem(item: net.minecraft.world.item.Item) =
        Slots.OffhandWithHotbar.findClosestSlot(item) != null

    /** True if there's still oak leaves to clear within farming range — used to gate AutoWalk. */
    fun hasLeavesInRange(): Boolean {
        if (!breakLeaves) return false
        return player.eyePosition.searchBlocksInRangeSorted(range) { _, state -> isLeaves(state) }
            .isNotEmpty()
    }

    /**
     * Silently swaps to a hoe (for leaves) or axe (for logs). Returns false only when
     * [requireTool] is on and no matching tool is held.
     */
    private fun selectTool(leaves: Boolean): Boolean {
        if (!autoTool) return true

        val slot = Slots.OffhandWithHotbar.findClosestSlot { stack ->
            if (leaves) stack.item is HoeItem else stack.item is AxeItem
        }

        if (slot == null) {
            return !requireTool
        }

        SilentHotbar.selectSlotSilently(this, slot, ticksUntilReset = 2)
        return true
    }

    // --- Chest storage + sapling dropping ---

    private fun chestScreenOpen(): Boolean {
        val screen = mc.screen
        return screen is AbstractContainerScreen<*> && screen !is InventoryScreen
    }

    private fun countItem(item: Item): Int =
        Slots.All.sumOf { if (it.itemStack.`is`(item)) it.itemStack.count else 0 }

    /** Nearest reachable chest within range, or null. */
    private fun findChest(): BlockPos? =
        player.eyePosition.searchBlocksInRangeSorted(ChestStorage.range) { _, state -> state.block is ChestBlock }
            .firstOrNull { (pos, state) ->
                raytraceBlockRotation(
                    player.eyePosition, pos, state,
                    range = ChestStorage.range.toDouble(),
                    wallsRange = ChestStorage.range.toDouble(),
                ) != null
            }?.first

    /** True when we should visit a chest: inventory full (to deposit) or out of bone meal (to restock). */
    private fun needsStorage(): Boolean {
        if (!ChestStorage.enabled) return false
        val full = (ChestStorage.depositApples || ChestStorage.depositLogs) && !hasInventorySpace()
        val needBone = ChestStorage.withdrawBoneMeal && AutoUseBoneMeal.enabled &&
            countItem(Items.BONE_MEAL) <= 0 && ChestStorage.emptyCooldown.hasElapsed(8000)
        return full || needBone
    }

    /** Aims at and opens the nearest chest while [needsStorage]. Transfers are done by the scheduler. */
    @Suppress("unused")
    private val chestOpenHandler = tickHandler {
        if (!ChestStorage.enabled || chestScreenOpen() || !needsStorage()) {
            return@tickHandler
        }
        val chest = findChest() ?: return@tickHandler
        val state = world.getBlockState(chest)
        val (rotation, _) = raytraceBlockRotation(
            player.eyePosition, chest, state,
            range = ChestStorage.range.toDouble(),
            wallsRange = ChestStorage.range.toDouble(),
        ) ?: return@tickHandler

        RotationManager.setRotationTarget(
            rotation,
            valueGroup = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = this@ModuleAutoAppleFarm,
        )

        val currentRot = RotationManager.currentRotation ?: player.rotation
        val hit = raytraceBlock(ChestStorage.range.toDouble(), currentRot, chest, state)
        if (hit != null && hit.blockPos == chest) {
            interaction.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            waitTicks(3)
        }
    }

    /** While a chest is open: deposit apples, then withdraw bone meal up to the target, then close. */
    @Suppress("unused")
    private val chestTransferHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (!ChestStorage.enabled) return@handler
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return@handler
        if (screen is InventoryScreen) return@handler

        val actions = mutableListOf<InventoryAction>()

        // Deposit apples / logs into the chest (frees inventory space).
        val depositItems = buildList {
            if (ChestStorage.depositApples) add(Items.APPLE)
            if (ChestStorage.depositLogs) add(Items.OAK_LOG)
        }
        if (depositItems.isNotEmpty()) {
            Slots.HotbarAndInventory
                .filter { slot -> depositItems.any { slot.itemStack.`is`(it) } }
                .forEach { actions += InventoryAction.Click.performQuickMove(screen, it) }
        }

        // Withdraw bone meal one stack at a time until we reach the target.
        if (ChestStorage.withdrawBoneMeal && countItem(Items.BONE_MEAL) < ChestStorage.boneMealTarget) {
            val boneMeal = screen.findItemsInContainer().firstOrNull { it.itemStack.`is`(Items.BONE_MEAL) }
            if (boneMeal != null) {
                actions += InventoryAction.Click.performQuickMove(screen, boneMeal)
            } else {
                // Chest has no bone meal - don't keep reopening it for restocking.
                ChestStorage.emptyCooldown.reset()
            }
        }

        if (actions.isEmpty()) {
            event.schedule(ChestStorage.constraints, InventoryAction.CloseScreen(screen))
        } else {
            event.schedule(ChestStorage.constraints, actions)
        }
    }

    /** Throws away excess oak saplings (keeping [DropSaplings.keep]) and all sticks. */
    @Suppress("unused")
    private val dropSaplingsHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (!DropSaplings.enabled || chestScreenOpen()) return@handler

        val toDrop = mutableListOf<InventoryAction>()

        // Saplings: keep some for replanting, throw the excess.
        var kept = 0
        for (slot in Slots.HotbarAndInventory.filter { it.itemStack.`is`(Items.OAK_SAPLING) }) {
            if (kept >= DropSaplings.keep) {
                toDrop += InventoryAction.Click.performThrow(screen = null, slot)
            } else {
                kept += slot.itemStack.count
            }
        }

        // Sticks: junk drop from leaves, never needed - throw all of them.
        if (DropSaplings.dropSticks) {
            Slots.HotbarAndInventory
                .filter { it.itemStack.`is`(Items.STICK) }
                .forEach { toDrop += InventoryAction.Click.performThrow(screen = null, it) }
        }

        if (toDrop.isNotEmpty()) {
            event.schedule(DropSaplings.constraints, toDrop)
        }
    }

    /** Moves bone meal / food from the main inventory into a free hotbar slot so they can be used. */
    @Suppress("unused")
    private val ensureHotbarHandler = handler<ScheduleInventoryActionEvent> { event ->
        if (chestScreenOpen()) return@handler

        var swap: InventoryAction? = null
        if (AutoUseBoneMeal.enabled && AutoUseBoneMeal.swapToHotbar) {
            swap = swapToHotbar(Items.BONE_MEAL, Slots.OffhandWithHotbar.findClosestSlot(Items.BONE_MEAL) != null)
        }
        if (swap == null && AutoEat.enabled) {
            swap = swapToHotbar(Items.APPLE, Slots.Hotbar.findClosestSlot { it.isFood } != null)
        }

        if (swap != null) {
            event.schedule(AutoUseBoneMeal.constraints, swap)
        }
    }

    /** Builds a swap of [item] from the main inventory into a hotbar slot, unless already available. */
    private fun swapToHotbar(item: Item, alreadyAvailable: Boolean): InventoryAction? {
        if (alreadyAvailable) return null
        val from = Slots.Inventory.findSlot(item) ?: return null
        val to = pickHotbarSlot() ?: return null
        return InventoryAction.Click.performSwap(screen = null, from, to)
    }

    /** A hotbar slot to drop bone meal into: an empty one, else one not holding a tool/sapling. */
    private fun pickHotbarSlot(): HotbarItemSlot? {
        Slots.Hotbar.firstOrNull { it.itemStack.isEmpty }?.let { return it }
        return Slots.Hotbar.firstOrNull {
            val stack = it.itemStack
            stack.item !is AxeItem && stack.item !is HoeItem &&
                !stack.`is`(Items.OAK_SAPLING) && !stack.`is`(Items.BONE_MEAL)
        }
    }

    // --- Block predicates (oak only) ---

    private fun isLeaves(state: BlockState) = state.`is`(Blocks.OAK_LEAVES)
    private fun isLog(state: BlockState) = state.`is`(Blocks.OAK_LOG)
    private fun isSapling(state: BlockState) = state.`is`(Blocks.OAK_SAPLING)

    /** Dirt or grass with air directly above it — a valid spot to plant a sapling. */
    private fun isPlantableGround(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        if (!state.`is`(Blocks.DIRT) && !state.`is`(Blocks.GRASS_BLOCK)) {
            return false
        }
        return world.getBlockState(pos.above()).isAir
    }
}
