package com.example.snorelyzer.ml

import com.example.snorelyzer.ml.AudioModelConfig.CAPTURE_WINDOW_SAMPLES
import com.example.snorelyzer.ml.AudioModelConfig.HOP_SIZE
import com.example.snorelyzer.ml.AudioModelConfig.MEL_FRAMES
import com.example.snorelyzer.ml.AudioModelConfig.MODEL_WINDOW_SAMPLES
import com.example.snorelyzer.ml.AudioModelConfig.N_FFT
import com.example.snorelyzer.ml.AudioModelConfig.N_MELS
import com.example.snorelyzer.ml.AudioModelConfig.SAMPLE_RATE
import com.example.snorelyzer.ml.AudioModelConfig.WIN_LENGTH
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sin

class AudioProcessor : MelSpectrogramProcessor {
    private val resampleRadius = 47
    private val resampleSideWeights = FloatArray((resampleRadius + 1) / 2) { index ->
        val offset = 2 * index + 1
        val phase = PI * offset / resampleRadius
        val blackman = 0.42 + 0.5 * cos(phase) + 0.08 * cos(2 * phase)
        (blackman * sin(PI * offset / 2) / (PI * offset)).toFloat()
    }
    private val resampleGain = 1f / (0.5f + 2f * resampleSideWeights.sum())
    private val paddedCapture = FloatArray(CAPTURE_WINDOW_SAMPLES + 2 * resampleRadius)

    // Valid pre-emphasis removes one sample before the centered STFT.
    private val emphasizedSamples = MODEL_WINDOW_SAMPLES - 1
    private val fftPadding = N_FFT / 2
    private val paddedSignal = FloatArray(emphasizedSamples + 2 * fftPadding)
    private val fft = FloatFFT_1D(N_FFT.toLong())
    private val window = FloatArray(WIN_LENGTH) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / (WIN_LENGTH - 1))).toFloat()
    }
    private val fftBuffer = FloatArray(N_FFT)
    private val power = FloatArray(N_FFT / 2)
    private val melFilters = createMelFilters()
    private val melSpec = FloatArray(AudioModelConfig.MEL_TENSOR_SIZE)

    override fun process(audioData: FloatArray): FloatArray {
        require(audioData.size == CAPTURE_WINDOW_SAMPLES) {
            "Expected $CAPTURE_WINDOW_SAMPLES microphone samples, got ${audioData.size}"
        }
        prepareSignal(audioData)

        for (frame in 0 until MEL_FRAMES) {
            val start = fftPadding + frame * HOP_SIZE - WIN_LENGTH / 2
            val windowOffset = (N_FFT - WIN_LENGTH) / 2
            fftBuffer.fill(0f)
            for (index in window.indices) {
                fftBuffer[windowOffset + index] = paddedSignal[start + index] * window[index]
            }
            fft.realForward(fftBuffer)
            power[0] = fftBuffer[0] * fftBuffer[0]
            for (bin in 1 until power.size) {
                val real = fftBuffer[2 * bin]
                val imaginary = fftBuffer[2 * bin + 1]
                power[bin] = real * real + imaginary * imaginary
            }

            for (mel in melFilters.indices) {
                val filter = melFilters[mel]
                var energy = 0f
                for (index in filter.weights.indices) {
                    energy += filter.weights[index] * power[filter.firstBin + index]
                }
                melSpec[mel * MEL_FRAMES + frame] = (ln(energy.coerceAtLeast(1e-7f)) + 4.5f) / 5f
            }
        }
        return melSpec
    }

    private fun prepareSignal(audioData: FloatArray) {
        audioData.copyInto(paddedCapture, destinationOffset = resampleRadius)
        reflectPadding(paddedCapture, resampleRadius)

        // Low-pass before halving 32 kHz capture to 16 kHz. The half-band
        // filter has a center tap and symmetric pairs at odd offsets.
        var previous = downsampleAt(resampleRadius)
        for (index in 0 until emphasizedSamples) {
            val current = downsampleAt(resampleRadius + 2 * (index + 1))
            paddedSignal[fftPadding + index] = current - 0.97f * previous
            previous = current
        }
        reflectPadding(paddedSignal, fftPadding)
    }

    private fun downsampleAt(center: Int): Float {
        var sample = 0.5f * paddedCapture[center]
        for (index in resampleSideWeights.indices) {
            val offset = 2 * index + 1
            sample += resampleSideWeights[index] *
                (paddedCapture[center - offset] + paddedCapture[center + offset])
        }
        return sample * resampleGain
    }

    private fun reflectPadding(samples: FloatArray, padding: Int) {
        val last = samples.lastIndex - padding
        for (index in 1..padding) {
            samples[padding - index] = samples[padding + index]
            samples[last + index] = samples[last - index]
        }
    }

    private fun createMelFilters(): Array<MelFilter> {
        // EfficientSED uses unnormalized Kaldi triangles over 0–7000 Hz.
        // The Nyquist bin has zero weight, as in the upstream padded mel bank.
        val melByBin = DoubleArray(N_FFT / 2) { melScale(it * SAMPLE_RATE.toDouble() / N_FFT) }
        val step = melScale(7_000.0) / (N_MELS + 1)
        return Array(N_MELS) { mel ->
            val center = (mel + 1) * step
            val firstBin = melByBin.indexOfFirst { it > center - step }
            val endBin = melByBin.indexOfFirst { it >= center + step }
            MelFilter(
                firstBin = firstBin,
                weights = FloatArray(endBin - firstBin) { index ->
                    (1.0 - kotlin.math.abs(melByBin[firstBin + index] - center) / step).toFloat()
                }
            )
        }
    }

    private fun melScale(hertz: Double): Double = 1127.0 * ln1p(hertz / 700.0)

    private data class MelFilter(val firstBin: Int, val weights: FloatArray)
}
