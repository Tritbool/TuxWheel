package io.github.tuxwheel.settings

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel

@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun commandContractSettings(
    viewModel: BleSessionViewModel,
    appConfig: AppConfig,
) {
    val state by viewModel.sessionState.collectAsState()
    if (!state.isConnected) return

    val alarmCommandInput = state.commandDescriptors
        .firstOrNull { it.id == "SET_ALARM_SPEED" }
        ?.input
    val alarmCommandValue = reportedAlarmCommandValue(
        alarmCommandInput,
        state.alarmMode,
        state.alarm1Speed,
        state.alarm2Speed,
        state.alarm3Speed,
    )
    // The speed limiter writes this very same wheel register. While it is on the wheel holds the
    // limiter speed and the user's own max speed is parked for the restore, so the max-speed row
    // edits that parked value instead of overwriting the limiter behind its back.
    var limiterSavedMaxSpeed by remember(state.deviceAddress) {
        mutableIntStateOf(appConfig.speedLimiterSavedMaxSpeedFor(state.deviceAddress))
    }
    val speedLimiterHoldsTheWheel = appConfig.speedLimiterEnabledFor(state.deviceAddress)
    val reportedValues = mapOf(
        "SET_PEDALS_MODE" to listOfNotNull(state.pedalsMode),
        "SET_ALARM_SPEED" to alarmCommandValue.orEmpty(),
        "SET_LED_MODE" to listOfNotNull(state.ledMode),
        "SET_LIGHT_MODE" to listOfNotNull(state.lightMode),
        // KingSong answers its configured max speed in the settings frame, Gotway in the tiltback
        // field and the Ninebot Z in its own speed-limit field; wheels that answer nothing fall
        // back to the last value written below.
        "SET_SPEED_LIMIT" to listOfNotNull(
            if (speedLimiterHoldsTheWheel) limiterSavedMaxSpeed
            else state.wheelMaxSpeed ?: state.tiltBackSpeed ?: state.speedLimit?.toInt()
        ),
        // Every row below is reported by the wheel itself and lands in EUCData:
        // the InMotion V9 status page (ride mode, pedal sensitivity and angle, speaker volume,
        // mute, AVAS, DRL, speed units) and the KingSong alarm tuple.
        "SET_RIDE_MODE" to listOfNotNull(state.rideModeOffroad?.asReportedInt()),
        "SET_PEDAL_SENSITIVITY" to listOfNotNull(state.pedalSensitivityPercent),
        "SET_PEDAL_ANGLE" to listOfNotNull(state.pedalMountingAngleTenths),
        "SPEAKER_VOLUME" to listOfNotNull(state.speakerVolumePercent),
        "SET_MUTE" to listOfNotNull(state.speakerMuted.asReportedInt()),
        "SET_AVAS" to listOfNotNull(state.avasEnabled.asReportedInt()),
        "SET_DRL" to listOfNotNull(state.daytimeRunningLight.asReportedInt()),
        "SET_IMPERIAL_UNITS" to listOfNotNull(state.usesMiles.asReportedInt()),
    )
    // Wheels that never report a setting back leave the screen with nothing to show, so the last
    // value written to this wheel is kept and stands in for the missing report.
    val writtenValues = remember(state.deviceAddress) {
        mutableStateMapOf<String, Int>().apply { putAll(appConfig.wheelCommandValues(state.deviceAddress)) }
    }
    val writeCommand: (String, CommandValue) -> Unit = { commandId, value ->
        val speedLimit = commandId == "SET_SPEED_LIMIT"
        val written = (value as? CommandValue.Integer)?.value
        if (speedLimit && speedLimiterHoldsTheWheel) {
            if (written != null) {
                limiterSavedMaxSpeed = written
                appConfig.setSpeedLimiterSavedMaxSpeedFor(state.deviceAddress, written)
            }
        } else {
            commandValueToReportedInt(value)?.let {
                writtenValues[commandId] = it
                appConfig.setWheelCommandValue(state.deviceAddress, commandId, it)
            }
            if (speedLimit && written != null) appConfig.wheelMaxSpeed = written
            viewModel.sendCommand(commandId, value)
        }
    }
    val commands = remember(
        state.commandDescriptors,
        state.commandContextRevision,
        reportedValues,
    ) {
        wheelSettingsCommands(state.commandDescriptors, reportedValues)
    }
    val commandsById = commands.associateBy { it.id }
    key(state.deviceAddress, state.commandContextRevision) {
        wheelCommandSections(commands).forEach { (section, sectionCommands) ->
            WheelSectionHeading(stringResource(section.title))
            sectionCommands.forEach { command ->
                key(command.id) {
                    WheelCommandRow(
                        command,
                        reportedValues[command.id]?.singleOrNull(),
                        writtenValues[command.id],
                        state.deviceManufacturer,
                        writeCommand,
                    )
                }
            }
        }
        if (commands.any { it.actionable && it.conditional }) {
            WheelSectionNote(stringResource(R.string.wheel_command_conditional_note))
        }
    }
    val visibleValueCommandIds = commandsById.filterValues { it.input != CommandInput.None }.keys
    // Gotway answers its max speed in the tiltback field, so once the editable max-speed row
    // carries it the read-only tiltback line would just repeat the same number under another name.
    val speedLimitRowShowsAValue = "SET_SPEED_LIMIT" in visibleValueCommandIds &&
        reportedValues.getValue("SET_SPEED_LIMIT").isNotEmpty()
    wheelReportedSettings(
        state,
        visibleValueCommandIds,
        alarmCommandInput,
        speedLimitRowShowsAValue,
    )
}

@Composable
internal fun WheelSectionHeading(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 16.dp)
            .semantics { heading() },
    )
}

/** Small muted explanation shown under a [WheelSectionHeading]. */
@Composable
internal fun WheelSectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun WheelSettingRow(
    title: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    availability: String? = null,
    availabilityIsError: Boolean = false,
    opensEditor: Boolean = false,
    isAction: Boolean = false,
    control: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isAction) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (availability != null) {
                Text(
                    text = availability,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (availabilityIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.65f),
            )
        }
        control?.invoke()
        if (opensEditor) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun commandReportedValueLabel(
    input: CommandInput,
    value: CommandValue,
    manufacturer: String,
    commandId: String,
): String? =
    when (input) {
        CommandInput.Boolean -> (value as? CommandValue.Toggle)?.value?.let {
            stringResource(if (it) R.string.on else R.string.off)
        }
        is CommandInput.Choice -> (value as? CommandValue.Integer)?.value?.let { current ->
            val optionId = input.options.firstOrNull { it.value == current }?.id
            if (optionId != null) localizedCommandOptionLabel(optionId, manufacturer, commandId)
            else current.toString()
        }
        is CommandInput.Range -> (value as? CommandValue.Integer)?.let {
            // A range expressed in tenths of a degree (the InMotion pedal angle) reads as
            // its decimal value; "10 0.1 degree" made the user believe the angle was wrong.
            if (input.unit == TENTHS_DEGREE_UNIT) {
                "${"%.1f".format(it.value / 10.0)} ${stringResource(R.string.unit_degree)}"
            } else {
                "${it.value} ${input.unit}".trim()
            }
        }
        is CommandInput.Tuple -> (value as? CommandValue.Integers)?.copyValues()?.joinToString()
        else -> null
    }

@Composable
private fun wheelReportedSettings(
    state: BleSessionState,
    visibleCommandIds: Set<String>,
    alarmCommandInput: CommandInput?,
    speedLimitRowShowsAValue: Boolean,
) {
    val hasAlarmControl = "SET_ALARM_SPEED" in visibleCommandIds
    val rows = buildList {
        listOf(
            Triple("SET_PEDALS_MODE", R.string.wheel_pedals_firmness, state.pedalsMode),
            Triple("SET_LED_MODE", R.string.led_mode_title, state.ledMode),
            Triple("SET_LIGHT_MODE", R.string.light_mode_title, state.lightMode),
        ).forEach { (commandId, title, value) ->
            if (value != null && commandId !in visibleCommandIds) {
                val input = state.commandDescriptors.firstOrNull { it.id == commandId }?.input
                add(stringResource(title) to reportedSettingLabel(input, value, state.deviceManufacturer, commandId))
            }
        }
        state.alarmMode?.let { mode ->
            if (!hasAlarmControl || alarmCommandInput !is CommandInput.Choice) {
                add(
                    stringResource(R.string.alarm_mode_title) to
                        reportedSettingLabel(alarmCommandInput, mode, state.deviceManufacturer, "SET_ALARM_SPEED"),
                )
            }
        }
        state.alarm1Speed?.let { speed ->
            if (!hasAlarmControl || alarmCommandInput !is CommandInput.Range) {
                add(stringResource(R.string.alert1_title) to speed.toString())
            }
        }
        state.rollAngleMode?.let { mode ->
            val label = when (mode) {
                0 -> R.string.low
                1 -> R.string.medium
                else -> R.string.high
            }
            add(stringResource(R.string.roll_angle_title) to stringResource(label))
        }
        state.usesMiles?.let { usesMiles ->
            add(
                stringResource(R.string.wheel_controller_uses_miles) to
                    stringResource(if (usesMiles) R.string.on else R.string.off),
            )
        }
        state.tiltBackSpeed?.let { speed ->
            if (!speedLimitRowShowsAValue) {
                add(stringResource(R.string.tilt_back_description) to speed.toString())
            }
        }
        state.autoPowerOffMinutes?.let { minutes ->
            add(
                stringResource(R.string.wheel_auto_power_off) to
                    stringResource(R.string.wheel_minutes_value, minutes),
            )
        }
    }
    if (rows.isNotEmpty()) {
        WheelSectionHeading(stringResource(R.string.wheel_section_reported))
        rows.forEach { (title, value) -> WheelSettingRow(title, value) }
    }
}

@Composable
private fun reportedSettingLabel(
    input: CommandInput?,
    value: Int,
    manufacturer: String,
    commandId: String,
): String {
    if (input is CommandInput.Choice) {
        val optionId = input.options.firstOrNull { it.value == value }?.id
        if (optionId != null) return localizedCommandOptionLabel(optionId, manufacturer, commandId)
    }
    if (input is CommandInput.Range && input.unit == TENTHS_DEGREE_UNIT) {
        return "%.1f".format(value / 10.0) + " " + stringResource(R.string.unit_degree)
    }
    return value.toString()
}

/** The library-wide unit string for ranges denominated in tenths of a degree. */
private const val TENTHS_DEGREE_UNIT = "0.1 degree"

@Composable
private fun localizedCommandOptionLabel(
    id: String,
    manufacturer: String? = null,
    commandId: String? = null,
): String =
    commandOptionLabelResource(id, manufacturer, commandId)?.let { stringResource(it) }
        ?: humanizeCommandOptionId(id)

@Composable
private fun WheelCommandRow(
    command: CommandControlModel,
    rawReportedValue: Int?,
    lastWrittenValue: Int?,
    manufacturer: String,
    send: (String, CommandValue) -> Unit,
) {
    val title = wheelCommandTitle(command)
    val editor = command.editor
    // A wheel that never reports this command back leaves the app holding only its own last write,
    // which is the closest thing to a current value there is.
    val remembered = lastWrittenValue?.let {
        commandValueFromReportedValues(command.input, listOf(it))
    }
    val known = command.reportedValue ?: remembered
    var pending by remember(command.id) { mutableStateOf<CommandPending?>(null) }
    var rejected by remember(command.id) { mutableStateOf(false) }
    val writeStatus = commandWriteStatus(pending, command.reportedValue)

    // The wheel echoing the written value back is the only acknowledgement the library offers, so
    // the optimistic value simply stands until it either agrees or reports something it moved to.
    LaunchedEffect(writeStatus) {
        when (writeStatus) {
            CommandWriteStatus.APPLIED -> pending = null
            CommandWriteStatus.REJECTED -> {
                pending = null
                rejected = true
            }
            CommandWriteStatus.NONE -> Unit
        }
    }

    val feedback = if (rejected) stringResource(R.string.wheel_command_rejected) else null
    val write: (CommandValue) -> Unit = { written ->
        rejected = false
        pending = written.takeIf { it != known }
            ?.let { CommandPending(it, reportedBefore = command.reportedValue) }
        send(command.id, written)
    }

    // Show what the user last asked for rather than what the wheel has admitted to so far.
    val shown = command.copy(reportedValue = pending?.value ?: known)
    val value = if (shown.input != CommandInput.None) {
        shown.reportedValue?.let { commandReportedValueLabel(shown.input, it, manufacturer, command.id) }
            ?: rawReportedValue?.let { reportedSettingLabel(shown.input, it, manufacturer, command.id) }
            ?: stringResource(R.string.wheel_command_not_reported)
    } else null
    if (editor == WheelCommandEditor.UNAVAILABLE) {
        WheelSettingRow(
            title = title,
            value = value,
            availability = stringResource(R.string.command_unavailable_context),
        )
        return
    }
    val toggle = shown.reportedValue as? CommandValue.Toggle
    if (editor == WheelCommandEditor.SWITCH && toggle != null) {
        WheelSettingRow(
            title = title,
            availability = feedback,
            availabilityIsError = rejected,
            modifier = Modifier.toggleable(
                value = toggle.value,
                role = Role.Switch,
                onValueChange = { write(CommandValue.Toggle(it)) },
            ),
            control = { Switch(checked = toggle.value, onCheckedChange = null) },
        )
        return
    }

    var open by remember(command.input, editor) { mutableStateOf(false) }
    val dismiss = { open = false }
    val submit: (CommandValue) -> Unit = {
        dismiss()
        write(it)
    }
    WheelSettingRow(
        title = title,
        value = value,
        availability = feedback,
        availabilityIsError = rejected,
        opensEditor = editor != WheelCommandEditor.ACTION,
        isAction = command.input == CommandInput.None,
        modifier = Modifier.clickable(role = Role.Button) {
            if (editor == WheelCommandEditor.ACTION) submit(CommandValue.None)
            else open = true
        },
    )
    if (open) {
        when (editor) {
            WheelCommandEditor.PICK -> WheelCommandPicker(shown, title, manufacturer, dismiss, submit)
            WheelCommandEditor.FORM -> WheelCommandForm(shown, title, manufacturer, dismiss, submit)
            WheelCommandEditor.CONFIRM_ACTION -> AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(title) },
                text = {
                    Text(
                        if (command.id == "POWER_OFF") stringResource(R.string.power_off_message)
                        else command.description.ifBlank {
                            stringResource(R.string.wheel_command_confirm_calibration)
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { submit(CommandValue.None) }) {
                        Text(stringResource(R.string.wheel_command_confirm))
                    }
                },
                dismissButton = { WheelCommandCancel(dismiss) },
            )
            else -> Unit
        }
    }
}

@Composable
private fun wheelCommandTitle(command: CommandControlModel): String {
    val resource = when (command.id) {
        "SET_PEDALS_MODE" -> R.string.wheel_pedals_firmness
        "SET_PEDAL_CUTOFF_ANGLE" -> R.string.wheel_pedal_cutoff_angle_title
        "SET_PEDAL_PITCH_TRIM" -> R.string.wheel_pedal_pitch_trim_title
        "SET_LIGHT_MODE" -> R.string.light_mode_title
        "LIGHT_BRIGHTNESS" -> R.string.light_brightness_title
        "SET_LED_MODE" -> R.string.led_mode_title
        "LIGHT_ON" -> R.string.wheel_command_light_on
        "LIGHT_OFF" -> R.string.wheel_command_light_off
        "SET_SPEED_LIMIT" -> R.string.max_speed_title
        "SET_ALARM_SPEED" -> if (command.input is CommandInput.Choice) R.string.alarm_mode_title
            else R.string.alert1_title
        "SPEAKER_VOLUME" -> R.string.speaker_volume_title
        "CALIBRATE" -> R.string.wheel_calibration
        "RESET_TRIP" -> R.string.reset_trip
        "POWER_OFF" -> R.string.power_off
        else -> null
    }
    return resource?.let { stringResource(it) } ?: command.name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WheelCommandPicker(
    command: CommandControlModel,
    title: String,
    manufacturer: String,
    dismiss: () -> Unit,
    submit: (CommandValue) -> Unit,
) {
    val input = command.input
    val options = when (input) {
        is CommandInput.Choice -> input.options.mapNotNull { option ->
            commandValueFromInputs(input, mapOf("value" to option.id))?.let {
                localizedCommandOptionLabel(option.id, manufacturer, command.id) to it
            }
        }
        CommandInput.Boolean -> listOf(
            stringResource(R.string.on) to CommandValue.Toggle(true),
            stringResource(R.string.off) to CommandValue.Toggle(false),
        )
        else -> return
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (command.description.isNotBlank()) {
                Text(command.description, style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.selectableGroup()) {
                options.forEach { (label, value) ->
                    CommandOptionRow(label, value == command.reportedValue) {
                        if (value == command.reportedValue) dismiss() else submit(value)
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelCommandForm(
    command: CommandControlModel,
    title: String,
    manufacturer: String,
    dismiss: () -> Unit,
    submit: (CommandValue) -> Unit,
) {
    val input = command.input
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(commandInputDefaults(input, command.reportedValue))
        }
    }
    val value = commandValueFromInputs(input, values.toMap())
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (command.description.isNotBlank()) Text(command.description)
                CommandValueFields(input, values, manufacturer, command.id)
                if (input is CommandInput.Range ||
                    (input is CommandInput.Tuple && input.fields.any { it.input is CommandInput.Range })
                ) {
                    Text(
                        stringResource(R.string.command_bounds_warning),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(submit) }, enabled = value != null) {
                Text(stringResource(R.string.wheel_command_apply))
            }
        },
        dismissButton = { WheelCommandCancel(dismiss) },
    )
}

@Composable
private fun WheelCommandCancel(dismiss: () -> Unit) {
    TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
}

@Composable
private fun CommandOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CommandValueFields(
    input: CommandInput,
    values: MutableMap<String, String>,
    manufacturer: String,
    commandId: String,
    path: String = "value",
    label: String = stringResource(R.string.wheel_command_value),
) {
    when (input) {
        CommandInput.None -> Unit
        CommandInput.Boolean -> Column(Modifier.selectableGroup()) {
            listOf("true" to R.string.on, "false" to R.string.off).forEach { (value, resource) ->
                CommandOptionRow(stringResource(resource), values[path] == value) { values[path] = value }
            }
        }
        is CommandInput.Range -> {
            CommandTextField(
                label = label,
                value = values[path].orEmpty(),
                sensitive = false,
                keyboardType = KeyboardType.Number,
                supportingText = stringResource(
                    R.string.command_range_details,
                    input.min,
                    input.max,
                    input.step,
                    input.scale.toString(),
                    input.unit,
                ),
                onValueChange = { values[path] = it },
            )
        }
        is CommandInput.Choice -> Column(Modifier.selectableGroup()) {
            input.options.forEach { option ->
                CommandOptionRow(
                    localizedCommandOptionLabel(option.id, manufacturer, commandId),
                    values[path] == option.id,
                ) {
                    values[path] = option.id
                }
            }
        }
        is CommandInput.Text -> {
            CommandTextField(
                label = label,
                value = values[path].orEmpty(),
                sensitive = input.sensitive,
                keyboardType = KeyboardType.Text,
                supportingText = stringResource(R.string.command_text_length, input.minLength, input.maxLength),
                onValueChange = { values[path] = it },
            )
        }
        is CommandInput.Tuple -> input.fields.forEach { field ->
            CommandValueFields(field.input, values, manufacturer, commandId, field.id, field.id)
        }
        is CommandInput.Record -> input.fields.forEach { field ->
            CommandValueFields(field.input, values, manufacturer, commandId, field.id, field.id)
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
    supportingText: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
