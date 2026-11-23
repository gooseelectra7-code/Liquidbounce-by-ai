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
package net.ccbluex.liquidbounce.features.module.modules.combat.autoanchor

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
     * (`utils/aiming/data/Rotation.kt:107-128`), so the rotation that actually goes out is NOT bit-identical to
     * the one the aura requested. Every interaction is verified against the rotation this tick's movement packet
     * carries, exactly - so when the dither pushes the ray off the block the same-tick attempt simply falls back
     * to the next tick. The anticheat-friendlier default: an exact grid is what Grim's AimProcessor keys on.
     */
    DITHER("Dither"),

    /**
     * The requested delta is pre-quantised to whole gcd counts and marked `isNormalized`, which turns
     * `normalize()` into a no-op (`utils/aiming/data/Rotation.kt:107-108`) - no dither is added. The rotation that
     * leaves is therefore bit-identical to the requested one, and the same-tick verification is deterministic.
     */
    EXACT("Exact")
}

/**
 * How the item a phase needs is brought into the hand.
 *
 * Every phase of an anchor cycle needs a different item (`Items.RESPAWN_ANCHOR` to place, `Items.GLOWSTONE` to
 * charge, anything that is not glowstone to detonate), and only ONE `ServerboundSetCarriedItemPacket` may go out
 * per tick - `MultiPlayerGameMode` emits it at the tick head through `ensureHasSentCarriedItem()`
 * (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:224`), so the slot for a tick's interaction is
 * chosen before the tick, never in the middle of it.
 */
enum class SwitchMode(override val tag: String) : Tagged {
    /** Server-side slot change only (silent hotbar); the client keeps showing the current item. */
    SILENT("Silent"),

    /** Real hotbar slot change, visible on the client. */
    NORMAL("Normal"),

    /** Never switch: only act while the needed item is already held. */
    NONE("None")
}

/**
 * Which entities the pick ray may pass through when aiming at an anchor, or at the block an anchor is placed
 * against.
 *
 * The crystal aura's third level (`Crystals`) has no analogue here: it existed because an end crystal we are about
 * to attack still stands in front of its own base client-side. An anchor is a block, so nothing of ours ever
 * occludes the click.
 */
enum class LookThrough(override val tag: String) : Tagged {
    /** Vanilla pick: any pickable entity in front of the block cancels the interaction. */
    OFF("Off"),

    /** Every entity is ignored by the pick. */
    ALL("All")
}

/**
 * Which item the detonating click is made with.
 *
 * The block takes the click before the item does: `MultiPlayerGameMode.performUseItemOn`
 * (`net/minecraft/client/multiplayer/MultiPlayerGameMode.java:333-357`) calls `blockState.useItemOn(...)` at
 * `:347` and only falls through to the held item afterwards. For a non-fuel item
 * `RespawnAnchorBlock.useItemOn` returns `TRY_WITH_EMPTY_HAND`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:93-95`), which routes straight into `useWithoutItem`
 * (`:352-354`) and detonates - so the detonating hand may hold almost anything.
 *
 * Almost. Two items are not free:
 * - Glowstone charges instead of detonating (`isRespawnFuel`, `RespawnAnchorBlock.java:125-127`).
 * - The respawn anchor itself is fine in the normal case (`useWithoutItem` returns `CONSUME`, which
 *   `consumesAction()`, so `performUseItemOn` returns before reaching `itemStack.useOn`), but NOT in the desync
 *   window where our charge click has not been applied server-side yet: the server then sees `CHARGE == 0`,
 *   `useWithoutItem` returns `PASS` (`:101`), and the item's `useOn` places a STRAY anchor against the clicked
 *   face - exactly on top of the geometry the search just scored.
 */
enum class DetonateHand(override val tag: String) : Tagged {

    /**
     * A TOTEM from the hotbar when there is one, otherwise the first hotbar item that cannot consume the click -
     * the inverse of the "does this item consume a use" test the executor already owns. A desynced detonate is
     * then a harmless no-op instead of a stray anchor. The default because the failure it prevents is silent and
     * ruins the next cycle's scoring.
     *
     * The totem preference costs at most one extra `SetCarriedItem` and buys a life: the blast is as often on top
     * of us as on the victim, and `LivingEntity.checkTotemDeathProtection` reads the MAIN hand first. It is keyed
     * on the `DEATH_PROTECTION` component rather than the item id, and the "cannot consume the click" filter
     * still applies to it. See `AnchorJob.safeSlot`.
     */
    SAFE("Safe"),

    /** Keep the respawn anchor in hand (one slot switch less per cycle, at the cost of the stray placement). */
    ANCHOR("Anchor"),

    /** Detonate with whatever is held; refuse the phase while that is glowstone. */
    HELD("Held")
}

/**
 * What the damage model assumes about a remote victim's armour when the server hides part of it.
 *
 * A victim's armour reaches the client through two different channels, and a server can obfuscate them
 * independently. The armour POINTS come from the equipment the server sends (`ClientboundSetEquipmentPacket`) plus
 * `Attributes.ARMOR`, which is syncable (`net/minecraft/world/entity/ai/attributes/Attributes.java:10-13`). The
 * PROTECTION enchantments are read off those same stacks by `EnchantProtection` - so a server that strips
 * enchantment components from the equipment packet leaves the model believing every opponent is unenchanted, and
 * the predicted damage comes out far too high.
 *
 * Every level here SUBSTITUTES the local player's own values for the victim's - it does not take the better of the
 * two, which is where this differs from [AnchorSettings.assumeResistance]. A Resistance effect the client never
 * receives is genuinely absent from its view, so assuming a minimum can only correct an over-estimate. A rewritten
 * enchantment list is not a missing reading but a false one: the usual obfuscation leaves a decoy enchantment on
 * the stack so the item still glints, and that decoy can be a Protection level the victim does not have. Treated
 * as a lower bound, a fake Protection IV would survive the comparison and talk the aura out of acting at all.
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
     * Also substitute our armour points and toughness. The right level when the server hides the equipment itself,
     * which leaves `getArmorValue()` reading 0.
     */
    FULL("Full")
}

/**
 * Whether the damage model honours the victim's vanilla invulnerability window (i-frames).
 *
 * `bad_respawn_point` is in NONE of the bypass tags - the merged jar's
 * `data/minecraft/tags/damage_type/bypasses_invulnerability.json` does not list it - so the window of
 * `LivingEntity.hurtServer` (`net/minecraft/world/entity/LivingEntity.java:1197-1202`) applies to an anchor blast
 * exactly as it does to a crystal's.
 */
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
 * The anchor settings: how far and how often anchors are cycled, the damage thresholds a cycle has to meet, what
 * the model assumes about a victim the server hides, target prediction and how the items reach the hand.
 *
 * The defaults that differ from the crystal aura's differ for one reason - an anchor explodes INSIDE its own cell.
 * `RespawnAnchorBlock.explode` passes `pos.getCenter()` as the centre
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:168-169`), i.e. `(x + 0.5, y + 0.5, z + 0.5)`, while
 * an end crystal's centre sits a full block above its pedestal. The cell beside a victim's feet is therefore
 * reachable in a way no crystal placement is, which raises both the damage the module can expect AND the damage it
 * takes.
 */
class AnchorSettings : ValueGroup("Anchor") {

    /**
     * Maximum distance from the eye to the hit point on the clicked block face.
     *
     * Same value and same reasoning as the crystal aura: the server bound is
     * `Player.isWithinBlockInteractionRange(pos, 1.0)` (`net/minecraft/world/entity/player/Player.java:1980`,
     * checked at `ServerGamePacketListenerImpl.java:1321`), which is looser than anything selectable here, so the
     * setting is a self-imposed ceiling rather than a limit to be probed.
     */
    val range by float("Range", 4.5f, 1f..5.5f)

    /**
     * Minimum time between two block interactions.
     *
     * `0` because the cycle already throttles itself: place, charge and detonate are three `UseItemOn` clicks of
     * ONE packet class, and Grim's `PacketOrderI` allows one of those per tick, so a full cycle cannot run faster
     * than three ticks whatever this says. It exists to slow the module down further, never to speed it up.
     */
    val delay by int("Delay", 0, 0..1000, "ms")

    /**
     * Minimum effective damage a cycle must deal to the target.
     *
     * Higher than the crystal aura's 6: a cycle costs up to three ticks, one respawn anchor and one glowstone,
     * where a crystal costs two ticks and one crystal. Paying that for a 6-damage hit is a bad trade, and the
     * geometry usually offers better - for a victim standing at `(0.5, 64, 0.5)` the foot-adjacent cell
     * `(1, 64, 0)` puts the centre 1.118 blocks from their feet, raw 59.7 of a possible 71.
     */
    val minDamage by float("MinDamage", 8f, 0f..40f)

    /**
     * Maximum effective damage a cycle may deal to the local player.
     *
     * Deliberately left at the crystal aura's 6 rather than raised. At anchor range the blast is as often on top
     * of us as on the victim, and this is the gate that will bind most of the time; the answer to "it refuses
     * everything" is the damage-model diagnostic first, not a looser gate.
     */
    val maxSelfDamage by float("MaxSelfDamage", 6f, 0f..40f)

    /** Never detonate an anchor whose explosion would be lethal for the local player. */
    val antiSuicide by boolean("AntiSuicide", true)

    /** Accept a cycle that is lethal for the target even when it fails the damage thresholds. */
    val lethalOverride by boolean("LethalOverride", true)

    /**
     * How much better, in percent, a cell that needs a NEW anchor must be than the best cell that already holds
     * one.
     *
     * The two candidate kinds are not comparable at face value. An anchor already standing with `CHARGE >= 1` is a
     * SINGLE interaction and costs nothing; a fresh cell is three interactions, one respawn anchor and one
     * glowstone, and the three clicks cannot share a tick. So a new cell has to be worth the difference, not
     * merely tie it - the shape of the crystal aura's `BasePlaceSettings.minGain`, applied here between candidate
     * KINDS instead of between "direct" and "with a base".
     */
    val newAnchorGain by float("NewAnchorGain", 25f, 0f..200f, "%")

    /**
     * Charge an anchor that is not charged yet.
     *
     * Turning it off restricts the module to anchors that already carry `CHARGE >= 1`
     * (`RespawnAnchorBlock.java:101`: `useWithoutItem` requires `CHARGE != 0`), i.e. detonate-only on anchors
     * somebody else charged. It belongs in the search's ammo test rather than in a later check, because a cell we
     * would place an anchor on and then never charge is worthless: refusing it in the search is what stops the
     * module proposing cycles the sequencer keeps rejecting.
     */
    val recharge by boolean("Recharge", true)

    /**
     * Act even where the dimension says respawn anchors WORK, i.e. set spawn instead of exploding.
     *
     * The primary gate is `Level.respawnAnchorWorks` (`utils/world/WorldExtensions.kt:47-48`), which reads
     * `EnvironmentAttributes.RESPAWN_ANCHOR_WORKS` off the DIMENSION. Vanilla's values are overworld `false`,
     * nether `true`, end `false` (`net/minecraft/world/level/dimension/DimensionTypes.java:39, 89, 125`), so
     * "explodes here" is `!respawnAnchorWorks`. The client may legitimately read it:
     * `ClientLevel` builds its own `EnvironmentAttributeSystem` (`ClientLevel.java:246`) and vanilla itself calls
     * `getDimensionValue` client-side (`net/minecraft/world/level/Level.java:740`).
     *
     * What the client CANNOT evaluate is the POSITIONAL form `canSetSpawn(serverLevel, pos)`
     * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:172-174`), so a server that varies the attribute
     * per position - or simply lies - takes the module offline with no other symptom. This is the escape hatch;
     * `AnchorLedger`'s dud latch is the backstop for the opposite error. `ModuleAnchorOptimizer` carries the same
     * setting for the same reason, and AGENTS.md records it as the first thing to try when the module is silent
     * on one server only.
     */
    val anyDimension by boolean("AnyDimension", false)

    /** Which item the detonating click is made with; see [DetonateHand]. */
    val detonateHand by enumChoice("DetonateHand", DetonateHand.SAFE)

    /**
     * Resistance level assumed for a victim whose real effect the client cannot see; `0` = assume none.
     *
     * The server sends `ClientboundUpdateMobEffectPacket` only to the affected player's own connection
     * (`ServerPlayer.onEffectAdded` / `onEffectUpdated`, `net/minecraft/server/level/ServerPlayer.java:1638-1655`)
     * and to players riding the entity (`LivingEntity.sendEffectToPassengers`, `LivingEntity.java:1069-1075`) -
     * never to trackers. `getEffect(RESISTANCE)` is therefore always null for a remote victim and the damage model
     * over-estimates it by 1.25x per level, up to predicting full damage against a victim that takes zero at
     * Resistance V. It is only ever a fallback and never lowers an effect the client can actually see.
     */
    val assumeResistance by int("AssumeResistance", 0, 0..5)

    /**
     * Armour a remote victim is assumed to have when the server hides it; see [ArmorAssumption].
     *
     * Reach for this on servers that obfuscate enchantments: without it every opponent is scored as unenchanted,
     * the predicted damage is far above the real one, and [minDamage] lets through cycles that do not deliver.
     */
    val assumeArmor by enumChoice("AssumeArmor", ArmorAssumption.OFF)

    /**
     * Whether the damage model honours the victim's invulnerability window; see [IFrameMode].
     *
     * Not in the original settings table, but `DamageCalculator` takes the flag in its constructor and something
     * has to supply it. [IFrameMode.RESPECT] is the modelling-correct default: the tag audit shows the window
     * really does apply to `bad_respawn_point`.
     */
    val iFrames by enumChoice("IFrames", IFrameMode.RESPECT)

    /**
     * Act on blocks that other blocks hide: click an anchor cell without a line of sight.
     *
     * Nothing on the server side objects. `ServerGamePacketListenerImpl.handleUseItemOn` validates only
     * `isWithinBlockInteractionRange(pos, 1.0)` and that the hit location sits within +-1.0000001 of the block
     * centre per axis; it never traces the world between the eye and the block. Grim does not either:
     * `RotationPlace.didRayTraceHit` (`checks/impl/scaffolding/RotationPlace.java:83-134`) builds a
     * `SimpleCollisionBox` of the TARGET position alone and returns true as soon as
     * `ReachUtils.calculateIntercept` meets that one box.
     *
     * What it costs, stated plainly: this is the one action in this module a vanilla client cannot produce. Its
     * crosshair picks the FIRST block along the ray (`Entity.pick` with `ClipContext.Block.OUTLINE`), so a real
     * player can never click an occluded one. Hence opt-in and off by default.
     */
    val throughBlocks by boolean("ThroughBlocks", false)

    /**
     * Which entities the pick ray may pass through; see [LookThrough].
     *
     * Defaults to [LookThrough.OFF], stricter than the crystal aura's default, because the crystal aura only
     * needed a looser setting to see past its own crystals and an anchor never has that problem.
     */
    val lookThrough by enumChoice("LookThrough", LookThrough.OFF)

    /**
     * Target position prediction for the damage evaluation.
     *
     * ONE prediction horizon, not the crystal aura's two: an anchor's damage is decided at a single moment, the
     * detonation, so there is no second snapshot to keep. The honest limitation is the other way round - a cycle
     * entered at PLACE detonates up to three ticks later, and this horizon does not lengthen with the phase.
     */
    val predict by enumChoice("Predict", PredictMode.AUTO)

    /** Extrapolation length used by [PredictMode.FIXED]. */
    val predictTicks by int("PredictTicks", 2, 0..10, "ticks")

    /** How the item a phase needs is brought into the hand; see [SwitchMode]. */
    val switchMode by enumChoice("Switch", SwitchMode.SILENT)

    /**
     * Ticks the switched slot stays selected before swapping back.
     *
     * `0` keeps the switch as short as the crystal aura's. A full cycle switches up to three times anyway, once
     * per phase, so a long hold buys nothing here.
     */
    val swapBack by int("SwapBack", 0, 0..20, "ticks")
}

/**
 * The cycle budget: how long one place/charge/detonate sequence may run and how the module notices that its
 * detonations do nothing.
 *
 * The detonation is the one step the client cannot predict. The client branch of
 * `RespawnAnchorBlock.useWithoutItem` is `return InteractionResult.CONSUME`
 * (`net/minecraft/world/level/block/RespawnAnchorBlock.java:120-122`) and `ClientLevel.explode` has an empty body
 * (`net/minecraft/client/multiplayer/ClientLevel.java:1045-1060`), so the anchor stays standing on screen until
 * the server's block update arrives. Everything in this group exists because of that blind window.
 */
class CycleSettings : ValueGroup("Cycle") {

    /**
     * Ticks one cycle may run before it is abandoned.
     *
     * A cycle is three interactions at one per tick, so three ticks is the floor and everything above is slack for
     * re-validation misses (the ray no longer lands, the slot was not free, the tick already carried a click).
     * 20 is one second: past that the world has changed enough that re-scanning beats waiting.
     *
     * Abandoning is free, which is what makes a tight budget safe: a use has no ABORT packet, and an abandoned
     * cycle leaves a standing charged anchor, which is worth MORE than it cost. That is the opposite of an
     * abandoned crystal cycle.
     */
    val maxTicks by int("MaxTicks", 20, 3..100, "ticks")

    /**
     * Ticks a detonated cell stays excluded from the search; `0` derives it from the ping.
     *
     * After the detonate click the anchor is still visible locally (class KDoc), so without this the search
     * proposes the same anchor every tick until the server's removal arrives, and the module re-detonates a block
     * that is already gone. `0` means `ping + 2` ticks, the shape the crystal ledger uses for a chain-removed
     * crystal; a fixed value is for servers whose ping reading is useless.
     *
     * This is also the window inside which an explosion must be observed: `AnchorLedger` turns a pending
     * detonation that reaches this age with neither a `ClientboundExplodePacket` nor a vanished anchor into a
     * DUD. The two are one window on purpose - a cell whose detonation is still unexplained must stay hidden,
     * or the search re-proposes it and the module clicks it again.
     */
    val detonateTimeout by int("DetonateTimeout", 0, 0..40, "ticks")

    /**
     * Ticks a cell whose detonation produced no observable explosion stays excluded from the search.
     *
     * A dud is what a dimension where anchors do NOT explode looks like from the client: `canSetSpawn` is read
     * positionally on a `ServerLevel` (`RespawnAnchorBlock.java:172-174`) and the client can only consult the
     * DIMENSION-level `RESPAWN_ANCHOR_WORKS` value, which a server may set per position or simply lie about. Five
     * seconds is generous on purpose - this is a backstop for a wrong reading, not a latency budget.
     *
     * The positive signal is unambiguous when it comes: `ClientboundExplodePacket` is a record whose first field
     * is `Vec3 center`, broadcast to everyone within 64 blocks, and `badRespawnPointExplosion(boomPos)` puts that
     * same centre in the damage source (`net/minecraft/world/damagesource/DamageSources.java:278-280`), so a hit
     * can be attributed by exact comparison of the centre. One arriving late even retracts the verdict.
     */
    val dudTicks by int("DudTicks", 100, 20..400, "ticks")

    /**
     * How many duds in a row disable the module's detonation until the world changes.
     *
     * Two, not one: a single dud is explainable by a lost packet or by a block broken under us between the click
     * and the blast. Two in a row is a systematic answer, i.e. the dimension really does not explode anchors here,
     * and looping forever on that is both useless and loud.
     */
    val dudLimit by int("DudLimit", 2, 1..10)
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
 *
 * [holdTicks] matters more here than it does for the crystal aura. Grim's `DuplicateRotPlace`
 * (`checks/impl/scaffolding/DuplicateRotPlace.java:36-50`) arms only when the yaw delta between two rotation
 * updates exceeds 2 degrees (`if (deltaX > 2)`) and flags only when that delta REPEATS across consecutive
 * placements; clicking the same cell three ticks running with the aim HELD gives a delta of about zero and rewards
 * on every click. Re-aiming before each phase is the shape that trips it, so the aim is taken once and kept for
 * the whole cycle - which needs a hold long enough to span it.
 */
class RotationSettings : ValueGroup("Rotation") {

    /** Snap onto the look point or turn towards it with limited speed. */
    val mode by enumChoice("Mode", RotationMode.SNAP)

    /** Allowed deviation of a look point from the exact aim, in mouse-grid steps. */
    val grid by enumChoice("Grid", GridMode.DITHER)

    /** Ticks the rotation is kept after the last action; long enough to span a whole three-tick cycle. */
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
 * Diagnostics (off by default): the chat cross-check of the damage model against vanilla's own arithmetic, and the
 * per-tick outgoing packet trace.
 *
 * Both exist because of one lesson this module inherits: when the aura refuses everything, the first question is
 * whether the MODEL is wrong, not whether the gates are too tight. The cross-check answers that; the trace answers
 * the other half - that exactly one block interaction leaves per tick and that the slot change precedes it.
 */
class DebugSettings(parent: EventListener) : ToggleableValueGroup(parent, "Debug", false) {

    /**
     * Print every outgoing packet of a tick that carried one of ours, in order.
     *
     * This is the check that three `UseItemOn` clicks really are spread across three ticks (`PacketOrderI` allows
     * one per tick) and that the `SetCarriedItem` precedes the click in each of them.
     */
    val packets by boolean("Packets", true)
}

/**
 * The world overlay's knobs; `AnchorRenderer` reads them once per frame so the two passes it emits - the
 * geometry and the glow layer wrapped around it - can never disagree about a value.
 *
 * The soft/glow/dome blocks are the shared vocabulary of `combat/aura/render` and mean exactly what they mean
 * for the crystal aura, which is why they carry the same names and defaults. Everything above them is specific
 * to this module: the cycle is three interactions rather than two, an anchor has a charge, and the self-damage
 * gate binds far more often here than it ever does for a crystal.
 */
class AnchorRenderSettings : ValueGroup("Render") {

    /** Draw the cell mark at all: the box, the click target, the ribbon and the pips hang off this. */
    val box by boolean("Box", true)

    /** Face colour of the cell box. */
    val fillColor by color("Fill", Color4b(150, 90, 255, 40))

    /** Outline colour of the cell box. */
    val outlineColor by color("Outline", Color4b(170, 120, 255, 190))

    /** Outline of the block actually clicked, drawn only when it is not the cell (a relative placement). */
    val clickColor by color("ClickTarget", Color4b(120, 200, 255, 170))

    /** Three stacked bars above the cell - place, charge, detonate - plus the phase's countdown ring. */
    val cycleRibbon by boolean("CycleRibbon", true)

    /** Colour of the ribbon and of the countdown ring. */
    val ribbonColor by color("RibbonColor", Color4b(255, 200, 90, 220))

    /** Four notches reading the anchor's `CHARGE`, 0..4. */
    val chargePips by boolean("ChargePips", true)

    /** Colour of the charge notches. */
    val pipColor by color("PipColor", Color4b(255, 240, 160, 230))

    /**
     * The ring on the TARGET's feet: how far an anchor can stand and still deal `Place.MinDamage` to them.
     * Costs one bisection of pure arithmetic per frame and no raycasts at all (`render/AnchorDanger.kt`).
     */
    val dangerRings by boolean("DangerRings", true)

    /**
     * The ring on OUR feet: how close an anchor may come before it breaks `Place.MaxSelfDamage`.
     *
     * This is the one worth leaving on. At anchor range the blast is on top of the player as often as on the
     * victim, so "the module refuses everything" is usually "the player is standing in this ring" - and without
     * the ring drawn there is no way to tell that from "nothing is in range".
     */
    val selfDanger by boolean("SelfDanger", true)

    /** Target ring at a scratch. */
    val ringCold by color("RingCold", Color4b(80, 170, 255, 170))

    /** Target ring for a cell that empties the victim on its own. */
    val ringHot by color("RingHot", Color4b(255, 90, 60, 200))

    /** The self ring; red because it is the one that means "do not". */
    val selfRingColor by color("SelfRingColor", Color4b(255, 70, 70, 190))

    /** Damage figures over the cell, and the stand-down reason under them. */
    val damageText by boolean("DamageText", true)

    /** Tween-rate multiplier over every animation of the overlay. */
    val animationSpeed by float("AnimationSpeed", 1f, 0.25f..3f, "x")

    /** The volumetric look; `false` restores flat boxes and one-pixel outlines. */
    val soft by boolean("Soft", true)

    /** World-space width of an outline ribbon, in blocks. */
    val outlineWidth by float("OutlineWidth", 0.02f, 0.005f..0.08f)

    /** Fraction of a band's half-width spent fading. */
    val feather by float("Feather", 0.5f, 0f..1f)

    /** Exponent of the grazing-angle term; higher pulls the bright rim tighter against the silhouette. */
    val fresnel by float("Fresnel", 1.4f, 0f..4f)

    /** Alpha floor of a face seen exactly head-on - the term that makes a box read as a volume. */
    val faceMin by float("FaceMin", 0.15f, 0f..1f)

    /** Alpha multiplier at the top edge of a face relative to its bottom edge. */
    val gradient by float("Gradient", 0.35f, 0f..2f)

    /** Distance in blocks at which the whole drawing starts losing alpha. */
    val fadeStart by float("FadeStart", 24f, 0f..128f)

    /** Distance in blocks at which it is fully gone. */
    val fadeEnd by float("FadeEnd", 64f, 0f..256f)

    /** The soft halo under the geometry. */
    val glow by boolean("Glow", true)

    /** How far the halo reaches. */
    val glowRadius by float("GlowRadius", 0.55f, 0f..1f)

    /** Brightness of the halo. */
    val glowStrength by float("GlowStrength", 1.35f, 0f..4f)

    /** Higher hugs the geometry more tightly. */
    val glowFalloff by float("GlowFalloff", 1.2f, 0.2f..4f)

    /** Overall opacity of the halo layer. */
    val glowAlpha by float("GlowAlpha", 0.85f, 0f..1f)

    /**
     * Alpha multiplier applied to the geometry only in the GLOW pass. A fill at 40/255, scaled down again by the
     * Fresnel term, hands the blur a nearly black source and the halo has nothing to work with.
     */
    val glowGain by float("GlowGain", 3f, 1f..8f, "x")

    /** The screen-space refraction dome on a detonation. */
    val dome by boolean("Dome", true)

    /** Global multiplier over the dome's displacement and smear. */
    val domeStrength by float("DomeStrength", 1f, 0f..3f)

    /** Peak screen-space displacement, in UV units. */
    val domeAmplitude by float("DomeAmplitude", 0.026f, 0f..0.12f)

    /** How many concentric waves fill the dome. */
    val domeRipples by float("DomeRipples", 3f, 0.5f..8f)
}
