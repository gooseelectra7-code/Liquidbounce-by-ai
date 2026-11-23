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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleCustomAmbience;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleSkyShader;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.jspecify.annotations.NullMarked;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(SkyRenderer.class)
public abstract class MixinSkyRenderer {

    @WrapOperation(
        method = "extractRenderState",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/level/SkyRenderState;skyColor:I", opcode = Opcodes.PUTFIELD)
    )
    private void applyCustomSkyColor(SkyRenderState instance, int value, Operation<Void> original) {
        var customSkyColor = ModuleCustomAmbience.SkyColor.INSTANCE;
        if (customSkyColor.getRunning()) {
            value = customSkyColor.getColor().argb();
        }
        original.call(instance, value);
    }

    // --- SkyShader full-screen sky replacement ---------------------------------------------------
    // While ModuleSkyShader is active every vanilla sky draw is cancelled and the module's own
    // full-screen pass is drawn in place of the sky-colour disc (overworld) / end sky (End). Both
    // run in the frame graph SKY pass before terrain, so terrain composes over the replacement.
    // Mirrors SystemDLC WorldRenderHooks, which cancelled the same SkyRendering draws and called the
    // module from the sky-disc method.

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void replaceSkyDisc(int color, CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ModuleSkyShader.renderSky();
            ci.cancel();
        }
    }

    @Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true)
    private void replaceEndSky(CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ModuleSkyShader.renderSky();
            ci.cancel();
        }
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void cancelDarkDisc(CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void cancelSunMoonAndStars(PoseStack poseStack, float rain, float alpha, float twilightAlpha,
                                       MoonPhase moonPhase, float sunAngle, float sizeScale, CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void cancelSunriseAndSunset(PoseStack poseStack, float angle, int color, CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEndFlash", at = @At("HEAD"), cancellable = true)
    private void cancelEndFlash(PoseStack poseStack, float scale, float alpha, float rotation, CallbackInfo ci) {
        if (ModuleSkyShader.isActive()) {
            ci.cancel();
        }
    }

}
