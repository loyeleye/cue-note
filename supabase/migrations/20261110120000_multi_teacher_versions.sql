-- Several teachers on one student, with Google Docs style version history.
--
-- 1. Every teacher has an alias (a random animal to start), a color and a
--    "show my real name" switch. Other teachers see the alias, plus the real
--    name only if the switch is on. Students always see both:
--    "Tiger (Marigold Stewart)". list_teachers() is the one place that
--    applies those rules, because row security can't hide single columns.
-- 2. card_version remembers what a version was restored from.
-- 3. lesson_checkpoint: named versions of a whole lesson. A name is a moment
--    in time; the lesson as it was then is rebuilt from card_version.
-- 4. save_card checks for clashes. The editor sends the values it started
--    from, and a save is refused only when someone else changed one of the
--    same fields in the meantime. Different fields still merge.
-- 5. restore_checkpoint puts every card back to how it was at a named version,
--    as new versions, so nothing is lost.
-- 6. Live updates and presence through Supabase Realtime.

-- ---------------------------------------------------------------- 1. Identity
create function private.random_alias() returns text
language sql volatile set search_path = '' as $$
  select (array['Tiger','Heron','Otter','Fox','Lark','Robin','Wren','Finch','Owl','Panda',
                'Koala','Dolphin','Falcon','Lynx','Badger','Hare','Seal','Swan','Crane','Nightingale',
                'Puffin','Bear','Deer','Kestrel'])[1 + floor(random() * 24)::int]
$$;

-- Names, not hex codes: the dashboard maps each to a shade that works in
-- light and dark mode.
create function private.random_color() returns text
language sql volatile set search_path = '' as $$
  select (array['blue','teal','green','amber','coral','rose','violet','indigo'])[1 + floor(random() * 8)::int]
$$;

alter table public.profile
  add column alias       text    not null default private.random_alias(),
  add column color       text    not null default private.random_color(),
  add column name_public boolean not null default false,
  add constraint profile_alias_len check (char_length(btrim(alias)) between 1 and 40),
  add constraint profile_color_ok check (color in ('blue','teal','green','amber','coral','rose','violet','indigo'));

grant update (display_name, alias, color, name_public) on public.profile to authenticated;

-- The teachers of one student, as the caller is allowed to see them. The real
-- name comes back for the student, for the teacher themself, and for anyone
-- whose name_public is on; otherwise it is null.
create function public.list_teachers(student uuid)
returns table (id uuid, alias text, color text, name_public boolean, display_name text)
language sql stable security definer set search_path = '' as $$
  select p.id, p.alias, p.color, p.name_public,
         case when student = auth.uid() or p.id = auth.uid() or p.name_public
              then p.display_name end
    from public.teacher_student_share s
    join public.profile p on p.id = s.teacher_id
   where s.student_id = student and s.status = 'active'
     and (student = auth.uid() or private.is_teacher_of(student))
   order by s.accepted_at nulls last, p.id
$$;
revoke execute on function public.list_teachers(uuid) from public, anon;
grant execute on function public.list_teachers(uuid) to authenticated;

-- A new teacher's first share: if their random color or alias matches a
-- teacher the student already has, pick an unused one so the two can be
-- told apart. Teachers who already have students keep what they chose.
create or replace function public.accept_share(share uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  stu uuid;
  first_share boolean;
begin
  first_share := not exists (select 1 from public.teacher_student_share s
                              where s.teacher_id = auth.uid() and s.status = 'active');
  update public.teacher_student_share s
     set teacher_id = auth.uid(), status = 'active', accepted_at = now()
   where s.id = share and s.status = 'pending'
     and s.student_id <> auth.uid()
     and lower(s.invite_email) = lower(auth.jwt() ->> 'email')
  returning s.student_id into stu;
  if not found then
    raise exception 'no pending invite for this account';
  end if;
  update public.profile p set role = 'teacher' where p.id = auth.uid() and p.role <> 'teacher';

  if first_share then
    update public.profile p set color = coalesce((
      select c from unnest(array['blue','teal','green','amber','coral','rose','violet','indigo']) c
       where c not in (select o.color from public.teacher_student_share s join public.profile o on o.id = s.teacher_id
                        where s.student_id = stu and s.status = 'active' and o.id <> auth.uid())
       order by random() limit 1), p.color)
     where p.id = auth.uid() and p.color in (
       select o.color from public.teacher_student_share s join public.profile o on o.id = s.teacher_id
        where s.student_id = stu and s.status = 'active' and o.id <> auth.uid());
    update public.profile p set alias = coalesce((
      select a from unnest(array['Tiger','Heron','Otter','Fox','Lark','Robin','Wren','Finch','Owl','Panda',
                                 'Koala','Dolphin','Falcon','Lynx','Badger','Hare','Seal','Swan','Crane','Nightingale',
                                 'Puffin','Bear','Deer','Kestrel']) a
       where a not in (select o.alias from public.teacher_student_share s join public.profile o on o.id = s.teacher_id
                        where s.student_id = stu and s.status = 'active' and o.id <> auth.uid())
       order by random() limit 1), p.alias)
     where p.id = auth.uid() and p.alias in (
       select o.alias from public.teacher_student_share s join public.profile o on o.id = s.teacher_id
        where s.student_id = stu and s.status = 'active' and o.id <> auth.uid());
  end if;
end $$;

-- ---------------------------------------------------------------- 2. Restores
alter table public.card_version
  add column restored_from uuid references public.card_version (id) on delete set null;
-- The wall clock, not the transaction start, so several saves in one call
-- (a lesson restore) still have a clear order in the history.
alter table public.card_version alter column created_at set default clock_timestamp();

-- ---------------------------------------------------------------- 3. Named versions
create table public.lesson_checkpoint (
  id         uuid primary key default gen_random_uuid(),
  lesson_id  uuid not null references public.lesson (id) on delete cascade,
  label      text not null check (char_length(btrim(label)) between 1 and 80),
  created_by uuid references public.profile (id) on delete set null,
  created_at timestamptz not null default clock_timestamp()
);
create index lesson_checkpoint_lesson_idx on public.lesson_checkpoint (lesson_id, created_at desc);
create index lesson_checkpoint_created_by_idx on public.lesson_checkpoint (created_by);
alter table public.lesson_checkpoint enable row level security;

create policy checkpoint_select on public.lesson_checkpoint for select to authenticated
  using (private.can_see_student(private.lesson_student(lesson_id)));
create policy checkpoint_teacher_insert on public.lesson_checkpoint for insert to authenticated
  with check (created_by = auth.uid() and private.is_teacher_of(private.lesson_student(lesson_id)));
-- Like Google Docs, a name can be changed or removed later; here only by the
-- teacher who made it. Removing a name never removes any card version.
create policy checkpoint_owner_update on public.lesson_checkpoint for update to authenticated
  using (created_by = auth.uid() and private.is_teacher_of(private.lesson_student(lesson_id)))
  with check (created_by = auth.uid());
create policy checkpoint_owner_delete on public.lesson_checkpoint for delete to authenticated
  using (created_by = auth.uid() and private.is_teacher_of(private.lesson_student(lesson_id)));
revoke update on public.lesson_checkpoint from authenticated;
grant update (label) on public.lesson_checkpoint to authenticated;

-- The card history index already covers (feedback_id, created_at); restores
-- look versions up by what they came from.
create index card_version_restored_from_idx on public.card_version (restored_from) where restored_from is not null;
create index card_version_author_idx on public.card_version (author_id) where author_id is not null;

-- ---------------------------------------------------------------- 4. save_card
drop function public.save_card(uuid, jsonb);

-- expected: the values the editor started from, for the fields in changes.
-- Null skips the clash check (older dashboards, restores of a lesson).
create function public.save_card(fb uuid, changes jsonb, expected jsonb default null, restored_from uuid default null)
returns public.card_version
language plpgsql security invoker set search_path = '' as $$
declare
  f public.feedback;
  v public.card_version;
  cur jsonb;
  k text;
  clash jsonb := '[]'::jsonb;
  last_by uuid;
begin
  -- Lock the card so two saves at once take turns and the check is exact.
  select * into f from public.feedback where id = fb for update;
  if not found then raise exception 'card not found'; end if;

  if save_card.restored_from is not null and not exists (
       select 1 from public.card_version cv where cv.id = save_card.restored_from and cv.feedback_id = fb) then
    raise exception 'that version belongs to another card';
  end if;

  if expected is not null then
    cur := to_jsonb(f);
    for k in select jsonb_object_keys(changes) loop
      if expected ? k
         and (cur -> k) is distinct from coalesce(expected -> k, 'null'::jsonb)
         and (cur -> k) is distinct from coalesce(changes -> k, 'null'::jsonb) then
        clash := clash || to_jsonb(k);
      end if;
    end loop;
    if jsonb_array_length(clash) > 0 then
      select cv.author_id into last_by from public.card_version cv
       where cv.feedback_id = fb order by cv.created_at desc, cv.id desc limit 1;
      raise exception 'edit_conflict' using
        errcode = 'P0409',
        detail = jsonb_build_object('fields', clash, 'by', last_by, 'at', f.updated_at,
                                    'theirs', (select jsonb_object_agg(x, cur -> x) from jsonb_array_elements_text(clash) x))::text,
        hint = 'Someone else changed this card while you were editing. Choose which version to keep, then save again.';
    end if;
  end if;

  if changes ? 'take_id' and (changes ->> 'take_id') is not null and not exists (
       select 1 from public.take t where t.id = (changes ->> 'take_id')::uuid and t.lesson_id = f.lesson_id) then
    raise exception 'a card can only link to a take from the same lesson';
  end if;

  update public.feedback set
    cue           = case when changes ? 'cue'           then coalesce(changes ->> 'cue', '') else cue end,
    category      = case when changes ? 'category'      then changes ->> 'category' else category end,
    her_words     = case when changes ? 'her_words'     then coalesce(changes ->> 'her_words', '') else her_words end,
    practice_flag = case when changes ? 'practice_flag' then (changes ->> 'practice_flag')::public.practice_flag else practice_flag end,
    take_id       = case when changes ? 'take_id'       then (changes ->> 'take_id')::uuid else take_id end,
    song_id       = case when changes ? 'song_id'       then (changes ->> 'song_id')::uuid else song_id end,
    bar_start     = case when changes ? 'bar_start'     then (changes ->> 'bar_start')::int else bar_start end,
    bar_end       = case when changes ? 'bar_end'       then (changes ->> 'bar_end')::int else bar_end end
  where id = fb
  returning * into f;
  if not found then raise exception 'you cannot edit this card'; end if;

  insert into public.card_version (feedback_id, author, author_id, payload, restored_from)
  values (fb, 'teacher', auth.uid(), jsonb_build_object(
    'cue', f.cue, 'category', f.category, 'her_words', f.her_words,
    'practice_flag', f.practice_flag, 'take_id', f.take_id, 'song_id', f.song_id,
    'bar_start', f.bar_start, 'bar_end', f.bar_end), save_card.restored_from)
  returning * into v;
  return v;
end $$;
revoke execute on function public.save_card(uuid, jsonb, jsonb, uuid) from public, anon;
grant execute on function public.save_card(uuid, jsonb, jsonb, uuid) to authenticated;

-- ---------------------------------------------------------------- 5. Restore a lesson
-- Every card that looks different now from how it looked at the named
-- version gets a new version with the old values. Cards added after the name
-- are left alone. Returns how many cards changed.
create function public.restore_checkpoint(checkpoint uuid) returns int
language plpgsql security invoker set search_path = '' as $$
declare
  cp public.lesson_checkpoint;
  r record;
  n int := 0;
begin
  select * into cp from public.lesson_checkpoint where id = checkpoint;
  if not found then raise exception 'named version not found'; end if;

  for r in
    select distinct on (cv.feedback_id) cv.feedback_id, cv.id, cv.payload, to_jsonb(f) as cur
      from public.card_version cv
      join public.feedback f on f.id = cv.feedback_id
     where f.lesson_id = cp.lesson_id and cv.created_at <= cp.created_at
     order by cv.feedback_id, cv.created_at desc, cv.id desc
  loop
    if exists (select 1 from jsonb_each(r.payload) p where (r.cur -> p.key) is distinct from p.value) then
      perform public.save_card(r.feedback_id, r.payload, null, r.id);
      n := n + 1;
    end if;
  end loop;
  return n;
end $$;
revoke execute on function public.restore_checkpoint(uuid) from public, anon;
grant execute on function public.restore_checkpoint(uuid) to authenticated;

-- ---------------------------------------------------------------- Push wording
-- The student always sees who changed the card: "Tiger (Marigold Stewart)".
create or replace function public.queue_card_push() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  fb record;
  who text;
  url text;
begin
  if new.author <> 'teacher' then return new; end if;
  select f.id, f.lesson_id, f.cue, l.student_id into fb
    from public.feedback f join public.lesson l on l.id = f.lesson_id
   where f.id = new.feedback_id;
  select p.alias || coalesce(' (' || nullif(btrim(p.display_name), '') || ')', '') into who
    from public.profile p where p.id = new.author_id;
  insert into public.notification_outbox (recipient_id, feedback_id, lesson_id, title, body)
  values (fb.student_id, fb.id, fb.lesson_id,
          coalesce(who || ' updated a card', 'Card updated'),
          left(coalesce(nullif(fb.cue, ''), 'Your teacher updated a card'), 140));
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'net' and p.proname = 'http_post') then
    select value into url from private.app_config where key = 'notify_url';
    execute 'select net.http_post(url := $1, body := $2)' using url, '{"wake":true}'::jsonb;
  end if;
  return new;
end $$;

-- ---------------------------------------------------------------- 6. Realtime
-- Card changes reach other open dashboards. Realtime applies the same row
-- security, so a teacher only hears about cards they can already read.
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table public.feedback, public.card_version, public.lesson_checkpoint;
  end if;
end $$;

-- Presence ("who's here", "Jane is editing") runs on a private channel per
-- lesson, named lesson:<id>. Only that student's teachers may join it.
create function private.lesson_topic_teacher(topic text) returns boolean
language plpgsql stable security definer set search_path = '' as $$
begin
  if topic !~ '^lesson:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return false;
  end if;
  return private.is_teacher_of(private.lesson_student(substr(topic, 8)::uuid));
end $$;
revoke execute on function private.lesson_topic_teacher(text) from public, anon;
grant execute on function private.lesson_topic_teacher(text) to authenticated;

do $$ begin
  if to_regclass('realtime.messages') is not null then
    execute $p$
      create policy lesson_presence_read on realtime.messages for select to authenticated
        using (realtime.messages.extension in ('presence', 'broadcast')
               and private.lesson_topic_teacher(realtime.topic()))
    $p$;
    execute $p$
      create policy lesson_presence_write on realtime.messages for insert to authenticated
        with check (realtime.messages.extension in ('presence', 'broadcast')
                    and private.lesson_topic_teacher(realtime.topic()))
    $p$;
  end if;
end $$;
