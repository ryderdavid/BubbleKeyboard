// Gathers each scene's public/bar/<id>/meta.json (written by RenderVideoTest) into src/barMeta.json.
import fs from 'node:fs';
const script = JSON.parse(fs.readFileSync('src/script.json', 'utf8'));
const all = {};
for (const s of script) {
  const f = `public/bar/${s.id}/meta.json`;
  if (fs.existsSync(f)) all[s.id] = JSON.parse(fs.readFileSync(f, 'utf8'));
  else console.warn('no frames yet for', s.id);
}
fs.writeFileSync('src/barMeta.json', JSON.stringify(all));
console.log('scenes with frames:', Object.keys(all).join(', '));
