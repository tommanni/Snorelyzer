# Snorelyzer

**Snorelyzer** is an AI-powered Android sleep audio tracker that detects, classifies, and records sleep sound events (snoring, coughing, gasping, sleep talking) completely on-device.

---

## ✨ Features

- **On-Device AI Classification**: Real-time sleep sound identification using LiteRT (TensorFlow Lite).
- **Intelligent Audio Gating**: Analyzes audio signals to run ML inference efficiently and save battery.
- **Episode Audio Clip Recording**: Automatically records and trims WAV audio clips for detected sleep events.
- **Background Tracking**: Reliable foreground service execution with hold-to-stop tracking safety controls.
- **Sleep Insights & Persistence**: Stores sessions, episodes, and event timelines locally using Room DB.

---

## 🛠️ Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3), Navigation Compose
- **Machine Learning & DSP**: LiteRT (TensorFlow Lite), JTransforms FFT (Mel Spectrograms)
- **Dependency Injection**: Koin
- **Database**: Room DB
- **Asynchrony**: Coroutines & StateFlow
- **Background Execution**: Android Foreground Service (`AudioRecord`)

---

## 🚀 Getting Started

### Prerequisites
- Android Studio
- JDK 11+
- Android Device or Emulator with microphone input (API 26+)

### Build & Run
1. Open the project in Android Studio.
2. Build and run on a physical device or emulator.

---

## 🧪 Testing

Run all unit tests:
```bash
./gradlew testDebugUnitTest
```
