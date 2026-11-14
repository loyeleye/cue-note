-- The student deletes part of a lesson: a whole stretch (exercises, takes and
-- cards, with tombstones) or just a try. Only the lesson's student may, and
-- the clip paths come back so the phone can delete the files.
\set ON_ERROR_STOP on
\set QUIET on

alter table public.teacher_student_share alter column restricted_to_taught set default false;
alter table public.lesson_view_consent alter column status set default 'granted';

-- Sam (student), Tia (Sam's teacher)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}');
insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at) values
  ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());

insert into public.lesson (id, student_id, recorded_at, consent_at) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now());
-- Two exercises: the warmup (0-10 s) and the chatter after the lesson (20-60 s).
insert into public.exercise (id, lesson_id, start_ms, end_ms) values
  ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 10000),
  ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 20000, 60000);
insert into public.take (id, lesson_id, start_ms, end_ms, clip_path, exercise_id) values
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 3000, 's/l/t1.m4a', '30000000-0000-0000-0000-000000000001'),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 5000, 7000, 's/l/t2.m4a', '30000000-0000-0000-0000-000000000001'),
  ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 25000, 27000, 's/l/t3.m4a', '30000000-0000-0000-0000-000000000002');
insert into public.feedback (id, lesson_id, take_id, span_start_ms, span_end_ms, cue, clip_path, summary_note_clip) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
   3000, 4500, 'Down and over', 's/l/fb1.m4a', null),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003',
   28000, 40000, 'Not a lesson', 's/l/fb2.m4a', 's/teacher-notes/n.m4a');

-- ---------------------------------------------------------------- Tia can't
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', false);
set role authenticated;
do $$ begin
  begin
    perform public.remove_lesson_part('10000000-0000-0000-0000-000000000001', 0, 100000, true);
    raise exception 'FAIL: a teacher deleted part of the student''s lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- Sam deletes one try
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', false);
set role authenticated;
do $$
declare paths text[];
begin
  select array_agg(p order by p) into paths
    from public.remove_lesson_part('10000000-0000-0000-0000-000000000001', 1000, 3000, false) p;
  assert paths = array['s/l/t1.m4a'], 'FAIL: deleting a try returned ' || coalesce(paths::text, 'nothing');
  assert not exists (select 1 from public.take where id = '20000000-0000-0000-0000-000000000001'), 'FAIL: try not deleted';
  assert exists (select 1 from public.take where id = '20000000-0000-0000-0000-000000000002'), 'FAIL: the other try went too';
  assert (select take_id from public.feedback where id = '40000000-0000-0000-0000-000000000001') is null,
    'FAIL: the card still points at the deleted try';
  assert exists (select 1 from public.feedback where id = '40000000-0000-0000-0000-000000000001'),
    'FAIL: deleting a try deleted its card';
  assert not exists (select 1 from public.feedback_removed), 'FAIL: deleting a try left a card tombstone';

  -- Everything from the chatter on: the exercise, its try and its card, with a tombstone.
  select array_agg(p order by p) into paths
    from public.remove_lesson_part('10000000-0000-0000-0000-000000000001', 20000, 60000, true) p;
  assert paths = array['s/l/fb2.m4a', 's/l/t3.m4a', 's/teacher-notes/n.m4a'],
    'FAIL: deleting a stretch returned ' || coalesce(paths::text, 'nothing');
  assert not exists (select 1 from public.exercise where id = '30000000-0000-0000-0000-000000000002'), 'FAIL: exercise not deleted';
  assert exists (select 1 from public.exercise where id = '30000000-0000-0000-0000-000000000001'), 'FAIL: the warmup went too';
  assert not exists (select 1 from public.take where id = '20000000-0000-0000-0000-000000000003'), 'FAIL: its try not deleted';
  assert not exists (select 1 from public.feedback where id = '40000000-0000-0000-0000-000000000002'), 'FAIL: its card not deleted';
  assert exists (select 1 from public.feedback where id = '40000000-0000-0000-0000-000000000001'), 'FAIL: the warmup''s card went too';
  assert exists (select 1 from public.feedback_removed where feedback_id = '40000000-0000-0000-0000-000000000002'
                 and removed_by = '00000000-0000-0000-0000-00000000000a'), 'FAIL: no tombstone for the deleted card';

  -- Nothing to delete is an error, not a silent no-op.
  begin
    perform public.remove_lesson_part('10000000-0000-0000-0000-000000000001', 5000, 5000, true);
    raise exception 'FAIL: an empty stretch was accepted';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

\echo 'All remove_lesson_part tests passed.'
