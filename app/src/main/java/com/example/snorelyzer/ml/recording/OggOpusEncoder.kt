package com.example.snorelyzer.ml.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.OpusUtil
import com.example.snorelyzer.MicrophoneCapture
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val OpusSampleRate = 48_000
internal const val OpusCodecPollMicros = 1_000L
internal const val OpusCodecStallNanos = 3_000_000_000L
private const val Bitrate = 48_000
private const val FrameSamples = OpusSampleRate / 50 // 20 ms

internal fun encodeOggOpus(file: File, request: AudioClipWriteRequest) {
    require(request.sampleRate == MicrophoneCapture.SAMPLE_RATE && request.samples.isNotEmpty())
    val samples = ClipOpusPcm(request.samples)
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OpusSampleRate, 1).apply {
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        setInteger(MediaFormat.KEY_BIT_RATE, Bitrate)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        file.outputStream().buffered().use { output ->
            encodePackets(codec, samples, OggOpusFile(output, samples.size.toLong(), opusPeakComment(request.samples)))
        }
    } finally {
        codec.release()
    }
}

private fun encodePackets(codec: MediaCodec, samples: ClipOpusPcm, file: OggOpusFile) {
    val info = MediaCodec.BufferInfo()
    var inputSamples = 0
    var inputEnded = false
    var lastProgress = System.nanoTime()
    while (true) {
        val endGranule = file.endGranule
        // Wait for OpusHead before deciding how many padding samples the encoder needs.
        var fedInput = false
        while (!inputEnded && (inputSamples < samples.size || endGranule != null)) {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) break
            val timeUs = inputSamples * 1_000_000L / OpusSampleRate
            if (endGranule != null && inputSamples >= endGranule) {
                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            } else {
                val buffer = checkNotNull(codec.getInputBuffer(index)).order(ByteOrder.nativeOrder())
                val neededSamples = (endGranule ?: samples.size.toLong()) - inputSamples
                val neededFrames = ((neededSamples + FrameSamples - 1) / FrameSamples).toInt()
                val availableFrames = buffer.remaining() / (FrameSamples * Short.SIZE_BYTES)
                val samplesToQueue = minOf(neededFrames, availableFrames) * FrameSamples
                check(samplesToQueue > 0)
                repeat(samplesToQueue) { buffer.putShort(samples[inputSamples + it]) }
                try {
                    codec.queueInputBuffer(index, 0, samplesToQueue * Short.SIZE_BYTES, timeUs, 0)
                } catch (error: MediaCodec.CodecException) {
                    throw IOException("Opus input failed at $timeUs us (${error.diagnosticInfo})", error)
                }
                inputSamples += samplesToQueue
            }
            fedInput = true
            lastProgress = System.nanoTime()
        }

        when (val index = codec.dequeueOutputBuffer(info, if (fedInput) 0 else OpusCodecPollMicros)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                codec.outputFormat.getByteBuffer("csd-0")?.let(file::writeHeaders)
                lastProgress = System.nanoTime()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                if (System.nanoTime() - lastProgress > OpusCodecStallNanos) {
                    throw IOException("Opus encoder stopped making progress")
                }
            }
            else -> if (index >= 0) {
                try {
                    if (info.size > 0) {
                        val packet = checkNotNull(codec.getOutputBuffer(index))
                        packet.limit(info.offset + info.size)
                        packet.position(info.offset)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            file.writeHeaders(packet)
                        } else {
                            file.writePacket(packet)
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    file.finish()
                    return
                }
                lastProgress = System.nanoTime()
            }
        }
    }
}

/** Resamples the recorder's 32 kHz PCM to 48 kHz in place as the codec takes input. */
private class ClipOpusPcm(private val samples: FloatArray) {
    val size = (samples.size * 3 + 1) / 2

    operator fun get(index: Int): Short {
        if (index >= size) return 0
        val sourceIndex = index * 2 / 3
        val phase = index * 2 % 3
        if (phase == 0) return samples[sourceIndex].toPcm16()

        var value = 0f
        val coefficients = SincPhases[phase - 1]
        for (tap in coefficients.indices) {
            val sampleIndex = (sourceIndex + tap - Radius + 1).coerceIn(samples.indices)
            value += samples[sampleIndex] * coefficients[tap]
        }
        return value.toPcm16()
    }

    private companion object {
        const val Radius = 8
        // The two fractional phases of a windowed sinc low-pass filter.
        val SincPhases = Array(2) { phase ->
            val coefficients = FloatArray(Radius * 2) { tap ->
                val distance = tap - Radius + 1 - (phase + 1) / 3.0
                (sin(PI * distance) / (PI * distance) * (0.5 + 0.5 * cos(PI * distance / Radius))).toFloat()
            }
            val sum = coefficients.sum()
            FloatArray(coefficients.size) { coefficients[it] / sum }
        }
    }
}

/** Writes single-stream Ogg Opus with bounded pages and exact end trimming (RFC 7845). */
@OptIn(UnstableApi::class)
private class OggOpusFile(
    private val output: OutputStream,
    private val audioSamples: Long,
    private val peakComment: ByteArray
) {
    var endGranule: Long? = null
        private set
    private var sequence = 0
    private val serial = kotlin.random.Random.nextInt()
    private var encodedSamples = 0L
    private var bodySize = 0
    private var segmentCount = 0
    private val segments = ByteArray(255)
    private val body = ByteArray(PageBodyBytes)

    fun writeHeaders(data: ByteBuffer) {
        if (endGranule != null) return
        val header = ByteArray(19) // OpusHead with mono channel mapping family 0.
        val magic = "OpusHead".encodeToByteArray()
        val lastStart = data.limit() - header.size
        val start = (data.position()..lastStart).firstOrNull { offset ->
            magic.indices.all { data.get(offset + it) == magic[it] }
        } ?: throw IOException("Missing Opus identification header")
        data.duplicate().apply { position(start) }.get(header)
        check(header[9] == 1.toByte() && header[18] == 0.toByte())
        endGranule = audioSamples + OpusUtil.getPreSkipSamples(header)
        addPacket(ByteBuffer.wrap(header))
        writePage(0, flags = 2) // Beginning of stream

        val vendor = "Snorelyzer".encodeToByteArray()
        val tags = ByteBuffer.allocate(20 + vendor.size + peakComment.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".encodeToByteArray()).putInt(vendor.size).put(vendor)
            .putInt(1).putInt(peakComment.size).put(peakComment)
        tags.flip()
        addPacket(tags)
        writePage(0)
    }

    fun writePacket(packet: ByteBuffer) {
        if (encodedSamples >= checkNotNull(endGranule)) return
        val segmentLength = packet.remaining() / 255 + 1
        if (bodySize + packet.remaining() > body.size || segmentCount + segmentLength > segments.size) {
            writePage(encodedSamples)
        }
        encodedSamples += OpusUtil.parsePacketAudioSampleCount(packet.slice())
        addPacket(packet)
    }

    fun finish() {
        val end = checkNotNull(endGranule)
        check(encodedSamples >= end && segmentCount > 0) { "Incomplete Opus stream" }
        writePage(end, flags = 4) // End of stream
    }

    private fun addPacket(packet: ByteBuffer) {
        val size = packet.remaining()
        check(bodySize + size <= body.size)
        repeat(size / 255) { segments[segmentCount++] = 255.toByte() }
        segments[segmentCount++] = (size % 255).toByte()
        packet.get(body, bodySize, size)
        bodySize += size
    }

    private fun writePage(granule: Long, flags: Byte = 0) {
        val page = ByteBuffer.allocate(27 + segmentCount).order(ByteOrder.LITTLE_ENDIAN)
            .put(OggCapturePattern).put(0).put(flags)
            .putLong(granule).putInt(serial).putInt(sequence++).putInt(0)
            .put(segmentCount.toByte()).put(segments, 0, segmentCount)
        val header = page.array()
        val crc = Util.crc32(header, 0, header.size, 0)
        page.putInt(22, Util.crc32(body, 0, bodySize, crc))
        output.write(header)
        output.write(body, 0, bodySize)
        bodySize = 0
        segmentCount = 0
    }

    private companion object {
        const val PageBodyBytes = 4_096
        val OggCapturePattern = "OggS".encodeToByteArray()
    }
}
