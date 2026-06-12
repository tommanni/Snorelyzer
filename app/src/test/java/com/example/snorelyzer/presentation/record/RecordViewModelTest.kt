package com.example.snorelyzer.presentation.record

import com.example.snorelyzer.DetectedClassUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial state mirrors idle recording service state`() = runTest {
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals("Waiting for audio...", viewModel.state.value.latestStatus)
        assertEquals(emptyList<Any>(), viewModel.state.value.latestResults)
        assertFalse(viewModel.state.value.isServiceRunning)
    }

    @Test
    fun `start click requests recording permission`() = runTest {
        val viewModel = viewModel()
        val event = async { viewModel.events.first() }

        viewModel.onAction(RecordAction.OnStartClick)
        advanceUntilIdle()

        assertEquals(RecordEvent.RequestRecordingPermission, event.await())
    }

    @Test
    fun `granted recording permission starts recording service`() = runTest {
        val viewModel = viewModel()
        val event = async { viewModel.events.first() }

        viewModel.onAction(RecordAction.OnRecordingPermissionResult(granted = true))
        advanceUntilIdle()

        assertEquals(RecordEvent.StartRecordingService, event.await())
    }

    @Test
    fun `stop click after minimum duration saves recording`() = runTest {
        val viewModel = viewModel(
            nowMillis = 10 * 60 * 1_000L,
            isServiceRunning = true,
            sessionStartedAtMillis = 0L
        )
        val event = async { viewModel.events.first() }

        viewModel.onAction(RecordAction.OnStopClick)
        advanceUntilIdle()

        assertEquals(RecordEvent.StopRecordingService(save = true), event.await())
    }

    @Test
    fun `stop click before minimum duration shows short session dialog`() = runTest {
        val viewModel = viewModel(
            nowMillis = 9 * 60 * 1_000L,
            isServiceRunning = true,
            sessionStartedAtMillis = 0L
        )

        viewModel.onAction(RecordAction.OnStopClick)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showShortSessionDialog)
    }

    @Test
    fun `keep recording closes short session dialog`() = runTest {
        val viewModel = viewModel(
            nowMillis = 9 * 60 * 1_000L,
            isServiceRunning = true,
            sessionStartedAtMillis = 0L
        )

        viewModel.onAction(RecordAction.OnStopClick)
        viewModel.onAction(RecordAction.OnKeepRecordingClick)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showShortSessionDialog)
        assertEquals(1, viewModel.state.value.stopTrackingHoldResetKey)
    }

    @Test
    fun `end now discards recording and shows notification`() = runTest {
        val viewModel = viewModel(
            nowMillis = 9 * 60 * 1_000L,
            isServiceRunning = true,
            sessionStartedAtMillis = 0L
        )
        val events = async { viewModel.events.take(2).toList() }

        viewModel.onAction(RecordAction.OnStopClick)
        viewModel.onAction(RecordAction.OnEndShortSessionNowClick)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showShortSessionDialog)
        assertEquals(
            listOf(
                RecordEvent.StopRecordingService(save = false),
                RecordEvent.ShowRecordingDiscardedNotification
            ),
            events.await()
        )
    }

    private fun viewModel(
        nowMillis: Long = 0L,
        isServiceRunning: Boolean = false,
        sessionStartedAtMillis: Long? = null
    ): RecordViewModel {
        return RecordViewModel(
            nowMillis = { nowMillis },
            latestStatus = MutableStateFlow("Waiting for audio..."),
            latestResults = MutableStateFlow(emptyList<DetectedClassUi>()),
            isServiceRunning = MutableStateFlow(isServiceRunning),
            sessionStartedAtMillis = MutableStateFlow(sessionStartedAtMillis)
        )
    }
}
