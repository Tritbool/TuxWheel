package io.github.tuxwheel.telemetry

import android.content.Context
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.utils.Constants.WHEEL_TYPE
import io.github.tuxwheel.utils.MathsUtil
import io.github.tuxwheel.utils.StringUtil.toTempString
import java.util.Locale

/** Ordered telemetry summary selection and formatting used by the Compose telemetry page. */
object TelemetryPresentation {
    fun fields(): List<Int> = listOf(
        R.string.speed, R.string.dynamic_speed_limit, R.string.top_speed,
        R.string.average_speed, R.string.average_riding_speed, R.string.distance,
        R.string.wheel_distance, R.string.user_distance, R.string.total_distance,
        R.string.ride_time, R.string.riding_time, R.string.battery, R.string.voltage,
        R.string.voltage_sag, R.string.current, R.string.phase_current,
        R.string.dynamic_current_limit, R.string.power, R.string.motor_power,
        R.string.torque, R.string.output, R.string.pwm, R.string.temperature,
        R.string.motor_temperature, R.string.cpu_temp, R.string.imu_temp,
        R.string.mosfet_temperature, R.string.board_temperature, R.string.tire_pressure,
        R.string.angle, R.string.roll, R.string.fan_status, R.string.charging_status,
        R.string.mode, R.string.sleep_timer, R.string.cpuload,
        R.string.error, R.string.name, R.string.model, R.string.version,
        R.string.serial_number
    )

    fun values(context: Context, config: AppConfig, model: BleSessionViewModel): Map<Int, String?> {
        val state = model.sessionState.value
        val data = state.lastData
        fun speed(value: Double) = String.format(
            Locale.US, "%.1f " + context.getString(if (config.useMph) R.string.mph else R.string.kmh),
            if (config.useMph) MathsUtil.kmToMiles(value) else value
        )
        fun distance(value: Double) = String.format(
            Locale.US, (if (config.useMph) "%.2f " else "%.3f ") +
                context.getString(if (config.useMph) R.string.miles else R.string.km),
            if (config.useMph) MathsUtil.kmToMiles(value) else value
        )
        fun unit(value: Double, resource: Int) =
            String.format(Locale.US, "%.2f " + context.getString(resource), value)
        fun percent(value: Int) = String.format(Locale.US, "%d%%", value)
        fun opt(value: Double?, format: (Double) -> String): String? = value?.let(format)
        fun opt(value: Int?, format: (Int) -> String): String? = value?.let(format)
        fun typeName(value: String?): String? = value?.takeIf { it.isNotBlank() }
        return LinkedHashMap<Int, String?>().apply {
            put(R.string.speed, speed(model.speedDouble))
            put(R.string.dynamic_speed_limit, if (model.wheelType == WHEEL_TYPE.KINGSONG) {
                data?.dynamicSpeedLimit?.let { speed(it) }
            } else speed(model.speedLimit))
            put(R.string.top_speed, speed(model.topSpeedDouble))
            put(R.string.average_speed, speed(model.averageSpeedDouble))
            put(R.string.average_riding_speed, speed(model.averageRidingSpeedDouble))
            put(R.string.distance, distance(model.distanceDouble))
            put(R.string.wheel_distance, opt(state.wheelDistance) { distance(it) })
            put(R.string.user_distance, distance(model.userDistanceDouble))
            put(R.string.total_distance, opt(state.totalDistance) { distance(it) })
            put(R.string.ride_time, model.rideTimeString)
            put(R.string.riding_time, model.ridingTimeString)
            put(R.string.battery, percent(model.batteryLevel))
            put(R.string.voltage, unit(model.voltageDouble, R.string.volt))
            put(R.string.voltage_sag, unit(model.voltageSagDouble, R.string.volt))
            put(R.string.current, unit(model.currentDouble, R.string.amp))
            put(R.string.phase_current, opt(data?.phaseCurrent) { unit(it, R.string.amp) })
            put(R.string.dynamic_current_limit, unit(model.currentLimit, R.string.amp))
            put(R.string.power, unit(model.powerDouble, R.string.watt))
            put(R.string.motor_power, unit(model.motorPower, R.string.watt))
            put(R.string.torque, opt(data?.torque) { unit(it, R.string.newton) })
            put(R.string.output, percent(model.output))
            put(R.string.pwm, opt(state.pwm) { String.format(Locale.US, "%.0f%%", it) })
            put(R.string.temperature, model.temperatureDouble.toInt().toTempString())
            put(
                R.string.motor_temperature,
                opt(state.motorTemperature) { it.toInt().toTempString() },
            )
            put(R.string.cpu_temp, opt(state.cpuLoad) { it.toTempString() })
            put(R.string.imu_temp, opt(state.imuTemperature) { it.toInt().toTempString() })
            put(R.string.mosfet_temperature, opt(data?.mosfetTemperature) { it.toInt().toTempString() })
            put(R.string.board_temperature, opt(data?.boardTemperature) { it.toInt().toTempString() })
            put(R.string.tire_pressure, opt(data?.tirePressureKpa) { unit(it, R.string.kpa) })
            put(R.string.angle, opt(state.angle) { String.format(Locale.US, "%.2f\u00b0", it) })
            put(R.string.roll, opt(data?.roll) { String.format(Locale.US, "%.2f\u00b0", it) })
            put(
                R.string.fan_status,
                opt(state.fanStatus) { context.getString(if (it == 0) R.string.off else R.string.on) },
            )
            put(
                R.string.charging_status,
                opt(state.chargingStatus) {
                    context.getString(if (it == 0) R.string.discharging else R.string.charging)
                },
            )
            put(R.string.mode, typeName(model.modeStr))
            put(R.string.sleep_timer, typeName(model.sleepTimerString))
            put(R.string.cpuload, opt(state.cpuLoad) { percent(it) })
            put(R.string.error, typeName(model.error))
            put(R.string.name, typeName(model.name))
            put(R.string.model, typeName(model.model))
            put(R.string.version, model.version.takeIf { it != "Unknown" })
            put(R.string.serial_number, model.serial.takeIf { it != "Unknown" })
        }
    }
}
