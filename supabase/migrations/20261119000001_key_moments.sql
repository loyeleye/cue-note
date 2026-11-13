-- Key moments: what a teacher sees of a lesson by default, the full lesson
-- as a one-off that both people agree to, and replies in the teacher's voice.
--
-- 1. By default a teacher (one the lesson is open to) sees the lesson, every
--    card with the teacher's own words, and the sketches. Of the student's
--    singing they hear only the takes on cards marked "Practice this", plus
--    the practice takes the student shares. The other takes, their clips and
--    the exercise timeline stay the student's.
-- 2. full_lesson_pass opens one lesson in full to one teacher when both say
--    yes: the student invites and the teacher accepts, or the teacher asks
--    and the student agrees. It ends on its own exactly 14 days after the
--    yes; either person can end it sooner.
-- 3. A teacher can answer a question or reply to a shared take with a short
--    voice note (clips/<student>/teacher-notes/..., as voice notes already are).

-- ---------------------------------------------------------------- The pass
create table public.full_lesson_pass (
  id         uuid primary key default gen_random_uuid(),
  lesson_id  uuid not null references public.lesson (id) on delete cascade,
  student_id uuid not null references public.profile (id) on delete cascade,
  teacher_id uuid not null references public.profile (id) on delete cascade,
  asked_by   text not null check (asked_by in ('student', 'teacher')),
  status     text not null default 'pending' check (status in ('pending', 'active', 'declined', 'ended')),
  -- why, in the asker's words ("recital on the 30th")
  note       text check (length(note) <= 500),
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  ends_at    timestamptz,
  ended_at   timestamptz
);
-- One open ask or pass per lesson and teacher.
create unique index full_lesson_pass_open_idx on public.full_lesson_pass (lesson_id, teacher_id)
  where status in ('pending', 'active');
create index full_lesson_pass_student_idx on public.full_lesson_pass (student_id, created_at desc);
create index full_lesson_pass_teacher_idx on public.full_lesson_pass (teacher_id, created_at desc);

alter table public.full_lesson_pass enable row level security;
create policy full_lesson_pass_select on public.full_lesson_pass for select to authenticated
  using (student_id = (select auth.uid()) or teacher_id = (select auth.uid()) or private.is_debug());
-- Changes go through the functions below only.
revoke all on public.full_lesson_pass from anon;
revoke insert, update, delete on public.full_lesson_pass from authenticated;

-- A pass lasts exactly 14 days, so neither person has to guess when it ends.
create function private.pass_end() returns timestamptz
language sql stable set search_path = '' as $$
  select now() + interval '14 days'
$$;

-- The caller sees this lesson in full: they're the student, a debug
-- account, or a teacher with a pass that hasn't ended.
create function private.full_lesson_for(lesson uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select private.is_debug()
      or private.lesson_student(lesson) = auth.uid()
      or exists (select 1 from public.full_lesson_pass p
                  where p.lesson_id = lesson and p.teacher_id = auth.uid()
                    and p.status = 'active' and p.ends_at > now())
$$;

-- A take is open to the caller when the lesson is open in full, or a card
-- marked "Practice this" is about it: one of the moments to work on.
create function private.take_open(take uuid, lesson uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select private.full_lesson_for(lesson)
      or exists (select 1 from public.feedback f
                  where f.take_id = take and f.practice_flag = 'practice_this')
$$;

-- Clips of takes follow their take: clips/<student>/<lesson>/<take id>.m4a.
-- Teachers' words (a card's clip), voice notes and shared practice takes
-- keep the rules they already have.
create function private.clip_open(bucket text, name text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  parts text[] := string_to_array(name, '/');
  uuid_re constant text := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
  file text;
begin
  if bucket <> 'clips' or parts[1] = auth.uid()::text or private.is_debug() then return true; end if;
  if coalesce(parts[2], '') !~ uuid_re or array_length(parts, 1) <> 3 then return true; end if;
  file := regexp_replace(parts[3], '\.[^.]*$', '');
  if file !~ uuid_re then return true; end if;
  if exists (select 1 from public.take t where t.id = file::uuid and t.lesson_id = parts[2]::uuid) then
    return private.take_open(file::uuid, parts[2]::uuid);
  end if;
  return true;
end $$;

revoke execute on function private.pass_end(), private.full_lesson_for(uuid),
  private.take_open(uuid, uuid), private.clip_open(text, text) from public, anon;
grant execute on function private.full_lesson_for(uuid), private.take_open(uuid, uuid),
  private.clip_open(text, text) to authenticated;

-- Restrictive, so they only ever take access away.
create policy take_key_moments on public.take as restrictive for all to authenticated
  using (private.take_open(id, lesson_id));
create policy exercise_key_moments on public.exercise as restrictive for all to authenticated
  using (private.full_lesson_for(lesson_id));
create policy storage_key_moments on storage.objects as restrictive for all to authenticated
  using (private.clip_open(bucket_id, name));

-- ---------------------------------------------------------------- Asking and answering
-- The student invites one of their teachers (teacher = that teacher), or a
-- teacher asks for themselves (teacher left out). If the other person has
-- already asked for the same lesson, this is their yes and the pass starts.
create function public.ask_full_lesson(lesson uuid, teacher uuid default null, note text default null)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  stu uuid := private.lesson_student(lesson);
  who text;
  t uuid;
  p public.full_lesson_pass;
begin
  if stu is null then raise exception 'lesson not found'; end if;
  if auth.uid() = stu then
    who := 'student'; t := teacher;
    if t is null or not exists (select 1 from public.teacher_student_share s
                                 where s.student_id = stu and s.teacher_id = t and s.status = 'active') then
      raise exception 'that person isn''t one of your teachers';
    end if;
    -- Only a lesson that teacher can already open; the pass adds the rest of it.
    if not exists (select 1 from public.lesson l
                    where l.id = lesson
                      and (l.taught_by = t or exists (select 1 from public.teacher_student_share s
                                                        where s.student_id = stu and s.teacher_id = t
                                                          and s.status = 'active' and not s.restricted_to_taught))) then
      raise exception 'that teacher can''t open this lesson; let them see it first';
    end if;
  else
    who := 'teacher'; t := auth.uid();
    if not private.is_teacher_of(stu) or not private.lesson_open(lesson) then
      raise exception 'lesson not found';
    end if;
  end if;

  select * into p from public.full_lesson_pass x
   where x.lesson_id = lesson and x.teacher_id = t and x.status in ('pending', 'active') for update;
  if found then
    if p.status = 'pending' and p.asked_by <> who then
      update public.full_lesson_pass
         set status = 'active', decided_at = now(), ends_at = private.pass_end()
       where id = p.id;
    end if;
    return p.id;
  end if;

  insert into public.full_lesson_pass (lesson_id, student_id, teacher_id, asked_by, note)
  values (lesson, stu, t, who, nullif(btrim(coalesce(note, '')), ''))
  returning id into p.id;
  return p.id;
end $$;

-- The person who didn't ask says yes or "not this time".
create function public.answer_full_lesson(pass uuid, yes boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare
  p public.full_lesson_pass;
begin
  select * into p from public.full_lesson_pass x where x.id = pass for update;
  if not found or auth.uid() not in (p.student_id, p.teacher_id) then raise exception 'request not found'; end if;
  if p.status <> 'pending' then raise exception 'that request was already answered'; end if;
  if (p.asked_by = 'student' and auth.uid() <> p.teacher_id)
     or (p.asked_by = 'teacher' and auth.uid() <> p.student_id) then
    raise exception 'the other person answers this one';
  end if;
  if yes then
    update public.full_lesson_pass
       set status = 'active', decided_at = now(), ends_at = private.pass_end()
     where id = pass;
  else
    update public.full_lesson_pass set status = 'declined', decided_at = now() where id = pass;
  end if;
end $$;

-- Either person ends a pass, or takes back an ask, at any time.
create function public.end_full_lesson(pass uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.full_lesson_pass
     set status = 'ended', ended_at = now()
   where id = pass and auth.uid() in (student_id, teacher_id) and status in ('pending', 'active');
end $$;

revoke execute on function public.ask_full_lesson(uuid, uuid, text), public.answer_full_lesson(uuid, boolean),
  public.end_full_lesson(uuid) from public, anon;
grant execute on function public.ask_full_lesson(uuid, uuid, text), public.answer_full_lesson(uuid, boolean),
  public.end_full_lesson(uuid) to authenticated;

-- ---------------------------------------------------------------- Voice replies
alter table public.student_question
  add column answer_clip_path text;   -- clips/<student>/teacher-notes/<file>
alter table public.practice_take
  add column reply           text check (length(reply) <= 2000),
  add column reply_clip_path text,    -- clips/<student>/teacher-notes/<file>
  add column replied_by      uuid references public.profile (id) on delete set null,
  add column replied_at      timestamptz;

-- A voice reply must be a voice note in this student's folder.
create function private.teacher_note_path(student uuid, path text) returns boolean
language sql immutable set search_path = '' as $$
  select path is null or path like student::text || '/teacher-notes/%'
$$;
alter table public.student_question
  add constraint student_question_answer_clip check (private.teacher_note_path(student_id, answer_clip_path));
alter table public.practice_take
  add constraint practice_take_reply_clip check (private.teacher_note_path(student_id, reply_clip_path));

-- Questions: the teacher's answer may now be a voice note too.
create or replace function private.guard_student_question() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.updated_at := now();
  if auth.uid() is null then return new; end if;   -- service role
  if tg_op = 'INSERT' then
    if new.status <> 'open' or new.answer is not null or new.answer_clip_path is not null
       or new.answered_by is not null or new.answered_at is not null then
      raise exception 'a new question starts open, with no answer';
    end if;
    return new;
  end if;
  if auth.uid() = old.student_id then
    -- The student rewords, re-pins, drops or reopens; the answer is the teacher's.
    if new.answer is distinct from old.answer or new.answer_clip_path is distinct from old.answer_clip_path
       or new.answered_by is distinct from old.answered_by
       or new.answered_at is distinct from old.answered_at or new.student_id is distinct from old.student_id then
      raise exception 'only a teacher can answer a question';
    end if;
    if new.status = 'answered' and old.status <> 'answered' then
      raise exception 'only a teacher can mark a question answered';
    end if;
    return new;
  end if;
  -- A teacher answers (in words, by voice, or neither), or reopens.
  if (to_jsonb(new) - 'status' - 'answer' - 'answer_clip_path' - 'answered_by' - 'answered_at' - 'updated_at' - private.unlinked(to_jsonb(old), to_jsonb(new)))
     is distinct from (to_jsonb(old) - 'status' - 'answer' - 'answer_clip_path' - 'answered_by' - 'answered_at' - 'updated_at' - private.unlinked(to_jsonb(old), to_jsonb(new))) then
    raise exception 'teachers may only answer a question';
  end if;
  if new.status = 'dropped' and old.status <> 'dropped' then
    raise exception 'only the student can drop a question';
  end if;
  if new.status = 'answered' then
    new.answered_by := auth.uid();
    new.answered_at := coalesce(old.answered_at, now());
  else
    new.answered_by := null; new.answered_at := null;
  end if;
  return new;
end $$;

-- Shared takes: a teacher marks one heard, and may reply in words or by voice.
create or replace function private.guard_practice_take() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  mine constant text[] := array['heard_at', 'heard_by', 'reply', 'reply_clip_path', 'replied_by', 'replied_at'];
begin
  if auth.uid() is null then return new; end if;   -- service role
  if tg_op = 'INSERT' then
    if (select count(*) from public.practice_take t
         where t.student_id = new.student_id and t.heard_at is null) >= 3 then
      raise exception 'you can share up to three practice takes at a time';
    end if;
    new.heard_at := null; new.heard_by := null;
    new.reply := null; new.reply_clip_path := null; new.replied_by := null; new.replied_at := null;
    return new;
  end if;
  if auth.uid() = old.student_id then
    if new.heard_at is distinct from old.heard_at or new.heard_by is distinct from old.heard_by
       or new.student_id is distinct from old.student_id then
      raise exception 'only a teacher can mark a take as heard';
    end if;
    if new.reply is distinct from old.reply or new.reply_clip_path is distinct from old.reply_clip_path
       or new.replied_by is distinct from old.replied_by or new.replied_at is distinct from old.replied_at then
      raise exception 'only a teacher can reply to a take';
    end if;
    return new;
  end if;
  if (to_jsonb(new) - mine - private.unlinked(to_jsonb(old), to_jsonb(new)))
     is distinct from (to_jsonb(old) - mine - private.unlinked(to_jsonb(old), to_jsonb(new))) then
    raise exception 'teachers may only mark a practice take as heard, or reply to it';
  end if;
  if new.reply is distinct from old.reply or new.reply_clip_path is distinct from old.reply_clip_path then
    new.reply := nullif(btrim(coalesce(new.reply, '')), '');
    if new.reply is null and new.reply_clip_path is null then
      new.replied_by := null; new.replied_at := null;
    else
      new.replied_by := auth.uid(); new.replied_at := now();
      -- A reply means it was heard.
      new.heard_at := coalesce(new.heard_at, now());
    end if;
  else
    new.replied_by := old.replied_by; new.replied_at := old.replied_at;
  end if;
  new.heard_by := case when new.heard_at is null then null else coalesce(old.heard_by, auth.uid()) end;
  return new;
end $$;
