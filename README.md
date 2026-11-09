# Cue Note

A vocal-coaching app for a teacher and a student. The student's Android phone
records each in-person lesson on two microphones and turns it into a timeline of
cards; the teacher corrects those cards and sets practice from a web dashboard.

The full design is in the project's design doc. This repository is built in
milestones; **M1 (foundations and recorder)** is what's here so far.

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
| `:core:data`      | Room database of lessons, consent, magic-link sign-in (supabase-kt). |
| `:feature:record` | Home, consent, setup check, recording and sign-in screens. |
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

## M1 gate

1. The setup check passes on the phone: each voice on its own channel, and the
   student's voice clearly above the piano on the left channel.
2. A 60-minute lesson recorded with the screen off shows **No gaps** in the
   lesson list.
