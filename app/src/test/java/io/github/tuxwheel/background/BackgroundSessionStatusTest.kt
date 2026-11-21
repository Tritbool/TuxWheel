package io.github.tuxwheel.background

import io.github.tuxwheel.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSessionStatusTest {
    private fun status(
        enabled: Boolean = true,
        paused: Boolean = false,
        permissions: Boolean = true,
        bluetooth: Boolean = true,
        hasWheels: Boolean = true,
        missingAssociations: Boolean = false,
        observationReady: Boolean = true,
        serviceStatus: Int? = null,
        companion: Boolean = true,
    ) = backgroundSessionStatus(
        enabled, paused, permissions, bluetooth, hasWheels, missingAssociations, observationReady, serviceStatus, companion,
    )

    @Test fun `disabled and paused modes take precedence`() {
        assertEquals(R.string.background_session_disabled, status(enabled = false, permissions = false))
        assertEquals(R.string.background_session_paused, status(paused = true, permissions = false))
    }

    @Test fun `missing requirements are not reported as monitoring`() {
        assertEquals(R.string.background_session_permissions, status(permissions = false))
        assertEquals(R.string.background_session_bluetooth_off, status(bluetooth = false))
        assertEquals(R.string.background_session_no_wheels, status(hasWheels = false))
        assertEquals(R.string.background_session_associations_missing, status(missingAssociations = true))
    }

    @Test fun `service status is distinct from idle monitoring`() {
        assertEquals(R.string.background_session_recording, status(serviceStatus = R.string.background_session_recording))
        assertEquals(R.string.background_session_recording, status(
            missingAssociations = true, serviceStatus = R.string.background_session_recording,
        ))
        assertEquals(R.string.background_session_monitoring, status())
        assertEquals(R.string.background_session_observation_unconfirmed, status(observationReady = false))
        assertEquals(R.string.background_session_not_running, status(companion = false))
    }
}
