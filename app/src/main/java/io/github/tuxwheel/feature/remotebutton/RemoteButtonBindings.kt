package io.github.tuxwheel.feature.remotebutton

import java.net.URLDecoder
import java.net.URLEncoder

/** One configured Bluetooth remote button. */
data class RemoteButtonBinding(
    val keyCode: Int,
    val action: RemoteButtonAction,
    /** InputDevice descriptor of the captured remote; null = any device (legacy entry). */
    val deviceId: String? = null,
    val deviceName: String? = null,
)

/**
 * Pure codec for the stored remote-button mappings.
 *
 * The [AppConfig][io.github.tuxwheel.AppConfig] string holds at most [MAX_SLOTS] entries
 * separated by `;`, each `keyCode:ACTION[:encodedDeviceId:encodedDeviceName]`. An empty string
 * means nothing is caught, which is the default. Entries recorded before device capture existed
 * carry no device fields and match any input source.
 */
object RemoteButtonBindings {
    const val MAX_SLOTS = 5
    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"
    private const val REQUIRED_FIELDS = 4
    private const val CHARSET = "UTF-8"

    fun parse(raw: String): List<RemoteButtonBinding> =
        raw.split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val fields = entry.split(FIELD_SEPARATOR, limit = REQUIRED_FIELDS)
                val keyCode = fields.getOrNull(0)?.toIntOrNull()
                val action = fields.getOrNull(1)
                    ?.let { runCatching { RemoteButtonAction.valueOf(it) }.getOrNull() }
                if (keyCode == null || action == null) return@mapNotNull null
                RemoteButtonBinding(
                    keyCode = keyCode,
                    action = action,
                    deviceId = fields.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::decode),
                    deviceName = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let(::decode),
                )
            }
            .take(MAX_SLOTS)

    fun serialize(bindings: List<RemoteButtonBinding>): String =
        bindings.take(MAX_SLOTS).joinToString(ENTRY_SEPARATOR) { binding ->
            listOfNotNull(
                binding.keyCode.toString(),
                binding.action.name,
                binding.deviceId?.let(::encode),
                binding.deviceName?.let(::encode),
            ).joinToString(separator = FIELD_SEPARATOR, limit = REQUIRED_FIELDS)
        }

    fun forKey(bindings: List<RemoteButtonBinding>, keyCode: Int): RemoteButtonBinding? =
        bindings.firstOrNull { it.keyCode == keyCode }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, CHARSET)
    } catch (e: Exception) {
        null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, CHARSET)
}
