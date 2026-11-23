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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager

/**
 * Unhook (panic) module
 *
 * Instant "vanish" panic button, modelled on the SystemDLC client's PanicModule
 * (client/module/client/PanicModule.java): on activation it closes any open
 * screen and disables every running module, so the client goes dormant and the
 * screen looks vanilla in a single keypress. Bind it to a key, or run the
 * `.unhook` command (both call [panic]).
 *
 * Scope / honesty note: LiquidBounce injects with Fabric mixins, which are
 * applied at class-load time and CANNOT be removed at runtime. The SystemDLC
 * source literally un-injects itself — it unloads a native JVMTI agent so the
 * redefined classes revert to their original bytecode (Boot.nativeUnload +
 * ClassRedefiner). This module has no such native layer to unload; it makes
 * every client hook inert by disabling the module that drives it, which is the
 * closest faithful analogue for a mixin client.
 *
 * The disable sweep runs inside [AutoConfig.withLoading] for two reasons that
 * mirror the panic's stealth intent: the on-disk config is NOT overwritten
 * (loadingNow suppresses the store, so your setup returns next launch, exactly
 * like SystemDLC preserving its pre-panic config blob), and per-module
 * enable/disable notifications are suppressed (ClientModule.onToggled only
 * notifies when !loadingNow).
 */
object ModuleUnhook : ClientModule("Unhook", ModuleCategories.MISC) {

    /**
     * Closes the current screen and disables every running module (this one
     * included, so it resets to OFF and behaves as a push-button). Must run on
     * the client thread; both callers satisfy this — the tick handler fires
     * from [net.ccbluex.liquidbounce.event.GameTickEvent], and command handlers
     * run on the client thread from the chat send hook.
     */
    fun panic() {
        AutoConfig.withLoading {
            mc.setScreen(null)

            // `filter` materialises a new list, so disabling modules (which
            // mutates ModuleManager's backing set) does not invalidate the
            // iteration. ClickGUI stays running (running == true always,
            // disableActivation == true) so the GUI can still be reopened.
            for (module in ModuleManager.filter { it.running && it !== ModuleUnhook }) {
                module.enabled = false
            }

            // Reset the push-button itself. Silent because we are inside
            // withLoading (no disabled notification).
            enabled = false
        }
    }

    @Suppress("unused")
    private val panicTick = tickHandler {
        panic()
    }
}
