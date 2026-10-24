## ADDED Requirements

### Requirement: The page presents Voicebar 1.5 as it is
The page SHALL describe the current app (1.5): talking and live words, cleanup at pauses, spoken formatting, finishing and sending by tap or swipe, the last read before sending, Fix last dictation, the two own keys, Voicebar's keyboard with swipe typing, the cursor trackpad, "voice bar" commands, Gboard alongside, on-phone speech (Parakeet) and the voice filters, and where words go. It MUST NOT describe features the app no longer has.

#### Scenario: Visitor reads the features
- **WHEN** a visitor scrolls the page
- **THEN** every feature shown matches 1.5's behavior and naming (e.g. "Fix last dictation" is an own-key choice, not a hold on Voice)

#### Scenario: Real bar imagery
- **WHEN** a feature shows the bar in a still image
- **THEN** the image is a render of the real DockView from the app's render tests, in light or dark to match the visitor's scheme

### Requirement: Material You color
The page SHALL take its colors from Material 3 tonal-spot schemes generated from a seed color, using M3 color roles (primary, secondary and tertiary containers, surface containers, outline). The default seed MUST produce the app's fixed palette (primary `#415F91`). Light and dark schemes MUST follow the visitor's `prefers-color-scheme`.

#### Scenario: Default look
- **WHEN** a visitor opens the page with no saved choice
- **THEN** the page uses the Voicebar blue scheme in the system's light or dark mode

#### Scenario: Dark mode
- **WHEN** the visitor's system is in dark mode
- **THEN** every surface, text and the hero bar use the dark scheme's roles with WCAG AA contrast for body text

### Requirement: Wallpaper re-toning
The page SHALL offer a small set of wallpaper swatches that re-tone the whole page, including the hero bar, from that wallpaper's seed, the way Android 12+ re-tones Voicebar. The hero bar MUST use the same system tones the app maps for its roles. The choice MAY be remembered in the visitor's browser and MUST work without it.

#### Scenario: Picking a wallpaper
- **WHEN** a visitor picks a wallpaper swatch
- **THEN** the page and hero bar change to that seed's scheme with a short color transition, and the swatch shows as selected

#### Scenario: Storage unavailable
- **WHEN** browser storage throws or is empty
- **THEN** the page renders in the default scheme without errors

### Requirement: Animated hero
The hero SHALL show a phone with a chat and the bar going through a dictation: Voice is tapped, the pill listens with words appearing in the box, it polishes, the tidy text replaces the raw words, and the message is sent. It MUST loop, pause when off screen, and show a still final state when the visitor prefers reduced motion.

#### Scenario: Reduced motion
- **WHEN** `prefers-reduced-motion: reduce` is set
- **THEN** the hero shows a single still state and no looping animation runs

### Requirement: Video and download
The page SHALL embed the 1:43 narrated welcome video with a poster frame, loading it only on demand, and SHALL link Download to the latest GitHub release.

#### Scenario: Downloading
- **WHEN** a visitor presses Download
- **THEN** they reach the latest release on GitHub, with the version shown on the button matching that release

### Requirement: Accessible and fast
The page SHALL be a single static HTML file plus assets in `docs/`, with semantic landmarks, a skip link, visible focus, alt text for images, keyboard-operable controls, no horizontal scroll at 360 px wide, and no third-party scripts. Images MUST be compressed (WebP with sizes set) and the video MUST NOT load before it is played.

#### Scenario: Phone width
- **WHEN** the page is viewed 360 px wide
- **THEN** all content fits with a 16 px side gutter and no horizontal scrolling

#### Scenario: Keyboard only
- **WHEN** a visitor tabs through the page
- **THEN** every link, button and swatch is reachable with a visible focus ring, in reading order
