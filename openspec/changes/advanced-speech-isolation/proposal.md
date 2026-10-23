## Why

Auto-listen decides whether a dictation is "someone talking at the phone" by loudness alone: about 200 ms within roughly 6 dB of the user's usual level, inside an 8 s window, or the dictation is cancelled and thrown away. That works for normal speech but fails exactly when the user wants to be quiet: whispering late at night, or speaking softly so as not to announce everything. The logs already show the failure: the recognizer transcribed every word correctly, the level just fell short of the bar, and the words were discarded. Loudness is a stand-in for intent; Voicebar already has better, cheap signals for intent that it ignores.

## What Changes

- Replace the single loudness bar with an **engagement decision that weighs evidence**:
  - the user tapped the text box (strong);
  - whispered speech with recognized words (strong; whispers don't carry, so a transcribable whisper is near);
  - fold open with the phone in hand;
  - loud enough near the phone (today's rule);
  - darkness with the phone in hand (lowers the loudness bar);
  - quiet *voiced* speech while the phone lies still (counts against);
  - in a pocket (**veto**: never engaged).
- **Explicit whisper detection** from the audio Voicebar already reads: speech-band energy that rises and falls at syllable rate, high-energy moments without a pitch, energy tilted toward high frequencies, confirmed by the recognizer returning words.
- **Device context signals** from low-power, on-change sensors: proximity plus ambient light (pocket, dark room), motion (held vs lying still), and hinge/fold state on foldables.
- **Never silently drop clearly heard words**: when the start window passes without engagement, the words stay in the pill for a tap instead of the dictation being cancelled.
- **Voice and whisper profiles**: the user's usual voice (the existing voiceprint and typical level) and a new whisper profile (whisper level and sound), each recorded once and refined from use.
- A new **Advanced speech isolation** settings group holding all of it, each signal with a switch and a strength, plus the existing "Only my voice" and "Ignore children's voices" moved there.
- The child filter keeps requiring a pitch (a voiced child), so whispers keep passing through it; its trailing hold must not clip a whisper that follows a child.

Out of scope: camera or face detection; any new cloud call for engagement (the removed OpenAI safeguard stays removed); changing how manually started dictations work (they stay engaged from the start); changing speech recognition providers.

## Capabilities

### New Capabilities
- `dictation-engagement`: deciding whether an auto-started dictation is the user dictating to the phone, from weighed evidence and a pocket veto, and what happens to words when it can't tell.
- `whisper-detection`: recognizing whispered speech as distinct from quiet voiced speech, from the audio alone plus recognized words.
- `device-context-signals`: cheap sensor readings that describe how the phone is being used right now: in a pocket, in a dark room, held or lying still, folded or open.
- `speech-profiles`: the user's voice profile and whisper profile, how they're recorded, refined from use, and reset.
- `speech-isolation-settings`: the Advanced speech isolation settings group and the defaults that make it work for someone who never opens it.

### Modified Capabilities
(none: no specs exist yet in `openspec/specs/`)

## Impact

- **Code**: `voice/AutoListenGuard.kt` (becomes the evidence-weighing engagement decision), `VoicebarService.kt` (feeds signals, keeps unengaged words in the pill), `voice/ChildFilter.kt` (whisper-aware trailing hold), a new whisper detector next to the child filter's pitch tracker, a new sensor reader, `voice/Voiceprint.kt` and `VoiceprintUi.kt` (profiles), `SettingsActivity.kt` (new group), `DockView.kt` (pill state for held words past the window), `GboardButtonService.kt` (the "text box was tapped" accessibility event).
- **Platform**: `Sensor.TYPE_PROXIMITY`, `TYPE_LIGHT`, `TYPE_ACCELEROMETER` (or significant-motion), `TYPE_HINGE_ANGLE` / Jetpack WindowManager fold state; all registered only while a dictation is starting or running.
- **Battery**: no always-on additions; sensors and audio features run only during auto-started dictations' first seconds.
- **Privacy**: everything stays on the phone; profiles are stored like the existing voiceprint.
- **Settings and prefs**: new keys for each signal and profile; existing voiceprint and child-filter prefs keep their values when moved.
