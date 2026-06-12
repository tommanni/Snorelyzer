package com.example.snorelyzer.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.pressBack
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ActiveSleepSessionNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeSleepSessionHidesNavigationAndConsumesBackUntilServiceStops() {
        val isServiceRunning = MutableStateFlow(true)

        composeRule.setContent {
            SnorelyzerTheme {
                SnorelyzerApp(
                    onRequestRecordingPermission = {},
                    onStartRecordingService = {},
                    onStopRecordingService = {},
                    onDiscardRecordingService = {},
                    isServiceRunningFlow = isServiceRunning
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
            isServiceRunning.value = false
        }

        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
    }
}
