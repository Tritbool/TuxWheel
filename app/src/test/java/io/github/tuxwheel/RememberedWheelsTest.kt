package io.github.tuxwheel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tuxwheel.ble.RememberedWheel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RememberedWheelsTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val config = AppConfig(application)
    private val first = RememberedWheel("AA:BB:CC:DD:EE:FF", "KS-16S", "16S")
    private val second = RememberedWheel("AA:BB:CC:DD:EE:FE", "Aeros")

    @Test fun `legacy target migrates once and forgetting it persists`() {
        config.lastMac = "aa-bb-cc-dd-ee-ff"
        config.lastName = first.name
        assertThat(config.rememberedWheels).containsExactly(first.copy(alias = ""))

        config.rememberedWheels = emptyList()

        assertThat(AppConfig(application).rememberedWheels).isEmpty()
        assertThat(config.lastMac).isEqualTo("aa-bb-cc-dd-ee-ff")
    }

    @Test fun `priority and alias persist independently of the advertised name`() {
        config.rememberedWheels = listOf(second, first)
        config.rememberWheel(first.address.lowercase(), "Updated BLE name")
        config.rememberWheel(second.address, "Unknown")

        assertThat(AppConfig(application).rememberedWheels)
            .containsExactly(second, first.copy(name = "Updated BLE name")).inOrder()
    }
}
