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
    fun roundTripsKeyCodeActionAndDevice() {
        val bindings = listOf(
            RemoteButtonBinding(24, RemoteButtonAction.ToggleSpeedLimiter, "abcd:1234", "AB Shutter 3"),
            RemoteButtonBinding(25, RemoteButtonAction.ToggleLight),
        )
        val parsed = RemoteButtonBindings.parse(RemoteButtonBindings.serialize(bindings))
        assertThat(parsed).containsExactlyElementsIn(bindings).inOrder()
    }

    @Test
    fun roundTripsDeviceNamesWithSeparators() {
        val bindings = listOf(
            RemoteButtonBinding(24, RemoteButtonAction.Beep, "dc:ba", "AB; Shutter: 3"),
        )
        assertThat(RemoteButtonBindings.parse(RemoteButtonBindings.serialize(bindings)))
            .isEqualTo(bindings)
    }

    @Test
    fun parsesLegacyEntriesWithoutDevice() {
        val parsed = RemoteButtonBindings.parse("24:ToggleSpeedLimiter")
        assertThat(parsed).containsExactly(
            RemoteButtonBinding(24, RemoteButtonAction.ToggleSpeedLimiter, null, null)
        )
    }

    @Test
    fun ignoresUnknownActionsAndKeyCodes() {
        val parsed = RemoteButtonBindings.parse("24:NotAnAction;abc:Beep;25:Beep")
        assertThat(parsed).containsExactly(RemoteButtonBinding(25, RemoteButtonAction.Beep))
    }

    @Test
    fun capsAtFiveSlots() {
        val many = (1..8).map { RemoteButtonBinding(it, RemoteButtonAction.Beep) }
        assertThat(RemoteButtonBindings.parse(RemoteButtonBindings.serialize(many)))
            .hasSize(RemoteButtonBindings.MAX_SLOTS)
    }

    @Test
    fun upsertAppendsANewButtonAtTheRequestedSlot() {
        val first = RemoteButtonBinding(24, RemoteButtonAction.ToggleSpeedLimiter)
        val learned = RemoteButtonBinding(25, RemoteButtonAction.ToggleSpeedLimiter)
        val (next, slot) = RemoteButtonBindings.upsert(listOf(first), 1, learned)
        assertThat(next).containsExactly(first, learned).inOrder()
        assertThat(slot).isEqualTo(1)
    }

    @Test
    fun upsertNeverWritesPastTheEndOfTheList() {
        val first = RemoteButtonBinding(24, RemoteButtonAction.ToggleSpeedLimiter)
        val learned = RemoteButtonBinding(24, RemoteButtonAction.Beep)
        val (next, slot) = RemoteButtonBindings.upsert(listOf(first), 5, learned)
        assertThat(next).containsExactly(learned)
        assertThat(slot).isEqualTo(0)
    }

    @Test
    fun upsertReplacesAnEntryWhoseKeyCollides() {
        val first = RemoteButtonBinding(24, RemoteButtonAction.Beep, "device:1", "Remote A")
        val learned = RemoteButtonBinding(24, RemoteButtonAction.ToggleLight, "device:1", "Remote A")
        val (next, slot) = RemoteButtonBindings.upsert(listOf(first), 1, learned)
        assertThat(next).containsExactly(learned)
        assertThat(slot).isEqualTo(0)
    }

    @Test
    fun firstMappingWinsForDuplicateKeys() {
        val parsed = RemoteButtonBindings.parse(
            RemoteButtonBindings.serialize(
                listOf(
                    RemoteButtonBinding(24, RemoteButtonAction.ToggleSpeedLimiter),
                    RemoteButtonBinding(24, RemoteButtonAction.Beep),
                )
            )
        )
        assertThat(RemoteButtonBindings.forKey(parsed, 24)?.action)
            .isEqualTo(RemoteButtonAction.ToggleSpeedLimiter)
    }
}
