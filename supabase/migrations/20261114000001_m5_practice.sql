-- M5: practice between lessons, and the next lesson's agenda.
--
-- Practice stays on the phone by default. Only three things sync:
--
-- 1. practice_session: the log summary. When the student practiced, for how
--    long, and which notes (cards) they worked on. No audio.
-- 2. practice_take: a practice take the student chose to share before the
--    next lesson, with its pitch summary and how it felt. A student shares
--    up to three at a time; once the teacher has listened (heard_at), the
--    take leaves the agenda and frees its place.
-- 3. student_question: a question for the teacher, pinned to a card, a bar
--    of a song, or neither. The teacher can answer it, or mark it answered
--    in the lesson; the student can drop it.
--
-- The agenda for the next lesson is the open questions and the shared takes
-- not heard yet, newest first. The lesson date it is for already lives on
-- teacher_student_share.next_lesson_at, which the teacher sets.
--
-- The student owns every row. A teacher with an active share reads them,
-- and changes only their own columns (heard_at; an answer). A teacher the
-- student limited to their own lessons sees only the takes and questions
-- that belong to a lesson they taught; the log summary holds no lesson
-- content, so every active teacher sees it.

-- ---------------------------------------------------------------- Tables
create table public.practice_session (
  id         uuid primary key,                    -- generated on the phone
  student_id uuid not null references public.profile (id) on delete cascade,
  started_at timestamptz not null,
  ended_at   timestamptz not null,
  minutes    int not null check (minutes between 0 and 600),
  -- [{"feedback_id": "<uuid>", "takes": 3}], one entry per note practiced
  items      jsonb not null default '[]' check (jsonb_typeof(items) = 'array'),
  updated_at timestamptz not null default now(),
  check (ended_at >= started_at)
);
create index practice_session_student_idx on public.practice_session (student_id, started_at desc);

create table public.practice_take (
  id           uuid primary key,                  -- generated on the phone
  student_id   uuid not null references public.profile (id) on delete cascade,
  feedback_id  uuid references public.feedback (id) on delete set null,
  lesson_id    uuid not null references public.lesson (id) on delete cascade,
  session_id   uuid references public.practice_session (id) on delete set null,
  recorded_at  timestamptz not null,
  duration_ms  int not null check (duration_ms > 0),
  clip_path    text,                              -- clips: <student>/<lesson>/practice-<id>.m4a
  -- [{"sung": 60.1, "target": 60.0, "verdict": "IN_TUNE"}], one per note
  pitch        jsonb check (pitch is null or jsonb_typeof(pitch) = 'array'),
  feeling_note text check (length(feeling_note) <= 500),
  shared_at    timestamptz not null default now(),
  heard_at     timestamptz,
  heard_by     uuid references public.profile (id) on delete set null
);
create index practice_take_student_idx on public.practice_take (student_id, shared_at desc);
create index practice_take_lesson_idx on public.practice_take (lesson_id);

create type public.question_status as enum ('open', 'answered', 'dropped');

create table public.student_question (
  id          uuid primary key,                   -- generated on the phone
  student_id  uuid not null references public.profile (id) on delete cascade,
  feedback_id uuid references public.feedback (id) on delete set null,
  lesson_id   uuid references public.lesson (id) on delete cascade,
  song_id     uuid references public.song (id) on delete set null,
  bar         int check (bar > 0),
  body        text not null check (length(btrim(body)) between 1 and 1000),
  status      public.question_status not null default 'open',
  answer      text check (length(answer) <= 2000),
  answered_by uuid references public.profile (id) on delete set null,
  answered_at timestamptz,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index student_question_student_idx on public.student_question (student_id, created_at desc);
create index student_question_lesson_idx on public.student_question (lesson_id) where lesson_id is not null;

-- ---------------------------------------------------------------- Guards
-- A card or lesson named on a take or question must be the student's own.
create function private.guard_practice_links() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.lesson_id is not null and private.lesson_student(new.lesson_id) is distinct from new.student_id then
    raise exception 'that lesson isn''t this student''s';
  end if;
  if new.feedback_id is not null then
    if private.feedback_student(new.feedback_id) is distinct from new.student_id then
      raise exception 'that note isn''t this student''s';
    end if;
    -- The card's lesson is the take's lesson, so the "only my lessons" limit
    -- can't be dodged by leaving lesson_id empty.
    new.lesson_id := (select f.lesson_id from public.feedback f where f.id = new.feedback_id);
  end if;
  return new;
end $$;

-- The links a row just lost because the card or song they named was deleted
-- (on delete set null). A teacher may delete a card or a song, so those
-- changes reach the guards below as the teacher's own.
create function private.unlinked(o jsonb, n jsonb) returns text[]
language sql immutable set search_path = '' as $$
  select coalesce(array_agg(k), '{}') from unnest(array['feedback_id', 'song_id', 'session_id']) k
   where n ? k and n -> k = 'null'::jsonb and o -> k <> 'null'::jsonb
$$;

-- Up to three shared takes wait for the teacher at a time.
create function private.guard_practice_take() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then return new; end if;   -- service role
  if tg_op = 'INSERT' then
    if (select count(*) from public.practice_take t
         where t.student_id = new.student_id and t.heard_at is null) >= 3 then
      raise exception 'you can share up to three practice takes at a time';
    end if;
    new.heard_at := null; new.heard_by := null;
    return new;
  end if;
  if auth.uid() = old.student_id then
    -- The student can't mark their own take as heard, or move it.
    if new.heard_at is distinct from old.heard_at or new.heard_by is distinct from old.heard_by
       or new.student_id is distinct from old.student_id then
      raise exception 'only a teacher can mark a take as heard';
    end if;
    return new;
  end if;
  -- A teacher marks it heard (or not), and nothing else. (A card they delete
  -- unlinks its takes; that arrives here as feedback_id going to null.)
  if (to_jsonb(new) - 'heard_at' - 'heard_by' - private.unlinked(to_jsonb(old), to_jsonb(new)))
     is distinct from (to_jsonb(old) - 'heard_at' - 'heard_by' - private.unlinked(to_jsonb(old), to_jsonb(new))) then
    raise exception 'teachers may only mark a practice take as heard';
  end if;
  new.heard_by := case when new.heard_at is null then null else auth.uid() end;
  return new;
end $$;

create function private.guard_student_question() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.updated_at := now();
  if auth.uid() is null then return new; end if;   -- service role
  if tg_op = 'INSERT' then
    if new.status <> 'open' or new.answer is not null or new.answered_by is not null or new.answered_at is not null then
      raise exception 'a new question starts open, with no answer';
    end if;
    return new;
  end if;
  if auth.uid() = old.student_id then
    -- The student rewords, re-pins, drops or reopens; the answer is the teacher's.
    if new.answer is distinct from old.answer or new.answered_by is distinct from old.answered_by
       or new.answered_at is distinct from old.answered_at or new.student_id is distinct from old.student_id then
      raise exception 'only a teacher can answer a question';
    end if;
    if new.status = 'answered' and old.status <> 'answered' then
      raise exception 'only a teacher can mark a question answered';
    end if;
    return new;
  end if;
  -- A teacher answers (with or without words), or reopens.
  if (to_jsonb(new) - 'status' - 'answer' - 'answered_by' - 'answered_at' - 'updated_at' - private.unlinked(to_jsonb(old), to_jsonb(new)))
     is distinct from (to_jsonb(old) - 'status' - 'answer' - 'answered_by' - 'answered_at' - 'updated_at' - private.unlinked(to_jsonb(old), to_jsonb(new))) then
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

create function private.touch_updated_at() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

revoke execute on function private.unlinked(jsonb, jsonb) from public, anon;
grant execute on function private.unlinked(jsonb, jsonb) to authenticated;
revoke execute on function private.guard_practice_links(), private.guard_practice_take(),
  private.guard_student_question(), private.touch_updated_at() from public, anon, authenticated;

create trigger practice_session_touch before update on public.practice_session
  for each row execute function private.touch_updated_at();
create trigger practice_take_links before insert or update on public.practice_take
  for each row execute function private.guard_practice_links();
create trigger practice_take_guard before insert or update on public.practice_take
  for each row execute function private.guard_practice_take();
create trigger student_question_links before insert or update on public.student_question
  for each row execute function private.guard_practice_links();
create trigger student_question_guard before insert or update on public.student_question
  for each row execute function private.guard_student_question();

-- ---------------------------------------------------------------- Row-level security
alter table public.practice_session enable row level security;
alter table public.practice_take    enable row level security;
alter table public.student_question enable row level security;

create policy practice_session_student_all on public.practice_session for all to authenticated
  using (student_id = (select auth.uid())) with check (student_id = (select auth.uid()));
create policy practice_session_teacher_select on public.practice_session for select to authenticated
  using (private.is_teacher_of(student_id));

create policy practice_take_student_all on public.practice_take for all to authenticated
  using (student_id = (select auth.uid())) with check (student_id = (select auth.uid()));
create policy practice_take_teacher_select on public.practice_take for select to authenticated
  using (private.is_teacher_of(student_id));
create policy practice_take_teacher_update on public.practice_take for update to authenticated
  using (private.is_teacher_of(student_id)) with check (private.is_teacher_of(student_id));
-- The student's limit: a restricted teacher sees only takes from lessons they taught.
create policy practice_take_taught_only on public.practice_take as restrictive for all to authenticated
  using (private.note_open(student_id, lesson_id, student_id));

create policy student_question_student_all on public.student_question for all to authenticated
  using (student_id = (select auth.uid())) with check (student_id = (select auth.uid()));
create policy student_question_teacher_select on public.student_question for select to authenticated
  using (private.is_teacher_of(student_id));
create policy student_question_teacher_update on public.student_question for update to authenticated
  using (private.is_teacher_of(student_id)) with check (private.is_teacher_of(student_id));
create policy student_question_taught_only on public.student_question as restrictive for all to authenticated
  using (private.note_open(student_id, lesson_id, student_id));

revoke all on public.practice_session, public.practice_take, public.student_question from anon;

-- ---------------------------------------------------------------- Files
-- A shared take's clip goes in its lesson's folder, as
-- clips/<student>/<lesson>/practice-<take>.m4a, so the existing storage
-- rules cover it: the student writes it, their teachers read it, the "only
-- my lessons" limit applies, and it goes when the lesson is deleted.
