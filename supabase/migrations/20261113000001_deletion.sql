-- Real deletion. Teachers can delete a card (and its clip) or remove just its
-- audio, and a student who deletes a lesson on the phone also deletes it here
-- (lesson rows cascade; the phone removes its files).
--
-- A deleted card leaves a tombstone holding only its id, so making the notes
-- again doesn't bring it back. A card whose audio was removed keeps
-- audio_removed_at, so the phone doesn't upload that clip again and
-- extract-cues doesn't relink it.

-- ---------------------------------------------------------------- Remove the audio, keep the note
alter table public.feedback add column audio_removed_at timestamptz;

-- Teachers still may not change what the phone measured, except to remove
-- the audio: clip_path may go to null once audio_removed_at is set, and
-- audio_removed_at, once set, stays set.
create or replace function public.guard_teacher_feedback() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then return new; end if;   -- service role / edge function
  if new.id is distinct from old.id
     or new.lesson_id is distinct from old.lesson_id
     or new.span_start_ms is distinct from old.span_start_ms
     or new.span_end_ms is distinct from old.span_end_ms
     or new.transcript is distinct from old.transcript
     or new.link_confidence is distinct from old.link_confidence
     or new.local_id is distinct from old.local_id
     or (old.audio_removed_at is not null and new.audio_removed_at is distinct from old.audio_removed_at)
     or (new.clip_path is distinct from old.clip_path
         and not (new.clip_path is null and new.audio_removed_at is not null)) then
    raise exception 'teachers may change only cue text, links, flags and bars, or remove the audio';
  end if;
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------- Delete a card
create table public.feedback_removed (
  feedback_id uuid primary key,
  lesson_id   uuid not null references public.lesson (id) on delete cascade,
  removed_by  uuid references public.profile (id) on delete set null,
  removed_at  timestamptz not null default now()
);
create index feedback_removed_lesson_idx on public.feedback_removed (lesson_id);
alter table public.feedback_removed enable row level security;

create policy feedback_removed_select on public.feedback_removed for select to authenticated
  using (private.lesson_student(lesson_id) = (select auth.uid())
         or private.is_teacher_of(private.lesson_student(lesson_id)));
create policy feedback_removed_teacher_insert on public.feedback_removed for insert to authenticated
  with check (removed_by = (select auth.uid())
              and private.is_teacher_of(private.lesson_student(lesson_id)));
create policy feedback_removed_taught_only on public.feedback_removed as restrictive for all to authenticated
  using (private.lesson_open(lesson_id))
  with check (private.lesson_open(lesson_id));
revoke update, delete on public.feedback_removed from authenticated, anon;

create policy feedback_teacher_delete on public.feedback for delete to authenticated
  using (private.is_teacher_of(private.lesson_student(lesson_id)));

-- Deletes a card and leaves its tombstone, in one step. Runs as the caller,
-- so the policies above decide who may. Remove the card's clip first.
create function public.remove_card(card uuid) returns void
language plpgsql security invoker set search_path = '' as $$
declare
  fb record;
begin
  select id, lesson_id into fb from public.feedback where id = card;
  if not found then raise exception 'no such card'; end if;
  insert into public.feedback_removed (feedback_id, lesson_id, removed_by)
  values (fb.id, fb.lesson_id, auth.uid())
  on conflict (feedback_id) do nothing;
  delete from public.feedback where id = fb.id;
  if not found then raise exception 'you can''t delete this card'; end if;
end $$;
revoke execute on function public.remove_card(uuid) from public, anon;
grant execute on function public.remove_card(uuid) to authenticated;

-- ---------------------------------------------------------------- Files
-- A teacher may delete a card's clip, and nothing else of the student's: the
-- object must be the clip_path of a card in one of their student's lessons.
-- The dashboard removes the file first, then the card or its link, so the
-- card still names the file when this is checked. The restrictive
-- storage_taught_only policy still applies on top.
create function private.is_card_clip_of_my_student(object_name text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.feedback f
     where f.clip_path = object_name
       and private.is_teacher_of(private.lesson_student(f.lesson_id)))
$$;
revoke execute on function private.is_card_clip_of_my_student(text) from public, anon;
grant execute on function private.is_card_clip_of_my_student(text) to authenticated;

create policy storage_teacher_card_clip_delete on storage.objects for delete to authenticated
  using (bucket_id = 'clips' and private.is_card_clip_of_my_student(name));
