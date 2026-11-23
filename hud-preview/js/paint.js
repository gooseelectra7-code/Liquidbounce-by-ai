/*
 * Drawing primitives, ported from ModuleHud2's own renderer.
 *
 * Everything below works in GUI UNITS — the same units the Kotlin code lays out in. The canvas
 * carries a scale of GUI_SCALE, so one unit here is one gui pixel in game and the numbers in the
 * element files are the numbers from the source, untouched.
 */

const GUI_SCALE = 2;          // the game's gui scale the preview emulates — the reference frame ran at 2
const SCREEN_W = 1920 / GUI_SCALE;
const SCREEN_H = 1080 / GUI_SCALE;

const STYLES = {
  classic: {
    radius: 4.5,
    backgroundTop: [18, 20, 28, 150 / 255],
    backgroundBottom: [10, 11, 16, 175 / 255],
    accentFrom: [216, 221, 244],
    accentTo: [134, 144, 214],
    info: [152, 158, 182],
    gradientSpeed: 0.4,
  },
  leet: {
    radius: 8,
    backgroundTop: [19, 19, 22, 216 / 255],
    backgroundBottom: [16, 16, 19, 228 / 255],
    accentFrom: [168, 163, 245],
    accentTo: [168, 163, 245],
    info: [143, 143, 151],
    gradientSpeed: 0.4,
  },
};

const Paint = {
  ctx: null,
  style: STYLES.classic,
  blurCanvas: null,
  blurEnabled: true,
  shadowEnabled: true,
  time: 0,

  rgba(c, alpha = 1) {
    const a = (c.length > 3 ? c[3] : 1) * alpha;
    return `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${a})`;
  },

  lerp(a, b, t) {
    return [
      a[0] + (b[0] - a[0]) * t,
      a[1] + (b[1] - a[1]) * t,
      a[2] + (b[2] - a[2]) * t,
    ];
  },

  /** The module's breathing accent: a sine slid along the gradient by time. */
  accent(fraction) {
    const s = this.style;
    const wave = Math.sin((fraction * 0.35 - this.time * s.gradientSpeed * 0.35) * Math.PI * 2);
    return this.lerp(s.accentFrom, s.accentTo, 0.5 + 0.5 * wave);
  },

  easeOutBack(t) {
    const c1 = 1.70158;
    const c3 = c1 + 1;
    const u = t - 1;
    return 1 + c3 * u * u * u + c1 * u * u;
  },

  fadeAlpha(p) {
    const inv = 1 - p;
    return 1 - inv * inv * inv;
  },

  path(x, y, w, h, r) {
    const ctx = this.ctx;
    const radius = Math.min(r, Math.min(w, h) / 2);
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.arcTo(x + w, y, x + w, y + h, radius);
    ctx.arcTo(x + w, y + h, x, y + h, radius);
    ctx.arcTo(x, y + h, x, y, radius);
    ctx.arcTo(x, y, x + w, y, radius);
    ctx.closePath();
  },

  /**
   * The shared glass card: blurred backdrop inside the rounded shape, a vertical body gradient
   * over it, a soft drop shadow underneath and hairline separators on request.
   */
  glass(x, y, w, h, opts = {}) {
    const ctx = this.ctx;
    const s = this.style;
    const alpha = opts.alpha ?? 1;
    const radius = opts.radius ?? s.radius;
    const bodyAlpha = (this.blurEnabled ? 0.7 : 1) * alpha;

    if (this.shadowEnabled) {
      ctx.save();
      ctx.shadowColor = `rgba(0, 0, 0, ${0.35 * alpha})`;
      ctx.shadowBlur = 5;
      ctx.shadowOffsetY = 1;
      ctx.fillStyle = "rgba(0, 0, 0, 0.001)";
      this.path(x, y, w, h, radius);
      ctx.fill();
      ctx.restore();
    }

    ctx.save();
    this.path(x, y, w, h, radius);
    ctx.clip();

    if (this.blurEnabled && this.blurCanvas) {
      ctx.drawImage(this.blurCanvas, 0, 0, SCREEN_W, SCREEN_H);
    }

    const grad = ctx.createLinearGradient(0, y, 0, y + h);
    grad.addColorStop(0, this.rgba(s.backgroundTop, bodyAlpha));
    grad.addColorStop(1, this.rgba(s.backgroundBottom, bodyAlpha));
    ctx.fillStyle = grad;
    ctx.fillRect(x, y, w, h);

    if (opts.glow) {
      const g = opts.glow;
      const radial = ctx.createRadialGradient(g.x, g.y, 0, g.x, g.y, g.radius);
      radial.addColorStop(0, this.rgba(g.color, g.alpha));
      radial.addColorStop(1, this.rgba(g.color, 0));
      ctx.fillStyle = radial;
      ctx.fillRect(x, y, w, h);
    }

    if (opts.separators) {
      ctx.fillStyle = `rgba(255, 255, 255, ${0.16 * alpha})`;
      for (const sx of opts.separators) {
        ctx.fillRect(sx, y + h * 0.28, 0.8, h * 0.44);
      }
    }
    ctx.restore();

    // one-gui-pixel edge, the canvas equivalent of the AA feather ring
    ctx.save();
    ctx.strokeStyle = `rgba(255, 255, 255, ${0.055 * alpha})`;
    ctx.lineWidth = 1 / GUI_SCALE;
    this.path(x, y, w, h, radius);
    ctx.stroke();
    ctx.restore();
  },

  /** Inset bar track with the dark hairline ring the bars use. */
  barTrack(x, y, w, h, r, alpha = 1) {
    const ctx = this.ctx;
    const grad = ctx.createLinearGradient(0, y, 0, y + h);
    grad.addColorStop(0, `rgba(0, 0, 0, ${0.48 * alpha})`);
    grad.addColorStop(1, `rgba(0, 0, 0, ${0.30 * alpha})`);
    ctx.fillStyle = grad;
    this.path(x, y, w, h, r);
    ctx.fill();

    ctx.strokeStyle = `rgba(0, 0, 0, ${0.5 * alpha})`;
    ctx.lineWidth = 0.6;
    this.path(x, y, w, h, r);
    ctx.stroke();

    const inner = ctx.createLinearGradient(0, y + 0.5, 0, y + 1.6);
    inner.addColorStop(0, `rgba(0, 0, 0, ${0.30 * alpha})`);
    inner.addColorStop(1, "rgba(0, 0, 0, 0)");
    ctx.fillStyle = inner;
    ctx.fillRect(x + r * 0.7, y + 0.5, w - r * 1.4, 1.1);
  },

  /** Accent bar fill with the gloss line and the travelling shimmer band. */
  barFill(x, y, w, h, fraction, alpha = 1, colorAt = null) {
    const ctx = this.ctx;
    const width = Math.max(0, Math.min(1, fraction)) * w;
    if (width <= 0.5) return;
    const r = Math.min(2.2, width / 2);

    ctx.save();
    this.path(x, y, width, h, r);
    ctx.clip();

    const grad = ctx.createLinearGradient(x, 0, x + w, 0);
    for (let i = 0; i <= 6; i++) {
      const f = i / 6;
      grad.addColorStop(f, this.rgba(colorAt ? colorAt(f) : this.accent(f), alpha));
    }
    ctx.fillStyle = grad;
    ctx.fillRect(x, y, width, h);

    const gloss = ctx.createLinearGradient(0, y + h * 0.12, 0, y + h * 0.5);
    gloss.addColorStop(0, `rgba(255, 255, 255, ${0.13 * alpha})`);
    gloss.addColorStop(1, "rgba(255, 255, 255, 0.015)");
    ctx.fillStyle = gloss;
    ctx.fillRect(x, y + h * 0.12, width, h * 0.38);

    const band = h * 2.4;
    const cycle = w + band * 2;
    const center = x - band + ((this.time * w * 0.45) % cycle);
    const shimmer = ctx.createLinearGradient(center - band, 0, center + band, 0);
    shimmer.addColorStop(0, "rgba(255, 255, 255, 0)");
    shimmer.addColorStop(0.5, `rgba(255, 255, 255, ${0.16 * alpha})`);
    shimmer.addColorStop(1, "rgba(255, 255, 255, 0)");
    ctx.fillStyle = shimmer;
    ctx.fillRect(x, y, width, h);
    ctx.restore();
  },

  font(px, weight) {
    return `${weight} ${px}px InterHud, sans-serif`;
  },

  measure(str, px, weight = 500) {
    const ctx = this.ctx;
    ctx.font = this.font(px, weight);
    return ctx.measureText(str).width;
  },

  metrics(px, weight = 500) {
    const ctx = this.ctx;
    ctx.font = this.font(px, weight);
    const m = ctx.measureText("Hg");
    const ascent = m.fontBoundingBoxAscent ?? px * 0.95;
    const descent = m.fontBoundingBoxDescent ?? px * 0.25;
    return { ascent, descent, lineHeight: ascent + descent };
  },

  /** [yTop] is the top of the line box, matching WatermarkFont.submit. */
  text(str, x, yTop, px, color, weight = 500, alpha = 1) {
    const ctx = this.ctx;
    const { ascent } = this.metrics(px, weight);
    ctx.font = this.font(px, weight);
    ctx.fillStyle = Array.isArray(color) ? this.rgba(color, alpha) : color;
    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
    ctx.fillText(str, x, yTop + ascent);
  },

  /** Same, but the fill runs the accent gradient across the string like the watermark title. */
  accentText(str, x, yTop, px, alpha = 1, weight = 600) {
    const ctx = this.ctx;
    const width = this.measure(str, px, weight);
    const grad = ctx.createLinearGradient(x, 0, x + width, 0);
    for (let i = 0; i <= 6; i++) {
      const f = i / 6;
      grad.addColorStop(f, this.rgba(this.accent(f), alpha));
    }
    this.text(str, x, yTop, px, grad, weight, alpha);
  },

  /** Pixel-art sprite: nearest neighbour, snapped to the device grid. */
  sprite(img, x, y, w, h, alpha = 1) {
    if (!img || !img.complete || img.naturalWidth === 0) return;
    const ctx = this.ctx;
    ctx.save();
    ctx.imageSmoothingEnabled = false;
    ctx.globalAlpha *= alpha;
    const sx = Math.round(x * GUI_SCALE) / GUI_SCALE;
    const sy = Math.round(y * GUI_SCALE) / GUI_SCALE;
    ctx.drawImage(img, sx, sy, w, h);
    ctx.restore();
  },

  /** Soft additive dot, the canvas stand-in for the radial glow fans. */
  glowDot(x, y, radius, color, alpha) {
    const ctx = this.ctx;
    if (radius <= 0 || alpha <= 0.002) return;
    const grad = ctx.createRadialGradient(x, y, 0, x, y, radius);
    grad.addColorStop(0, this.rgba(color, alpha));
    grad.addColorStop(1, this.rgba(color, 0));
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  },

  /** Rounded-rect outline drawn inside the rectangle, like submitRoundedOutline. */
  outline(x, y, w, h, r, color, thickness = 1, alpha = 1) {
    const ctx = this.ctx;
    ctx.save();
    ctx.strokeStyle = this.rgba(color, alpha);
    ctx.lineWidth = thickness;
    this.path(x + thickness / 2, y + thickness / 2, w - thickness, h - thickness, Math.max(0.5, r - thickness / 2));
    ctx.stroke();
    ctx.restore();
  },
};
