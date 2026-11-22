package com.example.snorelyzer.presentation.insights

import androidx.media3.common.Player
import com.example.snorelyzer.SessionSaveStatus
import com.example.snorelyzer.StoppedSessionSave
import com.example.snorelyzer.data.local.AudioExportDestination
import com.example.snorelyzer.data.local.AudioExportFormat
import com.example.snorelyzer.data.local.RecordingAudioAccess
import com.example.snorelyzer.data.local.RecordingEpisodeEntity
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionEntity
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import com.example.snorelyzer.presentation.record.MainDispatcherRule
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionInsightsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val files = TemporaryFolder()

    private val sleepNights = MutableSharedFlow<List<SleepNight>>()
    private val localDataSource = FakeSleepRecordingLocalDataSource(sleepNights)

    @Test
    fun `initial state is loading until sleep nights emit`() = runTest {
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLoading)
        assertEquals(emptyList<SleepNight>(), viewModel.state.value.sleepNights)
        assertNull(viewModel.state.value.selectedDate)
        assertNull(viewModel.state.value.selectedNight)
    }

    @Test
    fun `saved session selects its persisted night even when it is older than other nights`() = runTest {
        val newest = sleepNight(LocalDate.of(2026, 6, 11), "newest")
        val savedNight = sleepNight(LocalDate.of(2026, 6, 10), "just-saved")
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        sleepNights.emit(listOf(newest))
        advanceUntilIdle()

        val save = StoppedSessionSave("just-saved", SessionSaveStatus.Saving)
        viewModel.onAction(SessionInsightsAction.OnShowSavedSession(save))
        advanceUntilIdle()
        sleepNights.emit(listOf(newest))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.selectedNight)

        viewModel.onAction(SessionInsightsAction.OnShowSavedSession(save.copy(status = SessionSaveStatus.Saved)))
        advanceUntilIdle()
        sleepNights.emit(listOf(newest, savedNight))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(savedNight, viewModel.state.value.selectedNight)

        viewModel.onAction(SessionInsightsAction.OnDateSelected(newest.date))
        viewModel.onAction(SessionInsightsAction.OnShowSavedSession(save.copy(status = SessionSaveStatus.Saved)))
        assertEquals(newest, viewModel.state.value.selectedNight)
    }

    @Test
    fun `failed save without a session stops loading and does not select unrelated history`() = runTest {
        val newest = sleepNight(LocalDate.of(2026, 6, 11), "newest")
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        viewModel.onAction(
            SessionInsightsAction.OnShowSavedSession(StoppedSessionSave("missing", SessionSaveStatus.Failed))
        )
        advanceUntilIdle()
        sleepNights.emit(listOf(newest))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.selectedNight)
        assertEquals(setOf(newest.date), viewModel.state.value.availableDates)
    }

    @Test
    fun `first emitted sleep nights select latest night`() = runTest {
        val latestNight = sleepNight(LocalDate.of(2026, 6, 10), "latest")
        val olderNight = sleepNight(LocalDate.of(2026, 6, 9), "older")
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

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
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

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
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

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
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

        sleepNights.emit(listOf(firstLatestNight, selectedNight))
        viewModel.onAction(SessionInsightsAction.OnDateSelected(selectedNight.date))
        sleepNights.emit(listOf(newLatestNight, updatedSelectedNight))
        advanceUntilIdle()

        assertEquals(selectedNight.date, viewModel.state.value.selectedDate)
        assertEquals(updatedSelectedNight, viewModel.state.value.selectedNight)
    }

    @Test
    fun `sleep night updates automatically select new latest night when user has not manually selected date`() = runTest {
        val firstLatestNight = sleepNight(LocalDate.of(2026, 6, 10), "first-latest")
        val olderNight = sleepNight(LocalDate.of(2026, 6, 9), "older")
        val newLatestNight = sleepNight(LocalDate.of(2026, 6, 11), "new-latest")
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

        sleepNights.emit(listOf(firstLatestNight, olderNight))
        advanceUntilIdle()

        assertEquals(firstLatestNight.date, viewModel.state.value.selectedDate)
        assertEquals(firstLatestNight, viewModel.state.value.selectedNight)

        sleepNights.emit(listOf(newLatestNight, firstLatestNight, olderNight))
        advanceUntilIdle()

        assertEquals(newLatestNight.date, viewModel.state.value.selectedDate)
        assertEquals(newLatestNight, viewModel.state.value.selectedNight)
    }

    @Test
    fun `toggle session collapse toggles collapsed state for session id`() = runTest {
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

        viewModel.onAction(SessionInsightsAction.OnToggleSessionCollapse("session-123"))
        assertEquals(setOf("session-123"), viewModel.state.value.collapsedSessionIds)

        viewModel.onAction(SessionInsightsAction.OnToggleSessionCollapse("session-123"))
        assertEquals(emptySet<String>(), viewModel.state.value.collapsedSessionIds)
    }

    @Test
    fun `toggle episode spans toggles expanded state for episode id`() = runTest {
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())

        viewModel.onAction(SessionInsightsAction.OnToggleEpisodeSpans("episode-456"))
        assertEquals(setOf("episode-456"), viewModel.state.value.expandedEpisodeIds)

        viewModel.onAction(SessionInsightsAction.OnToggleEpisodeSpans("episode-456"))
        assertEquals(emptySet<String>(), viewModel.state.value.expandedEpisodeIds)
    }

    @Test
    fun `calendar dismissal preserves night and confirmation selects another night`() = runTest {
        val latest = sleepNight(LocalDate.of(2026, 6, 10), "latest")
        val older = sleepNight(LocalDate.of(2026, 6, 7), "older")
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        sleepNights.emit(listOf(latest, older))
        advanceUntilIdle()

        viewModel.onAction(SessionInsightsAction.OnShowDatePicker)
        assertTrue(viewModel.state.value.isDatePickerVisible)
        viewModel.onAction(SessionInsightsAction.OnDismissDatePicker)
        assertFalse(viewModel.state.value.isDatePickerVisible)
        assertEquals(latest.date, viewModel.state.value.selectedDate)

        viewModel.onAction(SessionInsightsAction.OnShowDatePicker)
        viewModel.onAction(SessionInsightsAction.OnDateSelected(older.date))
        assertFalse(viewModel.state.value.isDatePickerVisible)
        assertEquals(older, viewModel.state.value.selectedNight)
    }

    @Test
    fun `calendar cannot open without recorded nights`() = runTest {
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        viewModel.onAction(SessionInsightsAction.OnShowDatePicker)
        assertFalse(viewModel.state.value.isDatePickerVisible)
    }

    @Test
    fun `confirming deletion targets the sessions shown in the confirmation`() = runTest {
        val latest = sleepNight(LocalDate.of(2026, 6, 10), "latest")
        val first = sleepNight(LocalDate.of(2026, 6, 9), "first")
        val second = sleepNight(first.date, "second")
        val target = first.copy(sessions = first.sessions + second.sessions)
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        advanceUntilIdle()
        sleepNights.emit(listOf(latest, target))

        viewModel.onAction(SessionInsightsAction.OnDateSelected(target.date))
        viewModel.onAction(SessionInsightsAction.OnRequestDeleteNight)
        assertEquals(target, viewModel.state.value.nightToDelete)

        viewModel.onAction(SessionInsightsAction.OnDateSelected(latest.date))
        viewModel.onAction(SessionInsightsAction.OnConfirmDeleteNight)
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), localDataSource.deletedSessionIds)
        assertNull(viewModel.state.value.nightToDelete)
        assertFalse(viewModel.state.value.isDeletingNight)
    }

    @Test
    fun `export uses the night requested before opening the picker`() = runTest {
        val latest = nightWithClip(LocalDate.of(2026, 6, 10), "latest")
        val older = nightWithClip(LocalDate.of(2026, 6, 9), "older")
        val audioAccess = RecordingAudioAccess()
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer(), audioAccess)
        sleepNights.emit(listOf(latest, older))
        advanceUntilIdle()

        viewModel.onAction(SessionInsightsAction.OnExportNight)
        assertEquals(
            SessionInsightsEvent.ChooseExportDestination(AudioExportFormat.Zip, "Snorelyzer-2026-06-10.zip"),
            viewModel.events.first()
        )
        assertNull(audioAccess.tryExpire(latest.date))
        viewModel.onAction(SessionInsightsAction.OnRequestDeleteNight)
        assertNull(viewModel.state.value.nightToDelete)

        viewModel.onAction(SessionInsightsAction.OnDateSelected(older.date))
        val output = ByteArrayOutputStream()
        val destination = object : AudioExportDestination {
            override fun open() = output
            override fun discard() = error("Successful export should keep its destination")
        }
        viewModel.onAction(SessionInsightsAction.OnExportDestinationResult(destination))

        assertEquals(SessionInsightsEvent.AudioSaved(AudioExportFormat.Zip, 1, 0), viewModel.events.first())
        audioAccess.tryExpire(latest.date).use { assertNotNull(it) }
        ZipInputStream(output.toByteArray().inputStream()).use { archive ->
            assertTrue(archive.nextEntry.name.startsWith("session-01/"))
            assertEquals("latest", archive.readBytes().decodeToString())
            assertNull(archive.nextEntry)
        }
        assertEquals(older.date, viewModel.state.value.selectedDate)
        assertNull(viewModel.state.value.audioExport)
    }

    @Test
    fun `canceling the save picker allows the clip to be requested again`() = runTest {
        val night = nightWithClip(LocalDate.of(2026, 6, 10), "latest")
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        sleepNights.emit(listOf(night))
        advanceUntilIdle()

        val action = SessionInsightsAction.OnSaveClip(night.allEpisodes.single().episode.episodeId)
        viewModel.onAction(action)
        val firstRequest = viewModel.events.first() as SessionInsightsEvent.ChooseExportDestination
        assertEquals(AudioExportFormat.Wav, firstRequest.format)

        viewModel.onAction(SessionInsightsAction.OnExportDestinationResult(null))
        assertNull(viewModel.state.value.audioExport)
        viewModel.onAction(action)
        assertEquals(firstRequest, viewModel.events.first())
    }

    @Test
    fun `night with no audio reports unavailability before opening the picker`() = runTest {
        val night = nightWithClip(LocalDate.of(2026, 6, 10), "latest")
        assertTrue(files.root.resolve("latest.wav").delete())
        val viewModel = SessionInsightsViewModel(localDataSource, FakePlayer())
        sleepNights.emit(listOf(night))
        advanceUntilIdle()

        viewModel.onAction(SessionInsightsAction.OnExportNight)

        assertEquals(SessionInsightsEvent.ExportAudioUnavailable, viewModel.events.first())
        assertNull(viewModel.state.value.audioExport)
        assertFalse(viewModel.state.value.hasExportableNightAudio)
    }

    private fun nightWithClip(date: LocalDate, sessionId: String): SleepNight {
        val audio = files.newFile("$sessionId.wav").apply { writeText(sessionId) }
        val session = sleepNight(date, sessionId).sessions.single()
        val episode = RecordingEpisodeWithDetails(
            episode = RecordingEpisodeEntity("$sessionId-clip", sessionId, 0, 500, 500, 16_000, audio.absolutePath),
            groups = emptyList(),
            eventSpans = emptyList()
        )
        return SleepNight(date, listOf(session.copy(episodes = listOf(episode))))
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

private class FakePlayer : Player by (Proxy.newProxyInstance(
    Player::class.java.classLoader,
    arrayOf(Player::class.java)
) { proxy, method, args ->
    when (method.name) {
        "toString" -> "FakePlayer"
        "hashCode" -> 1
        "equals" -> args?.getOrNull(0) === proxy
        else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
} as Player)

private class FakeSleepRecordingLocalDataSource(
    private val sleepNights: Flow<List<SleepNight>>
) : SleepRecordingLocalDataSource {
    override suspend fun upsertSession(session: RecordingSessionMetadata) = Unit

    override suspend fun insertEpisode(episode: RecordingEpisodeMetadata) = Unit

    override suspend fun completeSession(session: RecordingSessionMetadata) = Unit

    override suspend fun deleteSession(sessionId: String) = Unit

    var deletedSessionIds: List<String> = emptyList()
        private set

    override suspend fun deleteFinishedSessions(sessionIds: List<String>) {
        deletedSessionIds += sessionIds
    }

    override suspend fun deleteAllSessions() = Unit

    override fun observeSessions(): Flow<List<RecordingSessionWithEpisodes>> = emptyFlow()

    override fun observeSleepNights(zoneId: ZoneId): Flow<List<SleepNight>> = sleepNights

    override fun observeEpisodesForSession(sessionId: String): Flow<List<RecordingEpisodeWithDetails>> = emptyFlow()
}
