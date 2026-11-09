-- Private buckets. Paths are <student_id>/<lesson_id>/<file>.
-- Teacher voice notes go to clips/<student_id>/teacher-notes/<file>.
insert into storage.buckets (id, name, public) values
  ('clips', 'clips', false),
  ('photos', 'photos', false)
on conflict (id) do nothing;

create policy storage_student_all on storage.objects for all to authenticated
  using (bucket_id in ('clips', 'photos') and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id in ('clips', 'photos') and (storage.foldername(name))[1] = auth.uid()::text);

create policy storage_teacher_read on storage.objects for select to authenticated
  using (bucket_id in ('clips', 'photos')
         and public.is_teacher_of(((storage.foldername(name))[1])::uuid));

create policy storage_teacher_voice_note on storage.objects for insert to authenticated
  with check (bucket_id = 'clips'
              and (storage.foldername(name))[2] = 'teacher-notes'
              and public.is_teacher_of(((storage.foldername(name))[1])::uuid));
