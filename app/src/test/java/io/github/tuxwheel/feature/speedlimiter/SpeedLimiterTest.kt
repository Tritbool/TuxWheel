package io.github.tuxwheel.feature.speedlimiter

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.protocols.CommandDescriptor
import io.github.tritbool.euc.ble.protocols.CommandEncoding
import io.github.tritbool.euc.ble.protocols.CommandInput
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test

class SpeedLimiterTest {
    private val states = MutableStateFlow(BleSessionState.EMPTY)
    private val session = mockk<BleSessionViewModel>(relaxed = true)
    private val config = mockk<AppConfig>(relaxed = true)
    private var enabled = false
    private var limit = 20
    private var saved = 0
    private var wheelMax = 35
    private var writtenMax: Int? = 35

    @Before
    fun setUp() {
        every { session.sessionState } returns states
        every { session.isCommandSupported(CommandType.SET_SPEED_LIMIT) } returns true
        every { session.isCommandValueSupported(any(), any()) } returns true
        every { config.speedLimiterEnabled } answers { enabled }
        every { config.speedLimiterEnabled = any() } answers { enabled = firstArg() }
        every { config.speedLimiterSpeed } answers { limit }
        every { config.speedLimiterSpeed = any() } answers { limit = firstArg() }
        every { config.speedLimiterSavedMaxSpeed } answers { saved }
        every { config.speedLimiterSavedMaxSpeed = any() } answers { saved = firstArg() }
        every { config.speedLimiterEnabledFor(any()) } answers { enabled }
        every { config.setSpeedLimiterEnabledFor(any(), any()) } answers { enabled = secondArg() }
        every { config.speedLimiterSpeedFor(any()) } answers { limit }
        every { config.setSpeedLimiterSpeedFor(any(), any()) } answers { limit = secondArg() }
        every { config.speedLimiterSavedMaxSpeedFor(any()) } answers { saved }
        every { config.setSpeedLimiterSavedMaxSpeedFor(any(), any()) } answers { saved = secondArg() }
        every { config.wheelMaxSpeed } answers { wheelMax }
        every { config.wheelMaxSpeed = any() } answers { wheelMax = firstArg() }
        every { config.wheelCommandValues(any()) } answers {
            writtenMax?.let { mapOf(CommandType.SET_SPEED_LIMIT.name to it) }.orEmpty()
        }
    }

    private fun connect(
        manufacturer: String = "Gotway",
        reportedMax: Int? = null,
        tiltBack: Int? = null,
        speedLimit: Double? = null,
    ) {
        states.value = BleSessionState(
            connectionState = BLEConstants.ConnectionState.CONNECTED,
            commandDescriptors = listOf(
                CommandDescriptor(
                    id = CommandType.SET_SPEED_LIMIT.name,
                    name = "Speed limit",
                    description = "Set wheel speed limit",
                    input = CommandInput.Range(1, 100, 1, "km/h"),
                    encoding = CommandEncoding.AVAILABLE,
                ),
            ),
            lastData = mockk(relaxed = true) {
                every { this@mockk.manufacturer } returns manufacturer
                every { this@mockk.wheelMaxSpeed } returns reportedMax
                every { this@mockk.tiltBackSpeed } returns tiltBack
                every { this@mockk.speedLimit } returns speedLimit
                every { this@mockk.alarm1Speed } returns 20
                every { this@mockk.alarm2Speed } returns 25
                every { this@mockk.alarm3Speed } returns 30
            }
        )
    }

    @Test
    fun `enable saves user max speed and applies limit, disable restores it`() {
        connect()
        assertThat(SpeedLimiter.enable(session, config)).isTrue()
        assertThat(enabled).isTrue()
        assertThat(saved).isEqualTo(35)
        verify(exactly = 1) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(20))
        }

        assertThat(SpeedLimiter.disable(session, config)).isTrue()
        assertThat(enabled).isFalse()
        assertThat(wheelMax).isEqualTo(35)
        verify(exactly = 1) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(35))
        }
    }

    @Test
    fun `enabling twice keeps the originally saved max speed`() {
        connect()
        SpeedLimiter.enable(session, config)
        wheelMax = 20
        SpeedLimiter.enable(session, config)
        assertThat(saved).isEqualTo(35)
    }

    @Test
    fun `previous cycle parked maximum wins over the wheel stale report`() {
        connect()
        SpeedLimiter.enable(session, config)
        assertThat(saved).isEqualTo(35)

        // While the limiter is engaged the wheel's ceiling report carries the limiter speed;
        // right after a disable->re-enable that report is still the stale limit.
        states.value = states.value.copy(
            lastData = mockk(relaxed = true) {
                every { this@mockk.manufacturer } returns "Gotway"
                every { this@mockk.wheelMaxSpeed } returns 20
            }
        )
        SpeedLimiter.disable(session, config)
        SpeedLimiter.enable(session, config)

        assertThat(saved).isEqualTo(35)
    }

    @Test
    fun `a wheel reporting its ceiling parks that value when nothing is stored`() {
        connect(reportedMax = 44)
        assertThat(SpeedLimiter.enable(session, config)).isTrue()
        assertThat(saved).isEqualTo(44)
    }

    @Test
    fun `kingsong saves the wheel reported max speed`() {
        connect("Kingsong", reportedMax = 42)
        assertThat(SpeedLimiter.toggle(session, config)).isTrue()
        assertThat(saved).isEqualTo(42)
    }

    @Test
    fun `limiter cannot change state without a connected supporting wheel`() {
        assertThat(SpeedLimiter.enable(session, config)).isFalse()
        assertThat(enabled).isFalse()
        connect()
        every { session.isCommandSupported(CommandType.SET_SPEED_LIMIT) } returns false
        assertThat(SpeedLimiter.enable(session, config)).isFalse()
        verify(exactly = 0) { session.sendCommand(any<String>(), any<CommandValue>()) }
    }

    @Test
    fun `changing the limit re-applies it only while active`() {
        connect()
        SpeedLimiter.setLimit(session, config, 15)
        verify(exactly = 0) { session.sendCommand(any<String>(), any<CommandValue>()) }
        SpeedLimiter.enable(session, config)
        SpeedLimiter.setLimit(session, config, 18)
        verify(exactly = 1) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(18))
        }
        assertThat(limit).isEqualTo(18)
    }

    @Test
    fun `rejects stored values outside the current descriptor range`() {
        connect()
        enabled = true
        saved = 120

        every {
            session.isCommandValueSupported(
                CommandType.SET_SPEED_LIMIT.name,
                CommandValue.Integer(120),
            )
        } returns false

        assertThat(SpeedLimiter.disable(session, config)).isFalse()
        assertThat(enabled).isTrue()
        verify(exactly = 0) { session.sendCommand(any<String>(), any<CommandValue>()) }
    }

    // --- InMotion V9 field report: the limiter parked 55 km/h (the app-wide max speed written
    // for another wheel) instead of the 40 km/h the V9 reports, and the V9 refused the restore.

    @Test
    fun `inmotion parks the tiltback the wheel reports, never the app-wide max speed`() {
        wheelMax = 55
        writtenMax = null
        // The V9 plain realtime frame puts its dynamic limit in speedLimit: it must be ignored.
        connect("InMotion", reportedMax = 40, speedLimit = 42.29)
        assertThat(SpeedLimiter.enable(session, config)).isTrue()
        assertThat(saved).isEqualTo(40)

        assertThat(SpeedLimiter.disable(session, config)).isTrue()
        verify(exactly = 1) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(40))
        }
        verify(exactly = 0) {
            session.sendCommand(CommandType.SET_SPEED_LIMIT.name, CommandValue.Integer(55))
        }
    }

    @Test
    fun `a stale parked maximum loses against a fresh wheel report`() {
        saved = 55 // left by the faulty version
        connect("InMotion", reportedMax = 40)
        assertThat(SpeedLimiter.enable(session, config)).isTrue()
        assertThat(saved).isEqualTo(40)
    }

    @Test
    fun `a wheel report equal to the limiter speed keeps the parked maximum`() {
        saved = 40
        connect("InMotion", reportedMax = 20) // still the limiter's own ceiling
        assertThat(SpeedLimiter.enable(session, config)).isTrue()
        assertThat(saved).isEqualTo(40)
    }

    @Test
    fun `limiter refuses to engage when the user maximum is unknown`() {
        wheelMax = 55
        writtenMax = null
        connect("InMotion")
        assertThat(SpeedLimiter.enable(session, config)).isFalse()
        assertThat(enabled).isFalse()
        verify(exactly = 0) { session.sendCommand(any<String>(), any<CommandValue>()) }
    }

    @Test
    fun `disable never writes 0 km h when nothing is parked`() {
        connect()
        enabled = true
        saved = 0
        assertThat(SpeedLimiter.disable(session, config)).isFalse()
        verify(exactly = 0) { session.sendCommand(any<String>(), any<CommandValue>()) }
    }
}
