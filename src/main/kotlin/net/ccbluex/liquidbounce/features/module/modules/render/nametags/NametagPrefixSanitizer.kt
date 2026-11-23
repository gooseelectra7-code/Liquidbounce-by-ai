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
package net.ccbluex.liquidbounce.features.module.modules.render.nametags

/**
 * Server-prefix sanitizer — a 1:1 port of SystemDLC's `client.data.TextSanitizer`
 * (`.../system-source/src/main/java/client/data/TextSanitizer.java`), used only by the
 * SYSTEM nametag mode ([NametagSystemStyle]).
 *
 * [extractPrefix] takes a player's team/display name and their plain username and returns
 * the cleaned text that appears BEFORE the username (the server prefix), so a display name
 * like `§c[Owner] Notch` yields `Owner`. It first strips color/format codes, homoglyph-
 * normalizes fancy Unicode letters/digits back to ASCII (the big [HOMOGLYPHS] table, so a
 * prefix written in mathematical-bold or full-width letters still matches the username), then
 * cleans the located prefix span to letters/digits/space/`_`/`-`.
 *
 * The homoglyph table and all string logic are copied verbatim from the source
 * (`getStringByString2` / `getStringByString` / `getStringByStringString` / `getIntByStringString`).
 */
object NametagPrefixSanitizer {

    private val HOMOGLYPHS: Map<Int, Char> = buildMap(600) {
        // explicit lowercase-mapping code points (TextSanitizer.java:11-36)
        put(7424, 'a'); put(665, 'b'); put(7428, 'c'); put(7429, 'd'); put(7431, 'e')
        put(42800, 'f'); put(610, 'g'); put(668, 'h'); put(618, 'i'); put(7434, 'j')
        put(7435, 'k'); put(671, 'l'); put(7437, 'm'); put(628, 'n'); put(7439, 'o')
        put(7448, 'p'); put(491, 'q'); put(640, 'r'); put(42801, 's'); put(7451, 't')
        put(7452, 'u'); put(7456, 'v'); put(7457, 'w'); put(655, 'y'); put(7458, 'z')

        // mathematical alphanumeric symbol blocks: {upper start, lower start} (TextSanitizer.java:37-59)
        val letterRanges = arrayOf(
            intArrayOf(119808, 119834), intArrayOf(119860, 119886), intArrayOf(119912, 119938),
            intArrayOf(119964, 119990), intArrayOf(120016, 120042), intArrayOf(120068, 120094),
            intArrayOf(120120, 120146), intArrayOf(120172, 120198), intArrayOf(120224, 120250),
            intArrayOf(120276, 120302), intArrayOf(120328, 120354), intArrayOf(120380, 120406),
            intArrayOf(120432, 120458),
        )
        for (range in letterRanges) {
            for (i in 0 until 26) {
                put(range[0] + i, ('a' + i))
                put(range[1] + i, ('a' + i))
            }
        }

        // mathematical digit block starts (TextSanitizer.java:61-67)
        val digitStarts = intArrayOf(120782, 120792, 120802, 120812, 120822)
        for (start in digitStarts) {
            for (j in 0 until 10) {
                put(start + j, ('0' + j))
            }
        }

        // letterlike symbols (TextSanitizer.java:69-92)
        put(8462, 'h'); put(8492, 'b'); put(8496, 'e'); put(8497, 'f'); put(8459, 'h')
        put(8464, 'i'); put(8466, 'l'); put(8499, 'm'); put(8475, 'r'); put(8495, 'e')
        put(8458, 'g'); put(8500, 'o'); put(8493, 'c'); put(8460, 'h'); put(8465, 'i')
        put(8476, 'r'); put(8488, 'z'); put(8450, 'c'); put(8461, 'h'); put(8469, 'n')
        put(8473, 'p'); put(8474, 'q'); put(8477, 'r'); put(8484, 'z')

        // full-width Latin letters + digits (TextSanitizer.java:94-101)
        for (k in 0 until 26) {
            put(65313 + k, ('a' + k))
            put(65345 + k, ('a' + k))
        }
        for (l in 0 until 10) {
            put(65296 + l, ('0' + l))
        }

        // circled / parenthesized Latin small letters (TextSanitizer.java:103-106)
        for (m in 0 until 26) {
            put(9398 + m, ('a' + m))
            put(9424 + m, ('a' + m))
        }

        // regional-indicator / squared Latin (TextSanitizer.java:108-111)
        for (n in 0 until 26) {
            put(127280 + n, ('a' + n))
            put(127344 + n, ('a' + n))
        }
    }

    private val COLOR_CODE = Regex("§.")
    private val FORMAT_CONTROL = Regex("[\\p{Cf}\\p{Cc}]")
    private val MULTI_SPACE = Regex(" +")

    /**
     * `TextSanitizer.getStringByStringString` (TextSanitizer.java:131-136): returns the cleaned
     * prefix span located before [plainName] inside [displayName], or "" if none.
     */
    fun extractPrefix(displayName: String, plainName: String): String {
        val stripped = COLOR_CODE.replace(displayName, "").let { FORMAT_CONTROL.replace(it, "") }
        val normalized = normalizeHomoglyphs(stripped)
        val index = indexOfName(plainName, normalized)
        return if (index <= 0) "" else cleanSegment(normalized.substring(0, index))
    }

    /** `getIntByStringString` (TextSanitizer.java:138-152): first case-insensitive index of [name] in [text]. */
    private fun indexOfName(name: String, text: String): Int {
        if (name.isEmpty()) {
            return 0
        }
        val limit = text.length - name.length
        for (l in 0..limit) {
            if (text.regionMatches(l, name, 0, name.length, ignoreCase = true)) {
                return l
            }
        }
        return -1
    }

    /** `getStringByString2` (TextSanitizer.java:154-172): map each code point through [HOMOGLYPHS]. */
    private fun normalizeHomoglyphs(text: String): String {
        if (text.isEmpty()) {
            return text
        }
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val mapped = HOMOGLYPHS[cp]
            if (mapped != null) {
                builder.append(mapped)
            } else {
                builder.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return builder.toString()
    }

    /**
     * `getStringByString` (TextSanitizer.java:114-129): keep letters/digits/space/`_`/`-`,
     * replace runs of anything else with a single space, then trim + collapse spaces.
     */
    private fun cleanSegment(text: String): String {
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || cp == 32 || cp == 95 || cp == 45) {
                builder.appendCodePoint(cp)
            } else if (builder.isNotEmpty() && builder[builder.length - 1] != ' ') {
                builder.append(' ')
            }
            i += Character.charCount(cp)
        }
        return MULTI_SPACE.replace(builder.toString().trim(), " ")
    }
}
