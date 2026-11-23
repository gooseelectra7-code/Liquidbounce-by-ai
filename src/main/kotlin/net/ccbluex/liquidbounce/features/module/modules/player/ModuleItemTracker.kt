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
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.nameprotect.ModuleNameProtect
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleItemRadius
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.ServerFlag
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcItems
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcTheme
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.TrackedItem
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.font.system.SystemFont
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.gui.systemdlc.SystemDraw
import net.ccbluex.liquidbounce.render.gui.systemdlc.drawPlateItem
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ItemTracker module
 *
 * Combat-intel port of the SystemDLC `client.module.player.ItemTracker`
 * (`.../system-source/src/main/java/client/module/player/ItemTracker.java`, 982 L). It reports what
 * OTHER players are doing and draws countdown plaques over server-signalled traps.
 *
 * ## What is ported
 * 1. **Toast notifications** (routed to our [notification] API — [ClientChat.notification], the
 *    `NotificationEvent` toast every module raises): crossbow shot (charged->uncharged while still
 *    holding a crossbow, `ItemTracker.java:203-215`), ender-pearl throw (`:261-286`), item-use
 *    completion (`:377-419` — a use that ran to within 2 ticks of its full duration), swing-based
 *    tracked-item use (`:733-763`), totem pop (EntityEvent 35, `:719-731`), splash-potion landing
 *    (`:779-841`).
 * 2. **Trap plaques** — camera-facing rounded plates over positions fingerprinted purely from the
 *    clientbound sound packet's pitch/volume (`addPlaySoundS2CPacket`, `:589-650`): "Трапка"
 *    (`block.piston.extend`), "Пласт" (`block.anvil.place`), "Драконка" (evoker_fangs + toast) and
 *    "Неизбежка" (wither + ender_dragon). Rendered with OUR GUI render API exactly like
 *    [net.ccbluex.liquidbounce.features.module.modules.render.ModuleTNTTimer] (pattern A below).
 * 3. **Opponent-cooldown feed** — the SystemDLC `CooldownTracker` is consumed by their UseTrackerHud,
 *    which is NOT ported. The `EnemyCooldowns` setting is kept and its lightweight in-memory tracker
 *    ([CooldownTracker]) is fed faithfully, but there is NO visible output until that HUD is ported.
 *
 * ## Threading (AGENTS.md "Cross-cutting gotchas")
 * `PacketEvent` can fire on the netty thread. The [packetHandler] therefore only reads the packet's
 * type (and the `eventId` primitive) and enqueues the immutable packet onto a
 * [ConcurrentLinkedQueue]; everything that touches the world — entity resolution, the plaque list,
 * the maps — runs on the client thread from [tickHandler]. Same discipline as
 * [net.ccbluex.liquidbounce.utils.combat.intel.PvpIntelTracker] and AutoCrystal's `pendingSpawns`.
 *
 * ## Render pattern (A) — projected screen-space plate
 * The SystemDLC original draws a real 3D billboard through its own shaders
 * (`onCameraFloatItemTrackEntryMatrixStackVec3dDoubleString`, `:928-976`). That stack is NOT ported.
 * As [ModuleTNTTimer] does, the plaque anchor (sound position + 1 block up, `:934`) is projected with
 * [WorldToScreen.calculateScreenPos] and drawn in an [OverlayRenderEvent] with `drawRoundedRect` + a
 * 16x16 item icon + the imported SystemDLC font. The world-space `DistanceScale` factor is converted to a
 * screen-pixel scale by measuring how many pixels one world block spans along the camera-up axis at
 * the anchor depth. The plate geometry (`42 + textWidth` wide, 26 tall, radius 8, icon at left+10,
 * text at left+32 size 14 white) is byte-identical to the source and to [ModuleTNTTimer].
 *
 * ## Fidelity notes / deviations
 * - Our [notification] API carries a title + message + severity but NO icon slot; the SystemDLC
 *   per-toast item [client.api.Icon] is dropped. The action sentence is routed as the message with a
 *   constant "ItemTracker" title (the idiom every LB module uses).
 * - Player names are masked through [ModuleNameProtect.replace] — the analog of the source's
 *   `Protect.getStringByString2` (`:462-466`); it returns the raw name when NameProtect is off.
 * - `check3()` (ItemRadius dragon-skin, `:293-296`) is read from the sibling
 *   [ModuleItemRadius.dragonSkin] accessor built in parallel; it enlarges the trap/plaster durations
 *   (30000/20000 vs 15000/60000, `:599,613`).
 * - `check4()` (AutoSwap, `:549-552`) is always `false`: no AutoSwap module is ported, so the source's
 *   `UnsafeAccess.getModule()` null path is reproduced. This still drives `isTrackedItem2` (`:765-773`)
 *   correctly via [ServerFlag].
 * - "Масштаб плашки" is always visible; the source hides it while `TrapTimers` is off
 *   (`setVisibleWhen`, `:176`) — our flat float DSL has no per-value visibility predicate.
 */
@Suppress("TooManyFunctions", "LargeClass")
object ModuleItemTracker : ClientModule("ItemTracker", ModuleCategories.PLAYER) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.itemTracker"

    // --- Settings (10), in source order (:119-158) ---

    /** "Радиус" / tracking range, default 50, 10..100 (SliderSetting, `:119`). */
    private val range by int("Range", 50, 10..100)

    /** "Метание зелий" / report who a thrown potion landed on (`:123-126`). */
    private val potionThrows by boolean("PotionThrows", true)

    /** "Бросок перлов" / report ender-pearl throws (`:127-130`). */
    private val pearlThrows by boolean("PearlThrows", true)

    /** "Использование" / report item-use starts/completions (`:131-134`). */
    private val itemUse by boolean("ItemUse", true)

    /** "Потеря тотемов" / report totem pops (`:135-138`). */
    private val totemPops by boolean("TotemPops", true)

    /** "Кулдауны противников" / feed the opponent-cooldown tracker (`:139-142`). No HUD yet. */
    private val enemyCooldowns by boolean("EnemyCooldowns", true)

    /** "Таймеры трапки/пласта" / draw the world trap/plaster countdown plaques (`:143-146`). */
    private val trapTimers by boolean("TrapTimers", true)

    /** "Масштаб плашки" / plate scale, default 0.3, 0.1..1.0 (`:147-149`). Fed to [SystemDlcTheme.distanceScale]. */
    private val plateScale by float("PlateScale", 0.3F, 0.1F..1.0F)

    /** "Себя тоже" / also log the local player's own actions (`:151-153`). */
    private val includeSelf by boolean("IncludeSelf", false)

    /** "Игнорировать друзей" / do not log friends' actions (`:155-157`). */
    private val ignoreFriends by boolean("IgnoreFriends", true)

    // --- Reused plaque icons (source itemStack3 / itemStack6, `:85,88`) ---
    // `by lazy`, not eager fields: building an ItemStack reads the item's default DataComponents,
    // which crashes ("Components not bound yet") if this object's init is forced before the item
    // component registry binds. Deferring to first render (components long since bound) avoids the
    // registry-freeze trap — same fix as ModuleTNTTimer.tntStack.
    private val scrapStack by lazy { ItemStack(Items.NETHERITE_SCRAP) }
    private val kelpStack by lazy { ItemStack(Items.DRIED_KELP) }

    // --- Main-thread state (all mutated only on the client thread) ---

    /** `map` (`:99`): thrown-potion entity id -> last icon + position, for landing detection. */
    private val pendingPotions = HashMap<Int, DroppedItemEntry>()

    /** `map2` (`:100`): player uuid -> in-progress item use, for the "used to full duration" gate. */
    private val itemUseCounts = HashMap<UUID, ItemCount>()

    /** `map3` (`:101`): player uuid -> held totem stack, so a pop can name the exact talisman. */
    private val totemStacks = HashMap<UUID, ItemStack>()

    /** `map4` (`:102`): player uuid -> held splash-potion stack, so a landing can name the potion. */
    private val splashStacks = HashMap<UUID, ItemStack>()

    /** `set` (`:103`): players currently holding a CHARGED crossbow (for the shot transition). */
    private val chargedCrossbowHolders = HashSet<UUID>()

    /** `set2` (`:104`): reused per-scan scratch of uuids seen in range. */
    private val scratchUuids = HashSet<UUID>()

    /** `set3` (`:105`): reused per-scan scratch of potion entity ids. */
    private val seenPotionIds = HashSet<Int>()

    /** `set4` (`:106`): pearl entity ids already announced. */
    private val seenPearlIds = HashSet<Int>()

    /** `map5` (`:107`): per (uuid,item) rate-limit for swing-use toasts (1000 ms). */
    private val useNotifyTimes = HashMap<UUID, ItemUseRecord>()

    /** `list` (`:108`): live trap plaques. Identity-keyed by [ItemTrackEntry]. */
    private val plaques = ArrayList<ItemTrackEntry>()

    /** `map6` (`:109`): per-plaque exponentially-smoothed screen scale. */
    private val plaqueScales = HashMap<ItemTrackEntry, Float>()

    /** `time` (`:110`): wall-clock of the previous rendered frame. */
    private var lastFrameTime = System.currentTimeMillis()

    /** `set5` (`:111`): stun effects seen on the local player (for the "Дезка" cooldown). */
    private val stunEffects = HashSet<MobEffect>()

    /** `vec3d`/`time2` (`:112-113`): last evoker-fangs sound, awaiting the challenge toast. */
    private var evokerFangsPos: Vec3? = null
    private var evokerFangsTime = 0L

    /** `vec3d2`/`time3` (`:114-115`): last wither-break sound, awaiting the dragon-hurt sound. */
    private var witherBreakPos: Vec3? = null
    private var witherBreakTime = 0L

    /** Netty-thread ingress: immutable packets awaiting main-thread processing. */
    private val pendingPackets = ConcurrentLinkedQueue<Packet<*>>()

    // ── Ingress (netty thread) ───────────────────────────────────────────────

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !event.original) {
            return@handler
        }
        // Only type checks + a primitive read happen here; a decode race must not drop the connection.
        runCatching {
            when (val packet = event.packet) {
                // EntityStatusS2CPacket status == 35 (totem pop) — `:698`.
                is ClientboundEntityEventPacket -> if (packet.eventId.toInt() == TOTEM_POP_EVENT) {
                    pendingPackets.add(packet)
                }
                is ClientboundSoundPacket -> pendingPackets.add(packet)
                is ClientboundLoginPacket -> pendingPackets.add(packet)
                is ClientboundAnimatePacket -> pendingPackets.add(packet)
                is ClientboundUpdateMobEffectPacket -> pendingPackets.add(packet)
                is ClientboundRemoveMobEffectPacket -> pendingPackets.add(packet)
            }
        }
    }

    // ── Drain + per-tick scans (client thread) ───────────────────────────────

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (mc.player == null || mc.level == null) {
            pendingPackets.clear()
            return@handler
        }

        drainPackets()

        // `onAnimatedFloat` (:302-327): the per-tick entity scans, in the same order/gating.
        val rangeSq = rangeSquared()
        if (potionThrows) {
            scanHeldSplash(rangeSq)
            scanPotionEntities(rangeSq)
        }
        if (pearlThrows) {
            scanPearls(rangeSq)
        }
        if (itemUse || enemyCooldowns) {
            scanItemUse(rangeSq)
        }
        if (itemUse) {
            scanCrossbow(rangeSq)
        }
        if (totemPops) {
            scanHeldTotems(rangeSq)
        }
    }

    private fun drainPackets() {
        while (true) {
            when (val packet = pendingPackets.poll() ?: break) {
                is ClientboundEntityEventPacket -> onTotemPop(packet)
                is ClientboundSoundPacket -> if (trapTimers) onSound(packet)
                is ClientboundLoginPacket -> onGameJoin()
                is ClientboundAnimatePacket -> onSwing(packet)
                is ClientboundUpdateMobEffectPacket -> if (enemyCooldowns) onEffectAdd(packet)
                is ClientboundRemoveMobEffectPacket -> if (enemyCooldowns) onEffectRemove(packet)
            }
        }
    }

    // ── (1) Notification scans ───────────────────────────────────────────────

    /** `addDouble` (`:193-220`): a crossbow charged->uncharged transition while a crossbow is still held. */
    private fun scanCrossbow(rangeSq: Double) {
        val self = mc.player ?: return
        scratchUuids.clear()
        for (p in world.players()) {
            if (self.distanceToSqr(p) > rangeSq) {
                continue
            }
            val uuid = p.uuid
            scratchUuids.add(uuid)
            val nowCharged = handMatches(p, ::isChargedCrossbow) // isPlayerEntity (:257-259)
            val wasCharged = chargedCrossbowHolders.contains(uuid)
            if (nowCharged && !wasCharged) {
                chargedCrossbowHolders.add(uuid)
            } else if (!nowCharged && wasCharged) {
                chargedCrossbowHolders.remove(uuid)
                // isPlayerEntity2 (:340-342): still holds a crossbow -> it was fired, not swapped away.
                if (handMatches(p, ::isCrossbow) && shouldLog(p)) {
                    notify(nameOf(p) + " выстрелил из арбалета")
                }
            }
        }
        chargedCrossbowHolders.retainAll(scratchUuids)
    }

    /** `addDouble2` (`:261-286`): a newly-seen ender pearl in range, owned by a loggable living entity. */
    private fun scanPearls(rangeSq: Double) {
        val self = mc.player ?: return
        val seen = HashSet<Int>()
        for (entity in world.entitiesForRendering()) {
            if (entity !is ThrownEnderpearl) {
                continue
            }
            val id = entity.id
            seen.add(id)
            if (seenPearlIds.contains(id)) {
                continue
            }
            seenPearlIds.add(id)
            val owner = entity.owner
            if (self.distanceToSqr(entity) <= rangeSq && owner is LivingEntity && shouldLog(owner)) {
                notify(nameOf(owner) + " бросил перл")
            }
        }
        seenPearlIds.retainAll(seen)
    }

    /**
     * `addDouble3` (`:377-419`): tracks each player's in-progress item use; when they stop within 2
     * ticks of the full duration it fires the "used" toast (`itemUse`) and records the opponent
     * cooldown (`enemyCooldowns`). The current cooldown target is always scanned even out of range.
     */
    private fun scanItemUse(rangeSq: Double) {
        val self = mc.player ?: return
        scratchUuids.clear()
        val trackedUuid = if (enemyCooldowns) CooldownTracker.uuid else null

        for (p in world.players()) {
            val uuid = p.uuid
            val isTracked = uuid == trackedUuid
            if (!isTracked && self.distanceToSqr(p) > rangeSq) {
                continue
            }
            scratchUuids.add(uuid)
            val using = p.isUsingItem
            val stack = if (using) p.useItem else ItemStack.EMPTY
            val count = itemUseCounts[uuid]
            if (using && !stack.isEmpty) {
                val ticks = p.ticksUsingItem
                if (count != null && count.item === stack.item) {
                    count.useTicks = ticks
                } else {
                    itemUseCounts[uuid] = ItemCount(stack.copy(), stack.getUseDuration(p), ticks)
                    if (isTracked) {
                        recordSwingCooldown(stack) // onItemStack2 on a fresh use start (:399)
                    }
                }
            } else if (count != null) {
                if (count.useTicks + 2 >= count.maxTicks) {
                    if (itemUse && shouldLog(p)) {
                        notifyItemUse(count.itemStack, p) // onItemStackPlayerEntity (:405)
                    }
                    if (isTracked) {
                        recordFinishCooldown(count.itemStack) // onItemStack (:409)
                    }
                }
                itemUseCounts.remove(uuid)
            }
        }
        itemUseCounts.keys.retainAll(scratchUuids)
    }

    /** `onDouble` (`:333-338`): record splash/lingering potions currently held by living entities. */
    private fun scanHeldSplash(rangeSq: Double) = scanHeld(rangeSq, splashStacks) {
        handStack(it, ::isSplashOrLingering) // getItemStackByLivingEntity (:249-251)
    }

    /** `onDouble2` (`:353-358`): record totems currently held by living entities. */
    private fun scanHeldTotems(rangeSq: Double) = scanHeld(rangeSq, totemStacks) {
        handStack(it, ::isTotem) // getItemStackByLivingEntity2 (:525-527)
    }

    /** `onFunctionDoubleMap` (`:468-484`): shared held-item scan into [map]. */
    private inline fun scanHeld(rangeSq: Double, map: HashMap<UUID, ItemStack>, fn: (LivingEntity) -> ItemStack) {
        val self = mc.player ?: return
        scratchUuids.clear()
        for (entity in world.entitiesForRendering()) {
            if (entity is LivingEntity && self.distanceToSqr(entity) <= rangeSq) {
                val uuid = entity.uuid
                scratchUuids.add(uuid)
                val stack = fn(entity)
                if (!stack.isEmpty) {
                    map[uuid] = stack.copy()
                }
            }
        }
        map.keys.retainAll(scratchUuids)
    }

    /** `addDouble4` (`:779-810`): track thrown potions; on removal announce who they landed on. */
    private fun scanPotionEntities(rangeSq: Double) {
        val self = mc.player ?: return
        seenPotionIds.clear()
        for (entity in world.entitiesForRendering()) {
            if (entity !is AbstractThrownPotion) {
                continue
            }
            val id = entity.id
            val existing = pendingPotions[id]
            if (existing == null) {
                if (self.distanceToSqr(entity) > rangeSq) {
                    continue
                }
                pendingPotions[id] = DroppedItemEntry(potionStackFor(entity).copy(), entity.position())
            } else {
                existing.vec3d = entity.position()
            }
            seenPotionIds.add(id)
        }

        val iterator = pendingPotions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!seenPotionIds.contains(entry.key)) {
                iterator.remove()
                onPotionLanded(entry.value)
            }
        }
    }

    /** `getItemStackByPotionEntity` (`:812-822`): the thrower's tracked splash stack, else the entity's own. */
    private fun potionStackFor(potion: AbstractThrownPotion): ItemStack {
        val owner = potion.owner
        if (owner != null) {
            val stack = splashStacks[owner.uuid]
            if (stack != null && !stack.isEmpty) {
                return stack
            }
        }
        return potion.item
    }

    /** `onDroppedItemEntry` (`:824-841`): notify every loggable player within the 8x4x8 box + 4 blocks flat. */
    private fun onPotionLanded(entry: DroppedItemEntry) {
        val v = entry.vec3d
        val box = AABB(v.x - 4.0, v.y - 2.0, v.z - 4.0, v.x + 4.0, v.y + 2.0, v.z + 4.0)
        val potionName = potionDisplayName(entry.itemStack)
        // getEntitiesByClass(LivingEntity, box, isLivingEntity2) == players intersecting the box (:829).
        for (p in world.getEntitiesOfClass(Player::class.java, box)) {
            val dx = p.position().x - v.x
            val dz = p.position().z - v.z
            if (sqrt(dx * dx + dz * dz) <= 4.0 && shouldLog(p)) {
                notify(nameOf(p) + " получил " + potionName)
            }
        }
    }

    /** `onItemStackPlayerEntity` (`:425-441`): "<name> <verb> <item>". */
    private fun notifyItemUse(stack: ItemStack, player: Player) {
        if (stack.isEmpty) {
            return
        }
        val displayName = stack.hoverName.string
        val tracked = SystemDlcItems.resolve(stack)
        val verb = verbFor(stack, tracked) ?: return
        val itemName = tracked?.text ?: stripName(displayName)
        notify(nameOf(player) + " " + verb + " " + itemName)
    }

    // ── (1) Notification packet handlers ─────────────────────────────────────

    /** `onEntityStatusS2CPacket` (`:719-731`): totem pop -> cooldown (tracked) + loss toast. */
    private fun onTotemPop(packet: ClientboundEntityEventPacket) {
        val level = mc.level ?: return
        val entity = packet.getEntity(level) as? LivingEntity ?: return
        if (enemyCooldowns && isTrackedOpponent(entity)) {
            CooldownTracker.record(CooldownItem.TOTEM)
        }
        if (totemPops && shouldLog(entity)) {
            notifyTotemLoss(entity)
        }
    }

    /** `removeLivingEntity` (`:529-541`): "<name> потерял <totem/talisman>". */
    private fun notifyTotemLoss(entity: LivingEntity) {
        val stack = totemStacks.remove(entity.uuid) ?: ItemStack(Items.TOTEM_OF_UNDYING)
        notify(nameOf(entity) + " потерял " + totemDisplayName(stack))
    }

    /** `onEntityAnimationS2CPacket` (`:733-763`): a swing with a tracked item (server-flag gated). */
    private fun onSwing(packet: ClientboundAnimatePacket) {
        val level = mc.level ?: return
        val action = packet.action
        if (action != SWING_MAIN_HAND && action != SWING_OFF_HAND) {
            return
        }
        val player = level.getEntity(packet.id) as? Player ?: return
        val stack = if (action == SWING_MAIN_HAND) player.mainHandItem else player.offhandItem
        val tracked = SystemDlcItems.resolve(stack) ?: return
        if (!swingCountsAsUse(tracked)) { // isTrackedItem2 (:765-773)
            return
        }
        if (enemyCooldowns && isTrackedOpponent(player)) {
            recordSwingCooldown(stack) // onItemStack2 (:742)
        }
        if (itemUse && shouldLog(player)) {
            val self = mc.player ?: return
            if (self.distanceToSqr(player) <= rangeSquared() && rateLimitUse(tracked, player.uuid)) {
                notify(nameOf(player) + " использовал " + tracked.text)
            }
        }
    }

    /** `addEntityStatusEffectS2CPacket` (`:570-587`): three distinct stun effects on self -> "Дезка" cooldown. */
    private fun onEffectAdd(packet: ClientboundUpdateMobEffectPacket) {
        if (CooldownTracker.uuid == null) {
            return
        }
        val self = mc.player ?: return
        if (packet.entityId != self.id) {
            return
        }
        val effect = packet.effect.value()
        val amp = packet.effectAmplifier
        val flagged = (effect === MobEffects.WITHER.value() && amp == 1) ||
            (effect === MobEffects.SLOWNESS.value() && amp == 4) ||
            (effect === MobEffects.MINING_FATIGUE.value() && amp == 4)
        if (flagged) {
            stunEffects.add(effect)
            if (stunEffects.size >= STUN_EFFECT_THRESHOLD) {
                CooldownTracker.record(CooldownItem.NAUSEA)
                stunEffects.clear()
            }
        }
    }

    /** `RemoveEntityStatusEffectS2CPacket` branch (`:711-713`). */
    private fun onEffectRemove(packet: ClientboundRemoveMobEffectPacket) {
        stunEffects.remove(packet.effect().value())
    }

    /** `GameJoinS2CPacket` branch (`:702-704`): reset the stun set + the cooldown tracker on (re)join. */
    private fun onGameJoin() {
        stunEffects.clear()
        CooldownTracker.reset()
    }

    // ── (1) Cooldown recorders (feed the invisible tracker) ──────────────────

    /** `onItemStack` (`:226-247`): cooldown recorded when the tracked opponent FINISHES a use. */
    private fun recordFinishCooldown(stack: ItemStack) {
        val item = stack.item
        when {
            item === Items.ENCHANTED_GOLDEN_APPLE -> CooldownTracker.record(CooldownItem.ENCHANTED_GOLDEN_APPLE)
            item === Items.GOLDEN_APPLE -> CooldownTracker.record(CooldownItem.GOLDEN_APPLE)
            item === Items.CHORUS_FRUIT -> CooldownTracker.record(CooldownItem.CHORUS_FRUIT)
            item === Items.POTION -> {
                val potion = stack.get(DataComponents.POTION_CONTENTS) ?: return
                for (instance in potion.allEffects) {
                    val effect = instance.effect.value()
                    when {
                        effect === MobEffects.INSTANT_HEALTH.value() -> CooldownTracker.record(CooldownItem.HEALING_POTION)
                        effect === MobEffects.NAUSEA.value() -> CooldownTracker.record(CooldownItem.NAUSEA)
                    }
                }
            }
        }
    }

    /** `onItemStack2` (`:861-870`): cooldown recorded on a tracked opponent's use-start or swing. */
    private fun recordSwingCooldown(stack: ItemStack) {
        when (stack.item) {
            TrackedItem.ENDER_EYE.item -> CooldownTracker.record(CooldownItem.NAUSEA)
            TrackedItem.DRIED_KELP.item -> CooldownTracker.record(CooldownItem.DRIED_KELP)
            TrackedItem.NETHERITE_SCRAP.item -> CooldownTracker.record(CooldownItem.NETHERITE_SCRAP)
        }
    }

    // ── (2) Trap plaques: sound ingestion ────────────────────────────────────

    /** `addPlaySoundS2CPacket` (`:589-650`): fingerprint the sound and arm a plaque. */
    private fun onSound(packet: ClientboundSoundPacket) {
        val path = packet.sound.value().location().path
        val pitch = packet.pitch
        val volume = packet.volume
        val pos = Vec3(packet.x, packet.y, packet.z)
        val now = System.currentTimeMillis()

        when (path) {
            // block.piston.extend, vol 0.7 pitch 0.5 -> "Трапка"; dragon-skin 30000ms else 15000ms (:595-607).
            "block.piston.extend" -> if (fingerprint(pitch, PISTON_PITCH, volume, PISTON_VOLUME)) {
                val duration = if (isDragonSkinActive()) TRAP_DRAGON_MS else TRAP_MS
                addPlaque(pos, now, duration, scrapStack, TrackedItem.NETHERITE_SCRAP.text)
            }
            // block.anvil.place, vol 0.7 pitch 1.1 -> "Пласт"; 20000ms if a kelp holder looks down else 60000ms (:609-621).
            "block.anvil.place" -> if (fingerprint(pitch, ANVIL_PITCH, volume, ANVIL_VOLUME)) {
                val duration = if (isKelpHolderLookingDown(pos)) PLAST_NEAR_MS else PLAST_FAR_MS
                addPlaque(pos, now, duration, kelpStack, TrackedItem.DRIED_KELP.text)
            }
            // evoker_fangs then challenge_complete within 500ms & sqDist < 0.5 -> "Драконка" 30000ms (:623-634).
            "entity.evoker_fangs.attack" -> {
                evokerFangsPos = pos
                evokerFangsTime = now
            }
            "ui.toast.challenge_complete" -> {
                val origin = evokerFangsPos
                if (origin != null && now - evokerFangsTime <= PAIR_WINDOW_MS && origin.distanceToSqr(pos) < PAIR_DIST_SQ) {
                    addPlaque(origin, now, DRAGON_MS, scrapStack, LABEL_DRAGON)
                    evokerFangsPos = null
                    evokerFangsTime = 0L
                }
            }
            // wither.break_block then ender_dragon.hurt within 500ms & sqDist < 0.5 -> "Неизбежка" 15000ms (:635-648).
            "entity.wither.break_block" -> {
                witherBreakPos = pos
                witherBreakTime = now
            }
            "entity.ender_dragon.hurt" -> {
                val origin = witherBreakPos
                if (origin != null && now - witherBreakTime <= PAIR_WINDOW_MS && origin.distanceToSqr(pos) < PAIR_DIST_SQ) {
                    addPlaque(origin, now, INEVITABLE_MS, scrapStack, LABEL_INEVITABLE)
                    witherBreakPos = null
                    witherBreakTime = 0L
                }
            }
        }
    }

    /** Adds a plaque unless a coincident same-label re-arm is already live (`isVec3dLongString`, `:652-666`). */
    private fun addPlaque(pos: Vec3, now: Long, duration: Long, icon: ItemStack, label: String) {
        if (!isDuplicatePlaque(pos, now, label)) {
            plaques.add(ItemTrackEntry(pos, now, duration, icon, label))
        }
    }

    /** `isVec3dLongString` (`:652-666`): a live same-label plaque near end-of-life within [0.25, 5] blocks. */
    private fun isDuplicatePlaque(pos: Vec3, now: Long, label: String): Boolean {
        for (entry in plaques) {
            if (entry.text == label) {
                val remaining = entry.duration - (now - entry.time)
                if (remaining <= DEDUP_REMAINING_MS) {
                    val distSq = entry.vec3d.distanceToSqr(pos)
                    if (distSq >= DEDUP_MIN_DIST_SQ && distSq <= DEDUP_MAX_DIST_SQ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /** `isVec3d` (`:872-891`): nearest kelp-holder within 8 blocks looks at a pitch |xRot| < 45. */
    private fun isKelpHolderLookingDown(pos: Vec3): Boolean {
        val level = mc.level ?: return false
        var nearest: Player? = null
        var best = KELP_SEARCH_DIST_SQ
        for (p in level.players()) {
            if (holdsKelp(p)) {
                val distSq = p.position().distanceToSqr(pos)
                if (distSq < best) {
                    best = distSq
                    nearest = p
                }
            }
        }
        return nearest != null && abs(nearest.xRot) < KELP_PITCH_LIMIT
    }

    // ── (2) Trap plaques: rendering (pattern A, mirrors ModuleTNTTimer) ───────

    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (mc.player == null || mc.level == null || !trapTimers) {
            return@handler
        }

        val now = System.currentTimeMillis()
        // list.removeIf(expired) + map6.keySet().retainAll(list) each frame (:903-904).
        plaques.removeIf { now - it.time > it.duration }
        plaqueScales.keys.retainAll(plaques)
        if (plaques.isEmpty()) {
            return@handler
        }

        // f = min(dt, 0.1); this.time = now (:906-907).
        val dt = min((now - lastFrameTime) / 1000.0F, 0.1F)
        lastFrameTime = now
        val lerpFactor = min(1.0F, dt * 10.0F)

        val cameraPos = mc.gameRenderer.mainCamera.position()
        val up = mc.gameRenderer.mainCamera.upVector()

        for (entry in plaques) {
            val remaining = entry.duration - (now - entry.time)
            if (remaining <= 0L) {
                continue
            }
            val text = entry.text + " " + formatSeconds(remaining / 1000.0)

            // Plaque anchor: sound position + 1 block up (:934).
            val anchor = entry.vec3d.add(0.0, 1.0, 0.0)
            val anchorScreen = WorldToScreen.calculateScreenPos(anchor) ?: continue

            val distance = entry.vec3d.distanceTo(cameraPos)
            val target = SystemDlcTheme.distanceScale(distance, plateScale)
            val prev = plaqueScales.getOrDefault(entry, target)
            val smoothed = prev + (target - prev) * lerpFactor
            plaqueScales[entry] = smoothed

            val upPoint = anchor.add(up.x().toDouble(), up.y().toDouble(), up.z().toDouble())
            val upScreen = WorldToScreen.calculateScreenPos(upPoint) ?: continue
            val pixelsPerBlock = hypot(
                (upScreen.x - anchorScreen.x).toDouble(),
                (upScreen.y - anchorScreen.y).toDouble(),
            ).toFloat()
            val screenScale = smoothed * pixelsPerBlock
            if (screenScale <= 0.0F) {
                continue
            }

            drawPlate(event, anchorScreen.x, anchorScreen.y, screenScale, entry.iconStack, text)
        }
    }

    /**
     * One plaque, local geometry byte-identical to the source (`:941-972`) and to [ModuleTNTTimer]:
     * width `42 + textWidth`, height 26, radius 8, 16x16 icon at left+10, text at left+32 size 14 white.
     */
    private fun drawPlate(
        event: OverlayRenderEvent,
        anchorX: Float,
        anchorY: Float,
        screenScale: Float,
        icon: ItemStack,
        text: String,
    ) {
        // Text width at the source's size 14, measured in the imported SystemDLC font (SystemFont.kt),
        // so the plate auto-fits the real glyphs.
        val textWidth = SystemDraw.textWidth(text, TEXT_SIZE, TEXT_FAMILY)
        val plateWidth = PLATE_BASE_WIDTH + textWidth // f5 = 42 + textWidth (:945)
        val left = -plateWidth / 2.0F                 // f7 = -f5 / 2 (:947)

        with(event.context) {
            pose().pushMatrix()
            pose().translate(anchorX, anchorY)
            pose().scale(screenScale, screenScale)

            drawRoundedRect(
                x1 = left,
                y1 = -PLATE_HEIGHT / 2.0F,
                x2 = left + plateWidth,
                y2 = PLATE_HEIGHT / 2.0F,
                radius = PLATE_RADIUS,
                fillColor = SystemDlcTheme.background(),
                outlineColor = null,
                outlineWidth = 0.0F,
            )

            // 16x16 item icon at (left + 10, -8) (f9 = f7 + 10, f10 = -8; :959-965).
            // Crisp per-on-screen-size raster (drawPlateItem) instead of the vanilla fixed-16px atlas
            // blit, which aliases under this plate's screenScale (see PlateItemIcon).
            pose().pushMatrix()
            pose().translate(left + ICON_LEFT_PAD, -ICON_SIZE / 2.0F)
            drawPlateItem(icon, 0.0F, 0.0F, ICON_SIZE, screenScale)
            pose().popMatrix()

            // Text at (left + 32, -8.5) size 14 white, in the imported SystemDLC font. Left-aligned so
            // its left edge lands at f11 = f9 + 16 + 6, top at f12 = -8.5 (:967-972). SystemFont draws
            // at real px; the active pose (anchor + screenScale) scales the glyphs 1:1 with the plate.
            SystemDraw.text(
                this,
                text,
                left + ICON_LEFT_PAD + ICON_SIZE + TEXT_GAP,
                TEXT_TOP,
                TEXT_SIZE,
                Color4b.WHITE,
                TEXT_FAMILY,
            )

            pose().popMatrix()
        }
    }

    // ── (3) Opponent-cooldown feed: last-target selection ────────────────────

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        if (!enemyCooldowns) {
            return@handler
        }
        // onPlayerEntityWorldHandEntityEntityHitResult (:686-693): switch the tracked opponent to
        // whoever we just attacked (another living player).
        val attacked = event.entity
        val self = mc.player ?: return@handler
        if (attacked is Player && attacked !== self && attacked.isAlive) {
            CooldownTracker.switchTo(attacked)
        }
    }

    // ── Shared predicates / helpers ──────────────────────────────────────────

    /** `isLivingEntity` (`:222-224`): IncludeSelf/IgnoreFriends gate. */
    private fun shouldLog(entity: LivingEntity): Boolean {
        if (!includeSelf && entity === mc.player) {
            return false
        }
        return !ignoreFriends || !FriendManager.isFriend(entity)
    }

    /** `getStringByLivingEntity` (`:462-466`): masked display name via NameProtect. */
    private fun nameOf(entity: LivingEntity): String = ModuleNameProtect.replace(entity.name.string)

    /** `isLivingEntity3` (`:554-557`): the entity is the current cooldown target. */
    private fun isTrackedOpponent(entity: LivingEntity): Boolean {
        val uuid = CooldownTracker.uuid ?: return false
        return uuid == entity.uuid
    }

    /** `isTrackedItemUUID` (`:559-568`): per (uuid,item) 1000 ms rate limit for swing-use toasts. */
    private fun rateLimitUse(tracked: TrackedItem, uuid: UUID): Boolean {
        val now = System.currentTimeMillis()
        val record = useNotifyTimes[uuid]
        if (record != null && record.entry === tracked && now - record.time < USE_RATE_LIMIT_MS) {
            return false
        }
        useNotifyTimes[uuid] = ItemUseRecord(tracked, now)
        return true
    }

    /** `getStringByItemStackTrackedItem` (`:443-460`): the Russian verb for a used item, or null. */
    private fun verbFor(stack: ItemStack, tracked: TrackedItem?): String? {
        val item = stack.item
        return when {
            item is CrossbowItem -> "натянул"
            item === Items.POTION || item === Items.SPLASH_POTION || item === Items.LINGERING_POTION -> "выпил"
            item === Items.MILK_BUCKET -> "выпил"
            item === Items.GOLDEN_APPLE || item === Items.ENCHANTED_GOLDEN_APPLE -> "съел"
            item === Items.CHORUS_FRUIT -> "съел"
            item === Items.GOLDEN_CARROT -> "съел"
            else -> if (tracked != null && tracked.item !== Items.CROSSBOW) "использовал" else null
        }
    }

    /** `getStringByItemStack` (`:288-291`): totem/talisman display name, else "тотем". */
    private fun totemDisplayName(stack: ItemStack): String =
        SystemDlcItems.trackedItemByTotemModelData(stack)?.text ?: "тотем"

    /** `getStringByItemStack2` (`:843-859`): potion display name (custom color -> name -> "зелье"). */
    private fun potionDisplayName(stack: ItemStack): String {
        val potion = stack.get(DataComponents.POTION_CONTENTS)
        if (potion != null) {
            val customColor = potion.customColor()
            if (customColor.isPresent) {
                val tracked = TrackedItem.getTrackedItemByInt2(customColor.get())
                if (tracked != null) {
                    return tracked.text
                }
            }
        }
        val name = stripName(stack.hoverName.string)
        val tracked = SystemDlcItems.resolve(stack)
        return tracked?.text ?: name.ifEmpty { "зелье" }
    }

    /** `getStringByString` (`:521-523`): strip legacy color codes + brackets, collapse whitespace, trim. */
    private fun stripName(text: String?): String {
        if (text == null) {
            return ""
        }
        return text
            .replace(COLOR_CODE_REGEX, "")
            .replace(BRACKET_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    /**
     * `isTrackedItem2` (`:765-773`) via `ServerFlagSwitchMap` (NONE->1, ANY->2, FT->3, HW->4): does a
     * swing with this item count as a use? `check4()` (AutoSwap) is always false — no AutoSwap port.
     */
    private fun swingCountsAsUse(tracked: TrackedItem): Boolean = when (tracked.serverFlag) {
        ServerFlag.NONE -> false          // case 1
        ServerFlag.ANY -> true            // case 2
        ServerFlag.FT -> !autoSwapActive() // case 3 -> !check4()
        ServerFlag.HW -> autoSwapActive()  // case 4 -> check4()
    }

    /** `check4()` (`:549-552`): AutoSwap module not ported -> `UnsafeAccess.getModule()` null -> false. */
    private fun autoSwapActive(): Boolean = false

    /** `check3()` (`:293-296`): ItemRadius dragon-skin, read from the sibling module's public accessor. */
    private fun isDragonSkinActive(): Boolean = ModuleItemRadius.dragonSkin

    /** `isFloatFloatFloatFloat` (`:897-899`): |pitch - refPitch| < 0.01 && |volume - refVolume| < 0.01. */
    private fun fingerprint(pitch: Float, refPitch: Float, volume: Float, refVolume: Float): Boolean =
        abs(pitch - refPitch) < FINGERPRINT_EPSILON && abs(volume - refVolume) < FINGERPRINT_EPSILON

    /** `getItemStackByLivingEntityPredicate` (`:486-494`) reduced to a boolean: either hand matches. */
    private inline fun handMatches(entity: LivingEntity, predicate: (ItemStack) -> Boolean): Boolean =
        predicate(entity.mainHandItem) || predicate(entity.offhandItem)

    /** `getItemStackByLivingEntityPredicate` (`:486-494`): the first matching hand stack, or EMPTY. */
    private inline fun handStack(entity: LivingEntity, predicate: (ItemStack) -> Boolean): ItemStack {
        val main = entity.mainHandItem
        if (predicate(main)) {
            return main
        }
        val off = entity.offhandItem
        return if (predicate(off)) off else ItemStack.EMPTY
    }

    private fun isChargedCrossbow(stack: ItemStack): Boolean =
        stack.item is CrossbowItem && CrossbowItem.isCharged(stack) // isItemStack3 (:373-375)

    private fun isCrossbow(stack: ItemStack): Boolean = stack.item is CrossbowItem // isItemStack2 (:329-331)

    private fun isSplashOrLingering(stack: ItemStack): Boolean =
        stack.item === Items.SPLASH_POTION || stack.item === Items.LINGERING_POTION // isItemStack4 (:496-499)

    private fun isTotem(stack: ItemStack): Boolean = stack.item === Items.TOTEM_OF_UNDYING // isItemStack (:298-300)

    /** `isPlayerEntity3` (`:893-895`): kelp in either hand. */
    private fun holdsKelp(entity: Player): Boolean =
        entity.mainHandItem.item === Items.DRIED_KELP || entity.offhandItem.item === Items.DRIED_KELP

    /** `TimeFormat.getStringByDouble`: `< 10 -> "%.1fс"`, else `"%dс"` (Cyrillic "с", U+0441). */
    private fun formatSeconds(seconds: Double): String =
        if (seconds < 10.0) String.format("%.1fс", seconds) else String.format("%dс", seconds.toInt())

    private fun notify(message: String) =
        notification("ItemTracker", message, NotificationEvent.Severity.INFO)

    private fun rangeSquared(): Double {
        val r = range.toDouble()
        return r * r
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** `update11` (`:668-684`): full reset (mirrors onEnable + onDisable, `:348-351,978-981`). */
    private fun reset() {
        pendingPotions.clear()
        itemUseCounts.clear()
        totemStacks.clear()
        splashStacks.clear()
        chargedCrossbowHolders.clear()
        scratchUuids.clear()
        seenPotionIds.clear()
        seenPearlIds.clear()
        useNotifyTimes.clear()
        plaques.clear()
        plaqueScales.clear()
        stunEffects.clear()
        pendingPackets.clear()
        CooldownTracker.reset()
        evokerFangsPos = null
        evokerFangsTime = 0L
        witherBreakPos = null
        witherBreakTime = 0L
    }

    override fun onEnabled() {
        reset()
    }

    override fun onDisabled() {
        reset()
        super.onDisabled()
    }

    // ── Value/state records ──────────────────────────────────────────────────

    /** `DroppedItemEntry`: a thrown potion's identifying stack + last position. */
    private class DroppedItemEntry(@JvmField val itemStack: ItemStack, @JvmField var vec3d: Vec3)

    /** `ItemCount`: an in-progress item use. [maxTicks] = full duration, [useTicks] = ticks used so far. */
    private class ItemCount(
        @JvmField val itemStack: ItemStack,
        @JvmField val maxTicks: Int,
        @JvmField var useTicks: Int,
    ) {
        @JvmField
        val item = itemStack.item
    }

    /** `ItemUseRecord`: the last swing-use toast time per player, for rate limiting. */
    private class ItemUseRecord(@JvmField val entry: TrackedItem, @JvmField val time: Long)

    /**
     * `ItemTrackEntry`: one live trap plaque. A plain class (identity equals/hashCode) so [plaqueScales]
     * keys by object identity, matching the source `map6`. [duration] is the source `time2`.
     */
    private class ItemTrackEntry(
        @JvmField val vec3d: Vec3,
        @JvmField val time: Long,
        @JvmField val duration: Long,
        @JvmField val iconStack: ItemStack,
        @JvmField val text: String,
    )

    /**
     * `client.enums.CooldownItem` — the opponent cooldowns and their durations (seconds), copied
     * verbatim from `CooldownItem.java:11-18`. The display texts and durations are kept for the future
     * UseTrackerHud port; there is no visible consumer yet.
     */
    @Suppress("unused")
    private enum class CooldownItem(@JvmField val displayText: String, seconds: Int) {
        ENCHANTED_GOLDEN_APPLE("Чарка", 150),
        NAUSEA("Дезка", 60),
        TOTEM("Тотем", 60),
        GOLDEN_APPLE("Гэпл", 30),
        DRIED_KELP("Пласт", 30),
        HEALING_POTION("Исцеление", 20),
        CHORUS_FRUIT("Хорус", 20),
        NETHERITE_SCRAP("Трапка", 10);

        @JvmField
        val durationMs: Long = seconds * 1000L
    }

    /**
     * `client.concurrent.CooldownTracker` (`CooldownTracker.java`, 96 L) — in-memory opponent-cooldown
     * ledger, ported 1:1. It records real end-times per [CooldownItem] for the current target and
     * archives previous targets. There is NO visible output until the SystemDLC UseTrackerHud is ported;
     * this exists so the `EnemyCooldowns` feed paths are faithful and ready for that consumer.
     */
    @Suppress("unused")
    private object CooldownTracker {
        private val archive = HashMap<UUID, HashMap<CooldownItem, Long>>()
        private val current = HashMap<CooldownItem, Long>()

        /** The current tracked opponent's uuid (`getUUID`). */
        var uuid: UUID? = null
            private set

        /** The current tracked opponent's name (`getText`). */
        var text: String? = null
            private set

        /** `update()`: clear everything. */
        fun reset() {
            current.clear()
            archive.clear()
            uuid = null
            text = null
        }

        /** `onMap`: drop already-elapsed cooldowns. */
        private fun prune(map: HashMap<CooldownItem, Long>) {
            val now = System.currentTimeMillis()
            map.entries.removeIf { it.value <= now }
        }

        /** `removePlayerEntity`: switch target, archiving the old one's live cooldowns and restoring the new. */
        fun switchTo(player: Player) {
            val newUuid = player.uuid
            if (newUuid == uuid) {
                return
            }
            val old = uuid
            if (old != null) {
                val snapshot = HashMap(current)
                prune(snapshot)
                if (snapshot.isEmpty()) {
                    archive.remove(old)
                } else {
                    archive[old] = snapshot
                }
            }
            uuid = newUuid
            text = player.gameProfile.name
            current.clear()
            val restored = archive.remove(newUuid)
            if (restored != null) {
                prune(restored)
                current.putAll(restored)
            }
        }

        /** `onCooldownItem`: record a cooldown end-time for the current target (deduped within 1000 ms). */
        fun record(item: CooldownItem) {
            if (uuid == null) {
                return
            }
            val end = System.currentTimeMillis() + item.durationMs
            val existing = current[item]
            if (existing == null || abs(existing - end) >= COOLDOWN_DEDUP_MS) {
                current[item] = end
            }
        }

        /** `getLongByCooldownItem`: remaining ms for a cooldown, evicting it when elapsed. */
        fun remaining(item: CooldownItem): Long {
            val end = current[item] ?: return 0L
            val left = end - System.currentTimeMillis()
            if (left > 0L) {
                return left
            }
            current.remove(item)
            return 0L
        }
    }

    // ── Constants (cited to the source) ──────────────────────────────────────

    private const val TOTEM_POP_EVENT = 35            // EntityStatusS2CPacket status (:698)
    private const val SWING_MAIN_HAND = 0             // EntityAnimation id 0 (:735)
    private const val SWING_OFF_HAND = 3              // EntityAnimation id 3 (:735)
    private const val STUN_EFFECT_THRESHOLD = 3       // set5.size >= 3 (:580)
    private const val USE_RATE_LIMIT_MS = 1000L       // isTrackedItemUUID (:562)
    private const val COOLDOWN_DEDUP_MS = 1000L       // CooldownTracker.onCooldownItem (:72)
    private const val FINGERPRINT_EPSILON = 0.01F     // isFloatFloatFloatFloat (:898)

    // Sound fingerprints (:596-598,611-612).
    private const val PISTON_VOLUME = 0.7F
    private const val PISTON_PITCH = 0.5F
    private const val ANVIL_VOLUME = 0.7F
    private const val ANVIL_PITCH = 1.1F

    // Plaque durations (ms), source (:599,613,630,643).
    private const val TRAP_MS = 15000L
    private const val TRAP_DRAGON_MS = 30000L
    private const val PLAST_NEAR_MS = 20000L
    private const val PLAST_FAR_MS = 60000L
    private const val DRAGON_MS = 30000L
    private const val INEVITABLE_MS = 15000L

    // Inline plaque labels not carried by TrackedItem (:630,643).
    private const val LABEL_DRAGON = "Драконка"
    private const val LABEL_INEVITABLE = "Неизбежка"

    // Pair-window matching (:626,639) and dedup band (:656-658).
    private const val PAIR_WINDOW_MS = 500L
    private const val PAIR_DIST_SQ = 0.5
    private const val DEDUP_REMAINING_MS = 3000L
    private const val DEDUP_MIN_DIST_SQ = 0.0625
    private const val DEDUP_MAX_DIST_SQ = 25.0

    // Kelp-holder look test (:877,889).
    private const val KELP_SEARCH_DIST_SQ = 64.0
    private const val KELP_PITCH_LIMIT = 45.0F

    // Plate geometry, verbatim (:944-971) — identical to ModuleTNTTimer.
    private const val PLATE_BASE_WIDTH = 42.0F
    private const val PLATE_HEIGHT = 26.0F
    private const val PLATE_RADIUS = 8.0F
    private const val ICON_LEFT_PAD = 10.0F
    private const val ICON_SIZE = 16.0F
    private const val TEXT_GAP = 6.0F
    private const val TEXT_TOP = -8.5F
    private const val TEXT_SIZE = 14.0F

    /** Imported SystemDLC body atlas (Latin `b` / Cyrillic `bb`), the source's primary body weight. */
    private val TEXT_FAMILY = SystemFont.Family.B

    // getStringByString regexes (:522).
    private val COLOR_CODE_REGEX = Regex("(?i)§[0-9a-fk-or]")
    private val BRACKET_REGEX = Regex("[\\[\\]]")
    private val WHITESPACE_REGEX = Regex("\\s+")
}
