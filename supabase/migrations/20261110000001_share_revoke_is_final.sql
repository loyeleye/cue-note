-- A revoked share stays revoked.
--
-- share_update lets a teacher update her own share rows (to set
-- next_lesson_at, or to leave a student). Its check only asked that the row
-- stay hers, so after a student revoked a share the teacher could set the
-- status back to 'active' and read the lessons again.
--
-- From now on the app's users may change a share's status only to 'revoked'.
-- Turning a pending invite into an active share happens only inside
-- accept_share(), which runs as the function's owner, not as the user.
create function public.guard_share_status() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user in ('authenticated', 'anon')
     and new.status is distinct from old.status
     and new.status <> 'revoked' then
    raise exception 'a share can only be revoked; a revoked share cannot be turned back on';
  end if;
  return new;
end $$;

create trigger share_status_guard before update on public.teacher_student_share
  for each row execute function public.guard_share_status();
