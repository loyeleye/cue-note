// App-wide state: who is signed in, and whether we're in demo mode.
import { supabase, isConfigured } from './supabase';
import { supabaseApi } from './api-supabase';
import { demoApi } from './api-demo';
import type { Api } from './api';
import type { Profile } from './types';

const DEMO_KEY = 'cuenote-demo-mode';

export const app = $state({
  ready: false,
  demo: false,
  email: '',
  me: null as Profile | null
});

/** The data layer for the current mode. */
export function api(): Api {
  return app.demo ? demoApi : supabaseApi;
}

function readDemoFlag() {
  try { return localStorage.getItem(DEMO_KEY) === '1'; } catch { return false; }
}

export async function refreshMe() {
  app.me = await api().me();
}

let started = false;
export function startApp() {
  if (started) return;
  started = true;
  app.demo = readDemoFlag();
  const settle = async (email: string) => {
    app.email = email;
    await refreshMe().catch(() => (app.me = null));
    app.ready = true;
  };
  if (app.demo || !isConfigured) {
    settle(app.demo ? 'demo' : '');
  } else {
    supabase.auth.getSession().then(({ data }) => settle(data.session?.user.email ?? ''));
  }
  supabase.auth.onAuthStateChange((_e, session) => {
    if (app.demo) return;
    // Let the auth call finish before querying with the new session.
    setTimeout(() => settle(session?.user.email ?? ''), 0);
  });
}

export async function enterDemo() {
  try { localStorage.setItem(DEMO_KEY, '1'); } catch { /* still works for this visit */ }
  app.demo = true;
  app.email = 'demo';
  await refreshMe();
  app.ready = true;
}

export async function leaveDemo() {
  try { localStorage.removeItem(DEMO_KEY); } catch { /* nothing to clear */ }
  app.demo = false;
  const { data } = await supabase.auth.getSession();
  app.email = data.session?.user.email ?? '';
  await refreshMe().catch(() => (app.me = null));
}
