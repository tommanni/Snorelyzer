package com.example.snorelyzer.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snorelyzer.MicrophoneCapture
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class Fmn06InferenceTest {
    @Test
    fun bundledQuantizedModelClassifiesThroughTheAppPipeline() {
        val pipeline = GlobalContext.get().get<AudioProcessingPipeline>()
        try {
            val sampleRate = MicrophoneCapture.SAMPLE_RATE
            val chunk = FloatArray(sampleRate) { index ->
                (0.1 * sin(2.0 * PI * 120 * index / sampleRate)).toFloat()
            }
            val result = pipeline.processChunk(chunk, chunkStartMillis = 0, forceInference = true)
            if (result is AudioProcessingResult.Failed) {
                throw AssertionError("Quantized fmn06 inference failed", result.error)
            }

            val groups = (result as AudioProcessingResult.Classified).groupResults
            assertTrue(groups.map { it.group }.containsAll(listOf(
                RecordedEventGroup.Snoring,
                RecordedEventGroup.Cough,
                RecordedEventGroup.SleepTalking
            )))
            assertTrue(groups.all { it.probability in 0f..1f })
        } finally {
            pipeline.close()
        }
    }
}
