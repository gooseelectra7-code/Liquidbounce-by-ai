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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.BreakSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.PlaceSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageCalculator
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.DamageResult
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.TargetEntry
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.utils.math.distanceToSqr
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.Vec3

/**
 * One accepted attack: the [crystal] to hit, its explosion [center] (`crystal.position()`), the [target] it was scored
 * against, that target's [damage] (evaluated at `TargetEntry.breakState`) and the local player's [selfDamage].
 */
data class BreakCandidate(
    val crystal: EndCrystal,
    val center: Vec3,
    val target: TargetEntry,
    val damage: DamageResult,
    val selfDamage: DamageResult,
)

/**
 * Finds the existing crystal to attack right now. One instance per tick (it holds the tick's [WorldView]); main
 * thread only.
 *
 * Vanilla: any damage removes the crystal and explodes it at its own position with power 6
 * (`EndCrystal.hurtServer`, `net/minecraft/world/entity/boss/enderdragon/EndCrystal.java:82-106`; `level.explode(...,
 * this.getX(), this.getY(), this.getZ(), 6.0F, ...)` at `:98`), so the centre is `crystal.position()`
 * (`Entity.java:3632`) and the victims are hurt exactly as for a placement (`ServerExplosion.java:182-183` for the
 * 12-block feet range). The acceptance rules and the ordering are those of [PlaceSearch] (MinDamage, LethalOverride,
 * MaxSelfDamage and AntiSuicide from [place]), evaluated at `TargetEntry.breakState`, through the same
 * [ExplosionRanker].
 *
 * Candidate crystals ([best]): every [WorldView.crystals] entry, minus those we already attacked or expect to be
 * chain-removed ([CrystalLedger.isAttacked], [CrystalLedger.isExpectedRemoved]) and, with `brk.onlyOwn`, those not
 * confirmed ours ([CrystalLedger.isOwn]); prefilter `crystal.getBoundingBox()` (`Entity.java:3369`, the
 * `sized(2.0F, 2.0F)` box) closer than `brk.range` to the eye — the eye-to-hit-point distance of the attack pick can
 * only be larger, the exact ray test is the planner's look point search.
 */
class BreakSearch(
    private val view: WorldView,
    private val calc: DamageCalculator,
    private val place: PlaceSettings,
    private val brk: BreakSettings,
    private val ledger: CrystalLedger,
) {

    /**
     * Best existing crystal to attack now (uses `TargetEntry.breakState`; skips attacked / expectedRemoved and, with
     * OnlyOwn, foreign crystals), or null.
     */
    fun best(): BreakCandidate? {
        val ranker = ExplosionRanker<EndCrystal>(
            view = view,
            calc = calc,
            settings = place,
            victimOf = { it.breakState },
            sourceOf = { it.source },
            selfSource = view.selfSource,
            gate = { true },
        )
        val eye = view.eye
        val rangeSq = rangeSq()
        for (crystal in view.crystals) {
            if (isExcluded(crystal.id)) continue
            val distSq = crystal.boundingBox.distanceToSqr(eye.x, eye.y, eye.z)
            if (distSq < rangeSq) {
                ranker.offer(crystal, crystal.position(), distSq)
            }
        }
        return ranker.best()?.let {
            BreakCandidate(crystal = it.key, center = it.center, target = it.target, damage = it.damage,
                selfDamage = it.selfDamage)
        }
    }

    /**
     * Damage bookkeeping for a crystal exploding at [center] (IdPredict phantoms and the ledger's lastHurt
     * estimates): target entity id → `afterBlocking`, the value `LivingEntity.hurtServer` stores in `lastHurt`
     * (`damage -= damageBlocked`, `net/minecraft/world/entity/LivingEntity.java:1181`; `this.lastHurt = damage` at
     * `:1203` inside and `:1206` outside the invulnerability window). Victims that take nothing (`afterBlocking == 0`)
     * and i-frame-gated hits are left out: for the latter vanilla returns before touching `lastHurt`
     * (`:1198-1199`), so the ledger's previous estimate stays valid. Evaluated at `TargetEntry.breakState`; the local
     * player is not tracked by the ledger and therefore not included.
     */
    fun predictedLastHurt(center: Vec3): Map<Int, Float> {
        val result = Int2FloatOpenHashMap()
        for (target in view.targets) {
            val damage = calc.damage(center, target.breakState, target.source)
            if (damage.afterBlocking > 0f && !damage.iFrameGated) {
                result.put(target.entity.id, damage.afterBlocking)
            }
        }
        return result
    }

    private fun isExcluded(id: Int): Boolean {
        if (ledger.isAttacked(id) || ledger.isExpectedRemoved(id)) return true
        return brk.onlyOwn && !ledger.isOwn(id)
    }

    private fun rangeSq(): Double {
        val range = brk.range.toDouble()
        return range * range
    }
}
