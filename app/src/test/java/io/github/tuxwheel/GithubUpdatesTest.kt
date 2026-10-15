package io.github.tuxwheel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GithubUpdatesTest {
    private val preferences = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("update_test", Context.MODE_PRIVATE)
    private val apk = "https://github.com/Tritbool/TuxWheel/releases/download/v1.10.0/TuxWheel.apk"

    @Before fun reset() {
        preferences.edit().clear().commit()
    }

    private fun release(): JSONObject = JSONObject()
        .put("draft", false).put("prerelease", false).put("tag_name", "v1.10.0")
        .put("assets", JSONArray().put(JSONObject().put("name", "TuxWheel.apk")
            .put("browser_download_url", apk)))

    @Test fun `versions compare numeric components and optional v without integer overflow`() {
        listOf("1.10.0" to "1.9.9", "v2" to "1.99", "1.0.1" to "1",
            "999999999999999999999" to "2").forEach { (next, current) ->
            assertThat(GithubReleases.isNewer(next, current)).isTrue()
        }
        listOf("v1.0" to "1", "1" to "1.0.0", "1.9" to "1.10",
            "0.9.9" to "1.0").forEach { (next, current) ->
            assertThat(GithubReleases.isNewer(next, current)).isFalse()
        }
    }

    @Test fun `malformed and prerelease versions are ignored on either side`() {
        listOf("", "v", "V2", "1..2", "1.", ".2", "-1", " 2", "2 ",
            "v2.0-beta", "2+build", "latest", "9".repeat(129)).forEach {
            assertThat(GithubReleases.isNewer(it, "1")).isFalse()
            assertThat(GithubReleases.isNewer("3", it)).isFalse()
        }
    }

    @Test fun `stable newer release requires a safe APK asset`() {
        assertThat(GithubReleases.parse(release().toString(), "1.9")?.apkUrl).isEqualTo(apk)
        assertThat(GithubReleases.parse(release().toString(), "1.10")).isNull()
        listOf(
            release().put("draft", true), release().put("prerelease", true),
            release().put("prerelease", "false"), release().apply { remove("draft") },
            release().put("tag_name", "v2-beta"), release().apply { remove("tag_name") },
            release().apply { remove("assets") }, release().put("assets", JSONArray()),
            release().put("assets", JSONArray().put(JSONObject().put("name", "missing.apk"))),
            release().put("assets", JSONArray().put(JSONObject().put("name", "source.zip")
                .put("browser_download_url", apk)))
        ).forEach { assertThat(GithubReleases.parse(it.toString(), "1")).isNull() }
    }

    @Test fun `APK links cannot escape the HTTPS repository release path`() {
        listOf(
            apk.replace("https:", "http:"), apk.replace("github.com", "evil.example"),
            apk.replace("github.com", "github.com.evil.example"),
            apk.replace("github.com", "user@github.com"),
            apk.replace("github.com", "github.com:443"),
            apk.replace("Tritbool/TuxWheel", "other/TuxWheel"),
            apk.replace("v1.10.0/", "v1.9.0/"), apk.replace(".apk", ".zip"),
            "$apk?redirect=elsewhere", "$apk#fragment",
            apk.replace("TuxWheel.apk", "../TuxWheel.apk"),
            apk.replace("TuxWheel.apk", "%2e%2e%2fTuxWheel.apk"),
            "intent://github.com/Tritbool/TuxWheel/releases/download/v1.10.0/TuxWheel.apk"
        ).forEach { url ->
            assertThat(GithubReleases.safeApkUrl(url, "v1.10.0")).isFalse()
            val json = release()
            json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertThat(GithubReleases.parse(json.toString(), "1")).isNull()
        }
    }

    @Test fun `channel flag and package gate protect debug and independent distributions`() {
        assertThat(GithubUpdateChannel.allowed(true, false, "io.github.tuxwheel", null)).isTrue()
        assertThat(GithubUpdateChannel.allowed(false, false, "io.github.tuxwheel", null)).isFalse()
        assertThat(GithubUpdateChannel.allowed(true, true, "io.github.tuxwheel", null)).isFalse()
        assertThat(GithubUpdateChannel.allowed(true, false, "io.github.tuxwheel.debug", null)).isFalse()
        listOf("org.fdroid.fdroid", "org.fdroid.fdroid.privileged", "org.fdroid.basic",
            "com.aurora.adroid", "com.looker.droidify", "com.machiav3lli.fdroid",
            "org.frknkrc44.fdroid").forEach {
            assertThat(GithubUpdateChannel.allowed(true, false, "io.github.tuxwheel", it)).isFalse()
        }
    }

    @Test fun `failed attempts persist across checker instances and retry only after a day`() = runBlocking {
        var time = 1_000L
        var calls = 0
        fun checker() = GithubUpdateChecker(preferences, "1", { time }) {
            calls++
            throw java.io.IOException("Offline")
        }
        assertThat(checker().check()).isNull()
        time += GithubUpdateChecker.DAY_MILLIS - 1
        assertThat(checker().check()).isNull()
        assertThat(calls).isEqualTo(1)
        time++
        assertThat(checker().check()).isNull()
        assertThat(calls).isEqualTo(2)
        time = 500L
        checker().check()
        assertThat(calls).isEqualTo(3)
    }

    @Test fun `successful and malformed responses are also throttled`() = runBlocking {
        var calls = 0
        val checker = GithubUpdateChecker(preferences, "1", { 100L }) {
            calls++
            release().toString()
        }
        assertThat(checker.check()?.tag).isEqualTo("v1.10.0")
        assertThat(checker.check()).isNull()
        assertThat(calls).isEqualTo(1)
        val malformed = GithubUpdateChecker(preferences, "1", { GithubUpdateChecker.DAY_MILLIS + 100 }) {
            calls++
            "{bad json"
        }
        assertThat(malformed.check()).isNull()
        assertThat(malformed.check()).isNull()
        assertThat(calls).isEqualTo(2)
    }

    @Test fun `cancellation propagates without removing persisted attempt`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val checker = GithubUpdateChecker(preferences, "1", { 100L }) {
            started.complete(Unit)
            awaitCancellation()
        }
        var cancelled = false
        val job = launch {
            try {
                checker.check()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        started.await()
        job.cancelAndJoin()
        assertThat(cancelled).isTrue()
        assertThat(preferences.getLong("last_attempt", 0)).isEqualTo(100L)
        assertThat(checker.check()).isNull()
    }

    @Test fun `HTTP client uses timeouts no redirects and closes bounded response`() = runBlocking {
        val connection = mockk<HttpURLConnection>(relaxed = true)
        every { connection.responseCode } returns 200
        every { connection.inputStream } returns ByteArrayInputStream(release().toString().toByteArray())
        assertThat(GithubReleases.fetch { connection }).isEqualTo(release().toString())
        verify {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.disconnect()
        }
    }

    @Test fun `redirects failures and oversized responses are rejected and disconnected`() = runBlocking {
        for (code in listOf(302, 403, 500, 200)) {
            val connection = mockk<HttpURLConnection>(relaxed = true)
            every { connection.responseCode } returns code
            every { connection.inputStream } returns ByteArrayInputStream(ByteArray(1_048_577))
            var failed = false
            try {
                GithubReleases.fetch { connection }
            } catch (_: IOException) {
                failed = true
            }
            assertThat(failed).isTrue()
            verify { connection.disconnect() }
        }
    }
}
