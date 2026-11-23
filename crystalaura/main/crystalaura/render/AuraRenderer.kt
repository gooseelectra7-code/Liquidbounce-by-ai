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

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.ExplosionMath
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.RenderSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.calc.VictimState
import net.ccbluex.liquidbounce.render.FULL_BOX
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraAnim
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraAnimColor
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraBlastDome
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraText
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraEase
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraGlow
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.DomeSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.GlowSettings
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.SoftStyle
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftBox
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftOutlineRing
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftSegments
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable snapshot of everything the renderers need, built once per PLAN tick by the module and read by the
 * render handlers between ticks. Every field may be null / zero: the module renders while it has no plan at all.
 *
 * - [target] / [targetState]: the victim of the decided action and its frozen [VictimState] (the placement's
 *   `placeState`, else the attack's `breakState`).
 * - [killZoneRadius]: `AuraTargetRenderer.killZoneRadius` for [targetState]; `0f` means "do not draw the ring".
 * - [bestDamage] / [selfDamage]: effective damage of the decided action against the victim and against us.
 * - [lethal]: `DamageResult.isLethalFor(targetState)` for that damage.
 * - [placePos] / [basePos] / [attackBox]: `TickPlan.placePos` / `basePos` / `attackBox`.
 */
class AuraVisuals(
    val target: LivingEntity?,
    val targetState: VictimState?,
    val killZoneRadius: Float,
    val bestDamage: Float,
    val selfDamage: Float,
    val lethal: Boolean,
    val placePos: BlockPos?,
    val basePos: BlockPos?,
    val attackBox: AABB?,
)

/**
 * World overlay of CrystalAura's current plan ("Detonation"): the block the next crystal will be placed on with
 * the block an obsidian base will be placed at, the hitbox of the crystal that will
 * be attacked, the target's kill-zone ring ([AuraTargetRenderer]), the expanding shockwave of every attack we
 * actually sent, the forge burning where those attacks pile up ([AuraForge]), and the damage figures over the
 * placement.
 *
 * Pure rendering, mirrors no vanilla logic. Block boxes follow the ModuleAirPlace idiom
 * (`ModuleAirPlace.kt:139-147`); the world-space attack box is anchored at its min corner so the vertex
 * coordinates stay float-precise far from the origin (`ModuleItemRadius.kt:676-684`). The damage text is projected
 * and drawn in an `OverlayRenderEvent` exactly like `ModuleTNTTimer` (`ModuleTNTTimer.kt:152-153, 221-229`).
 *
 * ### Animation
 * Nothing in here pops. [render] first pushes this frame's snapshot into the tweens of [AuraAnim] ([update]) and
 * then draws whatever they currently show, so every element survives the snapshot going `null`: the boxes glide
 * from the old block to the new one instead of teleporting, the damage figures roll
 * towards their new value, and everything fades rather than blinking. `RenderSettings.animationSpeed` is copied
 * into `AuraAnim.speed` once per frame and scales all of it.
 */
@Suppress("TooManyFunctions", "LargeClass")
object AuraRenderer {

    /**
     * Live shockwaves as a ring buffer of at most [SHOCKWAVE_RING] entries: centre and wall-clock start. A slot is
     * cleared once its ripple finished; the oldest entry is overwritten when the buffer wraps.
     */
    private val shockwaveCenters = arrayOfNulls<Vec3>(SHOCKWAVE_RING)
    private val shockwaveStarts = LongArray(SHOCKWAVE_RING)
    private var shockwaveCursor = 0

    /** Wall clock of the most recent attack, shared with the shockwaves; drives the attack-box flash. */
    private var lastAttackAt = 0L

    /** The planned placement block and the obsidian base block, each gliding to wherever the plan moved it. */
    private val placeBox = GlidingBlock()
    private val baseBox = GlidingBlock()

    /** The two damage figures as rolling counters, so the numbers ease to their new value instead of jumping. */
    private val damageAnim = AuraAnim(TEXT_ROLL_MS, 0f, AuraEase.OUT_CUBIC)
    private val selfDamageAnim = AuraAnim(TEXT_ROLL_MS, 0f, AuraEase.OUT_CUBIC)

    /** Scale "pop" of the damage text: snapped to [TEXT_POP_PEAK] and springing back to 1 on every new position. */
    private val textPop = AuraAnim(TEXT_POP_MS, 1f, AuraEase.SPRING)

    /** Cold -> hot (or lethal) text colour, cross-faded like the ring's. */
    private val textColor = AuraAnimColor(TEXT_COLOR_MS, Color4b.WHITE, AuraEase.OUT_CUBIC)

    /**
     * Endpoints of the orbit's vertical ticks, refilled in place every frame and handed to [drawSoftSegments] as
     * one mesh - the crown must not allocate, it is rebuilt for every live ripple of every frame.
     */
    private val orbitSegments = FloatArray(ORBIT_POINTS * ORBIT_STRIDE)

    /** Lifetime of one refraction dome; longer than the drawn ripple, which is a much smaller effect. */
    private const val DOME_MS = 520L

    /** Starts one ripple at [center]; called by the module when an attack packet really went out. */
    fun recordShockwave(center: Vec3) {
        val now = System.currentTimeMillis()
        shockwaveCenters[shockwaveCursor] = center
        shockwaveStarts[shockwaveCursor] = now
        shockwaveCursor = (shockwaveCursor + 1) % SHOCKWAVE_RING
        lastAttackAt = now

        // The same detonation also drives the screen-space dome. Its radius is the crystal's real blast radius,
        // so the refraction covers exactly the volume the explosion damaged (`ExplosionMath.DOUBLE_RADIUS`).
        AuraBlastDome.add(this, center, ExplosionMath.DOUBLE_RADIUS, DOME_MS)

        // And the same detonation heats the forge at that block: one hit is a flicker, a sustained cycle pins the
        // site at full heat (`render/AuraForge.kt`, `record`).
        AuraForge.record(center)
    }

    /** Drops every live shockwave and every animation; called from the module's own reset. */
    fun reset() {
        shockwaveCenters.fill(null)
        shockwaveCursor = 0
        lastAttackAt = 0L
        placeBox.reset()
        baseBox.reset()
        damageAnim.snap(0f)
        selfDamageAnim.snap(0f)
        textPop.snap(1f)
        textColor.snap(Color4b.WHITE)
        AuraTargetRenderer.reset()
        AuraGlow.release()
        AuraForge.release()
        AuraBlastDome.release(this)
    }

    /**
     * Draws the planned boxes, the target ring, the forge and the live shockwaves, then the two
     * screen-space layers.
     *
     * The geometry is emitted TWICE when [RenderSettings.glow] is on: once straight onto the world target, and
     * once into [AuraGlow]'s offscreen target, which blurs it and composites a soft halo underneath. Passing the
     * same lambda to both is what keeps the halo registered with the shape - it is the Halo ESP's arrangement
     * (`EspHaloMode.kt`), and the reason that mode is the one thing in the client that does not read as a decal.
     *
     * The two passes differ in exactly ONE number. `AuraGlow` builds its halo out of the colours it is handed, so
     * a fill at the module's default alpha of 40/255, scaled down again by the Fresnel term, hands the blur a
     * nearly black source and the halo has nothing to work with - which is why the glow could not be seen in game
     * whatever `GlowStrength` was set to. The glow pass therefore uses a style whose
     * [SoftStyle.alphaGain] is [RenderSettings.glowGain]; the world pass keeps `1` and draws at exactly the alpha
     * the user chose. Hence the lambda takes the style rather than closing over it.
     *
     * [AuraBlastDome] and [AuraForge]'s plume run last and independently of any geometry: they displace the
     * finished scene, so they have nothing to draw into the world pass.
     */
    fun render(event: WorldRenderEvent, visuals: AuraVisuals?, settings: RenderSettings) {
        AuraAnim.speed = settings.animationSpeed
        val now = System.currentTimeMillis()
        update(visuals, settings, now)

        val forge = forgeSettings(settings)
        val shockwaves = settings.shockwave && hasLiveShockwave(now)
        val forgeLit = forge.core && AuraForge.peakHeat(now) > 0f
        val hasGeometry = shockwaves || forgeLit || hasPlanResidual() || AuraTargetRenderer.hasResidual()

        if (hasGeometry) {
            val geometry: WorldRenderEnvironment.(SoftStyle) -> Unit = { style ->
                drawPlan(visuals, settings, style, now)
                AuraTargetRenderer.draw(this, settings, style, event.partialTicks, now)
                AuraForge.draw(this, forge, style, now)
                if (shockwaves) {
                    drawShockwaves(settings, style, now)
                }
            }

            val worldStyle = softStyle(settings, 1f)
            renderEnvironmentForWorld(event.matrixStack) { geometry(worldStyle) }

            if (settings.glow) {
                val glowStyle = softStyle(settings, settings.glowGain)
                AuraGlow.render(event, glowSettings(settings), hasGeometry = true) { geometry(glowStyle) }
            }
        }

        if (settings.dome) {
            AuraBlastDome.render(event, domeSettings(settings))
        }
        AuraForge.render(event, forge, now)
    }

    /** The frame's glow parameters, read once so the halo cannot disagree with the geometry it wraps. */
    private fun glowSettings(settings: RenderSettings) = GlowSettings(
        radius = settings.glowRadius,
        strength = settings.glowStrength,
        falloff = settings.glowFalloff,
        alpha = settings.glowAlpha,
    )

    /** The frame's refraction parameters for [AuraBlastDome]. */
    private fun domeSettings(settings: RenderSettings) = DomeSettings(
        strength = settings.domeStrength,
        amplitude = settings.domeAmplitude,
        ripples = settings.domeRipples,
    )

    /**
     * The frame's forge parameters. `Forge` off takes the plume with it: the plume is the air over a pool that
     * is not being drawn, and half the effect on its own reads as a bug rather than as a setting.
     */
    private fun forgeSettings(settings: RenderSettings) = ForgeSettings(
        core = settings.forge,
        haze = settings.forge && settings.forgeHaze,
        strength = settings.forgeStrength,
        radius = settings.forgeRadius,
        cold = settings.forgeCold,
        hot = settings.forgeHot,
    )

    /**
     * The frame's [SoftStyle] at a given [alphaGain], built once per pass and threaded through every draw below,
     * so the settings are read exactly once per frame and every element of the overlay is guaranteed to use the
     * same look. Only the gain ever differs between the world pass and the glow pass - see [render].
     */
    private fun softStyle(settings: RenderSettings, alphaGain: Float) = SoftStyle(
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

    /**
     * Pushes this frame's snapshot into every tween; draws nothing. Called unconditionally from [render], so an
     * element that just lost its plan starts fading out instead of being dropped on the spot.
     */
    private fun update(visuals: AuraVisuals?, settings: RenderSettings, nowMs: Long) {
        if (placeBox.retarget(visuals?.placePos)) {
            // The chosen block moved: bump the text so the new figure announces itself.
            textPop.snap(TEXT_POP_PEAK)
            textPop.target = 1f
        }
        baseBox.retarget(visuals?.basePos)

        if (visuals != null) {
            damageAnim.target = visuals.bestDamage
            selfDamageAnim.target = visuals.selfDamage
            textColor.target = targetColor(visuals, settings)
        }
        AuraTargetRenderer.update(visuals, settings, nowMs)
    }

    /** True while a box is still visible after the plan is gone. */
    private fun hasPlanResidual(): Boolean = placeBox.visible || baseBox.visible

    /**
     * Damage figures over the planned placement: the effective damage to the target, coloured by profit, then the
     * self damage in a dimmer colour. Both numbers are the ROLLING values, the whole block is scaled by the pop and
     * faded with the placement marker, and the anchor follows the marker's glide. Skipped when the anchor is behind
     * the camera (`WorldToScreen.calculateScreenPos` returns null, `utils/render/WorldToScreen.kt:80-84`).
     *
     * Takes no plan: everything it draws lives in the tweens [render] already pushed, so the text keeps fading out
     * correctly for a frame that has no plan at all.
     */
    fun renderOverlay(event: OverlayRenderEvent, settings: RenderSettings) {
        if (!settings.damageText) return
        val alpha = placeBox.alpha.value
        if (alpha <= 0f) return
        val screen = WorldToScreen.calculateScreenPos(placeBox.center().add(0.0, TEXT_HEIGHT, 0.0)) ?: return
        drawDamageText(event.context, screen, alpha.coerceIn(0f, 1f))
    }

    private fun drawDamageText(ctx: GuiGraphicsExtractor, screen: Vec3f, alpha: Float) {
        val size = TEXT_SIZE * textPop.value.coerceAtLeast(TEXT_MIN_POP)
        val targetText = format(damageAnim.value)
        val selfText = SELF_SEPARATOR + format(selfDamageAnim.value)
        val left = screen.x - (textWidth(targetText, size) + textWidth(selfText, size)) / 2f
        val top = screen.y - textLineHeight(size) / 2f

        val pen = drawText(ctx, targetText, left, top, size, textColor.value.fade(alpha))
        drawText(ctx, selfText, pen, top, size, SELF_TEXT_COLOR.fade(alpha))
    }

    /** Placement / base / attack boxes, behind the `Box` toggle. */
    private fun WorldRenderEnvironment.drawPlan(
        visuals: AuraVisuals?,
        settings: RenderSettings,
        style: SoftStyle,
        nowMs: Long,
    ) {
        if (settings.box) {
            val fill = settings.fillColor
            drawGlidingBlock(placeBox, fill, settings.outlineColor, style)
            drawGlidingBlock(baseBox, fill.alpha(fill.a / 2), settings.outlineColor, style)
            visuals?.attackBox?.let { drawWorldBox(it, attackOutline(settings, nowMs), style) }
        }
    }

    /**
     * One gliding full block: the drawn corner is the tween between the block we came from and the block we are
     * going to, the outline follows the fade, and the face alpha blooms once on arrival ([GlidingBlock.fillBoost]).
     *
     * Through [drawSoftBox], so the six faces are Fresnel-weighted and vertically graded and the 12 edges are
     * feathered ribbons instead of one-pixel wires (`render/SoftGeometry.kt`, `drawSoftBox`).
     */
    private fun WorldRenderEnvironment.drawGlidingBlock(
        block: GlidingBlock,
        fill: Color4b,
        outline: Color4b,
        style: SoftStyle,
    ) {
        val alpha = block.alpha.value
        if (alpha <= 0f) return
        // `Color4b.fade` can only DIM, and the arrival bloom has to be able to push the face past its configured
        // alpha, so the face channel is computed directly (`render/engine/type/Color4b.kt:170-176`).
        val face = (fill.a * alpha * block.fillBoost).toInt().coerceIn(0, MAX_CHANNEL)
        drawSoftBox(
            origin = block.corner(),
            box = FULL_BOX,
            fill = fill.with(a = face),
            outline = outline.fade(alpha.coerceIn(0f, 1f)),
            style = style,
        )
    }

    /** Outline of a world-space [box], translated so its min corner is the local origin. */
    private fun WorldRenderEnvironment.drawWorldBox(box: AABB, outline: Color4b, style: SoftStyle) {
        val origin = Vec3(box.minX, box.minY, box.minZ)
        drawSoftBox(
            origin = origin,
            box = box.move(-origin.x, -origin.y, -origin.z),
            fill = null,
            outline = outline,
            style = style,
        )
    }

    /**
     * Outline colour of the crystal we are about to hit, flashed towards white for [ATTACK_FLASH_MS] from the
     * moment the attack packet went out (the same [lastAttackAt] stamp the shockwave uses).
     */
    private fun attackOutline(settings: RenderSettings, nowMs: Long): Color4b {
        val base = settings.attackColor
        if (lastAttackAt == 0L) return base
        val rest = 1f - (nowMs - lastAttackAt).toFloat() / ATTACK_FLASH_MS
        if (rest <= 0f || rest > 1f) return base

        val flash = AuraEase.OUT_CUBIC.ease(rest)
        val lit = (base.a + (MAX_CHANNEL - base.a) * flash).toInt().coerceIn(0, MAX_CHANNEL)
        return base.interpolateTo(Color4b.WHITE, (flash * ATTACK_FLASH_MIX).toDouble()).with(a = lit)
    }

    /** True while any slot still has a ripple inside its [SHOCKWAVE_MS] window. */
    private fun hasLiveShockwave(now: Long): Boolean {
        for (index in shockwaveCenters.indices) {
            if (shockwaveCenters[index] != null && now - shockwaveStarts[index] < SHOCKWAVE_MS) {
                return true
            }
        }
        return false
    }

    /**
     * One ripple per live shockwave, plus the optional orbit. Finished slots are released here, so a paused game
     * never keeps a stale ripple.
     */
    private fun WorldRenderEnvironment.drawShockwaves(settings: RenderSettings, style: SoftStyle, now: Long) {
        val color = settings.shockwaveColor
        for (index in shockwaveCenters.indices) {
            val center = shockwaveCenters[index] ?: continue
            val age = (now - shockwaveStarts[index]).toFloat()
            if (age < 0f || age >= SHOCKWAVE_MS) {
                shockwaveCenters[index] = null
                continue
            }
            drawRipple(center, color, age, style)
            if (settings.orbit) {
                drawOrbit(center, color, age, style)
            }
        }
    }

    /**
     * Two concentric rings so the wave reads as having depth: the outer one eases out over the full
     * [SHOCKWAVE_MS] with an `(1 - t)^2` alpha (the original ripple), the inner one snaps out on
     * [AuraEase.OUT_BACK] over the much shorter [SHOCKWAVE_INNER_MS] and is gone before the outer is half-way.
     *
     * Both are [drawSoftOutlineRing]s: a world-space band that thins with distance and fades to nothing on both
     * rims, rather than the constant two-pixel SDF stroke that made a four-block wave read as a wire circle.
     */
    private fun WorldRenderEnvironment.drawRipple(center: Vec3, color: Color4b, age: Float, style: SoftStyle) {
        val progress = age / SHOCKWAVE_MS
        val rest = 1f - progress
        drawSoftOutlineRing(
            center,
            SHOCKWAVE_RADIUS * AuraEase.OUT_CUBIC.ease(progress),
            color.fade(rest * rest),
            style,
        )

        val innerProgress = age / SHOCKWAVE_INNER_MS
        if (innerProgress >= 1f) return
        drawSoftOutlineRing(
            center,
            SHOCKWAVE_RADIUS * SHOCKWAVE_INNER_SCALE * AuraEase.OUT_BACK.ease(innerProgress),
            color.fade((1f - innerProgress) * SHOCKWAVE_INNER_ALPHA),
            style,
        )
    }

    /**
     * The competitor client's orbital flourish (`rockstar-client-src/src/main/java/rockstar/client/
     * TargetEspModule.java:186-200`): [ORBIT_POINTS] points every [ORBIT_STEP_DEG] degrees at
     * `(cos, sin)(angle + t * spin) * radius * scale`, each raised by `base + amplitude * |sin(angle)|`, so the ring
     * looks like a crown that turns and shrinks as the wave decays. The ticks go into the reusable
     * [orbitSegments] buffer and out as ONE feathered-ribbon mesh ([drawSoftSegments]) - no allocation inside the
     * loop, and no one-pixel wires left in the module.
     */
    private fun WorldRenderEnvironment.drawOrbit(center: Vec3, color: Color4b, age: Float, style: SoftStyle) {
        val progress = age / SHOCKWAVE_MS
        val rest = 1f - progress
        val faded = color.fade(rest * rest * ORBIT_ALPHA)
        if (faded.isTransparent) return

        val radius = SHOCKWAVE_RADIUS * (ORBIT_SCALE - ORBIT_SHRINK * progress)
        val spin = progress * ORBIT_SPIN_DEG
        for (step in 0 until ORBIT_POINTS) {
            val degrees = step * ORBIT_STEP_DEG
            val theta = (degrees + spin) * DEG_TO_RAD
            val x = (cos(theta) * radius).toFloat()
            val z = (sin(theta) * radius).toFloat()
            val height = ORBIT_BASE_HEIGHT + ORBIT_HEIGHT * abs(sin(degrees * DEG_TO_RAD)).toFloat()
            val base = step * ORBIT_STRIDE
            orbitSegments[base] = x
            orbitSegments[base + 1] = 0f
            orbitSegments[base + 2] = z
            orbitSegments[base + 3] = x
            orbitSegments[base + 4] = height
            orbitSegments[base + 5] = z
        }
        drawSoftSegments(center, orbitSegments, ORBIT_POINTS, faded, style, ORBIT_WIDTH_SCALE)
    }

    /**
     * Cold -> hot by `AuraTargetRenderer.heatRatio`, at full alpha so the glyphs stay readable. A placement that
     * kills outright uses `RenderSettings.attackColor` instead: the approved setting list has no separate lethal
     * colour, and that one is already the module's "this crystal ends it" red.
     */
    private fun targetColor(visuals: AuraVisuals, settings: RenderSettings): Color4b {
        if (visuals.lethal) return settings.attackColor.with(a = MAX_CHANNEL)
        val ratio = AuraTargetRenderer.heatRatio(visuals).toDouble()
        return settings.ringCold.interpolateTo(settings.ringHot, ratio).with(a = MAX_CHANNEL)
    }

    /** Advanced width of [text] at pixel height [size], through whichever text path is active. */
    private fun textWidth(text: String, size: Float): Float =
        AuraText.width(ModuleCrystalAura.damageFont, text, size)

    /** Height of one line at pixel height [size]; the two text paths measure differently, so both go through one. */
    private fun textLineHeight(size: Float): Float = AuraText.lineHeight(ModuleCrystalAura.damageFont, size)

    /** Draws [text] with its top-left at ([drawX], [drawY]) and returns the pen X after the last glyph. */
    private fun drawText(
        ctx: GuiGraphicsExtractor,
        text: String,
        drawX: Float,
        drawY: Float,
        size: Float,
        color: Color4b,
    ): Float = AuraText.draw(ModuleCrystalAura.damageFont, ctx, text, drawX, drawY, size, color)

    private fun format(value: Float): String = AuraText.format(value)

    /**
     * A full block whose DRAWN position is a tween: retargeting it records where it is right now and springs from
     * there to the new block, so a plan that hops one block over glides instead of teleporting. [alpha] fades the
     * whole thing in and out with the plan, and [fillBoost] blooms the face alpha once, at the end of the glide.
     */
    private class GlidingBlock {

        private val glide = AuraAnim(GLIDE_MS, 1f, AuraEase.SPRING)
        private val bloom = AuraAnim(BLOOM_MS, 0f, AuraEase.OUT_CUBIC)

        /** Opacity of the whole block: 1 while the plan names it, 0 afterwards. */
        val alpha = AuraAnim(FADE_MS, 0f, AuraEase.OUT_CUBIC)

        private var from = Vec3.ZERO
        private var to = Vec3.ZERO
        private var key: BlockPos? = null
        private var bloomed = true

        /** True while anything of this block is still on screen. */
        val visible: Boolean
            get() = alpha.value > 0f

        /** Extra face-alpha factor of the arrival bloom; `1` once it decayed. */
        val fillBoost: Float
            get() = 1f + bloom.value * BLOOM_GAIN

        /**
         * Pushes [pos] (which may be `null`) and returns true when the named block actually CHANGED this frame.
         * The arrival bloom is fired on the first frame the glide is settled, not when it starts.
         */
        fun retarget(pos: BlockPos?): Boolean {
            if (!bloomed && glide.settled) {
                bloomed = true
                bloom.snap(1f)
                bloom.target = 0f
            }
            alpha.target = if (pos == null) 0f else 1f
            if (pos == null || pos == key) {
                return false
            }

            from = if (key == null) cornerOf(pos) else corner()
            to = cornerOf(pos)
            key = pos.immutable()
            bloomed = false
            glide.snap(0f)
            glide.target = 1f
            return true
        }

        /** The lower-north-west corner currently drawn at. */
        fun corner(): Vec3 {
            val t = glide.value.toDouble()
            return Vec3(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
                from.z + (to.z - from.z) * t,
            )
        }

        /** The centre of the currently drawn block, i.e. the anchor of the damage text. */
        fun center(): Vec3 = corner().add(HALF_BLOCK, 0.0, HALF_BLOCK)

        fun reset() {
            glide.snap(1f)
            bloom.snap(0f)
            alpha.snap(0f)
            from = Vec3.ZERO
            to = Vec3.ZERO
            key = null
            bloomed = true
        }

        private fun cornerOf(pos: BlockPos) = Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
    }

    /** Lifetime and end radius of one shockwave ripple, and how many may be alive at once. */
    private const val SHOCKWAVE_MS = 260f
    private const val SHOCKWAVE_RADIUS = 4.0f
    private const val SHOCKWAVE_RING = 24

    /** The inner ring: shorter, smaller and dimmer than the outer one. */
    private const val SHOCKWAVE_INNER_MS = 170f
    private const val SHOCKWAVE_INNER_SCALE = 0.55f
    private const val SHOCKWAVE_INNER_ALPHA = 0.7f

    /** The orbit crown around a ripple; [ORBIT_SPIN_DEG] is how far it turns over one ripple's whole life. */
    private const val ORBIT_POINTS = 18
    private const val ORBIT_STEP_DEG = 20.0
    private const val ORBIT_SPIN_DEG = 40f
    private const val ORBIT_SCALE = 1.15f
    private const val ORBIT_SHRINK = 0.45f
    private const val ORBIT_ALPHA = 0.55f
    private const val ORBIT_BASE_HEIGHT = 0.1f
    private const val ORBIT_HEIGHT = 0.45f
    private const val DEG_TO_RAD = PI / 180.0

    /** Floats per orbit tick in [orbitSegments] (two endpoints), and how wide its ribbon is drawn. */
    private const val ORBIT_STRIDE = 6
    private const val ORBIT_WIDTH_SCALE = 0.75f

    /** Flash of the attacked crystal's outline: how long, and how far towards white it pulls. */
    private const val ATTACK_FLASH_MS = 180f
    private const val ATTACK_FLASH_MIX = 0.8f

    /** Damage text: anchored one and a bit blocks over the centre of the placement block. */
    private const val TEXT_HEIGHT = 1.6
    private const val TEXT_SIZE = 11.0f
    private const val SELF_SEPARATOR = " / "
    private val SELF_TEXT_COLOR = Color4b(190, 190, 190, 200)

    /** Tween lengths of the plan overlay, before `AuraAnim.speed`. */
    private const val GLIDE_MS = 200L
    private const val BLOOM_MS = 260L
    private const val FADE_MS = 180L
    private const val TEXT_ROLL_MS = 180L
    private const val TEXT_POP_MS = 200L
    private const val TEXT_COLOR_MS = 220L

    /** Peak of the text pop, the floor its spring undershoot is clamped at, and the arrival bloom's face gain. */
    private const val TEXT_POP_PEAK = 1.18f
    private const val TEXT_MIN_POP = 0.5f
    private const val BLOOM_GAIN = 1.4f

    private const val HALF_BLOCK = 0.5
    private const val MAX_CHANNEL = 255
}
