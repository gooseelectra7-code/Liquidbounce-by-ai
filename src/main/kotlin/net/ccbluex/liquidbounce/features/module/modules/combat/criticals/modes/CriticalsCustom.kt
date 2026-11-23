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
package net.ccbluex.liquidbounce.features.module.modules.combat.criticals.modes

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.VisualsValueGroup.showCriticals
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.canDoCriticalHit
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.modes
import net.ccbluex.liquidbounce.utils.entity.lastRotation
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.LivingEntity

/**
 * Custom criticals mode
 *
 * A port of catlean's `Criticals` CUSTOM mode (`su.catlean.Criticals`). Instead of a fixed
 * preset like [CriticalsPacket], the burst is written by hand: up to five movement packets,
 * each with its own Y offset, `onGround` flag and packet shape, sent in order on every
 * attack.
 *
 * Source read for this port (the shipped jar's readable decompile, since the
 * partially-deobfuscated `catlean/catlean/src` copy is opaque):
 * `catlean-src-clean/su/catlean/Criticals.java:342-368` (the CUSTOM branch sends packets
 * 1..5 in order, each as `(offset, full, ground)`) and `:398-424` (the sender itself).
 * Defaults come from the same file's static initializer, `:672-845`: packets 1-3 on,
 * 4-5 off; ground only on packet 1; `full` on for 1-3; offsets 0.0 / 0.0625 / 0.04535 /
 * 0.0 / 0.0, all ranged 0.0..1.5.
 *
 * Deliberate differences from catlean, do NOT "fix" them back:
 * - The trigger is this client's [AttackEntityEvent] with [canDoCriticalHit], not catlean's
 *   outgoing-`ServerboundAttackPacket` hook. That keeps every Criticals mode on one gate
 *   (cooldown > 0.9, no lava/water/web/climbable/vehicle, sprint handling via
 *   [ModuleCriticals.WhenSprinting]) instead of adding a second, weaker one. catlean's own
 *   gate was "on ground or flying, not in lava, not underwater"
 *   (`Criticals.java:270-330`), which [ModuleCriticals.allowsCriticalHit] already covers,
 *   except that it also refuses while `abilities.flying` - we keep OUR rule.
 * - `Full` repeats the LAST ROTATION THE CLIENT SENT (`player.lastRotation`, i.e.
 *   `yRotLast/xRotLast`), which is what catlean does through its own `LocalPlayerAccessor`
 *   (`Criticals.java:414-421`). Never `player.yRot`: with a silent rotation up, the camera
 *   yaw is NOT the yaw on the wire (`MixinLocalPlayer.hookSilentRotationYaw` only rewrites
 *   `getYRot()` inside `sendPosition`/`tick`), so a hand-built packet carrying it would
 *   hand the server the camera rotation and undo the silent aim.
 *
 * NOT Grim-audited: an attack followed by extra position packets inside the same tick is
 * exactly the shape Grim's Simulation/Post checks look at, and the offsets here are the
 * user's to choose - the presets of [CriticalsPacket] are the audited ones.
 */
object CriticalsCustom : Mode("Custom") {

    override val parent: ModeValueGroup<Mode>
        get() = modes

    /**
     * One configured packet of the burst. A [ToggleableValueGroup] so that `Enabled`
     * collapses its three settings in both ClickGUIs (settings-style rule: a sub-feature
     * with its own settings is a nested toggleable group, never a flat boolean).
     *
     * [owner] is passed in rather than read from [CriticalsCustom]: these are built inside
     * the object's own initializer, where the `INSTANCE` field is still null.
     */
    private class PacketStage(
        owner: Mode,
        index: Int,
        sendDefault: Boolean,
        groundDefault: Boolean,
        fullDefault: Boolean,
        offsetDefault: Float,
    ) : ToggleableValueGroup(owner, "Packet$index", sendDefault) {

        /** The `onGround` flag of this packet (catlean's `ground-N`). */
        val ground by boolean("Ground", groundDefault)

        /** Position + rotation instead of position only (catlean's `full-N`). */
        val full by boolean("Full", fullDefault)

        /** Added to the player's Y (catlean's `offset-N`, same 0.0..1.5 range). */
        val offset by float("Offset", offsetDefault, 0.0f..1.5f)
    }

    private val packets = arrayOf(
        PacketStage(
            this, 1, sendDefault = true, groundDefault = true,
            fullDefault = true, offsetDefault = 0.0f,
        ),
        PacketStage(
            this, 2, sendDefault = true, groundDefault = false,
            fullDefault = true, offsetDefault = 0.0625f,
        ),
        PacketStage(
            this, 3, sendDefault = true, groundDefault = false,
            fullDefault = true, offsetDefault = 0.04535f,
        ),
        PacketStage(
            this, 4, sendDefault = false, groundDefault = false,
            fullDefault = false, offsetDefault = 0.0f,
        ),
        PacketStage(
            this, 5, sendDefault = false, groundDefault = false,
            fullDefault = false, offsetDefault = 0.0f,
        ),
    )

    init {
        packets.forEach { tree(it) }
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (event.entity !is LivingEntity) {
            return@handler
        }

        val ignoreSprinting = ModuleCriticals.WhenSprinting.shouldAttemptCritWhileSprinting()

        if (!canDoCriticalHit(true, ignoreSprinting)) {
            return@handler
        }

        var sentAny = false
        for (packet in packets) {
            if (!packet.enabled) {
                continue
            }

            send(packet.offset.toDouble(), packet.full, packet.ground)
            sentAny = true
        }

        if (sentAny) {
            showCriticals(event.entity)
        }
    }

    private fun send(offsetY: Double, full: Boolean, onGround: Boolean) {
        val packet = if (full) {
            // the rotation already on the wire - see the KDoc, never player.yRot/xRot
            val rotation = player.lastRotation
            ServerboundMovePlayerPacket.PosRot(
                player.x, player.y + offsetY, player.z,
                rotation.yaw, rotation.pitch, onGround, player.horizontalCollision,
            )
        } else {
            ServerboundMovePlayerPacket.Pos(
                player.x, player.y + offsetY, player.z,
                onGround, player.horizontalCollision,
            )
        }

        network.send(packet)
    }
}
