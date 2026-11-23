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
package net.ccbluex.liquidbounce.features.module.modules.combat.placement

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.phys.AABB
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/**
 * Base of catlean's eight block-placing modules (`su.catlean._0`): AutoBasePlace, Surround,
 * HoleFiller, SourceFiller, Blocker, AutoWeb, AutoSweetBerries, AntiPhase.
 *
 * Everything a placing module shares lives here — the reach pair, the interaction modes, the pause
 * conditions, the per-tick budget — so a subclass only has to answer two questions: which blocks it
 * places ([blocks]) and which positions it wants filled. [place] does the rest.
 *
 * The budget is worth spelling out, because it is where [PlaceTiming] earns its keep:
 * [blocksPerAction] is how many positions may be filled per action and [delayPerAction] how many
 * ticks to wait afterwards, but under [PlaceTiming.VANILLA] the loop stops after the FIRST success
 * regardless. That is the difference between building a surround the way a player would and
 * building it the way only a client can.
 */
abstract class CatleanPlaceModule(
    name: String,
    category: ModuleCategory
) : ClientModule(name, category) {

    private val timing by enumChoice("Timing", PlaceTiming.VANILLA)
    private val blocksPerAction by int("BlocksPerAction", 6, 2..20)
    private val delayPerAction by int("DelayPerAction", 0, 0..10)

    /**
     * Whether the aim is moved onto the placement.
     *
     * Off means the click is sent at whatever the player happens to be looking at, which every
     * server-side check that compares the two will notice. It exists because on servers that do not
     * check, not moving the aim is strictly better.
     */
    protected val rotate by boolean("Rotate", true)

    /**
     * Send the placement's rotation as its own packet around the click instead of steering the real
     * aim into it.
     *
     * Only meaningful together with [PlaceTiming.INSTANT]: the point is to put the right rotation on
     * the wire for the tick the click lands in, which is only possible when the click is in this
     * tick to begin with.
     */
    protected val grimSilent by boolean("GrimSilent", false)

    protected val placeMode by enumChoice("PlaceMode", PlaceMode.NORMAL)
    protected val interact by enumChoice("Interact", InteractMode.STRICT)
    protected val switchMode by enumChoice("Switch", PlaceSwitchMode.SILENT)

    protected val range by float("Range", 4.0f, 2.0f..10.0f)
    protected val wallRange by float("WallRange", 4.0f, 2.0f..10.0f)

    private val pause = tree(Pause)

    private object Pause : ValueGroup("Pause") {
        val mining by boolean("Mining", false)
        val eating by boolean("Eating", false)
        val inventory by boolean("Inventory", false)
        val blink by boolean("Blink", false)
    }

    private val render by enumChoice("Render", PlaceRender.BOTH)

    /** Ticks still owed to [delayPerAction] before the next action may run. */
    private var actionDelay = 0


    private companion object {
        /**
         * How far the rotation already on the wire may be from the one a placement was computed for.
         *
         * Tight on purpose: Grim's `RotationPlace` ray-traces from the rotation to the block and
         * rejects a placement that does not hit it, so slack here is slack in "am I looking at it".
         * The rotation is instant, so one tick after it is requested it is already on the wire.
         */
        const val AIM_TOLERANCE = 2.0f

        /** How far the aim may be dragged off by something else before the plan is abandoned. */
        const val RESET_THRESHOLD = 2.0f
    }

    /** Which blocks this module places. The first one found in the hotbar wins. */
    protected abstract val blocks: List<Block>

    /**
     * Priority the placement rotation is requested at.
     *
     * The default ties with KillAura and AutoCrystal, which is what every module here has always
     * done — and it is why they yield to each other explicitly instead: at equal priority
     * [net.ccbluex.liquidbounce.utils.client.RequestHandler] settles the tie by queue order, so
     * "who is aiming" is not decided by anything a module can reason about.
     *
     * A subclass whose placement is a short, self-limiting burst can raise it instead. That is
     * strictly better than a yield flag where it applies: the loser keeps its request alive at the
     * lower priority, so the moment the burst stops requesting, the loser's plan is already on top
     * and the aim never passes through a reset. Nothing needs to be told the burst is over.
     */
    protected open val placeRotationPriority: Priority
        get() = Priority.IMPORTANT_FOR_USAGE_2

    /**
     * Conditions under which the module stands down, all of them opt-in.
     *
     * They are settings rather than rules because each is a trade the user owns: pausing while
     * mining keeps a dig from being interrupted but loses the placement; not pausing does the
     * reverse.
     */
    protected fun isPaused(): Boolean = when {
        Pause.mining && mc.hitResult != null && interaction.isDestroying -> true
        Pause.eating && player.isUsingItem -> true
        Pause.inventory && InventoryManager.isInventoryOpenServerSide -> true
        Pause.blink && ModuleBlink.running -> true
        else -> false
    }

    /**
     * Fill [positions], in order, as far as the budget allows. Returns whether anything was placed.
     *
     * `calcGhost` lets the caller declare positions it is about to fill so they count as solid
     * support for the ones after them — that is what makes a multi-block structure placeable in one
     * pass instead of one acknowledgement at a time.
     */
    @Suppress("ReturnCount")
    protected fun place(
        positions: List<BlockPos>,
        blockList: List<Block> = blocks,
        ignore: IgnoreMode = IgnoreMode.ONLY_CRYSTALS,
        calcGhost: List<BlockPos> = emptyList()
    ): Boolean {
        if (actionDelay > 0) {
            actionDelay--
            return false
        }

        if (positions.isEmpty() || isPaused()) {
            return false
        }

        val items: Set<Item> = blockList.mapTo(HashSet()) { it.asItem() }
        val slot = Slots.Hotbar.findSlot(items)?.hotbarIndex ?: return false

        var budget = blocksPerAction
        var placedAny = false

        for (pos in positions) {
            if (budget <= 0) {
                break
            }

            if (!placeOne(pos, slot, ignore, calcGhost)) {
                continue
            }

            placedAny = true
            budget--
            actionDelay = delayPerAction

            // Vanilla pacing is one placement per tick; the budget only matters to Instant.
            if (timing != PlaceTiming.INSTANT) {
                break
            }
        }

        return placedAny
    }

    @Suppress("ReturnCount")
    private fun placeOne(
        pos: BlockPos,
        slot: Int,
        ignore: IgnoreMode,
        calcGhost: List<BlockPos>
    ): Boolean {
        val placement = CatleanBlockPlacer.findPlacement(
            pos = pos,
            slot = slot,
            interact = interact,
            mode = placeMode,
            ignore = ignore,
            range = range,
            wallRange = wallRange,
            switchMode = switchMode,
            calcGhost = calcGhost
        ) ?: return false

        // Only once the position is known to be placeable. Attacking first meant a cell that could
        // never be filled — out of reach, no clickable support — still cost the crystal in front of
        // it an attack every tick, forever.
        if (ignore == IgnoreMode.ONLY_CRYSTALS && !breakCrystalAt(pos)) {
            return false
        }

        if (!rotate) {
            return send(placement)
        }

        // Instant and server-side, as catlean's is: the outgoing rotation changes, the player's own
        // aim does not, and nothing is spread over ticks. See the note on AutoCrystal's `aimAt` for
        // why the fork's AngleSmooth was fitted here and then removed again.
        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = placement.rotation,
                ticksUntilReset = 1,
                resetThreshold = RESET_THRESHOLD,
                considerInventory = Pause.inventory,
                movementCorrection = MovementCorrection.SILENT
            ),
            placeRotationPriority,
            this
        )

        // The click used to be handed to the rotation target's `whenReached`, which fires only for
        // the request that WINS the priority queue. Every module here asks at the same priority, and
        // ties are settled by registration order — so AutoBasePlace, registered after AutoCrystal,
        // never won and never placed anything, while `place()` cheerfully reported success. Sending
        // it directly removes the race entirely.
        //
        // What is kept is the guard that made the rotation meaningful: the interaction goes out
        // before this tick's movement packet, so the server validates it against the rotation from
        // the previous one, and it is not sent until that rotation is close enough to be explained
        // by a hand.
        if (RotationManager.serverRotation.angleTo(placement.rotation) > AIM_TOLERANCE) {
            return false
        }

        return send(placement)
    }

    /**
     * Send the placement, unless something already put an entity interaction in this tick.
     *
     * The guard is shared across every module that sends one, because the server sees a single
     * packet stream: AutoCrystal attacking and this module placing in the same tick is the
     * block-and-entity pair `MultiActionsF` looks for, with neither module doing anything wrong by
     * itself.
     */
    private fun send(placement: CatleanBlockPlacer.Placement): Boolean {
        // One interaction per tick under strict, across every module sharing the guard. AutoCrystal
        // attacking OR placing this tick blocks this placement — a place beside an attack is
        // `MultiActionsF`/`PacketOrderJ`, and two places (this base plus the aura's crystal) are
        // `MultiPlace`. Off strict `actedThisTick` is always false and nothing here changes.
        if (CrystalTickGuard.actedThisTick) {
            return false
        }

        if (placement.action() != CatleanBlockPlacer.PlacementOutcome.PLACED) {
            return false
        }

        CrystalTickGuard.markPlaced()
        return true
    }

    /**
     * Attack a crystal standing where we want to build, and report whether the way is clear NOW.
     *
     * True only when there was nothing there to begin with. When there was, the attack goes out and
     * the placement waits for the next tick — see the comment at the send for why they must not
     * share one.
     */
    private fun breakCrystalAt(pos: BlockPos): Boolean {
        // Matched against the crystal's own base cell rather than its 2x2x2 hitbox: querying the
        // hitbox meant a crystal standing one cell over counted as "in the way" and got attacked for
        // a placement it was nowhere near.
        val crystal = world
            .getEntitiesOfClass(EndCrystal::class.java, AABB(pos).inflate(1.0, 1.0, 1.0)) { it.isAlive }
            .firstOrNull { BlockPos.containing(it.position()) == pos }
            ?: return true

        // The unified one-per-tick guard, not just `placedThisTick`: this is an ENTITY attack, so if
        // anything already acted this tick — the aura's own attack included — a second one here is
        // `MultiInteractA` (and, paired with a place, `MultiActionsF`). Off strict it is always false.
        if (CrystalTickGuard.actedThisTick) {
            return false
        }

        network.send(ServerboundAttackPacket(crystal.id))
        network.send(ServerboundSwingPacket(InteractionHand.MAIN_HAND))
        CrystalTickGuard.markAttacked()

        // And that is the tick. Placing straight afterwards is what the original does, but it puts a
        // block interaction and an entity interaction in one tick window — `MultiActionsF` and
        // `PacketOrderJ` both look for exactly that pair. The cell is free next tick anyway, so the
        // whole cost of waiting is one tick.
        return false
    }

    /** Whether the module draws anything over its positions. */
    protected val isRendering: Boolean
        get() = render != PlaceRender.NONE

    override fun onDisabled() {
        actionDelay = 0
        super.onDisabled()
    }

}
