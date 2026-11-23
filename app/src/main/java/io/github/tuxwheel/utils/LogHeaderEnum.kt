package io.github.tuxwheel.utils

enum class LogHeaderEnum {
    DATE,
    TIME,
    SPEED,
    VOLTAGE,
    PHASE_CURRENT,
    CURRENT,
    POWER,
    TORQUE,
    PWM,
    BATTERY_LEVEL,
    DISTANCE,
    TOTALDISTANCE,
    SYSTEM_TEMP,
    TEMP2,
    TILT,
    ROLL,
    MODE,
    ALERT;

    companion object {
        /**
         * Column-name aliases so the enriched v2 log schema (docs/log_format.md), which uses
         * explicit unit-suffixed names, can be read through the same header mapping as the
         * legacy WheelLog schema.
         */
        private val aliases = mapOf(
            "speed_kmh" to SPEED,
            "voltage_v" to VOLTAGE,
            "phase_current_a" to PHASE_CURRENT,
            "current_a" to CURRENT,
            "power_w" to POWER,
            "torque_nm" to TORQUE,
            "pwm_pct" to PWM,
            "battery_pct" to BATTERY_LEVEL,
            "distance_km" to DISTANCE,
            "total_distance_km" to TOTALDISTANCE,
            "mainboard_temp_c" to SYSTEM_TEMP,
            "motor_temperature_c" to TEMP2,
            "pitch_deg" to TILT,
            "roll_deg" to ROLL,
        )

        fun fromHeaderColumn(name: String): LogHeaderEnum? {
            val normalized = name.trim().lowercase()
            aliases[normalized]?.let { return it }
            return try {
                valueOf(normalized.uppercase())
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
