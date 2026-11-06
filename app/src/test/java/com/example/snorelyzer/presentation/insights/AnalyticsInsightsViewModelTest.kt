package com.example.snorelyzer.presentation.insights

import com.example.snorelyzer.data.local.EventSpanEntity
import com.example.snorelyzer.data.local.RecordingEpisodeEntity
import com.example.snorelyzer.data.local.RecordingEpisodeGroupEntity
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionEntity
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.ml.recording.RecordedEventGroup
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import com.example.snorelyzer.presentation.record.MainDispatcherRule
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsInsightsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sleepNights = MutableSharedFlow<List<SleepNight>>()
    private val localDataSource = FakeAnalyticsDataSource(sleepNights)

    @Test
    fun `initial state is loading until sleep nights emit`() = runTest {
        val viewModel = AnalyticsInsightsViewModel(localDataSource)

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLoading)
        assertEquals(emptyList<SleepNight>(), viewModel.state.value.sleepNights)
        assertEquals(0, viewModel.state.value.multiNightAnalytics.totalNights)
    }

    @Test
    fun `emitted nights populate analytics and select latest night trend`() = runTest {
        val date1 = LocalDate.of(2026, 11, 5)
        val date2 = LocalDate.of(2026, 11, 6)
        val night1 = createTestNight(date1, 1762377600000L)
        val night2 = createTestNight(date2, 1762464000000L)

        val viewModel = AnalyticsInsightsViewModel(localDataSource)
        sleepNights.emit(listOf(night2, night1))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(2, viewModel.state.value.sleepNights.size)
        assertEquals(2, viewModel.state.value.multiNightAnalytics.totalNights)
        assertEquals(date2, viewModel.state.value.selectedTrendDate)
    }

    @Test
    fun `selecting trend date updates state`() = runTest {
        val date1 = LocalDate.of(2026, 11, 5)
        val date2 = LocalDate.of(2026, 11, 6)
        val night1 = createTestNight(date1, 1762377600000L)
        val night2 = createTestNight(date2, 1762464000000L)

        val viewModel = AnalyticsInsightsViewModel(localDataSource)
        sleepNights.emit(listOf(night2, night1))
        advanceUntilIdle()

        viewModel.onAction(AnalyticsInsightsAction.OnSelectTrendNight(date1))
        assertEquals(date1, viewModel.state.value.selectedTrendDate)
    }

    private fun createTestNight(date: LocalDate, startMillis: Long): SleepNight {
        val session = RecordingSessionWithEpisodes(
            session = RecordingSessionEntity(
                sessionId = "s-$date",
                startedAtMillis = startMillis,
                endedAtMillis = startMillis + 28800000L
            ),
            episodes = listOf(
                RecordingEpisodeWithDetails(
                    episode = RecordingEpisodeEntity(
                        episodeId = "ep-$date",
                        sessionId = "s-$date",
                        clipStartMillis = startMillis + 3600000L,
                        clipEndMillis = startMillis + 3610000L,
                        durationMillis = 10000L,
                        sampleRate = 16000,
                        filePath = null
                    ),
                    groups = listOf(
                        RecordingEpisodeGroupEntity("ep-$date", RecordedEventGroup.Snoring)
                    ),
                    eventSpans = listOf(
                        EventSpanEntity(
                            id = 1L,
                            episodeId = "ep-$date",
                            group = RecordedEventGroup.Snoring,
                            startedAtMillis = startMillis + 3600000L,
                            endedAtMillis = startMillis + 3610000L
                        )
                    )
                )
            )
        )
        return SleepNight(date = date, sessions = listOf(session))
    }
}

private class FakeAnalyticsDataSource(
    private val sleepNights: Flow<List<SleepNight>>
) : SleepRecordingLocalDataSource {
    override suspend fun upsertSession(session: RecordingSessionMetadata) = Unit
    override suspend fun insertEpisode(episode: RecordingEpisodeMetadata) = Unit
    override suspend fun completeSession(session: RecordingSessionMetadata) = Unit
    override suspend fun deleteSession(sessionId: String) = Unit
    override suspend fun deleteAllSessions() = Unit
    override fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>> = emptyFlow()
    override fun observeSleepNights(zoneId: ZoneId): Flow<List<SleepNight>> = sleepNights
    override fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>> = emptyFlow()
}
