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
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.item.getCooldown
import net.minecraft.client.gui.Gui
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Potions — the player's live [net.minecraft.world.entity.LivingEntity.getActiveEffects], showing
 * the real effect sprite, the translated name, the level as a dim numeral glued to the name and
 * the remaining time as `m:ss`.
 */
internal class HudTestPotionsPanel(parent: EventListener) : HudTestListPanel(
    parent,
    name = "Potions",
    anchor = HudTestAnchor.TOP_LEFT,
    offsetX = 302f,
    offsetY = 352f,
    title = "Potions",
) {

    private val optionLevel = displayOption("Level")
    private val optionDuration = displayOption("Duration")

    override val extraOptions: List<HudTestOption> get() = listOf(optionLevel, optionDuration)

    override val emptyLabel: String get() = "No effects"

    override fun drawHeaderIcon(shapes: HudTestShapes, x: Float, y: Float, size: Float, argb: Int) {
        shapes.iconHeart(x, y, size, argb)
    }

    override fun collectRows(): List<HudTestRow> {
        val effects = mc.player?.activeEffects ?: return emptyList()
        return effects
            .sortedWith(
                compareByDescending<MobEffectInstance> { it.isInfiniteDuration }
                    .thenByDescending { it.duration }
            )
            .map { instance ->
                HudTestRow(
                    icon = HudTestRowIcon.Sprite(Gui.getMobEffectSprite(instance.effect)),
                    label = instance.effect.value().displayName.string,
                    suffix = if (optionLevel.enabled) levelOf(instance) else "",
                    trailing = if (optionDuration.enabled) durationOf(instance) else "",
                )
            }
    }

    /** Level 1 is left implicit, exactly like the reference (`Luck` but `Haste 2`). */
    private fun levelOf(instance: MobEffectInstance): String =
        if (instance.amplifier > 0) (instance.amplifier + 1).toString() else ""

    private fun durationOf(instance: MobEffectInstance): String {
        if (instance.isInfiniteDuration) {
            return "--"
        }
        val seconds = instance.duration / TICKS_PER_SECOND
        return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    private companion object {
        const val TICKS_PER_SECOND = 20
    }
}

/**
 * Keybinds — every module that carries a bind, with its name and the key name the client itself
 * reports.
 *
 * The rows carry no marker: the client has no per-module icon asset, so the reference's unique
 * little pictogram per module cannot be reproduced, and a per-category stand-in only ever repeated
 * the same handful of glyphs down the list. The labels take the icon column instead.
 */
internal class HudTestKeybindsPanel(parent: EventListener) : HudTestListPanel(
    parent,
    name = "Keybinds",
    anchor = HudTestAnchor.TOP_RIGHT,
    offsetX = -430f,
    offsetY = 228f,
    title = "Keybinds",
) {

    private val optionKey = displayOption("Key")
    private val optionOnlyEnabled = displayOption("OnlyEnabled", default = false)
    private val maxRows by int("MaxRows", 8, 1..16)

    override val extraOptions: List<HudTestOption> get() = listOf(optionKey, optionOnlyEnabled)

    override val emptyLabel: String get() = if (optionOnlyEnabled.enabled) "Nothing on" else "No binds"

    override val rowIcons: Boolean get() = false

    override fun drawHeaderIcon(shapes: HudTestShapes, x: Float, y: Float, size: Float, argb: Int) {
        shapes.iconKeyboard(x, y, size, argb)
    }

    override fun collectRows(): List<HudTestRow> = ModuleManager
        .asSequence()
        .filter { !it.bind.isUnbound && !it.hidden && !it.notActivatable }
        .filter { !optionOnlyEnabled.enabled || it.enabled }
        .sortedBy { it.name }
        .take(maxRows)
        .map { module ->
            HudTestRow(
                icon = null,
                label = module.name,
                trailing = if (optionKey.enabled) module.bind.keyName else "",
            )
        }
        .toList()
}

/**
 * Cooldowns — the real item cooldown tracker.
 *
 * The tracker is keyed by cooldown group and holds no item of its own, so the player's inventory
 * is scanned for stacks that are on cooldown. Items seen on cooldown are remembered until their
 * cooldown expires, which is what keeps a totem's re-pop timer on screen after the totem itself
 * has been consumed.
 */
internal class HudTestCooldownsPanel(parent: EventListener) : HudTestListPanel(
    parent,
    name = "Cooldowns",
    anchor = HudTestAnchor.TOP_RIGHT,
    offsetX = -217f,
    offsetY = 667f,
    title = "Cooldowns",
) {

    private val optionTime = displayOption("Time")

    /** Item -> a sample stack of it, for everything seen on cooldown recently. */
    private val tracked = LinkedHashMap<Item, ItemStack>()

    override val extraOptions: List<HudTestOption> get() = listOf(optionTime)

    override val emptyLabel: String get() = "No cooldowns"

    override fun drawHeaderIcon(shapes: HudTestShapes, x: Float, y: Float, size: Float, argb: Int) {
        shapes.iconClock(x, y, size, argb)
    }

    override fun collectRows(): List<HudTestRow> {
        val player = mc.player ?: return emptyList()
        val cooldowns = player.cooldowns

        for (stack in player.inventory.nonEquipmentItems) {
            if (!stack.isEmpty && cooldowns.getCooldown(stack) != null) {
                tracked.putIfAbsent(stack.item, stack.copy())
            }
        }
        val offhand = player.getItemBySlot(EquipmentSlot.OFFHAND)
        if (!offhand.isEmpty && cooldowns.getCooldown(offhand) != null) {
            tracked.putIfAbsent(offhand.item, offhand.copy())
        }

        val rows = ArrayList<HudTestRow>(tracked.size)
        val iterator = tracked.entries.iterator()
        while (iterator.hasNext()) {
            val stack = iterator.next().value
            val entry = cooldowns.getCooldown(stack)
            val remaining = if (entry == null) 0 else entry.endTick - entry.currentTick
            if (remaining <= 0) {
                iterator.remove()
                continue
            }
            rows += HudTestRow(
                icon = HudTestRowIcon.Stack(stack),
                label = stack.hoverName.string,
                trailing = if (optionTime.enabled) formatRemaining(remaining) else "",
            )
        }
        return rows
    }

    /** Whole seconds above one second, one decimal below — the last second is worth watching. */
    private fun formatRemaining(ticks: Int): String {
        val seconds = ticks * SECONDS_PER_TICK
        return if (seconds >= 1f) {
            "${seconds.toInt()}s"
        } else {
            "%.1fs".format(Locale.ROOT, seconds)
        }
    }

    private companion object {
        const val SECONDS_PER_TICK = 0.05f
    }
}
