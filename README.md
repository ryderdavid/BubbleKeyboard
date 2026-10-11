# Bubble Keyboard

An Android keyboard that is only a bubble. Tap it to open a ring of keys; tap **abc** for the full keyboard.

- **Bubble:** tap it and the ring opens already listening (to open it without recording, slide to the left notch). Press it and four notches fade in around it: slide toward one and the bubble stretches after your thumb until it clicks in, then let go: **←** open the ring, **→** cursor joystick, **↑** full keyboard, **↓** settings. Let go short and it springs back; keep pulling past a clicked-in notch and you feel it strain (the bubble leans and stretches toward your thumb while ticks come harder and closer), then it breaks free with a snap and just moves. Ease back before the break and it settles into the notch again. Slide diagonally (or past a notch) to move it (remembered per orientation), or flick it diagonally and it glides on, slows down, and settles against the screen edge with a small bounce: it stays out of the way rather than rebounding back into it (the open ring too).
- **Press to talk:** press and hold the folded bubble: a light tick, then it sinks under your thumb while a pulse swells out from it, reaching the ring's edge at 0.4 s, where it locks in with a firm click and a flash: the bubble springs back up as the mic and the ring opens right where the pulse landed, already listening. Let go and keep talking; tap the center to finish (or ✕ to throw it away). Let go before the click and it's a tap, which records too.
- **Ring:** arrows, backspace, space, enter, abc. Backspace repeats while held. A faint tab just outside the disc marks each arrow's pull-out; turn on **Show hold hints** and small, very faint icons sit just outside the ring beyond every key instead, saying what holding it does (the keys themselves stay clean). Hold an arrow and a dashed socket fades in beyond it (swung around the ring if the screen edge is in the way); drag and the key comes out of its spot with your thumb, stretching from it, until it clicks into the socket about 80% of the way there (a click, and it turns solid with the action's icon). Let go there for **↑** pin the ring open (pin; again to unpin), **→** cursor joystick, **↓** settings (gear), **←** close the ring (circled ✕); let go short and it springs back, doing nothing. Tap, then tap-and-hold any arrow to rapid-fire it. Left/right stop at the ends of the text rather than pushing focus to the next field. The ring folds by itself after 5 s untouched (configurable 5–120 s); a thin arc around the center drains as it counts down, and pauses while you dictate. Pinned, there's no countdown, a small pin sits on the center, and the ring comes back open whenever the keyboard does.
- **Blooms:** hold space for punctuation, enter for numbers. Slide to a key and let go to type it; slide back to cancel. Keys swell and part around your finger so the neighbours stay visible; the key under your finger shows in a bubble above it. Near an edge the fan turns toward the middle of the screen, flips near the top, and is nudged to stay on screen.
- **Cut / copy / paste / delete:** whenever text is selected in the field, four buttons pop up in a column beside the bubble (or the open ring), on whichever side has more room, or in a row above/below if neither side fits. Cut, copy and paste ask the app to do it (like a long-press menu would); delete is a backspace, which removes the selection.
- **Paste button:** sit idle in a text field for a moment with text on the clipboard (on focusing the field, after tapping to place the cursor, or after copying something) and a round paste button, the same as the selection ones, pops up beside the bubble. Any other touch, key or edit puts it away. Only the clipboard's description (is it text?) is checked; the keyboard never reads its contents.
- **Cursor joystick:** slide right from the bubble (or pull the ring's → out) and the disc becomes a game-style stick: the base shaded like a well with a dashed travel ring and rim chevrons, the center button the knob. Push the knob and a target moves through the text (shown as a one-character highlight, since a keyboard can't draw inside the app's text): a dead zone, a crawl just past it, up to about 30 characters a second at the rim; up and down move by the app's own lines. Tap the knob and the cursor jumps to the target. Hold it still for a beat (a click) and pushing selects from the cursor instead. Cut, copy, paste and delete stand by beside it (cut and copy fade until something is selected), and a small ✕ on the rim folds it away.
- **Full keyboard:** tap abc and a staggered QWERTY grid of pills cascades out above the ring (below it if there's no room) and stays open. It's laid out like a phone keyboard: **⇧** z–m **⌫** on the third row, then comma, a wide space bar, period and **↵** along the bottom. Tap or slide-and-lift to type; backspace repeats while held; **⇧** capitalizes the next letter, double tap for caps lock. Slide along the space bar to move the cursor, a character per step either way (Gboard's gesture: the other keys fade and the bar shows arrows; a plain tap still types a space). abc turns into **✕**: tap it to fold the grid away. The ring doesn't time out while the grid is open.
- Only the bubble takes touches; the app behind is never resized. Colors follow Material You on Android 12+.
- The system navigation buttons sit on a solid Material You strip (no gray contrast scrim), with icons tinted for light or dark mode.

## Voice typing

Tap the folded bubble (or hold it until it clicks), or tap the open ring's center, and talk; it keeps listening after you let go. Finish with a tap on the center; the red **✕** where backspace was throws it away (or slide out from **←** onto ✕ to throw it away and fold up). The ring stays open and its keys still work meanwhile. It also finishes by itself after 10 s without a recognized word (adjustable 3–60 s); background noise doesn't count. The spin-down arc around the mic shows the silence left and refills with each new word.

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

Settings (open the app) follow Android 16's settings style in Material 3 and your wallpaper colors (Jetpack Compose): a home page that shows setup steps until the keyboard is on, then categories, each a short page of grouped rows. **Look and size**: bubble size and opacity, ring size, finger swell, with a live preview. **Ring and gestures**: keep the ring open (pin), how long it stays open, rotate-to-fit, the pull-out actions. **Voice typing**: microphone, ElevenLabs key, type-as-you-speak, silence timeout, Claude cleanup, its key and model (keys are entered in a dialog and never shown again). **Text and language**: millennial mode. Plus a gesture guide and a text box to try it in.

`./gradlew testDebugUnitTest -Prender` also renders the bubble, ring, blooms and voice pill over a sample page into `app/build/previews/`, for checking the look without a phone, and plays the whole feature walkthrough over a mock chat app into `app/build/walkthrough/frames` (24 fps) with its chapters in `chapters.json`; `ffmpeg -framerate 24 -i app/build/walkthrough/frames/f_%05d.jpg -c:v libx264 -pix_fmt yuv420p walkthrough.mp4` makes the film.

Icons: gear and cancel from [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0); the keyboard-switch glyph is AOSP's `ic_ime_switcher` (Apache 2.0).

### Estimated cost

Settings → Voice typing → **Estimated cost** shows what dictation cost over the past 24 hours, 7 days and 30 days, split into Scribe and Claude. The keyboard counts what it actually sent: the seconds of audio streamed to Scribe v2 Realtime (priced at $0.39 per hour), and the input and output tokens Claude reports back in each cleanup reply (priced per model at list rates; Haiku 5.5 is $0.10 / $0.50 per million). The counts are kept on the phone in hourly buckets for 31 days; no text or audio is stored. It's an estimate at list prices: plan allowances, free hours and credits aren't taken into account, so the providers' dashboards are the real bill. A model with no known price has its tokens counted but not priced.

## Language processing

- **Millennial mode** (off by default): a dictated message never ends with a period. Question marks, exclamation marks, an ellipsis and periods between sentences stay. Dictate more right after such a message and its period comes back before the new words (and goes away again if that follow-up is thrown out or hears nothing).

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # Robolectric tests: gestures, dictation flow, protocol against fake servers
```

Needs JDK 17+ and the Android SDK (platform 37). minSdk 26.
