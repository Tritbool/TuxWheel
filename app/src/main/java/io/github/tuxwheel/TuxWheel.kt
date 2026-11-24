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
    lateinit var eventsLoggingTree: EventsLoggingTree
        private set

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TuxWheel)
            modules(listOf(settingModule, notificationsModule, volumeKeyModule, dbModule, bleModule, dashboardModule))
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Planted for the whole process: background-session events that happen after the
        // activities are gone (reboot wake, heartbeat, service death cause) must still reach
        // the in-app events page, which is the only diagnosis surface available without adb.
        eventsLoggingTree = EventsLoggingTree(this).also { Timber.plant(it) }
        GlobalContext.get().get<BackgroundWheelManager>().start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleManager.setLocale(this)
    }
}