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

import io.netty.handler.codec.DecoderException;
import net.ccbluex.liquidbounce.interfaces.DiscardedPayloadAddition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(DiscardedPayload.class)
public abstract class MixinDiscardedPayload implements DiscardedPayloadAddition {

    @Unique
    private byte @Nullable [] liquid_bounce$capturedData;

    @Override
    public byte @Nullable [] liquid_bounce$getCapturedData() {
        return liquid_bounce$capturedData;
    }

    @Override
    public void liquid_bounce$setCapturedData(byte @Nullable [] data) {
        this.liquid_bounce$capturedData = data;
    }

    /**
     * For a handful of modded plugin channels, replace the default "skip bytes" codec with one that
     * copies the payload bytes onto the {@link DiscardedPayloadAddition} so client-side listeners
     * can decode them.
     * <p>
     * {@code fsb} is FiguraServerBackend, read by {@code ModuleFiguraSnooper}: that plugin
     * broadcasts every avatar ping to every online player over {@code fsb:ping}. {@code figura} is
     * kept because forks of the plugin have shipped under that namespace - the official Figura
     * client itself does not use plugin channels at all, it talks to its own backend.
     * <p>
     * Encoding is symmetric: bytes staged through {@code liquid_bounce$setCapturedData} are written
     * back out, so a {@code DiscardedPayload} on one of these channels can also be sent without
     * registering a typed payload.
     */
    @Inject(method = "codec", at = @At("HEAD"), cancellable = true)
    private static void liquid_bounce$captureUnknownPayload(Identifier id, int maxLength,
                                                            CallbackInfoReturnable<StreamCodec<FriendlyByteBuf, DiscardedPayload>> cir) {
        String namespace = id.getNamespace();
        if (!"fsb".equals(namespace) && !"figura".equals(namespace)) return;

        cir.setReturnValue(new StreamCodec<>() {
            @Override
            public DiscardedPayload decode(FriendlyByteBuf buf) {
                int n = buf.readableBytes();
                if (n < 0 || n > maxLength) {
                    throw new DecoderException("Payload may not be larger than " + maxLength + " bytes");
                }
                byte[] data = new byte[n];
                buf.readBytes(data);
                DiscardedPayload payload = new DiscardedPayload(id);
                ((DiscardedPayloadAddition) (Object) payload).liquid_bounce$setCapturedData(data);
                return payload;
            }

            @Override
            public void encode(FriendlyByteBuf buf, DiscardedPayload payload) {
                byte[] data = ((DiscardedPayloadAddition) (Object) payload).liquid_bounce$getCapturedData();
                if (data != null) {
                    buf.writeBytes(data);
                }
            }
        });
    }
}
