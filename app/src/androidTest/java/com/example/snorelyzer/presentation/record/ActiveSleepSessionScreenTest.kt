package com.example.snorelyzer.presentation.record

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActiveSleepSessionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeStateShowsOnlyStopTracking() {
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()
        composeRule.onAllNodesWithText("Start Tracking").assertCountEquals(0)
    }

    @Test
    fun tappingStopTrackingDoesNotDispatchStopAction() {
        val actions = mutableListOf<RecordAction>()

        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            down(center)
            up()
        }

        assertEquals(emptyList<RecordAction>(), actions)
    }

    @Test
    fun stopTrackingHoldShowsCountdown() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            down(center)
        }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("3").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("2").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("1").assertIsDisplayed()

        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            up()
        }
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun releasingStopTrackingEarlyCancelsStopAction() {
        val actions = mutableListOf<RecordAction>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            down(center)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            up()
        }
        composeRule.mainClock.advanceTimeBy(1_000)

        assertEquals(emptyList<RecordAction>(), actions)
        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun holdingStopTrackingForThreeSecondsDispatchesStopActionOnce() {
        val actions = mutableListOf<RecordAction>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            down(center)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(3_100)
        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            up()
        }

        assertEquals(listOf(RecordAction.OnStopClick), actions)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun shortSessionDialogShowsWarningAndActions() {
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(
                        isServiceRunning = true,
                        showShortSessionDialog = true
                    ),
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithText("Recording is too short").assertIsDisplayed()
        composeRule.onNodeWithText("Sleep sessions shorter than 10 minutes are not saved. Keep tracking to save this session later.").assertIsDisplayed()
        composeRule.onNodeWithText("Keep tracking").assertIsDisplayed()
        composeRule.onNodeWithText("End now").assertIsDisplayed()
    }

    @Test
    fun shortSessionDialogButtonsDispatchActions() {
        val actions = mutableListOf<RecordAction>()
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = RecordState(
                        isServiceRunning = true,
                        showShortSessionDialog = true
                    ),
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithText("Keep tracking").performClick()
        composeRule.onNodeWithText("End now").performClick()

        assertEquals(
            listOf(
                RecordAction.OnKeepRecordingClick,
                RecordAction.OnEndShortSessionNowClick
            ),
            actions
        )
    }

    @Test
    fun keepTrackingResetsCompletedStopTrackingHold() {
        val actions = mutableListOf<RecordAction>()
        var state by mutableStateOf(RecordState(isServiceRunning = true))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SnorelyzerTheme {
                ActiveSleepSessionScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action == RecordAction.OnKeepRecordingClick) {
                            state = state.copy(
                                showShortSessionDialog = false,
                                stopTrackingHoldResetKey = state.stopTrackingHoldResetKey + 1
                            )
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            down(center)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(3_100)
        composeRule.onNodeWithTag(StopTrackingHoldButtonTag).performTouchInput {
            up()
        }

        state = state.copy(showShortSessionDialog = true)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Keep tracking").performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()
        assertEquals(
            listOf(
                RecordAction.OnStopClick,
                RecordAction.OnKeepRecordingClick
            ),
            actions
        )
    }
}
