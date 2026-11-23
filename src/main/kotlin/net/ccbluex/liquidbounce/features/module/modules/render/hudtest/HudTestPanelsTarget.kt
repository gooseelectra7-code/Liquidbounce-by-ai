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
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkGuiElement
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.ccbluex.liquidbounce.features.module.modules.render.watermarkBounds
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.combat.intel.TargetIntel.nametagHealth
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3x2f
import kotlin.math.roundToInt

/** A face is 8x8 skin texels, and the head only stays square when it covers a whole multiple. */
private const val FACE_TEXELS = 8

/**
 * A player head drawn straight from their skin: the face (skin pixels 8..16 of 64) with the hat
 * layer (40..48) over it, both mapped across one rounded square and sampled with NEAREST so the
 * skin's own pixels stay square.
 *
 * The head is fitted to a whole multiple of [FACE_TEXELS] device pixels off a device-aligned
 * origin before anything is drawn. NEAREST hands every destination pixel the texel its centre
 * lands in, so at the raw 46 px the 8 face texels came out 6, 5, 6, 6, 6, 5, 6, 6 pixels wide —
 * both eyes rendered a pixel apart in width and the nose sat off the middle of the face. Rounding
 * the head to 48 gives all eight texels the same six pixels and the face comes out symmetric.
 * [density] is physical pixels per local unit, which is what the fit is measured in.
 */
@Suppress("LongParameterList")
internal fun GuiGraphicsExtractor.hudSkinHead(
    skin: Identifier?,
    x: Float,
    y: Float,
    size: Float,
    radius: Float,
    density: Float,
    alpha: Float,
) {
    val scale = density.coerceAtLeast(0.01f)
    val deviceSize = (size * scale / FACE_TEXELS).roundToInt().coerceAtLeast(1) * FACE_TEXELS
    val fitted = deviceSize / scale
    val left = Math.round((x + (size - fitted) * 0.5f) * scale) / scale
    val top = Math.round((y + (size - fitted) * 0.5f) * scale) / scale
    val fittedRadius = radius * fitted / size

    if (skin == null) {
        hudShapes(left, top, left + fitted, top + fitted, density) {
            roundRect(left, top, fitted, fitted, fittedRadius, scaleAlpha(HudTestTheme.PLACEHOLDER, alpha))
        }
        return
    }

    val texture = mc.textureManager.getTexture(skin)
    val setup = TextureSetup.singleTexture(
        texture.textureView,
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
    )
    val contour = HudTestGeometry.contour(left, top, left + fitted, top + fitted, fittedRadius, 0f)
    val tint = scaleAlpha(-1, alpha)
    val faceU = { vx: Float -> 0.125f + (vx - left) / fitted * 0.125f }
    val faceV = { vy: Float -> 0.125f + (vy - top) / fitted * 0.125f }
    val hatU = { vx: Float -> 0.625f + (vx - left) / fitted * 0.125f }

    guiRenderState.addGuiElement(
        WatermarkGuiElement(
            RenderPipelines.GUI_TEXTURED,
            setup,
            Matrix3x2f(pose()),
            scissorStack.peek(),
            watermarkBounds(left, top, left + fitted, top + fitted),
        ) { pose ->
            HudTestGeometry.fan(contour, left + fitted * 0.5f, top + fitted * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setUv(faceU(vx), faceV(vy)).setColor(tint)
            }
            HudTestGeometry.fan(contour, left + fitted * 0.5f, top + fitted * 0.5f) { vx, vy ->
                addVertexWith2DPose(pose, vx, vy).setUv(hatU(vx), faceV(vy)).setColor(tint)
            }
        }
    )
}

/**
 * The centre target hud and the gear strip beneath it.
 *
 * The card itself is 188x52 with a 48 px head inset by 2, the name on a fixed baseline, the hp
 * number right-aligned to `width - 15` and a two-pixel health bar that eases towards
 * `health / maxHealth` instead of snapping. The gear strip is a second, smaller card centred on the
 * same axis, holding six 16 px cells at a pitch of 16 — the four armour pieces, the main hand and
 * the off hand, with empty slots left empty.
 */
internal class HudTestTargetPanel(parent: EventListener) : HudTestPanel(
    parent,
    name = "TargetHud",
    defaultAnchor = HudTestAnchor.CENTER,
    defaultOffsetX = 0f,
    defaultOffsetY = -20f,
) {

    private val showHead by boolean("Head", true)
    private val showHealthBar by boolean("HealthBar", true)
    private val showGear by boolean("Gear", true)

    /** The eased health fraction the bar actually draws. */
    private var barFraction = 0f

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val target = ModuleHudTest.combatTarget() ?: editStandIn()
        val alpha = fade(target != null, dt)
        if (alpha <= MIN_ALPHA || target == null) {
            return
        }

        val health = target.nametagHealth()
        val maxHealth = target.maxHealth.coerceAtLeast(health).coerceAtLeast(1f)
        barFraction += (health / maxHealth - barFraction) * HudTestTheme.damp(BAR_SMOOTHING, dt)

        val density = frameDensity
        val frame = frame(WIDTH, HEIGHT, alpha)
        val ctx = event.context

        ctx.pushFrame(frame)
        ctx.hudCard(WIDTH, HEIGHT, RADIUS, alpha, density, ModuleHudTest.glass(ctx))
        if (showHealthBar) {
            ctx.hudShapes(BAR_X - 2f, BAR_Y - 2f, BAR_X + BAR_WIDTH + 2f, BAR_Y + BAR_HEIGHT + 2f, density) {
                bar(
                    BAR_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT, barFraction,
                    scaleAlpha(HudTestTheme.TRACK, alpha),
                    scaleAlpha(HudTestTheme.BAR_FILL_TARGET, alpha),
                )
            }
        }
        writeText(ctx, target, health, density, alpha)
        if (showHead) {
            ctx.hudSkinHead(skinOf(target), HEAD_INSET, HEAD_INSET, HEAD_SIZE, HEAD_RADIUS, density, alpha)
        }
        ctx.popFrame()

        if (showGear) {
            renderGear(ctx, frame, target, alpha)
        }
    }

    /** While a screen is open and nothing is being fought, the player stands in so it can be moved. */
    private fun editStandIn(): LivingEntity? = if (ModuleHudTest.editMode()) mc.player else null

    private fun skinOf(target: LivingEntity): Identifier? =
        (target as? AbstractClientPlayer)?.skin?.body?.texturePath()

    private fun writeText(
        ctx: GuiGraphicsExtractor,
        target: LivingEntity,
        health: Float,
        density: Float,
        alpha: Float,
    ) {
        val nameTop = textTopOnBaseline(WatermarkFont.BRAND, HudTestTheme.TITLE_PX, density, BASELINE)

        // The hp block is laid out first because it owns the right edge; whatever is left over
        // between it and TEXT_X is all the room the name gets. Without this a normal sixteen
        // character nickname is drawn straight through the health number.
        val value = health.roundToInt().toString()
        val unitTop = textTopOnBaseline(WatermarkFont.TEXT, HudTestTheme.UNIT_PX, density, BASELINE)
        val unitWidth = WatermarkFont.TEXT.width(HP_UNIT, HudTestTheme.UNIT_PX, density)
        val valueWidth = WatermarkFont.BRAND.width(value, HudTestTheme.TITLE_PX, density)
        val right = WIDTH - HudTestTheme.PAD_X

        val nameRoom = right - unitWidth - valueWidth - NAME_GAP - TEXT_X
        val name = fitText(WatermarkFont.BRAND, target.name.string, HudTestTheme.TITLE_PX, density, nameRoom)
        ctx.hudText(
            WatermarkFont.BRAND, name, TEXT_X, nameTop,
            HudTestTheme.TITLE_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
        )

        ctx.hudText(
            WatermarkFont.BRAND, value, right - unitWidth - valueWidth, nameTop,
            HudTestTheme.TITLE_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
        )
        ctx.hudText(
            WatermarkFont.TEXT, HP_UNIT, right - unitWidth, unitTop,
            HudTestTheme.UNIT_PX, density, scaleAlpha(HudTestTheme.UNIT_DIM, alpha),
        )
    }

    /** The armour, main hand and off hand of [target] in the reference's slot order. */
    private fun gearOf(target: LivingEntity): List<ItemStack> = listOf(
        target.getItemBySlot(EquipmentSlot.HEAD),
        target.getItemBySlot(EquipmentSlot.CHEST),
        target.getItemBySlot(EquipmentSlot.LEGS),
        target.getItemBySlot(EquipmentSlot.FEET),
        target.getItemBySlot(EquipmentSlot.MAINHAND),
        target.getItemBySlot(EquipmentSlot.OFFHAND),
    )

    private fun renderGear(ctx: GuiGraphicsExtractor, parent: HudTestFrame, target: LivingEntity, alpha: Float) {
        val gear = gearOf(target)
        val gearFrame = HudTestFrame(
            x = parent.snap(parent.x + (parent.width - GEAR_WIDTH) * 0.5f * parent.unit),
            y = parent.snap(parent.y + (parent.height + GEAR_GAP) * parent.unit),
            unit = parent.unit,
            density = parent.density,
            alpha = alpha,
            grow = parent.grow,
            width = GEAR_WIDTH,
            height = GEAR_HEIGHT,
        )

        ctx.pushFrame(gearFrame)
        ctx.hudCard(GEAR_WIDTH, GEAR_HEIGHT, GEAR_RADIUS, alpha, gearFrame.density, ModuleHudTest.glass(ctx))
        ctx.popFrame()

        val deviceSize = (CELL * gearFrame.density * gearFrame.grow).roundToInt().coerceAtLeast(8)
        val cellY = (GEAR_HEIGHT - CELL) * 0.5f
        for ((index, stack) in gear.withIndex()) {
            if (stack.isEmpty) {
                continue
            }
            HudTestItemIcons.draw(
                ctx, stack,
                gearFrame.guiX(GEAR_PAD + index * CELL), gearFrame.guiY(cellY), deviceSize,
            )
        }
    }

    private companion object {
        const val WIDTH = 188f
        const val HEIGHT = 52f
        const val RADIUS = 10f

        /**
         * The head's corner has to stay concentric with the card's: an inset of 2 inside a card
         * radius of 10 leaves a corner radius of 8. The 3 / 9 pair this used to carry put the
         * head's arc centre 2.8 px off the card's, which pushed the head's top-left and
         * bottom-left corners nearly two pixels outside the card's own rounding.
         */
        const val HEAD_INSET = 2f
        const val HEAD_SIZE = HEIGHT - HEAD_INSET * 2f
        const val HEAD_RADIUS = RADIUS - HEAD_INSET
        const val TEXT_X = 64f

        /** Clear space kept between the name and the hp block so they never touch. */
        const val NAME_GAP = 8f

        /** Name and hp share this baseline; it is what puts the cap row where the reference has it. */
        const val BASELINE = 28f
        const val HP_UNIT = "hp"

        const val BAR_X = 64f
        const val BAR_Y = 35f
        const val BAR_WIDTH = 108f
        const val BAR_HEIGHT = 2f

        const val GEAR_WIDTH = 112f
        const val GEAR_HEIGHT = 34f
        const val GEAR_RADIUS = 12f
        const val GEAR_GAP = 5f
        const val GEAR_PAD = 8f
        const val CELL = 16f

        /** Fraction of the bar's remaining travel left after one second. */
        const val BAR_SMOOTHING = 1.0e-5f
        const val MIN_ALPHA = 0.004f
    }
}
