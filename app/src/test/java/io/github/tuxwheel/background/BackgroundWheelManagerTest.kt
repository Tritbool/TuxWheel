package io.github.tuxwheel.background

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.ble.RememberedWheel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackgroundWheelManagerTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val config = AppConfig(context)
    private val disconnects = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val session = mockk<BleSessionViewModel> {
        every { manualDisconnects } returns disconnects
    }

    @Test fun `manual disconnect blocks wheel until disappearance and persists the block`() = runTest {
        config.rememberedWheels = listOf(RememberedWheel(address, "Wheel"))
        config.backgroundSessionEnabled = true
        config.backgroundManualDisconnectPolicy = 0
        val manager = BackgroundWheelManager(context, config, session, backgroundScope)
        manager.start()
        runCurrent()
        manager.appeared(address.lowercase())
        disconnects.emit(address)
        runCurrent()
        assertThat(AppConfig(context).backgroundBlockedWheels).containsExactly(address)
        manager.disappeared(address.lowercase())
        assertThat(config.backgroundBlockedWheels).isEmpty()
        assertThat(manager.present.value).isEmpty()
    }

    @Test fun `immediate reconnect policy neither blocks nor suspends`() = runTest {
        config.backgroundSessionEnabled = true
        config.backgroundManualDisconnectPolicy = 1
        val manager = BackgroundWheelManager(context, config, session, backgroundScope)
        manager.start()
        runCurrent()
        disconnects.emit(address)
        runCurrent()
        assertThat(config.backgroundBlockedWheels).isEmpty()
        assertThat(config.backgroundSessionPaused).isFalse()
    }

    @Test fun `manual reactivation policy survives restart and enable clears suspension`() = runTest {
        config.backgroundSessionEnabled = true
        config.backgroundManualDisconnectPolicy = 2
        val manager = BackgroundWheelManager(context, config, session, backgroundScope)
        manager.start()
        runCurrent()
        disconnects.emit(address)
        runCurrent()
        assertThat(AppConfig(context).backgroundSessionPaused).isTrue()
        config.backgroundSessionEnabled = true
        assertThat(config.backgroundSessionPaused).isFalse()
        assertThat(config.backgroundBlockedWheels).isEmpty()
    }
}
