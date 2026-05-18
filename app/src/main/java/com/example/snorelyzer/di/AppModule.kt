package com.example.snorelyzer.di

import com.example.snorelyzer.ml.AudioGate
import com.example.snorelyzer.ml.AudioProcessor
import com.example.snorelyzer.ml.SleepClassifier
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val appModule = module {
    factoryOf(::AudioProcessor)
    factoryOf(::AudioGate)
    factoryOf(::SleepClassifier)
}
