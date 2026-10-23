## ADDED Requirements

### Requirement: Pocket detection
The system SHALL judge the phone to be in a pocket or bag when the proximity sensor reports something covering the screen and the ambient light is dark. Light alone SHALL NOT mean pocketed (a dark bedroom is not a pocket).

#### Scenario: In a pocket
- **WHEN** proximity reports near and the light level is dark
- **THEN** the phone is judged pocketed

#### Scenario: Dark bedroom, phone in hand
- **WHEN** the light level is dark but proximity reports far
- **THEN** the phone is not judged pocketed, and the room is judged dark

### Requirement: Held or lying still
The system SHALL judge whether the phone is being held (small continual hand movement) or lying still (a table, a stand), from the motion sensor.

#### Scenario: On a table
- **WHEN** the motion sensor shows no movement beyond sensor noise for the last few seconds
- **THEN** the phone is judged lying still

#### Scenario: In hand
- **WHEN** the motion sensor shows the small irregular movement of a hand
- **THEN** the phone is judged held

### Requirement: Fold state on foldables
On foldable phones, the system SHALL know whether the phone is open (unfolded) or closed, and treat open with the phone held as evidence of deliberate use. On phones without a hinge, this signal SHALL simply be absent.

#### Scenario: Unfolded
- **WHEN** a foldable is open and held as an auto-started dictation begins
- **THEN** that counts toward engagement

### Requirement: Sensors only while needed
Sensors SHALL be registered only from the start of an auto-started dictation until it is engaged or ends, using on-change reporting where the platform offers it, and SHALL be released afterwards. No sensor SHALL run while no dictation is starting.

#### Scenario: Idle keyboard
- **WHEN** the keyboard is open but no dictation is starting or running
- **THEN** no proximity, light, motion or hinge sensor is registered

#### Scenario: Missing sensor
- **WHEN** a phone lacks one of these sensors
- **THEN** that signal is absent and the decision uses the rest
