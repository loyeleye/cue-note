-- The student deletes part of a lesson from the phone: a whole exercise (or
-- everything from one on, when the recorder was left running after the
-- lesson), or a single try.
--
-- remove_lesson_part takes a stretch of the lesson in ms. With whole = true,
-- everything in it goes: its exercises, takes and cards. A deleted card leaves
-- the same tombstone a teacher's delete does (feedback_removed), so making the
-- notes again doesn't bring it back. With whole = false only the takes go; a
-- card that was about one stays, now about no try (take_id is set null).
--
-- A row is in the stretch when its middle is. The function returns every clip
-- path the deleted rows named (takes' and cards' clips, and teachers' voice
-- notes on the cards), so the phone can delete those files from storage; the
-- student owns their folder, so the storage rules already let it.
--
-- Runs as definer because students may not delete cards directly; it checks
-- the caller is the lesson's student first.

create function public.remove_lesson_part(lesson uuid, from_ms bigint, to_ms bigint, whole boolean)
returns setof text
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
begin
  if me is null or private.lesson_student(lesson) is distinct from me then
    raise exception 'only the lesson''s student can delete part of it';
  end if;
  if to_ms <= from_ms then raise exception 'nothing to delete'; end if;

  if whole then
    return query
      select p from public.feedback f
        cross join lateral (values (f.clip_path), (f.summary_note_clip)) v (p)
       where f.lesson_id = lesson
         and (f.span_start_ms + f.span_end_ms) / 2 >= from_ms
         and (f.span_start_ms + f.span_end_ms) / 2 < to_ms
         and p is not null;
    insert into public.feedback_removed (feedback_id, lesson_id, removed_by)
    select f.id, f.lesson_id, me from public.feedback f
     where f.lesson_id = lesson
       and (f.span_start_ms + f.span_end_ms) / 2 >= from_ms
       and (f.span_start_ms + f.span_end_ms) / 2 < to_ms
    on conflict (feedback_id) do nothing;
    delete from public.feedback f
     where f.lesson_id = lesson
       and (f.span_start_ms + f.span_end_ms) / 2 >= from_ms
       and (f.span_start_ms + f.span_end_ms) / 2 < to_ms;
    delete from public.exercise e
     where e.lesson_id = lesson
       and (e.start_ms + e.end_ms) / 2 >= from_ms
       and (e.start_ms + e.end_ms) / 2 < to_ms;
  end if;

  return query
    select t.clip_path from public.take t
     where t.lesson_id = lesson
       and (t.start_ms + t.end_ms) / 2 >= from_ms
       and (t.start_ms + t.end_ms) / 2 < to_ms
       and t.clip_path is not null;
  delete from public.take t
   where t.lesson_id = lesson
     and (t.start_ms + t.end_ms) / 2 >= from_ms
     and (t.start_ms + t.end_ms) / 2 < to_ms;
end $$;
revoke execute on function public.remove_lesson_part(uuid, bigint, bigint, boolean) from public, anon;
grant execute on function public.remove_lesson_part(uuid, bigint, bigint, boolean) to authenticated;
