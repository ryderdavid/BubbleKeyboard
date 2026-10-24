## 1. Colors

- [ ] 1.1 Add `tools/site/` with `colors.mjs` generating tonal-spot light/dark roles and bar roles per seed into `docs/colors.css`
- [ ] 1.2 Confirm the default seed's light primary is `#415F91` and its bar roles match `values/colors.xml`

## 2. Assets

- [ ] 2.1 Re-run the render tests and copy the 1.5 states used by the page to `docs/img/` as WebP (light and dark)
- [ ] 2.2 Replace `docs/welcome.mp4` with the new welcome video and add a WebP poster frame

## 3. Page

- [ ] 3.1 Page shell: top app bar, skip link, landmarks, type scale, shape tokens, light/dark
- [ ] 3.2 Hero: headline, Download and Watch buttons, phone with chat and the CSS bar
- [ ] 3.3 Hero animation timeline with off-screen pause and reduced-motion still
- [ ] 3.4 Wallpaper swatches re-toning the page, remembered safely
- [ ] 3.5 Video section with on-demand loading
- [ ] 3.6 Feature sections with real renders
- [ ] 3.7 How it works, privacy, get started, footer

## 4. Check

- [ ] 4.1 Check in a browser at 360 px, tablet and desktop, light and dark, each wallpaper, keyboard-only and reduced motion
- [ ] 4.2 Validate the change with `openspec validate product-page`, commit, and confirm Pages rebuilt
