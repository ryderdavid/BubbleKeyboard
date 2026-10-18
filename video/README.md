# Voicebar welcome video

Remotion source for the narrated welcome video bundled as `app/src/main/res/raw/welcome.mp4`.

```
npm install
ELEVENLABS_API_KEY=… node tts.mjs [voiceId]   # narration per scene, Eleven v4, with timings
node timeline.mjs                             # scene lengths and caption timings from the audio
npx remotion render src/index.ts Welcome out/welcome.mp4 --codec=h264 --crf=26
```

Edit the words in `src/script.json`; the scenes in `src/Video.tsx` key their actions to words in it.
