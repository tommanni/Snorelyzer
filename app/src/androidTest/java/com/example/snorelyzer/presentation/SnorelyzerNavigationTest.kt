package com.example.snorelyzer.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.example.snorelyzer.MainActivity
import org.junit.Rule
import org.junit.Test

class SnorelyzerNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsOnRecordScreen() {
        composeRule.onNodeWithText("Record").assertIsDisplayed()
        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
        composeRule.onAllNodesWithText("Stop Tracking").assertCountEquals(0)
    }

    @Test
    fun bottomNavigationSwitchesBetweenTopLevelScreens() {
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("Session").assertIsDisplayed()
        composeRule.onNodeWithText("Analytics").assertIsDisplayed()
        composeRule.onNodeWithText("Session").assertIsSelected()
        composeRule.onNodeWithText("Analytics").assertIsNotSelected()
        composeRule.onNodeWithText("Session insights content will appear here.").assertIsDisplayed()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Privacy policy").assertIsDisplayed()

        composeRule.onNodeWithText("Record").performClick()
        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
        composeRule.onAllNodesWithText("Stop Tracking").assertCountEquals(0)
    }

    @Test
    fun insightsTabsSwitchVisibleContent() {
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("Session insights content will appear here.").assertIsDisplayed()

        composeRule.onNodeWithText("Analytics").performClick()
        composeRule.onNodeWithText("Analytics").assertIsSelected()
        composeRule.onNodeWithText("Analytics insights content will appear here.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Session insights content will appear here.").assertCountEquals(0)

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("Analytics").assertIsSelected()
        composeRule.onNodeWithText("Analytics insights content will appear here.").assertIsDisplayed()

        composeRule.onNodeWithText("Session").performClick()
        composeRule.onNodeWithText("Session").assertIsSelected()
        composeRule.onNodeWithText("Session insights content will appear here.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Analytics insights content will appear here.").assertCountEquals(0)
    }

    @Test
    fun backFromAnalyticsReturnsToSessionBeforeLeavingInsights() {
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("Analytics").performClick()
        composeRule.onNodeWithText("Analytics").assertIsSelected()
        composeRule.onNodeWithText("Analytics insights content will appear here.").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithText("Session").assertIsSelected()
        composeRule.onNodeWithText("Session insights content will appear here.").assertIsDisplayed()

        pressBack()
        composeRule.onNodeWithText("Start Tracking").assertIsDisplayed()
    }
}
