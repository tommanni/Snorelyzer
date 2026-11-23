package com.example.snorelyzer.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingAudioExportTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun `single clip export preserves the original bytes`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val audio = files.newFile("original.wav").apply { writeBytes(bytes) }
        val export = RecordingAudioExport.single(clip("first", 0, audio).episode, ZoneOffset.UTC)
        val destination = TestDestination()

        assertTrue(export.hasAvailableAudio())
        assertEquals("clip-1970-01-01_00-00-00-000.wav", export.fileName)
        assertEquals(1, export.saveTo(destination))
        assertArrayEquals(bytes, destination.bytes.toByteArray())
        assertFalse(destination.discarded)
    }

    @Test
    fun `single Opus export preserves the original bytes and file type`() = runTest {
        val bytes = byteArrayOf(4, 5, 6)
        val audio = files.newFile("original.opus").apply { writeBytes(bytes) }
        val export = RecordingAudioExport.single(clip("first", 0, audio).episode, ZoneOffset.UTC)
        val destination = TestDestination()

        assertEquals("clip-1970-01-01_00-00-00-000.opus", export.fileName)
        assertEquals("audio/ogg", export.format.mimeType)
        assertEquals(1, export.saveTo(destination))
        assertArrayEquals(bytes, destination.bytes.toByteArray())
    }

    @Test
    fun `night archive groups mixed formats by session and skips absent audio`() = runTest {
        val first = files.newFile("first.wav").apply { writeText("first") }
        val second = files.newFile("second.opus").apply { writeText("second") }
        val sameTimestamp = files.newFile("same-time.wav").apply { writeText("same time") }
        val missing = File(files.root, "missing.wav")
        val night = SleepNight(
            date = LocalDate.of(1970, 1, 1),
            sessions = listOf(
                RecordingSessionWithEpisodes(
                    RecordingSessionEntity("first", 0, 5_000),
                    listOf(clip("first", 1_000, first), clip("first", 1_000, sameTimestamp), clip("first", 2_000, missing))
                ),
                RecordingSessionWithEpisodes(
                    RecordingSessionEntity("second", 6_000, 9_000),
                    listOf(clip("second", 7_000, second), clip("second", 8_000, null))
                )
            )
        )
        val export = RecordingAudioExport.night(night, ZoneOffset.UTC)
        val destination = TestDestination()

        assertEquals("Snorelyzer-1970-01-01.zip", export.fileName)
        assertEquals(5, export.clipCount)
        assertEquals(3, export.saveTo(destination))
        val entries = ZipInputStream(destination.bytes.toByteArray().inputStream()).use { archive ->
            buildMap {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    put(entry.name, archive.readBytes().decodeToString())
                }
            }
        }
        assertEquals(
            mapOf(
                "session-01/clip-1970-01-01_00-00-01-000-001.wav" to "first",
                "session-01/clip-1970-01-01_00-00-01-000-002.wav" to "same time",
                "session-02/clip-1970-01-01_00-00-07-000-001.opus" to "second"
            ),
            entries
        )
        assertFalse(destination.discarded)
    }

    @Test
    fun `export discards destination if audio disappears before it is opened`() = runTest {
        val audio = files.newFile("original.wav")
        val export = RecordingAudioExport.single(clip("first", 0, audio).episode, ZoneOffset.UTC)
        val destination = TestDestination()

        assertTrue(export.hasAvailableAudio())
        assertTrue(audio.delete())
        assertFalse(export.hasAvailableAudio())
        assertEquals(0, export.saveTo(destination))
        assertTrue(destination.discarded)
    }

    @Test
    fun `export discards partial output on a write error`() = runTest {
        val audio = files.newFile("original.wav").apply { writeText("audio") }
        val export = RecordingAudioExport.single(clip("first", 0, audio).episode, ZoneOffset.UTC)
        val destination = TestDestination(object : OutputStream() {
            override fun write(value: Int) = throw IOException("Storage unavailable")
        })

        val result = runCatching { export.saveTo(destination) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(destination.discarded)
        assertTrue(audio.isFile)
    }

    @Test
    fun `export reports an incomplete file if the destination refuses to discard it`() = runTest {
        val audio = File(files.root, "missing.wav")
        val export = RecordingAudioExport.single(clip("first", 0, audio).episode, ZoneOffset.UTC)
        val destination = object : AudioExportDestination {
            override fun open() = error("Missing audio should not open the destination")
            override fun discard() = throw IOException("Destination refused deletion")
        }

        val result = runCatching { export.saveTo(destination) }

        assertTrue(result.exceptionOrNull() is IncompleteAudioExportException)
    }

    private fun clip(session: String, start: Long, file: File?): RecordingEpisodeWithDetails {
        return RecordingEpisodeWithDetails(
            episode = RecordingEpisodeEntity(
                episodeId = "$session-${file?.name.orEmpty()}",
                sessionId = session,
                clipStartMillis = start,
                clipEndMillis = start + 500,
                durationMillis = 500,
                sampleRate = 16_000,
                filePath = file?.absolutePath
            ),
            groups = emptyList(),
            eventSpans = emptyList()
        )
    }

    private class TestDestination(private val output: OutputStream? = null) : AudioExportDestination {
        val bytes = ByteArrayOutputStream()
        var discarded = false
            private set

        override fun open() = output ?: bytes

        override fun discard() {
            discarded = true
        }
    }
}
