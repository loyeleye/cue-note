-- M3 cards: what the phone measured stays the phone's, the cost log is
-- readable but not writable, and past corrections reach only the edge function.
\set ON_ERROR_STOP on
\set QUIET on

-- These tests cover a teacher the student lets see all their lessons; since
-- 20261115000002 a new share starts limited to the lessons the teacher taught.
alter table public.teacher_student_share alter column restricted_to_taught set default false;

-- Sam (student), Tia (Sam's teacher), Uma (a teacher Sam never shared with)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"role":"teacher","display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"role":"teacher"}');
insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at)
values ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());

-- ---------------------------------------------------------------- Sam uploads a lesson
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
insert into public.lesson (id, student_id, recorded_at, consent_at, pipeline_state)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now(), 'uploading');
insert into public.take (id, lesson_id, start_ms, end_ms, clip_path)
values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 9000,
        '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/20000000-0000-0000-0000-000000000001.ogg');
-- Uploading the same take again (upsert) updates it.
insert into public.take (id, lesson_id, start_ms, end_ms)
values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1200, 9000)
on conflict (id) do update set start_ms = excluded.start_ms;
do $$ begin
  assert (select start_ms from public.take where id = '20000000-0000-0000-0000-000000000001') = 1200, 'FAIL: take upsert';
  -- The student can't write cards or the cost log; extract-cues does.
  begin
    insert into public.feedback (lesson_id, span_start_ms, span_end_ms) values ('10000000-0000-0000-0000-000000000001', 1, 2);
    raise exception 'FAIL: student wrote a feedback row';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into public.cue_extraction (lesson_id, model) values ('10000000-0000-0000-0000-000000000001', 'x');
    raise exception 'FAIL: student wrote the cost log';
  exception when insufficient_privilege then null;
  end;
  begin
    perform * from public.cue_corrections_for('00000000-0000-0000-0000-00000000000a');
    raise exception 'FAIL: student read the corrections function';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- extract-cues (service role)
set role service_role;
insert into public.feedback (id, lesson_id, local_id, take_id, span_start_ms, span_end_ms, transcript, link_confidence,
                             cue, category, her_words, practice_flag)
values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001-fb-001', '20000000-0000-0000-0000-000000000001', 9500, 15000,
        'okay so that was lovely but you are pushing the top, let the jaw drop and think of a yawn', 0.9,
        'Drop the jaw on the top note', 'registration', 'let the jaw drop and think of a yawn', 'practice_this');
insert into public.card_version (feedback_id, author, payload)
values ('40000000-0000-0000-0000-000000000001', 'ai',
        '{"cue":"Drop the jaw on the top note","category":"registration","her_words":"let the jaw drop and think of a yawn","practice_flag":"practice_this"}');
insert into public.cue_extraction (lesson_id, model, input_tokens, output_tokens, cost_usd, cards)
values ('10000000-0000-0000-0000-000000000001', 'claude-opus-5-5', 4000, 600, 0.028, 1);
do $$ begin
  -- One local id per lesson.
  begin
    insert into public.feedback (lesson_id, local_id, span_start_ms, span_end_ms)
    values ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001-fb-001', 1, 2);
    raise exception 'FAIL: two cards with one local id';
  exception when unique_violation then null;
  end;
  assert (select count(*) from public.cue_corrections_for('00000000-0000-0000-0000-00000000000a')) = 0,
    'FAIL: an untouched AI card counted as a correction';
end $$;
reset role;

-- ---------------------------------------------------------------- Tia corrects the card
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.cue_extraction) = 1, 'FAIL: Tia cannot see the cost log';
  assert (select transcript from public.feedback) like 'okay so%', 'FAIL: Tia cannot see the transcript';
  perform public.save_card('40000000-0000-0000-0000-000000000001', '{"cue":"Yawn into the top note","category":"resonance"}');
  -- What the phone heard isn't hers to change.
  begin
    update public.feedback set transcript = 'something else';
    raise exception 'FAIL: teacher changed the transcript';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  begin
    update public.feedback set link_confidence = 0.1;
    raise exception 'FAIL: teacher changed the link confidence';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

set role service_role;
do $$
declare r record;
begin
  select * into r from public.cue_corrections_for('00000000-0000-0000-0000-00000000000a');
  assert r.ai ->> 'cue' = 'Drop the jaw on the top note', 'FAIL: correction lost the AI version';
  assert r.teacher ->> 'cue' = 'Yawn into the top note', 'FAIL: correction lost her version';
  assert r.transcript like 'okay so%', 'FAIL: correction lost the transcript';
end $$;
reset role;

-- ---------------------------------------------------------------- Uma sees nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.cue_extraction) = 0, 'FAIL: an unshared teacher read the cost log';
  assert (select count(*) from public.feedback) = 0, 'FAIL: an unshared teacher read a card';
end $$;
reset role;

-- ---------------------------------------------------------------- Sam reads his cards
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.cue_extraction) = 1, 'FAIL: the student cannot see the cost log';
  assert (select cue from public.feedback) = 'Yawn into the top note', 'FAIL: the student does not see her edit';
  assert (select count(*) from public.card_version) = 2, 'FAIL: the student does not see the history';
end $$;
reset role;
\echo 'All M3 tests passed.'
