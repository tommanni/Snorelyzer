package com.example.snorelyzer.presentation.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsViewModelTest {

    @Test
    fun `initial selected tab is session`() {
        val viewModel = InsightsViewModel()

        assertEquals(InsightsTab.Session, viewModel.state.value.selectedTab)
    }

    @Test
    fun `tab selection updates selected tab`() {
        val viewModel = InsightsViewModel()

        viewModel.onAction(InsightsAction.OnTabSelected(InsightsTab.Analytics))

        assertEquals(InsightsTab.Analytics, viewModel.state.value.selectedTab)
    }

    @Test
    fun `session tab can be selected after analytics`() {
        val viewModel = InsightsViewModel()

        viewModel.onAction(InsightsAction.OnTabSelected(InsightsTab.Analytics))
        viewModel.onAction(InsightsAction.OnTabSelected(InsightsTab.Session))

        assertEquals(InsightsTab.Session, viewModel.state.value.selectedTab)
    }
}
