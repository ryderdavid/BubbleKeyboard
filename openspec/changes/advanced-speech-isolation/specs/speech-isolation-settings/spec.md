## ADDED Requirements

### Requirement: An Advanced speech isolation group
Settings SHALL have an "Advanced speech isolation" group holding: the voice profile (with "Only my voice" and its strength), the whisper profile, "Ignore children's voices" and its strength, and a switch for each engagement signal (box tapped, whispering, pocket, held, fold, dark room) with a strength where it applies.

#### Scenario: Finding everything in one place
- **WHEN** the user opens Advanced speech isolation
- **THEN** every voice filter, profile and engagement signal is there, each with its switch

### Requirement: Moving existing settings keeps their values
"Only my voice", "Ignore children's voices", their strengths and the recorded voiceprint SHALL keep their current values when they move into the new group.

#### Scenario: Upgrade
- **WHEN** a user with "Only my voice" on and a recorded voiceprint updates Voicebar
- **THEN** both are still on and recorded in the new group

### Requirement: Defaults work untouched
The defaults SHALL make whispered and soft dictation work for someone who never opens the group: the tap, whisper, pocket, held and fold signals on; dark-room lowering on; the voice filters keep their current defaults (off).

#### Scenario: Fresh install, late at night
- **WHEN** a user who never opened the group taps into a text box in a dark room and whispers
- **THEN** the dictation engages and the words are typed

### Requirement: Turning a signal off removes it from the decision
Each engagement signal's switch SHALL remove that signal from the decision entirely, so the user can fall back to today's loudness-only behavior by turning them all off.

#### Scenario: All signals off
- **WHEN** the user turns every engagement signal off
- **THEN** auto-listen engages on loudness alone, as before this change
