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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.exec

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.AnchorSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.aim.RayTests
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.AnchorMove
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.seq.AnchorPlan
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world.AnchorView
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.entity.interactBlock
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * What [AnchorExecutor.execute] did with one tick's [AnchorPlan].
 *
 * [sent] is the only thing the sequencer may act on: it means the `ServerboundUseItemOnPacket` LEFT the client.
 * `MultiPlayerGameMode.useItemOn` puts the packet on the wire inside `startPrediction` whatever the interaction
 * returns (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:319-331`), so a click that the client-side
 * simulation refused still cost the tick's one block interaction - which is exactly what
 * `AnchorFlow.onSent` has to be told, and why [sent] and [reason] are independent.
 *
 * The one case where nothing goes out despite reaching `useItemOn` is the world border (`:321-322`), and
 * [AnchorExecutor] checks that itself before it clicks.
 */
data class AnchorExecReport(val move: AnchorMove, val sent: Boolean, val reason: String?) {

    override fun toString(): String {
        val verdict = if (sent) "sent" else "skipped"
        return if (reason == null) "$move $verdict" else "$move $verdict: $reason"
    }
}

/**
 * Performs the ONE block interaction of an anchor tick at the vanilla click position: the RETURN of
 * `Minecraft.handleKeybinds` (`InputHandleEvent`, `MixinMinecraft.hookHandleInputEvent`), i.e. after
 * `gameMode.tick()` has sent the carried-item change chosen in PLAN and before `LocalPlayer.tick` sends the
 * movement packet (`net/minecraft/client/Minecraft.java:1823-1927`). Every step re-verifies its preconditions
 * against the live world and against `AuraRotation.outgoing`, and rejects with a reason instead of acting.
 *
 * ### One interaction, three flavours, and never two in a tick
 * Place, charge and detonate are all `ServerboundUseItemOnPacket` on a block - the plain `BlockItem` path
 * (`net/minecraft/world/item/Items.java:2193`), `RespawnAnchorBlock.useItemOn`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:78-97`) and `useWithoutItem` (`:99-123`) - and Grim's
 * `PacketOrderI` allows ONE of those per tick. So [execute] performs exactly one [interactBlock] and returns; the
 * three flavours differ only in WHICH block is clicked and what has to be in the hand:
 *
 * | move | clicked block | hand | client result |
 * |---|---|---|---|
 * | [AnchorMove.PLACE] | the NEIGHBOUR, on the face pointing at the cell - or, for the replace-in-place form, the
 *   CELL itself on any visible face | `Items.RESPAWN_ANCHOR` | `SUCCESS` |
 * | [AnchorMove.CHARGE] | the anchor itself, any visible face | `Items.GLOWSTONE` | `SUCCESS` (`:91`) |
 * | [AnchorMove.DETONATE] | the anchor itself, any visible face | anything but glowstone | `CONSUME` (`:120-122`) |
 *
 * `CONSUME` is `new Success(SwingSource.NONE, ...)` (`net/minecraft/world/InteractionResult.java:13`), so it
 * `consumesAction()` but `interactBlock` does not swing for it - which is precisely what a vanilla client does on
 * a detonating click, and why the detonate tick carries no `[SW]`.
 *
 * ### The rotation every ray is traced against
 * [AuraRotation.outgoing] is the rotation THIS tick's movement packet will carry: `RotationManager.update()` ran
 * at the head of the tick, right after the `RotationUpdateEvent` the module plans in (RotationManager.kt:319-324),
 * so by the time `InputHandleEvent` fires the value is final and normalized. A rotation requested in PLAN
 * therefore already covers the click sent here - which is the vanilla ordering (`MouseHandler.turnPlayer` runs in
 * the render section, `client/Minecraft.java:1321`, AFTER the tick loop at `:1301`, so `pick(1.0F)` at `:1823`,
 * the clicks at `:1865` and `sendPosition` all use one rotation) and the one Grim evaluates (queued places drained
 * at `CheckManagerListener.java:680-681`; `scaffolding/RotationPlace.java:52`: *"Use post flying because it has
 * the correct rotation"*).
 *
 * The ray tests are EXACT and no tolerance is ever widened to make an attempt land: a single post-flying miss arms
 * `RotationPlace`'s `flagBuffer`, and the pre-flying path it enables CANCELS placements for the next 10
 * interactions. A miss sends nothing and reports `ray misses`; the next tick retries with the rotation by then on
 * the wire.
 *
 * ### Foreign-click gate (`allowUse`)
 * `InputHandleEvent` fires at the RETURN of `handleKeybinds`, i.e. AFTER vanilla's own attack loop
 * (`Minecraft.java:2050-2052`), its use loops (`:2054-2056`, `:2069-2071`) and `continueAttack` (`:2073`). A block
 * interaction may not follow a foreign use, dig or entity interaction inside one tick window - `PacketOrderI`
 * (`checks/impl/packetorder/PacketOrderI.java:98-123`), `MultiActionsF` (`MultiActionsF.java:21, 43-74`) and
 * `PacketOrderJ` (`PacketOrderJ.java:12, 24`) all read that window. The hub clears the gate for the tick and a
 * closed one sends nothing, reported as `use blocked this tick`.
 *
 * ### Gates copied from vanilla
 * `player.isAlive && !player.isSpectator` (a spectator use is `CONSUME`d client-side,
 * `MultiPlayerGameMode.java:336-337`); `player.isUsingItem` - `handleKeybinds` swallows every click while an item
 * is in use (`Minecraft.java:2036-2047`); `player.isHandsBusy` (`startUseItem`, `:1740`);
 * `!gameMode.isDestroying()` (`:1738`); the item of the hand must be enabled (`:1747`).
 *
 * Sneaking is NOT gated here but one level up, in the module hub: `performUseItemOn` sets
 * `suppressUsingBlock = player.isSecondaryUseActive() && haveSomethingInOurHands`
 * (`MultiPlayerGameMode.java:339-341`) and skips the block entirely, so the whole module stands down rather than
 * putting dead packets on the wire.
 *
 * ### The whole attack family is gone
 * The crystal aura's executor also owned an attack, a phantom attack, `attackRangeGate`, `phantomSprintReset` and
 * the vanilla-like offhand hand loop. None of it has an analogue here: an anchor is a block, every interaction is
 * a main-hand `UseItemOn`, and the offhand can never carry one (the block takes the click before the item does, so
 * an offhand placement is a different interaction entirely).
 */
class AnchorExecutor(private val settings: AnchorSettings) : MinecraftShortcuts {

    private val range: Double
        get() = settings.range.toDouble()

    /**
     * Performs [plan]'s move and reports what happened. [view] is the view the plan was made from (its block
     * queries read the live level), [rayIgnore] the LookThrough filter, [allowUse] the hub's foreign-click gate.
     *
     * Returns `sent = false` with a reason for every rejection; the caller then leaves its delay chronometer
     * untouched, does not advance the flow, and retries next tick. [AnchorMove.NONE] never reaches here - the
     * sequencer answers it without an executor call - but [cellGate] rejects it anyway rather than clicking.
     */
    fun execute(
        plan: AnchorPlan,
        view: AnchorView,
        rayIgnore: (Entity) -> Boolean,
        allowUse: Boolean,
    ): AnchorExecReport {
        val move = plan.move
        val gate = if (allowUse) {
            commonGate() ?: slotGate(move, plan.slot) ?: cellGate(move, plan, view)
        } else {
            USE_BLOCKED
        }
        if (gate != null) return AnchorExecReport(move, sent = false, reason = gate)

        // A null `face` is unconstrained on purpose: the charge and the detonation read no direction, and a
        // replace-in-place placement puts the anchor into the clicked cell whatever direction came back
        // (`BlockPlaceContext.java:31-37, 54-61`). Whatever the ray produced is what goes on the wire.
        val hit = byAim(AuraRotation.outgoing) { rotation ->
            RayTests.hitsBlock(view.eye, rotation, plan.clickPos, range, rayIgnore, settings.throughBlocks)
                ?.takeIf { plan.face == null || it.direction == plan.face }
        } ?: return AnchorExecReport(move, sent = false, reason = "ray misses")

        // `useItemOn` sends the packet inside `startPrediction` whatever the interaction returns, so from here on
        // the tick's one block interaction is spent and `sent` is true even for a client-side refusal.
        val result = interactBlock(hit, InteractionHand.MAIN_HAND, SwingMode.DO_NOT_HIDE)
        val reason = if (result is InteractionResult.Success) null else "not consumed ($result)"
        return AnchorExecReport(move, sent = true, reason = reason)
    }

    /**
     * Runs [test] against [aim] - the rotation this tick's movement packet will carry - and, when that misses,
     * against the rotation already on the wire.
     *
     * Both are EXACT rotations, and both are raytrace candidates of the post-flying check that actually decides:
     * `(yawN, pitchN)` and `(yawN-1, pitchN-1)` (`scaffolding/RotationPlace.java:83-134`), and `didRayTraceHit`
     * passes as soon as ANY candidate reaches the target. So nothing here is loosened: each attempt is the same
     * exact ray test from a rotation the server is known to accept, and a click that reaches from neither is still
     * refused.
     *
     * Why both rather than the outgoing one alone: that would drop an interaction whenever our rotation request
     * lost to a higher-priority module and whenever a `RotationMode.SMOOTH` step is still in flight - both cases
     * the wire-only verification used to perform, so accepting both keeps this a strict superset of it.
     */
    private inline fun <T : Any> byAim(aim: Rotation, test: (Rotation) -> T?): T? {
        test(aim)?.let { return it }
        val wire = AuraRotation.wire
        return if (wire == aim) null else test(wire)
    }

    /** Gates every flavour shares (see the class KDoc), null when open. */
    private fun commonGate(): String? = when {
        !player.isAlive || player.isSpectator -> "dead or spectator"
        player.isUsingItem -> "using an item"
        player.isHandsBusy -> "hands busy"
        interaction.isDestroying -> "destroying a block"
        else -> null
    }

    /**
     * The hand is the one the phase needs, the server holds the slot, and the item is enabled
     * (`Minecraft.java:1747`).
     *
     * A null [slot] means "whatever is already in the main hand" - `SwitchMode.NONE`, or `DetonateHand.HELD` - so
     * only the item test applies. [AnchorMove.DETONATE] has no expected item but one FORBIDDEN one: glowstone is
     * `isRespawnFuel` (`RespawnAnchorBlock.java:125-127`) and would charge the anchor instead of detonating it.
     */
    private fun slotGate(move: AnchorMove, slot: HotbarItemSlot?): String? {
        val held = player.getItemInHand(InteractionHand.MAIN_HAND)
        val expected = expectedItem(move)
        return when {
            slot != null && SilentHotbar.serversideSlot != slot.hotbarIndex -> "slot not held server-side"
            expected != null && held.item !== expected -> "item no longer in hand"
            expected == null && held.`is`(Items.GLOWSTONE) -> "glowstone in hand would charge, not detonate"
            !held.isItemEnabled(world.enabledFeatures()) -> "item disabled"
            else -> null
        }
    }

    /**
     * The cell is still in the state the move expects, and the clicked block is still inside the world border
     * (`MultiPlayerGameMode.java:321-322` would drop the packet without sending it).
     *
     * - [AnchorMove.PLACE]: nothing stands in the cell, it is replaceable and no `blocksBuilding` entity is in it
     *   - the two halves of `BlockItem.canPlace` (`net/minecraft/world/item/BlockItem.java:132-136`).
     * - [AnchorMove.CHARGE]: an anchor stands at `CHARGE == 0`. Strict on purpose: a charge that rose under us
     *   means the anchor is ready to detonate, and spending a second glowstone on it would be pure waste - the
     *   flow reads the same blockstate next tick and enters `DETONATE` instead.
     * - [AnchorMove.DETONATE]: an anchor stands at `CHARGE >= 1`, which is exactly what `useWithoutItem` requires
     *   (`RespawnAnchorBlock.java:101`).
     */
    private fun cellGate(move: AnchorMove, plan: AnchorPlan, view: AnchorView): String? {
        if (!world.worldBorder.isWithinBounds(plan.clickPos)) return "outside the world border"

        val charge = view.anchorCharge(plan.cell)
        return when (move) {
            AnchorMove.PLACE -> when {
                charge != null -> "an anchor already stands there"
                !view.isReplaceable(plan.cell) -> "cell no longer replaceable"
                !view.isUnobstructed(plan.cell) -> "cell obstructed by an entity"
                else -> null
            }
            AnchorMove.CHARGE -> when (charge) {
                null -> "no anchor to charge"
                0 -> null
                else -> "already charged"
            }
            AnchorMove.DETONATE -> when {
                charge == null -> "no anchor to detonate"
                charge == 0 -> "not charged"
                else -> null
            }
            AnchorMove.NONE -> "nothing to do"
        }
    }

    private fun expectedItem(move: AnchorMove): Item? = when (move) {
        AnchorMove.PLACE -> Items.RESPAWN_ANCHOR
        AnchorMove.CHARGE -> Items.GLOWSTONE
        else -> null
    }

    private companion object {

        /** Reported when the hub's `allowUse` gate is closed (class KDoc). */
        const val USE_BLOCKED = "use blocked this tick"
    }
}

/**
 * Conservative static answer to "would this MAIN_HAND item consume the click by itself?", i.e. would
 * `performUseItemOn`'s fall-through to `itemStack.useOn` / `useItem` do anything
 * (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:360-374`, `Minecraft.java:1790`).
 *
 * An empty hand never consumes; a plain `Item` (swords, pickaxes, ... are registered as plain items,
 * `net/minecraft/world/item/Items.java:1217`) has the default `useOn` = `PASS`
 * (`net/minecraft/world/item/Item.java:179-181`) and a `use` that only consumes with `CONSUMABLE`, a swappable
 * `EQUIPPABLE`, `BLOCKS_ATTACKS` or `KINETIC_WEAPON` (`Item.java:188-211`); every `Item` SUBCLASS (`BlockItem`,
 * tools with `useOn` overrides, shields, ...) is treated as consuming.
 *
 * Copied from `crystalaura/exec/ActionExecutor.kt:548-556`, where it decides whether an OFFHAND placement is
 * legal. Here it is used INVERTED, by `DetonateHand.SAFE`: the detonating hand should hold an item that cannot
 * consume the click, so that in the desync window - our charge not applied server-side yet, the server seeing
 * `CHARGE == 0`, `useWithoutItem` returning `PASS` (`RespawnAnchorBlock.java:101`) - the fall-through is a
 * harmless no-op instead of a stray respawn anchor placed against the clicked face.
 *
 * Note the convenient consequence: `Items.GLOWSTONE` and `Items.RESPAWN_ANCHOR` are both `BlockItem`s, so both are
 * rejected by the SAFE pick without a special case, and an EMPTY hotbar slot is the ideal candidate.
 */
internal fun consumesUse(stack: ItemStack): Boolean {
    if (stack.isEmpty) return false
    if (stack.item.javaClass != Item::class.java) return true

    return stack.has(DataComponents.CONSUMABLE) ||
        stack.get(DataComponents.EQUIPPABLE)?.swappable() == true ||
        stack.has(DataComponents.BLOCKS_ATTACKS) ||
        stack.has(DataComponents.KINETIC_WEAPON)
}
