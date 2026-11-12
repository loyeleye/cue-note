-- M5: the practice log, shared practice takes, questions, and the agenda.
\set ON_ERROR_STOP on
\set QUIET on

-- These tests cover a teacher the student lets see all their lessons; since
-- 20261115000002 a new share starts limited to the lessons the teacher taught.
alter table public.teacher_student_share alter column restricted_to_taught set default false;

-- Sam (student), Tia and Ola (Sam's teachers), Uma (not Sam's teacher), Bo (another student)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000e', 'ola@example.com', '{"display_name":"Ola"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}'),
  ('00000000-0000-0000-0000-00000000000d', 'bo@example.com',  '{"display_name":"Bo"}');
insert into public.teacher_student_share (id, student_id, invite_email, teacher_id, status, accepted_at) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com',
   '00000000-0000-0000-0000-00000000000b', 'active', now()),
  ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', 'ola@example.com',
   '00000000-0000-0000-0000-00000000000e', 'active', now());
-- Lesson 1 taught by Tia, lesson 2 by Ola; Bo has lesson 9.
insert into public.lesson (id, student_id, recorded_at, consent_at, taught_by) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000b'),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000e'),
  ('10000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-00000000000d', now(), now(), null);
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'Lift here'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 0, 1000, 'Breathe low'),
  ('40000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000009', 0, 1000, 'Bo''s cue');

-- ---------------------------------------------------------------- Sam practices and shares
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  insert into public.practice_session (id, student_id, started_at, ended_at, minutes, items) values
    ('50000000-0000-0000-0000-000000000001', auth.uid(), now() - interval '20 minutes', now(), 18,
     '[{"feedback_id":"40000000-0000-0000-0000-000000000001","takes":4}]');
  -- Takes on Tia's card (no lesson given: the card fills it in) and on Ola's.
  insert into public.practice_take (id, student_id, feedback_id, session_id, recorded_at, duration_ms, clip_path, pitch, feeling_note) values
    ('60000000-0000-0000-0000-000000000001', auth.uid(), '40000000-0000-0000-0000-000000000001',
     '50000000-0000-0000-0000-000000000001', now(), 8000,
     '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/practice-60000000-0000-0000-0000-000000000001.m4a',
     '[{"sung":60.1,"target":60.0,"verdict":"IN_TUNE"}]', 'Felt open'),
    ('60000000-0000-0000-0000-000000000002', auth.uid(), '40000000-0000-0000-0000-000000000002',
     null, now(), 6000,
     '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000002/practice-60000000-0000-0000-0000-000000000002.m4a', null, null);
  assert (select lesson_id from public.practice_take where id = '60000000-0000-0000-0000-000000000001')
    = '10000000-0000-0000-0000-000000000001', 'FAIL: the take didn''t pick up its card''s lesson';
  begin
    insert into public.practice_take (id, student_id, lesson_id, recorded_at, duration_ms) values
      ('60000000-0000-0000-0000-000000000009', auth.uid(), '10000000-0000-0000-0000-000000000009', now(), 5000);
    raise exception 'FAIL: shared a take on someone else''s lesson';
  exception when raise_exception then
    if sqlerrm not like '%isn''t this student''s%' then raise; end if;
  end;
  insert into storage.objects (bucket_id, name) values
    ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/practice-60000000-0000-0000-0000-000000000001.m4a'),
    ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000002/practice-60000000-0000-0000-0000-000000000002.m4a');
  -- A question on Tia's card, one on bar 12 of Ola's lesson, one general.
  insert into public.student_question (id, student_id, feedback_id, lesson_id, bar, body) values
    ('70000000-0000-0000-0000-000000000001', auth.uid(), '40000000-0000-0000-0000-000000000001', null, null, 'Is this lift too much?'),
    ('70000000-0000-0000-0000-000000000002', auth.uid(), null, '10000000-0000-0000-0000-000000000002', 12, 'Bar 12 still cracks'),
    ('70000000-0000-0000-0000-000000000003', auth.uid(), null, null, null, 'How long should I warm up?');

  -- A third take fills the agenda; a fourth is refused.
  insert into public.practice_take (id, student_id, feedback_id, recorded_at, duration_ms) values
    ('60000000-0000-0000-0000-000000000003', auth.uid(), '40000000-0000-0000-0000-000000000001', now(), 5000);
  begin
    insert into public.practice_take (id, student_id, lesson_id, recorded_at, duration_ms) values
      ('60000000-0000-0000-0000-000000000004', auth.uid(), '10000000-0000-0000-0000-000000000002', now(), 5000);
    raise exception 'FAIL: shared a fourth take';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  -- Sam can't mark his own take heard, answer his own question, or point at Bo's card.
  begin
    update public.practice_take set heard_at = now() where id = '60000000-0000-0000-0000-000000000003';
    raise exception 'FAIL: the student marked a take heard';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    update public.student_question set status = 'answered' where id = '70000000-0000-0000-0000-000000000003';
    raise exception 'FAIL: the student answered their own question';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    insert into public.student_question (id, student_id, feedback_id, body) values
      ('70000000-0000-0000-0000-000000000009', auth.uid(), '40000000-0000-0000-0000-000000000009', 'sneaky');
    raise exception 'FAIL: pinned a question to someone else''s card';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    insert into public.student_question (id, student_id, body, status) values
      ('70000000-0000-0000-0000-000000000008', auth.uid(), 'already answered?', 'answered');
    raise exception 'FAIL: a question started answered';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    insert into public.practice_session (id, student_id, started_at, ended_at, minutes) values
      ('50000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-00000000000d', now(), now(), 5);
    raise exception 'FAIL: logged practice for someone else';
  exception when insufficient_privilege then null;
  end;
  -- He can reword and drop his own question, and unshare a take.
  update public.student_question set body = 'Is this lift too much, or not enough?' where id = '70000000-0000-0000-0000-000000000001';
  assert found, 'FAIL: the student could not reword a question';
  delete from public.practice_take where id = '60000000-0000-0000-0000-000000000003';
  assert found, 'FAIL: the student could not unshare a take';
end $$;
reset role;

-- ---------------------------------------------------------------- Tia reads the agenda
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.practice_session) = 1, 'FAIL: the teacher can''t see the log';
  assert (select count(*) from public.practice_take) = 2, 'FAIL: the teacher can''t see shared takes';
  assert (select count(*) from public.student_question where status = 'open') = 3, 'FAIL: the teacher can''t see questions';
  assert (select count(*) from storage.objects where name like '%/practice-%') = 2, 'FAIL: the teacher can''t hear shared takes';
  -- She sets the lesson date the agenda is for.
  update public.teacher_student_share set next_lesson_at = now() + interval '2 days' where id = '30000000-0000-0000-0000-000000000001';
  assert found, 'FAIL: the teacher could not set the lesson date';

  -- Listening marks the take heard, under her name.
  update public.practice_take set heard_at = now() where id = '60000000-0000-0000-0000-000000000001';
  assert (select heard_by from public.practice_take where id = '60000000-0000-0000-0000-000000000001') = auth.uid(),
    'FAIL: heard_by not set';
  -- She answers one question in words and one in the lesson.
  update public.student_question set status = 'answered', answer = 'A little less; think of a yawn.'
   where id = '70000000-0000-0000-0000-000000000001';
  update public.student_question set status = 'answered' where id = '70000000-0000-0000-0000-000000000003';
  assert (select answered_by from public.student_question where id = '70000000-0000-0000-0000-000000000001') = auth.uid(),
    'FAIL: answered_by not set';
  assert (select answered_at from public.student_question where id = '70000000-0000-0000-0000-000000000003') is not null,
    'FAIL: answered_at not set';

  -- She can't change what the student wrote or sang, or drop the question.
  begin
    update public.practice_take set feeling_note = 'x' where id = '60000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: the teacher changed the student''s note';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    update public.student_question set body = 'x' where id = '70000000-0000-0000-0000-000000000002';
    raise exception 'FAIL: the teacher reworded the student''s question';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    update public.student_question set status = 'dropped' where id = '70000000-0000-0000-0000-000000000002';
    raise exception 'FAIL: the teacher dropped the student''s question';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  delete from public.practice_take where id = '60000000-0000-0000-0000-000000000002';
  assert not found, 'FAIL: the teacher deleted a shared take';
  delete from public.practice_session;
  assert not found, 'FAIL: the teacher deleted the log';
  begin
    insert into public.student_question (id, student_id, body) values
      ('70000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-00000000000a', 'written by Tia');
    raise exception 'FAIL: the teacher asked a question as the student';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

-- The agenda: one take not heard yet, one open question.
do $$ begin
  assert (select count(*) from public.practice_take where heard_at is null) = 1, 'FAIL: agenda takes';
  assert (select count(*) from public.student_question where status = 'open') = 1, 'FAIL: agenda questions';
  assert (select feeling_note from public.practice_take where id = '60000000-0000-0000-0000-000000000001') = 'Felt open',
    'FAIL: the note changed';
end $$;

-- A heard take frees its place: Sam can share two more.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  insert into public.practice_take (id, student_id, lesson_id, recorded_at, duration_ms) values
    ('60000000-0000-0000-0000-000000000005', auth.uid(), '10000000-0000-0000-0000-000000000002', now(), 5000),
    ('60000000-0000-0000-0000-000000000006', auth.uid(), '10000000-0000-0000-0000-000000000002', now(), 5000);
  -- He can reopen an answered question.
  update public.student_question set status = 'open' where id = '70000000-0000-0000-0000-000000000003';
  assert found, 'FAIL: the student could not reopen a question';
  -- Sam limits Tia to her own lessons.
  update public.teacher_student_share set restricted_to_taught = true where id = '30000000-0000-0000-0000-000000000001';
end $$;
reset role;

-- ---------------------------------------------------------------- Tia, limited
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  -- The log has no lesson content, so she still sees it.
  assert (select count(*) from public.practice_session) = 1, 'FAIL: a limited teacher lost the log';
  -- Only the take and question on her lesson's card.
  assert (select count(*) from public.practice_take) = 1, 'FAIL: a limited teacher sees takes from other lessons';
  assert (select count(*) from public.student_question) = 1, 'FAIL: a limited teacher sees questions from other lessons';
  assert (select count(*) from storage.objects where name like '%/practice-%') = 1, 'FAIL: a limited teacher hears the wrong takes';
  update public.student_question set status = 'open' where id = '70000000-0000-0000-0000-000000000002';
  assert not found, 'FAIL: a limited teacher changed a question she can''t see';
end $$;
reset role;

-- ---------------------------------------------------------------- Ola deletes her card
-- The take and question on it lose the card but stay on the agenda.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.practice_take) = 4, 'FAIL: Ola can''t see every take';
  perform public.remove_card('40000000-0000-0000-0000-000000000002');
end $$;
reset role;
do $$ begin
  assert (select feedback_id from public.practice_take where id = '60000000-0000-0000-0000-000000000002') is null,
    'FAIL: the take still names the deleted card';
  assert (select lesson_id from public.practice_take where id = '60000000-0000-0000-0000-000000000002')
    = '10000000-0000-0000-0000-000000000002', 'FAIL: the take lost its lesson';
end $$;

-- ---------------------------------------------------------------- Outsiders
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.practice_session) = 0, 'FAIL: an outsider sees the log';
  assert (select count(*) from public.practice_take) = 0, 'FAIL: an outsider sees takes';
  assert (select count(*) from public.student_question) = 0, 'FAIL: an outsider sees questions';
  assert (select count(*) from storage.objects) = 0, 'FAIL: an outsider sees files';
  update public.practice_take set heard_at = now();
  assert not found, 'FAIL: an outsider marked a take heard';
end $$;
reset role;

-- A revoked teacher loses it all.
update public.teacher_student_share set status = 'revoked' where id = '30000000-0000-0000-0000-000000000002';
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.practice_take) = 0, 'FAIL: a revoked teacher sees takes';
  assert (select count(*) from public.student_question) = 0, 'FAIL: a revoked teacher sees questions';
end $$;
reset role;

-- Deleting a lesson takes its practice takes and questions with it.
delete from public.lesson where id = '10000000-0000-0000-0000-000000000001';
do $$ begin
  assert not exists (select 1 from public.practice_take where lesson_id = '10000000-0000-0000-0000-000000000001'),
    'FAIL: takes outlived the lesson';
  assert not exists (select 1 from public.student_question where id = '70000000-0000-0000-0000-000000000001'),
    'FAIL: questions outlived the lesson';
  assert not has_table_privilege('anon', 'public.practice_take', 'select'), 'FAIL: anon can read takes';
end $$;

\echo 'All practice tests passed.'
