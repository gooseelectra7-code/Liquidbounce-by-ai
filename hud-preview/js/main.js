/* Boot, the frame loop and the controls. */

const canvas = document.getElementById("hud");
const ctx = canvas.getContext("2d");
const frame = document.getElementById("frame");

Paint.ctx = ctx;
State.selSmooth = State.self.selectedSlot;
State.ringParticles = [];

let scene = null;
let lastFrame = performance.now();
let fpsAcc = 0;
let fpsFrames = 0;

// ── boot ─────────────────────────────────────────────────────────────────────
(async function boot() {
  await Promise.all([
    loadAssets(ASSET_NAMES),
    new Promise((resolve) => {
      scene = new Image();
      scene.onload = resolve;
      scene.onerror = resolve;
      scene.src = "assets/scene.jpg";
    }),
    document.fonts ? document.fonts.load("600 12px InterHud").then(() => document.fonts.load("500 12px InterHud")) : null,
  ]);

  applyUrlOverrides();
  buildBlur();
  buildElementToggles();
  wireControls();
  requestAnimationFrame(loop);
})();

/** ?target=ring&style=leet&lang=en — handy for grabbing a specific look without clicking. */
function applyUrlOverrides() {
  const params = new URLSearchParams(location.search);
  const target = params.get("target");
  if (target && ["glass", "dimasik", "ring"].includes(target)) {
    State.targetMode = target;
    document.querySelectorAll('[data-bind="targetMode"] button').forEach((b) => {
      b.classList.toggle("on", b.dataset.value === target);
    });
  }
  const style = params.get("style");
  if (style && STYLES[style]) {
    State.style = style;
    Paint.style = STYLES[style];
    document.querySelectorAll('[data-bind="style"] button').forEach((b) => {
      b.classList.toggle("on", b.dataset.value === style);
    });
  }
  const lang = params.get("lang");
  if (lang && Lang[lang]) {
    State.lang = lang;
    document.querySelectorAll('[data-bind="lang"] button').forEach((b) => {
      b.classList.toggle("on", b.dataset.value === lang);
    });
  }
  if (params.get("abs")) {
    State.target.absorption = parseFloat(params.get("abs"));
  }
}

/**
 * The blurred copy of the scene the glass panels sample, the preview's stand-in for
 * WatermarkGlass — the same idea: blur once per frame size, then clip it to each panel.
 */
function buildBlur() {
  const blur = document.createElement("canvas");
  blur.width = 1920;
  blur.height = 1080;
  const bctx = blur.getContext("2d");
  if (scene && scene.naturalWidth) {
    bctx.filter = "blur(14px)";
    bctx.drawImage(scene, 0, 0, 1920, 1080);
    bctx.filter = "none";
  } else {
    bctx.fillStyle = "#101821";
    bctx.fillRect(0, 0, 1920, 1080);
  }
  Paint.blurCanvas = blur;
}

// ── input ────────────────────────────────────────────────────────────────────
const KEY_MAP = {
  KeyW: "w", KeyA: "a", KeyS: "s", KeyD: "d", Space: "space",
  KeyЦ: "w", KeyФ: "a", KeyЫ: "s", KeyВ: "d",
};

window.addEventListener("keydown", (event) => {
  const key = KEY_MAP[event.code];
  if (key) {
    State.keys[key] = true;
    if (event.code === "Space") event.preventDefault();
  }
});
window.addEventListener("keyup", (event) => {
  const key = KEY_MAP[event.code];
  if (key) State.keys[key] = false;
});

frame.addEventListener("mousedown", (event) => {
  event.preventDefault();
  if (event.button === 0) {
    State.keys.lmb = true;
    State.clicks.left.push(performance.now());
    State.hitTarget();
    spawnRingBurst();
  } else if (event.button === 2) {
    State.keys.rmb = true;
    State.clicks.right.push(performance.now());
  }
});
window.addEventListener("mouseup", (event) => {
  if (event.button === 0) State.keys.lmb = false;
  if (event.button === 2) State.keys.rmb = false;
});
frame.addEventListener("contextmenu", (event) => event.preventDefault());

frame.addEventListener("wheel", (event) => {
  event.preventDefault();
  const direction = Math.sign(event.deltaY);
  State.self.selectedSlot = (State.self.selectedSlot + direction + 9) % 9;
  State.self.heldItemShownAt = Paint.time;
  State.self.heldItemName = HOTBAR_NAMES[State.self.selectedSlot];
}, { passive: false });

const HOTBAR_NAMES = [
  "Тотем бессмертия", "Незеритовый меч", "Незеритовая кирка", "Кристалл Энда",
  "Обсидиан", "Золотое яблоко", "Пузырёк опыта", "Жемчуг Энда", "Паутина",
];

// ── simulation ───────────────────────────────────────────────────────────────
function spawnRingBurst() {
  const tipAngle = -90 + 360 * State.target.ringPct;
  for (let i = 0; i < 14; i++) {
    if (State.ringParticles.length >= 64) break;
    const life = 0.55 + Math.random() * 0.65;
    State.ringParticles.push({
      angle: tipAngle + (Math.random() - 0.5) * 44,
      speed: (90 + Math.random() * 260) * (Math.random() < 0.22 ? -1 : 1),
      radial: (Math.random() - 0.5) * 4,
      drift: (Math.random() - 0.35) * 9,
      size: 1.5 + Math.random() * 2.3,
      color: hsl(Math.random(), 0.45 + Math.random() * 0.3, 1),
      maxLife: life,
      life,
    });
  }
}

function hsl(h, s, v) {
  const i = Math.floor(h * 6);
  const f = h * 6 - i;
  const p = v * (1 - s);
  const q = v * (1 - f * s);
  const t = v * (1 - (1 - f) * s);
  const table = [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]];
  return table[i % 6].map((c) => Math.round(c * 255));
}

function update(dt) {
  const target = State.target;

  // key press factors: quick in, softer out — the same speeds the module uses
  for (const id of Object.keys(State.press)) {
    const pressed = State.keys[id];
    const speed = pressed ? 28 : 12;
    const goal = pressed ? 1 : 0;
    State.press[id] += (goal - State.press[id]) * (1 - Math.exp(-speed * dt));
  }

  // target bars: the fill chases the value, the ghost lags behind it
  const pct = Math.max(0, Math.min(1, target.health / target.maxHealth));
  target.displayedPct += (pct - target.displayedPct) * Math.min(1, dt * 10);
  target.ghostPct = target.displayedPct > target.ghostPct
    ? target.displayedPct
    : target.ghostPct + (target.displayedPct - target.ghostPct) * Math.min(1, dt * 2.5);
  const ringGoal = Math.max(0, Math.min(1, (target.health + target.absorption) / target.maxHealth));
  target.ringPct += (ringGoal - target.ringPct) * (1 - Math.pow(1e-5, dt));
  if (target.hurtTime > 0) target.hurtTime = Math.max(0, target.hurtTime - dt * 20);

  // ring burst
  for (let i = State.ringParticles.length - 1; i >= 0; i--) {
    const particle = State.ringParticles[i];
    particle.life -= dt;
    if (particle.life <= 0) {
      State.ringParticles.splice(i, 1);
      continue;
    }
    particle.angle += particle.speed * dt;
    particle.speed *= Math.max(0, 1 - dt * 1.6);
    particle.radial += particle.drift * dt;
  }

  // walking: the coordinates and BPS the Info panel reads
  const self = State.self;
  const moving = State.keys.w || State.keys.a || State.keys.s || State.keys.d;
  const speed = moving ? 5.6 : 0;
  self.bps += (speed - self.bps) * Math.min(1, dt * 6);
  if (self.bps > 0.01) {
    const rad = (self.yaw * Math.PI) / 180;
    self.x += Math.sin(rad) * self.bps * dt;
    self.z -= Math.cos(rad) * self.bps * dt;
    if (State.keys.a) self.yaw -= 40 * dt;
    if (State.keys.d) self.yaw += 40 * dt;
  }

  // hotbar selection glides between slots
  State.selSmooth += (self.selectedSlot - State.selSmooth) * (1 - Math.exp(-18 * dt));

  // the one timed effect actually counts down and loops, so the drain fill is alive
  const timed = State.effects.find((effect) => !effect.infinite);
  if (timed) {
    timed.seconds -= dt;
    if (timed.seconds <= 0) timed.seconds = 100;
    timed.fraction = timed.seconds / 100;
  }
}

function draw() {
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, 1920, 1080);
  if (scene && scene.naturalWidth) {
    ctx.drawImage(scene, 0, 0, 1920, 1080);
  } else {
    ctx.fillStyle = "#0d141b";
    ctx.fillRect(0, 0, 1920, 1080);
  }

  // crosshair, so the target card has something to sit next to
  ctx.fillStyle = "rgba(255, 255, 255, 0.75)";
  ctx.fillRect(1920 / 2 - 1, 1080 / 2 - 8, 2, 16);
  ctx.fillRect(1920 / 2 - 8, 1080 / 2 - 1, 16, 2);

  ctx.setTransform(GUI_SCALE, 0, 0, GUI_SCALE, 0, 0);

  const on = State.elements;
  if (on.watermark) Elements.watermark();
  if (on.arrayList) Elements.arrayList();
  if (on.effects) Elements.effects();
  if (on.scoreboard) Elements.scoreboard();
  if (on.keystrokes) Elements.keystrokes();
  if (on.pvpIntel) Elements.pvpIntel();
  if (on.info) Elements.info();
  if (on.armor) Elements.armor();
  if (on.counters) Elements.counters();
  if (on.itemBinds) Elements.itemBinds();
  if (on.autoSwap) Elements.autoSwap();
  if (on.hotbar) Elements.hotbar();
  if (on.targetHud) TargetHud.draw();
  if (on.notifications) Elements.notifications();
}

function loop(now) {
  const dt = Math.min(0.1, (now - lastFrame) / 1000);
  lastFrame = now;
  Paint.time = now / 1000;
  State.time = Paint.time;

  fpsAcc += dt;
  fpsFrames++;
  if (fpsAcc >= 0.5) {
    State.fps = Math.round(fpsFrames / fpsAcc);
    document.getElementById("metaFps").textContent = `${State.fps} fps`;
    fpsAcc = 0;
    fpsFrames = 0;
  }

  update(dt);
  draw();
  requestAnimationFrame(loop);
}

// ── controls ─────────────────────────────────────────────────────────────────
const ELEMENT_LABELS = {
  watermark: "Watermark", targetHud: "TargetHud", effects: "Effects", arrayList: "ArrayList",
  keystrokes: "Keystrokes", info: "Info", scoreboard: "Scoreboard", armor: "Armor",
  hotbar: "Hotbar", counters: "Counters", pvpIntel: "PvpIntel", itemBinds: "ItemBinds",
  autoSwap: "AutoSwap", notifications: "Notifications",
};

function buildElementToggles() {
  const host = document.getElementById("elementToggles");
  for (const [key, label] of Object.entries(ELEMENT_LABELS)) {
    const wrapper = document.createElement("label");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = State.elements[key];
    input.addEventListener("change", () => { State.elements[key] = input.checked; });
    wrapper.append(input, document.createTextNode(label));
    host.append(wrapper);
  }
}

function syncTargetSliders() {
  const hp = document.getElementById("targetHp");
  const abs = document.getElementById("targetAbs");
  if (!hp || !abs) return;
  hp.value = State.target.health.toFixed(1);
  abs.value = State.target.absorption.toFixed(1);
  document.getElementById("hpValue").textContent = State.target.health.toFixed(1);
  document.getElementById("absValue").textContent = State.target.absorption.toFixed(1);
}

function wireControls() {
  document.querySelectorAll(".seg").forEach((seg) => {
    seg.addEventListener("click", (event) => {
      const button = event.target.closest("button");
      if (!button) return;
      seg.querySelectorAll("button").forEach((b) => b.classList.toggle("on", b === button));
      const bind = seg.dataset.bind;
      const value = button.dataset.value;
      if (bind === "style") {
        State.style = value;
        Paint.style = STYLES[value];
      } else if (bind === "lang") {
        State.lang = value;
      } else if (bind === "targetMode") {
        State.targetMode = value;
      }
    });
  });

  document.getElementById("optBlur").addEventListener("change", (e) => {
    Paint.blurEnabled = e.target.checked;
  });
  document.getElementById("optShadow").addEventListener("change", (e) => {
    Paint.shadowEnabled = e.target.checked;
  });

  document.getElementById("targetHp").addEventListener("input", (e) => {
    State.target.health = parseFloat(e.target.value);
    document.getElementById("hpValue").textContent = State.target.health.toFixed(1);
  });
  document.getElementById("targetAbs").addEventListener("input", (e) => {
    State.target.absorption = parseFloat(e.target.value);
    document.getElementById("absValue").textContent = State.target.absorption.toFixed(1);
  });

  document.getElementById("hitTarget").addEventListener("click", () => {
    State.hitTarget();
    spawnRingBurst();
  });
  document.getElementById("popTotem").addEventListener("click", () => {
    State.popTotem();
    spawnRingBurst();
  });

  let toggleState = true;
  document.getElementById("toggleModule").addEventListener("click", () => {
    const names = ["KillAura", "Speed", "Scaffold", "AutoCrystal", "Fly"];
    const name = names[Math.floor(Math.random() * names.length)];
    toggleState = !toggleState;
    State.pushNotification(
      name,
      toggleState ? State.t("enabled") : State.t("disabled"),
      toggleState ? "enable" : "disable",
    );
  });

  document.getElementById("killEnemy").addEventListener("click", () => {
    State.target.health = 0;
    State.target.absorption = 0;
    State.pushNotification("KillEffect", `${State.target.name}`, "info");
    spawnRingBurst();
    syncTargetSliders();
    setTimeout(() => {
      State.target.health = 20;
      State.target.combo = 0;
      syncTargetSliders();
    }, 2200);
  });
}
