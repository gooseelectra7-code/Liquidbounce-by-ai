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
package net.ccbluex.liquidbounce.injection.mixins.minecraft.network;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.*;
import net.ccbluex.liquidbounce.features.command.commands.ingame.CommandFakeGameMode;
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.ModuleAutoBow;
import net.ccbluex.liquidbounce.utils.client.SilentHotbar;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode {

    /**
     * Hook attacking entity
     */
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void hookAttack(Player player, Entity target, CallbackInfo callbackInfo) {
        var event = EventManager.INSTANCE.callEvent(new AttackEntityEvent(target));
        if (event.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    /**
     * Hook right-click entity interaction so a module can cancel it before the client sends the
     * interaction packet. Returning PASS both skips the packet and lets vanilla's hand loop move on,
     * so cancelling both hands emits nothing.
     *
     * <p>One hook covers it: the separate {@code interactAt} that used to carry the hit vector was
     * folded into {@code interact} itself, which now takes the {@link EntityHitResult}.
     */
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void hookInteractEntity(Player player, Entity target, EntityHitResult ray, InteractionHand hand,
                                    CallbackInfoReturnable<InteractionResult> cir) {
        var event = EventManager.INSTANCE.callEvent(new PlayerInteractEntityEvent(player, target, hand));
        if (event.isCancelled()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    /**
     * Hook into updateBlockBreakingProgress method at HEAD and call BlockBreakingProgress event.
     */
    @Inject(method = "continueDestroyBlock", at = @At(value = "HEAD"))
    private void hookBlockBreakingProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        final BlockBreakingProgressEvent blockBreakingProgressEvent = new BlockBreakingProgressEvent(pos);
        EventManager.INSTANCE.callEvent(blockBreakingProgressEvent);
    }

    /**
     * Hook into cancel block breaking at HEAD and call cancel block breaking event, which is able to cancel the execution.
     */
    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void hookCancelBlockBreaking(CallbackInfo callbackInfo) {
        final CancelBlockBreakingEvent cancelEvent = new CancelBlockBreakingEvent();
        EventManager.INSTANCE.callEvent(cancelEvent);

        if (cancelEvent.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void hookAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        var attackEvent = new BlockAttackEvent(pos);
        EventManager.INSTANCE.callEvent(attackEvent);
        if (attackEvent.isCancelled()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * @author superblaubeere27
     */
    @ModifyExpressionValue(method = "ensureHasSentCarriedItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedSlot()I"))
    private int hookCustomSelectedSlot(int original) {
        return SilentHotbar.INSTANCE.getServersideSlot();
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void hookItemInteractAtReturn(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        final PlayerInteractedItemEvent cancelEvent = new PlayerInteractedItemEvent(player, hand, cir.getReturnValue());
        EventManager.INSTANCE.callEvent(cancelEvent);
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void hookItemInteractAtHead(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        final PlayerInteractItemEvent cancelEvent = new PlayerInteractItemEvent(player, hand);
        EventManager.INSTANCE.callEvent(cancelEvent);
        if (cancelEvent.isCancelled()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void stopUsingItem(Player player, CallbackInfo callbackInfo) {
        ModuleAutoBow.onStopUsingItem();
    }

    /**
     * Client-side game mode spoof, see {@link CommandFakeGameMode}. Swapping the argument at HEAD makes the
     * whole method run on the faked mode: {@code localPlayerMode}, {@code updatePlayerAbilities} and the
     * {@code GameModeChangeEvent} injected at RETURN below all see it, while the hook keeps the mode the
     * server really sent.
     * <p>
     * {@code ordinal = 0} rather than a parameter name, so the second overload - whose two arguments are both
     * {@link GameType} - resolves to {@code mode} without depending on the local variable table.
     */
    @ModifyVariable(method = "setLocalMode(Lnet/minecraft/world/level/GameType;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private GameType hookLocalMode(GameType gameMode) {
        return CommandFakeGameMode.onLocalModeSet(gameMode);
    }

    @ModifyVariable(method = "setLocalMode(Lnet/minecraft/world/level/GameType;Lnet/minecraft/world/level/GameType;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private GameType hookLocalModeWithPrevious(GameType gameMode) {
        return CommandFakeGameMode.onLocalModeSet(gameMode);
    }

    @Inject(method = "setLocalMode(Lnet/minecraft/world/level/GameType;)V", at = @At("RETURN"))
    private void setGameMode(GameType gameMode, CallbackInfo callbackInfo) {
        EventManager.INSTANCE.callEvent(new GameModeChangeEvent(gameMode));
    }

    @Inject(method = "setLocalMode(Lnet/minecraft/world/level/GameType;Lnet/minecraft/world/level/GameType;)V", at = @At("RETURN"))
    private void setGameModes(GameType gameMode, GameType previousGameMode, CallbackInfo callbackInfo) {
        EventManager.INSTANCE.callEvent(new GameModeChangeEvent(gameMode));
    }

}
