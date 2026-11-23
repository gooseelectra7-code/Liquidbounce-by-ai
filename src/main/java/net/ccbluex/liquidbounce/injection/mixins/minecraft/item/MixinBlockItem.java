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

package net.ccbluex.liquidbounce.injection.mixins.minecraft.item;

import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.ClientBlockPlaceEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports the local player's own block placements, with the exact cell vanilla placed into.
 *
 * <p>This is the authoritative answer to "did WE just place that block, and where". Everything else
 * available to a module is indirect: the outgoing {@code ServerboundUseItemOnPacket} carries the
 * clicked face and leaves the placement cell to be re-derived (two candidates, one of them wrong),
 * and watching the world for a block that appeared cannot say whose it is. Here the placement has
 * already been decided - {@link BlockPlaceContext#getClickedPos()} is the cell, with the
 * "replaced the block I clicked" case already folded in - and only our own client reaches this code
 * for our own player.
 *
 * <p>It fires on the client's prediction, so before the server has confirmed anything; a consumer
 * that cares whether the block survived has to check the world afterwards. The integrated server
 * runs the same method with {@code isClientSide == false} and is filtered out here, so singleplayer
 * reports one placement rather than two.
 */
@NullMarked
@Mixin(BlockItem.class)
public abstract class MixinBlockItem {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
    private void hookClientBlockPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!context.getLevel().isClientSide() || context.getPlayer() != Minecraft.getInstance().player) {
            return;
        }

        if (!cir.getReturnValue().consumesAction()) {
            return;
        }

        EventManager.INSTANCE.callEvent(new ClientBlockPlaceEvent(
            context.getClickedPos().immutable(),
            ((BlockItem) (Object) this).getBlock(),
            context.getHand()
        ));
    }

}
