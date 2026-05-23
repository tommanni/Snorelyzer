package com.example.snorelyzer.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.snorelyzer.MainActivity
import org.junit.Rule
import org.junit.Test

class SnorelyzerNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsOnRecordScreen() {
        composeRule.onNodeWithText("Record").assertIsDisplayed()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationSwitchesBetweenTopLevelScreens() {
        composeRule.onNodeWithText("Insights").performClick()
        composeRule.onNodeWithText("Sleep clips and metadata visualizations will appear here.")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
        composeRule.onNodeWithText("Privacy policy").assertIsDisplayed()

        composeRule.onNodeWithText("Record").performClick()
        composeRule.onNodeWithText("Start").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
    }
}
