package com.example.snorelyzer.ml.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val PeakCommentKey = "SNORELYZER_PEAKS="

internal suspend fun readClipPeaks(file: File): List<Float>? = when (AudioClipFormat.fromFile(file)) {
    AudioClipFormat.Opus -> readStoredOpusPeaks(file) ?: readOpusPeaks(file)
    AudioClipFormat.Wav, null -> readWavPeaks(file)
}

/** Store the trimmed PCM envelope in the Opus comment page so long clips can display it quickly. */
internal fun opusPeakComment(samples: FloatArray): ByteArray {
    val waveform = WaveformPeaks(samples.size.toLong())
    for (sample in samples) waveform.add(sample.toPcm16().toInt())
    val peaks = waveform.values()
    val bytes = ByteBuffer.allocate(peaks.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (peak in peaks) bytes.putShort(peak.toPcm16())
    return (PeakCommentKey + Base64.getEncoder().encodeToString(bytes.array())).encodeToByteArray()
}

private fun readStoredOpusPeaks(file: File): List<Float>? {
    return try {
        DataInputStream(file.inputStream().buffered()).use { source ->
            val identification = source.readOggPage() ?: return null
            if (!identification.startsWith("OpusHead")) return null
            val comments = source.readOggPage() ?: return null
            if (!comments.startsWith("OpusTags")) return null
            val tags = ByteBuffer.wrap(comments).order(ByteOrder.LITTLE_ENDIAN)
            tags.position(8)
            val vendorLength = tags.readLength() ?: return null
            tags.position(tags.position() + vendorLength)
            if (tags.remaining() < Int.SIZE_BYTES) return null
            val commentCount = tags.int.coerceIn(0, tags.remaining() / Int.SIZE_BYTES)
            repeat(commentCount) {
                val size = tags.readLength() ?: return null
                val bytes = ByteArray(size).also { tags.get(it) }
                val comment = bytes.decodeToString()
                if (comment.startsWith(PeakCommentKey)) {
                    val data = Base64.getDecoder().decode(comment.removePrefix(PeakCommentKey))
                    if (data.isEmpty() || data.size > WaveformPeakCount * Short.SIZE_BYTES || data.size % Short.SIZE_BYTES != 0) {
                        return null
                    }
                    val peaks = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    return List(data.size / Short.SIZE_BYTES) { peaks.short.toInt().coerceIn(0, Short.MAX_VALUE.toInt()) / 32_768f }
                }
            }
            null
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun DataInputStream.readOggPage(): ByteArray? {
    if (readInt() != 0x4f676753 || skipBytes(22) != 22) return null // OggS and fixed page header.
    val lacing = ByteArray(readUnsignedByte()).also { readFully(it) }
    return ByteArray(lacing.sumOf { it.toInt() and 0xff }).also { readFully(it) }
}

private fun ByteArray.startsWith(magic: String): Boolean =
    size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

private fun ByteBuffer.readLength(): Int? =
    if (remaining() < Int.SIZE_BYTES) null else int.takeIf { it in 0..remaining() }

private suspend fun readOpusPeaks(file: File): List<Float>? {
    return try {
        val extractor = MediaExtractor()
        try {
            // A file descriptor keeps the extractor local and avoids media-server path access.
            file.inputStream().use { extractor.setDataSource(it.fd) }
            if (extractor.trackCount != 1) return null
            val format = extractor.getTrackFormat(0)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_OPUS ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1 ||
                !format.containsKey(MediaFormat.KEY_DURATION)
            ) return null

            val sampleCount = format.getLong(MediaFormat.KEY_DURATION) * OpusSampleRate / 1_000_000
            if (sampleCount <= 0) return null
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                decoder.configure(format, null, null, 0)
                decoder.start()
                decodePeaks(extractor, decoder, sampleCount)
            } finally {
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

private suspend fun decodePeaks(extractor: MediaExtractor, decoder: MediaCodec, sampleCount: Long): List<Float>? {
    val peaks = WaveformPeaks(sampleCount)
    val info = MediaCodec.BufferInfo()
    val context = currentCoroutineContext()
    var inputEnded = false
    var lastProgress = System.nanoTime()
    while (true) {
        context.ensureActive()
        var fedInput = false
        while (!inputEnded) {
            val index = decoder.dequeueInputBuffer(0)
            if (index < 0) break
            val input = checkNotNull(decoder.getInputBuffer(index))
            val size = extractor.readSampleData(input, 0)
            if (size < 0) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            } else {
                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
            fedInput = true
            lastProgress = System.nanoTime()
        }

        when (val index = decoder.dequeueOutputBuffer(info, if (fedInput) 0 else OpusCodecPollMicros)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val output = decoder.outputFormat
                if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != OpusSampleRate ||
                    output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1 ||
                    (output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                ) return null
                lastProgress = System.nanoTime()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                if (System.nanoTime() - lastProgress > OpusCodecStallNanos) {
                    throw IOException("Opus decoder stopped making progress")
                }
            }
            else -> if (index >= 0) {
                try {
                    if (info.size > 0) {
                        val output = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        output.limit(info.offset + info.size)
                        output.position(info.offset)
                        while (output.remaining() >= Short.SIZE_BYTES) {
                            peaks.add(output.short.toInt())
                        }
                    }
                } finally {
                    decoder.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return peaks.values()
                lastProgress = System.nanoTime()
            }
        }
    }
}
