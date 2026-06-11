package com.example.snorelyzer.presentation.insights

import org.junit.Assert.assertFalse
import org.junit.Test

class AnalyticsInsightsViewModelTest {

    @Test
    fun `initial state is not loading`() {
        val viewModel = AnalyticsInsightsViewModel()

        assertFalse(viewModel.state.value.isLoading)
    }
}
