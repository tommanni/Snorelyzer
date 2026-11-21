package com.example.snorelyzer.presentation.settings

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.snorelyzer.SleepTrackingSessionController
import com.example.snorelyzer.data.local.RecordingInterruptionStore
import com.example.snorelyzer.data.local.RoomRecordingMetadataSink
import com.example.snorelyzer.data.local.RoomSleepRecordingDataSource
import com.example.snorelyzer.data.local.SleepDataDeletion
import com.example.snorelyzer.data.local.SnorelyzerDatabase
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import com.example.snorelyzer.presentation.AppSnackbarHost
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsDeletionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cancellingKeepsDataAndConfirmingClearsHistoryAudioAndInterruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clips = Files.createTempDirectory(context.cacheDir.toPath(), "settings-deletion-").toFile()
        val preferencesName = clips.name
        val testContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences(preferencesName, mode)
        }
        val interruptionStore = RecordingInterruptionStore(testContext)
        val writer = WavAudioClipWriter(clips)
        val database = Room.inMemoryDatabaseBuilder(context, SnorelyzerDatabase::class.java).build()

        try {
            val clip = File(clips, "orphaned.wav").apply { writeBytes(byteArrayOf(1)) }
            val dataSource = RoomSleepRecordingDataSource(database, writer)
            val nights = dataSource.observeSleepNights(ZoneId.systemDefault())
            val metadataSink = RoomRecordingMetadataSink(dataSource)
            val session = RecordingSessionMetadata("session", startedAtMillis = 1_000L, endedAtMillis = 5_000L)
            runBlocking { dataSource.upsertSession(session) }

            interruptionStore.interruptedAtMillis = session.endedAtMillis
            val controller = SleepTrackingSessionController(
                audioEventRecorder = AudioEventRecorder(clipWriter = writer, metadataSink = metadataSink),
                audioProcessingPipelineFactory = { error("Unexpected recording") },
                initialInterruptedAtMillis = interruptionStore.interruptedAtMillis
            )
            val viewModel = SettingsViewModel(SleepDataDeletion(controller, metadataSink, dataSource, interruptionStore))
            composeRule.setContent {
                SnorelyzerTheme {
                    val snackbarHostState = remember { SnackbarHostState() }
                    Box {
                        SettingsRoot(snackbarHostState = snackbarHostState, viewModel = viewModel)
                        AppSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }

            composeRule.onNodeWithText("Delete all data").performClick()
            composeRule.onNodeWithText("Delete all data?").assertIsDisplayed()
            composeRule.onNodeWithText("This permanently deletes all sleep history, audio recordings, and detected events stored by this app on this device.")
                .assertIsDisplayed()
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.onNode(isDialog()).assertDoesNotExist()
            assertEquals(1, runBlocking { nights.first().size })
            assertTrue(clip.exists())
            assertEquals(session.endedAtMillis, interruptionStore.interruptedAtMillis)

            composeRule.onNodeWithText("Delete all data").performClick()
            val confirm = composeRule.onNode(hasText("Delete all data") and hasAnyAncestor(isDialog()))
            check(controller.sessionMutex.tryLock())
            try {
                confirm.performClick()
                composeRule.onNodeWithText("Wait for sleep tracking to finish, then try again.").assertIsDisplayed()
                assertTrue(clip.exists())
            } finally {
                controller.sessionMutex.unlock()
            }

            confirm.performClick()
            composeRule.waitUntil(5_000L) { !viewModel.state.value.showDeleteConfirmation }
            composeRule.onNodeWithText("All Snorelyzer data deleted.").assertIsDisplayed()
            assertTrue(runBlocking { nights.first().isEmpty() })
            assertFalse(clip.exists())
            assertNull(interruptionStore.interruptedAtMillis)
            assertNull(controller.state.value.interruptedAtMillis)
        } finally {
            database.close()
            clips.deleteRecursively()
            context.deleteSharedPreferences(preferencesName)
        }
    }
}
