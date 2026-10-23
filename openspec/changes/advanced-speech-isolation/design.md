## Context

`AutoListenGuard` (v1.5.6) engages an auto-started dictation when the mic level stays within `MARGIN` (0.15, about 6 dB) of the user's learned typical level for `LOUD_FOR_MS` (200 ms), tolerating dips up to `DIP_MS` (400 ms), inside `START_WINDOW_MS` (8 s); the bar is clamped to 0.35–0.75 on a scale where 0 ≈ −50 dBFS and 1 ≈ −10 dBFS. Unengaged words are shown in the pill (since 1.5.6) but the window's expiry cancels the dictation and discards them. The learned level only comes from dictations started by hand.

The user wants to whisper or talk softly into text boxes late at night. Logs show a case where the recognizer transcribed everything correctly and the level simply fell short.

Already available, at little or no extra cost:
- the mic audio, which the child filter already analyzes per 16 ms frame (64 ms STFT, pitch from harmonics);
- the recognizer's partials (words, or none);
- the accessibility companion (`GboardButtonService`), which receives view events and can see a text box being tapped;
- phone sensors: proximity, light, accelerometer, and on foldables a hinge angle or Jetpack WindowManager fold state.

Prior art: Alexa's Whisper Mode (a trained whisper/normal classifier on filter-bank energies), Apple Watch "Raise to Speak" (gesture plus nearness), Google's Personal VAD research (speaker-conditioned VAD). None combine these into one engagement decision for a keyboard.

## Goals / Non-Goals

**Goals:**
- Whispered and soft speech that's clearly meant for the phone engages auto-listen.
- Room talk, TV and other people stay out of the text box.
- Nothing clearly heard is silently discarded.
- No meaningful battery cost: no always-on sensors, no camera, no new network calls.
- Everything tunable in one settings group, with defaults that work untouched.

**Non-Goals:**
- Camera or face-based attention.
- Changing manually started dictations, the cleanup, or the speech provider.
- A trained ML whisper classifier at first (see Decisions).
- Speaker verification of whispers (voiceprints rely on vocal tone, which whispers lack).

## Decisions

**1. A scored decision with one veto, not a rule tree.**
`AutoListenGuard` becomes `Engagement`: each signal contributes a weight; engaged when the sum crosses 1.0; a pocket reading vetoes. Initial weights: box tapped 1.0 (alone enough); whisper-with-words 1.0; loud-near-voice 1.0 (today's rule); fold open and held 0.5; held 0.25; voiced-quiet-and-still −0.5. Darkness doesn't add weight; it lowers the loudness bar (MARGIN widens from 0.15 to about 0.30, floor 0.25).
*Why:* signals are individually weak and situational; a score combines them, keeps each one switchable (switch off = weight 0), and is easy to test with fixed inputs.
*Alternative:* nested if/else rules. Harder to tune and to explain in the log.

**2. Whisper detection from hand-built features, not a model, first.**
Per frame (reusing the child filter's STFT): speech-band energy relative to a running noise floor; voicing from the existing harmonic pitch score; spectral tilt (energy 2–6 kHz vs 100 Hz–1 kHz). Over a sliding ~300–500 ms stretch: whisper when syllable-rate energy modulation (3–8 Hz) is present, under ~15% of the high-energy frames are voiced, and the tilt is high. Confirmed only while the recognizer is returning words.
*Why:* the acoustic difference (no glottal excitation) is large and well understood; this costs a few arithmetic operations per frame on data already computed, and it's explainable in the log.
*Alternative:* a small LSTM classifier like Alexa's. Better in noise, but needs training data, a model file and inference cost. Revisit only if the features prove unreliable on real recordings.

**3. "Box tapped" from accessibility click events.**
`GboardButtonService` already receives view events. A `TYPE_VIEW_CLICKED` (or a focus event following a touch) on the editable that then opens Voicebar within ~1 s marks the start as user-initiated. Without the accessibility companion, this signal is absent.
*Alternative:* inferring from `EditorInfo` or timing alone. Not reliable; apps focus boxes on their own.

**4. Sensors: on-change, registered only for the decision.**
Proximity and light (on-change), accelerometer at a low rate for ~1 s windows (variance over sensor noise means held), hinge angle or WindowManager fold state. All registered at an auto-started dictation's start and released when engaged, ended, or after the window. `SensorManager` listeners on the main looper, values cached in a small `DeviceContext` snapshot.

**5. Past the window: hold instead of cancel.**
Today the window's expiry calls `bar.reset()` (cancel). Instead: if no words came back, stop quietly (as now); if words came back, stop listening, keep the text in the pill with a "Tap to add" state (the existing held-note mechanism used by "Extra care"), and discard it when the field closes.

**6. Profiles reuse the voiceprint's storage and enrolment UI.**
The whisper profile records ~20–30 s of whispered phrases with the same screen flow as the voiceprint; it stores the whisper's typical level, voicing ratio and tilt (a handful of floats), used to centre the detector's thresholds. Refined from hand-started dictations classified as whispers, like `learnTypical`.

**7. Settings: one new group, existing prefs moved by reference.**
The group lives on the Voice typing page (or its own page if it grows). "Only my voice" and "Ignore children's voices" keep their pref keys; only their place in the UI changes.

## Risks / Trade-offs

- [Phone noise suppression attenuates whispers on some devices] → Whisper detection also accepts a whisper-profile match at lower levels; log the levels so it can be tuned per device.
- [Breathy half-whisper falls between whisper and voice] → It's treated as quiet speech and goes through the loudness rules (with the dark-room lowering); the hold-in-pill fallback catches misses.
- [A TV or video with clear speech while the phone is held] → Voiced speech still needs the loudness rule; only whispers get the quiet pass, and TV whispers are rare. Hold-in-pill means a mistake needs a tap, not a message sent.
- [Accessibility companion off] → The tap signal is absent; the rest still work.
- [Sensor quirks: proximity reporting near when held close to the face, light sensors under dark themes] → Pocket requires both near and dark; tune with logged readings.
- [More states for the user to understand] → Defaults work untouched; the log explains every decision.

## Migration Plan

Ship behind defaults that reproduce today's behavior for loud speech; each new signal is independently switchable. Existing prefs keep their keys. Rollback is turning the new signals off (or reverting the release); no data migration is needed beyond the new profile prefs.

## Open Questions

- Do the weights in Decision 1 hold up on real recordings? Tune from a small set of the user's whispered and soft-spoken phrases recorded on their phone.
- Does the user's phone (a foldable) apply noise suppression to the VOICE_RECOGNITION source that hurts whispers?
- Should a dark room plus held also shorten `LOUD_FOR_MS`, or only lower the bar?
- Own settings page, or a group on Voice typing?
