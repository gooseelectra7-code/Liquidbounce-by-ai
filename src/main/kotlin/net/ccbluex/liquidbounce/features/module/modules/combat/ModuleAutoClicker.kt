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
package net.ccbluex.liquidbounce.features.module.modules.combat

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.ccbluex.fastutil.enumSetOf
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.tickUntil
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.CriticalsSelectionMode
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.clicking.Clicker
import net.ccbluex.liquidbounce.utils.collection.blockSortedSetOf
import net.ccbluex.liquidbounce.utils.collection.itemSortedSetOf
import net.ccbluex.liquidbounce.utils.combat.WallHitBypass
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.isHitBehindWall
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.WeaponType
import net.ccbluex.liquidbounce.utils.kotlin.matchesAny
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.minecraft.client.KeyMapping
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult

/**
 * AutoClicker module
 *
 * Clicks automatically when holding down a mouse button.
 */

object ModuleAutoClicker : ClientModule("AutoClicker", ModuleCategories.COMBAT, aliases = listOf("TriggerBot")) {

    object AttackButton : ToggleableValueGroup(this, "Attack", true) {

        val clicker = tree(Clicker(this, mc.options.keyAttack, simulateAttackKeyDown = true))

        internal val requiresNoInput by boolean("RequiresNoInput", false)
        internal val delayOnBroken by boolean("DelayOnBroken", true)
        private val objectiveType by enumChoice("Objective", ObjectiveType.ANY)
        private val onItemUse by enumChoice("OnItemUse", Use.WAIT)
        private val weapon by multiEnumChoice("Weapon", enumSetOf(WeaponType.ANY), canBeNone = false)
        private val criticalsSelectionMode by enumChoice("Criticals", CriticalsSelectionMode.SMART)
        private val delayPostStopUse by int("DelayPostStopUse", 0, 0..20, "ticks")

        /**
         * Hits a target that sits on the crosshair line but behind a block. Vanilla's crosshair
         * stops at the block, so the target is found by a block-ignoring trace along the player's
         * own rotation within [range], and the strike goes out through [bypass]. A target the
         * vanilla crosshair already reaches is left to the normal key click.
         */
        object ThroughWalls : ToggleableValueGroup(AttackButton, "ThroughWalls", false) {
            private val range by float("Range", 3f, 0.5f..6f, "blocks")
            val bypass = tree(WallHitBypass(this, enabled = true))

            /**
             * The entity to hit through the wall this tick, or null when there is none — also
             * null when the objective is blocks, or the traced entity is in plain sight.
             */
            fun findTarget(): Entity? {
                if (!enabled) {
                    return null
                }

                val accepts: (Entity) -> Boolean = when (objectiveType) {
                    ObjectiveType.ENEMY, ObjectiveType.ANY -> { entity -> entity.shouldBeAttacked() }
                    ObjectiveType.ENTITY -> { entity -> entity is LivingEntity }
                    ObjectiveType.BLOCK -> return null
                }

                val hit = findEntityInCrosshair(range.toDouble(), player.rotation) { entity ->
                    entity != player && !entity.isSpectator && accepts(entity)
                } ?: return null

                return hit.entity.takeIf { isHitBehindWall(hit.location) }
            }
        }

        init {
            tree(ThroughWalls)
        }

        private enum class ObjectiveType(override val tag: String) : Tagged {
            ENEMY("Enemy"),
            ENTITY("Entity"),
            BLOCK("Block"),
            ANY("Any")
        }

        private enum class Use(override val tag: String) : Tagged {
            WAIT("Wait"),
            STOP("Stop"),
            IGNORE("Ignore")
        }

        fun isOnObjective(): Boolean {
            val crosshair = mc.hitResult

            return when (objectiveType) {
                ObjectiveType.ENEMY -> crosshair is EntityHitResult && crosshair.entity.shouldBeAttacked()
                ObjectiveType.ENTITY -> crosshair is EntityHitResult
                ObjectiveType.BLOCK -> crosshair is BlockHitResult
                ObjectiveType.ANY -> true
            }
        }

        /**
         * The living entity currently on the attack objective, but only when the objective is
         * restricted to entities (Enemy/Entity modes). Used e.g. by the target HUD.
         */
        fun crosshairObjectiveEntity(): LivingEntity? {
            if (!running || objectiveType != ObjectiveType.ENEMY && objectiveType != ObjectiveType.ENTITY) {
                return null
            }
            if (objectiveType == ObjectiveType.ENEMY && !isOnObjective()) {
                return null
            }
            return (mc.hitResult as? EntityHitResult)?.entity as? LivingEntity
        }

        fun isWeaponSelected(): Boolean = weapon.matchesAny(player.mainHandItem)

        fun isCriticalHit(entity: Entity): Boolean {
            return criticalsSelectionMode.isCriticalHit(entity)
        }

        suspend fun encounterItemUse(): Boolean {
            return when (onItemUse) {
                Use.WAIT -> {
                    tickUntil { !player.isUsingItem }
                    waitTicks(delayPostStopUse)
                    true
                }

                Use.STOP -> {
                    interaction.releaseUsingItem(player)
                    waitTicks(delayPostStopUse)
                    true
                }

                Use.IGNORE -> false
            }
        }

        @Suppress("unused")
        private val sprintHandler = handler<SprintEvent> { event ->
            if (event.source == SprintEvent.Source.MOVEMENT_TICK || event.source == SprintEvent.Source.INPUT) {
                if (!attack || !isOnObjective() || !isWeaponSelected()) {
                    return@handler
                }

                val target = mc.hitResult as? EntityHitResult ?: return@handler
                if (criticalsSelectionMode.shouldStopSprinting(clicker, target.entity)) {
                    event.sprint = false
                }
            }
        }

    }

    object UseButton : ToggleableValueGroup(this, "Use", false) {
        val clicker = tree(Clicker(this, mc.options.keyUse, null))
        internal val holdingItemsForIgnore by items(
            "HoldingItemsForIgnore",
            default = itemSortedSetOf(
                Items.WATER_BUCKET,
                Items.LAVA_BUCKET,
                Items.ENDER_PEARL,
                Items.ENDER_EYE,
                Items.PLAYER_HEAD,
            ),
        )
        internal val blocksForIgnore by blocks(
            "BlocksForIgnore",
            default = BuiltInRegistries.BLOCK.filterTo(blockSortedSetOf()) {
                it is DoorBlock || it is FenceGateBlock || it is TrapDoorBlock
            },
        )
        internal val delayStart by boolean("DelayStart", false)
        internal val onlyBlock by boolean("OnlyBlock", false)
        internal val requiresNoInput by boolean("RequiresNoInput", false)

        internal var needToWait = true
    }

    private val SPECIAL_ITEMS_FOR_IGNORE = ReferenceOpenHashSet.of(
        Items.RED_BED,
        Items.PLAYER_HEAD,
        Items.COMPASS,
        Items.EMERALD,
        Items.LAPIS_LAZULI,
        Items.GREEN_DYE,
        Items.GRAY_DYE,
        Items.PINK_DYE,
        Items.SLIME_BALL,
    )

    init {
        tree(AttackButton)
        tree(UseButton)
    }

    val attack: Boolean
        get() = mc.options.keyAttack.isPressedOnAny || AttackButton.requiresNoInput

    val use: Boolean
        get() = mc.options.keyUse.isPressedOnAny || UseButton.requiresNoInput

    @Volatile
    private var lastFinishBreak = 0L

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet is ServerboundPlayerActionPacket
            && packet.action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
        ) {
            lastFinishBreak = System.currentTimeMillis()
        }
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        AttackButton.run {
            if (!enabled || !attack || !isWeaponSelected()) {
                return@run
            }

            // A wall target replaces the crosshair objective: the vanilla crosshair is sitting
            // on the block in front of the target, so `isOnObjective` would never pass for it.
            val wallTarget = AttackButton.ThroughWalls.findTarget()
            if (wallTarget == null && !isOnObjective()) {
                return@run
            }

            // Check if the player is breaking a block, if so, return
            if (interaction.isDestroying) {
                return@run
            }

            if ((System.currentTimeMillis() - lastFinishBreak < 300L) && delayOnBroken) {
                return@run
            }

            val crosshairTarget = mc.hitResult
            val attackedEntity = wallTarget ?: (crosshairTarget as? EntityHitResult)?.entity
            if (attackedEntity != null) {
                ModuleAutoWeapon.onTarget(attackedEntity)

                if (!isCriticalHit(attackedEntity)) {
                    return@run
                }
            }

            if (player.isUsingItem) {
                val encounterItemUse = encounterItemUse()

                if (encounterItemUse) {
                    return@tickHandler
                }
            }

            clicker.click {
                if (wallTarget != null) {
                    // The key click would only swing at the block, so the hit goes on the wire
                    // directly, aimed with the rotation the player actually has.
                    AttackButton.ThroughWalls.bypass.attack(wallTarget, player.rotation) {
                        attackEntity(wallTarget, SwingMode.DO_NOT_HIDE)
                    }
                } else {
                    KeyMapping.click(mc.options.keyAttack.key)
                }
                true
            }
        }

        UseButton.run {
            if (!enabled) return@run

            if (!use) {
                needToWait = true
                return@run
            }

            val mainHandStack = player.mainHandItem
            val offHandStack = player.offhandItem
            if (mainHandStack.item in SPECIAL_ITEMS_FOR_IGNORE && mainHandStack.customName != null) {
                return@run
            }

            if (mainHandStack.item in holdingItemsForIgnore || offHandStack.item in holdingItemsForIgnore) {
                return@run
            }

            if (onlyBlock && mainHandStack.item !is BlockItem && offHandStack.item !is BlockItem) {
                return@run
            }

            val crosshairTarget = mc.hitResult
            if (crosshairTarget is BlockHitResult) {
                val blockState = mc.level?.getBlockState(crosshairTarget.blockPos)
                if (blockState?.block in blocksForIgnore) {
                    return@run
                }
            }

            if (delayStart && needToWait) {
                needToWait = false
                waitTicks(2)
                return@run
            }

            clicker.click {
                KeyMapping.click(mc.options.keyUse.key)
                true
            }
        }
    }
}
