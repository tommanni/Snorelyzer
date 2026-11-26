package com.example.snorelyzer.ml.recording

import com.example.snorelyzer.ml.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedEventCatalogTest {

    @Test
    fun breathingIsRequestedWithoutBecomingARecordedEvent() {
        assertTrue(RecordedEventCatalog.relevantClassIndices.contains(46))

        val results = RecordedEventCatalog.aggregate(
            listOf(ClassificationResult(46, 0.99f))
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun directGaspAtThresholdTakesPriorityOverSnortAndSniff() {
        val results = RecordedEventCatalog.aggregate(
            listOf(
                ClassificationResult(46, 0.0120f),
                ClassificationResult(176, 0.10f),
                ClassificationResult(346, 0.50f),
                ClassificationResult(344, 0.30f)
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
        val gasp = ClassificationResult(176, 0.1027f)
        val tiedBreathing = ClassificationResult(46, gasp.probability)
        val higherBreathing = ClassificationResult(46, 0.2033f)

        assertTrue(RecordedEventCatalog.aggregate(listOf(gasp)).isEmpty())
        assertTrue(RecordedEventCatalog.aggregate(listOf(gasp, tiedBreathing)).isEmpty())
        assertTrue(RecordedEventCatalog.aggregate(listOf(gasp, higherBreathing)).isEmpty())
    }

    @Test
    fun snortAndSniffCannotSubstituteForABelowThresholdGasp() {
        val result = RecordedEventCatalog.aggregate(
            listOf(
                ClassificationResult(46, 0.0071f),
                ClassificationResult(176, 0.0847f),
                ClassificationResult(346, 0.80f),
                ClassificationResult(344, 0.90f)
            )
        ).single()

        assertEquals(0.0847f, result.probability)
        assertFalse(result.isOccurring)
    }

    @Test
    fun speechLabelsAggregateToSleepTalking() {
        val labels = listOf(
            ClassificationResult(353, 0.26f),
            ClassificationResult(238, 0.27f),
            ClassificationResult(157, 0.28f),
            ClassificationResult(78, 0.29f),
            ClassificationResult(103, 0.30f),
            ClassificationResult(254, 0.31f),
            ClassificationResult(424, 0.60f),
            ClassificationResult(206, 0.32f)
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
            listOf(ClassificationResult(345, 0.10f))
        ).single()

        assertEquals(RecordedEventGroup.Snoring, snoring.group)
        assertFalse(snoring.isOccurring)
    }
}
