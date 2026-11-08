// Interface pieces: the phone menu, the film player, the in-page index, and hash links across pages.
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { onPage, onScroll, ease, stopScroll, startScroll, scrollToTarget, prefersReducedMotion } from '../engine';
import { closedClip, hideIsland } from './notch';

let menuOpen = false;
let lastFocus: HTMLElement | null = null;

function menuEls() {
  return {
    btn: document.querySelector<HTMLButtonElement>('[data-menu-btn]'),
    menu: document.querySelector<HTMLElement>('[data-menu]'),
  };
}

function openMenu() {
  const { btn, menu } = menuEls();
  if (!btn || !menu || menuOpen) return;
  menuOpen = true;
  lastFocus = document.activeElement as HTMLElement;
  menu.hidden = false;
  btn.setAttribute('aria-expanded', 'true');
  btn.textContent = 'Close';
  stopScroll();
  if (!prefersReducedMotion()) {
    gsap.fromTo(menu, { clipPath: closedClip(innerWidth, innerHeight) }, { clipPath: 'inset(0px 0px 0px 0px round 0px 0px 0px 0px)', duration: 0.7, ease: ease.inOut });
    gsap.from(menu.querySelectorAll('li'), { yPercent: 60, autoAlpha: 0, duration: 0.8, stagger: 0.05, ease: ease.out, delay: 0.2 });
  }
  menu.querySelector<HTMLElement>('a')?.focus();
}

function closeMenu(restore = true) {
  const { btn, menu } = menuEls();
  if (!btn || !menu || !menuOpen) return;
  menuOpen = false;
  btn.setAttribute('aria-expanded', 'false');
  btn.textContent = 'Menu';
  const done = () => { menu.hidden = true; gsap.set(menu, { clearProps: 'clipPath' }); startScroll(); if (restore) lastFocus?.focus(); };
  if (prefersReducedMotion()) return done();
  gsap.to(menu, { clipPath: closedClip(innerWidth, innerHeight), duration: 0.5, ease: ease.inOut, onComplete: done });
}

export function startUI() {
  // Links within the page ("Go to Part 2", the guide's index, "Skip to content"): one smooth scroll, ours. Left alone,
  // Astro's router jumps natively and then restores the old scroll position, Lenis starts its own scroll, and the
  // address changes while the page stays put. Cancelling the click first makes the router stand aside (Lenis's own
  // anchor handling is off, see engine/scroll.ts); other listeners (closing the guide's index on a phone) still run.
  document.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = (e.target as Element).closest?.<HTMLAnchorElement>('a[href]');
    if (!a || (a.target && a.target !== '_self') || a.hasAttribute('download')) return;
    const url = new URL(a.href, location.href);
    if (url.origin !== location.origin || url.pathname !== location.pathname || url.search !== location.search || url.hash.length < 2) return;
    const el = document.getElementById(decodeURIComponent(url.hash.slice(1)));
    if (!el) return;
    e.preventDefault();
    if (url.hash !== location.hash) history.replaceState(history.state, '', url.hash);
    // A frame later, so a menu closing on the same click has started scrolling again first.
    requestAnimationFrame(() => {
      scrollToTarget(el);
      if (el.hasAttribute('tabindex')) el.focus({ preventScroll: true });
    });
  }, true);

  // The theme buttons, wherever they are (bar, menu): one listener for the document.
  document.addEventListener('click', (e) => {
    const b = (e.target as Element).closest?.<HTMLElement>('[data-theme-set]');
    if (!b) return;
    (window as unknown as { __tdTheme?: { set: (v: string) => void } }).__tdTheme?.set(b.dataset.themeSet ?? 'system');
  });

  document.addEventListener('click', (e) => {
    const t = e.target as HTMLElement;
    if (t.closest('[data-menu-btn]')) { menuOpen ? closeMenu() : openMenu(); return; }
    if (menuOpen && t.closest('[data-menu] a')) closeMenu(false);
  });
  document.addEventListener('keydown', (e) => {
    if (!menuOpen) return;
    if (e.key === 'Escape') { e.preventDefault(); closeMenu(); return; }
    if (e.key === 'Tab') {
      const { btn, menu } = menuEls();
      const items = [btn, ...menu!.querySelectorAll<HTMLElement>('a')].filter(Boolean) as HTMLElement[];
      const i = items.indexOf(document.activeElement as HTMLElement);
      const next = e.shiftKey ? (i <= 0 ? items.length - 1 : i - 1) : (i === items.length - 1 ? 0 : i + 1);
      e.preventDefault();
      items[next].focus();
    }
  });
  document.addEventListener('astro:before-preparation', () => { closeMenu(false); hideIsland(); });
  document.addEventListener('astro:before-swap', () => hideIsland(true));

  onPage(() => {
    // Arriving on /#film from another page: go to the anchor once the page is ready.
    if (location.hash.length > 1) {
      const el = document.getElementById(decodeURIComponent(location.hash.slice(1)));
      if (el) requestAnimationFrame(() => { ScrollTrigger.refresh(); scrollToTarget(el); });
    }

    // The films: the poster until someone asks for one, then the real thing with sound. Starting one pauses the others.
    const films = [...document.querySelectorAll<HTMLElement>('[data-film]')];
    const offs: (() => void)[] = [];
    films.forEach((film) => {
      const play = film.querySelector<HTMLButtonElement>('[data-film-play]');
      const tpl = film.querySelector<HTMLTemplateElement>('[data-film-tpl]');
      if (!play || !tpl) return;
      const onPlay = () => {
        films.forEach((f) => f.querySelector('video')?.pause());
        const video = tpl.content.firstElementChild!.cloneNode(true) as HTMLVideoElement;
        film.appendChild(video);
        film.classList.add('is-playing');
        video.play().catch(() => {});
        video.focus();
      };
      play.addEventListener('click', onPlay);
      offs.push(() => play.removeEventListener('click', onPlay));
    });

    // The in-page index follows the section you are reading.
    const toc = document.querySelector('[data-toc]');
    const tocST: ScrollTrigger[] = [];
    toc?.querySelectorAll<HTMLAnchorElement>('a').forEach((a) => {
      const sec = document.querySelector(a.hash);
      if (!sec) return;
      tocST.push(ScrollTrigger.create({ trigger: sec, start: 'top 40%', end: 'bottom 40%', onToggle: (s) => a.classList.toggle('is-on', s.isActive) }));
    });

    return () => { offs.forEach((o) => o()); tocST.forEach((s) => s.kill()); };
  });
}

// Install guide: copy buttons, and a progress line that follows your place in the guide.
export function startGuide() {
  document.addEventListener('click', async (e) => {
    const btn = (e.target as HTMLElement).closest<HTMLButtonElement>('[data-copy]');
    if (!btn) return;
    const text = btn.dataset.copy || '';
    try { await navigator.clipboard.writeText(text); } catch {
      const ta = document.createElement('textarea'); ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select(); try { document.execCommand('copy'); } catch {} ta.remove();
    }
    const label = btn.querySelector('span');
    btn.classList.add('is-done');
    if (label) label.textContent = 'Copied';
    setTimeout(() => { btn.classList.remove('is-done'); if (label) label.textContent = 'Copy'; }, 1600);
  });
  onPage(() => {
    const body = document.querySelector<HTMLElement>('[data-guide]');
    if (!body) return;
    const root = document.documentElement;
    // Where you are: the last step (or reference section) whose top has passed 40% of the screen.
    const steps = [...body.querySelectorAll<HTMLElement>('[data-step]')];
    const marks = [...steps, ...body.querySelectorAll<HTMLElement>('.greference section[id]')];
    const links = [...document.querySelectorAll<HTMLAnchorElement>('[data-gnav-step]')];
    const titles = new Map(links.map((a) => [a.dataset.gnavStep ?? '', (a.querySelector('span:last-child')?.textContent ?? '').trim()]));
    const count = document.querySelector('[data-gnav-count]');
    const bn = document.querySelector('[data-gbar-n]');
    const bt = document.querySelector('[data-gbar-title]');
    const bc = document.querySelector('[data-gbar-count]');
    const gbar = document.querySelector<HTMLButtonElement>('[data-gbar]');
    const gnav = document.querySelector<HTMLElement>('[data-gnav]');
    const N = steps.length;
    let cur = -1;
    const set = (i: number) => {
      if (i === cur) return;
      cur = i;
      const el = marks[i];
      links.forEach((a) => {
        const j = marks.findIndex((m) => m.id === a.dataset.gnavStep);
        a.classList.toggle('is-on', j === i);
        a.classList.toggle('is-done', j > -1 && j < i);
        if (j === i) a.setAttribute('aria-current', 'step'); else a.removeAttribute('aria-current');
      });
      steps.forEach((st, j) => st.classList.toggle('is-on', j === i));
      const isStep = i < N;
      const n = isStep ? i + 1 : N;
      root.style.setProperty('--gp', (n / N).toFixed(4));
      if (count) count.textContent = isStep ? `Step ${n} of ${N}` : `All ${N} steps done · reference`;
      if (bn) bn.textContent = isStep ? el.dataset.step ?? '' : '';
      if (bt) bt.textContent = titles.get(el.id) ?? '';
      if (bc) bc.textContent = isStep ? `${n} of ${N}` : 'Reference';
    };
    const pick = () => {
      const y = innerHeight * 0.4;
      let i = 0;
      marks.forEach((m, j) => { if (m.getBoundingClientRect().top < y) i = j; });
      set(i);
    };
    pick();
    const off = onScroll(pick);
    addEventListener('resize', pick);
    // On a phone, the bar opens the navigator as a list under it; a jump, Escape or a tap elsewhere folds it away.
    const setOpen = (open: boolean) => { gnav?.classList.toggle('is-open', open); gbar?.setAttribute('aria-expanded', String(open)); };
    const onBar = () => setOpen(gbar?.getAttribute('aria-expanded') !== 'true');
    const onDoc = (e: Event) => {
      const t = e.target as Element;
      if (t.closest?.('[data-gbar]')) return;
      if (t.closest?.('[data-gnav] a') || !t.closest?.('[data-gnav]')) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && gbar?.getAttribute('aria-expanded') === 'true') { setOpen(false); gbar.focus(); } };
    gbar?.addEventListener('click', onBar);
    document.addEventListener('click', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      off(); removeEventListener('resize', pick); root.style.removeProperty('--gp');
      gbar?.removeEventListener('click', onBar); document.removeEventListener('click', onDoc); document.removeEventListener('keydown', onKey);
    };
  });
}
