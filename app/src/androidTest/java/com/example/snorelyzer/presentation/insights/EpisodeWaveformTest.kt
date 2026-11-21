package com.example.snorelyzer.presentation.insights

import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.example.snorelyzer.data.local.RecordingEpisodeEntity
import com.example.snorelyzer.data.local.RecordingEpisodeWithDetails
import com.example.snorelyzer.data.local.RecordingSessionEntity
import com.example.snorelyzer.data.local.RecordingSessionWithEpisodes
import com.example.snorelyzer.data.local.SleepNight
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.ml.recording.AudioClipWriteRequest
import com.example.snorelyzer.ml.recording.RecordingEpisodeMetadata
import com.example.snorelyzer.ml.recording.RecordingSessionMetadata
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class EpisodeWaveformTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION") // Window flags also support the app's API 26 minimum.
    @Test
    fun tappingWaveformCuesPlaybackAndThePlayheadFollowsThePlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clips = Files.createTempDirectory(context.cacheDir.toPath(), "waveform-test").toFile()
        val store = ViewModelStore()
        try {
            val writer = WavAudioClipWriter(clips)
            val episodes = listOf("first", "second").map { id ->
                val clip = writer.writeClip(
                    AudioClipWriteRequest(
                        sessionId = "session",
                        episodeId = id,
                        samples = FloatArray(64_000),
                        sampleRate = 16_000,
                        clipStartMillis = 0L,
                        clipEndMillis = 4_000L
                    )
                )
                RecordingEpisodeWithDetails(
                    episode = RecordingEpisodeEntity(
                        episodeId = id,
                        sessionId = "session",
                        clipStartMillis = 0L,
                        clipEndMillis = 4_000L,
                        durationMillis = clip.durationMillis,
                        sampleRate = clip.sampleRate,
                        filePath = clip.filePath
                    ),
                    groups = emptyList(),
                    eventSpans = emptyList()
                )
            }
            val night = SleepNight(
                date = LocalDate.of(2026, 11, 21),
                sessions = listOf(RecordingSessionWithEpisodes(RecordingSessionEntity("session", 0L, 4_000L), episodes))
            )
            val activity = composeRule.activity
            val viewModel = composeRule.runOnUiThread {
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
                ViewModelProvider.create(store, viewModelFactory {
                    initializer { SessionInsightsViewModel(FakeWaveformDataSource(night), ExoPlayer.Builder(context).build()) }
                })[SessionInsightsViewModel::class]
            }

            composeRule.setContent {
                SnorelyzerTheme {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val playback = viewModel.playback.collectAsStateWithLifecycle()
                    SessionInsightsScreen(state, viewModel::onAction, playback = { playback.value })
                }
            }

            composeRule.waitUntil(5_000) { viewModel.state.value.selectedNight != null }
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
            val firstWaveform = composeRule.onAllNodesWithContentDescription("Seek audio clip")[0]
            composeRule.waitUntil(5_000) {
                viewModel.state.value.waveformsByPath[episodes.first().episode.filePath] is ClipWaveform.Peaks
            }
            firstWaveform.performTouchInput { click(center) }
            composeRule.waitUntil(5_000) { (viewModel.playback.value?.positionMillis ?: -1L) in 1_950L..2_050L }
            assertFalse(viewModel.playback.value!!.isPlayRequested)

            composeRule.onAllNodesWithContentDescription("Play audio")[0].performClick()
            composeRule.waitUntil(5_000) { (viewModel.playback.value?.positionMillis ?: -1L) in 2_150L..3_000L }
            firstWaveform.performTouchInput { click(Offset(width * 0.85f, centerY)) }
            composeRule.waitUntil(5_000) {
                val playback = viewModel.playback.value!!
                playback.isPlaying && playback.positionMillis >= 3_350L
            }
            composeRule.waitUntil(5_000) {
                val playback = viewModel.playback.value!!
                !playback.isPlayRequested && playback.positionMillis == playback.durationMillis
            }
            firstWaveform.assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f))
            )

            composeRule.onAllNodesWithContentDescription("Play audio")[0].performClick()
            composeRule.waitUntil(5_000) { viewModel.playback.value?.isPlaying == true }
            composeRule.onNodeWithContentDescription("Pause audio").performClick()
            composeRule.waitUntil(5_000) { viewModel.playback.value?.isPlaying == false }

            val secondWaveform = composeRule.onAllNodesWithContentDescription("Seek audio clip")[1]
            secondWaveform.performScrollTo().performTouchInput { click(center) }
            composeRule.waitUntil(5_000) { viewModel.playback.value?.episodeId == "second" }
            assertFalse(viewModel.playback.value!!.isPlayRequested)
        } finally {
            composeRule.runOnUiThread { store.clear() }
            clips.deleteRecursively()
        }
    }
}

private class FakeWaveformDataSource(night: SleepNight) : SleepRecordingLocalDataSource {
    private val nights = flowOf(listOf(night))

    override fun observeSleepNights(zoneId: ZoneId) = nights
    override fun observeSessions() = emptyFlow<List<RecordingSessionWithEpisodes>>()
    override fun observeEpisodesForSession(sessionId: String) = emptyFlow<List<RecordingEpisodeWithDetails>>()
    override suspend fun upsertSession(session: RecordingSessionMetadata) = Unit
    override suspend fun insertEpisode(episode: RecordingEpisodeMetadata) = Unit
    override suspend fun completeSession(session: RecordingSessionMetadata) = Unit
    override suspend fun deleteSession(sessionId: String) = Unit
    override suspend fun deleteFinishedSessions(sessionIds: List<String>) = Unit
    override suspend fun deleteAllSessions() = Unit
}
