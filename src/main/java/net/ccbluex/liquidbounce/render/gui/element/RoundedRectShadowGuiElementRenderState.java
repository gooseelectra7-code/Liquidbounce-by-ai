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

package net.ccbluex.liquidbounce.render.gui.element;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ccbluex.liquidbounce.render.ClientRenderPipelines;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;

/**
 * Drop-shadow for a rounded rectangle, modeled on {@link RoundedRectGuiElementRenderState}: the same
 * signed-distance rounded box, but the fragment shader
 * ({@code shaders/gui/rounded_rect_shadow.fsh}) outputs a blurred silhouette that fades out over
 * {@code blur} GUI units past the box edge.
 *
 * <p>GUI render states are batched by pipeline and texture setup, so per-element parameters
 * (blur, radius, original size, margin, color) are passed through vertex attributes — the exact
 * same batching contract as {@link RoundedRectGuiElementRenderState}. The GUI_ROUNDED_RECT vertex
 * format and {@code rounded_rect.vsh} are reused: {@code Size} carries the original rect size,
 * {@code Parameters.x} the corner radius, {@code Parameters.y} the per-side {@code margin} (so the
 * shader recovers the expanded quad size as {@code Size + 2*margin}), and {@code StrokeWidth} the
 * {@code blur}.</p>
 *
 * <p>{@code (x0,y0)-(x1,y1)} are the EXPANDED quad corners (the original rect grown by {@code margin}
 * on each side and shifted by the shadow offset). Draw this BEHIND the opaque fill, so
 * shadow-then-fill is pixel-identical to a single-pass composite.</p>
 *
 * <p>Unlike its sibling states this implements {@link GuiElementRenderState} directly rather than
 * {@code PoseReusableGuiElementRenderState}: that interface is {@code sealed} with an exhaustive
 * {@code permits} list which cannot be extended here, so the pooled-pose recycling in
 * {@code MixinGuiRenderState} does not apply. Callers therefore pass a NON-pooled pose copy
 * ({@code copyPose()}), exactly as vanilla {@code BlitRenderState} usage does.</p>
 */
public record RoundedRectShadowGuiElementRenderState(
    float x0,
    float y0,
    float x1,
    float y1,
    float width,
    float height,
    float radius,
    float margin,
    float blur,
    int color,
    Matrix3x2f pose,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements GuiElementRenderState {

    private static final int MAX_ENCODED_VALUE = 32767;

    @Override
    public void buildVertices(VertexConsumer vertices) {
        if (width <= 0.0F || height <= 0.0F || x1 <= x0 || y1 <= y0) {
            return;
        }
        if (!isVisible(color)) {
            return;
        }

        int encodedWidth = encode(width);
        int encodedHeight = encode(height);
        int encodedRadius = encode(Math.min(radius, Math.min(width, height) * 0.5F));
        int encodedMargin = encode(margin);
        float strokeBlur = Math.max(blur, 0.0F);

        vertices.addVertexWith2DPose(pose, x0, y0)
            .setUv(0.0F, 0.0F)
            .setColor(color)
            .setUv1(encodedWidth, encodedHeight)
            .setUv2(encodedRadius, encodedMargin)
            .setLineWidth(strokeBlur);
        vertices.addVertexWith2DPose(pose, x0, y1)
            .setUv(0.0F, 1.0F)
            .setColor(color)
            .setUv1(encodedWidth, encodedHeight)
            .setUv2(encodedRadius, encodedMargin)
            .setLineWidth(strokeBlur);
        vertices.addVertexWith2DPose(pose, x1, y1)
            .setUv(1.0F, 1.0F)
            .setColor(color)
            .setUv1(encodedWidth, encodedHeight)
            .setUv2(encodedRadius, encodedMargin)
            .setLineWidth(strokeBlur);
        vertices.addVertexWith2DPose(pose, x1, y0)
            .setUv(1.0F, 0.0F)
            .setColor(color)
            .setUv1(encodedWidth, encodedHeight)
            .setUv2(encodedRadius, encodedMargin)
            .setLineWidth(strokeBlur);
    }

    @Override
    public RenderPipeline pipeline() {
        return ClientRenderPipelines.GUI.roundedRectShadow();
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    private static int encode(float value) {
        return Math.round(Math.clamp(value, 0.0F, MAX_ENCODED_VALUE));
    }

    private static boolean isVisible(int argb) {
        return (argb >>> 24) != 0;
    }
}
