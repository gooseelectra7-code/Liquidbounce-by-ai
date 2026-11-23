/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/data/AnimatedColor.java`.
 *
 * Bundles two [AnimatedInt] colour tweens (each 0.22s, easeOutCubic) with one scalar
 * [Tween] (0.15s, easeOutExpo), all driven together by [update]. The first [update] snaps
 * every channel; later updates animate. Each [update] also pumps the three tweens once so
 * they advance on wall-clock time. Widgets use it for a two-stop gradient whose stops and
 * blend position all animate. No render dependencies.
 *
 * IMPORTANT source quirk (preserved only in the recovered alias): the source
 * `onIntIntFloat(count, count2, value)` assigns `count2` to colour A and `count` to colour B
 * — i.e. it SWAPS relative to the constructor `AnimatedColor(count, count2, value)` (A=count,
 * B=count2). The clean [update]`(colourA, colourB, scalar)` API is self-consistent (colourA
 * always drives colour A); [onIntIntFloat] reproduces the swap exactly.
 *
 * Source-field mapping (verified against AnimatedColor.java):
 *  - animatedInt  = colourA (:6)  | animatedInt2 = colourB (:7) | tween = scalar (:8)
 *  - getInt2 = colourA peek (:25) | getInt = colourB peek (:17) | getFloat = scalar peek (:21)
 *  - onIntIntFloat first call snaps (setInt2/setFloat), else animates (setInt/setFloat2) :29-44
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * Two animated colours plus an animated scalar. See file header.
 *
 * @param colourA initial colour A (ARGB)
 * @param colourB initial colour B (ARGB)
 * @param scalar initial scalar value
 */
class AnimatedColor(colourA: Int, colourB: Int, scalar: Float) {

    private val colourAnim = AnimatedInt(colourA, COLOUR_DURATION)
    private val colourAnim2 = AnimatedInt(colourB, COLOUR_DURATION)
    private val scalarTween: Tween = EasingPresets.expoTween(scalar, SCALAR_DURATION)
    private var started = false

    constructor(colourA: Color4b, colourB: Color4b, scalar: Float) :
        this(colourA.argb, colourB.argb, scalar)

    /** Current colour A (source `getInt2`, peek). */
    fun colourA(): Int = colourAnim.peek()

    /** Current colour B (source `getInt`, peek). */
    fun colourB(): Int = colourAnim2.peek()

    /** Current scalar (source `getFloat`, peek). */
    fun scalar(): Float = scalarTween.current

    /** [colourA] as a [Color4b]. */
    fun colourAColor(): Color4b = Color4b(colourA())

    /** [colourB] as a [Color4b]. */
    fun colourBColor(): Color4b = Color4b(colourB())

    /**
     * Drive both colours and the scalar. The first call snaps; later calls animate. Pumps
     * the three tweens once so they advance. Clean, non-swapping order (`colourA` drives
     * colour A). See [onIntIntFloat] for the source-exact swapped variant.
     */
    fun update(colourA: Int, colourB: Int, scalar: Float) {
        if (!started) {
            colourAnim.snap(colourA)
            colourAnim2.snap(colourB)
            scalarTween.snap(scalar)
            started = true
        } else {
            colourAnim.set(colourA)
            colourAnim2.set(colourB)
            scalarTween.to(scalar)
        }
        // pump the tweens (advance on wall-clock time), matching the source tail
        colourAnim.get()
        colourAnim2.get()
        scalarTween.get()
    }

    /** [update] from [Color4b]s. */
    fun update(colourA: Color4b, colourB: Color4b, scalar: Float) =
        update(colourA.argb, colourB.argb, scalar)

    // --- machine-recovered source-name aliases (SWAPPED — see file header) ---
    fun getInt2(): Int = colourA()
    fun getInt(): Int = colourB()
    fun getFloat(): Float = scalar()

    /** Source-exact: `count2` -> colour A, `count` -> colour B (swapped vs constructor). */
    fun onIntIntFloat(count: Int, count2: Int, value: Float) = update(count2, count, value)

    private companion object {
        const val COLOUR_DURATION = 0.22f
        const val SCALAR_DURATION = 0.15f
    }
}
