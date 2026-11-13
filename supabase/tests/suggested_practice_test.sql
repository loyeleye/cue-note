-- Suggested practice: the teacher's settings for the daily plan, and the phone's log of each sitting.
\set ON_ERROR_STOP on
\set QUIET on

-- Sam (student), Tia (Sam's teacher), Uma (not Sam's teacher), Bo (another student)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}'),
  ('00000000-0000-0000-0000-00000000000d', 'bo@example.com',  '{"display_name":"Bo"}');
insert into public.teacher_student_share (id, student_id, invite_email, teacher_id, status, accepted_at) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com',
   '00000000-0000-0000-0000-00000000000b', 'active', now());
insert into public.lesson (id, student_id, recorded_at, consent_at, taught_by) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000b'),
  ('10000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-00000000000d', now(), now(), null);
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'Think down and over'),
  ('40000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000009', 0, 1000, 'Bo''s cue');

-- ---------------------------------------------------------------- Tia steers Sam's plan
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  insert into public.practice_plan_setting (student_id, teacher_id, pinned_feedback_id, minutes, days_per_week, max_minutes, cool_down)
    values ('00000000-0000-0000-0000-00000000000a', auth.uid(), '40000000-0000-0000-0000-000000000001', 20, 4, 20, 'Slides down on a hum');
  update public.practice_plan_setting set rest_until = current_date + 3 where student_id = '00000000-0000-0000-0000-00000000000a';
  assert found, 'FAIL: the teacher could not set rest';
  begin
    update public.practice_plan_setting set pinned_feedback_id = '40000000-0000-0000-0000-000000000009';
    raise exception 'FAIL: pinned another student''s card';
  exception when raise_exception then
    if sqlerrm not like '%isn''t this student''s%' then raise; end if;
  end;
  begin
    update public.practice_plan_setting set minutes = 45;
    raise exception 'FAIL: a length the phone doesn''t offer';
  exception when check_violation then null;
  end;
  -- A row in another teacher's name is refused.
  begin
    insert into public.practice_plan_setting (student_id, teacher_id, minutes)
      values ('00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000c', 10);
    raise exception 'FAIL: wrote a setting as another teacher';
  exception when insufficient_privilege then null;
  end;
  -- Nothing for a student she doesn't teach.
  begin
    insert into public.practice_plan_setting (student_id, teacher_id, minutes)
      values ('00000000-0000-0000-0000-00000000000d', auth.uid(), 10);
    raise exception 'FAIL: set a plan for someone else''s student';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- Sam's phone reads it and logs a sitting
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.practice_plan_setting) = 1, 'FAIL: the student can''t read the setting';
  assert (select rest_until from public.practice_plan_setting) = current_date + 3, 'FAIL: rest not read';
  begin
    update public.practice_plan_setting set rest_until = null;
    assert not found, 'FAIL: the student changed the teacher''s setting';
  end;
  insert into public.practice_plan_log (id, student_id, day, started_at, ended_at, voice, minutes, steps, done, skipped)
    values ('80000000-0000-0000-0000-000000000001', auth.uid(), current_date, now() - interval '18 minutes', now(),
            'tired', 12, 5, 4, '["40000000-0000-0000-0000-000000000001"]');
  begin
    insert into public.practice_plan_log (id, student_id, day, started_at, ended_at, voice, minutes, steps, done)
      values ('80000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-00000000000d', current_date, now(), now(), 'fine', 10, 4, 4);
    raise exception 'FAIL: logged a sitting for someone else';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.practice_plan_log (id, student_id, day, started_at, ended_at, voice, minutes, steps, done)
      values ('80000000-0000-0000-0000-000000000008', auth.uid(), current_date, now(), now(), 'grumpy', 10, 4, 4);
    raise exception 'FAIL: a voice the phone doesn''t send';
  exception when check_violation then null;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- Tia sees the log; Uma sees nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select voice from public.practice_plan_log) = 'tired', 'FAIL: the teacher can''t see the log';
  update public.practice_plan_log set done = 5;
  assert not found, 'FAIL: the teacher changed the student''s log';
end $$;
reset role;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.practice_plan_log) = 0, 'FAIL: a stranger read the log';
  assert (select count(*) from public.practice_plan_setting) = 0, 'FAIL: a stranger read the setting';
end $$;
reset role;

-- ---------------------------------------------------------------- Deleting the card unpins it
delete from public.feedback where id = '40000000-0000-0000-0000-000000000001';
do $$ begin
  assert (select pinned_feedback_id from public.practice_plan_setting) is null, 'FAIL: a deleted card stayed pinned';
end $$;
\echo 'All suggested-practice tests passed.'
