package io.github.tuxwheel.ble

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tuxwheel.commandDescriptor
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BleCommandDiscoveryTest {
    private lateinit var model: BleSessionViewModel
    private val client = mockk<EucBleClient>(relaxed = true)
    private var descriptors = listOf(commandDescriptor("FIRST"))

    @Before
    fun setup() {
        model = BleSessionViewModel(ApplicationProvider.getApplicationContext())
        model.javaClass.getDeclaredField("_eucBleClient\$delegate").apply { isAccessible = true }
            .set(model, lazyOf(client))
        every { client.getCommandDescriptors() } answers { descriptors }
        model.refreshCommandDescriptors(client, forceContextRefresh = true)
    }

    @After
    fun cleanup() {
        model.viewModelScope.cancel()
    }

    @Test
    fun `descriptor refresh observes protocol and model contract changes and resets`() {
        val initialRevision = model.sessionState.value.commandContextRevision
        descriptors = listOf(
            commandDescriptor(
                "MODEL_COMMAND",
                input = CommandInput.Range(5, 65, 5, "km/h", scale = 0.1),
            ),
        )
        model.refreshSecurityCapabilities(client)

        assertThat(model.sessionState.value.commandDescriptors.map { it.id })
            .containsExactly("MODEL_COMMAND")
        assertThat(model.sessionState.value.commandContextRevision).isEqualTo(initialRevision + 1)

        val beforeProtocolRefresh = model.sessionState.value.commandContextRevision
        model.refreshSecurityCapabilities(client, forceContextRefresh = true)
        assertThat(model.sessionState.value.commandContextRevision).isEqualTo(beforeProtocolRefresh + 1)

        model.javaClass.getDeclaredMethod("prepareForConnection").apply {
            isAccessible = true
            invoke(model)
        }
        assertThat(model.sessionState.value.commandDescriptors).isEmpty()
        assertThat(model.sessionState.value.commandContextRevision).isEqualTo(beforeProtocolRefresh + 2)
    }

    @Test
    fun `submission uses checked string ID API and library rejections surface to the UI`() {
        val value = CommandValue.Integer(42)
        model.sendCommand("SET_SPEED_LIMIT", value)
        verify(exactly = 1) { client.sendCommand("SET_SPEED_LIMIT", value) }

        val rejection = "Command 'SET_SPEED_LIMIT' rejected (INVALID_VALUE): Integer is outside the advertised range or step"
        model.surfaceBleError(rejection)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(model.sessionState.value.lastError).isEqualTo(rejection)
    }
}
