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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ArmorAssumption
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.BlockReduction
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.BlockingState
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.EnchantProtection
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.LevelShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext

/**
 * One tracked victim of a [WorldView]: the live [entity] plus two frozen [VictimState] snapshots — [placeState] at
 * the position predicted for a crystal placed now, [breakState] at the position predicted for a crystal attacked
 * now (the `Predict*Ticks` inputs of [WorldView.capture]; `0` = current position) — and the [source] the exposure
 * rays of that victim read. Each snapshot carries its own predicted position everywhere the position is used:
 * `feet`, `box` and `blocking.position` (the shield-arc origin) all agree, as they do in vanilla.
 *
 * [source] is a [LevelShapeSource] over the live client level with `CollisionContext.of(entity)`
 * (`net/minecraft/world/phys/shapes/CollisionContext.java:24-31`: `new EntityCollisionContext(entity, false,
 * false)` for everything but minecarts), i.e. the very context `ServerExplosion.getSeenPercent` clips with:
 * `new ClipContext(from, center, COLLIDER, NONE, entity)` (`ServerExplosion.java:95-97`) delegates to
 * `CollisionContext.of(entity)` (`net/minecraft/world/level/ClipContext.java:28-30`). One instance per
 * (tick, victim): its memo must not outlive the tick.
 */
class TargetEntry(
    val entity: LivingEntity,
    val placeState: VictimState,
    val breakState: VictimState,
    val source: ShapeSource,
)

/**
 * Everything one crystal-aura tick looks at, captured on the main thread by [capture]: the local player as a victim
 * ([self], [selfSource]), the tracked [targets], every live [EndCrystal] ([crystals]) and the level [difficulty];
 * [eye] is `player.getEyePosition()` (`net/minecraft/world/entity/Entity.java:1893`), the origin of every range
 * test, [tick] the client tick the view belongs to.
 *
 * The victim snapshots are frozen for the tick. The block and entity queries ([state], [isPlaceable],
 * [isColumnFree]) deliberately read the live client level instead, so that a placement executed earlier in the same
 * tick is observed by the next query. Main thread only.
 *
 * ### What the client knows about remote victims, and how it gets there
 * - Armor / toughness: `Attributes.ARMOR` and `ARMOR_TOUGHNESS` are `setSyncable(true)`
 *   (`net/minecraft/world/entity/ai/attributes/Attributes.java:10-13`); `ClientboundUpdateAttributesPacket` →
 *   `ClientPacketListener.handleUpdateAttributes` (`net/minecraft/client/multiplayer/ClientPacketListener.java:
 *   2339-2361`) installs base value and transient modifiers, so `getArmorValue()`
 *   (`net/minecraft/world/entity/LivingEntity.java:1847-1849`) and `getAttributeValue(ARMOR_TOUGHNESS)`
 *   (`:2169-2171`) return the server's values.
 * - Health: synced entity data `DATA_HEALTH_ID` (`LivingEntity.java:182, 1149-1151`); [getActualHealth] prefers a
 *   below-name health objective when the server publishes one.
 * - Absorption: players sync it through `DATA_PLAYER_ABSORPTION_ID`
 *   (`net/minecraft/world/entity/player/Player.java:135, 1683`); every other living entity keeps a plain field
 *   (`LivingEntity.java:254, 3325-3327`) that is never sent, so a remote mob reports 0.
 * - Resistance: NOT knowable for a remote victim. `ClientboundUpdateMobEffectPacket` →
 *   `ClientPacketListener.handleUpdateMobEffect` (`ClientPacketListener.java:1796-1810`) does build a
 *   `MobEffectInstance` from `packet.getEffectAmplifier()` and `forceAddEffect`s it, but the server never sends
 *   that packet to trackers. Its only senders are `ServerPlayer.onEffectAdded` / `onEffectUpdated`
 *   (`net/minecraft/server/level/ServerPlayer.java:1638-1655`: `this.connection.send(...)`, i.e. the affected
 *   player's own connection), `LivingEntity.sendEffectToPassengers` (`LivingEntity.java:1069-1075`: only
 *   `ServerPlayer`s riding the entity) and the bulk re-send `PlayerList.sendActiveEffects(livingEntity,
 *   connection)` (`net/minecraft/server/players/PlayerList.java:458-462`), whose callers are the player's own
 *   join / respawn / dimension change (`PlayerList.java:214, 418`, `ServerPlayer.java:1139`) and
 *   `ServerPlayer.startRiding` for the entity being mounted (`ServerPlayer.java:2119-2126`).
 *   `ChunkMap.TrackedEntity` never sends it. So `getEffect(MobEffects.RESISTANCE)` (`LivingEntity.java:986-988`,
 *   `MobEffects.RESISTANCE` is a `Holder<MobEffect>`, `net/minecraft/world/effect/MobEffects.java:53`) is
 *   authoritative for the local player (and for a mount we ride) and always `null` for everybody else — an
 *   over-estimate of 1.25× per level, up to "full damage predicted against a victim that takes zero" at
 *   Resistance V. [capture]'s `assumeResistance` fills that hole; see [VictimCapture.resistanceOf].
 * - Armour and protection: both are read off the victim's equipment, which vanilla DOES sync
 *   (`ClientboundSetEquipmentPacket`), so on a vanilla server both are exact. A server may nonetheless strip the
 *   enchantment components from that packet — the common "enchantment obfuscation" — and then
 *   [EnchantProtection.protectionPoints] reads every opponent as unenchanted while their armour points still look
 *   right, which over-predicts the damage by up to the Protection cap. Such a server usually leaves a DECOY
 *   enchantment on the stack so the item still glints, so the visible list is false rather than empty. A server
 *   that hides the equipment outright also zeroes `getArmorValue()`. [capture]'s `assumeArmor` fills both holes by
 *   substituting our own readings; see [ArmorAssumption] and [VictimCapture.protectionOf].
 * - Blocking: `isUsingItem()` is bit 1 of the synced `DATA_LIVING_ENTITY_FLAGS` (`LivingEntity.java:3349-3351`).
 *   `onSyncedDataUpdated` (`:3447-3463`) sets `useItem` from the used hand and `useItemRemaining =
 *   useItem.getUseDuration(this)` when the flag appears; `updatingUsingItem` (`:3357-3365`) → `updateUsingItem`
 *   (`:3405`) counts `useItemRemaining` down every client tick. `getItemBlockingWith()` (`:3566-3579`: component
 *   present and `elapsedTicks >= blockDelayTicks()`) hence works for remote entities, with the remote count
 *   starting one-way latency after the server's (the block delay is judged elapsed slightly late).
 * - I-frames: `ClientboundDamageEventPacket` → `ClientPacketListener.handleDamageEvent`
 *   (`ClientPacketListener.java:1231-1237`) → `LivingEntity.handleDamageEvent` (`LivingEntity.java:2017-2021`)
 *   sets `invulnerableTime = 20`; `LivingEntity.baseTick` decrements it every tick for everything but a
 *   `ServerPlayer` (`:465-466`). The amount is never sent — `lastHurt` comes from the [CrystalLedger] estimate.
 * - Game mode: `PlayerInfo.getGameMode()` (`net/minecraft/client/multiplayer/PlayerInfo.java:74-76`), updated by
 *   `ClientboundPlayerInfoUpdatePacket` `UPDATE_GAME_MODE` (`ClientPacketListener.java:2027-2032`); this is also
 *   what `Player.isCreative()` / `isSpectator()` read on the client (`Player.java:1636-1652` through
 *   `AbstractClientPlayer.gameMode()`, `net/minecraft/client/player/AbstractClientPlayer.java:35-38`).
 * - `Entity.invulnerable` (`Entity.java:287`) is NBT only (`:1991, 2069`) and never synced: `isInvulnerable()` is
 *   false for every remote entity. It is still consulted, for exactness.
 */
class WorldView(
    val tick: Int,
    val eye: Vec3,
    val self: VictimState,
    val selfSource: ShapeSource,
    val targets: List<TargetEntry>,
    val crystals: List<EndCrystal>,
    val difficulty: Difficulty,
) {

    /** `level.getBlockState(pos)` of the live client level (`net/minecraft/world/level/Level.java:364`). */
    fun state(pos: BlockPos): BlockState = world.getBlockState(pos)

    /**
     * The block part of `EndCrystalItem.useOn` (`net/minecraft/world/item/EndCrystalItem.java:26-32`): the clicked
     * state `is(Blocks.OBSIDIAN) || is(Blocks.BEDROCK)` (`TypedInstance.is(T)` = `typeHolder().value() == rawType`,
     * `net/minecraft/core/TypedInstance.java:22-24`; `BlockStateBase implements TypedInstance<Block>`,
     * `BlockBehaviour.java:424`) and `level.isEmptyBlock(pos.above())` = `getBlockState(above).isAir()`
     * (`net/minecraft/world/level/LevelReader.java:85-87`, `BlockBehaviour.java:581-583`).
     */
    fun isPlaceable(pos: BlockPos): Boolean {
        val base = state(pos)
        return (base.`is`(Blocks.OBSIDIAN) || base.`is`(Blocks.BEDROCK)) && state(pos.above()).isAir
    }

    /**
     * The entity part of `EndCrystalItem.useOn` (`EndCrystalItem.java:34-39`): `level.getEntities(null,
     * new AABB(x, y, z, x + 1.0, y + 2.0, z + 1.0))` with the integer coordinates of [above] as doubles; the two
     * argument overload applies `EntitySelector.NO_SPECTATORS` (`net/minecraft/world/level/EntityGetter.java:29-31`,
     * `EntitySelector.java:15`: `entity -> !entity.isSpectator()`) and `Level.getEntities`
     * (`net/minecraft/world/level/Level.java:772-789`) also reports intersecting ender dragon parts. Vanilla fails
     * when the list is non-empty; here every listed entity must satisfy [ignore] instead (crystals we already
     * attacked or expect to be chain-removed are still present client-side, the server has removed them).
     */
    fun isColumnFree(above: BlockPos, ignore: (Entity) -> Boolean): Boolean {
        val x = above.x.toDouble()
        val y = above.y.toDouble()
        val z = above.z.toDouble()
        val entities = world.getEntities(null, AABB(x, y, z, x + COLUMN_WIDTH, y + COLUMN_HEIGHT, z + COLUMN_WIDTH))
        return entities.all(ignore)
    }

    companion object : MinecraftShortcuts {

        /** Footprint of the crystal obstruction box, `x + 1.0` / `z + 1.0` (`EndCrystalItem.java:37`). */
        private const val COLUMN_WIDTH = 1.0

        /** Height of the crystal obstruction box, `y + 2.0` (`EndCrystalItem.java:37`). */
        private const val COLUMN_HEIGHT = 2.0

        /**
         * Captures the world for client tick [tick] (main thread, in game).
         *
         * - `eye = player.getEyePosition()`; `difficulty = level.getDifficulty()`
         *   (`net/minecraft/world/level/LevelAccessor.java:48-50` → `ClientLevel.ClientLevelData.getDifficulty()`,
         *   `net/minecraft/client/multiplayer/ClientLevel.java:1123-1125`).
         * - [targets] become [TargetEntry]s: `placeState` at the position [placePredictTicks] ahead, `breakState`
         *   [breakPredictTicks] ahead (`0` = current position), `invulnerableTime` compensated by [latencyTicks]
         *   and `lastHurt` from [ledger] (see [VictimCapture]).
         * - `crystals`: `level.entitiesForRendering()` (`ClientLevel.java:331-333`, every entity the client holds)
         *   filtered to alive `EndCrystal`s (`Entity.isAlive()` = `!isRemoved()`, `Entity.java:2152-2154`).
         * - `selfSource`: `LevelShapeSource(level, CollisionContext.of(player))`, the local player's own context
         *   exactly like each target's (see [TargetEntry.source]).
         *
         * [assumeResistance] is an AMPLIFIER (`-1` = assume no effect, `0` = Resistance I, ...), used only as a
         * fallback for victims whose real Resistance the client cannot see — see the class KDoc and
         * [VictimCapture.resistanceOf]. Callers translate a 1-based level setting with `level - 1`.
         */
        @Suppress("LongParameterList")
        fun capture(
            tick: Int,
            targets: List<LivingEntity>,
            ledger: CrystalLedger,
            placePredictTicks: Int,
            breakPredictTicks: Int,
            latencyTicks: Int,
            assumeResistance: Int,
            assumeArmor: ArmorAssumption,
        ): WorldView {
            val level = world
            val victims = VictimCapture(
                level = level,
                ledger = ledger,
                placePredictTicks = placePredictTicks,
                breakPredictTicks = breakPredictTicks,
                latencyTicks = latencyTicks,
                assumeResistance = assumeResistance,
                assumeArmor = assumeArmor,
            )
            val crystals = level.entitiesForRendering().filterIsInstance<EndCrystal>().filter { it.isAlive }
            return WorldView(
                tick = tick,
                eye = player.eyePosition,
                self = victims.self(),
                selfSource = LevelShapeSource(level, CollisionContext.of(player)),
                targets = targets.map(victims::target),
                crystals = crystals,
                difficulty = victims.difficulty,
            )
        }
    }
}

/**
 * The per-call constants and victim snapshot helpers of one [WorldView.capture]. Builds [VictimState]s from live
 * entities; every field is documented at [VictimState], this class documents where the value is read from.
 *
 * ### Skip conditions
 * `Player.hurtServer` (`Player.java:676-705`) refuses damage when `isInvulnerableTo(level, source)` (for a living
 * entity `isInvulnerableToBase(source) || EnchantmentHelper.isImmuneToDamage(...)`, `LivingEntity.java:3934-3936`;
 * `isInvulnerableToBase` = removed, or `invulnerable && !BYPASSES_INVULNERABILITY && !source.isCreativePlayer()`,
 * `Entity.java:2940-2945`), when `abilities.invulnerable && !BYPASSES_INVULNERABILITY` (`:680`) or when
 * `isDeadOrDying()` (`:684`, `getHealth() <= 0.0F`, `LivingEntity.java:1157-1159`). `player_explosion` is in none
 * of the bypass tags (contract), `abilities.invulnerable` is set by `GameType.updatePlayerAbilities` for CREATIVE
 * and SPECTATOR (`net/minecraft/world/level/GameType.java:62-77`) and `source.isCreativePlayer()` is "the causing
 * player has `abilities.instabuild`" (`net/minecraft/world/damagesource/DamageSource.java:105-107`) — the causing
 * player of our crystal is us. Remote players expose no abilities, so their game mode is read from `PlayerInfo`
 * ([skipOf]); the local player's abilities arrive through `ClientboundPlayerAbilitiesPacket` →
 * `ClientPacketListener.handlePlayerAbilities` (`ClientPacketListener.java:2082-2088`) ([selfSkip]).
 * `EnchantmentHelper.isImmuneToDamage` (data-driven immunity effects) is not mirrored — no vanilla enchantment
 * grants immunity to explosions.
 *
 * ### Teams
 * `ServerPlayer.hurtServer` has a second refusal the base `Player` does not:
 * `if (entity instanceof Player player && !this.canHarmPlayer(player)) return false` — `this` the VICTIM, the
 * argument the ATTACKER (`ServerPlayer.java:986-999`). It applies to us because the causing entity of a crystal
 * we detonate IS us: `EndCrystal.hurtServer` builds `damageSources().explosion(this, source.getEntity())` when
 * the incoming source has an entity (`EndCrystal.java:96-98`), `explosion(entity, cause)` puts the cause in
 * `causingEntity` (`DamageSources.java:270-272`) and `DamageSource.getEntity()` returns exactly that
 * (`DamageSource.java:67-69`). So a victim we cannot harm takes ZERO from our crystal, and both [skipOf] and
 * [selfSkip] ask vanilla's own `Player.canHarmPlayer` (`Player.java:723-731`) in the server's direction.
 *
 * Only the TEAM half is modelled, and that is a client-side fact: `Team.isAlliedTo` is object identity
 * (`Team.java:17-19`) over the scoreboard the server syncs, and the victim's own team decides - a victim with no
 * team can always be harmed, whatever team WE are on. The other half, `ServerPlayer.canHarmPlayer`'s
 * `level().isPvpAllowed()` (`ServerPlayer.java:1003-1009`), is a server flag the client is never told; when it is
 * off the real damage is 0 and we predict more, which wastes a crystal on a target and refuses a placement on
 * ourselves - conservative in both directions, so there is nothing to guess at.
 *
 * NOT true of `ModuleAutoAnchor`: `badRespawnPointExplosion(boomPos)` carries no entity at all, so
 * `canHarmPlayer` is never consulted and a team does not protect against an anchor. Do not copy this across.
 *
 * ### I-frame latency
 * A target's `invulnerableTime` was set to 20 when its damage event reached us, one-way latency after the server
 * hit, and the explosion we trigger lands one-way latency after we act; when it lands the server's counter is
 * therefore about `reading - RTT`, hence `max(invulnerableTime - latencyTicks, 0)`. An under-compensation keeps
 * the target "inside the window" (only the delta above `lastHurt` is predicted), i.e. errs towards less damage.
 * The local player's counter is passed uncompensated: the server sends the local player its own damage events
 * (`ServerLevel.broadcastDamageEvent`, `net/minecraft/server/level/ServerLevel.java:1189-1191` →
 * `TrackedEntity.sendToTrackingPlayersAndSelf`, `net/minecraft/server/level/ChunkMap.java:1347-1351`), so the field
 * is the client's own copy of its own state kept by the same code path, and with `lastHurt = null` (unknown,
 * treated as 0 by the pipeline) the window can never lower the self estimate (`damage - 0`): self damage is never
 * under-estimated whatever the counter says.
 *
 * ### Predicted feet
 * `ticks <= 0` → `entity.position()` (`Entity.java:3632`), otherwise
 * [PositionExtrapolation.getBestForEntity]`(entity).getPositionInTicks(ticks)` (player simulation for players,
 * linear `position + ticks * (position - lastPos)` otherwise); `box = entity.getBoundingBoxAt(feet)` =
 * `boundingBox.move(feet - position())`.
 */
private class VictimCapture(
    private val level: ClientLevel,
    private val ledger: CrystalLedger,
    private val placePredictTicks: Int,
    private val breakPredictTicks: Int,
    private val latencyTicks: Int,
    private val assumeResistance: Int,
    private val assumeArmor: ArmorAssumption,
) : MinecraftShortcuts {

    /** `DamageTypes.PLAYER_EXPLOSION` of this level's registry, see [EnchantProtection.playerExplosionType]. */
    private val damageType: Holder<DamageType> = EnchantProtection.playerExplosionType(level)

    /** `level.getDifficulty()` (`LevelAccessor.java:48-50`, `ClientLevel.java:1123-1125`). */
    val difficulty: Difficulty = level.difficulty

    /**
     * The local player at its current position; `invulnerableTime` uncompensated and `lastHurt = null` (class KDoc).
     */
    fun self(): VictimState = victimOf(player, player.position(), selfSkip(), player.invulnerableTime, lastHurt = null)

    /**
     * [TargetEntry] for [entity]: `breakState` at [breakPredictTicks], `placeState` at [placePredictTicks] (the
     * same snapshot when both are equal), `invulnerableTime = max(entity.invulnerableTime - latencyTicks, 0)`
     * (`Entity.invulnerableTime`, `Entity.java:261`), `lastHurt = ledger.lastHurtEstimate(entity.getId())`.
     */
    fun target(entity: LivingEntity): TargetEntry {
        val breakFeet = predictedFeet(entity, breakPredictTicks)
        val breakState = victimOf(
            entity,
            breakFeet,
            skipOf(entity),
            maxOf(entity.invulnerableTime - latencyTicks, 0),
            ledger.lastHurtEstimate(entity.id),
        )
        val placeState = if (placePredictTicks == breakPredictTicks) {
            breakState
        } else {
            val placeFeet = predictedFeet(entity, placePredictTicks)
            breakState.copy(
                feet = placeFeet,
                box = entity.getBoundingBoxAt(placeFeet),
                // The shield arc is measured from the SAME position the damage is: `applyItemBlocking` uses
                // `this.position()` (`LivingEntity.java:1302`), which at explosion time is the position
                // `Entity.distanceToSqr` sees as well. Keeping the break position here would score a victim
                // predicted to run past the crystal as fully shielded (or the reverse).
                blocking = breakState.blocking?.copy(position = placeFeet),
            )
        }
        return TargetEntry(entity, placeState, breakState, LevelShapeSource(level, CollisionContext.of(entity)))
    }

    private fun predictedFeet(entity: LivingEntity, ticks: Int): Vec3 = if (ticks <= 0) {
        entity.position()
    } else {
        PositionExtrapolation.getBestForEntity(entity).getPositionInTicks(ticks.toDouble())
    }

    /**
     * `armor = getArmorValue()`, `toughness = (float) getAttributeValue(ARMOR_TOUGHNESS)` (`LivingEntity.java:1880`),
     * `resistanceAmplifier` = [resistanceOf], `protectionPoints` for `player_explosion` with `isDirect = false`,
     * `absorption = getAbsorptionAmount()`, `blocking` = [blockingOf] at [feet].
     *
     * `health` = `getActualHealth(fromScoreboard = false)` = the synced `DATA_HEALTH_ID`
     * (`LivingEntity.java:182, 1149-1151`), i.e. exactly the `getHealth()` that `hurtServer` and `setHealth`
     * work on (`LivingEntity.java:1157-1159, 1940`). The default `fromScoreboard = true`
     * (`utils/entity/EntityExtensions.kt:898-909`) would prefer a BELOW_NAME objective score — an `Int` in
     * whatever scale the server picked (hearts, 0-100, 0-1000, ...) — which is unusable as the right-hand side
     * of `DamageResult.isLethalFor` (`VictimState.kt:134`) and of the lethal search bound
     * (`PlaceSearch.kt:380`): a 0-100 scale disables the lethal branch outright, a hearts scale claims kills
     * that are not there.
     */
    private fun victimOf(
        entity: LivingEntity,
        feet: Vec3,
        skip: Boolean,
        invulnerableTime: Int,
        lastHurt: Float?,
    ): VictimState = VictimState(
        feet = feet,
        box = entity.getBoundingBoxAt(feet),
        isPlayer = entity is Player,
        skip = skip,
        armor = armorOf(entity),
        toughness = toughnessOf(entity),
        resistanceAmplifier = resistanceOf(entity),
        protectionPoints = protectionOf(entity),
        absorption = entity.absorptionAmount,
        health = entity.getActualHealth(fromScoreboard = false),
        blocking = blockingOf(entity, feet),
        invulnerableTime = invulnerableTime,
        lastHurt = lastHurt,
        difficulty = difficulty,
    )

    /**
     * `getEffect(RESISTANCE)?.getAmplifier() ?: -1` (`LivingEntity.java:986-988`,
     * `MobEffectInstance.java:208`), with the `assumeResistance` fallback of [WorldView.capture] mixed in.
     *
     * The client only ever receives `ClientboundUpdateMobEffectPacket` for itself and for a mount it rides (see
     * the [WorldView] class KDoc), so the reading is authoritative for the local player and always `null` for
     * everybody else. For any non-local victim the assumed amplifier therefore stands in when nothing is
     * visible; `maxOf` makes sure it can never lower an effect we actually see (the ridden-mount case, and the
     * local player, which is exempted outright).
     */
    private fun resistanceOf(entity: LivingEntity): Int {
        val actual = entity.getEffect(MobEffects.RESISTANCE)?.amplifier ?: -1
        return if (entity === player) actual else maxOf(actual, assumeResistance)
    }

    /**
     * `getArmorValue()` (`LivingEntity.java:1880` reads it for `getDamageAfterArmorAbsorb`), SUBSTITUTED by ours
     * when [ArmorAssumption.FULL] is on. Not floored: a `maxOf` would be useless against the case the setting
     * exists for, a server that reports the victim's equipment as empty, because 0 loses every `max` - and it
     * would also be unable to correct a server reporting a decoy value higher than the truth. The cost is the
     * honest one: a victim genuinely better armoured than us is over-predicted. See [ArmorAssumption] for why the
     * two channels are separate settings.
     */
    private fun armorOf(entity: LivingEntity): Int {
        if (entity === player || assumeArmor != ArmorAssumption.FULL) return entity.armorValue
        return player.armorValue
    }

    /** `getAttributeValue(ARMOR_TOUGHNESS)` (`LivingEntity.java:1881`), substituted like [armorOf]. */
    private fun toughnessOf(entity: LivingEntity): Float {
        val attribute = Attributes.ARMOR_TOUGHNESS
        if (entity === player || assumeArmor != ArmorAssumption.FULL) {
            return entity.getAttributeValue(attribute).toFloat()
        }
        return player.getAttributeValue(attribute).toFloat()
    }

    /**
     * [EnchantProtection.protectionPoints] for the explosion damage type, SUBSTITUTED by ours for a remote victim
     * unless [ArmorAssumption.OFF].
     *
     * This is the channel that breaks on an obfuscating server: the points are computed from the enchantment
     * components of the victim's equipment stacks, so a server that rewrites them controls this number entirely.
     *
     * Substituted rather than maxed, which is the difference to [resistanceOf]. A hidden effect is genuinely
     * absent from the client's view, so assuming a minimum for it can only ever correct an over-estimate. A
     * rewritten enchantment list is not a missing reading but a false one: the usual obfuscation leaves a decoy
     * enchantment on the stack so the item still glints, and that decoy may well be a Protection level nobody has.
     * Treating it as a lower bound would let a fake Protection IV survive `max` and suppress the aura outright, so
     * once this is on, the victim's own enchantments are not evidence and are dropped.
     */
    private fun protectionOf(entity: LivingEntity): Float {
        if (entity === player || assumeArmor == ArmorAssumption.OFF) {
            return EnchantProtection.protectionPoints(entity, damageType)
        }
        return selfProtection
    }

    /** Our own protection points, resolved once per capture — [protectionOf] needs them for every victim. */
    private val selfProtection: Float by lazy { EnchantProtection.protectionPoints(player, damageType) }

    /**
     * `Player.hurtServer` guards for the local player (class KDoc): creative / spectator (`Player.java:1636-1652`),
     * `abilities.invulnerable` (`:680`, `Abilities.java:14`), the `invulnerable` flag (`Entity.java:2942`, the
     * creative-attacker exception is moot: a creative self is skipped anyway) and `isDeadOrDying()` (`:684`).
     */
    private fun selfSkip(): Boolean =
        player.isCreative || player.isSpectator || player.abilities.invulnerable || player.isInvulnerable ||
            player.isDeadOrDying || !player.canHarmPlayer(player)

    /**
     * Skip guards for a remote victim: `isDeadOrDying()`; the `invulnerable` flag unless we are the creative
     * attacker (`Entity.java:2942`, `DamageSource.java:105-107`: `abilities.instabuild` of the causing player); for
     * players a `PlayerInfo` game mode of CREATIVE or SPECTATOR (`ClientPacketListener.getPlayerInfo(UUID)`,
     * `ClientPacketListener.java:2575-2577`; `PlayerInfo.getGameMode()`, `PlayerInfo.java:74-76`) — the client-side
     * stand-in for `abilities.invulnerable`, which `GameType.updatePlayerAbilities` sets for exactly those two
     * modes (`GameType.java:62-77`). An unknown player (no `PlayerInfo`) is not skipped.
     */
    private fun skipOf(entity: LivingEntity): Boolean {
        if (entity.isDeadOrDying || (entity.isInvulnerable && !player.abilities.instabuild)) {
            return true
        }
        if (entity !is Player) {
            return false
        }
        if (!entity.canHarmPlayer(player)) {
            return true
        }
        val gameMode = network.getPlayerInfo(entity.uuid)?.gameMode
        return gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR
    }

    /**
     * Snapshot of the `applyItemBlocking` inputs (`LivingEntity.java:1285-1322`), null when `getItemBlockingWith()`
     * is null (`:3566-3579`: not using an item, no `BLOCKS_ATTACKS` component, or block delay not elapsed).
     * `BlocksAttacks` (`net/minecraft/world/item/component/BlocksAttacks.java:28-36`) carries
     * `List<DamageReduction> damageReductions()` and `Optional<HolderSet<DamageType>> bypassedBy()`;
     * `DamageReduction(float horizontalBlockingAngle, Optional<HolderSet<DamageType>> type, float base, float factor)`
     * (`:124`). `bypassed` = `bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)` (`:1294`),
     * `typeMatches` pre-resolves `!(type.isPresent() && !type.get().contains(source.typeHolder()))` of
     * `DamageReduction.resolve` (`:150`), `viewVector = calculateViewVector(0.0F, getYHeadRot())` (`:1301`;
     * `Entity.calculateViewVector` is public final, `Entity.java:1875-1883`; `getYHeadRot()` returns the public
     * field `yHeadRot`, `LivingEntity.java:229, 3302-3304`), `position = position()` (`:1302`).
     * `HolderSet.contains(Holder)`: `net/minecraft/core/HolderSet.java:31`.
     *
     * `position` is [feet], the position of the [VictimState] being built, NOT the live `entity.position()`:
     * vanilla evaluates the shield arc and the blast distance against the same `this.position()` at explosion
     * time (`LivingEntity.java:1302` vs `ServerExplosion.java:182`), so a predicted state has to carry the
     * predicted position in both places or a victim predicted to run past the crystal is scored with the arc it
     * has right now.
     *
     * `yHeadRot` stays the client's interpolated current value — there is no way to predict where the victim
     * will be looking, so it is an unavoidable prediction error of the arc (bounded: the arc only matters
     * within `horizontalBlockingAngle`, 90 degrees for a vanilla shield).
     */
    private fun blockingOf(entity: LivingEntity, feet: Vec3): BlockingState? {
        val stack = entity.itemBlockingWith ?: return null
        val blocksAttacks = stack.get(DataComponents.BLOCKS_ATTACKS) ?: return null
        val reductions = blocksAttacks.damageReductions().map { reduction ->
            BlockReduction(
                horizontalBlockingAngle = reduction.horizontalBlockingAngle(),
                typeMatches = reduction.type().map { it.contains(damageType) }.orElse(true),
                base = reduction.base(),
                factor = reduction.factor(),
            )
        }
        return BlockingState(
            reductions = reductions,
            bypassed = blocksAttacks.bypassedBy().map { it.contains(damageType) }.orElse(false),
            viewVector = entity.calculateViewVector(0.0f, entity.yHeadRot),
            position = feet,
        )
    }
}
