-- Key moments: by default a teacher hears only the takes on "Practice this"
-- cards; the full lesson opens for 14 days when both say yes; teachers reply
-- to shared takes and questions by voice.
\set ON_ERROR_STOP on
\set QUIET on

insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"role":"teacher","display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"role":"teacher"}');
insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at)
values ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());

-- Sam's lesson, taught by Tia: three takes, one warmup.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
insert into public.lesson (id, student_id, recorded_at, consent_at, taught_by)
values ('10000000-0000-0000-0000-000000000001', auth.uid(), now(), now(), '00000000-0000-0000-0000-00000000000b');
insert into public.exercise (id, lesson_id, start_ms, end_ms, kind)
values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 60000, 'warmup');
insert into public.take (id, lesson_id, start_ms, end_ms) values
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 9000),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 20000, 30000),
  ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 40000, 50000);
insert into storage.objects (bucket_id, name) values
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/20000000-0000-0000-0000-000000000001.m4a'),
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/20000000-0000-0000-0000-000000000002.m4a'),
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/40000000-0000-0000-0000-000000000001.m4a');
insert into public.practice_session (id, student_id, started_at, ended_at, minutes)
values ('50000000-0000-0000-0000-000000000001', auth.uid(), now(), now(), 5);
reset role;

-- The cards: one "Practice this" on take 1, one plain card on take 2.
set role service_role;
insert into public.feedback (id, lesson_id, take_id, span_start_ms, span_end_ms, cue, practice_flag) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 9500, 15000, 'Lift', 'practice_this'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 31000, 35000, 'Breathe', null);
reset role;

create function pg_temp.as_user(id text) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', id)::text, false)
$$;
create function pg_temp.sees_default() returns void language plpgsql as $$
begin
  assert (select count(*) from public.lesson) = 1, 'FAIL: Tia lost the lesson';
  assert (select count(*) from public.feedback) = 2, 'FAIL: Tia should see every card';
  assert (select array_agg(id::text order by id) from public.take) = array['20000000-0000-0000-0000-000000000001'],
    'FAIL: by default Tia hears only the Practice this take';
  assert (select count(*) from public.exercise) = 0, 'FAIL: the timeline should be Sam''s by default';
  assert (select count(*) from storage.objects where name like '%/20000000-0000-0000-0000-000000000001.m4a') = 1, 'FAIL: Practice this clip';
  assert (select count(*) from storage.objects where name like '%/20000000-0000-0000-0000-000000000002.m4a') = 0, 'FAIL: hidden take clip leaked';
  assert (select count(*) from storage.objects where name like '%/40000000-0000-0000-0000-000000000001.m4a') = 1, 'FAIL: Tia''s own words clip';
end $$;
create function pg_temp.sees_all() returns void language plpgsql as $$
begin
  assert (select count(*) from public.take) = 3, 'FAIL: the pass should open every take';
  assert (select count(*) from public.exercise) = 1, 'FAIL: the pass should open the timeline';
  assert (select count(*) from storage.objects where name like '%/20000000-0000-0000-0000-000000000002.m4a') = 1, 'FAIL: the pass should open the clips';
end $$;

-- ---------------------------------------------------------------- Default
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
set role authenticated;
select pg_temp.sees_default();
-- a teacher can't hand herself a pass
do $$ begin
  begin
    insert into public.full_lesson_pass (lesson_id, student_id, teacher_id, asked_by, status, ends_at)
    values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', auth.uid(), 'student', 'active', now() + interval '1 year');
    raise exception 'FAIL: Tia wrote her own pass';
  exception when insufficient_privilege then null; end;
end $$;
reset role;

-- Sam still sees everything of his own.
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
set role authenticated;
select pg_temp.sees_all();

-- ---------------------------------------------------------------- Sam invites, Tia accepts
create temp table ids (k text primary key, v uuid);
grant all on ids to authenticated;
insert into ids values ('p1', public.ask_full_lesson('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000b', 'Recital on the 30th'));
do $$ begin
  -- asking twice gives the same request
  assert public.ask_full_lesson('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000b') = (select v from ids where k = 'p1'),
    'FAIL: a second invite made a second request';
  begin
    perform public.answer_full_lesson((select v from ids where k = 'p1'), true);
    raise exception 'FAIL: Sam answered his own invite';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
  begin
    perform public.ask_full_lesson('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000c');
    raise exception 'FAIL: Sam invited someone who isn''t his teacher';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
end $$;
reset role;

select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
set role authenticated;
select pg_temp.sees_default();   -- pending isn't yes
do $$ begin
  assert (select note from public.full_lesson_pass) = 'Recital on the 30th', 'FAIL: Tia should see the invite';
end $$;
select public.answer_full_lesson((select v from ids where k = 'p1'), true);
do $$ begin
  assert (select status from public.full_lesson_pass) = 'active', 'FAIL: pass not active';
  assert (select ends_at from public.full_lesson_pass) between now() + interval '13 days 23 hours' and now() + interval '14 days 1 minute',
    'FAIL: a pass lasts 14 days';
end $$;
select pg_temp.sees_all();
-- Tia ends it early
select public.end_full_lesson((select v from ids where k = 'p1'));
select pg_temp.sees_default();

-- ---------------------------------------------------------------- Tia asks, Sam says not this time
insert into ids values ('p2', public.ask_full_lesson('10000000-0000-0000-0000-000000000001'));
do $$ begin
  begin
    perform public.answer_full_lesson((select v from ids where k = 'p2'), true);
    raise exception 'FAIL: Tia answered her own ask';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
end $$;
reset role;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
set role authenticated;
select public.answer_full_lesson((select v from ids where k = 'p2'), false);
do $$ begin
  assert (select status from public.full_lesson_pass where id = (select v from ids where k = 'p2')) = 'declined', 'FAIL: decline';
  begin
    perform public.answer_full_lesson((select v from ids where k = 'p2'), true);
    raise exception 'FAIL: answered twice';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
end $$;
reset role;

-- She asks again; Sam inviting her for the same lesson is his yes.
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
set role authenticated;
insert into ids values ('p3', public.ask_full_lesson('10000000-0000-0000-0000-000000000001'));
select pg_temp.sees_default();
reset role;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
set role authenticated;
do $$ begin
  assert public.ask_full_lesson('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000b') = (select v from ids where k = 'p3'),
    'FAIL: Sam''s invite should answer Tia''s ask';
end $$;
reset role;
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
set role authenticated;
select pg_temp.sees_all();
reset role;

-- After 14 days it closes by itself.
update public.full_lesson_pass set ends_at = now() - interval '1 minute' where id = (select v from ids where k = 'p3');
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
set role authenticated;
select pg_temp.sees_default();
reset role;

-- ---------------------------------------------------------------- Uma, not Sam's teacher
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
set role authenticated;
do $$ begin
  assert (select count(*) from public.full_lesson_pass) = 0, 'FAIL: Uma sees Sam''s passes';
  begin
    perform public.ask_full_lesson('10000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: Uma asked for a stranger''s lesson';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
end $$;
reset role;

-- ---------------------------------------------------------------- Voice replies
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
set role authenticated;
insert into public.practice_take (id, student_id, feedback_id, session_id, recorded_at, duration_ms)
values ('60000000-0000-0000-0000-000000000001', auth.uid(), '40000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', now(), 8000);
insert into public.student_question (id, student_id, feedback_id, body)
values ('70000000-0000-0000-0000-000000000001', auth.uid(), '40000000-0000-0000-0000-000000000001', 'Is this lift too much?');
reset role;

select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
set role authenticated;
update public.practice_take set reply_clip_path = '00000000-0000-0000-0000-00000000000a/teacher-notes/r1.webm'
 where id = '60000000-0000-0000-0000-000000000001';
update public.student_question set status = 'answered', answer_clip_path = '00000000-0000-0000-0000-00000000000a/teacher-notes/r2.webm'
 where id = '70000000-0000-0000-0000-000000000001';
do $$ begin
  assert (select heard_at is not null and replied_by = auth.uid() and replied_at is not null from public.practice_take),
    'FAIL: a reply marks the take heard, by Tia';
  assert (select answered_by = auth.uid() from public.student_question), 'FAIL: voice answer';
  begin
    update public.practice_take set reply_clip_path = '00000000-0000-0000-0000-00000000000c/teacher-notes/x.webm';
    raise exception 'FAIL: a reply pointed outside Sam''s folder';
  exception when check_violation then null; end;
  begin
    update public.practice_take set duration_ms = 1;
    raise exception 'FAIL: Tia changed Sam''s take';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
end $$;
-- taking the reply back clears who replied
update public.practice_take set reply_clip_path = null;
do $$ begin
  assert (select replied_by is null and heard_at is not null from public.practice_take), 'FAIL: reply taken back';
end $$;
reset role;

select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
set role authenticated;
do $$ begin
  begin
    update public.practice_take set reply = 'great job, me';
    raise exception 'FAIL: Sam replied to his own take';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
  begin
    update public.student_question set answer_clip_path = null;
    raise exception 'FAIL: Sam removed the teacher''s voice answer';
  exception when raise_exception then if sqlerrm like 'FAIL%' then raise; end if; end;
end $$;
reset role;

\echo All key-moments tests passed.
