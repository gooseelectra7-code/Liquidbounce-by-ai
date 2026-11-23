/*
 * The game state the HUD reads. Values mirror a real session on the fork — the same modules,
 * effects, counters and hotbar the reference screenshot was taken with — so every panel has
 * something truthful to show instead of lorem filler.
 */

const Assets = {};

function loadAssets(list) {
  return Promise.all(list.map((name) => new Promise((resolve) => {
    const img = new Image();
    img.onload = () => { Assets[name] = img; resolve(); };
    img.onerror = () => { resolve(); };
    img.src = `assets/${name}.png`;
  })));
}

const ASSET_NAMES = [
  "logo", "glow", "skin_face",
  "item_netherite_helmet", "item_netherite_chestplate", "item_netherite_leggings",
  "item_netherite_boots", "item_netherite_sword", "item_netherite_pickaxe",
  "item_diamond_sword", "item_diamond_chestplate", "item_totem_of_undying",
  "item_end_crystal", "item_golden_apple", "item_experience_bottle", "item_ender_pearl",
  "item_obsidian", "item_glowstone", "item_cobweb", "item_elytra", "item_bow", "item_arrow",
  "heart_full", "heart_half", "heart_container", "heart_absorb_full", "heart_absorb_half",
  "food_full", "food_half", "food_empty", "armor_full", "air",
  "effect_speed", "effect_resistance", "effect_saturation", "effect_instant_health",
  "effect_strength", "effect_regeneration", "effect_fire_resistance", "effect_absorption",
];

const Lang = {
  ru: {
    fps: "фпс", ms: "мс", hp: "хп", m: "м", inf: "беск.",
    enabled: "включён", disabled: "выключен", notifications: "Уведомления",
    lmb: "ЛКМ", rmb: "ПКМ", bps: "БВС", win: "шанс", pops: "тотемы", gapples: "яблоки",
    armor: "броня", team: "ТИМЫ", eating: "ест", shield: "щит", combo: "комбо", hits: "удары",
    better: "лучше", worse: "хуже", even: "равно",
    effects: {
      speed: "Скорость", resistance: "Сопротивление", saturation: "Насыщенность",
      instant_health: "Исцеление", strength: "Сила", regeneration: "Регенерация",
      fire_resistance: "Огнестойкость", absorption: "Поглощение",
    },
    biome: "Равнина",
  },
  en: {
    fps: "fps", ms: "ms", hp: "hp", m: "m", inf: "inf",
    enabled: "enabled", disabled: "disabled", notifications: "Notifications",
    lmb: "LMB", rmb: "RMB", bps: "BPS", win: "win", pops: "pops", gapples: "gapples",
    armor: "armor", team: "TEAM", eating: "eating", shield: "shield", combo: "combo", hits: "hits",
    better: "better", worse: "worse", even: "even",
    effects: {
      speed: "Speed", resistance: "Resistance", saturation: "Saturation",
      instant_health: "Instant Health", strength: "Strength", regeneration: "Regeneration",
      fire_resistance: "Fire Resistance", absorption: "Absorption",
    },
    biome: "Plains",
  },
};

const State = {
  style: "classic",
  lang: "ru",
  targetMode: "glass",
  elements: {
    watermark: true, targetHud: true, effects: true, arrayList: true, keystrokes: true,
    info: true, scoreboard: true, armor: true, hotbar: true, counters: true,
    pvpIntel: true, itemBinds: true, autoSwap: true, notifications: true,
  },

  fontSize: 12,
  time: 0,
  fps: 0,

  self: {
    name: "cavestoru",
    health: 20, maxHealth: 20, absorption: 0,
    food: 17, saturation: 6, armorPoints: 20,
    air: 300, maxAir: 300,
    level: 30, xp: 0.62,
    ping: 21,
    x: -84.4, y: 64, z: 296.2, yaw: 137, bps: 0,
    selectedSlot: 1,
    heldItemName: "Незер. меч",
    heldItemShownAt: -9999,
    hotbar: [
      { icon: "item_totem_of_undying", count: 34 },
      { icon: "item_netherite_sword", count: 1, durability: 0.82 },
      { icon: "item_netherite_pickaxe", count: 1, durability: 0.61 },
      { icon: "item_end_crystal", count: 47 },
      { icon: "item_obsidian", count: 64 },
      { icon: "item_golden_apple", count: 12 },
      { icon: "item_experience_bottle", count: 9 },
      { icon: "item_ender_pearl", count: 16 },
      { icon: "item_cobweb", count: 21 },
    ],
    offhand: { icon: "item_totem_of_undying", count: 1 },
    armorItems: [
      { icon: "item_netherite_helmet", durability: 0.74 },
      { icon: "item_netherite_chestplate", durability: 0.55 },
      { icon: "item_netherite_leggings", durability: 0.91 },
      { icon: "item_netherite_boots", durability: 0.33 },
    ],
  },

  target: {
    name: "RediskaFPV",
    health: 17.5, maxHealth: 20, absorption: 0,
    distance: 3.4,
    hurtTime: 0,
    lastHitAt: -9999,
    displayedPct: 0.875,
    ghostPct: 0.875,
    ringPct: 0.875,
    armorItems: [
      { icon: "item_netherite_helmet", durability: 0.66 },
      { icon: "item_netherite_chestplate", durability: 0.42 },
      { icon: "item_netherite_leggings", durability: 0.88 },
      { icon: "item_netherite_boots", durability: 0.51 },
    ],
    mainHand: { icon: "item_netherite_sword", durability: 0.7 },
    offHand: { icon: "item_totem_of_undying", count: 1 },
    pops: 3,
    gapples: 2,
    combo: 4,
    hits: 11,
    winChance: 0.68,
    teaming: false,
    eating: false,
  },

  effects: [
    { key: "speed", amplifier: 1, infinite: true, fraction: 1 },
    { key: "resistance", amplifier: 3, infinite: true, fraction: 1 },
    { key: "saturation", amplifier: 0, infinite: true, fraction: 1 },
    { key: "instant_health", amplifier: 10, infinite: true, fraction: 1 },
    { key: "strength", amplifier: 1, infinite: false, seconds: 42, fraction: 0.42 },
  ],

  // a realistic active set — the whole list would run the ArrayList off the screen edge
  modules: [
    { name: "InventoryMove", tag: "StopOnAction" },
    { name: "CrystalOptimizer", tag: "" },
    { name: "SmoothCamera", tag: "" },
    { name: "NoJumpDelay", tag: "" },
    { name: "Criticals", tag: "Jump" },
    { name: "NoWeb", tag: "Strafe" },
    { name: "AutoWeapon", tag: "" },
    { name: "AutoClicker", tag: "" },
    { name: "AutoSwap", tag: "" },
    { name: "FullBright", tag: "" },
    { name: "ItemBind", tag: "" },
    { name: "Sprint", tag: "" },
  ],

  scoreboard: {
    title: "AnarchyMC",
    rows: [
      { text: "Ранг", value: "Legend" },
      { text: "Баланс", value: "128.4k" },
      { text: "Убийств", value: "1 204" },
      { text: "Смертей", value: "37" },
      { text: "K/D", value: "32.5" },
      { text: "Онлайн", value: "148" },
      { text: "TPS", value: "19.9" },
    ],
  },

  counters: [
    { icon: "item_totem_of_undying", count: 34 },
    { icon: "item_golden_apple", count: 12 },
    { icon: "item_end_crystal", count: 47 },
    { icon: "item_experience_bottle", count: 9 },
  ],

  itemBinds: [
    { icon: "item_end_crystal", key: "M3" },
    { icon: "item_netherite_pickaxe", key: "K" },
  ],

  autoSwap: { from: "item_totem_of_undying", to: "item_totem_of_undying", key: "X" },

  keys: { w: false, a: false, s: false, d: false, space: false, lmb: false, rmb: false },
  press: { w: 0, a: 0, s: 0, d: 0, space: 0, lmb: 0, rmb: 0 },
  clicks: { left: [], right: [] },

  notifications: [],
  hearts: { dying: [], popped: [] },

  t(key) {
    return Lang[this.lang][key];
  },

  effectName(key) {
    return Lang[this.lang].effects[key] ?? key;
  },

  pushNotification(title, message, kind) {
    this.notifications.push({
      title, message, kind,
      born: performance.now(),
      expire: performance.now() + 2500,
    });
    if (this.notifications.length > 6) this.notifications.shift();
  },

  hitTarget() {
    this.target.hurtTime = 10;
    this.target.lastHitAt = this.time;
    const damage = 2.5 + Math.random() * 3;
    this.target.health = Math.max(0, this.target.health - damage);
    this.target.combo += 1;
    this.target.hits += 1;
    syncTargetSliders();
  },

  popTotem() {
    this.target.pops += 1;
    this.target.health = 1;
    this.target.absorption = 8;
    this.pushNotification("TotemPopESP", `${this.target.name} — ${this.target.pops}`, "info");
    syncTargetSliders();
  },
};

/** Roman numerals the effect rows use, index 0 is the empty string for level I. */
const ROMAN = ["", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"];

function formatTime(date) {
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  return `${hh}:${mm}`;
}

function cardinal(yaw) {
  const dirs = State.lang === "ru"
    ? ["Ю", "ЮЗ", "З", "СЗ", "С", "СВ", "В", "ЮВ"]
    : ["S", "SW", "W", "NW", "N", "NE", "E", "SE"];
  const index = Math.round(((yaw % 360) + 360) % 360 / 45) % 8;
  return dirs[index];
}
