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
package net.ccbluex.liquidbounce.integration.screen.native

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.features.misc.proxy.Proxy
import net.ccbluex.liquidbounce.features.misc.proxy.ProxyManager
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.integration.screen.native.model.ProxyStatus
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.alphaF
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitScreen
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Native Hellhit proxy manager over the client's own [ProxyManager] (SOCKS5/HTTP): a
 * "No proxy" pill plus rows with host:port, type/credentials meta and an "in use" tag;
 * select + double-click to use; favorite/recheck/delete on hover; add modal with
 * host/port/credentials and an Add & Check flow ([ProxyManager.validateProxy] only
 * stores proxies that pass the check); clipboard paste of `host:port[:user:pass]`.
 * Check results surface through [ProxyStatus] as the status line bottom-left.
 */
@Suppress("TooManyFunctions")
internal class HellhitProxyManagerScreen : HellhitScreen("ProxyManager") {

    private companion object {
        const val ROW_H = 30f
        const val ROW_GAP = 2.5f
        const val TOP_Y = 16f
        const val FOOT_H = 26f
        const val NONE_H = 16f
        const val STATUS_FRESH_MS = 20_000L
    }

    private enum class Modal { NONE, ADD, DELETE }

    private var modal = Modal.NONE
    private var modalDrawn = Modal.NONE
    private var modalIndex = -1
    private var modalOpenedAt = 0L
    private var addType = Proxy.Type.SOCKS5

    private val search = TextInput("search", placeholder = "Search")
    private val hostInput = TextInput("host", placeholder = "Host")
    private val portInput = TextInput("port", placeholder = "Port")
    private val userInput = TextInput("user", placeholder = "Username (optional)")
    private val passInput = TextInput("pass", placeholder = "Password (optional)")

    private var selected = -1
    private var lastClickIndex = -1
    private var lastClickAt = 0L

    override fun drawBackdrop(ctx: GuiGraphicsExtractor) {
        MenuBackgrounds.drawLive(ctx, "bg_worlds", width, height)
    }

    private fun visibleProxies() = ProxyManager.proxies.withIndex()
        .filter { (_, proxy) ->
            val query = search.text.trim()
            query.isEmpty() || "${proxy.host}:${proxy.port}".contains(query, true)
        }
        .map { it.index to it.value }

    private fun use(proxy: Proxy) {
        ProxyManager.proxy = proxy
        ProxyStatus.note("Using ${proxy.host}:${proxy.port}")
    }

    // ------------------------------------------------------------------ *
    // Frame
    // ------------------------------------------------------------------ *

    @Suppress("LongMethod", "CognitiveComplexMethod")
    override fun drawContent(ctx: GuiGraphicsExtractor) {
        val a = motion
        val slabW = minOf(vw - 64f, 980f)
        val sx = (vw - slabW) / 2f

        drawTextCentered(
            ctx, WatermarkFont.BRAND, "Proxy Manager",
            sx, TOP_Y + 8f, 10f, palette.white.alphaF(a),
        )
        val total = ProxyManager.proxies.size
        if (total > 0) {
            val titleW = textWidth(WatermarkFont.BRAND, "Proxy Manager", 10f)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, "$total proxies",
                sx + titleW + 8f, TOP_Y + 9.5f, 6f, palette.dim.alphaF(a),
            )
        }
        drawTextInput(ctx, search, sx + slabW - 170f, TOP_Y + 1f, 170f, 14f, alphaF = a)

        // pinned "No proxy" pill
        val noneY = TOP_Y + 24f
        drawNoProxyPill(ctx, sx, noneY, slabW, a)

        val viewTop = noneY + NONE_H + 4f
        val viewBottom = vh - FOOT_H - 8f
        val proxies = visibleProxies()
        val contentH = proxies.size * (ROW_H + ROW_GAP)
        val scroll = updateScroll(viewBottom - viewTop, contentH)

        clipDesign(ctx, sx, viewTop, slabW, viewBottom - viewTop) {
            val yBase = viewTop - scroll
            for ((displayIndex, entry) in proxies.withIndex()) {
                val (index, proxy) = entry
                val idKey = System.identityHashCode(proxy)
                val y = yBase + animFloat("rowy:$idKey", displayIndex * (ROW_H + ROW_GAP))
                val ra = a * rowAppear("rowa:$idKey", displayIndex)
                if (ra > 0.005f && y + ROW_H >= viewTop - 4f && y <= viewBottom + 4f) {
                    drawProxyRow(ctx, index, proxy, sx, y, slabW, ra, viewTop, viewBottom)
                }
            }
            if (proxies.isEmpty()) {
                val hint = if (search.text.isEmpty()) "No proxies yet - press Add or Paste" else "No matches"
                val hw = textWidth(WatermarkFont.TEXT, hint, 6.5f)
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, hint,
                    sx + (slabW - hw) / 2f, viewTop + 24f, 6.5f, palette.dim.alphaF(a),
                )
            }
        }

        // footer: status line left, buttons right
        var bx = sx + slabW
        val by = vh - FOOT_H + 2f
        for ((label, primary, action) in footerButtons()) {
            val bw = textWidth(WatermarkFont.TEXT, label, 6.5f) + 18f
            bx -= bw
            pillButton(
                ctx, "proxy:$label", label, bx, by, bw, 16f,
                alphaF = a, primary = primary, px = 6.5f, onClick = action,
            )
            bx -= 5f
        }
        drawStatusLine(ctx, sx, bx - sx - 10f, by + 8f, a)

        drawModal(ctx, a)
    }

    private fun drawNoProxyPill(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, a: Float) {
        val active = ProxyManager.currentProxy == null
        val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + NONE_H)
        val ht = hover("proxy:none", hovered)
        val fill = watchColor(
            "proxyf:none",
            HellhitPalette.lerpColor(palette.row, palette.rowHover, ht),
        )
        ctx.drawRoundedRect(x, y, x + w, y + NONE_H, HellhitPalette.R_ROW, fill.alphaF(0.94f * a), null)
        if (active) {
            ctx.drawRoundedRect(x, y, x + w, y + NONE_H, HellhitPalette.R_ROW, null, palette.accent.alphaF(0.8f * a), 1f)
        }
        drawTextCentered(
            ctx, WatermarkFont.TEXT, "No proxy",
            x + 8f, y + NONE_H / 2f, 6.2f, (if (active) palette.white else palette.desc).alphaF(a),
        )
        if (active) {
            val tag = "direct connection"
            val tw = textWidth(WatermarkFont.TEXT, tag, 5.6f)
            drawTextCentered(ctx, WatermarkFont.TEXT, tag, x + w - tw - 8f, y + NONE_H / 2f, 5.6f, palette.dim.alphaF(a))
        }
        addHit(x, y, x + w, y + NONE_H) { button ->
            if (button == 0) {
                ProxyManager.proxy = Proxy.NONE
                selected = -1
                ProxyStatus.note("Proxy disabled")
                true
            } else {
                false
            }
        }
    }

    private fun drawStatusLine(ctx: GuiGraphicsExtractor, x: Float, maxW: Float, cy: Float, a: Float) {
        val line = ProxyStatus.last ?: return
        if (System.currentTimeMillis() - line.at > STATUS_FRESH_MS || maxW < 40f) return
        val color = if (line.error) palette.rose else palette.desc
        drawTextCentered(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, line.text, 6f, maxW), x, cy, 6f, color.alphaF(a),
        )
    }

    private fun footerButtons(): List<Triple<String, Boolean, () -> Unit>> = listOf(
        Triple("Use", true) {
            ProxyManager.proxies.getOrNull(selected)?.let(::use)
        },
        Triple("Add", false) {
            hostInput.text = ""
            portInput.text = ""
            userInput.text = ""
            passInput.text = ""
            addType = Proxy.Type.SOCKS5
            modal = Modal.ADD
            modalOpenedAt = System.currentTimeMillis()
            focusedInput = hostInput
        },
        Triple("Paste", false) { pasteFromClipboard() },
        Triple("Check", false) {
            ProxyManager.proxies.getOrNull(selected)?.let { proxy ->
                ProxyStatus.note("Checking ${proxy.host}:${proxy.port}...")
                ProxyManager.validateProxy(proxy, checkOnly = true)
            }
        },
    )

    private fun pasteFromClipboard() {
        val clipboard = mc.keyboardHandler.clipboard?.trim().orEmpty()
        if (clipboard.isEmpty()) {
            ProxyStatus.fail("Clipboard is empty")
            return
        }
        val proxy = runCatching { Proxy.parse(clipboard) }.getOrNull()
        if (proxy == null) {
            ProxyStatus.fail("Invalid proxy format - expected host:port[:user:pass]")
            return
        }
        addProxy(proxy)
    }

    /**
     * Saves the proxy to the list immediately (so it is never lost to a slow or failing
     * ping check), then validates it in the background for the status line and ip info.
     * The old flow only added a proxy if [ProxyManager.validateProxy] succeeded, and the
     * failure was hidden behind the modal scrim — so working proxies looked like they
     * "weren't adding".
     */
    private fun addProxy(proxy: Proxy) {
        ProxyManager.proxies.add(proxy)
        ConfigSystem.store(ProxyManager)
        selected = ProxyManager.proxies.lastIndex
        ProxyStatus.note("Added ${proxy.host}:${proxy.port} - checking...")
        runCatching { ProxyManager.validateProxy(proxy, checkOnly = true) }
    }

    // ------------------------------------------------------------------ *
    // Rows
    // ------------------------------------------------------------------ *

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
    private fun drawProxyRow(
        ctx: GuiGraphicsExtractor, index: Int, proxy: Proxy,
        x: Float, y: Float, w: Float, a: Float,
        viewTop: Float, viewBottom: Float,
    ) {
        val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + ROW_H) &&
            mouseYv in viewTop..viewBottom
        val ht = hover("proxyrow:$index", hovered)
        val isSelected = selected == index
        val inUse = ProxyManager.currentProxy == proxy

        val fill = watchColor(
            "proxyrowf:$index",
            HellhitPalette.lerpColor(palette.row, palette.rowHover, ht),
        )
        ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, fill.alphaF(0.94f * a), null)
        if (isSelected) {
            ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, null, palette.accent.alphaF(0.8f * a), 1f)
        }

        // name (+ favorite star) and meta
        val textX = x + 8f
        val infoW = w - 8f - 110f
        val name = clipText(WatermarkFont.BRAND, "${proxy.host}:${proxy.port}", 7f, infoW)
        drawText(ctx, WatermarkFont.BRAND, name, textX, y + 4.5f, 7f, palette.white.alphaF(a))
        if (proxy.favorite) {
            val nw = textWidth(WatermarkFont.BRAND, name, 7f)
            drawText(ctx, WatermarkFont.BRAND, "*", textX + nw + 3f, y + 5.5f, 7f, palette.accent.alphaF(a))
        }
        val meta = buildString {
            append((proxy.type ?: Proxy.Type.SOCKS5).name)
            proxy.credentials?.let { append("  ").append(it.username) }
            proxy.ipInfo?.country?.let { append("  ").append(it) }
        }
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, meta, 5.6f, infoW), textX, y + 16f, 5.6f, palette.desc.alphaF(a),
        )

        // in-use tag (crossfades into the hover controls)
        val infoA = a * (1f - ht)
        if (inUse && infoA > 0.01f) {
            val dotCx = x + w - 7f
            ctx.drawRoundedRect(
                dotCx - 1.8f, y + ROW_H / 2f - 1.8f, dotCx + 1.8f, y + ROW_H / 2f + 1.8f, 1.8f,
                palette.emerald.alphaF(infoA), null,
            )
            val tag = "in use"
            val tw = textWidth(WatermarkFont.TEXT, tag, 5.6f)
            drawText(ctx, WatermarkFont.TEXT, tag, x + w - 13f - tw, y + 5f, 5.6f, palette.dim.alphaF(infoA))
        }

        // the row hit goes in FIRST: hits are tested newest-first, so the buttons
        // registered after it win the click inside their own bounds
        addHit(x, y, x + w, y + ROW_H) { button ->
            if (button == 0) {
                val now = System.currentTimeMillis()
                if (lastClickIndex == index && now - lastClickAt < 350L) {
                    use(proxy)
                } else {
                    selected = index
                }
                lastClickIndex = index
                lastClickAt = now
                true
            } else {
                false
            }
        }

        // hover controls: check / favorite / delete
        if (ht > 0.05f) {
            val ca = a * ht
            val cy = y + (ROW_H - 12f) / 2f
            var cxx = x + w - 18f
            iconButton(ctx, "proxyctl:x:$index", RowIcon.DELETE, cxx, cy, alphaF = ca, danger = true) {
                modalIndex = index
                modal = Modal.DELETE
            }
            cxx -= 15f
            iconButton(ctx, "proxyctl:f:$index", RowIcon.STAR, cxx, cy, alphaF = ca, active = proxy.favorite) {
                proxy.favorite = !proxy.favorite
                ConfigSystem.store(ProxyManager)
            }
            cxx -= 15f
            iconButton(ctx, "proxyctl:c:$index", RowIcon.CHECK, cxx, cy, alphaF = ca) {
                ProxyStatus.note("Checking ${proxy.host}:${proxy.port}...")
                ProxyManager.validateProxy(proxy, checkOnly = true)
            }
        }
    }

    // ------------------------------------------------------------------ *
    // Modals
    // ------------------------------------------------------------------ *

    override fun onInputSubmit(input: TextInput) {
        when (modal) {
            Modal.ADD -> confirmAdd()
            else -> focusedInput = null
        }
    }

    private fun closeModal() {
        // modalIndex stays for the fade-out (the delete modal still shows the name)
        modal = Modal.NONE
        focusedInput = null
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    private fun drawModal(ctx: GuiGraphicsExtractor, a: Float) {
        val open = modal != Modal.NONE
        val ma = animUnit("modal", if (open) 1f else 0f)
        if (!open && ma <= 0.01f) return
        if (open) modalDrawn = modal
        val kind = if (open) modal else modalDrawn
        val alpha = a * ma
        // a fading-out modal stays visible but inert
        hitsEnabled = open
        drawScrim(ctx, alpha)
        addHit(0f, 0f, vw, vh) { true }

        val mw = 280f
        val mh = if (kind == Modal.ADD) 124f else 62f
        val mx = (vw - mw) / 2f
        val my = (vh - mh) / 2f

        ctx.drawRoundedRect(
            mx, my, mx + mw, my + mh, HellhitPalette.R_PANEL,
            palette.panel.alphaF(alpha), palette.panelBorder.alphaF(alpha), 1f,
        )
        val title = if (kind == Modal.ADD) "Add proxy" else "Delete proxy?"
        drawTextCentered(ctx, WatermarkFont.BRAND, title, mx + 10f, my + 11f, 7.5f, palette.white.alphaF(alpha))

        if (kind == Modal.ADD) {
            drawAddModal(ctx, alpha, mx, my, mw, mh)
        } else {
            drawDeleteModal(ctx, alpha, mx, my, mw, mh)
        }

        val cancelW = textWidth(WatermarkFont.TEXT, "Cancel", 6.5f) + 20f
        pillButton(
            ctx, "proxymodal:cancel", "Cancel",
            mx + 8f, my + mh - 21f, cancelW, 15f, alphaF = alpha, px = 6.5f,
        ) { closeModal() }
        hitsEnabled = true
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun drawAddModal(ctx: GuiGraphicsExtractor, a: Float, mx: Float, my: Float, mw: Float, mh: Float) {
        // type toggle: SOCKS5 | HTTP
        var tx = mx + 10f
        for (type in Proxy.Type.entries) {
            val tw = textWidth(WatermarkFont.TEXT, type.name, 6.2f)
            val active = type == addType
            drawTextCentered(
                ctx, WatermarkFont.TEXT, type.name, tx, my + 26f, 6.2f,
                (if (active) palette.white else palette.dim).alphaF(a),
            )
            if (active) {
                ctx.drawRoundedRect(tx, my + 32f, tx + tw, my + 33.2f, 0.6f, palette.accent.alphaF(a), null)
            }
            addHit(tx - 2f, my + 20f, tx + tw + 2f, my + 33f) { button ->
                if (button == 0) {
                    addType = type
                    true
                } else {
                    false
                }
            }
            tx += tw + 12f
        }

        val portW = 60f
        drawTextInput(ctx, hostInput, mx + 10f, my + 38f, mw - 20f - portW - 5f, 14f, alphaF = a)
        drawTextInput(ctx, portInput, mx + mw - portW - 10f, my + 38f, portW, 14f, alphaF = a)
        drawTextInput(ctx, userInput, mx + 10f, my + 56f, mw - 20f, 14f, alphaF = a)
        drawTextInput(ctx, passInput, mx + 10f, my + 74f, mw - 20f, 14f, alphaF = a)

        val bw = textWidth(WatermarkFont.TEXT, "Add & Check", 6.5f) + 20f
        pillButton(
            ctx, "proxymodal:ok", "Add & Check",
            mx + mw - bw - 8f, my + mh - 21f, bw, 15f, alphaF = a, primary = true, px = 6.5f,
        ) { confirmAdd() }
    }

    @Suppress("LongParameterList")
    private fun drawDeleteModal(ctx: GuiGraphicsExtractor, a: Float, mx: Float, my: Float, mw: Float, mh: Float) {
        val proxy = ProxyManager.proxies.getOrNull(modalIndex)
        val name = proxy?.let { "${it.host}:${it.port}" } ?: "?"
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, name, 6.5f, mw - 20f), mx + 10f, my + 22f, 6.5f,
            palette.desc.alphaF(a),
        )
        val bw = textWidth(WatermarkFont.TEXT, "Delete", 6.5f) + 20f
        pillButton(
            ctx, "proxymodal:del", "Delete",
            mx + mw - bw - 8f, my + mh - 21f, bw, 15f, alphaF = a, primary = true, px = 6.5f,
        ) {
            if (modalIndex in ProxyManager.proxies.indices) {
                val removed = ProxyManager.proxies.removeAt(modalIndex)
                if (removed == ProxyManager.proxy) {
                    ProxyManager.proxy = Proxy.NONE
                }
                ConfigSystem.store(ProxyManager)
                if (selected == modalIndex) selected = -1
            }
            closeModal()
        }
    }

    private fun confirmAdd() {
        val host = hostInput.text.trim()
        val port = portInput.text.trim().toIntOrNull()
        if (host.isEmpty()) {
            ProxyStatus.fail("Host is empty")
            return
        }
        if (port == null || port !in 1..65535) {
            ProxyStatus.fail("Invalid port")
            return
        }
        addProxy(Proxy(host, port, Proxy.credentials(userInput.text.trim(), passInput.text.trim()), addType))
        closeModal()
    }
}
