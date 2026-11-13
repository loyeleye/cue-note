-- Two changes to who sees which lessons.
--
-- 1. A teacher's lessons are theirs. When the student lets another teacher
--    "see all my lessons", that teacher still sees a lesson someone else
--    taught only once the teacher who taught it agrees (lesson_view_consent).
--    Each such teacher gets an email and a request on their dashboard, and
--    can take a yes back later. Lessons with no teacher on Cue Note (none set,
--    or only a typed name) need no one's agreement.
-- 2. A debug role, for testing the dashboard: a debug account sees every
--    student through the ordinary teacher dashboard, read-only. Accounts are
--    made debug by hand in the SQL editor (private.debug_user); nothing in the
--    API can add one. To be removed before release.

-- ---------------------------------------------------------------- Debug role
create table private.debug_user (
  user_id  uuid primary key references auth.users (id) on delete cascade,
  added_at timestamptz not null default now()
);
revoke all on private.debug_user from public, anon, authenticated;

create function private.is_debug() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from private.debug_user d where d.user_id = auth.uid())
$$;

-- The dashboard asks this to switch into the debug view.
create function public.am_debug() returns boolean
language sql stable security definer set search_path = '' as $$
  select private.is_debug()
$$;

-- Read-only: an extra permissive SELECT policy on every table with row
-- security. Inserts, updates, deletes and the RPCs that change things still
-- go through the ordinary rules, which a debug account doesn't meet.
do $$
declare t record;
begin
  for t in select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity loop
    execute format('create policy %I on public.%I for select to authenticated using (private.is_debug())',
                   t.relname || '_debug_read', t.relname);
  end loop;
end $$;
create policy storage_debug_read on storage.objects for select to authenticated
  using (bucket_id in ('clips', 'photos', 'scores') and private.is_debug());

-- Read RPCs the dashboard calls.
create or replace function public.list_teachers(student uuid)
returns table (id uuid, alias text, color text, name_public boolean, display_name text)
language sql stable security definer set search_path = '' as $$
  select p.id, p.alias, p.color, p.name_public,
         case when student = auth.uid() or p.id = auth.uid() or p.name_public or private.is_debug()
              then p.display_name end
    from public.teacher_student_share s
    join public.profile p on p.id = s.teacher_id
   where s.student_id = student and s.status = 'active'
     and (student = auth.uid() or private.is_teacher_of(student) or private.is_debug())
   order by s.accepted_at nulls last, p.id
$$;

create or replace function public.storage_used(student uuid)
returns table (category text, files bigint, bytes bigint)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not (private.can_see_student(student) or private.is_debug()) then
    raise exception 'not your student';
  end if;
  return query
    select case
             when o.bucket_id = 'clips' and (storage.foldername(o.name))[2] = 'teacher-notes' then 'voice_notes'
             else o.bucket_id
           end as category,
           count(*)::bigint,
           coalesce(sum((o.metadata ->> 'size')::bigint), 0)::bigint
      from storage.objects o
     where o.bucket_id in ('clips', 'photos', 'scores')
       and (storage.foldername(o.name))[1] = student::text
     group by 1
     order by 1;
end $$;

-- ---------------------------------------------------------------- Consent
create table public.lesson_view_consent (
  id          uuid primary key default gen_random_uuid(),
  student_id  uuid not null references public.profile (id) on delete cascade,
  owner_id    uuid not null references public.profile (id) on delete cascade,  -- taught the lessons
  viewer_id   uuid not null references public.profile (id) on delete cascade,  -- would see them
  status      text not null default 'pending' check (status in ('pending', 'granted', 'declined')),
  created_at  timestamptz not null default now(),
  decided_at  timestamptz,
  emailed_at  timestamptz,
  unique (student_id, owner_id, viewer_id),
  check (owner_id <> viewer_id)
);
alter table public.lesson_view_consent enable row level security;
revoke all on public.lesson_view_consent from anon, authenticated;
grant select on public.lesson_view_consent to authenticated;
-- The three people it concerns can read it; it changes only through decide_lesson_view.
create policy lesson_view_consent_read on public.lesson_view_consent for select to authenticated
  using (owner_id = auth.uid() or viewer_id = auth.uid() or student_id = auth.uid() or private.is_debug());

-- True when [owner] agreed that the caller may see the lessons they taught [student].
create function private.consented(student uuid, owner uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.lesson_view_consent c
     where c.student_id = student and c.owner_id = owner and c.viewer_id = auth.uid() and c.status = 'granted'
  )
$$;

-- Requests for every pair that needs one: each active teacher with "see all"
-- (viewer) and each other active teacher of the student (owner). Existing
-- answers are kept. Wakes the email function when it adds any.
create function private.request_view_consents(student uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  added int;
  url text;
begin
  insert into public.lesson_view_consent (student_id, owner_id, viewer_id)
  select student, o.teacher_id, v.teacher_id
    from public.teacher_student_share v
    join public.teacher_student_share o
      on o.student_id = v.student_id and o.status = 'active' and o.teacher_id is not null
     and o.teacher_id <> v.teacher_id
   where v.student_id = student and v.status = 'active' and v.teacher_id is not null and not v.restricted_to_taught
  on conflict (student_id, owner_id, viewer_id) do nothing;
  get diagnostics added = row_count;
  if added > 0 and exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                            where n.nspname = 'net' and p.proname = 'http_post') then
    select value into url from private.app_config where key = 'consent_email_url';
    if url is not null then
      execute 'select net.http_post(url := $1, body := $2)' using url, '{"wake":true}'::jsonb;
    end if;
  end if;
end $$;

create function public.share_requests_consent() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.status = 'active' and new.teacher_id is not null then
    perform private.request_view_consents(new.student_id);
  end if;
  return new;
end $$;
revoke execute on function public.share_requests_consent() from public, anon, authenticated;
create trigger share_consent_requests after insert or update of status, teacher_id, restricted_to_taught
  on public.teacher_student_share for each row execute function public.share_requests_consent();

-- Shares that already have "see all" get their requests now.
do $$
declare s uuid;
begin
  for s in select distinct student_id from public.teacher_student_share
            where status = 'active' and not restricted_to_taught loop
    perform private.request_view_consents(s);
  end loop;
end $$;

insert into private.app_config (key, value) values
  ('consent_email_url', 'https://tgytbytwofiymsvajdkd.supabase.co/functions/v1/consent-email')
on conflict (key) do nothing;

-- The teacher who taught the lessons says yes or no, and can change it later.
create function public.decide_lesson_view(request uuid, allow boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.lesson_view_consent
     set status = case when allow then 'granted' else 'declined' end, decided_at = now()
   where id = request and owner_id = auth.uid();
  if not found then raise exception 'request not found'; end if;
end $$;
revoke execute on function public.decide_lesson_view(uuid, boolean) from public, anon;
grant execute on function public.decide_lesson_view(uuid, boolean) to authenticated;

-- What the requests on a teacher's dashboard show: who's asking, for which
-- student. Names follow the usual rules: the student's display name (the
-- owner teaches them), and the viewer's alias.
create function public.my_lesson_view_requests()
returns table (id uuid, student_id uuid, student_name text, viewer_id uuid, viewer_alias text,
               status text, created_at timestamptz, decided_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select c.id, c.student_id, sp.display_name, c.viewer_id, vp.alias, c.status, c.created_at, c.decided_at
    from public.lesson_view_consent c
    join public.profile sp on sp.id = c.student_id
    join public.profile vp on vp.id = c.viewer_id
   where c.owner_id = auth.uid() and private.is_teacher_of(c.student_id)
   order by c.status <> 'pending', c.created_at desc
$$;
revoke execute on function public.my_lesson_view_requests() from public, anon;
grant execute on function public.my_lesson_view_requests() to authenticated;

-- ---------------------------------------------------------------- The rules
-- A lesson is closed to a teacher who didn't teach it when the student limits
-- them, or when another teacher on Cue Note taught it and hasn't agreed.
create or replace function private.lesson_open(lesson uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select private.is_debug() or not exists (
    select 1 from public.lesson l
     where l.id = lesson
       and l.student_id is distinct from auth.uid()
       and l.taught_by is distinct from auth.uid()
       and (private.restricted_for(l.student_id)
            or (l.taught_by is not null and not private.consented(l.student_id, l.taught_by))))
$$;

-- Notes and practice: what the caller wrote, what belongs to a lesson open to
-- them, and (with "see all") the student's own and those of a teacher who agreed.
create or replace function private.note_open(student uuid, lesson uuid, author uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select author = auth.uid()
      or student = auth.uid()
      or private.is_debug()
      or (lesson is not null and private.lesson_open(lesson))
      or (lesson is null and not private.restricted_for(student)
          and (author = student or private.consented(student, author)))
$$;

-- Files follow the same rules; a teacher's voice notes count as theirs.
create or replace function private.object_open(bucket text, name text, owner text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  parts text[] := string_to_array(name, '/');
  stu uuid;
begin
  if bucket not in ('clips', 'photos') or parts[1] = auth.uid()::text or private.is_debug() then return true; end if;
  if parts[1] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then return true; end if;
  stu := parts[1]::uuid;
  if parts[2] = 'teacher-notes' then
    return owner = auth.uid()::text
        or (not private.restricted_for(stu)
            and (owner is null or owner !~ '^[0-9a-f-]{36}$' or private.consented(stu, owner::uuid)));
  end if;
  if parts[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return private.lesson_open(parts[2]::uuid);
  end if;
  return not private.restricted_for(stu);
end $$;

revoke execute on function private.is_debug(), private.consented(uuid, uuid), private.request_view_consents(uuid)
  from public, anon;
grant execute on function private.is_debug(), private.consented(uuid, uuid) to authenticated;
revoke execute on function private.request_view_consents(uuid) from authenticated;
revoke execute on function public.am_debug() from public, anon;
grant execute on function public.am_debug() to authenticated;
