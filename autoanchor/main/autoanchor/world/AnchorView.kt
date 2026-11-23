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

package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.world

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.ArmorAssumption
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.BlockReduction
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.BlockingState
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.EnchantProtection
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.LevelShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.ShapeSource
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.VictimState
import net.ccbluex.liquidbounce.utils.block.canBeReplacedWith
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
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * `state.getValue(RespawnAnchorBlock.CHARGE)` when [state] is a respawn anchor, `null` otherwise.
 *
 * `CHARGE` is an `IntegerProperty` over `0..4`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:48-50`) and the block's ONLY property (`:199-201`) -
 * there is no waterlogging and no facing, so the charge fully describes an anchor's state. A `BlockItem`
 * placement always lands at `CHARGE = 0` (the block overrides neither `getStateForPlacement` nor `canSurvive`,
 * and `Items.RESPAWN_ANCHOR` is a plain `BlockItem`, `Items.java:2193`), `charge()` only ever increments
 * (`:176-181`) and nothing decrements it.
 *
 * Split out of [AnchorView.anchorCharge] so a caller that already holds the state - the search reads it exactly
 * once per surviving cell - does not pay a second `getBlockState`.
 */
fun chargeOf(state: BlockState): Int? =
    if (state.block === Blocks.RESPAWN_ANCHOR) state.getValue(RespawnAnchorBlock.CHARGE) else null

/**
 * One tracked victim of an [AnchorView]: the live [entity], the frozen [VictimState] snapshot the damage is
 * evaluated against, and the [source] the exposure rays of that victim read.
 *
 * ONE snapshot, where the crystal aura's `TargetEntry` carries two (`placeState` / `breakState`). The crystal has
 * two moments that matter, the placement and the attack; an anchor has exactly one, the detonation, because the
 * place and charge clicks change nothing about the victim - they only put the block and its charge in place. The
 * honest limitation of that is stated at `AnchorSettings.predict`: a cycle entered at PLACE detonates up to three
 * ticks after this snapshot was taken, and the prediction horizon does not lengthen with the phase.
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
    val state: VictimState,
    val source: ShapeSource,
)

/**
 * Everything one anchor tick looks at, captured on the main thread by [capture]: the local player as a victim
 * ([self], [selfSource]), the tracked [targets] and the level [difficulty]; [eye] is `player.getEyePosition()`
 * (`net/minecraft/world/entity/Entity.java:1893`), the origin of every range test, [tick] the client tick the view
 * belongs to.
 *
 * The victim snapshots are frozen for the tick. The block and entity queries ([state], [anchorCharge],
 * [isReplaceable], [isUnobstructed]) deliberately read the live client level instead, so that an interaction
 * executed earlier in the same tick is observed by the next query. Main thread only.
 *
 * ### What is NOT here, and why
 * The crystal aura's view also carries `crystals`, `isPlaceable` and `isColumnFree`. All three are consequences of
 * `EndCrystalItem.useOn`: a crystal needs an obsidian or bedrock pedestal, an air block above it and an
 * entity-free 1x2x1 column. A respawn anchor has none of those rules. It is a plain `BlockItem` (`Items.java:2193`)
 * with no `canSurvive` override, so its only placement requirements are the ones every block item has - the
 * destination is replaceable and unobstructed by entities - which is exactly what [isReplaceable] and
 * [isUnobstructed] test.
 *
 * ### What the client knows about remote victims, and how it gets there
 * - Armor / toughness: `Attributes.ARMOR` and `ARMOR_TOUGHNESS` are `setSyncable(true)`
 *   (`net/minecraft/world/entity/ai/attributes/Attributes.java:10-13`); `ClientboundUpdateAttributesPacket` ->
 *   `ClientPacketListener.handleUpdateAttributes` (`net/minecraft/client/multiplayer/ClientPacketListener.java:
 *   2339-2361`) installs base value and transient modifiers, so `getArmorValue()`
 *   (`net/minecraft/world/entity/LivingEntity.java:1847-1849`) and `getAttributeValue(ARMOR_TOUGHNESS)`
 *   (`:2169-2171`) return the server's values.
 * - Health: synced entity data `DATA_HEALTH_ID` (`LivingEntity.java:182, 1149-1151`); [getActualHealth] prefers a
 *   below-name health objective when the server publishes one.
 * - Absorption: players sync it through `DATA_PLAYER_ABSORPTION_ID`
 *   (`net/minecraft/world/entity/player/Player.java:135, 1683`); every other living entity keeps a plain field
 *   (`LivingEntity.java:254, 3325-3327`) that is never sent, so a remote mob reports 0.
 * - Resistance: NOT knowable for a remote victim. `ClientboundUpdateMobEffectPacket` ->
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
 *   authoritative for the local player (and for a mount we ride) and always `null` for everybody else - an
 *   over-estimate of 1.25x per level, up to "full damage predicted against a victim that takes zero" at
 *   Resistance V. [capture]'s `assumeResistance` fills that hole; see [VictimCapture.resistanceOf].
 * - Armour and protection: both are read off the victim's equipment, which vanilla DOES sync
 *   (`ClientboundSetEquipmentPacket`), so on a vanilla server both are exact. A server may nonetheless strip the
 *   enchantment components from that packet - the common "enchantment obfuscation" - and then
 *   [EnchantProtection.protectionPoints] reads every opponent as unenchanted while their armour points still look
 *   right, which over-predicts the damage by up to the Protection cap. Such a server usually leaves a DECOY
 *   enchantment on the stack so the item still glints, so the visible list is false rather than empty. A server
 *   that hides the equipment outright also zeroes `getArmorValue()`. [capture]'s `assumeArmor` fills both holes by
 *   substituting our own readings; see [ArmorAssumption] and [VictimCapture.protectionOf].
 * - Blocking: `isUsingItem()` is bit 1 of the synced `DATA_LIVING_ENTITY_FLAGS` (`LivingEntity.java:3349-3351`).
 *   `onSyncedDataUpdated` (`:3447-3463`) sets `useItem` from the used hand and `useItemRemaining =
 *   useItem.getUseDuration(this)` when the flag appears; `updatingUsingItem` (`:3357-3365`) -> `updateUsingItem`
 *   (`:3405`) counts `useItemRemaining` down every client tick. `getItemBlockingWith()` (`:3566-3579`: component
 *   present and `elapsedTicks >= blockDelayTicks()`) hence works for remote entities, with the remote count
 *   starting one-way latency after the server's (the block delay is judged elapsed slightly late).
 * - I-frames: `ClientboundDamageEventPacket` -> `ClientPacketListener.handleDamageEvent`
 *   (`ClientPacketListener.java:1231-1237`) -> `LivingEntity.handleDamageEvent` (`LivingEntity.java:2017-2021`)
 *   sets `invulnerableTime = 20`; `LivingEntity.baseTick` decrements it every tick for everything but a
 *   `ServerPlayer` (`:465-466`). The amount is never sent - `lastHurt` comes from the ledger estimate [capture]
 *   is handed.
 * - Game mode: `PlayerInfo.getGameMode()` (`net/minecraft/client/multiplayer/PlayerInfo.java:74-76`), updated by
 *   `ClientboundPlayerInfoUpdatePacket` `UPDATE_GAME_MODE` (`ClientPacketListener.java:2027-2032`); this is also
 *   what `Player.isCreative()` / `isSpectator()` read on the client (`Player.java:1636-1652` through
 *   `AbstractClientPlayer.gameMode()`, `net/minecraft/client/player/AbstractClientPlayer.java:35-38`).
 * - `Entity.invulnerable` (`Entity.java:287`) is NBT only (`:1991, 2069`) and never synced: `isInvulnerable()` is
 *   false for every remote entity. It is still consulted, for exactness.
 */
class AnchorView(
    val tick: Int,
    val eye: Vec3,
    val self: VictimState,
    val selfSource: ShapeSource,
    val targets: List<TargetEntry>,
    val difficulty: Difficulty,
    private val placementContext: CollisionContext,
) {

    /** `level.getBlockState(pos)` of the live client level (`net/minecraft/world/level/Level.java:364`). */
    fun state(pos: BlockPos): BlockState = world.getBlockState(pos)

    /**
     * Charge of the anchor standing at [pos], `null` when the cell holds anything else; see [chargeOf].
     *
     * This is the whole ledger the charge needs. The charge IS predicted client-side - `useItemOn` runs inside
     * `startPrediction` and its `setBlock` goes through `ClientLevel`'s prediction path
     * (`net/minecraft/client/multiplayer/ClientLevel.java:208-221`) - so the blockstate is already the value the
     * server will have, and nothing has to be remembered between ticks. The DETONATION is the step that is not
     * predicted (`RespawnAnchorBlock.java:120-122` returns `CONSUME` on the client and `ClientLevel.explode` has
     * an empty body, `ClientLevel.java:1045-1060`), which is why that one needs a ledger and this does not.
     */
    fun anchorCharge(pos: BlockPos): Int? = chargeOf(state(pos))

    /** [isReplaceable] for a cell whose state has not been read yet. */
    fun isReplaceable(pos: BlockPos): Boolean = isReplaceable(state(pos), pos)

    /**
     * `BlockPlaceContext.canPlace()` for a respawn anchor at [pos]: `state.canBeReplaced(context)`
     * (`net/minecraft/world/item/context/BlockPlaceContext.java:36, 59-61` ->
     * `BlockBehaviour.canBeReplaced(state, context)`,
     * `net/minecraft/world/level/block/state/BlockBehaviour.java:270-272`, which is
     * `state.canBeReplaced() && !itemInHand.is(block.asItem())` plus block overrides) through the client helper
     * `canBeReplacedWith` (`utils/block/BlockExtensions.kt:464-479`).
     *
     * Air is answered without building the `BlockPlaceContext` that helper allocates: air is `replaceable()`
     * (`net/minecraft/world/level/block/Blocks.java:58`) and is never the respawn anchor item, so the context
     * cannot change the answer. That short-circuit is worth spelling out here because the anchor's candidate set
     * is "every replaceable cell in reach" - thousands of cells, most of them air - where the crystal aura's was
     * "the handful of obsidian cells".
     */
    fun isReplaceable(state: BlockState, pos: BlockPos): Boolean =
        state.isAir || state.canBeReplacedWith(pos, ANCHOR_STACK)

    /**
     * `CollisionGetter.isUnobstructed(state, pos, context)` (`net/minecraft/world/level/CollisionGetter.java:31-34`)
     * for a respawn anchor at [pos], the second half of `BlockItem.canPlace`
     * (`net/minecraft/world/item/BlockItem.java:132-136`).
     *
     * `shape = state.getCollisionShape(level, pos, context)` (`BlockBehaviour.java:678`), empty -> free, else
     * `EntityGetter.isUnobstructed(null, shape.move(pos))` (`net/minecraft/world/level/EntityGetter.java:33-48`):
     * over `getEntities(null, shape.bounds())` - the two argument overload applies `EntitySelector.NO_SPECTATORS`
     * (`EntityGetter.java:29-31`, `EntitySelector.java:15`) - an entity obstructs when `!isRemoved() &&
     * blocksBuilding` (`Entity.java:218, 3851`; the `isPassengerOfSameVehicle` term is void for a null source)
     * `&& Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND)`
     * (`Shapes.java:88-90, 156-176`; `BooleanOp.java:12`).
     *
     * No entity filter, which is the one deviation from the crystal aura's copy of this method. Its filter existed
     * because a crystal we already attacked is gone server-side but still standing client-side, and it needed an
     * explicit exception so that a crystal never let the obsidian through. Neither half applies to an anchor: it
     * is a block, so the module never owns an entity that could stand in its own way.
     */
    fun isUnobstructed(pos: BlockPos): Boolean {
        val shape = ANCHOR_STATE.getCollisionShape(world, pos, placementContext)
        if (shape.isEmpty) {
            return true
        }

        val moved = shape.move(pos)
        for (entity in world.getEntities(null, moved.bounds())) {
            if (obstructs(entity, moved)) return false
        }
        return true
    }

    /** The per-entity predicate of `EntityGetter.isUnobstructed` (`EntityGetter.java:38-46`). */
    private fun obstructs(entity: Entity, shape: VoxelShape): Boolean {
        if (entity.isRemoved || !entity.blocksBuilding) {
            return false
        }
        return Shapes.joinIsNotEmpty(shape, Shapes.create(entity.boundingBox), BooleanOp.AND)
    }

    companion object : MinecraftShortcuts {

        /** `new ItemStack(Items.RESPAWN_ANCHOR)` (`ItemStack.java:245`, `Items.java:2193`): the item in hand. */
        val ANCHOR_STACK: ItemStack by lazy { ItemStack(Items.RESPAWN_ANCHOR) }

        /**
         * The placement state of a respawn anchor: `Block.getStateForPlacement` = `defaultBlockState()`
         * (`net/minecraft/world/level/block/Block.java:457-459`), i.e. `CHARGE = 0` - `RespawnAnchorBlock`
         * overrides neither `getStateForPlacement` nor `canSurvive`.
         */
        val ANCHOR_STATE: BlockState by lazy { Blocks.RESPAWN_ANCHOR.defaultBlockState() }

        /**
         * Captures the world for client tick [tick] (main thread, in game).
         *
         * - `eye = player.getEyePosition()`; `difficulty = level.getDifficulty()`
         *   (`net/minecraft/world/level/LevelAccessor.java:48-50` -> `ClientLevel.ClientLevelData.getDifficulty()`,
         *   `net/minecraft/client/multiplayer/ClientLevel.java:1123-1125`).
         * - [targets] become [TargetEntry]s: the snapshot sits [predictTicks] ahead (`0` = current position),
         *   `invulnerableTime` compensated by [latencyTicks] and `lastHurt` from [lastHurt] (see [VictimCapture]).
         * - `selfSource`: `LevelShapeSource(level, CollisionContext.of(player))`, the local player's own context
         *   exactly like each target's (see [TargetEntry.source]).
         * - `placementContext`: `CollisionContext.placementContext(player)`
         *   (`net/minecraft/world/phys/shapes/CollisionContext.java:37-42`), the context
         *   `BlockItem.canPlace` hands to `isUnobstructed`; a per-tick snapshot, like everything else here.
         *
         * [lastHurt] is the ledger's `LivingEntity.lastHurt` estimate by entity id; the wave that owns the ledger
         * passes its lookup, and `{ null }` means "unknown", which the pipeline treats as 0.
         *
         * [assumeResistance] is an AMPLIFIER (`-1` = assume no effect, `0` = Resistance I, ...), used only as a
         * fallback for victims whose real Resistance the client cannot see - see the class KDoc and
         * [VictimCapture.resistanceOf]. Callers translate a 1-based level setting with `level - 1`.
         */
        fun capture(
            tick: Int,
            targets: List<LivingEntity>,
            lastHurt: (Int) -> Float?,
            predictTicks: Int,
            latencyTicks: Int,
            assumeResistance: Int,
            assumeArmor: ArmorAssumption,
        ): AnchorView {
            val level = world
            val victims = VictimCapture(
                level = level,
                lastHurt = lastHurt,
                predictTicks = predictTicks,
                latencyTicks = latencyTicks,
                assumeResistance = assumeResistance,
                assumeArmor = assumeArmor,
            )
            return AnchorView(
                tick = tick,
                eye = player.eyePosition,
                self = victims.self(),
                selfSource = LevelShapeSource(level, CollisionContext.of(player)),
                targets = targets.map(victims::target),
                difficulty = victims.difficulty,
                placementContext = CollisionContext.placementContext(player),
            )
        }
    }
}

/**
 * The per-call constants and victim snapshot helpers of one [AnchorView.capture]. Builds [VictimState]s from live
 * entities; every field is documented at [VictimState], this class documents where the value is read from.
 *
 * ### Skip conditions
 * `Player.hurtServer` (`Player.java:676-705`) refuses damage when `isInvulnerableTo(level, source)` (for a living
 * entity `isInvulnerableToBase(source) || EnchantmentHelper.isImmuneToDamage(...)`, `LivingEntity.java:3934-3936`;
 * `isInvulnerableToBase` = removed, or `invulnerable && !BYPASSES_INVULNERABILITY && !source.isCreativePlayer()`,
 * `Entity.java:2940-2945`), when `abilities.invulnerable && !BYPASSES_INVULNERABILITY` (`:680`) or when
 * `isDeadOrDying()` (`:684`, `getHealth() <= 0.0F`, `LivingEntity.java:1157-1159`). `bad_respawn_point` is in none
 * of the bypass tags (the audit that made this whole pipeline reusable), `abilities.invulnerable` is set by
 * `GameType.updatePlayerAbilities` for CREATIVE and SPECTATOR (`net/minecraft/world/level/GameType.java:62-77`)
 * and `source.isCreativePlayer()` is "the causing player has `abilities.instabuild`"
 * (`net/minecraft/world/damagesource/DamageSource.java:105-107`).
 *
 * That last one has a wrinkle worth stating, because it differs from the crystal aura: an anchor blast is
 * `new DamageSource(type, boomPos)` with NO causing entity (`DamageSources.java:278-280`), so `isCreativePlayer()`
 * is false even when WE are in creative and the `invulnerable` flag therefore always protects. The check below is
 * kept as the crystal aura wrote it - `!player.abilities.instabuild` - because it can only ever be MORE permissive
 * than vanilla for an anchor, and the flag is never synced for a remote entity anyway.
 *
 * Remote players expose no abilities, so their game mode is read from `PlayerInfo` ([skipOf]); the local player's
 * abilities arrive through `ClientboundPlayerAbilitiesPacket` -> `ClientPacketListener.handlePlayerAbilities`
 * (`ClientPacketListener.java:2082-2088`) ([selfSkip]). `EnchantmentHelper.isImmuneToDamage` (data-driven immunity
 * effects) is not mirrored - no vanilla enchantment grants immunity to explosions.
 *
 * ### I-frame latency
 * A target's `invulnerableTime` was set to 20 when its damage event reached us, one-way latency after the server
 * hit, and the explosion we trigger lands one-way latency after we act; when it lands the server's counter is
 * therefore about `reading - RTT`, hence `max(invulnerableTime - latencyTicks, 0)`. An under-compensation keeps
 * the target "inside the window" (only the delta above `lastHurt` is predicted), i.e. errs towards less damage.
 * The local player's counter is passed uncompensated: the server sends the local player its own damage events
 * (`ServerLevel.broadcastDamageEvent`, `net/minecraft/server/level/ServerLevel.java:1189-1191` ->
 * `TrackedEntity.sendToTrackingPlayersAndSelf`, `net/minecraft/server/level/ChunkMap.java:1347-1351`), so the
 * field is the client's own copy of its own state kept by the same code path, and with `lastHurt = null` (unknown,
 * treated as 0 by the pipeline) the window can never lower the self estimate (`damage - 0`): self damage is never
 * under-estimated whatever the counter says.
 *
 * ### Predicted feet
 * `ticks <= 0` -> `entity.position()` (`Entity.java:3632`), otherwise
 * [PositionExtrapolation.getBestForEntity]`(entity).getPositionInTicks(ticks)` (player simulation for players,
 * linear `position + ticks * (position - lastPos)` otherwise); `box = entity.getBoundingBoxAt(feet)` =
 * `boundingBox.move(feet - position())`.
 */
private class VictimCapture(
    private val level: ClientLevel,
    private val lastHurt: (Int) -> Float?,
    private val predictTicks: Int,
    private val latencyTicks: Int,
    private val assumeResistance: Int,
    private val assumeArmor: ArmorAssumption,
) : MinecraftShortcuts {

    /** `DamageTypes.BAD_RESPAWN_POINT` of this level's registry, see [EnchantProtection.badRespawnPointType]. */
    private val damageType: Holder<DamageType> = EnchantProtection.badRespawnPointType(level)

    /** `level.getDifficulty()` (`LevelAccessor.java:48-50`, `ClientLevel.java:1123-1125`). */
    val difficulty: Difficulty = level.difficulty

    /**
     * The local player at its current position; `invulnerableTime` uncompensated and `lastHurt = null` (class
     * KDoc).
     */
    fun self(): VictimState = victimOf(player, player.position(), selfSkip(), player.invulnerableTime, null)

    /**
     * [TargetEntry] for [entity]: the snapshot at [predictTicks], `invulnerableTime =
     * max(entity.invulnerableTime - latencyTicks, 0)` (`Entity.invulnerableTime`, `Entity.java:261`), `lastHurt`
     * from the ledger lookup.
     */
    fun target(entity: LivingEntity): TargetEntry {
        val feet = predictedFeet(entity, predictTicks)
        val state = victimOf(
            entity,
            feet,
            skipOf(entity),
            maxOf(entity.invulnerableTime - latencyTicks, 0),
            lastHurt(entity.id),
        )
        return TargetEntry(entity, state, LevelShapeSource(level, CollisionContext.of(entity)))
    }

    private fun predictedFeet(entity: LivingEntity, ticks: Int): Vec3 = if (ticks <= 0) {
        entity.position()
    } else {
        PositionExtrapolation.getBestForEntity(entity).getPositionInTicks(ticks.toDouble())
    }

    /**
     * `armor = getArmorValue()`, `toughness = (float) getAttributeValue(ARMOR_TOUGHNESS)`
     * (`LivingEntity.java:1880`), `resistanceAmplifier` = [resistanceOf], `protectionPoints` for
     * `bad_respawn_point` with `isDirect = true` (the [EnchantProtection.protectionPoints] default; the source has
     * neither a causing nor a direct entity, `DamageSources.java:278-280`, so `isDirect()` holds,
     * `DamageSource.java:32-34`), `absorption = getAbsorptionAmount()`, `blocking` = [blockingOf] at [feet].
     *
     * `health` = `getActualHealth(fromScoreboard = false)` = the synced `DATA_HEALTH_ID`
     * (`LivingEntity.java:182, 1149-1151`), i.e. exactly the `getHealth()` that `hurtServer` and `setHealth`
     * work on (`LivingEntity.java:1157-1159, 1940`). The default `fromScoreboard = true`
     * (`utils/entity/EntityExtensions.kt:898-909`) would prefer a BELOW_NAME objective score - an `Int` in
     * whatever scale the server picked (hearts, 0-100, 0-1000, ...) - which is unusable as the right-hand side of
     * `DamageResult.isLethalFor` and of the lethal search bound: a 0-100 scale disables the lethal branch
     * outright, a hearts scale claims kills that are not there.
     */
    private fun victimOf(
        entity: LivingEntity,
        feet: Vec3,
        skip: Boolean,
        invulnerableTime: Int,
        lastHurtEstimate: Float?,
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
        lastHurt = lastHurtEstimate,
        difficulty = difficulty,
    )

    /**
     * `getEffect(RESISTANCE)?.getAmplifier() ?: -1` (`LivingEntity.java:986-988`, `MobEffectInstance.java:208`),
     * with the `assumeResistance` fallback of [AnchorView.capture] mixed in.
     *
     * The client only ever receives `ClientboundUpdateMobEffectPacket` for itself and for a mount it rides (see
     * the [AnchorView] class KDoc), so the reading is authoritative for the local player and always `null` for
     * everybody else. For any non-local victim the assumed amplifier therefore stands in when nothing is visible;
     * `maxOf` makes sure it can never lower an effect we actually see (the ridden-mount case, and the local
     * player, which is exempted outright).
     */
    private fun resistanceOf(entity: LivingEntity): Int {
        val actual = entity.getEffect(MobEffects.RESISTANCE)?.amplifier ?: -1
        return if (entity === player) actual else maxOf(actual, assumeResistance)
    }

    /**
     * `getArmorValue()` (`LivingEntity.java:1880` reads it for `getDamageAfterArmorAbsorb`), floored at ours when
     * [ArmorAssumption.FULL] hides the victim's equipment. See [ArmorAssumption] for why the two channels are
     * separate settings.
     */
    private fun armorOf(entity: LivingEntity): Int {
        if (entity === player || assumeArmor != ArmorAssumption.FULL) {
            return entity.armorValue
        }
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
     * [EnchantProtection.protectionPoints] for the anchor damage type, SUBSTITUTED by ours for a remote victim
     * unless [ArmorAssumption.OFF].
     *
     * This is the channel that breaks on an obfuscating server: the points are computed from the enchantment
     * components of the victim's equipment stacks, so a server that rewrites them controls this number entirely.
     *
     * Substituted rather than maxed, which is the difference to [resistanceOf]. A hidden effect is genuinely
     * absent from the client's view, so assuming a minimum for it can only ever correct an over-estimate. A
     * rewritten enchantment list is not a missing reading but a false one: the usual obfuscation leaves a decoy
     * enchantment on the stack so the item still glints, and that decoy may well be a Protection level nobody has.
     * Treating it as a lower bound would let a fake Protection IV survive `max` and suppress the module outright,
     * so once this is on, the victim's own enchantments are not evidence and are dropped.
     */
    private fun protectionOf(entity: LivingEntity): Float {
        if (entity === player || assumeArmor == ArmorAssumption.OFF) {
            return EnchantProtection.protectionPoints(entity, damageType)
        }
        return selfProtection
    }

    /** Our own protection points, resolved once per capture - [protectionOf] needs them for every victim. */
    private val selfProtection: Float by lazy { EnchantProtection.protectionPoints(player, damageType) }

    /**
     * `Player.hurtServer` guards for the local player (class KDoc): creative / spectator (`Player.java:1636-1652`),
     * `abilities.invulnerable` (`:680`, `Abilities.java:14`), the `invulnerable` flag (`Entity.java:2942`) and
     * `isDeadOrDying()` (`:684`).
     */
    private fun selfSkip(): Boolean =
        player.isCreative || player.isSpectator || player.abilities.invulnerable || player.isInvulnerable ||
            player.isDeadOrDying

    /**
     * Skip guards for a remote victim: `isDeadOrDying()`; the `invulnerable` flag unless we are the creative
     * attacker (`Entity.java:2942`, `DamageSource.java:105-107`, and see the class KDoc for why that exception is
     * unreachable for an anchor); for players a `PlayerInfo` game mode of CREATIVE or SPECTATOR
     * (`ClientPacketListener.getPlayerInfo(UUID)`, `ClientPacketListener.java:2575-2577`;
     * `PlayerInfo.getGameMode()`, `PlayerInfo.java:74-76`) - the client-side stand-in for
     * `abilities.invulnerable`, which `GameType.updatePlayerAbilities` sets for exactly those two modes
     * (`GameType.java:62-77`). An unknown player (no `PlayerInfo`) is not skipped.
     */
    private fun skipOf(entity: LivingEntity): Boolean {
        if (entity.isDeadOrDying || (entity.isInvulnerable && !player.abilities.instabuild)) {
            return true
        }
        if (entity !is Player) {
            return false
        }
        val gameMode = network.getPlayerInfo(entity.uuid)?.gameMode
        return gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR
    }

    /**
     * Snapshot of the `applyItemBlocking` inputs (`LivingEntity.java:1285-1322`), null when `getItemBlockingWith()`
     * is null (`:3566-3579`: not using an item, no `BLOCKS_ATTACKS` component, or block delay not elapsed).
     * `BlocksAttacks` (`net/minecraft/world/item/component/BlocksAttacks.java:28-36`) carries
     * `List<DamageReduction> damageReductions()` and `Optional<HolderSet<DamageType>> bypassedBy()`;
     * `DamageReduction(float horizontalBlockingAngle, Optional<HolderSet<DamageType>> type, float base,
     * float factor)` (`:124`). `bypassed` = `bypassedBy().map(t -> t.contains(source.typeHolder())).orElse(false)`
     * (`:1294`), `typeMatches` pre-resolves `!(type.isPresent() && !type.get().contains(source.typeHolder()))` of
     * `DamageReduction.resolve` (`:150`), `viewVector = calculateViewVector(0.0F, getYHeadRot())` (`:1301`;
     * `Entity.calculateViewVector` is public final, `Entity.java:1875-1883`; `getYHeadRot()` returns the public
     * field `yHeadRot`, `LivingEntity.java:229, 3302-3304`), `position = position()` (`:1302`).
     * `HolderSet.contains(Holder)`: `net/minecraft/core/HolderSet.java:31`.
     *
     * The shield applies at all because `bad_respawn_point` is NOT in `#bypasses_shield`, and the arc is measured
     * from the blast centre because `badRespawnPointExplosion(boomPos)` stores that centre as the source position
     * (`DamageSources.java:278-280`, read back by `DamageSource.getSourcePosition()`, `DamageSource.java:109-116`).
     *
     * `position` is [feet], the position of the [VictimState] being built, NOT the live `entity.position()`:
     * vanilla evaluates the shield arc and the blast distance against the same `this.position()` at explosion time
     * (`LivingEntity.java:1302` vs `ServerExplosion.java:182`), so a predicted state has to carry the predicted
     * position in both places or a victim predicted to run past the anchor is scored with the arc it has right
     * now.
     *
     * `yHeadRot` stays the client's interpolated current value - there is no way to predict where the victim will
     * be looking, so it is an unavoidable prediction error of the arc (bounded: the arc only matters within
     * `horizontalBlockingAngle`, 90 degrees for a vanilla shield).
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
