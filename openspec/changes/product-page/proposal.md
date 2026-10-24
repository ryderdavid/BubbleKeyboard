## Why

Voicebar's page at ryder.works/Voicebar (`docs/` on `main`) was written for 1.3: it describes a bar that no longer exists (no own keys, no Fix last dictation, no Voicebar keyboard, no sending check), plays the old 70-second video, and its colors only loosely follow the app. 1.5 is stable and has a fresh narrated video, so the page should present it properly, and look and feel like the Material You app it describes.

## What Changes

- Replace `docs/index.html` with a new single-page product site for Voicebar 1.5, designed to Material 3 (Material You): tonal color roles, the shape scale, Google Sans Flex type, M3 components (top app bar, filled and tonal buttons, cards, chips) and M3 motion.
- Color comes from the app's own seed: the page's default scheme is the same tonal-spot scheme the app uses before Android 12 (primary `#415F91`), in light and dark following the visitor's system setting.
- A "your wallpaper, your colors" control re-tones the whole page from a handful of wallpaper seeds, the way Android 12+ re-tones Voicebar, with the bar's colors taken from the same system tones the app maps (`system_accent1_100` and so on).
- A hero phone shows the bar working (tap Voice, words appear, polishing, the tidy message is sent), drawn in HTML/CSS so it re-tones with the page; feature sections use real renders of the bar from the app's render tests.
- The new 1:43 narrated welcome video replaces the old one.
- Download points at the latest GitHub release.
- Out of scope: a custom domain change, analytics or tracking, a blog or docs site, localisation, a Play Store listing, and changes to the app itself.

## Capabilities

### New Capabilities
- `product-page`: the public product page served by GitHub Pages from `docs/`: its content, Material You theming (default scheme, light/dark, wallpaper re-toning), the animated hero, the video, downloads, accessibility and performance budgets.

### Modified Capabilities
<!-- none: no existing specs -->

## Impact

- `docs/` (index.html, images, video, generated color CSS); Pages already builds from `main` `/docs`.
- A small build script under `tools/site/` that generates the color schemes with `@material/material-color-utilities` (dev-time only; the page itself has no build step and no runtime dependencies beyond Google Fonts).
- The GitHub repo's description still says "floating bubble keyboard"; updating it is a separate, outward-facing step.
