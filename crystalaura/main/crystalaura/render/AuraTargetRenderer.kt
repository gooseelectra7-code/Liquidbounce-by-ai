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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.render

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.RenderSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.BlockingState
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimPipeline
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimState
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraAnim
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraAnimColor
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraEase
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.SoftStyle
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftArc
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftRing
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.pulse
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.min

/**
 * The "Detonation" ground mark: everything this object draws is anchored at the TARGET's feet and encodes state
 * only a crystal aura knows.
 *
 * - **Blast ring**: a horizontal gradient band whose radius is the KILL-ZONE radius — the largest distance at which
 *   a crystal at full exposure would still deal at least `PlaceSettings.minDamage` to this victim ([killZoneRadius]).
 *   Its colour walks from `RenderSettings.ringCold` to `RenderSettings.ringHot` with `bestDamage / (health +
 *   absorption)` ([heatRatio]).
 * - **Kill segments**: the band is cut into `ceil((health + absorption) / bestDamage)` arcs (1..[MAX_SEGMENTS]), i.e.
 *   one arc per crystal still needed for the kill; they rotate slowly so the mark reads as alive. A single remaining
 *   crystal pulses the whole ring instead.
 * - **Shield wedge**: while the victim blocks and the crystal damage type is not bypassing the shield, the sector
 *   inside the ring in which `LivingEntity.applyItemBlocking` would eat the hit is filled.
 *
 * ### Animation
 * Nothing pops. [update] is called once per frame with the current snapshot and only pushes TARGETS into the
 * tweens of [AuraAnim]; [draw] reads what the tweens currently show. That split is what lets the ring keep fading
 * for [RING_FADE_MS] after the target is gone (the module hands us `null` and we still have a residual, see
 * [hasResidual]), lets the radius spring instead of jumping when the victim's armour changes, and lets a changed
 * segment count cross-fade between the old and the new arc layout instead of re-cutting the band in one frame.
 *
 * Pure rendering: nothing here touches the ledger, the plan or any packet path, and every input may be null.
 */
@Suppress("TooManyFunctions")
object AuraTargetRenderer {

    /** Ring radius, springing towards the current kill zone; only ever retargeted while the ring is alive. */
    private val radiusAnim = AuraAnim(RADIUS_MS, 0f, AuraEase.SPRING)

    /** Overall ring opacity: 1 while a target with a kill zone exists, 0 otherwise. Keeps the ring from blinking. */
    private val ringAlpha = AuraAnim(RING_FADE_MS, 0f, AuraEase.OUT_CUBIC)

    /** Cross-fade between the previous and the current arc layout; 1 = only the current layout is drawn. */
    private val segmentFade = AuraAnim(SEGMENT_MS, 1f, AuraEase.IN_OUT_CUBIC)

    /** One-shot brightening when the segment count DROPPED, i.e. a crystal of the kill was consumed. */
    private val consumeFlash = AuraAnim(CONSUME_FLASH_MS, 0f, AuraEase.OUT_CUBIC)

    /** Shield wedge opacity, on top of its own breathing sine. */
    private val wedgeAlpha = AuraAnim(WEDGE_FADE_MS, 0f, AuraEase.OUT_CUBIC)

    /** Cold -> hot band colour, cross-fading instead of stepping with the heat. */
    private val edgeColor = AuraAnimColor(COLOR_MS, Color4b.TRANSPARENT, AuraEase.OUT_CUBIC)

    /** Arc layout currently drawn and the one being faded out; `0` = one closed band (see [segmentCount]). */
    private var segments = 0
    private var previousSegments = 0

    /** Accumulated ring rotation and the clock it was last advanced at, so the heat may change its RATE. */
    private var spin = 0.0
    private var spinAt = 0L

    /** The victim we anchor to, and its last known feet for the fade-out after it is gone. */
    private var target: LivingEntity? = null
    private var lastFeet: Vec3? = null

    /** Facing and half-angle of the blocked sector, captured in [update] and drawn in [drawShieldWedge]. */
    private var wedgeFacing = 0.0
    private var wedgeHalf = 0.0

    /**
     * Largest distance `d` at which a crystal centred `d` blocks from the victim and seeing it fully would still
     * deal at least [minDamage] effective damage; `0f` when even a point-blank crystal fails the threshold (the
     * victim is effectively immune at the current settings, so the ring is not drawn).
     *
     * Found by [BISECTION_STEPS] bisection steps over `[0, ExplosionMath.DOUBLE_RADIUS]`, i.e. pure arithmetic and
     * NO raycasts: `ExplosionMath.rawDamage` depends on the two points only through their distance
     * (`ExplosionMath.kt:113-130`), so a probe centre at `feet + (d, 0, 0)` stands in for every direction, and the
     * whole pipeline is monotone decreasing in `d` (the raw damage falls with `d`, every later stage is monotone in
     * it), which is what makes bisection exact here.
     *
     * The shield is deliberately taken OUT of the probe (`blocking = null`): `VictimPipeline.blockedDamage` derives
     * the arc from `sourcePosition - blocking.position` (`VictimPipeline.kt:199-209`), so scoring the probe with it
     * would make the radius depend on the one direction the probe happens to use - the ring would collapse to zero
     * whenever due east sits inside the shield arc, and stay full size when it does not. The ring therefore shows
     * the reach against the victim's ARMOUR, and the blocked sector is drawn on top of it by [drawShieldWedge].
     *
     * `respectIFrames = false`: the ring answers "what does a crystal do to this victim", not "what does the next
     * crystal do inside the current invulnerability window" — the latter is already in the plan's own damage.
     */
    fun killZoneRadius(state: VictimState, minDamage: Float): Float {
        val victim = if (state.blocking == null) state else state.copy(blocking = null)
        if (damageAt(victim, 0.0) < minDamage) return 0f

        var low = 0.0
        var high = ExplosionMath.DOUBLE_RADIUS.toDouble()
        repeat(BISECTION_STEPS) {
            val middle = (low + high) / 2.0
            if (damageAt(victim, middle) >= minDamage) {
                low = middle
            } else {
                high = middle
            }
        }
        return low.toFloat()
    }

    /** Effective damage of a full-exposure crystal [distance] blocks east of [victim]. */
    private fun damageAt(victim: VictimState, distance: Double): Float {
        val center = victim.feet.add(distance, 0.0, 0.0)
        return VictimPipeline.apply(
            victim = victim,
            raw = ExplosionMath.rawDamage(victim.feet, center, 1f),
            exposure = 1f,
            sourcePosition = center,
            respectIFrames = false,
        ).effective
    }

    /**
     * `bestDamage / (health + absorption)`, clamped to `0..1`: 0 = a crystal barely scratches, 1 = one crystal
     * empties the victim. Drives the cold -> hot ring colour and the damage text colour.
     */
    fun heatRatio(visuals: AuraVisuals): Float {
        val state = visuals.targetState ?: return 0f
        val pool = state.health + state.absorption
        if (pool <= 0f) return 0f
        return (visuals.bestDamage / pool).coerceIn(0f, 1f)
    }

    /**
     * Pushes this frame's snapshot into the tweens; draws nothing. [visuals] is `null` whenever the module has no
     * plan, which starts the fade-out rather than dropping the ring.
     */
    fun update(visuals: AuraVisuals?, settings: RenderSettings, nowMs: Long) {
        advanceSpin(visuals, nowMs)
        target = visuals?.target
        val alive = isAlive(visuals, settings)
        ringAlpha.target = if (alive) 1f else 0f
        if (visuals == null || !alive) {
            updateWedge(null, settings)
            return
        }

        // Only retarget while alive, so a ring that is fading out keeps its last size instead of imploding.
        radiusAnim.target = visuals.killZoneRadius
        edgeColor.target = settings.ringCold.interpolateTo(settings.ringHot, heatRatio(visuals).toDouble())
        updateSegments(segmentCount(visuals, settings))
        updateWedge(visuals.targetState?.blocking, settings)
    }

    /** A ring exists only for a live victim with a non-empty kill zone and at least one of its parts enabled. */
    private fun isAlive(visuals: AuraVisuals?, settings: RenderSettings): Boolean {
        if (visuals?.target == null) return false
        return visuals.killZoneRadius > 0f && (settings.blastRing || settings.shieldWedge)
    }

    /**
     * Advances the rotation by real time rather than reading the clock directly, because the RATE depends on the
     * heat (hotter victim = visibly faster ring) and a rate change must not teleport the pattern.
     */
    private fun advanceSpin(visuals: AuraVisuals?, nowMs: Long) {
        val elapsed = if (spinAt == 0L) 0L else (nowMs - spinAt).coerceIn(0L, MAX_SPIN_STEP_MS)
        spinAt = nowMs
        val heat = if (visuals == null) 0f else heatRatio(visuals)
        val rate = SPIN_RAD_PER_SECOND * (1.0 + heat * SPIN_HEAT_GAIN)
        spin = (spin + elapsed / MILLIS_PER_SECOND * rate) % TWO_PI
    }

    /**
     * Starts a cross-fade whenever the arc layout changes, and a one-shot flash when the count DROPPED, which is
     * exactly the moment a crystal of the projected kill was consumed.
     */
    private fun updateSegments(count: Int) {
        if (count == segments) {
            return
        }
        if (count in 1 until segments) {
            consumeFlash.snap(1f)
            consumeFlash.target = 0f
        }
        previousSegments = segments
        segments = count
        segmentFade.snap(0f)
        segmentFade.target = 1f
    }

    /**
     * Captures the blocked sector for the frame. Centre = the victim's facing, `atan2(viewVector.z, viewVector.x)`:
     * `BlockingState.viewVector` is `calculateViewVector(0.0F, getYHeadRot())` (`calc/VictimState.kt:51-52`), a unit
     * vector with `y == 0`, and the ring parameterises the angle as `(cos, sin) -> (x, z)` exactly like
     * `drawGradientCircle` (`RenderShortcuts.kt:478-484`). Half-angle = the largest `horizontalBlockingAngle` of the
     * item's reductions (degrees; vanilla shield 90, `calc/VictimState.kt:33-34`), which is the angle
     * `DamageReduction.resolve` compares against (`calc/VictimPipeline.kt:222-227`).
     */
    private fun updateWedge(blocking: BlockingState?, settings: RenderSettings) {
        val degrees = wedgeDegrees(blocking)
        if (blocking == null || degrees == null || !settings.shieldWedge) {
            wedgeAlpha.target = 0f
            return
        }
        wedgeFacing = atan2(blocking.viewVector.z, blocking.viewVector.x)
        wedgeHalf = min(degrees.toDouble() * DEG_TO_RAD, PI)
        wedgeAlpha.target = 1f
    }

    /** Half-angle of the blocked sector in degrees, or `null` when nothing is actually blocked. */
    private fun wedgeDegrees(blocking: BlockingState?): Float? = blocking
        ?.takeUnless { it.bypassed }
        ?.reductions
        ?.maxOfOrNull { it.horizontalBlockingAngle }
        ?.takeIf { it > 0f }

    /** True while something is still on screen after the target is gone, so the caller keeps the frame alive. */
    fun hasResidual(): Boolean = !ringAlpha.settled || ringAlpha.value > 0f

    /** Drops every animation and the cached victim; called from `AuraRenderer.reset`. */
    fun reset() {
        radiusAnim.snap(0f)
        ringAlpha.snap(0f)
        segmentFade.snap(1f)
        consumeFlash.snap(0f)
        wedgeAlpha.snap(0f)
        edgeColor.snap(Color4b.TRANSPARENT)
        segments = 0
        previousSegments = 0
        spin = 0.0
        spinAt = 0L
        target = null
        lastFeet = null
    }

    /**
     * Draws the ring and the shield wedge at the interpolated feet of the victim, or - while fading out - at the
     * last feet we saw. [nowMs] is the wall clock shared with the shockwaves, [partialTicks] the frame's
     * `WorldRenderEvent.partialTicks`.
     */
    fun draw(
        env: WorldRenderEnvironment,
        settings: RenderSettings,
        style: SoftStyle,
        partialTicks: Float,
        nowMs: Long,
    ) {
        val alpha = ringAlpha.value
        val radius = radiusAnim.value
        if (alpha <= 0f || radius <= 0f) return

        val feet = target?.interpolateCurrentPosition(partialTicks) ?: lastFeet ?: return
        lastFeet = feet
        val edge = edgeColor.value
        // The soft helpers anchor themselves at `feet`, so the shared `withPositionRelativeToCamera` wrapper is
        // gone: each of them needs the camera in ITS own local frame to feather towards.
        if (settings.blastRing) {
            env.drawRing(feet, radius, edge, alpha, style, nowMs)
        }
        if (settings.shieldWedge) {
            env.drawShieldWedge(feet, radius, settings.shieldColor, alpha, style, nowMs)
        }
    }

    /**
     * How many crystals the victim still survives: `ceil((health + absorption) / bestDamage)`, clamped to
     * `1..`[MAX_SEGMENTS]. `0` means "no segmentation" — either the setting is off or no damage is known, and the
     * ring is drawn as one closed band.
     */
    private fun segmentCount(visuals: AuraVisuals, settings: RenderSettings): Int {
        if (!settings.killSegments || visuals.bestDamage <= 0f) return 0
        val state = visuals.targetState ?: return 0
        val pool = state.health + state.absorption
        if (pool <= 0f) return 0
        return ceil(pool / visuals.bestDamage).toInt().coerceIn(1, MAX_SEGMENTS)
    }

    /**
     * The band, cross-faded between the layout we are leaving and the one we are moving to, and brightened towards
     * white while [consumeFlash] decays. Both layouts share the same [spin], so the arcs slide instead of jumping.
     */
    private fun WorldRenderEnvironment.drawRing(
        feet: Vec3,
        radius: Float,
        edge: Color4b,
        alpha: Float,
        style: SoftStyle,
        nowMs: Long,
    ) {
        val flash = consumeFlash.value
        val hot = if (flash <= 0f) edge else edge.interpolateTo(Color4b.WHITE, (flash * FLASH_MIX).toDouble())
        val boost = 1f + flash * FLASH_ALPHA_GAIN
        val fade = segmentFade.value

        if (fade < 1f && previousSegments != segments) {
            drawRingLayer(feet, radius, hot, previousSegments, alpha * (1f - fade) * boost, style, nowMs)
        }
        drawRingLayer(feet, radius, hot, segments, alpha * fade * boost, style, nowMs)
    }

    /**
     * One arc layout: `segments <= 0` draws a closed gradient band, `1` draws it pulsing (the victim dies to the
     * next crystal), anything else draws that many rotating arcs separated by a small gap.
     *
     * All three go through the feathered helpers of `render/SoftGeometry.kt`, so the band no longer ends on a
     * hard rim at [radius]: the fade zones eat inwards from both edges and the kill-zone radius stays exact.
     */
    private fun WorldRenderEnvironment.drawRingLayer(
        feet: Vec3,
        radius: Float,
        edge: Color4b,
        count: Int,
        alpha: Float,
        style: SoftStyle,
        nowMs: Long,
    ) {
        // `Color4b.fade` can only DIM (`render/engine/type/Color4b.kt:170-176`), while the consume flash has to be
        // able to push the band past its configured alpha, so the channel is computed directly.
        val outer = edge.with(a = (edge.a * alpha).toInt().coerceIn(0, MAX_CHANNEL))
        if (outer.isTransparent) return
        val inner = (radius - BAND).coerceAtLeast(0f)

        if (count <= 0) {
            drawSoftRing(feet, radius, inner, outer, outer.with(a = 0), style)
            return
        }
        if (count == 1) {
            val pulsed = outer.fade(PULSE_BASE + PULSE_AMPLITUDE * pulse(nowMs, PULSE_PERIOD_MS))
            drawSoftRing(feet, radius, inner, pulsed, pulsed.with(a = 0), style)
            return
        }

        val fade = outer.with(a = 0)
        val step = TWO_PI / count
        val gap = min(GAP_RAD, step * MAX_GAP_FRACTION)
        for (index in 0 until count) {
            val from = spin + index * step
            drawSoftArc(feet, radius, inner, from, from + step - gap, outer, fade, style)
        }
    }

    /**
     * The sector in which our crystals are fully eaten by the victim's shield, drawn just inside the ring: it
     * fades in and out with [wedgeAlpha] and breathes on a [WEDGE_BREATHE_MS] sine, so a raised shield reads as a
     * live state rather than a static fill. The angles were captured in [updateWedge].
     */
    private fun WorldRenderEnvironment.drawShieldWedge(
        feet: Vec3,
        radius: Float,
        color: Color4b,
        alpha: Float,
        style: SoftStyle,
        nowMs: Long,
    ) {
        val breathe = WEDGE_BREATHE_BASE + WEDGE_BREATHE_AMP * pulse(nowMs, WEDGE_BREATHE_MS)
        val faded = color.fade((wedgeAlpha.value * alpha * breathe).coerceIn(0f, 1f))
        val outer = radius - BAND - SHIELD_GAP
        if (faded.isTransparent || outer <= 0f) return

        drawSoftArc(
            origin = feet,
            outerRadius = outer,
            innerRadius = (outer - SHIELD_BAND).coerceAtLeast(0f),
            fromRad = wedgeFacing - wedgeHalf,
            toRad = wedgeFacing + wedgeHalf,
            outerColor = faded,
            innerColor = faded.with(a = faded.a / SHIELD_INNER_DIVISOR),
            style = style,
        )
    }

    /** Bisection steps of [killZoneRadius]; 16 halvings of 12 blocks resolve the radius to ~0.2 mm. */
    private const val BISECTION_STEPS = 16

    /** Radial thickness of the ring band, in blocks. */
    private const val BAND = 0.35f

    /** Upper bound of [segmentCount] — beyond a dozen crystals the arcs stop being countable. */
    private const val MAX_SEGMENTS = 12

    /**
     * The arcs' angular resolution now lives with the geometry (`render/SoftGeometry.kt`, `arcSteps`), which
     * scales it with the radius instead of spending a fixed 2° step on a ring of any size.
     */
    private const val DEG_TO_RAD = PI / 180.0
    private const val TWO_PI = 2.0 * PI

    /** Gap between two kill segments, and its cap as a fraction of one segment's own span. */
    private const val GAP_RAD = 5.0 * DEG_TO_RAD
    private const val MAX_GAP_FRACTION = 0.25

    /**
     * Rotation of the segment pattern: 8° per second at a cold target, up to `1 + SPIN_HEAT_GAIN` times that when
     * one crystal would empty the victim. [MAX_SPIN_STEP_MS] caps the step a stalled frame may contribute, so a
     * loading pause does not whip the ring around.
     */
    private const val SPIN_RAD_PER_SECOND = 8.0 * DEG_TO_RAD
    private const val SPIN_HEAT_GAIN = 1.2
    private const val MAX_SPIN_STEP_MS = 100L

    /** Single-segment pulse: `alpha * (0.65 + 0.35 * pulse)` over a one-second period. */
    private const val PULSE_BASE = 0.65f
    private const val PULSE_AMPLITUDE = 0.35f
    private const val PULSE_PERIOD_MS = 1000L

    /** Placement of the shield wedge relative to the ring's inner edge, in blocks. */
    private const val SHIELD_GAP = 0.08f
    private const val SHIELD_BAND = 0.55f
    private const val SHIELD_INNER_DIVISOR = 3

    /** Shield-wedge breathing: alpha walks `0.7..1.0` over 1.4 s. */
    private const val WEDGE_BREATHE_MS = 1400L
    private const val WEDGE_BREATHE_BASE = 0.7f
    private const val WEDGE_BREATHE_AMP = 0.3f

    /** Tween lengths, before `AuraAnim.speed`. */
    private const val RADIUS_MS = 260L
    private const val RING_FADE_MS = 180L
    private const val SEGMENT_MS = 220L
    private const val CONSUME_FLASH_MS = 260L
    private const val WEDGE_FADE_MS = 200L
    private const val COLOR_MS = 220L

    /** How far the consume flash pulls the band towards white, and how much alpha it adds on top. */
    private const val FLASH_MIX = 0.85f
    private const val FLASH_ALPHA_GAIN = 0.6f

    private const val MILLIS_PER_SECOND = 1000.0
    private const val MAX_CHANNEL = 255
}
