package io.github.tuxwheel.background

import androidx.annotation.StringRes
import io.github.tuxwheel.R

@StringRes
internal fun backgroundSessionStatus(
    enabled: Boolean,
    paused: Boolean,
    hasPermissions: Boolean,
    bluetoothOn: Boolean,
    hasWheels: Boolean,
    missingAssociations: Boolean,
    observationReady: Boolean,
    @StringRes serviceStatus: Int?,
    companion: Boolean,
): Int = when {
    !enabled -> R.string.background_session_disabled
    paused -> R.string.background_session_paused
    !hasPermissions -> R.string.background_session_permissions
    !bluetoothOn -> R.string.background_session_bluetooth_off
    !hasWheels -> R.string.background_session_no_wheels
    serviceStatus != null -> serviceStatus
    missingAssociations -> R.string.background_session_associations_missing
    companion && !observationReady -> R.string.background_session_observation_unconfirmed
    companion -> R.string.background_session_monitoring
    else -> R.string.background_session_not_running
}
