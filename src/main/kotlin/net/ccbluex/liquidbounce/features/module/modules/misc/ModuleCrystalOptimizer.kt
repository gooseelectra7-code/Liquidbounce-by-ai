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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerInteractEntityEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * CrystalOptimizer module
 *
 * Spams end crystals and detonates them while the use key is held down.
 *
 * Ported from the wyvern client (CrystalOptimizer).
 */
object ModuleCrystalOptimizer : ClientModule("CrystalOptimizer", ModuleCategories.MISC) {

    private val breakDelay by int("BreakDelay", 0, 0..10)
    private val placeDelay by int("PlaceDelay", 0, 0..10)
    private val maxBreakPerTick by int("MaxBreakPerTick", 2, 1..5)
    private val wallBypass by boolean("WallBypass", true)

    /**
     * How a spawned crystal is allowed to be detonated:
     *
     * - [ExplodeMode.RAYTRACE]: the crystal only explodes when the player's ACTUAL crosshair
     *   ray (real client rotation - exactly what the server already receives) intersects the
     *   crystal hitbox. [throughBlocks] optionally drops the line-of-sight requirement.
     * - [ExplodeMode.ROTATE]: silently rotates ([rotations]) to the point of the crystal
     *   hitbox nearest to the eyes and only attacks once the SERVER-side rotation ray
     *   actually intersects the hitbox.
     *
     * GRIM-SAFETY: in both modes the attack only fires when the rotation the server knows
     * about (client rotation in Raytrace, [RotationManager.serverRotation] in Rotate) hits
     * the crystal hitbox within vanilla entity interaction range - consistent with Grim's
     * hitbox/reach analysis. Rotations go through [RotationManager] (wrap/modulo and
     * movement correction handled centrally); the attack itself uses the sanctioned
     * [attackEntity] util, so the packet order stays the vanilla interact -> swing. No
     * extra packets are sent and the placement path is untouched.
     */
    private val explodeMode by enumChoice("ExplodeMode", ExplodeMode.RAYTRACE)

    /** Raytrace mode only: also explode crystals the crosshair reaches through blocks. */
    private val throughBlocks by boolean("ThroughBlocks", false)

    /** Rotate mode only: how the silent rotation to the crystal hitbox behaves. */
    private val rotations = tree(RotationsValueGroup(this))

    private val obsidianEsp by boolean("ObsidianESP", true)
    private val espRange by int("ESPRange", 10, 5..30)

    @Suppress("unused")
    private enum class ExplodeMode(override val tag: String) : Tagged {
        RAYTRACE("Raytrace"),
        ROTATE("Rotate"),
    }

    private var breakTimer = 0
    private var placeTimer = 0

    override fun onEnabled() {
        breakTimer = 0
        placeTimer = 0
        super.onEnabled()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!mc.options.keyUse.isDown) {
            return@handler
        }

        val isMainHand = player.mainHandItem.item == Items.END_CRYSTAL
        val isOffHand = player.offhandItem.item == Items.END_CRYSTAL
        if (!isMainHand && !isOffHand) {
            return@handler
        }

        var hitResult: BlockHitResult? = null

        if (wallBypass) {
            hitResult = customRayTrace(5.0)
        }

        if (hitResult == null) {
            val standardHit = mc.hitResult
            if (standardHit is BlockHitResult && standardHit.type == HitResult.Type.BLOCK) {
                hitResult = standardHit
            }
        }

        if (hitResult == null) {
            return@handler
        }

        val blockPos = hitResult.blockPos
        val block = world.getBlockState(blockPos).block
        if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) {
            return@handler
        }

        val maxBreaks = maxBreakPerTick

        val crystalPos = blockPos.above()
        val crystalBox = AABB(crystalPos).inflate(0.5)

        if (breakTimer >= breakDelay) {
            val crystals = world.getEntitiesOfClass(EndCrystal::class.java, crystalBox) { it.isAlive }
            crystals.sortBy { it.distanceToSqr(player) }

            val breakHand = if (isOffHand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            var broken = 0

            for (crystal in crystals) {
                if (!canExplode(crystal)) {
                    continue
                }

                attackEntity(crystal, SwingMode.HIDE_BOTH)
                player.swing(breakHand)
                broken++
                if (broken >= maxBreaks) {
                    break
                }
            }

            if (broken > 0) {
                breakTimer = 0
            }
        } else {
            breakTimer++
        }

        if (placeTimer >= placeDelay) {
            if (world.isEmptyBlock(crystalPos) &&
                world.isEmptyBlock(crystalPos.above()) &&
                world.getEntitiesOfClass(EndCrystal::class.java, crystalBox) { it.isAlive }.isEmpty()
            ) {
                val hand = if (isOffHand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
                interaction.useItemOn(player, hand, hitResult)
                player.swing(hand)
                placeTimer = 0
            }
        } else {
            placeTimer++
        }
    }

    /**
     * Kills the stray offhand entity interaction that trips Grim's `PacketOrderD`.
     *
     * While the use key is held the module detonates crystals itself (an ATTACK, which the check
     * exempts), but VANILLA's own held right-click still fires `interactAt` then `interact` on any
     * crystal ENTITY under the crosshair. On 1.26.1+ Grim clears its "saw a main-hand interact" flag
     * after the offhand `INTERACT_AT` and then flags the offhand `INTERACT` that follows. A crystal is
     * not interactable, so cancelling that interaction loses nothing and removes the packet entirely.
     */
    @Suppress("unused")
    private val interactHandler = handler<PlayerInteractEntityEvent> { event ->
        if (event.entity is EndCrystal) {
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!obsidianEsp) {
            return@handler
        }

        val range = espRange
        val playerPos = player.blockPosition()
        val boxColor = Color4b(0, 160, 255, 80)

        renderEnvironmentForWorld(event.matrixStack) {
            for (x in -range..range) {
                for (y in -range..range) {
                    for (z in -range..range) {
                        val pos = playerPos.offset(x, y, z)
                        val block = world.getBlockState(pos).block
                        if (block == Blocks.OBSIDIAN || block == Blocks.BEDROCK) {
                            withPositionRelativeToCamera(Vec3.atLowerCornerOf(pos)) {
                                drawBox(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), boxColor, null)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Mode gate for detonating [crystal]. Returns true only when the rotation the SERVER
     * effectively sees intersects the crystal hitbox within vanilla entity reach:
     *
     * - Raytrace: the player's real client rotation. The ray must pick the crystal as the
     *   NEAREST pickable entity (natural entity occlusion); without [throughBlocks] it also
     *   needs line of sight (through-walls distance 0).
     * - Rotate: the current [RotationManager.serverRotation]. While it does not intersect
     *   yet, a silent rotation towards the nearest hitbox point is requested and the attack
     *   is postponed to a later tick (aim first, THEN explode - never an attack packet with
     *   a rotation that could not have hit).
     */
    private fun canExplode(crystal: EndCrystal): Boolean {
        val reach = player.entityInteractionRange()

        return when (explodeMode) {
            ExplodeMode.RAYTRACE -> {
                val rotation = Rotation(player.yRot, player.xRot)
                val wallsRange = if (throughBlocks) reach else 0.0
                val hit = isLookingAtEntity(player, crystal, rotation, reach, wallsRange)
                hit != null && hit.entity === crystal
            }

            ExplodeMode.ROTATE -> {
                // Grim traces the hitbox from the rotation it RECEIVED - gate on that one.
                val serverHit = isLookingAtEntity(player, crystal, RotationManager.serverRotation, reach, reach)
                if (serverHit != null && serverHit.entity === crystal) {
                    true
                } else {
                    aimAtNearestHitboxPoint(crystal)
                    false
                }
            }
        }
    }

    /**
     * Requests a silent rotation to the point of [crystal]'s hitbox nearest to the eyes
     * (the eye position clamped into the box - shortest possible aim path).
     */
    private fun aimAtNearestHitboxPoint(crystal: EndCrystal) {
        val eyes = player.eyePosition
        val box = crystal.boundingBox
        val nearestPoint = Vec3(
            eyes.x.coerceIn(box.minX, box.maxX),
            eyes.y.coerceIn(box.minY, box.maxY),
            eyes.z.coerceIn(box.minZ, box.maxZ),
        )

        RotationManager.setRotationTarget(
            Rotation.lookingAt(nearestPoint, eyes),
            considerInventory = false,
            valueGroup = rotations,
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this,
        )
    }

    private fun customRayTrace(range: Double): BlockHitResult? {
        val start = player.eyePosition
        val dir = player.lookAngle

        var d = 0.0
        while (d < range) {
            val posVec = start.add(dir.scale(d))
            val pos = BlockPos.containing(posVec)
            val block = world.getBlockState(pos).block

            if (block == Blocks.OBSIDIAN || block == Blocks.BEDROCK) {
                return BlockHitResult(posVec, Direction.UP, pos, false)
            }
            d += 0.08
        }

        return null
    }

}
