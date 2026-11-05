// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Deployed to Cloudflare Pages, https://tapdrop.pages.dev (wrangler.jsonc). Links go through src/data/url.ts.
export default defineConfig({
  site: 'https://tapdrop.pages.dev',
  trailingSlash: 'always',
  integrations: [sitemap()],
  prefetch: { prefetchAll: true, defaultStrategy: 'hover' },
  build: { inlineStylesheets: 'always', format: 'directory' },
  devToolbar: { enabled: false },
});
