package io.github.tuxwheel.ble

import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput

internal fun CommandDescriptor.isActionableContract(): Boolean =
    encoding != CommandEncoding.UNAVAILABLE &&
        encoding != CommandEncoding.UNKNOWN &&
        input !is CommandInput.Unsupported

internal fun CommandInput.isSupportedCommandInput(): Boolean = when (this) {
    CommandInput.None, CommandInput.Boolean -> true
    is CommandInput.Range -> min <= max && step > 0
    is CommandInput.Choice -> options.isNotEmpty()
    is CommandInput.Text -> minLength >= 0 && maxLength >= minLength
    is CommandInput.Tuple -> fields.isNotEmpty() && fields.all {
        when (val fieldInput = it.input) {
            is CommandInput.Range -> fieldInput.min <= fieldInput.max && fieldInput.step > 0
            is CommandInput.Choice -> fieldInput.options.isNotEmpty()
            else -> false
        }
    }
    is CommandInput.Record -> apiType == "PasswordChangeRequest" &&
        fields.map { it.id }.toSet() == setOf("oldPassword", "newPassword") &&
        fields.size == 2 && fields.all {
            (it.input as? CommandInput.Text)?.let { text ->
                text.minLength >= 0 && text.maxLength >= text.minLength
            } == true
        }
    is CommandInput.RawPayload, is CommandInput.Unsupported -> false
    else -> false
}
