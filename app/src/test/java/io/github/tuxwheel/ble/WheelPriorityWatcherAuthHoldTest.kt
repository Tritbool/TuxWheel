package io.github.tuxwheel.ble

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.AuthenticationState
import org.junit.Test

class WheelPriorityWatcherAuthHoldTest {
    @Test
    fun `a clean connected session does not hold the priority scan`() {
        assertThat(
            WheelPriorityWatcher.isAuthenticationPending(
                BleSessionState(
                    connectionState = BLEConstants.ConnectionState.CONNECTED,
                    wheelAuthentication = AuthenticationState.AUTHENTICATED,
                )
            )
        ).isFalse()
        assertThat(
            WheelPriorityWatcher.isAuthenticationPending(
                BleSessionState(connectionState = BLEConstants.ConnectionState.CONNECTED)
            )
        ).isFalse()
    }

    @Test
    fun `a pending KingSong PIN prompt holds the scan`() {
        assertThat(
            WheelPriorityWatcher.isAuthenticationPending(
                BleSessionState(connectionState = BLEConstants.ConnectionState.CONNECTED, passwordPromptRequired = true)
            )
        ).isTrue()
    }

    @Test
    fun `a wheel demanding authentication holds the scan`() {
        assertThat(
            WheelPriorityWatcher.isAuthenticationPending(
                BleSessionState(
                    connectionState = BLEConstants.ConnectionState.CONNECTED,
                    wheelAuthentication = AuthenticationState.REQUIRED,
                )
            )
        ).isTrue()
    }

    @Test
    fun `an in-flight password operation holds the scan`() {
        assertThat(
            WheelPriorityWatcher.isAuthenticationPending(
                BleSessionState(connectionState = BLEConstants.ConnectionState.CONNECTED, passwordOperationPending = true)
            )
        ).isTrue()
    }
}
