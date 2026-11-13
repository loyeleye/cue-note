-- A typed name for a teacher who isn't on Cue Note, and changing a lesson's
-- teacher after it went up (the student, from the phone).
\set ON_ERROR_STOP on
\set QUIET on

-- These tests cover a teacher the student lets see all their lessons; since
-- 20261115000002 a new share starts limited to the lessons the teacher taught.
alter table public.teacher_student_share alter column restricted_to_taught set default false;
-- and every teacher agreed to the others seeing their lessons (20261117000001).
alter table public.lesson_view_consent alter column status set default 'granted';

-- Sam (student), Tia (Sam's teacher).
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}');
insert into public.teacher_student_share (id, student_id, invite_email, teacher_id, status, accepted_at) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com',
   '00000000-0000-0000-0000-00000000000b', 'active', now());
insert into public.lesson (id, student_id, recorded_at, consent_at, teacher_name) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), 'Ana');

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  -- Sam changes the lesson to Tia, then back to a name, as the phone does.
  update public.lesson set taught_by = '00000000-0000-0000-0000-00000000000b', teacher_name = null
   where id = '10000000-0000-0000-0000-000000000001';
  assert (select taught_by from public.lesson where id = '10000000-0000-0000-0000-000000000001')
    = '00000000-0000-0000-0000-00000000000b', 'FAIL: student re-credits a lesson';
  update public.lesson set taught_by = null, teacher_name = 'Ana B'
   where id = '10000000-0000-0000-0000-000000000001';
  assert (select teacher_name from public.lesson where id = '10000000-0000-0000-0000-000000000001') = 'Ana B',
    'FAIL: typed name not saved';
  -- A blank or very long name is refused.
  begin
    update public.lesson set teacher_name = '   ' where id = '10000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: blank teacher name accepted';
  exception when check_violation then null;
  end;
  begin
    update public.lesson set teacher_name = repeat('x', 81) where id = '10000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: long teacher name accepted';
  exception when check_violation then null;
  end;
end $$;
reset role;

-- Tia sees the name, and claiming the lesson replaces it.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select teacher_name from public.lesson where id = '10000000-0000-0000-0000-000000000001') = 'Ana B',
    'FAIL: teacher can''t see the typed name';
  begin
    update public.lesson set teacher_name = 'Tia' where id = '10000000-0000-0000-0000-000000000001';
  exception when insufficient_privilege then null;
  end;
  assert (select teacher_name from public.lesson where id = '10000000-0000-0000-0000-000000000001') = 'Ana B',
    'FAIL: a teacher changed the student''s typed name';
  perform public.claim_lesson('10000000-0000-0000-0000-000000000001', true);
  assert (select taught_by from public.lesson where id = '10000000-0000-0000-0000-000000000001') = auth.uid(), 'FAIL: claim';
  assert (select teacher_name from public.lesson where id = '10000000-0000-0000-0000-000000000001') is null,
    'FAIL: claim should replace the typed name';
end $$;
reset role;
\echo 'All lesson teacher-name tests passed.'
