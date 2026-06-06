package com.example.snorelyzer.di

import androidx.room.Room
import com.example.snorelyzer.data.local.RoomRecordingMetadataSink
import com.example.snorelyzer.data.local.RoomSleepRecordingDataSource
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.data.local.SnorelyzerDatabase
import com.example.snorelyzer.ml.AudioGate
import com.example.snorelyzer.ml.AudioProcessor
import com.example.snorelyzer.ml.SleepClassifier
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.RecordingMetadataSink
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import com.example.snorelyzer.presentation.record.RecordViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            SnorelyzerDatabase::class.java,
            "snorelyzer.db"
        ).build()
    }
    single { get<SnorelyzerDatabase>().sleepRecordingDao }
    single<SleepRecordingLocalDataSource> { RoomSleepRecordingDataSource(dao = get()) }
    single<RecordingMetadataSink> { RoomRecordingMetadataSink(localDataSource = get()) }
    factoryOf(::AudioProcessor)
    factoryOf(::AudioGate)
    factoryOf(::SleepClassifier)
    single<AudioClipWriter> { WavAudioClipWriter(androidContext()) }
    factory { AudioEventRecorder(clipWriter = get(), metadataSink = get()) }
    viewModelOf(::RecordViewModel)
}
