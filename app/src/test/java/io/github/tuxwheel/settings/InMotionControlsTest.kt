package io.github.tuxwheel.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import io.github.tritbool.euc.ble.models.DiagnosticLogTransfer
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class InMotionControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: BleSessionViewModel
    private val client = mockk<EucBleClient>(relaxed = true)

    @Before fun setup() {
        model = BleSessionViewModel(ApplicationProvider.getApplicationContext())
        model.javaClass.getDeclaredField("_eucBleClient\$delegate").apply { isAccessible = true }
            .set(model, lazyOf(client))
        every { client.getCommandSupport(any()) } returns CommandSupport.UNSUPPORTED
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
    }

    @Test fun `late V9 capabilities reveal lock control which waits for automatic authentication`() {
        val config = mockk<AppConfig>(relaxed = true)
        compose.setContent { MaterialTheme { lockSetting(config, model) } }
        val title = compose.activity.getString(R.string.lock_mode_title)
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle {
            listOf(CommandType.LOCK, CommandType.UNLOCK, CommandType.REQUEST_LOCK_STATE).forEach {
                every { client.getCommandSupport(it) } returns CommandSupport.SUPPORTED
            }
            model.updateWheelSecurity(WheelSecurityState(AuthenticationState.REQUIRED))
        }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.runOnIdle {
            model.updateWheelSecurity(WheelSecurityState(AuthenticationState.AUTHENTICATED))
        }
        compose.onNode(isToggleable()).assertIsEnabled()
    }

    @Test fun `diagnostic Save is absent until CRC verified completion`() {
        @Suppress("UNCHECKED_CAST")
        val state = model.javaClass.getDeclaredField("_sessionState").apply { isAccessible = true }
            .get(model) as MutableStateFlow<BleSessionState>
        val reading = mockk<DiagnosticLogTransfer>(relaxed = true) {
            every { this@mockk.state } returns DiagnosticLogState.READING
        }
        state.value = BleSessionState(diagnosticLogTransfer = reading, diagnosticLogBusy = true)
        compose.setContent { MaterialTheme { diagnosticLogSetting(model) } }
        val save = compose.activity.getString(R.string.wheel_diagnostic_save)
        compose.onNodeWithText(save).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(
                diagnosticLogBusy = false,
                diagnosticLogTransfer = mockk(relaxed = true) {
                    every { this@mockk.state } returns DiagnosticLogState.COMPLETED
                },
            )
        }
        compose.onNodeWithText(save).assertIsDisplayed().assertIsEnabled()
    }
}
