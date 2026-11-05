// The driver: one variable, the light the notch sees (lux). It feeds the menu bar readout and sparkline, the
// sensor dot in the notch, and the hero. The island (the notch growing into a real TapDrop capture) lives here too.
import { gsap } from 'gsap';
import { ease, isMobile, prefersReducedMotion } from '../engine';

export const ROOM = 112;
export const lux = {
  hero: null as number | null, // the hero timeline pins the reading while it plays
  bump: 0, // a primary target under the pointer lights the notch a little
  shown: ROOM,
};

const HISTORY = 46;
const hist: number[] = Array(HISTORY).fill(ROOM);
let readout: HTMLElement | null = null;
let spark: SVGPolylineElement | null = null;
let als: HTMLElement | null = null;
let clock: HTMLElement | null = null;
let lastPush = 0;
let lastClock = 0;
let lastText = '';

const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function tickClock() {
  if (!clock) return;
  const d = new Date();
  const text = `${days[d.getDay()]} ${d.getDate()} ${months[d.getMonth()]} ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (clock.textContent !== text) clock.textContent = text;
}

// Map a reading to the sparkline's 14 px height (log scale, like a light meter).
const yOf = (v: number) => 12.5 - (Math.log10(Math.max(1, v)) / Math.log10(1600)) * 11;

function draw() {
  if (spark) spark.setAttribute('points', hist.map((v, i) => `${(i * 46) / (HISTORY - 1)},${yOf(v).toFixed(2)}`).join(' '));
  const text = Math.round(lux.shown).toLocaleString('en-US');
  if (readout && text !== lastText) { readout.textContent = text; lastText = text; }
  als?.style.setProperty('--als', String(gsap.utils.clamp(0.08, 1, Math.log10(Math.max(1, lux.shown)) / Math.log10(1500))));
}

export function startNotch() {
  const bind = () => {
    readout = document.querySelector('[data-lux]');
    spark = document.querySelector('[data-spark]');
    als = document.querySelector('[data-als]');
    clock = document.querySelector('[data-clock]');
    tickClock();
    draw();
  };
  bind();
  document.addEventListener('astro:after-swap', bind);

  // A primary target under the pointer lights up white: the notch reads a little more light.
  let bumpTarget = 0;
  document.addEventListener('pointerover', (e) => { bumpTarget = (e.target as Element).closest?.('[data-light]') ? 1 : 0; }, { passive: true });
  document.addEventListener('focusin', (e) => { bumpTarget = (e.target as Element).closest?.('[data-light]') ? 1 : 0; });

  if (prefersReducedMotion()) {
    // No idle noise: a steady reading, the clock still tells the time.
    gsap.ticker.add((t) => { if (t - lastClock > 1) { lastClock = t; tickClock(); } });
    return;
  }

  let n = 0;
  gsap.ticker.add((t) => {
    const r = gsap.ticker.deltaRatio();
    lux.bump += (bumpTarget - lux.bump) * (1 - Math.pow(0.88, r));
    // A real sensor never reads perfectly still: a slow drift and a little grain.
    n += ((Math.random() - 0.5) * 2 - n) * 0.08 * r;
    const noise = Math.sin(t * 0.9) * 1.6 + Math.sin(t * 0.23) * 2.4 + n * 1.4;
    const goal = (lux.hero ?? ROOM) + lux.bump * 260 + noise;
    lux.shown += (goal - lux.shown) * (1 - Math.pow(0.8, r));
    if (t - lastPush > 0.09) { lastPush = t; hist.push(lux.shown); hist.shift(); }
    if (t - lastClock > 1) { lastClock = t; tickClock(); }
    draw();
  });
}

/* ───────── The island ─────────
   One target (`want`) and one rendered state (`st`). Every change, and a check on the ticker while the island is
   up, moves the rendered state toward the target with tweens that overwrite whatever is in flight. Content is
   only ever revealed after the image for the current target is decoded, so the shape and its content can't
   drift apart, at any scroll speed or in any order of events. */

type Want = { src: string; w: number; h: number } | null;
let want: Want = null;
let mounted: { src: string; w: number; h: number } | null = null; // decoded and in the <img>
const st = { open: 0, veil: 1 }; // open: 0 = notch, 1 = full island. veil: 1 = black over the content
let mounting: string | null = null;
let checking = false;

const notchW = () => parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--notch-w')) || 200;
const notchH = () => parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--notch-h')) || 36;
/** The captures are 2x images of a 208 pt notch: scale them so their notch matches ours. */
const cssWidth = (w: number) => (w / 2) * (notchW() / 208);
export const closedClip = (W: number, H: number) => {
  const side = Math.max(0, (W - notchW()) / 2);
  return `inset(0px ${side}px ${Math.max(0, H - notchH())}px ${side}px round 0px 0px 12px 12px)`;
};

function els() {
  const root = document.querySelector<HTMLElement>('[data-island]');
  return {
    root,
    clip: root?.querySelector<HTMLElement>('[data-island-clip]') ?? null,
    img: root?.querySelector<HTMLImageElement>('[data-island-img]') ?? null,
    veil: root?.querySelector<HTMLElement>('[data-island-veil]') ?? null,
  };
}

// The island never covers the menu bar's items: it fits the free space between them.
let room = Infinity;
function measure() {
  const cx = innerWidth / 2;
  const l = (document.querySelector('.bar__menu li:last-child a') ?? document.querySelector('.bar__brand'))?.getBoundingClientRect();
  const r = document.querySelector('.bar__lux')?.getBoundingClientRect();
  const left = l && l.width ? cx - l.right : cx;
  const right = r && r.width ? r.left - cx : cx;
  room = 2 * Math.min(left, right) - 32;
}
const fitWidth = (w: number) => Math.min(cssWidth(w), room);

function render() {
  const { root, clip, veil } = els();
  if (!root || !clip || !veil) return;
  const W = mounted ? fitWidth(mounted.w) : notchW();
  const H = mounted ? (W * mounted.h) / mounted.w : notchH();
  const o = gsap.utils.clamp(0, 1, st.open);
  const side = (1 - o) * Math.max(0, (W - notchW()) / 2);
  const bottom = (1 - o) * Math.max(0, H - notchH());
  const r = 12 * (1 - o);
  root.style.setProperty('--iw', `${W}px`);
  clip.style.clipPath = `inset(0px ${side.toFixed(2)}px ${bottom.toFixed(2)}px ${side.toFixed(2)}px round 0px 0px ${r.toFixed(2)}px ${r.toFixed(2)}px)`;
  veil.style.opacity = st.veil.toFixed(3);
  root.classList.toggle('is-on', st.open > 0.002 || !!want);
}

const settled = () => (!want ? st.open <= 0.002 && st.veil >= 0.998 : mounted?.src === want.src && st.open >= 0.998 && st.veil <= 0.002);

function reconcile() {
  const { img } = els();
  if (!img) return;
  if (!want) {
    // Collapse: the content darkens as the shape starts folding back into the notch (one motion, no black pause).
    gsap.killTweensOf(st);
    if (st.veil < 0.998 || st.open > 0.002) {
      gsap.timeline({ onUpdate: render, onComplete: () => { mounted = null; render(); } })
        .to(st, { veil: 1, duration: 0.12, ease: 'power2.out', onUpdate: render }, 0)
        .to(st, { open: 0, duration: 0.38, ease: 'power3.inOut', onUpdate: render }, 0.03);
    } else render();
    return;
  }
  if (mounted?.src !== want.src) {
    // New content: darken whatever is showing, then swap the image once it has decoded.
    if (st.veil < 0.998) { gsap.to(st, { veil: 1, duration: 0.12, ease: 'power2.out', overwrite: true, onUpdate: render, onComplete: reconcile }); return; }
    if (mounting === want.src) return; // already decoding this one
    const target = want;
    mounting = target.src;
    const pre = new Image();
    pre.src = target.src;
    pre.decode().catch(() => {}).then(() => {
      mounting = null;
      if (want?.src !== target.src) { reconcile(); return; } // the target moved on while decoding
      img.src = target.src;
      img.width = target.w / 2; img.height = target.h / 2;
      mounted = { ...target };
      render();
      reconcile();
    });
    return;
  }
  // The right content is mounted: open the shape, then lift the veil.
  gsap.killTweensOf(st);
  const tl = gsap.timeline({ onUpdate: render });
  if (st.open < 0.998) tl.to(st, { open: 1, duration: 0.6 * (1 - st.open) + 0.15, ease: ease.out, onUpdate: render }, 0);
  if (st.veil > 0.002) tl.to(st, { veil: 0, duration: 0.3, ease: ease.soft, onUpdate: render }, st.open < 0.6 ? 0.25 : 0);
}

function watch() {
  if (checking) return;
  checking = true;
  let last = 0;
  const check = (t: number) => {
    if (t - last < 0.25) return;
    last = t;
    if (!gsap.isTweening(st) && !mounting && !settled()) reconcile();
    if (!want && settled()) { gsap.ticker.remove(check); checking = false; }
  };
  gsap.ticker.add(check);
}

/** Point the island at a capture (2x image, natural size w×h), or pass null to fold it back into the notch. */
export function setIsland(next: Want) {
  measure();
  if (prefersReducedMotion() || isMobile() || room < notchW() + 120) next = null;
  if (next?.src === want?.src && !!next === !!want) return;
  want = next;
  reconcile();
  watch();
}
export const showIsland = (src: string, w: number, h: number) => setIsland({ src, w, h });
export function hideIsland(immediate = false) {
  if (immediate) {
    want = null;
    gsap.killTweensOf(st);
    st.open = 0; st.veil = 1; mounted = null;
    render();
    return;
  }
  setIsland(null);
}
