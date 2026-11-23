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

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.client.MessageMetadata
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.combat.intel.TeamDetector

/**
 * PvP helper — surfaces the intel gathered by
 * [net.ccbluex.liquidbounce.utils.combat.intel.PvpIntelTracker]: alerts about teaming players and
 * totem pops. Most of the feature is the `PvpIntel` HUD panel in ModuleHud2; this module owns the
 * shared settings (read by the tracker/detector even while the module is off) and the chat/toast
 * alerts (fired only while it is running).
 */
object ModulePvpHelper : ClientModule("PvpHelper", ModuleCategories.MISC) {

    init {
        doNotIncludeAlways()
    }

    private object TeamDetection : ToggleableValueGroup(this, "TeamDetection", true) {
        /** Distinct victims the pair must BOTH have worked over before it counts as teaming. */
        val minVictims by int("MinSharedVictims", 2, 1..5)

        /** Weight each side must land on a victim for that victim to count as shared. */
        val minPerVictim by float("MinHitsPerVictim", 2f, 1f..10f)

        /** Total weight across all shared victims. */
        val minShared by int("MinSharedHits", 6, 2..30)

        val window by int("WindowSeconds", 240, 30..600, "s")
        val proximity by float("ProximityRadius", 24f, 8f..64f, "m")
        val clear by int("ClearSeconds", 60, 5..300, "s")
        val ignoreTeams by boolean("IgnoreScoreboardTeams", true)
        val heuristic by boolean("HeuristicEdges", true)
        val alert by enumChoice("AlertMode", AlertMode.BOTH)
    }

    private object PopAlerts : ToggleableValueGroup(this, "PopAlerts", true)

    init {
        tree(TeamDetection)
        tree(PopAlerts)
    }

    // Settings exposed to the tracker/detector — readable regardless of module enabled state.
    val minSharedHits: Float get() = TeamDetection.minShared.toFloat()
    val minSharedVictims: Int get() = TeamDetection.minVictims
    val minHitsPerVictim: Float get() = TeamDetection.minPerVictim
    val windowSeconds: Int get() = TeamDetection.window
    val proximityRadius: Float get() = TeamDetection.proximity
    val clearSeconds: Int get() = TeamDetection.clear
    val ignoreScoreboardTeams: Boolean get() = TeamDetection.ignoreTeams
    val heuristicEdges: Boolean get() = TeamDetection.heuristic

    @Suppress("unused")
    private val alertHandler = tickHandler {
        if (!TeamDetection.enabled) {
            // Still drain so a later enable does not replay a backlog of stale flags.
            TeamDetector.drainNewFlags()
        } else {
            for (pair in TeamDetector.drainNewFlags()) {
                announceTeam(pair)
            }
        }
    }

    private fun announceTeam(pair: TeamDetector.TeamPair) {
        // The victim count is the evidence, so state it — a flag without it invites second-guessing.
        val who = if (pair.victimWasMe) "teaming on YOU" else "teaming"
        val text = "${pair.aName} & ${pair.bName} are $who (${pair.sharedVictims} targets)"

        when (TeamDetection.alert) {
            AlertMode.CHAT, AlertMode.BOTH ->
                chat(markAsError(text), MessageMetadata(id = "MPvpHelper#team${teamKey(pair)}"))
            else -> {}
        }
        when (TeamDetection.alert) {
            AlertMode.NOTIFICATION, AlertMode.BOTH ->
                notification("PvP Helper", text, NotificationEvent.Severity.ERROR)
            else -> {}
        }
    }

    private fun teamKey(pair: TeamDetector.TeamPair): String {
        val a = pair.a.toString()
        val b = pair.b.toString()
        return if (a <= b) "$a$b" else "$b$a"
    }

    /** Called by the tracker when a target pops a totem, if [PopAlerts] is on and we are running. */
    fun onTotemPop(name: String, count: Int) {
        if (!running || !PopAlerts.enabled) return
        chat(markAsError("$name popped a totem (x$count)"), MessageMetadata(id = "MPvpHelper#pop$name"))
    }

    @Suppress("unused")
    private enum class AlertMode(override val tag: String) : Tagged {
        CHAT("Chat"),
        NOTIFICATION("Notification"),
        BOTH("Both"),
    }
}
