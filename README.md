# Cue Note

A vocal-coaching app for a teacher and a student. The student's Android phone
records each in-person lesson on two microphones and turns it into a timeline of
cards; the teacher corrects those cards and sets practice from a web dashboard.

The full design is in the project's design doc. This repository is built in
milestones; **M1 (foundations and recorder)**, **M2 (the on-phone pipeline)**,
**M3 (cards)**, **M4 (teacher dashboard)**, **M6 (sketches)**, the posture part of **M5**
and the start of M5's practice between lessons are here so far.

### Several teachers on one student

A student can share lessons with more than one teacher. Each teacher has an
alias and a color (Settings, "How you appear"); other teachers see the alias,
and the real name only if that teacher makes it public. Students always see
both, for example "Tiger (Marigold Stewart)". Cards keep a Google Docs style
history: who saved each version, named versions of the whole lesson, compare
and restore. Teachers with the same lesson open see each other and each
other's saves live; if two change the same field, the second is asked which
to keep. The local seed invites `teacher2@example.com` too, so two dashboards
can try this side by side.

Before recording, the phone asks "Who's teaching today?": one of the
student's teachers on Cue Note, or "Someone else" with a typed name for a
teacher who isn't on Cue Note (a guest, say; it's only a label and grants no
access). With one teacher they're picked to start with; with none, the name
from the first lesson is. The student can change who taught an older lesson
from the lesson itself ("Taught by … Change"), and a teacher can mark an older
lesson as theirs from the dashboard. A teacher can switch on "Only my lessons" for a student
on the dashboard to hide the others' lessons, notes and practice. A teacher sees only the lessons marked as
theirs unless the student turns on "Can see all my lessons" for them under
"Your teachers" on the phone; the database enforces it. Even then, a lesson
another teacher on Cue Note taught shows only once that teacher agrees: they
get an email (the `consent-email` function) and an Allow / Decline request at
the top of their dashboard, and can take a yes back later. Lessons with no
teacher set, or only a typed name, need no one's agreement.

**Email for those requests.** Set three secrets, or the requests only show on
the dashboard: `supabase secrets set RESEND_API_KEY=… CONSENT_EMAIL_FROM="Cue Note <notifications@cuenote.cc>"
DASHBOARD_URL=https://dashboard.cuenote.cc --project-ref tgytbytwofiymsvajdkd`. Optionally set `ALERT_EMAIL` to your own address to get an email whenever Resend refuses one.

**Hosting the dashboard.** It's a static site on Cloudflare (Workers, set up by `web/wrangler.jsonc`), connected to
this repo: root directory `web`, build command `npm run build`, deploy command `npx wrangler deploy`,
env vars `PUBLIC_SUPABASE_URL` and `PUBLIC_SUPABASE_ANON_KEY`, custom domain
`dashboard.cuenote.cc`. Each push to main redeploys it. In Supabase Auth's URL
settings, `https://dashboard.cuenote.cc/**` must be an allowed redirect, or
sign-in links go to localhost.

**Debug role (remove before release).** A debug account sees every student and
lesson in the normal teacher dashboard, read-only. Make one by running this in
the hosted project's SQL editor (sign in to the dashboard once first, so the
account exists):

```sql
insert into private.debug_user (user_id)
select id from auth.users where email = 'you@example.com';
```

`delete from private.debug_user;` turns it off. Dropping the role for good is a
migration that drops `private.debug_user`, `private.is_debug()`, `public.am_debug()`
and the `*_debug_read` policies. The phone sends who taught each lesson when it uploads it.

A soundcheck ends by showing which mic it heard as the student's and which as
the teacher's, with how sure it is (from how far apart the two voices were).
A lesson started within two hours uses that answer instead of asking "Who's on
which mic?" again; the consent screen shows it with a "Change" button. When the
soundcheck wasn't sure, the question still follows, already filled in.

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
| `:core:posture`   | Posture tracking: the camera service (its own process, so it can never stop the audio) running MediaPipe Pose, and the camera frames the framing check shares. The rules (framing, flags, singing, back-off) are pure Kotlin in `:core:signal`. |
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
not clip, and that the student's soft singing stays above the piano. A voice can read
each step aloud, so nobody has to watch the screen; each step listens for up to
12 to 15 seconds and moves on as soon as it has heard enough.

Each lesson is saved on the phone as `lessons/<id>/audio/chunk_00000.wav, …`,
`events.tsv` (marks, photos, gaps, mic changes) and `summary.tsv`. **Export to
Downloads** copies a lesson to `Downloads/CueNote/<id>/` for moving to a laptop.

### Posture (optional, per lesson)

Ticking **Track posture this lesson** on the consent screen (off by default)
adds a camera step after "Who's on which mic?":

1. **Framing check.** Put the phone where it stays for the lesson and tap
   *Start the check*. The student walks to their singing spot during a
   five-second countdown, and the phone talks them into place ("step a little
   to your left", "come a step closer"). After two seconds in frame it says
   "You're all set" and starts recording. How the student stands then is the
   baseline for the flags. After 30 seconds it gives up and offers *Try again*
   or *Record without posture*.
2. **During the lesson.** A camera service runs MediaPipe Pose on the phone at
   about 5 frames a second, only while the student's mic shows singing (it
   switches off after 8 seconds of quiet). It tracks only the student, even
   if the teacher is in view. The screen stays on, dimmed right down.
3. **Flags.** *Chin forward* (jutting out or lifting), *shoulders raised* and
   *head tilt*, each compared with the baseline and only after holding for
   about 1.5 seconds. Each flag saves one still snapshot.
   The framing check offers **Front** or **Side**. Side-on, the phone
   measures from the near ear, shoulder and hip: *chin forward* is the ear
   moving ahead of the shoulder (the most reliable view for it), *shoulders
   raised* the ear-to-shoulder height, and *chin lifted or dropped* the head
   tipping up or down in place of head tilt. The side-on prompts say
   forward, backward, nearer and farther instead of left and right.
4. **When things move.** Moments when the student is turned away from the
   phone aren't judged. If the phone is picked up or moved (the motion sensor
   notices), posture pauses, and once it's still again it quietly takes a
   fresh baseline from the student's first three seconds back in view. It
   does the same after an in-app photo. Which way up the phone is comes from
   gravity, so a sideways mount works even with auto-rotate off.
   **Reset posture** on the recording screen is for a lesson that's
   about posture: a spoken framing check like the first one, whose result
   becomes the new baseline.
5. **Audio always wins.** Posture pauses when the phone gets warm (Android's
   thermal status "moderate" or worse, until it has cooled for a minute) and
   for 3 minutes after any audio gap; after two gaps it stays off for the
   lesson. It runs in its own process, so a camera crash can't stop the
   recording.

**The voice.** Every spoken line is a recorded clip in two voices, Female (Gemini
voice Erinome, the default) and Male (Iapetus), in `android/app/src/main/assets/voice/<voice>/`,
listed with its words in `VoiceLine.kt`. The choice is on the sound check and the
framing check, and applies everywhere. A line without a clip is read by the phone's
best installed US English voice. To record new lines, add them to `VoiceLine.kt` and
run `tools/voice/say.py` once per voice (it checks each take says exactly its words).
A new line waits for the one playing to finish.

Files, in `lessons/<id>/posture/`: `pose.tsv` (the student's 33 body points
per frame), `events.tsv` (camera on and off, pauses, flags) and
`flag_<seconds>_<kind>.jpg`. No video is ever saved, and none of it is
uploaded. The lesson screen shows minutes watched, pauses and each flag with
its snapshot.

**Before using posture in a real lesson:** record a 60-minute test lesson
with posture on (phone on the wireless charger, screen dimmed), sing on and
off through it, and check the lesson list says **No gaps**.

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
where it left off. The results go into Room, and then the phone makes the
lesson's cards (M3, below).

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

## Cards (M3)

Once a lesson is processed and there is a network, the phone turns it into
cards by itself (or from **Make cards** on the lesson's screen):

1. **Clips.** Each take and each feedback block is cut into a small replay
   clip: both mics mixed to one track, AAC at 64 kbps in an `.m4a`, which
   plays on the phone and in any browser, Safari included. About 0.5 MB a
   minute. The full recording never leaves the phone.
2. **Upload.** The lesson, its takes (with their pitch lines) and the clips go
   to the student's own folder in Supabase. Takes and blocks get cloud ids
   derived from the phone's own ids (`CloudIds` in `:core:pipeline`), so
   uploading twice updates the same rows.
3. **Cards.** The phone sends each feedback block's words and the lesson's
   transcript to the `extract-cues` edge function. It asks Claude, with text
   only, for one card per block: her cue as a short instruction, her exact
   words, a category, "practice this" or "just listen", and bars when she
   named them. It checks the answer (her words must really be hers; a quote
   that isn't is replaced with her own sentence) and writes the cards, with
   the AI's version kept in the card history. Cards she has already edited
   keep her version. Her past corrections go into the request as examples,
   so the cards get closer to how she talks. Cues are at most 8 words, her
   words one sentence. Each exercise goes to the model in one call where it
   fits, and when she returns to a point the model reuses the cue word for
   word. It also names each exercise ("Lip trill on a 5-note scale"), which is
   written to `exercise.title` unless a person named it (`title_by_model`
   marks the model's own names, so making the notes again can fix one). The
   model sees what the student sang on each take, so a song is named from its
   lyrics; a title the teacher only mentions is not taken as the song sung.

It also writes a one- or two-sentence `summary` of each point, in plain
words that keep her images and add nothing she didn't say, and a `weight`
(0–1) for how much it is worth drilling: technique she explains at length
high, stories, music theory, key choice and a quick "yes" low.

The lesson screen then reads exercise by exercise (`LessonOutline` in
`:core:data`): each warmup or song section by name, with a button per try
(★ on a try she praised), then the three highest-weighted points in lesson
order, and the rest behind "More notes". Cards with the same cue show once,
with "She said this 3 times". A point is the cue, the summary, and one row of
actions: **Her words** (with its length), **Hear try N**, the try she liked
when it's another one, the setlist, and "Everything she said". Before the
notes are made, each exercise shows the phone's own best guess: the longest
blocks clearly about a try that sound like technique rather than theory or a
story, cut to the sentences of hers that carry it. Talk that isn't about the
singing is folded away under "Chat between songs". Before the notes are made
the screen shows the exercises and their tries only; how each block was
linked to a take is under Details. Opening a lesson fetches her latest edits
and the exercises' names from the dashboard.

A bar at the bottom of the lesson screen keeps the next step in reach:
**Start practicing** opens the first point from this lesson on the setlist,
and **Before next lesson** opens the next-lesson page, where takes and questions
go to your teacher with the full clips.

On the dashboard each card shows the summary the student sees and asks the
teacher **Did you say this?** (`check_summary`). Yes marks it as hers; no
takes the summary off the student's card, so the phone shows her own words
instead, and keeps it in `summary_rejected` for the tally. A summary the
teacher writes in the card editor counts as one she said. Making the notes
again leaves an answered or teacher-written summary alone. The lesson page
and the home page show how many summaries have been checked
(`summary_check_tally`), which tells us how far to trust them.

The answer is a three-stop slider, **Yes / Close / Wrong**
(`answer_summary`). Close keeps the summary, marked close; Wrong takes it off
(sliding back restores it). For Close or Wrong the teacher can write what she
meant, at any length, and/or record a voice note; the phone shows both under
"What your teacher meant". "Mark the other N Yes" finishes a lesson in one
pass. On the phone a point she flagged without a correction leads with her
clip and the try, says "Your teacher flagged the app's summary of this", keeps
the old summary a tap away (so a student who practiced from it knows to stop),
and offers "Add to my questions" or "Got it". The lesson says once how many
summaries she corrected; a summary she confirmed says so. Room 10 to 11 adds
`card.summaryCheck` and `card.summaryRejected`.

"Make my notes" replaces any earlier try still waiting to retry, and sketch
uploads have their own chain (`photos-<id>`), started again as soon as the
cards are in the cloud. They used to share the cards' chain, where a sketch
waiting for the cards could hold "Make my notes" back for up to a day. If
making the notes fails, the lesson screen says why, including while the phone
waits to try again. The card writer answers within the phone's 150 s limit:
calls of at most 8 blocks (`CUE_BATCH`) run side by side, each with a 110 s
timeout and no retry on the server.

Each run is logged in `cue_extraction` with its tokens and cost, to check the
design doc's estimate of $0.03 a lesson.

**Picking the model.** On a lesson that has cards, **Details → Compare AI
models** runs it through Haiku 5.5, Sonnet 5.5, Opus 5.5 and Fable 5.2 and
shows each one's card for every block, with what each run cost. The lesson's
own cards don't change. The model the app uses is the `CUE_MODEL` secret.

**Setting up the function.** It deploys with the others on merge. It needs the
Claude API key as a secret, once:

```
supabase secrets set ANTHROPIC_API_KEY=sk-ant-… --project-ref tgytbytwofiymsvajdkd
# optional: CUE_MODEL (default claude-opus-5-5), CUE_EFFORT (default low)
```

Without the key, the phone's lesson screen says "cue extraction is not set
up yet" and tries again later.

**Tests.** `node --experimental-strip-types --test supabase/functions/extract-cues/cues.test.ts`
checks the prompt, the answer schema and the checks on the answer, on a
sample lesson in `extract-cues/fixtures/`. `test/index.test.ts` runs the
whole function against a fake database and model (the command is in CI).

**Card-updated notifications.** When a teacher saves a card, the database
queues a push and wakes the `notify` edge function, which sends it through
Firebase Cloud Messaging. The phone registers its Firebase token in
`device_token` once signed in (and removes it on sign-out), asks for
Android 13's notification permission, and a tap opens that lesson, which
fetches her edit. `android/app/google-services.json` is the Firebase app's
config (not secret). The function needs the service account once:
`supabase secrets set FCM_SERVICE_ACCOUNT="$(cat key.json)" --project-ref tgytbytwofiymsvajdkd`.

## M3 gate

She reads one lesson's cards and agrees they say what she said.

## Practice between lessons (M5)

**Practice a note.** Every note on the setlist has a **Practice** button. The
practice screen plays the teacher's words and the lesson take again, then
**Sing a practice take** records one: 48 kHz mono from the phone's own mic,
or from the student's transmitter when the lesson mic receiver is plugged in
(the left channel, or the right if today's transmitters were swapped). Takes
are kept as `practice/<take id>.wav` in the app's storage and listed under the
note, newest first; nothing is uploaded. Takes within 20 minutes of each
other make one practice session (`practice_session`), which the practice log
will build on. Deleting a lesson deletes its notes' practice takes too.

**Headphones.** The screen says what the phone is playing through: wired or
USB-C headphones (best), Bluetooth (fine on your own, usually too slow to
sing along with) or the speaker (fine on your own; to sing along, the
backing would leak into the take).

**Timing check.** With headphones in, **Check timing** plays ten clicks; the
student listens to two, then sings a short "ta" on each of the rest. Because
they sing to what they hear, the gap between each click and its "ta" is the
whole round trip a sing-along take has: the headphones' delay plus the mic's.
`LatencyCheck` in `:core:signal` finds each "ta" (a rise out of the room's
level, 150 ms early to 450 ms late), takes the median, and asks for another
go when fewer than five land or the middle half spread over 45 ms. The
result is saved per pair of headphones and stored with each take, for lining
sing-along takes up with backing tracks. Over 250 ms counts as too slow to
sing along with comfortably.

**Music from the lesson.** The practice screen cuts tracks from the
teacher's channel of the lesson recording (right = teacher), once, into
`lessons/<id>/practice/<block>/`: **The piano** (the teacher's channel while
the student sang the take the note is about, with a 1.5 s lead-in; only when
the pipeline heard the piano under that take) and **Their demo** (each sung
demo in the feedback block). **Starting note** plays the first steady note
of the lesson take as a soft tone. **Slower** plays at 75% with the pitch
unchanged (WSOLA time-stretch, `TimeStretch` in `:core:pipeline`), and
**Loop** repeats a track. With headphones in, **Sing along with the piano**
records a take while the piano plays, and stops a second after it ends;
**Sing on your own** records without it. Notes the phone didn't make, or
whose lesson audio is gone, have no tracks. The student's voice leaking into
the teacher's mic stays in the piano track for now; how much to duck it is
for tuning on real lessons.

**Pitch after each take.** Once a take is saved, the phone tracks its pitch
(YIN every 32 ms, as in the lesson pipeline), splits it into notes, and lines
those notes up in order with the notes of the teacher's first sung demo,
moved by whole octaves to the student's range. Each note is *in tune*, *a
little flat* or *a little sharp* (more than 35 cents off), or *unclear* when
the pitch confidence is too low to judge. The take row shows the notes as
bars over the demo's dashed notes, with a small arrow on the ones that are
off, and one friendly line ("Mostly with their demo. One note ran a little
flat."). No numbers or percentages are shown. Results are kept as
`<take>.pitch.json`. The thresholds (`PitchCompare`, `PitchTrack` in
`:core:pipeline`) are starting points to tune on real lessons.

**Before the next lesson.** Practice stays on the phone unless the student
shares something. Once a teacher has accepted their invite, a take's ⋮ menu
has **Share with Marigold**: the take goes up as a small .m4a in the lesson's
clip folder (`clips/<student>/<lesson>/practice-<take>.m4a`) with its pitch
per note and any note on how it felt. Up to three shared takes wait at a
time; one frees up once the teacher has listened. **Ask** on the practice
screen pins a question to the note (and a bar, if the student knows it), and
the **Next lesson** screen (from Home) takes general ones, shows the week's
practice, what's shared, the questions with their answers, and the lesson
date the teacher set. The log summary goes up too: each sitting's start, end,
minutes and which notes, no audio. `PracticeSyncWorker` sends it all when
there's a network and brings answers and "listened" back. In the database,
`practice_session`, `practice_take` and `student_question` belong to the
student; teachers with an active share read them, mark takes heard and
answer questions, and a teacher limited to their own lessons sees only the
takes and questions from those lessons
(`supabase/migrations/20261114000001_m5_practice.sql`). On the dashboard,
each student has a **Next lesson** tab: the practice since the last lesson,
the shared takes to hear (with how it felt and one line on pitch, no
numbers), and the questions to answer in words or mark as covered in the
lesson.

**Suggested practice.** At the top of the setlist (and on Home), **Today's
practice** asks how the voice feels (Fine / A bit tired / Hoarse or sore,
nothing picked for the student) and how long they have (10 or 20 minutes; 30
once a teacher allows it). The phone then puts the teacher's own "practice
this" notes in an order for today (`PracticePlan` in `:core:pipeline`, tested
on the JVM): a gentle start, her latest warmup, one to three notes, the song
section they belong to, and a cool-down. The gentle start and cool-down are
never cut. Notes are ranked by her pin first, then how worth drilling the card
writer judged them (only once she said Yes or Close, or wrote the summary;
an unchecked weight counts zero), how often she made the point, and spacing
(a note not sung for a few days rises, one sung today drops). The app never
adds a note. Its reason shows small, as "App's pick: not sung since
Tuesday". A tired voice gets about 12 minutes in the middle of the range: the
warmup's lower half, no song, and notes about high or loud singing turned into
listening. A hoarse or sore voice gets listening only, with a "don't whisper
along" and when to see a doctor. Each step runs on the practice screen with a
step bar, Skip and Next, and the screen stays on. "Still feel easy?" comes
once, between steps about halfway; "A bit tired" goes to the cool-down, never
to the song. The end says what was done, never what was missed. Each sitting
is kept in `practice_plan` (Room 12 to 13), so it can be picked up within the
hour. The concept, mockups and panel reviews are in the project files under
`suggested-practice/`.

**Posture during practice.** **Watch my posture** on the practice screen
uses the lesson's posture tracking (`PostureService`, MediaPipe on the
phone). Switching it on runs the framing check from the student's singing
spot once per sitting; after that the camera is on only while a take
records, no video is saved, and each flagged moment keeps a snapshot in
`practice/<take id>/posture/`. Under the take, one line says how it went
("Posture: steady all the way through", or what was flagged), with the
snapshots. None of it leaves the phone.

## Sketches (M6)

**Taking them.** The lesson screen's **Photo** button opens a camera inside the
app; the recording carries on underneath. One tap on the big shutter saves
`photos/photo_<ms>.jpg` in the lesson folder, stamped with the moment of the
tap, and the screen goes back to the lesson.

**Which card.** After processing, each photo goes on the feedback it was taken
during, or that ended up to 90 s before it (`PipelineConfig.photoAfterMs`).
The student can move it on the phone (open a sketch, **Move to another card**)
and the teacher on the dashboard (drag it onto a card, or its **Move to** list).
A move by hand is never undone by reprocessing, and the phone's first guess is
kept (`photo_mark.auto_feedback_id`), so we can count how often it was right.

**Clean-up.** `:core:sketch` (pure Kotlin, tested on the JVM) finds the sheet as
the biggest bright patch, takes its four corners, straightens it with a
perspective transform, and divides out the paper's own brightness so shadows
go and the paper turns white while pen colors stay. The original is kept. When
it gets the edges wrong, **Fix the corners** lets the student drag them.

**Upload and caption.** Once the lesson's cards are in the cloud, the phone
uploads `photos/<student>/<lesson>/<photo id>.jpg` and `.clean.jpg` and calls
the `caption-sketch` edge function with the words said around the photo
(60 s before to 20 s after). It asks Claude for a one-line caption in her
terms and how well the sketch matches what she said; it never adds advice.
It needs the `ANTHROPIC_API_KEY` secret (and optionally `CUE_MODEL`).

## M6 gate

Most photos land on the right card without a manual fix: compare
`photo_mark.auto_feedback_id` with `feedback_id` over real lessons.

## Suggested diagrams

A card can carry one "Does this diagram help?": a standard drawing from the
app's own library (26 so far, in `web/static/diagrams/`) that matches what the
teacher explained. It is labeled as a suggestion and shows beside her sketch,
never in place of it.

**Where they come from.** `extract-cues` shows the model the library and asks
it, per block, for the one diagram that shows exactly what she explains, or
none, with how sure it is. `caption-sketch` does the same for a photo of her
sketch. Only picks at 0.7 or higher are saved (`diagram_suggestion`); the bar
is the `DIAGRAM_MIN_CONFIDENCE` secret, to raise if most get dismissed.

**Accept or dismiss.** The teacher answers on the dashboard and the student on
the phone. A teacher's dismissal hides the diagram for both; a student's hides
it only for the student. Rows are kept, so accept and dismiss counts can tune
the bar.

**Changing the drawings.** The list and the drawings are in
`tools/diagrams/gen.py`. Run `python3 tools/diagrams/gen.py` (SVGs, the
dashboard's `index.json` and the functions' `_shared/diagrams.ts`), then
`node tools/diagrams/png.cjs` (the phone's PNGs, needs Playwright). The
extract-cues tests check the three lists match.

## M2 gate

Link accuracy on the labelled real lessons meets the threshold agreed with the
teacher.

## M1 gate

1. The setup check passes on the phone: each voice on its own channel, and the
   student's voice clearly above the piano on the left channel.
2. A 60-minute lesson recorded with the screen off shows **No gaps** in the
   lesson list.
