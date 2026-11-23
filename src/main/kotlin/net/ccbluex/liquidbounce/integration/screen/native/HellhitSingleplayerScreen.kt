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

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.features.module.modules.render.WatermarkFont
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitPalette.alphaF
import net.ccbluex.liquidbounce.integration.screen.native.ui.HellhitScreen
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.getOrPutNullable
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.readNativeImage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen
import net.minecraft.world.level.storage.LevelSummary
import java.util.concurrent.TimeUnit

/**
 * Native Hellhit singleplayer screen: async level summaries, rows with world icon,
 * name and meta line, select + double-click play, Create/Edit (vanilla sub-screens),
 * delete confirm, live search.
 */
internal class HellhitSingleplayerScreen : HellhitScreen("Singleplayer") {

    private companion object {
        const val ROW_H = 30f
        const val ROW_GAP = 2.5f
        const val TOP_Y = 16f
        const val FOOT_H = 26f

        val icons = HashMap<String, TextureSetup?>()
    }

    private val search = TextInput("search", placeholder = "Search")

    @Volatile
    private var worlds: List<LevelSummary> = emptyList()

    @Volatile
    private var loading = true

    private var selected = -1
    private var lastClickIndex = -1
    private var lastClickAt = 0L
    private var deleteConfirm = -1
    private var deleteDrawn = -1

    override fun init() {
        super.init()
        reload()
    }

    override fun drawBackdrop(ctx: GuiGraphicsExtractor) {
        MenuBackgrounds.drawLive(ctx, "bg_worlds", width, height)
    }

    private fun reload() {
        loading = true
        selected = -1
        // Drop only the negative entries: a world that had no icon.png last time may have one now.
        // Successful entries stay, because their DynamicTexture is owned by this cache and nothing
        // would close it if we dropped the reference.
        icons.entries.removeIf { it.value == null }
        runCatching {
            val candidates = mc.levelSource.findLevelCandidates()
            if (candidates.isEmpty) {
                worlds = emptyList()
                loading = false
                return
            }
            mc.levelSource.loadLevelSummaries(candidates).whenComplete { summaries, error ->
                if (error != null) {
                    logger.error("Failed to load level summaries", error)
                    loading = false
                    return@whenComplete
                }
                mc.execute {
                    worlds = summaries
                    loading = false
                }
            }
        }.onFailure {
            logger.error("Failed to list levels", it)
            loading = false
        }
    }

    private fun iconOf(summary: LevelSummary): TextureSetup? = icons.getOrPutNullable(summary.levelId) {
        runCatching {
            val file = summary.icon.toFile()
            if (!file.exists()) return@runCatching null
            val texture = file.readNativeImage().asTexture { "HellhitWorld-${summary.levelId}" }
            TextureSetup.singleTexture(
                texture.textureView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
            )
        }.getOrNull()
    }

    private fun visibleWorlds(): List<Pair<Int, LevelSummary>> {
        val query = search.text.trim()
        return worlds.withIndex()
            .filter { (_, summary) -> query.isEmpty() || summary.levelName.contains(query, true) }
            .map { it.index to it.value }
    }

    private fun play(summary: LevelSummary) {
        runCatching {
            mc.createWorldOpenFlows().openWorld(summary.levelId) {
                mc.setScreen(HellhitSingleplayerScreen())
            }
        }.onFailure { logger.error("Failed to join world ${summary.levelId}", it) }
    }

    private fun edit(summary: LevelSummary) {
        val session = runCatching {
            mc.levelSource.validateAndCreateAccess(summary.levelId)
        }.onFailure { logger.error("Failed to access level ${summary.levelId}", it) }.getOrNull() ?: return

        runCatching {
            EditWorldScreen.create(mc, session) { _ ->
                session.safeClose()
                mc.setScreen(HellhitSingleplayerScreen())
            }
        }.onFailure {
            session.safeClose()
            logger.error("Failed to open world editor for ${summary.levelId}", it)
        }.onSuccess { screen ->
            mc.setScreen(screen)
        }
    }

    private fun delete(summary: LevelSummary) {
        Thread({
            runCatching {
                mc.levelSource.createAccess(summary.levelId).use { session ->
                    session.deleteLevel()
                }
            }.onFailure { logger.error("Failed to delete world ${summary.levelId}", it) }
            mc.execute { reload() }
        }, "hellhit-world-delete").apply { isDaemon = true }.start()
    }

    private fun relativeTime(epochMs: Long): String {
        if (epochMs <= 0) return "never"
        val diff = System.currentTimeMillis() - epochMs
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            days > 365 -> "${days / 365}y ago"
            days > 0 -> "${days}d ago"
            TimeUnit.MILLISECONDS.toHours(diff) > 0 -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            TimeUnit.MILLISECONDS.toMinutes(diff) > 0 -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            else -> "just now"
        }
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    override fun drawContent(ctx: GuiGraphicsExtractor) {
        val a = motion
        val slabW = minOf(vw - 64f, 980f)
        val sx = (vw - slabW) / 2f

        drawTextCentered(
            ctx, WatermarkFont.BRAND, "Singleplayer",
            sx, TOP_Y + 8f, 10f, palette.white.alphaF(a),
        )
        if (worlds.isNotEmpty()) {
            val titleW = textWidth(WatermarkFont.BRAND, "Singleplayer", 10f)
            drawTextCentered(
                ctx, WatermarkFont.TEXT, "${worlds.size} worlds",
                sx + titleW + 8f, TOP_Y + 9.5f, 6f, palette.dim.alphaF(a),
            )
        }
        drawTextInput(ctx, search, sx + slabW - 170f, TOP_Y + 1f, 170f, 14f, alphaF = a)

        val viewTop = TOP_Y + 24f
        val viewBottom = vh - FOOT_H - 8f
        val list = visibleWorlds()
        val contentH = list.size * (ROW_H + ROW_GAP)
        val scroll = updateScroll(viewBottom - viewTop, contentH)

        clipDesign(ctx, sx, viewTop, slabW, viewBottom - viewTop) {
            if (loading) {
                val hint = "Loading worlds..."
                val hw = textWidth(WatermarkFont.TEXT, hint, 6.5f)
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, hint,
                    sx + (slabW - hw) / 2f, viewTop + 24f, 6.5f, palette.dim.alphaF(a),
                )
            } else if (list.isEmpty()) {
                val hint = if (search.text.isEmpty()) "No worlds yet - press Create" else "No matches"
                val hw = textWidth(WatermarkFont.TEXT, hint, 6.5f)
                drawTextCentered(
                    ctx, WatermarkFont.TEXT, hint,
                    sx + (slabW - hw) / 2f, viewTop + 24f, 6.5f, palette.dim.alphaF(a),
                )
            } else {
                val yBase = viewTop - scroll
                for ((displayIndex, entry) in list.withIndex()) {
                    val (index, summary) = entry
                    val idKey = summary.levelId
                    val y = yBase + animFloat("rowy:$idKey", displayIndex * (ROW_H + ROW_GAP))
                    val ra = a * rowAppear("rowa:$idKey", displayIndex)
                    if (ra > 0.005f && y + ROW_H >= viewTop - 4f && y <= viewBottom + 4f) {
                        drawWorldRow(ctx, index, summary, sx, y, slabW, ra, viewTop, viewBottom)
                    }
                }
            }
        }

        // footer
        var bx = sx + slabW
        val by = vh - FOOT_H + 2f
        val buttons = listOf(
            Triple("Play", true) { worlds.getOrNull(selected)?.let(::play); Unit },
            Triple("Edit", false) { worlds.getOrNull(selected)?.let(::edit); Unit },
            Triple("Delete", false) { if (selected >= 0) deleteConfirm = selected },
            Triple("Create", false) {
                CreateWorldScreen.openFresh(mc) { mc.setScreen(HellhitSingleplayerScreen()) }
            },
            Triple("Refresh", false) { reload() },
        )
        for ((label, primary, action) in buttons) {
            val bw = textWidth(WatermarkFont.TEXT, label, 6.5f) + 18f
            bx -= bw
            pillButton(
                ctx, "sp:$label", label, bx, by, bw, 16f,
                alphaF = a, primary = primary, px = 6.5f, onClick = action,
            )
            bx -= 5f
        }

        drawDeleteModal(ctx, a)
    }

    @Suppress("LongParameterList", "CognitiveComplexMethod", "LongMethod")
    private fun drawWorldRow(
        ctx: GuiGraphicsExtractor, index: Int, summary: LevelSummary,
        x: Float, y: Float, w: Float, a: Float,
        viewTop: Float, viewBottom: Float,
    ) {
        val hovered = mouseXv in x..(x + w) && mouseYv in y..(y + ROW_H) &&
            mouseYv in viewTop..viewBottom
        val ht = hover("sprow:$index", hovered)
        val isSelected = selected == index

        val fill = watchColor(
            "sprowf:$index",
            HellhitPalette.lerpColor(palette.row, palette.rowHover, ht),
        )
        ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, fill.alphaF(0.94f * a), null)
        if (isSelected) {
            ctx.drawRoundedRect(x, y, x + w, y + ROW_H, HellhitPalette.R_ROW, null, palette.accent.alphaF(0.8f * a), 1f)
        }

        val icon = iconOf(summary)
        ctx.drawRoundedRect(x + 4f, y + 4f, x + 26f, y + 26f, 3f, palette.well.alphaF(a), null)
        if (icon != null) {
            ctx.drawTexQuad(icon, x + 4.5f, y + 4.5f, x + 25.5f, y + 25.5f)
        }

        val textX = x + 31f
        val infoW = w - 31f - 50f
        drawText(
            ctx, WatermarkFont.BRAND,
            clipText(WatermarkFont.BRAND, summary.levelName, 7f, infoW), textX, y + 4.5f, 7f,
            palette.white.alphaF(a),
        )
        val meta = buildString {
            append(relativeTime(summary.lastPlayed))
            append("  ")
            append(summary.settings.gameType().serializedName)
            append("  ")
            append(runCatching { summary.levelVersion().minecraftVersionName() }.getOrDefault(""))
        }
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, meta, 5.6f, infoW), textX, y + 16f, 5.6f, palette.desc.alphaF(a),
        )

        // tags (crossfade into the hover controls)
        val infoA = a * (1f - ht)
        if (infoA > 0.01f) {
            if (summary.settings.difficultySettings.hardcore()) {
                val tag = "hardcore"
                val tw = textWidth(WatermarkFont.TEXT, tag, 5.2f)
                drawText(ctx, WatermarkFont.TEXT, tag, x + w - tw - 8f, y + 5f, 5.2f, palette.rose.alphaF(infoA))
            } else if (summary.isLocked) {
                val tag = "locked"
                val tw = textWidth(WatermarkFont.TEXT, tag, 5.2f)
                drawText(ctx, WatermarkFont.TEXT, tag, x + w - tw - 8f, y + 5f, 5.2f, palette.dim.alphaF(infoA))
            }
        }

        // the row hit goes in FIRST: hits are tested newest-first, so the buttons
        // registered after it win the click inside their own bounds
        addHit(x, y, x + w, y + ROW_H) { button ->
            if (button == 0) {
                val now = System.currentTimeMillis()
                if (lastClickIndex == index && now - lastClickAt < 350L) {
                    play(summary)
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

        // hover controls: edit / delete
        if (ht > 0.05f) {
            val ca = a * ht
            val cy = y + (ROW_H - 12f) / 2f
            var cxx = x + w - 18f
            iconButton(ctx, "spctl:x:$index", RowIcon.DELETE, cxx, cy, alphaF = ca, danger = true) {
                deleteConfirm = index
            }
            cxx -= 15f
            iconButton(ctx, "spctl:e:$index", RowIcon.EDIT, cxx, cy, alphaF = ca) {
                edit(summary)
            }
        }
    }

    @Suppress("LongMethod")
    private fun drawDeleteModal(ctx: GuiGraphicsExtractor, a: Float) {
        val open = deleteConfirm >= 0
        if (open) deleteDrawn = deleteConfirm
        val ma = animUnit("modal", if (open) 1f else 0f)
        if (!open && ma <= 0.01f) return
        val summary = worlds.getOrNull(if (open) deleteConfirm else deleteDrawn) ?: run {
            deleteConfirm = -1
            return
        }
        val alpha = a * ma
        // a fading-out modal stays visible but inert
        hitsEnabled = open
        drawScrim(ctx, alpha)
        addHit(0f, 0f, vw, vh) { true }

        val mw = 240f
        val mh = 62f
        val mx = (vw - mw) / 2f
        val my = (vh - mh) / 2f
        ctx.drawRoundedRect(
            mx, my, mx + mw, my + mh, HellhitPalette.R_PANEL,
            palette.panel.alphaF(alpha), palette.panelBorder.alphaF(alpha), 1f,
        )
        drawTextCentered(
            ctx, WatermarkFont.BRAND, "Delete world?",
            mx + 10f, my + 11f, 7.5f, palette.white.alphaF(alpha),
        )
        drawText(
            ctx, WatermarkFont.TEXT,
            clipText(WatermarkFont.TEXT, "${summary.levelName} - cannot be undone", 6.2f, mw - 20f),
            mx + 10f, my + 22f, 6.2f, palette.desc.alphaF(alpha),
        )

        val cw = textWidth(WatermarkFont.TEXT, "Delete", 6.5f) + 20f
        pillButton(
            ctx, "spdel:ok", "Delete",
            mx + mw - cw - 8f, my + mh - 21f, cw, 15f, alphaF = alpha, primary = true, px = 6.5f,
        ) {
            delete(summary)
            deleteConfirm = -1
        }
        val cancelW = textWidth(WatermarkFont.TEXT, "Cancel", 6.5f) + 20f
        pillButton(
            ctx, "spdel:cancel", "Cancel",
            mx + mw - cw - 13f - cancelW, my + mh - 21f, cancelW, 15f, alphaF = alpha, px = 6.5f,
        ) { deleteConfirm = -1 }
        hitsEnabled = true
    }
}
