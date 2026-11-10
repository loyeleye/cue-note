// caption-sketch: writes a one-line caption for a photo of her sketch.
//
// The student's phone calls this after uploading a lesson photo, with the
// photo's id and what was said around it (the phone keeps the transcript;
// audio never leaves the phone). The function reads the photo with the
// caller's own session, so it captions only photos the caller may see,
// sends the cleaned image and the words to Claude, and saves:
//   caption     one short line in her own terms, e.g. "Soft palate lifting on the 'ah' vowel"
//   confidence  0..1, how clearly the sketch matches what she was saying
// It never adds advice she didn't give.
//
// Secrets (supabase secrets set ...):
//   ANTHROPIC_API_KEY  the Claude API key (shared with extract-cues)
//   CUE_MODEL          optional; the model, default claude-opus-5-5
// SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are provided by Supabase.
import { createClient } from 'npm:@supabase/supabase-js@2';
import Anthropic from 'npm:@anthropic-ai/sdk@1.2.0';

type Body = { photo_mark_id?: string; words?: string };

const SYSTEM = `You caption photos taken during a one-to-one singing lesson. The photo usually shows a quick sketch the voice teacher drew on paper or a whiteboard to explain something (the throat, the breath, a vowel shape, a melody contour, a rhythm). You also get what the teacher and the student said around the moment the photo was taken.

Write one caption of at most 12 words saying what the sketch shows, in the teacher's own terms from the transcript where they fit. Describe; do not add advice, corrections or technique she did not give. If the photo is not a sketch, say plainly what it shows.

confidence is how clearly the sketch matches what she was saying, from 0 (unrelated or unreadable) to 1 (plainly the thing she was explaining).`;

const OUTPUT_SCHEMA = {
  type: 'object',
  properties: {
    caption: { type: 'string' },
    confidence: { type: 'number' },
  },
  required: ['caption', 'confidence'],
  additionalProperties: false,
};

function toBase64(bytes: Uint8Array): string {
  let s = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode(...bytes.subarray(i, i + chunk));
  return btoa(s);
}

Deno.serve(async (req) => {
  if (req.method !== 'POST') return Response.json({ error: 'POST only' }, { status: 405 });
  const body: Body = await req.json().catch(() => ({}));
  const id = body.photo_mark_id;
  if (!id) return Response.json({ error: 'photo_mark_id is required' }, { status: 400 });
  const words = (body.words ?? '').slice(0, 8000);

  const url = Deno.env.get('SUPABASE_URL')!;
  // The caller's own session: row-level security decides whether they may see this photo.
  const asCaller = createClient(url, Deno.env.get('SUPABASE_ANON_KEY')!, {
    global: { headers: { Authorization: req.headers.get('Authorization') ?? '' } },
  });
  const { data: photo, error } = await asCaller
    .from('photo_mark')
    .select('id, image_path, cleaned_path')
    .eq('id', id)
    .maybeSingle();
  if (error) return Response.json({ error: error.message }, { status: 500 });
  if (!photo) return Response.json({ error: 'no such photo' }, { status: 404 });
  const path = photo.cleaned_path ?? photo.image_path;
  if (!path) return Response.json({ error: 'the photo is not uploaded yet' }, { status: 409 });

  const service = createClient(url, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!);
  const { data: file, error: fileError } = await service.storage.from('photos').download(path);
  if (fileError || !file) return Response.json({ error: `could not read the photo: ${fileError?.message}` }, { status: 500 });
  const image = toBase64(new Uint8Array(await file.arrayBuffer()));

  const apiKey = Deno.env.get('ANTHROPIC_API_KEY');
  if (!apiKey) return Response.json({ caption: null, note: 'ANTHROPIC_API_KEY is not set' });
  const client = new Anthropic({ apiKey });

  let caption: string;
  let confidence: number;
  try {
    const response = await client.beta.messages.create({
      model: Deno.env.get('CUE_MODEL') || 'claude-opus-5-5',
      max_tokens: 2000,
      // A short caption needs little thought. If a safety check declines,
      // the API retries on a fallback model inside the same call.
      betas: ['server-side-fallback-2026-07-01'],
      fallbacks: 'default',
      output_config: { effort: 'low', format: { type: 'json_schema', schema: OUTPUT_SCHEMA } },
      system: SYSTEM,
      messages: [{
        role: 'user',
        content: [
          { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: image } },
          { type: 'text', text: `What was said around the photo:\n${words || '(nothing was transcribed)'}` },
        ],
      }],
    // deno-lint-ignore no-explicit-any
    } as any);
    if (response.stop_reason === 'refusal') {
      return Response.json({ caption: null, note: 'declined' });
    }
    // deno-lint-ignore no-explicit-any
    const text = (response.content as any[]).filter((b) => b.type === 'text').map((b) => b.text).join('');
    const out = JSON.parse(text);
    caption = String(out.caption).trim().slice(0, 200);
    confidence = Math.min(1, Math.max(0, Number(out.confidence) || 0));
  } catch (e) {
    if (e instanceof Anthropic.RateLimitError || e instanceof Anthropic.InternalServerError) {
      return Response.json({ error: 'busy, try later' }, { status: 503 });
    }
    if (e instanceof Anthropic.APIError) {
      return Response.json({ error: `Claude: ${e.status} ${e.message}` }, { status: 502 });
    }
    return Response.json({ error: `could not read the caption: ${e}` }, { status: 502 });
  }

  const { error: saveError } = await service.from('photo_mark').update({ caption, confidence }).eq('id', id);
  if (saveError) return Response.json({ error: saveError.message }, { status: 500 });
  return Response.json({ caption, confidence });
});
