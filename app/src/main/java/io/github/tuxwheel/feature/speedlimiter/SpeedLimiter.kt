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
 * Activating it stores the user's own wheel max speed in [AppConfig.speedLimiterSavedMaxSpeed]
 * and pushes [AppConfig.speedLimiterSpeed] to the wheel; deactivating it pushes the saved
 * value back to the wheel and restores [AppConfig.wheelMaxSpeed].
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

    /** The wheel max speed currently configured by the user (before any limiter is applied). */
    fun currentWheelMaxSpeed(state: BleSessionState, config: AppConfig): Int =
        reportedMaxSpeed(state) ?: config.wheelMaxSpeed

    @SuppressLint("MissingPermission")
    fun enable(session: BleSessionViewModel, config: AppConfig): Boolean {
        if (config.speedLimiterEnabled) return true
        if (!isAvailable(session)) return false
        val limit = config.speedLimiterSpeed
        if (!session.isCommandValueSupported(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))) {
            return false
        }
        config.speedLimiterSavedMaxSpeed = currentWheelMaxSpeed(session.sessionState.value, config)
        config.speedLimiterEnabled = true
        session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))
        return true
    }

    @SuppressLint("MissingPermission")
    fun disable(session: BleSessionViewModel, config: AppConfig): Boolean {
        if (!config.speedLimiterEnabled) return true
        if (!isAvailable(session)) return false
        val saved = config.speedLimiterSavedMaxSpeed
        if (!session.isCommandValueSupported(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(saved))) {
            return false
        }
        config.wheelMaxSpeed = saved
        config.speedLimiterEnabled = false
        session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(saved))
        return true
    }

    fun toggle(session: BleSessionViewModel, config: AppConfig): Boolean =
        if (config.speedLimiterEnabled) disable(session, config) else enable(session, config)

    /** Updates the limiter speed and re-applies it to the wheel when the limiter is active. */
    @SuppressLint("MissingPermission")
    fun setLimit(session: BleSessionViewModel, config: AppConfig, speed: Int): Boolean {
        val limit = speed
        if (!session.isCommandValueSupported(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))) {
            return false
        }
        config.speedLimiterSpeed = limit
        if (config.speedLimiterEnabled && isAvailable(session)) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(limit))
        }
        return true
    }

    private fun reportedMaxSpeed(state: BleSessionState): Int? =
        if (state.getWheelType() == Constants.WHEEL_TYPE.KINGSONG)
            state.wheelMaxSpeed ?: state.speedLimit?.toInt() else null
}
