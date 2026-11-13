-- A teacher with "see all" sees another teacher's lessons only once that
-- teacher agrees; and the read-only debug role (20261117000001).
\set ON_ERROR_STOP on
\set QUIET on

-- Sam (student), Tia and Ola (Sam's teachers), Dee (a debug account), Bo (another student)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000e', 'ola@example.com', '{"display_name":"Ola"}'),
  ('00000000-0000-0000-0000-00000000000d', 'dee@example.com', '{"display_name":"Dee"}'),
  ('00000000-0000-0000-0000-0000000000b0', 'bo@example.com', '{"display_name":"Bo"}');
insert into public.teacher_student_share (id, student_id, invite_email, teacher_id, status, accepted_at) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com',
   '00000000-0000-0000-0000-00000000000b', 'active', now()),
  ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', 'ola@example.com',
   '00000000-0000-0000-0000-00000000000e', 'active', now());
-- Lesson 1 by Tia, 2 by Ola, 3 with no teacher, 4 a guest's; Bo's lesson 9.
insert into public.lesson (id, student_id, recorded_at, consent_at, taught_by, teacher_name) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000b', null),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000e', null),
  ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-00000000000a', now(), now(), null, null),
  ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-00000000000a', now(), now(), null, 'Ana'),
  ('10000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-0000000000b0', now(), now(), null, null);
insert into public.comment (student_id, lesson_id, author_id, body) values
  ('00000000-0000-0000-0000-00000000000a', null, '00000000-0000-0000-0000-00000000000e', 'Ola, general');
insert into storage.objects (bucket_id, name, owner_id) values
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000002/t.opus', null),
  ('clips', '00000000-0000-0000-0000-00000000000a/teacher-notes/ola.webm', '00000000-0000-0000-0000-00000000000e');
insert into private.debug_user (user_id) values ('00000000-0000-0000-0000-00000000000d');

do $$ begin
  assert (select count(*) from public.lesson_view_consent) = 0, 'FAIL: no requests while both are limited';
end $$;

-- Sam lets Tia see all his lessons: Ola is asked.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
update public.teacher_student_share set restricted_to_taught = false where id = '30000000-0000-0000-0000-000000000001';
do $$ begin
  assert (select count(*) from public.lesson_view_consent
           where owner_id = '00000000-0000-0000-0000-00000000000e' and viewer_id = '00000000-0000-0000-0000-00000000000b'
             and status = 'pending') = 1, 'FAIL: Ola should be asked';
  assert (select count(*) from public.lesson_view_consent) = 1, 'FAIL: only Ola should be asked';
  begin
    update public.lesson_view_consent set status = 'granted';
    raise exception 'FAIL: the student answered for the teacher';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

-- Before Ola answers, Tia sees hers, the unclaimed one and the guest's, not Ola's.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 3, 'FAIL: Tia should see 3 lessons before Ola agrees';
  assert not exists (select 1 from public.lesson where id = '10000000-0000-0000-0000-000000000002'), 'FAIL: Ola''s lesson visible';
  assert (select count(*) from public.comment) = 0, 'FAIL: Ola''s note visible';
  assert (select count(*) from storage.objects) = 0, 'FAIL: Ola''s files visible';
  begin
    perform public.decide_lesson_view((select id from public.lesson_view_consent), true);
    raise exception 'FAIL: Tia granted herself';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- Ola sees the request and agrees.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.my_lesson_view_requests() where status = 'pending' and student_name = 'Sam') = 1,
    'FAIL: Ola should see the request';
  perform public.decide_lesson_view((select id from public.my_lesson_view_requests()), true);
end $$;
reset role;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 4, 'FAIL: Tia should see all 4 once Ola agrees';
  assert (select count(*) from public.comment) = 1, 'FAIL: Ola''s note hidden after she agreed';
  assert (select count(*) from storage.objects) = 2, 'FAIL: Ola''s files hidden after she agreed';
end $$;
reset role;

-- Ola takes it back.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
select public.decide_lesson_view((select id from public.my_lesson_view_requests()), false);
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 3, 'FAIL: Tia still sees Ola''s lesson after a no';
end $$;
reset role;

-- The debug account sees every student's lessons, and can't change anything.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000d","email":"dee@example.com"}', false);
set role authenticated;
do $$ begin
  assert public.am_debug(), 'FAIL: debug account not recognised';
  assert (select count(*) from public.lesson) = 5, 'FAIL: debug should see every lesson';
  assert (select count(*) from public.profile) = 5, 'FAIL: debug should see every profile';
  assert (select count(*) from public.teacher_student_share) = 2, 'FAIL: debug should see every share';
  assert (select count(*) from public.list_teachers('00000000-0000-0000-0000-00000000000a')) = 2, 'FAIL: debug list_teachers';
  assert (select count(*) from storage.objects) = 2, 'FAIL: debug should see files';
  update public.lesson set teacher_name = 'Dee' where id = '10000000-0000-0000-0000-000000000003';
  assert not found, 'FAIL: debug changed a lesson';
  delete from public.comment;
  assert not found, 'FAIL: debug deleted a note';
  begin
    perform public.claim_lesson('10000000-0000-0000-0000-000000000003', true);
    raise exception 'FAIL: debug claimed a lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- Anyone else is not debug.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  assert not public.am_debug(), 'FAIL: Sam is not debug';
end $$;
reset role;
\echo 'All teacher consent and debug tests passed.'
