package com.example.snorelyzer.ml

import com.example.snorelyzer.MicrophoneCapture

object AudioModelConfig {
    const val MODEL_ASSET = "fmn06_strong_int8.tflite"

    const val WINDOW_SECONDS = 10
    const val SAMPLE_RATE = 16_000
    const val CAPTURE_WINDOW_SAMPLES = WINDOW_SECONDS * MicrophoneCapture.SAMPLE_RATE
    const val MODEL_WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE

    const val N_MELS = 128
    const val N_FFT = 512
    const val WIN_LENGTH = 400
    const val HOP_SIZE = 160
    const val MEL_FRAMES = MODEL_WINDOW_SAMPLES / HOP_SIZE
    const val MEL_TENSOR_SIZE = N_MELS * MEL_FRAMES

    const val OUTPUT_CLASSES = 447
    const val OUTPUT_FRAMES = 250
    const val OUTPUT_FRAME_MILLIS = WINDOW_SECONDS * 1_000L / OUTPUT_FRAMES
    const val OUTPUT_FRAMES_PER_SECOND = OUTPUT_FRAMES / WINDOW_SECONDS
}
