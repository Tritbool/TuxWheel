package io.github.tuxwheel.background

import android.Manifest
import android.app.AlarmManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

internal class BackgroundWheelManager(
    private val context: Context,
    private val config: AppConfig,
    private val session: BleSessionViewModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _present = MutableStateFlow<Set<String>>(emptySet())
    val present = _present.asStateFlow()
    private val _serviceStatus = MutableStateFlow<Int?>(null)
    val serviceStatus = _serviceStatus.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()
    private val _observationReady = MutableStateFlow(false)
    val observationReady = _observationReady.asStateFlow()
    private var observed = emptySet<String>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            config.dashboardPreferences().collect { refresh() }
        }
        scope.launch {
            session.manualDisconnects.collect { address ->
                if (config.backgroundSessionEnabled) {
                    when (config.backgroundManualDisconnectPolicy) {
                        0 -> if (address.isNotEmpty()) {
                            config.backgroundBlockedWheels = config.backgroundBlockedWheels + normalize(address)
                        }
                        2 -> config.backgroundSessionPaused = true
                    }
                }
            }
        }
        scope.launch {
            session.sessionState.map { it.isConnected }.distinctUntilChanged().collect { connected ->
                if (connected && config.backgroundSessionEnabled && !config.backgroundSessionPaused) startService()
            }
        }
    }

    fun appeared(address: String) {
        val normalized = normalize(address)
        val newlyPresent = normalized !in _present.value
        _present.value = _present.value + normalized
        if (newlyPresent && config.backgroundSessionEnabled && !config.backgroundSessionPaused &&
            config.rememberedWheels.any { it.address == normalized } &&
            normalized !in config.backgroundBlockedWheels
        ) startService()
    }

    fun disappeared(address: String) {
        val normalized = normalize(address)
        _present.value = _present.value - normalized
        if (normalized in config.backgroundBlockedWheels) {
            config.backgroundBlockedWheels = config.backgroundBlockedWheels - normalized
        }
    }

    fun clearPresence() {
        _present.value = emptySet()
    }

    fun hasPermissions(includeBackgroundLocation: Boolean = true): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (includeBackgroundLocation && Build.VERSION.SDK_INT in 29..30) {
                    add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    fun disassociate(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val normalized = normalize(address)
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            manager.disassociate(normalized)
            refresh()
            true
        } catch (e: IllegalArgumentException) {
            reportError(e)
            false
        } catch (e: SecurityException) {
            reportError(e)
            false
        }
    }

    @Suppress("DEPRECATION")
    fun associations(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(CompanionDeviceManager::class.java)?.associations
                ?.map(::normalize)?.toSet().orEmpty()
        } else emptySet()

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun refresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        val desired = if (config.backgroundSessionEnabled && hasPermissions()) {
            config.rememberedWheels.map { it.address }.toSet().intersect(associations())
        } else emptySet()
        try {
            for (address in associations() - desired) manager.stopObservingDevicePresence(address)
            for (address in desired - observed) manager.startObservingDevicePresence(address)
            observed = desired
            _observationReady.value = desired.isNotEmpty()
            _present.value = _present.value.intersect(desired)
            if (!config.backgroundSessionPaused && _present.value.any {
                    it !in config.backgroundBlockedWheels
                }
            ) startService()
        } catch (e: IllegalArgumentException) {
            _observationReady.value = false
            reportError(e)
        } catch (e: IllegalStateException) {
            _observationReady.value = false
            reportError(e)
        } catch (e: SecurityException) {
            _observationReady.value = false
            reportError(e)
        }
    }

    fun restore() {
        refresh()
        if (config.backgroundSessionEnabled && !config.backgroundSessionPaused) {
            scheduleHeartbeat()
            startService()
        } else {
            cancelHeartbeat()
        }
    }

    /**
     * Keep-alive while the black box is enabled: a repeating inexact alarm re-runs
     * [restore], which re-requests companion presence observing — the wake path the
     * system may have dropped after a reboot or an OEM cleanup — and restarts the
     * session service when the platform allows a background start.
     */
    fun scheduleHeartbeat() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + HEARTBEAT_INTERVAL_MILLIS,
            HEARTBEAT_INTERVAL_MILLIS,
            heartbeatIntent(),
        )
    }

    fun cancelHeartbeat() {
        context.getSystemService(AlarmManager::class.java)?.cancel(heartbeatIntent())
    }

    private fun heartbeatIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        HEARTBEAT_REQUEST_CODE,
        Intent(context, BackgroundSessionReceiver::class.java)
            .setAction(ACTION_SESSION_HEARTBEAT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun startService() {
        if (!hasPermissions()) {
            Timber.w("Cannot start background wheel session: Bluetooth permissions are missing")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundSessionService::class.java))
        } catch (e: IllegalStateException) {
            reportError(e)
        } catch (e: SecurityException) {
            reportError(e)
        }
    }

    fun setServiceStatus(status: Int?) {
        _serviceStatus.value = status
    }

    fun clearError() {
        _lastError.value = null
    }

    fun reportError(error: Exception) {
        Timber.e(error, "Background wheel session failed")
        _lastError.value = error.message ?: error.javaClass.simpleName
        Toast.makeText(context, context.getString(R.string.background_session_error, error.message),
            Toast.LENGTH_LONG).show()
    }

    private fun normalize(address: String) = address.uppercase(Locale.ROOT)

    companion object {
        const val ACTION_SESSION_HEARTBEAT = "io.github.tuxwheel.action.SESSION_HEARTBEAT"
        private const val HEARTBEAT_REQUEST_CODE = 8_066
        const val HEARTBEAT_INTERVAL_MILLIS = 15 * 60_000L
    }
}
