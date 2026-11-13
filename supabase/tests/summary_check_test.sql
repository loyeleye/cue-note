-- "Did you say this?": teachers check the card writer's summaries.
\set ON_ERROR_STOP on
\set QUIET on

alter table public.teacher_student_share alter column restricted_to_taught set default false;
alter table public.lesson_view_consent alter column status set default 'granted';

-- Sam (student), Tia (Sam's teacher), Uma (not Sam's teacher)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}');
insert into public.teacher_student_share (id, student_id, invite_email) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com');
insert into public.lesson (id, student_id, recorded_at, consent_at)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue, her_words, summary, weight) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 5000, 'Land from above', 'like from the sky', 'Fall onto the high notes, like landing from the sky.', 0.9),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 6000, 9000, 'Find the buzz', 'vibrate right here', 'Start lower, rather than aiming for head voice.', 0.6),
  ('40000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 9000, 12000, 'Low breath', '', 'Breathe low.', 0.5);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
select public.accept_share('30000000-0000-0000-0000-000000000001');

-- Yes to the first, no to the second.
select public.check_summary('40000000-0000-0000-0000-000000000001', true);
select public.check_summary('40000000-0000-0000-0000-000000000002', false);
do $$ begin
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'said', 'FAIL: yes not kept';
  assert (select summary_checked_by from public.feedback where id = '40000000-0000-0000-0000-000000000001') = '00000000-0000-0000-0000-00000000000b', 'FAIL: who said yes';
  assert (select summary from public.feedback where id = '40000000-0000-0000-0000-000000000002') = '', 'FAIL: a no must take the summary off the card';
  assert (select summary_rejected from public.feedback where id = '40000000-0000-0000-0000-000000000002') like 'Start lower%', 'FAIL: a no keeps what was rejected';
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'not_said', 'FAIL: no not kept';
  assert (select said from public.summary_check_tally()) = 1, 'FAIL: tally said';
  assert (select not_said from public.summary_check_tally()) = 1, 'FAIL: tally not said';
  assert (select unchecked from public.summary_check_tally()) = 1, 'FAIL: tally unchecked';
end $$;

-- Rewriting a summary through save_card is a yes, and it is in the version.
select public.save_card('40000000-0000-0000-0000-000000000002', '{"summary":"Find the vibration wherever you feel it."}'::jsonb);
do $$ begin
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'said', 'FAIL: her rewrite is a yes';
  assert (select payload ->> 'summary' from public.card_version where feedback_id = '40000000-0000-0000-0000-000000000002'
           order by created_at desc limit 1) = 'Find the vibration wherever you feel it.', 'FAIL: the version keeps the summary';
end $$;
reset role;

-- The card writer rewrites a summary: the old answer clears.
update public.feedback set summary = 'Land on the high notes from above.' where id = '40000000-0000-0000-0000-000000000001';
do $$ begin
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000001') is null, 'FAIL: a new summary needs a new answer';
end $$;

-- Not her lesson: Uma can't answer, and the student can't either.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  begin
    perform public.check_summary('40000000-0000-0000-0000-000000000003', true);
    raise exception 'FAIL: another teacher checked a summary';
  exception when others then
    if sqlerrm like 'FAIL:%' then raise; end if;
  end;
end $$;
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  begin
    perform public.check_summary('40000000-0000-0000-0000-000000000003', false);
    raise exception 'FAIL: the student checked a summary';
  exception when others then
    if sqlerrm like 'FAIL:%' then raise; end if;
  end;
end $$;
reset role;
do $$ begin
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000003') is null, 'FAIL: card 3 changed';
end $$;

\echo 'All summary-check tests passed.'
