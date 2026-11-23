package io.github.tuxwheel

import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.ble.RawTrafficRecord
import io.github.tuxwheel.ble.BmsLabWriter

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.PowerManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import io.github.tuxwheel.data.TripDao
import io.github.tuxwheel.utils.Constants
import io.github.tuxwheel.utils.FileUtil
import io.github.tuxwheel.utils.NotificationUtil
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tuxwheel.utils.PermissionsUtil.checkExternalFilePermission
import io.github.tritbool.euc.ble.core.BLEConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoggingService : Service() {
    private val appConfig: AppConfig by inject()
    private val viewModel: BleSessionViewModel by inject()
    private val notifications: NotificationUtil by inject()
    private val dao: TripDao by inject()
    private var sdf: SimpleDateFormat? = null
    private var fileUtil: FileUtil? = null
    private var ioState = CoroutineScope(Dispatchers.IO + Job())

    // Guards against re-running the (file creating) initialization when onStartCommand is
    // redelivered, and against writing the same telemetry sample twice.
    private var logStarted = false
    private var lastLoggedTimestamp: Long? = null

    private var rawFileUtil: FileUtil? = null
    private var rawFileAddress: String? = null
    private var rawLoggingJob: Job? = null

    private var bmsFileUtil: FileUtil? = null
    private var lastBmsWriteElapsed: Long? = null
    private val bmsLabScope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    // True when this recording created its own log file (as opposed to appending to a
    // "continue this day" log): only such files are eligible for the short-distance
    // discard. A dedicated scope lets the deletion survive the dying service's scopes.
    private var createdNewLogFile = false
    private val shortLogDeletionScope = CoroutineScope(Dispatchers.IO + Job())

    // Written format for this recording: the enriched schema applies only to files created
    // by their own recording. Continued "append to this day's log" files keep the format they
    // were started with, so a single file never mixes schemas.
    private var modernLogFormat = false

    private val modernDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val modernTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun updateConnectionState(connectionState: BLEConstants.ConnectionState) {
        if (connectionState != BLEConstants.ConnectionState.CONNECTED) {
            // Park logging: nothing is appended until fresh telemetry arrives again.
            lastLoggedTimestamp = null
            closeBmsFile()
        }
    }

    private val mBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = mBinder

    override fun onCreate() {
        super.onCreate()
        instance = this
        sdf = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss.SSS", Locale.US)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of startForegroundService(), otherwise Android
        // kills the service with ForegroundServiceDidNotStartInTimeException.
        startAsForeground()
        if (appConfig.backgroundSessionEnabled && !viewModel.sessionState.value.isConnected) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (appConfig.backgroundSessionEnabled) {
            notifications.setCustomTitle(getString(R.string.background_session_log_title))
        }
        acquireWakeLock()
        if (!logStarted) {
            if (!startLogging()) {
                stopSelf()
                return START_NOT_STICKY
            }
            logStarted = true
            observeTelemetry()
            observeRawTraffic()
            observeBmsLabFrames()
        }
        return START_STICKY
    }

    /**
     * Holds a partial wake lock while a session is being logged. Without it the OS
     * defers coroutine timers when the screen is off (Doze / OEM battery managers),
     * which was observed as 10-76s pauses in BLE polling and therefore in the CSV.
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TuxWheel:LoggingService"
        ).apply { setReferenceCounted(false) }
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startAsForeground() {
        if (notifications.notification == null) {
            notifications.update()
        }
        val notification = notifications.notification ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constants.MAIN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(Constants.MAIN_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Prepares (or reopens) the CSV file. Returns false when logging cannot be started.
     */
    private fun startLogging(): Boolean {
        var file = FileUtil(applicationContext, buffered = true)
        fileUtil = file
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!checkExternalFilePermission(this)) {
                showToast(R.string.logging_error_no_storage_permission)
                return false
            }
            if (!isExternalStorageReadable || !isExternalStorageWritable) {
                showToast(R.string.logging_error_storage_unavailable)
                return false
            }
        }
        var writeToLastLog = false
        val mac = viewModel.mac
        if (appConfig.continueThisDayLog &&
            appConfig.continueThisDayLogMacException != mac
        ) {
            val lastFileUtil = FileUtil.getLastLog(applicationContext)
            if (lastFileUtil?.file?.path?.contains(mac.replace(':', '_')) == true
            ) {
                file = lastFileUtil
                fileUtil = file
                // parse prev log for filling session state - TODO: Implement EUCData parser
                // val parser = ParserLogToWheelData()
                // parser.parseFile(fileUtil)
                file.prepareStream()
                writeToLastLog = true
                // reset trip duration for recalculation in trip list
                ioState.launch {
                    dao.getTripByFileName(file.file!!.name)?.apply {
                        duration = 0
                        dao.update(this)
                    }
                }
            }
        }
        if (!writeToLastLog) {
            val sdFormatter = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
            val filename = sdFormatter.format(Date()) + ".csv"
            if (!file.prepareFile(filename, mac)) {
                return false
            }
            createdNewLogFile = true
            appConfig.continueThisDayLogMacException = ""
            modernLogFormat = appConfig.logFormatModern
            file.writeLine(if (modernLogFormat) MODERN_LOG_HEADER else LEGACY_LOG_HEADER)
        }
        broadcastState(file.absolutePath, true)
        Timber.i("DataLogger Started")
        return true
    }


    private fun formatNullableDouble(
        value: Double?,
        decimals: Int,
    ): String {
        return value?.let {
            String.format(Locale.US, "%.${decimals}f", it)
        }.orEmpty()
    }

    private fun formatDoubleList(
        values: List<Double>?,
        decimals: Int,
    ): String {
        return "\"[" + values
            ?.joinToString(separator = ",") {
                String.format(Locale.US, "%.${decimals}f", it)
            }
            .orEmpty() + "]\""
    }

    /**
     * Logging is driven from the service itself so that it keeps running while the app is in
     * the background or the screen is off (it used to be pushed by MainActivity, which stopped
     * as soon as the activity was paused).
     */
    private fun observeTelemetry() {
        ioState.launch {
            viewModel.sessionState.collect { state ->
                if (!state.isConnected) {
                    lastLoggedTimestamp = null
                    return@collect
                }
                val timestamp = state.lastDataTimestamp ?: return@collect
                if (timestamp == lastLoggedTimestamp) return@collect
                lastLoggedTimestamp = timestamp
                updateFile()
            }
        }
    }


    private fun observeRawTraffic() {
        rawLoggingJob?.cancel()
        viewModel.rawTraffic.start()

        rawLoggingJob = ioState.launch {
            try {
                appConfig.rawLoggingPreferences().collectLatest { enabled ->
                    if (enabled) {
                        viewModel.rawTraffic.start()
                        try {
                            viewModel.rawTraffic.records.collect { record ->
                                if (appConfig.enableRawData) {
                                    val rawFile = getOrCreateRawFile(record.address) ?: return@collect
                                    rawFile.writeLine(record.toCsvLine())
                                }
                            }
                        } finally {
                            closeRawFile()
                        }
                    } else {
                        viewModel.rawTraffic.stop()
                    }
                }
            } finally {
                closeRawFile()
            }
        }
    }

    private fun observeBmsLabFrames() {
        val mainHandler = Handler(Looper.getMainLooper())
        val frames = viewModel.bmsLabCapture.start {
            mainHandler.post { showToast(R.string.bms_lab_dropped) }
        }
        bmsLabScope.launch {
            var file: FileUtil? = null
            var writer: BmsLabWriter? = null
            var address: String? = null
            var lastFlush = SystemClock.elapsedRealtime()
            try {
                for (frame in frames) {
                    if (frame.address != address) {
                        writer?.close()
                        file?.close()
                        val nextFile = FileUtil(applicationContext, buffered = true)
                        val formatter = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                        val filename = "${formatter.format(Date())}.bms.jsonl.gz"
                        check(nextFile.prepareFile(filename, frame.address)) {
                            "Unable to create BMS lab file"
                        }
                        file = nextFile
                        writer = BmsLabWriter(checkNotNull(nextFile.outputStream))
                        address = frame.address
                    }
                    writer!!.write(frame)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFlush >= 1_000L) {
                        writer.flush()
                        lastFlush = now
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "BMS lab capture failed")
                viewModel.bmsLabCapture.stop(frames)
                mainHandler.post { showToast(R.string.bms_lab_error) }
            } finally {
                try {
                    writer?.close()
                    file?.close()
                } catch (e: Exception) {
                    Timber.e(e, "Unable to finish BMS lab file")
                    mainHandler.post { showToast(R.string.bms_lab_error) }
                }
                bmsLabScope.cancel()
            }
        }
    }

    private fun closeRawFile() {
        rawFileUtil?.close()
        rawFileUtil = null
        rawFileAddress = null
    }

    @Synchronized
    private fun closeBmsFile() {
        bmsFileUtil?.close()
        bmsFileUtil = null
        lastBmsWriteElapsed = null
    }

    private fun getOrCreateRawFile(address: String): FileUtil? {
        if (rawFileAddress != address) closeRawFile()
        rawFileUtil?.let { return it }

        val rawFile = FileUtil(applicationContext, buffered = true)
        val formatter = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val filename = "${formatter.format(Date())}.raw.csv"

        if (!rawFile.prepareFile(filename, address)) {
            Timber.e("Unable to create RAW BLE log file")
            return null
        }

        rawFile.writeLine(RawTrafficRecord.CSV_HEADER)
        rawFileUtil = rawFile
        rawFileAddress = address
        Timber.i("RAW BLE logging started: %s", rawFile.absolutePath)
        return rawFile
    }

    private fun formatBmsLine(
        timestampMillis: Long,
        pack: BMSData,
    ): String {
        return listOf(
            timestampMillis.toString(),
            pack.bmsIndex.toString(),
            formatNullableDouble(pack.voltage, 3),
            formatNullableDouble(pack.current, 3),
            pack.remainingCapacity?.toString().orEmpty(),
            pack.factoryCapacity?.toString().orEmpty(),
            pack.cycles?.toString().orEmpty(),
            pack.isCharging?.toString().orEmpty(),
            formatDoubleList(pack.temperatures, 2),
            formatDoubleList(pack.cellVoltages, 4),
        ).joinToString(separator = ";")
    }


    private fun getOrCreateBmsFile(): FileUtil? {
        bmsFileUtil?.let { return it }

        val file = FileUtil(applicationContext)
        val formatter = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        val filename = "${formatter.format(Date())}.bms.csv"

        if (!file.prepareFile(filename, viewModel.mac)) {
            Timber.e("Unable to create BMS log file")
            return null
        }

        file.writeLine(
            "timestamp_ms;bms_index;voltage_v;current_a;" +
                    "remaining_capacity_mah;factory_capacity_mah;cycles;is_charging;" +
                    "temperatures_c;cell_voltages_v"
        )

        bmsFileUtil = file
        Timber.i("BMS logging started: %s", file.absolutePath)
        return file
    }


    private fun broadcastState(path: String?, isRunning: Boolean) {
        val serviceIntent = Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED).apply {
            // Keep the broadcast internal to the app.
            setPackage(packageName)
            if (!isNullOrEmpty(path)) {
                putExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION, path)
            }
            putExtra(Constants.INTENT_EXTRA_IS_RUNNING, isRunning)
        }
        sendBroadcast(serviceIntent)
    }

    private fun isNullOrEmpty(s: String?): Boolean {
        return s == null || s.trim { it <= ' ' }.isEmpty()
    }

    /**
     * Discards the freshly written log when the ride stayed under the configured minimum
     * distance (0 keeps everything). Continued "append to an existing log" sessions are
     * never deleted: the file may contain earlier, valid rides. The file is removed first;
     * the trip row follows asynchronously, outliving the dying service scopes.
     */
    private fun maybeDeleteShortLog() {
        val threshold = appConfig.minLoggedDistanceMeters
        if (threshold <= 0 || !createdNewLogFile) return
        val file = fileUtil ?: return
        val distanceMeters = viewModel.distanceDouble * 1_000.0
        if (distanceMeters >= threshold) return
        val fileName = file.fileName
        Timber.i(
            "Discarding short ride log (%.0f m < %d m): %s",
            distanceMeters,
            threshold,
            file.absolutePath,
        )
        if (file.delete()) {
            fileUtil = null
            shortLogDeletionScope.launch {
                dao.getTripByFileName(fileName)?.let { trip -> dao.delete(trip) }
            }
        }
    }

    override fun onDestroy() {
        maybeDeleteShortLog()
        viewModel.rawTraffic.stop()
        viewModel.bmsLabCapture.stop()
        ioState.cancel()
        releaseWakeLock()
        val path = fileUtil?.absolutePath
        rawLoggingJob?.cancel()
        rawLoggingJob = null
        closeBmsFile()
        fileUtil?.close()
        fileUtil = null
        Timber.wtf("DataLogger Stopping...")
        if (!appConfig.backgroundSessionEnabled) notifications.setCustomTitle("Uploading tack...")
        broadcastState(path, false)
        instance = null
        Timber.wtf("DataLogger Stopped")
        super.onDestroy()
    }

    private val isExternalStorageWritable: Boolean
        /* Checks if external storage is available for read and write */
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state
        }
    private val isExternalStorageReadable: Boolean
        /* Checks if external storage is available to at least read */
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state || Environment.MEDIA_MOUNTED_READ_ONLY == state
        }

    @Synchronized
    private fun updateBmsFile() {
        if (!appConfig.enableBmsData) {
            closeBmsFile()
            return
        }

        // Read the replay published before telemetry, rather than an independently
        // scheduled collector's cache, which can still contain the previous wheel.
        val packs = viewModel.bmsSnapshots.replayCache.lastOrNull().orEmpty().filter { pack ->
            pack.voltage != null ||
                    pack.current != null ||
                    !pack.temperatures.isNullOrEmpty() ||
                    !pack.cellVoltages.isNullOrEmpty()
        }
            .sortedBy { it.bmsIndex }

        if (packs.isEmpty()) {
            return
        }

        val elapsed = SystemClock.elapsedRealtime()
        val intervalMillis = appConfig.bmsLogIntervalSeconds * 1_000L
        if (lastBmsWriteElapsed?.let { elapsed - it < intervalMillis } == true) {
            return
        }

        val file = getOrCreateBmsFile() ?: return
        val now = System.currentTimeMillis()

        packs.forEach { pack ->
            file.writeLine(formatBmsLine(now, pack))
        }

        lastBmsWriteElapsed = elapsed
    }

    fun updateFile() {
        val wd = viewModel
        val file = fileUtil ?: return
        val formatter = sdf ?: return
        if (modernLogFormat) {
            file.writeLine(buildModernLogLine())
            if (appConfig.enableBmsData) updateBmsFile()
            return
        }
        val speed = if (appConfig.stealthLogging) "" else String.format(Locale.US, "%.2f", wd.speedDouble)
        file.writeLine(
            String.format(
                Locale.US,
                "%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f,%s,%d",
                formatter.format(System.currentTimeMillis()),
                speed,
                wd.voltageDouble,
                wd.phaseCurrentDouble,
                wd.currentDouble,
                wd.powerDouble,
                wd.torque,
                wd.calculatedPwm,
                wd.batteryLevel,
                wd.distanceDouble,
                wd.totalDistanceDouble,
                wd.temperatureDouble,
                wd.motorTemperature / 100.0,
                wd.angle,
                wd.roll,
                wd.modeStr,
                0
            )
        )
        if (appConfig.enableBmsData) updateBmsFile()
    }

    private fun formatInt(value: Int?): String = value?.toString() ?: ""

    private fun formatBool(value: Boolean?): String =
        value?.let { if (it) "true" else "false" } ?: ""

    /**
     * One row of the enriched, documented schema (docs/log_format.md). Legacy column names
     * keep their legacy semantics so existing consumers stay compatible; the appended
     * typed columns expose the decoded EUCData fields, empty when the wheel did not
     * report them.
     */
    private fun buildModernLogLine(): String {
        val state = viewModel.sessionState.value
        val data = state.lastData
        val now = System.currentTimeMillis()
        val stealth = appConfig.stealthLogging
        val cells = (data?.cellVoltages ?: state.cellVoltages)
            ?.joinToString(separator = ",") { String.format(Locale.US, "%.3f", it) }
            .orEmpty()
        return listOf(
            SCHEMA_VERSION,
            now.toString(),
            modernDate.format(now),
            modernTime.format(now),
            if (stealth) "" else formatNullableDouble(data?.speed ?: state.currentSpeed, 3),
            formatNullableDouble(data?.voltage ?: state.currentVoltage, 2),
            formatNullableDouble(data?.phaseCurrent, 3),
            formatNullableDouble(data?.current ?: state.currentCurrent, 3),
            formatNullableDouble(data?.power ?: state.currentPower, 2),
            formatNullableDouble(data?.torque, 2),
            formatNullableDouble(data?.pwm, 2),
            formatInt(data?.batteryLevel ?: state.batteryLevel),
            formatNullableDouble(data?.distance, 3),
            formatNullableDouble(data?.totalDistance ?: state.totalDistance, 3),
            (data?.rideTime ?: state.rideTime)?.toString() ?: "",
            data?.totalRideTimeSeconds?.toString() ?: "",
            data?.totalPowerOnTimeSeconds?.toString() ?: "",
            formatNullableDouble(data?.temperature ?: state.currentTemperature, 2),
            formatNullableDouble(data?.motorTemperature, 2),
            formatNullableDouble(data?.mosfetTemperature, 2),
            formatNullableDouble(data?.boardTemperature, 2),
            formatNullableDouble(data?.imuTemperature, 2),
            formatInt(data?.cpuLoad ?: state.cpuLoad),
            formatNullableDouble(data?.tirePressureKpa, 2),
            formatNullableDouble(data?.angle, 3),
            formatNullableDouble(data?.roll, 3),
            formatNullableDouble(data?.topSpeed, 2),
            formatNullableDouble(data?.speedLimit, 2),
            formatNullableDouble(data?.dynamicSpeedLimit, 2),
            formatNullableDouble(data?.wheelMaxSpeed?.toDouble(), 1),
            formatInt(data?.alarm1Speed),
            formatInt(data?.alarm2Speed),
            formatInt(data?.alarm3Speed),
            "\"" + (data?.mode.orEmpty()) + "\"",
            formatBool(data?.isCharging ?: state.isCharging),
            formatInt(data?.fanStatus),
            formatInt(data?.ledMode),
            formatInt(data?.lightMode),
            formatInt(data?.pedalsMode ?: state.pedalsMode),
            formatInt(data?.alertFlags),
            formatBool(data?.wheelAlarm),
            formatBool(data?.usesMiles),
            "0",
            "\"" + state.deviceManufacturer + "\"",
            "\"" + state.deviceModel + "\"",
            "\"" + (data?.serialNumber ?: state.serialNumber.orEmpty()) + "\"",
            "\"" + (data?.firmwareVersion ?: state.firmwareVersion.orEmpty()) + "\"",
            data?.frameType ?: "BASE",
            "\"[$cells]\"",
        ).joinToString(separator = ",")
    }

    private fun showToast(messageId: Int) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show()
    }

    inner class LocalBinder : Binder() {
        fun getService(): LoggingService {
            return this@LoggingService
        }
    }

    companion object {
        internal fun isRecording(): Boolean = instance?.logStarted == true
        private var instance: LoggingService? = null

        fun isInstanceCreated(): Boolean {
            return instance != null
        }

        fun activeLogLocation(): String? = instance?.fileUtil?.logLocation

        /** Self-describing marker of the enriched, documented schema (docs/log_format.md). */
        const val SCHEMA_VERSION = "tuxwheel-2"

        private const val LEGACY_LOG_HEADER =
            "date,time,speed,voltage,phase_current,current,power,torque,pwm,battery_level," +
            "distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"

        // Every column is an explicit, unit-suffixed name documented in docs/log_format.md;
        // cells are empty when the wheel does not report the field. The in-app trips parser
        // reads these through the LogHeaderEnum aliases.
        internal const val MODERN_LOG_HEADER =
            "schema,timestamp_ms,date,time,speed_kmh,voltage_v,phase_current_a,current_a," +
                "power_w,torque_nm,pwm_pct,battery_pct,distance_km,total_distance_km," +
                "ride_time_s,total_ride_time_s,total_power_on_time_s,mainboard_temp_c," +
                "motor_temperature_c,mosfet_temperature_c,board_temperature_c," +
                "imu_temperature_c,cpu_load_pct,tire_pressure_kpa,pitch_deg,roll_deg," +
                "top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh," +
                "alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging," +
                "fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm," +
                "uses_miles,alert,manufacturer,model,serial_number,firmware_version," +
                "frame_type,cell_voltages_v"
    }
}
