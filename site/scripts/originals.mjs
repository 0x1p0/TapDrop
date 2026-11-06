// Builds the "Never a screenshot" figure's data: the receipt photo, its 16 × 16 and 48 × 48 brightness grids, a
// wall of library fingerprints, and a video's grids every 0.5 s. Every score the page shows is the real Pearson
// correlation of the grids it draws (the same measure TapDrop's matcher uses), tuned to the values of a real run:
// a receipt ranked first at 0.959 against 0.537 for the next photo, then 0.99 up close.
//   node scripts/originals.mjs     (writes src/assets/originals/receipt.jpg and src/data/originals.json)
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const outImg = path.join(root, 'src/assets/originals');
fs.mkdirSync(outImg, { recursive: true });

// Seeded random, so the figure is the same on every build.
let seed = 0x7a9d1c;
const rnd = () => {
  seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const gauss = () => { const u = Math.max(1e-9, rnd()), v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); };

// ── The photo: a paper receipt on a dark table, as a phone camera would see it ──
const W = 1200, H = 1600;
const lines = [
  ['NORTH PIER BAKERY', '', 'title'],
  ['41 Harbour Road', '', 'sub'],
  ['28 Oct 2026', '14:31', 'row'],
  ['', '', 'rule'],
  ['Sourdough loaf', '6.50', 'row'],
  ['Almond croissant', '3.80', 'row'],
  ['Flat white', '4.20', 'row'],
  ['Flat white', '4.20', 'row'],
  ['Rye, sliced', '5.10', 'row'],
  ['', '', 'rule'],
  ['TOTAL', '23.80', 'total'],
  ['Card', '23.80', 'row'],
  ['', '', 'gap'],
  ['Thank you. See you soon.', '', 'sub'],
];
let y = 300;
const font = 'font-family="Menlo, Courier New, monospace" fill="#26241f"';
const text = lines.map(([l, r, k]) => {
  if (k === 'rule') { y += 34; const s = `<line x1="380" y1="${y}" x2="830" y2="${y}" stroke="#2a2826" stroke-width="3" stroke-dasharray="10 9" opacity="0.7"/>`; y += 40; return s; }
  if (k === 'gap') { y += 40; return ''; }
  const size = k === 'title' ? 44 : k === 'total' ? 38 : 30;
  const weight = k === 'title' || k === 'total' ? 700 : 400;
  y += k === 'title' ? 0 : size * 1.55;
  let s;
  if (k === 'title' || k === 'sub') s = `<text x="605" y="${y}" text-anchor="middle" ${font} font-size="${size}" font-weight="${weight}">${l}</text>`;
  else s = `<text x="380" y="${y}" ${font} font-size="${size}" font-weight="${weight}">${l}</text><text x="830" y="${y}" text-anchor="end" ${font} font-size="${size}" font-weight="${weight}">${r}</text>`;
  if (k === 'title') y += 66;
  return s;
}).join('');
const receiptSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <defs>
    <radialGradient id="room" cx="0.36" cy="0.18" r="1.05">
      <stop offset="0" stop-color="#4a4743"/><stop offset="0.5" stop-color="#262422"/><stop offset="1" stop-color="#121110"/>
    </radialGradient>
    <filter id="grain" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="4"/>
      <feColorMatrix values="0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0.10 0"/>
      <feComposite in2="SourceGraphic" operator="in"/>
    </filter>
    <linearGradient id="paper" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#fbfaf6"/><stop offset="0.55" stop-color="#efece4"/><stop offset="1" stop-color="#ddd9cf"/>
    </linearGradient>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="22"/></filter>
  </defs>
  <rect width="${W}" height="${H}" fill="url(#room)"/>
  <rect width="${W}" height="${H}" fill="#000" filter="url(#grain)"/>
  <g transform="rotate(-4 600 820)">
    <rect x="330" y="200" width="560" height="1240" rx="6" fill="#000" opacity="0.55" filter="url(#shadow)" transform="translate(26 30)"/>
    <path d="M330 200H890V1420l-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16-20 16-20-16Z" fill="url(#paper)"/>
    ${text}
    <g transform="translate(420 1290)">${Array.from({ length: 44 }, (_, i) => `<rect x="${i * 8.4}" y="0" width="${[2, 4, 3, 5, 2, 6][i % 6]}" height="72" fill="#26241f"/>`).join('')}</g>
  </g>
</svg>`;
const photo = sharp(Buffer.from(receiptSvg)).flatten({ background: '#121110' });
await photo.clone().jpeg({ quality: 86, mozjpeg: true }).toFile(path.join(outImg, 'receipt.jpg'));

// Brightness grid of the photo, like the matcher: average luminance per cell.
async function grid(n) {
  const { data } = await sharp(Buffer.from(receiptSvg)).flatten({ background: '#121110' }).grayscale().resize(n, n, { fit: 'fill', kernel: 'cubic' }).raw().toBuffer({ resolveWithObject: true });
  return [...data].map((v) => v / 255);
}
const screen16 = await grid(16);
const screen48 = await grid(48);

// ── Correlation and exact mixing ──
const mean = (a) => a.reduce((s, v) => s + v, 0) / a.length;
const z = (a) => { const m = mean(a); const sd = Math.sqrt(mean(a.map((v) => (v - m) ** 2))) || 1; return a.map((v) => (v - m) / sd); };
const corr = (a, b) => { const za = z(a), zb = z(b); return mean(za.map((v, i) => v * zb[i])); };
/** A grid whose correlation with `base` is exactly r: r·z(base) + √(1−r²)·(other, orthogonalized). */
function withCorr(base, other, r, look = base) {
  const zb = z(base);
  const zo = z(other);
  const c = mean(zo.map((v, i) => v * zb[i]));
  const orth = z(zo.map((v, i) => v - c * zb[i]));
  const mix = zb.map((v, i) => r * v + Math.sqrt(1 - r * r) * orth[i]);
  // Shown with the look (mean and spread) of a real photo's grid.
  const m = mean(look), sd = Math.sqrt(mean(look.map((v) => (v - m) ** 2)));
  return mix.map((v) => Math.min(1, Math.max(0, m + v * sd)));
}
/** withCorr, tuned so the correlation of the grid as drawn (clamped, 8-bit) is the target to three decimals. */
function tuned(base, other, target, look = base) {
  const q = (g) => g.map((v) => Math.round(v * 255) / 255);
  let lo = Math.max(0, target - 0.08), hi = Math.min(0.9999, target + 0.06), g = null;
  for (let k = 0; k < 40; k++) {
    const mid = (lo + hi) / 2;
    g = q(withCorr(base, other, mid, look));
    const r = corr(base, g);
    if (Math.abs(r - target) < 0.0004) break;
    if (r < target) lo = mid; else hi = mid;
  }
  return g;
}
const smoothNoise = (n, scale = 3) => {
  const raw = Array.from({ length: n * n }, () => gauss());
  const out = raw.map((_, i) => {
    const x = i % n, y = (i / n) | 0; let s = 0, w = 0;
    for (let dy = -scale; dy <= scale; dy++) for (let dx = -scale; dx <= scale; dx++) {
      const xx = x + dx, yy = y + dy; if (xx < 0 || yy < 0 || xx >= n || yy >= n) continue;
      const k = Math.exp(-(dx * dx + dy * dy) / (scale * scale)); s += raw[yy * n + xx] * k; w += k;
    }
    return s / w;
  });
  return out;
};

// ── Other photos in the library: fingerprints only (TapDrop keeps no copies) ──
const N = 16;
const field = (fn) => Array.from({ length: N * N }, (_, i) => Math.min(1, Math.max(0, fn((i % N) / (N - 1), ((i / N) | 0) / (N - 1)))));
const kinds = [
  // horizons and skies
  () => { const h = 0.35 + rnd() * 0.35, sx = rnd(), sy = h - 0.1 - rnd() * 0.2, br = 0.4 + rnd() * 0.5; return field((x, y) => (y < h ? 0.45 + 0.4 * (1 - y / h) * br : 0.18 + 0.2 * rnd()) + 0.9 * Math.exp(-((x - sx) ** 2 + (y - sy) ** 2) * 40)); },
  // a face or a figure against a background
  () => { const bx = 0.3 + rnd() * 0.4, bg = rnd() * 0.5; return field((x, y) => bg + 0.55 * Math.exp(-(((x - bx) / 0.22) ** 2 + ((y - 0.42) / 0.3) ** 2)) + 0.25 * (y > 0.75 ? 1 : 0) * rnd()); },
  // night: dark with a few lights
  () => { const pts = Array.from({ length: 5 }, () => [rnd(), rnd(), rnd()]); return field((x, y) => 0.05 + pts.reduce((s, [px, py, k]) => s + k * Math.exp(-((x - px) ** 2 + (y - py) ** 2) * 160), 0)); },
  // a document or a screen: bright with bands
  () => { const f = 3 + ((rnd() * 5) | 0), m = 0.5 + rnd() * 0.4; return field((x, y) => m + 0.2 * Math.sin(y * f * Math.PI + rnd() * 0.3) * (x > 0.15 && x < 0.85 ? 1 : 0.2)); },
  // indoors: blocks of light
  () => { const cut = rnd(), cut2 = rnd(); return field((x, y) => (x < cut ? 0.25 : 0.6) * (y < cut2 ? 1.1 : 0.6) + 0.08 * rnd()); },
  // greenery and texture
  () => { const sn = smoothNoise(N, 2); return field((x, y) => 0.35 + 0.18 * sn[Math.round(y * (N - 1)) * N + Math.round(x * (N - 1))]); },
];
const names = [];
const day = (d) => `202610${String(d).padStart(2, '0')}`;
const others = [];
const stretch = (g) => { const lo = Math.min(...g), hi = Math.max(...g), k = 0.7 + rnd() * 0.25, b = 0.03 + rnd() * 0.08; return g.map((v) => b + ((v - lo) / (hi - lo || 1)) * k); };
while (others.length < 47) {
  const g = stretch(kinds[(rnd() * kinds.length) | 0]());
  const r = corr(screen16, g);
  if (r > 0.45 || r < -0.2 || !Number.isFinite(r)) continue;
  others.push(g);
}
const best = tuned(screen16, smoothNoise(N, 1), 0.959, screen16);
// The next best is another paper-like photo: tuned to 0.537.
const paperLike = kinds[3]();
const second = tuned(screen16, kinds[3](), 0.537, paperLike);
let lib = [
  ...others.map((g) => ({ grid: g })),
  { grid: best, best: true },
  { grid: second, second: true },
];
// Shuffle so the match isn't in a corner, then name them like a phone's camera roll.
for (let i = lib.length - 1; i > 0; i--) { const j = (rnd() * (i + 1)) | 0; [lib[i], lib[j]] = [lib[j], lib[i]]; }
lib = lib.map((e, i) => {
  const d = 1 + ((i * 7) % 28);
  const hh = String(8 + ((i * 5) % 12)).padStart(2, '0'), mm = String((i * 13) % 60).padStart(2, '0'), ss = String((i * 29) % 60).padStart(2, '0');
  const name = e.best ? 'IMG_20261028_143116.jpg' : e.second ? 'IMG_20261028_142950.jpg' : `IMG_${day(d)}_${hh}${mm}${ss}.jpg`;
  return { name, score: Math.round(corr(screen16, e.grid) * 1000) / 1000, best: !!e.best, second: !!e.second, grid: e.grid.map((v) => Math.round(v * 255)) };
});
const ranked = [...lib].sort((a, b) => b.score - a.score).slice(0, 3).map((e) => ({ name: e.name, score: e.score }));

// ── Up close: 48 × 48, edges and all ──
const fineMatch = tuned(screen48, smoothNoise(48, 1), 0.99, screen48);
const fineScore = Math.round(corr(screen48, fineMatch) * 100) / 100;

// ── A video: one grid every 0.5 s for 12 s; the screen shows the moment at 7.5 s ──
const FR = 24;
const frames = Array.from({ length: FR }, (_, k) => {
  const t = k / (FR - 1);
  // a sunrise over water: the sky brightens, the sun climbs and drifts, the water catches it
  const sx = 0.25 + 0.5 * t, sy = 0.62 - 0.3 * t, h = 0.6;
  return field((x, y) => {
    const sky = y < h ? 0.12 + 0.55 * t * (1 - y / h) + 0.1 * Math.sin(x * 3 + t * 4) * t : 0.08 + 0.25 * t * Math.exp(-((x - sx) ** 2) * 30) * (0.6 + 0.4 * Math.sin(y * 30 + t * 9));
    const sun = 0.95 * Math.exp(-((x - sx) ** 2 + (y - sy) ** 2) * 90);
    return sky + sun;
  });
});
const at = 15; // 7.5 s
const screenV = tuned(frames[at], smoothNoise(N, 1), 0.97, frames[at]);
const curve = frames.map((f) => Math.round(corr(screenV, f) * 1000) / 1000);
const peak = curve.indexOf(Math.max(...curve));

const out = {
  note: 'Generated by scripts/originals.mjs. Scores are the Pearson correlations of the grids below.',
  screen16: screen16.map((v) => Math.round(v * 255)),
  screen48: screen48.map((v) => Math.round(v * 255)),
  fine48: fineMatch.map((v) => Math.round(v * 255)),
  fineScore,
  library: lib,
  ranked,
  video: { frames: frames.map((f) => f.map((v) => Math.round(v * 255))), screen: screenV.map((v) => Math.round(v * 255)), curve, peak, step: 0.5 },
};
fs.writeFileSync(path.join(root, 'src/data/originals.json'), JSON.stringify(out));
// What the page's script needs, and nothing more (it loads only where the figure is).
fs.writeFileSync(path.join(root, 'src/data/originals-client.json'), JSON.stringify({
  screen16: out.screen16, screen48: out.screen48, fine48: out.fine48, fineScore: out.fineScore,
  library: out.library.map(({ best, second, grid }) => ({ best, second, grid })),
  video: { curve: out.video.curve, peak: out.video.peak },
}));
console.log('best', ranked, 'fine', fineScore, 'video peak', peak, curve[peak], 'next', [...curve].sort((a, b) => b - a)[1]);

// ── Still renders (no JavaScript, reduced motion, phones): the same grids as images, for each theme ──
const THEMES = {
  dark: { lo: [8, 8, 10], hi: [244, 243, 239], dim: [150, 150, 156], ring: [214, 222, 255], suffix: '' },
  light: { lo: [26, 26, 29], hi: [255, 255, 255], dim: [176, 174, 168], ring: [11, 11, 13], suffix: '-light' },
};
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
function canvas(w, h, bg = [0, 0, 0, 0]) {
  const buf = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) buf.set(bg, i * 4);
  return { w, h, buf, rect(x, y, rw, rh, c) {
    for (let yy = Math.max(0, y); yy < Math.min(h, y + rh); yy++) for (let xx = Math.max(0, x); xx < Math.min(w, x + rw); xx++) this.buf.set(c, (yy * w + xx) * 4);
  } };
}
const save = (cv, name) => sharp(cv.buf, { raw: { width: cv.w, height: cv.h, channels: 4 } }).png({ compressionLevel: 9 }).toFile(path.join(outImg, name));
for (const T of Object.values(THEMES)) {
  const drawGrid = (cv, vals, n, x, y, cw, ch, gap, tint = T.hi, lift = 0.06) => {
    for (let i = 0; i < n * n; i++) {
      const c = mix(T.lo, tint, lift + (1 - lift) * (vals[i] / 255));
      cv.rect(x + (i % n) * (cw + gap), y + ((i / n) | 0) * (ch + gap), cw, ch, [...c, 255]);
    }
  };
  // The screen as 16 × 16 (the photo's 3:4 between the bars)
  { const cw = 24, ch = 32, g = 2; const cv = canvas(16 * (cw + g) - g, 16 * (ch + g) - g); drawGrid(cv, out.screen16, 16, 0, 0, cw, ch, g); await save(cv, `screen-grid${T.suffix}.png`); }
  // The library wall: 7 × 7 fingerprints, the match outlined
  {
    const c = 7, g = 1, t = 16 * (c + g) - g, gap = 14, pad = 6;
    const cv = canvas(7 * (t + gap) - gap + pad * 2, 7 * (t + gap) - gap + pad * 2);
    out.library.forEach((e, i) => {
      const x = pad + (i % 7) * (t + gap), y = pad + ((i / 7) | 0) * (t + gap);
      if (e.best) { cv.rect(x - 5, y - 5, t + 10, t + 10, [...T.ring, 255]); cv.rect(x - 3, y - 3, t + 6, t + 6, [...T.lo, 255]); }
      drawGrid(cv, e.grid, 16, x, y, c, c, g, e.best ? T.hi : T.dim, e.best ? 0.06 : 0.04);
    });
    await save(cv, `library${T.suffix}.png`);
  }
  // Up close: 48 × 48, on screen and the candidate
  { const c = 6, g = 1, t = 48 * (c + g) - g; const cv = canvas(t * 2 + 48, t); drawGrid(cv, out.screen48, 48, 0, 0, c, c, g); drawGrid(cv, out.fine48, 48, t + 48, 0, c, c, g); await save(cv, `upclose${T.suffix}.png`); }
  // The video: a grid every 0.5 s, the matching moment marked
  {
    const c = 3, g = 1, t = 16 * (c + g) - g, gap = 8;
    const cv = canvas(FR * (t + gap) - gap, t + 12);
    out.video.frames.forEach((f, k) => drawGrid(cv, f, 16, k * (t + gap), 6, c, c, g, k === peak ? T.hi : T.dim, 0.05));
    cv.rect(peak * (t + gap) - 4, 0, t + 8, 2, [...T.ring, 255]); cv.rect(peak * (t + gap) - 4, t + 10, t + 8, 2, [...T.ring, 255]);
    await save(cv, `video-strip${T.suffix}.png`);
  }
}
console.log('stills written');
