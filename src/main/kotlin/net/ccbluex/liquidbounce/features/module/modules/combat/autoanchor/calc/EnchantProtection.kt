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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc

import net.minecraft.advancements.criterion.DamageSourcePredicate
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.lang.ref.WeakReference
import kotlin.jvm.optionals.getOrNull

/**
 * Client-side replica of `EnchantmentHelper.getDamageProtection(ServerLevel, LivingEntity, DamageSource)`
 * (`net/minecraft/world/item/enchantment/EnchantmentHelper.java:182-188`): the enchantment protection points that
 * `LivingEntity.getDamageAfterMagicAbsorb` hands to `CombatRules.getDamageAfterMagicAbsorb`
 * (`net/minecraft/world/entity/LivingEntity.java:1918-1920`).
 *
 * ### Why vanilla cannot be asked on the client
 * `LivingEntity.getDamageAfterMagicAbsorb` (`LivingEntity.java:1911-1916`) consults the enchantments only on a
 * server level and otherwise silently reports zero protection:
 * ```java
 * float enchantmentArmor;
 * if (this.level() instanceof ServerLevel serverLevel) {
 *     enchantmentArmor = EnchantmentHelper.getDamageProtection(serverLevel, this, damageSource);
 * } else {
 *     enchantmentArmor = 0.0F;
 * }
 * ```
 * The `ServerLevel` is required because every effect is filtered through a `LootContext` built by
 * `Enchantment.damageContext(ServerLevel, int, Entity, DamageSource)` (`Enchantment.java:471-481`:
 * `new LootParams.Builder(serverLevel).withParameter(THIS_ENTITY, victim)...withParameter(DAMAGE_SOURCE, source)`).
 * A `ClientLevel` is no `ServerLevel`, and the `DamageSource` of an anchor that has not detonated yet cannot be
 * built, so this object re-evaluates the requirements directly against the damage type holder.
 *
 * ### Vanilla, mirrored step by step
 * `EnchantmentHelper.java:182-188`:
 * ```java
 * MutableFloat result = new MutableFloat(0.0F);
 * runIterationOnEquipment(victim, (enchantment, level, item) ->
 *     enchantment.value().modifyDamageProtection(serverLevel, level, item.itemStack(), victim, source, result));
 * return result.floatValue();
 * ```
 * `runIterationOnEquipment(LivingEntity, EnchantmentInSlotVisitor)` (`:168-172`) loops `EquipmentSlot.VALUES`
 * (`EquipmentSlot.java:23`, hands and body slots included) and calls
 * `runIterationOnItem(owner.getItemBySlot(slot), slot, owner, method)` (`:147-163`), which skips empty and
 * unenchanted stacks and invokes the visitor only `if (enchantment.value().matchingSlot(slot))` (`:157`) with
 * `accept(Holder<Enchantment> enchantment, int level, EnchantedItemInUse item)` (`:619-622`, `@FunctionalInterface`).
 * This object calls that very iteration, so the slot filtering is vanilla's own.
 *
 * `Enchantment.modifyDamageProtection` (`Enchantment.java:208-222`; its `item` argument is unused there):
 * ```java
 * applyEffects(this.getEffects(EnchantmentEffectComponents.DAMAGE_PROTECTION),
 *     damageContext(serverLevel, enchantmentLevel, victim, source), protection,
 *     (e, v) -> e.process(enchantmentLevel, victim.getRandom(), v));
 * ```
 * `getEffects(type)` (`:192-194`) is `this.effects.getOrDefault(type, List.of())`; `applyEffects` (`:537-543`) is
 * `for (ConditionalEffect<T> c : effects) if (c.matches(filterData)) action.apply(c.effect())`, the `MutableFloat`
 * overload (`:548-552`) wrapping the action as `effect -> v.setValue(this.apply(effect, v.floatValue()))`
 * (`FloatAction.asGeneric`, `:726-728`), i.e. `value = effect.process(enchantmentLevel, random, value)` in list order.
 * `DAMAGE_PROTECTION` is a `DataComponentType<List<ConditionalEffect<EnchantmentValueEffect>>>`
 * (`EnchantmentEffectComponents.java:26-28`); `ConditionalEffect<T>(T effect, Optional<LootItemCondition>
 * requirements)` matches when `requirements.isEmpty() || requirements.get().test(context)`
 * (`ConditionalEffect.java:11,22-24`); `EnchantmentValueEffect.process(int enchantmentLevel, RandomSource random,
 * float inputValue)` (`effects/EnchantmentValueEffect.java:24`).
 *
 * ### Requirement evaluation without a LootContext
 * Vanilla data (`data/minecraft/enchantment/<name>.json` in `minecraft-merged-deobf-26.1.2.jar`) only uses
 * `minecraft:damage_source_properties`: `blast_protection.json` has effect `add` linear `base 2.0,
 * per_level_above_first 2.0` with tags `[{is_explosion, expected true}, {bypasses_invulnerability, expected false}]`
 * (both hold for `bad_respawn_point`, so Blast Protection really does apply to an anchor blast);
 * `protection.json` has `add` linear `base 1.0, per_level_above_first 1.0` with tags
 * `[{bypasses_invulnerability, expected false}]`; `projectile_protection` / `feather_falling` follow the same shape
 * and `fire_protection.json` wraps it in `all_of`.
 * `DamageSourceCondition(Optional<DamageSourcePredicate> predicate)` (`predicates/DamageSourceCondition.java:14`)
 * tests `predicate.isEmpty() || predicate.get().matches(context.getLevel(), pos, damageSource)` (`:29-35`).
 * `DamageSourcePredicate(List<TagPredicate<DamageType>> tags, Optional<EntityPredicate> directEntity,
 * Optional<EntityPredicate> sourceEntity, Optional<Boolean> isDirect)` (`criterion/DamageSourcePredicate.java:15-17`)
 * matches (`:32-46`) when every `tag.matches(source.typeHolder())`, the present entity predicates match and
 * `!isDirect.isPresent() || isDirect.get() == source.isDirect()`; `TagPredicate<T>(TagKey<T> tag, boolean expected)`
 * matches `holder.is(this.tag) == this.expected` (`criterion/TagPredicate.java:10,26-28`).
 *
 * Deliberate deviations (the only ones):
 * - No `LootContext`: `source.typeHolder()` is the [Holder] passed in and `source.isDirect()` the `isDirect` flag
 *   (`DamageSource.isDirect() = causingEntity == directEntity`, `DamageSource.java:32-34`; an anchor blast is
 *   `new DamageSource(type, boomPos)` with NEITHER entity, `DamageSources.java:278-280`, so both are `null` and
 *   `isDirect()` is `true` - the reason the [protectionPoints] default flipped from the crystal's `false`).
 * - `direct_entity` / `source_entity` predicates need a `ServerLevel`
 *   (`EntityPredicate.matches(ServerLevel, Vec3, Entity)`, `DamageSourcePredicate.java:39,42`) and cannot be
 *   evaluated here: the effect is skipped and counted in [lastSkippedConditions].
 * - Every other `LootItemCondition` implementation (`all_of`, `any_of`, `inverted`, `random_chance`, data-pack
 *   predicates, ...) is skipped and counted as well; `CompositeLootItemCondition.terms` is `protected`
 *   (`CompositeLootItemCondition.java:15`), so even `all_of` cannot be unwrapped without an accessor. For explosion
 *   damage this changes nothing with vanilla data (fire protection never applies to explosions).
 * - The random source is the client entity's `getRandom()` (`Entity.java:3936-3938`); it only matters for random
 *   value effects (`remove_binomial`), which no vanilla protection enchantment uses.
 *
 * Main-thread only (see [lastSkippedConditions]).
 */
object EnchantProtection {

    /**
     * Number of `requirements` the last [protectionPoints] call could not evaluate client-side (their effects were
     * skipped, see the class KDoc). For the debug output; main thread.
     */
    var lastSkippedConditions: Int = 0
        private set

    /** [badRespawnPointType] cache, keyed by [Level] identity (weak, so a left world is not kept alive). */
    private var cachedLevel: WeakReference<Level>? = null
    private var cachedBadRespawnPoint: Holder<DamageType>? = null

    /**
     * Protection points of [victim]'s equipment against a damage source of type [damageType]: what
     * `EnchantmentHelper.getDamageProtection` (`EnchantmentHelper.java:182-188`) returns on the server.
     * For anchor blasts pass [badRespawnPointType] and leave [isDirect] at its default `true`:
     * `damageSources().badRespawnPointExplosion(boomPos)` is `new DamageSource(type, boomPos)`
     * (`DamageSources.java:278-280`), which leaves `directEntity` and `causingEntity` both `null`, so
     * `isDirect() = causingEntity == directEntity` holds (`DamageSource.java:32-34`). The caller applies vanilla's
     * cap: `CombatRules.getDamageAfterMagicAbsorb` clamps the points to `[0, 20]`. Updates
     * [lastSkippedConditions].
     */
    fun protectionPoints(victim: LivingEntity, damageType: Holder<DamageType>, isDirect: Boolean = true): Float {
        var total = 0.0f
        var skipped = 0
        // `getRandom()` spelled out: `Entity` also declares a protected field named `random` (`Entity.java:255`).
        val random = victim.getRandom()

        EnchantmentHelper.runIterationOnEquipment(victim) { enchantment, enchantmentLevel, _ ->
            val effects = enchantment.value().getEffects(EnchantmentEffectComponents.DAMAGE_PROTECTION)
            for (conditionalEffect in effects) {
                val verdict = applies(conditionalEffect.requirements().getOrNull(), damageType, isDirect)
                if (verdict == null) {
                    skipped++
                } else if (verdict) {
                    total = conditionalEffect.effect().process(enchantmentLevel, random, total)
                }
            }
        }

        lastSkippedConditions = skipped
        return total
    }

    /**
     * The `Holder<DamageType>` of `DamageTypes.BAD_RESPAWN_POINT` (`DamageTypes.java:55`,
     * `minecraft:bad_respawn_point`; registered as `new DamageType("badRespawnPoint", DamageScaling.ALWAYS, 0.1F,
     * DamageEffects.HURT, DeathMessageType.INTENTIONAL_GAME_DESIGN)`, `DamageTypes.java:109`) resolved through
     * `level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key)` (`Level.java:1093-1095`,
     * `RegistryAccess.java:22-24`, `Registry.java:27` -> `HolderGetter.java:11-13`, `Registries.java:265`) and cached
     * per [Level] instance: the holder belongs to that level's synced registry and must not outlive it.
     *
     * This is the type `RespawnAnchorBlock.explode` blows up with
     * (`RespawnAnchorBlock.java:169` -> `DamageSources.badRespawnPointExplosion`, `DamageSources.java:278-280`).
     */
    fun badRespawnPointType(level: Level): Holder<DamageType> {
        val cached = cachedBadRespawnPoint
        if (cached != null && cachedLevel?.get() === level) {
            return cached
        }

        val registry = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
        val holder: Holder<DamageType> = registry.getOrThrow(DamageTypes.BAD_RESPAWN_POINT)
        cachedLevel = WeakReference(level)
        cachedBadRespawnPoint = holder
        return holder
    }

    /**
     * `ConditionalEffect.matches` (`ConditionalEffect.java:22-24`) without a `LootContext`:
     * `true` = the requirement holds, `false` = it does not, `null` = it cannot be evaluated client-side.
     */
    private fun applies(condition: LootItemCondition?, damageType: Holder<DamageType>, isDirect: Boolean): Boolean? =
        when (condition) {
            null -> true
            is DamageSourceCondition -> {
                // DamageSourceCondition.test (:29-35): an absent predicate always passes.
                val predicate = condition.predicate().getOrNull()
                if (predicate == null) true else matches(predicate, damageType, isDirect)
            }
            else -> null
        }

    /**
     * `DamageSourcePredicate.matches(ServerLevel, Vec3, DamageSource)` (`DamageSourcePredicate.java:32-46`) with
     * `source.typeHolder()` replaced by [damageType] and `source.isDirect()` by [isDirect], in vanilla's order:
     * a failing tag decides `false` before the entity predicates are looked at; present entity predicates are not
     * evaluable (`null`); otherwise `!isDirect.isPresent() || isDirect.get() == source.isDirect()`.
     */
    private fun matches(predicate: DamageSourcePredicate, damageType: Holder<DamageType>, isDirect: Boolean): Boolean? =
        when {
            predicate.tags().any { !it.matches(damageType) } -> false
            predicate.directEntity().isPresent || predicate.sourceEntity().isPresent -> null
            else -> predicate.isDirect().getOrNull()?.let { it == isDirect } ?: true
        }
}
