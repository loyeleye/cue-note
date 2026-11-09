-- Cue Note: M4 teacher dashboard
-- Runs after 20261109000003, which moved the RLS helpers (is_teacher_of,
-- lesson_student, feedback_student) into the private schema. New helpers that
-- only policies use go there too; the RPCs the dashboard calls stay in public.
-- Adds sheet music (song, score, score_annotation), one-call card saving,
-- push-notification plumbing, a storage-used report, and the scores bucket.
-- Nothing here changes an existing column; it only adds.

-- ---------------------------------------------------------------------------
-- Sheet music, step 1: the score as a shared reference
-- ---------------------------------------------------------------------------
create type public.score_format as enum ('pdf', 'image', 'musicxml');
create type public.annotation_kind as enum ('breath', 'vowel', 'dynamic', 'text');

-- A song can span many lessons. Either person can add one.
create table public.song (
  id         uuid primary key default gen_random_uuid(),
  student_id uuid not null references public.profile (id) on delete cascade,
  title      text not null check (length(trim(title)) > 0),
  composer   text not null default '',
  created_by uuid references public.profile (id) on delete set null,
  created_at timestamptz not null default now()
);
create index song_student_idx on public.song (student_id, created_at desc);

-- The score file for a song, plus where each bar sits on the page.
-- bar_map is a list of {"bar": 1, "page": 1, "x": 0.12, "y": 0.30}, with x and y
-- as fractions of the page so they survive any zoom level.
create table public.score (
  id         uuid primary key default gen_random_uuid(),
  song_id    uuid not null references public.song (id) on delete cascade,
  file_path  text not null,                     -- scores/<student_id>/<song_id>/<file>
  format     public.score_format not null,
  page_count int,
  bar_map    jsonb not null default '[]',
  created_by uuid references public.profile (id) on delete set null,
  created_at timestamptz not null default now()
);
create unique index score_one_per_song on public.score (song_id);

-- Breaths, vowels, dynamics and short text drawn over the score.
create table public.score_annotation (
  id         uuid primary key default gen_random_uuid(),
  score_id   uuid not null references public.score (id) on delete cascade,
  bar        int not null check (bar > 0),
  beat       numeric,
  kind       public.annotation_kind not null,
  text       text not null default '',
  page       int not null default 1,
  x          real not null check (x between 0 and 1),
  y          real not null check (y between 0 and 1),
  author_id  uuid not null references public.profile (id) on delete cascade,
  created_at timestamptz not null default now()
);
create index score_annotation_score_idx on public.score_annotation (score_id, bar);

-- Takes already carry song_id; give it its foreign key now the table exists.
alter table public.take
  add constraint take_song_fk foreign key (song_id) references public.song (id) on delete set null;
-- A cue can be pinned to bars of a song even when its take has no song yet.
alter table public.feedback
  add column song_id uuid references public.song (id) on delete set null;

create function private.song_student(s uuid) returns uuid
language sql stable security definer set search_path = '' as $$
  select student_id from public.song where id = s
$$;

create function private.score_student(sc uuid) returns uuid
language sql stable security definer set search_path = '' as $$
  select g.student_id from public.score c join public.song g on g.id = c.song_id where c.id = sc
$$;

-- True for the student and for a teacher with an active share.
create function private.can_see_student(student uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select student = auth.uid() or private.is_teacher_of(student)
$$;

-- Policies run as the signed-in user, so that role needs to call the helpers.
revoke execute on function private.song_student(uuid), private.score_student(uuid),
  private.can_see_student(uuid) from public, anon;
grant execute on function private.song_student(uuid), private.score_student(uuid),
  private.can_see_student(uuid) to authenticated;

alter table public.song             enable row level security;
alter table public.score            enable row level security;
alter table public.score_annotation enable row level security;

create policy song_access on public.song for all to authenticated
  using (private.can_see_student(student_id))
  with check (private.can_see_student(student_id));

create policy score_access on public.score for all to authenticated
  using (private.can_see_student(private.song_student(song_id)))
  with check (private.can_see_student(private.song_student(song_id)));

create policy annotation_select on public.score_annotation for select to authenticated
  using (private.can_see_student(private.score_student(score_id)));
-- Each person writes and removes only their own marks.
create policy annotation_write on public.score_annotation for all to authenticated
  using (author_id = auth.uid() and private.can_see_student(private.score_student(score_id)))
  with check (author_id = auth.uid() and private.can_see_student(private.score_student(score_id)));

-- ---------------------------------------------------------------------------
-- save_card: one call that updates the card and appends its new version, so
-- the two can never get out of step. Runs with the caller's rights, so the
-- usual security rules still decide whether she may do it.
-- ---------------------------------------------------------------------------
create function public.save_card(fb uuid, changes jsonb)
returns public.card_version
language plpgsql security invoker set search_path = '' as $$
declare
  f public.feedback;
  v public.card_version;
begin
  select * into f from public.feedback where id = fb;
  if not found then raise exception 'card not found'; end if;

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

  insert into public.card_version (feedback_id, author, author_id, payload)
  values (fb, 'teacher', auth.uid(), jsonb_build_object(
    'cue', f.cue, 'category', f.category, 'her_words', f.her_words,
    'practice_flag', f.practice_flag, 'take_id', f.take_id, 'song_id', f.song_id,
    'bar_start', f.bar_start, 'bar_end', f.bar_end))
  returning * into v;
  return v;
end $$;

-- ---------------------------------------------------------------------------
-- Push notifications: "card updated" on the student's phone
-- ---------------------------------------------------------------------------
-- Each signed-in phone registers its Firebase token here.
create table public.device_token (
  token      text primary key,
  user_id    uuid not null references public.profile (id) on delete cascade,
  platform   text not null default 'android',
  updated_at timestamptz not null default now()
);
create index device_token_user_idx on public.device_token (user_id);
alter table public.device_token enable row level security;
create policy device_token_own on public.device_token for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Pushes waiting to be sent. Only the notify edge function (service role)
-- reads it; clients never see it.
create table public.notification_outbox (
  id           uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references public.profile (id) on delete cascade,
  feedback_id  uuid references public.feedback (id) on delete cascade,
  lesson_id    uuid references public.lesson (id) on delete cascade,
  title        text not null,
  body         text not null,
  created_at   timestamptz not null default now(),
  sent_at      timestamptz,
  attempts     int not null default 0,
  last_error   text
);
create index notification_outbox_pending on public.notification_outbox (created_at) where sent_at is null;
alter table public.notification_outbox enable row level security;
revoke all on public.notification_outbox from anon, authenticated;

-- Where the notify function lives. The private schema isn't exposed by the
-- API, and no client role gets rights on this table.
create table private.app_config (key text primary key, value text not null);
revoke all on private.app_config from public, anon, authenticated;
insert into private.app_config (key, value) values
  ('notify_url', 'https://tgytbytwofiymsvajdkd.supabase.co/functions/v1/notify');

-- pg_net lets the database make an HTTP call after a commit. Hosted Supabase
-- has it; the plain-Postgres test database does not, so this is optional.
do $$ begin
  if exists (select 1 from pg_available_extensions where name = 'pg_net') then
    create extension if not exists pg_net;
  end if;
end $$;

-- When she saves a card, queue a push for the student and wake the notify
-- function. The call carries no data: the function reads the queue itself,
-- so a stranger calling it can at most make queued pushes go out sooner.
create function public.queue_card_push() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  fb record;
  url text;
begin
  if new.author <> 'teacher' then return new; end if;
  select f.id, f.lesson_id, f.cue, l.student_id into fb
    from public.feedback f join public.lesson l on l.id = f.lesson_id
   where f.id = new.feedback_id;
  insert into public.notification_outbox (recipient_id, feedback_id, lesson_id, title, body)
  values (fb.student_id, fb.id, fb.lesson_id, 'Card updated',
          left(coalesce(nullif(fb.cue, ''), 'Your teacher updated a card'), 140));
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'net' and p.proname = 'http_post') then
    select value into url from private.app_config where key = 'notify_url';
    execute 'select net.http_post(url := $1, body := $2)' using url, '{"wake":true}'::jsonb;
  end if;
  return new;
end $$;
revoke execute on function public.queue_card_push() from public, anon, authenticated;
create trigger card_version_push after insert on public.card_version
  for each row execute function public.queue_card_push();

-- ---------------------------------------------------------------------------
-- Storage used, per student, for the dashboard's settings screen
-- ---------------------------------------------------------------------------
create function public.storage_used(student uuid)
returns table (category text, files bigint, bytes bigint)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not private.can_see_student(student) then
    raise exception 'not your student';
  end if;
  return query
    select case
             when o.bucket_id = 'clips' and (storage.foldername(o.name))[2] = 'teacher-notes' then 'voice_notes'
             else o.bucket_id
           end as category,
           count(*)::bigint,
           coalesce(sum((o.metadata ->> 'size')::bigint), 0)::bigint
      from storage.objects o
     where o.bucket_id in ('clips', 'photos', 'scores')
       and (storage.foldername(o.name))[1] = student::text
     group by 1
     order by 1;
end $$;

-- ---------------------------------------------------------------------------
-- Storage: a private scores bucket, and letting her tidy her own voice notes
-- Paths are scores/<student_id>/<song_id>/<file>.
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public) values ('scores', 'scores', false)
on conflict (id) do nothing;

create policy storage_scores_access on storage.objects for all to authenticated
  using (bucket_id = 'scores' and private.can_see_student(((storage.foldername(name))[1])::uuid))
  with check (bucket_id = 'scores' and private.can_see_student(((storage.foldername(name))[1])::uuid));

create policy storage_teacher_voice_note_delete on storage.objects for delete to authenticated
  using (bucket_id = 'clips'
         and (storage.foldername(name))[2] = 'teacher-notes'
         and owner_id = auth.uid()::text
         and private.is_teacher_of(((storage.foldername(name))[1])::uuid));

revoke all on public.song, public.score, public.score_annotation, public.device_token from anon;

-- The dashboard's RPCs: signed-in users only.
revoke execute on function public.save_card(uuid, jsonb), public.storage_used(uuid) from public, anon;
grant execute on function public.save_card(uuid, jsonb), public.storage_used(uuid) to authenticated;
