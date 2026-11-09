<script lang="ts">
  import '@fontsource-variable/fraunces/opsz.css';
  import '@fontsource-variable/fraunces/opsz-italic.css';
  import '@fontsource-variable/inter';
  import '../app.css';
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { app, leaveDemo, startApp } from '$lib/app.svelte';
  import { resetDemo } from '$lib/api-demo';
  import { supabase } from '$lib/supabase';

  let { children } = $props();
  onMount(startApp);

  async function exitDemo() {
    await leaveDemo();
    goto('/');
  }

  function reset() {
    if (!confirm('Put the sample data back the way it started?')) return;
    resetDemo();
    location.reload();
  }

  const signedIn = $derived(app.ready && !!app.me);
  const onAuthPage = $derived(page.url.pathname.startsWith('/auth'));
</script>

{#if app.demo}
  <div class="demo-banner" role="note">
    <span><strong>Demo mode.</strong> Made-up students and lessons; nothing here touches real data.</span>
    <span class="demo-actions">
      <button class="link" onclick={reset}>Reset demo</button>
      <button class="link" onclick={exitDemo}>Leave demo</button>
    </span>
  </div>
{/if}

<header>
  <a class="brand" href="/"><img src="/favicon.svg" alt="" width="28" height="28" />Cue Note</a>
  {#if signedIn}
    <nav>
      <a href="/" aria-current={page.url.pathname === '/' ? 'page' : undefined}>Students</a>
      <a href="/settings" aria-current={page.url.pathname === '/settings' ? 'page' : undefined}>Settings</a>
      {#if !app.demo}<button class="link" onclick={() => supabase.auth.signOut()}>Sign out</button>{/if}
    </nav>
  {/if}
</header>

<main>
  {#if !app.ready && !onAuthPage}
    <p class="muted">Loading…</p>
  {:else if !app.me && !onAuthPage && page.url.pathname !== '/'}
    <p>Sign in to see this page. <a href="/">Go to sign-in</a></p>
  {:else}
    {@render children()}
  {/if}
</main>

<style>
  .demo-banner {
    background: var(--warn-bg); color: var(--warn-fg); font-size: 13px; padding: 8px 16px;
    display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  }
  .demo-actions { display: flex; gap: 14px; }
  .demo-banner .link { color: inherit; }
  header {
    position: sticky; top: 0; z-index: 10; background: var(--bg); border-bottom: 1px solid var(--border);
    display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; gap: 12px;
  }
  .brand { font-family: var(--font-display); font-weight: 600; font-size: 21px; color: var(--fg); text-decoration: none; display: inline-flex; align-items: center; gap: 9px; }
  .brand img { border-radius: 7px; }
  nav { display: flex; gap: 16px; align-items: center; font-size: 14px; }
  nav a { color: var(--muted); text-decoration: none; }
  nav a { padding: 6px 2px; border-bottom: 2px solid transparent; }
  nav a:hover { color: var(--fg); }
  nav a[aria-current='page'] { color: var(--fg); font-weight: 600; border-bottom-color: var(--brass); }
  main { max-width: 1040px; margin: 0 auto; padding: 20px 16px 64px; }
</style>
