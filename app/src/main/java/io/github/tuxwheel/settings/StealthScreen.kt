package io.github.tuxwheel.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.utils.ThemeIconEnum
import org.koin.compose.koinInject

/**
 * Stealth / sheriff mode gathered in one place: a master switch that flips every stealth
 * option at once, and the individual options below it that keep working on their own.
 */
@Composable
fun stealthScreen(
    appConfig: AppConfig = koinInject(),
) {
    val all = remember { mutableStateOf(appConfig.hideTripSpeeds && appConfig.stealthLogging) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        switchPref(
            name = stringResource(R.string.stealth_master_title),
            desc = stringResource(R.string.stealth_master_description),
            themeIcon = ThemeIconEnum.SettingsBug,
            defaultState = all,
        ) { checked ->
            appConfig.hideTripSpeeds = checked
            appConfig.stealthLogging = checked
            all.value = checked
        }
        switchPref(
            name = stringResource(R.string.trip_stealth_mode_title),
            desc = stringResource(R.string.stealth_trip_description),
            default = appConfig.hideTripSpeeds,
        ) {
            appConfig.hideTripSpeeds = it
            all.value = appConfig.hideTripSpeeds && appConfig.stealthLogging
        }
        switchPref(
            name = stringResource(R.string.log_stealth_mode_title),
            desc = stringResource(R.string.stealth_log_description),
            default = appConfig.stealthLogging,
            showDiv = false,
        ) {
            appConfig.stealthLogging = it
            all.value = appConfig.hideTripSpeeds && appConfig.stealthLogging
        }
    }
}
