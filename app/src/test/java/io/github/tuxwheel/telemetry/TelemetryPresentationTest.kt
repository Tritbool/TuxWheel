package io.github.tuxwheel.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.tuxwheel.utils.Constants.WHEEL_TYPE
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TelemetryPresentationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `field list covers every metric for any wheel`() {
        val expected = """
            speed dynamic_speed_limit top_speed average_speed average_riding_speed distance
            wheel_distance user_distance total_distance ride_time riding_time battery voltage
            voltage_sag current phase_current dynamic_current_limit power motor_power torque
            output pwm temperature motor_temperature cpu_temp imu_temp mosfet_temperature
            board_temperature tire_pressure angle roll fan_status charging_status mode
            sleep_timer cpuload error name model version serial_number
        """.trimIndent().split(Regex("\\s+"))
        assertThat(TelemetryPresentation.fields().map(context.resources::getResourceEntryName))
            .containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `formatting retains precision units status text and hides unavailable rows`() {
        val config = AppConfig(context)
        config.useMph = false
        config.useFahrenheit = false
        val wheel = EUCData(
            speed = 0.0, voltage = 0.0, current = 0.0, temperature = 0.0, batteryLevel = 0,
            distance = 0.0, power = 0.0, timestamp = 0L, rawData = ByteArray(0),
            manufacturer = "", model = "", isCharging = false, rideTime = 0L,
            phaseCurrent = 12.346, torque = 1.256, angle = -1.236, roll = 2.345,
            wheelDistance = 3.218688, totalDistance = 160.9344, motorTemperature = 25.9,
            imuTemperature = -5.0, cpuLoad = 19, fanStatus = 0, chargingStatus = 0,
        )
        val state = BleSessionState(lastData = wheel)
        val model = mockk<BleSessionViewModel>(relaxed = true) {
            every { wheelType } returns WHEEL_TYPE.INMOTION_V2
            every { sessionState } returns MutableStateFlow(state)
            every { speedDouble } returns 16.09344
            every { topSpeedDouble } returns 32.18688
            every { averageSpeedDouble } returns 8.04672
            every { averageRidingSpeedDouble } returns 24.14016
            every { speedLimit } returns 40.2336
            every { distanceDouble } returns 1.609344
            every { userDistanceDouble } returns 4.828032
            every { voltageDouble } returns 84.126
            every { voltageSagDouble } returns 3.456
            every { temperatureDouble } returns 5.9
            every { currentDouble } returns -2.345
            every { currentLimit } returns 18.126
            every { powerDouble } returns -123.456
            every { motorPower } returns 987.654
            every { batteryLevel } returns 87
            every { output } returns 42
            every { version } returns "1.2"
            every { name } returns "Wheel"
            every { model } returns "S22"
            every { serial } returns "123"
            every { rideTimeString } returns "01:02"
            every { ridingTimeString } returns "00:42"
            every { sleepTimerString } returns "00:10"
            every { modeStr } returns "Hard"
        }
        val previousLocale = Locale.getDefault()
        startKoin { modules(module { single { config } }) }
        try {
            Locale.setDefault(Locale.FRANCE)
            val metric = TelemetryPresentation.values(context, config, model)
            val visible = metric.filterValues { it != null }.mapValues { entry -> entry.value!! }
            val expected = mapOf(
                R.string.speed to "16.1 km/h", R.string.top_speed to "32.2 km/h",
                R.string.average_speed to "8.0 km/h", R.string.average_riding_speed to "24.1 km/h",
                R.string.dynamic_speed_limit to "40.2 km/h", R.string.distance to "1.609 km",
                R.string.wheel_distance to "3.219 km", R.string.user_distance to "4.828 km",
                R.string.total_distance to "160.934 km", R.string.voltage to "84.13 V",
                R.string.voltage_sag to "3.46 V", R.string.temperature to "05℃",
                R.string.motor_temperature to "25℃", R.string.cpu_temp to "19℃",
                R.string.imu_temp to "-5℃", R.string.angle to "-1.24°", R.string.roll to "2.35°",
                R.string.current to "-2.35 A", R.string.phase_current to "12.35 A",
                R.string.dynamic_current_limit to "18.13 A", R.string.torque to "1.26 N*m",
                R.string.power to "-123.46 W", R.string.motor_power to "987.65 W",
                R.string.battery to "87%", R.string.output to "42%", R.string.cpuload to "19%",
                R.string.fan_status to context.getString(R.string.off),
                R.string.charging_status to context.getString(R.string.discharging),
                R.string.version to "1.2", R.string.name to "Wheel",
                R.string.model to "S22", R.string.serial_number to "123",
                R.string.ride_time to "01:02", R.string.riding_time to "00:42",
                R.string.sleep_timer to "00:10", R.string.mode to "Hard"
            )
            assertThat(visible).containsExactlyEntriesIn(expected)
            assertThat(metric[R.string.pwm]).isNull()
            assertThat(metric[R.string.mosfet_temperature]).isNull()
            assertThat(metric[R.string.board_temperature]).isNull()
            assertThat(metric[R.string.tire_pressure]).isNull()
            assertThat(metric[R.string.error]).isNull()

            val sensorless = wheel.copy(motorTemperature = null, cpuLoad = null, fanStatus = null)
            val sensorlessMetric = TelemetryPresentation.values(
                context, config,
                mockk { every { sessionState } returns MutableStateFlow(state.copy(lastData = sensorless)) },
            )
            assertThat(sensorlessMetric[R.string.motor_temperature]).isNull()
            assertThat(sensorlessMetric[R.string.cpu_temp]).isNull()
            assertThat(sensorlessMetric[R.string.cpuload]).isNull()
            assertThat(sensorlessMetric[R.string.fan_status]).isNull()

            config.useMph = true
            config.useFahrenheit = true
            val imperialData = wheel.copy(fanStatus = 1, chargingStatus = 1)
            val imperial = TelemetryPresentation.values(
                context, config,
                mockk { every { sessionState } returns MutableStateFlow(state.copy(lastData = imperialData)) },
            )
            assertThat(imperial).containsAtLeastEntriesIn(mapOf(
                R.string.speed to "10.0 mph", R.string.top_speed to "20.0 mph",
                R.string.average_speed to "5.0 mph", R.string.average_riding_speed to "15.0 mph",
                R.string.distance to "1.00 mi", R.string.wheel_distance to "2.00 mi",
                R.string.user_distance to "3.00 mi", R.string.total_distance to "100.00 mi",
                R.string.temperature to "41℉", R.string.motor_temperature to "77℉",
                R.string.cpu_temp to "66℉", R.string.imu_temp to "23℉",
                R.string.fan_status to context.getString(R.string.on),
                R.string.charging_status to context.getString(R.string.charging)
            ))
            every { model.wheelType } returns WHEEL_TYPE.KINGSONG
            val kingsongData = wheel.copy(dynamicSpeedLimit = 20.0)
            every { model.sessionState } returns MutableStateFlow(state.copy(lastData = kingsongData))
            config.useMph = false
            assertThat(TelemetryPresentation.values(context, config, model)[R.string.dynamic_speed_limit])
                .isEqualTo("20.0 km/h")
            config.useMph = true
            assertThat(TelemetryPresentation.values(context, config, model)[R.string.dynamic_speed_limit])
                .isEqualTo("12.4 mph")
            every { model.sessionState } returns MutableStateFlow(state.copy(lastData = kingsongData.copy(dynamicSpeedLimit = null)))
            assertThat(TelemetryPresentation.values(context, config, model)[R.string.dynamic_speed_limit])
                .isNull()
        } finally {
            Locale.setDefault(previousLocale)
            stopKoin()
        }
    }
}
