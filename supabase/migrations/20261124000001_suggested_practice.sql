-- Suggested practice: what a teacher sets for a student's daily plan, and
-- each sitting the phone ran.
--
-- The phone puts the teacher's own "practice this" notes in an order for
-- the day (see PracticePlan in the Android app). It never adds homework.
-- The teacher can steer it, in about ten seconds a student:
--
-- 1. practice_plan_setting, one row per teacher and student: the main point
--    (a card the plan always starts with), about how many minutes and days a
--    week, the longest sitting the student may pick, rest until a date
--    (listening only until then), and the words of the gentle start and the
--    cool-down. Each teacher writes only their own row; the student reads
--    all of theirs. With two teachers the phone takes the stricter of each.
-- 2. practice_plan_log, one row per sitting, written by the phone: the day,
--    how the voice felt, the length picked, how many steps were done, and
--    which notes were skipped. No audio. Like practice_session, every
--    active teacher reads it.

-- ---------------------------------------------------------------- Tables
create table public.practice_plan_setting (
  student_id         uuid not null references public.profile (id) on delete cascade,
  teacher_id         uuid not null references public.profile (id) on delete cascade,
  pinned_feedback_id uuid references public.feedback (id) on delete set null,
  minutes            int check (minutes in (10, 20, 30)),
  days_per_week      int check (days_per_week between 1 and 7),
  max_minutes        int check (max_minutes in (10, 20, 30)),
  rest_until         date,
  gentle_start       text check (length(btrim(gentle_start)) between 1 and 300),
  cool_down          text check (length(btrim(cool_down)) between 1 and 300),
  updated_at         timestamptz not null default now(),
  primary key (student_id, teacher_id)
);

create table public.practice_plan_log (
  id         uuid primary key,                    -- generated on the phone
  student_id uuid not null references public.profile (id) on delete cascade,
  day        date not null,
  started_at timestamptz not null,
  ended_at   timestamptz not null,
  voice      text not null check (voice in ('fine', 'tired', 'hoarse')),
  minutes    int not null check (minutes between 0 and 120),
  steps      int not null check (steps between 0 and 20),
  done       int not null check (done between 0 and 20),
  -- feedback ids of the notes skipped (null entries are the gentle start and so on, left out)
  skipped    jsonb not null default '[]' check (jsonb_typeof(skipped) = 'array'),
  updated_at timestamptz not null default now(),
  check (ended_at >= started_at)
);
create index practice_plan_log_student_idx on public.practice_plan_log (student_id, started_at desc);

-- ---------------------------------------------------------------- Guards
-- A teacher writes only their own row, about a student they teach, and the
-- main point must be one of that student's cards.
create function private.guard_practice_plan_setting() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.updated_at := now();
  if new.pinned_feedback_id is not null
     and private.feedback_student(new.pinned_feedback_id) is distinct from new.student_id then
    raise exception 'that note isn''t this student''s';
  end if;
  return new;
end $$;

revoke execute on function private.guard_practice_plan_setting() from public, anon, authenticated;

create trigger practice_plan_setting_guard before insert or update on public.practice_plan_setting
  for each row execute function private.guard_practice_plan_setting();
create trigger practice_plan_log_touch before update on public.practice_plan_log
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------- Row-level security
alter table public.practice_plan_setting enable row level security;
alter table public.practice_plan_log     enable row level security;

create policy practice_plan_setting_student_select on public.practice_plan_setting for select to authenticated
  using (student_id = (select auth.uid()));
create policy practice_plan_setting_teacher_all on public.practice_plan_setting for all to authenticated
  using (teacher_id = (select auth.uid()) and private.is_teacher_of(student_id))
  with check (teacher_id = (select auth.uid()) and private.is_teacher_of(student_id));

create policy practice_plan_log_student_all on public.practice_plan_log for all to authenticated
  using (student_id = (select auth.uid())) with check (student_id = (select auth.uid()));
create policy practice_plan_log_teacher_select on public.practice_plan_log for select to authenticated
  using (private.is_teacher_of(student_id));

revoke all on public.practice_plan_setting, public.practice_plan_log from anon;
