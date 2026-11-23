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

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.ccbluex.liquidbounce.render.burninghands.BurningHandsRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BurningHands (exploit-client port): captures the first-person hands into an offscreen
 * mask FBO via a SECOND submit into a private storage, flushed by the module itself.
 *
 * Why not the override-redirect: in this runtime vanilla's own {@code endBatch} hand
 * rasterization does NOT honor {@code RenderSystem.outputColorTextureOverride} (hands stay
 * on the main framebuffer, mask stays empty — readback-proven), while the module's own
 * {@code endBatch} flush through the same {@code RenderType#draw} path DOES (iteration-4/5
 * produced mask content). So vanilla draws the on-screen hands entirely by itself, and the
 * module captures its own identical copy:
 *
 * 1. BEFORE the vanilla {@code renderHandsWithItems} invoke: push the hand {@link PoseStack}
 *    (the method mutates the passed pose with the xBob/yBob rotations and does NOT restore
 *    it — verified: 0 pushPose/0 popPose in {@code renderHandsWithItems} itself).
 * 2. AFTER the invoke: pop back to the exact pose vanilla saw, then call
 *    {@code renderHandsWithItems} a SECOND time with the SAME pose object and the SAME light
 *    vanilla computes at the call site
 *    ({@code minecraft.getEntityRenderDispatcher().getPackedLightCoords(player, tickDelta)}),
 *    submitting into the module's PRIVATE {@code SubmitNodeStorage}. Submit nodes deep-copy
 *    the pose ({@code PoseStack.Pose.copy()} -> {@code new Pose().set(this)}), so the stored
 *    copies are byte-identical to vanilla's.
 * 3. At the same point, stash the {@code positionMatrix} parameter — that is
 *    {@code CameraRenderState.viewRotationMatrix} (R), which {@code renderItemInHand} bakes
 *    INVERTED into the hand pose while compensating via {@code modelViewStack.mul(R)}. That
 *    push is popped immediately after the submit (before anything rasterizes), so the module
 *    has to supply R itself — see {@code BurningHandsRenderer.vanillaModelView}. Reading the
 *    model-view at the rasterization point yields the identity (measured), which is why the
 *    earlier stash-at-{@code endBatch} attempt was a no-op.
 * 4. Shift.AFTER the {@code endBatch} invoke in {@code renderLevel} (vanilla's hands are on
 *    screen by then): apply R + the stashed hand projection around our own dispatcher flush +
 *    {@code endBatch}, rasterizing the private storage into HAND_MASK with the output
 *    overrides set. Then restore the state and run glow -> trail -> composite.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererBurningHands {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    public ItemInHandRenderer itemInHandRenderer;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Unique
    private boolean liquidbounce$burningHandsGuarded;

    @Inject(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void liquidbounce$burningHandsGuardPose(CameraRenderState cameraRenderState, float tickDelta,
                                                    Matrix4fc positionMatrix, CallbackInfo ci,
                                                    @Local PoseStack poseStack) {
        this.liquidbounce$burningHandsGuarded = false;
        if (!BurningHandsRenderer.canCapture()) {
            return;
        }
        // renderHandsWithItems mutates the top pose and does not restore it; keep a copy on
        // the stack so the second call sees the identical entry pose.
        poseStack.pushPose();
        this.liquidbounce$burningHandsGuarded = true;
    }

    @Inject(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V",
            shift = At.Shift.AFTER))
    private void liquidbounce$burningHandsSubmitCopy(CameraRenderState cameraRenderState, float tickDelta,
                                                     Matrix4fc positionMatrix, CallbackInfo ci,
                                                     @Local PoseStack poseStack) {
        if (!this.liquidbounce$burningHandsGuarded) {
            return;
        }
        this.liquidbounce$burningHandsGuarded = false;
        // R = CameraRenderState.viewRotationMatrix. The hand pose carries R^-1; the model-view
        // push that cancels it is gone before anything rasterizes, so the flush re-applies R.
        BurningHandsRenderer.stashHandViewMatrix(positionMatrix);
        // Restore the exact pose vanilla's call saw, then submit an identical second copy
        // into the module's private storage.
        poseStack.popPose();
        this.itemInHandRenderer.renderHandsWithItems(
                tickDelta,
                poseStack,
                BurningHandsRenderer.submitStorage(),
                this.minecraft.player,
                this.minecraft.getEntityRenderDispatcher().getPackedLightCoords(this.minecraft.player, tickDelta)
        );
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
            shift = At.Shift.AFTER))
    private void liquidbounce$burningHandsCapture(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!BurningHandsRenderer.canCapture()) {
            BurningHandsRenderer.clearCapturedGeometry();
            return;
        }
        // Vanilla's hands are on screen. Flush our identical copy into HAND_MASK
        // (adjacent RenderSystem state), then blit -> glow -> trail -> composite.
        BurningHandsRenderer.flushCapturedGeometry(this.gameRenderState);
    }
}
