## ADDED Requirements

### Requirement: Whispered speech is told apart from quiet voiced speech
The system SHALL classify a stretch of speech as whispered when, over at least about 300 ms, the speech-band energy rises and falls at syllable rate (about 3–8 Hz) above the room's background, the high-energy moments have no detectable pitch, and the energy is tilted toward high frequencies. Quiet voiced speech (soft talking, distant talking) SHALL NOT be classified as whispered.

#### Scenario: A whispered sentence
- **WHEN** the user whispers a sentence close to the phone
- **THEN** the stretch is classified as whispered

#### Scenario: Soft voiced talking
- **WHEN** someone talks softly with their voice (vowels have a pitch)
- **THEN** the stretch is not classified as whispered, even if it is quieter than the whisper

#### Scenario: Unvoiced consonants in normal speech
- **WHEN** normal speech contains "s", "sh", "f", "h", "t" or "k" sounds
- **THEN** those alone do not make the stretch whispered, because its vowels are voiced

### Requirement: Steady noise is not a whisper
Steady pitchless noise (a fan, air conditioning, rain, a shower) SHALL NOT be classified as whispered, because it lacks syllable-rate modulation and the recognizer returns no words for it.

#### Scenario: A fan in a quiet bedroom
- **WHEN** a fan runs and nobody speaks
- **THEN** no whisper is detected and nothing engages

### Requirement: Whisper detection is cheap and local
Whisper detection SHALL run on the phone from audio Voicebar already reads, reusing the child filter's pitch analysis where possible, only during the first seconds of auto-started dictations, with no added network calls.

#### Scenario: No extra cost outside auto-listen
- **WHEN** a dictation is started by hand, or no dictation is running
- **THEN** the whisper detector does no work

### Requirement: Whispers pass the child filter
The child filter SHALL only treat pitched (voiced) audio as a child's voice, so whispers keep passing through it, and its trailing hold after a child's voice SHALL end early when whispered speech follows.

#### Scenario: Whisper right after a child talks
- **WHEN** "Ignore children's voices" is on, a child talks, and the user whispers immediately after
- **THEN** the whisper reaches the recognizer unclipped
