# Bubble Keyboard

An Android keyboard that is only a bubble. Tap it to open a ring of keys; hold **abc** to fan out the letters.

- **Bubble:** drag to move (remembered per orientation), tap to open the ring, hold to talk.
- **Ring:** arrows, backspace, space, enter, abc. Right and backspace repeat while held; the other arrows have long-presses instead: **↑** switch keyboards, **↓** settings, **←** fold the ring up. The ring folds by itself after 30 s untouched (configurable 5–120 s); a thin arc around the center drains as it counts down, and pauses while you dictate.
- **Blooms:** hold abc for letters, space for punctuation, enter for numbers. Slide to a key and let go to type it; slide back to cancel. Tapping abc opens a letter bloom that waits for one tap. Keys swell and part around your finger so the neighbours stay visible; the key under your finger shows in a bubble above it. Near an edge the fan turns toward the middle of the screen, flips near the top, and is nudged to stay on screen.
- **Caps lock:** keep holding abc still for about a second (weak buzz, then strong). Do it again to turn it off (one weak buzz).
- Only the bubble takes touches; the app behind is never resized. Colors follow Material You on Android 12+.
- The system navigation buttons sit on a solid Material You strip (no gray contrast scrim), with icons tinted for light or dark mode.

## Voice typing

Two ways to talk:
- **Hold** the folded bubble or the ring's center and talk; let go and the text is typed. Slide more than ~90 dp away before letting go to throw it out.
- **Tap** the ring's center to start, tap it again to stop and type. The ring stays open meanwhile and its keys still work; long-press **←** to throw the dictation out and fold up.

Words stream into a pill above the bubble or ring as you speak.

- **Speech to text:** [ElevenLabs Scribe v2 Realtime](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime) over a WebSocket (16 kHz PCM, manual commit when you let go).
- **Cleanup (optional):** the whole utterance goes once to the Claude Messages API (default `claude-haiku-5-5`, effort low) to drop filler, fix punctuation and apply spoken corrections ("Tuesday, no, Wednesday"). If it fails or times out, the raw transcript is typed instead.
- **Keys:** your own ElevenLabs and Claude API keys, entered in settings, encrypted with an Android Keystore AES-GCM key and excluded from backups.
- **Swappable:** `voice/Transcriber` and `voice/Cleaner` are interfaces; another provider is one class each.
- Grant the microphone from the settings screen (a keyboard can't show a permission prompt itself).

Settings (open the app): bubble size, ring size, bubble opacity (how see-through it is at rest), finger swell (how far keys grow and part around your finger), rotate-to-fit; microphone, API keys, cleanup on/off and cleanup model.

`./gradlew testDebugUnitTest -Prender` also renders the bubble, ring, blooms and voice pill over a sample page into `app/build/previews/`, for checking the look without a phone.

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # Robolectric tests: gestures, dictation flow, protocol against fake servers
```

Needs JDK 17+ and the Android SDK (platform 37). minSdk 26.
