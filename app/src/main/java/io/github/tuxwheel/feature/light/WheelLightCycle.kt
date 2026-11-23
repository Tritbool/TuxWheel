package io.github.tuxwheel.feature.light

import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue

/**
 * Ouroboros headlight handler: instead of guessing the current light state from the wheel's
 * telemetry report — the reported `lightMode` semantics differ per brand and, inverted once,
 * made the old toggle always re-send the command matching the current state — each invocation
 * sends the next entry of the cycle of headlight commands the wheel supports.
 *
 * The position of the last sent entry persists in [AppConfig.lightCycleIndex]; when unknown,
 * the first press turns the headlight on. KingSong and Gotway/Begode both expose LIGHT_ON and
 * LIGHT_OFF, so the default cycle simply alternates the two. A wheel that only supports
 * SET_LIGHT_MODE cycles its on/off values the same way.
 */
object WheelLightCycle {
    const val NO_STEP = -1

    /**
     * The cycle for the connected wheel, ordered so that index 0 is LIGHT_ON when both
     * commands exist. Null when the wheel supports no headlight command at all.
     */
    fun steps(on: Boolean, off: Boolean): List<String>? = when {
        on && off -> listOf(CommandType.LIGHT_ON.name, CommandType.LIGHT_OFF.name)
        on -> listOf(CommandType.LIGHT_ON.name)
        off -> listOf(CommandType.LIGHT_OFF.name)
        else -> null
    }

    /** Index of the entry to send next, starting on LIGHT_ON when nothing has been sent yet. */
    fun nextIndex(lastIndex: Int, steps: List<String>): Int =
        if (lastIndex < 0 || lastIndex >= steps.size) 0 else (lastIndex + 1) % steps.size

    /** Sends the next headlight command of the cycle and remembers its position. */
    fun advance(session: BleSessionViewModel, config: AppConfig): Boolean {
        val on = session.isCommandValueSupported(CommandType.LIGHT_ON.name, CommandValue.None)
        val off = session.isCommandValueSupported(CommandType.LIGHT_OFF.name, CommandValue.None)
        val cycle = steps(on, off) ?: return false
        val index = nextIndex(config.lightCycleIndex, cycle)
        config.lightCycleIndex = index
        config.lightEnabled = cycle[index] == CommandType.LIGHT_ON.name
        session.sendCommand(cycle[index])
        return true
    }
}
