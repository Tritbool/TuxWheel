package io.github.tuxwheel.feature.remotebutton

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.feature.light.WheelLightCycle
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
 *
 * A binding captured with a device descriptor only fires for that device: in the foreground the
 * hardware volume rocker and screen taps carry a different InputDevice descriptor and are left
 * alone, and through the media session (which does not identify the source device) the press is
 * honored only while the captured remote is still connected. Legacy bindings without a device
 * match any source, as before the capture supported devices.
 */
@SuppressLint("MissingPermission")
class RemoteButtonDispatcher(
    private val context: Context,
    private val config: AppConfig,
    private val session: BleSessionViewModel,
) {
    var learningSlot: Int? = null
    var onKeyLearned: ((slot: Int, binding: RemoteButtonBinding) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHandledKey = 0
    private var lastHandledAt = 0L

    fun hasActiveBindings(): Boolean = RemoteButtonBindings.parse(config.remoteButtonBindings).isNotEmpty()

    /** True when at least one captured remote is currently connected as an input device. */
    fun hasConnectedCaptureDevice(): Boolean {
        val bindings = RemoteButtonBindings.parse(config.remoteButtonBindings)
        val connected = connectedInputDevices()
        return bindings.any { it.deviceId == null || it.deviceId in connected }
    }

    /**
     * @param sourceDescriptor InputDevice descriptor of the key event, or null when the source
     * device is unknown (media-session volume path).
     */
    fun handleKey(keyCode: Int, sourceDescriptor: String? = null): Boolean {
        if (learningSlot?.let { it in 0 until RemoteButtonBindings.MAX_SLOTS } == true &&
            keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_HOME
        ) {
            val slot = learningSlot!!
            val binding = RemoteButtonBinding(
                keyCode = keyCode,
                action = RemoteButtonAction.ToggleSpeedLimiter,
                deviceId = sourceDescriptor,
                deviceName = sourceDescriptor?.let(::nameFor),
            )
            mainHandler.post { onKeyLearned?.invoke(slot, binding) }
            return true
        }
        val bindings = RemoteButtonBindings.parse(config.remoteButtonBindings)
        val binding = RemoteButtonBindings.forKey(bindings, keyCode)?.takeIf { matchesSource(it, sourceDescriptor) }
            ?: return false
        val now = SystemClock.uptimeMillis()
        if (keyCode == lastHandledKey && now - lastHandledAt < REPEAT_THROTTLE_MS) return true
        lastHandledKey = keyCode
        lastHandledAt = now
        mainHandler.post { if (runAction(binding.action)) Alarms.vibrate(context, longArrayOf(0, 40)) }
        return true
    }

    private fun matchesSource(binding: RemoteButtonBinding, sourceDescriptor: String?): Boolean {
        val deviceId = binding.deviceId ?: return true
        if (sourceDescriptor != null) return sourceDescriptor == deviceId
        return deviceId in connectedInputDevices()
    }

    private fun connectedInputDevices(): Set<String> {
        val descriptors = mutableSetOf<String>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isExternal) descriptors.add(device.descriptor)
        }
        return descriptors
    }

    private fun nameFor(descriptor: String): String? {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.descriptor == descriptor) return device.name
        }
        return null
    }

    private fun runAction(action: RemoteButtonAction): Boolean {
        val state = session.sessionState.value
        if (!state.isConnected) return false
        return when (action) {
            RemoteButtonAction.ToggleSpeedLimiter -> SpeedLimiter.toggle(session, config)
            RemoteButtonAction.ToggleLight -> {
                WheelLightCycle.advance(session, config)
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
