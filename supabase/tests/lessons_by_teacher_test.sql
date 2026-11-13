-- Who taught each lesson: the teacher's "only my lessons" preference, the
-- student's "only lessons they teach" limit, and claim_lesson.
\set ON_ERROR_STOP on
\set QUIET on

-- These tests cover a teacher the student lets see all their lessons; since
-- 20261115000002 a new share starts limited to the lessons the teacher taught.
alter table public.teacher_student_share alter column restricted_to_taught set default false;
-- and every teacher agreed to the others seeing their lessons (20261116000001).
alter table public.lesson_view_consent alter column status set default 'granted';

-- Sam (student), Tia and Ola (both Sam's teachers), Uma (not Sam's teacher)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000e', 'ola@example.com', '{"display_name":"Ola"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}');
insert into public.teacher_student_share (id, student_id, invite_email, teacher_id, status, accepted_at) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com',
   '00000000-0000-0000-0000-00000000000b', 'active', now()),
  ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', 'ola@example.com',
   '00000000-0000-0000-0000-00000000000e', 'active', now());

-- Lesson 1 taught by Tia, lesson 2 by Ola, lesson 3 unmarked.
insert into public.lesson (id, student_id, recorded_at, consent_at, taught_by) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000b'),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now(), '00000000-0000-0000-0000-00000000000e'),
  ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-00000000000a', now(), now(), null);
insert into public.take (id, lesson_id, start_ms, end_ms) values
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 0, 1000);
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'Tia''s cue'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 0, 1000, 'Ola''s cue');
insert into public.card_version (feedback_id, author, payload) values
  ('40000000-0000-0000-0000-000000000001', 'ai', '{"cue":"Tia''s cue"}'),
  ('40000000-0000-0000-0000-000000000002', 'ai', '{"cue":"Ola''s cue"}');
insert into public.comment (student_id, lesson_id, author_id, body) values
  ('00000000-0000-0000-0000-00000000000a', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000e', 'Ola on her lesson'),
  ('00000000-0000-0000-0000-00000000000a', null, '00000000-0000-0000-0000-00000000000e', 'Ola, general'),
  ('00000000-0000-0000-0000-00000000000a', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000e', 'Ola on Tia''s lesson'),
  ('00000000-0000-0000-0000-00000000000a', null, '00000000-0000-0000-0000-00000000000b', 'Tia, general');
insert into public.practice_item (student_id, lesson_id, author_id, body) values
  ('00000000-0000-0000-0000-00000000000a', null, '00000000-0000-0000-0000-00000000000e', 'Ola''s practice'),
  ('00000000-0000-0000-0000-00000000000a', null, '00000000-0000-0000-0000-00000000000b', 'Tia''s practice');
insert into storage.objects (bucket_id, name, owner_id) values
  ('clips',  '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/t.opus', null),
  ('clips',  '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000002/t.opus', null),
  ('clips',  '00000000-0000-0000-0000-00000000000a/teacher-notes/ola.webm', '00000000-0000-0000-0000-00000000000e'),
  ('photos', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000002/p.jpg', null);

-- Crediting someone who isn't Sam's teacher is refused.
do $$ begin
  perform set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', true);
  begin
    update public.lesson set taught_by = '00000000-0000-0000-0000-00000000000c' where id = '10000000-0000-0000-0000-000000000003';
    raise exception 'FAIL: credited a lesson to someone who isn''t a teacher of Sam';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;

-- ---------------------------------------------------------------- Without a limit
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 3, 'FAIL: an unlimited teacher should see every lesson';
  assert (select count(*) from public.comment) = 4, 'FAIL: an unlimited teacher should see every note';
  -- Her own preference; she can't set the student's limit on herself or Ola.
  update public.teacher_student_share set show_only_taught = true where id = '30000000-0000-0000-0000-000000000001';
  assert (select show_only_taught from public.teacher_student_share where id = '30000000-0000-0000-0000-000000000001'),
    'FAIL: preference not saved';
  begin
    update public.teacher_student_share set restricted_to_taught = true where id = '30000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: a teacher set the student''s limit';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  -- Claim the unmarked lesson, then let it go again.
  perform public.claim_lesson('10000000-0000-0000-0000-000000000003', true);
  assert (select taught_by from public.lesson where id = '10000000-0000-0000-0000-000000000003') = auth.uid(), 'FAIL: claim';
  perform public.claim_lesson('10000000-0000-0000-0000-000000000003', false);
  assert (select taught_by from public.lesson where id = '10000000-0000-0000-0000-000000000003') is null, 'FAIL: unclaim';
  begin
    perform public.claim_lesson('10000000-0000-0000-0000-000000000002', true);
    raise exception 'FAIL: claimed Ola''s lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- Sam limits Tia
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
update public.teacher_student_share set restricted_to_taught = true where id = '30000000-0000-0000-0000-000000000001';
do $$ begin
  assert (select restricted_to_taught from public.teacher_student_share where id = '30000000-0000-0000-0000-000000000001'),
    'FAIL: Sam could not limit Tia';
  begin
    update public.teacher_student_share set show_only_taught = false where id = '30000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: the student changed the teacher''s own preference';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  -- Sam still sees everything of his own.
  assert (select count(*) from public.lesson) = 3, 'FAIL: the student lost his lessons';
  assert (select count(*) from storage.objects) = 4, 'FAIL: the student lost his files';
end $$;
reset role;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 1, 'FAIL: a limited teacher sees lessons she didn''t teach';
  assert (select count(*) from public.take) = 1, 'FAIL: a limited teacher sees takes from other lessons';
  assert (select count(*) from public.feedback) = 1, 'FAIL: a limited teacher sees cards from other lessons';
  assert (select count(*) from public.card_version) = 1, 'FAIL: a limited teacher sees other lessons'' history';
  -- Her own note, and Ola's note on Tia's lesson; not Ola's general note or Ola's lesson note.
  assert (select count(*) from public.comment) = 2, 'FAIL: a limited teacher sees the wrong notes';
  assert (select count(*) from public.practice_item) = 1, 'FAIL: a limited teacher sees another teacher''s practice';
  -- Files: only her lesson's clip.
  assert (select count(*) from storage.objects) = 1, 'FAIL: a limited teacher sees the wrong files';
  -- She can't edit a card she can't see, or claim the unmarked lesson.
  begin
    perform public.save_card('40000000-0000-0000-0000-000000000002', '{"cue":"x"}');
    raise exception 'FAIL: a limited teacher edited another lesson''s card';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    perform public.claim_lesson('10000000-0000-0000-0000-000000000003', true);
    raise exception 'FAIL: a limited teacher claimed an unmarked lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  -- Her own lesson still works.
  perform public.save_card('40000000-0000-0000-0000-000000000001', '{"cue":"Tia again"}');
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'Tia again', 'FAIL: own card';
  -- Presence: only on her lesson.
  assert private.lesson_topic_teacher('lesson:10000000-0000-0000-0000-000000000001'), 'FAIL: presence on her lesson';
  assert not private.lesson_topic_teacher('lesson:10000000-0000-0000-0000-000000000002'), 'FAIL: presence on Ola''s lesson';
end $$;
reset role;

-- Ola isn't limited: she still sees all three lessons and every note.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 3, 'FAIL: Tia''s limit affected Ola';
  assert (select count(*) from public.comment) = 4, 'FAIL: Tia''s limit hid notes from Ola';
end $$;
reset role;

-- Uma still sees nothing.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 0, 'FAIL: an outsider sees lessons';
  begin
    perform public.claim_lesson('10000000-0000-0000-0000-000000000003', true);
    raise exception 'FAIL: an outsider claimed a lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

do $$ begin
  assert not has_function_privilege('anon', 'public.claim_lesson(uuid, boolean)', 'execute'), 'FAIL: anon can claim lessons';
end $$;

\echo 'All lessons-by-teacher tests passed.'
