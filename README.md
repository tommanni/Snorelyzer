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

- **Custom audio processing:** Processes 32 kHz audio in a ten-second sliding window. FFT and Mel spectrogram preprocessing converts sound into model input, reusing buffers and previously computed frames.
- **On-device audio classifier:** Google LiteRT runs a dynamic-range quantized TensorFlow Lite export of `mn10_as_mels_64`, a MobileNetV3 model trained on AudioSet. The app uses selected model outputs to detect snoring, gasping, coughing, and sleep talking. [Bundled model](app/src/main/assets/efficientat.tflite) · [Upstream EfficientAT models](https://github.com/fschmid56/EfficientAT#pre-trained-models)
- **Adaptive detection:** Uses changes in sound energy and background noise to schedule inference. Periodic checks and classifier feedback help protect quiet or ongoing events from being treated as background.
- **Event-based recording:** A rolling buffer preserves audio from before a detection. The recorder groups sounds, trims outer background audio, and writes WAV clips with aligned event timestamps.
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
