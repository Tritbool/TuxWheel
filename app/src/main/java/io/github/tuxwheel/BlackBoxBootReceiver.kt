package io.github.tuxwheel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tuxwheel.utils.BlackBoxSupport
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Starts the background black box after the device boots or the app is updated, so
 * remembered wheels connect and get logged without opening the app.
 */
class BlackBoxBootReceiver : BroadcastReceiver(), KoinComponent {
    private val appConfig: AppConfig by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!appConfig.autoLog) return
        if (appConfig.rememberedWheels.isEmpty()) return
        if (!BlackBoxSupport.areBlePermissionsGranted(context)) {
            Timber.i("Black box not started from %s: BLE permissions missing", intent.action)
            return
        }
        BlackBoxSupport.startBlackBox(context)
        Timber.i("Black box logging started from %s", intent.action)
    }
}
