package com.example.snorelyzer.ml

import android.content.Context
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

class AudioProcessor(context: Context) {
    private val nMels = AudioModelConfig.N_MELS
    private val nFft = AudioModelConfig.N_FFT
    private val nFreqBins = AudioModelConfig.N_FREQ_BINS
    private val winLength = AudioModelConfig.WIN_LENGTH
    private val hopSize = AudioModelConfig.HOP_SIZE
    private val expectedFrames = AudioModelConfig.EXPECTED_FRAMES
    private val padLength = nFft / 2 // 512
    private val expectedSamples = AudioModelConfig.EXPECTED_SAMPLES

    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = FloatArray(winLength)
    private val melBasis = FloatArray(nMels * nFreqBins)

    private val paddedLen = expectedSamples + padLength * 2
    private val paddedAudio = FloatArray(paddedLen)
    private val preEmphasizedAudio = FloatArray(expectedSamples)
    private val powerFrames = FloatArray(expectedFrames * nFreqBins) // Changed to FloatArray
    private val fftBuffer = FloatArray(nFft)
    private val melSpec = FloatArray(expectedFrames * nMels)
    private val newMelFrames = FloatArray(expectedFrames * nMels)
    
    private val melStartIndices = IntArray(nMels)
    private val melEndIndices = IntArray(nMels)

    init {
        // Pre-compute Hann window (periodic=False)
        for (i in 0 until winLength) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (winLength - 1))).toFloat()
        }

        // Load mel basis matrix from assets
        context.assets.open(AudioModelConfig.MEL_BASIS_ASSET).use { inputStream ->
            val bytes = inputStream.readBytes()
            require(bytes.size == AudioModelConfig.MEL_BASIS_BYTES) {
                "Expected ${AudioModelConfig.MEL_BASIS_ASSET} to be " +
                    "${AudioModelConfig.MEL_BASIS_BYTES} bytes for $nMels mel bins, got ${bytes.size}"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in melBasis.indices) {
                melBasis[i] = buffer.float
            }
        }

        // Pre-compute sparse indices for mel basis
        for (m in 0 until nMels) {
            val melRowOffset = m * nFreqBins
            var firstNonZero = -1
            var lastNonZero = -1
            
            for (k in 0 until nFreqBins) {
                if (melBasis[melRowOffset + k] > 0.0f) {
                    if (firstNonZero == -1) firstNonZero = k
                    lastNonZero = k
                }
            }
            
            // Fallback in case a row is entirely zero (unlikely but safe)
            if (firstNonZero == -1) {
                firstNonZero = 0
                lastNonZero = 0
            }
            
            melStartIndices[m] = firstNonZero
            melEndIndices[m] = lastNonZero
        }
    }

    private var isFirstRun = true
    private val framesPerStep = 100 // 1 second of audio at 32kHz with 320 hopSize
    private val stepSamples = framesPerStep * hopSize

    fun reset() {
        isFirstRun = true
    }

    fun process(audioData: FloatArray): FloatArray {
        require(audioData.size == expectedSamples) { "Expected 320,000 samples, got ${audioData.size}" }

        // 1 & 2. Pre-emphasis & padding
        if (isFirstRun) {
            computeFullPreEmphasis(audioData)
        } else {
            updatePreEmphasisTail(audioData)
        }

        // Determine how many frames we need to compute
        val startFrame = if (isFirstRun) 0 else expectedFrames - framesPerStep

        if (!isFirstRun) {
            // Shift existing melSpec left by framesPerStep (100)
            for (m in 0 until nMels) {
                val melRowStart = m * expectedFrames
                System.arraycopy(
                    melSpec,
                    melRowStart + framesPerStep,
                    melSpec,
                    melRowStart,
                    expectedFrames - framesPerStep
                )
            }
        }

        // 3. STFT (Compute only new frames)
        for (frame in startFrame until expectedFrames) {
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

        // 4. Mel Projection & Normalization (Compute only new frames)
        for (f in startFrame until expectedFrames) {
            val frameOffset = f * nFreqBins
            val melFrameOffset = (f - startFrame) * nMels
            for (m in 0 until nMels) {
                val melRowOffset = m * nFreqBins
                val startIdx = melStartIndices[m]
                val endIdx = melEndIndices[m]

                var sum = 0.0f
                for (k in startIdx..endIdx) {
                    sum += melBasis[melRowOffset + k] * powerFrames[frameOffset + k]
                }

                val lnMel = ln(sum + 0.00001f)
                newMelFrames[melFrameOffset + m] = (lnMel + 4.5f) / 5.0f
            }
        }

        for (f in startFrame until expectedFrames) {
            val melFrameOffset = (f - startFrame) * nMels
            for (m in 0 until nMels) {
                melSpec[m * expectedFrames + f] = newMelFrames[melFrameOffset + m]
            }
        }

        isFirstRun = false
        return melSpec
    }

    private fun computeFullPreEmphasis(audioData: FloatArray) {
        preEmphasizedAudio[0] = audioData[0]
        for (i in 1 until expectedSamples) {
            preEmphasizedAudio[i] = audioData[i] - 0.97f * audioData[i - 1]
        }
        System.arraycopy(preEmphasizedAudio, 0, paddedAudio, padLength, expectedSamples)
        refreshReflectivePadding()
    }

    private fun updatePreEmphasisTail(audioData: FloatArray) {
        val retainedSamples = expectedSamples - stepSamples

        System.arraycopy(preEmphasizedAudio, stepSamples, preEmphasizedAudio, 0, retainedSamples)

        for (i in retainedSamples until expectedSamples) {
            preEmphasizedAudio[i] = audioData[i] - 0.97f * audioData[i - 1]
        }

        System.arraycopy(paddedAudio, padLength + stepSamples, paddedAudio, padLength, retainedSamples)

        System.arraycopy(
            preEmphasizedAudio,
            retainedSamples,
            paddedAudio,
            padLength + retainedSamples,
            stepSamples
        )
        refreshReflectivePadding()
    }

    private fun refreshReflectivePadding() {
        for (i in 0 until padLength) {
            paddedAudio[padLength - 1 - i] = paddedAudio[padLength + i + 1]
            paddedAudio[padLength + expectedSamples + i] = paddedAudio[padLength + expectedSamples - 2 - i]
        }
    }
}
