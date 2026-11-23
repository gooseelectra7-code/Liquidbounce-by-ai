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

package net.ccbluex.liquidbounce.injection.mixins.blaze3d.gl;

import com.mojang.blaze3d.shaders.ShaderSource;
import net.ccbluex.liquidbounce.render.ClientShaders;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Wraps the GL device's {@code defaultShaderSource} so that any pipeline compiled WITHOUT an
 * explicitly-provided {@link ShaderSource} (e.g. the per-dimension pipeline recompile triggered by
 * Sodium/Iris/vanilla) can still resolve LiquidBounce's in-memory custom shaders.
 *
 * <p>Without this, custom-shader pipelines that are referenced by registered {@code RenderType}s
 * (such as the entity-chams render types) fail to recompile on dimension change with
 * "Couldn't find source for ... shader (liquidbounce:shader/...)", leaving the affected entities
 * invisible. The explicit-source path used by {@code ClientRenderPipelines.precompile()} is
 * unaffected; this only adds a fallback for the default-source path.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public abstract class MixinGlDevice {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static @Nullable ShaderSource liquid_bounce$wrapDefaultShaderSource(@Nullable ShaderSource original) {
        if (original == null) {
            return null;
        }

        return (identifier, type) -> {
            String source = original.get(identifier, type);
            return source != null ? source : ClientShaders.Companion.get(identifier, type);
        };
    }

}
