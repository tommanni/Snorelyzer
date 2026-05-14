package com.example.snorelyzer.ml

import android.content.Context
import org.jtransforms.fft.DoubleFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

class AudioProcessor(context: Context) {
    private val nMels = 128
    private val nFft = 1024
    private val nFreqBins = nFft / 2 + 1 // 513
    private val winLength = 800
    private val hopSize = 320
    private val expectedFrames = 1000
    private val padLength = nFft / 2 // 512

    private val fft = DoubleFFT_1D(nFft.toLong())
    private val window = DoubleArray(winLength)
    private val melBasis = FloatArray(nMels * nFreqBins)

    init {
        // Pre-compute Hann window (periodic=False)
        for (i in 0 until winLength) {
            window[i] = 0.5 - 0.5 * cos(2.0 * PI * i / (winLength - 1))
        }

        // Load mel basis matrix from assets
        context.assets.open("mel_basis.bin").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in melBasis.indices) {
                melBasis[i] = buffer.float
            }
        }
    }

    fun process(audioData: FloatArray): FloatArray {
        require(audioData.size == 320000) { "Expected 320,000 samples, got ${audioData.size}" }

        // 1. Pre-emphasis
        val preemphasized = FloatArray(audioData.size)
        preemphasized[0] = audioData[0]
        for (i in 1 until audioData.size) {
            preemphasized[i] = audioData[i] - 0.97f * audioData[i - 1]
        }

        // 2. Pad
        val paddedLen = audioData.size + padLength * 2
        val paddedAudio = FloatArray(paddedLen)
        System.arraycopy(preemphasized, 0, paddedAudio, padLength, preemphasized.size)
        for (i in 0 until padLength) {
            paddedAudio[padLength - 1 - i] = preemphasized[i + 1]
            paddedAudio[padLength + preemphasized.size + i] = preemphasized[preemphasized.size - 2 - i]
        }

        val powerFrames = DoubleArray(expectedFrames * nFreqBins)
        val fftBuffer = DoubleArray(nFft)

        // 3. STFT
        for (frame in 0 until expectedFrames) {
            val startInOriginal = frame * hopSize
            val startInPadded = startInOriginal + padLength - (winLength / 2)
            val offset = (nFft - winLength) / 2

            for (i in 0 until nFft) fftBuffer[i] = 0.0
            for (i in 0 until winLength) {
                fftBuffer[offset + i] = paddedAudio[startInPadded + i].toDouble() * window[i]
            }

            fft.realForward(fftBuffer)

            val frameOffset = frame * nFreqBins
            powerFrames[frameOffset + 0] = (fftBuffer[0] * fftBuffer[0])
            powerFrames[frameOffset + nFreqBins - 1] = (fftBuffer[1] * fftBuffer[1])
            for (k in 1 until nFreqBins - 1) {
                val re = fftBuffer[2 * k]
                val im = fftBuffer[2 * k + 1]
                powerFrames[frameOffset + k] = (re * re + im * im)
            }
        }

        // 4. Mel Projection & Normalization
        val melSpec = FloatArray(expectedFrames * nMels)
        for (f in 0 until expectedFrames) {
            val frameOffset = f * nFreqBins
            for (m in 0 until nMels) {
                val melRowOffset = m * nFreqBins
                var sum = 0.0
                for (k in 0 until nFreqBins) {
                    sum += melBasis[melRowOffset + k] * powerFrames[frameOffset + k]
                }

                // 1. Natural Log with 1e-5 epsilon
                val lnMel = ln(sum + 0.00001)

                // 2. Fast Normalization (+4.5 / 5.0)
                val normMel = (lnMel + 4.5) / 5.0

                // Indexing for [128, 1000] layout
                melSpec[m * expectedFrames + f] = normMel.toFloat()
            }
        }

        return melSpec
    }
}