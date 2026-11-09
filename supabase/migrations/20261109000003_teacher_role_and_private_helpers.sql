-- Two fixes found while reviewing the schema against Supabase's guidance.
--
-- 1. The teacher role no longer comes from sign-up metadata. Anyone can put
--    {"role": "teacher"} in their own sign-up data, so it proves nothing.
--    Everyone now starts as a student. An account becomes a teacher only by
--    accepting a student's invite sent to its verified email (accept_share).
--
-- 2. The security definer helpers that RLS policies use move out of the
--    public schema. Supabase exposes every function in public as an API
--    endpoint, so a signed-in user could call lesson_student() with any lesson
--    id and learn whose it is. The private schema is not exposed. Policies
--    point at functions by id, not by name, so they keep working after the move.

-- ---------------------------------------------------------------- 1. Role
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profile (id, role, display_name)
  values (new.id, 'student', coalesce(new.raw_user_meta_data ->> 'display_name', ''));
  return new;
end $$;

-- Accepting an invite is the trusted path to the teacher role: a student
-- chose this person, and the email match proves the account owns the inbox.
create or replace function public.accept_share(share uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  update public.teacher_student_share s
     set teacher_id = auth.uid(), status = 'active', accepted_at = now()
   where s.id = share and s.status = 'pending'
     and s.student_id <> auth.uid()
     and lower(s.invite_email) = lower(auth.jwt() ->> 'email');
  if not found then
    raise exception 'no pending invite for this account';
  end if;
  update public.profile p set role = 'teacher' where p.id = auth.uid() and p.role <> 'teacher';
end $$;

revoke execute on function public.accept_share(uuid) from public, anon;
grant execute on function public.accept_share(uuid) to authenticated;
revoke execute on function public.handle_new_user() from public, anon, authenticated;

-- ---------------------------------------------------------------- 2. Helpers
create schema if not exists private;
revoke all on schema private from public;
-- Policies run as the signed-in user, so that role needs to reach the helpers.
grant usage on schema private to authenticated;

alter function public.is_teacher_of(uuid)    set schema private;
alter function public.lesson_student(uuid)   set schema private;
alter function public.feedback_student(uuid) set schema private;

revoke execute on all functions in schema private from public, anon;
grant execute on all functions in schema private to authenticated;

-- This trigger names lesson_student in its body, so it needs the new name.
create or replace function public.guard_teacher_photo() returns trigger
language plpgsql set search_path = '' as $$
begin
  if auth.uid() is null or private.lesson_student(old.lesson_id) = auth.uid() then
    return new;                                   -- the student owns everything else
  end if;
  if (to_jsonb(new) - 'feedback_id') is distinct from (to_jsonb(old) - 'feedback_id') then
    raise exception 'teachers may only relink a photo';
  end if;
  return new;
end $$;
