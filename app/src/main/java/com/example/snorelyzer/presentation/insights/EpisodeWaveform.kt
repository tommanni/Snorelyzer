package com.example.snorelyzer.presentation.insights

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.snorelyzer.R
import com.example.snorelyzer.ui.theme.SnorelyzerTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeWaveform(
    peaks: List<Float>?,
    durationMillis: Long,
    positionMillis: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubPosition by remember(durationMillis) { mutableStateOf<Long?>(null) }
    val position = (scrubPosition ?: positionMillis).coerceIn(0L, durationMillis)
    val progress = position.toFloat() / durationMillis
    val elapsedSeconds = position / 1_000L
    val durationSeconds = durationMillis / 1_000L
    val elapsed = remember(elapsedSeconds) { formatClipTime(elapsedSeconds) }
    val duration = remember(durationSeconds) { formatClipTime(durationSeconds) }
    val seekLabel = stringResource(R.string.insights_seek_clip)
    val timeDescription = stringResource(R.string.insights_clip_position, elapsed, duration)
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Column(modifier = modifier) {
        Slider(
            value = progress,
            onValueChange = { scrubPosition = (it.toDouble() * durationMillis).roundToLong() },
            onValueChangeFinished = {
                scrubPosition?.let(onSeek)
                scrubPosition = null
            },
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = seekLabel
                    stateDescription = timeDescription
                },
            track = {
                WaveformTrack(peaks = peaks, progress = progress)
            },
            thumb = {
                Canvas(Modifier.width(4.dp).height(40.dp)) {
                    drawLine(
                        color = if (focused) colors.onSurface else colors.primary,
                        start = Offset(center.x, 2.dp.toPx()),
                        end = Offset(center.x, size.height - 2.dp.toPx()),
                        strokeWidth = (if (focused) 4.dp else 2.dp).toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        )
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            val timeStyle = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = "tnum"))
            Text(elapsed, color = colors.onSurfaceVariant, style = timeStyle)
            Text(duration, color = colors.onSurfaceVariant, style = timeStyle)
        }
    }
}

@Composable
private fun WaveformTrack(peaks: List<Float>?, progress: Float) {
    val colors = MaterialTheme.colorScheme
    val peakScale = remember(peaks) { peaks?.maxOrNull()?.takeIf { it > 0f } ?: 1f }
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        if (peaks.isNullOrEmpty()) {
            val start = if (isRtl) size.width else 0f
            val end = size.width - start
            val playhead = start + (end - start) * progress
            drawLine(colors.outlineVariant, Offset(start, center.y), Offset(end, center.y), 2.dp.toPx())
            drawLine(colors.primary, Offset(start, center.y), Offset(playhead, center.y), 2.dp.toPx())
            return@Canvas
        }

        val barCount = (size.width / 3.dp.toPx()).toInt().coerceIn(1, peaks.size)
        val spacing = size.width / barCount
        val stroke = minOf(2.dp.toPx(), spacing * 0.6f)
        for (index in 0 until barCount) {
            val start = index * peaks.size / barCount
            val end = (index + 1) * peaks.size / barCount
            var peak = 0f
            for (sample in start until end) peak = maxOf(peak, peaks[sample])

            val barCenter = (index + 0.5f) * spacing
            val x = if (isRtl) size.width - barCenter else barCenter
            val halfHeight = maxOf(1.dp.toPx(), 17.dp.toPx() * (peak / peakScale))
            drawLine(
                color = if (barCenter <= size.width * progress) colors.primary else colors.outline,
                start = Offset(x, center.y - halfHeight),
                end = Offset(x, center.y + halfHeight),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatClipTime(seconds: Long): String =
    String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L)

internal val previewWaveformPeaks = List(96) { index ->
    val distance = abs(index % 19 - 9)
    if (distance > 5) 0.07f else (6 - distance) * (if (index % 2 == 0) 0.12f else 0.15f)
}

@Preview(name = "Episode waveform - Light", widthDp = 360)
@Preview(name = "Episode waveform - Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EpisodeWaveformPreview() {
    SnorelyzerTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            EpisodeWaveform(
                peaks = previewWaveformPeaks,
                durationMillis = 24_000L,
                positionMillis = 10_000L,
                onSeek = {},
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
