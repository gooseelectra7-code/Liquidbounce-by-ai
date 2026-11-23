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
package net.ccbluex.liquidbounce.injection.mixins.minecraft.registry;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.ccbluex.liquidbounce.LiquidBounce;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Survive missing damage_type/banner_pattern/etc. tags during the configuration phase.
 *
 * When connecting to a server that runs an older Minecraft protocol via ViaFabricPlus, some tags that
 * newer clients require to initialize item data components are simply not sent (the source protocol
 * predates them). The vanilla flow inside {@code RegistryDataCollector.updateComponents} →
 * {@code DataComponentInitializers.build} → {@code InitializerEntry.run} ends up calling
 * {@code HolderGetter.getOrThrow} on the missing {@code TagKey}, which raises
 * {@code IllegalStateException: Missing tag TagKey[...]} and aborts {@code ClientboundFinishConfigurationPacket},
 * disconnecting the client with "Network Protocol Error".
 *
 * Catch only that specific failure and skip the offending initializer. The affected item just won't
 * receive the unavailable data component locally (e.g. {@code DataComponents.FIRE_RESISTANT} won't be
 * attached to netherite items if {@code damage_type/is_fire} is missing). Authoritative behavior is
 * server-side, so gameplay still works; we simply don't crash the configuration phase.
 */
@Mixin(targets = "net.minecraft.core.component.DataComponentInitializers$InitializerEntry")
public abstract class MixinDataComponentInitializersInitializerEntry {

    @WrapMethod(method = "run")
    private void liquid_bounce$swallowMissingTag(DataComponentMap.Builder builder,
                                                 HolderLookup.Provider provider,
                                                 Operation<Void> original) {
        try {
            original.call(builder, provider);
        } catch (IllegalStateException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Missing tag ")) {
                LiquidBounce.INSTANCE.getLogger().warn(
                    "[VFP-compat] Skipped data component initializer due to {} " +
                        "(likely a ViaFabricPlus protocol gap; affected component not attached locally).",
                    msg
                );
                return;
            }
            throw e;
        }
    }
}
