/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC item-matching helpers that map an `ItemStack` to a
 * [TrackedItem]. Shared library for the SystemDLC render ports; no module registration.
 *
 * Source (SystemDLC client, MC 1.21.4 Yarn):
 *   - `client/module/player/ItemTracker.java`
 *       `getTrackedItemByItemStack` (:501-519) — THE combined resolver ([resolve] here);
 *       `isTrackedItem`             (:253-255) — the "has a special matcher" predicate.
 *   - `client/module/player/AutoSwap.java`
 *       `isTrackedItemItemStack4`   (:611-632) — the per-entry predicate ([matches] here);
 *       `isItemStackItem`           (:607-609) — the SHULKER item check ([isItemStackItem]).
 *   - `client/util/SphereItems.java`
 *       `getTrackedItemByItemStack` (:568-577) — totem custom-model-data shortcut
 *                                                ([trackedItemByTotemModelData]);
 *       `isItemStackInt`            (:93-102)  — totem CMD id matcher;
 *       `isItemStackInt2`           (:497-503) — potion custom-color matcher;
 *       `isStringItemStack`         (:542-562) — lore-line-contains matcher;
 *       `isItemStackString`         (:579-581) — display-name-contains matcher;
 *       `isStringString`            (:530-539) — case-insensitive substring test.
 *
 * 26.1 component API used (verified via javap on `minecraft-merged.jar` 26.1.2 and against
 * existing repo call sites):
 *   - `DataComponents.CUSTOM_MODEL_DATA` -> `CustomModelData.floats(): List<Float>`
 *     (repo: this file, :148-149 and :164 — ModuleAutoTotem.kt lost its custom-model-data filter
 *     when it was replaced by the javelin port on 2026-09-12).
 *   - `DataComponents.POTION_CONTENTS` -> `PotionContents.customColor(): Optional<Integer>`
 *     (repo: ItemExtensions.kt:140,148; javap PotionContents).
 *   - `DataComponents.LORE` -> `ItemLore.lines(): List<Component>` (javap ItemLore).
 *   - `ItemStack.getHoverName(): Component`, `Component.getString()` (javap; Yarn getName()).
 *   - SHULKER: Yarn `Block.getBlockFromItem(item) instanceof ShulkerBoxBlock` is reproduced
 *     as `item is BlockItem && item.block is ShulkerBoxBlock` — the exact Mojmap equivalent
 *     (Block.byItem returns AIR for non-block items) and the proven repo idiom
 *     (InventoryMoveSystemBypass.kt:236-237). Not an ItemTags lookup: the source keys off the
 *     concrete ShulkerBoxBlock, which covers all 17 colored + the uncolored box.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.ShulkerBoxBlock

/**
 * SystemDLC item classification. Resolve any stack to its [TrackedItem] via [resolve]; the
 * lower-level predicates are exposed for the ports that need individual checks.
 */
object SystemDlcItems {

    /**
     * `ItemTracker.getTrackedItemByItemStack` (:501-519) — THE combined resolver.
     *
     * Iterates [TrackedItem] entries in declaration order. An entry that carries a special
     * matcher ([isTrackedItem]) returns immediately on a [matches] hit; a plain item-id entry
     * records the FIRST one whose [TrackedItem.item] equals the stack's item as a fallback.
     * This is why declaration order is load-bearing: a special entry (e.g. `SNOWBALL_FREEZE`,
     * a talisman) wins over the plain sibling (`SNOWBALL`, `TOTEM_OF_UNDYING`) whenever its
     * predicate matches, and the plain sibling is the fallback otherwise.
     */
    fun resolve(itemStack: ItemStack?): TrackedItem? {
        if (itemStack == null || itemStack.isEmpty) {
            return null
        }

        var fallback: TrackedItem? = null
        for (tracked in TrackedItem.entries) {
            if (isTrackedItem(tracked)) {
                if (matches(tracked, itemStack)) {
                    return tracked
                }
            } else if (fallback == null && tracked.item === itemStack.item) {
                fallback = tracked
            }
        }
        return fallback
    }

    /**
     * `ItemTracker.isTrackedItem` (:253-255) — the entry carries a matcher beyond a plain
     * item id (a name/lore substring, a potion color, or a totem CMD id). Note it keys off the
     * raw [TrackedItem.text2]/[TrackedItem.text3], not the lowercase [TrackedItem.text4]/
     * [TrackedItem.text5]; equivalent, since one is null exactly when the other is.
     */
    fun isTrackedItem(trackedItem: TrackedItem): Boolean =
        trackedItem.text2 != null || trackedItem.text3 != null ||
            trackedItem.value != -1 || trackedItem.value2 != -1

    /**
     * `AutoSwap.isTrackedItemItemStack4` (:611-632) — does [itemStack] match [trackedItem]?
     * Dispatch order (exactly the source):
     *  1. [TrackedItem.value] set   -> potion custom-color match ([isItemStackInt2]).
     *  2. [TrackedItem.value2] set  -> totem CMD id match ([isItemStackInt]).
     *  3. no name/lore matcher      -> plain item-id equality.
     *  4. otherwise                 -> item must pass [isItemStackItem], then lore-contains
     *                                  ([TrackedItem.text5]) OR name-contains ([TrackedItem.text4]).
     */
    fun matches(trackedItem: TrackedItem?, itemStack: ItemStack?): Boolean {
        if (itemStack == null || itemStack.isEmpty || trackedItem == null) {
            return false
        }
        if (trackedItem.value != -1) {
            return isItemStackInt2(itemStack, trackedItem.value)
        }
        if (trackedItem.value2 != -1) {
            return isItemStackInt(itemStack, trackedItem.value2)
        }
        if (trackedItem.text4 == null && trackedItem.text5 == null) {
            return itemStack.item === trackedItem.item
        }
        if (!isItemStackItem(itemStack, trackedItem.item)) {
            return false
        }
        val lore = trackedItem.text5
        if (lore != null && isStringItemStack(lore, itemStack)) {
            return true
        }
        val name = trackedItem.text4
        return name != null && isItemStackString(itemStack, name)
    }

    /**
     * `AutoSwap.isItemStackItem` (:607-609) — the stack's item equals [item], OR ([item] is the
     * shulker box and the stack is any shulker-box variant). A null [item] passes (matches the
     * source's `item2 != null` guard).
     */
    fun isItemStackItem(itemStack: ItemStack, item: Item?): Boolean {
        if (item != null && itemStack.item !== item) {
            val stackItem = itemStack.item
            return item === Items.SHULKER_BOX &&
                stackItem is BlockItem && stackItem.block is ShulkerBoxBlock
        }
        return true
    }

    /**
     * `SphereItems.getTrackedItemByItemStack` (:568-577) — totem-of-undying shortcut: reads the
     * first custom-model-data float on a totem and maps it through
     * [TrackedItem.getTrackedItemByInt] (talismans 1..8). Null for anything that is not a totem
     * with custom model data.
     */
    fun trackedItemByTotemModelData(itemStack: ItemStack?): TrackedItem? {
        if (itemStack == null || itemStack.isEmpty || itemStack.item !== Items.TOTEM_OF_UNDYING) {
            return null
        }
        val cmd = itemStack.get(DataComponents.CUSTOM_MODEL_DATA)
        return if (cmd != null && cmd.floats().isNotEmpty()) {
            TrackedItem.getTrackedItemByInt(cmd.floats().first().toInt())
        } else {
            null
        }
    }

    /**
     * `SphereItems.isItemStackInt` (:93-102) — [itemStack] is a totem whose first custom-model-data
     * float equals [count].
     */
    fun isItemStackInt(itemStack: ItemStack?, count: Int): Boolean {
        if (itemStack == null || itemStack.isEmpty || itemStack.item !== Items.TOTEM_OF_UNDYING) {
            return false
        }
        val cmd = itemStack.get(DataComponents.CUSTOM_MODEL_DATA) ?: return false
        return cmd.floats().isNotEmpty() && cmd.floats().first().toInt() == count
    }

    /**
     * `SphereItems.isItemStackInt2` (:497-503) — [itemStack] is a SPLASH_POTION whose potion
     * custom color equals [count].
     */
    fun isItemStackInt2(itemStack: ItemStack?, count: Int): Boolean {
        if (itemStack == null || itemStack.isEmpty || itemStack.item !== Items.SPLASH_POTION) {
            return false
        }
        val potion = itemStack.get(DataComponents.POTION_CONTENTS) ?: return false
        val customColor = potion.customColor()
        return customColor.isPresent && customColor.get() == count
    }

    /**
     * `SphereItems.isStringItemStack` (:542-562) — any lore line of [itemStack] contains [needle]
     * (case-insensitive). [needle] is the entry's lowercase [TrackedItem.text5].
     */
    fun isStringItemStack(needle: String?, itemStack: ItemStack?): Boolean {
        if (itemStack == null || itemStack.isEmpty || needle == null) {
            return false
        }
        val lore = itemStack.get(DataComponents.LORE) ?: return false
        for (line in lore.lines()) {
            val text = line?.string
            if (text != null && isStringString(text, needle)) {
                return true
            }
        }
        return false
    }

    /**
     * `SphereItems.isItemStackString` (:579-581) — the display name of [itemStack] contains
     * [needle] (case-insensitive). [needle] is the entry's lowercase [TrackedItem.text4].
     */
    fun isItemStackString(itemStack: ItemStack?, needle: String): Boolean {
        if (itemStack == null || itemStack.isEmpty) {
            return false
        }
        return isStringString(itemStack.hoverName.string, needle)
    }

    /**
     * `SphereItems.isStringString` (:530-539) — does [haystack] contain [needle] as a
     * case-insensitive substring (Java `regionMatches(true, ...)` scan)?
     */
    fun isStringString(haystack: String, needle: String): Boolean =
        haystack.contains(needle, ignoreCase = true)
}
