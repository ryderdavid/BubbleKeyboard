## ADDED Requirements

### Requirement: Engagement is weighed from several kinds of evidence
For a dictation started automatically (a text box opening with auto-listen on), the system SHALL decide whether the user is dictating to the phone by weighing evidence rather than by loudness alone. The evidence SHALL include at least: the user tapping the text box, detected whispering with recognized words, the phone being open (foldables) and held, speech loud enough near the phone, and quiet voiced speech while the phone lies still (which counts against). A dictation the user started by hand SHALL be engaged from the start, as today.

#### Scenario: A tap on the box is enough
- **WHEN** auto-listen starts because the user tapped a text box
- **THEN** the dictation is engaged at once, as if the user had tapped Voice

#### Scenario: A box that focused itself still needs evidence
- **WHEN** auto-listen starts because an app focused its text box without a tap
- **THEN** the dictation is engaged only once the weighed evidence reaches the engagement level

#### Scenario: Loud speech near the phone still engages
- **WHEN** speech within about 6 dB of the user's usual level continues for at least 200 ms (dips under 400 ms allowed)
- **THEN** the dictation is engaged, as before this change

#### Scenario: Quiet talk across the room does not engage
- **WHEN** the phone lies still and the only speech is quiet and voiced
- **THEN** the dictation is not engaged

### Requirement: Whispering engages
Detected whispering with words coming back from the recognizer SHALL count as strong evidence of engagement, regardless of how quiet it is, because a whisper only reaches the microphone clearly from close by.

#### Scenario: Late-night whisper
- **WHEN** an auto-started dictation hears whispered speech and the recognizer returns words for it
- **THEN** the dictation is engaged and the words go into the text box

### Requirement: A pocket vetoes engagement
When the phone is judged to be in a pocket or bag, an auto-started dictation SHALL NOT be engaged by any other evidence.

#### Scenario: Speech while pocketed
- **WHEN** the proximity sensor reports something covering the screen, it is dark, and speech is heard
- **THEN** the dictation is not engaged and nothing is typed

### Requirement: Darkness with the phone in hand lowers the loudness bar
When the room is dark and the phone is held, the loudness evidence SHALL accept speech further below the user's usual level than in daylight, bounded by a minimum level.

#### Scenario: Soft voice in a dark room
- **WHEN** the room is dark, the phone is held, and the user speaks softly (voiced) below the daytime bar but above the dark-room bar
- **THEN** the dictation is engaged

### Requirement: Clearly heard words are never silently dropped
When the start window passes without engagement, the system SHALL keep the recognized words in the voice pill for the user to accept with a tap, instead of cancelling the dictation and discarding them. Nothing SHALL be typed into the text box without engagement or a tap.

#### Scenario: Missed the bar but heard everything
- **WHEN** the start window ends without engagement and the recognizer has returned words
- **THEN** the words stay in the pill, a tap types them into the box, and leaving the box discards them

#### Scenario: Nothing heard
- **WHEN** the start window ends without engagement and no words came back
- **THEN** the dictation stops quietly, as today

### Requirement: The decision is explained in the log
Each auto-started dictation SHALL log which evidence engaged it, or, when it wasn't engaged, the strongest evidence seen and what was missing.

#### Scenario: Reading why
- **WHEN** a dictation ends unengaged
- **THEN** Settings ▸ Logs shows the loudest level and the bar, whether a whisper or tap was seen, and the sensor readings that applied
