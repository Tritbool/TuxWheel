package io.github.tuxwheel.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.BuildConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.ble.getWheelType
import io.github.tuxwheel.background.BackgroundSessionSettingsActivity
import io.github.tuxwheel.utils.Constants
import io.github.tuxwheel.utils.ThemeIconEnum
import org.koin.compose.koinInject

@Composable
fun startScreen(
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit = {},
) {
    val viewModel: BleSessionViewModel = koinInject()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val isSpecificVisible = sessionState.getWheelType() != Constants.WHEEL_TYPE.Unknown
    val config: AppConfig = koinInject()
    val blackBoxStatus by produceState(
        initialValue = blackBoxSettingStatus(config),
        config,
    ) {
        config.dashboardPreferences().collect { value = blackBoxSettingStatus(config) }
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val context: Context = LocalContext.current

        clickablePref(
            name = stringResource(R.string.speed_limiter_title),
            themeIcon = ThemeIconEnum.SettingsSpeedLimiter,
        ) {
            onSelect(SettingsScreenEnum.SpeedLimiter.name)
        }
        clickablePref(
            name = stringResource(R.string.speed_settings_title),
            themeIcon = ThemeIconEnum.SettingsSpeedometer,
        ) {
            onSelect(SettingsScreenEnum.Application.name)
        }
        clickablePref(
            name = stringResource(R.string.stealth_settings_title),
            themeIcon = ThemeIconEnum.SettingsBug,
        ) {
            onSelect(SettingsScreenEnum.Stealth.name)
        }
        clickablePref(
            name = stringResource(R.string.logs_settings_title),
            themeIcon = ThemeIconEnum.SettingsLog,
        ) {
            onSelect(SettingsScreenEnum.Log.name)
        }
        clickablePref(
            name = stringResource(R.string.background_session_title),
            desc = stringResource(blackBoxStatus),
        ) {
            context.startActivity(Intent(context, BackgroundSessionSettingsActivity::class.java))
        }
        clickablePref(
            name = stringResource(R.string.remote_buttons_title),
            themeIcon = ThemeIconEnum.MenuBluetooth,
        ) {
            onSelect(SettingsScreenEnum.RemoteButtons.name)
        }
        switchPref(
            name = stringResource(R.string.auto_switch_wheels_title),
            desc = stringResource(R.string.auto_switch_wheels_description),
            default = config.autoSwitchToPriorityWheel,
            showDiv = false,
        ) {
            config.autoSwitchToPriorityWheel = it
        }
        if (isSpecificVisible) {
            clickablePref(
                name = stringResource(R.string.alarm_settings_title),
                themeIcon = ThemeIconEnum.SettingsVibration,
            ) {
                onSelect(SettingsScreenEnum.Alarm.name)
            }
        }
        if (isSpecificVisible) {
            clickablePref(
                name = stringResource(R.string.wheel_settings_title),
                themeIcon = ThemeIconEnum.SettingsWheel,
            ) {
                onSelect(SettingsScreenEnum.Wheel.name)
            }
            clickablePref(
                name = stringResource(R.string.trip_settings_title),
                themeIcon = ThemeIconEnum.SettingsTrips,
            ) {
                onSelect(SettingsScreenEnum.Trip.name)
            }
        }
        var showAboutDialog by remember { mutableStateOf(false) }
        var showLicensesDialog by remember { mutableStateOf(false) }

        if (showLicensesDialog) {
            ThirdPartyLicensesDialog(onDismissRequest = { showLicensesDialog = false })
        }
        if (showAboutDialog && !showLicensesDialog) {
            AlertDialog(
                shape = RoundedCornerShape(8.dp),
                onDismissRequest = { showAboutDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.about_app_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        clickablePref(
                            name = stringResource(R.string.github),
                            desc = stringResource(R.string.github_desc),
                        ) {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/Tritbool/TuxWheel".toUri(),
                                ),
                            )
                        }
                        clickablePref(
                            name = stringResource(R.string.FAQ),
                        ) {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/Tritbool/TuxWheel#usage".toUri()
                                ),
                            )
                        }
                        clickablePref(
                            name = stringResource(R.string.bug_report),
                        ) {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/Tritbool/TuxWheel/issues".toUri()
                                ),
                            )
                        }
                        clickablePref(
                            name = stringResource(R.string.third_party_licenses_title),
                        ) {
                            showLicensesDialog = true
                        }
                        Text(
                            text = stringResource(R.string.about_app_desc),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showAboutDialog = false }) {
                        Text(stringResource(id = android.R.string.ok))
                    }
                },
            )
        }
        clickablePref(
            name = stringResource(R.string.about_app_title),
            themeIcon = ThemeIconEnum.SettingsAbout,
            showArrowIcon = false,
            showDiv = false,
        ) {
            showAboutDialog = true
        }
    }
}

private fun blackBoxSettingStatus(config: AppConfig): Int = when {
    !config.backgroundSessionEnabled -> R.string.background_session_disabled
    config.backgroundSessionPaused -> R.string.background_session_paused
    else -> R.string.background_session_enabled
}

@Preview
@Composable
fun startScreenPreview(appConfig: AppConfig = koinInject()) {
    startScreen()
}