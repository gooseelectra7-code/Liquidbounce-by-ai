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
package net.ccbluex.liquidbounce.features.module.modules.misc.clonerecorder

import com.mojang.authlib.GameProfile
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.deeplearn.clone.CloneCombatSignals
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.command.commands.ingame.fakeplayer.FakePlayer
import net.ccbluex.liquidbounce.utils.network.entityIdC2SInteractOrAttack
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Spawns a client-side target to aim at, so a profile can be filled without waiting for real
 * fights.
 *
 * Real combat gives the most honest data but very little of it per minute — most ticks are spent
 * walking rather than aiming. The gym produces a dense stream of flicks, tracking and settles at
 * controlled angles, which is what the model needs most of; it is meant to be mixed with real
 * recordings, not to replace them.
 *
 * The dummy is a [FakePlayer] — the same 0.6×1.8 box as the opponents the clone is deployed
 * against. This is load-bearing since schema v5 made the target box dimensions *features*: the
 * zombie that stood here before is 1.95 tall, about two standard deviations away in normalized
 * units, so a model could learn "tall box = drill, short box = match" and quietly refuse to carry
 * the drilled skill into fights. The dummy also *strafes* by default: a static target teaches no
 * tracking at all, and the target-velocity features of a gym profile would be a constant zero the
 * model then meets non-zero in every real fight.
 *
 * The dummy survives several hits before it "dies" and relocates — see [hitsToKill]. The first gym
 * killed it on every hit, which taught a poisonous correlation: 61 % of one measured profile was
 * gym ticks in which every landed attack was instantly followed by a target switch (a fifth of
 * them to *behind* the player). A model marinated in that answers a landed hit in a real fight
 * with "the target relocates now" — and sometimes samples the relocation turn even though the
 * features say the target is still in front. Hit → target persists is what real combat looks
 * like, so it has to be what the drill looks like.
 */
object CloneGym : ToggleableValueGroup(ModuleCloneRecorder, "Gym", false) {

    private val minDistance by float("MinDistance", 2f, 1f..6f)
    private val maxDistance by float("MaxDistance", 3f, 1f..6f)
    private val yawSpread by float("YawSpread", 65f, 5f..180f)

    /** Whether the dummy strafes around the player like an opponent, or stands still. */
    private val moving by boolean("Moving", true)

    /**
     * How many landed hits the dummy absorbs before it "dies" and respawns elsewhere.
     *
     * Rolled per spawn from this range. More than one is the point: most landed hits must be
     * followed by the same target still being there — tracked, hit again — because that is the
     * pattern of a real fight, and a kill-per-hit gym overrepresents target switches by an order
     * of magnitude (a switch every 1.2 s in the measured profile against maybe a few per minute
     * in play).
     */
    private val hitsToKill by intRange("HitsToKill", 2..5, 1..10, "hits")

    /** Whether the dummy hops like a strafing player, so pitch tracking gets trained too. */
    private val jumping by boolean("Jumping", true)

    /**
     * Chance that a respawn is an ambush: the dummy appears *behind* the player (±120–180° off
     * their view) instead of inside [yawSpread].
     *
     * Exists because real recordings barely contain the one regime the clone is worst at. Measured
     * across profiles, past 135° the hand is idle on 40 % of ticks and almost never attacks — the
     * player disengaging — so a model trained on them answers "target behind me" with standing
     * still, faithfully. The committed turn-and-fight has to be *put into* the data, and a fight
     * cannot be relied on to produce it often: this makes it a drill. The spawn sound is
     * positional, so the cue → reaction → snap chain is the same one a real backstab triggers.
     *
     * The default is deliberately modest. At 0.35 with a kill-per-hit dummy the measured profile
     * ended up with a behind-teleport every five seconds — a rare emergency turned into a fifth of
     * all switches, which the model then reproduced unprompted. Raise it only for a dedicated
     * short snap-drill session, not for bulk recording.
     */
    private val ambushChance by float("AmbushChance", 0.15f, 0f..1f)

    var target: LivingEntity? = null
        private set

    /** Hits the current dummy still absorbs before it relocates. */
    private var hitsLeft = 1

    /** Tick index inside the current hop, or `-1` between hops. */
    private var jumpTick = -1

    /**
     * Client-only ids, negative so they can never collide with server entities — and far from
     * `.fakeplayer`'s own negative range, which counts down from −1.
     */
    private var nextId = FIRST_GYM_ID

    /** Polar position of the dummy around the player, in the yaw convention (0 = +Z). */
    private var angle = 0.0
    private var radius = 3.0
    private var orbitDirection = 1
    private var speed = MIN_SPEED

    override fun onDisabled() {
        despawn()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        target = null
    }

    @Suppress("unused")
    private val spawnHandler = tickHandler {
        val current = target
        if (current == null || current.isRemoved) {
            target = spawn()
            return@tickHandler
        }

        if (moving) {
            move(current)
        }
    }

    /**
     * Swallows every packet aimed at the dummy, so the server never learns about an entity that
     * only exists on this client.
     *
     * A swing is labelled through [CloneCombatSignals.notePracticeAttack] before the cancellation
     * — the cancel is bookkeeping toward the server, not a veto of the player's real click, and
     * without the label the attack-centred sanitizer discards the whole drill. The hit then counts
     * against [hitsLeft], and only the killing one relocates the dummy: every earlier hit leaves
     * the target tracked, hit, and still there — the shape of a real exchange, and the pattern
     * whose absence taught earlier clones that a landed hit means the target teleports. The
     * replacement spawns in the *same tick*: a target-less tick is a hard sequence boundary, so a
     * per-kill gap would chop the session into sub-[AimSequence.MIN_LENGTH] fragments; a same-tick
     * swap keeps one continuous sequence in which every kill is a clean `targetChanged` switch. A
     * right-click is cancelled too but is neither a hit nor a reason to move anything.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val current = target ?: return@handler
        if (event.packet.entityIdC2SInteractOrAttack != current.id) {
            return@handler
        }

        if (event.packet is ServerboundAttackPacket) {
            CloneCombatSignals.notePracticeAttack(current.id)

            if (--hitsLeft <= 0) {
                despawn()
                target = spawn()
            }
        }

        event.cancelEvent()
    }

    private fun despawn() {
        val current = target ?: return
        target = null
        world.removeEntity(current.id, Entity.RemovalReason.DISCARDED)
    }

    private fun spawn(): LivingEntity {
        // A player entity, not a mob: the box has to be the one the clone is deployed against.
        // A random UUID keeps it out of the level's by-UUID lookups for real players; the id sits
        // in its own negative range. No removal callback — the gym tracks its dummy itself.
        val dummy = FakePlayer(world, GameProfile(UUID.randomUUID(), DUMMY_NAME))
        dummy.id = nextId--
        // The gym owns its kinematics completely — no sounds of its own, no physics pulling it
        // off the ring the settings describe.
        dummy.isSilent = true
        dummy.setNoGravity(true)
        dummy.interpolation.setInterpolationLength(LERP_STEPS)

        val offset = if (Random.nextDouble() < ambushChance) {
            val side = if (Random.nextBoolean()) 1 else -1
            side * Random.nextDouble(AMBUSH_MIN_OFFSET, 180.0)
        } else {
            Random.nextDouble(-yawSpread.toDouble(), yawSpread.toDouble())
        }
        angle = Math.toRadians(player.yRot + offset)
        radius = Random.nextDouble(
            minDistance.toDouble(),
            maxDistance.toDouble().coerceAtLeast(minDistance + 0.1)
        )
        orbitDirection = if (Random.nextBoolean()) 1 else -1
        speed = Random.nextDouble(MIN_SPEED, MAX_SPEED)
        hitsLeft = hitsToKill.random()
        jumpTick = -1

        val position = positionOnRing()
        dummy.setPos(position)
        dummy.yRot = facingPlayerYaw()
        dummy.yHeadRot = dummy.yRot
        dummy.yBodyRot = dummy.yRot
        world.addEntity(dummy)

        world.playLocalSound(
            position.x,
            position.y,
            position.z,
            SoundEvents.EXPERIENCE_ORB_PICKUP,
            SoundSource.NEUTRAL,
            1f,
            1f,
            false
        )

        return dummy
    }

    /**
     * One tick of strafing: mostly a steady orbit, with the reversals, speed changes and hops a
     * person strafing produces. Applied through the entity's own interpolation handler — the same
     * path a remote player's movement takes — so the dummy glides between ticks instead of
     * stepping at 20 Hz, and the hand tracking it records the motion it would record against a
     * person. The hop follows the vanilla jump parabola closely enough for the pitch tracking it
     * exists to train.
     */
    private fun move(entity: LivingEntity) {
        if (Random.nextDouble() < REVERSAL_CHANCE) {
            orbitDirection = -orbitDirection
        }
        if (Random.nextDouble() < SPEED_CHANGE_CHANCE) {
            speed = Random.nextDouble(MIN_SPEED, MAX_SPEED)
        }

        radius = (radius + Random.nextDouble(-RADIUS_JITTER, RADIUS_JITTER))
            .coerceIn(minDistance.toDouble(), maxDistance.toDouble().coerceAtLeast(minDistance + 0.1))
        angle += orbitDirection * speed / radius

        if (!jumping) {
            jumpTick = -1
        } else if (jumpTick < 0) {
            if (Random.nextDouble() < JUMP_CHANCE) {
                jumpTick = 0
            }
        } else if (++jumpTick >= JUMP_TICKS) {
            jumpTick = -1
        }

        val hop = if (jumpTick >= 0) {
            (JUMP_VELOCITY * jumpTick - JUMP_DRAG * jumpTick * jumpTick).coerceAtLeast(0.0)
        } else {
            0.0
        }

        entity.moveOrInterpolateTo(positionOnRing().add(0.0, hop, 0.0), facingPlayerYaw(), 0f)
    }

    /** Where the ring around the player currently puts the dummy, on the player's own floor. */
    private fun positionOnRing() = Vec3(
        player.x - sin(angle) * radius,
        player.y,
        player.z + cos(angle) * radius
    )

    private fun facingPlayerYaw(): Float = (Math.toDegrees(angle) + 180.0).toFloat()

    /** Tangential speed range in blocks per tick — a walk to just short of a sprint. */
    private const val MIN_SPEED = 0.08
    private const val MAX_SPEED = 0.22

    /** Per-tick chance the strafe flips, averaging a reversal every second or so. */
    private const val REVERSAL_CHANCE = 0.04
    private const val SPEED_CHANGE_CHANCE = 0.03
    private const val RADIUS_JITTER = 0.04

    /** Smallest view offset an ambush spawn may use, in degrees — never inside peripheral vision. */
    private const val AMBUSH_MIN_OFFSET = 120.0

    /** Ticks the entity's interpolation spreads each step over. */
    private const val LERP_STEPS = 2

    /** Per-tick chance a hop starts — one every couple of seconds, like a strafing player. */
    private const val JUMP_CHANCE = 0.03

    /** Vanilla-shaped hop: initial rise per tick, quadratic pulldown, back down within a second. */
    private const val JUMP_VELOCITY = 0.42
    private const val JUMP_DRAG = 0.04
    private const val JUMP_TICKS = 11

    /** Far below `.fakeplayer`'s own negative id range, so the two can never collide. */
    private const val FIRST_GYM_ID = -1_000_000

    private const val DUMMY_NAME = "Sparring"
}
