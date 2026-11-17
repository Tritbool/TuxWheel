package io.github.tuxwheel.settings

import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.PasswordChangeRequest
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
): List<CommandControlModel> = descriptors
    .asSequence()
    .filter { it.id in wheelSettingsCommandIds }
    .map { it.toControlModel() }
    .filter { it.actionable }
    .toList()

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
