/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * The colour/scalar helpers the SystemDLC widgets use, ported from the parts of
 * `client/util/MathUtil.java` that operate on packed ARGB ints (the rest of that 296-line
 * source is exclusively colour math too — nothing unrelated was left behind). Provides the
 * ARGB<->HSL and ARGB<->HSV round trips, channel unpackers, a hue wheel, a hex parser and
 * a couple of scalar colour predicates. All maths is on packed ARGB ints exactly as the
 * source (channels truncated with `(int)`, `Math.round` where the source rounds); [Color4b]
 * convenience wrappers sit at the bottom. No render dependencies.
 *
 * Clean-name <-> source-name map (verified against MathUtil.java, line ranges noted):
 *  - hslToArgbRaw(l,h,s)      = getIntByFloatFloatFloat          (:4-21)
 *  - scaleSaturation(argb,f)  = getIntByFloatInt                 (:23-36)
 *  - argbToHsl(argb)[h,s,l]   = getFloatArrayByInt               (:38-61)
 *  - green/blue/alpha/red     = getFloatByInt/2/3/4              (:63-73,:158-160)
 *  - rgbDistance(a,b)         = getFloatByIntInt                 (:93-98)
 *  - sameRgb(a,b)             = isIntInt                         (:100-102)
 *  - isDark(argb)             = isInt                            (:104-109)
 *  - scaleAlpha(argb,f)       = getIntByFloatInt2                (:111-114)
 *  - hsvToArgbAlpha(v,a,s,h)  = getIntByFloatIntFloatFloat       (:146-149)
 *  - argbToHsv(argb)[h,s,v]   = getFloatArrayByInt2             (:151-156)
 *  - hsvToArgbRaw(s,h,v)      = getIntByFloatFloatFloat2 (private):162-208)
 *  - parseHex(text)           = getIntegerByString              (:210-250)
 *  - hueColor(hue)            = getIntByFloat                    (:252-295)
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SystemDLC colour maths. See file header for the clean/source name mapping.
 */
object ColorMath {

    /** Opaque alpha mask `0xFF000000` (== -0x1000000 as a signed Int). */
    private const val OPAQUE = -0x1000000

    // ------------------------------------------------------------------ HSL

    /**
     * HSL -> opaque ARGB, in the source parameter order `(lightness, hue, saturation)`.
     * Source `getIntByFloatFloatFloat` (:4-21). See [hslToArgb] for the conventional order.
     */
    fun hslToArgbRaw(lightness: Float, hue: Float, saturation: Float): Int {
        val r: Float
        val g: Float
        val b: Float
        if (saturation == 0.0f) {
            r = lightness
            g = lightness
            b = lightness
        } else {
            val q = if (lightness < 0.5f) {
                lightness * (1.0f + saturation)
            } else {
                lightness + saturation - lightness * saturation
            }
            val p = 2.0f * lightness - q
            r = hue2rgb(p, q, hue + 0.33333334f)
            g = hue2rgb(p, q, hue)
            b = hue2rgb(p, q, hue - 0.33333334f)
        }
        return OPAQUE or ((r * 255.0f).roundToInt() shl 16) or
            ((g * 255.0f).roundToInt() shl 8) or (b * 255.0f).roundToInt()
    }

    /** HSL -> opaque ARGB in conventional `(hue, saturation, lightness)` order. */
    fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int =
        hslToArgbRaw(lightness, hue, saturation)

    /** hue helper for [hslToArgbRaw]. Source `getFloatByFloatFloatFloat` (private) (:75-91). */
    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0.0f) t += 1.0f
        if (t > 1.0f) t -= 1.0f
        return when {
            t < 0.16666667f -> p + (q - p) * 6.0f * t
            t < 0.5f -> q
            t < 0.6666667f -> p + (q - p) * (0.6666667f - t) * 6.0f
            else -> p
        }
    }

    /**
     * Scale the saturation of [argb] by [factor] (result clamped 0..1), keeping hue,
     * lightness and alpha. `factor ~= 1` returns [argb] unchanged.
     * Source `getIntByFloatInt` (:23-36).
     */
    fun scaleSaturation(argb: Int, factor: Float): Int {
        if (abs(factor - 1.0f) < 0.001f) {
            return argb
        }
        val hsl = argbToHsl(argb)
        val h = hsl[0]
        val s = (hsl[1] * factor).coerceIn(0.0f, 1.0f)
        val l = hsl[2]
        val rgb = hslToArgbRaw(l, h, s) and 0xFFFFFF
        return (argb and OPAQUE) or rgb
    }

    /** ARGB -> `[hue, saturation, lightness]`, all 0..1. Source `getFloatArrayByInt` (:38-61). */
    fun argbToHsl(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255.0f
        val g = (argb shr 8 and 0xFF) / 255.0f
        val b = (argb and 0xFF) / 255.0f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0f
        if (max == min) {
            return floatArrayOf(0.0f, 0.0f, l)
        }
        val delta = max - min
        val s = if (l > 0.5f) delta / (2.0f - max - min) else delta / (max + min)
        var h = when (max) {
            r -> (g - b) / delta + (if (g < b) 6.0f else 0.0f)
            g -> (b - r) / delta + 2.0f
            else -> (r - g) / delta + 4.0f
        }
        h /= 6.0f
        return floatArrayOf(h, s, l)
    }

    // ------------------------------------------------------------- channels

    /** Red channel as 0..1. Source `getFloatByInt4` (:158-160). */
    fun red(argb: Int): Float = (argb shr 16 and 0xFF) / 255.0f

    /** Green channel as 0..1. Source `getFloatByInt` (:63-65). */
    fun green(argb: Int): Float = (argb shr 8 and 0xFF) / 255.0f

    /** Blue channel as 0..1. Source `getFloatByInt2` (:67-69). */
    fun blue(argb: Int): Float = (argb and 0xFF) / 255.0f

    /** Alpha channel as 0..1. Source `getFloatByInt3` (:71-73). */
    fun alpha(argb: Int): Float = (argb shr 24 and 0xFF) / 255.0f

    // -------------------------------------------------------- predicates/dist

    /**
     * RMS RGB distance between two colours in 0..1 (`sqrt(mean of squared channel diffs)`).
     * Source `getFloatByIntInt` (:93-98).
     */
    fun rgbDistance(a: Int, b: Int): Float {
        val dr = ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) / 255.0f
        val dg = ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) / 255.0f
        val db = ((b and 0xFF) - (a and 0xFF)) / 255.0f
        return sqrt((dr * dr + dg * dg + db * db) / 3.0f)
    }

    /** True if two colours share the same RGB (alpha ignored). Source `isIntInt` (:100-102). */
    fun sameRgb(a: Int, b: Int): Boolean = (a and 0xFFFFFF) == (b and 0xFFFFFF)

    /** True if the colour is near-black (max RGB channel < 40). Source `isInt` (:104-109). */
    fun isDark(argb: Int): Boolean {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return maxOf(r, g, b) < 40
    }

    /**
     * Scale the alpha channel of [argb] by [factor] (clamped 0..1), keeping RGB.
     * Source `getIntByFloatInt2` (:111-114).
     */
    fun scaleAlpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24 and 0xFF) * factor.coerceIn(0.0f, 1.0f)).roundToInt()
        return (argb and 0xFFFFFF) or (a shl 24)
    }

    // ------------------------------------------------------------------ HSV

    /** ARGB -> `[hue, saturation, value]`, all 0..1. Source `getFloatArrayByInt2` (:151-156). */
    fun argbToHsv(argb: Int): FloatArray {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return rgbToHsv(b, g, r)
    }

    /** RGB (ints 0..255, in `(b, g, r)` order) -> `[h,s,v]`. Source `getFloatArrayByIntIntInt`. */
    private fun rgbToHsv(cb: Int, cg: Int, cr: Int): FloatArray {
        val max = maxOf(cr, cg, cb)
        val min = minOf(cr, cg, cb)
        val v = max / 255.0f
        val s = if (max != 0) (max - min).toFloat() / max else 0.0f
        val h: Float
        if (s == 0.0f) {
            h = 0.0f
        } else {
            val d = (max - min).toFloat()
            val fr = (max - cr) / d
            val fg = (max - cg) / d
            val fb = (max - cb) / d
            var hue = when (max) {
                cr -> fb - fg
                cg -> 2.0f + fr - fb
                else -> 4.0f + fg - fr
            }
            hue /= 6.0f
            if (hue < 0.0f) hue += 1.0f
            h = hue
        }
        return floatArrayOf(h, s, v)
    }

    /**
     * HSV -> opaque ARGB, in the source parameter order `(saturation, hue, value)`.
     * Source `getIntByFloatFloatFloat2` (private) (:162-208). See [hsvToArgb] for order.
     */
    fun hsvToArgbRaw(saturation: Float, hue: Float, value: Float): Int {
        var r = 0
        var g = 0
        var b = 0
        if (saturation == 0.0f) {
            val gray = (value * 255.0f + 0.5f).toInt()
            r = gray
            g = gray
            b = gray
        } else {
            val f = (hue - floor(hue)) * 6.0f
            val f1 = f - floor(f)
            val p = (value * (1.0f - saturation) * 255.0f + 0.5f).toInt()
            val q = (value * (1.0f - saturation * f1) * 255.0f + 0.5f).toInt()
            val t = (value * (1.0f - saturation * (1.0f - f1)) * 255.0f + 0.5f).toInt()
            val vi = (value * 255.0f + 0.5f).toInt()
            when (f.toInt()) {
                0 -> { r = vi; g = t; b = p }
                1 -> { r = q; g = vi; b = p }
                2 -> { r = p; g = vi; b = t }
                3 -> { r = p; g = q; b = vi }
                4 -> { r = t; g = p; b = vi }
                5 -> { r = vi; g = p; b = q }
            }
        }
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    /** HSV -> opaque ARGB in conventional `(hue, saturation, value)` order. */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int =
        hsvToArgbRaw(saturation, hue, value)

    /**
     * HSV -> ARGB with an explicit alpha byte, source order `(value, alpha, saturation, hue)`.
     * Source `getIntByFloatIntFloatFloat` (:146-149).
     */
    fun hsvToArgbAlpha(value: Float, alpha: Int, saturation: Float, hue: Float): Int {
        val rgb = hsvToArgbRaw(saturation, hue, value)
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }

    /** HSV -> ARGB in conventional order with an alpha byte 0..255. */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float, alpha: Int): Int =
        hsvToArgbAlpha(value, alpha, saturation, hue)

    // ---------------------------------------------------------------- hue wheel

    /**
     * A translucent hue-wheel colour for [hue] (0..1), fixed ~0.85 saturation / full value,
     * alpha `0x78` (120). Source `getIntByFloat` (:252-295).
     */
    fun hueColor(hue: Float): Int {
        val f = (hue - floor(hue)) * 6.0f
        val l = f.toInt()
        val f1 = f - l
        val f2 = 0.14999998f
        val f3 = 1.0f - 0.85f * f1
        val f4 = 1.0f - 0.85f * (1.0f - f1)
        val r: Int
        val g: Int
        val b: Int
        when (l) {
            0 -> { r = 255; g = (f4 * 255.0f + 0.5f).toInt(); b = (f2 * 255.0f + 0.5f).toInt() }
            1 -> { r = (f3 * 255.0f + 0.5f).toInt(); g = 255; b = (f2 * 255.0f + 0.5f).toInt() }
            2 -> { r = (f2 * 255.0f + 0.5f).toInt(); g = 255; b = (f4 * 255.0f + 0.5f).toInt() }
            3 -> { r = (f2 * 255.0f + 0.5f).toInt(); g = (f3 * 255.0f + 0.5f).toInt(); b = 255 }
            4 -> { r = (f4 * 255.0f + 0.5f).toInt(); g = (f2 * 255.0f + 0.5f).toInt(); b = 255 }
            else -> { r = 255; g = (f2 * 255.0f + 0.5f).toInt(); b = (f3 * 255.0f + 0.5f).toInt() }
        }
        return 2013265920 or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------- hex parse

    /**
     * Parse a hex colour string to ARGB, or `null` if malformed. Accepts optional leading
     * `#`, then `RGB`, `RRGGBB` (forced opaque) or `RRGGBBAA` (alpha LAST, CSS-style).
     * Source `getIntegerByString` (:210-250).
     *
     * NOTE: the 8-digit form here is `RRGGBBAA`, unlike `Color4b.fromHex` which reads
     * 8-digit as `AARRGGBB` (alpha first). Use [parseHexColor] for a [Color4b] result.
     */
    fun parseHex(text: String?): Int? {
        if (text == null) {
            return null
        }
        var s = text.trim()
        if (s.startsWith("#")) {
            s = s.substring(1)
        }
        return try {
            if (s.length == 3) {
                val sb = StringBuilder(6)
                for (i in 0 until 3) {
                    val c = s[i]
                    sb.append(c).append(c)
                }
                s = sb.toString()
            }
            when (s.length) {
                6 -> {
                    val r = s.substring(0, 2).toInt(16)
                    val g = s.substring(2, 4).toInt(16)
                    val b = s.substring(4, 6).toInt(16)
                    OPAQUE or (r shl 16) or (g shl 8) or b
                }
                8 -> {
                    val r = s.substring(0, 2).toInt(16)
                    val g = s.substring(2, 4).toInt(16)
                    val b = s.substring(4, 6).toInt(16)
                    val a = s.substring(6, 8).toInt(16)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    // ------------------------------------------------------------- Color4b glue

    /** [argbToHsl] on a [Color4b]. */
    fun argbToHsl(color: Color4b): FloatArray = argbToHsl(color.argb)

    /** [argbToHsv] on a [Color4b]. */
    fun argbToHsv(color: Color4b): FloatArray = argbToHsv(color.argb)

    /** HSL -> opaque [Color4b] (conventional order). */
    fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color4b =
        Color4b(hslToArgb(hue, saturation, lightness))

    /** HSV -> opaque [Color4b] (conventional order). */
    fun hsvToColor(hue: Float, saturation: Float, value: Float): Color4b =
        Color4b(hsvToArgb(hue, saturation, value))

    /** Translucent hue-wheel [Color4b] (alpha 120). */
    fun hueColor4b(hue: Float): Color4b = Color4b(hueColor(hue))

    /** Scale a [Color4b]'s alpha by [factor] (clamped 0..1). */
    fun scaleAlpha(color: Color4b, factor: Float): Color4b = Color4b(scaleAlpha(color.argb, factor))

    /** Scale a [Color4b]'s saturation by [factor]. */
    fun scaleSaturation(color: Color4b, factor: Float): Color4b =
        Color4b(scaleSaturation(color.argb, factor))

    /** [parseHex] returning a [Color4b] (or null). Reads 8-digit as `RRGGBBAA`. */
    fun parseHexColor(text: String?): Color4b? = parseHex(text)?.let { Color4b(it) }

    // --- machine-recovered source-name aliases ---
    fun getIntByFloatFloatFloat(l: Float, h: Float, s: Float): Int = hslToArgbRaw(l, h, s)
    fun getIntByFloatInt(factor: Float, argb: Int): Int = scaleSaturation(argb, factor)
    fun getFloatArrayByInt(argb: Int): FloatArray = argbToHsl(argb)
    fun getFloatByInt(argb: Int): Float = green(argb)
    fun getFloatByInt2(argb: Int): Float = blue(argb)
    fun getFloatByInt3(argb: Int): Float = alpha(argb)
    fun getFloatByInt4(argb: Int): Float = red(argb)
    fun getFloatByIntInt(a: Int, b: Int): Float = rgbDistance(a, b)
    fun isIntInt(a: Int, b: Int): Boolean = sameRgb(a, b)
    fun isInt(argb: Int): Boolean = isDark(argb)
    fun getIntByFloatInt2(factor: Float, argb: Int): Int = scaleAlpha(argb, factor)
    fun getFloatArrayByInt2(argb: Int): FloatArray = argbToHsv(argb)
    fun getIntByFloatIntFloatFloat(value: Float, alpha: Int, saturation: Float, hue: Float): Int =
        hsvToArgbAlpha(value, alpha, saturation, hue)
    fun getIntegerByString(text: String?): Int? = parseHex(text)
    fun getIntByFloat(hue: Float): Int = hueColor(hue)
}
