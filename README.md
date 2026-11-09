# Cue Note

A vocal-coaching app for a teacher and a student. The student's Android phone
records each in-person lesson on two microphones and turns it into a timeline of
cards; the teacher corrects those cards and sets practice from a web dashboard.

The full design is in the project's design doc. This repository is built in
milestones; **M1 (foundations and recorder)**, **M2 (the on-phone pipeline)** and
**M4 (teacher dashboard)** are here so far.

## Layout

| Folder      | What it is |
|-------------|------------|
| `android/`  | The student app (Kotlin, Jetpack Compose). |
| `web/`      | The teacher dashboard (SvelteKit, static, for Cloudflare Pages). |
| `supabase/` | The backend: database schema, security rules, storage buckets. |

### Android modules

| Module            | Owns |
|-------------------|------|
| `:core:signal`    | Pure Kotlin, no Android imports: WAV chunk writer, level meters, setup-check rules, gap detector. Runs in plain JVM tests. |
| `:core:audio`     | USB mic selection and the foreground recording service (48 kHz stereo, 30 s chunks, screen off). |
| `:core:ml`        | Pure Kotlin wrappers for the models, behind two interfaces: voice detection (Silero) and speech to text (whisper.cpp), plus the model downloader. The C++ bridge lives in `src/main/cpp`. |
| `:core:ml-android`| Builds that C++ (whisper.cpp v1.8.7 and its Silero) for the phone with the NDK. |
| `:core:pipeline`  | Pure Kotlin: the after-lesson pipeline (pitch, five-label segmenter, takes and feedback, transcription, linker), the debug timeline page, the golden-set scorer, synthetic lessons and a laptop runner. |
| `:core:data`      | Room database of lessons and pipeline results, consent, magic-link sign-in (supabase-kt), the background processing job. |
| `:feature:record` | Home, consent, setup check, recording and sign-in screens. |
| `:feature:lesson` | A lesson's processing status, its takes with their feedback, and the debug timeline. |
| `:app`            | Wires it together; navigation; deep link `cuenote://auth-callback`. |

## Recording setup

Hollyland Lark M2S receiver in the phone's USB-C port, in **stereo** mode:
**left = student, right = teacher**. The teacher clips her transmitter on the side
of her collar away from the piano, with noise cancellation off. The phone sits in
the ULANZI mount; with the receiver in the only USB-C port, power comes from the
battery or the Qi2 charger.

Run **Setup check** in the app before the first lesson and whenever the setup
changes. It confirms each voice lands on its own channel, that a loud note does
not clip, and that the student's soft singing stays above the piano.

Each lesson is saved on the phone as `lessons/<id>/audio/chunk_00000.wav, …`,
`events.tsv` (marks, photos, gaps, mic changes) and `summary.tsv`. **Export to
Downloads** copies a lesson to `Downloads/CueNote/<id>/` for moving to a laptop.

## Running things

**Android.** Open `android/` in Android Studio, or run `./gradlew assembleDebug`.
CI builds a debug APK on every pull request (the *cue-note-debug-apk* artifact on
the run's page) that can be installed on the phone directly. To enable sign-in,
add to `android/local.properties`:

```
cuenote.supabaseUrl=https://<project>.supabase.co
cuenote.supabaseAnonKey=<anon key>
```

**Web.** `cd web && cp .env.example .env && npm install && npm run dev`.

**Database tests.** With any Postgres 16 you can reach:
`PGHOST=localhost PGUSER=postgres PGPASSWORD=… supabase/tests/run.sh`.
They apply the migrations to a throwaway database and check, user by user, what
each person can and cannot read or change.

## Processing a lesson (M2)

After a lesson, the phone works out what happened, all on the phone:

1. **Listen.** Every 32 ms, for each mic: how loud it is, the pitch and how
   sure that pitch is (YIN), and how likely it is a voice (Silero).
2. **Label.** Each moment becomes one of: student singing, student speaking,
   teacher speaking, teacher singing, piano only. Whose voice it is comes from
   which mic hears it louder. Singing holds notes or runs on legato; speech
   slides and stops. A note held dead steady, or fading like a struck string,
   is the piano.
3. **Group.** Student singing becomes *takes* (a breath or a short piano bit
   stays inside one take). The teacher talking and demonstrating between takes
   becomes a *feedback block*, kept together across a student question.
4. **Transcribe.** whisper.cpp writes down only the talking, each person from
   their own mic, so the transcript reads as a dialogue.
5. **Link.** Each feedback block belongs to the most recent take before it,
   skipping any student question in between. Each sung demo is matched by
   melody to the phrase it models. Photos and marks join cards by time.
6. **Timeline.** `pipeline/timeline.html` in the lesson folder shows all of
   it, lane by lane, and plays the audio.

It starts by itself the next time the phone charges after a lesson (which also
means the mic receiver is unplugged), or from **Process now** on the lesson's
screen. The first run downloads the speech models once (about 150 MB, on
Wi-Fi). Every stage saves its result, so a run that Android stops picks up
where it left off. The results go into Room; uploading them is M3.

### On a laptop

Export a lesson with **Export to Downloads** and copy `Downloads/CueNote/<id>/`
over. Then, from `android/`:

```
core/ml/host-build.sh                          # once: builds whisper.cpp for the laptop
mkdir -p ~/cuenote-models && cd ~/cuenote-models
curl -LO https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin
cd - && export CUENOTE_MODELS=~/cuenote-models

./gradlew :core:pipeline:run --args="process ~/lessons/<id>"      # writes <id>/pipeline/timeline.html
./gradlew :core:pipeline:run --args="process ~/lessons/<id> --again"  # start over (after changing thresholds)
```

Thresholds live in `PipelineConfig`; a lesson's `pipeline/config.json` can
override them while tuning.

### The golden set

1. Open a processed lesson's timeline, press **Label right answers**, then
   **Start from found** and fix what is wrong: drag on the *Right answers* row
   to mark takes (T), feedback (F) and her demos (D); click a feedback block
   then a take to link them. **Save labels.json** into the lesson folder.
2. Put labelled lessons in one folder and run
   `CUENOTE_GOLDEN=~/golden ./gradlew :core:pipeline:test`. The report is in
   `core/pipeline/build/golden-report/real.md`: take detection, link accuracy
   (the M2 gate number), demo detection, and every mistake with its time.
3. Once the threshold is agreed with the teacher, write it to
   `~/golden/thresholds.json` (for example `{"linkAccuracy": 0.85}`) and the
   test fails below it.

Real lessons are too big for git (an hour is about 700 MB), so the golden set
lives outside the repository. CI runs three synthetic lessons on every change
instead; the report is on each run's summary page.

## M2 gate

Link accuracy on the labelled real lessons meets the threshold agreed with the
teacher.

## M1 gate

1. The setup check passes on the phone: each voice on its own channel, and the
   student's voice clearly above the piano on the left channel.
2. A 60-minute lesson recorded with the screen off shows **No gaps** in the
   lesson list.
