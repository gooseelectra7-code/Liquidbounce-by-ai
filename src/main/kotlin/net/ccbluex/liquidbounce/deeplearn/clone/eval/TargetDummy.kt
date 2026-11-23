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
package net.ccbluex.liquidbounce.deeplearn.clone.eval

import com.mojang.authlib.GameProfile
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.entity.box
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.Entity
import java.util.UUID

/**
 * The opponent a replayed smoother is fighting: an entity moved, every tick, to where the recording
 * says the target stood.
 *
 * Nothing here decides what to aim at — that angle is replayed and handed to the smoother directly.
 * What this carries is everything else a smoother asks the *world* about the thing it is tracking,
 * which in practice means how far away it is and how big it is. Several of them scale themselves by
 * range, one across a tenfold span, and a target that is always at zero distance pins every one of
 * them to whichever end of that curve zero lands on.
 *
 * A [RemotePlayer], because the recording was made against players: its box is a player's, 0.6 by
 * 1.8 blocks, which is what the recorded per-tick hitbox size says the real targets were.
 *
 * It is never added to the level. This runs on a background thread inside a live client, in a world
 * the user is standing in; an entity in the level would tick, render, and be found by everything
 * that scans for targets — including the modules being measured. Out of the level it is an inert
 * box: [Entity.setPos] writes the entity's own position and bounding box and, with no level callback
 * attached to it, nothing else.
 */
class TargetDummy private constructor(
    private val viewer: Entity,

    /** The stand-in itself, for handing to a [net.ccbluex.liquidbounce.utils.aiming.RotationTarget]. */
    val entity: Entity
) {

    /**
     * Puts the box centre [distance] blocks from [viewer]'s eyes along [rotation] — the direction
     * and the range the recording measured to the target's own box centre, so a smoother asking the
     * world how far its target is gets the recorded answer back.
     *
     * An entity's position is its feet and the box grows upwards from there, so the position has to
     * sit below the point the box is wanted at. The offset is read back off the box the entity built
     * for itself rather than assumed to be half of a height, which keeps this right for a box that
     * is inflated, or not centred on the position at all.
     */
    fun placeAt(rotation: Rotation, distance: Float) {
        val centre = viewer.eyePosition.add(rotation.directionVector.scale(distance.toDouble()))

        entity.setPos(centre)
        val offset = entity.box.center.subtract(centre)
        entity.setPos(centre.subtract(offset))
    }

    companion object {

        /**
         * Binds a stand-in to the level [viewer] is in. Those are also the eyes every placement is
         * measured from, and they are the live ones a smoother measures its own distances from, so
         * the two agree to within whatever the user moves while a run is in flight.
         */
        fun anchoredTo(viewer: Entity): TargetDummy {
            val level = viewer.level() as? ClientLevel
                ?: error("A smoother can only be replayed from inside a world.")

            return TargetDummy(viewer, RemotePlayer(level, GameProfile(PROFILE_ID, PROFILE_NAME)))
        }

        /** A profile insists on both; nothing ever renders this one or looks it up. */
        private const val PROFILE_NAME = "CloneEval"
        private val PROFILE_ID: UUID = UUID.nameUUIDFromBytes("liquidbounce:clone-eval".toByteArray())
    }
}
