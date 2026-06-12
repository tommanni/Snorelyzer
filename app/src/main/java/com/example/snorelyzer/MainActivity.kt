package com.example.snorelyzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.snorelyzer.presentation.SnorelyzerApp
import com.example.snorelyzer.ui.theme.SnorelyzerTheme

class MainActivity : ComponentActivity() {

    private var permissionResultCallback: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val hasRecordingPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        permissionResultCallback?.invoke(hasRecordingPermission)
        permissionResultCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SnorelyzerTheme {
                SnorelyzerApp(
                    onRequestRecordingPermission = ::checkPermissionsAndReport,
                    onStartRecordingService = ::startSleepTracker,
                    onStopRecordingService = ::stopSleepTracker,
                    onDiscardRecordingService = ::discardSleepTracker
                )
            }
        }
    }

    private fun checkPermissionsAndReport(onResult: (Boolean) -> Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            onResult(true)
        } else {
            permissionResultCallback = onResult
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startSleepTracker() {
        val intent = Intent(this, SleepTrackerService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSleepTracker() {
        val intent = Intent(this, SleepTrackerService::class.java).apply {
            action = SleepTrackerService.ACTION_STOP_SAVE
        }
        startService(intent)
    }

    private fun discardSleepTracker() {
        val intent = Intent(this, SleepTrackerService::class.java).apply {
            action = SleepTrackerService.ACTION_STOP_DISCARD
        }
        startService(intent)
    }
}
