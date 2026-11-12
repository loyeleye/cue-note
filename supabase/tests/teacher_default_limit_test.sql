-- A teacher sees only the lessons they taught unless the student lets them
-- see everything (20261115000002).
\set ON_ERROR_STOP on
\set QUIET on

insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}');
-- Sam invites Tia the way the phone does: no limit column sent.
insert into public.teacher_student_share (id, student_id, invite_email, teacher_id, status, accepted_at) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com',
   '00000000-0000-0000-0000-00000000000b', 'active', now());
insert into public.lesson (id, student_id, recorded_at, consent_at, taught_by) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000b'),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now(), null);
update public.lesson set teacher_name = 'Ana' where id = '10000000-0000-0000-0000-000000000002';

do $$ begin
  assert (select restricted_to_taught from public.teacher_student_share where id = '30000000-0000-0000-0000-000000000001'),
    'FAIL: a new share should start limited to the teacher''s own lessons';
end $$;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 1, 'FAIL: Tia should see only the lesson she taught';
  assert (select id from public.lesson) = '10000000-0000-0000-0000-000000000001', 'FAIL: wrong lesson visible';
end $$;
reset role;

-- Sam lets her see everything.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
update public.teacher_student_share set restricted_to_taught = false where id = '30000000-0000-0000-0000-000000000001';
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 2, 'FAIL: granted, Tia should see every lesson';
end $$;
reset role;
\echo 'All teacher default-limit tests passed.'
