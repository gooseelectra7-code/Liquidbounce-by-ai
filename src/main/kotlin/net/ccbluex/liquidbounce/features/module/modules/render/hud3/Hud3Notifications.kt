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
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import java.util.ArrayDeque

/**
 * The notification stack — the `notifications` component set `<3:179>`, placed at (111, 200) of
 * the design frame with a vertical gap of 5.
 *
 * One pill per notification: a 12 px severity mark, a gap of 5, the text in **pure white** at
 * 10 px (`<3:162>` — the only white in the design; every other label is `#F7F3F3`), and across the
 * bottom a 2 px timeout bar. Padding is 5 horizontally and 7 vertically, which is what makes the
 * pill 26 tall.
 *
 * The bar is authored 143 wide at x = -4 inside a 133 wide card (`<3:163>`) — wider than the card
 * and starting outside it — and the card clips. So it is simply the full card width, inset at the
 * ends by the rounded corners like the armour pill's bars.
 *
 * Do **not** port the component set's own 1 px `#8A38F5` border (`<3:179>` stroke): that is
 * Figma's frame around a set of variants, not part of any of them.
 *
 * ## Two things the design cannot state
 *
 * The file shows three variants and the client has five severities, so the mapping is ours:
 * SUCCESS and ENABLED take the green check, ERROR the red cross, INFO and DISABLED the amber
 * mark. And the file shows one sentence where a [NotificationEvent] carries a title and a message,
 * so the two are joined here — module toggles arrive with the fields the other way round
 * (title `Enabled`, message `KillAura`) and are recomposed, exactly as the HudTest panel does.
 */
internal class Hud3Notifications(parent: EventListener) :
    Hud3Panel(parent, "Notifications", Hud3Anchor.TOP_LEFT, 111f, 200f) {

    private val lifetime by float("Lifetime", 3f, 0.5f..10f, "s")
    private val maxVisible by int("MaxVisible", 5, 1..10)

    private class Toast(
        val text: String,
        val severity: NotificationEvent.Severity,
        val bornAt: Long,
        val diesAt: Long,
    ) {
        fun remaining(now: Long): Float {
            val span = (diesAt - bornAt).toFloat()
            return if (span <= 0f) 0f else ((diesAt - now) / span).coerceIn(0f, 1f)
        }
    }

    /** Newest first, so the stack grows downwards from the anchor. */
    private val toasts = ArrayDeque<Toast>()

    fun push(event: NotificationEvent) {
        val now = System.currentTimeMillis()
        toasts.addFirst(
            Toast(compose(event), event.severity, now, now + (lifetime * 1000f).toLong())
        )
        while (toasts.size > HISTORY_LIMIT) {
            toasts.removeLast()
        }
    }

    /**
     * The toasts on screen, keyed by identity. A new one slides in at the top and pushes the rest
     * down; an expired one slides back out where it was and the stack closes over it; one that was
     * waiting past [maxVisible] slides in as soon as there is room.
     */
    private val shown = Hud3Presence<Toast, Toast>({ it })

    fun clear() {
        toasts.clear()
        shown.clear()
    }

    override fun render(event: OverlayRenderEvent, dt: Float) {
        val ctx = event.context
        val density = frameDensity
        val now = System.currentTimeMillis()

        toasts.removeIf { now >= it.diesAt }
        shown.sync(if (enabled) toasts.take(maxVisible) else emptyList(), dt)
        val alpha = fade(shown.hasPresent, dt)
        if (alpha <= 0.004f) {
            return
        }

        val extent = shown.layout(Hud3Theme.ROW_26 + Hud3Theme.STACK_GAP, dt)
        var width = 0f
        for (entry in shown.all) {
            val cardWidth = entry.size.update(widthOf(entry.value, density), dt)
            if (!entry.leaving) {
                width = maxOf(width, cardWidth)
            }
        }
        val height = (extent - Hud3Theme.STACK_GAP).coerceAtLeast(Hud3Theme.ROW_26)
        val frame = frame(width.coerceAtLeast(Hud3Theme.ROW_26), height, alpha)
        val glass = ModuleHud3.glass(ctx)

        ctx.pushFrame(frame)
        for (entry in shown.all) {
            val presence = entry.eased
            drawToast(
                ctx, entry.value, entry.size.value,
                slideDirection * Hud3Theme.SLIDE * 2f * (1f - presence), entry.offset.value,
                now, density, alpha * presence, glass,
            )
        }
        ctx.popFrame()
    }

    private fun widthOf(toast: Toast, density: Float): Float =
        Hud3Theme.PAD * 2f + Hud3Theme.ICON_12 + Hud3Theme.GAP +
            textWidth(toast.text, Hud3Theme.PX_10, density)

    @Suppress("LongParameterList")
    private fun drawToast(
        ctx: GuiGraphicsExtractor,
        toast: Toast,
        cardWidth: Float,
        x: Float,
        y: Float,
        now: Long,
        density: Float,
        alpha: Float,
        glass: TextureSetup?,
    ) {
        if (alpha <= 0.004f) {
            return
        }
        val hue = scaled(colorOf(toast.severity), alpha)
        ctx.hud3Pill(x, y, cardWidth, Hud3Theme.ROW_26, alpha, density, glass)
        Hud3Icons.draw(
            ctx, iconOf(toast.severity), x + Hud3Theme.PAD,
            y + (Hud3Theme.ROW_26 - Hud3Theme.ICON_12) * 0.5f, Hud3Theme.ICON_12, hue,
        )
        ctx.hud3Text(
            toast.text, x + Hud3Theme.PAD + Hud3Theme.ICON_12 + Hud3Theme.GAP,
            textTopCentered(Hud3Theme.PX_10, density, y, Hud3Theme.ROW_26),
            Hud3Theme.PX_10, density, scaled(Hud3Theme.TEXT_NOTIFICATION, alpha),
        )

        val barY = y + Hud3Theme.ROW_26 - Hud3Theme.BAR_HEIGHT
        val inset = bottomEdgeInset(Hud3Theme.RADIUS, Hud3Theme.BAR_HEIGHT)
        ctx.hud3Shapes(x, barY, x + cardWidth, barY + Hud3Theme.BAR_HEIGHT, density) {
            progressBar(
                x + inset, barY, cardWidth - inset * 2f, Hud3Theme.BAR_HEIGHT,
                Hud3Theme.BAR_HEIGHT * 0.5f, toast.remaining(now), hue,
            )
        }
    }

    /**
     * Module toggles arrive as title `Enabled` / message `KillAura`; everything else as a title
     * and its detail. A missing message leaves the title alone rather than a dangling separator.
     */
    private fun compose(event: NotificationEvent): String = when (event.severity) {
        NotificationEvent.Severity.ENABLED, NotificationEvent.Severity.DISABLED ->
            "${event.message} ${event.title.lowercase()}".trim()

        else -> if (event.message.isEmpty()) event.title else "${event.title}: ${event.message}"
    }

    private fun iconOf(severity: NotificationEvent.Severity): String = when (severity) {
        NotificationEvent.Severity.SUCCESS, NotificationEvent.Severity.ENABLED -> Hud3Icons.CHECK
        NotificationEvent.Severity.ERROR -> Hud3Icons.CANCEL
        else -> Hud3Icons.INFO
    }

    private fun colorOf(severity: NotificationEvent.Severity): Int = when (severity) {
        NotificationEvent.Severity.SUCCESS, NotificationEvent.Severity.ENABLED -> Hud3Theme.OK
        NotificationEvent.Severity.ERROR -> Hud3Theme.FAIL
        else -> Hud3Theme.WARN
    }

    private companion object {
        /** More than this many pending toasts and the oldest is dropped outright. */
        const val HISTORY_LIMIT = 16
    }
}
