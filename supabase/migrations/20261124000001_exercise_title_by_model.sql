-- Exercise titles: remember which ones the model gave, so making the notes
-- again can replace a wrong guess (a song the teacher only mentioned) while a
-- name a person gives stays. Every title so far came from the model.
alter table public.exercise
  add column title_by_model boolean not null default false;

update public.exercise set title_by_model = true where title is not null;
