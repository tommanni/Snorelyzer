package com.example.snorelyzer.di

import androidx.room.Room
import com.example.snorelyzer.SleepTrackingSessionController
import com.example.snorelyzer.SleepTrackingSessionStateSource
import com.example.snorelyzer.data.local.RoomRecordingMetadataSink
import com.example.snorelyzer.data.local.RoomSleepRecordingDataSource
import com.example.snorelyzer.data.local.SleepRecordingLocalDataSource
import com.example.snorelyzer.data.local.SnorelyzerDatabase
import com.example.snorelyzer.ml.AudioGate
import com.example.snorelyzer.ml.AudioProcessingPipeline
import com.example.snorelyzer.ml.AudioProcessor
import com.example.snorelyzer.ml.MelSpectrogramProcessor
import com.example.snorelyzer.ml.RelevantSleepClassifier
import com.example.snorelyzer.ml.SleepClassifier
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.RecordedEventConfig
import com.example.snorelyzer.ml.recording.RecordingMetadataSink
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import com.example.snorelyzer.presentation.insights.AnalyticsInsightsViewModel
import com.example.snorelyzer.presentation.insights.InsightsViewModel
import com.example.snorelyzer.presentation.insights.SessionInsightsViewModel
import com.example.snorelyzer.presentation.record.RecordViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.core.module.dsl.viewModel

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
    single { RecordedEventConfig() }
    factory<MelSpectrogramProcessor> { AudioProcessor(androidContext()) }
    factoryOf(::AudioGate)
    factory<RelevantSleepClassifier> { SleepClassifier(androidContext()) }
    factory {
        AudioProcessingPipeline(
            audioProcessor = get(),
            audioGate = get(),
            classifier = get(),
            recordedEventConfig = get()
        )
    }
    single<AudioClipWriter> { WavAudioClipWriter(androidContext()) }
    factory { AudioEventRecorder(clipWriter = get(), metadataSink = get()) }
    single {
        SleepTrackingSessionController(
            audioEventRecorder = get(),
            audioProcessingPipelineFactory = { get<AudioProcessingPipeline>() }
        )
    }
    single<SleepTrackingSessionStateSource> { get<SleepTrackingSessionController>() }
    viewModelOf(::InsightsViewModel)
    viewModelOf(::SessionInsightsViewModel)
    viewModelOf(::AnalyticsInsightsViewModel)
    viewModel { RecordViewModel(sessionStateSource = get()) }
}
