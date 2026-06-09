package com.example.snorelyzer.presentation.record

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleStateShowsOnlyStartTracking() {
        composeRule.setContent {
            SnorelyzerTheme {
                RecordScreen(
                    state = RecordState(isServiceRunning = false),
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
        composeRule.onAllNodesWithText("Stop Tracking").assertCountEquals(0)
    }

    @Test
    fun activeStateShowsOnlyStopTracking() {
        composeRule.setContent {
            SnorelyzerTheme {
                RecordScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithText("Stop Tracking").assertIsDisplayed()
        composeRule.onAllNodesWithText("Start Tracking").assertCountEquals(0)
    }

    @Test
    fun buttonsDispatchRecordActions() {
        val actions = mutableListOf<RecordAction>()

        composeRule.setContent {
            SnorelyzerTheme {
                RecordScreen(
                    state = RecordState(isServiceRunning = false),
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithText("Start Tracking").performClick()

        composeRule.setContent {
            SnorelyzerTheme {
                RecordScreen(
                    state = RecordState(isServiceRunning = true),
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithText("Stop Tracking").performClick()

        assertEquals(
            listOf(RecordAction.OnStartClick, RecordAction.OnStopClick),
            actions
        )
    }
}
