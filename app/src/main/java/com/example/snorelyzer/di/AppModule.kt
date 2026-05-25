package com.example.snorelyzer.di

import com.example.snorelyzer.ml.AudioGate
import com.example.snorelyzer.ml.AudioProcessor
import com.example.snorelyzer.ml.SleepClassifier
import com.example.snorelyzer.ml.recording.AudioClipWriter
import com.example.snorelyzer.ml.recording.AudioEventRecorder
import com.example.snorelyzer.ml.recording.WavAudioClipWriter
import com.example.snorelyzer.presentation.record.RecordViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    factoryOf(::AudioProcessor)
    factoryOf(::AudioGate)
    factoryOf(::SleepClassifier)
    single<AudioClipWriter> { WavAudioClipWriter(androidContext()) }
    factory { AudioEventRecorder(clipWriter = get()) }
    viewModelOf(::RecordViewModel)
}
