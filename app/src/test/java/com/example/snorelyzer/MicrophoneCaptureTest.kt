package com.example.snorelyzer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneCaptureTest {
    @Test
    fun negativeReadClosesRecorderAndResumesWithNewTimestamp() = runTest {
        val initial = FakeInput(listOf(FULL_CHUNK) + List(20) { 0 } + READ_ERROR)
        val replacement = FakeInput(listOf(FULL_CHUNK))
        val opened = mutableListOf<FakeInput>()
        val starts = mutableListOf<Long>()
        val capture = MicrophoneCapture(
            openInput = {
                if (opened.isEmpty()) initial.also(opened::add)
                else replacement.also {
                    assertEquals(1, initial.closeCalls)
                    opened.add(it)
                }
            },
            nowMillis = { 10_000L + currentTime }
        )

        val job = launch { capture.run(onInterrupted = {}, onChunk = { _, start -> starts.add(start) }) }
        advanceTimeBy(2_000)
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf(10_000L, 12_000L), starts)
        assertEquals(listOf(initial, replacement), opened)
        assertEquals(1, replacement.closeCalls)
    }

    @Test
    fun positiveReadResetsEmptyReadCountAndStallTriggersRecovery() = runTest {
        val initial = FakeInput(List(60) { 0 } + 1 + List(60) { 0 } + FULL_CHUNK)
        val replacement = FakeInput(listOf(FULL_CHUNK))
        val inputs = ArrayDeque(listOf(initial, replacement))
        val chunkStarts = mutableListOf<Long>()
        val interruptions = mutableListOf<Long>()
        val capture = MicrophoneCapture(inputs::removeFirst, nowMillis = { currentTime })

        val job = launch {
            capture.run(
                onInterrupted = { interruptions += currentTime },
                onChunk = { _, start -> chunkStarts += start }
            )
        }
        advanceTimeBy(10_000)
        runCurrent()
        job.cancelAndJoin()

        assertEquals(listOf(9_000L), interruptions)
        assertEquals(listOf(3_000L, 10_000L), chunkStarts)
        assertEquals(1, initial.closeCalls)
        assertEquals(1, replacement.closeCalls)
    }

    @Test
    fun cancelDuringBackoffDoesNotReopenRecorder() = runTest {
        val input = FakeInput(listOf(READ_ERROR))
        var opens = 0
        val capture = MicrophoneCapture(
            openInput = { input.also { opens++ } },
            nowMillis = { currentTime }
        )

        val job = launch { capture.run(onInterrupted = {}, onChunk = { _, _ -> error("Unexpected audio") }) }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()

        assertEquals(1, opens)
        assertEquals(1, input.closeCalls)
    }

    @Test
    fun persistentFailuresBackOffAndEventuallyExit() = runTest {
        val attempts = mutableListOf<Long>()
        val capture = MicrophoneCapture(
            openInput = {
                attempts += currentTime
                throw MicrophoneUnavailableException("Microphone unavailable")
            },
            nowMillis = { currentTime }
        )

        val result = runCatching { capture.run(onInterrupted = {}, onChunk = { _, _ -> error("Unexpected audio") }) }

        assertTrue(result.exceptionOrNull() is MicrophoneUnavailableException)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L), attempts.take(6))
        assertEquals(15, attempts.size)
        assertEquals(301_000L, attempts.last())
    }

    @Test
    fun permissionFailureExitsWithoutRetrying() = runTest {
        var opens = 0
        val capture = MicrophoneCapture(
            openInput = {
                opens++
                throw SecurityException("Microphone permission denied")
            },
            nowMillis = { currentTime }
        )

        val result = runCatching { capture.run(onInterrupted = {}, onChunk = { _, _ -> error("Unexpected audio") }) }

        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(1, opens)
    }

    private class FakeInput(reads: List<Int>) : MicrophoneInput {
        private val reads = ArrayDeque(reads)
        var closeCalls = 0
            private set

        override fun read(buffer: FloatArray, offset: Int, length: Int): Int {
            check(closeCalls == 0)
            return minOf(reads.removeFirstOrNull() ?: 0, length)
        }

        override fun close() {
            closeCalls++
        }
    }

    private companion object {
        const val FULL_CHUNK = MicrophoneCapture.SAMPLES_PER_CHUNK
        const val READ_ERROR = -6
    }
}
