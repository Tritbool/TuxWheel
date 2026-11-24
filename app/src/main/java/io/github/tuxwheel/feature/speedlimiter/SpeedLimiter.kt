package io.github.tuxwheel.feature.speedlimiter

import android.annotation.SuppressLint
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.ble.getWheelType
import io.github.tuxwheel.utils.Constants
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue

/**
 * App-side speed limiter built on [CommandType.SET_SPEED_LIMIT].
 *
 * Activating it stores the user's own wheel max speed and pushes the limiter speed to the
 * wheel; deactivating it pushes the saved value back to the wheel. All state — whether the
 * limiter is on, its speed and the parked maximum — is keyed by wheel address: switching from
 * the S18 limited at 40 km/h to the Rocket must never announce the S18's saved maximum as the
 * Rocket's own.
 */
object SpeedLimiter {
    const val MIN_SPEED = 1
    const val MAX_SPEED = 100

    /** KingSong encodes the limit together with the alarm speeds, so they must be known first. */
    fun isAvailable(
        session: BleSessionViewModel,
        state: BleSessionState = session.sessionState.value
    ): Boolean {
        val range = state.commandDescriptors.firstOrNull { it.id == CommandType.SET_SPEED_LIMIT.name }
            ?.input as? CommandInput.Range
        if (!state.isConnected || range == null ||
            range.unit.lowercase() !in setOf("km/h", "kmh") ||
            range.scale != 1.0 ||
            range.min > range.max ||
            range.step <= 0 ||
            (range.max.toLong() - range.min.toLong()) % range.step.toLong() != 0L ||
            !session.isCommandSupported(CommandType.SET_SPEED_LIMIT)
        ) return false
        if (state.getWheelType() != Constants.WHEEL_TYPE.KINGSONG) return true
        return reportedMaxSpeed(state) != null && state.alarm1Speed != null &&
            state.alarm2Speed != null && state.alarm3Speed != null
    }

    private fun addressOf(state: BleSessionState): String = state.deviceAddress.uppercase()

    /** The wheel max speed currently configured by the user (before any limiter is applied). */
    fun currentWheelMaxSpeed(state: BleSessionState, config: AppConfig): Int =
        reportedMaxSpeed(state)
            ?: config.speedLimiterSavedMaxSpeedFor(addressOf(state)).takeIf { it > 0 }
            ?: config.wheelMaxSpeed

    @SuppressLint("MissingPermission")
    fun enable(session: BleSessionViewModel, config: AppConfig): Boolean {
        val state = session.sessionState.value
        val address = addressOf(state)
        if (config.speedLimiterEnabledFor(address)) return true
        if (!isAvailable(session, state)) return false
        val limit = config.speedLimiterSpeedFor(address)
        if (!session.isCommandValueSupported(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))) {
            return false
        }
        config.setSpeedLimiterSavedMaxSpeedFor(address, currentWheelMaxSpeed(state, config))
        config.setSpeedLimiterEnabledFor(address, true)
        session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))
        return true
    }

    @SuppressLint("MissingPermission")
    fun disable(session: BleSessionViewModel, config: AppConfig): Boolean {
        val state = session.sessionState.value
        val address = addressOf(state)
        if (!config.speedLimiterEnabledFor(address)) return true
        if (!isAvailable(session, state)) return false
        val saved = config.speedLimiterSavedMaxSpeedFor(address)
        if (!session.isCommandValueSupported(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(saved))) {
            return false
        }
        config.wheelMaxSpeed = saved
        config.setSpeedLimiterEnabledFor(address, false)
        session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(saved))
        return true
    }

    fun toggle(session: BleSessionViewModel, config: AppConfig): Boolean =
        if (config.speedLimiterEnabledFor(addressOf(session.sessionState.value)))
            disable(session, config) else enable(session, config)

    /** Updates the limiter speed and re-applies it to the wheel when the limiter is active. */
    @SuppressLint("MissingPermission")
    fun setLimit(session: BleSessionViewModel, config: AppConfig, speed: Int): Boolean {
        val state = session.sessionState.value
        val address = addressOf(state)
        val limit = speed
        if (!session.isCommandValueSupported(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))) {
            return false
        }
        config.setSpeedLimiterSpeedFor(address, limit)
        if (config.speedLimiterEnabledFor(address) &&
            isAvailable(session, state)
        ) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))
        }
        return true
    }

    private fun reportedMaxSpeed(state: BleSessionState): Int? =
        if (state.getWheelType() == Constants.WHEEL_TYPE.KINGSONG)
            state.wheelMaxSpeed ?: state.speedLimit?.toInt() else null
}
