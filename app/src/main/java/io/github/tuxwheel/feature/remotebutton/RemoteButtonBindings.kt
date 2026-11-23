package io.github.tuxwheel.feature.remotebutton

/**
 * Pure codec for the stored remote-button mappings.
 *
 * The [AppConfig][io.github.tuxwheel.AppConfig] string holds at most [MAX_SLOTS] entries in the
 * form `keyCode:ACTION` separated by `;`, e.g. `24:ToggleSpeedLimiter;87:Beep`. An empty string
 * means nothing is caught, which is the default.
 */
object RemoteButtonBindings {
    const val MAX_SLOTS = 5
    private const val ENTRY_SEPARATOR = ";"
    private const val KEY_SEPARATOR = ":"

    fun parse(raw: String): List<Pair<Int, RemoteButtonAction>> =
        raw.split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val keyCode = entry.substringBefore(KEY_SEPARATOR, "").toIntOrNull()
                val action = entry.substringAfter(KEY_SEPARATOR, "")
                    .let { runCatching { RemoteButtonAction.valueOf(it) }.getOrNull() }
                if (keyCode == null || action == null) null else keyCode to action
            }
            .take(MAX_SLOTS)

    fun serialize(bindings: List<Pair<Int, RemoteButtonAction>>): String =
        bindings.take(MAX_SLOTS).joinToString(ENTRY_SEPARATOR) { (keyCode, action) ->
            "$keyCode$KEY_SEPARATOR${action.name}"
        }

    fun actionFor(bindings: List<Pair<Int, RemoteButtonAction>>, keyCode: Int): RemoteButtonAction? =
        bindings.firstOrNull { it.first == keyCode }?.second
}
