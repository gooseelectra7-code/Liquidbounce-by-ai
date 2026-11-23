/*
 * One function per HUD element, laid out with the constants from ModuleHud2.kt.
 *
 * Everything is in gui units. Anchors follow the module's own defaults: the corner each element
 * is pinned to and the offsets it ships with, so the preview is the HUD as it comes out of the box.
 */

const P = Paint;

function anchorX(corner, width, offset) {
  return corner.includes("right") ? SCREEN_W - offset - width : offset;
}

function anchorY(corner, height, offset) {
  return corner.includes("bottom") ? SCREEN_H - offset - height : offset;
}

/** Dark inset cell the item rows sit in. */
function itemCell(x, y, size, alpha = 1) {
  const ctx = P.ctx;
  const grad = ctx.createLinearGradient(0, y, 0, y + size);
  grad.addColorStop(0, `rgba(0, 0, 0, ${0.40 * alpha})`);
  grad.addColorStop(1, `rgba(0, 0, 0, ${0.26 * alpha})`);
  ctx.fillStyle = grad;
  P.path(x, y, size, size, 2);
  ctx.fill();
}

/** Item icon plus the badges vanilla draws on it: durability bar and stack count. */
function itemIcon(item, x, y, size, alpha = 1) {
  if (!item) return;
  P.sprite(Assets[item.icon], x, y, size, size, alpha);

  const unit = size / 16;
  if (item.durability !== undefined && item.durability < 1) {
    const ctx = P.ctx;
    const barX = x + 2 * unit;
    const barY = y + 13 * unit;
    ctx.fillStyle = `rgba(0, 0, 0, ${alpha})`;
    ctx.fillRect(barX, barY, 13 * unit, 2 * unit);
    const hue = item.durability * 120;
    ctx.fillStyle = `hsla(${hue}, 100%, 50%, ${alpha})`;
    ctx.fillRect(barX, barY, 13 * unit * item.durability, unit);
  }

  if (item.count > 1) {
    const text = String(item.count);
    const px = 8 * unit;
    const width = P.measure(text, px, 600);
    P.text(text, x + 17 * unit - width + 0.4, y + 9 * unit + 0.4, px, [63, 63, 63], 600, alpha);
    P.text(text, x + 17 * unit - width, y + 9 * unit, px, [255, 255, 255], 600, alpha);
  }
}

const Elements = {
  // ── Watermark ──────────────────────────────────────────────────────────────
  watermark() {
    const px = State.fontSize;
    const infoPx = px * 0.85;
    const brand = P.metrics(px, 600);
    const logo = brand.lineHeight;
    const title = "LiquidBounce";
    const brandWidth = P.measure(title, px, 600);

    const sections = [
      `${State.fps} ${State.t("fps")}`,
      `${State.self.ping} ${State.t("ms")}`,
      formatTime(new Date()),
    ];
    const widths = sections.map((s) => P.measure(s, infoPx, 500));

    let width = 7 + logo + 4.5 + brandWidth + 7;
    for (const w of widths) width += 5 + 0.8 + 5 + w;
    const height = brand.lineHeight + 9;

    const x = anchorX("topleft", width, 6);
    const y = anchorY("topleft", height, 6);

    const pulse = 0.8 + 0.2 * Math.sin(P.time * 2.2);
    const separators = [];
    let sx = 7 + logo + 4.5 + brandWidth;
    for (const w of widths) {
      sx += 5;
      separators.push(sx);
      sx += 0.8 + 5 + w;
    }

    P.ctx.save();
    P.ctx.translate(x, y);
    P.glass(0, 0, width, height, {
      separators,
      glow: {
        x: 7 + logo * 0.5, y: height * 0.5, radius: logo * 1.05 * pulse,
        color: P.accent(0.2), alpha: 0.30 * pulse,
      },
    });

    P.sprite(Assets.logo, 7, (height - logo) * 0.5, logo, logo);
    P.accentText(title, 7 + logo + 4.5, 4.5, px);

    const baseline = 4.5 + brand.ascent;
    const infoTop = baseline - P.metrics(infoPx, 500).ascent;
    let cursor = 7 + logo + 4.5 + brandWidth;
    sections.forEach((section, index) => {
      cursor += 5 + 0.8 + 5;
      P.text(section, cursor, infoTop, infoPx, P.style.info);
      cursor += widths[index];
    });
    P.ctx.restore();
  },

  // ── Info (bottom left) ─────────────────────────────────────────────────────
  info() {
    const px = State.fontSize;
    const infoPx = px * 0.85;
    const brand = P.metrics(px, 600);
    const self = State.self;

    const label = "XYZ";
    const labelWidth = P.measure(label, px, 600);
    const coords = `${self.x.toFixed(0)} ${self.y.toFixed(0)} ${self.z.toFixed(0)}`;
    const sections = [
      coords,
      `${State.t("bps")} ${self.bps.toFixed(1)}`,
      cardinal(self.yaw),
      Lang[State.lang].biome,
    ];
    const widths = sections.map((s) => P.measure(s, infoPx, 500));

    let width = 7 + labelWidth + 7;
    for (const w of widths) width += 5 + 0.8 + 5 + w;
    const height = brand.lineHeight + 9;

    const x = anchorX("bottomleft", width, 6);
    const y = anchorY("bottomleft", height, 6);

    const separators = [];
    let sx = 7 + labelWidth;
    for (const w of widths) {
      sx += 5;
      separators.push(sx);
      sx += 0.8 + 5 + w;
    }

    P.ctx.save();
    P.ctx.translate(x, y);
    P.glass(0, 0, width, height, { separators });
    P.accentText(label, 7, 4.5, px);

    const baseline = 4.5 + brand.ascent;
    const infoTop = baseline - P.metrics(infoPx, 500).ascent;
    let cursor = 7 + labelWidth;
    sections.forEach((section, index) => {
      cursor += 5 + 0.8 + 5;
      P.text(section, cursor, infoTop, infoPx, P.style.info);
      cursor += widths[index];
    });
    P.ctx.restore();
  },

  // ── ArrayList ──────────────────────────────────────────────────────────────
  arrayList() {
    const px = State.fontSize * 0.92;
    const m = P.metrics(px, 600);
    const rowHeight = m.lineHeight + 2 * 2;
    const modules = State.modules;

    const rows = modules.map((mod) => {
      const nameWidth = P.measure(mod.name, px, 600);
      const tagWidth = mod.tag ? P.measure(mod.tag, px * 0.85, 500) + 4 : 0;
      return { mod, nameWidth, tagWidth, width: 4.5 * 2 + nameWidth + tagWidth + 2 };
    });

    const right = SCREEN_W - 2;
    let y = 6;

    for (const row of rows) {
      const x = right - row.width;
      P.ctx.save();
      P.ctx.translate(x, y);

      const ctx = P.ctx;
      const grad = ctx.createLinearGradient(0, 0, 0, rowHeight);
      grad.addColorStop(0, P.rgba(P.style.backgroundTop, 0.85));
      grad.addColorStop(1, P.rgba(P.style.backgroundBottom, 0.85));
      ctx.fillStyle = grad;
      P.path(0, 0, row.width, rowHeight, 2);
      ctx.fill();

      // accent stripe on the anchored edge
      const accent = P.accent(y / SCREEN_H);
      ctx.fillStyle = P.rgba(accent, 0.9);
      ctx.fillRect(row.width - 2, 0, 2, rowHeight);

      P.text(row.mod.name, 4.5, 2, px, [235, 237, 245], 600);
      if (row.mod.tag) {
        P.text(row.mod.tag, 4.5 + row.nameWidth + 4, 2 + (m.lineHeight - P.metrics(px * 0.85, 500).lineHeight) / 2,
          px * 0.85, P.style.info, 500);
      }
      P.ctx.restore();
      y += rowHeight + 1.5;
    }
  },

  // ── Effects ────────────────────────────────────────────────────────────────
  effects() {
    const rowPx = State.fontSize * 0.92;
    const m = P.metrics(rowPx, 500);
    const iconSize = Math.round(m.lineHeight + 2);
    const rowHeight = Math.max(m.lineHeight, iconSize) + 2.5 * 2;

    const rows = State.effects.map((effect) => {
      const title = `${State.effectName(effect.key)} ${ROMAN[effect.amplifier] ?? ""}`.trim();
      const time = effect.infinite ? State.t("inf")
        : `${Math.floor(effect.seconds / 60)}:${String(Math.floor(effect.seconds % 60)).padStart(2, "0")}`;
      return { effect, title, time };
    });

    let innerWidth = 76;
    for (const row of rows) {
      innerWidth = Math.max(
        innerWidth,
        4.5 + iconSize + 4 + P.measure(row.title, rowPx, 500) + 12 + P.measure(row.time, rowPx, 500) + 4.5,
      );
    }

    const width = 6 * 2 + innerWidth;
    const height = 5.5 * 2 + rows.length * (rowHeight + 3) - 3;
    const x = anchorX("topright", width, 6);
    const y = anchorY("topright", height, 250);

    P.ctx.save();
    P.ctx.translate(x, y);
    P.glass(0, 0, width, height);

    const wave = 0.45 + 0.55 * (0.5 + 0.5 * Math.sin(P.time * 4.5));
    let rowY = 5.5;
    for (const row of rows) {
      const expiring = !row.effect.infinite && row.effect.fraction < 0.25;
      const pulse = expiring ? wave : 1;

      // row plate, with the remaining time draining out of it
      const ctx = P.ctx;
      ctx.save();
      P.path(6, rowY, innerWidth, rowHeight, 2.5);
      ctx.clip();
      ctx.fillStyle = `rgba(0, 0, 0, ${0.28 * pulse})`;
      ctx.fillRect(6, rowY, innerWidth, rowHeight);
      const fillWidth = innerWidth * (row.effect.infinite ? 1 : row.effect.fraction);
      const accent = P.accent(rowY / SCREEN_H);
      const grad = ctx.createLinearGradient(6, 0, 6 + innerWidth, 0);
      grad.addColorStop(0, P.rgba(accent, 0.20 * pulse));
      grad.addColorStop(1, P.rgba(accent, 0.05 * pulse));
      ctx.fillStyle = grad;
      ctx.fillRect(6, rowY, fillWidth, rowHeight);
      ctx.restore();

      const contentY = rowY + 2.5;
      P.sprite(Assets[`effect_${row.effect.key}`], 6 + 4.5, contentY + (Math.max(m.lineHeight, iconSize) - iconSize) / 2,
        iconSize, iconSize);
      P.text(row.title, 6 + 4.5 + iconSize + 4, contentY + (Math.max(m.lineHeight, iconSize) - m.lineHeight) / 2,
        rowPx, [235, 237, 245], 500);

      const timeWidth = P.measure(row.time, rowPx, 500);
      P.text(row.time, 6 + innerWidth - 4.5 - timeWidth,
        contentY + (Math.max(m.lineHeight, iconSize) - m.lineHeight) / 2, rowPx, P.style.info, 500);

      rowY += rowHeight + 3;
    }
    P.ctx.restore();
  },

  // ── Scoreboard ─────────────────────────────────────────────────────────────
  scoreboard() {
    const px = State.fontSize * 0.92;
    const m = P.metrics(px, 500);
    const board = State.scoreboard;

    let innerWidth = P.measure(board.title, px, 600);
    for (const row of board.rows) {
      innerWidth = Math.max(innerWidth, P.measure(row.text, px, 500) + 12 + P.measure(row.value, px, 500));
    }

    const width = 7 * 2 + innerWidth;
    const height = 5 * 2 + m.lineHeight + 4 + board.rows.length * (m.lineHeight + 2.5) - 2.5;
    const x = anchorX("topleft", width, 6);
    const y = anchorY("topleft", height, 190);

    P.ctx.save();
    P.ctx.translate(x, y);
    P.glass(0, 0, width, height);

    const titleWidth = P.measure(board.title, px, 600);
    P.accentText(board.title, (width - titleWidth) / 2, 5, px);

    let rowY = 5 + m.lineHeight + 4;
    for (const row of board.rows) {
      P.text(row.text, 7, rowY, px, [225, 228, 238], 500);
      const valueWidth = P.measure(row.value, px, 500);
      P.text(row.value, width - 7 - valueWidth, rowY, px, P.style.info, 500);
      rowY += m.lineHeight + 2.5;
    }
    P.ctx.restore();
  },

  // ── Keystrokes ─────────────────────────────────────────────────────────────
  keystrokes() {
    const KEY = 17;
    const GAP = 2;
    const width = KEY * 3 + GAP * 2;
    const cells = [];

    let layoutY = 0;
    cells.push({ id: "w", label: "W", x: KEY + GAP, y: layoutY, w: KEY, h: KEY });
    layoutY += KEY + GAP;
    cells.push({ id: "a", label: "A", x: 0, y: layoutY, w: KEY, h: KEY });
    cells.push({ id: "s", label: "S", x: KEY + GAP, y: layoutY, w: KEY, h: KEY });
    cells.push({ id: "d", label: "D", x: (KEY + GAP) * 2, y: layoutY, w: KEY, h: KEY });
    layoutY += KEY + GAP;
    cells.push({ id: "space", label: "—", x: 0, y: layoutY, w: width, h: 8 });
    layoutY += 8 + GAP;
    const mouseWidth = (width - GAP) / 2;
    cells.push({ id: "lmb", label: State.t("lmb"), x: 0, y: layoutY, w: mouseWidth, h: 13, cps: cps("left") });
    cells.push({ id: "rmb", label: State.t("rmb"), x: mouseWidth + GAP, y: layoutY, w: mouseWidth, h: 13, cps: cps("right") });
    layoutY += 13 + GAP;

    const height = layoutY - GAP;
    const x = anchorX("bottomleft", width, 8);
    const y = anchorY("bottomleft", height, 62);
    const labelPx = State.fontSize * 0.72;

    P.ctx.save();
    P.ctx.translate(x, y);
    for (const cell of cells) {
      const press = State.press[cell.id] ?? 0;
      P.glass(cell.x, cell.y, cell.w, cell.h);

      if (press > 0.01) {
        const ctx = P.ctx;
        ctx.save();
        P.path(cell.x, cell.y, cell.w, cell.h, P.style.radius);
        ctx.clip();
        const accent = P.accent(0.3);
        const grad = ctx.createLinearGradient(0, cell.y, 0, cell.y + cell.h);
        grad.addColorStop(0, P.rgba(accent, 0.55 * press));
        grad.addColorStop(1, P.rgba(accent, 0.28 * press));
        ctx.fillStyle = grad;
        ctx.fillRect(cell.x, cell.y, cell.w, cell.h);
        ctx.restore();
      }

      if (cell.id === "space") {
        const dashWidth = cell.w * 0.25;
        P.ctx.fillStyle = `rgba(255, 255, 255, ${0.55 + 0.45 * press})`;
        P.ctx.fillRect(cell.x + (cell.w - dashWidth) / 2, cell.y + cell.h / 2 - 0.5, dashWidth, 1);
      } else {
        const label = cell.cps !== undefined && cell.cps > 0 ? `${cell.label} ${cell.cps}` : cell.label;
        const labelWidth = P.measure(label, labelPx, 600);
        const lm = P.metrics(labelPx, 600);
        P.text(label, cell.x + (cell.w - labelWidth) / 2, cell.y + (cell.h - lm.lineHeight) / 2,
          labelPx, [255, 255, 255], 600, 0.85 + 0.15 * press);
      }
    }
    P.ctx.restore();
  },

  // ── PvpIntel ───────────────────────────────────────────────────────────────
  pvpIntel() {
    const px = 7.5;
    const m = P.metrics(px, 500);
    const target = State.target;
    const rows = [
      { label: State.t("pops"), value: String(target.pops), color: [235, 237, 245] },
      { label: State.t("gapples"), value: String(target.gapples), color: [235, 237, 245] },
      { label: State.t("armor"), value: State.t("better"), color: [224, 96, 96] },
      { label: State.t("combo"), value: String(target.combo), color: [235, 237, 245] },
      { label: State.t("hits"), value: String(target.hits), color: [235, 237, 245] },
      { label: "ping", value: `${28} ${State.t("ms")}`, color: P.style.info },
    ];

    const title = target.name;
    const winLabel = `${State.t("win")} ${Math.round(target.winChance * 100)}%`;
    let innerWidth = Math.max(62, P.measure(title, px, 600), P.measure(winLabel, px, 500));
    for (const row of rows) {
      innerWidth = Math.max(innerWidth, P.measure(row.label, px, 500) + 4 + P.measure(row.value, px, 500));
    }

    const rowBlock = rows.length * (m.lineHeight + 1.5) - 1.5;
    const width = 5 * 2 + innerWidth;
    const height = 4 * 2 + m.lineHeight + 3 + 2 + 3 + m.lineHeight + 1.5 + rowBlock;
    const x = anchorX("topleft", width, 6);
    const y = anchorY("topleft", height, 44);

    P.ctx.save();
    P.ctx.translate(x, y);
    P.glass(0, 0, width, height);

    P.accentText(title, 5, 4, px);

    let cursorY = 4 + m.lineHeight + 3;
    P.barTrack(5, cursorY, innerWidth, 2, 1);
    P.barFill(5, cursorY, innerWidth, 2, target.winChance, 1,
      (f) => P.lerp([224, 96, 96], [127, 203, 143], target.winChance));
    cursorY += 2 + 3;

    P.text(winLabel, 5, cursorY, px, P.style.info, 500);
    cursorY += m.lineHeight + 1.5;

    for (const row of rows) {
      P.text(row.label, 5, cursorY, px, P.style.info, 500);
      const valueWidth = P.measure(row.value, px, 500);
      P.text(row.value, width - 5 - valueWidth, cursorY, px, row.color, 500);
      cursorY += m.lineHeight + 1.5;
    }
    P.ctx.restore();
  },

  // ── Armor ──────────────────────────────────────────────────────────────────
  armor() {
    const icon = 16;
    const cell = icon + 1.5;
    const gap = 3;
    const items = State.self.armorItems;
    const width = items.length * cell + (items.length - 1) * gap;
    const height = cell + 1.5 + 1.6;
    const x = anchorX("bottomright", width, 6);
    const y = anchorY("bottomright", height, 28);

    P.ctx.save();
    P.ctx.translate(x, y);
    items.forEach((item, index) => {
      const cx = index * (cell + gap);
      itemCell(cx, 0, cell);
      itemIcon(item, cx + (cell - icon) / 2, (cell - icon) / 2, icon);

      const barY = cell + 1.5;
      P.ctx.fillStyle = "rgba(0, 0, 0, 0.45)";
      P.path(cx, barY, cell, 1.6, 0.8);
      P.ctx.fill();
      const hue = item.durability * 120;
      P.ctx.fillStyle = `hsl(${hue}, 85%, 55%)`;
      P.path(cx, barY, cell * item.durability, 1.6, 0.8);
      P.ctx.fill();
    });
    P.ctx.restore();
  },

  // ── Counters ───────────────────────────────────────────────────────────────
  counters() {
    const icon = 16;
    const cell = icon + 1.5;
    const gap = 3;
    const items = State.counters;
    const width = items.length * cell + (items.length - 1) * gap;
    const height = cell;
    const x = anchorX("bottomright", width, 6);
    const y = anchorY("bottomright", height, 57);

    P.ctx.save();
    P.ctx.translate(x, y);
    items.forEach((item, index) => {
      const cx = index * (cell + gap);
      itemCell(cx, 0, cell);
      itemIcon(item, cx + (cell - icon) / 2, (cell - icon) / 2, icon);
    });
    P.ctx.restore();
  },

  // ── ItemBinds ──────────────────────────────────────────────────────────────
  itemBinds() {
    const icon = 16;
    const cell = icon + 1.5;
    const colGap = 3;
    const labelPx = State.fontSize * 0.7;
    const lm = P.metrics(labelPx, 600);
    const badgeHeight = lm.lineHeight + 1.2 * 2;
    const binds = State.itemBinds;

    const columns = binds.map((bind) => {
      const labelWidth = P.measure(bind.key, labelPx, 600);
      return { bind, badgeWidth: labelWidth + 3 * 2, width: Math.max(cell, labelWidth + 6) };
    });

    const width = columns.reduce((acc, c) => acc + c.width + colGap, 0) - colGap;
    const height = cell + 2 + badgeHeight;
    const x = anchorX("bottomright", width, 6);
    const y = anchorY("bottomright", height, 92);

    P.ctx.save();
    P.ctx.translate(x, y);
    let cx = 0;
    for (const column of columns) {
      itemCell(cx + (column.width - cell) / 2, 0, cell);
      itemIcon(column.bind, cx + (column.width - icon) / 2, (cell - icon) / 2, icon);

      const badgeX = cx + (column.width - column.badgeWidth) / 2;
      const badgeY = cell + 2;
      P.ctx.fillStyle = "rgba(0, 0, 0, 0.42)";
      P.path(badgeX, badgeY, column.badgeWidth, badgeHeight, 2);
      P.ctx.fill();
      const labelWidth = P.measure(column.bind.key, labelPx, 600);
      P.text(column.bind.key, badgeX + (column.badgeWidth - labelWidth) / 2, badgeY + 1.2,
        labelPx, [235, 237, 245], 600);
      cx += column.width + colGap;
    }
    P.ctx.restore();
  },

  // ── AutoSwap ───────────────────────────────────────────────────────────────
  autoSwap() {
    const icon = 16;
    const cell = icon + 1.5;
    const gap = 3;
    const arrowW = 13;
    const labelPx = State.fontSize * 0.7;
    const lm = P.metrics(labelPx, 600);
    const buttonHeight = lm.lineHeight + 1.6 * 2;

    const width = cell + gap + arrowW + gap + cell;
    const height = cell + 3 + buttonHeight;
    const x = anchorX("bottomright", width, 6);
    const y = anchorY("bottomright", height, 132);
    const swap = State.autoSwap;

    P.ctx.save();
    P.ctx.translate(x, y);

    itemCell(0, 0, cell);
    itemIcon({ icon: swap.from }, (cell - icon) / 2, (cell - icon) / 2, icon);

    // the ⇄ between the two slots, drawn as two arrows so it reads at any scale
    const ctx = P.ctx;
    const arrowX = cell + gap;
    const midY = cell / 2;
    ctx.strokeStyle = P.rgba(P.accent(0.5), 0.9);
    ctx.lineWidth = 1;
    ctx.lineCap = "round";
    ctx.beginPath();
    ctx.moveTo(arrowX + 1, midY - 2);
    ctx.lineTo(arrowX + arrowW - 1, midY - 2);
    ctx.moveTo(arrowX + arrowW - 3.5, midY - 4);
    ctx.lineTo(arrowX + arrowW - 1, midY - 2);
    ctx.lineTo(arrowX + arrowW - 3.5, midY);
    ctx.moveTo(arrowX + arrowW - 1, midY + 2);
    ctx.lineTo(arrowX + 1, midY + 2);
    ctx.moveTo(arrowX + 3.5, midY);
    ctx.lineTo(arrowX + 1, midY + 2);
    ctx.lineTo(arrowX + 3.5, midY + 4);
    ctx.stroke();

    itemCell(cell + gap + arrowW + gap, 0, cell);
    itemIcon({ icon: swap.to }, cell + gap + arrowW + gap + (cell - icon) / 2, (cell - icon) / 2, icon);

    const buttonY = cell + 3;
    P.glass(0, buttonY, width, buttonHeight, { radius: 2.5 });
    const keyWidth = P.measure(swap.key, labelPx, 600);
    P.text(swap.key, (width - keyWidth) / 2, buttonY + 1.6, labelPx, [235, 237, 245], 600);
    P.ctx.restore();
  },

  // ── Notifications ──────────────────────────────────────────────────────────
  notifications() {
    const now = performance.now();
    State.notifications = State.notifications.filter((n) => now < n.expire + 200);
    if (State.notifications.length === 0) return;

    const px = State.fontSize * 0.9;
    const smallPx = px * 0.85;
    const m = P.metrics(px, 600);
    const sm = P.metrics(smallPx, 500);

    let y = SCREEN_H - 130;
    for (let i = State.notifications.length - 1; i >= 0; i--) {
      const toast = State.notifications[i];
      const width = Math.max(
        110,
        8 * 2 + 2.5 + Math.max(P.measure(toast.title, px, 600), P.measure(toast.message, smallPx, 500)),
      );
      const height = 5 * 2 + m.lineHeight + sm.lineHeight;

      const age = now - toast.born;
      const life = toast.expire - now;
      const appear = Math.min(1, age / 250);
      const leave = Math.max(0, Math.min(1, life / 200));
      const alpha = P.fadeAlpha(appear) * leave;
      const slide = (1 - P.easeOutBack(appear)) * 18;

      y -= height;
      const x = 6 - slide;

      P.ctx.save();
      P.ctx.translate(x, y);
      P.glass(0, 0, width, height, { alpha });

      const stripe = toast.kind === "enable" ? [127, 203, 143]
        : toast.kind === "disable" ? [224, 96, 96] : P.accent(0.4);
      P.ctx.fillStyle = P.rgba(stripe, 0.95 * alpha);
      P.path(0, 0, 2.5, height, 1);
      P.ctx.fill();

      P.text(toast.title, 8 + 2.5, 5, px, [240, 242, 250], 600, alpha);
      P.text(toast.message, 8 + 2.5, 5 + m.lineHeight, smallPx, P.style.info, 500, alpha);
      P.ctx.restore();

      y -= 4;
    }
  },

  // ── Hotbar with hearts, food, armour, air and the xp bar ───────────────────
  hotbar() {
    const CELL = 20;
    const GAP = 2;
    const ROW = CELL * 9 + GAP * 8;
    const self = State.self;

    const x = (SCREEN_W - ROW) / 2;
    const y = SCREEN_H - 4 - CELL;

    P.ctx.save();
    P.ctx.translate(x, y);

    // offhand sits to the left of the row
    P.glass(-(CELL + 6), 0, CELL, CELL);
    itemIcon(self.offhand, -(CELL + 6) + 2, 2, 16);

    for (let slot = 0; slot < 9; slot++) {
      const cx = slot * (CELL + GAP);
      P.glass(cx, 0, CELL, CELL);
      itemIcon(self.hotbar[slot], cx + 2, 2, 16);
    }

    // selection ring, gliding between slots
    const selX = State.selSmooth * (CELL + GAP);
    const accent = P.accent(0.25);
    P.outline(selX - 0.5, -0.5, CELL + 1, CELL + 1, P.style.radius, accent, 1.2, 0.95);
    P.glowDot(selX + CELL / 2, CELL / 2, CELL * 0.75, accent, 0.10);

    // experience bar
    const XP_Y = -8.5;
    const XP_H = 4.5;
    P.barTrack(0, XP_Y, ROW, XP_H, 1.3);
    const ctx = P.ctx;
    ctx.save();
    P.path(0, XP_Y, ROW * self.xp, XP_H, 1.3);
    ctx.clip();
    const xpGrad = ctx.createLinearGradient(0, XP_Y, 0, XP_Y + XP_H);
    xpGrad.addColorStop(0, "rgb(126, 211, 47)");
    xpGrad.addColorStop(1, "rgb(63, 140, 26)");
    ctx.fillStyle = xpGrad;
    ctx.fillRect(0, XP_Y, ROW * self.xp, XP_H);
    ctx.restore();

    if (self.level > 0) {
      const levelPx = State.fontSize * 0.8;
      const text = String(self.level);
      const width = P.measure(text, levelPx, 600);
      P.text(text, (ROW - width) / 2 + 0.4, XP_Y - 8.6 + 0.4, levelPx, [20, 30, 12], 600);
      P.text(text, (ROW - width) / 2, XP_Y - 8.8, levelPx, [126, 211, 47], 600);
    }

    // hearts, food and air on the stats row
    const STATS_Y = -19;
    const hearts = Math.ceil(self.maxHealth / 2);
    const absorbHearts = Math.ceil(self.absorption / 2);
    const low = self.health <= 6;

    for (let i = 0; i < hearts + absorbHearts; i++) {
      const column = i % 10;
      const row = Math.floor(i / 10);
      const hx = column * 8;
      const hy = STATS_Y - row * 10;
      const beat = low && i < hearts ? 1 + 0.09 * Math.sin(P.time * 9 + i * 0.9) : 1;

      P.ctx.save();
      P.ctx.translate(hx + 4.5, hy + 4.5);
      P.ctx.scale(beat, beat);
      P.ctx.translate(-4.5, -4.5);
      if (i < hearts) {
        P.sprite(Assets.heart_container, 0, 0, 9, 9, 0.85);
        const filled = self.health / 2 - i;
        if (filled >= 1) P.sprite(Assets.heart_full, 0, 0, 9, 9);
        else if (filled > 0) P.sprite(Assets.heart_half, 0, 0, 9, 9);
      } else {
        const index = i - hearts;
        const filled = self.absorption / 2 - index;
        if (filled >= 1) P.sprite(Assets.heart_absorb_full, 0, 0, 9, 9);
        else if (filled > 0) P.sprite(Assets.heart_absorb_half, 0, 0, 9, 9);
      }
      P.ctx.restore();
    }

    // armour points sit above the hearts
    const armorIcons = Math.round(self.armorPoints / 2);
    for (let i = 0; i < armorIcons; i++) {
      P.sprite(Assets.armor_full, i * 8, STATS_Y - 10, 9, 9);
    }

    // food mirrors the hearts on the right edge
    for (let i = 0; i < 10; i++) {
      const fx = ROW - 9 - i * 8;
      P.sprite(Assets.food_empty, fx, STATS_Y, 9, 9, 0.75);
      const filled = self.food / 2 - i;
      if (filled >= 1) P.sprite(Assets.food_full, fx, STATS_Y, 9, 9);
      else if (filled > 0) P.sprite(Assets.food_half, fx, STATS_Y, 9, 9);
    }

    // the held item's name fades in above the bar, like vanilla
    const sinceName = P.time - self.heldItemShownAt;
    if (sinceName < 2.5) {
      const alpha = Math.min(1, (2.5 - sinceName) / 0.5);
      const namePx = State.fontSize * 0.92;
      const width = P.measure(self.heldItemName, namePx, 500);
      P.text(self.heldItemName, (ROW - width) / 2, STATS_Y - 14, namePx, [255, 255, 255], 500, alpha);
    }
    P.ctx.restore();
  },
};

function cps(button) {
  const now = performance.now();
  const list = State.clicks[button];
  while (list.length && now - list[0] > 1000) list.shift();
  return list.length;
}
