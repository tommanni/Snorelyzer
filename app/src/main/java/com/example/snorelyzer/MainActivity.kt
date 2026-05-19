package com.example.snorelyzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import com.example.snorelyzer.ui.theme.SnorelyzerTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            startSleepTracker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SnorelyzerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val latestStatus by SleepTrackerService.latestStatus.collectAsState()
                        val latestResults by SleepTrackerService.latestResults.collectAsState()
                        val isServiceRunning by SleepTrackerService.isServiceRunning.collectAsState()
                        val nightSummary by SleepTrackerService.nightSummary.collectAsState()
                        val nowMillis = System.currentTimeMillis()

                        SleepTrackerScreen(
                            latestStatus = latestStatus,
                            latestResults = latestResults,
                            isServiceRunning = isServiceRunning,
                            nightSummary = nightSummary,
                            nowMillis = nowMillis,
                            onStartClick = { checkPermissionsAndStart() },
                            onStopClick = { stopSleepTracker() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SleepTrackerScreen(
        latestStatus: String,
        latestResults: List<DetectedClassUi>,
        isServiceRunning: Boolean,
        nightSummary: NightSummaryUi,
        nowMillis: Long,
        onStartClick: () -> Unit,
        onStopClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sleep Tracker",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Text(
                text = if (isServiceRunning) "Tracking" else latestStatus,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            LatestResultsSection(
                latestStatus = latestStatus,
                latestResults = latestResults
            )

            NightSummarySection(
                summary = nightSummary,
                nowMillis = nowMillis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }

                Button(
                    onClick = onStopClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop")
                }
            }
        }
    }

    @Composable
    private fun LatestResultsSection(
        latestStatus: String,
        latestResults: List<DetectedClassUi>
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Latest sounds",
                style = MaterialTheme.typography.titleMedium
            )

            if (latestResults.isEmpty()) {
                Text(
                    text = latestStatus,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                latestResults.forEach { result ->
                    SummaryRow(
                        label = result.label,
                        value = "${result.probabilityPercent}%"
                    )
                }
            }
        }
    }

    @Composable
    private fun NightSummarySection(
        summary: NightSummaryUi,
        nowMillis: Long
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Night summary",
                style = MaterialTheme.typography.titleMedium
            )

            if (!summary.hasSession) {
                Text(
                    text = "No night summary yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return
            }

            SummaryRow("Duration", formatDuration(summary.durationMillis(nowMillis)))
            SummaryRow("Gate opened", "${summary.inferenceChunks}/${summary.totalChunks} (${summary.gateOpenPercent}%)")
            SummaryRow("Skipped background", summary.skippedBackgroundChunks.toString())
            SummaryRow("Noise floor", formatRange(summary.minNoiseFloorDb, summary.maxNoiseFloorDb, "dB"))
            SummaryRow("Max relative dB", formatFloat(summary.maxObservedRelativeDb, "dB"))
            SummaryRow("Max frame-relative dB", formatFloat(summary.maxObservedMaxFrameRelativeDb, "dB"))
            SummaryRow("Max crest delta", formatFloat(summary.maxObservedCrestDelta, ""))

            if (summary.triggerCounts.isNotEmpty()) {
                Text(
                    text = "Triggers",
                    style = MaterialTheme.typography.titleSmall
                )
                summary.triggerCounts.forEach { (trigger, count) ->
                    SummaryRow(trigger, count.toString())
                }
            }

            if (summary.reasonCounts.isNotEmpty()) {
                Text(
                    text = "Gate reasons",
                    style = MaterialTheme.typography.titleSmall
                )
                summary.reasonCounts.entries.take(5).forEach { (reason, count) ->
                    SummaryRow(reason, count.toString())
                }
            }

            if (summary.topLabels.isNotEmpty()) {
                Text(
                    text = "Top labels",
                    style = MaterialTheme.typography.titleSmall
                )
                summary.topLabels.forEach { label ->
                    SummaryRow(
                        label = label.label,
                        value = "top1 ${label.top1Count}, seen ${label.appearanceCount}, avg ${label.averageConfidencePercent}%, max ${label.maxConfidencePercent}%"
                    )
                }
            }
        }
    }

    @Composable
    private fun SummaryRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m ${seconds}s"
        }
    }

    private fun formatRange(minValue: Float?, maxValue: Float?, suffix: String): String {
        if (minValue == null || maxValue == null) return "-"
        return "${formatDecimal(minValue)} to ${formatDecimal(maxValue)} $suffix".trim()
    }

    private fun formatFloat(value: Float?, suffix: String): String {
        if (value == null) return "-"
        return "${formatDecimal(value)} $suffix".trim()
    }

    private fun formatDecimal(value: Float): String {
        return "%.1f".format(value)
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startSleepTracker()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startSleepTracker() {
        val intent = Intent(this, SleepTrackerService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSleepTracker() {
        val intent = Intent(this, SleepTrackerService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }
}
