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
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.drawPlateItem
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * TNTTimer module
 *
 * 1:1 visual port of the SystemDLC `client.module.render.TntTimer`
 * (`.../system-source/src/main/java/client/module/render/TntTimer.java`, 148 L). Above every
 * primed TNT it draws a camera-facing rounded plate carrying a TNT icon and the remaining fuse
 * (seconds), distance-scaled by the SystemDLC `DistanceScale` curve and exponentially smoothed
 * per entity, exactly like the source `render` method (TntTimer.java:97-143).
 *
 * ## Render pattern (A) — projected screen-space plate
 * The SystemDLC original draws a true 3D billboard: it translates the pose to the lerped TNT
 * position + 1.5 blocks, applies its camera-facing `RotationBuffer`, scales the local geometry by
 * the (negative) distance-scale factor and rasterizes shape/item/text through its own shaders
 * (TntTimer.java:98-139). That render stack is NOT ported. Instead — as the sibling render modules
 * do ([net.ccbluex.liquidbounce.features.module.modules.render.ModuleDamageParticles],
 * [net.ccbluex.liquidbounce.features.module.modules.render.nametags.ModuleNametags]) — the plate
 * anchor is projected to the screen with [WorldToScreen.calculateScreenPos] and the whole plate is
 * drawn in an [OverlayRenderEvent] with our GUI render API (`drawRoundedRect` + `item` + vanilla
 * font). The distance-scale factor is a WORLD-space scale in the original, so it is converted back
 * to a screen-pixel scale by measuring how many screen pixels one world block spans along the
 * camera-up axis at the anchor depth (perpendicular to view, so it never foreshortens). That
 * reproduces the source's perspective sizing without its shader pipeline. Pattern (A) was chosen
 * over the 3D-billboard pattern (B) because our neighbouring render modules already implement it,
 * making it the low-risk faithful path.
 *
 * ## Fidelity notes / deviations
 * - The original suppresses the plate while its `StreamBypass.check6()` flag is on
 *   (TntTimer.java:56). We have no equivalent stream-bypass gate, so it is skipped.
 * - The fuse text uses a Cyrillic "с" suffix (seconds), reproducing `TimeFormat.getStringByDouble`
 *   (TimeFormat.java:8): `sec < 10 -> "%.1fс"`, else `"%dс"`.
 * - Text is drawn with the imported SystemDLC MSDF font ([SystemFont], family [SystemFont.Family.B])
 *   at the source's size 14 (TntTimer.java:108,137); the plate width tracks the measured font width
 *   (`42 + textWidth`, exactly the source layout).
 * - Only the timer plate is drawn: the previous module's entity-outline "glow" over primed TNT
 *   was removed (user request), so no `MixinEntityRenderer` hook references this module any more.
 */
object ModuleTNTTimer : ClientModule("TNTTimer", ModuleCategories.RENDER) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.tntTimer"

    /**
     * "Масштаб плашки" / plate scale — SliderSetting default 0.3, range 0.1..1.0, step 0.1
     * (TntTimer.java:40-41). Our float DSL has no step, so the range/default are kept and the step
     * dropped. Fed into the [SystemDlcTheme.distanceScale] curve as the `base` factor.
     */
    private val plateScale by float("PlateScale", 0.3F, 0.1F..1.0F)

    /**
     * Reused stack for the plate icon (source: `new ItemStack(Items.TNT)`, TntTimer.java:33).
     *
     * `by lazy`, not an eager field: `MixinEntityRenderer` touches `ModuleTNTTimer.INSTANCE` from
     * the entity-outline path, which can force this object's static init before the item component
     * registry is bound. Building the `ItemStack` there read TNT's default DataComponents too early
     * — `NullPointerException: Components not bound yet` in `<clinit>` → `ExceptionInInitializerError`
     * → `NoClassDefFoundError` on world join. Deferring construction to first render (components
     * long since bound) avoids the registry-freeze trap.
     */
    private val tntStack by lazy { ItemStack(Items.TNT) }

    /** Per-entity exponentially-smoothed scale (source `map`, TntTimer.java:34). */
    private val smoothedScale = HashMap<Int, Float>()

    /** Entity ids seen this frame, for map eviction (source `set`, TntTimer.java:35). */
    private val seen = HashSet<Int>()

    /** Wall-clock timestamp of the previous frame (source `time`, TntTimer.java:36,46). */
    private var lastFrameTime = System.currentTimeMillis()

    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        if (mc.level == null || mc.player == null) {
            return@handler
        }

        // Per-frame wall-clock delta, capped at 0.1 s (TntTimer.java:62-64).
        val now = System.currentTimeMillis()
        val dt = min((now - lastFrameTime) / 1000.0F, 0.1F)
        lastFrameTime = now
        // f2 = prev + (target - prev) * min(1, dt * 10) (TntTimer.java:105).
        val lerpFactor = min(1.0F, dt * 10.0F)

        val cameraPos = mc.gameRenderer.mainCamera.position()
        // Camera-up basis vector (unit, perpendicular to view) for the world->screen scale probe.
        val up = mc.gameRenderer.mainCamera.upVector()

        seen.clear()

        for (entity in world.entitiesForRendering()) {
            if (entity !is PrimedTnt) {
                continue
            }

            val id = entity.id
            seen.add(id)

            // fuse (ticks) -> seconds, clamped >= 0 (TntTimer.java:72-73).
            val seconds = max(0.0, entity.fuse / 20.0)
            val text = formatSeconds(seconds)

            // Distance from the (non-lerped) TNT position to the camera (TntTimer.java:76).
            val distance = entity.position().distanceTo(cameraPos)
            // clamp(dist * 0.0025, 0.02, 0.1) * plateScale (DistanceScale.java:5-6).
            val target = SystemDlcTheme.distanceScale(distance, plateScale)

            // Exponential per-entity smoothing; first sighting defaults prev to target (TntTimer.java:104-106).
            val prev = smoothedScale.getOrDefault(id, target)
            val f2 = prev + (target - prev) * lerpFactor
            smoothedScale[id] = f2

            // Plate anchor: lerped position + 1.5 blocks up (TntTimer.java:75,101).
            val anchor = entity.interpolateCurrentPosition(event.tickDelta).add(0.0, 1.5, 0.0)
            val anchorScreen = WorldToScreen.calculateScreenPos(anchor) ?: continue

            // World->screen scale: pixels spanned by the smoothed world scale along camera-up.
            val upPoint = anchor.add(up.x().toDouble(), up.y().toDouble(), up.z().toDouble())
            val upScreen = WorldToScreen.calculateScreenPos(upPoint) ?: continue
            val pixelsPerBlock = hypot(
                (upScreen.x - anchorScreen.x).toDouble(),
                (upScreen.y - anchorScreen.y).toDouble(),
            ).toFloat()
            val screenScale = f2 * pixelsPerBlock
            if (screenScale <= 0.0F) {
                continue
            }

            drawPlate(event, anchorScreen.x, anchorScreen.y, screenScale, text)
        }

        // Evict entities that vanished this frame: map.keySet().retainAll(set) (TntTimer.java:87).
        smoothedScale.keys.retainAll(seen)
    }

    /**
     * Draws one plate centred on the projected [anchor], with the exact SystemDLC local geometry
     * (TntTimer.java:108-139) scaled from local units to screen pixels by [screenScale].
     */
    private fun drawPlate(
        event: OverlayRenderEvent,
        anchorX: Float,
        anchorY: Float,
        screenScale: Float,
        text: String,
    ) {
        // Text width at the source's size 14, measured in the imported SystemDLC font (SystemFont.kt),
        // so the plate auto-fits the real glyphs (source `f3`, TntTimer.java:108-110).
        val textWidth = SystemDraw.textWidth(text, TEXT_SIZE, TEXT_FAMILY)
        val plateWidth = PLATE_BASE_WIDTH + textWidth // f5 = 42 + textWidth (TntTimer.java:111-112)
        val left = -plateWidth / 2.0F               // f7 = -f5 / 2 (TntTimer.java:114)

        with(event.context) {
            pose().pushMatrix()
            pose().translate(anchorX, anchorY)
            pose().scale(screenScale, screenScale)

            // Rounded plate: [-w/2, -13]..[w/2, 13], radius 8, fill = Theme.background(), no border
            // (TntTimer.java:113-121; f6 = max(14,16)+10 = 26, f8 = -13, radius f14 = 8).
            drawRoundedRect(
                x1 = left,
                y1 = -PLATE_HEIGHT / 2.0F,
                x2 = left + plateWidth,
                y2 = PLATE_HEIGHT / 2.0F,
                radius = PLATE_RADIUS,
                fillColor = SystemDlcTheme.background(),
                outlineColor = null,
                outlineWidth = 0.0F,
            )

            // TNT icon 16x16 at (left + 10, -8) (f9 = f7 + 10, f10 = -8, f16 = 16; TntTimer.java:122-131).
            // Drawn crisply at the actual on-screen pixel size (drawPlateItem) instead of the vanilla
            // fixed-16px atlas blit, which aliases under this plate's screenScale (see PlateItemIcon).
            pose().pushMatrix()
            pose().translate(left + ICON_LEFT_PAD, -ICON_SIZE / 2.0F)
            drawPlateItem(tntStack, 0.0F, 0.0F, ICON_SIZE, screenScale)
            pose().popMatrix()

            // Text at (left + 32, -8.5), size 14, pure white, in the imported SystemDLC font. Drawn
            // left-aligned so its left edge lands at f11 = f9 + 16 + 6 and its top at f12 = -8.5
            // (TntTimer.java:134-139). The active pose (anchor + screenScale) scales the glyphs 1:1
            // with the plate, so no local 14/9 scale is needed — SystemFont draws at real px size.
            SystemDraw.text(
                this,
                text,
                left + ICON_LEFT_PAD + ICON_SIZE + TEXT_GAP,
                TEXT_TOP,
                TEXT_SIZE,
                Color4b.WHITE,
                TEXT_FAMILY,
            )

            pose().popMatrix()
        }
    }

    /**
     * `TimeFormat.getStringByDouble` (TimeFormat.java:7-9): `< 10 -> "%.1fс"`, else `"%dс"`.
     * The suffix is a Cyrillic "с" (seconds).
     */
    private fun formatSeconds(seconds: Double): String =
        if (seconds < 10.0) String.format("%.1fс", seconds) else String.format("%dс", seconds.toInt())

    override fun onDisabled() {
        smoothedScale.clear()
        seen.clear()
        super.onDisabled()
    }

    // Local plate geometry, verbatim from TntTimer.java (see drawPlate for the source lines).
    private const val PLATE_BASE_WIDTH = 42.0F   // f5 = f4 + 20 = (22 + textWidth) + 20
    private const val PLATE_HEIGHT = 26.0F        // f6 = max(14, 16) + 10
    private const val PLATE_RADIUS = 8.0F         // f14
    private const val ICON_LEFT_PAD = 10.0F       // f9 = f7 + 10
    private const val ICON_SIZE = 16.0F           // f16
    private const val TEXT_GAP = 6.0F             // f11 = f9 + 16 + 6
    private const val TEXT_TOP = -8.5F            // f12
    private const val TEXT_SIZE = 14.0F           // f13 / f20

    /** Imported SystemDLC body atlas (Latin `b` / Cyrillic `bb`), the source's primary body weight. */
    private val TEXT_FAMILY = SystemFont.Family.B
}
