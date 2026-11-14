package com.example.snorelyzer.presentation.insights

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NightDateControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigationSkipsMissingNightsAndDisablesAtBoundaries() {
        val newest = LocalDate.of(2026, 11, 14)
        val oldest = newest.minusDays(5)
        var selected by mutableStateOf(newest)
        composeRule.setContent {
            SnorelyzerTheme {
                NightDateNavigation(selected, setOf(oldest, newest), { selected = it }, {})
            }
        }
        composeRule.onNodeWithContentDescription("Next recorded night").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Previous recorded night").performClick()
        composeRule.runOnIdle { assertEquals(oldest, selected) }
        composeRule.onNodeWithContentDescription("Previous recorded night").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next recorded night").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(newest, selected) }
    }

    @Test
    fun cancelDoesNotCommitCalendarSelection() {
        val date = LocalDate.of(2026, 11, 14)
        var selected: LocalDate? = null
        var dismissed = false
        composeRule.setContent {
            SnorelyzerTheme {
                NightDatePickerDialog(date, setOf(date), { selected = it }, { dismissed = true })
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(null, selected)
            assertEquals(true, dismissed)
        }
    }

    @Test
    fun confirmationReturnsTheSameCalendarDate() {
        val date = LocalDate.of(2026, 11, 14)
        var selected: LocalDate? = null
        composeRule.setContent {
            SnorelyzerTheme {
                NightDatePickerDialog(date, setOf(date), { selected = it }, {})
            }
        }
        composeRule.onNodeWithText("Show night").performClick()
        composeRule.runOnIdle { assertEquals(date, selected) }
    }
}
