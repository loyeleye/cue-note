-- Exercises: the student uploads them with the lesson, a shared teacher reads
-- them, an unshared teacher sees nothing, and takes and cards point at them.
\set ON_ERROR_STOP on
\set QUIET on

insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"role":"teacher","display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"role":"teacher"}');
insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at)
values ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());

-- ---------------------------------------------------------------- Sam uploads
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
insert into public.lesson (id, student_id, recorded_at, consent_at, pipeline_state)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), 'uploading');
insert into public.exercise (id, lesson_id, start_ms, end_ms, kind, reason) values
  ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 60000, 'warmup', 'first singing'),
  ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 60000, 300000, 'song', 'she said "now the song"');
insert into public.take (id, lesson_id, start_ms, end_ms, exercise_id) values
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 9000, '30000000-0000-0000-0000-000000000001'),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 20000, 30000, '30000000-0000-0000-0000-000000000001');
-- uploading again updates the same row
insert into public.exercise (id, lesson_id, start_ms, end_ms, kind)
values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 500, 60000, 'warmup')
on conflict (id) do update set start_ms = excluded.start_ms;
do $$ begin
  assert (select start_ms from public.exercise where id = '30000000-0000-0000-0000-000000000001') = 500, 'FAIL: exercise upsert';
  assert (select count(*) from public.take where exercise_id = '30000000-0000-0000-0000-000000000001') = 2, 'FAIL: takes in the warmup';
  begin
    insert into public.exercise (id, lesson_id, start_ms, end_ms, kind)
    values ('30000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000001', 0, 10, 'scales');
    raise exception 'FAIL: unknown kind accepted';
  exception when check_violation then null;
  end;
end $$;
reset role;

-- extract-cues writes the card with its exercise
set role service_role;
insert into public.feedback (id, lesson_id, take_id, exercise_id, span_start_ms, span_end_ms, cue, her_words)
values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 31000, 40000, 'Wide tube', 'imagine a big tube');
reset role;

-- ---------------------------------------------------------------- Tia reads, may not write
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.exercise) = 2, 'FAIL: Tia cannot see Sam''s exercises';
  assert (select exercise_id from public.feedback where id = '40000000-0000-0000-0000-000000000001')
    = '30000000-0000-0000-0000-000000000001', 'FAIL: card lost its exercise';
  update public.exercise set kind = 'song' where id = '30000000-0000-0000-0000-000000000001';
  if found then raise exception 'FAIL: Tia edited an exercise'; end if;
end $$;
reset role;

-- ---------------------------------------------------------------- Uma sees nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.exercise) = 0, 'FAIL: unshared teacher reads an exercise';
end $$;
reset role;

-- ---------------------------------------------------------------- deleting the lesson takes its exercises
delete from public.lesson where id = '10000000-0000-0000-0000-000000000001';
do $$ begin
  assert (select count(*) from public.exercise) = 0, 'FAIL: exercises outlived their lesson';
end $$;
\echo 'All exercise tests passed.'
