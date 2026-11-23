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

import net.ccbluex.liquidbounce.features.module.modules.render.ModuleSaturation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whole-frame ({@code OnlyWorld = false}) half of {@link ModuleSaturation}.
 *
 * <p>Injected right after {@code GuiRenderer.render(GpuBufferSlice)} in {@code GameRenderer.render}.
 * At that point the world main pass, the vanilla post-chain and the HUD have all drawn into the main
 * render target, and nothing in {@code render} has presented the frame yet (there is no
 * {@code blitToScreen} in {@code GameRenderer} — the present happens later in the client loop). So this
 * is the composited-frame point: the saturation ping-pong sees world + HUD together, exactly what the
 * original's OnlyWorld=OFF branch applied over.
 *
 * <p>The world-only branch is handled elsewhere ({@code MixinLevelRenderer.lambda$addMainPass$0} TAIL,
 * before the HUD is drawn) so the two sites stay mutually exclusive. {@link ModuleSaturation#applyFrame()}
 * self-gates on {@code running && !onlyWorld} and the 0.001 dead-band, so an idle or world-only module
 * costs nothing here.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererSaturation {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            shift = At.Shift.AFTER))
    private void liquidbounce$saturationWholeFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        ModuleSaturation.applyFrame();
    }
}
