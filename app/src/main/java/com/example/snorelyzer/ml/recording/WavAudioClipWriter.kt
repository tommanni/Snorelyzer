package com.example.snorelyzer.ml.recording

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

internal class WavAudioClipWriter internal constructor(
    private val recordingsDir: File
) : AudioClipWriter {
    constructor(context: Context) : this(File(context.filesDir, RECORDINGS_DIR))

    override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
        recordingsDir.mkdirs()
        val file = File(recordingsDir, request.fileName())
        val temporaryFile = File.createTempFile("clip-", ".tmp", recordingsDir)
        try {
            FileOutputStream(temporaryFile).use { output ->
                writeWav(output, request.samples, request.sampleRate)
            }
            Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporaryFile.delete()
        }
        return AudioClipWriteResult(
            filePath = file.absolutePath,
            sampleRate = request.sampleRate,
            durationMillis = request.samples.size * 1_000L / request.sampleRate
        )
    }

    override fun deleteClip(filePath: String): Boolean {
        val file = File(filePath)
        return !file.exists() || file.delete()
    }

    override fun deleteAllClips(): Boolean {
        return recordingsDir.deleteRecursively().also {
            recordingsDir.mkdirs()
        }
    }

    override fun storedClips(): List<StoredAudioClip> = recordingsDir.listFiles().orEmpty().mapNotNull { file ->
        val match = CLIP_NAME.matchEntire(file.name) ?: return@mapNotNull null
        val start = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val end = match.groupValues[3].toLongOrNull() ?: return@mapNotNull null
        if (!file.isFile) return@mapNotNull null
        StoredAudioClip(match.groupValues[1], start, end, file.absolutePath)
    }

    private fun writeWav(
        output: FileOutputStream,
        samples: FloatArray,
        sampleRate: Int
    ) {
        val dataSize = samples.size * BYTES_PER_SAMPLE
        val byteRate = sampleRate * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putAscii("RIFF")
            .putInt(WAV_HEADER_BYTES - 8 + dataSize)
            .putAscii("WAVE")
            .putAscii("fmt ")
            .putInt(16)
            .putShort(1.toShort())
            .putShort(CHANNEL_COUNT.toShort())
            .putInt(sampleRate)
            .putInt(byteRate)
            .putShort(BYTES_PER_SAMPLE.toShort())
            .putShort(BITS_PER_SAMPLE.toShort())
            .putAscii("data")
            .putInt(dataSize)
            .array()

        output.write(header)

        val sampleBytes = ByteBuffer.allocate(PCM_BUFFER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            sampleBytes.putShort(sample.toPcm16())
            if (!sampleBytes.hasRemaining()) {
                output.write(sampleBytes.array())
                sampleBytes.clear()
            }
        }
        output.write(sampleBytes.array(), 0, sampleBytes.position())
    }

    private fun AudioClipWriteRequest.fileName(): String {
        return "${sessionId}_${episodeId}_${clipStartMillis}_${clipEndMillis}.wav"
    }

    private fun ByteBuffer.putAscii(value: String): ByteBuffer {
        value.forEach { char -> put(char.code.toByte()) }
        return this
    }

    private fun Float.toPcm16(): Short {
        val clamped = coerceIn(-1f, 1f)
        return (clamped * Short.MAX_VALUE).roundToInt().toShort()
    }

    private companion object {
        const val RECORDINGS_DIR = "recordings"
        val CLIP_NAME = Regex("(session-(-?\\d+))_\\1-episode-\\d+_-?\\d+_(-?\\d+)\\.wav")
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val PCM_BUFFER_BYTES = 16 * 1024
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val WAV_HEADER_BYTES = 44
    }
}
