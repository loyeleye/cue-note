-- Photos of me: kept as taken (no clean copy, corners or caption), a note only
-- on a photo of me, and a teacher can move a shared one but change nothing else.
\set ON_ERROR_STOP on
\set QUIET on

-- These tests cover a teacher the student lets see all their lessons; since
-- 20261115000002 a new share starts limited to the lessons the teacher taught.
alter table public.teacher_student_share alter column restricted_to_taught set default false;
-- and every teacher agreed to the others seeing their lessons (20261117000001).
alter table public.lesson_view_consent alter column status set default 'granted';

-- Sam (student), Tia (Sam's teacher), Uma (not Sam's teacher)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}');
insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at) values
  ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());
insert into public.lesson (id, student_id, recorded_at, consent_at) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now()),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'first'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 5000, 9000, 'second'),
  ('40000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000002', 0, 1000, 'other lesson');

-- ---------------------------------------------------------------- Sam shares a photo of himself
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', false);
set role authenticated;

insert into public.photo_mark (id, lesson_id, taken_at_ms, feedback_id, image_path, kind, note)
values ('30000000-0000-0000-0000-0000000000e1', '10000000-0000-0000-0000-000000000001', 1500,
        '40000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-0000000000e1.jpg',
        'me', 'Jaw dropped, lips not spread');

do $$ begin
  assert (select kind from public.photo_mark where id = '30000000-0000-0000-0000-0000000000e1') = 'me',
    'FAIL: the photo of me was not kept as one';

  begin
    insert into public.photo_mark (id, lesson_id, taken_at_ms, kind) values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 1, 'selfie');
    raise exception 'FAIL: an unknown kind was accepted';
  exception when check_violation then null; end;

  begin
    update public.photo_mark set cleaned_path = 'x.clean.jpg' where id = '30000000-0000-0000-0000-0000000000e1';
    raise exception 'FAIL: a photo of me got a cleaned copy';
  exception when check_violation then null; end;

  begin
    update public.photo_mark set caption = 'An open mouth' where id = '30000000-0000-0000-0000-0000000000e1';
    raise exception 'FAIL: a photo of me got a caption';
  exception when check_violation then null; end;

  begin
    insert into public.photo_mark (id, lesson_id, taken_at_ms, note) values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 1, 'a note');
    raise exception 'FAIL: a sketch took a note meant for photos of me';
  exception when check_violation then null; end;

  begin
    insert into public.photo_mark (id, lesson_id, taken_at_ms, kind, note)
    values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 1, 'me', repeat('x', 201));
    raise exception 'FAIL: a 201-character note was accepted';
  exception when check_violation then null; end;
end $$;

reset role;

-- ---------------------------------------------------------------- Tia sees it and can only move it
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;

do $$ begin
  assert (select count(*) from public.photo_mark where kind = 'me') = 1, 'FAIL: Tia can''t see the photo Sam shared';
  update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000002' where id = '30000000-0000-0000-0000-0000000000e1';
  assert (select link_source from public.photo_mark where id = '30000000-0000-0000-0000-0000000000e1') = 'teacher',
    'FAIL: Tia''s move was not marked as hers';

  begin
    update public.photo_mark set note = 'Tia''s words' where id = '30000000-0000-0000-0000-0000000000e1';
    raise exception 'FAIL: Tia changed Sam''s note';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  begin
    update public.photo_mark set kind = 'sketch' where id = '30000000-0000-0000-0000-0000000000e1';
    raise exception 'FAIL: Tia turned Sam''s photo into a sketch';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  delete from public.photo_mark where id = '30000000-0000-0000-0000-0000000000e1';
  if found then raise exception 'FAIL: Tia deleted Sam''s photo'; end if;
end $$;

reset role;

-- ---------------------------------------------------------------- Sam stops sharing: the row goes
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', false);
set role authenticated;
delete from public.photo_mark where id = '30000000-0000-0000-0000-0000000000e1';
reset role;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.photo_mark) = 0, 'FAIL: Tia still sees the photo Sam stopped sharing';
end $$;
reset role;

-- ---------------------------------------------------------------- Uma never sees it
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.photo_mark) = 0, 'FAIL: Uma sees Sam''s photos';
end $$;
reset role;
\echo 'All photos-of-me tests passed.'
