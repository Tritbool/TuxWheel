package io.github.tuxwheel.di

import io.github.tuxwheel.feature.remotebutton.RemoteButtonDispatcher
import io.github.tuxwheel.utils.VolumeKeyController
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val volumeKeyModule = module {
    single { RemoteButtonDispatcher(androidApplication(), get(), get()) }
    single { VolumeKeyController(androidApplication(), get()) }
}
