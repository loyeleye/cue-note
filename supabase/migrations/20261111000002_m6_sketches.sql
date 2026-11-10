-- M6 sketches: what the phone learns about each lesson photo, and who put it
-- on its card.
--
-- photo_mark gains:
--   cleaned_path      the straightened, whitened copy (photos/<student>/<lesson>/<id>.clean.jpg)
--   corners           the page's corners as 8 fractions of the photo [x,y TL, TR, BR, BL], or null
--   page_confidence   how sure the phone was about those corners, 0..1 (1 = set by hand)
--   auto_feedback_id  the card the phone guessed by time, kept after any move,
--                     so we can count how often the guess was right (the M6 gate)
--   link_source       who put it on its current card: auto, student or teacher
-- confidence (already there) is caption-sketch's "the sketch matches her words", 0..1.

alter table public.photo_mark
  add column cleaned_path     text,
  add column corners          jsonb check (corners is null or jsonb_array_length(corners) = 8),
  add column page_confidence  real,
  add column auto_feedback_id uuid references public.feedback (id) on delete set null,
  add column link_source      text not null default 'auto'
    check (link_source in ('auto', 'student', 'teacher'));

create index photo_mark_feedback_idx on public.photo_mark (feedback_id);

-- A teacher may still only move a photo to another card. When she does, the
-- photo is marked as hers, whatever link_source the request sent.
create or replace function public.guard_teacher_photo() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null or private.lesson_student(old.lesson_id) = auth.uid() then
    return new;                                   -- the student owns everything else
  end if;
  if (to_jsonb(new) - 'feedback_id' - 'link_source') is distinct from (to_jsonb(old) - 'feedback_id' - 'link_source') then
    raise exception 'teachers may only relink a photo';
  end if;
  if new.feedback_id is distinct from old.feedback_id then
    new.link_source := 'teacher';
  else
    new.link_source := old.link_source;
  end if;
  return new;
end $$;

-- For everyone: a photo's card is from its own lesson (a card from another
-- lesson would put the sketch where nobody looks for it).
create function public.photo_card_same_lesson() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.feedback_id is not null and not exists (
    select 1 from public.feedback f where f.id = new.feedback_id and f.lesson_id = new.lesson_id
  ) then
    raise exception 'a photo can only go on a card from its own lesson';
  end if;
  return new;
end $$;
create trigger photo_mark_same_lesson before insert or update of feedback_id, lesson_id on public.photo_mark
  for each row execute function public.photo_card_same_lesson();

-- The dashboard hears about captions and moves as they happen.
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table public.photo_mark;
  end if;
end $$;
