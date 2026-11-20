package io.github.tuxwheel.ble

import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tuxwheel.utils.StringUtil
import java.util.Locale

data class RememberedWheel(
    val address: String,
    val name: String,
    val alias: String = ""
) {
    val displayName: String get() = alias.ifBlank {
        name.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) } ?: address
    }

    companion object {
        internal fun normalizeAddress(address: String): String? =
            address.trim().replace('-', ':').uppercase(Locale.ROOT).takeIf(StringUtil::isCorrectMac)
    }
}

internal object RememberedWheelMatcher {
    fun preferredAvailable(
        wheels: List<RememberedWheel>,
        discovered: Collection<EUCDevice>
    ): EUCDevice? {
        val byAddress = discovered.associateBy { it.address.uppercase(Locale.ROOT) }
        return wheels.firstNotNullOfOrNull { wheel ->
            byAddress[wheel.address.uppercase(Locale.ROOT)]
        }
    }
}
