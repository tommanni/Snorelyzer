package com.example.snorelyzer.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.example.snorelyzer.SleepTrackingSessionState
import com.example.snorelyzer.SessionSaveStatus
import com.example.snorelyzer.StoppedSessionSave
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ActiveSleepSessionNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeSleepSessionHidesNavigationAndConsumesBackUntilServiceStops() {
        val sleepTrackingSessionState = MutableStateFlow(
            SleepTrackingSessionState(isRunning = true)
        )

        composeRule.setContent {
            SnorelyzerTheme {
                SnorelyzerApp(
                    onRequestRecordingPermission = {},
                    onStartRecordingService = {},
                    onStopRecordingService = {},
                    onDiscardRecordingService = {},
                    sleepTrackingSessionStateFlow = sleepTrackingSessionState
                )
            }
        }

        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()
        composeRule.onAllNodesWithText("Record").assertCountEquals(0)
        composeRule.onAllNodesWithText("Insights").assertCountEquals(0)
        composeRule.onAllNodesWithText("Settings").assertCountEquals(0)

        pressBack()
        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()

        composeRule.runOnIdle {
            sleepTrackingSessionState.value = SleepTrackingSessionState(isRunning = false)
        }

        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
    }

    @Test
    fun recordTabReturnsToRecordAfterFirstSavedSession() {
        val sleepTrackingSessionState = MutableStateFlow(SleepTrackingSessionState(isRunning = true))
        composeRule.setContent {
            SnorelyzerTheme {
                SnorelyzerApp(
                    onRequestRecordingPermission = {},
                    onStartRecordingService = {},
                    onStopRecordingService = {},
                    onDiscardRecordingService = {},
                    sleepTrackingSessionStateFlow = sleepTrackingSessionState
                )
            }
        }
        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()

        composeRule.runOnIdle {
            sleepTrackingSessionState.value = SleepTrackingSessionState(
                explicitlyStoppedSession = StoppedSessionSave("first-session", SessionSaveStatus.Saved)
            )
        }
        composeRule.onNodeWithText("Insights").assertIsSelected()

        composeRule.onNodeWithText("Record").performClick()

        composeRule.onNodeWithText("Record").assertIsSelected()
        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
    }

    @Test
    fun explicitSaveOpensSessionInsightsAndShowsLateSaveWarning() {
        val sleepTrackingSessionState = MutableStateFlow(SleepTrackingSessionState())
        composeRule.setContent {
            SnorelyzerTheme {
                SnorelyzerApp(
                    onRequestRecordingPermission = {},
                    onStartRecordingService = {},
                    onStopRecordingService = {},
                    onDiscardRecordingService = {},
                    sleepTrackingSessionStateFlow = sleepTrackingSessionState
                )
            }
        }
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("Analytics").performClick()
        composeRule.onNodeWithText("Analytics").assertIsSelected()
        composeRule.onNodeWithText("Record").performClick()

        composeRule.runOnIdle {
            sleepTrackingSessionState.value = SleepTrackingSessionState(isRunning = true)
        }
        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()

        val save = StoppedSessionSave("test-session", SessionSaveStatus.Saving)
        composeRule.runOnIdle {
            sleepTrackingSessionState.value = SleepTrackingSessionState(explicitlyStoppedSession = save)
        }
        composeRule.onNodeWithText("Insights").assertIsSelected()
        composeRule.onNodeWithText("Session").assertIsSelected()

        composeRule.runOnIdle {
            sleepTrackingSessionState.value = SleepTrackingSessionState(
                hasSaveFailure = true,
                explicitlyStoppedSession = save.copy(status = SessionSaveStatus.Failed)
            )
        }
        composeRule.onNodeWithText("Some recordings couldn’t be saved.").assertIsDisplayed()
        composeRule.onNodeWithText("Session insights content will appear here.").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
    }
}
