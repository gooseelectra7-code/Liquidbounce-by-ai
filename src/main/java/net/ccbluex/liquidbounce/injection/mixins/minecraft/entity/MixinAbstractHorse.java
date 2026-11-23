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
package net.ccbluex.liquidbounce.injection.mixins.minecraft.entity;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.ccbluex.liquidbounce.utils.navigation.pathing.route.NavMountSteering;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Silent steering for a ridden mount.
 *
 * Vanilla derives the direction a horse travels from the rider's real head rotation, so a silent
 * rotation - which only rewrites what is sent to the server - leaves the animal walking wherever
 * the camera happens to point. Redirecting the mount's own steering rotation is the same technique
 * the elytra hooks in {@link MixinLivingEntity} already use for gliding, and it keeps the player's
 * view free while travelling.
 * <p>
 * The server is told the same rotation the animal steers by, so the two stay consistent.
 */
@Mixin(AbstractHorse.class)
public abstract class MixinAbstractHorse {

    /**
     * {@code getRiddenRotation} is the single place a horse reads its rider's facing, returning
     * {@code Vec2(pitch * 0.5, yaw)}. Only the yaw is substituted: pitch does not affect where the
     * animal goes, and leaving it alone keeps the head looking where the player is looking.
     */
    @ModifyReturnValue(method = "getRiddenRotation", at = @At("RETURN"))
    private Vec2 hookSilentRiddenRotation(Vec2 original) {
        var rotation = NavMountSteering.steeringRotationFor((AbstractHorse) (Object) this);

        if (rotation == null) {
            return original;
        }

        return new Vec2(original.x, rotation.yaw());
    }
}
