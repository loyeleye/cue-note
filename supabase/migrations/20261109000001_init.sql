-- Sing Assist: M1 schema
-- Tables follow the "Data model and sync" section of the design doc.
-- Practice (M5) and sheet music (M4/M7) tables arrive in their own migrations.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Types
-- ---------------------------------------------------------------------------
create type public.user_role as enum ('student', 'teacher');
create type public.share_status as enum ('pending', 'active', 'revoked');
create type public.card_author as enum ('ai', 'teacher');
create type public.practice_flag as enum ('practice_this', 'just_listen');

-- ---------------------------------------------------------------------------
-- profile: one row per auth user, created automatically on sign-up
-- ---------------------------------------------------------------------------
create table public.profile (
  id           uuid primary key references auth.users (id) on delete cascade,
  role         public.user_role not null default 'student',
  display_name text not null default '',
  created_at   timestamptz not null default now()
);

-- The role comes from the sign-up metadata ({"role": "teacher"}); anything
-- else, or nothing, makes a student.
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profile (id, role, display_name)
  values (
    new.id,
    case when new.raw_user_meta_data ->> 'role' = 'teacher'
         then 'teacher'::public.user_role else 'student'::public.user_role end,
    coalesce(new.raw_user_meta_data ->> 'display_name', '')
  );
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- teacher_student_share: the student invites a teacher by email; the teacher
-- accepts with accept_share(). Only an active share grants access.
-- ---------------------------------------------------------------------------
create table public.teacher_student_share (
  id             uuid primary key default gen_random_uuid(),
  student_id     uuid not null references public.profile (id) on delete cascade,
  invite_email   text not null,
  teacher_id     uuid references public.profile (id) on delete cascade,
  status         public.share_status not null default 'pending',
  next_lesson_at timestamptz,
  created_at     timestamptz not null default now(),
  accepted_at    timestamptz,
  check (status = 'pending' or teacher_id is not null or status = 'revoked')
);
create unique index share_one_active_per_pair
  on public.teacher_student_share (student_id, teacher_id) where status = 'active';
create index share_teacher_idx on public.teacher_student_share (teacher_id) where status = 'active';

-- ---------------------------------------------------------------------------
-- Lessons and what the phone pipeline produces
-- ---------------------------------------------------------------------------
create table public.lesson (
  id             uuid primary key,             -- generated on the phone
  student_id     uuid not null references public.profile (id) on delete cascade,
  recorded_at    timestamptz not null,
  duration_ms    bigint,
  pipeline_state text not null default 'recorded',
  consent_at     timestamptz not null,         -- no consent, no lesson
  created_at     timestamptz not null default now()
);
create index lesson_student_idx on public.lesson (student_id, recorded_at desc);

create table public.take (
  id               uuid primary key,
  lesson_id        uuid not null references public.lesson (id) on delete cascade,
  start_ms         bigint not null,
  end_ms           bigint not null check (end_ms > start_ms),
  clip_path        text,
  pitch_contour    jsonb,
  pitch_confidence jsonb,
  song_id          uuid,                        -- FK added with the song table (M4)
  bar_start        int,
  bar_end          int,
  created_at       timestamptz not null default now()
);
create index take_lesson_idx on public.take (lesson_id, start_ms);

create table public.feedback (
  id            uuid primary key default gen_random_uuid(),
  lesson_id     uuid not null references public.lesson (id) on delete cascade,
  take_id       uuid references public.take (id) on delete set null,
  span_start_ms bigint not null,
  span_end_ms   bigint not null,
  clip_path     text,
  cue           text not null default '',
  category      text,
  her_words     text not null default '',
  practice_flag public.practice_flag,
  bar_start     int,
  bar_end       int,
  resolved_at   timestamptz,
  updated_at    timestamptz not null default now()
);
create index feedback_lesson_idx on public.feedback (lesson_id);

-- Append-only history: the AI original is never lost.
create table public.card_version (
  id          uuid primary key default gen_random_uuid(),
  feedback_id uuid not null references public.feedback (id) on delete cascade,
  author      public.card_author not null,
  author_id   uuid references public.profile (id) on delete set null,
  payload     jsonb not null,
  created_at  timestamptz not null default now()
);
create index card_version_feedback_idx on public.card_version (feedback_id, created_at);

create table public.photo_mark (
  id           uuid primary key,
  lesson_id    uuid not null references public.lesson (id) on delete cascade,
  taken_at_ms  bigint not null,               -- offset from lesson start
  feedback_id  uuid references public.feedback (id) on delete set null,
  caption      text,
  image_path   text,
  diagram_json jsonb,
  confidence   real
);
create index photo_mark_lesson_idx on public.photo_mark (lesson_id);

-- Teacher-written notes (text or a short voice note) and practice items.
create table public.comment (
  id         uuid primary key default gen_random_uuid(),
  student_id uuid not null references public.profile (id) on delete cascade,
  lesson_id  uuid references public.lesson (id) on delete cascade,
  author_id  uuid not null references public.profile (id) on delete cascade,
  body       text not null default '',
  clip_path  text,                             -- voice note
  created_at timestamptz not null default now()
);

create table public.practice_item (
  id          uuid primary key default gen_random_uuid(),
  student_id  uuid not null references public.profile (id) on delete cascade,
  lesson_id   uuid references public.lesson (id) on delete set null,
  feedback_id uuid references public.feedback (id) on delete set null,
  author_id   uuid not null references public.profile (id) on delete cascade,
  body        text not null,
  due_week    date,
  created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Access helpers (security definer so policies can call them cheaply)
-- ---------------------------------------------------------------------------
create function public.is_teacher_of(student uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.teacher_student_share s
    where s.student_id = student and s.teacher_id = auth.uid() and s.status = 'active'
  )
$$;

create function public.lesson_student(lesson uuid) returns uuid
language sql stable security definer set search_path = '' as $$
  select l.student_id from public.lesson l where l.id = lesson
$$;

create function public.feedback_student(fb uuid) returns uuid
language sql stable security definer set search_path = '' as $$
  select l.student_id from public.feedback f join public.lesson l on l.id = f.lesson_id where f.id = fb
$$;

-- Teacher accepts an invite sent to her email address.
create function public.accept_share(share uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not exists (select 1 from public.profile p where p.id = auth.uid() and p.role = 'teacher') then
    raise exception 'only a teacher can accept a share';
  end if;
  update public.teacher_student_share s
     set teacher_id = auth.uid(), status = 'active', accepted_at = now()
   where s.id = share and s.status = 'pending'
     and lower(s.invite_email) = lower(auth.jwt() ->> 'email');
  if not found then
    raise exception 'no pending invite for this account';
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- Field ownership: a teacher may change only teacher-owned columns.
-- RLS decides which rows; these triggers decide which columns.
-- ---------------------------------------------------------------------------
create function public.guard_teacher_feedback() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null then return new; end if;   -- service role / edge function
  if new.id is distinct from old.id
     or new.lesson_id is distinct from old.lesson_id
     or new.span_start_ms is distinct from old.span_start_ms
     or new.span_end_ms is distinct from old.span_end_ms
     or new.clip_path is distinct from old.clip_path then
    raise exception 'teachers may change only cue text, links, flags and bars';
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger feedback_teacher_fields before update on public.feedback
  for each row execute function public.guard_teacher_feedback();

create function public.guard_teacher_photo() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null or public.lesson_student(old.lesson_id) = auth.uid() then
    return new;                                   -- the student owns everything else
  end if;
  if (to_jsonb(new) - 'feedback_id') is distinct from (to_jsonb(old) - 'feedback_id') then
    raise exception 'teachers may only relink a photo';
  end if;
  return new;
end $$;
create trigger photo_mark_teacher_fields before update on public.photo_mark
  for each row execute function public.guard_teacher_photo();

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
alter table public.profile               enable row level security;
alter table public.teacher_student_share enable row level security;
alter table public.lesson                enable row level security;
alter table public.take                  enable row level security;
alter table public.feedback              enable row level security;
alter table public.card_version          enable row level security;
alter table public.photo_mark            enable row level security;
alter table public.comment               enable row level security;
alter table public.practice_item         enable row level security;

-- profile: see yourself and the people you share with; edit only your name.
create policy profile_select on public.profile for select to authenticated using (
  id = auth.uid()
  or public.is_teacher_of(id)
  or exists (select 1 from public.teacher_student_share s
             where s.teacher_id = profile.id and s.student_id = auth.uid() and s.status = 'active')
);
create policy profile_update on public.profile for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());
revoke update on public.profile from authenticated;
grant update (display_name) on public.profile to authenticated;

-- shares
create policy share_select on public.teacher_student_share for select to authenticated using (
  student_id = auth.uid()
  or teacher_id = auth.uid()
  or (status = 'pending' and lower(invite_email) = lower(auth.jwt() ->> 'email'))
);
create policy share_insert on public.teacher_student_share for insert to authenticated
  with check (student_id = auth.uid() and status = 'pending' and teacher_id is null);
create policy share_update on public.teacher_student_share for update to authenticated
  using (student_id = auth.uid() or teacher_id = auth.uid())
  with check (status = 'revoked' or (teacher_id = auth.uid()));
revoke update on public.teacher_student_share from authenticated;
grant update (status, next_lesson_at) on public.teacher_student_share to authenticated;
create policy share_delete on public.teacher_student_share for delete to authenticated
  using (student_id = auth.uid());

-- lesson: the student owns it; a shared teacher reads it.
create policy lesson_student_all on public.lesson for all to authenticated
  using (student_id = auth.uid()) with check (student_id = auth.uid());
create policy lesson_teacher_select on public.lesson for select to authenticated
  using (public.is_teacher_of(student_id));

-- take: the student owns it; a shared teacher reads it.
create policy take_student_all on public.take for all to authenticated
  using (public.lesson_student(lesson_id) = auth.uid())
  with check (public.lesson_student(lesson_id) = auth.uid());
create policy take_teacher_select on public.take for select to authenticated
  using (public.is_teacher_of(public.lesson_student(lesson_id)));

-- feedback: written by the edge function (service role), then edited by her.
create policy feedback_student_select on public.feedback for select to authenticated
  using (public.lesson_student(lesson_id) = auth.uid());
create policy feedback_teacher_select on public.feedback for select to authenticated
  using (public.is_teacher_of(public.lesson_student(lesson_id)));
create policy feedback_teacher_update on public.feedback for update to authenticated
  using (public.is_teacher_of(public.lesson_student(lesson_id)))
  with check (public.is_teacher_of(public.lesson_student(lesson_id)));

-- card_version: append-only. Teachers add their own versions.
create policy card_version_select on public.card_version for select to authenticated using (
  public.feedback_student(feedback_id) = auth.uid()
  or public.is_teacher_of(public.feedback_student(feedback_id))
);
create policy card_version_teacher_insert on public.card_version for insert to authenticated with check (
  author = 'teacher' and author_id = auth.uid()
  and public.is_teacher_of(public.feedback_student(feedback_id))
);
revoke update, delete on public.card_version from authenticated, anon;

-- photo_mark: the student owns it; the teacher can read and relink.
create policy photo_student_all on public.photo_mark for all to authenticated
  using (public.lesson_student(lesson_id) = auth.uid())
  with check (public.lesson_student(lesson_id) = auth.uid());
create policy photo_teacher_select on public.photo_mark for select to authenticated
  using (public.is_teacher_of(public.lesson_student(lesson_id)));
create policy photo_teacher_update on public.photo_mark for update to authenticated
  using (public.is_teacher_of(public.lesson_student(lesson_id)))
  with check (public.is_teacher_of(public.lesson_student(lesson_id)));

-- comment and practice_item: the teacher writes, the student reads.
create policy comment_select on public.comment for select to authenticated
  using (student_id = auth.uid() or public.is_teacher_of(student_id));
create policy comment_teacher_write on public.comment for all to authenticated
  using (author_id = auth.uid() and public.is_teacher_of(student_id))
  with check (author_id = auth.uid() and public.is_teacher_of(student_id));

create policy practice_select on public.practice_item for select to authenticated
  using (student_id = auth.uid() or public.is_teacher_of(student_id));
create policy practice_teacher_write on public.practice_item for all to authenticated
  using (author_id = auth.uid() and public.is_teacher_of(student_id))
  with check (author_id = auth.uid() and public.is_teacher_of(student_id));

-- Nothing is readable without signing in.
revoke all on all tables in schema public from anon;
