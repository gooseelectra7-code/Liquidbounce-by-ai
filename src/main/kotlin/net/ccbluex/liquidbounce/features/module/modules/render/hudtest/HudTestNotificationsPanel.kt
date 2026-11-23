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

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.module.modules.render.scaleAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The bottom-centre notification stack.
 *
 * Each toast is a 41 px pill whose width follows its text, laid out as
 * `[34 px icon well] + 20 + text + 14`, with no divider between the well and the text — the
 * reference measures flat card color there. The text is two runs: a coloured lead word (the
 * event's title) and the rest in white.
 *
 * Toasts stack upwards from the anchor at a pitch of 49 (an 8 px gap), the newest at the bottom;
 * a new one slides up into place and every one fades out when its lifetime is over.
 */
internal class HudTestNotificationsPanel(parent: EventListener) : HudTestPanel(
    parent,
    name = "Notifications",
    defaultAnchor = HudTestAnchor.BOTTOM_CENTER,
    defaultOffsetX = 0f,
    defaultOffsetY = -200f,
) {

    private val lifetime by float("Lifetime", 3f, 1f..10f, "s")
    private val maxStack by int("MaxStack", 5, 1..10)

    private class Toast(
        val lead: String,
        val rest: String,
        val severity: NotificationEvent.Severity,
        val createdAt: Long,
        var expireAt: Long,
    )

    private val toasts = ArrayDeque<Toast>()

    /** Queues a toast for [event]; called by the module's notification handler. */
    fun push(event: NotificationEvent) {
        if (!enabled) {
            return
        }
        val now = System.currentTimeMillis()
        val (lead, rest) = runsFor(event)
        toasts.addLast(Toast(lead, rest, event.severity, now, now + (lifetime * 1000f).toLong()))
        // Force-expire the oldest live toast while the stack is over its cap
        while (toasts.count { it.expireAt > now } > maxStack) {
            toasts.firstOrNull { it.expireAt > now }?.expireAt = now
        }
    }

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val now = System.currentTimeMillis()
        toasts.removeIf { it.expireAt != Long.MAX_VALUE && now > it.expireAt + OUT_MS }

        val editing = ModuleHudTest.editMode()
        val shown: List<Toast> = toasts.ifEmpty {
            if (editing) listOf(SAMPLE) else emptyList()
        }
        val alpha = fade(shown.isNotEmpty(), dt)
        if (alpha <= MIN_ALPHA || shown.isEmpty()) {
            return
        }

        val density = frameDensity
        var stackWidth = MIN_WIDTH
        val widths = FloatArray(shown.size)
        for ((index, toast) in shown.withIndex()) {
            widths[index] = toastWidth(toast, density)
            stackWidth = maxOf(stackWidth, widths[index])
        }
        val stackHeight = shown.size * HEIGHT + (shown.size - 1) * GAP
        val frame = frame(stackWidth, stackHeight, alpha)

        val ctx = event.context
        ctx.pushFrame(frame)
        for ((index, toast) in shown.withIndex()) {
            drawToast(ctx, toast, widths[index], stackWidth, index * (HEIGHT + GAP), density, alpha, now)
        }
        ctx.popFrame()
    }

    /**
     * Splits an event into the coloured lead word and the white remainder.
     *
     * The reference puts the subject first and colours it ("**Strength** effect applied!"), while a
     * module toggle arrives the other way round — title "Enabled", message "KillAura" — so those two
     * severities have their runs swapped and read "**KillAura** enabled".
     */
    private fun runsFor(event: NotificationEvent): Pair<String, String> = when (event.severity) {
        NotificationEvent.Severity.ENABLED, NotificationEvent.Severity.DISABLED ->
            event.message to event.title.lowercase()
        else -> event.title to event.message
    }

    private fun toastWidth(toast: Toast, density: Float): Float =
        WELL + TEXT_GAP + textWidth(toast, density) + PAD_RIGHT

    private fun textWidth(toast: Toast, density: Float): Float {
        var width = WatermarkFont.BRAND.width(toast.lead, HudTestTheme.TEXT_PX, density)
        if (toast.rest.isNotEmpty()) {
            width += WatermarkFont.BRAND.width(" ${toast.rest}", HudTestTheme.TEXT_PX, density)
        }
        return width
    }

    /** The lead word carries the whole message's tone; the palette only offers these two. */
    private fun leadColor(severity: NotificationEvent.Severity): Int = when (severity) {
        NotificationEvent.Severity.DISABLED, NotificationEvent.Severity.ERROR -> HudTestTheme.VALUE_DIM
        else -> HudTestTheme.ACCENT
    }

    @Suppress("LongParameterList")
    private fun drawToast(
        ctx: GuiGraphicsExtractor,
        toast: Toast,
        width: Float,
        stackWidth: Float,
        rowY: Float,
        density: Float,
        alpha: Float,
        now: Long,
    ) {
        val appear = ((now - toast.createdAt) / IN_MS.toFloat()).coerceIn(0f, 1f)
        val leaving = if (toast.expireAt == Long.MAX_VALUE) {
            0f
        } else {
            ((now - toast.expireAt) / OUT_MS.toFloat()).coerceIn(0f, 1f)
        }
        val toastAlpha = alpha * HudTestTheme.easeOutCubic(appear) * (1f - leaving)
        if (toastAlpha <= MIN_ALPHA) {
            return
        }
        val slide = (1f - HudTestTheme.easeOutBack(appear)) * SLIDE

        ctx.pose().pushMatrix()
        ctx.pose().translate((stackWidth - width) * 0.5f, rowY + slide)
        ctx.hudCard(width, HEIGHT, RADIUS, toastAlpha, density, ModuleHudTest.glass(ctx))
        ctx.hudShapes(0f, 0f, width, HEIGHT, density) {
            glyphSeverity(
                toast.severity, (WELL - ICON) * 0.5f, (HEIGHT - ICON) * 0.5f, ICON,
                scaleAlpha(HudTestTheme.ACCENT_ICON, toastAlpha),
            )
        }
        writeToastText(ctx, toast, density, toastAlpha)
        ctx.pose().popMatrix()
    }

    private fun writeToastText(ctx: GuiGraphicsExtractor, toast: Toast, density: Float, alpha: Float) {
        val top = textTopCentered(WatermarkFont.BRAND, HudTestTheme.TEXT_PX, density, 0f, HEIGHT)
        val textX = WELL + TEXT_GAP
        ctx.hudText(
            WatermarkFont.BRAND, toast.lead, textX, top,
            HudTestTheme.TEXT_PX, density, scaleAlpha(leadColor(toast.severity), alpha),
        )
        if (toast.rest.isNotEmpty()) {
            val offset = WatermarkFont.BRAND.width(toast.lead, HudTestTheme.TEXT_PX, density)
            ctx.hudText(
                WatermarkFont.BRAND, " ${toast.rest}", textX + offset, top,
                HudTestTheme.TEXT_PX, density, scaleAlpha(HudTestTheme.WHITE, alpha),
            )
        }
    }

    private companion object {
        const val HEIGHT = 41f
        const val RADIUS = 12f
        const val WELL = 34f
        const val TEXT_GAP = 20f
        const val PAD_RIGHT = 14f
        const val ICON = HudTestTheme.ICON
        const val GAP = 8f
        const val MIN_WIDTH = 120f
        const val SLIDE = 14f
        const val IN_MS = 260L
        const val OUT_MS = 220L
        const val MIN_ALPHA = 0.004f

        /** Inert stand-in so the stack can be grabbed while a screen is open. */
        val SAMPLE = Toast(
            "HudTest", "drag me", NotificationEvent.Severity.INFO, 0L, Long.MAX_VALUE,
        )
    }
}
