package io.github.tuxwheel.ble

import android.app.Application
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

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val config = mockk<AppConfig>()
        every { config.rawLoggingPreferences() } returns flowOf(false)
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

        model.startScan()
        runCurrent()
        callback.captured.onScanStarted()
        runCurrent()

        verify(exactly = 1) { client.disconnect() }
        verify(exactly = 1) { client.startScan() }
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
