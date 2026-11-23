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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.exec

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.BasePlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.BreakSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.PlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.RayTests
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base.BasePlan
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.BreakSearch
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.features.module.modules.world.speedmine.ModuleSpeedMine
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.canBeReplacedWith
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.entity.StrictInteractionResult
import net.ccbluex.liquidbounce.utils.entity.StrictInteractionSource
import net.ccbluex.liquidbounce.utils.entity.interactBlock
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** What [ActionExecutor.execute] did; [reason] names every rejected action (null when nothing was rejected). */
data class ExecReport(val attacked: Boolean, val placed: Boolean, val basePlaced: Boolean, val reason: String?)

/**
 * Performs a [TickPlan] at the vanilla click position: the RETURN of `Minecraft.handleKeybinds` (`InputHandleEvent`,
 * `MixinMinecraft.hookHandleInputEvent`), i.e. after `gameMode.tick()` has sent the carried-item change and before
 * `LocalPlayer.tick` sends the movement packet (`Minecraft.java:1823-1927`). Attack first, then the use - the order
 * of `handleKeybinds` (`Minecraft.java:2050-2056`). Every step re-verifies its preconditions against the live world
 * and against `AuraRotation.outgoing` and rejects with a reason instead of acting.
 *
 * ### The rotation every ray is traced against
 * `AuraRotation.outgoing` is the rotation THIS tick's movement packet will carry: `RotationManager.update()` ran at
 * the head of the tick, right after the `RotationUpdateEvent` the aura plans in (RotationManager.kt:319-324), so by
 * the time `InputHandleEvent` fires the value is final and normalized. A rotation the plan requested a few hundred
 * microseconds ago therefore already covers the click we are about to send - which is exactly the vanilla ordering
 * (`MouseHandler.turnPlayer` runs in the render section, `client/Minecraft.java:1321`, AFTER the tick loop at
 * `:1301`, so `pick(1.0F)` at `:1823`, the clicks at `:1865` and `sendPosition` all use one rotation) and the one
 * Grim evaluates (queued places and breaks drained at `CheckManagerListener.java:680-681` with the NEW rotation;
 * `scaffolding/RotationPlace.java:52`: "Use post flying because it has the correct rotation").
 *
 * This is where the plan's ATTEMPTS become actions: `TickPlanner` decides intent, the ray tests below decide the
 * hit. They are exact - no tolerance is widened to make an attempt land, because a single post-flying miss arms
 * `RotationPlace`'s `flagBuffer` and the pre-flying path it enables CANCELS placements for the next 10 interactions.
 * A miss simply sends nothing; the next tick retries with the rotation by then on the wire.
 *
 * ### Foreign-click gates (`allowAttack` / `allowUse`)
 * [execute] attacks only when `allowAttack` is set and uses only when `allowUse` is; the hub clears them for the tick
 * once a foreign use / dig packet has already gone out. `InputHandleEvent` fires at the RETURN of `handleKeybinds`,
 * i.e. AFTER vanilla's own attack loop (`Minecraft.java:2050-2052`), its use loops (`:2054-2056`, `:2069-2071`) and
 * `continueAttack` (`:2073`), so appending our attack behind the player's own use would put an attack after a use
 * inside one tick - a packet order vanilla never produces. A blocked action sends nothing and is reported as
 * `attack blocked this tick` / `use blocked this tick`.
 *
 * ### Gates copied from vanilla
 * - Both actions: `player.isAlive && !player.isSpectator` (a spectator attack turns into `gameMode.spectate`,
 *   `Minecraft.java:1685-1690`; a spectator use is `CONSUME`d client-side, `MultiPlayerGameMode.java:336-337`);
 *   `player.isUsingItem` - `handleKeybinds` swallows every click while an item is in use (`Minecraft.java:2036-2047`);
 *   `player.isHandsBusy` (`startAttack`, `:1683`; `startUseItem`, `:1740`).
 * - Attack: `missTime > 0` (`:1674`; `Minecraft.missTime` is a public field, `:378`); the main-hand item must be
 *   enabled (`:1693`), `!player.cannotAttackWithItem(item, 0)` (`:1695`, `MINIMUM_ATTACK_CHARGE`) and must not carry
 *   `DataComponents.PIERCING_WEAPON` (`:1699-1702`, `attackEntity` would divert into `piercingAttack`); a
 *   `DataComponents.ATTACK_RANGE` component in the main hand must accept the hit location ([attackRangeGate],
 *   `:1707-1710`).
 * - Use: `!gameMode.isDestroying()` (`:1738`); the item of the hand must be enabled (`:1747`).
 *
 * ### Attack
 * `attackEntity(crystal, DO_NOT_HIDE)` (`utils/combat/CombatExtensions.kt:239-311`) = `MultiPlayerGameMode.attack`
 * (`MultiPlayerGameMode.java:421-426`: `ensureHasSentCarriedItem`, `ServerboundAttackPacket(id)`, `player.attack`,
 * `resetAttackStrengthTicker`) followed by `player.swing(MAIN_HAND)` (`Minecraft.java:1730` →
 * `LocalPlayer.swing`, `LocalPlayer.java:324-327`: `ServerboundSwingPacket`). Then the ledger learns the attack with
 * `BreakSearch.predictedLastHurt` and every other crystal the explosion reaches is marked expected-removed:
 * `ServerExplosion.hurtEntities` hurts each entity with `Math.sqrt(distanceToSqr(center)) / 12.0 <= 1.0`
 * (`ServerExplosion.java:181-190`) and a hurt crystal is removed without exploding when the source is an explosion
 * (`EndCrystal.java:95-99`).
 *
 * IdPredict phantom (no entity client-side): the same packets as `MultiPlayerGameMode.attack` minus the client-side
 * `player.attack(entity)` prediction - `ensureHasSentCarriedItem()`, `ServerboundAttackPacket(id)`, the sprint side
 * effect of `player.attack` ([phantomSprintReset]), `resetAttackStrengthTicker()`, swing. `AttackEntityEvent` is not
 * fired (it needs an entity).
 *
 * A predicted id is a GUESS, and a wrong guess is not a miss but a kick: `ServerGamePacketListenerImpl.handleAttack`
 * disconnects with `multiplayer.disconnect.invalid_entity_attacked` as soon as the addressed entity is an
 * `ItemEntity`, an `ExperienceOrb`, the player itself or a non-attackable `AbstractArrow`
 * (`ServerGamePacketListenerImpl.java:1770-1775`) - the packet passes the reach and `PIERCING_WEAPON` tests first
 * (`:1768-1769`), so nothing earlier saves us. Three layers keep that unlikely: `CrystalLedger.predictNextId`
 * (`state/CrystalLedger.kt:208-214`) answers only while the last three observed spawns had consecutive ids,
 * `TickPlanner.phantomAttack` (`exec/TickPlanner.kt:268-277`) only fires while the pending placement is at most one
 * tick old, and [attackPhantom] refuses once the id exists client-side. The setting stays opt-in and default OFF.
 *
 * ### Use
 * Crystal in the main hand: `interactBlock(hit, MAIN_HAND, DO_NOT_HIDE)` (`utils/entity/InteractionUtils.kt:196-214`)
 * = the BLOCK branch of `startUseItem` (`Minecraft.java:1768-1783`): `gameMode.useItemOn` sends
 * `ServerboundUseItemOnPacket` through `startPrediction` whatever the client-side result is
 * (`MultiPlayerGameMode.java:319-331`, `:289-295`), the swing follows only a client `Success`.
 * Crystal in the offhand: [useOnLikeVanilla] replays the hand loop of `startUseItem` (`:1743-1800`) - main hand
 * first: `useItemOn`, then `useItem` for a NON-empty stack that did not consume (`:1790`), then the offhand - so the
 * wire carries what a real client sends (`[UIO main][UIO off]`, or `[UIO main][UI main][UIO off]` with an item in
 * the main hand). Allowed only when the main-hand item cannot consume the click ([mainHandConsumesUse]).
 * `interactBlockLikeVanilla` is deliberately not used: it calls `useItem` for an EMPTY main hand too, which vanilla
 * never does (`:1790`). The `ServerboundUseItemPacket` carries the OUTGOING rotation - the server snaps the player
 * to it (`ServerGamePacketListenerImpl.java:1382-1386`), and that is the rotation this tick's movement packet puts
 * there anyway, so the two never disagree.
 *
 * The hit put on the wire is the one traced HERE against the outgoing rotation, not the planned one.
 *
 * ### The manual swing (design decision)
 * A crystal we attacked this tick (or expect to be chain-removed) still exists client-side; the client-side
 * `EndCrystalItem.useOn` then returns `FAIL` because of it (`EndCrystalItem.java:34-39`) and vanilla would not
 * swing. The `ServerboundUseItemOnPacket` was sent anyway (see above), and the server - which processes the attack
 * packet first and has already removed that crystal - places the crystal. So when the ONLY obstruction is such a
 * crystal the hand THE RESULT CAME FROM is swung manually: the packet sequence equals that of a legit client whose
 * removal packet had already arrived. Any other client-side `FAIL` is reported and not swung ([crystalOutcome]).
 *
 * Base placement: `interactBlock` with the obsidian; a client `FAIL` (`BlockItem.place`) is reported, never swung.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ActionExecutor(
    private val place: PlaceSettings,
    private val brk: BreakSettings,
    private val base: BasePlaceSettings,
) : MinecraftShortcuts {

    private val placeRange: Double
        get() = place.range.toDouble()

    private val breakRange: Double
        get() = brk.range.toDouble()

    private val baseRange: Double
        get() = base.range.toDouble()

    /**
     * Runs the executable part of [plan] (attack first, then the use) and reports what happened. [view] is the view
     * the plan was made from (its block / entity queries read the live level), [rayIgnore] the LookThrough filter.
     * [allowAttack] / [allowUse] are the hub's foreign-click gates (class KDoc): a cleared gate skips its action -
     * the real AND the phantom attack for [allowAttack] - and reports it instead of sending anything.
     *
     * An attempt whose exact ray reaches neither rotation of [byAim] is reported as `attack ray misses` / `use ray
     * misses` and nothing is sent; the hub leaves its delay chronometer untouched and the next tick tries again.
     */
    fun execute(
        plan: TickPlan,
        view: WorldView,
        ledger: CrystalLedger,
        breakSearch: BreakSearch,
        rayIgnore: (Entity) -> Boolean,
        allowAttack: Boolean,
        allowUse: Boolean,
    ): ExecReport {
        commonGate()?.let { return ExecReport(attacked = false, placed = false, basePlaced = false, reason = it) }

        val aim = AuraRotation.outgoing
        var attackReason: String? = null
        var attackedCrystal: EndCrystal? = null
        if (plan.attackAttempt) {
            attackReason = if (allowAttack) {
                attack(plan, view, ledger, breakSearch, aim, rayIgnore)
            } else {
                ATTACK_BLOCKED
            }
            if (attackReason == null) {
                attackedCrystal = plan.attack?.crystal
            }
        }
        val attacked = plan.attackAttempt && attackReason == null

        val useReason = when {
            !plan.useAttempt -> null
            !allowUse -> USE_BLOCKED
            else -> use(plan, view, ledger, aim, ignoreAlso(attackedCrystal, rayIgnore))
        }
        val used = plan.useAttempt && useReason == null
        return ExecReport(
            attacked = attacked,
            placed = used && plan.base == null,
            basePlaced = used && plan.base != null,
            reason = reason(attackReason, useReason),
        )
    }

    /**
     * Runs [test] against [aim] - the rotation this tick's movement packet will carry - and, when that misses,
     * against the rotation already on the wire.
     *
     * Both are EXACT rotations, and for an ATTACK or a BREAK both are raytrace candidates of the check that
     * decides: `Reach` casts `(yaw, pitch)`, `(lastYaw, pitch)` and `(lastYaw, lastPitch)`
     * (`combat/Reach.java:267-280`) and `RotationBreak` the same three for a 1.9+ client
     * (`breaking/RotationBreak.java:99-107`), and each passes as soon as ANY candidate reaches the target. So
     * nothing here is loosened: each attempt is the same exact ray test from a rotation the server is known to
     * accept, and an action that reaches from neither is still refused.
     *
     * A PLACEMENT does NOT share that candidate set and must go through [byPlaceAim] instead - see there.
     *
     * Why both rather than the outgoing one alone: that would drop an action whenever our rotation request lost to a
     * higher-priority module, whenever a `RotationMode.SMOOTH` step is still in flight, and - with `SameTick` - on
     * the tick where the turn towards the placement leaves the crystal the same tick's attack needs. All three are
     * cases the old wire-only verification performed, so accepting both keeps this a strict superset of it.
     */
    private inline fun <T : Any> byAim(aim: Rotation, test: (Rotation) -> T?): T? {
        test(aim)?.let { return it }
        val wire = AuraRotation.wire
        return if (wire == aim) null else test(wire)
    }

    /**
     * [byAim] for a PLACEMENT, whose check does NOT have the same candidate set as the attack and break ones.
     *
     * `RotationPlace.didRayTraceHit` casts from exactly two rotations - `(yaw, pitch)` and `(lastYaw, pitch)`
     * (`scaffolding/RotationPlace.java:83-90`) - and it has no `(lastYaw, lastPitch)` branch at all, where
     * `RotationBreak` (`:99-107`) and `Reach` (`:267-280`) both add one for a 1.9+ client. So the fallback for a
     * placement is the PREVIOUS YAW carrying THIS TICK'S PITCH, never the whole previous rotation: verifying a
     * placement against the latter verifies a ray the check never casts.
     *
     * Why the difference bites so hard. `RotationPlace` is the one check with an asymmetric buffer: a single
     * post-flying miss sets `flagBuffer = 1`, each clean placement pays back only `0.1`, and while it is above
     * zero the PRE-flying leg does not flag but CANCELS - `place.resync()` (`:36-49, 66-76`). One misjudged
     * placement therefore costs up to ten more, and they fail silently: the block simply never appears.
     *
     * With a `SMOOTH` rotation both yaw and pitch move every tick, so the whole-previous-rotation fallback fired
     * constantly and kept that buffer armed. Reported in game on 2026-09-14 as "BasePlace barely registers on one
     * server, about 1 in 4" - the one-in-ten recovery rate is exactly the shape of it.
     */
    private inline fun <T : Any> byPlaceAim(aim: Rotation, test: (Rotation) -> T?): T? {
        test(aim)?.let { return it }
        val lastYaw = AuraRotation.wire.yaw
        if (lastYaw == aim.yaw) return null
        // Never sent, only ray-tested and used to build the hit vector; both halves are already on the grid.
        return test(Rotation(lastYaw, aim.pitch, isNormalized = true))
    }

    /** Gates shared by both actions (see the class KDoc), null when open. */
    private fun commonGate(): String? = when {
        !player.isAlive || player.isSpectator -> "dead or spectator"
        player.isUsingItem -> "using an item"
        player.isHandsBusy -> "hands busy"
        else -> null
    }

    private fun attack(
        plan: TickPlan,
        view: WorldView,
        ledger: CrystalLedger,
        breakSearch: BreakSearch,
        aim: Rotation,
        rayIgnore: (Entity) -> Boolean,
    ): String? {
        attackGate()?.let { return it }

        val crystal = plan.attack?.crystal
        if (crystal != null) {
            return attackCrystal(crystal, view, ledger, breakSearch, aim, rayIgnore)
        }

        val phantom = plan.phantomAttack ?: return "nothing to attack"
        return attackPhantom(phantom, view, ledger, breakSearch, aim, rayIgnore)
    }

    /**
     * `Minecraft.startAttack` gates (`Minecraft.java:1674, 1693-1702`), null when open; see [attackRangeGate].
     *
     * Also read by the hub at PLAN, so that an attack this gate would refuse is never planned: with `SameTick` off a
     * planned attack takes the tick's use, and one that never leaves takes it every tick (`TickPlanner.decide`).
     * Both reads see the same state: `missTime` is decremented only AFTER `handleKeybinds` returns
     * (`Minecraft.java:1865-1868`), `InputHandleEvent` fires at that return, and the main hand changes between the
     * two only when the plan switches the slot for a use, i.e. only on a `SameTick` tick.
     */
    fun attackGate(): String? {
        if (mc.missTime > 0 && !brk.ignoreMissTime) return "miss time"

        val held = player.getItemInHand(InteractionHand.MAIN_HAND)
        return when {
            !held.isItemEnabled(world.enabledFeatures()) -> "main-hand item disabled"
            player.cannotAttackWithItem(held, 0) -> "attack charge too low"
            held.has(DataComponents.PIERCING_WEAPON) -> "piercing weapon in hand"
            else -> null
        }
    }

    /**
     * The ENTITY branch of `Minecraft.startAttack` (`Minecraft.java:1705-1711`): with a `DataComponents.ATTACK_RANGE`
     * component in the main hand vanilla calls `gameMode.attack` only while
     * `customItemRange.isInRange(player, hitResult.getLocation())` holds (`:1707-1710`) - otherwise it merely swings
     * (`:1730`) and no attack packet is sent. `AttackRange.isInRange(LivingEntity, Vec3)`
     * (`net/minecraft/world/item/component/AttackRange.java:104-106` → `:112-117`) accepts
     * `sqrt(location.distanceToSqr(attacker.getEyePosition()))` inside
     * `[effectiveMinRange - hitboxMargin, effectiveMaxRange + hitboxMargin]`, with the creative reaches used for a
     * creative player (`:88-102`); it is called instead of being re-implemented so the component owns the answer.
     *
     * In practice a dead gate: the vanilla spear family carries `ATTACK_RANGE` together with `PIERCING_WEAPON`, and
     * [attackGate] already refuses a piercing weapon (`:1699-1703`). It exists for datapack / mod items that carry
     * `ATTACK_RANGE` alone, where sending the attack would be a packet vanilla could not have produced.
     */
    private fun attackRangeGate(location: Vec3): String? {
        val range = player.getItemInHand(InteractionHand.MAIN_HAND).get(DataComponents.ATTACK_RANGE) ?: return null

        return if (range.isInRange(player, location)) null else "outside the item attack range"
    }

    private fun attackCrystal(
        crystal: EndCrystal,
        view: WorldView,
        ledger: CrystalLedger,
        breakSearch: BreakSearch,
        aim: Rotation,
        rayIgnore: (Entity) -> Boolean,
    ): String? {
        if (!crystal.isAlive || world.getEntity(crystal.id) !== crystal) return "crystal gone"
        if (ledger.isAttacked(crystal.id)) return "crystal already attacked"
        val ignoreOther = ignoreExcept(crystal, rayIgnore)
        val hit = byAim(aim) {
            RayTests.hitsCrystal(view.eye, it, crystal, breakRange, ignoreOther, place.throughBlocks)
        } ?: return "attack ray misses"
        attackRangeGate(hit.location)?.let { return it }

        attackEntity(crystal, SwingMode.DO_NOT_HIDE)
        bookAttack(crystal.id, crystal.position(), view, ledger, breakSearch)
        return null
    }

    /**
     * The IdPredict phantom attack (class KDoc, including the disconnect risk of a wrong id). The freshness of the
     * pending placement is decided by `TickPlanner.phantomAttack`; what is left here is the client-side proof that
     * the id is still unused, that the id was not attacked yet, that the outgoing rotation reaches the predicted box
     * and that the main-hand `ATTACK_RANGE` component (if any) accepts the point.
     */
    private fun attackPhantom(
        phantom: PhantomAttack,
        view: WorldView,
        ledger: CrystalLedger,
        breakSearch: BreakSearch,
        aim: Rotation,
        rayIgnore: (Entity) -> Boolean,
    ): String? {
        if (world.getEntity(phantom.id) != null) return "predicted id already spawned"
        if (ledger.isAttacked(phantom.id)) return "phantom already attacked"
        val point = byAim(aim) {
            RayTests.hitsPhantomBox(view.eye, it, phantom.box, breakRange, rayIgnore, place.throughBlocks)
        }
            ?: return "phantom ray misses"
        attackRangeGate(point)?.let { return it }

        sendPhantomAttack(phantom.id)
        bookAttack(phantom.id, phantom.center, view, ledger, breakSearch)
        return null
    }

    /**
     * `MultiPlayerGameMode.attack` (`MultiPlayerGameMode.java:421-426`) for an id with no client-side entity:
     * `ensureHasSentCarriedItem()`, `ServerboundAttackPacket(id)`, the reproducible part of `player.attack(entity)`
     * ([phantomSprintReset], kept in vanilla's slot BEFORE the ticker reset because the sprint condition reads the
     * attack strength), `resetAttackStrengthTicker()`, then the swing of `startAttack` (`Minecraft.java:1730`).
     */
    private fun sendPhantomAttack(id: Int) {
        interaction.ensureHasSentCarriedItem()
        network.send(ServerboundAttackPacket(id))
        phantomSprintReset()
        player.resetAttackStrengthTicker()
        SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
    }

    /**
     * The ONE side effect of `player.attack(entity)` that is observable on the wire and reproducible without an
     * entity. `Player.attack` (`net/minecraft/world/entity/player/Player.java:939-993`) computes
     * `fullStrengthAttack = getAttackStrengthScale(0.5F) > 0.9F` (`:944, :950`) and
     * `knockbackAttack = isSprinting() && fullStrengthAttack` (`:952-957`), then hands
     * `getKnockback(entity, damageSource) + (knockbackAttack ? 0.5F : 0.0F)` to `causeExtraKnockback`
     * (`:975`), which does `setSprinting(false)` for any `knockbackAmount > 0.0F` (`:1107-1119`).
     * `LivingEntity.getKnockback` (`LivingEntity.java:1512-1517`) is `getAttributeValue(ATTACK_KNOCKBACK) / 2.0F` on
     * a client level (the enchantment modifier needs a `ServerLevel`). `LocalPlayer.sendPosition` →
     * `sendIsSprintingIfNeeded` (`LocalPlayer.java:266-267, 305-314`) then emits
     * `ServerboundPlayerCommandPacket(STOP_SPRINTING)` from `LocalPlayer.tick`, i.e. still this tick and before the
     * move packet - which is why the flag is flipped instead of the packet being sent by hand.
     *
     * Deviations, both harmless: the outer `baseDamage > 0.0F || magicBoost > 0.0F` gate (`:949`) is assumed true
     * (`baseDamageScaleFactor()` is at least `0.2F`, `:1170-1173`, and a player's `ATTACK_DAMAGE` is positive), and
     * `wasHurt` (`:973`) is assumed true, which it is for a crystal: `EndCrystal.hurtClient` returns true unless the
     * source is an `EnderDragon` or the crystal is base-invulnerable
     * (`net/minecraft/world/entity/boss/enderdragon/EndCrystal.java:82-85`). Both only ever ADD a sprint stop.
     */
    private fun phantomSprintReset() {
        if (!player.isSprinting) return

        val knockbackAttack = player.getAttackStrengthScale(STRENGTH_PARTIAL) > FULL_STRENGTH_SCALE
        val bonus = if (knockbackAttack) SPRINT_KNOCKBACK_BONUS else 0.0f
        val knockback = player.getAttributeValue(Attributes.ATTACK_KNOCKBACK).toFloat() / KNOCKBACK_HALVING + bonus
        if (knockback > 0.0f) {
            player.isSprinting = false
        }
    }

    /**
     * Ledger bookkeeping of a sent attack: the lastHurt estimates, and every other crystal inside the explosion
     * radius (`!(dist > 1.0)`, `ServerExplosion.java:182-183`) as expected-removed.
     */
    private fun bookAttack(id: Int, center: Vec3, view: WorldView, ledger: CrystalLedger, breakSearch: BreakSearch) {
        ledger.markAttacked(id, breakSearch.predictedLastHurt(center))
        for (other in view.crystals) {
            if (other.id == id || ExplosionMath.distanceFraction(other.position(), center) > 1.0) continue
            ledger.markExpectedRemoved(other.id)
        }
    }

    private fun use(
        plan: TickPlan,
        view: WorldView,
        ledger: CrystalLedger,
        aim: Rotation,
        ignore: (Entity) -> Boolean,
    ): String? {
        if (interaction.isDestroying) return "destroying a block"
        val slot = plan.slot ?: return "no slot"

        val base = plan.base
        val place = plan.place
        val gate = slotGate(slot, if (base != null) Items.OBSIDIAN else Items.END_CRYSTAL)
        return when {
            gate != null -> gate
            base != null -> placeBase(base, view, aim, ignore)
            place != null -> placeCrystal(place, slot, view, ledger, aim, ignore)
            else -> "nothing to use"
        }
    }

    /** The slot still holds [expected], the server holds the slot, the item is enabled (`Minecraft.java:1747`). */
    private fun slotGate(slot: HotbarItemSlot, expected: Item): String? {
        val hand = slot.useHand
        val held = player.getItemInHand(hand)
        return when {
            !isHeld(slot) -> "slot not held server-side"
            held.item !== expected -> "item no longer in hand"
            !held.isItemEnabled(world.enabledFeatures()) -> "item disabled"
            hand == InteractionHand.OFF_HAND && mainHandConsumesUse(player.getItemInHand(InteractionHand.MAIN_HAND)) ->
                "main-hand item would consume the use"
            else -> null
        }
    }

    private fun placeCrystal(
        candidate: PlaceCandidate,
        slot: HotbarItemSlot,
        view: WorldView,
        ledger: CrystalLedger,
        aim: Rotation,
        ignore: (Entity) -> Boolean,
    ): String? {
        val pos = candidate.pos
        val above = pos.above()
        crystalGate(pos, above, view, ledger)?.let { return it }
        val hit = byPlaceAim(aim) { RayTests.hitsBlock(view.eye, it, pos, placeRange, ignore, place.throughBlocks) }
            ?: return "use ray misses"

        val outcome = if (slot.isOffHand) {
            useOnLikeVanilla(hit, aim)
        } else {
            StrictInteractionResult(
                hand = InteractionHand.MAIN_HAND,
                source = StrictInteractionSource.USE_ITEM_ON,
                result = interactBlock(hit, InteractionHand.MAIN_HAND, SwingMode.DO_NOT_HIDE),
            )
        }
        val reason = crystalOutcome(outcome, above, view, ledger)
        if (reason == null) {
            ledger.markPlaced(above, ledger.predictNextId(), candidate.damage.effective)
        }
        return reason
    }

    /**
     * `EndCrystalItem.useOn` preconditions minus the crystals that are about to vanish (`EndCrystalItem.java:26-39`),
     * and the world border check of `useItemOn` that would drop the packet (`MultiPlayerGameMode.java:321-322`).
     */
    private fun crystalGate(pos: BlockPos, above: BlockPos, view: WorldView, ledger: CrystalLedger): String? = when {
        !view.isPlaceable(pos) -> "block no longer placeable"
        !view.isColumnFree(above, dyingCrystals(ledger)) -> "column obstructed"
        !world.worldBorder.isWithinBounds(pos) -> "outside the world border"
        else -> null
    }

    /**
     * `Success` → placed. Client-side `Fail` whose only cause is a crystal we attacked / expect removed → manual
     * swing (class KDoc), placed. Anything else → reason (the packet still went out for a `Fail`).
     *
     * The swung hand is [StrictInteractionResult.hand], the hand the result actually came from, NOT the hand holding
     * the crystal: the vanilla-like offhand loop of [useOnLikeVanilla] runs the MAIN hand first and can end there
     * (a world-border or feature-flag `Fail` of `useItemOn`, `MultiPlayerGameMode.java:321-322, 343-345`), and
     * swinging the offhand for a main-hand result would put a `ServerboundSwingPacket` on the wire that no client
     * would send.
     *
     * The `Fail` branch is deliberately narrow: `occupied` requires the crystal column to hold at least one entity,
     * and the second test requires every entity in it to be a [dyingCrystals] member, so a `Fail` from the block
     * half of `EndCrystalItem.useOn` (`EndCrystalItem.java:27-32`) or from any foreign entity is reported, never
     * swung.
     */
    private fun crystalOutcome(
        outcome: StrictInteractionResult?,
        above: BlockPos,
        view: WorldView,
        ledger: CrystalLedger,
    ): String? {
        if (outcome == null) return "use not consumed (null)"
        if (outcome.result is InteractionResult.Success) return null
        if (outcome.result !is InteractionResult.Fail) return "use not consumed (${outcome.result})"

        val occupied = !view.isColumnFree(above) { false }
        if (!occupied || !view.isColumnFree(above, dyingCrystals(ledger))) return "client-side fail"

        SwingMode.DO_NOT_HIDE.swing(outcome.hand)
        return null
    }

    /**
     * The hand loop of `Minecraft.startUseItem` for a BLOCK hit (`Minecraft.java:1743-1800`): per hand (main, then
     * off) a disabled item ends the loop (`:1747-1749`), `useItemOn` ends it on `Success` or `Fail` (`:1768-1783`),
     * a non-empty stack that passed tries `useItem` and ends it on `Success` (`:1790-1797`). Returns the consuming
     * result TOGETHER WITH the hand and the call it came from, or null when no hand consumed the click.
     *
     * Neither `USE_ITEM` leg can consume here, so [crystalOutcome] never mistakes one for a placement: the main-hand
     * one is refused up front by [slotGate] through [mainHandConsumesUse], and the offhand holds `Items.END_CRYSTAL`
     * (also [slotGate]), which registers plain `Item.Properties` with only `ENCHANTMENT_GLINT_OVERRIDE`
     * (`Items.java:1869-1871`) and does not override `use`, so `Item.use` falls through to `PASS`
     * (`Item.java:188-211`).
     */
    private fun useOnLikeVanilla(hit: BlockHitResult, aim: Rotation): StrictInteractionResult? {
        for (hand in InteractionHand.entries) {
            val held = player.getItemInHand(hand)
            if (!held.isItemEnabled(world.enabledFeatures())) return null

            val onBlock = interactBlock(hit, hand, SwingMode.DO_NOT_HIDE)
            if (onBlock is InteractionResult.Success || onBlock is InteractionResult.Fail) {
                return StrictInteractionResult(hand, StrictInteractionSource.USE_ITEM_ON, onBlock)
            }
            if (held.isEmpty) continue

            val used = useItem(hand, aim.yRot, aim.xRot, SwingMode.DO_NOT_HIDE)
            if (used is InteractionResult.Success) {
                return StrictInteractionResult(hand, StrictInteractionSource.USE_ITEM, used)
            }
        }
        return null
    }

    /**
     * Conservative static answer to "would the MAIN_HAND pass of `startUseItem` consume the click on obsidian?".
     * Obsidian / bedrock have no block use, so the pass consumes iff the item's `useOn` or `use` does: an empty hand
     * never (`performUseItemOn` skips `useOn`, `MultiPlayerGameMode.java:360-374`; `useItem` is skipped,
     * `Minecraft.java:1790`); a plain `Item` (swords, pickaxes, ... are registered as plain items,
     * `Items.java:1217`) has the default `useOn` = `PASS` (`Item.java:179-181`) and a `use` that only consumes with
     * `CONSUMABLE`, a swappable `EQUIPPABLE`, `BLOCKS_ATTACKS` or `KINETIC_WEAPON` (`Item.java:188-211`); every
     * `Item` subclass (`BlockItem`, tools with `useOn` overrides, shields, ...) is treated as consuming.
     */
    private fun mainHandConsumesUse(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (stack.item.javaClass != Item::class.java) return true

        return stack.has(DataComponents.CONSUMABLE) ||
            stack.get(DataComponents.EQUIPPABLE)?.swappable() == true ||
            stack.has(DataComponents.BLOCKS_ATTACKS) ||
            stack.has(DataComponents.KINETIC_WEAPON)
    }

    /**
     * Re-verifies the base the way `BasePlaceSearch` admitted it (`BlockPlaceContext.canPlace()` through the client
     * helper `canBeReplacedWith`, BlockExtensions.kt:464-478; air short-circuits), re-traces the click against the
     * outgoing rotation and clicks with the obsidian in the main hand.
     *
     * The face is constrained only for the RELATIVE form. `BasePlan.face` is null for the replace-in-place form,
     * where `replaceClicked` makes `BlockPlaceContext` put the obsidian into the clicked cell whatever the hit
     * direction was (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-61`) - so whatever
     * direction the ray really produced goes on the wire, exactly as vanilla does. Same shape as
     * `MineJob`'s own nullable-face click test.
     */
    private fun placeBase(base: BasePlan, view: WorldView, aim: Rotation, ignore: (Entity) -> Boolean): String? {
        val state = view.state(base.block)
        if (!state.isAir && !state.canBeReplacedWith(base.block, obsidianStack)) {
            return "base block no longer replaceable"
        }
        if (!world.worldBorder.isWithinBounds(base.clickPos)) return "outside the world border"
        val hit = byPlaceAim(aim) { rot ->
            RayTests.hitsBlock(view.eye, rot, base.clickPos, baseRange, ignore, place.throughBlocks)
                ?.takeIf { base.face == null || it.direction == base.face }
        } ?: return "base ray misses"

        // A `ModuleSpeedMine` dig left on this cell - a cell we or the player broke - would break the obsidian again:
        // with `Rebreak = Grim` it watches the cell after its STOP, and `InstantAsync` fires on exactly the obsidian
        // block update the server sends for this placement. Released before the click, so the update finds nothing.
        // For an air cell the dig is over and the release sends nothing (`ModuleSpeedMine.releaseForAura`).
        ModuleSpeedMine.releaseForAura(base.block)

        val result = interactBlock(hit, InteractionHand.MAIN_HAND, SwingMode.DO_NOT_HIDE)
        return if (result is InteractionResult.Success) null else "base not placed ($result)"
    }

    /** The offhand is always held; a hotbar slot is held when it is the slot the server has selected. */
    private fun isHeld(slot: HotbarItemSlot): Boolean =
        slot.isOffHand || SilentHotbar.serversideSlot == slot.hotbarIndex

    /** Crystals that still exist client-side but are gone (or about to be) server-side. */
    private fun dyingCrystals(ledger: CrystalLedger): (Entity) -> Boolean =
        { entity -> entity is EndCrystal && (ledger.isAttacked(entity.id) || ledger.isExpectedRemoved(entity.id)) }

    /** [ignore] with [entity] itself always pickable - a ray meant to hit it must be allowed to. */
    private fun ignoreExcept(entity: EndCrystal, ignore: (Entity) -> Boolean): (Entity) -> Boolean =
        { other -> other !== entity && ignore(other) }

    /** [ignore] extended by the crystal attacked this tick, unchanged when there is none. */
    private fun ignoreAlso(entity: EndCrystal?, ignore: (Entity) -> Boolean): (Entity) -> Boolean =
        if (entity == null) ignore else { other -> other === entity || ignore(other) }

    private fun reason(attackReason: String?, useReason: String?): String? {
        val parts = ArrayList<String>(2)
        if (attackReason != null) parts.add("attack: $attackReason")
        if (useReason != null) parts.add("use: $useReason")
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    private companion object {
        /** The stack `canBeReplacedWith` builds its placement context from; registry-backed, hence lazy. */
        val obsidianStack: ItemStack by lazy { ItemStack(Items.OBSIDIAN) }

        /** Reported when the hub's `allowAttack` gate is closed (class KDoc). */
        const val ATTACK_BLOCKED = "attack blocked this tick"

        /** Reported when the hub's `allowUse` gate is closed (class KDoc). */
        const val USE_BLOCKED = "use blocked this tick"

        /** `getAttackStrengthScale(0.5F)` of `Player.attack` (`Player.java:944`). */
        const val STRENGTH_PARTIAL = 0.5f

        /** `fullStrengthAttack = attackStrengthScale > 0.9F` (`Player.java:950`). */
        const val FULL_STRENGTH_SCALE = 0.9f

        /** `knockbackAttack ? 0.5F : 0.0F` added to the knockback amount (`Player.java:975`). */
        const val SPRINT_KNOCKBACK_BONUS = 0.5f

        /** `getKnockback` halves the `ATTACK_KNOCKBACK` attribute (`LivingEntity.java:1515-1516`). */
        const val KNOCKBACK_HALVING = 2.0f
    }
}
