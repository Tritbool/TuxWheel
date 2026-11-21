package io.github.tuxwheel.background

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.LoggingService
import io.github.tuxwheel.MainActivity
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

class BackgroundSessionService : Service() {
    private val config: AppConfig by inject()
    private val session: BleSessionViewModel by inject()
    private val manager: BackgroundWheelManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = BackgroundSessionPolicy()
    private var observers: Job? = null
    private var scanCallback: ScanCallback? = null
    private var scannedAddresses = emptySet<String>()
    private val lastSeen = mutableMapOf<String, Long>()
    private val advertisedNames = mutableMapOf<String, String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var loggingRequested = false
    private var loggingFailed = false
    private var loggingRequestAt = 0L
    private var manualDisconnectPending = false
    private var stopping = false
    private var lastNotificationText: Int? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            showNotification(R.string.background_session_waiting)
        } catch (e: SecurityException) {
            manager.reportError(e)
            finishSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!config.backgroundSessionEnabled || config.backgroundSessionPaused || !manager.hasPermissions()) {
            finishSession()
            stopSelf()
            return START_NOT_STICKY
        }
        if (observers == null) {
            val savedAddress = config.backgroundActiveWheel
            if (savedAddress.isNotEmpty() && !session.sessionState.value.isConnected) {
                val elapsed = config.backgroundInterruptedAt.takeIf { it != 0L }
                    ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: 0L
                policy.begin(savedAddress, SystemClock.elapsedRealtime() - elapsed)
                if (!policy.expired(SystemClock.elapsedRealtime(), config.backgroundSessionTimeoutMinutes * 60_000L)) {
                    connect(savedAddress)
                }
            }
            observers = scope.launch {
                launch {
                    session.manualDisconnects.collect { address ->
                        if (address.isNotEmpty()) lastSeen[address] = SystemClock.elapsedRealtime()
                        manualDisconnectPending = true
                        finishSession()
                        if (config.backgroundSessionPaused) stopSelf()
                    }
                }
                launch {
                    config.dashboardPreferences().collect { evaluate() }
                }
                launch {
                    session.sessionState.collect { evaluate() }
                }
                launch {
                    manager.present.collect { evaluate() }
                }
                while (true) {
                    evaluate()
                    delay(1_000)
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun evaluate() {
        if (stopping) return
        if (!config.backgroundSessionEnabled || config.backgroundSessionPaused || !manager.hasPermissions()) {
            stopping = true
            if (policy.address != null) session.disconnect(userInitiated = false)
            finishSession()
            stopSelf()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLegacyScan()
            lastSeen.filter { (address, time) ->
                address != policy.address && now - time >= LEGACY_ABSENCE_MILLIS
            }.keys.toList().forEach { address ->
                lastSeen.remove(address)
                manager.disappeared(address)
            }
        }

        val state = session.sessionState.value
        if (manualDisconnectPending) {
            if (state.connectionState != BLEConstants.ConnectionState.DISCONNECTED || state.isReconnecting) return
            manualDisconnectPending = false
        }
        val connectedAddress = state.selectedDevice?.address.takeIf { state.isConnected }
        if (connectedAddress != null && connectedAddress != policy.address) {
            val replacingSession = policy.address != null
            finishSession()
            policy.begin(connectedAddress, now)
            if (replacingSession) return
        }
        if (policy.address == null && !state.isConnected &&
            state.connectionState == BLEConstants.ConnectionState.DISCONNECTED &&
            !state.isScanning && !state.isAutoConnecting && !state.isReconnecting
        ) {
            val wheel = policy.choose(
                config.rememberedWheels.map { it.address }, manager.present.value, config.backgroundBlockedWheels, now,
                config.wheelPriorityWaitSeconds * 1_000L,
            )
            if (wheel != null) {
                policy.begin(wheel, now)
                connect(wheel)
            }
        }
        if (policy.address == null) {
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Stay alive for the existing priority window, but not indefinitely while idle.
                if (manager.present.value.none { address ->
                        config.rememberedWheels.any { it.address == address } &&
                            address !in config.backgroundBlockedWheels
                    }
                ) {
                    stopping = true
                    stopSelf()
                }
            }
            return
        }
        acquireWakeLock()
        policy.updateConnection(state.isConnected, now)
        if (config.backgroundActiveWheel != policy.address) {
            config.backgroundActiveWheel = requireNotNull(policy.address)
        }
        if (state.isConnected && config.backgroundInterruptedAt != 0L) {
            config.backgroundInterruptedAt = 0L
        } else if (!state.isConnected && config.backgroundInterruptedAt == 0L) {
            config.backgroundInterruptedAt = System.currentTimeMillis()
        }
        if (policy.expired(now, config.backgroundSessionTimeoutMinutes * 60_000L)) {
            session.disconnect(userInitiated = false)
            finishSession()
            showNotification(R.string.background_session_waiting)
            return
        }
        if (loggingRequested && !LoggingService.isInstanceCreated() && now - loggingRequestAt >= 5_000 && !loggingFailed) {
            loggingFailed = true
            manager.reportError(IllegalStateException(getString(R.string.background_session_logging_failed)))
        }
        showNotification(when {
            loggingFailed -> R.string.background_session_logging_failed
            !state.isConnected -> R.string.background_session_reconnecting
            LoggingService.isRecording() -> R.string.background_session_recording
            else -> R.string.background_session_starting_log
        })
        if (state.isConnected && !loggingRequested && !loggingFailed) {
            if (!LoggingService.isInstanceCreated()) {
                try {
                    startForegroundServiceCompat(Intent(this, LoggingService::class.java))
                    loggingRequested = true
                    loggingRequestAt = now
                } catch (e: IllegalStateException) {
                    loggingFailed = true
                    manager.reportError(e)
                } catch (e: SecurityException) {
                    loggingFailed = true
                    manager.reportError(e)
                }
            } else {
                loggingRequested = true
                loggingRequestAt = now
            }
        }
        if (!state.isConnected && !state.isReconnecting &&
            state.connectionState == BLEConstants.ConnectionState.DISCONNECTED &&
            !state.isScanning && !state.isAutoConnecting && policy.canRetry(now)
        ) {
            policy.attempting(now)
            connect(requireNotNull(policy.address))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return
        try {
            val device = adapter.getRemoteDevice(address)
            val name = advertisedNames[address] ?: device.name?.takeIf { it.isNotBlank() }
                ?: config.rememberedWheels.firstOrNull { it.address == address }?.name.orEmpty()
            session.connectExternalDevice(device, name)
        } catch (e: IllegalArgumentException) {
            manager.reportError(e)
        } catch (e: SecurityException) {
            manager.reportError(e)
        }
    }

    private fun finishSession() {
        if (loggingRequested || policy.address != null) {
            stopService(Intent(this, LoggingService::class.java))
        }
        loggingRequested = false
        loggingFailed = false
        if (config.backgroundActiveWheel.isNotEmpty()) config.backgroundActiveWheel = ""
        if (config.backgroundInterruptedAt != 0L) config.backgroundInterruptedAt = 0L
        policy.end()
        releaseWakeLock()
    }

    @SuppressLint("MissingPermission")
    private fun updateLegacyScan() {
        val addresses = config.rememberedWheels.map { it.address }.toSet()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            stopLegacyScan()
            manager.clearPresence()
            lastSeen.clear()
            return
        }
        if (scanCallback != null && scannedAddresses == addresses) return
        stopLegacyScan()
        if (addresses.isEmpty()) return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scope.launch {
                    val address = try {
                        result.device.address
                    } catch (e: SecurityException) {
                        manager.reportError(e)
                        stopping = true
                        stopSelf()
                        return@launch
                    }
                    lastSeen[address] = SystemClock.elapsedRealtime()
                    result.scanRecord?.deviceName?.let { advertisedNames[address] = it }
                    manager.appeared(address)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    manager.reportError(IllegalStateException("Background BLE scan failed: $errorCode"))
                    stopping = true
                    stopSelf()
                }
            }
        }
        try {
            scanner.startScan(
                addresses.map { ScanFilter.Builder().setDeviceAddress(it).build() },
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                callback,
            )
            scannedAddresses = addresses
            scanCallback = callback
        } catch (e: IllegalStateException) {
            manager.reportError(e)
        } catch (e: SecurityException) {
            manager.reportError(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopLegacyScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        try {
            getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Could not stop background wheel scan")
        } catch (e: SecurityException) {
            Timber.w(e, "Bluetooth scan permission was revoked")
        }
    }

    private fun showNotification(text: Int) {
        if (text == lastNotificationText) return
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, getString(R.string.background_session_title), NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.background_session_title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, notification)
        lastNotificationText = text
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "TuxWheel:BackgroundSession",
        ).apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopping = true
        stopLegacyScan()
        scope.cancel()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) manager.clearPresence()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "background_sessions"
        private const val NOTIFICATION_ID = 20
        private const val LEGACY_ABSENCE_MILLIS = 60_000L
    }
}
