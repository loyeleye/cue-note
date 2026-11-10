-- Suggested diagrams: who sees them, and that a teacher's dismissal is hers
-- while a student's is theirs alone.
\set ON_ERROR_STOP on
\set QUIET on

-- Sam (student), Tia (Sam's teacher), Uma (not Sam's teacher)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma"}');
insert into public.teacher_student_share (student_id, invite_email, teacher_id, status, accepted_at) values
  ('00000000-0000-0000-0000-00000000000a', 'tia@example.com', '00000000-0000-0000-0000-00000000000b', 'active', now());
insert into public.lesson (id, student_id, recorded_at, consent_at) values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'Breathe low');
-- the service role (extract-cues) saves two picks
insert into public.diagram_suggestion (id, feedback_id, diagram_id, confidence, source) values
  ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'breath-low-high', 0.86, 'cues'),
  ('50000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', 'breath-diaphragm', 0.74, 'sketch');

do $$ begin
  begin
    insert into public.diagram_suggestion (feedback_id, diagram_id, confidence, source)
    values ('40000000-0000-0000-0000-000000000001', 'x', 1.5, 'cues');
    raise exception 'FAIL: a confidence over 1 was accepted';
  exception when check_violation then null; end;
end $$;

-- ---------------------------------------------------------------- Sam
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', false);
set role authenticated;

do $$ begin
  assert (select count(*) from public.diagram_suggestion) = 2, 'FAIL: Sam does not see the suggestions';

  update public.diagram_suggestion set student_status = 'dismissed' where id = '50000000-0000-0000-0000-000000000002';
  assert (select student_status from public.diagram_suggestion where id = '50000000-0000-0000-0000-000000000002') = 'dismissed',
    'FAIL: Sam could not dismiss';
  assert (select student_decided_at from public.diagram_suggestion where id = '50000000-0000-0000-0000-000000000002') is not null,
    'FAIL: Sam''s decision has no time';

  begin
    update public.diagram_suggestion set teacher_status = 'dismissed' where id = '50000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Sam made the teacher''s decision';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  begin
    update public.diagram_suggestion set diagram_id = 'vowels-jaw' where id = '50000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Sam changed which diagram it is';
  exception when insufficient_privilege then null; end;

  begin
    insert into public.diagram_suggestion (feedback_id, diagram_id, confidence, source)
    values ('40000000-0000-0000-0000-000000000001', 'vowels-jaw', 0.9, 'cues');
    raise exception 'FAIL: Sam added a suggestion';
  exception when insufficient_privilege then null; end;
end $$;

reset role;

-- ---------------------------------------------------------------- Tia
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;

do $$ begin
  assert (select count(*) from public.diagram_suggestion) = 2, 'FAIL: Tia does not see the suggestions';
  -- Sam's dismissal is Sam's: the teacher's side is still open
  assert (select teacher_status from public.diagram_suggestion where id = '50000000-0000-0000-0000-000000000002') = 'open',
    'FAIL: Sam''s dismissal hid it for Tia';

  update public.diagram_suggestion set teacher_status = 'dismissed' where id = '50000000-0000-0000-0000-000000000001';
  assert (select teacher_decided_by from public.diagram_suggestion where id = '50000000-0000-0000-0000-000000000001')
    = '00000000-0000-0000-0000-00000000000b', 'FAIL: Tia''s dismissal is not marked as hers';

  begin
    update public.diagram_suggestion set student_status = 'accepted' where id = '50000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Tia made the student''s decision';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  begin
    delete from public.diagram_suggestion where id = '50000000-0000-0000-0000-000000000002';
    raise exception 'FAIL: Tia deleted a suggestion';
  exception when insufficient_privilege then null; end;
end $$;

reset role;

-- ---------------------------------------------------------------- Uma
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;

do $$ begin
  assert (select count(*) from public.diagram_suggestion) = 0, 'FAIL: Uma sees Sam''s suggestions';
  update public.diagram_suggestion set teacher_status = 'accepted';
  if found then raise exception 'FAIL: Uma decided on Sam''s suggestion'; end if;
end $$;

reset role;
\echo 'All diagram suggestion tests passed.'
