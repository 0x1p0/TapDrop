// "What a tap sends": five taps on one pinned stage. Everything on the stage is a pure function of the scroll
// progress through the section: which item, and how far into its tap. It is applied on every tick while the
// stage is near the screen, so any scroll speed, direction or jump lands in exactly one consistent state.
import { gsap } from 'gsap';
import { onPage, scrollToTarget, prefersReducedMotion } from '../engine';
import { lux, ROOM, islandRadius } from './notch';

const clamp = (v: number, a = 0, b = 1) => (v < a ? a : v > b ? b : v);
const seg = (t: number, a: number, b: number) => clamp((t - a) / (b - a));
const sm = (x: number) => x * x * (3 - 2 * x); // smoothstep: every value eases in and out
const ss = (t: number, a: number, b: number) => sm(seg(t, a, b));
const inOut3 = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2);
const lerp = (a: number, b: number, k: number) => a + (b - a) * k;

// One tap, as fractions of its stretch of scroll (175svh each). Neighbouring beats overlap, so one thing hands
// over to the next; the phone never leaves the stage, it turns back to show the next screen.
const B = {
  flip: [0, 0.16], // (after the first tap) the phone turns back to you, showing this tap's screen
  clear: [0, 0.12], // the last tap's window clears as the new screen turns to you
  lasso: [0.12, 0.22], pick: [0.2, 0.26], // circle item: the finger draws, then TapDrop's picker takes over
  turn: [0.25, 0.4], // rest is over: it turns around (screen to the Mac) and presses to the notch
  whiteIn: [0.33, 0.48], whiteOut: [0.49, 0.63],
  blackIn: [0.47, 0.61], blackOut: [0.62, 0.76],
  buzz: [0.58, 0.68],
  release: [0.62, 0.76], // comes off the notch, still back to you
  open: [0.58, 0.76], veilIn: [0.66, 0.84], veilOut: [0.86, 0.98], close: [0.91, 1],
  land: [0.68, 0.95], landFade: [0.68, 0.84],
  capIn: [0.8, 0.95],
} as const;

function reading(t: number) {
  if (t < 0.3) return ROOM;
  if (t < 0.37) return lerp(ROOM, 38, ss(t, 0.3, 0.37));
  if (t < 0.46) return lerp(38, 1480, ss(t, 0.37, 0.46));
  if (t < 0.5) return 1480;
  if (t < 0.6) return lerp(1480, 2, ss(t, 0.5, 0.6));
  if (t < 0.64) return 2;
  return lerp(2, ROOM, ss(t, 0.64, 0.76));
}

export function startTapSends() {
  onPage(() => {
    const ts = document.querySelector<HTMLElement>('[data-ts]');
    if (!ts || prefersReducedMotion()) return;
    const q = <T extends Element = HTMLElement>(s: string) => [...ts.querySelectorAll<T>(s)];
    const stage = ts.querySelector<HTMLElement>('.ts__stage')!;
    const items = q<HTMLButtonElement>('[data-ts-go]');
    const fills = q('[data-ts-fill]');
    const screens = q('[data-ts-screen]');
    const pick = ts.querySelector<HTMLElement>('[data-ts-pick]')!;
    const lassoPath = ts.querySelector<SVGPathElement>('[data-ts-lasso-path]')!;
    const lassoFill = ts.querySelector<SVGRectElement>('[data-ts-lasso-fill]')!;
    const phone = ts.querySelector<HTMLElement>('[data-ts-phone]')!;
    const flip = ts.querySelector<HTMLElement>('[data-ts-flip]')!;
    const glow = ts.querySelector<HTMLElement>('[data-ts-glow]')!;
    const white = ts.querySelector<HTMLElement>('[data-ts-white]')!;
    const flash = ts.querySelector<HTMLElement>('[data-ts-flash]')!;
    const dark = ts.querySelector<HTMLElement>('[data-ts-dark]')!;
    const isls = q('[data-ts-isl]');
    const side = ts.querySelector<HTMLElement>('.ts__side')!;
    const small = matchMedia('(max-width: 767px)').matches;
    const results = q('[data-ts-result]');
    const caps = q('[data-ts-cap]');
    const video = ts.querySelector<HTMLVideoElement>('[data-ts-video]');
    const scrub = ts.querySelector<HTMLElement>('[data-ts-scrub]');
    const N = items.length;
    const html = document.documentElement;

    // Layout numbers (no transforms involved), refreshed on resize.
    let g = { vh: innerHeight, pressY: 0, notchW: 200, notchH: 36, islands: [] as { W: number; H: number; top: number }[], from: [] as { dx: number; dy: number; s: number }[] };
    const measure = () => {
      const cs = getComputedStyle(html);
      const notchW = parseFloat(cs.getPropertyValue('--notch-w')) || 200;
      const notchH = parseFloat(cs.getPropertyValue('--notch-h')) || 36;
      const barH = parseFloat(cs.getPropertyValue('--bar-h')) || 36;
      const small = matchMedia('(max-width: 767px)').matches;
      const islandTop = small ? barH + 4 : 0;
      const islands = isls.map((w) => ({ W: w.offsetWidth, H: w.offsetHeight || w.offsetWidth * 0.25, top: islandTop }));
      const res = ts.querySelector<HTMLElement>('.ts__results')!;
      const cx = res.offsetLeft + res.offsetWidth / 2;
      const cy = res.offsetTop + res.offsetHeight / 2;
      const from = results.map((win, j) => {
        const isl = islands[j];
        const thumbX = stage.offsetWidth / 2 - isl.W / 2 + isl.W * 0.102;
        const thumbY = isl.top + isl.H * 0.6;
        return { dx: thumbX - cx, dy: thumbY - cy, s: Math.max(0.05, (isl.W * 0.105) / Math.max(1, win.offsetWidth)) };
      });
      const pressY = -(phone.offsetTop - (small ? barH - 6 : notchH * 0.45));
      g = { vh: innerHeight, pressY, notchW, notchH, islands, from };
    };

    let drivingLux = false;
    let lastWhite = false;
    let lastBlack = false;
    let lastI = -1;
    let ps = -1; // smoothed progress: the stage glides after the scroll instead of stepping with it
    const update = () => {
      const r = ts.getBoundingClientRect();
      const len = Math.max(1, ts.offsetHeight - innerHeight);
      const target = clamp(-r.top / len, 0, 0.99999);
      const onScreen = r.bottom > 0 && r.top < innerHeight;
      if (ps < 0 || !onScreen) ps = target;
      else {
        ps += (target - ps) * (1 - Math.pow(1 - 0.11, gsap.ticker.deltaRatio()));
        if (Math.abs(target - ps) < 1e-5) ps = target;
      }
      const x = ps * N;
      const i = Math.min(N - 1, Math.floor(x));
      const t = x - i;
      const pinned = r.top <= 1 && r.bottom >= innerHeight - 1;

      // Index: the active tap in full white with its progress; earlier ones done, later ones waiting.
      if (i !== lastI) {
        items.forEach((b, j) => {
          b.classList.toggle('is-on', j === i);
          b.classList.toggle('is-done', j < i);
          if (j === i) b.setAttribute('aria-current', 'step'); else b.removeAttribute('aria-current');
        });
        lastI = i;
      }
      fills.forEach((f, j) => { f.style.transform = `scaleX(${j < i ? 1 : j === i ? t.toFixed(4) : 0})`; });

      // The front of the phone: this tap's screen, swapped for the next one while its back faces you.
      const shown = i;
      const isText = shown === N - 1;
      const tt = shown === i ? t : 0; // the circle item's gesture only runs during its own tap
      const pickK = isText ? (i === N - 1 ? ss(tt, B.pick[0], B.pick[1]) : 0) : 0;
      screens.forEach((sc, j) => { sc.style.opacity = j === shown ? '1' : '0'; });
      pick.style.opacity = pickK.toFixed(3);
      const lk = isText && i === N - 1 ? inOut3(seg(tt, B.lasso[0], B.lasso[1])) : 0;
      lassoPath.style.strokeDashoffset = String(1 - lk);
      lassoPath.style.opacity = (isText && i === N - 1 ? 1 - pickK : 0).toFixed(3);
      lassoFill.setAttribute('opacity', String(isText && i === N - 1 ? 0.2 * ss(tt, 0.18, 0.22) * (1 - pickK) : 0));

      // The phone: turns round, presses to the notch, buzzes, comes off, turns back.
      const turn = ss(t, B.turn[0], B.turn[1]);
      const rel = ss(t, B.release[0], B.release[1]);
      const flipIn = i === 0 ? 1 : ss(t, B.flip[0], B.flip[1]); // the back (from the last tap) turns to the front
      const press = turn * (1 - rel);
      const bz = seg(t, B.buzz[0], B.buzz[1]);
      const buzzX = bz > 0 && bz < 1 ? Math.sin(bz * Math.PI * 12) * 5 * Math.sin(bz * Math.PI) : 0;
      const y = press * g.pressY;
      const rot = -180 * (1 - flipIn) + 180 * turn; // -180 (still back to you) → 0 (front) → 180 (back, tapping)
      flip.style.transform = `translate3d(${buzzX.toFixed(2)}px, ${y.toFixed(1)}px, 0) rotateY(${rot.toFixed(2)}deg) scale(${(1 - 0.07 * press).toFixed(4)})`;
      phone.style.setProperty('--buzz', (bz > 0 && bz < 1 ? Math.sin(bz * Math.PI) : 0).toFixed(3));

      // The light key: white floods out of the notch, then black. Each crossfades into the next.
      const w = ss(t, B.whiteIn[0], B.whiteIn[1]) * (1 - ss(t, B.whiteOut[0], B.whiteOut[1]));
      const b = ss(t, B.blackIn[0], B.blackIn[1]) * (1 - ss(t, B.blackOut[0], B.blackOut[1]));
      flash.style.opacity = (w * 0.78).toFixed(3);
      // On night a touch of the room stays; on bone the frame has to be truly black to read as black.
      dark.style.opacity = (b * (html.dataset.theme === 'light' ? 1 : 0.85)).toFixed(3);
      white.style.opacity = w.toFixed(3);
      const ty = `translate3d(0, ${y.toFixed(1)}px, 0)`;
      glow.style.transform = ty;
      white.style.transform = ty;
      const backFacing = clamp(turn * 2);
      glow.style.opacity = (backFacing * press * (1 - w) * 0.85).toFixed(3);
      const isWhite = w > 0.5;
      if (isWhite !== lastWhite) { lastWhite = isWhite; html.classList.toggle('is-white', isWhite); }
      // The black frame: in the bone theme, the stage's words turn bone for its beat.
      const isBlack = b > 0.45;
      if (isBlack !== lastBlack) { lastBlack = isBlack; html.classList.toggle('is-black', isBlack); }

      // The island: blooms out of the notch with this tap's real capture, then folds back.
      const open = ss(t, B.open[0], B.open[1]) * (1 - ss(t, B.close[0], B.close[1]));
      const veil = clamp(1 - ss(t, B.veilIn[0], B.veilIn[1]) + ss(t, B.veilOut[0], B.veilOut[1]));
      isls.forEach((el, j) => {
        const on = j === i && open > 0.001;
        el.style.visibility = on ? 'visible' : 'hidden';
        if (!on) return;
        el.style.opacity = sm(clamp(open / 0.3)).toFixed(3); // grows out of the notch's black, never pops
        const { W, H } = g.islands[j];
        const side = (1 - open) * Math.max(0, (W - g.notchW) / 2);
        const bottom = (1 - open) * Math.max(0, H - g.notchH);
        const rr = lerp(12, islandRadius(W), open); // the notch's corners growing into the island's own
        (el.firstElementChild as HTMLElement).style.clipPath = `inset(0px ${side.toFixed(1)}px ${bottom.toFixed(1)}px ${side.toFixed(1)}px round 0px 0px ${rr.toFixed(1)}px ${rr.toFixed(1)}px)`;
        (el.querySelector('.ts__iveil') as HTMLElement).style.opacity = veil.toFixed(3);
      });

      if (small) side.style.opacity = (1 - 0.92 * Math.max(press, open)).toFixed(3);

      // Results: each lands out of the island. As it lands, the last one steps back and dims, so the window
      // in front always belongs to the tap you just watched.
      const k = ss(t, B.land[0], B.land[1]);
      const kFade = ss(t, B.landFade[0], B.landFade[1]);
      const back = ss(t, B.clear[0], B.clear[1]);
      results.forEach((win, j) => {
        let d: number;
        if (j < i) d = (i - 1 - j + back) * 1.6; // steps back and fades out: the Mac waits for the new tap
        else if (j === i && kFade > 0) d = -(1 - k);
        else { win.style.opacity = '0'; win.style.visibility = 'hidden'; return; }
        win.style.visibility = 'visible';
        win.style.zIndex = String(10 + j);
        if (j === i) {
          const f = g.from[j];
          win.style.transform = `translate3d(${(f.dx * (1 - k)).toFixed(1)}px, ${(f.dy * (1 - k)).toFixed(1)}px, 0) scale(${lerp(f.s, 1, k).toFixed(4)})`;
          win.style.opacity = kFade.toFixed(3);
        } else {
          win.style.transform = `translate3d(0, ${(-d * 36).toFixed(1)}px, 0) scale(${(1 - d * 0.08).toFixed(4)})`;
          win.style.opacity = clamp(1 - d * 0.62).toFixed(3);
        }
      });
      // Captions name the window in front, crossfading as the next one lands.
      caps.forEach((c, j) => {
        const o = j === i ? ss(t, B.capIn[0], B.capIn[1]) : j === i - 1 ? 1 - ss(t, B.clear[0], B.clear[1]) : 0;
        c.style.opacity = o.toFixed(3);
      });

      // The landed video plays while its window is in front.
      if (video) {
        const play = pinned && ((i === 2 && k > 0.85) || (i === 3 && back < 0.4));
        if (play && video.paused) { video.preload = 'auto'; video.play().catch(() => {}); }
        if (!play && !video.paused) video.pause();
        if (scrub && video.duration) scrub.style.transform = `scaleX(${(video.currentTime / video.duration).toFixed(3)})`;
      }

      // The menu bar's reading follows the tap.
      if (pinned) { lux.hero = reading(t); drivingLux = true; }
      else if (drivingLux) {
        lux.hero = null; drivingLux = false;
        if (lastWhite) { lastWhite = false; html.classList.remove('is-white'); }
        if (lastBlack) { lastBlack = false; html.classList.remove('is-black'); }
      }
    };

    measure();
    update();
    // On the one clock while the stage is near the screen; once more on leaving, so it rests in its end state.
    let far = false;
    const tick = () => {
      const r = ts.getBoundingClientRect();
      const near = r.bottom > -innerHeight * 0.5 && r.top < innerHeight * 1.5;
      if (near) { far = false; update(); }
      else if (!far) { far = true; update(); }
    };
    gsap.ticker.add(tick);
    const onResize = () => { measure(); update(); };
    addEventListener('resize', onResize);
    // Islands and windows size from their images: measure again as they arrive.
    const imgs = ts.querySelectorAll('img');
    imgs.forEach((im) => im.addEventListener('load', onResize, { once: true }));

    // The index jumps to a tap: it plays on the way there and stops on its result.
    const go = (e: Event) => {
      const el = e.currentTarget as HTMLElement;
      const j = +el.dataset.tsGo!;
      if ((e as MouseEvent).detail > 0) el.blur(); // a pointer click: the active label alone stays white
      const len = ts.offsetHeight - innerHeight;
      const top = ts.getBoundingClientRect().top + scrollY;
      scrollToTarget(top + len * ((j + 0.9) / N));
    };
    items.forEach((b) => b.addEventListener('click', go));

    return () => {
      gsap.ticker.remove(tick);
      removeEventListener('resize', onResize);
      items.forEach((b) => b.removeEventListener('click', go));
      if (drivingLux) lux.hero = null;
      html.classList.remove('is-white', 'is-black');
      video?.pause();
    };
  });
}
