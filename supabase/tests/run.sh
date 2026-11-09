#!/usr/bin/env bash
# Runs the migrations and RLS tests against a throwaway plain Postgres database.
# Usage: PGHOST=... PGUSER=... PGPASSWORD=... supabase/tests/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
db="cuenote_test_$$"
createdb "$db"
trap 'dropdb --if-exists "$db"' EXIT
psql -v ON_ERROR_STOP=1 -q -d "$db" -f "$here/stub_supabase.sql"
for f in "$here"/../migrations/*.sql; do
  echo "Applying $(basename "$f")"
  psql -v ON_ERROR_STOP=1 -q -d "$db" -f "$f"
done
psql -v ON_ERROR_STOP=1 -q -o /dev/null -d "$db" -f "$here/rls_test.sql"
