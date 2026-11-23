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

package net.ccbluex.liquidbounce.features.module.modules.combat.aura.render

/**
 * Every knob of the soft look in one place, so a call site reads as geometry and not as a parameter list. The
 * consuming module's render settings map onto this one-to-one (`CrystalConfig.kt:348-392` for CrystalAura,
 * `AnchorConfig.kt` for AutoAnchor).
 *
 * @param enabled `false` restores the ORIGINAL hard-edged look: every entry point below falls straight through
 *   to the stock helper it replaced (`render/RenderShortcuts.kt:321-339` for boxes, `:456-486` for rings), so
 *   the toggle is a true A/B and not a second, dimmer version of the same drawing.
 * @param outlineWidth World-space width of an outline ribbon, in blocks. This is the FULL width including both
 *   feather zones, so `0.02` is a two-centimetre line that keeps its thickness at any distance instead of
 *   collapsing to the one-pixel wire `ClientRenderPipelines.Lines` produces.
 * @param feather Fraction of a band's half-width spent fading, `0..1`. `0` gives a solid ribbon with hard sides
 *   (thick, but still aliased), `1` gives a pure triangular falloff with no solid core at all, and the default
 *   `0.5` keeps a solid centre with a soft shoulder on each side - that shoulder is what removes the hard border.
 * @param fresnelPower Exponent of the grazing-angle term, `f^fresnelPower`. Higher pulls the bright rim tighter
 *   against the silhouette (glassier, more volumetric); `0` disables the effect and every face is painted flat.
 * @param faceMin Alpha floor of a face seen exactly head-on, `0..1`. This is the single term that makes a filled
 *   box read as a VOLUME rather than a decal: at `0.15` the face you look straight at almost vanishes while the
 *   faces you graze stay bright, which is what the eye reads as depth.
 * @param gradientTop Alpha multiplier at the TOP edge of a face relative to its bottom edge. `< 1` fades upwards
 *   (the default, so nothing is one flat tone), `1` is a constant tone, `> 1` fades downwards instead.
 * @param fadeStart Distance in blocks at which the whole drawing starts losing alpha.
 * @param fadeEnd Distance in blocks at which it is fully gone. The interpolation is smoothstep, so nothing pops
 *   as the camera walks across either threshold.
 * @param alphaGain Multiplier applied to EVERY resolved alpha, on top of the distance fade. `1` is the honest
 *   look; above that the same drawing is emitted brighter without any of its colours being changed, which is what
 *   the glow pass needs: `AuraGlow` derives its halo from the colours routed into it, so a fill at the default
 *   alpha of 40/255 already scaled down to `faceMin` by the Fresnel term hands the blur a nearly black source and
 *   the halo has nothing to work with. The world pass keeps `1`, the pass routed into the halo uses
 *   `RenderSettings.glowGain` (`AuraRenderer.render`). Saturates at full alpha rather than wrapping.
 */
data class SoftStyle(
    val enabled: Boolean = true,
    val outlineWidth: Float = 0.02f,
    val feather: Float = 0.5f,
    val fresnelPower: Float = 1.4f,
    val faceMin: Float = 0.15f,
    val gradientTop: Float = 0.35f,
    val fadeStart: Float = 24f,
    val fadeEnd: Float = 64f,
    val alphaGain: Float = 1f,
)
