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

import net.ccbluex.liquidbounce.features.account.AccountManager
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.integration.screen.native.model.AltManagerStatus
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.alphaF
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitScreen
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.utils.client.browseUrl
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.randomUsername
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Native Hellhit alt manager over the client's own [AccountManager]: rows with account
 * type, name and uuid; the current session is tagged; select + double-click login;
 * favorite/delete/reorder on hover; Add modal with Microsoft (system browser OAuth),
 * Cracked, Session and Altening tabs; direct login; session restore. Results surface
 * through [AltManagerStatus] as the status line bottom-left.
 */
@Suppress("TooManyFunctions")
internal class HellhitAltManagerScreen : HellhitScreen("AltManager") {

    private companion object {
        const val ROW_H = 30f
        const val ROW_GAP = 2.5f
        const val TOP_Y = 16f
        const val FOOT_H = 26f
        const val STATUS_FRESH_MS = 20_000L
    }

    private enum class Modal { NONE, ADD, DIRECT, DELETE }

    private enum class AddTab(val label: String) {
        MICROSOFT("Microsoft"),
        CRACKED("Cracked"),
        SESSION("Session"),
        ALTENING("Altening"),
    }

    private var modal = Modal.NONE
    private var modalDrawn = Modal.NONE
    private var modalIndex = -1
    private var modalOpenedAt = 0L
    private var addTab = AddTab.MICROSOFT
    private var msRequested = false

    private val search = TextInput("search", placeholder = "Search")
    private val nameInput = TextInput("name", placeholder = "Username")
    private val sessionInput = TextInput("session", placeholder = "Session token")
    private val alteningInput = TextInput("altening", placeholder = "Account token")
    private val apiInput = TextInput("api", placeholder = "API token (generate)")
    private val directInput = TextInput("direct", placeholder = "Username or token")

    private var selected = -1
    private var lastClickIndex = -1
    private var lastClickAt = 0L

    override fun drawBackdrop(ctx: GuiGraphicsExtractor) {
        MenuBackgrounds.drawLive(ctx, "bg_worlds", width, height)
    }

    // ------------------------------------------------------------------ *
    // Actions (network work goes to daemon threads; results come as events)
    // ------------------------------------------------------------------ *

    private fun async(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    private fun loginIndex(index: Int) {
        AltManagerStatus.note("Logging in...")
        async("hellhit-alt-login") { AccountManager.loginAccount(index) }
    }

    private fun visibleAccounts() = AccountManager.accounts.withIndex()
        .filter { (_, account) ->
            val query = search.text.trim()
            val profile = account.profile
            query.isEmpty() || profile?.username?.contains(query, true) == true ||
                account.type.commonName.contains(query, true)
        }
        .map { it.index to it.value }

    private fun isActive(username: String?) =
        username != null && mc.user?.name.equals(username, ignoreCase = true)

    // ------------------------------------------------------------------ *
    // Frame
    // ------------------------------------------------------------------ *

    @Suppress("LongMethod", "CognitiveComplexMethod")
    override fun drawContent(ctx: GuiGraphicsExtractor) {
        val a = motion
        val slabW = minOf(vw - 64f, 980f)
        val sx = (vw - slabW) / 2f

        drawTextCentered(
            ctx, WatermarkFont.BRAND, "Alt Manager",
            sx, TOP_Y + 8f, 10f, palette.white.alphaF(a),
        )
        val total = AccountManager.accounts.size
        if (total > 0) {
            val titleW = textWidth(WatermarkFont.BRAND, "Alt Manager", 10f)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, "$total accounts",
                sx + titleW + 8f, TOP_Y + 9.5f, 6f, palette.dim.alphaF(a),
            )
        }
        drawTextInput(ctx, search, sx + slabW - 170f, TOP_Y + 1f, 170f, 14f, alphaF = a)

        val viewTop = TOP_Y + 24f
        val viewBottom = vh - FOOT_H - 8f
        val accounts = visibleAccounts()
        val contentH = accounts.size * (ROW_H + ROW_GAP)
        val scroll = updateScroll(viewBottom - viewTop, contentH)

        clipDesign(ctx, sx, viewTop, slabW, viewBottom - viewTop) {
            val yBase = viewTop - scroll
            for ((displayIndex, entry) in accounts.withIndex()) {
                val (index, account) = entry
                val idKey = System.identityHashCode(account)
                // reorders slide rows to their new slots; fresh rows cascade in
                val y = yBase + animFloat("rowy:$idKey", displayIndex * (ROW_H + ROW_GAP))
                val ra = a * rowAppear("rowa:$idKey", displayIndex)
                if (ra > 0.005f && y + ROW_H >= viewTop - 4f && y <= viewBottom + 4f) {
                    drawAccountRow(ctx, index, account, sx, y, slabW, ra, viewTop, viewBottom)
                }
            }
            if (accounts.isEmpty()) {
                val hint = if (search.text.isEmpty()) "No accounts yet - press Add" else "No matches"
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
                ctx, "alt:$label", label, bx, by, bw, 16f,
                alphaF = a, primary = primary, px = 6.5f, onClick = action,
            )
            bx -= 5f
        }
        drawStatusLine(ctx, sx, bx - sx - 10f, by + 8f, a)

        drawModal(ctx, a)
    }

    private fun drawStatusLine(ctx: GuiGraphicsExtractor, x: Float, maxW: Float, cy: Float, a: Float) {
        val line = AltManagerStatus.last ?: return
        if (System.currentTimeMillis() - line.at > STATUS_FRESH_MS || maxW < 40f) return
        val color = if (line.error) palette.rose else palette.desc
        drawTextCentered(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, line.text, 6f, maxW), x, cy, 6f, color.alphaF(a),
        )
    }

    private fun footerButtons(): List<Triple<String, Boolean, () -> Unit>> = listOf(
        Triple("Login", true) {
            if (selected in AccountManager.accounts.indices) loginIndex(selected)
        },
        Triple("Direct", false) {
            directInput.text = ""
            modal = Modal.DIRECT
            focusedInput = directInput
        },
        Triple("Add", false) {
            modal = Modal.ADD
            modalOpenedAt = System.currentTimeMillis()
            msRequested = false
        },
        Triple("Restore", false) {
            AccountManager.restoreInitial()
        },
    )

    // ------------------------------------------------------------------ *
    // Rows
    // ------------------------------------------------------------------ *

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
    private fun drawAccountRow(
        ctx: GuiGraphicsExtractor, index: Int, account: net.ccbluex.liquidbounce.authlib.account.MinecraftAccount,
        x: Float, y: Float, w: Float, a: Float,
        viewTop: Float, viewBottom: Float,
    ) {
        val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + ROW_H) &&
            mouseYv in viewTop..viewBottom
        val ht = hover("altrow:$index", hovered)
        val isSelected = selected == index

        val fill = watchColor(
            "altrowf:$index",
            HellhitPalette.lerpColor(palette.row, palette.rowHover, ht),
        )
        ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, fill.alphaF(0.94f * a), null)
        if (isSelected) {
            ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, null, palette.accent.alphaF(0.8f * a), 1f)
        }

        val username = account.profile?.username
        val uuid = account.profile?.uuid?.toString().orEmpty()

        // initial tile
        ctx.drawRoundedRect(x + 4f, y + 4f, x + 26f, y + 26f, 3f, palette.well.alphaF(a), null)
        val letter = username?.firstOrNull()?.uppercase() ?: "?"
        val lw = textWidth(WatermarkFont.BRAND, letter, 8f)
        drawTextCentered(ctx, WatermarkFont.BRAND, letter, x + 15f - lw / 2f, y + 15f, 8f, palette.desc.alphaF(a))

        // name (+ favorite star) and type/uuid meta
        val textX = x + 31f
        val infoW = w - 31f - 90f
        val name = clipText(WatermarkFont.BRAND, username ?: "<no profile>", 7f, infoW)
        drawText(ctx, WatermarkFont.BRAND, name, textX, y + 4.5f, 7f, palette.white.alphaF(a))
        if (account.favorite) {
            val nw = textWidth(WatermarkFont.BRAND, name, 7f)
            drawText(ctx, WatermarkFont.BRAND, "*", textX + nw + 3f, y + 5.5f, 7f, palette.accent.alphaF(a))
        }
        val meta = "${account.type.commonName}  $uuid"
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, meta, 5.6f, infoW), textX, y + 16f, 5.6f, palette.desc.alphaF(a),
        )

        // active-session tag (crossfades into the hover controls)
        val infoA = a * (1f - ht)
        if (isActive(username) && infoA > 0.01f) {
            val dotCx = x + w - 7f
            ctx.drawRoundedRect(
                dotCx - 1.8f, y + ROW_H / 2f - 1.8f, dotCx + 1.8f, y + ROW_H / 2f + 1.8f, 1.8f,
                palette.emerald.alphaF(infoA), null,
            )
            val tag = "active"
            val tw = textWidth(WatermarkFont.TEXT, tag, 5.6f)
            drawText(ctx, WatermarkFont.TEXT, tag, x + w - 13f - tw, y + 5f, 5.6f, palette.dim.alphaF(infoA))
        }

        // the row hit goes in FIRST: hits are tested newest-first, so the buttons
        // registered after it win the click inside their own bounds
        addHit(x, y, x + w, y + ROW_H) { button ->
            if (button == 0) {
                val now = System.currentTimeMillis()
                if (lastClickIndex == index && now - lastClickAt < 350L) {
                    loginIndex(index)
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

        // hover controls: up / down / favorite / delete
        if (ht > 0.05f) {
            val ca = a * ht
            val cy = y + (ROW_H - 12f) / 2f
            var cxx = x + w - 18f
            iconButton(ctx, "altctl:x:$index", RowIcon.DELETE, cxx, cy, alphaF = ca, danger = true) {
                modalIndex = index
                modal = Modal.DELETE
            }
            cxx -= 15f
            iconButton(ctx, "altctl:f:$index", RowIcon.STAR, cxx, cy, alphaF = ca, active = account.favorite) {
                runCatching {
                    if (account.favorite) {
                        AccountManager.unfavoriteAccount(index)
                    } else {
                        AccountManager.favoriteAccount(index)
                    }
                }
            }
            cxx -= 15f
            iconButton(ctx, "altctl:v:$index", RowIcon.DOWN, cxx, cy, alphaF = ca) {
                if (index < AccountManager.accounts.size - 1) {
                    runCatching { AccountManager.swapAccounts(index, index + 1) }
                }
            }
            cxx -= 15f
            iconButton(ctx, "altctl:u:$index", RowIcon.UP, cxx, cy, alphaF = ca) {
                if (index > 0) {
                    runCatching { AccountManager.swapAccounts(index, index - 1) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ *
    // Modals
    // ------------------------------------------------------------------ *

    override fun onInputSubmit(input: TextInput) {
        when {
            modal == Modal.ADD && addTab == AddTab.CRACKED -> confirmCracked()
            modal == Modal.ADD && addTab == AddTab.SESSION -> confirmSession()
            modal == Modal.ADD && addTab == AddTab.ALTENING && input === alteningInput -> confirmAltening()
            modal == Modal.ADD && addTab == AddTab.ALTENING && input === apiInput -> confirmAlteningGenerate()
            modal == Modal.DIRECT -> confirmDirectCracked()
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
        // a successful addition (Microsoft OAuth finishing in the browser included) closes Add
        if (modal == Modal.ADD && AltManagerStatus.lastAdditionOkAt > modalOpenedAt) {
            closeModal()
        }
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
        val mh = when (kind) {
            Modal.ADD -> if (addTab == AddTab.ALTENING) 110f else 96f
            Modal.DIRECT -> 78f
            else -> 62f
        }
        val mx = (vw - mw) / 2f
        val my = (vh - mh) / 2f

        ctx.drawRoundedRect(
            mx, my, mx + mw, my + mh, HellhitPalette.R_PANEL,
            palette.panel.alphaF(alpha), palette.panelBorder.alphaF(alpha), 1f,
        )
        val title = when (kind) {
            Modal.ADD -> "Add account"
            Modal.DIRECT -> "Direct login"
            Modal.DELETE -> "Delete account?"
            else -> ""
        }
        drawTextCentered(ctx, WatermarkFont.BRAND, title, mx + 10f, my + 11f, 7.5f, palette.white.alphaF(alpha))

        when (kind) {
            Modal.ADD -> drawAddModal(ctx, alpha, mx, my, mw, mh)
            Modal.DIRECT -> drawDirectModal(ctx, alpha, mx, my, mw, mh)
            Modal.DELETE -> drawDeleteModal(ctx, alpha, mx, my, mw, mh)
            else -> Unit
        }

        val cancelW = textWidth(WatermarkFont.TEXT, "Cancel", 6.5f) + 20f
        pillButton(
            ctx, "altmodal:cancel", "Cancel",
            mx + 8f, my + mh - 21f, cancelW, 15f, alphaF = alpha, px = 6.5f,
        ) { closeModal() }
        hitsEnabled = true
    }

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
    private fun drawAddModal(ctx: GuiGraphicsExtractor, a: Float, mx: Float, my: Float, mw: Float, mh: Float) {
        // tab strip
        var tx = mx + 10f
        for (tab in AddTab.entries) {
            val tw = textWidth(WatermarkFont.TEXT, tab.label, 6.2f)
            val active = tab == addTab
            drawTextCentered(
                ctx, WatermarkFont.TEXT, tab.label, tx, my + 26f, 6.2f,
                (if (active) palette.white else palette.dim).alphaF(a),
            )
            if (active) {
                ctx.drawRoundedRect(tx, my + 32f, tx + tw, my + 33.2f, 0.6f, palette.accent.alphaF(a), null)
            }
            addHit(tx - 2f, my + 20f, tx + tw + 2f, my + 33f) { button ->
                if (button == 0) {
                    addTab = tab
                    focusedInput = null
                    true
                } else {
                    false
                }
            }
            tx += tw + 12f
        }

        val fy = my + 38f
        when (addTab) {
            AddTab.MICROSOFT -> {
                val hint = if (msRequested) {
                    "Waiting for the browser login - press again to reopen."
                } else {
                    "The login continues in your system browser."
                }
                drawText(
                    ctx, WatermarkFont.TEXT,
                    clipText(WatermarkFont.TEXT, hint, 5.8f, mw - 20f), mx + 10f, fy + 2f, 5.8f,
                    palette.desc.alphaF(a),
                )
                val bw = textWidth(WatermarkFont.TEXT, "Open browser", 6.5f) + 20f
                pillButton(
                    ctx, "altmodal:ms", "Open browser",
                    mx + mw - bw - 8f, my + mh - 21f, bw, 15f, alphaF = a, primary = true, px = 6.5f,
                ) {
                    msRequested = true
                    AltManagerStatus.note("Opening Microsoft login...")
                    async("hellhit-alt-ms") {
                        AccountManager.newMicrosoftAccount { url -> browseUrl(url) }
                    }
                }
            }
            AddTab.CRACKED -> {
                val randomW = textWidth(WatermarkFont.TEXT, "Random", 6f) + 14f
                drawTextInput(ctx, nameInput, mx + 10f, fy, mw - 20f - randomW - 5f, 14f, alphaF = a)
                pillButton(
                    ctx, "altmodal:random", "Random",
                    mx + mw - randomW - 10f, fy, randomW, 14f, alphaF = a, px = 6f,
                ) { nameInput.text = randomUsername() }
                confirmButton(ctx, a, mx, my, mw, mh, "Add") { confirmCracked() }
            }
            AddTab.SESSION -> {
                drawTextInput(ctx, sessionInput, mx + 10f, fy, mw - 20f, 14f, alphaF = a)
                confirmButton(ctx, a, mx, my, mw, mh, "Add") { confirmSession() }
            }
            AddTab.ALTENING -> {
                drawTextInput(ctx, alteningInput, mx + 10f, fy, mw - 20f, 14f, alphaF = a)
                drawTextInput(ctx, apiInput, mx + 10f, fy + 18f, mw - 20f, 14f, alphaF = a)
                val gw = textWidth(WatermarkFont.TEXT, "Generate", 6.5f) + 20f
                pillButton(
                    ctx, "altmodal:gen", "Generate",
                    mx + mw - gw - 8f - textWidth(WatermarkFont.TEXT, "Add", 6.5f) - 25f,
                    my + mh - 21f, gw, 15f, alphaF = a, px = 6.5f,
                ) { confirmAlteningGenerate() }
                confirmButton(ctx, a, mx, my, mw, mh, "Add") { confirmAltening() }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun confirmButton(
        ctx: GuiGraphicsExtractor, a: Float,
        mx: Float, my: Float, mw: Float, mh: Float,
        label: String, action: () -> Unit,
    ) {
        val bw = textWidth(WatermarkFont.TEXT, label, 6.5f) + 20f
        pillButton(
            ctx, "altmodal:ok", label,
            mx + mw - bw - 8f, my + mh - 21f, bw, 15f, alphaF = a, primary = true, px = 6.5f,
            onClick = action,
        )
    }

    @Suppress("LongParameterList")
    private fun drawDirectModal(ctx: GuiGraphicsExtractor, a: Float, mx: Float, my: Float, mw: Float, mh: Float) {
        drawTextInput(ctx, directInput, mx + 10f, my + 20f, mw - 20f, 14f, alphaF = a)
        val cw = textWidth(WatermarkFont.TEXT, "Cracked", 6.5f) + 20f
        pillButton(
            ctx, "altmodal:dcracked", "Cracked",
            mx + mw - cw - 8f, my + mh - 21f, cw, 15f, alphaF = a, primary = true, px = 6.5f,
        ) { confirmDirectCracked() }
        val sw = textWidth(WatermarkFont.TEXT, "Session", 6.5f) + 20f
        pillButton(
            ctx, "altmodal:dsession", "Session",
            mx + mw - cw - 13f - sw, my + mh - 21f, sw, 15f, alphaF = a, px = 6.5f,
        ) { confirmDirectSession() }
    }

    @Suppress("LongParameterList")
    private fun drawDeleteModal(ctx: GuiGraphicsExtractor, a: Float, mx: Float, my: Float, mw: Float, mh: Float) {
        val name = AccountManager.accounts.getOrNull(modalIndex)?.profile?.username ?: "?"
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, name, 6.5f, mw - 20f), mx + 10f, my + 22f, 6.5f,
            palette.desc.alphaF(a),
        )
        val bw = textWidth(WatermarkFont.TEXT, "Delete", 6.5f) + 20f
        pillButton(
            ctx, "altmodal:del", "Delete",
            mx + mw - bw - 8f, my + mh - 21f, bw, 15f, alphaF = a, primary = true, px = 6.5f,
        ) {
            if (modalIndex in AccountManager.accounts.indices) {
                AccountManager.removeAccount(modalIndex)
                if (selected == modalIndex) selected = -1
            }
            closeModal()
        }
    }

    // ------------------------------------------------------------------ *
    // Confirm actions
    // ------------------------------------------------------------------ *

    private fun confirmCracked() {
        val name = nameInput.text.trim()
        if (name.isEmpty()) return
        AccountManager.newCrackedAccount(name)
    }

    private fun confirmSession() {
        val token = sessionInput.text.trim()
        if (token.isEmpty()) return
        AltManagerStatus.note("Checking session token...")
        async("hellhit-alt-session") { AccountManager.newSessionAccount(token) }
    }

    private fun confirmAltening() {
        val token = alteningInput.text.trim()
        if (token.isEmpty()) return
        AltManagerStatus.note("Adding altening account...")
        async("hellhit-alt-altening") { AccountManager.newAlteningAccount(token) }
    }

    private fun confirmAlteningGenerate() {
        val token = apiInput.text.trim()
        if (token.isEmpty()) return
        AltManagerStatus.note("Generating altening account...")
        async("hellhit-alt-altening-gen") { AccountManager.generateAlteningAccount(token) }
    }

    private fun confirmDirectCracked() {
        val name = directInput.text.trim()
        if (name.isEmpty()) return
        AltManagerStatus.note("Logging in...")
        async("hellhit-alt-direct") { AccountManager.loginCrackedAccount(name) }
        closeModal()
    }

    private fun confirmDirectSession() {
        val token = directInput.text.trim()
        if (token.isEmpty()) return
        AltManagerStatus.note("Logging in...")
        async("hellhit-alt-direct-session") { AccountManager.loginSessionAccount(token) }
        closeModal()
    }
}
