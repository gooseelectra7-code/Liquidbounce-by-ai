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
package net.ccbluex.liquidbounce.features.module.modules.combat.testat

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.testaa.TestAAMath
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.world.bedRule
import net.ccbluex.liquidbounce.utils.world.respawnAnchorWorks
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * testAT module
 *
 * The "Auto Totem" module of the donut / "aesthetic" client (`c/IiiiiIiiii`, decompiled as
 * `C:\Users\caves\Documents\donut\work\src_named\c\AutoTotem.java`), after the user's
 * `donut\work\aot_java\AUTO_TOTEM.md`. Like `testAA` it follows donut, not this client's anticheat doctrine: it
 * shares none of `ModuleAutoTotem`'s / `ModuleOffhand`'s guards (no `SwapQuiet`, no `HandSwapGate`, no stand-down of
 * the auras, no inventory-screen check). Enable one totem module at a time.
 *
 * ## What is known, and from where
 * Almost all of this module is `native` AOT code in the jar: the tick handler (`AutoTotem.java:171`), every threat
 * check (`SocialCommand.java:3995`, `:5292`, `:11045`, `:55009`, `:59374`, `:62340`, `:66062`, `:69764`, `:69821`)
 * and the swap (`:19503`, `:41066`). None of it was traced here. What this port rests on:
 * - READ from the decompile: the settings tree (`AutoTotem.java:70-83`, `:89-156`), the four threat labels
 *   (`:101-104`), the state that `onEnable` / `onDisable` reset (`:205-245`: a stack, a slot starting at -1, a
 *   counter, a set) and the one non-native helper, the "plain totem" test (`SocialCommand.java:55409-55427`).
 * - READ from donut's own default config (`donut\work\modules_default.bin.out`): the names, order and defaults
 *   (Health on, Min Health 6, On Falling on, Fall Distance 3, Falling Player off, Radius 5, Height 3, Near Explosive
 *   on, Damage 1, Threats = all four). Slider ranges are not in it and are GUESSES.
 * - INFERRED from third-party reconstructions of the native code (`donut\work\aot_java\f22`-`f25`, hints only, each
 *   marked there with open questions): the swap packets ([swap], f25 - the most complete of them), the order of
 *   the threat cascade ([inDanger], f22) and the falling-player test ([fallingPlayerAbove], f23/f24, which disagree
 *   with each other). The per-block explosion checker (f22's "70 KB mega-checker") is not reconstructed at all.
 * - GUESS: when the original offhand item goes back ([onTick]), which totem is preferred ([totemSlot]), the
 *   explosion centres and powers per threat ([blockThreat]), and the scan box.
 *
 * The damage model is `testaa`'s port of donut's `DamageUtil` - the same client's model, not vanilla's.
 */
@Suppress("MagicNumber")
object ModuleTestAT : ClientModule("testAT", ModuleCategories.COMBAT) {

    private enum class Threat(override val tag: String) : Tagged {
        BED("Bed"),
        CRYSTAL("Crystal"),
        OBSIDIAN("Obsidian"),
        ANCHOR("Anchor"),
    }

    /** Health + absorption below [minHealth]. */
    private object Health : ToggleableValueGroup(ModuleTestAT, "Health", true) {
        val minHealth by float("MinHealth", 6.0f, 0.0f..36.0f)
    }

    /** Our own fall distance above [fallDistance]. */
    private object OnFalling : ToggleableValueGroup(ModuleTestAT, "OnFalling", true) {
        val fallDistance by float("FallDistance", 3.0f, 0.0f..20.0f)
    }

    /** Another player falling onto us from within [radius] and [height]. */
    private object FallingPlayer : ToggleableValueGroup(ModuleTestAT, "FallingPlayer", false) {
        val radius by float("Radius", 5.0f, 1.0f..10.0f)
        val height by float("Height", 3.0f, 1.0f..10.0f)
    }

    /** An explosive near us that could deal at least [damage]. */
    private object NearExplosive : ToggleableValueGroup(ModuleTestAT, "NearExplosive", true) {
        val damage by float("Damage", 1.0f, 0.0f..36.0f)
        val threats by multiEnumChoice("Threats", Threat.BED, Threat.CRYSTAL, Threat.OBSIDIAN, Threat.ANCHOR)
    }

    init {
        tree(Health)
        tree(OnFalling)
        tree(FallingPlayer)
        tree(NearExplosive)
    }

    // ── State (`AutoTotem.java:205-245`) ─────────────────────────────────────

    /** The hotbar slot our swap took a totem from, where the original offhand item now sits; -1 when none. */
    private var restoreSlot = -1

    /** What was in the offhand before our first swap, to recognise it in [restoreSlot]. */
    private var restoreStack: ItemStack? = null

    override fun onEnabled() = reset()

    override fun onDisabled() = reset()

    private fun reset() {
        restoreSlot = -1
        restoreStack = null
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Suppress("unused")
    private val tickHandler = tickHandler { onTick() }

    /**
     * `pilot_autototem.java` / AUTO_TOTEM.md "Обработчик тика": while [inDanger], a totem from the hotbar goes into
     * an offhand that holds none. GUESS: once the danger is over, the item the first swap displaced goes back - that
     * is what the slot and stack donut resets on enable/disable are for - but only when there was one, and only while
     * it still sits where the swap put it.
     */
    private fun onTick() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (inDanger(player, level)) {
            if (player.offhandItem.`is`(Items.TOTEM_OF_UNDYING)) {
                return
            }
            val slot = totemSlot(player)
            if (slot == -1) {
                return
            }
            if (restoreSlot == -1) {
                restoreSlot = slot
                restoreStack = player.offhandItem.copy()
            }
            swap(player, slot)
            return
        }

        val slot = restoreSlot
        if (slot == -1) {
            return
        }
        val original = restoreStack
        reset()
        if (original != null && !original.isEmpty && player.offhandItem.`is`(Items.TOTEM_OF_UNDYING) &&
            ItemStack.isSameItemSameComponents(player.inventory.getItem(slot), original)
        ) {
            swap(player, slot)
        }
    }

    /**
     * Hotbar only - the swap announces the slot with `SetCarriedItem`, which only takes 0..8. GUESS: a plain totem
     * (`SocialCommand.java:55409-55427`: no enchantments, no attribute modifiers) first, then any.
     */
    private fun totemSlot(player: LocalPlayer): Int {
        val totems = (0..8).filter { player.inventory.getItem(it).`is`(Items.TOTEM_OF_UNDYING) }
        return totems.firstOrNull { isPlainTotem(player.inventory.getItem(it)) } ?: totems.firstOrNull() ?: -1
    }

    /** `SocialCommand.IIIiIiIIIIiiIi` (`SocialCommand.java:55409-55427`), 1:1. */
    private fun isPlainTotem(stack: ItemStack): Boolean {
        if (!EnchantmentHelper.getEnchantmentsForCrafting(stack).isEmpty) {
            return false
        }
        val modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS)?.modifiers() ?: return true
        return modifiers.isEmpty()
    }

    /**
     * f25 (`c/iiIiiIIIii.IIIIIIIIiIiIiI(LocalPlayer, AutoTotem, int)`): bring the server's held slot up to date,
     * then `SetCarriedItem(slot)` -> `SWAP_ITEM_WITH_OFFHAND` -> `SetCarriedItem(held)`, sent raw, and mirror the
     * exchange in the client's inventory. No container click, so `MultiActionsC` does not apply.
     *
     * "held" is the slot the server holds after the sync; with [SilentHotbar] in play that is
     * [SilentHotbar.serversideSlot], not `Inventory.selected`. When [slot] IS the held slot the two `SetCarriedItem`s
     * repeat the held slot - donut sends them anyway.
     */
    private fun swap(player: LocalPlayer, slot: Int) {
        interaction.ensureHasSentCarriedItem()
        val held = SilentHotbar.serversideSlot

        network.send(ServerboundSetCarriedItemPacket(slot))
        network.send(
            ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO,
                Direction.DOWN
            )
        )
        network.send(ServerboundSetCarriedItemPacket(held))

        val inventory = player.inventory
        val fromSlot = inventory.getItem(slot)
        val fromOffhand = inventory.getItem(Inventory.SLOT_OFFHAND)
        inventory.setItem(slot, fromOffhand)
        inventory.setItem(Inventory.SLOT_OFFHAND, fromSlot)
    }

    // ── Threats (f22) ────────────────────────────────────────────────────────

    /**
     * f22's OR-cascade, in its order: Health, On Falling, the entities around us (Falling Player; Crystal), then the
     * block scan (Bed, Obsidian, Anchor). Each branch is gated by its toggle, each explosive by `Threats`.
     */
    @Suppress("ReturnCount")
    private fun inDanger(player: LocalPlayer, level: ClientLevel): Boolean {
        if (Health.enabled && player.health + player.absorptionAmount < Health.minHealth) {
            return true
        }
        if (OnFalling.enabled && player.fallDistance > OnFalling.fallDistance) {
            return true
        }
        if (FallingPlayer.enabled && fallingPlayerAbove(player, level)) {
            return true
        }
        if (!NearExplosive.enabled) {
            return false
        }

        // f22: any end crystal in our box inflated by Radius, no damage test on this branch.
        if (Threat.CRYSTAL in NearExplosive.threats &&
            level.getEntities(player, player.boundingBox.inflate(FallingPlayer.radius.toDouble()))
                .any { it is EndCrystal }
        ) {
            return true
        }

        return blockThreat(player, level)
    }

    /**
     * f23/f24 (two copies that disagree on the box and the result's polarity; this takes their common ground): a
     * player other than us, not on the ground and falling (downward motion, or already past `Fall Distance`), at most
     * [FallingPlayer.height] above our feet and within [FallingPlayer.radius] horizontally.
     */
    private fun fallingPlayerAbove(player: LocalPlayer, level: ClientLevel): Boolean {
        val radius = FallingPlayer.radius.toDouble()
        val height = FallingPlayer.height.toDouble()
        val box = AABB(
            player.x - radius, player.y, player.z - radius,
            player.x + radius, player.y + height, player.z + radius
        )
        return level.getEntities(player, box).any { entity ->
            if (entity !is Player || entity.onGround()) {
                return@any false
            }
            val dx = entity.x - player.x
            val dz = entity.z - player.z
            val dy = entity.y - player.y
            dx * dx + dz * dz <= radius * radius && dy in 0.0..height &&
                (entity.deltaMovement.y < 0.0 || entity.fallDistance >= OnFalling.fallDistance)
        }
    }

    /**
     * f22's block scan: every cell within Radius horizontally and Height vertically of our feet, one explosion test
     * per enabled block threat. The test itself is native in donut; GUESS, the physically meaningful one - the
     * explosion that block can produce, at the centre and power vanilla uses, must reach [NearExplosive.damage]:
     * a bed where beds explode (`BedBlock.java:94-104`, power 5 at the cell centre), a crystal that could be placed on
     * obsidian with air above (power 6 at `pos + (0.5, 1, 0.5)`), a respawn anchor where anchors explode (power 5 at
     * its centre, its own cell cleared first).
     */
    @Suppress("CognitiveComplexMethod", "ReturnCount")
    private fun blockThreat(player: LocalPlayer, level: ClientLevel): Boolean {
        val threats = NearExplosive.threats
        val bed = Threat.BED in threats && level.bedRule.explodes()
        val obsidian = Threat.OBSIDIAN in threats
        val anchor = Threat.ANCHOR in threats && !level.respawnAnchorWorks
        if (!bed && !obsidian && !anchor) {
            return false
        }

        val radius = ceil(FallingPlayer.radius).toInt()
        val height = ceil(FallingPlayer.height).toInt()
        val feet = player.blockPosition()
        val from = feet.offset(-radius, -height, -radius)
        val to = feet.offset(radius, height, radius)
        for (cursor in BlockPos.betweenClosed(from, to)) {
            val state = level.getBlockState(cursor)
            val hit = when {
                bed && state.block is BedBlock ->
                    reaches(player, level, Vec3.atCenterOf(cursor), 5.0f, cursor.immutable())

                obsidian && state.`is`(Blocks.OBSIDIAN) && level.getBlockState(cursor.above()).isAir ->
                    reaches(player, level, Vec3(cursor.x + 0.5, cursor.y + 1.0, cursor.z + 0.5), 6.0f, null)

                anchor && state.`is`(Blocks.RESPAWN_ANCHOR) ->
                    reaches(player, level, Vec3.atCenterOf(cursor), 5.0f, cursor.immutable())

                else -> false
            }
            if (hit) {
                return true
            }
        }
        return false
    }

    /** donut's damage model: the unobstructed bound first, the exposure grid only when that bound is enough. */
    private fun reaches(
        player: LocalPlayer,
        level: ClientLevel,
        center: Vec3,
        power: Float,
        airPos: BlockPos?,
    ): Boolean {
        val threshold = NearExplosive.damage
        if (TestAAMath.damage(Vec3.ZERO, 1.0f, player, power, center) < threshold) {
            return false
        }
        val exposure = TestAAMath.exposure(level, center, player, airPos)
        return TestAAMath.damage(Vec3.ZERO, exposure, player, power, center) >= threshold
    }
}
