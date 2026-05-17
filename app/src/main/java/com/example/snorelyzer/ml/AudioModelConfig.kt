package com.example.snorelyzer.ml

object AudioModelConfig {
    const val MODEL_ASSET = "efficientat.tflite"
    const val MEL_BASIS_ASSET = "mel_basis.bin"

    const val N_MELS = 64
    const val N_FFT = 1024
    const val N_FREQ_BINS = N_FFT / 2 + 1
    const val WIN_LENGTH = 800
    const val HOP_SIZE = 320
    const val EXPECTED_FRAMES = 1000
    const val EXPECTED_SAMPLES = 320000

    const val FLOAT_BYTES = 4
    const val MEL_TENSOR_SIZE = N_MELS * EXPECTED_FRAMES
    const val MEL_TENSOR_BYTES = MEL_TENSOR_SIZE * FLOAT_BYTES
    const val MEL_BASIS_BYTES = N_MELS * N_FREQ_BINS * FLOAT_BYTES
}
