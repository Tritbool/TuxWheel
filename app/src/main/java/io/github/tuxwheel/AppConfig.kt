package io.github.tuxwheel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
import androidx.preference.PreferenceManager
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.ble.RememberedWheel
import io.github.tuxwheel.utils.NotificationUtil
import io.github.tuxwheel.utils.VolumeKeyController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

class AppConfig(var context: Context) : KoinComponent {
    companion object {
        const val BMS_LOG_INTERVAL_MIN_SECONDS = 1
        const val BMS_LOG_INTERVAL_MAX_SECONDS = 60
        const val BMS_LOG_INTERVAL_DEFAULT_SECONDS = 5
        internal const val AUTO_CONNECT_SCAN_SECONDS = 10
        internal const val DEFAULT_MIN_LOGGED_DISTANCE_METERS = 10
        internal const val DEFAULT_WHEEL_PRIORITY_WAIT_SECONDS = 1
        private val AUTO_WHEEL_NAME = Regex("wheel(\\d+)", RegexOption.IGNORE_CASE)
    }

    private val notifications: NotificationUtil by inject()
    private val volumeKeyController: VolumeKeyController by inject()
    private val sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)
    private var specificPrefix: String = ""
    private val separator = ";"
    private val viewModel: BleSessionViewModel by inject()

    init {
        // Clear all preferences if they are incompatible
        val version = getValue("versionSettings", -1)
        val currentVer = 1
        if (version < currentVer && sharedPreferences.edit()?.clear()?.commit() == true) {
            setValue("versionSettings", currentVer)
        }
    }

    //region -=[ general settings ]=-    
    //region application
    var useEng: Boolean
        get() = getValue(R.string.use_eng, false)
        set(value) = setValue(R.string.use_eng, value)

    /** A listener is owned by the collector and released at STOP/disposal. */
    fun dashboardPreferences(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    data class TelemetryPreferences(
        val useMph: Boolean,
        val useFahrenheit: Boolean,
        val nightMode: Int,
        val viewBlocks: List<String>,
        val pageGraph: Boolean,
        val pageEvents: Boolean,
        val pageTrips: Boolean,
        val autoUploadEc: Boolean,
        val hideTripSpeeds: Boolean
    )

    fun telemetryPreferences(): Flow<TelemetryPreferences> = callbackFlow {
        fun snapshot() = TelemetryPreferences(
            useMph, useFahrenheit, dayNightThemeMode,
            viewBlocks.toList(), pageGraph, pageEvents, pageTrips, autoUploadEc, hideTripSpeeds
        )
        val resources = setOf(
            R.string.use_mph,
            R.string.use_fahrenheit, R.string.day_night_theme,
            R.string.view_blocks_string, R.string.show_page_graph,
            R.string.show_page_events, R.string.show_page_trips, R.string.auto_upload_ec,
            R.string.hide_trip_speeds
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || getResId(key) in resources) trySend(snapshot())
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    /**
     * Night mode chosen in the application settings, forwarded verbatim to
     * [androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode].
     *
     * Older builds stored `MODE_NIGHT_UNSPECIFIED` (-100) for "follow the phone", but
     * `setDefaultNightMode()` only accepts `MODE_NIGHT_FOLLOW_SYSTEM` (-1) and silently drops
     * anything else, which made that choice a no-op. Any legacy value is mapped back to
     * [MODE_NIGHT_FOLLOW_SYSTEM] so the stored preference keeps working after an upgrade.
     */
    var dayNightThemeMode: Int
        get() = when (val stored = getValue(R.string.day_night_theme, MODE_NIGHT_FOLLOW_SYSTEM.toString()).toIntOrNull()) {
            null, MODE_NIGHT_UNSPECIFIED -> MODE_NIGHT_FOLLOW_SYSTEM
            else -> stored
        }
        set(value) = setValue(R.string.day_night_theme, value.toString())

    var useMph: Boolean
        get() = getValue(R.string.use_mph, false)
        set(value) = setValue(R.string.use_mph, value)

    var useFahrenheit: Boolean
        get() = getValue(R.string.use_fahrenheit, false)
        set(value) = setValue(R.string.use_fahrenheit, value)

    private var viewBlocksString: String?
        get() = getValue(R.string.view_blocks_string, null)
        set(value) = setValue(R.string.view_blocks_string, value)

    var viewBlocks: Array<String>
        get() = this.viewBlocksString?.split(separator)?.toTypedArray()
            ?: arrayOf(
                context.getString(R.string.pwm),
                context.getString(R.string.max_pwm),
                context.getString(R.string.voltage),
                context.getString(R.string.average_riding_speed),
                context.getString(R.string.riding_time),
                context.getString(R.string.top_speed),
                context.getString(R.string.distance),
                context.getString(R.string.total)
            )
        set(value) {
            this.viewBlocksString = value.joinToString(separator)
        }

    var usePipMode: Boolean
        get() = getValue(R.string.use_pip_mode, false)
        set(value) = setValue(R.string.use_pip_mode, value)

    private var notificationButtonsString: String?
        get() = getValue(R.string.notification_buttons, null)
        set(value) {
            setValue(R.string.notification_buttons, value)
            notifications.update()
        }

    var notificationButtons: Array<String>
        get() = this.notificationButtonsString?.split(separator)?.filter { button ->
            listOf(R.string.icon_connection, R.string.icon_logging,
                R.string.icon_beep, R.string.icon_light).any { context.getString(it) == button }
        }?.toTypedArray()
            ?: arrayOf(
                context.getString(R.string.icon_connection),
                context.getString(R.string.icon_logging)
            )
        set(value) {
            this.notificationButtonsString = value.joinToString(separator)
        }

    var maxSpeed: Int
        get() = getValue(R.string.max_speed, 100)
        set(value) = setValue(R.string.max_speed, value)

    var valueOnDial: String
        get() = getValue(R.string.value_on_dial, "2")
        set(value) = setValue(R.string.value_on_dial, value)

    var pageGraph: Boolean
        get() = getValue(R.string.show_page_graph, true)
        set(value) = setValue(R.string.show_page_graph, value)

    var pageEvents: Boolean
        get() = getValue(R.string.show_page_events, false)
        set(value) = setValue(R.string.show_page_events, value)

    var pageTrips: Boolean
        get() = getValue(R.string.show_page_trips, true)
        set(value) = setValue(R.string.show_page_trips, value)

    var noConnectionSound: Int
        get() = getValue(R.string.no_connection_sound, 0)
        set(value) = setValue(R.string.no_connection_sound, value)

    var useStopMusic: Boolean
        get() = getValue(R.string.use_stop_music, false)
        set(value) = setValue(R.string.use_stop_music, value)

    var useBeepOnSingleTap: Boolean
        get() = getValue(R.string.beep_on_single_tap, false)
        set(value) = setValue(R.string.beep_on_single_tap, value)

    var useBeepOnVolumeUp: Boolean
        get() = getValue(R.string.beep_on_volume_up, false)
        set(value) {
            setValue(R.string.beep_on_volume_up, value)
            volumeKeyController.setActive(viewModel.isConnected && value)
        }

    var beepByWheel: Boolean
        get() = getValue(R.string.beep_by_wheel, false)
        set(value) = setValue(R.string.beep_by_wheel, value)

    var useCustomBeep: Boolean
        get() = getValue(R.string.custom_beep, false)
        set(value) = setValue(R.string.custom_beep, value)

    var beepFile: Uri
        get() = Uri.parse(getValue(R.string.beep_file, ""))
        set(value) = setValue(R.string.beep_file, value.toString())

    var customBeepTimeLimit: Float
        get() = getValue("custom_beep_time_limit", 2.0f)
        set(value) = setValue("custom_beep_time_limit", value)

    var detectBatteryOptimization: Boolean
        get() = getValue(R.string.use_detect_battery_optimization, true)
        set(value) = setValue(R.string.use_detect_battery_optimization, value)

    //endregion

    //region logs
    var backgroundSessionEnabled: Boolean
        get() = getValue("background_session_enabled", false)
        set(value) {
            if (value) {
                backgroundSessionPaused = false
                backgroundBlockedWheels = emptySet()
            }
            setValue("background_session_enabled", value)
        }

    var backgroundSessionTimeoutMinutes: Int
        get() = getValue("background_session_timeout_minutes", 15).coerceIn(1, 120)
        set(value) = setValue("background_session_timeout_minutes", value.coerceIn(1, 120))

    /** 0: wait for absence, 1: reconnect immediately, 2: wait for manual reactivation. */
    var backgroundManualDisconnectPolicy: Int
        get() = getValue("background_manual_disconnect_policy", 0).coerceIn(0, 2)
        set(value) = setValue("background_manual_disconnect_policy", value.coerceIn(0, 2))

    var backgroundSessionPaused: Boolean
        get() = getValue("background_session_paused", false)
        set(value) = setValue("background_session_paused", value)

    var backgroundBlockedWheels: Set<String>
        get() = getValue("background_blocked_wheels", "").split(';').filter { it.isNotEmpty() }.toSet()
        set(value) = setValue("background_blocked_wheels", value.sorted().joinToString(";"))

    internal var backgroundActiveWheel: String
        get() = getValue("background_active_wheel", "")
        set(value) = setValue("background_active_wheel", value)

    internal var backgroundInterruptedAt: Long
        get() = getValue("background_interrupted_at", 0L)
        set(value) = setValue("background_interrupted_at", value)

    var autoLog: Boolean
        get() = getValue(R.string.auto_log, false)
        set(value) {
            setValue(R.string.auto_log, value)
        }

    /**
     * Sessions shorter than this distance are not kept: their freshly written log file and
     * trip entry are deleted when logging stops. 0 keeps every log, however short.
     */
    var minLoggedDistanceMeters: Int
        get() = getValue(R.string.min_logged_distance_meters, DEFAULT_MIN_LOGGED_DISTANCE_METERS)
            .coerceAtLeast(0)
        set(value) = setValue(R.string.min_logged_distance_meters, value.coerceAtLeast(0))

    /**
     * Write newly created logs with the enriched, documented CSV schema
     * (docs/log_format.md) instead of the legacy WheelLog-compatible one. Applies only to
     * files created by their own recording; continued "this day" logs keep their schema.
     */
    var logFormatModern: Boolean
        get() = getValue(R.string.log_format_modern, false)
        set(value) = setValue(R.string.log_format_modern, value)

    var autoUploadEc: Boolean
        get() = getValue(R.string.auto_upload_ec, false)
        set(value) = setValue(R.string.auto_upload_ec, value)

    var ecUserId: String?
        get() = getValue(R.string.ec_user_id, null)
        set(value) = setValue(R.string.ec_user_id, value)

    var ecToken: String?
        get() = getValue(R.string.ec_token, null)
        set(value) = setValue(R.string.ec_token, value)

    var ecGarage: String?
        get() = getSpecific(R.string.ec_garage, null)
        set(value) = setSpecific(R.string.ec_garage, value)

    var enableRawData: Boolean
        get() = getValue(R.string.use_raw_data, false)
        set(value) = setValue(R.string.use_raw_data, value)

    fun rawLoggingPreferences(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || getResId(key) == R.string.use_raw_data) trySend(enableRawData)
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(enableRawData)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    var enableBmsData: Boolean
        get() = getValue(R.string.use_bms_data, false)
        set(value) = setValue(R.string.use_bms_data, value)

    var enableBmsLabData: Boolean
        get() = getValue(R.string.use_bms_lab_data, false)
        set(value) = setValue(R.string.use_bms_lab_data, value)

    var bmsLogIntervalSeconds: Int
        get() = getValue(R.string.bms_log_interval, BMS_LOG_INTERVAL_DEFAULT_SECONDS)
            .coerceIn(BMS_LOG_INTERVAL_MIN_SECONDS, BMS_LOG_INTERVAL_MAX_SECONDS)
        set(value) = setValue(R.string.bms_log_interval,
            value.coerceIn(BMS_LOG_INTERVAL_MIN_SECONDS, BMS_LOG_INTERVAL_MAX_SECONDS))

    var startAutoLoggingWhenIsMovingMore: Float
        get() = getValue(R.string.auto_log_when_moving_more, 7f)
        set(value) = setValue(R.string.auto_log_when_moving_more, value)

    var continueThisDayLog: Boolean
        get() = getValue(R.string.continue_this_day_log, false)
        set(value) = setValue(R.string.continue_this_day_log, value)

    var continueThisDayLogMacException: String
        get() = getValue(R.string.continue_this_day_log_exception, "")
        set(value) = setValue(R.string.continue_this_day_log_exception, value)

    var hideTripSpeeds: Boolean
        get() = getValue(R.string.hide_trip_speeds, false)
        set(value) = setValue(R.string.hide_trip_speeds, value)

    var stealthLogging: Boolean
        get() = getValue(R.string.stealth_logging, false)
        set(value) = setValue(R.string.stealth_logging, value)
    //endregion    

    //region toolbar
    var showClock: Boolean
        get() = getValue("show_clock", true)
        set(value) = setValue("show_clock", value)

    //region wheel
    var lightEnabled: Boolean
        get() = getValue("wheel_light_enabled", false)
        set(value) = setValue("wheel_light_enabled", value)

    var lightMode: String
        get() = getValue("wheel_light_mode", "0")
        set(value) = setValue("wheel_light_mode", value)

    var lightBrightness: Int
        get() = getValue("wheel_light_brightness", 100)
        set(value) = setValue("wheel_light_brightness", value)

    var speakerVolume: Int
        get() = getValue("wheel_speaker_volume", 5)
        set(value) = setValue("wheel_speaker_volume", value)

    var pedalsMode: String
        get() = getValue("wheel_pedals_mode", "0")
        set(value) = setValue("wheel_pedals_mode", value)

    var ledMode: String
        get() = getValue("wheel_led_mode", "0")
        set(value) = setValue("wheel_led_mode", value)

    var wheelMaxSpeed: Int
        get() = getValue("wheel_max_speed", 25)
        set(value) = setValue("wheel_max_speed", value)

    var wheelAlarm1Speed: Int
        get() = getValue("wheel_alarm_1_speed", 25)
        set(value) = setValue("wheel_alarm_1_speed", value)

    var wheelLocked: Boolean
        get() = getValue("wheel_locked", false)
        set(value) = setValue("wheel_locked", value)

    /**
     * Bluetooth remote-button mappings, at most five `keyCode:ACTION` entries separated by `;`.
     * Empty means no button is caught. See [io.github.tuxwheel.feature.remotebutton].
     */
    var remoteButtonBindings: String
        get() = getValue("remote_button_bindings", "")
        set(value) = setValue("remote_button_bindings", value)

    /**
     * Position in the headlight command cycle of the last entry the app sent
     * ([io.github.tuxwheel.feature.light.WheelLightCycle]); -1 when nothing was sent yet.
     */
    var lightCycleIndex: Int
        get() = getValue("light_cycle_index", -1)
        set(value) = setValue("light_cycle_index", value)

    /** Whether the app-side speed limiter currently holds the wheel at [speedLimiterSpeed]. */
    var speedLimiterEnabled: Boolean
        get() = getValue("speed_limiter_enabled", false)
        set(value) = setValue("speed_limiter_enabled", value)

    /** Speed (km/h) applied to the wheel while the speed limiter is active. */
    var speedLimiterSpeed: Int
        get() = getValue("speed_limiter_speed", 20)
        set(value) = setValue("speed_limiter_speed", value)

    /** User's own wheel max speed (km/h), restored when the speed limiter is turned off. */
    var speedLimiterSavedMaxSpeed: Int
        get() = getValue("speed_limiter_saved_max_speed", 25)
        set(value) = setValue("speed_limiter_saved_max_speed", value)

    var connectBeep: Boolean
        get() = getValue("connect_beep", true)
        set(value) = setValue("connect_beep", value)

    /**
     * Last value the app wrote for each command of one wheel, keyed by MAC address and command id.
     *
     * Most wheels never report a setting back: KingSong has no read command for its pedal cutoff
     * angle or pitch trim, and Gotway and ExtremeBull answer a write with a beep instead of a
     * report. Without remembering the request, the settings screen would fall back to "not
     * reported" right after the user changed something they could see the wheel accept.
     */
    fun wheelCommandValues(address: String): Map<String, Int> {
        if (address.isBlank()) return emptyMap()
        val prefix = wheelCommandValuePrefix(address)
        return sharedPreferences.all
            .filterKeys { it.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                val commandValue = value as? Int ?: return@mapNotNull null
                key.removePrefix(prefix) to commandValue
            }
            .toMap()
    }

    fun setWheelCommandValue(address: String, commandId: String, value: Int) {
        if (address.isBlank()) return
        setValue(wheelCommandValuePrefix(address) + commandId, value)
    }

    private fun wheelCommandValuePrefix(address: String) =
        "wheel_command_value$separator$address$separator"

    var profileName: String
        get() = getValue("profile_name", "")
        set(value) = setValue("profile_name", value)

    //region connection
    internal var wheelPriorityWaitSeconds: Int
        get() = getValue("wheel_priority_wait_seconds", DEFAULT_WHEEL_PRIORITY_WAIT_SECONDS)
            .coerceIn(0, AUTO_CONNECT_SCAN_SECONDS)
        set(value) = setValue("wheel_priority_wait_seconds", value.coerceIn(0, AUTO_CONNECT_SCAN_SECONDS))

    /**
     * Keep scanning while connected and switch to a higher-priority remembered wheel as soon
     * as it appears, instead of requiring the current wheel to be turned off first.
     * Inherent trade-off (to explain in the UI): any higher-priority wheel found in range
     * interrupts the current connection, even mid-ride, so unexpected disconnect/reconnect
     * behavior is expected rather than a bug.
     */
    var autoSwitchToPriorityWheel: Boolean
        get() = getValue(R.string.auto_switch_wheels, false)
        set(value) = setValue(R.string.auto_switch_wheels, value)

    fun autoSwitchWheelPreferences(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || getResId(key) == R.string.auto_switch_wheels) {
                trySend(autoSwitchToPriorityWheel)
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(autoSwitchToPriorityWheel)
        awaitClose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun migrateRememberedWheels() {
        if (getValue<String?>("remembered_wheels", null) == null) {
            rememberedWheels = RememberedWheel.normalizeAddress(lastMac)?.let {
                listOf(RememberedWheel(it, lastName))
            }.orEmpty()
        }
    }

    var rememberedWheels: List<RememberedWheel>
        get() {
            migrateRememberedWheels()
            val stored = getValue("remembered_wheels", "[]")
            return try {
                val wheels = JSONArray(stored)
                (0 until wheels.length()).mapNotNull { index ->
                    val wheel = wheels.optJSONObject(index) ?: return@mapNotNull null
                    val address = RememberedWheel.normalizeAddress(wheel.optString("address"))
                        ?: return@mapNotNull null
                    RememberedWheel(address, wheel.optString("name"), wheel.optString("alias"), wheel.optString("protocol"))
                }.distinctBy { it.address }
            } catch (e: JSONException) {
                Timber.e(e, "Failed to read remembered wheels")
                emptyList()
            }
        }
        set(value) {
            val wheels = JSONArray()
            value.mapNotNull { wheel ->
                RememberedWheel.normalizeAddress(wheel.address)?.let { wheel.copy(address = it) }
            }.distinctBy { it.address }
                .forEach { wheel ->
                    wheels.put(
                        JSONObject()
                            .put("address", wheel.address)
                            .put("name", wheel.name)
                            .put("alias", wheel.alias)
                            .put("protocol", wheel.protocolId)
                    )
                }
            setValue("remembered_wheels", wheels.toString())
        }

    fun setRememberedWheelProtocol(address: String, protocolId: String) {
        val wheels = rememberedWheels.toMutableList()
        val index = wheels.indexOfFirst { it.address.equals(address, ignoreCase = true) }
        if (index < 0 || wheels[index].protocolId == protocolId) return
        wheels[index] = wheels[index].copy(protocolId = protocolId)
        rememberedWheels = wheels
    }

    fun rememberWheel(address: String, name: String, addIfMissing: Boolean = true) {
        val key = RememberedWheel.normalizeAddress(address) ?: return
        val existingWheels = rememberedWheels
        val wheels = existingWheels.toMutableList()
        val index = wheels.indexOfFirst { it.address == key }
        val advertisedName = name.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
        if (index >= 0) {
            val existing = wheels[index]
            wheels[index] = existing.copy(name = advertisedName ?: existing.name)
        } else if (addIfMissing) {
            wheels.add(RememberedWheel(key, advertisedName.orEmpty()))
        }
        if (wheels != existingWheels) rememberedWheels = wheels
    }

    /**
     * Appends a wheel entered by MAC address. A custom name is stored as the alias so it is
     * never replaced; otherwise a "wheelN" placeholder is stored as the BLE name and is
     * replaced by the advertised name on the first successful connection.
     */
    fun addManualWheel(address: String, customName: String): ManualWheelResult {
        val key = RememberedWheel.normalizeAddress(address) ?: return ManualWheelResult.INVALID_ADDRESS
        val wheels = rememberedWheels
        if (wheels.any { it.address == key }) return ManualWheelResult.ALREADY_SAVED
        val alias = customName.trim()
        val wheel = if (alias.isNotEmpty()) {
            RememberedWheel(key, "", alias)
        } else {
            val next = wheels.flatMap { listOf(it.name, it.alias) }
                .mapNotNull { AUTO_WHEEL_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull()?.plus(1) ?: 1
            RememberedWheel(key, "wheel$next")
        }
        rememberedWheels = wheels + wheel
        return ManualWheelResult.ADDED
    }

    enum class ManualWheelResult { ADDED, INVALID_ADDRESS, ALREADY_SAVED }

    var lastMac: String
        get() = getValue("last_mac", "")
        set(value) = setValue("last_mac", value)

    var lastName: String
        get() = getValue("last_name", "")
        set(value) = setValue("last_name", value)

    var advDataForWheel: String
        get() = getValue("adv_data_for_wheel", "")
        set(value) = setValue("adv_data_for_wheel", value)

    var passwordForWheel: String
        get() = getValue("password_for_wheel", "")
        set(value) = setValue("password_for_wheel", value)
    //endregion

    //region alarms
    var alarmsEnabled: Boolean
        get() = getValue("alarms_enabled", false)
        set(value) = setValue("alarms_enabled", value)

    var pwmBasedAlarms: Boolean
        get() = getValue(R.string.altered_alarms, false)
        set(value) = setValue(R.string.altered_alarms, value)

    var disablePhoneVibrate: Boolean
        get() = getValue(R.string.disable_phone_vibrate, false)
        set(value) = setValue(R.string.disable_phone_vibrate, value)

    var disablePhoneBeep: Boolean
        get() = getValue(R.string.disable_phone_beep, false)
        set(value) = setValue(R.string.disable_phone_beep, value)

    var useWheelBeepForAlarm: Boolean
        get() = getValue(R.string.use_wheel_beep_for_alarm, false)
        set(value) = setValue(R.string.use_wheel_beep_for_alarm, value)

    var alarm1Speed: Int
        get() = getValue(R.string.alarm_1_speed, 0)
        set(value) = setValue(R.string.alarm_1_speed, value)

    var alarm1Battery: Int
        get() = getValue(R.string.alarm_1_battery, 0)
        set(value) = setValue(R.string.alarm_1_battery, value)

    var alarm2Speed: Int
        get() = getValue(R.string.alarm_2_speed, 0)
        set(value) = setValue(R.string.alarm_2_speed, value)

    var alarm2Battery: Int
        get() = getValue(R.string.alarm_2_battery, 0)
        set(value) = setValue(R.string.alarm_2_battery, value)

    var alarm3Speed: Int
        get() = getValue(R.string.alarm_3_speed, 0)
        set(value) = setValue(R.string.alarm_3_speed, value)

    var alarm3Battery: Int
        get() = getValue(R.string.alarm_3_battery, 0)
        set(value) = setValue(R.string.alarm_3_battery, value)

    var alarmCurrent: Int
        get() = getValue(R.string.alarm_current, 0)
        set(value) = setValue(R.string.alarm_current, value)

    var alarmPhaseCurrent: Int
        get() = getValue(R.string.alarm_phase_current, 0)
        set(value) = setValue(R.string.alarm_phase_current, value)

    var alarmBattery: Int
        get() = getValue(R.string.alarm_battery, 0)
        set(value) = setValue(R.string.alarm_battery, value)

    var alarmWheel: Boolean
        get() = getValue(R.string.alarm_wheel, false)
        set(value) = setValue(R.string.alarm_wheel, value)

    var alarmTemperature: Int
        get() = getValue(R.string.alarm_temperature, 0)
        set(value) = setValue(R.string.alarm_temperature, value)

    var alarmMotorTemperature: Int
        get() = getValue(R.string.alarm_motor_temperature, 0)
        set(value) = setValue(R.string.alarm_motor_temperature, value)

    var alarmFactor1: Int
        get() = getValue(R.string.alarm_factor1, 80)
        set(value) = setValue(R.string.alarm_factor1, value)

    var alarmFactor2: Int
        get() = getValue(R.string.alarm_factor2, 95)
        set(value) = setValue(R.string.alarm_factor2, value)

    var warningSpeed: Int
        get() = getValue(R.string.warning_speed, 0)
        set(value) = setValue(R.string.warning_speed, value)

    var warningPwm: Int
        get() = getValue(R.string.warning_pwm, 0)
        set(value) = setValue(R.string.warning_pwm, value)

    var warningSpeedPeriod: Int
        get() = getValue(R.string.warning_speed_period, 0)
        set(value) = setValue(R.string.warning_speed_period, value)

    var hwPwm: Boolean
        get() = getValue(R.string.hw_pwm, false)
        set(value) = setValue(R.string.hw_pwm, value)

    var rotationIsSet: Boolean
        get() = getValue(R.string.rotation_set, false)
        set(value) = setValue(R.string.rotation_set, value)

    var powerFactor: Int
        get() = getValue(R.string.power_factor, 90)
        set(value) = setValue(R.string.power_factor, value)

    var rotationSpeed: Int
        get() = getValue(R.string.rotation_speed, 500)
        set(value) = setValue(R.string.rotation_speed, value)

    var rotationVoltage: Int
        get() = getValue(R.string.rotation_voltage, 840)
        set(value) = setValue(R.string.rotation_voltage, value)

    var useShortPwm: Boolean
        get() = getValue(R.string.use_short_pwm, false)
        set(value) = setValue(R.string.use_short_pwm, value)

    var swapSpeedPwm: Boolean
        get() = getValue(R.string.swap_speed_pwm, false)
        set(value) = setValue(R.string.swap_speed_pwm, value)

    var colorPwmStart: Int
        get() = getValue(R.string.color_pwm_start, 60)
        set(value) = setValue(R.string.color_pwm_start, value)

    var colorPwmEnd: Int
        get() = getValue(R.string.color_pwm_end, 90)
        set(value) = setValue(R.string.color_pwm_end, value)
    //endregion

    fun getResId(resName: String?): Int {
        return if (resName == null || resName === "") {
            -1
        } else try {
            context.resources.getIdentifier(resName, "string", context.packageName)
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    private fun setSpecific(resId: Int, value: Any?) {
        setValue(specificPrefix + "_" + context.getString(resId), value)
    }

    fun setValue(resId: Int, value: Any?) {
        setValue(context.getString(resId), value)
    }

    fun setValue(key: String, value: Any?) {
        when (value) {
            is String? -> sharedPreferences.edit().putString(key, value).apply()
            is String -> sharedPreferences.edit().putString(key, value).apply()
            is Int -> sharedPreferences.edit().putInt(key, value).apply()
            is Float -> sharedPreferences.edit().putFloat(key, value).apply()
            is Double -> sharedPreferences.edit().putFloat(key, value.toFloat()).apply()
            is Boolean -> sharedPreferences.edit().putBoolean(key, value).apply()
            is Long -> sharedPreferences.edit().putLong(key, value).apply()
        }
    }

    private fun <T : Any?> getSpecific(resId: Int, defaultValue: T): T {
        return getValue(specificPrefix + "_" + context.getString(resId), defaultValue)
    }

    fun <T : Any?> getValue(resId: Int, defaultValue: T): T {
        return getValue(context.getString(resId), defaultValue)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any?> getValue(key: String, defaultValue: T): T {
        return try {
            when (defaultValue) {
                is String? -> sharedPreferences.getString(key, defaultValue) as T
                is String -> sharedPreferences.getString(key, defaultValue) as T
                is Int -> sharedPreferences.getInt(key, defaultValue) as T
                is Float -> sharedPreferences.getFloat(key, defaultValue) as T
                is Double -> sharedPreferences.getFloat(key, defaultValue.toFloat()).toDouble() as T
                is Boolean -> sharedPreferences.getBoolean(key, defaultValue) as T
                is Long -> sharedPreferences.getLong(key, defaultValue) as T
                else -> defaultValue
            }
        } catch (ex: ClassCastException) {
            defaultValue
        }
    }

}