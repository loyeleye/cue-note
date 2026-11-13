-- "Did you say this?" with Close, and her explanation in words or a voice note.
\set ON_ERROR_STOP on
\set QUIET on

alter table public.teacher_student_share alter column restricted_to_taught set default false;
alter table public.lesson_view_consent alter column status set default 'granted';

insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}');
insert into public.teacher_student_share (id, student_id, invite_email) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com');
insert into public.lesson (id, student_id, recorded_at, consent_at)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue, her_words, summary, weight) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 5000, 'Land from above', 'like from the sky', 'Fall onto the high notes from the sky.', 0.9),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 6000, 9000, 'Find the buzz', 'vibrate right here', 'Aim for head voice.', 0.6),
  ('40000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 9000, 12000, 'Low breath', '', 'Breathe low.', 0.5),
  ('40000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 13000, 15000, 'Chat', '', '', null);

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
select public.accept_share('30000000-0000-0000-0000-000000000001');

-- Close keeps the summary; her note goes with it.
select public.answer_summary('40000000-0000-0000-0000-000000000001', 'close', '  More like a ball rolling over a hill, not falling.  ');
-- Wrong, with a voice note and a long explanation.
select public.answer_summary('40000000-0000-0000-0000-000000000002', 'not_said', repeat('Start low and find the buzz. ', 20),
  '00000000-0000-0000-0000-00000000000a/teacher-notes/n1.webm');
do $$ begin
  assert (select summary from public.feedback where id = '40000000-0000-0000-0000-000000000001') like 'Fall onto%', 'FAIL: close keeps the summary';
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'close', 'FAIL: close kept';
  assert (select summary_note from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'More like a ball rolling over a hill, not falling.', 'FAIL: note trimmed and kept';
  assert (select summary from public.feedback where id = '40000000-0000-0000-0000-000000000002') = '', 'FAIL: wrong takes the summary off';
  assert (select summary_rejected from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'Aim for head voice.', 'FAIL: wrong keeps the old summary';
  assert length((select summary_note from public.feedback where id = '40000000-0000-0000-0000-000000000002')) > 500, 'FAIL: long notes are fine';
  assert (select summary_note_clip from public.feedback where id = '40000000-0000-0000-0000-000000000002') like '%/teacher-notes/n1.webm', 'FAIL: voice note kept';
  assert (select close from public.summary_check_tally()) = 1, 'FAIL: tally close';
  assert (select not_said from public.summary_check_tally()) = 1, 'FAIL: tally not said';
  assert (select unchecked from public.summary_check_tally()) = 1, 'FAIL: tally unchecked';
end $$;

-- Answering again replaces the note; no note clears it.
select public.answer_summary('40000000-0000-0000-0000-000000000001', 'said');
do $$ begin
  assert (select summary_note from public.feedback where id = '40000000-0000-0000-0000-000000000001') is null, 'FAIL: note cleared';
end $$;

-- A voice note must be hers for this student; a verdict must be one of three.
do $$ begin
  begin
    perform public.answer_summary('40000000-0000-0000-0000-000000000003', 'close', null, '00000000-0000-0000-0000-00000000000c/teacher-notes/x.webm');
    assert false, 'FAIL: another student''s file accepted';
  exception when raise_exception then null; end;
  begin
    perform public.answer_summary('40000000-0000-0000-0000-000000000003', 'maybe');
    assert false, 'FAIL: unknown verdict accepted';
  exception when raise_exception then null; end;
  begin
    perform public.answer_summary('40000000-0000-0000-0000-000000000004', 'close');
    assert false, 'FAIL: close on a card with no summary';
  exception when raise_exception then null; end;
end $$;
reset role;

-- Back from wrong to close: the summary returns.
set role authenticated;
select public.answer_summary('40000000-0000-0000-0000-000000000002', 'close');
do $$ begin
  assert (select summary from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'Aim for head voice.', 'FAIL: summary back';
  assert (select summary_rejected from public.feedback where id = '40000000-0000-0000-0000-000000000002') is null, 'FAIL: rejected cleared';
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'close', 'FAIL: close after wrong';
end $$;
select public.answer_summary('40000000-0000-0000-0000-000000000002', 'not_said', 'Start low.', '00000000-0000-0000-0000-00000000000a/teacher-notes/n1.webm');
reset role;

-- Not her lesson, and the student: no answers.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  begin
    perform public.answer_summary('40000000-0000-0000-0000-000000000003', 'said');
    assert false, 'FAIL: Uma answered';
  exception when raise_exception or insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  begin
    perform public.answer_summary('40000000-0000-0000-0000-000000000003', 'said');
    assert false, 'FAIL: the student answered';
  exception when raise_exception or insufficient_privilege then null; end;
  -- but the student reads her note
  assert (select summary_note_clip from public.feedback where id = '40000000-0000-0000-0000-000000000002') is not null, 'FAIL: student reads the voice note path';
end $$;
reset role;
do $$ begin
  assert (select summary_check from public.feedback where id = '40000000-0000-0000-0000-000000000003') is null, 'FAIL: card 3 changed';
end $$;

\echo 'All summary-answer tests passed.'
