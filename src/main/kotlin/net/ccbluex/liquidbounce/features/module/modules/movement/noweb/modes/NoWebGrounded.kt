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

package net.ccbluex.liquidbounce.features.module.modules.movement.noweb.modes

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet
import kotlin.math.abs
import kotlin.math.roundToInt
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.movement.noweb.NoWebMode
import net.ccbluex.liquidbounce.utils.block.BlockChangeAcks
import net.ccbluex.liquidbounce.utils.block.MiningClaim
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.block.immutable
import net.ccbluex.liquidbounce.utils.item.isSword
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Grounded mode: proactive, vanilla-shaped web digging, targeting the GroundedGrim fork of Grim
 * (clone read at `C:\Users\caves\Music\GroundedGrim`) under its STRICTEST default config
 * (`force-stuck-speed: true`, Simulation threshold 0.001, immediate-setback-threshold 0.1).
 *
 * Movement is never modified on the wire. GroundedGrim has no web-specific check; webs are
 * enforced by the Simulation offset check against `stuckSpeedMultiplier` x0.25/x0.05
 * (`utils/nmsutil/StuckSpeed.java:17`), and an offset >= the immediate-setback threshold
 * (default 0.1, `checks/impl/prediction/OffsetHandler.java:164`) is flagged and setback
 * (`OffsetHandler.java:101-120`). Cancelling the vanilla slowdown under that config is an
 * immediate-setback violation every tick, so the core of this mode is the digger below.
 * [cancelCollision] (default OFF) is only for servers that accept un-slowed movement (e.g.
 * `force-stuck-speed: false`, where `predictionengine/predictions/PredictionEngine.java:942-946`
 * offers the un-slowed movement candidate even deep in a web - the default is true,
 * `player/GrimPlayer.java:281`). While off, [handleEntityCollision] only RECORDS the web and
 * vanilla slowdown applies.
 *
 * Crits inside a web are vanilla-legal; removing the web is NOT needed for them. The crit
 * verdict is the server's, read when the attack packet is processed (`Player.attack` ->
 * `canCriticalAttack`, `Player.java:1018-1028`: it needs `fallDistance > 0` and not onGround,
 * and a web never sets onGround). Each server tick drains packets BEFORE ticking levels
 * (`MinecraftServer.processPacketsAndTick`, `MinecraftServer.java:1033` vs `:1035`): the tick's
 * movement packet has already accumulated fallDistance (`doCheckFallDamage`,
 * `ServerGamePacketListenerImpl.java:1149`/`:1170` -> `Entity.checkFallDamage`,
 * `Entity.java:1473`), while the web's reset (`WebBlock.entityInside`, `WebBlock.java:35` ->
 * `Entity.makeStuckInBlock`, `Entity.java:2865-2868`) fires only later, in the level tick
 * (`LivingEntity.aiStep`, `LivingEntity.java:3066`). GroundedGrim mirrors the reset
 * (`utils/nmsutil/Collisions.java:611-613`) and its only crit check, BehaviorE, cannot flag a
 * web-stuck player anyway: early return at `fallDistance <= 0` plus an explicit
 * `stuckSpeedMultiplier.getX() < 0.99` exemption (`checks/impl/combat/BehaviorE.java:49`,
 * `:61`).
 *
 * The dig:
 * - A cobweb dug with a sword or shears yields 0.125 progress/tick -> 8 ticks
 *   (`utils/nmsutil/BlockBreakSpeed.java:286-312`: speedMultiplier 15.0 against destroySpeed
 *   4.0). A dig timed like vanilla costs nothing on FastBreak's dig-duration balance
 *   (`predictedTime - realTime <= 25 ms` decays, `checks/impl/breaking/FastBreak.java:99-114`),
 *   which is why the STOP goes out at the full vanilla 1.0 progress rather than the server's
 *   0.7 acceptance threshold: 1.0 keeps that diff ~0. The instant START+ABORT+STOP triple of
 *   [NoWebGrimBreak] accumulates ~+400 ms per web there (predicted 400 ms, real ~0) and flags
 *   on the third - do NOT copy that shape. Between two digs the FINISH->START pacing balance
 *   applies (`FastBreak.java:82-91`: a START < 275 ms after the last finish adds
 *   `300 - delay`, flags above 1000), so [DIG_RESTART_DELAY_MS] mirrors SpeedMine's 300 ms gate.
 * - Breaking checks are the upstream ones: ABORT always with [Direction.DOWN] (PositionBreakB),
 *   a swing before every START (NoSwingBreak), one live dig per client (WrongBreak), which is
 *   what the [MiningClaim] is for.
 * - All dig packets are sent at tick HEAD (pre-flying) from the tick handler, never from the
 *   collision callback.
 * - The dig FACE is computed from the eye position, never hardcoded: PositionBreakA (LIVE,
 *   cancels) flags a UP face when the eye's max Y is below the block's top
 *   (`checks/impl/breaking/PositionBreakA.java:56`, DOWN symmetrically at `:57`), and a
 *   horizontal face when the eye is not outside the block's span on that axis; the
 *   `isIntersected` exemption at `:45` only rescues webs the player stands inside. [digFace]
 *   therefore returns UP only above the block, DOWN only below it, else the horizontal face
 *   toward the player, computed per send because the eye moves. No rotation is filed for the
 *   dig: RotationBreak is DISABLED in this fork (`manager/CheckManager.java:268`, commented
 *   out).
 * - Re-digging a cell we just STOPped waits for the server's ack of that STOP
 *   ([BlockChangeAcks], mirrored from SpeedMine's rebreak gate): Grim predicts the cell air
 *   from our FINISHED_DIGGING until the ack resolves it
 *   (`events/packets/CheckManagerListener.java:824-828`), while the client still draws the web
 *   until the real block update, and a START into Grim-air is AirLiquidBreak (LIVE, cancels,
 *   `checks/impl/breaking/AirLiquidBreak.java:60`). [ACK_TIMEOUT_MS] (~2 s) is the fallback for
 *   a STOP that never produced an ack. The flat [DIG_RESTART_DELAY_MS] alone would lose to any
 *   RTT above 300 ms.
 * - The digger stands ALL the way down while `player.isUsingItem` - no swing, no START, no
 *   STOP, no ABORT, a dig in flight freezes in place and resumes when the use ends.
 *   MultiActionsB (experimental, cancels) flags any dig packet while using an item
 *   (`checks/impl/multiactions/MultiActionsB.java:19-27`) and MultiActionsE (experimental,
 *   cancels) flags the swing (`checks/impl/multiactions/MultiActionsE.java:24-35`).
 * - The digger freezes the same way while the crystal aura acted inside this tick window
 *   ([auraBusy], the 40 ms wall-clock idiom mirrored from `ModuleSpeedMine.auraBusy`):
 *   PacketOrderI (experimental, cancels) flags a dig sharing a tick with an attack/place/use
 *   (`checks/impl/packetorder/PacketOrderI.java:195-210`) and MultiActionsF (experimental,
 *   cancels) flags a dig sharing a tick with an entity interaction
 *   (`checks/impl/multiactions/MultiActionsF.java:79-91`). Handler order between modules is
 *   undefined, so the shared wall clock is what both can read.
 *
 * Proactive targeting: [BreakWebs] scans ahead every tick, sweeping the player's bounding box
 * forward along the horizontal movement direction (look direction when standing still) by up to
 * ScanAhead blocks and recording every web cell. A walking player (0.21 b/t) reaches a web 2
 * blocks ahead in ~10 ticks, longer than the 8-tick dig, so the web is air before arrival; a
 * sprinting player (0.28 b/t, ~7 ticks) eats at most a tick of slowdown.
 *
 * Strict-config behavior in one sentence: the web ahead is removed server-side in 8 ticks
 * (0.4 s) with a fully vanilla dig shape, movement is never modified on the wire (no Simulation
 * exposure), and the one unavoidable slow window is walking into a web the dig has not finished
 * yet.
 */
object NoWebGrounded : NoWebMode("Grounded") {

    private const val MAX_TRACKED_WEBS = 8

    /**
     * Half-block sweep step of the proactive scan, so no web cell is skipped between samples.
     */
    private const val SCAN_STEP_BLOCKS = 0.5

    /**
     * Grim FastBreak FINISH->START pacing (`FastBreak.java:82-91`), mirrored from SpeedMine
     * (`ModuleSpeedMine.startMine`): vanilla's own 5-tick destroy delay is ~250 ms, so a START
     * sooner than 300 ms after the last STOP accumulates delay balance.
     */
    private const val DIG_RESTART_DELAY_MS = 300L

    /**
     * Fallback for a STOP that never produced an ack (cancelled by another module and nothing
     * sequenced followed it), mirrored from SpeedMine (`ModuleSpeedMine.ACK_TIMEOUT_MS`).
     */
    private const val ACK_TIMEOUT_MS = 2000L

    private val cancelCollision by boolean("CancelCollision", false)

    private object BreakWebs : ToggleableValueGroup(this@NoWebGrounded, "BreakWebs", true) {
        val scanAhead by float("ScanAhead", 2.0f, 0.5f..3.0f, "blocks")
    }

    init {
        tree(BreakWebs)
    }

    private val trackedWebs = ObjectLinkedOpenHashSet<BlockPos>()

    /** The web being dug right now, or null. Non-null implies the START is on the wire. */
    private var digging: BlockPos? = null
    private var ticksSpent = 0
    private var lastStopMs = 0L

    /**
     * The cell, ack sequence and send time of our last STOP, for the AirLiquidBreak ack gate
     * ([stopAckProcessed]); mirrored from SpeedMine's `stopSequence` / `stopSentAt`.
     */
    private var lastStoppedPos: BlockPos? = null
    private var stopSequence = -1
    private var stopSentAt = 0L

    override fun disable() {
        resetState()
    }

    override fun handleEntityCollision(pos: BlockPos): Boolean {
        track(pos)

        return cancelCollision
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        resetState()
    }

    @Suppress("unused")
    private val digTickHandler = tickHandler {
        if (BreakWebs.enabled) {
            scanAhead()
            tickDig()
        }
    }

    /**
     * Sweeps the player's bounding box forward along the horizontal movement direction (the look
     * direction when standing still) by up to [BreakWebs.scanAhead] blocks and records every web
     * cell the swept box overlaps.
     */
    private fun scanAhead() {
        val delta = player.deltaMovement
        val horizontal = Vec3(delta.x, 0.0, delta.z)
        val direction = if (horizontal.lengthSqr() > 1.0E-7) {
            horizontal.normalize()
        } else {
            val look = player.lookAngle
            Vec3(look.x, 0.0, look.z).takeIf { it.lengthSqr() > 1.0E-7 }?.normalize() ?: return
        }

        val steps = (BreakWebs.scanAhead / SCAN_STEP_BLOCKS).roundToInt().coerceAtLeast(1)
        for (step in 1..steps) {
            val sweptBox = player.boundingBox.move(direction.scale(step * SCAN_STEP_BLOCKS))
            for (pos in BlockPos.betweenClosed(sweptBox)) {
                if (pos.getState()?.block is WebBlock) {
                    track(pos)
                }
            }
        }
    }

    private fun track(pos: BlockPos) {
        trackedWebs.add(pos.immutable)
        while (trackedWebs.size > MAX_TRACKED_WEBS) {
            trackedWebs.removeFirst()
        }
    }

    private fun tickDig() {
        // MultiActionsB / MultiActionsE (experimental, cancel): no swing, no START, no STOP, no
        // ABORT while using an item - a dig in flight freezes in place and resumes when the use
        // ends. The server counts its own ticks since the START, so the freeze only makes the
        // dig LONGER than predicted, which FastBreak's balance rewards, never punishes.
        if (player.isUsingItem) {
            return
        }
        // PacketOrderI / MultiActionsF (experimental, cancel): the crystal aura sent a packet
        // inside this tick window. Handler order between modules is undefined, so the shared
        // wall clock is what both can read - mirrored from `ModuleSpeedMine.auraBusy`.
        if (auraBusy()) {
            return
        }

        val eyes = player.eyePosition
        trackedWebs.removeIf { pos -> pos.getState()?.block !is WebBlock || !inReach(pos, eyes) }

        val current = digging
        if (current != null) {
            continueDig(current, eyes)
            return
        }

        // Bare hand on a cobweb is 400 ticks and pure FastBreak cost: no tool, no dig.
        if (!isWebTool(player.mainHandItem)) {
            return
        }
        if (System.currentTimeMillis() - lastStopMs < DIG_RESTART_DELAY_MS) {
            return
        }

        // AirLiquidBreak (LIVE): Grim holds the last STOPped cell as air until the server's ack
        // of that STOP is processed; a START into Grim-air cancels. Skip that cell until then.
        val now = System.currentTimeMillis()
        val target = trackedWebs
            .filter { it != lastStoppedPos || stopAckProcessed(now) }
            .minByOrNull { AABB(it).distanceToSqr(eyes) } ?: return

        // Grim WrongBreak (STABLE): only one live dig pos per client. A refused claim means
        // another digger is mid-dig and this START waits for the next tick.
        if (!MiningClaim.claim(this, target)) {
            return
        }

        // NoSwingBreak: the swing is on the wire immediately BEFORE the START.
        player.swing(InteractionHand.MAIN_HAND)
        sequenced { seq ->
            ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, digFace(target), seq
            )
        }
        digging = target
        ticksSpent = 0
    }

    private fun continueDig(pos: BlockPos, eyes: Vec3) {
        val state = pos.getState()

        if (state?.block !is WebBlock || !inReach(pos, eyes) || !isWebTool(player.mainHandItem)) {
            // The dig is dead: vanilla's interruption path. ABORT is always Direction.DOWN
            // (Grim PositionBreakB), raw packet with sequence 0 like SpeedMine's sendAbort.
            sendAbort(pos)
            endDig()
            return
        }

        // Renew the claim. A refusal means another digger took over; the claim contract forbids
        // any break packet from us this tick, so the stalled server-side dig is left to time out.
        if (!MiningClaim.claim(this, pos)) {
            digging = null
            return
        }

        ticksSpent++
        // Vanilla hold-to-dig shape: one swing per dig tick.
        player.swing(InteractionHand.MAIN_HAND)

        // The server's own acceptance test (`ServerPlayerGameMode.handleBlockBreakAction`, STOP
        // branch): `getDestroyProgress(player, level, pos) * (ticksSpentDestroying + 1)`, here
        // pushed to the full vanilla 1.0 rather than the server's 0.7 threshold - FastBreak's
        // blockBreakBalance charges `predictedTime - realTime`, and 1.0 keeps the diff ~0
        // (`FastBreak.java:99-114`).
        val delta = state.getDestroyProgress(player, world, pos)
        if (delta * (ticksSpent + 1) >= 1.0f) {
            sequenced { seq ->
                stopSequence = seq
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, digFace(pos), seq
                )
            }
            val now = System.currentTimeMillis()
            lastStopMs = now
            stopSentAt = now
            lastStoppedPos = pos
            endDig()
        }
    }

    private fun sendAbort(pos: BlockPos) {
        network.send(
            ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN
            )
        )
    }

    /**
     * Whether the server's answer to our last STOP has been processed ([BlockChangeAcks]).
     * Until then Grim predicts the cell air
     * (`events/packets/CheckManagerListener.java:824-828`) while the client still draws the
     * web, and a START there is AirLiquidBreak (LIVE, cancels,
     * `checks/impl/breaking/AirLiquidBreak.java:60`). [ACK_TIMEOUT_MS] covers a STOP that never
     * produced an ack.
     */
    private fun stopAckProcessed(now: Long): Boolean =
        stopSequence < 0 || BlockChangeAcks.isProcessed(world, stopSequence) ||
            now - stopSentAt > ACK_TIMEOUT_MS

    /**
     * The crystal aura sent a packet inside this tick window. `PacketOrderI` flags a dig that
     * shares a tick with a place, use or attack
     * (`checks/impl/packetorder/PacketOrderI.java:195-210`), and the two modules' handler order
     * is undefined, so the shared wall clock is what both can read (< 40 ms = same window).
     * Mirrored verbatim from `ModuleSpeedMine.auraBusy`.
     */
    private fun auraBusy(): Boolean =
        ModuleCrystalAura.running &&
            System.currentTimeMillis() - ModuleCrystalAura.lastActionMs < 40L

    /**
     * The face of [pos] vanilla's raytrace could produce from the current eye position
     * (PositionBreakA, LIVE, `checks/impl/breaking/PositionBreakA.java:50-58`): UP only when the
     * eye is above the block's top, DOWN only when below its bottom, otherwise the horizontal
     * face pointing from the block toward the player, preferring the axis with the larger
     * offset. A face is legal only when the eye is outside the block's span on that axis on the
     * face's side; an eye inside the horizontal spans on both axes is covered by the check's
     * `isIntersected` exemption (`:45`) instead. Computed per send - the eye moves mid-dig.
     */
    private fun digFace(pos: BlockPos): Direction {
        val eyes = player.eyePosition
        if (eyes.y >= pos.y + 1.0) return Direction.UP
        if (eyes.y <= pos.y.toDouble()) return Direction.DOWN

        val dx = eyes.x - (pos.x + 0.5)
        val dz = eyes.z - (pos.z + 0.5)
        val xFace = if (dx > 0) Direction.EAST else Direction.WEST
        val zFace = if (dz > 0) Direction.SOUTH else Direction.NORTH
        val xLegal = if (dx > 0) eyes.x >= pos.x + 1.0 else eyes.x <= pos.x.toDouble()
        val zLegal = if (dz > 0) eyes.z >= pos.z + 1.0 else eyes.z <= pos.z.toDouble()
        return when {
            abs(dx) >= abs(dz) && xLegal -> xFace
            zLegal -> zFace
            xLegal -> xFace
            // Eye column fully inside the block horizontally: PositionBreakA's isIntersected
            // exemption applies, any face passes.
            else -> zFace
        }
    }

    private fun endDig() {
        digging = null
        ticksSpent = 0
        MiningClaim.release(this)
    }

    private fun isWebTool(stack: ItemStack) = stack.isSword || stack.`is`(Items.SHEARS)

    /**
     * Eyes to the NEAREST point of the block's cell against `blockInteractionRange` - the rule
     * SpeedMine uses (`ModuleSpeedMine.inReach`, Grim FarBreak's shape, inside the server's +1.0).
     */
    private fun inReach(pos: BlockPos, eyes: Vec3): Boolean {
        val range = player.blockInteractionRange()
        return AABB(pos).distanceToSqr(eyes) <= range * range
    }

    /** SpeedMine's `sequenced` idiom: the packet carries the prediction sequence. */
    private fun sequenced(supplier: (Int) -> ServerboundPlayerActionPacket) {
        interaction.startPrediction(world) { sequence -> supplier(sequence) }
    }

    private fun resetState() {
        digging = null
        ticksSpent = 0
        lastStopMs = 0L
        lastStoppedPos = null
        stopSequence = -1
        stopSentAt = 0L
        trackedWebs.clear()
        MiningClaim.release(this)
    }
}
