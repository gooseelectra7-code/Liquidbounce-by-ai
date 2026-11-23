/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful port of the SystemDLC `client/data/AnimatedFloat.java`.
 *
 * Despite the name, the source class holds no interpolation of its own: it is a small
 * change-detecting holder of a current float pair (`a`, `b`) against a reference snapshot
 * pair (`refA`, `refB`) captured at construction/reset, plus a boolean flag. [hasChanged]
 * reports whether either component drifted from its snapshot. Widgets use it to decide
 * whether a dependent (real) animation needs restarting. Pure data; no render dependencies.
 *
 * Source-field mapping (verified against AnimatedFloat.java):
 *  - value  = a       (:4)  | value2 = b     (:5)
 *  - value3 = refA    (:6)  | value4 = refB  (:7)   | flag = flag (:8)
 *  - constructor / getAnimatedFloatByFloatFloatBoolean sets a=refA=value5, b=refB=value6.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.gui.anim

/**
 * Change-detecting float-pair holder. See file header.
 *
 * @param a initial current/reference value of component A
 * @param b initial current/reference value of component B
 * @param flag caller-defined boolean state
 */
class AnimatedFloat(a: Float, b: Float, flag: Boolean) {

    /** Current value of component A (source `value`). */
    var a: Float = a

    /** Current value of component B (source `value2`). */
    var b: Float = b

    /** Reference snapshot of A captured at construction/[reset] (source `value3`). */
    var refA: Float = a
        private set

    /** Reference snapshot of B captured at construction/[reset] (source `value4`). */
    var refB: Float = b
        private set

    /** Caller-defined boolean state (source `flag`). */
    var flag: Boolean = flag

    /**
     * Re-seed current and reference pairs and the flag; returns this for chaining.
     * Source `getAnimatedFloatByFloatFloatBoolean` (:38-45).
     */
    fun reset(a: Float, b: Float, flag: Boolean): AnimatedFloat {
        this.a = a
        this.b = b
        this.refA = a
        this.refB = b
        this.flag = flag
        return this
    }

    /** True if either component drifted from its reference snapshot. Source `check` (:51-53). */
    fun hasChanged(): Boolean = a != refA || b != refB

    // --- machine-recovered source-name aliases ---
    fun getValue(): Float = a
    fun setValue(value: Float) { a = value }
    fun getValue2(): Float = b
    fun setValue2(value: Float) { b = value }
    fun getValue3(): Float = refA
    fun getValue4(): Float = refB
    fun isFlag(): Boolean = flag
    fun check(): Boolean = hasChanged()
    fun getAnimatedFloatByFloatFloatBoolean(a: Float, b: Float, flag: Boolean): AnimatedFloat =
        reset(a, b, flag)
}
