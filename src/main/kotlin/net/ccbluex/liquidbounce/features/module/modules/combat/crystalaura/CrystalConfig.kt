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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection

/** How the aim reaches a look point: instantly ([SNAP]) or with a speed-limited turn ([SMOOTH]). */
enum class RotationMode(override val tag: String) : Tagged {
    SNAP("Snap"),
    SMOOTH("Smooth")
}

/**
 * How much of the mouse-sensitivity grid step (gcd) a look point may be off the exact aim:
 * [DITHER] allows most of a step, [EXACT] only half a step.
 */
enum class GridMode(override val tag: String) : Tagged {

    /**
     * `Rotation.normalize()` rounds the delta onto the grid and dithers it by up to 0.4*gcd
     * (`utils/aiming/data/Rotation.kt:107-128`), so the rotation that actually goes out is NOT bit-identical to the
     * one the aura requested. The aura verifies every action against the rotation this tick's movement packet
     * carries (`AuraRotation.outgoing`), exactly - so when the dither pushes the ray off the block the same-tick
     * attempt simply falls back to the next tick, which is what the module did for every re-aim before.
     * The anticheat-friendlier default: an exact grid is what Grim's AimProcessor keys on.
     */
    DITHER("Dither"),

    /**
     * The requested delta is pre-quantised to whole gcd counts and marked `isNormalized`, which turns
     * `normalize()` into a no-op (`utils/aiming/data/Rotation.kt:107-108`) - no dither is added. The rotation that
     * leaves is therefore bit-identical to the requested one, and the aura's same-tick verification against
     * `AuraRotation.outgoing` is deterministic: a re-aim and the placement or attack it enables land in the same
     * client tick every time instead of only when the dither happens to stay on the block.
     */
    EXACT("Exact")
}

/** How the end crystal is brought into the hand before placing. */
enum class SwitchMode(override val tag: String) : Tagged {
    /** Server-side slot change only (silent hotbar); the client keeps showing the current item. */
    SILENT("Silent"),

    /** Real hotbar slot change, visible on the client. */
    NORMAL("Normal"),

    /** Never switch: only place while already holding an end crystal. */
    NONE("None")
}

/** What breaks the cells of an AutoMine plan; see [MineSettings.engine]. */
enum class MineEngine(override val tag: String) : Tagged {
    /** Vanilla's own progressive breaking (`mine/MineJob.kt`): every tick of the dig belongs to it. */
    VANILLA("Vanilla"),

    /**
     * Packet mining through `ModuleSpeedMine` (`mine/PacketMineJob.kt`): only the START and the STOP take a tick,
     * everything between them is the aura's.
     */
    SPEED_MINE("SpeedMine"),
}

/** Which entities the placement pick ray may pass through when aiming at the base block. */
enum class LookThrough(override val tag: String) : Tagged {
    /** Vanilla pick: any pickable entity in front of the block cancels the placement. */
    OFF("Off"),

    /** End crystals are ignored by the pick (they are attacked anyway). */
    CRYSTALS("Crystals"),

    /** Every entity is ignored by the pick. */
    ALL("All")
}

/**
 * What the damage model assumes about a remote victim's armour when the server hides part of it.
 *
 * A victim's armour reaches the client through two different channels, and a server can obfuscate them
 * independently. The armour POINTS come from the equipment the server sends
 * (`ClientboundSetEquipmentPacket`) plus `Attributes.ARMOR`, which is syncable
 * (`net/minecraft/world/entity/ai/attributes/Attributes.java:10-13`). The PROTECTION enchantments are read off
 * those same stacks by `EnchantProtection` — so a server that strips enchantment components from the equipment
 * packet leaves the model believing every opponent is unenchanted, and the predicted damage comes out far too
 * high.
 *
 * Every level here SUBSTITUTES the local player's own values for the victim's — it does not take the better of
 * the two, which is where this differs from [PlaceSettings.assumeResistance].
 *
 * The reason is that the two holes are not the same kind of hole. A Resistance effect the client never receives
 * is genuinely absent from its view, so assuming a minimum can only correct an over-estimate. A rewritten
 * enchantment list is not a missing reading but a false one: the usual obfuscation leaves a decoy enchantment on
 * the stack so the item still glints, and that decoy can be a Protection level the victim does not have. Treated
 * as a lower bound, a fake Protection IV would survive the comparison and talk the aura out of placing at all.
 * So once this is on, the victim's own enchantments stop counting as evidence.
 *
 * The trade-off is the honest one: on a server that does NOT obfuscate, an opponent in genuinely better gear than
 * ours is then under-estimated. Leave it [OFF] there.
 */
enum class ArmorAssumption(override val tag: String) : Tagged {

    /** Trust the client's reading, whatever it is. */
    OFF("Off"),

    /**
     * Score the victim with OUR protection enchantments and leave their armour points alone. The right level for
     * the common case: the server rewrites or hides enchantments but the armour pieces themselves are real.
     */
    PROTECTION("Protection"),

    /**
     * Also substitute our armour points and toughness. The right level when the server hides the equipment
     * itself, which leaves `getArmorValue()` reading 0.
     */
    FULL("Full")
}

/** Whether the damage model honours the victim's vanilla invulnerability window (i-frames). */
enum class IFrameMode(override val tag: String) : Tagged {
    /** Model `LivingEntity.hurtServer`: inside the window only the delta above `lastHurt` is dealt. */
    RESPECT("Respect"),

    /** Evaluate every hit as if the victim were outside the window. */
    IGNORE("Ignore")
}

/** How far ahead target positions are extrapolated for the damage evaluation. */
enum class PredictMode(override val tag: String) : Tagged {
    /** Use the current position. */
    OFF("Off"),

    /** Extrapolate by a tick count derived from the latency. */
    AUTO("Auto"),

    /** Extrapolate by the configured tick count. */
    FIXED("Fixed")
}

/**
 * Placement settings: how far and how often crystals are placed, the damage thresholds a placement has to
 * meet, target prediction, how the crystal gets into the hand and whether a place may share a tick with a break.
 */
class PlaceSettings : ValueGroup("Place") {

    /**
     * Maximum distance from the eye to the hit point on the clicked block face.
     *
     * The default is vanilla's own `BLOCK_INTERACTION_RANGE` (4.5) and that is also the ceiling Grim's `FarPlace`
     * enforces: it measures the eye to the CLOSEST point of the clicked block's box against that attribute plus a
     * movement-threshold margin of about 0.04, and cancels (`place.resync()`) on the first violation
     * (`checks/impl/scaffolding/FarPlace.java:31-56`). So a value above ~4.54 does not reach further - it makes
     * placements silently disappear. The slider still goes higher because the attribute is an ATTRIBUTE: a server
     * that raises it makes the larger value real.
     */
    val range by float("Range", 4.5f, 1f..5.5f)

    /** Minimum time between two placements. */
    val delay by int("Delay", 0, 0..1000, "ms")

    /** Minimum effective damage the crystal must deal to the target. */
    val minDamage by float("MinDamage", 6f, 0f..40f)

    /** Maximum effective damage the crystal may deal to the local player. */
    val maxSelfDamage by float("MaxSelfDamage", 6f, 0f..40f)

    /** Never place a crystal whose explosion would be lethal for the local player. */
    val antiSuicide by boolean("AntiSuicide", true)

    /** Accept a placement that is lethal for the target even when it fails the damage thresholds. */
    val lethalOverride by boolean("LethalOverride", true)

    /**
     * Resistance level assumed for a victim whose real effect the client cannot see; `0` = assume none.
     *
     * The server sends `ClientboundUpdateMobEffectPacket` only to the affected player's own connection
     * (`ServerPlayer.onEffectAdded` / `onEffectUpdated`, `net/minecraft/server/level/ServerPlayer.java:1638-1655`)
     * and to players riding the entity (`LivingEntity.sendEffectToPassengers`, `LivingEntity.java:1069-1075`) —
     * never to trackers. `getEffect(RESISTANCE)` is therefore always null for a remote victim and the damage
     * model over-estimates it by 1.25x per level, up to predicting full damage against a victim that takes zero
     * at Resistance V. Raise this on servers where Resistance is common; it is only ever a fallback and never
     * lowers an effect the client can actually see (`WorldView.capture` takes `assumeResistance - 1`, an
     * amplifier, and `max`es it against the real reading — see `WorldView`).
     */
    val assumeResistance by int("AssumeResistance", 0, 0..5)

    /**
     * Armour a remote victim is assumed to have when the server hides it; see [ArmorAssumption].
     *
     * Reach for this on servers that obfuscate enchantments: without it every opponent is scored as unenchanted,
     * the predicted damage is far above the real one, and `MinDamage` lets through placements that do not deliver.
     */
    val assumeArmor by enumChoice("AssumeArmor", ArmorAssumption.OFF)

    /**
     * Act on targets that blocks hide: place, dig and DETONATE without a line of sight. Governs placements, the
     * obsidian base, AutoMine and the attack on a crystal alike.
     *
     * The attack half was added on 2026-10-03, after the user's report that with this on "it does not place through
     * blocks at all": the attack rays still stopped at blocks, so `TickPlanner.canAimSpawn` (which refuses a
     * placement whose crystal no attack ray could pick) refused every placement that went through a wall, because
     * the crystal it spawns stands behind that same wall. A placement and the attack that detonates it are one
     * action here; without the attack half this setting placed crystals that could never be hit. The server tests
     * an attack's range only (`ServerGamePacketListenerImpl.handleAttack`, `:1768`) and Grim's `Reach` intercepts
     * the look ray with the target box only (`checks/impl/combat/Reach.java:293`); other ENTITIES on the ray still
     * mask the crystal, as `LookThrough` decides.
     *
     * Nothing on the server side objects. `ServerGamePacketListenerImpl.handleUseItemOn` validates only
     * `isWithinBlockInteractionRange(pos, 1.0)` and that the hit location sits within +-1.0000001 of the block
     * centre per axis; `ServerPlayerGameMode.handleBlockBreakAction` validates range, the build limit, spawn
     * protection, the world border and `blockActionRestricted`. Neither traces the world between the eye and the
     * block. Grim does not either: `RotationPlace.didRayTraceHit`
     * (`checks/impl/scaffolding/RotationPlace.java:83-134`) and its twin
     * (`checks/impl/breaking/RotationBreak.java:77-126`) build a `SimpleCollisionBox` of the TARGET position alone
     * and return true as soon as `ReachUtils.calculateIntercept` meets that one box - the requirement is to point
     * at the block within `BLOCK_INTERACTION_RANGE`, not to see it.
     *
     * What it does cost, stated plainly: this is the one action in this module a vanilla client cannot produce.
     * Its crosshair picks the FIRST block along the ray (`Entity.pick` with `ClipContext.Block.OUTLINE`), so a real
     * player can never click an occluded one. Every packet stays well formed and every other rule still holds - the
     * face is still one the eye is outside of, the range is still checked, the packet order is untouched - but the
     * behaviour itself is not reproducible by hand. Hence opt-in and off by default.
     */
    val throughBlocks by boolean("ThroughBlocks", false)

    /** Which entities the placement pick ray may pass through. */
    val lookThrough by enumChoice("LookThrough", LookThrough.CRYSTALS)

    /** Target position prediction for the damage evaluation. */
    val predict by enumChoice("Predict", PredictMode.AUTO)

    /** Extrapolation length used by [PredictMode.FIXED]. */
    val predictTicks by int("PredictTicks", 2, 0..10, "ticks")

    /** How the end crystal is brought into the hand. */
    val switchMode by enumChoice("Switch", SwitchMode.SILENT)

    /** Ticks the switched slot stays selected before swapping back. */
    val swapBack by int("SwapBack", 0, 0..20, "ticks")

    /**
     * Allow a placement in the SAME tick as a break (attack first, the order vanilla's click loops produce,
     * `Minecraft.java:2050-2056`).
     *
     * Default OFF, and it has to stay off against Grim. A block interaction and an entity interaction inside one
     * tick window are exactly what `MultiActionsF` looks for ("Interacting with a block and an entity in the same
     * tick", `MultiActionsF.java:21`; the attack sets `entity` at `:60`, the placement sets `block` at `:43`, both
     * are cleared only on a tick packet at `:74`), and the resulting `attack -> use item` with no `INTERACT_ENTITY`
     * between them is `PacketOrderJ` ("Sent use item after attacking without the expected interaction packet",
     * `PacketOrderJ.java:12, 24`). The window closes on the movement packet, or on `CLIENT_TICK_END` when the tick
     * sent no movement (`GrimProcessor.java:80-91`) - and our clicks always precede both, so the pair lands inside
     * one window every single time.
     *
     * On servers without those checks it roughly doubles the rate; it is the module's one deliberate multi-action.
     */
    val sameTick by boolean("SameTick", false)

    /**
     * Only allow [sameTick] while the player is standing still, and drop back to one action per tick the moment
     * they move.
     *
     * This is an EMPIRICAL mitigation, not a legalisation: the pair is just as illegal standing as moving - the
     * window is one client tick either way, since our clicks precede both the movement packet and
     * `CLIENT_TICK_END`. What changes is the rate. Standing, the aura runs far fewer cycles per second, so the
     * violations stay under the alert threshold; moving, they pile up and the check reports. Playtesting on a Grim
     * server produced exactly that split, which is why the mode exists.
     *
     * "Standing" is deliberately strict and asymmetric: any per-tick movement or sprinting disables it at once,
     * and it takes [SAME_TICK_CALM_TICKS] calm ticks to come back, so a single step cannot flip it back and forth.
     */
    val sameTickSafe by boolean("SameTickSafe", true)
}

/**
 * Break settings: how far and how often crystals are attacked, id prediction, ownership filtering,
 * i-frame handling and how long to wait before attacking an unremoved crystal again.
 */
class BreakSettings : ValueGroup("Break") {

    /** Maximum distance from the eye to the hit point on the crystal box. */
    val range by float("Range", 3f, 1f..6f)

    /** Minimum time between two attacks. */
    val delay by int("Delay", 0, 0..1000, "ms")

    /** Attack our own placement by its predicted entity id before the spawn packet arrives. */
    val idPredict by boolean("IdPredict", false)

    /** Only attack crystals placed by this module. */
    val onlyOwn by boolean("OnlyOwn", false)

    /** Whether the damage model honours the victim's invulnerability window. */
    val iFrames by enumChoice("IFrames", IFrameMode.RESPECT)

    /** Ticks before an attacked but still present crystal is attacked again; 0 = derived from the ping. */
    val retryTicks by int("RetryTicks", 0, 0..40, "ticks")

    /**
     * Detonate even while vanilla's own attack lockout is running.
     *
     * `Minecraft.startAttack` sets `missTime = 10` whenever a left click resolves to `MISS` - which includes a click
     * on an air block, through the deliberate `case BLOCK:` fall-through (`client/Minecraft.java:1710-1725`) - and
     * `Minecraft.tick` decrements it once per tick, and only while no screen is open
     * (`:1863-1869`). `Minecraft.startAttack` refuses to attack at all while it is positive (`:1674-1675`), and this
     * module mirrored that refusal.
     *
     * The cost of mirroring it is severe in kitpvp: one whiffed click freezes detonation for half a second. It does
     * NOT freeze placement, so the aura places a crystal it then cannot pop, and `TickPlanner.wastedByOwnBlast`
     * suppresses every further placement inside that crystal's blast - the whole aura stalls on a single miss.
     *
     * `missTime` is a purely client-side input lockout: `ServerGamePacketListenerImpl.handleAttack` has no analogue
     * of it, so an attack sent while it runs is an ordinary, correct packet, and no check reads it. Turning this off
     * restores the vanilla-faithful behaviour at the cost of that stall.
     */
    val ignoreMissTime by boolean("IgnoreMissTime", true)
}

/**
 * Speed envelope of [RotationMode.SMOOTH]: how fast the aim turns towards a look point, how fast it hands the view
 * back to the player, and when the hand-back counts as finished. All of it is dead weight in [RotationMode.SNAP],
 * which is why it sits in its own node.
 */
class SmoothRotationSettings : ValueGroup("Smooth") {

    /** Upper turn speed per tick. */
    val maxSpeed by float("MaxSpeed", 60f, 1f..180f, "°")

    /** Lower turn speed per tick. */
    val minSpeed by float("MinSpeed", 15f, 1f..180f, "°")

    /** Fraction of the remaining angle covered per tick. */
    val accel by float("Accel", 0.6f, 0.05f..1f)

    /** Turn speed per tick when returning to the player's own look direction. */
    val returnSpeed by float("ReturnSpeed", 30f, 1f..180f, "°")

    /** Angle below which the aim counts as reset to the player's own look direction. */
    val resetThreshold by float("ResetThreshold", 1f, 0.1f..5f, "°")
}

/**
 * Rotation settings: what the aim does ([mode], [grid]), how it behaves on the wire ([movementCorrection],
 * [holdTicks]), and - one node deeper - the [RotationMode.SMOOTH] speed envelope ([smooth]). The smooth values are
 * re-exposed as read-only accessors so callers do not care where they are registered.
 */
class RotationSettings : ValueGroup("Rotation") {

    /** Snap onto the look point or turn towards it with limited speed. */
    val mode by enumChoice("Mode", RotationMode.SNAP)

    /** Allowed deviation of a look point from the exact aim, in mouse-grid steps. */
    val grid by enumChoice("Grid", GridMode.DITHER)

    /** Ticks the rotation is kept after the last action. */
    val holdTicks by int("HoldTicks", 5, 1..20, "ticks")

    /** Movement correction applied while the aim is overridden. */
    val movementCorrection by enumChoice("MovementCorrection", MovementCorrection.SILENT)

    /** The [RotationMode.SMOOTH] speed envelope. */
    val smooth = tree(SmoothRotationSettings())

    val maxSpeed: Float
        get() = smooth.maxSpeed

    val minSpeed: Float
        get() = smooth.minSpeed

    val accel: Float
        get() = smooth.accel

    val returnSpeed: Float
        get() = smooth.returnSpeed

    val resetThreshold: Float
        get() = smooth.resetThreshold
}

/**
 * Base placement (off by default): place an obsidian base first when a crystal on it would deal at least
 * [minGain] percent more damage than the best direct placement; [range] is the eye-to-hit-point limit for
 * placing the obsidian.
 *
 * [mine] is the same idea one step earlier: when the base cell is not free yet, break what is in the way first.
 */
class BasePlaceSettings(parent: EventListener) : ToggleableValueGroup(parent, "BasePlace", false) {

    /** Minimum damage gain over the best direct placement, in percent. */
    val minGain by float("MinGain", 20f, 0f..200f, "%")

    /** Maximum distance from the eye to the hit point when placing the obsidian; see `PlaceSettings.range`
     *  for why 4.5 is a ceiling and not a default. */
    val range by float("Range", 4.5f, 1f..5.5f)

    /** Mine the cells that stand between us and a better base. */
    val mine = tree(MineSettings(this))
}

/**
 * AutoMine (off by default): break the one or two blocks that stand between us and an obsidian base, so that a
 * crystal can be placed where the terrain does not currently allow one.
 *
 * The two situations this exists for are the two the user named: an enemy standing on flat ground, where breaking
 * the ground block next to them and dropping the obsidian into that hole puts the crystal at FOOT level, and an
 * enemy underground, where the 1x1x1 pocket the crystal needs has to be carved out first.
 *
 * Why foot level is worth a dig: `ServerExplosion.hurtEntities` measures to the victim's FEET
 * (`net/minecraft/world/level/ServerExplosion.java:182-183` → `Entity.distanceToSqr`, which uses `getY()`, the
 * feet, while the eye position at `:184` only steers the knockback), so a crystal one block lower has a strictly
 * SMALLER `distanceFraction` and therefore deals more. On flat ground the gain is far larger than that term alone
 * suggests, because a base placed beside the enemy at their own level occludes its own crystal: the obsidian
 * spans the block below the explosion centre and cuts the lowest rows of `getSeenPercent`'s sample grid. Measured
 * for a victim at `(0.5, 64.0, 0.5)` with 20 armour / 8 toughness: base at `y-1` gives exposure ~1.0, raw 74.8,
 * ~62.8 effective; base beside them at `y` gives exposure ~0.4, raw 21.1, ~8.6 effective.
 *
 * How many cells that is follows from `EndCrystalItem.useOn`
 * (`net/minecraft/world/item/EndCrystalItem.java:22-57`) and from what an obsidian placement needs: the base cell
 * must be replaceable by obsidian (or already be obsidian / bedrock, `EndCrystalItem.java:27`) and the cell above it
 * must be TRUE AIR (`:30-32` through `LevelReader.isEmptyBlock` = `getBlockState(pos).isAir()`,
 * `net/minecraft/world/level/LevelReader.java:85-87`). There is no block check above that, none on the horizontal
 * neighbours and no test against the crystal's own `sized(2.0F, 2.0F)` hitbox, so a fully enclosed pocket works and
 * two cells are always enough — hence the [maxBlocks] default of 2.
 *
 * Digging is slow and mutually exclusive with everything else the aura does (a tick that carries a dig packet
 * carries nothing else — Grim's `PacketOrderI.java:98-123` and `MultiActionsF.java:79-91`), which is what [minGain]
 * and [interrupt] exist to pay for.
 */
class MineSettings(parent: EventListener) : ToggleableValueGroup(parent, "AutoMine", false) {

    /**
     * What breaks the cells (2026-10-02, the user's ask: "while the packet SpeedMine works you can place crystals,
     * blocks and so on").
     *
     * [MineEngine.VANILLA] drives `continueDestroyBlock` every tick, and every one of those ticks is the dig's:
     * `PacketOrderI` counts the swing-only middle ticks as well, so the aura neither places nor attacks for the whole
     * dig. [MineEngine.SPEED_MINE] hands the cell to `ModuleSpeedMine`'s packet mining, which needs exactly two dig
     * packets: the START and the STOP. Only the ticks that carry them (and the aim that leads to them) are the
     * dig's; the aura keeps every tick in between - see `PacketMineJob`.
     *
     * Needs `SpeedMine` enabled in `Packet` mode. Without it [requireSpeedMine] decides what happens, and a hint
     * says which.
     *
     * Declared ABOVE [interrupt] and [requireSpeedMine], which it hides: safe because the hook fires only from
     * `Value.set`, never while the group is being constructed, and the `init` below covers the initial state
     * (AGENTS.md, "Settings style").
     */
    val engine by enumChoice("Engine", MineEngine.SPEED_MINE)
        .onChanged { syncEngineVisibility(it) }

    private val requireSpeedMineValue = boolean("RequireSpeedMine", true)

    /**
     * [MineEngine.SPEED_MINE] only: with `ModuleSpeedMine` unable to dig for the aura (switched off, or on but not in
     * `Packet` mode, or with NCPAir rebreak - `ModuleSpeedMine.auraCompatible`), skip AutoMine altogether
     * and plan as if it were off, instead of digging the vanilla way (2026-10-03, the user's ask). Off restores that
     * fallback to [MineEngine.VANILLA], which owns every tick of the dig.
     */
    val requireSpeedMine by requireSpeedMineValue

    /**
     * How many cells may be cleared to open ONE base.
     *
     * 1 is the flat-ground case (break the ground block next to the enemy, the obsidian goes into the hole), 2 is
     * the enclosed case (base cell and the cell above it are both solid). Nothing above 2 is ever needed for a
     * crystal — the item checks exactly those two cells — so 3 only exists as headroom.
     */
    val maxBlocks by int("MaxBlocks", 2, 1..3)

    /**
     * How much better, in percent, the mined placement must be than the best placement available RIGHT NOW.
     *
     * The floor is `best.damage.effective * (1 + MinGain / 100)`, the shape `BasePlaceSettings.minGain` uses. It is
     * higher than the base-place default because a dig costs time - and with [MineEngine.VANILLA] whole ticks in which
     * the aura can neither place nor attack - so a mined spot must be clearly worth waiting for and must never
     * displace something we can do now.
     */
    val minGain by float("MinGain", 30f, 0f..200f, "%")

    /**
     * Maximum eye-to-hit-point distance for the DIG (not for the placement, which keeps
     * [BasePlaceSettings.range]).
     *
     * The server-side bound is `Player.isWithinBlockInteractionRange`, and Grim's `FarBreak` measures eye to block
     * AABB against `BLOCK_INTERACTION_RANGE`; the box distance used for the pre-cut is the lower bound of the real
     * hit-point distance, so a value at or below the server's own limit is always safe.
     */
    val range by float("Range", 4.5f, 1f..5.5f)

    /**
     * How the mining tool is brought into the hand: server-side only ([SwitchMode.SILENT]), as a real hotbar
     * change ([SwitchMode.NORMAL]) or not at all ([SwitchMode.NONE], dig with whatever is held).
     *
     * The tool decides the dig time — `Player.getDestroySpeed` reads the SELECTED hotbar slot
     * (`net/minecraft/world/entity/player/Player.java:584-614`), and the wrong tool is a 3.33x penalty (the `30` vs
     * `100` divisor of `BlockBehaviour.getDestroyProgress`,
     * `net/minecraft/world/level/block/state/BlockBehaviour.java:356-364`), not a hard stop. The switch must be
     * announced BEFORE the dig packet, which `MultiPlayerGameMode.continueDestroyBlock` does by itself
     * (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:224`, `ensureHasSentCarriedItem()` first).
     */
    val tool by enumChoice("Tool", SwitchMode.SILENT)

    /**
     * Give up on a cell that has cost the aura this many ticks.
     *
     * A cell can simply be too slow to be worth it: obsidian with the wrong tool is about 167 ticks, and with the
     * vanilla engine the aura would spend every one of them unable to attack or place. This is the hard ceiling;
     * the real cost of a two-cell pocket is `dig + 300 ms + dig`, the `FINISHED_DIGGING` to `START_DIGGING` pacing
     * Grim's `FastBreak` requires.
     *
     * Same meaning with [MineEngine.SPEED_MINE], where only the ticks the dig TAKES from the aura are counted (the
     * aim and the START, the aim and the STOP); how long the block itself takes is bounded by the dig's own
     * predicted time instead (`PacketMineJob`).
     */
    val maxTicks by int("MaxTicks", 40, 5..200, "ticks")

    private val interruptValue = boolean("Interrupt", true)

    /**
     * Abort the dig the moment the aura has a placement or an attack it can execute right now.
     *
     * Digging must never cost a crystal that was already available. With this off the job keeps its ticks until the
     * cell is broken or [maxTicks] runs out. Vanilla engine only: the SpeedMine engine takes so few ticks that it
     * simply lets a ready action go first, at most a couple of ticks in a row (`PacketMineJob`).
     */
    val interrupt by interruptValue

    init {
        syncEngineVisibility(engine)
    }

    private fun syncEngineVisibility(current: MineEngine) {
        interruptValue.notAnOption(current != MineEngine.VANILLA)
        requireSpeedMineValue.notAnOption(current != MineEngine.SPEED_MINE)
    }
}

/**
 * Render settings of the "Detonation" overlay: which of its parts are drawn and in which colours.
 *
 * The parts are independent - the boxes are the plan, the ring is the target's kill zone, the shockwave is the
 * history of our own attacks and the text is the numbers behind the decision.
 *
 * Every part is animated (`render/AuraAnim.kt`); [animationSpeed] scales all of it at once.
 *
 * HOW they are drawn is the second block of settings, from [soft] down: angle-weighted faces, graded fills,
 * feathered outlines and a distance falloff, all of it plain geometry in `render/SoftGeometry.kt`. Those change
 * only the look, never which elements exist, and [soft] switches the whole block back to the original flat boxes
 * and one-pixel outlines.
 */
class RenderSettings : ValueGroup("Render") {

    /** Draw the planned placement, base and attack boxes. */
    val box by boolean("Box", true)

    /** Face colour of the placement box; the base box uses it at half alpha. */
    val fillColor by color("Fill", Color4b(0, 255, 0, 40))

    /** Outline colour of the placement and base boxes. */
    val outlineColor by color("Outline", Color4b(0, 255, 0, 180))

    /**
     * Draw the blast ring on the ground at the target's feet: a band at the kill-zone radius, i.e. the largest
     * distance at which a fully exposed crystal would still deal at least [PlaceSettings.minDamage] to this target.
     * Nothing is drawn when even a point-blank crystal misses that threshold.
     */
    val blastRing by boolean("BlastRing", true)

    /**
     * Cut the blast ring into one arc per crystal the target still survives
     * (`ceil((health + absorption) / damage)`, at most 12). A single remaining crystal makes the whole ring pulse.
     */
    val killSegments by boolean("KillSegments", true)

    /** Fill the sector of the ring in which the target's raised shield eats our crystals entirely. */
    val shieldWedge by boolean("ShieldWedge", true)

    /** Expanding ground ripple at every crystal whose attack packet really went out. */
    val shockwave by boolean("Shockwave", true)

    /**
     * Extra flourish on every ripple: a ring of short vertical ticks orbiting the wave, lifted from the
     * competitor client's target ESP (`rockstar-client-src/src/main/java/rockstar/client/TargetEspModule.java:
     * 186-200` — points at `sin/cos(toRadians(angle + t * 0.3)) * radius * scale`, height `0.1 + f * |sin(angle)|`).
     * Off by default; the plain ripple is the quieter read.
     */
    val orbit by boolean("Orbit", false)

    /** Target damage and self damage of the planned placement, drawn over its block. */
    val damageText by boolean("DamageText", true)

    /**
     * Let VANILLA play its own explosion effect for crystal-sized blasts - the flash, the boom and the block
     * debris. On by default, because turning it off hides something the server actually told the client.
     *
     * This is the one setting here that suppresses vanilla rather than adding to it. At the rate this module
     * detonates, `ClientPacketListener.handleExplosion`
     * (`net/minecraft/client/multiplayer/ClientPacketListener.java:1362-1380`) fires several times a second, and
     * each one costs a full-screen `explosion_emitter` particle, a 4.0-volume sound and a debris burst through
     * `ClientLevel.trackExplosionEffects` - together they white out the screen and drown every other cue in a
     * fight.
     *
     * Only those three are dropped. `packet.playerKnockback()` is applied exactly as vanilla does: it is
     * gameplay, not decoration, and dropping it would desync the player from the server.
     *
     * Scoped by RADIUS, not by ownership. `ClientboundExplodePacket` carries `radius` and an end crystal is
     * always `ExplosionMath.POWER` (6.0), so a TNT blast at 4.0 and a respawn anchor at 5.0 keep their effects
     * and only crystal-sized ones go quiet. The packet says nothing about what exploded or who did it, so
     * another player's crystal is hidden too - which is the useful reading anyway, since their crystals blind
     * you exactly as much as ours do.
     */
    val explosionEffect by boolean("ExplosionEffect", true)

        /** Ring colour when one crystal barely scratches the target. */
    val ringCold by color("RingCold", Color4b(80, 170, 255, 170))

    /** Ring colour when one crystal nearly empties the target's health and absorption. */
    val ringHot by color("RingHot", Color4b(255, 90, 60, 200))

    /** Fill colour of the shield wedge, the arc in which our damage is fully blocked. */
    val shieldColor by color("ShieldColor", Color4b(255, 210, 60, 110))

    /** Colour of the expanding attack ripples. */
    val shockwaveColor by color("ShockwaveColor", Color4b(255, 255, 255, 180))

    /** Outline colour of the crystal that will be attacked, and of a placement that kills outright. */
    val attackColor by color("AttackColor", Color4b(255, 80, 80, 200))

    /**
     * Divides the duration of EVERY tween of the overlay (`AuraAnim.speed`): `2.0` makes the whole overlay settle
     * in half the time, `0.5` doubles it. It is applied when a tween is read, so moving this while an animation is
     * running takes effect on that animation too.
     */
    val animationSpeed by float("AnimationSpeed", 1f, 0.25f..3f, "x")

    /**
     * Draw the whole overlay through the soft geometry path (`render/SoftGeometry.kt`): angle-weighted faces,
     * graded fills, feathered outlines and a distance falloff.
     *
     * Turning it OFF restores the original look exactly - flat one-tone box faces and one-pixel `Lines` outlines
     * (`render/RenderShortcuts.kt:321-339`) - which is what every setting below stops affecting.
     */
    val soft by boolean("Soft", true)

    /**
     * Thickness of every outline in BLOCKS, not pixels: box edges, and the orbit's ticks at three quarters of it.
     * A world-space width means a line keeps its weight as you walk towards it instead of staying a hairline.
     */
    val outlineWidth by float("OutlineWidth", 0.02f, 0.005f..0.08f)

    /**
     * How much of a band's half-width is spent fading out, `0..1`. `0` is a solid ribbon that still has a hard
     * side; `1` is a pure triangular falloff with no solid centre; the default keeps a core with a soft shoulder.
     * The same number softens the inner and outer rim of the blast ring, the kill arcs and the shield wedge.
     */
    val feather by float("Feather", 0.5f, 0f..1f)

    /**
     * Sharpness of the grazing-angle highlight on box faces. Raising it squeezes the bright rim against the
     * silhouette, which reads as glassier and thinner; `0` switches the effect off and paints every face flat.
     */
    val fresnel by float("Fresnel", 1.4f, 0f..4f)

    /**
     * How much alpha a face keeps when you look STRAIGHT at it, `0..1`. This is the setting that decides whether
     * a box looks like a solid volume (low: only the grazing faces glow) or like a sticker (`1`: uniform paint).
     */
    val faceMin by float("FaceMin", 0.15f, 0f..1f)

    /**
     * Alpha at the top edge of a face relative to its bottom edge. Below `1` the fill fades upwards so no face is
     * one flat tone, `1` is a constant tone, and above `1` the gradient runs the other way and fades downwards.
     */
    val gradient by float("Gradient", 0.35f, 0f..2f)

    /** Distance in blocks at which the overlay starts fading, so distant marks stop shouting. */
    val fadeStart by float("FadeStart", 24f, 0f..128f)

    /** Distance in blocks at which it has faded out completely; smoothstep in between, so nothing pops. */
    val fadeEnd by float("FadeEnd", 64f, 0f..256f)

    /**
     * Soft additive halo around everything the module draws: the geometry is rendered a second time into an
     * offscreen target, blurred and composited underneath itself. This is what the Halo ESP does, and the reason
     * it is the only overlay in the client that reads as light rather than as a decal.
     */
    val glow by boolean("Glow", true)

    /** Halo reach, 0..1 - drives how deep the blur pyramid goes and how wide it is upsampled. */
    val glowRadius by float("GlowRadius", 0.55f, 0f..1f)

    /** Halo brightness. Higher blows the rim out to white; 0 leaves the sharp geometry alone. */
    val glowStrength by float("GlowStrength", 1.35f, 0f..4f)

    /** Exponent on the blurred coverage: ~1 is air and haze, ~3 is a thin bright rim. */
    val glowFalloff by float("GlowFalloff", 1.2f, 0.2f..4f)

    /** Opacity of the whole halo layer; 0 skips the offscreen target entirely. */
    val glowAlpha by float("GlowAlpha", 0.85f, 0f..1f)

    /**
     * Extra alpha the geometry is drawn with WHEN IT IS ROUTED INTO THE HALO, and only then.
     *
     * `AuraGlow` derives its halo from the colours of the geometry handed to it, exactly like the Halo ESP: the
     * silhouette is blurred and composited back, so the halo is only ever as bright as what was drawn into it.
     * That is a problem for this module specifically, because two of its own defaults conspire against it - the
     * face colour [fillColor] is alpha 40 of 255, and [faceMin] then scales a face seen head-on down to another
     * 15% of that. The blur therefore receives something very close to black and the halo has nothing to work
     * with, which is why the glow was invisible in game no matter what [glowStrength] was set to.
     *
     * This multiplies the alpha of the glow pass ONLY (`SoftStyle.alphaGain`, applied in
     * `render/SoftGeometry.kt` at the single place a colour's alpha is resolved): the world pass keeps drawing at
     * exactly the alpha you chose, while the halo gets a source it can actually blur. Raising [fillColor]'s own
     * alpha instead would work too, but it would also make the geometry itself heavier - which is the look the
     * soft path exists to avoid.
     */
    val glowGain by float("GlowGain", 3f, 1f..8f, "x")

    /**
     * Screen-space shockwave: a world-anchored dome that REFRACTS the scene behind it instead of drawing a ring,
     * occluded by real geometry through the depth buffer. Costs one full-screen pass per frame while a dome is
     * live.
     */
    val dome by boolean("Dome", true)

    /** Overall displacement multiplier; 0 leaves the scene untouched. */
    val domeStrength by float("DomeStrength", 1f, 0f..3f)

    /** Peak displacement of the refraction, in screen fractions. */
    val domeAmplitude by float("DomeAmplitude", 0.026f, 0f..0.12f)

    /** How many concentric waves fill the dome. */
    val domeRipples by float("DomeRipples", 3f, 0.5f..8f)

    /**
     * The forge: the block the aura is spamming crystals on, drawn as something being worked rather than as a
     * marker. Every detonation adds heat to that site, the heat decays over about a second, and everything the
     * effect draws is scaled by it - so one crystal is a flicker and a sustained cycle lights the site up.
     *
     * This is the world-space half: an ADDITIVE pool on the ground, its feathered rim, three slowly turning
     * spokes and a crown of rising embers (`render/AuraForge.kt`). Additive because it has to read at a glance
     * whatever [fillColor] is set to, which by default is alpha 40.
     */
    val forge by boolean("Forge", true)

    /**
     * The screen-space half: a world-anchored heat plume over the site that displaces the scene the way hot air
     * does, with a vertical channel split for hot glass (`shaders/aura/forge_haze.fsh`, ported from
     * the competitor's `heat_haze`). This is the expensive one - two full-screen passes per frame while any site
     * is warm - so it has its own toggle; the pool above stays.
     */
    val forgeHaze by boolean("ForgeHaze", true)

    /** Displacement and tint of the plume. `0` skips the passes entirely and leaves the pool on its own. */
    val forgeStrength by float("ForgeStrength", 1f, 0f..3f)

    /** Footprint of a site in blocks: the pool's radius, and the width of the plume before it spreads upwards. */
    val forgeRadius by float("ForgeRadius", 2.5f, 0.5f..8f)

    /** Colour of a site that has just been hit once. */
    val forgeCold by color("ForgeCold", Color4b(80, 170, 255, 150))

    /** Colour of a site under a sustained cycle; past that the ramp pulls towards white-hot. */
    val forgeHot by color("ForgeHot", Color4b(255, 140, 40, 210))
}
