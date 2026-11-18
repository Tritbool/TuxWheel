package io.github.tuxwheel.settings

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.tuxwheel.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ThirdPartyLicensesTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `dependency inventory and existing license notices are bundled for offline reading`() {
        val inventory = readAsset("third_party_licenses.md")
        assertThat(inventory).contains("# TuxWheel")
        assertThat(inventory).contains("Dependency License Report")
        assertThat(inventory).contains("euc-ble-library")
        assertThat(inventory).contains("MPAndroidChart")
        assertThat(inventory).contains("koin-android")
        assertThat(inventory).contains("richtext-ui-material3")
        assertThat(inventory).contains("richtext-commonmark")
        assertThat(inventory).contains("ui-tooling")
        assertThat(inventory).contains("androidx.annotation")
        assertThat(inventory).doesNotContain("junit")

        assertThat(readAsset("NOTICE.txt")).contains("upstream metadata discrepancy")
        assertThat(readAsset("GPL-3.0.txt")).contains("GNU GENERAL PUBLIC LICENSE")
    }

    @Test fun `license viewer title is available in English and French`() {
        mapOf(
            Locale.ENGLISH to "Third-party software and licenses",
            Locale.FRENCH to "Logiciels tiers et licences",
        ).forEach { (locale, expected) ->
            val configuration = Configuration(application.resources.configuration)
            configuration.setLocale(locale)
            val context = application.createConfigurationContext(configuration)
            assertThat(context.getString(R.string.third_party_licenses_title)).isEqualTo(expected)
        }
    }

    @Test fun `embedded license links resolve only inside the bundled license directory`() {
        assertThat(licenseAssetPath("some-library/LICENSE.txt"))
            .isEqualTo("licenses/some-library/LICENSE.txt")
        assertThat(licenseAssetPath("some-library/License%20notice.txt"))
            .isEqualTo("licenses/some-library/License notice.txt")
        listOf(
            "https://example.com/LICENSE",
            "file:///etc/passwd",
            "//example.com/LICENSE",
            "/LICENSE",
            "../NOTICE.txt",
            "%2e%2e/NOTICE.txt",
            "some-library/../../NOTICE.txt",
            "",
        ).forEach { url -> assertThat(licenseAssetPath(url)).isNull() }
    }

    private fun readAsset(name: String): String =
        application.assets.open("licenses/$name").bufferedReader().use { it.readText() }
}
