package io.github.tuxwheel.settings

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.PasswordChangeRequest
import io.github.tuxwheel.R
import io.github.tuxwheel.commandDescriptor
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandContractMappingTest {
    @Test
    fun `reported setting values populate command defaults only when accepted by the contract`() {
        val pedals = commandDescriptor(
            "SET_PEDALS_MODE",
            input = CommandInput.Choice(
                listOf(
                    CommandInput.Option("soft", 1),
                    CommandInput.Option("hard", 2),
                ),
            ),
        )
        val speed = commandDescriptor(
            "SET_LIGHT_MODE",
            input = CommandInput.Range(1, 5, 1, "level"),
        )
        val commands = wheelSettingsCommands(
            listOf(pedals, speed),
            mapOf("SET_PEDALS_MODE" to listOf(2), "SET_LIGHT_MODE" to listOf(7)),
        )

        assertThat(commands.map { it.id }).containsExactly("SET_PEDALS_MODE", "SET_LIGHT_MODE")
        assertThat(commandInputDefaults(commands.first().input, commands.first().reportedValue))
            .containsExactly("value", "hard")
        assertThat(commands.last().reportedValue).isNull()
        assertThat(commandInputDefaults(commands.last().input, commands.last().reportedValue)).isEmpty()
    }

    @Test
    fun `wheel settings fallback values do not create a setter default`() {
        val input = CommandInput.Choice(
            listOf(CommandInput.Option("soft", 1), CommandInput.Option("hard", 2)),
        )

        assertThat(commandValueFromReportedValues(input, listOf(2)))
            .isEqualTo(CommandValue.Integer(2))
        assertThat(commandValueFromReportedValues(input, listOf(3))).isNull()
        assertThat(commandInputDefaults(input, null)).isEmpty()
    }

    @Test
    fun `stable command option IDs resolve to localized labels`() {
        assertThat(commandOptionLabelResource("hard")).isEqualTo(R.string.hard)
        assertThat(commandOptionLabelResource("soft")).isEqualTo(R.string.soft)
        assertThat(commandOptionLabelResource("auto")).isEqualTo(R.string.auto)
        assertThat(commandOptionLabelResource("medium")).isEqualTo(R.string.medium)
        assertThat(commandOptionLabelResource("led_type1")).isEqualTo(R.string.led_type1)
        assertThat(commandOptionLabelResource("HARD")).isEqualTo(R.string.hard)
        assertThat(commandOptionLabelResource("unknown")).isNull()
    }

    @Test
    fun `alarm command value follows the descriptor input rather than another decoded setting`() {
        val choice = CommandInput.Choice(listOf(CommandInput.Option("sport", 1)))
        val range = CommandInput.Range(5, 65, 5, "km/h")

        assertThat(reportedAlarmCommandValue(choice, alarmMode = 1, alarmSpeed = 25)).isEqualTo(1)
        assertThat(reportedAlarmCommandValue(range, alarmMode = 1, alarmSpeed = 25)).isEqualTo(25)
        assertThat(reportedAlarmCommandValue(null, alarmMode = 1, alarmSpeed = 25)).isNull()
    }

    @Test
    fun `wheel settings keeps every configuration command and drops queries and dedicated screens`() {
        val commands = wheelSettingsCommands(
            listOf(
                commandDescriptor("SET_LIGHT_MODE"),
                commandDescriptor("RESET_TRIP"),
                commandDescriptor("SET_SPEED_LIMIT", input = CommandInput.Range(0, 100, 1, "km/h")),
                commandDescriptor("SET_PASSWORD", input = CommandInput.Text(4, 4, "[0-9]{4}", true)),
                commandDescriptor("LOCK"),
                commandDescriptor("REQUEST_FIRMWARE", operation = "query"),
                commandDescriptor("REQUEST_SERIAL", operation = "query"),
                commandDescriptor("REQUEST_DIAGNOSTIC_LOG", operation = "file_transfer"),
                commandDescriptor(
                    "CUSTOM",
                    input = CommandInput.RawPayload("Opaque protocol bytes", setOf("bytes")),
                    operation = "raw",
                ),
                commandDescriptor("SET_PEDALS_MODE", encoding = CommandEncoding.UNAVAILABLE),
                commandDescriptor("SET_LED_MODE", input = CommandInput.Unsupported("Unknown input")),
                commandDescriptor("LIGHT_BRIGHTNESS", encoding = CommandEncoding.UNKNOWN),
                commandDescriptor(
                    "LIGHT_ON",
                    encoding = CommandEncoding.UNAVAILABLE,
                    input = CommandInput.Unsupported("Unknown input"),
                ),
            ),
        )

        assertThat(commands.map { it.id })
            .containsExactly("SET_LIGHT_MODE", "RESET_TRIP", "SET_SPEED_LIMIT", "SET_PEDALS_MODE")
        assertThat(commands.single { it.id == "SET_PEDALS_MODE" }.editor)
            .isEqualTo(WheelCommandEditor.UNAVAILABLE)
    }

    @Test
    fun `the wheel max speed belongs to the wheel settings screen, not to the speed limiter`() {
        val commands = wheelSettingsCommands(
            listOf(commandDescriptor("SET_SPEED_LIMIT", input = CommandInput.Range(0, 99, 1, "km/h"))),
        )

        assertThat(commands.map { it.id }).containsExactly("SET_SPEED_LIMIT")
        assertThat(commands.single().editor).isEqualTo(WheelCommandEditor.FORM)
        assertThat(wheelCommandSectionFor("SET_SPEED_LIMIT"))
            .isEqualTo(WheelCommandSection.RIDING)
    }

    @Test
    fun `a command the app never listed still reaches the settings screen`() {
        val commands = wheelSettingsCommands(
            listOf(commandDescriptor("SET_TILT_BACK_ANGLE", input = CommandInput.Range(0, 30, 1, "deg"))),
        )

        assertThat(commands.map { it.id }).containsExactly("SET_TILT_BACK_ANGLE")
        assertThat(wheelCommandSectionFor("SET_TILT_BACK_ANGLE")).isEqualTo(WheelCommandSection.OTHER)
    }

    @Test
    fun `sections order commands and collect the unlisted ones last`() {
        val commands = wheelSettingsCommands(
            listOf(
                commandDescriptor("POWER_OFF"),
                commandDescriptor("SET_PEDALS_MODE"),
                commandDescriptor("SET_LIGHT_MODE"),
                commandDescriptor("SET_MYSTERY_SETTING"),
            ),
        )

        assertThat(wheelCommandSections(commands).map { it.section }).containsExactly(
            WheelCommandSection.PEDALS,
            WheelCommandSection.LIGHTING,
            WheelCommandSection.ACTIONS,
            WheelCommandSection.OTHER,
        ).inOrder()
        assertThat(wheelCommandSections(commands).last().commands.map { it.id })
            .containsExactly("SET_MYSTERY_SETTING")
    }

    @Test
    fun `empty sections are not rendered`() {
        val commands = wheelSettingsCommands(listOf(commandDescriptor("SET_PEDALS_MODE")))

        assertThat(wheelCommandSections(commands).map { it.section })
            .containsExactly(WheelCommandSection.PEDALS)
    }

    @Test
    fun `a written command holds its value until the wheel confirms or overrules it`() {
        val requested = CommandValue.Integer(2)
        val pending = CommandPending(requested, reportedBefore = CommandValue.Integer(0))

        assertThat(commandWriteStatus(null, requested)).isEqualTo(CommandWriteStatus.NONE)
        // A wheel that never reports the setting back proves nothing, so nothing is claimed.
        assertThat(commandWriteStatus(CommandPending(requested), null))
            .isEqualTo(CommandWriteStatus.NONE)
        // A report that has not moved since the write only means the wheel has not re-reported yet.
        assertThat(commandWriteStatus(pending, CommandValue.Integer(0)))
            .isEqualTo(CommandWriteStatus.NONE)
        assertThat(commandWriteStatus(pending, requested))
            .isEqualTo(CommandWriteStatus.APPLIED)
        assertThat(commandWriteStatus(pending, CommandValue.Integer(1)))
            .isEqualTo(CommandWriteStatus.REJECTED)
    }

    @Test
    fun `placeholder option ids are readable while catalogued ones keep their own label`() {
        assertThat(commandOptionLabelResource("free")).isEqualTo(R.string.free)
        assertThat(humanizeCommandOptionId("mode_0")).isEqualTo("Mode 0")
        assertThat(humanizeCommandOptionId("ride_mode")).isEqualTo("Ride mode")
        assertThat(humanizeCommandOptionId("standby-delay")).isEqualTo("Standby delay")
        assertThat(humanizeCommandOptionId("__")).isEqualTo("__")
    }

    @Test
    fun `kingsong pedal mode codes carry the labels that brand gave them`() {
        assertThat(commandOptionLabelResource("mode_0", "KingSong", "SET_PEDALS_MODE"))
            .isEqualTo(R.string.soft)
        assertThat(commandOptionLabelResource("mode_1", "KingSong", "SET_PEDALS_MODE"))
            .isEqualTo(R.string.medium)
        assertThat(commandOptionLabelResource("mode_2", "KingSong", "SET_PEDALS_MODE"))
            .isEqualTo(R.string.hard)
    }

    @Test
    fun `a pedal mode label never crosses over to another brand or command`() {
        assertThat(commandOptionLabelResource("mode_0", "Gotway", "SET_PEDALS_MODE")).isNull()
        assertThat(commandOptionLabelResource("mode_0", "KingSong", "SET_LIGHT_MODE")).isNull()
        assertThat(commandOptionLabelResource("mode_0", "Unknown", "SET_PEDALS_MODE")).isNull()
        assertThat(commandOptionLabelResource("mode_0")).isNull()
    }

    @Test
    fun `descriptive option ids read as words and initialisms keep their shape`() {
        assertThat(commandOptionLabelResource("strobe", "Gotway", "SET_LIGHT_MODE"))
            .isEqualTo(R.string.strobe)
        assertThat(humanizeCommandOptionId("pwm_tiltback_only")).isEqualTo("PWM tiltback only")
        assertThat(humanizeCommandOptionId("dynamic_pwm_tiltback")).isEqualTo("Dynamic PWM tiltback")
        assertThat(humanizeCommandOptionId("two_speed_alarms_and_pwm_tiltback"))
            .isEqualTo("Two speed alarms and PWM tiltback")
    }

    @Test
    fun `a written value keeps the raw form the wheel would have reported`() {
        assertThat(commandValueToReportedInt(CommandValue.Integer(2))).isEqualTo(2)
        assertThat(commandValueToReportedInt(CommandValue.Toggle(true))).isEqualTo(1)
        assertThat(commandValueToReportedInt(CommandValue.Toggle(false))).isEqualTo(0)
        assertThat(commandValueToReportedInt(CommandValue.Text("abc"))).isNull()
        assertThat(commandValueToReportedInt(CommandValue.None)).isNull()
    }

    @Test
    fun `kingsong pedal mode codes map through the catalogue and stay absent when unreported`() {
        val kingsong = commandDescriptor(
            "SET_PEDALS_MODE",
            input = CommandInput.Choice(
                listOf(
                    CommandInput.Option("mode_0", 0),
                    CommandInput.Option("mode_1", 1),
                    CommandInput.Option("mode_2", 2),
                ),
            ),
        )

        assertThat(commandValueFromReportedValues(kingsong.input, listOf(1)))
            .isEqualTo(CommandValue.Integer(1))
        assertThat(commandInputDefaults(kingsong.input, CommandValue.Integer(1)))
            .containsExactly("value", "mode_1")
        // Kingsong has no read command for its pedal mode, so there is nothing to show.
        assertThat(commandValueFromReportedValues(kingsong.input, emptyList())).isNull()
    }

    @Test
    fun `simple options allow direct selection and numeric edits use a form`() {
        val choice = CommandInput.Choice(listOf(CommandInput.Option("soft", 1)))
        val commands = wheelSettingsCommands(
            listOf(
                commandDescriptor("SET_PEDALS_MODE", input = choice),
                commandDescriptor("SET_ALARM_SPEED", input = choice),
                commandDescriptor("LIGHT_BRIGHTNESS", input = CommandInput.Range(0, 5, 1, "level")),
            ),
        )

        assertThat(commands.map { it.editor })
            .containsExactly(WheelCommandEditor.PICK, WheelCommandEditor.PICK, WheelCommandEditor.FORM)
            .inOrder()
    }

    @Test
    fun `a boolean uses a switch only if the wheel reports its state`() {
        val descriptors = listOf(commandDescriptor("SET_LIGHT_MODE", input = CommandInput.Boolean))

        assertThat(wheelSettingsCommands(descriptors).single().editor)
            .isEqualTo(WheelCommandEditor.PICK)
        val reportedOff = wheelSettingsCommands(descriptors, mapOf("SET_LIGHT_MODE" to listOf(0))).single()
        assertThat(reportedOff.reportedValue).isEqualTo(CommandValue.Toggle(false))
        assertThat(reportedOff.editor).isEqualTo(WheelCommandEditor.SWITCH)
    }

    @Test
    fun `power off and calibration retain confirmation`() {
        val commands = wheelSettingsCommands(
            listOf(commandDescriptor("POWER_OFF"), commandDescriptor("CALIBRATE"), commandDescriptor("LIGHT_ON")),
        )

        assertThat(commands.map { it.editor }).containsExactly(
            WheelCommandEditor.CONFIRM_ACTION,
            WheelCommandEditor.CONFIRM_ACTION,
            WheelCommandEditor.ACTION,
        ).inOrder()
    }

    @Test
    fun `wheel settings retain conditional commands`() {
        val commands = wheelSettingsCommands(
            listOf(commandDescriptor("SET_LIGHT_MODE", encoding = CommandEncoding.CONDITIONAL)),
        )

        assertThat(commands.map { it.id }).containsExactly("SET_LIGHT_MODE")
        assertThat(commands.single().conditional).isTrue()
    }

    @Test
    fun `descriptor mapping preserves metadata and permits conditional commands`() {
        val range = CommandInput.Range(5, 65, 5, "km/h", scale = 0.1)
        val descriptor = commandDescriptor(
            id = "SET_SPEED_LIMIT",
            name = "Speed limit",
            description = "Limit wheel speed",
            input = range,
            encoding = CommandEncoding.CONDITIONAL,
        )

        val model = descriptor.toControlModel()

        assertThat(model.id).isEqualTo("SET_SPEED_LIMIT")
        assertThat(model.name).isEqualTo("Speed limit")
        assertThat(model.description).isEqualTo("Limit wheel speed")
        assertThat(model.input).isEqualTo(range)
        assertThat(model.conditional).isTrue()
        assertThat(model.actionable).isTrue()
    }

    @Test
    fun `unavailable unknown unsupported and raw payload inputs are never actionable`() {
        val descriptors = listOf(
            commandDescriptor("A", encoding = CommandEncoding.UNAVAILABLE),
            commandDescriptor("B", encoding = CommandEncoding.UNKNOWN),
            commandDescriptor("C", input = CommandInput.Unsupported("Unknown kind")),
            commandDescriptor(
                "D",
                input = CommandInput.RawPayload("Opaque protocol bytes", setOf("bytes")),
            ),
        )

        assertThat(descriptors.map { it.toControlModel().actionable })
            .containsExactly(false, false, false, false)
    }

    @Test
    fun `library rejects empty choices and zero range steps before mapping`() {
        assertThrows(IllegalArgumentException::class.java) { CommandInput.Choice(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { CommandInput.Range(1, 9, 0, "units") }
    }

    @Test
    fun `converts no-value boolean range choice and text values without guessing`() {
        val choice = CommandInput.Choice(
            listOf(
                CommandInput.Option("on", 1),
                CommandInput.Option("auto", 4),
            ),
        )
        val text = CommandInput.Text(4, 4, "[0-9]{4}", sensitive = true)

        assertThat(commandValueFromInputs(CommandInput.None, emptyMap()))
            .isEqualTo(CommandValue.None)
        assertThat(commandValueFromInputs(CommandInput.Boolean, mapOf("value" to "true")))
            .isEqualTo(CommandValue.Toggle(true))
        assertThat(commandValueFromInputs(CommandInput.Boolean, emptyMap())).isNull()
        assertThat(commandValueFromInputs(CommandInput.Range(1, 9, 2, "units"), mapOf("value" to "5")))
            .isEqualTo(CommandValue.Integer(5))
        assertThat(commandValueFromInputs(CommandInput.Range(1, 9, 2, "units"), mapOf("value" to "4")))
            .isNull()
        assertThat(commandValueFromInputs(CommandInput.Range(1, 9, 2, "units"), mapOf("value" to "11")))
            .isNull()
        assertThat(commandValueFromInputs(choice, mapOf("value" to "auto")))
            .isEqualTo(CommandValue.Integer(4))
        assertThat(commandValueFromInputs(choice, mapOf("value" to "4"))).isNull()
        assertThat(commandValueFromInputs(text, mapOf("value" to "0012")))
            .isEqualTo(CommandValue.Text("0012"))
        assertThat(commandValueFromInputs(text, mapOf("value" to "12"))).isNull()
    }

    @Test
    fun `converts tuple and known record into the library typed values`() {
        val tuple = CommandInput.Tuple(
            "IntArray",
            listOf(
                CommandInput.Field("first", CommandInput.Range(0, 100, 5, "km/h")),
                CommandInput.Field("second", CommandInput.Range(0, 100, 5, "km/h")),
            ),
        )
        val tupleValue = commandValueFromInputs(
            tuple,
            mapOf("first" to "15", "second" to "30"),
        )
        assertThat(tupleValue).isEqualTo(CommandValue.Integers(listOf(15, 30)))
        assertThat(commandValueFromInputs(tuple, mapOf("first" to "15"))).isNull()

        val record = CommandInput.Record(
            "PasswordChangeRequest",
            listOf(
                CommandInput.Field("oldPassword", CommandInput.Text(4, 4, "[0-9]{4}", true)),
                CommandInput.Field("newPassword", CommandInput.Text(4, 4, "[0-9]{4}", true)),
            ),
        )
        val recordValue = commandValueFromInputs(
            record,
            mapOf("oldPassword" to "0012", "newPassword" to "0034"),
        )

        assertThat(recordValue).isInstanceOf(PasswordChangeRequest::class.java)
        assertThat(recordValue?.toString()).contains("<redacted>")
        assertThat(commandValueFromInputs(
            record,
            mapOf("oldPassword" to "12", "newPassword" to "0034"),
        )).isNull()
    }
}
