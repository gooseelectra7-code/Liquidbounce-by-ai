/*
 * The three TargetHud looks, ported one for one.
 *
 * Glass works in gui units like the rest of the HUD. Dimasik and Ring live in NATIVE SCREEN PIXELS
 * — the original divides the gui scale out of its pose — so both scale the canvas by 1 / GUI_SCALE
 * first and then use the source's own numbers.
 */

const TargetHud = {
  draw() {
    const mode = State.targetMode;
    if (mode === "glass") this.glass();
    else if (mode === "dimasik") this.dimasik();
    else this.ring();
  },

  /** Shake and red flash for the ~0.7 s after our hit landed. */
  damage() {
    const t = (P.time - State.target.lastHitAt) / 0.7;
    if (t >= 1 || t < 0) return { amp: 0, shakeX: 0, shakeY: 0, flash: 0 };
    const amp = (1 - t) * (1 - t);
    return {
      amp,
      shakeX: Math.sin(P.time * 50) * 1.6 * amp,
      shakeY: Math.cos(P.time * 43) * 1.1 * amp,
      flash: 0.5 * amp,
    };
  },

  healthColor(pct) {
    return P.lerp([224, 96, 96], [127, 203, 143], pct);
  },

  /** Head from the skin: face + hat over a rounded square, red while hurt. */
  head(x, y, size, radius, flash) {
    const ctx = P.ctx;
    ctx.save();
    P.path(x, y, size, size, radius);
    ctx.clip();
    P.sprite(Assets.skin_face, x, y, size, size);
    if (flash > 0.01) {
      ctx.fillStyle = `rgba(224, 68, 60, ${flash})`;
      ctx.fillRect(x, y, size, size);
    }
    ctx.restore();
  },

  items(list) {
    return list.filter(Boolean);
  },

  // ── Glass: the module's own card ───────────────────────────────────────────
  glass() {
    const target = State.target;
    const px = State.fontSize;
    const smallPx = px * 0.85;
    const nameM = P.metrics(px, 600);
    const smallM = P.metrics(smallPx, 500);

    const hpText = `${Math.ceil(target.health + target.absorption)} ${State.t("hp")}`;
    const distText = `${target.distance.toFixed(1)} ${State.t("m")}`;
    const nameWidth = P.measure(target.name, px, 600);
    const hpWidth = P.measure(hpText, smallPx, 500);
    const distWidth = P.measure(distText, smallPx, 500);

    const contentWidth = Math.max(nameWidth, hpWidth + 8 + distWidth, 86);
    const topBlock = nameM.lineHeight + 4 + 7 + 4 + smallM.lineHeight;
    const headSize = topBlock;
    const width = 7 * 2 + headSize + 6 + contentWidth;
    const height = 6 * 2 + topBlock;

    const items = this.items([...target.armorItems, target.mainHand, target.offHand]);
    const cellSize = 16 + 1.5;
    const cellGap = 3;
    const rowY = height + 2;

    const x = SCREEN_W / 2 + 60;
    const y = SCREEN_H / 2 + 20;
    const pop = 0.96 + 0.04 * P.easeOutBack(1);
    const dmg = this.damage();

    P.ctx.save();
    P.ctx.translate(x, y);
    P.ctx.scale(pop, pop);

    P.glass(0, 0, width, height);

    this.head(7 + dmg.shakeX, 6 + dmg.shakeY, headSize, 3, dmg.flash);

    const contentX = 7 + headSize + 6;
    let contentY = 6;
    P.accentText(target.name, contentX, contentY, px);
    contentY += nameM.lineHeight + 4;

    P.barTrack(contentX, contentY, contentWidth, 7, 2.2);
    P.barFill(contentX, contentY, contentWidth, 7, target.displayedPct);
    if (target.ghostPct > target.displayedPct + 0.01) {
      const ctx = P.ctx;
      ctx.save();
      P.path(contentX + contentWidth * target.displayedPct, contentY,
        contentWidth * (target.ghostPct - target.displayedPct), 7, 1.5);
      ctx.fillStyle = "rgba(224, 99, 95, 0.55)";
      ctx.fill();
      ctx.restore();
    }
    contentY += 7 + 4;

    const pct = target.health / target.maxHealth;
    P.text(hpText, contentX, contentY, smallPx, this.healthColor(pct), 500);
    P.text(distText, contentX + contentWidth - distWidth, contentY, smallPx, P.style.info, 500);

    // bare equipment cells hang right under the card
    items.forEach((item, index) => {
      const cx = index * (cellSize + cellGap);
      itemCell(cx, rowY, cellSize);
      itemIcon(item, cx + (cellSize - 16) / 2, rowY + (cellSize - 16) / 2, 16);
    });
    P.ctx.restore();
  },

  // ── Dimasik: the 188x64 native-pixel card ──────────────────────────────────
  dimasik() {
    const target = State.target;
    const W = 188;
    const H = 64;

    const x = SCREEN_W / 2 + 60;
    const y = SCREEN_H / 2 + 20;

    P.ctx.save();
    P.ctx.translate(x, y);
    P.ctx.scale(1 / GUI_SCALE, 1 / GUI_SCALE);

    const ctx = P.ctx;
    // flat card + hairline outline
    ctx.fillStyle = P.rgba(P.style.backgroundBottom, 1);
    P.path(0, 0, W, H, 10);
    ctx.fill();
    P.outline(0, 0, W, H, 10, [255, 255, 255], 1, 0.10);

    const hurt = Math.min(1, target.hurtTime / 10);
    this.head(8, 8, 48, 8, hurt * 0.75);
    P.outline(8, 8, 48, 48, 8, P.accent(0.5), 1.5, 1);

    P.text(target.name, 64, 12, 12, [255, 255, 255], 600);
    P.text(`Hp: ${target.health.toFixed(1)}`, 64, 29, 10, P.style.info, 500);

    // track, health, then absorption stacked over it
    ctx.fillStyle = "rgba(0, 0, 0, 0.45)";
    P.path(64, 47, 116, 5, 2);
    ctx.fill();

    const healthPct = Math.max(0, Math.min(1, target.health / target.maxHealth));
    if (healthPct > 0.004) {
      const grad = ctx.createLinearGradient(64, 0, 180, 0);
      grad.addColorStop(0, P.rgba(P.accent(0), 1));
      grad.addColorStop(1, P.rgba(P.accent(1), 1));
      ctx.fillStyle = grad;
      P.path(64, 47, 116 * healthPct, 5, 2);
      ctx.fill();
    }

    const absPct = Math.max(0, Math.min(1, target.absorption / target.maxHealth));
    if (absPct > 0.004) {
      const grad = ctx.createLinearGradient(64, 0, 180, 0);
      grad.addColorStop(0, "rgb(242, 197, 92)");
      grad.addColorStop(1, "rgb(192, 138, 42)");
      ctx.fillStyle = grad;
      P.path(64, 47, 116 * absPct, 5, 2);
      ctx.fill();
    }

    // equipment row sits on top of the card, 16 px each, empties skipped
    const items = this.items([...target.armorItems, target.mainHand, target.offHand]);
    items.forEach((item, index) => {
      itemIcon(item, index * 16, -16, 16);
    });
    P.ctx.restore();
  },

  // ── Ring: the card is built around its own equipment row ───────────────────
  ring() {
    const target = State.target;
    const icon = 20;
    const gap = 1;
    const slots = 6;
    const rowWidth = slots * icon + (slots - 1) * gap;
    const H = Math.max(52, 32 + icon);
    const head = H - 12;
    const columnX = 6 + head + 8;
    const W = columnX + rowWidth + 3 + head + 6;
    const outer = head / 2;
    const cx = W - 6 - outer;
    const cy = H / 2;
    const stroke = outer * 0.25;
    const inner = outer - stroke;
    const mid = outer - stroke / 2;

    const x = SCREEN_W / 2 + 60;
    const y = SCREEN_H / 2 + 20;

    P.ctx.save();
    P.ctx.translate(x, y);
    P.ctx.scale(1 / GUI_SCALE, 1 / GUI_SCALE);

    const ctx = P.ctx;
    ctx.fillStyle = P.rgba(P.style.backgroundBottom, 1);
    P.path(0, 0, W, H, 10);
    ctx.fill();
    P.outline(0, 0, W, H, 10, [255, 255, 255], 1, 0.10);

    const hurt = Math.min(1, target.hurtTime / 10);
    this.head(6, 6, head, head * 0.175, hurt * 0.75);
    P.outline(6, 6, head, head, head * 0.175, P.accent(0.5), 1.5, 1);

    P.text(target.name, columnX, 8, H * 0.23, [255, 255, 255], 600);

    // ── the ring itself ──────────────────────────────────────────────────────
    const pct = Math.max(0, Math.min(1, target.ringPct));
    const gold = target.absorption > 0;
    const color = gold ? [242, 197, 92] : this.gradeColor(pct);

    ctx.save();
    ctx.lineCap = "butt";
    ctx.strokeStyle = "rgba(0, 0, 0, 0.45)";
    ctx.lineWidth = stroke;
    ctx.beginPath();
    ctx.arc(cx, cy, mid, 0, Math.PI * 2);
    ctx.stroke();

    if (pct > 0.003) {
      const from = -Math.PI / 2;
      const to = from + Math.PI * 2 * pct;

      // glow bloom, tapered towards the two ends
      ctx.globalCompositeOperation = "lighter";
      for (let layer = 3; layer >= 1; layer--) {
        const spread = (2.5 * layer) / 3;
        const falloff = 1 - spread / 2.5;
        ctx.strokeStyle = P.rgba(color, 0.24 * falloff * falloff);
        ctx.lineWidth = stroke + spread * 2;
        ctx.beginPath();
        ctx.arc(cx, cy, mid, from + 0.06, to - 0.06);
        ctx.stroke();
      }
      ctx.globalCompositeOperation = "source-over";

      ctx.strokeStyle = P.rgba(color, 1);
      ctx.lineWidth = stroke;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.arc(cx, cy, mid, from, to);
      ctx.stroke();

      const tipX = cx + mid * Math.cos(to);
      const tipY = cy + mid * Math.sin(to);
      P.glowDot(tipX, tipY, stroke * 1.4, color, 0.26);
      const startX = cx + mid * Math.cos(from);
      const startY = cy + mid * Math.sin(from);
      P.glowDot(startX, startY, stroke, color, 0.14);
    }
    ctx.restore();

    // hit burst running along the ring
    for (const particle of State.ringParticles) {
      const t = Math.max(0, particle.life / particle.maxLife);
      const fade = t * t;
      const radius = mid + particle.radial;
      const angle = (particle.angle * Math.PI) / 180;
      const px = cx + radius * Math.cos(angle);
      const py = cy + radius * Math.sin(angle);
      P.glowDot(px, py, particle.size * (2 - t), particle.color, 0.26 * fade);
      P.glowDot(px, py, particle.size * 0.42, particle.color, 0.85 * fade);
    }

    const numberPx = outer * 0.6;
    const text = String(Math.ceil(target.health + target.absorption));
    const textWidth = P.measure(text, numberPx, 600);
    const nm = P.metrics(numberPx, 600);
    // optically centred on the digits, the way the module places it: baseline sits half a cap
    // height below the ring centre
    P.text(text, cx - textWidth / 2, cy + numberPx * 0.36 - nm.ascent, numberPx, [255, 255, 255], 600);

    // equipment row inside the card, packed, sitting on the bottom padding
    const items = this.items([...target.armorItems, target.mainHand, target.offHand]).slice(0, slots);
    const rowY = H - 6 - icon;
    items.forEach((item, index) => {
      itemIcon(item, columnX + index * (icon + gap), rowY, icon);
    });
    P.ctx.restore();
  },

  gradeColor(pct) {
    const low = [224, 85, 85];
    const mid = [242, 197, 92];
    const high = [127, 203, 143];
    return pct < 0.5 ? P.lerp(low, mid, pct * 2) : P.lerp(mid, high, (pct - 0.5) * 2);
  },
};
