# Wheel Movement Tracker v2.5

Screen-based Android tracker for a clockwise 9-sector wheel.

The configured clockwise labels are:

`77 → w1 → m1 → w2 → m2 → w3 → m3 → w4 → m4`

## Included

- Records movement as clockwise values `0..8` and displays the equivalent reverse value: `1/-8`, `2/-7`, …, `8/-1`.
- Saves the game round number, an independent sequence number, start sector, winner sector and winner name.
- Analyzes numeric movement patterns and named-sector/fruit patterns separately.
- Adapts Frequency, Sequence, Delta and Cycle model weights from recent back-tests.
- Shows all nine movement/name probabilities and handles each `+n/-equivalent` pair as one modulo-9 result.
- Converts movement probabilities into destination fruits, groups named-sector probabilities by fruit, and averages both analyses into one final Mango/Watermelon/77 percentage.
- Lets the user select a rollback window of 10, 20, 30, 50, 100, 200, 500 or all saved rounds.
- Reports repeated patterns, counts and prediction W/L results after the learning history is available.
- Shows the latest round, sequence, winner, movement, next predictions and W/L totals in a compact floating overlay.
- Starts as a small two-line bar in the safe upper screen area, away from the wheel pointer.
- Expands into a fixed-height scrollable analysis panel instead of growing over the wheel.
- Provides a bottom-right drag grip for manual width and height resizing; the selected size is saved.
- Keeps the overlay inside the upper 25% safe band and outside the detector's wheel-ring scan region.
- Keeps text in stable fixed-line rows and allows every section to be expanded or minimized independently.
- Includes a global mini mode that leaves only a one-line round, result and final-fruit summary visible.
- Moves only from the dedicated drag handle, clamps to the screen edges, and throttles dragging to display frames for smooth stable movement.
- Exports all saved round data and settings as JSON, then imports it using either duplicate-safe Merge or full Replace mode.
- Uses a compact title-and-buttons-only main screen; analysis is kept in a separate scrollable report with five recent records.
- Allows custom scan counts from 1–2,000 (or all), minimum forecast percentage from 0–100%, and minimum sample size from 1–2,000.
- Tracks eligible fruit-forecast wins/losses and simulated flat-stake profit percentage using x2 Mango/Watermelon and x8 77.
- Verifies the pointer fruit from the green 77 wedge; the supplied recording confirms w1, m1 and 77 for rounds 2049–2051.
- Saves at most one result per recognized round and rejects the wheel-reset transition at the start of a new round.
- Calculates the reported jump from the detected start and winner positions, avoiding full-rotation alias errors.
- Auto-calibrates the wheel scan region near the expected portrait layout.
- Reads the visible game screen locally with Android screen capture and OCR for the round number.
- Does not save or display spin duration/time.
- Uses rolling analysis over the last 5, 10, 20, 50 and all saved rounds.
- Stores up to 2,000 rounds locally.
- Provides a full shareable text report with numeric and named-sector counts, repeated patterns and historical hit rates.

## Important

The displayed percentages are normalized historical pattern scores. They are not guaranteed probabilities and cannot make a genuinely random wheel predictable.

## Build

The project includes the Gradle wrapper. Open the folder containing `settings.gradle` and `gradlew`, then run:

```bash
chmod +x gradlew
./gradlew assembleDebug
```

The verified installable APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`

The release build is also configured to use the local debug signing key for testing:

```bash
./gradlew assembleRelease
```
