-- Exercises: a lesson is a run of warmups and song sections, each with the
-- student's tries (takes) and the teacher's feedback in between. Feedback
-- often spans several tries, so a card belongs to its exercise as a whole,
-- and to one take only when it is clearly about that try.
--
-- The phone finds exercises and uploads them like takes: the student owns the
-- rows, and a shared teacher reads them. Ids are hashes of the phone's own
-- ids, so uploading a lesson again updates the same rows.

create table public.exercise (
  id         uuid primary key,
  lesson_id  uuid not null references public.lesson (id) on delete cascade,
  start_ms   bigint not null,
  end_ms     bigint not null check (end_ms > start_ms),
  kind       text not null default 'warmup' check (kind in ('warmup', 'song')),
  -- a name the student or teacher gives it ("Lip trill on 5-note scale")
  title      text,
  -- why the phone started a new exercise here, for checking its guesses
  reason     text not null default '',
  created_at timestamptz not null default now()
);
create index exercise_lesson_idx on public.exercise (lesson_id, start_ms);

alter table public.take
  add column exercise_id uuid references public.exercise (id) on delete set null;
alter table public.feedback
  add column exercise_id uuid references public.exercise (id) on delete set null;
create index take_exercise_idx on public.take (exercise_id);
create index feedback_exercise_idx on public.feedback (exercise_id);

alter table public.exercise enable row level security;

create policy exercise_student_all on public.exercise for all to authenticated
  using (private.lesson_student(lesson_id) = auth.uid())
  with check (private.lesson_student(lesson_id) = auth.uid());
create policy exercise_teacher_select on public.exercise for select to authenticated
  using (private.is_teacher_of(private.lesson_student(lesson_id)));
-- like takes, only lessons the student has opened to this teacher
create policy exercise_taught_only on public.exercise as restrictive for all to authenticated
  using (private.lesson_open(lesson_id));
