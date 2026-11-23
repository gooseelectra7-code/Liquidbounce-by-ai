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
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.RotationSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.SwitchMode
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.AuraRotation
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoint
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.LookPoints
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.aim.RayTests
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.base.BasePlan
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimState
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.BreakCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceCandidate
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.state.CrystalLedger
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.world.WorldView
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * IdPredict target: the crystal of our newest pending placement, addressed by its predicted entity id before its
 * spawn packet has arrived. [box] / [center] are `ExplosionMath.crystalBox` / `crystalCenter` of the clicked block
 * (a placed crystal stands at `(above.x + 0.5, above.y, above.z + 0.5)`, `EndCrystalItem.java:42`).
 *
 * [id] is a PREDICTION, and the server punishes a wrong one with a kick rather than a miss - see
 * [TickPlanner.phantomAttack] for the guards and the vanilla lines.
 */
data class PhantomAttack(val id: Int, val box: AABB, val center: Vec3)

/**
 * What one client tick does: at most ONE attack ([attack] or [phantomAttack], never both) and at most ONE use
 * ([place] or [base], never both), attack first, plus the rotation to request for this tick ([look]).
 *
 * [attackAttempt] / [useAttempt]: PLAN offers the action to the executor for THIS tick. Whether it actually hits is
 * decided at EXECUTE against `AuraRotation.outgoing`, the rotation this tick's movement packet carries - PLAN owns
 * the intent (what to attack, what to place, where to look), EXECUTE owns the verdict (decision table in
 * [TickPlanner]). An action that is planned but not offered stays in the plan for rendering and debugging; it is
 * what [look] aims at. [attempting] is true when anything is offered this tick. [slot] / [hand]: hotbar slot and
 * hand of the use - the slot is brought into the hand for an offered use, because `gameMode.tick()` has to emit the
 * `SetCarriedItem` BEFORE the clicks. [placeHit]: the block hit of the use against the wire when the wire already
 * hits, otherwise the hit the look point promises (`LookPoint.point` lies on `LookPoint.face` of
 * `LookPoint.blockPos`); informational only - the executor always re-traces the ray against the outgoing rotation.
 */
class TickPlan(
    val attack: BreakCandidate?,
    val phantomAttack: PhantomAttack?,
    val place: PlaceCandidate?,
    val base: BasePlan?,
    val attackAttempt: Boolean,
    val useAttempt: Boolean,
    val look: LookPoint?,
    val slot: HotbarItemSlot?,
    val hand: InteractionHand,
    val placeHit: BlockHitResult?,
) {

    val attempting: Boolean
        get() = attackAttempt || useAttempt

    val hasAttack: Boolean
        get() = attack != null || phantomAttack != null

    val hasUse: Boolean
        get() = place != null || base != null

    /** Block the next crystal is placed on (rendering). */
    val placePos: BlockPos?
        get() = place?.pos

    /** Block the obsidian base goes to (rendering). */
    val basePos: BlockPos?
        get() = base?.block

    /** Hitbox of the crystal (or phantom) to attack (rendering). */
    val attackBox: AABB?
        get() = attack?.crystal?.boundingBox ?: phantomAttack?.box

    /**
     * Compact one-liner: actions with positions and target / self damage, what is offered to the executor this tick
     * (`try=AU`), and the look angle. What actually went out is the `ExecReport` the hub logs right after EXECUTE -
     * `try=` is a plan-time field and the ray verdict is not taken yet when it is printed.
     */
    fun describe(): String {
        val out = StringBuilder(DESCRIBE_CAPACITY)
        attack?.let {
            out.append("atk#").append(it.crystal.id).append(' ')
                .append(fmt(it.damage.effective)).append('/').append(fmt(it.selfDamage.effective))
        }
        phantomAttack?.let { out.append("phantom#").append(it.id) }
        place?.let {
            separate(out).append("place ").append(pos(it.pos)).append(' ')
                .append(fmt(it.damage.effective)).append('/').append(fmt(it.selfDamage.effective))
            describeDefence(out, it.target.placeState)
        }
        base?.let {
            // `face` is null for the replace-in-place form, where the obsidian goes into the clicked cell itself
            // and the hit direction is read by nobody - printed as `in-place` so a trace says which form ran.
            separate(out).append("base ").append(pos(it.block)).append(" via ").append(pos(it.clickPos))
                .append('/').append(it.face?.name ?: "in-place")
                .append(' ').append(fmt(it.candidate.damage.effective))
        }
        if (out.isEmpty()) out.append("idle")
        out.append(" try=").append(if (attackAttempt) 'A' else '-').append(if (useAttempt) 'U' else '-')
        look?.let {
            out.append(" look=").append(fmt(it.angle)).append("deg")
            if (it.servesAttack) out.append("+atk")
        }
        return out.toString()
    }

    /**
     * The victim's defence as the model read it: `arm` = `getArmorValue()`, `tgh` = `ARMOR_TOUGHNESS`,
     * `prot` = the enchantment protection points `VictimPipeline.damageAfterMagicAbsorb` applies as
     * `1 - clamp(prot, 0, 20) / 25`, and `res` the Resistance amplifier (`-1` = none).
     *
     * These four are what separates a predicted number from the damage actually taken, and three of them can be
     * wrong without anything looking broken: `prot` is computed client-side by `EnchantProtection` because vanilla
     * refuses to compute it off a `ServerLevel`, `res` is never synced for a remote victim at all, and a server may
     * rewrite the enchantment components the first is read from. Printing them turns "the aura over-estimates" from
     * a guess into a reading — a full-Protection-IV set is 16 points, a mixed set with Blast Protection IV on one
     * piece reaches the 20-point cap, and `prot=0.0` on a visibly enchanted opponent is the bug, not the model.
     */
    private fun describeDefence(out: StringBuilder, victim: VictimState) {
        out.append(" arm=").append(victim.armor)
            .append('/').append(fmt(victim.toughness))
            .append(" prot=").append(fmt(victim.protectionPoints))
            .append(" res=").append(victim.resistanceAmplifier)
    }

    private fun separate(out: StringBuilder): StringBuilder = if (out.isEmpty()) out else out.append(' ')

    private fun pos(pos: BlockPos): String = "(" + pos.x + ' ' + pos.y + ' ' + pos.z + ')'

    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

    private companion object {
        const val DESCRIBE_CAPACITY = 96
    }
}

/**
 * Turns this tick's search results into a [TickPlan]: which actions are offered to the executor for THIS tick, which
 * wait, and the ONE rotation to request. Pure decision logic over ray tests; no packets, no world mutation.
 *
 * ### PLAN decides intent, EXECUTE decides the hit
 * The wire tests below choose the candidate and the look point; they no longer veto the action. A planned action is
 * marked as an attempt ([TickPlan.attackAttempt] / [TickPlan.useAttempt]) and `ActionExecutor` re-traces its ray
 * against `AuraRotation.outgoing` - the rotation THIS tick's movement packet carries, final and normalized by the
 * time EXECUTE runs (see the `AuraRotation.outgoing` KDoc for the vanilla and Grim ordering that makes this legal).
 * A rotation requested here therefore serves the action in the SAME tick instead of the next one; when the exact
 * ray misses (the `GridMode.DITHER` noise pushed it off the point) the executor sends nothing and the next tick
 * retries with the rotation by then on the wire - the previous behaviour, kept as the fallback. No tolerance is
 * widened anywhere to make an attempt land.
 *
 * ### Rules
 * - At most one attack and one use per tick, attack first (the vanilla click order of `Minecraft.handleKeybinds`,
 *   `Minecraft.java:2036-2066`: all attack clicks, then all use clicks).
 * - `canAttack` / `canUse` are the delay gates of the hub; `sameTick == false` means a tick with an
 *   attack has no use.
 * - The use is the crystal placement unless a base plan exists (its MinGain rule was applied by `BasePlaceSearch`);
 *   a base without an obsidian slot falls back to the placement. [SwitchMode.NONE]: the needed slot must already be
 *   the one the server holds (`SilentHotbar.serversideSlot`, or the offhand), else no use.
 * - The placement is a DEMOTION CHAIN, not a single candidate: `PlaceSearch.candidates()` in rank order, walked
 *   past everything [wastedByOwnBlast] refuses ([decide]) and everything no rotation reaches ([aimUse]). Before
 *   that the tick simply did nothing in both cases and the next tick re-produced the same rejected block, which
 *   stalled the aura for as long as the rejection lasted. The head of the chain is what the old single candidate
 *   was, at the same cost; every demotion costs one more self-damage evaluation in the ranker.
 * - IdPredict ([BreakSettings.idPredict]): when the newest pending placement carries a predicted id, is at most
 *   [PENDING_MAX_AGE] ticks old, no spawn matched it yet and `level.getEntity(id)` is null, a [PhantomAttack]
 *   replaces the real attack - only when no real `BreakCandidate` exists and the id was not attacked already.
 *   Attacking a wrong id is a DISCONNECT, not a miss; see [phantomAttack].
 *
 * ### Wire tests (rotation currently on the wire = what the server has BEFORE this tick's movement packet)
 * attack: `RayTests.hitsCrystal` with the crystal itself never ignored; phantom: `RayTests.hitsPhantomBox`;
 * placement: `RayTests.hitsBlock` on the obsidian with this tick's attack crystal added to the ignore filter (the
 * attack packet precedes the use packet, so the server has removed that crystal when the use is handled);
 * base: `hitsBlock` on `clickPos` with `direction == face`. Their job is to answer "is a re-aim needed", not
 * "may the action run" - the latter is the executor's, against the outgoing rotation.
 *
 * ### Decision table (A = attack planned, U = use planned)
 * 1. A passes (or no A) and U passes (or no U): both are offered, no look; the aim is held ([TickPlan.look] null →
 *    `AuraRotation.hold`), so the outgoing rotation equals the wire one and the executor's exact test reproduces
 *    the verdict taken here.
 * 2. A fails: the attack is offered anyway and the look is requested for it - with the request landing in THIS
 *    tick's movement packet, the executor's ray against the outgoing rotation is the one that decides. Look =
 *    `LookPoints.forPlacement` with the attack crystal when a placement is planned and that point serves the
 *    attack, else `forCrystal` / `forPhantomBox`. A crystal that no sampled ray can reach never gets here: the
 *    hub takes the first crystal of the break ranking that [TickPlanner.canAim] admits, and [TickPlanner.decide]
 *    tests a phantom the same way, BEFORE the attack is allowed to suppress the use. The drop below (null look)
 *    is the guard behind that, not the mechanism - on its own it left a tick with neither action whenever
 *    `SameTick` was off, because the use had already been suppressed for the attack it dropped.
 *    The use is deliberately NOT offered while the attack is being re-aimed: should the attack's exact ray miss
 *    while the use's hits, a crystal placed within 12 blocks of one that is attacked a tick later is chain-removed
 *    WITHOUT exploding (`EndCrystal.hurtServer`, `EndCrystal.java:95-99`: only a non-explosion damage source
 *    explodes), i.e. a wasted crystal.
 * 3. A passes, U fails: both are offered and the look is the use's point (`forPlacement` without an attack crystal,
 *    `forFace` for a base). This is the case the reaction fix is about - the placement no longer costs the tick the
 *    re-aim takes. The price is that the tick is now aimed at the use: when the turn leaves the crystal the
 *    executor drops the attack, and rule 2 re-proposes it next tick, one tick later than before. The trade exists
 *    only with `SameTick` on, which is the only way A and U share a tick at all
 *    ([PlaceSettings.sameTick], default off - without it `decide` never plans a use next to an attack).
 * The all-or-nothing form of rule 3 (wait for one rotation serving both) would deadlock whenever no single rotation
 * can pick both the crystal and the block (see rule 2), hence this refinement of the approved plan.
 *
 * Slack for the look point search = `AuraRotation.slackDegrees` (the grid rounding / dither `Rotation.normalize`
 * may add), budget [BUDGET] rays per search.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class TickPlanner(
    private val place: PlaceSettings,
    private val brk: BreakSettings,
    private val rotation: RotationSettings,
    private val base: BasePlaceSettings,
) : MinecraftShortcuts {

    private val placeRange: Double
        get() = place.range.toDouble()

    private val breakRange: Double
        get() = brk.range.toDouble()

    private val baseRange: Double
        get() = base.range.toDouble()

    /**
     * `PlaceSettings.ThroughBlocks`: the block rays AND the attack rays stop caring what blocks stand between the eye
     * and the target (see `RayTests.hitsCrystal` for why the attack half is legal).
     */
    private val throughBlocks: Boolean
        get() = place.throughBlocks

    /**
     * Decides this tick. [breakBest] / [placeCandidates] / [basePlan] are the search results, [wire] the rotation on
     * the wire, [canAttack] / [canUse] the hub's attack and use gates (see [attackGated]), [sameTick] whether an
     * attack and a use may share this tick
     * (the hub resolves `SameTick` and its safe mode), [crystalSlot] / [obsidianSlot] the slots holding the items
     * (null when absent) and [rayIgnore] the LookThrough filter for the pick rays.
     *
     * [placeCandidates] is `PlaceSearch.candidates()` in rank order and LAZY: its head has already been paid for
     * (the search made it), each further element costs one 45-ray self-damage evaluation, and this method pulls
     * exactly as far as it must - not at all when the tick cannot use a crystal anyway, one element in the common
     * case, further ones only while [wastedByOwnBlast] keeps refusing. [resolve] carries the same iterator on for
     * the look-point demotion, so a tick can never pull the chain twice. The hub has already dropped every
     * candidate [canAimSpawn] refuses, so the head it measured MinGain against is one this tick can use.
     *
     * [breakBest] must be an attack [canAim] admits (the hub walks `BreakSearch.ranked` with [canAim]), and
     * [canAttack] must already include the executor's own attack gate (`ActionExecutor.attackGate`: miss time, a
     * disabled item, a minimum attack charge, a piercing weapon). Both preconditions keep an attack from costing
     * the tick its use: with `sameTick` off a planned attack suppresses the use BEFORE anything tests whether the
     * attack can go, so an attack that never goes left the tick with neither action - every tick, for as long as
     * the crystal stood (in game 2026-10-03: a placement, then seconds of `idle` next to a crystal that was never
     * hit). An IdPredict phantom is tested here with [canAimBox] for the same reason.
     *
     * [attackGated]: the attack gate is what closed [canAttack] (as opposed to the break delay). [standingWaitTicks]:
     * how long after its spawn a standing crystal the aura is not detonating still protects its blast. Both feed
     * [wastedByOwnBlast].
     */
    @Suppress("LongParameterList")
    fun decide(
        view: WorldView,
        ledger: CrystalLedger,
        breakBest: BreakCandidate?,
        attackGated: Boolean,
        standingWaitTicks: Int,
        placeCandidates: Sequence<PlaceCandidate>,
        basePlan: BasePlan?,
        wire: Rotation,
        canAttack: Boolean,
        canUse: Boolean,
        sameTick: Boolean,
        crystalSlot: HotbarItemSlot?,
        obsidianSlot: HotbarItemSlot?,
        rayIgnore: (Entity) -> Boolean,
    ): TickPlan {
        val attack = breakBest?.takeIf { canAttack }
        val phantom = if (canAttack && attack == null) {
            phantomAttack(ledger)?.takeIf { canAimBox(view.eye, wire, it.box, rayIgnore) }
        } else {
            null
        }
        val hasAttack = attack != null || phantom != null
        val useAllowed = canUse && (sameTick || !hasAttack)
        val plannedBase = basePlan?.takeIf { useAllowed && usable(obsidianSlot) }
        val places = if (useAllowed && usable(crystalSlot)) {
            val standing = Standing(breakBest?.crystal?.takeIf { !attackGated }, standingWaitTicks)
            placeCandidates.filterNot { wastedByOwnBlast(view, ledger, it, standing, attack?.crystal, phantom) }
                .iterator()
        } else {
            emptyList<PlaceCandidate>().iterator()
        }
        val plannedPlace = places.nextOrNull()
        val slot = if (plannedBase != null) obsidianSlot else crystalSlot?.takeIf { plannedPlace != null }
        val draft = Draft(attack, phantom, plannedPlace, plannedBase, slot)
        return resolve(view, wire, draft, rayIgnore, crystalSlot, places)
    }

    /**
     * The decision table of the class KDoc. Recurses at most once, with the attack dropped (no reachable aim for
     * it); [places] is the placement demotion chain of [decide], handed on to [aimUse] and never restarted.
     *
     * ### Why the base falls back to the direct placement
     * A base wins the tick's single use by the MinGain rule, but it only pays off once it is actually clicked. When
     * its click face cannot be aimed at right now, keeping it would spend the tick's re-aim on a click that has to
     * wait while a crystal placement the current wire rotation ALREADY hits was ready - and with a base that stays
     * unaimable (no reachable face at all) the crystal cycle would starve for good. So the base is dropped for this
     * tick, the placement takes the use, and the base is re-proposed next tick with its rotation already on the way.
     *
     * That fallback keys on the wire, not on the outgoing rotation, and it has to: PLAN runs before
     * `RotationManager.update()` (RotationManager.kt:319-324), so the only rotation it can compare two candidates
     * with is the one already sent. "Already hits" therefore stays "hits without a re-aim", exactly as before.
     *
     * And because it keys on the wire, the tick files NO look point. The base's aim used to be requested here on
     * the grounds that it is "worth starting now", which was sound while the executor's placement fallback was the
     * full wire: whatever the rotation moved to, `wire` was still a candidate and the placement still landed. It
     * is not sound against Grim's real candidate set for a placement - `(yaw, pitch)` and `(lastYaw, pitch)` only,
     * no `(lastYaw, lastPitch)` (`RotationPlace.java:39-53`, which is why [ActionExecutor.byPlaceAim] exists) - so
     * steering towards the base moves the pitch off the one the placement was verified with and the executor, now
     * correctly, refuses to send it. The tick would deliver neither action, which is precisely the plan-that-
     * cannot-execute the module's doctrine forbids.
     *
     * With no look point the module HOLDS (`ModuleCrystalAura.requestRotation` -> `AuraRotation.hold`, which
     * re-files `wire` itself), so `outgoing == wire` and EXECUTE reproduces PLAN's verification exactly. The base
     * is not abandoned: it is re-proposed every tick and takes the aim on the first tick with no wire-ready
     * placement to spend it on - speculative aiming gets the spare ticks, never a tick with a verified action in
     * it.
     */
    @Suppress("LongParameterList")
    private fun resolve(
        view: WorldView,
        wire: Rotation,
        draft: Draft,
        rayIgnore: (Entity) -> Boolean,
        crystalSlot: HotbarItemSlot?,
        places: Iterator<PlaceCandidate>,
    ): TickPlan {
        val eye = view.eye
        if (draft.hasAttack && !attackHits(eye, wire, draft, rayIgnore)) {
            val look = attackLook(eye, wire, draft, rayIgnore)
                ?: return resolve(view, wire, draft.withoutAttack(), rayIgnore, crystalSlot, places)
            // Rule 2: the attack is offered and the executor decides it against the rotation this look produces.
            // The use waits - see the chain-removal note in the class KDoc.
            return plan(draft, attackAttempt = true, useAttempt = false, look = look, placeHit = null)
        }

        val useHit = useHit(eye, wire, draft, rayIgnore)
        if (useHit == null && draft.base != null && draft.place != null && crystalSlot != null) {
            val withoutBase = draft.withoutBase(crystalSlot)
            val placeHit = useHit(eye, wire, withoutBase, rayIgnore)
            if (placeHit != null) {
                return plan(withoutBase, withoutBase.hasAttack, true, look = null, placeHit = placeHit)
            }
        }

        // Rules 1 and 3: both planned actions are offered; a use the wire does not reach yet gets the look point,
        // and the executor decides it against the rotation that look produces - in THIS tick.
        return if (!draft.hasUse || useHit != null) {
            plan(draft, draft.hasAttack, draft.hasUse, look = null, placeHit = useHit)
        } else {
            aimUse(eye, wire, draft, rayIgnore, places)
        }
    }

    /**
     * Rule 3 for a use the wire does not reach yet: the look point search, with the CRYSTAL PLACEMENT demoted to the
     * ranker's next candidate whenever no rotation reaches the current one.
     *
     * [useLook] returns null when no sampled ray on the block is both reachable and robust - the block is behind a
     * wall, edge-on, or simply out of the rotation's way. That verdict does not change by itself: without a
     * demotion the tick plans a use it cannot aim at, sends nothing, and the next tick's `PlaceSearch.best()`
     * answers with the very same block, which is a stall for as long as the geometry lasts. Demoting spends the
     * tick on the runner-up instead, and the wire test is redone for it first - the demoted block may already be
     * hit, in which case the tick needs no re-aim at all.
     *
     * Bound: [MAX_PLACE_LOOKS] look point searches per tick, i.e. the primary candidate plus at most three
     * demotions, each of them at most [BUDGET] rays. A base look is never demoted (there is one base plan, and
     * `resolve` already falls back from an unaimable base to the direct placement).
     */
    private fun aimUse(
        eye: Vec3,
        wire: Rotation,
        draft: Draft,
        rayIgnore: (Entity) -> Boolean,
        places: Iterator<PlaceCandidate>,
    ): TickPlan {
        var current = draft
        var searches = 1
        while (true) {
            val look = useLook(eye, wire, current, rayIgnore)
            if (look != null || current.base != null || searches >= MAX_PLACE_LOOKS) {
                return plan(current, current.hasAttack, current.hasUse, look, placeHit = null)
            }

            val next = places.nextOrNull()
                ?: return plan(current, current.hasAttack, current.hasUse, look = null, placeHit = null)
            current = current.withPlace(next)
            searches++
            val hit = useHit(eye, wire, current, rayIgnore)
            if (hit != null) {
                return plan(current, current.hasAttack, current.hasUse, look = null, placeHit = hit)
            }
        }
    }

    /**
     * True when a crystal placed at [candidate] would be thrown away by one of ours that is still waiting to be
     * detonated.
     *
     * An explosion removes every crystal within the blast WITHOUT detonating it: `EndCrystal.hurtServer` only runs
     * `level.explode` when the damage source is not `IS_EXPLOSION` (`EndCrystal.java:88-99`), and `ServerExplosion`
     * hurts every entity inside `dist <= 1.0` (`ServerExplosion.java:181-190`). So while one of our crystals is in
     * flight or standing unbroken, every further crystal placed inside its blast is simply lost - the aura would
     * stack two or three crystals per detonation and pay for all of them.
     *
     * Not counted, because they are gone before the placement is handled (the attack packet precedes the use packet
     * inside one tick): the crystal [detonating] this tick, the [phantom]'s pending placement, and anything the
     * ledger already marks attacked or chain-removed. Anything out of [BreakSettings.range] is not counted either -
     * a standing crystal and the pending placement alike - because we cannot detonate it, so waiting on it would
     * stall the aura instead of protecting it. The pending branch used to skip that range test, which froze every
     * placement within 12 blocks for the whole pending window whenever one landed out of detonation reach.
     *
     * Every other standing crystal is one the aura is NOT detonating this tick, and the rule used to treat each of
     * them as one about to go: refused by the break rules (MinDamage, MaxSelfDamage, AntiSuicide), refused by
     * [canAim], or simply not the best. Counting such a crystal refused every placement within 12 blocks of it -
     * i.e. around the target - for as long as it stood, up to the 600 ticks an own mark lives
     * (`CrystalLedger.OWN_TICKS`): the 2026-10-03 report, a placement and then seconds of `idle`. Not counting it
     * at all is wrong the other way: a crystal placed for where the target WILL be (`Place.Predict`) is routinely
     * refused at its spawn, because the break side predicts a shorter way ahead, and it becomes the attack once
     * the target arrives - placing in its blast meanwhile stacks crystals one detonation then throws away (the
     * refutation audit's counterexample to the first cut of this fix). So it protects its blast for
     * `Standing.waitTicks` after its spawn and not after - generous on purpose: the window is counted from the
     * spawn, so it outlasts the place prediction horizon by the time the placement took to come back.
     *
     * The one exception is `Standing.held`: the break candidate while the break DELAY alone keeps it from being hit,
     * which is a crystal that will go off within `Break.Delay`. While the executor's attack gate is what holds it
     * ([decide]'s `attackGated`), it gets the age rule like any other: that gate can stay shut indefinitely (a
     * piercing weapon in the main hand), and a crystal held through it would freeze the placements that could
     * re-open it. Known residue, `SameTick` only: an older crystal the break rules accept but rank below the one
     * attacked this tick no longer protects its blast, so a placement there can be thrown away by its detonation.
     */
    @Suppress("LongParameterList")
    private fun wastedByOwnBlast(
        view: WorldView,
        ledger: CrystalLedger,
        candidate: PlaceCandidate,
        standing: Standing,
        detonating: EndCrystal?,
        phantom: PhantomAttack?,
    ): Boolean {
        val reachSq = breakRange * breakRange
        val pending = ledger.pendingPlacement()
        if (pending != null && phantom == null) {
            val obsidian = pending.above.below()
            if (ExplosionMath.crystalBox(obsidian).distanceToSqr(view.eye) < reachSq &&
                inBlast(ExplosionMath.crystalCenter(obsidian), candidate.center)
            ) {
                return true
            }
        }

        return view.crystals.any { crystal ->
            crystal !== detonating &&
                ledger.isOwn(crystal.id) &&
                !ledger.isAttacked(crystal.id) &&
                !ledger.isExpectedRemoved(crystal.id) &&
                crystal.boundingBox.distanceToSqr(view.eye) < reachSq &&
                inBlast(crystal.position(), candidate.center) &&
                standing.protects(crystal, ledger)
        }
    }

    /** Both points inside one explosion radius, using the vanilla feet-distance cut-off. */
    private fun inBlast(a: Vec3, b: Vec3): Boolean = ExplosionMath.distanceFraction(a, b) <= 1.0

    /**
     * IdPredict phantom for the newest pending placement, or null when disabled, nothing is pending, no id was
     * predicted, the placement is no longer FRESH, the id is already attacked, or the entity already exists
     * client-side (then it is a real candidate).
     *
     * ### Why freshness, and why it is the last guard against a kick
     * A predicted id is a guess: `CrystalLedger.predictNextId` (`state/CrystalLedger.kt:208-214`) answers
     * `maxSeenEntityId + 1` while the last three observed spawns had consecutive ids, and the only other check is
     * `level.getEntity(id) == null` - which is trivially true for an id no entity ever gets. A guess that lands on
     * an `ItemEntity`, an `ExperienceOrb`, the player itself or a non-attackable `AbstractArrow` does not miss, it
     * DISCONNECTS us: `ServerGamePacketListenerImpl.handleAttack` answers such a target with
     * `multiplayer.disconnect.invalid_entity_attacked`
     * (`net/minecraft/server/network/ServerGamePacketListenerImpl.java:1770-1775`), after the reach and
     * `PIERCING_WEAPON` tests (`:1768-1769`) have already passed.
     *
     * The window in which the guess is defensible is the one where OUR crystal's spawn packet is still in flight,
     * i.e. the tick right after the placement went out. [PENDING_MAX_AGE] therefore admits a `PendingPlacement`
     * stamped this tick or the previous one (`ledger.tick - pending.tick` in `0..1`); the placement is stamped in
     * `CrystalLedger.markPlaced` from `ActionExecutor.placeCrystal`, so the earliest a phantom can fire is the tick
     * after the placement. Older pendings are exactly the ones whose id most likely belongs to a foreign entity by
     * now, and they keep drifting further away with every entity the server spawns. The setting is opt-in and
     * default OFF; this narrows it, it does not replace [CrystalLedger.predictNextId]'s own evidence.
     */
    private fun phantomAttack(ledger: CrystalLedger): PhantomAttack? {
        if (!brk.idPredict) return null
        val pending = ledger.pendingPlacement() ?: return null
        val id = pending.predictedId ?: return null
        val age = ledger.tick - pending.tick
        if (age !in 0..PENDING_MAX_AGE || ledger.isAttacked(id) || world.getEntity(id) != null) return null

        val obsidian = pending.above.below()
        return PhantomAttack(id, ExplosionMath.crystalBox(obsidian), ExplosionMath.crystalCenter(obsidian))
    }

    /**
     * Whether [crystal] can be attacked from [view]'s eye this tick: the [wire] already picks it, or the look point
     * search finds a rotation that does. These are the two tests [resolve] runs before it drops an attack for want
     * of an aim - `attackHits`, then `attackLook`, whose last resort is this same `forCrystal` search on the same
     * inputs - so an attack admitted here is never dropped there, which is the precondition [decide] relies on.
     */
    fun canAim(view: WorldView, wire: Rotation, crystal: EndCrystal, rayIgnore: (Entity) -> Boolean): Boolean {
        val eye = view.eye
        val pickable = ignoreExcept(crystal, rayIgnore)
        if (RayTests.hitsCrystal(eye, wire, crystal, breakRange, pickable, throughBlocks) != null) {
            return true
        }
        val slack = AuraRotation.slackDegrees(rotation)
        return LookPoints.forCrystal(eye, wire, crystal, breakRange, rayIgnore, slack, BUDGET, throughBlocks) != null
    }

    /**
     * Whether the crystal a placement on [obsidian] spawns could be attacked from where we stand: [canAimBox] on
     * `ExplosionMath.crystalBox(obsidian)`, the box the crystal will have (`EndCrystalItem.java:42` puts it at
     * `(above.x + 0.5, above.y, above.z + 0.5)`).
     *
     * `PlaceSearch` admits a cell by the BOX distance of that crystal to the eye, which is only a lower bound of the
     * pick: a crystal whose reachable part is the far side of the target's own body, a corner sticking into the
     * break range, or (with `ThroughBlocks` off) a crystal behind a block passes it and then stands there with no
     * ray that picks it. That crystal is not merely lost: it is the one [canAim] keeps refusing tick after tick.
     * With `ThroughBlocks` on the attack rays ignore blocks as well (`RayTests.hitsCrystal`), so a wall refuses
     * nothing here: the first cut of this test (2026-10-03) applied the crystal's occlusion to placements that
     * setting had sent through that very wall, and so refused nearly every one of them. The test is the geometry
     * of NOW, read twice too strictly or too loosely: we may have stepped by the time the spawn arrives, and the
     * target's body is tested where it stands, not where `Predict` expects it.
     * [ignore] is the caller's pick filter, which must also pass the crystals this tick's attack removes - the
     * attacked one and the ones its blast chain-removes, gone before the placement is handled (see
     * `ModuleCrystalAura.placeColumnIgnore`).
     *
     * The hub runs it on the placement ranking BEFORE that ranking's head becomes the MinGain reference of
     * BasePlace and AutoMine: a head nobody could detonate would otherwise set the bar their plans must beat.
     */
    fun canAimSpawn(eye: Vec3, wire: Rotation, obsidian: BlockPos, ignore: (Entity) -> Boolean): Boolean =
        canAimBox(eye, wire, ExplosionMath.crystalBox(obsidian), ignore)

    /**
     * [canAim] for a crystal box with no entity behind it (an IdPredict phantom, a placement not made yet):
     * `RayTests.hitsPhantomBox` on the wire, else `LookPoints.forPhantomBox`. For a phantom these are again
     * exactly the tests of [resolve] (`attackHits`, then the phantom branch of `attackLook`).
     */
    private fun canAimBox(eye: Vec3, wire: Rotation, box: AABB, ignore: (Entity) -> Boolean): Boolean {
        if (RayTests.hitsPhantomBox(eye, wire, box, breakRange, ignore, throughBlocks) != null) return true
        val slack = AuraRotation.slackDegrees(rotation)
        return LookPoints.forPhantomBox(eye, wire, box, breakRange, ignore, slack, BUDGET, throughBlocks) != null
    }

    /** A slot can be used: present and, with [SwitchMode.NONE], already held server-side. */
    private fun usable(slot: HotbarItemSlot?): Boolean =
        slot != null && (place.switchMode != SwitchMode.NONE || isHeld(slot))

    /** The offhand is always held; a hotbar slot is held when it is the slot the server has selected. */
    private fun isHeld(slot: HotbarItemSlot): Boolean =
        slot.isOffHand || SilentHotbar.serversideSlot == slot.hotbarIndex

    private fun attackHits(eye: Vec3, wire: Rotation, draft: Draft, rayIgnore: (Entity) -> Boolean): Boolean {
        val crystal = draft.attack?.crystal
        if (crystal != null) {
            val pickable = ignoreExcept(crystal, rayIgnore)
            return RayTests.hitsCrystal(eye, wire, crystal, breakRange, pickable, throughBlocks) != null
        }

        val phantom = draft.phantom ?: return false
        return RayTests.hitsPhantomBox(eye, wire, phantom.box, breakRange, rayIgnore, throughBlocks) != null
    }

    /**
     * The wire hit of the planned use (base click or placement block), null when there is none or it misses.
     *
     * A base with a null `face` is the replace-in-place form: `BlockPlaceContext` ignores the hit direction once
     * `replaceClicked` is true (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-61`), so the
     * face must NOT be constrained - the shape `MineJob` already uses for its own nullable-face click.
     */
    private fun useHit(eye: Vec3, wire: Rotation, draft: Draft, rayIgnore: (Entity) -> Boolean): BlockHitResult? {
        val ignore = ignoreAlso(draft.attack?.crystal, rayIgnore)
        val base = draft.base
        if (base != null) {
            return RayTests.hitsBlock(eye, wire, base.clickPos, baseRange, ignore, throughBlocks)
                ?.takeIf { base.face == null || it.direction == base.face }
        }

        val place = draft.place ?: return null
        return RayTests.hitsBlock(eye, wire, place.pos, placeRange, ignore, throughBlocks)
    }

    /** Rule 2: a rotation that performs the attack, serving the placement as well when possible. */
    private fun attackLook(eye: Vec3, wire: Rotation, draft: Draft, rayIgnore: (Entity) -> Boolean): LookPoint? {
        val slack = AuraRotation.slackDegrees(rotation)
        val crystal = draft.attack?.crystal
        val place = draft.place
        if (crystal != null && place != null) {
            val serving = LookPoints.forPlacement(
                eye, wire, place.pos, placeRange, crystal, breakRange, ignoreAlso(crystal, rayIgnore), slack, BUDGET,
                throughBlocks,
            )
            if (serving != null && serving.servesAttack) return serving
        }
        if (crystal != null) {
            return LookPoints.forCrystal(eye, wire, crystal, breakRange, rayIgnore, slack, BUDGET, throughBlocks)
        }

        val phantom = draft.phantom ?: return null
        return LookPoints.forPhantomBox(eye, wire, phantom.box, breakRange, rayIgnore, slack, BUDGET, throughBlocks)
    }

    /**
     * Rule 3: a rotation for the deferred use. The attack (if any) runs this tick, so no crystal has to be served,
     * but the attacked crystal is ignored by the block ray - it is gone when the rotation lands.
     */
    private fun useLook(eye: Vec3, wire: Rotation, draft: Draft, rayIgnore: (Entity) -> Boolean): LookPoint? {
        val slack = AuraRotation.slackDegrees(rotation)
        val ignore = ignoreAlso(draft.attack?.crystal, rayIgnore)
        val base = draft.base
        if (base != null) return baseLook(eye, wire, base, ignore, slack)

        val place = draft.place ?: return null
        return LookPoints.forPlacement(
            eye, wire, place.pos, placeRange, null, breakRange, ignore, slack, BUDGET, throughBlocks
        )
    }

    /**
     * The base half of [useLook], in whichever of vanilla's two placement forms the plan carries.
     *
     * Relative form ([BasePlan.face] non-null): only that face of the neighbour will do, so `forFace`.
     * Replace-in-place form (null): the obsidian goes into the clicked cell whatever direction comes back
     * (`net/minecraft/world/item/context/BlockPlaceContext.java:31-37, 54-61`), so ANY visible face serves and
     * `forPlacement` is the right search - sampled on `RayTests.pickBox`, the outline actually standing there,
     * because a block that does not fill its cell would otherwise be aimed at through empty air.
     */
    private fun baseLook(
        eye: Vec3,
        wire: Rotation,
        base: BasePlan,
        ignore: (Entity) -> Boolean,
        slack: Float,
    ): LookPoint? {
        val face = base.face
        if (face != null) {
            return LookPoints.forFace(eye, wire, base.clickPos, face, baseRange, ignore, slack, BUDGET, throughBlocks)
        }

        val box = RayTests.pickBox(base.clickPos) ?: return null
        return LookPoints.forPlacement(
            eye, wire, base.clickPos, baseRange, null, breakRange, ignore, slack, BUDGET, throughBlocks,
            sampleBox = box,
        )
    }

    private fun plan(
        draft: Draft,
        attackAttempt: Boolean,
        useAttempt: Boolean,
        look: LookPoint?,
        placeHit: BlockHitResult?,
    ): TickPlan = TickPlan(
        attack = draft.attack,
        phantomAttack = draft.phantom,
        place = draft.place,
        base = draft.base,
        attackAttempt = attackAttempt,
        useAttempt = useAttempt,
        look = look,
        slot = draft.slot,
        hand = draft.slot?.useHand ?: InteractionHand.MAIN_HAND,
        placeHit = placeHit ?: look?.toBlockHit(),
    )

    /** `BlockHitResult(location, direction, pos, inside = false)` (`BlockHitResult.java:17`) of a block look point. */
    private fun LookPoint.toBlockHit(): BlockHitResult? {
        val face = face ?: return null
        val pos = blockPos ?: return null
        return BlockHitResult(point, face, pos, false)
    }

    /** [ignore] with [entity] itself always pickable - a ray meant to hit it must be allowed to. */
    private fun ignoreExcept(entity: EndCrystal, ignore: (Entity) -> Boolean): (Entity) -> Boolean =
        { other -> other !== entity && ignore(other) }

    /** [ignore] extended by [entity] (this tick's attack crystal), unchanged when there is none. */
    private fun ignoreAlso(entity: EndCrystal?, ignore: (Entity) -> Boolean): (Entity) -> Boolean =
        if (entity == null) ignore else { other -> other === entity || ignore(other) }

    private companion object {
        /** Rays one look point search may trace. */
        const val BUDGET = 96

        /**
         * How many placement look point searches one tick may spend ([aimUse]): the primary candidate plus at most
         * three demotions, `4 * BUDGET` rays in the worst case. The demotion only ever runs when the wire misses
         * AND the search found no reachable point, which is rare; the bound exists so that a pathological tick -
         * a whole ranked list of blocks behind the same wall - cannot turn one tick into a full ray budget times
         * `ExplosionRanker.MAX_FINALISTS`. Four is enough for the case it is meant for (the best cells clustered on
         * one unreachable face of an obsidian pile, the next one a step around it) and cheap enough to never show.
         */
        const val MAX_PLACE_LOOKS = 4

        /**
         * How many ticks old the pending placement of an IdPredict phantom may be ([phantomAttack]): 1, i.e. this
         * tick or the previous one - the window in which our own spawn packet can still be in flight.
         */
        const val PENDING_MAX_AGE = 1
    }
}

/** The actions under consideration before the wire tests decide what the tick aims at. */
private class Draft(
    val attack: BreakCandidate?,
    val phantom: PhantomAttack?,
    val place: PlaceCandidate?,
    val base: BasePlan?,
    val slot: HotbarItemSlot?,
) {

    val hasAttack: Boolean
        get() = attack != null || phantom != null

    val hasUse: Boolean
        get() = place != null || base != null

    fun withoutAttack(): Draft = Draft(null, null, place, base, slot)

    /** The same tick with the base dropped, so the direct placement it was shadowing can run instead. */
    fun withoutBase(slot: HotbarItemSlot?): Draft = Draft(attack, phantom, place, null, slot)

    /** The same tick with the placement demoted to the ranker's next candidate ([TickPlanner.aimUse]). */
    fun withPlace(place: PlaceCandidate): Draft = Draft(attack, phantom, place, base, slot)
}

/**
 * What [TickPlanner.wastedByOwnBlast] needs to know about the standing crystals this tick: [held], the break
 * candidate while only the break delay keeps it from being hit (null otherwise), and [waitTicks], how long after
 * its spawn any other crystal still protects its blast.
 */
private class Standing(val held: EndCrystal?, val waitTicks: Int) {

    /** Whether [crystal] (own, unattacked, in reach) is to be treated as one that will still be detonated. */
    fun protects(crystal: EndCrystal, ledger: CrystalLedger): Boolean {
        if (crystal === held) return true
        val age = ledger.ownAge(crystal.id) ?: return false
        return age <= waitTicks
    }
}

/** The next element, or null when the chain is exhausted - `firstOrNull()` for an iterator we keep walking. */
private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
