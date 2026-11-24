package io.github.tuxwheel.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.utils.Constants
import org.koin.compose.koinInject

@Composable
fun tripScreen(
    viewModel: BleSessionViewModel = koinInject(),
    appConfig: AppConfig = koinInject()
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
    ) {
        clickablePref(
            name = stringResource(R.string.reset_max_values_title),
            desc = stringResource(R.string.reset_max_values_description),
            showArrowIcon = false,
        ) {
            viewModel.resetMaxValues()
        }
        val context = LocalContext.current
        clickablePref(
            name = stringResource(R.string.reset_lowest_battery_title),
            showArrowIcon = false,
        ) {
            viewModel.resetVoltageSag()
            context.sendBroadcast(Intent(Constants.ACTION_PREFERENCE_RESET))
        }
        clickablePref(
            name = stringResource(R.string.reset_user_distance_title),
            showArrowIcon = false,
        ) {
            viewModel.resetUserDistance()
        }
    }
}
