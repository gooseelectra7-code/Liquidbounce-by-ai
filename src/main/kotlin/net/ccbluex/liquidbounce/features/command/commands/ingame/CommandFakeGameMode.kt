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
package net.ccbluex.liquidbounce.features.command.commands.ingame

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.command.Command
import net.ccbluex.liquidbounce.features.command.CommandException
import net.ccbluex.liquidbounce.features.command.builder.CommandBuilder
import net.ccbluex.liquidbounce.features.command.builder.ParameterBuilder
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.client.variable
import net.minecraft.world.level.GameType

/**
 * FakeGameMode Command
 *
 * Overwrites the game mode the CLIENT runs in - `MultiPlayerGameMode.localPlayerMode` - with a mode of your
 * choice. The server is never told anything, it keeps whatever mode it has us in, so this only changes what the
 * client-side code believes:
 * - `setLocalMode` runs `GameType.updatePlayerAbilities` on the local abilities, so CREATIVE hands out
 *   `mayfly`/`instabuild`/`invulnerable` and SPECTATOR additionally sets `flying`
 *   (`net/minecraft/world/level/GameType.java:62-77`).
 * - block breaking, `hasMissTime`, `hasExperience`, `canHurtPlayer` and the spectator early-outs in `useItem` /
 *   `useItemOn` / `interact` all read `localPlayerMode`
 *   (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:113-524`).
 *
 * The client has a SECOND source of truth for its own mode: `Player.isCreative()` / `isSpectator()` are built on
 * `Player.gameMode()` (`net/minecraft/world/entity/player/Player.java:1636-1652`), which for a client player
 * reads the tab list entry - `PlayerInfo.getGameMode()`, the mode the server announced
 * (`net/minecraft/client/player/AbstractClientPlayer.java:35-38`). Spoofing only `localPlayerMode` would leave
 * those two disagreeing, so `MixinAbstractClientPlayer` fakes that read for the local player as well, off the
 * same [spoofedMode].
 *
 * Everything the server validates stays on its own mode: flight, instant break and creative inventory actions
 * are refused or flagged server-side. This is a client-side spoof, not a permission bypass.
 *
 * The spoof is sticky: [onLocalModeSet] re-applies it to every mode the server sends afterwards (login, respawn,
 * `/gamemode`), and remembers the real mode so `reset` can restore it. Abilities the server pushes with
 * `ClientboundPlayerAbilitiesPacket` are NOT re-spoofed - the mode stays faked, but such a packet can take
 * `mayfly`/`instabuild` back until the next mode change.
 */
object CommandFakeGameMode : Command.Factory, EventListener, MinecraftShortcuts {

    private const val RESET_KEYWORD = "reset"

    /**
     * The mode the client is forced into, `null` while the spoof is off. Read from Java by
     * `MixinAbstractClientPlayer`, which has to fake the second source of truth described above.
     */
    @JvmStatic
    var spoofedMode: GameType? = null
        private set

    /**
     * The last mode the SERVER put us in - what [RESET_KEYWORD] restores.
     */
    private var serverMode = GameType.DEFAULT_MODE

    /**
     * Set while [applyLocally] is inside `setLocalMode`, so [onLocalModeSet] does not mistake our own call for
     * one coming from the server and overwrite [serverMode] with the faked mode.
     */
    private var applying = false

    override fun createCommand(): Command {
        return CommandBuilder
            .begin("fakegamemode")
            .alias("fgm")
            .requiresIngame()
            .parameter(
                ParameterBuilder
                    .begin<String>("mode")
                    .verifiedBy(ParameterBuilder.STRING_VALIDATOR)
                    .autocompletedFrom { GameType.values().map { it.getSerializedName() } + RESET_KEYWORD }
                    .required()
                    .build()
            )
            .handler {
                val name = args[0] as String

                if (name.equals(RESET_KEYWORD, true)) {
                    val previous = spoofedMode ?: throw CommandException(command.result("notSpoofing"))

                    spoofedMode = null
                    applyLocally(serverMode)
                    chat(
                        regular(
                            command.result(
                                "reset",
                                variable(previous.getSerializedName()),
                                variable(serverMode.getSerializedName())
                            )
                        ),
                        command
                    )
                    return@handler
                }

                val mode = GameType.values().firstOrNull { it.getSerializedName().equals(name, true) }
                    ?: throw CommandException(command.result("unknownMode", variable(name)))

                spoofedMode = mode
                applyLocally(mode)
                chat(
                    regular(
                        command.result(
                            "spoofed",
                            variable(mode.getSerializedName()),
                            variable(serverMode.getSerializedName())
                        )
                    ),
                    command
                )
                chat(regular(command.result("clientOnly")), command)
            }
            .build()
    }

    /**
     * HEAD hook of both `MultiPlayerGameMode.setLocalMode` overloads, see [MixinMultiPlayerGameMode]. Every
     * server-driven mode change passes through them - login
     * (`net/minecraft/client/multiplayer/ClientPacketListener.java:551`), respawn (`:1343`) and the
     * `CHANGE_GAME_MODE` game event (`:1552`) - which makes this the one place where the real mode can be
     * recorded and swapped for the faked one.
     *
     * @return the mode the client should actually run in
     */
    @JvmStatic
    fun onLocalModeSet(mode: GameType): GameType {
        if (applying) {
            return mode
        }

        serverMode = mode
        return spoofedMode ?: mode
    }

    /**
     * Puts [mode] into the client the same way the server would, so `updatePlayerAbilities` runs and the
     * `GameModeChangeEvent` at the RETURN of `setLocalMode` reaches the HUD.
     */
    private fun applyLocally(mode: GameType) {
        applying = true
        try {
            interaction.setLocalMode(mode)
        } finally {
            applying = false
        }
    }

    /**
     * The spoof belongs to one session - a mode faked on one server must not leak into the next one.
     */
    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        spoofedMode = null
        serverMode = GameType.DEFAULT_MODE
    }

}
