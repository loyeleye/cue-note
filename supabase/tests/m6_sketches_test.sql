-- M6 sketches: who can move a photo to another card, that a move by the
-- teacher is marked as hers, and that a photo only goes on a card from its
-- own lesson.
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
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now()),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 0, 1000, 'first'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 5000, 9000, 'second'),
  ('40000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000002', 0, 1000, 'other lesson');

-- ---------------------------------------------------------------- Sam's phone uploads a photo
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a"}', false);
set role authenticated;

insert into public.photo_mark (id, lesson_id, taken_at_ms, feedback_id, auto_feedback_id, image_path, cleaned_path,
                               corners, page_confidence)
values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1500,
        '40000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001.jpg',
        '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001.clean.jpg',
        '[0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9]', 0.8);

do $$ begin
  assert (select link_source from public.photo_mark) = 'auto', 'FAIL: a new photo is not marked as the phone''s guess';

  begin
    insert into public.photo_mark (id, lesson_id, taken_at_ms, corners)
    values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 1, '[0.1,0.2]');
    raise exception 'FAIL: corners with 2 numbers were accepted';
  exception when check_violation then null; end;

  begin
    update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000009'
     where id = '30000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Sam put a photo on a card from another lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  -- Sam moves it himself
  update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000002', link_source = 'student'
   where id = '30000000-0000-0000-0000-000000000001';
  assert (select link_source from public.photo_mark) = 'student', 'FAIL: Sam''s move was not kept';
end $$;

reset role;

-- ---------------------------------------------------------------- Tia moves it back on the dashboard
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;

do $$ begin
  -- the dashboard sends only the card; the database marks the move as hers
  update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000001'
   where id = '30000000-0000-0000-0000-000000000001';
  assert (select link_source from public.photo_mark) = 'teacher', 'FAIL: Tia''s move was not marked as hers';
  assert (select auto_feedback_id from public.photo_mark) = '40000000-0000-0000-0000-000000000001',
    'FAIL: the phone''s guess was lost';

  begin
    update public.photo_mark set link_source = 'auto' where id = '30000000-0000-0000-0000-000000000001';
    assert (select link_source from public.photo_mark) = 'teacher', 'FAIL: Tia passed her move off as the phone''s';
  end;

  begin
    update public.photo_mark set cleaned_path = 'x' where id = '30000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Tia changed the cleaned photo';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  begin
    update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000009'
     where id = '30000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Tia put a photo on a card from another lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  -- and off every card
  update public.photo_mark set feedback_id = null where id = '30000000-0000-0000-0000-000000000001';
  assert (select feedback_id from public.photo_mark) is null, 'FAIL: Tia could not take a photo off its card';
end $$;

reset role;

-- ---------------------------------------------------------------- Uma sees and moves nothing
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;

do $$ begin
  assert (select count(*) from public.photo_mark) = 0, 'FAIL: Uma sees Sam''s photos';
  update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000002';
  if found then raise exception 'FAIL: Uma moved Sam''s photo'; end if;
end $$;

reset role;
\echo 'All M6 sketch tests passed.'
