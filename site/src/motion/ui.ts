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

    // The film: the poster until someone asks for it, then the real thing with sound.
    const film = document.querySelector<HTMLElement>('[data-film]');
    const play = film?.querySelector<HTMLButtonElement>('[data-film-play]');
    const tpl = film?.querySelector<HTMLTemplateElement>('[data-film-tpl]');
    const onPlay = () => {
      if (!film || !tpl) return;
      const video = tpl.content.firstElementChild!.cloneNode(true) as HTMLVideoElement;
      film.appendChild(video);
      film.classList.add('is-playing');
      video.play().catch(() => {});
      video.focus();
    };
    play?.addEventListener('click', onPlay);

    // The in-page index follows the section you are reading.
    const toc = document.querySelector('[data-toc]');
    const tocST: ScrollTrigger[] = [];
    toc?.querySelectorAll<HTMLAnchorElement>('a').forEach((a) => {
      const sec = document.querySelector(a.hash);
      if (!sec) return;
      tocST.push(ScrollTrigger.create({ trigger: sec, start: 'top 40%', end: 'bottom 40%', onToggle: (s) => a.classList.toggle('is-on', s.isActive) }));
    });

    return () => { play?.removeEventListener('click', onPlay); tocST.forEach((s) => s.kill()); };
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
    const set = () => {
      const r = body.getBoundingClientRect();
      const p = Math.min(1, Math.max(0, (innerHeight * 0.4 - r.top) / Math.max(1, r.height - innerHeight * 0.4)));
      root.style.setProperty('--gp', p.toFixed(4));
    };
    set();
    const off = onScroll(set);
    addEventListener('resize', set);
    return () => { off(); removeEventListener('resize', set); root.style.removeProperty('--gp'); };
  });
}
