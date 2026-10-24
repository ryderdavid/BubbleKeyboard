## Context

GitHub Pages already serves `main:/docs` at ryder.works/Voicebar. The current page is a 41 KB hand-written HTML file from the 1.3 era with a CSS recreation of the old bar. The app is a Material 3 Android keyboard: on Android 12+ its roles map onto the system dynamic palette (`values-v31/colors.xml`), and before that it uses a fixed tonal-spot palette from the baseline blue seed (primary `#415F91`). The app's render tests produce real PNGs of the bar (`app/build/previews/`), and `video/` produced the new 1:43 welcome video.

## Goals / Non-Goals

**Goals:**
- A page that reads as a Material You product: tonal surfaces, the M3 shape scale (extra-large 28 px cards, full pills), Google Sans Flex, M3 motion easing.
- Colors that come from the same math Android uses, so the page and the app agree.
- No build step for the page itself; one small generator for color tokens.

**Non-Goals:**
- A framework, bundler or CMS; analytics; a custom domain change; any app change.

## Decisions

- **Plain HTML/CSS with a little JS** over a static-site generator: one page, one maintainer, Pages serves it as is. Alternative (Astro/Jekyll) adds a toolchain for no gain.
- **Generated color tokens** (`tools/site/colors.mjs` → `docs/colors.css`): `@material/material-color-utilities` `SchemeTonalSpot` (Android's default dynamic scheme) for each seed, light and dark, written as CSS custom properties under `[data-seed="…"]`. Hand-picking hexes would drift from Android's output. The bar's roles are emitted separately from the tonal palettes at the same tones `values-v31` maps (`system_accent1_100` = tone 90, `_600` = 40, `_900` = 10, `neutral1_100` = 90, …), so the hero bar matches the app on that wallpaper.
- **Re-toning with `data-seed` on `<html>`**: switching swaps one attribute; a 400 ms color transition on surfaces gives the Material You "re-tone" moment. The choice is kept in `localStorage` inside try/catch.
- **Hero bar drawn in HTML/CSS** rather than the real PNG frames: it has to re-tone live and animate cheaply. Its geometry follows the real seven-button bar render; feature sections use the real renders (WebP, light and dark via `<picture>`), so nothing claims more than the app does.
- **Hero animation as a timeline of CSS classes** driven by one small script, with an IntersectionObserver to pause off screen and a reduced-motion still state.
- **Video**: `docs/welcome.mp4` replaced by the app's crf-27 encode (3.4 MB), `preload="none"` with a WebP poster.
- **Type**: Google Sans Flex from Google Fonts (variable `wght`, `opsz`, `ROND`), falling back to system-ui; Material Symbols Rounded for icons, subset with `icon_names`.

## Risks / Trade-offs

- [The CSS hero bar drifts from the real bar as the app changes] → the page's stills come from render tests; the hero is reviewed against `light-01-idle.png` when the bar changes.
- [Google Fonts unavailable] → system-ui fallback with matched metrics is acceptable; icons are also given text labels.
- [Old render PNGs show retired UI] → re-run `-Prender` before copying images, and only use states that exist in 1.5.

## Migration Plan

Replace `docs/index.html` and `docs/welcome.mp4` in one commit on `main`; Pages rebuilds in about a minute. Rollback is reverting that commit.

## Open Questions

- Whether to also refresh the repo description and social preview image (outward-facing; ask first).
