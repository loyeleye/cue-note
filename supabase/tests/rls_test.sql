-- Row-level security tests. Each block acts as one user and asserts what
-- that user can and cannot see or change. Any failed assert stops the run.
\set ON_ERROR_STOP on
\set QUIET on

-- These tests cover a teacher the student lets see all their lessons; since
-- 20261115000002 a new share starts limited to the lessons the teacher taught.
alter table public.teacher_student_share alter column restricted_to_taught set default false;

-- Users: Sam (student), Tia (Sam's teacher), Uma (another teacher), Val (another student)
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'sam@example.com', '{"display_name":"Sam"}'),
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"role":"teacher","display_name":"Tia"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"role":"teacher"}'),
  ('00000000-0000-0000-0000-00000000000d', 'val@example.com', '{}');

do $$ begin
  -- Sign-up metadata is user-controlled, so {"role":"teacher"} must not count.
  assert (select role from public.profile where id = '00000000-0000-0000-0000-00000000000b') = 'student',
    'FAIL: sign-up metadata made Tia a teacher';
  assert (select role from public.profile where id = '00000000-0000-0000-0000-00000000000a') = 'student',
    'default role should be student';
end $$;

-- ---------------------------------------------------------------- Sam
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;

insert into public.lesson (id, student_id, recorded_at, consent_at)
values ('10000000-0000-0000-0000-000000000001', auth.uid(), now(), now());
insert into public.take (id, lesson_id, start_ms, end_ms)
values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 9000);
insert into public.photo_mark (id, lesson_id, taken_at_ms)
values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 5000);
insert into public.teacher_student_share (student_id, invite_email)
values (auth.uid(), 'TIA@example.com');
insert into public.teacher_student_share (student_id, invite_email)
values (auth.uid(), 'sam@example.com');

do $$ begin
  begin
    insert into public.lesson (id, student_id, recorded_at, consent_at)
    values (gen_random_uuid(), '00000000-0000-0000-0000-00000000000d', now(), now());
    raise exception 'FAIL: Sam created a lesson for Val';
  exception when insufficient_privilege then null; end;

  begin
    insert into public.lesson (id, student_id, recorded_at, consent_at)
    values (gen_random_uuid(), auth.uid(), now(), null);
    raise exception 'FAIL: lesson without consent was accepted';
  exception when not_null_violation then null; end;

  begin
    update public.profile set role = 'teacher' where id = auth.uid();
    raise exception 'FAIL: Sam changed his own role';
  exception when insufficient_privilege then null; end;

  begin
    insert into public.teacher_student_share (student_id, invite_email, teacher_id, status)
    values (auth.uid(), 'uma@example.com', '00000000-0000-0000-0000-00000000000c', 'active');
    raise exception 'FAIL: Sam activated a share without the teacher';
  exception when insufficient_privilege then null; end;

  begin
    perform public.accept_share((select id from public.teacher_student_share
                                  where invite_email = 'sam@example.com'));
    raise exception 'FAIL: Sam accepted his own invite and became a teacher';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  assert (select role from public.profile where id = auth.uid()) = 'student',
    'FAIL: Sam is no longer a student';
  delete from public.teacher_student_share where invite_email = 'sam@example.com';

  begin
    perform private.lesson_student('10000000-0000-0000-0000-000000000001');
  exception when others then
    raise exception 'FAIL: policies cannot reach the private helpers: %', sqlerrm;
  end;
end $$;
reset role;

-- Edge function (service role, no user) writes the AI card.
insert into public.feedback (id, lesson_id, take_id, span_start_ms, span_end_ms, cue, her_words)
values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000001', 9500, 15000, 'Lift the soft palate', 'more space at the back');
insert into public.card_version (feedback_id, author, payload)
values ('40000000-0000-0000-0000-000000000001', 'ai', '{"cue":"Lift the soft palate"}');
select set_config('request.jwt.claims', '', false);

-- ---------------------------------------------------------------- Tia before accepting
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 0, 'FAIL: Tia sees lessons before accepting';
  assert (select count(*) from public.teacher_student_share where status = 'pending') = 1,
    'FAIL: Tia cannot see her pending invite';
end $$;
select public.accept_share((select id from public.teacher_student_share limit 1));

-- ---------------------------------------------------------------- Tia after accepting
do $$ begin
  assert (select role from public.profile where id = auth.uid()) = 'teacher',
    'FAIL: accepting an invite did not make Tia a teacher';
  assert (select count(*) from public.lesson) = 1, 'FAIL: Tia cannot see Sam''s lesson';
  assert (select count(*) from public.take) = 1, 'FAIL: Tia cannot see Sam''s take';
  assert (select display_name from public.profile where id = '00000000-0000-0000-0000-00000000000a') = 'Sam',
    'FAIL: Tia cannot see Sam''s profile';

  update public.feedback set cue = 'Yawn-sigh, then sing', practice_flag = 'practice_this'
   where id = '40000000-0000-0000-0000-000000000001';
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'Yawn-sigh, then sing',
    'FAIL: Tia could not reword the cue';

  insert into public.card_version (feedback_id, author, author_id, payload)
  values ('40000000-0000-0000-0000-000000000001', 'teacher', auth.uid(), '{"cue":"Yawn-sigh, then sing"}');

  begin
    update public.feedback set span_start_ms = 0 where id = '40000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Tia changed timing, which the student app owns';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  begin
    update public.take set start_ms = 0 where id = '20000000-0000-0000-0000-000000000001';
    if found then raise exception 'FAIL: Tia edited a take'; end if;
  end;

  update public.photo_mark set feedback_id = '40000000-0000-0000-0000-000000000001'
   where id = '30000000-0000-0000-0000-000000000001';
  assert (select feedback_id from public.photo_mark) is not null, 'FAIL: Tia could not relink a photo';
  begin
    update public.photo_mark set caption = 'x' where id = '30000000-0000-0000-0000-000000000001';
    raise exception 'FAIL: Tia changed a photo caption';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;

  begin
    update public.card_version set payload = '{}';
    raise exception 'FAIL: card_version is not append-only';
  exception when insufficient_privilege then null; end;

  insert into public.practice_item (student_id, author_id, body)
  values ('00000000-0000-0000-0000-00000000000a', auth.uid(), 'Five-note scale on "ng", slow');
  insert into public.comment (student_id, lesson_id, author_id, body)
  values ('00000000-0000-0000-0000-00000000000a', '10000000-0000-0000-0000-000000000001', auth.uid(), 'Great breath support today');

  begin
    insert into public.practice_item (student_id, author_id, body)
    values ('00000000-0000-0000-0000-00000000000d', auth.uid(), 'not my student');
    raise exception 'FAIL: Tia wrote practice for a student she does not teach';
  exception when insufficient_privilege then null; end;
end $$;

-- Storage: Tia reads Sam's clips and may add a voice note, nothing else.
reset role;
insert into storage.objects (bucket_id, name) values
  ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/take1.opus'),
  ('clips', '00000000-0000-0000-0000-00000000000d/99999999-0000-0000-0000-000000000001/take1.opus');
set role authenticated;
do $$ begin
  assert (select count(*) from storage.objects) = 1, 'FAIL: Tia sees clips she should not (or not Sam''s)';
  insert into storage.objects (bucket_id, name)
  values ('clips', '00000000-0000-0000-0000-00000000000a/teacher-notes/note1.webm');
  begin
    insert into storage.objects (bucket_id, name)
    values ('clips', '00000000-0000-0000-0000-00000000000a/10000000-0000-0000-0000-000000000001/fake.opus');
    raise exception 'FAIL: Tia wrote into Sam''s lesson clips';
  exception when insufficient_privilege then null; end;
end $$;
reset role;

-- ---------------------------------------------------------------- Uma: unshared teacher
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 0, 'FAIL: unshared teacher reads a lesson';
  assert (select count(*) from public.take) = 0, 'FAIL: unshared teacher reads a take';
  assert (select count(*) from public.feedback) = 0, 'FAIL: unshared teacher reads feedback';
  assert (select count(*) from public.card_version) = 0, 'FAIL: unshared teacher reads card versions';
  assert (select count(*) from public.photo_mark) = 0, 'FAIL: unshared teacher reads photos';
  assert (select count(*) from public.comment) = 0, 'FAIL: unshared teacher reads comments';
  assert (select count(*) from storage.objects) = 0, 'FAIL: unshared teacher reads clips';
  assert (select count(*) from public.profile) = 1, 'FAIL: unshared teacher sees other profiles';
  begin
    perform public.accept_share((select id from public.teacher_student_share limit 1));
    raise exception 'FAIL: Uma accepted an invite meant for Tia';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  assert (select role from public.profile where id = auth.uid()) = 'student',
    'FAIL: Uma became a teacher without an invite';
end $$;
reset role;

-- ---------------------------------------------------------------- Sam sees her edits
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.card_version) = 2, 'FAIL: Sam does not see both card versions';
  assert (select count(*) from public.practice_item) = 1, 'FAIL: Sam does not see his practice item';
  begin
    update public.feedback set cue = 'my own cue';
    if found then raise exception 'FAIL: Sam edited her cue'; end if;
  end;
  update public.teacher_student_share set status = 'revoked';
end $$;
reset role;

-- ---------------------------------------------------------------- Tia after Sam revokes
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson) = 0, 'FAIL: Tia still sees lessons after revoke';
  -- She can still see the revoked row, but must not be able to switch it back on.
  begin
    update public.teacher_student_share set status = 'active';
    raise exception 'FAIL: Tia undid Sam''s revoke';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
  assert (select count(*) from public.lesson) = 0, 'FAIL: Tia sees lessons again after revoke';
end $$;
reset role;

-- ---------------------------------------------------------------- Anonymous
select set_config('request.jwt.claims', '', false);
set role anon;
do $$ begin
  begin
    perform count(*) from public.lesson;
    raise exception 'FAIL: anon can query lessons';
  exception when insufficient_privilege then null; end;
  begin
    perform private.is_teacher_of('00000000-0000-0000-0000-00000000000a');
    raise exception 'FAIL: anon can call the private helpers';
  exception when insufficient_privilege then null; end;
  begin
    perform public.accept_share(gen_random_uuid());
    raise exception 'FAIL: anon can call accept_share';
  exception when insufficient_privilege then null; end;
end $$;

-- Nothing that leaks data is left as a callable function in public.
reset role;
do $$ begin
  assert not exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                     where n.nspname = 'public'
                       and p.proname in ('is_teacher_of', 'lesson_student', 'feedback_student')),
    'FAIL: an access helper is still in the public schema';
end $$;
reset role;

\echo 'All RLS tests passed.'
