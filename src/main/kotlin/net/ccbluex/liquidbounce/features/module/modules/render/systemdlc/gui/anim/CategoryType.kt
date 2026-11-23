/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful reproduction of the SystemDLC `client/module/CategoryType.java` enum.
 *
 * Each entry carries a lowercase icon-name [key] that the SystemDLC ClickGUI (and the icon
 * map maintained alongside this port, "Agent C") resolves to a vector/texture asset. Entry
 * names, order and key strings are copied verbatim from the source (CategoryType.java:4-75)
 * — do NOT rename or reorder; the icon map keys off these exact values. The per-icon
 * geometry ([width], [height], [strokeWidth], [filled], [paths]) is mutable and populated
 * later by the icon loader via [set], defaulting to 16x16 / stroke 1 / not filled / no paths,
 * exactly as the source. No render dependencies.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

/**
 * SystemDLC icon/category keys. [key] is the asset lookup name; the remaining fields are
 * icon geometry filled in at load time by [set].
 */
enum class CategoryType(val key: String) {
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

    /** Icon width in units (default 16, source `width`). */
    var width: Float = 16.0f
        private set

    /** Icon height in units (default 16, source `height`). */
    var height: Float = 16.0f
        private set

    /** Stroke width for outline icons (default 1, source `strokeWidth`). */
    var strokeWidth: Float = 1.0f
        private set

    /** Whether the icon is filled rather than stroked (source `filled`). */
    var filled: Boolean = false
        private set

    /** SVG-style path data for the icon, empty until loaded (source `paths`). */
    var paths: Array<String> = emptyArray()
        private set

    /**
     * Populate the icon geometry. Positive [width]/[height]/[strokeWidth] override the
     * current value (non-positive is ignored); [filled] is always applied; [paths] is
     * applied only when non-empty. Matches the source guards (CategoryType.java:93-110).
     */
    fun set(width: Float, height: Float, strokeWidth: Float, filled: Boolean, paths: Array<String>?) {
        if (width > 0.0f) {
            this.width = width
        }
        if (height > 0.0f) {
            this.height = height
        }
        if (strokeWidth > 0.0f) {
            this.strokeWidth = strokeWidth
        }
        this.filled = filled
        if (paths != null && paths.isNotEmpty()) {
            this.paths = paths
        }
    }
}
