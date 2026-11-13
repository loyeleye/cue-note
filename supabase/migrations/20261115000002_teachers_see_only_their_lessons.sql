-- A teacher sees only the lessons they taught, unless the student lets them
-- see everything. This flips restricted_to_taught from opt-in to the default:
-- new shares start limited, and existing ones are limited now. The student
-- turns on "Can see all my lessons" for a teacher on the phone to lift it.
-- The rules that enforce it are unchanged (20261110130000_lessons_by_teacher).
alter table public.teacher_student_share alter column restricted_to_taught set default true;
update public.teacher_student_share set restricted_to_taught = true where not restricted_to_taught;
