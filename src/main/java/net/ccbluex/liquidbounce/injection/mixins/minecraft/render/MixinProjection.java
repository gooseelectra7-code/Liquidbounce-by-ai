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

import net.ccbluex.liquidbounce.features.module.modules.render.ModuleAspect;
import net.minecraft.client.renderer.Projection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies {@link ModuleAspect} to the projection the world is actually DRAWN with.
 *
 * The module used to hook only {@code Camera.createProjectionMatrixForCulling}, which — as the name
 * says — builds the matrix for the cull frustum, not for rendering. Changing the aspect there moves
 * only the boundary at which geometry stops being submitted, so the picture on screen never changed
 * and the module looked broken.
 *
 * The render matrix comes from {@link Projection}, which stores {@code width}/{@code height} handed
 * to {@code setupPerspective(zNear, zFar, perspectiveFov, width, height)} and derives the aspect
 * from them. Scaling the width here is what actually stretches the view.
 *
 * The culling hook in {@code MixinCamera} stays, and has to: the cull frustum must follow the render
 * frustum, or the wider view fills its new edges with geometry that was culled away.
 */
@Mixin(Projection.class)
public abstract class MixinProjection {

    /**
     * {@code width} of {@code setupPerspective(float zNear, float zFar, float perspectiveFov,
     * float width, float height)}. Divided, matching the convention the culling hook already uses,
     * so one Ratio value moves both frustums the same way.
     */
    @ModifyVariable(method = "setupPerspective", at = @At("HEAD"), ordinal = 3, argsOnly = true)
    private float hookAspectRatio(float width) {
        if (!ModuleAspect.INSTANCE.getRunning()) {
            return width;
        }

        return width / ModuleAspect.getRatioMultiplier();
    }

}
