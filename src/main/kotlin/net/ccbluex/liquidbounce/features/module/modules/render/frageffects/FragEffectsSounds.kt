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
package net.ccbluex.liquidbounce.features.module.modules.render.frageffects

import net.ccbluex.liquidbounce.utils.client.clientIdentifier
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvent

/**
 * The two sounds [ModuleFragEffects] plays.
 *
 * Registration has to happen from `MixinBuiltInRegistries` during `bootStrap`, before the registry
 * freezes — by the time modules are constructed it is far too late, and registering then throws.
 * [HitFXRegistry][net.ccbluex.liquidbounce.features.module.modules.render.hitfx.HitFXRegistry] is
 * hooked at the same spot for the same reason.
 */
object FragEffectsSounds {

    private var strikeSound: SoundEvent? = null
    private var totemSound: SoundEvent? = null

    /** Sound played on a confirmed kill, or `null` if registration never ran. */
    val strike: SoundEvent?
        get() = strikeSound

    /** Sound played when a target we are fighting pops a totem. */
    val totem: SoundEvent?
        get() = totemSound

    @JvmStatic
    fun registerAll() {
        if (strikeSound != null) {
            return
        }

        strikeSound = register("frageffects-strike")
        totemSound = register("frageffects-totem")
    }

    private fun register(id: String): SoundEvent {
        val soundId = clientIdentifier(id)
        return Registry.register(
            BuiltInRegistries.SOUND_EVENT,
            soundId,
            SoundEvent.createVariableRangeEvent(soundId),
        )
    }

}
