package io.github.tuxwheel

import android.app.Application
import android.content.res.Configuration
import io.github.tuxwheel.di.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber
import io.github.tuxwheel.background.BackgroundWheelManager
import org.koin.core.context.GlobalContext

class TuxWheel : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TuxWheel)
            modules(listOf(settingModule, notificationsModule, volumeKeyModule, dbModule, bleModule, dashboardModule))
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        GlobalContext.get().get<BackgroundWheelManager>().start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleManager.setLocale(this)
    }
}