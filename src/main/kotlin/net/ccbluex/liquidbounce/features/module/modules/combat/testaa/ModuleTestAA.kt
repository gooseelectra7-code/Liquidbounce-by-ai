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
package net.ccbluex.liquidbounce.features.module.modules.combat.testaa

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.entity.PlayerSimulationCache
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * testAA module
 *
 * A port of the "Anchor Aura" module of the donut / "aesthetic" client (`c/IiiiIiIiII`, decompiled as
 * `C:\Users\caves\Documents\donut\work\src_named\c\AnchorAura.java`; the placement sub-group is `c/iIIIIiiiii`,
 * `i^I^I^I^Iiiiii.java`). The user asked for it strictly 1:1, WITHOUT this client's own anticheat guards: none of
 * `combat/autoanchor`'s invariants (one interaction per tick, the wait for the server's ack of our placement, the
 * blast shadow, the reach cap, the aura chain, the totem stand-downs) applies here, and none must be added under this
 * name. It is NOT Grim-audited. `ModuleAutoAnchor` stays the client's anchor aura; enable one of the two.
 *
 * ## What is a port and what is not
 * The jar ships most of the module as Java, but the TICK HANDLER (`AnchorAura.java:511`), the placement and cover
 * executors (`i^I^I^I^Iiiiii.java:403`, `:954`), the self-safety check (`SocialCommand.java:65196`), the rotation
 * gate (`SocialCommand.java:34577`) and the `<clinit>` that creates the settings are `native` - server-compiled AOT
 * code whose bodies are not in the jar. They were not traced here.
 *
 * PORTED as written (every function names its source): the damage model and its exposure grid ([TestAAMath]), the
 * aim searches, raycasts, support / safe-placement tests and slot finders ([TestAAWorld]), the three candidate
 * searches with their sort orders and the sticky-aim rule ([TestAASearch]), the charge/detonate step ([planInteract]
 * + [executeInteract], `AnchorAura.java:383-500`), the death cooldown (`SocialCommand.java:62792-62820`,
 * `AnchorAura.java:196-208`), the target filter (`AnchorAura.java:339-348`) and the forced un-sneak
 * (`AnchorAura.java:611-626`). A fresh read-only audit compared all of it against the decompile (and the bytecode
 * where CFR misprints) and found no behavioural mismatch.
 *
 * SETTINGS: names, order and defaults come from a dump of donut's own default config
 * (`donut\work\modules_default.bin.out`: Range 6, Predict 0, Min Damage 6, Max Self Damage 6, Blast Through on, Remove
 * Locally on, Sort Damage, Cover off, Danger Threshold 4, Place Delay 0, Render on, Rotation Speed 180, Randomization
 * 2), which settle what the native `<clinit>` could not. The slider RANGES of Range, Predict, Min Damage and Max Self
 * Damage are not in that dump and are GUESSES; Danger Threshold, Place Delay, Rotation Speed and Randomization ranges
 * are read from their Java constructors. The two colours decode as zigzag varints to Tailwind amber-500 at alpha 48
 * and amber-300 at alpha 230 (INFERRED from the decode landing on exactly those palette entries).
 *
 * ## The tick: PLAN, then EXECUTE
 * donut's rotation gate writes the stepped rotation into its rotation holder and returns, and the lambda reads that
 * holder for its raycast right away (`AnchorAura.java:441-455`; the write is shown by a third-party reconstruction,
 * `donut\work\aot_java\f18_rotation_worker.java`) - it aims and clicks in ONE tick. The equivalent here: the aim is
 * filed in [plan] at [RotationUpdateEvent], [RotationManager] steps it immediately after, and [execute] at
 * [InputHandleEvent] raycasts with [outgoing] - the rotation this very tick's movement packet will carry - and clicks.
 * Same shape as `combat/crystalaura`'s PLAN/EXECUTE.
 *
 * RECONSTRUCTED, every decision GUESS: the order of the tick (death bookkeeping, target, prediction, existing anchors
 * FIRST, then the placement - in the same tick as a click that went out, not while a click still waits for its
 * rotation), how `Place Delay` gates a placement, what `Cover` is triggered by, what the placement / cover executors
 * do (modelled on the charge/detonate step: aim, check the outgoing rotation, swap, click, swing), the self-safety
 * check ([TestAASearch]), what `Rotation Speed` limits (per axis, per tick) and when donut's rotation gate reports
 * failure.
 *
 * SUBSTITUTED: donut's `SwapManager` (a priority lease on the REAL selected slot, restored on release) by
 * [SilentHotbar] (same packets, the visible hotbar does not move); its movement simulator by
 * [PlayerSimulationCache], which donut's own cache mirrors down to the error message; its self-measured ping by the
 * tab-list latency it falls back to; its `TickScheduler` (one task per tick across modules, by priority) by running
 * the action directly; and its rotation manager by [RotationManager] with a speed-limited processor.
 */
@Suppress("TooManyFunctions", "MagicNumber")
object ModuleTestAA : ClientModule("testAA", ModuleCategories.COMBAT) {

    private enum class SortMode(override val tag: String, val search: TestAASearch.SortMode) : Tagged {
        DAMAGE("Damage", TestAASearch.SortMode.DAMAGE),
        SAFETY("Safety", TestAASearch.SortMode.SAFETY),
    }

    /** Target range: the target's box, measured from our eyes (`SocialCommand.iiiiIiiIIiiii`). */
    private val range by float("Range", 6.0f, 1.0f..12.0f)

    /** Prediction ticks for the target; 0 = `min(ceil(ping / 50) + 1, 10)` (`DamageUtil.IIiIiIIIiIiii`). */
    private val predict by int("Predict", 0, 0..10, "ticks")

    private val minDamage by float("MinDamage", 6.0f, 0.0f..36.0f)
    private val maxSelfDamage by float("MaxSelfDamage", 6.0f, 0.0f..36.0f)

    /** Re-score anchors that miss [minDamage] only through cover, with the cells the blast clears as air. */
    private val blastThrough by boolean("BlastThrough", true)

    /** Remove the detonated anchor from the CLIENT's world at once (`AnchorAura.java:489-491`). */
    private val removeLocally by boolean("RemoveLocally", true)

    private val sort by enumChoice("Sort", SortMode.DAMAGE)

    private object Placing : ValueGroup("Placing") {
        val placeDelay by int("PlaceDelay", 0, 0..20, "ticks")

        object Cover : ToggleableValueGroup(ModuleTestAA, "Cover", false) {
            val dangerThreshold by float("DangerThreshold", 4.0f, 0.0f..20.0f)
        }

        init {
            tree(Cover)
        }
    }

    private object Render : ToggleableValueGroup(ModuleTestAA, "Render", true) {
        val fill by color("Fill", Color4b(245, 158, 11, 48))
        val outline by color("Outline", Color4b(252, 211, 77, 230))
    }

    private object Rotations : ValueGroup("Rotation") {
        val rotationSpeed by float("RotationSpeed", 180.0f, 1.0f..180.0f, "°/t")
        val randomization by float("Randomization", 2.0f, 0.0f..5.0f, "°")
    }

    init {
        tree(Placing)
        tree(Render)
        tree(Rotations)
    }

    /** donut calls its rotation gate with 15 (`AnchorAura.java:437-441`); read as a priority, see [rotate]. */
    private val rotationPriority = Priority.IMPORTANT_FOR_USAGE_2

    /** Ticks a hotbar lease outlives its last renewal ([SilentHotbar] expires it after that). */
    private const val LEASE_TICKS = 1

    // ── State ────────────────────────────────────────────────────────────────

    /** What [plan] decided, for [execute] to carry out in the same tick. */
    private sealed interface Plan

    /** Charge / detonate [interaction] against [target]; [params] feed the placement that may follow. */
    private class InteractPlan(
        val target: Player,
        val interaction: TestAASearch.Interaction,
        val params: TestAASearch.Params,
    ) : Plan

    /** Place the block in hotbar [slot] into [pos]; [anchor] is false for a cover block. */
    private class PlacePlan(val pos: BlockPos, val slot: Int, val anchor: Boolean) : Plan

    private var plan: Plan? = null

    /** `AnchorAura.iIIIiiiiIiI`: player id -> `nanoTime` until which a DEAD player is not a target. */
    private val deaths = ConcurrentHashMap<Int, Long>()

    /** Death packets parked on the netty thread, with their arrival time (the entity is resolved on the tick). */
    private val deathPackets = ConcurrentLinkedQueue<Pair<ClientboundEntityEventPacket, Long>>()

    /** `AnchorAura.iIIIiiiiIii`. */
    private var currentTarget: Player? = null

    /** `AnchorAura.iIIiIIIIiii`: something is being clicked this tick, so sneak is released. */
    private var acting = false

    /** `iIIIIiiiii.iIiiIIIIiiI`: ticks since our last anchor placement, `Int.MAX_VALUE` before the first. */
    private var ticksSincePlace = Int.MAX_VALUE

    /** What [Render] draws: the anchor being clicked or the cell being placed into. */
    private var renderPos: BlockPos? = null

    override fun onEnabled() = reset()

    override fun onDisabled() = reset()

    private fun reset() {
        deaths.clear()
        deathPackets.clear()
        plan = null
        currentTarget = null
        acting = false
        renderPos = null
        SilentHotbar.resetSlot(this)
    }

    // ── Death cooldown ───────────────────────────────────────────────────────

    /**
     * `SocialCommand.IIIiiIIiiIiiIi` (`SocialCommand.java:62792-62820`): only `EntityEvent.DEATH` (3), not the totem
     * pop (35). donut resolves the entity on the packet thread; here the packet is parked and resolved at the tick
     * head, because the world must not be read off the netty thread. The deadline still counts from arrival.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) {
            return@handler
        }
        val packet = event.packet
        if (packet is ClientboundEntityEventPacket && packet.eventId == EntityEvent.DEATH) {
            deathPackets += packet to System.nanoTime()
        }
    }

    private fun updateDeaths(level: ClientLevel, player: LocalPlayer) {
        while (true) {
            val (packet, arrival) = deathPackets.poll() ?: break
            val dead = packet.getEntity(level) as? Player ?: continue
            deaths[dead.id] = arrival + (TestAAWorld.ping(player).toLong() + 200L) * 1_000_000L
        }

        // `AnchorAura.IiIiiiiIIIiII` (`AnchorAura.java:196-208`): gone, or back alive past the deadline.
        val now = System.nanoTime()
        deaths.entries.removeIf { (id, deadline) ->
            val entity = level.getEntity(id) as? Player
            entity == null || now > deadline && entity.isAlive
        }
    }

    // ── PLAN ─────────────────────────────────────────────────────────────────

    /**
     * Head of the client tick, before [RotationManager] steps the aim: decide, and file the rotation the action
     * needs, so it is stepped in the same tick.
     */
    @Suppress("unused")
    private val planHandler = handler<RotationUpdateEvent> { plan() }

    @Suppress("ReturnCount")
    private fun plan() {
        plan = null
        acting = false
        renderPos = null

        val player = mc.player ?: return
        val level = mc.level ?: return

        updateDeaths(level, player)
        if (ticksSincePlace != Int.MAX_VALUE) {
            ticksSincePlace++
        }

        val target = findTarget(player, level)
        currentTarget = target
        if (target == null) {
            SilentHotbar.resetSlot(this)
            return
        }

        val params = TestAASearch.Params(
            minDamage = minDamage,
            maxSelfDamage = maxSelfDamage,
            blastThrough = blastThrough,
            sort = sort.search,
            randomization = Rotations.randomization,
            offset = predictedOffset(target, TestAAWorld.predictionTicks(predict, player)),
            friends = friendsNear(player, level),
            predictedBoxes = predictedBoxes(level, player),
        )

        val server = RotationManager.serverRotation
        val interaction = TestAASearch.findInteraction(level, player, target, server.yaw, server.pitch, params)
        plan = if (interaction != null) {
            acting = true
            renderPos = interaction.pos
            (if (Placing.Cover.enabled) planCover(player, level, interaction.pos) else null)
                ?: planInteract(player, target, interaction, params)
        } else {
            planPlacement(player, level, target, params, afterClick = false)
        }

        if (plan != null) {
            lockItemUse()
        }
    }

    /**
     * The aim half of `AnchorAura.IiIiiiiIiIiii` (`AnchorAura.java:398-443`): from where our own simulation puts our
     * eyes next tick, the grid-snapped rotation onto the anchor's box (deflated by 0.2), recomputed from the hit
     * location relative to our own rotation, is filed through the rotation gate. No plan when the aim cannot reach
     * the anchor or the gate refuses - the lambda returns there without touching the hotbar.
     */
    private fun planInteract(
        player: LocalPlayer,
        target: Player,
        interaction: TestAASearch.Interaction,
        params: TestAASearch.Params,
    ): Plan? {
        if (!target.isAlive || deaths.containsKey(target.id) || currentTarget !== target) {
            return null
        }

        val pos = interaction.pos
        val feet = PlayerSimulationCache.getSimulationForLocalPlayer().getSnapshotAt(1).pos
        val eye = feet.add(0.0, player.eyeHeight.toDouble(), 0.0)
        val aim = TestAASearch.closestPoint(eye, AABB(pos).deflate(0.2))
        val range = player.blockInteractionRange()
        val hit = TestAAWorld.findInteractHit(player, pos, aim, range, 1.0f, eye, Rotations.randomization)
            ?: return null

        val delta = TestAAWorld.angleDelta(player.xRot, player.yRot, hit.location.subtract(eye))
        if (!rotate(player.yRot + delta.yaw, player.xRot + delta.pitch)) {
            return null
        }
        return InteractPlan(target, interaction, params)
    }

    /**
     * The placement half of the tick. [afterClick] (an anchor was just clicked this tick) keeps that click's hotbar
     * lease and render box when there is nothing to place.
     */
    @Suppress("ReturnCount")
    private fun planPlacement(
        player: LocalPlayer,
        level: ClientLevel,
        target: Player,
        params: TestAASearch.Params,
        afterClick: Boolean,
    ): PlacePlan? {
        val anchorSlot = TestAAWorld.hotbarSlotOf(player, Items.RESPAWN_ANCHOR)
        if (anchorSlot == -1) {
            if (!afterClick) {
                SilentHotbar.resetSlot(this)
            }
            return null
        }

        // `i^I^I^I^Iiiiii.java:497-510`: the target is predicted to where it will be when the anchor goes off - the
        // configured prediction, one tick for the click that follows the placement, and what is left of the delay.
        val remaining = if (ticksSincePlace == Int.MAX_VALUE) {
            0
        } else {
            max(0, Placing.placeDelay - ticksSincePlace - 1)
        }
        val ticks = TestAAWorld.predictionTicks(predict, player) + 1 + remaining
        val stack = player.inventory.getItem(anchorSlot)
        val placement = TestAASearch.findPlacement(
            level, player, target, player.blockInteractionRange(), stack, predictedOffset(target, ticks), params
        )
        if (placement == null) {
            if (!afterClick) {
                SilentHotbar.resetSlot(this)
            }
            return null
        }

        acting = true
        renderPos = placement.pos
        if (ticksSincePlace != Int.MAX_VALUE && ticksSincePlace < Placing.placeDelay) {
            return null
        }
        if (!rotate(placement.hit.yaw, placement.hit.pitch)) {
            return null
        }
        return PlacePlan(placement.pos, anchorSlot, anchor = true)
    }

    /**
     * Cover. donut's trigger and executor (`i^I^I^I^Iiiiii.java:954`) are native; GUESS: before an anchor is clicked,
     * if our own damage from it is above `Danger Threshold` and a cover block is in the hotbar, the best cover cell
     * ([TestAASearch.findCover]) is placed first, and the anchor waits for a tick in which no cover is found.
     */
    @Suppress("ReturnCount")
    private fun planCover(player: LocalPlayer, level: ClientLevel, anchorPos: BlockPos): Plan? {
        val self = TestAASearch.ownDamage(level, player, anchorPos)
        if (self <= Placing.Cover.dangerThreshold) {
            return null
        }

        val slot = coverSlot(player)
        if (slot == -1) {
            return null
        }

        val cover = TestAASearch.findCover(
            level, player, player.blockInteractionRange(), anchorPos, self, slot, Rotations.randomization
        ) ?: return null
        renderPos = cover.pos
        if (!rotate(cover.hit.yaw, cover.hit.pitch)) {
            return null
        }
        return PlacePlan(cover.pos, slot, anchor = false)
    }

    /** The block the cover is made of. Not visible in donut's Java; GUESS: obsidian, then crying obsidian. */
    private fun coverSlot(player: LocalPlayer): Int {
        val obsidian = TestAAWorld.hotbarSlotOf(player, Items.OBSIDIAN)
        if (obsidian != -1) {
            return obsidian
        }
        return TestAAWorld.hotbarSlotOf(player, Items.CRYING_OBSIDIAN)
    }

    // ── EXECUTE ──────────────────────────────────────────────────────────────

    /** Vanilla's click position (the RETURN of `handleKeybinds`), after this tick's rotation step. */
    @Suppress("unused")
    private val executeHandler = handler<InputHandleEvent> { execute() }

    private fun execute() {
        val current = plan ?: return
        plan = null

        val player = mc.player ?: return
        val level = mc.level ?: return
        val gameMode = mc.gameMode ?: return

        when (current) {
            is InteractPlan -> {
                if (!executeInteract(player, level, gameMode, current)) {
                    return
                }
                // GUESS: a click that went out is followed by the placement IN THE SAME TICK - the order of the one
                // third-party reconstruction of the native tick (`donut\work\aot_java\ANCHOR_AURA.md`, "deferred
                // placement"), and what `Remove Locally` makes possible: the detonated cell is air again client-side,
                // so it can be placed into anew. Its aim is filed for the next tick; it is only placed now if the
                // outgoing rotation already lands on a click that fills the cell.
                val next = planPlacement(player, level, current.target, current.params, afterClick = true)
                if (next != null) {
                    executePlace(player, level, gameMode, next)
                }
            }

            is PlacePlan -> executePlace(player, level, gameMode, current)
        }
    }

    /** The rotation this tick's movement packet will carry; final once [RotationManager] has stepped it. */
    private val outgoing: Rotation
        get() = RotationManager.currentRotation ?: mc.player?.rotation ?: Rotation.ZERO

    private fun lookedAt(player: LocalPlayer, level: ClientLevel): BlockHitResult {
        val rotation = outgoing
        return TestAAWorld.raycast(
            level, player, player.eyePosition, rotation.yaw, rotation.pitch, player.blockInteractionRange()
        )
    }

    /**
     * The click half of `AnchorAura.IiIiiiiIiIiii` (`AnchorAura.java:444-499`), 1:1: unless the rotation lands on the
     * anchor the first slot is merely pre-selected. When it lands: the first slot (glowstone if the anchor is empty)
     * is selected and the anchor clicked, and an empty anchor is detonated right after with the detonating slot IN
     * THE SAME TICK - two `UseItemOn`s back to back, as donut sends them. `Remove Locally` then erases the anchor
     * client-side, and the first slot is held again.
     *
     * @return true when the anchor was clicked (the flag only feeds the reconstructed tick order in [execute]).
     */
    @Suppress("ReturnCount")
    private fun executeInteract(
        player: LocalPlayer,
        level: ClientLevel,
        gameMode: MultiPlayerGameMode,
        plan: InteractPlan,
    ): Boolean {
        val interaction = plan.interaction
        val pos = interaction.pos
        val looked = lookedAt(player, level)
        if (looked.type != HitResult.Type.BLOCK || looked.blockPos != pos) {
            select(interaction.firstSlot)
            return false
        }

        if (!select(interaction.firstSlot)) {
            return false
        }
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, looked)
        player.swing(InteractionHand.MAIN_HAND)

        var detonated = !interaction.needsCharge
        if (interaction.needsCharge && interaction.detonateSlot != -1 && select(interaction.detonateSlot)) {
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, looked)
            player.swing(InteractionHand.MAIN_HAND)
            detonated = true
        }

        if (detonated && removeLocally) {
            level.removeBlock(pos, false)
        }

        select(interaction.firstSlot)
        return true
    }

    /**
     * Placement / cover executor. donut's (`i^I^I^I^Iiiiii.java:403`, `:954`) are native; this is the charge/detonate
     * step's shape - GUESS: click only when the outgoing rotation lands on a click that places the block into the
     * planned cell, otherwise pre-select its slot.
     */
    private fun executePlace(player: LocalPlayer, level: ClientLevel, gameMode: MultiPlayerGameMode, plan: PlacePlan) {
        val looked = lookedAt(player, level)
        val stack = player.inventory.getItem(plan.slot)
        if (looked.type != HitResult.Type.BLOCK || TestAAWorld.placementPos(player, looked, stack) != plan.pos) {
            select(plan.slot)
            return
        }

        if (!select(plan.slot)) {
            return
        }
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, looked)
        player.swing(InteractionHand.MAIN_HAND)
        if (plan.anchor) {
            ticksSincePlace = 0
        }
    }

    // ── Target ───────────────────────────────────────────────────────────────

    /**
     * `AnchorAura.IiIiiiiIiIiIi` (`AnchorAura.java:339-348`) as the filter and `AnchorAura.IiIiiiiIIIIiI`
     * (`:170-176`, `distanceToSqr` from our eyes) as the key: the nearest living, non-spectating, non-friend player
     * whose box is within [range] of our eyes and who did not just die.
     */
    private fun findTarget(player: LocalPlayer, level: ClientLevel): Player? {
        val eye = player.eyePosition
        return level.players()
            .filter { other ->
                other !== player && other.isAlive && !deaths.containsKey(other.id) && !other.isSpectator &&
                    !FriendManager.isFriend(other.gameProfile.name) &&
                    TestAASearch.closestPoint(eye, other.boundingBox).distanceTo(eye) <= range
            }
            .minByOrNull { it.distanceToSqr(eye) }
    }

    /** `SocialCommand.IIIiIIIiiIiIiI` (`SocialCommand.java:51877-51907`): FRIENDS within 16 blocks (feet to feet). */
    private fun friendsNear(player: LocalPlayer, level: ClientLevel): List<Player> =
        level.players().filter { other ->
            other !== player && other.isAlive && !other.isSpectator &&
                FriendManager.isFriend(other.gameProfile.name) && other.distanceToSqr(player) <= 256.0
        }

    /**
     * `DamageUtil.IIiIiIIIiIIiI` (`DamageUtil.java:570-590`): where the movement simulation puts [player] after
     * [ticks] ticks, relative to where it is now.
     */
    private fun predictedOffset(player: Player, ticks: Int): Vec3 =
        PlayerSimulationCache.getSimulationForOtherPlayers(player).getSnapshotAt(ticks).pos.subtract(player.position())

    /**
     * `DamageUtil.IIiIiIIIIiiii` (`DamageUtil.java:444-514`): every entity near us with its box stretched along its
     * predicted motion - our own through our simulation, players through theirs, anything else by its velocity (no
     * vertical part on the ground or without gravity). The native tick fills `AnchorAura.iIIIiiiiiii` with this; the
     * area and tick count it passes are not known - GUESS: our reach around us, the `Predict` ticks.
     */
    private fun predictedBoxes(level: ClientLevel, player: LocalPlayer): List<Pair<Entity, AABB>> {
        val ticks = TestAAWorld.predictionTicks(predict, player)
        val area = player.boundingBox.inflate(player.blockInteractionRange() + 2.0)
        return level.getEntities(null, area).map { entity ->
            val offset = when (entity) {
                is LocalPlayer -> PlayerSimulationCache.getSimulationForLocalPlayer()
                    .getSnapshotAt(ticks).pos.subtract(entity.position())

                is Player -> predictedOffset(entity, ticks)
                else -> {
                    val motion = entity.deltaMovement.scale(ticks.toDouble())
                    if (entity.onGround() || entity.isNoGravity) motion.multiply(1.0, 0.0, 1.0) else motion
                }
            }
            entity to entity.boundingBox.expandTowards(offset)
        }
    }

    // ── Rotation, hotbar, input ──────────────────────────────────────────────

    /** Steps towards the requested rotation by at most [Rotations.rotationSpeed] degrees per axis and tick. */
    private object SpeedLimit : RotationProcessor {
        override fun process(
            rotationTarget: RotationTarget,
            currentRotation: Rotation,
            targetRotation: Rotation
        ): Rotation {
            val speed = Rotations.rotationSpeed
            val yaw = Mth.wrapDegrees(targetRotation.yaw - currentRotation.yaw).coerceIn(-speed, speed)
            val pitch = (targetRotation.pitch - currentRotation.pitch).coerceIn(-speed, speed)
            return Rotation(currentRotation.yaw + yaw, currentRotation.pitch + pitch)
        }
    }

    /**
     * donut's rotation gate (`SocialCommand.IIIIIiIiiiIiII`, native). File the rotation, and report failure when a
     * request of higher priority owns the rotation manager - then nothing may be clicked on it. INFERRED from a
     * third-party reconstruction of the gate's worker (`donut\work\aot_java\f18_rotation_worker.java:113`: it returns
     * false when the caller's 15 is below a value the manager reports - read as that manager's active priority), which
     * also shows the per-axis speed limit and the +-90 pitch clamp [SpeedLimit] applies. Not read from code here.
     */
    private fun rotate(yaw: Float, pitch: Float): Boolean {
        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = Rotation(yaw, pitch.coerceIn(-90.0f, 90.0f)),
                processors = listOf(SpeedLimit),
                ticksUntilReset = 1,
                resetThreshold = 2.0f,
                considerInventory = true,
                movementCorrection = MovementCorrection.SILENT
            ),
            rotationPriority,
            this
        )
        val owner = RotationManager.activeRotationPriority
        return owner == null || owner <= rotationPriority.priority
    }

    /**
     * donut's `SwapManager.IIiiiiiIiiIiI(slot, "anchoraura")` (`SwapManager.java:200-237`): take the lease on [slot]
     * and report whether it is the slot the server will see.
     */
    private fun select(slot: Int): Boolean {
        if (slot !in 0..8) {
            return false
        }
        SilentHotbar.selectSlotSilently(this, slot, LEASE_TICKS)
        return SilentHotbar.isSlotModifiedBy(this) && SilentHotbar.serversideSlot == slot
    }

    /**
     * donut's `RotationManager.iiIIiIiiIiIiI()`, bound to Lua as `lock_item_use`; its reader is native. GUESS: while
     * this module has an action in the tick, the player's own use clicks queued for it are dropped. Done in [plan],
     * because by [execute] vanilla has already handled them.
     */
    private fun lockItemUse() {
        mc.options.keyUse.clickCount = 0
    }

    /**
     * `AnchorAura.java:611-626`: while acting, the sneak input is released - sneaking with an item in hand makes a
     * click on the anchor place the item against it instead of charging or detonating it. donut's handler then calls
     * a native movement fix; [RotationManager]'s movement correction takes that part.
     */
    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent>(
        priority = EventPriorityConvention.OBJECTION_AGAINST_EVERYTHING
    ) { event ->
        if (acting) {
            event.sneak = false
        }
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!Render.enabled) {
            return@handler
        }
        val pos = renderPos ?: return@handler

        renderEnvironmentForWorld(event.matrixStack) {
            withPositionRelativeToCamera(Vec3.atLowerCornerOf(pos)) {
                drawBox(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), Render.fill, Render.outline)
            }
        }
    }
}
