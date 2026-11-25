package io.github.tuxwheel.ble

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.PasswordOperation
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.PasswordChangeRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class WheelPasswordTest {
    private lateinit var model: BleSessionViewModel
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val client = mockk<EucBleClient>(relaxed = true)
    private var descriptors = listOf(
        textCommand(CommandType.AUTHENTICATE.name),
    )

    companion object {
        private const val WHEEL_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }

    @Before fun setup() {
        model = BleSessionViewModel(application)
        model.javaClass.getDeclaredField("_eucBleClient\$delegate").apply { isAccessible = true }
            .set(model, lazyOf(client))
        every { client.getCommandDescriptors() } answers { descriptors }
        model.refreshCommandDescriptors(client, forceContextRefresh = true)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
    }

    private fun setCommands(vararg commands: CommandDescriptor) {
        descriptors = commands.toList()
        model.refreshSecurityCapabilities(client, forceContextRefresh = true)
    }

    private fun textCommand(id: String) = CommandDescriptor(
        id,
        id,
        "",
        CommandInput.Text(4, 4, "[0-9]{4}", sensitive = true),
        CommandEncoding.AVAILABLE,
    )

    private fun passwordCommands() = arrayOf(
        textCommand(CommandType.SET_PASSWORD.name),
        CommandDescriptor(
            CommandType.CHANGE_PASSWORD.name,
            CommandType.CHANGE_PASSWORD.name,
            "",
            CommandInput.Record(
                "PasswordChangeRequest",
                listOf(
                    CommandInput.Field("oldPassword", CommandInput.Text(4, 4, "[0-9]{4}", true)),
                    CommandInput.Field("newPassword", CommandInput.Text(4, 4, "[0-9]{4}", true)),
                ),
            ),
            CommandEncoding.AVAILABLE,
        ),
        textCommand(CommandType.REMOVE_PASSWORD.name),
    )

    @Test fun `prompt once per protocol session including reconnect`() {
        model.updateWheelSecurity(null)
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
        model.updateWheelSecurity(WheelSecurityState())
        assertThat(model.sessionState.value.passwordPromptRequired).isTrue()
        model.submitWheelPassword("")
        model.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
        verify(exactly = 0) { client.sendCommand(CommandType.AUTHENTICATE.name, any<CommandValue>()) }
        model.updateWheelSecurity(null)
        model.updateWheelSecurity(WheelSecurityState())
        assertThat(model.sessionState.value.passwordPromptRequired).isTrue()
    }

    @Test fun `passwordless InMotion authentication never offers a KingSong PIN popup`() {
        setCommands()
        model.updateWheelSecurity(WheelSecurityState())
        model.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
        model.updateWheelSecurity(WheelSecurityState(AuthenticationState.AUTHENTICATED))
        assertThat(model.sessionState.value.wheelAuthentication).isEqualTo(AuthenticationState.AUTHENTICATED)
        verify(exactly = 0) { client.sendCommand(CommandType.AUTHENTICATE.name, any<CommandValue>()) }
    }

    private fun securedModelWithVault(secured: io.github.tuxwheel.utils.WheelPasswordVault): BleSessionViewModel {
        val securedModel = BleSessionViewModel(application, null, secured)
        securedModel.javaClass.getDeclaredField("_eucBleClient\$delegate").apply { isAccessible = true }
            .set(securedModel, lazyOf(client))
        val states = securedModel.javaClass.getDeclaredField("_sessionState")
            .apply { isAccessible = true }
            .get(securedModel) as kotlinx.coroutines.flow.MutableStateFlow<BleSessionState>
        @Suppress("DEPRECATION")
        states.value = states.value.copy(
            selectedDevice = io.github.tritbool.euc.ble.models.EUCDevice(
                "KingSong", WHEEL_ADDRESS, 0, rssi = -50)
        )
        return securedModel
    }

    @Test fun `a saved pin is replayed automatically instead of prompting`() {
        val vault = mockk<io.github.tuxwheel.utils.WheelPasswordVault>(relaxed = true)
        every { vault.load(WHEEL_ADDRESS) } returns "0012"
        val securedModel = securedModelWithVault(vault)

        securedModel.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))

        verify(exactly = 1) {
            client.sendCommand(CommandType.AUTHENTICATE.name, CommandValue.Text("0012"))
        }
        assertThat(securedModel.sessionState.value.passwordPromptRequired).isFalse()

        securedModel.updateWheelSecurity(WheelSecurityState(AuthenticationState.AUTHENTICATED))
        securedModel.viewModelScope.cancel()
    }

    @Test fun `a failed pin replay falls back to the manual prompt`() {
        val vault = mockk<io.github.tuxwheel.utils.WheelPasswordVault>(relaxed = true)
        every { vault.load(WHEEL_ADDRESS) } returns "0012"
        val securedModel = securedModelWithVault(vault)

        securedModel.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))
        securedModel.handleWheelSecurityEvent(
            WheelSecurityEvent.AuthenticationStatus(AuthenticationState.REQUIRED)
        )

        assertThat(securedModel.sessionState.value.passwordPromptRequired).isTrue()
        assertThat(securedModel.sessionState.value.passwordPromptError).isTrue()
        securedModel.viewModelScope.cancel()
    }

    @Test fun `a wheel without a saved pin still offers the manual prompt`() {
        val vault = mockk<io.github.tuxwheel.utils.WheelPasswordVault>(relaxed = true)
        every { vault.load(WHEEL_ADDRESS) } returns null
        val securedModel = securedModelWithVault(vault)

        securedModel.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))

        verify(exactly = 0) { client.sendCommand(any<String>(), any<CommandValue>()) }
        assertThat(securedModel.sessionState.value.passwordPromptRequired).isTrue()
        securedModel.viewModelScope.cancel()
    }

    @Test fun `late V9 identification refreshes observable lock and diagnostic capabilities`() {
        setCommands()
        assertThat(model.sessionState.value.supportedSecurityCommands).isEmpty()
        setCommands(
            CommandDescriptor(
                CommandType.REQUEST_LOCK_STATE.name, "Lock status", "", CommandInput.None,
                CommandEncoding.AVAILABLE,
            ),
            CommandDescriptor(
                CommandType.REQUEST_DIAGNOSTIC_LOG.name, "Diagnostic log", "", CommandInput.None,
                CommandEncoding.CONDITIONAL,
            ),
        )
        model.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))
        assertThat(model.sessionState.value.supportedSecurityCommands)
            .containsAtLeast(CommandType.REQUEST_LOCK_STATE, CommandType.REQUEST_DIAGNOSTIC_LOG)
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
        setCommands()
        model.updateWheelSecurity(null)
        assertThat(model.sessionState.value.supportedSecurityCommands).isEmpty()
    }

    @Test fun `wheel lock state follows wheel notifications and resets when unavailable`() {
        model.updateWheelSecurity(WheelSecurityState(lock = WheelLockState.LOCKED))
        assertThat(model.sessionState.value.wheelLockState).isEqualTo(WheelLockState.LOCKED)

        model.updateWheelSecurity(WheelSecurityState(lock = WheelLockState.UNLOCKED))
        assertThat(model.sessionState.value.wheelLockState).isEqualTo(WheelLockState.UNLOCKED)

        model.updateWheelSecurity(null)
        assertThat(model.sessionState.value.wheelLockState).isEqualTo(WheelLockState.UNKNOWN)
    }

    @Test fun `lock toggle chooses command from wheel state and queries when unknown`() {
        setCommands(
            CommandDescriptor(CommandType.LOCK.name, "Lock", "", CommandInput.None, CommandEncoding.AVAILABLE),
            CommandDescriptor(CommandType.UNLOCK.name, "Unlock", "", CommandInput.None, CommandEncoding.AVAILABLE),
            CommandDescriptor(
                CommandType.REQUEST_LOCK_STATE.name, "Status", "", CommandInput.None,
                CommandEncoding.AVAILABLE,
            ),
        )

        model.toggleWheelLock()
        model.updateWheelSecurity(WheelSecurityState(lock = WheelLockState.UNLOCKED))
        model.toggleWheelLock()
        model.updateWheelSecurity(WheelSecurityState(lock = WheelLockState.LOCKED))
        model.toggleWheelLock()

        verify(exactly = 1) { client.sendCommand(CommandType.LOCK.name, CommandValue.None) }
        verify(exactly = 1) { client.sendCommand(CommandType.UNLOCK.name, CommandValue.None) }
        verify(exactly = 3) { client.sendCommand(CommandType.REQUEST_LOCK_STATE.name, CommandValue.None) }
    }

    @Test fun `validate ASCII digits and preserve leading zeros without sending unlock`() {
        model.updateWheelSecurity(WheelSecurityState())
        listOf("12", "12345", "abcd", "１２３４").forEach { model.submitWheelPassword(it) }
        verify(exactly = 0) { client.sendCommand(CommandType.AUTHENTICATE.name, any<CommandValue>()) }
        assertThat(model.sessionState.value.passwordPromptRequired).isTrue()
        model.submitWheelPassword("0012")
        verify(exactly = 1) {
            client.sendCommand(CommandType.AUTHENTICATE.name, CommandValue.Text("0012"))
        }
        verify(exactly = 0) { client.sendCommand(CommandType.UNLOCK.name, CommandValue.None) }
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
    }

    @Test fun `repeated required reply offers retry and confirmed authentication cancels timeout`() {
        model.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))
        model.submitWheelPassword("0012")
        model.handleWheelSecurityEvent(WheelSecurityEvent.AuthenticationStatus(AuthenticationState.REQUIRED))
        assertThat(model.sessionState.value.passwordPromptError).isTrue()
        model.submitWheelPassword("0012")
        model.updateWheelSecurity(WheelSecurityState(AuthenticationState.AUTHENTICATED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
    }

    @Test fun `timeout offers retry but protocol reset cancels pending request`() {
        model.updateWheelSecurity(WheelSecurityState())
        model.submitWheelPassword("0012")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertThat(model.sessionState.value.passwordPromptRequired).isTrue()
        assertThat(model.sessionState.value.passwordPromptError).isTrue()
        model.submitWheelPassword("0012")
        model.updateWheelSecurity(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertThat(model.sessionState.value.passwordPromptRequired).isFalse()
    }

    @Test fun `password management sends requests and waits for wheel confirmation`() {
        setCommands(*passwordCommands())
        model.updateWheelSecurity(WheelSecurityState())

        assertThat(model.setWheelPassword("0012")).isTrue()
        assertThat(model.sessionState.value.passwordOperationPending).isTrue()
        assertThat(model.sessionState.value.passwordOperationStatus).isNull()
        verify { client.sendCommand(CommandType.SET_PASSWORD.name, CommandValue.Text("0012")) }
        model.handleWheelSecurityEvent(
            WheelSecurityEvent.PasswordOperationResult(PasswordOperation.SET, PasswordOperationStatus.CONFIRMED)
        )
        assertThat(model.sessionState.value.passwordOperationPending).isFalse()
        assertThat(model.sessionState.value.passwordOperationStatus).isEqualTo(PasswordOperationStatus.CONFIRMED)

        model.clearWheelPasswordOperationResult()
        assertThat(model.changeWheelPassword("0012", "0034")).isTrue()
        verify {
            client.sendCommand(
                CommandType.CHANGE_PASSWORD.name,
                match {
                    it is PasswordChangeRequest &&
                        it.oldPassword == "0012" && it.newPassword == "0034"
                },
            )
        }
        model.handleWheelSecurityEvent(
            WheelSecurityEvent.PasswordOperationResult(
                PasswordOperation.CHANGE,
                PasswordOperationStatus.TIMEOUT_UNCONFIRMED,
            )
        )
        assertThat(model.sessionState.value.passwordOperationStatus)
            .isEqualTo(PasswordOperationStatus.TIMEOUT_UNCONFIRMED)

        model.clearWheelPasswordOperationResult()
        assertThat(model.removeWheelPassword("0034")).isTrue()
        verify { client.sendCommand(CommandType.REMOVE_PASSWORD.name, CommandValue.Text("0034")) }
    }

    @Test fun `password management rejects invalid credentials and overlapping requests`() {
        setCommands(textCommand(CommandType.SET_PASSWORD.name))
        model.updateWheelSecurity(WheelSecurityState())

        assertThat(model.setWheelPassword("１２３４")).isFalse()
        assertThat(model.sessionState.value.passwordOperationPending).isFalse()
        assertThat(model.setWheelPassword("0012")).isTrue()
        assertThat(model.setWheelPassword("0034")).isFalse()
        verify(exactly = 1) {
            client.sendCommand(CommandType.SET_PASSWORD.name, any<CommandValue>())
        }
    }
}
