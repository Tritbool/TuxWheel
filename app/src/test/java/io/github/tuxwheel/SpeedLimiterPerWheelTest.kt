package io.github.tuxwheel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Limiter state (enabled, speed, parked maximum) is stored per wheel: the S18 limited at
 * 40 km/h must never announce its saved maximum as the Rocket's own when it connects next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SpeedLimiterPerWheelTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val config = AppConfig(application)
    private val s18 = "AA:BB:CC:DD:EE:01"
    private val rocket = "AA:BB:CC:DD:EE:02"

    @Before
    fun resetPreferences() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(application).edit()
            .clear().putInt("versionSettings", 1).commit()
    }

    @Test fun `a fresh wheel starts with the limiter off and the last used speed`() {
        config.speedLimiterSpeed = 40
        assertThat(config.speedLimiterEnabledFor(s18)).isFalse()
        assertThat(config.speedLimiterSpeedFor(s18)).isEqualTo(40)
    }

    @Test fun `one wheel's saved maximum never bleeds into another wheel`() {
        config.setSpeedLimiterEnabledFor(s18, true)
        config.setSpeedLimiterSavedMaxSpeedFor(s18, 40)
        config.setSpeedLimiterEnabledFor(rocket, true)
        config.setSpeedLimiterSavedMaxSpeedFor(rocket, 54)

        assertThat(config.speedLimiterSavedMaxSpeedFor(s18)).isEqualTo(40)
        assertThat(config.speedLimiterSavedMaxSpeedFor(rocket)).isEqualTo(54)
    }

    @Test fun `a wheel keeps its own limiter speed`() {
        config.setSpeedLimiterSpeedFor(s18, 40)
        config.setSpeedLimiterSpeedFor(rocket, 54)
        assertThat(config.speedLimiterSpeedFor(s18)).isEqualTo(40)
        assertThat(config.speedLimiterSpeedFor(rocket)).isEqualTo(54)
    }

    @Test fun `disabling the limiter on one wheel leaves the other engaged`() {
        config.setSpeedLimiterEnabledFor(s18, true)
        config.setSpeedLimiterEnabledFor(rocket, true)
        config.setSpeedLimiterEnabledFor(s18, false)
        assertThat(config.speedLimiterEnabledFor(s18)).isFalse()
        assertThat(config.speedLimiterEnabledFor(rocket)).isTrue()
    }

    @Test fun `an anonymous session falls back to the shared legacy values`() {
        assertThat(config.speedLimiterEnabledFor("")).isEqualTo(config.speedLimiterEnabled)
        config.speedLimiterEnabled = true
        assertThat(config.speedLimiterEnabledFor("")).isTrue()
    }
}
