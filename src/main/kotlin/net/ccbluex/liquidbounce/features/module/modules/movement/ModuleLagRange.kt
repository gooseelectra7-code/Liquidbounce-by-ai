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
package net.ccbluex.liquidbounce.features.module.modules.movement

import com.mojang.blaze3d.vertex.PoseStack
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PerspectiveEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.blink.BlinkManager.positions
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.render.isCustom
import net.ccbluex.liquidbounce.utils.render.setPosition
import net.ccbluex.liquidbounce.utils.render.setRotation
import net.minecraft.client.CameraType
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.entity.Entity
import java.util.concurrent.ThreadLocalRandom

/**
 * LagRange module
 *
 * Ported from SystemDLC (`system-source — копия`, client/module/movement/LagRange.java plus its
 * packet-queueing base client/module/CategoryModule.java and the ghost helper
 * client/util/FakePlayerCopy.java).
 *
 * A range-gated FakeLag: while another player is within [range] meters, every outgoing packet is
 * held back and released in bursts on a randomized cadence (uniform in `[0, 2 * delay]` ms, the
 * base's `getLong()`), so nearby enemies see a lagging, hard-to-hit target. The range condition
 * carries a 150 ms hysteresis in both directions (LagRange `check3()`), interaction-critical packet
 * families are never held (they flush the queue and pass straight through, base `isPacket()`), and
 * the queue is also flushed when the condition drops, when the player attacks an entity (the
 * original's doAttack-hook InteractEvent) and on disable. Optionally renders the player's
 * server-side position as a ghost model while packets are held (base `render5()` +
 * FakePlayerCopy), only outside first person.
 *
 * Grim-relevant decisions (sanctioned APIs only):
 * - All queueing goes through [BlinkManager] (default-FLUSH contract): packets are NEVER dropped
 *   or mutated, only delayed and then delivered in their original order via the silent-send
 *   bypass. To the server this is indistinguishable from network lag - the same surface as the
 *   audited ModuleFakeLag/ModuleBlink.
 * - The instant-flush families release held movement BEFORE the triggering interact/inventory/chat
 *   packet reaches the wire (BlinkManager flushes the queue and lets the packet pass), so packet
 *   order is preserved exactly as vanilla produced it - no interact ever overtakes its own
 *   preceding movement.
 * - No rotations, no movement modification, no packet content changes anywhere.
 * - The world scan for the range gate runs on the game tick only; the [BlinkPacketEvent] handler
 *   (which can fire on the netty thread) reads a single volatile flag and never touches the world
 *   (AGENTS.md netty-thread gotcha).
 */
object ModuleLagRange : ClientModule("LagRange", ModuleCategories.MOVEMENT) {

    /**
     * Original: "Задержка" ("Delay"), default 300, 50..1000 step 10, unit "мс"
     * (CategoryModule.java:50-53). The actual wait between flushes is uniform random in
     * [0, 2 * delay] ms - see [randomWait]. (The 10 ms slider step is not representable
     * in the Value DSL.)
     */
    private val delay by int("Delay", 300, 50..1000, "ms")

    /**
     * Original: "Дистанция" ("Distance"), default 10.0, 1.0..30.0 step 0.5, unit "м" = meters
     * (LagRange.java:17-19). Distance to the nearest player at which the hold activates.
     * (The 0.5 slider step is not representable in the Value DSL.)
     */
    private val range by float("Range", 10f, 1f..30f, "m")

    /**
     * Original: "Показать серверную позицию" ("Show server position"), default true
     * (CategoryModule.java:54-57) - renders your model at the position the server sees.
     */
    private val showServerPosition by boolean("ShowServerPosition", true)

    /** Hysteresis of the range condition in both directions (LagRange.java:47: `>= 150L`). */
    private const val HYSTERESIS_MS = 150L

    /**
     * The committed range-gate state (original LagRange `flag2`) plus the pending-transition
     * timestamp (original `time3`). Only ever touched on the game thread (tick handler).
     */
    private var gateState = false
    private var transitionStart = 0L

    /**
     * The per-tick module condition (original CategoryModule `flag`). Written on the game tick,
     * read by the [BlinkPacketEvent] handler which can run on the netty thread - hence volatile.
     * The original re-evaluated `check3()` per packet event instead; with the 150 ms hysteresis
     * the at-most-one-tick staleness of this cache is behaviorally equivalent.
     */
    @Volatile
    private var active = false

    /** Flush cadence state (original CategoryModule `time` = 0 and `time2` = 300, lines 59-60). */
    private var lastFlush = 0L
    private var nextWait = 300L

    private val poseStack = PoseStack()

    /**
     * Original CategoryModule.getLong() (lines 175-178): uniform random in [0, 2 * delay]
     * inclusive (`nextLong(2 * delay + 1)`), 0 if the doubled delay is not positive.
     */
    private fun randomWait(): Long {
        val bound = delay.toLong() * 2L
        return if (bound <= 0L) 0L else ThreadLocalRandom.current().nextLong(bound + 1L)
    }

    /**
     * Original LagRange.check3() (lines 27-61): any player other than ourselves within
     * range^2 blocks flips the raw condition; the committed state only follows it after the
     * raw value has held steadily for [HYSTERESIS_MS], in both directions.
     *
     * Deviation from the original scan: [ModuleBlink]'s client-side dummy is excluded - it is a
     * client-spawned RemotePlayer in `world.players()` that no server-side player corresponds to
     * (the original's ghost was never added to the world, so it never had this problem).
     */
    private fun updateRangeGate(): Boolean {
        val rangeSq = range.toDouble() * range.toDouble()
        val inRange = world.players().any { other ->
            other !== player &&
                !ModuleBlink.isDummyPlayer(other.id) &&
                player.distanceToSqr(other) <= rangeSq
        }

        val now = System.currentTimeMillis()
        if (inRange != gateState) {
            if (transitionStart == 0L) {
                transitionStart = now
            }
            if (now - transitionStart >= HYSTERESIS_MS) {
                gateState = inRange
                transitionStart = 0L
            }
        } else {
            transitionStart = 0L
        }
        return gateState
    }

    /**
     * Original CategoryModule.isPacket() (lines 115-149): these families are never held - the
     * queue is flushed and the packet passes through immediately. Yarn 1.21.4 -> Mojang 26.1.2
     * mapping; the entity-interact packet was split into Interact + Attack in this version, so
     * one Yarn class maps to two here.
     */
    private fun isInstantFlushPacket(packet: Packet<*>): Boolean = when (packet) {
        // interact / action / swing (isPacket lines 116-120)
        is ServerboundInteractPacket,            // PlayerInteractEntityC2SPacket (interact half)
        is ServerboundAttackPacket,              // PlayerInteractEntityC2SPacket (attack half)
        is ServerboundPlayerActionPacket,        // PlayerActionC2SPacket
        is ServerboundUseItemOnPacket,           // PlayerInteractBlockC2SPacket
        is ServerboundUseItemPacket,             // PlayerInteractItemC2SPacket
        is ServerboundSwingPacket,               // HandSwingC2SPacket
        // inventory (isPacket lines 123-128)
        is ServerboundContainerClickPacket,      // ClickSlotC2SPacket
        is ServerboundContainerClosePacket,      // CloseHandledScreenC2SPacket
        is ServerboundContainerButtonClickPacket, // ButtonClickC2SPacket
        is ServerboundSetCreativeModeSlotPacket, // CreativeInventoryActionC2SPacket
        is ServerboundSelectTradePacket,         // SelectMerchantTradeC2SPacket
        is ServerboundRenameItemPacket,          // RenameItemC2SPacket
        // chat / commands (isPacket line 131)
        is ServerboundChatPacket,                // ChatMessageC2SPacket
        is ServerboundChatCommandPacket,         // CommandExecutionC2SPacket
        // book / sign (isPacket line 134)
        is ServerboundEditBookPacket,            // BookUpdateC2SPacket
        is ServerboundSignUpdatePacket,          // UpdateSignC2SPacket
        // client status / abilities (isPacket line 137)
        is ServerboundClientCommandPacket,       // ClientStatusC2SPacket
        is ServerboundPlayerAbilitiesPacket,     // UpdatePlayerAbilitiesC2SPacket
        // spectate / teleport confirm (isPacket line 140)
        is ServerboundSpectateEntityPacket,      // SpectatorTeleportC2SPacket
        is ServerboundAcceptTeleportationPacket, // TeleportConfirmC2SPacket
        // recipe / craft (isPacket line 143)
        is ServerboundPlaceRecipePacket,         // CraftRequestC2SPacket
        is ServerboundRecipeBookSeenRecipePacket, // RecipeBookDataC2SPacket
        is ServerboundRecipeBookChangeSettingsPacket // RecipeCategoryOptionsC2SPacket
            -> true

        else -> false
    }

    /**
     * Original CategoryModule.onTick() (lines 66-86): flush on the falling edge of the condition,
     * then (while the condition holds) flush the queue every [nextWait] ms and re-roll the wait.
     * The timer keeps running from the last flush moment even while the queue is empty, exactly
     * like the original (`time`/`time2` are only reset in the timed branch).
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val nowActive = updateRangeGate()

        if (active && !nowActive) {
            // Falling edge (onTick lines 69-72: update11) - release everything that was held.
            BlinkManager.flush(TransferOrigin.OUTGOING)
        }
        active = nowActive

        if (nowActive) {
            val now = System.currentTimeMillis()
            if (now - lastFlush >= nextWait) {
                BlinkManager.flush(TransferOrigin.OUTGOING)
                lastFlush = now
                nextWait = randomWait()
            }
        }
    }

    /**
     * Original CategoryModule.onPacketEvent() (lines 152-168): while the condition holds, queue
     * every outgoing packet except the instant-flush families, which flush the queue and pass.
     *
     * [BlinkPacketEvent.action] defaults to FLUSH, so "not setting QUEUE" on an instant-flush
     * packet makes BlinkManager flush the held queue and let the packet through - the exact
     * `isPacket() -> update11() + pass` behavior of the original. The null-packet poll events
     * must also be answered with QUEUE, otherwise BlinkManager's per-frame default flush would
     * drain the queue between our cadence flushes.
     */
    @Suppress("unused")
    private val blinkHandler = handler<BlinkPacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING || !active) {
            return@handler
        }

        val packet = event.packet
        if (packet != null && isInstantFlushPacket(packet)) {
            // Leave the default FLUSH action: queue released, packet passes through.
            return@handler
        }

        event.action = BlinkManager.Action.QUEUE
    }

    /**
     * Original CategoryModule.onInteractEvent() (lines 171-173), dispatched from the client's
     * doAttack hook while the crosshair is on an entity (GameMenuHooks.java:132-158, Yarn
     * `method_1536` = MinecraftClient.doAttack): flush when the player attacks an entity.
     * [AttackEntityEvent] fires at MultiPlayerGameMode.attack HEAD
     * (MixinMultiPlayerGameMode.java:48-54) - on the game thread, before the attack packet is
     * emitted, matching the original's hook point. Gated on [active] because the BlinkManager
     * queue is shared: while we are not the holder, the held packets belong to another module
     * (in the original the queue was module-private and this flush was a no-op there).
     */
    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> {
        if (active) {
            BlinkManager.flush(TransferOrigin.OUTGOING)
        }
    }

    /**
     * Original CategoryModule.render5() (lines 106-113) + FakePlayerCopy.render() (lines 58-73):
     * while packets are held, render the player's model at the server-side position (the first
     * held position-carrying move packet), only outside first person. Render mechanism copied
     * from the in-repo BlinkEspModel (features/blink/esp/BlinkEspMode.kt:95-127), which is the
     * existing "show the server position" renderer BlinkManager itself uses; like the original,
     * the ghost uses tick delta 0 (FakePlayerCopy.java:62) and the last rotation the server
     * actually received.
     */
    @Suppress("unused")
    private val ghostRenderHandler = handler<GameRenderEvent> {
        if (!showServerPosition || !active) {
            return@handler
        }
        val pos = positions.firstOrNull() ?: return@handler

        val perspectiveEvent = EventManager.callEvent(PerspectiveEvent(mc.options.cameraType))
        if (perspectiveEvent.perspective == CameraType.FIRST_PERSON) {
            return@handler
        }

        val entity: Entity = player
        val entityRenderer = mc.entityRenderDispatcher.getRenderer(entity)
        val rs = entityRenderer.createRenderState(entity, 0f)

        rs.isCustom = true
        rs.setPosition(pos)
        if (rs is LivingEntityRenderState) {
            rs.setRotation(RotationManager.actualServerRotation)
        }

        val cameraState = mc.gameRenderer.gameRenderState.levelRenderState.cameraRenderState
        mc.entityRenderDispatcher.submit(
            rs,
            cameraState,
            rs.x - cameraState.pos.x,
            rs.y - cameraState.pos.y,
            rs.z - cameraState.pos.z,
            poseStack,
            mc.gameRenderer.submitNodeStorage,
        )
    }

    /**
     * Original LagRange.onEnable() (lines 64-68) + CategoryModule.onEnable() (lines 192-198):
     * reset the hysteresis, the condition and the cadence (`time = now`, `time2 = getLong()`).
     * The original also cleared its private queue; the shared BlinkManager queue is not ours to
     * clear (default-FLUSH contract - packets are never dropped), and it is empty of our packets
     * here anyway because disable always flushes.
     */
    override fun onEnabled() {
        gateState = false
        transitionStart = 0L
        active = false
        lastFlush = System.currentTimeMillis()
        nextWait = randomWait()
    }

    /**
     * Original CategoryModule.onDisable() (lines 100-103): flush everything still held.
     */
    override fun onDisabled() {
        BlinkManager.flush(TransferOrigin.OUTGOING)
        active = false
    }
}
