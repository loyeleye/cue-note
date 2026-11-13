-- A short summary of each point she made, and how much it is worth drilling.
--
-- The card writer (extract-cues) fills both. summary is one or two plain
-- sentences in the student's terms that keep her own images ("land from the
-- sky"); her_words stays her verbatim line. weight, 0..1, ranks the points
-- within an exercise: a technique she explains at length near 1, a story,
-- music theory, scheduling or a quick "yes" near 0. The phone shows the top
-- few per exercise. Neither is part of card_version: a teacher's edit is of
-- the cue and her words, and the summary is the writer's reading of them.
alter table public.feedback
  add column summary text not null default '',
  add column weight  real check (weight is null or (weight >= 0 and weight <= 1));
