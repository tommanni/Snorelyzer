# Snorelyzer

**Snorelyzer** is a PoC Android app that listens for sleep audio events, such as snoring, coughing, gasping, and sleep talking, saves relevant audio clips, and helps users explore patterns across nights. Sound detection runs entirely on the device, with recordings and history stored locally.

## What it does

- Tracks sleep sounds in the background, with hold-to-stop controls.
- Automatically groups detected sounds into playable audio episodes.
- Organizes recordings by night and session, with analytics for reviewing patterns over time.

## Under the hood

```text
Microphone → Adaptive audio gate → On-device ML → Recorded episodes → Playback & analytics
```

- **Custom audio processing:** Captures at 32 kHz and resamples each ten-second inference window to 16 kHz. The frontend converts it into 128 normalized log-mel bands using EfficientSED's preprocessing.
- **On-device audio classifier:** Google LiteRT runs a dynamic-range INT8 export of EfficientSED's `fmn06_strong`, trained on AudioSet Strong. The app uses 40 ms frame scores for snoring, gasping, coughing, and speech-related event spans. Inference windows advance by at least two seconds, with one second of lookahead for those spans. [Bundled model](app/src/main/assets/fmn06_strong_int8.tflite) · [Upstream EfficientSED checkpoint](https://github.com/theMoro/EfficientSED#checkpoints-of-models-trained-on-audioset-strong)
- **Adaptive detection:** Uses changes in sound energy and background noise to schedule inference. Periodic checks and classifier feedback help protect quiet or ongoing events from being treated as background.
- **Event-based recording:** A rolling buffer preserves audio from before a detection. The recorder saves each episode from its first to last detected event with one second of context on both sides and a three-second minimum when enough audio is available. Clips use 48 kb/s mono Opus with aligned event timestamps. Android 8–9 and devices that encounter an encoding error save WAV clips.
- **Structured persistence:** Room relates sessions, episodes, sound groups, and event spans. Nights are derived from session dates, keeping overnight recordings together.
- **Separated responsibilities:** A foreground service captures audio; a session controller coordinates processing, saving, discarding, and cleanup. Compose screens observe ViewModel state through a unidirectional flow, with type-safe navigation and Koin dependency injection.

## Tech stack

**Kotlin · Jetpack Compose · Material 3 · Coroutines & StateFlow · Koin · Room/KSP · Google LiteRT · JTransforms · Media3**

## Build & test

Open in Android Studio with **JDK 21** and **Android SDK 36.1**. Run on an Android device or emulator with microphone input (**API 26+**). Model assets are included in the repository.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest # Requires a connected device or emulator
```

Tests cover audio gating and processing, recording and trimming, session lifecycle, analytics, ViewModels, Room persistence, and Compose navigation.
