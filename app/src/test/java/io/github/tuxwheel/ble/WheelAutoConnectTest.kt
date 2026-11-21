package io.github.tuxwheel.ble

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState
import io.github.tritbool.euc.ble.core.ConnectionCallback
import io.github.tritbool.euc.ble.core.ErrorCallback
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tuxwheel.AppConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class WheelAutoConnectTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<EucBleClient>(relaxed = true)
    private val callback = slot<ConnectionCallback>()
    private val errors = slot<ErrorCallback>()
    private val wheel = EUCDevice("Unknown", "AA:BB:CC:DD:EE:FF", 0, rssi = -42)
    private val otherWheel = wheel.copy(name = "Other wheel", address = "AA:BB:CC:DD:EE:FE")
    private lateinit var model: BleSessionViewModel
    private lateinit var config: AppConfig

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        config = mockk<AppConfig>()
        every { config.rawLoggingPreferences() } returns flowOf(false)
        every { config.wheelPriorityWaitSeconds } returns 1
        every { config.rememberWheel(any(), any(), any()) } returns Unit
        startKoin { modules(module { single { config } }) }
        every { client.setConnectionCallback(capture(callback)) } answers { }
        every { client.setErrorCallback(capture(errors)) } answers { }
        every { client.trafficFlow } returns MutableSharedFlow()
        every { client.securityStateFlow } returns MutableStateFlow(null)
        every { client.securityEventFlow } returns MutableSharedFlow()
        every { client.stopScan() } answers { callback.captured.onScanCompleted(emptyList()) }
        model = BleSessionViewModel(ApplicationProvider.getApplicationContext(), client)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test fun `external discovery connects without starting a library scan`() = runTest(dispatcher) {
        val device = mockk<BluetoothDevice> {
            every { address } returns wheel.address
        }
        model.connectExternalDevice(device, "ExtremeBull", -42)
        model.connectExternalDevice(device, "ExtremeBull", -42)
        runCurrent()

        verify(exactly = 1) { client.connectExternalDevice(device, "ExtremeBull", -42) }
        verify(exactly = 0) { client.startScan() }
        verify(exactly = 0) { client.connect(any()) }
        verify(exactly = 1) { client.clearForcedProtocol() }
        assertThat(model.sessionState.value.connectionState).isEqualTo(ConnectionState.CONNECTING)
    }

    @Test fun `cold start stops scanning and connects once to the exact saved MAC`() = runTest(dispatcher) {
        model.connectToRememberedWheel("aa:bb:cc:dd:ee:ff", "ExtremeBull")
        model.connectToRememberedWheel(wheel.address, "ExtremeBull")
        runCurrent()
        callback.captured.onScanStarted()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()
        verify(exactly = 0) { client.connect(any()) }

        callback.captured.onDeviceDiscovered(wheel)
        callback.captured.onDeviceDiscovered(wheel)
        runCurrent()

        val connected = slot<EUCDevice>()
        verify(exactly = 1) { client.startScan() }
        verify(exactly = 1) { client.connect(capture(connected)) }
        verifyOrder { client.startScan(); client.stopScan(); client.connect(any()) }
        assertThat(connected.captured.address).isEqualTo(wheel.address)
        assertThat(connected.captured.name).isEqualTo("ExtremeBull")
        assertThat(connected.captured.rssi).isEqualTo(wheel.rssi)
        assertThat(model.sessionState.value.isScanning).isFalse()
    }

    @Test fun `absent saved wheel times out without connecting to any wheel`() = runTest(dispatcher) {
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        callback.captured.onScanStarted()
        callback.captured.onDeviceDiscovered(otherWheel)
        advanceTimeBy(10_000)
        runCurrent()
        assertLookupEnded()
        assertThat(model.sessionState.value.lastError).isNotNull()
    }

    @Test fun `one startup scan waits for the favorite and connects to it immediately on discovery`() = runTest(dispatcher) {
        model.autoConnect(listOf(RememberedWheel(wheel.address, "Favorite"), RememberedWheel(otherWheel.address, "")))
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        advanceTimeBy(900)
        verify(exactly = 0) { client.connect(any()) }

        callback.captured.onDeviceDiscovered(wheel)
        runCurrent()
        verify(exactly = 1) { client.startScan() }
        verify(exactly = 1) { client.connect(match { it.address == wheel.address }) }
        assertThat(model.sessionState.value.isAutoConnecting).isFalse()
    }

    @Test fun `startup picks the highest ranked wheel when the configured grace period ends`() = runTest(dispatcher) {
        every { config.wheelPriorityWaitSeconds } returns 2
        val thirdWheel = wheel.copy(address = "AA:BB:CC:DD:EE:FD")
        model.autoConnect(listOf(wheel, otherWheel, thirdWheel).map { RememberedWheel(it.address, "") })
        runCurrent()
        callback.captured.onDeviceDiscovered(thirdWheel)
        advanceTimeBy(1_500)
        callback.captured.onDeviceDiscovered(otherWheel)
        advanceTimeBy(499)
        assertThat(model.sessionState.value.isAutoConnecting).isTrue()
        verify(exactly = 0) { client.connect(any()) }

        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 1) { client.startScan() }
        verify(exactly = 1) { client.connect(otherWheel) }
        assertThat(model.sessionState.value.isAutoConnecting).isFalse()
    }

    @Test fun `zero grace period connects immediately to the first saved wheel found`() = runTest(dispatcher) {
        every { config.wheelPriorityWaitSeconds } returns 0
        model.autoConnect(listOf(wheel, otherWheel).map { RememberedWheel(it.address, "") })
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()
        verify(exactly = 1) { client.connect(otherWheel) }
    }

    @Test fun `priority wait cannot extend the overall scan past ten seconds`() = runTest(dispatcher) {
        every { config.wheelPriorityWaitSeconds } returns 10
        model.autoConnect(listOf(wheel, otherWheel).map { RememberedWheel(it.address, "") })
        runCurrent()
        advanceTimeBy(9_500)
        callback.captured.onDeviceDiscovered(otherWheel)
        advanceTimeBy(500)
        runCurrent()
        verify(exactly = 1) { client.connect(otherWheel) }
    }

    @Test fun `startup without any saved wheel nearby ends quietly`() = runTest(dispatcher) {
        model.autoConnect(listOf(RememberedWheel(wheel.address, "")))
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        advanceTimeBy(10_000)
        runCurrent()
        assertLookupEnded()
        assertThat(model.sessionState.value.lastError).isNull()
    }

    @Test fun `startup considers results reported when the library finishes scanning`() = runTest(dispatcher) {
        model.autoConnect(listOf(RememberedWheel(wheel.address, ""), RememberedWheel(otherWheel.address, "")))
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        callback.captured.onScanCompleted(listOf(wheel, otherWheel))
        runCurrent()
        verify(exactly = 0) { client.stopScan() }
        verify(exactly = 1) { client.connect(wheel) }
    }

    @Test fun `opening manual scan reuses discoveries and prevents auto connection`() = runTest(dispatcher) {
        model.autoConnect(listOf(wheel, otherWheel).map { RememberedWheel(it.address, "") })
        runCurrent()
        callback.captured.onScanStarted()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()
        assertThat(model.cancelAutoConnect(keepScanning = true)).isTrue()
        model.startScan()
        callback.captured.onDeviceDiscovered(wheel)
        advanceTimeBy(10_000)
        runCurrent()

        verify(exactly = 1) { client.startScan() }
        verify(exactly = 0) { client.stopScan(); client.connect(any()) }
        assertThat(model.sessionState.value.isAutoConnecting).isFalse()
        assertThat(model.sessionState.value.isScanning).isTrue()
        assertThat(model.sessionState.value.scanResults).containsExactly(otherWheel, wheel).inOrder()
    }

    @Test fun `manually selected wheels are remembered only after connection`() = runTest(dispatcher) {
        model.startScan()
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()
        model.connectByAddress(otherWheel.address)
        runCurrent()
        verify(exactly = 0) { config.rememberWheel(any(), any(), any()) }
        every { client.getConnectedDevice() } returns otherWheel
        callback.captured.onConnected()
        runCurrent()
        verify(exactly = 1) { config.rememberWheel(otherWheel.address, otherWheel.name, true) }
    }

    @Test fun `auto connection refreshes only an already saved wheel name`() = runTest(dispatcher) {
        model.autoConnect(listOf(RememberedWheel(otherWheel.address, "wheel1")))
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()
        every { client.getConnectedDevice() } returns otherWheel
        callback.captured.onConnected()
        runCurrent()
        verify(exactly = 1) { config.rememberWheel(otherWheel.address, otherWheel.name, false) }
    }

    @Test fun `cancelled lookup ignores a late discovery and releases the scan`() = runTest(dispatcher) {
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        callback.captured.onScanStarted()
        model.disconnect()
        callback.captured.onDeviceDiscovered(wheel)
        advanceTimeBy(10_000)
        runCurrent()
        assertLookupEnded()
        assertThat(model.sessionState.value.lastError).isNull()
    }

    @Test fun `manual scan replaces startup lookup without stale completion stopping it`() = runTest(dispatcher) {
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        callback.captured.onScanStarted()
        runCurrent()

        model.startScan()
        runCurrent()
        callback.captured.onScanStarted()
        runCurrent()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()

        verify(exactly = 2) { client.startScan() }
        verify(exactly = 1) { client.stopScan() }
        verify(exactly = 0) { client.connect(any()) }
        assertThat(model.sessionState.value.isScanning).isTrue()
        assertThat(model.sessionState.value.scanResults.map { it.address }).contains(otherWheel.address)
    }

    @Test fun `scan error cleans up even before scan started callback`() = runTest(dispatcher) {
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        errors.captured.onError(BLEException("Bluetooth is disabled"))
        callback.captured.onDeviceDiscovered(wheel)
        runCurrent()
        assertLookupEnded()
        assertThat(model.sessionState.value.lastError).isEqualTo("Bluetooth is disabled")
    }

    @Test fun `selecting a manually discovered wheel reuses its scan result`() = runTest(dispatcher) {
        model.startScan()
        runCurrent()
        callback.captured.onScanStarted()
        callback.captured.onDeviceDiscovered(otherWheel)
        runCurrent()
        model.stopScan()
        model.connectByAddress(otherWheel.address)
        runCurrent()

        verify(exactly = 1) { client.startScan() }
        val connected = slot<EUCDevice>()
        verify(exactly = 1) { client.connect(capture(connected)) }
        assertThat(connected.captured).isSameInstanceAs(otherWheel)
    }

    @Test fun `link loss leaves retries to the client and disconnect cancels its backoff`() = runTest(dispatcher) {
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        callback.captured.onDeviceDiscovered(wheel)
        every { client.getConnectedDevice() } returns wheel
        callback.captured.onConnected()
        runCurrent()
        assertThat(model.sessionState.value.isConnected).isTrue()

        callback.captured.onDisconnected()
        runCurrent()
        assertThat(model.sessionState.value.isReconnecting).isTrue()
        assertThat(model.sessionState.value.isConnected).isFalse()
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        verify(exactly = 1) { client.startScan() }
        verify(exactly = 1) { client.connect(any()) }

        callback.captured.onConnecting()
        callback.captured.onConnected()
        runCurrent()
        assertThat(model.sessionState.value.isConnected).isTrue()
        assertThat(model.sessionState.value.isReconnecting).isFalse()

        callback.captured.onDisconnected()
        runCurrent()
        model.disconnect()
        runCurrent()
        verify(exactly = 1) { client.disconnect() }
        assertThat(model.sessionState.value.isReconnecting).isFalse()
    }

    @Test fun `manual scan cancels reconnect backoff and immediately starts scanning`() = runTest(dispatcher) {
        model.connectToRememberedWheel(wheel.address, "")
        runCurrent()
        callback.captured.onDeviceDiscovered(wheel)
        every { client.getConnectedDevice() } returns wheel
        callback.captured.onConnected()
        runCurrent()
        callback.captured.onDisconnected()
        runCurrent()

        verify(exactly = 1) { client.startScan() }
        model.startScan()
        runCurrent()
        callback.captured.onScanStarted()
        runCurrent()

        verify(exactly = 1) { client.disconnect() }
        verify(exactly = 2) { client.startScan() }
        verifyOrder { client.disconnect(); client.startScan() }
        assertThat(model.sessionState.value.isReconnecting).isFalse()
        assertThat(model.sessionState.value.isScanning).isTrue()
        verify(exactly = 1) { client.connect(any()) }
    }

    private fun assertLookupEnded() {
        verify(exactly = 1) { client.stopScan() }
        verify(exactly = 0) { client.connect(any()) }
        assertThat(model.sessionState.value.connectionState).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(model.sessionState.value.isScanning).isFalse()
    }
}
