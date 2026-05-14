package com.example.snorelyzer.ml

import android.content.Context
import org.jtransforms.fft.FloatFFT_1D
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
    private val expectedSamples = 320000

    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = FloatArray(winLength)
    private val melBasis = FloatArray(nMels * nFreqBins)

    private val paddedLen = expectedSamples + padLength * 2
    private val paddedAudio = FloatArray(paddedLen)
    private val powerFrames = FloatArray(expectedFrames * nFreqBins) // Changed to FloatArray
    private val fftBuffer = FloatArray(nFft)
    private val melSpec = FloatArray(expectedFrames * nMels)

    init {
        // Pre-compute Hann window (periodic=False)
        for (i in 0 until winLength) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (winLength - 1))).toFloat()
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
        require(audioData.size == expectedSamples) { "Expected 320,000 samples, got ${audioData.size}" }

        // 1 & 2. Pre-emphasis & Padding
        paddedAudio[padLength] = audioData[0]
        for (i in 1 until audioData.size) {
            paddedAudio[padLength + i] = audioData[i] - 0.97f * audioData[i - 1]
        }

        // Reflective padding
        for (i in 0 until padLength) {
            paddedAudio[padLength - 1 - i] = paddedAudio[padLength + i + 1]
            paddedAudio[padLength + expectedSamples + i] = paddedAudio[padLength + expectedSamples - 2 - i]
        }

        // 3. STFT
        for (frame in 0 until expectedFrames) {
            val startInPadded = frame * hopSize + padLength - (winLength / 2)
            val offset = (nFft - winLength) / 2

            // Fast array zeroing
            fftBuffer.fill(0.0f)

            for (i in 0 until winLength) {
                fftBuffer[offset + i] = paddedAudio[startInPadded + i] * window[i]
            }

            fft.realForward(fftBuffer)

            val frameOffset = frame * nFreqBins

            powerFrames[frameOffset + 0] = fftBuffer[0] * fftBuffer[0]
            powerFrames[frameOffset + nFreqBins - 1] = fftBuffer[1] * fftBuffer[1]
            for (k in 1 until nFreqBins - 1) {
                val re = fftBuffer[2 * k]
                val im = fftBuffer[2 * k + 1]
                powerFrames[frameOffset + k] = re * re + im * im
            }
        }

        // 4. Mel Projection & Normalization
        for (f in 0 until expectedFrames) {
            val frameOffset = f * nFreqBins
            for (m in 0 until nMels) {
                val melRowOffset = m * nFreqBins

                var sum = 0.0f
                for (k in 0 until nFreqBins) {
                    sum += melBasis[melRowOffset + k] * powerFrames[frameOffset + k]
                }

                val lnMel = ln(sum + 0.00001f)
                melSpec[m * expectedFrames + f] = (lnMel + 4.5f) / 5.0f
            }
        }

        return melSpec
    }
}