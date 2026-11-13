#!/usr/bin/env python3
"""Records app voice lines with Gemini text-to-speech, in the Iapetus voice.

    GEMINI_API_KEY=... python3 tools/voice/say.py KEY "Line to say." [KEY "Line." ...]

Pick the voice with VOICE=Iapetus (default) or VOICE=Erinome; each line becomes android/app/src/main/assets/voice/<voice>/KEY.mp3: trimmed to about
30 ms of silence at each end and level-matched to the other clips (-16 LUFS,
24 kHz mono MP3), then transcribed back so you can check it says its words.
Needs ffmpeg.
"""
import base64, json, os, re, subprocess, sys, tempfile, urllib.request

MODEL = os.environ.get("TTS_MODEL", "gemini-2.5-pro-preview-tts")
CHECK_MODEL = "gemini-3.8-flash"
VOICE = os.environ.get("VOICE", "Iapetus")
STYLE = "Say this warmly and clearly, at a natural conversational pace, like a friendly stage manager talking to a singer and their teacher:"
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "main", "assets", "voice", VOICE.lower())
KEY = os.environ["GEMINI_API_KEY"]


def gemini(model, body):
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={KEY}",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except Exception as e:
            if attempt == 3: raise
            print("  retrying:", e)


def record(key, text):
    res = gemini(MODEL, {
        "contents": [{"parts": [{"text": f"{STYLE}\n\n{text}"}]}],
        "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {
            "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": VOICE}}}},
    })
    pcm = base64.b64decode(res["candidates"][0]["content"]["parts"][0]["inlineData"]["data"])
    with tempfile.NamedTemporaryFile(suffix=".pcm", delete=False) as f:
        f.write(pcm)
    os.makedirs(OUT, exist_ok=True)
    out = os.path.abspath(os.path.join(OUT, f"{key}.mp3"))
    # Trim both ends, shorten any pause over 0.45 s to 0.35 s, then level-match.
    trim = "silenceremove=start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_threshold=-45dB:" \
           "stop_duration=0.45:stop_silence=0.35,areverse," \
           "silenceremove=start_periods=1:start_threshold=-45dB,areverse," \
           "adelay=30,apad=pad_dur=0.03,loudnorm=I=-16:TP=-1:LRA=11"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", "24000", "-ac", "1", "-i", f.name,
                    "-af", trim, "-ar", "24000", "-ac", "1", "-b:a", "64k", out], check=True)
    os.unlink(f.name)
    with open(out, "rb") as f:
        mp3 = base64.b64encode(f.read()).decode()
    heard = gemini(CHECK_MODEL, {"contents": [{"parts": [
        {"inlineData": {"mimeType": "audio/mp3", "data": mp3}},
        {"text": "Transcribe this audio exactly. Reply with the words only."}]}]})
    heard = heard["candidates"][0]["content"]["parts"][0]["text"].strip()
    print(f"{key}.mp3\n  said:  {text}\n  heard: {heard}")
    return words(heard) == words(text)


def words(s):
    return re.sub(r"[^a-z' ]", " ", s.lower().replace("’", "'")).split()


args = sys.argv[1:]
if not args or len(args) % 2:
    sys.exit(__doc__)
bad = []
for i in range(0, len(args), 2):
    # A take that doesn't say exactly its words is recorded again, up to three times.
    if not any(record(args[i], args[i + 1]) for _ in range(3)):
        bad.append(args[i])
if bad:
    sys.exit("These never matched their words: " + ", ".join(bad))
