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

                        SleepTrackerScreen(
                            latestStatus = latestStatus,
                            latestResults = latestResults,
                            isServiceRunning = isServiceRunning,
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
