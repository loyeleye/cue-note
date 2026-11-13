-- Several teachers on one student: aliases and who sees real names, clash
-- checks in save_card, named lesson versions and restoring them.
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
  ('00000000-0000-0000-0000-00000000000b', 'tia@example.com', '{"display_name":"Tia Real"}'),
  ('00000000-0000-0000-0000-00000000000e', 'ola@example.com', '{"display_name":"Ola Real"}'),
  ('00000000-0000-0000-0000-00000000000c', 'uma@example.com', '{"display_name":"Uma Real"}');

insert into public.teacher_student_share (id, student_id, invite_email) values
  ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', 'tia@example.com'),
  ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', 'ola@example.com');
insert into public.lesson (id, student_id, recorded_at, consent_at)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-00000000000a', now(), now());
insert into public.feedback (id, lesson_id, span_start_ms, span_end_ms, cue, her_words) values
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1000, 5000, 'Lift the soft palate', 'space'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 6000, 9000, 'Low breath', '');
insert into public.card_version (feedback_id, author, payload, created_at) values
  ('40000000-0000-0000-0000-000000000001', 'ai', '{"cue":"Lift the soft palate","her_words":"space"}', now() - interval '1 hour'),
  ('40000000-0000-0000-0000-000000000002', 'ai', '{"cue":"Low breath"}', now() - interval '1 hour');

-- Force a clash: Tia already uses Ola's starting alias and color.
update public.profile set alias = 'Heron', color = 'teal' where id in
  ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-00000000000e');

do $$ begin
  assert (select count(*) from public.profile where alias is null or color is null) = 0, 'FAIL: profiles need an alias and color';
end $$;

-- ---------------------------------------------------------------- Both accept
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
select public.accept_share('30000000-0000-0000-0000-000000000001');
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
select public.accept_share('30000000-0000-0000-0000-000000000002');
reset role;

do $$ begin
  assert (select color from public.profile where id = '00000000-0000-0000-0000-00000000000e')
      <> (select color from public.profile where id = '00000000-0000-0000-0000-00000000000b'),
    'FAIL: a new co-teacher kept the same color';
  assert (select alias from public.profile where id = '00000000-0000-0000-0000-00000000000e')
      <> (select alias from public.profile where id = '00000000-0000-0000-0000-00000000000b'),
    'FAIL: a new co-teacher kept the same alias';
  assert (select count(*) from public.teacher_student_share where status = 'active') = 2, 'FAIL: two active shares expected';
end $$;
update public.profile set alias = 'Tiger' where id = '00000000-0000-0000-0000-00000000000b';
update public.profile set alias = 'Lark'  where id = '00000000-0000-0000-0000-00000000000e';

-- ---------------------------------------------------------------- Ola
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$
declare r record;
begin
  -- Ola sees Tia's alias but not her real name, and can't read her profile row.
  select * into r from public.list_teachers('00000000-0000-0000-0000-00000000000a') where id = '00000000-0000-0000-0000-00000000000b';
  assert r.alias = 'Tiger' and r.display_name is null, 'FAIL: a co-teacher saw a private real name';
  assert (select count(*) from public.profile where id = '00000000-0000-0000-0000-00000000000b') = 0,
    'FAIL: a co-teacher can read the other teacher''s profile row';
  -- Her own entry carries her real name.
  select * into r from public.list_teachers('00000000-0000-0000-0000-00000000000a') where id = auth.uid();
  assert r.display_name = 'Ola Real', 'FAIL: a teacher should see their own name';
  assert (select count(*) from public.list_teachers('00000000-0000-0000-0000-00000000000a')) = 2, 'FAIL: expected both teachers';

  -- She can change her alias and color, but not to a color outside the palette.
  update public.profile set alias = 'Wren', color = 'rose' where id = auth.uid();
  assert (select alias from public.profile where id = auth.uid()) = 'Wren', 'FAIL: alias did not change';
  begin
    update public.profile set color = 'chartreuse' where id = auth.uid();
    raise exception 'FAIL: accepted a color outside the palette';
  exception when check_violation then null;
  end;
  begin
    update public.profile set role = 'student' where id = auth.uid();
    raise exception 'FAIL: a teacher changed their own role';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;

-- Tia turns her name public: now Ola sees it too.
update public.profile set name_public = true where id = '00000000-0000-0000-0000-00000000000b';
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select display_name from public.list_teachers('00000000-0000-0000-0000-00000000000a')
           where id = '00000000-0000-0000-0000-00000000000b') = 'Tia Real', 'FAIL: public name not shown';
end $$;
reset role;
update public.profile set name_public = false where id = '00000000-0000-0000-0000-00000000000b';

-- ---------------------------------------------------------------- Sam and Uma
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
do $$ begin
  -- The student always sees real names.
  assert (select count(*) from public.list_teachers(auth.uid()) where display_name is null) = 0,
    'FAIL: the student should see every teacher''s real name';
end $$;
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.list_teachers('00000000-0000-0000-0000-00000000000a')) = 0,
    'FAIL: an outsider listed Sam''s teachers';
end $$;
reset role;

-- ---------------------------------------------------------------- Clashes
-- Tia and Ola both open card 1 with the cue "Lift the soft palate".
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
select public.save_card('40000000-0000-0000-0000-000000000001',
  '{"cue":"Yawn, then sing"}', '{"cue":"Lift the soft palate"}');
reset role;

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$
declare d jsonb;
begin
  -- Ola changed a different field: it merges and keeps Tia's cue.
  perform public.save_card('40000000-0000-0000-0000-000000000001',
            '{"her_words":"more space"}', '{"her_words":"space"}');
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'Yawn, then sing',
    'FAIL: a save to another field undid Tia''s cue';

  -- Ola changed the same field from the old value: refused, with details.
  begin
    perform public.save_card('40000000-0000-0000-0000-000000000001',
              '{"cue":"Drop the jaw"}', '{"cue":"Lift the soft palate"}');
    raise exception 'FAIL: a same-field clash was saved over';
  exception when sqlstate 'P0409' then
    get stacked diagnostics d = pg_exception_detail;
    assert d -> 'fields' = '["cue"]'::jsonb, 'FAIL: clash should name the cue';
    assert d ->> 'by' = '00000000-0000-0000-0000-00000000000e', 'FAIL: clash should name the last editor';
    assert d -> 'theirs' ->> 'cue' = 'Yawn, then sing', 'FAIL: clash should carry the current cue';
  end;

  -- Making the same change as the other teacher isn't a clash.
  perform public.save_card('40000000-0000-0000-0000-000000000001',
            '{"cue":"Yawn, then sing"}', '{"cue":"Lift the soft palate"}');
  -- After choosing, she saves against the current value.
  perform public.save_card('40000000-0000-0000-0000-000000000001',
            '{"cue":"Yawn, then drop the jaw"}', '{"cue":"Yawn, then sing"}');
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'Yawn, then drop the jaw',
    'FAIL: resolved save did not stick';
end $$;
reset role;

-- ---------------------------------------------------------------- Named versions
-- Ola names the lesson as it is now, then both cards change afterwards.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
insert into public.lesson_checkpoint (id, lesson_id, label, created_by, created_at) values
  ('50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'After Ola''s review',
   '00000000-0000-0000-0000-00000000000e', now());
reset role;
-- Move the history behind the name so "later" edits are clearly later.
update public.card_version set created_at = created_at - interval '10 minutes';
update public.lesson_checkpoint set created_at = now() - interval '5 minutes';

select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000b","email":"tia@example.com"}', false);
set role authenticated;
do $$
declare v public.card_version;
begin
  perform public.save_card('40000000-0000-0000-0000-000000000001', '{"cue":"Something else"}');
  perform public.save_card('40000000-0000-0000-0000-000000000002', '{"cue":"Breathe into the back"}');
  assert (select count(*) from public.lesson_checkpoint) = 1, 'FAIL: Tia should see Ola''s named version';

  -- Only the teacher who named it can rename or remove it.
  update public.lesson_checkpoint set label = 'Mine now';
  assert (select label from public.lesson_checkpoint) = 'After Ola''s review', 'FAIL: renamed someone else''s version';
  delete from public.lesson_checkpoint;
  assert (select count(*) from public.lesson_checkpoint) = 1, 'FAIL: deleted someone else''s version';
  begin
    insert into public.lesson_checkpoint (lesson_id, label, created_by)
    values ('10000000-0000-0000-0000-000000000001', 'Pretending', '00000000-0000-0000-0000-00000000000e');
    raise exception 'FAIL: named a version as another teacher';
  exception when insufficient_privilege then null;
  end;

  -- Restore the lesson: both cards go back, each as a new version.
  assert public.restore_checkpoint('50000000-0000-0000-0000-000000000001') = 2, 'FAIL: expected two cards restored';
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000001') = 'Yawn, then drop the jaw',
    'FAIL: card 1 not restored';
  assert (select cue from public.feedback where id = '40000000-0000-0000-0000-000000000002') = 'Low breath',
    'FAIL: card 2 not restored to the AI original';
  select * into v from public.card_version where feedback_id = '40000000-0000-0000-0000-000000000002'
   order by created_at desc limit 1;
  assert v.restored_from is not null and v.author_id = auth.uid(), 'FAIL: restore should be credited and linked';
  -- A second restore finds nothing left to change.
  assert public.restore_checkpoint('50000000-0000-0000-0000-000000000001') = 0, 'FAIL: restore is not idempotent';

  -- A restore can't point at another card's version.
  begin
    perform public.save_card('40000000-0000-0000-0000-000000000001', '{"cue":"x"}', null, v.id);
    raise exception 'FAIL: restored from another card''s version';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- Uma sees no named versions and can't restore them.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.lesson_checkpoint) = 0, 'FAIL: an outsider sees named versions';
  begin
    perform public.restore_checkpoint('50000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: an outsider restored a lesson';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

-- ---------------------------------------------------------------- Presence
-- Joining lesson:<id> needs to be one of that student's teachers.
select set_config('realtime.topic', 'lesson:10000000-0000-0000-0000-000000000001', false);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
insert into realtime.messages (topic, extension) values (realtime.topic(), 'presence');
do $$ begin
  assert (select count(*) from realtime.messages) = 1, 'FAIL: a teacher cannot use the lesson channel';
end $$;
reset role;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000c","email":"uma@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from realtime.messages) = 0, 'FAIL: an outsider reads the lesson channel';
  begin
    insert into realtime.messages (topic, extension) values (realtime.topic(), 'presence');
    raise exception 'FAIL: an outsider joined the lesson channel';
  exception when insufficient_privilege then null;
  end;
  perform set_config('realtime.topic', 'lesson:not-a-uuid', false);
  assert (select count(*) from realtime.messages) = 0, 'FAIL: a malformed topic should just be refused';
end $$;
reset role;

-- ---------------------------------------------------------------- Push wording
do $$ begin
  assert exists (select 1 from public.notification_outbox where title = 'Tiger (Tia Real) updated a card'),
    'FAIL: the student''s push should name the teacher with their real name';
  assert not has_function_privilege('anon', 'public.list_teachers(uuid)', 'execute'), 'FAIL: anon can list teachers';
  assert not has_function_privilege('anon', 'public.restore_checkpoint(uuid)', 'execute'), 'FAIL: anon can restore';
end $$;

-- ---------------------------------------------------------------- Revoke
-- Sam removes Ola: she drops out of the teacher list and the lesson channel,
-- and can't switch the share back on.
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000a","email":"sam@example.com"}', false);
set role authenticated;
update public.teacher_student_share set status = 'revoked' where id = '30000000-0000-0000-0000-000000000002';
do $$ begin
  assert (select count(*) from public.list_teachers(auth.uid())) = 1, 'FAIL: a removed teacher is still listed';
end $$;
reset role;
select set_config('realtime.topic', 'lesson:10000000-0000-0000-0000-000000000001', false);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-0000-0000-00000000000e","email":"ola@example.com"}', false);
set role authenticated;
do $$ begin
  assert (select count(*) from public.list_teachers('00000000-0000-0000-0000-00000000000a')) = 0,
    'FAIL: a removed teacher still lists Sam''s teachers';
  assert not private.lesson_topic_teacher(realtime.topic()), 'FAIL: a removed teacher can join the lesson channel';
  assert (select count(*) from public.lesson_checkpoint) = 0, 'FAIL: a removed teacher still sees named versions';
  begin
    update public.teacher_student_share set status = 'active' where id = '30000000-0000-0000-0000-000000000002';
    raise exception 'FAIL: a removed teacher switched the share back on';
  exception when raise_exception then
    if sqlerrm like 'FAIL%' then raise; end if;
  end;
end $$;
reset role;

\echo 'All multi-teacher tests passed.'
