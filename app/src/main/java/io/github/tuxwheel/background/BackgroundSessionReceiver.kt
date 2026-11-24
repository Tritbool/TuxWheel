package io.github.tuxwheel.background

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class BackgroundSessionReceiver : BroadcastReceiver(), KoinComponent {
    private val manager: BackgroundWheelManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("Background broadcast received: %s", intent.action)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            BackgroundWheelManager.ACTION_SESSION_HEARTBEAT -> manager.restore()
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
                ) manager.restore()
            }
        }
    }
}
