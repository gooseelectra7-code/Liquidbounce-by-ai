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
package net.ccbluex.liquidbounce.features.module.modules.movement.elytrascanner

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerMoveEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.ccbluex.liquidbounce.utils.block.isBed
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.markAsError
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.client.regular
import net.ccbluex.liquidbounce.utils.collection.blockSortedSetOf
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.toVec3f
import net.ccbluex.liquidbounce.utils.raytracing.clip
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureStart
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * ElytraGridScanner module
 *
 * Automatically flies an elytra in a configurable square grid, scanning for
 * player bases and traces while avoiding collisions and staying low.
 */
@Suppress("TooManyFunctions")
object ModuleElytraGridScanner : ClientModule("ElytraGridScanner", ModuleCategories.MOVEMENT) {

    private const val NOTIFICATION_COOLDOWN_MS = 3000L
    private const val AVOIDANCE_UP_PITCH = 0.5
    private const val TERRAIN_SAMPLE_RADIUS = 3
    private const val TERRAIN_SAMPLE_STEP = 2

    private val centerX by int("CenterX", 0, -30000000..30000000)
    private val centerZ by int("CenterZ", 0, -30000000..30000000)
    private val squareSize by int("SquareSize", 100, 10..2000, "blocks")
    private val lineSpacing by int("LineSpacing", 32, 8..128, "blocks")
    private val waypointDistance by int("WaypointDistance", 16, 4..64, "blocks")

    private val minAltitude by float("MinAltitude", 80f, 30f..320f)
    private val maxAltitude by float("MaxAltitude", 200f, 100f..320f)
    private val terrainClearance by float("TerrainClearance", 25f, 5f..100f, "blocks")

    private val collisionLookahead by float("CollisionLookahead", 40f, 10f..80f, "blocks")
    private val collisionAvoidAngle by float("CollisionAvoidAngle", 30f, 5f..90f, "degrees")

    private val horizontalSpeed by float("HorizontalSpeed", 1.2f, 0.5f..3.0f, "b/t")
    private val verticalSpeed by float("VerticalSpeed", 0.6f, 0.1f..2.0f, "b/t")
    private val waypointReach by float("WaypointReach", 8f, 2f..20f, "blocks")

    private val targetBlocks by blocks("TargetBlocks", defaultTargetBlocks())

    private val ignoreVanillaStructures by boolean("IgnoreVanillaStructures", true)
    private val detectSpawners by boolean("DetectSpawners", true)
    private val detectObfuscatedDungeons by boolean("DetectObfuscatedDungeons", true)

    private val modes = choices("Mode", ElytraFlyMode, arrayOf(ElytraFlyMode, FireworkMode))

    private val waypoints = mutableListOf<Vec3>()
    private var waypointIndex = 0
    private var scanCompleted = false

    private val foundTargets = ConcurrentHashMap<BlockPos, Block>()
    private val announcedTargets = ConcurrentHashMap.newKeySet<BlockPos>()
    private var lastNotificationTime = 0L

    private var currentDirection = Vec3.ZERO
    private var currentVerticalVelocity = 0.0

    private var serverChunkRadius = mc.options.renderDistance().get()

    private val structureCache = ConcurrentHashMap<ChunkPos, List<BoundingBox>>()

    override fun onEnabled() {
        if (!hasElytra()) {
            chat(markAsError(message("noElytra")))
            enabled = false
            return
        }

        rebuildWaypoints()
        waypointIndex = 0
        scanCompleted = false
        foundTargets.clear()
        announcedTargets.clear()
        structureCache.clear()
        currentDirection = Vec3.ZERO
        currentVerticalVelocity = 0.0
        serverChunkRadius = mc.options.renderDistance().get()
        modes.activeMode.onEnableMode()
        ChunkScanner.subscribe(Scanner)
        chat(regular(message("enabledScan", squareSize, centerX, centerZ)))
    }

    override fun onDisabled() {
        ChunkScanner.unsubscribe(Scanner)
        structureCache.clear()
        announcedTargets.clear()
        currentDirection = Vec3.ZERO
        currentVerticalVelocity = 0.0
        modes.activeMode.onDisableMode()
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != net.ccbluex.liquidbounce.event.events.TransferOrigin.INCOMING) {
            return@handler
        }

        val packet = event.packet
        if (packet is ClientboundLoginPacket) {
            serverChunkRadius = packet.chunkRadius()
        }
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        if (!hasElytra()) {
            chat(markAsError(message("noElytraBroken")))
            enabled = false
            return@tickHandler
        }

        if (!player.isFallFlying) {
            handleTakeoff()
            return@tickHandler
        }

        updateFlightTarget()
        modes.activeMode.onTickMode()
    }

    @Suppress("unused")
    private val moveHandler = handler<PlayerMoveEvent> { event ->
        if (!player.isFallFlying) {
            return@handler
        }

        modes.activeMode.onMoveMode(event)
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        renderEnvironmentForWorld(event.matrixStack) {
            renderScanArea()
            renderRoute()
            renderFoundTargets()
        }
    }

    private fun handleTakeoff() {
        when {
            player.onGround() -> player.jumpFromGround()
            player.deltaMovement.y != 0.0 -> {
                player.startFallFlying()
                network.send(
                    ServerboundPlayerCommandPacket(
                        player,
                        ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                    )
                )
            }
        }
    }

    private fun updateFlightTarget() {
        if (scanCompleted) {
            hoverAtLastWaypoint()
            return
        }

        val targetPosition = computeTargetPosition()
        val horizontalDelta = targetPosition.subtract(player.position())
        horizontalDelta.y = 0.0

        val baseDirection = when {
            horizontalDelta.lengthSqr() < 1.0e-8 -> {
                val look = player.lookAngle
                Vec3(look.x, 0.0, look.z).normalize()
            }
            else -> horizontalDelta.normalize()
        }

        val avoidanceDirection = findAvoidanceDirection(baseDirection)
        currentDirection = (avoidanceDirection ?: baseDirection).normalize()

        val desiredAltitude = getDesiredAltitude(player.x.toInt(), player.z.toInt())
        val verticalDiff = desiredAltitude - player.y
        currentVerticalVelocity = verticalDiff.coerceIn(-verticalSpeed.toDouble(), verticalSpeed.toDouble())

        val lookTarget = player.position()
            .add(currentDirection)
            .add(0.0, currentVerticalVelocity, 0.0)
        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = Rotation.lookingAt(lookTarget, player.eyePosition),
                entity = null,
                processors = emptyList(),
                ticksUntilReset = 2,
                resetThreshold = 1f,
                considerInventory = true,
                movementCorrection = MovementCorrection.STRICT
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_3,
            provider = this
        )
    }

    private fun hoverAtLastWaypoint() {
        val waypoint = waypoints[waypointIndex]
        val desiredAltitude = getDesiredAltitude(waypoint.x.toInt(), waypoint.z.toInt())
        val verticalDiff = desiredAltitude - player.y
        currentVerticalVelocity = verticalDiff.coerceIn(-verticalSpeed.toDouble(), verticalSpeed.toDouble())

        currentDirection = Vec3.ZERO
        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = Rotation(player.yRot, 0f),
                entity = null,
                processors = emptyList(),
                ticksUntilReset = 2,
                resetThreshold = 1f,
                considerInventory = true,
                movementCorrection = MovementCorrection.STRICT
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_3,
            provider = this
        )
    }

    private fun computeTargetPosition(): Vec3 {
        pruneWaypointsByRenderDistance()

        if (waypoints.isEmpty()) {
            rebuildWaypoints()
        }
        if (waypoints.isEmpty()) {
            return player.position()
        }

        while (waypointIndex < waypoints.lastIndex) {
            val waypoint = waypoints[waypointIndex]
            if (hypot(player.x - waypoint.x, player.z - waypoint.z) >= waypointReach) {
                break
            }
            waypointIndex++
        }

        if (waypointIndex == waypoints.lastIndex) {
            val lastWaypoint = waypoints.last()
            if (hypot(player.x - lastWaypoint.x, player.z - lastWaypoint.z) < waypointReach) {
                scanCompleted = true
                chat(regular(message("scanComplete", foundTargets.size)))
                return player.position()
            }
        }

        val waypoint = waypoints[waypointIndex]
        return Vec3(
            waypoint.x,
            getDesiredAltitude(waypoint.x.toInt(), waypoint.z.toInt()),
            waypoint.z
        )
    }

    private fun pruneWaypointsByRenderDistance() {
        if (waypoints.isEmpty() || waypointIndex >= waypoints.size) {
            return
        }

        val radiusChunks = max(serverChunkRadius - 2, 4)
        val radiusBlocks = radiusChunks * 16.0
        val playerX = player.x
        val playerZ = player.z

        while (waypointIndex < waypoints.lastIndex) {
            val waypoint = waypoints[waypointIndex]
            val dx = abs(playerX - waypoint.x)
            val dz = abs(playerZ - waypoint.z)
            if (dx <= radiusBlocks || dz <= radiusBlocks) {
                break
            }
            waypointIndex++
        }
    }

    private fun findAvoidanceDirection(baseDirection: Vec3): Vec3? {
        val start = player.eyePosition
        val lookahead = collisionLookahead.toDouble()
        val scanSteps = 5

        if (isDirectionClear(start, baseDirection, lookahead)) {
            return null
        }

        val candidateDirections = buildList {
            add(rotateHorizontal(baseDirection, collisionAvoidAngle))
            add(rotateHorizontal(baseDirection, -collisionAvoidAngle))
            add(Vec3(baseDirection.x, AVOIDANCE_UP_PITCH, baseDirection.z).normalize())
            for (i in 1..scanSteps) {
                val angle = collisionAvoidAngle + (collisionAvoidAngle * i / scanSteps)
                add(rotateHorizontal(baseDirection, angle))
                add(rotateHorizontal(baseDirection, -angle))
            }
        }

        return candidateDirections.firstOrNull { isDirectionClear(start, it, lookahead) }
    }

    private fun isDirectionClear(start: Vec3, direction: Vec3): Boolean {
        val lookahead = collisionLookahead.toDouble()
        return isDirectionClear(start, direction, lookahead)
    }

    private fun isDirectionClear(start: Vec3, direction: Vec3, lookahead: Double): Boolean {
        val end = start.add(direction.scale(lookahead))
        return isPathClear(start, end)
    }

    private fun isPathClear(start: Vec3, end: Vec3): Boolean {
        return world.clip(
            start,
            end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            player
        ).type == HitResult.Type.MISS
    }

    private fun rotateHorizontal(direction: Vec3, degrees: Float): Vec3 {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians)
        val sin = sin(radians)
        return Vec3(
            direction.x * cos - direction.z * sin,
            direction.y,
            direction.x * sin + direction.z * cos
        )
    }

    private fun rebuildWaypoints() {
        waypoints.clear()
        structureCache.clear()
        val half = squareSize / 2
        val minX = centerX - half
        val maxX = centerX + half
        val minZ = centerZ - half
        val maxZ = centerZ + half

        var x = minX
        var movingPositiveZ = true
        while (true) {
            addLineWaypoints(x, minZ, maxZ, movingPositiveZ)

            if (x == maxX) {
                break
            }
            x = (x + lineSpacing).coerceAtMost(maxX)
            movingPositiveZ = !movingPositiveZ
        }
    }

    private fun addLineWaypoints(x: Int, minZ: Int, maxZ: Int, movingPositiveZ: Boolean) {
        val zStart = if (movingPositiveZ) minZ else maxZ
        val zEnd = if (movingPositiveZ) maxZ else minZ
        val step = if (movingPositiveZ) waypointDistance else -waypointDistance

        var z = zStart
        while (if (movingPositiveZ) z <= zEnd else z >= zEnd) {
            waypoints.add(Vec3(x + 0.5, 0.0, z + 0.5))
            if (z == zEnd) {
                break
            }
            val nextZ = z + step
            z = when {
                movingPositiveZ && nextZ > zEnd -> zEnd
                !movingPositiveZ && nextZ < zEnd -> zEnd
                else -> nextZ
            }
        }
    }

    private fun getDesiredAltitude(x: Int, z: Int): Double {
        val terrainHeight = getMaxTerrainHeightInRadius(x, z, TERRAIN_SAMPLE_RADIUS)
        return (terrainHeight + terrainClearance)
            .toDouble()
            .coerceIn(minAltitude.toDouble(), maxAltitude.toDouble())
    }

    private fun getMaxTerrainHeightInRadius(x: Int, z: Int, radius: Int): Int {
        var maxHeight = Int.MIN_VALUE
        var samples = 0
        for (dx in -radius..radius step TERRAIN_SAMPLE_STEP) {
            for (dz in -radius..radius step TERRAIN_SAMPLE_STEP) {
                if (dx == 0 && dz == 0) {
                    continue
                }
                val height = getTerrainHeight(x + dx, z + dz)
                if (height > maxHeight) {
                    maxHeight = height
                }
                samples++
            }
        }

        val centerHeight = getTerrainHeight(x, z)
        return if (samples == 0) {
            centerHeight
        } else {
            max(centerHeight, maxHeight)
        }
    }

    private fun getTerrainHeight(x: Int, z: Int): Int {
        val chunk = world.getChunk(x shr 4, z shr 4)
        if (chunk.isEmpty) {
            return minAltitude.toInt()
        }

        val mutable = BlockPos.MutableBlockPos(x, chunk.height - 1, z)
        while (mutable.y > chunk.minY) {
            if (!chunk.getBlockState(mutable).isAir) {
                return mutable.y
            }
            mutable.y--
        }
        return chunk.minY
    }

    private fun hasElytra(): Boolean {
        if (player.abilities.instabuild || player.hasEffect(MobEffects.LEVITATION)) {
            return false
        }
        val chestSlot = player.getItemBySlot(EquipmentSlot.CHEST)
        return chestSlot.item == Items.ELYTRA && !chestSlot.nextDamageWillBreak()
    }

    private fun isTargetBlock(state: BlockState): Boolean {
        if (state.block in targetBlocks || state.isBed) {
            return true
        }

        if (detectSpawners && state.block == Blocks.SPAWNER) {
            return true
        }

        return detectObfuscatedDungeons && looksLikeDungeonRoom(state)
    }

    private fun looksLikeDungeonRoom(state: BlockState): Boolean {
        val block = state.block
        return block == Blocks.MOSSY_COBBLESTONE || block == Blocks.COBBLESTONE
    }

    private fun findDungeonCenter(pos: BlockPos, markerBlock: Block): BlockPos? {
        val searchRadius = 3
        val mutable = BlockPos.MutableBlockPos()
        var matchCount = 0
        var sumX = 0
        var sumY = 0
        var sumZ = 0

        for (dx in -searchRadius..searchRadius) {
            for (dy in -searchRadius..searchRadius) {
                for (dz in -searchRadius..searchRadius) {
                    mutable.set(pos.x + dx, pos.y + dy, pos.z + dz)
                    val state = world.getBlockState(mutable)
                    if (state.block == markerBlock) {
                        matchCount++
                        sumX += mutable.x
                        sumY += mutable.y
                        sumZ += mutable.z
                    }
                }
            }
        }

        if (matchCount < 2) {
            return null
        }

        return BlockPos(sumX / matchCount, sumY / matchCount, sumZ / matchCount)
    }

    private fun notifyFoundTarget(pos: BlockPos, block: Block) {
        if (!announcedTargets.add(pos)) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < NOTIFICATION_COOLDOWN_MS) {
            return
        }
        lastNotificationTime = now
        notification(
            name,
            message("foundTargetAt", block.name, pos.x, pos.y, pos.z),
            NotificationEvent.Severity.INFO
        )
        chat(regular(message("foundTargetAt", block.name, pos.x, pos.y, pos.z)))
    }

    private fun isInsideVanillaStructure(pos: BlockPos): Boolean {
        if (!ignoreVanillaStructures) {
            return false
        }

        val chunkPos = ChunkPos(pos.x shr 4, pos.z shr 4)
        val cached = structureCache[chunkPos]
        if (cached != null) {
            return cached.any { it.isInside(pos.x, pos.y, pos.z) }
        }

        val serverLevel = world as? ServerLevel
        val boundingBoxes = if (serverLevel != null) {
            collectStructureBoundingBoxes(serverLevel, chunkPos)
        } else {
            collectClientStructureBoundingBoxes(chunkPos)
        }

        structureCache[chunkPos] = boundingBoxes
        return boundingBoxes.any { it.isInside(pos.x, pos.y, pos.z) }
    }

    private fun collectStructureBoundingBoxes(serverLevel: ServerLevel, chunkPos: ChunkPos): List<BoundingBox> {
        val manager = serverLevel.structureManager()
        val registry = serverLevel.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        val structures = VANILLA_STRUCTURE_KEYS.mapNotNull { key ->
            registry.getValue(key)?.let { structure ->
                manager.getStructureAt(posAtChunk(chunkPos), structure)
            }
        }
        return structures.filter { it.isValid }.map { it.boundingBox }
    }

    private fun collectClientStructureBoundingBoxes(chunkPos: ChunkPos): List<BoundingBox> {
        val chunk = world.getChunk(chunkPos.x, chunkPos.z)
        val registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        return VANILLA_STRUCTURE_KEYS.mapNotNull { key ->
            registry.getValue(key)?.let { chunk.getStartForStructure(it) }
        }.filter { it.isValid }.map { it.boundingBox }
    }

    private fun posAtChunk(chunkPos: ChunkPos): BlockPos {
        return BlockPos(chunkPos.x * 16 + 8, 64, chunkPos.z * 16 + 8)
    }

    private fun WorldRenderEnvironment.renderScanArea() {
        val half = squareSize / 2.0
        val box = AABB(
            centerX - half, minAltitude.toDouble(), centerZ - half,
            centerX + half, minAltitude.toDouble() + 1.0, centerZ + half
        )
        drawBox(
            box,
            faceColor = Color4b(0, 255, 255, 30),
            outlineColor = Color4b(0, 255, 255, 120)
        )
    }

    private fun WorldRenderEnvironment.renderRoute() {
        if (waypoints.size < 2) {
            return
        }
        val positions = waypoints.map { waypoint ->
            val y = getDesiredAltitude(waypoint.x.toInt(), waypoint.z.toInt())
            relativeToCamera(Vec3(waypoint.x, y, waypoint.z)).toVec3f()
        }.toTypedArray()
        drawLineStrip(Color4b(0, 255, 0, 200).argb, *positions)
    }

    private fun WorldRenderEnvironment.renderFoundTargets() {
        if (foundTargets.isEmpty()) {
            return
        }
        for ((pos, block) in foundTargets) {
            val color = if (block.defaultBlockState().isBed) {
                Color4b(255, 0, 0, 100)
            } else {
                Color4b(255, 255, 0, 100)
            }
            withPositionRelativeToCamera(pos.center) {
                drawBox(
                    AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
                    faceColor = color,
                    outlineColor = color.with(a = 200)
                )
            }
        }
    }

    private object Scanner : ChunkScanner.BlockChangeSubscriber {
        override fun recordBlock(pos: BlockPos, state: BlockState, cleared: Boolean) {
            if (!isTargetBlock(state)) {
                return
            }

            val immutablePos = pos.immutable()
            if (isInsideVanillaStructure(immutablePos)) {
                return
            }

            if (state.block == Blocks.SPAWNER || looksLikeDungeonRoom(state)) {
                val dungeonCenter = findDungeonCenter(immutablePos, state.block)
                if (dungeonCenter != null && foundTargets.putIfAbsent(dungeonCenter, state.block) == null) {
                    notifyFoundTarget(dungeonCenter, state.block)
                }
                return
            }

            if (foundTargets.putIfAbsent(immutablePos, state.block) == null) {
                notifyFoundTarget(immutablePos, state.block)
            }
        }

        override fun chunkUpdate(chunk: LevelChunk) {
            // Tracked via recordBlock on chunk load and block updates.
        }

        override fun clearChunk(pos: ChunkPos) {
            foundTargets.keys.removeIf { ChunkPos.containing(it) == pos }
            announcedTargets.removeIf { ChunkPos.containing(it) == pos }
        }

        override fun clearAllChunks() {
            foundTargets.clear()
            announcedTargets.clear()
        }
    }

    /**
     * Base mode class for the scanner flight behavior.
     */
    abstract class Mode(name: String) : net.ccbluex.liquidbounce.config.types.group.Mode(name) {
        abstract override val parent: ModeValueGroup<Mode>
        abstract fun onEnableMode()
        abstract fun onDisableMode()
        abstract fun onTickMode()
        abstract fun onMoveMode(event: PlayerMoveEvent)
    }

    /**
     * Standard elytra flight mode that directly sets movement velocity.
     */
    private object ElytraFlyMode : Mode("ElytraFly") {
        override val parent: ModeValueGroup<Mode>
            get() = modes

        override fun onEnableMode() = Unit
        override fun onDisableMode() = Unit
        override fun onTickMode() = Unit
        override fun onMoveMode(event: PlayerMoveEvent) {
            if (currentDirection.lengthSqr() >= 1.0e-8) {
                event.movement = Vec3(
                    currentDirection.x * horizontalSpeed.toDouble(),
                    currentVerticalVelocity,
                    currentDirection.z * horizontalSpeed.toDouble()
                )
            } else if (scanCompleted) {
                event.movement = Vec3(event.movement.x, currentVerticalVelocity, event.movement.z)
            }
        }
    }

    /**
     * Legit firework mode: uses actual fireworks for propulsion like ElytraTarget.
     */
    private object FireworkMode : Mode("Firework") {

        private object ConsiderInventory : ToggleableValueGroup(this, "ConsiderInventory", enabled = false) {
            val constraints = tree(PlayerInventoryConstraints())
        }

        init {
            tree(ConsiderInventory)
        }

        private val cooldown by intRange("Cooldown", 20..20, 0..300, "ticks")

        private const val MILLISECONDS_PER_TICK = 50

        private var skipTicks = 0

        override val parent: ModeValueGroup<Mode>
            get() = modes

        override fun onEnableMode() {
            skipTicks = 0
        }

        override fun onDisableMode() {
            skipTicks = 0
        }

        override fun onTickMode() {
            if (skipTicks > 0) {
                skipTicks--
            }
        }

        @Suppress("unused")
        private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
            if (skipTicks > 0 || !shouldUseFirework()) {
                return@handler
            }

            val slotsToSearch = if (ConsiderInventory.enabled) {
                Slots.OffhandWithHotbar + Slots.Inventory
            } else {
                Slots.OffhandWithHotbar
            }
            val fireworkSlot = slotsToSearch.findSlot(Items.FIREWORK_ROCKET) ?: return@handler

            if (fireworkSlot is HotbarItemSlot) {
                useHotbarSlotOrOffhand(fireworkSlot)
            } else {
                val targetSlot = if (HotbarItemSlot.OFFHAND.canBeSwapTarget) {
                    HotbarItemSlot.OFFHAND
                } else {
                    HotbarItemSlot(player.inventory.selectedSlot)
                }

                val actions = listOf<InventoryAction>(
                    InventoryAction.Click.performSwap(from = fireworkSlot, to = targetSlot),
                    InventoryAction.UseItem(targetSlot, ModuleElytraGridScanner),
                    InventoryAction.Click.performSwap(from = fireworkSlot, to = targetSlot),
                )
                event.schedule(ConsiderInventory.constraints, actions)
            }

            skipTicks = cooldown.random()
        }

        private fun shouldUseFirework(): Boolean {
            if (!player.isFallFlying || player.isUsingItem) {
                return false
            }

            if (currentDirection.lengthSqr() < 1.0e-8) {
                return false
            }

            return world.entitiesForRendering().none {
                it is FireworkRocketEntity && it.owner == player
            }
        }

        override fun onMoveMode(event: PlayerMoveEvent) {
            // In legit mode we only steer via rotations; physics + fireworks handle movement.
            // We still nudge vertical velocity gently to hold altitude.
            event.movement = event.movement.add(0.0, currentVerticalVelocity, 0.0)
        }
    }
}

private fun defaultTargetBlocks(): SortedSet<Block> = blockSortedSetOf().apply {
    add(Blocks.CHEST)
    add(Blocks.TRAPPED_CHEST)
    add(Blocks.ENDER_CHEST)
    add(Blocks.BARREL)
    add(Blocks.BREWING_STAND)
    add(Blocks.FURNACE)
    add(Blocks.BLAST_FURNACE)
    add(Blocks.SMOKER)
    add(Blocks.DISPENSER)
    add(Blocks.DROPPER)
    add(Blocks.HOPPER)
    add(Blocks.REDSTONE_WIRE)
    add(Blocks.REPEATER)
    add(Blocks.COMPARATOR)
    add(Blocks.REDSTONE_TORCH)
    add(Blocks.REDSTONE_WALL_TORCH)
    add(Blocks.REDSTONE_BLOCK)
    add(Blocks.OBSERVER)
    add(Blocks.PISTON)
    add(Blocks.STICKY_PISTON)
    add(Blocks.NOTE_BLOCK)
    add(Blocks.ENCHANTING_TABLE)
    add(Blocks.ANVIL)
    add(Blocks.BEACON)
    add(Blocks.CRAFTING_TABLE)

    BuiltInRegistries.BLOCK.filterTo(this) { it.defaultBlockState().isBed }
}

private val VANILLA_STRUCTURE_KEYS: List<ResourceKey<Structure>> = listOf(
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("pillager_outpost")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("mineshaft")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("woodland_mansion")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("jungle_temple")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("desert_pyramid")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("igloo")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("shipwreck")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("swamp_hut")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("stronghold")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("ocean_monument")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("ocean_ruin")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("fortress")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("end_city")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("buried_treasure")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("bastion_remnant")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("village")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("ruined_portal")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("ancient_city")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("trail_ruins")),
    ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("trial_chambers"))
)
