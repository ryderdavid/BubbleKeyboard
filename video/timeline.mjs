// Builds src/timeline.json: each scene's length and word timings. With narration/<id>.json
// (ElevenLabs alignment) and narration/<id>.mp3 it uses the real timings; otherwise it estimates.
import fs from 'node:fs';
import {execSync} from 'node:child_process';
const FPS = 30, LEAD = 0.35, TAIL = 0.55;
const script = JSON.parse(fs.readFileSync('src/script.json', 'utf8'));
const out = script.map((s) => {
  const mp3 = `public/narration/${s.id}.mp3`, al = `public/narration/${s.id}.json`;
  let words, dur, audio = null;
  if (fs.existsSync(mp3) && fs.existsSync(al)) {
    const a = JSON.parse(fs.readFileSync(al, 'utf8'));
    dur = parseFloat(execSync(`ffprobe -v error -show_entries format=duration -of csv=p=0 ${mp3}`).toString());
    words = []; let cur = null;
    a.characters.forEach((ch, i) => {
      if (/\s/.test(ch)) { if (cur) { words.push(cur); cur = null; } return; }
      if (!cur) cur = {w: '', start: a.character_start_times_seconds[i], end: 0};
      cur.w += ch; cur.end = a.character_end_times_seconds[i];
    });
    if (cur) words.push(cur);
    audio = `narration/${s.id}.mp3`;
  } else {
    const ws = s.text.split(/\s+/); const per = 0.36;
    words = ws.map((w, i) => ({w, start: i * per, end: (i + 1) * per - 0.04}));
    dur = ws.length * per;
  }
  const frames = Math.round((LEAD + dur + TAIL) * FPS);
  return {...s, audio, lead: LEAD, frames, words};
});
fs.writeFileSync('src/timeline.json', JSON.stringify(out, null, 1));
console.log(out.map((s) => `${s.id} ${(s.frames / FPS).toFixed(1)}s`).join('\n'), '\ntotal', (out.reduce((a, s) => a + s.frames, 0) / FPS).toFixed(1), 's');
