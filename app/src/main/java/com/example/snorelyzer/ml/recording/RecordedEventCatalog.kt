package com.example.snorelyzer.ml.recording

import com.example.snorelyzer.ml.ClassificationResult

data class RelevantClassSpec(
    val index: Int,
    val label: String,
    val group: RecordedEventGroup
)

data class RecordedEventGroupResult(
    val group: RecordedEventGroup,
    val probability: Float,
    val sourceLabel: String,
    val threshold: Float
) {
    val isOccurring: Boolean
        get() = probability >= threshold
}

object RecordedEventCatalog {
    const val GASP_CLASS_INDEX = 44
    const val BREATHING_CLASS_INDEX = 41

    val relevantClasses = listOf(
        RelevantClassSpec(43, "Snoring", RecordedEventGroup.Snoring),
        RelevantClassSpec(GASP_CLASS_INDEX, "Gasp", RecordedEventGroup.Gasp),
        RelevantClassSpec(47, "Cough", RecordedEventGroup.Cough),
        RelevantClassSpec(0, "Speech", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(1, "Male speech, man speaking", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(2, "Female speech, woman speaking", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(3, "Child speech, kid speaking", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(4, "Conversation", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(5, "Narration, monologue", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(15, "Whispering", RecordedEventGroup.SleepTalking),
        RelevantClassSpec(70, "Hubbub, speech noise, speech babble", RecordedEventGroup.SleepTalking)
    )

    val relevantClassIndices: Set<Int> = relevantClasses.mapTo(mutableSetOf(BREATHING_CLASS_INDEX)) { it.index }

    private val specsByIndex = relevantClasses.associateBy { it.index }

    fun aggregate(
        results: List<ClassificationResult>,
        config: RecordedEventConfig = RecordedEventConfig()
    ): List<RecordedEventGroupResult> {
        val breathingScore = results.firstOrNull { it.index == BREATHING_CLASS_INDEX }?.probability
        return results
            .mapNotNull { result ->
                val spec = specsByIndex[result.index] ?: return@mapNotNull null
                if (spec.group == RecordedEventGroup.Gasp &&
                    breathingScore?.let { result.probability > it } != true
                ) {
                    return@mapNotNull null
                }
                val threshold = config.thresholds[spec.group] ?: return@mapNotNull null
                RecordedEventGroupResult(
                    group = spec.group,
                    probability = result.probability,
                    sourceLabel = spec.label,
                    threshold = threshold
                )
            }
            .groupBy { it.group }
            .values
            .mapNotNull { groupResults ->
                groupResults.maxByOrNull { it.probability }
            }
            .sortedByDescending { it.probability }
    }
}
