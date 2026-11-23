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
package net.ccbluex.liquidbounce.features.module.modules.combat.placement

import net.ccbluex.liquidbounce.config.types.list.Tagged

/**
 * The mode enums of catlean's block placement engine (`su.catlean.eb`, `tm`, `iu`, `t2`, `vv`).
 *
 * Their constants were the one part of the client the obfuscator left alone, so unlike the method
 * bodies around them these are carried over literally rather than reconstructed.
 */

/** How the placement reaches the server. */
enum class PlaceMode(override val tag: String) : Tagged {
    /**
     * Build and send [net.minecraft.network.protocol.game.ServerboundUseItemOnPacket] directly,
     * inside a prediction so the sequence number is right. The client never runs the interaction,
     * which is what makes it silent — and also what makes it the mode that desyncs if the server
     * rejects the placement.
     */
    PACKET("Packet"),

    /** Go through `MultiPlayerGameMode.useItemOn`, exactly as a right-click would. */
    NORMAL("Normal")
}

/**
 * How strictly the placement has to look like something a vanilla client could have produced.
 *
 * The difference is entirely in which faces and which points on them are allowed:
 * - [VANILLA] takes any face of a solid neighbour and aims at the point nearest the eye.
 * - [STRICT] additionally requires the face to be one the player can actually see — the eye must be
 *   on its outer side and the block beyond it must be air ([CatleanBlockPlacer.visibleFaces]).
 * - [AIR_PLACE] skips face selection entirely and just ray-traces at the target cell, which only
 *   works on servers that do not verify the support block.
 * - [GRIM] is [STRICT] plus a randomised hit point instead of the nearest one. Always aiming at the
 *   closest point on a face is a perfectly stable function of position, and that is the tell; the
 *   randomisation costs nothing because every sampled point is already known to be reachable.
 */
enum class InteractMode(override val tag: String) : Tagged {
    VANILLA("Vanilla"),
    STRICT("Strict"),
    AIR_PLACE("AirPlace"),
    GRIM("GRIM")
}

/** What happens to the hotbar around the placement. */
enum class PlaceSwitchMode(override val tag: String) : Tagged {
    /**
     * Claim the slot, place, then hand the previous slot back — so the server never sees us settled
     * on the placing item. That restore is a second held-item change, and it arrives AFTER the use
     * packet, which is the shape Grim's `PacketOrderE` looks for.
     */
    SILENT("Silent"),

    /** Claim the slot and keep it, as a player who switched would. One held-item change, no restore. */
    NORMAL("Normal"),

    /** Swap the stack into the held slot through the inventory instead of changing slots. */
    ALTERNATIVE("Alternative")
}

/**
 * Which entities standing in the target cell are allowed to be there.
 *
 * This is the setting our own [net.ccbluex.liquidbounce.utils.block.placer.BlockPlacer] never had.
 * It vetoes a position whenever anything occupies it, which is right for obsidian and wrong for
 * cobweb — and it is why AutoWeb placed nothing at all until the collision-shape short circuit went
 * in. catlean makes it a choice instead of a rule.
 *
 * Items and experience orbs never block in any mode: nothing collides with them.
 */
enum class IgnoreMode(override val tag: String) : Tagged {
    /** Place regardless of what is standing there. Correct for blocks without a collision shape. */
    NONE("None"),

    /** Any entity in the cell vetoes the placement. */
    ALL("All"),

    /** Everything vetoes except end crystals — the mode base-placing under a crystal needs. */
    ONLY_CRYSTALS("OnlyCrystals")
}

/** How the crystal itself is reached in the hotbar. */
enum class SwitchMode(override val tag: String) : Tagged {
    NONE("None"),
    NORMAL("Normal"),
    SILENT("Silent"),
    INVENTORY("Inventory")
}

/** Pacing of a multi-position placement within one tick. */
enum class PlaceTiming(override val tag: String) : Tagged {
    /** One position per tick, as a player clicking would manage. */
    VANILLA("Vanilla"),

    /** Fill every position the per-action budget allows, all in this tick. */
    INSTANT("Instant")
}

/** What a place module draws over the positions it is working on. */
enum class PlaceRender(override val tag: String) : Tagged {
    SHADER("Shader"),
    FILL("Fill"),
    LINE("Line"),
    BOTH("Both"),
    NONE("None")
}
