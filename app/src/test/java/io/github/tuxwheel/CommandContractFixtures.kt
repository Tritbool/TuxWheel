package io.github.tuxwheel

import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput

internal fun commandDescriptor(
    id: String,
    input: CommandInput = CommandInput.None,
    encoding: CommandEncoding = CommandEncoding.AVAILABLE,
    name: String = id,
    description: String = "Description for $id",
    operation: String? = null,
) = CommandDescriptor(
    id = id,
    name = name,
    description = description,
    input = input,
    encoding = encoding,
    operation = operation,
)
