package com.example.snorelyzer.di

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import com.example.snorelyzer.SleepTrackingSessionController
import com.example.snorelyzer.SleepTrackingSessionStateSource
import com.example.snorelyzer.data.local.AudioRetentionPreferences
import com.example.snorelyzer.data.local.RecordingAudioAccess
import com.example.snorelyzer.data.local.RecordingAudioCleanupSchedule
import com.example.snorelyzer.data.local.RecordingAudioRetention
import com.example.snorelyzer.data.local.RecordingAudioRetentionDataSource
import com.example.snorelyzer.data.local.RecordingInterruptionStore
import com.example.snorelyzer.data.local.RecordingMaintenance
import com.example.snorelyzer.data.local.RecordingRetentionMigration
import com.example.snorelyzer.data.local.RoomRecordingMetadataSink
import com.example.snorelyzer.data.local.RoomSleepRecordingDataSource
import com.example.snorelyzer.data.local.SleepDataDeletion
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
import com.example.snorelyzer.presentation.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            SnorelyzerDatabase::class.java,
            "snorelyzer.db"
        ).addMigrations(RecordingRetentionMigration).build()
    }
    single { get<SnorelyzerDatabase>().sleepRecordingDao }
    singleOf(::RoomSleepRecordingDataSource) binds arrayOf(
        SleepRecordingLocalDataSource::class,
        RecordingAudioRetentionDataSource::class
    )
    singleOf(::RecordingAudioAccess)
    singleOf(::RecordingMaintenance)
    single { AudioRetentionPreferences(androidContext()) }
    single {
        val context = androidContext()
        RecordingAudioRetention(get(), get(), get(), get()) { RecordingAudioCleanupSchedule.now(context) }
    }
    singleOf(::RoomRecordingMetadataSink) bind RecordingMetadataSink::class
    singleOf(::SleepDataDeletion)
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
    single { RecordingInterruptionStore(androidContext()) }
    single {
        SleepTrackingSessionController(
            audioEventRecorder = get(),
            audioProcessingPipelineFactory = { get<AudioProcessingPipeline>() },
            initialInterruptedAtMillis = get<RecordingInterruptionStore>().interruptedAtMillis
        )
    }
    single<SleepTrackingSessionStateSource> { get<SleepTrackingSessionController>() }
    factory<Player> {
        ExoPlayer.Builder(androidContext()).build()
    }
    viewModelOf(::InsightsViewModel)
    viewModelOf(::SessionInsightsViewModel)
    viewModelOf(::AnalyticsInsightsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { RecordViewModel(sessionStateSource = get()) }
}
