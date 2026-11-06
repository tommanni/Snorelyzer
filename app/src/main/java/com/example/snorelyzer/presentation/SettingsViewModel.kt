package com.example.snorelyzer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.ml.recording.AudioClipWriter
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val localDataSource: SleepRecordingLocalDataSource,
    private val audioClipWriter: AudioClipWriter
) : ViewModel() {

    fun clearAllData() {
        viewModelScope.launch {
            localDataSource.deleteAllSessions()
            audioClipWriter.deleteAllClips()
        }
    }
}
