/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Shared visual constants for the SystemDLC render ports (TntTimer, ItemColor,
 * ItemNametag, ItemRadius, ItemTracker, and the NameTags second mode). The
 * SystemDLC client draws every plate against ONE `ThemeConfig` palette and a single
 * `DistanceScale` curve; this object reproduces both 1:1 so the ported modules share
 * the exact colors/scaling instead of each re-deriving them.
 *
 * Palette values are copied verbatim from `client/enums/ThemePalette.java`
 * (INSTANCE = light, INSTANCE2 = dark; success #86EFAC is shared). The original
 * cross-fades between the two over 0.35s on a theme switch; this object now reproduces
 * that fade too: [dark] picks the target theme (default dark) and [advance] eases the
 * blend. Callers that never call [advance] behave exactly like before — [dark] snaps
 * instantly while no animation is being driven (see the setter), so the already-committed
 * plate modules, which only set [dark] and read the accessors, see zero visual change.
 * DistanceScale mirrors `client/util/DistanceScale.java`.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc

import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * The SystemDLC 11-slot theme palette + distance scaling, shared by every ported
 * render plate. Colors are ARGB, exact from the source.
 *
 * Theme selection & cross-fade:
 * - [dark] sets the TARGET theme (default `true` = INSTANCE2). Its setter only moves the
 *   TARGET; the visible blend [themeMix] is advanced by [advance].
 * - To keep backward compatibility with callers that never animate, the setter SNAPS
 *   [themeMix] straight to the target as long as [advance] has never been called this
 *   session. The moment a caller starts driving the animation (any [advance] call), the
 *   setter switches to leaving the easing to [advance]. This means a module that only
 *   assigns [dark] and reads the accessors gets the exact same Color4b it always did.
 * - Accessors return [lerpColor] of the light and dark slots at [themeMix]; at the
 *   initial `themeMix == 1.0` (matching `dark = true`) every accessor returns EXACTLY the
 *   dark slot, byte-identical to the pre-animation implementation.
 *
 * Accent override: [accent] (default `null`) overrides only [primary]; leave it `null`
 * for the original behavior. [DEFAULT_ACCENT] is the SystemDLC "Свой акцент" default.
 */
object SystemDlcTheme {

    /** Cross-fade duration in seconds; matches the source theme-switch fade. */
    private const val FADE_SECONDS = 0.35f

    /** Backing target of [dark]. */
    private var darkState: Boolean = true

    /**
     * Visible blend between the palettes: `0` = light (INSTANCE), `1` = dark (INSTANCE2).
     * Initialized to match the initial [dark] (`true` -> `1.0`) so that, with no [advance]
     * call and no theme switch, every accessor returns exactly the dark Color4b it always
     * returned.
     */
    private var themeMix: Float = 1f

    /** Blend value at the start of the current easing leg (source for the eased lerp). */
    private var animFrom: Float = 1f

    /** Linear progress `0..1` of the current easing leg; `1` = settled. */
    private var animProgress: Float = 1f

    /**
     * Whether [advance] has ever driven the animation. Until it has, [dark] snaps instantly
     * (the committed plate modules never call [advance] and must see the old instant switch).
     */
    private var animationDriven: Boolean = false

    /**
     * Which of the two source palettes is the TARGET (INSTANCE2 = dark, the default).
     *
     * Setting this only changes the target. While no animation is being driven
     * ([animationDriven] == false) the blend snaps immediately — identical to the old
     * behavior. Once [advance] has been called at least once, a change instead starts an
     * eased leg from the current [themeMix] that [advance] carries to the target.
     */
    var dark: Boolean
        get() = darkState
        set(value) {
            if (value == darkState) {
                return
            }
            darkState = value
            if (animationDriven) {
                // Ease from wherever the blend currently sits toward the new target.
                animFrom = themeMix
                animProgress = 0f
            } else {
                // No animation driver yet: preserve the pre-animation instant switch.
                themeMix = if (value) 1f else 0f
            }
        }

    /**
     * Optional accent override. When non-null, [primary] returns it verbatim; when null
     * (the default), [primary] behaves exactly as before. Corresponds to the SystemDLC
     * ThemeModule "Свой акцент" toggle.
     */
    @JvmField
    var accent: Color4b? = null

    // --- light palette (ThemePalette.INSTANCE) ---
    private val LIGHT_BACKGROUND = Color4b(0xFFFFFFFF.toInt())
    private val LIGHT_SURFACE = Color4b(0xFFFAFAFA.toInt())
    private val LIGHT_ELEVATED = Color4b(0xFFF5F5F5.toInt())
    private val LIGHT_BORDER = Color4b(0xFFE5E5E5.toInt())
    private val LIGHT_MUTED_FG = Color4b(0xFF737373.toInt())
    private val LIGHT_PRIMARY = Color4b(0xFF171717.toInt())
    private val LIGHT_FOREGROUND = Color4b(0xFF0A0A0A.toInt())
    private val LIGHT_WARNING = Color4b(0xFFFCD34D.toInt())
    private val LIGHT_CAUTION = Color4b(0xFFFDBA74.toInt())
    private val LIGHT_DANGER = Color4b(0xFFFCA5A5.toInt())

    // --- dark palette (ThemePalette.INSTANCE2) ---
    private val DARK_BACKGROUND = Color4b(0xFF0A0A0A.toInt())
    private val DARK_SURFACE = Color4b(0xFF171717.toInt())
    private val DARK_ELEVATED = Color4b(0xFF262626.toInt())
    private val DARK_BORDER = Color4b(0xFF404040.toInt())
    private val DARK_MUTED_FG = Color4b(0xFFA1A1A1.toInt())
    private val DARK_PRIMARY = Color4b(0xFFFAFAFA.toInt())
    private val DARK_FOREGROUND = Color4b(0xFFFAFAFA.toInt())
    private val DARK_WARNING = Color4b(0xFFB45309.toInt())
    private val DARK_CAUTION = Color4b(0xFFC2410C.toInt())
    private val DARK_DANGER = Color4b(0xFFB91C1C.toInt())

    /** success/accent is shared across both themes (#86EFAC). */
    @JvmField
    val SUCCESS = Color4b(0xFF86EFAC.toInt())

    /** default shadow tint (Theme.value322 = 0x1A000000). */
    @JvmField
    val SHADOW = Color4b(0x1A000000)

    /** SystemDLC ThemeModule "Свой акцент" default (#DC3742). [accent] stays null by default. */
    @JvmField
    val DEFAULT_ACCENT = Color4b(0xFFDC3742.toInt())

    /**
     * Advance the theme cross-fade by [dtSeconds] toward the current [dark] target
     * (`1.0` if dark, `0.0` if light) over ~[FADE_SECONDS] using easeInOutCubic.
     *
     * The FIRST call marks the animation as driven, after which [dark] eases instead of
     * snapping. Callers that never invoke this method leave [themeMix] untouched, so the
     * accessors keep returning the current palette exactly.
     */
    fun advance(dtSeconds: Float) {
        animationDriven = true

        val target = if (darkState) 1f else 0f
        if (animProgress >= 1f || themeMix == target) {
            themeMix = target
            animProgress = 1f
            return
        }

        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceAtLeast(0f) else 0f
        animProgress = (animProgress + dt / FADE_SECONDS).coerceIn(0f, 1f)
        themeMix = animFrom + (target - animFrom) * easeInOutCubic(animProgress)
        if (animProgress >= 1f) {
            themeMix = target
        }
    }

    /**
     * Per-channel ARGB linear interpolation. Returns [a] exactly at `t <= 0` and [b]
     * exactly at `t >= 1`, so the accessors are byte-identical to the direct palette
     * pick at the un-animated endpoints.
     */
    fun lerpColor(a: Color4b, b: Color4b, t: Float): Color4b {
        if (t <= 0f) {
            return a
        }
        if (t >= 1f) {
            return b
        }
        return Color4b(
            lerpChannel(a.r, b.r, t),
            lerpChannel(a.g, b.g, t),
            lerpChannel(a.b, b.b, t),
            lerpChannel(a.a, b.a, t),
        )
    }

    private fun lerpChannel(from: Int, to: Int, t: Float): Int =
        (from + (to - from) * t + 0.5f).toInt().coerceIn(0, 255)

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val f = -2f * x + 2f
            1f - (f * f * f) / 2f
        }
    }

    fun background(): Color4b = lerpColor(LIGHT_BACKGROUND, DARK_BACKGROUND, themeMix)
    fun surface(): Color4b = lerpColor(LIGHT_SURFACE, DARK_SURFACE, themeMix)
    fun elevated(): Color4b = lerpColor(LIGHT_ELEVATED, DARK_ELEVATED, themeMix)
    fun border(): Color4b = lerpColor(LIGHT_BORDER, DARK_BORDER, themeMix)
    fun mutedFg(): Color4b = lerpColor(LIGHT_MUTED_FG, DARK_MUTED_FG, themeMix)
    fun primary(): Color4b = accent ?: lerpColor(LIGHT_PRIMARY, DARK_PRIMARY, themeMix)
    fun foreground(): Color4b = lerpColor(LIGHT_FOREGROUND, DARK_FOREGROUND, themeMix)
    fun success(): Color4b = SUCCESS
    fun warning(): Color4b = lerpColor(LIGHT_WARNING, DARK_WARNING, themeMix)
    fun caution(): Color4b = lerpColor(LIGHT_CAUTION, DARK_CAUTION, themeMix)
    fun danger(): Color4b = lerpColor(LIGHT_DANGER, DARK_DANGER, themeMix)

    /**
     * Scale the alpha channel of [color] by [factor] (clamped 0..1), keeping RGB.
     * Mirrors `Theme.getIntByIntFloat` / `MathUtil.getIntByFloatInt2`.
     */
    fun withAlphaFactor(color: Color4b, factor: Float): Color4b {
        val f = factor.coerceIn(0f, 1f)
        return Color4b(color.r, color.g, color.b, (color.a * f).toInt().coerceIn(0, 255))
    }

    /**
     * World-distance -> screen-size scale, verbatim from `DistanceScale.java`:
     * `clamp(distance * 0.0025, 0.02, 0.1) * base`.
     */
    fun distanceScale(distance: Double, base: Float): Float {
        val f = (distance * 0.0025).toFloat().coerceIn(0.02f, 0.1f)
        return f * base
    }
}
