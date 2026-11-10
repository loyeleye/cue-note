// A stand-in for supabase-js over in-memory tables, covering just the calls
// extract-cues makes. The caller's user id is the bearer token.
type Row = Record<string, any>;
export const tables: Record<string, Row[]> = {};
export const rpcs: Record<string, (args: any) => any> = {};
let clock = 0;

export function reset() {
  for (const k of Object.keys(tables)) delete tables[k];
  for (const k of Object.keys(rpcs)) delete rpcs[k];
  clock = 0;
}

class Query {
  private filters: ((r: Row) => boolean)[] = [];
  private op: 'select' | 'insert' | 'upsert' | 'update' | 'delete' = 'select';
  private payload: any;
  private one = false;
  private sortKey?: string;
  private table: string;
  constructor(table: string) { this.table = table; tables[table] ??= []; }
  select(_cols?: string) { return this; }
  insert(rows: any) { this.op = 'insert'; this.payload = rows; return this; }
  upsert(rows: any) { this.op = 'upsert'; this.payload = rows; return this; }
  update(obj: any) { this.op = 'update'; this.payload = obj; return this; }
  delete() { this.op = 'delete'; return this; }
  eq(k: string, v: any) { this.filters.push((r) => r[k] === v); return this; }
  in(k: string, vs: any[]) { this.filters.push((r) => vs.includes(r[k])); return this; }
  order(k: string) { this.sortKey = k; return this; }
  maybeSingle() { this.one = true; return this; }
  private run() {
    const t = tables[this.table];
    const match = (r: Row) => this.filters.every((f) => f(r));
    const stamp = (r: Row) => ({ created_at: String(++clock).padStart(6, '0'), ...r });
    switch (this.op) {
      case 'insert':
        for (const r of [].concat(this.payload)) t.push(stamp(r));
        return { data: null, error: null };
      case 'upsert':
        for (const r of [].concat(this.payload) as Row[]) {
          const i = t.findIndex((x) => x.id === r.id);
          if (i >= 0) t[i] = { ...t[i], ...r }; else t.push(stamp(r));
        }
        return { data: null, error: null };
      case 'update':
        for (const r of t) if (match(r)) Object.assign(r, this.payload);
        return { data: null, error: null };
      case 'delete':
        tables[this.table] = t.filter((r) => !match(r));
        return { data: null, error: null };
      default: {
        let rows = t.filter(match).map((r) => ({ ...r }));
        if (this.sortKey) rows.sort((a, b) => String(a[this.sortKey!]).localeCompare(String(b[this.sortKey!])));
        return { data: this.one ? rows[0] ?? null : rows, error: null };
      }
    }
  }
  then(ok: (v: any) => any, bad?: (e: any) => any) { return Promise.resolve(this.run()).then(ok, bad); }
}

export function createClient(_url: string, _key: string, opts?: { global?: { headers?: Record<string, string> } }) {
  const token = (opts?.global?.headers?.Authorization ?? '').replace(/^Bearer /, '');
  return {
    auth: { async getUser() { return token ? { data: { user: { id: token } }, error: null } : { data: null, error: new Error('no') }; } },
    from: (t: string) => new Query(t),
    async rpc(name: string, args: any) { return { data: rpcs[name]?.(args) ?? [], error: null }; },
  };
}
