package com.example.snorelyzer.presentation.insights

import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionEntity
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionInsightsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sleepNights = MutableSharedFlow<List<SleepNight>>()
    private val localDataSource = FakeSleepRecordingLocalDataSource(sleepNights)

    @Test
    fun `initial state is loading until sleep nights emit`() = runTest {
        val viewModel = SessionInsightsViewModel(localDataSource)

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLoading)
        assertEquals(emptyList<SleepNight>(), viewModel.state.value.sleepNights)
        assertNull(viewModel.state.value.selectedDate)
        assertNull(viewModel.state.value.selectedNight)
    }

    @Test
    fun `first emitted sleep nights select latest night`() = runTest {
        val latestNight = sleepNight(LocalDate.of(2026, 6, 10), "latest")
        val olderNight = sleepNight(LocalDate.of(2026, 6, 9), "older")
        val viewModel = SessionInsightsViewModel(localDataSource)

        sleepNights.emit(listOf(latestNight, olderNight))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(listOf(latestNight, olderNight), viewModel.state.value.sleepNights)
        assertEquals(latestNight.date, viewModel.state.value.selectedDate)
        assertEquals(latestNight, viewModel.state.value.selectedNight)
        assertEquals(setOf(latestNight.date, olderNight.date), viewModel.state.value.availableDates)
    }

    @Test
    fun `date selection updates selected night when date is available`() = runTest {
        val latestNight = sleepNight(LocalDate.of(2026, 6, 10), "latest")
        val olderNight = sleepNight(LocalDate.of(2026, 6, 9), "older")
        val viewModel = SessionInsightsViewModel(localDataSource)

        sleepNights.emit(listOf(latestNight, olderNight))
        viewModel.onAction(SessionInsightsAction.OnDateSelected(olderNight.date))
        advanceUntilIdle()

        assertEquals(olderNight.date, viewModel.state.value.selectedDate)
        assertEquals(olderNight, viewModel.state.value.selectedNight)
    }

    @Test
    fun `date selection keeps empty selected date when no night exists`() = runTest {
        val latestNight = sleepNight(LocalDate.of(2026, 6, 10), "latest")
        val emptyDate = LocalDate.of(2026, 6, 8)
        val viewModel = SessionInsightsViewModel(localDataSource)

        sleepNights.emit(listOf(latestNight))
        viewModel.onAction(SessionInsightsAction.OnDateSelected(emptyDate))
        advanceUntilIdle()

        assertEquals(emptyDate, viewModel.state.value.selectedDate)
        assertNull(viewModel.state.value.selectedNight)
    }

    @Test
    fun `sleep night updates preserve user selected date`() = runTest {
        val firstLatestNight = sleepNight(LocalDate.of(2026, 6, 10), "first-latest")
        val selectedNight = sleepNight(LocalDate.of(2026, 6, 9), "selected")
        val newLatestNight = sleepNight(LocalDate.of(2026, 6, 11), "new-latest")
        val updatedSelectedNight = sleepNight(LocalDate.of(2026, 6, 9), "updated-selected")
        val viewModel = SessionInsightsViewModel(localDataSource)

        sleepNights.emit(listOf(firstLatestNight, selectedNight))
        viewModel.onAction(SessionInsightsAction.OnDateSelected(selectedNight.date))
        sleepNights.emit(listOf(newLatestNight, updatedSelectedNight))
        advanceUntilIdle()

        assertEquals(selectedNight.date, viewModel.state.value.selectedDate)
        assertEquals(updatedSelectedNight, viewModel.state.value.selectedNight)
    }

    private fun sleepNight(
        date: LocalDate,
        sessionId: String
    ): SleepNight {
        return SleepNight(
            date = date,
            sessions = listOf(
                RecordingSessionWithEpisodes(
                    session = RecordingSessionEntity(
                        sessionId = sessionId,
                        startedAtMillis = 0L,
                        endedAtMillis = 1_000L
                    ),
                    episodes = emptyList()
                )
            )
        )
    }
}

private class FakeSleepRecordingLocalDataSource(
    private val sleepNights: Flow<List<SleepNight>>
) : SleepRecordingLocalDataSource {
    override suspend fun upsertSession(session: RecordingSessionMetadata) = Unit

    override suspend fun insertEpisode(episode: RecordingEpisodeMetadata) = Unit

    override suspend fun completeSession(session: RecordingSessionMetadata) = Unit

    override suspend fun deleteSession(sessionId: String) = Unit

    override fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>> = emptyFlow()

    override fun observeSleepNights(zoneId: ZoneId): Flow<List<SleepNight>> = sleepNights

    override fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>> = emptyFlow()
}
