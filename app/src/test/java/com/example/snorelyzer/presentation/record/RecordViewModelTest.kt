package com.example.snorelyzer.presentation.record

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial state mirrors idle recording service state`() = runTest {
        val viewModel = RecordViewModel()

        advanceUntilIdle()

        assertEquals("Waiting for audio...", viewModel.state.value.latestStatus)
        assertEquals(emptyList<Any>(), viewModel.state.value.latestResults)
        assertFalse(viewModel.state.value.isServiceRunning)
    }

    @Test
    fun `start click requests recording permission`() = runTest {
        val viewModel = RecordViewModel()
        val event = async { viewModel.events.first() }

        viewModel.onAction(RecordAction.OnStartClick)
        advanceUntilIdle()

        assertEquals(RecordEvent.RequestRecordingPermission, event.await())
    }

    @Test
    fun `granted recording permission starts recording service`() = runTest {
        val viewModel = RecordViewModel()
        val event = async { viewModel.events.first() }

        viewModel.onAction(RecordAction.OnRecordingPermissionResult(granted = true))
        advanceUntilIdle()

        assertEquals(RecordEvent.StartRecordingService, event.await())
    }

    @Test
    fun `stop click stops recording service`() = runTest {
        val viewModel = RecordViewModel()
        val event = async { viewModel.events.first() }

        viewModel.onAction(RecordAction.OnStopClick)
        advanceUntilIdle()

        assertEquals(RecordEvent.StopRecordingService, event.await())
    }
}
