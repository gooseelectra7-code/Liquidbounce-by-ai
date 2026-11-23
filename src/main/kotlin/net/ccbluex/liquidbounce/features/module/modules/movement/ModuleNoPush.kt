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
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.IceBlock
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3

/**
 * NoPush, ported from catlean (`su.catlean.NoPush`, obfuscated `su.catlean.bu` in `catlean_26.2.jar`).
 *
 * It replaced the upstream module on 2026-09-24 at the user's request. Four toggles, all on by default as in
 * catlean. The upstream `PushBy` list (a list of what MAY push, inverted from these) and its `Sinking` option are
 * gone with it.
 *
 * Read from the original bytecode, not only the decompile: the decompile reads `bu.n` backwards, because the
 * obfuscator's predicate there is `ch.P()` (true) while the handlers use `ch.E()` (false, confirmed for `bu.s`
 * and `bu.i` by the live dump in `deobf/dump3/callsites.tsv`). What decides it is that the body `bu.n` guards is
 * vanilla's `FlowingFluid.isSolidFace` line for line, which an obfuscator cannot invent. The one encrypted constant,
 * the entity event id, decrypts to 31.
 *
 * - **Blocks** - `LocalPlayer.moveTowardsClosestSpace` does nothing (hooked in `MixinLocalPlayer`).
 * - **Entities** - inside `Entity.push(Entity)` both `push(x, y, z)` calls run with zeros when the receiver is
 *   the local player (hooked in `MixinEntity`), as catlean's `@ModifyArgs` does. On the client that receiver is
 *   the only case that matters: `EntitySelector.pushableBy` lets nothing but the local player be pushed there
 *   (`EntitySelector.java:36`), and every living entity reaches it through `doPush` -> `player.push(this)`
 *   (`LivingEntity.java:3203-3205`).
 * - **Liquids** - the current is [liquidFlow]: vanilla's `FlowingFluid.getFlow` with the horizontal term removed
 *   and the falling-column term kept, so a waterfall still pulls down and nothing pushes sideways. Water and lava
 *   alike, since both are `FlowingFluid`.
 * - **FishingHook** - a hook that has us hooked does not pull us. catlean drops the whole
 *   `ClientboundEntityEventPacket` with id 31; `FishingHook.handleEntityEvent` does nothing else with that id than
 *   the pull (`FishingHook.java:488-494`), so skipping the pull (`MixinFishingHook`) is the same thing.
 *
 * One deliberate difference: catlean hooks `FlowingFluid.getFlow` itself, which every caller shares - including
 * `FluidRenderer` (`FluidRenderer.java:132`), so with the module on every water surface in the world was meshed
 * as still water, and every other entity's client-side drift changed too. Here the replacement is applied only to
 * the local player's own fluid pass (`EntityFluidInteraction.update`, `:74`, hooked in
 * `MixinEntityFluidInteraction`).
 *
 * Grim, read from the clone at `8eb5f28`: `Liquids` is not safe in moving water - Grim's own fluid pass applies
 * the full vanilla current (`predictionengine/EntityFluidInteraction.java:81`) with no branch for a client that
 * ignores it, so `Simulation` sees the missing push. Entity pushes are an uncertainty allowance there
 * (`PredictionEngine.java:490`); whether that fully covers an ignored push was not verified.
 */
object ModuleNoPush : ClientModule("NoPush", ModuleCategories.MOVEMENT) {

    private val blocks by boolean("Blocks", true)
    private val entities by boolean("Entities", true)
    private val liquids by boolean("Liquids", true)
    private val fishingHook by boolean("FishingHook", true)

    @JvmStatic
    fun ignoresBlocks() = running && blocks

    @JvmStatic
    fun ignoresEntities() = running && entities

    @JvmStatic
    fun ignoresLiquids() = running && liquids

    @JvmStatic
    fun ignoresFishingHook() = running && fishingHook

    /**
     * catlean's `WaterPushEvent` handler (`bu.i`): the flow of [state] at [pos] as vanilla computes it, minus the
     * horizontal part. Null for a fluid that is not a [FlowingFluid], whose flow the caller keeps as it is.
     */
    @JvmStatic
    fun liquidFlow(level: BlockGetter, pos: BlockPos, state: FluidState): Vec3? {
        val fluid = state.type as? FlowingFluid ?: return null

        var flow = Vec3.ZERO

        if (state.getValue(FlowingFluid.FALLING)) {
            val neighbour = BlockPos.MutableBlockPos()

            for (direction in Direction.Plane.HORIZONTAL) {
                neighbour.setWithOffset(pos, direction)

                if (isSolidFace(level, fluid, neighbour, direction) ||
                    isSolidFace(level, fluid, neighbour.above(), direction)
                ) {
                    flow = flow.normalize().add(0.0, FALLING_PULL, 0.0)
                    break
                }
            }
        }

        return flow.normalize()
    }

    /** `bu.n`, which is vanilla's `FlowingFluid.isSolidFace` (`FlowingFluid.java:109`). */
    private fun isSolidFace(level: BlockGetter, fluid: Fluid, pos: BlockPos, direction: Direction): Boolean {
        val blockState = level.getBlockState(pos)

        return when {
            level.getFluidState(pos).type.isSame(fluid) -> false
            direction == Direction.UP -> true
            blockState.block is IceBlock -> false
            else -> blockState.isFaceSturdy(level, pos, direction)
        }
    }

    /** `add(0.0, -6.0, 0.0)` in both `bu.i` and `FlowingFluid.getFlow`. */
    private const val FALLING_PULL = -6.0

}
