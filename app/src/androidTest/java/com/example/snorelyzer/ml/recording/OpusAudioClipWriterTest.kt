package com.example.snorelyzer.ml.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.PowerManager
import android.util.Log
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class OpusAudioClipWriterTest {
    @Test
    fun savesAndDecodesAFiveMinuteClipWithSoundAtBothEnds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "opus-test-").toFile()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Snorelyzer:OpusTest")
        try {
            wakeLock.acquire(120_000)
            val sampleRate = 32_000
            val samples = FloatArray(sampleRate * 300)
            val tone = FloatArray(sampleRate) { (0.6 * sin(2 * PI * 440 * it / sampleRate)).toFloat() }
            tone.copyInto(samples, destinationOffset = sampleRate)
            tone.copyInto(samples, destinationOffset = samples.size - sampleRate)
            val request = AudioClipWriteRequest("session-0-episode-1", "session-0", samples, sampleRate, 0, 300_000)
            lateinit var clip: AudioClipWriteResult
            val elapsedMillis = measureTimeMillis { clip = OpusAudioClipWriter(directory).writeClip(request) }
            val file = File(clip.filePath)

            assertEquals("opus", file.extension)
            assertEquals(300_000L, clip.durationMillis)
            assertTrue(file.length() < samples.size * 2)
            Log.i("OpusClipTest", "300 seconds encoded in ${elapsedMillis}ms, ${file.length()} bytes")

            val extractor = MediaExtractor()
            try {
                file.inputStream().use { extractor.setDataSource(it.fd) }
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS, format.getString(MediaFormat.KEY_MIME))
                assertTrue(abs(format.getLong(MediaFormat.KEY_DURATION) - 300_000_000) < 20_000)
            } finally {
                extractor.release()
            }

            lateinit var peaks: List<Float>
            val waveformMillis = measureTimeMillis { peaks = checkNotNull(readClipPeaks(file)) }
            Log.i("OpusClipTest", "300-second waveform loaded in ${waveformMillis}ms")
            assertTrue(peaks.first() > 0.4f)
            assertTrue(peaks[48] < 0.01f)
            assertTrue(peaks.last() > 0.4f)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            directory.deleteRecursively()
        }
    }
}
