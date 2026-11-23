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

package net.ccbluex.liquidbounce.render.gui.systemdlc

import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Key type for the ported SystemDLC vector icon set.
 *
 * Each constant maps to `resources/liquidbounce/icons/system/<iconName>.json` (rasterized into
 * [SystemIconAtlas]). The set and the [iconName] keys reproduce the source client's
 * `client.module.CategoryType` enum **verbatim** — in that enum every constant carries a `key` string
 * that is exactly the icon file name (`LOGO("logo")`, `COMBAT("combat")`, `SETTINGS("settings")`, ...),
 * i.e. the CategoryType -> icon-name mapping is the identity `name.lowercase() == iconName`. The
 * ClickGUI there references icons by CategoryType; here you reference the same icon by [SystemIcon].
 *
 * Lookup by raw name (e.g. a LiquidBounce `ModuleCategory` tag such as "combat"/"movement"/"render")
 * is available via [byName].
 */
enum class SystemIcon(val iconName: String) {
    LOGO("logo"),
    ALL("all"),
    CLIENT("client"),
    COMBAT("combat"),
    MOVEMENT("movement"),
    PANEL("panel"),
    PARTICLES("particles"),
    PLAYER("player"),
    RENDER("render"),
    SEARCH("search"),
    DROPDOWN_ARROWS("dropdown_arrows"),
    CHEVRON_RIGHT("chevron_right"),
    DROPDOWN_SUCCESS("dropdown_success"),
    STAR("star"),
    STAR_FILLED("star_filled"),
    EXPAND_ALL("expand_all"),
    COLLAPSE_ALL("collapse_all"),
    SUN("sun"),
    MOON("moon"),
    LANGUAGE("language"),
    SUCCESS_CHECKBOX("success_checkbox"),
    KEYBOARD("keyboard"),
    CLOSE("close"),
    POTION("potion"),
    HEART("heart"),
    COOLDOWN("cooldown"),
    EDIT("edit"),
    SETTINGS("settings"),
    VISUAL("visual"),
    CLOUDS("clouds"),
    SAVE("save"),
    SHARE("share"),
    TRASH("trash"),
    INFO("info"),
    FRIENDS("friends"),
    WAYPOINT("waypoint"),
    WAYPOINT_ARROW("waypoint_arrow"),
    COPY("copy"),
    TOOLTIP_ARROW("tooltip_arrow"),
    TOOLTIP_ARROW_UP("tooltip_arrow_up"),
    TOOLTIP_ARROW_LEFT("tooltip_arrow_left"),
    TOOLTIP_ARROW_RIGHT("tooltip_arrow_right"),
    ALIGN_LEFT("align_left"),
    ALIGN_CENTER("align_center"),
    ALIGN_RIGHT("align_right"),
    ARMOR_HUD("armor_hud"),
    TARGET_HUD("target_hud"),
    SWAP_HUD("swap_hud"),
    BOLT("bolt"),
    GLOBE("globe"),
    RACK("rack"),
    CUBE("cube"),
    WATERMARK("watermark"),
    PLUS("plus"),
    KEY("key"),
    LOCK_OPEN("lock_open"),
    LOCK_CLOSED("lock_closed"),
    ORIENT_VERTICAL("orient_vertical"),
    ORIENT_HORIZONTAL("orient_horizontal"),
    ARROW_LEFT("arrow_left"),
    ARROW_RIGHT("arrow_right"),
    COORDS("coords"),
    SETTING_DROPDOWN("setting_dropdown"),
    SETTING_COLOR("setting_color"),
    SETTING_SLIDER("setting_slider"),
    SETTING_CHECKBOX("setting_checkbox"),
    SETTING_ALIGNMENT("setting_alignment"),
    BRUSH("brush"),
    RESET("reset"),
    EYEDROPPER("eyedropper"),
    TIMER_RING("timer_ring"),
    USE_TRACKER("use_tracker");

    companion object {
        private val BY_NAME: Map<String, SystemIcon> = entries.associateBy { it.iconName }

        /** Icon whose file name is [name] (e.g. "combat"), or null if there is none. */
        fun byName(name: String): SystemIcon? = BY_NAME[name]
    }
}

/**
 * Draws [icon] tinted [color] into the square box `[x, y, x + size, y + size]`, centered and fit to the
 * icon's native aspect ratio (a non-square icon is letterboxed within the box, never stretched). Pulls the
 * rasterized sprite from [SystemIconAtlas], which is lazily built on the calling render thread; a no-op
 * until the atlas is ready. The atlas is white-on-transparent, so the tint is applied via the GUI textured
 * pipeline's per-vertex color multiply (same path as the native ClickGUI icon renderer).
 *
 * MUST be called on the render thread (it may trigger the lazy GL atlas build).
 */
fun drawIcon(
    ctx: GuiGraphicsExtractor,
    icon: SystemIcon,
    x: Float,
    y: Float,
    size: Float,
    color: Color4b,
) {
    // Rendered at exactly the aspect-fit pixel size for this `size`, so the blit is ~1:1 and crisp.
    val rendered = SystemIconAtlas.get(icon, size) ?: return
    val w = rendered.pxW.toFloat()
    val h = rendered.pxH.toFloat()
    val ox = x + (size - w) / 2f
    val oy = y + (size - h) / 2f

    ctx.drawTexQuad(
        rendered.setup,
        ox,
        oy,
        ox + w,
        oy + h,
        0f,
        0f,
        1f,
        1f,
        color.argb,
    )
}
