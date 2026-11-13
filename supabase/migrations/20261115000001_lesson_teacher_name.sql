-- A lesson taught by someone who isn't on Cue Note: a guest teacher, or one
-- the student hasn't invited yet. The student types their name on the phone
-- ("Someone else" under "Who's teaching today?", or later from the lesson).
-- It's only a label: it grants nothing, and is set only when taught_by isn't.
alter table public.lesson
  add column teacher_name text
  check (teacher_name is null or (char_length(teacher_name) between 1 and 80 and btrim(teacher_name) <> ''));

-- claim_lesson: a teacher on Cue Note marking a lesson as theirs replaces any
-- typed name (it was probably them, before they joined). Otherwise unchanged.
create or replace function public.claim_lesson(lesson uuid, mine boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare
  stu uuid;
  cur uuid;
begin
  select l.student_id, l.taught_by into stu, cur from public.lesson l where l.id = claim_lesson.lesson for update;
  if not found or not private.is_teacher_of(stu) or not private.lesson_open(claim_lesson.lesson) then
    raise exception 'lesson not found';
  end if;
  if mine then
    if cur is not null and cur <> auth.uid() then
      raise exception 'another teacher is already marked on this lesson';
    end if;
    update public.lesson set taught_by = auth.uid(), teacher_name = null where id = claim_lesson.lesson;
  elsif cur = auth.uid() then
    -- A restricted teacher would lose the lesson by un-marking it; that's
    -- their choice, so it is allowed.
    update public.lesson set taught_by = null where id = claim_lesson.lesson;
  end if;
end $$;
