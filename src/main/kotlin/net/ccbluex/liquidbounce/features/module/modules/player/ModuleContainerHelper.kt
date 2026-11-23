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
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.ScreenRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import kotlin.math.max

/**
 * ContainerHelper - ported from SystemDLC (`system-source — копия`,
 * client/module/player/ContainerHelper.java).
 *
 * Draws a row of vanilla-styled buttons above every opened container screen
 * (and the survival inventory), horizontally centered over the container's
 * slot area:
 * - Drop All («Выкинуть всё») - THROW-clicks every non-empty slot of the
 *   container (or of the whole player inventory in the inventory screen).
 * - Stash All («Сложить всё») - QUICK_MOVE-clicks every non-empty player
 *   inventory slot into the container (container screens only).
 * - Take All («Забрать всё») - QUICK_MOVE-clicks every non-empty container
 *   slot into the player inventory (container screens only).
 * - Sort («Сортировать») - stack-merges and then orders the container slots
 *   (or the player's main inventory rows 9..35 in the inventory screen) by
 *   equippable -> tool/damageable -> block -> other, then item id, damage,
 *   descending count; paced at two swap operations per client tick.
 *
 * Grim-relevant decisions:
 * - Every slot interaction goes through the sanctioned vanilla path
 *   `MultiPlayerGameMode.handleContainerInput` (same call
 *   `InventoryAction.Click.performAction` uses), so container revision /
 *   carried-stack bookkeeping stays vanilla-consistent. No rotation,
 *   movement or interaction packets are involved anywhere in this module.
 * - The original consumed the triggering mouse press at the raw GLFW
 *   callback so vanilla never processed it. Here the press is consumed at
 *   `AbstractContainerScreen.mouseClicked` HEAD
 *   (MixinAbstractContainerScreenContainerHelper) - without that, vanilla
 *   would treat the press above the container as a click outside the
 *   bounds and send its own -999 PICKUP click (throwing any carried stack).
 * - Original per-tick pacing kept: the burst actions fire in the click
 *   call stack, sorting performs at most 2 operations (up to 6 PICKUP
 *   clicks) per client tick and halts while the cursor holds a stack.
 */
object ModuleContainerHelper : ClientModule("ContainerHelper", ModuleCategories.PLAYER) {

    /**
     * Original: `ContainerAction` enum, captions «Выкинуть всё» / «Сложить всё» /
     * «Забрать всё» / «Сортировать» (drawn on the buttons, translated here).
     */
    private enum class ContainerAction(override val tag: String, val label: String) : Tagged {
        DROP("DropAll", "Drop All"),
        STASH("StashAll", "Stash All"),
        TAKE("TakeAll", "Take All"),
        SORT("Sort", "Sort"),
    }

    /**
     * Original: MultilistSetting «Кнопки» ("Which buttons to show");
     * all four actions enabled by default (ContainerHelper.java:47-56 passes
     * the full action list as the default selection).
     */
    private val buttons by multiEnumChoice("Buttons", ContainerAction.entries)

    // Vanilla nine-sliced button sprites, same ids as the original
    // (Identifier.ofVanilla("widget/button"[ "_highlighted"])).
    private val BUTTON_SPRITE: Identifier = Identifier.withDefaultNamespace("widget/button")
    private val BUTTON_HIGHLIGHTED_SPRITE: Identifier = Identifier.withDefaultNamespace("widget/button_highlighted")

    // Original text colors 16777215 (0xFFFFFF) / 16777120 (0xFFFFA0) carry no
    // alpha; the 1.21.4 font renderer force-opaqued them, while the 26.1 text
    // pipeline DISCARDS zero-alpha text (GuiGraphicsExtractor.text returns
    // when ARGB.alpha == 0), so the same colors are passed opaque here.
    private const val TEXT_COLOR = 0xFFFFFFFF.toInt()
    private const val HOVERED_TEXT_COLOR = 0xFFFFFFA0.toInt()

    private const val BUTTON_HEIGHT = 20

    /** Original: `ContainerButton` record; hit box is width x 20. */
    private class ContainerButton(val x: Int, val y: Int, val width: Int, val action: ContainerAction) {
        fun isHovered(mouseX: Float, mouseY: Float): Boolean =
            mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + BUTTON_HEIGHT
    }

    /** Original: `ContainerBounds` record (left, right, bgTop). */
    private class ContainerBounds(val left: Int, val right: Int, val bgTop: Int)

    private val buttonsOnScreen = ArrayList<ContainerButton>()

    // Original sort state: flag / value235 / flag2.
    private var sorting = false
    private var sortSyncId = -1
    private var sortPlayerSide = false

    /**
     * Original sort order comparator: category (equippable 0, tool/damageable 1,
     * block 2, other 3), then item registry id string, then damage, then
     * descending stack count.
     */
    private val SORT_ORDER = compareBy<ItemStack>(
        { sortCategory(it) },
        { BuiltInRegistries.ITEM.getKey(it.item).toString() },
        { it.damageValue },
        { -it.count },
    )

    private fun sortCategory(stack: ItemStack): Int = when {
        stack.has(DataComponents.EQUIPPABLE) -> 0
        stack.has(DataComponents.TOOL) || stack.isDamageableItem -> 1
        stack.item is BlockItem -> 2
        else -> 3
    }

    override fun onDisabled() {
        // Original onDisable: clear the button list and stop sorting.
        buttonsOnScreen.clear()
        sorting = false
    }

    /**
     * Original getFloat2()/getFloat(): raw cursor position scaled to GUI
     * coordinates - the exact formula vanilla `MouseHandler` uses
     * (xpos * guiScaledWidth / screenWidth), so hover and click math match.
     */
    private fun mouseGuiX(): Float =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.screenWidth).toFloat()

    private fun mouseGuiY(): Float =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.screenHeight).toFloat()

    /**
     * Original onHudRenderContext: dispatched at TAIL of `Screen.render`, which
     * maps to [ScreenRenderEvent] (fired right after
     * `Screen.extractRenderStateWithTooltipAndSubtitles` in
     * `GameRenderer.extractGui`) - the buttons draw on top of the container.
     */
    @Suppress("unused")
    private val screenRenderHandler = handler<ScreenRenderEvent> { event ->
        buttonsOnScreen.clear()

        // Original hook skipped dispatch while an overlay was up
        // (GameRendererHooks: mc.getOverlay() == null); LB also fires this
        // event from the loading overlay, so mirror that guard.
        if (mc.overlay != null) {
            return@handler
        }

        val screen = mc.screen as? AbstractContainerScreen<*> ?: return@handler
        if (screen is CreativeModeInventoryScreen) {
            return@handler
        }

        val bounds = computeBounds(screen) ?: return@handler
        val actions = visibleActions(screen)
        if (actions.isEmpty()) {
            return@handler
        }

        val font = mc.font

        // Total row width: (n - 1) * 4 px gaps + per-button (text width + 16).
        var totalWidth = (actions.size - 1) * 4
        for (action in actions) {
            totalWidth += font.width(action.label) + 16
        }

        val y = bounds.bgTop - BUTTON_HEIGHT - 4
        var x = Math.round(bounds.left + (bounds.right - bounds.left - totalWidth) / 2.0f)

        val context = event.context
        val mouseX = mouseGuiX()
        val mouseY = mouseGuiY()

        for (action in actions) {
            val width = font.width(action.label) + 16
            val button = ContainerButton(x, y, width, action)
            val hovered = button.isHovered(mouseX, mouseY)

            context.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                if (hovered) BUTTON_HIGHLIGHTED_SPRITE else BUTTON_SPRITE,
                x,
                y,
                width,
                BUTTON_HEIGHT,
            )
            // Original: drawCenteredTextWithShadow(text, x + width / 2, y + 6, color);
            // centeredText draws with shadow (delegates to text(..., true)).
            context.centeredText(
                font,
                action.label,
                x + width / 2,
                y + 6,
                if (hovered) HOVERED_TEXT_COLOR else TEXT_COLOR,
            )

            buttonsOnScreen.add(button)
            x += width + 4
        }
    }

    /**
     * Original onTick: while sorting, require the same synced container to be
     * open, then perform at most two sort steps per client tick, stopping as
     * soon as a step reports nothing left to do.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!sorting) {
            return@handler
        }

        val screen = mc.screen as? AbstractContainerScreen<*>
        if (mc.gameMode == null || screen == null || screen.menu.containerId != sortSyncId) {
            sorting = false
            return@handler
        }

        repeat(2) {
            if (!sortStep(screen)) {
                sorting = false
                return@handler
            }
        }
    }

    /**
     * Original onSlotSelection: left mouse press over one of the buttons runs
     * its action and consumes the click. Called from
     * MixinAbstractContainerScreenContainerHelper at `mouseClicked` HEAD;
     * returning true cancels vanilla processing of the press.
     */
    fun handleMouseClick(screen: AbstractContainerScreen<*>, click: MouseButtonEvent): Boolean {
        // Original guards: list not empty, GLFW_PRESS (mouseClicked is always a
        // press), left button, in game (running covers it), interaction manager
        // present, current screen is a container screen (it is the receiver).
        if (!running || buttonsOnScreen.isEmpty() || click.button() != 0 || mc.gameMode == null) {
            return false
        }

        val mouseX = click.x().toFloat()
        val mouseY = click.y().toFloat()

        for (button in buttonsOnScreen) {
            if (!button.isHovered(mouseX, mouseY)) {
                continue
            }

            val isInventory = screen is InventoryScreen
            when (button.action) {
                ContainerAction.DROP -> throwAll(screen, playerSide = isInventory)
                ContainerAction.STASH -> quickMoveAll(screen, playerSide = true)
                ContainerAction.TAKE -> quickMoveAll(screen, playerSide = false)
                ContainerAction.SORT -> beginSort(screen, playerSide = isInventory)
            }
            return true
        }

        return false
    }

    /**
     * Original getListByHandledScreen: the inventory screen offers only
     * Drop All + Sort; container screens offer all four. Filtered by the
     * Buttons selection.
     */
    private fun visibleActions(screen: AbstractContainerScreen<*>): List<ContainerAction> {
        val available = if (screen is InventoryScreen) {
            listOf(ContainerAction.DROP, ContainerAction.SORT)
        } else {
            listOf(ContainerAction.DROP, ContainerAction.STASH, ContainerAction.TAKE, ContainerAction.SORT)
        }

        return available.filter { it in buttons }
    }

    /**
     * Original getContainerBoundsByHandledScreen: min/max slot x over the
     * container-side slots (all slots in the inventory screen) locates the
     * row horizontally; the background top is reconstructed from the tallest
     * slot as (screenHeight - max(166, maxSlotY + 18 + 8)) / 2, with the
     * background left fixed at (screenWidth - 176) / 2.
     */
    private fun computeBounds(screen: AbstractContainerScreen<*>): ContainerBounds? {
        val playerInventory = mc.player?.inventory
        val isInventory = screen is InventoryScreen

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = 0

        for (slot in screen.menu.slots) {
            if (slot.y + 18 > maxY) {
                maxY = slot.y + 18
            }

            val inPlayerInventory = playerInventory != null && slot.container === playerInventory
            if (isInventory || !inPlayerInventory) {
                if (slot.x < minX) {
                    minX = slot.x
                }
                if (slot.x + 16 > maxX) {
                    maxX = slot.x + 16
                }
            }
        }

        if (minX == Int.MAX_VALUE) {
            return null
        }

        val bgHeight = max(166, maxY + 8)
        val bgLeft = (screen.width - 176) / 2
        val bgTop = (screen.height - bgHeight) / 2
        return ContainerBounds(bgLeft + minX, bgLeft + maxX, bgTop)
    }

    /**
     * Original getListByBooleanHandledScreenBoolean: slots whose backing
     * inventory matches the requested side; when restricting (sort), player
     * side is narrowed to the main inventory rows (backing indices 9..35 -
     * no hotbar, no armor/offhand).
     */
    private fun slotsOnSide(
        screen: AbstractContainerScreen<*>,
        playerSide: Boolean,
        restrictToMainInventory: Boolean,
    ): List<Slot> {
        val playerInventory = mc.player?.inventory
        val result = ArrayList<Slot>()

        for (slot in screen.menu.slots) {
            val inPlayerInventory = playerInventory != null && slot.container === playerInventory
            if (playerSide == inPlayerInventory &&
                (!restrictToMainInventory || !inPlayerInventory || slot.containerSlot in 9..35)
            ) {
                result.add(slot)
            }
        }

        return result
    }

    /** Original onHandledScreenBoolean2 (DROP): THROW (button 1) every non-empty slot on the side. */
    private fun throwAll(screen: AbstractContainerScreen<*>, playerSide: Boolean) {
        val menu = screen.menu

        for (slot in slotsOnSide(screen, playerSide, restrictToMainInventory = false)) {
            if (slot.hasItem()) {
                interaction.handleContainerInput(menu.containerId, slot.index, 1, ContainerInput.THROW, player)
            }
        }
    }

    /** Original onBooleanHandledScreen (STASH/TAKE): QUICK_MOVE every non-empty slot on the side. */
    private fun quickMoveAll(screen: AbstractContainerScreen<*>, playerSide: Boolean) {
        val playerInventory = mc.player?.inventory ?: return
        val menu = screen.menu

        for (slot in menu.slots) {
            if (slot.hasItem() && (slot.container === playerInventory) == playerSide) {
                interaction.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player)
            }
        }
    }

    /** Original onHandledScreenBoolean (SORT): arm the per-tick sorter for this container. */
    private fun beginSort(screen: AbstractContainerScreen<*>, playerSide: Boolean) {
        sorting = true
        sortSyncId = screen.menu.containerId
        sortPlayerSide = playerSide
    }

    /**
     * Original isHandledScreen: one sort step. Aborts (returns false) when the
     * side has no slots or the cursor holds a stack; otherwise merges one
     * partial stack pair, or swaps one out-of-order stack into place.
     */
    private fun sortStep(screen: AbstractContainerScreen<*>): Boolean {
        val slots = slotsOnSide(screen, sortPlayerSide, restrictToMainInventory = true)
        if (slots.isEmpty()) {
            return false
        }

        if (!screen.menu.getCarried().isEmpty) {
            return false
        }

        return mergeStep(screen, slots) || orderStep(screen, slots)
    }

    /**
     * Original isHandledScreenList: find the first not-full stack with a later
     * same-item-and-components partner and merge them (one operation).
     */
    private fun mergeStep(screen: AbstractContainerScreen<*>, slots: List<Slot>): Boolean {
        for (i in slots.indices) {
            val stack = slots[i].getItem()
            if (stack.isEmpty || stack.count >= stack.maxStackSize) {
                continue
            }

            for (j in i + 1 until slots.size) {
                val other = slots[j].getItem()
                if (!other.isEmpty && ItemStack.isSameItemSameComponents(stack, other)) {
                    moveStack(destId = slots[i].index, srcId = slots[j].index, screen = screen)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Original isListHandledScreen: sort the non-empty stacks with
     * [SORT_ORDER], find the first slot whose stack is not the expected one
     * (by identity) and swap the expected stack instance into it.
     */
    private fun orderStep(screen: AbstractContainerScreen<*>, slots: List<Slot>): Boolean {
        val stacks = ArrayList<ItemStack>()
        for (slot in slots) {
            if (slot.hasItem()) {
                stacks.add(slot.getItem())
            }
        }
        stacks.sortWith(SORT_ORDER)

        for (i in stacks.indices) {
            val expected = stacks[i]
            if (slots[i].getItem() === expected) {
                continue
            }

            for (j in i + 1 until slots.size) {
                if (slots[j].getItem() === expected) {
                    moveStack(destId = slots[i].index, srcId = slots[j].index, screen = screen)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Original onIntIntHandledScreen: PICKUP the source slot, PICKUP the
     * destination (dropping/merging the carried stack there), then PICKUP the
     * source again if the cursor still holds a remainder or displaced stack.
     */
    private fun moveStack(destId: Int, srcId: Int, screen: AbstractContainerScreen<*>) {
        val menu = screen.menu
        pickup(srcId, menu)
        pickup(destId, menu)
        if (!menu.getCarried().isEmpty) {
            pickup(srcId, menu)
        }
    }

    /** Original onIntScreenHandler: a single left-button PICKUP click. */
    private fun pickup(slotId: Int, menu: AbstractContainerMenu) {
        interaction.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.PICKUP, player)
    }
}
