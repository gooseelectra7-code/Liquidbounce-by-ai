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

package net.ccbluex.liquidbounce.utils.navigation.pathing.exec

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElytraGuidanceTest {

    /** Cruising east at height, on profile, with rockets to spare. */
    @Suppress("LongParameterList")
    private fun situation(
        position: Vec3 = Vec3(0.0, 100.0, 0.0),
        velocity: Vec3 = Vec3(1.5, 0.0, 0.0),
        target: Vec3 = Vec3(100.0, 100.0, 0.0),
        cruiseY: Double = 100.0,
        groundY: Double = 64.0,
        blockedAhead: Boolean = false,
        hasFirework: Boolean = true,
        boosted: Boolean = false,
        landing: Boolean = false,
        touchdown: Boolean = false,
    ) = ElytraSituation(
        position = position,
        velocity = velocity,
        target = target,
        cruiseY = cruiseY,
        groundY = groundY,
        blockedAhead = blockedAhead,
        hasFirework = hasFirework,
        boosted = boosted,
        landing = landing,
        touchdown = touchdown,
    )

    @Test
    fun `the nose points at the target`() {
        // Yaw grows clockwise from south, so due east is -90.
        val command = ElytraGuidance.steer(situation(target = Vec3(100.0, 100.0, 0.0)))

        assertEquals(-90f, command.yaw, 0.01f)
    }

    @Test
    fun `below the profile it climbs`() {
        val command = ElytraGuidance.steer(situation(position = Vec3(0.0, 80.0, 0.0)))

        assertTrue(command.pitch < 0f, "should have the nose up, got ${command.pitch}")
        assertTrue(command.pitch >= ElytraGuidance.CLIMB_PITCH, "climbed steeper than the wing allows")
    }

    @Test
    fun `above the profile it dives, which is how spare height becomes speed`() {
        val command = ElytraGuidance.steer(situation(position = Vec3(0.0, 140.0, 0.0)))

        assertTrue(command.pitch > 0f, "should have the nose down, got ${command.pitch}")
        assertTrue(command.pitch <= ElytraGuidance.DIVE_PITCH)
    }

    @Test
    fun `on profile it barely commands anything`() {
        val command = ElytraGuidance.steer(situation())

        assertEquals(0f, command.pitch, 1f)
    }

    @Test
    fun `a wing below flying speed levels out instead of climbing`() {
        // Climbing costs speed, and a climb commanded below flying speed buys a stall rather than
        // altitude. Level off, build the speed back, climb on that.
        val command = ElytraGuidance.steer(
            situation(position = Vec3(0.0, 80.0, 0.0), velocity = Vec3(0.2, 0.0, 0.0), hasFirework = false),
        )

        assertEquals(ElytraGuidance.GLIDE_PITCH, command.pitch)
    }

    @Test
    fun `a wing cast from a standing jump climbs away on its rocket`() {
        // Take-off is exactly the stalled-and-below-profile case the guard fires on, and levelling
        // out there flies the rocket into the ground the jump just left.
        val command = ElytraGuidance.steer(
            situation(position = Vec3(0.0, 65.0, 0.0), velocity = Vec3.ZERO, groundY = 64.0),
        )

        assertTrue(command.useFirework, "nothing else can get a standing wing off the ground")
        assertTrue(command.pitch < 0f, "the rocket has to be pointed somewhere other than down")
    }

    @Test
    fun `a slow wing does climb once a rocket is burning`() {
        val command = ElytraGuidance.steer(
            situation(position = Vec3(0.0, 80.0, 0.0), velocity = Vec3(0.2, 0.0, 0.0), boosted = true),
        )

        assertTrue(command.pitch < 0f, "thrust pays for the climb, got ${command.pitch}")
    }

    @Test
    fun `terrain ahead is climbed over even from a standstill`() {
        // The stall guard must not win here: levelling out to recover speed flies into the rock.
        val command = ElytraGuidance.steer(
            situation(velocity = Vec3(0.2, 0.0, 0.0), blockedAhead = true),
        )

        assertEquals(ElytraGuidance.CLIMB_PITCH, command.pitch)
        assertTrue(command.useFirework, "a wall ahead is worth a rocket")
    }

    @Test
    fun `a climb is paid for with a rocket`() {
        assertTrue(ElytraGuidance.steer(situation(position = Vec3(0.0, 80.0, 0.0))).useFirework)
    }

    @Test
    fun `a slow cruise asks for a rocket even at the right height`() {
        assertTrue(ElytraGuidance.steer(situation(velocity = Vec3(0.7, 0.0, 0.0))).useFirework)
    }

    @Test
    fun `no rocket is asked for while one is already burning`() {
        val command = ElytraGuidance.steer(situation(position = Vec3(0.0, 80.0, 0.0), boosted = true))

        assertFalse(command.useFirework)
    }

    @Test
    fun `no rocket is asked for when none is carried`() {
        val command = ElytraGuidance.steer(situation(position = Vec3(0.0, 80.0, 0.0), hasFirework = false))

        assertFalse(command.useFirework)
    }

    @Test
    fun `the approach points further down than the slope it means to fly`() {
        // A wing does not go where it looks: its velocity chases the look direction by about a tenth
        // of the gap per tick. Pointing at the slope we want therefore descends far more shallowly
        // than that, arrives over the destination still high, and sails past looking for somewhere
        // to lose it. So the command leads: past the wanted angle by the error outstanding.
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(0.0, 100.0, 0.0),
                velocity = Vec3(1.5, 0.0, 0.0),
                target = Vec3(100.0, 64.0, 0.0),
                landing = true,
            ),
        )

        // 36 blocks to lose over 100 travelled is a twenty-degree slope, and the aircraft is
        // currently level, so the command is roughly twice that.
        assertEquals(40f, command.pitch, 4f)
        assertFalse(command.useFirework, "burning rockets on the approach only overshoots the landing")
    }

    @Test
    fun `once the descent has caught up, the command settles onto the slope`() {
        // The lead has to bleed away as the flight path arrives, or it is just a permanent dive.
        val descending = Vec3(1.41, -0.51, 0.0) // about twenty degrees below level
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(0.0, 100.0, 0.0),
                velocity = descending,
                target = Vec3(100.0, 64.0, 0.0),
                landing = true,
            ),
        )

        assertEquals(20f, command.pitch, 4f)
    }

    @Test
    fun `still high over the destination, it turns down onto it rather than flying on`() {
        // A wing cannot come down on the spot: height is only lost by travelling. Flying on until
        // it is gone is how an autopilot sails past its target and never comes back.
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(0.0, 90.0, 0.0),
                velocity = Vec3(1.5, 0.0, 0.0),
                target = Vec3(2.0, 64.0, 0.0),
                groundY = 64.0,
                landing = true,
                touchdown = true,
            ),
        )

        assertTrue(command.pitch >= ElytraGuidance.SPIRAL_PITCH, "should be spending height, steeply")
        assertEquals(-90f + ElytraGuidance.SPIRAL_TURN, command.yaw, 0.01f, "should be turning, not running on")
        assertTrue(
            ElytraGuidance.SPIRAL_TURN >= 45f,
            "a small offset from the heading barely turns a wing at all: the velocity chases the " +
                "look direction by a tenth per tick, so ten degrees off is one degree of turn",
        )
    }

    @Test
    fun `a fast wing bleeds its speed off before touching down`() {
        // Vanilla prices a glider's collision at ten times the speed it loses: arriving at cruise
        // costs most of a health bar, and a few dozen ticks of nose-up costs nothing.
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(0.0, 68.0, 0.0),
                velocity = Vec3(1.5, 0.0, 0.0),
                target = Vec3(6.0, 64.0, 0.0),
                groundY = 64.0,
                landing = true,
            ),
        )

        assertEquals(ElytraGuidance.FLARE_PITCH, command.pitch)
    }

    @Test
    fun `a slow wing near the ground simply settles onto it`() {
        // Once the speed is gone there is nothing left to bleed, and holding the nose up from here
        // is how a landing turns back into a flight.
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(0.0, 68.0, 0.0),
                velocity = Vec3(0.3, 0.0, 0.0),
                target = Vec3(6.0, 64.0, 0.0),
                groundY = 64.0,
                landing = true,
            ),
        )

        assertEquals(ElytraGuidance.SINK_PITCH, command.pitch)
        assertTrue(command.pitch > 0f, "nose-down, or it never touches down at all")
    }

    @Test
    fun `having flown past the destination it does not turn back for it`() {
        // The latch is the point: judging "we are landing here" by distance alone unlatches the
        // moment the aircraft overshoots, and a wide orbit is no better than a tight one.
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(40.0, 70.0, 0.0),
                velocity = Vec3(1.2, 0.0, 0.0),
                target = Vec3(0.0, 64.0, 0.0),
                groundY = 64.0,
                landing = true,
                touchdown = true,
            ),
        )

        assertEquals(-90f, command.yaw, 0.01f, "should carry on east, not turn back west")
    }

    @Test
    fun `over the destination it stops steering at it and holds the heading`() {
        // Steering at a point beneath the aircraft is a circle around it, which is exactly how an
        // autopilot orbits its destination for ever instead of landing on it.
        val command = ElytraGuidance.steer(
            situation(
                position = Vec3(0.0, 70.0, 0.0),
                velocity = Vec3(1.2, 0.0, 0.0),
                target = Vec3(1.0, 64.0, 0.0),
                groundY = 64.0,
                landing = true,
            ),
        )

        assertEquals(-90f, command.yaw, 0.01f, "should hold the easterly heading, not turn back")
    }

    @Test
    fun `a high approach is never steeper than the limit`() {
        val command = ElytraGuidance.steer(
            situation(position = Vec3(0.0, 200.0, 0.0), target = Vec3(20.0, 64.0, 0.0), landing = true),
        )

        assertEquals(ElytraGuidance.MAX_COMMANDED_DIVE, command.pitch)
        assertTrue(
            ElytraGuidance.MAX_COMMANDED_DIVE > ElytraGuidance.APPROACH_MAX_DIVE,
            "the attitude has to be able to lead the flight path it is asking for",
        )
    }
}
