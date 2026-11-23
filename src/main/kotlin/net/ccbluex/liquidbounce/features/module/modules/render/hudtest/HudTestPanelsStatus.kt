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

import com.google.common.base.Suppliers
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.texture.DynamicTexture
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * The client droplet used as the status bar's leading mark, uploaded once and kept for the session.
 */
internal object HudTestLogo {

    private const val RESOURCE = "resources/liquidbounce/icon_64x64.png"

    private var setup: TextureSetup? = null
    private var failed = false

    /** The droplet texture, or `null` when it could not be loaded (logged once). */
    fun texture(): TextureSetup? {
        setup?.let { return it }
        if (failed) {
            return null
        }
        return try {
            val stream = javaClass.classLoader.getResourceAsStream(RESOURCE)
                ?: error("missing classpath resource $RESOURCE")
            val image = stream.use(NativeImage::read)
            val texture = DynamicTexture(Suppliers.ofInstance("HudTest logo"), image)
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            ).also { setup = it }
        } catch (e: Throwable) {
            failed = true
            logger.error("HudTest: failed to load the status bar logo", e)
            null
        }
    }
}

/**
 * The top-centre status bar.
 *
 * A single 41 px tall pill whose width follows its content, laid out strictly left to right the way
 * the reference measures out:
 *
 * ```
 * pad 17 | logo 10 | gap 29 | icon 10 + gap 7 + value + unit | gap 13 | rule | gap 13 | ... | pad 17
 * ```
 *
 * The three slots are live: frames per second, the connection latency the player list reports, and
 * a free text slot that understands a couple of placeholders.
 */
internal class HudTestStatusBarPanel(parent: EventListener) : HudTestPanel(
    parent,
    name = "StatusBar",
    defaultAnchor = HudTestAnchor.TOP_CENTER,
    defaultOffsetX = 0f,
    defaultOffsetY = 25f,
) {

    private val showLogo by boolean("Logo", true)
    private val showFps by boolean("Fps", true)
    private val showPing by boolean("Ping", true)
    private val infoText by text("InfoText", "t.me/jbdsgn")

    /** One `icon + value + unit` group of the bar. */
    private class Slot(
        val icon: HudTestShapes.(Float, Float, Float, Int) -> Unit,
        val value: String,
        val unit: String,
        /** Gap between this slot and the rule that follows it, and between that rule and the next. */
        val gapBefore: Float,
        val gapAfter: Float,
    )

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val alpha = fade(mc.player != null || ModuleHudTest.editMode(), dt)
        if (alpha <= MIN_ALPHA) {
            return
        }
        val slots = buildSlots()
        if (slots.isEmpty()) {
            return
        }

        val density = frameDensity
        val positions = FloatArray(slots.size)
        val width = layout(slots, positions, density)
        val frame = frame(width, HEIGHT, alpha)

        val ctx = event.context
        ctx.pushFrame(frame)
        ctx.hudCard(width, HEIGHT, RADIUS, alpha, density, ModuleHudTest.glass(ctx))
        ctx.hudShapes(0f, 0f, width, HEIGHT, density) {
            paintMarks(this, slots, positions, density, alpha)
        }
        writeSlots(ctx, slots, positions, density, alpha)
        if (showLogo) {
            HudTestLogo.texture()?.let {
                ctx.hudTexturedQuad(it, PAD_X, (HEIGHT - LOGO) * 0.5f, LOGO, LOGO, scaleAlpha(-1, alpha))
            }
        }
        ctx.popFrame()
    }

    private fun buildSlots(): List<Slot> {
        val slots = ArrayList<Slot>(3)
        if (showFps) {
            slots += Slot({ x, y, size, argb -> iconRocket(x, y, size, argb) }, mc.fps.toString(), "fps", 13f, 13f)
        }
        if (showPing) {
            slots += Slot({ x, y, size, argb -> iconBars(x, y, size, argb) }, ping().toString(), "ms", 12f, 14f)
        }
        val info = resolveInfo()
        if (info.isNotEmpty()) {
            slots += Slot({ x, y, size, argb -> iconPerson(x, y, size, argb) }, info, "", 12f, 14f)
        }
        return slots
    }

    private fun ping(): Int {
        val player = mc.player ?: return 0
        return mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
    }

    /** `%user%`, `%server%` and `%time%` are substituted; everything else is taken literally. */
    private fun resolveInfo(): String {
        var text = infoText
        if (text.contains(PLACEHOLDER_USER)) {
            text = text.replace(PLACEHOLDER_USER, mc.user?.name ?: "")
        }
        if (text.contains(PLACEHOLDER_SERVER)) {
            text = text.replace(PLACEHOLDER_SERVER, mc.currentServer?.ip ?: "Singleplayer")
        }
        if (text.contains(PLACEHOLDER_TIME)) {
            text = text.replace(PLACEHOLDER_TIME, LocalTime.now().format(TIME_FORMAT))
        }
        return text
    }

    /**
     * Walks the bar left to right, filling [positions] with each slot's icon x and returning the
     * total card width. The slots' own text widths are what makes the card grow and shrink.
     */
    private fun layout(slots: List<Slot>, positions: FloatArray, density: Float): Float {
        var cursor = PAD_X
        if (showLogo) {
            cursor += LOGO + LOGO_GAP
        }
        for ((index, slot) in slots.withIndex()) {
            positions[index] = cursor
            cursor += ICON + ICON_GAP + runWidth(slot, density)
            if (index < slots.size - 1) {
                cursor += slot.gapBefore + RULE_WIDTH + slot.gapAfter
            }
        }
        return cursor + PAD_X
    }

    private fun runWidth(slot: Slot, density: Float): Float =
        WatermarkFont.BRAND.width(slot.value, HudTestTheme.TEXT_PX, density) +
            WatermarkFont.TEXT.width(slot.unit, HudTestTheme.UNIT_PX, density)

    @Suppress("LongParameterList")
    private fun paintMarks(
        shapes: HudTestShapes,
        slots: List<Slot>,
        positions: FloatArray,
        density: Float,
        alpha: Float,
    ) {
        val iconY = (HEIGHT - ICON) * 0.5f
        val ruleY = (HEIGHT - RULE_HEIGHT) * 0.5f
        for ((index, slot) in slots.withIndex()) {
            slot.icon(shapes, positions[index], iconY, ICON, scaleAlpha(HudTestTheme.ACCENT_ICON, alpha))
            if (index < slots.size - 1) {
                val ruleX = positions[index] + ICON + ICON_GAP + runWidth(slot, density) + slot.gapBefore
                shapes.rect(ruleX, ruleY, RULE_WIDTH, RULE_HEIGHT, scaleAlpha(HudTestTheme.DIVIDER, alpha))
            }
        }
    }

    @Suppress("LongParameterList")
    private fun writeSlots(
        ctx: GuiGraphicsExtractor,
        slots: List<Slot>,
        positions: FloatArray,
        density: Float,
        alpha: Float,
    ) {
        val valueTop = textTopCentered(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, 0f, HEIGHT)
        val baseline = textBaseline(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, valueTop)
        val unitTop = textTopOnBaseline(WatermarkFont.TEXT, HudTestTheme.UNIT_PX, density, baseline)
        for ((index, slot) in slots.withIndex()) {
            val textX = positions[index] + ICON + ICON_GAP
            ctx.hudText(
                WatermarkFont.BRAND, slot.value, textX, valueTop,
                HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
            )
            if (slot.unit.isNotEmpty()) {
                val offset = WatermarkFont.BRAND.width(slot.value, HudTestTheme.TEXT_PX, density)
                ctx.hudText(
                    WatermarkFont.TEXT, slot.unit, textX + offset, unitTop,
                    HudTestTheme.UNIT_PX, density, scaleAlpha(HudTestTheme.UNIT_DIM, alpha),
                )
            }
        }
    }

    private companion object {
        const val HEIGHT = 41f
        const val RADIUS = 14f
        const val PAD_X = 17f

        /** The droplet fills its texture edge to edge, so it takes the same slot as the marks. */
        const val LOGO = HudTestTheme.ICON
        const val LOGO_GAP = 29f
        const val ICON = HudTestTheme.ICON

        /** Clear space between a mark and the value it introduces; the marks fill their slot. */
        const val ICON_GAP = 7f
        const val RULE_WIDTH = 1f
        const val RULE_HEIGHT = 14f
        const val MIN_ALPHA = 0.004f

        const val PLACEHOLDER_USER = "%user%"
        const val PLACEHOLDER_SERVER = "%server%"
        const val PLACEHOLDER_TIME = "%time%"

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * The bottom-left coordinate readout: a compass mark and three `label + value` runs, right-aligned
 * to `width - 13` exactly as measured. The card's width follows the numbers, so a five-digit
 * coordinate simply widens it instead of colliding with the compass.
 */
internal class HudTestCoordsPanel(parent: EventListener) : HudTestPanel(
    parent,
    name = "Coords",
    defaultAnchor = HudTestAnchor.BOTTOM_LEFT,
    defaultOffsetX = 23f,
    defaultOffsetY = -28f,
) {

    private val decimals by int("Decimals", 0, 0..2)

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val player = mc.player
        val alpha = fade(player != null, dt)
        if (alpha <= MIN_ALPHA || player == null) {
            return
        }

        val density = frameDensity
        val labels = arrayOf("x", "y", "z")
        val values = arrayOf(
            format(player.x), format(player.y), format(player.z),
        )

        var runWidth = 0f
        for (index in 0 until 3) {
            runWidth += WatermarkFont.TEXT.width(labels[index], HudTestTheme.UNIT_PX, density)
            runWidth += WatermarkFont.BRAND.width(values[index], HudTestTheme.TEXT_PX, density)
        }
        runWidth += RUN_GAP * 2f

        val width = READOUT_X + runWidth + PAD_RIGHT
        val frame = frame(width, HEIGHT, alpha)

        val ctx = event.context
        ctx.pushFrame(frame)
        ctx.hudCard(width, HEIGHT, RADIUS, alpha, density, ModuleHudTest.glass(ctx))
        ctx.hudShapes(0f, 0f, width, HEIGHT, density) {
            iconCompass(ICON_X, (HEIGHT - ICON) * 0.5f, ICON, scaleAlpha(HudTestTheme.ACCENT_ICON, alpha))
        }
        writeReadout(ctx, labels, values, density, alpha)
        ctx.popFrame()
    }

    @Suppress("LongParameterList")
    private fun writeReadout(
        ctx: GuiGraphicsExtractor,
        labels: Array<String>,
        values: Array<String>,
        density: Float,
        alpha: Float,
    ) {
        val valueTop = textTopCentered(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, 0f, HEIGHT)
        val baseline = textBaseline(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, valueTop)
        val labelTop = textTopOnBaseline(WatermarkFont.TEXT, HudTestTheme.UNIT_PX, density, baseline)

        var cursor = READOUT_X
        for (index in 0 until 3) {
            ctx.hudText(
                WatermarkFont.TEXT, labels[index], cursor, labelTop,
                HudTestTheme.UNIT_PX, density, scaleAlpha(HudTestTheme.UNIT_DIM, alpha),
            )
            cursor += WatermarkFont.TEXT.width(labels[index], HudTestTheme.UNIT_PX, density)
            ctx.hudText(
                WatermarkFont.BRAND, values[index], cursor, valueTop,
                HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
            )
            cursor += WatermarkFont.BRAND.width(values[index], HudTestTheme.TEXT_PX, density) + RUN_GAP
        }
    }

    private fun format(value: Double): String =
        if (decimals <= 0) floor(value).toInt().toString() else "%.${decimals}f".format(Locale.ROOT, value)

    private companion object {
        const val HEIGHT = 40f
        const val RADIUS = 12f

        /** The same 17 px pad the status bar puts before its droplet. */
        const val ICON_X = 17f
        const val ICON = HudTestTheme.ICON

        /** Where the readout starts — the same 29 px breathing room the status bar's logo gets. */
        const val READOUT_X = 57f
        const val RUN_GAP = 4f
        const val PAD_RIGHT = 13f
        const val MIN_ALPHA = 0.004f
    }
}
