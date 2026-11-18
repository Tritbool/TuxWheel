package io.github.tuxwheel

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Use phone settings" dark-mode choice used to be stored as
 * [AppCompatDelegate.MODE_NIGHT_UNSPECIFIED] (-100). `setDefaultNightMode()` only accepts
 * NO/YES/FOLLOW_SYSTEM/AUTO_* and drops anything else with a debug log, so picking that entry
 * changed nothing. The settings list and [AppConfig] must therefore agree on
 * [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM] (-1), and legacy stored values must be repaired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppConfigNightModeTest {

    private val config = AppConfig(ApplicationProvider.getApplicationContext<Application>())

    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun storedRaw(): String? =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(application)
            .getString(application.getString(R.string.day_night_theme), null)

    @Test fun `unset night mode defaults to following the system`() {
        assertThat(config.dayNightThemeMode)
            .isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun `legacy unspecified value is repaired to follow the system`() {
        config.setValue(R.string.day_night_theme, AppCompatDelegate.MODE_NIGHT_UNSPECIFIED.toString())
        assertThat(config.dayNightThemeMode)
            .isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun `garbage night mode value falls back to following the system`() {
        config.setValue(R.string.day_night_theme, "not-a-mode")
        assertThat(config.dayNightThemeMode)
            .isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun `explicit day and night choices round trip`() {
        config.dayNightThemeMode = AppCompatDelegate.MODE_NIGHT_YES
        assertThat(config.dayNightThemeMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)
        config.dayNightThemeMode = AppCompatDelegate.MODE_NIGHT_NO
        assertThat(config.dayNightThemeMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)
    }

    @Test fun `follow system is stored as a mode setDefaultNightMode actually accepts`() {
        config.dayNightThemeMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        assertThat(storedRaw()).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString())
        assertThat(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            .isNotEqualTo(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
    }
}
