#!/usr/bin/env bash
# Runs the migrations and RLS tests against throwaway plain Postgres databases.
# Each test file gets a fresh database, so tests never depend on each other.
# Usage: PGHOST=... PGUSER=... PGPASSWORD=... supabase/tests/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dbs=()
trap 'for d in "${dbs[@]}"; do dropdb --if-exists "$d"; done' EXIT

fresh_db() {
  local db="cuenote_test_$$_$1"
  dbs+=("$db")
  createdb "$db"
  psql -v ON_ERROR_STOP=1 -q -d "$db" -f "$here/stub_supabase.sql"
  for f in "$here"/../migrations/*.sql; do
    psql -v ON_ERROR_STOP=1 -q -d "$db" -f "$f"
  done
  echo "$db"
}

ls "$here"/../migrations/*.sql | xargs -n1 basename | sed 's/^/Migration: /'
n=0
for t in "$here"/*_test.sql; do
  n=$((n + 1))
  db="$(fresh_db "$n")"
  echo "Running $(basename "$t")"
  psql -v ON_ERROR_STOP=1 -q -o /dev/null -d "$db" -f "$t"
done

# The local sample data must load cleanly on top of the migrations.
db="$(fresh_db seed)"
echo "Loading seed.sql"
psql -v ON_ERROR_STOP=1 -q -o /dev/null -d "$db" -f "$here/../seed.sql"
echo "Seed loaded."
