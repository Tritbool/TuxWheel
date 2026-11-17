package io.github.tuxwheel.settings

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel

@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun commandContractSettings(viewModel: BleSessionViewModel) {
    val state by viewModel.sessionState.collectAsState()
    val alarmCommandInput = state.commandDescriptors
        .firstOrNull { it.id == "SET_ALARM_SPEED" }
        ?.input
    val alarmCommandValue = reportedAlarmCommandValue(
        alarmCommandInput,
        state.alarmMode,
        state.alarm1Speed,
    )
    val reportedValues = mapOf(
        "SET_PEDALS_MODE" to listOfNotNull(state.pedalsMode),
        "SET_ALARM_SPEED" to listOfNotNull(alarmCommandValue),
        "SET_LED_MODE" to listOfNotNull(state.ledMode),
        "SET_LIGHT_MODE" to listOfNotNull(state.lightMode),
    )
    val commands = remember(
        state.commandDescriptors,
        state.commandContextRevision,
        reportedValues,
    ) {
        wheelSettingsCommands(state.commandDescriptors, reportedValues)
    }
    if (commands.isNotEmpty()) {
        Text(stringResource(R.string.wheel_commands_title), modifier = Modifier.padding(vertical = 8.dp))
    }
    commands.forEach { command ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(command.name)
            if (command.description.isNotBlank()) Text(command.description)
            command.reportedValue?.let { value ->
                val label = commandReportedValueLabel(command.input, value)
                if (label != null) {
                    Text(stringResource(R.string.command_current_value, label))
                }
            }
            if (command.conditional) {
                Text(stringResource(R.string.command_conditional))
            }
            if (command.input is CommandInput.Range) {
                Text(stringResource(R.string.command_bounds_warning))
            }
            if (command.actionable) {
                CommandInputControl(
                    command.name,
                    command.description,
                    command.id,
                    command.input,
                    command.reportedValue,
                    viewModel::sendCommand,
                )
            } else {
                val reason = when {
                    command.input is CommandInput.Unsupported -> R.string.command_input_unsupported
                    command.input is CommandInput.RawPayload -> R.string.command_payload_editor_missing
                    command.encoding == CommandEncoding.UNKNOWN -> R.string.command_unknown_context
                    command.encoding == CommandEncoding.UNAVAILABLE -> R.string.command_unavailable_context
                    else -> R.string.command_no_control
                }
                Text(
                    stringResource(reason) + " " + stringResource(R.string.command_not_actionable),
                )
            }
        }
    }
    wheelReportedSettings(state, commands.map { it.id to it.reportedValue }.toMap())
}

@Composable
private fun commandReportedValueLabel(input: CommandInput, value: CommandValue): String? =
    when (input) {
        CommandInput.Boolean -> (value as? CommandValue.Toggle)?.value?.let {
            stringResource(if (it) R.string.on else R.string.off)
        }
        is CommandInput.Choice -> (value as? CommandValue.Integer)?.value?.let { current ->
            val optionId = input.options.firstOrNull { it.value == current }?.id
            if (optionId != null) localizedCommandOptionLabel(optionId) else current.toString()
        }
        is CommandInput.Range -> (value as? CommandValue.Integer)?.let {
            "${it.value} ${input.unit}".trim()
        }
        is CommandInput.Tuple -> (value as? CommandValue.Integers)?.copyValues()?.joinToString()
        else -> null
    }

@Composable
private fun wheelReportedSettings(
    state: BleSessionState,
    commandValues: Map<String, CommandValue?>,
) {
    val alarmCommandInput = state.commandDescriptors
        .firstOrNull { it.id == "SET_ALARM_SPEED" }
        ?.input
    val alarmCommandHasReportedValue = commandValues["SET_ALARM_SPEED"] != null
    val alarmModeSetting = state.alarmMode?.takeIf {
        alarmCommandInput !is CommandInput.Choice || !alarmCommandHasReportedValue
    }?.let {
        ReportedWheelSetting(
            commandId = "SET_ALARM_MODE",
            value = it,
            title = stringResource(R.string.alarm_mode_title),
            input = alarmCommandInput.takeIf { input -> input is CommandInput.Choice },
        )
    }
    val alarmSpeedSetting = state.alarm1Speed?.takeIf {
        alarmCommandInput !is CommandInput.Range || !alarmCommandHasReportedValue
    }?.let {
        ReportedWheelSetting(
            commandId = "SET_ALARM_SPEED",
            value = it,
            title = stringResource(R.string.alert1_title),
            input = alarmCommandInput.takeIf { input -> input is CommandInput.Range },
        )
    }
    val reported = listOfNotNull(
        ReportedWheelSetting(
            commandId = "SET_PEDALS_MODE",
            value = state.pedalsMode,
            title = stringResource(R.string.pedals_mode_title),
            input = state.commandDescriptors.firstOrNull { it.id == "SET_PEDALS_MODE" }?.input,
        ),
        alarmModeSetting,
        alarmSpeedSetting,
        ReportedWheelSetting(
            commandId = "SET_LED_MODE",
            value = state.ledMode,
            title = stringResource(R.string.led_mode_title),
            input = state.commandDescriptors.firstOrNull { it.id == "SET_LED_MODE" }?.input,
        ),
        ReportedWheelSetting(
            commandId = "SET_LIGHT_MODE",
            value = state.lightMode,
            title = stringResource(R.string.light_mode_title),
            input = state.commandDescriptors.firstOrNull { it.id == "SET_LIGHT_MODE" }?.input,
        ),
    )
    reported.forEach { setting ->
        val representedByCommand = when (setting.commandId) {
            "SET_ALARM_MODE" -> alarmCommandInput is CommandInput.Choice &&
                commandValues["SET_ALARM_SPEED"] != null
            "SET_ALARM_SPEED" -> alarmCommandInput is CommandInput.Range &&
                commandValues["SET_ALARM_SPEED"] != null
            else -> commandValues[setting.commandId] != null
        }
        if (setting.value != null && !representedByCommand) {
            val label = setting.input?.let {
                reportedSettingLabel(it, setting.value)
            } ?: setting.value.toString()
            baseSettings(name = setting.title, desc = "", rightContent = { Text(label) })
        }
    }
    state.tiltBackSpeed?.let { speed ->
        baseSettings(
            name = stringResource(R.string.tilt_back_description),
            desc = "",
            rightContent = { Text(speed.toString()) },
        )
    }
    state.autoPowerOffMinutes?.let { minutes ->
        baseSettings(
            name = stringResource(R.string.wheel_auto_power_off),
            desc = "",
            rightContent = { Text(stringResource(R.string.wheel_minutes_value, minutes)) },
        )
    }
}

private data class ReportedWheelSetting(
    val commandId: String,
    val value: Int?,
    val title: String,
    val input: CommandInput?,
)

@Composable
private fun reportedSettingLabel(input: CommandInput, value: Int): String {
    if (input is CommandInput.Choice) {
        val optionId = input.options.firstOrNull { it.value == value }?.id
        if (optionId != null) return localizedCommandOptionLabel(optionId)
    }
    return value.toString()
}

@Composable
private fun localizedCommandOptionLabel(id: String): String =
    commandOptionLabelResource(id)?.let { stringResource(it) } ?: id

@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun CommandInputControl(
    name: String,
    description: String,
    id: String,
    input: CommandInput,
    reportedValue: CommandValue?,
    send: (String, CommandValue) -> Unit,
) {
    var showConfirmation by remember(id, input) { mutableStateOf(false) }
    if (input == CommandInput.None) {
        val requiresConfirmation = id == "POWER_OFF" || id == "CALIBRATE"
        Button(
            onClick = {
                if (requiresConfirmation) showConfirmation = true
                else send(id, CommandValue.None)
            },
        ) { Text(stringResource(R.string.wheel_password_submit)) }
        if (showConfirmation) {
            AlertDialog(
                onDismissRequest = { showConfirmation = false },
                title = { Text(name) },
                text = { Text(description) },
                confirmButton = {
                    TextButton(onClick = {
                        showConfirmation = false
                        send(id, CommandValue.None)
                    }) { Text(stringResource(R.string.wheel_password_submit)) }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmation = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        return
    }

    var showDialog by remember(id, input) { mutableStateOf(false) }
    val values = remember(id, input, reportedValue) {
        mutableStateMapOf<String, String>().apply {
            putAll(commandInputDefaults(input, reportedValue))
        }
    }
    Button(onClick = { showDialog = true }) { Text(stringResource(R.string.command_set_value)) }
    if (showDialog) {
        val value = commandValueFromInputs(input, values.toMap())
        AlertDialog(
            onDismissRequest = {
                values.clear()
                showDialog = false
            },
            title = { Text(name) },
            text = {
                Column {
                    CommandValueFields(input, values)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = value != null,
                    onClick = {
                        try {
                            value?.let { send(id, it) }
                        } finally {
                            values.clear()
                            showDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.wheel_password_submit)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    values.clear()
                    showDialog = false
                }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CommandValueFields(
    input: CommandInput,
    values: MutableMap<String, String>,
    path: String = "value",
) {
    when (input) {
        CommandInput.None -> Unit
        CommandInput.Boolean -> listOf(
            "true" to stringResource(R.string.on),
            "false" to stringResource(R.string.off),
        ).forEach { (value, label) ->
            Row {
                RadioButton(selected = values[path] == value, onClick = { values[path] = value })
                Text(label, modifier = Modifier.padding(top = 12.dp))
            }
        }
        is CommandInput.Range -> {
            Text(
                stringResource(
                    R.string.command_range_details,
                    input.min,
                    input.max,
                    input.step,
                    input.scale.toString(),
                    input.unit,
                ),
            )
            CommandTextField(
                label = path,
                value = values[path].orEmpty(),
                sensitive = false,
                keyboardType = KeyboardType.Number,
                onValueChange = { values[path] = it },
            )
        }
        is CommandInput.Choice -> input.options.forEach { option ->
            Row {
                RadioButton(
                    selected = values[path] == option.id,
                    onClick = { values[path] = option.id },
                )
                Text(localizedCommandOptionLabel(option.id), modifier = Modifier.padding(top = 12.dp))
            }
        }
        is CommandInput.Text -> {
            Text(stringResource(R.string.command_text_length, input.minLength, input.maxLength))
            CommandTextField(
                label = path,
                value = values[path].orEmpty(),
                sensitive = input.sensitive,
                keyboardType = KeyboardType.Text,
                onValueChange = { values[path] = it },
            )
        }
        is CommandInput.Tuple -> input.fields.forEach { field ->
            CommandValueFields(field.input, values, field.id)
        }
        is CommandInput.Record -> input.fields.forEach { field ->
            CommandValueFields(field.input, values, field.id)
        }
        is CommandInput.RawPayload, is CommandInput.Unsupported -> Unit
        else -> Unit
    }
}

@Composable
private fun CommandTextField(
    label: String,
    value: String,
    sensitive: Boolean,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
