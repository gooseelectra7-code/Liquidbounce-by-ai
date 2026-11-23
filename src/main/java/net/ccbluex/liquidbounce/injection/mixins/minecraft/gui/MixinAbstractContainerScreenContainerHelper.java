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
package net.ccbluex.liquidbounce.injection.mixins.minecraft.gui;

import net.ccbluex.liquidbounce.features.module.modules.player.ModuleContainerHelper;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds container-screen mouse presses to {@link ModuleContainerHelper}.
 *
 * The SystemDLC original consumed the press at the raw GLFW mouse-button
 * callback so vanilla never processed it. The sanctioned equivalent here is
 * cancelling {@code AbstractContainerScreen.mouseClicked} at HEAD: without
 * this, vanilla treats a press on the button row (above the container
 * background) as a click outside the bounds and sends its own -999 PICKUP
 * click, throwing any carried stack.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreenContainerHelper {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void hookContainerHelperButtons(MouseButtonEvent click, boolean doubled,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (ModuleContainerHelper.INSTANCE.handleMouseClick((AbstractContainerScreen<?>) (Object) this, click)) {
            cir.setReturnValue(true);
        }
    }

}
