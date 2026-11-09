-- M4 teacher dashboard: songs, scores, annotations, save_card, push queue,
-- storage used. Same style as rls_test.sql: act as one user, assert.
\set ON_ERROR_STOP on
\set QUIET on

-- Sam (student), Tia (Sam's teacher), Uma (a teacher Sam never shared with)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"role":"teacher","display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"role":"teacher"}');

insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at)
values ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());
insert into public.lesson (id, student_id, recorded_at, consent_at)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now()),
       ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.take (id, lesson_id, start_ms, end_ms) values
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 9000),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 20000, 30000),
  ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 1000, 5000);
insert into public.feedback (id, lesson_id, take_id, span_start_ms, span_end_ms, cue, her_words)
values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', 9500, 15000, 'Lift the soft palate', 'more space at the back');
insert into public.card_version (feedback_id, author, payload)
values ('40000000-0000-0000-0000-000000000001', 'ai', '{"cue":"Lift the soft palate"}');
insert into storage.objects (bucket_id, name, owner_id, metadata) values
  ('clips',  '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/take1.opus', null, '{"size": 1000}'),
  ('clips',  '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/take2.opus', null, '{"size": 500}'),
  ('photos', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/p1.jpg',     null, '{"size": 250}');

do $$ begin
  assert (select count(*) from public.notification_outbox) = 0, 'FAIL: the AI card queued a push';
end $$;

-- ---------------------------------------------------------------- Tia
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$
declare v public.card_version;
begin
  -- One call rewords the card, relinks it and appends a version.
  v := public.save_card('40000000-0000-0000-0000-000000000001',
         '{"cue":"Yawn, then sing","practice_flag":"practice_this","take_id":"20000000-0000-0000-0000-000000000002"}');
  assert v.author = 'teacher' and v.author_id = auth.uid(), 'FAIL: version not credited to Tia';
  assert v.payload ->> 'cue' = 'Yawn, then sing', 'FAIL: version payload missing the new cue';
  assert v.payload ->> 'her_words' = 'more space at the back', 'FAIL: version should snapshot untouched fields';
  assert (select take_id from public.feedback where id = '40000000-0000-0000-0000-000000000001')
         = '20000000-0000-0000-0000-000000000002', 'FAIL: relink did not stick';
  assert (select count(*) from public.card_version) = 2, 'FAIL: expected AI + teacher versions';

  begin
    perform public.save_card('40000000-0000-0000-0000-000000000001',
              '{"take_id":"20000000-0000-0000-0000-000000000003"}');
    raise exception 'FAIL: relinked a card to a take from another lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  -- Songs, scores and annotations for her student.
  insert into public.song (id, student_id, title, created_by)
  values ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'Caro mio ben', auth.uid());
  insert into public.score (id, song_id, file_path, format, bar_map, created_by)
  values ('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001',
          '00000000-0000-0000-0000-00000000000a/50000000-0000-0000-0000-000000000001/score.pdf', 'pdf',
          '[{"bar":1,"page":1,"x":0.1,"y":0.2}]', auth.uid());
  insert into public.score_annotation (score_id, bar, kind, text, x, y, author_id)
  values ('60000000-0000-0000-0000-000000000001', 3, 'breath', '', 0.5, 0.2, auth.uid());
  perform public.save_card('40000000-0000-0000-0000-000000000001',
            '{"song_id":"50000000-0000-0000-0000-000000000001","bar_start":3,"bar_end":4}');
  assert (select bar_start from public.feedback) = 3, 'FAIL: could not pin the cue to bar 3';

  begin
    insert into public.song (student_id, title) values ('00000000-0000-0000-0000-00000000000c', 'not mine');
    raise exception 'FAIL: Tia added a song for someone who is not her student';
  exception when insufficient_privilege then null; end;

  -- Scores bucket: she may upload into her student's folder only.
  insert into storage.objects (bucket_id, name, metadata)
  values ('scores', '00000000-0000-0000-0000-00000000000a/50000000-0000-0000-0000-000000000001/score.pdf', '{"size": 4000}');
  begin
    insert into storage.objects (bucket_id, name)
    values ('scores', '00000000-0000-0000-0000-00000000000c/x/score.pdf');
    raise exception 'FAIL: Tia uploaded a score for a stranger';
  exception when insufficient_privilege then null; end;

  -- A voice note she can later delete; she cannot delete the student's clips.
  insert into storage.objects (bucket_id, name, metadata)
  values ('clips', '00000000-0000-0000-0000-00000000000a/teacher-notes/n1.webm', '{"size": 100}');

  assert (select bytes from public.storage_used('00000000-0000-0000-0000-00000000000a') where category = 'clips') = 1500,
    'FAIL: clip bytes wrong';
  assert (select bytes from public.storage_used('00000000-0000-0000-0000-00000000000a') where category = 'voice_notes') = 100,
    'FAIL: voice notes should be counted on their own';
  assert (select bytes from public.storage_used('00000000-0000-0000-0000-00000000000a') where category = 'scores') = 4000,
    'FAIL: score bytes wrong';

  delete from storage.objects where name like '%/teacher-notes/n1.webm';
  assert found, 'FAIL: Tia could not delete her own voice note';
  delete from storage.objects where name like '%/take1.opus';
  assert not found, 'FAIL: Tia deleted a student clip';

  begin
    perform count(*) from public.notification_outbox;
    raise exception 'FAIL: a teacher can read the push queue';
  exception when insufficient_privilege then null; end;
end $$;
reset role;

do $$ begin
  assert (select count(*) from public.notification_outbox where sent_at is null
            and recipient_id = '00000000-0000-0000-0000-00000000000a') = 2,
    'FAIL: each teacher save should queue one push for Sam';
end $$;

-- ---------------------------------------------------------------- Uma (unshared)
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.song) = 0, 'FAIL: unshared teacher sees songs';
  assert (select count(*) from public.score) = 0, 'FAIL: unshared teacher sees scores';
  assert (select count(*) from public.score_annotation) = 0, 'FAIL: unshared teacher sees annotations';
  begin
    perform public.save_card('40000000-0000-0000-0000-000000000001', '{"cue":"hijack"}');
    raise exception 'FAIL: unshared teacher saved a card';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    perform * from public.storage_used('00000000-0000-0000-0000-00000000000a');
    raise exception 'FAIL: unshared teacher read storage used';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- Sam
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.song) = 1, 'FAIL: Sam cannot see his song';
  assert (select count(*) from public.score_annotation) = 1, 'FAIL: Sam cannot see her annotation';
  delete from public.score_annotation;
  assert not found, 'FAIL: Sam deleted her annotation';
  begin
    perform public.save_card('40000000-0000-0000-0000-000000000001', '{"cue":"my own"}');
    raise exception 'FAIL: the student saved a teacher card';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  insert into public.device_token (token, user_id) values ('fcm-token-1', auth.uid());
  assert (select count(*) from public.device_token) = 1, 'FAIL: Sam cannot register his phone';
  assert (select sum(files) from public.storage_used(auth.uid())) = 4, 'FAIL: Sam cannot see his own storage';
end $$;
reset role;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.device_token) = 0, 'FAIL: Tia sees Sam''s phone token';
end $$;
reset role;

\echo 'All M4 tests passed.'
