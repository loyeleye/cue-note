import { createClient } from '@supabase/supabase-js';
import { env } from '$env/dynamic/public';

export const isConfigured = Boolean(env.PUBLIC_SUPABASE_URL && env.PUBLIC_SUPABASE_ANON_KEY);

// PKCE: the emailed link comes back to /auth/callback with a one-time code,
// which is exchanged for a session using a secret kept in this browser.
export const supabase = createClient(
  env.PUBLIC_SUPABASE_URL || 'http://localhost:54321',
  env.PUBLIC_SUPABASE_ANON_KEY || 'missing-anon-key',
  { auth: { flowType: 'pkce', persistSession: true, detectSessionInUrl: false } }
);
