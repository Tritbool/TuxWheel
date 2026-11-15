package io.github.tuxwheel.ble

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryUpdateBufferTest {
    private fun data(timestamp: Long) = EUCData(
        speed = 10.0, voltage = 84.0, current = 2.0, temperature = 25.0,
        batteryLevel = 80, distance = 0.0, power = 168.0, timestamp = timestamp,
        rawData = byteArrayOf(), manufacturer = "Kingsong", model = "S22",
        isCharging = false, rideTime = 0,
    )

    @Test fun `burst retains latest sample and extrema without growing a packet backlog`() = runTest {
        val buffer = TelemetryUpdateBuffer { next, _ -> next }
        val refreshes = mutableListOf<TelemetryUpdate>()
        backgroundScope.launch { buffer.collect { refreshes.add(it) } }
        runCurrent()
        repeat(10_000) { index ->
            val sample = data(index.toLong()).copy(
                speed = if (index == 20) 60.0 else 10.0,
                power = if (index == 20) 3000.0 else 168.0,
                current = if (index == 20) 40.0 else 2.0,
                phaseCurrent = if (index == 20) 80.0 else null,
                pwm = if (index == 20) 95.0 else null,
                temperature = if (index == 20) 70.0 else 25.0,
                batteryLevel = if (index == 20) 50 else 80,
                voltage = if (index == 20) 70.0 else 84.0,
            )
            buffer.offer(sample, sample, index.toLong())
        }
        advanceTimeBy(49)
        assertThat(refreshes).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        assertThat(refreshes).hasSize(1)
        val update = refreshes.single()
        assertThat(update.data.timestamp).isEqualTo(9999)
        assertThat(update.receivedAt).isEqualTo(9999)
        assertThat(update.maxSpeed).isEqualTo(60.0)
        assertThat(update.maxPower).isEqualTo(3000.0)
        assertThat(update.maxCurrent).isEqualTo(40.0)
        assertThat(update.maxPhaseCurrent).isEqualTo(80.0)
        assertThat(update.maxPwm).isEqualTo(95.0)
        assertThat(update.maxTemperature).isEqualTo(70.0)
        assertThat(update.minBattery).isEqualTo(50)
        assertThat(update.minVoltage).isEqualTo(70.0)
        assertThat(update.first.timestamp).isEqualTo(0)
        advanceTimeBy(1000)
        runCurrent()
        assertThat(refreshes).hasSize(1)
    }

    @Test fun `continuous input publishes no more than twenty refreshes per second`() = runTest {
        val buffer = TelemetryUpdateBuffer { next, _ -> next }
        val times = mutableListOf<Long>()
        backgroundScope.launch { buffer.collect { times.add(testScheduler.currentTime) } }
        runCurrent()
        repeat(1000) {
            val sample = data(it.toLong())
            buffer.offer(sample, sample, it.toLong())
            advanceTimeBy(1)
            runCurrent()
        }
        assertThat(times).hasSize(20)
        assertThat(times.zipWithNext().all { (a, b) -> b - a >= 50 }).isTrue()
    }

    @Test fun `clear discards stale wheel updates and peak measurements`() = runTest {
        val buffer = TelemetryUpdateBuffer { next, previous ->
            next.copy(lightMode = next.lightMode ?: previous.lightMode)
        }
        val old = data(1).copy(speed = 60.0, lightMode = 2)
        buffer.offer(old, old, 1)
        val partial = data(2)
        buffer.offer(partial, partial, 2)
        assertThat(buffer.take()!!.data.lightMode).isEqualTo(2)
        buffer.offer(old, old, 1)
        buffer.clear()
        assertThat(buffer.take()).isNull()
        buffer.offer(partial, partial, 2)
        val next = buffer.take()!!
        assertThat(next.data.lightMode).isNull()
        assertThat(next.maxSpeed).isEqualTo(10.0)
    }
}
