import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

// The dashboard has no server of its own: it builds to static files for
// Cloudflare Pages and talks to Supabase directly with the teacher's session.
export default {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({ fallback: '200.html' })
  }
};
