# Voicebar

A voice-first Android keyboard that works alongside Gboard. Voicebar is a one-row bar under the app for talking, moving the cursor and editing; for typing, **ABC** hands the field to Gboard, and a button drawn over Gboard brings you back, already listening.

## The bar

- **Three wide buttons** (default): ⋮ · **Cursor** · **ABC** · **Voice** · ⌫. Hold ⌫ and it opens into ↵ · space · ⌫ under your finger; tap then hold ⌫ to keep deleting.
- **Seven equal buttons:** ⋮ · space · Cursor · ABC · Voice · ⌫ · ↵. Space and ↵ are one tap; holding ⌫ keeps deleting. Pick the layout in Settings ▸ Bar and Gboard (each option shows the real bar).
- **Voice:** tap and talk. The pill shows a centered live waveform and a timer. **Tap the pill** to finish (always polished); **swipe it right** to finish and then send, **left** to throw it away (the pill squeezes aside over Send or Cancel; past the mark it says *Let go to send/cancel*). Sending uses the field's own Send/Go/Search action when it offers one, otherwise the app's Send button, pressed through the accessibility service (or a note if there's none). *← Cancel · Tap to finish · Send →* shows as dictation starts and after 3 s of quiet (every 10 s while it stays quiet), morphing out of the waveform; where the pill is too narrow, *Tap to finish* and *← Cancel · Send →* take turns. Polishing shows inside the pill; paste and ✕ stay in place, ghosted. Around it: **paste** on the left (only with text on the clipboard; one line, two or three buttons wide; throws the dictation away and pastes the clipboard instead), then **✕** (throw it away) and **ABC** at the right (finish, then switch to Gboard).
- **Voice bar commands:** end a dictation by saying *voice bar* and a command, then pausing: *done*, *send*, *keyboard* (finish, then Gboard), *clean up* (polish so far, keep listening) or *cancel*. Loose wording works (*send it*, *never mind*, *scratch that*, *let me type*…). The command isn't typed. While you say it, the pill turns Material You purple and shows the command instead of typing it.
- **“Voice bar, listen” anytime:** while a keyboard is up and idle (Voicebar's bar, or Gboard with the way-back button), an on-phone keyword spotter ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), GigaSpeech KWS model, ~5 MB, nothing leaves the phone) listens for *voice bar*. The button turns purple with a glow; *listen* (or dictate, start, talk) starts dictating, switching back from Gboard if needed.
- **Millennial mode:** in chats only (messaging apps, or any box whose Enter sends), everywhere, or off. Chats only keeps periods in email, notes and everything else.
- **Cursor:** a trackpad. Drag to move, hold still then drag to select, keep one finger down while another drags to go further. Cut, copy, paste and delete sit above it; **Done** folds it away.
- **⋮:** settings, switch keyboard, and this month's estimated cost.

## Typing with Gboard

**ABC** switches the field to Gboard. The accessibility service *Voicebar: way back from Gboard* (Android 11+; on 13+ a sideloaded app first needs *Allow restricted settings* in its App info) draws a way back while Gboard is open, in one of three styles:

- **Bar on top of Gboard** (default): a strip in Gboard's own background color (sampled from the screen), joined to its top edge, with a tonal *Tap to return* pill.
- **In Gboard's voice-key spot:** covers Gboard's mic and takes the tap; with Gboard's voice key off it sits at the right end of Gboard's top row, sliding left past any round Gboard button (the AI writing key) rather than covering it.
- **Floating mic bubble:** draggable; remembers its spot relative to the keyboard's top.

Any of them switches back and starts dictation right away. If the service is off, ABC says so first (tap the message to open Accessibility settings, or tap ABC again to switch anyway). The service reads only Gboard's window: its position, its key layout and its background color.

## Voice typing

**Listen as soon as a text box opens** (off by default): dictation starts by itself whenever a text field opens Voicebar (not in password fields).

**Type as you speak** (default): the words go straight into the text field as the keyboard's draft and are rewritten in place as the recognizer firms them up; the polished text replaces the draft in one step.
- Press a key mid-dictation and what's typed so far stays; later words continue after the key, and only they get polished.
- Tap in the text or select something mid-dictation and the dictation stops right there and finishes in place: the polish replaces the draft while your cursor or selection stays exactly where you put it. The dictation never pulls the cursor back.
- If the app ends the draft itself, the dictation stops too; the polish lands only if the raw words are still right before the cursor, otherwise your text is left alone.
- Hiding the keyboard or changing fields mid-dictation keeps what was typed.
- Password fields and raw-key fields (terminals) fall back to the preview mode.


**Preview mode** (setting off): words stream into the voice pill and are typed when you finish.

- **Speech to text:** [ElevenLabs Scribe v2 Realtime](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime) over a WebSocket (16 kHz PCM; manual commits at a pause and at the end).
- **Cleanup (optional), in two stages:** hands-free dictation that goes quiet for half the silence timeout (at most 5 s; 5 s of the default 10) commits what's been said with ElevenLabs and sends it to the Claude Messages API (default `claude-haiku-5-5`, effort low) while the mic stays open; the cleaned text replaces the raw words in the draft. Keep talking and the next cleanup (another pause, ✓, or the timeout) sends the cleaned text and the new raw words marked apart (`<cleaned>` / `<raw>`), so spoken corrections across the pause ("Tuesday… no, Wednesday") still resolve. If nothing was said after the last pause, finishing types the cleaned text with no further request. Any failure or timeout falls back to what's there: cleaned part plus raw words.
- **Keys:** your own ElevenLabs and Claude API keys, entered in settings, encrypted with an Android Keystore AES-GCM key and excluded from backups.
- **Swappable:** `voice/Transcriber` and `voice/Cleaner` are interfaces; another provider is one class each.
- Grant the microphone from the settings screen (a keyboard can't show a permission prompt itself).

Settings (open the app) use Material 3 with your wallpaper colors: setup steps (turn on Voicebar, switch to it, turn on Gboard, turn on the way back) until everything is ready, then **Bar and Gboard**, **Voice typing**, **Text and language**, a short guide and a text box to try it in.

`./gradlew testDebugUnitTest -Prender` renders the bar's states, the color schemes and the settings pages into `app/build/previews/`, for checking the look without a phone.

Icons: gear and cancel from [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0); the keyboard-switch glyph is AOSP's `ic_ime_switcher` (Apache 2.0).

### Estimated cost

Settings → Voice typing → **Estimated cost** shows what dictation cost over the past 24 hours, 7 days and 30 days, split into Scribe and Claude. Voicebar counts what it actually sent: the seconds of audio streamed to Scribe v2 Realtime (priced at $0.39 per hour), and the input and output tokens Claude reports back in each cleanup reply (priced per model at list rates; Haiku 5.5 is $0.10 / $0.50 per million). The counts are kept on the phone in hourly buckets for 31 days; no text or audio is stored. It's an estimate at list prices: plan allowances, free hours and credits aren't taken into account, so the providers' dashboards are the real bill. A model with no known price has its tokens counted but not priced.

## Language processing

- **Millennial mode** (off by default): a dictated message never ends with a period. Question marks, exclamation marks, an ellipsis and periods between sentences stay. Dictate more right after such a message and its period comes back before the new words (and goes away again if that follow-up is thrown out or hears nothing).

## Language processing

- **Millennial mode** (off by default): a dictated message never ends with a period. Question marks, exclamation marks, an ellipsis and periods between sentences stay. Dictate more right after such a message and its period comes back before the new words.

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # Robolectric tests: the bar, dictation flow, protocol against fake servers
```

Release builds are signed with the permanent key in `~/Code/BubbleKeyboard-signing/` on Ryder's Mac (not in the repo); add `bk.signingDir=<that folder>` to `local.properties`. Without it the build falls back to the machine's debug key, and that APK won't install over one signed with the real key.

Needs JDK 17+ and the Android SDK (platform 37). minSdk 26.

The internal app ID stays `com.ryderdavid.bubblekeyboard` (and the service classes keep their names) so Voicebar installs over Bubble Keyboard with its settings, keys and enabled services intact.
