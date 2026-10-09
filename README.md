# Bubble Keyboard

An Android keyboard that is only a bubble. Tap it to open a ring of keys; hold **abc** to fan out the letters.

- **Bubble:** tap to open the ring (just the keys). Drag to move it (remembered per orientation), or flick it and it glides on, slows down, and settles against the screen edge with a small bounce: it stays out of the way rather than rebounding back into it (the open ring too).
- **Press to talk:** press and hold the folded bubble: a light tick, then it sinks under your thumb while a pulse swells out from it, reaching the ring's edge at 0.4 s, where it locks in with a firm click and a flash: the bubble springs back up as the mic and the ring opens right where the pulse landed, already listening. Let go and keep talking; tap the center to finish (or ✕ to throw it away). Let go before the click and it's just a tap.
- **Ring:** arrows, backspace, space, enter, abc. Up and backspace repeat while held. **→ ↓ ←** have side functions: hold one and an icon pops up beyond it (swung around the ring if the screen edge is in the way); slide out onto it and let go for **→** switch keyboard, **↓** settings (gear), **←** close the ring (circled ✕). Tap, then tap-and-hold any arrow to rapid-fire it. Left/right stop at the ends of the text rather than pushing focus to the next field. The ring folds by itself after 5 s untouched (configurable 5–120 s); a thin arc around the center drains as it counts down, and pauses while you dictate.
- **Blooms:** hold abc for letters, space for punctuation, enter for numbers. Slide to a key and let go to type it; slide back to cancel. Tapping abc opens a letter bloom that waits for one tap. Keys swell and part around your finger so the neighbours stay visible; the key under your finger shows in a bubble above it. Near an edge the fan turns toward the middle of the screen, flips near the top, and is nudged to stay on screen.
- **Cut / copy / paste:** whenever text is selected in the field, three buttons pop up in a column beside the bubble (or the open ring), on whichever side has more room, or in a row above/below if neither side fits. They ask the app to cut, copy or paste (like a long-press menu would), so the keyboard never reads the clipboard itself.
- **Full keyboard grid:** keep holding abc still for about 0.8 s (weak buzz, then strong) and a staggered QWERTY grid of pills cascades out above the ring (below it if there's no room) and stays open. It's laid out like a phone keyboard: **⇧** z–m **⌫** on the third row, then comma, a wide space bar, period and **↵** along the bottom. Tap or slide-and-lift to type; backspace repeats while held; **⇧** capitalizes the next letter, double tap for caps lock. abc turns into **✕**: tap it to fold the grid away. The ring doesn't time out while the grid is open.
- Only the bubble takes touches; the app behind is never resized. Colors follow Material You on Android 12+.
- The system navigation buttons sit on a solid Material You strip (no gray contrast scrim), with icons tinted for light or dark mode.

## Voice typing

Hold the folded bubble until it clicks (above), or tap the open ring's center, and talk; it keeps listening after you let go. Finish with a tap on the center; the red **✕** where backspace was throws it away (or slide out from **←** onto ✕ to throw it away and fold up). The ring stays open and its keys still work meanwhile. It also finishes by itself after 10 s without a recognized word (adjustable 3–60 s); background noise doesn't count. The spin-down arc around the mic shows the silence left and refills with each new word.

**Type as you speak** (default): the words go straight into the text field as the keyboard's draft (underlined in most apps) and are rewritten in place as the recognizer firms them up. When you finish, the polished text replaces the draft in one step. A small **Listening…** / **Polishing…** label floats above the bubble.

While listening, a throb swells from the mic button out to the full ring (or, for the folded bubble, well past where your thumb covers it) with your voice. It's auto-gained: it tracks your recent loudest and the room's quiet level, so normal speech fills it whether you're whispering or across a room.
- Press a key mid-dictation and what's typed so far stays; later words continue after the key, and only they get polished.
- Tap in the text or select something mid-dictation and the dictation stops right there and finishes in place: the polish replaces the draft while your cursor or selection stays exactly where you put it. The dictation never pulls the cursor back.
- If the app ends the draft itself, the dictation stops too; the polish lands only if the raw words are still right before the cursor, otherwise your text is left alone.
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
