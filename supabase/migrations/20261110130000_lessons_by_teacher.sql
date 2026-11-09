-- Who taught each lesson, and two ways to see only those lessons.
--
-- 1. lesson.taught_by: the student picks it on the phone before recording
--    ("Who's teaching today?"). A teacher can also mark an older lesson as
--    theirs, or take their mark off, from the dashboard (claim_lesson).
-- 2. teacher_student_share.show_only_taught: the teacher's own preference.
--    The dashboard then lists only their lessons. It's a view setting, not
--    a lock: they can switch it off at any time.
-- 3. teacher_student_share.restricted_to_taught: set by the student. That
--    teacher can then open only the lessons they taught, and only the notes
--    and practice they wrote or that belong to those lessons. This one is
--    enforced by the database, through restrictive policies that sit on top
--    of the existing ones, so every lesson-level table is covered in one
--    place. Songs and scores stay shared: they belong to the student.

-- ---------------------------------------------------------------- Columns
alter table public.lesson
  add column taught_by uuid references public.profile (id) on delete set null;
create index lesson_taught_by_idx on public.lesson (taught_by) where taught_by is not null;

alter table public.teacher_student_share
  add column show_only_taught     boolean not null default false,
  add column restricted_to_taught boolean not null default false;

grant update (status, next_lesson_at, show_only_taught, restricted_to_taught)
  on public.teacher_student_share to authenticated;

-- The student could only revoke a share before; now they also change the
-- limit on an active one. What may change is still decided by the column
-- grants above and the guard triggers (status can only become 'revoked').
drop policy share_update on public.teacher_student_share;
create policy share_update on public.teacher_student_share for update to authenticated
  using (student_id = auth.uid() or teacher_id = auth.uid())
  with check (student_id = auth.uid() or teacher_id = auth.uid());

-- The student sets the restriction; the teacher sets their own preference.
create function public.guard_share_settings() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then return new; end if;
  if new.restricted_to_taught is distinct from old.restricted_to_taught and auth.uid() <> old.student_id then
    raise exception 'only the student can limit a teacher to their own lessons';
  end if;
  if new.show_only_taught is distinct from old.show_only_taught and auth.uid() is distinct from old.teacher_id then
    raise exception 'only the teacher can change what their dashboard shows';
  end if;
  return new;
end $$;
create trigger share_settings_guard before update on public.teacher_student_share
  for each row execute function public.guard_share_settings();

-- A lesson can only be credited to someone who teaches (or taught) the student.
create function public.guard_lesson_teacher() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.taught_by is null or auth.uid() is null then return new; end if;
  if tg_op = 'UPDATE' and new.taught_by is not distinct from old.taught_by then return new; end if;
  if not exists (select 1 from public.teacher_student_share s
                  where s.student_id = new.student_id and s.teacher_id = new.taught_by) then
    raise exception 'that person isn''t one of this student''s teachers';
  end if;
  return new;
end $$;
create trigger lesson_teacher_guard before insert or update on public.lesson
  for each row execute function public.guard_lesson_teacher();

-- ---------------------------------------------------------------- Helpers
-- True when the student has limited the caller to the lessons they taught.
create function private.restricted_for(student uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.teacher_student_share s
     where s.student_id = student and s.teacher_id = auth.uid()
       and s.status = 'active' and s.restricted_to_taught
  )
$$;

-- False only when the caller is a restricted teacher and didn't teach this
-- lesson. Everything else (the student, unrestricted teachers, a lesson that
-- doesn't exist yet) is left to the ordinary policies.
create function private.lesson_open(lesson uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select not exists (
    select 1 from public.lesson l
     where l.id = lesson
       and l.student_id is distinct from auth.uid()
       and l.taught_by is distinct from auth.uid()
       and private.restricted_for(l.student_id))
$$;

create function private.feedback_open(fb uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select coalesce((select private.lesson_open(f.lesson_id) from public.feedback f where f.id = fb), true)
$$;

-- Notes and practice: a restricted teacher sees what they wrote, and what
-- belongs to a lesson they taught.
create function private.note_open(student uuid, lesson uuid, author uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select author = auth.uid()
      or not private.restricted_for(student)
      or (lesson is not null and private.lesson_open(lesson))
$$;

-- Files: clips/<student>/<lesson>/... and photos/<student>/<lesson>/...;
-- teacher voice notes are clips/<student>/teacher-notes/...
create function private.object_open(bucket text, name text, owner text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  parts text[] := string_to_array(name, '/');
begin
  if bucket not in ('clips', 'photos') or parts[1] = auth.uid()::text then return true; end if;
  if parts[1] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then return true; end if;
  if not private.restricted_for(parts[1]::uuid) then return true; end if;
  if parts[2] = 'teacher-notes' then return owner = auth.uid()::text; end if;
  if parts[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return private.lesson_open(parts[2]::uuid);
  end if;
  return false;
end $$;

revoke execute on function private.restricted_for(uuid), private.lesson_open(uuid), private.feedback_open(uuid),
  private.note_open(uuid, uuid, uuid), private.object_open(text, text, text) from public, anon;
grant execute on function private.restricted_for(uuid), private.lesson_open(uuid), private.feedback_open(uuid),
  private.note_open(uuid, uuid, uuid), private.object_open(text, text, text) to authenticated;

-- ---------------------------------------------------------------- Restrictive policies
-- A restrictive policy must pass as well as one of the ordinary ones, so these
-- only ever take access away.
create policy lesson_taught_only on public.lesson as restrictive for all to authenticated
  using (private.lesson_open(id));
create policy take_taught_only on public.take as restrictive for all to authenticated
  using (private.lesson_open(lesson_id));
create policy feedback_taught_only on public.feedback as restrictive for all to authenticated
  using (private.lesson_open(lesson_id));
create policy photo_taught_only on public.photo_mark as restrictive for all to authenticated
  using (private.lesson_open(lesson_id));
create policy card_version_taught_only on public.card_version as restrictive for all to authenticated
  using (private.feedback_open(feedback_id));
create policy checkpoint_taught_only on public.lesson_checkpoint as restrictive for all to authenticated
  using (private.lesson_open(lesson_id));
create policy comment_taught_only on public.comment as restrictive for all to authenticated
  using (private.note_open(student_id, lesson_id, author_id));
create policy practice_taught_only on public.practice_item as restrictive for all to authenticated
  using (private.note_open(student_id, lesson_id, author_id));
create policy storage_taught_only on storage.objects as restrictive for all to authenticated
  using (private.object_open(bucket_id, name, owner_id::text));

-- Presence on a lesson follows the same rule.
create or replace function private.lesson_topic_teacher(topic text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
begin
  if topic !~ '^lesson:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return false;
  end if;
  return private.is_teacher_of(private.lesson_student(substr(topic, 8)::uuid))
     and private.lesson_open(substr(topic, 8)::uuid);
end $$;

-- ---------------------------------------------------------------- claim_lesson
-- From the dashboard: "This was my lesson" (mine = true) marks an unmarked
-- lesson as the caller's; mine = false takes their own mark off. A lesson
-- someone else is marked on stays theirs; the student can change it.
create function public.claim_lesson(lesson uuid, mine boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare
  stu uuid;
  cur uuid;
begin
  select l.student_id, l.taught_by into stu, cur from public.lesson l where l.id = claim_lesson.lesson for update;
  if not found or not private.is_teacher_of(stu) or not private.lesson_open(claim_lesson.lesson) then
    raise exception 'lesson not found';
  end if;
  if mine then
    if cur is not null and cur <> auth.uid() then
      raise exception 'another teacher is already marked on this lesson';
    end if;
    update public.lesson set taught_by = auth.uid() where id = claim_lesson.lesson;
  elsif cur = auth.uid() then
    -- A restricted teacher would lose the lesson by un-marking it; that's
    -- their choice, so it is allowed.
    update public.lesson set taught_by = null where id = claim_lesson.lesson;
  end if;
end $$;
revoke execute on function public.claim_lesson(uuid, boolean) from public, anon;
grant execute on function public.claim_lesson(uuid, boolean) to authenticated;
