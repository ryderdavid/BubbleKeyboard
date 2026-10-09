# Bubble Keyboard

An Android keyboard that is only a bubble. Tap it to open a ring of keys; hold **abc** to fan out the letters.

- **Bubble:** drag to move (remembered per orientation), tap to open the ring, hold to talk.
- **Ring:** arrows, backspace, space, enter, abc. Up and backspace repeat while held. **→ ↓ ←** have side functions: hold one and an icon pops up beyond it (swung around the ring if the screen edge is in the way); slide out onto it and let go for **→** switch keyboard, **↓** settings (gear), **←** close the ring (circled ✕). Tap, then tap-and-hold any arrow to rapid-fire it. Left/right stop at the ends of the text rather than pushing focus to the next field. The ring folds by itself after 30 s untouched (configurable 5–120 s); a thin arc around the center drains as it counts down, and pauses while you dictate.
- **Blooms:** hold abc for letters, space for punctuation, enter for numbers. Slide to a key and let go to type it; slide back to cancel. Tapping abc opens a letter bloom that waits for one tap. Keys swell and part around your finger so the neighbours stay visible; the key under your finger shows in a bubble above it. Near an edge the fan turns toward the middle of the screen, flips near the top, and is nudged to stay on screen.
- **Cut / copy / paste:** whenever text is selected in the field, three buttons pop up in a column beside the bubble (or the open ring), on whichever side has more room, or in a row above/below if neither side fits. They ask the app to cut, copy or paste (like a long-press menu would), so the keyboard never reads the clipboard itself.
- **Caps lock:** keep holding abc still for about a second (weak buzz, then strong). Do it again to turn it off (one weak buzz).
- Only the bubble takes touches; the app behind is never resized. Colors follow Material You on Android 12+.
- The system navigation buttons sit on a solid Material You strip (no gray contrast scrim), with icons tinted for light or dark mode.

## Voice typing

Ways to talk:
- **Tap** the folded bubble: the ring opens already listening, hands free (the "Tap the bubble to talk" setting; off, a tap just opens the ring). Tap the center to finish. While listening, backspace turns into a red **✕** that throws the dictation away.
- **Hold** the folded bubble or the ring's center and talk; let go and the text is typed. Slide more than ~90 dp away before letting go to throw it out.
- **Tap** the open ring's center to start, tap it again to stop and type. The ring stays open meanwhile and its keys still work; slide out from **←** onto ✕ to throw the dictation out and fold up.

**Type as you speak** (default): the words go straight into the text field as the keyboard's draft (underlined in most apps) and are rewritten in place as the recognizer firms them up. When you finish, the polished text replaces the draft in one step. A small **Listening…** / **Polishing…** label floats above the bubble.

While listening, a throb swells from the mic button out to the full ring (or, for the folded bubble, well past where your thumb covers it) with your voice. It's auto-gained: it tracks your recent loudest and the room's quiet level, so normal speech fills it whether you're whispering or across a room.
- Press a key mid-dictation and what's typed so far stays; later words continue after the key, and only they get polished.
- If the app ends the draft itself (you tap elsewhere, say), the polish lands only if the raw words are still right before the cursor; otherwise your text is left alone. Words that arrive late are still typed.
- Hiding the keyboard or changing fields mid-dictation keeps what was typed.
- Password fields and raw-key fields (terminals) fall back to the preview mode.

**Preview mode** (setting off): words stream into a pill above the bubble or ring, labeled **Listening…** then **Polishing…**, with an accent glow that breathes with your voice; the text is typed when you finish.

- **Speech to text:** [ElevenLabs Scribe v2 Realtime](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime) over a WebSocket (16 kHz PCM, manual commit when you let go).
- **Cleanup (optional):** the whole utterance goes once to the Claude Messages API (default `claude-haiku-5-5`, effort low) to drop filler, fix punctuation and apply spoken corrections ("Tuesday, no, Wednesday"). If it fails or times out, the raw transcript is typed instead.
- **Keys:** your own ElevenLabs and Claude API keys, entered in settings, encrypted with an Android Keystore AES-GCM key and excluded from backups.
- **Swappable:** `voice/Transcriber` and `voice/Cleaner` are interfaces; another provider is one class each.
- Grant the microphone from the settings screen (a keyboard can't show a permission prompt itself).

Settings (open the app): bubble size, ring size, bubble opacity (how see-through it is at rest), finger swell (how far keys grow and part around your finger), rotate-to-fit; microphone, API keys, cleanup on/off and cleanup model.

`./gradlew testDebugUnitTest -Prender` also renders the bubble, ring, blooms and voice pill over a sample page into `app/build/previews/`, for checking the look without a phone.

Icons: gear and cancel from [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0); the keyboard-switch glyph is AOSP's `ic_ime_switcher` (Apache 2.0).

## Language processing

- **Millennial mode** (off by default): a dictated message never ends with a period. Question marks, exclamation marks, an ellipsis and periods between sentences stay. Dictate more right after such a message and its period comes back before the new words (and goes away again if that follow-up is thrown out or hears nothing).

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # Robolectric tests: gestures, dictation flow, protocol against fake servers
```

Needs JDK 17+ and the Android SDK (platform 37). minSdk 26.
