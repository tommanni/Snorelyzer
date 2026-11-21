package com.example.snorelyzer.data.local

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class AudioExportFormat { Wav, Zip }

interface AudioExportDestination {
    fun open(): OutputStream
    fun discard()
}

internal class RecordingAudioExport private constructor(
    val fileName: String,
    val format: AudioExportFormat,
    private val clips: List<Clip>
) {
    val clipCount: Int get() = clips.size

    suspend fun hasAvailableAudio(): Boolean = withContext(Dispatchers.IO) {
        clips.any { it.file?.let { file -> file.isFile && file.canRead() } == true }
    }

    // Returns the number of clips copied. A destination with no audio is discarded.
    suspend fun saveTo(destination: AudioExportDestination): Int {
        var completed = false
        val savedCount = try {
            withContext(Dispatchers.IO) {
                writeClipsTo(destination).also {
                    ensureActive()
                    completed = it > 0
                }
            }
        } catch (exception: CancellationException) {
            if (!completed) discardFailedAudioExport(destination)
            throw exception
        } catch (exception: Exception) {
            if (!discardFailedAudioExport(destination)) throw IncompleteAudioExportException(exception)
            throw exception
        }
        if (savedCount == 0 && !discardFailedAudioExport(destination)) throw IncompleteAudioExportException()
        return savedCount
    }

    private fun CoroutineScope.writeClipsTo(destination: AudioExportDestination): Int {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        fun copyClip(input: InputStream, output: OutputStream) {
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
        }
        return when (format) {
            AudioExportFormat.Wav -> {
                val input = clips.single().file?.openClip() ?: return 0
                input.use {
                    destination.open().use { output -> copyClip(input, output) }
                }
                1
            }
            AudioExportFormat.Zip -> {
                ZipOutputStream(destination.open().buffered()).use { archive ->
                    clips.count { clip ->
                        ensureActive()
                        val input = clip.file?.openClip() ?: return@count false
                        input.use {
                            archive.putNextEntry(ZipEntry(clip.entryName))
                            copyClip(input, archive)
                            archive.closeEntry()
                        }
                        true
                    }
                }
            }
        }
    }

    private data class Clip(val file: File?, val entryName: String)

    companion object {
        private val clipTimestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss-SSS", Locale.ROOT)

        fun single(episode: RecordingEpisodeEntity, zoneId: ZoneId): RecordingAudioExport {
            val fileName = "${episode.clipBaseName(zoneId)}.wav"
            return RecordingAudioExport(
                fileName = fileName,
                format = AudioExportFormat.Wav,
                clips = listOf(Clip(episode.filePath?.let(::File), fileName))
            )
        }

        fun night(night: SleepNight, zoneId: ZoneId): RecordingAudioExport {
            val clips = night.sessions.flatMapIndexed { sessionIndex, session ->
                val folder = "session-${(sessionIndex + 1).toString().padStart(2, '0')}"
                session.episodes.sortedBy { it.episode.clipStartMillis }.mapIndexed { index, details ->
                    val episode = details.episode
                    val suffix = (index + 1).toString().padStart(3, '0')
                    Clip(episode.filePath?.let(::File), "$folder/${episode.clipBaseName(zoneId)}-$suffix.wav")
                }
            }
            return RecordingAudioExport("Snorelyzer-${night.date}.zip", AudioExportFormat.Zip, clips)
        }

        private fun RecordingEpisodeEntity.clipBaseName(zoneId: ZoneId): String {
            val time = Instant.ofEpochMilli(clipStartMillis).atZone(zoneId)
            return "clip-${clipTimestamp.format(time)}"
        }
    }
}

private fun File.openClip() = try {
    if (isFile) inputStream() else null
} catch (_: FileNotFoundException) {
    null
}

internal class IncompleteAudioExportException(cause: Exception? = null) :
    IOException("Could not remove incomplete export", cause)

internal suspend fun discardFailedAudioExport(destination: AudioExportDestination): Boolean =
    withContext(Dispatchers.IO + NonCancellable) {
        // The destination provider may deny or omit delete support.
        try {
            destination.discard()
            true
        } catch (_: Exception) {
            false
        }
    }
