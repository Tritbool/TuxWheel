package io.github.tuxwheel.utils

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import io.github.tuxwheel.R

object ThemeManager {

    private val original = HashMap<ThemeIconEnum, Int>()

    fun getTypeface(context: Context): Typeface {
        val fontResId = R.font.prime
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.resources.getFont(fontResId)
        else
            ResourcesCompat.getFont(context, fontResId)!!
    }

    init {
        // notification
        original[ThemeIconEnum.NotificationIcon] = R.drawable.ic_stat_tuxwheel
        original[ThemeIconEnum.NotificationConnecting] = R.drawable.ic_action_wheel_light_orange
        original[ThemeIconEnum.NotificationConnected] = R.drawable.ic_action_wheel_orange
        original[ThemeIconEnum.NotificationDisconnected] = R.drawable.ic_action_wheel_grey
        original[ThemeIconEnum.NotificationLogOn] = R.drawable.ic_action_logging_orange
        original[ThemeIconEnum.NotificationLogOff] = R.drawable.ic_action_logging_grey
        original[ThemeIconEnum.NotificationHorn] = R.drawable.ic_horn_32_gray
        original[ThemeIconEnum.NotificationLight] = R.drawable.ic_sun_32_gray
        // menu
        original[ThemeIconEnum.MenuLogOn] = R.drawable.ic_action_logging_orange
        original[ThemeIconEnum.MenuLogOff] = R.drawable.ic_action_logging_white
        original[ThemeIconEnum.MenuWheelOn] = R.drawable.ic_action_wheel_orange
        original[ThemeIconEnum.MenuWheelSearch] = R.drawable.anim_wheel_icon
        original[ThemeIconEnum.MenuWheelOff] = R.drawable.ic_action_wheel_white
        original[ThemeIconEnum.MenuSettings] = R.drawable.ic_baseline_settings_24
        original[ThemeIconEnum.MenuBluetooth] = R.drawable.ic_action_bluetooth_searching_white
        // settings page
        original[ThemeIconEnum.SettingsSpeedometer] = R.drawable.ic_speedometer_white_24dp
        original[ThemeIconEnum.SettingsSpeedLimiter] = R.drawable.ic_speed_limit
        original[ThemeIconEnum.SettingsLog] = R.drawable.ic_show_chart_white_24dp
        original[ThemeIconEnum.SettingsBug] = R.drawable.ic_baseline_bug_report_24
        original[ThemeIconEnum.SettingsAbout] = R.drawable.ic_baseline_info_24
        original[ThemeIconEnum.SettingsVibration] = R.drawable.ic_baseline_vibration_24
        original[ThemeIconEnum.SettingsWheel] = R.drawable.ic_wheel_white_24
        original[ThemeIconEnum.SettingsTrips] = R.drawable.ic_baseline_explore_24
        original[ThemeIconEnum.SettingsDonate] = R.drawable.ic_donate_24
        original[ThemeIconEnum.SettingsPowerOff] = R.drawable.ic_baseline_power_off_24
        original[ThemeIconEnum.SettingsCalibration] = R.drawable.ic_baseline_calibration_24
        original[ThemeIconEnum.SettingsLanguage] = R.drawable.ic_baseline_translate_24
        original[ThemeIconEnum.SettingsAutoLog] = R.drawable.ic_baseline_magic_log_24
        original[ThemeIconEnum.SettingsBlocks] = R.drawable.ic_baseline_dashboard_customize_24
        original[ThemeIconEnum.SettingsNotification] = R.drawable.ic_notification
        original[ThemeIconEnum.SettingsPageEvents] = R.drawable.ic_baseline_event_note_24
        original[ThemeIconEnum.SettingsPageTrips] = R.drawable.ic_baseline_road_with_two_placeholders_24
        original[ThemeIconEnum.SettingsConnectionSound] = R.drawable.ic_baseline_music_note_24
        original[ThemeIconEnum.SettingsAutoMute] = R.drawable.ic_baseline_volume_off_24
        // trip page
        original[ThemeIconEnum.TripsPopupButton] = R.drawable.ic_baseline_settings_24
        original[ThemeIconEnum.TripsShare] = R.drawable.ic_baseline_share_24
        original[ThemeIconEnum.TripsDelete] = R.drawable.ic_baseline_delete_24
    }

    @DrawableRes
    fun getId(icon: ThemeIconEnum): Int = original[icon] ?: R.drawable.transparent

}