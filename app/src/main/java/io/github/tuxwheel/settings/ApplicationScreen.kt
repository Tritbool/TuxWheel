package io.github.tuxwheel.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.feature.dashboard.DashboardCatalogue
import io.github.tuxwheel.feature.dashboard.DashboardBlock
import io.github.tuxwheel.utils.NotificationUtil
import io.github.tuxwheel.utils.ThemeIconEnum
import org.koin.compose.koinInject

@Composable
fun applicationScreen(
    appConfig: AppConfig = koinInject(),
    notifications: NotificationUtil = koinInject()) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
    ) {
        var restartRequiredAlert by remember { mutableStateOf(false) }
        if (restartRequiredAlert) {
            AlertDialog(
                onDismissRequest ={ restartRequiredAlert = false },
                title = { Text(stringResource(R.string.use_eng_alert_title)) },
                text = { Text(stringResource(R.string.use_eng_alert_description)) },
                confirmButton = {
                    Button(onClick = { restartRequiredAlert = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        group(name = stringResource(R.string.general_settings_category_title)) {
            switchPref(
                name = stringResource(R.string.use_eng_title),
                desc = stringResource(R.string.use_eng_description),
                themeIcon = ThemeIconEnum.SettingsLanguage,
                default = appConfig.useEng
            ) {
                restartRequiredAlert = true
                appConfig.useEng = it
            }

            list(
                name = stringResource(R.string.day_night_theme_title),
                entries = mapOf(
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString() to stringResource(R.string.day_night_theme_as_system),
                    AppCompatDelegate.MODE_NIGHT_NO.toString() to stringResource(R.string.day_night_theme_day),
                    AppCompatDelegate.MODE_NIGHT_YES.toString() to stringResource(R.string.day_night_theme_night),
                ),
                defaultKey = appConfig.dayNightThemeMode.toString(),
                showDiv = false,
            ) {
                val mode = it.first.toInt()
                appConfig.dayNightThemeMode = mode
                // Apply live: AppCompat recreates activities with the new night
                // mode, so Compose screens and the dashboard update immediately.
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        group(name = stringResource(R.string.measurement_systems_category_title)) {

            switchPref(
                name = stringResource(R.string.use_mph_title),
                desc = stringResource(R.string.use_mph_description),
                default = appConfig.useMph
            ) {
                appConfig.useMph = it
            }

            switchPref(
                name = stringResource(R.string.use_fahrenheit_title),
                desc = stringResource(R.string.use_fahrenheit_description),
                default = appConfig.useFahrenheit,
            ) {
                appConfig.useFahrenheit = it
            }

        }

        group(name = stringResource(R.string.main_view_category)) {
            val context = LocalContext.current
            val blocks = DashboardCatalogue.labels.map { DashboardBlock(stringResource(it), "", metric = it) }
            val persistedTitles = appConfig.viewBlocks.toList()
            val selectedTitles = remember(context, blocks, persistedTitles) {
                val aliases = if (persistedTitles.any { title -> blocks.none { it.label == title } })
                    DashboardCatalogue.aliases(context) else emptyMap()
                DashboardCatalogue.select(persistedTitles, blocks, aliases).map { it.label }
            }

            multiList(
                name = stringResource(R.string.view_blocks_title),
                desc = stringResource(R.string.view_blocks_description),
                themeIcon = ThemeIconEnum.SettingsBlocks,
                entries = blocks.associate { it.label to it.label },
                defaultKeys = selectedTitles,
                useSort = true,
            ) {
                appConfig.viewBlocks = it.toTypedArray()
            }
            switchPref(
                name = stringResource(R.string.use_pip_mode_title),
                desc = stringResource(R.string.use_pip_mode_description),
                default = appConfig.usePipMode,
            ) {
                appConfig.usePipMode = it
            }
            multiList(
                name = stringResource(R.string.notification_buttons_title),
                desc = stringResource(R.string.notification_buttons_description),
                themeIcon = ThemeIconEnum.SettingsNotification,
                entries = mapOf(
                    stringResource(R.string.icon_connection) to stringResource(R.string.icon_connection),
                    stringResource(R.string.icon_logging) to stringResource(R.string.icon_logging),
                    stringResource(R.string.icon_beep) to stringResource(R.string.icon_beep),
                    stringResource(R.string.icon_light) to stringResource(R.string.icon_light),
                ),
                defaultKeys = appConfig.notificationButtons.toList()
            ) {
                appConfig.notificationButtons = it.toTypedArray()
                notifications.update()
            }

            switchPref(
                name = stringResource(R.string.show_clock_title),
                default = appConfig.showClock,
                showDiv = false,
            ) {
                appConfig.showClock = it
            }
        }

        group(name = stringResource(R.string.dashboard_speedial_category_title)) {
            var valueOnDial by remember { mutableStateOf(appConfig.valueOnDial) }
            list(
                name = stringResource(R.string.value_on_dial_title),
                desc = stringResource(R.string.value_on_dial_description),
                entries = mapOf(
                    "0" to stringResource(R.string.speed),
                    "1" to stringResource(R.string.current),
                    "2" to stringResource(R.string.pwm),
                    "3" to stringResource(R.string.phase_current),
                ),
                defaultKey = valueOnDial,
            ) {
                valueOnDial = it.first
                appConfig.valueOnDial = valueOnDial
            }

            val pwmDial = (valueOnDial == "2")
            val ringUnit = when (valueOnDial) {
                "0" -> stringResource(if (appConfig.useMph) R.string.mph else R.string.kmh)
                "1", "3" -> stringResource(R.string.amp)
                else -> stringResource(R.string.persent)
            }
            sliderPref(
                name = stringResource(R.string.max_speed_dial_title),
                desc = stringResource(R.string.max_speed_dial_description, ringUnit),
                min = 10f,
                max = 400f,
                position = appConfig.maxSpeed.toFloat(),
            ) {
                appConfig.maxSpeed = it.toInt()
            }

            var shortPwm by remember { mutableStateOf(appConfig.useShortPwm) }
            switchPref(
                name = stringResource(R.string.use_short_pwm_title),
                desc = stringResource(R.string.use_short_pwm_description),
                default = shortPwm,
                showDiv = true,
            ) {
                shortPwm = it
                appConfig.useShortPwm = shortPwm
            }

            switchPref(
                name = stringResource(R.string.swap_speedpwm_title),
                desc = stringResource(R.string.swap_speedpwm_description),
                default = appConfig.swapSpeedPwm,
                showDiv = false,
            ) {
                appConfig.swapSpeedPwm = it
            }

            var colorPwmStart by remember { mutableStateOf(appConfig.colorPwmStart.toFloat()) }
            var colorPwmEnd by remember { mutableStateOf(appConfig.colorPwmEnd.toFloat()) }
            AnimatedVisibility(visible = (shortPwm || pwmDial)) {
                sliderPref(
                    name = stringResource(R.string.color_pwm_start_title),
                    desc = stringResource(R.string.color_pwm_start_description),
                    min = 0f,
                    max = 100f,
                    position = colorPwmStart,
                    showDiv = false,
                    unit = R.string.persent,
                ) {
                    colorPwmStart = it

                    if (colorPwmStart > colorPwmEnd) {
                        colorPwmEnd = colorPwmStart
                    }

                    appConfig.colorPwmStart = colorPwmStart.toInt()
                    appConfig.colorPwmEnd = colorPwmEnd.toInt()
                }
            }

            AnimatedVisibility(visible = (shortPwm || pwmDial)) {
                sliderPref(
                    name = stringResource(R.string.color_pwm_end_title),
                    desc = stringResource(R.string.color_pwm_end_description),
                    min = 0f,
                    max = 100f,
                    position = colorPwmEnd,
                    showDiv = false,
                    unit = R.string.persent,
                ) {
                    colorPwmEnd = it

                    if (colorPwmEnd < colorPwmStart) {
                        colorPwmStart = colorPwmEnd
                    }

                    appConfig.colorPwmStart = colorPwmStart.toInt()
                    appConfig.colorPwmEnd = colorPwmEnd.toInt()
                }
            }
        }

        group(name = stringResource(R.string.dashboard_pages_category_title)) {
            switchPref(
                name = stringResource(R.string.show_page_graph_title),
                default = appConfig.pageGraph,
            ) {
                appConfig.pageGraph = it
            }

            switchPref(
                name = stringResource(R.string.show_page_events_title),
                desc = stringResource(R.string.show_page_events_description),
                themeIcon = ThemeIconEnum.SettingsPageEvents,
                default = appConfig.pageEvents,
            ) {
                appConfig.pageEvents = it
            }

            switchPref(
                name = stringResource(R.string.show_page_trips_title),
                themeIcon = ThemeIconEnum.SettingsPageTrips,
                default = appConfig.pageTrips,
                showDiv = false,
            ) {
                appConfig.pageTrips = it
            }
        }

        group(name = stringResource(R.string.audio_category_title)) {
            sliderPref(
                name = stringResource(R.string.no_connection_sound_title),
                desc = stringResource(R.string.no_connection_sound_description),
                min = 0f,
                max = 60f,
                unit = R.string.sec,
                position = appConfig.noConnectionSound.toFloat(),
                showSwitch = true,
                disableSwitchAtMin = true,
            ) {
                appConfig.noConnectionSound = it.toInt()
            }

            switchPref(
                name = stringResource(R.string.use_stop_music_title),
                desc = stringResource(R.string.use_stop_music_description),
                themeIcon = ThemeIconEnum.SettingsAutoMute,
                default = appConfig.useStopMusic,
                showDiv = false,
            ) {
                appConfig.useStopMusic = it
            }
        }

        group(
            name = stringResource(R.string.beep_category),
        ) {
            switchPref(
                name = stringResource(R.string.beep_on_single_tap_title),
                desc = stringResource(R.string.beep_on_single_tap_description),
                default = appConfig.useBeepOnSingleTap,
            ) {
                appConfig.useBeepOnSingleTap = it
            }

            switchPref(
                name = stringResource(R.string.beep_on_volume_up_title),
                desc = stringResource(R.string.beep_on_volume_up_description),
                default = appConfig.useBeepOnVolumeUp,
            ) {
                appConfig.useBeepOnVolumeUp = it
            }

            var beepByWheel by remember { mutableStateOf(appConfig.beepByWheel) }
            switchPref(
                name = stringResource(R.string.beep_by_wheel_title),
                desc = stringResource(R.string.beep_by_wheel_description),
                default = appConfig.beepByWheel,
            ) {
                appConfig.beepByWheel = it
                beepByWheel = it
            }

            var useCustomBeep by remember { mutableStateOf(appConfig.useCustomBeep) }
            AnimatedVisibility(!beepByWheel) {
                // var showBeepDialog by remember { mutableStateOf(false) }
                Column {
                    switchPref(
                        name = stringResource(R.string.use_custom_beep_title),
                        default = appConfig.useCustomBeep,
                        showDiv = false,
                    ) {
                        appConfig.useCustomBeep = it
                        useCustomBeep = it
                    }

                    AnimatedVisibility (useCustomBeep) {
                        var ringtone by remember { mutableStateOf(appConfig.beepFile) }
                        Column {
                            alarmsList(
                                name = stringResource(R.string.custom_beep_title),
                                default = ringtone,
                            ) {
                                appConfig.beepFile = it.second
                                ringtone = it.second
                            }
                            sliderPref(
                                name = stringResource(R.string.beep_time_limit_title),
                                min = 0.5f,
                                max = 20f,
                                unit = R.string.sec,
                                position = appConfig.customBeepTimeLimit,
                                format = "%.1f",
                                showDiv = false,
                            ) {
                                appConfig.customBeepTimeLimit = it
                            }
                        }
                    }
                }
            }
        }

        group(name = stringResource(R.string.system_category_title)) {
            switchPref(
                name = stringResource(R.string.use_detect_battery_optimization_title),
                desc = stringResource(R.string.use_detect_battery_optimization_description),
                default = appConfig.detectBatteryOptimization,
            ) {
                appConfig.detectBatteryOptimization = it
            }
        }
    }
}