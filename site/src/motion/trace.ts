// "What the notch sees": the light-key trace draws as you read. On the home page each chapter moves the reading on;
// elsewhere it draws as it scrolls into view. Without motion it is simply complete.
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { onPage, prefersReducedMotion } from '../engine';
import { idle } from './idle';

const W = 640;
// Ends of each chapter on the trace: room, covered, white, black, counted.
const STOPS = [0.19, 0.32, 0.48, 0.6, 1];
const MARKS = [176, 264, 352, 420];

function luxAt(y: number) {
  return y < 150 ? 112 + ((150 - y) / 120) * 1368 : Math.max(2, 112 - ((y - 150) / 65) * 110);
}

export function startTrace() {
  onPage(() => {
    const traces = [...document.querySelectorAll<HTMLElement>('[data-trace]')];
    if (!traces.length || prefersReducedMotion()) return;
    const kills: (() => void)[] = [];

    traces.forEach((fig) => {
      const path = fig.querySelector<SVGPathElement>('[data-trace-line]')!;
      const clip = fig.querySelector<SVGRectElement>('[data-trace-clip]')!;
      const dot = fig.querySelector<SVGCircleElement>('[data-trace-dot]')!;
      const out = fig.querySelector<HTMLElement>('[data-trace-lux]');
      const marks = [...fig.querySelectorAll<SVGGElement>('[data-mark]')];
      // Lookup: y of the line for every x.
      const len = path.getTotalLength();
      const ys = new Float32Array(W + 1);
      for (let i = 0, x = 0; i <= 600; i++) {
        const pt = path.getPointAtLength((i / 600) * len);
        while (x <= pt.x && x <= W) ys[x++] = pt.y;
      }
      const state = { x: 0 };
      let last = '';
      let scan = false; // a complete trace whose reading head keeps sweeping it, like the live graph in the apps
      const draw = () => {
        const x = Math.round(gsap.utils.clamp(0, W, state.x));
        const head = scan ? Math.round(gsap.utils.clamp(0, W, ((idle.t * 120) % (W + 160)) - 80)) : x;
        // While waiting, the head of the trace keeps reading the room, a few lux either way.
        const wobble = !scan && x < 120 ? Math.sin(idle.t * 2.1) * 1.2 + Math.sin(idle.t * 0.7) * 0.8 : 0;
        const y = ys[head] + wobble;
        clip.setAttribute('width', String(Math.max(0.01, x)));
        dot.setAttribute('cx', String(head));
        dot.setAttribute('cy', y.toFixed(1));
        marks.forEach((m, i) => m.style.opacity = x >= MARKS[i] ? '1' : '0');
        const t = Math.round(luxAt(y)).toLocaleString('en-US');
        if (out && t !== last) { out.textContent = t; last = t; }
      };
      marks.forEach((m) => { m.style.transition = 'opacity 0.35s'; });

      const chapters = fig.closest('[data-key]')?.querySelectorAll<HTMLElement>('[data-chapter]');
      if (chapters?.length) {
        const list = chapters[0].parentElement!;
        state.x = W * STOPS[0] * 0.5;
        chapters.forEach((ch, i) => {
          const st = ScrollTrigger.create({
            trigger: ch, start: 'top 62%', end: 'bottom 62%',
            onToggle: (s) => {
              ch.classList.toggle('is-on', s.isActive);
              if (s.isActive) gsap.to(state, { x: W * STOPS[i], duration: 1.4, ease: 'power2.inOut', overwrite: true });
            },
          });
          kills.push(() => st.kill());
        });
        const back = ScrollTrigger.create({ trigger: list, start: 'top 62%', onLeaveBack: () => gsap.to(state, { x: W * STOPS[0] * 0.5, duration: 0.8, overwrite: true }) });
        kills.push(() => back.kill());
      } else {
        state.x = W;
        scan = true;
      }
      // Draw on the one clock while the figure is on screen (its section, since the card itself is sticky).
      const vis = ScrollTrigger.create({ trigger: fig.closest('[data-key]') ?? fig, start: 'top bottom', end: 'bottom top', onToggle: (s) => (s.isActive ? gsap.ticker.add(draw) : gsap.ticker.remove(draw)) });
      kills.push(() => { vis.kill(); gsap.ticker.remove(draw); });
      draw();
    });
    return () => kills.forEach((k) => k());
  });
}
