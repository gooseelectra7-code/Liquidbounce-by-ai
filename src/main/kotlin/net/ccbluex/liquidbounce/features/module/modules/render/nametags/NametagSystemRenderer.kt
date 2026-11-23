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
package net.ccbluex.liquidbounce.features.module.modules.render.nametags

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.modules.render.nametags.NametagEffects.drawEffectRow
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.ccbluex.liquidbounce.render.gui.systemdlc.drawPlateItem
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.scores.DisplaySlot
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SystemDLC-style nametag plate — the second rendering mode of [ModuleNametags].
 *
 * 1:1 visual port of the SystemDLC `client.module.render.NameTags` + its renderer
 * `client.util.NameTagCache` (`.../system-source/src/main/java/client/...`). When
 * [ModuleNametags.style] is `SYSTEM`, [ModuleNametags] routes its collected+sorted
 * entity list here instead of the default plate. The projection/overlay path is shared
 * with the default mode ([ModuleNametags] owns the [net.ccbluex.liquidbounce.event.events.OverlayRenderEvent]
 * handler, [net.ccbluex.liquidbounce.utils.entity.RenderedEntities] the target set); this
 * object only restyles the plate.
 *
 * ## Render pattern (A) — projected screen-space plate (same as [net.ccbluex.liquidbounce.features.module.modules.render.ModuleTNTTimer])
 * SystemDLC draws a true 3D billboard (`NameTagCache.onFloatNameTagEntryCameraMatrixStack`
 * translates to `pos + height + 0.5`, applies `RotationBuffer`, scales by the negative
 * distance-scale factor). That shader billboard is NOT ported. Instead the plate anchor
 * (`interpolateCurrentPosition + bbHeight + 0.5`) is projected with
 * [WorldToScreen.calculateScreenPos] and the whole plate is drawn with our GUI render API
 * (`drawRoundedRect` + `item` + the imported SystemDLC font). The world-space `DistanceScale` factor is
 * converted to a screen-pixel scale by measuring how many screen pixels one world block
 * spans along the camera-up axis at the anchor depth — identical to TntTimer's approach.
 *
 * ## Layout (`NameTagCache.onIntNameTagEntry...` / `onMatrix4fNameTagEntry...`)
 * Local units where an item is 16 px and text is size 14. Left -> right:
 * `6 | offhand | voice | friendBadge | prefix | name | 6 | hpChip | armor | mainhand | 6`.
 * Total width `f27 = 6 + offhand + voice + friend + (prefix+6) + name + 6 + (hpText+8) + armor + mainhand + 6`.
 * Plate: `[-w/2, -16]..[w/2, 16]`, radius 8, fill `background()*opacity` (no border).
 * HP chip: at `value20`, `y -10..10`, width `hpText+8`, radius 4, its own bg color.
 * Items at `y -8`, 16 px; text top at `y -7`, size 14.
 *
 * ## Fidelity notes / deviations (do NOT read as bugs)
 * - **Voice icon GATED OFF**: SystemDLC reads Simple-Voice-Chat over a reflective
 *   `VoicechatBridge` (`de.maxhenkel.voicechat.*`). This fork has no such bridge (grep:
 *   no `voicechat`/`maxhenkel` reference), so the voice icon is never shown and its 18 px
 *   slot never reserved. Re-enable by wiring a bridge + a per-uuid icon lookup.
 * - **Friend badge icon GATED OFF**: the badge is a SystemDLC SVG (`CategoryType.FRIENDS`)
 *   asset we do not have. Friend status STILL drives the visuals it can (name -> success
 *   green, HP chip -> success-tinted) via [FriendManager.isFriend]; only the 21 px badge
 *   glyph + its reserved width are omitted (not left blank) to avoid a fabricated icon.
 * - **Item alpha fade -> pose scale**: SystemDLC fades item icons by alpha during the
 *   per-element show/hide animation. Our `item()` helper has no per-draw alpha, so the
 *   fade is reproduced as a center scale (item grows/shrinks with the animating slot
 *   width). Fully-shown items (scale 1) are pixel-identical.
 * - **Empty-slot cross**: SystemDLC draws a `CategoryType.CLOSE` SVG in a reserved-but-empty
 *   slot. We draw a muted "×" (U+00D7) instead (a generic close mark, not a branded asset).
 *   This ONE glyph stays on the vanilla font: the imported SystemDLC atlases do not contain
 *   U+00D7 (verified: b/bb glyph sets), so drawing it through [SystemFont] would render blank.
 *   `HideEmptySlots` collapses the slot entirely, as in the source.
 * - **MB health bypass reduced**: SystemDLC's MB mode scans every scoreboard objective for
 *   a health/hp/heart name (else the LIST slot). `Scoreboard.getObjectives()` /
 *   `DisplaySlot.LIST` are NOT referenced anywhere in this fork and could not be verified,
 *   so MB falls back to the BELOW_NAME objective (same source as FT) plus absorption. FT and
 *   OFF are faithful.
 * - **Players only**: the source iterates `world.getPlayers()`. SYSTEM plates are drawn for
 *   [Player] entities only; non-player [net.ccbluex.liquidbounce.utils.entity.RenderedEntities]
 *   are skipped in this mode (they get no plate).
 * - Text (name/prefix/HP, and the stack count) is the imported SystemDLC MSDF font ([SystemFont],
 *   family [SystemFont.Family.B]) at the source sizes 14 / 8; plate width tracks the measured font
 *   widths (same call as TntTimer). The lone exception is the empty-slot "×" (see below).
 */
object NametagSystemStyle : ValueGroup("System") {

    // --- settings (apply ONLY when ModuleNametags.style == SYSTEM) ---

    /**
     * "Размер" — name/plate scale fed to [SystemDlcTheme.distanceScale] (source default 0.35).
     *
     * This is the DISTANCE-INDEPENDENT half of the plate's size: [SystemDlcTheme.distanceScale] clamps
     * `distance * 0.0025` to `0.02..0.1`, which holds the plate at a constant on-screen size between 8
     * and 40 blocks, and this value is the flat factor on top of it. The distance-dependent half is
     * [ModuleNametags.scale], the same `Distance -> Scale` curve the DEFAULT style uses (wired in
     * 2026-09-17; before that the curve did nothing here). The range goes past the source's 1.0 because
     * 0.35 is a small plate and there was nothing above it to reach for.
     */
    private val size by float("Size", 0.35f, 0.01f..3.0f)

    /** "Фон плашки" — draw the rounded backdrop; off = text + icons only. */
    private val background by boolean("Background", true)

    /** "Прозрачность плашки" — backdrop opacity in percent (source default 100). */
    private val backgroundOpacity by float("BackgroundOpacity", 100f, 0f..100f, "%")

    /** INSTANCE2 (dark) vs INSTANCE (light) SystemDLC palette; screenshots use dark. */
    private val darkTheme by boolean("DarkTheme", true)

    /** "Префикс" — extract & show the server prefix before the name (default on). */
    private val prefix by boolean("Prefix", true)

    /** "Брoня" — armor row (default on). */
    private val armor by boolean("Armor", true)

    /** "Левая рука" — offhand item, drawn left of the name (default on). */
    private val offHand by boolean("OffHand", true)

    /** "Правая рука" — mainhand item, drawn right of the armor row (source default OFF). */
    private val mainHand by boolean("MainHand", false)

    /** "Цветное HP" — tint the HP chip/text by the health fraction (default on). */
    private val coloredHealth by boolean("ColoredHealth", true)

    /** "Режим HP" — Points ("NNHP") vs Hearts ("cur/max"). */
    private val healthMode by enumChoice("HealthMode", SystemHealthMode.POINTS)

    /** "Обход HP" — real-HP source: Off / MB / FT (source default FT). */
    private val healthBypass by enumChoice("HealthBypass", SystemHealthBypass.FUNTIME)

    /** "Скрывать пустые слоты" — collapse empty equipment slots (source default off). */
    private val hideEmptySlots by boolean("HideEmptySlots", false)

    /** "Количество предметов" — draw the stack count on items with count > 1 (default on). */
    private val itemCount by boolean("ItemCount", true)

    /** "Упрощать вдали" — collapse items/armor at distance, keeping name + HP (default off). */
    private val simplifyFar by boolean("SimplifyFar", false)

    /** "Дистанция упрощения" — collapse threshold in blocks (source default 30; 4-block hysteresis). */
    private val simplifyDistance by float("SimplifyDistance", 30f, 8f..128f, " blocks")

    enum class SystemHealthMode(override val tag: String) : Tagged {
        POINTS("Points"),
        HEARTS("Hearts"),
    }

    enum class SystemHealthBypass(override val tag: String) : Tagged {
        OFF("Off"),
        MINEBLAZE("MB"),
        FUNTIME("FT"),
    }

    // --- per-player animation state (source NameTagAnimations, keyed by uuid) ---

    private class SystemAnim {
        /** Element progress in [0,1] (raw, pre-easing). Names match the layout roles. */
        var offhandShown = 0f
        var mainhandShown = 0f
        var armorShown = 0f
        var offhandPresent = 0f
        var mainhandPresent = 0f
        val armorPiece = FloatArray(4)

        /** Exponentially smoothed distance scale (source `value`, NaN until first frame). */
        var scaleValue = Float.NaN

        /** LOD expanded flag with hysteresis (source `flag`, default expanded). */
        var expanded = true

        /** First-frame init done (source `flag2`). */
        var initialized = false
    }

    private val animMap = HashMap<UUID, SystemAnim>()
    private val seen = HashSet<UUID>()
    private var lastFrameTime = System.currentTimeMillis()

    /** Clearance between the plate's top edge and the effect row, in plate units. */
    private const val EFFECT_ROW_GAP = 3f

    /** SystemDLC text sizes: name/prefix/HP at size 14, the in-slot stack count at size 8. */
    private const val NAME_SIZE = 14f
    private const val COUNT_SIZE = 8f

    /** Imported SystemDLC body atlas (Latin `b` / Cyrillic `bb`), the source's primary body weight. */
    private val TEXT_FAMILY = SystemFont.Family.B

    fun reset() {
        animMap.clear()
        seen.clear()
    }

    /**
     * Entry point from [ModuleNametags]. [entities] is the module's collected list, sorted
     * NEAR -> FAR; SystemDLC paints FAR -> NEAR, so it is iterated reversed.
     */
    internal fun render(context: GuiGraphicsExtractor, tickDelta: Float, entities: List<NametagRenderState>) {
        if (entities.isEmpty()) {
            return
        }
        val self = mc.player ?: return

        SystemDlcTheme.dark = darkTheme

        val now = System.currentTimeMillis()
        val dt = min((now - lastFrameTime) / 1000f, 0.1f)
        lastFrameTime = now

        val up = mc.gameRenderer.mainCamera.upVector()

        seen.clear()
        with(context) {
            for (i in entities.indices.reversed()) {
                val entity = entities[i].entity
                if (entity !is Player || entity === self) {
                    continue
                }
                drawPlayer(entity, self, tickDelta, dt, up, entities[i].scale)
            }
        }
        animMap.keys.retainAll(seen)
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    private fun GuiGraphicsExtractor.drawPlayer(
        entity: Player,
        self: Player,
        tickDelta: Float,
        dt: Float,
        up: org.joml.Vector3fc,
        curveScale: Float,
    ) {
        val uuid = entity.uuid
        val distance = self.distanceTo(entity).toDouble()

        // Plate anchor: interpolated position + bbHeight + 0.5 (NameTagCache.java:267-268), plus the
        // module-wide VerticalOffset so the two styles answer the same setting. The DEFAULT plate adds
        // it in NametagRenderState.calculateScreenPos; this style projects its own anchor and so has to
        // add it here.
        val anchor = entity.interpolateCurrentPosition(tickDelta)
            .add(0.0, entity.bbHeight.toDouble() + 0.5 + ModuleNametags.verticalOffset, 0.0)
        val anchorScreen = WorldToScreen.calculateScreenPos(anchor) ?: return

        val anim = animMap.getOrPut(uuid) { SystemAnim() }
        seen.add(uuid)

        // --- gather entity data ---
        val offhandStack = entity.getItemBySlot(EquipmentSlot.OFFHAND)
        val mainhandStack = entity.getItemBySlot(EquipmentSlot.MAINHAND)
        // index 0 = feet .. 3 = head, so the armor row renders head -> feet left to right
        // (matches source inventory slots 36..39, drawn i = 3 downTo 0).
        val armorStacks = arrayOf(
            entity.getItemBySlot(EquipmentSlot.FEET),
            entity.getItemBySlot(EquipmentSlot.LEGS),
            entity.getItemBySlot(EquipmentSlot.CHEST),
            entity.getItemBySlot(EquipmentSlot.HEAD),
        )
        val isFriend = FriendManager.isFriend(entity)

        val currentHealth = currentHealth(entity)
        val maxHealth = maxHealth(entity)
        val hpString = if (healthMode == SystemHealthMode.HEARTS) {
            heartsString(currentHealth, maxHealth)
        } else {
            pointsString(currentHealth)
        }

        val plainName = entity.name.string
        val prefixText = if (prefix) {
            val display = (entity.team?.getFormattedName(entity.name) ?: entity.name).string
            NametagPrefixSanitizer.extractPrefix(display, plainName)
        } else {
            ""
        }

        // --- update animations (NameTagCache.java:307-388) ---
        // LOD hysteresis on distance (value3 vs simplifyDistance, 4-block band).
        if (!simplifyFar) {
            anim.expanded = true
        } else if (anim.expanded) {
            if (distance > simplifyDistance + 4.0) {
                anim.expanded = false
            }
        } else if (distance < simplifyDistance) {
            anim.expanded = true
        }

        val showOff = offHand && anim.expanded
        val showMain = mainHand && anim.expanded
        val showArm = armor && anim.expanded
        val offPresent = !offhandStack.isEmpty
        val mainPresent = !mainhandStack.isEmpty
        val offSlot = !hideEmptySlots || offPresent
        val mainSlot = !hideEmptySlots || mainPresent
        val armorTargets = BooleanArray(4) { !hideEmptySlots || !armorStacks[it].isEmpty }

        if (!anim.initialized) {
            anim.offhandShown = boolTo(showOff)
            anim.mainhandShown = boolTo(showMain)
            anim.armorShown = boolTo(showArm)
            anim.offhandPresent = boolTo(offSlot)
            anim.mainhandPresent = boolTo(mainSlot)
            for (k in 0 until 4) anim.armorPiece[k] = boolTo(armorTargets[k])
            anim.initialized = true
        }
        anim.offhandShown = step(anim.offhandShown, showOff, dt)
        anim.mainhandShown = step(anim.mainhandShown, showMain, dt)
        anim.armorShown = step(anim.armorShown, showArm, dt)
        anim.offhandPresent = step(anim.offhandPresent, offSlot, dt)
        anim.mainhandPresent = step(anim.mainhandPresent, mainSlot, dt)
        for (k in 0 until 4) anim.armorPiece[k] = step(anim.armorPiece[k], armorTargets[k], dt)

        val target = SystemDlcTheme.distanceScale(distance, size)
        anim.scaleValue = if (anim.scaleValue.isNaN()) {
            target
        } else {
            anim.scaleValue + (target - anim.scaleValue) * min(1.0f, dt * 10.0f)
        }

        // eased element values
        val fOff = easeInOutCubic(anim.offhandShown)
        val fMain = easeInOutCubic(anim.mainhandShown)
        val fArm = easeInOutCubic(anim.armorShown)
        val fOffP = easeInOutCubic(anim.offhandPresent)
        val fMainP = easeInOutCubic(anim.mainhandPresent)
        val a0 = easeInOutCubic(anim.armorPiece[0])
        val a1 = easeInOutCubic(anim.armorPiece[1])
        val a2 = easeInOutCubic(anim.armorPiece[2])
        val a3 = easeInOutCubic(anim.armorPiece[3])

        // --- widths (NameTagCache.java:182-201) ---
        val prefixPresent = prefixText.isNotEmpty()
        val prefixWidth = if (prefixPresent) width14(prefixText) else 0f
        val prefixSlot = if (prefixPresent) prefixWidth + 6f else 0f          // f10
        val nameWidth = width14(plainName)                                    // f11
        val hpWidth = width14(hpString)                                       // f12
        val offhandWidth = 22f * fOffP * fOff                                 // f14 = (16+6)*f3*f
        val mainhandWidth = 22f * fMainP * fMain                              // f16
        val armorBase = 16f * (a0 + a1 + a2 + a3)                             // f17
        val armorGaps = 6f * max(a0, a1) + 6f * max(a1, a2) + 6f * max(a2, a3)
        val maxArmor = max(max(a0, a1), max(a2, a3))                          // f22
        val armorRowWidth = (6f * maxArmor + armorBase + armorGaps) * fArm    // f23
        val friendBadgeWidth = 0f                                            // f24 gated off
        val voiceWidth = 0f                                                  // f25 gated off
        val hpChipWidth = hpWidth + 8f                                        // f26
        val totalWidth = 6f + offhandWidth + voiceWidth + friendBadgeWidth + prefixSlot +
            nameWidth + 6f + hpChipWidth + armorRowWidth + mainhandWidth + 6f // f27
        val chipX = -totalWidth / 2f + 6f + offhandWidth + voiceWidth + friendBadgeWidth +
            prefixSlot + nameWidth + 6f                                       // value20

        // --- colors (NameTagCache.java:202-235) ---
        val kColor: Color4b? = when {
            isFriend -> SystemDlcTheme.success()
            coloredHealth && currentHealth <= 100f ->
                healthGradient(if (maxHealth > 0f) currentHealth / maxHealth else 1f)
            else -> null
        }
        val nameColor = if (isFriend) SystemDlcTheme.success() else SystemDlcTheme.foreground()
        val prefixColor = SystemDlcTheme.foreground()
        val hpTextColor: Color4b
        val hpChipColor: Color4b
        if (kColor == null) {
            hpTextColor = SystemDlcTheme.foreground()
            hpChipColor = SystemDlcTheme.elevated()
        } else {
            hpTextColor = hslTint(kColor, 1.0f, if (darkTheme) 0.66f else 0.38f)
            hpChipColor = hslTint(kColor, 0.55f, if (darkTheme) 0.22f else 0.9f)
        }

        // --- world -> screen scale (TntTimer probe along camera-up) ---
        val upPoint = anchor.add(up.x().toDouble(), up.y().toDouble(), up.z().toDouble())
        val upScreen = WorldToScreen.calculateScreenPos(upPoint) ?: return
        val pixelsPerBlock = hypot(
            (upScreen.x - anchorScreen.x).toDouble(),
            (upScreen.y - anchorScreen.y).toDouble(),
        ).toFloat()
        // anim.scaleValue is the plate's own distance compensation (Size x SystemDlcTheme.distanceScale);
        // curveScale is ModuleNametags.scale sampled at this distance, i.e. the second, independent knob.
        val screenScale = anim.scaleValue * pixelsPerBlock * curveScale
        if (screenScale <= 0f) {
            return
        }

        // --- draw ---
        val left = -totalWidth / 2f
        val itemY = -8f
        val textTop = -7f

        pose().pushMatrix()
        pose().translate(anchorScreen.x, anchorScreen.y)
        pose().scale(screenScale, screenScale)

        if (background) {
            val opacityFrac = (backgroundOpacity / 100f).coerceIn(0f, 1f)
            // plate: [-w/2,-16]..[w/2,16], radius 8, fill background()*opacity, no border.
            drawRoundedRect(
                x1 = left,
                y1 = -16f,
                x2 = left + totalWidth,
                y2 = 16f,
                radius = 8f,
                fillColor = SystemDlcTheme.withAlphaFactor(SystemDlcTheme.background(), opacityFrac),
                outlineColor = null,
                outlineWidth = 0f,
            )
            // HP chip: at value20, y -10..10, width hpText+8, radius 4, own bg color.
            if (hpWidth > 0.001f) {
                drawRoundedRect(
                    x1 = chipX,
                    y1 = -10f,
                    x2 = chipX + hpChipWidth,
                    y2 = 10f,
                    radius = 4f,
                    fillColor = hpChipColor,
                    outlineColor = null,
                    outlineWidth = 0f,
                )
            }
        }

        var cursor = left + 6f

        // 1. offhand item (itemStack = OFF hand)
        if (fOff > 0.001f && fOffP > 0.001f) {
            drawSystemSlot(offhandStack, cursor, itemY, fOff * fOffP, screenScale)
        }
        cursor += offhandWidth
        // 2. voice icon  -> gated off (voiceWidth == 0)
        // 3. friend badge -> gated off (friendBadgeWidth == 0)
        // 4. prefix
        if (prefixPresent) {
            drawText14(prefixText, cursor, textTop, prefixColor)
            cursor += prefixWidth + 6f
        }
        // 5. name
        drawText14(plainName, cursor, textTop, nameColor)
        cursor += nameWidth + 6f
        // 6. HP text (chip already under the background)
        drawText14(hpString, cursor + 4f, textTop, hpTextColor)
        cursor += hpWidth + 8f
        // 7. armor row (right -> left, i = 3 downTo 0)
        if (fArm > 0.001f && maxArmor > 0.001f) {
            var ax = cursor + 6f * fArm * maxArmor
            val pieces = floatArrayOf(a0, a1, a2, a3)
            for (i in 3 downTo 0) {
                val p = pieces[i]
                if (p > 0.001f) {
                    drawSystemSlot(armorStacks[i], ax, itemY, fArm * p, screenScale)
                }
                ax += 16f * p * fArm
                if (i > 0) {
                    ax += 6f * max(p, pieces[i - 1]) * fArm
                }
            }
        }
        cursor += armorRowWidth
        // 8. mainhand item (itemStack2 = MAIN hand)
        if (fMain > 0.001f && fMainP > 0.001f) {
            val mx = cursor + 6f * fMain * fMainP
            drawSystemSlot(mainhandStack, mx, itemY, fMain * fMainP, screenScale)
        }

        // Status effects, in this style's own palette and font, above the plate's top edge (y = -16).
        // Not part of the SystemDLC source layout - it has no effect row - so it is additive and off
        // by default (see NametagEffects).
        drawEffectRow(
            entity = entity,
            centerX = 0f,
            bottomY = -16f - EFFECT_ROW_GAP,
            tagScale = 1f,
            poseScale = screenScale,
            system = true,
        )

        pose().popMatrix()
    }

    /**
     * Draws an item (or a muted "x" for a reserved empty slot), with a scale-based fade. [screenScale]
     * is the plate's world->screen scale; combined with this slot's [opacity] fade it is the on-screen
     * pixel scale the item icon is rasterized for, so it stays crisp (drawPlateItem) instead of aliasing
     * from the vanilla fixed-16px atlas blit (see PlateItemIcon).
     */
    private fun GuiGraphicsExtractor.drawSystemSlot(
        stack: ItemStack,
        leftX: Float,
        topY: Float,
        opacity: Float,
        screenScale: Float,
    ) {
        if (opacity <= 0.001f) {
            return
        }
        if (!stack.isEmpty) {
            pose().pushMatrix()
            pose().translate(leftX + 8f, topY + 8f)
            pose().scale(opacity, opacity)
            pose().translate(-8f, -8f)
            drawPlateItem(stack, 0f, 0f, 16f, screenScale * opacity)
            if (itemCount && stack.count > 1) {
                // Stack count in the imported SystemDLC font, size 8 (was an 8/9 vanilla-font scale),
                // right-aligned to the 16 px item box's right edge, top y = 8. The active pose (plate
                // screenScale + this slot's opacity scale) scales it 1:1 with the icon.
                SystemDraw.text(
                    this,
                    stack.count.toString(),
                    16f,
                    8f,
                    COUNT_SIZE,
                    SystemDlcTheme.foreground(),
                    TEXT_FAMILY,
                    TextAlign.RIGHT,
                )
            }
            pose().popMatrix()
        } else if (!hideEmptySlots) {
            // Reserved-but-empty slot: SystemDLC draws a CLOSE svg; we use a muted font "x".
            val faded = SystemDlcTheme.withAlphaFactor(SystemDlcTheme.mutedFg(), opacity)
            pose().pushMatrix()
            pose().translate(leftX + 8f, topY + 8f)
            centeredText(mc.font, "×", 0, -4, faded.argb)
            pose().popMatrix()
        }
    }

    /**
     * Draws left-aligned text with its left edge at [leftX] and top at [topY], at SystemDLC size 14
     * in the imported font ([SystemFont]). SystemFont draws at real px and treats `y` as the line top;
     * the active pose (anchor + screenScale) scales the glyphs 1:1 with the plate.
     */
    private fun GuiGraphicsExtractor.drawText14(text: String, leftX: Float, topY: Float, color: Color4b) {
        SystemDraw.text(this, text, leftX, topY, NAME_SIZE, color, TEXT_FAMILY)
    }

    /** Width of [text] at SystemDLC size 14 in the imported font. */
    private fun width14(text: String): Float = SystemDraw.textWidth(text, NAME_SIZE, TEXT_FAMILY)

    // --- health (HealthTracker.java) ---

    /**
     * Current HP = `getFloatByServerModePlayerEntity` (HealthTracker.java:126-142). OFF adds
     * absorption; FT reads BELOW_NAME scoreboard (no absorption) else base; MB adds absorption
     * to the scoreboard read (see class KDoc for the reduced objective scan).
     */
    private fun currentHealth(entity: Player): Float {
        val base = entity.health + entity.absorptionAmount
        return when (healthBypass) {
            SystemHealthBypass.FUNTIME -> {
                val score = belowNameScore(entity)
                if (score != null && score > 0f) score else base
            }
            SystemHealthBypass.MINEBLAZE -> {
                val score = belowNameScore(entity)
                if (score != null && score > 0f) score + entity.absorptionAmount else base
            }
            SystemHealthBypass.OFF -> base
        }
    }

    /** Max HP = `getFloatByPlayerEntityServerMode` (HealthTracker.java:144-153). */
    private fun maxHealth(entity: Player): Float {
        val f = entity.maxHealth
        val f1 = entity.absorptionAmount
        return when (healthBypass) {
            SystemHealthBypass.OFF -> f + f1
            SystemHealthBypass.FUNTIME -> max(f, 20f)
            SystemHealthBypass.MINEBLAZE -> max(f, 20f) + f1
        }
    }

    /** BELOW_NAME scoreboard score for [entity], or null (mirrors HealthTracker.getFloatByPlayerEntity). */
    private fun belowNameScore(entity: Player): Float? {
        return try {
            val objective = entity.level().scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME) ?: return null
            objective.scoreboard.getPlayerScoreInfo(entity, objective)?.value()?.toFloat()
        } catch (_: Exception) {
            null
        }
    }

    /** `getStringByFloat2` (HealthTracker.java:159): `> 100 -> "?"`, else `"NNHP"`. */
    private fun pointsString(current: Float): String =
        if (current > 100f) "?" else "${current.roundToInt()}HP"

    /** `getStringByFloatFloat` (HealthTracker.java:65-73): `> 100 -> "?"`, else `"cur/max"` hearts. */
    private fun heartsString(current: Float, maxHp: Float): String =
        if (current > 100f) "?" else "${(current / 2f).roundToInt()}/${(maxHp / 2f).roundToInt()}"

    // --- color math (NameTagCache.java + MathUtil.java, HSL) ---

    /**
     * Health-fraction gradient (NameTagCache.getIntByFloatFloat): 0 = danger, 0.5 = caution,
     * 1 = success. `interpolateTo` is the per-channel ARGB lerp of `AnimatedInt.getIntByIntFloatInt`.
     */
    private fun healthGradient(frac: Float): Color4b {
        val f = frac.coerceIn(0f, 1f)
        return if (f >= 0.5f) {
            SystemDlcTheme.caution().interpolateTo(SystemDlcTheme.success(), ((f - 0.5f) / 0.5f).toDouble())
        } else {
            SystemDlcTheme.danger().interpolateTo(SystemDlcTheme.caution(), (f / 0.5f).toDouble())
        }
    }

    /**
     * NameTagCache.getIntByFloatFloatInt: keep [base]'s hue, scale its saturation by [satScale],
     * override lightness to [lightness]. Opaque. HSL, exactly per MathUtil.java:4-61.
     */
    private fun hslTint(base: Color4b, satScale: Float, lightness: Float): Color4b {
        val hsl = rgbToHsl(base)
        return hslToColor(lightness, hsl[0], hsl[1] * satScale)
    }

    /** RGB -> [h, s, l] with h in [0,1] (MathUtil.getFloatArrayByInt). */
    private fun rgbToHsl(c: Color4b): FloatArray {
        val r = c.r / 255f
        val g = c.g / 255f
        val b = c.b / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) {
            return floatArrayOf(0f, 0f, l)
        }
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return floatArrayOf(h / 6f, s, l)
    }

    /** [l, h, s] -> opaque Color4b (MathUtil.getIntByFloatFloatFloat). */
    private fun hslToColor(l: Float, h: Float, s: Float): Color4b {
        if (s == 0f) {
            val v = (l * 255f).roundToInt().coerceIn(0, 255)
            return Color4b(v, v, v, 255)
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val r = hue2rgb(p, q, h + 1f / 3f)
        val g = hue2rgb(p, q, h)
        val b = hue2rgb(p, q, h - 1f / 3f)
        return Color4b(
            (r * 255f).roundToInt().coerceIn(0, 255),
            (g * 255f).roundToInt().coerceIn(0, 255),
            (b * 255f).roundToInt().coerceIn(0, 255),
            255,
        )
    }

    /** MathUtil.getFloatByFloatFloatFloat (hue -> channel). */
    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    // --- animation helpers (Animation.java rate 5, Easings.getFloatByFloat7 = easeInOutCubic) ---

    private fun step(raw: Float, target: Boolean, dt: Float): Float {
        val next = raw + (if (target) 1f else -1f) * dt * 5f
        return next.coerceIn(0f, 1f)
    }

    private fun boolTo(flag: Boolean): Float = if (flag) 1f else 0f

    private fun easeInOutCubic(v: Float): Float =
        if (v < 0.5f) {
            4f * v * v * v
        } else {
            val f = -2f * v + 2f
            1f - f * f * f * 0.5f
        }
}
