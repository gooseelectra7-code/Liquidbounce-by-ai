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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.mine

import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.scan.PlaceCandidate
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Where the obsidian of a [MineTarget] goes in — the same pair `BasePlan.clickPos` / `BasePlan.face` carries,
 * produced by the same reading of `BlockPlaceContext`
 * (`net/minecraft/world/item/context/BlockPlaceContext.java:31-38, 54-61`), and like it in BOTH of vanilla's forms:
 *
 * - [face] non-null: click that face of the NEIGHBOUR [pos], `pos.relative(face)` being the base cell — a
 *   non-replaceable clicked block sends the obsidian to `hit.getBlockPos().relative(hit.getDirection())`.
 * - [face] null: [pos] IS the base cell and the obsidian replaces what stands in it — `replaceClicked` makes
 *   `getClickedPos()` answer the clicked position and the hit direction is read by nobody. This is the only form
 *   available for a base cell every one of whose neighbours is itself replaceable (a snow layer or a fire on open
 *   ground), and it exists here so such a cell is not wrongly judged unreachable.
 *
 * [pos] is guaranteed NOT to be one of the cells the plan breaks. That is the one hard rule the mined variant adds:
 * Grim's `AirLiquidPlace` resyncs a placement made against a block it believes is air, at cancel VL **0**
 * (`checks/impl/scaffolding/AirLiquidPlace.java:58-91`), and a block we just finished breaking IS air in Grim's
 * world. Its only escape hatch (`:67-78`) excuses a clicked position whose recorded modification is younger than
 * two ticks AND caused by `START_DIGGING` or a netty sync — an INSTANT break, which a multi-tick dig never is. For
 * the replace-in-place form that rule is what forbids it on a plan that clears the base cell: the cell would be
 * air by the time the obsidian went in, so `MineSearch.clickFor` only offers it when `CLEAR_BASE` is not set.
 *
 * Nothing here is ever re-clicked. It is the search's PROOF that the obsidian has a way in; the placement itself
 * runs on a later tick through `BasePlaceSearch`, which finds its own click target against the world as it is then.
 */
data class BaseClick(val pos: BlockPos, val face: Direction?)

/**
 * The immutable plan for one mined base: break [cells], then a crystal standing on [base] is worth [candidate].
 *
 * [cells] is ordered TOP-DOWN — `base.above()` before `base` when both are in it — because a cell is far easier to
 * aim at once the block above it is gone, and because a dig must never leave an unsupported column above the
 * pocket (see `MineSearch`, which refuses a plan whose top cell carries a `FallingBlock`). It holds one or two
 * entries: `EndCrystalItem.useOn` inspects exactly the base cell and the cell above it
 * (`net/minecraft/world/item/EndCrystalItem.java:22-57`), so nothing beyond those two ever has to go.
 *
 * [click] is null exactly when [base] already is `Blocks.OBSIDIAN` or `Blocks.BEDROCK` (`EndCrystalItem.java:27`)
 * and therefore survives the dig as a base — the cheapest kind of target, one dig and no placement at all. In every
 * other case [base] ends up air or replaceable and the obsidian has to be clicked in through [click].
 *
 * Nothing here is re-validated after construction: the world moves on, so the consumer must check every cell again
 * on the tick it acts.
 */
data class MineTarget(
    val base: BlockPos,
    val cells: List<BlockPos>,
    val click: BaseClick?,
    val candidate: PlaceCandidate,
)
