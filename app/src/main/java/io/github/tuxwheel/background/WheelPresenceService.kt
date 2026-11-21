package io.github.tuxwheel.background

import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi
import org.koin.android.ext.android.inject

@RequiresApi(31)
class WheelPresenceService : CompanionDeviceService() {
    private val manager: BackgroundWheelManager by inject()

    override fun onDeviceAppeared(address: String) {
        manager.appeared(address)
    }

    override fun onDeviceDisappeared(address: String) {
        manager.disappeared(address)
    }
}
