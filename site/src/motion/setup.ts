// Global motion for TapDrop: runs once on the client, before the first page initializes.
import { gsap } from 'gsap';
import { setTransition, ease } from '../engine';
import { startIdle } from './idle';
import { startNotch, lux, closedClip } from './notch';
import { startHero } from './hero';
import { startTapSends } from './tapsends';
import { startTrace } from './trace';
import { startUI, startGuide } from './ui';

export function setupMotion() {
  startIdle();
  startNotch();
  startHero();
  startTapSends();
  startTrace();
  startUI();
  startGuide();

  // Page transitions are the light key: white blooms out of the notch, black follows,
  // then the black draws back up into the notch to show the next page.
  setTransition({
    leave: (c) => {
      const white = c.querySelector('.curtain__white');
      const panel = c.querySelector('.curtain__panel');
      return gsap.timeline()
        .set(c, { autoAlpha: 1 })
        .set(white, { opacity: 1, clipPath: 'circle(0% at 50% 0%)' })
        .set(panel, { opacity: 0, clipPath: 'none' })
        .add(() => { lux.hero = 1480; })
        .to(white, { clipPath: 'circle(150% at 50% 0%)', duration: 0.5, ease: 'power2.in' })
        .add(() => { lux.hero = 2; })
        .to(panel, { opacity: 1, duration: 0.22, ease: 'power2.out' }, '+=0.04');
    },
    enter: (c) => {
      const white = c.querySelector('.curtain__white');
      const panel = c.querySelector('.curtain__panel');
      return gsap.timeline()
        .set(white, { opacity: 0 })
        .add(() => { lux.hero = null; })
        .fromTo(panel, { clipPath: 'inset(0px 0px 0px 0px round 0px 0px 0px 0px)' },
          { clipPath: () => closedClip(innerWidth, innerHeight), duration: 0.85, ease: ease.inOut })
        .set(c, { autoAlpha: 0 })
        .set(panel, { opacity: 0, clearProps: 'clipPath' });
    },
  });
}
