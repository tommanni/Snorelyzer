package com.example.snorelyzer.ml.recording

interface AudioClipWriter {
    fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult
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
