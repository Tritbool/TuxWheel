package io.github.tuxwheel.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import io.github.tuxwheel.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale

/**
 * Opt-in "follow wheel priority" mode: while the session is connected, keep a filtered scan
 * running for the other remembered wheels and switch, without user action, to a strictly
 * higher-priority one (earlier in `rememberedWheels`) as soon as it advertises.
 *
 * Switches only move up in priority, so a session riding the top-priority wheel never
 * bounces back down. This deliberately deviates from the maximum-stability reconnect
 * behavior: mid-ride disconnect/reconnect is the documented trade-off of the mode.
 */
internal class WheelPriorityWatcher internal constructor(
    private val application: Application,
    private val appConfig: AppConfig,
    private val session: BleSessionViewModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private var loop: Job? = null
    private var scanCallback: ScanCallback? = null
    private var scannedAddresses = emptySet<String>()

    /** Latest addresses advertised since the current wheel connected. */
    private var seen = emptySet<String>()
    private var switching = false

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            try {
                while (true) {
                    if (switching) {
                        delay(WORK_PERIOD_MILLIS)
                        continue
                    }
                    if (!appConfig.autoSwitchToPriorityWheel ||
                        !session.sessionState.value.isConnected
                    ) {
                        stopScan()
                        delay(WORK_PERIOD_MILLIS)
                        continue
                    }
                    scanCycle()
                    delay(WORK_PERIOD_MILLIS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                stopScan()
            }
        }
    }

    fun stop() {
        scope.cancel()
        loop = null
        stopScan()
    }

    @SuppressLint("MissingPermission")
    private fun scanCycle() {
        val remembered = appConfig.rememberedWheels.map {
            RememberedWheel.normalizeAddress(it.address) ?: it.address
        }
        if (remembered.size < 2) {
            stopScan()
            return
        }
        val current = session.sessionState.value.selectedDevice?.address
            ?.uppercase(Locale.ROOT)
        if (current == null || !session.sessionState.value.isConnected) {
            stopScan()
            return
        }
        val currentIndex = remembered.indexOf(current)
        // Unknown current device (connected manually): every remembered wheel outranks it.
        val candidates = if (currentIndex < 0) remembered else remembered.subList(0, currentIndex)
        if (candidates.isEmpty()) {
            // Already riding the top-priority wheel.
            stopScan()
            return
        }
        if (scanCallback == null) startScan(candidates)
        val target = candidates.firstOrNull { it in seen }
        if (target != null) scope.launch { switchTo(target) }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(addresses: List<String>) {
        if (scannedAddresses == addresses.toSet() && scanCallback != null) return
        stopScan()
        val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen = seen + result.device.address.uppercase(Locale.ROOT)
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("Priority wheel scan failed: %d", errorCode)
                scanCallback = null
                scannedAddresses = emptySet()
            }
        }
        try {
            scanner.startScan(
                addresses.map { ScanFilter.Builder().setDeviceAddress(it).build() },
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                callback,
            )
            scanCallback = callback
            scannedAddresses = addresses.toSet()
            seen = emptySet()
        } catch (e: Exception) {
            Timber.w(e, "Priority wheel scan could not start")
            scanCallback = null
            scannedAddresses = emptySet()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        scannedAddresses = emptySet()
        seen = emptySet()
        try {
            application.getSystemService(BluetoothManager::class.java)?.adapter
                ?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Timber.w(e, "Priority wheel scan could not stop")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun switchTo(address: String) {
        if (switching) return
        switching = true
        stopScan()
        Timber.i("Priority wheel switch: leaving current wheel for %s", address)
        try {
            session.disconnect(userInitiated = false)
        } catch (e: Exception) {
            Timber.w(e, "Priority wheel switch could not disconnect")
            switching = false
            return
        }
        withTimeoutOrNull(SWITCH_TIMEOUT_MILLIS) {
            session.sessionState.first { state ->
                !state.isConnected && state.connectionState ==
                    io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState.DISCONNECTED
            }
        } ?: run {
            switching = false
            return
        }
        seen = emptySet()
        try {
            session.autoConnect(appConfig.rememberedWheels)
        } catch (e: Exception) {
            Timber.w(e, "Priority wheel switch could not connect to %s", address)
        }
        withTimeoutOrNull(SWITCH_SETTLE_MILLIS) {
            session.sessionState.first { it.isConnected }
        }
        switching = false
    }

    companion object {
        private const val WORK_PERIOD_MILLIS = 2_000L
        private const val SWITCH_TIMEOUT_MILLIS = 15_000L
        private const val SWITCH_SETTLE_MILLIS = 15_000L
    }
}
