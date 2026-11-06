// The films, resolved at build time from public/media: the real file when it's there, a Lumen placeholder poster
// until the re-rendered films land (the old 2.0 poster is recognised by its hash and replaced), and each film's
// length read from the MP4 itself, so the play button never states a wrong duration.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { to } from './url';

const pub = path.join(process.cwd(), 'public');
const OLD_POSTERS = new Set(['999c6e7334b57fef']); // the 2.0 film's poster
const exists = (p: string) => fs.existsSync(path.join(pub, p));
const hash = (p: string) => crypto.createHash('sha256').update(fs.readFileSync(path.join(pub, p))).digest('hex').slice(0, 16);

/** Seconds, from the MP4's movie header ('mvhd'), or null. */
function duration(p: string): number | null {
  try {
    const b = fs.readFileSync(path.join(pub, p));
    const i = b.indexOf('mvhd');
    if (i < 0) return null;
    const v = b[i + 4];
    const scale = v === 1 ? b.readUInt32BE(i + 24) : b.readUInt32BE(i + 16);
    const dur = v === 1 ? Number(b.readBigUInt64BE(i + 28)) : b.readUInt32BE(i + 20);
    return scale ? dur / scale : null;
  } catch { return null; }
}
const clock = (s: number) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`;

export type Film = { src: string | null; poster: string; length: string | null };

export function film(name: string, posterName: string): Film {
  const src = `/media/${name}`;
  const poster = `/media/${posterName}`;
  const realPoster = exists(poster) && !OLD_POSTERS.has(hash(poster));
  const d = exists(src) ? duration(src) : null;
  return {
    src: exists(src) ? to(src) : null,
    poster: to(realPoster ? poster : `/media/placeholder/${posterName}`),
    length: d ? clock(d) : null,
  };
}
