package io.github.tuxwheel.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.LoggingService
import io.github.tuxwheel.R

/**
 * Helpers for the "true background black box": logging sessions that start (and stay) active
 * without the app being opened, driven by remembered wheels.
 *
 * Capability matrix across supported Android versions (minSdk 23):
 * - Android 6-11 (API 23-30): background scan needs the legacy runtime location permission.
 * - Android 12+ (API 31+): background scan/connect needs BLUETOOTH_SCAN/BLUETOOTH_CONNECT.
 * - Android 14+ (API 34+): the connectedDevice foreground service type is enforced; it may
 *   only start while the permissions above are already granted.
 * - All versions: BOOT_COMPLETED may start a connectedDevice foreground service; battery
 *   optimization exemptions keep the session alive on OEM ROMs.
 */
object BlackBoxSupport {

    /** Runtime permissions the background auto-connection depends on for this Android version. */
    fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    fun areBlePermissionsGranted(context: Context): Boolean {
        return requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Legacy Android 6-9 storage permission required to write CSV files. */
    fun isStoragePermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager =
            context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.applicationContext.packageName)
    }

    /**
     * Returns string resource ids describing every prerequisite that currently blocks the
     * background black box, or an empty list when it is fully operational.
     */
    fun blockingReasons(context: Context, appConfig: AppConfig): List<Int> {
        val reasons = mutableListOf<Int>()
        if (appConfig.rememberedWheels.isEmpty()) {
            reasons.add(R.string.blackbox_missing_wheels)
        }
        if (!areBlePermissionsGranted(context)) {
            reasons.add(R.string.blackbox_missing_ble_permissions)
        }
        if (!isIgnoringBatteryOptimizations(context)) {
            reasons.add(R.string.blackbox_battery_optimizations)
        }
        if (!isStoragePermissionGranted(context)) {
            reasons.add(R.string.blackbox_missing_storage_permissions)
        }
        return reasons
    }

    /** Starts the logging service (or marks the running one) as a background black box session. */
    fun startBlackBox(context: Context) {
        val intent = Intent(context, LoggingService::class.java)
            .putExtra(LoggingService.EXTRA_BLACK_BOX, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.applicationContext.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
