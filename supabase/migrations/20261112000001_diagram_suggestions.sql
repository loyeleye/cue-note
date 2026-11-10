-- Suggested diagrams: "Does this diagram help?" on a card.
--
-- When the cards are made (extract-cues), or a sketch is captioned
-- (caption-sketch), the model may pick one diagram from the app's fixed
-- library (web/static/diagrams/index.json) for a card, with how sure it is.
-- Only picks at or above the bar (0.7 to start) are saved here, by the
-- service role.
--
-- Each side decides on its own:
--   teacher_status  open | accepted | dismissed. A teacher's dismissal hides
--                   the diagram for her and for the student.
--   student_status  open | accepted | dismissed. A student's dismissal hides
--                   it only for the student.
-- Accepted by either, it stays on the card (unless a teacher dismisses it).
-- Rows are never deleted by the app, so the accept and dismiss counts can
-- tune the bar later.

create table public.diagram_suggestion (
  id                 uuid primary key default gen_random_uuid(),
  feedback_id        uuid not null references public.feedback (id) on delete cascade,
  diagram_id         text not null check (diagram_id ~ '^[a-z0-9-]{1,60}$'),
  confidence         real not null check (confidence >= 0 and confidence <= 1),
  source             text not null check (source in ('cues', 'sketch')),
  teacher_status     text not null default 'open' check (teacher_status in ('open', 'accepted', 'dismissed')),
  student_status     text not null default 'open' check (student_status in ('open', 'accepted', 'dismissed')),
  teacher_decided_by uuid references auth.users (id) on delete set null,
  teacher_decided_at timestamptz,
  student_decided_at timestamptz,
  created_at         timestamptz not null default now(),
  unique (feedback_id, diagram_id)
);

create index diagram_suggestion_feedback_idx on public.diagram_suggestion (feedback_id);

alter table public.diagram_suggestion enable row level security;

-- The student and their teachers read them; the dashboard and the phone
-- hide the dismissed ones themselves.
create policy diagram_suggestion_select on public.diagram_suggestion for select to authenticated using (
  private.feedback_student(feedback_id) = auth.uid()
  or private.is_teacher_of(private.feedback_student(feedback_id))
);
create policy diagram_suggestion_update on public.diagram_suggestion for update to authenticated
  using (
    private.feedback_student(feedback_id) = auth.uid()
    or private.is_teacher_of(private.feedback_student(feedback_id))
  );
create policy diagram_suggestion_taught_only on public.diagram_suggestion as restrictive for all to authenticated
  using (private.feedback_open(feedback_id));

-- Only the decisions can change, and only through the trigger below.
revoke insert, update, delete on public.diagram_suggestion from authenticated, anon;
grant update (teacher_status, student_status) on public.diagram_suggestion to authenticated;

-- The student changes only their own decision; a teacher only the teacher's,
-- stamped with who made it.
create function public.guard_diagram_suggestion() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then
    return new;                                   -- the service role
  end if;
  if private.feedback_student(old.feedback_id) = auth.uid() then
    if new.teacher_status is distinct from old.teacher_status then
      raise exception 'only a teacher can change the teacher''s decision';
    end if;
    if new.student_status is distinct from old.student_status then
      new.student_decided_at := now();
    end if;
  else
    if new.student_status is distinct from old.student_status then
      raise exception 'only the student can change the student''s decision';
    end if;
    if new.teacher_status is distinct from old.teacher_status then
      new.teacher_decided_by := auth.uid();
      new.teacher_decided_at := now();
    end if;
  end if;
  return new;
end $$;
create trigger diagram_suggestion_guard before update on public.diagram_suggestion
  for each row execute function public.guard_diagram_suggestion();

-- The dashboard hears about new suggestions and decisions as they happen.
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table public.diagram_suggestion;
  end if;
end $$;
