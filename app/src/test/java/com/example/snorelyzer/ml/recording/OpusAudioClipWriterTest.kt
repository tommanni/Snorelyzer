package com.example.snorelyzer.ml.recording

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpusAudioClipWriterTest {
    @get:Rule
    val clips = TemporaryFolder()

    @Test
    fun encoderFailureSavesWavAndRemovesThePartialFile() {
        val writer = OpusAudioClipWriter(clips.root, encode = { file, _ ->
            file.writeText("partial stream")
            throw IOException("Encoder failed")
        })
        val request = AudioClipWriteRequest("session-0-episode-1", "session-0", FloatArray(32_000), 32_000, 0, 1_000)

        val result = writer.writeClip(request)

        val output = File(result.filePath)
        assertEquals("wav", output.extension)
        assertEquals("RIFF", output.inputStream().use { it.readNBytes(4).decodeToString() })
        assertEquals(listOf(output), clips.root.listFiles()!!.toList())
        assertEquals(listOf(result.filePath), writer.storedClips().map { it.filePath })
    }
}
