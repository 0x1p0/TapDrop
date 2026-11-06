// The signature: one scrubbed timeline on a sticky stage. A phone, back to you, presses to the notch; the page
// floods white, cuts to black, the phone buzzes and lifts, the notch grows into the real island and the original
// photo drops out of it. The light reading in the menu bar follows the same progress.
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { onPage, prefersReducedMotion, ease } from '../engine';
import { lux, ROOM, closedClip, openClip } from './notch';
import { idle } from './idle';

gsap.registerPlugin(ScrollTrigger);

const lerp = (a: number, b: number, t: number) => a + (b - a) * gsap.utils.clamp(0, 1, t);
// Reading at progress p (0..1 of the hero), in the order the light key reads it.
// Reading at progress p (0..1 of the hero), in the order the light key reads it. Eased, never stepped.
const sm = (x: number) => { const k = gsap.utils.clamp(0, 1, x); return k * k * (3 - 2 * k); };
function reading(p: number) {
  if (p < 0.12) return lerp(ROOM, 38, sm(p / 0.12)); // the phone covers the sensor
  if (p < 0.26) return lerp(38, 1480, sm((p - 0.12) / 0.14)); // white
  if (p < 0.37) return 1480;
  if (p < 0.45) return lerp(1480, 2, sm((p - 0.37) / 0.08)); // black
  if (p < 0.54) return 2;
  if (p < 0.66) return lerp(2, ROOM, sm((p - 0.54) / 0.12)); // lifted: the room again
  return ROOM;
}
const stepAt = (p: number) => (p < 0.12 ? 0 : p < 0.37 ? 1 : p < 0.47 ? 2 : 3);

export function startHero() {
  onPage(({ intro }) => {
    const hero = document.querySelector<HTMLElement>('[data-hero]');
    if (!hero || prefersReducedMotion()) return;
    const q = <T extends Element = HTMLElement>(s: string) => hero.querySelector<T>(s)!;
    const stage = q('[data-hero-stage]');
    const phone = q('[data-phone]');
    const phoneIdle = q('[data-phone-idle]');
    const glow = q('[data-glow]');
    const spill = q('[data-spill]');
    const white = q('[data-white]');
    const buzz = q('[data-buzz]');
    const flood = q('[data-flood]');
    const dark = q('[data-dark]');
    const lift = q('[data-lift]');
    const title = q('[data-title]');
    const key = q('[data-title-key]');
    const aside = q('[data-aside]');
    const meta = q('.hero__meta');
    const hint = q('[data-hint]');
    const steps = q('[data-steps]');
    const stepEls = [...hero.querySelectorAll<HTMLElement>('[data-step]')];
    const isl = q('[data-landed-island]');
    const islClip = q('[data-landed-clip]');
    const veil = q('[data-landed-veil]');
    const photo = q('[data-landed-photo]');
    const cap = q('[data-landed-cap]');
    const html = document.documentElement;

    // The intro (headline, words, the phone's light) runs in CSS from the first paint; see .rise and .css-in.
    void intro;

    // The white flood and the key-light title share one radius, so the black words appear exactly where the
    // light has reached.
    const fl = { r: 0 };
    const applyFlood = () => {
      const r = fl.r.toFixed(1);
      flood.style.clipPath = `circle(${r}px at 50% 0px)`;
      key.style.clipPath = `circle(${r}px at ${(stage.offsetWidth / 2 - key.offsetLeft).toFixed(1)}px ${(-key.offsetTop).toFixed(1)}px)`;
    };
    gsap.set(flood, { opacity: 1 });
    applyFlood();
    gsap.set(buzz, { autoAlpha: 0 });
    gsap.set(steps, { autoAlpha: 0, y: 16 });
    gsap.set(islClip, { clipPath: () => closedClip(isl.offsetWidth, isl.offsetHeight) });
    gsap.set(veil, { autoAlpha: 1 });
    gsap.set(isl, { autoAlpha: 0 });
    gsap.set(cap, { autoAlpha: 0, y: 10 });

    // Where the photo starts: inside the island's thumbnail (about 10% across, 63% down the capture).
    const fromPhoto = () => {
      const s = stage.getBoundingClientRect();
      const i = isl.getBoundingClientRect();
      const p = photo.getBoundingClientRect();
      const thumbW = i.width * 0.105;
      return {
        x: i.left + i.width * 0.102 - (p.left + p.width / 2),
        y: i.top - s.top + i.height * 0.626 - (p.top - s.top + p.height / 2),
        scale: thumbW / p.width,
      };
    };
    const pressY = () => -(phone.offsetTop - 14);
    const small = matchMedia('(max-width: 767px)').matches;

    // Twelve beats over the hero's scroll. Every tween eases in and out and overlaps its neighbour.
    const tl = gsap.timeline({
      defaults: { ease: 'power2.inOut' },
      scrollTrigger: {
        trigger: hero,
        start: 'top top',
        end: 'bottom bottom',
        scrub: 1.1,
        invalidateOnRefresh: true,
        onUpdate: (self) => apply(self.progress),
        onLeave: () => { lux.hero = null; },
        onLeaveBack: () => { lux.hero = null; apply(0); },
      },
    });

    // 0 → 1.6 touch: the phone rises to the notch; the meta steps aside.
    tl.to(phone, { y: pressY, duration: 1.6 }, 0)
      .to(glow, { scale: 1.1, duration: 1.6, ease: 'sine.inOut' }, 0)
      .to([meta, hint], { autoAlpha: 0, y: -12, duration: 0.9 }, 0.1);
    if (small) tl.to(title, { autoAlpha: 0, duration: 1.0 }, 0.4);
    // 1.4 → 3.6 white: the screen's light floods the page from the notch.
    tl.to(fl, { r: () => Math.hypot(innerWidth, innerHeight) * 1.04, duration: 2.2, ease: 'sine.in', onUpdate: applyFlood }, 1.4)
      .to(white, { opacity: 1, duration: 1.3 }, 1.4)
      .to([glow, spill], { opacity: 0, duration: 1.1 }, 2.1)
      // The words hand over: the lede and buttons leave before the readings arrive in their place.
      .to(aside, { autoAlpha: 0, y: -16, duration: 0.7 }, 2.3)
      .to(steps, { autoAlpha: 1, y: 0, duration: 0.9, ease: 'power2.out' }, 3.0)
      // 4.4 → 5.4 black: everything goes dark on cue (the white has held for a beat).
      .to(dark, { opacity: 1, duration: 1.0 }, 4.4)
      .to(white, { opacity: 0, duration: 0.9 }, 4.5)
      .to([title, key], { autoAlpha: 0, duration: 0.6 }, 4.8)
      // 5.6 → 6.6 buzz.
      .to(phoneIdle, { keyframes: { x: [0, -6, 6, -5, 5, -3, 3, -1, 0] }, duration: 1, ease: 'none' }, 5.6)
      .to(buzz, { keyframes: { autoAlpha: [0, 1, 1, 0] }, duration: 1, ease: 'none' }, 5.6)
      // In the bone theme the room comes back after the black frame (the lift layer only shows there), once the
      // phone has gone: a dark phone fading over a lightening page reads as a grey ghost.
      .to(lift, { opacity: 1, duration: 0.8, ease: 'power2.inOut' }, 7.4)
      // 6.4 → 7.8 lift it off: a short drop that fades, not a throw.
      .to(phone, { y: () => innerHeight * 0.4, rotation: 5, autoAlpha: 0, duration: 1.4, ease: 'sine.inOut' }, 6.4)
      // 6.9 → 8.6 the notch grows into the island, then its content comes up.
      .to(isl, { autoAlpha: 1, duration: 0.6, ease: 'power1.inOut' }, 6.8)
      .fromTo(islClip, { clipPath: () => closedClip(isl.offsetWidth, isl.offsetHeight || isl.offsetWidth * 0.25) },
        { clipPath: () => openClip(isl.offsetWidth), duration: 1.3, ease: 'power3.inOut', immediateRender: false }, 7.0)
      .to(veil, { autoAlpha: 0, duration: 1.0 }, 7.6)
      .to(steps, { autoAlpha: 0, y: 12, duration: 0.9 }, 7.4)
      // 8.4 → 10.4 the photo drops out of the island and lands.
      .fromTo(photo, { x: () => fromPhoto().x, y: () => fromPhoto().y, scale: () => fromPhoto().scale },
        { x: 0, y: 0, scale: 1, duration: 2.0, ease: 'power3.inOut', immediateRender: false }, 8.4)
      .to(photo, { autoAlpha: 1, duration: 0.9, ease: 'power1.out' }, 8.4)
      .to(cap, { autoAlpha: 1, y: 0, duration: 0.9, ease: 'power2.out' }, 10.0)
      .to({}, { duration: 1.1 }, 10.9);
    gsap.set(photo, { autoAlpha: 0 });

    let lastStep = -2;
    let white_ = false;
    let black_ = false;
    function apply(p: number) {
      lux.hero = p > 0.001 && p < 0.7 ? reading(p) : null;
      const isWhite = p > 0.2 && p < 0.4;
      if (isWhite !== white_) { white_ = isWhite; html.classList.toggle('is-white', isWhite); }
      // The black frame: words read bone on it (in the bone theme, until the room comes back).
      const light = html.dataset.theme === 'light';
      const isBlack = p > 0.4 && p < 1 && (!light || p < 0.65);
      if (isBlack !== black_) { black_ = isBlack; html.classList.toggle('is-black', isBlack); }
      const s = stepAt(p);
      if (s !== lastStep) { lastStep = s; stepEls.forEach((el, i) => el.classList.toggle('is-on', i === s)); }
    }
    apply(0);

    // Idle: the phone hangs under the notch, its light breathing, leaning toward the pointer.
    const breathe = () => {
      const p = tl.scrollTrigger?.progress ?? 0;
      if (p > 0.45) return;
      gsap.set(phoneIdle, { y: Math.sin(idle.t * 1.1) * 4 * Math.max(0, 1 - p * 2.2), rotation: idle.x * 5 * Math.max(0, 1 - p * 2.2) });
      gsap.set(glow, { x: idle.x * 18, y: idle.y * 10 });
    };
    const st = ScrollTrigger.create({ trigger: hero, start: 'top bottom', end: 'bottom top', onToggle: (s) => (s.isActive ? gsap.ticker.add(breathe) : gsap.ticker.remove(breathe)) });
    gsap.ticker.add(breathe);

    return () => {
      gsap.ticker.remove(breathe);
      st.kill();
      lux.hero = null;
      html.classList.remove('is-white', 'is-black');
    };
  });
}
