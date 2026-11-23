/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.render

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraAnim
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraBlastDome
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraEase
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraGlow
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraText
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.DomeSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.GlowSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.SoftStyle
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftBox
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftOutlineRing
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.pulse
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.AnchorRenderSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.ModuleAutoAnchor
import net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor.calc.AnchorMath
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * World overlay of AutoAnchor's current cycle.
 *
 * It draws four things, and only the first is the sort of mark a crystal aura also has:
 *
 * 1. **The cell and the click target.** The cell an anchor is going into (or already stands in), and - when they
 *    differ - the block actually being clicked, which for a relative placement is a NEIGHBOUR and not the cell.
 *    Seeing those two apart is the difference between "it is aiming at the wrong block" and "that is how vanilla
 *    placement works".
 * 2. **The cycle ribbon.** Three stacked bars above the cell, one per interaction: place, charge, detonate. The
 *    current one is lit and pulsing, the finished ones are steady, the pending ones are faint. Because
 *    `PacketOrderI` allows exactly ONE block interaction per tick, a full cycle takes three ticks and the ribbon
 *    literally beats at the module's rhythm - 150 ms bottom to top. Under it, a ground arc closes as the phase's
 *    tick budget burns, so a cycle that is stuck is visible BEFORE `Cycle.MaxTicks` aborts it.
 * 3. **The charge pips.** Four notches reading `RespawnAnchorBlock.CHARGE`. The charge is predicted client-side
 *    (`ClientLevel.setBlock`'s prediction path), so a pip fills on the same frame the click leaves - and a
 *    stranger's anchor already sitting at charge 2 is visible as a one-click opportunity rather than a three-tick
 *    cycle.
 * 4. **The two danger rings** ([AnchorDanger]). The one piece here that is a diagnostic and not decoration: where
 *    an anchor still hurts the victim, and where one would hurt US more than `Place.MaxSelfDamage` permits. See
 *    that object's KDoc for why this is the module's most useful drawing.
 *
 * Plus the blast itself: the detonation feeds [AuraBlastDome], re-centred for the anchor's geometry (see
 * [recordBlast]).
 *
 * ### Standing down is drawn, not hidden
 * When the module refuses a tick, the overlay does not vanish - the last cell is drawn in a muted grey and the
 * reason is printed under it. Four of this module's gates (dimension, dud latch, glowstone in the offhand, another
 * module mid-interaction) are invisible from the world, and a module that goes silent for one of them is
 * indistinguishable from a broken one. This is the cheapest fix for that, and it is why [AnchorVisuals] carries a
 * cell even on a tick nothing was planned.
 *
 * Pure rendering. Nothing here touches the ledger, the flow or any packet path; every input may be null, and the
 * snapshot is only ever read, never written back. Modelled on `crystalaura/render/AuraRenderer.kt`, and sharing
 * its vocabulary through `combat/aura/render` rather than copying it.
 */
@Suppress("TooManyFunctions")
object AnchorRenderer {

    // ---- Tweens. Nothing pops: `update` retargets, `draw` reads what the tween currently shows. ----

    /** Drawn cell corner, springing between cells so a new candidate glides instead of teleporting. */
    private val cellX = AuraAnim(CELL_MOVE_MS, 0f, AuraEase.SPRING)
    private val cellY = AuraAnim(CELL_MOVE_MS, 0f, AuraEase.SPRING)
    private val cellZ = AuraAnim(CELL_MOVE_MS, 0f, AuraEase.SPRING)

    /** Overall opacity of the cell mark; 0 while there is no cell, which fades rather than drops it. */
    private val cellAlpha = AuraAnim(FADE_MS, 0f, AuraEase.OUT_CUBIC)

    /** Radii of the two danger rings, springing so an armour or a threshold change slides. */
    private val targetRadius = AuraAnim(RADIUS_MS, 0f, AuraEase.SPRING)
    private val selfRadius = AuraAnim(RADIUS_MS, 0f, AuraEase.SPRING)
    private val targetRingAlpha = AuraAnim(FADE_MS, 0f, AuraEase.OUT_CUBIC)
    private val selfRingAlpha = AuraAnim(FADE_MS, 0f, AuraEase.OUT_CUBIC)

    /** Damage figures, counted up rather than snapped, and a one-shot pop when the cell changes. */
    private val damageAnim = AuraAnim(TEXT_MS, 0f, AuraEase.OUT_CUBIC)
    private val selfDamageAnim = AuraAnim(TEXT_MS, 0f, AuraEase.OUT_CUBIC)
    private val textPop = AuraAnim(POP_MS, 1f, AuraEase.SPRING)

    /** Last snapshot, kept for the overlay pass and for the fade-out after the module goes idle. */
    private var last: AnchorVisuals? = null

    /** World position the last cell occupied, so the fade-out has somewhere to happen. */
    private var lastCell: BlockPos? = null

    /** Wall clock of the last detonation; the ribbon flashes briefly on it. */
    private var lastBlastAt = 0L

    /**
     * Records a detonation we actually sent, at [center] - the anchor's own block centre.
     *
     * The dome radius is the anchor's real blast radius (`AnchorMath.DOUBLE_RADIUS`, 10 against the crystal's 12),
     * so the refraction covers exactly the volume the explosion could damage and no more. The centre matters as
     * much as the radius: an anchor detonates from the middle of its own cell, not from a metre above a pedestal,
     * and `RespawnAnchorBlock.explode` has already removed the block by then - so the dome starts inside a cell
     * that is, at that instant, empty.
     */
    fun recordBlast(center: Vec3) {
        lastBlastAt = System.currentTimeMillis()
        AuraBlastDome.add(this, center, AnchorMath.DOUBLE_RADIUS, DOME_MS)
    }

    /**
     * Drops every animation and hands this module's domes back; called from the module's own reset.
     *
     * [AuraGlow] is deliberately NOT released here, and adding the call would be a regression rather than a
     * tidy-up: its targets are shared with the crystal aura, and while releasing them is harmless (a
     * `LazyRenderTargetHolder` rebuilds on the next use), doing it while the other aura is mid-frame throws away
     * a window-sized target it is about to ask for again. Its own idle counter hands them back when NOBODY has
     * drawn for a while, which is the correct condition. [AuraBlastDome] needs the opposite treatment - it holds
     * live per-caller state - which is why that one takes an owner.
     */
    fun reset() {
        last = null
        lastCell = null
        lastBlastAt = 0L
        cellAlpha.snap(0f)
        targetRingAlpha.snap(0f)
        selfRingAlpha.snap(0f)
        damageAnim.snap(0f)
        selfDamageAnim.snap(0f)
        textPop.snap(1f)
        AuraBlastDome.release(this)
    }

    /**
     * Draws the frame.
     *
     * The geometry is emitted TWICE while `Render.Glow` is on: once onto the world target, and once into
     * [AuraGlow]'s offscreen target, which blurs it into a halo underneath. Handing the same lambda to both is
     * what keeps the halo registered with the shape. The two passes differ in one number - the glow pass is
     * handed a higher `alphaGain`, because a 40/255 fill scaled down again by the Fresnel term gives the blur a
     * nearly black source to work with.
     */
    fun render(event: WorldRenderEvent, visuals: AnchorVisuals?, settings: AnchorRenderSettings) {
        AuraAnim.speed = settings.animationSpeed
        val now = System.currentTimeMillis()
        update(visuals, settings)

        val hasGeometry = cellAlpha.value > 0f || targetRingAlpha.value > 0f || selfRingAlpha.value > 0f
        if (hasGeometry) {
            val geometry: WorldRenderEnvironment.(SoftStyle) -> Unit = { style ->
                drawCell(settings, style, now)
                drawRings(settings, style, event.partialTicks)
            }

            renderEnvironmentForWorld(event.matrixStack) { geometry(softStyle(settings, 1f)) }

            if (settings.glow) {
                AuraGlow.render(event, glowSettings(settings), hasGeometry = true) {
                    geometry(softStyle(settings, settings.glowGain))
                }
            }
        }

        if (settings.dome) {
            AuraBlastDome.render(event, domeSettings(settings))
        }
    }

    /** The damage figures and, when the module is standing down, the reason - both over the cell. */
    fun renderOverlay(event: OverlayRenderEvent, settings: AnchorRenderSettings) {
        if (!settings.damageText) return
        val alpha = cellAlpha.value.coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val anchor = drawnCell().add(HALF, TEXT_HEIGHT, HALF)
        val screen = WorldToScreen.calculateScreenPos(anchor) ?: return
        drawText(event.context, screen, alpha)
    }

    // ---- Animation ----

    /** Pushes this frame's snapshot into the tweens; draws nothing. */
    private fun update(visuals: AnchorVisuals?, settings: AnchorRenderSettings) {
        last = visuals

        val cell = visuals?.cell
        if (cell != null) {
            if (cell != lastCell) {
                retargetCell(cell)
                textPop.snap(POP_FROM)
                textPop.target = 1f
            }
            lastCell = cell
        }
        cellAlpha.target = if (cell != null && settings.box) 1f else 0f

        damageAnim.target = visuals?.bestDamage ?: 0f
        selfDamageAnim.target = visuals?.selfDamage ?: 0f

        updateRings(visuals, settings)
    }

    /** Springs the drawn corner towards a new cell, or snaps it when the mark was invisible anyway. */
    private fun retargetCell(cell: BlockPos) {
        val far = lastCell?.let { it.distSqr(cell) > GLIDE_MAX_SQ } != false
        if (far || cellAlpha.value <= 0f) {
            cellX.snap(cell.x.toFloat())
            cellY.snap(cell.y.toFloat())
            cellZ.snap(cell.z.toFloat())
            return
        }
        cellX.target = cell.x.toFloat()
        cellY.target = cell.y.toFloat()
        cellZ.target = cell.z.toFloat()
    }

    /**
     * Retargets the two danger radii.
     *
     * They are recomputed only while a snapshot carries the state they are measured against; without one the
     * radius is LEFT where it is and only the alpha falls, so a ring fades out at its own size instead of
     * collapsing to a point first.
     */
    private fun updateRings(visuals: AnchorVisuals?, settings: AnchorRenderSettings) {
        val targetState = visuals?.targetState?.takeIf { settings.dangerRings }
        if (targetState != null) {
            targetRadius.target = AnchorDanger.reachRadius(targetState, visuals.minDamage)
        }
        targetRingAlpha.target = if (targetState != null && targetRadius.target > 0f) 1f else 0f

        val selfState = visuals?.selfState?.takeIf { settings.selfDanger }
        if (selfState != null) {
            selfRadius.target = AnchorDanger.reachRadius(selfState, visuals.maxSelfDamage)
        }
        selfRingAlpha.target = if (selfState != null && selfRadius.target > 0f) 1f else 0f
    }

    // ---- World geometry ----

    /** The cell mark: the box, the click target, the cycle ribbon, the countdown arc and the charge pips. */
    private fun WorldRenderEnvironment.drawCell(settings: AnchorRenderSettings, style: SoftStyle, nowMs: Long) {
        val alpha = cellAlpha.value.coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val visuals = last
        val muted = visuals?.standDown != null
        val corner = drawnCell()
        val fill = tint(settings.fillColor, muted)
        val outline = tint(settings.outlineColor, muted)

        drawSoftBox(
            origin = corner,
            box = FULL_BOX,
            fill = fill.fade(alpha),
            outline = outline.fade(alpha),
            style = style,
        )

        drawClickTarget(visuals, settings, style, alpha, muted)

        if (visuals == null) return
        if (settings.cycleRibbon) {
            drawRibbon(visuals, settings, style, corner, alpha, muted, nowMs)
            drawCountdown(visuals, settings, style, corner, alpha, muted)
        }
        if (settings.chargePips) {
            drawPips(visuals, settings, style, corner, alpha, muted)
        }
    }

    /**
     * Outline of the block actually clicked, whenever it is not the cell itself.
     *
     * For a relative placement the click lands on a NEIGHBOUR and the anchor appears in the cell next to it
     * (`BlockPlaceContext`), so the two boxes being apart is correct behaviour and not a mis-aim. There is
     * nothing to draw for the replace-in-place form, the charge or the detonation - all three click the cell.
     */
    private fun WorldRenderEnvironment.drawClickTarget(
        visuals: AnchorVisuals?,
        settings: AnchorRenderSettings,
        style: SoftStyle,
        alpha: Float,
        muted: Boolean,
    ) {
        val click = visuals?.clickPos ?: return
        if (click == visuals.cell) return

        drawSoftBox(
            origin = Vec3(click.x.toDouble(), click.y.toDouble(), click.z.toDouble()),
            box = FULL_BOX,
            fill = null,
            outline = tint(settings.clickColor, muted).fade(alpha * CLICK_ALPHA),
            style = style,
        )
    }

    /**
     * Three stacked bars above the cell - place, charge, detonate, bottom to top - showing where the cycle is.
     *
     * The index of the current step falls straight out of `AnchorFlow.remaining`, which is 3 / 2 / 1 for the
     * three phases: everything below it is done, everything above is still owed. One interaction may leave per
     * tick, so the stack fills at exactly one bar per tick and a full cycle is three.
     */
    @Suppress("LongParameterList")
    private fun WorldRenderEnvironment.drawRibbon(
        visuals: AnchorVisuals,
        settings: AnchorRenderSettings,
        style: SoftStyle,
        corner: Vec3,
        alpha: Float,
        muted: Boolean,
        nowMs: Long,
    ) {
        val current = CYCLE_STEPS - visuals.remaining
        val flash = if (nowMs - lastBlastAt < BLAST_FLASH_MS) BLAST_FLASH_GAIN else 1f
        val beat = pulse(nowMs, RIBBON_PULSE_MS)

        repeat(CYCLE_STEPS) { index ->
            val low = RIBBON_BASE + index * (RIBBON_HEIGHT + RIBBON_GAP)
            val box = AABB(
                RIBBON_INSET, low, RIBBON_INSET,
                1.0 - RIBBON_INSET, low + RIBBON_HEIGHT, 1.0 - RIBBON_INSET,
            )
            val weight = when {
                !visuals.engaged -> RIBBON_IDLE
                index < current -> RIBBON_DONE
                index == current -> RIBBON_LIVE_MIN + (1f - RIBBON_LIVE_MIN) * beat
                else -> RIBBON_PENDING
            }
            val color = tint(settings.ribbonColor, muted)
            drawSoftBox(
                origin = corner,
                box = box,
                fill = color.fade((alpha * weight * flash).coerceIn(0f, 1f)),
                outline = null,
                style = style,
            )
        }
    }

    /**
     * A ground arc closing as the current phase burns its tick budget - a cycle that has stopped making progress
     * is visible before `Cycle.MaxTicks` gives up on it, which is the difference between "slow" and "stuck".
     *
     * Drawn as an outline ring scaled by the remaining budget rather than a swept arc: the ring shrinking towards
     * the cell reads as a countdown from any angle, where a sweep only reads correctly from above.
     */
    private fun WorldRenderEnvironment.drawCountdown(
        visuals: AnchorVisuals,
        settings: AnchorRenderSettings,
        style: SoftStyle,
        corner: Vec3,
        alpha: Float,
        muted: Boolean,
    ) {
        if (!visuals.engaged) return
        val left = 1f - visuals.phaseProgress
        if (left >= 1f) return

        drawSoftOutlineRing(
            origin = corner.add(HALF, COUNTDOWN_Y, HALF),
            radius = COUNTDOWN_MIN + (COUNTDOWN_MAX - COUNTDOWN_MIN) * left,
            color = tint(settings.ribbonColor, muted).fade(alpha * COUNTDOWN_ALPHA),
            style = style,
        )
    }

    /**
     * Four notches on the cell's north face reading `RespawnAnchorBlock.CHARGE`, filled ones bright.
     *
     * Nothing is drawn for a cell that holds no anchor: a charge of zero and no anchor at all are different
     * states, and the placement bar of the ribbon already says which one this is.
     */
    @Suppress("LongParameterList")
    private fun WorldRenderEnvironment.drawPips(
        visuals: AnchorVisuals,
        settings: AnchorRenderSettings,
        style: SoftStyle,
        corner: Vec3,
        alpha: Float,
        muted: Boolean,
    ) {
        val charge = visuals.charge ?: return
        val color = tint(settings.pipColor, muted)

        repeat(MAX_CHARGE) { index ->
            val left = PIP_MARGIN + index * (PIP_WIDTH + PIP_GAP)
            val box = AABB(
                left, PIP_LOW, -PIP_DEPTH,
                left + PIP_WIDTH, PIP_HIGH, PIP_DEPTH,
            )
            val weight = if (index < charge) 1f else PIP_EMPTY
            drawSoftBox(
                origin = corner,
                box = box,
                fill = color.fade(alpha * weight),
                outline = null,
                style = style,
            )
        }
    }

    /**
     * The two danger rings, each on its own owner's feet.
     *
     * The target ring says "an anchor inside this hurts them enough to be worth placing"; the self ring says "an
     * anchor inside this hurts ME more than the gate allows". Where the two overlap is where the module can act,
     * and where the self ring swallows the target one is why it refuses - which is the whole point of drawing
     * them (see [AnchorDanger]).
     */
    private fun WorldRenderEnvironment.drawRings(
        settings: AnchorRenderSettings,
        style: SoftStyle,
        partialTicks: Float,
    ) {
        val visuals = last

        val targetAlpha = targetRingAlpha.value
        if (targetAlpha > 0f) {
            val feet = visuals?.target?.interpolateCurrentPosition(partialTicks) ?: visuals?.targetState?.feet
            if (feet != null) {
                val heat = AnchorDanger.heatRatio(visuals?.targetState, damageAnim.value)
                val color = settings.ringCold.interpolateTo(settings.ringHot, heat.toDouble())
                drawSoftOutlineRing(feet, targetRadius.value, color.fade(targetAlpha), style)
            }
        }

        val selfAlpha = selfRingAlpha.value
        if (selfAlpha > 0f) {
            val feet = mc.player?.interpolateCurrentPosition(partialTicks) ?: visuals?.selfState?.feet
            if (feet != null) {
                drawSoftOutlineRing(feet, selfRadius.value, settings.selfRingColor.fade(selfAlpha), style)
            }
        }
    }

    // ---- Overlay text ----

    /**
     * `target / self` over the cell, and under it the stand-down reason when there is one.
     *
     * The reason is the line that earns this pass: `Debug` already prints it to chat, but a player mid-fight
     * reads the world and not the chat log.
     */
    private fun drawText(ctx: GuiGraphicsExtractor, screen: Vec3f, alpha: Float) {
        val font = ModuleAutoAnchor.damageFont
        val size = TEXT_SIZE * textPop.value.coerceAtLeast(TEXT_MIN_POP)
        val damage = AuraText.format(damageAnim.value)
        val self = SELF_SEPARATOR + AuraText.format(selfDamageAnim.value)
        val lethal = last?.lethal == true

        val width = AuraText.width(font, damage, size) + AuraText.width(font, self, size)
        val left = screen.x - width / 2f
        val top = screen.y - AuraText.lineHeight(font, size) / 2f

        val head = if (lethal) LETHAL_COLOR else TEXT_COLOR
        val pen = AuraText.draw(font, ctx, damage, left, top, size, head.fade(alpha))
        AuraText.draw(font, ctx, self, pen, top, size, SELF_COLOR.fade(alpha))

        val reason = last?.standDown ?: return
        val small = size * REASON_SCALE
        val reasonLeft = screen.x - AuraText.width(font, reason, small) / 2f
        val reasonTop = top + AuraText.lineHeight(font, size) * REASON_GAP
        AuraText.draw(font, ctx, reason, reasonLeft, reasonTop, small, REASON_COLOR.fade(alpha))
    }

    // ---- Helpers ----

    /** The cell corner the tweens currently show, in world space. */
    private fun drawnCell(): Vec3 =
        Vec3(cellX.value.toDouble(), cellY.value.toDouble(), cellZ.value.toDouble())

    /** A standing-down overlay is drawn in grey: the shape stays legible, the intent reads as "not now". */
    private fun tint(color: Color4b, muted: Boolean): Color4b =
        if (muted) color.interpolateTo(MUTED, MUTE_MIX) else color

    private fun softStyle(settings: AnchorRenderSettings, alphaGain: Float) = SoftStyle(
        enabled = settings.soft,
        outlineWidth = settings.outlineWidth,
        feather = settings.feather,
        fresnelPower = settings.fresnel,
        faceMin = settings.faceMin,
        gradientTop = settings.gradient,
        fadeStart = settings.fadeStart,
        fadeEnd = settings.fadeEnd,
        alphaGain = alphaGain,
    )

    private fun glowSettings(settings: AnchorRenderSettings) = GlowSettings(
        radius = settings.glowRadius,
        strength = settings.glowStrength,
        falloff = settings.glowFalloff,
        alpha = settings.glowAlpha,
    )

    private fun domeSettings(settings: AnchorRenderSettings) = DomeSettings(
        strength = settings.domeStrength,
        amplitude = settings.domeAmplitude,
        ripples = settings.domeRipples,
    )

    // ---- Constants ----

    /** The unit cube, drawn relative to the cell's min corner so the vertices stay float-precise. */
    private val FULL_BOX = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    private const val HALF = 0.5
    private const val CYCLE_STEPS = 3
    private const val MAX_CHARGE = 4

    private const val CELL_MOVE_MS = 180L
    private const val FADE_MS = 220L
    private const val RADIUS_MS = 320L
    private const val TEXT_MS = 200L
    private const val POP_MS = 260L
    private const val DOME_MS = 900L

    /** Cells further apart than this snap instead of gliding; a glide across the map reads as a bug. */
    private const val GLIDE_MAX_SQ = 36.0
    private const val POP_FROM = 0.6f

    private const val RIBBON_BASE = 1.08
    private const val RIBBON_HEIGHT = 0.055
    private const val RIBBON_GAP = 0.045
    private const val RIBBON_INSET = 0.28
    private const val RIBBON_PULSE_MS = 520L
    private const val RIBBON_DONE = 0.85f
    private const val RIBBON_PENDING = 0.16f
    private const val RIBBON_IDLE = 0.1f
    private const val RIBBON_LIVE_MIN = 0.55f
    private const val BLAST_FLASH_MS = 220L
    private const val BLAST_FLASH_GAIN = 1.8f

    private const val COUNTDOWN_Y = 0.02
    private const val COUNTDOWN_MIN = 0.35f
    private const val COUNTDOWN_MAX = 0.95f
    private const val COUNTDOWN_ALPHA = 0.7f

    private const val PIP_MARGIN = 0.16
    private const val PIP_WIDTH = 0.11
    private const val PIP_GAP = 0.06
    private const val PIP_LOW = 0.24
    private const val PIP_HIGH = 0.5
    private const val PIP_DEPTH = 0.012
    private const val PIP_EMPTY = 0.14f

    private const val CLICK_ALPHA = 0.45f

    private const val TEXT_HEIGHT = 1.35
    private const val TEXT_SIZE = 11.0f
    private const val TEXT_MIN_POP = 0.5f
    private const val SELF_SEPARATOR = " / "
    private const val REASON_SCALE = 0.78f
    private const val REASON_GAP = 1.15f
    private const val MUTE_MIX = 0.75

    private val TEXT_COLOR = Color4b(235, 235, 235, 230)
    private val LETHAL_COLOR = Color4b(255, 120, 90, 240)
    private val SELF_COLOR = Color4b(190, 190, 190, 200)
    private val REASON_COLOR = Color4b(170, 170, 170, 180)
    private val MUTED = Color4b(150, 150, 150, 120)
}
