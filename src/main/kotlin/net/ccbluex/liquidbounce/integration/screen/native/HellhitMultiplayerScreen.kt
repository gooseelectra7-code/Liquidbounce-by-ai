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

import net.ccbluex.liquidbounce.utils.client.openVfpProtocolSelection
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.account.AccountManager
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.features.spoofer.SpooferManager
import net.ccbluex.liquidbounce.integration.screen.native.model.AltManagerStatus
import net.ccbluex.liquidbounce.integration.screen.native.model.ActiveServerList
import net.ccbluex.liquidbounce.integration.screen.native.model.servers
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.alphaF
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitScreen
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.getOrPutNullable
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import java.io.ByteArrayInputStream

/**
 * Native Hellhit multiplayer screen over the client's own [ActiveServerList] (the same
 * vanilla `ServerList` + pinger the browser UI used): rows with favicon, name, motd,
 * ping and a status dot; select + double-click join; add/edit/delete/reorder; direct
 * connect; live search. Sub-flows without a design of their own (VFP protocol picker)
 * open their native screens.
 */
@Suppress("TooManyFunctions")
internal class HellhitMultiplayerScreen : HellhitScreen("Multiplayer") {

    private companion object {
        const val ROW_H = 30f
        const val ROW_GAP = 2.5f
        const val TOP_Y = 16f
        const val FOOT_H = 26f

        /** Height of the quick-accounts strip above the footer. */
        const val ACC_H = 30f

        /** Favicon textures keyed by server ip; cleared per screen instance. */
        val icons = HashMap<String, TextureSetup?>()

        /** Friendly labels for the spoofer groups. */
        val SPOOFER_LABELS = mapOf(
            "ClientSpoofer" to "Client Brand",
            "ResourceSpoofer" to "Resource Pack",
            "BungeecordSpoofer" to "BungeeCord / UUID",
            "FingerprintSpoofer" to "Fingerprint",
        )
    }

    private enum class Tab { SERVERS, OPSEC }

    private var tab = Tab.SERVERS

    private enum class Modal { NONE, ADD, EDIT, DIRECT, DELETE }

    private var modal = Modal.NONE
    private var modalDrawn = Modal.NONE
    private var modalIndex = -1

    private val search = TextInput("search", placeholder = "Search")
    private val nameInput = TextInput("name", placeholder = "Name")
    private val addressInput = TextInput("address", placeholder = "Address")

    private var selected = -1
    private var lastClickIndex = -1
    private var lastClickAt = 0L

    override fun init() {
        super.init()
        // Drop only the negative entries, so a favicon that arrives with this ping round is picked
        // up. Successful entries stay: their DynamicTexture is owned by this cache and nothing
        // would close it if we dropped the reference.
        icons.entries.removeIf { it.value == null }
        ActiveServerList.pingThemAll()
    }

    override fun drawBackdrop(ctx: GuiGraphicsExtractor) {
        MenuBackgrounds.drawLive(ctx, "bg_worlds", width, height)
    }

    private fun iconOf(server: ServerData): TextureSetup? {
        val bytes = server.iconBytes ?: return null
        return icons.getOrPutNullable(server.ip) {
            runCatching {
                val image = ByteArrayInputStream(bytes).readNativeImage()
                val texture = image.asTexture { "HellhitServer-${server.ip}" }
                TextureSetup.singleTexture(
                    texture.textureView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
                )
            }.getOrNull()
        }
    }

    private fun visibleServers(): List<Pair<Int, ServerData>> {
        val query = search.text.trim()
        return ActiveServerList.serverList.servers.withIndex()
            .filter { (_, server) ->
                query.isEmpty() || server.name.contains(query, true) || server.ip.contains(query, true)
            }
            .map { it.index to it.value }
    }

    private fun join(server: ServerData) {
        val address = runCatching { ServerAddress.parseString(server.ip) }.getOrNull() ?: return
        ConnectScreen.startConnecting(JoinMultiplayerScreen(TitleScreen()), mc, address, server, false, null)
    }

    private fun stateDot(server: ServerData): Color4b = when (server.state()) {
        ServerData.State.SUCCESSFUL -> palette.emerald
        ServerData.State.PINGING -> Color4b(245, 191, 79, 255)
        ServerData.State.UNREACHABLE -> palette.rose
        ServerData.State.INCOMPATIBLE -> Color4b(245, 191, 79, 255)
        else -> palette.dim
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    override fun drawContent(ctx: GuiGraphicsExtractor) {
        val a = motion
        val slabW = minOf(vw - 64f, 980f)
        val sx = (vw - slabW) / 2f

        // header: Servers / OpSec tabs on the left, search on the right
        drawHeaderTabs(ctx, sx, a)
        drawTextInput(ctx, search, sx + slabW - 170f, TOP_Y + 1f, 170f, 14f, alphaF = a)

        if (tab == Tab.OPSEC) {
            drawOpsecTab(ctx, sx, slabW, a)
            drawModal(ctx, a)
            return
        }

        // list viewport: the full height between header and the accounts strip + footer
        val viewTop = TOP_Y + 24f
        val viewBottom = vh - FOOT_H - ACC_H - 10f
        val servers = visibleServers()
        val contentH = servers.size * (ROW_H + ROW_GAP)
        val scroll = updateScroll(viewBottom - viewTop, contentH)

        clipDesign(ctx, sx, viewTop, slabW, viewBottom - viewTop) {
            val yBase = viewTop - scroll
            for ((displayIndex, entry) in servers.withIndex()) {
                val (index, server) = entry
                val idKey = System.identityHashCode(server)
                // reorders slide rows to their new slots; fresh rows cascade in
                val y = yBase + animFloat("rowy:$idKey", displayIndex * (ROW_H + ROW_GAP))
                val ra = a * rowAppear("rowa:$idKey", displayIndex)
                if (ra > 0.005f && y + ROW_H >= viewTop - 4f && y <= viewBottom + 4f) {
                    drawServerRow(ctx, index, server, sx, y, slabW, ra, viewTop, viewBottom)
                }
            }
            if (servers.isEmpty()) {
                val hint = if (search.text.isEmpty()) "No servers yet - press Add" else "No matches"
                val hw = textWidth(WatermarkFont.TEXT, hint, 6.5f)
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, hint,
                    sx + (slabW - hw) / 2f, viewTop + 24f, 6.5f, palette.dim.alphaF(a),
                )
            }
        }

        // quick accounts strip above the footer
        drawAccountStrip(ctx, sx, vh - FOOT_H - ACC_H - 4f, slabW, a)

        // footer buttons, bottom-right
        var bx = sx + slabW
        val by = vh - FOOT_H + 2f
        for ((label, primary, action) in footerButtons()) {
            val bw = textWidth(WatermarkFont.TEXT, label, 6.5f) + 18f
            bx -= bw
            pillButton(
                ctx, "mp:$label", label, bx, by, bw, 16f,
                alphaF = a, primary = primary, px = 6.5f, onClick = action,
            )
            bx -= 5f
        }
        drawLoginStatus(ctx, sx, bx - sx - 10f, by + 8f, a)

        drawModal(ctx, a)
    }

    // ------------------------------------------------------------------ *
    // Header tabs
    // ------------------------------------------------------------------ *

    private fun drawHeaderTabs(ctx: GuiGraphicsExtractor, sx: Float, a: Float) {
        var tx = sx
        val cy = TOP_Y + 8f
        for ((t, label) in listOf(Tab.SERVERS to "Servers", Tab.OPSEC to "OpSec")) {
            val tw = textWidth(WatermarkFont.BRAND, label, 10f)
            val active = tab == t
            val hovered = mouseXv in tx..(tx + tw) && mouseYv in (TOP_Y - 2f)..(TOP_Y + 17f)
            val ht = hover("mptab:$label", hovered)
            val color = watchColor(
                "mptabc:$label",
                if (active) palette.white else HellhitPalette.lerpColor(palette.dim, palette.textOff, ht),
            )
            drawTextCentered(ctx, WatermarkFont.BRAND, label, tx, cy, 10f, color.alphaF(a))
            if (active) {
                ctx.drawRoundedRect(tx, cy + 8f, tx + tw, cy + 9.2f, 0.6f, palette.accent.alphaF(a), null)
            }
            addHit(tx - 3f, TOP_Y - 2f, tx + tw + 3f, TOP_Y + 17f) { button ->
                if (button == 0) {
                    if (tab != t) {
                        tab = t
                        scrollTarget = 0f
                        selected = -1
                        modal = Modal.NONE
                    }
                    true
                } else {
                    false
                }
            }
            tx += tw + 14f
        }
        if (tab == Tab.SERVERS) {
            val total = ActiveServerList.serverList.servers.size
            if (total > 0) {
                drawTextCentered(ctx, WatermarkFont.TEXT, "$total servers", tx, TOP_Y + 9.5f, 6f, palette.dim.alphaF(a))
            }
        }
    }

    // ------------------------------------------------------------------ *
    // OpSec tab (spoofers & protection modules, toggled before joining)
    // ------------------------------------------------------------------ *

    /** TextInputs bound to spoofer string values, keyed by value identity. */
    private val spooferInputs = HashMap<Int, TextInput>()

    private var opTop = 0f
    private var opBottom = 0f
    private var opAlpha = 1f

    private fun opVisible(y: Float, h: Float): Boolean = y + h >= opTop - 4f && y <= opBottom + 4f

    private fun drawOpsecTab(ctx: GuiGraphicsExtractor, sx: Float, slabW: Float, a: Float) {
        opTop = TOP_Y + 24f
        opBottom = vh - 12f
        opAlpha = a
        val colW = minOf(slabW, 470f)
        val colX = sx + (slabW - colW) / 2f
        val contentH = layoutSpoofers(null, colX, 0f, colW)
        val scroll = updateScroll(opBottom - opTop, contentH)
        clipDesign(ctx, sx, opTop, slabW, opBottom - opTop) {
            layoutSpoofers(ctx, colX, opTop - scroll, colW)
        }
    }

    /** Lays out every top-level spoofer group; null [ctx] measures (returns total height). */
    private fun layoutSpoofers(ctx: GuiGraphicsExtractor?, x: Float, yStart: Float, w: Float): Float {
        var y = yStart
        for (group in SpooferManager.inner) {
            if (group !is ToggleableValueGroup) continue
            y = layoutSpoofer(ctx, group, x, y, w, top = true)
            y += 4f
        }
        return y - yStart
    }

    @Suppress("LongParameterList")
    private fun layoutSpoofer(
        ctx: GuiGraphicsExtractor?, group: ToggleableValueGroup, x: Float, y: Float, w: Float, top: Boolean,
    ): Float {
        val rowH = if (top) 30f else 22f
        val a = opAlpha
        val id = System.identityHashCode(group)
        if (ctx != null && opVisible(y, rowH)) {
            val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + rowH) && mouseYv in opTop..opBottom
            val ht = hover("spf:$id", hovered)
            val fill = watchColor("spff:$id", HellhitPalette.lerpColor(palette.row, palette.rowHover, ht))
            ctx.drawRoundedRect(x, y, x + w, y + rowH, HellhitPalette.R_ROW, fill.alphaF(0.94f * a), null)
            val label = if (top) SPOOFER_LABELS[group.name] ?: group.name else group.name
            drawText(ctx, WatermarkFont.BRAND, label, x + 10f, y + (if (top) 6f else 6.5f),
                if (top) 7.5f else 6.5f, palette.white.alphaF(a))
            if (top) {
                val desc = runCatching { group.description.get() }.getOrNull().orEmpty()
                if (desc.isNotEmpty()) {
                    drawText(ctx, WatermarkFont.TEXT, clipText(WatermarkFont.TEXT, desc, 5.8f, w - 60f),
                        x + 10f, y + 18f, 5.8f, palette.desc.alphaF(a))
                }
            }
            drawSwitch(ctx, "spfsw:$id", group.enabled, x + w - 34f, y + (rowH - 12f) / 2f, a)
            addHit(x, y, x + w, y + rowH) { button ->
                if (button == 0) {
                    group.enabled = !group.enabled
                    true
                } else {
                    false
                }
            }
        }
        var yy = y + rowH
        if (group.enabled) {
            yy = layoutInner(ctx, group, x + 12f, yy + 2f, w - 12f)
        }
        return yy
    }

    private fun layoutInner(ctx: GuiGraphicsExtractor?, group: ValueGroup, x: Float, y: Float, w: Float): Float {
        var yy = y
        for (v in group.inner) {
            if (v.name == "Enabled" || v.name == "Hidden" || v.notAnOption) continue
            yy = layoutValue(ctx, v, x, yy, w)
        }
        return yy
    }

    private fun layoutValue(ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, y: Float, w: Float): Float = when {
        value is ModeValueGroup<*> -> layoutInner(ctx, value.activeMode, x + 8f, drawModeChips(ctx, value, x, y, w), w - 8f)
        value is ToggleableValueGroup -> layoutSpoofer(ctx, value, x, y, w, top = false)
        value.valueType == ValueType.TEXT -> drawTextRow(ctx, value, x, y, w)
        value.valueType == ValueType.BOOLEAN -> drawBoolRow(ctx, value, x, y, w)
        else -> y
    }

    private fun drawModeChips(ctx: GuiGraphicsExtractor?, mode: ModeValueGroup<*>, x: Float, y: Float, w: Float): Float {
        val h = 20f
        val a = opAlpha
        if (ctx != null && opVisible(y, h)) {
            drawText(ctx, WatermarkFont.TEXT, mode.name, x, y + 2f, 5.8f, palette.dim.alphaF(a))
            var cx = x
            val cy = y + 8f
            val id = System.identityHashCode(mode)
            for (m in mode.modes) {
                val tag = m.tag
                val active = mode.activeMode.tag == tag
                val cw = textWidth(WatermarkFont.TEXT, tag, 5.8f) + 12f
                val hovered = mouseXv in cx..(cx + cw) && mouseYv in cy..(cy + 11f)
                val ht = hover("chip:$id:$tag", hovered)
                val fill = watchColor("chipf:$id:$tag",
                    if (active) palette.accent else HellhitPalette.lerpColor(palette.row, palette.rowHover, ht))
                ctx.drawRoundedRect(cx, cy, cx + cw, cy + 11f, HellhitPalette.R_CTRL, fill.alphaF(0.94f * a), null)
                val tw = textWidth(WatermarkFont.TEXT, tag, 5.8f)
                drawTextCentered(ctx, WatermarkFont.TEXT, tag, cx + (cw - tw) / 2f, cy + 5.5f, 5.8f,
                    (if (active) palette.ink else palette.textOff).alphaF(a))
                addHit(cx, cy, cx + cw, cy + 11f) { button ->
                    if (button == 0) {
                        mode.setByString(tag)
                        true
                    } else {
                        false
                    }
                }
                cx += cw + 4f
            }
        }
        return y + h
    }

    private fun drawTextRow(ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, y: Float, w: Float): Float {
        val h = 18f
        val a = opAlpha
        if (ctx != null && opVisible(y, h)) {
            val id = System.identityHashCode(value)
            val input = spooferInputs.getOrPut(id) { TextInput("spf$id", placeholder = value.name) }
            if (focusedInput !== input) input.text = value.get()?.toString().orEmpty()
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + 5.5f, 5.8f, palette.dim.alphaF(a))
            val fx = x + textWidth(WatermarkFont.TEXT, value.name, 5.8f) + 8f
            drawTextInput(ctx, input, fx, y + 1f, (x + w) - fx, 14f, px = 6f, alphaF = a)
            if (focusedInput === input) runCatching { value.setByString(input.text) }
        }
        return y + h
    }

    private fun drawBoolRow(ctx: GuiGraphicsExtractor?, value: Value<*>, x: Float, y: Float, w: Float): Float {
        val h = 18f
        val a = opAlpha
        if (ctx != null && opVisible(y, h)) {
            @Suppress("UNCHECKED_CAST") val v = value as Value<Boolean>
            drawText(ctx, WatermarkFont.TEXT, value.name, x, y + 5.5f, 5.8f, palette.desc.alphaF(a))
            drawSwitch(ctx, "spfb:${System.identityHashCode(value)}", v.get(), x + w - 34f, y + (h - 12f) / 2f, a)
            addHit(x, y, x + w, y + h) { button ->
                if (button == 0) {
                    v.set(!v.get())
                    true
                } else {
                    false
                }
            }
        }
        return y + h
    }

    /** Compact animated on/off switch (design units). */
    private fun drawSwitch(ctx: GuiGraphicsExtractor, key: String, on: Boolean, x: Float, y: Float, a: Float) {
        val w = 24f
        val h = 12f
        val prog = animUnit("$key:p", if (on) 1f else 0f)
        val track = watchColor("$key:t", if (on) palette.accent else palette.well)
        ctx.drawRoundedRect(x, y, x + w, y + h, h / 2f, track.alphaF(0.95f * a), palette.hairline.alphaF(a), 1f)
        val knobX = x + 2f + prog * (w - h)
        ctx.drawRoundedRect(
            knobX, y + 2f, knobX + (h - 4f), y + h - 2f, (h - 4f) / 2f,
            (if (on) palette.ink else palette.textOff).alphaF(a), null,
        )
    }

    // ------------------------------------------------------------------ *
    // Quick accounts strip (the useful slice of the alt manager)
    // ------------------------------------------------------------------ *

    private fun quickLogin(index: Int) {
        AltManagerStatus.note("Logging in...")
        Thread({ AccountManager.loginAccount(index) }, "hellhit-mp-login").apply { isDaemon = true }.start()
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    private fun drawAccountStrip(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, a: Float) {
        val chipH = 20f
        val cy = y + (ACC_H - chipH) / 2f
        val centerY = y + ACC_H / 2f

        var cx = x
        drawTextCentered(ctx, WatermarkFont.TEXT, "Accounts", cx, centerY, 6f, palette.dim.alphaF(a))
        cx += textWidth(WatermarkFont.TEXT, "Accounts", 6f) + 8f

        // "Manage" pinned right; chips fill the room in between
        val manageW = textWidth(WatermarkFont.TEXT, "Manage", 6f) + 16f
        val maxX = x + w - manageW - 8f

        var hidden = 0
        for ((index, account) in AccountManager.accounts.withIndex()) {
            val username = account.profile?.username ?: continue
            val nameW = textWidth(WatermarkFont.TEXT, username, 6.2f)
            val chipW = 14f + 4f + nameW + 12f
            if (cx + chipW > maxX) {
                hidden++
                continue
            }

            val idKey = System.identityHashCode(account)
            val active = mc.user?.name.equals(username, ignoreCase = true)
            val hovered = mouseXv in cx..(cx + chipW) && mouseYv in cy..(cy + chipH)
            val ht = hover("mpacc:$idKey", hovered)
            val fill = watchColor(
                "mpaccf:$idKey",
                HellhitPalette.lerpColor(palette.row, palette.rowHover, ht),
            )
            ctx.drawRoundedRect(
                cx, cy, cx + chipW, cy + chipH, HellhitPalette.R_ROW,
                fill.alphaF(0.94f * a),
                (if (active) palette.accent else palette.hairline).alphaF(if (active) 0.8f * a else a), 1f,
            )
            ctx.drawRoundedRect(cx + 3f, cy + 3f, cx + 17f, cy + chipH - 3f, 2.5f, palette.well.alphaF(a), null)
            val letter = username.first().uppercase()
            val lw = textWidth(WatermarkFont.BRAND, letter, 6f)
            drawTextCentered(ctx, WatermarkFont.BRAND, letter, cx + 10f - lw / 2f, centerY, 6f, palette.desc.alphaF(a))
            drawTextCentered(
                ctx, WatermarkFont.TEXT, username, cx + 21f, centerY, 6.2f,
                (if (active) palette.white else palette.desc).alphaF(a),
            )
            if (account.favorite) {
                drawTextCentered(ctx, WatermarkFont.BRAND, "*", cx + 21f + nameW + 2f, centerY + 1f, 6f,
                    palette.accent.alphaF(a))
            }
            addHit(cx, cy, cx + chipW, cy + chipH) { button ->
                if (button == 0) {
                    if (!active) quickLogin(index)
                    true
                } else {
                    false
                }
            }
            cx += chipW + 5f
        }

        if (AccountManager.accounts.isEmpty()) {
            drawTextCentered(
                ctx, WatermarkFont.TEXT, "none yet - press Manage",
                cx, centerY, 6f, palette.dim.alphaF(a),
            )
        } else if (hidden > 0) {
            drawTextCentered(ctx, WatermarkFont.TEXT, "+$hidden", cx + 2f, centerY, 6f, palette.dim.alphaF(a))
        }

        pillButton(
            ctx, "mpacc:manage", "Manage",
            x + w - manageW, cy, manageW, chipH, alphaF = a, px = 6f,
        ) { NativeScreens.openAltManager() }
    }

    private fun drawLoginStatus(ctx: GuiGraphicsExtractor, x: Float, maxW: Float, cy: Float, a: Float) {
        val line = AltManagerStatus.last ?: return
        if (System.currentTimeMillis() - line.at > 20_000L || maxW < 40f) return
        val color = if (line.error) palette.rose else palette.desc
        drawTextCentered(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, line.text, 6f, maxW), x, cy, 6f, color.alphaF(a),
        )
    }

    private fun footerButtons(): List<Triple<String, Boolean, () -> Unit>> = buildList {
        add(
            Triple("Join", true) {
                selectedServer()?.let(::join)
                Unit
            },
        )
        add(
            Triple("Direct", false) {
                // vanilla remembers the last direct-connect address here
                addressInput.text = mc.options.lastMpIp.orEmpty()
                modal = Modal.DIRECT
                focusedInput = addressInput
            },
        )
        add(
            Triple("Add", false) {
                nameInput.text = ""
                addressInput.text = ""
                modal = Modal.ADD
                focusedInput = nameInput
            },
        )
        add(
            Triple("Refresh", false) {
                ActiveServerList.pingThemAll()
            },
        )
        // optional integration: only when ViaFabricPlus is actually loaded
        if (FabricLoader.getInstance().isModLoaded("viafabricplus")) {
            add(
                Triple("Protocol", false) {
                    mc.execute(::openVfpProtocolSelection)
                },
            )
        }
    }

    private fun selectedServer(): ServerData? =
        ActiveServerList.serverList.servers.getOrNull(selected)

    @Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
    private fun drawServerRow(
        ctx: GuiGraphicsExtractor, index: Int, server: ServerData,
        x: Float, y: Float, w: Float, a: Float,
        viewTop: Float, viewBottom: Float,
    ) {
        val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + ROW_H) &&
            mouseYv in viewTop..viewBottom
        val ht = hover("mprow:$index", hovered)
        val isSelected = selected == index

        val fill = watchColor(
            "mprowf:$index",
            HellhitPalette.lerpColor(palette.row, palette.rowHover, ht),
        )
        ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, fill.alphaF(0.94f * a), null)
        if (isSelected) {
            ctx.drawRoundedRect(
                x, y, x + w, y + ROW_H, HellhitPalette.R_ROW,
                null, palette.accent.alphaF(0.8f * a), 1f,
            )
        }

        // favicon
        val icon = iconOf(server)
        ctx.drawRoundedRect(x + 4f, y + 4f, x + 26f, y + 26f, 3f, palette.well.alphaF(a), null)
        if (icon != null) {
            ctx.drawTexQuad(icon, x + 4.5f, y + 4.5f, x + 25.5f, y + 25.5f)
        }

        // name + motd
        val textX = x + 31f
        val infoW = w - 31f - 74f
        drawText(
            ctx, WatermarkFont.BRAND,
            clipText(WatermarkFont.BRAND, server.name, 7f, infoW), textX, y + 4.5f, 7f, palette.white.alphaF(a),
        )
        val motd = server.motd?.string?.replace('\n', ' ')?.trim().orEmpty()
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, motd.ifEmpty { server.ip }, 5.6f, infoW),
            textX, y + 16f, 5.6f, palette.desc.alphaF(a),
        )

        // right column: ping + players + state dot (crossfades into the hover controls)
        val infoA = a * (1f - ht)
        if (infoA > 0.01f) {
            val dotCx = x + w - 7f
            ctx.drawRoundedRect(dotCx - 1.8f, y + ROW_H / 2f - 1.8f, dotCx + 1.8f, y + ROW_H / 2f + 1.8f, 1.8f,
                stateDot(server).alphaF(infoA), null)
            if (server.ping > 0) {
                val pingText = "${server.ping}ms"
                val pw = textWidth(WatermarkFont.TEXT, pingText, 5.6f)
                drawText(ctx, WatermarkFont.TEXT, pingText, x + w - 13f - pw, y + 5f, 5.6f, palette.dim.alphaF(infoA))
            }
            val players = server.players
            if (players != null) {
                val playersText = "${players.online}/${players.max}"
                val pw = textWidth(WatermarkFont.TEXT, playersText, 5.6f)
                drawText(
                    ctx, WatermarkFont.TEXT, playersText,
                    x + w - 13f - pw, y + 16f, 5.6f, palette.dim.alphaF(infoA),
                )
            }
        }

        // the row hit goes in FIRST: hits are tested newest-first, so the buttons
        // registered after it win the click inside their own bounds
        addHit(x, y, x + w, y + ROW_H) { button ->
            if (button == 0) {
                val now = System.currentTimeMillis()
                if (lastClickIndex == index && now - lastClickAt < 350L) {
                    join(server)
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

        // hover controls: up / down / edit / delete
        if (ht > 0.05f) {
            val ca = a * ht
            val cy = y + (ROW_H - 12f) / 2f
            var cxx = x + w - 18f
            iconButton(ctx, "mpctl:x:$index", RowIcon.DELETE, cxx, cy, alphaF = ca, danger = true) {
                modalIndex = index
                modal = Modal.DELETE
            }
            cxx -= 15f
            iconButton(ctx, "mpctl:e:$index", RowIcon.EDIT, cxx, cy, alphaF = ca) {
                modalIndex = index
                nameInput.text = server.name
                addressInput.text = server.ip
                modal = Modal.EDIT
                focusedInput = nameInput
            }
            cxx -= 15f
            iconButton(ctx, "mpctl:v:$index", RowIcon.DOWN, cxx, cy, alphaF = ca) {
                if (index < ActiveServerList.serverList.servers.size - 1) {
                    ActiveServerList.serverList.swap(index, index + 1)
                    ActiveServerList.serverList.save()
                }
            }
            cxx -= 15f
            iconButton(ctx, "mpctl:u:$index", RowIcon.UP, cxx, cy, alphaF = ca) {
                if (index > 0) {
                    ActiveServerList.serverList.swap(index, index - 1)
                    ActiveServerList.serverList.save()
                }
            }
        }
    }

    // ------------------------------------------------------------------ *
    // Modals
    // ------------------------------------------------------------------ *

    override fun onInputSubmit(input: TextInput) {
        when (modal) {
            Modal.ADD, Modal.EDIT, Modal.DIRECT -> confirmModal()
            else -> focusedInput = null
        }
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
        // swallow clicks outside the modal slab
        addHit(0f, 0f, vw, vh) { true }

        val fields = when (kind) {
            Modal.ADD, Modal.EDIT -> listOf(nameInput, addressInput)
            Modal.DIRECT -> listOf(addressInput)
            else -> emptyList()
        }
        val mw = 240f
        val mh = 46f + fields.size * 20f
        val mx = (vw - mw) / 2f
        val my = (vh - mh) / 2f

        ctx.drawRoundedRect(
            mx, my, mx + mw, my + mh, HellhitPalette.R_PANEL,
            palette.panel.alphaF(alpha), palette.panelBorder.alphaF(alpha), 1f,
        )
        val title = when (kind) {
            Modal.ADD -> "Add server"
            Modal.EDIT -> "Edit server"
            Modal.DIRECT -> "Direct connect"
            Modal.DELETE -> "Delete server?"
            else -> ""
        }
        drawTextCentered(ctx, WatermarkFont.BRAND, title, mx + 10f, my + 11f, 7.5f, palette.white.alphaF(alpha))

        var fy = my + 20f
        for (field in fields) {
            drawTextInput(ctx, field, mx + 10f, fy, mw - 20f, 14f, alphaF = alpha)
            fy += 20f
        }
        if (kind == Modal.DELETE) {
            val name = ActiveServerList.serverList.servers.getOrNull(modalIndex)?.name ?: "?"
            drawText(
                ctx, WatermarkFont.TEXT,
                clipText(WatermarkFont.TEXT, name, 6.5f, mw - 20f), mx + 10f, fy + 2f, 6.5f,
                palette.desc.alphaF(alpha),
            )
        }

        val confirmLabel = if (kind == Modal.DELETE) "Delete" else "Save"
        val cw = textWidth(WatermarkFont.TEXT, confirmLabel, 6.5f) + 20f
        pillButton(
            ctx, "mpmodal:ok", confirmLabel,
            mx + mw - cw - 8f, my + mh - 21f, cw, 15f, alphaF = alpha, primary = true, px = 6.5f,
        ) { confirmModal() }
        val cancelW = textWidth(WatermarkFont.TEXT, "Cancel", 6.5f) + 20f
        pillButton(
            ctx, "mpmodal:cancel", "Cancel",
            mx + mw - cw - 13f - cancelW, my + mh - 21f, cancelW, 15f, alphaF = alpha, px = 6.5f,
        ) { closeModal() }
        hitsEnabled = true
    }

    private fun closeModal() {
        // modalIndex stays for the fade-out (the delete modal still shows the name)
        modal = Modal.NONE
        focusedInput = null
    }

    @Suppress("CognitiveComplexMethod", "ReturnCount")
    private fun confirmModal() {
        when (modal) {
            Modal.ADD -> {
                val address = addressInput.text.trim()
                if (address.isEmpty() || !ServerAddress.isValidAddress(address)) return
                val server = ServerData(nameInput.text.trim().ifEmpty { address }, address, ServerData.Type.OTHER)
                ActiveServerList.serverList.add(server, false)
                ActiveServerList.serverList.save()
                ActiveServerList.ping(server)
            }
            Modal.EDIT -> {
                val server = ActiveServerList.serverList.servers.getOrNull(modalIndex) ?: return
                val address = addressInput.text.trim()
                if (address.isEmpty() || !ServerAddress.isValidAddress(address)) return
                server.name = nameInput.text.trim().ifEmpty { address }
                server.ip = address
                ActiveServerList.serverList.save()
            }
            Modal.DIRECT -> {
                val address = addressInput.text.trim()
                if (address.isEmpty() || !ServerAddress.isValidAddress(address)) return
                mc.options.lastMpIp = address
                mc.options.save()
                join(ServerData("Direct Connect", address, ServerData.Type.OTHER))
            }
            Modal.DELETE -> {
                val server = ActiveServerList.serverList.servers.getOrNull(modalIndex) ?: return
                ActiveServerList.serverList.remove(server)
                ActiveServerList.serverList.save()
                if (selected == modalIndex) selected = -1
            }
            else -> Unit
        }
        closeModal()
    }
}
