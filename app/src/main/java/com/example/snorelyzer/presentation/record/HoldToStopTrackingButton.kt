package com.example.snorelyzer.presentation.record

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun HoldToStopTrackingButton(
    onStopTracking: () -> Unit,
    resetKey: Int,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var hasDispatchedStop by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val backgroundColor = NavigationBarDefaults.containerColor
    val fillColor = Color.White
    val textColor = if (progress.value >= 0.5f) {
        Color.Black
    } else {
        contentColorFor(backgroundColor)
    }

    LaunchedEffect(resetKey) {
        progress.snapTo(0f)
        isHolding = false
        hasDispatchedStop = false
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(backgroundColor)
            .testTag(StopTrackingHoldButtonTag)
            .pointerInput(onStopTracking) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        hasDispatchedStop = false
                        progress.snapTo(0f)

                        coroutineScope {
                            val animationJob = launch {
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = StopTrackingHoldDurationMillis,
                                        easing = LinearEasing
                                    )
                                )
                                hasDispatchedStop = true
                                onStopTracking()
                            }

                            tryAwaitRelease()
                            if (!hasDispatchedStop) {
                                animationJob.cancel()
                                progress.snapTo(0f)
                                isHolding = false
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .testTag(StopTrackingHoldProgressTag)
                .background(fillColor)
        )

        Text(
            text = if (isHolding) progress.value.toCountdownLabel() else "Stop Tracking",
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
    }
}

private fun Float.toCountdownLabel(): String {
    return (3 - (this * 3).toInt())
        .coerceIn(1, 3)
        .toString()
}

private const val StopTrackingHoldDurationMillis = 3_000
internal const val StopTrackingHoldButtonTag = "stop_tracking_hold_button"
internal const val StopTrackingHoldProgressTag = "stop_tracking_hold_progress"
