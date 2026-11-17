package io.github.tuxwheel.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class WheelLockControlsTest {
    private lateinit var model: BleSessionViewModel
    private val client = mockk<EucBleClient>(relaxed = true)

    @Before fun setup() {
        model = BleSessionViewModel(ApplicationProvider.getApplicationContext())
        model.javaClass.getDeclaredField("_eucBleClient\$delegate").apply { isAccessible = true }
            .set(model, lazyOf(client))
        every { client.getCommandDescriptors() } returns listOf(
            CommandType.LOCK,
            CommandType.UNLOCK,
            CommandType.REQUEST_LOCK_STATE,
        ).map {
            CommandDescriptor(it.name, it.name, "", CommandInput.None, CommandEncoding.AVAILABLE)
        }
        model.refreshCommandDescriptors(client, forceContextRefresh = true)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
    }

    @Test fun `every toggle sends the selected command even before lock status or telemetry arrives`() {
        repeat(3) {
            model.setWheelLocked(true)
            model.setWheelLocked(false)
        }
        verify(exactly = 3) { client.sendCommand(CommandType.LOCK.name, CommandValue.None) }
        verify(exactly = 3) { client.sendCommand(CommandType.UNLOCK.name, CommandValue.None) }
        verify(exactly = 6) { client.sendCommand(CommandType.REQUEST_LOCK_STATE.name, CommandValue.None) }
    }

    @Test fun `speed telemetry age and reported lock state never suppress a selected command`() {
        @Suppress("UNCHECKED_CAST")
        val state = model.javaClass.getDeclaredField("_sessionState").apply { isAccessible = true }
            .get(model) as MutableStateFlow<BleSessionState>
        val states = WheelLockState.entries.flatMap { lockState ->
            listOf(null, 1L, Long.MAX_VALUE).flatMap { timestamp ->
                listOf(-5.0, 0.0, 5.0, Double.NaN).map { speed ->
                    BleSessionState(
                        connectionState = BLEConstants.ConnectionState.CONNECTED,
                        wheelLockState = lockState,
                        lastDataTimestamp = timestamp,
                        lastData = data(speed),
                    )
                }
            }
        }
        states.forEach {
            state.value = it
            model.setWheelLocked(true)
            model.setWheelLocked(false)
        }
        verify(exactly = states.size) { client.sendCommand(CommandType.LOCK.name, CommandValue.None) }
        verify(exactly = states.size) { client.sendCommand(CommandType.UNLOCK.name, CommandValue.None) }
    }

    @Test fun `repeated wheel reports can correct an optimistic toggle without suppressing commands`() {
        model.handleWheelSecurityEvent(WheelSecurityEvent.LockStatus(WheelLockState.UNLOCKED))
        val revision = model.sessionState.value.wheelLockStateRevision
        model.setWheelLocked(true)
        model.handleWheelSecurityEvent(WheelSecurityEvent.LockStatus(WheelLockState.UNLOCKED))
        assertThat(model.sessionState.value.wheelLockState).isEqualTo(WheelLockState.UNLOCKED)
        assertThat(model.sessionState.value.wheelLockStateRevision).isEqualTo(revision + 1)
        model.setWheelLocked(true)
        verify(exactly = 2) { client.sendCommand(CommandType.LOCK.name, CommandValue.None) }
    }

    private fun data(speed: Double) = EUCData(
        speed = speed, voltage = 100.0, current = 0.0, temperature = 25.0, batteryLevel = 80,
        distance = 0.0, power = 0.0, timestamp = 1L, rawData = byteArrayOf(),
        manufacturer = "KingSong", model = "KS", isCharging = false, rideTime = 0,
    )
}
