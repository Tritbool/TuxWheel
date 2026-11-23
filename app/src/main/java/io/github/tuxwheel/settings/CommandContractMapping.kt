package io.github.tuxwheel.settings

import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.PasswordChangeRequest
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.isActionableContract
import io.github.tuxwheel.ble.isSupportedCommandInput

/**
 * Display order of the wheel settings screen.
 *
 * Sections only order and label commands; they are not a filter. Anything the protocol exposes that
 * is not listed here lands in [OTHER], so a new library command still reaches the user instead of
 * disappearing silently.
 */
internal enum class WheelCommandSection(val title: Int, val commandIds: List<String>) {
    PEDALS(
        R.string.wheel_section_pedals,
        listOf("SET_PEDALS_MODE", "SET_PEDAL_ANGLE", "SET_PEDAL_CUTOFF_ANGLE", "SET_PEDAL_PITCH_TRIM"),
    ),
    RIDING(
        R.string.wheel_section_riding,
        listOf("SET_RIDE_MODE", "SET_SPEED_LIMIT", "SET_PWM_LIMIT"),
    ),
    LIGHTING(
        R.string.wheel_section_lighting,
        listOf(
            "SET_LIGHT_MODE", "LIGHT_BRIGHTNESS", "LIGHT_ON", "LIGHT_OFF",
            "SET_LED_MODE", "SET_LED_STROBE", "SET_HIGH_BEAM",
        ),
    ),
    ALARMS(R.string.wheel_section_alarms, listOf("SET_ALARM_SPEED", "SPEAKER_VOLUME", "BEEP")),
    POWER_BATTERY(
        R.string.wheel_section_power_battery,
        listOf("SET_STANDBY_DELAY", "SET_CHARGE_LIMIT"),
    ),
    ACTIONS(R.string.wheel_section_actions, listOf("CALIBRATE", "RESET_TRIP", "POWER_OFF")),
    OTHER(R.string.wheel_section_other, emptyList()),
}

/**
 * Configuration commands that own a dedicated screen, so repeating them here would create two
 * competing editors for one wheel value.
 *
 * `SET_SPEED_LIMIT` is deliberately *not* here even though [io.github.tuxwheel.feature.speedlimiter.SpeedLimiter]
 * writes it too: the wheel's own max speed is a wheel setting the user expects on this screen, and
 * hiding it left KingSong, Gotway and InMotion with no way to set it at all. The limiter collision
 * is handled where the write happens (see `commandContractSettings`).
 */
private val wheelSettingsCommandsWithOwnScreen = setOf(
    "AUTHENTICATE", "SET_PASSWORD", "CHANGE_PASSWORD", "REMOVE_PASSWORD",
    "LOCK", "UNLOCK",
)

private val wheelSettingsSectionByCommandId: Map<String, WheelCommandSection> =
    WheelCommandSection.entries.flatMap { section -> section.commandIds.map { it to section } }.toMap()

internal fun wheelCommandSectionFor(commandId: String): WheelCommandSection =
    wheelSettingsSectionByCommandId[commandId] ?: WheelCommandSection.OTHER

internal data class WheelCommandSectionCommands(
    val section: WheelCommandSection,
    val commands: List<CommandControlModel>,
)

/** Groups commands in [WheelCommandSection] order, dropping empty sections. */
internal fun wheelCommandSections(
    commands: List<CommandControlModel>,
): List<WheelCommandSectionCommands> = WheelCommandSection.entries.mapNotNull { section ->
    val inSection = commands.filter { wheelCommandSectionFor(it.id) == section }
    inSection.takeIf { it.isNotEmpty() }?.let { WheelCommandSectionCommands(section, it) }
}

internal enum class WheelCommandEditor {
    UNAVAILABLE, ACTION, CONFIRM_ACTION, SWITCH, PICK, FORM,
}

internal data class CommandControlModel(
    val id: String,
    val name: String,
    val description: String,
    val input: CommandInput,
    val encoding: CommandEncoding,
    val conditional: Boolean,
    val actionable: Boolean,
    val reportedValue: CommandValue? = null,
) {
    val editor: WheelCommandEditor
        get() = when {
            !actionable -> WheelCommandEditor.UNAVAILABLE
            input == CommandInput.None && (id == "POWER_OFF" || id == "CALIBRATE") ->
                WheelCommandEditor.CONFIRM_ACTION
            input == CommandInput.None -> WheelCommandEditor.ACTION
            input == CommandInput.Boolean && reportedValue is CommandValue.Toggle ->
                WheelCommandEditor.SWITCH
            input == CommandInput.Boolean || input is CommandInput.Choice -> WheelCommandEditor.PICK
            else -> WheelCommandEditor.FORM
        }
}

internal fun CommandDescriptor.toControlModel() = CommandControlModel(
    id = id,
    name = name,
    description = description,
    input = input,
    encoding = encoding,
    conditional = encoding == CommandEncoding.CONDITIONAL,
    actionable = isActionableContract() && input.isSupportedCommandInput(),
)

/**
 * Every command the protocol exposes as user configuration.
 *
 * The screen shows what the wheel offers rather than a hand-picked subset: only non-configuration
 * commands (anything carrying an `operation`, such as firmware or serial queries, raw payloads and
 * diagnostic file transfers) and commands with their own screen are left out.
 */
internal fun wheelSettingsCommands(
    descriptors: List<CommandDescriptor>,
    reportedValues: Map<String, List<Int>> = emptyMap(),
): List<CommandControlModel> = descriptors
    .asSequence()
    .filter { it.operation == null }
    .filterNot { it.id in wheelSettingsCommandsWithOwnScreen }
    .map { descriptor ->
        descriptor.toControlModel().copy(
            reportedValue = commandValueFromReportedValues(
                descriptor.input,
                reportedValues[descriptor.id].orEmpty(),
            ),
        )
    }
    .filter {
        it.actionable ||
            (it.encoding == CommandEncoding.UNAVAILABLE && it.input.isSupportedCommandInput())
    }
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

/**
 * The raw reported-shaped number a written value corresponds to, so it can be remembered.
 *
 * Only the inputs the wheel can also report back have a raw form; tuples and free text are left out
 * because nothing could be read back to compare them against anyway.
 */
internal fun commandValueToReportedInt(value: CommandValue): Int? = when (value) {
    is CommandValue.Integer -> value.value
    is CommandValue.Toggle -> if (value.value) 1 else 0
    else -> null
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

/**
 * Option labels the catalogue publishes as bare codes for one manufacturer only.
 *
 * A code that means something on one wheel means nothing on another, and the library deliberately
 * refuses to settle the meaning of a code it has not seen evidence for, so those labels live here
 * instead of being inferred from a brand that happens to share the number. Each entry is keyed by
 * manufacturer to keep a label established on one wheel from leaking onto the others: the app once
 * corrected Gotway's pedal order in a table shared by every brand and silently reversed KingSong's
 * with it.
 */
private val brandOptionLabels: Map<Triple<String, String, String>, Int> = mapOf(
    // KingSong sends the mode straight through on 0x87 and echoes it back in the 0xA9 frame,
    // with 0 = hard and 2 = soft (WheelLog and DarknessBot both label it that way, and rider
    // feedback confirmed the previous 0 = soft mapping was inverted); Gotway decodes to the
    // opposite order, which is why this cannot be one shared table.
    Triple("kingsong", "SET_PEDALS_MODE", "mode_0") to R.string.hard,
    Triple("kingsong", "SET_PEDALS_MODE", "mode_1") to R.string.medium,
    Triple("kingsong", "SET_PEDALS_MODE", "mode_2") to R.string.soft,
)

internal fun commandOptionLabelResource(
    id: String,
    manufacturer: String? = null,
    commandId: String? = null,
): Int? {
    if (manufacturer != null && commandId != null) {
        val key = Triple(manufacturer.lowercase(), commandId.uppercase(), id.lowercase())
        brandOptionLabels[key]?.let { return it }
    }
    return when (id.lowercase()) {
        "hard" -> R.string.hard
        "high" -> R.string.high
        "low" -> R.string.low
        "medium" -> R.string.medium
        "soft" -> R.string.soft
        "auto", "automatic" -> R.string.auto
        "free" -> R.string.free
        "on" -> R.string.on
        "off" -> R.string.off
        "strobe" -> R.string.strobe
        "led_type1" -> R.string.led_type1
        "led_type2" -> R.string.led_type2
        "led_type3" -> R.string.led_type3
        "led_type4" -> R.string.led_type4
        "led_type5" -> R.string.led_type5
        "led_type6" -> R.string.led_type6
        "led_type7" -> R.string.led_type7
        else -> null
    }
}

/**
 * Turns a catalogue option id into readable text when the app has no label for it.
 *
 * Option ids are machine identifiers, and some protocols publish placeholders instead of words:
 * KingSong exposes its pedal mode as `mode_0`, `mode_1`, `mode_2` because the library refuses to
 * guess what those codes mean. Printing the id verbatim reads as a defect, so ids are spaced and
 * capitalised, and short initialisms are kept upper case so `pwm_tiltback_only` reads as
 * `PWM tiltback only` rather than `Pwm tiltback only`.
 */
internal fun humanizeCommandOptionId(id: String): String {
    val words = id.split('_', '-').filter { it.isNotBlank() }
    if (words.isEmpty()) return id
    return words.mapIndexed { index, word ->
        when {
            word.lowercase() in upperCasedWords -> word.uppercase()
            index == 0 -> word.lowercase().replaceFirstChar { it.titlecase() }
            else -> word.lowercase()
        }
    }.joinToString(" ")
}

private val upperCasedWords = setOf("pwm", "bms", "led", "rgb", "usb", "ip", "id")

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

/**
 * A value the app has written to the wheel and not yet seen come back.
 *
 * @property reportedBefore the value the wheel reported when the write went out. A report that
 * differs from both this and the written value is the wheel overruling the request; a report that
 * has not moved at all only means the wheel has not re-reported yet, which proves nothing.
 */
internal data class CommandPending(
    val value: CommandValue,
    val reportedBefore: CommandValue? = null,
)

internal enum class CommandWriteStatus { NONE, APPLIED, REJECTED }

/**
 * Decides what the settings screen does with the last value it wrote.
 *
 * The library only confirms that a payload was queued, and most wheels never echo a setting back:
 * Kingsong has no read command for its pedal mode at all, and Gotway and ExtremeBull answer a write
 * with a beep rather than a report. Waiting on an acknowledgement would therefore hang for the
 * whole timeout on every write and then claim a change failed when it actually landed, so nothing
 * is ever reported as merely pending. The written value stands as the shown one until the wheel
 * reports either the value asked for, which retires the pending write, or a different value that
 * moved after the write, which means the wheel overruled it.
 */
internal fun commandWriteStatus(
    pending: CommandPending?,
    reportedValue: CommandValue?,
): CommandWriteStatus = when {
    pending == null -> CommandWriteStatus.NONE
    reportedValue == pending.value -> CommandWriteStatus.APPLIED
    reportedValue != null && reportedValue != pending.reportedBefore -> CommandWriteStatus.REJECTED
    else -> CommandWriteStatus.NONE
}
