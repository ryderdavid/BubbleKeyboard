## ADDED Requirements

### Requirement: Voice profile
The system SHALL keep a voice profile made of the user's existing voiceprint (when recorded) and their usual dictation level, refined from dictations they start by hand, as today.

#### Scenario: Learning the usual level
- **WHEN** the user finishes a dictation they started by hand
- **THEN** the usual level moves toward that dictation's louder moments, as it does today

### Requirement: Whisper profile
The system SHALL let the user record a whisper profile once (reading a few phrases in a whisper, like recording the voiceprint), storing their typical whisper level and sound, and SHALL refine it from whispered dictations they start by hand. Without a whisper profile, whisper detection SHALL still work with built-in defaults.

#### Scenario: Recording the whisper profile
- **WHEN** the user records their whisper profile
- **THEN** their whisper level and sound are kept on the phone and used to tune whisper detection

#### Scenario: No profile recorded
- **WHEN** the user never records a whisper profile
- **THEN** whispers are still detected using the defaults

### Requirement: Profiles stay on the phone and can be reset
Profiles SHALL be stored only on the phone, never uploaded, and each SHALL have a reset that returns it to the defaults.

#### Scenario: Reset
- **WHEN** the user resets the whisper profile
- **THEN** its recorded level and sound are deleted and the defaults apply
