package com.example.snorelyzer.ml.recording

import com.example.snorelyzer.ml.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedEventCatalogTest {

    @Test
    fun breathingIsNotARelevantClass() {
        assertFalse(RecordedEventCatalog.relevantClassIndices.contains(41))

        val results = RecordedEventCatalog.aggregate(
            listOf(ClassificationResult(41, 0.99f))
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun gaspUmbrellaLabelsAggregateToGasp() {
        val results = RecordedEventCatalog.aggregate(
            listOf(
                ClassificationResult(44, 0.26f),
                ClassificationResult(46, 0.50f),
                ClassificationResult(50, 0.30f)
            )
        )

        val gasp = results.single()
        assertEquals(RecordedEventGroup.Gasp, gasp.group)
        assertEquals(0.50f, gasp.probability)
        assertEquals("Snort", gasp.sourceLabel)
        assertTrue(gasp.isOccurring)
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
