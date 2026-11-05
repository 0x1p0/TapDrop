# TapDrop website

The site for TapDrop: a static Astro site with GSAP and Lenis for motion. Every image on it is a real capture from the
Mac and Android apps, and the film is the 2.0 launch film. TapDrop is free and open source under the MIT License.

## Run it in development

```sh
cd site
npm install
npm run dev          # http://localhost:4321
```

Node 22 or newer. The dev server hot-reloads, but judge motion and speed on a production build.

When the install steps change, update `docs/INSTALL.md` first, then `src/pages/get-started.astro` to match.

## Build and preview for production

```sh
cd site
npm run build        # writes the static site to site/dist
npm run preview      # serves dist on http://localhost:4321
```

`dist/` is plain static files (HTML, CSS, JS, images, the film), so any static host can serve it. Before you put it
online, set the real address in two places: `site` in `astro.config.mjs` and `url` in `src/data/site.ts` (canonical
links, Open Graph tags and the sitemap use it), plus the `Sitemap:` line in `public/robots.txt`. The placeholder is
`https://tapdrop.app`.

## Where things live

| Path | What it is |
|---|---|
| `src/data/site.ts` | Every line of copy on the home page, the requirements and the setup steps |
| `src/pages/` | `index.astro` (home), `how-it-works.astro`, `get-started.astro` (the install guide, from `docs/INSTALL.md`), `404.astro` |
| `src/components/` | The menu bar with the notch, the phone illustration, the light trace, the five-tap stage, result windows, buttons, footer, and the guide's key labels, menu paths and copyable commands |
| `src/motion/` | `hero.ts` (the tap on the home page), `tapsends.ts` (the five-tap stage), `notch.ts` (the light reading and the island), `trace.ts`, `ui.ts`, `setup.ts` (page transitions) |
| `src/engine/` | Smooth scroll, reveals and the page lifecycle, all on GSAP's single ticker |
| `src/styles/` | `tokens.css` (colors, type scale, notch size), `base.css`, `site.css`, `sends.css` |
| `src/assets/` | Real captures: `island/` (Mac notch island), `phone/` (Android screens), `mac/`, `photo/` |
| `public/media/` | The film (`tapdrop-film.mp4`, H.264, 1080p30, about 10 MB), its poster, and a 6 s muted loop of the real video for the five-tap stage |
| `DESIGN.md` | The concept, palette, type, grid and motion rules for this site |

## Updating the film

The site's film is a web encode of `video/out/TapDrop-2.0.mp4`:

```sh
IN=../video/out/TapDrop-2.0.mp4
ffmpeg -y -i $IN -vf "fps=30,scale=1920:1080" -c:v libx264 -preset slow -b:v 1000k -pass 1 -an -f mp4 /dev/null
ffmpeg -y -i $IN -vf "fps=30,scale=1920:1080" -c:v libx264 -preset slow -b:v 1000k -maxrate 2500k -bufsize 4000k \
  -pass 2 -pix_fmt yuv420p -c:a aac -b:a 96k -movflags +faststart public/media/tapdrop-film.mp4
```

## Accessibility and motion

With reduced motion on, there is no smooth scroll, no scrubbed tap and no transitions beyond short fades: the tap is
laid out as a still sequence, and every section is fully visible. The site works without JavaScript too.
