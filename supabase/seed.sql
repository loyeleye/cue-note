-- Sample data for local development (`supabase db reset` loads this file).
-- Never applied to the hosted project. Sign in to the local dashboard as
-- teacher@example.com (the email lands in Inbucket, http://localhost:54324)
-- and accept Sam's invite to see his lesson.
-- The web dashboard also has a demo mode with richer sample data that needs
-- no database at all: "Try the demo" on its sign-in page.

insert into auth.users (id, email, raw_user_meta_data) values
  ('5a000000-0000-4000-8000-000000000001', 'sam@example.com', '{"display_name":"Sam Rivera"}');

insert into public.teacher_student_share (student_id, invite_email)
values ('5a000000-0000-4000-8000-000000000001', 'teacher@example.com');

insert into public.lesson (id, student_id, recorded_at, duration_ms, pipeline_state, consent_at) values
  ('5a000000-0000-4000-8000-0000000000a1', '5a000000-0000-4000-8000-000000000001',
   now() - interval '4 days', 3120000, 'cards_ready', now() - interval '4 days');

insert into public.song (id, student_id, title, composer) values
  ('5a000000-0000-4000-8000-0000000000c1', '5a000000-0000-4000-8000-000000000001', 'Caro mio ben', 'Tommaso Giordani');

insert into public.take (id, lesson_id, start_ms, end_ms, song_id, bar_start, bar_end, pitch_contour, pitch_confidence) values
  ('5a000000-0000-4000-8000-0000000000b1', '5a000000-0000-4000-8000-0000000000a1', 130000, 134400, null, null, null,
   '{"hop_ms":400,"hz":[293.7,329.6,370,392,440,392,370,329.6,293.7,293.7,293.7]}', '[0.9,0.9,0.9,0.9,0.9,0.9,0.9,0.9,0.9,0.9,0.9]'),
  ('5a000000-0000-4000-8000-0000000000b2', '5a000000-0000-4000-8000-0000000000a1', 400000, 404400,
   '5a000000-0000-4000-8000-0000000000c1', 1, 4,
   '{"hop_ms":400,"hz":[370,370,329.6,293.7,293.7,null,440,440,392,370,370]}', '[0.9,0.9,0.9,0.4,0.3,0,0.9,0.9,0.9,0.9,0.9]');

insert into public.feedback (id, lesson_id, take_id, span_start_ms, span_end_ms, cue, category, her_words, practice_flag) values
  ('5a000000-0000-4000-8000-0000000000d1', '5a000000-0000-4000-8000-0000000000a1', '5a000000-0000-4000-8000-0000000000b1',
   150000, 162000, 'Keep the hum buzzing forward', 'resonance',
   'Feel it buzz on your lips, right behind your nose.', 'practice_this'),
  ('5a000000-0000-4000-8000-0000000000d2', '5a000000-0000-4000-8000-0000000000a1', '5a000000-0000-4000-8000-0000000000b2',
   428000, 440000, 'Breathe low before the first phrase', 'breath',
   'Don''t lift the shoulders. Let the belly drop out, then sing.', 'practice_this');

insert into public.card_version (feedback_id, author, payload)
select id, 'ai', jsonb_build_object('cue', cue, 'category', category, 'her_words', her_words,
                                    'practice_flag', practice_flag, 'take_id', take_id)
  from public.feedback where lesson_id = '5a000000-0000-4000-8000-0000000000a1';
