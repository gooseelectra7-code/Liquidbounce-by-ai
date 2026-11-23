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

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.common.OutlineFlag;
import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.DrawOutlinesEvent;
import net.ccbluex.liquidbounce.features.module.modules.render.*;
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.EspHaloMode;
import net.ccbluex.liquidbounce.features.module.modules.render.frageffects.FragEffectsShader;
import net.ccbluex.liquidbounce.features.module.modules.render.killeffect.KillEffectScanMode;
import net.ccbluex.liquidbounce.render.engine.OutlineShaderRenderer;
import net.ccbluex.liquidbounce.utils.collection.Pools;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Nullable
    public abstract RenderTarget entityOutlineTarget();

    @Shadow
    protected abstract boolean shouldShowEntityOutlines();

    // After ModelViewMatrix setup
    @Inject(method = "renderLevel", at = @At(value = "NEW", target = "()Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;"))
    private void onRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
        OutlineShaderRenderer renderer = OutlineShaderRenderer.INSTANCE;
        if (!renderer.shouldRender()) {
            return;
        }

        var matrixStack = Pools.MatStack.borrow();
        var event = new DrawOutlinesEvent(
            renderer.prepareRenderTarget(),
            matrixStack,
            cameraState,
            deltaTracker.getGameTimeDeltaPartialTick(false),
            DrawOutlinesEvent.OutlineType.INBUILT_OUTLINE
        );
        EventManager.INSTANCE.callEvent(event);
        Pools.MatStack.recycle(matrixStack);

        if (event.getDirtyFlag()) {
            renderer.setDirty(true);
        }
    }

    @ModifyExpressionValue(method = "lambda$renderLevel$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;colorFromFloat(FFFF)I"))
    private int customFogClearColor(int original) {
        return ModuleCustomAmbience.FogValueGroup.INSTANCE.modifyClearColor(original);
    }

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OutlineBufferSource;endOutlineBatch()V"))
    private void onDrawOutlines(CallbackInfo ci) {
        OutlineShaderRenderer.INSTANCE.drawBlitIfDirty(this.minecraft.getMainRenderTarget());
    }

    /**
     * FragEffects desaturation, applied once the main pass has drawn the world so it affects the
     * scene but not the HUD drawn afterwards — the same place the vanilla post-effect it replaces
     * used to sit.
     */
    @Inject(method = "lambda$addMainPass$0", at = @At("TAIL"))
    private void onFragEffectsPostProcess(CallbackInfo ci) {
        // Both of these are full-screen passes over the finished scene: the world is in the main
        // target and the depth buffer is complete, while the HUD has not been drawn yet.
        KillEffectScanMode.render();
        FragEffectsShader.apply();
        // Saturation's OnlyWorld=true branch: same before-HUD point. applyWorld() self-gates on
        // running && onlyWorld and the dead-band, so it is inert unless that mode is active.
        ModuleSaturation.applyWorld();
    }

    /**
     * AFTER {@code endOutlineBatch()}, not before it. Upstream had {@code Shift.BEFORE} and that
     * handed every listener an incomplete entity-outline target.
     * <p>
     * {@link net.minecraft.client.renderer.OutlineBufferSource} is
     * {@code MultiBufferSource.immediate(new ByteBufferBuilder(1536))} with no fixed buffers, so
     * every outline batch shares one builder. {@code BufferSource.getBuffer} draws the pending
     * batch when the shared render type changes ({@code MultiBufferSource.java:59-61}); the final
     * one waits for {@code endBatch} ({@code :72-77}), and {@code LevelRenderer.java:710} is its
     * only call site in the whole client. One instruction earlier the target is therefore always
     * short exactly one batch, whichever it happens to be.
     * <p>
     * Which one is scene-dependent but not random. The outline render type is keyed on
     * {@code (first texture, cull)} ({@code RenderType.java:41-43}), NOT on the entity, so players
     * sharing a skin share a batch. Player bodies use {@code RenderTypes::entityTranslucent}
     * ({@code PlayerModel.java:36}), which blends ({@code RenderPipelines.java:320-328}) and so
     * lands in the translucent list sorted {@code -lengthSquared()}, nearest LAST
     * ({@code ModelFeatureRenderer.java:48}); capes ({@code entitySolid}) and armour/elytra
     * ({@code armorCutoutNoCull}) do not blend and are drawn in the earlier solid pass. In a plain
     * fight the pending batch was therefore the nearest player's BODY - unless a translucent
     * model-part, item or block submit in the same or a higher order bucket took its place
     * ({@code FeatureRenderDispatcher.java:70-89} walks {@code submitsPerOrder} ascending).
     * <p>
     * Unrelated hazard worth knowing while here: {@code GameRenderer.java:378} and {@code :763}
     * run the same {@code FeatureRenderDispatcher} again after {@code renderLevel} and never call
     * {@code endOutlineBatch}, so outline geometry submitted during the hand or HUD passes leaks
     * into the NEXT frame's target. Vanilla submits outline colour 0 there.
     */
    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OutlineBufferSource;endOutlineBatch()V", shift = At.Shift.AFTER))
    private void onRenderGlow(CallbackInfo ci) {
        var entityOutlineFb = entityOutlineTarget();
        if (!this.shouldShowEntityOutlines() || entityOutlineFb == null) {
            return;
        }

        var matrixStack = Pools.MatStack.borrow();
        entityOutlineFb.blitToScreen();
        final var cameraState = this.minecraft.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        var event = new DrawOutlinesEvent(
            entityOutlineFb, matrixStack,
            cameraState,
            this.minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false),
            DrawOutlinesEvent.OutlineType.MINECRAFT_GLOW
        );
        EventManager.INSTANCE.callEvent(event);
        Pools.MatStack.recycle(matrixStack);
        OutlineFlag.drawOutline |= event.getDirtyFlag();
    }

    @WrapOperation(method = "renderLevel", at = @At(
        value = "FIELD",
        target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;haveGlowingEntities:Z",
        opcode = Opcodes.GETFIELD
    ))
    private boolean modifyDrawOutline(LevelRenderState instance, Operation<Boolean> original) {
        // Halo draws its own composite from the silhouettes and replaces the vanilla outline pass;
        // the silhouettes themselves are flushed in the main pass, which this does not gate.
        // Another module that asked for the outline pass still gets it.
        if (EspHaloMode.usesVanillaSilhouette() && !OutlineFlag.drawOutline) {
            liquid_bounce$haloOwnsOutlineTarget = true;
            return false;
        }

        liquid_bounce$haloOwnsOutlineTarget = false;

        var flag = OutlineFlag.drawOutline;
        if (flag) {
            OutlineFlag.drawOutline = false;
            return true;
        }
        return original.call(instance);
    }

    /**
     * Set while Halo has taken the entity-outline target over for its own composite, so the
     * silhouettes in it were never turned into outlines by the vanilla post chain.
     */
    @Unique
    private static boolean liquid_bounce$haloOwnsOutlineTarget;

    /**
     * Vanilla blends the entity-outline target over the finished frame here, which is after the
     * world render event - so the raw silhouettes Halo left in it would bury everything drawn
     * there: target renderers, nametags, tracers. Halo blends the same target itself, next to its
     * composite in the main pass, so this pass has nothing left to do.
     */
    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void cancelHaloOutlineBlit(CallbackInfo ci) {
        if (liquid_bounce$haloOwnsOutlineTarget) {
            ci.cancel();
        }
    }

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;cullTerrain(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Z)V"), index = 2)
    private boolean renderSetupTerrainModifyArg(boolean spectator) {
        return ModuleFreeCam.INSTANCE.getRunning() || spectator;
    }

    @Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void cancelBlockOutline(MultiBufferSource.BufferSource immediate, PoseStack matrices, boolean renderBlockOutline, LevelRenderState renderStates, CallbackInfo ci) {
        if (ModuleBlockOutline.INSTANCE.getRunning()) {
            ci.cancel();
        }
    }

    @WrapWithCondition(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;JI)V"))
    private boolean cancelRenderBreakingTexture(SubmitNodeCollector instance, PoseStack poseStack, BlockStateModel blockStateModel, long l, int i) {
        return ModuleAntiBlind.canRender(DoRender.BLOCK_BREAK_OVERLAY);
    }

}
