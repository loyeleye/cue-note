-- "Did you say this?" gets a third answer, and room for her to explain.
--
-- 1. summary_check may be 'close': near enough, so the summary stays on the
--    student's card, marked as close.
-- 2. summary_note and summary_note_clip: what she meant, in her own words
--    (any length) and/or a voice note (clips/<student>/teacher-notes/...).
--    The student's phone shows both on the point.
-- 3. answer_summary(fb, verdict, note, note_clip): one call for all three
--    answers. 'not_said' takes the summary off the student's card and keeps
--    it in summary_rejected, as check_summary does. Moving back from
--    'not_said' to 'said' or 'close' puts that summary back. Answering again
--    replaces the note and clip; passing null for both clears them.
-- 4. summary_check_tally() also counts 'close'.

alter table public.feedback drop constraint if exists feedback_summary_check_check;
alter table public.feedback
  add constraint feedback_summary_check_check check (summary_check in ('said', 'close', 'not_said')),
  add column summary_note      text,
  add column summary_note_clip text;

create function public.answer_summary(fb uuid, verdict text, note text default null, note_clip text default null)
returns public.feedback
language plpgsql security invoker set search_path = '' as $$
declare
  f public.feedback;
  student uuid;
  words text := nullif(btrim(coalesce(note, '')), '');
begin
  if verdict not in ('said', 'close', 'not_said') then raise exception 'verdict must be said, close or not_said'; end if;
  select * into f from public.feedback where id = fb for update;
  if not found then raise exception 'card not found'; end if;
  student := private.lesson_student(f.lesson_id);
  if auth.uid() is null or not private.is_teacher_of(student) then
    raise exception 'only a teacher of this lesson can check its summaries';
  end if;
  -- Back from "wrong": the summary she took off comes back.
  if verdict in ('said', 'close') and f.summary = '' and f.summary_check = 'not_said' and coalesce(f.summary_rejected, '') <> '' then
    update public.feedback set summary = summary_rejected, summary_rejected = null, summary_check = 'not_said' where id = fb returning * into f;
  end if;
  if verdict in ('said', 'close') and f.summary = '' then raise exception 'this card has no summary to check'; end if;
  if length(words) > 4000 then raise exception 'keep the note under 4000 characters'; end if;
  if note_clip is not null and note_clip not like student::text || '/teacher-notes/%' then
    raise exception 'a voice note must be one of your notes for this student';
  end if;

  update public.feedback set
    summary_rejected = case when verdict = 'not_said' and summary <> '' then summary else summary_rejected end,
    summary          = case when verdict = 'not_said' then '' else summary end,
    summary_check      = verdict,
    summary_checked_by = auth.uid(),
    summary_checked_at = now(),
    summary_note       = words,
    summary_note_clip  = answer_summary.note_clip
  where id = fb
  returning * into f;
  if not found then raise exception 'you cannot check this card'; end if;
  return f;
end $$;
revoke execute on function public.answer_summary(uuid, text, text, text) from public, anon;
grant execute on function public.answer_summary(uuid, text, text, text) to authenticated;

drop function public.summary_check_tally();
create function public.summary_check_tally()
returns table (said bigint, close bigint, not_said bigint, unchecked bigint, lessons bigint)
language sql stable security invoker set search_path = '' as $$
  select count(*) filter (where summary_check = 'said'),
         count(*) filter (where summary_check = 'close'),
         count(*) filter (where summary_check = 'not_said'),
         count(*) filter (where summary_check is null and summary <> ''),
         count(distinct lesson_id) filter (where summary_check is null and summary <> '')
    from public.feedback;
$$;
revoke execute on function public.summary_check_tally() from public, anon;
grant execute on function public.summary_check_tally() to authenticated;
