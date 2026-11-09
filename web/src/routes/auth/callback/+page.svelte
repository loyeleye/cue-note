<script lang="ts">
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { supabase } from '$lib/supabase';

  let message = $state('Signing you in…');

  // The magic link redirects here with ?code=… ; trade it for a session.
  onMount(async () => {
    const code = new URL(location.href).searchParams.get('code');
    if (!code) { message = 'This sign-in link is missing its code. Request a new one.'; return; }
    const { error } = await supabase.auth.exchangeCodeForSession(code);
    if (error) { message = `Sign-in failed: ${error.message}. Links work once and expire after an hour.`; return; }
    goto('/', { replaceState: true });
  });
</script>

<p>{message}</p>
