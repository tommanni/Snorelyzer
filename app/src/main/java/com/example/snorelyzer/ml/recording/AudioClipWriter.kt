package com.example.snorelyzer.ml.recording

interface AudioClipWriter {
    fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult
    fun deleteClip(filePath: String): Boolean
}

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
