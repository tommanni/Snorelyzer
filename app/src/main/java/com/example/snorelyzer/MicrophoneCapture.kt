package com.example.snorelyzer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal interface MicrophoneInput : AutoCloseable {
    fun read(buffer: FloatArray, offset: Int, length: Int): Int
}

internal class MicrophoneUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal class MicrophoneCapture(
    private val openInput: () -> MicrophoneInput,
    private val nowMillis: () -> Long
) {
    suspend fun run(
        onInterrupted: suspend (MicrophoneUnavailableException) -> Unit,
        onChunk: suspend (FloatArray, Long) -> Unit
    ): Nothing {
        val buffer = FloatArray(SAMPLES_PER_CHUNK)
        var lastChunkEndMillis = Long.MIN_VALUE
        var retries = 0
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS

        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                openInput().use { input ->
                    val openedAtMillis = nowMillis()
                    var nextChunkStartMillis: Long? = null
                    while (true) {
                        val firstSampleAtMillis = readChunk(input, buffer)
                        currentCoroutineContext().ensureActive()
                        val chunkStartMillis = nextChunkStartMillis
                            ?: maxOf(openedAtMillis, firstSampleAtMillis, lastChunkEndMillis)
                        onChunk(buffer, chunkStartMillis)
                        lastChunkEndMillis = chunkStartMillis + CHUNK_DURATION_MILLIS
                        nextChunkStartMillis = lastChunkEndMillis
                        retries = 0
                        retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
                    }
                }
            } catch (error: MicrophoneUnavailableException) {
                currentCoroutineContext().ensureActive()
                onInterrupted(error)
                if (retries == MAX_RETRIES) throw error
                delay(retryDelayMillis)
                retries++
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun readChunk(input: MicrophoneInput, buffer: FloatArray): Long {
        var samplesRead = 0
        var emptyReads = 0
        var firstSampleAtMillis = 0L
        while (samplesRead < buffer.size) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, samplesRead, buffer.size - samplesRead)
            when {
                count > 0 -> {
                    if (samplesRead == 0) {
                        // The first samples may arrive well after startRecording() succeeds.
                        firstSampleAtMillis = nowMillis() - count * 1_000L / SAMPLE_RATE
                    }
                    samplesRead += count
                    emptyReads = 0
                }
                count < 0 -> throw MicrophoneUnavailableException("Audio read failed ($count)")
                else -> {
                    emptyReads++
                    if (emptyReads > MAX_EMPTY_READS) {
                        throw MicrophoneUnavailableException("Microphone stopped returning samples")
                    }
                    delay(EMPTY_READ_DELAY_MILLIS)
                }
            }
        }
        return firstSampleAtMillis
    }

    companion object {
        const val SAMPLE_RATE = 32_000
        const val SAMPLES_PER_CHUNK = SAMPLE_RATE
        const val CHUNK_DURATION_MILLIS = SAMPLES_PER_CHUNK * 1_000L / SAMPLE_RATE

        private const val EMPTY_READ_DELAY_MILLIS = 50L
        private const val MAX_EMPTY_READS = 60
        private const val MAX_RETRIES = 14
        private const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L
    }
}
