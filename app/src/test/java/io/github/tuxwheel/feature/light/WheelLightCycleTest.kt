package io.github.tuxwheel.feature.light

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.protocols.CommandType
import org.junit.Test

class WheelLightCycleTest {
    @Test
    fun `a wheel with both commands alternates ON and OFF starting with ON`() {
        val cycle = WheelLightCycle.steps(on = true, off = true)!!
        assertThat(cycle).containsExactly(CommandType.LIGHT_ON.name, CommandType.LIGHT_OFF.name).inOrder()

        var index = WheelLightCycle.nextIndex(WheelLightCycle.NO_STEP, cycle)
        assertThat(cycle[index]).isEqualTo(CommandType.LIGHT_ON.name)
        index = WheelLightCycle.nextIndex(index, cycle)
        assertThat(cycle[index]).isEqualTo(CommandType.LIGHT_OFF.name)
        index = WheelLightCycle.nextIndex(index, cycle)
        assertThat(cycle[index]).isEqualTo(CommandType.LIGHT_ON.name)
    }

    @Test
    fun `a wheel with a single command repeats it`() {
        val cycle = WheelLightCycle.steps(on = true, off = false)!!
        assertThat(cycle).containsExactly(CommandType.LIGHT_ON.name)
        assertThat(cycle[WheelLightCycle.nextIndex(0, cycle)]).isEqualTo(CommandType.LIGHT_ON.name)
    }

    @Test
    fun `a wheel with no headlight command has no cycle`() {
        assertThat(WheelLightCycle.steps(on = false, off = false)).isNull()
    }
}
