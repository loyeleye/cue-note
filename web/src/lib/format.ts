// Small display helpers shared by the screens.
export function clock(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

export function minutes(ms: number | null): string {
  return ms == null ? '' : `${Math.round(ms / 60000)} min`;
}

export function date(iso: string | null, opts: Intl.DateTimeFormatOptions = { weekday: 'short', month: 'short', day: 'numeric' }): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, opts) : '';
}

export function dateTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) : '';
}

export function ago(iso: string): string {
  const d = (Date.now() - new Date(iso).getTime()) / 1000;
  if (d < 60) return 'just now';
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
  const days = Math.floor(d / 86400);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

export function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}

export function bars(start: number | null, end: number | null): string {
  if (start == null) return '';
  return end != null && end !== start ? `bars ${start}–${end}` : `bar ${start}`;
}

export const FLAG_LABEL = { practice_this: 'Practice this', just_listen: 'Just listen' } as const;

/** Monday of the week containing `d`, as YYYY-MM-DD. */
export function weekOf(d = new Date()): string {
  const m = new Date(d);
  m.setDate(m.getDate() - ((m.getDay() + 6) % 7));
  return `${m.getFullYear()}-${String(m.getMonth() + 1).padStart(2, '0')}-${String(m.getDate()).padStart(2, '0')}`;
}

export function weekLabel(week: string | null): string {
  if (!week) return 'No week set';
  if (week === weekOf()) return 'This week';
  const next = new Date(); next.setDate(next.getDate() + 7);
  if (week === weekOf(next)) return 'Next week';
  return `Week of ${new Date(week + 'T12:00:00').toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`;
}
