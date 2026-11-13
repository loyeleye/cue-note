-- Email the teacher when a student invites them. Until now an invite was only
-- a row: the teacher saw it only if they happened to sign in to the dashboard
-- with that address. The consent-email function sends invites too; this marks
-- which have been emailed and wakes the function for each new one. Invites
-- already waiting go out on the function's next wake.

alter table public.teacher_student_share add column invite_emailed_at timestamptz;

create function public.wake_share_email() returns trigger
language plpgsql security definer set search_path = '' as $$
declare url text;
begin
  if new.status = 'pending' and exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                                         where n.nspname = 'net' and p.proname = 'http_post') then
    select value into url from private.app_config where key = 'consent_email_url';
    if url is not null then
      execute 'select net.http_post(url := $1, body := $2)' using url, '{"wake":true}'::jsonb;
    end if;
  end if;
  return new;
end $$;
revoke execute on function public.wake_share_email() from public, anon, authenticated;
create trigger share_invite_email after insert on public.teacher_student_share
  for each row execute function public.wake_share_email();

-- Wake it once now, for invites made before this.
do $$
declare url text;
begin
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'net' and p.proname = 'http_post') then
    select value into url from private.app_config where key = 'consent_email_url';
    if url is not null then
      execute 'select net.http_post(url := $1, body := $2)' using url, '{"wake":true}'::jsonb;
    end if;
  end if;
end $$;
