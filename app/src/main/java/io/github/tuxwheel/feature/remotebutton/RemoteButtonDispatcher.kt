package io.github.tuxwheel.feature.remotebutton

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.feature.dashboard.DashboardActions
import io.github.tuxwheel.feature.speedlimiter.SpeedLimiter
import io.github.tuxwheel.utils.Alarms
import io.github.tuxwheel.utils.SomeUtil
import kotlin.math.abs

/**
 * Funnel for hardware button presses from any Bluetooth HID remote (AB Shutter 3 and friends).
 *
 * Sources feed [handleKey]: the activity's key dispatch while TuxWheel is foreground, and a
 * media-session volume provider (see [io.github.tuxwheel.utils.VolumeKeyController]) while the
 * screen is off or another app is in front. An empty binding list catches nothing.
 */
@SuppressLint("MissingPermission")
class RemoteButtonDispatcher(
    private val context: Context,
    private val config: AppConfig,
    private val session: BleSessionViewModel,
) {
    var learningSlot: Int? = null
    var onKeyLearned: ((slot: Int, keyCode: Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHandledKey = 0
    private var lastHandledAt = 0L

    fun hasActiveBindings(): Boolean = RemoteButtonBindings.parse(config.remoteButtonBindings).isNotEmpty()

    fun handleKey(keyCode: Int): Boolean {
        if (learningSlot?.let { it in 0 until RemoteButtonBindings.MAX_SLOTS } == true &&
            keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_HOME
        ) {
            val slot = learningSlot!!
            mainHandler.post { onKeyLearned?.invoke(slot, keyCode) }
            return true
        }
        val bindings = RemoteButtonBindings.parse(config.remoteButtonBindings)
        val action = RemoteButtonBindings.actionFor(bindings, keyCode) ?: return false
        val now = SystemClock.uptimeMillis()
        if (keyCode == lastHandledKey && now - lastHandledAt < REPEAT_THROTTLE_MS) return true
        lastHandledKey = keyCode
        lastHandledAt = now
        mainHandler.post { if (runAction(action)) Alarms.vibrate(context, longArrayOf(0, 40)) }
        return true
    }

    private fun runAction(action: RemoteButtonAction): Boolean {
        val state = session.sessionState.value
        if (!state.isConnected) return false
        return when (action) {
            RemoteButtonAction.ToggleSpeedLimiter -> SpeedLimiter.toggle(session, config)
            RemoteButtonAction.ToggleLight -> {
                DashboardActions.toggleLight(session, config)
                true
            }
            RemoteButtonAction.ToggleWheelLock -> toggleWheelLock(state.currentSpeed)
            RemoteButtonAction.Beep -> {
                SomeUtil.playBeep()
                true
            }
        }
    }

    private fun toggleWheelLock(speedKmh: Double): Boolean {
        if (abs(speedKmh) > MOVING_SPEED_KMH) return false
        session.toggleWheelLock()
        return true
    }

    companion object {
        private const val REPEAT_THROTTLE_MS = 500L
        private const val MOVING_SPEED_KMH = 1.0
    }
}
