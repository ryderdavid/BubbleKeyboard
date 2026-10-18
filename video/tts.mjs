// Narration for each scene with ElevenLabs Eleven v4, with character timings for the captions.
// ELEVENLABS_API_KEY=… node tts.mjs [voiceId]   (then: node timeline.mjs)
import fs from 'node:fs';
const key = process.env.ELEVENLABS_API_KEY;
if (!key) { console.error('Set ELEVENLABS_API_KEY'); process.exit(1); }
const voice = process.argv[2] || process.env.VOICE_ID || 'EXAVITQu4vr4xnSDxMaL';
const script = JSON.parse(fs.readFileSync('src/script.json', 'utf8'));
fs.mkdirSync('public/narration', {recursive: true});
for (let i = 0; i < script.length; i++) {
  const s = script[i];
  const body = {
    text: s.text,
    model_id: 'eleven_v4',
    previous_text: i > 0 ? script[i - 1].text : undefined,
    next_text: i < script.length - 1 ? script[i + 1].text : undefined,
  };
  const r = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}/with-timestamps?output_format=mp3_44100_128`, {
    method: 'POST', headers: {'xi-api-key': key, 'content-type': 'application/json'}, body: JSON.stringify(body),
  });
  if (!r.ok) { console.error(s.id, r.status, await r.text()); process.exit(1); }
  const j = await r.json();
  fs.writeFileSync(`public/narration/${s.id}.mp3`, Buffer.from(j.audio_base64, 'base64'));
  fs.writeFileSync(`public/narration/${s.id}.json`, JSON.stringify(j.alignment ?? j.normalized_alignment));
  console.log('ok', s.id);
}
