-- Photos of me: a lesson photo is either a sketch of the teacher's or a photo
-- the student took of themself (say, the mouth shape the teacher asked for).
--
-- photo_mark gains:
--   kind  'sketch' (cleaned up and captioned, as before) or 'me' (kept as taken)
--   note  the student's own line on what to notice in a photo of me
--
-- A photo of me stays on the phone unless the student shares it, so a 'me'
-- row exists only while it is shared; the phone deletes the row and its file
-- when they stop sharing. It never has a cleaned copy, page corners or an AI
-- caption, and caption-sketch refuses it.

alter table public.photo_mark
  add column kind text not null default 'sketch' check (kind in ('sketch', 'me')),
  add column note text check (note is null or char_length(note) <= 200);

alter table public.photo_mark
  add constraint photo_mark_me_as_taken check (
    kind = 'sketch' or (cleaned_path is null and corners is null and caption is null and diagram_json is null)
  ),
  add constraint photo_mark_note_on_me check (note is null or kind = 'me');
