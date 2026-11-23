package com.example.snorelyzer.ml.recording

import com.example.snorelyzer.ml.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedEventCatalogTest {

    @Test
    fun breathingIsRequestedWithoutBecomingARecordedEvent() {
        assertTrue(RecordedEventCatalog.relevantClassIndices.contains(41))

        val results = RecordedEventCatalog.aggregate(
            listOf(ClassificationResult(41, 0.99f))
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun directGaspAtThresholdTakesPriorityOverSnortAndSniff() {
        val results = RecordedEventCatalog.aggregate(
            listOf(
                ClassificationResult(41, 0.0120f),
                ClassificationResult(44, 0.10f),
                ClassificationResult(46, 0.50f),
                ClassificationResult(50, 0.30f)
            )
        )

        val gasp = results.single()
        assertEquals(RecordedEventGroup.Gasp, gasp.group)
        assertEquals(0.10f, gasp.probability)
        assertEquals("Gasp", gasp.sourceLabel)
        assertTrue(gasp.isOccurring)
    }

    @Test
    fun gaspNeedsALowerBreathingScore() {
        val gasp = ClassificationResult(44, 0.1027f)
        val tiedBreathing = ClassificationResult(41, gasp.probability)
        val higherBreathing = ClassificationResult(41, 0.2033f)

        assertTrue(RecordedEventCatalog.aggregate(listOf(gasp)).isEmpty())
        assertTrue(RecordedEventCatalog.aggregate(listOf(gasp, tiedBreathing)).isEmpty())
        assertTrue(RecordedEventCatalog.aggregate(listOf(gasp, higherBreathing)).isEmpty())
    }

    @Test
    fun snortAndSniffCannotSubstituteForABelowThresholdGasp() {
        val result = RecordedEventCatalog.aggregate(
            listOf(
                ClassificationResult(41, 0.0071f),
                ClassificationResult(44, 0.0847f),
                ClassificationResult(46, 0.80f),
                ClassificationResult(50, 0.90f)
            )
        ).single()

        assertEquals(0.0847f, result.probability)
        assertFalse(result.isOccurring)
    }

    @Test
    fun speechLabelsAggregateToSleepTalking() {
        val labels = listOf(
            ClassificationResult(0, 0.26f),
            ClassificationResult(1, 0.27f),
            ClassificationResult(2, 0.28f),
            ClassificationResult(3, 0.29f),
            ClassificationResult(4, 0.30f),
            ClassificationResult(5, 0.31f),
            ClassificationResult(15, 0.60f),
            ClassificationResult(70, 0.32f)
        )

        val sleepTalking = RecordedEventCatalog.aggregate(labels).single()

        assertEquals(RecordedEventGroup.SleepTalking, sleepTalking.group)
        assertEquals(0.60f, sleepTalking.probability)
        assertEquals("Whispering", sleepTalking.sourceLabel)
        assertTrue(sleepTalking.isOccurring)
    }

    @Test
    fun belowThresholdGroupIsAggregatedButNotOccurring() {
        val snoring = RecordedEventCatalog.aggregate(
            listOf(ClassificationResult(43, 0.10f))
        ).single()

        assertEquals(RecordedEventGroup.Snoring, snoring.group)
        assertFalse(snoring.isOccurring)
    }
}
