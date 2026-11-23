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

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.processor.MinecraftTextProcessor
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.TextAlign
import net.ccbluex.liquidbounce.render.gui.systemdlc.drawPlateSprite
import net.ccbluex.liquidbounce.utils.collection.LruCache
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The status effect row both nametag styles draw above the tag.
 *
 * ## The look
 * One tile per effect, laid out left to right on a single rounded backdrop. A tile is the effect's own
 * vanilla marker sprite over a plate tinted with that effect's colour, an amplifier badge in the top
 * right corner, the remaining `m:ss` under the icon, and a drain bar across the bottom that empties as
 * the effect runs out. A tile whose effect is nearly over pulses. This is deliberately NOT the shape
 * [NametagEnchantmentRenderer] uses — that one stacks abbreviated text labels, which is unreadable past
 * a couple of entries and carries no sense of time left.
 *
 * The drain bar measures against the LONGEST duration seen for that entity+effect rather than a fixed
 * window, so a 3:00 potion and an 8-second splash both start full and empty at the same rate they
 * actually expire — the same reference `ModuleHellhitHud.PotionsEl` uses for its own cards.
 *
 * ## Icon crispness
 * Markers go through [drawPlateSprite], so they are rasterized at the size the plate actually puts them
 * on screen instead of being a nearest-neighbour stretch of the 18px gui atlas cell — the same treatment
 * the SYSTEM plate's item icons get from [net.ccbluex.liquidbounce.render.gui.systemdlc.PlateItemIcon].
 *
 * ## What it can show (read this before calling it a bug)
 * A remote player's effects are NOT synced on a vanilla server. `ClientboundUpdateMobEffectPacket` is
 * only ever sent to the affected player's own connection (`ServerPlayer.onEffectAdded` /
 * `onEffectUpdated`), to players riding the entity (`LivingEntity.sendEffectToPassengers`) and by
 * `PlayerList.sendActiveEffects` on join / respawn / dimension change; `ServerEntity.sendPairingData`
 * never sends it, which is verified for 26.1.2 and already documented at
 * `combat/autoanchor/world/AnchorView.kt:128-137`. So `getActiveEffects()` reads:
 * - the local player and any entity we ride: authoritative,
 * - every other player or mob: EMPTY, unless the server chooses to broadcast the packet itself.
 *
 * The row therefore draws nothing over other players on a vanilla server. That is the protocol, not a
 * failure of this code, and it is why the group ships disabled.
 */
@Suppress("TooManyFunctions")
internal object NametagEffects : ToggleableValueGroup(ModuleNametags, "Effects", false) {

    // --- settings ---

    /** Row scale on top of the tag's own scale. */
    private val scale by float("Scale", 1f, 0.25f..4f)

    /** Cap on tiles per tag; the longest-lasting effects win. */
    private val maxCount by int("MaxCount", 6, 1..12)

    /** Remaining `m:ss` under each icon. An infinite effect draws no text (see [timeString]). */
    private val duration by boolean("Duration", true)

    /** The amplifier badge (`II`..`V`, the raw number above five); level I is never badged. */
    private val amplifier by boolean("Amplifier", true)

    /** The drain bar across a tile's bottom. */
    private val drainBar by boolean("DrainBar", true)

    /** The rounded backdrop under the whole row; off = tiles only. */
    private val background by boolean("Background", true)

    /** Skip beacon/conduit effects, which are permanent and mostly noise on a tag. */
    private val hideAmbient by boolean("HideAmbient", false)

    // --- layout, in local units (1 unit = 1 unit of the caller's pose) ---

    private const val TILE_W = 15f
    private const val TILE_GAP = 1.5f
    private const val ICON = 10f
    private const val ICON_TOP = 3f
    private const val TIME_TOP = ICON_TOP + ICON + 1f
    private const val TEXT_PX = 6f
    private const val BAR_H = 1.5f
    private const val BAR_INSET = 2f
    private const val BAR_GAP = 1.5f
    private const val TILE_BOTTOM_PAD = 1.5f
    private const val TILE_RADIUS = 3f
    private const val BADGE_H = 7f
    private const val BADGE_PAD_X = 1.5f
    private const val ROW_PAD = 2f
    private const val ROW_RADIUS = 4.5f

    /** Ticks left at which a tile starts pulsing — vanilla's own "about to expire" threshold. */
    private const val BLINK_TICKS = 200

    /** Pulse period in milliseconds. */
    private const val BLINK_PERIOD_MS = 700f

    /** Bottom of the pulse's alpha envelope; the top is always 1. */
    private const val BLINK_FLOOR = 0.45f

    /** How far a tile's fill is pulled towards the effect colour (0 = neutral plate, 1 = flat colour). */
    private const val FILL_TINT = 0.22f

    // --- palettes ---

    private val NEUTRAL_FILL = Color4b(20, 20, 24, 200)
    private val NEUTRAL_ROW_BG = Color4b(0, 0, 0, 130)
    private val NEUTRAL_TEXT = Color4b(228, 228, 232, 255)
    private val HARMFUL_TEXT = Color4b(255, 122, 122, 255)
    private val BADGE_BG = Color4b(0, 0, 0, 170)
    private val BAR_TRACK = Color4b(255, 255, 255, 30)

    // --- state ---

    /** Longest duration seen for one entity+effect, the reference the drain bar empties against. */
    private class Drain {
        var max: Int = 1
        var lastSeen: Long = 0L
    }

    private val drains = HashMap<Long, Drain>()

    /** Above this many tracked entity+effect pairs, entries untouched for [DRAIN_TTL_MS] are dropped. */
    private const val DRAIN_LIMIT = 512
    private const val DRAIN_TTL_MS = 5_000L

    /**
     * Reused per-call scratch list. Everything this object draws happens inside one [drawEffectRow] call on the
     * render thread, so a single buffer is enough and keeps a busy scene from allocating per tag.
     */
    private val scratch = ArrayList<MobEffectInstance>(8)

    /**
     * Processed text for the nametag font, keyed by `argb|string`. The label set is tiny and highly
     * repetitive across players ("1:23", "II"), so this keeps [MinecraftTextProcessor.TEXT_POOL] from
     * being drained by a per-frame `process` for every tile.
     */
    private val processedCache = LruCache<String, MinecraftTextProcessor.RecyclingProcessedText>(128)

    /** Longest first, so the cap keeps what the user most likely cares about; infinite effects lead. */
    private val ORDER: Comparator<MobEffectInstance> = Comparator
        .comparingInt<MobEffectInstance> { if (it.isInfiniteDuration) Int.MIN_VALUE else -it.duration }
        .thenComparing { it.descriptionId }

    /**
     * Draws the row for [entity], its bottom edge at [bottomY] and centred on [centerX] in the caller's
     * current pose.
     *
     * @param tagScale the tag's own scale; multiplied by the [scale] setting to size the row.
     * @param poseScale the accumulated scale between the caller's pose and GUI-logical space, used to
     *   rasterize the markers at their real on-screen size.
     * @param system draw in the SystemDLC palette and font ([NametagSystemStyle]) instead of the default
     *   plate's.
     * @return the height the row occupies in the CALLER's units, 0 when nothing was drawn.
     */
    @Suppress("LongParameterList")
    fun GuiGraphicsExtractor.drawEffectRow(
        entity: Entity,
        centerX: Float,
        bottomY: Float,
        tagScale: Float,
        poseScale: Float,
        system: Boolean,
    ): Float {
        if (!running || entity !is LivingEntity || tagScale <= 0f) {
            return 0f
        }

        val effects = collect(entity)
        if (effects.isEmpty()) {
            return 0f
        }

        val unit = tagScale * scale
        val tileHeight = tileHeight()
        val rowWidth = effects.size * TILE_W + (effects.size - 1) * TILE_GAP
        val now = System.currentTimeMillis()

        pose().pushMatrix()
        pose().translate(centerX, bottomY)
        pose().scale(unit, unit)

        val left = -rowWidth / 2f
        val top = -tileHeight

        if (background) {
            drawRoundedRect(
                x1 = left - ROW_PAD,
                y1 = top - ROW_PAD,
                x2 = left + rowWidth + ROW_PAD,
                y2 = ROW_PAD,
                radius = ROW_RADIUS,
                fillColor = if (system) {
                    SystemDlcTheme.withAlphaFactor(SystemDlcTheme.background(), 1f)
                } else {
                    NEUTRAL_ROW_BG
                },
                outlineColor = null,
                outlineWidth = 0f,
            )
        }

        var x = left
        for (instance in effects) {
            drawTile(entity, instance, x, top, tileHeight, unit * poseScale, system, now)
            x += TILE_W + TILE_GAP
        }

        pose().popMatrix()
        pruneDrains(now)

        return (tileHeight + if (background) ROW_PAD * 2f else 0f) * unit
    }

    /** Effects worth drawing for [entity], sorted by [ORDER] and cut to [maxCount]. */
    private fun collect(entity: LivingEntity): List<MobEffectInstance> {
        scratch.clear()
        for (instance in entity.activeEffects) {
            if (!instance.isVisible && !instance.showIcon()) {
                continue
            }
            if (hideAmbient && instance.isAmbient) {
                continue
            }
            scratch += instance
        }
        if (scratch.isEmpty()) {
            return scratch
        }
        scratch.sortWith(ORDER)
        while (scratch.size > maxCount) {
            scratch.removeAt(scratch.size - 1)
        }
        return scratch
    }

    private fun tileHeight(): Float =
        (if (duration) TIME_TOP + TEXT_PX else ICON_TOP + ICON) + BAR_GAP + BAR_H + TILE_BOTTOM_PAD

    @Suppress("LongParameterList")
    private fun GuiGraphicsExtractor.drawTile(
        entity: LivingEntity,
        instance: MobEffectInstance,
        tileX: Float,
        tileY: Float,
        tileHeight: Float,
        poseScale: Float,
        system: Boolean,
        now: Long,
    ) {
        val effect = instance.effect.value()
        val accent = Color4b.fullAlpha(effect.color)
        val harmful = effect.category == MobEffectCategory.HARMFUL
        val fade = pulse(instance, now)

        // plate: a neutral tile pulled towards the effect's own colour, so a row of them still reads
        // as one strip instead of a rainbow of flat swatches.
        val base = if (system) SystemDlcTheme.elevated() else NEUTRAL_FILL
        drawRoundedRect(
            x1 = tileX,
            y1 = tileY,
            x2 = tileX + TILE_W,
            y2 = tileY + tileHeight,
            radius = TILE_RADIUS,
            fillColor = mix(base, accent, FILL_TINT).fadeTo(fade),
            outlineColor = accent.alpha(60).fadeTo(fade),
            outlineWidth = 0.5f,
        )

        drawPlateSprite(
            Gui.getMobEffectSprite(instance.effect),
            tileX + (TILE_W - ICON) / 2f,
            tileY + ICON_TOP,
            ICON,
            poseScale,
            Color4b.WHITE.fadeTo(fade),
        )

        if (amplifier && instance.amplifier > 0) {
            drawBadge(levelString(instance.amplifier), tileX, tileY, accent, system, fade)
        }

        if (duration) {
            val time = timeString(instance)
            if (time != null) {
                val color = if (harmful) HARMFUL_TEXT else if (system) SystemDlcTheme.foreground() else NEUTRAL_TEXT
                drawLabel(time, tileX + TILE_W / 2f, tileY + TIME_TOP, color.fadeTo(fade), system)
            }
        }

        if (drainBar) {
            drawDrain(entity, instance, tileX, tileY + tileHeight, accent, fade)
        }
    }

    /** Amplifier badge in the tile's top-right corner, on its own dark pill so it reads over the icon. */
    @Suppress("LongParameterList")
    private fun GuiGraphicsExtractor.drawBadge(
        text: String,
        tileX: Float,
        tileY: Float,
        accent: Color4b,
        system: Boolean,
        fade: Float,
    ) {
        val width = labelWidth(text, system) + BADGE_PAD_X * 2f
        val x = tileX + TILE_W - width - 0.5f
        drawRoundedRect(
            x1 = x,
            y1 = tileY,
            x2 = x + width,
            y2 = tileY + BADGE_H,
            radius = BADGE_H / 2f,
            fillColor = BADGE_BG.fadeTo(fade),
            outlineColor = null,
            outlineWidth = 0f,
        )
        drawLabel(text, x + width / 2f, tileY + (BADGE_H - TEXT_PX) / 2f, accent.fadeTo(fade), system)
    }

    /**
     * The drain bar: a full-width track with the remaining fraction filled in the effect colour. The
     * reference is the longest duration seen for this entity+effect, so the fill always starts full.
     */
    @Suppress("LongParameterList")
    private fun GuiGraphicsExtractor.drawDrain(
        entity: LivingEntity,
        instance: MobEffectInstance,
        tileX: Float,
        tileBottom: Float,
        accent: Color4b,
        fade: Float,
    ) {
        val y = tileBottom - TILE_BOTTOM_PAD - BAR_H
        val x1 = tileX + BAR_INSET
        val x2 = tileX + TILE_W - BAR_INSET
        drawRoundedRect(x1, y, x2, y + BAR_H, BAR_H / 2f, BAR_TRACK.fadeTo(fade), null, 0f)

        val fraction = fraction(entity, instance)
        if (fraction <= 0.001f) {
            return
        }
        val fillWidth = (x2 - x1) * fraction
        // A rounded fill narrower than its own height degenerates into a dot; skip it instead.
        if (fillWidth < BAR_H) {
            return
        }
        drawRoundedRect(x1, y, x1 + fillWidth, y + BAR_H, BAR_H / 2f, accent.fadeTo(fade), null, 0f)
    }

    // --- text, over whichever font the calling style draws in ---

    /** Width of [text] at [TEXT_PX] in the style's font, in local units. */
    private fun labelWidth(text: String, system: Boolean): Float {
        if (system) {
            return SystemDraw.textWidth(text, TEXT_PX, SystemFont.Family.B)
        }
        val font = ModuleNametags.fontRenderer
        return font.getStringWidth(processed(text, Color4b.WHITE), shadow = true) * (TEXT_PX / font.height)
    }

    /** Draws [text] centred on [centerX] with its top at [topY], at [TEXT_PX] in the style's font. */
    private fun GuiGraphicsExtractor.drawLabel(
        text: String,
        centerX: Float,
        topY: Float,
        color: Color4b,
        system: Boolean,
    ) {
        if (system) {
            SystemDraw.text(this, text, centerX, topY, TEXT_PX, color, SystemFont.Family.B, TextAlign.CENTER)
            return
        }
        val font = ModuleNametags.fontRenderer
        val textScale = TEXT_PX / font.height
        val processedText = processed(text, color)
        font.draw(processedText) {
            this.x = centerX - font.getStringWidth(processedText, shadow = true) * textScale * 0.5f
            this.y = topY
            this.scale = textScale
            shadow = true
        }
    }

    private fun processed(text: String, color: Color4b): MinecraftTextProcessor.RecyclingProcessedText =
        processedCache.computeIfAbsent("${color.argb}|$text") {
            ModuleNametags.fontRenderer.process(text, color)
        }

    // --- values ---

    /** `m:ss` left, or null for an infinite effect (no glyph for it is guaranteed in either font). */
    private fun timeString(instance: MobEffectInstance): String? {
        if (instance.isInfiniteDuration) {
            return null
        }
        val seconds = instance.duration / TICKS_PER_SECOND
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Roman for the levels a potion can actually reach, the plain number past that. */
    private fun levelString(amplifierValue: Int): String = when (amplifierValue) {
        1 -> "II"
        2 -> "III"
        3 -> "IV"
        4 -> "V"
        else -> (amplifierValue + 1).toString()
    }

    private fun fraction(entity: LivingEntity, instance: MobEffectInstance): Float {
        if (instance.isInfiniteDuration) {
            return 1f
        }
        val key = (entity.id.toLong() shl EFFECT_ID_BITS) or
            BuiltInRegistries.MOB_EFFECT.getId(instance.effect.value()).toLong()
        val drain = drains.getOrPut(key) { Drain() }
        drain.max = max(drain.max, instance.duration)
        drain.lastSeen = System.currentTimeMillis()
        return (instance.duration.toFloat() / drain.max).coerceIn(0f, 1f)
    }

    private fun pruneDrains(now: Long) {
        if (drains.size <= DRAIN_LIMIT) {
            return
        }
        drains.values.removeIf { now - it.lastSeen > DRAIN_TTL_MS }
    }

    /** 1 normally; a sine pulse down to [BLINK_FLOOR] once the effect is inside [BLINK_TICKS]. */
    private fun pulse(instance: MobEffectInstance, now: Long): Float {
        if (instance.isInfiniteDuration || !instance.endsWithin(BLINK_TICKS)) {
            return 1f
        }
        val phase = sin(now % BLINK_PERIOD_MS.toLong() / BLINK_PERIOD_MS * 2f * Math.PI.toFloat())
        return BLINK_FLOOR + (1f - BLINK_FLOOR) * (0.5f + 0.5f * phase)
    }

    /** Called when the module goes down — the drain references belong to a world we may have left. */
    fun reset() {
        drains.clear()
        scratch.clear()
    }

    // --- colour helpers ---

    private fun mix(base: Color4b, accent: Color4b, t: Float): Color4b = Color4b(
        (base.r + (accent.r - base.r) * t).roundToInt().coerceIn(0, 255),
        (base.g + (accent.g - base.g) * t).roundToInt().coerceIn(0, 255),
        (base.b + (accent.b - base.b) * t).roundToInt().coerceIn(0, 255),
        base.a,
    )

    private fun Color4b.fadeTo(factor: Float): Color4b =
        if (factor >= 0.999f) this else alpha((a * factor).roundToInt().coerceIn(0, 255))

    private const val TICKS_PER_SECOND = 20

    /** Bits of the drain key reserved for the mob effect registry id. */
    private const val EFFECT_ID_BITS = 12
}
