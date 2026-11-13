-- Teachers check the card writer's summaries: "Did you say this?"
--
-- 1. save_card takes `summary` like any other card field, and every version
--    keeps it, so a teacher's rewrite of a summary has the same history,
--    clash check and restore as her cue and her words.
-- 2. check_summary(fb, said): a teacher answers on one card. "Yes" keeps the
--    summary, marked checked. "No" takes it off the student's card (the
--    phone falls back to her own words) and keeps it in summary_rejected,
--    so the tally can count it and the card writer's prompt can be tuned.
--    A teacher who rewrites a summary has, by doing so, said yes to it.
-- 3. When the card writer rewrites a summary (a lesson's notes made again),
--    the old answer no longer applies and the check clears.
-- 4. summary_check_tally(): for the dashboard, how the teacher's answers
--    stand across the lessons she can see.

alter table public.feedback
  add column summary_check      text check (summary_check in ('said', 'not_said')),
  add column summary_checked_by uuid references public.profile (id) on delete set null,
  add column summary_checked_at timestamptz,
  add column summary_rejected   text;

-- ---------------------------------------------------------------- 3. A new summary clears the answer
create function private.summary_rewritten() returns trigger
language plpgsql set search_path = '' as $$
begin
  if new.summary is distinct from old.summary
     and new.summary_check is not distinct from old.summary_check then
    -- Whoever changed it (the card writer, or a save without an answer),
    -- the old answer was about the old words.
    new.summary_check := null;
    new.summary_checked_by := null;
    new.summary_checked_at := null;
  end if;
  return new;
end $$;
create trigger feedback_summary_rewritten before update of summary on public.feedback
  for each row execute function private.summary_rewritten();

-- ---------------------------------------------------------------- 1. save_card with summary
create or replace function public.save_card(fb uuid, changes jsonb, expected jsonb default null, restored_from uuid default null)
returns public.card_version
language plpgsql security invoker set search_path = '' as $$
declare
  f public.feedback;
  v public.card_version;
  cur jsonb;
  k text;
  clash jsonb := '[]'::jsonb;
  last_by uuid;
  new_summary boolean;
begin
  -- Lock the card so two saves at once take turns and the check is exact.
  select * into f from public.feedback where id = fb for update;
  if not found then raise exception 'card not found'; end if;

  if save_card.restored_from is not null and not exists (
       select 1 from public.card_version cv where cv.id = save_card.restored_from and cv.feedback_id = fb) then
    raise exception 'that version belongs to another card';
  end if;

  if expected is not null then
    cur := to_jsonb(f);
    for k in select jsonb_object_keys(changes) loop
      if expected ? k
         and (cur -> k) is distinct from coalesce(expected -> k, 'null'::jsonb)
         and (cur -> k) is distinct from coalesce(changes -> k, 'null'::jsonb) then
        clash := clash || to_jsonb(k);
      end if;
    end loop;
    if jsonb_array_length(clash) > 0 then
      select cv.author_id into last_by from public.card_version cv
       where cv.feedback_id = fb order by cv.created_at desc, cv.id desc limit 1;
      raise exception 'edit_conflict' using
        errcode = 'P0409',
        detail = jsonb_build_object('fields', clash, 'by', last_by, 'at', f.updated_at,
                                    'theirs', (select jsonb_object_agg(x, cur -> x) from jsonb_array_elements_text(clash) x))::text,
        hint = 'Someone else changed this card while you were editing. Choose which version to keep, then save again.';
    end if;
  end if;

  if changes ? 'take_id' and (changes ->> 'take_id') is not null and not exists (
       select 1 from public.take t where t.id = (changes ->> 'take_id')::uuid and t.lesson_id = f.lesson_id) then
    raise exception 'a card can only link to a take from the same lesson';
  end if;

  new_summary := changes ? 'summary' and coalesce(changes ->> 'summary', '') is distinct from f.summary;

  update public.feedback set
    cue           = case when changes ? 'cue'           then coalesce(changes ->> 'cue', '') else cue end,
    category      = case when changes ? 'category'      then changes ->> 'category' else category end,
    her_words     = case when changes ? 'her_words'     then coalesce(changes ->> 'her_words', '') else her_words end,
    summary       = case when changes ? 'summary'       then coalesce(changes ->> 'summary', '') else summary end,
    practice_flag = case when changes ? 'practice_flag' then (changes ->> 'practice_flag')::public.practice_flag else practice_flag end,
    take_id       = case when changes ? 'take_id'       then (changes ->> 'take_id')::uuid else take_id end,
    song_id       = case when changes ? 'song_id'       then (changes ->> 'song_id')::uuid else song_id end,
    bar_start     = case when changes ? 'bar_start'     then (changes ->> 'bar_start')::int else bar_start end,
    bar_end       = case when changes ? 'bar_end'       then (changes ->> 'bar_end')::int else bar_end end,
    -- Her own summary is one she stands by.
    summary_check      = case when new_summary and auth.uid() is not null and coalesce(changes ->> 'summary', '') <> '' then 'said' else summary_check end,
    summary_checked_by = case when new_summary and auth.uid() is not null and coalesce(changes ->> 'summary', '') <> '' then auth.uid() else summary_checked_by end,
    summary_checked_at = case when new_summary and auth.uid() is not null and coalesce(changes ->> 'summary', '') <> '' then now() else summary_checked_at end
  where id = fb
  returning * into f;
  if not found then raise exception 'you cannot edit this card'; end if;

  insert into public.card_version (feedback_id, author, author_id, payload, restored_from)
  values (fb, 'teacher', auth.uid(), jsonb_build_object(
    'cue', f.cue, 'category', f.category, 'her_words', f.her_words, 'summary', f.summary,
    'practice_flag', f.practice_flag, 'take_id', f.take_id, 'song_id', f.song_id,
    'bar_start', f.bar_start, 'bar_end', f.bar_end), save_card.restored_from)
  returning * into v;
  return v;
end $$;
revoke execute on function public.save_card(uuid, jsonb, jsonb, uuid) from public, anon;
grant execute on function public.save_card(uuid, jsonb, jsonb, uuid) to authenticated;

-- ---------------------------------------------------------------- 2. "Did you say this?"
create function public.check_summary(fb uuid, said boolean) returns public.feedback
language plpgsql security invoker set search_path = '' as $$
declare
  f public.feedback;
begin
  -- Row security limits this to the teachers of the lesson; the student can read, not answer.
  select * into f from public.feedback where id = fb for update;
  if not found then raise exception 'card not found'; end if;
  if auth.uid() is null or not private.is_teacher_of(private.lesson_student(f.lesson_id)) then
    raise exception 'only a teacher of this lesson can check its summaries';
  end if;
  if said then
    if f.summary = '' then raise exception 'this card has no summary to check'; end if;
    update public.feedback
       set summary_check = 'said', summary_checked_by = auth.uid(), summary_checked_at = now()
     where id = fb returning * into f;
  else
    -- Off the student's card, kept for the tally. The trigger leaves an answer given with the change alone.
    update public.feedback
       set summary_rejected = case when summary <> '' then summary else summary_rejected end,
           summary = '',
           summary_check = 'not_said', summary_checked_by = auth.uid(), summary_checked_at = now()
     where id = fb returning * into f;
  end if;
  if not found then raise exception 'you cannot check this card'; end if;
  return f;
end $$;
revoke execute on function public.check_summary(uuid, boolean) from public, anon;
grant execute on function public.check_summary(uuid, boolean) to authenticated;

-- ---------------------------------------------------------------- 4. The tally
-- Across every card the caller can see: summaries she said yes to, said no
-- to, and those still waiting for an answer. Row security does the scoping.
create function public.summary_check_tally()
returns table (said bigint, not_said bigint, unchecked bigint, lessons bigint)
language sql stable security invoker set search_path = '' as $$
  select count(*) filter (where summary_check = 'said'),
         count(*) filter (where summary_check = 'not_said'),
         count(*) filter (where summary_check is null and summary <> ''),
         count(distinct lesson_id) filter (where summary_check is null and summary <> '')
    from public.feedback;
$$;
revoke execute on function public.summary_check_tally() from public, anon;
grant execute on function public.summary_check_tally() to authenticated;
