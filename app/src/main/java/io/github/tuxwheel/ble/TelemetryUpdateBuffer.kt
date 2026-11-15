package io.github.tuxwheel.ble

import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

internal data class TelemetryUpdate(
    val data: EUCData,
    val receivedAt: Long,
    val first: EUCData,
    val maxSpeed: Double,
    val maxPower: Double,
    val maxCurrent: Double,
    val maxPhaseCurrent: Double?,
    val maxPwm: Double?,
    val maxTemperature: Double,
    val minBattery: Int,
    val minVoltage: Double?,
)

/**
 * A single pending refresh, not a packet queue. Extrema survive coalescing while
 * full-rate diagnostic capture remains independent of this presentation buffer.
 */
internal class TelemetryUpdateBuffer(
    private val merge: (EUCData, EUCData) -> EUCData,
) {
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private var pending: TelemetryUpdate? = null

    @Synchronized
    fun offer(data: EUCData, corrected: EUCData, receivedAt: Long) {
        val previous = pending?.takeIf {
            it.data.manufacturer == data.manufacturer &&
                (it.data.model == data.model || data.model.isBlank() || it.data.model.isBlank())
        }
        pending = TelemetryUpdate(
            data = previous?.let { merge(data, it.data) } ?: data,
            receivedAt = receivedAt,
            first = previous?.first ?: corrected,
            maxSpeed = max(previous?.maxSpeed ?: 0.0, corrected.speed),
            maxPower = max(previous?.maxPower ?: 0.0, corrected.power),
            maxCurrent = max(previous?.maxCurrent ?: 0.0, corrected.current),
            maxPhaseCurrent = maximum(previous?.maxPhaseCurrent, corrected.phaseCurrent),
            maxPwm = maximum(previous?.maxPwm, corrected.pwm),
            maxTemperature = max(previous?.maxTemperature ?: 0.0, corrected.temperature),
            minBattery = min(previous?.minBattery ?: 101, corrected.batteryLevel),
            minVoltage = minimum(previous?.minVoltage, corrected.voltage.takeIf { it > 0 }),
        )
        check(updates.trySend(Unit).isSuccess)
    }

    @Synchronized
    fun take(): TelemetryUpdate? = pending.also { pending = null }

    @Synchronized
    fun clear() {
        pending = null
    }

    suspend fun collect(onUpdate: (TelemetryUpdate) -> Unit) {
        for (ignored in updates) {
            delay(50L)
            take()?.let(onUpdate)
        }
    }

    private fun maximum(a: Double?, b: Double?): Double? =
        if (a == null) b else if (b == null) a else max(a, b)

    private fun minimum(a: Double?, b: Double?): Double? =
        if (a == null) b else if (b == null) a else min(a, b)
}
