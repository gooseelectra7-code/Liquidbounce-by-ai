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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.PlayerFluidCollisionCheckEvent;
import net.ccbluex.liquidbounce.features.module.modules.movement.ModuleNoPush;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(EntityFluidInteraction.class)
public abstract class MixinEntityFluidInteraction {

    @Inject(method = "applyCurrentTo", at = @At(value = "HEAD"), cancellable = true)
    private void hookFluidMovement(TagKey<Fluid> fluid, Entity entity, double scale, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player) {
            return;
        }

        var event = EventManager.INSTANCE.callEvent(new PlayerFluidCollisionCheckEvent(fluid));

        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    /**
     * NoPush Liquids: the local player's current is catlean's flow (vanilla's minus the horizontal part), see
     * {@link ModuleNoPush#liquidFlow}. Only this entity's own pass is touched, never the shared {@code getFlow}.
     */
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 hookNoPushInLiquids(FluidState instance, BlockGetter level, BlockPos pos, Operation<Vec3> original, @Local(argsOnly = true, name = "entity") Entity entity) {
        if (entity != Minecraft.getInstance().player || !ModuleNoPush.ignoresLiquids()) {
            return original.call(instance, level, pos);
        }

        Vec3 flow = ModuleNoPush.liquidFlow(level, pos, instance);
        return flow != null ? flow : original.call(instance, level, pos);
    }

}
