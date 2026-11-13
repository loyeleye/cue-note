-- A teacher's reason for asking to hear a whole lesson is optional again.
-- The dashboard still invites one, and the student sees it when there is one.
create or replace function public.ask_full_lesson(lesson uuid, teacher uuid default null, note text default null)
returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  stu uuid := private.lesson_student(lesson);
  who text;
  t uuid;
  p public.full_lesson_pass;
begin
  if stu is null then raise exception 'lesson not found'; end if;
  if auth.uid() = stu then
    who := 'student'; t := teacher;
    if t is null or not exists (select 1 from public.teacher_student_share s
                                 where s.student_id = stu and s.teacher_id = t and s.status = 'active') then
      raise exception 'that person isn''t one of your teachers';
    end if;
    -- Only a lesson that teacher can already open; the pass adds the rest of it.
    if not exists (select 1 from public.lesson l
                    where l.id = lesson
                      and (l.taught_by = t or exists (select 1 from public.teacher_student_share s
                                                        where s.student_id = stu and s.teacher_id = t
                                                          and s.status = 'active' and not s.restricted_to_taught))) then
      raise exception 'that teacher can''t open this lesson; let them see it first';
    end if;
  else
    who := 'teacher'; t := auth.uid();
    if not private.is_teacher_of(stu) or not private.lesson_open(lesson) then
      raise exception 'lesson not found';
    end if;
  end if;

  select * into p from public.full_lesson_pass x
   where x.lesson_id = lesson and x.teacher_id = t and x.status in ('pending', 'active') for update;
  if found then
    if p.status = 'pending' and p.asked_by <> who then
      update public.full_lesson_pass
         set status = 'active', decided_at = now(), ends_at = private.pass_end()
       where id = p.id;
    end if;
    return p.id;
  end if;

  insert into public.full_lesson_pass (lesson_id, student_id, teacher_id, asked_by, note)
  values (lesson, stu, t, who, nullif(btrim(coalesce(note, '')), ''))
  returning id into p.id;
  return p.id;
end $$;
