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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.debug

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.Exposure
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.TargetEntry
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.entity.explosionDamageBreakdown
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ServerExplosion
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs

/**
 * Diagnostics of the crystal aura: a rate-limited debug chat line ([log]) and a runtime exactness check of the
 * replicated explosion model against vanilla's own static code running on the client ([crossCheck]).
 *
 * This is a DIAGNOSTIC path, only active with the module's Debug setting: every [crossCheck] casts the complete
 * `getSeenPercent` grid through vanilla `level.clip` (45 rays for a 0.6 x 1.8 x 0.6 player box, see
 * `Exposure.sampleCount`) in addition to the module's own rays, and the damage oracle allocates freely. Both entry
 * points return immediately when their `enabled` argument is false. Main thread only: the counters, timers and the
 * report buffer are shared, unsynchronized state.
 *
 * ### Oracles
 * - Exposure: `ServerExplosion.getSeenPercent(Vec3, Entity)` (`net/minecraft/world/level/ServerExplosion.java:78-109`),
 *   a public static that samples `entity.getBoundingBox()` (`:79`) and clips `entity.level()` with the entity's own
 *   context (`:96`), i.e. the LIVE box and level. Our side is therefore re-evaluated with [Exposure.seenPercent] at
 *   `entity.getBoundingBox()` through the target's own [TargetEntry.source] (same `CollisionContext.of(entity)`,
 *   per-tick memo, so the cells are already cached) instead of taking the (possibly predicted) `VictimState.box`
 *   result the aura scored with.
 * - Damage: [explosionDamageBreakdown] (`utils/entity/EntityExtensions.kt:574-607`), the client's diagnostic
 *   re-computation of its `getEffectiveDamage`, built on the same `getSeenPercent` call (`:576`). Its damage source is
 *   `damageSources().explosion(null)` (`:581`), type `explosion` (`net/minecraft/world/damagesource/DamageSources.java:
 *   266-272`) instead of the crystal's `player_explosion`; both are `"scaling": "always"`, both are in `is_explosion`
 *   and neither is in a `bypasses_*` tag (`data/minecraft/damage_type/<id>.json` and
 *   `data/minecraft/tags/damage_type/<tag>.json`
 *   of the 26.1.2 jar), so the two types are indistinguishable for every stage the oracle models.
 *
 * ### Stage mapping, ours ([DamageResult]) -> oracle (`ExplosionDamageBreakdown`, `EntityExtensions.kt:555-568`)
 * - `exposure` -> `exposure`: exact oracle. Compared on EVERY check (tolerance 1e-4; a real disagreement is at least
 *   one ray, 1/45).
 * - `raw` -> `raw` (`:577-579`): the `getEntityDamageAmount` formula (`ExplosionDamageCalculator.java:29-35`) with
 *   the same operand order; the oracle lacks the `dist > 1.0` skip of `ServerExplosion.hurtEntities`
 *   (`ServerExplosion.java:183`), which is why the damage stages are only compared when `ours.raw > 0` (our
 *   `DamageCalculator` yields `raw == 0` exactly when it did not evaluate: skipped victim or outside the radius).
 * - `afterDifficulty` -> `afterDifficulty` (`:587-597`): the same `Player`-only table as `Player.hurtServer`
 *   (`Player.java:688-700`).
 * - `afterArmor` -> `afterArmor` (`:599`, `:648-653`): comparable only when neither the shield nor the i-frame stage
 *   changed the value, i.e. `ours.dealt == ours.afterDifficulty` - the oracle feeds `afterDifficulty` straight into
 *   the armor formula. Its armor / toughness are the sums of the `Attributes.ARMOR` / `ARMOR_TOUGHNESS` modifiers of
 *   the four armor-slot stacks (`:625-633`, `utils/item/ItemCategorizations.kt:103-107`) rather than the synced
 *   entity attributes our `VictimState.armor` / `toughness` read: a mismatch here can be the oracle's simplification
 *   (e.g. a zombie's base armor of 2, or non-armor attribute modifiers), not necessarily our error.
 * - `afterMagic` (= `effective`) -> `afterEnchants` (`:600-601`, `:662-699`): resistance with vanilla's arithmetic,
 *   then the fixed EPF table (Protection 1/level, Blast Protection 2/level, summed, `1 - clamp(epf, 0, 20) / 25`) -
 *   equal to vanilla data for the vanilla enchantments, but blind to data-pack `DAMAGE_PROTECTION` effects that our
 *   `EnchantProtection` follows. Same comparability condition as `afterArmor`.
 * - NOT comparable (the oracle models fewer stages): `afterBlocking`, `iFrameGated`, `dealt` (no `applyItemBlocking`
 *   and no `invulnerableTime` / `lastHurt` window - checks are skipped whenever those stages changed the value),
 *   `absorbed`, `healthLoss` (the oracle stops before the `actuallyHurt` absorption split). The oracle's
 *   `afterResistance` has no counterpart in [DamageResult] (resistance and protection are merged in `afterMagic`)
 *   and is not compared.
 *
 * ### Bookkeeping
 * [checks] counts every stage comparison performed (the exposure always; plus `raw`, `afterDifficulty`,
 * `afterArmor`, `afterMagic` when the damage stages are comparable), [mismatches] those whose absolute difference
 * exceeds the tolerance (1e-4 for the exposure, 0.05 for damage values; a NaN on either side counts as a mismatch).
 * The counters are updated whether or not the mismatch line is emitted; the line itself (all mismatching stages of
 * the check, both values and the stage name, plus a note when the damage stages were skipped and why) is rate-limited
 * to one per second. The memo of [TargetEntry.source] is per tick, so run the check before the tick's actions have
 * changed the world - vanilla's side always reads the live level.
 */
object AuraDebug {

    /** Five [log] lines per second at most (published only because the inline [log] reads it). */
    @PublishedApi
    internal const val LOG_INTERVAL_MS = 200L

    /** One mismatch line per second at most. */
    private const val MISMATCH_INTERVAL_MS = 1000L

    private const val EXPOSURE_TOLERANCE = 1e-4f

    private const val DAMAGE_TOLERANCE = 0.05f

    private const val STAGE_SEPARATOR = " | "

    @PublishedApi
    internal val logTimer = Chronometer()

    private val mismatchTimer = Chronometer()

    /** Reusable buffer for the mismatch line; only its final `toString()` allocates. */
    private val report = StringBuilder()

    /** Number of stage comparisons [crossCheck] performed since the last [reset]. */
    var checks: Int = 0
        private set

    /** Number of those comparisons that exceeded their tolerance. */
    var mismatches: Int = 0
        private set

    /**
     * Rate-limited debug chat line (at most one per [LOG_INTERVAL_MS], dropped otherwise), only when [enabled].
     * Inlined so that [message] is neither allocated nor evaluated when disabled or dropped.
     */
    inline fun log(module: ClientModule, enabled: Boolean, message: () -> String) {
        if (enabled && logTimer.hasAtLeastElapsed(LOG_INTERVAL_MS)) {
            logTimer.reset()
            chat(message(), module)
        }
    }

    /**
     * Compares the aura's model with vanilla for the crystal explosion at [center] against [target] (see the class
     * KDoc for the oracles, the stage mapping and the rate limit). [ours] is the result the aura scored with, computed
     * on `target.breakState` when [forBreak] else `target.placeState`; the damage stages are compared only when that
     * state's feet are the entity's current position (`Vec3.equals`, `Vec3.java:196-206`), when [ours] was actually
     * evaluated (`raw > 0`) and when neither the shield nor the i-frame stage altered the value
     * (`dealt == afterDifficulty`) - otherwise only the exposure is checked and the emitted line notes why.
     *
     * Diagnostic only: 45 vanilla `level.clip` calls per check (the oracle's `getSeenPercent`) plus the module's own
     * 45 rays; the oracle also allocates. Active only with the module's Debug setting ([enabled]).
     */
    fun crossCheck(
        module: ClientModule,
        enabled: Boolean,
        center: Vec3,
        target: TargetEntry,
        ours: DamageResult,
        forBreak: Boolean,
    ) {
        if (!enabled) return

        val entity = target.entity
        val state = if (forBreak) target.breakState else target.placeState
        val skipReason = damageSkipReason(ours, state.feet, entity)
        // The breakdown carries the very same `ServerExplosion.getSeenPercent(center, entity)` value, so the
        // vanilla grid is cast once per check either way.
        val breakdown = if (skipReason == null) entity.explosionDamageBreakdown(center, ExplosionMath.POWER) else null
        val vanillaExposure = breakdown?.exposure ?: ServerExplosion.getSeenPercent(center, entity)
        val liveExposure = Exposure.seenPercent(center, entity.boundingBox, target.source)

        val describe = mismatchTimer.hasAtLeastElapsed(MISMATCH_INTERVAL_MS)
        report.setLength(0)
        compare("exposure", liveExposure, vanillaExposure, EXPOSURE_TOLERANCE, describe)
        if (breakdown != null) {
            compare("raw", ours.raw, breakdown.raw, DAMAGE_TOLERANCE, describe)
            compare("afterDifficulty", ours.afterDifficulty, breakdown.afterDifficulty, DAMAGE_TOLERANCE, describe)
            compare("afterArmor", ours.afterArmor, breakdown.afterArmor, DAMAGE_TOLERANCE, describe)
            compare("afterMagic", ours.afterMagic, breakdown.afterEnchants, DAMAGE_TOLERANCE, describe)
        }
        if (report.isEmpty()) return

        mismatchTimer.reset()
        if (skipReason != null) {
            report.append(STAGE_SEPARATOR).append("damage stages skipped: ").append(skipReason)
        }
        val phase = if (forBreak) "break" else "place"
        chat("CrossCheck ${entity.name.string}#${entity.id} ($phase): $report", module)
    }

    /** Zeroes [checks] / [mismatches] and lets the next [log] and mismatch line through immediately. */
    fun reset() {
        checks = 0
        mismatches = 0
        report.setLength(0)
        logTimer.reset(0L)
        mismatchTimer.reset(0L)
    }

    /**
     * Why the damage stages of [ours] cannot be compared with the oracle, or `null` when they can: [ours] was not
     * evaluated at all (`raw == 0`, see the class KDoc), [feet] is not the entity's live `position()` (predicted
     * victim), or the shield / i-frame stages - which the oracle lacks - changed the value.
     */
    private fun damageSkipReason(ours: DamageResult, feet: Vec3, entity: LivingEntity): String? = when {
        ours.raw == 0f -> "not evaluated (skipped victim or outside the radius)"
        feet != entity.position() -> "victim not at the evaluated position (predicted)"
        ours.dealt != ours.afterDifficulty -> "shield or i-frame stage changed the value (not modelled by the oracle)"
        else -> null
    }

    /**
     * Counts one comparison of [stage]; a difference above [tolerance] (or a NaN) counts as a mismatch and, when
     * [describe] is set, is appended to [report] with both values.
     */
    private fun compare(stage: String, ours: Float, vanilla: Float, tolerance: Float, describe: Boolean) {
        checks++
        if (abs(ours - vanilla) <= tolerance) return

        mismatches++
        if (!describe) return

        if (report.isNotEmpty()) {
            report.append(STAGE_SEPARATOR)
        }
        report.append(stage).append(" ours=").append(format(ours)).append(" vanilla=").append(format(vanilla))
    }

    private fun format(value: Float): String = String.format(Locale.ROOT, "%.4f", value)
}
