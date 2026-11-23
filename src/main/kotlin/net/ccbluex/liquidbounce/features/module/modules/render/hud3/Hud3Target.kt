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
package net.ccbluex.liquidbounce.features.module.modules.render.hud3

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import kotlin.math.roundToInt

/**
 * The target hud — `<3:802>`, a 141x38 fill box at (325, 154) of the design frame.
 *
 * Composition, all of it off the file:
 * - a 28 px head slot at (5, 5) holding a 21 px rounded avatar (`<I3:784;3:209>`, radius 5.25),
 * - the target's two hand items as 10 px badges laid **over** the head's bottom corners
 *   (`<3:792>` at (5, 23) and `<3:794>` at (23, 23)) — that overlap is deliberate, it is clearly
 *   visible in the design's own render and is not a stray layer,
 * - the name in 12 px at (43, 5) and, 2 px under its line box, a row of four 12 px armour cells
 *   with a gap of 2 (`<3:787>`), ordered boots to helmet like the armour pill,
 * - a 24 px ring badge at the right with the health inside (`<3:797>`, stroke 3 INSIDE).
 *
 * The card's width follows the **armour row**, not the name: the design's inner group is 92 =
 * 28 + 10 + 54, and 54 is exactly four cells plus three gaps while the name only measures 37. So a
 * short name leaves the card at its design width and a long one grows it.
 *
 * The health ring is drawn as a real proportional arc rather than the closed ring the file shows.
 * At full health the two are the same picture, which is the state the design was drawn in; a ring
 * that never moves on a target hud is not what the mark can have meant.
 */
internal class Hud3Target(parent: EventListener) :
    Hud3Panel(parent, "TargetHud", Hud3Anchor.TOP_LEFT, 325f, 154f) {

    private val showArmor by boolean("Armor", true)
    private val showHands by boolean("Hands", true)
    private val showHealth by boolean("Health", true)

    /**
     * The entity on the card. It outlives the target by the fade-out, so the card leaves with the
     * face it had instead of going blank first.
     */
    private var shown: LivingEntity? = null

    /** Linear 0..1 progress of the switch to [shown]: its content fades in, its gear pops. */
    private var switchProgress = 0f

    /** The ring's health share, easing towards the real one. */
    private val health = Hud3Smooth(Hud3Theme.PROGRESS_HALF_LIFE_MS)

    /**
     * The "damage taken" trail behind the ring: it holds where the health was for [TRAIL_HOLD_MS]
     * after a hit, then drains down to it, so the size of the hit stays readable for a moment.
     */
    private val trail = Hud3Smooth(TRAIL_HALF_LIFE_MS)
    private var trailHoldUntil = 0L
    private var lastHealth = -1f

    /** The red flash over the avatar while the target is in its hurt animation. */
    private val hurt = Hud3Smooth(HURT_HALF_LIFE_MS)

    /** The card width, following the name of whoever is on it. */
    private val cardWidth = Hud3Smooth(Hud3Theme.SIZE_HALF_LIFE_MS)

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val ctx = event.context
        val density = frameDensity
        val live = if (enabled) ModuleHud3.combatTarget() else null

        if (live != null && live !== shown) {
            shown = live
            switchProgress = 0f
            // the ring fills in from empty for the new target rather than swinging from the old one
            health.snap(0f)
            trail.snap(0f)
            trailHoldUntil = 0L
            lastHealth = -1f
            hurt.reset()
        }
        val alpha = fade(live != null, dt)
        val target = shown
        if (alpha <= 0.004f || target == null) {
            if (live == null) {
                shown = null
            }
            return
        }
        switchProgress = (switchProgress + Hud3Motion.step(Hud3Theme.ENTER_MS, dt)).coerceAtMost(1f)
        val content = alpha * Hud3Motion.easeOut(switchProgress)

        val name = target.name.string
        val nameWidth = textWidth(name, Hud3Theme.PX_12, density)
        val infoWidth = maxOf(nameWidth, ARMOR_ROW_WIDTH)
        val groupWidth = HEAD_SLOT + HEAD_GAP + infoWidth
        val badgeWidth = if (showHealth) OUTER_GAP + BADGE else 0f
        val width = cardWidth.update(Hud3Theme.PAD * 2f + groupWidth + badgeWidth, dt)
        val frame = frame(width, HEIGHT, alpha)

        val text = scaled(Hud3Theme.TEXT, content)
        val hurtShare = hurt.update(target.hurtTime / target.hurtDuration.coerceAtLeast(1).toFloat(), dt)

        ctx.pushFrame(frame)
        ctx.hud3Pill(0f, 0f, width, HEIGHT, alpha, density, ModuleHud3.glass(ctx))

        // The avatar is a textured quad but its coordinates are local, so it belongs inside the
        // pose - unlike the item sprites, which do their own gui-scale arithmetic.
        ctx.hud3SkinHead(
            skinOf(target),
            Hud3Theme.PAD + (HEAD_SLOT - HEAD_IMAGE) * 0.5f,
            GROUP_Y + (HEAD_SLOT - HEAD_IMAGE) * 0.5f,
            HEAD_IMAGE,
            HEAD_RADIUS,
            density,
            content,
            flash = scaled(Hud3Theme.FAIL, content * hurtShare * HURT_FLASH),
        )

        val infoX = Hud3Theme.PAD + HEAD_SLOT + HEAD_GAP
        val available = width - infoX - Hud3Theme.PAD - badgeWidth
        ctx.hud3Text(
            fitText(name, Hud3Theme.PX_12, density, available),
            infoX, textTopCentered(Hud3Theme.PX_12, density, GROUP_Y, NAME_BAND),
            Hud3Theme.PX_12, density, text,
        )

        if (showHealth) {
            drawHealth(ctx, target, width, density, content, dt)
        }
        ctx.popFrame()

        drawSprites(ctx, frame, target, infoX, Hud3Motion.easeOutBack(switchProgress))
    }

    /** The health ring with its damage trail, and the number inside it counting to the new value. */
    @Suppress("LongParameterList")
    private fun drawHealth(
        ctx: GuiGraphicsExtractor,
        target: LivingEntity,
        width: Float,
        density: Float,
        content: Float,
        dt: Float,
    ) {
        val real = healthFraction(target)
        val now = System.currentTimeMillis()
        if (lastHealth >= 0f && real < lastHealth - HEALTH_EPSILON) {
            trailHoldUntil = now + TRAIL_HOLD_MS
        }
        lastHealth = real
        val filled = health.update(real, dt)
        if (now >= trailHoldUntil) {
            trail.update(filled, dt)
        }
        if (trail.value < filled) {
            trail.snap(filled)
        }

        val badgeX = width - Hud3Theme.PAD - BADGE
        val badgeY = (HEIGHT - BADGE) * 0.5f
        val centerX = badgeX + BADGE * 0.5f
        val centerY = badgeY + BADGE * 0.5f
        val radius = BADGE * 0.5f
        ctx.hud3Shapes(badgeX - 1f, badgeY - 1f, badgeX + BADGE + 1f, badgeY + BADGE + 1f, density) {
            ring(centerX, centerY, radius, BADGE_STROKE, scaled(Hud3Theme.ACCENT, content * Hud3Theme.SPENT_OPACITY))
            arc(centerX, centerY, radius, BADGE_STROKE, trail.value, scaled(Hud3Theme.ACCENT, content * TRAIL_OPACITY))
            arc(centerX, centerY, radius, BADGE_STROKE, filled, scaled(Hud3Theme.ACCENT, content))
        }
        val number = (filled * target.maxHealth).roundToInt().coerceAtLeast(0).toString()
        ctx.hud3Text(
            number,
            badgeX + (BADGE - textWidth(number, Hud3Theme.PX_10, density)) * 0.5f,
            textTopCentered(Hud3Theme.PX_10, density, badgeY, BADGE),
            Hud3Theme.PX_10, density, scaled(Hud3Theme.TEXT, content),
        )
    }

    /**
     * The armour row and the two hand badges. Item sprites resample themselves to a whole number
     * of device pixels off their own gui-space origin, so they are drawn after the pose is popped.
     * The hand badges land over the avatar, which is the design's stacking (`<3:792>` / `<3:794>`
     * are siblings drawn after the head).
     */
    private fun drawSprites(
        ctx: GuiGraphicsExtractor,
        frame: Hud3Frame,
        target: LivingEntity,
        infoX: Float,
        pop: Float,
    ) {
        if (showArmor) {
            for ((index, slot) in ARMOR_ORDER.withIndex()) {
                ctx.hud3Item(
                    target.getItemBySlot(slot), frame,
                    infoX + index * (Hud3Theme.ITEM_12 + ARMOR_GAP), ARMOR_Y, Hud3Theme.ITEM_12, pop,
                )
            }
        }

        if (!showHands) {
            return
        }
        ctx.hud3Item(
            target.getItemBySlot(EquipmentSlot.MAINHAND), frame, Hud3Theme.PAD, HAND_Y, Hud3Theme.ITEM_10, pop,
        )
        ctx.hud3Item(
            target.getItemBySlot(EquipmentSlot.OFFHAND), frame, Hud3Theme.PAD + HAND_OFFSET, HAND_Y,
            Hud3Theme.ITEM_10, pop,
        )
    }

    private fun skinOf(target: LivingEntity): Identifier? =
        (target as? AbstractClientPlayer)?.skin?.body?.texturePath()

    private fun healthFraction(target: LivingEntity): Float {
        val max = target.maxHealth
        return if (max <= 0f) 0f else (target.health / max).coerceIn(0f, 1f)
    }

    private companion object {
        const val HEIGHT = 38f

        /** Top of the inner group — `(38 - 28) / 2`. */
        const val GROUP_Y = 5f

        /** The 28 px slot the avatar is centred in — `<3:784>`. */
        const val HEAD_SLOT = 28f

        /** The avatar itself — `<I3:784;3:209>`. */
        const val HEAD_IMAGE = 21f

        /** Its corner radius — `<I3:784;3:209>` cornerRadius. */
        const val HEAD_RADIUS = 5.25f

        /** Gap between the avatar slot and the name column — `<3:783>` itemSpacing. */
        const val HEAD_GAP = 10f

        /** Gap between the inner group and the health badge — `<3:802>` itemSpacing. */
        const val OUTER_GAP = 15f

        /** Height of the name's line box at 12 px — `<3:786>`. */
        const val NAME_BAND = 14f

        /** Top of the armour row — `5 + 14 + 2`, the group's vertical gap being 2 (`<3:785>`). */
        const val ARMOR_Y = 21f

        /** Gap between the armour cells — `<3:787>` itemSpacing. */
        const val ARMOR_GAP = 2f

        /** `4 * 12 + 3 * 2` — what actually sets the card's inner width. */
        const val ARMOR_ROW_WIDTH = Hud3Theme.ITEM_12 * 4f + ARMOR_GAP * 3f

        /** Top of the two hand badges — `<3:792>`. */
        const val HAND_Y = 23f

        /** Distance between them — `<3:794>` x minus `<3:792>` x. */
        const val HAND_OFFSET = 18f

        /** The health ring badge — `<3:797>`. */
        const val BADGE = 24f

        /** Its stroke, aligned INSIDE. */
        const val BADGE_STROKE = 3f

        /** How long the damage trail holds before it drains, and how fast it then drains. */
        const val TRAIL_HOLD_MS = 320L
        const val TRAIL_HALF_LIFE_MS = 110f

        /** The trail is the accent at this opacity, between the spent track and the live arc. */
        const val TRAIL_OPACITY = 0.5f

        /** A drop smaller than this is noise, not a hit. */
        const val HEALTH_EPSILON = 0.0005f

        /** The avatar's hurt flash: its follow, and its peak opacity. */
        const val HURT_HALF_LIFE_MS = 30f
        const val HURT_FLASH = 0.55f

        /** Boots to helmet, matching the design's render. */
        val ARMOR_ORDER = listOf(
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD,
        )
    }
}
