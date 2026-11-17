package io.github.tuxwheel.ble

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import io.github.tritbool.euc.ble.models.DiagnosticLogTransfer
import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class WheelDiagnosticLogTest {
    @get:Rule val files = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var model: BleSessionViewModel
    private val client = mockk<EucBleClient>(relaxed = true)
    private lateinit var state: MutableStateFlow<BleSessionState>
    private var snapshot: DiagnosticLogTransfer? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model = BleSessionViewModel(ApplicationProvider.getApplicationContext())
        model.javaClass.getDeclaredField("_eucBleClient\$delegate").apply { isAccessible = true }
            .set(model, lazyOf(client))
        @Suppress("UNCHECKED_CAST")
        val flow = model.javaClass.getDeclaredField("_sessionState").apply { isAccessible = true }
            .get(model) as MutableStateFlow<BleSessionState>
        state = flow
        state.value = BleSessionState(connectionState = BLEConstants.ConnectionState.CONNECTED)
        every { client.getCommandDescriptors() } returns listOf(
            CommandDescriptor(
                CommandType.REQUEST_DIAGNOSTIC_LOG.name,
                "Diagnostic log",
                "",
                CommandInput.None,
                CommandEncoding.CONDITIONAL,
            ),
            CommandDescriptor(
                CommandType.CANCEL_DIAGNOSTIC_LOG.name,
                "Cancel diagnostic log",
                "",
                CommandInput.None,
                CommandEncoding.CONDITIONAL,
            ),
        )
        model.refreshCommandDescriptors(client, forceContextRefresh = true)
        every { client.getDiagnosticLogTransfer() } answers { snapshot }
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun transfer(status: DiagnosticLogState, bytes: ByteArray? = null) =
        mockk<DiagnosticLogTransfer> {
            every { state } returns status
            every { copyData() } returns bytes
        }

    @Test fun `download tracks snapshots rejects overlap and cancels without saving partial data`() = runTest(dispatcher) {
        snapshot = transfer(DiagnosticLogState.READING)
        model.requestDiagnosticLog()
        runCurrent()
        assertThat(model.sessionState.value.diagnosticLogTransfer).isSameInstanceAs(snapshot)
        model.requestDiagnosticLog()
        verify(exactly = 1) {
            client.sendCommand(CommandType.REQUEST_DIAGNOSTIC_LOG.name, CommandValue.None)
        }
        val file = files.newFile()
        model.saveDiagnosticLog(Uri.fromFile(file))
        assertThat(model.sessionState.value.diagnosticLogError).isNotNull()
        assertThat(file.length()).isEqualTo(0)
        snapshot = transfer(DiagnosticLogState.CANCELLED)
        model.cancelDiagnosticLog()
        assertThat(model.sessionState.value.diagnosticLogBusy).isFalse()
        verify(exactly = 1) {
            client.sendCommand(CommandType.CANCEL_DIAGNOSTIC_LOG.name, CommandValue.None)
        }
    }

    @Test fun `verified completion saves exact binary and a failed destination remains retryable`() = runTest(dispatcher) {
        snapshot = transfer(DiagnosticLogState.READING)
        model.requestDiagnosticLog()
        runCurrent()
        val bytes = byteArrayOf(0, 0xAA.toByte(), 0xA5.toByte(), 0xFF.toByte(), 10)
        snapshot = transfer(DiagnosticLogState.COMPLETED, bytes)
        advanceTimeBy(250)
        runCurrent()
        assertThat(model.sessionState.value.diagnosticLogBusy).isFalse()
        val directory = files.newFolder()
        model.saveDiagnosticLog(Uri.fromFile(directory))
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { model.sessionState.first { !it.diagnosticLogSaving } }
        }
        assertThat(model.sessionState.value.diagnosticLogSaved).isFalse()
        assertThat(model.sessionState.value.diagnosticLogError).isNotNull()
        val file = files.newFile()
        model.saveDiagnosticLog(Uri.fromFile(file))
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { model.sessionState.first { !it.diagnosticLogSaving } }
        }
        assertThat(file.readBytes()).isEqualTo(bytes)
        assertThat(model.sessionState.value.diagnosticLogSaved).isTrue()
        assertThat(model.sessionState.value.diagnosticLogError).isNull()
    }

    @Test fun `missing start reset and failed verification never become successful downloads`() = runTest(dispatcher) {
        snapshot = null
        model.requestDiagnosticLog()
        advanceTimeBy(5_001)
        runCurrent()
        assertThat(model.sessionState.value.diagnosticLogBusy).isFalse()
        assertThat(model.sessionState.value.diagnosticLogError).isNotNull()
        snapshot = transfer(DiagnosticLogState.FAILED)
        model.requestDiagnosticLog()
        runCurrent()
        assertThat(model.sessionState.value.diagnosticLogTransfer?.state).isEqualTo(DiagnosticLogState.FAILED)
        snapshot = transfer(DiagnosticLogState.READING)
        model.requestDiagnosticLog()
        runCurrent()
        snapshot = null
        advanceTimeBy(250)
        runCurrent()
        assertThat(model.sessionState.value.diagnosticLogBusy).isFalse()
        assertThat(model.sessionState.value.diagnosticLogError).isNotNull()
        assertThat(model.sessionState.value.diagnosticLogSaved).isFalse()
    }
}
