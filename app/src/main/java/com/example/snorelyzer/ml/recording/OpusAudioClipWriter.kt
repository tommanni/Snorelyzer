package com.example.snorelyzer.ml.recording

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

internal class OpusAudioClipWriter(
    private val recordingsDir: File,
    private val encode: ((File, AudioClipWriteRequest) -> Unit)? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ::encodeOggOpus else null,
    private val wavWriter: WavAudioClipWriter = WavAudioClipWriter(recordingsDir)
) : AudioClipWriter by wavWriter {
    constructor(context: Context) : this(recordingsDirectory(context))

    override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
        val encoder = encode ?: return wavWriter.writeClip(request)
        try {
            return writeEncodedClip(recordingsDir, request, AudioClipFormat.Opus, OpusSampleRate) { encoder(it, request) }
        } catch (error: Exception) {
            runCatching { Log.w("OpusAudioClipWriter", "Saving WAV for ${request.episodeId}", error) }
            return wavWriter.writeClip(request)
        }
    }

    override fun writeUncompressedClip(request: AudioClipWriteRequest): AudioClipWriteResult = wavWriter.writeClip(request)
}
