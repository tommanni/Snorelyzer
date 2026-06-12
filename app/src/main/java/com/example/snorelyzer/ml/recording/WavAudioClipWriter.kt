package com.example.snorelyzer.ml.recording

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class WavAudioClipWriter internal constructor(
    private val recordingsDir: File
) : AudioClipWriter {
    constructor(context: Context) : this(File(context.filesDir, RECORDINGS_DIR))

    override fun writeClip(request: AudioClipWriteRequest): AudioClipWriteResult {
        recordingsDir.mkdirs()
        val file = File(recordingsDir, request.fileName())
        FileOutputStream(file).use { output ->
            writeWav(output, request.samples, request.sampleRate)
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

        val sampleBytes = ByteBuffer.allocate(dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            sampleBytes.putShort(sample.toPcm16())
        }
        output.write(sampleBytes.array())
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
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val WAV_HEADER_BYTES = 44
    }
}
