// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Published on GitHub Pages as a project site: https://0x1p0.github.io/TapDrop/ (links go through src/data/url.ts).
export default defineConfig({
  site: 'https://0x1p0.github.io',
  base: '/TapDrop',
  trailingSlash: 'always',
  integrations: [sitemap()],
  prefetch: { prefetchAll: true, defaultStrategy: 'hover' },
  build: { inlineStylesheets: 'always', format: 'directory' },
  devToolbar: { enabled: false },
});
