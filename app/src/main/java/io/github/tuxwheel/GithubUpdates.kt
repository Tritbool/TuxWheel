package io.github.tuxwheel

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.math.BigInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class GithubRelease(val tag: String, val apkUrl: String)

internal object GithubReleases {
    const val ENDPOINT = "https://api.github.com/repos/Tritbool/TuxWheel/releases/latest"
    private val versionPattern = Regex("v?[0-9]+(?:\\.[0-9]+)*")

    private fun version(value: String): List<BigInteger>? =
        value.takeIf { it.length <= 128 && versionPattern.matches(it) }
            ?.removePrefix("v")?.split('.')?.map { it.toBigInteger() }

    fun isNewer(candidate: String, installed: String): Boolean {
        val next = version(candidate) ?: return false
        val current = version(installed) ?: return false
        for (i in 0 until maxOf(next.size, current.size)) {
            val comparison = (next.getOrNull(i) ?: BigInteger.ZERO)
                .compareTo(current.getOrNull(i) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun safeApkUrl(value: String, tag: String): Boolean {
        if (version(tag) == null) return false
        return try {
            val uri = URI(value)
            val prefix = "/Tritbool/TuxWheel/releases/download/$tag/"
            uri.scheme == "https" && uri.host == "github.com" &&
                uri.rawUserInfo == null && uri.port == -1 &&
                uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.startsWith(prefix) &&
                Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
                    .matches(uri.rawPath.removePrefix(prefix))
        } catch (_: Exception) {
            false
        }
    }

    fun parse(body: String, installed: String): GithubRelease? {
        val json = JSONObject(body)
        if (json.opt("draft") != false || json.opt("prerelease") != false) return null
        val tag = json.optString("tag_name")
        if (!isNewer(tag, installed)) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name").endsWith(".apk") && safeApkUrl(url, tag)) {
                return GithubRelease(tag, url)
            }
        }
        return null
    }

    suspend fun fetch(
        openConnection: () -> HttpURLConnection = {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        }
    ): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = openConnection()
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "TuxWheel")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Release unavailable")
                val body = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (!continuation.isActive) throw CancellationException()
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > 1_048_576) throw IOException("Release response too large")
                        output.write(buffer, 0, size)
                    }
                    output.toString("UTF-8")
                }
                if (continuation.isActive) continuation.resume(body)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal object GithubUpdateChannel {
    private val fdroidInstallers = setOf(
        "org.fdroid.fdroid", "org.fdroid.fdroid.privileged",
        "org.fdroid.basic", "org.fdroid.nearby", "com.aurora.adroid",
        "com.looker.droidify", "com.machiav3lli.fdroid", "org.frknkrc44.fdroid",
        "org.fossify.fdroid"
    )

    fun allowed(enabled: Boolean, debug: Boolean, packageName: String, installer: String?): Boolean =
        enabled && !debug && packageName == "io.github.tuxwheel" &&
            installer !in fdroidInstallers

    fun allowed(context: Context): Boolean {
        if (!allowed(BuildConfig.ENABLE_GITHUB_UPDATES, BuildConfig.DEBUG, context.packageName, null)) {
            return false
        }
        return try {
            val manager = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                manager.getInstallerPackageName(context.packageName)
            }
            allowed(true, false, context.packageName, installer)
        } catch (_: Exception) {
            false
        }
    }
}

internal class GithubUpdateChecker(
    private val preferences: SharedPreferences,
    private val installedVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend () -> String = { GithubReleases.fetch() }
) {
    suspend fun check(): GithubRelease? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val claimed = synchronized(GithubUpdateChecker::class.java) {
            val time = now()
            val previous = preferences.getLong("last_attempt", Long.MIN_VALUE)
            // A backwards clock is treated as a new day, rather than disabling checks forever.
            if (previous != Long.MIN_VALUE && time >= previous && time - previous < DAY_MILLIS) {
                false
            } else {
                preferences.edit().putLong("last_attempt", time).commit()
            }
        }
        if (!claimed) return@withContext null
        try {
            val body = fetch()
            coroutineContext.ensureActive()
            GithubReleases.parse(body, installedVersion)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
