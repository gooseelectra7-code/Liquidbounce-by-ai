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

package net.ccbluex.liquidbounce.injection.mixins.minecraft.render;

import net.ccbluex.liquidbounce.features.module.modules.render.ModuleSkyShader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla cloud pass while {@link ModuleSkyShader} is active. The clouds pass runs
 * after the terrain (main) pass, so without this it would draw on top of the procedural sky. Mirrors
 * the SystemDLC WorldRenderHooks cloud cancel.
 */
@NullMarked
@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cancelClouds(int color, CloudStatus cloudStatus, float cloudHeight, int cloudRange,
                              Vec3 cameraPosition, long ticks, float partialTick, CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ci.cancel();
        }
    }

}
