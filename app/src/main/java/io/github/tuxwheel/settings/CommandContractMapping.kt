package io.github.tuxwheel.settings

import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.PasswordChangeRequest
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.isActionableContract
import io.github.tuxwheel.ble.isSupportedCommandInput

private val wheelSettingsCommandIds = setOf(
    "LIGHT_ON",
    "LIGHT_OFF",
    "SET_LIGHT_MODE",
    "LIGHT_BRIGHTNESS",
    "SPEAKER_VOLUME",
    "SET_PEDALS_MODE",
    "SET_ALARM_SPEED",
    "SET_LED_MODE",
    "POWER_OFF",
    "CALIBRATE",
    "RESET_TRIP",
)

internal data class CommandControlModel(
    val id: String,
    val name: String,
    val description: String,
    val input: CommandInput,
    val encoding: CommandEncoding,
    val conditional: Boolean,
    val actionable: Boolean,
    val reportedValue: CommandValue? = null,
)

internal fun CommandDescriptor.toControlModel() = CommandControlModel(
    id = id,
    name = name,
    description = description,
    input = input,
    encoding = encoding,
    conditional = encoding == CommandEncoding.CONDITIONAL,
    actionable = isActionableContract() && input.isSupportedCommandInput(),
)

internal fun wheelSettingsCommands(
    descriptors: List<CommandDescriptor>,
    reportedValues: Map<String, List<Int>> = emptyMap(),
): List<CommandControlModel> = descriptors
    .asSequence()
    .filter { it.id in wheelSettingsCommandIds }
    .map { descriptor ->
        descriptor.toControlModel().copy(
            reportedValue = commandValueFromReportedValues(
                descriptor.input,
                reportedValues[descriptor.id].orEmpty(),
            ),
        )
    }
    .filter { it.actionable }
    .toList()

internal fun commandValueFromReportedValues(
    input: CommandInput,
    values: List<Int>,
): CommandValue? {
    val value = when (input) {
        CommandInput.Boolean -> values.singleOrNull()?.let { when (it) {
            0 -> CommandValue.Toggle(false)
            1 -> CommandValue.Toggle(true)
            else -> null
        } }
        is CommandInput.Range, is CommandInput.Choice ->
            values.singleOrNull()?.let(CommandValue::Integer)
        is CommandInput.Tuple ->
            if (values.size == input.fields.size) CommandValue.Integers(values) else null
        CommandInput.None, is CommandInput.Text, is CommandInput.Record,
        is CommandInput.RawPayload, is CommandInput.Unsupported -> null
        else -> null
    } ?: return null
    return value.takeIf { input.validationError(it) == null }
}

internal fun reportedAlarmCommandValue(
    input: CommandInput?,
    alarmMode: Int?,
    alarmSpeed: Int?,
): Int? = when (input) {
    is CommandInput.Choice -> alarmMode
    is CommandInput.Range -> alarmSpeed
    else -> null
}

internal fun commandInputDefaults(
    input: CommandInput,
    value: CommandValue?,
): Map<String, String> {
    if (value == null) return emptyMap()
    return when (input) {
        CommandInput.Boolean -> (value as? CommandValue.Toggle)?.let {
            mapOf("value" to it.value.toString())
        }.orEmpty()
        is CommandInput.Range -> (value as? CommandValue.Integer)?.let {
            mapOf("value" to it.value.toString())
        }.orEmpty()
        is CommandInput.Choice -> (value as? CommandValue.Integer)?.let { current ->
            input.options.firstOrNull { it.value == current.value }?.let {
                mapOf("value" to it.id)
            }
        }.orEmpty()
        is CommandInput.Tuple -> (value as? CommandValue.Integers)?.copyValues()
            ?.takeIf { it.size == input.fields.size }
            ?.let { fields -> input.fields.mapIndexed { index, field ->
                field.id to fields[index].toString()
            }.toMap() }
            .orEmpty()
        CommandInput.None, is CommandInput.Text, is CommandInput.Record,
        is CommandInput.RawPayload, is CommandInput.Unsupported -> emptyMap()
        else -> emptyMap()
    } as Map<String, String>
}

internal fun commandOptionLabelResource(id: String): Int? = when (id.lowercase()) {
    "hard" -> R.string.hard
    "high" -> R.string.high
    "low" -> R.string.low
    "medium" -> R.string.medium
    "soft" -> R.string.soft
    "auto", "automatic" -> R.string.auto
    "on" -> R.string.on
    "off" -> R.string.off
    "led_type1" -> R.string.led_type1
    "led_type2" -> R.string.led_type2
    "led_type3" -> R.string.led_type3
    "led_type4" -> R.string.led_type4
    "led_type5" -> R.string.led_type5
    "led_type6" -> R.string.led_type6
    "led_type7" -> R.string.led_type7
    else -> null
}

internal fun commandValueFromInputs(
    input: CommandInput,
    values: Map<String, String>,
): CommandValue? {
    val result = when (input) {
        CommandInput.None -> CommandValue.None
        CommandInput.Boolean -> values["value"]?.toBooleanStrictOrNull()?.let(CommandValue::Toggle)
        is CommandInput.Range ->
            if (input.min <= input.max && input.step > 0) {
                values["value"]?.toIntOrNull()?.let(CommandValue::Integer)
            }
            else null
        is CommandInput.Choice -> values["value"]?.let { id ->
            input.options.firstOrNull { it.id == id }?.let { CommandValue.Integer(it.value) }
        }
        is CommandInput.Text -> values["value"]?.let(CommandValue::Text)
        is CommandInput.Tuple -> {
            val fields = input.fields.map { field ->
                val value = commandValueFromInputs(field.input, valuesForField(values, field.id))
                    as? CommandValue.Integer ?: return null
                value.value
            }
            CommandValue.Integers(fields)
        }
        is CommandInput.Record -> {
            if (input.apiType != "PasswordChangeRequest") return null
            val fields = input.fields.associate { field ->
                field.id to (commandValueFromInputs(field.input, valuesForField(values, field.id))
                    ?: return null)
            }
            if (fields.keys != setOf("oldPassword", "newPassword") ||
                fields.values.any { it !is CommandValue.Text }
            ) return null
            val request = PasswordChangeRequest(
                oldPassword = (fields.getValue("oldPassword") as CommandValue.Text).value,
                newPassword = (fields.getValue("newPassword") as CommandValue.Text).value,
            )
            request
        }
        is CommandInput.RawPayload, is CommandInput.Unsupported -> return null
        else -> return null
    } ?: return null
    return result.takeIf { input.validationError(it) == null }
}

private fun valuesForField(values: Map<String, String>, id: String): Map<String, String> =
    values.filterKeys { it == id }.mapKeys { "value" }
