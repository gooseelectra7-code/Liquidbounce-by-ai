/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Faithful data transcription of the SystemDLC item taxonomy. Shared library used by
 * the SystemDLC render ports (TntTimer, ItemColor, ItemNametag, ItemRadius, ItemTracker,
 * NameTags second mode); no module registration of its own.
 *
 * Source (SystemDLC client, MC 1.21.4 Yarn):
 *   - `client/enums/TrackedItem.java`  (the 36-entry enum + `getTrackedItemByInt` /
 *     `getTrackedItemByInt2`), reproduced 1:1 below.
 *   - `client/enums/ServerFlag.java`   (the NONE/FT/HW/ANY flag).
 *
 * EVERY constant is copied verbatim from the source: the mapped item, the Russian
 * display [text], the lowercase name/lore match strings ([text2]/[text3] -> [text4]/
 * [text5]), the potion custom-color id [value], the totem custom-model-data id [value2]
 * (talismans 1..8), the [flag]/[flag2] booleans and the [serverFlag]. Declaration ORDER
 * is preserved exactly because it decides which of two overlapping matches wins
 * (see `SystemDlcItems.resolve`). Russian strings are byte-exact Cyrillic (verified: no
 * Latin homoglyphs present in the source data).
 *
 * Yarn->Mojmap item mapping: all 21 distinct `Items.*` constants used here carry
 * identical names in both mapping sets (verified via javap on
 * `minecraft-merged.jar` for 26.1.2). `Item` = net.minecraft.world.item.Item.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.systemdlc

import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/**
 * SystemDLC server-context flag (`client/enums/ServerFlag.java`). Declaration order
 * preserved: NONE, FT, HW, ANY.
 */
enum class ServerFlag {
    NONE,
    FT,
    HW,
    ANY,
}

/**
 * The 36 SystemDLC tracked items (`client/enums/TrackedItem.java`).
 *
 * Field meanings (from the Java source):
 *  - [item]       the vanilla item this entry keys off (Mojmap).
 *  - [text]       Russian display name.
 *  - [text2]      raw display-name match substring (may be null).
 *  - [text3]      raw lore-line match substring (may be null).
 *  - [text4]      `text2.lowercase()` — the name matcher actually compared against.
 *  - [text5]      `text3.lowercase()` — the lore matcher actually compared against.
 *  - [flag]       Java `flag` — always false in every declared entry (kept for fidelity).
 *  - [value]      potion custom-color id, or -1 (SPLASH_POTION custom color matcher).
 *  - [value2]     totem custom-model-data id, or -1 (talismans 1..8).
 *  - [flag2]      Java `flag2` — the talisman "sacrificial" bit.
 *  - [serverFlag] which server family the entry belongs to.
 *
 * The primary constructor mirrors the canonical Java constructor
 * (`TrackedItem(item, text6, text7, text8, flag3, count, count2, flag4, serverFlag2)`);
 * the secondary constructors reproduce the Java convenience overloads verbatim so each
 * entry line reads the same as the source.
 */
@Suppress("MagicNumber")
enum class TrackedItem private constructor(
    @JvmField val item: Item,
    @JvmField val text: String,
    @JvmField val text2: String?,
    @JvmField val text3: String?,
    @JvmField val flag: Boolean,
    @JvmField val value: Int,
    @JvmField val value2: Int,
    @JvmField val flag2: Boolean,
    @JvmField val serverFlag: ServerFlag,
) {
    ENDER_EYE(Items.ENDER_EYE, "Дезориентация", "дезориентация", "звуковая волна", ServerFlag.FT),
    FIRE_CHARGE(Items.FIRE_CHARGE, "Огненный смерч", "огненный", null, ServerFlag.ANY),
    SUGAR(Items.SUGAR, "Явная пыль", "явная пыль", "световая вспышка", ServerFlag.FT),
    TOTEM_OF_UNDYING(Items.TOTEM_OF_UNDYING, "Тотем бессмертия", null),
    EXPERIENCE_BOTTLE(Items.EXPERIENCE_BOTTLE, "Бутылка опыта", null),
    NETHERITE_SCRAP(Items.NETHERITE_SCRAP, "Трапка", "трапка", "нерушимая клетка", ServerFlag.FT),
    DRIED_KELP(Items.DRIED_KELP, "Пласт", "пласт", "нерушимая стена", ServerFlag.FT),
    GOLDEN_APPLE(Items.GOLDEN_APPLE, "Золотое яблоко", null),
    ENCHANTED_GOLDEN_APPLE(Items.ENCHANTED_GOLDEN_APPLE, "Зач. золотое яблоко", null),
    CHORUS_FRUIT(Items.CHORUS_FRUIT, "Хорус", null),
    ENDER_PEARL(Items.ENDER_PEARL, "Эндер пёрл", null),
    SNOWBALL(Items.SNOWBALL, "Снежок", null),
    SNOWBALL_FREEZE(Items.SNOWBALL, "Снежок заморозка", "заморозка", "ледяная сфера"),
    POPPED_CHORUS_FRUIT(Items.POPPED_CHORUS_FRUIT, "Трапка 2", null, null, ServerFlag.HW),
    NETHER_STAR(Items.NETHER_STAR, "Стан", null, null, ServerFlag.HW),
    JACK_O_LANTERN(Items.JACK_O_LANTERN, "Светильник Джека", null),
    PRISMARINE_SHARD(Items.PRISMARINE_SHARD, "Взрывная трапка", null, null, ServerFlag.HW),
    WIND_CHARGE(Items.WIND_CHARGE, "Заряд вихря", null),
    BOZHA_AURA(Items.PHANTOM_MEMBRANE, "Божья аура", "божья аура", "божественная аура", ServerFlag.FT),
    CROSSBOW(Items.CROSSBOW, "Арбалет", "арбалет"),
    SHULKER(Items.SHULKER_BOX, "Шалкер", "ящик"),
    POTION_POPPER(Items.SPLASH_POTION, "Хлопушка", null, 16738740, -1),
    SVYATAYA_VODA(Items.SPLASH_POTION, "Святая вода", "святая вода", 16777215, -1),
    POTION_GNEVA(Items.SPLASH_POTION, "Зелье Гнева", "зелье гнева", 10040115, -1),
    POTION_PALADINA(Items.SPLASH_POTION, "Зелье Палладина", "зелье палладина", 65535, -1),
    POTION_ASSASINA(Items.SPLASH_POTION, "Зелье Ассасина", "зелье ассасина", 3355443, -1),
    POTION_RADIACII(Items.SPLASH_POTION, "Зелье Радиации", "зелье радиации", 3329330, -1),
    SNOTVORNOE(Items.SPLASH_POTION, "Снотворное", "снотворное", 255, -1),
    TALIC_MRAKA(Items.TOTEM_OF_UNDYING, "Талисман Мрака", null, -1, 1, true),
    TALIC_VIHRYA(Items.TOTEM_OF_UNDYING, "Талисман Вихря", null, -1, 2, true),
    TALIC_DEMONA(Items.TOTEM_OF_UNDYING, "Талисман Демона", null, -1, 3, true),
    TALIC_RAZDORA(Items.TOTEM_OF_UNDYING, "Талисман Раздора", null, -1, 4, true),
    TALIC_YAROSTI(Items.TOTEM_OF_UNDYING, "Талисман Ярости", null, -1, 5, false),
    TALIC_KRUSHITELYA(Items.TOTEM_OF_UNDYING, "Талисман Крушителя", null, -1, 6, false),
    TALIC_KARATELYA(Items.TOTEM_OF_UNDYING, "Талисман Карателя", null, -1, 7, false),
    TALIC_TIRANA(Items.TOTEM_OF_UNDYING, "Талисман Тирана", null, -1, 8, true),
    ;

    /** `text4 = text2 == null ? null : text2.toLowerCase()` — lowercase display-name matcher. */
    @JvmField
    val text4: String? = text2?.lowercase()

    /** `text5 = text3 == null ? null : text3.toLowerCase()` — lowercase lore matcher. */
    @JvmField
    val text5: String? = text3?.lowercase()

    // --- Java convenience constructor overloads, reproduced verbatim ---

    /** Java ctor `(Item, text, text2, int count, int count2)` — potion/totem id, no lore/server flag. */
    private constructor(item: Item, text: String, text2: String?, count: Int, count2: Int) :
        this(item, text, text2, null, false, count, count2, false, ServerFlag.NONE)

    /** Java ctor `(Item, text, text2, text3, ServerFlag)` — name+lore match with a server flag. */
    private constructor(item: Item, text: String, text2: String?, text3: String?, serverFlag: ServerFlag) :
        this(item, text, text2, text3, false, -1, -1, false, serverFlag)

    /** Java ctor `(Item, text, text2, text3)` — name+lore match, no server flag. */
    private constructor(item: Item, text: String, text2: String?, text3: String?) :
        this(item, text, text2, text3, false, -1, -1, false, ServerFlag.NONE)

    /** Java ctor `(Item, text, text2, int count, int count2, boolean flag)` — talisman (flag == flag2). */
    private constructor(item: Item, text: String, text2: String?, count: Int, count2: Int, flag: Boolean) :
        this(item, text, text2, null, false, count, count2, flag, ServerFlag.NONE)

    /** Java ctor `(Item, text, text2)` — plain name match (or null), no ids/lore/server flag. */
    private constructor(item: Item, text: String, text2: String?) :
        this(item, text, text2, null, false, -1, -1, false, ServerFlag.NONE)

    companion object {

        /**
         * `TrackedItem.getTrackedItemByInt` — first entry whose totem custom-model-data id
         * ([value2]) equals [count]. Called with a real totem CMD float (talismans 1..8);
         * returns null when nothing matches. Iterates in declaration order.
         */
        @JvmStatic
        fun getTrackedItemByInt(count: Int): TrackedItem? =
            entries.firstOrNull { it.value2 == count }

        /**
         * `TrackedItem.getTrackedItemByInt2` — first entry whose potion custom-color id
         * ([value]) equals [count]. Called with a real potion custom color; returns null when
         * nothing matches. Iterates in declaration order.
         */
        @JvmStatic
        fun getTrackedItemByInt2(count: Int): TrackedItem? =
            entries.firstOrNull { it.value == count }
    }
}
