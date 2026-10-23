## 1. Groundwork

- [ ] 1.1 Record a reference set on the user's phone: whispered phrases, soft voiced phrases, normal speech, room talk across the room, a fan; keep them as test fixtures (16 kHz PCM)
- [ ] 1.2 Log the raw per-stretch features (level, noise floor, voiced ratio, tilt, modulation) for auto-started dictations, so tuning can use real data

## 2. Whisper detection

- [ ] 2.1 Add per-frame features next to the child filter's pitch analysis: speech-band energy vs running noise floor, voicing score, spectral tilt
- [ ] 2.2 Add the stretch classifier (syllable-rate modulation, voiced ratio, tilt) and unit-test it on the fixtures: whispers yes, soft voiced no, fan no
- [ ] 2.3 Require recognized words to confirm a whisper; test with a fake recognizer
- [ ] 2.4 End the child filter's trailing hold early when whispering follows; test a child-then-whisper fixture

## 3. Device context signals

- [ ] 3.1 Add a `DeviceContext` reader: proximity and light (pocket, dark), accelerometer variance (held/still), hinge or fold state; registered only for an auto-started dictation's decision
- [ ] 3.2 Unit-test the pocket, dark, held and still judgments from recorded sensor sequences; test that sensors are released when engaged or ended
- [ ] 3.3 Treat missing sensors as absent signals; test on a phone without a hinge

## 4. Box-tapped signal

- [ ] 4.1 In the accessibility companion, note a tap on an editable view and hand it to the keyboard when that box opens it within about 1 s
- [ ] 4.2 Test: a tapped box engages at once; a self-focused box does not

## 5. Engagement decision

- [ ] 5.1 Turn `AutoListenGuard` into a scored `Engagement` with the weights and veto from the design; each switch-off sets its weight to 0
- [ ] 5.2 Dark room with the phone held lowers the loudness bar (wider margin, lower floor)
- [ ] 5.3 Unit-test the spec scenarios: tap, late-night whisper, quiet talk across the room, pocket veto, soft voice in the dark, all signals off equals today's behavior
- [ ] 5.4 Log the engaging evidence, or the strongest evidence and what was missing

## 6. Hold instead of drop

- [ ] 6.1 At the window's end with words heard: stop listening, keep the words in the pill as "Tap to add"; discard when the box closes
- [ ] 6.2 Test both outcomes (words heard, nothing heard) and render the pill state

## 7. Profiles

- [ ] 7.1 Whisper profile enrolment (reuse the voiceprint flow): record, store level, voiced ratio and tilt; reset
- [ ] 7.2 Centre the whisper thresholds on the profile when present; refine it from hand-started whispered dictations
- [ ] 7.3 Tests for record, refine, reset and the no-profile defaults

## 8. Settings

- [ ] 8.1 Add the Advanced speech isolation group: voice profile with Only my voice, whisper profile, Ignore children's voices, and a switch (and strength where it applies) per signal
- [ ] 8.2 Move the existing voice filter rows there, keeping their pref keys and values; test an upgrade keeps them
- [ ] 8.3 Help text and README; render the new settings group

## 9. Verify on the phone

- [ ] 9.1 Late-night whisper into a tapped box engages and types; room talk with the phone on the table doesn't; pocketed never does
- [ ] 9.2 Check the battery: no sensors registered while idle (dumpsys sensorservice)
