package io.github.tuxwheel

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 31])
class PrivacyConfigurationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `merged manifest gates Internet on update channel and retains BLE and logging permissions`() {
        val permissions = application.packageManager.getPackageInfo(
            application.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toList()
        assertThat(permissions.contains(Manifest.permission.INTERNET))
            .isEqualTo(BuildConfig.ENABLE_GITHUB_UPDATES)
        assertThat(permissions).doesNotContain(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        assertThat(permissions).contains(Manifest.permission.FOREGROUND_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertThat(permissions).containsAtLeast(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            assertThat(permissions).containsAtLeast(
                Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        assertThat(permissions).doesNotContain("${application.packageName}.permission")
    }

    @Test fun `application opts out of automatic Android backup`() {
        assertThat(application.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }

    @Test fun `cloud backup and device transfer exclude every app data domain`() {
        val domains = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref"
        )
        val exclusions = mutableMapOf<String, MutableSet<String>>()
        var section = ""
        application.resources.getXml(R.xml.data_extraction_rules).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup", "device-transfer" -> {
                            section = parser.name
                            exclusions[section] = mutableSetOf()
                        }
                        "exclude" -> {
                            assertThat(parser.getAttributeValue(null, "path")).isEqualTo(".")
                            exclusions.getValue(section).add(parser.getAttributeValue(null, "domain"))
                        }
                        "include" -> throw AssertionError("Automatic data extraction must stay disabled")
                    }
                }
            }
        }
        assertThat(exclusions.keys).containsExactly("cloud-backup", "device-transfer")
        exclusions.values.forEach { assertThat(it).containsExactlyElementsIn(domains) }
    }
}
