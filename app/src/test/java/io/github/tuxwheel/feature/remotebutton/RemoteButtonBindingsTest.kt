package io.github.tuxwheel.feature.remotebutton

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteButtonBindingsTest {
    @Test
    fun emptyStringCatchesNothing() {
        assertThat(RemoteButtonBindings.parse("")).isEmpty()
        assertThat(RemoteButtonBindings.serialize(emptyList())).isEmpty()
    }

    @Test
    fun roundTripsKeyCodeAndAction() {
        val bindings = listOf(
            24 to RemoteButtonAction.ToggleSpeedLimiter,
            25 to RemoteButtonAction.ToggleLight,
            126 to RemoteButtonAction.Beep,
        )
        val parsed = RemoteButtonBindings.parse(RemoteButtonBindings.serialize(bindings))
        assertThat(parsed).containsExactlyElementsIn(bindings).inOrder()
        assertThat(RemoteButtonBindings.actionFor(parsed, 24))
            .isEqualTo(RemoteButtonAction.ToggleSpeedLimiter)
        assertThat(RemoteButtonBindings.actionFor(parsed, 87)).isNull()
    }

    @Test
    fun ignoresUnknownActionsAndKeyCodes() {
        val parsed = RemoteButtonBindings.parse("24:NotAnAction;abc:Beep;25:Beep")
        assertThat(parsed).containsExactly(25 to RemoteButtonAction.Beep)
    }

    @Test
    fun capsAtFiveSlots() {
        val many = (1..8).map { it to RemoteButtonAction.Beep }
        assertThat(RemoteButtonBindings.parse(RemoteButtonBindings.serialize(many)))
            .hasSize(RemoteButtonBindings.MAX_SLOTS)
    }

    @Test
    fun firstMappingWinsForDuplicateKeys() {
        val parsed = RemoteButtonBindings.parse("24:ToggleSpeedLimiter;24:Beep")
        assertThat(RemoteButtonBindings.actionFor(parsed, 24))
            .isEqualTo(RemoteButtonAction.ToggleSpeedLimiter)
    }
}
