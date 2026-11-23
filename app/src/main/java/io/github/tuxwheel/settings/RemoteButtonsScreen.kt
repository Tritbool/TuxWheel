package io.github.tuxwheel.settings

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.feature.remotebutton.RemoteButtonAction
import io.github.tuxwheel.feature.remotebutton.RemoteButtonBinding
import io.github.tuxwheel.feature.remotebutton.RemoteButtonBindings
import io.github.tuxwheel.feature.remotebutton.RemoteButtonDispatcher
import io.github.tuxwheel.utils.ThemeIconEnum
import io.github.tuxwheel.utils.VolumeKeyController
import org.koin.compose.koinInject

@Composable
fun remoteButtonsScreen(
    appConfig: AppConfig = koinInject(),
    viewModel: BleSessionViewModel = koinInject(),
    remoteButtonDispatcher: RemoteButtonDispatcher = koinInject(),
    volumeKeyController: VolumeKeyController = koinInject(),
) {
    val state by viewModel.sessionState.collectAsState()
    var bindings by remember {
        mutableStateOf(RemoteButtonBindings.parse(appConfig.remoteButtonBindings))
    }
    var actionSlot by remember { mutableStateOf<Int?>(null) }
    var listening by remember { mutableStateOf(false) }
    var listeningSlot by remember { mutableStateOf(0) }
    var lastLearnedWasDuplicate by remember { mutableStateOf(false) }

    fun persist(next: List<RemoteButtonBinding>) {
        bindings = next.take(RemoteButtonBindings.MAX_SLOTS)
        appConfig.remoteButtonBindings = RemoteButtonBindings.serialize(bindings)
        volumeKeyController.setActive(
            state.isConnected && (appConfig.useBeepOnVolumeUp || bindings.isNotEmpty())
        )
    }

    fun startListening() {
        listeningSlot = bindings.size
        remoteButtonDispatcher.learningSlot = listeningSlot
        listening = true
    }

    fun stopListening() {
        remoteButtonDispatcher.learningSlot = null
        listening = false
    }

    DisposableEffect(Unit) {
        remoteButtonDispatcher.onKeyLearned = { slot, learned ->
            if (listening && slot == listeningSlot) {
                listening = false
                remoteButtonDispatcher.learningSlot = null
                val existingSlot = bindings.indexOfFirst { it.keyCode == learned.keyCode }
                lastLearnedWasDuplicate = existingSlot >= 0
                val (next, finalSlot) = RemoteButtonBindings.upsert(bindings, slot, learned)
                persist(next)
                actionSlot = finalSlot
            } else if (slot >= RemoteButtonBindings.MAX_SLOTS) {
                stopListening()
            }
        }
        onDispose {
            remoteButtonDispatcher.onKeyLearned = null
            remoteButtonDispatcher.learningSlot = null
            listening = false
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        baseSettings(
            name = stringResource(R.string.remote_buttons_description),
            desc = if (state.isConnected) stringResource(R.string.remote_buttons_connected_hint)
            else stringResource(R.string.remote_buttons_offline),
            showDiv = false,
        )
        if (listening) {
            baseSettings(
                name = stringResource(R.string.remote_buttons_listening),
                desc = stringResource(R.string.remote_buttons_listening_hint),
                showDiv = false,
            )
        }
        if (lastLearnedWasDuplicate) {
            baseSettings(
                name = stringResource(R.string.remote_button_duplicate_note),
                desc = "",
                showDiv = false,
            )
        }
        bindings.forEachIndexed { index, binding ->
            clickablePref(
                name = bindingLabel(binding),
                themeIcon = ThemeIconEnum.MenuBluetooth,
                showArrowIcon = false,
            ) {
                actionSlot = index
            }
        }
        if (bindings.size < RemoteButtonBindings.MAX_SLOTS) {
            clickablePref(
                name = stringResource(R.string.remote_buttons_add),
                themeIcon = ThemeIconEnum.MenuBluetooth,
                desc = stringResource(R.string.remote_buttons_add_hint),
                showArrowIcon = false,
            ) {
                if (!listening) startListening()
            }
        }
    }

    actionSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { actionSlot = null },
            title = {
                Text(
                    stringResource(
                        R.string.remote_button_action_title,
                        bindings.getOrNull(slot)?.let { keyLabel(it.keyCode) } ?: "",
                    )
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    RemoteButtonAction.entries.forEach { candidate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    persist(
                                        bindings.mapIndexed { index, pair ->
                                            if (index == slot) pair.copy(action = candidate) else pair
                                        }
                                    )
                                    actionSlot = null
                                }
                        ) {
                            RadioButton(
                                selected = bindings.getOrNull(slot)?.action == candidate,
                                onClick = null,
                            )
                            Text(stringResource(actionLabel(candidate)))
                        }
                    }
                    clickablePref(
                        name = stringResource(R.string.remote_button_forget),
                        themeIcon = ThemeIconEnum.SettingsPowerOff,
                        showArrowIcon = false,
                        showDiv = false,
                    ) {
                        persist(bindings.filterIndexed { index, _ -> index != slot })
                        actionSlot = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionSlot = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun bindingLabel(binding: RemoteButtonBinding): String {
    val device = binding.deviceName?.takeIf { it.isNotBlank() }
        ?: binding.deviceId?.let { stringResource(R.string.remote_button_unknown_device) }
    val base = keyLabel(binding.keyCode) + " → " + stringResource(actionLabel(binding.action))
    return if (device == null) base
    else base + " (" + device + ")"
}

private fun keyLabel(keyCode: Int): String =
    KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        .replace('_', ' ').lowercase()

private fun actionLabel(action: RemoteButtonAction): Int = when (action) {
    RemoteButtonAction.ToggleSpeedLimiter -> R.string.remote_action_speed_limiter
    RemoteButtonAction.ToggleLight -> R.string.remote_action_light
    RemoteButtonAction.ToggleWheelLock -> R.string.remote_action_lock
    RemoteButtonAction.Beep -> R.string.remote_action_beep
}
