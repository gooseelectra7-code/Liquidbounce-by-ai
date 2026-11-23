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
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged

/**
 * Settings of the ArrayList element of [ModuleHellhitHud], whose module list reproduces the Rice
 * client's `Interface`.
 *
 * There is deliberately no "style" choice: the geometry (flush square slabs, hairline `|` on the
 * anchored edge, paddings) is fixed in the element itself so no stored config can drift away from
 * the reference. Only color, motion and the module filter are user facing.
 */

/**
 * How the per-row accent color is derived. Mirrors the "ArrayList Color Mode" of the original client,
 * except that the palette is the HUD's own accent (from the ClickGUI Themes panel) instead of a
 * separate theme, so [STATIC], [FADE] and [BREATHE] all stay inside the Hellhit design language.
 */
enum class ArrayListColorMode(override val tag: String) : Tagged {

    /** The first gradient stop, flat on every row. */
    STATIC("Static"),

    /** The original's flowing ramp: a sine over the row's offset in the list, moving with time. */
    FADE("Fade"),

    /** The same two stops ping-ponging, every row in phase. */
    BREATHE("Breathe"),

    /** Pastel rainbow, each row trailing the one above it. Ignores the theme. */
    RAINBOW("Rainbow"),
}

/**
 * How a row enters and leaves.
 *
 * [SLIDE] is the original's motion — the row travels out past the anchored screen edge. [REVEAL] is the
 * Hellhit HUD's own take: the row stays put and a scissor uncovers it from that edge, so glyphs never
 * move off the device pixel grid (see the pixel-snapping note on [ModuleHellhitHud]).
 */
enum class ArrayListMotion(override val tag: String) : Tagged {
    SLIDE("Slide"),
    REVEAL("Reveal"),
}

/** Which modules are eligible for the list. Mirrors the "Modules to Show" setting of the original. */
enum class ArrayListFilter(override val tag: String) : Tagged {
    ALL("All"),
    EXCLUDE_RENDER("ExcludeRender"),
    ONLY_BOUND("OnlyBound"),
}
