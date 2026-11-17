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
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel

@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun commandContractSettings(viewModel: BleSessionViewModel) {
    val state by viewModel.sessionState.collectAsState()
    val commands = remember(state.commandDescriptors, state.commandContextRevision) {
        wheelSettingsCommands(state.commandDescriptors)
    }
    if (commands.isEmpty()) return

    Text(stringResource(R.string.wheel_commands_title), modifier = Modifier.padding(vertical = 8.dp))
    commands.forEach { command ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(command.name)
            if (command.description.isNotBlank()) Text(command.description)
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
}

@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun CommandInputControl(
    name: String,
    description: String,
    id: String,
    input: CommandInput,
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
    val values = remember(id, input) { mutableStateMapOf<String, String>() }
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
                Text("${option.id} (${option.value})", modifier = Modifier.padding(top = 12.dp))
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
