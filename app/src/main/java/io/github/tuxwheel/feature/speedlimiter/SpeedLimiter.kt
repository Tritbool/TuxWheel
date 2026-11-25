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

    /**
     * The wheel max speed currently configured by the user (before any limiter is applied), or
     * null when it is unknown.
     *
     * Order of trust:
     *  1. The value the wheel itself reports ([reportedMaxSpeed]) — the only source that is
     *    guaranteed to be accepted back by the wheel on restore. It loses only when it equals the
     *    limiter speed while a parked value exists: right after a disable→re-enable the wheel may
     *    still report the limiter's own ceiling.
     *  2. The parked value from a previous limiter cycle of this wheel.
     *  3. The last max speed the app wrote to this wheel (per wheel address).
     *
     * The app-wide [AppConfig.wheelMaxSpeed] is never used: it is shared by every wheel, so it
     * carried another wheel's 55 km/h into the InMotion V9 (factory ceiling below that), the V9
     * refused the restore and stayed at the limiter speed.
     */
    fun currentWheelMaxSpeed(state: BleSessionState, config: AppConfig): Int? {
        val address = addressOf(state)
        val parked = config.speedLimiterSavedMaxSpeedFor(address).takeIf { it > 0 }
        val reported = reportedMaxSpeed(state)
        val limit = config.speedLimiterSpeedFor(address)
        return when {
            reported != null && (parked == null || reported != limit) -> reported
            parked != null -> parked
            else -> config.wheelCommandValues(state.deviceAddress)[CommandType.SET_SPEED_LIMIT.name]
                ?.takeIf { it > 0 }
        }
    }

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
        // Refuse rather than invent a maximum: the restore would push it to the wheel.
        val userMax = currentWheelMaxSpeed(state, config) ?: return false
        config.setSpeedLimiterSavedMaxSpeedFor(address, userMax)
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
        // Nothing parked: sending it would write 0 km/h to the wheel.
        if (saved <= 0) return false
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

    /**
     * The configured max speed as reported by the wheel itself. The library normalizes it into
     * [BleSessionState.wheelMaxSpeed] for every brand that reports it (KingSong, Gotway,
     * ExtremeBull, Leaperkim, Nosfet, Ninebot Z, InMotion V9/P6), so no brand is special-cased
     * here.
     */
    private fun reportedMaxSpeed(state: BleSessionState): Int? = state.wheelMaxSpeed?.takeIf { it > 0 }
}
