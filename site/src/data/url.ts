// Every internal link goes through here, so the site still works if it's ever served under a base path.
const base = import.meta.env.BASE_URL.replace(/\/$/, '');

/** "/get-started/" → "<base>/get-started/" (just "/get-started/" at the root). */
export const to = (path: string) => base + path;
