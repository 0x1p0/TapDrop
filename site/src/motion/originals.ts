// "Never a screenshot": the pinned figure. One variable, the scroll progress p of the track, drives every layer:
// the step index, the screen dissolving into its 16 × 16 grid, the library lighting up as it is ranked, the 48 × 48
// look up close, and the original being sent. Every frame is computed from p, so any speed or direction is exact.
// The grids and scores come from src/data/originals.json (scripts/originals.mjs), loaded only on this page.
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { onPage, prefersReducedMotion } from '../engine';

gsap.registerPlugin(ScrollTrigger);

type Lib = { best: boolean; second: boolean; grid: number[] };
type Data = { screen16: number[]; screen48: number[]; fine48: number[]; fineScore: number; library: Lib[]; video: { curve: number[]; peak: number } };

const clamp = gsap.utils.clamp(0, 1);
const span = (p: number, a: number, b: number) => clamp((p - a) / (b - a));
const ease = (t: number) => 1 - Math.pow(1 - clamp(t), 3);
const smooth = (t: number) => { const k = clamp(t); return k * k * (3 - 2 * k); };
// The grids' light, per theme: bone cells on night, or ink-to-white cells on bone (true brightness either way).
const PAL = {
  dark: { lo: [8, 8, 10], hi: [244, 243, 239], dim: [150, 150, 156], ring: 'rgb(214 222 255)', glow: 'rgba(214,222,255,0.9)' },
  light: { lo: [26, 26, 29], hi: [255, 255, 255], dim: [176, 174, 168], ring: 'rgb(11 11 13)', glow: 'rgba(11,11,13,0)' },
};
let pal = PAL.dark;
const tone = (v: number, tint = pal.hi, lift = 0.06) => {
  const k = lift + (1 - lift) * v;
  return `rgb(${pal.lo.map((n, i) => Math.round(n + (tint[i] - n) * k)).join(' ')})`;
};

/** Draws an n × n grid into (x, y, w, h); `reveal(i)` gives each cell's 0..1 presence. */
function grid(ctx: CanvasRenderingContext2D, vals: number[], n: number, x: number, y: number, w: number, h: number, gap: number, reveal: (i: number) => number, tint = pal.hi) {
  const cw = (w - gap * (n - 1)) / n, ch = (h - gap * (n - 1)) / n;
  for (let i = 0; i < n * n; i++) {
    const r = reveal(i);
    if (r <= 0.002) continue;
    ctx.globalAlpha = r;
    ctx.fillStyle = tone(vals[i] / 255, tint);
    ctx.fillRect(x + (i % n) * (cw + gap), y + ((i / n) | 0) * (ch + gap), cw, ch);
  }
  ctx.globalAlpha = 1;
}

/** A diagonal wave: cell i of an n × n grid appears as t sweeps from 0 to 1. */
const wave = (n: number, t: number, width = 0.35) => (i: number) => {
  const d = ((i % n) + ((i / n) | 0)) / (2 * (n - 1));
  return smooth((t * (1 + width) - d) / width);
};

/** The cost figures count up once as they arrive, landing on the exact value the page was rendered with. */
function countUp() {
  const els = [...document.querySelectorAll<HTMLElement>('[data-count]')];
  const sts = els.map((el) => {
    const target = el.dataset.count ?? el.textContent ?? '0';
    const n = parseFloat(target);
    const dec = (target.split('.')[1] ?? '').length;
    if (!Number.isFinite(n)) return null;
    const o = { v: 0 };
    el.textContent = (0).toFixed(dec);
    return ScrollTrigger.create({
      trigger: el,
      start: 'top 88%',
      once: true,
      onEnter: () => gsap.to(o, { v: n, duration: 1.3, ease: 'expo.out', onUpdate: () => { el.textContent = o.v.toFixed(dec); }, onComplete: () => { el.textContent = target; } }),
    });
  });
  return () => sts.forEach((s) => s?.kill());
}

export function startOriginals() {
  onPage(() => (prefersReducedMotion() ? undefined : countUp()));
  onPage(() => {
    const root = document.querySelector<HTMLElement>('[data-og]');
    if (!root || prefersReducedMotion()) return;
    let alive = true;
    const kills: (() => void)[] = [];

    import('../data/originals-client.json').then((mod) => {
      if (!alive) return;
      const data = (mod.default ?? mod) as unknown as Data;
      kills.push(stage(root, data));
      kills.push(video(root, data));
      ScrollTrigger.refresh();
    });

    return () => { alive = false; kills.forEach((k) => k()); };
  });
}

function stage(root: HTMLElement, data: Data) {
  const track = root.querySelector<HTMLElement>('[data-og-track]');
  if (!track || !matchMedia('(min-width: 768px)').matches) return () => {};
  const q = <T extends Element = HTMLElement>(s: string) => root.querySelector<T>(s)!;
  const qa = <T extends Element = HTMLElement>(s: string) => [...root.querySelectorAll<T>(s)];
  const steps = qa('[data-og-step]');
  const photo = q<HTMLImageElement>('[data-og-photo]');
  const cells = q<HTMLCanvasElement>('[data-og-cells]');
  const wall = q<HTMLCanvasElement>('[data-og-wall]');
  const fa = q<HTMLCanvasElement>('[data-og-fine-a]');
  const fb = q<HTMLCanvasElement>('[data-og-fine-b]');
  const lib = q('[data-og-lib]');
  const close = q('[data-og-close]');
  const result = q('[data-og-result]');
  const readout = q('[data-og-readout]');
  const fineEl = q('[data-og-fine]');
  const timeEl = q('[data-og-time]');
  const rows = qa('[data-og-row]');
  const bars = qa('[data-og-bar]');
  const scores = qa('[data-og-score]');
  const phone = q('[data-og-phone]');
  // The step sentences live once, in the still sequence; the pinned stage borrows them.
  const still = [...root.querySelectorAll<HTMLElement>('.og-still__text p')];
  qa('[data-og-text]').forEach((el, i) => { if (!el.textContent) el.textContent = still[i]?.textContent ?? ''; });
  const cx = cells.getContext('2d')!, wx = wall.getContext('2d')!, ax = fa.getContext('2d')!, bx = fb.getContext('2d')!;

  // The library wall: each fingerprint drawn once, then composed with its own light on every frame.
  const W = wall.width, G = 18, T = (W - G * 6) / 7;
  let tiles: HTMLCanvasElement[] = [];
  const paint = () => {
    pal = document.documentElement.dataset.theme === 'light' ? PAL.light : PAL.dark;
    tiles = data.library.map((e) => {
      const c = document.createElement('canvas');
      c.width = c.height = Math.ceil(T);
      const x = c.getContext('2d')!;
      grid(x, e.grid, 16, 0, 0, T, T, 1, () => 1, e.best ? pal.hi : pal.dim);
      return c;
    });
  };
  paint();
  const order = data.library.map((_, i) => i); // scan in reading order, a light passing over the shelf
  const bestI = data.library.findIndex((e) => e.best);
  const secondI = data.library.findIndex((e) => e.second);

  let last = -1;
  let lastStep = -1;
  const render = (p: number) => {
    if (Math.abs(p - last) < 0.0002) return;
    last = p;
    const b1 = span(p, 0.14, 0.34), b2 = span(p, 0.36, 0.58), b3 = span(p, 0.62, 0.78), b4 = span(p, 0.82, 0.96);
    const step = p < 0.14 ? 0 : p < 0.36 ? 1 : p < 0.6 ? 2 : p < 0.8 ? 3 : 4;
    if (step !== lastStep) {
      lastStep = step;
      steps.forEach((s, i) => { s.classList.toggle('is-on', i === step); s.classList.toggle('is-done', i < step); });
      readout.textContent = step === 0 ? 'On screen' : step === 1 ? 'On screen · 16 × 16 · 256 numbers' : step < 4 ? 'On screen · its fingerprint' : 'Sent';
    }

    // 1. The photo dissolves into its grid of light.
    gsap.set(photo, { opacity: 1 - 0.82 * ease(b1) });
    cx.clearRect(0, 0, cells.width, cells.height);
    if (b1 > 0) grid(cx, data.screen16, 16, 0, 0, cells.width, cells.height, 3, wave(16, b1));
    phone.style.setProperty('--og-glow', String(0.12 + 0.35 * ease(b4)));

    // 2. The library, ranked: a light passes over every fingerprint; the match stays lit.
    wx.clearRect(0, 0, W, W);
    tiles.forEach((t, i) => {
      const x = (i % 7) * (T + G), y = ((i / 7) | 0) * (T + G);
      const at = order.indexOf(i) / tiles.length;
      const pass = clamp(1 - Math.abs(b2 * 1.25 - at) / 0.12); // the scanning light
      const settled = i === bestI ? 1 : i === secondI ? 0.62 : 0.3;
      const base = 0.16 + (settled - 0.16) * smooth((b2 * 1.25 - at) / 0.12);
      wx.globalAlpha = Math.max(base, pass);
      wx.drawImage(t, x, y, T, T);
      if (i === bestI && b2 > 0.7) {
        const k = smooth((b2 - 0.7) / 0.25);
        wx.globalAlpha = k;
        wx.strokeStyle = pal.ring;
        wx.lineWidth = 3;
        wx.shadowColor = pal.glow;
        wx.shadowBlur = 18 * k;
        wx.strokeRect(x - 7, y - 7, T + 14, T + 14);
        wx.shadowBlur = 0;
      }
    });
    wx.globalAlpha = 1;
    bars.forEach((el, i) => el.style.setProperty('--k', String(ease(span(b2, 0.25 + i * 0.12, 0.85 + i * 0.05)))));
    scores.forEach((el, i) => {
      const v = Number(el.dataset.v) * ease(span(b2, 0.25 + i * 0.12, 0.85 + i * 0.05));
      el.textContent = v.toFixed(3);
    });
    rows.forEach((el, i) => gsap.set(el, { opacity: 0.25 + 0.75 * ease(span(b2, 0.2 + i * 0.1, 0.5 + i * 0.1)) }));

    // 3. Up close: the screen and the best match at 48 × 48.
    const libOut = smooth(span(p, 0.58, 0.64));
    gsap.set(lib, { autoAlpha: 1 - libOut, y: -18 * libOut, scale: 1 - 0.03 * libOut });
    const closeIn = smooth(span(p, 0.6, 0.66)) * (1 - smooth(span(p, 0.8, 0.85)));
    gsap.set(close, { autoAlpha: closeIn, y: 18 * (1 - smooth(span(p, 0.6, 0.66))) - 18 * smooth(span(p, 0.8, 0.85)) });
    if (closeIn > 0.01) {
      ax.clearRect(0, 0, fa.width, fa.height); bx.clearRect(0, 0, fb.width, fb.height);
      grid(ax, data.screen48, 48, 0, 0, fa.width, fa.height, 1, wave(48, b3 * 1.4, 0.25));
      grid(bx, data.fine48, 48, 0, 0, fb.width, fb.height, 1, wave(48, b3 * 1.4 - 0.15, 0.25));
    }
    fineEl.textContent = (data.fineScore * ease(span(b3, 0.35, 0.95))).toFixed(2);

    // 4. The original, sent.
    const resIn = smooth(span(p, 0.82, 0.88));
    gsap.set(result, { autoAlpha: resIn, y: 22 * (1 - resIn) });
    timeEl.textContent = (1.0 * ease(span(b4, 0.15, 0.9))).toFixed(1);
  };

  const st = ScrollTrigger.create({
    trigger: track,
    start: 'top top',
    end: 'bottom bottom',
    scrub: true,
    invalidateOnRefresh: true,
    onUpdate: (s) => render(s.progress),
    onRefresh: (s) => render(s.progress),
  });
  render(0);
  // A theme change repaints every grid in the other theme's light.
  const onTheme = () => { paint(); last = -1; render(st.progress); };
  document.addEventListener('td:theme', onTheme);
  return () => { st.kill(); document.removeEventListener('td:theme', onTheme); };
}

function video(root: HTMLElement, data: Data) {
  const box = root.querySelector<HTMLElement>('[data-og-video]');
  if (!box) return () => {};
  const strip = box.querySelector<HTMLElement>('.og-video__strip');
  const curve = box.querySelector<SVGPathElement>('[data-og-curve]');
  const peak = data.video.peak / (data.video.curve.length - 1);
  const apply = (p: number) => {
    // The paused frame is ranked against every half second: the window sweeps the strip, the curve draws behind it,
    // and it settles on the moment it matched.
    const scan = span(p, 0.05, 0.6);
    const settle = smooth(span(p, 0.62, 0.86));
    strip?.style.setProperty('--p', String(scan * (1 - settle) + peak * settle));
    strip?.style.setProperty('--wo', String(smooth(span(p, 0, 0.1))));
    curve?.style.setProperty('--draw', String(1 - scan));
  };
  const st = ScrollTrigger.create({ trigger: box, start: 'top 80%', end: 'bottom 45%', scrub: 0.6, onUpdate: (s) => apply(s.progress) });
  apply(0);
  return () => st.kill();
}
