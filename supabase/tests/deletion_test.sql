-- Real deletion: a teacher deletes a card (leaving a tombstone) or removes
-- just its audio; only card clips may be deleted by a teacher; a student
-- deleting a lesson takes everything of it with them.
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
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.take (id, lesson_id, start_ms, end_ms, clip_path) values
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000,
   '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/take.m4a');
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue, clip_path) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'Down and over',
   '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/fb1.m4a'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 2000, 3000, 'Ribs open',
   '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/fb2.m4a');
insert into public.card_version (feedback_id, author, payload) values
  ('40000000-0000-0000-0000-000000000001', 'ai', '{"cue":"Down and over"}');
insert into storage.objects (bucket_id, name, owner_id) values
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/take.m4a', null),
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/fb1.m4a', null),
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/fb2.m4a', null);

-- ---------------------------------------------------------------- Uma, not Sam's teacher
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c"}', false);
set role authenticated;
do $$ begin
  begin
    perform public.remove_card('40000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: Uma deleted Sam''s card';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  delete from storage.objects where name like '%/fb1.m4a';
  assert not found, 'FAIL: Uma deleted a clip of Sam''s';
end $$;
reset role;

-- ---------------------------------------------------------------- Tia, Sam's teacher
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b"}', false);
set role authenticated;
do $$ begin
  -- Only card clips: the student's own take is not hers to delete.
  delete from storage.objects where name like '%/take.m4a';
  assert not found, 'FAIL: Tia deleted the student''s take';

  -- Remove the audio, keep the note: the file first, then the link.
  delete from storage.objects where name like '%/fb2.m4a';
  assert found, 'FAIL: Tia could not delete a card clip';
  update public.feedback set clip_path = null, audio_removed_at = now() where id = '40000000-0000-0000-0000-000000000002';
  assert (select clip_path from public.feedback where id = '40000000-0000-0000-0000-000000000002') is null,
    'FAIL: audio link not removed';
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'Ribs open',
    'FAIL: removing the audio lost the note';

  -- Once removed, the audio can't be relinked by a teacher.
  begin
    update public.feedback set clip_path = 'x', audio_removed_at = null where id = '40000000-0000-0000-0000-000000000002';
    raise exception 'FAIL: Tia relinked removed audio';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  -- And clearing the clip still needs audio_removed_at.
  begin
    update public.feedback set clip_path = null where id = '40000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Tia cleared a clip without marking the audio removed';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  -- Delete a card: the file, then the card, which leaves a tombstone.
  delete from storage.objects where name like '%/fb1.m4a';
  assert found, 'FAIL: Tia could not delete the card clip';
  perform public.remove_card('40000000-0000-0000-0000-000000000001');
  assert not exists (select 1 from public.feedback where id = '40000000-0000-0000-0000-000000000001'),
    'FAIL: card not deleted';
  assert exists (select 1 from public.feedback_removed where feedback_id = '40000000-0000-0000-0000-000000000001'
                 and removed_by = '00000000-0000-0000-0000-00000000000b'),
    'FAIL: no tombstone';

  -- Tombstones can't be undone by a client.
  begin
    delete from public.feedback_removed;
    raise exception 'FAIL: Tia deleted a tombstone';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

do $$ begin
  assert not exists (select 1 from public.card_version where feedback_id = '40000000-0000-0000-0000-000000000001'),
    'FAIL: the card''s versions outlived it';
end $$;

-- ---------------------------------------------------------------- Sam deletes the lesson
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', false);
set role authenticated;
do $$ begin
  assert exists (select 1 from public.feedback_removed), 'FAIL: Sam can''t see the tombstone';
  delete from storage.objects where name like '%/10000000-0000-0000-0000-000000000001/%';
  assert found, 'FAIL: Sam could not delete the lesson''s files';
  delete from public.lesson where id = '10000000-0000-0000-0000-000000000001';
  assert found, 'FAIL: Sam could not delete the lesson';
end $$;
reset role;

do $$ begin
  assert not exists (select 1 from public.feedback), 'FAIL: cards outlived the lesson';
  assert not exists (select 1 from public.take), 'FAIL: takes outlived the lesson';
  assert not exists (select 1 from public.feedback_removed), 'FAIL: tombstones outlived the lesson';
  assert not exists (select 1 from storage.objects), 'FAIL: files outlived the lesson';
end $$;

\echo 'All deletion tests passed.'
