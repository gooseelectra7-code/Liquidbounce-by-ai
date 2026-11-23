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
package net.ccbluex.liquidbounce.interfaces;

import org.jspecify.annotations.Nullable;

/**
 * Addition to {@link net.minecraft.network.protocol.common.custom.DiscardedPayload}.
 *
 * Vanilla's {@code DiscardedPayload} only stores the channel id and {@code skipBytes()}s the payload contents,
 * leaving downstream listeners with no way to read packets on channels Minecraft doesn't know about
 * (unknown modded channels such as FiguraServerBackend's {@code fsb:*}). {@code MixinDiscardedPayload} replaces the
 * fallback codec for those channels with one that copies the payload bytes onto this side-channel.
 */
public interface DiscardedPayloadAddition {

    @Nullable
    byte[] liquid_bounce$getCapturedData();

    void liquid_bounce$setCapturedData(byte @Nullable [] data);

}
