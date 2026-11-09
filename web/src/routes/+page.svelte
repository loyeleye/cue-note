<script lang="ts">
  import { onMount } from 'svelte';
  import type { Session } from '@supabase/supabase-js';
  import { supabase, isConfigured } from '$lib/supabase';

  let session = $state<Session | null>(null);
  let role = $state<string | null>(null);
  let email = $state('');
  let status = $state('');
  let sending = $state(false);

  onMount(() => {
    supabase.auth.getSession().then(({ data }) => (session = data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, s) => (session = s));
    return () => data.subscription.unsubscribe();
  });

  $effect(() => {
    if (!session) { role = null; return; }
    supabase.from('profile').select('role').eq('id', session.user.id).single()
      .then(({ data }) => (role = data?.role ?? null));
  });

  async function sendLink(e: SubmitEvent) {
    e.preventDefault();
    sending = true;
    status = '';
    const { error } = await supabase.auth.signInWithOtp({
      email: email.trim(),
      options: {
        emailRedirectTo: `${location.origin}/auth/callback`,
        // A new account made from the dashboard is a teacher account.
        data: { role: 'teacher' }
      }
    });
    sending = false;
    status = error ? `Couldn't send the link: ${error.message}` : 'Check your email for the sign-in link.';
  }
</script>

<h1>Cue Note</h1>

{#if !isConfigured}
  <p class="muted">Supabase isn't configured. Copy <code>.env.example</code> to <code>.env</code> and fill it in.</p>
{:else if session}
  <p>Signed in as <strong>{session.user.email}</strong>{role ? ` (${role})` : ''}.</p>
  <p class="muted">Your students and their lessons will appear here.</p>
  <button onclick={() => supabase.auth.signOut()}>Sign out</button>
{:else}
  <p>Sign in to see your students' lessons. We'll email you a link; no password needed.</p>
  <form onsubmit={sendLink}>
    <label for="email">Email</label>
    <input id="email" type="email" autocomplete="email" required bind:value={email} />
    <button type="submit" disabled={sending}>{sending ? 'Sending…' : 'Email me a link'}</button>
  </form>
  {#if status}<p role="status">{status}</p>{/if}
{/if}

<style>
  .muted { color: var(--muted); }
  form { display: flex; flex-direction: column; gap: 8px; max-width: 360px; }
  input, button { font: inherit; padding: 10px 12px; border-radius: 8px; border: 1px solid var(--border); }
  input { background: transparent; color: inherit; }
  button { background: var(--accent); color: white; border: none; cursor: pointer; }
  button:disabled { opacity: 0.6; }
</style>
