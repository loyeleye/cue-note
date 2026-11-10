-- Cue Note: M3 cards
-- The phone uploads a lesson (lesson row, take rows, Opus clips), then calls
-- the extract-cues edge function, which turns each feedback block's words into
-- a card: it writes the feedback row and the AI's card_version with the
-- service role. Nothing here changes an existing column; it only adds.

-- ---------------------------------------------------------------------------
-- feedback: what the phone heard, kept beside the AI's reading of it
-- ---------------------------------------------------------------------------
-- transcript      everything the teacher said in the block, as whisper wrote it
-- link_confidence how sure the phone was that the block belongs to its take
-- local_id        the phone's own id ("<lesson>-fb-001"); feedback.id is
--                 derived from it, so uploading twice updates the same row
alter table public.feedback
  add column transcript      text not null default '',
  add column link_confidence real,
  add column local_id        text;
create unique index feedback_local_id_idx on public.feedback (lesson_id, local_id) where local_id is not null;

-- Teachers still may not change what the phone measured.
create or replace function public.guard_teacher_feedback() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then return new; end if;   -- service role / edge function
  if new.id is distinct from old.id
     or new.lesson_id is distinct from old.lesson_id
     or new.span_start_ms is distinct from old.span_start_ms
     or new.span_end_ms is distinct from old.span_end_ms
     or new.clip_path is distinct from old.clip_path
     or new.transcript is distinct from old.transcript
     or new.link_confidence is distinct from old.link_confidence
     or new.local_id is distinct from old.local_id then
    raise exception 'teachers may change only cue text, links, flags and bars';
  end if;
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------------
-- cue_extraction: one row per extract-cues run, to measure real cost per lesson
-- (the design doc assumes $0.03) and to show why a run failed.
-- ---------------------------------------------------------------------------
create table public.cue_extraction (
  id             uuid primary key default gen_random_uuid(),
  lesson_id      uuid not null references public.lesson (id) on delete cascade,
  model          text not null,
  input_tokens   int,
  output_tokens  int,
  cost_usd       numeric(10, 5),
  cards          int not null default 0,   -- cards written or refreshed
  kept_teacher   int not null default 0,   -- cards left alone because a teacher had edited them
  trial          boolean not null default false,  -- a model comparison; it wrote no cards
  error          text,
  created_at     timestamptz not null default now()
);
create index cue_extraction_lesson_idx on public.cue_extraction (lesson_id, created_at desc);

alter table public.cue_extraction enable row level security;
-- The student and their teachers can read it; only the edge function writes.
create policy cue_extraction_select on public.cue_extraction for select to authenticated using (
  private.lesson_student(lesson_id) = auth.uid()
  or private.is_teacher_of(private.lesson_student(lesson_id))
);
create policy cue_extraction_taught_only on public.cue_extraction as restrictive for all to authenticated
  using (private.lesson_open(lesson_id));
revoke all on public.cue_extraction from anon;
revoke insert, update, delete on public.cue_extraction from authenticated;
grant select on public.cue_extraction to authenticated;

-- ---------------------------------------------------------------------------
-- The teacher's past corrections, as few-shot examples for the next lesson.
-- For each card a teacher changed: the AI's first version and her latest one.
-- Only the edge function calls this (service role).
-- ---------------------------------------------------------------------------
create function private.cue_corrections(student uuid, max_rows int default 8)
returns table (transcript text, ai jsonb, teacher jsonb, corrected_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select f.transcript, ai.payload, t.payload, t.created_at
    from public.feedback f
    join public.lesson l on l.id = f.lesson_id
    join lateral (select cv.payload from public.card_version cv
                   where cv.feedback_id = f.id and cv.author = 'ai'
                   order by cv.created_at, cv.id limit 1) ai on true
    join lateral (select cv.payload, cv.created_at from public.card_version cv
                   where cv.feedback_id = f.id and cv.author = 'teacher'
                   order by cv.created_at desc, cv.id desc limit 1) t on true
   where l.student_id = student
     and f.transcript <> ''
     and (ai.payload ->> 'cue', ai.payload ->> 'her_words', ai.payload ->> 'category', ai.payload ->> 'practice_flag')
         is distinct from
         (t.payload ->> 'cue', t.payload ->> 'her_words', t.payload ->> 'category', t.payload ->> 'practice_flag')
   order by t.created_at desc
   limit max_rows
$$;
revoke execute on function private.cue_corrections(uuid, int) from public, anon, authenticated;

-- Wrapper the edge function can reach through the API (private isn't exposed).
create function public.cue_corrections_for(student uuid, max_rows int default 8)
returns table (transcript text, ai jsonb, teacher jsonb, corrected_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select * from private.cue_corrections(student, max_rows)
$$;
revoke execute on function public.cue_corrections_for(uuid, int) from public, anon, authenticated;
do $$ begin
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    grant execute on function public.cue_corrections_for(uuid, int) to service_role;
  end if;
end $$;
