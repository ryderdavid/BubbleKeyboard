# Bubble Keyboard

An Android keyboard that is only a bubble. Tap it to open a ring of keys; hold **abc** to fan out the letters.

- **Bubble:** drag to move (remembered per orientation), tap to open the ring, long-press to switch keyboards.
- **Ring:** arrows and backspace (repeat while held), space, enter, abc. Tap the center to fold back to the bubble; folds by itself after 3 s idle.
- **Bloom:** hold abc, slide to a letter, let go to type it; slide back to abc to cancel. Tapping abc opens a bloom that waits for one tap. Keys magnify near your finger. Near an edge the fan turns toward the middle of the screen, flips near the top, and is nudged to stay on screen.
- Only the bubble takes touches; the app behind is never resized. Colors follow Material You on Android 12+.

Settings (open the app): bubble size, ring size, rotate-to-fit.

## Build

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # Robolectric tests for the bubble's gestures
```

Needs JDK 17+ and the Android SDK (platform 37). minSdk 26.
