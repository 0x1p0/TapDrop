// The site is served under a base path (GitHub Pages: /TapDrop/). Every internal link goes through here.
const base = import.meta.env.BASE_URL.replace(/\/$/, '');

/** "/get-started/" → "/TapDrop/get-started/"; "/#film" → "/TapDrop/#film". */
export const to = (path: string) => base + path;
