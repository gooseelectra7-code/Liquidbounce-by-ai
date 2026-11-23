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
package net.ccbluex.liquidbounce.features.module.modules.`fun`

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.kotlin.random

/**
 * Derp module
 *
 * Makes it look as if you were derping around.
 */
object ModuleDerp : ClientModule("Derp", ModuleCategories.FUN) {

    private val yawMode = choices("Yaw", YawRandom,
        arrayOf(YawStatic, YawOffset, YawRandom, YawJitter, YawSpin))
    private val pitchMode = choices("Pitch", PitchRandom,
        arrayOf(PitchStatic, PitchOffset, PitchRandom))
    private val safePitch by boolean("SafePitch", true)
    private val notDuringSprint by boolean("NotDuringSprint", true)

    /**
     * Stand down while something more important is steering the aim, and resume the moment it lets
     * go. This is what makes derping compatible with a snap-style aura: the rotation belongs to the
     * hit while the hit is being made, and to the derp the rest of the time.
     *
     * Losing on priority is not enough on its own. Derp asks for a rotation EVERY tick, and with a
     * randomized yaw it asks for a different one every tick, so the instant the aura pauses — which
     * a snap timing does as soon as the weapon is ready — the aim is yanked somewhere random. The
     * click is validated against the rotation actually on the wire, which lags a tick behind, so
     * that yank lands exactly where it costs the hit: the aura aimed, went to click, and the target
     * was no longer under the crosshair.
     *
     * Off restores the old behaviour of derping unconditionally.
     */
    private val yieldToActions by boolean("YieldToActions", true)

    // DO NOT USE TREE TO MAKE SURE THAT THE ROTATIONS ARE NOT CHANGED
    private val rotations = RotationsValueGroup(this)

    val repeatable = tickHandler {
        if (notDuringSprint && (mc.options.keySprint.isDown || player.isSprinting)) {
            return@tickHandler
        }

        if (yieldToActions && isSomeoneElseAiming()) {
            return@tickHandler
        }

        val yaw = yawMode.activeMode.yaw
        val pitch = pitchMode.activeMode.pitch.let {
            if (safePitch) {
                it.coerceIn(-90f, 90f)
            } else {
                it
            }
        }

        RotationManager.setRotationTarget(rotations.toRotationTarget(Rotation(yaw, pitch)),
            Priority.NOT_IMPORTANT, this@ModuleDerp)
    }

    /**
     * Whether the rotation currently belongs to someone above us.
     *
     * Compared against our OWN priority rather than a fixed value, so it keeps meaning the right
     * thing if the request priority here is ever changed. Ties do not count as being outranked —
     * only something strictly more important takes the aim away.
     */
    private fun isSomeoneElseAiming(): Boolean {
        val active = RotationManager.activeRotationPriority ?: return false
        return active > Priority.NOT_IMPORTANT.priority
    }

    private object YawStatic : YawMode("Static") {

        val yawValue by float("Yaw", 0f, -180f..180f, "°")

        override val yaw: Float
            get() = yawValue

    }

    private object YawOffset : YawMode("Offset") {

        val yawOffsetValue by float("Offset", 0f, -180f..180f, "°")

        override val yaw: Float
            get() = player.yRot + yawOffsetValue

    }

    private object YawRandom : YawMode("Random") {
        override val yaw: Float
            get() = (-180f..180f).random()

    }

    /**
     * Swings between a base yaw and the base plus [angle]: [yawForwardTicks] ticks on the base, then
     * [yawBackwardTicks] ticks on the far side, and round again.
     *
     * The base is either an absolute world yaw ([Base.STATIC]) or the camera yaw plus an offset
     * ([Base.OFFSET]) - the same two readings as [YawStatic] and [YawOffset], with the jitter on top.
     * The defaults (Offset, 0°, 180°) are exactly the old behaviour of this mode: the camera yaw,
     * then the camera yaw turned round. A swing centred on the base is Offset/Yaw at `-Angle / 2`.
     *
     * Only the PHASE is kept by the tick handler; the yaw itself is derived when it is read, so a
     * change of the camera yaw or of a setting shows on the next request, not on the next phase.
     */
    private object YawJitter : YawMode("Jitter") {

        /**
         * Declared ABOVE the values it hides so it sits at the top of the GUI. Safe: the hook fires
         * only from `Value.set` (`config/types/Value.kt:236`), and `ChoiceListValue`'s `init` only
         * checks the default (`config/types/list/ChoiceListValue.kt:41-43`), so it cannot run before
         * the fields below exist. The `init` block covers the initial state.
         */
        private val baseMode by enumChoice("Base", Base.OFFSET)
            .onChanged { syncBaseVisibility(it) }

        private val staticYawValue = float("Yaw", 0f, -180f..180f, "°")
        private val staticYaw by staticYawValue

        private val offsetValue = float("Offset", 0f, -180f..180f, "°")
        private val offset by offsetValue

        private val angle by float("Angle", 180f, -180f..180f, "°")

        val yawForwardTicks by int("ForwardTicks", 2, 0..100, "ticks")
        val yawBackwardTicks by int("BackwardTicks", 2, 0..100, "ticks")

        /** Whether the jitter is on its far side, i.e. [angle] is added to the base. */
        private var backward = false

        init {
            syncBaseVisibility(baseMode)
        }

        private fun syncBaseVisibility(current: Base) {
            staticYawValue.notAnOption(current != Base.STATIC)
            offsetValue.notAnOption(current != Base.OFFSET)
        }

        override val yaw: Float
            get() {
                val baseYaw = when (baseMode) {
                    Base.STATIC -> staticYaw
                    Base.OFFSET -> player.yRot + offset
                }

                return if (backward) baseYaw + angle else baseYaw
            }

        @Suppress("unused")
        val repeatable = tickHandler {
            repeat(yawForwardTicks) {
                backward = false
                waitTicks(1)
            }

            repeat(yawBackwardTicks) {
                backward = true
                waitTicks(1)
            }
        }

        private enum class Base(override val tag: String) : Tagged {
            STATIC("Static"),
            OFFSET("Offset")
        }

    }

    private object YawSpin : YawMode("Spin") {

        override var yaw = 0.0f

        val yawSpinSpeed by int("Speed", 50, -70..70, "°/tick")

        @Suppress("unused")
        val repeatable = tickHandler {
            yaw += yawSpinSpeed
            waitTicks(1)
        }

    }

    private object PitchStatic : PitchMode("Static") {

        override val pitch: Float
            get() = pitchValue

        val pitchValue by float("Pitch", -90f, -180f..180f, "°")

    }

    private object PitchOffset : PitchMode("Offset") {

        override val pitch: Float
            get() = player.xRot + pitchOffsetValue

        val pitchOffsetValue by float("Offset", 0f, -180f..180f, "°")

    }

    private object PitchRandom : PitchMode("Random") {

        override val parent: ModeValueGroup<*>
            get() = pitchMode

        override val pitch: Float
            get() = if (safePitch) (-90f..90f).random() else (-180f..180f).random()

    }

    abstract class YawMode(name: String) : Mode(name) {
        override val parent: ModeValueGroup<*>
            get() = yawMode
        abstract val yaw: Float
    }

    abstract class PitchMode(name: String) : Mode(name) {
        override val parent: ModeValueGroup<*>
            get() = pitchMode
        abstract val pitch: Float
    }



}
