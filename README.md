# Bubble Keyboard

An Android keyboard that is only a bubble. Tap it to open a ring of keys; hold **abc** to fan out the letters.

- **Bubble:** drag to move (remembered per orientation), tap to open the ring, long-press to switch keyboards.
- **Ring:** arrows and backspace (repeat while held), space, enter, abc. Tap the center to fold back to the bubble; folds by itself after 3 s idle.
- **Blooms:** hold abc for letters, space for punctuation, enter for numbers. Slide to a key and let go to type it; slide back to cancel. Tapping abc opens a letter bloom that waits for one tap. Keys swell and part around your finger so the neighbours stay visible; the key under your finger shows in a bubble above it. Near an edge the fan turns toward the middle of the screen, flips near the top, and is nudged to stay on screen.
- **Caps lock:** keep holding abc still for about a second (weak buzz, then strong). Do it again to turn it off (one weak buzz).
- Only the bubble takes touches; the app behind is never resized. Colors follow Material You on Android 12+.

Settings (open the app): bubble size, ring size, rotate-to-fit.

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # Robolectric tests for the bubble's gestures
```

Needs JDK 17+ and the Android SDK (platform 37). minSdk 26.
