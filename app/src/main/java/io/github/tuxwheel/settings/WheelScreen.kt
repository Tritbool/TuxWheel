package io.github.tuxwheel.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.utils.Constants
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.models.PasswordOperation
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import org.koin.compose.koinInject

@Composable
@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
fun wheelScreen(
    appConfig: AppConfig = koinInject(),
    viewModel: BleSessionViewModel = koinInject(),
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        baseSettings(
            name = if (viewModel.isConnected) {
                viewModel.model.ifBlank { viewModel.name.ifBlank { stringResource(R.string.unknown_device) } }
            } else {
                stringResource(R.string.unknown_device)
            },
            desc = if (viewModel.isConnected) viewModel.manufacturer else "",
        )

        diagnosticLogSetting(viewModel)

        if (!viewModel.isConnected) {
            return@Column
        }

        lockSetting(appConfig, viewModel)
        passwordManagementSetting(viewModel)
        connectBeepSetting(appConfig, viewModel)
        commandContractSettings(viewModel)
        gotwayCorrectionSettings(appConfig, viewModel)
        forAllWheel(appConfig)
    }
}

@Composable
@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
private fun passwordManagementSetting(viewModel: BleSessionViewModel) {
    val supportsSet = viewModel.isCommandSupported(CommandType.SET_PASSWORD)
    val supportsChange = viewModel.isCommandSupported(CommandType.CHANGE_PASSWORD)
    val supportsRemove = viewModel.isCommandSupported(CommandType.REMOVE_PASSWORD)
    if (!(supportsSet || supportsChange || supportsRemove)) return

    val state by viewModel.sessionState.collectAsState()
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    clickablePref(
        name = stringResource(R.string.wheel_password_management_title),
        desc = stringResource(R.string.wheel_password_management_description),
    ) {
        viewModel.clearWheelPasswordOperationResult()
        showDialog = true
    }
    LaunchedEffect(state.passwordOperationStatus) {
        val message = when (state.passwordOperationStatus) {
            PasswordOperationStatus.CONFIRMED -> R.string.wheel_password_confirmed
            PasswordOperationStatus.TIMEOUT_UNCONFIRMED -> R.string.wheel_password_unconfirmed
            else -> null
        }
        if (message != null && !state.passwordOperationPending) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearWheelPasswordOperationResult()
            showDialog = false
        }
    }
    if (showDialog) {
        WheelPasswordDialog(
            viewModel = viewModel,
            state = state,
            supportsSet = supportsSet,
            supportsChange = supportsChange,
            supportsRemove = supportsRemove,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
private fun WheelPasswordDialog(
    viewModel: BleSessionViewModel,
    state: io.github.tuxwheel.ble.BleSessionState,
    supportsSet: Boolean,
    supportsChange: Boolean,
    supportsRemove: Boolean,
    onDismiss: () -> Unit,
) {
    var operation by remember {
        mutableStateOf(state.passwordOperation ?: PasswordOperation.SET)
    }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var submitError by remember { mutableStateOf(false) }

    val pending = state.passwordOperationPending
    val result = state.passwordOperationStatus
    val canSubmit = validWheelPasswordInput(operation, currentPassword, newPassword, confirmPassword) &&
        when (operation) {
            PasswordOperation.SET -> supportsSet
            PasswordOperation.CHANGE -> supportsChange
            PasswordOperation.REMOVE -> supportsRemove
        }
    val passwordMismatch = confirmPassword.isNotEmpty() && confirmPassword != newPassword
    val hasResult = result != null || state.passwordOperationError || submitError

    fun clearResult() {
        submitError = false
        viewModel.clearWheelPasswordOperationResult()
    }

    fun dismiss() {
        currentPassword = ""
        newPassword = ""
        confirmPassword = ""
        viewModel.clearWheelPasswordOperationResult()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text(stringResource(R.string.wheel_password_management_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.wheel_password_management_warning))
                Row {
                    TextButton(
                        enabled = !pending && supportsSet,
                        onClick = {
                            operation = PasswordOperation.SET
                            currentPassword = ""
                            newPassword = ""
                            confirmPassword = ""
                            clearResult()
                        },
                    ) { Text(stringResource(R.string.wheel_password_set)) }
                    TextButton(
                        enabled = !pending && supportsChange,
                        onClick = {
                            operation = PasswordOperation.CHANGE
                            currentPassword = ""
                            newPassword = ""
                            confirmPassword = ""
                            clearResult()
                        },
                    ) { Text(stringResource(R.string.wheel_password_change)) }
                    TextButton(
                        enabled = !pending && supportsRemove,
                        onClick = {
                            operation = PasswordOperation.REMOVE
                            currentPassword = ""
                            newPassword = ""
                            confirmPassword = ""
                            clearResult()
                        },
                    ) { Text(stringResource(R.string.wheel_password_remove)) }
                }
                if (operation != PasswordOperation.SET) {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all { it in '0'..'9' }) {
                                currentPassword = value
                                clearResult()
                            }
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (operation == PasswordOperation.CHANGE) {
                                        R.string.wheel_password_current
                                    } else {
                                        R.string.wheel_password_current_remove
                                    }
                                )
                            )
                        },
                        singleLine = true,
                        enabled = !pending,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                if (operation != PasswordOperation.REMOVE) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all { it in '0'..'9' }) {
                                newPassword = value
                                clearResult()
                            }
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (operation == PasswordOperation.CHANGE) {
                                        R.string.wheel_password_new
                                    } else {
                                        R.string.wheel_password_value
                                    }
                                )
                            )
                        },
                        singleLine = true,
                        enabled = !pending,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { value ->
                            if (value.length <= 4 && value.all { it in '0'..'9' }) {
                                confirmPassword = value
                                clearResult()
                            }
                        },
                        label = { Text(stringResource(R.string.wheel_password_confirm)) },
                        isError = passwordMismatch,
                        supportingText = {
                            if (passwordMismatch) {
                                Text(stringResource(R.string.wheel_password_mismatch))
                            }
                        },
                        singleLine = true,
                        enabled = !pending,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                when {
                    pending -> Text(stringResource(R.string.wheel_password_waiting))
                    state.passwordOperationError || submitError ->
                        Text(stringResource(R.string.wheel_password_send_failed))
                }
            }
        },
        confirmButton = {
            if (!hasResult && !pending) {
                TextButton(
                    enabled = canSubmit,
                    onClick = {
                        val accepted = when (operation) {
                            PasswordOperation.SET -> viewModel.setWheelPassword(newPassword)
                            PasswordOperation.CHANGE ->
                                viewModel.changeWheelPassword(currentPassword, newPassword)
                            PasswordOperation.REMOVE -> viewModel.removeWheelPassword(currentPassword)
                        }
                        if (!accepted) submitError = true
                        currentPassword = ""
                        newPassword = ""
                        confirmPassword = ""
                    },
                ) { Text(stringResource(R.string.wheel_password_submit)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { dismiss() }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(dismissOnClickOutside = !pending),
    )
}

internal fun validWheelPasswordInput(
    operation: PasswordOperation,
    currentPassword: String,
    newPassword: String,
    confirmPassword: String,
): Boolean {
    fun valid(password: String) = password.length == 4 && password.all { it in '0'..'9' }
    return when (operation) {
        PasswordOperation.SET -> valid(newPassword) && newPassword == confirmPassword
        PasswordOperation.CHANGE ->
            valid(currentPassword) && valid(newPassword) && newPassword == confirmPassword
        PasswordOperation.REMOVE -> valid(currentPassword)
    }
}

@Composable
private fun connectBeepSetting(appConfig: AppConfig, viewModel: BleSessionViewModel) {
    if (!viewModel.isCommandSupported(CommandType.BEEP)) return

    switchPref(
        name = stringResource(R.string.connect_beep_title),
        desc = stringResource(R.string.connect_beep_description),
        default = appConfig.connectBeep,
    ) { enabled ->
        appConfig.connectBeep = enabled
    }
}

@Composable
@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
internal fun lockSetting(appConfig: AppConfig, viewModel: BleSessionViewModel) {
    val state by viewModel.sessionState.collectAsState()
    val supportsLock = CommandType.LOCK in state.supportedSecurityCommands
    val supportsUnlock = CommandType.UNLOCK in state.supportedSecurityCommands
    val supportsLockState = CommandType.REQUEST_LOCK_STATE in state.supportedSecurityCommands
    if (!(supportsLock && supportsUnlock && supportsLockState)) return

    val ready = CommandType.AUTHENTICATE in state.supportedSecurityCommands ||
        state.wheelAuthentication == AuthenticationState.AUTHENTICATED
    val checkedState = remember(state.selectedDevice?.address) { mutableStateOf(appConfig.wheelLocked) }
    androidx.compose.runtime.LaunchedEffect(
        state.selectedDevice?.address,
        state.isConnected,
        supportsLockState,
        ready,
    ) {
        viewModel.requestWheelLockState()
    }
    androidx.compose.runtime.LaunchedEffect(
        state.selectedDevice?.address,
        state.wheelLockState,
        state.wheelLockStateRevision,
    ) {
        when (state.wheelLockState) {
            WheelLockState.LOCKED -> {
                checkedState.value = true
                appConfig.wheelLocked = true
            }
            WheelLockState.UNLOCKED -> {
                checkedState.value = false
                appConfig.wheelLocked = false
            }
            WheelLockState.UNKNOWN -> Unit
        }
    }
    switchPref(
        name = stringResource(R.string.lock_mode_title),
        desc = stringResource(when {
            !ready -> R.string.wheel_lock_authenticating
            state.wheelLockState == WheelLockState.UNKNOWN -> R.string.wheel_lock_unknown
            else -> R.string.lock_mode_description
        }),
        defaultState = checkedState,
        enabled = ready,
    ) { checked ->
        appConfig.wheelLocked = checked
        viewModel.setWheelLocked(checked)
    }
}

@Composable
@androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
internal fun diagnosticLogSetting(viewModel: BleSessionViewModel) {
    val state by viewModel.sessionState.collectAsState()
    val supported = state.isConnected && CommandType.REQUEST_DIAGNOSTIC_LOG in state.supportedSecurityCommands
    val transfer = state.diagnosticLogTransfer
    if (!supported && transfer == null && state.diagnosticLogError == null) return
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.saveDiagnosticLog(uri)
    }
    val description = when {
        state.diagnosticLogError != null -> state.diagnosticLogError!!
        state.diagnosticLogSaving -> stringResource(R.string.wheel_diagnostic_saving)
        state.diagnosticLogSaved -> stringResource(R.string.wheel_diagnostic_saved)
        transfer?.state == DiagnosticLogState.FAILED -> stringResource(
            R.string.wheel_diagnostic_failed, transfer.failure?.name.orEmpty())
        transfer?.state == DiagnosticLogState.CANCELLED -> stringResource(R.string.wheel_diagnostic_cancelled)
        transfer?.state == DiagnosticLogState.COMPLETED -> stringResource(R.string.wheel_diagnostic_verified)
        transfer?.state == DiagnosticLogState.VERIFYING -> stringResource(R.string.wheel_diagnostic_verifying)
        transfer?.state == DiagnosticLogState.READING -> stringResource(
            R.string.wheel_diagnostic_progress, transfer.receivedBytes, transfer.totalBytes ?: 0)
        state.diagnosticLogBusy -> stringResource(R.string.wheel_diagnostic_opening)
        else -> stringResource(R.string.wheel_diagnostic_description)
    }
    Column {
        baseSettings(name = stringResource(R.string.wheel_diagnostic_title), desc = description)
        if (state.diagnosticLogBusy) {
            val progress = transfer?.progress?.toFloat()
            if (progress == null) LinearProgressIndicator()
            else LinearProgressIndicator(progress = { progress })
        }
        Row {
            TextButton(
                enabled = supported && !state.diagnosticLogBusy && !state.diagnosticLogSaving,
                onClick = viewModel::requestDiagnosticLog,
            ) { Text(stringResource(R.string.wheel_diagnostic_download)) }
            if (state.diagnosticLogBusy) {
                TextButton(onClick = viewModel::cancelDiagnosticLog) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
            if (transfer?.state == DiagnosticLogState.COMPLETED) {
                TextButton(
                    enabled = !state.diagnosticLogSaving && !state.diagnosticLogBusy,
                    onClick = { save.launch("inmotion-diagnostic.bin") },
                ) { Text(stringResource(R.string.wheel_diagnostic_save)) }
            }
        }
    }
}

/**
 * Gotway/Begode's reverse-engineered protocol has known quirks that are corrected on the
 * app side rather than by asking the wheel to change anything (see
 * [BleSessionViewModel]'s telemetry pipeline for where these are applied). Only shown for
 * Gotway/Begode/ExtremeBull-family wheels.
 */
@Composable
private fun gotwayCorrectionSettings(appConfig: AppConfig, viewModel: BleSessionViewModel) {
    if (viewModel.wheelType != Constants.WHEEL_TYPE.GOTWAY) return

    switchPref(
        name = stringResource(R.string.is_gotway_mcm_title),
        desc = stringResource(R.string.is_gotway_mcm_description),
        default = appConfig.gotwayMcm,
    ) {
        appConfig.gotwayMcm = it
    }
    list(
        name = stringResource(R.string.gotway_negative_title),
        desc = stringResource(R.string.gotway_negative_description),
        entries = mapOf(
            "-1" to stringResource(R.string.straight),
            "0" to stringResource(R.string.absolute),
            "1" to stringResource(R.string.reverse),
        ),
        defaultKey = appConfig.gotwayNegative,
    ) {
        appConfig.gotwayNegative = it.first
    }
}

@Composable
private fun forAllWheel(appConfig: AppConfig = koinInject()) {
    sliderPref(
        name = stringResource(R.string.battery_capacity_title),
        desc = stringResource(R.string.battery_capacity_description),
        position = appConfig.batteryCapacity.toFloat(),
        min = 0f,
        max = 9999f,
        unit = R.string.wh,
    ) {
        appConfig.batteryCapacity = it.toInt()
    }
    sliderPref(
        name = stringResource(R.string.charging_power_title),
        desc = stringResource(R.string.charging_power_description),
        position = appConfig.chargingPower.toFloat() / 10f,
        min = 0f,
        max = 100.0f,
        unit = R.string.amp,
        format = "%.1f",
    ) {
        appConfig.chargingPower = it.toInt() * 10
    }

    var showProfileDialog by remember { mutableStateOf(false) }
    var profileText by remember { mutableStateOf(appConfig.profileName) }
    clickablePref(
        name = stringResource(R.string.profile_name_title),
        desc = profileText
    ) {
        showProfileDialog = true
    }
    if (showProfileDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.profile_name_title)) },
            text = {
                androidx.compose.material3.TextField(
                    value = profileText,
                    onValueChange = { newText ->
                        profileText = newText
                        appConfig.profileName = newText
                    },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showProfileDialog = false
                    },
                ) {
                    androidx.compose.material3.Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showProfileDialog = false },
                ) {
                    androidx.compose.material3.Text(stringResource(android.R.string.cancel))
                }
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
            ),
        )
    }
    baseSettings(
        name = stringResource(R.string.current_mac),
    ) {
        androidx.compose.material3.Text(appConfig.lastMac.trimEnd('_'))
    }
}
