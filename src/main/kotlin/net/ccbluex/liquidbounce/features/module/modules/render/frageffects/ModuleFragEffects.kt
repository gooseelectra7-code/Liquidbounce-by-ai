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
package net.ccbluex.liquidbounce.features.module.modules.render.frageffects

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntIntPair
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldEntityRemoveEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.injection.mixins.minecraft.network.MixinClientboundEntityEventPacketAccessor
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.MessageMetadata
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.random.Random

/**
 * FragEffects
 *
 * A kill confirmation: the screen flashes, a red vignette slams in, the world desaturates and the
 * body bursts into a ring of debris. Popping a totem plays a quieter, orange-tinted variant.
 *
 * Ported from the veganew client. Two things could not come across as-is:
 *
 * - The original forced the game timer to 0.12 on a frag for a slow-motion punch. There is no
 *   render-only timer in Minecraft — the timer scales `deltaTicks`, which decides how many game
 *   ticks run and therefore how often movement packets are sent. Instead the effect keeps its own
 *   clock, and [HitStop] dilates only that: the flash, vignette and debris slow down, the world does
 *   not, and nothing reaches the server.
 * - It loaded the vanilla `desaturate.json` / `blobs2.json` post-effects, which no longer ship with
 *   the game. [FragEffectsShader] replaces them with a client shader.
 */
@Suppress("TooManyFunctions")
object ModuleFragEffects : ClientModule("FragEffects", ModuleCategories.RENDER) {

    /** Window in which an entity's death still counts as our kill. */
    private const val ATTACK_TTL_TICKS = 60L

    private val includeMobs by boolean("IncludeMobs", true)
    private val totemEffects by boolean("TotemEffects", true)

    /** How a death gets attributed to us. */
    enum class Credit(override val tag: String) : Tagged {
        /** Only deaths of something we hit ourselves, or that the server blamed on us. */
        OWN_HITS("OwnHits"),

        /**
         * Also deaths that follow a crystal we detonated. Vanilla blames an end crystal explosion on
         * the crystal, not on whoever broke it, so a crystal kill carries no attribution at all —
         * this pairs our own crystal hits with deaths that land next to them shortly after.
         */
        EXPLOSIONS("Explosions"),

        /** Any death within [radius]. Fires on kills that were not yours. */
        NEARBY("Nearby"),
    }

    private val credit by enumChoice("Credit", Credit.EXPLOSIONS)

    /** How close a death has to be to count under [Credit.NEARBY]. */
    private val radius by float("Radius", 24f, 4f..128f, "blocks")

    /**
     * Guesswork paths for servers that suppress the vanilla death event. Off by default: both infer
     * a death from health rather than being told about one, and both can misfire — a body that
     * unloads while low, or one that healed out of sight and came back.
     */
    private object Fallbacks : ToggleableValueGroup(this, "Fallbacks", false) {
        /** Treat a body of ours that vanished while nearly dead as a kill. */
        val onVanish by boolean("OnVanish", true)

        /** Treat health snapping from nearly dead back to full in one tick as a respawn. */
        val onRespawnSnap by boolean("OnRespawnSnap", true)
    }

    /**
     * Scales how long the whole effect runs. The veganew timings are gone in well under a second,
     * which reads as a blink on a modern monitor, so this defaults above 1.
     */
    private val duration by float("Duration", 2f, 0.25f..6f, "x")

    private object Flash : ToggleableValueGroup(this, "Flash", true) {
        /** Above 1 the flash saturates to solid white and stays there for part of the rise. */
        val power by float("Power", 1.4f, 0f..4f)
    }

    private object Vignette : ToggleableValueGroup(this, "Vignette", true) {
        val power by float("Power", 2f, 0f..4f)
    }

    private object Particles : ToggleableValueGroup(this, "Particles", true) {
        /** Multiplies the ~7500 particles the original threw per frag. */
        val amount by float("Amount", 1f, 0.05f..4f)

        /**
         * Half-edge of one particle in blocks. The original drew 3px GL points, which translates to
         * something far larger than it sounds — at the old 0.012 the debris was invisible past a
         * couple of blocks.
         */
        val size by float("Size", 0.05f, 0.002f..0.4f, "blocks")

        /** Scales how far the ring is thrown from the body. */
        val spread by float("Spread", 1.6f, 0.2f..6f, "x")

        val darkColor by color("DarkColor", Color4b(0, 0, 0, 255))
        val brightColor by color("BrightColor", Color4b(255, 255, 255, 255))
        val totemColor by color("TotemColor", Color4b(255, 155, 70, 255))
    }

    private object Desaturate : ToggleableValueGroup(this, "Desaturate", true) {
        val strength by float("Strength", 1f, 0f..1f)
    }

    private object FovPunch : ToggleableValueGroup(this, "FovPunch", true) {
        val power by float("Power", 0.45f, 0f..2f)
    }

    /**
     * Stretches this module's own clock right after a frag, so the effect reads as a hit-stop.
     * Purely visual — see the class docs.
     */
    private object HitStop : ToggleableValueGroup(this, "HitStop", true) {
        val slowdown by float("Slowdown", 0.2f, 0.02f..1f)
        val length by int("Length", 400, 0..2000, "ms")
    }

    private object Sounds : ToggleableValueGroup(this, "Sounds", true) {
        val volume by float("Volume", 0.55f, 0f..1f)
    }

    /**
     * Diagnostics. Detection has a lot of moving parts and every one of them fails silently, so
     * rather than guessing which, turn this on and the module says what it saw.
     */
    private object Diagnostics : ToggleableValueGroup(this, "Debug", false) {
        /** Reports every attack, death and credit decision to chat. */
        val log by boolean("Log", true)

        /** Plays the whole effect once as soon as the module is switched on. */
        val testOnEnable by boolean("TestOnEnable", true)
    }

    /**
     * Turns the view towards whatever just died. Off by default: unlike everything else here this
     * moves the real rotation, so it goes out in movement packets.
     */
    private val flickToVictim by boolean("FlickToVictim", false)

    private val rotations = tree(RotationsValueGroup(this))

    init {
        tree(Flash)
        tree(Vignette)
        tree(Particles)
        tree(Desaturate)
        tree(FovPunch)
        tree(HitStop)
        tree(Sounds)
        tree(Fallbacks)
        tree(Diagnostics)
    }

    private val vignetteTexture by lazy(LazyThreadSafetyMode.NONE) {
        LiquidBounce.resource("textures/frageffects/vignette.png")
            .readNativeImage().asTexture { "FragEffects Vignette" }
    }

    // -- Animation state -------------------------------------------------------------------------

    /**
     * A one-shot envelope: a fast attack from a non-zero starting point, then a slower release.
     * The original drove these by adding a fixed step every frame, which made the effect run at the
     * framerate; here the phases are wall-clock so a 30 fps and a 240 fps client see the same thing.
     */
    private class Envelope(
        private val baseAttackMs: Float,
        private val baseReleaseMs: Float,
        private val attackFrom: Float,
    ) {
        private var startedAt = Float.NEGATIVE_INFINITY

        /**
         * Captured at trigger time rather than read live, so dragging the Duration slider mid-effect
         * cannot make an envelope jump backwards.
         */
        private var attackMs = baseAttackMs
        private var releaseMs = baseReleaseMs

        /** Set once the attack finishes, so the peak can be reacted to exactly once. */
        var peaked: Boolean = false
            private set

        fun trigger(now: Float, durationScale: Float) {
            startedAt = now
            attackMs = baseAttackMs * durationScale
            releaseMs = baseReleaseMs * durationScale
            peaked = false
        }

        fun reset() {
            startedAt = Float.NEGATIVE_INFINITY
            peaked = false
        }

        /** True while the envelope is still climbing towards 1. */
        fun isRising(now: Float): Boolean = now - startedAt < attackMs

        fun value(now: Float): Float {
            val elapsed = now - startedAt
            if (elapsed < 0f) {
                return 0f
            }

            if (elapsed < attackMs) {
                return attackFrom + (1f - attackFrom) * (elapsed / attackMs)
            }

            peaked = true
            val released = (elapsed - attackMs) / releaseMs
            return (1f - released).coerceAtLeast(0f)
        }
    }

    private val strike = Envelope(baseAttackMs = 135f, baseReleaseMs = 480f, attackFrom = 0.5f)
    private val totem = Envelope(baseAttackMs = 150f, baseReleaseMs = 620f, attackFrom = 0.1f)

    private val particles = FragParticles(capacity = MAX_PARTICLES)
    private val random = Random(0x5EED)

    /** Dilated effect time in ms. Everything the module animates is measured against this. */
    private var effectClock = 0f
    private var lastFrameNanos = 0L
    private var hitStopUntilRealMs = 0L

    /** Guards [onStrikePeak] so the follow-up burst fires once per frag, not once per frame. */
    private var strikeHandled = true

    /** Position of the most recent victim, used for the burst and the optional flick. */
    private var lastVictimPos: Vec3? = null
    private var flickUntilRealMs = 0L

    /** Entity id -> tick after which our hit no longer counts as having caused the death. */
    private val attackedUntil = Int2LongOpenHashMap().apply { defaultReturnValue(-1L) }

    /**
     * Entity id -> health seen on the previous tick, for the entities we are fighting. Needed by the
     * two detection paths that have no packet to key off: a body that vanishes while nearly dead,
     * and one whose health snaps back to full because the server respawned it in place.
     */
    private val lastHealth = Int2FloatOpenHashMap().apply { defaultReturnValue(-1f) }

    /** Entity id -> tick until which a second frag for the same body is swallowed. */
    private val fragCooldown = Int2LongOpenHashMap().apply { defaultReturnValue(-1L) }

    /** Entity id -> tick the health in [lastHealth] was sampled on. */
    private val lastHealthTick = Int2LongOpenHashMap().apply { defaultReturnValue(-1L) }

    /** Where and when we last detonated a crystal, for [Credit.EXPLOSIONS]. */
    private val blasts = ObjectArrayList<Blast>()

    private class Blast(val pos: Vec3, val tick: Long)

    /**
     * Live crystal id -> position, refreshed at the end of every tick.
     *
     * A crystal is gone by the time the attack that broke it is processed, so its position has to
     * have been written down while it still existed.
     */
    private val crystalPositions = Int2ObjectOpenHashMap<Vec3>()

    /**
     * Death and totem-pop events, parked off the network thread as (entity id, event id) pairs.
     *
     * The packet itself is not kept: resolving it needs the world, and by the time the queue is
     * drained the body is usually gone, which is precisely the case this module cares about.
     */
    private val pendingEntityEvents = ConcurrentLinkedQueue<IntIntPair>()

    /**
     * Bodies captured as they were removed, so a death can still be located after the server has
     * taken the entity away. Deaths and removals arrive in the same tick and the removal is handled
     * first, so without this the kills that matter most resolve to nothing.
     */
    private val removedBodies = Int2ObjectOpenHashMap<RemovedBody>()

    private class RemovedBody(val entity: Entity, val tick: Long)

    /** Victims the server blamed on us, parked off the network thread. */
    private val pendingDamage = ConcurrentLinkedQueue<Int>()

    /** Entity ids we sent an attack for, parked off the network thread. */
    private val pendingAttacks = ConcurrentLinkedQueue<Int>()

    /**
     * Read on the network thread, so it is cached rather than reached through `player`, which
     * asserts it is called from the client thread.
     */
    @Volatile
    private var localPlayerId = -1

    // -- Detection -------------------------------------------------------------------------------

    /**
     * Detection path 2: the body vanished.
     *
     * A server never removes an entity with [Entity.RemovalReason.KILLED] —
     * `ClientPacketListener.handleRemoveEntities` always passes `DISCARDED` — so the reason says
     * nothing about whether it died. What separates a death from someone walking out of render
     * range is the health it was last seen at, which is a guess, so this path is restricted to
     * bodies we were actually hitting. It only matters on servers that suppress the death event;
     * everywhere else path 1 has already fired and the dedupe swallows this.
     */
    @Suppress("unused")
    private val entityRemoveHandler = handler<WorldEntityRemoveEvent> { event ->
        val entity = event.entity
        val id = entity.id

        val seen = lastHealth.get(id)
        val current = (entity as? LivingEntity)?.health ?: seen
        val health = if (seen < 0f) current else min(seen, current)

        if (Fallbacks.enabled && Fallbacks.onVanish && health >= 0f && health <= DEATH_HEALTH_THRESHOLD) {
            tryFrag(entity, requireAttack = true)
        }

        // The entity is kept, not the attribution dropped: a death queued this tick still has to be
        // able to find its body, and `attackedUntil` has to survive to credit it. Both age out on
        // their own a moment later.
        removedBodies.put(id, RemovedBody(entity, player.tickCount.toLong()))
        lastHealth.remove(id)
        lastHealthTick.remove(id)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        when (val packet = event.packet) {
            // DEATH is the one signal every server sends, instant-respawn plugins included: without
            // it the death animation would not play. It is the primary path; the rest are guesses.
            is ClientboundEntityEventPacket -> {
                if (packet.eventId == EntityEvent.DEATH ||
                    (totemEffects && packet.eventId == EntityEvent.PROTECTED_FROM_DEATH)
                ) {
                    val id = (packet as MixinClientboundEntityEventPacketAccessor).`liquid_bounce$getEntityId`()
                    pendingEntityEvents += IntIntPair.of(id, packet.eventId.toInt())
                }
            }

            // The server's own attribution, which covers everything an attack event misses:
            // arrows, thrown tridents, and anything a plugin blames on us directly.
            is ClientboundDamageEventPacket -> {
                // Both ids are -1 when the damage had no source entity — fall, fire, poison, void.
                // Comparing those against an unset local id credited us with every environmental
                // scratch on the server, which is where the phantom kills came from.
                val self = localPlayerId
                if (self >= 0 && (packet.sourceCauseId == self || packet.sourceDirectId == self)) {
                    pendingDamage += packet.entityId
                }
            }

            // The outgoing attack itself, rather than `AttackEntityEvent`: modules that build the
            // packet by hand — this fork's AutoCrystal among them — never fire that event, so
            // listening for it would miss exactly the kills the Explosions credit exists for.
            is ServerboundAttackPacket -> {
                if (event.origin == TransferOrigin.OUTGOING) {
                    pendingAttacks += packet.entityId
                }
            }

            else -> Unit
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val now = player.tickCount.toLong()
        localPlayerId = player.id

        if (selfTestPending) {
            runSelfTest()
        }

        drainAttacks(now)

        while (true) {
            val victimId = pendingDamage.poll() ?: break
            attackedUntil.put(victimId, now + ATTACK_TTL_TICKS)
        }

        drainEntityEvents()

        if (Fallbacks.enabled) {
            trackHealth()
        } else if (!lastHealth.isEmpty()) {
            lastHealth.clear()
            lastHealthTick.clear()
        }

        // Forget attacks that aged out, otherwise the maps grow for the whole session.
        attackedUntil.int2LongEntrySet().removeIf { it.longValue < now }
        fragCooldown.int2LongEntrySet().removeIf { it.longValue < now }
        blasts.removeIf { now - it.tick > BLAST_CREDIT_TICKS }
        removedBodies.values.removeIf { now - it.tick > REMOVED_BODY_TICKS }

        snapshotCrystals()
    }

    /**
     * Turns the attacks we sent last tick into either a victim we can be credited for, or — when the
     * thing we hit was a crystal — a blast that nearby deaths can be traced back to.
     */
    private fun drainAttacks(now: Long) {
        while (true) {
            val id = pendingAttacks.poll() ?: break
            val crystal = crystalPositions.get(id)

            if (crystal == null) {
                debug("attacked id $id")
                attackedUntil.put(id, now + ATTACK_TTL_TICKS)
                continue
            }

            debug("blast at ${crystal.x.toInt()} ${crystal.y.toInt()} ${crystal.z.toInt()}")

            if (blasts.size >= MAX_TRACKED_BLASTS) {
                blasts.removeAt(0)
            }
            blasts.add(Blast(crystal, now))
        }
    }

    private fun snapshotCrystals() {
        crystalPositions.clear()
        if (credit != Credit.EXPLOSIONS) {
            return
        }

        for (entity in world.entitiesForRendering()) {
            if (entity is EndCrystal) {
                crystalPositions.put(entity.id, entity.position())
            }
        }
    }

    private fun drainEntityEvents() {
        val level = mc.level ?: return

        while (true) {
            val event = pendingEntityEvents.poll() ?: break
            val id = event.leftInt()
            val victim = level.getEntity(id) as? LivingEntity
                ?: removedBodies.get(id)?.entity as? LivingEntity
                ?: continue

            if (event.rightInt() == EntityEvent.DEATH.toInt()) {
                debug("death of ${victim.name.string} (id $id)")
                tryFrag(victim)
            } else if (isOurs(victim, player.tickCount.toLong(), requireAttack = false)) {
                // Only pops we caused are interesting; a pop across the map is noise. Same credit
                // rules as a kill, so a crystal that forces a totem still counts under Explosions.
                onTotemPop(victim)
            }
        }
    }

    /**
     * Detection path 3, plus the health history the removal path reads.
     *
     * Servers that respawn instantly and in place never remove the entity at all — it simply snaps
     * from nearly dead back to full in a single tick. Nothing else in the game does that: potions
     * and golden apples regenerate over many ticks.
     */
    private fun trackHealth() {
        val now = player.tickCount.toLong()

        for (other in world.players()) {
            val id = other.id
            if (other === player || attackedUntil.get(id) < now) {
                continue
            }

            val current = other.health
            val previous = lastHealth.put(id, current)
            val sampledAt = lastHealthTick.put(id, now)

            // A sample older than a tick means the body was not being tracked continuously — it went
            // out of range and came back. Comparing across that gap is what produced phantom kills
            // for players who simply healed out of sight.
            if (previous < 0f || now - sampledAt > 1L) {
                continue
            }

            val resurrected = previous <= DEATH_HEALTH_THRESHOLD &&
                current > previous &&
                current >= other.maxHealth * RESPAWN_HEALTH_RATIO

            if (Fallbacks.onRespawnSnap && resurrected) {
                tryFrag(other)
            }
        }
    }

    /**
     * Fires a frag if the death is ours and has not been counted already. Every detection path
     * funnels through here, so overlapping signals for a single death produce a single effect.
     *
     * @param requireAttack skips the looser [Credit] modes, for paths that are already a guess.
     */
    private fun tryFrag(entity: Entity, requireAttack: Boolean = false) {
        if (!includeMobs && entity !is Player) {
            return
        }

        val now = player.tickCount.toLong()
        if (fragCooldown.get(entity.id) >= now) {
            return
        }

        if (!isOurs(entity, now, requireAttack)) {
            debug("skipped ${entity.name.string}: not credited (Credit=$credit)")
            return
        }

        fragCooldown.put(entity.id, now + FRAG_DEDUPE_TICKS)
        attackedUntil.remove(entity.id)
        onFrag(entity)
    }

    private fun isOurs(entity: Entity, now: Long, requireAttack: Boolean): Boolean {
        if (attackedUntil.get(entity.id) >= now) {
            return true
        }

        if (requireAttack) {
            return false
        }

        return when (credit) {
            Credit.OWN_HITS -> false
            Credit.EXPLOSIONS -> nearRecentBlast(entity)
            Credit.NEARBY -> entity.distanceTo(player) <= radius
        }
    }

    private fun nearRecentBlast(entity: Entity): Boolean {
        val position = entity.position()
        return blasts.any { it.pos.distanceToSqr(position) <= BLAST_CREDIT_RADIUS_SQ }
    }

    private fun debug(message: String) {
        if (Diagnostics.enabled && Diagnostics.log) {
            chat(regular("[FragEffects] $message"), MessageMetadata(id = "FragEffects#debug", remove = false))
        }
    }

    /**
     * Set on enable, consumed on the first tick. Modules are restored from config before a world
     * exists, so the self test cannot touch `player` from [onEnabled] itself.
     */
    private var selfTestPending = false

    override fun onEnabled() {
        reset()
        selfTestPending = Diagnostics.enabled && Diagnostics.testOnEnable
        super.onEnabled()
    }

    /**
     * Straight into the effect, skipping every credit check: if this shows nothing the problem is
     * rendering, and if it shows then the problem is detection.
     */
    private fun runSelfTest() {
        selfTestPending = false
        debug("self test")
        onFrag(player)
    }

    private fun onFrag(entity: Entity) {
        debug("FRAG on ${entity.name.string}")
        strike.trigger(effectClock, duration)
        strikeHandled = false
        lastVictimPos = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

        beginHitStop()
        beginFlick()
        playSound(FragEffectsSounds.strike, Sounds.volume)

        // Two overlapping bursts: a dense short-lived shell and a sparser lingering one.
        spawnBurst(2400, 150f)
        spawnBurst(3600, 450f)
    }

    private fun onTotemPop(entity: LivingEntity) {
        totem.trigger(effectClock, duration)
        lastVictimPos = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

        beginFlick()
        playSound(FragEffectsSounds.totem, Sounds.volume * TOTEM_VOLUME_RATIO)

        spawnBurst(800, 900f)
        spawnBurst(250, 3500f)
    }

    /** The second, wider wave the original threw once the flash reached its peak. */
    private fun onStrikePeak() {
        spawnBurst(360, 2400f)
        spawnBurst(1200, 300f)
    }

    private fun spawnBurst(count: Int, lifetimeMs: Float) {
        if (!Particles.enabled) {
            return
        }

        val center = lastVictimPos ?: return

        particles.spawnRing(
            center = center,
            count = (count * Particles.amount).toInt(),
            // Debris outlives the flash by the same factor, so the burst does not vanish while the
            // screen is still red.
            lifetimeMs = lifetimeMs * duration,
            now = effectClock,
            // The ring starts wider while a totem is flaring, which is what pushes that burst out.
            startDistance = (0.5f + totem.value(effectClock)) * Particles.spread,
            random = random::nextFloat,
        )
    }

    private fun beginHitStop() {
        if (HitStop.enabled) {
            hitStopUntilRealMs = System.currentTimeMillis() + HitStop.length
        }
    }

    private fun beginFlick() {
        if (flickToVictim) {
            flickUntilRealMs = System.currentTimeMillis() + FLICK_DURATION_MS
        }
    }

    private fun playSound(sound: SoundEvent?, volume: Float) {
        if (sound != null && Sounds.enabled && volume > 0f) {
            mc.soundManager.play(SimpleSoundInstance.forUI(sound, 1f, volume))
        }
    }

    // -- Rotation --------------------------------------------------------------------------------

    @Suppress("unused")
    private val flickHandler = handler<GameTickEvent> {
        if (!flickToVictim || System.currentTimeMillis() > flickUntilRealMs) {
            return@handler
        }

        val victim = lastVictimPos ?: return@handler

        RotationManager.setRotationTarget(
            Rotation.lookingAt(victim, player.eyePosition),
            considerInventory = false,
            valueGroup = rotations,
            // Deliberately the lowest priority: a cosmetic flourish must never outrank the aura or
            // the crystal placer fighting for the same rotation.
            priority = Priority.NOT_IMPORTANT,
            provider = this,
        )
    }

    // -- Clock -----------------------------------------------------------------------------------

    /**
     * Advances the effect clock. Called from the overlay pass, which runs once per frame whether or
     * not there is anything to draw.
     */
    private fun advanceClock() {
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now

        if (previous == 0L) {
            return
        }

        val realDeltaMs = ((now - previous) / 1_000_000.0).toFloat().coerceIn(0f, MAX_FRAME_DELTA_MS)
        val dilation = if (HitStop.enabled && System.currentTimeMillis() < hitStopUntilRealMs) {
            HitStop.slowdown
        } else {
            1f
        }

        effectClock += realDeltaMs * dilation

        // A float loses sub-millisecond resolution after a few hours of uptime, which would make the
        // envelopes step instead of ramp. Rebasing is safe only while nothing holds a timestamp.
        if (effectClock > CLOCK_REBASE_MS &&
            particles.isEmpty &&
            strike.value(effectClock) <= 0f &&
            totem.value(effectClock) <= 0f
        ) {
            effectClock = 0f
            strike.reset()
            totem.reset()
        }
    }

    // -- Rendering -------------------------------------------------------------------------------

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        advanceClock()

        val strikeValue = strike.value(effectClock)
        if (!strikeHandled && strike.peaked) {
            strikeHandled = true
            onStrikePeak()
        }

        if (strikeValue <= 0.004f) {
            return@handler
        }

        val width = mc.window.guiScaledWidth.toFloat()
        val height = mc.window.guiScaledHeight.toFloat()

        with(event.context) {
            if (Flash.enabled && strike.isRising(effectClock)) {
                // Peaks halfway through the attack and is gone by the time it ends.
                val flashAlpha =
                    (wave((strikeValue - 0.5f).coerceAtLeast(0f) * 2f) * Flash.power).coerceAtMost(1f)
                if (flashAlpha > 0.004f) {
                    drawQuad(
                        0f, 0f, width, height,
                        fillColor = Color4b.WHITE.with(a = (255f * flashAlpha).toInt().coerceIn(0, 255)),
                    )
                }
            }

            if (Vignette.enabled) {
                drawVignette(width, height, strikeValue.coerceIn(0f, 1f), Vignette.power)
            }
        }
    }

    /**
     * The vignette starts oversized and zooms in as it saturates from white to red, so the frame
     * appears to close in on the kill.
     */
    private fun GuiGraphicsExtractor.drawVignette(
        width: Float,
        height: Float,
        progress: Float,
        intensity: Float,
    ) {
        if (progress <= 0.004f || intensity <= 0f) {
            return
        }

        // [progress] alone drives the geometry and the tint, [intensity] only the alpha. Folding the
        // strength into progress would peg it above 1 for most of the envelope, and the vignette
        // would sit at screen size the whole time instead of closing in on the kill.
        val inverse = 1f - progress
        val overshootX = width * 0.5f * inverse * inverse
        val overshootY = height * 0.5f * inverse * inverse

        val tint = (255f * (1f - progress / VIGNETTE_DESATURATION)).toInt().coerceIn(0, 255)
        val alpha = (VIGNETTE_PEAK_ALPHA * progress * intensity).toInt().coerceIn(0, 255)

        drawTexQuad(
            vignetteTexture.textureSetup,
            -overshootX, -overshootY, width + overshootX, height + overshootY,
            argb = Color4b(255, tint, tint, alpha).argb,
            pipeline = ClientRenderPipelines.GUI.TexQuadAdditiveNoCull,
        )
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        if (!Particles.enabled || particles.isEmpty) {
            return@handler
        }

        particles.prune(effectClock)
        if (particles.isEmpty) {
            return@handler
        }

        val totemValue = totem.value(effectClock)
        val brightColor = Particles.brightColor
            .interpolateTo(Particles.totemColor, min(totemValue * TOTEM_TINT_SHARPNESS, 1f).toDouble())

        renderEnvironmentForWorld(event.matrixStack) {
            particles.render(
                env = this,
                now = effectClock,
                globalAlpha = 1f,
                pointHalfSize = Particles.size,
                darkColor = Particles.darkColor,
                brightColor = brightColor,
                // The cloud shatters into a mesh once the flash is past its peak, exactly as the
                // original's switch from GL_POINTS to GL_TRIANGLES did.
                web = !strike.isRising(effectClock),
            )
        }
    }

    // -- Hooks -----------------------------------------------------------------------------------

    /**
     * Multiplier applied to the camera FOV, called from `MixinCamera`.
     * Returns [original] untouched when the module is off or idle.
     */
    @JvmStatic
    fun applyFovPunch(original: Float): Float {
        if (!running || !FovPunch.enabled) {
            return original
        }

        val punch = strike.value(effectClock) + quadInOut(totem.value(effectClock)) / 6f
        return original * (1f + punch.coerceIn(0f, 1f) * FovPunch.power)
    }

    /** Desaturation strength for [FragEffectsShader], 0 when nothing is happening. */
    @JvmStatic
    fun desaturationStrength(): Float {
        if (!running || !Desaturate.enabled) {
            return 0f
        }

        val strikeValue = strike.value(effectClock)
        val totemValue = totem.value(effectClock)

        // The original gated this to the top half of the flash, where the white overlay already
        // hid most of it. Running it across the whole envelope is what makes the drain readable.
        return (strikeValue + totemValue * TOTEM_DESATURATION_RATIO).coerceIn(0f, 1f) * Desaturate.strength
    }

    // -- Lifecycle -------------------------------------------------------------------------------

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        reset()
    }

    override fun onDisabled() {
        reset()
        // The desaturate scratch target is window-sized; without this it stayed resident for the
        // rest of the session after a single frag.
        FragEffectsShader.release()
        super.onDisabled()
    }

    private fun reset() {
        attackedUntil.clear()
        lastHealth.clear()
        lastHealthTick.clear()
        fragCooldown.clear()
        blasts.clear()
        crystalPositions.clear()
        removedBodies.clear()
        pendingEntityEvents.clear()
        pendingDamage.clear()
        pendingAttacks.clear()
        particles.clear()
        strike.reset()
        totem.reset()
        strikeHandled = true
        lastVictimPos = null
        hitStopUntilRealMs = 0L
        flickUntilRealMs = 0L
        lastFrameNanos = 0L
    }

    // -- Math ------------------------------------------------------------------------------------

    private fun quadInOut(x: Float): Float = 2f * (1f - x) * x * x + x * x

    /** 0 -> 1 -> 0 across [0, 1], eased at both ends. */
    private fun wave(x: Float): Float {
        val clamped = x.coerceIn(0f, 1f)
        return quadInOut(if (clamped > 0.5f) (1f - clamped) * 2f else clamped * 2f)
    }

    /** Health at or below which a vanished body is read as dead rather than out of range. */
    private const val DEATH_HEALTH_THRESHOLD = 8f

    /** Fraction of max health a respawn snaps back to. */
    private const val RESPAWN_HEALTH_RATIO = 0.95f

    /** How long one body stays counted, so overlapping detection paths fire the effect once. */
    private const val FRAG_DEDUPE_TICKS = 40L

    /** How long a crystal we broke keeps crediting deaths beside it. */
    private const val BLAST_CREDIT_TICKS = 20L

    /** Squared blast radius a death has to land inside to be credited to it. */
    private const val BLAST_CREDIT_RADIUS_SQ = 12.0 * 12.0

    private const val MAX_TRACKED_BLASTS = 16

    /** How long a removed body stays available for a death queued in the same tick to find. */
    private const val REMOVED_BODY_TICKS = 5L

    private const val MAX_PARTICLES = 40000
    private const val MAX_FRAME_DELTA_MS = 100f
    private const val CLOCK_REBASE_MS = 600_000f
    private const val FLICK_DURATION_MS = 350L
    private const val TOTEM_VOLUME_RATIO = 0.55f
    private const val TOTEM_TINT_SHARPNESS = 18f
    private const val TOTEM_DESATURATION_RATIO = 0.6f
    private const val VIGNETTE_PEAK_ALPHA = 190f
    private const val VIGNETTE_DESATURATION = 1.25f

}
