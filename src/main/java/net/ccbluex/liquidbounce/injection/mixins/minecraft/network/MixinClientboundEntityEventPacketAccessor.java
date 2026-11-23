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

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the subject of an entity event.
 * <p>
 * The packet only offers {@code getEntity(Level)}, which needs the world and therefore the main
 * thread, and which returns null once the entity is gone. A death is very often followed by the
 * server removing the body in the same tick, so anything that reads the packet after the fact — a
 * queue drained on the next tick, say — can no longer tell who died. The raw id survives that.
 */
@Mixin(ClientboundEntityEventPacket.class)
public interface MixinClientboundEntityEventPacketAccessor {

    @Accessor("entityId")
    int liquid_bounce$getEntityId();

}
