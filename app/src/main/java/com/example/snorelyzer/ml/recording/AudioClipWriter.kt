package com.example.snorelyzer.ml.recording

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

interface AudioClipWriter {
    fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult

    /** Writers that support compression can override this when an uncompressed save is needed. */
    fun writeUncompressedClip(request: AudioClipWriteRequest): AudioClipWriteResult = writeClip(request)
    fun deleteClip(filePath: String): Boolean
    fun deleteAllClips(): Boolean
    fun storedClips(): List<StoredAudioClip> = emptyList()
}

data class StoredAudioClip(
    val sessionId: String,
    val sessionStartMillis: Long,
    val clipEndMillis: Long,
    val filePath: String
)

data class AudioClipWriteRequest(
    val episodeId: String,
    val sessionId: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val clipStartMillis: Long,
    val clipEndMillis: Long
)

data class AudioClipWriteResult(
    val filePath: String,
    val sampleRate: Int,
    val durationMillis: Long
)

internal enum class AudioClipFormat(val extension: String, val mimeType: String) {
    Wav("wav", "audio/wav"),
    Opus("opus", "audio/ogg");

    companion object {
        fun fromFile(file: File): AudioClipFormat? = entries.firstOrNull {
            it.extension.equals(file.extension, ignoreCase = true)
        }
    }
}

internal fun recordingsDirectory(context: Context) = File(context.filesDir, "recordings")

internal fun writeEncodedClip(
    directory: File,
    request: AudioClipWriteRequest,
    format: AudioClipFormat,
    sampleRate: Int = request.sampleRate,
    encode: (File) -> Unit
): AudioClipWriteResult {
    directory.mkdirs()
    val file = directory.resolve(
        "${request.sessionId}_${request.episodeId}_${request.clipStartMillis}_${request.clipEndMillis}.${format.extension}"
    )
    val temporaryFile = File.createTempFile("clip-", ".tmp", directory)
    try {
        encode(temporaryFile)
        Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temporaryFile.delete()
    }
    return AudioClipWriteResult(file.absolutePath, sampleRate, request.samples.size * 1_000L / request.sampleRate)
}

internal fun Float.toPcm16(): Short = (coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
